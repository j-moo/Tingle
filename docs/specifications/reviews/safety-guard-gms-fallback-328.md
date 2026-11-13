# AI-02 Safety Guard GMS 대체 분석 검토 · S15P31E106-328

[AI-02 위험 대화 감지 명세](../functional-spec-v3/ai.md#ai-02-위험-대화-감지와-대응-연결)의 구현 검토 기록이다. 명시적으로 조립한 `GmsPrimaryAdapter`와 `LunaShadowEngine`을 합성 릴리스·토크나이저·JWT 및 GMS MockTransport로 검증한다.

- BE 서비스 인증, 요청/토큰 검증, 릴리스 사전 검증을 통과한 뒤에만 분석한다. 모델 응답의 manifest나 버전이 일치하지 않으면 대체하지 않는다.
- GMS 거부, 타임아웃, `408/429`, `5xx`, 네트워크 장애, 파싱/검증 불가 또는 검증된 `FAILED`가 나오면 기존 `LocalRuleRiskEngine.analyze`를 공식 분석용 제한 실행기에서 한 번 호출한다. 원래 요청 기한을 연장하지 않으며, 취소와 엔진 종료는 대체 분석의 성공으로 바꾸지 않는다.
- GMS 리다이렉트와 `408/429` 외의 `4xx`(인증 거절 `401/403` 포함), 로컬 요청 프로토콜·인코딩 오류는 내부적으로 인증·설정 오류로 분류한다. 기존 `503 SERVICE_UNAVAILABLE`로 종료하고 규칙 분석이나 Shadow를 실행하지 않는다. 제공자 오류 본문은 읽지 않는다.
- 기존 규칙에서 새 메시지의 위험 근거를 찾으면 `200`의 기존 `RISK` DTO를 반환한다. 근거 및 규칙의 모델·프롬프트 버전을 보존하고, 응답 전 기존 릴리스 재검사를 통과해야 한다. 이 결과는 GMS와 규칙을 비교하는 Shadow 표본에 포함하지 않는다. 유효한 GMS `NORMAL`/`RISK`/`INSUFFICIENT`의 응답과 Shadow는 유지한다.
- 규칙 무감지, 규칙 실패 또는 시간 부족은 기존 `503 SERVICE_UNAVAILABLE`로 남는다. 무감지를 `200 NORMAL`이나 새로운 미판정 DTO로 바꾸지 않는다. 위험 결과는 기존 안내용이며 자동 제재를 지시하지 않는다.

기준 develop의 BE는 `NORMAL`/`RISK`만 성공으로 저장하고, `INSUFFICIENT`와 `FAILED`를 실패 영수증으로 처리한다. BE -312 검토본 `51d3706`은 AI `400`/`422`를 같은 입력으로 재시도하지 않고 `503`은 재시도 대상으로 둔다(총 시도 상한 3회). 거부 후 규칙 무감지에서의 비재시도 처리, `FAILED`와 `INSUFFICIENT`의 구분 및 저장·재시도 기준, 현재 `503`에 포함된 제공자 인증·설정 오류의 재시도 정책은 BE 합의가 필요하다. 이번 후보는 기존 상태 코드와 실제 `attemptId`/`manifest`/`verdict` 전송 형태를 유지한다.

공용 GMS의 새 예외는 `GmsError`의 하위 타입이다. Match Insight의 생성·검증은 기존 `MODEL_ERROR` 및 설명 대체 경로를 사용한다. `http_api.py`와 `luna_shadow.py`는 개발 릴리스의 `SOURCE_FILES`에 포함돼 있어, 이 후보의 소스는 이전 릴리스에 기록된 해시와 일치하지 않는다. 활성화에는 별도의 개발 릴리스 검토가 필요하다.
