# Match Insight evidence 계약 검증 (-145)

기준 develop: `8241fc394d1ba346a79d6aac218554343d43e2fb` (2026-11-12).

BE가 당시 두 사람의 설문을 비교하며, 원응답과 `personality.result`는 BE 내부에 유지한다.
AI는 BE가 승인한 `context.evidence`만 사용하고 원본 비교·점수·성격을 재계산하지 않는다.

| 경계 | 현재 구현과 검증 |
| --- | --- |
| 입력 DTO | `attemptId`, `contextId`, 기존 11필드 manifest, `contextBundle={context,scope}` 유지. 새 계약 필드 없음 |
| 당시 연결 | contextId=encounter_id, evaluation_ref=BE inputFingerprint의 64자리 소문자 해시, version_ref=manifest.releaseId. 모든 record와 scope reference 일치 |
| 원본 근거 | e1 형식의 고유 ID, 비어 있지 않은 source_ref. source_ref의 실제 원본 진위·당시 비교·동의는 BE 책임 |
| 열람과 방향 | context/scope viewer 일치. A_TO_B는 A의 선호에 B가 맞음. viewer A/B의 두 방향을 HTTP+MockTransport로 검증 |
| 공개 범위 | display_evidence_ids는 실제 ID만 포함. model_evidence_ids는 display_evidence_ids의 부분집합. 미승인 근거를 GMS 생성·검토에 전송하지 않음 |
| 문장 문법 | contact/planning/activity/experience 및 기존 4 kind, 승인 정형 fact와 grounding 유지. D01/D03 허용, D02/D05 보류. 의역 범위 확대 없음 |
| 외부 최소 전송 | viewer와 선택된 Evidence의 id/direction/kind/topic/fact만 전달. 생성에는 allowedPhrasings, 검토에는 candidate 추가. reference/source_ref/manifest/scope/원응답/personality.result 미전송 |
| 성공 응답 | source=GENERATED, promptVersion=`6e03adc54d375ea78a71dbd047993ba3cf0a53687db38fadbbc3db66e7a52b86` |
| fallback | source=RULE_FALLBACK, promptVersion=null. 두 경우 모두 기존 5필드, evidenceKeys=[] 유지 |

## 검증과 미확인 경계

`test_match_gms_development.py`에서 과거 reference·scope 무결성, 비공개 필드 거부,
GMS 생성/검토 양쪽의 최소 payload, viewer/direction, 정형 문법, 정확한 응답 버전을 보강했다.
Match 영향 테스트와 Safety GMS 런타임 회귀: **193 PASS, 0 FAIL, 0 SKIP**.
독립 브랜치 전체 회귀: **Python 2488 PASS, Node 4 PASS, parity PASS, 0 FAIL, 0 SKIP**.
기존 Starlette per-request cookie 사용에 관한 deprecation warning 1건이 있다.

```powershell
cd C:/Project/S15P31E106-ai/ai-server
../.venv/Scripts/python.exe -m pytest -q tests/test_match_gms_development.py tests/test_match_explanation_fidelity_145.py tests/test_match_explanation_display_subset_145.py tests/test_match_explanation_planning_viewer_a_145.py tests/test_gms_main_runtime.py
../.venv/Scripts/python.exe scripts/run_tests.py
```

현재 develop의 `JpaHistoricalExplanationSource`는 당시 저장 입력을 검증하고 공개 요약만 구성하지만
model_evidence_ids=[]를 유지한다. `HistoricalRuleExplanationProvider`는 RULE_FALLBACK 및 null 버전만
수용한다. DTO 계약 일치가 실제 GENERATED 저장·조회·철회 연동 완료를 뜻하지 않는다.
BE의 외부 전송 승인과 GENERATED 결과 수용, source_ref의 실제 원본 비교, D02/D05는 별도 확인 대상이다.
BE 코드는 수정하지 않는다.

이번 보강은 테스트·문서만 수정하므로 기존 MR !332의 Safety source pin 영향을 추가로 넓히지 않는다.
!332 자체는 develop 대비 main.py와 공유 gms.py/prompts.py가 달라 기존 Safety GMS source pin을
재사용할 수 없다. 실제 최종 통합 소스의 팀 승인 전까지 기존 pin은 거부해야 하며 승인 파일/해시는 변경하지 않는다.
실제 GMS 외부 호출·BE/앱 실연동은 수행하지 않는다. **CI_NOT_CHECKED**.
