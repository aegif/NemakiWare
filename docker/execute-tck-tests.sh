#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=========================================="
echo "NemakiWare TCK Test Execution"
echo "=========================================="

if ! docker ps | grep -q "docker-core-1"; then
    echo "ERROR: NemakiWare core container is not running"
    echo "Please run './test-all.sh --auto-fix' first to start the Docker environment"
    exit 1
fi

if ! docker ps | grep -q "docker-couchdb-1"; then
    echo "ERROR: CouchDB container is not running"
    echo "Please run './test-all.sh --auto-fix' first to start the Docker environment"
    exit 1
fi

echo "✓ Docker containers are running"

mkdir -p $SCRIPT_DIR/tck-reports

echo "Testing connectivity to NemakiWare core service..."
if ! docker exec docker-core-1 curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service"
    echo "Please ensure the Docker environment is fully started and healthy"
    exit 1
fi
echo "✓ Core service is accessible"

echo "Preparing TCK configuration for Docker environment..."
cp $SCRIPT_DIR/cmis-tck-parameters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-parameters.properties
cp $SCRIPT_DIR/cmis-tck-filters-docker.properties $NEMAKI_HOME/core/src/test/resources/cmis-tck-filters.properties

echo "Building and executing TCK tests using existing infrastructure..."
cd $NEMAKI_HOME

echo "Compiling TCK test classes..."
mvn test-compile -f core/pom.xml -q

echo "Executing TCK tests against Docker environment..."
cd $NEMAKI_HOME/core
mvn test -Dtest="jp.aegif.nemaki.cmis.tck.AllTest" \
    -Dparameters.file="cmis-tck-parameters-docker.properties" \
    -Dmaven.test.skip=false \
    -Dmaven.jetty.skip=true \
    -Pproduct \
    > $SCRIPT_DIR/tck-reports/tck-execution.log 2>&1

echo "Capturing TCK test output..."
if [ -f "$SCRIPT_DIR/tck-reports/tck-execution.log" ]; then
    echo "TCK execution log created successfully"
    
    cat > $SCRIPT_DIR/tck-reports/tck-report.txt << 'REPORT_EOF'
========================================
NemakiWare CMIS TCK Test Results
========================================
REPORT_EOF
    
    echo "Execution Date: $(date)" >> $SCRIPT_DIR/tck-reports/tck-report.txt
    echo "" >> $SCRIPT_DIR/tck-reports/tck-report.txt
    
    echo "Test Execution Results:" >> $SCRIPT_DIR/tck-reports/tck-report.txt
    echo "======================" >> $SCRIPT_DIR/tck-reports/tck-report.txt
    cat $SCRIPT_DIR/tck-reports/tck-execution.log >> $SCRIPT_DIR/tck-reports/tck-report.txt
    
    echo "✓ TCK reports generated successfully"
else
    echo "ERROR: TCK execution log not found"
    exit 1
fi

echo "TCK test execution completed successfully!"
echo "Reports available in: $SCRIPT_DIR/tck-reports/"
ls -la $SCRIPT_DIR/tck-reports/
