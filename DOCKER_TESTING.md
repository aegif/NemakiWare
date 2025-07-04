# NemakiWare Docker Testing Guide

## Overview

This document outlines the testing strategy for NemakiWare Docker environment with **Jakarta EE 10 + Metro RI + Tomcat 10 + Java 17 + CouchDB 3.x** migration, focusing on **stable build process** and **CMIS functionality verification**.

## ⚠️ CRITICAL: Jakarta EE 10 Migration Completed (2025-07-03)

**ALWAYS use the Jakarta build process for all testing and development.**

### Mandatory Build Process

```bash
# 1. ALWAYS use Jakarta build process
./docker/build-jakarta.sh

# 2. ALWAYS deploy with Jakarta scripts  
./docker/deploy-jakarta.sh

# 3. Verify CMIS endpoints
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# Expected: HTTP 200
```

**JAR Conflict Prevention:**
- ✅ Maven antrun plugin automatically manages JAR conflicts
- ✅ Jakarta-converted OpenCMIS JARs (8 files)
- ✅ Metro RI JAX-WS Runtime (2.7MB)
- ❌ NEVER manually manage OpenCMIS JARs
- ❌ NEVER use legacy build commands

### Key Modernization Components

- **Jakarta EE**: Complete migration from javax.* to jakarta.* namespace
- **Tomcat 10**: Jakarta EE compatible servlet container (simplified single-version setup)
- **Java 17**: Core modules upgraded from Java 8 to Java 17
- **CouchDB 3.x**: Enhanced security and performance with mandatory authentication
- **Simplified Environment**: Standard container naming (nemaki-couchdb, nemaki-core)

## Testing Strategy

### Core Component Testing (Simplified Environment)

The testing approach prioritizes the CMIS core functionality in a simplified Docker environment:

1. **Core Services** - CMIS AtomPub, Browser, WebServices endpoints with Jakarta EE + Tomcat 10
2. **CouchDB 3.x** - Latest stable CouchDB version with mandatory authentication
3. **Jakarta EE Migration** - Complete servlet modernization with OpenCMIS 1.1.0
4. **Authentication System** - Restored user authentication with security upgrades
5. **Simplified Container Architecture** - Standard naming without version complexity

### Benefits of Simplified Environment

- **Consistent naming** - Standard container names (nemaki-couchdb, nemaki-core)
- **Single Tomcat version** - Tomcat 10 only for Jakarta EE compatibility
- **Reduced complexity** - No multi-version testing confusion
- **Better maintainability** - Clear container relationships and networking

## Quick Start Commands

### 1. Environment Setup (Simplified Tomcat 10 + CouchDB 3.x)

```bash
cd docker/

# Clean previous environment
docker compose down --remove-orphans

# Start the simplified environment
docker compose up -d

# Wait for services to initialize
sleep 30

# Verify services are running
curl -u admin:password http://localhost:5984/_up
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### Manual Step-by-Step Startup

```bash
cd docker/

# Start CouchDB first (required for database initialization)
docker compose up -d couchdb
sleep 20

# Initialize databases
docker compose up -d initializer
sleep 10

# Start Core application with Jakarta EE + Tomcat 10
docker compose up -d core
sleep 30

# Verify all services
docker compose ps
```

### 2. Authentication Testing

```bash
# Test CouchDB connectivity
curl -u admin:password http://localhost:5984/_up

# Test CMIS authentication
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Test user authentication system
curl -u admin:admin -X POST http://localhost:8080/core/browser/bedroom \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "cmisaction=getRepositoryInfo"
```

### 3. CMIS Functionality Testing

```bash
# Test repository information
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Test CMIS queries via AtomPub binding (CORRECT FORMAT)
# IMPORTANT: Use application/cmisquery+xml content type with proper XML structure

# Test document query
curl -u admin:admin -X POST \
  -H "Content-Type: application/cmisquery+xml; charset=UTF-8" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<cmis:query xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <cmis:statement>SELECT * FROM cmis:document</cmis:statement>
  <cmis:maxItems>5</cmis:maxItems>
</cmis:query>' \
  http://localhost:8080/core/atom/bedroom/query

# Test folder query
curl -u admin:admin -X POST \
  -H "Content-Type: application/cmisquery+xml; charset=UTF-8" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<cmis:query xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <cmis:statement>SELECT * FROM cmis:folder</cmis:statement>
  <cmis:maxItems>5</cmis:maxItems>
</cmis:query>' \
  http://localhost:8080/core/atom/bedroom/query

# Test query with WHERE clause
curl -u admin:admin -X POST \
  -H "Content-Type: application/cmisquery+xml; charset=UTF-8" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<cmis:query xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <cmis:statement>SELECT cmis:objectId, cmis:name FROM cmis:folder WHERE cmis:name LIKE '\''%Root%'\''</cmis:statement>
  <cmis:searchAllVersions>false</cmis:searchAllVersions>
  <cmis:includeAllowableActions>true</cmis:includeAllowableActions>
</cmis:query>' \
  http://localhost:8080/core/atom/bedroom/query

# Alternative: Browser binding query (simpler form-encoded format)
curl -u admin:admin -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "cmisaction=query" \
  -d "q=SELECT * FROM cmis:folder" \
  -d "maxItems=5" \
  http://localhost:8080/core/browser/bedroom
```

### CMIS AtomPub Query Format Reference

**Key Points for AtomPub Queries:**
- Content-Type: `application/cmisquery+xml; charset=UTF-8`
- Use XML structure with `cmis:query` root element
- Query statement goes in `cmis:statement` element
- Namespace: `http://docs.oasis-open.org/ns/cmis/core/200908/`

**Common Query Parameters:**
- `cmis:statement` (required): The CMIS SQL query
- `cmis:maxItems` (optional): Limit number of results
- `cmis:skipCount` (optional): For pagination
- `cmis:searchAllVersions` (optional): Include all versions
- `cmis:includeAllowableActions` (optional): Include allowed operations

**Response Format:**
- Returns Atom feed (`application/atom+xml;type=feed`)
- Results are in `atom:entry` elements
- Metadata in `cmisra:numItems` for result count

## Service Configuration

### Docker Compose Services (Simplified Environment)

```yaml
services:
  couchdb:      # Document storage (CouchDB 3.x) - container: nemaki-couchdb
  initializer:  # Database initialization - container: nemaki-initializer  
  core:         # CMIS server (Jakarta EE + Tomcat 10) - container: nemaki-core
```

### Service Dependencies

```
core → depends_on → [initializer:completed]
initializer → depends_on → [couchdb:healthy]
couchdb → standalone (CouchDB 3.x with authentication)
```

### Container Names and Hostnames

- **CouchDB**: Container `nemaki-couchdb`, internal hostname `couchdb`, external port `5984`
- **Core**: Container `nemaki-core`, internal hostname `core`, external port `8080`
- **Initializer**: Container `nemaki-initializer`, runs once and exits

### Environment Variables

```yaml
couchdb:
  environment:
    - COUCHDB_USER=admin
    - COUCHDB_PASSWORD=password

core:
  environment:
    - JAVA_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED
    - CATALINA_OPTS=-Xms512m -Xmx1024m -XX:+DisableExplicitGC
```

## Test Categories

### 1. Core CMIS Functionality

**Focus**: Jakarta EE compatibility and authentication

- **Repository Services** - Basic repository operations
- **Authentication System** - User login and security
- **Document Operations** - Create, read, update, delete
- **Folder Management** - Folder hierarchy operations
- **CMIS Protocol Bindings** - AtomPub, Browser, WebServices

### 2. Integration Points

- **CouchDB 3.x** - Database connectivity and views
- **Jakarta EE Migration** - Servlet compatibility
- **Authentication Security** - MD5 to BCrypt upgrade
- **Property Configuration** - Spring property loading

### 3. Performance Metrics

- **Startup Time** - < 60 seconds for all services
- **Response Time** - < 200ms for basic operations
- **Memory Usage** - < 1.5GB total for containers
- **Authentication Response** - < 100ms for login operations

## Known Issues and Solutions

### 1. Fixed Issues (Jakarta EE + Tomcat 10 Migration)

✅ **Jakarta EE Migration** - Complete transition from javax.* to jakarta.* namespace
✅ **Tomcat 10 Compatibility** - Jakarta EE servlet container deployment
✅ **OpenCMIS 1.1.0** - Jakarta EE compatible CMIS implementation
✅ **Authentication System** - Complete restoration with security upgrades
✅ **CouchDB Connection** - Hostname resolution and property configuration
✅ **Docker Environment** - Simplified container naming and networking

### 2. Current Status (Jakarta EE + Tomcat 10 Environment)

- **Core Application**: ✅ Functional with Jakarta EE + Tomcat 10
- **CouchDB 3.x Integration**: ✅ Cloudant SDK with mandatory authentication
- **Authentication System**: ✅ MD5 to BCrypt security upgrade implemented
- **CMIS Protocol**: ✅ AtomPub, Browser, WebServices endpoints working
- **Property Configuration**: ✅ Spring property loading with external overrides
- **Container Environment**: ✅ Simplified naming (nemaki-couchdb, nemaki-core)

## Troubleshooting

### Common Issues

1. **Core startup failures**
   ```bash
   # Check logs
   docker logs nemaki-core --tail 50
   
   # Verify CouchDB connectivity
   curl -u admin:password http://localhost:5984/_up
   curl -u admin:password http://localhost:5984/_all_dbs
   ```

2. **Authentication issues**
   ```bash
   # Test CouchDB authentication
   curl -u admin:password http://localhost:5984/bedroom
   
   # Test CMIS authentication
   curl -u admin:admin http://localhost:8080/core/atom/bedroom
   
   # Check user data in CouchDB
   curl -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/userItemsById?key=\"admin\""
   ```

3. **Container networking issues**
   ```bash
   # Verify container status
   docker compose ps
   
   # Check container connectivity
   docker exec nemaki-core curl http://couchdb:5984/_up
   
   # Test internal networking
   docker network inspect nemaki-network
   ```

4. **Jakarta EE compatibility issues**
   ```bash
   # Verify Java version
   docker exec nemaki-core java -version
   
   # Check servlet container logs
   docker logs nemaki-core | grep -E "jakarta|servlet"
   
   # Verify OpenCMIS loading
   docker logs nemaki-core | grep -i "chemistry"
   ```

5. **Property configuration issues**
   ```bash
   # Check property files
   docker exec nemaki-core cat /usr/local/tomcat/conf/nemakiware.properties
   
   # Verify Spring context loading
   docker logs nemaki-core | grep -E "PropertyPlaceholder|properties"
   
   # Check CouchDB URL configuration
   docker logs nemaki-core | grep "couchdb.url"
   ```

### Performance Optimization

1. **Database Initialization**
   - Ensure all 4 repositories exist (bedroom, bedroom_closet, canopy, canopy_closet)
   - Verify design documents are properly created
   - Confirm admin user exists with correct authentication

2. **Container Resource Limits**
   ```yaml
   deploy:
     resources:
       limits:
         memory: 1G
         cpus: '1.0'
   ```

3. **Java 17 Heap Settings**
   ```bash
   CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC --add-opens java.base/java.lang=ALL-UNNAMED"
   ```

## Expected Results

### Core Functionality Status

- **Repository Services**: ✅ Working (HTTP 200)
- **Authentication**: ✅ Working (admin:admin)
- **CMIS AtomPub**: ✅ Working (HTTP 200)
- **CMIS Browser**: ✅ Working (HTTP 200)
- **CMIS WebServices**: ✅ Working (HTTP 200)
- **Document Operations**: ✅ Basic CRUD operations functional

### Overall Target: **Complete CMIS 1.1 Compliance**

## Automation Scripts

### Quick Test Suite

```bash
#!/bin/bash
# quick-test.sh - Fast core functionality test

cd docker/

echo "🚀 Starting simplified environment..."
docker compose up -d

echo "⏱️ Waiting for services..."
sleep 30

echo "🔍 Verifying core functionality..."
curl -u admin:password http://localhost:5984/_up
curl -u admin:admin http://localhost:8080/core/atom/bedroom

echo "✅ Basic functionality verified"
```

### Environment Reset

```bash
#!/bin/bash
# reset-environment.sh - Clean restart

cd docker/

echo "🧹 Cleaning environment..."
docker compose down --remove-orphans

echo "🚀 Starting fresh..."
docker compose up -d

echo "⏱️ Waiting for initialization..."
sleep 30

echo "🔍 Verifying services..."
docker compose ps
```

## Maintenance

### Regular Tasks

1. **Weekly functionality tests** - Verify CMIS endpoints
2. **Log rotation** - Manage container log sizes  
3. **Database maintenance** - Monitor CouchDB performance
4. **Authentication monitoring** - Track login success rates

### Status Monitoring

```bash
# Check service health
docker compose ps

# Monitor logs
docker logs nemaki-core --tail 20
docker logs nemaki-couchdb --tail 20

# Test endpoints
curl -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom
```

## Configuration Reference

### Docker Compose Structure

```yaml
# Simplified docker-compose.yml
services:
  couchdb:
    image: couchdb:3.3
    container_name: nemaki-couchdb
    environment:
      - COUCHDB_USER=admin
      - COUCHDB_PASSWORD=password
    ports:
      - "5984:5984"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5984/_up"]

  initializer:
    image: openjdk:17-jdk-slim
    container_name: nemaki-initializer
    depends_on:
      couchdb:
        condition: service_healthy
    # Database initialization commands

  core:
    build:
      context: ./core
      dockerfile: Dockerfile
    container_name: nemaki-core
    depends_on:
      - initializer
    ports:
      - "8080:8080"
    # Jakarta EE + Tomcat 10 deployment
```

---

**Last Updated**: 2025-07-03
**Target Environment**: Docker Compose with Jakarta EE + Tomcat 10 + CouchDB 3.x
**Architecture**: Simplified single-version environment
**CouchDB Version**: 3.3 (Latest stable with mandatory authentication)
**Java Version**: 17
**Servlet Container**: Tomcat 10 (Jakarta EE compatible)