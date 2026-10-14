# FSRPG 工作约定

用户于 2026-10-11 规定：后续 Mod 完成构建和验证后直接部署到 `C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/mods/`，不重复询问部署许可。部署旧 JAR 与配置迁移前先备份，只替换本 Mod 与必要依赖，保留其它 Mod、存档和自定义模型。部署后校验 JAR 哈希与已测试发行一致。此规则同时记录于父目录 AGENTS.md。

本机所有游戏测试窗口必须在用户指定的屏幕二 Windows `\\.\DISPLAY5`，屏幕一留给用户。runClient 已接入开发专用 monitorAgent，在窗口创建前选择该显示器，不自动抢焦点。显示器原点和主屏标记会随 Windows 桌面布局变化，不能用窗口 Y 坐标正负或 primary 标记判断是否在正确屏幕；烟测应记录窗口中心实际所在显示器的 monitorAdapter 与 monitorBounds，并严格核对 DISPLAY5 标识。不要移除或绕开这项设置。该 agent 不进入发布 Mod JAR。

先阅读 README.md 与 schemas/。目标为 Minecraft 1.21.11、NeoForge 21.11.45、Java 21；不要因为原设计稿标题写了 1.21.1 而改版本。

优先编辑 config/fsrpg/ 的 JSON 与 models/*.fsvoxel；源码内 defaults/ 只是首次运行和发行的模板。已运行的开发客户端配置位于 run/client/config/fsrpg/。文本改动后 /fsrpg reload；不要直接改运行中的客户端快照来决定伤害或任务进度。

技能、任务和目标 ID 应保持稳定。保存玩家数据位于世界 data/fsrpg/，不要为验证覆盖或删除用户世界。新增数值应该配置化并校验有限值、边界、单位和引用。

保持战斗与任务核心独立于 Minecraft 客户端。所有伤害、资源、技能允许条件、领奖和配置修改权限由服务端决定；客户端只发意图和显示结果。客户端专用类只能在 Dist.CLIENT 入口加载。手持物品必须取实际 ItemStack 的官方渲染状态，禁止用 FSVOXEL 自制剑网格替换。动作采样至少30 Hz，录像必须逐实际渲染帧捕获并验证源时间戳，不能把20fps截图变帧率伪装成30fps。

修改相应核心后运行 gradlew.bat build。需要验证游戏事件时使用复制测试世界与开发 smoke 源集，发布包不得包含 smoke 类。0.2.0 已接入西洋剑第三人称玩家姿态和腕部剑，第一人称自定义动作仍未制作；0.3.0 已启用双刀和长枪；空手仍保留 disabled 配置档位。发布前验证真实客户端，不能用编辑器动画预览代替游戏验证。
