# 0.11.0 外置玩家外观接口

为玩家外观增加 `config/fsrpg/avatars/` 接口。按约定命名的 BB 模型、Java 皮肤或未加密基岩皮肤几何可在客户端重载，无需重编 Mod。服务端战斗、物品 ID、伤害和网络协议仍为原有规则，协议版本 12。

## 使用

正式目录：`C:/Users/34003/AppData/Roaming/.minecraft/versions/1.21.11-NeoForge/config/fsrpg/avatars/`。

- `player.bbmodel`：本机玩家外观；BB 支持 4/5 工程的方块、分组、旋转、膨胀、逐面 UV、多贴图及内嵌或同目录相对 PNG。
- `player.png` / `player.slim.png`：原版经典 / 纤细皮肤。保持 64×64 完整内外层及 64×32 旧皮肤的镜像与透明度规则。
- `player/geometry.json` + `skin.png`：基岩方块骨骼皮肤；可选 `avatar.json` 指定几何 ID、文件名或 skins.json 中的皮肤条目。
- `default`：没有单独配置时使用；完整 UUID 名称只配置对应玩家。优先级为 UUID、本机 player、default、账户皮肤。

同一名称不能同时启用文件和文件夹，或包含歧义的多个模型。加载失败会显示错误并保留完整的上一个可用外观。`/fsrpg-avatar reload` 或 K 页模型重载生效；移走配置再重载恢复账户皮肤。外观仅影响本机显示，不自动同步至其他客户端。

详细文件布局与骨名约定见 [接口说明](../config/fsrpg/avatars/README.md)。首次运行只补种说明与 `examples/player.bbmodel`，示例不会自动启用，已有文件不会被覆盖。

## 官方皮肤与几何边界

Java PNG 继续使用官方玩家模型、皮肤外层开关和 Steve / Alex 渲染器，第一人称也采用同一贴图与体型。基岩导入保留骨骼和凸出的真实方块几何，支持 legacy / modern 文档、镜像、box UV、逐面 UV、骨与方块旋转。

基础人形需命名 head、body、right_arm、left_arm、right_leg、left_leg（支持官方基岩 camelCase）。扩展前臂、手、小腿和脚可接入已有动作，饰物继承父骨。第三人称和第一人称手臂均接入；实际武器继续走官方 ItemStack 渲染。

六骨模型的四肢整体转动，不自动将一个方块拆成肘膝关节。任意非标准身材与原版盔甲、披风和现有武器握点仍需实物适配。BB 自带动画、mesh、Molang、自定义材质脚本、基岩 poly_mesh / texture_meshes 等未支持内容明确拒绝；加密商城资产和缺失完整几何的角色创建器服饰不属于本次直接导入范围。不能据此承诺所有“官方 3D 皮肤”文件都兼容。

## 验证与交付

完整 `gradlew.bat build --offline` 通过，包含 95 个独立回归测试任务。新增 AvatarSkin / AvatarCatalog / BlockbenchAvatar / BedrockAvatar / AvatarPose 五个测试共 5507 项检查；另以 Mojang 官方 humanoid.custom.geo.json 进行几何验收。开发 smoke 与 monitorAgent 不进入发行 JAR。

最终 JAR 的 DISPLAY5 实机证据位于 `verification/avatar-20261030/`。人物专项覆盖 Java 内外层与体型、BB、官方基岩模型正背面、第一人称、实际挥剑、错误重载保留、删除回退及开发配置原样恢复；继承的镜头与交互另做同包 75 项检查和独立进程 7 项保存重载检查。源录像按实际渲染帧和真实时间戳验证编码。

发行 JAR：`build/libs/fsrpg-neoforge-0.11.0.jar`。SHA-256：`3c18f0e3581cc2c51649f71866f421d8bae3bdc16bafd7757a3b6b735590d66b`。

部署记录见 `verification/avatar-20261030/deployment.json`。部署只替换本 Mod，备份旧 JAR、完整配置与 options；仅补种缺失的外观说明和未启用示例，保留现有自定义模型、其他 Mod 和用户存档。

已于 2026-10-30 安装至官方实例 `mods/fsrpg-neoforge-0.11.0.jar`，安装哈希与上述实测 JAR 一致。原 89 个配置文件及 options 逐字节保留，备份目录为 `fsrpg-backups/20261030-014202-753-before-0.11.0-83e3b0e7/`。
