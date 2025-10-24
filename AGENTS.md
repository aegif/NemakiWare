# Repository Guidelines

## Project Structure & Modules
- `common/` — Shared utilities (JAR).
- `core/` — CMIS REST/Web Services server (WAR, Jakarta EE 10, Spring 6).
  - `core/src/main/webapp/ui/` — React SPA UI (Vite + TypeScript + Ant Design)
  - `core/src/main/webapp/ui/tests/` — Playwright E2E tests
- `solr/` — Search integration helpers.
- `cloudant-init/` — CouchDB/Cloudant bootstrap tools.
- `docker/` — Compose files, images, and runtime config.
- `setup/`, `war_content/`, `WEB-INF/` — Installer and packaging assets.
- Tests live under `*/src/test/java`; reports under `*/test-reports/`.

## Build, Test, and Run

### Backend Build
- Build all modules: `mvn -T 1C -DskipTests install` (root `pom.xml`).
- Build server only: `mvn -pl core -am package` (produces `core/target/core.war`).
- Run dev server (Jetty 11): `cd core && ./start-jetty-dev.sh`.
  - Access CMIS: `http://localhost:8080/core/atom/bedroom` (admin:admin).
- Run tests (JUnit 4): `mvn test` or `mvn -pl core test`.

### React UI Build and Deployment

**Important**: React UI must be built and deployed to WAR file for production use.

```bash
# 1. Build React UI (from UI directory)
cd core/src/main/webapp/ui
npm install  # First time only
npm run build

# 2. Build core WAR with UI assets
cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# 3. Copy WAR to Docker directory
cp core/target/core.war docker/core/core.war

# 4. Deploy via Docker
cd docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# 5. Wait for startup (約90秒)
sleep 90

# 6. Verify UI is accessible
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ui/dist/index.html
# Expected: 200
```

### Docker Environment
- Full stack via Docker: `docker compose -f docker/docker-compose-simple.yml up -d`.
- Services: CouchDB (5984), Solr (8983), Tomcat Core (8080)
- Check status: `docker compose -f docker/docker-compose-simple.yml ps`
- View logs: `docker compose -f docker/docker-compose-simple.yml logs core`

### Playwright UI Tests

**Current Test Status** (2025-10-25):
- ✅ 69 tests passing (67%)
- ❌ 4 tests failing (4%)
- ⏭️ 30 tests skipped (29%)
- Total: 103 tests

**Running Tests**:
```bash
# All tests (single browser, sequential)
cd core/src/main/webapp/ui
npx playwright test --project=chromium --workers=1

# Specific test file
npx playwright test tests/versioning/document-versioning.spec.ts --project=chromium --workers=1

# Specific test case
npx playwright test tests/versioning/document-versioning.spec.ts:37 --project=chromium --workers=1

# Debug mode (with browser UI)
npx playwright test --project=chromium --debug

# Generate HTML report
npx playwright show-report
```

**Test Environment Requirements**:
- Docker containers must be running (see Docker Environment section)
- Server must be healthy: `http://localhost:8080/core/ui/dist/index.html` returns 200
- Recommended: Single worker (`--workers=1`) to avoid race conditions

## Coding Style & Naming
- Java 17; use Jakarta APIs (`jakarta.*`), avoid `javax.*`.
- Indentation: 4 spaces, UTF-8, 120-col soft wrap.
- Packages: `jp.aegif.nemaki...`; Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Prefer SLF4J (`org.slf4j.Logger`) over `System.out`.
- Module boundaries: put shared code in `common/`; CMIS/server code in `core/`.
- React/TypeScript: Follow standard TypeScript/React conventions, use functional components with hooks.

## Testing Guidelines

### JUnit Tests (Backend)
- Framework: JUnit 4 (Surefire configured with Java 17 module opens).
- Place tests in `src/test/java`; name files `*Test.java`.
- Keep unit tests fast and isolated (mock Solr when applicable).
- Useful scripts: `qa-test.sh`, `test-rest-api-comprehensive.sh`.

### Playwright Tests (UI)
- Framework: Playwright (TypeScript)
- Test files: `core/src/main/webapp/ui/tests/**/*.spec.ts`
- Test helpers: `tests/utils/auth-helper.ts`, `tests/utils/test-helper.ts`
- Configuration: `playwright.config.ts`

**Key Test Helpers**:
- `AuthHelper`: Login/logout, session management
- `TestHelper`: Ant Design element waiting, common UI interactions
- `uploadDocument()`: Document upload with retry logic

**Known Issues** (as of 2025-10-25):
1. **Document Versioning Tests** (4 failing):
   - check-in: Cleanup timeout
   - cancel check-out: Cleanup timeout
   - version history: Modal selector mismatch
   - version download: Filename mismatch

2. **Skipped Tests** (30 tests):
   - UI not implemented: Custom Type Creation, Group/User Management CRUD, Permission Management
   - Partial WIP: PDF Preview
   - Test issues: Access Control (test user timeout), Document Viewer Auth

**Important Fixes Applied** (2025-10-24):
1. ✅ AtomPub parser: Now extracts ALL CMIS properties (not just hardcoded 8)
2. ✅ Cache invalidation: checkout/cancelCheckout operations
3. ✅ deleteTree operation: Browser Binding support added
4. ✅ Versioning: cmis:document.versionable=true
5. ✅ Advanced Search: CMIS Browser Binding query syntax

## Commit & Pull Request Guidelines
- Use concise, imperative subjects. Conventional prefixes are common: `feat:`, `fix:`, `refactor:`, `chore:` (optionally add module tag, e.g., `[core] fix: ...`).
- Include context and rationale in the body; reference issues (`Fixes #123`).
- PRs should include: clear description, reproduction steps, test evidence (logs or report paths), config notes (e.g., CouchDB, ports), and any Docker compose variant used.

## Security & Configuration Tips
- Do not commit secrets. Local defaults: CouchDB `admin/password` (dev only).
- Primary config: `core/nemakiware.properties`, `docker/repositories.yml`.
- For Java 17, ensure `MAVEN_OPTS` includes required `--add-opens` (see `core/start-jetty-dev.sh`).

## Current Work Status (2025-10-25)

### Active Branch
- **Branch**: `vk/1620-ui` (merged from `origin/feature/react-ui-playwright`)
- **Related PR**: https://github.com/aegif/NemakiWare/pull/391
- **Focus**: UI test improvements and React UI enhancements

### Recent Improvements
1. **Merged 20 commits** from origin/feature/react-ui-playwright:
   - Document Versioning test improvements
   - AtomPub parser fixes
   - Cache invalidation for checkout operations
   - deleteTree operation support
   - CI/CD fixes

2. **Test Status**:
   - 69 passing (67%)
   - 4 failing (Document Versioning cleanup issues)
   - 30 skipped (UI not implemented or test issues)

### Next Steps
1. **High Priority**:
   - Fix Document Versioning test cleanup timeouts
   - Fix version history modal selector
   - Fix version download filename mismatch

2. **Medium Priority**:
   - Fix Access Control test user timeout
   - Fix Document Viewer Auth navigation issue
   - Improve CI/CD timeout handling (extend to 90 minutes)

3. **Low Priority**:
   - Implement missing UI features (Custom Type Creation, User/Group Management CRUD, etc.)
   - Complete PDF Preview functionality

### Reference Documents
- **HANDOFF-DOCUMENT.md**: Detailed session handoff with technical findings
- **PLAYWRIGHT-TEST-PROGRESS.md**: Test progress tracking
- **CLAUDE.md**: Comprehensive project documentation and history

### Quick Troubleshooting

**Docker not running**:
```bash
# Check Docker daemon status
docker ps

# If not running, start Docker Desktop (macOS) or systemctl (Linux)
# Then restart containers
cd docker && docker compose -f docker-compose-simple.yml up -d
```

**UI not accessible**:
```bash
# Verify core container is running
docker compose -f docker/docker-compose-simple.yml ps

# Check core logs
docker compose -f docker/docker-compose-simple.yml logs core

# Restart if needed
docker compose -f docker/docker-compose-simple.yml restart core
sleep 90
```

**Tests failing with timeout**:
- Check server is responding: `curl http://localhost:8080/core/ui/dist/index.html`
- Increase timeout in `playwright.config.ts`: `timeout: 60000`
- Use `--workers=1` to avoid race conditions
- Check Docker container logs for errors

**Build issues**:
```bash
# Clean rebuild
cd core/src/main/webapp/ui && npm run build
cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# Verify WAR file size (should be ~300MB)
ls -lh core/target/core.war
```
