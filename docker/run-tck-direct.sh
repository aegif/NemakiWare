#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "=========================================="
echo "NemakiWare Direct TCK Test Execution"
echo "Bypassing Maven to run TCK tests directly"
echo "=========================================="

CORE_RUNNING=$(docker ps --format "{{.Names}}" | grep -E "(core|docker-core)" || echo "")
if [ -z "$CORE_RUNNING" ]; then
    echo "ERROR: NemakiWare core container is not running"
    echo "Please run './test-all.sh --auto-fix' first"
    exit 1
fi

echo "✓ Docker containers are running"

if ! curl -f -s http://localhost:8080/core > /dev/null; then
    echo "ERROR: Cannot connect to NemakiWare core service"
    exit 1
fi
echo "✓ Core service is accessible"

mkdir -p $SCRIPT_DIR/tck-reports

echo "Building classpath for direct Java execution..."
cd $NEMAKI_HOME/core

mvn compile test-compile -Ptck-docker -q

CLASSPATH=$(mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q && cat /tmp/cp.txt)
CLASSPATH="$CLASSPATH:target/classes:target/test-classes"

echo "Classpath built successfully"

cp src/test/resources/cmis-tck-parameters-docker.properties /tmp/cmis-tck-parameters-docker.properties

echo "Running DockerTckRunner directly via Java..."
cd $SCRIPT_DIR

java -cp "$CLASSPATH" \
    -Djava.awt.headless=true \
    -Dfile.encoding=UTF-8 \
    jp.aegif.nemaki.cmis.tck.DockerTckRunner \
    > tck-reports/tck-direct-execution.log 2>&1

JAVA_EXIT_CODE=$?

echo "Direct TCK execution completed (exit code: $JAVA_EXIT_CODE)"

if [ -f "tck-reports/tck-direct-execution.log" ]; then
    echo "Generating TCK reports from direct execution..."
    ./generate-tck-report.sh
    
    if [ -f "tck-reports/current-score.txt" ]; then
        CURRENT_SCORE=$(cat "tck-reports/current-score.txt")
        echo "Current TCK Score: ${CURRENT_SCORE}%"
    fi
fi

echo "Direct TCK execution completed!"
echo "Reports available in: $SCRIPT_DIR/tck-reports/"
