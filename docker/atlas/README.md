# Native ARM64 (Apple Silicon) Apache Atlas image

`docker-compose-atlas.yml` uses the community `sburn/apache-atlas:2.3.0` image,
which is published for **linux/amd64 only**. On Apple Silicon it therefore runs
under **qemu emulation** — slow and OOM-prone (Atlas bundles its server + an
embedded HBase + Solr). No arm64 Atlas image is published anywhere.

Atlas is a JVM application, so it runs natively on arm64; this directory builds
a native arm64 image that exposes the *same* Atlas REST API (`api/atlas/v2`,
port 21000) — a drop-in with **no NemakiWare change**.

## Build

```bash
docker/atlas/build-arm64.sh
```

Clones `sburn/docker-apache-atlas` for the build context, drops in
`Dockerfile.arm64` + `settings-arm64.xml`, points `conf/atlas-env.sh` at the
arm64 JDK, and builds `nemakiware-atlas:2.3.0-arm64`. The first build compiles
Atlas 2.3.0 from source (~15–20 min); a BuildKit cache mount for `~/.m2` keeps
re-builds fast.

## Run the Atlas overlay natively

```bash
NEMAKI_ATLAS_IMAGE=nemakiware-atlas:2.3.0-arm64 \
NEMAKI_ATLAS_PLATFORM=linux/arm64 \
  docker compose -f docker-compose-simple.yml -f docker-compose-atlas.yml up -d
```

The `atlas` service reads `NEMAKI_ATLAS_IMAGE` / `NEMAKI_ATLAS_PLATFORM`
(defaults: the amd64 image on `linux/amd64`), so x86 hosts and CI are unaffected.

## What the arm64 build had to fix (all in `Dockerfile.arm64`)

Building Atlas 2.3.0 from source in 2026 on arm64 required, beyond the sburn
recipe:

1. **arm64 JDK path** — `JAVA_HOME` (Dockerfile ENV) and the runtime
   `conf/atlas-env.sh` both hardcoded `.../java-8-openjdk-amd64`; pointed at
   `.../java-8-openjdk-arm64` (the amd64 path is why `atlas_start.py` failed with
   `OSError` looking for `jar`).
2. **node-sass native build** — added `python3` + `build-essential` (+
   `PYTHON=python3`) so node-gyp can compile the Atlas UI's SASS binding on arm64.
3. **dead expired-cert repo** — `repo.hortonworks.com`'s TLS cert expired (Sep
   2025) and it 504s. Rather than disabling Maven's TLS validation globally
   (`ssl.insecure`/`ssl.allowall`), the sole Hortonworks repo (`hortonworks.repo`
   — the only such reference in Atlas 2.3.0's poms) is mirrored to clojars in
   `settings-arm64.xml`, so the build never connects to the dead host and full
   TLS validation stays on for every repository, including Maven Central.
4. **dead vendor artifacts** — the hive/sqoop/storm/hbase/kafka/impala/falcon
   bridges pull Hortonworks-hosted artifacts that now 504. They're not needed for
   NemakiWare (which uses only the Atlas server/REST API), so they're excluded
   from the reactor (`-pl '!addons/...'`) and their hook assemblies removed from
   `distro/pom.xml` (otherwise the distro assembly fails "archive cannot be
   empty").
5. **carbonite** — the one still-needed formerly-Hortonworks artifact
   (`com.twitter:carbonite:1.5.0`) is mirrored to clojars via `settings-arm64.xml`.

## Verified

`nemakiware-atlas:2.3.0-arm64` (arch `arm64`, ~3.9 GB):

- Atlas boots natively (embedded HBase + Solr + Atlas server all start on arm64;
  the build pre-initializes them so first start is fast).
- `GET /api/atlas/v2/types/typedefs/headers` → 200 with 196 type definitions;
  `GET /api/atlas/admin/version` → `2.3.0 / apache-atlas`. ~2 GB RAM at idle.

## Runtime posture (arm64 image)

Beyond parity with the amd64 image, this build hardens the runtime:

- **Non-root** — runs as a dedicated `atlas` UID. Atlas + embedded HBase/Solr
  bind only unprivileged ports (21000 / 16000 / 9838) and write under
  `/apache-atlas`, which the build `chown`s to `atlas`.
- **Process-supervised** — `atlas_start.py` daemonises Atlas and returns, so the
  container's PID 1 polls the Atlas server pidfile and exits when the process
  dies. Previously a bare `tail -fF` kept the container reported "Up" even after
  Atlas had crashed (only embedded HBase surviving), masking the outage from the
  healthcheck and Docker's restart policy.
- **Loopback-bound** — `docker-compose-atlas.yml` publishes 21000 on
  `127.0.0.1` only (dev/eval), because the credentials below are fixed.

## Credentials

Unchanged from the amd64 image: `admin` / `admin` (baked into the Atlas user
store; see `docker-compose-atlas.yml`). They cannot be overridden, so never
expose port 21000 off-host without TLS + a real IdP in front.
