#!/bin/bash
# REST API Complete Test Suite
# Tests all user and group management operations

BASE_URL="http://localhost:8080/core/rest/repo/bedroom"
AUTH="-u admin:admin"
CONTENT_TYPE="-H Content-Type: application/x-www-form-urlencoded"

echo "=== NemakiWare REST API Complete Test Suite ==="
echo "Base URL: $BASE_URL"
echo

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test results
TOTAL_TESTS=0
PASSED_TESTS=0

# Helper function to test HTTP response
test_endpoint() {
    local name="$1"
    local method="$2" 
    local url="$3"
    local data="$4"
    local expected_codes="$5"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "Testing $name: "
    
    if [ "$method" = "GET" ]; then
        response=$(curl -s -w "%{http_code}" $AUTH "$url")
        http_code="${response: -3}"
        body="${response%???}"
    elif [ "$method" = "POST" ]; then
        response=$(curl -s -w "%{http_code}" -X POST $AUTH $CONTENT_TYPE -d "$data" "$url")
        http_code="${response: -3}"
        body="${response%???}"
    elif [ "$method" = "PUT" ]; then
        response=$(curl -s -w "%{http_code}" -X PUT $AUTH $CONTENT_TYPE -d "$data" "$url")
        http_code="${response: -3}"
        body="${response%???}"
    elif [ "$method" = "DELETE" ]; then
        response=$(curl -s -w "%{http_code}" -X DELETE $AUTH "$url")
        http_code="${response: -3}"
        body="${response%???}"
    fi
    
    if [[ "$expected_codes" =~ $http_code ]]; then
        echo -e "${GREEN}✓ PASS${NC} (HTTP $http_code)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        if [ ! -z "$body" ]; then
            echo "    Response: ${body:0:100}..."
        fi
    else
        echo -e "${RED}✗ FAIL${NC} (HTTP $http_code, expected: $expected_codes)"
        if [ ! -z "$body" ]; then
            echo -e "    ${YELLOW}Response: ${body:0:200}...${NC}"
        fi
    fi
    echo
}

echo "=== User Management Tests ==="

# Test 1: List users
test_endpoint "User List" "GET" "$BASE_URL/user/list" "" "200"

# Test 2: Create user with all fields
test_endpoint "Create User (testapi)" "POST" "$BASE_URL/user/create/testapi" \
    "name=Test API User&firstName=Test&lastName=API&email=testapi@example.com&password=testpass123" \
    "200"

# Test 3: Show specific user
test_endpoint "Show User (testapi)" "GET" "$BASE_URL/user/show/testapi" "" "200"

# Test 4: Update user
test_endpoint "Update User (testapi)" "PUT" "$BASE_URL/user/update/testapi" \
    "name=Updated API User&firstName=Updated&lastName=Test&email=updated@example.com" \
    "200"

# Test 5: Create another user for group testing
test_endpoint "Create User (testapi2)" "POST" "$BASE_URL/user/create/testapi2" \
    "name=Test API User 2&firstName=Test2&lastName=API2&email=testapi2@example.com&password=testpass456" \
    "200"

echo "=== Group Management Tests ==="

# Test 6: List groups
test_endpoint "Group List" "GET" "$BASE_URL/group/list" "" "200"

# Test 7: Create group
test_endpoint "Create Group (testapgroup)" "POST" "$BASE_URL/group/create/testapgroup" \
    "name=Test API Group&users=[\"testapi\"]&groups=[]" \
    "200"

# Test 8: Show specific group
test_endpoint "Show Group (testapgroup)" "GET" "$BASE_URL/group/show/testapgroup" "" "200"

# Test 9: Update group
test_endpoint "Update Group (testapgroup)" "PUT" "$BASE_URL/group/update/testapgroup" \
    "name=Updated API Group&users=[\"testapi\",\"testapi2\"]&groups=[]" \
    "200"

echo "=== Error Handling Tests ==="

# Test 10: Create duplicate user (should fail)
test_endpoint "Create Duplicate User" "POST" "$BASE_URL/user/create/testapi" \
    "name=Duplicate User&firstName=Dup&lastName=User&email=dup@example.com&password=duppass" \
    "400 500"

# Test 11: Show non-existent user
test_endpoint "Show Non-existent User" "GET" "$BASE_URL/user/show/nonexistent" "" "404 500"

# Test 12: Show non-existent group  
test_endpoint "Show Non-existent Group" "GET" "$BASE_URL/group/show/nonexistent" "" "404 500"

echo "=== Cleanup Tests ==="

# Test 13: Delete group
test_endpoint "Delete Group (testapgroup)" "DELETE" "$BASE_URL/group/delete/testapgroup" "" "200 204"

# Test 14: Delete users
test_endpoint "Delete User (testapi)" "DELETE" "$BASE_URL/user/delete/testapi" "" "200 204"

# Test 15: Delete second user
test_endpoint "Delete User (testapi2)" "DELETE" "$BASE_URL/user/delete/testapi2" "" "200 204"

echo "=== Test Results ==="
echo "Total Tests: $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $((TOTAL_TESTS - PASSED_TESTS))"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
    echo -e "${GREEN}🎉 All tests passed! REST API is fully functional.${NC}"
    exit 0
else
    echo -e "${RED}❌ Some tests failed. REST API needs fixes.${NC}"
    exit 1  
fi