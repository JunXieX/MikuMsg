# MikuMsg

Minecraft 代理端全网玩家消息同步插件。由 Velocity 代理端统一播报进入 / 退出 / 切换服务器消息，
配合 Paper / Folia 后端插件抑制各子服务器原生的进出消息，实现全网消息一致、不重复刷屏。

## 功能

- **消息同步**：代理端监听玩家进入、退出、切换服务器事件，格式化后一次性广播给全网所有在线玩家，
  无需在每个后端子服务器安装同步插件。
- **后端抑制**：后端插件在最高优先级将原生 `join` / `quit` 消息置空，避免与代理广播重复。
- **MikuAuth 联动**（可选）：未完成登录的玩家（含压测假玩家）的进出 / 切换消息不广播；
  登录完成被送往正式服时按「进入」补报一次。
- **MikuVanish 联动**（可选）：隐身玩家的进出 / 切换消息一律不广播；进入隐身补发一条离线消息，
  解除隐身补发一条进入消息（由状态变更事件驱动，无轮询）。
- **可配置**：三类消息各自可开关、可自定义 MiniMessage 格式，支持服务器显示名别名。

## 模块

| 模块 | 运行环境 | 说明 |
| --- | --- | --- |
| `velocity/` | Velocity 4.x（Java 25） | 代理端，全网消息广播与命令 |
| `paper/` | Paper / Folia（Java 25） | 后端子服务器，抑制原生进出消息 |

## 安装

1. 将 `MikuMsg-Velocity-<版本>.jar` 放入代理服务器的 `plugins/` 目录。
2. 将 `MikuMsg-Paper-<版本>.jar` 放入**每一个**后端子服务器的 `plugins/` 目录。
3. 重启服务端，插件会在首次启动时生成默认配置。

MikuAuth、MikuVanish 均为可选联动，未安装时对应过滤自动关闭，不影响基本功能。

## 命令

代理端：

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mmsg fj <玩家名> [服务器]` | 全网广播一条虚假「进入」消息 | `mikumsg.fake` |
| `/mmsg fl <玩家名> [服务器]` | 全网广播一条虚假「退出」消息 | `mikumsg.fake` |
| `/mmsg reload` | 重载代理配置，并通知所有在线后端重载配置 | `mikumsg.reload` |

后端：

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mmsg reload` | 重载本服配置 | `mikumsg.reload` |

> 玩家在游戏内输入的 `/mmsg` 由代理端命令拦截处理，后端命令主要用于后端控制台。
> 无玩家在线的后端收不到代理的 reload 通知，需在其控制台执行 `/mmsg reload`。

## 配置

代理端 `plugins/MikuMsg/config.properties`：

```properties
join.enabled=true
join.format=<green>+ <white>%player% <gray>进入了 <aqua>%server%
leave.enabled=true
leave.format=<red>- <white>%player% <gray>离开了 <aqua>%server%
change.enabled=true
change.format=<yellow>⇄ <white>%player% <gray>从 <aqua>%old_server% <gray>切换到 <aqua>%server%
auth-server=limbo
#alias.survival=生存服
```

文本采用 MiniMessage 标签格式，占位符：`%player%` 玩家名、`%server%` 当前服务器、`%old_server%` 切换前的服务器。

后端 `plugins/MikuMsg/config.yml`：

```yaml
suppress:
  join-message: true
  quit-message: true
```

## 构建

构建统一由 GitHub Actions 完成：

- 推送 `main` 分支：执行编译校验与打包，产物作为构建工件上传。
- 推送 `v*` 标签：执行构建并创建对应的正式 Release，附上两个 jar。

工作流：[.github/workflows/build.yml](.github/workflows/build.yml)

本地编译校验（需要 JDK 25）：

```bash
# 先准备 velocity/libs 下的两个编译期 API 依赖，来源与校验值见 velocity/build.gradle.kts 注释
gradle build
```

产物输出在仓库根目录：`MikuMsg-Paper-<版本>.jar`、`MikuMsg-Velocity-<版本>.jar`。

## 作者

JunXieX