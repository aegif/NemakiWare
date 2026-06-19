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
# Keep 127.0.0.1 if you front with nginx/App Gateway + TLS (see README).
# Use 0.0.0.0 only for a quick throwaway demo over plain HTTP.
NEMAKI_HTTP_BIND="${NEMAKI_HTTP_BIND:-0.0.0.0}"
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
    | jq -r .access_token)
  COUCHDB_PASSWORD=$(curl -sf -H "Authorization: Bearer ${AAD_TOKEN}" \
    "${COUCHDB_KEYVAULT_SECRET_URI}?api-version=7.4" | jq -r .value)
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
