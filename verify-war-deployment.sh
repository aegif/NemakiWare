#!/bin/bash

# WAR deployment verification script
# This script ensures that modified code is actually compiled and deployed

echo "=== NemakiWare WAR Deployment Verification ==="
echo "Timestamp: $(date)"
echo

# Step 1: Verify WAR file exists
echo "1. WAR File Verification:"
if [ -f "core/target/core.war" ]; then
    echo "✅ WAR file exists: core/target/core.war"
    WAR_SIZE=$(stat -f%z core/target/core.war 2>/dev/null || stat -c%s core/target/core.war 2>/dev/null)
    echo "   Size: ${WAR_SIZE} bytes"
    WAR_DATE=$(stat -f%Sm core/target/core.war 2>/dev/null || stat -c%y core/target/core.war 2>/dev/null)
    echo "   Modified: ${WAR_DATE}"
else
    echo "❌ WAR file not found: core/target/core.war"
    exit 1
fi

echo

# Step 2: Verify critical class files in WAR
echo "2. Critical Class Files in WAR:"
echo "   Checking NemakiBrowserBindingServlet.class..."
if unzip -l core/target/core.war | grep -q "NemakiBrowserBindingServlet.class"; then
    echo "✅ NemakiBrowserBindingServlet.class found in WAR"
    CLASS_INFO=$(unzip -l core/target/core.war | grep "NemakiBrowserBindingServlet.class")
    echo "   ${CLASS_INFO}"
else
    echo "❌ NemakiBrowserBindingServlet.class NOT found in WAR"
    echo "   This indicates compilation failure or classpath issues"
    exit 1
fi

echo "   Checking ObjectServiceImpl.class..."
if unzip -l core/target/core.war | grep -q "ObjectServiceImpl.class"; then
    echo "✅ ObjectServiceImpl.class found in WAR"
else
    echo "❌ ObjectServiceImpl.class NOT found in WAR"
fi

echo

# Step 3: Verify Docker context WAR is updated
echo "3. Docker Context WAR Verification:"
if [ -f "docker/core/core.war" ]; then
    echo "✅ Docker context WAR exists: docker/core/core.war"
    DOCKER_WAR_SIZE=$(stat -f%z docker/core/core.war 2>/dev/null || stat -c%s docker/core/core.war 2>/dev/null)
    DOCKER_WAR_DATE=$(stat -f%Sm docker/core/core.war 2>/dev/null || stat -c%y docker/core/core.war 2>/dev/null)
    echo "   Size: ${DOCKER_WAR_SIZE} bytes"
    echo "   Modified: ${DOCKER_WAR_DATE}"
    
    # Compare timestamps
    if [ -f "core/target/core.war" ]; then
        if [ "core/target/core.war" -nt "docker/core/core.war" ]; then
            echo "⚠️  Target WAR is newer than Docker context WAR"
            echo "   Run: cp core/target/core.war docker/core/core.war"
            exit 1
        elif [ "docker/core/core.war" -nt "core/target/core.war" ]; then
            echo "✅ Docker context WAR is up to date"
        else
            echo "✅ Both WAR files have same timestamp"
        fi
    fi
else
    echo "❌ Docker context WAR not found: docker/core/core.war"
    echo "   Run: cp core/target/core.war docker/core/core.war"
    exit 1
fi

echo

# Step 4: Check container deployment status
echo "4. Container Deployment Status:"
if docker ps | grep -q "docker-core-1"; then
    echo "✅ Core container is running"
    
    # Check container logs for our version markers
    echo "   Checking for servlet initialization logs..."
    if docker logs docker-core-1 --tail 100 | grep -q "NemakiBrowserBindingServlet"; then
        echo "✅ NemakiBrowserBindingServlet initialization found in logs"
        
        # Extract the most recent version check timestamp
        VERSION_CHECK=$(docker logs docker-core-1 --tail 100 | grep "VERSION CHECK:" | tail -1)
        if [ ! -z "$VERSION_CHECK" ]; then
            echo "   Most recent version: $VERSION_CHECK"
        fi
    else
        echo "⚠️  NemakiBrowserBindingServlet initialization not found in recent logs"
        echo "   Container may need restart to load new code"
    fi
    
    # Check for static initialization
    if docker logs docker-core-1 --tail 100 | grep -q "STATIC INIT: NemakiBrowserBindingServlet"; then
        echo "✅ Static initialization found - class was loaded"
    else
        echo "⚠️  Static initialization not found - class may not be loaded"
    fi
    
else
    echo "❌ Core container is not running"
    echo "   Start with: cd docker && docker compose -f docker-compose-simple.yml up -d"
    exit 1
fi

echo

# Step 5: Test endpoint availability
echo "5. Endpoint Availability Test:"
ENDPOINT_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo")
if [ "$ENDPOINT_RESPONSE" = "200" ]; then
    echo "✅ CMIS Browser Binding endpoint is accessible (HTTP $ENDPOINT_RESPONSE)"
else
    echo "❌ CMIS Browser Binding endpoint returned HTTP $ENDPOINT_RESPONSE"
    if [ "$ENDPOINT_RESPONSE" = "000" ]; then
        echo "   Container may not be ready or network issue"
    fi
fi

echo

# Step 6: Summary and recommendations
echo "6. Verification Summary:"
echo "   If all checks pass, your code changes should be deployed and active"
echo "   If any checks fail, follow the recommended actions above"
echo "   For container deployment issues, try: cd docker && docker compose -f docker-compose-simple.yml restart core"

echo
echo "=== Verification Complete ==="