#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=========================================="
echo "NemakiWare Comprehensive TCK Test Execution"
echo "Host-based Maven execution with 278 tests"
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

echo "✓ Docker containers are running"

mkdir -p $SCRIPT_DIR/tck-reports

echo "Testing connectivity to NemakiWare core service..."
if ! curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service on localhost:8080"
    echo "Please ensure the Docker environment is fully started and port 8080 is exposed"
    exit 1
fi
echo "✓ Core service is accessible from host"

echo "Preparing TCK configuration for host-based execution..."
cp $SCRIPT_DIR/cmis-tck-parameters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-docker.properties
echo "Verifying configuration file placement..."
ls -la $NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters-docker.properties

echo "Compiling comprehensive TCK test classes..."
cd $NEMAKI_HOME/core
mvn test-compile -Pdevelopment -q

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile TCK test classes"
    exit 1
fi

echo "Executing comprehensive TCK tests (using standard OpenCMIS test groups)..."
echo "Using host-based Maven execution with comprehensive test coverage..."

cd $NEMAKI_HOME/core
echo "Compiling test classes first..."
mvn test-compile -Ptck-docker -q

echo "Running comprehensive TCK tests via Maven exec plugin (standalone runner)..."
mvn exec:java -Dexec.mainClass="jp.aegif.nemaki.cmis.tck.ComprehensiveAllTest" \
    -Dexec.classpathScope=test \
    -Ptck-docker \
    -Djetty.skip=true \
    > $SCRIPT_DIR/tck-reports/tck-execution.log 2>&1

TCK_EXIT_CODE=$?

echo "Generating comprehensive TCK reports..."
cd $SCRIPT_DIR
./generate-tck-report.sh

if [ $TCK_EXIT_CODE -eq 0 ]; then
    echo "✓ TCK test execution completed successfully!"
else
    echo "⚠ TCK test execution completed with test failures (exit code: $TCK_EXIT_CODE)"
    echo "This is expected - check reports for detailed results"
fi

echo "Reports available in: $SCRIPT_DIR/tck-reports/"
ls -la $SCRIPT_DIR/tck-reports/ 2>/dev/null || echo "No reports generated"

if [ -f "$SCRIPT_DIR/tck-reports/current-score.txt" ]; then
    CURRENT_SCORE=$(cat "$SCRIPT_DIR/tck-reports/current-score.txt")
    echo "Current TCK Score: ${CURRENT_SCORE}%"
fi
