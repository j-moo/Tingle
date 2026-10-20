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

## 테스트

가상환경을 활성화한 상태에서 실행합니다.

```bash
python -m pytest
```
