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

echo "Compiling comprehensive TCK test classes..."
cd $NEMAKI_HOME/core
mvn test-compile -Ptck-docker -q

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to compile TCK test classes"
    exit 1
fi

echo "Running comprehensive query testing and fixes..."
cd $SCRIPT_DIR
chmod +x fix-repository-mismatch.sh diagnose-solr-connectivity.sh fix-solr-connectivity.sh test-individual-queries.sh comprehensive-query-test.sh validate-tck-improvements.sh
./comprehensive-query-test.sh

echo "Executing comprehensive TCK tests (using standard OpenCMIS test groups)..."
echo "Using direct Maven surefire execution (bypassing Jetty completely)..."

cd $NEMAKI_HOME/core
echo "Running comprehensive TCK tests via direct surefire plugin..."
mvn surefire:test -Dtest="jp.aegif.nemaki.cmis.tck.ComprehensiveAllTest" \
    -Dparameters.file="cmis-tck-parameters-docker.properties" \
    -DfailIfNoTests=false \
    > $SCRIPT_DIR/tck-reports/tck-execution.log 2>&1

TCK_EXIT_CODE=$?

echo "Generating comprehensive TCK reports..."
cd $SCRIPT_DIR
./generate-tck-report.sh

echo "Generating enhanced summary with user's reporting script..."
if [ -f "./show-tck-summary.sh" ]; then
    ./show-tck-summary.sh
fi

echo "Validating TCK improvements and generating final reports..."
./validate-tck-improvements.sh

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

if [ -f "$SCRIPT_DIR/tck-reports/improvement-summary.txt" ]; then
    echo -e "\n=== IMPROVEMENT SUMMARY ==="
    cat "$SCRIPT_DIR/tck-reports/improvement-summary.txt"
fi
