#!/usr/bin/env python3
"""Focused, read-only spear frame audit and before/after screenshot contact sheet."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFont

PROJECT = Path(__file__).resolve().parents[1]
JOINTS = {
    "body": "bodyWorldQuaternion",
    "head": None,
    "right_arm": "rightUpperArmWorldQuaternion",
    "right_forearm": "rightForearmWorldQuaternion",
    "right_hand": "rightHandWorldQuaternion",
    "left_arm": "leftUpperArmWorldQuaternion",
    "left_forearm": "leftForearmWorldQuaternion",
    "left_hand": "leftHandWorldQuaternion",
}


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def length(v):
    return math.sqrt(dot(v, v))


def distance(a, b):
    return length([x - y for x, y in zip(a, b)])


def wrap(value):
    return (value + 180) % 360 - 180


def quaternion_angle(a, b):
    cosine = abs(dot(a, b)) / (length(a) * length(b))
    return math.degrees(2 * math.acos(max(-1, min(1, cosine))))


def matrix_quaternion(m):
    # The actual JOML matrices are column major. Remove any numerical axis scale.
    axes = [[m[i + k] for k in range(3)] for i in (0, 4, 8)]
    axes = [[x / length(axis) for x in axis] for axis in axes]
    r00, r10, r20 = axes[0]
    r01, r11, r21 = axes[1]
    r02, r12, r22 = axes[2]
    trace = r00 + r11 + r22
    if trace > 0:
        s = math.sqrt(trace + 1) * 2
        q = [(r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s, s / 4]
    elif r00 > r11 and r00 > r22:
        s = math.sqrt(1 + r00 - r11 - r22) * 2
        q = [s / 4, (r01 + r10) / s, (r02 + r20) / s, (r21 - r12) / s]
    elif r11 > r22:
        s = math.sqrt(1 + r11 - r00 - r22) * 2
        q = [(r01 + r10) / s, s / 4, (r12 + r21) / s, (r02 - r20) / s]
    else:
        s = math.sqrt(1 + r22 - r00 - r11) * 2
        q = [(r02 + r20) / s, (r12 + r21) / s, s / 4, (r10 - r01) / s]
    return [x / length(q) for x in q]


def quaternion(observation, joint):
    field = JOINTS[joint]
    return observation[field] if field else matrix_quaternion(observation["rig"][joint])


def aim_step(before, after):
    first = before.get("renderAnimation", {}).get("lastAim")
    second = after.get("renderAnimation", {}).get("lastAim")
    if first is None or second is None:
        return None
    return math.hypot(wrap(second["yaw"] - first["yaw"]), second["pitch"] - first["pitch"])


def profile_observation(frame, profile):
    result = frame["observation"]
    return result if profile == "stock" else result["slimActor"]


def state(frame, profile):
    observation = profile_observation(frame, profile)
    return {
        "index": frame["index"], "file": frame["file"],
        "capturedAtSeconds": frame["capturedAtSeconds"],
        "gameTimeTicks": frame["gameTimeTicks"],
        "sampledTick": observation.get("sampledTick"),
        "fixtureTick": frame["observation"].get("fixtureTick"),
        "bodyYaw": observation.get("bodyYaw"),
        "aimDeltaYaw": observation.get("aimDeltaYaw"),
        "walkAnimationSpeed": observation.get("walkAnimationSpeed"),
        "actorTickCount": observation.get("actorTickCount"),
        "actorPositionMeters": observation.get("actorPositionMeters"),
        "movementSegment": observation.get("movementSegment"),
        "movementScriptTick": observation.get("movementScriptTick"),
        "rootCm": observation.get("rootCm"),
        "rightKnee": observation.get("rightKnee"),
        "leftKnee": observation.get("leftKnee"),
        "finalLowerBody": observation.get("finalLowerBody"),
        "renderAnimation": observation.get("renderAnimation"),
        "rightShoulderCm": observation.get("rightShoulderCm"),
        "leftShoulderCm": observation.get("leftShoulderCm"),
        "rightElbowCm": observation.get("rightElbowCm"),
        "leftElbowCm": observation.get("leftElbowCm"),
        "rightPalmCm": observation.get("rightPalmCm"),
        "leftPalmCm": observation.get("leftPalmCm"),
        "rightWristTwistDegrees": observation.get("rightHandLocalTwistYDegrees"),
        "leftWristTwistDegrees": observation.get("leftHandLocalTwistYDegrees"),
        "rig": observation.get("rig"),
    }


def latest_passed(root):
    candidates = sorted(root.glob("run-*/spear-solver-smoke.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    for path in candidates:
        if json.loads(path.read_text(encoding="utf-8-sig")).get("passed"):
            return path.parent
    raise ValueError(f"No completed passed spear capture under {root}")


def metric(summary, name, value, location):
    entry = summary.setdefault(name, {"minimum": value, "maximum": value, "samples": 0})
    entry["samples"] += 1
    if value < entry["minimum"]:
        entry["minimum"] = value
        entry["minimumWhere"] = location
    if value >= entry["maximum"]:
        entry["maximum"] = value
        entry["maximumWhere"] = location


def contact_sheet(run, pairs, destination):
    if not pairs:
        return None
    crop = (310, 145, 520, 310)
    width, height, header = 420, 330, 64
    sheet = Image.new("RGB", (width * 2, (height + header) * len(pairs)), "#141923")
    draw = ImageDraw.Draw(sheet)
    font_path = Path("C:/Windows/Fonts/arial.ttf")
    font = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
    for row, pair in enumerate(pairs):
        top = row * (height + header)
        text = f"{pair['profile']} {pair['joint']} {pair['degrees']:.2f} deg | aim {pair['aimStepDegrees']:.2f} deg"
        draw.text((8, top + 5), text, fill="white", font=font)
        draw.text((8, top + 27), f"{pair['clip']} | frames {pair['before']['index']} -> {pair['after']['index']}", fill="#ced8e9", font=font)
        for column, phase in enumerate(("before", "after")):
            path = (run / pair[phase]["file"]).resolve()
            if not path.is_relative_to(run.resolve()):
                raise ValueError("Capture image path escapes the selected run")
            with Image.open(path) as image:
                tile = image.convert("RGB").crop(crop).resize((width, height), Image.Resampling.NEAREST)
            sheet.paste(tile, (column * width, top + header))
            draw.text((column * width + 8, top + 47), f"{phase}: {pair[phase]['index']}, tick {pair[phase]['fixtureTick']}", fill="#f9d378", font=font)
    sheet.save(destination)
    return str(destination)


def span(values):
    return max(values) - min(values) if values else 0


def longest_freeze(frames, profile, equal):
    """Measure unchanged poses against source capture time, even if sampledTick freezes."""
    maximum, beginning = 0, None
    for before, after in zip(frames, frames[1:]):
        first, second = profile_observation(before, profile), profile_observation(after, profile)
        if equal(first, second):
            beginning = before["capturedAtSeconds"] if beginning is None else beginning
            maximum = max(maximum, after["capturedAtSeconds"] - beginning)
        else:
            beginning = None
    return maximum


def stable_support(observation):
    lower = observation.get("finalLowerBody")
    if not observation.get("lowerBodyApplicable") or lower is None:
        return False
    grounded = False
    for side in ("right", "left"):
        leg = lower[side]
        contact = leg["support"] > .5
        grounded |= contact
        for point in leg["armorSoleCorners"]:
            if not all(math.isfinite(value) for value in point) or point[1] > 24.03 or (contact and abs(point[1] - 24) > .03):
                return False
    right, left = lower["right"]["hip"], lower["left"]["hip"]
    yaw=lower.get("pelvisYawRadians",0)
    return grounded and abs((left[0]-right[0])-3.8*math.cos(yaw))<.03 and abs((left[2]-right[2])+3.8*math.sin(yaw))<.03 and abs(left[1]-right[1])<.03


def audit_movement(capture):
    clips = [clip for clip in capture["clips"] if clip["id"] == "spear-moving-extremes"]
    required = ("walk-start", "forward-walk", "forward-sprint", "walk-stop", "strafe-left", "strafe-right", "backward-walk", "moving-thrust", "moving-sweep")
    constant = ("forward-walk", "forward-sprint", "strafe-left", "strafe-right", "backward-walk")
    summary = {"scope": "Actual entity displacement, official walk speed, per-render gait/knees/support/root/upper pose at fixed aim", "profiles": {}, "violations": []}
    failures = summary["violations"]
    if len(clips) != 1:
        failures.append({"condition": "exactly one moving clip"})
        summary["passed"] = False
        return summary
    for profile in ("stock", "slim"):
        groups = {}
        for frame in clips[0]["frames"]:
            observation = profile_observation(frame, profile)
            groups.setdefault(observation.get("movementSegment", "missing"), []).append(frame)
        actor = summary["profiles"][profile] = {}
        for segment in required:
            frames = groups.get(segment, [])
            if len(frames) < 5:
                failures.append({"profile": profile, "segment": segment, "condition": "segment has at least five rendered samples"})
                continue
            samples = [profile_observation(frame, profile) for frame in frames]
            source_duration = frames[-1]["capturedAtSeconds"] - frames[0]["capturedAtSeconds"]
            sampled = [sample["sampledTick"] for sample in samples]
            age = [sample.get("renderStateAgeInTicks", 0) for sample in samples]
            moving = segment not in ("walk-start", "walk-stop")
            travel, seen_ticks, actual_velocities = 0, set(), []
            maximum_velocity_error, maximum_motion_velocity_error = 0, 0
            for sample in samples:
                tick = sample["actorTickCount"]
                velocity = sample["deltaMovementMetersPerTick"]
                fixture = sample["fixtureVelocityMetersPerTick"]
                actual_velocities.append(velocity)
                maximum_velocity_error = max(maximum_velocity_error, math.hypot(velocity[0] - fixture[0], velocity[2] - fixture[1]))
                motion = sample.get("renderAnimation", {}).get("motion", {})
                if "velocityX" in motion and "velocityZ" in motion:
                    maximum_motion_velocity_error = max(maximum_motion_velocity_error, math.hypot(motion["velocityX"] - velocity[0], motion["velocityZ"] - velocity[2]))
                if tick not in seen_ticks:
                    travel += math.hypot(velocity[0], velocity[2])
                    seen_ticks.add(tick)
            gait = [sample["finalLowerBody"]["phaseRadians"] for sample in samples]
            gait_steps = [math.atan2(math.sin(b - a), math.cos(b - a)) for a, b in zip(gait, gait[1:])]
            supports = [sample["finalLowerBody"]["right"]["support"] for sample in samples]
            upper_joints = ("body", "right_arm", "right_forearm", "right_hand", "left_arm", "left_forearm", "left_hand")
            upper_ranges = {joint: max(quaternion_angle(quaternion(samples[0], joint), quaternion(sample, joint)) for sample in samples) for joint in upper_joints}
            result = actor[segment] = {
                "frames": len(frames), "sourceDurationSeconds": source_duration, "actualTravelMeters": travel,
                "actorTickCountSpan": span([sample["actorTickCount"] for sample in samples]),
                "sampledTickSpan": span(sampled), "renderStateAgeSpan": span(age),
                "freshSamplePairs": sum(b > a + .00001 for a, b in zip(sampled, sampled[1:])),
                "minimumWalkAnimationSpeed": min(sample["walkAnimationSpeed"] for sample in samples),
                "maximumWalkAnimationSpeed": max(sample["walkAnimationSpeed"] for sample in samples),
                "endingWalkAnimationSpeed": samples[-1]["walkAnimationSpeed"],
                "gaitTravelRadians": sum(abs(value) for value in gait_steps),
                "minimumGaitStepRadians": min(gait_steps, default=0),
                "supportSwitches": sum(a != b for a, b in zip(supports, supports[1:])),
                "rightKneeRangeRadians": span([sample["rightKnee"] for sample in samples]),
                "leftKneeRangeRadians": span([sample["leftKnee"] for sample in samples]),
                "rootCoordinateRangesCm": [span([sample["rootCm"][axis] for sample in samples]) for axis in range(3)],
                "upperJointRangesDegrees": upper_ranges,
                "rightPalmPathCm": sum(distance(a["rightPalmCm"], b["rightPalmCm"]) for a, b in zip(samples, samples[1:])),
                "leftPalmPathCm": sum(distance(a["leftPalmCm"], b["leftPalmCm"]) for a, b in zip(samples, samples[1:])),
                "maximumFixtureVelocityErrorMetersPerTick": maximum_velocity_error,
                "maximumSampledMotionVelocityErrorMetersPerTick": maximum_motion_velocity_error,
                "unsupportedFrames": sum(not stable_support(sample) for sample in samples),
                "maximumPoseClockFreezeSeconds": longest_freeze(frames, profile, lambda a, b: abs(a["sampledTick"] - b["sampledTick"]) <= .00001),
                "maximumGaitFreezeSeconds": longest_freeze(frames, profile, lambda a, b: abs(a["finalLowerBody"]["phaseRadians"] - b["finalLowerBody"]["phaseRadians"]) <= .000001),
                "maximumUpperPoseFreezeSeconds": longest_freeze(frames, profile, lambda a, b: distance(a["rootCm"], b["rootCm"]) < .0001 and all(quaternion_angle(quaternion(a, joint), quaternion(b, joint)) < .001 for joint in upper_joints)),
            }
            checks = {
                "per-render pose clock advances": result["freshSamplePairs"] >= (len(frames) - 1) * .8 and result["maximumPoseClockFreezeSeconds"] < .15,
                "official state extraction stays fresh": result["renderStateAgeSpan"] > .5,
                "both feet share grounded support": result["unsupportedFrames"] == 0,
                "fixture velocity matches actual applied movement": maximum_velocity_error < .000001,
            }
            if moving:
                checks.update({
                    "actual displacement drives native walk speed": travel > .5 and result["maximumWalkAnimationSpeed"] > .25,
                    "gait phase advances": result["gaitTravelRadians"] > .5,
                    "support foot swaps": result["supportSwitches"] > 0,
                    "both knees animate": min(result["rightKneeRangeRadians"], result["leftKneeRangeRadians"]) > .03,
                    "root remains live": result["rootCoordinateRangesCm"][1] > .03,
                })
            else:
                checks["walk speed settles after stop"] = result["endingWalkAnimationSpeed"] < .025
            if segment in constant:
                checks.update({
                    "ready movement keeps fixed aim": all(sample.get("constantAimMovement") and sample["renderAnimation"]["phase"] == "READY" for sample in samples),
                    "chest stride survives spear constraints": upper_ranges["body"] > .1,
                    "arms follow the live chest": min(upper_ranges[joint] for joint in ("right_arm", "left_arm")) > .05,
                    "palms move at fixed aim": min(result["rightPalmPathCm"], result["leftPalmPathCm"]) > .1,
                    "gait has no sustained freeze": result["maximumGaitFreezeSeconds"] < .18,
                    "upper pose has no sustained freeze": result["maximumUpperPoseFreezeSeconds"] < .25,
                })
            result["checks"] = checks
            for condition, passed in checks.items():
                if not passed:
                    failures.append({"profile": profile, "segment": segment, "condition": condition})
        # Verify the tail of the stable directional segments rather than their acceleration blends.
        for segment, expected in (("forward-walk", .4), ("forward-sprint", .68), ("strafe-left", .4), ("strafe-right", .4), ("backward-walk", .4)):
            if segment in actor and abs(actor[segment]["endingWalkAnimationSpeed"] - expected) > .025:
                failures.append({"profile": profile, "segment": segment, "condition": "vanilla walk speed converges to min(4 * metres-per-tick, 1)", "expected": expected, "actual": actor[segment]["endingWalkAnimationSpeed"]})
    summary["passed"] = not failures
    return summary


def analyze(run, output):
    report = json.loads((run / "spear-solver-smoke.json").read_text(encoding="utf-8-sig"))
    capture = json.loads((run / "render-capture.json").read_text(encoding="utf-8-sig"))
    metrics, peaks, jumps, violations, clip_summaries = {}, {}, [], [], []
    movement = audit_movement(capture)
    frames_total = 0
    for clip in capture["clips"]:
        frames = clip["frames"]
        frames_total += len(frames)
        clip_summaries.append({key: clip[key] for key in ("id", "capturedFrames", "writtenFrames", "captureDurationSeconds", "sourceFps", "distinctSamplesWithinGameTick")})
        for index, frame in enumerate(frames):
            for profile in ("stock", "slim"):
                observation = profile_observation(frame, profile)
                location = {"clip": clip["id"], "profile": profile, "index": frame["index"], "file": frame["file"], "fixtureTick": frame["observation"].get("fixtureTick")}
                rear, front = observation["rightPalmCm"], observation["leftPalmCm"]
                shaft = observation["rightBladeAxis"]
                separation = [x - y for x, y in zip(front, rear)]
                cross = [separation[1] * shaft[2] - separation[2] * shaft[1], separation[2] * shaft[0] - separation[0] * shaft[2], separation[0] * shaft[1] - separation[1] * shaft[0]]
                values = {"gripSpacingErrorCm": abs(length(separation) - 58), "gripAxisErrorCm": length(cross)}
                for side in ("right", "left"):
                    shoulder, elbow = observation[side + "ShoulderCm"], observation[side + "ElbowCm"]
                    wrist = observation["rig"][side + "_hand"][12:15]
                    palm = observation[side + "PalmCm"]
                    q = observation[side + "ForearmLocalQuaternion"]
                    values[side + "UpperLengthErrorCm"] = abs(distance(shoulder, elbow) - 37.5)
                    values[side + "ForearmLengthErrorCm"] = abs(distance(elbow, wrist) - 25)
                    values[side + "PalmLinkLengthErrorCm"] = abs(distance(elbow, palm) - 31.25)
                    values[side + "WristPronationAbsoluteDegrees"] = abs(observation[side + "HandLocalTwistYDegrees"])
                    values[side + "ElbowSignedDegrees"] = wrap(math.degrees(2 * math.atan2(q[0], q[3])))
                    values[side + "WristFlexionDegrees"] = observation[side + "WristFlexionDegrees"]
                for name, value in values.items():
                    if not math.isfinite(value):
                        violations.append({**location, "metric": name, "value": str(value), "condition": "finite"})
                        continue
                    metric(metrics.setdefault(profile, {}), name, value, location)
                    failed = (name.endswith("ErrorCm") and value > .03) or (name.endswith("PronationAbsoluteDegrees") and value > 150.03) or (name.endswith("ElbowSignedDegrees") and not -.03 <= value <= 135.03) or (name.endswith("WristFlexionDegrees") and value > .1)
                    if failed:
                        violations.append({**location, "metric": name, "value": value})
                if index == 0:
                    continue
                previous = frames[index - 1]
                before = profile_observation(previous, profile)
                aim = aim_step(before, observation)
                for joint in JOINTS:
                    degrees = quaternion_angle(quaternion(before, joint), quaternion(observation, joint))
                    key = profile + ":" + joint
                    pair = {"clip": clip["id"], "profile": profile, "joint": joint, "degrees": degrees, "aimStepDegrees": aim,
                            "sourceFrameGapSeconds": frame["capturedAtSeconds"] - previous["capturedAtSeconds"],
                            "sampleTickDelta": observation.get("sampledTick", 0) - before.get("sampledTick", 0),
                            "before": state(previous, profile), "after": state(frame, profile)}
                    groups = peaks.setdefault(key, {"overall": [], "smallAim": []})
                    groups["overall"].append(pair)
                    groups["overall"] = sorted(groups["overall"], key=lambda p: p["degrees"], reverse=True)[:3]
                    if aim is not None and aim <= 2.1:
                        groups["smallAim"].append(pair)
                        groups["smallAim"] = sorted(groups["smallAim"], key=lambda p: p["degrees"], reverse=True)[:3]
                    if degrees >= 90 or (aim is not None and aim <= 2.1 and degrees > 35):
                        pair["reason"] = "overall joint jump >=90 degrees" if degrees >= 90 else "joint jump >35 degrees while aim step <=2.1 degrees"
                        jumps.append(pair)
    output.mkdir(parents=True, exist_ok=True)
    top_pairs = sorted([p for groups in peaks.values() for kind in ("overall", "smallAim") for p in groups[kind]], key=lambda p: p["degrees"], reverse=True)
    sheet_pairs, seen = [], set()
    for pair in sorted(jumps, key=lambda p: p["degrees"], reverse=True) + top_pairs:
        key = (pair["clip"], pair["profile"], pair["after"]["index"])
        if key in seen:
            continue
        seen.add(key)
        sheet_pairs.append(pair)
        if len(sheet_pairs) == 8:
            break
    sheet = contact_sheet(run, sheet_pairs, output / "worst-before-after-contact-sheet.png")
    summary = {"scope": "Spear actual framebuffer frame pairs only; original images and capture stay unchanged", "sourceRun": str(run.resolve()),
               "sourceCaptureSha256": hashlib.sha256((run / "render-capture.json").read_bytes()).hexdigest(),
               "sourceJarSha256": report.get("modJarSha256"), "loadedSolverBytecodeSha256": report.get("loadedSolverBytecodeSha256"),
               "liveSolverLimits": report.get("liveSolverLimits"), "capturedFrames": frames_total, "actorSamples": frames_total * 2,
               "clips": clip_summaries, "metrics": metrics, "passedFocusedGeometry": not violations,
               "requiresJointJumpReview": bool(jumps), "geometryViolations": violations,
               "jointPeaks": {key: {kind: {k: p[k] for k in ("clip", "degrees", "aimStepDegrees", "sourceFrameGapSeconds", "sampleTickDelta")} | {"beforeIndex": p["before"]["index"], "afterIndex": p["after"]["index"]} for kind, pairs in groups.items() if (p := pairs[0] if pairs else None)} for key, groups in peaks.items()},
               "jumpAlerts": [{k: p[k] for k in ("clip", "profile", "joint", "degrees", "aimStepDegrees", "reason")} | {"beforeIndex": p["before"]["index"], "afterIndex": p["after"]["index"]} for p in sorted(jumps, key=lambda p: p["degrees"], reverse=True)], "movementValidation": movement, "contactSheet": sheet}
    (output / "focused-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (output / "top-jump-pairs.json").write_text(json.dumps({"sourceRun": str(run.resolve()), "jointTopPairs": peaks, "alertPairs": jumps, "contactSheetPairs": sheet_pairs}, indent=2), encoding="utf-8")
    print(f"Spear focused capture: {frames_total} actual frames, {len(violations)} geometry violations, {len(jumps)} jump alerts")
    print(f"Movement validation: {'passed' if movement['passed'] else 'FAILED'}, {len(movement['violations'])} violations")
    for violation in movement["violations"]:
        print(f"  {violation.get('profile', '')} {violation.get('segment', '')}: {violation['condition']}")
    for key, groups in peaks.items():
        overall, small = groups["overall"][0], groups["smallAim"][0] if groups["smallAim"] else None
        print(f"  {key:21} overall={overall['degrees']:7.3f} {overall['clip']} {overall['before']['index']}->{overall['after']['index']} | smallAim={small['degrees'] if small else 0:7.3f}")
    print(f"Summary: {output / 'focused-summary.json'}")
    print(f"Contact sheet: {sheet}")
    return not violations and not jumps and movement["passed"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=PROJECT / "verification/spear-blend-20261012/game")
    parser.add_argument("--run", type=Path, help="Explicit completed runtime capture directory; default latest passed run")
    parser.add_argument("--output", type=Path, help="Default selected run/focused-analysis")
    arguments = parser.parse_args()
    run = arguments.run or latest_passed(arguments.root)
    passed = analyze(run, arguments.output or run / "focused-analysis")
    return 0 if passed else 2


if __name__ == "__main__":
    sys.exit(main())
