# NemakiWare Database Initialization Reference

## Overview

This document provides a comprehensive reference for NemakiWare's database initialization process. NemakiWare uses CouchDB as its NoSQL backend and employs a multi-repository architecture where each repository maps to a separate CouchDB database.

## Database Architecture

### Core Databases Created

1. **nemaki_conf** - System configuration database
2. **Repository databases** (e.g., "bedroom", "canopy") - Content repositories  
3. **Archive databases** (e.g., "bedroom_closet", "canopy_closet") - Archive repositories

### Database Naming Convention

- Main repositories: Use repository ID directly (e.g., "bedroom")
- Archive repositories: Repository ID + "_closet" suffix (e.g., "bedroom_closet")
- Configuration database: Always "nemaki_conf"

## Initialization Components

### 1. Docker Initialization (`docker/initializer/`)

**Key Files:**
- `entrypoint.sh` - Main initialization script
- `cloudant-init.jar` - Java-based database initializer
- `bedroom_init.dump` - Main repository initialization data
- `archive_init.dump` - Archive repository initialization data
- `dump/init.json` - Repository configuration metadata

### 2. CouchDB Initialization (`setup/couchdb/`)

**Key Files:**
- `cloudant-init/` - Java initializer source code
- `initial_import/` - Dump files for initialization
- `dump/init.json` - Repository definitions

### 3. Installer Components (`setup/installer/`)

**Key Files:**
- `install.xml` - IzPack installer configuration
- `user-input-spec.xml` - User input specification
- `tomcat/` - Application server configuration templates

## Database Initialization Process

### Phase 1: Configuration Database Setup

The `nemaki_conf` database is created first with the following structure:

```bash
# Database creation
curl -X PUT ${COUCHDB_URL}/nemaki_conf

# Design document creation
curl -X PUT ${COUCHDB_URL}/nemaki_conf/_design/_repo \
    -H "Content-Type: application/json" \
    -d '{
        "_id": "_design/_repo",
        "views": {
            "configuration": {
                "map": "function(doc) { if (doc.type == '\''configuration'\'')  emit(doc._id, doc) }"
            }
        }
    }'

# Configuration document
curl -X POST ${COUCHDB_URL}/nemaki_conf \
    -H "Content-Type: application/json" \
    -d '{
        "type": "configuration",
        "configuration": {
            "cmis.server.default.max.items.types": "50",
            "cmis.server.default.depth.types": "-1",
            "cmis.server.default.max.items.objects": "200",
            "cmis.server.default.depth.objects": "10"
        }
    }'
```

### Phase 2: Repository Database Creation

Each repository database is created using the `CouchDBInitializer` Java class:

**Initialization Arguments:**
```bash
java -cp cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
    ${COUCHDB_URL} \
    ${COUCHDB_USERNAME} \
    ${COUCHDB_PASSWORD} \
    ${REPOSITORY_ID} \
    ${DUMP_FILE} \
    ${FORCE}
```

**Process:**
1. Check if database exists
2. Delete if force=true and exists
3. Create new database
4. Import dump file data

### Phase 3: Data Import from Dump Files

#### Main Repository (bedroom_init.dump)

The bedroom_init.dump contains the following key documents:

**System Users:**
- `admin` user (ID: 94ee2cb22e260d582b5d66903f001436)
  - Password hash: `$2a$10$0goveaxLj.2UJAlVN1Ru2OmGID6q04ugln/HAkVpzb5bwvZ.kvEne`
  - Admin privileges: true

- `system` user (ID: 94ee2cb22e260d582b5d66903f002175)
  - Internal system user
  - Admin privileges: true

- `solr` user (ID: 6174536bc714bfc3294b00306f000d5d)
  - Search engine user
  - Password hash: `$2a$10$YTaQCKmmf.f2XkcOPjVAVe1q3RR.Z2/YD6sotC/Kz.ZVlBYqhjIW6`
  - Admin privileges: true

**Folder Structure:**
- Root folder (`/`) - ID: e02f784f8360a02cc14d1314c10038ff
- Sites folder (`sites`) - ID: e02f784f8360a02cc14d1314c1003f06
- Sample Site folder - ID: b11bf1bf25317a8fa2941b8f140148b6

**Type Definitions:**
- `nemaki:document` type definition
- `nemaki:tag` property definition (STRING, MULTI cardinality)

**Change Tracking:**
- Change events for folder creation
- Token-based change tracking system

#### Archive Repository (archive_init.dump)

Minimal initialization with only the design document containing views for:
- Document/folder retrieval by originalId
- Path-based lookups
- Version series management
- Creation date sorting

## CouchDB Design Documents and Views

### Main Repository Views (`_design/_repo`)

**Content Management Views:**
- `contentsById` - All CMIS objects by ID
- `documents` - Document objects only
- `folders` - Folder objects only  
- `items` - Item objects only
- `policies` - Policy objects only

**Hierarchy and Navigation Views:**
- `children` - Child objects by parent ID
- `childByName` - Children by parent ID and name
- `childrenNames` - Child names by parent ID
- `foldersByPath` - Folders by path

**User and Group Management Views:**
- `users` - All users
- `usersById` - Users by user ID
- `groups` - All groups
- `groupsById` - Groups by group ID
- `admin` - Admin users only

**Version Management Views:**
- `versionSeries` - Version series objects
- `documentsByVersionSeriesId` - Documents by version series
- `latestVersions` - Latest document versions
- `latestMajorVersions` - Latest major versions
- `privateWorkingCopies` - Private working copies

**Relationship and Policy Views:**
- `relationships` - All relationships
- `relationshipsBySource` - Relationships by source object
- `relationshipsByTarget` - Relationships by target object
- `policiesByAppliedObject` - Policies by applied object

**Type System Views:**
- `typeDefinitions` - Type definitions by type ID
- `propertyDefinitionCores` - Core property definitions
- `propertyDefinitionCoresByPropertyId` - Property definitions by property ID
- `propertyDefinitionDetails` - Detailed property definitions
- `propertyDefinitionDetailsByCoreNodeId` - Property details by core node

**Change Tracking Views:**
- `changes` - All change events
- `changesByToken` - Changes by token (for incremental sync)

**Other Views:**
- `attachments` - File attachments
- `renditions` - Document renditions
- `countByObjectType` - Object count by type (with reduce function)

### Archive Repository Views (`_design/_repo`)

**Simplified Archive Views:**
- `all` - All archived objects by originalId
- `allByCreated` - All objects by creation date
- `documents` - Archived documents by originalId
- `folders` - Archived folders by originalId
- `attachments` - Archived attachments by originalId
- `path` - Objects by path
- `children` - Children by parent ID
- `versionSeries` - Documents by version series ID

## Configuration Files and Property Sources

### Core Configuration (`nemakiware.properties`)

**Database Configuration:**
```properties
db.couchdb.url=http://couchdb2:5984
db.couchdb.max.connections=20
db.couchdb.connection.timeout=30000
db.couchdb.socket.timeout=60000
db.couchdb.auth.enabled=true
db.couchdb.auth.username=admin
db.couchdb.auth.password=password
```

**CMIS Server Defaults:**
```properties
cmis.server.default.max.items.types=50
cmis.server.default.depth.types=-1
cmis.server.default.max.items.objects=200
cmis.server.default.depth.objects=10
```

**Repository Configuration:**
```properties
repository.definition.default=repositories-default.yml
repository.definition=repositories.yml
```

**Search Engine Configuration:**
```properties
solr.protocol=http
solr.host=solr
solr.port=8983
solr.context=solr
solr.indexing.force=false
solr.nemaki.userid=solr
```

### Repository Definitions (`repositories.yml`)

Standard configuration:
```yaml
repositories:
  - id: canopy
    name: canopy
    archive: canopy_closet
  - id: bedroom
    name: bedroom
    archive: bedroom_closet
```

### Default Repository Settings (`repositories-default.yml`)

```yaml
default:
  description: NemakiWare, Lightweight CMIS Server
  root: e02f784f8360a02cc14d1314c10038ff
  principal.anonymous: anonymous
  principal.anyone: GROUP_EVERYONE
  thinClientUri: http://localhost:8080/ui/
  vendor: aegif
  product.name: NemakiWare
  product.version: 2.4.1
  namespace: http://www.aegif.jp/NemakiWare/
super.users: canopy
```

### Application Server Templates

**Core Server Template (`app-server-core.properties`):**
```properties
cmis.server.port=$tomcat.port
repository.definition=app-server-core-repositories.yml
db.couchdb.url=$db.couchdb.url
solr.port=$tomcat.port
```

**Repository Template (`app-server-core-repositories.yml`):**
```yaml
repositories:
  - id: canopy
    name: canopy
    archive: canopy_closet
    thinClientUri: http://localhost:8080/ui/repo/canopy/
  - id: $cmis.repository.main
    name: $cmis.repository.main
    archive: ${cmis.repository.main}_closet
    thinClientUri: http://localhost:8080/ui/repo/$cmis.repository.main/
```

## User Accounts and Permissions

### Default System Users

1. **admin**
   - User ID: admin
   - Password: admin (BCrypt hashed)
   - Admin privileges: true
   - Purpose: System administration

2. **system**
   - User ID: system
   - No password (internal use only)
   - Admin privileges: true
   - Purpose: Internal system operations

3. **solr**
   - User ID: solr
   - Password: solr (BCrypt hashed) 
   - Admin privileges: true
   - Purpose: Search engine operations

### Permission Model

**Default ACL Structure:**
- Root folder has CMIS_ANYONE read permission
- Sites folder inherits permissions from root
- Sample Site folder inherits permissions from parent

**Permission Principals:**
- `CMIS_ANYONE` - Anonymous access
- `GROUP_EVERYONE` - All authenticated users
- `anonymous` - Anonymous user principal

## System Documents and Metadata

### Change Tracking Documents

Each content operation creates change tracking documents:
- `type: "change"`
- `changeType: "CREATED"/"UPDATED"/"DELETED"`
- `token` - Timestamp-based token for ordering
- `latest` - Boolean flag for latest change

### Type System Documents

**Core Property Definitions:**
- Document type: `propertyDefinitionCore`
- Contains: propertyId, queryName, propertyType, cardinality

**Detailed Property Definitions:**
- Document type: `propertyDefinitionDetail`
- Contains: displayName, description, required, orderable, updatability, queryable

**Type Definitions:**
- Document type: `typeDefinition`
- Contains: typeId, displayName, baseId, parentId, properties array

### Version Series Documents

- Document type: `versionSeries`
- Manages document versioning metadata
- Links to all versions of a document

## Initialization Sequence and Dependencies

### Complete Initialization Workflow

1. **Environment Setup**
   - Wait for CouchDB service availability
   - Parse connection parameters
   - Authenticate with CouchDB

2. **Configuration Database**
   - Create `nemaki_conf` database
   - Create configuration design document
   - Insert default configuration values

3. **Repository Database Creation**
   - For each repository in init.json:
     - Create main repository database
     - Create archive repository database
     - Import respective dump files

4. **Data Import Process**
   - Parse dump file (JSON array format)
   - For each document in dump:
     - Remove `_rev` field (fresh import)
     - PUT document to database
     - Handle conflicts (skip existing docs)

5. **Verification**
   - Verify database creation
   - Verify document import counts
   - Check design document creation

### Dependencies and Prerequisites

**Required Services:**
- CouchDB 2.x+ running and accessible
- Valid CouchDB credentials (if auth enabled)

**Required Files:**
- `bedroom_init.dump` - Main repository data
- `archive_init.dump` - Archive repository data
- `init.json` - Repository definitions
- `cloudant-init.jar` - Initialization tool

**Required Permissions:**
- Database creation rights in CouchDB
- Document read/write permissions
- Design document creation permissions

## Error Handling and Recovery

### Common Initialization Issues

1. **Database Already Exists**
   - Solution: Use force=true to recreate
   - Impact: All existing data lost

2. **Authentication Failures**
   - Check CouchDB credentials
   - Verify user has database creation rights

3. **Dump File Format Issues**
   - Verify JSON format validity
   - Check for required document fields

4. **Network Connectivity**
   - Verify CouchDB URL accessibility
   - Check firewall and network settings

### Recovery Procedures

**Partial Initialization Failure:**
1. Identify failed component
2. Clean up partially created resources
3. Re-run initialization with force=true

**Data Corruption:**
1. Stop all services
2. Backup any valid data
3. Delete corrupted databases
4. Re-run complete initialization

## File Reference Summary

### Initialization Scripts
- `/docker/initializer/entrypoint.sh` - Main Docker initialization
- `/setup/couchdb/cloudant-init/src/main/java/jp/aegif/nemaki/cloudantinit/CouchDBInitializer.java` - Modern initializer

### Data Files
- `/setup/couchdb/initial_import/bedroom_init.dump` - Main repository data
- `/setup/couchdb/initial_import/archive_init.dump` - Archive repository data
- `/setup/couchdb/dump/init.json` - Repository definitions
- `/docker/initializer/dump/bedroom_init.dump` - Docker copy of main data

### Configuration Templates
- `/core/src/main/webapp/WEB-INF/classes/nemakiware.properties` - Core properties
- `/core/src/main/webapp/WEB-INF/classes/repositories.yml` - Repository definitions
- `/core/src/main/webapp/WEB-INF/classes/repositories-default.yml` - Default settings
- `/setup/installer/tomcat/app-server-core.properties` - Server template
- `/setup/installer/tomcat/app-server-core-repositories.yml` - Repository template

### Build and Deployment
- `/setup/installer/install.xml` - IzPack installer configuration
- `/setup/installer/user-input-spec.xml` - User input specification
- `/setup/installer/make.sh` - Installer build script

This reference provides the complete picture of NemakiWare's database initialization process, from the file-level details to the high-level workflow, enabling full understanding and troubleshooting of the system setup.