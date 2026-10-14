#!/usr/bin/env python3
"""Read actual spear captures and compare conservative torso/head clearance proxies."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

PROJECT = Path(__file__).resolve().parents[1]
CLIPS = ("spear-ready-extremes", "spear-thrust-sweep-return")
BOXES = {"torso": ([-25, 0, -12.5], [25, 75, 12.5]), "head": ([-25, 0, -25], [25, 50, 25])}


def latest_passed(root):
    for report in sorted(root.glob("run-*/spear-solver-smoke.json"), key=lambda value: value.stat().st_mtime, reverse=True):
        if json.loads(report.read_text(encoding="utf-8-sig")).get("passed"):
            return report.parent
    raise ValueError(f"No completed passed client capture under {root}")


def signed_box_distance(points, minimum, maximum):
    offsets = np.maximum(np.asarray(minimum) - points, points - np.asarray(maximum))
    return np.linalg.norm(np.maximum(offsets, 0), axis=1) + np.minimum(0, np.max(offsets, axis=1))


def clearance(start, end, inverse, bounds, radius):
    start = inverse[:3, :3] @ start + inverse[:3, 3]
    end = inverse[:3, :3] @ end + inverse[:3, 3]
    steps = max(1, int(np.ceil(np.linalg.norm(end - start))))
    points = start + np.linspace(0, 1, steps + 1)[:, None] * (end - start)
    return float(np.min(signed_box_distance(points, *bounds)) - radius)


def location(run, clip, frame, profile, observation):
    image = (run / frame["file"]).resolve()
    if not image.is_relative_to(run.resolve()):
        raise ValueError("Source image path escapes capture run")
    return {"clip": clip["id"], "profile": profile, "frameIndex": frame["index"], "sourceImage": str(image),
            "sourceSeconds": frame["capturedAtSeconds"], "sampledTick": observation["sampledTick"],
            "fixtureTick": frame["observation"].get("fixtureTick"), "action": observation.get("renderAnimation", {}).get("action"),
            "phase": observation.get("renderAnimation", {}).get("phase"), "aim": observation.get("renderAnimation", {}).get("lastAim")}


def add_metric(metrics, name, value, where):
    metric = metrics.setdefault(name, {"samples": 0, "belowMinus0_01Cm": 0, "belowMinus5Cm": 0, "belowMinus10Cm": 0,
                                      "minimumClearanceCm": value, "maximumClearanceCm": value, "sum": 0, "worst": where})
    metric["samples"] += 1
    metric["sum"] += value
    metric["belowMinus0_01Cm"] += value < -.01
    metric["belowMinus5Cm"] += value < -5
    metric["belowMinus10Cm"] += value < -10
    if value < metric["minimumClearanceCm"]:
        metric["minimumClearanceCm"] = value
        metric["worst"] = where
    metric["maximumClearanceCm"] = max(metric["maximumClearanceCm"], value)


def analyze_run(run):
    capture_path = run / "render-capture.json"
    report = json.loads((run / "spear-solver-smoke.json").read_text(encoding="utf-8-sig"))
    capture = json.loads(capture_path.read_text(encoding="utf-8-sig"))
    if not report.get("passed") or capture.get("pendingWrites") != 0:
        raise ValueError(f"Capture is incomplete: {run}")
    result = {"sourceRun": str(run.resolve()), "jarSha256": report["modJarSha256"],
              "solverBytecodeSha256": report["loadedSolverBytecodeSha256"],
              "captureSha256": hashlib.sha256(capture_path.read_bytes()).hexdigest(), "clips": {}}
    for clip in capture["clips"]:
        if clip["id"] not in CLIPS:
            continue
        summary = result["clips"][clip["id"]] = {"frames": len(clip["frames"]), "sourceFps": clip["sourceFps"],
            "captureDurationSeconds": clip["captureDurationSeconds"], "profiles": {}}
        for profile in ("stock", "slim"):
            metrics = summary["profiles"][profile] = {}
            for frame in clip["frames"]:
                observation = frame["observation"] if profile == "stock" else frame["observation"]["slimActor"]
                where = location(run, clip, frame, profile, observation)
                rear, axis = np.asarray(observation["rightPalmCm"]), np.asarray(observation["rightBladeAxis"])
                shaft_start, shaft_end = rear - axis * 60, rear + axis * 178
                centerline_worst = {}
                for volume, bounds in BOXES.items():
                    bone = "body" if volume == "torso" else "head"
                    matrix = np.asarray(observation["rig"][bone], dtype=float).reshape((4, 4), order="F")
                    inverse = np.linalg.inv(matrix)
                    shaft = clearance(shaft_start, shaft_end, inverse, bounds, 2)
                    add_metric(metrics, "shaftTo" + volume, shaft, where)
                    add_metric(metrics, "shaftCenterlineTo" + volume, shaft + 2, where)
                    for side in ("right", "left"):
                        shoulder = np.asarray(observation[side + "ShoulderCm"])
                        elbow = np.asarray(observation[side + "ElbowCm"])
                        palm = np.asarray(observation[side + "PalmCm"])
                        upper = clearance(shoulder + (elbow - shoulder) * .32, elbow, inverse, bounds, 10)
                        lower = clearance(elbow, palm, inverse, bounds, 8.5)
                        add_metric(metrics, side + "UpperTo" + volume, upper, where)
                        add_metric(metrics, side + "LowerTo" + volume, lower, where)
                        add_metric(metrics, side + "UpperCenterlineTo" + volume, upper + 10, where)
                        add_metric(metrics, side + "LowerCenterlineTo" + volume, lower + 8.5, where)
                        for part, value in (("Upper", upper + 10), ("Lower", lower + 8.5)):
                            name = side + part + "CenterlineToBodyOrHead"
                            if name not in centerline_worst or value < centerline_worst[name][0]:
                                centerline_worst[name] = (value, {**where, "volume": volume})
                for name, (value, nearest) in centerline_worst.items():
                    add_metric(metrics, name, value, nearest)
            for metric in metrics.values():
                metric["meanClearanceCm"] = metric.pop("sum") / metric["samples"]
                metric["belowMinus0_01Fraction"] = metric["belowMinus0_01Cm"] / metric["samples"]
                metric["belowMinus5Fraction"] = metric["belowMinus5Cm"] / metric["samples"]
                metric["belowMinus10Fraction"] = metric["belowMinus10Cm"] / metric["samples"]
    if set(result["clips"]) != set(CLIPS):
        raise ValueError("Missing one of the two stationary comparison clips")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old", type=Path, help="Default latest passed 0.5.1 spear-solver run")
    parser.add_argument("--new", type=Path, help="Default latest passed 0.5.2 spear-blend run")
    parser.add_argument("--output", type=Path, default=PROJECT / "verification/spear-blend-20261012/real-collision-comparison.json")
    args = parser.parse_args()
    old = args.old or latest_passed(PROJECT / "verification/spear-solver-20261012/game")
    new = args.new or latest_passed(PROJECT / "verification/spear-blend-20261012/game")
    result = {"scope": "Read-only conservative collision proxies from actual captured matrices, first two stationary clips only",
        "geometry": {"units": "centimetres", "boxes": BOXES, "shaft": "rear -60..+178, radius2; also centerline without radius",
                     "arms": "upper radius10 excluding proximal .32 (12 cm); lower radius8.5; segment sample spacing <=1 cm"},
        "limitations": ["Conservative capsules and rigid stock boxes are contact candidates, not visible skin mesh intersection proof.",
                        "Counts are normalized by captured frames because source FPS/frame counts differ; source times and actual aims/phases may differ.",
                        "Identical clip scripts do not guarantee identical per-frame targets, phase timing, history or same-frame solver branches.",
                        "Only stationary aiming/attack clips are compared; the new moving script differs from 0.5.1 and is excluded.",
                        "Shaft rear+178 equals historical front+120 with the closed 58 cm grip; no 1.5 cm scoring margin is added."],
        "old": analyze_run(old), "new": analyze_run(new)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    for clip in CLIPS:
        print(clip)
        for profile in ("stock", "slim"):
            first, second = result["old"]["clips"][clip], result["new"]["clips"][clip]
            print(f"  {profile}: old {first['frames']} frames {first['sourceFps']:.2f}fps, new {second['frames']} frames {second['sourceFps']:.2f}fps")
            for metric in ("shaftTotorso", "shaftTohead", "shaftCenterlineTotorso", "shaftCenterlineTohead", "rightUpperTotorso", "leftUpperTotorso", "rightLowerTotorso", "leftLowerTotorso"):
                a, b = first["profiles"][profile][metric], second["profiles"][profile][metric]
                print(f"    {metric:27s} contact {a['belowMinus0_01Cm']}/{a['samples']} ({a['belowMinus0_01Fraction']:.1%}) -> {b['belowMinus0_01Cm']}/{b['samples']} ({b['belowMinus0_01Fraction']:.1%}); min {a['minimumClearanceCm']:.3f} -> {b['minimumClearanceCm']:.3f}cm; new clearance<-5cm {b['belowMinus5Cm']}")
    print(f"Evidence: {args.output.resolve()}")


if __name__ == "__main__":
    main()
