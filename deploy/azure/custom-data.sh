#!/bin/bash
# =============================================================================
# NemakiWare — Azure VM custom-data bootstrap (Ubuntu 22.04 / 24.04)
# =============================================================================
# Pass this as the VM's custom-data (cloud-init) at creation:
#   az vm create ... --custom-data deploy/azure/custom-data.sh
# It runs ONCE as root on first boot and brings up the full stack from the
# PUBLISHED images (no Java/Maven/Node build on the host).
#
# Recommended size: Standard_B2ms (8 GB) minimum; Standard_D4s_v5 (16 GB) for RAG.
# Recommended image: Ubuntu Server 22.04 LTS or 24.04 LTS.
# =============================================================================
set -euo pipefail
exec > >(tee /var/log/nemaki-bootstrap.log) 2>&1
echo "[nemaki] bootstrap starting at $(date -u)"

# ── CONFIG (edit these before creating the VM) ───────────────────────────────
NEMAKI_REPO="${NEMAKI_REPO:-aegif/NemakiWare}"      # GitHub owner/repo (open source)
NEMAKI_REF="${NEMAKI_REF:-v3.2.0}"                  # git tag / branch to deploy
NEMAKI_IMAGE_PREFIX="${NEMAKI_IMAGE_PREFIX:-ghcr.io/aegif/nemakiware}"
NEMAKI_VERSION="${NEMAKI_VERSION:-3.2.0}"           # image tag (must exist in registry)
COUCHDB_USER="${COUCHDB_USER:-admin}"
# Public exposure. SAFE DEFAULT: 127.0.0.1 — core is reachable only via a local
# reverse proxy (front it with nginx/App Gateway + TLS, see README). For a quick
# throwaway demo over plain HTTP, explicitly set 0.0.0.0 (and open 8080).
NEMAKI_HTTP_BIND="${NEMAKI_HTTP_BIND:-127.0.0.1}"
# Optional: read the CouchDB password from Azure Key Vault via the VM's
# system-assigned managed identity. Provide the full secret URI, e.g.
#   https://my-vault.vault.azure.net/secrets/couchdb-password
COUCHDB_KEYVAULT_SECRET_URI="${COUCHDB_KEYVAULT_SECRET_URI:-}"
INSTALL_DIR="/opt/nemakiware"

# ── Install Docker + compose plugin + git ────────────────────────────────────
echo "[nemaki] installing docker + git"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl git gnupg jq openssl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker

# ── Read overrides from VM tags via IMDS (best effort) ───────────────────────
# Lets Terraform / az drive config purely through VM tags, keeping this script
# as the single source of truth. jq is installed above.
TAGS_JSON=$(curl -sf -H "Metadata: true" \
  "http://169.254.169.254/metadata/instance/compute/tagsList?api-version=2021-02-01" 2>/dev/null || echo '[]')
read_tag() { echo "$TAGS_JSON" | jq -r --arg n "$1" '.[] | select(.name==$n) | .value' 2>/dev/null | head -n1; }
for pair in "NemakiVersion:NEMAKI_VERSION" "NemakiRef:NEMAKI_REF" \
            "NemakiRepo:NEMAKI_REPO" "NemakiImagePrefix:NEMAKI_IMAGE_PREFIX" \
            "NemakiHttpBind:NEMAKI_HTTP_BIND" "CouchdbKeyvaultSecretUri:COUCHDB_KEYVAULT_SECRET_URI"; do
  tagkey="${pair%%:*}"; varname="${pair##*:}"
  val=$(read_tag "$tagkey") || true
  # printf -v assigns the literal value to the named variable with no
  # re-evaluation — never use eval here (tag values are attacker-influenceable).
  if [ -n "${val:-}" ] && [ "${val}" != "null" ]; then
    echo "[nemaki] tag override $varname=$val"; printf -v "$varname" '%s' "$val"
  fi
done

# ── Fetch the deployment tree (compose + couchdb/local.ini) ──────────────────
echo "[nemaki] cloning ${NEMAKI_REPO}@${NEMAKI_REF}"
rm -rf "$INSTALL_DIR/src"
git clone --depth 1 --branch "$NEMAKI_REF" "https://github.com/${NEMAKI_REPO}.git" "$INSTALL_DIR/src"
COMPOSE_DIR="$INSTALL_DIR/src/docker"
mkdir -p "$COMPOSE_DIR/secrets"

# ── Resolve CouchDB password ─────────────────────────────────────────────────
if [ -n "$COUCHDB_KEYVAULT_SECRET_URI" ]; then
  echo "[nemaki] reading CouchDB password from Key Vault via managed identity"
  AAD_TOKEN=$(curl -sf -H "Metadata: true" \
    "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://vault.azure.net" \
    | jq -r .access_token) || AAD_TOKEN=""
  if [ -z "$AAD_TOKEN" ] || [ "$AAD_TOKEN" = "null" ]; then
    echo "[nemaki] FATAL: could not obtain an AAD token from IMDS — is a system-assigned managed identity enabled on the VM?" >&2
    exit 1
  fi
  COUCHDB_PASSWORD=$(curl -sf -H "Authorization: Bearer ${AAD_TOKEN}" \
    "${COUCHDB_KEYVAULT_SECRET_URI}?api-version=7.4" | jq -r .value) || COUCHDB_PASSWORD=""
  if [ -z "$COUCHDB_PASSWORD" ] || [ "$COUCHDB_PASSWORD" = "null" ]; then
    echo "[nemaki] FATAL: failed to read Key Vault secret '$COUCHDB_KEYVAULT_SECRET_URI' — check the identity has 'get' on the secret and the URI is correct." >&2
    exit 1
  fi
else
  echo "[nemaki] generating random CouchDB password (stored only in $COMPOSE_DIR/.env)"
  COUCHDB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-28)
fi

# ── Write .env ───────────────────────────────────────────────────────────────
umask 077
cat > "$COMPOSE_DIR/.env" <<EOF
NEMAKI_IMAGE_PREFIX=${NEMAKI_IMAGE_PREFIX}
NEMAKI_VERSION=${NEMAKI_VERSION}
COUCHDB_USER=${COUCHDB_USER}
COUCHDB_PASSWORD=${COUCHDB_PASSWORD}
NEMAKI_HTTP_BIND=${NEMAKI_HTTP_BIND}
NEMAKI_PUBLIC_SCHEME=auto
EOF
umask 022

# ── Pull + start ─────────────────────────────────────────────────────────────
cd "$COMPOSE_DIR"
echo "[nemaki] pulling images"
docker compose -f docker-compose-prod.yml pull
echo "[nemaki] starting stack"
docker compose -f docker-compose-prod.yml up -d

# ── systemd unit so the stack restarts on reboot ─────────────────────────────
cat > /etc/systemd/system/nemakiware.service <<EOF
[Unit]
Description=NemakiWare (docker compose)
Requires=docker.service
After=docker.service
[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=${COMPOSE_DIR}
ExecStart=/usr/bin/docker compose -f docker-compose-prod.yml up -d
ExecStop=/usr/bin/docker compose -f docker-compose-prod.yml down
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable nemakiware.service

echo "[nemaki] bootstrap complete at $(date -u)."
echo "[nemaki] health: curl -u ${COUCHDB_USER}:*** http://localhost:8080/core/atom/bedroom"
echo "[nemaki] (admin UI login defaults to admin/admin — change immediately)"
