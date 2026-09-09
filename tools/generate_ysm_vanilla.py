#!/usr/bin/env python3
"""1:1 retarget of actioncombat GeckoLib rigs into YSM bone names.

No damping. Forearms, hands and extra torso bones stay as children so the
authored motion is preserved. GeckoLib plays the same files the YSM pack ships.
"""
from __future__ import annotations

import json
import shutil
import zipfile
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GEO_DIR = ROOT / "src/main/resources/assets/actioncombat/geckolib/models"
ANIM_DIR = ROOT / "src/main/resources/assets/actioncombat/geckolib/animations"
OUT = ROOT / "ysm/packs/actioncombat_vanilla"
MC_RES = ROOT / "build/moddev/artifacts/neoforge-21.11.45-client-extra-aka-minecraft-resources.jar"

# Original rig -> YSM-style names. Hierarchy is kept; only labels change.
BONE_MAP = {
    "root": "Root",
    "lower_body": "Hip",
    "upper_body": "Body",
    "head": "Head",
    "upper_arm_r": "RightArm",
    "forearm_r": "RightForeArm",
    "hand_r": "RightHand",
    "hand_item_r": "RightHandLocator",
    "upper_arm_l": "LeftArm",
    "forearm_l": "LeftForeArm",
    "hand_l": "LeftHand",
    "hand_item_l": "LeftHandLocator",
    "leg_r": "RightLeg",
    "lower_leg_r": "RightForeLeg",
    "foot_r": "RightFoot",
    "leg_l": "LeftLeg",
    "lower_leg_l": "LeftForeLeg",
    "foot_l": "LeftFoot",
}

SETS = [
    {
        "geo": "player_longsword.geo.json", "anim": "third_person_spear.animation.json",
        "out_geo": None, "out_anim": "ysm_player_spear.animation.json",
        "identifier": "geometry.actioncombat.ysm_player", "ysm_main": False,
    },
    {
        "geo": "player_longsword.geo.json",
        "anim": "third_person_singlesword.animation.json",
        "out_geo": "ysm_player.geo.json",
        "out_anim": "ysm_player.animation.json",
        "identifier": "geometry.actioncombat.ysm_player",
        "ysm_main": True,
    },
    {
        "geo": "player_doublesword.geo.json",
        "anim": "third_person_doublesword.animation.json",
        "out_geo": "ysm_player_dual.geo.json",
        "out_anim": "ysm_player_dual.animation.json",
        "identifier": "geometry.actioncombat.ysm_player_dual",
        "ysm_main": False,
    },
    {
        "geo": "player_longsword.geo.json",
        "anim": "third_person_katana.animation.json",
        "out_geo": None,
        "out_anim": "ysm_player_katana.animation.json",
        "identifier": "geometry.actioncombat.ysm_player",
        "ysm_main": False,
    },
]

MAIN_CLIPS = {
    "empty", "walk", "run", "climbing", "climb", "sneaking", "sneak", "swim",
    "swim_stand", "attacked", "jump", "fly", "elytra_fly", "boat",
    "use_mainhand", "use_offhand", "swing_hand", "swing_offhand", "sleep",
    "ride", "ride_pig", "sit", "idle", "riptide", "death",
    "ladder_up", "ladder_stillness", "ladder_down",
}
MAIN_ALIASES = {
    "idle": "static_idle",
    "walk": "walk_hold",
    "run": "run_hold",
    "sneak": "crouch_idle",
    "sneaking": "crouch_idle",
    "jump": "jump",
    "attacked": "hurt",
    "swing_hand": "attack4",
    "swing_offhand": "attack1",
    "use_mainhand": "attack4",
    "use_offhand": "attack1",
}


def rename(name: str) -> str:
    return BONE_MAP.get(name, name)


def convert_geo(src: dict, identifier: str) -> dict:
    geo = deepcopy(src)
    geom = geo["minecraft:geometry"][0]
    geom["description"]["identifier"] = identifier
    for bone in geom["bones"]:
        bone["name"] = rename(bone["name"])
        if "parent" in bone:
            bone["parent"] = rename(bone["parent"])
    return geo


def convert_anim(src: dict) -> dict:
    out = {"format_version": src.get("format_version", "1.8.0"), "animations": {}}
    for clip_name, clip in src.get("animations", {}).items():
        converted = deepcopy(clip)
        bones = converted.get("bones")
        if isinstance(bones, dict):
            converted["bones"] = {rename(k): v for k, v in bones.items()}
        out["animations"][clip_name] = converted
    return out


def add_ysm_aliases(anims: dict) -> None:
    """YSM main.animation.json requires vanilla clip names; alias our clips."""
    if "empty" not in anims:
        anims["empty"] = {"loop": True, "animation_length": 0.05, "bones": {}}
    for ysm_name, src_name in MAIN_ALIASES.items():
        if ysm_name not in anims and src_name in anims:
            anims[ysm_name] = deepcopy(anims[src_name])
    # leftover required mains fall back to idle/jump so YSM does not missing-clip
    fallback = anims.get("idle") or next(iter(anims.values()))
    for name in MAIN_CLIPS:
        if name not in anims:
            anims[name] = deepcopy(fallback)


def dump(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def copy_steve(dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not MC_RES.is_file():
        raise SystemExit(f"missing vanilla resources jar: {MC_RES}")
    with zipfile.ZipFile(MC_RES) as z:
        dest.write_bytes(z.read("assets/minecraft/textures/entity/player/wide/steve.png"))


def ysm_json(extra_names: list[str]) -> dict:
    extras = {name: name for name in extra_names}
    return {
        "spec": 2,
        "metadata": {
            "name": "动作战斗",
            "tips": "太刀模组全部第三人称动画的 YSM 骨骼转换（动作原样保留）。",
            "license": {"type": "CC 0", "desc": "与动作战斗模组一同分发"},
            "authors": [{"name": "actioncombat", "role": "动画转换"}],
        },
        "properties": {
            "height_scale": 0.9375,
            "width_scale": 0.9375,
            "extra_animation": extras,
            "preview_animation": "idle",
        },
        "files": {
            "player": {
                "model": {
                    "main": "models/main.json",
                    "arm": "models/arm.json",
                },
                "animation": {
                    "main": "animations/main.animation.json",
                    "arm": "animations/arm.animation.json",
                    "extra": "animations/extra.animation.json",
                },
                "texture": ["textures/steve.png"],
            },
            "language_path": "lang",
        },
    }


def arm_from_main(geo: dict) -> dict:
    keep = {"LeftArm", "RightArm", "LeftForeArm", "RightForeArm",
            "LeftHand", "RightHand", "LeftHandLocator", "RightHandLocator"}
    out = deepcopy(geo)
    geom = out["minecraft:geometry"][0]
    geom["description"]["identifier"] = "geometry.actioncombat.ysm_player.arm"
    bones = []
    for bone in geom["bones"]:
        if bone["name"] not in keep:
            continue
        b = deepcopy(bone)
        if b["name"] in ("LeftArm", "RightArm"):
            b.pop("parent", None)
        bones.append(b)
    geom["bones"] = bones
    return out


def split_main_extra(anims: dict) -> tuple[dict, dict]:
    main, extra = {}, {}
    for name, clip in anims.items():
        if name in MAIN_CLIPS:
            main[name] = clip
        else:
            extra[name] = clip
    return main, extra


def main() -> None:
    converted = []
    for spec in SETS:
        geo = json.loads((GEO_DIR / spec["geo"]).read_text())
        anim = json.loads((ANIM_DIR / spec["anim"]).read_text())
        geo_out = convert_geo(geo, spec["identifier"])
        anim_out = convert_anim(anim)
        add_ysm_aliases(anim_out["animations"])
        if spec["out_geo"]:
            dump(GEO_DIR / spec["out_geo"], geo_out)
        dump(ANIM_DIR / spec["out_anim"], anim_out)
        converted.append((spec, geo_out, anim_out))
        print(f"geckolib {spec['out_geo']} / {spec['out_anim']} "
              f"({len(anim_out['animations'])} clips, {len(geo_out['minecraft:geometry'][0]['bones'])} bones)")

    # YSM folder pack from the singlesword conversion
    spec, geo_out, anim_out = converted[0]
    if OUT.exists():
        shutil.rmtree(OUT)
    main_clips, extra_clips = split_main_extra(anim_out["animations"])
    dump(OUT / "models/main.json", geo_out)
    dump(OUT / "models/arm.json", arm_from_main(geo_out))
    dump(OUT / "animations/main.animation.json",
         {"format_version": "1.8.0", "animations": main_clips})
    dump(OUT / "animations/extra.animation.json",
         {"format_version": "1.8.0", "animations": extra_clips})
    dump(OUT / "animations/arm.animation.json", {
        "format_version": "1.8.0",
        "animations": {
            "idle": deepcopy(main_clips.get("idle") or extra_clips.get("static_idle") or {"bones": {}}),
            "swing": deepcopy(main_clips.get("swing_hand") or extra_clips.get("attack4") or {"bones": {}}),
        },
    })
    extra_names = [n for n in extra_clips
                   if not n.endswith("_upper") and n not in ("static_idle",)]
    dump(OUT / "ysm.json", ysm_json(extra_names))
    dump(OUT / "lang/zh_cn.json", {"name": "动作战斗", "tips": "原动作 1:1 转 YSM 骨骼"})
    copy_steve(OUT / "textures/steve.png")
    (OUT / "README.md").write_text(
        "# 动作战斗 YSM 包\n\n"
        "第三人称太刀动画按 YSM 骨骼名 **原样转换**（保留前臂/手/小腿，不压幅度）。\n\n"
        "模组内置同一套文件：拔刀后第三人称直接播 `ysm_player` / `ysm_player_dual`。\n"
        "也可把本文件夹或 zip 放进 Yes Steve Model 的 `custom` 目录。\n",
        encoding="utf-8",
    )

    zip_path = ROOT / "ysm/packs/actioncombat_vanilla.zip"
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in OUT.rglob("*"):
            if f.is_file():
                z.write(f, Path("actioncombat_vanilla") / f.relative_to(OUT))
    print(f"wrote {OUT}")
    print(f"zip   {zip_path} ({zip_path.stat().st_size} bytes)")
    print("extra:", ", ".join(extra_names[:20]), "..." if len(extra_names) > 20 else "")


if __name__ == "__main__":
    main()
