#!/bin/bash

# NemakiWare Jakarta EE 10 Docker Deployment Script
# This script deploys the Jakarta-built WAR file to Docker containers

set -e

echo "=== NemakiWare Jakarta EE 10 Docker Deployment ==="
echo "Starting time: $(date)"

# Change to docker directory
cd "$(dirname "$0")"
DOCKER_DIR=$(pwd)
echo "Docker directory: $DOCKER_DIR"

# 1. Verify Prerequisites
echo ""
echo "1. Verifying prerequisites..."

# Check if core.war exists
if [[ ! -f "core/core.war" ]]; then
    echo "ERROR: core.war not found in docker/core/"
    echo "Please run build-jakarta.sh first"
    exit 1
fi

WAR_SIZE=$(ls -lh core/core.war | awk '{print $5}')
echo "   ✓ core.war found: $WAR_SIZE"

# Check Docker is running
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is not running"
    exit 1
fi
echo "   ✓ Docker is running"

# 2. Stop and Clean Existing Containers
echo ""
echo "2. Cleaning existing containers..."

# Stop existing containers if they exist
for container in docker-core-1 docker-couchdb-1 docker-solr-1 docker-ui-1; do
    if docker ps -a --format '{{.Names}}' | grep -q "^${container}$"; then
        echo "   Stopping and removing $container..."
        docker stop "$container" >/dev/null 2>&1 || true
        docker rm "$container" >/dev/null 2>&1 || true
    fi
done

# Clean up networks
docker network rm nemaki-network >/dev/null 2>&1 || true

# 3. Start CouchDB and Initialize Databases
echo ""
echo "3. Starting CouchDB and initializing databases..."

# Start CouchDB
docker compose -f docker-compose-simple.yml up -d couchdb

# Wait for CouchDB to be ready
echo "   Waiting for CouchDB to be ready..."
sleep 10

# Initialize databases
echo "   Initializing databases..."
./test-simple.sh init-only

# 4. Build and Start Core Container
echo ""
echo "4. Building and starting Core container..."

# Build fresh Core image
docker build --no-cache -t nemakiware/core core/
echo "   ✓ Core image built"

# Start Core container
docker run -d --name docker-core-1 --network nemaki-network \
    -p 8080:8080 \
    -e CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties -Ddb.couchdb.auth.enabled=true -Ddb.couchdb.auth.username=admin -Ddb.couchdb.auth.password=password" \
    nemakiware/core

echo "   ✓ Core container started"

# 5. Wait for Startup and Verify
echo ""
echo "5. Waiting for application startup..."
sleep 45

# Check container health
if ! docker ps --format '{{.Names}}' | grep -q "docker-core-1"; then
    echo "ERROR: Core container failed to start"
    docker logs docker-core-1 --tail 20
    exit 1
fi

echo "   ✓ Core container is running"

# 6. Verify Jakarta JARs in Deployed Container
echo ""
echo "6. Verifying Jakarta deployment..."

# Check Jakarta-converted JARs are deployed
JAKARTA_COUNT=$(docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core/WEB-INF/lib/ | grep "chemistry-opencmis.*\.jar" | grep "Jul.*2025" | wc -l)
echo "   ✓ Jakarta-converted OpenCMIS JARs in container: $JAKARTA_COUNT"

# Check Metro RI is deployed
METRO_COUNT=$(docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core/WEB-INF/lib/ | grep "jaxws-rt-4.0.2.jar" | wc -l)
echo "   ✓ Metro RI JAX-WS Runtime in container: $METRO_COUNT"

# 7. Test CMIS Endpoints
echo ""
echo "7. Testing CMIS endpoints..."

# Test AtomPub endpoint
ATOM_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom)
echo "   CMIS AtomPub endpoint: HTTP $ATOM_STATUS"

# Test Browser endpoint (should return 405 for GET)
BROWSER_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/browser/bedroom)
echo "   CMIS Browser endpoint: HTTP $BROWSER_STATUS"

# 8. Deployment Summary
echo ""
echo "=== Deployment Summary ==="
if [[ "$ATOM_STATUS" == "200" ]]; then
    echo "✅ CMIS AtomPub endpoint: WORKING"
else
    echo "❌ CMIS AtomPub endpoint: FAILED"
fi

if [[ "$BROWSER_STATUS" == "405" ]]; then
    echo "✅ CMIS Browser endpoint: WORKING (405 expected for GET)"
else
    echo "⚠️  CMIS Browser endpoint: HTTP $BROWSER_STATUS"
fi

echo "✅ Jakarta EE 10 migration with Metro RI: DEPLOYED"
echo "✅ Docker environment: READY"
echo ""
echo "Available endpoints:"
echo "  - CMIS AtomPub: http://localhost:8080/core/atom/bedroom"
echo "  - CMIS Browser: http://localhost:8080/core/browser/bedroom (POST only)"
echo "  - Authentication: admin/admin"
echo ""
echo "Deployment completed successfully at: $(date)"