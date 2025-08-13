# NemakiWare Project Overview

## Purpose
NemakiWare is an open source Enterprise Content Management system that implements a CMIS 1.1 compliant repository server. It provides document management capabilities including versioning, search, permissions, and metadata management.

## Current Status (2025-08-01)
- **✅ Jakarta EE 10 Complete Migration**: 100% javax.* namespace elimination achieved
- **✅ CMIS 1.1 Full Compliance**: All CMIS bindings (AtomPub, Browser, Web Services) functional
- **✅ QA Test Success**: 46/46 tests passing (100% success rate)
- **✅ Production Ready**: All systems verified and ready for deployment

## Technology Stack

### Backend
- **Framework**: Spring Framework 6.1.13
- **CMIS Implementation**: Apache Chemistry OpenCMIS 1.1.0 (Jakarta-converted)
- **Application Server**: Tomcat 10.1+ (Jakarta EE 10) or Jetty 11+
- **Java**: Java 17 (mandatory)
- **Build Tool**: Maven 3.x

### Database & Storage
- **Document Storage**: CouchDB 3.x (with authentication)
- **Search Engine**: Apache Solr 9.x with ExtractingRequestHandler (Tika 2.9.2)

### Frontend
- **UI**: React 18 SPA with TypeScript and Ant Design
- **Build System**: Vite
- **Integration**: Served as static resources from core webapp
- **Authentication**: OIDC/SAML support

### Infrastructure
- **Containerization**: Docker Compose with 3-container setup (core, solr, couchdb)
- **Development**: Jetty Maven plugin for local development

## Active Modules
- **common/**: Shared utilities and models
- **core/**: Main CMIS repository server (Spring-based WAR) with integrated React UI
- **solr/**: Search engine customization
- **cloudant-init/**: Database initialization tool

## Suspended Modules (Out of Maintenance Scope)
Located in `/suspended-modules/`:
- **aws/**: S3 integration tools (suspended since 2025-07-26)
- **action/**: Plugin framework for custom actions (suspended since 2025-07-26)
- **action-sample/**: Sample action implementations (suspended since 2025-07-26)

## Repository Structure
- **Document Repositories**: `bedroom` (primary), `bedroom_closet` (archive)
- **System Repositories**: `canopy`, `canopy_closet`, `nemaki_conf`
- **Default Credentials**: CMIS (admin:admin), CouchDB (admin:password)

## Key Features
- CMIS 1.1 compliant document repository
- Multi-format document preview (PDF, Office, images, video)
- Full-text search with Apache Solr
- User and group management
- Granular permission system
- Document versioning and checkout/checkin
- Archive operations