#!/bin/bash

# NemakiWare Minimal TCK Test Execution
# Focuses on core functionality without data-dependent tests
# Addresses the issue of too many invalid query tests

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=========================================="
echo "NemakiWare Minimal TCK Test Execution"
echo "Goal: Test core functionality only"
echo "=========================================="

# Check if containers are running
CORE_RUNNING=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" || echo "")
COUCHDB_RUNNING=$(docker ps --format "{{.Names}}" | grep -E "(couchdb|couchdb-1)" || echo "")

if [ -z "$CORE_RUNNING" ]; then
    echo "ERROR: NemakiWare core container is not running"
    echo "Available containers:"
    docker ps --format "table {{.Names}}\\t{{.Status}}"
    echo "Please start the Docker environment first"
    exit 1
fi

if [ -z "$COUCHDB_RUNNING" ]; then
    echo "ERROR: CouchDB container is not running" 
    echo "Please start the Docker environment first"
    exit 1
fi

# Get the actual container name for core
CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
echo "✓ Found core container: $CORE_CONTAINER"
echo "✓ Docker containers are running"

# Test basic connectivity
echo "Testing connectivity to NemakiWare core service..."
if ! docker exec $CORE_CONTAINER curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service"
    exit 1
fi
echo "✓ Core service is accessible"

# Test CMIS endpoints
echo "Testing CMIS endpoints..."
CMIS_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom || echo "error")
if [ "$CMIS_STATUS" != "200" ]; then
    echo "ERROR: CMIS AtomPub endpoint not accessible (HTTP $CMIS_STATUS)"
    exit 1
fi
echo "✓ CMIS AtomPub endpoint accessible"

# Test basic query functionality
echo "Testing basic query functionality..."
QUERY_STATUS=$(curl -s -u admin:admin -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "q=SELECT * FROM cmis:folder WHERE cmis:name = 'Repository Root'" \
    -o /dev/null -w "%{http_code}" \
    http://localhost:8080/core/atom/bedroom/query || echo "error")

if [ "$QUERY_STATUS" != "200" ]; then
    echo "ERROR: Basic CMIS query failed (HTTP $QUERY_STATUS)"
    exit 1
fi
echo "✓ Basic query functionality working"

# Set Java 17 environment for TCK tests
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java version: $(java -version 2>&1 | head -1)"

echo "Verifying TCK configuration files exist..."
if [ ! -f "$NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-docker.properties" ]; then
    echo "ERROR: cmis-tck-parameters-docker.properties not found"
    exit 1
fi
if [ ! -f "$NEMAKI_HOME/core/src/test/resources/cmis-tck-filters-minimal.properties" ]; then
    echo "ERROR: cmis-tck-filters-minimal.properties not found"
    exit 1
fi
echo "✓ TCK configuration files verified"

echo "Creating necessary directories..."
mkdir -p "$NEMAKI_HOME/core/target/test-classes"
mkdir -p "$NEMAKI_HOME/core/target/test-lib"
mkdir -p "$SCRIPT_DIR/tck-reports"
echo "✓ Necessary directories created"

echo "Building minimal TCK test package..."
cd $NEMAKI_HOME

echo "Compiling TCK test classes..."
mvn test-compile -f core/pom.xml -Pdevelopment -q

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile TCK test classes"
    exit 1
fi

echo "Creating minimal TCK test JAR for container execution..."
mvn dependency:copy-dependencies -DoutputDirectory=core/target/test-lib -DincludeScope=test -f core/pom.xml -q

# Verify test-classes directory was populated
if [ ! -d "core/target/test-classes" ] || [ -z "$(ls -A core/target/test-classes 2>/dev/null)" ]; then
    echo "ERROR: test-classes directory is empty or missing"
    exit 1
fi

cd core/target
if [ -d "test-classes" ]; then
    jar cf minimal-tck-tests.jar -C test-classes .
    echo "✓ Minimal TCK test JAR created successfully"
else
    echo "ERROR: test-classes directory not found after compilation"
    exit 1
fi
cd $NEMAKI_HOME

echo "Copying minimal TCK test files to container..."
docker cp core/target/minimal-tck-tests.jar $CORE_CONTAINER:/tmp/
docker cp core/target/test-lib $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-parameters-docker.properties $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-filters-minimal.properties $CORE_CONTAINER:/tmp/

echo "Executing minimal TCK tests inside container..."
echo "Note: Only core functionality tests will be executed"
echo "Data-dependent and potentially invalid tests are excluded"
echo ""

docker exec $CORE_CONTAINER java -cp "/tmp/minimal-tck-tests.jar:/tmp/test-lib/*:/usr/local/tomcat/webapps/core/WEB-INF/lib/*" \
    jp.aegif.nemaki.cmis.tck.MinimalTckRunner

TCK_EXIT_CODE=$?

echo "Copying TCK reports from container..."
docker exec $CORE_CONTAINER test -d /usr/local/docker/tck-reports && \
    docker cp $CORE_CONTAINER:/usr/local/docker/tck-reports/. $SCRIPT_DIR/tck-reports/ || \
    echo "No reports found in container"

echo ""
echo "=========================================="
echo "Minimal TCK Test Execution Summary"
echo "=========================================="

if [ $TCK_EXIT_CODE -eq 0 ]; then
    echo "✓ All minimal TCK tests PASSED"
    echo "✓ Core CMIS functionality verified"
    echo "✓ No invalid query tests were executed"
else
    echo "⚠ Some tests failed (exit code: $TCK_EXIT_CODE)"
    echo "⚠ Check reports for detailed failure information"
fi

echo ""
echo "Reports available in: $SCRIPT_DIR/tck-reports/"
ls -la $SCRIPT_DIR/tck-reports/ 2>/dev/null || echo "No reports generated"

echo ""
echo "Next steps:"
echo "1. Review any test failures in the reports"
echo "2. Consider creating proper test data for expanded testing"
echo "3. Gradually enable more test groups as data becomes available"

exit $TCK_EXIT_CODE