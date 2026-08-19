#!/usr/bin/env python3
"""
네이버 지역 검색이 실제로 무엇을 돌려주는지 재는 도구.

<왜 필요한가>
PhotoboothService.getPhotoboothsInViewport()는 키워드 9개 x 최대 4페이지 = 최대 36회를 부른다.
그런데 "페이지 2~4가 뷰포트 안 결과를 얼마나 더 주는가"를 모르면 몇 페이지가 적당한지
근거를 갖고 자를 수 없다. 스텁은 항상 같은 응답을 주므로 이 질문에 답할 수 없다.

그래서 실제 API를 서비스와 같은 방식으로 호출해 페이지별 기여를 센다.
앱을 거치지 않으므로 코드를 건드리지 않고, 무엇을 세는지도 분명하다.

<사용법>
  NAVER_LOCAL_CLIENT_ID=... NAVER_LOCAL_CLIENT_SECRET=... \
  python3 tools/performance/naver-probe/probe.py --region "강남구 역삼동" \
      --ne 37.5030,127.0450 --sw 37.4930,127.0350

호출 수는 키워드 x 페이지로 서비스와 동일하다(기본 9 x 4 = 36회).
"""
import argparse, json, os, sys, time, urllib.parse, urllib.request

# PhotoboothService.KEYWORDS 와 같은 목록
KEYWORDS = ["포토부스", "인생네컷", "하루필름", "포토이즘", "포토시그널",
            "포토그레이", "돈룩업", "엑시트", "포토랩"]
PAGE_SIZE = 5
ENDPOINT = "https://naverapihub.apigw.ntruss.com/search/v1/local"   # NAVER API HUB 이관 후 경로


def search(query, start, cid, secret):
    url = ENDPOINT + "?" + urllib.parse.urlencode(
        {"query": query, "display": PAGE_SIZE, "start": start, "sort": "random"})
    req = urllib.request.Request(url, headers={
        "X-NCP-APIGW-API-KEY-ID": cid, "X-NCP-APIGW-API-KEY": secret})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r).get("items", [])


def to_wgs84(v):
    """Local Search의 mapx/mapy는 경도·위도 x 10^7 정수 문자열이다."""
    return int(v) / 1e7


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--region", required=True, help='예: "강남구 역삼동"')
    ap.add_argument("--ne", required=True, help="북동 위도,경도")
    ap.add_argument("--sw", required=True, help="남서 위도,경도")
    ap.add_argument("--max-pages", type=int, default=4)
    ap.add_argument("--interval-ms", type=int, default=200, help="서비스의 레이트 리미터와 동일")
    args = ap.parse_args()

    cid = os.environ.get("NAVER_LOCAL_CLIENT_ID")
    secret = os.environ.get("NAVER_LOCAL_CLIENT_SECRET")
    if not cid or not secret:
        sys.exit("NAVER_LOCAL_CLIENT_ID / NAVER_LOCAL_CLIENT_SECRET 환경변수가 필요합니다.")

    ne_lat, ne_lng = map(float, args.ne.split(","))
    sw_lat, sw_lng = map(float, args.sw.split(","))

    def in_viewport(item):
        try:
            lng, lat = to_wgs84(item["mapx"]), to_wgs84(item["mapy"])
        except (KeyError, ValueError):
            return False
        return sw_lat <= lat <= ne_lat and sw_lng <= lng <= ne_lng

    calls = 0
    per_page_new = {p: 0 for p in range(1, args.max_pages + 1)}
    per_page_new_in_vp = {p: 0 for p in range(1, args.max_pages + 1)}
    seen_all, seen_vp = set(), set()
    rows = []

    for kw_base in KEYWORDS:
        kw = f"{args.region} {kw_base}"
        kw_total = kw_pages = kw_in_vp = 0
        for page in range(1, args.max_pages + 1):
            start = 1 + (page - 1) * PAGE_SIZE
            if calls:
                time.sleep(args.interval_ms / 1000.0)
            items = search(kw, start, cid, secret)
            calls += 1
            kw_pages = page
            if not items:
                break
            kw_total += len(items)
            for it in items:
                key = (it.get("title", ""), it.get("address", ""))
                if key not in seen_all:
                    seen_all.add(key)
                    per_page_new[page] += 1
                if in_viewport(it):
                    kw_in_vp += 1
                    if key not in seen_vp:
                        seen_vp.add(key)
                        per_page_new_in_vp[page] += 1
            if len(items) < PAGE_SIZE:
                break   # 마지막 페이지 (서비스와 동일한 종료 조건)
        rows.append((kw_base, kw_pages, kw_total, kw_in_vp))

    print(f"\n지역: {args.region}   뷰포트: ({sw_lat},{sw_lng}) ~ ({ne_lat},{ne_lng})")
    print(f"외부 호출 {calls}회\n")
    print(f"{'키워드':12} {'실제페이지':>8} {'수집':>5} {'뷰포트내':>7}")
    print("─" * 40)
    for kw, pages, total, in_vp in rows:
        print(f"{kw:12} {pages:>8} {total:>5} {in_vp:>7}")

    print(f"\n{'페이지':>6} {'신규(전체)':>10} {'신규(뷰포트내)':>14}")
    print("─" * 34)
    for p in range(1, args.max_pages + 1):
        print(f"{p:>6} {per_page_new[p]:>10} {per_page_new_in_vp[p]:>14}")

    print(f"\n고유 장소 {len(seen_all)}곳, 그중 뷰포트 안 {len(seen_vp)}곳")
    later = sum(per_page_new_in_vp[p] for p in range(2, args.max_pages + 1))
    print(f"페이지 2~{args.max_pages}가 추가로 준 뷰포트 내 신규 장소: {later}곳")
    if len(seen_vp):
        print(f"  → 전체 뷰포트 결과의 {later/len(seen_vp)*100:.1f}%")


if __name__ == "__main__":
    main()
