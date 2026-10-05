# MikuMsg

作者：JunXieX
MikuMC系列插件交流群：1105054380
非开源项目，请勿二次分发

Minecraft 代理端全网玩家消息同步插件。代理端（Velocity）统一播报玩家的进入 / 退出 / 切换服务器消息，
后端（Paper / Folia）抑制各子服务器原生的进出消息，实现全网消息一致、不重复刷屏。

## 运行环境

- 代理：Velocity 4.x
- 后端：Paper / Folia（`paper-api 26.3`，`folia-supported: true`）
- JDK 25

## 功能

- **全网消息同步**：玩家的进入、退出、切换服务器消息由代理统一广播，全网所有在线玩家都能看到，
  不需要在每个后端子服务器再装同步插件。
- **后端原生消息抑制**：后端插件关掉各子服务器自带的进入 / 退出消息，避免与代理广播重复刷屏。
- **过滤未登录玩家**（配合 MikuAuth）：尚未完成登录的玩家，其进出 / 切换消息不广播。
- **尊重隐身状态**（配合 MikuVanish）：隐身玩家的进出 / 切换消息一律不广播；进入隐身补发一条离线消息，
  解除隐身补发一条进入消息。
- **可自定义**：三类消息各自可开关、可自定义显示格式（MiniMessage），并支持为服务器配置显示名别名。

## 安装

| 文件 | 放到哪 |
| --- | --- |
| `MikuMsg-Velocity-<版本>.jar` | 代理服务器的 `plugins/` 目录 |
| `MikuMsg-Paper-<版本>.jar` | **每一个**后端子服务器的 `plugins/` 目录 |

放好后重启服务端，首次启动会在 `plugins/MikuMsg/` 生成默认配置。

## 前置依赖

下表两个插件均为**可选**联动，未安装时对应过滤自动关闭，不影响基本功能。

| 依赖 | 作用 |
| --- | --- |
| MikuAuth 3.6.0+ | 过滤未登录玩家的进出 / 切换消息 |
| MikuVanish 1.3.0+ | 隐藏隐身玩家的进出 / 切换消息，并在隐身状态翻转时补发消息 |

## 命令与权限

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

> 玩家在游戏内输入 `/mmsg` 由代理端拦截处理，后端命令主要用于后端控制台。
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

`auth-server` 填 MikuAuth 的认证服名字。文本采用 MiniMessage 标签格式，
占位符：`%player%` 玩家名、`%server%` 当前服务器、`%old_server%` 切换前的服务器。

后端 `plugins/MikuMsg/config.yml`：

```yaml
suppress:
  join-message: true
  quit-message: true
```