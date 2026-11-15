# MR !357 운영 표시 정책 차단 검증

검증일: 2026-11-15 (Asia/Seoul). 기준 `develop@2692eff01b4bbfb1cab9529ac7d406b7c7791f5e`,
MR 원본 `dc5289e013fe7fcde2775f5ad5f2f1e5ab3adb03`.
기존 작업트리는 보존하고 `.mr357-policy` 전용 작업트리에서 검증했다.

## 원인과 변경

원본 `main.py`는 dev와 prod ON 양쪽에 `UNAPPROVED`인
`proposed_label_only_policy()`를 주입했다. opt-in과 문구 승인을 구분한 주석만으로는
미승인 문구의 운영 응답 반환을 막을 수 없었다.

두 기준 커밋의 `PresentationPolicy`, `proposed_label_only_policy`, 승인/운영 정책
참조를 검색했다. 승인된 것은 버전 조합·참조 데이터·근거 의미이며, 표시 정책은
`proposals.py`, `DISPLAY_POLICY_DRAFT.md`, 샘플 manifest 모두 미승인으로 명시한다.
재사용할 승인 운영 정책·등록 체계·승인 출처·표시 버전은 확인되지 않았다.

- prod OFF: 기존처럼 개인분석 패키지 import, runtime, OpenAPI 라우터 없음. POST 404.
- prod ON: `production.py`의 운영 정책이 없으면 앱 생성 단계에서 기동 거부.
  제안 factory나 adapter를 생성하지 않는다. 참조 JSON 손상이 있어도 정책 거부가 먼저다.
- 승인본을 연결할 운영 경로는 유지한다. 정책 담당자가 확정한 실제 정책과 승인 출처·
  표시 버전을 후속 코드 리뷰로 연결해야 한다. 운영 플래그만 바꿔서는 활성화되지 않는다.
- local/test/dev 제안 정책과 기존 계산·설문·DTO·서비스 JWT·버전·참조 해시 검증은 보존한다.
- 새 의존성, 정책 승인용 SHA, 승인 boolean, 서버 설정 변경은 없다.

## 정책 담당 검토 범위

[presentation-review.json](presentation-review.json)의 `approval`은 `UNAPPROVED`,
`approvalSource`와 `presentationVersion`은 `null`이다. 운영 코드가 읽는 승인 파일이 아니다.

1. 15개 reading templateId·원문 textTemplate·priority·근거 순서와 상위 3개 정렬.
2. 정상 `PA-R-EXPRESSION-01`의 Q030=4/5 × Q032=1/2 실제 4개 문구.
   renderer는 SUCCEEDED의 Reading.text에만 적용되며 INSUFFICIENT 또는 근거 검증 실패는
   기존 코어 원문을 사용한다. 그 원문도 승인 대상이다.
3. 다른 동적 reading 9개 ID의 `{Qxxx.answerLabel}` 치환. 문자열은 기존 catalogue의
   실제 보기 label 그대로다. 이를 요약하거나 임의 교정하지 않았다.
4. 차이 `PA-D-SELF-WISH-FIT`, `self-preference-{selfQuestionId}-{preferenceQuestionId}`,
   label/self/wish 문구와 priority 2/1. Q030~060 ↔ Q065~095의 31개 비교쌍과
   배열 보기의 ` · ` 연결, unknown/any 제외, 실제 fit 기반 상위 2개 선정 범위를 포함한다.
   fit은 계산·정렬에 남고 표시 문구에는 없다. 수치 비교에서 실제 쓰인 Q098만 근거에 추가한다.
5. 표시 정책 버전·승인 출처, 외부 analysisVersion 영향, 과거 저장 text 재현 방식.

추천의 reason text는 현재 공개 DTO에 없으며 새로 추가하지 않았다. 점수·유형 코드·
추천 mode/evidence 계산과 BE 공개 필터·저장 책임도 변경하지 않았다.

## 재현 명령

저장소의 기존 requirements 두 파일을 전용 가상환경에 설치했다. 새 패키지 선언은 없다.
아래 명령은 `ai-server`에서 해당 환경의 Python으로 실행한다.

```powershell
python -m pytest -q tests/test_personal_analysis_production.py tests/test_personal_analysis_import_isolation.py
python scripts/run_tests.py
docker build -t tingle-ai-personal-policy-357:20261115-review .
python -m scripts.check_personal_runtime --image tingle-ai-personal-policy-357:20261115-review
git diff --check
```

## 검증 결과

- 전체 runner: **Python 3,142 passed**, 실패·오류·skip 0. 기존 per-request cookies
  폐기 예정 경고 1건. Node **4 passed**. JS/Python **367,518회 수치 비교, 최대 오차 0.0**.
- 운영 정책/HTTP 및 import 격리 **43개 사례**가 전체 실행에 포함되어 통과했다.
  prod 기본 OFF 라우터 부재, ON+미승인 활성화 거부, 테스트 전용 정책 연결 시
  SUCCEEDED/INSUFFICIENT 200, JWT 누락 401·권한 오류 403, 버전 오류 409,
  참조 해시 오류 503, 계산 오류 500, local/test/dev의 기존 제안 응답 보존을 확인했다.
- 새 Docker 이미지의 기존 CMD `python -m app.main`으로 5개 시나리오 모두 통과:
  dev의 정상/부족 200, prod OFF 및 OFF+참조 누락/변조의 health 200·개인분석 404·
  개인분석 import/runtime 부재, prod ON의 실제 프로세스 비정상 종료와 미승인 정책 오류.
- 검토 JSON을 기존 독립 branch fixture 15개, expression 실제 변형 4개,
  catalogue 62개 문항, 정상/부족 차이 renderer와 대조하여 일치했다.
- `.env.example`에는 `APP_ENV`, `HOST`, `PORT`, `PERSONAL_ANALYSIS_PROD_ENABLED`의
  공개 예시값만 있다. 실제 비밀값·키·토큰은 없고 실제 `.env`는 Git ignore 대상이다.
  저장소에서 이미 추적 중인 예제 파일의 주석만 보완했다.
- `git diff --check` 통과. 변경은 AI 파일뿐이며 계산·설문·DTO·보안 모듈은 변경하지 않았다.

최초 targeted 실행에서는 INSUFFICIENT에 formatter가 적용된다고 잘못 가정한 기대값
1건이 실패했다. SUCCEEDED에만 formatter를 적용하는 기존 계약에 맞춰 기대값을 고친 뒤
위 전체 runner를 제외 없이 재실행하여 통과했다. Windows 제한 환경에서는 TestClient의
로컬 소켓 초기화가 멈춰 로컬 실행 권한으로 재실행했다. 의존성은 기존 로컬 캐시에서
전용 환경으로 설치했으며 다른 작업의 가상환경은 변경하지 않았다.

운영 성공 경로 테스트는 `TEST-ONLY-*` ID의 **테스트 전용 합성 정책**으로 운영 연결점을
대체한다. 실제 adapter·참조 데이터·계산·서비스 JWT 검증을 통과하지만 운영 문구의
실제 승인을 뜻하지 않는다. prod 테스트에서 제안 factory를 호출하면 즉시 실패한다.
개인분석 테스트는 GMS 환경 키 탐색과 요청 메서드를 실패하도록 막는다.

## 남은 차단·미확인

- 실제 표시 정책 승인이 없으므로 운영 개인분석은 OFF로 유지해야 한다. ON은 전체 AI 앱의
  기동을 거부하므로 설정을 변경하면 안 된다. 이 MR만으로 가입 진행 해결을 주장하지 않는다.
- 승인 후 정책 연결, 실제 BE 저장·앱 표시·가입 완료 확인이 필요하다.
- Safety Guard 등의 기존 release descriptor/trust pin은 수정하지 않았다. 기존 승인
  절차에서 변경된 소스와의 호환성을 별도로 검토해야 한다.
- BE/FE/IoT/Infra, 개발·운영 서버 설정·배포는 변경하지 않았다. CI 반복 조회·자동 병합은 하지 않는다.
