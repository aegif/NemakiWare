# Devin Session Handoff Documentation - NemakiWare Automated TCK System

This document provides comprehensive information for future Devin sessions working on the NemakiWare CMIS query permission filtering and automated TCK test system.

## Current State Summary

### Completed Work
- ✅ **CMIS Query Permission Filtering Fix**: Modified `PermissionServiceImpl.java` to handle null ACLs gracefully
- ✅ **Data Synchronization Fix**: Automated CouchDB design document creation and Solr re-indexing
- ✅ **Automated TCK Test System**: Complete Docker-based automation for reproducible test results
- ✅ **Docker Compose Compatibility**: Fixed to work with both legacy and modern Docker Compose versions
- ✅ **Comprehensive Documentation**: Created README-AUTOMATED-TCK.md with usage instructions

### Current Branch and PR
- **Branch**: `devin/1750282993-fix-cmis-query-permission-filtering`
- **PR**: #375 - "Fix CMIS query permission filtering issue - handle null ACL gracefully"
- **Status**: Open, ready for review, all automation components committed

### Expected TCK Results
- **Total Tests**: 214
- **Passed Tests**: 70 (32.71% pass rate)
- **Execution Time**: ~10-15 minutes
- **Key Achievement**: CMIS queries now return actual data instead of 0 results

## Java 8 Environment Requirements

### Critical Java 8 Dependency
NemakiWare **ONLY** works with Java 8 due to legacy dependencies and compatibility requirements.

### Java 8 Setup and Verification
```bash
# Check current Java version
java -version
# Should output: openjdk version "1.8.0_xxx" or similar

# Set JAVA_HOME (if needed)
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Verify Maven uses Java 8
mvn -version
# Should show Java version: 1.8.0_xxx
```

### Java 8 Installation (if needed)
```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install openjdk-8-jdk

# Set as default Java version
sudo update-alternatives --config java
sudo update-alternatives --config javac
```

### Maven Configuration
The project is configured for Java 8 in `core/pom.xml`:
```xml
<maven.compiler.source>1.8</maven.compiler.source>
<maven.compiler.target>1.8</maven.compiler.target>
```

## Maven Dependency Resolution

### Critical Dependencies
The NemakiWare project has two critical internal dependencies that must be resolved:

1. **nemakiware-common**: Located in `common/` directory
2. **nemakiware-action**: Referenced in `core/pom.xml` but location varies

### Dependency Resolution Process
```bash
# Navigate to project root
cd ~/repos/NemakiWare

# Build and install common module first
cd common/
mvn clean install -DskipTests
cd ..

# Build core module (depends on common)
cd core/
mvn clean compile -DskipTests

# Verify dependencies are resolved
mvn dependency:tree | grep nemakiware
```

### Dependency Chain
```
core/pom.xml depends on:
├── nemakiware-common (from common/ directory)
└── nemakiware-action (location varies, check local Maven repo)
```

### Troubleshooting Dependencies
```bash
# Clear Maven cache if issues occur
rm -rf ~/.m2/repository/jp/aegif/nemaki/

# Rebuild dependencies from scratch
cd ~/repos/NemakiWare/common && mvn clean install -DskipTests
cd ~/repos/NemakiWare/core && mvn clean compile -DskipTests
```

## Automatic Design Document Creation System

### Multi-Layer Automatic Creation
The system ensures design documents are created automatically through **multiple redundant mechanisms**:

#### 1. Docker Initialization Layer
- **File**: `docker/initializer/entrypoint.sh` (lines 61-72)
- **Action**: Calls `setup-design-documents.sh` during container startup
- **Scope**: Creates comprehensive `_design/_repo` documents for all databases

#### 2. Java ConnectorPool Layer
- **File**: `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java` (lines 465-480)
- **Method**: `addDb(String dbName)`
- **Action**: Automatically creates `_design/_repo` when adding database connectors
- **Trigger**: Called whenever a new database connection is established

#### 3. Patch System Layer
- **File**: `core/src/main/java/jp/aegif/nemaki/patch/PatchUtil.java` (lines 50-63)
- **Method**: `addDb(String dbName)`
- **Action**: Creates design documents during patch execution
- **Trigger**: Automatic patch application on startup via `PatchService.applyPatchesOnStartup()`

#### 4. Automatic Patch Execution
- **File**: `core/src/main/webapp/WEB-INF/classes/patchContext.xml` (line 17)
- **Configuration**: `init-method="applyPatchesOnStartup"`
- **Action**: Automatically applies patches including `Patch_20160815` which creates design documents
- **Trigger**: Spring application context initialization

### Design Document Structure
Each `_design/_repo` document contains essential views:
```json
{
  "_id": "_design/_repo",
  "views": {
    "configuration": { "map": "function(doc) { if (doc.type == 'configuration') emit(doc._id, doc) }" },
    "content": { "map": "function(doc) { if (doc.type == 'content') emit(doc._id, doc) }" },
    "changes": { "map": "function(doc) { emit(doc._id, doc) }" },
    "admin": { "map": "function(doc) { if (doc.type == 'user' && doc.isAdmin) emit(doc._id, doc) }" },
    "user": { "map": "function(doc) { if (doc.type == 'user') emit(doc._id, doc) }" },
    "group": { "map": "function(doc) { if (doc.type == 'group') emit(doc._id, doc) }" },
    "type": { "map": "function(doc) { if (doc.type == 'type') emit(doc._id, doc) }" },
    "acl": { "map": "function(doc) { if (doc.type == 'acl') emit(doc._id, doc) }" }
  }
}
```

## Docker Environment Setup

### Docker Compose Compatibility
The system supports both legacy and modern Docker Compose:
- **Legacy**: `docker-compose` command
- **Modern**: `docker compose` command (v2.32.1+)
- **Auto-detection**: Scripts automatically detect and use available command

### Container Architecture
```
nemaki-network (bridge)
├── couchdb (172.18.0.4:5984) - Database storage
├── solr (172.18.0.3:8983) - Search indexing
├── initializer - Database initialization and design document creation
└── core (8080) - Main CMIS application server
```

### Startup Sequence
1. **CouchDB**: Database server starts first
2. **Initializer**: Creates databases, design documents, imports initial data
3. **Solr**: Search server starts and waits for indexing triggers
4. **Core**: CMIS application server starts, applies patches, triggers indexing

### Environment Variables
```bash
# CouchDB Configuration
COUCHDB_USER=admin
COUCHDB_PASSWORD=password
COUCHDB_URL=http://couchdb:5984

# Repository Configuration
REPOSITORY_ID=bedroom
```

## Automated TCK Test System

### Main Automation Script
**File**: `docker/automated-tck-test.sh`

### Usage Examples
```bash
# Basic usage (recommended)
cd ~/repos/NemakiWare/docker/
./automated-tck-test.sh

# Custom credentials
COUCHDB_USER=myuser COUCHDB_PASSWORD=mypass ./automated-tck-test.sh

# Skip cleanup (use existing containers)
./automated-tck-test.sh --no-cleanup

# Show help
./automated-tck-test.sh --help
```

### Expected Results
- **Total Tests**: ~214
- **Passed Tests**: ~70 (32.71% pass rate)
- **Execution Time**: ~10-15 minutes
- **Key Improvement**: CMIS queries return actual data instead of 0 results

### Integration with Existing Scripts
The automation system **reuses** existing Docker scripts without conflicts:
- **execute-tck-tests.sh**: TCK test execution (unchanged)
- **run-tck.sh**: TCK automation wrapper (unchanged)
- **generate-tck-report.sh**: Report generation (unchanged)
- **docker-compose-simple.yml**: Container orchestration (enhanced with env vars)

### Automation Workflow
1. **Environment Setup**: Clean/start Docker containers
2. **Configuration**: Generate `repositories.yml` with environment variables
3. **Design Documents**: Create CouchDB design documents automatically
4. **Data Sync**: Trigger Solr re-indexing for data synchronization
5. **Test Execution**: Run comprehensive TCK tests
6. **Report Generation**: Create HTML and text reports with pass rates

## Key Files and Their Roles

### Core Java Files
- **PermissionServiceImpl.java**: CMIS permission filtering with null ACL handling
- **SolrQueryProcessor.java**: Query processing with null pointer protection
- **ConnectorPool.java**: Automatic CouchDB connector and design document creation
- **PatchUtil.java**: Database patching utilities with design document creation
- **Patch_20160815.java**: System patch that creates design documents and configuration

### Docker Configuration Files
- **docker-compose-simple.yml**: Container orchestration with environment variables
- **initializer/entrypoint.sh**: Database initialization with design document setup
- **initializer/Dockerfile**: Initializer container with setup scripts
- **core/repositories.yml**: CMIS repository configuration (dynamically generated)

### Automation Scripts
- **automated-tck-test.sh**: Main orchestration script for complete automation
- **setup-design-documents.sh**: CouchDB design document creation with container networking
- **execute-tck-tests.sh**: TCK test execution wrapper
- **run-tck.sh**: TCK automation with report generation
- **generate-tck-report.sh**: HTML and text report generation

### Documentation Files
- **README-AUTOMATED-TCK.md**: Comprehensive automation system documentation
- **DEVIN-SESSION-HANDOFF.md**: This session handoff documentation

## Troubleshooting Guide

### Common Issues and Solutions

#### 1. Java Version Conflicts
**Problem**: Build fails with "unsupported class file version"
**Solution**:
```bash
# Verify Java 8 is active
java -version
javac -version
mvn -version

# Set Java 8 as default if needed
sudo update-alternatives --config java
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
```

#### 2. Maven Dependency Resolution Failures
**Problem**: "Could not resolve dependencies" for nemakiware-common or nemakiware-action
**Solution**:
```bash
# Clear Maven cache
rm -rf ~/.m2/repository/jp/aegif/nemaki/

# Rebuild dependencies in order
cd ~/repos/NemakiWare/common && mvn clean install -DskipTests
cd ~/repos/NemakiWare/core && mvn clean compile -DskipTests
```

#### 3. Docker Compose Command Not Found
**Problem**: "docker-compose: command not found"
**Solution**: The automation script auto-detects and uses `docker compose` (v2) if available
```bash
# Verify Docker Compose is available
docker compose version
# or
docker-compose version
```

#### 4. CouchDB Connection Timeouts
**Problem**: "Connection refused" or timeout errors during initialization
**Solution**:
```bash
# Check container status
docker compose -f docker-compose-simple.yml ps

# View container logs
docker compose -f docker-compose-simple.yml logs couchdb
docker compose -f docker-compose-simple.yml logs initializer

# Restart containers if needed
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d
```

#### 5. Design Documents Not Created
**Problem**: CMIS queries fail due to missing design documents
**Solution**: Multiple automatic creation mechanisms should prevent this, but manual verification:
```bash
# Check design documents exist
curl -u admin:password http://localhost:5984/bedroom/_design/_repo
curl -u admin:password http://localhost:5984/bedroom_closet/_design/_repo

# Manually trigger design document creation if needed
cd ~/repos/NemakiWare/docker
./setup-design-documents.sh
```

#### 6. Solr Indexing Issues
**Problem**: CMIS queries return 0 results despite data in CouchDB
**Solution**:
```bash
# Check Solr index status
curl "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0"

# Trigger manual re-indexing
curl -X GET "http://localhost:8080/core/rest/repo/bedroom/search-engine/reindex?tracking=FULL&token=0" -u admin:admin

# Verify indexing completed
curl "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0"
```

#### 7. TCK Test Execution Failures
**Problem**: Tests fail to execute or hang indefinitely
**Solution**:
```bash
# Verify all services are healthy
curl -f http://localhost:8080/core
curl -f http://localhost:5984
curl -f http://localhost:8983/solr

# Check for port conflicts
netstat -tlnp | grep -E ':(5984|8080|8983|9000)'

# Restart automation with clean environment
cd ~/repos/NemakiWare/docker
./automated-tck-test.sh --clean-start
```

## Session Continuity Information

### Git Status
- **Current Branch**: `devin/1750282993-fix-cmis-query-permission-filtering`
- **PR Status**: #375 open and ready for review
- **Uncommitted Changes**: Some binary files and configuration files (safe to ignore)

### Environment Status
- **Java Version**: Should be Java 8 (verify with `java -version`)
- **Docker Status**: Containers may be running from previous tests
- **Maven Dependencies**: Should be resolved (verify with `mvn compile` in core/)

### Next Steps for Future Sessions
1. **Verify Environment**: Check Java 8, Maven dependencies, Docker status
2. **Test Automation**: Run `./docker/automated-tck-test.sh` to verify system works
3. **Review PR**: Check PR #375 status and any new comments
4. **Monitor CI**: Use `git_pr_checks` to monitor any CI pipeline results
5. **Address Feedback**: Respond to any user feedback or review comments

### Key Commands for Quick Start
```bash
# Navigate to project
cd ~/repos/NemakiWare

# Check current branch and status
git branch --show-current
git status

# Verify Java 8 environment
java -version

# Test automated TCK system
cd docker/
./automated-tck-test.sh --help
./automated-tck-test.sh --no-cleanup

# View PR status
# Use git_view_pr repo="aegif/NemakiWare" pull_number="375"
```

## Success Metrics

### Technical Achievements
- ✅ **CMIS Query Fix**: Queries return actual data instead of 0 results
- ✅ **Permission Filtering**: Null ACLs handled gracefully without breaking security
- ✅ **Data Synchronization**: CouchDB and Solr properly synchronized
- ✅ **Automation**: Complete Docker-based reproducible test environment
- ✅ **Documentation**: Comprehensive guides for usage and troubleshooting

### TCK Test Results
- **Before Fix**: 0 results, infinite hangs, NullPointerException
- **After Fix**: 214 tests, 70 passed (32.71%), completes in ~10 minutes
- **Key Improvement**: CMIS queries functional and returning actual repository data

### Environment Reproducibility
- ✅ **Java 8 Compatibility**: Documented setup and verification steps
- ✅ **Maven Dependencies**: Documented resolution process for internal dependencies
- ✅ **Docker Automation**: Complete automation from clean environment to test results
- ✅ **Session Handoff**: Comprehensive documentation for future Devin sessions

This documentation ensures that future Devin sessions can immediately understand the current state, continue development, and maintain the automated TCK test system without starting from scratch.
