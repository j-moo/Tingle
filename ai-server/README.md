# AI Server

Python 3.12 기반 FastAPI 개발 서버입니다. 현재는 헬스체크와 실행 설정만 포함합니다.

## 로컬 실행

`ai-server` 디렉터리에서 아래 명령을 실행합니다.

### Windows PowerShell

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
python -m app.main
```

### macOS / Linux

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
cp .env.example .env
python -m app.main
```

서버가 실행되면 `http://127.0.0.1:8000/health`에서 `{"status":"ok"}` 응답을 확인할 수 있습니다. FastAPI 문서는 `http://127.0.0.1:8000/docs`에서 확인합니다.

`.env`는 로컬 설정용이며 Git에 포함하지 않습니다. 공유할 설정 항목은 `.env.example`에 추가하고 비밀값은 기록하지 않습니다.

## Docker 로컬 실행

저장소 루트에서 Docker Desktop의 Linux 컨테이너 엔진을 실행한 뒤 아래 명령을 사용합니다.

```bash
docker build -t e106-ai:local ./ai-server
docker run --detach --rm --name e106-ai-local -p 127.0.0.1:18000:8000 e106-ai:local
```

이미지는 Python 3.12와 기존 `requirements.txt`를 사용하며, 일반 사용자로 `python -m app.main`을 실행합니다. 기본값은 `APP_ENV=prod`, `HOST=0.0.0.0`, `PORT=8000`이므로 개발용 자동 재시작은 비활성화됩니다. 로컬 확인 포트 `18000`은 해당 PC에서만 접근할 수 있습니다.

PowerShell에서는 다음으로 HTTP 200과 `{"status":"ok"}` 응답을 확인합니다. 시작 직후 연결되지 않으면 로그를 확인한 뒤 다시 요청합니다.

```powershell
$response = Invoke-WebRequest -Uri http://127.0.0.1:18000/health
$response.StatusCode
$response.Content
```

로그 확인과 종료는 다음과 같습니다. `--rm`으로 실행했으므로 종료하면 컨테이너도 삭제됩니다.

```bash
docker logs e106-ai-local
docker stop --timeout 10 e106-ai-local
docker ps -a --filter name=e106-ai-local
```

빌드 컨텍스트에는 의존성 목록과 앱의 Python 소스만 포함합니다. `.env`, `.env.example`, 가상환경, 테스트, 캐시 파일은 이미지에 복사하지 않습니다. 현재 `/health` 확인에는 비밀값이나 GMS 호출이 필요하지 않습니다. 향후 필요한 비밀값은 이미지 빌드 인자가 아닌 실행 시 환경변수로 전달합니다.

인프라 연결 시 빌드 컨텍스트는 `ai-server/`, 컨테이너 포트는 `8000`, 헬스체크 경로는 `/health`를 사용합니다. 운영·개발 컨테이너는 내부망에서만 연결하며, 위 포트 공개 명령은 로컬 확인용입니다.

### 동일 이미지로 두 컨테이너 동시 실행

Dockerfile은 운영 색상이나 서비스 이름에 의존하지 않습니다. 운영 Blue/Green과 개발 `ai-dev`에 같은 이미지를 사용하고, 환경 차이는 실행 시 환경변수로 전달합니다. `APP_ENV=prod`와 `APP_ENV=dev`에서는 모두 자동 재시작이 비활성화됩니다. 아래는 실제 서버 배포나 트래픽 전환 없이 두 인스턴스를 로컬에서 확인하는 예시입니다.

```bash
docker run --detach --rm --name e106-ai-prod-local -e APP_ENV=prod -p 127.0.0.1:18000:8000 e106-ai:local
docker run --detach --rm --name e106-ai-dev-local -e APP_ENV=dev -p 127.0.0.1:18001:8000 e106-ai:local
docker inspect --format '{{.Name}} image={{.Image}} running={{.State.Running}}' e106-ai-prod-local e106-ai-dev-local
```

PowerShell에서 두 포트 모두 HTTP 200과 `{"status":"ok"}`를 반환하는지 확인합니다. 위 `inspect` 결과의 이미지 ID도 같아야 합니다.

```powershell
foreach ($port in 18000, 18001) {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/health"
    [pscustomobject]@{ Port = $port; StatusCode = $response.StatusCode; Body = $response.Content }
}
```

검증 후 두 컨테이너의 로그를 확인하고 종료합니다.

```bash
docker logs e106-ai-prod-local
docker logs e106-ai-dev-local
docker stop --timeout 10 e106-ai-prod-local e106-ai-dev-local
docker ps -a --filter name=e106-ai-
```

### 인프라 연결 확인 범위

2026-10-22에 확인한 원격 `develop`(`e59cbfb`)과 `master`(`bbaf777`)의 Compose·Jenkinsfile·배포 스크립트는 동일합니다. Compose에는 `ai`·`ai-dev`가 주석으로만 있고, Jenkins `DEPLOY`에는 AI가 없으며, `deploy-bluegreen.sh`는 `back`만 지원합니다. 운영 AI 색상별 서비스 이름과 내부 호출 경로·전환 방식은 인프라 담당의 후속 연결 대상입니다. 이 Dockerfile만으로 서버의 AI 배포나 블루·그린 전환이 완료되지는 않습니다.

Jira `S15P31E106-138`의 내부 포트 8000·`/health` 요구와 기존 앱의 Python 3.12 실행 설정을 기준으로 작성했습니다. `10-22/AI_요청사항.md` 원문을 확보하지 못해 해당 문서에만 있는 추가 실행 옵션·볼륨·자원·종료 시간 요구는 확인하지 못했습니다. 실제 서버 배포, 운영 색상 전환 및 Jenkins Credentials의 비밀값 전달은 로컬 검증 범위에 포함하지 않습니다.

### 로컬 검증 기록 (2026-10-22)

`e106-ai:local` 빌드에 성공했으며 Python 3.12.15 환경에서 기존 테스트 1개와 `pip check`가 통과했습니다. 이미지의 `/app`에는 `requirements.txt`와 앱 Python 소스 3개만 있고, `.env`·가상환경·캐시 파일 및 GMS 키 환경변수는 포함되지 않았습니다.

아래 두 컨테이너가 동시에 실행 중이며 이미지 ID가 모두 `sha256:e09ff9ca68fb6f0257a929385b8e0ab7347957e0642dadd0f188f6b0d35ec091`임을 확인했습니다.

| 로컬 컨테이너 | APP_ENV | 호스트 포트 | /health 결과 |
| --- | --- | --- | --- |
| e106-ai-prod-local | prod | 127.0.0.1:18000 | HTTP 200, `{"status":"ok"}` |
| e106-ai-dev-local | dev | 127.0.0.1:18001 | HTTP 200, `{"status":"ok"}` |

두 컨테이너 모두 일반 사용자로 `0.0.0.0:8000`에서 실행됐고, 종료 시 `Application shutdown complete` 로그를 확인했습니다. `--rm`에 의해 검증용 컨테이너는 모두 삭제됐으며 로컬 이미지는 남겨 두었습니다. 이 기록은 동일 이미지의 다중 인스턴스 검증이며 실제 서버 블루·그린 전환 검증은 포함하지 않습니다.

## 테스트

가상환경을 활성화한 상태에서 실행합니다.

```bash
python -m pytest
```
