"""Mutation controls for the independent final-skin-leg audit (no Minecraft/Gradle needed)."""
import copy
import importlib.util
import math
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("review_render_motion", Path(__file__).with_name("review-render-motion.py"))
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


def standing():
    observation = {"lowerBodyApplicable": True, "actorRenderPositionMeters": [0, 80, 0],
                   "rig": {"body": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 150 - 13.2 * 6.25, 0, 1]}}
    lower = {"units": review.LEG_UNITS, "groundY": 24, "pelvis": [0, 13.2, 0], "waist": [0, 13.2, 0],
             "phaseRadians": .3, "directionXZ": [0, 1]}
    for side, sign in (("right", -1), ("left", 1)):
        hip = [sign * 1.9, 13.2, 0]
        knee = [sign * 1.9, 18.6, math.sqrt(36 - 5.4 ** 2)]
        sole = [sign * 1.9, 24, 0]
        ankle = [knee[i] + .75 * (sole[i] - knee[i]) for i in range(3)]
        lower[side] = {"hip": hip, "knee": knee, "ankle": ankle, "soleCenter": sole, "support": 1,
                       "kneeCorners": [[knee[0] + x * 2, knee[1], knee[2] + z * 2] for x in (-1, 1) for z in (-1, 1)],
                       "ankleCorners": [[ankle[0] + x * 2, ankle[1], ankle[2] + z * 2] for x in (-1, 1) for z in (-1, 1)],
                       "soleCorners": [[sole[0] + x * 2, 24, z * 2] for x in (-1, 1) for z in (-1, 1)],
                       "armorSoleCorners": [[sole[0] + x * 2.25, 24, z * 2.25] for x in (-1, 1) for z in (-1, 1)]}
        observation["rig"]["native" + side.title() + "Leg"] = hip + [0, 0, 0]
    observation["finalLowerBody"] = lower
    return observation


def stride():
    p, q = standing(), standing()
    for observation in (p, q):
        observation.update(movingWeight=1, lowerBodyWeight=0, walkAnimationSpeed=.84,
                           bodyYaw=0, reviewBeat="forward", actorPositionMeters=[0, 80, 0])
        observation["finalLowerBody"]["right"]["support"] = 0
    q["actorPositionMeters"] = [0, 80, .02]
    q["finalLowerBody"]["phaseRadians"] = .32
    p["finalLowerBody"]["right"]["soleCenter"][2] = .1
    q["finalLowerBody"]["right"]["soleCenter"][2] = -.1
    p["finalLowerBody"]["left"]["soleCenter"][2] = -.1
    q["finalLowerBody"]["left"]["soleCenter"][2] = .1
    return p, q


class LowerBodyAuditTest(unittest.TestCase):
    def geometry(self, observation):
        audit = review.Audit()
        review.audit_lower_body(audit, observation, {"clip": "control", "frame": 0})
        return audit

    def pair(self, p, q, repeat=1):
        audit = review.Audit()
        for i in range(repeat):
            review.audit_lower_body_pair(audit, p, q, {"phase": "READY"}, {"phase": "READY"},
                                         {"clip": "actual-input-movement", "fromFrame": i, "toFrame": i + 1})
        return audit

    def test_analytic_bent_legs_preserve_skin_armor_and_contact(self):
        audit = self.geometry(standing())
        self.assertFalse(audit.failed_counts, audit.failures)
        self.assertGreater(sum(audit.counts.values()), 35)

    def test_missing_final_geometry_is_not_old_source_leg_fallback(self):
        source_only = {"rig": {"right_leg": [1] * 16, "left_leg": [1] * 16}}
        self.assertIn("finalLowerBodyApplicabilityRecorded", self.geometry(source_only).failed_counts)
        source_only["lowerBodyApplicable"] = True
        self.assertIn("finalLowerBodyGeometryPresent", self.geometry(source_only).failed_counts)

    def test_airborne_explicit_exemption_is_supported(self):
        self.assertFalse(self.geometry({"lowerBodyApplicable": False}).failed_counts)

    def test_nonfinite_real_joint_is_rejected(self):
        observation = standing()
        observation["finalLowerBody"]["right"]["knee"][2] = float("nan")
        self.assertIn("finiteFinalSkinLegJoints", self.geometry(observation).failed_counts)

    def test_rubber_shin_is_rejected(self):
        observation = standing()
        observation["finalLowerBody"]["left"]["knee"][2] += 1
        self.assertIn("finalSkinLegBoneLengths", self.geometry(observation).failed_counts)

    def test_detached_hip_even_if_modelpart_reports_it_is_rejected(self):
        observation = standing()
        observation["finalLowerBody"]["right"]["hip"][0] -= 1
        observation["rig"]["nativeRightLeg"][0] -= 1
        self.assertIn("hipsStayAttachedToSharedPelvis", self.geometry(observation).failed_counts)

    def test_modelpart_disagrees_with_skin_joints(self):
        observation = standing()
        observation["rig"]["nativeLeftLeg"][2] += 1
        self.assertIn("skinHipMatchesActualModelPart", self.geometry(observation).failed_counts)

    def test_averaging_two_detached_hips_cannot_fake_waist_attachment(self):
        observation = standing()
        observation["finalLowerBody"]["pelvis"][0] += 1
        for side in ("left", "right"):
            observation["finalLowerBody"][side]["hip"][0] += 1
            observation["rig"]["native" + side.title() + "Leg"][0] += 1
        audit = self.geometry(observation)
        self.assertNotIn("hipsStayAttachedToSharedPelvis", audit.failed_counts)
        self.assertIn("actualTorsoWaistStaysAttachedToPelvis", audit.failed_counts)

    def test_armor_inflation_must_not_bury_boot(self):
        observation = standing()
        for point in observation["finalLowerBody"]["left"]["armorSoleCorners"]:
            point[1] += .25
        self.assertIn("allSkinAndArmorSoleCornersAboveFloor", self.geometry(observation).failed_counts)

    def test_visually_twisted_sole_is_rejected(self):
        observation = standing()
        observation["finalLowerBody"]["right"]["soleCorners"][0][1] -= .2
        self.assertIn("finalSolesRemainFlat", self.geometry(observation).failed_counts)

    def test_narrowed_boot_is_rejected(self):
        observation = standing()
        for point in observation["finalLowerBody"]["right"]["armorSoleCorners"]:
            point[0] = -1.9 + (point[0] + 1.9) * .8
        self.assertIn("soleShapePreservesOriginalSkinAndArmorWidth", self.geometry(observation).failed_counts)

    def test_hovering_support_flag_is_not_accepted(self):
        observation = standing()
        observation["finalLowerBody"]["right"]["soleCenter"][1] -= .2
        self.assertIn("supportFootActuallyTouchesFloor", self.geometry(observation).failed_counts)
        observation = standing()
        for side in ("left", "right"):
            observation["finalLowerBody"][side]["support"] = 0
        self.assertIn("groundedPoseHasRealSupportFoot", self.geometry(observation).failed_counts)

    def test_actual_stride_moves_swing_forward_and_stance_backward(self):
        audit = self.pair(*stride(), repeat=12)
        review.audit_lower_body_clip(audit, [], "actual-input-movement")
        self.assertNotIn("actualFootTravelFollowsMovementAndSupportPhase", audit.failed_counts)
        self.assertNotIn("rapierNativeMovementHasRealFootStrides", audit.failed_counts)
        self.assertNotIn("gaitDirectionAgreesWithIndependentActorTravel", audit.failed_counts)

    def test_reversed_moonwalk_is_rejected(self):
        p, q = stride()
        for side in ("left", "right"):
            q["finalLowerBody"][side]["soleCenter"][2] *= -2
        self.assertIn("actualFootTravelFollowsMovementAndSupportPhase", self.pair(p, q).failed_counts)

    def test_frozen_feet_cannot_pass_coverage(self):
        p, q = stride()
        for side in ("left", "right"):
            q["finalLowerBody"][side]["soleCenter"] = copy.deepcopy(p["finalLowerBody"][side]["soleCenter"])
        audit = self.pair(p, q, repeat=12)
        review.audit_lower_body_clip(audit, [], "actual-input-movement")
        self.assertIn("rapierNativeMovementHasRealFootStrides", audit.failed_counts)

    def test_gait_metadata_opposite_real_actor_movement_is_rejected(self):
        p, q = stride()
        q["actorPositionMeters"][2] *= -1
        audit = self.pair(p, q, repeat=12)
        review.audit_lower_body_clip(audit, [], "actual-input-movement")
        self.assertIn("gaitDirectionAgreesWithIndependentActorTravel", audit.failed_counts)

    def test_body_yaw_is_applied_when_comparing_world_actor_travel(self):
        p, q = stride()
        p["bodyYaw"] = q["bodyYaw"] = 90
        q["actorPositionMeters"] = [-.02, 80, 0]
        audit = self.pair(p, q, repeat=12)
        review.audit_lower_body_clip(audit, [], "actual-input-movement")
        self.assertNotIn("gaitDirectionAgreesWithIndependentActorTravel", audit.failed_counts)


class ContactGeometryTest(unittest.TestCase):
    @staticmethod
    def box(x=0, z=0, floor=24):
        return [[x + a, y, z + b] for y in (floor - 1.5, floor) for a in (-2, 2) for b in (-2, 2)]

    def test_real_3d_overlap_and_separate_forward_lane(self):
        self.assertAlmostEqual(review.convex_overlap_depth(self.box(), self.box(x=3)), 1)
        self.assertEqual(review.convex_overlap_depth(self.box(), self.box(x=1, z=5)), 0)

    def test_lifted_cross_step_can_pass_above_the_support_foot(self):
        self.assertEqual(review.convex_overlap_depth(self.box(), self.box(x=1, floor=22.5)), 0)

    def test_knee_sections_intersect_in_3d_but_separate_with_depth(self):
        first = [[x, 0, z] for x in (-2, 2) for z in (-2, 2)]
        coplanar = [[x + 3, y, z] for x, y, z in first]
        crossing = [[x, z, 0] for x, _, z in first]
        separate = [[x, y, z + 5] for x, y, z in crossing]
        self.assertTrue(review.sections_intersect(first, coplanar))
        self.assertTrue(review.sections_intersect(first, crossing))
        self.assertFalse(review.sections_intersect(first, separate))

    def test_parallel_knee_sections_at_different_heights_do_not_intersect(self):
        first = [[x, 0, z] for x in (-2, 2) for z in (-2, 2)]
        raised = [[x, y - 1, z] for x, y, z in first]
        self.assertFalse(review.sections_intersect(first, raised))

    def test_rotated_disjoint_hulls_with_overlapping_aabbs(self):
        def rotate(points):
            h = math.sqrt(.5)
            return [[(p[0] + p[2]) * h, p[1], (-p[0] + p[2]) * h] for p in points]
        first, second = rotate(self.box()), rotate(self.box(x=4.2))
        for i in range(3):
            self.assertLess(max(min(p[i] for p in first), min(p[i] for p in second)),
                            min(max(p[i] for p in first), max(p[i] for p in second)))
        self.assertEqual(review.convex_overlap_depth(first, second), 0)

    @staticmethod
    def world_frames(planted=True, yaw=0):
        frames = []
        for i in range(10):
            observation = standing()
            observation.update(entityId="actor", bodyYaw=yaw, actorPositionMeters=[999, 80, 999],
                               actorRenderPositionMeters=[-i * .01 if yaw == 90 else 0, 80, i * .01 if yaw == 0 else 0])
            if planted:
                for side in ("left", "right"):
                    observation["finalLowerBody"][side]["soleCenter"][2] += i * .16
            frames.append({"index": i, "file": str(i) + ".png", "capturedAtSeconds": i / 60, "observation": observation})
        return frames

    def test_world_planted_contact_does_not_use_uninterpolated_entity_position(self):
        report = review.lower_body_contact_diagnostics(review.Audit(), self.world_frames(), "control")
        self.assertEqual(report["interpolatedWorldContactFrames"], 10)
        self.assertEqual(len(report["worldSupportEpisodes"]), 2)
        for contact in report["worldSupportEpisodes"]:
            self.assertLess(contact["pathCm"], 1e-10)
            self.assertLess(contact["netDisplacementCm"], 1e-10)
            self.assertAlmostEqual(contact["actorTravelCm"], 9)

    def test_stuck_body_local_feet_report_actual_sustained_world_slip(self):
        report = review.lower_body_contact_diagnostics(review.Audit(), self.world_frames(planted=False), "control")
        for contact in report["worldSupportEpisodes"]:
            self.assertAlmostEqual(contact["pathCm"], 9)
            self.assertAlmostEqual(contact["durationSeconds"], .15)
            self.assertAlmostEqual(contact["netDisplacementCm"], 9)

    def test_world_contact_uses_actual_yaw(self):
        report = review.lower_body_contact_diagnostics(review.Audit(), self.world_frames(yaw=90), "control")
        for contact in report["worldSupportEpisodes"]:
            self.assertLess(contact["pathCm"], 1e-10)


if __name__ == "__main__":
    unittest.main(verbosity=2)
