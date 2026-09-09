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
| Right mouse | Instant thrust (0.50s, charged 0.20s); 2s cooldown, 1s recovery only on miss | Right swing chain |
| X | Guard; perfect at 0.05–0.30s | Guard; perfect at 0.05–0.30s |
| Ctrl+W during guard | Normal sprint | One radial sword bash per raised guard |
| Shift | 2-block scripted dodge step | 0.2s max-speed dash boost |
| C | Crouch | Crouch |
| V | Inspect | Inspect |

Every clip is 0.40s. The rapier chain gates 0.4 / 0.4 / 0.4 / 0.5s, and the fourth slash
scans its hit 0.05s later than the other three; a charged thrust that lands tightens the
chain to a flat 0.3s for 3s. Dual wielding gates 0.45s, and a third swing in
a row on the same hand takes 0.6s - swinging the other hand resets the streak, so L,L,R,R
never slows down. Dual clips hold their final frame 0.1s past the clip so consecutive
swings never flash the idle pose; the cancel window is unchanged. All five dual attack
clips (and their upper overlays) also open and settle on the idle stance, so a combo
never jumps between two different opening poses - `tools/check_combo_boundaries.mjs`
guards that invariant.

Server confirmations never restart a clip that is already on screen. The client adopts
the hand the server reports, mirrors every server-side hand reset (guard, charged swing,
idle timeout, sheath) and keeps the server's gate verbatim instead of clamping it, so a
single mismatch cannot cascade into a combo playing on the wrong arm. Other weapons keep the flat 0.30s gate. Rapier
thrust hits are resolved immediately by the server, with no windup. On hit another action
can begin immediately; on miss attacks, guard and dodge are gated for 1s. Its separate 2s
cooldown survives sheathing and weapon changes. Vanilla interactions remain available
while sheathed.

Every confirmed contact opens its own damage window: vanilla's 10-tick hurt cooldown
would otherwise swallow a second hit of equal damage, which at 0.3-0.6s cadences meant
every other cut dealt nothing.

The dual-wield dash is deliberately not a clip and not a state: Shift raises the
`MOVEMENT_SPEED` attribute (`+300 %`, `ADD_MULTIPLIED_TOTAL` = 4x walking speed) for
0.20s, so a moving player accelerates into a dash on their own and may jump, swing or
guard at any point. It cancels nothing, blocks nothing and moves nobody by script. The
FOV change vanilla derives from that attribute is cancelled in `ComputeFovModifierEvent`,
so the dash changes movement only.

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

The rapier sheathe is an authored flourish (挽剑花): the blade whips through four
reversing turns in front of the chest, is caught, and the right hand crosses the body
to push it down into the imaginary scabbard on the left hip while the left hand catches
the scabbard mouth. `put_away` is 1.65s and `CombatConstants.RAPIER_SHEATH_SECONDS`
tracks it; the first-person viewmodel plays the same beats with the twirl solved so the
tip traces a real circle on screen. `check_rapier.mjs` pins the spin count, the
hand-to-hilt gap and the left-hip end pose so a later tweak cannot silently flatten it.

The locked enemy gets three lines over its marker, all in the vanilla font: name,
current / max health, and the rapier hit streak `n/4`. The fourth hit deprives the
enemy's AI for 1s, and that line switches to the remaining seconds, rebuilt at most
every 0.1s. The server reports the streak through `ClientboundRapierCombo`. The streak
line is green while the enemy is inside attack range (4 blocks, line of sight) and
yellow once it is not.

The charged thrust and the dodge hold the top interrupt priority: either one cancels
whatever is playing - a swing, a guard, a dodge or a recovery - and only answers to its own
cooldown. A plain poke still waits for the current action's cancel frame.

While drawn the FOV is constant. The hit FOV punch is gone and neither sprinting nor the
dual dash widens or pulls back the camera; the `MOVEMENT_SPEED` dash boost is neutralised
in `ComputeFovModifierEvent` and every combat camera rig shares one framing.

A rapier thrust also moves the body: the player is carried 0.5 blocks forward as the
blade extends (at the contact beat, spread over 3 ticks). There is no pull-back - the
lunge only goes forward, and the player keeps the ground they gained.

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
HMCL's `dsh 2` instance: turn through all directions, look up/down, exercise both hands
in both slash directions, and switch F5 during a swing.

Upstream template notes
-----------------------

Mappings are Mojang official names; see
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md for the license.
Run `gradlew --refresh-dependencies` to refresh the local cache, `gradlew clean` to
reset build output.

Community Documentation: https://docs.neoforged.net/
NeoForged Discord: https://discord.neoforged.net/
