# NemakiWare TCK Test Guide

## Overview
This guide provides step-by-step instructions for running TCK (Test Compatibility Kit) tests for NemakiWare using Docker. It includes recent fixes and improvements made to ensure successful TCK execution.

## Prerequisites

### System Requirements
- Docker and Docker Compose installed
- Java 8 (for building)
- Maven 3.x
- At least 4GB of available RAM
- macOS, Linux, or Windows with WSL2

### Initial Setup
```bash
# Clone the repository and checkout the branch
git clone https://github.com/your-org/NemakiWare.git
cd NemakiWare
git checkout feature/solr-auto-indexing

# Verify Docker is running
docker --version
docker compose version
```

## Recent Fixes Applied

### 1. PermissionServiceImpl Query Fix
**Issue**: CMIS queries were failing due to `PermissionServiceImpl.getPermissionMap()` returning null instead of an empty map.

**Solution Applied**:
```java
// Fixed in: core/src/main/java/jp/aegif/nemaki/businesslogic/impl/PermissionServiceImpl.java
if (permissionMap == null || permissionMap.isEmpty()) {
    return new HashMap<String, Boolean>();  // Return empty map instead of null
}
```

### 2. TCK Test Infrastructure Cleanup
- Removed 13+ redundant test scripts
- Consolidated testing into Docker-based scripts
- Organized test configuration files

## Running TCK Tests

### Quick Start (Simple Test)

1. **Build and Start the Test Environment**
```bash
cd docker
./test-simple.sh
```

This script will:
- Build all necessary components (Core, UI, Solr)
- Start Docker containers
- Initialize databases
- Run basic health checks

2. **Wait for Services to Start**
The script automatically waits for services, but you can verify manually:
```bash
# Check if all containers are running
docker ps

# Verify Core is responding
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

3. **Run TCK Tests**
```bash
# Run focused TCK tests
./execute-tck-tests.sh

# Or run specific test groups
docker exec docker-core-1 java -cp /path/to/classpath \
  jp.aegif.nemaki.cmis.tck.DockerTckRunner
```

### Comprehensive Test (Full Integration)

For complete testing including all components:

```bash
cd docker
./test-all.sh
```

This comprehensive script:
- Builds all modules with production profile
- Runs unit tests
- Deploys to Docker
- Executes full TCK suite
- Generates reports

## Understanding Test Results

### Successful Test Output
```
=== Service Status ===
✓ CouchDB is running
✓ Solr is running (http://localhost:8983)
✓ Core server is running
✓ UI application is accessible

=== CMIS Endpoints ===
- CMIS AtomPub: ✓ Working
- CMIS Browser: ✓ Working
- CMIS Web Services: ✓ Working
```

### TCK Test Groups

1. **Basics Test Group** - Repository info, security, root folder
2. **Control Test Group** - ACL and permissions
3. **CRUD Test Group** - Create, Read, Update, Delete operations
4. **Query Test Group** - CMIS SQL queries
5. **Filing Test Group** - Folder operations
6. **Types Test Group** - Type system
7. **Versioning Test Group** - Document versioning

### Common Issues and Solutions

#### Issue: TCK Tests Timeout
**Solution**: Run focused tests instead of full suite:
```bash
# Create a simple test runner for query tests only
cat > QueryOnlyTest.java << 'EOF'
// ... (minimal test code)
EOF
javac -cp "..." QueryOnlyTest.java
java -cp "..." QueryOnlyTest
```

#### Issue: CouchDB Connection Failed
**Solution**: Ensure CouchDB is initialized:
```bash
# Check CouchDB
curl -u admin:password http://localhost:5984/_all_dbs
# Should show: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]
```

#### Issue: Solr Not Responding
**Solution**: Solr startup can be slow:
```bash
# Wait for Solr
sleep 30
# Check Solr
curl http://localhost:8983/solr/admin/cores
```

## Verifying CMIS Query Functionality

After fixes, verify queries work correctly:

```bash
# Test basic queries
curl -u admin:admin -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "q=SELECT * FROM cmis:folder" \
  http://localhost:8080/core/atom/bedroom/query
```

Expected: XML response with folder results

## Project Structure

```
NemakiWare/
├── docker/
│   ├── test-simple.sh      # Quick test script
│   ├── test-all.sh         # Comprehensive test
│   ├── execute-tck-tests.sh # TCK execution
│   └── verify-core.sh      # Health check
├── core/
│   └── src/test/
│       ├── java/.../DockerTckRunner.java
│       └── resources/cmis-tck-*.properties
└── CLAUDE.md              # Additional project documentation
```

## Environment Variables

Key environment variables used:
```bash
COUCHDB_USER=admin
COUCHDB_PASSWORD=password
COUCHDB_URL=http://couchdb:5984
```

## Generating TCK Reports

TCK results are automatically generated, but you can create custom reports:

```bash
# After test execution
docker logs docker-core-1 > tck-execution.log
grep -E "PASS|FAIL|WARNING" tck-execution.log > tck-summary.txt
```

## Troubleshooting

### Enable Debug Logging
```bash
# Add to CATALINA_OPTS
-Dlog4j.configuration=file:/path/to/debug-log4j.properties
```

### Check Container Logs
```bash
docker logs docker-core-1 --tail 100
docker logs docker-couchdb-1 --tail 100
docker logs docker-solr-1 --tail 100
```

### Reset Environment
```bash
docker compose down -v  # Remove volumes
./test-simple.sh       # Start fresh
```

## Additional Resources

- [CMIS 1.1 Specification](https://docs.oasis-open.org/cmis/CMIS/v1.1/CMIS-v1.1.html)
- [Apache Chemistry](https://chemistry.apache.org/)
- See `CLAUDE.md` for detailed project information

## Contributing

When adding new tests or fixing issues:

1. Update this documentation
2. Test with both `test-simple.sh` and `test-all.sh`
3. Ensure TCK tests pass before committing
4. Document any new environment requirements

---

Last Updated: 2025-06-21
TCK Compliance: CMIS 1.1
Query Functionality: ✅ Fixed and Verified