#!/usr/bin/env bash
#
# Build a native arm64 (aarch64) Hugging Face TEI image for Apple Silicon
# (and other ARM64 hosts, e.g. AWS Graviton).
#
# Why this exists: Hugging Face publishes only linux/amd64 TEI CPU images
# (ghcr.io/huggingface/text-embeddings-inference:cpu-*), so on Apple Silicon the
# stock image runs under qemu emulation. The upstream Dockerfile is Intel-MKL-
# locked and cannot be built for arm64 as-is. This script clones the TEI source
# and builds it with Dockerfile.arm64 (MKL dropped, ort+candle backends) to get
# a native arm64 image that exposes the SAME native TEI HTTP API (POST /embed,
# GET /health) — a drop-in with no NemakiWare code change.
#
# Usage:
#   docker/tei/build-arm64.sh
# then run the stack with:
#   NEMAKI_TEI_IMAGE=nemakiware-tei:1.7.4-arm64-ort NEMAKI_TEI_PLATFORM=linux/arm64 \
#     docker compose -f docker-compose-simple.yml --profile rag up -d
#
# Notes:
# - The ort (ONNX Runtime) backend is required for XLM-RoBERTa models such as
#   intfloat/multilingual-e5-large (NemakiWare's 1024-dim model): they ship an
#   onnx/ export and hang on the pure-candle backend on ARM. The candle backend
#   is kept as a fallback for BERT-family models.
# - git-lfs is intentionally bypassed: the TEI source tree is not LFS; only
#   docs/assets are, and we don't need them to build.
set -euo pipefail

TEI_VERSION="${TEI_VERSION:-v1.7.4}"
IMAGE_TAG="${IMAGE_TAG:-nemakiware-tei:1.7.4-arm64-ort}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BUILD_DIR="$(mktemp -d)"
cleanup() { rm -rf "$BUILD_DIR"; }
trap cleanup EXIT

echo ">> Cloning text-embeddings-inference ${TEI_VERSION} (LFS skipped)..."
git -c filter.lfs.smudge= -c filter.lfs.process= -c filter.lfs.required=false \
    clone --depth 1 --branch "${TEI_VERSION}" \
    https://github.com/huggingface/text-embeddings-inference.git "${BUILD_DIR}/tei"

cp "${SCRIPT_DIR}/Dockerfile.arm64" "${BUILD_DIR}/tei/Dockerfile.arm64"

echo ">> Building ${IMAGE_TAG} for linux/arm64 (native, no emulation)..."
docker build \
    -f "${BUILD_DIR}/tei/Dockerfile.arm64" \
    --platform=linux/arm64 \
    -t "${IMAGE_TAG}" \
    "${BUILD_DIR}/tei"

echo ">> Done: ${IMAGE_TAG}"
echo ">> Run the RAG stack natively with:"
echo "     NEMAKI_TEI_IMAGE=${IMAGE_TAG} NEMAKI_TEI_PLATFORM=linux/arm64 \\"
echo "       docker compose -f docker-compose-simple.yml --profile rag up -d"
