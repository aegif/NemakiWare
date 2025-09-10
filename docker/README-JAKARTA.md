# Jakarta EE Deployment Guide for NemakiWare

This guide explains how to deploy NemakiWare on Jakarta EE compatible containers (Tomcat 10+, Jetty 11+) using the custom-built Jakarta-converted OpenCMIS libraries.

## Overview

NemakiWare traditionally uses Java EE (javax.*) APIs through Apache Chemistry OpenCMIS. To deploy on modern Jakarta EE containers, all javax.* references must be converted to jakarta.* namespace. This is achieved through pre-converted JAR files stored in `/lib/jakarta-converted/`.

## Pre-converted Jakarta JARs

The following Jakarta-converted JARs are available:

### Stable Release (1.1.0)
- `chemistry-opencmis-client-bindings-1.1.0-jakarta.jar`
- `chemistry-opencmis-commons-api-1.1.0-jakarta.jar`
- `chemistry-opencmis-commons-impl-1.1.0-jakarta.jar`
- `chemistry-opencmis-server-bindings-1.1.0-jakarta.jar`
- `chemistry-opencmis-server-support-1.1.0-jakarta.jar`
- `chemistry-opencmis-test-tck-1.1.0-jakarta.jar`

### Custom Build (1.2.0-SNAPSHOT)
- All modules from 1.1.0 plus:
- `chemistry-opencmis-client-api-1.2.0-SNAPSHOT-jakarta.jar`
- `chemistry-opencmis-client-impl-1.2.0-SNAPSHOT-jakarta.jar`

### JAX-WS Runtime
- `jaxws-rt-4.0.2-jakarta.jar`

## Quick Start - Docker Deployment

### Using docker-compose with Tomcat 10

1. **Prepare the Jakarta-enabled WAR**:
   ```bash
   # Build the core WAR
   mvn clean package -f core/pom.xml
   
   # Copy WAR to Docker build context
   cp core/target/core.war docker/core/
   
   # Prepare Jakarta JARs for Docker
   docker/integrate-jakarta-jars.sh docker-core
   ```

2. **Start the Jakarta environment**:
   ```bash
   cd docker
   docker-compose -f docker-compose-tomcat10.yml up -d
   ```

3. **Verify deployment**:
   ```bash
   # Check if core is running
   curl -u admin:admin http://localhost:8080/core/atom/bedroom
   ```

## Manual Jakarta Conversion

### For existing WAR files

Use the integration script to convert an existing WAR:

```bash
# Convert a WAR file to use Jakarta JARs
docker/integrate-jakarta-jars.sh war path/to/core.war

# Or use SNAPSHOT versions
USE_SNAPSHOT=true docker/integrate-jakarta-jars.sh war path/to/core.war
```

### For development builds

Replace JARs after building:

```bash
# Build normally
mvn clean package -f core/pom.xml

# Replace with Jakarta versions
docker/use-jakarta-jars.sh

# Or use SNAPSHOT versions
USE_SNAPSHOT=true docker/use-jakarta-jars.sh
```

## Docker Build Process

### Building Jakarta-compatible Docker image

1. **Ensure Jakarta JARs are prepared**:
   ```bash
   docker/integrate-jakarta-jars.sh docker-core
   ```

2. **Build the Docker image**:
   ```bash
   docker build -f docker/core/Dockerfile.jakarta -t nemakiware-tomcat10 docker/core/
   ```

3. **Run with docker-compose**:
   ```bash
   docker-compose -f docker-compose-tomcat10.yml up
   ```

## Integration Scripts

### `integrate-jakarta-jars.sh`

Main integration script with multiple commands:

- `copy <dir>` - Copy Jakarta JARs to a directory
- `war <file>` - Replace JARs inside a WAR file
- `docker-core` - Prepare for Docker core build

### `use-jakarta-jars.sh`

Simple script to replace JARs in an extracted WAR directory.

## Environment Variables

- `USE_SNAPSHOT` - Use 1.2.0-SNAPSHOT versions (default: false)
- `JAKARTA_LIB_DIR` - Directory containing Jakarta JARs
- `NEMAKI_HOME` - NemakiWare home directory

## Troubleshooting

### Common Issues

1. **ClassNotFoundException for javax.* classes**
   - Ensure all OpenCMIS JARs are replaced with Jakarta versions
   - Check that no javax.* JARs remain in WEB-INF/lib

2. **JAXB/JAX-WS issues**
   - Verify jaxws-rt-4.0.2-jakarta.jar is included
   - Add Jakarta-specific system properties in CATALINA_OPTS

3. **Docker build cache issues**
   - Use `--no-cache` flag when building
   - Run `docker/integrate-jakarta-jars.sh docker-core` before each build

### Verification

Check that Jakarta JARs are properly included:

```bash
# List JARs in running container
docker exec nemaki-core-tomcat10 ls -la /usr/local/tomcat/webapps/core/WEB-INF/lib/chemistry-opencmis-*.jar

# Check for javax references (should be empty)
docker exec nemaki-core-tomcat10 grep -r "javax.xml.ws" /usr/local/tomcat/webapps/core/WEB-INF/lib/
```

## Best Practices

1. **Version Control**: Jakarta JARs are tracked in Git to ensure consistency
2. **Build Process**: Always use integration scripts to ensure proper JAR replacement
3. **Testing**: Test Jakarta deployment separately from standard deployment
4. **Documentation**: Keep track of which version (1.1.0 or 1.2.0-SNAPSHOT) is deployed

## Notes

- The 1.2.0-SNAPSHOT versions are custom-built from Apache Chemistry source
- Jakarta conversion is done using Eclipse Transformer 0.5.0
- Original javax.* JARs are preserved for backward compatibility
- This setup allows deployment on both traditional (Tomcat 9) and modern (Tomcat 10+) containers