#!/usr/bin/env python3
"""Audit only this round's actual rendered limits/arms/sword-layer captures."""
import argparse
import importlib.util
import json
import hashlib
import math
from pathlib import Path
from PIL import Image, ImageDraw

SCRIPT = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location("spear_audit", SCRIPT.with_name("analyze-spear-capture.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def cross(a, b):
    return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]


def analyze(run):
    report = json.loads((run / "action-limits-smoke.json").read_text(encoding="utf-8"))
    capture = json.loads((run / "render-capture.json").read_text(encoding="utf-8"))
    violations, jumps, results, cards = [], [], [], []
    total = 0
    for clip in capture["clips"]:
        identity = clip["id"]
        spear = identity.startswith("spear-")
        frames = clip["frames"]
        total += len(frames)
        metrics = {"id": identity, "actualRenderedFrames": len(frames), "sourceFps": clip["sourceFps"],
                   "writtenFrames": clip["writtenFrames"], "distinctSamplesWithinGameTick": clip["distinctSamplesWithinGameTick"],
                   "maximumAdjacentJointStepDegrees": 0, "maximumGripSpacingErrorCm": 0,
                   "maximumGripAxisErrorCm": 0, "minimumElbowBendDegrees": 180,
                   "maximumElbowBendDegrees": 0, "maximumTorsoYawDegrees": 0, "maximumTorsoTiltDegrees": 0,
                   "maximumWristFlexionDegrees": 0, "maximumOldUpperArmDifferenceDegrees": 0}
        metrics["maximumWristTwistDegrees"] = 0
        metrics["maximumLocalCursorAimYawDifferenceDegrees"] = 0
        metrics["maximumNativeBodyFacingDeltaDegrees"] = 0
        metrics["minimumWorldTorsoForwardDotActorAim"] = 1
        previous = {}
        last_render, last_time = -1, -1.0
        if clip["sourceFps"] < 30 or clip["writtenFrames"] != len(frames):
            violations.append({"clip": identity, "condition": "actual complete rendered capture >=30 FPS"})
        for frame in frames:
            if frame["renderFrame"] <= last_render or frame["capturedAtSeconds"] <= last_time or not (run / frame["file"]).is_file():
                violations.append({"clip": identity, "frame": frame["index"], "condition": "strictly increasing real render timestamp/image"})
            last_render, last_time = frame["renderFrame"], frame["capturedAtSeconds"]
            stock = frame["observation"]
            for profile, value in ((stock.get("profile","stock"), stock), ("slim", stock.get("slimActor"))):
                if value is None:
                    continue
                context = {"clip": identity, "frame": frame["index"], "profile": profile}
                metrics["maximumTorsoTiltDegrees"] = max(metrics["maximumTorsoTiltDegrees"], value["torsoTiltDegrees"])
                if spear:
                    rear, front, axis = value["rightPalmCm"], value["leftPalmCm"], value["rightBladeAxis"]
                    grip = abs(audit.distance(rear, front)-58)
                    alignment = audit.length(cross([x-y for x, y in zip(front, rear)], axis))
                    metrics["maximumGripSpacingErrorCm"] = max(metrics["maximumGripSpacingErrorCm"], grip)
                    metrics["maximumGripAxisErrorCm"] = max(metrics["maximumGripAxisErrorCm"], alignment)
                    torso = value["rig"]["body"]
                    torso_yaw = abs(math.degrees(math.atan2(torso[8], torso[10])))
                    metrics["maximumTorsoYawDegrees"] = max(metrics["maximumTorsoYawDegrees"], torso_yaw)
                    pole_facing=torso[8]*axis[0]+torso[10]*axis[2]
                    if grip > .03 or alignment > .03 or pole_facing <= 0:
                        violations.append(dict(context, condition="closed grip/axis and torso facing the actual pole", grip=grip, axis=alignment, torsoYaw=torso_yaw))
                    if "actualAimYawDifferenceDegrees" in value:
                        error = value["actualAimYawDifferenceDegrees"]
                        native_delta = abs(audit.wrap(value["bodyYaw"]-value["actorYaw"]))
                        forward = pole_facing
                        metrics["maximumLocalCursorAimYawDifferenceDegrees"] = max(metrics["maximumLocalCursorAimYawDifferenceDegrees"], error)
                        metrics["maximumNativeBodyFacingDeltaDegrees"] = max(metrics["maximumNativeBodyFacingDeltaDegrees"], native_delta)
                        metrics["minimumWorldTorsoForwardDotActorAim"] = min(metrics["minimumWorldTorsoForwardDotActorAim"], forward)
                        if error > .02 or value["actualAimPitchDifferenceDegrees"] > .02 or forward <= 0:
                            violations.append(dict(context, condition="native local movement preserves visible cursor aim and forward torso", yawError=error, nativeBodyDelta=native_delta, forwardDot=forward))
                    for side in ("right", "left"):
                        q = value[side+"ForearmLocalQuaternion"]
                        bend = audit.wrap(math.degrees(2*math.atan2(q[0], q[3])))
                        flex = value[side+"WristFlexionDegrees"]
                        wrist = value[side+"HandLocalQuaternion"]
                        twist = abs(audit.wrap(math.degrees(2*math.atan2(wrist[1], wrist[3]))))
                        metrics["maximumWristTwistDegrees"] = max(metrics["maximumWristTwistDegrees"], twist)
                        metrics["minimumElbowBendDegrees"] = min(metrics["minimumElbowBendDegrees"], bend)
                        metrics["maximumElbowBendDegrees"] = max(metrics["maximumElbowBendDegrees"], bend)
                        metrics["maximumWristFlexionDegrees"] = max(metrics["maximumWristFlexionDegrees"], flex)
                        if bend < 9.97 or bend > 135.03 or flex > .1 or twist > 150.03 or value[side+"ForearmOffXHingeDegrees"] > .1:
                            violations.append(dict(context, condition="positive anatomical elbow and straight wrist", side=side, bend=bend, flex=flex))
                else:
                    if value["renderAnimation"]["model"] != "legacy_rapier.fsvoxel":
                        violations.append(dict(context, condition="original sword model"))
                    fidelity = value.get("oldSwordSourceArmDifferenceDegrees", {})
                    for difference in fidelity.values():
                        metrics["maximumOldUpperArmDifferenceDegrees"] = max(metrics["maximumOldUpperArmDifferenceDegrees"], difference)
                    if fidelity and max(fidelity.values()) > .1:
                        violations.append(dict(context, condition="original upper local arm rotations", difference=fidelity))
                    if value.get("swordSourceStable", False) and value["renderAnimation"]["phase"] == "READY" and value["torsoTiltDegrees"] > 6:
                        violations.append(dict(context, condition="mild moving sword READY torso", tilt=value["torsoTiltDegrees"]))
                before = previous.get(profile)
                if before and spear:
                    old_frame, old = before
                    largest, bone = max((audit.quaternion_angle(audit.quaternion(old, name), audit.quaternion(value, name)), name) for name in audit.JOINTS)
                    metrics["maximumAdjacentJointStepDegrees"] = max(metrics["maximumAdjacentJointStepDegrees"], largest)
                    if largest > 89.01:
                        jumps.append(dict(context, condition="actual adjacent joint step >89 degrees", bone=bone, step=largest, before=old_frame["index"]))
                previous[profile] = (frame, value)
        results.append(metrics)
        selected = [frames[int((len(frames)-1)*fraction)] for fraction in (.2, .5, .8)]
        cards.extend((identity, frame) for frame in selected)
    passed = (report["passed"] and not violations and not jumps and report["movementValidation"]["passed"]
              and report["swordMovementValidation"]["passed"] and report["swordSourceComparisons"] > 100
              and report["maximumCursorBodyYawDegrees"] <= .02 and report["cursorChecks"] >= 200 and report["nearEntityChecks"] >= 20)
    summary = {"passed": passed, "scope": report["scope"], "run": str(run), "modJarSha256": report["modJarSha256"],
               "jarSha256": report["modJarSha256"],
               "sourceCaptureSha256": hashlib.sha256((run / "render-capture.json").read_bytes()).hexdigest(),
               "sourceRuntimeReportSha256": hashlib.sha256((run / "action-limits-smoke.json").read_bytes()).hexdigest(),
               "monitorAdapter": report["monitorAdapter"], "aimField": report["aimField"], "actualRenderedFrames": total, "clips": results,
               "violations": violations, "jointJumpAlerts": jumps, "serverStamina": report["serverStamina"],
               "cursorChecks": report["cursorChecks"], "nearEntityChecks": report["nearEntityChecks"], "nearTargetSemantics": report["nearTargetSemantics"], "nearEntityMeasurements": report["nearEntityMeasurements"], "maximumCursorBodyYawDegrees": report["maximumCursorBodyYawDegrees"],
               "maximumCursorAimYawDegrees": report["maximumCursorAimYawDegrees"], "maximumCursorAimPitchDegrees": report["maximumCursorAimPitchDegrees"],
               "maximumSwordReadyTiltDegrees": report["maximumSwordReadyTiltDegrees"], "swordSourceComparisons": report["swordSourceComparisons"],
               "maximumSwordSourceArmDifferenceDegrees": report["maximumSwordSourceArmDifferenceDegrees"]}
    summary["numericChecks"] = {"actualRenderedFrames": total, "geometryViolationCount": len(violations),
                                "jointJumpAlertCount": len(jumps), "cursorInputChecks": report["cursorChecks"],
                                "originalUpperSourceComparisons": report["swordSourceComparisons"],
                                "serverStaminaCases": len(report["serverStamina"]), "actualNearEntityPickingChecks": report["nearEntityChecks"]}
    output = run / "focused-analysis"
    output.mkdir(exist_ok=True)
    (output / "focused-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    sheet = Image.new("RGB", (1500, len(results)*328), "#11151b")
    draw = ImageDraw.Draw(sheet)
    for i, (identity, frame) in enumerate(cards):
        with Image.open(run / frame["file"]) as source:
            source.thumbnail((490, 288))
            x, y = i%3*500, i//3*328
            sheet.paste(source, (x+(500-source.width)//2, y+30))
            draw.text((x+8, y+8), f"{identity} frame {frame['index']}", fill="white")
    sheet.save(output / "actual-render-contact-sheet.jpg", quality=92)
    print(json.dumps({"passed": passed, "actualRenderedFrames": total, "violations": len(violations), "jumpAlerts": len(jumps),
                      "summary": str(output / "focused-summary.json"), "contactSheet": str(output / "actual-render-contact-sheet.jpg")}, ensure_ascii=False))
    return passed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    raise SystemExit(0 if analyze(args.run.resolve()) else 2)
