# API·DB 정합화 적용 기준

2026-10-21 사용자 승인에 따른 로컬 통합 수정. 기준 develop: 1485656dd0b4c7777be704f1023c05caa4abb18c.
공식 공통 API는 openapi.json, DB는 docs/database/schema.sql·tables.json을 사용한다. 사용자 승인과 팀 전체 승인을 구분한다. 노션 API·ERDCloud 웹 저장 반영은 2026-10-22 재검증했다. 원격 푸시·병합·공용 DB 적용·배포 완료를 뜻하지 않는다. 기능 명세 작업본과 과거 핸드오프는 수정하지 않는다.

| 항목 | 적용 기준 |
| --- | --- |
| 닉네임 | NFC → 앞뒤 U+0020 제거 → 완성형 한글·영문·숫자·일반 공백 2~20자. 독립 자모 단독·혼합 모두 거부. 중복 확인 형식 오류는200 available=false/INVALID_FORMAT 유지 |
| 이메일 | nullable text·비고유 연락처. PRIVACY 동의 후 로그인부터 수집/갱신, 소셜 미제공이면NULL. 이메일 없이도 로그인. 계정 식별은 provider+subject, 응답에는 이메일 제외 |
| 생활 정보 | 공통 OpenAPI의 ONLY_WHEN_DRINKING 및 긴 음주 코드 사용 |
| 프로필 응답·입력 | 필수 nullable 키 유지. 미정의 PATCH 필드는400 INVALID_REQUEST. 기존 정규화·검증 길이는 유지 |
| DB | 물리 FK 없음. 문자열 저장은 공식 DDL text로 맞추며 업무 길이 검증과 분리. 기존 공유 DB는 변경하지 않음 |
| 프로필 잠금 | 프로필 completed 이후 gender/birthDate 변경은422 PROFILE_FIELD_LOCKED. 동일 값은 허용. 전체 가입 READY와 구분 |
| 토큰 | 현재 인증 구현의 새6필드·sid=family·access30분/refresh14일·30초 유실 재시도 및 폐기 범위 유지. ACCESS_TOKEN_EXPIRED만 자동 갱신1회, 위조/폐기는 재로그인 |
| 앱 호환 | 이 develop의 앱/웹 소스에 구형 accessTokenExpiresIn/signupCompleted 및 인증 API 연결은 발견되지 않음. 구형 응답을 새로 추가하지 않고 실앱 연동 시 별도 확인 |
| 제안 | 숫자 proposalId·수락/거절 최초 선택 키2컬럼·기존 멱등 처리 유지 |

검증은 OpenAPI/SQL 파싱, 저장소 설계 간 구조 대조, 컴파일·Checkstyle·단위/일회용 PostgreSQL 통합 테스트로 구분한다. 실제 소셜 공급자·앱·기기·공용 DB 검증은 별도다. 가입 상세의 설문 진행도·설정 버전 임시값과 채팅 후속 작업은 이 범위에서 구현하지 않는다.

## 적용 이력

- 2026-10-21: develop 1485656 기준으로 현재 구현과 사용자 승인 기준을 대조하고 로컬 정합화·118개 테스트를 검증했다.
- 2026-10-22: 노션 API78개 Export와 ERDCloud24테이블165컬럼 저장본을 재검증했다. [근거·제한·후속 대조 절차](../../verification/spec-alignment-20261022.md)를 함께 보존한다.
- 기능 명세가 바뀌어도 이 승인 기록을 소급해서 덮어쓰지 않는다. 새 변경의 필드·상태·호환성 영향을 확인하고 별도 변경 이력을 추가한다.
