package cn.youximi.sudoop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import cn.youximi.sudoop.mixin.StoredUserEntryAccessor;
import net.neoforged.neoforge.event.CommandEvent;

public final class TemporaryOpManager {
    private final Map<UUID, TemporaryOpRecord> records = new HashMap<>();
    private final Set<UUID> internalPermissionChanges = new HashSet<>();
    private final Set<UUID> actionBarVisible = new HashSet<>();

    private MinecraftServer server;
    private AuditLog auditLog;
    private CommandDispatcher<CommandSourceStack> registeredDispatcher;
    private long tickCounter;

    public void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == registeredDispatcher) {
            return;
        }
        String commandName = SudoOpConfig.commandNameOrNull();
        if (commandName == null) {
            return;
        }
        if (dispatcher.getRoot().getChild(commandName) != null) {
            SudoOp.LOGGER.error("无法注册 /{}：命令名已被其他命令占用。", commandName);
            return;
        }
        SudoCommand.register(dispatcher, commandName);
        registeredDispatcher = dispatcher;
    }

    public void onServerAboutToStart(MinecraftServer server) {
        this.server = server;
        this.auditLog = new AuditLog(server);
        this.records.clear();
        this.internalPermissionChanges.clear();
        this.actionBarVisible.clear();
        this.tickCounter = 0;

        registerCommand(server.getCommands().getDispatcher());

        for (TemporaryOpRecord record : auditLog.loadActive()) {
            TemporaryOpRecord previous = records.put(record.playerId(), record);
            if (previous != null) {
                SudoOp.LOGGER.warn("临时 OP 状态文件中发现玩家 {} 的重复记录，已使用最后一条记录。", record.playerName());
            }
        }

        cleanupAfterRestart(server);
        persistActiveState();
        SudoOp.LOGGER.info("SudoOp 已启动：临时 OP 不会从上次服务器会话恢复。当前清理后仍追踪 {} 条记录。", records.size());
    }

    public void onServerTick(MinecraftServer server) {
        if (this.server != server || auditLog == null) {
            return;
        }

        tickCounter++;
        long now = System.currentTimeMillis();
        for (TemporaryOpRecord record : List.copyOf(records.values())) {
            if (now >= record.expiresAt()) {
                expireRecord(server, record);
            } else if (!hasRuntimeOwnership(server, record)) {
                skipRecord(server, record, "OP 状态已变化，无法确认当前权限仍属于本模组，未自动撤销。");
            } else if (tickCounter % 20 == 0) {
                updateActionBar(server, record, false, now);
            }
        }
    }

    public void onServerStopping(MinecraftServer server) {
        if (this.server != server || auditLog == null) {
            return;
        }

        for (TemporaryOpRecord record : List.copyOf(records.values())) {
            ServerOpListEntry current = server.getPlayerList().getOps().get(record.profile());
            if (!hasRuntimeOwnership(server, record)) {
                skipRecord(server, record, "服务器停止时 OP 状态已变化，未强行撤销。");
                continue;
            }
            if (hasNonListOperatorStatus(server, record.profile())) {
                skipRecord(server, record, "服务器停止时检测到原生或后台权限来源，未改动当前权限。");
                continue;
            }

            if (revokeEntry(server, record, current)) {
                records.remove(record.playerId(), record);
                clearActionBar(record.playerId());
                auditLog.appendRecord("SERVER_STOP_CLEANUP", record, "REVOKED", "服务器停止前清理成功");
            } else {
                skipRecord(server, record, "服务器停止时无法确认或撤销 OP 条目，已停止追踪。");
            }
        }
        persistActiveState();
    }

    public void onServerStopped(MinecraftServer server) {
        if (this.server == server) {
            this.server = null;
            this.auditLog = null;
            this.registeredDispatcher = null;
            this.records.clear();
            this.internalPermissionChanges.clear();
            this.actionBarVisible.clear();
        }
    }

    public void onPlayerLoggedIn(ServerPlayer player) {
        if (!isTemporaryOp(player)) {
            return;
        }
        TemporaryOpRecord record = records.get(player.getUUID());
        if (record != null) {
            updateActionBar(player.getServer(), record, true, System.currentTimeMillis());
        }
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        actionBarVisible.remove(player.getUUID());
    }

    public void onPermissionsChanged(ServerPlayer player, int oldLevel, int newLevel) {
        if (internalPermissionChanges.contains(player.getUUID())) {
            return;
        }

        TemporaryOpRecord record = records.get(player.getUUID());
        if (record != null) {
            skipRecord(player.getServer(), record,
                    "检测到 OP 权限变化（等级 " + oldLevel + " -> " + newLevel + "），停止追踪且不改动当前权限。");
        }
    }

    public void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> parseResults = event.getParseResults();
        ServerPlayer player = parseResults.getContext().getSource().getPlayer();
        if (player == null || !isTemporaryOp(player)) {
            return;
        }

        String command = firstToken(parseResults.getReader().getString());
        try {
            CommandContext<CommandSourceStack> context = parseResults.getContext().build(parseResults.getReader().getString());
            if (!containsOpManagementNode(context)) {
                return;
            }
            if (command.equals("execute")) {
                event.setCanceled(true);
                parseResults.getContext().getSource().sendFailure(Component.literal("该 OP 管理操作不可用。"));
                if (auditLog != null) {
                    auditLog.appendRequest("PROTECTED_OP_OPERATION_BLOCKED", player.getUUID(),
                            player.getGameProfile().getName(), "BLOCKED", "临时 OP 玩家尝试通过 execute 执行 OP 管理命令，已拦截");
                }
                return;
            }
            if (!command.equals("op") && !command.equals("deop")) {
                return;
            }
            Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
            for (GameProfile target : targets) {
                if (isProtectedBackendOp(player.getServer(), target)) {
                    event.setCanceled(true);
                    parseResults.getContext().getSource().sendFailure(Component.literal("该 OP 管理操作不可用。"));
                    if (auditLog != null) {
                        auditLog.appendRequest("PROTECTED_OP_OPERATION_BLOCKED", player.getUUID(), player.getGameProfile().getName(),
                                "BLOCKED", "临时 OP 玩家尝试操作非模组 OP，已拦截");
                    }
                    return;
                }
            }
        } catch (CommandSyntaxException ignored) {
        }
    }

    private static boolean containsOpManagementNode(CommandContext<CommandSourceStack> context) {
        return context.getNodes().stream().anyMatch(node -> {
            String name = node.getNode().getName();
            return name.equals("op") || name.equals("deop");
        });
    }

    public boolean shouldBlockOpManagement(CommandSourceStack source, Collection<GameProfile> targets) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !isTemporaryOp(player)) {
            return false;
        }
        for (GameProfile target : targets) {
            if (isProtectedBackendOp(player.getServer(), target)) {
                return true;
            }
        }
        return false;
    }

    public int request(ServerPlayer player, String suppliedPassword) {
        MinecraftServer server = player.getServer();
        if (this.server != server || auditLog == null) {
            player.sendSystemMessage(Component.literal("临时 OP 服务尚未准备好，请稍后再试。"));
            return 0;
        }

        long now = System.currentTimeMillis();
        TemporaryOpRecord currentRecord = records.get(player.getUUID());
        if (currentRecord != null) {
            if (!hasRuntimeOwnership(server, currentRecord)) {
                skipRecord(server, currentRecord, "申请时发现 OP 状态已变化，停止追踪旧记录。");
            } else if (now < currentRecord.expiresAt()) {
                int minutes = remainingMinutes(currentRecord.expiresAt(), now);
                auditLog.appendRequest("REJECT_ALREADY_TEMPORARY", player.getUUID(), player.getGameProfile().getName(),
                        "REJECTED", "玩家仍处于临时 OP 状态");
                player.sendSystemMessage(Component.literal("你仍处于临时 OP 状态，还剩 " + minutes + " 分钟。"));
                return 0;
            } else {
                expireRecord(server, currentRecord);
            }
        }

        GameProfile profile = player.getGameProfile();
        PlayerList playerList = server.getPlayerList();
        if (playerList.isOp(profile)) {
            auditLog.appendRequest("REJECT_ALREADY_OP", player.getUUID(), profile.getName(),
                    "REJECTED", "玩家已经拥有原生或后台 OP");
            player.sendSystemMessage(Component.literal("你已经拥有 OP，不能获取临时 OP。"));
            return 0;
        }

        String configuredPassword = SudoOpConfig.password();
        if (configuredPassword.isEmpty()) {
            if (suppliedPassword != null && !suppliedPassword.isEmpty()) {
                auditLog.appendRequest("REJECT_PASSWORD", player.getUUID(), profile.getName(),
                        "REJECTED", "当前未设置密码，不应提供密码参数");
                player.sendSystemMessage(Component.literal("当前未设置密码，请直接使用 /" + SudoOpConfig.commandName() + "。"));
                return 0;
            }
        } else if (suppliedPassword == null || suppliedPassword.isEmpty()) {
            auditLog.appendRequest("REJECT_PASSWORD", player.getUUID(), profile.getName(),
                    "REJECTED", "未提供密码");
            player.sendSystemMessage(Component.literal("当前需要密码，请使用 /" + SudoOpConfig.commandName() + " <密码>。"));
            return 0;
        } else if (!constantTimeEquals(configuredPassword, suppliedPassword)) {
            auditLog.appendRequest("REJECT_PASSWORD", player.getUUID(), profile.getName(),
                    "REJECTED", "密码错误");
            player.sendSystemMessage(Component.literal("密码错误，未授予临时 OP。"));
            return 0;
        }

        int durationSeconds = SudoOpConfig.durationSeconds();
        int level = SudoOpConfig.opLevel();
        long expiresAt = now + durationSeconds * 1000L;
        TemporaryOpRecord record = new TemporaryOpRecord(
                UUID.randomUUID(), player.getUUID(), profile.getName(), now, expiresAt, level, false);

        records.put(record.playerId(), record);
        if (!persistActiveState()) {
            records.remove(record.playerId(), record);
            player.sendSystemMessage(Component.literal("临时 OP 申请失败，请联系管理员查看后台日志。"));
            auditLog.appendRecord("GRANT_FAILED", record, "FAILED", "无法持久化临时 OP 状态，未执行授权");
            return 0;
        }

        if (playerList.getOps().get(profile) != null || playerList.isOp(profile)) {
            records.remove(record.playerId(), record);
            persistActiveState();
            auditLog.appendRequest("REJECT_ALREADY_OP", player.getUUID(), profile.getName(),
                    "REJECTED", "授权前再次检测到原生或后台 OP，未覆盖现有权限");
            player.sendSystemMessage(Component.literal("你已经拥有 OP，不能获取临时 OP。"));
            return 0;
        }

        try {
            playerList.getOps().add(new ServerOpListEntry(profile, level, false));
        } catch (Exception exception) {
            records.remove(record.playerId(), record);
            persistActiveState();
            SudoOp.LOGGER.error("授予玩家 {} 临时 OP 时写入原生 OP 列表失败。", profile.getName(), exception);
            auditLog.appendRecord("GRANT_FAILED", record, "FAILED", "写入原生 OP 列表失败");
            player.sendSystemMessage(Component.literal("临时 OP 申请失败，请联系管理员查看后台日志。"));
            return 0;
        }

        ServerOpListEntry actualEntry = playerList.getOps().get(profile);
        if (!record.matches(actualEntry)) {
            records.remove(record.playerId(), record);
            persistActiveState();
            auditLog.appendRecord("GRANT_FAILED", record, "SKIPPED", "授权后 OP 条目无法确认归属，未继续追踪");
            player.sendSystemMessage(Component.literal("临时 OP 申请失败，当前权限状态无法安全确认。"));
            SudoOp.LOGGER.error("玩家 {} 授权后 OP 条目与预期不一致，未执行任何自动撤销。", profile.getName());
            return 0;
        }

        record.setRuntimeEntry(actualEntry);
        String opsFileDigest = digestFile(playerList.getOps().getFile());
        if (opsFileDigest == null) {
            SudoOp.LOGGER.error("无法计算玩家 {} 授权后的 ops.json 摘要；当前临时 OP 仅保留运行期间安全追踪。", profile.getName());
            auditLog.appendRecord("GRANT_STATE_UNPERSISTED", record, "GRANTED_UNTRACKED", "无法计算授权后的 ops.json 摘要，重启时不会自动撤销");
        } else {
            record.setOpsFileDigest(opsFileDigest);
            if (!persistActiveState()) {
                SudoOp.LOGGER.error("保存玩家 {} 授权后的临时 OP 摘要失败；当前权限仍保留，但重启时不会自动撤销。", profile.getName());
                auditLog.appendRecord("GRANT_STATE_UNPERSISTED", record, "GRANTED_UNTRACKED", "无法持久化授权后的 ops.json 摘要，运行期间继续按内存状态安全追踪");
            }
        }
        try {
            playerList.sendPlayerPermissionLevel(player);
        } catch (Exception exception) {
            SudoOp.LOGGER.error("刷新玩家 {} 的临时 OP 权限数据包失败，权限记录仍由状态机安全追踪。", profile.getName(), exception);
        }

        auditLog.appendRecord("GRANT_SUCCESS", record, "GRANTED", "临时 OP 授予成功");
        player.sendSystemMessage(Component.literal("临时 OP 已生效，持续 " + durationSeconds + " 秒。"));
        broadcastGrant(server, record);
        updateActionBar(server, record, true, now);
        return 1;
    }

    public boolean isTemporaryOp(ServerPlayer player) {
        TemporaryOpRecord record = records.get(player.getUUID());
        if (record == null || this.server != player.getServer()) {
            return false;
        }
        if (System.currentTimeMillis() >= record.expiresAt()) {
            expireRecord(player.getServer(), record);
            return false;
        }
        if (!hasRuntimeOwnership(player.getServer(), record)) {
            skipRecord(player.getServer(), record, "查询临时 OP 状态时发现权限归属变化，停止追踪。");
            return false;
        }
        return true;
    }

    public Suggestions filterCommandSuggestions(ServerPlayer player, String input, Suggestions suggestions) {
        if (!isTemporaryOp(player)) {
            return suggestions;
        }

        if (containsCommandToken(input, "deop")) {
            Set<String> visibleNames = visibleTemporaryNames(player.getServer());
            List<Suggestion> filtered = suggestions.getList().stream()
                    .filter(suggestion -> containsIgnoreCase(visibleNames, suggestion.getText()))
                    .toList();
            return new Suggestions(suggestions.getRange(), filtered);
        }
        if (containsCommandToken(input, "op")) {
            List<Suggestion> filtered = suggestions.getList().stream()
                    .filter(suggestion -> !isProtectedBackendOpName(player.getServer(), suggestion.getText()))
                    .toList();
            return new Suggestions(suggestions.getRange(), filtered);
        }
        return suggestions;
    }

    private void cleanupAfterRestart(MinecraftServer server) {
        List<TemporaryOpRecord> startupRecords = records.values().stream()
                .sorted(Comparator.comparingLong(TemporaryOpRecord::grantedAt).reversed())
                .toList();
        for (TemporaryOpRecord record : startupRecords) {
            ServerOpListEntry current = server.getPlayerList().getOps().get(record.profile());
            if (current == null) {
                removeStartupRecord(record, "未发现匹配的 OP 条目，未作修改。");
                continue;
            }
            if (record.opsFileDigest() == null || record.opsFileDigest().isBlank()) {
                removeStartupRecord(record, "状态记录缺少授权时的 ops.json 摘要，无法确认权限归属，未作修改。");
                continue;
            }
            String currentOpsFileDigest = digestFile(server.getPlayerList().getOps().getFile());
            if (currentOpsFileDigest == null || !MessageDigest.isEqual(
                    record.opsFileDigest().getBytes(StandardCharsets.UTF_8),
                    currentOpsFileDigest.getBytes(StandardCharsets.UTF_8))) {
                removeStartupRecord(record, "当前 ops.json 摘要与授权时不一致，无法确认权限归属，未作修改。");
                continue;
            }
            if (!record.matches(current)) {
                removeStartupRecord(record, "OP 条目等级、名称或玩家限制状态变化，无法确认归属，未作修改。");
                continue;
            }
            if (hasNonListOperatorStatus(server, record.profile())) {
                removeStartupRecord(record, "同时存在原生或后台权限来源，未作修改。");
                continue;
            }
            if (revokeEntry(server, record, current)) {
                records.remove(record.playerId(), record);
                auditLog.appendRecord("STARTUP_CLEANUP", record, "REVOKED", "服务器启动时清理上次遗留临时 OP 成功");
            } else {
                removeStartupRecord(record, "无法确认撤销结果，未继续操作。");
            }
        }
    }

    private void removeStartupRecord(TemporaryOpRecord record, String reason) {
        records.remove(record.playerId(), record);
        auditLog.appendRecord("STARTUP_CLEANUP", record, "SKIPPED", reason);
    }

    private void expireRecord(MinecraftServer server, TemporaryOpRecord record) {
        ServerOpListEntry current = server.getPlayerList().getOps().get(record.profile());
        if (!hasRuntimeOwnership(server, record)) {
            skipRecord(server, record, "到期时 OP 条目已变化或无法确认归属，未自动撤销。");
            return;
        }
        if (hasNonListOperatorStatus(server, record.profile())) {
            skipRecord(server, record, "到期时检测到原生或后台权限来源，未改动当前权限。");
            return;
        }

        if (revokeEntry(server, record, current)) {
            records.remove(record.playerId(), record);
            persistActiveState();
            clearActionBar(record.playerId());
            auditLog.appendRecord("EXPIRE_REVOKED", record, "REVOKED", "临时 OP 到期且撤销成功");
            broadcastExpire(server, record);
        } else {
            skipRecord(server, record, "到期后撤销未完成，已停止追踪且未继续修改权限。");
        }
    }

    private void skipRecord(MinecraftServer server, TemporaryOpRecord record, String reason) {
        if (!records.remove(record.playerId(), record)) {
            return;
        }
        persistActiveState();
        clearActionBar(record.playerId());
        auditLog.appendRecord("EXPIRE_SKIPPED", record, "SKIPPED", reason);
        SudoOp.LOGGER.warn("玩家 {} 的临时 OP 已停止追踪：{}", record.playerName(), reason);
    }

    private boolean hasRuntimeOwnership(MinecraftServer server, TemporaryOpRecord record) {
        ServerOpListEntry current = server.getPlayerList().getOps().get(record.profile());
        return current != null && current == record.runtimeEntry() && record.matches(current);
    }

    private boolean revokeEntry(MinecraftServer server, TemporaryOpRecord record, ServerOpListEntry expectedEntry) {
        if (expectedEntry == null || !record.matches(expectedEntry)) {
            return false;
        }

        UUID playerId = record.playerId();
        internalPermissionChanges.add(playerId);
        try {
            server.getPlayerList().deop(record.profile());
        } catch (Exception exception) {
            SudoOp.LOGGER.error("撤销玩家 {} 的临时 OP 时发生异常。", record.playerName(), exception);
            return false;
        } finally {
            internalPermissionChanges.remove(playerId);
        }

        ServerOpListEntry after = server.getPlayerList().getOps().get(record.profile());
        return after == null && !server.getPlayerList().isOp(record.profile());
    }

    private boolean hasNonListOperatorStatus(MinecraftServer server, GameProfile profile) {
        return server.isSingleplayerOwner(profile) || server.getPlayerList().isAllowCommandsForAllPlayers();
    }

    private boolean isProtectedBackendOp(MinecraftServer server, GameProfile profile) {
        TemporaryOpRecord record = records.get(profile.getId());
        if (record != null && hasRuntimeOwnership(server, record)) {
            return false;
        }
        return server.getPlayerList().getOps().get(profile) != null || server.getPlayerList().isOp(profile);
    }

    private boolean isProtectedBackendOpName(MinecraftServer server, String name) {
        for (ServerOpListEntry entry : server.getPlayerList().getOps().getEntries()) {
            GameProfile profile = ((StoredUserEntryAccessor<GameProfile>)entry).sudoop$getUser();
            if (profile != null && profile.getName().equalsIgnoreCase(name)) {
                TemporaryOpRecord record = records.get(profile.getId());
                return record == null || !hasRuntimeOwnership(server, record);
            }
        }
        return false;
    }

    private Set<String> visibleTemporaryNames(MinecraftServer server) {
        Set<String> names = new HashSet<>();
        for (TemporaryOpRecord record : List.copyOf(records.values())) {
            if (hasRuntimeOwnership(server, record)) {
                names.add(record.playerName());
            }
        }
        return names;
    }

    private void updateActionBar(MinecraftServer server, TemporaryOpRecord record, boolean force, long now) {
        ServerPlayer player = server.getPlayerList().getPlayer(record.playerId());
        if (player == null || now >= record.expiresAt()) {
            return;
        }

        if (!SudoOpConfig.actionBarEnabled()) {
            clearActionBar(record.playerId());
            return;
        }

        int minutes = remainingMinutes(record.expiresAt(), now);
        if (!force && now - record.lastActionBarSentAt() < 1000L && minutes == record.lastActionBarMinutes()) {
            return;
        }
        player.displayClientMessage(render(SudoOpConfig.actionBarMessage(), record.playerName(), minutes), true);
        record.setLastActionBarSentAt(now);
        record.setLastActionBarMinutes(minutes);
        actionBarVisible.add(record.playerId());
    }

    private void clearActionBar(UUID playerId) {
        if (!actionBarVisible.remove(playerId) || server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.displayClientMessage(Component.empty(), true);
        }
    }

    private void broadcastGrant(MinecraftServer server, TemporaryOpRecord record) {
        if (SudoOpConfig.broadcastEnabled()) {
            server.getPlayerList().broadcastSystemMessage(
                    render(SudoOpConfig.grantBroadcastMessage(), record.playerName(),
                            remainingMinutes(record.expiresAt(), record.grantedAt())), false);
        }
    }

    private void broadcastExpire(MinecraftServer server, TemporaryOpRecord record) {
        if (SudoOpConfig.broadcastEnabled()) {
            server.getPlayerList().broadcastSystemMessage(
                    render(SudoOpConfig.expireBroadcastMessage(), record.playerName(), 0), false);
        }
    }

    private boolean persistActiveState() {
        if (auditLog == null) {
            return false;
        }
        try {
            auditLog.saveActive(records.values());
            return true;
        } catch (Exception exception) {
            SudoOp.LOGGER.error("保存 SudoOp 临时 OP 状态失败。", exception);
            return false;
        }
    }

    private static int remainingMinutes(long expiresAt, long now) {
        long remaining = Math.max(1L, expiresAt - now);
        return (int)Math.max(1L, (remaining + 59_999L) / 60_000L);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String digestFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            SudoOp.LOGGER.error("读取原生 OP 列表并计算摘要失败。", exception);
            return null;
        }
    }

    private static boolean containsIgnoreCase(Collection<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static String firstToken(String input) {
        String value = input == null ? "" : input.strip();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        int separator = value.indexOf(' ');
        String token = separator < 0 ? value : value.substring(0, separator);
        return token.toLowerCase(Locale.ROOT);
    }

    private static boolean containsCommandToken(String input, String target) {
        String value = input == null ? "" : input.strip();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        for (String token : value.split("\\s+")) {
            if (token.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static Component render(String template, String player, Integer minutes) {
        String value = template.replace("{player}", player);
        value = value.replace("{minutes}", minutes == null ? "" : String.valueOf(minutes));

        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character == '&' || character == '\u00a7') && index + 1 < value.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(Character.toLowerCase(value.charAt(index + 1)));
                if (formatting != null) {
                    appendStyledText(result, text, style);
                    style = style.applyLegacyFormat(formatting);
                    index++;
                    continue;
                }
            }
            text.append(character);
        }
        appendStyledText(result, text, style);
        return result;
    }

    private static void appendStyledText(MutableComponent result, StringBuilder text, Style style) {
        if (text.length() > 0) {
            result.append(Component.literal(text.toString()).withStyle(style));
            text.setLength(0);
        }
    }
}
