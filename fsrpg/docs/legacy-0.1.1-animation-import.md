# 0.1.1 动作移植

旧源只读：`C:/Users/34003/Desktop/actioncombat_legacy_20260923`。导入源的几何与动画 SHA-256 与 `build/libs/actioncombat-neoforge-0.1.1.jar` 内资源一致。完整来源、骨骼名和播放节点映射见 `legacy-0.1.1-animation-map.json`。

旧版默认 `YsmStyle.enabled=true`，因此第三人称使用 `ysm_player` / `ysm_player_dual`，第一人称使用独立的 `fp_singlesword` / `fp_doublesword`。弓的两套动作分别位于单剑的第三人称和第一人称文件内。没有使用已废弃的备用播放器替代这些资源。

## 导入

运行 `python tools/import_legacy_011.py`。它仅写当前项目的六份 `legacy_*.fsvoxel` 及 `run/client/config/fsrpg/models` 同名副本，不写旧项目，不写 `spear.fsvoxel`。

保留 Root、Hip、Body、肩、肘、腕、腿及独立 ItemLocator 层级。Bedrock 的 `Rz·Ry·Rx(-X,-Y,+Z)` 通过 180° Y 坐标基变换转换成 FS 的 `Rx·Ry·Rz`；像素单位转换为 6.25 cm。源数组、pre/post、step、Catmull-Rom 均先按原语义求值。60 Hz 为最低基础密度，急转折自适应补点；补点阈值为 0.12°、0.02 cm 和 0.001 缩放变化。运行时使用连续渲染钟和四元数插值。

第三人称资源不含人物或武器网格，人物皮肤和装备仍由官方渲染路径绘制。第一人称保留原手臂网格及皮肤 UV，剔除刀刃、光效网格，真实武器仍通过 ItemStackRenderState 附着在旧挂点。西洋剑使用旧 NONE 物品空间校准，双刀使用旧 THIRD_PERSON_RIGHT/LEFT_HAND，弓使用原始物品空间和官方 bow_pulling 模型。物品的附魔与其余组件保留。

发行版 0.6.0 的动画解析器要求缩放严格为正。旧双刀第三人称 draw / put_away 的两个 ItemLocator 有隐藏用 0 缩放键，且 Catmull-Rom 在隐藏区间下冲到约 -0.08148；原始作者没有任何负缩放键或镜像意图。这四个源轨道的隐藏值和下冲规范为 1e-6 正缩放，仍不可见；其余正缩放保留。导入清单记录每个轨道的原最小值、样本数与归一化量。未来遇到真正 authored negative key 会明确报错要求镜像节点，不能静默夹断。

## 播放

`PlayerAnimations` 将 legacy 模型交给 `LegacyMotion`，不叠加新版程序化击剑呼吸、肘限位、步态发力和长枪瞄准求解。旧第三人称播放器的 Hip yaw 取消、65°击剑站姿、弓的侧身根旋转及俯仰 roll 同样保留。

技能引用 `legacy_rapier_attack1_upper` 至 `attack4_upper`；`legacy_rapier_rapier_thrust_hit_upper` / `miss_upper`；双刀为 `legacy_dual_blade_attack_1_upper` / `attack2_upper` / `attack3_upper` / `attack4_upper` / `attack_dual_upper`。上半身源节点保持下半身正常待机、行走和奔跑。

`rapier_guard` 与兼容 `rapier_guard_heavy` 都映射旧版唯一的 defend（FP 对应 defend_1），保留原格挡动作。

双刀 READY 别名 `dual_idle_00` 至 `dual_idle_22` 保留各手实际终点：左手左/右砍来自 attack_1/attack4，右手左/右砍来自 attack2/attack3，0 表示尚未出招。服务端 leftSide/rightSide 的 -1/0/1 对应别名的 0/1/2。

刺击沿用原 `RapierThrustRules` 的分段映射。第三人称前摇采样到 0.04 s、伸展到 0.20 s、恢复到 0.50 s；第一人称对应 0.14/0.25/0.50 s。服务端的前摇、判定、恢复窗口提供壁钟时间，充能只停在 chamber，不提前刺出。

弓别名为 bow_draw、bow_rapid/snipe_idle、bow_rapid/snipe_charge、bow_rapid/snipe_release、bow_bash。本地拉弦按 archery.chargeStartTick / requiredDrawTicks 及渲染子 tick 取连续进度。release 优先于自动下一轮 charging，并停止拉弦物品外观。

`LegacyFirstPersonAnimations.onRenderHand(RenderHandEvent)` 由客户端事件总线注册，一次主手事件提交完整旧 FP 手臂和官方实际物品，副手事件仅取消默认重复绘制。长枪不进入此路径。

`ArcheryPresentation` 接入旧 `SnipeTrajectory` 和 `ClientArchery` 的画面算法。服务端快照决定 100 熟练度轨迹与 300 熟练度倍率是否解锁，并给出完整箭速和完整倍率；客户端不复制解锁阈值或箭速规则。狙击轨迹每两 tick 预测 140 步，积分顺序为移动、0.99 阻力、0.05 重力，方块碰撞停止；第三人称每三步显示 END_ROD，落点显示十点 CRIT 圆圈与中心 FLAME。第一人称保留落点标记。

解锁倍率后，狙击蓄力暂时切入第一人称，离开瞄准或打开界面后恢复原视角。倍率随渲染子 tick 连续变化，FOV 使用旧光学公式 `2·atan(tan(FOV/2)/倍率)`，放箭恢复动画保留放箭前倍率。切武器、退出世界或打开界面会清除残留粒子。控制项显示 P 技能树和 R 姿态/弓术模式。

## 验证

`python tools/verify_legacy_011.py` 比较导入骨骼与旧 JAR 中的源动作，包括采样点之间的渲染子帧、源文件哈希和 config 副本哈希。结果保存 `verification/legacy-011/asset-fidelity.json`。

`LegacyAssetTest` 使用发行依赖中的真实 ModelLoader，解析六份模型和全部播放节点，采样完整 FK 并检查有限矩阵、至少 30 Hz、必要动作、持物挂点、无替代武器网格。最终第一/第三人称展示由复制世界实机检查。
