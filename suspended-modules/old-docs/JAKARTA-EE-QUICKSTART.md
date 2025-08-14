# Jakarta EE 10 Quickstart Guide

This document provides a fully working Jakarta EE 10 migration of NemakiWare with Java 17 + Tomcat 10.

## Quick Start

### Prerequisites
- Java 17+ (required for building - CRITICAL)
- Docker and Docker Compose
- Maven 3.6+

### Environment Setup

1. **Set Java 17 environment** (CRITICAL for building):
```bash
# Set JAVA_HOME to your Java 17 installation
export JAVA_HOME=/path/to/your/java-17
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Should show Java 17+

# Example paths for common installations:
# macOS (Homebrew): export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.x.x/libexec/openjdk.jdk/Contents/Home
# macOS (Oracle): export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.x.x.jdk/Contents/Home
# Linux: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
# Windows: set JAVA_HOME=C:\Program Files\Java\jdk-17.x.x
```

2. **Clone and navigate to project**:
```bash
git clone <repository-url>
cd NemakiWare
git checkout feature/jakarta-ee-10-stable  # or your target branch
```

3. **Execute the complete Jakarta EE test suite** (automated build and verification):
```bash
cd docker
./test-jakarta-complete.sh
```

This automated script will:
- Build Jakarta EE compatible WAR with correct profile
- Set up complete Docker environment
- Verify all services are working
- Run comprehensive query validation with detailed results
- Generate detailed reports

**Alternative manual steps:**

3a. **Build Jakarta EE compatible WAR** (preserves all critical fixes):
```bash
# Build with Jakarta profile to ensure proper Jakarta EE 10 compatibility
mvn clean package -f core/pom.xml -Pjakarta -DskipTests

# Verify WAR was created
ls -la core/target/core.war
```

3b. **Start the complete Jakarta EE environment**:
```bash
cd docker

# Copy the Jakarta-built WAR to Docker context
cp ../core/target/core.war core/core.war

# Build Docker image with Jakarta libraries
docker build --no-cache -t nemakiware-tomcat10 -f core/Dockerfile.jakarta core/

# Start the complete environment
docker compose -f docker-compose-jakarta-complete.yml up -d
```

3c. **Wait for initialization** (about 3-5 minutes):
```bash
# Check container health
docker compose -f docker-compose-jakarta-complete.yml ps

# Verify Core application startup (wait for "Server startup" message)
docker logs nemaki-core-tomcat10 --tail 20

# Wait for all initializers to complete
docker logs nemaki-initializer-bedroom
docker logs nemaki-initializer-canopy
```

### Verification

The `test-jakarta-complete.sh` script provides comprehensive verification automatically. You can also run manual tests:

1. **Test CMIS endpoints**:
```bash
# CMIS AtomPub
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# CMIS queries with result count validation
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:document"
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:folder"
```

2. **Check Solr search**:
```bash
curl "http://localhost:8983/solr/nemaki/select?q=*:*"
```

3. **Access CouchDB**:
```bash
curl -u admin:password http://localhost:5984/_all_dbs
```

4. **Review detailed validation report**:
```bash
# After running test-jakarta-complete.sh
cat docker/query-validation-report.txt
```

### Expected Results

After successful setup, you should see:
- ✅ Core CMIS endpoints returning HTTP 200
- ✅ All 4 repositories initialized: `bedroom`, `bedroom_closet`, `canopy`, `canopy_closet`
- ✅ CMIS queries returning actual results (not just 0 items)
- ✅ Solr search functionality working
- ✅ Complete query validation report with hit counts for each query type

## Key Features

✅ **Jakarta EE 10 Compatibility**
- Full namespace migration (`javax.servlet` → `jakarta.servlet`)
- Tomcat 10.1 with Jakarta EE 10
- OpenCMIS Jakarta-converted libraries

✅ **Automatic Database Initialization**
- CouchDB repositories: `bedroom`, `bedroom_closet`, `canopy`, `canopy_closet`
- Complete CMIS type system setup
- User and permission initialization

✅ **CMIS Query System**
- Fixed QueryObject.getMainFromName() NoSuchElementException
- Resolved TypeManagerImpl property inheritance issues
- Full CMIS SQL query support

✅ **Solr 9.x Integration**
- Modern Solr with Jakarta EE compatibility
- Automatic indexing and search functionality
- Clean separation of content and token cores

## Architecture

### Container Stack
- **nemaki-core-tomcat10**: Main CMIS server (Jakarta EE 10 + Tomcat 10.1)
- **nemaki-couchdb**: Document storage (CouchDB 3.3.3)
- **nemaki-solr**: Search engine (Solr 9.8)
- **nemaki-initializer-***: Database initialization containers

### Service URLs
- Core CMIS: http://localhost:8080/core
- Solr Admin: http://localhost:8983/solr
- CouchDB: http://localhost:5984 (admin:password)

### Authentication
- **CMIS**: admin:admin
- **CouchDB**: admin:password

## Development Workflow

### Building Changes
```bash
# Set Java 17 environment (CRITICAL - use your Java 17 path)
export JAVA_HOME=/path/to/your/java-17
export PATH=$JAVA_HOME/bin:$PATH

# Build core WAR with Jakarta profile (preserves all fixes)
mvn clean package -f core/pom.xml -Pjakarta

# Update Docker image
cp core/target/core.war docker/core/core.war
docker build --no-cache -t nemakiware-tomcat10 -f docker/core/Dockerfile.jakarta docker/core/

# Restart container
docker compose -f docker-compose-jakarta-complete.yml restart core
```

### Testing Changes
```bash
# Test CMIS functionality
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:document"

# Check application logs
docker logs nemaki-core-tomcat10 --tail 50
```

## Key Fixes Applied

**CRITICAL**: This Jakarta EE environment preserves all essential fixes from the development environment:

1. **Design Document Compatibility** ✅
   - `changesByToken` view correctly implemented in initialization dumps
   - Cloudant SDK `_design/_repo` document access fixed
   - All CouchDB views match standard CMIS specification (NEVER modified from provided spec)

2. **QueryObject Jakarta EE Compatibility** ✅
   - Fixed SQL parsing issues in Jakarta EE environment
   - Added comprehensive error handling for QueryObject.getMainFromName()
   - Resolved froms map population problems

3. **Type System Inheritance** ✅
   - Fixed TypeManagerImpl property definition clearing
   - Preserved CMIS type inheritance chain
   - Eliminated "Unknown property" errors

4. **Database Initialization** ✅
   - All 4 repositories automatically initialized: `bedroom`, `bedroom_closet`, `canopy`, `canopy_closet`
   - Proper authentication configuration for CouchDB 3.x
   - Complete design document structure with all required views

5. **Solr Integration** ✅
   - Updated to Solr 9.x with Jakarta compatibility
   - Fixed field mapping and indexing issues
   - Ensured proper data synchronization

6. **Jakarta EE Conversion** ✅
   - OpenCMIS libraries converted to Jakarta EE 10 namespaces
   - Spring Framework 6.x with Jakarta servlet support
   - Tomcat 10.1 with complete Jakarta EE 10 stack

## Troubleshooting

### Common Issues

1. **Java Version Errors**
   - Ensure Java 17+ is used for building (set your Java 17 path correctly)
   - Check JAVA_HOME environment variable: `echo $JAVA_HOME`
   - Verify Maven is using Java 17: `mvn -version`
   - If using multiple Java versions, ensure PATH gives priority to Java 17

2. **Container Startup Issues**
   - Wait for all initializer containers to complete (3-5 minutes)
   - Check Docker logs for specific error messages
   - Ensure proper dependency order: CouchDB → Initializers → Solr → Core

3. **CMIS Query Failures**
   - Verify Core container health with: `curl http://localhost:8080/core`
   - Check Solr connectivity: `curl http://localhost:8983/solr/`
   - Ensure CouchDB authentication: `curl -u admin:password http://localhost:5984/_all_dbs`

4. **Design Document Issues** (CRITICAL - from past experience)
   - If you see "view not found" errors, **DO NOT** modify code
   - The issue is always in initialization data, not in code
   - All views must match the standard CMIS specification exactly
   - Check that `changesByToken` view exists (NOT `latestChange`)

5. **Jakarta EE Compatibility Issues**
   - Ensure `-Pjakarta` profile is used for all builds
   - Verify Jakarta JARs are properly installed in WAR file
   - Check for javax.servlet conflicts in logs

### Debug Commands
```bash
# Check container status
docker compose -f docker-compose-jakarta-complete.yml ps

# View detailed logs
docker logs nemaki-core-tomcat10
docker logs nemaki-couchdb
docker logs nemaki-solr

# Test individual components
curl -u admin:password http://localhost:5984/_all_dbs
curl http://localhost:8983/solr/
curl http://localhost:8080/core
```

## Critical Design Constraints

**ABSOLUTE RULE**: Design documents and CouchDB views MUST NEVER be modified from the standard CMIS specification to accommodate code issues. This ensures compatibility with existing user environments.

### Why This Matters
- Existing production environments depend on standard view definitions
- Code must be adapted to work with standard design documents, not vice versa
- The `changesByToken` view name is part of the standard and cannot be changed
- All 45 standard CMIS views must be supported exactly as specified

### Common Mistakes to Avoid
- ❌ Changing view names in code (e.g., using `latestChange` instead of `changesByToken`)
- ❌ Modifying view map functions to match code expectations
- ❌ Adding custom fields to standard CMIS views
- ✅ Fixing code to work with standard view definitions
- ✅ Adding custom views alongside standard ones (if needed)
- ✅ Ensuring initialization data matches standard specifications exactly

## Migration Notes

This environment represents a complete migration from:
- **Before**: Java 8/11 + Tomcat 8/9 + javax.servlet
- **After**: Java 17 + Tomcat 10.1 + jakarta.servlet + Jakarta EE 10

All core functionality has been preserved and enhanced with modern Jakarta EE standards, while maintaining strict compatibility with standard CMIS design documents.