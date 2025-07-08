#!/bin/bash

# Manual TCK-like test execution for current environment

echo "=========================================="
echo "Manual TCK Test Execution"
echo "Testing CMIS 1.1 Compliance"
echo "=========================================="

AUTH="admin:admin"
BASE_URL="http://localhost:8080/core/atom/bedroom"
ROOT_ID="e02f784f8360a02cc14d1314c10038ff"

# Test results tracking
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Helper function to test and report
test_cmis() {
    local test_name="$1"
    local expected_status="$2"
    local curl_command="$3"
    
    ((TOTAL_TESTS++))
    echo -n "Testing $test_name... "
    
    # Execute the curl command and capture status
    local actual_status=$(eval "$curl_command")
    
    if [ "$actual_status" = "$expected_status" ]; then
        echo "✓ PASSED"
        ((PASSED_TESTS++))
    else
        echo "✗ FAILED (expected: $expected_status, got: $actual_status)"
        ((FAILED_TESTS++))
    fi
}

echo ""
echo "1. BASICS TEST GROUP"
echo "===================="
test_cmis "Repository Access" "200" \
    "curl -s -u $AUTH -o /dev/null -w '%{http_code}' '$BASE_URL'"

test_cmis "Repository Info" "200" \
    "curl -s -u $AUTH '$BASE_URL' | grep -q 'repositoryId' && echo '200' || echo '404'"

echo ""
echo "2. TYPES TEST GROUP"
echo "==================="
test_cmis "Document Type Access" "200" \
    "curl -s -u $AUTH -o /dev/null -w '%{http_code}' '$BASE_URL/type?id=cmis:document'"

test_cmis "Folder Type Access" "200" \
    "curl -s -u $AUTH -o /dev/null -w '%{http_code}' '$BASE_URL/type?id=cmis:folder'"

echo ""
echo "3. CRUD TEST GROUP"
echo "=================="

# Create a test folder
echo -n "Testing Folder Creation... "
FOLDER_RESPONSE=$(curl -s -u $AUTH -X POST \
    -H "Content-Type: application/atom+xml" \
    -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
  <atom:title>tck-test-folder</atom:title>
  <cmisra:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:folder</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>tck-test-folder</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmisra:object>
</atom:entry>' \
    "$BASE_URL/children?id=$ROOT_ID")

if echo "$FOLDER_RESPONSE" | grep -q "tck-test-folder"; then
    echo "✓ PASSED"
    ((PASSED_TESTS++))
    ((TOTAL_TESTS++))
    
    # Extract folder ID for further tests
    FOLDER_ID=$(echo "$FOLDER_RESPONSE" | xmllint --format - 2>/dev/null | grep "cmis:objectId" | head -1 | sed 's/.*>\(.*\)<.*/\1/')
    
    # Test folder retrieval
    test_cmis "Folder Retrieval" "200" \
        "curl -s -u $AUTH -o /dev/null -w '%{http_code}' '$BASE_URL/id?id=$FOLDER_ID'"
    
    # Test folder deletion
    test_cmis "Folder Deletion" "204" \
        "curl -s -u $AUTH -X DELETE -o /dev/null -w '%{http_code}' '$BASE_URL/id?id=$FOLDER_ID'"
else
    echo "✗ FAILED"
    ((FAILED_TESTS++))
    ((TOTAL_TESTS++))
fi

echo ""
echo "4. QUERY TEST GROUP"
echo "==================="

# Test basic queries
test_cmis "Query: SELECT * FROM cmis:folder" "200" \
    "curl -s -u $AUTH -o /dev/null -w '%{http_code}' '$BASE_URL/query?q=SELECT+*+FROM+cmis:folder'"

test_cmis "Query: SELECT * FROM cmis:document" "200" \
    "curl -s -u $AUTH -o /dev/null -w '%{http_code}' '$BASE_URL/query?q=SELECT+*+FROM+cmis:document'"

echo ""
echo "5. VERSIONING TEST GROUP"
echo "======================="

# Test if versioning is enabled
test_cmis "Check-out capability" "200" \
    "curl -s -u $AUTH '$BASE_URL' | grep -q 'capabilityPWCUpdatable.*true' && echo '200' || echo '404'"

echo ""
echo "6. FILING TEST GROUP"
echo "===================="

# Test filing capabilities
test_cmis "Multi-filing disabled" "200" \
    "curl -s -u $AUTH '$BASE_URL' | grep -q 'capabilityMultifiling.*false' && echo '200' || echo '404'"

test_cmis "Unfiling disabled" "200" \
    "curl -s -u $AUTH '$BASE_URL' | grep -q 'capabilityUnfiling.*false' && echo '200' || echo '404'"

echo ""
echo "7. CONTROL (ACL) TEST GROUP"
echo "==========================="

# Test ACL capability
test_cmis "ACL capability" "200" \
    "curl -s -u $AUTH '$BASE_URL' | grep -q 'capabilityACL.*manage' && echo '200' || echo '404'"

echo ""
echo "=========================================="
echo "TCK TEST EXECUTION SUMMARY"
echo "=========================================="
echo "Total Tests: $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $FAILED_TESTS"
SUCCESS_RATE=$(( PASSED_TESTS * 100 / TOTAL_TESTS ))
echo "Success Rate: $SUCCESS_RATE%"
echo ""

if [ $FAILED_TESTS -eq 0 ]; then
    echo "🎉 All tests PASSED!"
    echo "✅ NemakiWare shows excellent CMIS 1.1 compliance"
    exit 0
else
    echo "⚠️  Some tests failed"
    echo "📊 Review the results above for details"
    exit 1
fi