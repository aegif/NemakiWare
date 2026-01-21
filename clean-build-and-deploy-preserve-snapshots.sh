#!/bin/bash

# Clean Build and Deploy Script for NemakiWare (Preserves 1.2.0-SNAPSHOT)
# This script performs a complete clean build while preserving local SNAPSHOT dependencies

echo "=== NemakiWare Clean Build and Deployment (Preserving SNAPSHOTs) ==="
echo "Timestamp: $(date)"
echo

# Configuration
export JAVA_HOME=${JAVA_HOME:-/path/to/java-17}
export PATH=$JAVA_HOME/bin:$PATH

# Step 1: Verify Java 17 environment
echo "1. Java Environment Verification:"
java -version 2>&1 | head -1

if ! java -version 2>&1 | grep -q "17\."; then
    echo "❌ Java 17 required but found different version"
    exit 1
else
    echo "✅ Java 17 environment confirmed"
fi

echo

# Step 2: Clean Maven cache selectively (preserve 1.2.0-SNAPSHOT)
echo "2. Maven Clean (Selective):"
echo "   Preserving 1.2.0-SNAPSHOT artifacts..."
# Remove only non-SNAPSHOT OpenCMIS artifacts
find ~/.m2/repository/org/apache/chemistry/opencmis -type d -name "1.1.0" -exec rm -rf {} + 2>/dev/null || true
find ~/.m2/repository/org/apache/chemistry/opencmis -type d -name "1.0.0" -exec rm -rf {} + 2>/dev/null || true
echo "   Cleaning all target directories..."
find . -name "target" -type d -exec rm -rf {} + 2>/dev/null || true
echo "   Cleaning core target directory specifically..."
rm -rf core/target/
echo "✅ Maven clean completed (1.2.0-SNAPSHOT preserved)"

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

mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

if [ $? -eq 0 ]; then
    echo "✅ Maven build succeeded"
    
    # Verify WAR file was created
    if [ -f "core/target/core.war" ]; then
        echo "✅ WAR file created: core/target/core.war"
        WAR_SIZE=$(ls -lh core/target/core.war | awk '{print $5}')
        echo "   WAR size: $WAR_SIZE"
    else
        echo "❌ WAR file not found!"
        exit 1
    fi
else
    echo "❌ Maven build failed"
    exit 1
fi

echo

# Step 5: Copy WAR to Docker context
echo "5. WAR Deployment Preparation:"
cp core/target/core.war docker/core/core.war
if [ $? -eq 0 ]; then
    echo "✅ WAR file copied to Docker context"
else
    echo "❌ Failed to copy WAR file"
    exit 1
fi

echo

# Step 6: Build Docker image with no cache
echo "6. Docker Image Build:"
cd docker
echo "   Building fresh Docker image (no cache)..."
docker build --no-cache -t nemakiware/core -f core/Dockerfile.simple core/

if [ $? -eq 0 ]; then
    echo "✅ Docker image built successfully"
else
    echo "❌ Docker image build failed"
    exit 1
fi

echo

# Step 7: Start containers
echo "7. Container Deployment:"
echo "   Starting containers..."
docker compose -f docker-compose-simple.yml up -d

if [ $? -eq 0 ]; then
    echo "✅ Containers started"
else
    echo "❌ Container startup failed"
    exit 1
fi

echo

# Step 8: Wait for deployment
echo "8. Deployment Initialization:"
echo "   Waiting for services to initialize (60 seconds)..."
sleep 60

echo

# Step 9: Verify deployment
echo "9. Deployment Verification:"
echo "   Checking container status..."
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "(core|couchdb|solr)"

echo
echo "   Testing CMIS endpoint..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin http://localhost:8080/core/atom/bedroom)

if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ CMIS endpoint responding: HTTP $HTTP_CODE"
else
    echo "❌ CMIS endpoint not responding: HTTP $HTTP_CODE"
    echo "   Checking container logs..."
    docker logs docker-core-1 --tail 20
    exit 1
fi

echo

# Step 10: Verify OpenCMIS JARs in WAR
echo "10. OpenCMIS JAR Verification:"
echo "   Checking for 1.2.0-SNAPSHOT JARs in WAR..."
JAR_COUNT=$(unzip -l docker/core/core.war | grep -c "chemistry-opencmis.*1\.2\.0-SNAPSHOT\.jar")
echo "   Found $JAR_COUNT OpenCMIS 1.2.0-SNAPSHOT JARs"

if [ $JAR_COUNT -gt 0 ]; then
    echo "✅ OpenCMIS 1.2.0-SNAPSHOT JARs present in WAR"
    unzip -l docker/core/core.war | grep "chemistry-opencmis.*1\.2\.0-SNAPSHOT\.jar"
else
    echo "⚠️  No OpenCMIS 1.2.0-SNAPSHOT JARs found in WAR"
    echo "   WAR may be using Maven dependencies or Antrun copy may have failed"
fi

echo
echo "============================================"
echo "  CLEAN BUILD AND DEPLOYMENT COMPLETE"
echo "============================================"
echo
echo "✅ System ready for testing"
echo "   - Docker containers: Running"
echo "   - CMIS endpoint: Responding"
echo "   - Build artifacts: Deployed"
echo
echo "Next steps:"
echo "  1. Run verify-container-deployment.sh to check code version"
echo "  2. Run staged testing pipeline"
echo "  3. Execute TCK tests"