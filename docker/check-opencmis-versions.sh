#!/bin/bash

# OpenCMIS Version Consistency Checker
# Prevents Browser Binding parameter parsing failures caused by version mixing
# Created: 2025-07-07

echo "=== OpenCMIS Version Consistency Check ==="
echo "Checking for version alignment across all components..."
echo

ERRORS=0

# 1. Check Maven pom.xml properties
echo "1. Checking Maven pom.xml files..."
POM_VERSIONS=$(grep -r "<org.apache.chemistry.opencmis.version>" */pom.xml 2>/dev/null | grep -v "\.bak" | cut -d'>' -f2 | cut -d'<' -f1 | sort -u)
POM_COUNT=$(echo "$POM_VERSIONS" | wc -l | tr -d ' ')

if [ "$POM_COUNT" -eq 1 ] && [ "$POM_VERSIONS" = "1.1.0" ]; then
    echo "✅ Maven properties: ALL use version 1.1.0"
elif [ "$POM_COUNT" -eq 0 ]; then
    echo "⚠️  Maven properties: No version properties found (may be using defaults)"
else
    echo "❌ Maven properties: INCONSISTENT versions found:"
    grep -r "<org.apache.chemistry.opencmis.version>" */pom.xml 2>/dev/null | grep -v "\.bak"
    ERRORS=$((ERRORS + 1))
fi
echo

# 2. Check Jakarta JAR directory
echo "2. Checking Jakarta converted JARs..."
JAKARTA_DIR=""
if [ -d "lib/jakarta-converted" ]; then
    JAKARTA_DIR="lib/jakarta-converted"
elif [ -d "../lib/jakarta-converted" ]; then
    JAKARTA_DIR="../lib/jakarta-converted"
fi

if [ -n "$JAKARTA_DIR" ]; then
    SNAPSHOT_JARS=$(find "$JAKARTA_DIR" -name "*SNAPSHOT*" 2>/dev/null)
    if [ -z "$SNAPSHOT_JARS" ]; then
        echo "✅ Jakarta JARs: No SNAPSHOT versions found"
    else
        echo "❌ Jakarta JARs: SNAPSHOT versions detected:"
        echo "$SNAPSHOT_JARS"
        ERRORS=$((ERRORS + 1))
    fi
    
    # Check for required 1.1.0 JARs
    REQUIRED_JARS=(
        "chemistry-opencmis-client-api-1.1.0-jakarta.jar"
        "chemistry-opencmis-client-impl-1.1.0-jakarta.jar"
        "chemistry-opencmis-client-bindings-1.1.0-jakarta.jar"
        "chemistry-opencmis-commons-api-1.1.0-jakarta.jar"
        "chemistry-opencmis-commons-impl-1.1.0-jakarta.jar"
        "chemistry-opencmis-server-bindings-1.1.0-jakarta.jar"
        "chemistry-opencmis-server-support-1.1.0-jakarta.jar"
        "chemistry-opencmis-test-tck-1.1.0-jakarta.jar"
        "jaxws-rt-4.0.2-jakarta.jar"
    )
    
    MISSING_JARS=""
    for jar in "${REQUIRED_JARS[@]}"; do
        if [ ! -f "$JAKARTA_DIR/$jar" ]; then
            MISSING_JARS="$MISSING_JARS\n  - $jar"
        fi
    done
    
    if [ -z "$MISSING_JARS" ]; then
        echo "✅ Jakarta JARs: All required 1.1.0 JARs present"
    else
        echo "❌ Jakarta JARs: Missing required JARs:"
        echo -e "$MISSING_JARS"
        ERRORS=$((ERRORS + 1))
    fi
else
    echo "⚠️  Jakarta JARs: lib/jakarta-converted/ directory not found (may need to run jakarta-transform.sh)"
fi
echo

# 3. Check Docker references
echo "3. Checking Docker configuration files..."
DOCKER_SNAPSHOT_REFS=$(grep -r "1.2.0-SNAPSHOT" docker/ 2>/dev/null | grep -v "\.bak")
if [ -z "$DOCKER_SNAPSHOT_REFS" ]; then
    echo "✅ Docker files: No SNAPSHOT references found"
else
    echo "❌ Docker files: SNAPSHOT references detected:"
    echo "$DOCKER_SNAPSHOT_REFS"
    ERRORS=$((ERRORS + 1))
fi

# Check Tomcat version (adjust path based on script location)
DOCKERFILE_PATH=""
if [ -f "docker/core/Dockerfile.simple" ]; then
    DOCKERFILE_PATH="docker/core/Dockerfile.simple"
elif [ -f "../docker/core/Dockerfile.simple" ]; then
    DOCKERFILE_PATH="../docker/core/Dockerfile.simple"  
elif [ -f "core/Dockerfile.simple" ]; then
    DOCKERFILE_PATH="core/Dockerfile.simple"
fi

if [ -n "$DOCKERFILE_PATH" ]; then
    TOMCAT_VERSION=$(grep "FROM tomcat:" "$DOCKERFILE_PATH" | head -1)
    if echo "$TOMCAT_VERSION" | grep -q "tomcat:10"; then
        echo "✅ Tomcat version: Using Tomcat 10+ (Jakarta EE compatible)"
    else
        echo "❌ Tomcat version: $TOMCAT_VERSION"
        echo "   Expected: tomcat:10.1-jre17 or higher"
        ERRORS=$((ERRORS + 1))
    fi
else
    echo "⚠️  Dockerfile: Dockerfile.simple not found in expected locations"
fi
echo

# 4. Final status
echo "=== SUMMARY ==="
if [ $ERRORS -eq 0 ]; then
    echo "✅ ALL VERSIONS CONSISTENT: 1.1.0"
    echo "✅ System is ready for Jakarta EE Browser Binding operation"
    exit 0
else
    echo "❌ FOUND $ERRORS VERSION INCONSISTENCIES"
    echo "❌ Browser Binding parameter parsing may fail"
    echo
    echo "REMEDIATION STEPS:"
    echo "1. Fix all inconsistencies above"
    echo "2. Run: find . -name '*-1.2.0-SNAPSHOT*' -delete"
    echo "3. Run: ./docker/jakarta-transform.sh"
    echo "4. Run: mvn clean package -Pjakarta -Pdevelopment"
    exit 1
fi