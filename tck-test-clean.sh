#!/bin/bash
#
# TCK Test Execution with Database Cleanup
#
# This script performs a complete TCK test execution with proper database cleanup
# to ensure clean test conditions and prevent test data accumulation issues.
#
# For detailed documentation, see:
#   - README.md: "Testing" section
#   - CLAUDE.md: "TCK Test Execution (Standard Procedure)" section
#
# Usage:
#   ./tck-test-clean.sh [test-group]
#
# Examples:
#   ./tck-test-clean.sh                    # Run all TCK tests
#   ./tck-test-clean.sh QueryTestGroup     # Run specific test group
#   ./tck-test-clean.sh QueryTestGroup#queryLikeTest  # Run specific test method
#

set -e  # Exit on error

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COUCHDB_HOST="localhost"
COUCHDB_PORT="5984"
COUCHDB_USER="admin"
COUCHDB_PASSWORD="password"
REPOSITORY_NAME="bedroom"
DOCKER_COMPOSE_FILE="docker/docker-compose-simple.yml"
CORE_CONTAINER="docker-core-1"
TEST_TARGET="${1:-}"  # Optional test target (e.g., QueryTestGroup)

echo -e "${BLUE}================================================${NC}"
echo -e "${BLUE}  TCK Test Execution with Database Cleanup${NC}"
echo -e "${BLUE}================================================${NC}"
echo ""

# Step 1: Check Docker containers status
echo -e "${YELLOW}[Step 1/5] Checking Docker containers...${NC}"
if ! docker ps | grep -q "$CORE_CONTAINER"; then
    echo -e "${RED}ERROR: Core container is not running${NC}"
    echo "Please start containers with: cd docker && docker compose -f docker-compose-simple.yml up -d"
    exit 1
fi
echo -e "${GREEN}✓ Docker containers are running${NC}"
echo ""

# Step 2: Check initial database status
echo -e "${YELLOW}[Step 2/5] Checking initial database status...${NC}"
INITIAL_DOC_COUNT=$(curl -s -u "$COUCHDB_USER:$COUCHDB_PASSWORD" \
    "http://$COUCHDB_HOST:$COUCHDB_PORT/$REPOSITORY_NAME" | jq -r '.doc_count')
echo "Current document count: $INITIAL_DOC_COUNT"

if [ "$INITIAL_DOC_COUNT" -gt 500 ]; then
    echo -e "${YELLOW}⚠ Large number of documents detected (${INITIAL_DOC_COUNT})${NC}"
    echo -e "${YELLOW}  This may cause QuerySmokeTest failures due to old test data${NC}"
fi
echo ""

# Step 3: Database cleanup
echo -e "${YELLOW}[Step 3/5] Cleaning up database...${NC}"
echo "Deleting $REPOSITORY_NAME database..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
    -u "$COUCHDB_USER:$COUCHDB_PASSWORD" \
    "http://$COUCHDB_HOST:$COUCHDB_PORT/$REPOSITORY_NAME")

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "404" ]; then
    echo -e "${GREEN}✓ Database deleted (HTTP $HTTP_CODE)${NC}"
else
    echo -e "${RED}ERROR: Failed to delete database (HTTP $HTTP_CODE)${NC}"
    exit 1
fi

# Restart core container to reinitialize database
echo "Restarting core container for database reinitialization..."
cd "$(dirname "$0")"
docker compose -f "$DOCKER_COMPOSE_FILE" restart core > /dev/null 2>&1

# Wait for server to be ready
echo "Waiting for server initialization (90 seconds)..."
sleep 90

# Verify server is ready
if curl -s -o /dev/null -w "%{http_code}" -u admin:admin \
    "http://localhost:8080/core/atom/$REPOSITORY_NAME" | grep -q "200"; then
    echo -e "${GREEN}✓ Server is ready${NC}"
else
    echo -e "${RED}ERROR: Server failed to start${NC}"
    exit 1
fi

# Check cleaned database
CLEAN_DOC_COUNT=$(curl -s -u "$COUCHDB_USER:$COUCHDB_PASSWORD" \
    "http://$COUCHDB_HOST:$COUCHDB_PORT/$REPOSITORY_NAME" | jq -r '.doc_count')
echo "Clean database document count: $CLEAN_DOC_COUNT"
echo -e "${GREEN}✓ Database cleanup completed${NC}"
echo ""

# Step 4: Java environment setup
echo -e "${YELLOW}[Step 4/5] Setting up Java environment...${NC}"
# Detect OS and set JAVA_HOME accordingly
if [ -d "/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home" ]; then
    export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
elif [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
else
    echo -e "${RED}ERROR: Java 17 not found in expected locations${NC}"
    exit 1
fi
export PATH=$JAVA_HOME/bin:$PATH

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
echo "Java version: $JAVA_VERSION"
echo -e "${GREEN}✓ Java environment configured${NC}"
echo ""

# Step 5: Execute TCK tests
echo -e "${YELLOW}[Step 5/5] Executing TCK tests...${NC}"

if [ -n "$TEST_TARGET" ]; then
    echo "Test target: $TEST_TARGET"
    TEST_OPTION="-Dtest=$TEST_TARGET"
else
    echo "Running all TCK tests"
    TEST_OPTION=""
fi

echo ""
echo -e "${BLUE}Starting Maven test execution...${NC}"
echo -e "${BLUE}(This may take 5-40 minutes depending on test scope)${NC}"
echo ""

# Execute tests with appropriate timeout
TIMEOUT_SECONDS=5400  # 90 minutes
START_TIME=$(date +%s)

timeout ${TIMEOUT_SECONDS}s mvn test $TEST_OPTION -f core/pom.xml -Pdevelopment

TEST_EXIT_CODE=$?
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
DURATION_MIN=$((DURATION / 60))
DURATION_SEC=$((DURATION % 60))

echo ""
echo -e "${BLUE}================================================${NC}"
echo -e "${BLUE}  TCK Test Execution Summary${NC}"
echo -e "${BLUE}================================================${NC}"
echo ""
echo "Execution time: ${DURATION_MIN}m ${DURATION_SEC}s"
echo "Initial database docs: $INITIAL_DOC_COUNT"
echo "Clean database docs: $CLEAN_DOC_COUNT"

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ All tests PASSED${NC}"
    echo ""
    echo "Test reports available at:"
    echo "  - core/target/surefire-reports/"
    exit 0
elif [ $TEST_EXIT_CODE -eq 124 ]; then
    echo -e "${RED}✗ Tests TIMEOUT after ${TIMEOUT_SECONDS}s${NC}"
    exit 1
else
    echo -e "${RED}✗ Tests FAILED${NC}"
    echo ""
    echo "Check test reports for details:"
    echo "  - core/target/surefire-reports/"
    exit 1
fi
