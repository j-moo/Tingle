# 개인분석 v2 공통 문구와 과거 검토 이력

현재 기준: 2026-11-16, [MR !369 머지](https://lab.ssafy.com/s15-final/S15P31E106/-/commit/dc7fc4016319844373961f9d8b0a61b10b4a66f2).
검토 식별자 `mr357-wording-review-20261115-v2`의 문구는
**local/test/dev/prod에서 같은 `proposed_label_only_policy()`로 적용**된다.
문구 검토는 팀 리뷰로 관리한다. 별도 승인 파일·pin·digest·prod 기능 스위치는 현재 요구하지 않는다.
[가을님의 !369 확인](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/369#note_3081464)과
이 문서의 과거 미승인 검토 기록을 구분한다.

## 현재 적용 범위

[main.py](../main.py)가 [proposals.py](proposals.py)의 정책을 명시적으로 주입한다.
독립 adapter의 빈 기본 설정을 모든 환경의 실제 앱 조립과 혼동하지 않는다.
정책 함수·DTO·계산·문구·참조 데이터를 이 문서 작업에서 수정하지 않는다.

- 15개 reading templateId와 차이 `PA-D-SELF-WISH-FIT`를 사용한다.
- reading은 기존 priority 내림차순·안정 정렬의 상위3개, 차이는 기존 fit 기반 상위2개와 priority2/1을 유지한다.
- 등록 formatter는 SUCCEEDED와 INSUFFICIENT 모두에 적용된다. 검증된 답변을 재진술하며 전체 정보가 충분하다고 주장하지 않는다.
- 점수·유형·추천·근거·상태·선정 순서와 외부 `SIS-A-1.0`은 변경하지 않는다.
- 계산의 fit은 선정·정렬에 남고 설명 text에는 표시하지 않는다.

## v2 표현과 근거

| 범위 | 현재 표현 원칙 |
| --- | --- |
| EXPRESSION-01 | Q030/Q032의 실제 답변 재진술. 미측정 행동을 추론하지 않음 |
| CONFLICT-01/03 | 대화를 시작하는 시점과 감정이 격해졌을 때 필요한 방식을 각각 인용 |
| EXPERIENCE-02/03/04 | 약속 준비 방식과 장소 선호를 구분해 보기 label을 인용 |
| TIME-02 | 원하는 연락 빈도와 만남 빈도를 각각 인용 |
| SUPPORT-01 | 가까운 사람이 힘들 때 행동과 연인과의 화해 행동을 구분 |
| 음주 선호 Q089 | 설문에서 선택한 상대 음주 빈도의 허용 상한임을 명시. Q098이 계산에 미치는 영향과 구분 |
| 시간대 Q087 | 계산에서 불규칙 시간대를 제외했다면 그 안내를 표시 |

차이는 실제 자기/선호 문항 쌍을 근거로 쓰고, 수치 비교에서 소비한 Q098 조건만 추가한다.
추천은 실제 score 참여 문항과 소비한 Q096/Q098을 따른다.
unknown/any 또는 누락 값을 실제 측정 근거로 만들지 않는다.
자세한 구현은 [adapter.py](adapter.py)와 [계약 테스트](../../tests/test_personal_analysis_api_contract.py)를 따른다.

## 현재 오류·로그 경계

| 조건 | 현재 처리 |
| --- | --- |
| 등록 formatter의 답변 누락·불일치·근거 개수/순서/ID/label 불일치 | `FormatterEvidenceMismatch` → HTTP500 INTERNAL_ERROR |
| 요청 시 참조 무결성, 없는 표시 매핑·차이/추천 근거 등 다른 `PresentationUnavailable` | 기존 HTTP503 SERVICE_UNAVAILABLE |
| 인증 미설정·검증 장애 | 기존 인증 실패 경계 유지. 문구 승인 게이트와 무관 |
| 기동 시 참조 데이터 누락·변조 | 모든 환경의 앱 기동 실패 |

formatter 검증 실패를 코어 원문으로 fallback하거나 200/INSUFFICIENT로 바꾸지 않는다.
에러 body/헤더와 X-Request-Id/no-store는 기존 계약을 유지한다.
진단 로그는 requestId·서버가 선택한 templateId·고정 errorType만 기록한다.
답변 원문·회원정보·JWT·키·예외 메시지/traceback은 기록하지 않는다.
templateId가 정해지지 않으면 진단에 `UNRESOLVED`를 사용한다.

BE는 기존500/503을 일시 오류로 분류하므로, 오류 분류를 나눴다는 이유만으로 재시도가 없어지는 것은 아니다.
이 문서 정리는 BE 코드·재시도 설정·실제 실패 결과를 변경하지 않는다.

## 검토 자료와 재현

[presentation-review.json](presentation-review.json)의 `currentRuntime`은 현재 공통 정책 설명이다.
`historicalReview`는 **v1 검토 snapshot**으로, 당시 UNAPPROVED·정상 상태에만 적용하던 renderer·
INSUFFICIENT 원문 fallback·prod OFF/ON 설명을 포함한다. 이 값들은 현재 v2 동작이 아니다.
과거 inventory를 현재 문구 구현이나 운영 승인 설정으로 로드하지 않는다.
[합성 샘플 manifest](../../tests/fixtures/personal-analysis-api/sample-manifest.json)의 과거 검토 상태도 같은 용도로 구분한다.

기존 v1/v2 비교의 집계는 reading121, 정상 표현4, 차이18,482로 총18,607개 치환 사례다.
이는 전체100문항을 동시에 조합한 전수 입력 수가 아니며 이번 문서 작업의 실행 횟수도 아니다.
현재 회귀는 [문구 테스트](../../tests/test_personal_analysis_wording_v2.py)와
[환경 동등성 테스트](../../tests/test_personal_analysis_environment_parity.py)로 확인한다.

```powershell
python -B -m pytest -q tests/test_personal_analysis_wording_v2.py tests/test_personal_analysis_api_contract.py tests/test_personal_analysis_environment_parity.py tests/test_personal_analysis_documentation.py
```

## 과거 검토의 범위 — 현재 승인 차단이 아님

2026-11-15의 v2 검토에서는 문구·templateId·priority를 미승인 후보로 기록했고,
초기 formatter 실패를503으로 다룬 후 BE 리뷰에서500으로 분리했다.
해당 시점의 운영 OFF/ON 조건, 독립 작업 경로, 테스트 수치, GMS 실패 조사와 미결정 목록은
[정리 전 v2 문서 고정본](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/dc7fc4016319844373961f9d8b0a61b10b4a66f2/ai-server/app/personal_analysis_api/WORDING_REVIEW_V2.md)에 보존한다.
이를 현재 운영이 미승인 상태로 차단됐다는 설명이나 이번 후속 테스트 결과로 재사용하지 않는다.

현재 기본 안내는 [README](README.md), 과거 승인 차단과 현재 검증의 구분은
[검증 기록 안내](PRODUCTION_POLICY_VERIFICATION.md)를 따른다.
배포·재시작은 이 문서 수정과 별개이며 이미 저장된 BE text나 FAILED 결과를 자동 변경하지 않는다.
