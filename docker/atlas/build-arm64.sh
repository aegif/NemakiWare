#!/usr/bin/env bash
#
# Build a native arm64 (aarch64) Apache Atlas 2.3.0 image for Apple Silicon
# (and other ARM64 hosts).
#
# Why this exists: the community sburn/apache-atlas:2.3.0 image (the one wired
# into docker-compose-atlas.yml) is linux/amd64 only, so on Apple Silicon it
# runs under qemu emulation (slow + OOM-prone). No arm64 Atlas image is
# published anywhere. Atlas is a JVM app (Atlas server + embedded HBase + Solr),
# so it runs on arm64 — but building it from source in 2026 needs several fixes,
# all encapsulated in docker/atlas/Dockerfile.arm64 (built on sburn's build
# recipe):
#   1. JAVA_HOME points at the arm64 JDK path (Dockerfile ENV + conf/atlas-env.sh).
#   2. python3 + build-essential added so node-gyp can build node-sass (the UI's
#      native SASS binding) on arm64.
#   3. maven-wagon SSL relaxed (ignore.validity.dates) — repo.hortonworks.com's
#      TLS cert expired (Sep 2025).
#   4. The vendor bridge modules (hive/sqoop/storm/hbase/kafka/impala/falcon) are
#      excluded from the reactor and their hook assemblies removed from
#      distro/pom.xml: they pull Hortonworks-hosted artifacts that are now dead
#      (504 + expired cert), and NemakiWare only needs the Atlas server/REST API.
#   5. settings-arm64.xml mirrors the dead Hortonworks repo to clojars (which has
#      the one still-needed artifact, com.twitter:carbonite).
#
# The result exposes the same Atlas REST API (api/atlas/v2, port 21000) as the
# amd64 image — a drop-in with no NemakiWare change.
#
# Usage:
#   docker/atlas/build-arm64.sh
# then run the Atlas overlay natively with:
#   NEMAKI_ATLAS_IMAGE=nemakiware-atlas:2.3.0-arm64 NEMAKI_ATLAS_PLATFORM=linux/arm64 \
#     docker compose -f docker-compose-simple.yml -f docker-compose-atlas.yml up -d
set -euo pipefail

IMAGE_TAG="${IMAGE_TAG:-nemakiware-atlas:2.3.0-arm64}"
# Pin the build context to a specific sburn/docker-apache-atlas commit so the
# image is reproducible (upstream `master` drifts). Override with SBURN_REF.
SBURN_REF="${SBURN_REF:-9808d67cd47852d56b2501e5d4ae2e8b65e30aeb}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BUILD_DIR="$(mktemp -d)"
cleanup() { rm -rf "$BUILD_DIR"; }
trap cleanup EXIT

echo ">> Cloning sburn/docker-apache-atlas @ ${SBURN_REF} for the build context..."
# Treeless clone gets the full commit graph cheaply so a pinned SHA (not just a
# branch/tag tip) can be checked out; blobs are fetched lazily on checkout.
git clone --filter=tree:0 --quiet \
    https://github.com/sburn/docker-apache-atlas.git "${BUILD_DIR}/atlas"
git -C "${BUILD_DIR}/atlas" checkout --quiet "${SBURN_REF}"

# Drop in the arm64 Dockerfile + the clojars mirror settings, and point the
# runtime atlas-env.sh JAVA_HOME at the arm64 JDK (upstream hardcodes amd64).
cp "${SCRIPT_DIR}/Dockerfile.arm64"   "${BUILD_DIR}/atlas/Dockerfile.arm64"
cp "${SCRIPT_DIR}/settings-arm64.xml" "${BUILD_DIR}/atlas/settings-arm64.xml"
sed -i.bak \
    's#export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64#export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-arm64#' \
    "${BUILD_DIR}/atlas/conf/atlas-env.sh"

echo ">> Building ${IMAGE_TAG} for linux/arm64 (native; Atlas source build ~15-20 min first time)..."
docker build \
    -f "${BUILD_DIR}/atlas/Dockerfile.arm64" \
    --platform=linux/arm64 \
    -t "${IMAGE_TAG}" \
    "${BUILD_DIR}/atlas"

echo ">> Done: ${IMAGE_TAG}"
echo ">> Run the Atlas overlay natively with:"
echo "     NEMAKI_ATLAS_IMAGE=${IMAGE_TAG} NEMAKI_ATLAS_PLATFORM=linux/arm64 \\"
echo "       docker compose -f docker-compose-simple.yml -f docker-compose-atlas.yml up -d"
