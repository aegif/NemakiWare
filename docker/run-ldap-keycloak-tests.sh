#!/bin/bash
# =============================================================================
# LDAP + Keycloak Integration Test Runner
# =============================================================================
#
# This script sets up the complete LDAP + Keycloak + NemakiWare test environment
# and runs the integration tests.
#
# Usage:
#   ./run-ldap-keycloak-tests.sh [options]
#
# Options:
#   --skip-build    Skip building NemakiWare WAR (use existing)
#   --keep-running  Keep containers running after tests
#   --only-setup    Only start containers, don't run tests
#   --help          Show this help message
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="docker-compose-ldap-keycloak-test.yml"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default options
SKIP_BUILD=false
KEEP_RUNNING=false
ONLY_SETUP=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --keep-running)
            KEEP_RUNNING=true
            shift
            ;;
        --only-setup)
            ONLY_SETUP=true
            KEEP_RUNNING=true
            shift
            ;;
        --help)
            head -25 "$0" | tail -20
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

echo -e "${BLUE}"
echo "============================================================================="
echo " LDAP + Keycloak Integration Test Environment"
echo "============================================================================="
echo -e "${NC}"

# Step 1: Build NemakiWare
if [ "$SKIP_BUILD" = false ]; then
    echo -e "${YELLOW}[1/6] Building NemakiWare...${NC}"

    # Build UI
    echo "  Building UI..."
    cd "$PROJECT_ROOT/core/src/main/webapp/ui"
    npm install --silent
    npm run build --silent

    # Build WAR
    echo "  Building WAR..."
    cd "$PROJECT_ROOT"
    JAVA_HOME="${JAVA_HOME:-/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home}"
    export JAVA_HOME
    mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

    # Copy WAR
    cp core/target/core.war docker/core/core.war
    echo -e "${GREEN}  ✓ Build complete${NC}"
else
    echo -e "${YELLOW}[1/6] Skipping build (--skip-build)${NC}"
fi

# Step 2: Stop existing containers
echo -e "${YELLOW}[2/6] Stopping existing containers...${NC}"
cd "$SCRIPT_DIR"
docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
echo -e "${GREEN}  ✓ Containers stopped${NC}"

# Step 3: Start test environment
echo -e "${YELLOW}[3/6] Starting test environment...${NC}"
docker compose -f "$COMPOSE_FILE" up -d --build --force-recreate
echo -e "${GREEN}  ✓ Containers started${NC}"

# Step 4: Wait for services to be ready
echo -e "${YELLOW}[4/6] Waiting for services...${NC}"

# Wait for OpenLDAP
echo "  Waiting for OpenLDAP..."
for i in $(seq 1 30); do
    if docker exec openldap ldapsearch -x -H ldap://localhost -b "dc=nemakiware,dc=example,dc=com" -D "cn=admin,dc=nemakiware,dc=example,dc=com" -w "adminpassword" "(objectClass=*)" >/dev/null 2>&1; then
        echo -e "${GREEN}  ✓ OpenLDAP ready${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}  ✗ OpenLDAP timeout${NC}"
        exit 1
    fi
    sleep 2
done

# Wait for Keycloak
echo "  Waiting for Keycloak..."
for i in $(seq 1 60); do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8088/realms/nemakiware/.well-known/openid-configuration | grep -q "200"; then
        echo -e "${GREEN}  ✓ Keycloak ready${NC}"
        break
    fi
    if [ $i -eq 60 ]; then
        echo -e "${RED}  ✗ Keycloak timeout${NC}"
        exit 1
    fi
    sleep 3
done

# Wait for NemakiWare
echo "  Waiting for NemakiWare..."
for i in $(seq 1 60); do
    if curl -s -o /dev/null -w "%{http_code}" -u admin:admin http://localhost:8080/core/atom/bedroom | grep -q "200"; then
        echo -e "${GREEN}  ✓ NemakiWare ready${NC}"
        break
    fi
    if [ $i -eq 60 ]; then
        echo -e "${RED}  ✗ NemakiWare timeout${NC}"
        exit 1
    fi
    sleep 3
done

# Step 5: Create test users in NemakiWare
echo -e "${YELLOW}[5/6] Creating test users in NemakiWare...${NC}"

# Create LDAP users
for user_data in "ldapuser1:LDAP%20User%20One:ldappass1:LDAP%20User:One:ldapuser1@nemakiware.example.com" \
                 "ldapuser2:LDAP%20User%20Two:ldappass2:LDAP%20User:Two:ldapuser2@nemakiware.example.com" \
                 "ldapadmin:LDAP%20Administrator:ldapadminpass:LDAP:Administrator:ldapadmin@nemakiware.example.com"; do
    IFS=':' read -r userid name password firstname lastname email <<< "$user_data"

    result=$(curl -s -u admin:admin -X POST \
        -d "name=$name&password=$password&firstName=$firstname&lastName=$lastname&email=$email" \
        "http://localhost:8080/core/rest/repo/bedroom/user/create/$userid")

    if echo "$result" | grep -q '"status":"success"'; then
        echo -e "  ${GREEN}✓${NC} Created user: $userid"
    else
        echo -e "  ${YELLOW}⚠${NC} User may already exist: $userid"
    fi
done

echo -e "${GREEN}  ✓ Test users ready${NC}"

if [ "$ONLY_SETUP" = true ]; then
    echo -e "${BLUE}"
    echo "============================================================================="
    echo " Test Environment Ready"
    echo "============================================================================="
    echo -e "${NC}"
    echo "Services:"
    echo "  - OpenLDAP:    ldap://localhost:389"
    echo "  - Keycloak:    http://localhost:8088"
    echo "  - NemakiWare:  http://localhost:8080"
    echo ""
    echo "Test Users (LDAP & Keycloak):"
    echo "  - ldapuser1 / ldappass1"
    echo "  - ldapuser2 / ldappass2"
    echo "  - ldapadmin / ldapadminpass"
    echo ""
    echo "Keycloak Admin:"
    echo "  - admin / admin"
    echo ""
    echo "To run tests manually:"
    echo "  cd $PROJECT_ROOT/core/src/main/webapp/ui"
    echo "  npx playwright test tests/auth/ldap-oidc-integration.spec.ts"
    echo ""
    echo "To stop the environment:"
    echo "  cd $SCRIPT_DIR"
    echo "  docker compose -f $COMPOSE_FILE down -v"
    exit 0
fi

# Step 6: Run integration tests
echo -e "${YELLOW}[6/6] Running integration tests...${NC}"
cd "$PROJECT_ROOT/core/src/main/webapp/ui"

# Install playwright browsers if needed
npx playwright install chromium --with-deps 2>/dev/null || true

# Run tests
echo ""
npx playwright test tests/auth/ldap-oidc-integration.spec.ts --project=chromium
TEST_RESULT=$?

# Cleanup
if [ "$KEEP_RUNNING" = false ]; then
    echo ""
    echo -e "${YELLOW}Cleaning up...${NC}"
    cd "$SCRIPT_DIR"
    docker compose -f "$COMPOSE_FILE" down -v
    echo -e "${GREEN}✓ Cleanup complete${NC}"
fi

echo -e "${BLUE}"
echo "============================================================================="
if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN} All tests passed!${NC}"
else
    echo -e "${RED} Some tests failed (exit code: $TEST_RESULT)${NC}"
fi
echo "============================================================================="
echo -e "${NC}"

exit $TEST_RESULT
