import base64
import binascii
import importlib.util
from pathlib import Path
import sqlite3
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("lab_server", Path(__file__).parents[1] / "tools/lab_server.py")
SERVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SERVER)


def profile(value):
    return struct.pack("<IIII16sH", 1, 1, 1, 604800, bytes([value]) * 16, 1024) + bytes(range(256)) * 4


def message(device, peer, kind=2):
    own = profile(device)
    peer_body = struct.pack("<Q", peer) + profile(peer)
    packet = b"TK\x01\x01" + struct.pack("<H", len(peer_body)) + peer_body
    packet += struct.pack("<I", binascii.crc32(packet))
    raw = struct.pack("<BBBBI16sQQQHH", 1, kind, 7, 0, 1, bytes([5]) * 16,
                      peer, 1700000000, 1700604800, len(own), len(packet)) + own + packet
    return {"protocol": SERVER.PROTOCOL, "deviceId": str(device), "eventId": raw[8:24].hex(),
            "submissionId": str(device) + ":" + raw[8:24].hex(),
            "queue": "MATCHED" if kind == 2 else "SERVER_REVIEW",
            "recordBase64": base64.b64encode(raw).decode(), "recordCrc32": SERVER.crc(raw)}


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.test_root = (Path(__file__).parents[1] / "build/test-server").resolve()
        self.test_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=self.test_root)
        self.path = Path(self.temp.name) / "server.db"
        self.store = SERVER.Store(self.path)

    def tearDown(self):
        if Path(self.temp.name).resolve().parent != self.test_root:
            raise RuntimeError("refusing cleanup outside test workspace")
        self.temp.cleanup()

    def test_durable_pair_and_retry(self):
        first = message(1, 2)
        self.assertEqual(self.store.accept(first)["verification"], "WAITING_COUNTERPART")
        # A fresh receiver must see the first committed row.
        self.store = SERVER.Store(self.path)
        self.assertEqual(self.store.accept(message(2, 1))["verification"], "PAIR_OBSERVED_TEST_ONLY")
        self.assertTrue(self.store.accept(first)["canDelete"])
        db = sqlite3.connect(self.path)
        try:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM records").fetchone()[0], 2)
        finally:
            db.close()

    def test_review_is_not_a_match(self):
        self.assertEqual(self.store.accept(message(1, 2, 3))["verification"], "SERVER_REVIEW_PENDING")

    def test_bad_bindings_do_not_store(self):
        for field, value in (("deviceId", "0"), ("eventId", "00"), ("recordCrc32", "0"),
                             ("submissionId", "different"), ("queue", "SERVER_REVIEW")):
            invalid = message(1, 2)
            invalid[field] = value
            with self.assertRaises(ValueError):
                self.store.accept(invalid)

    def test_conflicting_retry(self):
        first = message(1, 2)
        self.store.accept(first)
        raw = bytearray(base64.b64decode(first["recordBase64"]))
        raw[40] ^= 1
        first["recordBase64"] = base64.b64encode(raw).decode()
        first["recordCrc32"] = SERVER.crc(raw)
        with self.assertRaises(ValueError):
            self.store.accept(first)


if __name__ == "__main__":
    unittest.main()
