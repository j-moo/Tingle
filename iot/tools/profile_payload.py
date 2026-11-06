"""Reference codec for signed, local profile storage; no matching or peer export.

Uses the pinned AI r2 logical codebook. This candidate needs FE/BE agreement.
CLI output contains size/hash only; input values and payload bytes are not logged.
"""

import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import struct


FORMAT = "tingle-profile-storage-v1"
SURVEY = "SIS-Q-1.1-100"
CODEBOOK_SHA256 = "ee4357d4248e89619845e7ca99ad30542e37d0766454e65a2de5d85c12cba641"
STATES = ("value", "unknown", "any", "missing")
TRAITS = ("E", "A", "C", "I")
DOMAINS = {
    40: ["rest", "talk", "culture", "walk", "active"],
    44: ["explain", "apologize", "change", "time", "lighten"],
    45: ["listen", "empathize", "solve", "help", "presence"],
    49: ["half", "alternate", "income", "inviter", "discuss"],
    50: ["practical", "experience", "handmade", "memory", "daily"],
    52: ["morning", "afternoon", "evening", "night", "irregular"],
    53: ["local", "stay", "district", "nearby", "far"],
    55: ["none", "electronic", "cigarette", "both", "quitting"],
    56: ["none", "protestant", "catholic", "buddhist", "won", "muslim", "other"],
    58: ["yes", "no", "undecided"],
    60: ["none", "covered", "visible", "cosmetic"],
    96: ["personality", "approach", "connection", "experience", "conflict", "boundaries", "money", "lifestyle", "future"],
    97: ["smoking", "children", "religion", "tattoo", "age"],
    98: ["close", "some", "wide"],
}
WISH_SOURCE = {75: 40, 79: 44, 80: 45, 84: 49, 85: 50, 87: 52, 88: 53, 90: 55, 91: 56, 93: 58, 95: 60}
PERSONAL_DOMAINS = {
    "actualGender": ["MALE", "FEMALE"],
    "desiredGender": ["MALE", "FEMALE"],
    "smoking": ["NON_SMOKER", "E_CIGARETTE", "ONLY_WHEN_DRINKING", "SMOKER"],
    "drinking": ["NEVER", "SPECIAL_OCCASIONS_ONLY", "MONTHLY_AT_MOST_ONCE", "MONTHLY_TWO_TO_FOUR", "WEEKLY_TWO_TO_THREE", "WEEKLY_FOUR_OR_MORE"],
    "religion": ["NONE", "PROTESTANT", "CATHOLIC", "BUDDHIST", "OTHER", "UNDISCLOSED"],
}
PERSONAL_FIELDS = ("actualAge", "actualGender", "desiredGender", "hasProfilePhoto", "requirePartnerPhoto", "smoking", "drinking", "religion")
OPTIONS = ("matchingEnabled", "vibrationEnabled", "ledEnabled")
TAGS = (1, 0x10, 0x11, 0x20, 0x21, 0x30, 0x31, 0x32, 0x40, 0x41)
LENGTHS = (46, 9, 39, 5, 39, 3, 2, 2, 16, 1)


def require(condition, field):
    if not condition:
        raise ValueError("Invalid profile field: " + field)


def keys(value, required, optional=()):
    require(isinstance(value, dict) and set(required) <= value.keys() <= set(required) | set(optional), "keys")


def entry(value, allow_any=False):
    keys(value, ("state",), ("value",))
    require(value["state"] in STATES, "state")
    status = STATES.index(value["state"])
    require(status != 2 or allow_any, "any")
    require(("value" in value) == (status == 0), "value/state")
    return status, value.get("value")


def integer(value, lo, hi):
    require(type(value) is int and lo <= value <= hi, "integer")
    return value


def bitmap(value, domain, maximum):
    require(isinstance(value, list) and 1 <= len(value) <= maximum, "selection")
    require(all(v in domain for v in value) and len(set(value)) == len(value), "selection")
    return sum(1 << domain.index(v) for v in value)


def states_pack(statuses):
    packed = bytearray((len(statuses) + 3) // 4)
    for i, value in enumerate(statuses):
        packed[i // 4] |= value << (2 * (i % 4))
    return bytes(packed)


def state_at(data, i):
    return (data[i // 4] >> (2 * (i % 4))) & 3


def logical(status, value):
    return {"state": STATES[status], **({"value": value} if status == 0 else {})}


def question(value, n):
    status, raw = entry(value, allow_any=n >= 61 and n != 98)
    if status:
        return 0, status
    if 61 <= n <= 64:
        domain = ["low", "mid", "high"]
    else:
        domain = DOMAINS.get(WISH_SOURCE.get(n, n), list(range(1, 6)))
    if n in (40, 52, 96, 97) or n in WISH_SOURCE:
        maximum = 2 if n in (40, 97) else 3 if n == 96 else len(domain)
        return bitmap(raw, domain, maximum), status
    require(raw in domain and type(raw) is type(domain[0]), "codebook")
    return domain.index(raw) + 1, status


def question_decode(value, status, n):
    if status:
        return logical(status, None)
    domain = ["low", "mid", "high"] if 61 <= n <= 64 else DOMAINS.get(WISH_SOURCE.get(n, n), list(range(1, 6)))
    raw = [v for i, v in enumerate(domain) if value & (1 << i)] if n in (40, 52, 96, 97) or n in WISH_SOURCE else domain[value - 1]
    return logical(status, raw)


def encode_personal(data):
    keys(data, PERSONAL_FIELDS + ("desiredAge", "deviceOptions"))
    values, statuses = [], []
    for name in PERSONAL_FIELDS:
        status, value = entry(data[name], allow_any=name == "desiredGender")
        statuses.append(status)
        if status:
            value = 0
        elif name == "actualAge":
            value = integer(value, 19, 65535)
        elif name in ("hasProfilePhoto", "requirePartnerPhoto"):
            require(type(value) is bool, "boolean")
            value = int(value)
        elif name == "desiredGender":
            value = bitmap(value, PERSONAL_DOMAINS[name], 2)
        else:
            require(value in PERSONAL_DOMAINS[name], "personal codebook")
            value = PERSONAL_DOMAINS[name].index(value) + 1
        values.append(value)
    age = data["desiredAge"]
    keys(age, ("mode",), ("min", "max"))
    modes = ("missing", "unknown", "any", "range")
    require(age["mode"] in modes, "desiredAge.mode")
    mode = modes.index(age["mode"])
    if mode == 3:
        require(set(age) == {"mode", "min", "max"}, "desiredAge.range")
        low = integer(age["min"], 19, 65535)
        high = 0 if age["max"] is None else integer(age["max"], low, 65535)
    else:
        require(set(age) == {"mode"}, "desiredAge.mode")
        low = high = 0
    options = data["deviceOptions"]
    keys(options, OPTIONS)
    require(all(type(options[k]) is bool for k in OPTIONS), "deviceOptions")
    flags = sum(int(options[k]) << i for i, k in enumerate(OPTIONS))
    body = struct.pack("<H7B", *values) + states_pack(statuses) + struct.pack("<BHH", mode, low, high)
    return body, bytes([flags])


def encode(snapshot, personal):
    keys(snapshot, ("surveyVersion", "actual", "wishes", "priority", "strict", "tolerance"), ("legacyAge",))
    require(snapshot["surveyVersion"] == SURVEY, "surveyVersion")
    keys(snapshot["actual"], TRAITS + tuple(f"q{n}" for n in range(30, 61)))
    keys(snapshot["wishes"], tuple(str(n) for n in range(61, 96)))
    groups = [bytes([1]) + SURVEY.encode("ascii") + bytes.fromhex(CODEBOOK_SHA256)]
    trait_values, statuses = [], []
    for name in TRAITS:
        status, raw = entry(snapshot["actual"][name])
        statuses.append(status)
        if status:
            fixed = 0
        else:
            require(type(raw) in (int, float), "trait")
            fixed = Decimal(str(raw)) * 100
            require(fixed.is_finite() and 0 <= fixed <= 10000 and fixed == fixed.to_integral_value(), "trait precision")
            fixed = int(fixed)
        trait_values.append(fixed)
    groups.append(struct.pack("<4H", *trait_values) + states_pack(statuses))
    for lo, hi, owner in ((30, 60, "actual"), (61, 64, "wishes"), (65, 95, "wishes")):
        values = [question(snapshot[owner][f"q{n}" if owner == "actual" else str(n)], n) for n in range(lo, hi + 1)]
        groups.append(bytes(x[0] for x in values) + states_pack([x[1] for x in values]))
    for name, n in (("priority", 96), ("strict", 97), ("tolerance", 98)):
        value, status = question(snapshot[name], n)
        groups.append(bytes([status]) + (struct.pack("<H", value) if n == 96 else bytes([value])))
    personal_body, options = encode_personal(personal)
    if "legacyAge" in snapshot:
        age = snapshot["legacyAge"]
        require(personal["actualAge"] == {"state": "value", "value": age["age"]} and
                personal["desiredAge"] == {"mode": "range", "min": age["min"], "max": age["max"]}, "legacyAge binding")
    groups.extend((personal_body, options))
    require(tuple(len(x) for x in groups) == LENGTHS, "group lengths")
    return b"TPS1" + b"".join(struct.pack("<BH", tag, len(body)) + body for tag, body in zip(TAGS, groups))


def decode(payload):
    require(len(payload) == 196 and payload[:4] == b"TPS1", "header")
    offset, groups = 4, []
    for tag, size in zip(TAGS, LENGTHS):
        require(struct.unpack_from("<BH", payload, offset) == (tag, size), "TLV")
        groups.append(payload[offset + 3:offset + 3 + size])
        offset += 3 + size
    require(groups[0] == bytes([1]) + SURVEY.encode() + bytes.fromhex(CODEBOOK_SHA256), "metadata")
    snapshot = {"surveyVersion": SURVEY, "actual": {}, "wishes": {}}
    for i, name in enumerate(TRAITS):
        snapshot["actual"][name] = logical(state_at(groups[1][8:], i), struct.unpack_from("<H", groups[1], 2 * i)[0] / 100)
    for group, lo, hi, owner in ((groups[2], 30, 60, "actual"), (groups[3], 61, 64, "wishes"), (groups[4], 65, 95, "wishes")):
        count = hi - lo + 1
        for i, n in enumerate(range(lo, hi + 1)):
            snapshot[owner][f"q{n}" if owner == "actual" else str(n)] = question_decode(group[i], state_at(group[count:], i), n)
    for group, name, n in zip(groups[5:8], ("priority", "strict", "tolerance"), (96, 97, 98)):
        snapshot[name] = question_decode(int.from_bytes(group[1:], "little"), group[0], n)
    values = struct.unpack_from("<H7B", groups[8])
    personal = {}
    for i, name in enumerate(PERSONAL_FIELDS):
        status, value = state_at(groups[8][9:11], i), values[i]
        if status == 0:
            if name in ("hasProfilePhoto", "requirePartnerPhoto"):
                value = bool(value)
            elif name == "desiredGender":
                value = [x for bit, x in enumerate(PERSONAL_DOMAINS[name]) if value & (1 << bit)]
            elif name != "actualAge":
                value = PERSONAL_DOMAINS[name][value - 1]
        personal[name] = logical(status, value)
    mode, low, high = struct.unpack_from("<BHH", groups[8], 11)
    personal["desiredAge"] = {"mode": ("missing", "unknown", "any", "range")[mode]}
    if mode == 3:
        personal["desiredAge"].update(min=low, max=high or None)
    personal["deviceOptions"] = {k: bool(groups[9][0] & (1 << i)) for i, k in enumerate(OPTIONS)}
    # Re-encoding rejects noncanonical state padding, forbidden bits and bad values.
    require(encode(snapshot, personal) == payload, "canonical payload")
    return snapshot, personal


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--case", default="NORMAL")
    parser.add_argument("--side", choices=("left", "right"), required=True)
    parser.add_argument("--personal", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = json.loads(args.golden.read_text(encoding="utf-8-sig"))["cases"]
    case = next((c for c in cases if c["id"] == args.case), None)
    require(case is not None, "case")
    payload = encode(case["logicalSnapshot"][args.side], json.loads(args.personal.read_text(encoding="utf-8-sig")))
    decode(payload)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    print(json.dumps({"format": FORMAT, "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, IndexError, ArithmeticError):
        raise SystemExit("Profile validation failed; input values are not logged.") from None
