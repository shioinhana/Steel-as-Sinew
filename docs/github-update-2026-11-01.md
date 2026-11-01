# FSRPG 更新说明 2026年11月1日

本次同步整理截至 **0.13.0** 的已实现内容，按动作、任务、素材说明当前代码和扩展入口。运行环境为 **Minecraft Java 1.21.11 / NeoForge 21.11.45 / Java 21**，当前网络协议 **14**；客户端和服务端使用同版。

## 动作

镜头调度统一到越肩第三人称。F5 的背后第三人称使用右后方越肩机位，正面第三人称和第一人称沿用原版视角；R 拔刀自动进入越肩，收刀、换物和空手保持当前视角，由玩家主动切换。普通探索与骑乘分别记忆镜头位置，马、船采用独立的较远机位；Ctrl 配合 I/K 调整高度、J/L 调整左右偏移，两套设置分别保存。镜头位置保存由后台线程提交，避免磁盘保存重试阻塞画面。

越肩选目标以实际渲染机位及 FOV 发出射线，再从人物眼睛校验距离和遮挡。普通攻击、挖掘保留 Minecraft 原版交互流程；眼睛到准星目标被挡住时停止不合法交互，并给实际遮挡方块或实体显示轮廓和“被遮挡”提示。镜头对最终位置执行墙体裁剪，靠近障碍立即收近，离开后平滑缓出；裁剪减少重复扫掠，武器档位按快照版本缓存。

长枪使用自由浮动准星，不自动锁敌。准星偏移越大，人物和镜头转向越快，接近枪姿边界时连续加速并逐渐回中；枪姿与攻击使用同一条世界瞄准射线，并补偿镜头、眼睛和持枪手之间的视差。点按刺出并收回，长按可保持刺击并通过拖动形成扫区；打开界面或失焦会清理保持输入。双手共杆、动态握距、关节限位、连续转步和下肢支撑保留，角色移动时上下身协调工作。

长枪格挡采用胸前双手横执枪杆：格挡阶段使用专门的双骨 IK，将双手放到横杆线上并对齐实际物品轴；退出格挡后回到原有瞄准求解。刺击、扫击、命中去重、距离、墙体遮挡和资源消耗继续由服务器判断。武器外观始终读取真实 ItemStack 的官方模型与变换。

西洋剑保留当前全身五段攻击、攻击推进、格挡与振刀还击，以及第三／第一人称配套动作。默认主链为直刺、斜挑、斜劈、短横砍、弓步终刺；位移由服务器逐 tick 碰撞处理，碰墙收步，取消攻击后停止原攻击推进。

相关实现和说明：

- 镜头与交互：[`CombatCamera`](../src/main/java/org/fsrpg/client/CombatCamera.java)、[`ShoulderInteraction`](../src/main/java/org/fsrpg/client/ShoulderInteraction.java)、[`ShoulderViewRay`](../src/main/java/org/fsrpg/client/ShoulderViewRay.java)、[`CameraClipSmoother`](../src/main/java/org/fsrpg/client/CameraClipSmoother.java)、[镜头调度说明](release-0.10.2.md)。
- 长枪：[`SpearCursorSteering`](../src/main/java/org/fsrpg/client/SpearCursorSteering.java)、[`SpearConstraints`](../src/main/java/org/fsrpg/client/SpearConstraints.java)、[`SpearGrip`](../src/main/java/org/fsrpg/client/SpearGrip.java)、[自由准星说明](release-0.8.5.md)、[横杆格挡与镜头优化](release-0.9.2.md)。
- 西洋剑：[`combat.schema.json`](../schemas/combat.schema.json) 的 `swordCombat`、[全身动作清单](rapier-fullbody-0.12.0-manifest.json)。

## 任务

任务系统开放 JSON 配置、字段 Schema、管理员创建命令和服务端实体标记约定。开发者可以引用已注册的原版或其他 Mod 的物品、实体 ID，配置目标、称号条件、任务链与奖励，无需修改客户端页面。当前扩展入口以配置与命令为主；`QuestService` 是源码中可复用的纯 Java 核心，尚未提供独立的 Java SPI 或专用 NeoForge 任务事件总线。

| 配置和入口 | 扩展内容 |
| --- | --- |
| 实例 `config/fsrpg/quests.json` | 任务 ID、名称、描述、前置任务、多目标、重复规则、任务阶段及奖励 |
| `config/fsrpg/guild.json` | 最大追踪数、每日数量、时区、各级晋升的累计／指定任务条件及考试关联 |
| `config/fsrpg/guild_access.json` | 公会 NPC 标签与名称、任务板方块、考试办理距离 |
| `config/fsrpg/guild_bosses.json` | 可选 BOSS 刷新点的实体种类、名称、维度、坐标、等级、精英标记和重生间隔；模板默认关闭自动生成 |
| [`schemas/quests.schema.json`](../schemas/quests.schema.json) | 目标、过滤条件、奖励、阶段、冷却等完整字段约束 |
| [`guild.schema.json`](../schemas/guild.schema.json)、[`guild_access.schema.json`](../schemas/guild_access.schema.json)、[`guild_bosses.schema.json`](../schemas/guild_bosses.schema.json) | 全局公会规则、交互及刷新点约束 |
| [`defaults/guild_quests.json`](../src/main/resources/defaults/guild_quests.json) | 可参考的收集、讨伐、玩家悬赏、阶段、日常和晋升考试示例 |

编辑 JSON 后执行 `/fsrpg reload`。管理员可用 `/fsrpg questCreate <完整单行JSON>` 创建新任务，校验成功才保存，旧配置备份为 `quests.json.bak`；同 ID 创建会拒绝，应修改原定义并重载。任务、目标和阶段使用稳定 ID，便于更新后保留玩家进度。

目标类型包括 `COLLECT`、`KILL`、`PLAYER_KILL`、`VISIT` 和 `USE_SKILL`。收集支持背包实时数量和接取后累计获得数量两种模式；讨伐可组合实体种类、等级上下限、精英、标签、名称、BOSS 身份及死亡地点条件。玩家悬赏使用独立目标类型，可限定目标 UUID；普通／高阶／S 级悬赏等级与显示难度分别配置。

外部实体集成使用服务端持久数据和标签：

- `fsrpg_level` 整数表示等级，缺省为 1。
- `fsrpg_elite=true` 持久数据或 `fsrpg_elite` 实体标签表示精英。
- `fsrpg_boss=true` 持久数据或 `fsrpg:boss` 实体标签表示 BOSS；原版凋灵、末影龙也被识别为 BOSS。
- `criteria.requiredTag` 可要求开发者自定义的实体标签；`entityName` 可精确匹配自定义名称。

现有 NeoForge 服务端适配器从真实死亡、伤害贡献、拾取、背包、位置及技能执行结果推进任务，客户端只提交接取、追踪、放弃或领奖意图。BOSS 参与进度按真实伤害记录检查，符合距离、维度与时限的在线参与者分别计数，环境伤害收尾也会检查参与者。若增加新的目标类型，需要同时扩展核心定义／校验、服务端真实事件适配和界面描述；现有核心方法不能视作任意客户端可调用的进度写入接口。

奖励独立配置固定道具、实体货币、自选、随机池、阶段奖励、经验与声望。服务器检查完成状态、阶段顺序、选择索引及重复领取标记；先持久化领取结果，再发放物品。随机种子随接取记录保存，重复领取或重启不能对同一记录重新抽奖。D–S 称号晋升需要满足配置条件并完成对应考试，申请和最终晋升由服务器检查公会 NPC 距离。

任务状态、目标计数、完成次数、历史、每日分配、领取标记、称号和声望按玩家 UUID 保存在世界 `data/fsrpg/quests.json`。schema 1 可迁移到 schema 2；更新按稳定 ID 保留已有记录，同 ID 的用户任务不会被默认示例覆盖。每任务存档保留最多 64 次终态历史，网络显示最近 8 次详细记录及必要终态摘要，超大显示数据会明确提示裁剪。

玩家可从 J 页的“公会任务”、公会 NPC、任务板或 `/fsrpg guild` 进入；普通任务有 `accept`、`claim`、`abandon`、`track`、`untrack` 命令，晋升申请使用 `/fsrpg exam`。默认同时追踪三条，取消追踪保留任务，放弃停止推进并遵守独立冷却。每日任务按配置时区零点刷新，当日分配固定，重启或晋升不会重抽；未完成的旧日常跨日失效，已经完成待领奖的记录仍可领取。

核心实现位于 [`src/main/java/org/fsrpg/quest/`](../src/main/java/org/fsrpg/quest/)，游戏适配位于 [`RpgServer`](../src/main/java/org/fsrpg/RpgServer.java)，配置事务与创建入口位于 [`RpgConfig`](../src/main/java/org/fsrpg/config/RpgConfig.java)。更多示例和字段用法见[任务系统说明](任务系统-0.13.0.md)。

## 素材

同步保留可编辑源文件、导入清单、运行时模型与贴图，开发者可据具体目录继续制作和复现导入。

| 素材 | 已纳入内容与目录 |
| --- | --- |
| 资源物品 | 钱袋、金币／银币／晶币／下界币，以及金／钴／铜／银／白五种冒险者牌，共十件，集中到“FSRPG 资源”创造栏。原始 BB 模型和贴图在 [`artwork/imports/261001/`](../artwork/imports/261001/)、[`white_adventurer/`](../artwork/imports/white_adventurer/) 和 [`white_adventurer_badge/`](../artwork/imports/white_adventurer_badge/)；物品导入清单为 [`item-models-manifest.json`](../artwork/imports/item-models-manifest.json) |
| 物品运行资源 | [`assets/fsrpg/items/`](../src/main/resources/assets/fsrpg/items/)、[`models/item/`](../src/main/resources/assets/fsrpg/models/item/) 和 [`textures/item/`](../src/main/resources/assets/fsrpg/textures/item/)；由实际 ItemStack 官方渲染。钱袋托持、抛接与硬币检视表现由现有客户端逻辑驱动 |
| 玩家武器动作 | [`assets/fsrpg/fsvoxel/models/`](../src/main/resources/assets/fsrpg/fsvoxel/models/) 中的长枪、双刀、西洋剑与弓模型，包含 `rapier_fullbody.fsvoxel`／`rapier_fullbody_fp.fsvoxel` 和旧动作兼容文件；检视配置位于 [`assets/fsrpg/animations/weapon_inspections.json`](../src/main/resources/assets/fsrpg/animations/weapon_inspections.json)。外置定制模型可放入实例 `config/fsrpg/models/` |
| 生物模型和动作 | 原始 BB 工程及贴图在 [`artwork/imports/creatures/生物动作/`](../artwork/imports/creatures/生物动作/)；运行时导入结果在 [`assets/fsrpg/art/creatures/`](../src/main/resources/assets/fsrpg/art/creatures/) 与 [`textures/art/creatures/`](../src/main/resources/assets/fsrpg/textures/art/creatures/)。现有导入清单记录 30 个模型、27 个实体映射、117 个动作片段，具体片段以 [`all-assets-manifest.json`](../artwork/imports/all-assets-manifest.json) 为准 |
| HUD | 生命、经验、灵力、体力四套原始素材在 [`artwork/imports/hud/`](../artwork/imports/hud/)，对应导入贴图在 [`textures/art/hud/`](../src/main/resources/assets/fsrpg/textures/art/hud/)，实际条形界面资源在 [`textures/gui/hud/`](../src/main/resources/assets/fsrpg/textures/gui/hud/) |
| 玩家外观接口与示例 | [`config/fsrpg/avatars/`](../config/fsrpg/avatars/) 保留骨名、文件布局与重载说明，`examples/player.bbmodel` 是未自动启用的示例；支持 BB 方块人物、Java 经典／纤细 PNG 皮肤及未加密基岩方块骨骼几何。使用 `/fsrpg-avatar reload` 重载，外观按本机文件显示，不自动上传给其他玩家 |

物品几何、UV 和贴图转换可参考 [`tools/import-261001.py`](../tools/import-261001.py)、[`tools/import_item_models.py`](../tools/import_item_models.py) 和 [`tools/import-all-art.py`](../tools/import-all-art.py)。动作重制和检查脚本、清单位于 `tools/` 与 `docs/`，用于继续编辑与核对来源。已导入素材的具体能力按对应代码和清单判断；外观导入不自动支持 BB 自带动画、任意网格、材质脚本或加密商城资产。

**Forging 独立工程：** 对应源码位于 [reforged-forging](https://github.com/shioinhana/reforged-forging)，当前整理版本为 0.1.0，支持 Minecraft 1.21.11 的 NeoForge 与 Forge 构建。内容包含加热、锤击塑形、淬火、武器／工具装配、宝石镶嵌及生存配方链；NeoForge 提供可选 JEI 集成。它作为独立 Mod 维护，不把其源码或物品 ID 混入 FSRPG。部分最终美术仍是工作占位，以该工程的 FINAL_ASSET_CHECKLIST.md 为准。
