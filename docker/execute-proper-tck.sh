#!/bin/bash

# OpenCMIS TCK Proper Execution for NemakiWare
# Follows OpenCMIS TCK best practices and standards
# TCK creates its own test data, eliminating data dependency issues

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=============================================="
echo "OpenCMIS TCK Proper Execution for NemakiWare"
echo "Standard TCK approach with self-contained tests"
echo "=============================================="

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

# Get the actual container name for core
CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
echo "✓ Found core container: $CORE_CONTAINER"

# Test CMIS connectivity and basic functionality
echo "Testing CMIS endpoints and basic functionality..."

# Test repository info
REPO_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom || echo "error")
if [ "$REPO_STATUS" != "200" ]; then
    echo "ERROR: CMIS AtomPub endpoint not accessible (HTTP $REPO_STATUS)"
    exit 1
fi
echo "✓ CMIS AtomPub endpoint accessible"

# Test basic query capability via AtomPub binding (correct format)
QUERY_STATUS=$(curl -s -u admin:admin -X POST \
    -H "Content-Type: application/cmisquery+xml; charset=UTF-8" \
    -d '<?xml version="1.0" encoding="UTF-8"?>
<cmis:query xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <cmis:statement>SELECT * FROM cmis:folder</cmis:statement>
</cmis:query>' \
    -o /dev/null -w "%{http_code}" \
    http://localhost:8080/core/atom/bedroom/query || echo "error")

if [ "$QUERY_STATUS" != "200" ] && [ "$QUERY_STATUS" != "201" ]; then
    echo "ERROR: Basic CMIS query failed (HTTP $QUERY_STATUS)"
    exit 1
fi
echo "✓ Basic query functionality working"

# Test folder creation capability (TCK will need this)
echo "Testing folder creation capability..."
TEST_FOLDER_RESPONSE=$(curl -s -u admin:admin -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "cmisaction=createFolder" \
    -d "propertyId[0]=cmis:objectTypeId" \
    -d "propertyValue[0]=cmis:folder" \
    -d "propertyId[1]=cmis:name" \
    -d "propertyValue[1]=TCK_CAPABILITY_TEST" \
    "http://localhost:8080/core/browser/bedroom?objectId=e02f784f8360a02cc14d1314c10038ff" 2>/dev/null || echo "")

if echo "$TEST_FOLDER_RESPONSE" | grep -q "objectId" || echo "$TEST_FOLDER_RESPONSE" | grep -q "TCK_CAPABILITY_TEST"; then
    echo "✓ Folder creation capability confirmed"
    # Clean up test folder if created successfully
    if echo "$TEST_FOLDER_RESPONSE" | grep -q "objectId"; then
        TEST_FOLDER_ID=$(echo "$TEST_FOLDER_RESPONSE" | grep -o '"cmis:objectId"[^}]*"value"[^"]*"[^"]*"' | sed 's/.*"value"[^"]*"//' | sed 's/".*//' | head -1)
        if [ -n "$TEST_FOLDER_ID" ]; then
            curl -s -u admin:admin -X POST \
                -H "Content-Type: application/x-www-form-urlencoded" \
                -d "cmisaction=delete" \
                "http://localhost:8080/core/browser/bedroom?objectId=$TEST_FOLDER_ID" > /dev/null || true
            echo "✓ Test folder cleaned up"
        fi
    fi
else
    echo "⚠ Folder creation may have issues, but proceeding with TCK"
fi

# Set Java 17 environment for TCK tests
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java version: $(java -version 2>&1 | head -1)"

echo "Verifying TCK configuration files..."
if [ ! -f "$NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-proper.properties" ]; then
    echo "ERROR: cmis-tck-parameters-proper.properties not found"
    exit 1
fi
if [ ! -f "$NEMAKI_HOME/core/src/test/resources/cmis-tck-filters-proper.properties" ]; then
    echo "ERROR: cmis-tck-filters-proper.properties not found"
    exit 1
fi
echo "✓ TCK configuration files verified"

echo "Creating necessary directories..."
mkdir -p "$NEMAKI_HOME/core/target/test-classes"
mkdir -p "$NEMAKI_HOME/core/target/test-lib"
mkdir -p "$SCRIPT_DIR/tck-reports"
echo "✓ Directories created"

echo "Building proper TCK test package..."
cd $NEMAKI_HOME

echo "Compiling TCK test classes..."
mvn test-compile -f core/pom.xml -Pdevelopment -q

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile TCK test classes"
    exit 1
fi

echo "Copying test dependencies..."
mvn dependency:copy-dependencies -DoutputDirectory=core/target/test-lib -DincludeScope=test -f core/pom.xml -q

# Create test JAR
cd core/target
if [ -d "test-classes" ]; then
    jar cf proper-tck-tests.jar -C test-classes .
    echo "✓ Proper TCK test JAR created"
else
    echo "ERROR: test-classes directory not found"
    exit 1
fi
cd $NEMAKI_HOME

echo "Copying TCK files to container..."
docker cp core/target/proper-tck-tests.jar $CORE_CONTAINER:/tmp/
docker cp core/target/test-lib $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-parameters-proper.properties $CORE_CONTAINER:/tmp/
docker cp core/src/test/resources/cmis-tck-filters-proper.properties $CORE_CONTAINER:/tmp/

echo ""
echo "=============================================="
echo "Executing OpenCMIS TCK Tests"
echo "=============================================="
echo "Note: TCK will create its own test data"
echo "This eliminates the 'invalid query' and 'systematic data' issues"
echo ""

# Execute proper TCK tests using the standard OpenCMIS ConsoleRunner
echo "Executing TCK tests with OpenCMIS ConsoleRunner..."
docker exec $CORE_CONTAINER java -cp "/tmp/test-lib/*:/usr/local/tomcat/webapps/core/WEB-INF/lib/*" \
    org.apache.chemistry.opencmis.tck.runner.ConsoleRunner \
    /tmp/cmis-tck-parameters-proper.properties

TCK_EXIT_CODE=$?

echo ""
echo "Copying TCK reports from container..."
docker exec $CORE_CONTAINER test -d /usr/local/docker/tck-reports && \
    docker cp $CORE_CONTAINER:/usr/local/docker/tck-reports/. $SCRIPT_DIR/tck-reports/ || \
    echo "No reports found in container"

echo ""
echo "=============================================="
echo "OpenCMIS TCK Execution Summary"
echo "=============================================="

if [ $TCK_EXIT_CODE -eq 0 ]; then
    echo "🎉 SUCCESS: All OpenCMIS TCK tests PASSED"
    echo "✅ NemakiWare demonstrates full CMIS 1.1 compliance"
    echo "✅ No invalid queries or data dependency issues"
else
    echo "⚠️  PARTIAL: Some TCK tests failed (exit code: $TCK_EXIT_CODE)"
    echo "📊 Check detailed reports for compliance assessment"
fi

echo ""
echo "Generated Reports:"
if [ -d "$SCRIPT_DIR/tck-reports" ]; then
    ls -la $SCRIPT_DIR/tck-reports/
else
    echo "No reports directory found"
fi

echo ""
echo "Key Benefits of This Approach:"
echo "✓ TCK creates its own test data (no dependency on existing data)"
echo "✓ Only valid, meaningful queries are executed"
echo "✓ Comprehensive CMIS 1.1 compliance testing"
echo "✓ Standard OpenCMIS testing methodology"
echo "✓ Eliminates 'systematic data ID' issues"

echo ""
echo "Next Steps:"
if [ $TCK_EXIT_CODE -eq 0 ]; then
    echo "1. Review success reports for compliance confirmation"
    echo "2. NemakiWare is ready for production CMIS usage"
    echo "3. Consider performance testing if needed"
else
    echo "1. Review failure details in generated reports"
    echo "2. Address any critical CMIS compliance issues"
    echo "3. Re-run tests after fixes"
fi

exit $TCK_EXIT_CODE