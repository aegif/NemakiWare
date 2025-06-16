#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "========================================"
echo "NemakiWare Prerequisites Check"
echo "========================================"

# Check required tools
echo "1. Checking required tools..."
MISSING_TOOLS=()

if ! command -v docker > /dev/null; then
    MISSING_TOOLS+=("docker")
fi

if ! command -v docker-compose > /dev/null && ! docker compose version > /dev/null 2>&1; then
    MISSING_TOOLS+=("docker-compose")
fi

if ! command -v mvn > /dev/null; then
    MISSING_TOOLS+=("maven")
fi

if ! command -v sbt > /dev/null; then
    MISSING_TOOLS+=("sbt")
fi

if ! command -v java > /dev/null; then
    MISSING_TOOLS+=("java")
fi

if [ ${#MISSING_TOOLS[@]} -ne 0 ]; then
    echo "✗ Missing required tools: ${MISSING_TOOLS[*]}"
    echo "Please install the missing tools before proceeding."
    exit 1
else
    echo "✓ All required tools are available"
fi

# Check Java version
echo ""
echo "2. Checking Java version..."
JAVA_VERSION=$(java -version 2>&1 | grep "version" | awk '{print $3}' | sed 's/"//g' | cut -d'.' -f1-2)
echo "Java version: $JAVA_VERSION"
if [[ "$JAVA_VERSION" < "1.8" ]] || [[ "$JAVA_VERSION" == "1.8"* ]]; then
    echo "✓ Java 8 compatible version detected"
else
    echo "⚠ Warning: Java version may not be compatible (Java 8 recommended)"
fi

# Check Maven version
echo ""
echo "3. Checking Maven version..."
MVN_VERSION=$(mvn -version 2>/dev/null | head -1 | awk '{print $3}' || echo "unknown")
echo "Maven version: $MVN_VERSION"

# Check required directories
echo ""
echo "4. Checking required directories..."
REQUIRED_DIRS=(
    "$NEMAKI_HOME/core"
    "$NEMAKI_HOME/ui" 
    "$NEMAKI_HOME/solr"
    "$NEMAKI_HOME/setup/couchdb/cloudant-init"
    "$NEMAKI_HOME/docker"
)

for dir in "${REQUIRED_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        echo "✓ $dir exists"
    else
        echo "✗ $dir missing"
        exit 1
    fi
done

# Check required files
echo ""
echo "5. Checking required files..."
REQUIRED_FILES=(
    "$NEMAKI_HOME/docker/docker-compose-simple.yml"
    "$NEMAKI_HOME/docker/prepare-initializer.sh"
    "$NEMAKI_HOME/docker/build-solr.sh"
    "$NEMAKI_HOME/core/pom.xml"
    "$NEMAKI_HOME/ui/build.sbt"
    "$NEMAKI_HOME/solr/pom.xml"
)

for file in "${REQUIRED_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo "✓ $file exists"
    else
        echo "✗ $file missing"
        exit 1
    fi
done

# Check Docker daemon
echo ""
echo "6. Checking Docker daemon..."
if docker info > /dev/null 2>&1; then
    echo "✓ Docker daemon is running"
else
    echo "✗ Docker daemon is not running"
    echo "Please start Docker daemon before proceeding."
    exit 1
fi

# Check available disk space
echo ""
echo "7. Checking available disk space..."
AVAILABLE_SPACE=$(df -h . | tail -1 | awk '{print $4}' | sed 's/[^0-9]//g')
if [ "$AVAILABLE_SPACE" -gt 2048 ]; then
    echo "✓ Sufficient disk space available"
else
    echo "⚠ Warning: Low disk space (less than 2GB available)"
fi

echo ""
echo "========================================"
echo "Prerequisites check completed successfully!"
echo "You can now run: ./test-simple.sh"
echo "========================================"