#!/bin/bash
#
# TCK Test Execution with Database Cleanup
#
# This script performs a complete TCK test execution with proper database cleanup
# to ensure clean test conditions and prevent test data accumulation issues.
#
# WARNING: This script DROPS the configured CouchDB database. Run it ONLY
# against a local development / CI environment. Multiple safety guards must
# be cleared before any destructive action runs:
#
#   1. CouchDB endpoint must be on localhost / 127.0.0.1 (refuses remote URLs).
#   2. REPOSITORY_NAME must look like a test repository (override only via
#      explicit env override that includes "test" or "dev" in the name).
#   3. CONFIRM_DELETE_BEDROOM=yes must be set (or --confirm passed) to allow
#      the DELETE step. The default behaviour is REFUSE.
#
# Usage:
#   CONFIRM_DELETE_BEDROOM=yes ./tck-test-clean.sh                    # Run all TCK tests
#   CONFIRM_DELETE_BEDROOM=yes ./tck-test-clean.sh QueryTestGroup     # Run specific test group
#   CONFIRM_DELETE_BEDROOM=yes ./tck-test-clean.sh QueryTestGroup#queryLikeTest
#

set -e  # Exit on error

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration — overridable via env, defaults match the local dev compose stack.
COUCHDB_HOST="${COUCHDB_HOST:-localhost}"
COUCHDB_PORT="${COUCHDB_PORT:-5984}"
COUCHDB_USER="${COUCHDB_USER:-admin}"
COUCHDB_PASSWORD="${COUCHDB_PASSWORD:-password}"
REPOSITORY_NAME="${REPOSITORY_NAME:-bedroom}"
DOCKER_COMPOSE_FILE="${DOCKER_COMPOSE_FILE:-docker/docker-compose-simple.yml}"
CORE_CONTAINER="${CORE_CONTAINER:-docker-core-1}"
TEST_TARGET="${1:-}"  # Optional test target (e.g., QueryTestGroup)
if [ "$TEST_TARGET" = "--confirm" ]; then
    CONFIRM_DELETE_BEDROOM=yes
    TEST_TARGET=""
fi

# ── Safety gate ────────────────────────────────────────────────────────
case "$COUCHDB_HOST" in
    localhost|127.0.0.1|::1)
        ;;
    *)
        echo -e "${RED}REFUSING TO RUN: COUCHDB_HOST='$COUCHDB_HOST' is not a loopback address.${NC}"
        echo "This script DROPS the CouchDB database '$REPOSITORY_NAME'. It is only"
        echo "safe to use against a local development / CI environment. If you really"
        echo "want to clean a different host, do it by hand."
        exit 2
        ;;
esac

# Allow obvious test-pattern names; refuse anything that looks like a real repo.
case "$REPOSITORY_NAME" in
    bedroom|canopy|*test*|*dev*|*ci*)
        ;;
    *)
        echo -e "${RED}REFUSING TO RUN: REPOSITORY_NAME='$REPOSITORY_NAME' does not look like a test/dev repo.${NC}"
        echo "Set REPOSITORY_NAME to a name containing 'test' / 'dev' / 'ci', or to"
        echo "the canonical 'bedroom' / 'canopy' fixtures, before re-running."
        exit 2
        ;;
esac

if [ "${CONFIRM_DELETE_BEDROOM:-}" != "yes" ]; then
    echo -e "${RED}REFUSING TO RUN: CONFIRM_DELETE_BEDROOM=yes is required.${NC}"
    echo "This script will DROP the CouchDB database '$REPOSITORY_NAME' on $COUCHDB_HOST:$COUCHDB_PORT."
    echo "Re-run as:"
    echo "  CONFIRM_DELETE_BEDROOM=yes ./tck-test-clean.sh ${TEST_TARGET:-}"
    exit 2
fi

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

# Recreate the core container to reinitialise the database. CLAUDE.md
# explicitly forbids `docker compose restart` here because it leaves the
# previously-built WAR running; we always force a recreate so the latest
# WAR is what serves the test run.
echo "Recreating core container for database reinitialization..."
cd "$(dirname "$0")"
COUCHDB_USER="$COUCHDB_USER" COUCHDB_PASSWORD="$COUCHDB_PASSWORD" \
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d --force-recreate core > /dev/null 2>&1

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
if [ -d "${JAVA_HOME:-/path/to/java-21}" ]; then
    export JAVA_HOME=${JAVA_HOME:-/path/to/java-21}
elif [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
else
    echo -e "${RED}ERROR: Java 21 not found in expected locations${NC}"
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
