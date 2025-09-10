#!/bin/bash

# Container deployment verification script
# This script checks if the new code is actually running inside Docker containers

echo "=== NemakiWare Container Deployment Verification ==="
echo "Timestamp: $(date)"
echo

# Step 1: Check container status
echo "1. Container Status Check:"
if ! docker ps | grep -q "docker-core-1"; then
    echo "❌ Core container (docker-core-1) is not running"
    echo "   Start with: cd docker && docker compose -f docker-compose-simple.yml up -d"
    exit 1
else
    echo "✅ Core container is running"
    CONTAINER_STATUS=$(docker ps --format "table {{.Names}}\t{{.Status}}" | grep docker-core-1)
    echo "   ${CONTAINER_STATUS}"
fi

echo

# Step 2: Check for unique version marker in logs
echo "2. Version Marker Verification:"
echo "   Looking for unique BUILD-20250819 version marker..."

# Clear old logs to get fresh output
echo "   Triggering a request to generate fresh logs..."
curl -s -o /dev/null -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo&_timestamp=$(date +%s)"

sleep 2

# Check for version marker
VERSION_MARKER=$(docker logs docker-core-1 --tail 50 | grep "VERSION CHECK:" | tail -1)
if [ ! -z "$VERSION_MARKER" ]; then
    echo "✅ Version marker found:"
    echo "   $VERSION_MARKER"
    
    # Extract build ID
    if echo "$VERSION_MARKER" | grep -q "BUILD-20250819"; then
        echo "✅ Latest build ID (BUILD-20250819) confirmed in running container"
        BUILD_ID=$(echo "$VERSION_MARKER" | grep -o "BUILD-20250819-[0-9]*")
        echo "   Build ID: $BUILD_ID"
    else
        echo "⚠️  Version marker found but doesn't contain latest BUILD-20250819 ID"
        echo "   Container may be running old code"
    fi
else
    echo "❌ No version marker found in recent logs"
    echo "   This suggests the custom servlet is not being invoked"
    echo "   Check if requests are reaching the servlet or if container needs restart"
fi

echo

# Step 3: Check static initialization
echo "3. Static Initialization Check:"
STATIC_INIT=$(docker logs docker-core-1 --tail 100 | grep "STATIC INIT: NemakiBrowserBindingServlet" | tail -1)
if [ ! -z "$STATIC_INIT" ]; then
    echo "✅ Static initialization found:"
    echo "   $STATIC_INIT"
else
    echo "⚠️  Static initialization not found in recent logs"
    echo "   The custom servlet class may not have been loaded yet"
fi

echo

# Step 4: Check WAR file inside container
echo "4. Container WAR File Verification:"
echo "   Checking WAR file timestamp inside container..."
CONTAINER_WAR_INFO=$(docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core.war 2>/dev/null)
if [ $? -eq 0 ]; then
    echo "✅ WAR file found inside container:"
    echo "   $CONTAINER_WAR_INFO"
else
    echo "❌ WAR file not found at expected location inside container"
fi

echo "   Checking if WAR is deployed (expanded)..."
WEBAPP_INFO=$(docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core/ 2>/dev/null | head -5)
if [ $? -eq 0 ]; then
    echo "✅ WAR file appears to be deployed (webapp directory exists)"
    echo "   Sample contents:"
    echo "$WEBAPP_INFO"
else
    echo "❌ WAR file not deployed - webapp directory not found"
fi

echo

# Step 5: Check specific class file in container
echo "5. Container Class File Verification:"
echo "   Checking for NemakiBrowserBindingServlet.class inside container..."
CLASS_INFO=$(docker exec docker-core-1 find /usr/local/tomcat/webapps/core/WEB-INF/classes -name "*NemakiBrowserBindingServlet*" 2>/dev/null)
if [ ! -z "$CLASS_INFO" ]; then
    echo "✅ Servlet class file found in container:"
    echo "   $CLASS_INFO"
    
    # Get class file timestamp
    CLASS_TIMESTAMP=$(docker exec docker-core-1 ls -la "$CLASS_INFO" 2>/dev/null)
    echo "   Details: $CLASS_TIMESTAMP"
else
    echo "❌ NemakiBrowserBindingServlet.class not found in container"
    echo "   This indicates deployment failure"
fi

echo

# Step 6: Test servlet responsiveness
echo "6. Servlet Response Test:"
echo "   Testing CMIS Browser Binding endpoint..."
RESPONSE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo&_test=$(date +%s)")
if [ "$RESPONSE_CODE" = "200" ]; then
    echo "✅ Servlet is responding (HTTP $RESPONSE_CODE)"
    
    # Check if our servlet was invoked (look for fresh logs)
    sleep 2
    RECENT_SERVICE_CALL=$(docker logs docker-core-1 --tail 10 | grep "NEMAKI SERVICE METHOD CALLED" | tail -1)
    if [ ! -z "$RECENT_SERVICE_CALL" ]; then
        echo "✅ Custom servlet method invocation confirmed:"
        echo "   $RECENT_SERVICE_CALL"
    else
        echo "⚠️  No recent service method calls found"
        echo "   Servlet may be responding but not invoking custom code"
    fi
else
    echo "❌ Servlet not responding properly (HTTP $RESPONSE_CODE)"
    if [ "$RESPONSE_CODE" = "000" ]; then
        echo "   Connection failed - container may not be ready"
    fi
fi

echo

# Step 7: Summary and recommendations
echo "7. Deployment Verification Summary:"

# Count successful checks
CHECKS_PASSED=0
TOTAL_CHECKS=6

# Container running
if docker ps | grep -q "docker-core-1"; then
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
fi

# Version marker present
if [ ! -z "$VERSION_MARKER" ] && echo "$VERSION_MARKER" | grep -q "BUILD-20250819"; then
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
fi

# Static init found
if [ ! -z "$STATIC_INIT" ]; then
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
fi

# WAR file in container
if docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core.war &>/dev/null; then
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
fi

# Class file in container
if [ ! -z "$CLASS_INFO" ]; then
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
fi

# Servlet responding
if [ "$RESPONSE_CODE" = "200" ]; then
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
fi

echo "   Checks passed: $CHECKS_PASSED/$TOTAL_CHECKS"

if [ $CHECKS_PASSED -eq $TOTAL_CHECKS ]; then
    echo "✅ ALL CHECKS PASSED - Your new code is deployed and running"
elif [ $CHECKS_PASSED -ge 4 ]; then
    echo "⚠️  MOST CHECKS PASSED - Code likely deployed but may have minor issues"
else
    echo "❌ MULTIPLE CHECKS FAILED - Code deployment appears unsuccessful"
    echo
    echo "   Recommended actions:"
    echo "   1. Rebuild and redeploy: mvn clean package -f core/pom.xml -Pdevelopment"
    echo "   2. Copy WAR: cp core/target/core.war docker/core/core.war"
    echo "   3. Restart container: cd docker && docker compose -f docker-compose-simple.yml restart core"
    echo "   4. Wait for deployment: sleep 30"
    echo "   5. Re-run verification: ./verify-container-deployment.sh"
fi

echo
echo "=== Container Verification Complete ==="