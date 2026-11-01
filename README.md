# Steel as Sinew

当前开发与构建入口是 [`fsrpg/`](fsrpg/)，版本 **FSRPG 0.13.0**，运行于 **Minecraft Java 1.21.11 / NeoForge 21.11.45 / Java 21**，网络协议 14。客户端和服务端使用同版。

原 ActionCombat 工程、YSM 包、素材与提交历史保留在仓库原位置；它们与新的 FSRPG 分开构建。`main` 分支构建时先进入 `fsrpg/`；下载 `v0.x.y` 标签的 FSRPG 源码压缩包时，解压根目录就是该版本的工程。

```powershell
cd fsrpg
.\gradlew.bat build
```

macOS / Linux 使用 `cd fsrpg && bash ./gradlew build`。首次构建需要联网解析 Gradle、Java 工具链和 Minecraft / NeoForge 依赖。成品在 `fsrpg/build/libs/`。游戏还需安装配套的 FSVOXEL Renderer 0.6.0；已在主要版本 Release 中提供该依赖。

## 动作

- 镜头统一使用右后方越肩机位，探索与骑乘分别记忆位置；支持 Ctrl + I/K/J/L 调节，保留 F5 和玩家主动选择的第一／第三人称视角。
- 准星选目标与实际渲染机位、视场角一致，服务器继续检查眼睛到目标的距离与遮挡；普通攻击和挖掘沿用原版交互流程。
- 长枪使用自由浮动准星、连续转向和回中；保留点刺、持续刺击与拖动扫区。双手共杆、握距、关节约束和步法协调使用实际枪姿求解，格挡为胸前双手横执枪杆。
- 西洋剑保留当前全身五段攻击、碰撞位移、格挡、振刀和专用还击，第三／第一人称共用实际服务器动作状态。

详见[三部分完整更新说明](fsrpg/docs/github-update-2026-11-01.md#动作)与[项目说明](fsrpg/README.md)。

## 任务

重点开放配置与扩展入口。任务、目标、前置、称号、冷却、阶段及各种奖励使用 JSON 配置和 Schema；管理员可通过 `/fsrpg questCreate <JSON>` 创建新任务，`/fsrpg reload` 校验后重载。

- 支持多材料、等级／精英／BOSS 讨伐、玩家悬赏、D–S 晋升考试、日常任务和多任务追踪。
- 可以引用其他 Mod 已注册的物品与实体 ID，并通过服务端等级、精英、BOSS 标记和自定义标签参与目标过滤。
- `QuestService` 是独立于 Minecraft 客户端的 Java 核心；当前公开扩展方式是配置、命令和服务端标记约定，没有宣称独立的 Java SPI 或专用任务事件总线。
- 状态、领取标记、每日分配与历史按玩家 UUID 持久化；奖励和权限由服务器判断，客户端只提交意图。

入口：[任务接口与集成约定](fsrpg/docs/github-update-2026-11-01.md#任务)、[完整使用与配置说明](fsrpg/docs/任务系统-0.13.0.md)、[字段 Schema](fsrpg/schemas/)、[默认任务示例](fsrpg/src/main/resources/defaults/guild_quests.json)。玩家按 **J → 公会任务**进入。

## 素材

保留可编辑源文件、贴图、导入清单与运行时结果：十件货币／钱袋／冒险者牌物品，长枪、双刀、西洋剑、弓及检视动作，生物模型和动作、四类 HUD 素材，以及玩家外观示例。物品仍使用真实 ItemStack 的官方模型，素材目录与能力边界见[素材清单](fsrpg/docs/github-update-2026-11-01.md#素材)。

- [FSVOXEL Workbench](https://github.com/shioinhana/fsvoxel-workbench)：工作台与配套 Windows 套件独立维护。
- [Reforged Forging](https://github.com/shioinhana/reforged-forging)：独立锻造 Mod，包含加热、锤击塑形、淬火、装配及宝石镶嵌，提供 NeoForge／Forge 两套构建；部分最终美术仍以该项目素材清单为准。

本仓库不包含个人世界、运行目录、Gradle 缓存或测试逐帧缓存。FSRPG 0.13.0 已通过完整构建的 105 个回归测试任务，以及同一发行 JAR 的 DISPLAY5 12 场景／80 项实机检查；旧版本的验证范围以各自发布说明为准。各工程继续保留原有许可证及素材来源说明。
