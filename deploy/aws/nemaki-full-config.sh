# =============================================================================
# NemakiWare — post-bootstrap full-config automation (ephemeral test envs)
# =============================================================================
# Appended AFTER user-data.sh by the terraform module (local.user_data). Turns
# the stock prod stack into a fully-configured verification environment WITHOUT
# any manual SSH:
#   - runs the Setup Wizard (DB + admin)
#   - Bedrock RAG embedding (instance-profile IAM, no static keys)
#   - Apache Atlas container + selects the ATLAS catalog backend
#   - cloud auth (Microsoft / Google) clientIds via admin-managed settings
#   - Caddy HTTPS reverse proxy on ${NIP_HOST} (Let's Encrypt) so the browser
#     gets a secure context (fixes MSAL `crypto_nonexistent`) + a stable
#     redirect origin.
# Reads config from env vars exported by terraform (CLOUD_AUTH_*, NIP_HOST,
# BEDROCK_*). Best-effort: never aborts the boot.
# =============================================================================
set +eu
CD="/opt/nemakiware/src/docker"
log() { echo "[nemaki-full] $*"; }
cd "$CD" 2>/dev/null || { log "compose dir missing; skip"; return 0 2>/dev/null || exit 0; }

CORE() { docker ps -qf name=core | head -1; }

# ── wait for core to answer setup/state ──────────────────────────────────────
log "waiting for core setup/state"
for i in $(seq 1 90); do curl -sf http://localhost:8080/core/api/v1/setup/state >/dev/null 2>&1 && break; sleep 5; done

# ── Setup Wizard (DB init + admin) ───────────────────────────────────────────
CU=$(grep -E '^COUCHDB_USER=' .env | cut -d= -f2-)
CP=$(grep -E '^COUCHDB_PASSWORD=' .env | cut -d= -f2-)
if curl -sf http://localhost:8080/core/api/v1/setup/state 2>/dev/null | grep -q '"setupRequired":true'; then
  TOKEN=$(docker exec "$(CORE)" cat /usr/local/tomcat/conf/setup-token 2>/dev/null | tr -d '\r\n')
  log "running setup /apply"
  printf '{"couchdb":{"url":"http://couchdb:5984","username":"%s","password":"%s"},"auth":{"passwordEnabled":true}}' "$CU" "$CP" \
    | curl -sf -X POST http://localhost:8080/core/api/v1/setup/apply \
        -H "X-Setup-Token: $TOKEN" -H "Content-Type: application/json" --data @- >/dev/null
fi
for i in $(seq 1 60); do curl -sf -u admin:admin http://localhost:8080/core/atom/bedroom >/dev/null 2>&1 && { log "admin:admin OK"; break; }; sleep 3; done

# ── Bedrock/RAG config ───────────────────────────────────────────────────────
# IMPORTANT: Spring loads `classpath:nemakiware.properties` (WEB-INF/classes/),
# NOT the -Dnemakiware.properties=/conf/ file, and its bundled copy has
# rag.embedding.provider COMMENTED OUT (defaults to "tei"). RAG is also NOT an
# admin-managed key, so the integration-settings API can't set it either.
# The reliable channel is a JVM system property (readValue checks System
# properties first, and Spring @Value resolves ${...} from them too). Inject via
# Tomcat's setenv.sh, which catalina.sh sources to APPEND to CATALINA_OPTS —
# so we don't need to know the (long, compose-set) existing CATALINA_OPTS.
mkdir -p config
cat > config/setenv.sh <<SETENV
export CATALINA_OPTS="\$CATALINA_OPTS -Drag.embedding.provider=bedrock -Drag.bedrock.region=${BEDROCK_REGION:-ap-northeast-1} -Drag.bedrock.model.id=${BEDROCK_MODEL_ID:-amazon.titan-embed-text-v2:0} -Drag.bedrock.vector.dimension=1024"
SETENV
chmod +x config/setenv.sh

# ── Atlas container + setenv.sh mount override ───────────────────────────────
cat > docker-compose-atlas-prod.yml <<YAML
services:
  atlas:
    image: sburn/apache-atlas:2.3.0
    restart: unless-stopped
  core:
    depends_on:
      atlas:
        condition: service_started
    volumes:
      - ./config/setenv.sh:/usr/local/tomcat/bin/setenv.sh:ro
YAML
grep -q '^NEMAKI_HEAP_MAX=' .env || echo 'NEMAKI_HEAP_MAX=2g' >> .env
log "recreating stack with atlas override"
docker compose -f docker-compose-prod.yml -f docker-compose-atlas-prod.yml up -d
# The sburn/apache-atlas all-in-one image reliably fails its FIRST boot with an
# EmbeddedServer NPE; a single restart after the embedded services initialise
# brings it up. Do it in the background so it does not block the rest.
( sleep 150; docker restart "$(docker ps -aqf name=atlas | head -1)" >/dev/null 2>&1; log "atlas restarted (first-boot workaround)" ) &

for i in $(seq 1 60); do curl -sf -u admin:admin http://localhost:8080/core/atom/bedroom >/dev/null 2>&1 && break; sleep 5; done

# ── cloud auth + ATLAS backend via admin-managed integration-settings ────────
# (nemaki_conf value takes precedence over -D, so these PUTs are the source of
#  truth. purview.isEnabled() wins over atlas, so purview must be disabled.)
au() { curl -sf -u admin:admin -H "X-Requested-With: XMLHttpRequest" -H "Content-Type: application/json" \
         -X PUT "http://localhost:8080/core/api/v1/admin/integration-settings/$1" --data @- >/dev/null 2>&1; }
if [ -n "${CLOUD_AUTH_MICROSOFT_CLIENT_ID:-}" ]; then
  printf '{"cloud.auth.microsoft.enabled":"true","cloud.auth.microsoft.clientId":"%s","cloud.auth.microsoft.tenantId":"%s"}' \
    "$CLOUD_AUTH_MICROSOFT_CLIENT_ID" "${CLOUD_AUTH_MICROSOFT_TENANT_ID:-}" | au microsoft-auth && log "MS auth configured"
fi
if [ -n "${CLOUD_AUTH_GOOGLE_CLIENT_ID:-}" ]; then
  printf '{"cloud.auth.google.enabled":"true","cloud.auth.google.clientId":"%s"}' \
    "$CLOUD_AUTH_GOOGLE_CLIENT_ID" | au google-auth && log "Google auth configured"
fi
printf '{"purview.enabled":"false"}' | au purview
printf '{"atlas.enabled":"true","atlas.endpoint":"http://atlas:21000","atlas.username":"admin","atlas.password":"admin"}' | au atlas && log "ATLAS backend selected"

# ── Caddy HTTPS reverse proxy on ${NIP_HOST} (Let's Encrypt) ─────────────────
if [ -n "${NIP_HOST:-}" ]; then
  mkdir -p /opt/caddy
  printf '%s {\n  reverse_proxy localhost:8080\n}\n' "$NIP_HOST" > /opt/caddy/Caddyfile
  docker rm -f caddy >/dev/null 2>&1
  docker run -d --name caddy --restart unless-stopped --network host \
    -v /opt/caddy/Caddyfile:/etc/caddy/Caddyfile:ro -v caddy_data:/data caddy:2 >/dev/null 2>&1
  log "caddy started for https://${NIP_HOST}"
fi
log "full-config automation complete at $(date -u)"
