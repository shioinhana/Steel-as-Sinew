"""Negative controls for actual legacy rapier item-path and wrist-cone evidence."""
import copy
import importlib.util
import math
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("rapier_flow_review", Path(__file__).with_name("review-rapier-flow.py"))
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)


def matrix(x=0, y=0):
    x, y = math.radians(x), math.radians(y)
    cx, sx, cy, sy = math.cos(x), math.sin(x), math.cos(y), math.sin(y)
    return [cy, sx * sy, -cx * sy, 0, 0, cx, sx, 0, sy, -sx * cy, cx * cy, 0, 0, 0, 0, 1]


def native_pose():
    pose = {"lowerBodyApplicable": False, "rig": {}, "renderAnimation": {"clipTags": ["rapier_fencing:0100"]},
            "rightActualOfficialBladeAxis": [0, -1, 0]}
    for side in ("right", "left"):
        for bone in ("_arm", "_forearm", "_hand"):
            pose["rig"][side + bone] = matrix()
        for name in ("UpperArmWorldQuaternion", "ForearmWorldQuaternion", "HandWorldQuaternion", "ForearmLocalQuaternion", "HandLocalQuaternion"):
            pose[side + name] = [0, 0, 0, 1]
    pose["rig"]["right_item"] = matrix(90, 3.012787504)
    return pose


class RapierFlowReviewTest(unittest.TestCase):
    def gait_pair(self, phase, step, support=0):
        def sample(t, z):
            return {"lowerBodyApplicable": True, "reviewBeat": "steady", "movingWeight": 1,
                    "lowerBodyWeight": 0, "walkAnimationSpeed": .3,
                    "finalLowerBody": {"directionXZ": [0,-1], "phaseRadians": t,
                        "right": {"soleCenter": [0,24,z], "support": support}}}
        audit=flow.native.Audit()
        flow.native.audit_lower_body_pair(audit,sample(phase,0),sample(phase+.1,step),
                                         {"phase":"READY"},{"phase":"READY"},{"clip":"test"})
        return audit

    def test_middle_swing_cannot_slide_backwards(self):
        self.assertIn("actualFootTravelFollowsMovementAndSupportPhase", self.gait_pair(math.pi*.5,-.2).failed_counts)
        self.assertFalse(self.gait_pair(math.pi*.5,.2).failed_counts)

    def test_landing_swing_matches_backward_contact_velocity(self):
        self.assertFalse(self.gait_pair(math.pi*.94,-.2).failed_counts)
        self.assertIn("actualFootTravelFollowsMovementAndSupportPhase", self.gait_pair(math.pi*.94,.2).failed_counts)

    def test_support_cannot_slide_forward(self):
        self.assertFalse(self.gait_pair(math.pi*1.4,-.2,1).failed_counts)
        self.assertIn("actualFootTravelFollowsMovementAndSupportPhase", self.gait_pair(math.pi*1.4,.2,1).failed_counts)

    def audit(self, pose):
        audit = flow.native.Audit()
        flow.audit_rapier_pose(audit, pose, {"frame": 0})
        return audit

    def test_actual_legacy_mount_aligns_sprite_with_hand(self):
        audit = self.audit(native_pose())
        self.assertFalse(audit.failed_counts, audit.failures)

    def test_old_fifty_degree_carrier_is_rejected_as_evidence(self):
        pose = native_pose()
        pose["rightActualOfficialBladeAxis"] = [0, -math.cos(math.radians(50)), math.sin(math.radians(50))]
        self.assertIn("actualLegacyOfficialBladeAxis", self.audit(pose).failed_counts)

    def test_detached_item_cannot_pass_using_hand_proximity_field(self):
        pose = native_pose()
        pose["rightOfficialLocatorHandErrorCm"] = 0
        pose["rig"]["right_item"][12] = 2
        self.assertIn("actualOfficialLocatorAttachedToHand", self.audit(pose).failed_counts)

    def test_wrist_beyond_authored_cone_is_rejected(self):
        pose = native_pose()
        pose["rig"]["left_hand"] = matrix(45)
        self.assertIn("actualWristWithin34DegreeCone", self.audit(pose).failed_counts)

    def test_fp_observation_does_not_fabricate_avatar(self):
        self.assertIsNone(flow.avatar_of({"firstPersonAction": "legacy_rapier_attack1_upper"}))

    def test_real_branch_is_checked_without_counting_as_a_main_phrase(self):
        audit = flow.native.Audit()
        self.assertFalse(flow.audit_phrase(audit, "charged-thrust", {"frame": 0}))
        self.assertFalse(audit.failed_counts)
        flow.audit_phrase(audit, "unknown-thrust", {"frame": 1})
        self.assertIn("currentReauthoredPhrase", audit.failed_counts)

    def test_first_person_stalled_drawn_hands_are_rejected(self):
        audit = flow.native.Audit()
        before = {"drawn": True, "firstPersonNativeSamples": 8, "firstPersonOfficialItemSubmissions": 8}
        flow.audit_first_person_pair(audit, before, before, {"frame": 1})
        self.assertIn("firstPersonRendererContinuesEveryDrawnFrame", audit.failed_counts)
        self.assertIn("firstPersonItemContinuesEveryDrawnFrame", audit.failed_counts)

    def test_drawing_fixture_may_use_vanilla_hands(self):
        audit = flow.native.Audit()
        state = {"drawn": False, "phase": "DRAWING", "firstPersonNativeSamples": 8, "firstPersonOfficialItemSubmissions": 8}
        flow.audit_first_person_pair(audit, state, state, {"frame": 1})
        self.assertFalse(audit.failed_counts)

    def test_avatar_extraction_preserves_raw_evidence(self):
        raw = {"submittedAvatar": {"lowerBodyApplicable": True}, "rig": {"body": matrix()}, "scenario": "start-stop"}
        before = copy.deepcopy(raw)
        self.assertEqual(flow.avatar_of(raw)["reviewBeat"], "start-stop")
        self.assertEqual(raw, before)


if __name__ == "__main__":
    unittest.main()
