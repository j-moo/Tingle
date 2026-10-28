# 인프라 계획 — Suddenly I See

v0.4.0, 2026.10.22. 서버 구성 · CI 검사 · develop/master 자동 배포 · back 컨테이너 연결을 실제 구성에 맞춰 고쳤다. 비밀값을 Jenkins Credentials → `app.env` 로 옮겼다.

(v0.3.0, 2026.10.21. 모바일 앱을 Kotlin Multiplatform 으로 정하고, React 랜딩 웹 컨테이너와 FastAPI AI 서버 컨테이너를 추가했다.)

## 1. 목표와 원칙

- 운영(`master`)은 **블루-그린 무중단 배포**. 사용자가 채팅·동기화 중이어도 배포로 끊기지 않는다. WebSocket 은 재접속으로 복구한다.
- 개발(`develop`)은 같은 서버의 `/dev` 경로에 **재시작 배포**. 앱의 개발 빌드는 `/dev/api` 를 바라본다.
- 앱 서비스(web · back · ai) 배포는 사람이 서버에 접속해서 하지 않는다. **GitLab 머지 → Jenkins → 서버** 로만 간다. nginx · db · jenkins 같은 인프라 변경은 인프라 담당이 수동 배포한다 (7절).
- AI 는 **AI 서버(FastAPI)가 GMS API(GPT-6 Luna)를 호출**해 처리한다. 백엔드는 AI 서버만 부르고 GMS 를 직접 부르지 않는다. 오프라인 매칭 판정은 키링 안에서 끝나므로 서버·GMS 를 부르지 않는다.
- 외부에 여는 포트는 22, 80, 443 뿐. DB·AI 서버·Jenkins·Grafana 는 Nginx 뒤 또는 내부망에만 둔다.
- 상호 수락 전 **프로필·사진은 서버 어디서도 노출하지 않는다**. 사진 파일도 Nginx 가 직접 서빙하지 않고 백엔드가 권한을 확인한 뒤 준다.
- 단계마다 **측정값**을 남긴다 (배포 다운타임, 응답 시간, WebSocket 재접속 시간, 복구 시간).

## 2. 서버

| 서버 | 사양 | 역할 | 상태 |
| --- | --- | --- | --- |
| `k15e106.p.ssafy.io` (3.36.48.187) | Ubuntu 24.04, 4 vCPU / 15GiB / 디스크 309GB | 운영 + 개발(`/dev`) + Jenkins + DB + 모니터링 전부 | 사용 중 (10/22 구성) |

- 서버는 **1대 구성**이다.
- 메모리 예산(15GiB): nginx 0.1 / web 0.05 ×2 / back 운영 1.5(전환 중 3) / back-dev 1.5 / ai 운영 0.5(전환 중 1) / ai-dev 0.5 / PostgreSQL 1 / Jenkins 1.5 / 모니터링 1 / 빌드 중 +3~4(Android Gradle) → 평소 8GB, 최대 13GB. 컨테이너마다 `mem_limit`, Jenkins 동시 빌드 1개.
- CI 의 app 검사는 Gradle 힙을 3GB 로 제한하고 Kotlin 컴파일을 Gradle 안에서 돌린다 (데몬을 따로 띄우면 7GB 가까이 쓴다).
- iOS 빌드는 Linux 서버에서 못 한다. **Mac 1대**(팀 보유 또는 교육장)에서 Xcode 로 수동 빌드한다. 서버 역할이 아니므로 아래 CI 범위에서 제외.

## 3. 네트워크·도메인·인증서

| 항목 | 계획 |
| --- | --- |
| 도메인 | SSAFY 제공 `*.p.ssafy.io` 1개 (예: `k15e106.p.ssafy.io`). 운영 `/`·`/api`, 개발 `/dev`·`/dev/api` 로 경로 분리 |
| HTTPS | Let's Encrypt(certbot). 서버 수령 후 첫 작업. Android 는 평문 HTTP 를 기본 차단하고 iOS 는 ATS 로 HTTPS 를 요구하므로 인증서 없이는 앱 연동 불가 |
| 진입점 | Nginx 하나. `/` → web(운영), `/api/` → back(운영), `/ws/` → back WebSocket(운영), `/dev/` → web-dev, `/dev/api/`, `/dev/ws/` → back-dev, `/app/` → APK 다운로드 정적 파일, `/jenkins/`, `/grafana/` |
| AI 서버 | Nginx 로 열지 않는다. back 이 Docker 내부망 주소(`http://ai:8000`, 개발은 `http://ai-dev:8000`)로만 호출 |
| WebSocket | Nginx 에 `Upgrade`/`Connection` 헤더 전달, `proxy_read_timeout 3600`. 블루-그린 전환 시 기존 연결은 old 컨테이너에 남아 있다가 종료되므로 앱이 재접속 |
| 방화벽 | ufw 로 22, 80, 443 만 허용 (`infra/infra.md`). Docker 가 여는 포트는 nginx 80 · 443 뿐 |
| 내부망 | Docker 네트워크. DB 5432, AI 8000, Jenkins 8080 은 외부 비공개 |

앱은 경로 기준 URL 을 쓰므로 쿠키 분리 문제는 없다. 운영·개발 앱 빌드는 `./gradlew assembleRelease -PapiBase=https://도메인/api` 처럼 Gradle 속성으로 서버를 고르고, 앱은 빌드 설정 값으로 읽는다.

## 4. 컨테이너 구성

모두 `infra/docker-compose.yml` 하나로 띄운다. `Dockerfile` 은 각 파트 폴더(`back/`, `ai-server/`, `front/web/`)에 둔다. 앱(`front/app/`)과 펌웨어(`iot/`)는 컨테이너가 아니라 **빌드 산출물**이다.

| 서비스 | 기술 | 운영 | 개발 |
| --- | --- | --- | --- |
| nginx | Nginx + certbot 볼륨, `/app/` 정적 디렉터리 | 공용 | 공용 |
| web | React 랜딩·서비스 설명 페이지. Node 로 빌드하고 `nginx:alpine` 으로 정적 파일 서빙 | `web` | `web-dev` |
| back | Spring Boot (REST + STOMP WebSocket + 예약 작업 + AI 서버 호출). 프로필 prod · dev | `back` (4단계에서 `back-blue` / `-green`) | `back-dev` |
| ai | FastAPI (Python 3.12). GMS 호출로 자연어 구조화·설문값 정규화. 외부에 열지 않는다 | `ai-blue` / `-green` (back 은 `http://ai:8000` — nginx 별칭이 운영 색으로 전달) | `ai-dev` |
| db | PostgreSQL 17 (백엔드 로컬 `back/compose.yaml` 과 동일), 컨테이너 1개, 사용자 `106hclk` | DB `tingle` | DB `tingle_dev` |
| jenkins | Jenkins LTS(JDK 21) + docker CLI, Docker 소켓 · `/srv/tingle` · `/backup` 마운트. 설정은 JCasC(`infra/jenkins/casc`). 검사 도구는 파트별 docker 이미지로 실행 (6절) | 공용 | 공용 |
| (5단계) prometheus, node-exporter, cadvisor, loki, promtail, grafana | 모니터링 | 공용 | 공용 |

- 운영 blue/green(4단계)은 둘 다 compose 에 적되 평소엔 한쪽만 켠다. 블루-그린 대상은 **ai → back** 순서다. 지금(3단계)은 `back` 하나를 재시작 배포한다.
- web 은 정적 페이지라 블루-그린 없이 재시작 배포한다. 재시작은 1초 안팎이며 끊겨도 새로고침으로 복구된다.
- AI 서버는 DB 를 쓰지 않는다. 필요한 입력은 back 이 요청에 담아 보낸다.
- 프로필 사진은 `uploads` 볼륨(`/data/uploads`)에 저장. 백엔드만 접근하며 백업 대상에 포함한다.

## 5. 앱·펌웨어 산출물

| 산출물 | 만드는 곳 | 배포 방법 |
| --- | --- | --- |
| Android APK (개발) | Jenkins `./gradlew assembleDebug -PapiBase=.../dev/api` | `/dev/app/latest.apk` 에 복사. 팀원이 QR 로 설치 |
| Android APK (운영) | Jenkins `master` 빌드 `./gradlew assembleRelease` | `/app/latest.apk` + GitLab Release 첨부. Play Store 등록 안 함 |
| iOS | Mac 에서 Xcode 수동 빌드 (Kotlin Multiplatform iOS 타깃) | 개발자 계정 유무에 따라 시뮬레이터 또는 실기기 사이드로드. **Apple Developer Program 가입 여부 결정 필요** |
| 키링 펌웨어 (.uf2 / .hex) | Jenkins `arduino-cli compile` 로 컴파일 검사 + 산출물 보관 | 실제 굽기는 USB 로 수동. 펌웨어 버전은 태그로 관리하고 앱·서버의 `algorithm_version` 과 맞춘다 |

- Android SDK 는 app 검사 단계가 `tingle-ci-android` 볼륨에 처음 한 번 받고, 플랫폼 · 빌드 도구는 AGP 가 내려받는다. Gradle 캐시도 볼륨(`tingle-ci-gradle-app`). 첫 빌드 약 5분, 이후 1분 안팎. arduino-cli 는 펌웨어 코드가 생기면 같은 방식으로 붙인다.
- APK 서명 키(`upload-keystore.jks`)와 비밀번호는 Jenkins Credentials. 저장소에 넣지 않는다.

## 6. CI/CD (Jenkins)

Jenkins job `tingle` 은 **develop · master 만 본다**. develop · master 에 들어오는 push(머지)와, 대상 브랜치가 develop · master 인 MR 만 빌드한다. 작업 브랜치 push 는 빌드하지 않는다. 단, 열린 MR 의 작업 브랜치에 커밋이 추가되면 그 MR 빌드를 다시 돌린다.

```
checkout
  → 변경 파트 감지 (MR 은 대상 브랜치, push 는 직전 커밋과 비교. back/, ai-server/, front/app/, front/web/, iot/. Jenkinsfile 변경 시 전체)
  → 검사 (병렬, 바뀐 파트만)
       back: gradlew build (컴파일 + checkstyle + Testcontainers 테스트)   eclipse-temurin:21-jdk
       ai:   pip install + pytest                                         python:3.12-slim
       web:  pnpm install --frozen-lockfile + pnpm lint + pnpm build      node:24-alpine
       app:  gradlew check :androidApp:assembleDebug                      eclipse-temurin:21-jdk + Android SDK
       iot:  펌웨어 코드가 없어 확인만 (생기면 arduino-cli compile, 보드 seeed_xiao_nrf52840)
  → [MR]      여기서 끝. 파트별 · 전체(jenkins) 결과를 GitLab MR 에 표시
  → [develop] app-env → /srv/tingle/dev 에 풀기 → compose up -d --build --no-deps web-dev back-dev → 응답 확인
  → [master]  app-env → DB 백업 → /srv/tingle/repo 에 풀기 → compose up -d --build --no-deps web → 응답 확인
              → back 블루-그린 (deploy-bluegreen.sh: 새 색 기동 → health → nginx 전환 → 옛 색 정지)
  → (이후)    develop: APK(dev) → /dev/app/latest.apk / master: APK(prod) → /app/latest.apk, GitLab Release / 펌웨어 산출물 보관
```

- `Jenkinsfile` 은 저장소 최상위. 검사 도구는 Jenkins 에 깔지 않고 파트마다 docker 이미지로 돌린다 (작업 폴더는 `--volumes-from` 으로 공유). 캐시는 `tingle-ci-*` docker 볼륨.
- 배포 대상은 Jenkinsfile 의 `DEPLOY` 표 (브랜치 → 풀 폴더 · 서비스 · 응답 확인 주소). ai 가 생기면 여기에 더한다. 확인 주소는 back `/actuator/health`(DB 연결 포함, 외부 비공개), ai `/health`.
- `app-env`: Jenkins Credentials 의 앱 비밀값을 `/srv/tingle/app.env` 로 쓴다. compose 가 `--env-file` 로 읽는다. 직접 수정 금지.
- DB 백업: master 배포 전 `pg_dump -Fc tingle` → `/backup/db-tingle-<시각>-<커밋>.dump`, 30개 보관.
- 비밀값(DB 계정, JWT 키, 푸시 키, 이후 GMS 키 · APK 키스토어 · FCM 서비스 계정)은 Jenkins Credentials. 목록은 `infra/jenkins/casc/jenkins.yaml` 주석. JCasC 에는 Credentials 를 두지 않는다 (재시작마다 화면에서 넣은 값을 지운다).
- 동시 빌드 1개. 앱 빌드와 서버 배포가 겹치지 않게 큐로 직렬화.
- GitLab "파이프라인 성공 시에만 머지" 를 켰다 (10/22). Jenkins 결과가 success 가 아니면 머지 버튼이 막힌다. Jenkins 결과가 아예 없는 MR(옛 브랜치)은 커밋을 하나 더 올려 검사를 받아야 한다. Jenkins 가 죽으면 아무도 머지할 수 없으므로 그때는 설정을 잠시 끈다.

## 7. 배포

| 대상 | 방법 | 서버 폴더 |
| --- | --- | --- |
| 개발 (web-dev · back-dev) | develop 머지 → Jenkins 재시작 배포 | `/srv/tingle/dev` |
| 운영 (web · back) | master 머지 → Jenkins. web 은 재시작, back 은 블루-그린(`infra/deploy-bluegreen.sh`). ai 는 생기면 블루-그린 | `/srv/tingle/repo` |
| 인프라 (nginx · db · jenkins) | 인프라 담당 수동 배포 | `/srv/tingle/repo` |

서버 `.env` 는 Jenkins 를 띄우는 값만(손으로 관리), 앱 비밀값은 `app.env`(Jenkins 가 씀). 수동 배포 명령:

`docker compose --env-file /srv/tingle/.env --env-file /srv/tingle/app.env -f /srv/tingle/repo/infra/docker-compose.yml up -d --build <서비스>` (프로젝트 이름은 compose 의 `name: tingle`)

### 운영 블루-그린 (`infra/deploy-bluegreen.sh <서비스> [rollback]`)

10/22 back 에 적용. 운영 색은 `/srv/tingle/nginx-state/<서비스>.conf` (nginx 가 include, 없으면 blue). 시험: 0.2초 간격 요청 중 blue → green 181건 · rollback 174건 모두 실패 0건, 최대 응답 0.09초.

ai 와 back 에 같은 스크립트를 쓴다. ai 를 먼저 전환해 새 back 이 새 AI 응답 형식을 받을 수 있게 한다.

```
1. 현재 색 읽기     /srv/tingle/nginx-state/<서비스>.conf. 파일 없으면 처음 (blue 로 띄움)
2. 반대 색 띄우기   compose up -d <서비스>-{new}
3. 상태 확인        back /actuator/health, ai /health 가 200 (2초 간격, 최대 90초)
                   실패 → stop <서비스>-{new}; exit 1  (운영 그대로, Jenkins 실패)
4. 전환            upstream 파일을 {new} 로 쓰고 nginx -s reload
                   back: 새 HTTP 요청·새 WebSocket 연결은 {new} 로. 기존 WebSocket 은 {old} 에 남음
                   ai: back 의 AI 호출 주소(내부 upstream)를 {new} 로
5. 정리            30초 후 stop <서비스>-{old} (SIGTERM → graceful shutdown 20초)
                   old 가 닫히면 앱이 재접속 → {new}. 이미지는 최근 2개 태그만 남기고 prune
```

- **롤백**: `sh /srv/tingle/repo/infra/deploy-bluegreen.sh back rollback` (Jenkins 컨테이너 안에서) — 멈춰 둔 이전 색을 다시 켜고 상태 확인 후 nginx 를 되돌린다.
- **예약 작업 중복**: 전환 중 20~30초 동안 blue·green 이 동시에 떠 있다. 7일 대기 만료·24시간 제안 만료·30일 제한 해제 같은 예약 작업은 **ShedLock(DB 잠금)** 으로 한 칸만 실행되게 한다. 멱등하게 짜서 두 번 돌아도 결과가 같아야 한다.
- **WebSocket**: 앱은 연결이 끊기면 1·2·4초 간격으로 재접속하고, 채팅 메시지는 DB 에 있으므로 재접속 후 마지막 메시지 ID 이후를 다시 받는다. 전환 1회당 채팅 끊김은 재접속 시간(목표 3초 이내)뿐이다.
- **AI 호출**: back 은 AI 서버 호출에 타임아웃과 재시도를 둔다. 전환 중 실패한 요청은 재시도로 복구한다.

## 8. DB·파일·백업

- DB 는 **PostgreSQL 17** 한 컨테이너에 운영 `tingle`, 개발 `tingle_dev` 두 DB 를 둔다. 확장은 쓰지 않는다.
- 서버 DB 테이블은 처음에 `docs/database/schema.sql` 로 만들었다 (10/22, 각 24개). 이후 구조 변경은 환경마다 다르게 반영한다 (BE 컨벤션 `[db-schema]`, Flyway 안 씀).
  - 개발 `tingle_dev`: 프로필 dev 가 `ddl-auto: update` (`back/src/main/resources/application-dev.yml`). develop 배포 때 새 테이블 · 컬럼이 자동 추가된다. 컬럼 삭제 · 이름 변경은 SQL 로 직접.
  - 운영 `tingle`: 프로필 prod 가 `ddl-auto: validate`. **BE 가 변경 SQL 을 인프라에 주면, 인프라가 master 머지 전에 백업을 받고 운영 DB 에 실행**한다. 빠뜨리면 새 색이 시작하지 못해 상태 확인에 실패하고 옛 색이 그대로 운영된다.
- 블루-그린 전환 중 이전·새 버전이 같은 DB 를 쓰므로 **이전 버전이 깨지지 않는 변경만 한 번에** 배포한다. 컬럼 이름 변경·삭제는 추가 → 코드 전환 → 삭제로 나눈다.
- 핵심 제약은 DB 에 둔다: `offline_encounter_submissions(encounter_id, device_id)` 유일, `pair_restrictions`·`chat_rooms` 의 정렬된 사용자 쌍 유일, 활성 채팅방 1개 부분 유일 인덱스. 앱·키링이 중복 업로드해도 DB 가 막는다.
- `master` 배포 직전 `pg_dump` → `/backup/db-tingle-<시각>-<커밋>.dump` (30개 보관). 매일 한국 시각 04:00 운영 · 개발 DB `pg_dump` + `/data/uploads` tar → `/backup/daily-*` (7일치, `infra/backup-daily.sh`, 서버 crontab). 로그는 `/backup/daily.log`. 서버가 1대라 서버 유실에는 못 버티므로 **주 1회 팀장 로컬로 내려받는다**. DB 포트는 열지 않는다.
- 복원은 구성 직후 1회(10/22 완료), 이후 2주마다 연습하고 걸린 시간을 `infra/배포_측정.md` 에 기록한다.
- 개인정보: 테스트 기간엔 **가상 프로필만** 넣는다. 실사용자 데이터를 넣게 되면 백업 파일 암호화와 보존 기간(기획안 12절 미확정)을 먼저 정한다.

## 9. 푸시 알림

| 항목 | 계획 |
| --- | --- |
| 수단 | Firebase Cloud Messaging(FCM). Android 즉시 가능, iOS 는 APNs 키가 필요해 Apple Developer Program 이 있어야 함 |
| 발송 시점 | 익명 제안 생성, 상대 수락 완료, 새 채팅 메시지. 한쪽 기록만 도착한 `WAITING_FOR_COUNTERPART` 에는 **보내지 않음**(기획 확정) |
| 서버 | 백엔드가 FCM Admin SDK 로 발송. 서비스 계정 JSON 은 Jenkins Credentials → `app-env` 단계가 서버에 파일로 써서 back 에 연결 |
| MVP 대안 | iOS 푸시가 안 되면 앱 실행 시 `/api/v1/match-proposals` 폴링 + WebSocket 알림으로 대체. 푸시 도착 시각은 보장하지 않는다는 기획 전제와 일치 |

## 10. 모니터링·로그 (5단계)

| 도구 | 역할 | 메모리 |
| --- | --- | --- |
| Prometheus + node-exporter + cAdvisor | 서버·컨테이너 지표(CPU, 메모리, 응답 시간, 에러율) | 0.3~0.5GB |
| Loki + Promtail | 컨테이너 로그 수집·검색 | 0.3~0.5GB |
| Grafana | 대시보드. `/grafana/` 로 접속, 로그인 필수 | 0.1~0.2GB |

- 배포 전후 응답 시간·에러율·WebSocket 연결 수 그래프로 블루-그린 효과를 보여 준다.
- 따로 재는 것: back → AI 서버 호출 시간·실패율, AI 서버의 GMS 호출 시간·실패율, 동기화 API 처리 시간, 예약 작업 실행 횟수(중복 실행 감지), 상태별 이벤트 수(`WAITING_FOR_COUNTERPART`, `EXPIRED_UNPAIRED` 등).
- 로그에 프로필 원문·사진 경로·기기 키를 찍지 않는다 (기획안 8.3). AI 서버도 GMS 요청·응답 원문을 로그에 남기지 않는다.

## 11. 보안

- SSH 는 키 인증만, 비밀번호 로그인 끔. 접속 IP 제한.
- 비밀값: 서버 `/srv/tingle/.env` 에는 Jenkins 를 띄우는 값만(관리자 계정 · Webhook 비밀값), 앱 비밀값은 Jenkins Credentials → `/srv/tingle/app.env`. 저장소에는 `infra/.env.example`. GMS 키는 ai 컨테이너에만 넣는다.
- Jenkins·Grafana 는 Nginx 뒤 경로로만 열고 계정 로그인. Docker 소켓을 마운트하는 Jenkins 는 사실상 root 권한.
- AI 서버는 외부에 열지 않고 back 만 호출한다.
- 기기 인증 키(키링 등록 시 발급)는 DB 에 해시 또는 서버 비밀키로 서명 검증만 하고 평문 저장하지 않는다. 키 발급·회전 절차는 BE·펌웨어 담당이 2주차에 설계.
- 프로필·사진 조회 API 는 서버에서 상호 수락 여부를 확인한다. 앱이 숨기는 것으로 대신하지 않는다.
- 업로드 사진은 확장자·크기 제한, EXIF 위치 정보 제거.

## 12. 단계별 로드맵

계획서 3-5 일정과 맞춤.

| 단계 | 시기 | 만드는 것 | 완료 기준 | 상태 (10/22) |
| --- | --- | --- | --- | --- |
| **0. 서버 준비** | 3주차 (10/19~) 서버 수령 직후 | Docker·Compose, 도메인·certbot, `/srv/tingle/.env`, Jenkins 실행, GitLab Webhook | Jenkins 로그인, MR 에 Jenkins 반응 | 완료 |
| **1. CI 검사** | 3주차 | Jenkinsfile 검사 단계 (back·ai·app·web·firmware), 파트별 docker 이미지 | MR 에 결과 표시, 실패 시 머지 불가 | 완료 (펌웨어 검사는 코드 생기면) |
| **2. 개발 환경** | 3~4주차 | compose, back·ai·web Dockerfile, develop 재시작 배포, `/dev/app/latest.apk` | 개발 APK 로 `/dev/api` 접속 성공, `/dev/` 랜딩 표시, back-dev → ai-dev 호출 성공 | web-dev · back-dev · ai-dev 배포 완료 (ai 10/23 MR !59 Dockerfile). APK 남음 |
| **3. 운영 재시작 배포** | 4주차 (10/26~) | master 재시작 배포. **다운타임·WebSocket 끊김 측정** | `/`·`/api` 동작, 다운타임 기록 | 완료 (10/22 첫 master 머지 MR !53, web 재시작 끊김 0.35초, `infra/배포_측정.md`) |
| **4. 블루-그린** | 5주차 (11/2~11/6) | deploy-bluegreen.sh, upstream 전환(ai·back), 롤백, ShedLock 확인 | 배포 중 1초 간격 요청 실패 0건, 채팅 재접속 3초 이내 | back · ai 전환 · 롤백 구현, back 시험 실패 0건. graceful shutdown · ai 전환 시험 · WebSocket 확인 남음 |
| **5. 운영 보강** | 6주차 (11/9~11/13) | 매일 백업, 모니터링, 푸시(FCM), 이미지 보관 정책 | 복원 1회 성공, Grafana 에서 배포 전후 확인 | 배포 전 백업만 완료 |
| **6. 최종** | 7주차 (11/16~) | 운영 APK 릴리스, 시연용 데이터 초기화 스크립트 | E2E 시연 전 운영 환경 고정 | — |

3단계에서 재시작 배포의 끊김을 먼저 재 두어야 4단계에서 "○초 → 0초" 비교가 나온다.

## 13. 측정

```
배포 시작 전부터 끝난 뒤까지 1초 간격으로
  curl -s -o /dev/null -w "%{http_code} %{time_total}\n" https://도메인/api/actuator/health
→ 2xx 아닌 응답 수 = 끊긴 초, time_total 로 지연도 기록

WebSocket: 테스트 앱 2대를 채팅방에 두고 배포 → 끊김 감지 시각과 재접속 완료 시각의 차이를 기록
```

`배포_측정.md` 에 날짜·커밋·실패 수·최대 응답 시간·재접속 시간을 남긴다. 복원 연습 시간도 같은 파일에 적는다.

## 14. 각 파트에 요청

| 파트 | 요청 | 이유 |
| --- | --- | --- |
| BE | `/actuator/health`(DB 연결 포함), JWT(세션 금지), graceful shutdown 20초, 예약 작업 ShedLock + 멱등, AI 서버 호출 타임아웃·재시도·시간 로그, AI 서버 주소는 환경변수 | 상태 확인·전환 중 두 칸 동시 실행·병목 측정 |
| BE | 프로필·사진 API 서버측 권한 검사, 업로드는 `/data/uploads` 볼륨 경로를 환경변수로 | 상호 수락 전 비공개·백업 |
| AI | `ai-server/Dockerfile`, 컨테이너에서는 `HOST=0.0.0.0` 으로 실행, `/health` 유지, GMS 키는 환경변수, GMS 호출 타임아웃·재시도, graceful shutdown | 컨테이너 상태 확인·블루-그린 전환 |
| AI | GMS 프롬프트·응답 스키마 버전 관리, 구조화 결과 검증 규칙, 요청·응답 원문 로그 금지 | back 과의 계약·개인정보 |
| 앱(FE) | Gradle 속성 `apiBase` 로 서버 선택, WebSocket 자동 재접속(1·2·4초), 마지막 메시지 ID 이후 재수신 | 운영/개발 분리·블루-그린 전환 복구 |
| 앱(FE) | Android 서명 키 생성 후 팀장에게 전달(Jenkins Credentials 등록), `./gradlew check` 통과 | CI 빌드 |
| 웹(FE) | `front/web/Dockerfile`(Node·pnpm 빌드 → `nginx:alpine`), 빌드 기준 경로를 설정으로 받아 `/` 와 `/dev/` 양쪽에서 동작 | 운영/개발 경로 분리 |
| 전체 | 로그에 개인정보 원문 금지 | 기획안 8.3 |

10/22 기준 진행:
- BE: JWT(세션 금지) 됨. `/actuator/health` 됨(MR !43). **지금 필요한 것은 graceful shutdown** (블루-그린). 나머지(ShedLock, AI 호출 타임아웃 · 주소, 사진 권한 · 업로드 경로)는 해당 기능을 만들 때 지킨다. `back/Dockerfile` 은 인프라가 작성(MR !40).
- 웹: `front/web/Dockerfile` 있음, `/` · `/dev/` 동작.
- AI: `ai-server/Dockerfile` 없음 (인프라가 작성 가능).

## 15. 결정 필요

| 항목 | 선택지 | 기한 |
| --- | --- | --- |
| Apple Developer Program ($99/년) | 가입(iOS 실기기 배포·푸시 가능) / 미가입(시뮬레이터 + Android 중심 시연) | 3주차 |
| iOS 빌드용 Mac | 팀원 보유 / 교육장 대여 | 3주차 |
| 개인정보 보존 기간·백업 암호화 | 가상 데이터만 쓰는 동안 보류 | 실사용 전 |

## 16. Jira 이슈 (안)

| 이슈 | 내용 | 단계 |
| --- | --- | --- |
| -7 (기존) | 개발 서버 Docker 실행 환경 구성 | 0, 2 |
| -8 (기존) | Jenkins CI/CD 파이프라인 구성 | 0, 1, 2 |
| -24 (기존) | 인프라 계획 수립 | — |
| 신규 | [INFRA] 도메인·HTTPS 인증서·방화벽 설정 | 0 |
| ~~신규~~ | ~~[INFRA] Jenkins 에이전트 이미지 구성~~ → 파트별 docker 이미지로 대체(6절), 이슈 불필요 | — |
| 신규 | [INFRA] web·ai 컨테이너와 Nginx 경로 구성 | 2 |
| 신규 | [INFRA] 개발 APK 자동 빌드와 `/dev/app` 배포 링크 | 2 |
| 신규 | [INFRA] 운영 환경 재시작 배포와 다운타임·WebSocket 끊김 측정 | 3 |
| 신규 | [INFRA] 블루-그린 전환 스크립트와 Nginx upstream·WebSocket 구성 (ai·back) | 4 |
| 신규 | [INFRA] 블루-그린 무중단 검증 (배포 중 1초 간격 요청 실패 0건·채팅 재접속 3초 이내) | 4 |
| 신규 | [INFRA] 배포 전 DB·업로드 백업과 복원 절차 | 5 |
| 신규 | [INFRA] 모니터링(Prometheus·Loki·Grafana) 구성 | 5 |
| 신규 | [INFRA] FCM 서비스 계정·APNs 키 Credentials 등록과 환경변수 연결 | 5 |

파트 코드에서 해야 할 준비(health·graceful shutdown·Dockerfile·apiBase 등)는 14절 요청으로 각 파트가 자기 이슈를 만든다.

## 17. 체크리스트

- [x] 서버 수령 후 실제 사양 확인, 2절 갱신
- [x] 도메인 확정, DNS 연결, certbot 발급
- [x] 서버 `/srv/tingle/.env`(Jenkins 기동 값) 작성, 앱 비밀값은 Jenkins Credentials → `app.env` (GMS 키 · FCM · 업로드 경로 · AI 서버 주소는 해당 컨테이너 연결 때)
- [x] Jenkins 관리자 계정, GitLab Webhook·토큰 등록 — APK 키스토어 Credentials 는 남음
- [x] 검사 이미지 빌드 시간·Gradle 캐시 확인 (첫 빌드 약 5분, 이후 1~2분)
- [ ] BE 에 graceful shutdown 요청 (health 는 완료) (ShedLock·AI 호출 재시도는 해당 기능 때)
- [ ] AI 에 `HOST=0.0.0.0`·graceful shutdown 요청 (Dockerfile 은 인프라가 작성 가능)
- [ ] 앱 팀에 apiBase·재접속 요청 — 웹 Dockerfile·기준 경로는 완료
- [ ] 3단계 재시작 배포 다운타임·WebSocket 끊김 기록
- [ ] 4단계 배포 중 요청 실패 0건, 재접속 3초 이내 확인
- [ ] 백업 복원 1회 연습, 주 1회 로컬 내려받기
- [ ] Apple Developer Program·Mac 결정
