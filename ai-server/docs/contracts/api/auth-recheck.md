# 앱 인증·토큰 정합화 상태

2026-10-21. develop1485656의 병합된 인증·제안 구현을 다시 확인하고 [사용자 승인 정합화 기준](alignment-20261021.md)을 적용했다. 노션 API·ERDCloud 저장 반영은 2026-10-22 재검증했다([검증 이력](../../verification/spec-alignment-20261022.md)). 팀 전체 승인·실제 앱/공급자 연동·공용 DB 적용·배포는 별도다.

| 이전 항목 | 현재 로컬 적용 기준 |
| --- | --- |
| AUTH-OPEN-01 | 새 토큰6필드를 유지. 현재 앱/웹 소스에 구형 필드 사용을 찾지 못했으며 실앱 호환은 후속 검증 |
| AUTH-OPEN-02 | 프로필 completed 후 gender/birthDate 변경은422 PROFILE_FIELD_LOCKED, 동일 값 허용. 전체 READY와 구분 |
| AUTH-OPEN-03 | 확정: sid=refresh family·30초 유실 재시도·로그아웃 해당 family·탈취 전체 family 폐기 |
| AUTH-OPEN-04 | ACCESS_TOKEN_EXPIRED만 갱신1회, ACCESS_TOKEN_INVALID/SESSION_REVOKED는 재로그인. 현재 서버 구현 기준 유지 |
| AUTH-OPEN-05 | 이메일 nullable text·동의 후 연락처, 물리 FK 없음·DDL text, 공통 생활정보 코드로 정렬 |
| AUTH-OPEN-06 | 실제 DB 기반 가입7단계와 nullable 응답·unknown 거부는 최신 구현에 존재. 가입 상세의 설문/설정 필드는 여전히 임시값 |

기존 조사 당시의 ‘약관·프로필만으로 READY’, ProfileResponse NON_NULL, PATCH unknown 무시, profiles 물리 FK, 축약 생활정보 코드는 이전 소스에 대한 기록이다. 현재 병합된 구현의 결함으로 재보고하지 않는다.

남은 검증: 실제 앱에서 새 토큰·401 분기를 적용하는지, 실제 공급자의 이메일 제공/동의, 가입 상세의 설문·설정값 연결, 채팅 생성 후속 작업. 명세 변경만으로 이 항목을 완료 처리하지 않는다.

이전 MR14/16 조사 당시의 확인 사항은 [당시 원문](../../verification/spec-alignment-20261022/previous-auth-recheck.txt)으로 보존했다. 현재 상태를 설명하는 문서와 구분한다.
