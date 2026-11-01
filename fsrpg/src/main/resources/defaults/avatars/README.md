# 外置玩家外观

此目录是客户端接口。正式游戏目录为 `config/fsrpg/avatars/`。外观仅在安装了这些文件的客户端可见，不自动上传或同步给其他玩家。

最简单的命名：

```text
avatars/
  player.bbmodel      本机玩家使用的 BB 模型（可改用下面任一种）
  player.png          Java 经典四像素手臂皮肤
  player.slim.png     Java 纤细三像素手臂皮肤
  default.bbmodel     未单独指定的所有玩家外观
  <玩家UUID>.bbmodel  只指定该玩家
```

同一名称只能选一种外观，不能同时放 player.bbmodel 和 player/ 文件夹。优先级为 **UUID > 本机 player > default > 原版账户皮肤**。examples/ 中的示例不参与加载；复制其中的模型到 player.bbmodel 才会启用。

也可使用文件夹，名称同样为 player、default 或玩家完整 UUID：

```text
avatars/player/
  model.bbmodel                 BB：贴图内嵌或使用文件夹内相对路径

avatars/player/
  skin.png                      Java：64×64 或原版旧式 64×32
  avatar.json                   可选：{"type":"skin","slim":true}

avatars/player/
  geometry.json                 基岩版未加密的方块骨骼几何
  skin.png
  avatar.json                   多几何时指定：{"geometry":"geometry.my_avatar"}
```

基岩皮肤包可将 skins.json、geometry.json 和贴图放在同一个 player/ 文件夹。包含多个皮肤时，用 avatar.json 的 `skin` 选择 skins.json 中的 localization_name；几何文件名称不同时再指定 `model`。例如：

```json
{"type":"bedrock","model":"humanoid.geo.json","skin":"my_skin","geometry":"geometry.my_avatar"}
```

BB 默认读取 model.bbmodel，也可用 `{"type":"blockbench","model":"我的角色.bbmodel"}` 指定文件。

## 人物骨骼命名与制作

基础骨骼必须存在：`head`、`body`、`right_arm`、`left_arm`、`right_leg`、`left_leg`。也识别官方基岩的 rightArm / leftArm / rightLeg / leftLeg，以及 Head / Body 等大小写。头发、衣饰作为对应骨骼的子组随父骨运动。

BB 坐标以脚底 Y=0、头顶 Y=32 为标准；正面朝 -Z，角色右侧为 +X。标准肩部为 (±5,22,0)，髋部为 (±1.9,12,0)，头部为 (0,24,0)。非标准比例可加载，但原版护甲和实际武器握点仍按现有玩家体型，需要实物校准。

可选分段骨骼：right_forearm / left_forearm、right_hand / left_hand、right_shin / left_shin、right_foot / left_foot。前臂、手和小腿可接收现有动作的关节姿态；六骨人物的四肢整体跟随原版姿态。不会自动为未拆分的网格生成肘膝关节。

BB 与基岩方块几何支持贴图、独立方块旋转、骨骼层级和 UV；不支持的 mesh、表达式或几何特性会明确拒绝，不静默丢弃。模型自带动画、材质脚本、物理、角色创建器服饰及加密商城数据不自动转为 FSRPG 动作。

Java 皮肤保留 Steve / Alex 体型、64×32 旧皮肤左右肢镜像、帽子、外套、双袖及双裤腿，遵循原版底层不透明与外层透明规则。外层随动作一起运动。基岩几何保持原有凸出方块与多层表面，不会压成一张 Java 皮肤。

第三人称、背包人物和第一人称手臂使用此接口。武器仍由实际 ItemStack 官方渲染，伤害、碰撞和服务器数值不由外观决定。原版护甲、披风继续使用原版形状。

## 使用

放好文件后输入 `/fsrpg-avatar reload`。`/fsrpg-avatar` 显示数量和目录；K 编辑器的模型重载也会重载外观。重载校验失败保留上一份可用外观，并显示错误。移走对应文件并重载即可恢复原版。

第一次启动加载失败时使用原版账户外观。不会改写用户模型、皮肤或配置。
