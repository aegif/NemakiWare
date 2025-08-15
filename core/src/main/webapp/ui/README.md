Backend-enabled E2E

Prerequisites:
- Java 17, Maven 3.6+
- Docker (for CouchDB)
- NemakiWare backend running on http://localhost:8080

Local backend startup:
- Start CouchDB:
  docker run -d --name couchdb-dev -p 5984:5984 -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password couchdb:3
- Start Jetty from repo root:
  cd core
  export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
  mvn jetty:run -Pdevelopment -Djetty.port=8080

Run E2E against backend:
- cd core/src/main/webapp/ui
- export VITE_PROXY_TARGET=http://localhost:8080
- npx playwright install
- npm run test:e2e

Defaults:
- Repository: bedroom
- Credentials: admin / admin

# NemakiWare React UI

## Setup
- Node.js 18+
- Install deps:
  npm ci

## Dev
- Backend at http://localhost:8080
- Optional env:
  VITE_API_BASE=/core
  VITE_PROXY_TARGET=http://localhost:8080
- Start:
  npm run dev
- App base:
  http://localhost:5173/core/ui/

## Build
npm run build

## Type check
npm run type-check

## E2E (Playwright)
- Install browsers: npx playwright install
- Run tests (no backend): npm run test:e2e
- Headed: npm run test:e2e:headed

### Backend-enabled E2E
- Prereqs as above; start backend on :8080
- Env:
  - export VITE_PROXY_TARGET=http://localhost:8080
  - export E2E_WITH_BACKEND=true
- Run a subset:
  - npx playwright test tests/login-success.spec.ts
  - npx playwright test tests/documents.spec.ts
  - npx playwright test tests/search.spec.ts
  - npx playwright test tests/folder-crud.spec.ts
  - npx playwright test tests/permissions.spec.ts

### CI backend fallback (containerized)
If starting Jetty from source fails in CI, the workflow automatically falls back to spin up the containerized backend (couchdb + core) using docker-compose and rechecks readiness at http://localhost:8080/core/rest/all/repositories before running Playwright.

## Troubleshooting: Local backend build with OpenCMIS 1.2.0-SNAPSHOT

If building NemakiWare Core locally fails with messages about missing OpenCMIS 1.2.0-SNAPSHOT artifacts or the parent POM, install the local artifacts before running the Maven build:

1) Install OpenCMIS JARs (from the repository’s built-jars):
   JARS_DIR="build-workspace/chemistry-opencmis/built-jars"
   for j in "$JARS_DIR"/*-1.2.0-SNAPSHOT.jar; do \
     base="$(basename "$j" .jar | sed 's/-1\.2\.0-SNAPSHOT$//')"; \
     mvn -q install:install-file -Dfile="$j" \
       -DgroupId=org.apache.chemistry.opencmis \
       -DartifactId="$base" \
       -Dversion=1.2.0-SNAPSHOT \
       -Dpackaging=jar; \
   done

2) Install the OpenCMIS parent POM:
   POM_FILE="build-workspace/chemistry-opencmis/built-jars/chemistry-opencmis-1.2.0-SNAPSHOT.pom"
   if [ -f "$POM_FILE" ]; then
     mvn -q install:install-file -Dfile="$POM_FILE" \
       -DgroupId=org.apache.chemistry.opencmis \
       -DartifactId=chemistry-opencmis \
       -Dversion=1.2.0-SNAPSHOT \
       -Dpackaging=pom
   else
     TMP_POM="$(mktemp)"
     cat > "$TMP_POM" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.apache.chemistry.opencmis</groupId>
  <artifactId>chemistry-opencmis</artifactId>
  <version>1.2.0-SNAPSHOT</version>
  <packaging>pom</packaging>
</project>
EOF
     mvn -q install:install-file -Dfile="$TMP_POM" \
       -DgroupId=org.apache.chemistry.opencmis \
       -DartifactId=chemistry-opencmis \
       -Dversion=1.2.0-SNAPSHOT \
       -Dpackaging=pom
   fi

3) Install the suspended plugin (Nemaki Action) locally before the root build:
   mvn -q -DskipTests install -f suspended-modules/action/pom.xml

Then run the normal build:
   mvn -q -DskipTests install
