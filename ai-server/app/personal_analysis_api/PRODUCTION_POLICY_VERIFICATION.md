# 개인분석 현재 실행 검증과 과거 승인 차단 기록

갱신: 2026-11-16. 이 파일명은 기존 링크를 보존하기 위해 유지한다.
현재 개인분석에 별도 운영 승인 파일·pin·기능 스위치가 필요하다는 뜻이 아니다.

## 현재 기준

[!369 머지 `dc7fc401`](https://lab.ssafy.com/s15-final/S15P31E106/-/commit/dc7fc4016319844373961f9d8b0a61b10b4a66f2) 이후
[main.py](../main.py)는 local/test/dev/prod에서 동일한 `proposed_label_only_policy()`와 라우터를 등록한다.
문구 승인 상태에 따른 prod 전용 404/503이나 전체 앱 기동 거부는 제거됐다.
`production.py` 및 개인분석 승인 환경변수 3개는 삭제됐으며 재도입하지 않는다.
현재 계약은 [README](README.md), 문구·오류 경계는 [v2 설명](WORDING_REVIEW_V2.md)을 따른다.

인증·100문항·버전·참조 무결성·근거 검증은 유지한다.
참조 데이터가 누락·변조되면 모든 환경이 기동에서 실패한다.
인증 장애·요청 시 참조/변환 계약 오류의 기존 503까지 없어진 것은 아니다.
문구 검토는 팀 리뷰이며 검토용 JSON은 운영 승인 자료나 런타임 입력이 아니다.

## 현재 검증 항목과 재현

| 검사 | 확인할 동작 |
| --- | --- |
| 정상·정보부족 | prod/dev 모두 HTTP 200, 같은 합성 입력의 전체 응답 동일 |
| 인증 | 실제 서비스 JWT와 환경별 issuer/audience 검증. 인증 실패 시 본문 파싱·계산 0회 |
| 요청·버전 | 100문항·catalogue·본문 크기와 기존 400/422/409 경계 유지 |
| 참조 손상 | 임시 복사본에서 missing/integrity 오류를 주입하고 모든 환경의 기동 실패 확인 |
| 계산·문구 실패 | formatter 근거 불일치는500, 다른 서비스 불가 조건은503. 정상/정보부족으로 오류 승격하지 않음 |
| 앱·패키징 | 기존 Docker CMD, 다른 AI 라우트 보존, JSON inventory 포함·파싱 확인 |

기존 테스트 의존성을 사용해 `ai-server`에서 실행한다.

```powershell
python -B -m pytest -q tests/test_service_auth_startup.py tests/test_personal_analysis_production.py tests/test_personal_analysis_environment_parity.py tests/test_personal_analysis_import_isolation.py tests/test_personal_analysis_documentation.py
docker build -t personal-analysis-docs:review .
python -B -m scripts.check_personal_runtime --image personal-analysis-docs:review
git diff --check
```

명령 목록은 실행 결과가 아니다. 후속 문서 작업에서 실제 실행한 검증과 미실행은 별도 보고한다.
합성 입력과 임시 RSA 키만 사용하며 실제 GMS·운영 배포·실사용자 데이터는 사용하지 않는다.

## 과거 !357 승인 차단 검증 — 이력 전용

2026-11-15의 문서와 검증은 당시 표시 정책이 UNAPPROVED였던 상태를 대상으로 했다.
당시에는 prod OFF의 라우터 부재/404와 prod ON의 승인 정책 부재로 인한 기동 실패를 확인했다.
이는 **현재 동작이 아니며**, 현재 prod 실행을 위해 승인 파일·pin·별도 표시 버전을 등록하라는 요구도 아니다.
과거 테스트 수치와 독립 검토 작업 경로를 이번 문서 후속의 결과로 재사용하지 않는다.

원문·당시 기준 SHA·실행 수치는 [정리 전 고정 기록](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/dc7fc4016319844373961f9d8b0a61b10b4a66f2/ai-server/app/personal_analysis_api/PRODUCTION_POLICY_VERIFICATION.md)에 보존한다.
v1 inventory의 원래 승인 상태/등록 설명은 [검토 JSON](presentation-review.json)의 `historicalReview` 아래에 원문 값으로 보존한다.
현재 공통 정책 적용 상태는 같은 JSON의 `currentRuntime`과 !369 코드를 기준으로 읽는다.

## 구현·배포·과거 결과의 구분

!369 머지는 코드 조립 상태를 확정한 것이며 실제 배포 이미지·운영 키 연결·설문→BE 저장/조회 검증을 증명하지 않는다.
이 문서 작업은 계산·정책·API·인증이나 BE 저장 결과를 변경하지 않는다.
과거 FAILED 복구·저장 text 교체·공유 서버 배포는 별도 작업이다.
