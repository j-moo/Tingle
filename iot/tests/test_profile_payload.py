"""Synthetic storage fixtures; fixed AI r2 bytes are an independent baseline."""

import copy
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import profile_payload as codec


AI_NORMAL = bytes.fromhex(
    "100900350cdb1adb1a350c00112700040202040304020204020a02040403040202030202040601020101030304010000000000000000"
    "20050000000000aa21270000000000030000000000000000000000000000000000000000000000000000aaa8aaaaaaaaaa2a"
    "30030002000031020002003202000002"
)


def example():
    raw = [4, 2, 2, 4, 3, 4, 2, 2, 4, 2, ["talk", "walk"], 2, 4, 4, "change", "help", 2, 2, 3,
           "alternate", "experience", 4, ["afternoon", "evening"], "local", 2, "none", "none", 3, "undecided", 4, "none"]
    actual = {k: {"state": "value", "value": v} for k, v in zip(codec.TRAITS, (31.25, 68.75, 68.75, 31.25))}
    actual.update({f"q{n}": {"state": "value", "value": v} for n, v in enumerate(raw, 30)})
    wishes = {str(n): {"state": "any"} for n in range(61, 96)}
    wishes["69"] = {"state": "value", "value": 3}
    snapshot = {"surveyVersion": codec.SURVEY, "actual": actual, "wishes": wishes,
                "priority": {"state": "any"}, "strict": {"state": "any"},
                "tolerance": {"state": "value", "value": "some"}}
    values = (27, "MALE", ["FEMALE"], True, False, "NON_SMOKER", "NEVER", "UNDISCLOSED")
    personal = {k: {"state": "value", "value": v} for k, v in zip(codec.PERSONAL_FIELDS, values)}
    personal["desiredAge"] = {"mode": "range", "min": 25, "max": 32}
    personal["deviceOptions"] = dict.fromkeys(codec.OPTIONS, True)
    return snapshot, personal


class ProfileStorageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        output = os.environ.get("KR_PROFILE_FIXTURES")
        if output:
            folder = Path(output)
            folder.mkdir(parents=True, exist_ok=True)
            s, p = example()
            (folder / "normal.bin").write_bytes(codec.encode(s, p))
            s["actual"]["q34"] = {"state": "unknown"}
            s["wishes"]["95"] = {"state": "missing"}
            p["religion"] = {"state": "missing"}
            (folder / "unknown.bin").write_bytes(codec.encode(s, p))
            s["strict"] = {"state": "value", "value": ["age"]}
            p["deviceOptions"]["matchingEnabled"] = False
            (folder / "strict-age-off.bin").write_bytes(codec.encode(s, p))

    def test_fixed_ai_bytes_and_complete_roundtrip(self):
        snapshot, personal = example()
        payload = codec.encode(snapshot, personal)
        self.assertEqual(len(payload), 196)
        self.assertEqual(payload[53:173], AI_NORMAL)
        self.assertEqual(codec.decode(payload), (snapshot, personal))

    def test_unknown_missing_any_and_personal_options(self):
        snapshot, personal = example()
        snapshot["actual"]["q34"] = {"state": "unknown"}
        snapshot["wishes"]["95"] = {"state": "missing"}
        snapshot["strict"] = {"state": "value", "value": ["age"]}
        personal["religion"] = {"state": "missing"}
        personal["desiredGender"] = {"state": "any"}
        personal["desiredAge"] = {"mode": "range", "min": 19, "max": None}
        for flags in range(8):
            personal["deviceOptions"] = {k: bool(flags & (1 << i)) for i, k in enumerate(codec.OPTIONS)}
            self.assertEqual(codec.decode(codec.encode(snapshot, personal)), (snapshot, personal))

    def test_version_precision_and_pii_are_rejected(self):
        snapshot, personal = example()
        for key, value in (("surveyVersion", "UNPUBLISHED"), ("nickname", "synthetic"), ("birthDate", "1999-01-01")):
            altered = copy.deepcopy(snapshot)
            altered[key] = value
            with self.assertRaises(ValueError):
                codec.encode(altered, personal)
        snapshot["actual"]["E"]["value"] = 31.251
        with self.assertRaises(ValueError):
            codec.encode(snapshot, personal)
        snapshot, personal = example()
        personal["actualAge"]["value"] = True
        with self.assertRaises(ValueError):
            codec.encode(snapshot, personal)

    def test_bad_states_selections_and_legacy_age_are_rejected(self):
        snapshot, personal = example()
        for entry in ({"state": "any"}, {"state": "unknown", "value": 1}, {"state": "value", "value": 6}):
            changed = copy.deepcopy(snapshot)
            changed["actual"]["q30"] = entry
            with self.assertRaises(ValueError):
                codec.encode(changed, personal)
        snapshot["actual"]["q40"]["value"] = ["rest", "walk", "active"]
        with self.assertRaises(ValueError):
            codec.encode(snapshot, personal)
        snapshot, personal = example()
        snapshot["legacyAge"] = {"age": 28, "min": 25, "max": 32}
        with self.assertRaises(ValueError):
            codec.encode(snapshot, personal)

    def test_noncanonical_binary_is_rejected(self):
        payload = codec.encode(*example())
        for length in range(len(payload)):
            with self.assertRaises(ValueError):
                codec.decode(payload[:length])
        for offset, value in ((0, 0), (4, 2), (7, 2), (84, 255), (195, 128)):
            changed = bytearray(payload)
            changed[offset] = value
            with self.assertRaises((ValueError, IndexError)):
                codec.decode(bytes(changed))
        with self.assertRaises(ValueError):
            codec.decode(payload + b"\0")


if __name__ == "__main__":
    unittest.main()
