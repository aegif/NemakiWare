#!/bin/bash
# Spring @RestController API Test Suite
# Tests new Spring-based user and group management APIs

BASE_URL="http://localhost:8080/core/api/v1/repo/bedroom"
AUTH="-u admin:admin"
CONTENT_TYPE="-H Content-Type: application/x-www-form-urlencoded"

echo "=== NemakiWare Spring @RestController API Test Suite ==="
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
        if [ ! -z "$body" ] && [ ${#body} -lt 500 ]; then
            echo "    Response: $body"
        elif [ ! -z "$body" ]; then
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

echo "=== Spring User Management API Tests ==="

# Test 1: List users
test_endpoint "User List (Spring)" "GET" "$BASE_URL/users" "" "200"

# Test 2: Create user
test_endpoint "Create User (springapi)" "POST" "$BASE_URL/users" \
    "userId=springapi&name=Spring API User&firstName=Spring&lastName=API&email=springapi@example.com&password=springpass123" \
    "201"

# Test 3: Get specific user
test_endpoint "Get User (springapi)" "GET" "$BASE_URL/users/springapi" "" "200"

# Test 4: Update user
test_endpoint "Update User (springapi)" "PUT" "$BASE_URL/users/springapi" \
    "name=Updated Spring User&firstName=Updated&lastName=Spring&email=updated.spring@example.com" \
    "200"

# Test 5: Create second user for group testing
test_endpoint "Create User (springapi2)" "POST" "$BASE_URL/users" \
    "userId=springapi2&name=Spring API User 2&firstName=Spring2&lastName=API2&email=springapi2@example.com&password=springpass456" \
    "201"

echo "=== Spring Group Management API Tests ==="

# Test 6: List groups
test_endpoint "Group List (Spring)" "GET" "$BASE_URL/groups" "" "200"

# Test 7: Create group
test_endpoint "Create Group (springgroup)" "POST" "$BASE_URL/groups" \
    "groupId=springgroup&name=Spring API Group&users=[\"springapi\"]&groups=[]" \
    "201"

# Test 8: Get specific group
test_endpoint "Get Group (springgroup)" "GET" "$BASE_URL/groups/springgroup" "" "200"

# Test 9: Update group
test_endpoint "Update Group (springgroup)" "PUT" "$BASE_URL/groups/springgroup" \
    "name=Updated Spring Group&users=[\"springapi\",\"springapi2\"]&groups=[]" \
    "200"

# Test 10: Add members to group
test_endpoint "Add Members (springgroup)" "POST" "$BASE_URL/groups/springgroup/members" \
    "users=[\"admin\"]" \
    "200"

# Test 11: Remove members from group
test_endpoint "Remove Members (springgroup)" "DELETE" "$BASE_URL/groups/springgroup/members" \
    "users=[\"admin\"]" \
    "200"

echo "=== Error Handling Tests ==="

# Test 12: Create duplicate user (should fail)
test_endpoint "Create Duplicate User" "POST" "$BASE_URL/users" \
    "userId=springapi&name=Duplicate User&firstName=Dup&lastName=User&email=dup@example.com&password=duppass" \
    "400"

# Test 13: Get non-existent user
test_endpoint "Get Non-existent User" "GET" "$BASE_URL/users/nonexistent" "" "404"

# Test 14: Get non-existent group  
test_endpoint "Get Non-existent Group" "GET" "$BASE_URL/groups/nonexistent" "" "404"

echo "=== Cleanup Tests ==="

# Test 15: Delete group
test_endpoint "Delete Group (springgroup)" "DELETE" "$BASE_URL/groups/springgroup" "" "200"

# Test 16: Delete users
test_endpoint "Delete User (springapi)" "DELETE" "$BASE_URL/users/springapi" "" "200"

# Test 17: Delete second user
test_endpoint "Delete User (springapi2)" "DELETE" "$BASE_URL/users/springapi2" "" "200"

echo "=== Test Results ==="
echo "Total Tests: $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $((TOTAL_TESTS - PASSED_TESTS))"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
    echo -e "${GREEN}🎉 All tests passed! Spring REST API is fully functional.${NC}"
    exit 0
else
    echo -e "${RED}❌ Some tests failed. Spring REST API needs fixes.${NC}"
    exit 1  
fi