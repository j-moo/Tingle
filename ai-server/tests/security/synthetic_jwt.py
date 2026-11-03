"""Test-only signer preserving malformed JSON/header values for negative cases."""

import json
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from jwt.utils import base64url_encode

NOW = 1_800_000_000


def sign_synthetic(claims, key, headers):
    header = base64url_encode(json.dumps({"alg":"RS256", **headers}).encode())
    payload = base64url_encode(json.dumps(claims).encode())
    message = header + b"." + payload
    signature = key.sign(message, padding.PKCS1v15(), hashes.SHA256())
    return (message + b"." + base64url_encode(signature)).decode()
