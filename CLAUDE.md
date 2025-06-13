# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NemakiWare is an open source Enterprise Content Management system built as a CMIS (Content Management Interoperability Services) 1.1 compliant repository. It uses CouchDB as the NoSQL backend and provides a complete ECM solution with full-text search, versioning, and web UI.

## Build System and Commands

### Core Build Commands

**Maven (Core, Solr, Common, Action modules):**
```bash
# Build all modules
mvn clean package

# Build individual modules
mvn clean package -f core/pom.xml
mvn clean package -f solr/pom.xml

# Build for production (enables tests)
mvn clean package -Pproduct

# Run tests
mvn test -Pproduct

# Skip tests (development profile - default)
mvn clean package -Pdevelopment
```

**SBT (UI module):**
```bash
cd ui/
sbt compile          # Compile
sbt test            # Run tests
sbt war             # Create WAR file
```

### Docker Development

**Full Integration Testing:**
```bash
# Main test script that builds everything and tests integration
./docker/test-war.sh

# Verify core server is running
./docker/verify-core.sh
```

**Development Environment:**
```bash
# Start development environment
cd setup/development/
./setup.sh -e          # Setup and start
./start.sh             # Start services
./stop.sh              # Stop services
```

### Test Commands

**Core CMIS TCK Tests:**
```bash
# Run CMIS compatibility tests
mvn test -Pproduct -f core/pom.xml

# Test files are in core/src/test/java/jp/aegif/nemaki/cmis/tck/
```

**UI Tests:**
```bash
cd ui/
sbt test
```

## Architecture Overview

### Multi-Module Structure

- **core/**: Main CMIS repository server (Spring-based WAR)
- **ui/**: Web interface (Play Framework/Scala)
- **solr/**: Search engine customization (Apache Solr)
- **common/**: Shared utilities and models
- **action/**: Plugin framework for custom actions

### Core Architecture Layers

**CMIS Service Layer** (`jp.aegif.nemaki.cmis.service`):
- Implements CMIS 1.1 specification
- Services: Repository, Object, Navigation, Discovery, Versioning, ACL, Policy

**Business Logic Layer** (`jp.aegif.nemaki.businesslogic`):
- `ContentService`: Main content operations (700+ methods)
- `PrincipalService`: User/group management
- `TypeService`: CMIS type system
- `RenditionManager`: Document preview generation

**Data Access Layer** (`jp.aegif.nemaki.dao`):
- Decorator pattern with caching: `cached.ContentDaoServiceImpl` wraps `couch.ContentDaoServiceImpl`
- Repository-aware caching with EHCache
- Multi-tenant data isolation

### Key Integration Points

**CouchDB Integration:**
- Document storage with JSON + attachments
- Multi-repository support (each repository = separate database)
- Connection pooling via `ConnectorPool`

**Solr Integration:**
- Full-text search with `SolrQueryProcessor`
- Separate cores for content (`nemaki`) and tokens
- Document indexing via `SolrUtil`

**CMIS Protocol Bindings:**
- AtomPub: `/atom/*`
- Browser (JSON): REST endpoints
- Web Services: SOAP `/services/*`
- Custom REST: `/rest/*`

### Multi-Tenancy

Each repository is an isolated tenant:
- Configuration: `repositories.yml`
- Database mapping: Repository → CouchDB database
- Cache isolation: Repository-aware caching

### Spring Configuration

Modular configuration files:
- `applicationContext.xml`: Main context
- `serviceContext.xml`: CMIS services (700+ lines)
- `businesslogicContext.xml`: Business services + caching
- `daoContext.xml`: Data access with caching decorators
- `couchContext.xml`: CouchDB connections

### Plugin Architecture

Action plugins for custom functionality:
- Java-based: Implement `JavaBackedAction`
- UI triggers: User buttons and create buttons
- Spring XML configuration in `META-INF/plugins.xml`

## Development Patterns

### Service Layer Pattern
All services use interface + implementation pattern with Spring AOP proxies for caching and transaction management.

### Caching Strategy
Decorator pattern: `cached.*Impl` wraps `couch.*Impl` with repository-aware EHCache.

### Error Handling
Business exceptions in `jp.aegif.nemaki.util.NemakiPropertyException` hierarchy.

### Configuration Management
Property files override defaults:
- `nemakiware.properties`: Core configuration
- `repositories.yml`: Repository definitions
- Environment-specific overrides in Docker configs

## Technology Stack

- **Backend**: Spring Framework, Apache Chemistry OpenCMIS
- **Database**: CouchDB (document storage)
- **Search**: Apache Solr
- **Caching**: EHCache
- **Web Framework**: Jersey/JAX-RS, Play Framework (UI)
- **Build**: Maven (core), SBT (UI)
- **Java Version**: 1.8

## Testing and Verification

### Core Application Health Check

**Default Authentication Credentials**:
```bash
Username: admin
Password: admin
```
*Source: `/setup/installer/tomcat/app-server-solr.properties`*

**CMIS Endpoint Testing**:
```bash
# Test CMIS AtomPub endpoint with bedroom repository
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom"

# Test CMIS AtomPub endpoint with canopy repository  
curl -s -u admin:admin "http://localhost:8080/core/atom/canopy"

# Test CMIS Browser endpoint
curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom"

# Test CMIS Web Services
curl -s "http://localhost:8080/core/services"
```

**Expected Success Responses**:
- **CMIS AtomPub**: XML response containing `<cmis:repositoryId>bedroom</cmis:repositoryId>` and repository capabilities
- **CMIS Web Services**: HTML page with "CMIS 1.1 Web Services" title
- **HTTP Status**: 200 for authenticated requests, 401 for unauthenticated (expected)

**UI Application Testing**:
```bash
# Test UI application
curl -s -L "http://localhost:9000/ui" | head -10

# Expected: HTML content with "NemakiWare" title and CSS includes
```

**Database Verification**:
```bash
# Verify all repositories exist
curl -s -u admin:password http://localhost:5984/_all_dbs

# Expected output: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]
```

**Common Issues and Troubleshooting**:
- **HTTP 404 on CMIS endpoints**: Check if repository ID is included in URL path
- **HTTP 401 Unauthorized**: Verify admin:admin credentials or check authentication configuration
- **Missing repositories**: Ensure all 4 repositories (bedroom, bedroom_closet, canopy, canopy_closet) are initialized
- **Spring startup failures**: Check for DocumentNotFoundException errors in Core container logs

## Important Notes

### Installer vs Docker Environment Differences

The production installer and Docker test environment have significant differences:

1. **CouchDB Authentication**:
   - **Installer**: Traditionally worked without authentication (admin party mode)
   - **Docker**: Uses CouchDB with authentication (admin/password) - required for CouchDB 3.x
   - **Current Standard**: Authentication required for all environments

2. **Repository Configuration**:
   - **Installer**: Creates `bedroom`, `bedroom_closet`, `canopy`, and `canopy_closet`
   - **Docker**: Must create all repositories defined in repositories.yml with proper initialization

3. **Initialization Process**:
   - **Installer**: Direct bjornloka.jar execution (auth varies by CouchDB version)
   - **Docker**: URL-embedded authentication (standardized for CouchDB 3.x compatibility)

### Best Practice
To ensure compatibility with installer-based deployments, Docker test environment should:
- Create all repositories: `bedroom`, `bedroom_closet`, `canopy`, and `canopy_closet`
- Use bedroom_init.dump for both bedroom and canopy repositories (identical data structure)
- Use archive_init.dump for both archive repositories
- Always use authentication for CouchDB 3.x compatibility

## Known Issues and Solutions

### Critical Fixes Applied in test-war.sh

The following critical fixes have been identified and MUST be maintained in `docker/test-war.sh`:

#### 1. Ektorp Thread Management Fix
**Problem**: Ektorp creates idle connection monitor threads that cause memory leaks
**Solution**: Disable cleanupIdleConnections in ConnectorPool.java source code
```bash
# Fix Ektorp IdleConnectionMonitor issue by disabling cleanupIdleConnections in ConnectorPool
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java; then
  echo "Disabling cleanupIdleConnections to prevent thread leaks..."
  sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java
  echo "ConnectorPool cleanupIdleConnections disabled"
fi
```

#### 2. Spring Initialization Order Fix
**Problem**: @PostConstruct in PatchService causes circular dependency
**Solution**: Remove @PostConstruct annotation from PatchService.java
```bash
# Remove @PostConstruct from PatchService to prevent Spring initialization conflicts
if grep -q "@PostConstruct" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java; then
  echo "Removing @PostConstruct from PatchService to prevent initialization conflicts..."
  sed -i '.bak' '/@PostConstruct/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
  sed -i '.bak2' '/import javax.annotation.PostConstruct;/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
  echo "PatchService @PostConstruct removed"
fi
```

#### 3. SBT Repository Configuration Fix
**Problem**: SBT repositories use HTTP URLs which fail due to security policies
**Solution**: Update all repository URLs to HTTPS in plugins.sbt
```bash
# Ensure SBT configuration is properly set up for HTTPS repositories
if ! grep -q "https://repo.typesafe.com" project/plugins.sbt; then
  echo "Updating SBT plugins.sbt with HTTPS repositories..."
  # [Complete plugins.sbt content with HTTPS URLs]
fi
```

#### 4. CouchDB Database Initialization Fix
**Problem**: canopy repository lacks proper design documents causing Spring TokenService failure
**Root Cause Analysis**:
1. **Installer DOES create canopy** - both bedroom and canopy repositories
2. **Both use bedroom_init.dump** - canopy gets identical data structure
3. **Authentication mismatch** - installer uses no auth, Docker uses auth

**Installer Process** (confirmed working):
```bash
# Creates both repositories with same data
bjornloka.jar http://localhost:5984 bedroom bedroom_init.dump archive_init.dump
bjornloka.jar http://localhost:5984 canopy bedroom_init.dump archive_init.dump
```

**Solution**: Ensure canopy initialization uses bedroom_init.dump (matches installer)
- CRITICAL: canopy uses bedroom_init.dump (NOT canopy_init.dump which doesn't exist)
- CRITICAL: Both repositories get identical initial data structure
- CRITICAL: Authentication format must match Docker CouchDB configuration

**Required Fix in test-war.sh**:
```bash
# Ensure canopy uses bedroom_init.dump since canopy_init.dump doesn't exist
elif [[ "${repo_id}" == "canopy" ]]; then
    dump_file="/app/bedroom_init.dump"
    echo "Using bedroom dump file for canopy repository (no canopy-specific dump available)"
```

**CRITICAL Force Parameter Fix**:
Database existence check should NOT skip data initialization. Design documents are created during data import, not database creation.
```bash
# Always use force=true to ensure proper repository initialization even if database exists
# This is necessary because database creation and data initialization are separate steps
local force_param="true"
```
**Previously**: `force=false` for existing databases caused skipped initialization
**Now**: `force=true` ensures design documents are always created

**CRITICAL Repository Completeness**:
ALL 4 repositories MUST be initialized for proper Core application startup:
- `bedroom` (main repository using bedroom_init.dump)
- `bedroom_closet` (archive repository using archive_init.dump)  
- `canopy` (main repository using bedroom_init.dump)
- `canopy_closet` (archive repository using archive_init.dump)

**Missing Archive Repositories**: If `bedroom_closet` or `canopy_closet` are missing, Core application may return 404 errors on endpoints even when container appears healthy.

#### 5. CouchDB Authentication Configuration Fix
**Problem**: CouchDB authentication required for all operations (mandatory for CouchDB 3.x)
**Root Cause**: CouchDB 3.x enforces authentication, older versions allowed admin party mode
**Solution**: Unified authentication configuration across all components

**Default Credentials** (used in test-war.sh):
```bash
export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}
```

**bjornloka.jar Authentication Fix**:
```bash
# OLD (failed): no authentication
java -cp /app/bjornloka.jar jp.aegif.nemaki.bjornloka.Load http://couchdb2:5984 bedroom /app/bedroom_init.dump true

# NEW (required): URL-embedded authentication for CouchDB 3.x
java -cp /app/bjornloka.jar jp.aegif.nemaki.bjornloka.Load \
  http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@${container_name}:5984 ${repo_id} ${dump_file} ${force_param}
```

**Docker Environment Variables**:
All containers must receive CouchDB credentials:
```yaml
environment:
  - COUCHDB_USER=${COUCHDB_USER:-admin}
  - COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}
  - COUCHDB_URL=http://couchdb2:5984
  - COUCHDB_USERNAME=${COUCHDB_USER:-admin}  # Alternative naming
```

**Manual Database Operations**:
```bash
# Check database existence
curl -s -u "admin:password" http://localhost:5984/canopy

# Create database manually if needed
curl -X PUT -u "admin:password" http://localhost:5984/canopy

# Verify design documents
curl -s -u "admin:password" http://localhost:5984/canopy/_design/_repo
```

### Test-war.sh Execution Requirements

1. **ALWAYS run these fixes BEFORE building core.war**
2. **NEVER skip any of the source code modifications**
3. **Verify all repositories in repositories.yml have corresponding dump files**
4. **Ensure bjornloka.jar is executed for ALL repositories with proper authentication**
5. **CRITICAL: Verify CouchDB authentication is working before proceeding**
6. **CRITICAL: Ensure all databases have design documents after initialization**

### Authentication Troubleshooting

**Common Authentication Issues**:
1. **CouchDB not ready**: Wait for health check to pass
2. **Wrong credentials**: Verify COUCHDB_USER/COUCHDB_PASSWORD
3. **Network connectivity**: Test Docker container communication
4. **bjornloka.jar parameter format**: Must use URL-embedded auth

**Pre-execution Verification**:
```bash
# Test CouchDB authentication
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/_all_dbs

# Verify container can reach CouchDB
docker exec docker-initializer2-1 curl -s http://couchdb2:5984

# Check environment variables in container
docker exec docker-initializer2-1 env | grep COUCHDB
```

### Debugging Commands

When test-war.sh fails, check:
```bash
# Check CouchDB databases exist and have data
curl -s -u "admin:password" http://localhost:5984/canopy
curl -s -u "admin:password" http://localhost:5984/bedroom

# Check design documents exist
curl -s -u "admin:password" http://localhost:5984/canopy/_design/_repo
curl -s -u "admin:password" http://localhost:5984/bedroom/_design/_repo

# Check Core application logs for Spring errors
docker logs docker-core2-1 | grep -E "ERROR|Exception|Failed"
docker exec docker-core2-1 cat /usr/local/tomcat/logs/localhost.$(date +%Y-%m-%d).log
```