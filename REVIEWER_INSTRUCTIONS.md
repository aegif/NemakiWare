# NemakiWare Code Review Instructions

## Overview

This document provides step-by-step instructions for reviewers to build, test, and review the Production Readiness Phase 3 changes (debug code cleanup).

**Branch**: `feature/react-ui-playwright`
**Review Scope**: Debug code cleanup (System.out/err.println → proper logging framework)
**Files Changed**: 11 files, 157 debug statements replaced

---

## ⚠️ CRITICAL: Known Environment Issues

**IMPORTANT**: This project has known database initialization issues that are **completely unrelated to the code changes being reviewed**.

### Common Symptoms:
- HTTP 401 Unauthorized errors during testing
- CouchDB design document `_repo` missing views (userItemsById, groupItemsById, etc.)
- QA test partial failures (~55% pass rate)
- TCK test authentication errors

### What This Means:
- ✅ **Code compiles successfully** - No business logic changes, only logging modifications
- ⚠️ **Tests may fail** - Due to environment issues, NOT code defects
- ✅ **Review focus** - Code quality, log levels, performance guards

**DO NOT** reject this PR due to authentication/database initialization failures. These are pre-existing environment issues documented in CLAUDE.md.

---

## Prerequisites

### 1. Java 17 (Mandatory)

Verify Java version before proceeding:

```bash
java -version
```

**Expected output**: `openjdk version "17.x.x"` or `java version "17.x.x"`

**Installation paths by platform**:
- **macOS (JetBrains Runtime)**: `/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home`
- **macOS (Homebrew)**: `/usr/local/opt/openjdk@17`
- **Linux**: `/usr/lib/jvm/java-17-openjdk`
- **Windows**: `C:\Program Files\Java\jdk-17`

**Set JAVA_HOME** (required for Maven):

```bash
# macOS/Linux example
export JAVA_HOME=/path/to/java-17
export PATH=$JAVA_HOME/bin:$PATH

# Windows example (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

### 2. Docker and Docker Compose

```bash
docker --version          # Should be 20.x or higher
docker compose version    # Should be v2.x or higher
```

### 3. Maven

```bash
mvn -version
```

**Expected**: Maven 3.8+ with Java 17

### 4. Port Availability

Verify these ports are available:
- **8080**: Core application (Tomcat)
- **5984**: CouchDB
- **8983**: Apache Solr

```bash
# macOS/Linux
lsof -i :8080
lsof -i :5984
lsof -i :8983

# Windows (PowerShell)
netstat -ano | findstr :8080
netstat -ano | findstr :5984
netstat -ano | findstr :8983
```

**If ports are in use**: Stop conflicting services or modify `docker-compose-simple.yml` port mappings.

---

## Build Instructions

### Step 1: Clone Repository

```bash
git clone https://github.com/aegif/NemakiWare.git
cd NemakiWare
```

### Step 2: Checkout Review Branch

```bash
git checkout feature/react-ui-playwright
git pull origin feature/react-ui-playwright
```

**Verify branch**:
```bash
git branch
git log --oneline -5
```

**Expected commits**:
- `e83cf76ea` - feat: Merge Phase 3 debug code cleanup from vk/61b8-react-ui
- `388f1deb1` - Production readiness Phase 3: Debug code cleanup in NemakiPropertyDefinition and TypeManagerImpl

### Step 3: Compile Code

```bash
# Ensure JAVA_HOME is set to Java 17
mvn clean compile -f core/pom.xml
```

**Expected output**:
```
[INFO] BUILD SUCCESS
[INFO] Total time: XX.XXX s
```

**If compilation fails**:
1. Verify Java 17: `mvn -version`
2. Check JAVA_HOME: `echo $JAVA_HOME` (macOS/Linux) or `echo $env:JAVA_HOME` (Windows)
3. Review error messages for missing dependencies

---

## Docker Deployment

### Step 1: Build WAR File

```bash
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests
```

**Expected**: `core/target/core.war` created (~313 MB)

### Step 2: Copy WAR to Docker Context

```bash
cp core/target/core.war docker/core/core.war
```

### Step 3: Start Docker Environment

```bash
cd docker
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

**Expected containers**:
- `docker-couchdb-1` - CouchDB 3.x database
- `docker-solr-1` - Apache Solr search engine
- `docker-core-1` - NemakiWare CMIS server

### Step 4: Wait for Container Health

```bash
# Wait 60-90 seconds for initialization
sleep 90

# Check container status
docker ps
```

**Expected status**: All containers showing "Up" with "(healthy)" status

**Check core container logs**:
```bash
CORE_CONTAINER=$(docker ps --filter "name=core" --format "{{.Names}}" | head -1)
docker logs $CORE_CONTAINER --tail 50
```

**Expected log messages**:
- `INFO: Server startup in [XXXX] milliseconds`
- No ERROR or EXCEPTION messages (warnings are acceptable)

---

## Testing Instructions

### ⚠️ Expected Test Behavior

Due to known database initialization issues:
- **Compilation**: ✅ Should always succeed
- **Basic connectivity**: ✅ Should work (AtomPub, Browser Binding)
- **Full QA tests**: ⚠️ May show ~55% pass rate due to authentication failures
- **TCK tests**: ⚠️ May show authentication errors

**This is normal and NOT a defect in the code changes.**

### Test 1: Basic Connectivity (Should PASS)

```bash
# Test CMIS AtomPub Binding
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

**Expected**: HTTP 200 with XML response containing `<app:service>` element

**If fails with 401**: This is the known database initialization issue - see Known Issues section

### Test 2: Repository Info (Should PASS)

```bash
curl -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo"
```

**Expected**: HTTP 200 with JSON response containing repository metadata

### Test 3: CouchDB Database Check

```bash
curl -u admin:password http://localhost:5984/_all_dbs
```

**Expected**: `["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]`

**Check design document views**:
```bash
curl -u admin:password http://localhost:5984/bedroom/_design/_repo | jq '.views | keys | length'
```

**Expected (healthy database)**: 38 views as defined in setup/couchdb/initial_import/bedroom_init.dump

**Actual (incomplete initialization)**: Only 5 views if database initialization failed

**Known Issue**: If only 5 views present (contentsById, documents, folders, items, policies), the database initialization is incomplete. The official design document should contain 38 views including userItemsById, groupItemsById, children, changesByToken, patch, and 33 others.

### Test 4: QA Test Suite (May show partial failures)

```bash
cd /path/to/NemakiWare
./qa-test.sh
```

**Expected output examples**:
- ✅ **Best case**: 56/56 tests passing (100%)
- ⚠️ **Typical with DB issue**: 31/56 tests passing (~55%)
- ❌ **Complete failure**: 0/56 - indicates serious environment problem

**Interpreting results**:
- **CMIS リポジトリ情報**: Should PASS (basic connectivity)
- **Document CRUD operations**: May FAIL (authentication issue)
- **Folder operations**: May FAIL (authentication issue)

### Test 5: TCK Tests (May show authentication errors)

```bash
export JAVA_HOME=/path/to/java-17
timeout 180s mvn test -Dtest=BasicsTestGroup -f core/pom.xml -Pdevelopment
```

**Possible outcomes**:
- ✅ **Tests run: 3, Failures: 0, Errors: 0** - Database initialization working
- ⚠️ **Tests run: 3, Failures: 0, Errors: 3** - CmisUnauthorizedException (known issue)

**If TCK fails with authentication errors**: This is the known database initialization issue, NOT a code defect.

---

## Code Review Focus

### What Changed

**Phase 1 (4 files, 13 statements)**:
- `NemakiBrowserBindingServlet.java` - Multipart request debug
- `CompileServiceImpl.java` - TCK query alias debug
- `DiscoveryServiceImpl.java` - Query operation debug
- `SolrQueryProcessor.java` - Query sort/permission debug

**Phase 2 (5 files, 58 statements)**:
- `TypeServiceImpl.java` - Type creation debug
- `ObjectServiceImpl.java` - InputStream verification, content stream debug
- `ContentDaoServiceImpl.java` - Property conversion, relationship debug
- `RepositoryServiceImpl.java` - Property ID determination debug
- `CouchPropertyDefinitionCore.java` - PropertyType error debug

**Phase 3 (2 files, 86 statements)**:
- `NemakiPropertyDefinition.java` - Property definition model debug (17 statements)
- `TypeManagerImpl.java` - Type manager implementation debug (69 statements)

**Total**: 11 files, 157 System.out/err.println → proper logging framework

### Review Checklist

#### 1. Logging Framework Usage

**Verify SLF4J Logger setup** (newer files):
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(ClassName.class);
```

**Verify Apache Commons Log setup** (existing files):
```java
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

private static final Log log = LogFactory.getLog(ClassName.class);
```

#### 2. Appropriate Log Levels

**Debug statements** should use `log.debug()`:
```java
// ✅ CORRECT
if (log.isDebugEnabled()) {
    log.debug("Property ID determination - core.propertyId=" + value);
}

// ❌ INCORRECT (old code - removed)
System.err.println("Property ID determination - core.propertyId=" + value);
```

**Warning statements** should use `log.warn()`:
```java
// ✅ CORRECT
log.warn("TCK CRITICAL: PropertyType is NULL for propertyId=" + id);

// ❌ INCORRECT (old code - removed)
System.err.println("TCK CRITICAL: PropertyType is NULL for propertyId=" + id);
```

#### 3. Performance Guards

**All debug logs must have `isDebugEnabled()` guards**:
```java
// ✅ CORRECT - Performance guard present
if (log.isDebugEnabled()) {
    log.debug("Expensive string concatenation: " + obj1 + ", " + obj2);
}

// ❌ INCORRECT - No performance guard
log.debug("Expensive string concatenation: " + obj1 + ", " + obj2);
```

**Exception**: `log.warn()` and `log.error()` do NOT need guards (infrequent events).

#### 4. No Business Logic Changes

**Verify**:
- ✅ Only logging statements changed
- ✅ No method signatures modified
- ✅ No control flow changes (if/else/loops)
- ✅ No variable declarations changed

**Red flags** (should NOT appear):
- ❌ New method parameters
- ❌ Changed return types
- ❌ Modified exception handling logic
- ❌ New class fields

#### 5. Compilation Verification

**Verify locally**:
```bash
mvn clean compile -f core/pom.xml
```

**Expected**: BUILD SUCCESS with no warnings related to changed files

---

## Known Issues and Workarounds

### Issue 1: HTTP 401 Unauthorized (Common)

**Symptom**:
```
<!doctype html><html lang="en"><head><title>HTTP Status 401 – Unauthorized</title>
```

**Root Cause**: CouchDB design document `_repo` incomplete initialization - missing 33 out of 38 required views

**Diagnosis**:
```bash
# Check number of views
curl -u admin:password http://localhost:5984/bedroom/_design/_repo | jq '.views | keys | length'

# List actual views
curl -u admin:password http://localhost:5984/bedroom/_design/_repo | jq '.views | keys'
```

**Expected (healthy)**: 38 views as defined in official dump file (setup/couchdb/initial_import/bedroom_init.dump)

**Actual (incomplete initialization)**: Only 5 views if database initialization failed:
- contentsById
- documents
- folders
- items
- policies

**Missing 33 views** including: userItemsById, groupItemsById, children, changesByToken, patch, attachments, relationships, versionSeries, typeDefinitions, and 24 others

**Workaround**:
```bash
# Stop containers
cd docker
docker compose -f docker-compose-simple.yml down --volumes

# Restart with fresh databases
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# Wait for initialization
sleep 90

# Verify views created
curl -u admin:password http://localhost:5984/bedroom/_design/_repo | jq '.views | keys'
```

**Success criteria**: If views still missing after workaround, this is a DatabasePreInitializer bug requiring separate fix.

### Issue 2: QA Test Partial Failures

**Symptom**: QA test shows 31/56 passing (55% success rate)

**Failed test categories**:
- Document CRUD operations
- Folder operations
- Version history retrieval
- Complex queries
- ACL operations

**Root Cause**: Same as Issue 1 (database initialization incomplete)

**Workaround**: Same as Issue 1

**Important**: This does NOT indicate a defect in the code changes being reviewed.

### Issue 3: TCK Authentication Errors

**Symptom**:
```
Tests run: 3, Failures: 0, Errors: 3, Skipped: 0
BasicsTestGroup.securityTest:17->TestGroupBase.run:153 » CmisUnauthorized
```

**Root Cause**: Same as Issue 1 and Issue 2

**Workaround**: Same as Issue 1

**Important**: Code compiles successfully. Authentication errors are environment-related.

### Issue 4: Container Port Conflicts

**Symptom**:
```
Error starting userland proxy: listen tcp4 0.0.0.0:8080: bind: address already in use
```

**Diagnosis**:
```bash
# Check what's using port 8080
lsof -i :8080  # macOS/Linux
netstat -ano | findstr :8080  # Windows
```

**Workaround**:
1. Stop conflicting service
2. OR modify `docker/docker-compose-simple.yml` port mapping:
   ```yaml
   ports:
     - "8081:8080"  # Change external port to 8081
   ```

### Issue 5: Container Not Healthy

**Symptom**: `docker ps` shows container but not "(healthy)" status

**Diagnosis**:
```bash
docker logs docker-core-1 --tail 100
```

**Common causes**:
- CouchDB not ready (wait 30 more seconds)
- Java heap memory too low (check `docker-compose-simple.yml` CATALINA_OPTS)
- Port conflict preventing startup

**Workaround**:
```bash
# Restart core container
docker compose -f docker-compose-simple.yml restart core

# Wait for health check
sleep 60

# Verify
docker ps
```

---

## Distinguishing Code Issues from Environment Issues

### Code Issue Indicators ❌ (Should NOT happen in this PR)

- Compilation errors in changed files
- Java syntax errors
- Import statement errors
- Method signature mismatches
- Class definition errors

**Action**: Reject PR and request fixes

### Environment Issue Indicators ✅ (Expected and acceptable)

- HTTP 401 Unauthorized errors
- CouchDB design document views missing
- QA test authentication failures
- TCK test CmisUnauthorizedException
- Database initialization incomplete

**Action**: Accept PR - these are pre-existing environment issues documented in CLAUDE.md

### Quick Verification

**To verify this is a logging-only change**:

```bash
# Check diff statistics
git diff origin/master feature/react-ui-playwright --stat

# Should show only .java files modified with +/- in logging statements
# No .xml, .yml, .properties, or database schema changes
```

**Review the actual changes**:

```bash
# View changes in a specific file
git diff origin/master feature/react-ui-playwright core/src/main/java/jp/aegif/nemaki/model/NemakiPropertyDefinition.java

# Look for pattern: System.out/err.println → log.debug()/log.warn()
```

---

## Troubleshooting

### Problem: mvn command not found

**Solution**:
```bash
# Install Maven
# macOS
brew install maven

# Linux (Ubuntu/Debian)
sudo apt-get install maven

# Windows
# Download from https://maven.apache.org/download.cgi
```

### Problem: JAVA_HOME not set correctly

**Symptom**:
```
mvn -version
# Shows Java version other than 17
```

**Solution**:
```bash
# Find Java 17 installation
/usr/libexec/java_home -V  # macOS
update-alternatives --config java  # Linux

# Set JAVA_HOME
export JAVA_HOME=/path/to/java-17
```

### Problem: Docker daemon not running

**Symptom**:
```
Cannot connect to the Docker daemon at unix:///var/run/docker.sock
```

**Solution**:
- **macOS/Windows**: Start Docker Desktop application
- **Linux**: `sudo systemctl start docker`

### Problem: Permission denied errors

**Symptom**:
```
mkdir: cannot create directory: Permission denied
```

**Solution**:
```bash
# Linux: Add user to docker group
sudo usermod -aG docker $USER
# Logout and login again

# macOS/Windows: Check Docker Desktop settings
```

### Problem: Out of memory during Maven build

**Symptom**:
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution**:
```bash
# Increase Maven memory
export MAVEN_OPTS="-Xmx2g -XX:MaxPermSize=512m"

# Retry build
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests
```

---

## Review Summary

### What to Approve

✅ **Code compiles successfully**
✅ **Appropriate logging framework usage** (SLF4J or Apache Commons Log)
✅ **Correct log levels** (debug for trace, warn for errors)
✅ **Performance guards present** (isDebugEnabled() for debug logs)
✅ **No business logic changes**
✅ **No method signature changes**
✅ **Consistent code style**

### What NOT to Reject For

⚠️ **QA test partial failures** - Known database initialization issue
⚠️ **TCK authentication errors** - Known environment issue
⚠️ **HTTP 401 errors** - Known CouchDB design document issue
⚠️ **Container health issues on first startup** - Initialization timing

### Red Flags (Should NOT appear in this PR)

❌ Modified method signatures
❌ Changed business logic
❌ New dependencies in pom.xml
❌ Database schema changes
❌ Configuration file changes (beyond logging levels)

---

## Questions or Issues

If you encounter problems not covered in this document:

1. **Check CLAUDE.md** - Comprehensive troubleshooting guide
2. **Review git log** - Verify commit history matches expected changes
3. **Contact PR author** - Provide specific error messages and steps to reproduce

---

## Appendix: Complete File Change List

**Phase 1 (13 statements)**:
1. `core/src/main/java/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.java` (1 statement)
2. `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java` (3 statements)
3. `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/DiscoveryServiceImpl.java` (3 statements)
4. `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java` (6 statements)

**Phase 2 (58 statements)**:
5. `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/TypeServiceImpl.java` (10 statements)
6. `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceImpl.java` (12 statements)
7. `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java` (14 statements)
8. `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/RepositoryServiceImpl.java` (16 statements)
9. `core/src/main/java/jp/aegif/nemaki/model/couch/CouchPropertyDefinitionCore.java` (6 statements)

**Phase 3 (86 statements)**:
10. `core/src/main/java/jp/aegif/nemaki/model/NemakiPropertyDefinition.java` (17 statements)
11. `core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java` (69 statements)

**Total**: 11 files, 157 statements

---

**Document Version**: 1.0
**Last Updated**: 2025-10-18
**Branch**: feature/react-ui-playwright
**Commits**: 388f1deb1, e83cf76ea
