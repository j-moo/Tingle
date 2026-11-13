# Icebreaker Cf 검증과 BE 호환 확인 (-47)

기준 develop: `8241fc394d1ba346a79d6aac218554343d43e2fb` (2026-11-12).
BE !331 확인 source: `adbca900382c5fcef4c955dace799351ae0cd516`, Open / 미병합.

## 최소 변경과 검증 경계

기존 생성 경로는 JSON 파싱 → IcebreakerResponse DTO → templates._validate_questions →
주제/문장 제한 검사 순서다. 기존 templates 검사는 이미 C 계열 문자를 거부하지만,
DTO 자체는 원문의 Cf를 통과시키는 누락을 재현했다.
`IcebreakerResponse.check_questions`에서 원문 Cf를 먼저 거부하도록 4줄만 추가했다.
공백 제거·NFKC·소문자화·중복/금지어/도메인 검사 순서는 유지한다.
Cf를 삭제해 질문을 통과시키거나 정상 한국어의 허용 범위를 축소하지 않는다.

- Zero Width Space, ZWNJ/ZWJ, Word Joiner, BOM, Soft Hyphen, 아랍어/양방향 제어 문자,
  supplementary-plane 태그 등 Python Unicode 15.0.0의 **Cf 170자 전수 거부**.
- 대표 Cf 10자의 문장 앞/중간/뒤 배치 거부. 정규화 호출 이전 원문 거부 확인.
- 연락처 금지어 사이의 숨은 문자 8종 및 일반 질문 속 Cf는 기존 Q040 fallback 사용.
- 정상 한국어·공백 제거·NFD 정규화·정규화 후 중복/금지어 검증 유지.
- 승인 Q040 5주제(rest/talk/culture/walk/active)는 계속 source=GENERATED.
- 실패·timeout·provider 오류는 기존 COMMON_TOPIC_TEMPLATE 또는 GENERAL_FALLBACK 사용.
  취소는 fallback으로 삼키지 않는다.

정확한 GMS_PROMPT_VERSION은 **`icebreakers-gms-ffb273caf96e6059`**이며 프롬프트는 변경하지 않았다.
AI DTO는 정확한 버전만 허용한다. 임의 해시·접두사만 일치·null·빈 버전은 거부한다.
질문 개수와 source/promptVersion 응답 필드는 유지하며 GENERATED를 템플릿으로 위장하지 않는다.

## BE blocker와 Safety

BE !331의 IcebreakerQuestions는 `promptVersion.startsWith("icebreakers-gms-")`만 확인한다.
정확한 승인 버전 대조와 잘못된 버전 거부는 아직 미해결이며 기존 리뷰 blocker를 유지한다.
BE 코드는 수정하지 않는다. !333은 BE 버전 승인 검증 및 실제 연동 확인 전까지 Draft다.
BE에서 양쪽 동의·정확한 버전·GENERATED 저장/GET 유지·철회/지연 응답 폐기와
기존 COMMON_TOPIC_TEMPLATE/GENERAL_FALLBACK 회귀 확인이 남아 있다.

이번 contract.py 변경은 Safety GMS_SOURCE_FILES 밖이며 공유 GMS·main.py·lifespan은 추가 수정하지 않았다.
기존 !333의 main.py 주입 변경 자체는 develop pin을 무효화한다.
기본 OFF·prod 차단·정확한 source pin 거부를 유지하고 새 승인 해시는 만들지 않는다.

## 로컬 확인

Icebreaker·Safety 영향 회귀: **495 PASS, 0 FAIL, 0 SKIP**.
독립 브랜치 전체 회귀: **Python 2479 PASS, Node 4 PASS, parity PASS, 0 FAIL, 0 SKIP**.
기존 Starlette per-request cookie 사용에 관한 deprecation warning 1건이 있다.
수정 전 U+200B DTO 누락 테스트가 실패함을 재현한 후 수정했다.

```powershell
cd C:/Project/S15P31E106-ai/ai-server
../.venv/Scripts/python.exe -m pytest -q tests/test_icebreaker_cf.py tests/test_icebreaker_gms_development.py tests/test_icebreaker_templates.py tests/test_icebreaker_q040.py tests/test_icebreakers_http.py tests/test_gms_main_runtime.py tests/test_safety_asgi_contract.py
../.venv/Scripts/python.exe scripts/run_tests.py
```

MockTransport만 사용했다. 실제 GMS 외부 호출과 BE/앱 실연동은 수행하지 않았다. **CI_NOT_CHECKED**.
