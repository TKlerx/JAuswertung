import unittest

from pc_measurement import clock_error, offset_window, signal_onset


class MeasurementTest(unittest.TestCase):
    def test_independent_reference_keeps_the_whole_observation_window(self):
        self.assertEqual(offset_window([(1_000, 800, 1_014), (2_002, 1_800, 2_008)]), (201, 209))
        self.assertIsNone(offset_window([(1_000, 800, 1_014), (3_000, 1_800, 3_008)]))

    def test_ten_ms_includes_reference_uncertainty(self):
        self.assertEqual(clock_error(15_000, (1_000, 2_000), (8_000, 10_000)),
                         {"error_us": 7_500, "reference_uncertainty_us": 1_500,
                          "upper_error_us": 9_000, "result": "within_limit"})
        self.assertEqual(clock_error(17_000, (1_000, 2_000), (8_000, 10_000))["result"], "inconclusive")
        self.assertEqual(clock_error(20_001, (1_000, 2_000), (8_000, 10_000))["result"], "outside_limit")

    def test_single_spike_is_not_a_horn(self):
        samples = [(n * 1000, 0.1 if n == 2 or 12 <= n <= 25 else 0) for n in range(40)]
        self.assertEqual(signal_onset(samples, 0.02), 12_000)
        self.assertIsNone(signal_onset(samples[:11], 0.02))


if __name__ == "__main__":
    unittest.main()
