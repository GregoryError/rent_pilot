#!/bin/bash
# Deploy script executed on server-khqi via SSH from GitHub Actions.
# Pulls latest feat/channels-mvp and rebuilds staging containers.

set -e

DEPLOY_DIR="/opt/rentoptima-staging"
LOG_FILE="/var/log/optirent-staging-deploy.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "=== Deploy started ==="

cd "$DEPLOY_DIR" || {
    log "ERROR: cannot cd to $DEPLOY_DIR"
    exit 1
}

log "Fetching latest changes"
git fetch origin feat/channels-mvp

CURRENT_SHA=$(git rev-parse HEAD)
LATEST_SHA=$(git rev-parse origin/feat/channels-mvp)

if [ "$CURRENT_SHA" = "$LATEST_SHA" ]; then
    log "No changes to deploy (already at $CURRENT_SHA)"
    exit 0
fi

log "Deploying $CURRENT_SHA → $LATEST_SHA"
git reset --hard origin/feat/channels-mvp

log "Rebuilding containers"
docker compose up --build -d

log "Waiting for container to be ready"
sleep 10

# Ждём пока Spring Boot стартанёт
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
