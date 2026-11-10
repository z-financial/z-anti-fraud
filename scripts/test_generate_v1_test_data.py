"""Check fixture boundaries, wire identities, and reproducibility."""

import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import generate_v1_test_data as generator


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.time = datetime(2026, 11, 10, 12, tzinfo=timezone.utc)
        self.scenarios = {s.case_id: s for s in generator.build_scenarios("test", self.time)}
        self.temp_root = Path(__file__).resolve().parents[1] / "target"
        self.temp_root.mkdir(exist_ok=True)

    def test_window_scope_and_duplicate_delivery(self):
        expected = {
            "cutoff-included": (500000, 6), "cutoff-excluded": (450000, 5),
            "same-time-excluded": (450000, 5), "future-excluded": (450000, 5),
            "duplicate-amount": (450000, 5), "duplicate-count": (49000, 49),
            "incoming-excluded": (450000, 5), "other-account-excluded": (450000, 5),
        }
        for case_id, totals in expected.items():
            with self.subTest(case=case_id):
                self.assertEqual(totals, generator.history_totals(self.scenarios[case_id]))
        generator.validate(list(self.scenarios.values()))

    def test_boundaries_and_all_combinations_are_covered(self):
        for rule, prefix in ((generator.AMOUNT_RULE, "amount"),
                             (generator.COUNT_RULE, "count"),
                             (generator.SINGLE_RULE, "single")):
            self.assertEqual((), self.scenarios[f"{prefix}-below"].matched_rules)
            self.assertEqual((), self.scenarios[f"{prefix}-equal"].matched_rules)
            self.assertEqual((rule,), self.scenarios[f"{prefix}-above"].matched_rules)
        self.assertEqual(8, len({frozenset(s.matched_rules) for s in self.scenarios.values()}))

    def test_adapter_compatible_identity_and_no_labels_in_request(self):
        transfer = generator.Transfer("evt-1", "op-1", "sender", "recipient", 123, self.time)
        rows = generator.movement_rows(transfer)
        self.assertEqual(["transfer:v1:ZXZ0LTE:out", "transfer:v1:ZXZ0LTE:in"],
                         [r["event_id"] for r in rows])
        self.assertEqual(["sender", "recipient"], [r["account_id"] for r in rows])
        payload = generator.request(transfer)
        self.assertEqual("money_movement.transfer", payload["event"]["eventType"])
        self.assertEqual(123, payload["event"]["data"]["amount"])
        self.assertNotIn("expected", json.dumps(payload))

    def test_generation_is_reproducible_and_refuses_accidental_overwrite(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as tmp:
            first, second = Path(tmp) / "first", Path(tmp) / "second"
            manifest = generator.generate(first, "test", self.time)
            generator.generate(second, "test", self.time)
            self.assertEqual(22, manifest["scenarioCount"])
            for filename in generator.FILES:
                self.assertEqual((first / filename).read_bytes(), (second / filename).read_bytes())
            requests = [json.loads(line) for line in (first / "requests.jsonl").read_text().splitlines()]
            expected = [json.loads(line) for line in (first / "expected.jsonl").read_text().splitlines()]
            self.assertEqual([r["requestId"] for r in requests], [e["requestId"] for e in expected])
            self.assertEqual(8, len({tuple(e["expectedMatchedRuleIds"]) for e in expected}))
            with self.assertRaises(ValueError):
                generator.generate(first, "test", self.time)
            generator.generate(first, "test", self.time, overwrite=True)

    def test_rejects_ambiguous_clock_or_run_id(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as tmp:
            for run_id, clock in (("bad/id", self.time), ("test", self.time.replace(tzinfo=None)),
                                  ("test", self.time.replace(microsecond=1))):
                with self.subTest(run_id=run_id, clock=clock), self.assertRaises(ValueError):
                    generator.generate(Path(tmp), run_id, clock)


if __name__ == "__main__":
    unittest.main()
