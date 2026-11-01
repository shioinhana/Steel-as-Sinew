"""Compare completed native-frame reports. Does not launch a game or alter configuration.

Run after all three reports exist:
    python tools/compare-spear-realtime.py

The output is deterministic for identical reports and JAR bytes. Missing telemetry
from 0.7.2 remains null; it is never presented as zero solver time or zero fallbacks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any


SCENES = (
    "sheathed-control", "spear-idle", "spear-aim-sweep", "spear-movement",
    "spear-attacks", "spear-stress-30s",
)
USER_SCENES = ("sheathed-control", "spear-stress-30s")
DISPLAY = r"\\.\DISPLAY5"
PERCENTILES = ("p50Ms", "p95Ms", "p99Ms", "maxMs", "meanMs")
TAILS = ("over16Ms", "over33Ms", "over50Ms", "over100Ms")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def finite(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def nonnegative(value: Any) -> bool:
    return finite(value) and value >= 0


def counter(row: dict, name: str) -> int | None:
    value = row.get("solverCounters", {}).get(name)
    return int(value) if nonnegative(value) and int(value) == value else None


def ratio(after: Any, before: Any) -> float | None:
    return after / before if nonnegative(after) and finite(before) and before > 0 else None


def solver(row: dict) -> dict:
    calls = counter(row, "spearSolveCalls")
    nanos = counter(row, "spearSolveNanos")
    requests = counter(row, "frameRequests")
    hits = counter(row, "frameCacheHits")
    continuations = counter(row, "spearContinuationCalls")
    return {
        "telemetryAvailable": calls is not None and nanos is not None,
        "calls": calls,
        "totalMs": nanos / 1e6 if nanos is not None else None,
        "meanMs": nanos / calls / 1e6 if nanos is not None and calls else None,
        "meanUnit": "milliseconds per actual SpearAimPose.apply call",
        "frameRequests": requests,
        "cacheHits": hits,
        "cacheHitRate": ratio(hits, requests),
        "continuationFallbackAttempts": continuations,
        "fallbackRate": ratio(continuations, calls),
        "fallbackRateUnit": "continuation/rescue attempts per solve; several attempts can belong to one solve",
        "callsPerNativeFrame": ratio(calls, row.get("frames")),
        "processMaximumSolveMs": counter(row, "maxSpearSolveNanos") / 1e6
            if counter(row, "maxSpearSolveNanos") is not None else None,
        "maximumScope": "process-wide high-water mark, not a per-scene maximum",
        "duplicateSolveTelemetryAvailable": calls is not None,
    }


def scene_summary(row: dict) -> dict:
    seconds = row.get("sampleSeconds")
    frames = row.get("frames")
    quality_names = (
        "motionAtLeast30Hz", "qualityFrames", "qualityObservedHz", "badGripFrames",
        "completeShaftClearFailures", "nativeSubtickPosePairs", "changedCachedPosePairs",
        "observedRenderedRigFrames", "officialHeldItemSubmissions", "framesWithMoreThanOneSpearSolve",
        "maximumGripAxisErrorCm", "maximumNativePoseGapTicks", "jointFrameStepsOver25Degrees",
        "maximumNativeJointStepDegrees", "minimumGripSpacingCm", "maximumGripSpacingCm",
    )
    summary = {
        "fps": row.get("fps"), "frames": frames, "sampleSeconds": seconds,
        "frameInterval": {key: row.get("frameInterval", {}).get(key) for key in PERCENTILES + TAILS},
        "renderPreToPost": {key: row.get("renderPreToPost", {}).get(key) for key in PERCENTILES + TAILS},
        "garbageCollectionCount": row.get("garbageCollectionCount"),
        "garbageCollectionTimeMs": row.get("garbageCollectionTimeMs"),
        "qualityObserverTimeMs": row.get("qualityObserverTimeMs"),
        "solver": solver(row),
        "quality": {name: row.get(name) for name in quality_names},
        "over50FramesPerSecond": ratio(row.get("frameInterval", {}).get("over50Ms"), seconds),
        "over100FramesPerSecond": ratio(row.get("frameInterval", {}).get("over100Ms"), seconds),
        "over50FrameFraction": ratio(row.get("frameInterval", {}).get("over50Ms"), frames),
        "over100FrameFraction": ratio(row.get("frameInterval", {}).get("over100Ms"), frames),
        # Keep the recorded phase and GC counters of the worst real frames visible.
        "worstRecordedSlowFrames": sorted(
            row.get("slowFramesOver25Ms", []), key=lambda value: value.get("frameIntervalMs", 0), reverse=True
        )[:5],
    }
    for field in ("unarmoredStress", "ironArmorStress", "actualArmorTransitionSampleSeconds"):
        if field in row:
            summary[field] = row[field]
    return summary


class Acceptance:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def require(self, value: bool, message: str) -> None:
        if not value:
            self.failures.append(message)

    def validate_report(self, report: dict, label: str, expected_scenes: tuple[str, ...],
                        expected_sha: str, require_new_telemetry: bool) -> dict[str, dict]:
        self.require(report.get("passed") is True, f"{label}: actual game report did not pass")
        self.require(report.get("modJarSha256") == expected_sha, f"{label}: exact JAR hash does not match")
        self.require(report.get("monitorAdapter") == DISPLAY, f"{label}: actual window is not DISPLAY5")
        self.require((report.get("windowWidth"), report.get("windowHeight")) == (1000, 680),
                     f"{label}: actual window size is not 1000 by 680")
        rows = report.get("scenes", [])
        names = [row.get("scene") for row in rows]
        self.require(report.get("completedScenes") == len(expected_scenes) and len(rows) == len(expected_scenes)
                     and names == list(expected_scenes), f"{label}: required ordered scene inventory is incomplete")
        by_scene = {row.get("scene"): row for row in rows}
        thrusts = report.get("realServerThrustTimings", [])
        self.require(len(thrusts) >= 8, f"{label}: fewer than eight actual server thrust timings")
        self.require(all(value.get("configuredDurationTicks") == 4 and value.get("observedDurationTicks") == 4
                         and value.get("observedSeconds") == .2 for value in thrusts),
                     f"{label}: comparison does not use identical real 0.2-second thrusts")
        for name in expected_scenes:
            if name not in by_scene:
                continue
            row = by_scene[name]
            timestamps = row.get("sourcePostTimestampsNanos", [])
            intervals = row.get("sourceFrameIntervalsMs", [])
            native_ok = len(timestamps) >= 5 and row.get("frames") == len(timestamps)
            native_ok = native_ok and all(finite(value) for value in timestamps)
            native_ok = native_ok and all(after > before for before, after in zip(timestamps, timestamps[1:]))
            native_ok = native_ok and len(intervals) == max(0, len(timestamps) - 1)
            self.require(native_ok, f"{label}/{name}: native frame timestamps/counts are invalid")
            if native_ok:
                duration = (timestamps[-1] - timestamps[0]) / 1e9
                self.require(finite(row.get("sampleSeconds")) and abs(duration - row["sampleSeconds"]) < 1e-6
                             and finite(row.get("fps")) and abs((len(timestamps) - 1) / duration - row["fps"]) < 1e-5,
                             f"{label}/{name}: reported FPS/duration disagree with native timestamps")
                self.require(all(nonnegative(value) for value in intervals), f"{label}/{name}: invalid frame intervals")
                if all(nonnegative(value) for value in intervals):
                    expected_intervals = [(after - before) / 1e6 for before, after in zip(timestamps, timestamps[1:])]
                    self.require(all(abs(recorded - actual) < 1e-5 for recorded, actual in zip(intervals, expected_intervals)),
                                 f"{label}/{name}: raw intervals disagree with native timestamps")
                    ordered = sorted(intervals)
                    for key, quantile in (("p50Ms", .5), ("p95Ms", .95), ("p99Ms", .99), ("maxMs", 1)):
                        measured = row.get("frameInterval", {}).get(key)
                        expected = ordered[math.floor((len(ordered) - 1) * quantile)]
                        self.require(finite(measured) and abs(measured - expected) < 1e-5,
                                     f"{label}/{name}: {key} disagrees with actual recorded intervals")
                    for key, threshold in (("over50Ms", 50), ("over100Ms", 100)):
                        self.require(row.get("frameInterval", {}).get(key) == sum(value > threshold for value in intervals),
                                     f"{label}/{name}: {key} does not include all slow frames")
            if name == "sheathed-control":
                continue
            self.require(row.get("motionAtLeast30Hz") is True and finite(row.get("fps")) and row["fps"] >= 30,
                         f"{label}/{name}: actual rendered motion is below 30 Hz")
            self.require(finite(row.get("qualityObservedHz")) and row["qualityObservedHz"] >= 30
                         and nonnegative(row.get("qualityFrames")) and row["qualityFrames"] > 0,
                         f"{label}/{name}: submitted-pose quality observation is below 30 Hz")
            self.require(row.get("badGripFrames") == 0 and row.get("completeShaftClearFailures") == 0,
                         f"{label}/{name}: actual submitted grip/shaft quality failed")
            self.require(row.get("framesWithMoreThanOneSpearSolve") == 0,
                         f"{label}/{name}: an actor was solved more than once in one native frame")
            for field in ("nativeSubtickPosePairs", "changedCachedPosePairs", "observedRenderedRigFrames", "officialHeldItemSubmissions"):
                self.require(nonnegative(row.get(field)) and row[field] > 0, f"{label}/{name}: missing real {field}")
            if require_new_telemetry:
                calls, requests, hits = (counter(row, field) for field in ("spearSolveCalls", "frameRequests", "frameCacheHits"))
                self.require(calls is not None and calls > 0 and counter(row, "spearSolveNanos") is not None,
                             f"{label}/{name}: current actual solver timing is unavailable")
                self.require(requests is not None and hits is not None and 0 <= hits <= requests,
                             f"{label}/{name}: current frame-cache telemetry is unavailable or invalid")
                self.require(counter(row, "spearContinuationCalls") is not None,
                             f"{label}/{name}: current continuation/fallback telemetry is unavailable")
            if name == "spear-stress-30s":
                self.require(finite(row.get("sampleSeconds")) and row["sampleSeconds"] >= 29
                             and row.get("ironArmorStress", {}).get("frames", 0) > 0
                             and row.get("unarmoredStress", {}).get("frames", 0) > 0,
                             f"{label}/{name}: full thirty-second real armor stress is incomplete")
        capture = report.get("separateUntimedNativeVisualCapture")
        if capture is not None:
            for clip in capture.get("clips", []):
                self.require(clip.get("sourceFps", 0) >= 30 and clip.get("distinctSamplesWithinGameTick", 0) > 0,
                             f"{label}: separate native movie failed true 30 Hz/subtick acceptance")
        return by_scene


def compare_scene(name: str, old: dict, new: dict) -> dict:
    before, after = scene_summary(old), scene_summary(new)
    ratios = {field: ratio(after["frameInterval"][field], before["frameInterval"][field]) for field in PERCENTILES}
    ratios["fps"] = ratio(after["fps"], before["fps"])
    improvement = {field: (1 - value) * 100 if value is not None else None for field, value in ratios.items() if field != "fps"}
    if ratios["fps"] is not None:
        improvement["fps"] = (ratios["fps"] - 1) * 100
    regressions: list[str] = []
    for field in ("p50Ms", "p95Ms", "p99Ms", "maxMs"):
        if finite(before["frameInterval"][field]) and finite(after["frameInterval"][field]) and after["frameInterval"][field] > before["frameInterval"][field]:
            regressions.append(f"frameInterval.{field}")
    for field in ("over50Ms", "over100Ms"):
        if nonnegative(before["frameInterval"][field]) and nonnegative(after["frameInterval"][field]) and after["frameInterval"][field] > before["frameInterval"][field]:
            regressions.append(f"frameInterval.{field}")
    for field in ("garbageCollectionCount", "garbageCollectionTimeMs"):
        if nonnegative(before[field]) and nonnegative(after[field]) and after[field] > before[field]:
            regressions.append(field)
    return {"scene": name, "before": before, "after": after, "afterOverBefore": ratios,
            "improvementPercent": improvement, "regressions": regressions,
            "beforeFps": before["fps"], "afterFps": after["fps"],
            "beforeP95Ms": before["frameInterval"]["p95Ms"], "afterP95Ms": after["frameInterval"]["p95Ms"],
            "beforeP99Ms": before["frameInterval"]["p99Ms"], "afterP99Ms": after["frameInterval"]["p99Ms"],
            "beforeMaxMs": before["frameInterval"]["maxMs"], "afterMaxMs": after["frameInterval"]["maxMs"]}


def reject_constant(value: str) -> None:
    raise ValueError(f"Nonfinite JSON constant: {value}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    project = Path(__file__).resolve().parents[1]
    parser.add_argument("--evidence", type=Path, default=project / "verification/spear-realtime-20261016")
    options = parser.parse_args()
    evidence = options.evidence.resolve()
    paths = {"baseline": evidence / "baseline/game/spear-performance-review.json",
             "optimized": evidence / "optimized/game/spear-performance-review.json",
             "officialSettings": evidence / "user-settings/game/spear-performance-review.json"}
    candidate = project / "build/libs/fsrpg-neoforge-0.7.3.jar"
    required = [candidate, *paths.values()]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        print("Comparison not run; required completed evidence is missing:\n" + "\n".join(missing), file=sys.stderr)
        return 2
    reports = {name: json.loads(path.read_text(encoding="utf-8-sig"), parse_constant=reject_constant) for name, path in paths.items()}
    acceptance = Acceptance()
    latest_sha = sha256(candidate)
    baseline_sha = reports["baseline"].get("modJarSha256")
    acceptance.require(isinstance(baseline_sha, str) and len(baseline_sha) == 64,
                       "baseline: archive SHA-256 is missing")
    source_reports = {}
    for name, path in paths.items():
        report = reports[name]
        source = Path(report.get("modJar", ""))
        source_matches = source.is_file() and sha256(source) == report.get("modJarSha256")
        acceptance.require(source_matches, f"{name}: reported tested JAR bytes are missing or changed")
        source_reports[name] = {"path": path.relative_to(evidence).as_posix(), "sha256": sha256(path),
                                "testedJar": str(source), "testedJarSha256": report.get("modJarSha256"),
                                "capturedAtUtc": report.get("capturedAtUtc"), "copiedWorld": report.get("copiedWorld")}
    old = acceptance.validate_report(reports["baseline"], "baseline", SCENES, baseline_sha, False)
    new = acceptance.validate_report(reports["optimized"], "optimized", SCENES, latest_sha, True)
    user = acceptance.validate_report(reports["officialSettings"], "officialSettings", USER_SCENES, latest_sha, True)
    official = reports["officialSettings"]
    acceptance.require(official.get("officialVideoSettingsConfirmation") is True and official.get("configuredVsync") is True
                       and official.get("configuredFpsCap") == 120 and official.get("configuredRenderDistance") == 12,
                       "officialSettings: real VSync/120 FPS/render-distance-12 confirmation is missing")
    optimized = reports["optimized"]
    acceptance.require(optimized.get("officialVideoSettingsConfirmation") is False and optimized.get("configuredVsync") is False
                       and optimized.get("configuredFpsCap") == 260 and optimized.get("configuredRenderDistance") == 3,
                       "optimized: controlled VSync-off/cap-260/render-distance-3 settings are missing")
    groups = [compare_scene(name, old[name], new[name]) for name in SCENES if name in old and name in new]
    drawn = [group for group in groups if group["scene"] != "sheathed-control"]
    p99_ratios = [group["afterOverBefore"]["p99Ms"] for group in drawn if group["afterOverBefore"]["p99Ms"] is not None]
    improved = sum(value < 1 for value in p99_ratios)
    median_ratio = statistics.median(p99_ratios) if p99_ratios else None
    meaningful = len(p99_ratios) == 5 and improved >= 4 and median_ratio is not None and median_ratio < .8
    acceptance.require(meaningful, "performance: need at least four of five drawn P99 values lower and median after/before below 0.8")
    result = {
        "passed": not acceptance.failures,
        "failures": acceptance.failures,
        "baselineJarSha256": baseline_sha,
        "optimizedJarSha256": latest_sha,
        "monitorAdapter": DISPLAY,
        "candidateJar": str(candidate),
        "sourceReports": source_reports,
        "method": "Native RenderFrameEvent.Post timestamps; same 1000x680 DISPLAY5 copied-world fixture, real 4-tick thrusts in both versions. Frame percentiles include every recorded sample; quality observers and GC remain included.",
        "acceptance": {"passed": meaningful, "drawnScenes": len(p99_ratios), "drawnP99ImprovedScenes": improved,
                       "requiredImprovedScenes": 4, "medianDrawnP99AfterOverBefore": median_ratio,
                       "maximumMedianDrawnP99AfterOverBefore": .8, "qualityFailuresAllowed": 0,
                       "minimumNativeMotionAndQualityHz": 30, "sameActualThrustDurationSeconds": .2},
        "groups": groups,
        "regressions": [{"scene": group["scene"], "metrics": group["regressions"]} for group in groups if group["regressions"]],
        "officialSettings": {"passed": official.get("passed"), "exactCandidateJar": official.get("modJarSha256") == latest_sha,
                             "vsync": official.get("configuredVsync"), "fpsCap": official.get("configuredFpsCap"),
                             "renderDistance": official.get("configuredRenderDistance"),
                             "scenes": [{"scene": name, **scene_summary(user[name])} for name in USER_SCENES if name in user]},
        "notes": [
            "P99 acceptance does not suppress a worse maximum, slow-frame count or garbage-collection result; those remain listed for every scene and in regressions.",
            "The sheathed control is reported in full and is excluded only from the five drawn-scene P99 acceptance threshold.",
            "Baseline -1 or missing counters mean unavailable, not zero cost. The old build cannot prove its duplicate-solve rate through missing instrumentation.",
            "Fallback telemetry counts continuation/rescue attempts, not distinct frames; fallbackRate is attempts per solve and can exceed one.",
            "Solver processMaximumSolveMs is a cumulative process high-water mark, not a local scene maximum.",
            "Official-settings confirmation uses the new candidate with VSync and a 120 FPS cap; it is a separate workload, not a like-for-like speedup ratio.",
            "The unchanged stress driver submits one attack intent per five client ticks (4 Hz at 20 TPS); the old baseline method text incorrectly called this 5 Hz.",
            "These are local copied-world measurements; performance can differ with hardware, worlds, entities, other mods and background activity.",
        ],
    }
    output = evidence / "comparison.json"
    temporary = output.with_suffix(".json.tmp")
    temporary.write_bytes((json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8"))
    temporary.replace(output)
    print(f"Comparison passed={result['passed']}; drawn P99 improved={improved}/5; median after/before={median_ratio}; {output}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
