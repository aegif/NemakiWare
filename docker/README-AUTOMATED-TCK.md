# Automated TCK Test Execution for NemakiWare

This document describes the automated Docker-based TCK test execution system that reproduces consistent test results across different environments.

## Overview

The automated TCK test system addresses the CMIS query permission filtering and data synchronization issues by providing a complete, reproducible test environment that:

- Automatically creates necessary CouchDB design documents
- Configures repositories.yml with proper CMIS credentials
- Triggers Solr re-indexing for data synchronization
- Executes comprehensive TCK tests
- Reports detailed test results

## Quick Start

### Prerequisites

- Docker and Docker Compose installed
- At least 4GB RAM available for containers
- Ports 5984, 8080, 8983, 9000 available

### Basic Usage

```bash
# Navigate to docker directory
cd docker/

# Run automated TCK tests (recommended)
./automated-tck-test.sh

# View results
ls -la tck-reports/
```

### Expected Results

When the automation works correctly, you should see:

- **Total Tests**: ~214
- **Passed Tests**: ~70 (32.71% pass rate)
- **Execution Time**: ~10-15 minutes
- **Key Improvement**: CMIS queries return actual data instead of 0 results

## Advanced Usage

### Custom Configuration

```bash
# Use custom CouchDB credentials
COUCHDB_USER=myuser COUCHDB_PASSWORD=mypass ./automated-tck-test.sh

# Skip environment cleanup (use existing containers)
./automated-tck-test.sh --no-cleanup

# Show help and all options
./automated-tck-test.sh --help
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `COUCHDB_USER` | `admin` | CouchDB username |
| `COUCHDB_PASSWORD` | `password` | CouchDB password |
| `REPOSITORY_ID` | `bedroom` | Repository to initialize |

## What the Automation Does

### 1. Design Document Creation

Automatically creates `_design/_repo` documents in CouchDB databases with essential views:

- **configuration**: For repository configuration documents
- **content**: For CMIS content objects
- **changes**: For change log tracking (critical for Solr indexing)
- **admin**: For administrative users
- **user/group/type/acl**: For various CMIS object types

### 2. Repository Configuration

Dynamically generates `repositories.yml` with proper YAML format:

```yaml
!jp.aegif.nemaki.util.yaml.RepositorySettings
settings: 
   canopy: !jp.aegif.nemaki.util.yaml.RepositorySetting
      password: [from environment]
      user: [from environment]
   bedroom: !jp.aegif.nemaki.util.yaml.RepositorySetting
      password: [from environment]
      user: [from environment]
```

### 3. Data Synchronization

- Triggers full Solr re-indexing using REST API
- Verifies Solr index contains documents from CouchDB
- Ensures CMIS change log tracking is functional

### 4. Test Execution

- Compiles and packages TCK test classes
- Executes tests inside Docker containers
- Generates comprehensive HTML and text reports
- Calculates pass rates and compliance scores

## Troubleshooting

### Common Issues

**Services not starting:**
```bash
# Check container status
docker-compose -f docker-compose-simple.yml ps

# View container logs
docker-compose -f docker-compose-simple.yml logs core
```

**Design documents not created:**
```bash
# Manually verify design documents
curl -u admin:password http://localhost:5984/bedroom/_design/_repo

# Check initializer logs
docker-compose -f docker-compose-simple.yml logs initializer
```

**TCK tests failing:**
```bash
# Check core service health
curl -f http://localhost:8080/core

# Verify Solr index
curl "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0"
```

### Manual Verification Steps

1. **Verify CouchDB databases exist:**
   ```bash
   curl -u admin:password http://localhost:5984/_all_dbs
   ```

2. **Check design documents:**
   ```bash
   curl -u admin:password http://localhost:5984/bedroom/_design/_repo
   ```

3. **Verify Solr indexing:**
   ```bash
   curl "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0"
   ```

4. **Test CMIS endpoint:**
   ```bash
   curl http://localhost:8080/core/atom/bedroom
   ```

## Technical Details

### Architecture

The automation system consists of:

- **initializer**: Creates databases and design documents
- **setup-design-documents.sh**: Enhanced design document creation
- **automated-tck-test.sh**: Main orchestration script
- **execute-tck-tests.sh**: TCK test execution
- **docker-compose-simple.yml**: Container orchestration

### Key Fixes Implemented

1. **Permission Filtering Fix**: Modified `PermissionServiceImpl.java` to handle null ACLs gracefully
2. **Data Synchronization Fix**: Created missing CouchDB design documents for change log tracking
3. **Configuration Fix**: Proper repositories.yml format with CMIS credentials
4. **Automation**: Complete Docker-based reproducible test environment

### File Structure

```
docker/
├── automated-tck-test.sh          # Main automation script
├── setup-design-documents.sh      # Design document creation
├── execute-tck-tests.sh           # TCK test execution
├── docker-compose-simple.yml      # Container definitions
├── initializer/
│   ├── entrypoint.sh              # Database initialization
│   ├── setup-design-documents.sh  # Design document setup
│   └── Dockerfile                 # Initializer container
├── core/
│   ├── repositories.yml           # CMIS repository configuration
│   └── Dockerfile.simple          # Core service container
└── tck-reports/                   # Generated test reports
    ├── tck-summary.html           # HTML test report
    ├── tck-report.txt             # Detailed text report
    └── current-score.txt          # Current compliance score
```

## Contributing

When modifying the automation system:

1. Test changes with `--clean-start` to ensure reproducibility
2. Verify that design documents are created correctly
3. Confirm TCK results match expected pass rates
4. Update documentation for any new configuration options

## Support

For issues with the automated TCK system:

1. Check the troubleshooting section above
2. Review container logs for specific error messages
3. Verify that all prerequisites are met
4. Test with default configuration before using custom settings

The automation system is designed to provide consistent, reproducible TCK test results that demonstrate the resolution of CMIS query permission filtering and data synchronization issues.
