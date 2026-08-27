# SudoOp

> Minecraft `1.21.1` · NeoForge `21.1.x` · 纯服务端模组

SudoOp 让服务器普通玩家通过一条命令自助获取**临时 OP 权限**，到期自动回收。玩家无需安装任何客户端模组，管理员也无需手动 `/op`、`/deop` 或盯着时间收权。

## 特性

- **一键授权**：玩家输入 `/sudo` 即可获取临时 OP，可选密码保护
- **自动回收**：权限到期自动撤销；服务器重启、停止时自动清理遗留的临时 OP
- **实时提醒**：全服广播授权/到期消息，玩家动作栏持续显示剩余时间
- **高度可定制**：命令名、时长、权限等级、广播文案均可在配置文件修改
- **审计日志**：授权、拒绝、到期、清理等事件全部记录在案，可追溯
- **保护真实管理员**：临时 OP 玩家无法通过 `/op`、`/deop` 发现或操作后台管理员

## 安装

1. 确保服务器运行在 **Java 21** 上
2. 将 `sudoop.jar` 放入服务器的 `mods` 目录
3. 启动服务器，首次启动会自动生成配置文件 `config/sudoop-server.toml`

## 使用方法

玩家在聊天栏输入：

```
/sudo              # 未设置密码时
/sudo <密码>       # 设置密码后
```

- 默认授予 **2 级** OP 权限，持续 **600 秒**（按真实时间计算）
- 授权仅对执行命令的玩家本人生效
- 已是后台 OP 或已持有临时 OP 的玩家会收到拒绝提示

## 配置

配置文件位于 `config/sudoop-server.toml`，修改后重启服务器生效。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `commandName` | `"sudo"` | 命令名称 |
| `durationSeconds` | `600` | 临时 OP 持续时长（秒） |
| `opLevel` | `2` | 授予的权限等级（1–4） |
| `password` | `""` | 申请所需密码，留空表示无需密码 |
| `broadcastEnabled` | `true` | 是否全服广播授权/到期 |
| `actionBarEnabled` | `true` | 是否在动作栏显示剩余时间 |
| `grantBroadcastMessage` | `"&a{player} 获取了临时OP"` | 授权广播文案 |
| `expireBroadcastMessage` | `"&e{player} 的临时OP已结束"` | 到期广播文案 |
| `actionBarMessage` | `"&b当前已获取临时OP，还剩 {minutes} 分钟"` | 动作栏文案 |

文案支持 `&` 或 `§` 颜色代码，以及 `{player}`（玩家名）、`{minutes}`（剩余分钟数）占位符。

> 密码不会出现在聊天提示、广播、日志或模组日志中。

## 审计日志

所有授权事件以 JSON 格式逐行写入服务端 `sudoop/audit.jsonl`，包含时间、玩家信息、权限等级、事件类型与结果，方便事后排查。

## 从源码构建

```bash
gradlew.bat build   # Windows
./gradlew build     # Linux / macOS
```

构建产物位于 `build/libs/sudoop-1.0.0.jar`。Gradle Toolchain 会自动选择或下载 Java 21。

## 许可证

[MIT](https://opensource.org/license/mit)
