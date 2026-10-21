# 인프라 계획 — Suddenly I See

v0.3.0, 2026.10.21. 모바일 앱을 Kotlin Multiplatform 으로 정하고, React 랜딩 웹 컨테이너와 FastAPI AI 서버 컨테이너를 추가했다.

## 1. 목표와 원칙

- 운영(`master`)은 **블루-그린 무중단 배포**. 사용자가 채팅·동기화 중이어도 배포로 끊기지 않는다. WebSocket 은 재접속으로 복구한다.
- 개발(`develop`)은 같은 서버의 `/dev` 경로에 **재시작 배포**. 앱의 개발 빌드는 `/dev/api` 를 바라본다.
- 배포는 사람이 서버에 접속해서 하지 않는다. **GitLab 머지 → Jenkins → 서버** 로만 간다.
- AI 는 **AI 서버(FastAPI)가 GMS API(GPT-6 Luna)를 호출**해 처리한다. 백엔드는 AI 서버만 부르고 GMS 를 직접 부르지 않는다. 오프라인 매칭 판정은 키링 안에서 끝나므로 서버·GMS 를 부르지 않는다.
- 외부에 여는 포트는 22, 80, 443 뿐. DB·AI 서버·Jenkins·Grafana 는 Nginx 뒤 또는 내부망에만 둔다.
- 상호 수락 전 **프로필·사진은 서버 어디서도 노출하지 않는다**. 사진 파일도 Nginx 가 직접 서빙하지 않고 백엔드가 권한을 확인한 뒤 준다.
- 단계마다 **측정값**을 남긴다 (배포 다운타임, 응답 시간, WebSocket 재접속 시간, 복구 시간).

## 2. 서버

| 서버 | 사양 | 역할 | 상태 |
| --- | --- | --- | --- |
| 기본 지급 EC2 `T00003-00372` | Lightsail xlarge (4 vCPU / 16GB, 수령 후 확인) | 운영 + 개발(`/dev`) + Jenkins + DB + 모니터링 전부 | 수령 대기 |

- 서버는 **1대 구성**이다.
- 메모리 예산(16GB 가정): nginx 0.1 / web 0.05 ×2 / backend 운영 1.5(전환 중 3) / backend-dev 1.5 / ai 운영 0.5(전환 중 1) / ai-dev 0.5 / PostgreSQL 1 / Jenkins 1.5 / 모니터링 1 / 빌드 중 +3~4(Android Gradle) → 평소 8GB, 최대 13GB. 컨테이너마다 `mem_limit`, Jenkins 동시 빌드 1개.
- 수령 즉시 `nproc`, `free -h`, `df -h` 로 실제 사양을 확인해 이 표를 갱신한다. 8GB 이면 모니터링을 Prometheus+Grafana 만으로 줄이고 Gradle 힙을 2GB 로 제한한다.
- iOS 빌드는 Linux 서버에서 못 한다. **Mac 1대**(팀 보유 또는 교육장)에서 Xcode 로 수동 빌드한다. 서버 역할이 아니므로 아래 CI 범위에서 제외.

## 3. 네트워크·도메인·인증서

| 항목 | 계획 |
| --- | --- |
| 도메인 | SSAFY 제공 `*.p.ssafy.io` 1개 (예: `k15e106.p.ssafy.io`). 운영 `/`·`/api`, 개발 `/dev`·`/dev/api` 로 경로 분리 |
| HTTPS | Let's Encrypt(certbot). 서버 수령 후 첫 작업. Android 는 평문 HTTP 를 기본 차단하고 iOS 는 ATS 로 HTTPS 를 요구하므로 인증서 없이는 앱 연동 불가 |
| 진입점 | Nginx 하나. `/` → web(운영), `/api/` → backend(운영), `/ws/` → backend WebSocket(운영), `/dev/` → web-dev, `/dev/api/`, `/dev/ws/` → backend-dev, `/app/` → APK 다운로드 정적 파일, `/jenkins/`, `/grafana/` |
| AI 서버 | Nginx 로 열지 않는다. backend 가 Docker 내부망 주소(`http://ai:8000`, 개발은 `http://ai-dev:8000`)로만 호출 |
| WebSocket | Nginx 에 `Upgrade`/`Connection` 헤더 전달, `proxy_read_timeout 3600`. 블루-그린 전환 시 기존 연결은 old 컨테이너에 남아 있다가 종료되므로 앱이 재접속 |
| 방화벽 | 22(팀장 IP), 80, 443. 나머지 전부 닫음 |
| 내부망 | Docker 네트워크. DB 5432, AI 8000, Jenkins 8080 은 외부 비공개 |

앱은 경로 기준 URL 을 쓰므로 쿠키 분리 문제는 없다. 운영·개발 앱 빌드는 `./gradlew assembleRelease -PapiBase=https://도메인/api` 처럼 Gradle 속성으로 서버를 고르고, 앱은 빌드 설정 값으로 읽는다.

## 4. 컨테이너 구성

모두 `infra/docker-compose.yml` 하나로 띄운다. `Dockerfile` 은 각 파트 폴더(`backend/`, `ai-server/`, `frontend/web/`)에 둔다. 앱(`frontend/app/`)과 펌웨어(`iot/`)는 컨테이너가 아니라 **빌드 산출물**이다.

| 서비스 | 기술 | 운영 | 개발 |
| --- | --- | --- | --- |
| nginx | Nginx + certbot 볼륨, `/app/` 정적 디렉터리 | 공용 | 공용 |
| web | React 랜딩·서비스 설명 페이지. Node 로 빌드하고 `nginx:alpine` 으로 정적 파일 서빙 | `web` | `web-dev` |
| backend | Spring Boot (REST + STOMP WebSocket + 예약 작업 + AI 서버 호출) | `backend-blue` / `-green` | `backend-dev` |
| ai | FastAPI (Python 3.12). GMS 호출로 자연어 구조화·설문값 정규화 | `ai-blue` / `-green` | `ai-dev` |
| db | PostgreSQL 16, 컨테이너 1개 | DB `sis` | DB `sis_dev` |
| jenkins | Jenkins LTS, Docker 소켓 마운트. 에이전트 이미지에 JDK 21·Android SDK·Node 20·Python 3.12·arduino-cli | 공용 | 공용 |
| (5단계) prometheus, node-exporter, cadvisor, loki, promtail, grafana | 모니터링 | 공용 | 공용 |

- 운영 blue/green 은 둘 다 compose 에 적되 평소엔 한쪽만 켠다. 블루-그린 대상은 **ai → backend** 순서다.
- web 은 정적 페이지라 블루-그린 없이 재시작 배포한다. 재시작은 1초 안팎이며 끊겨도 새로고침으로 복구된다.
- AI 서버는 DB 를 쓰지 않는다. 필요한 입력은 backend 가 요청에 담아 보낸다.
- 프로필 사진은 `uploads` 볼륨(`/data/uploads`)에 저장. 백엔드만 접근하며 백업 대상에 포함한다.

## 5. 앱·펌웨어 산출물

| 산출물 | 만드는 곳 | 배포 방법 |
| --- | --- | --- |
| Android APK (개발) | Jenkins `./gradlew assembleDebug -PapiBase=.../dev/api` | `/app/dev/latest.apk` 에 복사. 팀원이 QR 로 설치 |
| Android APK (운영) | Jenkins `master` 빌드 `./gradlew assembleRelease` | `/app/latest.apk` + GitLab Release 첨부. Play Store 등록 안 함 |
| iOS | Mac 에서 Xcode 수동 빌드 (Kotlin Multiplatform iOS 타깃) | 개발자 계정 유무에 따라 시뮬레이터 또는 실기기 사이드로드. **Apple Developer Program 가입 여부 결정 필요** |
| 키링 펌웨어 (.uf2 / .hex) | Jenkins `arduino-cli compile` 로 컴파일 검사 + 산출물 보관 | 실제 굽기는 USB 로 수동. 펌웨어 버전은 태그로 관리하고 앱·서버의 `algorithm_version` 과 맞춘다 |

- Jenkins 에이전트 이미지에 Android SDK(cmdline-tools·platform·build-tools)와 arduino-cli + Adafruit nRF52 코어를 넣는다. Gradle 캐시는 볼륨으로 유지한다. 첫 빌드 10~15분, 이후 APK 빌드 3~5분.
- APK 서명 키(`upload-keystore.jks`)와 비밀번호는 Jenkins Credentials. 저장소에 넣지 않는다.

## 6. CI/CD (Jenkins)

```
checkout
  → 변경 파트 감지 (backend/, ai-server/, frontend/app/, frontend/web/, iot/ diff. infra/·Jenkinsfile 변경 시 전체)
  → 검사 (병렬)  backend:  gradle build + checkstyle + 테스트
                 ai:       pytest
                 app:      ./gradlew check (lint + 단위 테스트)
                 web:      npm ci + npm run build
                 firmware: arduino-cli compile (보드 seeed_xiao_nrf52840)
  → [MR]      여기서 끝. 결과를 GitLab MR 에 표시. 실패하면 머지 불가
  → [develop] ai·backend·web 이미지 빌드(태그 dev) → compose up -d ai-dev backend-dev web-dev
              app: APK(dev) 빌드 → /app/dev/latest.apk
  → [master]  ai·backend·web 이미지 빌드(태그 커밋해시 + prod) → DB 백업 → deploy.sh ai → deploy.sh backend → compose up -d web
              app: APK(prod) 빌드 → /app/latest.apk, GitLab Release
              firmware: 산출물 보관 (자동 굽기 없음)
```

- `Jenkinsfile` 은 저장소 최상위. 비밀값(DB, GMS 키, APK 키스토어, FCM 서비스 계정)은 Jenkins Credentials.
- 동시 빌드 1개. 앱 빌드와 서버 배포가 겹치지 않게 큐로 직렬화.

## 7. 배포

### 개발 (재시작)

`docker compose -p sis --env-file /서버경로/.env -f infra/docker-compose.yml up -d --build ai-dev backend-dev web-dev`

### 운영 (블루-그린, `deploy.sh <서비스>`)

ai 와 backend 에 같은 스크립트를 쓴다. ai 를 먼저 전환해 새 backend 가 새 AI 응답 형식을 받을 수 있게 한다.

```
1. 현재 색 읽기     nginx/upstream/<서비스>.conf. 파일 없으면 blue
2. 반대 색 띄우기   compose up -d <서비스>-{new}
3. 상태 확인        backend /actuator/health, ai /health 가 200 (2초 간격, 최대 90초. Flyway 마이그레이션 시간 포함)
                   실패 → stop <서비스>-{new}; exit 1  (운영 그대로, Jenkins 실패)
4. 전환            upstream 파일을 {new} 로 쓰고 nginx -s reload
                   backend: 새 HTTP 요청·새 WebSocket 연결은 {new} 로. 기존 WebSocket 은 {old} 에 남음
                   ai: backend 의 AI 호출 주소(내부 upstream)를 {new} 로
5. 정리            30초 후 stop <서비스>-{old} (SIGTERM → graceful shutdown 20초)
                   old 가 닫히면 앱이 재접속 → {new}. 이미지는 최근 2개 태그만 남기고 prune
```

- **롤백**: `deploy.sh rollback <서비스>` — 이전 색을 다시 켜고 upstream 을 되돌린 뒤 reload.
- **예약 작업 중복**: 전환 중 20~30초 동안 blue·green 이 동시에 떠 있다. 7일 대기 만료·24시간 제안 만료·30일 제한 해제 같은 예약 작업은 **ShedLock(DB 잠금)** 으로 한 칸만 실행되게 한다. 멱등하게 짜서 두 번 돌아도 결과가 같아야 한다.
- **WebSocket**: 앱은 연결이 끊기면 1·2·4초 간격으로 재접속하고, 채팅 메시지는 DB 에 있으므로 재접속 후 마지막 메시지 ID 이후를 다시 받는다. 전환 1회당 채팅 끊김은 재접속 시간(목표 3초 이내)뿐이다.
- **AI 호출**: backend 는 AI 서버 호출에 타임아웃과 재시도를 둔다. 전환 중 실패한 요청은 재시도로 복구한다.

## 8. DB·파일·백업

- DB 는 **PostgreSQL 16** 한 컨테이너에 운영 `sis`, 개발 `sis_dev` 두 DB 를 둔다. 확장은 쓰지 않는다.
- 구조 변경은 Flyway. 전환 중 이전·새 버전이 같은 DB 를 쓰므로 **이전 버전이 깨지지 않는 변경만 한 번에** 배포한다. 컬럼 이름 변경·삭제는 추가 → 코드 전환 → 삭제로 나눈다.
- 핵심 제약은 DB 에 둔다: `offline_encounter_submissions(encounter_id, device_id)` 유일, `pair_restrictions`·`chat_rooms` 의 정렬된 사용자 쌍 유일, 활성 채팅방 1개 부분 유일 인덱스. 앱·키링이 중복 업로드해도 DB 가 막는다.
- `master` 배포 직전과 매일 새벽 `pg_dump` + `/data/uploads` tar → 서버 `/backup/`, 7일치. 서버가 1대라 서버 유실에는 못 버티므로 **주 1회 팀장 로컬로 내려받는다**. DB 포트는 열지 않는다.
- 복원은 구성 직후 1회, 이후 2주마다 연습하고 걸린 시간을 기록한다.
- 개인정보: 테스트 기간엔 **가상 프로필만** 넣는다. 실사용자 데이터를 넣게 되면 백업 파일 암호화와 보존 기간(기획안 12절 미확정)을 먼저 정한다.

## 9. 푸시 알림

| 항목 | 계획 |
| --- | --- |
| 수단 | Firebase Cloud Messaging(FCM). Android 즉시 가능, iOS 는 APNs 키가 필요해 Apple Developer Program 이 있어야 함 |
| 발송 시점 | 익명 제안 생성, 상대 수락 완료, 새 채팅 메시지. 한쪽 기록만 도착한 `WAITING_FOR_COUNTERPART` 에는 **보내지 않음**(기획 확정) |
| 서버 | 백엔드가 FCM Admin SDK 로 발송. 서비스 계정 JSON 은 Jenkins Credentials → `infra/.env` 경로 |
| MVP 대안 | iOS 푸시가 안 되면 앱 실행 시 `/api/v1/match-proposals` 폴링 + WebSocket 알림으로 대체. 푸시 도착 시각은 보장하지 않는다는 기획 전제와 일치 |

## 10. 모니터링·로그 (5단계)

| 도구 | 역할 | 메모리 |
| --- | --- | --- |
| Prometheus + node-exporter + cAdvisor | 서버·컨테이너 지표(CPU, 메모리, 응답 시간, 에러율) | 0.3~0.5GB |
| Loki + Promtail | 컨테이너 로그 수집·검색 | 0.3~0.5GB |
| Grafana | 대시보드. `/grafana/` 로 접속, 로그인 필수 | 0.1~0.2GB |

- 배포 전후 응답 시간·에러율·WebSocket 연결 수 그래프로 블루-그린 효과를 보여 준다.
- 따로 재는 것: backend → AI 서버 호출 시간·실패율, AI 서버의 GMS 호출 시간·실패율, 동기화 API 처리 시간, 예약 작업 실행 횟수(중복 실행 감지), 상태별 이벤트 수(`WAITING_FOR_COUNTERPART`, `EXPIRED_UNPAIRED` 등).
- 로그에 프로필 원문·사진 경로·기기 키를 찍지 않는다 (기획안 8.3). AI 서버도 GMS 요청·응답 원문을 로그에 남기지 않는다.

## 11. 보안

- SSH 는 키 인증만, 비밀번호 로그인 끔. 접속 IP 제한.
- 비밀값은 서버 `infra/.env` 와 Jenkins Credentials 에만. 저장소에는 `.env.example`. GMS 키는 ai 컨테이너에만 넣는다.
- Jenkins·Grafana 는 Nginx 뒤 경로로만 열고 계정 로그인. Docker 소켓을 마운트하는 Jenkins 는 사실상 root 권한.
- AI 서버는 외부에 열지 않고 backend 만 호출한다.
- 기기 인증 키(키링 등록 시 발급)는 DB 에 해시 또는 서버 비밀키로 서명 검증만 하고 평문 저장하지 않는다. 키 발급·회전 절차는 BE·펌웨어 담당이 2주차에 설계.
- 프로필·사진 조회 API 는 서버에서 상호 수락 여부를 확인한다. 앱이 숨기는 것으로 대신하지 않는다.
- 업로드 사진은 확장자·크기 제한, EXIF 위치 정보 제거.

## 12. 단계별 로드맵

계획서 3-5 일정과 맞춤.

| 단계 | 시기 | 만드는 것 | 완료 기준 |
| --- | --- | --- | --- |
| **0. 서버 준비** | 3주차 (10/19~) 서버 수령 직후 | Docker·Compose, 도메인·certbot, `infra/.env`, Jenkins 실행, GitLab Webhook, Jenkins 에이전트 이미지(Android SDK·Node·Python·arduino-cli) | Jenkins 로그인, MR 에 Jenkins 반응 |
| **1. CI 검사** | 3주차 | Jenkinsfile 검사 단계 (backend·ai·app·web·firmware) | MR 에 결과 표시, 실패 시 머지 불가 |
| **2. 개발 환경** | 3~4주차 | compose, backend·ai·web Dockerfile, develop 재시작 배포, `/app/dev/latest.apk` | 개발 APK 로 `/dev/api` 접속 성공, `/dev/` 랜딩 표시, backend-dev → ai-dev 호출 성공 |
| **3. 운영 재시작 배포** | 4주차 (10/26~) | master 재시작 배포. **다운타임·WebSocket 끊김 측정** | `/`·`/api` 동작, 다운타임 기록 |
| **4. 블루-그린** | 5주차 (11/2~11/6) | deploy.sh, upstream 전환(ai·backend), 롤백, ShedLock 확인 | 배포 중 1초 간격 요청 실패 0건, 채팅 재접속 3초 이내 |
| **5. 운영 보강** | 6주차 (11/9~11/13) | 배포 전 백업, 모니터링, 푸시(FCM), 이미지 보관 정책 | 복원 1회 성공, Grafana 에서 배포 전후 확인 |
| **6. 최종** | 7주차 (11/16~) | 운영 APK 릴리스, 시연용 데이터 초기화 스크립트 | E2E 시연 전 운영 환경 고정 |

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
| BE | `/actuator/health`(DB 연결 포함), JWT(세션 금지), Flyway, graceful shutdown 20초, 예약 작업 ShedLock + 멱등, AI 서버 호출 타임아웃·재시도·시간 로그, AI 서버 주소는 환경변수 | 상태 확인·전환 중 두 칸 동시 실행·병목 측정 |
| BE | 프로필·사진 API 서버측 권한 검사, 업로드는 `/data/uploads` 볼륨 경로를 환경변수로 | 상호 수락 전 비공개·백업 |
| AI | `ai-server/Dockerfile`, 컨테이너에서는 `HOST=0.0.0.0` 으로 실행, `/health` 유지, GMS 키는 환경변수, GMS 호출 타임아웃·재시도, graceful shutdown | 컨테이너 상태 확인·블루-그린 전환 |
| AI | GMS 프롬프트·응답 스키마 버전 관리, 구조화 결과 검증 규칙, 요청·응답 원문 로그 금지 | backend 와의 계약·개인정보 |
| 앱(FE) | Gradle 속성 `apiBase` 로 서버 선택, WebSocket 자동 재접속(1·2·4초), 마지막 메시지 ID 이후 재수신 | 운영/개발 분리·블루-그린 전환 복구 |
| 앱(FE) | Android 서명 키 생성 후 팀장에게 전달(Jenkins Credentials 등록), `./gradlew check` 통과 | CI 빌드 |
| 웹(FE) | `frontend/web/Dockerfile`(Node 빌드 → `nginx:alpine`), 빌드 기준 경로를 설정으로 받아 `/` 와 `/dev/` 양쪽에서 동작 | 운영/개발 경로 분리 |
| 펌웨어 | `iot/` 에 arduino-cli 로 빌드되는 구조(보드 FQBN·라이브러리 목록 `.txt`), 펌웨어 버전 상수 | CI 컴파일 검사·버전 추적 |
| 전체 | 로그에 개인정보 원문 금지 | 기획안 8.3 |

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
| 신규 | [INFRA] Jenkins 에이전트 이미지(Android SDK·Node·Python·arduino-cli) 구성 | 0, 1 |
| 신규 | [INFRA] web·ai 컨테이너와 Nginx 경로 구성 | 2 |
| 신규 | [INFRA] 개발 APK 자동 빌드와 `/app/dev` 배포 링크 | 2 |
| 신규 | [INFRA] 운영 환경 재시작 배포와 다운타임·WebSocket 끊김 측정 | 3 |
| 신규 | [INFRA] 블루-그린 전환 스크립트와 Nginx upstream·WebSocket 구성 (ai·backend) | 4 |
| 신규 | [INFRA] 블루-그린 무중단 검증 (배포 중 1초 간격 요청 실패 0건·채팅 재접속 3초 이내) | 4 |
| 신규 | [INFRA] 배포 전 DB·업로드 백업과 복원 절차 | 5 |
| 신규 | [INFRA] 모니터링(Prometheus·Loki·Grafana) 구성 | 5 |
| 신규 | [INFRA] FCM 서비스 계정·APNs 키 Credentials 등록과 환경변수 연결 | 5 |

파트 코드에서 해야 할 준비(health·graceful shutdown·Dockerfile·apiBase 등)는 14절 요청으로 각 파트가 자기 이슈를 만든다.

## 17. 체크리스트

- [ ] 서버 수령 후 실제 사양 확인, 2절 갱신
- [ ] 도메인 확정, DNS 연결, certbot 발급
- [ ] `infra/.env` 작성 (DOMAIN, DB 비밀번호, GMS 키, FCM 경로, UPLOAD_DIR, AI 서버 주소)
- [ ] Jenkins 관리자 계정, GitLab Webhook·토큰 등록, APK 키스토어 Credentials
- [ ] Jenkins 에이전트 이미지 빌드 시간·Gradle 캐시 확인
- [ ] BE 에 health·graceful shutdown·ShedLock·AI 호출 재시도 요청
- [ ] AI 에 Dockerfile·`HOST=0.0.0.0`·graceful shutdown 요청
- [ ] 앱 팀에 apiBase·재접속, 웹 담당에 Dockerfile·기준 경로 요청
- [ ] 3단계 재시작 배포 다운타임·WebSocket 끊김 기록
- [ ] 4단계 배포 중 요청 실패 0건, 재접속 3초 이내 확인
- [ ] 백업 복원 1회 연습, 주 1회 로컬 내려받기
- [ ] Apple Developer Program·Mac 결정
