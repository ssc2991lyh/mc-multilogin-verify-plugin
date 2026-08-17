# MCMultiLoginCompat

> Bukkit / Purpur 服务端的多账户正版登录兼容 + QQ 绑定验证码门禁插件。

## 项目简介

这个项目解决了 bukkit 类服务器无法使正版验证与单/多个 Yggdrasil 平台同时验证登录、以及服务器恶意账号管理类的问题。该项目通过对内部校验机制进行修改，使其支持正版用户 + 多 api 验证用户进入的同时不必启用服务器离线模式、使用 QQ 号与玩家账号相绑定来预防批量恶意离线用户对服务器造成的损害等。

- **多账户登录兼容**：接管 `hasJoinedServer`，让正版玩家与接入 LittleSkin / 自建 Yggdrasil（authlib-injector）的玩家可同时进服，**无需开启离线模式**。
- **QQ 绑定验证码门禁（mcverify）**：未验证玩家进服即被 `/kick`，踢出原因含「验证 XXXXXX」；玩家在绑定 QQ 群发送「验证 XXXXXX」后由 MC 服标记已验证，下次进服直接放行并收到「欢迎回来」。

## 特性

- 正版 + 多 Yggdrasil API 并存登录（`config.yml` 外部服务 或 `config.json` 自包含，按需选择）。
- 进服自动生成验证码、踢出提示直达、已验证欢迎、群内进出服播报。
- 验证状态（verify.json）**只保存在 MC 服本地**，适合 MC 服与 AstrBot跨机部署。
- 两种 QQ 接码通道，按需选择：**OneBot 直连** 或 **AstrBot 插件转发**。

## 架构（两套现成方案合并后的最终形态）

```
MC 服 (Linux)  MCMultiLoginCompat (本插件)
  ├─ 会话代理 / Netty 拦截：正版 + 多 Yggdrasil 登录兼容
  ├─ VerifyGate：进服查 verify.json
  │     ├─ 已验证 → 欢迎回来 + 进出服播报
  │     └─ 未验证 → 生成码写 verify.json → 下一 tick /kick（原因含「验证 XXXXXX」）
  ├─ verify.json：仅存在于 MC 服本地（插件数据目录）
  └─ 验证入站（二选一，由 verifychannel 决定）
        ├─ onebot  ：自带 HTTP 入站监听，接收 OneBot 群消息 webhook
        └─ astrbot ：/multilogin verify <code> 指令

QQ 群「验证 XXXXXX」
  ├─ onebot 通道：OneBot 直接 webhook 推到 MC 服 → 标记 → OneBot 回群
  └─ astrbot 通道：astrbot_plugin_mc_verify 收到 → 经 mcverify command/execute
                   → MC 服执行 /multilogin verify <code> → 标记 → 回调回群
```

> mcverify（AstrBot 端）只做「转发 + 收回调」，**不写 json、不轮询、不冻结**，所有状态都在 MC 服。

## 登录验证模式（二选一，也可同时配）

`hasJoined` 验证本身有两种来源，**满足任一即接管登录，都不满足则安全 fail-open**：

| 模式 | 配置位置 | 说明 |
| --- | --- | --- |
| **外部模式** | `config.yml` 的 `api-url` | 对接你已有的 Node 版 `MC-MultiLogin-service`（HTTP 请求）。 |
| **自包含模式** | `config.json` 的 `method[]` | 验证逻辑内嵌在插件进程内，无需外部 HTTP 服务。 |
| **都不配** | — | 插件只注册指令、不接管登录，保持原版验证，避免服主误以为在验证。 |

> 默认 `config.json` 的 `method[]` 为空数组，首次安装即是 fail-open；
> 需要自包含验证时手动填入 method 对象，然后重启或 `/multilogin reload`。

## 验证通道配置（verifyconfig.json）

| 字段 | 说明 |
| --- | --- |
| `verifychannel` | `onebot` / `astrbot` / `both` |
| `astrbottoken` | mcverify 的 token（astrbot/both 用；留空则提示从 `plugins/MCMultiLogin/config.yml` 填写复制） |
| `onebot_http_url` | OneBot HTTP 地址（onebot/both 用） |
| `onebot_token` | OneBot access_token（可为空） |
| `verify_webhook_port` | OneBot 把群消息推到本插件的端口（onebot/both 用，默认 8766） |

- `onebot` 时不读 `astrbottoken`；`astrbot` 时不读 `onebot_http_url` / `onebot_token`。
- 其余开关（kick / 欢迎 / 播报等）均为 `true/false`，见 `verifyconfig.json` 默认模板。

### AstrBot 端插件（astrbot 通道）

仓库： [`astrbot_plugin_mc_verify`](https://github.com/ssc2991lyh/astrbot_plugin_mc_verify)。需配置：

| 字段 | 说明 |
| --- | --- |
| `mc_host` | mcverify 地址（同机 127.0.0.1，否则 MC 服 IP） |
| `mc_rest_port` | mcverify 端口（默认 8765） |
| `astrbottoken` | 与 mcverify 配置的相同 token |
| `group_id` | 监听「验证 XXXX」的群号（留空=所有群） |

## 构建

```bash
# 需要 JDK 8+（产物目标字节码 Java 8，适配 1.8 ~ 最新 Purpur）
./gradlew build
# 产物：build/libs/mc-multilogin-compat-bukkit-<version>.jar
```

将 JAR 放入服务端 `plugins/` 重启即可。首次运行自动生成 `config.yml` / `config.json` / `verifyconfig.json`。

## 开源协议

本项目以 **GNU Affero General Public License v3.0 (AGPL-3.0)** 发布。详见 [LICENSE](./LICENSE)。

配套自研的 `astrbot_plugin_mc_verify` 同样以 AGPL-3.0 发布。
