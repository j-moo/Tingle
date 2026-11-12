"""공유 계약 복사본의 해시 고정을 CI 에서 항상 소비해 드리프트를 조기 감지한다."""
import contracts


def test_contract_copies_match_pinned_hashes():
    assert contracts.mvp_policy_cases()
    assert contracts.icebreakers_general_ko_v1()
    assert contracts.questionnaire_sis_q_1_1_100()
