#!/bin/bash
# Test minimal initialization with CMIS API folder/document creation

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "==========================================="
echo "Testing Minimal Initialization + CMIS API"
echo "==========================================="

# Stop any existing containers
echo "Stopping existing containers..."
docker compose -f docker-compose-tomcat10.yml down 2>/dev/null || true

# Start with minimal initialization
echo "Starting services with minimal initialization..."
docker compose -f docker-compose-tomcat10.yml up -d

# Wait for services
echo "Waiting for services to start..."
sleep 30

# Wait for CouchDB
echo "Waiting for CouchDB..."
timeout=60
while [ $timeout -gt 0 ]; do
    if curl -s -f http://localhost:5984/_all_dbs > /dev/null; then
        echo "✓ CouchDB is ready"
        break
    fi
    echo "Waiting for CouchDB... ($timeout seconds remaining)"
    sleep 5
    timeout=$((timeout - 5))
done

# Wait for Core application
echo "Waiting for Core application..."
timeout=120
while [ $timeout -gt 0 ]; do
    status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core)
    if [ "$status" = "200" ] || [ "$status" = "302" ]; then
        echo "✓ Core application is ready (HTTP $status)"
        break
    fi
    echo "Waiting for Core application... ($timeout seconds remaining)"
    sleep 10
    timeout=$((timeout - 10))
done

# Check for changesByToken view
echo ""
echo "Checking for changesByToken view..."
if curl -s -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/changesByToken" | grep -q "rows"; then
    echo "✓ changesByToken view is working"
else
    echo "✗ changesByToken view missing or not working"
fi

# Test CMIS endpoints
echo ""
echo "Testing CMIS endpoints..."
CMIS_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom)
echo "CMIS AtomPub status: $CMIS_STATUS"

# Run CMIS initial setup script
echo ""
echo "Running CMIS initial setup..."
if [ -f "$SCRIPT_DIR/cmis-initial-setup.sh" ]; then
    "$SCRIPT_DIR/cmis-initial-setup.sh"
else
    echo "WARNING: cmis-initial-setup.sh not found, skipping CMIS folder creation"
fi

# Verify setup
echo ""
echo "Verifying setup..."
echo "Database status:"
curl -s -u admin:password http://localhost:5984/_all_dbs | jq . || curl -s -u admin:password http://localhost:5984/_all_dbs

echo ""
echo "Checking bedroom repository documents:"
BEDROOM_DOCS=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=false" | jq '.total_rows // 0' 2>/dev/null || echo "unknown")
echo "Total documents in bedroom: $BEDROOM_DOCS"

echo ""
echo "Testing completed!"
echo "Access UI at: http://localhost:9000/ui/repo/bedroom/"