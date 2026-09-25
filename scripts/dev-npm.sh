#!/usr/bin/env bash
# Starts (or stops) a disposable Nginx Proxy Manager for the live tests and prints the env vars to use.
#   scripts/dev-npm.sh up      -> start jc21/nginx-proxy-manager on 127.0.0.1:18081 (admin UI/API)
#   scripts/dev-npm.sh down    -> stop and delete it, including its volumes
# NPM_VERSION overrides the image tag (default: the version the app is tested against).
set -euo pipefail

NAME=npm-test
VERSION="${NPM_VERSION:-2.15.1}"
EMAIL=admin@test.local
PASSWORD='TestPassw0rd!'

case "${1:-up}" in
  up)
    if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
      docker rm -f "$NAME" >/dev/null 2>&1 || true
      docker run -d --name "$NAME" \
        -p 127.0.0.1:18080:80 -p 127.0.0.1:18081:81 -p 127.0.0.1:18443:443 \
        -v "$NAME-data:/data" -v "$NAME-le:/etc/letsencrypt" \
        -e INITIAL_ADMIN_EMAIL="$EMAIL" -e INITIAL_ADMIN_PASSWORD="$PASSWORD" -e DISABLE_IPV6=true \
        "jc21/nginx-proxy-manager:$VERSION" >/dev/null
    fi
    for _ in $(seq 1 60); do
      if curl -fsS -m 2 http://127.0.0.1:18081/api/ 2>/dev/null | grep -q '"status":"OK"'; then
        echo "NPM $VERSION is up. Use:"
        echo "  export NPM_IT_URL=http://127.0.0.1:18081 NPM_IT_EMAIL=$EMAIL NPM_IT_PASSWORD='$PASSWORD'"
        exit 0
      fi
      sleep 3
    done
    echo "NPM did not come up; see: docker logs $NAME" >&2
    exit 1
    ;;
  down)
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker volume rm "$NAME-data" "$NAME-le" >/dev/null 2>&1 || true
    echo "removed"
    ;;
  *)
    echo "usage: $0 [up|down]" >&2
    exit 2
    ;;
esac
