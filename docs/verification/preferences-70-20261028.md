# 최종 전체 통합 검사 · 2026-10-28

70에 최신 !92 문서 보완2c7b533까지 결합한10d4ae7, target develop c63e5be 기준으로 `./gradlew.bat clean build --console=plain`이 성공했다. **53 suite / 480 tests / 실패0·오류0·skip0**, main/test 컴파일·Checkstyle·bootJar/jar·check/build 전부 통과했다. [suite별 증거](preferences-70-integrated-20261028.json)를 남긴다. 새 전체 앱 순수JPA22표/176컬럼·타입·길이·nullable/FK0와 정적36논리관계/활성67API 검증도 통과했다.

최초 전체480개 중2개는 로그인으로 초기user_setting0이 생기며 키링 상태가SYNC_PENDING이 된 새 연결을 기존fixture가 반영하지 못한 실패였다. 초기0·설정유실을 명확히 분리하고 기기양도 시 이전ACK가 이월되지 않음을 유지했다. 관련키링30개/Checkstyle 확인 후 위 전체480개를 재실행했다. 앞선55개·30개는 중복이므로 합산하지 않는다.

이전 게시9775a2c의 MR!93 CI는 실제 GitLab248188/Jenkins166에서300개 중159개 실패(Hibernate schema validation/ApplicationContext 생성 차단)였다. 현재 통합본의 전체 실행 성공과 구분한다. 원격CI는 이 검증 기록 커밋 게시 후 다시 확인하며 확인 전 성공으로 표시하지 않는다.

176은 기존4API·현재family 로그아웃 연결의 새22표 검증까지다. 사용자 확인상 IoT37 최종등록증명/ACK 서명·검증키/테스트벡터 확정본은 없으며67/100/68/69의 증명·nonce소비/run·당시payload/소유세대 저장설계는 별도 잔여다. 과거DB 전체자료/실기기/외부공급자/배포를 검증한 것이 아니다. 151 Notion은 앱 내부 브라우저에서 확인 중이며 승인된 두문단은 실제 저장 검증 결과를 별도로 기록한다.

---

# !92 통합 단위 · 2026-10-28

기존70(9775a2c)에 !92(35166ea)를 결합했다. MatchingPreference add/add는 canonical 컬럼을 유지하고 희망 나이 변경만 연결했다. 중복 PreferenceUserSetting 엔티티를 없애고 공용 settings.UserSetting 한 모델과 행 잠금으로 통합했다. 기존 users/profile fixture와 신규 is_operator 필수값을 맞췄다.

관련 55개 테스트(실패/오류/skip0)가 통과했고, Checkstyle main/test 경고0·bootJar도 통과했다. 새 실제 전체 앱/순수 JPA/JWT 회귀는 로그인 초기 두 행→약관/프로필→설문/성공분석 fixture→희망조건 GET/PUT→설정0→1→2→ACK 전후 가입/준비→오래된 ETag 거부, 타인403/가입게이트/로그아웃 즉시401을 확인했다. 기기 등록·분석 성공·ACK는 명시적인 DB fixture이며 실기기 증명을 뜻하지 않는다.

첫 선택 실행53개 중2개는 구 fixture의 is_operator 누락으로 실패했고 보완했다. 이후55개는 성공했으며 첫 스타일 검사는 변경 파일의 CRLF로 실패해 LF 정리 후 재검사 통과했다. 전체 clean build·실제 MR CI 최종 결과는 이 단위 이후 확인한다. 이전 MR !93의 실패 원인/결과를 성공으로 덮지 않는다.

---

# 희망 조건 API 개발 기록 · S15P31E106-70

- 기준: develop d3df02a, 작업 브랜치 be-feature/preferences-S15P31E106-70.
- 2026-10-28 사용자 결정: ERD 기준으로 이성만 적용. PUT에서 desiredGenders 입력을 제거하고 나이 3개 필드만 받는다. 상대 성별은 본인 프로필 성별의 반대로 계산하며 저장하지 않는다.
- 첫 변경 단위: 명시적 ANY/RANGE, nullable 나이 키 생략 거부, 최소 만19세·상한 없음·잘못된 순서·문자열/소수 입력 거부, 토큰 회원과 경로 userId 불일치 FORBIDDEN 검증.
- 새로운 DB revision/희망 성별 컬럼을 만들지 않는다. ETag는 user_setting.settings_version에 기반한 opaque strong ETag로 연결한다. LED 등 다른 설정 변경도 이전 ETag를 무효화할 수 있다.
- 기존 A 담당 User/Profile 및 가입 단계 이관151은 이 브랜치에서 변경하지 않는다. 첫 입력 단위 커밋은 `c744a8c`이며 실제 저장·HTTP 연결을 두 번째 변경 단위로 분리했다.
- 첫 단위 검증: JDK21에서 `gradlew test --tests '*PreferencesRulesTest' --tests '*ErrorMessagesTest' checkstyleMain checkstyleTest bootJar` 성공. 규칙16개·공통 오류문구1개(실패/오류/skip0), Checkstyle main/test 경고0, bootJar 생성. 성별 선택 입력을 거부하는 변경 후 재검증한 결과다.
- 미실행: 공유 DB 적용, 전체 통합·실기기/FE·외부 서비스·배포·MR 승인/병합.
- 두 번째 변경: GET/PUT `/api/users/{userId}/preferences`, ERD21 `matching_preference`와 `user_setting` JPA를 연결했다. PUT은 나이 세 필드만 받는다. GET/PUT의 desiredGenders는 본인 등록 프로필 성별의 반대 한 개이며 미선택 나이는 null이다.
- 동시 저장: 공용 `user_setting` 행 잠금 → 현재 strong ETag 재검사 → 나이와 settings_version 1 증가를 같은 트랜잭션에서 수행한다. 읽기도 공유 잠금으로 같은 버전의 조건을 반환한다. 71·101 등 다른 작성자도 같은 행 잠금·번호 증가 규약을 따라야 한다. 다른 회원·잘못된 If-Match·rollback·동시 같은 ETag 저장의 실패는 나이/번호를 함께 보존한다.
- 연동 경계: Controller의 AGREEMENT_REQUIRED는 회원 경로 확인보다 먼저 공통 가입 인터셉터가 실행되는 것을 피하는 진입 표시다. 실제 서비스의 PreferenceMemberAccess는 기존 OnboardingService의 **PREFERENCES_REQUIRED**와 등록 프로필/성별을 다시 검사한다. 인증을 해제하거나 미완료 회원을 통과시키지 않는다. 기존 JWT/세션 필터를 유지했다.
- 최초 행 생성: 아래 후속 변경에서 이70 브랜치의 AuthService.login이 명시적으로 초기화 서비스를 호출하도록 연결했다. GET/PUT은 누락 행을 자동 생성하지 않으며 SERVICE_UNAVAILABLE(503)을 유지한다. `app_users`/`profiles`의151 이관 및 가입 진행 조회의 구 matching_settings 의존 제거는 별도다.
- DB 변경 기록: 새 JPA 두 모델은 이미 병합된 ERD21 schema.sql의 두 테이블을 사용하므로 공식 설계 SQL의 테이블/컬럼을 추가하지 않았다. 구 운영 DB의 이관 SQL·데이터 적용은 이 브랜치에서 실행하지 않았다. 검증은 전용 일회용 PostgreSQL17에서 두 도메인 엔티티만 생성해 수행했다.
- 계약 변경:70의 두 operation과 신규 전용 PreferencesUpdateErd21/PreferencesResponseErd21만 갱신. 기존 공유 Preferences·다른 operation/schema는 구조 비교로 불변 확인. 실제 API 구현 상태와151 통합·기기 연동 완료를 분리했다.
- 최종 단위 검증: `gradlew test --tests '*Preference*Test' --tests '*ErrorMessagesTest' checkstyleMain checkstyleTest bootJar` 성공. **35개**(실제 PostgreSQL10개·MVC5개·규칙16개·가입 단계 포트3개·오류1개), 실패/오류/skip **0**, Checkstyle main/test 경고0, bootJar 통과. 실제 DB 경합·rollback과 MVC 경계를 검사했지만 회원 포트는 대체했으므로 전체 JWT/가입 흐름 통합 완료를 뜻하지 않는다.
- 전체 서버 통합 한계: 부모의176 브랜치 전체 빌드는253개 중159개가 기존 `app_users`↔ERD21 `users` 매핑 불일치로 실패했다. 이를 이 브랜치의 실행 결과로 혼용하지 않는다. 본 브랜치에서는 같은 원인을 재실행하기 위한 전체 build를 반복하지 않았고,151 통합과 최신 merged-result 전체 검증을 남긴다.
- 다음 행동:151의 회원/프로필 매핑과 preferencesSet·ready 판정을 함께 ERD21에 연결한 뒤 실제 JWT/가입/기기 흐름을 통합 검증한다.

## 후속 연결 · 로그인에서 최초 행 생성

-151 원격 소스 `f5ef7f3` 확인 결과: API 경로/설문 숫자ID 변경은 있지만 User는 `app_users`, Profile은 `profiles`, 설문은 `survey_responses`/revision, MemberAccessRepository의 희망 조건/준비 판정은 구 `matching_settings`와 스냅샷·sync/binding을 사용한다. 이 소스와70 기준의 AuthService.login 본문은 같았고 초기 설정 행 생성 호출이 없었다.
-70 범위에서 `PreferenceProvisioningService.initializeForMember`와 활성 회원 잠금 포트를 추가하고, **이70 브랜치**의 AuthService.login에서 실제 회원 조회 후 토큰 발급 전에 호출하도록 최소 연결했다.151 브랜치/다른 작업폴더, User/Profile/설문 매핑 및 공통 준비 SQL은 수정하지 않았다.
- 초기 값은 ERD21 주석의 matching_enabled=true, photo_required=false, 진동/LED=true, age_preference_mode/min/max=NULL을 사용한다. settings_version은 기존 Settings 계약의0부터 시작한다. 초기화는 사용자 선택/희망 조건 완료·설정 변경·기기 ACK가 아니므로 버전을 올리거나 READY를 만들지 않는다.
- 두 `INSERT ... ON CONFLICT (user_id) DO NOTHING`을 같은 트랜잭션에서 수행한다. 실제 활성 회원을 먼저 잠그고 없는 행만 생성한다. 재로그인/경합/부분 누락에도 기존 희망 나이·매칭/사진 옵션·진동/LED·설정 버전을 덮지 않는다. 두 번째 SQL이 실패하면 첫 번째 INSERT도 rollback한다. GET에 초기화 부작용을 넣지 않았다.
- 이번 검증 명령: `gradlew test --tests '*PreferenceProvisioningAccessTest' --tests '*PreferencesPersistenceTest' --tests '*PreferenceLoginConnectionTest' checkstyleMain checkstyleTest bootJar`. 영향 범위 **24개**(기존/추가 실제PG17, 로그인 연결4, 회원 접근3) 모두 실패/오류/skip0. Checkstyle의 import/줄 길이6개를 고친 뒤 `checkstyleMain checkstyleTest bootJar` 재검증 성공. 스타일 수정은 import/줄바꿈뿐이며 테스트 동작을 변경하지 않았다.
- 로그인 연결 테스트는 실제 AuthService·현재 User/Profile JPA·초기화 서비스·희망 조건 HTTP/JPA를 일회용 PostgreSQL17에서 연결했다. 외부 소셜 검증·토큰 발급·A 가입 단계 판정은 대체했다. 이 결과는151의 users/profile 이관, 실제 OAuth/JWT/세션·가입 진행·READY·기기 ACK의 통합 성공 증거가 아니다.
- 남은 공통 연결: `MemberAccessRepository.preferencesSet`만 새 age_mode 판정으로 바꾸면 statusOf가 아직 구 테이블 기반인 ready로 진입해 SQL 오류가 날 수 있다. 따라서 희망 조건 완료 판정과 준비/ACK 판정은151·176·178의 합의된 저장 계약으로 함께 연결해야 한다. 이번 단위에서는 두 함수를 임의 변경하지 않았다.
- 공유/운영 DB 적용·push·MR 생성·리뷰/병합·배포는 실행하지 않았다. 실제 DB 검증은 매번 전용 일회용 컨테이너에서만 수행했다.

## 설정 세대 유실 보호 수정

- 후속 연결 커밋 `acc554a` 이후, 기존 희망 조건만 남고 user_setting이 유실됐을 때 버전0을 생성하는 경계를 바로잡았다. 같은 활성 회원 행 잠금 아래 가입 완료 기록과 두 행의 존재를 먼저 확인한다.
- **기존 matching_preference 존재 + user_setting 없음**은503으로 차단·rollback한다. 원래 희망 나이·사진/매칭 옵션은 보존하며 과거 설정 버전을 추측하거나0으로 재시작하지 않는다. 이 실패 경로에서는 로그인 토큰을 발급하지 않는다.
- **가입 완료 기록 존재 + 두 행 모두 없음**도 신규 회원으로 취급하지 않고503을 반환한다. 구 matching_settings·스냅샷·기존 ACK의 값과 세대를 이 초기화가 이관한 것처럼 표현하지 않는다. 기존 회원의 이관은 명시적인 데이터/세대 검증이 필요하다.
- **user_setting 존재 + matching_preference 없음**은 기존 설정 번호와 기기 옵션을 유지하며 나이 미선택(NULL)인 희망 조건 행만 생성한다. 어떤 경우도 기본 생성만으로 사용자 선택 완료·기기 반영·READY를 만들지 않는다.
- 영향 검증 **29개**: 실제PG 상태/경합/rollback19, 로그인 연결6, 활성 회원 잠금/가입 완료 표지4. 실패/오류/skip0. Checkstyle main/test 경고0·bootJar 통과. 회원 없는 격리PG 테스트에서는 잠금 포트만 PG 트랜잭션 잠금으로 대체하며, 로그인 연결 테스트는 현재 User JPA를 실제 사용한다.
- 실제 OAuth/JWT·A151 ERD21 매핑·가입 단계/준비 판정·기기 ACK·배포는 여전히 미검증이다. 다음 행동은 앞서 기록한151 공통 판정/매핑 연결을 진행하는 것이다.
