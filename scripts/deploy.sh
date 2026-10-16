#!/bin/bash
set -e

DEPLOY_DIR="/opt/rentoptima-staging"
LOG_FILE="/var/log/optirent-staging-deploy.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# Ветку, которую установка отслеживает, задаёт DEPLOY_BRANCH в .env (по умолчанию
# feat/channels-mvp — как до переезда). Workflow передаёт ветку, которую выкатывает,
# первым аргументом; чужую ветку скрипт пропускает. Так push в feat/channels-mvp не
# попадёт на prod, когда эта установка обслуживает optirent.ru с веткой main.
REQUESTED_BRANCH="${1:-feat/channels-mvp}"

log "=== Deploy started ($REQUESTED_BRANCH) ==="

cd "$DEPLOY_DIR" || {
    log "ERROR: cannot cd to $DEPLOY_DIR"
    exit 1
}

BRANCH=$(grep -E '^DEPLOY_BRANCH=' .env 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '"'"'"' \r')
BRANCH="${BRANCH:-feat/channels-mvp}"

if [ "$REQUESTED_BRANCH" != "$BRANCH" ]; then
    log "Skip: this install tracks $BRANCH, not $REQUESTED_BRANCH"
    exit 0
fi

log "Fetching latest changes"
git fetch origin "$BRANCH"

CURRENT_SHA=$(git rev-parse HEAD)
LATEST_SHA=$(git rev-parse "origin/$BRANCH")

if [ "$CURRENT_SHA" = "$LATEST_SHA" ]; then
    log "No changes to deploy (already at $CURRENT_SHA)"
    exit 0
fi

log "Deploying $CURRENT_SHA → $LATEST_SHA"
git checkout -f -B "$BRANCH" "origin/$BRANCH"

log "Rebuilding containers"
docker compose up --build -d

log "Waiting for container to be ready"
sleep 10

for i in {1..30}; do
    if curl -sf -o /dev/null http://127.0.0.1:8081/login; then
        log "✅ App is responding"
        break
    fi
    if [ $i -eq 30 ]; then
        log "⚠️  App did not respond after 5 minutes, but continuing"
        break
    fi
    sleep 10
done

log "=== Deploy finished ==="
