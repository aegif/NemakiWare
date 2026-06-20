#!/usr/bin/env bash
# =============================================================================
# CI helper: drive the NemakiWare Setup Wizard on a freshly-started stack.
# =============================================================================
# A fresh CouchDB makes NemakiWare 3.x enter Setup Mode (setupRequired=true);
# the admin user + databases are created by the Setup Wizard, NOT auto-seeded.
# Test workflows that authenticate as admin:admin must therefore complete setup
# first. This script does that via the Setup API and then waits until admin auth
# works, so the existing tests can run unchanged.
#
# Run it from the directory that contains the compose file (e.g. `cd docker`).
# Environment:
#   BASE_URL          default http://localhost:8080
#   COMPOSE_FILE      default docker-compose-simple.yml
#   CORE_SERVICE      default core   (compose service name of the core container)
#   COUCHDB_URL       default http://couchdb:5984 (in-network URL the core uses)
#   COUCHDB_USER      default admin
#   COUCHDB_PASSWORD  required (CouchDB admin password used by the stack)
#
# The admin application password is left at the dump default (admin/admin): the
# apply request intentionally omits adminPassword so tests keep working.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-simple.yml}"
CORE_SERVICE="${CORE_SERVICE:-core}"
COUCHDB_URL="${COUCHDB_URL:-http://couchdb:5984}"
COUCHDB_USER="${COUCHDB_USER:-admin}"
COUCHDB_PASSWORD="${COUCHDB_PASSWORD:?COUCHDB_PASSWORD must be set}"
SETUP_BASE="${BASE_URL}/core/api/v1/setup"

echo "[setup] waiting for the core to serve setup/state ..."
state=""
for i in $(seq 1 90); do
  if state=$(curl -sf "${SETUP_BASE}/state" 2>/dev/null); then
    echo "[setup] core answered setup/state on attempt $i: $state"
    break
  fi
  state=""; sleep 5
done
[ -n "$state" ] || { echo "[setup] FATAL: core never served setup/state"; exit 1; }

if echo "$state" | grep -q '"setupRequired":false'; then
  echo "[setup] setup already complete — nothing to do"
  exit 0
fi

echo "[setup] reading setup token from the core container ..."
TOKEN="$(docker compose -f "$COMPOSE_FILE" exec -T "$CORE_SERVICE" \
  cat /usr/local/tomcat/conf/setup-token 2>/dev/null | tr -d '\r\n' || true)"
[ -n "$TOKEN" ] || { echo "[setup] FATAL: could not read setup token"; exit 1; }

echo "[setup] POST /apply (DB init + auth config) ..."
# Build the JSON with jq (robust against special chars in the password) and pass
# it via a file so the password never appears in the process argument list.
apply_body_file="$(mktemp)"
trap 'rm -f "$apply_body_file"' EXIT
jq -n --arg url "$COUCHDB_URL" --arg u "$COUCHDB_USER" --arg p "$COUCHDB_PASSWORD" \
  '{couchdb:{url:$url,username:$u,password:$p},auth:{passwordEnabled:true}}' > "$apply_body_file"
curl -sf -X POST "${SETUP_BASE}/apply" \
  -H "X-Setup-Token: ${TOKEN}" -H "Content-Type: application/json" \
  --data @"$apply_body_file" >/dev/null || { echo "[setup] FATAL: /apply failed"; exit 1; }

# /apply already self-completes setup (it flips setupRequired=false once the DB
# is initialized), so mark-complete usually returns 403 "not available in Normal
# Mode" — that is expected and fine. Call it once, best-effort, only to cover the
# rare case where apply leaves setup pending. The real gate is admin auth below.
echo "[setup] POST /apply/mark-complete (best-effort) ..."
mc_code=$(curl -s -o /tmp/nemaki_mc_body -w '%{http_code}' -X POST "${SETUP_BASE}/apply/mark-complete" \
  -H "X-Setup-Token: ${TOKEN}" -H "Content-Type: application/json" 2>/dev/null || echo "000")
echo "[setup] mark-complete → HTTP ${mc_code}: $(head -c 200 /tmp/nemaki_mc_body 2>/dev/null)"

echo "[setup] waiting for admin authentication to work ..."
for i in $(seq 1 60); do
  if curl -sf -u "admin:admin" "${BASE_URL}/core/atom/bedroom" >/dev/null 2>&1; then
    echo "[setup] admin:admin works — setup complete (attempt $i)"
    exit 0
  fi
  sleep 3
done
echo "[setup] FATAL: admin:admin never worked after setup"
docker compose -f "$COMPOSE_FILE" logs --tail 150 "$CORE_SERVICE" || true
exit 1
