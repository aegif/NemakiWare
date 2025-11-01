# NemakiWare Multi-Agent Collaboration Guide

**Last Updated**: 2025-11-01
**Target Audience**: All AI agents (Claude Code, Devin, Cursor, Copilot, etc.)
**Purpose**: Enable smooth test delegation and collaborative development
**Current Branch**: feature/react-ui-playwright

---

## 📋 Quick Start for New Agents

### Environment Prerequisites

```bash
# 1. Java 17 (MANDATORY for all Maven/TCK operations)
java -version
# Expected: openjdk version "17.0.x"

# Set Java 17 (adjust path to your environment)
export JAVA_HOME=/path/to/java-17
export PATH=$JAVA_HOME/bin:$PATH

# 2. Node.js 18+ (for Playwright UI tests)
node -v
# Expected: v18.x or later

# 3. Docker & Docker Compose
docker --version
docker compose version

# 4. Playwright browsers (for UI tests)
npx playwright --version
# If not installed: npx playwright install
```

### First-Time Setup

```bash
# Navigate to project root
cd /path/to/NemakiWare

# Run QA tests to verify environment
./qa-test.sh
# Expected: Tests passed: 56 / 56 (100%)
```

---

## 🏗️ Build and Deployment - CRITICAL PROCEDURES

**⚠️ IMPORTANT**: Docker deployment issues are common. Always use the reliable deployment procedure.

### Standard Build & Deploy Workflow

```bash
cd /path/to/NemakiWare

# 1. Clean Maven build
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# 2. Copy WAR to Docker directory (CRITICAL - must always do this)
cp core/target/core.war docker/core/core.war

# 3. Complete Docker rebuild (--build --force-recreate REQUIRED)
cd docker
docker compose -f docker-compose-simple.yml down --remove-orphans
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# 4. Wait for startup (minimum 90 seconds)
sleep 90

# 5. Verify deployment
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# Expected: HTTP 200 with XML response
```

**Common Mistakes to Avoid**:
- ❌ Using `docker compose restart` (doesn't rebuild Docker image)
- ❌ Forgetting to copy WAR file to docker/core/
- ❌ Not waiting for container startup
- ✅ Always use `--build --force-recreate` flags
- ✅ Always wait 90 seconds after `docker compose up`

**Detailed Guide**: See `BUILD_DEPLOY_GUIDE.md` for complete procedures, troubleshooting, and git worktree considerations.

---

## 🧪 Test Execution Guide

### Test Execution Priority

**Recommended Testing Order**:
1. QA Integration Tests (2-3 minutes) - Verify system health
2. TCK Compliance Tests (5-60 minutes per group) - Verify CMIS compliance
3. Playwright UI Tests (10-20 minutes) - Verify UI functionality

### 1. QA Integration Tests (First Step Recommended)

**Purpose**: Comprehensive system health check
**Execution Time**: 2-3 minutes
**Expected Result**: 56/56 tests PASS

```bash
cd /path/to/NemakiWare
./qa-test.sh

# Expected output:
# Tests passed: 56 / 56
# 🎉 全テスト合格！NemakiWareは正常に動作しています。
```

**Test Coverage**:
- CMIS endpoints (AtomPub, Browser Binding, Web Services)
- Database initialization and patch system
- Document/Folder CRUD operations
- Versioning, ACL, Query system
- Authentication and security

### 2. TCK Compliance Tests (CMIS 1.1 Specification)

**Purpose**: CMIS 1.1 specification compliance verification
**Execution Time**: 5-60 minutes per test group
**Expected Result**: 39/39 tests PASS for implemented features

**⚠️ CRITICAL**: Always use `tck-test-clean.sh` to prevent database bloat issues.

```bash
# Run all TCK tests with automatic database cleanup
./tck-test-clean.sh

# Run specific test group
./tck-test-clean.sh QueryTestGroup

# Run specific test method
./tck-test-clean.sh QueryTestGroup#queryLikeTest
```

**Test Groups and Expected Results**:

| Group | Tests | Time | Status | Notes |
|-------|-------|------|--------|-------|
| BasicsTestGroup | 3 | ~40s | ✅ PASS | Repository info, root folder, security |
| TypesTestGroup | 3 | ~3m | ✅ PASS | Type definitions, base types |
| ControlTestGroup | 1 | ~25s | ✅ PASS | ACL operations |
| VersioningTestGroup | 4 | ~6m | ✅ PASS | Versioning operations |
| ConnectionTestGroup | 2 | ~1s | ✅ PASS | Connection handling |
| InheritedFlagTest | 1 | ~1s | ✅ PASS | Property inheritance |
| QueryTestGroup | 6 | ~8m | ✅ PASS | CMIS SQL queries |
| CrudTestGroup1 | 10 | ~33m | ✅ PASS | CRUD operations (part 1) |
| CrudTestGroup2 | 9 | ~14m | ✅ PASS | CRUD operations (part 2) |
| **FilingTestGroup** | 3 | - | ⊘ SKIP | Multi-filing (product specification) |

**Total**: 39/39 PASS (100% for implemented features)

**Manual Execution** (if tck-test-clean.sh unavailable):
```bash
export JAVA_HOME=/path/to/java-17
timeout 600s mvn test -Dtest=BasicsTestGroup -f core/pom.xml -Pdevelopment
```

### 3. Playwright UI Tests

**Purpose**: End-to-end browser testing of React UI
**Execution Time**: 10-20 minutes (all browsers)
**Test Count**: 81 specs × 6 browser profiles = 486 total executions

```bash
cd /path/to/NemakiWare/core/src/main/webapp/ui

# Install dependencies (first time only)
npm install

# Run all tests (all browsers)
npx playwright test

# Run specific test file
npx playwright test tests/admin/initial-content-setup.spec.ts

# Run with specific browser
npx playwright test --project=chromium

# Run with headed browser (debugging)
npx playwright test --headed --project=chromium

# Generate HTML report
npx playwright show-report
```

**Browser Profiles** (6 total):
- chromium (desktop)
- firefox
- webkit (desktop)
- Mobile Chrome
- Mobile Safari
- Tablet

**Current Test Status** (2025-11-01):
- Expected to improve with Browser Binding "root" translation fix
- See CLAUDE.md for detailed Playwright test analysis

---

## 📂 Repository Structure

```
NemakiWare/
├── core/                           # Main CMIS repository server
│   ├── src/main/java/             # Java source code
│   │   └── jp/aegif/nemaki/       # Main package
│   ├── src/main/webapp/ui/        # React UI
│   │   ├── src/                   # React components
│   │   ├── tests/                 # Playwright tests
│   │   └── dist/                  # Built UI assets
│   └── pom.xml                    # Maven build config
├── docker/                         # Docker deployment
│   ├── docker-compose-simple.yml  # 3-container setup (recommended)
│   └── core/core.war              # Deployment WAR file
├── lib/                            # Self-build dependencies
│   ├── built-jars/                # Unified JAR management
│   └── nemaki-opencmis-1.1.0-jakarta/ # Jakarta EE OpenCMIS source
├── qa-test.sh                     # QA integration tests (56 tests)
├── tck-test-clean.sh              # TCK tests with database cleanup
├── BUILD_DEPLOY_GUIDE.md          # Comprehensive build procedures
├── HANDOFF.md                     # Quick agent handoff reference
├── CLAUDE.md                      # Detailed project history (Claude Code-specific)
└── AGENTS.md                      # This file (agent-agnostic)
```

---

## 🔑 Authentication and Endpoints

### Default Credentials
- **NemakiWare Admin**: `admin:admin`
- **CouchDB**: `admin:password` (CouchDB 3.x requires authentication)

### Key Endpoints

```bash
# CMIS AtomPub Binding
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# CMIS Browser Binding (JSON)
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# React UI
http://localhost:8080/core/ui/

# CouchDB
curl -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

# Solr
curl http://localhost:8983/solr/admin/cores?action=STATUS
```

### Repository IDs
- **bedroom**: Primary repository (use for all testing)
- **canopy**: Multi-repository management
- **bedroom_closet**: Archive repository for bedroom
- **canopy_closet**: Archive repository for canopy

---

## 🐛 Common Issues and Solutions

### Issue 1: "Code changes not reflecting in Docker container"

**Symptom**: Modifications don't appear after rebuild

**Diagnosis**:
```bash
# Check if you're building from correct location
pwd
git rev-parse --show-toplevel

# Check WAR file timestamp
ls -lh docker/core/core.war

# Check container class file timestamp
docker exec docker-core-1 ls -lh /usr/local/tomcat/webapps/core/WEB-INF/classes/jp/aegif/nemaki/cmis/servlet/
```

**Solution**:
```bash
# Wrong approach: docker compose restart (doesn't rebuild image)
# Correct approach:
cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml up -d --build --force-recreate core
sleep 90
```

### Issue 2: "Tests failing with connection errors"

**Symptom**: Timeout, ECONNREFUSED, or connection refused errors

**Diagnosis**:
```bash
# Check container status
docker ps
# Expected: 3 containers (docker-core-1, docker-couchdb-1, docker-solr-1)

# Check container health
docker ps | grep "(healthy)"
```

**Solution**:
```bash
# Restart containers if unhealthy
cd docker
docker compose -f docker-compose-simple.yml restart
sleep 90

# Check logs for errors
docker logs docker-core-1 --tail 50
docker logs docker-couchdb-1 --tail 50
docker logs docker-solr-1 --tail 50
```

### Issue 3: "TCK tests timing out or hanging"

**Symptom**: Tests hang after 2 minutes or never complete

**Diagnosis**:
```bash
# Check database document count (clean state: 116 documents)
curl -s -u admin:password http://localhost:5984/bedroom | jq '.doc_count'
# If >500 documents: Database bloat causing performance issues
```

**Solution**:
```bash
# Always use tck-test-clean.sh (automatic database cleanup)
./tck-test-clean.sh TestGroupName

# Manual cleanup if script unavailable:
curl -X DELETE -u admin:password http://localhost:5984/bedroom
cd docker && docker compose -f docker-compose-simple.yml restart core
sleep 90

# Verify clean state
curl -s -u admin:password http://localhost:5984/bedroom | jq '.doc_count'
# Expected: ~116 documents
```

### Issue 4: "Playwright tests failing with browser not found"

**Symptom**: "Executable doesn't exist" or browser installation errors

**Solution**:
```bash
# Install Playwright browsers on host machine
npx playwright install

# Verify installation
npx playwright --version

# List installed browsers
npx playwright install --help
```

### Issue 5: "UI not accessible - HTTP 404"

**Symptom**: http://localhost:8080/core/ui/ returns 404

**Diagnosis**:
```bash
# Check if UI assets are in WAR file
unzip -l core/target/core.war | grep "ui/dist/index.html"

# Check container UI files
docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core/ui/dist/
```

**Solution**:
```bash
# Rebuild UI and WAR
cd core/src/main/webapp/ui
npm run build

cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests
cp core/target/core.war docker/core/core.war
cd docker && docker compose -f docker-compose-simple.yml up -d --build --force-recreate
sleep 90
```

---

## 🎯 Current Status (2025-11-01)

### Recent Achievements ✅
- **Browser Binding Fix**: "root" marker translation working (commit 91384ee48)
- **TCK Compliance**: 39/39 tests PASS (100% for implemented features)
- **QA Integration**: 56/56 tests PASS (100%)
- **Documentation**: BUILD_DEPLOY_GUIDE.md, HANDOFF.md created
- **Branch Integration**: vk/368c-tck merged into feature/react-ui-playwright

### Current Focus ⏳
- Organizing Playwright skipped tests
- Creating UI implementation plan for missing features
- Continuing Playwright test-based improvements

### Known Playwright Test Skips 📋

**UI Features Not Implemented** (14 tests total):
1. **Custom Type Creation** (4 tests skipped in custom-type-creation.spec.ts)
   - UI for creating custom types doesn't exist yet
   - Backend type creation API exists (TypeResource.java)

2. **Versioning UI** (5 tests skipped in document-versioning.spec.ts)
   - Check-out button not implemented
   - Check-in button not implemented
   - Cancel check-out button not implemented
   - Version history button not implemented

3. **PDF Preview** (2 tests skipped in pdf-preview.spec.ts)
   - PDF rendering functionality incomplete
   - Viewer integration pending

4. **Permission Management** (3 tests skipped in access-control.spec.ts)
   - Test user creation UI not available
   - ACL assignment UI needs improvement

**Test Issues** (different from UI not implemented):
- Access Control tests: Test user timeout issues
- Document Viewer: Navigation/auth issues

**Total Skipped**: ~30 tests (out of ~103 total Playwright tests)

### Next Steps for UI Implementation 📌

**High Priority** (Core CMIS Functionality):
1. Versioning UI buttons (check-out, check-in, cancel, version history)
2. Permission management improvements (ACL UI)

**Medium Priority** (Enhanced Functionality):
3. Custom type creation UI
4. User/Group management CRUD UI
5. PDF preview completion

**Low Priority** (Nice to Have):
6. Advanced search UI improvements
7. Bulk operations UI
8. Workflow integration UI

---

## 🤝 Agent Collaboration Workflow

### Scenario 1: Delegating Test Execution

**Current Agent** (handoff preparation):
```bash
# 1. Ensure clean environment
docker ps  # Verify 3 containers running
./qa-test.sh  # Verify 56/56 tests pass

# 2. Document current state
cd core/src/main/webapp/ui
npx playwright test --list > playwright-tests-list.txt

# 3. Create handoff note
echo "Environment verified. QA: 56/56 PASS. Playwright test list in playwright-tests-list.txt" > handoff-status.txt
```

**Receiving Agent** (execution):
```bash
# 1. Verify prerequisites
java -version  # Java 17
node -v        # Node.js 18+
docker ps      # 3 containers
npx playwright --version  # Browsers installed

# 2. Verify environment
./qa-test.sh  # Should show 56/56

# 3. Execute delegated tests
cd core/src/main/webapp/ui
npx playwright test

# 4. Report results
npx playwright show-report
```

### Scenario 2: Delegating Code Changes

**Current Agent** (before handoff):
```bash
# 1. Commit and push current work
git add -A
git commit -m "work: [description of current state]"
git push origin [branch-name]

# 2. Document in HANDOFF.md or create handoff note
echo "Last commit: [hash]. Current work: [description]. Next step: [task]" > handoff-note.txt

# 3. Ensure build works
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests
./qa-test.sh
```

**Receiving Agent** (continuation):
```bash
# 1. Pull latest changes
git pull origin [branch-name]

# 2. Verify build
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests
./qa-test.sh

# 3. Continue from documented next step
```

---

## 📚 Reference Documentation

### Essential Reading
- **BUILD_DEPLOY_GUIDE.md**: Complete build/deploy procedures, troubleshooting, Docker gotchas
- **HANDOFF.md**: Quick reference for agent handoff (3 most important points)
- **CLAUDE.md**: Comprehensive project history, technical decisions, known issues (Claude Code-specific)

### CMIS API Reference
See CLAUDE.md sections:
- CMIS API Reference
- Browser Binding usage (critical for file uploads)
- AtomPub Binding usage

### TCK Testing Details
See CLAUDE.md sections:
- TCK Test Execution (Standard Procedure)
- TCK Test Results Summary
- Database Cleanup Requirements

---

## 🔧 Health Check Commands

### Quick Environment Verification
```bash
# All-in-one health check
docker ps && curl -s -u admin:admin http://localhost:8080/core/atom/bedroom -o /dev/null -w "CMIS: %{http_code}\n" && curl -s -u admin:password http://localhost:5984/_all_dbs -o /dev/null -w "CouchDB: %{http_code}\n" && ./qa-test.sh
```

### Individual Service Checks
```bash
# Docker containers
docker ps
# Expected: 3 containers running (docker-core-1, docker-couchdb-1, docker-solr-1)

# CMIS endpoint
curl -s -o /dev/null -w "%{http_code}" -u admin:admin http://localhost:8080/core/atom/bedroom
# Expected: 200

# CouchDB
curl -s -o /dev/null -w "%{http_code}" -u admin:password http://localhost:5984/_all_dbs
# Expected: 200

# Solr
curl -s -o /dev/null -w "%{http_code}" http://localhost:8983/solr/admin/cores?action=STATUS
# Expected: 200

# React UI
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ui/
# Expected: 200
```

### Database State Check
```bash
# Document count (clean state: 116 documents)
curl -s -u admin:password http://localhost:5984/bedroom | jq '.doc_count'

# Design documents
curl -s -u admin:password "http://localhost:5984/bedroom/_design/_repo" | jq '.views | keys'

# All databases
curl -s -u admin:password http://localhost:5984/_all_dbs
# Expected: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]
```

---

## 📊 Test Execution Matrix

| Test Type | Command | Time | Expected Result | When to Run |
|-----------|---------|------|-----------------|-------------|
| QA Integration | `./qa-test.sh` | 2-3 min | 56/56 PASS | First step, after any deploy |
| TCK Basics | `./tck-test-clean.sh BasicsTestGroup` | ~40s | 3/3 PASS | After CMIS changes |
| TCK Full | `./tck-test-clean.sh` | ~60 min | 39/39 PASS | Before PR, release |
| Playwright Quick | `npx playwright test --project=chromium` | 3-5 min | Varies | After UI changes |
| Playwright Full | `npx playwright test` | 10-20 min | Varies | Before PR, release |

---

## 🚨 Emergency Procedures

### Complete Environment Reset
```bash
cd /path/to/NemakiWare/docker

# 1. Stop and remove all containers
docker compose -f docker-compose-simple.yml down --remove-orphans

# 2. Clean Docker cache
docker system prune -f

# 3. Rebuild from scratch
cd ..
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests
cp core/target/core.war docker/core/core.war
cd docker
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# 4. Wait and verify
sleep 90
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### Database Reset (TCK Testing)
```bash
# Delete contaminated database
curl -X DELETE -u admin:password http://localhost:5984/bedroom

# Restart core for automatic reinitialization
cd docker
docker compose -f docker-compose-simple.yml restart core
sleep 90

# Verify clean state
curl -s -u admin:password http://localhost:5984/bedroom | jq '.doc_count'
# Expected: 116 documents
```

---

**Version**: 2.0
**Last Updated**: 2025-11-01
**Branch**: feature/react-ui-playwright
**Maintainer**: NemakiWare Development Team

For detailed technical documentation, see CLAUDE.md.
For quick handoff reference, see HANDOFF.md.
For build/deploy procedures, see BUILD_DEPLOY_GUIDE.md.
