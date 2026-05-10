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
- Ports available: 5984 (CouchDB), 8080 (NemakiWare core + UI), 8983 (Solr)

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
open http://localhost:8080/core/ui/login?repositoryId=bedroom
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
java -version  # Should be Java 21
mvn -version   # Should be 3.x
```

### Database Not Initialized
```bash
# Error: 404 on CMIS endpoints
# Solution: Check database initialization
curl -u admin:password http://localhost:5984/_all_dbs
# Should show: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]
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
| NemakiWare UI | http://localhost:8080/core/ui/ | admin/admin |

## Advanced Usage

### Multi-replica deployments

This compose file targets **a single core replica**. If you scale `core`
horizontally (e.g. `docker compose up -d --scale core=N` or in a
Kubernetes/ECS deployment), be aware:

- **SAML strict mode** (`saml.require.inResponseTo=true`) needs **cookie-
  based sticky sessions** on the load balancer. The
  `SamlAuthnRequestRegistry` and `SamlReplayCache` are JVM-local, so an
  IdP callback that lands on a different replica than the one that
  issued the AuthnRequest will fail strict validation, and replay
  protection is not shared across replicas. Set
  `-Dnemakiware.deployment.singleReplica=false` AND
  `-Dnemakiware.deployment.stickySession=true` to silence the loud
  startup warning once sticky sessions are in place.
- **Cron schedulers** (Cloud Directory Sync, Ingest, Retention,
  Lineage*) are leader-election gated. Set
  `lineage.leader-election.enabled=true` so only the leader replica
  performs the work.
- The plain compose-up path here is **single-replica only**. For HA
  configurations see `docs/AWS-DEPLOYMENT-GUIDE.md` §1
  (スケーラビリティの注意).

### Development Workflow

```bash
# Make changes to source code
vim ../core/src/main/java/...

# Rebuild and redeploy core.
#
# IMPORTANT: never use `docker compose restart core` here. The WAR is
# baked into the image at build time, so `restart` would re-launch the
# old container with the previous WAR and your changes would not take
# effect (this is what bit RC13 — see CLAUDE.md "重要" notice).
mvn clean package -f ../core/pom.xml
cp ../core/target/core.war core/core.war
export COUCHDB_USER=admin COUCHDB_PASSWORD=password   # required env
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core

# Or, from the repo root, the convenience target:
#   COUCHDB_USER=admin COUCHDB_PASSWORD=password make deploy

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