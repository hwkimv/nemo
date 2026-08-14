#!/usr/bin/env python3
"""
네이버 지도 API 대역 스텁.

지도 캐시 효과를 측정하려면 "외부 API가 실제로 몇 번 불렸는가"를 세어야 한다.
실제 네이버 API를 쓰면 호출 수를 셀 수 없고, 쿼터·요금·응답 변동이 측정을 오염시킨다.
그래서 같은 자리에 이 스텁을 두고 요청 수를 직접 센다.

제공하는 것
  GET  /v1/search/local.json   Local Search 응답 (items 5건)
  GET  /map-reversegeocode/v2/gc  Reverse Geocode 응답 ("강남구 역삼동")
  GET  /__stats                호출 횟수 조회 {"local": n, "reverse": n, "total": n}
  POST /__reset                카운터 초기화

옵션
  --latency-ms N   외부 API의 네트워크 지연을 흉내낸다 (기본 0)
  --port N         (기본 9999)

실행
  python3 tools/performance/naver-stub/stub.py --port 9999 --latency-ms 50
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

counts = {"local": 0, "reverse": 0}
lock = threading.Lock()
LATENCY_MS = 0

LOCAL_ITEMS = [
    {
        "title": f"테스트 포토부스 {i}",
        "link": "",
        "category": "사진",
        "description": "",
        "telephone": "",
        "address": f"서울특별시 강남구 역삼동 {i}",
        "roadAddress": f"서울특별시 강남구 테헤란로 {i}",
        # 네이버 Local Search 좌표(경도·위도 x 10^7 정수 문자열)
        # 측정용 뷰포트(37.493~37.503, 127.035~127.045) 안에 들어가도록 잡았다.
        "mapx": str(1270380000 + i * 10000),
        "mapy": str(374950000 + i * 10000),
    }
    for i in range(1, 6)
]

REVERSE_BODY = {
    "status": {"code": 0, "name": "ok", "message": "done"},
    "results": [
        {
            "name": "legalcode",
            "region": {
                "area1": {"name": "서울특별시"},
                "area2": {"name": "강남구"},
                "area3": {"name": "역삼동"},
                "area4": {"name": ""},
            },
        }
    ],
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]

        if path == "/__stats":
            with lock:
                self._json({**counts, "total": counts["local"] + counts["reverse"]})
            return

        if LATENCY_MS:
            time.sleep(LATENCY_MS / 1000.0)

        if path.startswith("/v1/search/local"):
            with lock:
                counts["local"] += 1
            self._json({"lastBuildDate": "", "total": 5, "start": 1,
                        "display": 5, "items": LOCAL_ITEMS})
            return

        if path.startswith("/map-reversegeocode"):
            with lock:
                counts["reverse"] += 1
            self._json(REVERSE_BODY)
            return

        self._json({"error": "unknown path", "path": path}, status=404)

    def do_POST(self):
        if self.path == "/__reset":
            with lock:
                counts["local"] = 0
                counts["reverse"] = 0
            self._json({"reset": True})
            return
        self._json({"error": "unknown path"}, status=404)

    def log_message(self, *args):
        pass  # 측정 중 stdout을 더럽히지 않는다


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9999)
    parser.add_argument("--latency-ms", type=int, default=0)
    args = parser.parse_args()
    LATENCY_MS = args.latency_ms

    print(f"naver-stub listening on :{args.port} (latency {args.latency_ms}ms)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
