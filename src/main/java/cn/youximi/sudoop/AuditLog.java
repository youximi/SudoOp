package cn.youximi.sudoop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;

public final class AuditLog {
    private static final Gson GSON = new GsonBuilder().create();

    private final Path activeFile;
    private final Path auditFile;

    public AuditLog(MinecraftServer server) {
        Path directory = server.getFile("sudoop");
        this.activeFile = directory.resolve("active-temporary-ops.json");
        this.auditFile = directory.resolve("audit.jsonl");
    }

    public List<TemporaryOpRecord> loadActive() {
        if (!Files.isRegularFile(activeFile)) {
            return new ArrayList<>();
        }

        try (BufferedReader reader = Files.newBufferedReader(activeFile, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IOException("active state root is not an object");
            }
            JsonArray records = root.getAsJsonObject().getAsJsonArray("records");
            if (records == null) {
                return new ArrayList<>();
            }

            List<TemporaryOpRecord> result = new ArrayList<>();
            for (JsonElement element : records) {
                try {
                    result.add(TemporaryOpRecord.fromJson(element.getAsJsonObject()));
                } catch (Exception exception) {
                    SudoOp.LOGGER.error("读取临时 OP 状态文件时跳过损坏记录。", exception);
                }
            }
            return result;
        } catch (Exception exception) {
            SudoOp.LOGGER.error("读取临时 OP 状态文件失败，将不恢复旧状态，仅执行可确认的清理。", exception);
            return new ArrayList<>();
        }
    }

    public void saveActive(Collection<TemporaryOpRecord> records) throws IOException {
        Files.createDirectories(activeFile.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonArray array = new JsonArray();
        records.stream()
                .sorted(Comparator.comparing(record -> record.playerId().toString()))
                .map(TemporaryOpRecord::toJson)
                .forEach(array::add);
        root.add("records", array);

        Path temporaryFile = activeFile.resolveSibling(activeFile.getFileName() + ".tmp");
        Files.writeString(temporaryFile, GSON.toJson(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporaryFile, activeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, activeFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void appendRecord(String event, TemporaryOpRecord record, String result, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", Instant.now().toString());
        json.addProperty("timestampMillis", System.currentTimeMillis());
        json.addProperty("event", event);
        json.addProperty("result", result);
        json.addProperty("reason", reason);
        json.addProperty("grantId", record.grantId().toString());
        json.addProperty("uuid", record.playerId().toString());
        json.addProperty("player", record.playerName());
        json.addProperty("grantedAt", record.grantedAt());
        json.addProperty("expiresAt", record.expiresAt());
        json.addProperty("level", record.level());
        if (record.opsFileDigest() != null) {
            json.addProperty("opsFileDigest", record.opsFileDigest());
        }
        append(json);
    }

    public void appendRequest(String event, UUID playerId, String playerName, String result, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", Instant.now().toString());
        json.addProperty("timestampMillis", System.currentTimeMillis());
        json.addProperty("event", event);
        json.addProperty("result", result);
        json.addProperty("reason", reason);
        json.addProperty("uuid", playerId.toString());
        json.addProperty("player", playerName);
        append(json);
    }

    private synchronized void append(JsonObject json) {
        try {
            Files.createDirectories(auditFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(auditFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                writer.write(GSON.toJson(json));
                writer.newLine();
            }
        } catch (IOException exception) {
            SudoOp.LOGGER.error("写入 SudoOp 审计日志失败。", exception);
        }
    }
}
