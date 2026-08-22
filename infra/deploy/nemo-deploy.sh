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
# 하지 않는 것
# ------------
# · zero-downtime 이 아니다. 포트 8080 을 직접 쓰는 단일 인스턴스라
#   기존 컨테이너를 내리고 새 것을 올리는 사이에 중단이 있다.
#   앞단에 Nginx/ALB 를 두면 없앨 수 있지만, 그건 지금 필요하지 않은 인프라다.
# · 비밀값을 받지 않는다. 런타임 secret 은 계속 호스트의 ENV_FILE 에 있다.
#   이 스크립트에 넘기는 것은 이미지 태그 하나뿐이다.
#
# 사용법
#   ./nemo-deploy.sh <image-tag>          # 예: ./nemo-deploy.sh a4dfb7c...
#   ./nemo-deploy.sh --rollback           # 마지막 성공 이미지로 되돌리기만
#
# 종료 코드
#   0  새 이미지 배포 성공 (readiness 확인됨)
#   1  새 이미지 실패 → 이전 이미지로 롤백 성공 (서비스는 살아 있음)
#   2  롤백까지 실패 → 사람이 봐야 함
#   3  사용법 오류 / 사전 조건 불충족

set -euo pipefail

# ─────────────────────────── 설정 ───────────────────────────

IMAGE_REPO="${NEMO_IMAGE_REPO:-ghcr.io/hwkimv/nemo/backend}"
CONTAINER="${NEMO_CONTAINER:-nemo-backend}"
ENV_FILE="${NEMO_ENV_FILE:-/home/ec2-user/nemo.env}"
STATE_FILE="${NEMO_STATE_FILE:-/home/ec2-user/.nemo-last-good}"
LOG_DIR="${NEMO_LOG_DIR:-/home/ec2-user/deploy-logs}"

APP_PORT="${NEMO_APP_PORT:-8080}"
MEMORY="${NEMO_MEMORY:-700m}"

# readiness 를 기다리는 상한.
# 실측 기동 시간이 21.1초다(CS 12). 넉넉히 잡되 무한정 기다리지는 않는다.
# 너무 짧으면 정상 배포를 실패로 판정해 불필요한 롤백을 한다.
READINESS_TIMEOUT_SEC="${NEMO_READINESS_TIMEOUT_SEC:-120}"
READINESS_INTERVAL_SEC="${NEMO_READINESS_INTERVAL_SEC:-2}"

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

# 호스트에서 서비스 포트의 프로브를 두드린다.
# 이미지 안에 curl 이 있는지에 의존하지 않는다.
probe() {
    curl -sf -m 3 "$1" 2>/dev/null
}

# readiness 가 UP 이 될 때까지 기다린다.
# 반환 0 = 준비됨, 1 = 상한까지 못 됨
wait_for_readiness() {
    local deadline=$(( SECONDS + READINESS_TIMEOUT_SEC ))
    local body=""

    while (( SECONDS < deadline )); do
        # 컨테이너가 죽어 버렸으면 더 기다릴 이유가 없다. 즉시 실패로 본다.
        if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
            fail "컨테이너가 실행 중이 아닙니다 (조기 종료)"
            return 1
        fi

        if body="$(probe "$READINESS_URL")"; then
            if [[ "$body" == *'"status":"UP"'* ]]; then
                log "readiness UP (${SECONDS}s 경과 시점)"
                return 0
            fi
        fi
        sleep "$READINESS_INTERVAL_SEC"
    done

    fail "readiness 가 ${READINESS_TIMEOUT_SEC}s 안에 UP 이 되지 않았습니다"
    # 위 probe 는 -f 라 503 을 실패로 본다. 그래서 body 가 비어 있다.
    # 왜 DOWN 인지 보려면 본문이 필요하므로 -f 없이 한 번 더 부른다.
    fail "마지막 readiness 응답: $(curl -s -m 3 "$READINESS_URL" 2>/dev/null || echo '<응답 없음>')"
    fail "liveness 응답      : $(curl -s -m 3 "$LIVENESS_URL" 2>/dev/null || echo '<응답 없음>')"
    return 1
}

# 실패한 컨테이너의 로그를 남긴다. 컨테이너를 지우면 로그도 사라진다.
# 롤백이 성공하면 왜 실패했는지 볼 방법이 이것뿐이다.
save_logs() {
    local tag="$1"
    mkdir -p "$LOG_DIR"
    local out="${LOG_DIR}/failed-${tag}-$(date +%Y%m%d-%H%M%S).log"
    docker logs "$CONTAINER" > "$out" 2>&1 || true
    log "실패한 컨테이너 로그: $out"
}

# 컨테이너를 지정한 이미지로 띄운다.
start_container() {
    local image="$1"

    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

    # --restart unless-stopped: 프로세스가 죽으면 Docker 가 다시 띄운다.
    #
    # ⚠️ 아래 HEALTHCHECK 가 unhealthy 를 보고해도 이 정책은 재시작하지 않는다.
    #    Docker 의 restart 정책은 '프로세스 종료'에만 반응한다.
    #    HEALTHCHECK 는 상태 관측과 이 스크립트의 배포 판정에만 쓴다.
    docker run -d \
        --name "$CONTAINER" \
        --restart unless-stopped \
        --env-file "$ENV_FILE" \
        -p "${APP_PORT}:8080" \
        -m "$MEMORY" \
        "$image" >/dev/null
}

# ─────────────────────────── 사전 확인 ───────────────────────────

require docker
require curl

if [[ ! -f "$ENV_FILE" ]]; then
    fail "환경변수 파일이 없습니다: $ENV_FILE"
    exit 3
fi

ROLLBACK_ONLY=false
if [[ "${1:-}" == "--rollback" ]]; then
    ROLLBACK_ONLY=true
elif [[ $# -ne 1 || -z "${1:-}" ]]; then
    fail "사용법: $0 <image-tag> | $0 --rollback"
    exit 3
fi

# 마지막으로 readiness 까지 확인된 이미지.
# 없으면 지금 돌고 있는 컨테이너의 이미지를 쓴다(스크립트 도입 전 배포분).
PREVIOUS_IMAGE=""
if [[ -f "$STATE_FILE" ]]; then
    PREVIOUS_IMAGE="$(cat "$STATE_FILE")"
elif docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    PREVIOUS_IMAGE="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
    log "last-good 기록이 없어 현재 컨테이너 이미지를 롤백 후보로 씁니다: $PREVIOUS_IMAGE"
fi

# ─────────────────────────── --rollback ───────────────────────────

if [[ "$ROLLBACK_ONLY" == true ]]; then
    if [[ -z "$PREVIOUS_IMAGE" ]]; then
        fail "되돌릴 이미지가 없습니다"
        exit 3
    fi
    log "수동 롤백: $PREVIOUS_IMAGE"
    start_container "$PREVIOUS_IMAGE"
    if wait_for_readiness; then
        log "롤백 완료"
        exit 0
    fi
    fail "롤백한 이미지도 readiness 에 실패했습니다"
    exit 2
fi

# ─────────────────────────── 배포 ───────────────────────────

TAG="$1"
NEW_IMAGE="${IMAGE_REPO}:${TAG}"

log "배포 시작"
log "  새 이미지 : $NEW_IMAGE"
log "  롤백 후보 : ${PREVIOUS_IMAGE:-<없음>}"

# pull 을 먼저 한다. 여기서 실패하면 기존 컨테이너를 건드리지 않았으므로
# 서비스가 그대로 살아 있다. 내리고 나서 pull 하면 중단이 길어진다.
if ! docker pull "$NEW_IMAGE"; then
    fail "이미지를 받지 못했습니다. 기존 컨테이너를 유지합니다."
    exit 3
fi

DEPLOY_STARTED_AT=$SECONDS
start_container "$NEW_IMAGE"

if wait_for_readiness; then
    ELAPSED=$(( SECONDS - DEPLOY_STARTED_AT ))
    echo "$NEW_IMAGE" > "$STATE_FILE"
    log "배포 성공 (${ELAPSED}s). last-good 갱신: $NEW_IMAGE"

    # liveness 도 함께 남긴다. 둘이 갈리는 순간을 보려면 양쪽을 기록해야 한다.
    log "liveness : $(probe "$LIVENESS_URL" || echo '<응답 없음>')"
    log "readiness: $(probe "$READINESS_URL" || echo '<응답 없음>')"
    exit 0
fi

# ─────────────────────────── 롤백 ───────────────────────────

fail "새 이미지가 readiness 에 실패했습니다. 롤백합니다."
save_logs "$TAG"

if [[ -z "$PREVIOUS_IMAGE" ]]; then
    fail "되돌릴 이미지가 없습니다. 컨테이너를 정지된 채로 둡니다."
    fail "이 배포가 첫 배포라면 정상입니다. 이미지를 고쳐 다시 실행하십시오."
    exit 2
fi

if [[ "$PREVIOUS_IMAGE" == "$NEW_IMAGE" ]]; then
    fail "롤백 후보가 방금 실패한 이미지와 같습니다. 되돌릴 곳이 없습니다."
    exit 2
fi

log "롤백: $PREVIOUS_IMAGE"
start_container "$PREVIOUS_IMAGE"

if wait_for_readiness; then
    log "롤백 성공. 서비스는 이전 버전으로 살아 있습니다."
    log "배포는 실패로 처리합니다."
    exit 1
fi

fail "롤백한 이미지도 readiness 에 실패했습니다. 사람이 봐야 합니다."
exit 2
