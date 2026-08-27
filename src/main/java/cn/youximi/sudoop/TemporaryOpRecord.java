package cn.youximi.sudoop;

import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import net.minecraft.server.players.ServerOpListEntry;
import cn.youximi.sudoop.mixin.StoredUserEntryAccessor;

public final class TemporaryOpRecord {
    private final UUID grantId;
    private final UUID playerId;
    private final String playerName;
    private final long grantedAt;
    private final long expiresAt;
    private final int level;
    private final boolean bypassesPlayerLimit;
    private String opsFileDigest;

    private ServerOpListEntry runtimeEntry;
    private long lastActionBarSentAt;
    private int lastActionBarMinutes;

    public TemporaryOpRecord(
            UUID grantId,
            UUID playerId,
            String playerName,
            long grantedAt,
            long expiresAt,
            int level,
            boolean bypassesPlayerLimit
    ) {
        this.grantId = grantId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
        this.level = level;
        this.bypassesPlayerLimit = bypassesPlayerLimit;
    }

    public static TemporaryOpRecord fromJson(JsonObject json) {
        return new TemporaryOpRecord(
                UUID.fromString(json.get("grantId").getAsString()),
                UUID.fromString(json.get("uuid").getAsString()),
                json.get("player").getAsString(),
                json.get("grantedAt").getAsLong(),
                json.get("expiresAt").getAsLong(),
                json.get("level").getAsInt(),
                json.has("bypassesPlayerLimit") && json.get("bypassesPlayerLimit").getAsBoolean(),
                json.has("opsFileDigest") ? json.get("opsFileDigest").getAsString() : null
        );
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("grantId", grantId.toString());
        json.addProperty("uuid", playerId.toString());
        json.addProperty("player", playerName);
        json.addProperty("grantedAt", grantedAt);
        json.addProperty("expiresAt", expiresAt);
        json.addProperty("level", level);
        json.addProperty("bypassesPlayerLimit", bypassesPlayerLimit);
        if (opsFileDigest != null) {
            json.addProperty("opsFileDigest", opsFileDigest);
        }
        json.addProperty("status", "ACTIVE");
        return json;
    }

    private TemporaryOpRecord(
            UUID grantId,
            UUID playerId,
            String playerName,
            long grantedAt,
            long expiresAt,
            int level,
            boolean bypassesPlayerLimit,
            String opsFileDigest
    ) {
        this(grantId, playerId, playerName, grantedAt, expiresAt, level, bypassesPlayerLimit);
        this.opsFileDigest = opsFileDigest;
    }

    public GameProfile profile() {
        return new GameProfile(playerId, playerName);
    }

    public boolean matches(ServerOpListEntry entry) {
        GameProfile profile = entry == null ? null : ((StoredUserEntryAccessor<GameProfile>)entry).sudoop$getUser();
        if (profile == null) {
            return false;
        }
        return playerId.equals(profile.getId())
                && playerName.equals(profile.getName())
                && level == entry.getLevel()
                && bypassesPlayerLimit == entry.getBypassesPlayerLimit();
    }

    public UUID grantId() {
        return grantId;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public long grantedAt() {
        return grantedAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public int level() {
        return level;
    }

    public boolean bypassesPlayerLimit() {
        return bypassesPlayerLimit;
    }

    public String opsFileDigest() {
        return opsFileDigest;
    }

    public void setOpsFileDigest(String opsFileDigest) {
        this.opsFileDigest = opsFileDigest;
    }

    public ServerOpListEntry runtimeEntry() {
        return runtimeEntry;
    }

    public void setRuntimeEntry(ServerOpListEntry runtimeEntry) {
        this.runtimeEntry = runtimeEntry;
    }

    public long lastActionBarSentAt() {
        return lastActionBarSentAt;
    }

    public void setLastActionBarSentAt(long lastActionBarSentAt) {
        this.lastActionBarSentAt = lastActionBarSentAt;
    }

    public int lastActionBarMinutes() {
        return lastActionBarMinutes;
    }

    public void setLastActionBarMinutes(int lastActionBarMinutes) {
        this.lastActionBarMinutes = lastActionBarMinutes;
    }
}
