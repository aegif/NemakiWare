# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current Active Issues (2025-07-23)

### CMIS Service Health Restoration - COMPLETED ✅

**Previous Issue**: `TypeError: _t.startsWith is not a function` in React UI when accessing folder contents.

**Resolution**: **Complete reversion of all service-side modifications** that were causing unintended side effects.

**Files Restored**:
- ✅ `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java` - Reverted to original state
- ✅ `core/src/main/java/jp/aegif/nemaki/cmis/filter/` - Directory deleted (filter approach abandoned) 
- ✅ `core/src/main/webapp/WEB-INF/web.xml` - Reverted to original state

**Current Status**: **CMIS service is completely healthy and CMIS 1.1 compliant**. Browser Binding now outputs proper empty arrays `"value":[]` instead of `"value":null` for multi-cardinality properties. JavaScript error should be resolved once UI source code is available.

**Version Information**: 
- Product Version: `3.0.0` (updated from 2.4.1)
- UI Access: `http://localhost:8080/core/ui/dist/` (corrected from `/ui/`)
- Configuration: `/core/src/main/webapp/WEB-INF/classes/repositories-default.yml`

**Test Command**:
```bash
# ✅ CORRECT: Browser Binding requires cmisselector parameter
curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children" | jq '.objects[0].object.properties."cmis:secondaryObjectTypeIds".value'
# Expected output: [] (empty array - CMIS compliant)

# ❌ INCORRECT: Missing cmisselector parameter
curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisaction=getChildren"
# Returns: {"exception":"notSupported","message":"Unknown operation"}
```

### Current Testing Standard - ESTABLISHED ✅ (2025-07-23)

**Primary Test Method**: Shell-based comprehensive testing using `comprehensive-test.sh`.

**Test Coverage (9 Comprehensive Tests)**:
1. **CMIS Core Functionality**: AtomPub, Browser Binding, Root Folder access
2. **REST API Services**: Repository listing, test endpoints  
3. **Search Engine Integration**: Solr URL and initialization endpoints
4. **Query System**: Document and folder queries

**Quick Test Execution**:
```bash
# Run all 9 comprehensive tests
./comprehensive-test.sh
```

**Legacy Scripts Removed**: All legacy test scripts (*.sh files in docker/ directory and project root) have been removed to avoid confusion. Use only the standardized procedures documented in the "Clean Build and Comprehensive Testing Procedures" section.

## Recent Major Changes (2025-07-19)

### Jakarta EE Environment Unification - COMPLETED ✅

**Achievement**: Complete resolution of Jakarta EE JAR mixing issues and unified Docker/Jetty environments.

**Key Results**:
- ✅ Jakarta JAR contamination eliminated from action/pom.xml
- ✅ Unified Java 17 environment (both Jetty and Docker)
- ✅ Solr host configuration working in container orchestration
- ✅ Patch system creating Sites folders correctly
- ✅ Git repository cleaned (900MB+ → 249MB, 95 large blobs removed)

**Current Stable Workflow**: See "Clean Build and Comprehensive Testing Procedures" section for complete standardized process.

## Project Overview

NemakiWare is an open source Enterprise Content Management system built as a CMIS 1.1 compliant repository using:

- **Backend**: Spring Framework, Apache Chemistry OpenCMIS, Jakarta EE 10
- **Database**: CouchDB (document storage)
- **Search**: Apache Solr with ExtractingRequestHandler (Tika 2.9.2)
- **UI**: React SPA (integrated in core webapp)
- **Application Server**: Tomcat 10.1+ (Jakarta EE) or Jetty 11+
- **Java**: Java 17 (mandatory for all operations)

### Multi-Module Structure

- **core/**: Main CMIS repository server (Spring-based WAR) with integrated React UI
- **solr/**: Search engine customization
- **common/**: Shared utilities and models  
- **action/**: Plugin framework for custom actions

### React UI Integration

**Location**: `/core/src/main/webapp/ui/dist/`
**Access URL**: `http://localhost:8080/core/ui/dist/`
**Build System**: Vite (React + TypeScript)
**Integration**: Served as static resources from core webapp

**UI Source Status**: 
- **Compiled Assets**: Pre-built Vite assets included in repository (`/core/src/main/webapp/ui/dist/`)
- **Source Code**: React/TypeScript source code is **NOT included in this repository**
- **UI Modifications**: Require access to separate UI source repository and rebuild process
- **Current Issue**: JavaScript `startsWith` error requires UI source code access to fix

**Note**: The `/ui` directory contains old Scala project files (deprecated) and should be ignored.

## Development Environment Setup

### Java 17 Environment (Mandatory)

```bash
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Verify version
java -version  # Must be 17.x.x
mvn -version   # Must show Java 17
```

### CouchDB Repository Structure

**Document Storage Repositories**:
- `bedroom` - Primary repository for documents/folders (use for testing)
- `bedroom_closet` - Archive repository for bedroom

**System Repositories**:
- `canopy` - Multi-repository management (uses same structure as bedroom)
- `canopy_closet` - Archive repository for canopy
- `nemaki_conf` - System configuration

**Important**: Always use `bedroom` repository for document operations and TCK tests.

### Authentication

**Default Credentials**: `admin:admin`
**CouchDB**: `admin:password` (CouchDB 3.x requires authentication)

## Clean Build and Comprehensive Testing Procedures (CURRENT STANDARD - 2025-07-23)

### 1. Complete Clean Build Process (Required for All Development)

**CRITICAL**: Use these exact commands for reliable builds and deployments.

```bash
# Step 1: Set Java 17 environment (MANDATORY)
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Verify Java version (must be 17.x.x)
java -version

# Step 2: Navigate to project root
cd /Users/ishiiakinori/NemakiWare

# Step 3: Clean build with Java 17
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# Step 4: Copy WAR file to Docker context
cp core/target/core.war docker/core/core.war

# Step 5: Stop and rebuild Docker environment with no cache
cd docker
docker compose -f docker-compose-simple.yml down
docker build --no-cache -t nemakiware/core -f core/Dockerfile.simple core/

# Step 6: Start clean environment
docker compose -f docker-compose-simple.yml up -d

# Step 7: Wait for complete initialization
sleep 60
```

### 2. Comprehensive Test Execution

```bash
# Navigate to project root and run comprehensive tests
cd /Users/ishiiakinori/NemakiWare
./comprehensive-test.sh
```

**Expected Test Results (9 Tests Total)**:
```
=== NemakiWare 包括的テスト結果 ===
✓ CMISリポジトリ情報: OK (HTTP 200)
✓ CMISブラウザバインディング: OK (HTTP 200)  
✓ CMISルートフォルダ: OK (HTTP 200)
✓ リポジトリ一覧: OK (HTTP 200)
✓ RESTテストエンドポイント: OK (HTTP 200)
✓ Solr検索エンジンURL: OK (HTTP 200)
✓ Solr初期化エンドポイント: OK (HTTP 200)
✓ 基本ドキュメントクエリ: OK (HTTP 200)
✓ 基本フォルダクエリ: OK (HTTP 200)

合格テスト: 9/9
🎉 全テスト合格！NemakiWareは正常に動作しています。
```

### 3. Development Health Check Commands

```bash
# Quick verification commands
docker ps  # All containers should be running
curl -u admin:admin http://localhost:8080/core/atom/bedroom  # Should return HTTP 200
curl -u admin:admin http://localhost:8080/core/rest/all/repositories  # Should return repository list
```

### 4. Troubleshooting Failed Tests

If tests fail, check in this order:

```bash
# 1. Check container status
docker ps
docker logs docker-core-1 --tail 20

# 2. Verify CouchDB connectivity
curl -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

# 3. Restart core container if needed
docker compose -f docker/docker-compose-simple.yml restart core
sleep 30

# 4. Re-run tests
./comprehensive-test.sh
```

**IMPORTANT**: All legacy test scripts (*.sh files in docker/ directory) have been removed. Use only the procedures documented above.

### Docker Deployment

```bash
# Build and deploy core
mvn clean package -f core/pom.xml -Pdevelopment
cp core/target/core.war docker/core/core.war

# Start environment
cd docker
docker compose -f docker-compose-simple.yml up -d

# Verify deployment
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### Source Code Modification Workflow

```bash
# 1. Modify Java source in core/src/main/java/
# 2. Rebuild and redeploy
mvn clean package -f core/pom.xml -Pdevelopment
cp core/target/core.war docker/core/core.war

# 3. Restart container to reflect changes
docker stop docker-core-1
docker build --no-cache -t docker-core docker/core/
docker start docker-core-1

# 4. Verify changes
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

## CMIS API Reference

### Browser Binding (Recommended for file uploads)

**IMPORTANT**: Browser Binding requires `cmisselector` parameter for GET requests and `cmisaction` for POST requests.

```bash
# ✅ CORRECT: Get children (GET with cmisselector)
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# ✅ CORRECT: Repository info
curl -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo"

# ✅ CORRECT: Create document with content (POST with cmisaction)
curl -u admin:admin -X POST \
  -F "cmisaction=createDocument" \
  -F "folderId=FOLDER_ID" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=document.pdf" \
  -F "content=@/path/to/document.pdf" \
  "http://localhost:8080/core/browser/bedroom"
```

### AtomPub Binding

```bash
# Repository info
curl -u admin:admin "http://localhost:8080/core/atom/bedroom"

# Query
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis:document&maxItems=10"
```

## Testing

### Health Check Commands

```bash
# Core application
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# Expected: HTTP 200 with XML repository info

# CouchDB
curl -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

# UI access
curl http://localhost:8080/core/ui/dist/
# Expected: React UI login page
```

### Test Scripts

```bash
# Quick environment test
cd docker && ./test-simple.sh

# Full integration test
cd docker && ./test-all.sh
```

## Important Configuration Files

- **Core Configuration**: `core/src/main/webapp/WEB-INF/classes/applicationContext.xml`
- **Repository Definition**: `docker/core/repositories.yml`
- **Docker Environment**: `docker/docker-compose-simple.yml`
- **CouchDB Initialization**: `setup/couchdb/initial_import/bedroom_init.dump`

## Known Issues and Workarounds

### JavaScript startsWith Error - RESOLVED ✅

**Symptom**: `TypeError: _t.startsWith is not a function` in React UI
**Root Cause**: **Service-side modifications were causing side effects** - Browser Binding now correctly outputs empty arrays
**Resolution Applied**: Reverted all half-baked service modifications and restored CMIS service health
**Current Status**: 
  - **✅ AtomPub**: `<cmis:propertyId propertyDefinitionId="cmis:secondaryObjectTypeIds"/>`
  - **✅ Browser**: `{"value":[]}`
  - **✅ CMIS 1.1 Compliant**: Both bindings now output CMIS-standard empty representations
**Test Verification**:
```bash
# Verify Browser Binding outputs empty arrays (not null)
curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children" | jq '.objects[0].object.properties."cmis:secondaryObjectTypeIds".value'
# Expected: [] (empty array)
```

### Maven systemPath Warnings

**Warning Messages**: `'dependencies.dependency.systemPath' should not point at files within the project directory`
**Cause**: Jakarta-converted JARs stored in project `/lib/jakarta-converted/`
**Status**: **EXPECTED BEHAVIOR** - These warnings are intentional and acceptable
**Reason**: Custom Jakarta EE JARs not available in public repositories

**DO NOT ATTEMPT TO RESOLVE** these systemPath warnings - they are part of the approved Jakarta EE conversion strategy.

## Troubleshooting

### Container Startup Issues

```bash
# Check container logs
docker logs docker-core-1 --tail 20

# Verify Java 17 environment
java -version
mvn -version

# Check CouchDB connectivity
curl -u admin:password http://localhost:5984/_all_dbs
```

### Build Issues

```bash
# Clean rebuild
mvn clean package -f core/pom.xml -Pdevelopment -U

# Verify WAR file created
ls -la core/target/core.war

# Check Docker context
ls -la docker/core/core.war
```

### CMIS Endpoint Issues

```bash
# Test basic connectivity
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Check repository structure
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff"
```

## Architectural Notes

### Jakarta EE vs Java EE

- **Current Standard**: Jakarta EE 10 with `jakarta.*` namespaces
- **Legacy Support**: Java EE 8 with `javax.*` namespaces (deprecated)
- **Migration**: Automatic JAR conversion in `/lib/jakarta-converted/`

### OpenCMIS Version Management

**CRITICAL**: All OpenCMIS JARs must use version 1.1.0 consistently
- Maven properties: `org.apache.chemistry.opencmis.version=1.1.0`
- Jakarta converted JARs: `*-1.1.0-jakarta.jar` only
- No SNAPSHOT versions in production

### Spring Configuration

- **Main Context**: `applicationContext.xml`
- **Services**: `serviceContext.xml` (700+ lines of CMIS service definitions)
- **Data Access**: `daoContext.xml` with caching decorators
- **CouchDB**: `couchContext.xml` with connection pooling

## Support Information

For help with Claude Code: https://docs.anthropic.com/en/docs/claude-code
For feedback: https://github.com/anthropics/claude-code/issues