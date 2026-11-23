import csv
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from score_t025 import score


class ScoringTest(unittest.TestCase):
    def test_readiness_and_reference_boundaries(self):
        template = Path(__file__).with_name("t025-trials.csv")
        with template.open(newline="", encoding="utf-8") as source:
            rows = list(csv.DictReader(source))

        with TemporaryDirectory() as directory, redirect_stdout(StringIO()):
            path = Path(directory) / "measurements.csv"

            def result():
                with path.open("w", newline="", encoding="utf-8") as output:
                    writer = csv.DictWriter(output, rows[0].keys())
                    writer.writeheader()
                    writer.writerows(rows)
                return score(path, 5000, 25000)

            self.assertEqual(result(), 2)
            for index, row in enumerate(rows):
                row.update(attempt_id=str(index), ready_ms="timeout")
            for row in rows[:29]:
                row.update(ready_ms="5000", horn_count="1", horn_error_us="22000",
                           horn_uncertainty_us="3000", lane3_error_us="-9000", lane3_uncertainty_us="1000",
                           lane5_error_us="0", lane5_uncertainty_us="750", reconciled="yes")
            self.assertEqual(result(), 0)
            rows[0]["lane3_error_us"] = "-9500"
            self.assertEqual(result(), 2)
            rows[0]["lane3_error_us"] = "-11001"
            self.assertEqual(result(), 1)
            rows[0]["lane3_error_us"] = "-9000"
            rows[0]["ready_ms"] = "5001"
            self.assertEqual(result(), 1)


if __name__ == "__main__":
    unittest.main()
