# 0.9.3：独立资源物品栏与物品渲染

Minecraft 1.21.11 / NeoForge 21.11.45 / Java 21，网络协议保持 12。

## 物品范围

创造模式新增 **FSRPG 资源** 分类，按钱袋、货币、冒险者牌排列；可从原版搜索栏检索。

| 物品 | 注册 ID |
| --- | --- |
| 钱袋 | `fsrpg:money_pouch` |
| 金币 | `fsrpg:gold_coin` |
| 银币 | `fsrpg:silver_coin` |
| 晶币 | `fsrpg:crystal_coin` |
| 下界币 | `fsrpg:nether_coin` |
| 金色冒险者牌 | `fsrpg:gold_adventurer_badge` |
| 钴色冒险者牌 | `fsrpg:cobalt_adventurer_badge` |
| 铜色冒险者牌 | `fsrpg:copper_adventurer_badge` |
| 银色冒险者牌 | `fsrpg:silver_adventurer_badge` |
| 白色冒险者牌（新增） | `fsrpg:white_adventurer_badge` |

用户明确本次仅将物品模型纳入物品栏。生物和 HUD 继续原有用途，forging 的物品、JAR 和配置不在修改范围。`261001/综合.bbmodel` 是已有单件的排版集合，不重复注册为物品。旧九件物品 ID、堆叠上限与背包兼容性保留。

## 渲染

使用 Minecraft 原生物品模型与 ItemStack 渲染。保留来源的可见几何、Euler XYZ 旋转、逐面 UV 和内嵌 PNG；重新计算各场景居中与显示比例，使货币、牌面和钱袋在物品栏、双手持握、地面、展示框、头部和置物架场景可见。晶币源中作者隐藏的一个薄板不再误导出，其余可见元素全部保留；十件合计 1584 个方块、9504 个纹理面。

物品栏按旋转后真实轮廓居中，四周留边。第一人称按原版 70° 手部透视重新定位，左右手对称内收、上移，避免模型从右下角出屏；钱袋第三人称抵消原版手层的 X 轴旋转，恢复袋口向上。新导入器区分立方体旋转 `Rz·Ry·Rx` 与显示旋转 `Rx·Ry·Rz`，并分别计算左右手变换。

本次白牌源为 `白色冒险者 (1).bbmodel`：544 个方块、3264 个面、17 组、80×80 PNG，无动画。源 SHA-256 `9c7f9f0ee94b23d75a798d2bbf97eaa65bafcbb68466b49463cbbc688999e4e6`，PNG SHA-256 `d09a5b0e258f9d87d9a53213046735df9756fdeb358327218cc7b41606155f45`。单独新增白牌，不覆盖此前使用 169 方块白色模型的银牌。

## 验收与安装

- `gradlew.bat build compileSmokeJava --offline` 通过，83 个自定义测试任务通过。
- `python tools/verify-imported-items.py` 检查十件资源的几何、UV 与原 PNG 保真，90 个显示场景通过；独立复算 20 个左右手模型在 4:3、1200:780、16:9 下的 60 个原版静止透视投影，均有可见边距。
- 最终发行 JAR 在 DISPLAY5 的标记复制世界中通过 368 项检查，包含精确的物品栏与搜索成员、90 个实际原生模型上下文、主副手同步、真实物品框与掉落实体；运行中累计 2679 个原生渲染帧，保存 24 张原生截图。
- 24 张截图覆盖独立创造栏、十件放大模型、十件双手第一人称和十件双手第三人称，以及展示框和地面总览。头部与置物架通过原生模型加载/边界检查，未另建实物场景；静止手部投影不代表挥手或换物全过程保证不越屏。
- 发行 SHA-256：`0594d30a57cc9c3297096e74a9355e281abfc2e0fbd76c6e648cc6abfb82aacd`。发布包不含 smoke 类或显示器 agent；除物品注册、物品资源和版本元数据外，其余生产类和资源保持原包字节。

统一重建：`python tools/import-261001.py`。源清单为 `artwork/imports/item-models-manifest.json`。实机及构建证据在 `verification/imported-items-20261023/`。

部署脚本 `tools/deploy-0.9.3.ps1` 要求实机报告和截图复核通过、显示器正确且被测 JAR 的 SHA-256 与待安装 JAR 一致；核实官方实例 Minecraft/NeoForge 版本及实际游戏目录。旧 JAR 和配置先备份，本版无需配置迁移。

2026-10-23 23:09（北京时间）已安装到 `C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/mods/fsrpg-neoforge-0.9.3.jar`，安装 SHA-256 与上述实测包一致。旧 0.9.2 JAR 与完整 70 项配置备份在该实例 `fsrpg-backups/20261023-230951-before-0.9.3/`；70 项配置逐字节保留，forging、FSVOXEL、options 与用户存档未改动。
