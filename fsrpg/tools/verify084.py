"""Read-only verification of the exact 0.8.4 staged configuration and protocol.

The deployment wrapper independently checks native evidence, byte hashes and
the official instance. This helper never writes or migrates a user file.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import zipfile


CHANGED = ("combat.json", "styles.json", "guard_cues.json")
BACKUP = "defense-flow-backup-v1"
MARKER = "defense-flow-migration-v1.json"
ADDED = {MARKER, f"{BACKUP}/plan.json", *(f"{BACKUP}/{name}" for name in CHANGED)}
RAPIER_MODELS = {f"models/{name}" for name in ("legacy_rapier.fsvoxel", "legacy_rapier_fp.fsvoxel")}
PREVIOUS_JAR_SHA = "37087898c6ab6515c6d609390c3441e94d9c0f4ab3a9dd79ecebaa5f0f90a0ed"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pairs(items: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in items:
        require(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"Nonfinite JSON: {value}")))


def tree(root: Path) -> dict[str, str]:
    result = {}
    seen = set()
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in [*dirs, *files]:
            path = Path(directory, name)
            require(not (getattr(path.lstat(), "st_file_attributes", 0) & 0x400)
                    and not path.is_symlink(), f"Linked configuration path: {path}")
        for name in files:
            path = Path(directory, name)
            relative = path.relative_to(root).as_posix()
            require(relative.casefold() not in seen, f"Case-colliding configuration path: {relative}")
            seen.add(relative.casefold())
            result[relative] = sha(path)
    return result


def manifest(value, label: str) -> dict[str, str]:
    require(isinstance(value, dict), f"Missing {label} hash map")
    seen = set()
    for path, digest in value.items():
        require(isinstance(path, str) and path and "\\" not in path and not path.startswith("/")
                and ":" not in path and all(part not in ("", ".", "..") for part in path.split("/")),
                f"Invalid {label} path: {path}")
        require(path.casefold() not in seen, f"Case-colliding {label} path: {path}")
        require(isinstance(digest, str) and len(digest) == 64
                and all(c in "0123456789abcdef" for c in digest), f"Invalid {label} SHA: {path}")
        seen.add(path.casefold())
    return value


def integer(value, maximum: int, name: str) -> int:
    require(type(value) is int and 0 <= value <= maximum, f"Invalid integer {name}")
    return value


def increment_guard(guard: dict) -> None:
    previous = integer(guard["perfectGuardTicks"], 72_000, "perfectGuardTicks")
    if previous > 0:
        guard["perfectGuardTicks"] = min(72_000, previous + 1)


def expected_configuration(previous: dict) -> dict:
    expected = copy.deepcopy(previous)
    combat = expected["combat.json"]
    increment_guard(combat)
    if "weaponClash" not in combat:
        combat["weaponClash"] = {"contactWindowTicks": 3, "damageTolerance": 3}
    else:
        clash = combat["weaponClash"]
        require(isinstance(clash, dict) and set(clash) == {"contactWindowTicks", "damageTolerance"}, "Invalid existing weaponClash object")
        clash["contactWindowTicks"] = min(10, integer(clash["contactWindowTicks"], 10, "contactWindowTicks") + 1)
        tolerance = clash["damageTolerance"]
        require(type(tolerance) in (int, float) and math.isfinite(tolerance) and 0 <= tolerance <= 1_000_000,
                "Invalid existing damage tolerance")
    for style in expected["styles.json"]:
        for name in ("guard", "heavyGuard"):
            if name in style and style[name] is not None:
                require(isinstance(style[name], dict), f"Invalid style {name}")
                increment_guard(style[name])
    cue = expected["guard_cues.json"]
    require(type(cue["enabled"]) is bool, "Invalid guard cue enabled flag")
    if cue["enabled"]:
        cue["inputWindowTicks"] = min(60, integer(cue["inputWindowTicks"], 60, "inputWindowTicks") + 1)
        cue["flashLeadTicks"] = max(integer(cue["flashLeadTicks"], 60, "flashLeadTicks"), cue["inputWindowTicks"])
    return expected


def protocol(jar: Path) -> None:
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate release JAR entries")
        for name in ("org/fsrpg/network/RpgGuardImpactPayload.class", "org/fsrpg/client/GuardHurtMotion.class",
                     "org/fsrpg/client/GuardImpactPresentation.class", "org/fsrpg/mixin/GuardDamagePacketMixin.class",
                     "org/fsrpg/mixin/GuardDamageCameraMixin.class"):
            require(name in names, f"Missing guard presentation class: {name}")
        data = archive.read("org/fsrpg/FsRpg.class")
    require(data[:4] == b"\xca\xfe\xba\xbe", "Invalid FsRpg class")
    count = struct.unpack_from(">H", data, 8)[0]
    index, offset, utf8, string_refs = 1, 10, {}, []
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            utf8[index] = data[offset:offset + size].decode("utf-8", errors="replace")
            offset += size
        elif tag == 8:
            string_refs.append(struct.unpack_from(">H", data, offset)[0])
            offset += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f"Unsupported class constant tag {tag}")
        index += 1
    constants = {utf8[value] for value in string_refs}
    require("11" in constants and "10" not in constants and "registrar" in utf8.values()
            and "org/fsrpg/network/RpgGuardImpactPayload" in utf8.values(),
            "FsRpg does not establish protocol 11 and the new guard payload registration")


def verify_assets(manifest_path: Path, jar: Path, previous_jar: Path) -> list[dict]:
    upgrade = read(manifest_path)
    require(upgrade.get("version") == "0.8.4" and upgrade.get("format") == "fsrpg.rapier-rework.v1",
            "Unexpected rapier asset manifest version/format")
    rows = upgrade.get("assets")
    require(isinstance(rows, list) and len(rows) == 2, "Expected exactly two authorized rapier assets")
    require({row.get("relativePath") for row in rows} == RAPIER_MODELS, "Unexpected upgraded rapier model inventory")
    by_entry = {}
    for row in rows:
        entry = "assets/fsrpg/fsvoxel/" + row["relativePath"]
        require(row.get("entry") == entry, "Rapier manifest entry/relative path disagree")
        manifest({entry: row.get("beforeSha256")}, "rapier before")
        manifest({entry: row.get("afterSha256")}, "rapier after")
        require(row["beforeSha256"] != row["afterSha256"], "A declared rapier replacement must have new bytes")
        by_entry[entry] = row
    aliases = upgrade.get("files")
    require(isinstance(aliases, list) and len(aliases) == 2, "Missing authoring manifest file aliases")
    for row in rows:
        matches = [value for value in aliases if value.get("path") == "src/main/resources/" + row["entry"]]
        require(len(matches) == 1 and matches[0].get("originalSha256") == row["beforeSha256"]
                and matches[0].get("newSha256") == row["afterSha256"]
                and matches[0].get("geometryUnchanged") is True and matches[0].get("thrustFourthUnchanged") is True,
                "Rapier authoring and deployment manifest hashes or preservation claims disagree")
    require(sha(previous_jar) == PREVIOUS_JAR_SHA, "Previous JAR is not the tested/deployed official 0.8.3 release")
    with zipfile.ZipFile(jar) as current, zipfile.ZipFile(previous_jar) as previous:
        old_names, new_names = previous.namelist(), current.namelist()
        require(len(old_names) == len(set(old_names)) and len(new_names) == len(set(new_names)), "Duplicate JAR entry inventory")
        assets = {name for name in old_names if name.startswith("assets/") and not name.endswith("/")}
        require(len(assets) == 148 and assets == {name for name in new_names if name.startswith("assets/") and not name.endswith("/")},
                "Release must retain the same exact 148 asset names")
        for entry in assets:
            old_bytes, new_bytes = previous.read(entry), current.read(entry)
            if entry in by_entry:
                row = by_entry[entry]
                require(hashlib.sha256(old_bytes).hexdigest() == row["beforeSha256"]
                        and hashlib.sha256(new_bytes).hexdigest() == row["afterSha256"],
                        f"Rapier asset differs from the original/new tested manifest: {entry}")
            else:
                require(old_bytes == new_bytes, f"An asset outside the two rapier replacements changed: {entry}")
    return rows


def verify_models(config: Path, stage: Path, report: dict, assets: list[dict], jar: Path) -> list[dict]:
    rows = report.get("modelUpgrade")
    require(isinstance(rows, list) and len(rows) == 2 and {row.get("relativePath") for row in rows} == RAPIER_MODELS,
            "Stage report must account for both authoritative rapier configuration models")
    expected = []
    with zipfile.ZipFile(jar) as archive:
        for asset in assets:
            name = asset["relativePath"]
            original, candidate = config / name, stage / name
            before = sha(original) if original.is_file() else None
            stock = before == asset["beforeSha256"]
            already_current = before == asset["afterSha256"]
            if stock or already_current:
                require(candidate.is_file() and candidate.read_bytes() == archive.read(asset["entry"]),
                        f"Stock rapier configuration must exactly match the new JAR model: {name}")
                after = asset["afterSha256"]
            else:
                require(original.is_file() == candidate.is_file(), f"Missing/custom model inventory changed: {name}")
                if original.is_file():
                    require(original.read_bytes() == candidate.read_bytes(), f"A custom rapier model was overwritten: {name}")
                after = before
            value = {"relativePath": name, "oldSha256": before, "newSha256": after,
                     "applied": stock, "customPreserved": not (stock or already_current)}
            matches = [row for row in rows if row.get("relativePath") == name]
            require(len(matches) == 1 and all(matches[0].get(key) == field for key, field in value.items())
                    and type(matches[0].get("applied")) is bool and type(matches[0].get("customPreserved")) is bool,
                    f"Stage model-upgrade decisions disagree with the actual original/candidate bytes: {name}")
            expected.append(value)
    return expected


def verify(config: Path, stage: Path, report_path: Path, jar: Path, asset_manifest: Path, previous_jar: Path) -> dict:
    require(config != stage and stage.name == "fsrpg" and "verification" in stage.parts,
            "Configuration candidate must be an isolated verification/fsrpg directory")
    report = read(report_path)
    require(report.get("passed") is True and report.get("version") == "0.8.4", "Stage084Config did not pass")
    require(Path(report["directory"]).resolve(strict=True) == stage, "Stage report points at another candidate")
    before = manifest(report.get("beforeSha256"), "before")
    after = manifest(report.get("afterSha256"), "after")
    require(len(before) >= 54 and before == tree(config), "Official configuration changed since it was staged")
    require(after == tree(stage), "Staged configuration no longer matches its full after manifest")
    require(not (set(before) & ADDED), "Official configuration already contains this migration")
    require(set(after) - set(before) == ADDED and not (set(before) - set(after)), "Only five exact migration artifacts may be added")
    changed = {name for name in before if before[name] != after[name]}
    allowed_changes = set(CHANGED) | RAPIER_MODELS
    require(changed <= allowed_changes, f"Unexpected changed configuration files: {changed - allowed_changes}")
    previous = {name: read(config / name) for name in CHANGED}
    current = {name: read(stage / name) for name in CHANGED}
    require(report.get("previous") == previous and report.get("current") == current,
            "Stage report pre/post objects differ from the actual files")
    expected = expected_configuration(previous)
    require(current == expected, "Staged migration changes fields outside the exact defense-flow allowlist")
    for name in CHANGED:
        require((stage / BACKUP / name).read_bytes() == (config / name).read_bytes(), f"Original migration backup changed: {name}")
    plan = read(stage / BACKUP / "plan.json")
    require(plan.get("existingCombat") is True and plan.get("existingCue") is True
            and plan.get("candidates") == expected, "Durable migration plan differs from the verified field transformation")
    marker = read(stage / MARKER)
    require(marker.get("releaseVersion") == "0.8.4" and marker.get("windowDeltaTicks") == 1
            and marker.get("disabledWindowsPreserved") is True and marker.get("unrelatedFieldsPreserved") is True
            and marker.get("continuousPerfectGuardLimit") == 3, "Incorrect migration completion marker")
    assets = verify_assets(asset_manifest, jar, previous_jar)
    models = verify_models(config, stage, report, assets, jar)
    protocol(jar)
    return {"passed": True, "version": "0.8.4", "networkProtocol": 11, "beforeSha256": before,
            "afterSha256": after, "changedFiles": sorted(changed), "addedFiles": sorted(ADDED),
            "stagedFileCount": len(after), "previousFileCount": len(before), "stageReportSha256": sha(report_path),
            "assetManifestSha256": sha(asset_manifest), "assetUpgrade": assets, "modelUpgrade": models,
            "assetFileCount": 148, "unchangedAssetFileCount": 146, "previousJarSha256": sha(previous_jar),
            "jarSha256": sha(jar), "configurationFilesToWrite": sorted(changed | ADDED,
                key=lambda name: (0 if name.startswith(BACKUP + "/") else 2 if name == MARKER else 1, name))}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--asset-manifest", type=Path, required=True)
    parser.add_argument("--previous-jar", type=Path, required=True)
    args = parser.parse_args()
    result = verify(*(path.resolve(strict=True) for path in (args.config, args.stage, args.report, args.jar, args.asset_manifest, args.previous_jar)))
    print(json.dumps(result, ensure_ascii=False, allow_nan=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
