#!/usr/bin/env python3
"""Audit untouched native rapier PNGs, final skin geometry and first-person playback.

Usage: python tools/review-rapier-flow.py verification/rapier-flow-20261024/game
The capture remains unchanged; submittedAvatar is read as the actual TP submission.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import importlib.util
import json
import math
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("native_review", Path(__file__).with_name("review-render-motion.py"))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
PHRASES = {"direct-thrust", "rising-cut", "diagonal-cut", "return-cut", "lunge-thrust"}
BRANCHES = {"charged-thrust", "riposte", "reprise"}


def avatar_of(observation):
    if "submittedAvatar" not in observation:
        return None
    avatar = dict(observation["submittedAvatar"])
    avatar["rig"] = observation["rig"]
    avatar["reviewBeat"] = observation["scenario"]
    return avatar


def audit_rapier_pose(audit, avatar, where):
    """Measure the actual legacy locator; its wrist uses a cone, not an X-only hinge."""
    native.audit_lower_body(audit, avatar, where)
    rig = avatar["rig"]
    anim = avatar.get("renderAnimation", {})
    authored = bool({"rapier_fencing:0100", "rapier_guard:0100", "rapier_charge:0100"} & set(anim.get("clipTags", [])))
    settled = anim.get("transitionElapsedTicks", 0) >= anim.get("transitionTicks", 0) - .001
    for name, values in rig.items():
        audit.require(native.finite_vector(values, 6 if name.startswith("native") else 16),
                      "finiteSubmittedRigMatrix", **where, bone=name)
    for side in ("right", "left"):
        hand, forearm = rig[side + "_hand"], rig[side + "_forearm"]
        wrist = math.degrees(math.acos(max(-1, min(1, native.dot(native.axis(hand, [0, -1, 0]), native.axis(forearm, [0, -1, 0]))))))
        audit.peak("actualWristFlexionDegrees", wrist, **where, side=side)
        if authored and settled:
            audit.require(wrist <= 34 + .05, "actualWristWithin34DegreeCone", **where, side=side, actual=wrist)
        for joint in ("UpperArmWorldQuaternion", "ForearmWorldQuaternion", "HandWorldQuaternion", "ForearmLocalQuaternion", "HandLocalQuaternion"):
            values = avatar.get(side + joint)
            audit.require(native.finite_vector(values, 4) and abs(native.dot(values, values) - 1) < .005,
                          "actualNormalizedArmQuaternion", **where, joint=side + joint)
    # The actual production chain is legacyLocator(right_item)*legacyRapierItem;
    # source sprite direction is +X+Y. The fixed 41.987212496-degree Z mount
    # and -90-degree X turn give the following local axis after FS conversion.
    angle = math.radians(41.987212496 + 45)
    axis = native.axis(rig["right_item"], [-math.cos(angle), 0, math.sin(angle)])
    captured = avatar.get("rightActualOfficialBladeAxis")
    audit.require(native.finite_vector(captured, 3) and native.dot(axis, native.unit(captured)) > .99999,
                  "actualLegacyOfficialBladeAxis", **where)
    locator_error = math.dist(rig["right_item"][12:15], rig["right_hand"][12:15])
    audit.require(locator_error < .1, "actualOfficialLocatorAttachedToHand", **where, errorCm=locator_error)
    if authored and settled:
        wrist = math.degrees(math.acos(max(-1, min(1, native.dot(axis, native.axis(rig["right_forearm"], [0, -1, 0]))))))
        audit.require(wrist <= 34 + .05, "actualOfficialBladeWithinForearm34DegreeCone", **where, actual=wrist)
        audit.require(native.dot(axis, native.axis(rig["right_hand"], [0, -1, 0])) > .99999,
                      "rigidOfficialMountFollowsAnatomicalHand", **where)


def audit_phrase(audit, phrase, where):
    audit.require(phrase in PHRASES | BRANCHES, "currentReauthoredPhrase", **where, phrase=phrase)
    return phrase in PHRASES


def audit_first_person_pair(audit, before, after, where):
    # The forced sheathe/draw fixture legitimately returns to vanilla hands.
    # Require new custom submissions for every frame while both samples are drawn.
    if before.get("firstPersonDrawn", before.get("drawn")) and after.get("firstPersonDrawn", after.get("drawn")):
        audit.require(after["firstPersonNativeSamples"] > before["firstPersonNativeSamples"],
                      "firstPersonRendererContinuesEveryDrawnFrame", **where)
        audit.require(after["firstPersonOfficialItemSubmissions"] > before["firstPersonOfficialItemSubmissions"],
                      "firstPersonItemContinuesEveryDrawnFrame", **where)


def review(root, jar):
    capture_path = root / "render-capture.json"
    game_path = root / "rapier-fencing-review.json"
    capture, game = native.read_json(capture_path), native.read_json(game_path)
    audit = native.Audit()
    audit.require(game.get("passed") is True, "realGameSmokePassed")
    audit.require(game.get("monitorAdapter") == r"\\.\DISPLAY5", "actualWindowOnDisplay5")
    sha = native.digest(jar)
    audit.require(game.get("modJarSha256") == sha, "actualTestedJarIdentity", actual=sha)
    audit.require(capture.get("captureEvent") == "RenderFrameEvent.Post" and capture.get("pendingWrites") == 0,
                  "completedNativeRenderCapture")
    clips = capture.get("clips", [])
    audit.require(len(clips) == 1 and clips[0].get("id") == "rapier-fencing", "expectedNativeRapierClip")
    frames = clips[0]["frames"]
    duration = frames[-1]["capturedAtSeconds"] - frames[0]["capturedAtSeconds"]
    fps = (len(frames) - 1) / duration
    audit.require(fps >= 30, "actualTimestampSamplingAtLeast30Hz", fps=fps)
    hashes, dimensions, phrases, arm_lengths = set(), set(), set(), defaultdict(list)
    fp_actions, scenarios = defaultdict(set), Counter()
    previous = None
    tp_frames = fp_frames = subtick_pairs = 0
    for index, frame in enumerate(frames):
        where = {"frame": index, "file": frame["file"]}
        obs = frame["observation"]
        scenario = obs.get("scenario", "")
        scenarios[scenario] += 1
        path = (root / frame["file"]).resolve()
        audit.require(path.is_relative_to(root), "pngStaysWithinEvidence", **where)
        size, pixel_hash = native.png_pixels(path)
        dimensions.add(size)
        hashes.add(pixel_hash)
        audit.require(frame.get("index") == index, "nativeFrameIndexSequence", **where)
        if index:
            prior = frames[index - 1]
            gap = frame["capturedAtSeconds"] - prior["capturedAtSeconds"]
            audit.require(0 < gap <= .25 and frame["renderFrame"] > prior["renderFrame"],
                          "actualMonotoneFrameTimestamps", **where, gapSeconds=gap)
        avatar = avatar_of(obs)
        if avatar is None:
            fp_frames += 1
            audit.require(obs.get("cameraType") == "FIRST_PERSON", "actualFirstPersonCamera", **where)
            audit.require(obs.get("firstPersonNativeSamples", 0) > 0 and obs.get("firstPersonOfficialItemSubmissions", 0) > 0,
                          "actualFirstPersonHandAndItemSubmissions", **where)
            fp_actions[scenario].add(obs.get("firstPersonAction", ""))
            if previous is not None and "submittedAvatar" not in previous["observation"]:
                before = previous["observation"]
                audit_first_person_pair(audit, before, obs, where)
            previous = frame
            continue
        tp_frames += 1
        audit_rapier_pose(audit, avatar, where)
        anim = avatar.get("renderAnimation", {})
        if "rapier_fencing:0100" in anim.get("clipTags", []):
            phrase = anim.get("rapierPhrase")
            if audit_phrase(audit, phrase, where):
                phrases.add(phrase)
            for side in ("right", "left") if anim.get("transitionElapsedTicks", 0) >= anim.get("transitionTicks", 0) - .001 else ():
                elbow = native.unit(avatar[side + "ForearmLocalQuaternion"])
                pronation = native.y_pronation(elbow)
                hinge = native.without_y_pronation(elbow, pronation, first=False)
                bend = (math.degrees(2 * math.atan2(hinge[0], hinge[3])) + 180) % 360 - 180
                audit.require(10 - .05 <= abs(bend) <= 135 + .05, "authoredElbowFlexion10To135", **where, side=side, actual=bend)
                audit.require(abs(pronation) <= 30 + .05, "authoredForearmPronation30", **where, side=side, actual=pronation)
        for side in ("right", "left"):
            shoulder, elbow, hand = (avatar["rig"][side + suffix] for suffix in ("_arm", "_forearm", "_hand"))
            arm_lengths[side + "Upper"].append(math.dist(shoulder[12:15], elbow[12:15]))
            arm_lengths[side + "Forearm"].append(math.dist(elbow[12:15], hand[12:15]))
        if previous is not None and previous["observation"].get("scenario") == scenario:
            before_avatar = avatar_of(previous["observation"])
            if before_avatar is not None:
                before = {**previous, "observation": before_avatar}
                after = {**frame, "observation": avatar}
                native.audit_pair(audit, before, after, "rapier-fencing")
                if math.floor(previous["gameTimeTicks"]) == math.floor(frame["gameTimeTicks"]) and before_avatar["rig"] != avatar["rig"]:
                    subtick_pairs += 1
        previous = frame
    for bone, values in arm_lengths.items():
        variation = max(values) - min(values)
        audit.require(variation < .05, "rigidHumanArmSegments", bone=bone, minimumCm=min(values), maximumCm=max(values), variationCm=variation)
    audit.require(phrases == PHRASES, "allFiveReauthoredNativePhrases")
    audit.require(len(dimensions) == 1 and len(hashes) >= 30, "nativePngDimensionsAndDistinctPixels")
    audit.require(subtick_pairs > 0, "actualSubtickPoses")
    expected = {f"legacy_rapier_attack{i}_upper" for i in range(1, 6)}
    audit.require(expected <= fp_actions["first-person-five-phrase-chain"], "firstPersonAllFivePhrasesActuallyRendered")
    audit.require({"legacy_rapier_attack1_upper", "legacy_rapier_rapier_thrust_hit_upper"} <= fp_actions["first-person-left-right-branch"],
                  "firstPersonRightBranchActuallyRendered")
    return {"passed": not audit.failed_counts, "sourceDataModified": False, "captureSha256": native.digest(capture_path),
            "modJarSha256": sha, "actualFrames": len(frames), "thirdPersonFrames": tp_frames, "firstPersonFrames": fp_frames,
            "sourceFps": fps, "distinctDecodedPngs": len(hashes), "actualSubtickPairs": subtick_pairs,
            "scenarios": dict(scenarios), "firstPersonActions": {k: sorted(v) for k, v in fp_actions.items()},
            "thresholds": {"wristFlexionDegrees": 34, "newPhraseElbowDegrees": [10, 135], "newPhraseForearmPronationDegrees": 30},
            "checkCount": sum(audit.counts.values()), "checksByCategory": dict(audit.counts),
            "failureCountsByCategory": dict(audit.failed_counts), "failures": audit.failures, "peaks": audit.peaks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--jar", type=Path)
    args = parser.parse_args()
    root = args.evidence.resolve(strict=True)
    game = native.read_json(root / "rapier-fencing-review.json")
    jar = args.jar.resolve(strict=True) if args.jar else Path(game["modJar"]).resolve(strict=True)
    report = review(root, jar)
    output = root / "independent-rapier-flow-review.json"
    output.write_text(json.dumps(native.safe_json(report), ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "frames": report["actualFrames"], "sourceFps": report["sourceFps"],
                      "failureCounts": report["failureCountsByCategory"], "output": str(output)}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
