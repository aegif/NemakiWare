# Jakarta EE 10 Quickstart Guide

This branch (`feature/jakarta-ee-10-stable`) provides a fully working Jakarta EE 10 migration of NemakiWare with Java 17 + Tomcat 10.

## Quick Start

### Prerequisites
- Java 17+ (required for building)
- Docker and Docker Compose
- Maven 3.6+

### Environment Setup

1. **Set Java 17 environment** (CRITICAL for building):
```bash
export JAVA_HOME=/path/to/java-17
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Should show Java 17+
```

2. **Clone and checkout this branch**:
```bash
git clone <repository-url>
cd NemakiWare
git checkout feature/jakarta-ee-10-stable
```

3. **Start the complete Jakarta EE environment**:
```bash
cd docker
docker compose -f docker-compose-jakarta-complete.yml up -d
```

4. **Wait for initialization** (about 2-3 minutes):
```bash
# Check container health
docker compose -f docker-compose-jakarta-complete.yml ps

# Verify Core application startup
docker logs nemaki-core-tomcat10 --tail 20
```

### Verification

1. **Test CMIS endpoints**:
```bash
# CMIS AtomPub
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# CMIS queries
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
# Set Java 17 environment
export JAVA_HOME=/path/to/java-17
export PATH=$JAVA_HOME/bin:$PATH

# Build core WAR
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

1. **QueryObject Jakarta EE Compatibility**
   - Fixed SQL parsing issues in Jakarta EE environment
   - Added comprehensive error handling for QueryObject.getMainFromName()
   - Resolved froms map population problems

2. **Type System Inheritance**
   - Fixed TypeManagerImpl property definition clearing
   - Preserved CMIS type inheritance chain
   - Eliminated "Unknown property" errors

3. **Solr Integration**
   - Updated to Solr 9.x with Jakarta compatibility
   - Fixed field mapping and indexing issues
   - Ensured proper data synchronization

## Troubleshooting

### Common Issues

1. **Java Version Errors**
   - Ensure Java 17+ is used for building
   - Check JAVA_HOME environment variable

2. **Container Startup Issues**
   - Wait for all initializer containers to complete
   - Check Docker logs for specific error messages

3. **CMIS Query Failures**
   - Verify Core container health
   - Check Solr connectivity
   - Ensure CouchDB authentication

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

## Migration Notes

This branch represents a complete migration from:
- **Before**: Java 8/11 + Tomcat 8/9 + javax.servlet
- **After**: Java 17 + Tomcat 10.1 + jakarta.servlet + Jakarta EE 10

All core functionality has been preserved and enhanced with modern Jakarta EE standards.