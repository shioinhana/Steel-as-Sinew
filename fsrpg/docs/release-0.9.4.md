# 0.9.4：原版手臂持物与取出动作

Minecraft 1.21.11 / NeoForge 21.11.45 / Java 21，网络协议保持 12。

十件 FSRPG 资源物品在第一人称使用玩家当前皮肤的原版手臂和袖层，左右手、惯用手和宽/纤细手臂由原版玩家渲染器处理。物品继续来自实际 ItemStack 的官方渲染状态；在真实手部挂点握持，钱袋握住袋口外缘、硬币落回手掌、冒险者牌握住下缘。钱袋独立放大并抬高手位，袋身向手的内侧悬挂；货币和铭牌保持较小尺寸，中央视野留空。

钱袋取出时用一秒完成下沉蓄力、向上掂起、回落缓冲和归位，默认位移依次为 −0.04、+0.10、−0.025、0 格，手臂随重量倾转。四种货币取出时抛起翻转一周并落回手掌；五种冒险者牌直接掏出。动作结束后停稳，堆叠数量变化不会重播，切换物品或物品栏槽位才重新触发。每只手独立计时，按实际渲染帧采样，暂停游戏时停止计时。所有动作都是本地显示，不生成掉落实体，也不改物品数量。切换时正在下放的旧缓存物品继续带着手臂收回，不会闪回无手的悬浮变换。

创造栏、第三人称、掉落和展示模型沿用 0.9.3。未修改素材几何、贴图、物品 ID、服务器逻辑或 forging。持有旧武器动作时，保留武器的完整双臂渲染；从武器切到资源物品后，过期战斗姿态不会抢占资源手臂。隐形时遵循原版隐藏皮肤手臂，物品继续显示。

## 客户端设置

以下设置位于 `config/fsrpg-client.toml`，新增字段由 NeoForge 在首次启动时补齐，保留已有字段的自定义值。部署前备份整个旧配置。

| 字段 | 默认值 | 范围或用途 |
| --- | --- | --- |
| `importedItemHands` | `true` | 启用资源物品原版手臂持握 |
| `importedItemEquipAnimations` | `true` | 启用一次性取出动作 |
| `importedItemScale` | `0.65` | `0.4–0.85`，第一人称货币与冒险者牌大小 |
| `importedPouchScale` | `1.02` | `0.7–1.3`，钱袋独立大小 |
| `importedPouchRaise` | `0.14` | `0–0.3` 格，钱袋与手臂一起抬高 |
| `importedPouchEquipSeconds` | `1.0` | `0.5–2` 秒 |
| `importedCoinEquipSeconds` | `1.05` | `0.45–2` 秒 |
| `importedBadgeEquipSeconds` | `0.35` | `0.15–1` 秒 |
| `importedCoinTossHeight` | `0.18` | `0.04–0.3` 格 |
| `importedPouchHeftHeight` | `0.10` | `0–0.18` 格，下沉与接住幅度分别为其 40% 与 25% |

## 验证与交付

本版验证证据位于 `verification/imported-item-hands-20261023/`。

- 最终完整 `gradlew.bat --offline build` 与 84 个自定义测试任务通过，最终构建 JAR 与实测 JAR 的 SHA-256 完全一致。
- 新增 `importedItemMotionTest`：1955 项轨迹、切换缓存、独立手部生命周期、数值边界与连续性检查。
- 同包 DISPLAY5 复制世界验收通过 410 项检查，2733 个实际渲染帧、28 张原始截图。包括十件资源、左右手、单手、袖层、隐形、GUI/第三人称与普通物品回退；86 次旧缓存物品下放事件全部保持手臂接管，0 次回退，真实热栏切换覆盖 4 帧过期武器姿态。
- 三段钱袋/金币/白牌取出录像分别捕获 109、110、109 张实际渲染 PNG，源采样率为 39.8307、39.8627、39.8046 Hz。独立核对 PNG、逐帧单调时间戳和亚 tick 姿态变化，再以 30 FPS 选择实际源画面输出，无插帧。
- 测试客户端首次升级保留全部 53 个旧配置字段的值，准确补齐 10 个新设置。发布包的素材与服务端类保持逐字节一致，且不包含 smoke 或显示器启动 agent。

测试及安装 JAR SHA-256：`0f0fd7eaa563147ce3339251d3a599e2271beecdf18728329d278e348c7fbe5e`。

2026-10-23 23:59 已部署到 `C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/mods/fsrpg-neoforge-0.9.4.jar`。部署前核对实际游戏目录、Minecraft/NeoForge 版本与游戏已退出；旧 0.9.3 JAR、70 项配置及客户端 TOML 备份在该实例的 `fsrpg-backups/20261023-235948-before-0.9.4/`。旧配置逐字节保留，新客户端字段将在下次启动时补齐；其他 Mod、options 和用户世界未修改。

实机短片：[钱袋掂动](../verification/imported-item-hands-20261023/game/equip-money-pouch-visual-review.mp4)、[抛币](../verification/imported-item-hands-20261023/game/equip-gold-coin-visual-review.mp4)、[铭牌取出](../verification/imported-item-hands-20261023/game/equip-white-adventurer-badge-visual-review.mp4)。
