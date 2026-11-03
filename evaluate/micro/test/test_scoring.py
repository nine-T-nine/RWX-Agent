import sys
from pathlib import Path
import unittest

MICRO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MICRO))
from common.scoring import curve, delay_buckets, document, piecewise, split_outliers  # noqa: E402


class CurveTests(unittest.TestCase):
    def test_linear_endpoints_and_outer_clamp(self):
        self.assertAlmostEqual(curve(0, 0, 10, 0, 100), 0)
        self.assertAlmostEqual(curve(10, 0, 10, 0, 100), 100)
        self.assertAlmostEqual(curve(5, 0, 10, 0, 100), 50)
        self.assertAlmostEqual(curve(-5, 0, 10, 0, 100), 0)
        self.assertAlmostEqual(curve(15, 0, 10, 0, 100), 100)

    def test_exp_keeps_natural_shape_between_endpoints(self):
        self.assertAlmostEqual(curve(15, 15, 60, 0, 100, "exp"), 0)
        self.assertAlmostEqual(curve(60, 15, 60, 0, 100, "exp"), 100)
        mid = curve(37.5, 15, 60, 0, 100, "exp")
        self.assertGreater(mid, 0)
        self.assertLess(mid, 50)  # convex: far below the linear midpoint

    def test_log_is_concave(self):
        self.assertAlmostEqual(curve(1, 1, 10, 0, 10, "log"), 0)
        self.assertAlmostEqual(curve(10, 1, 10, 0, 10, "log"), 10)
        self.assertGreater(curve(5, 1, 10, 0, 10, "log"), 5)

    def test_quadratic_uses_given_a_at_original_coordinates(self):
        self.assertAlmostEqual(curve(0, 0, 10, 0, 30, "quadratic", -3), 0)
        self.assertAlmostEqual(curve(10, 0, 10, 0, 30, "quadratic", -3), 30)
        # a=-3 overshoots the upper endpoint in the middle; the output is clamped, never negative
        self.assertLessEqual(curve(5, 0, 10, 0, 30, "quadratic", -3), 30)
        self.assertGreaterEqual(curve(0.1, 0, 10, 0, 30, "quadratic", -3), 0)

    def test_piecewise_edges_and_tail(self):
        segments = ((3.0, 25.0, 0.0, 0.8), (25.0, 40.0, 0.8, 1.0))
        self.assertEqual(piecewise(1.0, segments), 0.0)
        self.assertAlmostEqual(piecewise(25.0, segments), 0.8)
        self.assertEqual(piecewise(400.0, segments), 1.0)


class BucketTests(unittest.TestCase):
    def test_boundaries_assign_to_the_named_bucket(self):
        coefficient, rows = delay_buckets([0, 3, 3.5, 10, 10.5, 25, 25.5, 45, 45.5])
        self.assertEqual([row["count"] for row in rows], [2, 2, 2, 2, 1])
        # (2*0 + 2*3 + 2*6 + 2*10 + 1*18) / 9, deliberately not divided by the max weight
        self.assertAlmostEqual(coefficient, 56 / 9)

    def test_empty_series_is_zero(self):
        coefficient, rows = delay_buckets([])
        self.assertEqual(coefficient, 0)
        self.assertTrue(all(row["share"] == 0 for row in rows))

    def test_outlier_rule_matches_the_metric_plugins(self):
        kept, outliers, cutoff = split_outliers([1, 1, 1, 1, 100])
        self.assertEqual(kept, [1, 1, 1, 1])
        self.assertEqual(outliers, [100])
        self.assertEqual(cutoff, 1.0)
        self.assertEqual(split_outliers([5]), ([5], [], None))


class DocumentTests(unittest.TestCase):
    def test_items_are_capped_before_summing(self):
        document_ = document("x-v1", 100, [
            {"id": "a", "status": "OK", "cap": 30, "raw": 45},
            {"id": "b", "status": "N/A", "cap": 20, "raw": 10, "reason": "no evidence"},
            {"id": "c", "status": "OK", "cap": 50, "raw": 0.04}])
        self.assertEqual([item["points"] for item in document_["items"]], [30.0, 0.0, 0.0])
        self.assertEqual(document_["deduction"], 30.0)
        self.assertEqual(document_["total"], 70.0)

    def test_total_never_goes_below_zero(self):
        document_ = document("x-v1", 100, [{"id": "a", "status": "OK", "cap": 80, "raw": 200}])
        self.assertEqual(document_["total"], 20.0)


if __name__ == "__main__":
    unittest.main()
