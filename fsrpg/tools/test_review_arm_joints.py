"""Independent quaternion mutation controls for the three distinct weapon elbow rules."""
import importlib.util
import math
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("review_render_motion", Path(__file__).with_name("review-render-motion.py"))
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


def rotation(axis, degrees):
    q = [0., 0., 0., math.cos(math.radians(degrees) / 2)]
    q[axis] = math.sin(math.radians(degrees) / 2)
    return q


class ArmAuditTest(unittest.TestCase):
    def audit(self, style, forearm, hand=None):
        audit = review.Audit()
        review.audit_arm_joints(audit, style, hand or [0, 0, 0, 1], forearm, {"side": "right"})
        return audit

    def test_dual_negative_hinge_and_bounded_forearm_pronation_grid(self):
        for bend in (0, -5, -26, -60, -90, -110, -140):
            for pronation in (-35, -30, -25, -1, 0, 1, 25, 30, 35):
                q = review.quaternion_product(rotation(0, bend), rotation(1, pronation))
                for sign in (-1, 1):
                    with self.subTest(bend=bend, pronation=pronation, quaternionSign=sign):
                        audit = self.audit("dual_blade", [sign * v for v in q])
                        self.assertFalse(audit.failed_counts, audit.failures)

    def test_positive_backwards_dual_elbow_rejected(self):
        self.assertIn("dualElbowKeepsAnatomicalNegativeXFlexion", self.audit("dual_blade", rotation(0, 15)).failed_counts)

    def test_excessive_dual_pronation_rejected(self):
        q = review.quaternion_product(rotation(0, -60), rotation(1, 36))
        self.assertIn("dualForearmPronation35Degrees", self.audit("dual_blade", q).failed_counts)

    def test_forearm_lateral_elbow_twist_cannot_hide_inside_pronation(self):
        q = review.quaternion_product(review.quaternion_product(rotation(0, -60), rotation(1, 25)), rotation(2, 5))
        self.assertIn("dualForearmHingeAfterPronation", self.audit("dual_blade", q).failed_counts)

    def test_dual_wrist_flex_and_palm_twist_both_remain_neutral(self):
        for axis in (0, 1, 2):
            audit = self.audit("dual_blade", rotation(0, -60), rotation(axis, 2))
            self.assertIn("dualHandRemainsNeutralGrip", audit.failed_counts)

    def test_spear_still_requires_pure_x_forearm(self):
        self.assertFalse(self.audit("spear", rotation(0, -70)).failed_counts)
        q = review.quaternion_product(rotation(0, -70), rotation(1, 5))
        self.assertIn("forearmPureXHinge", self.audit("spear", q).failed_counts)

    def test_rapier_keeps_its_separate_bounded_anatomy(self):
        forearm = review.quaternion_product(rotation(0, 100), rotation(1, 60))
        hand = review.quaternion_product(rotation(1, 60), rotation(0, 26))
        audit = self.audit("rapier", forearm, hand)
        self.assertFalse(audit.failed_counts, audit.failures)


if __name__ == "__main__":
    unittest.main(verbosity=2)
