#!/bin/bash

# Enhanced TCK Runner with Java 17 Module System Fix
# Solves UNNAMEDモジュール問題 for Maven exec environment

echo "=========================================="
echo "NemakiWare TCK with Module System Fix"
echo "Java 17 + Jakarta EE 10 Environment"
echo "=========================================="

# Environment setup
CORE_DIR="/Users/ishiiakinori/NemakiWare"
POM_FILE="core/pom.xml"

# Java 17 Module System compatibility flags
MODULE_OPENS="--add-opens=java.base/java.util=ALL-UNNAMED"
MODULE_OPENS="$MODULE_OPENS --add-opens=java.base/java.lang=ALL-UNNAMED"
MODULE_OPENS="$MODULE_OPENS --add-opens=java.base/java.io=ALL-UNNAMED"
MODULE_OPENS="$MODULE_OPENS --add-opens=java.base/java.net=ALL-UNNAMED"
MODULE_OPENS="$MODULE_OPENS --add-opens=java.base/java.time=ALL-UNNAMED"

cd $CORE_DIR

echo ""
echo "1. Java Environment Check..."
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
echo "   Java Version: $JAVA_VERSION"

if [[ ! "$JAVA_VERSION" =~ ^17\. ]]; then
    echo "   ⚠️  Warning: Java 17 is required for optimal compatibility"
fi

echo ""
echo "2. Building Test Classes..."
mvn compiler:testCompile -f $POM_FILE -Pdevelopment -Dmaven.test.skip=false -q
if [ $? -ne 0 ]; then
    echo "   ✗ Test compilation failed"
    exit 1
fi
echo "   ✓ Test classes compiled"

echo ""
echo "3. Starting Jetty Server..."
echo "   Starting Jetty on port 8081 with module system fixes..."

# Start Jetty with module system fixes
export MAVEN_OPTS="$MODULE_OPENS"

mvn jetty:run -f $POM_FILE -Pdevelopment -Djetty.port=8081 -Djetty.skip=false > /tmp/jetty-startup.log 2>&1 &
JETTY_PID=$!

# Wait for Jetty to start
echo "   Waiting for Jetty startup (max 60 seconds)..."
START_TIME=$(date +%s)
TIMEOUT=60

while [ $(($(date +%s) - START_TIME)) -lt $TIMEOUT ]; do
    if curl -s http://localhost:8081/core >/dev/null 2>&1; then
        echo "   ✓ Jetty server ready on port 8081"
        break
    fi
    echo -n "."
    sleep 2
done

if [ $(($(date +%s) - START_TIME)) -ge $TIMEOUT ]; then
    echo ""
    echo "   ✗ Jetty startup timeout"
    echo "   Jetty startup log:"
    cat /tmp/jetty-startup.log | tail -20
    kill $JETTY_PID 2>/dev/null
    exit 1
fi

echo ""
echo "4. Environment Verification..."

# Test CMIS endpoint
echo "   Testing CMIS endpoint..."
CMIS_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8081/core/atom/bedroom)
if [ "$CMIS_STATUS" = "200" ]; then
    echo "   ✓ CMIS endpoint responding (HTTP $CMIS_STATUS)"
else
    echo "   ⚠️  CMIS endpoint status: HTTP $CMIS_STATUS"
fi

echo ""
echo "5. TCK Test Execution with Module System Fix..."

# Parse command line arguments
if [ $# -eq 0 ]; then
    TEST_GROUPS="basics"
    echo "   Executing default test group: basics"
else
    TEST_GROUPS="$*"
    echo "   Executing test groups: $TEST_GROUPS"
fi

# Run TCK tests with module system fix
echo "   Using Java module system fixes for TreeSet.m access..."

# Method 1: Use Java directly with classpath
echo "   Building test classpath..."
TEST_CLASSPATH="core/target/test-classes:core/target/classes"

# Add Maven dependencies to classpath using Maven dependency plugin
echo "   Resolving Maven dependencies..."
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/maven-classpath.txt -f $POM_FILE -Pdevelopment -q > /dev/null 2>&1

if [ -f /tmp/maven-classpath.txt ]; then
    MAVEN_CLASSPATH=$(cat /tmp/maven-classpath.txt)
    TEST_CLASSPATH="$TEST_CLASSPATH:$MAVEN_CLASSPATH"
    rm -f /tmp/maven-classpath.txt
    echo "   ✓ Maven dependencies resolved"
else
    echo "   ⚠️  Maven dependency resolution failed, using fallback"
    # Add Jakarta converted JARs
    for jar in $(find lib/jakarta-converted -name "*.jar" 2>/dev/null); do
        TEST_CLASSPATH="$TEST_CLASSPATH:$jar"
    done
    
    # Add essential JARs from Maven repository
    if [ -d ~/.m2/repository ]; then
        for jar in $(find ~/.m2/repository -name "*.jar" -path "*/junit/*" -o -path "*/slf4j/*" -o -path "*/logback/*" -o -path "*/commons-logging/*" 2>/dev/null | head -30); do
            TEST_CLASSPATH="$TEST_CLASSPATH:$jar"
        done
    fi
fi

echo "   Executing TCK with module system compatibility..."

java $MODULE_OPENS \
    -cp "$TEST_CLASSPATH" \
    jp.aegif.nemaki.cmis.tck.JettyTckRunner $TEST_GROUPS 2>&1

TCK_RESULT=$?

echo ""
echo "6. Cleanup..."

# Stop Jetty
echo "   Stopping Jetty server..."
kill $JETTY_PID 2>/dev/null
sleep 3

# Force kill if still running
if ps -p $JETTY_PID > /dev/null 2>&1; then
    kill -9 $JETTY_PID 2>/dev/null
    echo "   ✓ Jetty server stopped (force)"
else
    echo "   ✓ Jetty server stopped"
fi

# Clean up temporary files
rm -f /tmp/jetty-startup.log

# Unset environment variables
unset MAVEN_OPTS

echo ""
echo "=========================================="
echo "TCK Test Execution Summary"
echo "=========================================="

if [ $TCK_RESULT -eq 0 ]; then
    echo "🎉 TCK tests completed successfully!"
    echo "✅ Java 17 Module System issue resolved"
    echo ""
    echo "The UNNAMEDモジュール問題 has been fixed by:"
    echo "   • --add-opens=java.base/java.util=ALL-UNNAMED"
    echo "   • Direct Java execution with custom classpath"
    echo "   • Module system compatibility flags"
    exit 0
else
    echo "⚠️  Some TCK tests failed or encountered errors"
    echo "📊 Check output above for details"
    echo ""
    echo "If TreeSet.m access errors persist, verify:"
    echo "   1. Java version is 17.x"
    echo "   2. Module system flags are applied correctly"
    echo "   3. Classpath includes Jakarta-converted JARs"
    exit 1
fi