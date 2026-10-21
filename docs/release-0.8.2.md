# 0.8.2：删除长枪斜背快跑，恢复普通双手持枪

目标环境为 Minecraft **1.21.11 / NeoForge 21.11.45 / Java 21**，版本 **0.8.2**，网络协议 **10**。按用户要求撤掉长枪快跑时的背枪、斜背与自由左臂覆盖，恢复原有身前双手握枪和下肢跑动。

**当前验收状态：通过并已部署。** 最终完整 build 耗时 **1 分 53 秒**，**70 个自定义测试任务**及 `compileSmokeJava` 通过；相同发行 JAR 的 DISPLAY5 实机 **7 场景 / 21 项检查**通过。采得 **1436 个原生帧 / 39.62934199 Hz**，7 场景原 PNG 已逐一目视核对，全部源 PNG 独立校验后以 2 线程编码为 **1088 帧 / 30 FPS / 36.266667 秒**录像，无动作插帧。VerifyOnly 和 **2026-10-21 16:57** 官方实例安装均已完成，安装 SHA 与最终构建、实机一致。

## 行为与变更范围

删除 `SpearRunCarry` 及其专用状态、渲染覆盖和独立测试任务。快跑继续使用原有长枪动作采样和双手握持求解，双腿按真实移动跑动；普通行走、停止后改瞄准、转向和跑动攻击均沿用既有握持链。发布包中原有的 `SpearRunCarry`、`SpearRunCarry$State` 和 `SpearRunCarry$Measurements` 三个类均已消失，`AirInertia`、`SpearGrip` 和 `PlayerAnimations` 保留。

保留攻击、伤害、资源、输入规则、原版冲量移动、H 手动检视和全部动作素材。0.8.0 的空中惯性层继续保留，普通跑跳从离地基础姿势响应实际运动变化，落地清零。与 0.8.1 的归档比较确认 **148 项素材及全部服务端类逐字节不变**，变化限于版本元数据、`PlayerAnimations`、`SpearGrip` 及其相关内部类；协议注册类哈希保持一致。16 招技能仍为 [0.8.0 设计提案](武器技能设计-0.8.0.md)。0.8.0 与 0.8.1 的发行记录保留为历史，当前操作以本版本双手持枪为准。

## 实机验收

证据目录为 `verification/spear-carry-removed-20261021/`。开发测试只使用带 `.fsrpg-spear-carry-removed-test-world` 标记的复制世界 **FSRPG Spear Carry Removed 20261021 01**，窗口固定于 Windows `\\.\DISPLAY5`，尺寸为 1000 × 680。测试使用真实原版移动与跳跃，每场景仅起始定位一次，不逐 tick 传送或重写速度。

本轮 **7 个场景**全部通过，报告保存在 `game/run-air-review.json`：

- `spear-run-rear` 与 `spear-run-front`：背面、正面实际双手快跑分别 **135 / 137 帧**，普通双握与下肢跑动清楚可见。
- `spear-stop-reaim`：停止后保持双握并跟随新瞄准，稳定帧 **29**。
- `spear-run-turn`：持续双握跑动 **135 帧**，实际转向范围超过 25°门禁。
- `spear-run-attack`：真实攻击 **6 帧**，原稿握点推进 **3.41915 cm**。
- `spear-walk`：普通行走，`twoHandRunFrames` 为 **0**。
- `spear-run-jump`：实际上升、下降分别 **20 / 19 帧**，落地后 **76 帧**；活跃惯性 **43 帧**，同次跳跃姿态旋转变化 **0.023563 弧度**。

三个地面跑动场景的最大握持线误差约 **0.0000693 cm**，最小枪轴朝前分量约 **0.47772**，双腿实际变化均超过 0.2 门禁。已删除动作的自由左臂、背面斜角与跨背交点不再作为验收条件。7 场景实际腰口误差均为 **0 像素**；跑跳身体、腿部惯性最大俯仰分别约 **1.354° / 2.515°**，均在原有幅度上限内。

原生捕获来自 `RenderFrameEvent.Post`，`run-air` 片段共 **1436 帧 / 36.2105432 秒 / 39.6293419868 Hz**，最大源时间间隔 **0.058945 秒**，`pendingWrites=0`。7 场景各一张未编辑原 PNG 已逐一目视核对并记录 SHA，确认恢复普通双手持枪、无背枪覆盖，攻击、跑跳与腰口显示正常。1436 张源 PNG 全部独立校验；录像使用 **2 线程**按真实时间戳选取原图，输出 30 FPS，无动作插帧。

| 项目 | 最终结果 / 证据 |
| --- | --- |
| 完整 build 与 smoke 编译 | **通过**；完整 build **1 分 53 秒**，`compileSmokeJava` 成功；`build.log` |
| 自定义测试任务 | **70 个通过**；按最终 build 日志独立计数，删除背枪专用任务，不新增镜像测试 |
| DISPLAY5 实机 | **7 场景 / 21 项检查通过**；`game/run-air-review.json` |
| 原生帧、源时间戳与原 PNG | **通过**；1436 帧、39.62934199 Hz，最大间隔 0.058945 秒；`game/render-capture.json`、`game/native-png-validation.json` |
| 原 PNG 目视核对 | **通过**；7 场景各一张原 PNG 逐一核对并记录哈希；`game/visual-inspection.json` |
| 2 线程 30 FPS 录像与独立解码 | **通过**；1436 张源 PNG 全部验证，输出 1088 帧 / 36.266667 秒；`game/encoded-review.json`、`game/visual-review.mp4` |
| 生产包、素材与协议范围 | **通过**；3 个背枪类删除，148 项素材和全部服务端类逐字节保留，协议注册类哈希一致；`binary-scope-review.json`、`deployment.json` |
| 官方实例预检、备份、安装与 SHA-256 | **通过并已部署**；旧版与完整配置已备份，54 个 FSRPG 配置、完整 config / TOML / options、其它 Mod 与用户世界按字节保留；`deploy-verify.log`、`deploy.log`、`deployment.json` |

[本轮实机录像](../verification/spear-carry-removed-20261021/game/visual-review.mp4) 的 SHA-256 为 `ce3bd681054b5a2da10ea6a9b519d7d894bc11f4589018e1ca39170aa04fbb3a`。输出 30 FPS 用于观看，原生源采样率单独记录为 **39.6293419868 Hz**。开发专用 GeckoLib 已按原 SHA 恢复。

## 正式交付记录

已于 **2026-10-21 16:57:01（Asia/Shanghai）** 按工作区约定安装到 `C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/mods/fsrpg-neoforge-0.8.2.jar`。部署脚本为 `tools/deploy-0.8.2.ps1`，仅替换已核实的 0.8.1 JAR；旧版 SHA-256 为 `76200ffb97bbd46078abc4de35c4f2098ba0ea4caa3871cbf67dc600d6131a4a`。必要依赖继续使用已测试的 FSVOXEL 0.6.0。

部署前已核对正式实例版本、实际游戏目录与进程停止状态。旧 JAR 及完整原配置已备份于 `C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/fsrpg-backups/20261021-165658-596-before-0.8.2`。**54 个**既有 FSRPG 配置文件、完整 config 树、客户端 TOML、options.txt、其它 Mod 和用户世界按字节保留；本轮部署未写入或迁移配置。

完整构建、实机报告与正式安装的 `fsrpg-neoforge-0.8.2.jar` SHA-256 均为 `71f4551ec6e910f2c60b853e31bc739cf90b55e0344dc5258f82b5ac0440c98c`。实际部署记录为 `verification/spear-carry-removed-20261021/deployment.json`，其中保存安装时间、备份位置、素材和配置清单及哈希。
