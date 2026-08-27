package cn.youximi.sudoop;

import java.util.function.Supplier;
import java.util.regex.Pattern;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SudoOpConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public static final ModConfigSpec.ConfigValue<String> COMMAND_NAME = BUILDER
            .comment("Command name. Lowercase letters, digits and underscores are supported.")
            .define("commandName", "sudo", SudoOpConfig::isValidCommandName);

    public static final ModConfigSpec.IntValue DURATION_SECONDS = BUILDER
            .comment("Temporary OP duration in real-world seconds.")
            .defineInRange("durationSeconds", 600, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue OP_LEVEL = BUILDER
            .comment("Temporary OP permission level, from 1 through 4.")
            .defineInRange("opLevel", 2, 1, 4);

    public static final ModConfigSpec.ConfigValue<String> PASSWORD = BUILDER
            .comment("Password required by the command. Empty means no password is required.")
            .define("password", "");

    public static final ModConfigSpec.BooleanValue BROADCAST_ENABLED = BUILDER
            .comment("Broadcast grant and expiration messages to the whole server.")
            .define("broadcastEnabled", true);

    public static final ModConfigSpec.BooleanValue ACTION_BAR_ENABLED = BUILDER
            .comment("Show the remaining temporary OP time in the player's action bar.")
            .define("actionBarEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> GRANT_BROADCAST_MESSAGE = BUILDER
            .comment("Grant broadcast. Supports & color codes and {player}.")
            .define("grantBroadcastMessage", "&a{player} 获取了临时OP");

    public static final ModConfigSpec.ConfigValue<String> EXPIRE_BROADCAST_MESSAGE = BUILDER
            .comment("Expiration broadcast. Supports & color codes and {player}.")
            .define("expireBroadcastMessage", "&e{player} 的临时OP已结束");

    public static final ModConfigSpec.ConfigValue<String> ACTION_BAR_MESSAGE = BUILDER
            .comment("Action bar message. Supports & color codes, {player} and {minutes}.")
            .define("actionBarMessage", "&b当前已获取临时OP，还剩 {minutes} 分钟");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SudoOpConfig() {
    }

    public static String commandName() {
        String value = commandNameOrNull();
        return value == null ? "sudo" : value;
    }

    public static String commandNameOrNull() {
        String value;
        try {
            value = COMMAND_NAME.get();
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("before config is loaded")) {
                return null;
            }
            SudoOp.LOGGER.error("读取配置 commandName 失败，使用安全默认值 sudo。", exception);
            return "sudo";
        } catch (Exception exception) {
            SudoOp.LOGGER.error("读取配置 commandName 失败，使用安全默认值 sudo。", exception);
            return "sudo";
        }
        if (value == null) {
            SudoOp.LOGGER.error("配置 commandName 读取结果为空，使用安全默认值 sudo。");
            return "sudo";
        }
        if (COMMAND_NAME_PATTERN.matcher(value).matches()) {
            return value;
        }
        SudoOp.LOGGER.error("配置 commandName 的值 {} 非法，使用安全默认值 sudo。", value);
        return "sudo";
    }

    public static int durationSeconds() {
        int value = safe("durationSeconds", DURATION_SECONDS, 600);
        return value > 0 ? value : invalidInt("durationSeconds", value, 600);
    }

    public static int opLevel() {
        int value = safe("opLevel", OP_LEVEL, 2);
        return value >= 1 && value <= 4 ? value : invalidInt("opLevel", value, 2);
    }

    public static String password() {
        return safeSecret("password", PASSWORD, "");
    }

    public static boolean broadcastEnabled() {
        return safe("broadcastEnabled", BROADCAST_ENABLED, true);
    }

    public static boolean actionBarEnabled() {
        return safe("actionBarEnabled", ACTION_BAR_ENABLED, true);
    }

    public static String grantBroadcastMessage() {
        return safe("grantBroadcastMessage", GRANT_BROADCAST_MESSAGE, "&a{player} 获取了临时OP");
    }

    public static String expireBroadcastMessage() {
        return safe("expireBroadcastMessage", EXPIRE_BROADCAST_MESSAGE, "&e{player} 的临时OP已结束");
    }

    public static String actionBarMessage() {
        return safe("actionBarMessage", ACTION_BAR_MESSAGE, "&b当前已获取临时OP，还剩 {minutes} 分钟");
    }

    private static boolean isValidCommandName(Object value) {
        return value instanceof String string && COMMAND_NAME_PATTERN.matcher(string).matches();
    }

    private static int invalidInt(String key, int value, int fallback) {
        SudoOp.LOGGER.error("配置 {} 的值 {} 非法，使用安全默认值 {}。", key, value, fallback);
        return fallback;
    }

    private static <T> T safe(String key, Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            if (value == null) {
                throw new IllegalStateException("null value");
            }
            return value;
        } catch (Exception exception) {
            SudoOp.LOGGER.error("读取配置 {} 失败，使用安全默认值。", key, exception);
            return fallback;
        }
    }

    private static String safeSecret(String key, Supplier<String> supplier, String fallback) {
        try {
            String value = supplier.get();
            return value == null ? fallback : value;
        } catch (Exception exception) {
            SudoOp.LOGGER.error("读取配置 {} 失败，使用安全默认值。", key, exception);
            return fallback;
        }
    }
}
