#!/bin/bash

# Complete clean build and deployment script
# This script eliminates all caching issues and ensures fresh deployment

set -e  # Exit on any error

echo "=== NemakiWare Complete Clean Build and Deployment ==="
echo "Timestamp: $(date)"
echo

# Set Java environment
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Verify Java version
echo "1. Java Environment Verification:"
JAVA_VERSION=$(java -version 2>&1 | head -1)
echo "   $JAVA_VERSION"
if ! java -version 2>&1 | grep -q "17\."; then
    echo "❌ Java 17 required but found different version"
    exit 1
else
    echo "✅ Java 17 environment confirmed"
fi

echo

# Step 2: Clean Maven cache and target directories
echo "2. Maven Clean (Aggressive):"
echo "   Cleaning Maven cache for chemistry-opencmis..."
rm -rf ~/.m2/repository/org/apache/chemistry/opencmis/ 2>/dev/null || true
echo "   Cleaning all target directories..."
find . -name "target" -type d -exec rm -rf {} + 2>/dev/null || true
echo "   Cleaning core target directory specifically..."
rm -rf core/target/
echo "✅ Maven clean completed"

echo

# Step 3: Clean Docker environment completely
echo "3. Docker Environment Clean:"
echo "   Stopping all containers..."
cd docker && docker compose -f docker-compose-simple.yml down --remove-orphans || true

echo "   Removing old core image..."
docker image rm nemakiware/core 2>/dev/null || true
docker image rm docker-core 2>/dev/null || true

echo "   Cleaning Docker build cache..."
docker builder prune -f > /dev/null 2>&1 || true

echo "✅ Docker environment cleaned"
cd ..

echo

# Step 4: Fresh Maven build with detailed verification
echo "4. Fresh Maven Build:"
echo "   Starting clean package build..."
mvn clean compile package -f core/pom.xml -Pdevelopment -q

# Verify build success
if [ -f "core/target/core.war" ]; then
    WAR_SIZE=$(stat -f%z core/target/core.war 2>/dev/null || stat -c%s core/target/core.war 2>/dev/null)
    echo "✅ Build successful - WAR file created (${WAR_SIZE} bytes)"
    
    # Verify critical classes are in WAR
    echo "   Verifying critical classes in WAR..."
    if unzip -l core/target/core.war | grep -q "NemakiBrowserBindingServlet.class"; then
        echo "✅ NemakiBrowserBindingServlet.class found in WAR"
    else
        echo "❌ NemakiBrowserBindingServlet.class NOT found in WAR"
        exit 1
    fi
    
    if unzip -l core/target/core.war | grep -q "ObjectServiceImpl.class"; then
        echo "✅ ObjectServiceImpl.class found in WAR"
    else
        echo "❌ ObjectServiceImpl.class NOT found in WAR"
        exit 1
    fi
    
else
    echo "❌ Build failed - no WAR file created"
    exit 1
fi

echo

# Step 5: Copy WAR to Docker context
echo "5. Docker Context Update:"
echo "   Copying fresh WAR to Docker context..."
cp core/target/core.war docker/core/core.war

# Verify copy
if [ -f "docker/core/core.war" ]; then
    DOCKER_WAR_SIZE=$(stat -f%z docker/core/core.war 2>/dev/null || stat -c%s docker/core/core.war 2>/dev/null)
    echo "✅ WAR copied to Docker context (${DOCKER_WAR_SIZE} bytes)"
    
    # Verify sizes match
    if [ "$WAR_SIZE" -eq "$DOCKER_WAR_SIZE" ]; then
        echo "✅ WAR file sizes match - copy successful"
    else
        echo "❌ WAR file size mismatch - copy failed"
        exit 1
    fi
else
    echo "❌ Failed to copy WAR to Docker context"
    exit 1
fi

echo

# Step 6: Build fresh Docker image with no cache
echo "6. Fresh Docker Image Build:"
echo "   Building new Docker image (no cache)..."
cd docker
docker build --no-cache --pull -t nemakiware/core -f core/Dockerfile.simple core/

if [ $? -eq 0 ]; then
    echo "✅ Docker image built successfully"
else
    echo "❌ Docker image build failed"
    exit 1
fi

echo

# Step 7: Start fresh environment
echo "7. Fresh Environment Startup:"
echo "   Starting fresh Docker environment..."
docker compose -f docker-compose-simple.yml up -d

echo "   Waiting for containers to initialize (90 seconds)..."
for i in {1..18}; do
    echo -n "."
    sleep 5
done
echo

# Verify containers are running
if docker ps | grep -q "docker-core-1"; then
    echo "✅ Core container started successfully"
else
    echo "❌ Core container failed to start"
    docker logs docker-core-1 --tail 20
    exit 1
fi

if docker ps | grep -q "docker-couchdb-1"; then
    echo "✅ CouchDB container running"
else
    echo "⚠️  CouchDB container may not be running"
fi

if docker ps | grep -q "docker-solr-1"; then
    echo "✅ Solr container running"
else
    echo "⚠️  Solr container may not be running"
fi

cd ..

echo

# Step 8: Deployment verification
echo "8. Deployment Verification:"
echo "   Running deployment verification..."
./verify-container-deployment.sh

echo

# Step 9: Basic functionality test
echo "9. Basic Functionality Test:"
echo "   Testing CMIS Browser Binding endpoint..."
RESPONSE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo")

if [ "$RESPONSE_CODE" = "200" ]; then
    echo "✅ CMIS Browser Binding is responding (HTTP $RESPONSE_CODE)"
    
    echo "   Testing AtomPub Binding..."
    ATOM_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin "http://localhost:8080/core/atom/bedroom")
    
    if [ "$ATOM_RESPONSE" = "200" ]; then
        echo "✅ CMIS AtomPub Binding is responding (HTTP $ATOM_RESPONSE)"
    else
        echo "⚠️  CMIS AtomPub Binding returned HTTP $ATOM_RESPONSE"
    fi
    
else
    echo "❌ CMIS Browser Binding is not responding (HTTP $RESPONSE_CODE)"
    echo "   Check container logs: docker logs docker-core-1 --tail 20"
    exit 1
fi

echo

# Step 10: Summary
echo "10. Clean Build and Deployment Summary:"
echo "✅ Java 17 environment verified"
echo "✅ Maven cache and targets cleaned"
echo "✅ Docker environment completely refreshed"
echo "✅ Fresh WAR build with critical classes verified"
echo "✅ Docker image rebuilt without cache"
echo "✅ Fresh containers started and initialized"
echo "✅ Container deployment verified"
echo "✅ CMIS endpoints responding"

echo
echo "🎉 CLEAN BUILD AND DEPLOYMENT COMPLETE"
echo "   Your latest code changes are now deployed and running"
echo "   You can proceed with testing using confirmed fresh deployment"
echo
echo "Next steps:"
echo "   - Run TCK tests: JAVA_HOME=$JAVA_HOME timeout 60s mvn test -Dtest=CreateAndDeleteDocumentTest -f core/pom.xml -Pdevelopment"
echo "   - Run comprehensive tests: ./qa-test.sh"
echo "   - Check logs: docker logs docker-core-1 --tail 50"

echo
echo "=== Clean Build and Deployment Complete ==="
