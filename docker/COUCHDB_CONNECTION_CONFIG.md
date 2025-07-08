# CouchDB Connection Configuration Guide

This guide explains how NemakiWare handles CouchDB connections in different environments (Maven tests vs Docker).

## Overview

NemakiWare now supports automatic environment detection and connection retry logic to handle both local development (Maven tests) and Docker deployment scenarios seamlessly.

## Configuration Files

### 1. Maven Test Environment
- **File**: `core/src/test/resources/nemakiware.properties`
- **CouchDB URL**: `http://localhost:5984`
- **Usage**: Automatically used when running Maven tests

### 2. Docker Environment
- **File**: `docker/core/nemakiware.properties`
- **CouchDB URL**: `http://couchdb:5984`
- **Usage**: Used when running in Docker containers

### 3. Docker Override (Optional)
- **File**: `core/src/main/resources/nemakiware-docker.properties`
- **Purpose**: Provides Docker-specific overrides for any property

## Environment Detection

The `CloudantClientPool` class now includes intelligent environment detection:

1. **System Property Override**: `-Ddb.couchdb.url.override=http://custom:5984`
2. **Environment Variable**: `COUCHDB_URL=http://custom:5984`
3. **Docker Detection**: Automatically replaces `localhost` with `couchdb` when running in Docker
4. **Default Configuration**: Uses the configured URL from properties file

## Connection Retry Logic

The connection pool now implements robust retry logic:

- **Max Retries**: 3 attempts
- **Retry Delay**: 2 seconds between attempts
- **Connection Tests**: 
  - Socket connection test (fast)
  - HTTP connection test (fallback)
- **Graceful Failure**: Detailed logging of connection attempts

## Usage Examples

### Maven Tests
```bash
# CouchDB should be running on localhost:5984
mvn test
```

### Docker Compose
```bash
# Uses service name 'couchdb' automatically
docker-compose up
```

### Custom Configuration
```bash
# Override via system property
mvn test -Ddb.couchdb.url.override=http://192.168.1.100:5984

# Override via environment variable
export COUCHDB_URL=http://192.168.1.100:5984
mvn test
```

## Troubleshooting

### Connection Failures

Check the logs for detailed connection information:
```
INFO: Resolving CouchDB URL...
INFO: Testing connection to couchdb:5984
WARN: Failed to connect to CouchDB at: http://couchdb:5984 (attempt 1 of 3)
INFO: Retrying in 2 seconds...
```

### Common Issues

1. **Maven tests fail to connect**
   - Ensure CouchDB is running on localhost:5984
   - Check authentication credentials match

2. **Docker containers can't connect**
   - Verify service name in docker-compose.yml is 'couchdb'
   - Check network configuration

3. **Custom environment**
   - Use system property or environment variable to override URL

## Best Practices

1. **Development**: Keep CouchDB running locally on default port
2. **CI/CD**: Use environment variables for configuration
3. **Production**: Use explicit configuration via system properties
4. **Docker**: Rely on automatic detection and service discovery

## Implementation Details

The solution includes:

1. **CloudantClientPool enhancements**:
   - `resolveUrl()`: Intelligent URL resolution
   - `isRunningInDocker()`: Docker environment detection
   - Retry logic with exponential backoff
   - Dual connection testing (socket + HTTP)

2. **Property Loading Order**:
   - Default properties
   - Environment-specific overrides
   - System property overrides (highest priority)

3. **Logging**:
   - Detailed connection attempt logging
   - Environment detection results
   - Retry attempt tracking

This configuration approach ensures that NemakiWare works seamlessly across different deployment scenarios without manual configuration changes.