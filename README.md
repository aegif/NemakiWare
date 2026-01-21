# NemakiWare

NemakiWare is an open source Enterprise Content Management system, compliant with CMIS ver1.1.

## Features
- **All-in-one package** including CMIS server, full-text search engine, and modern React client
- **Docker Compose deployment** with CouchDB, Solr, and Tomcat
- **Jakarta EE 10 compatible** with Java 17
- **Modern React SPA UI** with TypeScript, Vite 7, and Ant Design 5
- **SAML and OIDC authentication** support (via Keycloak)
- **Full CMIS 1.1 compliance** verified with Apache Chemistry TCK

## Key Capabilities

* **CMIS ver1.1 compliant and CMIS-native server**
    * Easy integration with any CMIS-compliant client
    * Extended features: user/group management, archive, custom types
    * Highly customizable within CMIS specification

* **Enhanced Full-Text Search** (Apache Solr 9.x with Solr Cell)
    * PDF, Microsoft Office (Word, Excel, PowerPoint)
    * OpenDocument (Writer, Calc, Impress)
    * HTML, XML, RTF, plain text

* **NoSQL CouchDB backend**
    * Document-based storage with easy replication
    * Simple database management

## Etymology

"Nemaki" derives from the Japanese word "寝巻き" (pajamas/night clothes).
Relax and enjoy happy enterprise time as if you are lying on the couch in your room!

---

## Quick Start (Docker Compose)

### Prerequisites
- Docker and Docker Compose
- 4GB+ available memory

### 1. Build the Application

```bash
# Build UI
cd core/src/main/webapp/ui
npm install
npm run build

# Build Core WAR
cd ../../../..
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# Copy WAR to Docker directory
cp core/target/core.war docker/core/core.war
```

### 2. Start Services

```bash
cd docker
docker compose -f docker-compose-simple.yml up -d --build
```

This starts:
| Service | Port | Description |
|---------|------|-------------|
| CouchDB | 5984 | Document database |
| Solr | 8983 | Full-text search engine |
| Core | 8080 | CMIS server + React UI |

### 3. Wait for Startup

```bash
# Wait for NemakiWare to be ready (approximately 60-90 seconds)
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### 4. Access the Application

- **Web UI**: http://localhost:8080/core/ui/
- **CMIS Atom Binding**: http://localhost:8080/core/atom/bedroom
- **CMIS Browser Binding**: http://localhost:8080/core/browser/bedroom

**Default credentials**: `admin` / `admin`

### Stopping Services

```bash
cd docker
docker compose -f docker-compose-simple.yml down
```

### Rebuilding After Code Changes

```bash
# Rebuild and redeploy (important: use --build --force-recreate)
cp core/target/core.war docker/core/core.war
cd docker
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core
```

---

## Optional: Keycloak (SAML/OIDC Authentication)

For external authentication support:

```bash
cd docker
docker compose -f docker-compose.keycloak.yml up -d
```

Keycloak will be available at http://localhost:8088

---

## Development Environment (Jetty)

For debugging and rapid development without Docker:

### Prerequisites
- Java 17
- Maven 3.6+
- Docker (for CouchDB only)

### Setup

1. **Start CouchDB**
   ```bash
   docker run -d --name couchdb-dev -p 5984:5984 \
     -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password \
     couchdb:3
   ```

2. **Start Development Server**
   ```bash
   cd core
   ./start-jetty-dev.sh
   ```

**Note**: This mode uses MockSolrUtil (search disabled) for simplified development.

---

## Testing

### CMIS TCK Tests

```bash
# Run TCK tests (requires Docker environment running)
mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup \
  -f core/pom.xml -Pdevelopment
```

### Playwright E2E Tests

```bash
cd core/src/main/webapp/ui
npx playwright test --project=chromium
```

---

## Project Structure

```
NemakiWare/
├── core/                    # CMIS server (Spring + OpenCMIS)
│   └── src/main/webapp/ui/  # React SPA (TypeScript + Vite)
├── docker/                  # Docker Compose configurations
├── solr/                    # Solr search engine configuration
└── common/                  # Shared utilities
```

## Technical Stack

| Component | Technology |
|-----------|------------|
| Server | Tomcat 10.1 (Jakarta EE 10) |
| Framework | Spring 6, Apache Chemistry OpenCMIS |
| Database | CouchDB 3.x |
| Search | Apache Solr 9.x |
| UI | React 18, TypeScript, Vite 7, Ant Design 5 |
| Java | 17 (required) |

---

## License

Copyright (c) 2013-2026 aegif.

NemakiWare is Open Source software licensed under the **GNU Affero General Public License version 3**. See `legal/LICENSE` for details.
