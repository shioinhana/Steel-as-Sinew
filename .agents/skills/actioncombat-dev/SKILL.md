---
name: actioncombat-dev
description: |
  Trigger when working on the Action Combat Minecraft mod: 拔刀, 太刀, 动作战斗, YSM, GeckoLib dummy, gpt instance, attack timings, Zelda camera, or any file under the official actioncombat tree.
  Use this BEFORE editing animations, installing jars, or touching the 中文需求 folder. It is the map of repos, the playable gpt instance, the YSM pipeline, and how to retune clips.
---

# Action Combat 开发

官方源码只有一棵树。需求文件夹只放规格和美术，**不要在那里写源码**。

## 路径

| 角色 | 路径 |
| --- | --- |
| **官方源码** | `/Users/mac/Downloads/minecraft 1.21.11/actioncombat` |
| **可玩实例 HMCL `gpt`** | `~/Library/Application Support/minecraft/versions/gpt` |
| 模组 jar | `~/Library/Application Support/minecraft/versions/gpt/mods/actioncombat-0.1.0.jar` |
| 依赖 | 同目录 `geckolib-neoforge-1.21.11-5.4.5.jar` |
| 需求/美术 | `/Users/mac/Downloads/新Minecraft 1.21.1 动作战斗模组需求`（见 `现状.md`） |
| Forge 移植 | `/Users/mac/Downloads/actioncombat-archive-20260907/00-actioncombat-forge`（已归档） |
| YSM 包 | `actioncombat/ysm/packs/` |

版本：**Minecraft 1.21.11 / NeoForge 21.11.45 / GeckoLib 5.4.5**，包名 `com.actioncombat`。

改完必须：

```bash
cd "/Users/mac/Downloads/minecraft 1.21.11/actioncombat"
./gradlew jar --offline
cp build/libs/actioncombat-0.1.0.jar \
  "$HOME/Library/Application Support/minecraft/versions/gpt/mods/actioncombat-0.1.0.jar"
```

让用户**完全退出再开 HMCL 的 `gpt`**。不要用 Gradle `runClient` 当验收，也不要另开端口。

## 技术路径（拔刀后画面从哪来）

```
输入 R 拔刀
  → ClientCombat 预测 + ServerCombat 同步 drawn
  → 镜头：始终原神第三人称（不越肩）；仅挥砍期间锁角色朝向+视角，片结束后保持朝向并解锁
  → 人偶：
       若已装 Yes Steve Model（modid yes_steve_model / ysm）
         → 不隐藏原玩家、不画 GeckoLib 人偶
         → 游戏里选中的 YSM 模型就是演示人偶（鲸鱼娘）
       否则
         → 隐藏原版玩家
         → PlayerAnimatable 人偶 + YsmStyle 开
         → geo/anim = ysm_player 或 ysm_player_dual
         → 骨骼名 Head/Body/LeftArm/RightArm/LeftLeg/RightLeg + 前臂手 Locator
  → 第一人称仍是 fp_singlesword / fp_doublesword viewmodel
判定：第三人称片出刀瞬间只扫一次；射线从摄像机出发，距离从角色算
```

关键类：

- `client/ClientCombat.java` — 拔刀、输入、人偶同步、镜头状态
- `client/CombatCamera.java` / `CameraDirector.java` / `mixin/client/CameraMixin.java` / `EntityTurnMixin.java`
- `client/YsmStyle.java` — YSM 骨骼名、是否让位给宿主 YSM
- `client/PlayerGeoModel.java` / `PlayerGeoRenderer.java` — 人偶资源和渲染
- `entity/PlayerAnimatable.java` — locomotion + action 两层控制器
- `combat/ServerCombat.java` — 一次 hit scan
- `combat/WeaponCategory.java` — 片名单手/双刀
- `tools/generate_ysm_vanilla.py` — 原 GeckoLib 片 1:1 改名为 YSM 骨骼

## 怎么改动画

1. **第三人称动作（拔刀人偶）**  
   源文件（作者向，GeckoLib 原骨骼名）：
   - `src/main/resources/assets/actioncombat/geckolib/animations/third_person_singlesword.animation.json`
   - `.../third_person_doublesword.animation.json`
   模型：
   - `.../models/player_longsword.geo.json`
   - `.../models/player_doublesword.geo.json`

2. **片名字不能乱改**，Java 按这些播：

   | 片 | 用途 |
   | --- | --- |
   | `static_idle` | 拔刀站立 |
   | `walk_hold` / `walk_hold_left` / `walk_hold_right` / `walk_hold_back` | 走/侧移/后退 |
   | `run_hold` / `run_hold_*` | 跑及侧移后退 |
   | `crouch_idle` | 蹲 |
   | `jump_start` / `jump` / `fall` | 起跳 / 滞空 / 下落 |
   | `draw` / `put_away` | 拔/收 |
   | `attack1` 左手左砍，`attack3` 右手左砍 | 左键链 |
   | `attack4` 右手右砍，`attack2` 左手右砍 | 右键链 |
   | `attack_dual` | 弹反后双斩 |
   | `defend` / `defend_end` / `aiming` | 格挡/蓄力 |
   | `*_upper` | 上半身 overlay，腿继续走 |

   双刀片名有 `attack_1`（不要改成 attack1）。

3. **改完片之后** `./gradlew jar` 会跑：
   - `tools/generate_locomotion.mjs` — 缺的走跑 overlay
   - `tools/generate_attack_timings.mjs` — 从 **3P 片**刀速峰值算出击帧
   - `tools/generate_ysm_vanilla.py` — 骨骼改名写入 `ysm_player*.json` 和 `ysm/packs/actioncombat_vanilla/`

   出击窗口在 `src/main/resources/actioncombat/attack_timings.json`。手改会被生成器盖掉；要调手感就改生成器常量或 3P 片本身。

4. **第一人称**  
   `fp_singlesword.animation.json` / `fp_doublesword.animation.json`，只影响 FP viewmodel，**不是判定权威**。

5. **Blockbench**  
   用 Bedrock 实体动画 1.8 / geo 1.12。导出覆盖上面的 json。YSM 主动画名（idle/walk/run/jump）由生成器从我们的片做别名，不必手写两套。

## YSM 操作

本模组**不内置** `.ysm` 解密加载器，也不自动导入鲸鱼娘。外形要么是内置 `ysm_player`，要么是玩家自己装的 Yes Steve Model。

### 文件夹格式（可给独立 YSM 模组播）

```
ysm/packs/<id>/
  ysm.json
  models/main.json          # Bedrock geo，YSM 骨骼名
  models/arm.json
  animations/main.animation.json
  animations/extra.animation.json
  textures/*.png
```

我们自己的转换包：`ysm/packs/actioncombat_vanilla/`。  
`tools/generate_ysm_vanilla.py` 把 `player_longsword` 骨骼一对一映射：

`head→Head` `upper_body→Body` `upper_arm_r→RightArm` `forearm_r→RightForeArm` `hand_item_r→RightHandLocator` `leg_r→RightLeg` … 幅度不压。

没有 `.ysm` 包时，拔刀人偶播内置 `ysm_player`。

### 不要做的事

- 不要把 ARR 鲸鱼娘解密结果写进 jar 分发
- 不要改 `YsmStyle` 骨骼常量除非同时改生成器和 geo

## 战斗手感（已定）

- 拔刀后始终原神第三人称，**不再使用越肩**
- 挥砍期间：角色朝向和视角锁在一起；片结束后保持攻击朝向但取消锁定，WASD 才改朝向
- 状态机先判运动类型（空中/蹲/跑/走/站）再播；攻击/格挡是上半身 overlay，腿继续走
- 判定一次，摄像机射线，距离从角色
- 格挡 `X`；拔刀后 Shift=闪避，C=蹲
- 右键先右手（attack4→attack2）
- 攻击间隔固定 0.30s（6 tick）；片长 0.40s；接触 0.12–0.18s；闪避最大 4.5m / 0.20s

## 验收

改动画或渲染后：装 jar → 开 **gpt** → R 拔刀。  
无独立 YSM 模组：应看到内置 `ysm_player` 史蒂夫/原动作。  
有 Yes Steve Model：应看到游戏里选中的宿主模型。

## 2026-09-07 基线维护

参见官方工程 docs/当前基线.md；.restore-023010 是构建输入，不能作为废案删除。Forge 和未接入构建的 OpenYSM 试验已归档。

## 当前规则更新（西洋剑）

以 docs/西洋剑与双持更新.md 为准：单手为右手西洋剑，左键随机片，右键瞬刺；0.05–0.30s 成功格挡，双持举防冲刺一次范围击退。单手骨架左右已修正，不能再次按旧单手镜像重建。动作源 assets/rapier-workbench；转换 generateRapier 在 FP 生成后运行。
