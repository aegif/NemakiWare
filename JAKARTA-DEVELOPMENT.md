# Jakarta EE 10 Development Environment Guide

This guide provides detailed instructions for setting up and using the Jakarta EE 10 development environment for NemakiWare.

## Prerequisites

- **Java 17** (Required)
- **Maven 3.6+**
- **Docker** (for CouchDB only)
- **Git** (for version control)

## Environment Setup

### 1. Java 17 Configuration

Ensure Java 17 is installed and set as your default Java version:

```bash
# Check Java version
java -version
# Expected output: openjdk version "17.0.x" or higher

# Set JAVA_HOME if needed (example paths)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk  # Linux
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.x.x/libexec/openjdk.jdk/Contents/Home  # macOS Homebrew
export PATH=$JAVA_HOME/bin:$PATH

# Verify Maven uses Java 17
mvn -version
```

### 2. CouchDB Docker Container

Start a CouchDB 3.x container with authentication:

```bash
# Start CouchDB container
docker run -d --name couchdb-dev \
  -p 5984:5984 \
  -e COUCHDB_USER=admin \
  -e COUCHDB_PASSWORD=password \
  -v couchdb-dev-data:/opt/couchdb/data \
  couchdb:3

# Verify CouchDB is running
curl -u admin:password http://localhost:5984/
# Expected: {"couchdb":"Welcome","version":"3.x.x",...}
```

### 3. Java 17 Module System Configuration

Configure Maven to open required Java modules for Jakarta EE compatibility:

```bash
# Set MAVEN_OPTS for Java 17 module system
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

# Optional: Add to your shell profile for persistence
echo 'export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"' >> ~/.bashrc
```

## Development Server Startup

### Standard Development Mode

```bash
# Navigate to core module
cd core

# Easy startup with automated configuration
./start-jetty-dev.sh

# Server will start on http://localhost:8081
```

### Manual Development Mode

```bash
# Navigate to core module
cd core

# Set Java 17 module system compatibility
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

# Start Jetty development server
mvn jetty:run -Pjakarta -Djetty.port=8081

# Server will start on http://localhost:8081
```

### Custom Configuration

```bash
# Start with custom properties file
mvn jetty:run -Pjakarta \
  -Djetty.port=8081 \
  -Dnemakiware.properties=file:src/main/webapp/WEB-INF/classes/nemakiware-jetty-nosolr.properties

# Start with different CouchDB URL
mvn jetty:run -Pjakarta \
  -Djetty.port=8081 \
  -Ddb.couchdb.url=http://localhost:5984
```

## Verification and Testing

### 1. Basic Connectivity

```bash
# Test CMIS Service Document
curl -u admin:admin http://localhost:8081/core/atom/bedroom

# Test Repository Info (should return XML)
curl -u admin:admin http://localhost:8081/core/atom/bedroom | head -10

# Test Folder Operations
curl -u admin:admin "http://localhost:8081/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff"
```

### 2. Expected Responses

- **CMIS Service**: Returns XML with repository capabilities
- **Authentication**: Uses admin:admin credentials
- **HTTP Status**: 200 for successful operations
- **Content Type**: application/atom+xml for CMIS responses

### 3. Development Workflow

```bash
# 1. Make code changes in src/main/java/
# 2. Jetty automatically reloads classes (scan=1)
# 3. Test changes immediately without restart
# 4. For configuration changes, restart Jetty

# Stop Jetty
Ctrl+C

# Restart with changes
mvn jetty:run -Pjakarta -Djetty.port=8081
```

## Key Features

### Jakarta EE 10 Compatibility

- **Servlet API**: `jakarta.servlet` namespace
- **Authentication**: Custom Jakarta-compatible handler
- **OpenCMIS**: Jakarta-converted libraries
- **Spring**: Version 6 with Jakarta EE support

### Development Optimizations

- **Solr Disabled**: Uses MockSolrUtil for simplified development
- **Fast Reload**: Automatic class reloading with scan=1
- **Single Docker Dependency**: Only CouchDB required
- **Debug-Friendly**: Direct Maven execution

### Repository Configuration

The development environment uses two repositories:
- **bedroom**: Main document repository
- **canopy**: System management repository

Both use the same authentication (admin:admin) and are automatically initialized.

## Troubleshooting

### Common Issues

1. **Port Already in Use**
   ```bash
   # Check what's using port 8081
   lsof -i :8081
   
   # Use different port
   mvn jetty:run -Pjakarta -Djetty.port=8082
   ```

2. **Java Module Errors**
   ```bash
   # Ensure MAVEN_OPTS is set
   echo $MAVEN_OPTS
   
   # If empty, set it again
   export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED ..."
   ```

3. **CouchDB Connection Errors**
   ```bash
   # Check CouchDB container
   docker ps | grep couchdb-dev
   
   # Check CouchDB logs
   docker logs couchdb-dev
   
   # Test connection
   curl -u admin:password http://localhost:5984/
   ```

4. **Authentication Failures**
   ```bash
   # Verify credentials
   curl -u admin:admin http://localhost:8081/core/atom/bedroom
   
   # Check for 401 Unauthorized vs 500 Internal Server Error
   curl -v -u admin:admin http://localhost:8081/core/atom/bedroom
   ```

### Development Tips

- **Code Changes**: Most Java changes are automatically reloaded
- **Configuration Changes**: Require Jetty restart
- **Database Changes**: Use CouchDB admin interface at http://localhost:5984/_utils
- **Logging**: Check console output for detailed debug information

## Configuration Files

### Key Development Files

- `core/src/main/webapp/WEB-INF/classes/nemakiware-jetty-nosolr.properties`: Main config
- `core/src/main/webapp/WEB-INF/classes/log4j-jetty.properties`: Logging config
- `core/src/main/webapp/WEB-INF/classes/repositories.yml`: Repository definitions
- `core/pom.xml`: Maven configuration with Jetty plugin

### Important Settings

- **Solr**: Disabled via MockSolrUtil
- **CouchDB URL**: http://localhost:5984
- **Authentication**: admin:admin
- **Port**: 8081 (configurable)
- **Context Path**: /core

## Next Steps

After setting up the development environment:

1. **Explore CMIS Operations**: Test various CMIS endpoints
2. **Debug Code**: Set breakpoints in your IDE
3. **Modify Features**: Make changes and test immediately
4. **Run Tests**: Execute unit and integration tests
5. **Deploy**: Build WAR file for production deployment

For production deployment, see the main Docker and WAR deployment documentation.