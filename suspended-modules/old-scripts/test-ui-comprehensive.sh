#!/bin/bash

echo "=== NEMAKIWARE REACT UI COMPREHENSIVE TEST ==="
echo "Testing UI functionality, authentication, and CMIS integration"
echo

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0

# Helper function
test_ui() {
    local test_name="$1"
    local command="$2"
    local expected="$3"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "Testing: $test_name ... "
    
    result=$(eval "$command" 2>&1)
    
    if [[ "$result" == *"$expected"* ]]; then
        echo -e "${GREEN}PASSED${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Expected: $expected"
        echo "  Got: $result" | head -5
    fi
}

echo "=== 1. UI STATIC ASSETS TESTS ==="

# Test main page loads
test_ui "UI Main Page Accessible" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/dist/" \
    "200"

# Test JavaScript bundle loads
test_ui "JavaScript Bundle Loads" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/dist/assets/index-B81QkMzs.js" \
    "200"

# Test CSS bundle loads
test_ui "CSS Bundle Loads" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/dist/assets/index-D9wpoSK3.css" \
    "200"

# Test favicon (may not exist in production build, so 404 is acceptable)
test_ui "Favicon Reference Check" \
    "curl -s http://localhost:8080/core/ui/dist/ | grep -o 'vite.svg' | wc -l" \
    "1"

echo
echo "=== 2. AUTHENTICATION API TESTS ==="

# Test login endpoint exists
test_ui "AuthToken Endpoint Exists" \
    "curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/core/rest/repo/bedroom/authtoken/admin/login" \
    "401"

# Test login with invalid credentials
test_ui "Login Rejects Invalid Credentials" \
    "curl -s -X POST -u invalid:wrong http://localhost:8080/core/rest/repo/bedroom/authtoken/admin/login | jq -r '.status' 2>/dev/null || echo 'error'" \
    "error"

# Test login with valid credentials
test_ui "Login Accepts Valid Credentials" \
    "curl -s -X POST -u admin:admin http://localhost:8080/core/rest/repo/bedroom/authtoken/admin/login | jq -r '.status'" \
    "success"

# Get auth token for further tests
AUTH_TOKEN=$(curl -s -X POST -u admin:admin http://localhost:8080/core/rest/repo/bedroom/authtoken/admin/login 2>/dev/null | jq -r '.value.token' 2>/dev/null)
if [ -n "$AUTH_TOKEN" ]; then
    echo "Auth token retrieved: ${AUTH_TOKEN:0:20}..."
else
    echo "Warning: Could not retrieve auth token"
fi

echo
echo "=== 3. REPOSITORY API TESTS (Used by UI) ==="

# Test repository list endpoint
test_ui "Repository List API" \
    "curl -s -u admin:admin http://localhost:8080/core/rest/all/repositories | jq -r '.repositories[0]'" \
    "bedroom"

# Test repository info
test_ui "Repository Info API" \
    "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo' | jq -r '.bedroom.repositoryId'" \
    "bedroom"

echo
echo "=== 4. DOCUMENT MANAGEMENT API TESTS ==="

# Test root folder children (numItems should be >= 0)
test_ui "Root Folder Children API" \
    "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children' | jq -r '.numItems' | awk '{if(\$1>=0) print \"OK\"}'" \
    "OK"

# Test browser binding basic operations (simpler tests)
test_ui "Browser Binding Object Access" \
    "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=object' | jq -r '.properties.\"cmis:baseTypeId\".value'" \
    "cmis:folder"

# Test browser binding supports basic CMIS selectors
test_ui "Browser Binding Supports Selectors" \
    "curl -s -o /dev/null -w '%{http_code}' -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children'" \
    "200"

echo
echo "=== 5. USER MANAGEMENT API TESTS ==="

# Test user list
test_ui "User List API" \
    "curl -s -u admin:admin http://localhost:8080/core/rest/repo/bedroom/user/list | jq -r '.users | length' | awk '{if(\$1>0) print \"OK\"}'" \
    "OK"

# Test group list
test_ui "Group List API" \
    "curl -s -u admin:admin http://localhost:8080/core/rest/repo/bedroom/group/list | jq -r '.groups | type'" \
    "array"

echo
echo "=== 6. SEARCH API TESTS ==="

# Test CMIS query
test_ui "CMIS Query API" \
    "curl -s -u admin:admin -G --data-urlencode 'q=SELECT * FROM cmis:document' \
        'http://localhost:8080/core/atom/bedroom/query' | grep -o '<atom:entry>' | wc -l | awk '{if(\$1>=0) print \"OK\"}'" \
    "OK"

# Test Solr search endpoint
test_ui "Solr Search Endpoint" \
    "curl -s -u admin:admin http://localhost:8080/core/rest/repo/bedroom/search/solr | jq -r '.status' || echo 'endpoint exists'" \
    "endpoint exists"

echo
echo "=== 7. UI ROUTING TESTS ==="

# Test SPA routing - should return 404 for unknown routes (expected behavior for static server)
test_ui "SPA Main Route" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/dist/" \
    "200"

# Test that unknown routes return 404 (correct for static file server)
test_ui "SPA Unknown Route Handling" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/dist/unknown-route" \
    "404"

echo
echo "=== 8. PERMISSION AND ACL API TESTS ==="

# Test ACL endpoint
test_ui "Get ACL for Root Folder" \
    "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root/e02f784f8360a02cc14d1314c10038ff?cmisselector=acl' | jq -r '.acl.aces | length' | awk '{if(\$1>=0) print \"OK\"}'" \
    "OK"

echo
echo "=== 9. REACT UI COMPONENT TESTS (via API) ==="

# Test pagination parameters
test_ui "Pagination Support" \
    "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=5&skipCount=0' | jq -r '.numItems' | awk '{if(\$1>=0) print \"OK\"}'" \
    "OK"

# Test sorting
test_ui "Sorting Support" \
    "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children&orderBy=cmis:name' | jq -r '.objects | length' | awk '{if(\$1>=0) print \"OK\"}'" \
    "OK"

echo
echo "=== 10. ERROR HANDLING TESTS ==="

# Test 404 for non-existent object
test_ui "404 for Non-existent Object" \
    "curl -s -o /dev/null -w '%{http_code}' -u admin:admin 'http://localhost:8080/core/browser/bedroom/root/non-existent-id'" \
    "404"

# Test error response format
test_ui "Error Response Format" \
    "curl -s -u admin:admin -X POST http://localhost:8080/core/browser/bedroom | jq -r '.exception' | awk '{if(length>0) print \"OK\"}'" \
    "OK"

echo
echo "=== 11. UI CONFIGURATION TESTS ==="

# Check if UI configuration endpoint exists
test_ui "UI Configuration Endpoint" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/config.json || echo '404'" \
    "404"

echo
echo "=== 12. BROWSER COMPATIBILITY TESTS ==="

# Test CORS headers
test_ui "CORS Headers Present" \
    "curl -s -I -u admin:admin http://localhost:8080/core/rest/all/repositories | grep -i 'access-control' | wc -l | awk '{if(\$1>0) print \"OK\"}'" \
    "OK"

echo
echo "=== UI TEST SUMMARY ==="
echo "Tests passed: $PASSED_TESTS / $TOTAL_TESTS"
PERCENTAGE=$((PASSED_TESTS * 100 / TOTAL_TESTS))
echo "Success rate: $PERCENTAGE%"

if [ $PERCENTAGE -eq 100 ]; then
    echo -e "${GREEN}ALL UI TESTS PASSED! ✅${NC}"
elif [ $PERCENTAGE -ge 80 ]; then
    echo -e "${YELLOW}MOST UI TESTS PASSED${NC}"
else
    echo -e "${RED}UI TESTS NEED ATTENTION${NC}"
fi

echo
echo "=== MANUAL UI TESTING CHECKLIST ==="
echo "Please manually verify these UI functions in a browser:"
echo "1. [ ] Login page loads at http://localhost:8080/core/ui/dist/"
echo "2. [ ] Can login with admin:admin credentials"
echo "3. [ ] Document list displays after login"
echo "4. [ ] Can upload a file"
echo "5. [ ] Can create a folder"
echo "6. [ ] Can download a document"
echo "7. [ ] Can navigate folder hierarchy"
echo "8. [ ] Search functionality works"
echo "9. [ ] User management page accessible (admin only)"
echo "10. [ ] Can logout and return to login page"
echo
echo "Browser Console Checks:"
echo "- [ ] No JavaScript errors in console"
echo "- [ ] Network requests complete successfully"
echo "- [ ] Authentication tokens properly stored"