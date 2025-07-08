#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=========================================="
echo "NemakiWare TCK Test Execution"
echo "=========================================="

# Check if containers are running with more flexible pattern matching
CORE_RUNNING=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" || echo "")
COUCHDB_RUNNING=$(docker ps --format "{{.Names}}" | grep -E "(couchdb|couchdb-1)" || echo "")

if [ -z "$CORE_RUNNING" ]; then
    echo "ERROR: NemakiWare core container is not running"
    echo "Available containers:"
    docker ps --format "table {{.Names}}\t{{.Status}}"
    echo "Please run './test-all.sh --auto-fix' first to start the Docker environment"
    exit 1
fi

if [ -z "$COUCHDB_RUNNING" ]; then
    echo "ERROR: CouchDB container is not running" 
    echo "Available containers:"
    docker ps --format "table {{.Names}}\t{{.Status}}"
    echo "Please run './test-all.sh --auto-fix' first to start the Docker environment"
    exit 1
fi

# Get the actual container name for core
CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
echo "✓ Found core container: $CORE_CONTAINER"

echo "✓ Docker containers are running"

echo "Testing connectivity to NemakiWare core service..."
if ! docker exec $CORE_CONTAINER curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service"
    echo "Please ensure the Docker environment is fully started and healthy"
    exit 1
fi
echo "✓ Core service is accessible"

# Set Java 17 environment for TCK tests
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java version: $(java -version 2>&1 | head -1)"

echo "Verifying TCK configuration files exist..."
if [ ! -f "$NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-docker.properties" ]; then
    echo "ERROR: cmis-tck-parameters-docker.properties not found"
    exit 1
fi
if [ ! -f "$NEMAKI_HOME/core/src/test/resources/cmis-tck-filters-docker.properties" ]; then
    echo "ERROR: cmis-tck-filters-docker.properties not found"
    exit 1
fi
echo "✓ TCK configuration files verified"

echo "Creating necessary directories..."
# Create target/test-classes if it doesn't exist (not tracked in Git due to target/ gitignore)
mkdir -p "$NEMAKI_HOME/core/target/test-classes"
mkdir -p "$NEMAKI_HOME/core/target/test-lib"
# Create TCK reports directory (not tracked in Git due to tck-reports/ gitignore)
mkdir -p "$SCRIPT_DIR/tck-reports"
echo "✓ Necessary directories created"

echo "Using existing DockerTckRunner.java (no overwrite needed)"

echo "Building TCK test package..."
cd $NEMAKI_HOME

echo "Compiling TCK test classes..."
mvn test-compile -f core/pom.xml -Pjakarta

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile TCK test classes"
    exit 1
fi

echo "Creating TCK test JAR for container execution..."
# Package test classes and dependencies
echo "Copying test dependencies..."
mvn dependency:copy-dependencies -DoutputDirectory=core/target/test-lib -DincludeScope=test -f core/pom.xml -Pjakarta

# Verify test-classes directory was populated by compilation
if [ ! -d "core/target/test-classes" ] || [ -z "$(ls -A core/target/test-classes 2>/dev/null)" ]; then
    echo "ERROR: test-classes directory is empty or missing. Running test-compile again..."
    mvn test-compile -f core/pom.xml -Pdevelopment
fi

cd core/target
if [ -d "test-classes" ]; then
    jar cf tck-tests.jar -C test-classes .
    echo "✓ TCK test JAR created successfully"
else
    echo "ERROR: test-classes directory still not found after compilation"
    exit 1
fi
cd $NEMAKI_HOME

echo "Copying TCK test files to container..."
docker cp core/target/tck-tests.jar $CORE_CONTAINER:/tmp/
docker cp core/target/test-lib $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-parameters-docker.properties $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-filters-docker.properties $CORE_CONTAINER:/tmp/

echo "Executing detailed query validation first..."
docker exec $CORE_CONTAINER java -cp "/tmp/tck-tests.jar:/tmp/test-lib/*:/usr/local/tomcat/webapps/core/WEB-INF/lib/*" \
    jp.aegif.nemaki.cmis.tck.QueryValidationRunner

echo "Executing TCK tests inside container..."
docker exec $CORE_CONTAINER java -cp "/tmp/tck-tests.jar:/tmp/test-lib/*:/usr/local/tomcat/webapps/core/WEB-INF/lib/*" \
    jp.aegif.nemaki.cmis.tck.DockerTckRunner

echo "Copying TCK reports from container..."
# Reports are generated in /usr/local/docker/tck-reports inside container
docker exec $CORE_CONTAINER test -d /usr/local/docker/tck-reports && \
    docker cp $CORE_CONTAINER:/usr/local/docker/tck-reports/. $SCRIPT_DIR/tck-reports/ || \
    echo "No reports found in container"

echo "TCK test execution completed!"
echo "Reports available in: $SCRIPT_DIR/tck-reports/"
ls -la $SCRIPT_DIR/tck-reports/ 2>/dev/null || echo "No reports generated"
