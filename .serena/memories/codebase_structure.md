# Codebase Structure and Architecture

## Root Level Structure

```
NemakiWare/
├── core/                    # Main CMIS repository server (Spring WAR)
├── common/                  # Shared utilities and models
├── solr/                    # Search engine customization
├── cloudant-init/           # Database initialization tool
├── suspended-modules/       # Modules out of maintenance scope
│   ├── aws/                 # S3 integration tools (suspended)
│   ├── action/              # Plugin framework (suspended)
│   └── action-sample/       # Plugin samples (suspended)
├── docker/                  # Docker configuration and compose files
├── setup/                   # Installation and setup scripts
├── lib/                     # Jakarta-converted library JARs
├── pom.xml                  # Root Maven configuration
├── CLAUDE.md                # Primary development documentation
└── qa-test.sh               # Comprehensive integration test script
```

## Core Module Structure (Main Application)

```
core/
├── src/main/java/jp/aegif/nemaki/
│   ├── cmis/                # CMIS server implementation
│   ├── rest/                # REST API endpoints
│   │   ├── controller/      # Spring MVC controllers
│   │   └── provider/        # JAX-RS providers
│   ├── dao/                 # Data access layer
│   │   ├── impl/couch/      # CouchDB implementation
│   │   └── impl/cached/     # Caching decorators
│   ├── model/               # Domain models
│   │   ├── couch/           # CouchDB-specific models
│   │   └── cmis/            # CMIS-specific models
│   ├── businesslogic/       # Business logic services
│   ├── util/                # Utility classes
│   ├── config/              # Spring configuration
│   ├── patch/               # Database patches and initialization
│   └── init/                # Application initialization
├── src/main/webapp/
│   ├── ui/                  # React SPA frontend
│   │   ├── src/             # TypeScript React source
│   │   │   ├── components/  # React components
│   │   │   ├── services/    # API service layer
│   │   │   └── types/       # TypeScript type definitions
│   │   ├── dist/            # Built UI assets
│   │   ├── package.json     # Node.js dependencies
│   │   └── vite.config.ts   # Vite build configuration
│   └── WEB-INF/
│       ├── classes/         # Spring configuration files
│       └── web.xml          # Servlet configuration
└── pom.xml                  # Core module Maven configuration
```

## Key Java Packages

### Core CMIS Implementation
- **`jp.aegif.nemaki.cmis`**: CMIS 1.1 server implementation
  - Apache Chemistry OpenCMIS integration
  - AtomPub, Browser, and Web Services bindings
  - CMIS servlet implementations

### REST API Layer
- **`jp.aegif.nemaki.rest`**: JAX-RS REST endpoints
  - Repository management
  - User and group management
  - Authentication and authorization
  - Search integration
  - Archive operations

### Data Access Layer
- **`jp.aegif.nemaki.dao`**: Repository pattern implementation
  - Interface definitions for data operations
  - CouchDB-specific implementations
  - Caching decorators using EhCache

### Business Logic Layer
- **`jp.aegif.nemaki.businesslogic`**: Core business services
  - Document lifecycle management
  - Permission evaluation
  - Search indexing coordination
  - Archive management

### Domain Models
- **`jp.aegif.nemaki.model`**: Domain object definitions
  - CMIS property definitions
  - CouchDB document models
  - User and group models

## React UI Structure

```
core/src/main/webapp/ui/src/
├── components/
│   ├── DocumentList/        # Document browsing and management
│   ├── DocumentViewer/      # Document preview and properties
│   ├── Login/               # Authentication components
│   ├── UserManagement/      # User and group administration
│   └── common/              # Shared UI components
├── services/
│   ├── auth.ts              # Authentication service
│   ├── cmis.ts              # CMIS API client
│   └── api.ts               # Base API utilities
├── types/
│   ├── cmis.ts              # CMIS type definitions
│   └── auth.ts              # Authentication types
├── App.tsx                  # Main application component
└── main.tsx                 # Application entry point
```

## Docker Configuration Structure

```
docker/
├── docker-compose-simple.yml   # Main 3-container setup (core, couchdb, solr)
├── core/
│   ├── Dockerfile.simple       # Core application container
│   ├── core.war                # Deployed WAR file
│   ├── nemakiware.properties   # Application configuration
│   └── repositories.yml        # Repository definitions
├── solr/
│   ├── Dockerfile              # Solr container with customizations
│   └── solr/                   # Solr configuration and cores
└── initializer/                # Database initialization (deprecated)
```

## Configuration Files Location

### Spring Configuration
- **`core/src/main/webapp/WEB-INF/classes/applicationContext.xml`**: Main Spring context
- **`core/src/main/webapp/WEB-INF/classes/serviceContext.xml`**: CMIS service definitions
- **`core/src/main/webapp/WEB-INF/classes/daoContext.xml`**: Data access configuration
- **`core/src/main/webapp/WEB-INF/classes/couchContext.xml`**: CouchDB configuration

### Database Configuration
- **`setup/couchdb/initial_import/bedroom_init.dump`**: Database initialization data
- **`docker/core/repositories.yml`**: Repository definitions for Docker
- **`docker/core/nemakiware.properties`**: Application properties for Docker

### Build Configuration
- **Root `pom.xml`**: Multi-module Maven configuration
- **`core/pom.xml`**: Core module with Jakarta EE dependencies
- **`lib/jakarta-converted/`**: Custom Jakarta EE JAR files

## Key Architecture Patterns

### Dependency Injection
- Spring Framework with XML configuration
- `@Autowired` annotations for field injection
- Interface-based service definitions

### Repository Pattern
- DAO interfaces with multiple implementations
- CouchDB as primary implementation
- Caching decorators for performance

### Servlet Architecture
- Jakarta EE 10 servlets (`jakarta.servlet.*`)
- CMIS bindings as specialized servlets
- JAX-RS for REST endpoints

### Frontend Architecture
- React SPA with TypeScript
- Vite for build tooling
- Ant Design for UI components
- Service layer for API communication

## Database Schema (CouchDB)

### Document Types
- **Content Documents**: CMIS documents and folders
- **Type Definitions**: CMIS object type definitions
- **User/Group Documents**: Authentication and authorization
- **Change Documents**: CMIS change log entries

### Repository Structure
- **bedroom**: Primary document repository
- **bedroom_closet**: Archive repository
- **canopy**: Multi-repository management
- **canopy_closet**: Archive for canopy
- **nemaki_conf**: System configuration

### Design Documents
- **_repo**: Main design document with views for content queries
- **_user**: Views for user and group management
- **_change**: Views for change log functionality