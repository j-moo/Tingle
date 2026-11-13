"""파트 간 공유 계약 파일의 AI 복사본 읽기.

각 파트는 자기 폴더의 복사본만 읽고, 원본과의 일치는 같은 SHA-256 고정으로 보장한다.
back 쪽 고정은 back/src/test/java/com/e106/tingle/SharedContractTest.java 다.
해시가 다르면 back 원본과 이 복사본을 함께 갱신한다.
"""
import hashlib
from pathlib import Path

_TESTS = Path(__file__).resolve().parent


def _load(path, expected):
    data = path.read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise AssertionError(f"{path.name} 계약 해시 불일치: {actual} (기대 {expected})")
    return data


def mvp_policy_cases():
    """back/src/test/resources/contracts/mvp-policy-20261026-cases.json 과 같은 bytes (back ContractFixtures 가 고정)."""
    return _load(
        _TESTS / "fixtures/mvp-policy-20261026-cases.json",
        "9bedd16058295211224008b6553099300c72358fa85f478f980879e174b540a8",
    )


def icebreakers_general_ko_v1():
    """back/src/main/resources/icebreakers/general-ko-v1.json 과 같은 bytes."""
    return _load(
        _TESTS / "fixtures/icebreakers-general-ko-v1.json",
        "395e30569f10de4961bd446713aeddbc4b25ef734ddc6c284896a0cd5e8c383e",
    )


def questionnaire_sis_q_1_1_100():
    """back/src/main/resources/survey/questionnaires/SIS-Q-1.1-100.json 과 같은 bytes."""
    return _load(
        _TESTS.parent / "reference/survey-20261021/questionnaire-SIS-Q-1.1-100.json",
        "d1199ef59596e78e2c2d3df8884a72a2b97c63099e443da7b9103f3d1e7bcb6e",
    )
