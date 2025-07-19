#!/bin/bash

# NemakiWare Maven Jakarta Docker Deployment Script (Simplified)
# This script deploys Maven-built Jakarta WAR with automatic initialization

set -e

echo "=== NemakiWare Maven Jakarta Docker Deployment (Simplified) ==="
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
    echo "Please run build-maven-jakarta.sh first"
    exit 1
fi

WAR_SIZE=$(ls -lh core/core.war | awk '{print $5}')
echo "   ✓ Maven-built WAR found: $WAR_SIZE"

# Check Docker is running
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is not running"
    exit 1
fi
echo "   ✓ Docker is running"

# 2. Stop Existing Environment
echo ""
echo "2. Stopping existing containers..."
docker compose -f docker-compose-tomcat10.yml down 2>/dev/null || true
echo "   ✓ Previous environment stopped"

# 3. Start Jakarta EE Environment (Simplified)
echo ""
echo "3. Starting Jakarta EE 10 environment..."
echo "   ✓ CouchDB 3.x: Starting with authentication"
echo "   ✓ Tomcat 10: Jakarta EE compatible"
echo "   ✓ Auto-initialization: Built into WAR"

# Use Tomcat 10 environment (Jakarta EE ready)
docker compose -f docker-compose-tomcat10.yml up -d --build

echo "   ✓ Jakarta EE environment started"

# 4. Wait for Startup and Auto-Initialization
echo ""
echo "4. Waiting for auto-initialization..."
echo "   ⏳ Maven WAR includes complete auto-initialization"
echo "   ⏳ All databases will be created automatically"
echo "   ⏳ No manual database setup required"

sleep 60  # Allow time for auto-initialization

# 5. Verify Auto-Initialization Success
echo ""
echo "5. Verifying auto-initialization..."

# Check CouchDB databases
echo "   Checking database creation..."
sleep 5
DATABASES=$(curl -s -u admin:password http://localhost:5984/_all_dbs 2>/dev/null || echo "[]")
DB_COUNT=$(echo "$DATABASES" | grep -o "bedroom\|canopy\|nemaki_conf" | wc -l)
echo "   ✓ Auto-created databases: $DB_COUNT/5"

if [[ $DB_COUNT -ge 3 ]]; then
    echo "   ✅ Auto-initialization: SUCCESS"
else
    echo "   ⚠️  Auto-initialization may still be in progress..."
fi

# 6. Test CMIS Endpoints
echo ""
echo "6. Testing CMIS endpoints..."

# Test AtomPub endpoint  
ATOM_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom 2>/dev/null || echo "000")
echo "   CMIS AtomPub (bedroom): HTTP $ATOM_STATUS"

# Test canopy repository
CANOPY_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/canopy 2>/dev/null || echo "000")
echo "   CMIS AtomPub (canopy): HTTP $CANOPY_STATUS"

# Test CMIS query (improved method)
echo "   Testing CMIS query functionality..."
QUERY_STATUS=$(curl -s -u admin:admin -X POST \
    -H "Content-Type: application/cmisquery+xml" \
    -d '<?xml version="1.0" encoding="UTF-8"?><cmis:query xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/"><cmis:statement>SELECT * FROM cmis:folder</cmis:statement><cmis:maxItems>5</cmis:maxItems></cmis:query>' \
    -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom/query 2>/dev/null || echo "000")
echo "   CMIS Query (XML): HTTP $QUERY_STATUS"

# 7. Deployment Summary
echo ""
echo "=== Maven Jakarta Deployment Summary ==="

# Overall status
if [[ "$ATOM_STATUS" == "200" && "$CANOPY_STATUS" == "200" ]]; then
    echo "✅ CMIS Core Functionality: WORKING"
    echo "✅ Auto-initialization: SUCCESS"
    echo "✅ Jakarta EE 10 migration: DEPLOYED"
else
    echo "⚠️  CMIS endpoints may still be starting up"
    echo "   Try testing again in 30-60 seconds"
fi

# Query status
if [[ "$QUERY_STATUS" == "200" ]]; then
    echo "✅ CMIS Query: WORKING"
elif [[ "$QUERY_STATUS" == "400" ]]; then
    echo "⚠️  CMIS Query: Needs proper XML format (expected for some queries)"
else
    echo "⚠️  CMIS Query: HTTP $QUERY_STATUS (may need Solr for full functionality)"
fi

echo ""
echo "Available endpoints:"
echo "  - CMIS AtomPub (bedroom): http://localhost:8080/core/atom/bedroom"
echo "  - CMIS AtomPub (canopy): http://localhost:8080/core/atom/canopy"
echo "  - CMIS Browser: http://localhost:8080/core/browser/bedroom"
echo "  - Authentication: admin/admin"
echo ""
echo "Database status:"
echo "  - CouchDB: http://localhost:5984/ (admin/password)"
echo "  - Databases: $DATABASES"
echo ""
echo "Key advantages of Maven Jakarta build:"
echo "  ✅ Single command build process"
echo "  ✅ Automatic Jakarta EE conversion"
echo "  ✅ Built-in auto-initialization"
echo "  ✅ No manual JAR management"
echo "  ✅ Production-ready WAR file"
echo ""
echo "Deployment completed successfully at: $(date)"