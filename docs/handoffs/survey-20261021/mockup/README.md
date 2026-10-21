# 공개 v15 설문 목업 소스

현재 프로젝트 서비스와 연결되지 않은 브라우저 로컬 체험판이다. 공개 사이트의 추적 파일을 그대로 복사했다. 로그인·서버 DB·실제 참가자 추천·키링 연동은 없다.

- 공개 사이트: https://suddenly-see-heart-e106.rusidpia.chatgpt.site
- 원본 Sites 커밋: dbb76fa3792821272a647fc2d95f281817d198da
- 설문 SIS-Q-1.1-100 / 표현 SIS-WORDING-20261020-1 / 유형 SIS-TYPE-INITIAL-1.0 / 참고 유형 SIS-TYPE-REF-1
- dist/index.html은 현재100문항, dist/legacy.html은 과거 검사 보존용이다. 과거 모듈은 최신100문항 계산에 혼용하지 않는다.

## 실행

Node.js 설치 후 이 mockup 폴더에서 실행한다. 검증 환경은 Node.js24다. 별도 패키지 설치·빌드가 필요 없다.

~~~text
node preview.mjs
~~~

브라우저에서 http://127.0.0.1:4178 을 연다. 종료는 Ctrl+C. 실제 설문 응답은 브라우저에 저장되며 공유 파일에는 원응답이 포함된다. 테스트에는 가상 응답을 사용한다.

## 계산 검증

같은 폴더에서 아래 네 명령을 실행한다.

~~~text
node experience100.test.mjs
node religion-tattoo.test.mjs
node wording-age.test.mjs
node type-reference.test.mjs
~~~

wording-age와 type-reference 테스트는 휴대 가능한 fixtures 경로로만 수정했다. 런타임 dist 파일은 변경하지 않았다. wording-baseline의16개 응답은 demoAnswers로 생성한 합성 대표값과 동일함을 검증했다. v14-source-manifest는 여섯 핵심 파일의 변경 없음을 비교하는 과거 해시 자료이며 현재 게시 버전은 v15다. 개인 설문 파일·자격증명·.git·.openai·node_modules는 포함하지 않았다.

## 읽을 코드

| 파일 | 역할 |
| --- | --- |
| [app100.mjs](dist/app100.mjs) | 검사·결과 화면, 로컬 저장, 가져오기·내보내기 |
| [data100.mjs](dist/data100.mjs), [wording100.mjs](dist/wording100.mjs) | 100문항·선택지·검증·표시 문구 |
| [engine100.mjs](dist/engine100.mjs), [initial-profiles.mjs](dist/initial-profiles.mjs) | 점수·유형·해석·실제 두 응답 비교 |
| [type-recommendations100.mjs](dist/type-recommendations100.mjs) | 실제 사람 조건과 분리된 참고 유형 |
| [record100.mjs](dist/record100.mjs), [age100.mjs](dist/age100.mjs) | 응답 이관·나이 조건 |

목업의 계산을 서비스 정책으로 자동 채택하지 않는다. [미결정 정책](../POLICY-DECISIONS.md)을 먼저 확인한다.
