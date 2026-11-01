#!/usr/bin/env python3
"""Independently audit native render evidence; never modify capture data or PNGs.

Example (run from the project directory):
  python tools/review-render-motion.py --jar build/libs/fsrpg-neoforge-0.3.1.jar
An archived negative control must use --output to avoid replacing current proof.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
from itertools import combinations
import json
import math
from pathlib import Path
import struct
import sys
import zlib

PROJECT = Path(__file__).resolve().parents[1]
ATTACK = {"WINDUP": 0, "ACTIVE": 1, "RECOVERY": 2}
ORDINARY = {*ATTACK, "READY"}
EXPECTED_WEAPONS = {
    "dual-direction-cycle", "spear-cursor-boundary", "spear-point-and-sweep",
    "dual-front-armor", "dual-idle-breath", "dual-omnidirectional-movement",
    "dual-cuts-oblique", "spear-idle-breath", "spear-omnidirectional-movement",
    "spear-thrust-sweep-oblique",
}
EPS = 0.0001
LEG_UNITS = "native-pixels (Y down; groundY=24; 16 pixels/m)"
LEG_EPS = .03  # Native pixels; 0.03 px is 1.875 mm, allowing float/skin roundoff only.


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def safe_json(value):
    """Keep a malformed input's diagnostic writable without accepting non-finite evidence."""
    if isinstance(value, float) and not math.isfinite(value):
        return str(value)
    if isinstance(value, dict):
        return {key: safe_json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [safe_json(item) for item in value]
    return value


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def finite_vector(values, size):
    return isinstance(values, list) and len(values) == size and all(
        isinstance(x, (int, float)) and math.isfinite(x) for x in values)


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def unit(v):
    n = math.sqrt(sum(x * x for x in v))
    if n < 1e-12:
        raise ValueError("zero-length vector/quaternion")
    return [x / n for x in v]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def subtract(a, b):
    return [x - y for x, y in zip(a, b)]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def convex_overlap_depth(first, second):
    """3-D separating axes for the convex hulls of captured section vertices.

    Every possible edge and face is included; extra axes cannot create a false
    intersection. A positive result is a conservative skin-volume candidate,
    because the actual bent surface between section rings may be non-convex.
    """
    for i in range(3):
        if min(max(p[i] for p in first), max(p[i] for p in second)) <= max(min(p[i] for p in first), min(p[i] for p in second)):
            return 0.0
    axes = {}

    def add_axis(raw):
        if dot(raw, raw) < 1e-14:
            return
        normal = unit(raw)
        sign = next((1 if v > 0 else -1 for v in normal if abs(v) > 1e-7), 1)
        normal = [v * sign for v in normal]
        axes.setdefault(tuple(round(v, 7) for v in normal), normal)

    for vertices in (first, second):
        for a, b, c in combinations(vertices, 3):
            add_axis(cross(subtract(b, a), subtract(c, a)))
    edges_first = [subtract(a, b) for a, b in combinations(first, 2)]
    edges_second = [subtract(a, b) for a, b in combinations(second, 2)]
    for a in edges_first:
        for b in edges_second:
            add_axis(cross(a, b))
    depth = math.inf
    for normal in axes.values():
        a, b = [dot(p, normal) for p in first], [dot(p, normal) for p in second]
        overlap = min(max(a), max(b)) - max(min(a), min(b))
        if overlap <= 1e-7:
            return 0.0
        depth = min(depth, overlap)
    return depth if math.isfinite(depth) else 0.0


def sections_intersect(first, second):
    """Intersect two actual planar skin quadrilaterals in 3-D, including coplanar ones."""
    polygons = [[quad[i] for i in (0, 1, 3, 2)] for quad in (first, second)]
    normals = [unit(cross(subtract(p[1], p[0]), subtract(p[2], p[0]))) for p in polygons]
    if abs(dot(normals[0], normals[1])) > .999999:
        if abs(dot(subtract(polygons[1][0], polygons[0][0]), normals[0])) > 1e-4:
            return False
        for polygon in polygons:
            for a, b in zip(polygon, polygon[1:] + polygon[:1]):
                axis = unit(cross(subtract(b, a), normals[0]))
                a_values, b_values = [[dot(p, axis) for p in quad] for quad in polygons]
                if min(max(a_values), max(b_values)) - max(min(a_values), min(b_values)) <= 1e-5:
                    return False
        return True

    def within(point, polygon, normal):
        signs = [dot(cross(subtract(b, a), subtract(point, a)), normal)
                 for a, b in zip(polygon, polygon[1:] + polygon[:1])]
        return min(signs) >= -1e-5 or max(signs) <= 1e-5

    for source, target, normal in ((polygons[0], polygons[1], normals[1]), (polygons[1], polygons[0], normals[0])):
        for a, b in zip(source, source[1:] + source[:1]):
            da, db = dot(subtract(a, target[0]), normal), dot(subtract(b, target[0]), normal)
            if da * db > 0 or abs(da - db) < 1e-10:
                continue
            t = da / (da - db)
            intersection = [a[i] + (b[i] - a[i]) * t for i in range(3)]
            if within(intersection, target, normal):
                return True
    return False


def angle(a, b):
    return math.degrees(2 * math.acos(min(1, abs(dot(unit(a), unit(b))))))


def quaternion_product(a, b):
    x, y, z, w = a
    u, v, s, t = b
    return [w*u + x*t + y*s - z*v, w*v - x*s + y*t + z*u,
            w*s + x*v - y*u + z*t, w*t - x*u - y*v - z*s]


def y_pronation(q):
    return (math.degrees(2 * math.atan2(q[1], q[3])) + 180) % 360 - 180


def without_y_pronation(q, pronation, *, first):
    """Remove Ry from Ry*Rx (palm) or Rx*Ry (forearm), preserving the bend."""
    half = math.radians(pronation) / 2
    inverse = [0, -math.sin(half), 0, math.cos(half)]
    return unit(quaternion_product(inverse, q) if first else quaternion_product(q, inverse))


def off_x_hinge(q):
    return math.degrees(2 * math.acos(min(1, math.hypot(q[0], q[3]))))


def audit_arm_joints(audit, style, local, elbow, where):
    local, elbow = unit(local), unit(elbow)
    palm_pronation = y_pronation(local)
    audit.peak("absoluteHandTwistYDegrees", abs(palm_pronation), **where)
    if style == "rapier":
        # Official fencing grip uses Rx(bend)*Ry(pronation), followed by
        # Ry(palmPronation)*Rx(wristFlex). Reconstruct both residual hinges
        # from captured quaternions; Euler fields or authoring verdicts are not read.
        fore_pronation = y_pronation(elbow)
        elbow_hinge = without_y_pronation(elbow, fore_pronation, first=False)
        palm_hinge = without_y_pronation(local, palm_pronation, first=True)
        elbow_off = off_x_hinge(elbow_hinge)
        palm_off = off_x_hinge(palm_hinge)
        bend = (math.degrees(2 * math.atan2(elbow_hinge[0], elbow_hinge[3])) + 180) % 360 - 180
        audit.peak("rapierForearmPronationDegrees", abs(fore_pronation), **where)
        audit.peak("rapierPalmPronationDegrees", abs(palm_pronation), **where)
        audit.peak("rapierNetPronationDegrees", abs(fore_pronation + palm_pronation), **where)
        audit.peak("rapierElbowFlexionDegrees", abs(bend), **where)
        audit.peak("rapierForearmResidualOffXDegrees", elbow_off, **where)
        audit.peak("rapierPalmResidualOffXDegrees", palm_off, **where)
        audit.require(abs(fore_pronation) <= 60 + EPS, "rapierForearmPronation60Degrees", **where, actual=fore_pronation)
        audit.require(abs(palm_pronation) <= 60 + EPS, "rapierPalmPronation60Degrees", **where, actual=palm_pronation)
        audit.require(abs(fore_pronation + palm_pronation) <= 120 + EPS, "rapierNetPronation120Degrees",
                      **where, actual=fore_pronation + palm_pronation)
        audit.require(abs(bend) <= 140 + EPS, "rapierElbowFlexion140Degrees", **where, actual=bend)
        audit.require(elbow_off <= 1 + EPS, "rapierForearmHingeAfterPronation", **where, actual=elbow_off)
        audit.require(palm_off <= 1 + EPS, "rapierPalmHingeAfterPronation", **where, actual=palm_off)
    elif style == "dual_blade":
        # Dual sword fingers stay closed around the fixed carrier; forearm
        # pronation rolls the cutting plane without introducing a wrist kink.
        fore_pronation = y_pronation(elbow)
        elbow_hinge = without_y_pronation(elbow, fore_pronation, first=False)
        elbow_off = off_x_hinge(elbow_hinge)
        bend = (math.degrees(2 * math.atan2(elbow_hinge[0], elbow_hinge[3])) + 180) % 360 - 180
        palm_neutral_error = angle(local, [0, 0, 0, 1])
        audit.peak("dualForearmPronationDegrees", abs(fore_pronation), **where)
        audit.peak("dualForearmResidualOffXDegrees", elbow_off, **where)
        audit.peak("dualElbowFlexionDegrees", abs(bend), **where)
        audit.peak("dualHandNeutralErrorDegrees", palm_neutral_error, **where)
        audit.require(abs(fore_pronation) <= 35 + EPS, "dualForearmPronation35Degrees", **where, actual=fore_pronation)
        audit.require(elbow_off <= 1 + EPS, "dualForearmHingeAfterPronation", **where, actual=elbow_off)
        audit.require(-140 - EPS <= bend <= EPS, "dualElbowKeepsAnatomicalNegativeXFlexion", **where, actual=bend)
        audit.require(palm_neutral_error <= .1 + EPS, "dualHandRemainsNeutralGrip", **where, actual=palm_neutral_error)
    else:
        off_x = off_x_hinge(elbow)
        audit.peak("forearmOffXHingeDegrees", off_x, **where)
        audit.require(abs(palm_pronation) <= 90 + EPS, "handTwist90Degrees", **where, actual=abs(palm_pronation))
        audit.require(off_x <= 1 + EPS, "forearmPureXHinge", **where, actual=off_x)


def point(matrix, xyz):
    return [matrix[i] * xyz[0] + matrix[i + 4] * xyz[1] + matrix[i + 8] * xyz[2] + matrix[i + 12] for i in range(3)]


def axis(matrix, xyz):
    return unit([matrix[i] * xyz[0] + matrix[i + 4] * xyz[1] + matrix[i + 8] * xyz[2] for i in range(3)])


def animation(frame):
    return frame.get("observation", {}).get("renderAnimation", {})


def presentation(a, b):
    return all(a.get(k) == b.get(k) for k in ("style", "model", "assetIdentity"))


def same_attack(a, b):
    if not presentation(a, b) or a.get("action") != b.get("action"):
        return False
    if a.get("phase") not in ATTACK or b.get("phase") not in ATTACK:
        return False
    same_start = abs(a.get("started", -1) - b.get("started", -2)) < EPS
    # This deliberately does not treat a forward phase's new network revision as a new swing.
    forward = ATTACK[b["phase"]] > ATTACK[a["phase"]]
    return same_start or forward


def png_pixels(path):
    """Validate every chunk and decode pixels, independent of stored PNG file counts."""
    # Pillow unfilters the rows before hashing, so encoding/metadata changes cannot
    # manufacture distinct animation frames. It is available in the workspace runtime.
    from PIL import Image
    raw = path.read_bytes()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    cursor, ended = 8, False
    while cursor + 12 <= len(raw):
        size = struct.unpack_from(">I", raw, cursor)[0]
        kind = raw[cursor + 4:cursor + 8]
        end = cursor + 8 + size
        if end + 4 > len(raw):
            raise ValueError("truncated PNG chunk")
        crc = struct.unpack_from(">I", raw, end)[0]
        if zlib.crc32(raw[cursor + 4:end]) & 0xffffffff != crc:
            raise ValueError("PNG CRC mismatch")
        cursor = end + 4
        if kind == b"IEND":
            ended = True
            break
    if not ended or cursor != len(raw):
        raise ValueError("missing PNG end or trailing bytes")
    with Image.open(path) as image:
        image.load()
        width, height = image.size
        if width <= 0 or height <= 0:
            raise ValueError("empty PNG")
        return (width, height), hashlib.sha256(image.convert("RGBA").tobytes()).hexdigest()


class Audit:
    def __init__(self):
        self.counts = Counter()
        self.failed_counts = Counter()
        self.failures = []
        self.peaks = {}
        self.world_rollback_count = 0
        self.world_rollbacks = []
        self.leg_motion = {}
        self.leg_contacts = {}

    def require(self, condition, check, **where):
        self.counts[check] += 1
        if not condition:
            self.failed_counts[check] += 1
            if self.failed_counts[check] <= 12:
                self.failures.append({"check": check, **where})

    def peak(self, name, value, **where):
        if math.isfinite(value) and value > self.peaks.get(name, {"value": -math.inf})["value"]:
            self.peaks[name] = {"value": value, **where}


def audit_lower_body(audit, observation, where):
    """Audit actual skin/armor vertices, never the unused authored leg matrices."""
    applicable = observation.get("lowerBodyApplicable")
    audit.require(isinstance(applicable, bool), "finalLowerBodyApplicabilityRecorded", **where)
    if applicable is not True:
        return
    lower = observation.get("finalLowerBody")
    if not isinstance(lower, dict):
        audit.require(False, "finalLowerBodyGeometryPresent", **where)
        return
    pelvis, direction = lower.get("pelvis"), lower.get("directionXZ")
    valid = finite_vector(pelvis, 3) and finite_vector(direction, 2) and finite_number(lower.get("phaseRadians"))
    audit.require(valid, "finiteFinalPelvisDirectionPhase", **where)
    audit.require(lower.get("units") == LEG_UNITS and lower.get("groundY") == 24,
                  "finalLegNativePixelCoordinates", **where)
    if not valid:
        return
    audit.require(abs(dot(direction, direction) - 1) < .005, "finalLegDirectionNormalized", **where)
    body = observation.get("rig", {}).get("body")
    audit.require(finite_vector(body, 16), "actualTorsoMatrixForPelvisAlignment", **where)
    if finite_vector(body, 16):
        # FS body origin is the rendered waist, independent of either leg's
        # reported hip. Convert its final world matrix into ModelPart pixels.
        waist = [body[12] / 6.25, (150 - body[13]) / 6.25, -body[14] / 6.25]
        error = math.dist(waist, pelvis)
        audit.peak("finalWaistToPelvisErrorPixels", error, **where)
        audit.require(error <= LEG_EPS, "actualTorsoWaistStaysAttachedToPelvis", **where,
                      errorPixels=error, torsoWaistNativePixels=waist, pelvisNativePixels=pelvis)
        captured_waist = lower.get("waist")
        audit.require(finite_vector(captured_waist, 3) and math.dist(captured_waist, waist) <= LEG_EPS,
                      "capturedWaistMatchesIndependentTorsoMatrix", **where)
    audit.require(finite_vector(observation.get("actorRenderPositionMeters"), 3),
                  "interpolatedActorOriginForWorldFootContact", **where)
    supports = []
    for side, sign in (("right", -1), ("left", 1)):
        leg = lower.get(side, {})
        side_where = {**where, "side": side}
        keys = ("hip", "knee", "ankle", "soleCenter")
        geometry_ok = all(finite_vector(leg.get(key), 3) for key in keys)
        audit.require(geometry_ok, "finiteFinalSkinLegJoints", **side_where)
        support = leg.get("support")
        audit.require(finite_number(support) and support in (0, 1), "finalLegSupportIsBinary", **side_where)
        supports.append(support)
        if not geometry_ok:
            continue
        hip, knee, ankle, sole = (leg[key] for key in keys)
        for field, center in (("kneeCorners", knee), ("ankleCorners", ankle)):
            ring = leg.get(field)
            finite_ring = isinstance(ring, list) and len(ring) == 4 and all(finite_vector(v, 3) for v in ring)
            audit.require(finite_ring, "finiteActualKneeAndAnkleSkinSections", **side_where, section=field)
            if finite_ring:
                midpoint = [sum(p[i] for p in ring) / 4 for i in range(3)]
                lengths = sorted(math.dist(ring[i], ring[j]) for i in range(4) for j in range(i))
                audit.require(math.dist(midpoint, center) <= LEG_EPS and
                              all(abs(x - y) <= LEG_EPS for x, y in zip(lengths, [4] * 4 + [4 * math.sqrt(2)] * 2)),
                              "actualKneeAndAnkleSkinSectionsKeepShape", **side_where, section=field)
        pelvis_yaw = lower.get("pelvisYawRadians",0)
        expected_hip = [pelvis[0] + sign * 1.9*math.cos(pelvis_yaw), pelvis[1], pelvis[2]-sign*1.9*math.sin(pelvis_yaw)]
        hip_error = math.dist(hip, expected_hip)
        audit.peak("finalHipToPelvisErrorPixels", hip_error, **side_where)
        audit.require(hip_error <= LEG_EPS, "hipsStayAttachedToSharedPelvis", **side_where, errorPixels=hip_error)
        native = observation.get("rig", {}).get("native" + side.title() + "Leg")
        audit.require(finite_vector(native, 6), "actualNativeLegParametersRecorded", **side_where)
        if finite_vector(native, 6):
            audit.require(math.dist(hip, native[:3]) <= LEG_EPS, "skinHipMatchesActualModelPart", **side_where)
        for bone, first, last, length in (("thigh", hip, knee, 6), ("shin", knee, sole, 6),
                                           ("kneeToAnkle", knee, ankle, 4.5), ("ankleToSole", ankle, sole, 1.5)):
            error = abs(math.dist(first, last) - length)
            audit.peak("finalLegBoneLengthErrorPixels", error, **side_where, bone=bone)
            audit.require(error <= LEG_EPS, "finalSkinLegBoneLengths", **side_where, bone=bone, errorPixels=error)
        audit.require(sole[1] <= 24 + .04, "soleCenterDoesNotPenetrateFloor", **side_where, groundY=sole[1])
        if support == 1:
            error = abs(sole[1] - 24)
            audit.peak("supportFootGroundErrorPixels", error, **side_where)
            audit.require(error <= .05, "supportFootActuallyTouchesFloor", **side_where, errorPixels=error)
        for field, width in (("soleCorners", 4), ("armorSoleCorners", 4.5)):
            corners = leg.get(field)
            valid_corners = isinstance(corners, list) and len(corners) == 4 and all(finite_vector(p, 3) for p in corners)
            audit.require(valid_corners, "finiteFinalSoleCorners", **side_where, surface=field)
            if not valid_corners:
                continue
            depths = [corner[1] - 24 for corner in corners]
            audit.peak("soleFloorPenetrationPixels", max(0, max(depths)), **side_where, surface=field)
            audit.require(max(depths) <= .04, "allSkinAndArmorSoleCornersAboveFloor", **side_where,
                          surface=field, maximumPenetrationPixels=max(depths))
            center = [sum(p[i] for p in corners) / 4 for i in range(3)]
            audit.require(math.dist(center, sole) <= LEG_EPS, "soleCornersCenteredOnActualFoot", **side_where, surface=field)
            audit.require(max(p[1] for p in corners) - min(p[1] for p in corners) <= LEG_EPS,
                          "finalSolesRemainFlat", **side_where, surface=field)
            distances = sorted(math.dist(corners[i], corners[j]) for i in range(4) for j in range(i))
            expected = [width] * 4 + [width * math.sqrt(2)] * 2
            audit.require(all(abs(a - b) <= LEG_EPS for a, b in zip(distances, expected)),
                          "soleShapePreservesOriginalSkinAndArmorWidth", **side_where, surface=field, distances=distances)
    audit.require(any(value == 1 for value in supports), "groundedPoseHasRealSupportFoot", **where)


def swing_stride_position(phase):
    """Independent Hermite reference: -1 to +1, with -2 tangents at both contacts.

    A lifted foot briefly moves backward at lift-off and before landing so its
    velocity meets the planted foot. Lifted does not imply forward at every sample.
    """
    u = (phase % (2 * math.pi)) / math.pi
    return (2*u**3 - 3*u**2 + 1)*-1 + (u**3 - 2*u**2 + u)*-2 + (-2*u**3 + 3*u**2) + (u**3 - u**2)*-2


def audit_lower_body_pair(audit, p, q, a, b, where):
    """Directional gait is measured from the captured feet, with stance moving backward.

    Attack feet and direction/acceleration transitions may intentionally pivot or plant.
    Only stable READY locomotion is eligible for a directional stride assertion.
    """
    if not (p.get("lowerBodyApplicable") is True and q.get("lowerBodyApplicable") is True
            and a.get("phase") == b.get("phase") == "READY"
            and p.get("reviewBeat") == q.get("reviewBeat")):
        return
    old, new = p.get("finalLowerBody", {}), q.get("finalLowerBody", {})
    old_dir, new_dir = old.get("directionXZ"), new.get("directionXZ")
    if not (finite_vector(old_dir, 2) and finite_vector(new_dir, 2)
            and finite_number(old.get("phaseRadians")) and finite_number(new.get("phaseRadians"))):
        return
    if not (p.get("movingWeight", 0) >= .95 and q.get("movingWeight", 0) >= .95
            and p.get("lowerBodyWeight", 1) <= .05 and q.get("lowerBodyWeight", 1) <= .05
            and min(p.get("walkAnimationSpeed", 0), q.get("walkAnimationSpeed", 0)) > .15
            and abs(p.get("walkAnimationSpeed", 0) - q.get("walkAnimationSpeed", 0)) < .015
            and dot(unit(old_dir), unit(new_dir)) > .9995
            and 0 < new["phaseRadians"] - old["phaseRadians"] < .4):
        return
    native_direction = unit([new_dir[0], -new_dir[1]])
    beat = q.get("reviewBeat", "steady-locomotion")
    by_clip = audit.leg_motion.setdefault(where["clip"], {})
    metric = by_clip.setdefault(beat, {"eligibleFramePairs": 0, "swingPairs": 0, "supportPairs": 0,
                                     "swingTravelPixels": 0.0, "supportTravelPixels": 0.0,
                                     "actualMovementDirectionSamples": 0, "actualMovementDirectionDotSum": 0.0})
    metric["eligibleFramePairs"] += 1
    # The actor's world positions are captured separately from the leg solver. They
    # change at client-tick boundaries, while skin geometry changes every frame.
    if finite_vector(p.get("actorPositionMeters"), 3) and finite_vector(q.get("actorPositionMeters"), 3):
        move = [q["actorPositionMeters"][i] - p["actorPositionMeters"][i] for i in (0, 2)]
        if .001 < math.hypot(*move) < .5:
            yaw = math.radians(q.get("bodyYaw", 0))
            world_direction = [new_dir[0] * math.cos(yaw) - new_dir[1] * math.sin(yaw),
                               new_dir[0] * math.sin(yaw) + new_dir[1] * math.cos(yaw)]
            alignment = dot(unit(move), unit(world_direction))
            metric["actualMovementDirectionSamples"] += 1
            metric["actualMovementDirectionDotSum"] += alignment
    for side in ("right", "left"):
        before, after = old.get(side, {}), new.get(side, {})
        if not (finite_vector(before.get("soleCenter"), 3) and finite_vector(after.get("soleCenter"), 3)
                and before.get("support") == after.get("support") and after.get("support") in (0, 1)):
            continue
        delta = [after["soleCenter"][i] - before["soleCenter"][i] for i in (0, 2)]
        step = dot(delta, native_direction)
        support = after["support"] == 1
        expected_direction = -1
        if not support:
            offset = 0 if side == "right" else math.pi
            expected_delta = swing_stride_position(new["phaseRadians"] + offset) - swing_stride_position(old["phaseRadians"] + offset)
            if abs(expected_delta) < 1e-5:
                continue
            expected_direction = math.copysign(1, expected_delta)
        progress = step * expected_direction
        metric["supportPairs" if support else "swingPairs"] += 1
        metric["supportTravelPixels" if support else "swingTravelPixels"] += max(0, progress)
        audit.peak("footTravelAgainstGaitPhasePixels", max(0, -progress), **where, side=side, support=support)
        audit.require(progress >= -.03, "actualFootTravelFollowsMovementAndSupportPhase", **where,
                      side=side, support=support, signedProgressPixels=progress)
    # Crossing steps are normal. Foot X ordering cannot establish an anatomical
    # collision: recovery can pass in front of, behind, or above the planted foot.


def audit_lower_body_clip(audit, frames, clip_id):
    applicable = sum(f["observation"].get("lowerBodyApplicable") is True for f in frames)
    required = clip_id.endswith(("omnidirectional-movement", "idle-breath")) or clip_id in {
        "actual-input-movement", "sprint-directional-lean", "idle-breathing"}
    if required:
        audit.require(applicable >= 30, "requiredGroundedClipHasFinalSkinGeometry", clip=clip_id, frames=applicable)
    metrics = audit.leg_motion.get(clip_id, {})
    expected = ("forward", "backward", "left", "right", "forward-left", "backward-right", "sprint") if clip_id.endswith("omnidirectional-movement") else ()
    for beat in expected:
        metric = metrics.get(beat, {})
        valid = (metric.get("eligibleFramePairs", 0) >= 6 and metric.get("swingPairs", 0) >= 3
                 and metric.get("supportPairs", 0) >= 3 and metric.get("swingTravelPixels", 0) >= .5
                 and metric.get("supportTravelPixels", 0) >= .5)
        audit.require(valid, "eachMovementDirectionHasActualSupportingAndSwingingFeet", clip=clip_id, beat=beat, measured=metric)
    for beat, metric in metrics.items():
        count = metric["actualMovementDirectionSamples"]
        average = metric["actualMovementDirectionDotSum"] / count if count else None
        metric["meanActualMovementDirectionDot"] = average
        if beat in expected or clip_id in ("actual-input-movement", "sprint-directional-lean"):
            audit.require(count >= 3 and average is not None and average > .7,
                          "gaitDirectionAgreesWithIndependentActorTravel", clip=clip_id, beat=beat, samples=count, meanDot=average)
    if clip_id in ("actual-input-movement", "sprint-directional-lean"):
        audit.require(sum(m["swingTravelPixels"] for m in metrics.values()) >= 1
                      and sum(m["supportTravelPixels"] for m in metrics.values()) >= 1,
                      "rapierNativeMovementHasRealFootStrides", clip=clip_id)
    contacts = lower_body_contact_diagnostics(audit, frames, clip_id)
    return {"groundedFrames": applicable, "motionByBeat": metrics, **contacts}


def lower_body_contact_diagnostics(audit, frames, clip_id):
    """Measure crossed-step clearance and actual world support drift, without a guessed gate."""
    episodes, active = [], {}
    geometry_frames = 0
    collision_candidates = []
    collision_frame_count = 0
    knee_intersections = []
    knee_intersection_count = 0
    minimum_knee_distance = math.inf
    minimum_sole_distance = math.inf
    world_samples = 0

    def finish(side, reason):
        contact = active.pop(side, None)
        if contact is not None:
            contact["endReason"] = reason
            contact["netDisplacementCm"] = math.dist(contact.pop("startWorldXZ"), contact.pop("lastWorldXZ")) * 100
            contact.pop("lastActorXZ", None)
            contact.pop("entityId", None)
            contact.pop("lastSeconds", None)
            episodes.append(contact)
            audit.peak("worldSupportEpisodePathCm", contact["pathCm"], clip=clip_id, side=side,
                       firstFrame=contact["firstFrame"], lastFrame=contact["lastFrame"], durationSeconds=contact["durationSeconds"])
            audit.peak("worldSupportEpisodeNetSlipCm", contact["netDisplacementCm"], clip=clip_id, side=side,
                       firstFrame=contact["firstFrame"], lastFrame=contact["lastFrame"], durationSeconds=contact["durationSeconds"])

    for frame in frames:
        observation = frame.get("observation", {})
        lower = observation.get("finalLowerBody", {})
        if observation.get("lowerBodyApplicable") is not True or not isinstance(lower, dict):
            for side in tuple(active):
                finish(side, "not grounded rendered geometry")
            continue
        right, left = lower.get("right", {}), lower.get("left", {})
        if all(finite_vector(leg.get("knee"), 3) and finite_vector(leg.get("soleCenter"), 3) for leg in (right, left)):
            minimum_knee_distance = min(minimum_knee_distance, math.dist(right["knee"], left["knee"]))
            minimum_sole_distance = min(minimum_sole_distance, math.dist(right["soleCenter"], left["soleCenter"]))
        # Ankle and sole rings are sampled from the deformed skin, not inferred
        # from foot-center X order. A lift or different depth can separate them.
        rings = [leg.get(key) for leg in (right, left) for key in ("ankleCorners", "soleCorners")]
        if all(isinstance(ring, list) and len(ring) == 4 and all(finite_vector(p, 3) for p in ring) for ring in rings):
            geometry_frames += 1
            depth = convex_overlap_depth(rings[0] + rings[1], rings[2] + rings[3])
            audit.peak("footSkinHullOverlapCandidateDepthPixels", depth, clip=clip_id, frame=frame.get("index"), file=frame.get("file"))
            if depth > 1e-5 and len(collision_candidates) < 24:
                collision_candidates.append({"frame": frame.get("index"), "file": frame.get("file"),
                                             "candidateDepthPixels": depth,
                                             "rightSupport": right.get("support"), "leftSupport": left.get("support")})
            if depth > 1e-5:
                collision_frame_count += 1
        knees = [leg.get("kneeCorners") for leg in (right, left)]
        if all(isinstance(ring, list) and len(ring) == 4 and all(finite_vector(p, 3) for p in ring) for ring in knees):
            if sections_intersect(*knees):
                knee_intersection_count += 1
                if len(knee_intersections) < 24:
                    knee_intersections.append({"frame": frame.get("index"), "file": frame.get("file"),
                                               "rightKnee": right.get("knee"), "leftKnee": left.get("knee")})
        actor = observation.get("actorRenderPositionMeters")
        if not finite_vector(actor, 3) or not finite_number(observation.get("bodyYaw")):
            for side in tuple(active):
                finish(side, "interpolated actor origin missing")
            continue
        world_samples += 1
        yaw = math.radians(observation["bodyYaw"])
        for side, leg in (("right", right), ("left", left)):
            sole = leg.get("soleCenter")
            if leg.get("support") != 1 or not finite_vector(sole, 3):
                finish(side, "foot entered swing")
                continue
            # FS+Z is native-Z, followed by the actual entity/body yaw; skin units
            # are 16 pixels/m. Entity render origin must be interpolated at this
            # frame, or 20Hz simulation positions would manufacture 60Hz slip.
            world = [actor[0] + (sole[0] * math.cos(yaw) + sole[2] * math.sin(yaw)) / 16,
                     actor[2] + (sole[0] * math.sin(yaw) - sole[2] * math.cos(yaw)) / 16]
            actor_xz = [actor[0], actor[2]]
            seconds = frame["capturedAtSeconds"]
            old = active.get(side)
            if old is not None and (old["entityId"] != observation.get("entityId")
                                    or not 0 < seconds - old["lastSeconds"] <= .12
                                    or math.dist(old["lastActorXZ"], actor_xz) > .75):
                finish(side, "actor change, teleport or capture gap")
                old = None
            if old is None:
                active[side] = {"side": side, "entityId": observation.get("entityId"),
                                "firstFrame": frame.get("index"), "lastFrame": frame.get("index"),
                                "firstFile": frame.get("file"), "lastFile": frame.get("file"),
                                "beat": observation.get("reviewBeat"), "durationSeconds": 0.0, "pathCm": 0.0,
                                "actorTravelCm": 0.0, "maximumFrameStepCm": 0.0,
                                "startWorldXZ": world, "lastWorldXZ": world, "lastActorXZ": actor_xz, "lastSeconds": seconds}
            else:
                step = math.dist(old["lastWorldXZ"], world) * 100
                old["durationSeconds"] += seconds - old["lastSeconds"]
                old["pathCm"] += step
                old["actorTravelCm"] += math.dist(old["lastActorXZ"], actor_xz) * 100
                old["maximumFrameStepCm"] = max(old["maximumFrameStepCm"], step)
                old.update(lastFrame=frame.get("index"), lastFile=frame.get("file"),
                           lastWorldXZ=world, lastActorXZ=actor_xz, lastSeconds=seconds)
    for side in tuple(active):
        finish(side, "capture boundary")
    return {"footSkinHullGeometryFrames": geometry_frames,
            "footSkinHullOverlapCandidateFrameCount": collision_frame_count,
            "footSkinHullOverlapCandidates": collision_candidates,
            "actualKneeSkinSectionIntersectionFrameCount": knee_intersection_count,
            "actualKneeSkinSectionIntersections": knee_intersections,
            "minimumKneeCenterDistancePixels": minimum_knee_distance if math.isfinite(minimum_knee_distance) else None,
            "minimumSoleCenterDistance3DPixels": minimum_sole_distance if math.isfinite(minimum_sole_distance) else None,
            "interpolatedWorldContactFrames": world_samples, "worldSupportEpisodes": episodes,
            "contactDiagnosticsScope": "3D ankle/sole section convex hulls give conservative overlap candidates, requiring image review; knee intersections use actual planar skin sections in 3D and distances include lift/depth. World support drift uses final skin soles plus interpolated entity origin and actual body yaw. No X-order constraint or arbitrary slip acceptance threshold is applied."}


def audit_pose(audit, observation, where):
    anim = observation.get("renderAnimation", {})
    style = anim.get("style")
    rig = observation.get("rig", {})
    if style not in ("dual_blade", "spear", "rapier"):
        audit.require(False, "recognizedWeaponRig", **where, style=style)
        return
    audit_lower_body(audit, observation, where)
    for key, values in observation.items():
        if key.endswith("Quaternion"):
            valid = finite_vector(values, 4)
            audit.require(valid, "finiteQuaternion", **where, joint=key)
            if valid:
                audit.require(abs(dot(values, values) - 1) < .005, "normalizedQuaternion", **where, joint=key)
    required = ["bodyWorldQuaternion"]
    for side in ("right", "left"):
        required += [side + suffix for suffix in ("UpperArmWorldQuaternion", "ForearmWorldQuaternion", "HandWorldQuaternion", "ForearmLocalQuaternion", "HandLocalQuaternion")]
    for key in required:
        audit.require(finite_vector(observation.get(key), 4), "requiredQuaternionPresent", **where, joint=key)
    for side in ("right", "left"):
        side_where = {**where, "side": side}
        hand, fore = rig.get(side + "_hand"), rig.get(side + "_forearm")
        palm = observation.get(side + "PalmCm")
        if not (finite_vector(hand, 16) and finite_vector(fore, 16) and finite_vector(palm, 3)):
            audit.require(False, "finiteArmMatricesAndPalm", **side_where)
            continue
        audit.require(math.dist(point(hand, [0, -6.25, 0]), palm) < .02, "palmMatchesActualHandMatrix", **side_where)
        hand_down, fore_down = axis(hand, [0, -1, 0]), axis(fore, [0, -1, 0])
        flex = math.degrees(math.acos(max(-1, min(1, dot(hand_down, fore_down)))))
        audit.peak("wristFlexionDegrees", flex, **side_where)
        audit.require(flex <= 26 + EPS, "wristFlexion26Degrees", **side_where, actual=flex)
        local = observation.get(side + "HandLocalQuaternion")
        elbow = observation.get(side + "ForearmLocalQuaternion")
        if finite_vector(local, 4) and finite_vector(elbow, 4):
            audit_arm_joints(audit, style, local, elbow, side_where)
        # Spear's left hand is a front grip, not a second held sword; rapier's offhand is free/shield.
        if side == "right" or style == "dual_blade":
            hilt = observation.get(side + "HiltCm")
            tip = observation.get(side + "CalibratedItemTipCm")
            observed_axis = observation.get(side + "BladeAxis")
            if not all(finite_vector(v, 3) for v in (hilt, tip, observed_axis)):
                audit.require(False, "officialItemReferencePointsPresent", **side_where)
                continue
            # Independent restatement of HeldItemTransform.dualBladeAxis()/rapierBladeAxis():
            # both closed sword grips use 50 degrees; the two-hand spear remains transverse +Z.
            local_axis = [0, -math.cos(math.radians(50)), math.sin(math.radians(50))] if style in ("rapier", "dual_blade") else [0, 0, 1]
            expected_axis = axis(hand, local_axis)
            error = math.dist(hilt, palm)
            alignment = dot(unit([tip[i] - hilt[i] for i in range(3)]), expected_axis)
            audit.peak("officialHiltPalmErrorCm", error, **side_where)
            audit.peak("officialBladeAxisErrorDot", 1 - alignment, **side_where)
            audit.require(error < .1, "officialHiltWithinPalm", **side_where, actual=error)
            audit.require(alignment > .999 and dot(unit(observed_axis), expected_axis) > .999,
                          "officialBladeFollowsActualHandAxis", **side_where, dot=alignment)
            if style == "dual_blade":
                face = observation.get(side + "CalibratedBladeFaceNormal")
                valid_face = finite_vector(face, 3)
                face_alignment = dot(unit(face), axis(hand, [1, 0, 0])) if valid_face else -1
                audit.peak("dualBladeFaceNormalErrorDot", 1 - face_alignment, **side_where)
                audit.require(valid_face and face_alignment > .999, "dualBladeFaceNormalFollowsPalmX",
                              **side_where, dot=face_alignment)
    if style == "spear":
        rear, front, shaft = (observation.get(k) for k in ("rightPalmCm", "leftPalmCm", "rightBladeAxis"))
        if all(finite_vector(v, 3) for v in (rear, front, shaft)):
            shaft = unit(shaft)
            grip = [front[i] - rear[i] for i in range(3)]
            projection = dot(grip, shaft)
            length_error = abs(math.sqrt(dot(grip, grip)) - 58)
            offshaft = math.sqrt(sum((grip[i] - projection * shaft[i]) ** 2 for i in range(3)))
            audit.peak("twoHandGripLengthErrorCm", length_error, **where)
            audit.peak("frontHandOffShaftCm", offshaft, **where)
            audit.require(length_error < .5 and offshaft < .5 and projection > 57.5,
                          "spearRearFrontGrips58cm", **where, lengthErrorCm=length_error, offshaftCm=offshaft, frontProjectionCm=projection)


def audit_pair(audit, before, after, clip_id):
    p, q = before["observation"], after["observation"]
    a, b = animation(before), animation(after)
    dt = after["capturedAtSeconds"] - before["capturedAtSeconds"]
    native = q["sampledTick"] - p["sampledTick"]
    where = {"clip": clip_id, "fromFrame": before["index"], "toFrame": after["index"],
             "fromFile": before["file"], "toFile": after["file"], "wallDeltaSeconds": dt, "sampledTickDelta": native}
    same = presentation(a, b) and p.get("entityId") == q.get("entityId")
    world_delta = after["gameTimeTicks"] - before["gameTimeTicks"]
    if same and world_delta < -EPS:
        audit.world_rollback_count += 1
        if len(audit.world_rollbacks) < 24:
            audit.world_rollbacks.append({**where, "worldTimeDeltaTicks": world_delta,
                                          "renderDeltaTicks": after.get("renderDeltaTicks"),
                                          "presentationClockAdvanced": native > EPS})
    if same and 0 < dt <= .04 and before.get("paused") is False and after.get("paused") is False \
            and before.get("levelRunningNormally") is True and after.get("levelRunningNormally") is True:
        audit.require(native >= -EPS, "presentationClockDoesNotRewind", **where)
        raw_delta = after.get("renderDeltaTicks")
        submissions = q.get("officialMainSubmissions", 0) > p.get("officialMainSubmissions", 0)
        if isinstance(raw_delta, (int, float)) and math.isfinite(raw_delta) and raw_delta > EPS and submissions:
            audit.require(native > EPS, "renderingPresentationClockActuallyAdvances", **where, renderDeltaTicks=raw_delta)
            error = abs(native - raw_delta)
            audit.peak("presentationVsNativeFrameDeltaErrorTicks", error, **where)
            audit.require(error < .0002, "presentationClockTracksNativeFrameDelta", **where, renderDeltaTicks=raw_delta, errorTicks=error)
    short = 0 < dt <= .04 and abs((q.get("bodyYaw", 0) - p.get("bodyYaw", 0) + 180) % 360 - 180) < 5
    if same and short:
        audit_lower_body_pair(audit, p, q, a, b, where)
    ordinary = a.get("phase") in ORDINARY and b.get("phase") in ORDINARY
    if same_attack(a, b):
        old, new = a["sampledElapsedTicks"], b["sampledElapsedTicks"]
        delta = new - old
        backtrack = a["sampledClipSeconds"] - b["sampledClipSeconds"]
        audit.peak("sameAttackClockBacktrackTicks", max(0, -delta), **where)
        audit.peak("sameAttackClipBacktrackSeconds", max(0, backtrack), **where)
        audit.peak("clockExcessAbove125PercentTicks", max(0, delta - 1.25 * max(0, native)), **where)
        audit.require(delta >= -EPS and backtrack <= EPS, "sameAttackClockAndClipMonotonic", **where, elapsed=[old, new])
        audit.require(delta <= 1.25 * max(0, native) + EPS, "sameAttackClockNoPhaseLeap", **where, elapsed=[old, new])
        forward = ATTACK[b["phase"]] > ATTACK[a["phase"]]
        if forward:
            audit.require(abs(a["started"] - b["started"]) < EPS, "forwardPhasePreservesAttackBinding", **where,
                          action=a["action"], phases=[a["phase"], b["phase"]], revisions=[a["sequence"], b["sequence"]],
                          started=[a["started"], b["started"]])
        w, end = a["windupTicks"], a["windupTicks"] + a["activeTicks"]
        # A clamped endpoint can consume only part of the native frame. Interior contact is exactly 1x.
        if native > 0 and old >= w - EPS and new < end - EPS:
            error = abs(delta - native)
            audit.peak("interiorActiveClockRateErrorTicks", error, **where)
            audit.require(error < .0002, "interiorVisualActiveClockExactly1x", **where, elapsed=[old, new], errorTicks=error)
    if not (same and short and ordinary):
        return
    same_phase = a.get("action") == b.get("action") and a.get("phase") == b.get("phase")
    nonactive = a.get("phase") in ("WINDUP", "READY", "RECOVERY")
    for side in ("right", "left"):
        palm_step = math.dist(p[side + "PalmCm"], q[side + "PalmCm"])
        audit.peak("shortOrdinaryPalmStepCm", palm_step, **where, side=side)
        if a.get("style") == "dual_blade":
            audit.require(palm_step < 70, "dualOrdinaryPalmHandoffContinuous", **where, side=side, stepCm=palm_step)
        local_step = angle(p[side + "HandLocalQuaternion"], q[side + "HandLocalQuaternion"])
        audit.peak("shortLocalHandStepDegrees", local_step, **where, side=side)
        audit.require(local_step <= 35 + EPS, "localHandNo35DegreeJump", **where, side=side, angle=local_step)
        for bone in ("UpperArm", "Forearm", "Hand"):
            key = side + bone + "WorldQuaternion"
            value = angle(p[key], q[key])
            audit.peak("shortWorld" + bone + "StepDegrees", value, **where, side=side, phase=b.get("phase"))
            enforce = same_phase and ((a.get("style") == "spear" and (bone == "Hand" or nonactive))
                                     or bone == "Hand" and a.get("phase") == "WINDUP")
            enforce |= bone == "Hand" and a.get("phase") == "RECOVERY" and b.get("phase") == "READY"
            if enforce:
                audit.require(value <= 45 + EPS, "stableNonAttackOrSpearJoint45Degrees", **where, joint=key, angle=value)
    chest = angle(p["bodyWorldQuaternion"], q["bodyWorldQuaternion"])
    audit.peak("shortChestStepDegrees", chest, **where, phase=b.get("phase"))
    if same_phase:
        audit.require(chest <= 45 + EPS, "samePhaseChest45Degrees", **where, angle=chest)


def audit_tails(audit, frames, clip_id):
    strokes, first = [], 0
    while first < len(frames):
        initial, last = animation(frames[first]), first
        while last + 1 < len(frames) and same_attack(animation(frames[last]), animation(frames[last + 1])):
            last += 1
        if initial.get("activeTicks", 0) <= 0:
            first = last + 1
            continue
        w, active = initial["windupTicks"], initial["activeTicks"]
        end = w + active
        samples = [animation(f) for f in frames[first:last + 1]]
        seen = any(w - EPS <= a.get("sampledElapsedTicks", -1) < end - EPS for a in samples)
        if seen:
            max_elapsed = max(a["sampledElapsedTicks"] for a in samples)
            max_seconds = max(a["sampledClipSeconds"] for a in samples)
            clip_end, clip_begin = initial["activeEndSeconds"], initial["activeBeginSeconds"]
            covered = max_elapsed >= end - EPS and max_seconds >= clip_end - EPS
            ordinary, allowed, reason = False, 0, "capture boundary or forced transition"
            if last + 1 < len(frames):
                prev, nxt = animation(frames[last]), animation(frames[last + 1])
                ordinary = (presentation(prev, nxt) and prev.get("actorAlive", True) and nxt.get("actorAlive", True)
                            and nxt.get("drawn", True) and nxt.get("phase") in ORDINARY
                            and prev.get("phase") in ("ACTIVE", "RECOVERY"))
                dt = frames[last + 1]["capturedAtSeconds"] - frames[last]["capturedAtSeconds"]
                native = frames[last + 1]["observation"]["sampledTick"] - frames[last]["observation"]["sampledTick"]
                if 0 < dt <= .04 and 0 < native <= .80001:
                    allowed = native
                if ordinary:
                    covered |= max_elapsed + allowed >= end - EPS and max_seconds + allowed * (clip_end - clip_begin) / active >= clip_end - EPS
                    reason = "ordinary following state"
                elif prev.get("phase") == "WINDUP":
                    reason = "server windup cancellation or dev fixture without phase packets"
            stroke = {"action": initial.get("action"), "firstFrame": first, "lastFrame": last,
                      "lastFile": frames[last]["file"], "activeEndTicks": end, "maximumElapsedTicks": max_elapsed,
                      "maximumClipSeconds": max_seconds, "activeEndClipSeconds": clip_end,
                      "remainingActiveTicks": max(0, end - max_elapsed), "allowedNativeStepTicks": allowed,
                      "ordinaryHandoff": ordinary, "covered": covered, "reason": reason}
            strokes.append(stroke)
            if ordinary:
                audit.require(covered, "completeVisualActiveTailBeforeOrdinaryHandoff", clip=clip_id, **stroke)
        first = last + 1
    if clip_id == "dual-cuts-oblique":
        audit.require(len(strokes) == 8, "allEightDualVisualStrokesObserved", clip=clip_id, observed=len(strokes))
    return strokes


def review(capture_path, game_path, jar_path, suite):
    audit = Audit()
    capture, game = read_json(capture_path), read_json(game_path)
    actual_sha, game_sha = digest(jar_path), game.get("jarSha256", game.get("modJarSha256", "")).lower()
    audit.require(actual_sha == game_sha, "exactJarSha256", actual=actual_sha, captured=game_sha)
    audit.require(capture.get("captureEvent") == "RenderFrameEvent.Post", "actualRenderFrameEvent")
    audit.require("nanoTime" in capture.get("clock", ""), "monotonicNativeCaptureClock")
    audit.require(capture.get("pendingWrites") == 0, "allPngWritesCompleted")
    audit.require(game.get("monitorAdapter") == r"\\.\DISPLAY5", "testWindowOnScreenTwo",
                  monitorAdapter=game.get("monitorAdapter"), monitorBounds=game.get("monitorBounds"),
                  windowX=game.get("windowX"), windowY=game.get("windowY"))
    clips = capture.get("clips", [])
    names = [clip["id"] for clip in clips]
    audit.require(len(names) == len(set(names)), "uniqueClipNames")
    audit.require(set(names) == EXPECTED_WEAPONS if suite == "weapons" else bool(names), "completeCaptureSuite", suite=suite, clips=names)
    results, total_frames = [], 0
    for clip in clips:
        name, frames = clip["id"], clip.get("frames", [])
        total_frames += len(frames)
        audit.require(len(frames) >= 30, "atLeast30NativeFrames", clip=name, count=len(frames))
        audit.require(len(frames) == clip.get("capturedFrames") == clip.get("writtenFrames"), "reportedFrameCountsMatchRawFrames", clip=name)
        hashes, sizes, changed_subtick = set(), set(), 0
        files = set()
        for i, frame in enumerate(frames):
            where = {"clip": name, "frame": i, "file": frame.get("file")}
            try:
                audit.require(frame.get("index") == i, "consecutiveFrameIndices", **where)
                raw_delta = frame.get("renderDeltaTicks")
                audit.require(isinstance(raw_delta, (int, float)) and math.isfinite(raw_delta) and raw_delta >= 0,
                              "nativeRenderDeltaRecorded", **where, renderDeltaTicks=raw_delta)
                audit.require(isinstance(frame.get("paused"), bool) and isinstance(frame.get("levelRunningNormally"), bool),
                              "nativePauseAndTickFreezeRecorded", **where)
                path = (capture_path.parent / frame["file"]).resolve()
                audit.require(path.is_relative_to(capture_path.parent.resolve()), "pngRemainsWithinCaptureDirectory", **where)
                if not path.is_relative_to(capture_path.parent.resolve()):
                    continue
                files.add(path)
                size, pixel_hash = png_pixels(path)
                sizes.add(size); hashes.add(pixel_hash)
                observation = frame["observation"]
                audit_pose(audit, observation, where)
                if i:
                    old = frames[i - 1]
                    audit.require(frame["capturedAtSeconds"] > old["capturedAtSeconds"] and frame["renderFrame"] > old["renderFrame"], "strictRealFrameTimestamps", **where)
                    audit_pair(audit, old, frame, name)
                    if math.floor(old["gameTimeTicks"]) == math.floor(frame["gameTimeTicks"]) and old["observation"].get("rig") != observation.get("rig"):
                        changed_subtick += 1
            except (KeyError, ValueError, TypeError, OSError, ZeroDivisionError) as error:
                audit.require(False, "validFrameDataAndPng", **where, error=str(error))
        seconds = frames[-1]["capturedAtSeconds"] - frames[0]["capturedAtSeconds"] if len(frames) > 1 else 0
        fps = (len(frames) - 1) / seconds if seconds > 0 else 0
        audit.require(fps >= 30, "actualTimestampFpsAtLeast30", clip=name, fps=fps)
        audit.require(abs(fps - clip.get("sourceFps", 0)) < .0001, "recomputedFpsMatchesMetadata", clip=name, fps=fps)
        audit.require(len(files) == len(frames) and len(sizes) == 1, "oneNativePngPerFrameSameDimensions", clip=name)
        audit.require(len(hashes) >= 30, "atLeast30DistinctDecodedPixelFrames", clip=name, uniquePixels=len(hashes))
        audit.require(changed_subtick > 0, "realSubtickRigMovement", clip=name, changedSubtickPairs=changed_subtick)
        submissions = [f["observation"].get("officialMainSubmissions", 0) for f in frames]
        audit.require(bool(submissions) and max(submissions) > min(submissions), "actualOfficialItemSubmissionsAdvance", clip=name)
        tails = audit_tails(audit, frames, name)
        lower_body = audit_lower_body_clip(audit, frames, name)
        results.append({"id": name, "frames": len(frames), "sourceFps": fps, "dimensions": sorted(sizes),
                        "distinctDecodedPixelFrames": len(hashes), "changedSubtickRigPairs": changed_subtick,
                        "maximumFrameGapSeconds": max((b["capturedAtSeconds"] - a["capturedAtSeconds"] for a, b in zip(frames, frames[1:])), default=0),
                        "activeStrokes": tails, "finalLowerBody": lower_body})
    return {"schemaVersion": 1, "reviewedAtUtc": datetime.now(timezone.utc).isoformat(),
            "review": "Independent native PNG, timestamp, matrix, quaternion, final skin/armor legs, clock and active-tail audit",
            "sourceDataModified": False, "motionReviewRead": False,
            "capturePath": str(capture_path.resolve()), "captureSha256": digest(capture_path),
            "jarPath": str(jar_path.resolve()), "jarSha256": actual_sha, "gameReportJarSha256": game_sha,
            "jarIdentityMatchesGameReport": actual_sha == game_sha, "gameReportPassed": game.get("passed"),
            "monitorAdapter": game.get("monitorAdapter"), "monitorBounds": game.get("monitorBounds"),
            "suite": suite, "captureEvent": capture.get("captureEvent"), "totalActualRenderedFrames": total_frames,
            "checkCount": sum(audit.counts.values()), "checksByCategory": dict(audit.counts),
            "failureCountsByCategory": dict(audit.failed_counts), "peaks": audit.peaks, "clips": results,
            "worldTimeRollbackCount": audit.world_rollback_count, "worldTimeRollbacks": audit.world_rollbacks,
            "thresholds": {"sourceFpsMinimum": 30, "wristFlexionDegrees": 26, "handTwistDegrees": 90,
                           "rapierForearmPronationDegrees": 60, "rapierPalmPronationDegrees": 60,
                           "rapierNetPronationDegrees": 120, "rapierElbowFlexionDegrees": 140,
                           "dualForearmPronationDegrees": 35, "dualElbowFlexionDegrees": 140,
                           "dualNeutralHandErrorDegrees": .1,
                           "residualHingeToleranceDegrees": 1,
                           "shortFrameSeconds": .04, "stableActorYawDeltaDegrees": 5, "jointStepDegrees": 45,
                           "localHandStepDegrees": 35, "hiltErrorCm": .1, "spearGripCm": 58,
                           "spearGripToleranceCm": .5, "dualPalmStepCm": 70,
                           "legGeometryErrorPixels": LEG_EPS, "solePenetrationPixels": .04,
                           "supportFootGroundErrorPixels": .05, "legSegmentPixels": 6,
                           "hipSpacingPixels": 3.8, "nativeGroundY": 24,
                           "minimumDirectionalFootTravelPixels": .5, "minimumActorGaitDirectionDot": .7},
            "scope": "Fast ACTIVE upper-arm arcs are diagnostic unless an existing spear-hand/chest gate applies. Forced transitions and capture boundaries are excluded from normal active-tail handoffs. PNG pixels and raw pose/clock values are recomputed; motion-review verdicts are never read.",
            "passed": not audit.failed_counts, "failures": audit.failures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, default=PROJECT / "verification/weapons-r2/render-capture.json")
    parser.add_argument("--game", type=Path)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--suite", choices=("weapons", "rapier"), default="weapons")
    args = parser.parse_args()
    game = args.game or args.capture.parent / "game-smoke.json"
    output = args.output or args.capture.parent / "independent-motion-review.json"
    version = next(line.split("=", 1)[1].strip() for line in (PROJECT / "gradle.properties").read_text().splitlines() if line.startswith("mod_version="))
    jar = args.jar or PROJECT / "build/libs" / f"fsrpg-neoforge-{version}.jar"
    try:
        report = review(args.capture.resolve(), game.resolve(), jar.resolve(), args.suite)
    except Exception as error:
        report = {"passed": False, "failures": [{"check": "auditExecution", "error": str(error)}], "sourceDataModified": False}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(safe_json(report), ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(output)
    print(json.dumps({"passed": report["passed"], "output": str(output), "frames": report.get("totalActualRenderedFrames"),
                      "failureCounts": report.get("failureCountsByCategory", report["failures"])}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
