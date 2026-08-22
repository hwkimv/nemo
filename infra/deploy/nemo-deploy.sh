#!/usr/bin/env bash
#
# NEMO 배포 스크립트 (EC2 호스트에서 실행)
#
# 이 스크립트가 존재하는 이유
# ---------------------------
# 예전 배포는 SSH 로 들어가 docker pull / docker rm / docker run 을 손으로 치는 것이었다.
# 그러면 두 가지를 아무도 하지 않는다.
#
#   1) 새 컨테이너가 실제로 요청을 받을 수 있는 상태가 됐는지 확인하기
#   2) 안 됐을 때 이전 버전으로 되돌리기
#
# 사람은 배포 직후 로그를 잠깐 보고 넘어간다. 기동에 21초가 걸리는 앱이라
# "떴네" 하고 창을 닫은 뒤에 죽는 경우를 잡지 못한다.
# 이 스크립트는 그 두 가지를 강제한다.
#
# 롤백 정책 — 기존 컨테이너를 내린 뒤의 실패는 전부 롤백한다
# -----------------------------------------------------------
# 이 경계가 핵심이다.
#
#   docker pull 실패        → 기존 컨테이너를 아직 건드리지 않았다. 그대로 두고 종료(exit 3)
#   docker run 실패         → 이미 내렸다. 롤백한다
#   컨테이너 조기 종료      → 이미 내렸다. 롤백한다
#   readiness 계약 불충족   → 이미 내렸다. 롤백한다 (404 = /readyz 가 없는 이미지)
#   readiness timeout       → 이미 내렸다. 롤백한다
#
# 예전 구현은 `docker run` 실패를 롤백하지 못했다. set -e 때문에 스크립트가
# 그 자리에서 죽어 아래 롤백 구문에 도달조차 하지 않았고, 종료 코드는 1 이었다.
# 계약상 1 은 "이전 버전 복구 성공"이라 호출자에게 거짓말까지 했다.
# 지금은 실패 지점이 어디든 rollback_to_previous() 하나로 모인다.
#
# 하지 않는 것
# ------------
# · zero-downtime 이 아니다. 포트 8080 을 직접 쓰는 단일 인스턴스라
#   기존 컨테이너를 내리고 새 것을 올리는 사이에 중단이 있다.
#   앞단에 Nginx/ALB 를 두면 없앨 수 있지만, 그건 지금 필요하지 않은 인프라다.
# · 비밀값을 받지 않는다. 런타임 secret 은 계속 호스트의 ENV_FILE 에 있다.
#   이 스크립트에 넘기는 것은 이미지 태그 하나뿐이다.
#
# 사용법
#   ./nemo-deploy.sh <image-tag>        # 예: ./nemo-deploy.sh a4dfb7c...
#   ./nemo-deploy.sh --redeploy-last-good
#
# 종료 코드
#   0  새 이미지 배포 성공 (readiness 확인됨)
#   1  새 이미지 실패 → 이전 이미지로 롤백 성공 (서비스는 살아 있음)
#   2  롤백까지 실패 → 사람이 봐야 함
#   3  사용법 오류 / 사전 조건 불충족 / 다른 배포가 진행 중

set -euo pipefail

# ─────────────────────────── 설정 ───────────────────────────

IMAGE_REPO="${NEMO_IMAGE_REPO:-ghcr.io/hwkimv/nemo/backend}"
CONTAINER="${NEMO_CONTAINER:-nemo-backend}"
ENV_FILE="${NEMO_ENV_FILE:-/home/ec2-user/nemo.env}"
STATE_FILE="${NEMO_STATE_FILE:-/home/ec2-user/.nemo-last-good}"
LOG_DIR="${NEMO_LOG_DIR:-/home/ec2-user/deploy-logs}"
LOCK_FILE="${NEMO_LOCK_FILE:-/tmp/nemo-deploy.lock}"

APP_PORT="${NEMO_APP_PORT:-8080}"
MEMORY="${NEMO_MEMORY:-700m}"

# readiness 를 기다리는 상한.
# 실측 기동 시간이 21.1초다(CS 12). 넉넉히 잡되 무한정 기다리지는 않는다.
# 너무 짧으면 정상 배포를 실패로 판정해 불필요한 롤백을 한다.
READINESS_TIMEOUT_SEC="${NEMO_READINESS_TIMEOUT_SEC:-120}"
READINESS_INTERVAL_SEC="${NEMO_READINESS_INTERVAL_SEC:-2}"

# 404 를 몇 번 연속으로 보면 "배포 계약 불충족"으로 판정할 것인가.
#
# 기동 중에 404 가 나올 수 있다면 이 판정은 정상 배포를 죽인다. 그래서 실측했다 —
# 같은 이미지를 두 번 기동시키며 /readyz 를 200ms 간격으로 폴링한 결과:
#
#   000(연결 거부) ×101  →  503 ×1  →  200
#
# **404 는 한 번도 나오지 않았다.** Spring Boot 는 포트를 열기 전에 매핑을 등록하므로
# "아직 기동 중이라 404"인 구간이 없다. 즉 404 는 /readyz 가 아예 없는 이미지라는 뜻이다.
# 그래도 한 번의 이상값으로 배포를 죽이지 않도록 연속 3회를 요구한다(약 6초).
READINESS_CONTRACT_STRIKES="${NEMO_READINESS_CONTRACT_STRIKES:-3}"

# 프로브 경로.
#
# /actuator/health/readiness 는 관리 포트(9090)에 있고 127.0.0.1 에만 바인딩돼 있다
# (CS 06 설계 유지). 컨테이너 밖에서는 부를 수 없으므로 배포 판정에 쓸 수 없다.
# 그래서 프로브 둘만 additional-path 로 서비스 포트에 얹어 두었다.
# /actuator/prometheus 는 그대로 관리 포트에만 있다.
READINESS_URL="http://127.0.0.1:${APP_PORT}/readyz"
LIVENESS_URL="http://127.0.0.1:${APP_PORT}/livez"

# ─────────────────────────── 유틸 ───────────────────────────

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

require() {
    command -v "$1" >/dev/null 2>&1 || { fail "$1 이 없습니다"; exit 3; }
}

now_ms() { date +%s%3N; }

# 타임라인. "26초 만에 복구"처럼 한 조각만 말하지 않으려고 각 구간을 따로 잡는다.
T_START=0        # 배포 시작
T_DOWN=0         # 기존 컨테이너를 내린 시각 = 중단 시작
T_DETECT=0       # 실패를 판정한 시각
T_RECOVER=0      # 서비스가 다시 readiness UP 이 된 시각 = 중단 끝

# 호스트에서 서비스 포트의 프로브를 두드린다.
# 이미지 안에 curl 이 있는지에 의존하지 않는다.
#
# -f 를 쓰지 않는다. -f 는 404 와 503 을 똑같이 exit 22 로 만들어 구분할 수 없다.
# 상태 코드가 필요하다. 연결 자체가 안 되면 curl 이 000 을 준다.
probe_status() {
    curl -s -o /dev/null -w '%{http_code}' -m 3 "$1" 2>/dev/null || echo "000"
}

probe_body() {
    curl -s -m 3 "$1" 2>/dev/null || true
}

container_running() {
    docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"
}

# readiness 가 UP 이 될 때까지 기다린다.
#   0 = 준비됨
#   1 = 준비되지 않음 (timeout / 조기 종료 / 계약 불충족). 이유는 로그에 남긴다.
wait_for_readiness() {
    local deadline=$(( SECONDS + READINESS_TIMEOUT_SEC ))
    local status="" strikes=0

    while (( SECONDS < deadline )); do
        # 컨테이너가 죽어 버렸으면 더 기다릴 이유가 없다.
        if ! container_running; then
            fail "컨테이너가 실행 중이 아닙니다 (조기 종료)"
            return 1
        fi

        status="$(probe_status "$READINESS_URL")"

        case "$status" in
            200)
                log "readiness UP (status=200)"
                return 0
                ;;
            404)
                # /readyz 가 없는 이미지다. 기다려도 생기지 않는다.
                strikes=$(( strikes + 1 ))
                if (( strikes >= READINESS_CONTRACT_STRIKES )); then
                    fail "readiness 경로가 404 입니다 (연속 ${strikes}회) — 이 이미지는 배포 계약을 만족하지 않습니다"
                    fail "기다려도 생기지 않으므로 상한(${READINESS_TIMEOUT_SEC}s)을 채우지 않고 실패로 판정합니다"
                    return 1
                fi
                ;;
            *)
                # 000(연결 거부) = 아직 기동 중, 503 = 떴지만 의존성 미준비. 둘 다 기다린다.
                strikes=0
                ;;
        esac
        sleep "$READINESS_INTERVAL_SEC"
    done

    fail "readiness 가 ${READINESS_TIMEOUT_SEC}s 안에 UP 이 되지 않았습니다 (마지막 status=${status:-없음})"
    fail "마지막 readiness 응답: $(probe_body "$READINESS_URL" || echo '<응답 없음>')"
    fail "liveness 응답      : $(probe_body "$LIVENESS_URL" || echo '<응답 없음>')"
    return 1
}

# 실패한 컨테이너의 로그를 남긴다. 컨테이너를 지우면 로그도 사라진다.
# 롤백이 성공하면 왜 실패했는지 볼 방법이 이것뿐이다.
save_logs() {
    local tag="$1"
    mkdir -p "$LOG_DIR"
    local out
    out="${LOG_DIR}/failed-${tag}-$(date +%Y%m%d-%H%M%S).log"
    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
        docker logs "$CONTAINER" > "$out" 2>&1 || true
        log "실패한 컨테이너 로그: $out"
    else
        # docker run 자체가 실패하면 남길 컨테이너가 없다.
        log "남길 컨테이너가 없습니다 (docker run 이 컨테이너를 만들지 못함)"
    fi
}

# 되돌릴 곳이 없을 때 실패한 컨테이너를 치운다.
#
# 예전에는 "컨테이너를 정지된 채로 둡니다"라고 적어 놓고 실제로는 아무것도 하지 않았다.
# readiness 만 DOWN 이고 프로세스는 살아 있는 이미지라면 그대로 계속 돌았다.
# 문구와 동작이 달랐다.
cleanup_failed_container() {
    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
        docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
        log "실패한 컨테이너를 제거했습니다. 지금 서비스는 내려가 있습니다."
    fi
}

# last-good 을 원자적으로 갱신한다.
# echo > 는 중간에 죽으면 빈 파일을 남길 수 있고, 그러면 롤백 후보가 사라진다.
write_state() {
    local tmp="${STATE_FILE}.tmp.$$"
    printf '%s\n' "$1" > "$tmp"
    mv -f "$tmp" "$STATE_FILE"
}

# 컨테이너를 지정한 이미지로 띄운다.
# 실패하면 non-zero 를 돌려준다. 호출자는 반드시 조건문 안에서 부를 것.
start_container() {
    local image="$1"

    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

    # --restart unless-stopped: 프로세스가 죽으면 Docker 가 다시 띄운다.
    #
    # ⚠️ Dockerfile 의 HEALTHCHECK 가 unhealthy 를 보고해도 이 정책은 재시작하지 않는다.
    #    Docker 의 restart 정책은 '프로세스 종료'에만 반응한다. 실측으로 확인했다 —
    #    unhealthy 로 넘어간 뒤에도 RestartCount 는 0 이었다.
    #    HEALTHCHECK 는 상태 관측과 이 스크립트의 배포 판정에만 쓴다.
    docker run -d \
        --name "$CONTAINER" \
        --restart unless-stopped \
        --env-file "$ENV_FILE" \
        -p "${APP_PORT}:8080" \
        -m "$MEMORY" \
        ${NEMO_EXTRA_RUN_ARGS:-} \
        "$image" >/dev/null
}

# ─────────────────────────── 롤백 (단일 경로) ───────────────────────────
#
# 기존 컨테이너를 내린 뒤의 모든 실패가 여기로 온다.
# 실패 종류마다 다르게 처리하지 않는 것이 요점이다 — 새 실패 경로가 생겨도
# 여기로 보내기만 하면 정책이 자동으로 같아진다.
rollback_to_previous() {
    local reason="$1"

    T_DETECT=$(now_ms)
    fail "배포 실패(${reason}). 롤백합니다."
    save_logs "$TAG"

    if [[ -z "$PREVIOUS_IMAGE" ]]; then
        fail "되돌릴 이미지가 없습니다 (첫 배포이거나 last-good 기록이 없습니다)"
        cleanup_failed_container
        report_timeline "롤백 대상 없음"
        exit 2
    fi

    if [[ "$PREVIOUS_IMAGE" == "$NEW_IMAGE" ]]; then
        fail "롤백 후보가 방금 실패한 이미지와 같습니다. 되돌릴 곳이 없습니다."
        cleanup_failed_container
        report_timeline "롤백 대상 없음"
        exit 2
    fi

    log "롤백: $PREVIOUS_IMAGE"
    if ! start_container "$PREVIOUS_IMAGE"; then
        fail "롤백 이미지를 기동시키지 못했습니다. 사람이 봐야 합니다."
        report_timeline "롤백 기동 실패"
        exit 2
    fi

    if wait_for_readiness; then
        T_RECOVER=$(now_ms)
        log "롤백 성공. 서비스는 이전 버전으로 살아 있습니다."
        log "배포는 실패로 처리합니다."
        report_timeline "롤백 성공"
        exit 1
    fi

    fail "롤백한 이미지도 readiness 에 실패했습니다. 사람이 봐야 합니다."
    report_timeline "롤백 후 readiness 실패"
    exit 2
}

# 각 구간을 따로 보고한다.
# "26초 만에 복구"처럼 한 조각만 말하면 실제 중단 시간이 가려진다.
report_timeline() {
    local outcome="$1"
    local total=$(( $(now_ms) - T_START ))

    echo ""
    echo "──────── 타임라인 (${outcome}) ────────"
    printf '  A. 실패 감지까지            : %s\n' \
        "$( (( T_DETECT > 0 )) && echo "$(( T_DETECT - T_START ))ms" || echo '—' )"
    printf '  B. 롤백 시작 → readiness UP : %s\n' \
        "$( (( T_RECOVER > 0 && T_DETECT > 0 )) && echo "$(( T_RECOVER - T_DETECT ))ms" || echo '—' )"
    printf '  C. 실제 서비스 중단          : %s\n' \
        "$( (( T_RECOVER > 0 && T_DOWN > 0 )) && echo "$(( T_RECOVER - T_DOWN ))ms" || echo '복구되지 않음' )"
    printf '  D. 배포 시도 전체            : %sms\n' "$total"
    echo "────────────────────────────────────"
}

# ─────────────────────────── 사전 확인 ───────────────────────────

require docker
require curl
require flock

# 동시 실행 방지.
#
# 배포 두 개가 겹치면 서로의 컨테이너를 제거하고, readiness 대상이 뒤섞이고,
# last-good 을 덮어써 롤백 후보가 오염된다. 큐를 만들 문제는 아니고
# "한 번에 하나"면 충분하다.
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    fail "다른 배포가 진행 중입니다. 끝난 뒤 다시 실행하십시오. (lock: $LOCK_FILE)"
    exit 3
fi

if [[ ! -f "$ENV_FILE" ]]; then
    fail "환경변수 파일이 없습니다: $ENV_FILE"
    exit 3
fi

REDEPLOY_LAST_GOOD=false
if [[ "${1:-}" == "--redeploy-last-good" ]]; then
    REDEPLOY_LAST_GOOD=true
elif [[ "${1:-}" == "--rollback" ]]; then
    # 예전 이름. 하는 일과 이름이 달라서 바꿨다. 아래 주석 참고.
    fail "--rollback 은 --redeploy-last-good 으로 바뀌었습니다."
    fail "이 스크립트는 last-good 이미지 '하나'만 보관하므로, 배포가 성공하면"
    fail "last-good 이 곧 지금 돌고 있는 버전입니다. 되돌릴 이전 버전이 없습니다."
    fail "직전 버전으로 내리려면 그 태그를 직접 지정하십시오: $0 <이전-태그>"
    exit 3
elif [[ $# -ne 1 || -z "${1:-}" ]]; then
    fail "사용법: $0 <image-tag> | $0 --redeploy-last-good"
    exit 3
fi

# 마지막으로 readiness 까지 확인된 이미지.
# 없으면 지금 돌고 있는 컨테이너의 이미지를 쓴다(스크립트 도입 전 배포분).
#
# ⚠️ 이 값은 "직전 버전"이 아니라 "마지막으로 성공한 버전"이다.
#    배포가 성공하면 곧 지금 돌고 있는 버전이 된다.
#    자동 롤백이 동작하는 이유는 **새 이미지가 성공하기 전까지 이 값을 갱신하지 않기** 때문이다.
PREVIOUS_IMAGE=""
if [[ -s "$STATE_FILE" ]]; then
    PREVIOUS_IMAGE="$(cat "$STATE_FILE")"
elif docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    PREVIOUS_IMAGE="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
    log "last-good 기록이 없어 현재 컨테이너 이미지를 롤백 후보로 씁니다: $PREVIOUS_IMAGE"
fi

# ─────────────────────────── --redeploy-last-good ───────────────────────────

if [[ "$REDEPLOY_LAST_GOOD" == true ]]; then
    if [[ -z "$PREVIOUS_IMAGE" ]]; then
        fail "last-good 기록이 없습니다"
        exit 3
    fi
    log "last-good 재배포: $PREVIOUS_IMAGE"
    T_START=$(now_ms)
    T_DOWN=$(now_ms)
    if ! start_container "$PREVIOUS_IMAGE"; then
        fail "last-good 이미지를 기동시키지 못했습니다."
        exit 2
    fi
    if wait_for_readiness; then
        T_RECOVER=$(now_ms)
        log "재배포 완료"
        report_timeline "last-good 재배포"
        exit 0
    fi
    fail "last-good 이미지도 readiness 에 실패했습니다"
    exit 2
fi

# ─────────────────────────── 배포 ───────────────────────────

TAG="$1"
NEW_IMAGE="${IMAGE_REPO}:${TAG}"

T_START=$(now_ms)
log "배포 시작"
log "  새 이미지 : $NEW_IMAGE"
log "  롤백 후보 : ${PREVIOUS_IMAGE:-<없음>}"

# pull 을 먼저 한다. 여기서 실패하면 기존 컨테이너를 건드리지 않았으므로
# 서비스가 그대로 살아 있다. 내리고 나서 pull 하면 중단이 길어진다.
#
# 그래서 이 실패만은 롤백하지 않는다. 되돌릴 것이 없다 — 아무것도 안 내렸다.
if ! docker pull "$NEW_IMAGE"; then
    fail "이미지를 받지 못했습니다. 기존 컨테이너를 유지합니다."
    exit 3
fi

# ── 여기서부터 서비스가 내려간다. 이 아래의 모든 실패는 롤백 대상이다. ──
T_DOWN=$(now_ms)

if ! start_container "$NEW_IMAGE"; then
    # 잘못된 run 옵션, 포트 바인딩 실패, env-file 문제 등.
    # 예전에는 set -e 가 여기서 스크립트를 죽여 롤백에 도달하지 못했다.
    rollback_to_previous "docker run 실패"
fi

if ! wait_for_readiness; then
    rollback_to_previous "readiness 실패"
fi

# ─────────────────────────── 성공 ───────────────────────────

T_RECOVER=$(now_ms)
write_state "$NEW_IMAGE"
log "배포 성공. last-good 갱신: $NEW_IMAGE"
log "liveness : $(probe_body "$LIVENESS_URL" || echo '<응답 없음>')"
log "readiness: $(probe_body "$READINESS_URL" || echo '<응답 없음>')"
report_timeline "배포 성공"
exit 0
