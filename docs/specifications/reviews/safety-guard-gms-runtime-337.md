# Safety Guard GMS 런타임 주입 · S15P31E106-337

[AI-02 위험 대화 감지 명세](../functional-spec-v3/ai.md#ai-02-위험-대화-감지와-대응-연결)의 구현과 릴리스 검토 기록이다.

기준 소스는 2026-11-11 fetch한 `origin/develop`의
`4d40a947f0a2a922e065578f07036b0c6a75f574`다.
!300의 실제 merge commit `502af003c47916291e934392b50fe111067a60bd`가
이 기준의 조상임을 확인했다. !293의 실제 adapter, !296의 오류 분류와
규칙 fallback, !300의 FastAPI lifespan 소유권을 재사용한다.

이번 변경은 실제 adapter를 선택적으로 조립하는 구조와 합성 ASGI 검증이다.
승인된 GMS 릴리스나 운영 활성화, 실제 GMS 접속 성공을 제공하지 않는다.
실제 키 조회, 실사용자 원문 전송, 실제 GMS HTTP 호출은 수행하지 않는다.

## 주입 조건과 소유권

`create_app(..., _risk_gms_primary=GmsPrimaryConfiguration(...))`는
신뢰된 Python 조립 코드에서만 받는다. 환경변수, HTTP 입력, 모델 이름 문자열,
동적 import 경로로 선택할 수 없다. 기본 `app = create_app()`에는 이 설정이 없다.

다음 조건을 모두 충족해야 실제 `GmsPrimaryAdapter`가 만들어진다.

1. `APP_ENV`가 `local/test/dev`이고 기존 `RISK_LOCAL_RULES_ENABLED`가 명시적 True다.
2. 기존 규칙 descriptor, 별도로 제공한 descriptor SHA-256, artifact,
   고정 `SOURCE_FILES`, 로컬 tokenizer 검증이 모두 통과한다.
3. 내부 설정의 `enabled`가 정확히 True다. 기존 테스트용 engine factory와
   GMS 설정을 동시에 켜면 시작을 거절한다.
4. 별도 GMS 승인 파일과 외부에서 전달한 SHA-256 pin이 일치하고 아래 바인딩이 일치한다.
5. 그 이후에만 지연 `client_factory`를 호출하며 반환값은 실제 `GmsClient`여야 한다.
   런타임은 `from_environment()`를 호출하지 않는다. 테스트는 메모리의 합성 키와
   `httpx2.MockTransport`만 사용한다.
6. 조립 후 다시 릴리스를 검사한 다음 `RuntimeResources`를 공개한다.

| 구성 | Safety Guard 동작 |
| --- | --- |
| 기본 local/test/dev/prod | 기존 OFF 계약 유지: 인증 후 `422 POLICY_UNRESOLVED`, GMS 생성/조회/요청 0회 |
| 승인된 개발 규칙만 명시적 활성화 | 기존 `LocalRuleRiskEngine`만 사용, GMS 요청 0회 |
| GMS 내부 설정 OFF | 승인 파일이나 client factory를 사용하지 않고 기존 규칙 경로 유지 |
| 승인된 개발 규칙 + GMS 내부 설정 ON + 별도 pin 검증 | 실제 adapter를 primary로 주입 |
| prod에서 어떤 opt-in을 전달해도 | 기존 OFF 유지, 개발 artifact나 GMS factory에 접근하지 않음 |
| 활성화 요청에서 pin/바인딩 실패 | lifespan 시작 실패, 엔진 미공개, client 호출 전 차단 |

`RuntimeResources(catalog, engine, execution)`와 `DevelopmentRuntime.resolve()`의
요청 시점 스냅샷 계약은 유지한다. primary 전용 엔진은 !296의
`LunaShadowEngine` 분석·fallback·기한 처리를 상속하고 `start_shadow=None`으로
Shadow를 등록하지 않는다. 상속 생성자의 review 참조는 같은 실제 adapter를
가리키지만 이 경로에서는 호출하지 않는다. Fake 모델은 테스트 파일에만 존재한다.

종료 시 !300의 순서대로 신규 입장을 차단하고 HTTP 실행 경계를 취소한 다음
엔진 경계를 닫는다. `GmsClient`는 요청마다 AsyncClient/stream을 열고 컨텍스트로
닫는다. 취소는 규칙 성공으로 바꾸지 않는다. 반복 close와 원래 요청 기한을 보존한다.

## 별도 GMS 승인 파일

`GmsReleaseCatalog`는 기존 규칙 릴리스에 더해 다음 정확한 JSON 바인딩을 검사한다.
시작할 때 승인 파일이나 pin을 자동 생성하지 않는다. 파일 내용의 일관성 검증은
사람의 승인 여부 검증과 다르므로, pin 전달 절차 자체에 팀의 릴리스 승인이 필요하다.

| 필드 | 고정 내용 |
| --- | --- |
| `format`, `scope` | `safety48-gms-primary-v1`, `development-only` |
| `manifest` | 기존 규칙 descriptor의 전체 manifest와 정확히 일치 |
| `rulesDescriptorSha256` | 검증된 규칙 descriptor 원본 바이트 SHA-256 |
| `endpoint`, `modelVersion` | 기존 `GmsClient`의 ENDPOINT와 MODEL |
| `promptVersion`, `promptSha256` | `safety48-gms-primary-v1`, 실제 PRIMARY_PROMPT UTF-8 SHA-256 |
| `executionLimits` | 실제 주입할 ExecutionLimits 전체 값 |
| `sources` | GMS_SOURCE_FILES 전체 소스 해시 |

`GMS_SOURCE_FILES`는 기존 규칙 `SOURCE_FILES` 전체와 `main.py`,
`gms_primary.py`, `gms_runtime.py`, 공용 GMS client 및 그 dto/prompts/validation을
추가로 묶는다. tokenizer 바이트·특수 토큰·패키지 버전·입력 버전은 기존 규칙
descriptor → artifact → tokenizer 연결을 통해 고정된다.
승인 파일·규칙 descriptor·artifact·tokenizer·소스는 요청 전후에도 재검사하며
변경되면 `422 POLICY_UNRESOLVED`로 결과 공개를 막는다.

기존 `SOURCE_FILES` 목록이나 기존 승인 release/source hash를 수정하지 않는다.
다만 이번에 수정한 `runtime.py`가 이미 그 목록에 포함되므로 이전 descriptor의
해시로 새 구현을 활성화할 수 없다. 새 소스에 맞는 별도 규칙 릴리스 검토와
별도 GMS 승인 pin이 모두 필요하다. 기존 pin을 새 파일에서 자동 계산해 대체하면 안 된다.

## ASGI 계약과 오류

실제 `app.main`의 JWT 인증, manifest, 마스킹/순서/토큰 제한 검사 후에만 provider에
접근한다. 요청 본문 전체나 attemptId/contextId/manifest를 GMS에 보내지 않고,
기존 adapter가 검증한 messages와 고정 system prompt만 전송한다.

- 정상 `NORMAL/RISK/INSUFFICIENT`는 기존 `attemptId/manifest/verdict` DTO를 유지한다.
- refusal, timeout, `408/429/5xx`, 파싱 실패, 검증된 FAILED에는 기존 규칙을 한 번 적용한다.
  새 메시지에서 위험 근거가 있는 경우에만 규칙 버전을 가진 `200 RISK`로 응답한다.
  규칙 무감지·실패·시간 부족은 기존 `503 SERVICE_UNAVAILABLE`다.
- `401/403`과 미확인 `400`은 기존 인증·설정 오류로 처리하며 규칙 fallback에 넣지 않는다.
  **SSAFY GMS의 HTTP400 정책 차단 실제 응답 규격은 미확인**이다. 임의의 오류 문구나
  추정한 code를 정책 거부로 분류하지 않는다. 이번 작업은 공용 client/adapter를 변경하지 않는다.
- primary 성공과 fallback 모두 Shadow를 실행하지 않는다.
- BE/FE/IoT 코드를 변경하지 않는다. INSUFFICIENT 저장 및 503 재시도 정책은 기존 계약을 유지한다.

## 운영 모델 활성화 전 필수 체크리스트

- [ ] 팀 리뷰로 새 규칙 releaseId/manifest/소스 해시를 승인한다. 이전 승인본은 보존한다.
- [ ] GMS 모델의 정확한 제공자 식별자와 고정 가능 여부를 확인한다. 현재 MODEL 문자열이
  불변 snapshot이라고 가정하지 않는다. 실제 응답 모델과 승인 모델의 일치를 검증한다.
- [ ] PRIMARY_PROMPT 원문·버전·SHA-256, 허용 출력 스키마, rule fallback 버전을 승인한다.
- [ ] 실제 모델에 맞는 tokenizer 바이트·패키지·특수 토큰·입력 직렬화를 승인한다.
  현재 카운터는 기존 canonical context 기준이다. system prompt와 실제 GMS 요청 포맷까지
  포함한 전체 입력의 토큰 예산을 검증하기 전에는 운영 적합성이 입증된 것으로 보지 않는다.
- [ ] 고정 SOURCE_FILES와 GMS_SOURCE_FILES, 공용 client 및 실행 제한을 릴리스 검토에 포함한다.
- [ ] 외부 전송 대상·목적·마스킹·동의·보관/삭제·접근권한을 승인하고 pin 전달자를 정한다.
  현재 입력 검사는 모든 개인정보를 탐지한다는 보장이 없다.
- [ ] 개발용 접근 및 **합성 입력 실호출**을 별도로 승인한 뒤, 승인 릴리스/모델/프롬프트와
  실행 구성을 대조한다. 이번 작업에서 실제 키나 운영 시크릿을 조회하지 않는다.
- [ ] 별도 스모크에서는 합성 입력만 사용하고 HTTP 상태와 승인된 구조화 오류 코드만 기록한다.
  인증 헤더·제공자 본문·입력 원문·예외 원문은 남기지 않는다.
- [ ] GMS HTTP400 정책 차단 규격을 공식 근거와 합성 사례로 확인한 후 별도 변경을 검토한다.
- [ ] prod 활성화는 현재 하드 차단을 바꾸는 별도 리뷰가 필요하다. 이 MR로 배포/활성화하지 않는다.

## 검증과 릴리스 영향

`tests/test_gms_main_runtime.py`는 합성 JWT/릴리스/tokenizer와 MockTransport로 실제
app.main을 호출한다. 기본 OFF, 정상 응답, 실패 행렬, 인증·입력·manifest 차단,
승인 pin/바인딩 실패, 사전/사후 파일 변경, 기한·취소·종료를 검증한다.
외부 소켓 접속과 `GmsClient.from_environment()`는 테스트에서 차단한다.
새 의존성 없이 저장소의 requirements 두 파일을 사용한다.

로컬 최종 검증은 전체 pytest **2,390 PASS / 0 FAIL / 0 SKIP**(신규 main/GMS
95건 포함), Node **4 PASS / 0 FAIL / 0 SKIP**, parity **367,518개 수치 비교,
최대 절대 오차 0.0**이다. 기존 cookies deprecation 경고 1건이 남는다.
Windows 샌드박스에서는 TestClient 실행이 멈춰 외부 접속 차단 fixture를 유지한
샌드박스 밖 실행을 사용했다. 초기 신규 종료 테스트의 응답 가정을 !300의
진행 중 요청 취소 계약에 맞췄다. 런타임 종료 구현은 바꾸지 않았다.

추가 `scripts/verify_docs.py` 검사는 기존 보존 문서 7개의 해시 불일치로 실패한다.
해당 검사기·보존 manifest·문서 묶음은 기준 develop과 diff가 없으며 이번 변경에
포함하지 않는다. Jenkins의 AI 공통 실행기는 이 보존 문서 검사를 실행하지 않는다.

CI와 동일한 전체 AI 실행 명령:

```powershell
python scripts/run_tests.py
```

전체 Python, Node 회귀 및 JS/Python parity 통과 후 develop 대상 일반 Ready MR을
제출한다. feature 브랜치/MR 검증은 Jenkinsfile의 develop/master push 배포 조건에
해당하지 않는다. 자동 병합·배포는 실행하지 않는다.
