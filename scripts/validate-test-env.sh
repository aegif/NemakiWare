#!/bin/bash

set -e

echo "=========================================="
echo "Validating NemakiWare Test Environment"
echo "=========================================="
echo ""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

success() {
    echo -e "${GREEN}✓${NC} $1"
}

error() {
    echo -e "${RED}✗${NC} $1"
}

warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

echo "1. Checking Docker containers..."
if ! docker ps | grep -q docker-couchdb-1; then
    error "CouchDB container is not running"
    echo "   Run: cd docker && docker-compose -f docker-compose-simple.yml up -d couchdb"
    exit 1
fi
success "CouchDB container is running"

if ! docker ps | grep -q docker-solr-1; then
    error "Solr container is not running"
    echo "   Run: cd docker && docker-compose -f docker-compose-simple.yml up -d solr"
    exit 1
fi
success "Solr container is running"

if ! docker ps | grep -q docker-core-1; then
    error "Core container is not running"
    echo "   Run: cd docker && docker-compose -f docker-compose-simple.yml up -d core"
    exit 1
fi
success "Core container is running"

echo ""

echo "2. Checking CouchDB health..."
if curl -s http://localhost:5984/_up | grep -q "ok"; then
    success "CouchDB is healthy"
else
    error "CouchDB is not healthy"
    echo "   Check logs: docker logs docker-couchdb-1"
    exit 1
fi

echo ""

echo "3. Checking Solr health..."
if curl -s http://localhost:8983/solr/admin/cores?action=STATUS | grep -q "status"; then
    success "Solr is healthy"
else
    error "Solr is not healthy"
    echo "   Check logs: docker logs docker-solr-1"
    exit 1
fi

echo ""

echo "4. Checking Core CMIS API..."
if curl -s http://localhost:8080/core/browser/bedroom/root | grep -q "cmis:objectId"; then
    success "Core CMIS API is responding"
else
    error "Core CMIS API is not responding"
    echo "   Check logs: docker logs docker-core-1"
    exit 1
fi

echo ""

echo "5. Checking initial content setup (Sites folder)..."
SITES_CHECK=$(curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children' | jq -r '.objects[] | select(.object.properties["cmis:name"].value == "Sites") | .object.properties["cmis:objectId"].value' 2>/dev/null || echo "")

if [ -n "$SITES_CHECK" ]; then
    success "Sites folder exists (ID: $SITES_CHECK)"
else
    error "Sites folder not found in root folder"
    echo "   This indicates incomplete initial content setup"
    echo "   Solution: Run 'make reset-test-env' to perform a complete environment reset"
    exit 1
fi

echo ""

echo "6. Checking initial content setup (Technical Documents folder)..."
TECH_DOCS_CHECK=$(curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children' | jq -r '.objects[] | select(.object.properties["cmis:name"].value == "Technical Documents") | .object.properties["cmis:objectId"].value' 2>/dev/null || echo "")

if [ -n "$TECH_DOCS_CHECK" ]; then
    success "Technical Documents folder exists (ID: $TECH_DOCS_CHECK)"
else
    error "Technical Documents folder not found in root folder"
    echo "   This indicates incomplete initial content setup"
    echo "   Solution: Run 'make reset-test-env' to perform a complete environment reset"
    exit 1
fi

echo ""

echo "7. Checking Playwright environment variables..."
if [ -n "$PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS" ]; then
    success "PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS is set"
else
    warning "PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS is not set"
    echo "   WebKit and Mobile Safari tests may fail on Linux"
    echo "   Solution: export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1"
fi

echo ""
echo "=========================================="
echo -e "${GREEN}✓ Environment validation complete!${NC}"
echo "=========================================="
echo ""
echo "You can now run Playwright tests:"
echo "  cd core/src/main/webapp/ui"
echo "  PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1 npx playwright test"
echo ""
echo "Or use the Makefile:"
echo "  make test-e2e-quick    # Run tests without environment reset"
echo "  make test-e2e          # Reset environment and run tests"
echo ""
