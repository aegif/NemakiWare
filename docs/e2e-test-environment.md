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
docker compose -f docker-compose-simple.yml build core

# Step 4: Stop containers and wipe all volumes (IMPORTANT!)
docker compose -f docker-compose-simple.yml down -v

# Step 5: Start all containers (healthcheck により起動順序は自動制御)
docker compose -f docker-compose-simple.yml up -d

# Wait for Core to be ready (healthcheck 完了後にAPIが応答する)
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

## Docker Compose Healthchecks (導入済み)

`docker-compose-simple.yml` には既に healthcheck と `depends_on` + `condition: service_healthy` が定義済みです。
各サービスが HTTP エンドポイントで応答するまで起動完了とみなさないため、
「CouchDB が起動する前に Core が接続を試みて失敗する」といった起動順序の問題を防止します。
起動順序は CouchDB → Solr → Core で自動制御されます。

```bash
docker compose -f docker-compose-simple.yml up -d
```

手動での起動順序制御は不要です。

## 環境リセット・検証スクリプト

環境検証には `scripts/validate-test-env.sh` を使用します:

```bash
./scripts/validate-test-env.sh
```

完全リセットは以下の手順で行います:

```bash
cd docker
docker compose -f docker-compose-simple.yml down -v
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

## Troubleshooting

### Issue: Tests fail with "Sites folder not found"

**Solution**: Perform a complete environment reset (see step 1 above). The issue is caused by stale Docker volumes or incomplete initialization.

### Issue: Core container fails to start

**Solution**: Check that CouchDB and Solr are running and healthy before starting Core:
```bash
docker compose -f docker-compose-simple.yml ps     # Check container status
docker compose -f docker-compose-simple.yml logs couchdb  # Check CouchDB logs
docker compose -f docker-compose-simple.yml logs solr     # Check Solr logs
docker compose -f docker-compose-simple.yml logs core     # Check Core logs
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
2. Build Docker image: `docker compose -f docker-compose-simple.yml build core`
3. Reset volumes: `docker compose -f docker-compose-simple.yml down -v`
4. Start containers with healthchecks: `docker compose -f docker-compose-simple.yml up -d`
5. Validate initial content: Run `tests/admin/initial-content-setup.spec.ts` first
6. Run full test suite: `npx playwright test --project=chromium`

## Summary

The key to reliable E2E testing is:

1. **Always start with a clean environment** (`docker compose down -v`)
2. **Rebuild Docker images** after code changes (`docker compose build core`)
3. **Start containers in correct order** (CouchDB → Solr → Core, healthcheck で自動化済み)
4. **Wait for each service to be healthy** before starting the next
5. **Validate initial content** before running full test suite
6. **Use WebKit environment variable** for Linux environments

Following these steps will prevent the initial content setup failures and ensure reliable test results.
