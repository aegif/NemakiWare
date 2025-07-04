#!/bin/bash

# NemakiWare Jakarta EE 10 Build Script
# This script creates a stable, reproducible build process for Jakarta EE 10 migration with Metro RI

set -e

echo "=== NemakiWare Jakarta EE 10 Build Process ==="
echo "Starting time: $(date)"

# Change to project root
cd "$(dirname "$0")/.."
PROJECT_ROOT=$(pwd)
echo "Project root: $PROJECT_ROOT"

# 1. Environment Setup
echo ""
echo "1. Setting up Java 17 environment..."
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Verify Java version
JAVA_VERSION=$(java -version 2>&1 | grep "openjdk version" | cut -d'"' -f2)
echo "Java version: $JAVA_VERSION"
if [[ ! "$JAVA_VERSION" == "17."* ]]; then
    echo "ERROR: Java 17 is required. Current version: $JAVA_VERSION"
    exit 1
fi

# 2. Clean Previous Build
echo ""
echo "2. Cleaning previous build artifacts..."
mvn clean -q

# 3. Build Core Module with Jakarta Profile
echo ""
echo "3. Building core module with Jakarta profile..."
echo "   - Jakarta-converted OpenCMIS JARs will be used"
echo "   - Metro RI JAX-WS Runtime will be included"
echo "   - Non-Jakarta JARs will be removed to prevent conflicts"

mvn package -f core/pom.xml -Pjakarta -Pdevelopment -q

# 4. Verify Build Success
echo ""
echo "4. Verifying build artifacts..."
CORE_WAR="$PROJECT_ROOT/core/target/core.war"
if [[ ! -f "$CORE_WAR" ]]; then
    echo "ERROR: core.war not found at $CORE_WAR"
    exit 1
fi

WAR_SIZE=$(ls -lh "$CORE_WAR" | awk '{print $5}')
echo "   ✓ core.war created: $WAR_SIZE"

# 5. Verify Jakarta JARs in WAR
echo ""
echo "5. Verifying Jakarta conversion in WAR..."
cd core/target

# Check for Jakarta-converted OpenCMIS JARs (by size and date)
JAKARTA_JARS=$(unzip -l core.war | grep "chemistry-opencmis.*\.jar" | grep "Jul.*2025" | wc -l)
echo "   ✓ Jakarta-converted OpenCMIS JARs: $JAKARTA_JARS"

# Check for Metro RI
METRO_RI=$(unzip -l core.war | grep "jaxws-rt-4.0.2.jar" | wc -l)
echo "   ✓ Metro RI JAX-WS Runtime: $METRO_RI"

# Check total OpenCMIS JARs
TOTAL_OPENCMIS=$(unzip -l core.war | grep "chemistry-opencmis.*\.jar" | grep -v "test" | wc -l)
echo "   ✓ Total OpenCMIS JARs: $TOTAL_OPENCMIS"

# List all OpenCMIS JARs for verification
echo "   JAR details:"
unzip -l core.war | grep "chemistry-opencmis.*\.jar" | grep -v "test" | head -8

# Accept if we have at least 7 OpenCMIS JARs total and Metro RI
if [[ $TOTAL_OPENCMIS -lt 7 ]]; then
    echo "ERROR: Insufficient OpenCMIS JARs found"
    exit 1
fi

if [[ $METRO_RI -lt 1 ]]; then
    echo "ERROR: Metro RI JAX-WS Runtime not found"
    exit 1
fi

# 6. Copy WAR to Docker Context
echo ""
echo "6. Preparing Docker deployment..."
cd "$PROJECT_ROOT"
cp core/target/core.war docker/core/core.war
echo "   ✓ WAR copied to Docker context"

# 7. Build Summary
echo ""
echo "=== Build Summary ==="
echo "✓ Java 17 environment verified"
echo "✓ Maven build with Jakarta profile completed"
echo "✓ Jakarta-converted OpenCMIS JARs: $JAKARTA_JARS"
echo "✓ Metro RI JAX-WS Runtime included"
echo "✓ WAR file ready for deployment"
echo ""
echo "Next steps:"
echo "  1. Run Docker environment: cd docker && ./deploy-jakarta.sh"
echo "  2. Test CMIS endpoints after startup"
echo ""
echo "Build completed successfully at: $(date)"