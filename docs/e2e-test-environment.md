# E2E Test Environment Setup and Troubleshooting

## Overview

This document describes how to set up a clean E2E test environment for NemakiWare Playwright tests and how to troubleshoot common issues.

## Root Cause: Initial Content Setup Test Failures

### Symptom
Initial content setup tests fail claiming that "Sites" and "Technical Documents" folders are missing from the root folder.

### Root Cause
The failures were **environmental issues**, not code bugs:

1. **Stale Docker volumes**: Previous test runs left incomplete or corrupted CouchDB data
2. **Container startup race conditions**: Core container started before CouchDB/Solr were fully ready, causing incomplete initialization
3. **Outdated Docker images**: New WAR file changes were not reflected in the Docker image

### Evidence
After a complete clean rebuild, logs showed successful initialization:

```
13:34:01.407 [main] ERROR jp.aegif.nemaki.patch.Patch_InitialContentSetup - === CREATING SITES FOLDER ===
13:34:01.624 [main] ERROR jp.aegif.nemaki.patch.Patch_InitialContentSetup - SUCCESS: Folder 'Sites' created successfully with ID: 636e6146168ed7e57165cfd9ac001a97
13:34:01.727 [main] ERROR jp.aegif.nemaki.patch.Patch_InitialContentSetup - SUCCESS: Folder 'Technical Documents' created successfully with ID: 636e6146168ed7e57165cfd9ac002718
13:34:02.307 [main] ERROR jp.aegif.nemaki.patch.Patch_InitialContentSetup - === INITIAL CONTENT SETUP PATCH COMPLETED SUCCESSFULLY for repository: bedroom ===
```

On subsequent restarts, the patch correctly detected existing folders:
```
13:34:12.070 [main] ERROR jp.aegif.nemaki.patch.Patch_InitialContentSetup - Folder 'Sites' already exists with ID: 636e6146168ed7e57165cfd9ac001a97 (preserving from previous version)
```

Before the clean rebuild, logs showed view initialization errors:
```
13:34:01.338 [main] ERROR jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl - Error getting patch history by name: initial-content-setup-20251005, error: Cannot invoke "com.ibm.cloud.cloudant.v1.model.ViewResult.getRows()" because "result" is null
```

This indicates CouchDB views/indexes were not ready when Core started, causing incomplete initialization.

## Prevention Measures

### 1. Complete Environment Reset (Recommended Before Test Runs)

Always perform a complete clean rebuild before running E2E tests:

```bash
# Navigate to project root
cd /path/to/NemakiWare

# Step 1: Clean build WAR file
mvn clean package -DskipTests

# Step 2: Navigate to docker directory
cd docker

# Step 3: Rebuild Docker image with new WAR
docker-compose -f docker-compose-simple.yml build core

# Step 4: Stop containers and wipe all volumes (IMPORTANT!)
docker-compose -f docker-compose-simple.yml down -v

# Step 5: Start containers in correct order
# Start CouchDB first
docker-compose -f docker-compose-simple.yml up -d couchdb

# Wait for CouchDB to be ready
timeout 60 bash -c 'until docker exec docker-couchdb-1 curl -s http://localhost:5984/_up | grep -q "ok"; do echo "Waiting for CouchDB..."; sleep 2; done && echo "CouchDB is ready!"'

# Start Solr
docker-compose -f docker-compose-simple.yml up -d solr

# Wait for Solr to be ready
timeout 60 bash -c 'until docker exec docker-solr-1 curl -s http://localhost:8983/solr/admin/cores?action=STATUS | grep -q "status"; do echo "Waiting for Solr..."; sleep 2; done && echo "Solr is ready!"'

# Start Core
docker-compose -f docker-compose-simple.yml up -d core

# Wait for Core to be ready
timeout 120 bash -c 'until curl -s http://localhost:8080/core/browser/bedroom/root | grep -q "cmis:objectId"; do echo "Waiting for Core..."; sleep 3; done && echo "Core is ready!"'
```

### 2. Pre-Test Validation

Before running the full Playwright test suite, validate that the initial content setup completed successfully:

```bash
# Navigate to UI directory
cd core/src/main/webapp/ui

# Run only initial-content-setup tests first (fast validation)
PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test tests/admin/initial-content-setup.spec.ts --project=chromium

# If these pass, run the full suite
PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test
```

### 3. CMIS API Validation

Verify that Sites and Technical Documents folders exist via CMIS API:

```bash
# Query root folder children
curl -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children' | jq '.objects[] | select(.object.properties["cmis:name"].value == "Sites" or .object.properties["cmis:name"].value == "Technical Documents") | {name: .object.properties["cmis:name"].value, id: .object.properties["cmis:objectId"].value}'
```

Expected output:
```json
{
  "name": "Sites",
  "id": "636e6146168ed7e57165cfd9ac001a97"
}
{
  "name": "Technical Documents",
  "id": "636e6146168ed7e57165cfd9ac002718"
}
```

If these folders are missing, perform a complete environment reset (see step 1).

### 4. WebKit Browser Support

For Playwright tests to run WebKit and Mobile Safari tests on Linux, you must set the following environment variable:

```bash
export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1
```

This bypasses Playwright's host requirements validation which incorrectly reports missing dependencies on some Linux environments.

## Docker Compose Healthchecks (Recommended)

To automatically enforce correct container startup order, add healthchecks to `docker-compose-simple.yml`:

```yaml
services:
  couchdb:
    # ... existing config ...
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5984/_up"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  solr:
    # ... existing config ...
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8983/solr/admin/cores?action=STATUS"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    depends_on:
      couchdb:
        condition: service_healthy

  core:
    # ... existing config ...
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/core/browser/bedroom/root"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 60s
    depends_on:
      couchdb:
        condition: service_healthy
      solr:
        condition: service_healthy
```

With these healthchecks, you can simply run:
```bash
docker-compose -f docker-compose-simple.yml up -d
```

And Docker Compose will automatically start containers in the correct order and wait for each to be healthy.

## Makefile Target (Optional)

Add a Makefile target for easy environment reset:

```makefile
.PHONY: reset-test-env
reset-test-env:
	@echo "Resetting test environment..."
	mvn clean package -DskipTests
	cd docker && docker-compose -f docker-compose-simple.yml build core
	cd docker && docker-compose -f docker-compose-simple.yml down -v
	cd docker && docker-compose -f docker-compose-simple.yml up -d couchdb
	@echo "Waiting for CouchDB..."
	@timeout 60 bash -c 'until docker exec docker-couchdb-1 curl -s http://localhost:5984/_up | grep -q "ok"; do sleep 2; done'
	cd docker && docker-compose -f docker-compose-simple.yml up -d solr
	@echo "Waiting for Solr..."
	@timeout 60 bash -c 'until docker exec docker-solr-1 curl -s http://localhost:8983/solr/admin/cores?action=STATUS | grep -q "status"; do sleep 2; done'
	cd docker && docker-compose -f docker-compose-simple.yml up -d core
	@echo "Waiting for Core..."
	@timeout 120 bash -c 'until curl -s http://localhost:8080/core/browser/bedroom/root | grep -q "cmis:objectId"; do sleep 3; done'
	@echo "Test environment ready!"

.PHONY: test-e2e
test-e2e: reset-test-env
	@echo "Running E2E tests..."
	cd core/src/main/webapp/ui && PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test
```

Usage:
```bash
make reset-test-env  # Reset environment only
make test-e2e        # Reset environment and run tests
```

## Troubleshooting

### Issue: Tests fail with "Sites folder not found"

**Solution**: Perform a complete environment reset (see step 1 above). The issue is caused by stale Docker volumes or incomplete initialization.

### Issue: Core container fails to start

**Solution**: Check that CouchDB and Solr are running and healthy before starting Core:
```bash
docker ps  # Check container status
docker logs docker-couchdb-1  # Check CouchDB logs
docker logs docker-solr-1     # Check Solr logs
docker logs docker-core-1     # Check Core logs
```

### Issue: WebKit/Mobile Safari tests fail with "Browser not found"

**Solution**: Set the environment variable:
```bash
export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1
```

### Issue: Tests are flaky or inconsistent

**Solution**: Always perform a complete environment reset before running tests. Do not reuse containers from previous test runs.

## CI/CD Integration

For CI/CD pipelines, always include these steps:

1. Build WAR file: `mvn clean package -DskipTests`
2. Build Docker image: `docker-compose build core`
3. Reset volumes: `docker-compose down -v`
4. Start containers with healthchecks: `docker-compose up -d`
5. Validate initial content: Run `tests/admin/initial-content-setup.spec.ts` first
6. Run full test suite: `PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test`

## Summary

The key to reliable E2E testing is:

1. **Always start with a clean environment** (`docker-compose down -v`)
2. **Rebuild Docker images** after code changes (`docker-compose build core`)
3. **Start containers in correct order** (CouchDB → Solr → Core)
4. **Wait for each service to be healthy** before starting the next
5. **Validate initial content** before running full test suite
6. **Use WebKit environment variable** for Linux environments

Following these steps will prevent the initial content setup failures and ensure reliable test results.
