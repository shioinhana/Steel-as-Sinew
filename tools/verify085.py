"""Read-only verification of the exact 0.8.5 staged configuration and protocol.

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


CHANGED = ("skills.json", "styles.json", "skill_trees.json")
BACKUP = "rapier-fencing-backup-v1"
MARKER = "rapier-fencing-migration-v1.json"
BASE_ADDED = {MARKER, f"{BACKUP}/plan.json", *(f"{BACKUP}/{name}" for name in CHANGED)}
FIFTH = "rapier_lunge_finish"
SKILL_FIELDS = ("label", "windupTicks", "activeTicks", "recoveryTicks")
RAPIER_MODELS = {f"models/{name}" for name in ("legacy_rapier.fsvoxel", "legacy_rapier_fp.fsvoxel")}
PREVIOUS_JAR_SHA = "ecee3a5904b0801d960406549af4b86395cbbbec8d16a550b29d711dc0a52de4"


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


def find(rows: list[dict], identity: str):
    values = [row for row in rows if row.get("id") == identity]
    require(len(values) <= 1, f"Duplicate catalog ID: {identity}")
    return values[0] if values else None


def expected_configuration(previous: dict, baseline: dict, defaults: dict, models_ready: bool) -> tuple[dict, dict]:
    expected = copy.deepcopy(previous)
    skills, styles = expected["skills.json"], expected["styles.json"]
    rapier, fifth = find(styles, "rapier"), find(skills, FIFTH)
    next_fifth = find(defaults["skills.json"], FIFTH)
    plain_skills = all(find(skills, old["id"]) is not None and all(
        find(skills, old["id"]).get(field) == old.get(field) for field in ("weapon", "input", "model", "action"))
        for old in baseline["skills"])
    stock_chain = rapier is not None and rapier.get("leftCombo") == baseline["leftCombo"]
    compatible_fifth = fifth is None or fifth == next_fifth
    catalog_has_room = fifth is not None or len(skills) < 128
    enabled = models_ready and plain_skills and stock_chain and compatible_fifth and catalog_has_room
    updates = {}
    fifth_added = chain_extended = root_extended = description_updated = False
    if enabled:
        for old in baseline["skills"]:
            actual, new = find(skills, old["id"]), find(defaults["skills.json"], old["id"])
            fields = []
            for field in SKILL_FIELDS:
                if actual.get(field) == old.get(field) and actual.get(field) != new.get(field):
                    actual[field] = copy.deepcopy(new[field])
                    fields.append(field)
            if fields:
                updates[old["id"]] = fields
        if fifth is None:
            skills.append(copy.deepcopy(next_fifth))
            fifth_added = True
        rapier["leftCombo"] = copy.deepcopy(find(defaults["styles.json"], "rapier")["leftCombo"])
        chain_extended = True
        root = find(expected["skill_trees.json"]["nodes"], "rapier")
        fresh_root = find(defaults["skill_trees.json"]["nodes"], "rapier")
        if root is not None and root.get("skills") == baseline["rootSkills"]:
            root["skills"] = copy.deepcopy(fresh_root["skills"])
            root_extended = True
        if root is not None and root.get("description") == baseline["rootDescription"]:
            root["description"] = fresh_root["description"]
            description_updated = True
    reason = ("stock_models_and_chain" if enabled else "custom_missing_or_unavailable_models" if not models_ready
              else "custom_or_missing_animation_skill" if not plain_skills else "custom_or_current_chain" if not stock_chain
              else "custom_fifth_skill_id" if not compatible_fifth else "catalog_full")
    return expected, {"enabled": bool(enabled), "reason": reason, "updatedFields": updates,
                      "fifthAdded": fifth_added, "chainExtended": chain_extended,
                      "rootSkillsExtended": root_extended, "rootDescriptionUpdated": description_updated}


def bundled_configuration(jar: Path, previous_jar: Path, assets: list[dict]) -> tuple[dict, dict]:
    def entry(archive, name):
        return json.loads(archive.read(name).decode("utf-8-sig"), object_pairs_hook=pairs)
    with zipfile.ZipFile(jar) as current, zipfile.ZipFile(previous_jar) as previous:
        baseline = entry(current, "defaults/rapier-fencing-0.8.4.json")
        require(baseline.get("format") == "fsrpg.rapier-fencing.baseline.v1" and baseline.get("version") == "0.8.4",
                "Unexpected fencing migration baseline")
        old_skills = entry(previous, "defaults/skills.json")
        old_rapier = find(entry(previous, "defaults/styles.json"), "rapier")
        old_root = find(entry(previous, "defaults/skill_trees.json")["nodes"], "rapier")
        ids = ("sword_left", "rapier_downcut", "rapier_crosscut", "rapier_light_thrust")
        require(baseline.get("skills") == [find(old_skills, identity) for identity in ids]
                and baseline.get("leftCombo") == old_rapier["leftCombo"]
                and baseline.get("rootSkills") == old_root["skills"]
                and baseline.get("rootDescription") == old_root["description"]
                and baseline.get("models") == {row["relativePath"]: row["beforeSha256"] for row in assets},
                "Bundled fencing baseline differs from the exact installed 0.8.4 release")
        defaults = {name: entry(current, "defaults/" + name) for name in CHANGED}
        expected_ids = (*ids, FIFTH)
        require([row["skillId"] for row in find(defaults["styles.json"], "rapier")["leftCombo"]] == list(expected_ids),
                "Bundled rapier chain does not contain the five stable reviewed skill IDs")
        for index, identity in enumerate(expected_ids):
            value = find(defaults["skills.json"], identity)
            require(tuple(value[field] for field in ("windupTicks", "activeTicks", "recoveryTicks"))
                    == ((4, 3, 5) if index == 4 else (3, 3, 3)), "Bundled fencing cadence differs from its reviewed five-stage contract")
        return baseline, defaults


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
    require(upgrade.get("version") == "0.8.5" and upgrade.get("format") == "fsrpg.rapier-fencing.v1",
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
                and matches[0].get("geometryUnchanged") is True and matches[0].get("officialItemCarrierUnchanged") is True
                and matches[0].get("previousFourAttacksReplaced") is True,
                "Rapier authoring and deployment manifest hashes or preservation claims disagree")
    require(sha(previous_jar) == PREVIOUS_JAR_SHA, "Previous JAR is not the tested/deployed official 0.8.4 release")
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
    require(report.get("passed") is True and report.get("version") == "0.8.5", "Stage085Config did not pass")
    require(Path(report["directory"]).resolve(strict=True) == stage, "Stage report points at another candidate")
    before = manifest(report.get("beforeSha256"), "before")
    after = manifest(report.get("afterSha256"), "after")
    require(len(before) >= 59 and before == tree(config), "Official configuration changed since it was staged")
    require(after == tree(stage), "Staged configuration no longer matches its full after manifest")
    assets = verify_assets(asset_manifest, jar, previous_jar)
    models = verify_models(config, stage, report, assets, jar)
    baseline, defaults = bundled_configuration(jar, previous_jar, assets)
    added = BASE_ADDED | {f"{BACKUP}/{row['relativePath']}" for row in models if row["applied"]}
    require(not any(name == MARKER or name.startswith(BACKUP + "/") for name in before), "Official configuration already contains this migration")
    require(set(after) - set(before) == added and not (set(before) - set(after)), "Only exact fencing backups, plan and completion marker may be added")
    changed = {name for name in before if before[name] != after[name]}
    allowed_changes = set(CHANGED) | RAPIER_MODELS
    require(changed <= allowed_changes, f"Unexpected changed configuration files: {changed - allowed_changes}")
    previous = {name: read(config / name) for name in CHANGED}
    current = {name: read(stage / name) for name in CHANGED}
    require(report.get("previous") == previous and report.get("current") == current,
            "Stage report pre/post objects differ from the actual files")
    expected, skill_upgrade = expected_configuration(previous, baseline, defaults, all(not row["customPreserved"] for row in models))
    require(current == expected, "Staged migration changes fields outside the exact stock-conditional fencing allowlist")
    require(report.get("skillUpgrade") == skill_upgrade, "Stage skill-upgrade decisions disagree with independent original/default comparisons")
    for name in CHANGED:
        require((stage / BACKUP / name).read_bytes() == (config / name).read_bytes(), f"Original migration backup changed: {name}")
    plan = read(stage / BACKUP / "plan.json")
    plan_before = {name: before[name] for name in CHANGED}
    for row in models:
        if row["applied"]:
            name = row["relativePath"]
            plan_before[name] = before[name]
            require((stage / BACKUP / name).read_bytes() == (config / name).read_bytes(), f"Original stock model backup changed: {name}")
    require(plan.get("releaseVersion") == "0.8.5" and plan.get("schemaVersion") == 1
            and plan.get("beforeSha256") == plan_before and plan.get("candidates") == expected
            and plan.get("skillUpgrade") == skill_upgrade and plan.get("modelUpgrade") == models,
            "Durable migration plan differs from the independently verified stock/custom transformation")
    marker = read(stage / MARKER)
    require(marker.get("releaseVersion") == "0.8.5" and marker.get("schemaVersion") == 1
            and marker.get("customFieldsPreserved") is True and marker.get("customModelsPreserved") is True
            and marker.get("skillUpgrade") == skill_upgrade and marker.get("modelUpgrade") == models,
            "Incorrect fencing migration completion marker")
    require(report.get("migrationPlanSha256") == sha(stage / BACKUP / "plan.json")
            and report.get("migrationMarkerSha256") == sha(stage / MARKER)
            and report.get("assetManifestSha256") == sha(asset_manifest), "Stage plan/marker/asset manifest binding changed")
    protocol(jar)
    return {"passed": True, "version": "0.8.5", "networkProtocol": 11, "beforeSha256": before,
            "afterSha256": after, "changedFiles": sorted(changed), "addedFiles": sorted(added),
            "stagedFileCount": len(after), "previousFileCount": len(before), "stageReportSha256": sha(report_path),
            "assetManifestSha256": sha(asset_manifest), "assetUpgrade": assets, "modelUpgrade": models, "skillUpgrade": skill_upgrade,
            "assetFileCount": 148, "unchangedAssetFileCount": 146, "previousJarSha256": sha(previous_jar),
            "jarSha256": sha(jar), "configurationFilesToWrite": sorted(changed | added,
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
