Action Combat
=============

Minecraft 1.21.11 / NeoForge 21.11.45 / GeckoLib 5.4.5.

An action-game combat layer: draw-sword combat mode, alternating left/right swings,
layered animation, and a Genshin/Zelda-style third-person camera. This covers combat
*feel* only — no stamina, spirit, level or attribute systems.

Current controls (rapier / dual swords)
---------------------------------------

See [西洋剑与双持更新](docs/西洋剑与双持更新.md) for the current rules and Workbench sources.

| Input | Single main-hand sword (rapier) | Dual swords |
| --- | --- | --- |
| R | Draw / sheathe | Draw / sheathe |
| Left mouse | Random authored attack; crosshair required | Left swing chain |
| Right mouse | Instant thrust; 2s cooldown, 1s recovery only on miss | Right swing chain |
| X | Guard; perfect at 0.05–0.30s | Guard; perfect at 0.05–0.30s |
| Ctrl+W during guard | Normal sprint | One radial sword bash per raised guard |
| Shift | 2-block short dodge | 4.5-block dodge |
| C | Crouch | Crouch |
| V | Inspect | Inspect |

Normal attacks retain the 0.30s gate and 0.40s clip. Rapier thrust hits are resolved
immediately by the server, with no windup. On hit another action can begin immediately;
on miss attacks, guard and dodge are gated for 1s. Its separate 2s cooldown survives
sheathing and weapon changes. Vanilla interactions remain available while sheathed.

Camera and body orientation
---------------------------

Third-person drawn combat uses a Genshin-style orbit (camera behind the player,
no over-the-shoulder offset). Unlocked: the mouse orbits the camera, WASD is
camera-relative, and the combat body faces the aim. Side steps turn the hips and
legs sideways while the upper body keeps looking forward; backward movement uses
its own retreat cycle. During a swing, body and camera lock together.

First person keeps the player, camera and hand rig facing together. The hand renderer
preserves the camera rotation supplied by `RenderHandEvent`; clearing that matrix
would make the hands rotate with the world instead of following the view.

Upper-body overlays (`attack*_upper`, `defend_upper`) play on top of the locomotion
state machine (air / crouch / run / walk / idle), so you can walk-attack and
walk-parry. Directional crouch movement and animated jump/fall poses keep the legs
active under combat overlays. Impact feedback does not pause the locomotion clock.
First person renders only arm/skin/sleeve meshes and the current held items;
the local player's complete body and its equipment layers are excluded.

Hits are scanned along the camera look — from the player's eyes toward the point
under the custom crosshair — so the reticle is the actual impact.

Blade trail and sparks
----------------------

The first-person rig ships five `daoguang*` trail quads and four `spark*` quads that no
authored clip touches, so they sat stacked at their rest pose in view of the camera.
`BladeVfx` poses them procedurally instead: the trail quads fan out along the swing arc
and dissolve tail-first, and the sparks scatter briefly on a confirmed hit. Both are
hidden outright when idle, since zero-thickness quads do not reliably scale to nothing.

The bone names are pinned by `check_assets.mjs` against the geo, read out of the Java, so
a rename cannot silently switch the effect off.

Weapon display
--------------

While sheathed the sword sits on the player's back rather than in their hand: the hand
item's render state is cleared and `BackWeaponLayer` draws the weapon off the torso bone,
so it follows the body when turning, leaning or crouching. Drawing moves it back to the
hand, which is where the rig models it.

The combat dummy also mirrors the player's armour, so entering combat mode no longer
makes armour disappear. That required the dummy to be a `LivingEntity` (GeckoLib's armour
layer fits vanilla humanoid armour models onto named rig bones and needs equipment slots
to read), so it registers a minimal attribute supplier and overrides `tick()` to nothing —
it is posed entirely by client code and is never really in the world. The rig has no
separate foot bones, so boots ride the lower leg.

Animation layers
----------------

The third-person body is a client-only dummy entity wearing the GeckoLib rig; the
vanilla body is hidden while it shows. Two controllers run in parallel:

- **locomotion** — always drives the stance underneath: idle, walk, run, crouch, jump,
  fall. Draw, dodge and hurt still take this layer (full body).
- **action** — upper-body overlay: swings, guard, charge. Attack clips are stripped
  of leg bones (`attack1_upper`, …) so a walk cycle keeps playing during a slash.

Author attacks on the torso/arms only (`upper_body` and above). Do not key `root`,
`lower_body` or the legs on a swing, or they will freeze the stride. The bone split
is in `docs/上下半身动画约定.md`. The pipeline still accepts a full-body authored
swing and generates the overlay automatically.

The dual-sword first-person rig is generated from the third-person arm hierarchy,
including its torso ancestors, shoulder, forearm and hand pivots. Body and blade meshes
are omitted; arms use the current player skin and hand locators render the actual held
items. Base stance and upper-body action use the dummy's state and action clock.

`generate_fp_swings.mjs` copies the actual third-person channels and keyframe times:
FP `attack1/attack3` map to 3P `attack_1/attack2` (leftward, left/right hand), and
FP `attack2/attack4` map to 3P `attack4/attack3` (rightward, left/right hand).
The single-sword viewmodel retains its existing authored conversion.

Asset pipeline
--------------

The supplied packs contain only `static_idle` and a run cycle, so the missing clips are
generated. These tools run automatically before `processResources`, so generated
data can never go stale:

    node tools/generate_locomotion.mjs      # walk cycle, crouch/jump/fall/hurt, upper-body variants
    node tools/generate_dual_slash.mjs       # third-person simultaneous slash and its overlay
    node tools/generate_fp_swings.mjs        # dual FP skeleton and clips from final third-person assets
    node tools/generate_attack_timings.mjs  # per-clip contact/combo/cancel windows
    node tools/check_assets.mjs             # verifies Java references match the assets
    node tools/check_fp_third_person.mjs     # dual FP joints, channels, aliases and blade trajectories

`generate_locomotion.mjs` derives the walk cycle from the authored run cycle (damped
toward the idle pose and time-stretched, so it keeps the artist's weight and timing) and
builds the crouch/jump/fall/hurt poses procedurally. These are usable, not final art.
The script is idempotent and never uses its own output as a source.

`generate_attack_timings.mjs` measures where the blade actually travels fastest across
the arm chain and records the contact frames, combo window and cancel point per clip,
into `src/main/resources/actioncombat/attack_timings.json`. This replaces applying one
fixed 30%–60% ratio to every swing, which made slow clips connect too early and fast
ones too late.

Verification
------------

    ./gradlew build              # includes generators, check_assets and unit tests
    ./gradlew smokeAssets        # bakes assets through the real GeckoLib pipeline
    ./gradlew runGameTestServer  # 13 server-side gametests

`smokeAssets` catches asset problems that only appear at bake time — it is what caught
generated keyframes being emitted in an order GeckoLib rejects. The gametests cover the
server-authoritative rules: draw gating, contact windows, alternation (including that
the same side cannot chain), guard reduction, charged openers, that every attack clip has
a generated timing entry rather than silently falling back to ratios, and that the
animation dummy still constructs (a missing attribute registration would break combat mode
the moment a player draws).

The smoke test also checks the first-person transform at 24 camera-angle/rig
combinations. Visual framing, animation blending and hit feel still need checking in
HMCL's `gpt` instance: turn through all directions, look up/down, exercise both hands
in both slash directions, and switch F5 during a swing.

Upstream template notes
-----------------------

Mappings are Mojang official names; see
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md for the license.
Run `gradlew --refresh-dependencies` to refresh the local cache, `gradlew clean` to
reset build output.

Community Documentation: https://docs.neoforged.net/
NeoForged Discord: https://discord.neoforged.net/
