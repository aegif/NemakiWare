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

mkdir -p $SCRIPT_DIR/tck-reports

echo "Testing connectivity to NemakiWare core service..."
if ! docker exec $CORE_CONTAINER curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service"
    echo "Please ensure the Docker environment is fully started and healthy"
    exit 1
fi
echo "✓ Core service is accessible"

echo "Preparing TCK configuration for Docker environment..."
cp $SCRIPT_DIR/cmis-tck-parameters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-docker.properties
cp $SCRIPT_DIR/cmis-tck-filters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-filters-docker.properties

echo "Using existing DockerTckRunner.java (no overwrite needed)"

echo "Building TCK test package..."
cd $NEMAKI_HOME

echo "Compiling TCK test classes..."
mvn test-compile -f core/pom.xml -Pdevelopment -q

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile TCK test classes"
    exit 1
fi

echo "Creating TCK test JAR for container execution..."
# Package test classes and dependencies
mvn dependency:copy-dependencies -DoutputDirectory=core/target/test-lib -DincludeScope=test -f core/pom.xml -q
cd core/target
jar cf tck-tests.jar -C test-classes .
cd $NEMAKI_HOME

echo "Copying TCK test files to container..."
docker cp core/target/tck-tests.jar $CORE_CONTAINER:/tmp/
docker cp core/target/test-lib $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-parameters-docker.properties $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-filters-docker.properties $CORE_CONTAINER:/tmp/

echo "Executing TCK tests inside container..."
docker exec $CORE_CONTAINER java -cp "/tmp/tck-tests.jar:/tmp/test-lib/*:/usr/local/tomcat/webapps/core/WEB-INF/lib/*" \
    jp.aegif.nemaki.cmis.tck.DockerTckRunner

echo "Copying TCK reports from container..."
mkdir -p $SCRIPT_DIR/tck-reports
# Reports are generated in /usr/local/docker/tck-reports inside container
docker exec $CORE_CONTAINER test -d /usr/local/docker/tck-reports && \
    docker cp $CORE_CONTAINER:/usr/local/docker/tck-reports/. $SCRIPT_DIR/tck-reports/ || \
    echo "No reports found in container"

echo "TCK test execution completed!"
echo "Reports available in: $SCRIPT_DIR/tck-reports/"
ls -la $SCRIPT_DIR/tck-reports/ 2>/dev/null || echo "No reports generated"
