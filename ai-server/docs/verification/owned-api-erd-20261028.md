# 영빈 담당 API ERD21 로컬 정합화 검증

2026-10-28 / 담당 김영빈 / S15P31E106-91·92. 기존 승인 작업 브랜치에서 문서 계약만 정합화했다.

## 변경 범위

- 담당40개 operation: 설문·분석/job13개, 채팅11개, 만남·제안·내부AI·동의·신고16개. 활성36개와 제외4개로 정리했다. 전체 활성은67개, 기존11개를 포함한 제외는15개다.
- 제외는 설문 metadata, 공용 jobs, CM09 신고, CM12 수락 취소다. x-excluded-operations에 후속/과거 계약을 보존한다. metadata의 옛 응답은 HistoricalSurveySession/Answer/SurveyManifest로 분리해 당시 예시가 현재 DTO를 오염시키지 않게 했다.
- 다른담당27개 operation·관련스키마와 기존제외11개 원문을 보존했다. 희망조건/설정4개만 전달된 경로 /api/users/{userId}/{preferences|settings}, 필수 int64 userId, 토큰주체불일치403 FORBIDDEN으로 교정했다.
- 공유 Manifest11필드 원형·인증security·기존권한/기한HTTP상태·x-change-history를 보존한다. AI설명문서 §4~7의 가상사례·프롬프트·GMS실행결과 원문은 동일하다.

## 재현

Python과 jsonschema/openapi-spec-validator가 준비된 환경에서 저장소 루트 기준 실행한다. 의존성 경로는 이미 설치된 디렉터리를 지정하며 스크립트는 다운로드하지 않는다. 기준 커밋은 OpenAPI 통합 직전80d3ede다. 검증은 이 날짜의 JSON만 갱신하며 이전27일 증거를 덮어쓰지 않는다.

```text
python docs/verification/verify_owned_api_erd21.py --baseline 80d3ede --dependencies <existing-package-directory>
```

[재현 검증 스크립트](verify_owned_api_erd21.py) · [기계 판독 결과](owned-api-erd-20261028.json) · [OpenAPI](../contracts/api/openapi.json)

## 검사 범위와 결과

OpenAPI3.1 형식, 모든 로컬참조, 스키마/미디어 예시, 오류입력 거절, 경로변수·필수값·operationId·경로충돌, 오류코드/fields, 본인권한/기한 상태 보존, 다른담당 operation 및 재귀공유스키마 불변, Markdown링크·표열수, 실험원문 동일성을 검사한다. 최종 수치와 PASS여부는 기계 판독 결과를 따른다.

- OpenAPI 3.1 형식 PASS, 로컬 참조593개와 스키마/미디어 예시605개 검증 PASS. 오류 입력5개 거절, 경로 변수45개 정합, 중복 operationId·경로 충돌0개다.
- 다른 담당27개 operation과 허용된4개 경로 보완 범위, 보호 대상 공유 스키마46개와 Manifest11필드·기존 제외11개 원문 보존을 확인했다.
- 담당 API의 인증·권한·기한 HTTP 상태와 기존 오류 코드·변경 이력을 보존했다. 제안 예시의24시간과 보수적인 NOT_STORED 접수 예시도 확인했다.
- 로컬 Markdown링크50개·표317행 정합과 AI 실험 §4~7 원문 동일성을 확인했다. `git diff --check`도 통과했다.

ERD별도검증은 [21개 테이블 구조 기록](erd21-alignment-20261028.json)의21테이블·151컬럼·35논리관계다. 이는 실제DB적용 증거가 아니다.

검증 중 reads 요청 예시에 남은 UUID를 저장본의 숫자8192로 교정했고, 제안목록 serverTime 필수값을 복원했다. 신고는 현재MVP제외로 처리하고 미래예시를 실제접수성공으로 표시하지 않는다.

## 미합의와 실행 경계

- 동의는 users.agreed_at만으로 선택동의·철회·문구버전·이력 복원 불가. enabled DTO는 유지하되 독립저장·정책승인 전 REVIEW_REQUIRED/구현차단이며 일괄동의에서 true를 추정하지 않는다.
- 만남 receipt·과거소유/설정세대·재전송 증거, 입력snapshot/release UUID와 서버파일 매핑, 요청키 영속판정, AI선점/실행세대·5분기산점·횟수·늦은결과는 합의 전이다. 현재설정으로 과거입력을 대체하지 않는다.
- 기존30일/자동만료 재매칭과 ERD의 PENDING 이후영구제한 충돌은 제품합의전이다. 기획원문·판정수식·모델정책·기존신고자료를 변경하지 않는다.
- 노션 저장·재조회는 별도 브라우저 기록을 따른다. 이 스크립트는 원격 노션저장을 검증하지 않는다.
- 서버빌드/테스트·CI·실물/외부AI·실DB적용·배포는 수행하지 않았다. 문서 검증을 구현완료로 보고하지 않는다.
