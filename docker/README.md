# NemakiWare Docker Environment Guide

## Quick Start

If you've just checked out this branch and want to run the Docker environment:

```bash
# 1. Navigate to docker directory
cd docker

# 2. Run the simple test script (recommended for first-time setup)
./test-simple.sh

# 3. Wait for "All services are up and running!" message
# This typically takes 2-3 minutes
```

> **For TCK Testing**: See [README-TCK.md](README-TCK.md) for detailed TCK test procedures

## Prerequisites

- Docker Desktop installed and running
- At least 4GB available RAM
- Ports available: 5984, 8080, 8983, 9000

## Test Scripts Overview

### test-simple.sh (Recommended)
Quick integration test with minimal build time:
- Builds and starts all services
- Initializes databases
- Runs health checks
- ~3 minutes total execution

### test-all.sh
Comprehensive test including unit tests:
- Full Maven build with tests
- Complete TCK suite execution
- Detailed reporting
- ~10-15 minutes execution

### execute-tck-tests.sh
Run tests against running environment:
- Requires services already running  
- For detailed TCK testing, see [README-TCK.md](README-TCK.md)

## Step-by-Step Testing Process

### 1. Initial Setup and Test

```bash
# Clean any existing containers
docker compose down -v

# Run simple test
./test-simple.sh
```

Expected output:
```
=== Building Core module ===
✅ Core build successful

=== Starting Docker services ===
✅ Docker compose started successfully

=== Service Status ===
✓ CouchDB is running
✓ Solr is running
✓ Core server is running
✓ UI application is accessible
```

### 2. Verify Services

After services start, verify endpoints:

```bash
# Check CMIS endpoint
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Check UI
open http://localhost:9000/ui/login?repositoryId=bedroom
# Login: admin/admin
```

### 3. Test the Application

Once services are running:

```bash
# Basic functionality test
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# For comprehensive TCK testing
# See README-TCK.md for detailed instructions
./execute-tck-tests.sh
```

### 4. Check Results

```bash
# View logs
docker logs docker-core-1 --tail 100

# Check test results
grep -E "PASS|FAIL" docker/tck-execution.log
```

## Common Issues and Solutions

### Port Already in Use
```bash
# Error: bind: address already in use
# Solution: Stop conflicting services or change ports in docker-compose.yml
docker ps  # Check running containers
lsof -i :8080  # Check what's using port
```

### Build Failures
```bash
# Error: Maven build failed
# Solution: Check Java version and Maven settings
java -version  # Should be Java 17
mvn -version   # Should be 3.x
```

### Database Not Initialized
```bash
# Error: 404 on CMIS endpoints
# Solution: Check database initialization
curl -u admin:password http://localhost:5984/_all_dbs
# Should show: ["bedroom","bedroom_closet","canopy","canopy_closet"]
```

### Container Health Issues
```bash
# Check container status
docker compose ps

# Restart unhealthy containers
docker compose restart <service-name>
```

## Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| CouchDB Admin | http://localhost:5984/_utils | admin/password |
| Solr Admin | http://localhost:8983/solr | - |
| CMIS AtomPub | http://localhost:8080/core/atom/bedroom | admin/admin |
| NemakiWare UI | http://localhost:9000/ui | admin/admin |

## Advanced Usage

### Development Workflow

```bash
# Make changes to source code
vim ../core/src/main/java/...

# Rebuild and redeploy core
mvn clean package -f ../core/pom.xml
cp ../core/target/core.war core/core.war
docker compose restart core

# Test changes
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### Debugging

```bash
# View container logs
docker logs docker-core-1 --tail 100
docker logs docker-couchdb-1 --tail 50

# Access container shell
docker exec -it docker-core-1 bash

# Monitor resource usage
docker stats
```

### Performance Testing

```bash
# Check response times
time curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Load testing (if needed)
# Install tools like ab, wrk, or use specialized tools
```

## Cleanup

After testing:

```bash
# Stop all containers
docker compose down

# Remove volumes (full cleanup)
docker compose down -v

# Remove test artifacts
rm -rf tck-reports/
```

## Next Steps

1. **For TCK Testing**: See [README-TCK.md](README-TCK.md) for comprehensive TCK procedures
2. **For Development**: Check [TCK_TEST_GUIDE.md](../TCK_TEST_GUIDE.md) for detailed testing information  
3. **For Architecture**: See [CLAUDE.md](../CLAUDE.md) for project structure and patterns
4. **For Source Changes**: Follow the development workflow section above

## Documentation Structure

- `README.md` (this file) - Docker environment setup and usage
- `README-TCK.md` - TCK testing procedures and automation
- `../TCK_TEST_GUIDE.md` - Step-by-step TCK testing guide
- `../CLAUDE.md` - Complete project documentation

## Recent Updates (2025-06-21)

- ✅ Fixed PermissionServiceImpl query issue (CMIS queries now work)
- ✅ Cleaned up redundant test scripts (removed 13+ duplicate files)  
- ✅ Improved test execution reliability
- ✅ Added comprehensive documentation structure

---

For issues or questions, check the troubleshooting section or refer to the specific documentation files above.