#!/usr/bin/env python3
"""
Sentry 수집 서버 대역 스텁.

"Sentry를 붙였다"고 말하려면 **무엇이 실제로 전송되는지** 봐야 한다.
특히 Authorization 헤더나 개인정보가 섞여 나가는지는 눈으로 확인해야지,
설정만 보고 안전하다고 말할 수 없다.

Sentry DSN은 결국 HTTP endpoint이므로, 그 자리에 이 스텁을 두면
SDK가 보내는 envelope 를 그대로 받아 볼 수 있다. Sentry 계정이 필요 없다.

DSN 형식
    http://<public_key>@<host>:<port>/<project_id>
    예) http://stub@localhost:9998/1

제공하는 것
    POST /api/<project_id>/envelope/   Sentry SDK가 이벤트를 보내는 곳
    GET  /__events                     받은 이벤트 목록(JSON)
    GET  /__last                       마지막 이벤트 하나
    POST /__reset                      비우기

실행
    python3 tools/observability/sentry-stub/stub.py --port 9998
"""
import argparse
import gzip
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

events = []
lock = threading.Lock()


def parse_envelope(raw: bytes):
    """Sentry envelope 은 줄 단위 JSON이다: header \n item_header \n item_payload ..."""
    try:
        text = raw.decode("utf-8", errors="replace")
    except Exception:
        return []
    lines = [ln for ln in text.split("\n") if ln.strip()]
    parsed = []
    # 0번째는 envelope header, 이후 (item header, payload) 쌍
    i = 1
    while i + 1 <= len(lines) - 1:
        try:
            item_header = json.loads(lines[i])
            payload = json.loads(lines[i + 1])
            parsed.append({"type": item_header.get("type"), "payload": payload})
        except json.JSONDecodeError:
            pass
        i += 2
    return parsed


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path == "/__reset":
            with lock:
                events.clear()
            self._json({"reset": True})
            return

        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""

        if self.headers.get("Content-Encoding") == "gzip":
            try:
                raw = gzip.decompress(raw)
            except Exception:
                pass

        for item in parse_envelope(raw):
            if item["type"] == "event":
                with lock:
                    events.append(item["payload"])

        self._json({"id": "stub-event-id"})

    def do_GET(self):
        if self.path == "/__events":
            with lock:
                self._json(events)
            return
        if self.path == "/__last":
            with lock:
                self._json(events[-1] if events else {})
            return
        self._json({"error": "unknown path", "path": self.path}, status=404)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9998)
    args = parser.parse_args()
    print(f"sentry-stub listening on :{args.port}", flush=True)
    print(f"  DSN: http://stub@localhost:{args.port}/1", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
