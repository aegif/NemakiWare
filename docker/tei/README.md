# Native ARM64 (Apple Silicon) TEI image

Hugging Face publishes the Text Embeddings Inference (TEI) CPU image only for
`linux/amd64` (`ghcr.io/huggingface/text-embeddings-inference:cpu-*`). On Apple
Silicon the stock image therefore runs under **qemu emulation**, and the
upstream `Dockerfile` is **Intel-MKL-locked** so it cannot be built for arm64
as-is.

This directory builds a **native arm64** TEI image that exposes the *same*
native TEI HTTP API (`POST /embed`, `GET /health`) — a drop-in replacement with
**no NemakiWare code change**.

## Build

```bash
docker/tei/build-arm64.sh
```

This clones the pinned TEI source (`TEI_VERSION`, default `v1.7.4`, git-lfs
bypassed) and builds `Dockerfile.arm64`, producing `nemakiware-tei:1.7.4-arm64-ort`.
Native compilation takes only a few minutes (no emulation).

## Run the RAG stack natively

```bash
NEMAKI_TEI_IMAGE=nemakiware-tei:1.7.4-arm64-ort \
NEMAKI_TEI_PLATFORM=linux/arm64 \
  docker compose -f docker-compose-simple.yml --profile rag up -d
```

The `tei` service in `docker-compose-simple.yml` reads `NEMAKI_TEI_IMAGE` /
`NEMAKI_TEI_PLATFORM` (defaults: the amd64 image on `linux/amd64`, i.e. emulated
on Apple Silicon), so x86 hosts and CI are unaffected.

## Why `ort,candle` and not `candle` alone

The build enables **both** backends (`--features ort,candle,http`, MKL dropped):

- **ort (ONNX Runtime)** is *required* for NemakiWare's model
  `intfloat/multilingual-e5-large` (1024-dim, XLM-RoBERTa). That model ships an
  `onnx/` export and TEI's pure-candle backend **hangs** loading it on ARM. With
  `ort`, TEI loads the ONNX weights and serves normally. The `ort` crate
  auto-downloads the aarch64 onnxruntime at build time.
- **candle** is kept as a fallback for BERT-family models (e.g.
  `sentence-transformers/all-MiniLM-L6-v2`), which the candle backend serves
  fine on ARM.

## Verified

`nemakiware-tei:1.7.4-arm64-ort` (arch `arm64`):

- `GET /health` → 200; `POST /embed` with `intfloat/multilingual-e5-large`
  returns **1024-dim** vectors (matches the Solr `knn_vector_1024` schema),
  ~35–48 ms/embed (on par with / faster than the emulated amd64 image).
- End-to-end on the compose network (alias `tei`): `core → tei /embed → Solr`
  block-join RAG search returns results (score ≈ 0.94), zero core-side embedding
  errors.

## Runtime posture (arm64 image)

- **Non-root** — runs as a dedicated `tei` UID. TEI binds unprivileged port 80
  and only writes the model cache under `/data`, which the image `chown`s to
  `tei`. **Adopting this image requires recreating the `tei_cache` volume** (or
  chowning it): Docker does not re-chown an already-populated, root-owned named
  volume on first mount, so a stale root-owned cache would be unreadable by the
  non-root process. Recreating triggers a one-time model re-download.
- **Loopback-bound** — `docker-compose-simple.yml` publishes 8081 on
  `127.0.0.1` only (dev/eval; the embedding endpoint is unauthenticated).

## Model constraint

The model is fixed by the Solr vector schema: `MODEL_ID=intfloat/multilingual-e5-large`
(1024 dimensions). Changing it requires updating
`docker/solr/solr/nemaki/conf/schema.xml` (`knn_vector_1024`) and a full RAG
reindex.
