package cn.youximi.sudoop;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

public final class SudoOpMessages {
    private static final Map<String, String> EN_US = loadLanguage("en_us");
    private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();

    static {
        if (EN_US.isEmpty()) {
            throw new IllegalStateException("Missing SudoOp en_us translations");
        }
        LANGUAGES.put("en_us", EN_US);
    }

    private SudoOpMessages() {
    }

    public static MutableComponent forPlayer(ServerPlayer player, String key, Object... args) {
        String locale = player == null ? "en_us" : player.clientInformation().language().toLowerCase(Locale.ROOT);
        Map<String, String> translations = locale.matches("[a-z_]{2,16}")
                ? LANGUAGES.computeIfAbsent(locale, SudoOpMessages::loadLanguage)
                : EN_US;
        String fallback = translations.getOrDefault(key, EN_US.get(key));
        if (fallback == null) {
            throw new IllegalArgumentException("Unknown SudoOp translation key: " + key);
        }
        return Component.translatableWithFallback(key, fallback, args);
    }

    private static Map<String, String> loadLanguage(String locale) {
        String path = "/assets/sudoop/lang/" + locale + ".json";
        try (InputStream stream = SudoOpMessages.class.getResourceAsStream(path)) {
            if (stream == null) {
                return Map.of();
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> translations = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                translations.put(entry.getKey(), entry.getValue().getAsString());
            }
            return Map.copyOf(translations);
        } catch (IOException | RuntimeException exception) {
            SudoOp.LOGGER.error("读取语言文件 {} 失败。", path, exception);
            return Map.of();
        }
    }
}
