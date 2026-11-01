"""Encode one completed native PNG capture at 30 FPS using its real timestamps.

This does not change the separate ten-clip acceptance in encode-review.ps1.
The 30 FPS filter selects captured images; no motion interpolation is used.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import shutil
import subprocess
from pathlib import Path

from PIL import Image


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--threads", type=int, choices=(2, 3, 4), default=2)
    parser.add_argument("--clip", help="Select one clip from a multi-clip capture without changing its source report")
    parser.add_argument("--scene-capture", action="store_true",
                        help="Validate world/UI interaction footage rather than requiring animated rig observations")
    options = parser.parse_args()
    capture_file = options.capture.resolve(strict=True)
    root = capture_file.parent
    capture_bytes = capture_file.read_bytes()
    capture = json.loads(capture_bytes)
    require(capture.get("captureEvent") == "RenderFrameEvent.Post" and capture.get("pendingWrites") == 0,
            "Only completed actual native render captures are accepted")
    clips = capture.get("clips", [])
    selected = [clip for clip in clips if clip.get("id") == options.clip] if options.clip else clips
    require(len(selected) == 1, "Select exactly one native capture clip with --clip")
    clip = selected[0]
    identifier = clip.get("id", "")
    require(identifier and all(character in "abcdefghijklmnopqrstuvwxyz0123456789-" for character in identifier),
            "Invalid capture ID")
    frames = clip.get("frames", [])
    require(len(frames) >= 31 and clip.get("writtenFrames") == len(frames)
            and clip.get("capturedFrames") == len(frames), "Incomplete native frame inventory")
    duration = frames[-1]["capturedAtSeconds"] - frames[0]["capturedAtSeconds"]
    require(math.isfinite(duration) and duration > 0, "Invalid native capture duration")
    source_fps = (len(frames) - 1) / duration
    require(source_fps >= 30 and clip.get("distinctSamplesWithinGameTick", 0) > 0,
            "Native source must contain real >=30 Hz subtick frames")
    if not options.scene_capture:
        require(clip.get("changedRigPairs", 0) > 0, "Animation capture must contain changing rig observations")
    require(abs(clip.get("sourceFps", -1) - source_fps) < 1e-8
            and abs(clip.get("captureDurationSeconds", -1) - duration) < 1e-8,
            "Reported source timing disagrees with individual frame timestamps")

    entries = ["ffconcat version 1.0"]
    hashes: set[str] = set()
    png_rows = []
    dimensions = None
    for index, frame in enumerate(frames):
        expected = f"screenshots/render-frames/{identifier}/frame-{index:06}.png"
        require(frame.get("index") == index and frame.get("file") == expected, "Unexpected frame index/path")
        image_file = (root / expected).resolve(strict=True)
        require(image_file.is_relative_to(root), "Frame outside capture directory")
        png = image_file.read_bytes()
        # Independent Pillow validation checks the PNG structure and chunk CRCs.
        # ffmpeg separately decodes the source images when reading the timeline.
        with Image.open(io.BytesIO(png)) as image:
            require(image.format == "PNG", f"Not an actual PNG: {expected}")
            size = image.size
            require(size[0] > 0 and size[1] > 0, f"Empty PNG: {expected}")
            require(dimensions in (None, size), "Native frame dimensions changed within the clip")
            dimensions = size
            image.verify()
        image_sha = digest(png)
        hashes.add(image_sha)
        png_rows.append({"index": index, "file": expected, "sha256": image_sha,
                         "capturedAtSeconds": frame["capturedAtSeconds"], "verifiedPng": True})
        gap = (frames[index + 1]["capturedAtSeconds"] - frame["capturedAtSeconds"]
               if index + 1 < len(frames) else 1 / source_fps)
        require(math.isfinite(gap) and 0 < gap <= .25, f"Non-monotonic/stalled native timestamp at frame {index}")
        entries.extend((f"file '{expected}'", "option framerate 1000", f"duration {gap:.9f}"))
    require(len(hashes) >= 30, "Fewer than thirty distinct real PNG byte hashes")
    entries.extend((f"file '{frames[-1]['file']}'", "option framerate 1000"))
    prefix = identifier + "-" if options.clip else ""
    timeline = root / (prefix + "native-timestamps.ffconcat")
    timeline.write_text("\n".join(entries) + "\n", encoding="utf-8")
    png_validation = root / (prefix + "native-png-validation.json")
    write_json(png_validation, {"passed": True, "captureSha256": digest(capture_bytes),
                               "method": "Independent Pillow PNG format/dimensions/chunk-CRC verification plus per-file SHA-256",
                               "verifiedPngFiles": len(png_rows), "uniquePngByteHashes": len(hashes),
                               "width": dimensions[0], "height": dimensions[1], "frames": png_rows})

    encoder, probe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    require(encoder is not None and probe is not None, "ffmpeg and ffprobe must be available")
    movie = root / (prefix + "visual-review.mp4")
    threads = str(options.threads)
    command = [encoder, "-hide_banner", "-loglevel", "warning", "-y", "-threads", threads,
               "-filter_threads", threads, "-f", "concat", "-safe", "0", "-i", str(timeline),
               "-vf", "fps=30:round=near", "-c:v", "libx264", "-threads", threads,
               "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(movie)]
    encoded = subprocess.run(command, capture_output=True, text=True, check=False)
    (root / (prefix + "encoding.log")).write_text(encoded.stderr, encoding="utf-8")
    require(encoded.returncode == 0, "Video encoding failed; see encoding.log")
    probe_command = [probe, "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                     "stream=codec_name,width,height,avg_frame_rate,r_frame_rate,nb_frames,nb_read_frames,duration:format=duration",
                     "-of", "json", str(movie)]
    probed = subprocess.run(probe_command, capture_output=True, text=True, check=True)
    video = json.loads(probed.stdout)
    stream = video["streams"][0]
    require(stream["avg_frame_rate"] == "30/1" and stream["r_frame_rate"] == "30/1", "Encoded video is not exactly 30 FPS")
    require((stream["width"], stream["height"]) == dimensions, "Encoded dimensions differ from real PNGs")
    require(int(stream["nb_frames"]) == int(stream["nb_read_frames"]), "ffprobe could not decode every encoded frame")
    require(abs(float(stream["duration"]) - duration) <= 2 / 30, "Encoded duration does not follow the actual timestamp span")
    require(capture_file.read_bytes() == capture_bytes, "Original capture report changed during encoding")
    report = {"passed": True, "schemaVersion": 1, "captureEvent": capture["captureEvent"],
              "sourceCapture": capture_file.name, "sourceCaptureSha256": digest(capture_bytes),
              "originalCaptureUnchanged": True, "clipId": identifier, "source": clip["source"],
              "capturedFrames": len(frames), "sourceFps": source_fps, "captureDurationSeconds": duration,
              "distinctSamplesWithinGameTick": clip["distinctSamplesWithinGameTick"], "changedRigPairs": clip["changedRigPairs"],
              "captureValidation": "scene-png-and-timestamps" if options.scene_capture else "animated-rig-png-and-timestamps",
              "output": movie.name, "outputSha256": digest(movie.read_bytes()), "outputFps": 30,
              "encodedFrames": int(stream["nb_read_frames"]), "encodingThreads": options.threads,
              "timestampMethod": "Actual per-PNG timestamp gaps; ffconcat image time base 1000 Hz; fps=30 selects real source images",
              "motionInterpolation": False, "independentPngValidation": {"passed": True,
                  "report": png_validation.name, "reportSha256": digest(png_validation.read_bytes()),
                  "verifiedPngFiles": len(png_rows), "uniquePngByteHashes": len(hashes),
                  "width": dimensions[0], "height": dimensions[1]},
              "timeline": timeline.name, "timelineSha256": digest(timeline.read_bytes()),
              "ffmpegCommand": command, "ffprobeCommand": probe_command, "ffprobe": video,
              "limitation": "Viewing resample only. Native source statistics remain in the unchanged capture; this movie is not a performance measurement."}
    write_json(root / (prefix + "encoded-review.json"), report)
    print(f"Encoded {len(frames)} independently verified native PNGs at {source_fps:.8f} source Hz -> "
          f"{stream['nb_read_frames']} actual-image selections at 30 FPS: {movie}")


if __name__ == "__main__":
    main()
