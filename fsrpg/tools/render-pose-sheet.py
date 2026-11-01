"""Render FSRPG pose dumps (PoseSheetDump) as orthographic multi-view contact sheets.

Usage: python tools/render-pose-sheet.py <poses.json> <out-dir> [label]
Outputs per sequence: <seq>-sheet.png (key frames x views), <seq>-trail.png (blade tip arcs),
<seq>-timing.png (blade tip speed with server phase bands).
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

VIEWS = [("front", 180, 8), ("right side", 90, 8), ("3/4 high", 145, 28), ("top", 180, 80)]
COLORS = {
    "body": (70, 110, 190), "head": (225, 180, 140), "right_upper": (200, 70, 60), "right_fore": (235, 120, 100),
    "left_upper": (60, 160, 80), "left_fore": (110, 200, 120), "right_thigh": (110, 90, 120), "right_shin": (150, 130, 160),
    "left_thigh": (90, 90, 90), "left_shin": (140, 140, 140),
}


def mat(a):
    # JOML Matrix4f.get(float[]) is column-major.
    return [[a[c * 4 + r] for c in range(4)] for r in range(4)]


def xf(m, p):
    return tuple(m[r][0] * p[0] + m[r][1] * p[1] + m[r][2] * p[2] + m[r][3] for r in range(3))


def box(m, lo, hi):
    cs = [(x, y, z) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    return [xf(m, c) for c in cs]


FACES = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]


def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def mul(a, s): return (a[0] * s, a[1] * s, a[2] * s)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def norm(a):
    n = math.sqrt(dot(a, a)) or 1
    return (a[0] / n, a[1] / n, a[2] / n)


def segment_box(a, b, width, up_hint=(0, 1, 0)):
    """Square-section box between two points."""
    axis = norm(sub(b, a))
    side = cross(axis, up_hint)
    if dot(side, side) < 1e-6:
        side = cross(axis, (1, 0, 0))
    side = norm(side)
    other = norm(cross(axis, side))
    h = width / 2
    corners = []
    for p in (a, b):
        for s in (-h, h):
            for o in (-h, h):
                corners.append(add(add(p, mul(side, s)), mul(other, o)))
    # reorder to match FACES layout (x,y,z bits): use (end, side, other)
    order = [corners[0], corners[1], corners[4], corners[5], corners[2], corners[3], corners[6], corners[7]]
    return order


def solids(frame, body_top):
    b = frame["bones"]
    out = []
    if "body" in b:
        out.append(("body", box(mat(b["body"]), (-25, body_top - 75, -12.5), (25, body_top, 12.5))))
    if "head" in b:
        out.append(("head", box(mat(b["head"]), (-25, 0, -25), (25, 50, 25))))
    for side, cx in (("right", -6.25), ("left", 6.25)):
        if side + "_arm" in b:
            out.append((side + "_upper", box(mat(b[side + "_arm"]), (cx - 12.5, -37.5, -12.5), (cx + 12.5, 12.5, 12.5))))
        if side + "_forearm" in b:
            out.append((side + "_fore", box(mat(b[side + "_forearm"]), (cx - 12.5, -25, -12.5), (cx + 12.5, 0, 12.5))))
    if "legs" in frame:
        for side in ("right", "left"):
            leg = frame["legs"][side]
            hip, sole = tuple(leg["hip"]), tuple(leg["sole"])
            d = math.dist(hip, sole)
            mid = mul(add(hip, sole), .5)
            yaw = leg["yaw"]
            fwd = (math.sin(yaw), 0, math.cos(yaw))
            axis = norm(sub(sole, hip))
            fwd = norm(sub(fwd, mul(axis, dot(fwd, axis))))
            h = math.sqrt(max(0, 37.5 ** 2 - (d / 2) ** 2))
            knee = add(mid, mul(fwd, h))
            out.append((side + "_thigh", segment_box(hip, knee, 25)))
            out.append((side + "_shin", segment_box(knee, sole, 25)))
    else:
        for side in ("right", "left"):
            if side + "_leg" in b:
                out.append((side + "_thigh", box(mat(b[side + "_leg"]), (-12.5, -37.5, -12.5), (12.5, 0, 12.5))))
            if side + "_foreleg" in b:
                out.append((side + "_shin", box(mat(b[side + "_foreleg"]), (-12.5, -37.5, -12.5), (12.5, 0, 12.5))))
    return out


def camera(azimuth, elevation):
    az, el = math.radians(azimuth), math.radians(elevation)
    eye = (math.sin(az) * math.cos(el), math.sin(el), math.cos(az) * math.cos(el))
    forward = mul(eye, -1)
    right = norm(cross(forward, (0, 1, 0)))
    up = cross(right, forward)
    return right, up, forward


def project(p, cam, scale, ox, oy):
    right, up, forward = cam
    return (ox + dot(p, right) * scale, oy - dot(p, up) * scale), dot(p, forward)


def draw_pose(draw, frame, cam, scale, ox, oy, body_top, center=(0, 100, 0)):
    light = norm((0.4, 0.8, 0.5))
    polys = []
    for name, corners in solids(frame, body_top):
        for face in FACES:
            pts3 = [sub(corners[i], center) for i in face]
            n = norm(cross(sub(pts3[1], pts3[0]), sub(pts3[2], pts3[0])))
            proj = [project(p, cam, scale, ox, oy) for p in pts3]
            depth = sum(d for _, d in proj) / 4
            shade = 0.45 + 0.55 * abs(dot(n, light))
            col = tuple(int(c * shade) for c in COLORS[name])
            polys.append((depth, [xy for xy, _ in proj], col))
    for key, col in (("blade", (20, 220, 230)), ("leftBlade", (230, 200, 30))):
        if key in frame:
            a, b = (sub(tuple(p), center) for p in frame[key])
            pa, da = project(a, cam, scale, ox, oy)
            pb, db = project(b, cam, scale, ox, oy)
            polys.append(((da + db) / 2 - 30, ("line", pa, pb), col))
    polys.sort(key=lambda t: -t[0])
    for _, pts, col in polys:
        if pts[0] == "line":
            draw.line([pts[1], pts[2]], fill=col, width=max(2, int(scale * 4)))
            draw.ellipse([pts[2][0] - 3, pts[2][1] - 3, pts[2][0] + 3, pts[2][1] + 3], fill=col)
        else:
            draw.polygon(pts, fill=col, outline=(30, 30, 30))
    # floor line and facing arrow
    f0, _ = project(sub((0, 0, 0), center), cam, scale, ox, oy)
    f1, _ = project(sub((0, 0, 60), center), cam, scale, ox, oy)
    draw.line([f0, f1], fill=(250, 130, 0), width=2)


def font(size):
    for name in ("msyh.ttc", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def sheet(seq, frames, out, label, body_top, every):
    picked = frames[::every]
    cell, scale = 250, 1.0
    cols = len(VIEWS)
    img = Image.new("RGB", (cols * cell + 150, len(picked) * cell + 30), (245, 245, 240))
    d = ImageDraw.Draw(img)
    f = font(13)
    d.text((6, 6), f"{label}  {seq}", fill=(0, 0, 0), font=font(16))
    for ci, (vname, az, el) in enumerate(VIEWS):
        d.text((150 + ci * cell + 6, 22), vname, fill=(60, 60, 60), font=f)
    cams = [camera(az, el) for _, az, el in VIEWS]
    for ri, fr in enumerate(picked):
        y = 30 + ri * cell
        d.text((6, y + 10), f"t={fr['tick']:.1f}\n{fr['action'].replace('legacy_', '')[:22]}\n{fr['phase']}", fill=(0, 0, 0), font=f)
        for ci, cam in enumerate(cams):
            x = 150 + ci * cell
            d.rectangle([x, y, x + cell - 2, y + cell - 2], outline=(200, 200, 200))
            draw_pose(d, fr, cam, scale, x + cell / 2, y + cell / 2 + 10, body_top)
    img.save(out / f"{seq}-sheet.png")


def trail(seq, frames, out, label, body_top):
    cell, scale = 420, 1.35
    img = Image.new("RGB", (cell * 3, cell + 30), (250, 250, 250))
    d = ImageDraw.Draw(img)
    d.text((6, 6), f"{label}  {seq}  blade tip arcs (dot = 1/60 s; red = ACTIVE)", fill=(0, 0, 0), font=font(15))
    for ci, (vname, az, el) in enumerate([VIEWS[0], VIEWS[2], VIEWS[3]]):
        cam = camera(az, el)
        ox, oy = ci * cell + cell / 2, 30 + cell / 2 + 20
        draw_pose(d, frames[0], cam, scale, ox, oy, body_top)
        for key, base in (("blade", (0, 120, 200)), ("leftBlade", (200, 140, 0))):
            prev = None
            for fr in frames:
                if key not in fr:
                    continue
                p, _ = project(sub(tuple(fr[key][1]), (0, 100, 0)), cam, scale, ox, oy)
                col = (220, 30, 30) if fr["phase"] == "ACTIVE" else base
                if prev:
                    d.line([prev, p], fill=col, width=1)
                d.ellipse([p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2], fill=col)
                prev = p
        d.text((ci * cell + 6, 30), VIEWS[[0, 2, 3][ci]][0], fill=(60, 60, 60), font=font(13))
    img.save(out / f"{seq}-trail.png")


def timing(seq, frames, out, label):
    w, h, pad = 1400, 360, 50
    img = Image.new("RGB", (w, h), (255, 255, 255))
    d = ImageDraw.Draw(img)
    t0, t1 = frames[0]["tick"], frames[-1]["tick"]
    sx = lambda t: pad + (t - t0) / (t1 - t0) * (w - 2 * pad)
    bands = {"WINDUP": (255, 240, 200), "ACTIVE": (255, 200, 200), "RECOVERY": (210, 230, 255)}
    for i, fr in enumerate(frames[:-1]):
        if fr["phase"] in bands:
            d.rectangle([sx(fr["tick"]), pad, sx(frames[i + 1]["tick"]), h - pad], fill=bands[fr["phase"]])
    series = []
    for key, col in (("blade", (0, 90, 200)), ("leftBlade", (200, 130, 0))):
        speeds = []
        for a, b in zip(frames, frames[1:]):
            if key in a and key in b:
                dt = (b["tick"] - a["tick"]) / 20
                speeds.append((b["tick"], math.dist(a[key][1], b[key][1]) / 100 / dt))
        if speeds:
            series.append((key, col, speeds))
    top = max((s for _, _, sp in series for _, s in sp), default=1) or 1
    for key, col, speeds in series:
        pts = [(sx(t), h - pad - s / top * (h - 2 * pad)) for t, s in speeds]
        d.line(pts, fill=col, width=2)
    last = None
    for fr in frames:
        if fr["action"] != last:
            d.line([sx(fr["tick"]), pad, sx(fr["tick"]), h - pad], fill=(120, 120, 120))
            d.text((sx(fr["tick"]) + 2, pad - 16), fr["action"].replace("legacy_", "").replace("rapier_", "")[:18], fill=(0, 0, 0), font=font(11))
            last = fr["action"]
    d.text((6, 6), f"{label}  {seq}  blade tip speed (peak {top:.1f} m/s); bands: windup/active/recovery; x = ticks {t0:.0f}-{t1:.0f}", fill=(0, 0, 0), font=font(14))
    img.save(out / f"{seq}-timing.png")


def main():
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
    label = sys.argv[3] if len(sys.argv) > 3 else ""
    for seq, frames in data.items():
        body_top = 43.75 if seq.startswith("dual") else 75
        attack = [f for f in frames if f["phase"] != "READY"]
        every = 2 if len(attack) < 200 else 4
        sheet(seq, [f for f in frames if f["phase"] != "READY" or frames.index(f) % 30 == 0], out, label, body_top, every)
        trail(seq, frames, out, label, body_top)
        timing(seq, frames, out, label)
        print("rendered", seq, len(frames))


if __name__ == "__main__":
    main()
