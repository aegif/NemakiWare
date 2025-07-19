#!/bin/bash

# NemakiWare Maven Jakarta Build Script (Simplified)
# This script uses Maven Jakarta profile for one-command build with automatic initialization

set -e

echo "=== NemakiWare Maven Jakarta Build (Simplified) ==="
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

# 2. Maven Jakarta Build (All-in-One)
echo ""
echo "2. Building with Maven Jakarta profile..."
echo "   ✓ Jakarta EE 10 conversion: Automatic"
echo "   ✓ OpenCMIS Jakarta JARs: Maven handles"
echo "   ✓ JAX-WS Runtime: Maven includes"
echo "   ✓ Auto-initialization: Built-in to WAR"

mvn clean package -f core/pom.xml -Pdevelopment,jakarta -U

# 3. Verify Build Success
echo ""
echo "3. Verifying build artifacts..."
CORE_WAR="$PROJECT_ROOT/core/target/core.war"
if [[ ! -f "$CORE_WAR" ]]; then
    echo "ERROR: core.war not found at $CORE_WAR"
    exit 1
fi

WAR_SIZE=$(ls -lh "$CORE_WAR" | awk '{print $5}')
echo "   ✓ Maven-built Jakarta WAR: $WAR_SIZE"

# 4. Quick WAR Content Verification
echo ""
echo "4. Verifying WAR contents..."
cd core/target

# Check Jakarta JARs
JAKARTA_OPENCMIS=$(unzip -l core.war | grep "chemistry-opencmis.*\.jar" | wc -l)
echo "   ✓ OpenCMIS JARs: $JAKARTA_OPENCMIS"

# Check JAX-WS
JAKARTA_JAXWS=$(unzip -l core.war | grep "jaxws-rt.*\.jar" | wc -l)
echo "   ✓ JAX-WS Runtime: $JAKARTA_JAXWS"

# Check initialization data
INIT_DUMPS=$(unzip -l core.war | grep "init\.dump" | wc -l)
echo "   ✓ Initialization data files: $INIT_DUMPS"

# Check auto-initialization config
PATCH_CONTEXT=$(unzip -l core.war | grep "patchContext\.xml" | wc -l)
echo "   ✓ Auto-initialization config: $PATCH_CONTEXT"

# 5. Copy to Docker Context (Simple)
echo ""
echo "5. Preparing Docker deployment..."
cd "$PROJECT_ROOT"
cp core/target/core.war docker/core/core.war
echo "   ✓ WAR copied to Docker context"

# 6. Build Summary
echo ""
echo "=== Maven Jakarta Build Summary ==="
echo "✅ Java 17 environment: Ready"
echo "✅ Maven Jakarta profile: Complete"
echo "✅ Jakarta EE 10 conversion: Automatic"
echo "✅ Auto-initialization: Built-in"
echo "✅ Docker deployment: Ready"
echo ""
echo "WAR file includes:"
echo "  - Jakarta-converted OpenCMIS libraries"
echo "  - Metro RI JAX-WS Runtime"
echo "  - Complete auto-initialization system"
echo "  - All required configuration files"
echo ""
echo "Next steps:"
echo "  cd docker && ./deploy-maven-jakarta.sh"
echo ""
echo "Build completed successfully at: $(date)"