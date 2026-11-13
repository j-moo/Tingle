# Safety Guard 앱 수명주기와 자원 소유권

관련 이슈: S15P31E106-334. 기준 develop은 `24ce543b0d7055d2759dac0b688cf4adc3860917`이며 !293(`449b9fc`)과 !296(`a17bb520`)을 포함한다. 이 명세의 검증 범위는 기본 OFF, 개발 규칙 엔진 및 pytest 합성 모델이다. GMS 공급자 등록·활성화·배포 승인을 의미하지 않는다.

## 앱 구성과 시작

`app.main.create_app()`은 기존 인증, 요청 ID, 오류 처리, 도메인 라우터와 health를 등록한다. import 시 기본 앱을 만들지만 Safety Guard 엔진과 개발 카탈로그는 만들지 않는다. 실제 lifespan startup에서 `DevelopmentRuntime.startup()`을 호출한다. 인증 공개키 설정의 기존 import/앱 생성 시 검증은 유지한다.

기본 OFF 또는 local/test/dev 이외 환경에서는 개발 release·tokenizer·규칙 데이터를 읽거나 엔진 factory를 호출하지 않는다. GMS 설정 조회·클라이언트 생성·네트워크 호출도 없다. lifespan이 시작된 OFF 앱은 인증 실패 시 기존 401/403, 인증 성공 시 본문을 읽기 전에 기존 422 POLICY_UNRESOLVED를 반환한다. 시작 전 또는 종료가 시작된 앱은 인증 후 기존 503 SERVICE_UNAVAILABLE로 거부한다.

명시적으로 켠 개발 규칙 모드는 기존 descriptor 파일과 별도 SHA-256 pin을 검증한다. 카탈로그의 tokenizer로 규칙 엔진을 구성하고 HTTP 실행 경계를 만든 다음, 카탈로그가 보관한 manifest에 대해 파일·source 검사를 다시 수행한다. 검증이 모두 성공한 `RuntimeResources(catalog, engine, execution)`만 요청에 공개한다. 검증 실패 시 이미 인수한 자원을 닫고 공개 상태를 남기지 않는다. 중복 startup은 현재 엔진을 덮어쓰지 않고 거부한다.

시작 실패 처리는 일반 `Exception`과 명시적 `CancelledError`에만 적용한다. `KeyboardInterrupt` 같은 프로세스 중단 신호는 startup 오류로 바꾸지 않는다. 정리 중 일반 예외가 추가로 발생해도 최초 release 오류와 취소는 그대로 전파하고, 일반 시작 오류의 기존 고정 오류 메시지는 유지한다.

`create_app(..., _risk_engine_factory=...)`는 테스트가 코드에서만 전달하는 생성 함수다. 환경 변수, HTTP 필드, 동적 import, 모델 레지스트리는 추가하지 않는다. 기본 생성 함수는 계속 `LocalRuleRiskEngine`이다. Fake AsyncModel의 구현과 LunaShadowEngine 합성 조립은 pytest 파일에만 존재한다. 반환에 성공한 엔진의 소유권은 런타임으로 이전한다. 생성 함수가 반환 전에 실패하면 자신이 부분 생성한 자원을 직접 정리해야 한다.

## 요청과 종료

요청은 검증된 자원 스냅샷을 빌리며 자원을 닫지 않는다. 인증 이전부터 시작한 동일 요청 기한으로 본문, release 사전 검사, 토큰 계산, 판정, release 사후 검사와 선택적 Shadow를 제한한다. 기존 HTTP DTO와 상태/코드, !296의 RISK 전용 fallback 및 fallback의 Shadow 집계 제외는 유지한다. HTTP 라우터를 직접 만드는 기존 단위 테스트 경로도 유지한다.

lifespan의 종료 정리는 다음 순서를 따른다.

1. 첫 await 이전에 공개 상태를 철회하여 신규 요청을 거부한다.
2. HTTP `ExecutionBoundary.aclose()`로 새 task/worker를 막고 진행 요청을 취소한다. 원래 호출자의 취소와 ASGI disconnect 동작을 유지한다.
3. 엔진이 제공하는 `aclose()`를 한 번 호출한다. `LunaShadowEngine`은 Shadow 경계, primary 경계를 차례로 닫는다. 기존 동기 규칙 엔진은 지속 자원이나 `aclose()`를 갖지 않는다.
4. 같은 종료 예산 안에서 잔존 task와 실제 worker를 확인한다. 총 예산은 HTTP 경계의 `cleanup_seconds × 4`로 기본 0.2초다. 기존 실행 경계의 개별 취소 대기는 그대로 유지한다.

종료 호출이 겹치거나 반복되면 같은 정리 task를 사용한다. 정리 task가 완료돼도 그 결과를 기다리던 종료 호출이 모두 반환하기 전에는 재시작을 거부한다. 이전 종료 호출이 새 세대의 엔진 상태를 읽거나 수정하는 경합을 막기 위한 조건이다. 호출자 한 명이 취소돼도 정리 task의 소유권은 런타임에 남는다. 종료 예산을 넘기면 정리 task에 취소를 요청하고 유한 시간 안에 반환한다. `remaining`은 HTTP·primary·Shadow의 pending task, executor의 active worker 및 아직 끝나지 않은 정리 task를 합한 진단 수치다. 하나의 요청이 여러 task를 가질 수 있으므로 요청 개수가 아니다.

이미 정리가 완료된 자원의 반복 종료 호출이 취소되면 `closed`를 복구한다. 정리 task가 끝났고 실제 pending task·active worker가 모두 0인 경우에만 적용하며, 작업이 남은 `closing` 상태를 우회하지 않는다.

Python은 실행 중인 동기 스레드를 강제로 종료하지 못한다. worker는 함수가 실제 끝날 때 슬롯을 반환하고, 늦게 끝난 결과는 취소된 Future에 공개하지 않는다. 잔존 작업이 있으면 같은 런타임의 재시작을 거부한다. 자원이 모두 반환되고 종료가 끝난 뒤 새 lifespan에서는 새 엔진·HTTP 경계를 만든다. 영구적으로 협조하지 않는 작업의 강제 회수는 프로세스 supervisor의 책임이며, asyncio 취소가 worker를 제거했다고 간주하지 않는다.

Uvicorn은 일반적으로 진행 HTTP 요청을 먼저 배출한 뒤 lifespan shutdown에 진입한다. 런타임의 입장 차단 기준은 lifespan shutdown 진입이다. 새 TCP 연결에 대한 서버의 거부 동작과 이 앱 수준의 기존 503은 별개다. 테스트는 실제 Uvicorn 루프백 HTTP의 정상 요청부터 서버 종료까지, 그리고 ASGI로 제어한 진행 요청/종료 경합을 각각 확인한다.

## 검증 범위

`tests/test_risk_main_lifespan.py`는 별도 FastAPI 앱을 만들지 않고 실제 `app.main` factory를 사용한다. 임시 release·tokenizer와 메모리의 합성 RSA/JWT만 사용하며 인증·catalog.supports·source hash를 우회하지 않는다. GmsClient 생성자·환경 factory·요청 함수를 차단하고 외부 socket 연결과 DNS 시도를 집계하여 0회를 확인한다. 기존 fixture는 원문, 오류 표식, JWT, 개인키가 캡처 로그·응답에 노출되지 않는지도 검사한다.

필수 검증은 OFF/health/인증, 잘못된 startup pin, 조립 후 release 변경, 정상·RISK·timeout·예외·refusal, 규칙 hit/no-hit fallback, 요청 중 취소, 본문·primary 중 disconnect, release/source 사전·사후 변경, 종료 중 입장 차단과 정리 순서, 중복 startup·반복 shutdown·재시작, 겹친 종료 호출과 호출자 취소, 동기 worker/비협조 모델의 유한 종료 예산과 슬롯 반환, 실제 HTTP/서버 종료를 포함한다. 기존 실제 앱 subprocess 검사는 예외 포착 범위를 import에서 lifespan startup까지 확장한다.

기존 requirements 및 선택 개발 requirements만 사용한다. 재현 명령은 `ai-server`에서 실행한다.

```powershell
python -B -m pytest -q tests/test_risk_main_lifespan.py
python -B -m pytest -q
$nodeTests = (Get-ChildItem tests/node/*.test.mjs | Sort-Object Name).FullName
node --test @nodeTests
python -B -m scripts.check_personal_parity
git diff --check
```

## Release source hash 영향과 활성화 조건

고정 `SOURCE_FILES` 10개 목록은 변경하지 않는다. 그중 변경되는 파일과 실제 LF 바이트의 SHA-256은 다음과 같다. 이 값은 변경 영향 관찰값이며 승인 pin으로 등록하지 않는다.

| 파일 | 기준 develop SHA-256 | 변경 후 SHA-256 |
| --- | --- | --- |
| http_api.py | `0ce7dbce93becc220c17239a608105e3f4ca75ce11097fa3f0daf3b6804da1e2` | `b40712c6c1e6accda6dfb0747bac03948205bc68a2d21b7961bcc447b1e6fcba` |
| runtime.py | `89d4b2c585214e31c094513bb3db39bbc27bf65b62b1a0ff9db9abb240225ab6` | `127e6c22ca6fcf95ca50dea006de880716a724b9156f079280e47d91312e4689` |

기존 바이트를 고정한 개발 artifact와 descriptor pin은 새 코드에서 일치하지 않아 시작이 거부된다. 승인된 재발급 전에는 OFF를 유지해야 한다. 실제 승인 manifest, artifact, source hash 목록 또는 운영 descriptor 값은 수정·생성·등록하지 않는다. 합성 fixture는 pytest 임시 경로에서만 생성한다.

`main.py`는 현재 SOURCE_FILES 밖이며 이번에 목록을 임의 확장하지 않는다. GMS adapter·공용 client와 prompt도 현재 개발 규칙 release의 승인 범위 밖이다. 실제 GMS 활성화 전에는 앱 조립 코드를 포함한 배포 source/이미지, 모델·prompt 바이트와 identity, tokenizer·입력 버전, fallback/Shadow 정책을 함께 승인해야 한다. 규칙 개발 release 또는 테스트 모델의 identity 검증을 공급자 승인으로 재사용하면 안 된다.

공용 GMS client, Match Insight, engine/execution/luna_shadow/release 구현은 변경하지 않는다. 기존 !296의 refusal 및 제공자 오류 분류는 유지한다. 실제 GMS의 내용 차단 HTTP 400 처리, BE 재시도와 요청 예산, 개인정보 전송·보관 정책, worker별 배포 시작·종료 및 supervisor의 강제 종료 예산은 별도 활성화 검토 대상이다.
