# 0.9.0：旧版分支技能完整化、格挡 1/5 闪光与 3/5–4/5 窗口、完美格挡后摇修复

目标环境：Minecraft 1.21.11 / NeoForge 21.11.45 / Java 21。网络协议由 11 升为 **12**（新增步法权威速度消息），联机两端必须同版。

本轮把旧 ActionCombat 0.1.1（`actioncombat_legacy_20260923`）的全部旧分支技能按实际源码而不是注释/设计稿还原到新版；同时按用户实机反馈修正格挡时序与"格挡后攻击迟滞"。旧的 0.8.x 武器动作、数值、任务与素材不被重做，除下文明确列出的新分支与修复外保持原样。

## 1. 步法 / 移动分支（新增 8 节点）

步法树在 P 技能树中显示，入门条件为心法、煅体开启后累计步法；两名流派节点互斥且不占选学额度，其余 6 个技巧每 150 步法总值获得 1 次选学额度（步法上限 600，最多 4 次选学）。所有位移、冲量、无敌、冷却、踩头/蹬墙与摔落免疫由服务端判定。

| 节点 | 键 / 方式 | 行为（以旧源码为准） |
| --- | --- | --- |
| 脱兔 | B | 沿输入方向瞬时位移 3 格，速度原样保留；8 tick 无敌、12 tick 冷却；碰撞裁剪不穿墙，不跨未加载区块/世界边界；水中/骑乘/游泳/爬梯/飞行/睡觉不可用；可同时攻击 |
| 飞鸿 | B | 把沿方向键方向的速度分量 x(m/s) 变换为 `x<=0 ? 0.5x : x+ln(x)+0.5x`；只写一次速度后自然衰减，地面/空中均可；30 tick 冷却，开启 8 tick 闪刺窗口 |
| 闪刺（步法＋西洋剑组合） | 西洋剑右键刺击命中 | 在脱兔无敌或飞鸿窗口内，真实刺击命中后追加原伤害 50% 的无视护甲伤害（`fsrpg:flash_thrust`），100 tick 组合冷却；只由真实刺击命中触发，普通挥砍不触发 |
| 登云 | Space（空中） | 增加 1 次空中跳，合计最多 2 段；跳跃间隔 4 tick，真实落地才重置 |
| 登云二 | G（空中） | 优先踩 4 格内可见目标，造成其最大生命 2% 向上取整伤害；否则蹬 2 格内墙并反向水平速度；24 tick 共享冷却，不消耗普通空中跳次数 |
| 登云三 | 选学 | 空中跳增至 3 次，合计最多 4 段；保留 4 tick 间隔 |
| 若轻 | 选学 | 空中水平阻力减半，重力保持原版 |
| 若轻二 | 选学 | 地面跳与登云跳沿输入方向追加 10 m/s 水平冲量；取消摔落伤害（只取消原版摔落伤害，不改其它无敌） |
| 若轻三 | 选学 | 取消脱兔/飞鸿发动冷却；登云跳跃间隔与踩头/蹬墙冷却保持 |

另：地面按住 G 再按跳跃为登云高跳，原版跳起后竖直初速 ×√2（高度 ×2），需要登云。B/G 均可在原版按键设置中改绑，默认不占用 R/C/Shift/Alt/H/Z/V/P/J/K/F8 与鼠标左右键。

## 2. 西洋剑与双刀旧分支

- 西洋剑 4 节点（入门、刺击、蓄力拨挡刺、缺血处决）与 0.8.5 五段击剑（直刺、换线刺、压剑刺、短回切、弓步终结刺）逐项核对完整：右键突刺 7/2/2 tick、伤害 9、距离 4、20 tick 提交冷却；蓄力 16 tick、未满受击抵消并蓄满、满蓄受击破防 200 tick、拨挡刺伤害 18；缺血处决追加目标缺失生命 10%；突刺推进 6 m/s。
- **拨挡还击 / 闪步回刺重新启用**：旧版 `rapier_riposte` / `rapier_reprise` 条件技能此前默认不可达。0.9.0 默认 `styles.json` 的 rapier `techniques` 设为 `rapier_riposte`（窗口 20 tick）与 `rapier_reprise`（窗口 12 tick），并配一次性迁移：只给旧默认空 `techniques` 的配置补默认值，任何自定义 `techniques` 原样保留，备份原 `styles.json` 到 `rapier-techniques-backup-v1/`，写 `rapier-techniques-migration-v1.json`。
- 双刀 5 节点（入门、连击上限 3/4/5/6）与方向手位、同向第三刀拒绝、完整收招归中、联动斩（100 tick）、命中叠层/100 tick 掉层核对完整，未改 0.8.3–0.8.5 已发布数值。
- 旧版"每次出刀后自动短举防、挡下武装双劈"**有意不恢复**：它与 0.8.4 之后显式 C 举防、新按授权与 3/5–4/5 完美窗口直接冲突；新版以显式 C 与完美格挡左键 ×1.5 反击替代，见"已知边界"。

## 3. 弓术分支

8 个节点行为与旧版一致：入门（R 拔/收弓、V 速射/狙击）、50 连续引弓（右键满蓄自动连放；左键长按连射沿用 0.5 迁移后的现有行为，按下先弓身撞击并打断拉弓）、100 落点预测、150 如梭（5 发储备，每发引弓 -20%，与速射减时加法叠加、总上限 -50%，每 60 tick 回 1 发）、150 啮矢（免疫一次投射物伤害并取回该箭，40 tick 冷却）、250 岩下电（箭速 +30%）、250 穿堂风（无视盾牌、护甲结算后追加 30% 穿甲伤害、实体穿透 127、真实方块穿透最多 3 格且每格速度 ×0.8、第 4 面不穿、世界边界绝不穿）、300 狙击镜（按实际箭速与蓄力缩放，最高 8×）。

穿堂风方块穿透重写为真实碰撞形状逐格求交：服务端命中与客户端落点预测使用同一 `ArcheryBlockPiercing.trace`，按 `AbstractArrow` 的 move→阻力→重力顺序，斜射/角点/台阶/半砖与连通墙 1–3 格一致；第 4 面复用 vanilla `onHitBlock` 并只结算一次。耐久不足时在扣弹药/体力/灵力前拒绝；刚好吃尽耐久的最后一发仍发射并碎弓。弩/弓箭充能、如梭与啮矢冷却跨重登保留。

## 4. 格挡时序（用户本轮指定）

有攻击动画的怪物，真实前摇为 x tick：

- 闪光：`floor(x/5)`。
- 完美格挡输入窗口：闭区间 `[ceil(3x/5), floor(4x/5)]`，只接受窗口内的**新按 C**；窗口前按住、接触时刻新按都不授权；窗口外的接触只走普通格挡/失败。
- 近战相杀窗口：仍为 `[ceil(3x/5), x]`，独立于完美格挡关闭点，包含真实接触。
- 短前摇 x=1/2：输入窗口按量化自然为空，不延长到接触。
- `fixed_ticks` 模式保留旧兼容语义。`defaults/guard_cues.json` 增加 `windowCloseFraction=0.8`；旧 5 字段配置解析即 0.8 且不改写文件；显式旧默认 `close=1.0` 的配置一次性迁移为 `max(0.8, open)`，备份到 `guard-cue-close-backup-v1/` 并写迁移标记，自定义值保留。

## 5. 完美格挡后无后摇 / 攻击迟滞修复

**根因（服务端状态机，已用回归测试复现）**：按住 C 时客户端每 4 tick 发送 `held`/`held_heavy` 续包；旧 `CombatActor.setGuarding` 把不在 GUARDING 的续包当成新的主动举防意图，命中 WINDUP/RECOVERY 的格挡取消规则后 `clearAttack()`。旧代码只豁免 `attackDamageMultiplier>1` 的完美格挡反击（×1.5），所以反击窗口过期后的普通左键、右键突刺/蓄力刺、技能、双刀与连击第二段会在接受后被静默取消并退回举防——表现为"格挡后攻击非常迟滞"。客户端输入、呈现与完美格挡清理本身没有延迟。

**修复**：`CombatActor` 用"物理按住令牌"`guardYieldedToSwing` 覆盖整段攻击生命周期；只要攻击是在按住 C 时被接受，后续续包不得取消/重开举防，直到真实释放 C、换档或收刀。完美格挡成功仍立即清理当前动作、排队攻击、后摇与已有硬直；下一 tick 左键即被接受。保留 ×1.5 反击窗口、攻击者 4 tick 僵直、按住 C 自动重开窗口（一次按住最多 3 次）、普通格挡体力/减伤/次数限制与相杀规则。

## 6. 配置与迁移

- `skill_trees.json`：17 → 25 节点，新增 `rules.footwork` 与 8 个稳定 ID（`rabbit`、`flying_swan`、`cloud_step`、`cloud_step_ii`、`cloud_step_iii`、`light_body`、`light_body_ii`、`light_body_iii`）。旧存档自动迁移：只补缺失节点/规则字段，自定义节点、自定义字段与其它文件保留；备份 `skill_trees.json` 到 `footwork-skill-backup-v1/` 并写 `footwork-skill-migration-v1.json`。
- `styles.json`：只给旧默认空 rapier `techniques` 补默认值（见 §2），自定义保留。
- `guard_cues.json`：新增 `windowCloseFraction`，旧文件解析默认 0.8，显式旧默认值一次性迁移（见 §4）。
- `archery.json`：字段不变（穿堂风穿透/减速、实体穿透等沿用 0.7.x 已存在字段）。
- 玩家数据：步法学/选学额度、已选流派、已学技巧保存在世界 `data/fsrpg/skill_trees.json`；不修改用户世界其它数据。

## 7. 已知边界 / 有意差异

- 旧版四段随机西洋剑、0.45 秒连击门、同手第三刀 0.6 秒已被 0.7.5–0.8.5 发布设计替代，不回退。
- 旧版双刀自动短举防不恢复（理由见 §2）。
- 旧按键语义已迁移：旧 Shift 步法流派 → 新 B（Shift 已用于突进）、旧 C 空中特技 → 新 G（C 已用于格挡）；旧 C+Space 高跳 → 新 G+Space。
- 弓落点预测与服务端放箭 power 存在最多 1 tick 量化差；水中阻力采样比 vanilla 滞后 1 tick；第 4 面终止时 vanilla 会把箭拉回 0.05 格钉墙，预测标记在墙面，存在约 0.05 格视觉差。
- 纯几何/状态机测试不能替代真实客户端；实机验收范围见 §8。

## 8. 验证与交付

- **完整构建**：`gradlew.bat build compileSmokeJava --offline` 通过，89 个可执行任务、**81 个自定义测试任务**全部通过（含新增 `footworkTest`、`footworkProgressionTest`、`footworkSkillMigrationTest`、`rapierTechniquesMigrationTest`）；`compileJava`/`compileTestJava` 通过。
- **重点回归**：`guardCueWindowTest` 367,608、`archeryTest` 4,998（含穿堂风几何 4,875）、`legacyMeleeTest` 682、`perfectGuardCounterTest` 453、`configTest` 252、`progressionTest` 181、`footworkProgressionTest` 162、`footworkSkillMigrationTest` 156、`FootworkTest` 137、`rapierTechniquesMigrationTest` 72；其余 81 个测试任务均通过。
- **真实客户端（DISPLAY5）**：同一发行 JAR 的 `DefenseFlowReview` 通过 **10 场 / 502 项原生检查**，`passed=true`，JAR SHA-256 `4b75efd23ca4e7097f9acb47b7148f41f00c5f2340e8daa30efce57ae16ba217`，采得 1071 个原生帧（约 39.54 Hz），验证了 1/5 闪光、3/5 打开/4/5 关闭的窗口、相杀窗口到接触、完美格挡链、普通格挡与免震屏路径；证据在 `verification/legacy-branches-20261022/game/`。
- **实机未覆盖**：本轮没有为步法 B/G 按键、踩头/蹬墙、若轻阻力与闪刺、穿堂风方块穿透单独编写 DISPLAY5 场景；这些由纯 Java 状态机/几何测试和上述实机的启动/数据包/网络/格挡路径覆盖，属于已知验证边界。
- **官方实例部署**：将 0.8.x 官方配置复制到 `verification/legacy-branches-20261022/fsrpg` 并用真实迁移预演，生成 25 节点、8 个步法节点、cue close=0.8 的候选；部署前备份旧 JAR 与完整 `config/fsrpg`/`options.txt` 到 `fsrpg-backups/20261022-015111-before-0.9.0/`，只替换 FSRPG JAR 并写入 6 个迁移文件（`skill_trees.json`、`styles.json`、`footwork-skill-backup-v1/skill_trees.json`、`footwork-skill-migration-v1.json`、`rapier-techniques-backup-v1/styles.json`、`rapier-techniques-migration-v1.json`），其它 Mod、`fsvoxel` 依赖、`options.txt` 与用户存档逐字节保留；安装 JAR SHA-256 与已测试构建一致，记录在备份目录 `deployment.json`。

