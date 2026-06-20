#!/bin/bash
# =============================================================================
# NemakiWare — AWS EC2 user-data bootstrap (Amazon Linux 2023)
# =============================================================================
# Paste this into the EC2 "User data" field (Advanced details) when launching
# an instance, or pass it via `aws ec2 run-instances --user-data file://...`.
# It runs ONCE as root on first boot and brings up the full stack from the
# PUBLISHED images (no Java/Maven/Node build on the host).
#
# Recommended instance: t3.large (8 GB) minimum; t3.xlarge (16 GB) if RAG is on.
# Recommended OS: Amazon Linux 2023.
#
# Before launching, review the CONFIG block below. Anything left at its default
# can also be overridden by tagging the instance with a matching tag Key
# (e.g. tag "NemakiVersion" = "3.2.0"); tags are read via IMDS at boot.
# =============================================================================
set -euo pipefail
exec > >(tee /var/log/nemaki-bootstrap.log) 2>&1
echo "[nemaki] bootstrap starting at $(date -u)"

# ── CONFIG (edit these, or set matching instance tags) ───────────────────────
NEMAKI_REPO="${NEMAKI_REPO:-aegif/NemakiWare}"      # GitHub owner/repo (open source)
NEMAKI_REF="${NEMAKI_REF:-v3.2.0}"                  # git tag / branch to deploy
NEMAKI_IMAGE_PREFIX="${NEMAKI_IMAGE_PREFIX:-ghcr.io/aegif/nemakiware}"
NEMAKI_VERSION="${NEMAKI_VERSION:-3.2.0}"           # image tag (must exist in registry)
COUCHDB_USER="${COUCHDB_USER:-admin}"
# Public exposure. SAFE DEFAULT: 127.0.0.1 — core is reachable only via a local
# reverse proxy (front it with nginx/ALB + TLS, see README). For a quick
# throwaway demo over plain HTTP, explicitly set 0.0.0.0 (and open 8080).
NEMAKI_HTTP_BIND="${NEMAKI_HTTP_BIND:-127.0.0.1}"
# Optional: pull the CouchDB password from AWS Secrets Manager instead of
# generating a random one. The secret value is used verbatim as the password.
COUCHDB_SECRET_ID="${COUCHDB_SECRET_ID:-}"
INSTALL_DIR="/opt/nemakiware"

# ── Read overrides from instance tags via IMDSv2 (best effort) ───────────────
read_tag() {
  local key="$1"
  local token region iid
  token=$(curl -sf -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 300" 2>/dev/null) || return 0
  region=$(curl -sf -H "X-aws-ec2-metadata-token: $token" \
    http://169.254.169.254/latest/meta-data/placement/region 2>/dev/null) || return 0
  iid=$(curl -sf -H "X-aws-ec2-metadata-token: $token" \
    http://169.254.169.254/latest/meta-data/instance-id 2>/dev/null) || return 0
  aws ec2 describe-tags --region "$region" \
    --filters "Name=resource-id,Values=$iid" "Name=key,Values=$key" \
    --query 'Tags[0].Value' --output text 2>/dev/null | grep -v '^None$' || true
}
# Tag reading needs the AWS CLI (preinstalled on AL2023). Skip silently if it is
# unavailable — the CONFIG / env values above remain in effect.
if command -v aws >/dev/null 2>&1; then
  for pair in "NemakiVersion:NEMAKI_VERSION" "NemakiRef:NEMAKI_REF" \
              "NemakiRepo:NEMAKI_REPO" "NemakiImagePrefix:NEMAKI_IMAGE_PREFIX" \
              "NemakiHttpBind:NEMAKI_HTTP_BIND" "CouchdbSecretId:COUCHDB_SECRET_ID"; do
    tagkey="${pair%%:*}"; varname="${pair##*:}"
    val=$(read_tag "$tagkey") || true
    # printf -v assigns the literal value to the named variable with no
    # re-evaluation — never use eval here (tag values are attacker-influenceable).
    if [ -n "${val:-}" ]; then echo "[nemaki] tag override $varname=$val"; printf -v "$varname" '%s' "$val"; fi
  done
fi

# ── Install Docker + compose plugin + git ────────────────────────────────────
echo "[nemaki] installing docker + git"
dnf -y update
dnf -y install docker git
systemctl enable --now docker

# AL2023's docker package does NOT bundle the Compose v2 plugin. Install it as a
# CLI plugin if `docker compose` is unavailable, so the bootstrap can run it.
if ! docker compose version >/dev/null 2>&1; then
  echo "[nemaki] installing docker compose v2 plugin"
  DCV="v2.29.7"
  mkdir -p /usr/local/lib/docker/cli-plugins
  curl -fsSL "https://github.com/docker/compose/releases/download/${DCV}/docker-compose-linux-$(uname -m)" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
  chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  docker compose version
fi

# ── Fetch the deployment tree (compose + couchdb/local.ini) ──────────────────
echo "[nemaki] cloning ${NEMAKI_REPO}@${NEMAKI_REF}"
rm -rf "$INSTALL_DIR/src"
git clone --depth 1 --branch "$NEMAKI_REF" "https://github.com/${NEMAKI_REPO}.git" "$INSTALL_DIR/src"
COMPOSE_DIR="$INSTALL_DIR/src/docker"
mkdir -p "$COMPOSE_DIR/secrets"

# ── Resolve CouchDB password ─────────────────────────────────────────────────
if [ -n "$COUCHDB_SECRET_ID" ]; then
  echo "[nemaki] reading CouchDB password from Secrets Manager: $COUCHDB_SECRET_ID"
  if ! command -v aws >/dev/null 2>&1; then
    echo "[nemaki] FATAL: COUCHDB_SECRET_ID is set but the AWS CLI is not installed." >&2
    exit 1
  fi
  region=$(curl -sf -H "X-aws-ec2-metadata-token: $(curl -sf -X PUT \
    'http://169.254.169.254/latest/api/token' \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" \
    http://169.254.169.254/latest/meta-data/placement/region) || {
      echo "[nemaki] FATAL: could not determine region from IMDS for Secrets Manager." >&2; exit 1; }
  COUCHDB_PASSWORD=$(aws secretsmanager get-secret-value --region "$region" \
    --secret-id "$COUCHDB_SECRET_ID" --query SecretString --output text) || {
      echo "[nemaki] FATAL: failed to read secret '$COUCHDB_SECRET_ID' — check the instance role has secretsmanager:GetSecretValue and the secret exists." >&2
      exit 1; }
  if [ -z "$COUCHDB_PASSWORD" ] || [ "$COUCHDB_PASSWORD" = "None" ]; then
    echo "[nemaki] FATAL: secret '$COUCHDB_SECRET_ID' resolved to an empty value." >&2
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

# ── Install a systemd unit so the stack restarts on reboot ───────────────────
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
