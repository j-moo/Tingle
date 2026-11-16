"""Loopback Match 통합 fixture. 실제 인증/manifest/근거/품질 검사를 사용한다.

외부 GMS 응답만 MockTransport로 대체한다. 활성화 OFF/생성/timeout/검토 거절은
각각 별도 앱으로 구성하며 운영 서버/외부 API/실제 회원 데이터는 사용하지 않는다.
"""

import argparse
import json
from pathlib import Path

from fastapi import FastAPI
import httpx2 as httpx
import uvicorn

from app.match_explanation.gms import GmsClient, MODEL
from app.match_explanation.runtime import create_runtime_router
from app.middleware.request_id import RequestIdMiddleware, install_error_handlers
from app.security.service_auth import ServiceAuthenticator, ServiceAuthSettings


def build_app(public_key, mode_file):
    root = FastAPI()
    counters = {}

    @root.middleware("http")
    async def select_synthetic_mode(request, call_next):
        if request.url.path == "/internal/ai/explanations":
            mode = mode_file.read_text(encoding="utf-8").strip()
            if mode not in counters:
                raise ValueError("Unknown synthetic fixture mode")
            request.scope["path"] = "/" + mode + request.scope["path"]
        return await call_next(request)
    for mode in ("off", "generated", "timeout", "review-rejected", "unsafe"):
        counts = counters[mode] = {"generation": 0, "verification": 0}

        def handler(request, mode=mode, counts=counts):
            body = json.loads(request.content)
            data = json.loads(body["input"][1]["content"])
            assert set(data) == {"viewer", "evidence", "candidate" if "candidate" in data else "allowedPhrasings"}
            assert all(set(row) == {"id", "direction", "kind", "topic", "fact"} for row in data["evidence"])
            reviewing = "candidate" in data
            counts["verification" if reviewing else "generation"] += 1
            if mode == "timeout":
                raise httpx.ReadTimeout("synthetic-only timeout", request=request)
            if reviewing:
                result = {"supported": mode != "review-rejected", "directionCorrect": True,
                          "privacySafe": True, "evidenceIds": ["e1"]}
            else:
                result = {"text": "두 분 다 하루에 몇 번씩 연락하는 게 좋대요.", "evidenceIds": ["e1"]}
                if mode == "unsafe": result["text"] += " 두 분은 서로 좋아할 거예요."
            return httpx.Response(200, json={
                "status": "completed", "model": MODEL, "error": None,
                "output": [{"type": "message", "role": "assistant", "content": [
                    {"type": "output_text", "text": json.dumps(result, ensure_ascii=False)}]}],
            })

        app = FastAPI()
        install_error_handlers(app)
        app.add_middleware(RequestIdMiddleware)
        auth = ServiceAuthenticator(ServiceAuthSettings(
            environment="test", public_keys={"synthetic-key": public_key}))
        provider = None if mode == "off" else GmsClient("synthetic-only", transport=httpx.MockTransport(handler))
        app.include_router(create_runtime_router(auth, gms_client=provider))
        root.mount("/" + mode, app)

    @root.get("/health")
    def health():
        return {"status": "ok", "provider": "mock", "counters": counters}

    return root


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--descriptor", type=Path, help="Legacy caller compatibility; unused")
    parser.add_argument("--mode-file", type=Path, required=True)
    args = parser.parse_args()
    uvicorn.run(build_app(args.public_key, args.mode_file), host="127.0.0.1", port=args.port,
                log_level="error", access_log=False)
