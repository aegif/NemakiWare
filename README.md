# NemakiWare

**Permission-aware document repository for RAG** — an open source platform that stores documents with fine-grained access control and makes them searchable via semantic vector search, ready to plug into any LLM pipeline.

## Why NemakiWare?

Building RAG on top of file storage or generic databases means bolting on permissions after the fact. NemakiWare solves this at the repository layer: every document, every chunk, every search result is governed by the same ACL model. Your LLM only sees what the requesting user is allowed to see.

- **ACL-filtered semantic search** — vector search results are filtered by the current user's permissions in real time
- **Automatic chunking & embedding** — upload a document and it is chunked, embedded, and indexed with zero extra work
- **MCP server built in** — connect Claude, ChatGPT, or any MCP-compatible agent directly to your repository
- **Bring your own embeddings** — Hugging Face TEI (self-hosted) or Amazon Bedrock (managed)
- **Full document lifecycle** — versioning, relationships, retention, archival to S3 cold storage
- **Modern React UI** — browse, search, manage users/groups, configure everything from the browser

## Quick Start

### Prerequisites

- Docker and Docker Compose
- 4GB+ available memory (16GB+ if enabling the self-hosted embedding server)

### 1. Build

```bash
# Install OpenCMIS JARs to local Maven repository (first build only)
./scripts/install-opencmis-local.sh

# Build UI
cd core/src/main/webapp/ui && npm install && npm run build && cd ../../../..

# Build server
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# Copy WAR to Docker directory
cp core/target/core.war docker/core/core.war
```

### 2. Start

```bash
cd docker

# Required env (compose fails fast if these are unset).
# Use strong values in any non-disposable deployment.
export COUCHDB_USER=admin
export COUCHDB_PASSWORD=password

# Core services (CouchDB + Solr + NemakiWare)
docker compose -f docker-compose-simple.yml up -d --build

# With self-hosted embedding server (TEI)
docker compose -f docker-compose-simple.yml --profile rag up -d --build
```

| Service | Port | Description |
|---------|------|-------------|
| NemakiWare | 8080 | Repository server + React UI |
| CouchDB | 5984 | Document database |
| Solr | 8983 | Full-text & vector search |
| TEI | 8081 | Embedding server (rag profile) |

### 3. Open

- **UI**: http://localhost:8080/core/ui/
- **Credentials**: `admin` / `admin`

A Setup Wizard runs on first launch to configure database, authentication, and embedding provider.

> **Deployment posture (3.1.1)**: this release is **single-replica** by
> design. Multi-replica deployments are supported but require explicit
> setup — sticky sessions at the LB, leader election for cron schedulers,
> bootstrap order with the Setup Wizard at N=1, and a few other
> conditions. See **[`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md)** for
> the authoritative checklist (10 JVM-local subsystems inventoried,
> 6 required conditions, 4 known limitations, bootstrap recipe,
> failure-mode lookup).

---

## Features

### Semantic Search (RAG)

Upload documents and search by meaning, not just keywords.

- **Hybrid search**: combines keyword full-text search with vector similarity
- **Supported formats**: PDF, Word, Excel, PowerPoint, HTML, XML, plain text
- **Configurable weighting**: property boost (metadata) vs content boost (document body)
- **Folder-scoped search**: restrict results to a specific folder tree
- **Similar documents**: find documents related to a given document
- **Rate limiting**: per-user token bucket (configurable)
- **Admin tools**: full reindex, folder reindex, index health monitoring, search-as-user testing

### Permission Model

Every search result is checked against the requesting user's permissions before being returned.

- CMIS ACL (Access Control List) on every object
- Inherited permissions from parent folders
- User/group-based access control
- Admin simulation mode for verifying what a specific user can see
- **External Ingestion delegation (3.1.1-RC3+)**: folder owners with `cmis:all` can manage manual-only import profiles for their folders, using only connectors an admin has expressly delegated. Scheduler, default-profile, connector CRUD, and admin-owned profiles remain admin-only. See [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md) for the full model

### MCP Server

NemakiWare exposes an MCP (Model Context Protocol) server so AI agents can directly search and retrieve documents.

| Tool | Description |
|------|-------------|
| `nemakiware_login` | Authenticate (username/password, API key, or OIDC) |
| `nemakiware_search` | Full-text keyword search |
| `nemakiware_rag_search` | Semantic vector search |
| `nemakiware_similar_documents` | Find similar documents |
| `nemakiware_get_document_content` | Retrieve document content |

Protocol: JSON-RPC 2.0 via HTTP/SSE.

### Embedding Providers

| Provider | Type | Notes |
|----------|------|-------|
| **Hugging Face TEI** | Self-hosted | Default. Ships as a Docker service. Uses `intfloat/multilingual-e5-large` (1024 dim) |
| **Amazon Bedrock** | Managed (Beta) | Titan Embedding V2. IAM role or explicit credentials. See [Bedrock guide](docs/BEDROCK_EMBEDDING.md) |

### Authentication

- Password (BCrypt)
- WebAuthn / Passkey (FIDO2 — Touch ID, Face ID, security keys)
- OIDC (Google, Microsoft)
- SAML (Keycloak)

### Webhooks

Subscribe to document events (created, updated, deleted, ACL changed) and receive HTTP callbacks. Supports Basic, Bearer, API key, and HMAC signing.

### Import / Export

- **ACP (Alfresco Content Package)** import
- **NemakiWare ZIP** format with JSON metadata — preserves folder hierarchy, relationships, and IDs
- **Filesystem** import/export (admin)

### Cloud Integration

| Feature | Google | Microsoft |
|---------|--------|-----------|
| OIDC login | Google Account | Microsoft Account |
| Cloud Drive import | Google Drive | OneDrive |
| Directory sync | Google Workspace | Entra ID |

See [Cloud Integration Guide](docs/CLOUD_INTEGRATION.md).

### Archive & Retention (Beta)

- Scheduled archival of expired or stale documents
- Cold storage to Amazon S3 (with Legal Hold support)
- COPY mode (keep local + S3) or MOVE mode (S3 only)
- Restore from archive, download archived content

---

## Architecture

```
                        ┌───────────────┐
                        │   React UI    │
                        └──────┬────────┘
                               │
┌──────────┐  MCP/REST  ┌──────┴────────┐  Embedding   ┌────────────┐
│ AI Agent ├───────────►│  NemakiWare   ├─────────────►│ TEI / Bedrock │
└──────────┘            │  (Tomcat 11)  │              └────────────┘
                        └──┬────────┬───┘
                           │        │
                     ┌─────┘        └─────┐
                     ▼                    ▼
               ┌──────────┐        ┌──────────┐
               │ CouchDB  │        │   Solr   │
               │ (data)   │        │ (search) │
               └──────────┘        └──────────┘
```

### Technical Stack

| Component | Technology |
|-----------|------------|
| Server | Tomcat 11 (Jakarta EE 11, Virtual Threads) |
| Framework | Spring 7, Apache Chemistry OpenCMIS |
| Database | CouchDB 3.x |
| Search | Apache Solr 9.x (full-text + DenseVector) |
| UI | React 19, TypeScript, Vite 7, Ant Design 5 |
| Java | 21 (required) |

### Project Structure

```
NemakiWare/
├── core/                    # Server (Spring + OpenCMIS)
│   └── src/main/webapp/ui/  # React SPA (TypeScript + Vite)
├── docker/                  # Docker Compose configurations
├── solr/                    # Solr configuration + vector schema
└── common/                  # Shared utilities
```

---

## REST API

### RAG Search

```bash
# Semantic search
curl -u admin:admin -X POST \
  -H "Content-Type: application/json" \
  -d '{"query":"quarterly revenue report","topK":5,"minScore":0.6}' \
  http://localhost:8080/core/api/v1/cmis/repositories/bedroom/rag/search

# Find similar documents
curl -u admin:admin \
  http://localhost:8080/core/api/v1/cmis/repositories/bedroom/rag/similar/{documentId}

# Health check
curl -u admin:admin \
  http://localhost:8080/core/api/v1/cmis/repositories/bedroom/rag/health
```

### CMIS Browser Binding

```bash
# List children of root folder
curl -u admin:admin \
  "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# Create a document
curl -u admin:admin -X POST \
  -F "cmisaction=createDocument" \
  -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" -F "propertyValue[1]=report.pdf" \
  -F "file=@report.pdf" \
  "http://localhost:8080/core/browser/bedroom/root"
```

---

## Development

### Prerequisites

- Java 21, Maven 3.6+, Node.js 18+
- Docker (for CouchDB)

### Development Server (without Docker)

```bash
# Start CouchDB
docker run -d --name couchdb-dev -p 5984:5984 \
  -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password couchdb:3

# Start backend (Jetty dev server — local development only, not for production)
bash core/start-jetty-dev.sh

# Start frontend dev server (hot reload)
cd core/src/main/webapp/ui && npm run dev
```

### Rebuilding After Changes

```bash
# Rebuild UI + WAR + deploy (never use docker compose restart)
cd core/src/main/webapp/ui && npm run build && cd ../../../..
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
cp core/target/core.war docker/core/core.war
cd docker
export COUCHDB_USER=admin COUCHDB_PASSWORD=password   # required env
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core
```

### Testing

```bash
# CMIS TCK tests (requires running Docker environment)
mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup \
  -f core/pom.xml -Pdevelopment

# Playwright E2E tests
cd core/src/main/webapp/ui && npx playwright test --project=chromium

# QA integration tests
./qa-test.sh qa
```

### OpenCMIS JAR Resolution

NemakiWare uses custom OpenCMIS 1.1.0-nemakiware JARs (Jakarta EE compatible). Pre-built JARs are in `lib/built-jars/` and must be installed before the first build:

```bash
./scripts/install-opencmis-local.sh
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [Release Notes](RELEASE_NOTES.md) | User-facing changelog (latest: 3.1.1-RC6.5) |
| [Architecture](docs/ARCHITECTURE.md) | System architecture overview |
| [AWS Deployment](docs/AWS-DEPLOYMENT-GUIDE.md) | Production deployment on AWS |
| [Bedrock Embedding](docs/BEDROCK_EMBEDDING.md) | Amazon Bedrock setup |
| [Cloud Integration](docs/CLOUD_INTEGRATION.md) | Google / Microsoft setup |
| [Archive Enhancement](docs/ARCHIVE_ENHANCEMENT.md) | Retention & cold storage |
| [Connector Delegation](docs/design/connector-delegation.md) | Folder-scoped External Ingestion delegation (RC3+) |
| [Multi-replica Deployment](docs/MULTI-REPLICA-DEPLOYMENT.md) | Required conditions and known limitations for N≥2 replicas |
| [SOC Audit Integration](docs/SOC-AUDIT-INTEGRATION.md) | Audit log shipping + SIEM playbooks (Filebeat / Fluent Bit / Vector → Elastic / Loki / Splunk). Templates under [`docs/soc-templates/`](docs/soc-templates/) — 4 of 6 CLI-validated by `scripts/validate-soc-templates.sh` (RC6.4+) |
| [Manual Verification — Connectors](docs/MANUAL-VERIFICATION-CONNECTORS.md) | コネクタ / インポートプロファイル / ガバナンス / Simulate-remove のステップバイステップ手動検証手順 (curl + UI 並記、RC6.4 時点) |

## Etymology

"Nemaki" derives from the Japanese word "寝巻き" (pajamas). Relax and enjoy happy enterprise time as if you are lying on the couch in your room!

## License

Copyright (c) 2013-2026 aegif.

NemakiWare is Open Source software licensed under the **GNU Affero General Public License version 3**. See `legal/LICENSE` for details.
