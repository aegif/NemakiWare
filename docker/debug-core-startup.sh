#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "========================================"
echo "Core Container Startup Debug"
echo "========================================"

# Check if containers are running
echo "1. Checking container status..."
docker compose -f docker-compose-simple.yml ps

echo ""
echo "2. Checking Core container logs (last 50 lines)..."
echo "========================================"
docker compose -f docker-compose-simple.yml logs --tail=50 core

echo ""
echo "3. Checking for specific Core startup errors..."
echo "========================================"
echo "Spring initialization errors:"
docker compose -f docker-compose-simple.yml logs core | grep -i "error\|exception\|failed" | tail -10

echo ""
echo "4. Checking Core container file system..."
echo "========================================"
echo "Checking if core.war exists in container:"
docker compose -f docker-compose-simple.yml exec core ls -la /usr/local/tomcat/webapps/ 2>/dev/null || echo "Core container not accessible"

echo ""
echo "5. Checking Core container environment..."
echo "========================================"
docker compose -f docker-compose-simple.yml exec core env | grep -E "(COUCHDB|JAVA)" 2>/dev/null || echo "Core container not accessible"

echo ""
echo "6. Testing Core endpoints..."
echo "========================================"
echo "Testing root endpoint:"
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:8080/ || echo "Connection failed"
echo ""

echo "Testing /core endpoint:"
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:8080/core || echo "Connection failed"  
echo ""

echo "Testing CMIS endpoint with auth:"
curl -s -u admin:admin -o /dev/null -w "HTTP %{http_code}" http://localhost:8080/core/atom/bedroom || echo "Connection failed"
echo ""

echo ""
echo "7. Checking CouchDB connectivity from Core container..."
echo "========================================"
docker compose -f docker-compose-simple.yml exec core curl -s -u admin:password http://couchdb:5984/_all_dbs 2>/dev/null || echo "CouchDB connection failed from Core container"

echo ""
echo "========================================"
echo "Debug information collection complete"
echo "========================================"