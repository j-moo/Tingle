"""Export public synthetic interop vectors; never imports private signing keys."""
import argparse
import base64
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    values = dict(line.split("=", 1) for line in args.source.read_text().splitlines())
    result = {"scope": "SYNTHETIC_HOST_ONLY_NOT_TEAM_SERVER_KEY",
              "format": "tingle-keyring-settings-v3" if bytes.fromhex(values["bundle"])[3] == ord("3") else "tingle-keyring-settings-v2", "privateKeysIncluded": False,
              "serverKeyId": "dev-ed25519-" + values["serverPublic"][:16],
              "serverPublicKeyRawHex": values["serverPublic"], "vectors": {}}
    for name, value in values.items():
        raw = bytes.fromhex(value)
        entry = {"bytes": len(raw), "base64url": base64.urlsafe_b64encode(raw).rstrip(b"=").decode(),
                 "sha256": hashlib.sha256(raw).hexdigest()}
        if name.endswith("Transcript"):
            entry["utf8"] = raw.decode()
        result["vectors"][name] = entry
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
