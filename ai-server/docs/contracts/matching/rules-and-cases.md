# 두 사람 매칭 규칙과 최소 사례

| 항목 | 내용 |
| --- | --- |
| 버전·작성일 | v0.1.7-draft 후보 / 변경일 2026-10-26 (Asia/Seoul). 최초 작성 2026-10-21. Q87 실제 시간 불일치 우선순위·재현 사례와 fixture EOF LF 정정 |
| 관련 Story | S15P31E106-36 |
| 계약 담당 | 황성현(AI): 매칭 알고리즘·정책 대조와 -36 계약·검증. 실제 오프라인 키링 계산은 확정 요구이며 AI·BE·IoT 역할은 §7에 구분. 상세 운영 모듈·어댑터/이식 소유자·서버 연결은 후속 합의 |
| 영향·검토 파트 | 이재준(PM·FE), 김영빈·가을(BE), 김종무(IOT), 관련 FE |
| 상태 | 계약·사례 최종 검토 후보. 2026-10-26 PM 승인 정책은 §1.4에 기록. 50점/coverage 70% 최종 제품 승인·신규 정책 구현·통합/실기기 검증·MR 승인/병합·-36 전체 완료는 별도 |
| 이번 변경 범위 | 이 문서와 `fixtures/mvp-policy-20261026-cases.json` 두 파일. fixture에는 EOF LF 한 개만 추가하며 기존 27개 ID·입력·기대값·관찰·NOT_IMPLEMENTED·실행 이력을 보존한다. Q87 재현 사례는 §5.8에만 추가한다. 검증 후 기존 -36 브랜치에만 커밋·일반 push하며 develop merge/rebase·강제 push·MR 승인/병합·Jira 변경은 금지. 기존 설문·engine100·BE·FE·IoT·FastAPI·-46·개인 분석 -142 및 로컬 원본은 변경하지 않음 |

**현재 정책의 읽기 순서:** §1.4의 PM 승인 결정 → §4·§6의 기본 조건/생활정보 계약 → §5.7의 신규 기대값 → §7~8의 후속 책임·결정이다. §5.1~5.6의 31개 사례, §3.3~3.4의 SVC 4건과 §6.1의 REASON 1건은 이전 버전의 검토 자료로 원문 보존한다. 그 안의 Q97 age·과거 POL-01 미정 표현은 §5.7의 대조를 적용하며 신규 설문 허용값으로 읽지 않는다. §9.1~9.13은 과거 단계의 작업/실행 이력이며 당시 미정·원본 동일 설명을 현재 정책/fixture 상태로 읽지 않는다. Q97·Q98 확정 정책 반영은 §9.13, 이번 Q87 우선순위·fixture EOF 정정과 검증은 §5.8·§9.14를 따른다.

## 1. 범위와 기준 자료

두 사람의 실제 특징, 상대 선호, 기본 조건을 받아 방향별 비교와 쌍 판정을 설명한다. 41·42번 규칙·목업 인계 이력과 완료 응답/개인 결과 연결을 보존하되, 현재 개인 분석 역할은 BE 리뷰(R)에 따라 AI 서버/황성현의 계산 제공과 BE/가을의 접수·호출·저장·조회로 정정한다(§7). 기존 pair의 원응답 입력과는 §2에서 구분한다. 이전 대조의 -64 연결 기록은 §7에 출처를 구분해 보존한다. 검사 결과의 참고 유형 3개는 실제 사람의 양방향 매칭 결과가 아니다.

BE 인계 묶음 **독립 JS 목업의 계산 코드와 기존 테스트 4개가 전달됐고, 앞선 재현 단계에서 네 명령 모두 종료 코드 0을 확인했다.** 인계 추가 커밋은 `954f5fb3ad25c5e8e8b429331d43fd9e053c736c`, 실행 스냅샷은 `fe76ce902422cc218998f7ac73c8d4175c069703`이다. 실제 `pair` 입출력도 확인했으며 §9.2~9.3에 기록한다. 따라서 계산 코드가 없다는 전제와 무조건 신규 엔진 구현을 출발점으로 삼지 않는다. 전달된 코드는 브라우저 로컬 목업이며 서비스 엔진·DB·앱·키링 연동 완료와 다르다. 나이 추가판의 사용이나 테스트 통과를 Q97·생활정보 등 제품 정책 전체의 승인으로 확대하지 않는다.

표시를 다음과 같이 구분한다. **제품 확정**은 F/P의 유지된 결정과 사용자 전달 PM26(2026-10-26) 승인 결정이며, 생활정보·Q97 충돌에는 PM26이 우선한다. **리뷰 결정**은 사용자가 전달한 R의 BE 결정, **통합 명세 정의**는 O/B에 선언된 API·저장 스키마, **문서 규칙**은 Q/S가 설명한 기존 나이 추가판 동작, **확인된 목업 동작**은 M 및 T26의 기존 코드 관찰, **서비스 계약 제안**은 추가 연결·상태 제안, **미정**은 아직 합의하지 않은 구현/제품 세부사항이다. 통합 명세 정의·산술 검산·신규 정책 기대값과 실제 구현/DB 적용/통합 검증을 구분한다.

### 1.1 출처와 실제 읽은 범위

| 표기 | 문서 경로·고정 참조 | 확인 시점·범위·사용 목적 |
| --- | --- | --- |
| Q | [docs/survey-analysis/personality-survey-v1.md](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fa1235698ab2b64552a60779350b74c5c3b251c4/docs/survey-analysis/personality-survey-v1.md#L9) | 프로젝트 커밋 `fa1235698ab2b64552a60779350b74c5c3b251c4`. 1~31행, 85~169행: 버전·응답 상태, 실제 생활 특징, Q61~100 선호/설정, 선택적 나이, 정책 차이 |
| S | [docs/survey-analysis/personality-scoring-v1.md](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fa1235698ab2b64552a60779350b74c5c3b251c4/docs/survey-analysis/personality-scoring-v1.md#L146) | 같은 프로젝트 커밋. 1~44행, 146~205행, 211~238행: 개인 계산 경계, 참고 유형/실제 쌍 비교, 방향별 공식·조건·과거 검증과 한계 |
| P0 | [초안 작성 당시 정책](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/a71389944357dfca1412095e646fd6293a4e9266/docs/contracts/matching/profile-policy-v1.md#L18) | 당시 로컬 참조의 고정 커밋 `a71389944357dfca1412095e646fd6293a4e9266` / `v0.1.14-draft`. 1~84행, 177~188행, 200~210행, 265~269행, 392~448행, 452~476행을 읽었던 과거 근거. 현재 정책 출처는 아래 P로 정정 |
| F | [docs/specifications/functional-spec-v1.md](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/ea74cc53f4cdd5e600b30cbe96100d192eea0773/docs/specifications/functional-spec-v1.md#L503) | 고정 develop `ea74cc53f4cdd5e600b30cbe96100d192eea0773` / `v0.1.81-draft`, 문서 날짜 2026-10-21. 앞선 확인 범위는 §2.2 123~132행, KEY-02 326~330행, SUR-01~03 503~529행, MAT-01 536~544행·MAT-02 551~557행·MAT-03 563~570행, POL01 1156행·DES04~07 1176~1179행. 이번에는 상위 게이트의 MAT-03 563~568행·MAT-05 587~592행·SAFE-01 841·848행만 추가 대조. 개인 분석 역할은 R로 정정 |
| P | [docs/contracts/matching/profile-policy-v1.md](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/ea74cc53f4cdd5e600b30cbe96100d192eea0773/docs/contracts/matching/profile-policy-v1.md#L89) | F와 같은 고정 develop / `v0.1.92-draft`, 문서 날짜 2026-10-21. 앞선 확인 범위는 §1 31~35행, §2 64~71·89~106행, 사진 175~179·189~200·204~210행, 성별 232~236행, 재검사 344~350행, 기기별 준비 547~551행, 역할 1000~1002행. 이번에는 §4.2 516~518·547~551행, §5 755~759행만 상위 게이트와 대조 |
| V | [설문·계산·분석 버전 파일 계약](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/ea74cc53f4cdd5e600b30cbe96100d192eea0773/docs/contracts/survey/versioning.md#L9) | F/P와 같은 고정 develop. 앞선 대조에서 확인한 9~15행만 재확인: 불변 파일 묶음·완료 응답/개인 결과/스냅샷·외부 ID 대응 미정·legacy80→100 임의 매핑 금지. §3.4의 필드 근거이며 확정 API로 간주하지 않음 |
| H | `docs/survey-analysis/survey-analysis-history.md`의 같은 `fa1235698ab2b64552a60779350b74c5c3b251c4` 버전 | §1~7, 특히 H16·H17과 게시 기록: 나이 추가 및 참고 유형 분리의 변경 맥락 |
| 기존 대조 기록 | 2026-10-21의 「대조표 작성 및 계약 차이 분석」 결과와 사용자 제공 배경 | 초안 작성 당시 -42/-64 결과 저장·-74 연동·POL-01·과거 ZIP 12개 게이트의 역할만 이어받음. 당시 Jira·PDF·ZIP 재조회와 테스트 재실행은 하지 않음 |
| M | [인계 README](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fe76ce902422cc218998f7ac73c8d4175c069703/docs/handoffs/survey-20261021/README.md), [목업 README](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fe76ce902422cc218998f7ac73c8d4175c069703/docs/handoffs/survey-20261021/mockup/README.md), [engine100.mjs](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fe76ce902422cc218998f7ac73c8d4175c069703/docs/handoffs/survey-20261021/mockup/dist/engine100.mjs#L19) | 앞선 재현 단계에서 인계 README → 인계 문항 정의서 → 계산 정의서 → 코드·테스트 순으로 확인. 실제 `pair/direction`, `data100.mjs`의 문항 검증, `age100.mjs`의 나이 검사, 기존 테스트 4개의 실행 근거. 이번 정정에서는 기존 기록을 사용하며 재실행하지 않음 |
| D | [POLICY-DECISIONS.md](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fe76ce902422cc218998f7ac73c8d4175c069703/docs/handoffs/survey-20261021/POLICY-DECISIONS.md)와 인계 문항 정의서의 정책 결정 목록 | D01~D10 추천안 전체를 승인한 것은 아님. F/P에서 해결된 제품 정책은 우선 적용하고 Q97 연결·생활조건 적용·상세 입력/버전 등 실제 남은 범위만 §8에 유지 |
| E | [만남·제안 상위 계약](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/6717a1d702f8fa1508df5a4057dc3aea96955466/docs/contracts/api/encounters-proposals.md#L63) | -36 수정 전 고정 커밋. §4 67~70행의 양쪽 이벤트·기한·관계 검증 후 제안 생성, §5 87~100행의 당시 설정/판정 버전·제출 증명 검증만 확인. 세부 미정이나 과거 정책 표현을 새 확정 정책으로 가져오지 않음 |
| R | 사용자가 전달한 -36 Draft MR 리뷰 요지, 2026-10-22 | 계산/상위 자격 구분과 BE 최신 상태 재검증, 설문 `version_code`, `response_json` 경로, 외부 ID 도입 예정, 개인 분석 역할에 대한 답변만 반영. 개인 분석 입출력 계약 -127·호출/저장 -115·결과 조회 -116, 쌍 비교 입력/실행/버전 -36·BE -74, IoT 동기화 -37에 연결. 통합 명세 수정·구현 완료 확인은 아님 |
| O | [최신 통합 OpenAPI](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/c90e57090c81b14adc95c8176916235fa1054ef4/docs/contracts/api/openapi.json#L24768) | 2026-10-23 fetch 후 고정한 `origin/develop` SHA `c90e57090c81b14adc95c8176916235fa1054ef4`를 `git show`로 직접 확인. `POST /internal/v1/matching/evaluations`·`EvaluationInput`·`Evaluation`·`Error`·`Manifest`·`SurveyMetadata`·`SurveySession`·`SurveyManifest`의 필드/required/null 허용/타입·응답 상태를 대조. 평가 API의 `x-contract-status=REVIEW_REQUIRED`와 확인 항목도 유지하며 런타임 구현 완료 근거로 쓰지 않음 |
| B | [최신 DB 스키마](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/c90e57090c81b14adc95c8176916235fa1054ef4/docs/database/schema.sql#L125) | O와 같은 고정 SHA. `survey_responses` 125~139행·`analysis_results` 142~152행·`settings_snapshots` 155~166행·`encounters` 182~197행을 직접 확인. `public_id`·`response_json`·버전·평가 저장 컬럼과 제약의 정의이며 실제 DB 적용 확인은 아님 |

Q/S의 Git blob은 기존 대조에서 사용한 프로젝트 커밋 `4c0ebab59653e9c146b7d495bfa6166d0e855a0d`와 각각 동일함을 확인했다. 문서 기준은 `SIS-Q-1.1-100`, `SIS-WORDING-20261020-1`, `SIS-AGE-1`이며, 참고 유형 분리 `SIS-TYPE-REF-1`도 반영된 자료다. Q의 v14·S/H의 v15 표기는 게시 이력이며 별도 설문 버전으로 섞지 않는다. Sites 소스 식별값 자체를 현재 프로젝트 커밋이나 전달 파일의 원본성 근거로 대신하지 않는다. 이번에 전달된 실행 코드의 근거는 M의 고정 프로젝트 커밋이다.

초안 최초 작성 시작 상태는 `ai-chore/fastapi-bootstrap-S15P31E106-51` / HEAD `d30674ad3e69bfff80a9553f937f2ffbf1e5d141`, 변경 파일 없음이었다. 당시 작업 트리에 대상 파일과 `docs/survey-analysis/`가 없어 위 커밋을 `git show`로 읽었다. 당시 작업 브랜치의 P는 v0.1.12이므로 최신 기준으로 사용하지 않았다. 이 초안 작성 당시에는 자료 복사·브랜치 전환·fetch/pull 없이 로컬 참조만 확인했다. 이후 앞선 재현 단계에서는 2026-10-21 약 15:18 KST에 서버 develop을 읽기 조회해 실행 스냅샷 SHA를 확인하고, 브랜치·원격 추적 참조를 바꾸지 않는 명시적 SHA 객체 수신과 별도 폴더 추출로 실행했다. 로컬 `origin/develop`의 `a713899…`를 서버 최신 상태로 표시하지 않는다. v0.1.3 정정에서는 원격 조회·객체 수신·브랜치 전환을 하지 않았다. 이번 v0.1.4 정정은 깨끗한 Docker 브랜치에서 기존 -36 브랜치로 전환하고 `git fetch --no-tags origin develop`으로 받은 O/B를 고정 SHA에서 읽었다(§9.7). UTF-8·LF 및 [공통 명세 컨벤션](../../../../docs/convention/gitlab-convention.md#공통-명세는-저장소에서-버전과-변경-영향을-함께-관리한다)을 따른다.

### 1.2 최신 기준과 과거 참조

| 자료 | 이번 계약에서의 위치 |
| --- | --- |
| 나이 추가 Q/S | 입력 의미·방향별 비교·나이 메타데이터의 기준. 제품 정책과 충돌하는 부분은 §4·§8에 남김 |
| F/P의 제품 확정 항목 | 반대 성별 자동 산출, 가입 만 19세 이상·상한 없음, 항상 양방향 기본 나이 검사, 사진 조건, 기존 제안 유지·기기별 독립 준비. F/P에 남은 v8 계산 참조와 구분 |
| 과거 PDF `Suddenly_I_See_문항과_매칭엔진_전달서_20261020.pdf` | 이전 전달 스냅샷의 설명 자료. 최신 나이 추가 규칙이나 실행 엔진의 존재를 증명하는 자료로 사용하지 않음 |
| 과거 `matching_contract_20261020_draft.zip` | 이전 계약 초안·가상 점수 게이트의 참조. 수락 후 프로필 공개, max=null을 미확인으로 처리한 부분을 현 계약에 가져오지 않음 |
| v8·12개 정보·임시 60점 | 기존 계약이 구분했던 과거 계산 참조. 최신 F/P에도 v8·80문항 임시 기준이 남아 있으므로 단순 오타로 폐기하지 않음. 이번 나이 추가판의 35개 선호·9영역 비교와 섞거나 병행 판정하지 않음 |

### 1.3 v8/80문항과 100문항의 대응 경계

이 계약의 **100문항·나이 추가판 검토 선택은 유지**한다. R의 BE 결정에 따라 설문 입력 `survey_responses.version_code`는 **`SIS-Q-1.1-100` 사용으로 해결**한다. O의 `SurveyManifest.surveyVersion`은 이 DB 컬럼에 저장하는 값으로 이미 정의돼 있고 예시도 `SIS-Q-1.1-100`이다. 고정 F의 SUR-01(503행)·MAT-03(565행), P의 32·68행에는 v8/80문항 임시 참조가 남아 있으나, 설문용 통합 명세 반영까지 미정·수정 예정으로 두지 않는다. 설문 버전 결정이 개인/쌍 계산 규칙·설정 버전과 불변 파일 묶음의 대응 또는 런타임 적용까지 확정한 것은 아니다. 남은 대응은 §3.4·VERSION-01에서 개인 분석 입출력/저장 계약 -127/-115와 쌍 비교 -36·BE -74로 구분하고 80문항을 같은 순번의 100문항에 자동 대응시키지 않는다(V 15행).

위 설문 값 결정은 기존 응답에 대한 이력이다. PM26 적용용 신규 설문 값까지 `SIS-Q-1.1-100`으로 확정한 것이 아니다. 생활정보 비반영이라는 F/P의 이전 목표는 **PM26에서 일반 선호 점수 반영 및 선택형 엄격 제외로 변경**됐다. 기존 Q89~91 계산을 제거하지 않으며, 실제값 출처·코드 차이·신규 버전/전환 시점은 §6.2·§8에서 구분한다.

### 1.4 PM26 신규 결정 이력과 우선순위 (2026-10-26)

승인 출처는 사용자 지시·앞선 「Tingle 매칭 정책 구현 계획 작성」 채팅과 사용자가 제공한 **「성현 매칭 계산 계약 검토 — 2026-10-26」 C01~C06 원문**, 이번 **PM Q97·Q98 최종 확정 및 R1/R2 확인 전달**이다. PM의 제품 기준은 **정책 v0.1.121·기능 명세 v0.1.114**이며, 이전 같은 날의 생활정보 비반영 방향을 대체했다. 이 두 버전은 사용자 제공 검토 원문의 식별값이며 해당 전문/커밋을 이번에 확보한 것은 아니다. 아래 f5224c9의 P/F는 이전 v0.1.92/v0.1.81로 구분한다. 앞선 C01~C06 검토 대상은 v0.1.4이고 R1/R2 반영 기준은 `949eb00bd8e69817a7998d0c1ed2009477ba0428`이다. 확정 정책 반영과 **이번 v0.1.7 후보 자체의 최종 검토·MR 승인/병합**은 구분한다.

[MR !32](https://lab.ssafy.com/s15-final/S15P31E106/-/merge_requests/32)의 기존 [v0.1.4 계약](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/c28761da4ea0b19ab44d8990e015a757b3b411fa/docs/contracts/matching/rules-and-cases.md), `6717a1d → 6446acf → c28761d`의 계약 수정 이력 및 §9.1~9.7을 보존한다. MR 푸시 사실은 사용자 배경을 따르며 이번에는 MR 상태를 변경하지 않았다.

| 항목 | PM26 승인 내용 | 기존 엔진/명세와 차이 및 적용 조건 |
| --- | --- | --- |
| 생활정보 일반 점수 | 흡연·음주·종교를 일반 선호 점수에 반영 | 기존 Q90/Q89/Q91 ↔ 상대 Q55/Q54/Q56 계산은 재사용 후보. F/P·Jira의 비반영/제거 목표는 이전 결정으로 보존 |
| Q97 엄격 제외 | `smoking/children/religion/tattoo`, 최대 2개 | 선택한 항목의 구체 수용 범위 밖이면 높은 점수와 무관하게 제외. 연결 허용 범위(smoking→Q90, children→Q93, religion→Q91, tattoo→Q95)가 any/unknown/미응답이면 범위를 재선택하거나 해당 엄격 제외 선택을 해제해야 하며 해결 전 신규 응답의 매칭 결과를 활성화하지 않음 |
| Q97 any/unknown/미응답 | `any`는 명시적 엄격 제외 없음으로 일반 매칭 진행. `unknown` 또는 미응답은 사용자 재확인 전 신규 응답 적용 불가 | any도 기본 조건·점수·coverage 검사를 받음. unknown/미응답을 any로 자동 치환하지 않음 |
| Q98 | `close/some/wide` 중 명시적 선택 필수. unknown/미응답은 재확인 | 신규 매칭에서 unknown/누락→some fallback 금지. 기존 fallback은 과거 버전 재현 기록으로만 보존(§3.5) |
| 신규 응답·기존 결과 | 기존 유효 결과가 없으면 신규 검증·계산·저장 성공 전 매칭 보류. 기존 유효 결과가 있으면 이전 결과로 매칭 유지 | 기존 제안·채팅 유지. 신규 응답은 검증·계산·저장 성공 후 새 유효 결과로 교체하며 기기별 설정/규칙 적용 확인·BE 최신 제한 재검증 유지(§1.5·§8) |
| 음주 | 일반 점수에만 반영, 음주 엄격 제외 없음 | `drinking`은 기존/신규 모두 Q97 허용 코드가 아님. 음주 감점으로 방향 점수 미달이 되는 것은 가능 |
| 나이 | 신규 Q97에서 `age` 제거. 양방향 기본 나이 검사 항상 적용 | 기존 엔진의 ‘Q97 age 선택 방향만 검사’와 다름. 나이는 점수·Q97 선택 수에 넣지 않음 |
| 유지할 기본 자격 | 만 19세 이상·상한 없음, 반대 성별 자동 대상, 양방향 희망 나이·사진 요구 조건 | 사진 등록은 선택이며 저장된 사진 요구 ON일 때 상대 사진 필수. 최초 OFF·누락/미확인 보류를 유지. 사진 조건을 점수로 상쇄하지 않음 |
| 임계값 | 방향별 50점·coverage 70%는 산술 검산 완료, **최종 제품 기준 승인 대기** | 기존 `>=` 경계 관찰과 운영 품질 승인은 다름. §5.7의 후보 통과는 이 잠정값 및 나머지 기본 조건 충족 가정 |
| 오프라인 | 사전 동기화 후 인터넷·휴대폰 없이 키링 간 매칭 유지 | 만남마다 AI 서버/BE 호출을 필수화하지 않음. 최소 데이터·정밀도·호환·시간 유효성·후속 서버 검증은 §7.1 |

PM26은 생활정보 정책 방향·Q97 네 선택지/최대2·any/unknown/미응답·구체 허용 범위 미확인 처리와 Q98 명시 선택/신규 fallback 금지, 기존 결과 유지·새 결과 교체를 해결한다. 실제값 출처, 신규 버전 코드, 구버전 참여 유예, 정수화·오프라인 유효 기간까지 승인했다고 확대하지 않는다.

### 1.5 이번 읽기 전용 고정 자료와 신규 버전·호환 계획

2026-10-26 `git ls-remote origin refs/heads/develop`로 확인한 원격 HEAD와 로컬 `origin/develop`은 모두 **`f5224c9aad434e4e1b54df8f3f17d6f84eb48897`**이다. 브랜치에 합치지 않고 `git show`로 읽었다. 아래는 §1.1의 과거 출처를 대체 삭제하지 않고 추가한 현재 대조 근거다.

| 표기 | 고정 자료 | 확인 범위·상태 |
| --- | --- | --- |
| Q26/V26 | [설문 catalogue](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json), [버전 계약](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/contracts/survey/versioning.md) | `SIS-Q-1.1-100` / `SIS-WORDING-20261020-1`의 Q097에는 **age가 실제로 존재**. 배열 enum 5개·uniqueItems·minItems=1/maxItems=2, 별도 단독 문자열 `any/unknown`. 새 네 선택지 파일은 아직 발행하지 않음 |
| M26 | [engine100](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/handoffs/survey-20261021/mockup/dist/engine100.mjs), 같은 폴더의 data100/age100 및 기존 테스트 4개 | data100의 최초 네 선택지 뒤 `options.push(age)`가 있음. pair는 버전·완료 상태·Q97 최대 선택 수 전체를 검증하지 않음. age100은 `SIS-AGE-1`, 19~100 유한 범위만 처리 |
| O26/B26 | [OpenAPI](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/contracts/api/openapi.json), [DB schema](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/database/schema.sql) | Evaluation 13개 필드·EvaluationInput·Manifest·SurveyManifest·SurveyMetadata는 O와 동일. 평가 endpoint는 REVIEW_REQUIRED 유지, 응답은 200/400/401/403/409/503이며 과거 O의 422는 현재 선언에 없음. DB 평가 결과/버전 동시 null 제약 유지 |
| I26/F26/P26 | [IoT 데이터 구조](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/contracts/iot/keyring-data-structures-v1.md), [통신 계약](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/f5224c9aad434e4e1b54df8f3f17d6f84eb48897/docs/contracts/iot/protocol-contract-v1.md), 같은 SHA의 기능 명세·프로필 정책 | IoT 구조 v0.5.1-draft/2026-10-22, 통신 검토 2026-10-22. 검사 결과/선호 `{}` 세부는 미정. F/P 버전은 §1.1과 같고 생활정보 이전 목표가 남아 있음. PM26에 따라 후속 정합화 필요 |
| T26 | §5.7의 저장소 공유 합성 fixture와 로컬 보존 분석·기존 관찰·테스트 참고 기록 | 합성 JSON 27건의 최초 추가 원본과 현재 기대 의미 정정을 §5.7·§9.13에 구분. 나머지 세 자료는 로컬 참고 출처이며 공유 첨부가 아님. 기존 테스트 통과와 신규 기대값 `NOT_IMPLEMENTED` 구분. 과거 fixture 비교를 PDF 원문 확인으로 대신하지 않음 |

기존 파일의 SHA-256을 f5224c9의 Git blob 원본 바이트에서 이번에 다시 계산했다. 이는 **기존 파일 지문**이며 신규 불변 묶음의 contentHash·matchingVersion 발행을 대신하지 않는다. engine100에는 독립 matchingVersion export가 없으므로 상수 존재를 계약 완료 조건으로 삼지 않는다.

| 기존 고정 파일 | SHA-256 |
| --- | --- |
| questionnaire-SIS-Q-1.1-100.json | `d1199ef59596e78e2c2d3df8884a72a2b97c63099e443da7b9103f3d1e7bcb6e` |
| mockup/dist/data100.mjs | `44aced472a2671bd5f63546c37884e465144c92c0450b7d5a95d6b3b51114043` |
| mockup/dist/engine100.mjs | `1b45d79d88b90bc407170acab69b32e29ad1efbf034fdb8536128a3051436893` |
| mockup/dist/age100.mjs (SIS-AGE-1) | `8572c1903b9c1248a3d488a5922c688960292a18164d77da32d6d8443751d19e` |

age 포함 catalogue의 develop 유입은 `954acb27bdcf77cb2cee591ac94844df4e8298b9`, 실행 코드 유입은 `954f5fb3ad25c5e8e8b429331d43fd9e053c736c`다. 이는 원 제작 Sites의 age 추가 커밋을 특정한 것이 아니다. 과거 PDF 원문·Sites 전후 객체는 미확보이며 미확인으로 유지한다(T26).

**불변 버전 발행 계획:** 기존 `questionnaire-SIS-Q-1.1-100.json`·BE resources 동명 catalogue·인계 dist·기존 테스트는 보존한다. 신규 catalogue를 별도 `<NEW_SURVEY_VERSION_TBD>` 파일로 발행하고 Q097의 네 선택지·최대 2개와 기본 나이 분리, any는 엄격 제외 없음·unknown/미응답은 재확인 전 신규 적용 불가를 반영해야 한다. 엄격 선택 배열의 최소 1개/중복 금지는 기존 정의를 보존할 추천안이며 최종 새 파일은 미발행이다. Q98 명시 선택과 신규 fallback 금지·기본 나이/입력 검증/호환 계약을 담은 `<NEW_MATCHING_VERSION_TBD>`도 별도 발행해야 한다. 두 표기는 자리표시자이며 신규 설문 버전과 matchingVersion은 아직 미발행·미확정이다.

새 matchingVersion이 필요한 이유는 Q97 age 분기 제거, 항상 양방향 기본 나이·ANY/RANGE/무상한/기준일 검사, 신규 허용값 검증 및 지원 버전 조합 검증이다. 실제값 정규화·unknown 처리·판정 정밀도를 변경하면 그 의미도 버전에 포함한다. fit·영역 평균·Q96 가중치·coverage·조화평균은 재사용 후보이나 handoff 원본을 수정해 같은 버전으로 배포하지 않는다. 개인 scoring/type/analysis 값은 입력 의미가 같으면 재사용 검토하되 -127에서 지원 조합을 검증한다.

| 조합·목적 | 호환 처리 추천안 | 보존/금지 |
| --- | --- | --- |
| 기존/기존의 과거 판정 재현 | 당시 응답·규칙 파일·설정/나이 기준일 스냅샷으로만 재현 | 완료 원응답·revision·completed_at·개인 결과·판정 결과를 보존. 현재 프로필로 과거 결과 재작성 금지 |
| 신규/신규의 새 매칭 | 새 불변 묶음·입력 검증·구현/공통 기대값 검증·기기 반영 확인 후 허용 | 이번 문서만으로 활성화하지 않음 |
| 기존/신규 또는 미지원 묶음 | 명시적 호환 규칙 없이는 자동 혼용 금지·새 계산/만남 보류 | 점수 0/상대 선호 불일치로 기록하거나 임의 구버전 강등 금지 |
| 기존 응답의 신규 참여 | 사용자 확인 후 새 회차에서 재응답하는 안 권고. 호환 규칙 없으면 전체 새 회차 재응답 | `['age']`·`['age','smoking']`에서 선택값 자동 삭제, any 치환, 신규 version 라벨 덧붙이기, 자동 완료 금지 |
| age가 없는 기존 응답·구버전 키링 | 자동 호환으로 보지 않음. 참여 전환/유예·지원 종료 시점 별도 결정 | 같은 설문 코드의 과거 판본 차이는 contentHash·표현/ageVersion·실행 파일 해시로 식별. 근거 없으면 판본 추정 금지 |

Manifest의 survey/matching/productPolicy/engineVersion·contentHash·releaseId와 IoT의 `matchingRuleVersion/matching_rule_version`, DB의 `evaluation_version_code` 대응은 VERSION-01에서 합의한다. 이름이 비슷하다고 같은 값으로 확정하지 않는다. V26의 새 회차·원본/결과/키링 사본 보존 및 legacy80→100 자동 매핑 금지 원칙을 그대로 따른다.

| 재현/신규 묶음 | 설문·개인 결과 | 키링/설정 사본·규칙 | 허용 상태 |
| --- | --- | --- | --- |
| 기존 판정 재현 | 기존 완료 응답과 그 회차의 당시 개인 결과/지원 규칙 | 당시 불변 사본·기존 코드/규칙 hash | 자료가 모두 있을 때 과거 재현. 새 정책 참여 승인과 별개 |
| 신규 양쪽 참여 | 신규 완료 회차와 명시적으로 지원된 개인 계산 규칙/결과 연결 | 신규 matching 묶음과 지원되는 codebook/정밀도·최신 반영 확인 사본 | 개인 계산 재사용 여부도 지원표 검증 필요. 신규 버전/구현/검증 전 미허용 |
| 기존/신규 혼합 또는 신규 회차+과거 개인 결과/기기 사본 | 같은 의미로 추측하거나 다른 회차 결과를 붙이지 않음 | 지원하지 않은 hash·기기 규칙/설정은 보류 | 호환표 없으면 자동 계산/만남 금지. 명시적 결과 재사용 계약을 먼저 확정 |

**신규 응답의 적용과 기존 유효 결과 유지:** 기존 유효 결과가 없는 신규 가입자는 신규 응답의 검증·계산·저장이 모두 성공할 때까지 매칭을 보류한다. 기존 유효 결과가 있는 재검사 회원은 재확인·검증·계산·저장 대기나 실패 중에도 이전 유효 결과로 매칭을 유지한다. Q97/Q98 재확인 때문에 신규 응답을 적용하지 못하는 경우도 같다. 신규 응답은 검증·계산·저장 성공 후 새 유효 결과로 교체하되, 각 기기는 설정/규칙의 적용 확인 후 참여하고 BE는 제안 생성 전 최신 ON·차단·재매칭 제한을 재검증한다. 이 유지 규칙이 미지원 버전 조합이나 미확인 기기의 참여를 허용하는 것은 아니다. 기존 제안·채팅은 유지하며 완료 원응답을 덮어쓰거나 새 설문 버전으로 자동 재라벨링하지 않는다. 구버전 Q97 age 응답은 당시 버전으로 보존한다.

## 2. 두 사람의 입력과 출처

### 2.1 확인된 목업의 실제 입력

```text
pair(a, b, ageA=null, ageB=null)
```

| 인자 | 실제 형태·사용 |
| --- | --- |
| `a` / `b` | 문항 번호를 키로 갖는 두 사람의 원응답 객체. 예: `a[1]`, `a[61]`, `a[97]`. `features/preferences/settings` 객체가 아님 |
| `ageA` / `ageB` | 원응답과 별도로 전달하는 선택적 `{age,min,max}` 또는 기본값 null. Q97에서 age를 선택한 방향만 범위 검사 |

`profileFromAnswers`는 `sanitizeAnswers`로 유효한 원응답을 골라 척도·실제 특징을 계산한다. `validAnswer`는 숫자 범위·범주·복수 선택 수와 중복 등을 검사하지만, `direction`은 선호와 Q97을 직접 읽으므로 `pair` 자체를 전체 서비스 입력 검증기로 취급하지 않는다. 회차·버전 호환·완료 상태 검증은 서비스 경계에서 추가로 정해야 한다.

`ageNumber/cleanAge`는 19~100 정수 외의 나이를 null로 정리한다. `ageDecision`은 선택한 방향의 범위 누락·min>max·상대 나이 누락을 `unresolved`로 처리한다. 제품의 ANY·무상한·성인 자격·생일 갱신을 구현한 것은 아니다.

기존 목업을 재사용할 때는 보존된 실제 원응답을 전달한다. 서비스의 개인 점수·유형·해석이나 `features`에서 원응답을 역산하거나 임의 생성하지 않는다. 서비스 원응답 경로는 R에 따라 `response_json.answers`로 확인됐으며, 실제 완료 회차 데이터의 쌍 비교 전달·검증과 pair 어댑터는 INPUT-01의 -36·BE -74 협업에 남긴다.

### 2.2 서비스 입력 제안

아래는 **서비스 계층의 내부 비교 입력 제안**이다. 실제 `pair` 인자나 확정된 저장 테이블·HTTP API·키링 패킷이 아니다. 두 사람 A/B에 각각 같은 항목을 연결하고, 값이 없으면 중간값·가상 원응답으로 채우지 않는다.

| 입력 | 자료형·필수 여부 | 실제 출처·사용 방향 |
| --- | --- | --- |
| `responseRef`, `personalResultRef`, `snapshotRef` | 식별 참조. 양쪽 필수, 전달 객체의 실제 키 형식은 미정 | 완료 원응답·개인 결과·판정 당시 설정을 연결하고 41/42 규칙·목업 인계 이력을 보존. R의 원응답 경로·내부/외부 ID 구분은 §3.4에 반영. 개인 분석 입출력은 -127, 나머지 매칭용 내부 스키마는 INPUT-01/-36·BE -74에서 합의 |
| `features.E/A/C/I` | 0~100 number 또는 null | 서비스에서 재사용할 개인 계산 결과의 실제 성격 특징 제안. A의 Q61~64가 B의 특징을 평가하고, B의 선호가 A를 평가. 기존 pair에 이 객체를 직접 전달하지 않으며, -36에서 역채점·유형 계산을 중복 구현하지 않음 |
| `features.q30`~`q60` | Q별 정수 1~5·범주 문자열·배열, 측정 안 됨은 unknown/null | 각자의 파트1 원응답 또는 그것을 보존한 매칭용 특징. A의 Q65~95는 B의 Q30~60과 대응. 개인 결과의 유형명·해석 문장으로 복원하지 않음 |
| `preferences.q61`~`q95` | Q별 단일 값·수용 목록 또는 `any`/`unknown`/미응답 | **선호를 가진 사람의** 완료 원응답에서 추출. A의 선호와 B의 실제 특징, B의 선호와 A의 실제 특징을 따로 비교 |
| `settings.q96`~`q98` | Q96 영역 최대 3개, Q97 엄격 제외 최대 2개 또는 단독 `any`, Q98 `close/some/wide` 명시 선택 필수 | 신규 Q97은 `smoking/children/religion/tattoo` 네 엄격 선택지이며 any는 엄격 제외 없이 일반 매칭. Q97 unknown/미응답 및 Q98 unknown/미응답은 재확인 전 신규 응답 적용 불가. 기존 Q26의 age·단독 any/unknown 원본은 §1.5에 보존 |
| `settings.q99/q100` | Q99 `fit/diverse`, Q100 `common/my_wishes/their_wishes/adjust` 또는 허용된 결측 상태 | 후보 구성·설명 순서용. 방향 점수나 나이 통과를 만들어내는 입력이 아님 |
| `age`, `ageAsOf` | 현재 만 나이 정수·한국 기준일. 제품 기본 자격 확인에 양쪽 필수 | 제품에서는 생년월일과 Asia/Seoul 날짜·생일로 산출. 비교에는 산출 나이·기준일을 전달하고 생년월일 원본을 상대나 키링 간 방송으로 보내지 않음 |
| `desiredAgeMode/Min/Max` | 명시한 `ANY/RANGE`; RANGE의 min≥19, max≥min 또는 null | 프로필 이후 기본 조건 설정. 필드명은 P의 초안. 양방향으로 검사하며 모드 미선택은 미완료 |
| `ageProfile={age,min,max}` | 기존 Q 목업은 각 값 19~100 정수 또는 null인 선택 메타데이터 | Q60 부근의 본인 나이·Q97 부근의 희망 나이 입력. **Q60 응답 자체는 문신 상태**이며 나이는 별도 필드다. 신규 기본 나이와의 표현/기준일 어댑터만 AGE-POL-01 미정. Q97에서 age를 제거한다는 결정은 해결 |
| `gender/desiredGenders`, `hasProfilePhoto/requirePartnerPhoto`, 준비 상태 | 각자 실제 기본 조건·사진 등록 상태·설정 버전. 표현 형식 초안 | 대상 성별은 저장한 본인 MALE/FEMALE의 반대로 BE가 자동 산출·검증하며 자유 선택 목록이 아님(F MAT-01, P 232~236행). `desiredGenders` 필드 유지/응답 형식만 미정. 양방향 사진 조건·기기별 준비를 선호 원응답이나 점수로 대체하지 않음 |
| 규칙·입력 버전 참조 | 양쪽 회차/결과/설정 버전, 고정 파일·hash, 새 쌍 비교 규칙 식별값 | R의 `SIS-Q-1.1-100`은 기존 응답 연결. PM26용 신규 surveyVersion/matchingVersion은 §1.5의 미발행 자리표시자. 개인 계산·설정 버전과 구분하고 -127/-115 및 -36·BE -74에서 대응 |

`any`는 명시적 무제약이며 `unknown`은 응답했지만 측정 정보가 없는 상태다. 미응답은 입력하지 않은 상태이고 `NONE` 같은 범주 코드는 실제 값이다. 설문 `any`를 배열 원소와 섞지 않는다(Q 22·28행). 제품 나이 `ANY`와 소문자 설문 `any`는 서로 다른 필드다. `typeRecommendations`, `selfPreferenceDifferences`, 유형 대표값으로 비공개 선호나 실제 특징을 대체하지 않는다.

Q97 엄격 선택의 구체 상대 허용 범위는 **선택한 본인의** smoking→Q90, children→Q93, religion→Q91, tattoo→Q95에서 읽는다. 하나라도 any/unknown/미응답이면 해당 범위를 재선택하거나 해당 엄격 제외 선택을 해제해야 한다. 해결 전에 신규 응답의 매칭 결과를 활성화하지 않으며 기존 유효 결과가 있으면 §1.5대로 유지한다. 상대의 실제값 미확인과 이 입력 설정 미완료를 구분하고 내부 오류코드나 Evaluation enum을 새로 만들지 않는다.

## 3. 방향별 판정과 쌍 출력

### 3.1 나이를 제외한 문서 기준 비교

`A→B`는 **A가 원하는 모습에 B가 맞는 정도**, `B→A`는 **B가 원하는 모습에 A가 맞는 정도**다. 두 사람의 유형이 같은지 또는 A/B의 자기 응답이 같은지를 검사하는 것이 아니다. 아래 표는 S 160~181행의 문서 규칙을 보존한 것이다. M에서 같은 구조의 계산을 확인했고 §9에 기존 테스트와 가상 쌍의 실행 결과를 별도로 남긴다. 표의 모든 경계나 §5의 문서 사례를 실행 검증했다는 뜻은 아니다.

| 단계 | 문서 규칙 |
| --- | --- |
| 항목 대응 | Q61~64 ↔ 상대 E/A/C/I, Q65~95 ↔ 상대 Q30~60 |
| 허용 구간 | 성격 low=[0,40], mid=[30,70], high=[60,100]. Q98 close/some/wide의 여유는 0/10/20. 순서 1~5 선호는 ±0/1/2 후 범위 안으로 제한. Q89 음주는 하한 1·선호 상한 사용 |
| 항목 fit | 구간 밖 거리 `d=max(L-x,0,x-U)`, `M=max(L-domainMin,domainMax-U)`, `fit=clamp(1-d/M,0,1)`. 전체 범위 선호는 null(무제약). 범주는 수용 목록 포함 1/미포함 0, 복수 활동은 교집합 유무 |
| 시간 정보 | Q87 선호 fit을 계산한 뒤 양쪽 Q52 실제 시간대를 평가한다. 양쪽에 유효한 구체 시간이 있고 교집합이 없으면 Q87이 irregular뿐이어도 fit=0을 우선한다. 어느 쪽 Q52에 구체 시간이 없으면 null, 실제 시간 교집합이 있으면 기존 선호 fit을 유지한다(irregular만인 선호는 null). 0은 관측·점수에 포함하고 null은 정보 부족으로 구분한다. any/unknown/미응답 선호는 루프 진입 전 제외하며, Q87 0 자체를 전체 자동 탈락으로 확대하지 않음(§5.8) |
| 방향 점수 | 정보 있는 항목을 9영역 안에서 평균하고, 본인의 Q96 선택 영역 비중 2·그 외 1로 가중 평균하여 0~100점 산출. 모든 항목을 한 번에 평균하지 않음 |
| 방향 coverage | `observed/informative`. any·unknown·미응답·전체 범위 순서 선호는 분모 제외. 비교할 선호는 있지만 fit=null이면 informative에만 포함하고 관측수에서는 제외. 실제 시간 불일치 등 fit=0은 informative·observed에 모두 포함. informative=0이면 coverage=0·score=null |
| 엄격 제외 | 기존 M은 smoking→Q90/Q55, children→Q93/Q58, religion→Q91/Q56, tattoo→Q95/Q60, age→별도 나이 정보. **신규 PM26은 앞의 네 항목 중 최대 2개만** 검사하며 age는 별도 기본 게이트로 항상 양방향 검사. drinking 엄격 제외 없음 |
| 쌍 점수·문서상 통과 | `H=2*S_AB*S_BA/(S_AB+S_BA)`. 합이 0이면 H=0, 한쪽 점수가 null이면 H=null. 기존 코드의 각 방향 score≥50·coverage≥0.7·excluded/unresolved 없음은 검산한 잠정 신규 기준이며 최종 제품 승인 대기. 기본 게이트 별도. 판정에 반올림 값 사용 금지 |

| 영역 | 선호 문항 |
| --- | --- |
| 기본 성격 | Q61~64 |
| 관계 시작·표현 | Q65~68 |
| 연락·함께하는 시간 | Q69~72 |
| 데이트·경험 | Q73~76 |
| 갈등·도움 | Q77~80 |
| 친구 관계·공개 범위 | Q81~82 |
| 비용·선물 | Q83~85 |
| 생활 습관·시간 | Q86~91·Q94~95 |
| 장기적인 관계 방향 | Q92~93 |

**확인된 기존 코드 동작:** Q98 unknown과 미응답 모두 계산상 `some`의 여유(성격 10·순서 1)를 사용한다. 허용 폭 조회의 기본값 처리이며 원응답에 `some`을 만들어 저장하지 않는다. PM26 최종 결정에 따라 신규 매칭에서는 이 fallback을 금지하고 과거 버전 재현 기록으로 보존한다(§3.5). 이 확인만으로 원응답 추출·서비스 스키마·결측 의미·직렬화를 포함한 INPUT-01 전체가 해결된 것은 아니다. 같은 유형 가산점은 없다. 참고 유형의 자기 유사성·최대 3개 표시는 이 판정을 대신하지 않는다.

### 3.2 목업 실제 반환과 통합 Evaluation 명세

기본 조건은 성인·반대 성별 자동 대상·희망 나이·사진 등 점수 밖의 자격 검사다. 점수는 관측한 특징의 선호 충족 정도이고, coverage는 비교에 필요한 정보의 충족률이다. 엄격 제외는 Q97에서 선택한 조건의 알려진 불일치이며 기본 조건 검사와 범위가 다르다. 정보 부족은 필요한 값을 확인하지 못한 상태이고, 입력 오류는 AGE-10의 역전 범위처럼 값 자체가 유효하지 않은 상태다. 이 구분을 유지해 입력→판정→출력을 읽으며, 아래 상태 표현은 확정된 서비스 오류 enum이 아니다.

`pair`의 실제 최상위 반환은 `{ab,ba,score,eligible}`다. `ab`는 A→B, `ba`는 B→A이며 각 방향은 `{score,coverage,informative,observed,rows,domains,excluded,unresolved}`를 반환한다. `rows`의 각 항목에는 `id,feature,domain,fit,wish,actual,label`, `domains`에는 `id,name,ids,fit,weight,items`가 있다. `excluded/unresolved`는 방향별 조건 코드 문자열 배열이다.

목업 `eligible`은 양쪽 점수≥50·coverage≥0.7·제외와 미해결 없음 등을 검사한다. 성인·반대 성별 자동 대상·제품 희망 나이·사진·준비 상태·버전 호환 및 **매칭 ON·차단·재매칭 제한** 등 전체 서비스 자격을 검사하지 않는다. 따라서 계산 `eligible=true`를 전체 서비스 자격 통과나 제안 생성 승인으로 복사하지 않는다.

**상위 서비스 게이트 연결(R):** F MAT-03/MAT-05/SAFE-01, P §4.2·§5와 E §4~5를 따른다. 키링은 반영된 설정/규칙으로 로컬 판정과 만남 기록을 만들 수 있지만, 그 결과가 BE의 최신 상태 검증을 대신하지 않는다. **BE는 새 제안 생성 전에** 양쪽 계정의 저장된 매칭 ON, 차단 관계, 해당 쌍의 재매칭 제한을 최신 상태로 재검증하고 당시 입력/설정·규칙 버전과 이벤트 증명도 상위 계약에 따라 확인한다. 제한 상태의 관리·처리는 기존 BE 관계/제안 계약의 책임이며 계산 함수나 키링에 새로운 권한을 부여하지 않는다.

OFF 저장 후 서버 신규 제안 생성 중지와 기기 반영 시 참여/진동 중지는 구분한다(P 516~518행). 최신 상태 전파·오프라인 확인 주기·동시/지연 이벤트의 세부 경계는 SYNC-01과 35·37·38 계약에 남긴다. 새 오프라인 보장이나 OFF에 따른 기존 제안 종료 정책을 만들지 않는다. 기존 제안·차단/나가기 등의 종료 효과는 각 상위 정책을 그대로 따른다.

`eligible=false`의 사유가 항상 `excluded/unresolved`에 담기는 것은 아니다. PAIR-02의 문서 기대값은 A→B 0점·B→A 100점·양쪽 coverage=1·H=0이므로, 양쪽 제외·미해결 배열이 모두 비어 있어도 점수 기준 미달로 false다. PAIR-03처럼 score=null·coverage 미달인 정보 부족도 별도로 확인한다. 이 배열은 모든 점수 미달·정보 부족·서비스 입력 오류를 나열하는 목록이 아니며, 설명을 보완하려고 목업 배열에 새 코드를 넣지 않는다.

성인·반대 성별 자동 대상·희망 나이·사진 옵션 같은 기본 자격은 점수와 별도다. 사진 옵션은 양쪽에 적용하고, 최초 기본값 OFF와 저장된 설정을 구분한다. 나이 통과만으로 전체 매칭을 보장하지 않는다. 입력·정책 미해결을 높은 점수로 덮어 통과시키지 않는다.

O의 **`POST /internal/v1/matching/evaluations`**는 요청 `EvaluationInput`, 성공 `200 Evaluation`을 이미 정의한다. `EvaluationInput`의 `encounterId/leftSnapshotId/rightSnapshotId/releaseId`는 모두 필수 `string(format: uuid)`이고 추가 속성을 허용하지 않는다. 인증은 `serviceBearer`, `Idempotency-Key` 헤더는 필수 UUID다. §2.2의 특징·선호·나이 객체를 이 API의 새 요청 필드로 추가하는 제안이 아니다. `releaseId`를 파일 버전 코드로 해석할 대응은 API의 확인 항목이며 현재 UUID 선언을 임의로 바꾸지 않는다.

아래는 **이미 정의된 Evaluation 필드**와 목업의 대응이다. O의 `Evaluation`은 13개 필드가 모두 required이고 `additionalProperties=false`다. 필수 필드의 null 허용은 생략 허용과 다르며, 스키마 정의가 BE 런타임 구현 완료를 뜻하지 않는다.

| Evaluation 명세 필드 | 타입·필수/null 허용·의미 | 실제 목업과의 대응·잔여 연결 |
| --- | --- | --- |
| `directionAB/directionBA` | 필수 `number(0~100) 또는 null` | `ab.score/ba.score`. 정보 부족의 null을 0으로 바꾸지 않음 |
| `coverageAB/coverageBA` | 필수 `number(0~1)`, null 불가. 방향별 비교 정보 충족률 | `ab.coverage/ba.coverage`. 설문 완료율·유형 coverage와 다르며 목업의 `informative/observed`는 Evaluation 필드가 아님 |
| `harmonicScore` | 필수 `number(0~100) 또는 null`. 두 방향의 조화평균이며 성공 확률이 아님 | 최상위 `score` |
| `eligible` | 필수 `boolean`, null 불가. O의 설명은 “현재 기준으로 매칭 후보가 될 수 있는지 여부” | 평가 범위의 후보 판정이며 아래의 전체 제안 생성 가능 여부와 구분 |
| `excluded` | 필수 `string[]`, 배열/원소 null 불가. 필수 조건 등의 제외 사유 | 목업은 `ab.excluded/ba.excluded`. 통합 배열에 방향·근거를 연결할 문자열 규칙은 추가 합의하며 객체 배열·새 enum으로 바꾸지 않음 |
| `unresolved` | 필수 `string[]`, 배열/원소 null 불가. 정책·정보 부족으로 판정 보류하는 사유 | 목업은 `ab.unresolved/ba.unresolved`. 서비스 입력 오류 전체를 이 배열로 나타낸다고 가정하지 않음. 방향·코드 대응은 INPUT-01 잔여 |
| `reasonKeys` | 필수 `string[]`, 배열/원소 null 불가. 판정 사유 코드 목록 | `pair` 반환에 없음. 키 문자열의 규칙·`rows/domains` 대응·공개 허용 범위는 추가 합의. 원응답이나 상세 객체를 넣는 필드가 아님 |
| `manifest` | 필수 `Manifest` 객체, null 불가 | `releaseId` UUID·`surveyVersion/wordingVersion/scoringVersion/typeModelVersion/analysisVersion/typeReferenceVersion/matchingVersion/productPolicyVersion/engineVersion` 문자열·`contentHash` 64자리 소문자 16진수 문자열 모두 필수. 각 버전 문자열은 길이 1~100. 설문용 `SurveyManifest`와 구분하며 실제 파일/버전 대응은 VERSION-01 잔여 |
| `leftSnapshotId/rightSnapshotId` | 필수 `string(format: uuid)`, null 불가 | 양쪽 당시 설정 사본을 연결. `pair` 반환에 없으며 내부 bigint 대응은 API 확인 항목 |
| `encounterId` | 필수 `string(format: uuid)`, null 불가 | 평가 대상 만남의 외부 식별자. 별도 `evaluationId` 발급을 전제하지 않으며 내부 bigint 대응은 API 확인 항목 |

`ageChecks/basicChecks`와 설명용 `PASS/FAIL/UNRESOLVED/INVALID`는 **Evaluation에 없는 내부 검토 표현**이다. 추가 필드를 허용하지 않는 정상 응답에 넣지 않는다. 기본 조건 검사 의무는 유지하되 내부 상세 구조를 새 API 계약으로 만들지 않는다.

**eligible의 범위:** 목업 `pair.eligible`은 계산 조건만 검사한다. O의 Evaluation은 당시 스냅샷과 규칙으로 평가하며 endpoint 설명에 제품 성별·나이·사진 필수 필터 우선이 명시돼 있으므로 목업 boolean을 그대로 복사해도 된다는 뜻이 아니다. 반대로 `Evaluation.eligible=true`만으로 매칭 ON·차단·재매칭 제한의 최신 상태까지 통과했거나 제안 생성이 승인됐다고 해석하지 않는다. 이 게이트 전체를 `eligible`에 포함한다는 명시는 O에 없으며 BE의 제안 생성 전 재검증은 별도로 유지한다. 실제 평가 범위·재검증 연결의 세부는 BE 확인 대상이다.

**저장 명세:** B의 `encounters.evaluation_json jsonb`와 `evaluation_version_code text`는 이미 정의돼 있다. 둘 다 nullable이며 `ck_encounters_2`로 함께 null이거나 함께 존재하고, `ck_encounters_3`으로 결과가 있으면 JSON 객체여야 한다. O는 양쪽 스냅샷·판정 기준·근거를 결과 묶음에 보존하고 결과/버전 존재로 평가 완료 여부를 판단하도록 설명한다. 상세 JSON 연결·실제 저장 동작은 추가 검토하며 개인 `analysis_results`와 섞지 않는다.

정책 미합의는 선호 불일치·잘못된 입력과 구분한다. 과거 O의 `422 POLICY_UNRESOLVED`는 최신 O26 평가 endpoint의 응답 목록에 없으므로 현재 사용 가능한 HTTP 계약으로 제안하지 않는다. Evaluation의 `unresolved: string[]`는 유지되며 정책/버전 보류와 입력 오류의 실제 전달 경계는 BE와 합의한다. O26의 200/400/401/403/409/503 및 REVIEW_REQUIRED를 따르고 새 HTTP/enum을 만들지 않는다. 이미 정의된 필드·자료형·저장 컬럼은 미정으로 되돌리지 않으며 실제 BE 응답·DB 적용 완료는 주장하지 않는다.

입력 오류는 SVC-04의 기존 400 Error 우선 제안과 연결하고, 정보 부족은 null 점수/coverage·unresolved 의미로 구분한다. 통신/계산 시스템 실패는 기존 503 등 서비스 오류·재시도 경계를 BE와 맞추며 선호 불일치0점/엄격 제외/정상 평가로 저장하지 않는다. 실패 중 기존 유효 결과 유지 원칙은 그대로다. 실제 타임아웃·재시도·원인별 오류 매핑 실행 검증은 후속이며 새 사유 enum을 임의로 확정하지 않는다.

**이전 샘플 보존 범위:** 다음 §3.3~3.4는 O 기준 원문이다. 그 안의 422 설명은 과거 O의 선언이며 현재 O26에서는 선언하지 않는다(위 정정 우선). SVC 입력·계산 발췌·400 Error 예시는 유지하고, 신규 응답으로 자동 변환하거나 새 API 필드를 덧붙이지 않는다.

### 3.3 검토용 서비스 요청·응답 샘플 4건

아래는 §2.2의 서비스 입력 제안과 §3.2의 명세 필드를 연결한 **내부 검토용 발췌**다. 실제 `pair` 호출·반환 예시(§9.3)와 구분하며, 이 샘플의 `features/preferences`를 `pair` 인자로 읽지 않는다. O에 이미 있는 API 경로·HTTP 상태·오류 계약을 따르며 새 필수 필드·오류 enum을 만들지 않는다. 샘플의 수치는 기존 PAIR 사례의 문서 계산 기대값이며 새 실행 결과가 아니다.

| 요청·응답에 연결할 출처·버전 | 이 샘플의 참조와 미정 범위 |
| --- | --- |
| 문항·계산 기준 | §1.1 Q/S의 `fa1235698ab2b64552a60779350b74c5c3b251c4`, `SIS-Q-1.1-100`·`SIS-WORDING-20261020-1`·`SIS-AGE-1`. 아래 수치의 문서 근거 |
| 제품 기본 조건 기준 | §1.1 F/P의 `ea74cc53f4cdd5e600b30cbe96100d192eea0773` / F `v0.1.81-draft`·P `v0.1.92-draft`. 과거 P0 출처는 §1.1에 보존하고 AGE-10·성별·사진 사례의 제품 근거는 최신 F/P로 정정 |
| 실제 목업 계약 참조 | §1.1 M의 `fe76ce902422cc218998f7ac73c8d4175c069703`. 실제 입출력의 대조 기준이며 서비스 샘플을 이 버전에서 실행했다는 뜻이 아님 |
| 양쪽 입력 참조 | 각 요청의 A/B에 §2.2의 `responseRef/personalResultRef/snapshotRef`를 연결하고 응답도 같은 참조를 보존하는 제안. `<SVC-01:A:responseRef>`처럼 샘플 ID·사람·필드로 적는 값은 모두 설명용 자리표시자이며 실제 조회한 ID가 아님. 다른 샘플은 해당 ID로 구분 |
| 서비스 판정 버전 | 양쪽 설문 `version_code=SIS-Q-1.1-100`은 R의 BE 결정이며 O의 `SurveyManifest.surveyVersion` 저장 대응도 정의돼 있음. Evaluation은 별도 전체 `Manifest`를 사용. 회차·설정 및 개인/쌍 계산 규칙 버전의 대응·호환 기준은 INPUT-01·VERSION-01에서 개인 분석 계약 -127/-115와 쌍 비교 -36·BE -74로 구분. Q/S/P/M 참조로 미정 규칙 버전을 대신 확정하지 않음 |

다음 표는 **요청 입력의 일부**다. SVC-01~03은 §5.3처럼 Q96=any·Q98=some, 나이 AGE-01과 같은 유한 ageProfile 및 양쪽 Q97 age 선택을 전제로 계산한다. 명시한 선호 외에는 해당 합성 사례에서만 any로 둔다. 이는 실제 누락 응답을 채우는 규칙이 아니다. §5.3의 성별·사진·준비 조건 통과 가정도 문서 계산을 위한 전제이며 실제 설정을 조회한 결과가 아니다. 각 필드의 실제 추출·원응답 참조가 없으면 샘플을 실행 입력으로 변환하지 않는다.

| 샘플 ID·기존 사례 | A의 서비스 요청 발췌 | B의 서비스 요청 발췌 |
| --- | --- | --- |
| SVC-01 / PAIR-01 | `features.q34=2`, `preferences.q69=4` | `features.q34=2`, `preferences.q69=2` |
| SVC-02 / PAIR-02 | SVC-01과 같은 특징·선호 | `features.q34=1`, `preferences.q69=2`. 변경된 입력·설정 참조는 SVC-02로 구분 |
| SVC-03 / PAIR-03 | SVC-01과 같은 특징·선호 | `features.q34=unknown`, `preferences.q69=2`. 관측값을 생성하지 않으며 참조는 SVC-03으로 구분 |
| SVC-04 / AGE-10 | `age=27`, `desiredAgeMode=RANGE`, `desiredAgeMin=35`, `desiredAgeMax=28` | `age=30`, `desiredAgeMode=ANY`, `desiredAgeMin=null`, `desiredAgeMax=null`. 점수용 입력은 이 오류 설명에 추가하지 않음 |

다음은 위 입력에 대응하는 **계산 부분의 문서 기대값**이다. SVC-01~03은 완전한 `200 Evaluation` 응답이 아니며 `encounterId/manifest/leftSnapshotId/rightSnapshotId/excluded/unresolved/reasonKeys` 등을 생략한 검토용 발췌다. 실제 정상 응답에서는 §3.2의 13개 필수 필드를 모두 보내야 한다. `eligible`의 발췌상 생략은 실제 응답에서의 생략·null 허용이 아니다. 분수는 정확한 기대값 표기이며 실제 number 직렬화 형식이 아니다. SVC-03의 `directionAB`·`harmonicScore` null은 명세가 허용하지만 coverage·eligible·배열·참조 필드는 null을 허용하지 않는다.

| 샘플 ID·판정 구분 | `directionAB / directionBA` | `coverageAB / coverageBA` | `harmonicScore`·계산 `eligible` | 사유·확인 범위 |
| --- | --- | --- | --- | --- |
| SVC-01 / 정상 계산 | 50 / 100 | 1 / 1 | `200/3`. 계산 조건 통과. Evaluation의 `eligible`은 발췌에서 생략 | 양쪽 점수·coverage 기준 충족. PAIR-01의 문서상 통과와 Evaluation의 기본 필터·전체 제안 생성 가능 여부를 구분. 실제 기본 조건·입력/버전 호환·영향 있는 정책 합의 전 제안 생성은 확정하지 않음 |
| SVC-02 / 점수 미달 | 0 / 100 | 1 / 1 | 0. `eligible=false` 기대 | 정보는 있으나 A의 선호에 B의 특징이 맞지 않아 50점 미달. 제외·미해결 배열에 새 사유 코드를 만들 필요가 없음 |
| SVC-03 / 정보 부족 | null / 100 | 0 / 1 | null. `eligible=false` 기대 | A→B observed=0/informative=1. q34 미확인으로 비교할 수 없으며 fit=0·점수 0으로 대체하지 않음. `unresolved`에 특정 코드가 반환된다고 가정하지 않음 |
| SVC-04 / 입력 오류 | 미평가 | 미평가 | 정상 Evaluation을 반환하지 않는 오류 처리 권장 | min>max인 유효하지 않은 입력. 기존 `400 INVALID_REQUEST`·`Error` 응답을 우선 제안하며 `200 Evaluation(eligible=false)`로 가장하지 않음. 목업 `ageDecision`이 이 범위를 unresolved로 다루는 기존 동작(§2.1)과 구분 |

**SVC-04 권장 오류 응답:** O의 평가 API는 `400 INVALID_REQUEST`에 `#/components/schemas/Error`를 사용한다. `Error`의 필수 필드는 `code/message`(비어 있지 않은 문자열)이고 선택 필드는 `requestId`(UUID 문자열), `fields`(`string[]`), `retryable`(boolean)이다. 다음은 기존 계약을 따르는 최소 본문 예시이며 런타임에서 관찰한 응답이 아니다.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
```

```json
{
  "code": "INVALID_REQUEST",
  "message": "희망 나이의 최솟값은 최댓값보다 클 수 없습니다."
}
```

`EvaluationInput`은 나이 원시값을 직접 받지 않고 네 참조 ID만 받는다. 따라서 SVC-04의 연결 데이터 검증 위치·오류 전파와 선택 `fields`의 실제 경로는 BE 확인 대상이다. 위 `400`은 기존 입력 검증 응답에 맞춘 우선 제안이며 min>max 전용 매핑이 이미 구현됐다는 뜻은 아니다. 이 경로의 `422`는 `POLICY_UNRESOLVED`로 정의돼 있으므로 입력 오류를 임의로 같은 정책 오류에 매핑하거나 새 오류 코드를 만들지 않는다.

이 발췌의 참조는 위 입력의 양쪽과 같은 Q/S/P/M·판정 버전에 연결한다. 명세의 응답 구조는 O를 따르며 남은 내부 연결·오류 전파만 협의한다. 원응답을 역산하거나 누락값을 생성하지 않는다. 기본 조건 불일치는 §5.1·§5.5~5.6, 엄격 제외는 POL-CASE-01·§9.3을 함께 읽는다.

### 3.4 SVC-01 재사용: 가상 A/B의 입력·버전 연결 한 쌍

**BE 검토용 연결 초안**이다. 새 SVC 사례나 확정 API·DB 행·실행 가능한 `pair` 요청을 추가하는 것이 아니다. §3.3 SVC-01의 A(`q34=2`, `q69=4`)·B(`q34=2`, `q69=2`) 발췌와 AGE-01의 유한 나이 조건을 그대로 참조한다. 완료 회차·개인 계산 성공·스냅샷 연결은 이 예제의 가정이며 실제 저장 상태를 조회·검증한 결과가 아니다. 원응답 전체와 개인 결과 본문을 갖고 있지 않으므로 실행 입력을 완성하거나 `features`에서 원응답을 역산하지 않는다.

아래 `<SVC-01:...>`는 **설명용 자리표시자**이며 `roundRef/responseRef/personalResultRef/snapshotRef`는 이 문서의 참조 이름 제안이다. B에서 `survey_responses.response_json jsonb NOT NULL`을 확인했고 R의 원응답 **`response_json.answers`**·나이 정보 **`response_json.metadata`** 경로를 유지한다. O의 `SurveySession.metadata`는 `SurveyMetadata`를 참조한다. 이 정정이 DB/API 원문 수정이나 실제 저장 상태 확인은 아니다.

**SurveyMetadata 명세 정의:** `ownAge/desiredAgeMin/desiredAgeMax`는 모두 required이며 각 값은 `integer(19~100) 또는 null`이다. 선택 정보라는 설명이 필드 생략을 뜻하지 않는다. `additionalProperties=true`이나 추가 항목은 release별 `metadataSchema`로 검증한다는 설명을 따른다. 목업 `ageA/ageB={age,min,max}`와 이름이 다르므로 같은 저장 객체로 선언하지 않는다. 제품의 `birthDate/Preferences`와 분리하며 `ANY/RANGE`·무상한·101세 이상 연결이나 null의 무상한 해석을 이 스키마만으로 해결하지 않는다(AGE-POL-01).

내부 **`response_id bigint`**와 외부 세션 식별자를 구분한다. **`survey_responses.public_id uuid NOT NULL DEFAULT gen_random_uuid()`·UNIQUE는 B에 이미 정의**돼 있으며 O의 `SurveySession.sessionId` 설명은 이를 외부 UUID로 사용하고 내부 bigint는 노출하지 않도록 명시한다. 더 이상 도입 예정으로 두지 않는다. 실제 DB 적용·생성/조회 런타임, 나머지 ID 대응과 매칭용 전달 객체는 별도 확인한다.

**설문/계산 버전의 경계:** O의 설문용 `SurveyManifest`는 `surveyVersion/wordingVersion/contentHash` 세 필드가 모두 required이고 추가 속성을 허용하지 않는다. 앞의 두 값은 길이 1~100 문자열, hash는 64자리 소문자 16진수 문자열이다. `surveyVersion → survey_responses.version_code` 대응과 `SIS-Q-1.1-100` 예시가 이미 정의돼 있다. Evaluation의 전체 `Manifest`에 있는 `scoringVersion/analysisVersion/matchingVersion` 등을 설문용 최소 manifest와 혼동하지 않는다. B의 `analysis_results`에는 `response_id/version_code/outcome/result_json` 등이 있지만 **별도 개인 계산 규칙 버전 저장칸은 없다**. 기존 `version_code`의 의미를 임의로 바꾸거나 컬럼을 신설하지 않으며 개인 계산 버전의 저장·재현 계약은 **-127/-115 후속 협의**다. `encounters.evaluation_version_code`로 보존하는 -36 쌍 비교 규칙과 개인 분석 저장 계약을 구분한다.

| 연결 단계 | 가상 A | 가상 B | 의미·검토 경계 |
| --- | --- | --- | --- |
| 완료 응답/회차 | `<SVC-01:A:roundRef>` → `<SVC-01:A:responseRef>`; 완료 시각 `<SVC-01:A:completed_at>`, 응답 revision `<SVC-01:A:revision>` | `<SVC-01:B:roundRef>` → `<SVC-01:B:responseRef>`; 완료 시각 `<SVC-01:B:completed_at>`, 응답 revision `<SVC-01:B:revision>` | 자기 회차의 불변 완료 응답을 지칭하는 제안. A/B의 참조·revision을 섞지 않음. 완료 여부·원응답 유효성 검증은 아직 수행하지 않음 |
| 설문 응답 버전 | A 응답의 `version_code` = `SIS-Q-1.1-100` | B 응답의 `version_code` = `SIS-Q-1.1-100` | R의 값 결정과 O의 `SurveyManifest.surveyVersion` 저장 대응은 정의됨. 실제 저장 적용은 별도. 개인/쌍 계산 규칙의 값·파일 목록·호환 여부는 VERSION-01에서 개인 분석 계약 -127/-115와 쌍 비교 -36·BE -74로 구분 |
| 그 응답의 개인 결과 | `<SVC-01:A:personalResultRef>` ← `<SVC-01:A:responseRef>` + `SIS-Q-1.1-100` + `<SVC-01:personalRuleVersion>` | `<SVC-01:B:personalResultRef>` ← `<SVC-01:B:responseRef>` + `SIS-Q-1.1-100` + `<SVC-01:personalRuleVersion>` | F SUR-02·V 12~14행의 응답/결과 연결을 표현. `personalRuleVersion`은 재현에 필요한 논리적 참조이며 B에 별도 저장칸이 있다는 뜻이 아님. 성공한 자기 응답의 실제 결과 ID·개인 규칙 버전 저장·전달 계약은 -127/-115 후속 협의 |
| 판정 당시 설정/스냅샷 | `<SVC-01:A:snapshotRef>` → `<SVC-01:A:personalResultRef>` + `<SVC-01:A:settingsVersion>` + `<SVC-01:ageAsOf>` | `<SVC-01:B:snapshotRef>` → `<SVC-01:B:personalResultRef>` + `<SVC-01:B:settingsVersion>` + `<SVC-01:ageAsOf>` | V 12행·F SUR-03/MAT-01의 당시 설정 연결 제안. 각자의 나이/사진/준비 상태 등을 판정 시점에 묶음. 생년월일·사진 원본·실제 개인정보는 넣지 않음. 기준일의 실제 형식·값도 미정 |
| 비교에 적용할 규칙 | 설문 `SIS-Q-1.1-100`과 `<SVC-01:personalRuleVersion>`·`<SVC-01:pairRuleVersion>`의 대응 | A와 같은 지원 묶음·쌍 비교 규칙을 참조하는 가정 | 설문 값과 Manifest 필드는 정의돼 있으나 개인/쌍 계산 규칙 버전까지 같은 값으로 정하지 않음. 문서/목업 참조와 실제 파일/규칙 ID의 대응·호환 검증은 VERSION-01에서 -127/-115와 -36·BE -74로 구분 |
| 결과에 보존할 참조 | `<SVC-01:A:responseRef>`·`<SVC-01:A:personalResultRef>`·`<SVC-01:A:snapshotRef>` | `<SVC-01:B:responseRef>`·`<SVC-01:B:personalResultRef>`·`<SVC-01:B:snapshotRef>` | O의 Evaluation은 `encounterId/manifest/leftSnapshotId/rightSnapshotId`를 필수로 연결하고 B의 쌍 판정 저장칸은 §3.2와 같음. 회차/revision·설정 버전은 참조한 원본으로 추적. `personalRuleVersion/pairRuleVersion/ageAsOf` 등 예제의 논리적 참조를 Evaluation 추가 필드나 DB 컬럼으로 선언하지 않으며 실제 보존 연결은 별도 협의 |

| 버전·파일 근거 | 확인된 값과 상태 |
| --- | --- |
| 문항·나이 추가판의 문서 식별값 | Q/S의 `SIS-Q-1.1-100`·`SIS-WORDING-20261020-1`·`SIS-AGE-1`, 참고 유형 분리 `SIS-TYPE-REF-1`는 §1.1의 문서 참조. 이 중 `SIS-Q-1.1-100`을 설문 `version_code`로 쓰는 것은 R의 BE 결정. 다른 코드와 개인/쌍 규칙 버전의 값까지 일괄 확정하지 않음 |
| 계산 파일 참조 | M의 실행 스냅샷 `fe76ce902422cc218998f7ac73c8d4175c069703` / `docs/handoffs/survey-20261021/mockup/dist/engine100.mjs`는 기존 목업 근거. 그 코드와 입력/나이 처리 등 필요한 파일의 서비스 묶음 목록·불변 버전 대응은 아직 미정이며 신규 서비스 배포/실행 버전이 아님 |
| 제품 정책 참조 | F/P의 `ea74cc53f4cdd5e600b30cbe96100d192eea0773` / F `v0.1.81-draft`·P `v0.1.92-draft`. 제품 게이트 설명의 기준이며 목업의 생활정보 동작·서비스 채택 승인과 구분 |

**BE 검토 제안:** A/B 각각의 완료 응답·개인 결과·당시 스냅샷이 같은 회차/사용자와 의도한 버전에 연결되는지, 묶음이 필요한 계산 파일과 지원 규칙을 가리키는지 확인한다. 다른 회차·진행 중 응답·역순 결과·미지원/80문항 입력을 이 한 쌍에 조용히 섞지 않는다. §3.3의 50/100·coverage 1/1·H=200/3은 여전히 합성 발췌의 문서 기대값이며 이 연결 예제의 실행 결과가 아니다. 실제 Evaluation 판정·제안 생성은 선언하지 않는다. 전달·검증·실행 책임은 §7·§8.1에서 확인한다.

### 3.5 Q98 unknown/미응답: 기존 재현 동작과 PM 확정 정책

**과거 버전 재현용 기존 동작:** M26의 `direction/wishFit`는 Q98이 unknown·누락 등 알려진 close/some/wide가 아니면 성격 여유10·순서 여유1로 계산한다. 이는 some과 같은 **계산상 fallback**이며 저장 원응답을 some으로 바꾸는 동작이 아니다. 범주 수용 목록에는 Q98 폭을 적용하지 않는다. Q98 unknown을 자동 unresolved에 넣지도 않는다. 아래 관찰과 산술은 과거 재현 기록으로 보존하며 신규 매칭에서는 이 fallback을 사용하지 않는다.

| 입력·비교 영향 | 기존 공식으로 확인 가능한 값 | 신규 처리 판단 |
| --- | --- | --- |
| A의 유일 선호 Q69=4, B Q34=2 | close는 [4,4]·fit=1/3→100/3점, some/unknown fallback은 [3,5]·fit=1/2→50점, wide는 [2,5]·fit=1→100점. 이 예의 coverage는 모두1/1 | unknown fallback 하나로 잠정 50 경계 통과가 달라지지만 신규 매칭은 명시적 폭 선택 전 신규 응답 적용 불가 |
| A Q69=3·상대 Q34=unknown, A Q90=[none]·상대 Q55=none | close/some은 Q69 informative=1/observed=0 + Q90 1/1 → coverage=1/2. wide는 Q69가 [1,5] 전구간이어서 분모에서 빠져 coverage=1/1. 관측된 생활영역 점수는 양쪽 경우100 | Q98은 fit뿐 아니라 숫자 선호의 전구간 무정보 여부·coverage 분모에도 영향을 준다. 실제값 unknown과 구분 |
| 범주/실제 시간대 선호 | 범주 목록 비교는 폭과 무관. Q87은 별도로 양쪽 Q52 실제 시간 겹침을 사용 | 영향 없는 항목까지 불일치0으로 바꾸지 않음 |

**PM 확정 정책(Q98-01):** 신규 응답은 close/some/wide 중 하나를 명시적으로 선택해야 한다. unknown/미응답은 사용자 재확인 전 신규 매칭에 적용하지 않고 unknown/누락→some fallback을 금지한다. 기존 유효 결과가 없으면 매칭 보류, 기존 유효 결과가 있으면 이전 결과로 매칭 유지다. 재확인한 응답도 검증·계산·저장 성공 후에만 교체하며 기존 제안·채팅은 유지한다. 완료 원응답에 some을 채우거나 새 설문 버전 라벨을 붙이지 않는다.

부분 산출의 fit·분모/표시 방식은 구현 계약의 잔여 항목이며 이번에 새 산출 규칙이나 Evaluation enum으로 확정하지 않는다. 다른 범주 항목에서 점수·coverage가 나오더라도 Q98 재확인 전 신규 응답의 결과를 활성화할 수 없다. 분모에서 임의 삭제해 coverage를 올리거나 0점 불일치로 대체하지 않는다.

성현은 영향/독립 벡터를 제시하고 BE는 입력/완료·오류 전파를 맞춘다. 명시 선택·재확인·신규 fallback 금지는 추가 PM 승인 대상이 아니다. 신규 27개 fixture는 Q98을 명시한 경계 검산이며 unknown/누락/close/some/wide·전구간 변화와 기존 유효 결과 유지의 공통 벡터는 §5.7 기준으로 후속 구현 검증에 추가해야 한다. 새 matchingVersion/기기 codebook은 미발행이며 기존 fallback을 신규 기본값으로 발행하지 않는다.

## 4. 양방향 나이 조건과 충돌 경계

### 4.1 제품 확정 규칙과 문서 규칙

| 구분 | P의 제품 확정 규칙 | Q/S의 나이 추가 문서 규칙 |
| --- | --- | --- |
| 실제 나이 | 생년월일·Asia/Seoul 기준 만 나이. 가입 만 19세 이상·상한 없음 | 직접 입력한 19~100 정수 또는 null. 선택 정보 |
| 희망 나이 | 첫 매칭 전에 ANY 또는 RANGE를 명시 선택. 양방향 필수 | 선택적 ageProfile.min/max. Q97 age를 고른 방향에서 적용 |
| 범위 | 양 끝 포함, 같은 최소/최대 허용. RANGE max=null은 상한 없음 | Q97 age 선택 시 포함 범위 밖이면 제외 |
| 누락 | 모드 미선택은 기본 조건 미완료. ANY와 다름 | Q97 age 선택 시 범위 누락·min>max·상대 나이 미측정은 보류 |
| 점수·유형 | 기본 자격이며 점수와 별도 | 나이는 성격·유형·매칭 점수에 넣지 않는 별도 필터 |

제품 기준의 방향 판정은 다음과 같다. 유효한 성인 나이·모드·범위를 확인한 뒤 적용하며, 누락·잘못된 입력에 이 식을 바로 적용하지 않는다.

```text
agePass(A→B) = A.mode=ANY
               또는 (A.mode=RANGE 그리고 B.age≥A.min
                     그리고 (A.max=null 또는 B.age≤A.max))
agePass(pair) = agePass(A→B) AND agePass(B→A)
```

ANY는 해당 사람의 선호 제한만 해제한다. 상대 방향 조건과 성인 자격을 해제하지 않는다. RANGE의 max=null은 상한 없음이고, ANY의 min/max=null은 무제약 경계값이며, mode 미선택/null은 입력 미완료다. 99·100을 무제한 대용 값으로 쓰지 않는다. 나이를 생일 이후에도 고정하거나 birthYear만으로 월·일을 추정하지 않는다.

### 4.2 AGE-POL-01: 나이 분리는 해결, 표현·기준일·어댑터는 후속

**제품 기본 나이는 Q97 선택 여부와 무관하게 항상 양방향 검사한다**(F MAT-01·P 89행·PM26). 신규 Q97에서 age를 제거하는 것도 해결됐다. 기존 age 선택 응답은 이전 버전으로만 보존하며 신규 기본 게이트로 자동 변환하지 않는다.

AGE-U01의 나이 거부는 확정이다. AGE-U02~03은 과거 중복 표현의 경계로 보존하고, 신규 정책에서는 별도 기본 나이 표현으로 판정해야 한다. AGE-POL-01에 남은 것은 생년월일/한국 기준일 파생값, ANY/RANGE·무상한·101세 이상·모드 미선택을 구별하는 전달/스냅샷 어댑터다. O26의 19~100 또는 null인 기존 SurveyMetadata를 무상한으로 재해석하거나 100세로 자르지 않는다. 생일·윤일/오프라인 유효성은 SYNC-01에서 합의한다.

## 5. 최소 사례와 기대값

아래는 **문서 검토용 합성 사례**이며 테스트 실행 기록이나 원본 fixture가 아니다. `PASS`는 해당 조건의 통과일 뿐이다. 확정된 제품 규칙의 기대값과 문서상 계산 기대값, 통합 정책 미정 사례를 구분한다. 사례 ID는 과거 ZIP의 M-번호·12개 게이트와 별개다.

### 5.1 제품 나이 규칙에서 기대값을 정할 수 있는 사례

각 나이는 같은 한국 기준일에 유효한 생년월일로 산출했다고 가정한다. 다른 기본 조건·성향 점수 결과를 가정해 전체 통과로 확장하지 않는다. 출처는 F MAT-01 537~544행·P 89~104행이며, 입력 오류/보류 상태명은 §3.2의 계약 초안이다.

| ID | 두 사람의 입력 | A→B / B→A | 나이 조건 기대값 |
| --- | --- | --- | --- |
| AGE-01 | A 27세·RANGE 25~32, B 30세·RANGE 25~35 | PASS / PASS | 양방향 일치. 나이 조건 통과 |
| AGE-02 | A 27세·RANGE 25~32, B 30세·RANGE 28~35 | PASS / FAIL | 한쪽만 불일치해 제외. 종합 점수가 높아도 통과 불가 |
| AGE-03 | AGE-02의 A/B를 서로 바꿈 | FAIL / PASS | 불일치 방향만 바뀌고 쌍의 제외 결과는 같음 |
| AGE-04 | A 27세·ANY, B 30세·ANY. 두 사람 min/max=null | PASS / PASS | 나이 선호 제한 없음. 성인 자격은 유지 |
| AGE-05 | A 27세·ANY, B 30세·RANGE 28~35 | PASS / FAIL | A의 ANY가 B의 조건을 해제하지 않음 |
| AGE-06 | A 100세·RANGE 30~상한 없음(max=null), B 30세·RANGE 100~100 | PASS / PASS | 무상한과 최소값 포함, 단일 나이 범위 적용. 99세 상한 추가 금지 |
| AGE-07 | A 25세·RANGE 32~32, B 32세·RANGE 25~25 | PASS / PASS | 최소=최대 유효, 양 끝 포함 |
| AGE-08 | A 27세·mode 미선택/null, B 30세·ANY | UNRESOLVED / PASS | A의 기본 조건 미완료. min/max가 null이어도 ANY로 추정 금지 |
| AGE-09 | A 나이 미확인·ANY, B 30세·ANY | 성인 자격 UNRESOLVED | 선호 무제약이어도 A의 성인 자격을 확인하지 못함. 매칭 보류 |
| AGE-10 | A 27세·RANGE min=35/max=28, B 30세·ANY | INVALID / PASS | 입력 검증 오류. 범위를 자동 교환하거나 선호 불일치로 저장하지 않음 |
| AGE-11 | 기준일 2026-10-20, A 생년월일 2007-10-21(18세)·ANY, B 30세·ANY | A 성인 자격 FAIL | ANY여도 제외. A가 2007-10-20생이면 19세로 성인 자격만 통과 |

### 5.2 Q97 연결 미정과 확정된 제품 나이 게이트 사례

| ID | 두 사람의 입력 | 확인한 개별 기대값 | 미정·연결 항목 |
| --- | --- | --- | --- |
| AGE-U01 | AGE-02와 같은 나이·범위. 양쪽 Q97에 age 없음 | 제품 기본 나이: A→B PASS / B→A FAIL이므로 제품 게이트 거부 확정. 문서 Q97 필터는 나이를 검사하지 않음 | Q97 미선택·높은 목업 점수로 제품 게이트를 우회하지 못함. 거부 여부는 미정이 아니며 AGE-POL-01은 Q97 연결 방식만 남음 |
| AGE-U02 | A 27세·ANY, B 30세·RANGE 25~35. A Q97에 age 선택 | 제품 기본 나이: 양방향 통과. A의 ANY를 ageProfile.min/max=null로 단순 전달하면 Q는 범위 누락으로 보류 | AGE-POL-01. ANY 표현·중복 입력 연결 결정 전 통합 결과 미정 |
| AGE-U03 | AGE-06의 A를 101세로, B의 희망 범위를 100~상한 없음으로 변경. 양쪽 Q97 age 선택 | 제품 기본 나이: 양방향 통과. A의 101세와 무상한은 Q의 19~100·유한 범위 입력으로 동일하게 표현할 수 없음 | AGE-POL-01. 100세로 자르거나 max=null을 미선택으로 바꾸지 않음 |

### 5.3 특징·선호에서 유도한 문서 기준 계산 사례

Q/S의 공식을 손으로 따라 기대값을 적은 최소 발췌다. 원응답 전체나 개인 계산의 기준 fixture를 복원한 것이 아니다. 이 표에서는 나이 AGE-01, 양쪽 Q97 age 선택과 동일한 유한 ageProfile 범위, 성별·사진·준비 조건 통과를 전제로 둔다. 명시한 선호 외에는 any로 비교 분모에서 제외하고 Q96=any, Q98=some을 사용한다. 생활 정책 충돌을 점수에 숨기지 않는다.

| ID | 두 사람의 실제 특징·선호 입력 | 방향별 기대값·쌍 출력 | 근거·한계 |
| --- | --- | --- | --- |
| PAIR-01 | A 자기 q34=2·선호 Q69=4, B 자기 q34=2·선호 Q69=2 | A→B: 허용 3~5, d=1·M=2, fit=0.5→50점. B→A: fit=1→100점. 양쪽 coverage=1, H=200/3, 문서상 eligible=true | S 163~178·202행. 점수가 실제 특징·선호에서 나오며 외부 가상 점수를 입력한 게이트가 아님 |
| PAIR-02 | PAIR-01에서 B 자기 q34=1로 변경 | A→B fit=0→0점, B→A 100점, 양쪽 coverage=1, H=0, eligible=false | 한 방향 50점 미만. 양쪽 평균·한쪽 고득점으로 통과시키지 않음 |
| PAIR-03 | PAIR-01에서 B 자기 q34=unknown | A→B observed=0/informative=1, coverage=0·score=null. B→A 100점·coverage=1, H=null, eligible=false·정보 부족 보류 | 측정 없음은 fit=0인 불일치와 다름. 원응답을 보충 생성하지 않음 |
| PAIR-04 | A E=30·선호 Q61=low, B E=70·선호 Q61=low | A→B 허용 0~50, fit=0.6→60점. B→A fit=1→100점. 양쪽 coverage=1, H=75, 문서상 eligible=true | S 163~178·203행. 내 특징과 상대 선호를 반대 방향에서 따로 사용 |
| PAIR-05 | 두 사람 모두 Q61~95=any, 실제 특징·유형이 있더라도 비교 선호 없음 | 양쪽 informative=0, coverage=0·score=null, H=null, eligible=false·비교 정보 부족 | 성향 유형·참고 유형이 존재해도 쌍 점수를 100으로 채우지 않음 |

### 5.4 정책·공개 경계 사례

| ID | 입력·상황 | 기대값 또는 미정 |
| --- | --- | --- |
| POL-CASE-01 | A Q90에서 비흡연만 수용·Q97 smoking 선택, B Q55는 수용 목록 밖. 다른 조건은 맞음 | 문서 규칙은 A→B 엄격 제외. 흡연·음주·종교를 점수/제외에 쓰지 않는 제품 목표와 POL-01 충돌. 제품 결과를 확정하거나 -74에서 활성화하지 않음 |
| PUB-01 | 유효한 제안 당사자가 수락 전에 상대 프로필 조회 | 공개 프로필은 제공. 설문 원응답·상대 선호·생년월일 원본·사진 필수 설정은 응답에 포함하지 않음(P 64~67·106행) |
| PUB-02 | 계산 row에 상대의 Q97 선택·상대 선호 수치가 내부 근거로 존재 | reasonKeys만으로 공개 허용하지 않음. 허용 목록·공개 근거 검토 전 상대 선호를 노출하는 설명은 생성·반환하지 않음 |

### 5.5 기본 성별 조건의 문서 사례

출처는 F MAT-01 536~540행·P 232~236행이다. 본인 성별은 MALE/FEMALE이고 대상은 BE가 반대로 자동 산출한다. 아래 목록 표기는 서버 산출값 또는 그와 다른 변조/오래된 입력을 설명하며, 사용자의 자유 선택 입력이 아니다. 저장된 본인 성별로 대상값을 검증한 뒤 상대가 그 대상에 맞는지 양방향 검사한다. 본인 성별이 미확인이면 대상도 임의 산출하지 않는다. 각 기대값은 **성별 조건/입력 검증만의 결과**이며 전체 매칭을 보장하지 않는다. 상태명은 §3.2의 제안이고 실행 통과 기록이 아니다.

| ID | 두 사람의 성별·서버 대상 또는 수신값 | A→B / B→A | 성별 조건·입력 검증 기대값 |
| --- | --- | --- | --- |
| GENDER-01 | A MALE→서버 대상 FEMALE, B FEMALE→서버 대상 MALE | PASS / PASS | 자동 산출한 대상에 서로 해당. 성별 조건 통과 |
| GENDER-02 | A MALE→대상 FEMALE. B FEMALE인데 수신 대상값이 FEMALE인 변조/오래된 입력 | 미평가 / 미평가 | 정상 자유 선택의 불일치가 아님. BE가 저장된 B 성별 기준 MALE과 다른 수신값의 우회를 거부하고 최신 상태 확인. 원래 입력 그대로 정상 판정하지 않음. 오류 형식·재조회 방식은 계약 미정 |
| GENDER-03 | A MALE→서버 대상 FEMALE, B 본인 성별 미확인인데 대상 MALE 수신 | UNRESOLVED / UNRESOLVED | B 프로필 미완료·서버 대상 산출 불가로 보류. 수신 대상값만으로 B→A를 PASS로 확정하지 않으며 무제약/불일치로 바꾸지 않음 |

### 5.6 사진 옵션의 문서 사례

출처는 F MAT-02 551~557행·P 179·189~200행이다. A→B는 A의 `requirePartnerPhoto`와 B의 `hasProfilePhoto`를 비교하고 B→A는 반대다. ON/OFF는 저장된 옵션을 성공적으로 조회한 값이며, 사진 등록/미등록/미확인을 구분한다. 각 기대값은 **사진 조건만의 결과**이며 전체 매칭·제안 생성을 보장하지 않는다. 실행 통과 기록이나 사진 옵션의 점수 가감 규칙이 아니다.

| ID | A 옵션 / B 옵션 | A 사진 / B 사진 | A→B / B→A | 사진 조건 기대값 |
| --- | --- | --- | --- | --- |
| PHOTO-01 | ON / OFF | 등록 / 미등록 | FAIL / PASS | A만 ON이므로 사진 없는 B가 A의 조건을 충족하지 못함 |
| PHOTO-02 | OFF / ON | 미등록 / 등록 | PASS / FAIL | B만 ON이므로 사진 없는 A가 B의 조건을 충족하지 못함 |
| PHOTO-03 | OFF / OFF | 미등록 / 미등록 | PASS / PASS | 양쪽 모두 사진을 요구하지 않음. 사진 없음만으로 제외하지 않음 |
| PHOTO-04 | ON / ON | 등록 / 등록 | PASS / PASS | 양쪽 모두 상대 사진 요구를 충족 |
| PHOTO-05 | ON / OFF | 등록 / 미확인 | UNRESOLVED / PASS | A가 요구하는 B 사진 상태를 확인하지 못함. 미확인을 미등록으로 단정하거나 통과시키지 않음 |
| PHOTO-06 | 저장 옵션 조회 실패 / OFF | 등록 / 미등록 | UNRESOLVED / PASS | A의 저장 옵션을 초기 기본값 OFF로 대체하지 않음. 조건을 확인할 수 없는 보류이며 오류 전달·재조회 방식은 미정 |

초기 기본값 OFF는 최초 설정에 관한 제품 결정이다. 저장된 설정의 조회 실패를 OFF로 간주하는 근거가 아니다. 사진 상태는 그 방향에서 ON일 때 필요한 값이며, 옵션 변경은 P의 저장 완료 후 새 매칭 적용 범위를 따른다.

### 5.7 PM26 신규 가상 사례 27건과 독립 검산

**기존 §5.1~5.6의 31개 ID·입력·기대값은 원문 보존**했다. AGE-U01은 신규 기본 나이에서도 거부하며, AGE-U02~03 및 PAIR 표의 Q97 age 전제는 기존 버전 재현용이다. 신규 회차는 age 없는 Q97과 별도 나이 설정을 사용한다. POL-CASE-01의 과거 정책 충돌은 PM26으로 해결됐으며 smoking 선택+구체 불일치는 신규 기대값상 엄격 제외다. PUB/GENDER/PHOTO의 공개·기본 조건 경계는 유지한다.

앞선 분석 채팅에서 작성한 합성 입력·신규 기대값 27건은 **저장소에서 공유하는 [JSON fixture][T26-cases]**로 추가했다. 최초 추가 시 원본 `synthetic-test-drafts.json`을 바이트 그대로 복사했으며, v0.1.6에서 PM 확정 정책과 충돌하는 Q97_UNKNOWN·STRICT_WITH_ANY_PREFERENCE의 기대 의미만 정정했다. 이번 v0.1.7은 EOF LF 한 개만 추가하며 JSON 데이터는 변경하지 않는다. 입력·기존 관찰·NOT_IMPLEMENTED·실행 이력은 보존하고 현재 공유본과 최초 원본의 SHA-256을 아래에 구분한다. 이 파일은 실제 사용자 데이터가 아닌 검토용 합성 입력/기대값이고, 실행 결과나 신규 정책 구현물이 아니다.

분석 계획·기존 엔진 관찰·기존 테스트 기록 세 파일은 **로컬에 보존된 참고 자료**이며 이번 저장소/MR 공유 파일에 포함하지 않는다. 아래에는 파일명·기준 버전·해시만 출처로 유지한다. 로컬 보존 폴더는 `C:/Users/SSAFY/.codex/visualizations/2026/10/25/01a17539-7954-7031-a835-e791f5bf5d22/tingle-mvp-policy-20261026/`이며 공유 링크가 아니다. 합성 입력 원본도 이 폴더에 그대로 보존한다. 본문의 27건 목록과 대표 산술은 공유 JSON과 함께 검토할 수 있다.

| 자료 | 연결·출처 | SHA-256 |
| --- | --- | --- |
| 분석 계획 | 로컬 참고 `analysis-and-change-plan.ko.txt` / 2026-10-26·기준 f5224c9 | `3731f1ab4f5a491a8d7a740a58dd012a28ce4e11bdf42268df471e30ce5ffc41` |
| 전체 합성 입력·신규 기대값 | 현재 공유 [mvp-policy-20261026-cases.json][T26-cases] / 155,142바이트·전체 100문항 A/B·27건 / Q97 두 사례 정정 후 EOF LF 한 개 추가 | `9bedd16058295211224008b6553099300c72358fa85f478f980879e174b540a8` |
| 최초 합성 입력·신규 기대값 원본 | 로컬 보존 `synthetic-test-drafts.json` / 최초 공유본·§9.11 당시 해시 / 현재 기대 의미 정정 전 | `a1f26e73b01003676e595822869983f8b669c19ebf7992f1d2ebbd04f64c26b9` |
| 기존 엔진 관찰 | 로컬 참고 `legacy-observations.json` / 26건 관찰·1건 미실행 | `93c3378e11de2c529b0963df989e3155869068d55fc627580cef976619b524f2` |
| 기존 테스트 실행 기록 | 로컬 참고 `existing-test-results.json` / 기존 4개 exit 0 | `fab70f540db1895a7b01b6884cdc627efde4d6e7e397707ed67b499d049156e8` |

[T26-cases]: <./fixtures/mvp-policy-20261026-cases.json>

합성 입력 용어: **S**은 JSON의 NORMAL 전체 응답에서 Q61~95=any로 두고 양쪽 Q34=3/Q69=3, Q96=any/Q97=any/Q98=some으로 구성한 가상 기준이다. **T**는 S에서 양쪽 Q30~39=3, Q65~74=3, Q98=close로 바꾼 가상 기준이다. 나머지 실제 응답은 JSON에 보존하며 중간값으로 실제 사용자의 누락을 메우는 규칙이 아니다. 기본 나이는 A27·RANGE25~32, B30·RANGE25~35이고, 반대 성별·사진 조건·준비/관계 게이트는 별도 충족 가정이다.

JSON의 `input`은 **기존 엔진 관찰 입력**(기존 surveyVersion·ageProfile 포함)이고 `newPolicyDraft`는 신규 구현용 의미 기대값이다. 이를 신규 버전 완료 응답으로 인정하거나 파일에 새 version 라벨만 붙이지 않는다. 특히 AGE_ANY_AND_UNBOUNDED는 기존 ageProfile 대신 `basicAgeReview`의 A102/RANGE100~무상한·B103/ANY를 검토한다. 표의 후보는 50점/70% 최종 승인 및 신규 구현/지원 버전·모든 기본 게이트 충족을 조건으로 한 기대값이다. Q97_UNKNOWN의 expectedEligible=false와 STRICT_WITH_ANY_PREFERENCE의 보류는 **해당 신규 응답의 결과를 활성화할 수 없음**을 뜻하며 이전 유효 결과를 무효화하는 값이나 새로운 Evaluation enum이 아니다. **신규 기대값은 27건 모두 NOT_IMPLEMENTED이며 신규 PASS는 0건**이다.

| 신규 사례 ID (JSON과 동일) | 핵심 가상 입력 | 기존 M26 관찰 | PM26 신규 기대값·검토 상태 |
| --- | --- | --- | --- |
| NORMAL | S·기본 나이 충족 | 100/100·coverage 1/1·true | 조건부 후보 |
| SCORE_EXACT_50 | S에서 양쪽 Q34=2/Q69=4 | 50/50·H=50·true | 잠정 50 포함 경계 |
| Q97_ANY_SCORE_LOW | S에서 A Q69=4·B Q34=1 | 0/100·false | Q97=any는 엄격 제외 없이 일반 매칭하되 점수 미달 |
| ASYMMETRIC_50_100 | 양쪽 Q34=2, A Q69=4·B Q69=2 | 50/100·H=200/3·true | 방향별 기준 충족 시 후보 |
| DIRECTION_BELOW_50_HARMONIC_50 | S에서 A Q69=4/Q98=close·B Q34=2 | 100/3과100·H=50·false | H≥50만으로 통과 불가 |
| COVERAGE_EXACT_70 | T에서 양쪽 Q30~32=unknown | 점수100/100·7/10 양쪽·true | 잠정 70% 포함 경계 |
| COVERAGE_BELOW_70 | 위에서 B Q33도 unknown | 점수100/100·6/10과7/10·false | 정보 부족·후보 보류 |
| STRICT_SMOKING_HIGH_SCORE | T+A Q90=[none]/Q97=[smoking]·B Q55=cigarette | 75/100·excluded smoking·false | 높은 점수여도 엄격 제외 |
| SOFT_SMOKING_NO_FLAG | 위 흡연 불일치, A Q97=any | 75/100·excluded 없음·true | 일반 점수 감점만 적용 |
| STRICT_RELIGION_HIGH_SCORE | T+A Q91=[none]/Q97=[religion]·B Q56=buddhist | 75/100·excluded religion·false | 선택한 종교 엄격 제외 |
| STRICT_CHILDREN_HIGH_SCORE | T+A Q93=[yes]/Q97=[children]·B Q58=no | 75/100·excluded children·false | 선택한 자녀 엄격 제외 |
| STRICT_TATTOO_HIGH_SCORE | T+A Q95=[none]/Q97=[tattoo]·B Q60=visible | 75/100·excluded tattoo·false | 선택한 문신 엄격 제외 |
| DRINKING_LOWER_ALLOWED | S+A Q89=2/Q98=close·B Q54=1 | 100/100·excluded 없음·true | 낮은 음주 빈도 허용 |
| DRINKING_OVER_PREFERENCE | 위에서 B Q54=5 | 50/100·excluded 없음·true | 일반 점수 하락, 엄격 제외 없음 |
| DRINKING_FULL_INTERVAL | S+A Q89=5/Q98=close·B Q54=5 | Q89 row 생략·A informative=1·true | 전구간은 무정보 처리 유지 검토 |
| AGE_DEFAULT_MISMATCH | S·Q97=any, B 희망28~35·A27 | 100/100·true | 기본 나이 B→A 불일치로 제외 |
| AGE_DEFAULT_MISSING | S·A 기본 나이 정보 없음·Q97=any | 100/100·true | 기본 조건 미완료 보류·ANY 추정 금지 |
| LEGACY_Q97_AGE | S+A Q97=[age] | 기존 유효·범위 충족 true | 신규 입력 거부·기존 원본/재현 보존 |
| UNKNOWN_ACTUAL | S에서 B Q34=unknown | A score=null·coverage=0·false | 정보 부족·0점 대체 금지 |
| UNKNOWN_STRICT_ACTUAL | S+A Q90=[none]/Q97=[smoking]·B Q55=unknown | A coverage=1/2·unresolved smoking·false | 비흡연 추정 금지·미확인 보류 |
| STRICT_WITH_ANY_PREFERENCE | S+A Q97=[smoking]·Q90=any | unresolved smoking·false | Q90 범위 재선택 또는 smoking 엄격 선택 해제 전 신규 응답 결과 활성화 금지. 이전 유효 결과는 유지 |
| Q97_UNKNOWN | S+A Q97=unknown | 엄격 루프 생략·true | 사용자 재확인 전 신규 응답 적용 불가(expectedEligible=false). any와 구분하며 이전 유효 결과는 유지 |
| NO_INFORMATIVE_PREFERENCES | S에서 A Q69도 any | A informative=0·score=null·coverage=0·false | 비교 정보 없음 |
| Q97_TOO_MANY_FLAGS | S+A smoking/children/religion 3개·수용 범위 모두 일치 | validAnswer=false, raw pair=true | 계산 전에 입력 거부·최대2 검사 |
| Q97_DRINKING_INVALID | S+A Q97=[drinking] | validAnswer=false, raw pair=false/unresolved | 스키마 입력 거부·엄격 음주 코드 추가 금지 |
| MIXED_VERSIONS | 동일 S 입력의 old/new 회차 조합 | raw pair는 버전을 받지 않아 true | 명시적 호환 없으면 보류·자동 혼용 금지 |
| AGE_ANY_AND_UNBOUNDED | basicAgeReview A102·100~무상한, B103·ANY | 기존 19~100 어댑터로 미실행 | 기본 나이 통과 예상, 신규 표현/어댑터 미구현 |

대표 사례의 입력 → 중간값 → 기대 결과는 다음과 같다. 아래 산술은 기준 공식으로 검산한 값이며 신규 엔진 실행 로그가 아니다.

| 사례 | 입력과 중간값 검산 | 기대 결과·한계 |
| --- | --- | --- |
| SCORE_EXACT_50 | Q69=4/some → 허용[3,5], 상대 Q34=2 → d=1·M=2·fit=1/2. 양쪽 유일 정보 영역이므로 S_AB=S_BA=50, observed/informative=1/1, H=50 | 기존 포함 경계와 일치. 최종 50 제품 승인 대기 |
| ASYMMETRIC_50_100 | 위 A 방향 50, B Q69=2/some → [1,3], A Q34=2 → fit=1·100. H=2×50×100/150=200/3 | 한쪽 50·반대100의 조건부 후보 |
| DIRECTION_BELOW_50_HARMONIC_50 | A Q69=4/close → [4,4], B Q34=2 → d=2·M=3·fit=1/3 → 100/3. B 방향100, H=50 | 한 방향 50 미만이므로 false. 표시 반올림/H로 우회 금지 |
| COVERAGE_EXACT_70 / BELOW_70 | Q65~74 열 선호 중 상대 Q30~32 누락 → informative=10·observed=7. 관측 fit 모두1, 비어 있는 영역은 평균에서 제외하여 점수100. B Q33도 unknown이면 A 방향 observed=6 | coverage=7/10 경계 후보, 6/10은 보류. 100문항 응답률/35 고정 분모와 다름 |
| STRICT_SMOKING_HIGH_SCORE | T의 관계 시작·연락·데이트 3영역 fit=1, A의 흡연 생활영역 fit=0. Q96=any → (1+1+1+0)/4=0.75, A 관측11/정보11. B는 3영역100·10/10. H=600/7 | Q97 smoking 위반으로 false. SOFT 사례는 같은 75/100·coverage1/1에서 엄격 제외 없이 조건부 후보 |
| STRICT_RELIGION/CHILDREN/TATTOO_HIGH_SCORE | 위 T에서 각각 Q91/Q56, Q93/Q58, Q95/Q60 불일치로 해당 4번째 영역 fit=0·다른 3영역1 | 각각 75/100 및 선택 flag 제외. 묶어 쓴 표제이며 실제 ID는 위 3건 |
| DRINKING_OVER_PREFERENCE | A Q89=2/close → [1,2], B Q54=5 → d=3·M=3·fit=0. S의 연락 fit=1·생활 fit=0 → A50, 관측2/정보2. B100·1/1, H=200/3 | drinking 엄격 제외 없이 잠정 기준 후보. B Q54=1이면 음주fit=1→A100. Q89=5면 전구간 무정보로 Q89 row 제외 |
| AGE_DEFAULT_MISMATCH | A27·희망25~32/B30·희망28~35, Q97=any, 점수100/100. A→B 30∈[25,32], B→A 27∉[28,35] | 기존 raw pair=true여도 신규 기본 나이 false. 성별/사진 등은 이 실행에서 검증한 것이 아님 |

27개 목록은 기존 31개 문서 사례·SVC 4건·REASON 1건에 더한 신규 검토 자료다. 확정 정책 기대값·남은 구현 계약·기존 실행 관찰을 구분했으며 신규 정책 서비스/키링 통합 통과를 주장하지 않는다.

다음은 후속 구현 검증에 적용할 **문서상의 기대 처리 기준**이다. fixture에 새 ID/입력/실행 결과를 추가한 것이 아니며 기존 27건의 개수·실행 상태를 유지한다.

| 입력/상태 | 확정 기대 처리 | 후속 확인 기준 |
| --- | --- | --- |
| Q97=unknown 또는 미응답 | 사용자 재확인 전 신규 응답 적용 불가. any 자동 치환 금지 | unknown은 Q97_UNKNOWN과 연결. 미응답도 같은 적용 제한이며 둘 다 신규 결과 활성화 전 재확인 |
| Q97 엄격 선택 + 연결 허용 범위 any/unknown/미응답 | smoking→Q90, children→Q93, religion→Q91, tattoo→Q95 중 선택한 항목의 범위를 재선택하거나 해당 엄격 선택 해제 | STRICT_WITH_ANY_PREFERENCE의 기대 의미를 네 항목·세 미확인 상태로 검증. 해결 전 신규 결과 활성화 금지 |
| Q98=unknown 또는 누락 | close/some/wide 중 명시 선택을 재확인. 신규 unknown/누락→some fallback 금지 | 두 상태 각각 확인. §3.5의 과거 50점/coverage1 관찰을 신규 결과로 저장·활성화하지 않음 |
| 기존 유효 결과 없는 신규 가입자 | 신규 검증·계산·저장 모두 성공 전 매칭 보류 | 재확인 중·검증 거부·계산 실패·저장 실패 어느 단계에서도 미완성 결과로 참여하지 않음 |
| 기존 유효 결과 있는 재검사 회원 | 재확인·검증·계산·저장 대기/실패 중 이전 유효 결과로 매칭 유지 | 신규 응답 미적용 때문에 이전 결과·기존 제안·채팅을 무효화하지 않음 |
| 신규 검증·계산·저장 모두 성공 | 새 유효 결과로 교체. 기기별 설정/규칙 적용 확인·BE 최신 제한 재검증 유지 | 원래 완료 응답/구버전 Q97 age·기존 제안/채팅 보존. 새 설문 버전 자동 재라벨링 금지 |

### 5.8 Q87-PRIORITY-01 — 실제 시간 불일치와 정보 부족의 처리 순서

MR !32 추가 리뷰 R1을 고정 M26 원본으로 재현했다. 실행 기준은 §1.5의 `f5224c9aad434e4e1b54df8f3f17d6f84eb48897`이며 `engine100.mjs` SHA-256은 `1b45d79d88b90bc407170acab69b32e29ad1efbf034fdb8536128a3051436893`다. Node.js v24.18.0에서 실행 전 engine100/data100/age100/initial-profiles/wording100 및 catalogue의 바이트를 이 고정 Git blob과 대조했다. 독립 matchingVersion export는 없으며 신규 엔진/버전 발행 결과가 아니다.

**입력 기준과 자료형:** [JSON fixture의 NORMAL][T26-cases] 전체 양쪽 100문항·ageProfile을 복제하고 **A Q87=`['irregular']`, A Q52=`['morning']`, B Q52=`['evening']` 세 필드만 변경**한다. B Q87=`'any'`, 양쪽 Q34=3/Q69=3·Q96=any/Q97=any/Q98=some·나이 메타데이터는 유지한다. catalogue의 Q052/Q087은 morning/afternoon/evening/night/irregular의 중복 없는 1~5개 문자열 배열이며 unknown은 별도 단독 문자열이다. Q087만 단독 any도 허용한다. morning/evening을 단일 문자열로 전달하지 않는다. NORMAL의 전체 200개 응답과 변경된 입력은 기존 `validAnswer` 검사도 통과했다.

**확인한 원본 분기 순서:** `direction`은 any/unknown/미응답 선호를 먼저 건너뛴다. 비교 대상 Q87은 informative를 1 늘리고 `wishFit`을 호출한다. `wishFit`은 선호나 상대 실제값이 any/unknown/미응답이면 먼저 null을 반환한다. 그 외 Q87 분기는 상대 Q52와 선호에서 irregular를 제거한 목록 중 하나라도 비면 null, 아니면 교집합에 따라 1/0을 반환한다. 이어 direction이 **양쪽 원응답 Q52에서 irregular를 뺀 실제 시간대**를 확인한다. 어느 쪽이든 구체 시간이 없으면 fit=null로 덮어쓰고, 양쪽에 구체 시간이 있지만 교집합이 없으면 **앞선 null도 fit=0으로 덮어쓴다.** 실제 시간이 겹치면 앞선 선호 fit을 유지하므로 irregular만인 선호는 null로 남는다. 시간 정보가 유효한 입력이라는 전제이며 raw pair를 전체 catalogue 검증기로 사용하지 않는다.

**독립 기대값과 실제값:** 우선순위 사례의 초기 wishFit은 null이지만 실제 morning/evening 불일치로 최종 Q87 fit=0이다. A는 Q69(connection) fit=1·Q87(lifestyle) fit=0, 각 영역 weight=1이므로 `S_AB=100×(1+0)/2=50`, observed/informative=2/2·coverage=1이다. B는 Q87=any로 건너뛰며 Q69만 fit=1이므로 `S_BA=100`·1/1·coverage=1이다. 쌍 점수는 `H=200/3`(실제 Number 출력 66.66666666666667), 양쪽 excluded/unresolved=[]·기존 `pair.eligible=true`로 독립 기대와 일치했다. **관측 가능한 0점은 영역 평균·coverage 관측수와 분모에 모두 포함**한다. 정보 부족을 먼저 확정해 Q87을 빼면 잘못된 A100·1/2·coverage0.5가 된다.

아래 대조도 같은 NORMAL 기반으로 실행했다. 각 행은 A Q52/B Q52/A Q87만 표시하며 B Q87=any를 유지한다. Q87 null은 row가 생성되지 않는다는 뜻이다. 모든 행의 B→A는 100점·관측/정보=1/1·coverage=1이다.

| 대조 | A Q52 / B Q52 / A Q87 | A Q87 fit | A 점수·관측/정보·coverage | 기존 pair.eligible |
| --- | --- | --- | --- | --- |
| Q87-PRIORITY-01 | [morning] / [evening] / [irregular] | 0 (초기 null 덮어쓰기) | 50·2/2·1 | true |
| 실제 겹침·선호 irregular만 | [evening] / [evening] / [irregular] | null | 100·1/2·0.5 | false |
| A 실제 irregular만 | [irregular] / [evening] / [evening] | null (초기 1 덮어쓰기) | 100·1/2·0.5 | false |
| B 실제 irregular만 | [morning] / [irregular] / [morning] | null | 100·1/2·0.5 | false |
| 실제 겹침·선호 일치 | [evening] / [evening] / [evening] | 1 | 100·2/2·1 | true |
| 실제 겹침·선호 불일치 | [evening] / [evening] / [morning] | 0 | 50·2/2·1 | true |
| any 선호 건너뛰기 | [morning] / [evening] / any | 미평가 | 100·1/1·1 | true |

후보 가능은 **잠정 방향별 50점·coverage 70% 및 별도 기본 나이·성별·사진·준비/관계 게이트 충족 가정**이다. 이 fixture의 기본 나이 범위는 양쪽 충족하나 raw pair가 해당 제품 기본 게이트를 모두 검사한 것은 아니다. 50/70 최종 제품 승인·신규 정책 구현·서비스/키링 검증으로 확대하지 않는다. 이 사례와 대조는 문서에만 추가하며 기존 27개 fixture ID·입력·기대값과 NOT_IMPLEMENTED를 보존한다.

재현 명령(저장소 루트, PowerShell·Node.js, 추가 의존성 없음). 기대값은 위 독립 산술에서 상수로 지정하고 원본 출력과 단언 비교한다.

```powershell
@'
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const fixed = 'f5224c9aad434e4e1b54df8f3f17d6f84eb48897';
const root = 'docs/handoffs/survey-20261021/mockup/dist/';
const catalogue = 'docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json';
for (const p of [catalogue, ...['engine100.mjs', 'data100.mjs',
  'age100.mjs', 'initial-profiles.mjs', 'wording100.mjs'].map(n => root + n)]) {
  assert.deepEqual(fs.readFileSync(p), execFileSync('git', ['show', fixed + ':' + p]));
}
const {pair, wishFit} = await import('./' + root + 'engine100.mjs');
const {validAnswer} = await import('./' + root + 'data100.mjs');
const fixture = JSON.parse(fs.readFileSync(
  'docs/contracts/matching/fixtures/mvp-policy-20261026-cases.json', 'utf8'));
const normal = fixture.cases.find(c => c.id === 'NORMAL').input;
const catalogueData = JSON.parse(fs.readFileSync(catalogue, 'utf8'));
for (const id of ['Q052', 'Q087']) {
  const q = catalogueData.questions.find(q => q.questionId === id);
  const array = q.responseSchema.oneOf.find(s => s.type === 'array');
  assert.deepEqual(array.items.enum, ['morning', 'afternoon', 'evening', 'night', 'irregular']);
  assert.equal(array.uniqueItems, true);
  assert.equal(array.minItems, 1); assert.equal(array.maxItems, 5);
  assert.equal(q.allowUnknown, true); assert.equal(q.allowAny, id === 'Q087');
}
const vectors = [
  ['Q87-PRIORITY-01', ['morning'], ['evening'], ['irregular'], null, 0, 50, 2, 2, true],
  ['overlap-irregular', ['evening'], ['evening'], ['irregular'], null, null, 100, 1, 2, false],
  ['own-irregular', ['irregular'], ['evening'], ['evening'], 1, null, 100, 1, 2, false],
  ['other-irregular', ['morning'], ['irregular'], ['morning'], null, null, 100, 1, 2, false],
  ['wish-match', ['evening'], ['evening'], ['evening'], 1, 1, 100, 2, 2, true],
  ['wish-mismatch', ['evening'], ['evening'], ['morning'], 0, 0, 50, 2, 2, true],
  ['any-skip', ['morning'], ['evening'], 'any', null, null, 100, 1, 1, true]
];
for (const [id, a52, b52, a87, initial, fit, score, observed, informative, eligible] of vectors) {
  const {left: a, right: b} = structuredClone(normal);
  a.answers[52] = a52; b.answers[52] = b52; a.answers[87] = a87;
  for (const side of [a, b]) {
    assert.deepEqual(Object.keys(side.answers).map(Number), Array.from({length: 100}, (_, i) => i + 1));
    for (const [n, value] of Object.entries(side.answers)) assert.equal(validAnswer(+n, value), true);
  }
  assert.equal(wishFit(87, a87, b52, a.answers[98]), initial);
  const p = pair(a.answers, b.answers, a.ageProfile, b.ageProfile);
  const values = d => [d.score, d.observed, d.informative, d.coverage];
  assert.equal(p.ab.rows.find(r => r.id === 87)?.fit ?? null, fit);
  assert.deepEqual(values(p.ab), [score, observed, informative, observed / informative]);
  assert.deepEqual(values(p.ba), [100, 1, 1, 1]);
  for (const d of [p.ab, p.ba]) {
    assert.deepEqual(d.excluded, []); assert.deepEqual(d.unresolved, []);
  }
  assert.ok(Math.abs(p.score - (score === 50 ? 200 / 3 : 100)) < 1e-10);
  assert.equal(p.eligible, eligible);
  console.log(JSON.stringify({id, fit, ab: values(p.ab), ba: values(p.ba), score: p.score, eligible: p.eligible}));
}
'@ | node --input-type=module
```

## 6. POL-01과 비공개 근거의 경계

**POL-01의 정책 방향 충돌은 PM26으로 해결됐다.** 흡연·음주·종교는 일반 선호 점수에 반영하고, Q97에서 고른 smoking/religion 및 children/tattoo만 구체 불일치 시 엄격 제외한다. drinking 엄격 제외는 없다. 이전 F/P·Jira의 ‘생활정보 비반영/제거’는 과거 정책이며 현재 기대값의 기준이 아니다. 이 결정으로 실제값 출처·변환 불가·신규 파일/호환·배포 합의까지 해결된 것은 아니다. §6.2의 출처 추천안과 버전 검증 전 실제 연동을 활성화하지 않으며, 원응답 any 치환·row 삭제·동일 버전 재사용으로 충돌을 숨기지 않는다.

내부 계산에는 판정에 필요한 최소 특징·비공개 선호를 사용하되 상대용 공개 프로필/API, 키링 방송, AI 설명 입력을 같은 데이터로 취급하지 않는다. 유효 제안의 수락 전 공개 프로필과 상호 수락 후 채팅을 구분한다. 원응답·선호는 수락 이후에도 자동 공개되지 않는다.

S는 fit≥0.7인 내 선호 충족·다른 특성의 상대 선호 충족·조율 차이(fit<1)를 최대 3개 선정하고 Q100으로 순서를 조정한다고 설명한다. 이 선정 규칙은 공개 동의가 아니다. 특히 ‘상대가 어떤 사람을 원한다’는 이유, Q97 선택·제외 사유·수치로 비공개 선호를 역으로 드러낼 수 있다. `reasonKeys/rows/excluded/unresolved`는 내부용으로 두고, -46에서 PM·FE·BE·AI가 공개 허용 근거를 정한 뒤 필요한 정보만 설명에 제공한다. 공개 프로필에 있는 실제 정보도 비공개 선호와 조합해 자동 공개하지 않는다.

### 6.1 -46 인계용 내부 근거 예시 1건

**REASON-SAMPLE-01**은 기존 PAIR-01 및 SVC-01의 B→A 한 항목을 연결한 문서 예시다. 실제 저장 row나 실행 로그를 가져온 결과가 아니며, 아래 참조·키 형식은 설명용이다. 내부 근거를 검토한 뒤 공개 허용 근거를 따로 선정하는 순서를 보여준다.

| 내부 연결 항목 | 예시 값·근거 |
| --- | --- |
| 방향·문항/특징 | B→A, B의 `preferences.q69` ↔ A의 `features.q34`. 영역은 연락·함께하는 시간 |
| 합성 사례의 비교 근거 | B의 선호 2·Q98=some → 허용 1~3, A의 실제 특징 2 → d=0·M=2·fit=1. 이 사례에서는 유일한 비교 선호이므로 B→A 100점·coverage=1 |
| `reasonKeys` 연결 제안 | `<REASON-SAMPLE-01:B→A:Q69/q34>`는 O의 `string[]`에 들어갈 문자열 한 개의 설명용 예시일 뿐, 확정 enum·공개 문구가 아님 |
| 판정 버전 참조 | §3.3의 Q/S/P 고정 참조·M 스냅샷을 구분해 연결. 실제 새 쌍 규칙 버전과 호환 기준은 VERSION-01 미정 |
| 입력 참조 | B 선호는 `<SVC-01:B:responseRef>`, A 특징은 `<SVC-01:A:responseRef>` 및 `<SVC-01:A:personalResultRef>`에 연결하는 제안. 양쪽 `<SVC-01:A:snapshotRef>`·`<SVC-01:B:snapshotRef>`로 판정 당시 설정을 연결. 모두 미정 식별자의 자리표시자이며 실제 조회한 값이 아님 |
| -46 검토 경계 | 내부 fit·방향·버전·입력 참조의 연결까지만 예시화. 공개 허용 목록·AI 입력은 PUB-REASON-01 미정이며 비공개 선호·원응답·이 내부 기록을 사용자 응답이나 LLM 입력으로 자동 전달하지 않음 |

### 6.2 생활정보 실제값 출처·프로필 차이와 구체 추천안

현재 M26은 완료 설문의 숫자 키 실제값을 `profileFromAnswers`로 읽으며 BE Profile을 직접 읽지 않는다. 서비스의 원본은 `survey_responses.version_code + response_json.answers`이고 BE 문항 ID는 Q001~Q100이다. 숫자 키 1~100 대응은 의미를 바꾸지 않는 명시적 어댑터로 검증한다. 선호는 항상 선호를 가진 사람의 완료 응답에서 읽고, 개인 유형명/해석 문장으로 실제값·선호를 역산하지 않는다.

| 비교 항목 | 설문 실제값·상대 선호·엄격 flag | 최신 프로필 차이·매핑 한계 |
| --- | --- | --- |
| 음주 | 상대 Q054(1~5) ↔ 내 Q089(최대 수용 빈도), 엄격 flag 없음 | Profile.drinkingFrequency 6종. NEVER→1은 직접 대응 후보. SPECIAL_OCCASIONS_ONLY는 상황으로 빈도 특정 불가. MONTHLY_AT_MOST_ONCE는 설문 ‘월1회 미만/월1~3회’ 경계와 다르고 MONTHLY_TWO_TO_FOUR도 ‘월1~3회/주1회 정도’에 걸침. WEEKLY_TWO_TO_THREE/WEEKLY_FOUR_OR_MORE→5(주2회 이상)는 정보 손실 있는 단방향 후보 |
| 흡연 | 상대 Q055 none/cigarette/electronic/both/quitting ↔ 내 Q090 수용 목록, smoking | NON_SMOKER→none, 명시적 E_CIGARETTE→electronic은 의미상 직접 대응 후보. SMOKER는 흡연 전반을 뜻하는 포괄적 선택값으로 일반담배만·전자담배만·일반담배와 전자담배 병용 사용자 모두 선택할 수 있다. 프로필 SMOKER만으로 Q055의 cigarette/electronic/both 등 구체 범주를 자동 추정하거나 손실 없이 변환할 수 없다. ONLY_WHEN_DRINKING은 흡연 상황으로 담배 종류 자동 변환 불가. both/quitting에 동일한 세부 프로필 코드 없음 |
| 종교 | 상대 Q056 none/protestant/catholic/buddhist/won/muslim/other ↔ 내 Q091 수용 목록, religion | NONE/PROTESTANT/CATHOLIC/BUDDHIST는 각각 직접 대응 후보. 프로필 OTHER에서 won/muslim/other 특정 불가. 설문 세 항목을 OTHER로 합치면 복원 불가. null(미입력)·UNDISCLOSED(비공개)·NONE(무교)을 구분 |
| 자녀 계획 | 상대 Q058 yes/no/undecided ↔ 내 Q093 수용 목록, children | 현재 Profile에 대응 필드 없음. 완료 설문이 실제값 출처 후보 |
| 문신 | 상대 Q060 none/covered/visible/cosmetic ↔ 내 Q095 수용 목록, tattoo | 현재 Profile에 대응 필드 없음. Q060을 나이 필드로 해석하지 않음 |
| 나이 | 점수에서 제외, 별도 기본 게이트 | Profile.birthDate와 한국 기준일의 만 나이·명시적 ANY/RANGE·상한 없음 설정을 스냅샷에 연결. 설문 메타데이터 19~100 값과 다른 의미 |

위 ‘직접 대응 후보’도 기존 응답 변환 허가가 아니다. `unknown`·`any`·미응답은 실제 범주와 다르며, 종교 미입력/비공개를 무교로 채우거나 흡연 상황을 일반담배로 추정하지 않는다. 프로필과 설문은 수집 시점·목적이 달라 내용이 다를 수 있다.

**최소 변경 추천안(DATA-01):** 신규 매칭용 특징은 우선 검증된 완료 설문 Q054/055/056/058/060에서 추출하고, 별도 불변 매칭 스냅샷에 출처 회차/revision·surveyVersion·정규화 버전·추출 시점을 추적한다. 공개 프로필은 원래 코드/공개 범위를 유지한다. 나이는 최신 생년월일 파생값을 별도 기준일로 묶는다. 이는 새 API 필드/DB 컬럼 확정이 아니라 필요한 의미 정보 제안이다.

프로필과 설문이 다르거나 프로필만 있는 항목은 조용히 우선순위를 바꾸지 않는다. PM·BE·AI가 출처와 사용자 재확인 흐름을 합의하고, 최신 프로필을 계산용으로 쓰기로 결정하면 변환 불가 세부만 사용자가 추가 응답하도록 한다. 그 결과는 새 특징/설정 스냅샷에 연결하고 기존 완료 원응답·기존 판정은 보존한다. 관련 값이 미확인인 새 매칭의 보류/안내와 재검사·프로필 변경 후 갱신 시점은 미합의다. 프로필 편집만으로 기존 설문을 덮어쓰거나 구버전 결과를 재평가하지 않는다.

**남은 결정:** 설문 실제값 우선 추천안을 채택할지, 프로필 우선 또는 별도 최신 매칭 사실값을 수집할지(PM 재준·성현·영빈/관련 BE), 변환 불가 추가 질문/불일치 안내(FE), 종교 비공개/미입력의 내부 계산·공개 처리, 갱신·기기 반영 경계가 필요하다. 생활정보를 점수에 쓴다는 PM 결정 자체는 재결정 대상이 아니다.

## 7. 구현 역할과 적용 순서

F §2.2·P §7의 과거 역할 기록과 R의 정정을 구분한 표다. **개인 분석 계산 제공은 AI 서버/황성현, 개인 분석 접수·호출·저장·조회는 BE/가을**이다. 개인 분석 입출력 계약은 **S15P31E106-127**, 호출·저장은 **S15P31E106-115**, 결과 조회는 **S15P31E106-116**에 연결한다. 쌍 비교 입력·실행·버전은 **-36 및 BE -74**, IoT 동기화는 **-37**로 구분한다. PM26의 **실제 오프라인 키링 적합도 계산은 확정 MVP 요구**다. 남은 것은 상세 모듈/언어·기존 구현 재사용·어댑터/이식 소유자·서버 검증 관계이며, 키링에서 계산할지 자체를 다시 미정으로 두지 않는다. Jira 배정 변경이나 구현 완료 선언이 아니다.

| 범위 | 담당·역할 | 이번 -36의 경계 |
| --- | --- | --- |
| 개인 분석 -127·-115·-116 | 계산 제공: AI 서버/황성현. 접수·AI 호출·저장·조회: BE/가을. 입출력 계약 -127, 호출·저장 -115, 결과 조회 -116으로 연결(R) | 기존 완료 응답·계산 규칙/결과 연결을 재사용. -36에서 개인 분석을 중복 구현하거나 리뷰 답변만으로 호출·저장·조회 구현 완료를 선언하지 않음 |
| 개인 계산 41·42 / 과거 -64 인계 이력 | 41·42번의 규칙·목업 인계와 F/P의 영빈 설문/개인 결과 역할 기록, 이전 대조의 -64 가을 연결은 과거 이력으로 보존 | 현재 개인 분석 역할은 위 R의 정정으로 읽음. 과거 이력을 삭제하거나 두 사람 비교의 전면 이식 담당으로 확대하지 않음 |
| 쌍 비교 계약 -36 | 성현의 매칭 알고리즘·확정 정책 대조·입력→방향 비교→출력 계약·검증. 영빈·종무와 검토 | 오프라인 기기 계산 목표는 확정. 구체 재사용/어댑터/이식 소유자와 런타임 연결은 EXEC-01. -36 담당만으로 전면 이식을 배정하지 않음 |
| BE 쌍 평가 연동 -74 | F의 영빈 설문/결과·제안 역할은 과거 기록. 쌍 비교 입력·실행·버전은 -36 및 BE -74에서 협업. 개인 분석 -115/-116의 가을 담당과 구분 | 쌍 비교의 BE 호출·재평가 관계와 계산 코드 소유자/실행 위치는 여전히 EXEC-01. 작업 연결만으로 쌍 비교 전체 실행·이식 배정을 확정하거나 POL-01·버전/어댑터 미해결 부분을 활성화하지 않음 |
| 스냅샷·기본 조건 | BE·FE 관련 계약과 -72 등 기존 작업에서 회차·설정·나이 기준일·버전을 연결 | 원응답/나이 경로는 §3.4로 확인. 매칭용 전달 객체·추출/어댑터의 나머지 세부는 INPUT-01/-36·BE -74. 이 파일로 DB/API를 변경하지 않음 |
| IOT -37 및 관련 구현 | 종무의 키링 통신·등록/초기화·복수 기기·동기화·진동·가상/실물 검증 역할은 F에서 확인. 합의할 입력·규칙 버전으로 AI/BE와 연결 | 기기 통신 역할만으로 전체 계산 엔진 이식 소유자로 자동 지정하지 않음. 생년월일·원응답·선호 전체 방송을 요구하지 않음 |
| 설명 -46 | 황성현과 PM·FE·BE가 실제 판정 근거·공개 허용 범위를 연결 | 문장 생성으로 판정·누락값을 보충하지 않음. -36을 전체 AI 기능·전체 QA로 확대하지 않음 |

개인 분석 입출력 계약은 -127, BE 호출·저장은 -115, 결과 조회는 -116으로 연결한다. 쌍 비교는 -36·BE -74·IoT -37에서 협업하며 세부 적용 순서는 §7.1을 따른다. 신규 엔진 전면 작성/전체 Python 이식을 전제하지 않고 오프라인 기기 계산을 충족할 최소 재사용/어댑터/이식 범위를 합의한다. 단계별 구현·검증은 이번 문서 정정으로 완료 처리하지 않는다. 기존 80문항을 100문항으로 추정 변환하거나 기존 결과를 덮어쓰지 않고 새 규칙에 별도 버전/호환 기준을 둔다.

### 7.1 오프라인 목표·AI/BE/IoT 책임과 정밀도·스냅샷 의존성

**PM26 목표는 사전 동기화된 키링끼리 인터넷·휴대폰 없이 판정·기록하는 것**이다. AI/BE 서버를 만남마다 호출하는 구조는 이 목표를 충족하지 않는다. 이후 앱 재연결·양쪽 기록 업로드·서버 검증을 거쳐 제안을 생성하는 흐름과 키링 로컬 만남 판정을 구분한다. 현재 IoT의 검사 결과/선호 `{}`는 상세 패킷 합의나 구현 완료의 근거가 아니다.

| 책임 경계 | 담당·제공할 결과물 | 의존성·검증 |
| --- | --- | --- |
| 판정 의미·참조 계산 | 성현(AI, -36): 방향 비교·fit/영역/Q96·coverage·엄격 제외/기본 게이트 의미·독립 기대값/공통 검증 벡터 제공 | 기존 JS 함수 재사용/어댑터 우선 검토. 서비스 언어·운영 모듈 소유자·이식 범위는 EXEC-01에서 합의. -36만으로 모든 런타임 구현을 배정하지 않음 |
| 입력·보존·서버 수용 | 영빈/관련 BE(-74): 완료 회차·개인 결과·설정 사본의 유효 연결, 지원 버전/실제값 출처 검증·동기화 제공·만남 평가 저장·제안 생성 전 최신 ON/관계/기한/증빙 재검증 | 개인 계산 제공은 AI, 접수/호출/저장 -115·조회 -116 및 입출력 -127은 가을과 구분. 서버 재검증을 키링의 온라인 호출 의무로 바꾸지 않음 |
| 기기 실행·통신·기록 | 종무(IoT, -37/관련 구현): 합의한 최소 데이터 인코딩·로컬 실행·버전/시간·기기별 반영 확인·비휘발성 만남 기록·알림/업로드 | 성현·BE와 공통 벡터로 같은 방향/쌍 결과 검증. 구체 계산 이식 소유자/신뢰 모델은 협의. 양쪽 알림 ON일 때 감지한 기기만 같은 색 LED·2회 진동, 한쪽 OFF면 기록만 유지 |
| 사용자 설정·전환 | 재준(PM·FE)/관련 FE: 신규 Q97/나이 분리·재응답/보류 안내·지원 종료/유예·50/70 최종 제품 승인 | 원응답 자동 변환 금지와 신규 버전/기기 준비 정책을 화면에 연결 |

**최소 의미 정보 추천안:** 로컬 계산에는 E/A/C/I, 실제 Q30~60, 본인 선호 Q61~95, Q96·신규 Q97·Q98 및 known/unknown/any/누락 구분이 필요하다. Q87은 양쪽 실제 Q52 시간대 겹침도 사용한다. 기본 게이트용 성인/현재 나이·한국 기준일/유효성·나이 모드/범위, 반대 성별 조건, 사진 유무/요구, ON/OFF·현재 상대 관계 제한·기기 준비 상태를 별도로 둔다. 16유형명·개인 해석·참고 추천 3개·Q99/Q100만으로 실제 쌍 판정을 대체하지 않는다.

자기 선호는 로컬에 두고 상대의 최소 특징을 받아 자기 방향을 계산한 뒤 인증된 방향 결과를 합치는 방식과, 두 기기가 pair 전체를 재계산하는 방식 중 선택은 EXEC-01에 남긴다. 전자는 상대 선호 전체 전송을 줄일 수 있으나 결과 신뢰/증빙을 합의해야 한다. 공개 광고에는 고정 회원 ID·원응답·선호를 넣지 않고 인증된 연결에서 필요한 정보만 교환한다. 생년월일·사진 원문 방송은 요구하지 않는다.

**정밀도 합의 항목(NUM-01):** 문항 fit → 영역별 관측 평균 → Q96 가중 평균 순서와 빈 영역 제외를 보존한다. 문항별 반올림·35문항 단순 평균·빈 영역 0점 삽입을 하지 않는다. 50/70 비교는 표시 반올림 전에 하며, 잠정 coverage 기준은 informative>0일 때 `10×observed >= 7×informative`로 검산할 수 있다. H≥50 하나로 두 방향 기준을 대체하지 않는다. 분모0·score=null·0/0, 음주 하한1·전구간 무정보, Q98 클램프·시간 irregular를 동일하게 처리해야 한다. 분수/고정소수 방식·스케일·허용 오차·32/64비트 오버플로·endian·codebook/bit mask·unknown/any/missing 예약값·해시 대상 바이트는 미정이다. AI·BE·IoT가 49.999…/50·7/10·6/10 공통 경계 벡터를 먼저 검증한다. 판정이 달라지는 수치 변경은 같은 matching/engineVersion으로 배포하지 않는다.

패킷 바이트 크기·BLE MTU/분할·RAM/비휘발 저장량·최대 대기 건수는 필요한 필드와수치 표현을 고정한 뒤 종무가 산정하고 BE·성현과 검증한다. 현재 `{}` 문서로 용량 적합성·통신 성공을 확정하지 않으며 이 문서에서 임의 크기/스케일을 발행하지 않는다.

**버전·스냅샷 의존성:** survey/matching/productPolicy/engine·codebook/정규화/수치표현·contentHash와 settingsSnapshotId/설정 버전의 지원 조합을 확인해야 한다. 미지원 조합은 임의 강등/선택 삭제 없이 보류하는 안을 검토한다. IoT의 `sync_version`은 규칙 버전과 별개다. 각자의 당시 사본 ID와 event_id·참여 기기·matched_at·policyVersion·matchingRuleVersion·고정 submissionId·peerCredential/evidence를 비휘발성으로 보존한다. 재부팅/설정 변경/재전송 때 당시 값을 현재 값으로 치환하지 않는다.

**원본 기록 삭제 — PM 확정 정책(R1):** 다음 두 경로 중 하나를 충족하면 해당 원본 기록을 삭제할 수 있다.

1. 해당 기록이 서버에 내구적으로 저장됐고, 그 저장 완료 사실이 키링까지 확인된 경우.
2. 서버 접수 여부와 관계없이 키링이 신뢰 가능한 시각으로 해당 기록의 원래 만남 기회 만료를 확인한 경우. 이 경로에는 앱 연결이나 서버 응답이 필요하지 않다.

시각이 불확실하면 만료를 추정하지 않고 삭제를 보류한다. 원본 삭제 이후에도 중복 억제용 최소 상태는 원본과 별도로 유지하며 두 상태를 동일하게 취급하지 않는다. 정확한 시각 동기화·보존 기간·중복 억제 구현 형식은 IoT/BE 후속 계약에 남기며, 여기서 만료 시간이나 새로운 삭제 기간을 임의로 정하지 않는다.

**시간·오프라인 최신성(SYNC-01):** 생일 갱신을 위해 한국 기준일·동기화 시각·RTC 유효성·다음 나이 변경/유효 만료가 필요하다. 윤일·장기 단절·시간 역행·재시작 후 확인·오프라인 유효 기간과 서버 OFF/차단 전파의 보장 범위는 BE·IoT·AI 및 PM이 합의해야 한다. 이를 해결하지 않은 상태에서 ‘오프라인에서도 서버 최신 상태 즉시 보장’이라고 주장하지 않는다. 기기별 최신 반영 후 독립 참여·구버전/미확인 기기만 보류하는 기존 정책은 유지한다.

**후속 적용 순서:** PM26 계약/출처·unknown·임계값 검토 → 새 불변 설문/계산 묶음·호환/재응답·ID 대응 합의 → 원본 보존한 운영 모듈/기본 게이트 구현 → 독립 산술·입력/버전 검증 → BE 스냅샷·가상 키링의 동일 벡터/정밀도·단절/재부팅 검증 → 기기별 반영 확인·신규 매칭 전환 → 서버 제안/별도 공개 근거 연동·실기기 검증이다. 단계별 담당은 위 표 및 §8을 따르며 이번에는 어느 구현 단계도 완료 처리하지 않는다.

## 8. 미정 항목과 연결 사례

**해결된 범위와 새 정책의 분리:** R/O/B의 기존 설문 `SIS-Q-1.1-100` 저장 대응, `SurveyManifest.surveyVersion`, `response_json.answers/metadata`, `public_id(UUID) → sessionId`, SurveyMetadata 세 필드는 정의돼 있다. O26에서도 Evaluation 13개 필드·required/null/`string[]` 및 B26의 encounters 결과/버전 저장 제약을 유지한다. PM26은 생활정보 반영·Q97 네 선택지/최대2·음주 엄격 제외 없음·age 분리·키링 오프라인 목표에 더해 Q97 any/unknown/미응답·구체 허용 범위 미확인 처리와 Q98 명시 선택/신규 fallback 금지를 해결했다. **신규 설문/계산 버전 값·전환 일정·세부 입력/구현 합의는 별도**이며 기존 SIS 코드 결정으로 해결된 것으로 보지 않는다. 개인 분석 역할·제안 생성 전 BE 최신 상위 게이트 재검증은 유지하고 런타임/DB 적용 완료는 주장하지 않는다.

**해결된 SYNC 하위 정책:** F KEY-02/SUR-03/MAT-01~02와 P 91·204~210·344~350·547~551행에 따라 희망 나이 변경·생일 갱신·사진/옵션 변경·유효한 새 재검사 결과 적용 후에도 기존 유효 제안·완료 수락·원래 만료 시각·기존 채팅을 유지한다. 기존 제안을 새 조건/점수로 재심사하거나 종료 제안을 복원하지 않는다. PM 최종 확인에 따라 기존 유효 결과 없는 신규 가입자는 검증·계산·저장 성공 전 매칭 보류, 기존 유효 결과 있는 재검사 회원은 Q97/Q98 재확인·검증·계산·저장 대기/실패 중에도 이전 유효 결과로 매칭 유지다. 신규 응답은 검증·계산·저장이 모두 성공한 후 새 유효 결과로 교체한다. 각 기기는 설정/규칙의 최신 반영 확인 후 독립 참여하며 구버전·미확인 기기만 보류한다. 모든 기기가 미반영이면 새 매칭 대기다. BE는 제안 생성 전 최신 ON·차단·재매칭 제한을 재검증한다. 완료 원응답 덮어쓰기·새 설문 버전 자동 재라벨링을 금지하고 구버전 Q97 age를 당시 버전으로 보존한다. 이 항목들을 SYNC-01의 미정으로 다시 올리지 않는다. 생일 기준일/윤일·오프라인 보장과 동시/지연/역순 처리는 아래에 남긴다.

| ID | 미정 내용·영향 | 결정·검토 담당 | 사례·결정 시점 |
| --- | --- | --- | --- |
| AGE-POL-01 | 신규 기본 나이의 ANY/RANGE·무상한·101세 이상·미설정 표현, 한국 기준일과 기존 metadata 어댑터 | 성현·영빈/관련 BE·종무, 윤일 경험은 재준 | AGE-U01~03·AGE_ANY_AND_UNBOUNDED. 신규 나이 입력/스냅샷 구현 전 합의. age 제거·항상 양방향·성인 조건은 해결 |
| POL-01 | PM26 결정의 F/P·OpenAPI/Jira 과거 문구 정합화, 후속 적용 버전/시점 | 재준·성현·영빈·명세 담당 | POL-CASE-01·STRICT/DRINKING 사례. 신규 정책 배포 전 정합화. 생활정보 일반 점수·네 엄격 flag 의미는 재승인 대상 아님 |
| DATA-01 | §6.2 완료 설문 실제값 우선 추천안, 프로필 불일치/변환 불가 추가 응답·미입력/비공개 처리·갱신 시점 | 재준·성현·영빈/관련 BE, 추가 질문/안내 FE | 생활정보 운영 어댑터·동기화 구현 전. 프로필 자동 덮어쓰기/코드 추정 금지 |
| INPUT-01 | 완료 회차/개인 결과/스냅샷 전달 객체·Q001 숫자 키 어댑터·Q97/Q98 확정 검증의 전달 경계·방향별 사유 문자열·입력 오류 전파 | 개인 분석 가을·성현(-127/-115), 쌍 비교 성현·영빈/관련 BE(-74) | SVC-01~04·Q97_TOO_MANY_FLAGS/Q97_DRINKING_INVALID. 서비스/기기 입력 경계 구현 전. O26의 기존 필드/스키마와 구분 |
| Q97-UNKNOWN-01 | **정책 해결, 구현/전파 잔여:** any는 엄격 제외 없음, unknown/미응답은 재확인 전 신규 적용 불가. 연결 허용 범위 any/unknown/미응답이면 재선택 또는 엄격 선택 해제 | 성현·영빈·화면 FE, 제품 의미 변경 시 재준 | Q97_UNKNOWN·STRICT_WITH_ANY_PREFERENCE·§5.7 추가 기대 기준. 신규 catalogue/완료 검증·적용 경계 구현 전. unknown→any 자동 치환·해결 전 신규 결과 활성화 금지, 이전 유효 결과 유지 |
| Q98-01 | **정책 해결, 구현/검증 잔여:** close/some/wide 명시 선택·unknown/누락 재확인·신규 fallback 금지. 부분 산출의 분모/표시·오류 전파는 별도 계약 | 성현 영향/벡터·BE 입력/오류·FE 재확인, 제품 의미 변경 시 재준 | §3.5·§5.7 추가 기대 기준. 신규 matchingVersion/입력 경계 구현 전. 기존 fallback은 과거 재현 기록으로 보존하며 재확인 전 신규 결과 적용 불가 |
| EXEC-01 | JS 재사용·운영 어댑터/이식 소유자·언어, 기기 방향 계산/결과 합의 또는 pair 재계산 신뢰 모델, BE 평가 연결 | 성현·영빈·종무(-36/-74/-37), 개인 분석 가을·성현(-127) | §7.1. 운영 모듈/패킷 구현 전. 키링 오프라인 실행 목표는 해결됐으며 새 엔진 전면 작성을 선결하지 않음 |
| VERSION-01 | 신규 survey/matching 값·불변 파일 묶음/hash, Manifest/releaseId·IoT 규칙·DB 버전 대응, old/new 지원·전환 유예/재응답 | 성현·영빈/설문 BE·종무, 전환 재준. 개인 계산 저장 가을·성현(-127/-115) | §1.5·MIXED_VERSIONS·LEGACY_Q97_AGE. 새 규칙 파일 발행/배포 전. 기존/신규 자동 혼용·선택 삭제 금지. 합성 fixture의 팀 공유 위치는 §5.7로 해결, 신규 규칙/호환 계약은 별도 |
| NUM-01 | 분수/고정소수·스케일·반올림/허용 오차·overflow·codebook/센티널·직렬화 해시 | 성현·종무·영빈/관련 BE | 50/70 경계·Q98/음주/시간 공통 벡터로 펌웨어 배포 전 검증. 이번에 정밀도 값 임의 확정 안 함 |
| THRESHOLD-01 | 양방향 50점/coverage70%의 최종 제품 기준 승인 | 산술 성현, 기기 동등성 종무·BE, 최종 재준(PM) | 산술 검산 완료. 신규 기본 게이트 결합·기기 정밀도/제품 검토 후 최종 고정, 운영 활성화 전 |
| SYNC-01 | 한국 날짜/생일·윤일·RTC/유효 만료·장기 오프라인 기간·최신 제한 전파, 동시/지연/역순 처리 | BE·성현·종무(-37), 윤일·유효 기간 재준 | AGE-11 및 기기 사용 준비 전. 오프라인 목표·기기별 준비·기존 제안 유지·제안 생성 전 BE 재검증은 해결 |
| PUB-REASON-01 | 비공개 선호를 노출하지 않는 공개 근거/AI 입력 연결 검토 기록 | 재준·FE·BE·성현 | 기존 REASON-SAMPLE-01/PUB-01~02 보존. -46 원문/MR은 이번 변경하지 않으며 공개 근거 연동 전 별도 확인 |

D의 D01~D10 추천안 전체를 승인한 것은 아니다. PM26으로 해결된 나이 분리·생활 점수·네 엄격 flag·Q97/Q98 재확인/적용 정책과 F/P의 유지된 기본 조건을 우선한다. 나머지 상세 중복 입력·실제값 결측 의미·부분 산출/전달 경계·Q99/Q100·이관은 위 담당/시점에 연결한다. 확정 Q97/Q98 정책을 미정으로 되돌리거나 구현 완료로 표시하지 않는다.

### 8.1 BE 리뷰 답변과 남은 세 가지 협업 항목

| 검토 항목 | 받은 답변과 남은 질문·결과물 | 검토 경로 |
| --- | --- | --- |
| 완료 입력의 연결 형태 | **확인:** 원응답 `response_json.answers`·나이 `response_json.metadata`, 내부 `response_id bigint`와 이미 정의된 `public_id UUID → sessionId` 구분. O의 metadata는 `ownAge/desiredAgeMin/desiredAgeMax` 모두 필수·19~100 정수 또는 null. **잔여:** 실제 적용·A/B별 완료 회차/revision·개인 결과·당시 스냅샷의 전달 객체와 검증, 나이 어댑터·SVC-04 오류 전파 | 개인 분석 계약: 가을·성현(-127/-115). 쌍 비교 입력: -36·BE -74, 저장/스냅샷 관련 BE |
| 계산 파일 버전 대응 | **확인:** 설문 값 `SIS-Q-1.1-100`, `SurveyManifest.surveyVersion → survey_responses.version_code` 정의와 별도 전체 `Manifest`, encounters 쌍 평가 저장 컬럼. **잔여:** 개인 계산 버전 저장칸/재현 계약은 -127/-115 후속 협의. 개인/쌍 계산 규칙·설정 버전의 파일 대응과 releaseId 매핑·호환 실패 처리는 별도 합의 | 개인 분석 입출력/저장: 가을·성현(-127/-115). 쌍 비교 버전: -36·BE -74. 기기 규칙 전달: 종무(-37) |
| 전달·검증·실행 경계 | **답변:** 개인 분석 계산 제공 AI 서버/황성현, 접수·호출·저장 BE/가을(-115), 결과 조회 BE/가을(-116); 새 제안 생성 전 BE 최신 ON·차단/재매칭 제한 재검증. **PM26 확정:** 실제 오프라인 키링 계산. **잔여:** 개인 분석 세부 입출력(-127), 상세 런타임 분담·JS 재사용/이식 소유자·BE 평가 연결·IoT 상태 전파 | 개인 분석: 가을·성현(-127/-115/-116). 쌍 비교: -36·BE -74. IoT 동기화: 종무·관련 BE(-37) |

위 질문은 구현 계약을 맞추기 위한 것이며 이미 확정된 성별·필수 양방향 나이·기존 제안 유지를 재승인받는 질문이 아니다. 정책 의미를 바꿔야 하는 안만 재준과 별도 합의 후 MR 대상으로 남긴다. 이번에는 API·코드·DB·팀 메시지·Jira를 변경하지 않는다.

## 9. 검증 이력과 후속 검증

§9.1~9.12는 이전 단계의 원문 이력이다. 과거의 22개/31개 개수, 미추적 상태, ‘정책 미승인’·422 응답 설명·원본 fixture 동일·해당 단계의 ‘이번’은 당시 사실로 보존하며 현재 상태를 의미하지 않는다. §9.8~9.10은 PM26 반영 후보를 한 파일만 수정해 정리한 단계, §9.11은 최초 합성 JSON 추가, §9.12는 R1/R2 정정이다. 이번 Q97/Q98 확정 정책과 fixture 기대 의미 정정·정적 검증은 §9.13에 별도 기록한다.

### 9.1 초안 작성 당시의 검증 이력

아래 표의 ‘이번’은 최초 초안 작성 당시를 뜻한다. 인계 목업 재현 결과는 §9.2~9.3에 별도로 기록한다.

| 구분 | 초안 작성 당시 수행 범위 |
| --- | --- |
| 문서 정적 검사 | 통과: 본문 9절, 중복 없는 사례 ID 22개와 참조, 미정 항목 7개, 표 열·코드블록·공백, UTF-8(BOM 없음)·LF. Q/S/P 고정 커밋의 문서 경로와 H·컨벤션 경로 존재 확인. 변경은 이 파일 1개이며 브랜치·HEAD·stage 상태 유지 |
| 사례 검토 | AGE의 방향·경계·ANY/무상한/미선택 구분, PAIR의 항목 fit→방향 점수→조화평균 기대값을 문서 규칙과 대조. 계산 코드 테스트 실행이 아님 |
| 과거 ZIP 12개 | 이미 계산된 가상 점수를 받는 게이트 사례라는 과거 근거만 인용. 실제 특징→점수 계산 검증, 나이 추가판 전체 통과 또는 이번 실행 결과로 기록하지 않음 |
| Q/S의 과거 테스트 기록 | S 195·205행의 목업 테스트·256쌍 등은 당시 기록. 이번에 재실행하지 않았고 새 코드의 검증 근거로 승계하지 않음 |
| 빌드·린트·코드 테스트 | 코드 변경이 없어 실행하지 않음. 기존 헬스체크나 과거 게이트를 새 매칭 검산으로 대신하지 않음 |
| Git·외부 반영 | 상태·커밋·문서 이력 읽기와 이 파일 작성만 수행. 브랜치 준비/전환·fetch/pull·stage·커밋·push·MR·Jira 변경은 수행하지 않음 |

### 9.2 앞선 인계 재현 단계의 실제 실행 결과

아래는 BE의 과거 검증 보고를 복사한 것이 아니라, 2026-10-21 15:20:21 KST에 별도로 실행한 기록이다. 이번 문서 정정 단계에서는 코드·환경 변경이 없어 네 테스트를 반복하지 않았다.

| 항목 | 실제 실행 기록 |
| --- | --- |
| 인계 추가 커밋 | `954f5fb3ad25c5e8e8b429331d43fd9e053c736c` |
| 실행 스냅샷 | `fe76ce902422cc218998f7ac73c8d4175c069703`. 인계 추가 커밋의 해당 폴더와 동일하며 서비스 최신 구현 전체를 검증한 것이 아님 |
| 환경 | Windows NT 10.0.26200.0 / PowerShell 7.6.5 / Node.js v24.18.0 / Git 2.55.0.windows.2 / Asia/Seoul |
| 실행 위치 | 위 스냅샷에서 별도 폴더로 추출한 `docs/handoffs/survey-20261021/mockup`. 현재 작업 브랜치를 전환해 실행한 것이 아님 |
| 원본 보존 | 의존성 설치 없음. 기존 코드·테스트 변경 없음. 추출한 76개 파일은 Git 원본과 일치하며 실행 전후 해시 변경 없음. 인계 manifest의 런타임 29개도 일치 |
| 이번 정정의 근거 | 앞선 실행 기록의 명령·종료 코드·stdout과 실제 pair 입출력. BE의 `VALIDATION.md`, 과거 구문·화면 검증과는 별도 |

mockup README의 정확한 명령 4개는 다음과 같다. 실행 위치는 위 표의 mockup 폴더다.

```powershell
node experience100.test.mjs
node religion-tattoo.test.mjs
node wording-age.test.mjs
node type-reference.test.mjs
```

| 테스트 | 종료 코드 | 실제 stdout |
| --- | --- | --- |
| `experience100.test.mjs` | 0 | 100-question schema, 16 type fixtures, neutral tie, preference invariance, pair symmetry (256 pairs), exclusions, actual time overlap, missingness and individual readings passed. |
| `religion-tattoo.test.mjs` | 0 | Religion/tattoo categorical fit, soft/hard reciprocal matching, unknowns, neutral type and lossless four-question migration passed. |
| `wording-age.test.mjs` | 0 | Wording preserves all old values, 16 profiles/types, 256 scores and reading branches; isolated optional age filter, inclusive bounds, missing/invalid age and symmetry passed. |
| `type-reference.test.mjs` | 0 | Type references independent of all real-person exclusions and neutral lifestyle inputs; labeled ANY fallback; missing-data behavior; unchanged actual-pair exclusions and six core source hashes; no answer mutation passed. |

이는 기존 실행 기록의 추가이며 §5의 22개 사례를 자동 테스트로 실행·통과시킨 기록이 아니다. BE의 과거 보고, 이번에 참조한 앞선 실행, 문서 정정 단계의 정적 검사를 구분한다.

### 9.3 기존 가상 쌍의 입력·출력 재현 예시

출처는 M의 [type-reference.test.mjs 15~16행](https://lab.ssafy.com/s15-final/S15P31E106/-/blob/fe76ce902422cc218998f7ac73c8d4175c069703/docs/handoffs/survey-20261021/mockup/type-reference.test.mjs#L15)다. 기존 합성 응답을 그대로 호출한 예시이며 서비스의 결측 응답을 생성해 보충한 것이 아니다. §5의 22개 사례와 별도 재현 예시로 두며 새 사례 ID나 제품 기대값으로 추가하지 않는다.

```javascript
import { demoAnswers, pair } from './dist/engine100.mjs';

const a = demoAnswers();
const strict = { ...a, 97: ['age', 'smoking'], 90: ['none'] };
const b = { ...demoAnswers(), 55: 'cigarette' };

pair(strict, b, { age: 25, min: 23, max: 27 }, { age: 31 });
```

두 사람 모두 유효 응답 100개였고 호출 후 입력은 바뀌지 않았다. 아래는 실제 반환의 주요 필드 발췌다. `rows/domains`는 이 요약에서 생략했으며, 각 방향의 rows는 35개·domains는 9개였다.

```json
{
  "ab": {
    "score": 98.95833333333333,
    "coverage": 1,
    "informative": 35,
    "observed": 35,
    "excluded": ["age", "smoking"],
    "unresolved": []
  },
  "ba": {
    "score": 100,
    "coverage": 1,
    "informative": 35,
    "observed": 35,
    "excluded": [],
    "unresolved": []
  },
  "score": 99.47643979057591,
  "eligible": false
}
```

A→B에서는 상대 31세가 A의 희망 23~27세를 벗어나고, 상대 흡연 상태 cigarette가 A의 수용 목록 none 밖이므로 제외된다. B→A의 제외·미해결 배열은 비어 있다. 점수 약 99.48에도 목업 `eligible=false`다. 같은 strict 입력의 별도 `typeRecommendations(strict)`는 참고 유형 STEADYSPARK·MOONROOT·EVERGLOW 3개를 반환했다. 이 참고 유형은 실제 상대의 양방향 자격 통과가 아니며 pair 반환 필드도 아니다.

### 9.4 이번 문서 정정과 후속 검증의 경계

이번에는 지정한 이 문서만 정정하고 미추적 상태를 유지한다. 기존 22개 사례와 과거 검증 이력은 보존하며, 정적 검사는 미추적 파일의 실제 내용을 읽어 표·코드블록·사례 ID·고정 출처·인코딩·공백·변경 범위를 대상으로 한다. stage·커밋·push·MR·Jira 변경은 하지 않으며, 기존 네 테스트·서비스 빌드·DB·GMS·키링 검증을 반복하거나 새로 수행하지 않는다.

2026-10-22 리뷰 보완에서는 §3.2의 판정 구분·false 사유, §3.3의 서비스 요청·응답 샘플 4건(SVC-01~04), §5.5~5.6의 성별 3개·사진 6개 사례(GENDER-01~03·PHOTO-01~06), §6.1의 내부 근거 예시 1건을 추가했다. 기존 22개 사례에 문서 기대값 9개를 더해 §5의 사례는 31개이며 서비스·내부 근거 샘플은 별도다. 기존 미정 항목 7개와 §9.1~9.3의 과거 기록을 보존한다. 수정 전 저장소 밖 백업과 SHA-256 대조를 수행했으며, 이번 확인은 문서 정적 검사·사례와 샘플의 일관성 검토다. 새 사례의 실행 통과·엔진 구현·QA 자동화·정책 승인을 뜻하지 않는다.

향후 기존 코드의 재사용·어댑터·필요한 이식 범위를 구현·검증할 때도 코드가 만든 값을 다시 정답으로 삼지 않고, 위 사례의 입력에서 독립적으로 계산한 기대값과 확정 정책을 기준으로 시험한다. 미정 사례는 결정 기록 후 기대값을 확정한다. 검증일·규칙/코드 버전·명령·실제 결과를 **신규 검증**으로 남기며 과거 12개 게이트나 목업 기록을 실행 결과로 복사하지 않는다. 이번 범위는 -36 계약 문서 정정과 앞선 재현 기록 반영이며 -36 전체 완료·제품 정책 승인·서비스 연동·전체 QA 완료를 뜻하지 않는다.

### 9.5 v0.1.2-draft 변경 이력과 정적 확인 (2026-10-22)

§9.1~9.4는 이전 작성/보완 단계의 기록으로 보존한다. 아래가 최신 F/P 대조 후 이번 정정의 기록이며, 과거 테스트 실행 커밋·명령·결과를 새 실행 결과로 바꾸지 않는다.

| 대상 | v0.1.1-draft의 설명 | v0.1.2-draft의 정정·보완 |
| --- | --- | --- |
| 정책 출처 | P0 `a713899…` / `v0.1.14-draft` | 과거 출처를 보존하고 현재 F/P를 `ea74cc53…` / F `v0.1.81-draft`·P `v0.1.92-draft`로 고정. 문서 사례의 정책 근거 행 갱신 |
| GENDER-01 | 설명용 성별 가/나와 희망 목록 | MALE/FEMALE과 BE의 반대 성별 자동 산출로 명료화. PASS/PASS 의미 유지 |
| GENDER-02 | 자유 선택처럼 읽히는 목록의 PASS/FAIL | 변조/오래된 대상값을 정상 판정 전에 거부하는 사례로 재분류. 방향 점수는 미평가이며 오류/재조회 형식은 미정 |
| GENDER-03 | 본인 성별 미확인인데 수신 희망 목록으로 B→A PASS | 서버 대상 산출 불가·프로필 미완료를 반영해 UNRESOLVED/UNRESOLVED로 정정. 수신값으로 통과시키지 않음 |
| AGE-U01·AGE-POL-01 | 제품 제외는 적었지만 결합 미정 사례로 묶고 항상 검사할지도 협의 대상으로 서술 | Q97 age 미선택이어도 항상 양방향 제품 검사. AGE-U01의 제품 게이트 거부는 확정. Q97·ANY·무상한·101세 이상 연결만 미정 |
| 역할·SYNC-01 | 역할 전체/기존 제안 변경·구버전 처리가 미정처럼 읽힘 | 확인된 AI/BE/IoT 역할과 미정인 실행 위치·구체 이식 소유자를 분리. 기존 제안 유지·기기별 준비/구버전 보류는 해결된 정책으로 분리 |
| SVC-01 연결 | 입력/결과/스냅샷 참조 제안만 존재 | §3.4에 같은 SVC-01의 가상 A/B 연결 한 쌍 추가. 신규 서비스 샘플 번호·완성 원응답·실행 결과를 만들지 않음 |
| v8/생활정보 | 과거 기준과 미정 정책을 기록 | 100문항 검토 선택을 유지하며 최신 F/P의 v8/80문항 참조와 서비스 대응은 별도 표시. 생활정보 목표와 적용 범위·버전·시점 구분 |

**호환성과 적용:** 팀 컨벤션 `[shared-contracts]`의 버전·변경일·영향/호환 기록에 따라 기존 문서 버전에서 패치 번호를 올렸다. 계산 코드·전송/저장 형식을 변경하지 않는 설명 정정과 연결 예제 보완이며, 문서 버전을 새 쌍 규칙 버전으로 간주하지 않는다. 영향 파트는 AI·BE·IoT·관련 FE다. BE가 §8.1의 세 항목을 검토하고 실행/입출력 계약을 맞춘 뒤 해당 구현을 별도 진행한다. 현재 제안 스키마의 API 호환성·서비스 채택 승인은 선언하지 않으며 정책 의미 변경은 합의 후 별도 MR로 남긴다.

이번 정적 확인의 대상은 UTF-8·표/코드블록·참조, §5의 동일한 사례 ID 31개, §3.3 서비스 샘플 4건과 §6.1 내부 근거 1건의 보존, §3.4의 A/B 회차/버전 참조 연결, §9.1~9.3 과거 검증 이력 보존 및 한 파일 변경 범위다. 새 연결 예제는 1쌍이며 새 실행 시험이 아니다. 기존 테스트 4개·빌드/린트·서비스/DB/GMS/실물 검증은 실행하지 않는다. 브랜치·HEAD·미추적 상태를 유지하고 fetch·브랜치 전환·stage·커밋·push·MR·Jira 변경을 하지 않는다.

### 9.6 v0.1.3-draft 리뷰 반영 이력 (2026-10-22)

§9.1~9.5는 이전 단계의 기록으로 그대로 보존한다. 이번에는 사용자가 전달한 리뷰(R)와 상위 계약의 해당 절만 대조했으며, MR/Jira 원문·전체 정책·계산 코드를 재조사하거나 목업 테스트 4개를 반복하지 않았다.

| 리뷰 항목 | 수정 위치·반영 내용 | 남은 범위·완료 경계 |
| --- | --- | --- |
| 계산/상위 서비스 자격 | §3.2에 계산 `eligible`과 매칭 ON·차단·재매칭 제한을 구분. F/P/E에 연결해 키링 로컬 판정과 제안 생성 전 BE 최신 상태 재검증 책임·시점을 명시 | 오프라인 보장·상태 전파·동시/지연 처리 세부는 SYNC-01. 기존 제안 종료 정책·계산 조건 변경 없음 |
| 설문 입력 버전 | §1.3·§2.2·§3.3~3.4·§8~8.1에 `survey_responses.version_code=SIS-Q-1.1-100`을 BE 결정으로 반영 | 통합 명세 수정은 예정. 개인/쌍 계산 규칙·설정 버전 및 파일 대응은 VERSION-01에서 개인 분석 계약 -127과 쌍 비교 -36·BE -74로 구분 |
| 저장 본문·식별자 | §2.1·§3.4·§8~8.1에서 기존 `answers_json` 표기를 `response_json`으로 정정. 원응답은 `response_json.answers`, 나이는 `response_json.metadata`. 내부 `response_id`와 외부 ID 구분 | metadata 내부 필드 미정. `public_id(UUID) → sessionId`는 도입 예정이며 DB/API 원문 수정·구현 완료 확인 아님 |
| 개인 분석 역할 | §1·§7·§8~8.1에 계산 제공 AI 서버/황성현, 접수·호출·저장·조회 BE/가을, 입출력 계약 -127·호출/저장 -115·결과 조회 -116 연결 | 41·42 규칙·목업 인계와 과거 -64/-74 연결 이력 보존. 쌍 비교 입력·실행·버전 -36·BE -74, IoT 동기화 -37로 연결하되 전체 실행 위치·이식 담당 확정 아님 |

**호환성과 적용:** 팀 컨벤션 `[shared-contracts]`에 따라 문서 패치 버전만 올렸다. 리뷰 답변으로 해결된 부분과 제안·도입 예정·미정·구현 완료를 구분한 설명 정정이다. 영향 파트는 AI·BE·IoT·관련 FE이며, 개인 분석 입출력 계약 -127·호출/저장 -115·결과 조회 -116, 쌍 비교 입력/실행/버전 -36·BE -74, IoT 동기화 -37로 연결한다. 이번에는 통합 명세·코드·DB·-46 계약을 수정하거나 실행/API 호환성을 검증하지 않았다.

**정적 확인 범위:** §5의 기존 31개 사례, §3.3의 SVC-01~04 입력/결과, §6.1의 REASON-SAMPLE-01과 §9.1~9.5 과거 기록을 보존하고 필드명·참조·역할·A/B 연결·문서 형식·한 파일 변경 범위를 검사한다. 작업 시작은 -36 브랜치/HEAD `6717a1d702f8fa1508df5a4057dc3aea96955466`의 깨끗한 작업 트리였다. -46은 커밋 `e94ff0f0e75bc97f1a4f8bf9dd42f6b0cb8374d6`과 저장소 밖 백업의 SHA-256 일치를 확인한 보존 상태로 두었다. 이번에는 브랜치 전환·fetch/pull·stage·커밋·push·MR 댓글/Resolve/Ready/머지·Jira 변경을 수행하지 않는다.

### 9.7 v0.1.4-draft 통합 명세 대조·정정 이력 (2026-10-23)

§9.1~9.6은 과거 작업 당시의 기록으로 그대로 보존한다. 그중 도입 예정·metadata 미정·통합 명세 수정 예정이라는 v0.1.3 당시 설명은 현재 상태가 아니며 이번 O/B 직접 대조로 아래와 같이 정정한다.

| BE 추가 리뷰 항목 | 고정 스키마 근거 | 수정 위치·반영 내용 / 잔여 범위 |
| --- | --- | --- |
| 내부 평가 API·출력 | O `paths./internal/v1/matching/evaluations.post`, `EvaluationInput`·`Evaluation`·`Manifest` | §3.2: 기존 `200 Evaluation` 계약과 13개 required 필드·자료형·null 허용을 반영. 기본 필터가 있는 평가 후보 판정과 전체 제안 생성 게이트를 구분. 런타임·내부/외부 ID·releaseId 파일 매핑은 별도 확인 |
| 입력 오류·계산 발췌 | O 해당 경로의 `400 INVALID_REQUEST`·`422 POLICY_UNRESOLVED`와 `Error` | §3.3: SVC-01~03은 완전한 Evaluation이 아닌 계산 발췌. SVC-04는 정상 `eligible=false` 대신 기존 `400`·`Error` 우선 제안. 연결 데이터 검증 위치/오류 전파·정책 보류 응답 분기는 BE 확인 |
| 사유 배열·평가 저장 | O `Evaluation.excluded/unresolved/reasonKeys`; B `encounters`·`ck_encounters_2/3` | §3.2·§6.1: 세 배열 모두 `string[]`. 평가 JSON·버전 컬럼과 null 동반 제약은 정의됨. 방향별 사유 문자열·상세 근거 연결은 별도 협의 |
| 외부 세션·나이 metadata | O `SurveySession.sessionId`·`SurveyMetadata`; B `survey_responses` | §3.4·§8~8.1: `public_id uuid NOT NULL UNIQUE → sessionId`, metadata 세 필드의 required·19~100 정수/null 정의 반영. 실제 DB 적용/런타임은 미확인. ANY/RANGE·무상한 연결은 AGE-POL-01 유지 |
| 설문/개인/쌍 규칙 버전 | O `SurveyManifest.surveyVersion`·전체 `Manifest`; B `survey_responses/analysis_results/encounters` | §1.3·§3.3~3.4·§8~8.1: 설문 버전 저장 대응은 정의됨. 개인 계산 규칙 버전 저장칸·재현 계약은 -127/-115 후속 협의. -36 쌍 비교 결과/버전 저장과 구분 |

**호환성과 적용:** 팀 컨벤션 `[shared-contracts]`의 버전·변경일·영향/호환·적용 순서 기록에 따라 기존 관례대로 문서 패치 버전을 올렸다. v0.1.3의 제안/미정 표현을 이미 있는 통합 명세에 맞추는 정정이며 API·DB·계산 규칙 버전을 새로 만드는 변경이 아니다. 영향 파트는 AI·BE·IoT·관련 FE다. 호출자는 O의 기존 required/null 허용/자료형을 기준으로 대조하고, 남은 어댑터·오류 전달·개인 버전 저장을 각 후속 계약에서 합의한 뒤 별도 구현·검증한다. 기존 제안 객체와 다른 필드/타입을 이 문서만으로 런타임에 적용하거나 호환성 검증 완료로 선언하지 않는다.

**작업·정적 확인 범위:** 시작은 Docker 브랜치 `ai-chore/docker-image-S15P31E106-138` / HEAD `437de3588c1d297ac823c061b60d4f1ca897ab06`, 변경 파일 없음이었다. 기존 `ai-docs/matching-contract-S15P31E106-36` / HEAD `0d936e4`로 전환하고 fetch한 `origin/develop`을 O/B의 SHA로 고정해 읽었다. develop merge/rebase 없이 이 파일만 수정한다. JSON·스키마 필드/required/null 허용/참조·표/코드블록·인코딩/공백, §5의 동일한 31개 사례·SVC 4건·내부 근거 1건과 §9.1~9.6 보존, `git diff --check` 및 한 파일 변경 범위를 정적으로 확인한다. 코드 변경이 없어 과거 테스트 4개·빌드/린트는 반복하지 않는다. stage·커밋·push·MR/Jira·-46·Docker 작업과 통합 OpenAPI/SQL/계산 코드는 변경하지 않는다. -36 전체 완료·정책 승인·BE 구현/DB 적용 완료를 뜻하지 않는다.

**정적 확인 결과:** 위 검사 통과. OpenAPI JSON의 내부 참조 872건과 문서의 고정 커밋 링크 14건을 해석했고, 표 30개·JSON 예시 2개·UTF-8(BOM 없음)/LF를 확인했다. Evaluation 13개 필드와 required/null 허용/타입, metadata·manifest·SQL 제약을 대조했다. §5의 31개 사례 본문과 SVC 입력 표·과거 §9.1~9.6은 원문 동일하며, SVC 4건·내부 근거 1건을 보존했다. `git diff --check` 통과, 변경 파일 1개·stage 없음. 이는 문서 정적 검사이며 API 호출·DB 실행·계산 테스트 결과가 아니다.

### 9.8 v0.1.5-draft 후보 변경 이력·PM C01~C06 대응 (2026-10-26)

이 절의 브랜치/HEAD·한 파일 변경·커밋/푸시 미수행은 **JSON 추가 전 문서 작성 단계의 이력**이다. 이후 두 파일 변경의 허용 범위와 검증은 §9.11을 따른다.

시작 브랜치/HEAD는 `ai-docs/match-explanation-S15P31E106-46` / `74ca51b13dc57321d8cccf04054037798968b712`이며 미커밋/미추적 파일은 없었다. 기존 -36 브랜치로만 전환했고 현재 기준 HEAD는 `c28761da4ea0b19ab44d8990e015a757b3b411fa`다. develop merge/rebase·fetch/pull·stage/commit/push 및 MR/Jira 변경 없이 이 파일만 수정했다. 원격 develop HEAD 읽기 조회와 고정 Git 객체 읽기는 §1.5에 남겼다. 환경은 Windows PowerShell 7.6.5·Git 2.55.0.windows.2·Node.js v24.18.0이다.

PM 검토 원문은 사용자가 이 작업 중 제공했다. 검토 대상은 원격 v0.1.4이며 아래는 **이번 문서 후보의 대응 자체 점검**이다. PM이 이 후보를 재승인했다거나 미전송 요청문을 실제 전달했다는 기록이 아니다.

| PM 검토 ID | 이번 후보에서 해결/작성한 항목 | 미해결·담당·필요한 검증 |
| --- | --- | --- |
| C01 정책 반영 | §1.4·§3.1·§6·§8에 승인 생활 점수, Q97 네 항목/최대2, 음주 엄격 제외 없음·기본 나이 분리 반영. 기존 비반영 목표를 과거 이력으로 정정 | **문서 정책 반영 완료**, 같은 정책 재승인 불필요. 성현/명세 담당이 후속 F/P·API/Jira 정합화, 신규 구현이 승인 정책과 같은지 검증. 이번 후보 최종 검토/병합은 별도 |
| C02 문항/나이 버전 | §1.5에서 SIS-Q-1.1-100의 age 존재·유입 SHA·해시, 새 파일 발행 필요, 원본 보존/새 회차/자동 삭제 금지·호환표를 작성. §4에서 양방향 나이 확정과 ANY/무상한/101세 이상 표현을 분리 | **부분 작성**. 성현·설문 BE가 최종 신규 문항의 브랜치/커밋·survey/wording 값과 나이 어댑터를 별도 발행/검증해야 함. 현재 새 파일/버전 미발행. 경계·생일/윤일/누락 검증 필요 |
| C03 계산 재현 버전 | §1.5의 기존 코드/규칙 SHA-256·미정 버전 자리표시자·설문/개인 결과/기기 사본 호환표·§7.1의 version/snapshot 의존성을 작성. engine100 독립 matchingVersion export 부재 명시 | **부분 작성**. 성현·BE·종무가 실제 신규 matchingVersion·전체 불변 묶음/해시·releaseId/IoT/DB 대응·지원 조합을 확정하고 과거 재현/혼용 보류 검증. 기존 hash를 신규 배포 hash로 간주하지 않음 |
| C04 실제 입력/결측 | §2/§6.2 실제값 경로·변환 불가/갱신 추천, §3.5 Q98 fallback 실제 동작/미승인 추천/점수·분모 영향, §3.2 오류 구분, 완전한 합성 입력 27건·기존 SVC/REASON을 연결 | **부분 작성**. 성현·BE가 출처 우선/정규화·전달 객체·방향 사유 문자열/오류 전파를 합의. 재준이 Q97 unknown·Q98 상세 규칙 추가 결정. Q98 변형/입력 거부·정보 부족/503·실제 API 응답 검증 필요. 새 패킷/서버 snapshot 입력이 완성됐다는 주장은 아님 |
| C05 오프라인 기기 계약 | §7/§7.1에서 **실제 오프라인 키링 적합도 계산을 확정 MVP 요구**로 이동. 최소 특징/선호/가중치/기본 조건/결측·버전·당시 기록과 AI/BE/IoT 책임을 분리 | **부분 작성**. 종무·BE·성현이 패킷 크기/MTU·정수화/오차·호환 오류·신뢰 모델·시간 유효성·메모리/비휘발 용량을 합의/구현. 공통 벡터의 서버/기기 일치·인터넷/휴대폰 단절·재부팅·실물 검증 미수행 |
| C06 최종 검산 증거 | §5.7의 전체 합성 입력27·대표 중간 점수/coverage·독립 산술 기대값·기존 관찰26/미실행1·기존 테스트4개 명령/버전을 연결. 31개/SVC4/REASON1·과거 이력 보존 | **부분 작성**. 성현은 신규 엔진 구현 후 독립 기대값과 실제 결과를 대조하고 BE/IoT는 서버/기기 공통 벡터를 검증. 신규 27건 NOT_IMPLEMENTED·신규 PASS0. 50/70 산술 검산 완료와 최종 제품 승인 대기를 구분 |

이전 문서 단계 정적 검사는 §9.10에 남긴다. 기존 T26 실행은 `f5224c9`의 변경 없는 별도 사본에서 `node experience100.test.mjs`·`node religion-tattoo.test.mjs`·`node wording-age.test.mjs`·`node type-reference.test.mjs`가 모두 exit0인 과거 기록이며, 파일명/해시 출처는 §5.7에 보존한 로컬 참고 기록이다. `baseline-probes.mjs` 관찰은 기존 엔진에 한정된다. 이번 JSON 공유 후속에서도 엔진·서비스·키링 테스트를 재실행하지 않았다.

### 9.9 Jira -36 완료 조건별 문서 충족 상태와 검증 경계

2026-10-26 [Jira -36](https://ssafy.atlassian.net/browse/S15P31E106-36)의 본문/댓글을 읽기 전용으로 확인했다. 본문 갱신 시각은 2026-10-23 12:39:42 +09:00이며 상태는 진행 중이다. 최신 PM26 전의 v8/60점·생활 비반영 문구가 남아 있으므로 승인 정책을 거꾸로 되돌리는 근거로 쓰지 않는다. 아래는 본문 완료 조건 6개를 순서대로 대조한 것으로 Jira 체크박스/상태를 변경한 것이 아니다.

| 완료 조건 | 이번 후보의 문서 충족 | 남은 조건 |
| --- | --- | --- |
| 1. 성향/생활·양방향 입력과 점수/산출불가/규칙 버전 출력 | §1.3·§2~3에서 v8/12개와 100문항 구분, Evaluation 13개 필드/required/null·정보 부족·버전/스냅샷 연결 보존 | 실제 전달 어댑터/신규 규칙 값은 INPUT/VERSION-01 후속. v8 순번을 100문항에 자동 대응하지 않음 |
| 2. 성별·한국 만 나이·양방향 나이 별도 조건 | §4·기존 AGE/GENDER/PHOTO 및 §5.7에서 문서 반영 | 나이 표현/기준일·실제 기본 게이트·기기 시계 연동 검증은 후속 |
| 3. 임시 v8 가중치/60점·생활 정책 경계 | v8/60점은 과거 임시 참조, 신규 50/70은 검산 후보/제품 승인 대기로 구분. PM26의 생활 점수·선택형 엄격 제외를 반영 | 과거 Jira의 ‘생활 제거/하드필터 미도입’ 문구는 PM26으로 대체된 범위. 동일 정책 재승인 불필요하며 실제 신규 버전 반영은 후속 |
| 4. 정상·정보 부족·unknown/any·방향 차이·기본 불일치 | 기존31 + 신규27 및 SVC4 보존/연결, 대표 입력/중간값/기대값 작성 | 신규 엔진·Q98 추가 벡터·서버/기기 실제 결과 검증 미완료 |
| 5. 실행 위치/오프라인·알림·당시 근거/버전·담당/시점 | §7.1·§8에 확정 오프라인 요구와 상세 후속 책임/결정 시점 기록 | 패킷/수치/호환/최신성·BE 수용/실물 구현 미완료 |
| 6. -46의 실제 근거 전달 예시와 검토 결과 | 기존 REASON-SAMPLE-01·SVC 연결/비공개 경계 보존. PM C01~C06 검토 대상과 이번 대응을 구분해 기록 | 신규 판정 근거의 공개 허용/후속 -46 연동 검토는 별도. 이번 -46 파일/MR 수정 없음 |

**-36 전체 완료 아님:** 본문이 요구하는 ‘계약·사례 검토 및 승인 MR의 develop 병합’은 수행하지 않았고, 이번 후보 PM/BE/IoT 재검토·신규 버전 발행·구현/통합 검증도 남아 있다. 문서 정적 검사만으로 이를 완료 처리하지 않는다.

### 9.10 이번 후보의 로컬 정적 검사 결과

아래 25/25 결과와 ‘한 파일·stage 없음’은 **JSON 추가 전 실행 이력**으로 유지한다. 현재 변경 범위/검증을 뜻하지 않으며 후속 JSON 추가 검증은 §9.11에서 구분한다.

2026-10-26 Node.js의 일회성 읽기 전용 검사에서 **25/25 항목 통과**했다. 대상 문서와 HEAD 원문을 대조해 기존 31개 사례·SVC 4건과 연결 절·REASON 1건·§9.1~9.7 전체 원문 보존을 확인했다. 신규 표 27개 ID/순서가 JSON과 같고 양쪽 100문항 입력, 신규 기대값 27건 NOT_IMPLEMENTED, 기존 관찰26/미실행1 구분이 일치한다. 대표 산술은 엔진 출력 재사용 없이 공식으로 검산했다.

고정 커밋 파일 링크 22건·O26 내부 $ref 583건을 해석했고, Markdown 표 43개 열 수·코드블록 8쌍·기존 JSON 예시2개 parse·UTF-8(BOM 없음)/LF를 확인했다. 검증 자료4개와 기존 catalogue/data100/engine100/age100의 SHA-256 4개가 본문 지문과 같다. EvaluationInput·Evaluation·Manifest·SurveyManifest·SurveyMetadata의 O→O26 동일성, Evaluation 13개 필수/추가필드 금지, 현재 평가 응답 목록/REVIEW_REQUIRED, 기존 Q097 age/최대2를 대조했다.

`git diff --check` 통과, 변경 경로는 이 파일1개이며 stage/미추적 파일 없음·브랜치/HEAD 유지다. 직접 재확인할 명령은 다음과 같다.

```powershell
git diff --check
git diff --name-only
git status --short
```

문서만 변경했으므로 Gradle/FE 빌드·린트·기존 엔진 테스트를 반복하지 않았다. 신규 엔진·BE API/DB·가상/실물 키링의 통합/동등성은 미구현 또는 계약 미합의로 이번에 실행하지 못했다. 후속 구현 후 §5.7 전체 입력·독립 기대값과 Q98 추가 벡터를 해당 런타임에서 대조하고 실행 명령·버전·실제 결과를 새 검증 기록으로 남겨야 한다. 현재 정적 검사 통과를 신규 정책 테스트 통과로 표시하지 않는다.

### 9.11 합성 JSON fixture 저장소 공유·두 파일 변경 검증 (2026-10-26)

한 파일 문서 작업 이후 사용자가 합성 JSON 추가와 기존 -36 브랜치의 커밋·일반 push를 승인했다. 시작 상태는 `ai-docs/matching-contract-S15P31E106-36` / `c28761da4ea0b19ab44d8990e015a757b3b411fa`이며, 예상한 이 문서의 미스테이징 변경만 있었고 다른 변경/스테이징 파일은 없었다. 원격 동일 브랜치 HEAD도 같은 값임을 읽기 조회했다. develop merge/rebase·강제 push·기존 설문/엔진/제품 코드·-46 수정·MR 승인/병합·Jira 변경은 범위에 포함하지 않는다.

추가 파일은 [fixtures/mvp-policy-20261026-cases.json][T26-cases]이며 원본 `synthetic-test-drafts.json`에서 **154,991바이트 그대로 복사**했다. 원본/복사본 SHA-256은 모두 `a1f26e73b01003676e595822869983f8b669c19ebf7992f1d2ebbd04f64c26b9`다. 원본 변경/삭제·JSON 값 수정/정규화/재생성은 하지 않았다. §5.7의 상대 링크를 실제 파일로 연결했고, 기존 세 로컬 참고자료의 파일명·해시·출처는 유지하면서 클릭 가능한 Windows 공유 링크를 제거했다.

실제 JSON 루트는 `format/newSurveyVersion/newMatchingVersion/cases`, 사례는 `id/input/newPolicyDraft/legacyProbeEnabled` 구조다. 입력 양쪽에 `surveyVersion/answers/ageProfile`이 있고 1~100 문항 키가 모두 존재한다. AGE_ANY_AND_UNBOUNDED만 `input.basicAgeReview`를 추가로 사용한다. 이를 확정 API/키링 패킷·신규 완료 응답 스키마로 선언하지 않는다. 신규 설문/매칭 식별값은 기존 자리표시자를 유지한다.

**사전 읽기 전용 정적 검사 26/26 통과:** 원본/복사본 바이트·SHA, 실제 구조·27개 고유 ID와 문서의 순서, 양쪽 100문항, 신규 기대값 27건 NOT_IMPLEMENTED, legacyProbeEnabled=true 26건/false 1건, 로컬 기존 관찰 기록의 대응, age 기존 관찰/신규 거부 경계, 미확정 버전, 상대 링크/해시/로컬 참고자료 표시, 기존31/SVC4/REASON1·과거 이력·신규27 표/대표 산술 원문, PM/C01~C06/Q98/오프라인 규칙, 민감정보 의심 패턴, Evaluation13 설명·고정 링크22·표43/코드블록9쌍·UTF-8/LF 및 두 파일 변경 범위를 확인했다. 개인정보·인증정보·API 키·JWT 의심 패턴은 발견되지 않았다.

`legacyProbeEnabled`는 기존 엔진 관찰 대상 여부를 보존한 값이며 이번에 실행했다는 뜻이 아니다. 실제 관찰26/미실행1 기록은 §5.7의 로컬 참고 출처와 이전 본문에 남아 있다. Q97 age·3개 flag·drinking 등 기존/잘못된 입력으로 거부·보류를 확인하는 사례도 원본 그대로 유지하며 신규 유효 입력으로 인정하지 않는다. **신규 정책 엔진 실행·PASS는 0건**이고 서버·키링 동등 계산/실물 검증은 미완료다.

최종 커밋 범위는 이 문서와 위 JSON 두 파일로 제한한다. 새 JSON은 스테이징 전 `git diff --stat`에 나타나지 않을 수 있으므로 상태 목록과 스테이징 후 통계를 함께 확인한다. 커밋 직전에는 두 파일 목록·원본/스테이징 JSON 바이트 일치·스테이징 문서 일치 및 공백 오류를 다시 검사한다.

```powershell
git diff --check
git status --short
git diff --stat
git diff --cached --name-only
git diff --cached --stat
git diff --cached --check
```

저장소 컨벤션 [commit-format]에 따라 요약은 한국어로 작성한다. 합성 fixture 공유는 새 규칙 버전 발행·실제 매칭 엔진 구현/테스트 통과를 뜻하지 않으며, 이번 검증 때문에 기존 테스트 성공 이력이나 신규 기대값 상태를 바꾸지 않는다.

### 9.12 PM 리뷰 R1/R2 최소 정정 (2026-10-26)

PM의 MR !32 v0.1.5-draft 리뷰에 따라 §7.1에 확정된 원본 삭제 두 경로·시각 불확실 시 보류·별도 중복 억제 상태 보존을 반영하고, §6.2의 SMOKER를 전자담배만 사용하는 경우도 포함하는 포괄값으로 정정했다. DATA-01 출처/갱신 미합의·완료 원응답 덮어쓰기 금지, 기존31/SVC4/REASON1·신규27 fixture/기대값 및 Q97/Q98·50/70 승인 상태는 유지한다. 이번 변경은 이 문서의 R1/R2와 짧은 이력뿐이며 엔진·서버·IoT 실행 검증 완료를 뜻하지 않는다.

### 9.13 v0.1.6-draft 후보 — Q97·Q98 최종 확정 정책 반영 (2026-10-26)

사용자가 PM의 R1/R2 확인 및 Q97/Q98 최종 결정을 전달했다. 시작 작업 트리는 `codex/ai-personal-analysis-core` / `f5224c9aad434e4e1b54df8f3f17d6f84eb48897`이며 개인 분석 미커밋 변경이 있었다. 해당 작업 트리의 상태·AI 파일 해시를 기록하고 stash/reset/clean 없이 별도 worktree의 기존 `ai-docs/matching-contract-S15P31E106-36`에서만 작업한다. 원격 -36 브랜치와 MR !32 head는 모두 기준 `949eb00bd8e69817a7998d0c1ed2009477ba0428`임을 Git 참조로 확인했다.

팀 컨벤션 `[shared-contracts]`에 따라 문서 패치 버전·변경일·영향/호환·적용 순서를 갱신했다. §1.4~1.5·§2·§3.5·§5.7·§8에 Q97 any/unknown/미응답·엄격 선택의 구체 허용 범위 재확인, Q98 명시 선택/신규 fallback 금지, 기존 유효 결과 유지와 신규 검증·계산·저장 성공 후 교체를 반영했다. §3.1의 기존 Q98 동작 문단도 과거 재현/신규 금지 참조로 최소 정정했다. 기존 완료 원응답·구버전 Q97 age·기존 제안/채팅을 보존하고 신규 설문 버전과 matchingVersion은 미발행으로 유지한다. 영향 파트는 AI·BE·FE·IoT이며 새 버전 발행/지원 조합·검증 구현 → 검증·계산·저장 성공 → 결과 교체 → 기기별 설정/규칙 적용 확인·제안 생성 전 BE 최신 제한 재검증의 순서를 따른다.

fixture는 Q97_UNKNOWN의 expectedEligible을 null→false로 정정하고 reviewRequired를 재확인 전 신규 적용 불가로 명시했다. STRICT_WITH_ANY_PREFERENCE는 reason만 구체 허용 범위 재선택 또는 엄격 선택 해제·해결 전 신규 결과 활성화 금지로 정정했다. 두 사례 모두 이전 유효 결과 유지 의미를 명시한다. 다른 기대값·전체 계산 입력·ID/순서·기존 관찰·legacyProbeEnabled·27건 NOT_IMPLEMENTED·실행 이력은 보존한다. v0.1.6 실행 당시 파일은 155,141바이트, SHA-256 `04a294fc497d38745fd927ff01ba5dce89ef4f28063de01a73692354acdfa38b`이며 최초 원본 해시는 §5.7·§9.11에 이력으로 보존한다.

기존 §5.1~5.6의 31개 사례·§3.3~3.4의 SVC 4건·§6.1의 REASON 1건·§6.2/§7.1의 R1/R2·§9.1~9.12 이력을 기준 커밋과 대조한다. Markdown/JSON 구조·표/코드블록·UTF-8/LF·링크·fixture ID/해시·변경 경로와 `git diff --check`, 기존 개인 분석 작업 트리 상태/해시 보존을 정적으로 확인한다. 검증 통과 후 위 두 파일만 -36 브랜치에 커밋·일반 push하며 develop merge/rebase·강제 push·MR 승인/병합·Jira 변경은 하지 않는다.

**정적 확인 결과:** Node.js v24.18.0의 읽기 전용 검사 15/15 통과. 기존31/SVC4/REASON1·R1/R2·§9.1~9.12 원문과 27건의 전체 입력/ID/순서·기존 관찰을 보존했다. fixture의 의미 변경은 두 사례의 세 기대 필드뿐이며 기존 JSON 서식/파일 종결도 보존했다. 표 44개·코드블록 9쌍·JSON 예시 2개·고정 Git blob 링크 22건·상대 참조·현재 SHA/크기·UTF-8(BOM 없음)/LF 및 `git diff --check`가 통과했다. 변경 파일은 계약 문서/fixture 두 개이며, 원래 개인 분석 브랜치/HEAD·미커밋 상태와 AI 파일 28개의 해시가 시작 기록과 같다. 이 검사는 문서 정적 검사이며 신규 엔진 실행 결과가 아니다.

문서 계약 갱신으로 계산 엔진·BE·FE·IoT·개인 분석 -142 코드는 수정하지 않는다. 따라서 코드 빌드/린트·신규 27건 실제 실행·키링 실물/서버 동등성 검증은 수행하지 않으며 신규 PASS는 0건이다. 50점/coverage70% 최종 제품 승인, 신규 버전 발행·입력/오류 전달·정밀도/호환/생일/오프라인 검증은 후속 항목으로 유지한다.

### 9.14 v0.1.7-draft 후보 — MR !32 추가 리뷰 R1/R2 반영 (2026-10-26)

시작 -36 브랜치 `ai-docs/matching-contract-S15P31E106-36`의 로컬/원격 HEAD는 모두 `eaa44845b8741f4c81a4f8540882082e29f57318`이며 전용 worktree는 깨끗했다. 원래 `codex/ai-personal-analysis-core` / `f5224c9aad434e4e1b54df8f3f17d6f84eb48897`의 미커밋 상태·tracked diff/staged diff·변경/미추적 파일 21개 바이트 해시를 먼저 기록하고 기존 -36 worktree를 재사용했다. stash/reset/clean·develop merge/rebase를 하지 않는다.

**R1:** §3.1의 시간 정보·coverage 설명을 원본 direction/wishFit 분기 순서로 정정하고 §5.8 Q87-PRIORITY-01과 대조 6건을 추가했다. Node.js v24.18.0에서 고정 원본·catalogue 자료형·NORMAL 전체 응답을 확인한 뒤 독립 기대값과 실제 출력이 7/7 일치했다. 실제 불일치 0은 observed/informative 양쪽에 포함하며, 정보 부족 null은 informative에만 포함한다. 기존 27개 정책 fixture는 재작성/추가하지 않았다.

**R2:** JSON의 기존 EOF는 `}`(0x7D)로 LF가 없었다. JSON을 재직렬화하지 않고 정확히 LF(0x0A) 한 바이트만 덧붙였다. 수정 전 **155,141바이트·SHA-256 `04a294fc497d38745fd927ff01ba5dce89ef4f28063de01a73692354acdfa38b`**, 수정 후 현재 저장소 fixture는 **155,142바이트·SHA-256 `9bedd16058295211224008b6553099300c72358fa85f478f980879e174b540a8`**다. 수정 전후 JSON 파싱 성공·전체 구조/값 deepEqual·27개 ID/순서/전체 입력/기대값 동일·기존 바이트 prefix 동일·EOF LF 정확히 한 개를 확인했다. 현재 참조는 §5.7에 갱신하고 §9.13은 v0.1.6 실행 당시 해시/크기로 명시했다. 최초 생성 파일과 과거 PC 실행 로그의 출처 SHA는 소급 변경하지 않았다.

팀 컨벤션 `[shared-contracts]`에 따라 문서 패치 버전·변경 이력을 갱신했다. 영향 파트는 AI·BE·FE·IoT이며 기존 계산 규칙의 설명과 EOF 형식만 정정하므로 계산/입력/기기 버전·API·배포 순서를 새로 발행하지 않는다. Q97/Q98 확정 정책·§6.2/§7.1의 이전 R1/R2·원본 삭제/결과 유지 규칙·50점/coverage70% 최종 승인 대기 상태를 보존한다.

**검증 결과:** 고정 원본 6개 바이트·해시, Q87 7개 대조 실행과 독립 기대값, JSON 전후 동일성/27건 NOT_IMPLEMENTED·현재 문서 SHA/크기, 기존31/SVC4/REASON1 및 이전 정책/이력 보존, Markdown 표/코드블록·JSON 예시·상대/고정 Git blob 링크·UTF-8(BOM 없음)/LF·`git diff --check`, 변경 경로 두 개와 원래 -142 상태/파일 해시 보존을 확인했다. 신규 정책 엔진/27개 정책 테스트 재작성·신규 PASS는 없으며 코드 빌드/린트·서버/키링 실물 동등성 검증은 범위 밖이다. 검증 후 두 파일만 기존 -36 브랜치에 커밋·일반 push하며 강제 push·MR 승인/병합·Jira 완료 변경은 하지 않는다.
