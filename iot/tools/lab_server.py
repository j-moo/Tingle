"""Local synthetic-data receiver; not the production BE or its proof verifier.

Run from repository root: python iot/tools/lab_server.py
Android USB forwarding: adb reverse tcp:8765 tcp:8765
Only a committed SQLite row produces a STORED receipt. SERVER_REVIEW stays pending
for the future algorithm; storing evidence is never a successful matching result.
"""

import argparse
import base64
import binascii
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sqlite3
import struct

PROTOCOL = "tingle-lab-binary-v1"


def crc(data):
    return str(binascii.crc32(data) & 0xFFFFFFFF)


def validate(message):
    if not isinstance(message, dict) or message.get("protocol") != PROTOCOL:
        raise ValueError("unsupported lab protocol")
    device = message.get("deviceId")
    if not isinstance(device, str) or not device.isdecimal() or not 0 < int(device) < 2**63:
        raise ValueError("invalid device")
    record = base64.b64decode(message["recordBase64"], validate=True)
    if not 86 <= len(record) <= 2310 or record[0] != 1 or record[1] not in (2, 3):
        raise ValueError("invalid record")
    own_len, peer_len = struct.unpack_from("<HH", record, 48)
    if not 34 <= own_len <= 1058 or peer_len > 1200 or 52 + own_len + peer_len != len(record):
        raise ValueError("invalid record boundaries")
    own = record[52:52 + own_len]
    if struct.unpack_from("<H", own, 32)[0] + 34 != own_len:
        raise ValueError("invalid own profile")
    event = record[8:24].hex()
    queue = "MATCHED" if record[1] == 2 else "SERVER_REVIEW"
    if (message.get("eventId") != event or message.get("submissionId") != device + ":" + event
            or message.get("queue") != queue or message.get("recordCrc32") != crc(record)):
        raise ValueError("unbound submission")
    if record[1] == 2:
        peer = record[52 + own_len:]
        if (len(peer) < 52 or peer[:4] != b"TK\x01\x01"
                or struct.unpack_from("<H", peer, 4)[0] + 10 != len(peer)
                or struct.unpack_from("<I", peer, len(peer) - 4)[0] != binascii.crc32(peer[:-4])):
            raise ValueError("invalid matched peer packet")
        if struct.unpack_from("<Q", peer, 6)[0] != struct.unpack_from("<Q", record, 24)[0]:
            raise ValueError("peer identity mismatch")
    return device, event, queue, record


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as db, db:
            db.execute("""CREATE TABLE IF NOT EXISTS records (
                device TEXT NOT NULL, event TEXT NOT NULL, queue TEXT NOT NULL,
                bytes BLOB NOT NULL, PRIMARY KEY(device,event))""")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA synchronous=FULL")
        return db

    def accept(self, message):
        device, event, queue, record = validate(message)
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT bytes FROM records WHERE device=? AND event=?", (device, event)).fetchone()
            if old is not None and old[0] != record:
                raise ValueError("immutable submission conflict")
            db.execute("INSERT OR IGNORE INTO records VALUES (?,?,?,?)", (device, event, queue, record))
            others = db.execute("SELECT device,bytes FROM records WHERE event=? AND device<>?", (event, device)).fetchall()
            verification = "SERVER_REVIEW_PENDING" if queue == "SERVER_REVIEW" else "WAITING_COUNTERPART"
            if queue == "MATCHED" and others:
                verification = "PAIR_MISMATCH"
                peer_id = str(struct.unpack_from("<Q", record, 24)[0])
                own_len = struct.unpack_from("<H", record, 48)[0]
                for other_id, other in others:
                    other_own_len = struct.unpack_from("<H", other, 48)[0]
                    if (other_id == peer_id and other[1] == 2
                            and struct.unpack_from("<Q", other, 24)[0] == int(device)
                            and record[32:48] == other[32:48]
                            and record[52:52 + own_len] == other[52 + other_own_len + 14:-4]
                            and other[52:52 + other_own_len] == record[52 + own_len + 14:-4]):
                        verification = "PAIR_OBSERVED_TEST_ONLY"
        # Exiting the transaction commits BEFORE the receipt is returned.
        return {"protocol": PROTOCOL, "deviceId": device, "eventId": event,
                "recordCrc32": crc(record), "status": "STORED", "canDelete": True,
                "verification": verification}


def serve(store, host, port):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if self.path != "/lab/records" or not 0 < length <= 16384:
                    raise ValueError("invalid request path or size")
                receipt = store.accept(json.loads(self.rfile.read(length)))
                body = json.dumps(receipt).encode()
                self.send_response(200)
            except (ValueError, KeyError, TypeError, binascii.Error):
                body = b'{"status":"NOT_STORED"}'
                self.send_response(400)
            except sqlite3.Error:
                body = b'{"status":"NOT_STORED"}'
                self.send_response(503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format_string, *args):
            # No tokens, profile bytes, or device identities in HTTP logs.
            pass

    server = ThreadingHTTPServer((host, port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default=str(Path(__file__).resolve().parents[1] / "build/lab-server.db"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    serve(Store(args.db), args.host, args.port)
