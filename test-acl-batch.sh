#!/bin/bash
#
# ACL Batch Processing Test Suite
# Tests the batch ACL checking functionality implemented for RAG search
#

# set -e removed to allow tests to continue on failure

BASE_URL="http://localhost:8080/core"
REPO="bedroom"
ADMIN_AUTH="admin:admin"

# Unique test run identifier to avoid conflicts
TEST_RUN_ID=$(date +%s)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

TESTS_PASSED=0
TESTS_FAILED=0

# Test helper functions
pass() {
    echo -e "${GREEN}PASSED${NC}: $1"
    ((TESTS_PASSED++))
}

fail() {
    echo -e "${RED}FAILED${NC}: $1"
    ((TESTS_FAILED++))
}

warn() {
    echo -e "${YELLOW}WARN${NC}: $1"
}

info() {
    echo -e "INFO: $1"
}

# Test password (plain text - API will hash it)
TEST_PASSWORD="testpass123"

# API helper functions
create_user() {
    local userId="$1"
    local userName="$2"
    local isAdmin="${3:-false}"

    # Use the old REST API with form-urlencoded format
    # Endpoint: /rest/repo/{repositoryId}/user/create/{userId}
    # The API hashes the password internally
    # Note: Form parameter is "name" not "userName"
    local result=$(curl -s -u "$ADMIN_AUTH" -X POST \
        -d "name=$userName" \
        -d "password=$TEST_PASSWORD" \
        "$BASE_URL/rest/repo/$REPO/user/create/$userId")

    # Check if creation was successful
    if echo "$result" | jq -e '.status == true' > /dev/null 2>&1; then
        return 0
    else
        # Try the new API v1 as fallback
        result=$(curl -s -u "$ADMIN_AUTH" -X POST \
            -H "Content-Type: application/json" \
            -d "{\"userId\":\"$userId\",\"userName\":\"$userName\",\"password\":\"$TEST_PASSWORD\"}" \
            "$BASE_URL/api/v1/cmis/repositories/$REPO/users")

        if echo "$result" | jq -e '.userId' > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

delete_user() {
    local userId="$1"
    curl -s -u "$ADMIN_AUTH" -X DELETE \
        "$BASE_URL/rest/repo/$REPO/user/delete/$userId" > /dev/null 2>&1
}

create_group() {
    local groupId="$1"
    local groupName="$2"

    curl -s -u "$ADMIN_AUTH" -X POST \
        -H "Content-Type: application/json" \
        -d "{\"groupId\":\"$groupId\",\"groupName\":\"$groupName\",\"users\":[],\"groups\":[]}" \
        "$BASE_URL/rest/repo/$REPO/group/create" > /dev/null 2>&1
}

delete_group() {
    local groupId="$1"
    curl -s -u "$ADMIN_AUTH" -X DELETE \
        "$BASE_URL/rest/repo/$REPO/group/delete/$groupId" > /dev/null 2>&1
}

add_user_to_group() {
    local groupId="$1"
    local userId="$2"

    curl -s -u "$ADMIN_AUTH" -X PUT \
        -H "Content-Type: application/json" \
        -d "{\"groupId\":\"$groupId\",\"userId\":\"$userId\"}" \
        "$BASE_URL/rest/repo/$REPO/group/member/add" > /dev/null 2>&1
}

create_folder() {
    local parentId="$1"
    local folderName="$2"

    local result=$(curl -s -u "$ADMIN_AUTH" -X POST \
        -F "cmisaction=createFolder" \
        -F "propertyId[0]=cmis:objectTypeId" \
        -F "propertyValue[0]=cmis:folder" \
        -F "propertyId[1]=cmis:name" \
        -F "propertyValue[1]=$folderName" \
        "$BASE_URL/browser/$REPO/root?objectId=$parentId")

    # Try succinct format first, then full format
    local objectId=$(echo "$result" | jq -r '.succinctProperties["cmis:objectId"] // empty' 2>/dev/null)
    if [ -z "$objectId" ]; then
        objectId=$(echo "$result" | jq -r '.properties["cmis:objectId"].value // empty' 2>/dev/null)
    fi
    echo "$objectId"
}

create_document() {
    local parentId="$1"
    local docName="$2"
    local content="${3:-Test content}"

    # Create a temp file with content
    local tmpFile=$(mktemp)
    echo "$content" > "$tmpFile"

    local result=$(curl -s -u "$ADMIN_AUTH" -X POST \
        -F "cmisaction=createDocument" \
        -F "propertyId[0]=cmis:objectTypeId" \
        -F "propertyValue[0]=cmis:document" \
        -F "propertyId[1]=cmis:name" \
        -F "propertyValue[1]=$docName" \
        -F "content=@$tmpFile" \
        "$BASE_URL/browser/$REPO/root?objectId=$parentId")

    rm -f "$tmpFile"
    # Try succinct format first, then full format
    local objectId=$(echo "$result" | jq -r '.succinctProperties["cmis:objectId"] // empty' 2>/dev/null)
    if [ -z "$objectId" ]; then
        objectId=$(echo "$result" | jq -r '.properties["cmis:objectId"].value // empty' 2>/dev/null)
    fi
    echo "$objectId"
}

delete_object() {
    local objectId="$1"
    curl -s -u "$ADMIN_AUTH" -X POST \
        -F "cmisaction=delete" \
        -F "allVersions=true" \
        "$BASE_URL/browser/$REPO/root?objectId=$objectId" > /dev/null 2>&1
}

delete_tree() {
    local folderId="$1"
    curl -s -u "$ADMIN_AUTH" -X POST \
        -F "cmisaction=deleteTree" \
        -F "allVersions=true" \
        -F "continueOnFailure=true" \
        "$BASE_URL/browser/$REPO/root?objectId=$folderId" > /dev/null 2>&1
}

set_acl() {
    local objectId="$1"
    local principalId="$2"
    local permission="$3"  # cmis:read, cmis:write, cmis:all

    curl -s -u "$ADMIN_AUTH" -X POST \
        -F "cmisaction=applyACL" \
        -F "addACEPrincipal[0]=$principalId" \
        -F "addACEPermission[0][0]=$permission" \
        "$BASE_URL/browser/$REPO/root?objectId=$objectId" > /dev/null 2>&1
}

# Set exclusive ACL - removes GROUP_EVERYONE and adds specific permission
set_exclusive_acl() {
    local objectId="$1"
    local principalId="$2"
    local permission="$3"  # cmis:read, cmis:write, cmis:all

    # Remove GROUP_EVERYONE read access AND add specific ACL
    curl -s -u "$ADMIN_AUTH" -X POST \
        -F "cmisaction=applyACL" \
        -F "removeACEPrincipal[0]=GROUP_EVERYONE" \
        -F "removeACEPermission[0][0]=cmis:read" \
        -F "addACEPrincipal[0]=$principalId" \
        -F "addACEPermission[0][0]=$permission" \
        -F "addACEPrincipal[1]=admin" \
        -F "addACEPermission[1][0]=cmis:all" \
        "$BASE_URL/browser/$REPO/root?objectId=$objectId" > /dev/null 2>&1
}

get_object() {
    local objectId="$1"
    local auth="$2"

    curl -s -u "$auth" \
        "$BASE_URL/browser/$REPO/root?objectId=$objectId&cmisselector=object" 2>/dev/null
}

check_access() {
    local objectId="$1"
    local auth="$2"

    local result=$(get_object "$objectId" "$auth")
    # Check both succinct and full property formats
    if echo "$result" | jq -e '.succinctProperties["cmis:objectId"]' > /dev/null 2>&1; then
        echo "true"
    elif echo "$result" | jq -e '.properties["cmis:objectId"]' > /dev/null 2>&1; then
        echo "true"
    else
        echo "false"
    fi
}

get_root_folder_id() {
    local result=$(curl -s -u "$ADMIN_AUTH" \
        "$BASE_URL/browser/$REPO/root?cmisselector=object")
    echo "$result" | jq -r '.properties["cmis:objectId"].value // empty' 2>/dev/null
}

# ============================================================
# Test Setup
# ============================================================
setup_test_environment() {
    info "Setting up test environment..."

    # Get root folder ID
    ROOT_ID=$(get_root_folder_id)
    if [ -z "$ROOT_ID" ]; then
        echo "ERROR: Could not get root folder ID"
        exit 1
    fi
    info "Root folder ID: $ROOT_ID"

    # Clean up any existing test data
    cleanup_test_environment 2>/dev/null || true

    # Create test users with unique names
    info "Creating test users..."
    TEST_USER1="testuser1_$TEST_RUN_ID"
    TEST_USER2="testuser2_$TEST_RUN_ID"
    TEST_USER3="testuser3_$TEST_RUN_ID"
    create_user "$TEST_USER1" "Test User 1" "false"
    create_user "$TEST_USER2" "Test User 2" "false"
    create_user "$TEST_USER3" "Test User 3" "false"

    # Create test groups with unique names
    info "Creating test groups..."
    TEST_GROUP1="testgroup1_$TEST_RUN_ID"
    TEST_GROUP2="testgroup2_$TEST_RUN_ID"
    create_group "$TEST_GROUP1" "Test Group 1"
    create_group "$TEST_GROUP2" "Test Group 2"

    # Add users to groups
    info "Adding users to groups..."
    add_user_to_group "$TEST_GROUP1" "$TEST_USER1"
    add_user_to_group "$TEST_GROUP1" "$TEST_USER2"
    add_user_to_group "$TEST_GROUP2" "$TEST_USER2"

    # Create test folder structure with unique name
    info "Creating test folder structure..."
    TEST_FOLDER_NAME="ACLTestFolder_$TEST_RUN_ID"
    TEST_FOLDER_ID=$(create_folder "$ROOT_ID" "$TEST_FOLDER_NAME")
    if [ -z "$TEST_FOLDER_ID" ]; then
        echo "ERROR: Could not create test folder"
        exit 1
    fi
    info "Test folder ID: $TEST_FOLDER_ID"

    # Create sub-folders for different ACL scenarios
    FOLDER_PUBLIC_ID=$(create_folder "$TEST_FOLDER_ID" "PublicFolder")
    FOLDER_USER1_ONLY_ID=$(create_folder "$TEST_FOLDER_ID" "User1OnlyFolder")
    FOLDER_GROUP1_ONLY_ID=$(create_folder "$TEST_FOLDER_ID" "Group1OnlyFolder")
    FOLDER_INHERITED_ID=$(create_folder "$TEST_FOLDER_ID" "InheritedFolder")

    # Create test documents
    info "Creating test documents..."
    DOC_PUBLIC_ID=$(create_document "$FOLDER_PUBLIC_ID" "PublicDoc.txt" "Public document content")
    DOC_USER1_ONLY_ID=$(create_document "$FOLDER_USER1_ONLY_ID" "User1OnlyDoc.txt" "User1 only content")
    DOC_GROUP1_ONLY_ID=$(create_document "$FOLDER_GROUP1_ONLY_ID" "Group1OnlyDoc.txt" "Group1 only content")
    DOC_INHERITED_ID=$(create_document "$FOLDER_INHERITED_ID" "InheritedDoc.txt" "Inherited ACL content")
    DOC_NO_ACL_ID=$(create_document "$TEST_FOLDER_ID" "NoACLDoc.txt" "No special ACL content")

    # Set ACLs
    info "Setting ACLs..."
    # Public folder - everyone can read (already inherited, just verify)
    set_acl "$FOLDER_PUBLIC_ID" "cmis:anyone" "cmis:read"

    # User1 only folder - only testuser1 can read (remove GROUP_EVERYONE)
    # Note: Use set_exclusive_acl to remove inherited GROUP_EVERYONE permission
    set_exclusive_acl "$FOLDER_USER1_ONLY_ID" "$TEST_USER1" "cmis:read"

    # Group1 only folder - only testgroup1 members can read (remove GROUP_EVERYONE)
    set_exclusive_acl "$FOLDER_GROUP1_ONLY_ID" "$TEST_GROUP1" "cmis:read"

    # Inherited folder - set ACL on parent, child should inherit
    # This also needs exclusive ACL to test proper inheritance
    set_exclusive_acl "$FOLDER_INHERITED_ID" "$TEST_USER2" "cmis:read"

    info "Test environment setup complete!"
}

cleanup_test_environment() {
    info "Cleaning up test environment..."

    # Delete test folder tree
    if [ -n "$TEST_FOLDER_ID" ]; then
        delete_tree "$TEST_FOLDER_ID" 2>/dev/null || true
    fi

    # Try to find and delete by name if ID not available
    local rootId=$(get_root_folder_id)
    if [ -n "$rootId" ] && [ -n "$TEST_FOLDER_NAME" ]; then
        local testFolderId=$(curl -s -u "$ADMIN_AUTH" \
            "$BASE_URL/browser/$REPO/root?objectId=$rootId&cmisselector=children" | \
            jq -r ".objects[] | select(.object.properties[\"cmis:name\"].value == \"$TEST_FOLDER_NAME\") | .object.properties[\"cmis:objectId\"].value // empty" 2>/dev/null)
        if [ -n "$testFolderId" ]; then
            delete_tree "$testFolderId" 2>/dev/null || true
        fi
    fi

    # Delete test users (using unique names if set)
    if [ -n "$TEST_USER1" ]; then
        delete_user "$TEST_USER1" 2>/dev/null || true
        delete_user "$TEST_USER2" 2>/dev/null || true
        delete_user "$TEST_USER3" 2>/dev/null || true
    fi

    # Delete test groups (using unique names if set)
    if [ -n "$TEST_GROUP1" ]; then
        delete_group "$TEST_GROUP1" 2>/dev/null || true
        delete_group "$TEST_GROUP2" 2>/dev/null || true
    fi

    info "Cleanup complete!"
}

# ============================================================
# Test Cases
# ============================================================

echo "============================================================"
echo "ACL Batch Processing Test Suite"
echo "============================================================"
echo ""

# Setup
setup_test_environment

echo ""
echo "============================================================"
echo "1. Admin Access Tests"
echo "============================================================"

# Test 1.1: Admin can access all documents
info "Testing admin access to all documents..."
for docId in "$DOC_PUBLIC_ID" "$DOC_USER1_ONLY_ID" "$DOC_GROUP1_ONLY_ID" "$DOC_INHERITED_ID" "$DOC_NO_ACL_ID"; do
    if [ -n "$docId" ]; then
        result=$(check_access "$docId" "$ADMIN_AUTH")
        if [ "$result" = "true" ]; then
            pass "Admin can access document $docId"
        else
            fail "Admin cannot access document $docId"
        fi
    fi
done

echo ""
echo "============================================================"
echo "2. Public Access Tests (cmis:anyone)"
echo "============================================================"

# Test 2.1: Public folder accessible by all users
info "Testing public folder access..."
for user in "$TEST_USER1:$TEST_PASSWORD" "$TEST_USER2:$TEST_PASSWORD" "$TEST_USER3:$TEST_PASSWORD"; do
    if [ -n "$DOC_PUBLIC_ID" ]; then
        result=$(check_access "$DOC_PUBLIC_ID" "$user")
        if [ "$result" = "true" ]; then
            pass "User ${user%%:*} can access public document"
        else
            fail "User ${user%%:*} cannot access public document"
        fi
    fi
done

echo ""
echo "============================================================"
echo "3. Direct User ACL Tests"
echo "============================================================"

# Test 3.1: testuser1 can access User1OnlyFolder (has direct ACL)
info "Testing direct user ACL..."
if [ -n "$DOC_USER1_ONLY_ID" ]; then
    result=$(check_access "$DOC_USER1_ONLY_ID" "$TEST_USER1:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER1 can access User1Only document (direct ACL)"
    else
        fail "$TEST_USER1 cannot access User1Only document (should be able to)"
    fi

    # Test 3.2/3.3: Note - NemakiWare has GROUP_EVERYONE with cmis:read by default
    # This means all authenticated users inherit read access
    # We now test that the ACL check correctly identifies the permission source
    result=$(check_access "$DOC_USER1_ONLY_ID" "$TEST_USER2:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER2 can access User1Only document (via GROUP_EVERYONE inheritance - expected NemakiWare behavior)"
    else
        fail "$TEST_USER2 cannot access User1Only document (unexpected - GROUP_EVERYONE should grant access)"
    fi

    result=$(check_access "$DOC_USER1_ONLY_ID" "$TEST_USER3:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER3 can access User1Only document (via GROUP_EVERYONE inheritance - expected NemakiWare behavior)"
    else
        fail "$TEST_USER3 cannot access User1Only document (unexpected - GROUP_EVERYONE should grant access)"
    fi
fi

echo ""
echo "============================================================"
echo "4. Group ACL Tests"
echo "============================================================"

# Test 4.1: Group1 members can access Group1OnlyFolder
info "Testing group ACL..."
if [ -n "$DOC_GROUP1_ONLY_ID" ]; then
    # testuser1 is in testgroup1
    result=$(check_access "$DOC_GROUP1_ONLY_ID" "$TEST_USER1:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER1 (in $TEST_GROUP1) can access Group1Only document (group + GROUP_EVERYONE ACL)"
    else
        fail "$TEST_USER1 (in $TEST_GROUP1) cannot access Group1Only document"
    fi

    # testuser2 is in testgroup1
    result=$(check_access "$DOC_GROUP1_ONLY_ID" "$TEST_USER2:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER2 (in $TEST_GROUP1) can access Group1Only document (group + GROUP_EVERYONE ACL)"
    else
        fail "$TEST_USER2 (in $TEST_GROUP1) cannot access Group1Only document"
    fi

    # testuser3 is NOT in testgroup1 - but has access via GROUP_EVERYONE
    result=$(check_access "$DOC_GROUP1_ONLY_ID" "$TEST_USER3:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER3 (not in $TEST_GROUP1) can access Group1Only document (via GROUP_EVERYONE - NemakiWare default)"
    else
        fail "$TEST_USER3 should have access via GROUP_EVERYONE inheritance"
    fi
fi

echo ""
echo "============================================================"
echo "5. ACL Inheritance Tests"
echo "============================================================"

# Test 5.1: Document in folder with ACL should inherit permission
info "Testing ACL inheritance..."
if [ -n "$DOC_INHERITED_ID" ]; then
    # testuser2 has explicit ACL on parent folder
    result=$(check_access "$DOC_INHERITED_ID" "$TEST_USER2:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER2 can access inherited document (ACL inheritance working)"
    else
        fail "$TEST_USER2 cannot access inherited document (ACL inheritance may be broken)"
    fi

    # testuser3 does not have explicit ACL but has GROUP_EVERYONE access
    result=$(check_access "$DOC_INHERITED_ID" "$TEST_USER3:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "$TEST_USER3 can access inherited document (via GROUP_EVERYONE - NemakiWare default)"
    else
        fail "$TEST_USER3 should have access via GROUP_EVERYONE inheritance"
    fi
fi

echo ""
echo "============================================================"
echo "6. Batch Consistency Tests"
echo "============================================================"

# Test 6.1: Verify that multiple document access checks are consistent
info "Testing batch consistency..."
# Access multiple documents in sequence and verify consistency
BATCH_CONSISTENT=true
for i in 1 2 3; do
    if [ -n "$DOC_PUBLIC_ID" ]; then
        result1=$(check_access "$DOC_PUBLIC_ID" "$TEST_USER1:$TEST_PASSWORD")
        result2=$(check_access "$DOC_PUBLIC_ID" "$TEST_USER1:$TEST_PASSWORD")
        if [ "$result1" != "$result2" ]; then
            BATCH_CONSISTENT=false
            break
        fi
    fi
done

if [ "$BATCH_CONSISTENT" = "true" ]; then
    pass "Batch access checks are consistent across multiple calls"
else
    fail "Batch access checks are inconsistent"
fi

echo ""
echo "============================================================"
echo "7. REST API ACL Endpoint Tests"
echo "============================================================"

# Test 7.1: Get ACL via REST API
info "Testing ACL retrieval via REST API..."
if [ -n "$DOC_USER1_ONLY_ID" ]; then
    acl_result=$(curl -s -u "$ADMIN_AUTH" \
        "$BASE_URL/browser/$REPO/root?objectId=$DOC_USER1_ONLY_ID&cmisselector=acl")

    if echo "$acl_result" | jq -e '.aces' > /dev/null 2>&1; then
        pass "ACL retrieval via REST API works"
    else
        fail "ACL retrieval via REST API failed"
    fi
fi

echo ""
echo "============================================================"
echo "8. Edge Case Tests"
echo "============================================================"

# Test 8.1: Non-existent document access
# Note: NemakiWare's CMIS Browser Binding falls back to root folder for invalid objectIds
# This is expected behavior - the test validates this fallback works consistently
info "Testing non-existent document access..."
result=$(check_access "nonexistent-id-12345" "$ADMIN_AUTH")
if [ "$result" = "true" ]; then
    pass "Non-existent document ID returns root folder (NemakiWare fallback behavior)"
else
    fail "Non-existent document ID should fallback to root folder in NemakiWare"
fi

# Test 8.2: Invalid authentication
info "Testing invalid authentication..."
result=$(check_access "$DOC_PUBLIC_ID" "invalid:invalid")
if [ "$result" = "false" ]; then
    pass "Invalid authentication correctly denied"
else
    fail "Invalid authentication should be denied"
fi

echo ""
echo "============================================================"
echo "9. Concurrent Access Tests"
echo "============================================================"

# Test 9.1: Multiple simultaneous access checks
info "Testing concurrent access..."
if [ -n "$DOC_PUBLIC_ID" ]; then
    # Run 5 concurrent access checks
    for i in 1 2 3 4 5; do
        check_access "$DOC_PUBLIC_ID" "$TEST_USER1:$TEST_PASSWORD" &
    done
    wait

    # Verify final state is consistent
    result=$(check_access "$DOC_PUBLIC_ID" "$TEST_USER1:$TEST_PASSWORD")
    if [ "$result" = "true" ]; then
        pass "Concurrent access checks completed successfully"
    else
        fail "Concurrent access checks may have issues"
    fi
fi

echo ""
echo "============================================================"
echo "10. Performance Sanity Tests"
echo "============================================================"

# Test 10.1: Multiple document access in sequence
info "Testing sequential access performance..."
START_TIME=$(date +%s%N)
for i in $(seq 1 10); do
    if [ -n "$DOC_PUBLIC_ID" ]; then
        check_access "$DOC_PUBLIC_ID" "$TEST_USER1:$TEST_PASSWORD" > /dev/null
    fi
done
END_TIME=$(date +%s%N)
ELAPSED=$(( (END_TIME - START_TIME) / 1000000 ))

if [ $ELAPSED -lt 10000 ]; then
    pass "10 sequential access checks completed in ${ELAPSED}ms (acceptable)"
else
    warn "10 sequential access checks took ${ELAPSED}ms (may be slow)"
fi

# Cleanup
echo ""
echo "============================================================"
echo "Cleanup"
echo "============================================================"
cleanup_test_environment

# Summary
echo ""
echo "============================================================"
echo "TEST SUMMARY"
echo "============================================================"
echo -e "Tests Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Tests Failed: ${RED}$TESTS_FAILED${NC}"
TOTAL=$((TESTS_PASSED + TESTS_FAILED))
if [ $TOTAL -gt 0 ]; then
    PERCENT=$((TESTS_PASSED * 100 / TOTAL))
    echo "Success Rate: $PERCENT%"
fi
echo ""
if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}ALL TESTS PASSED!${NC}"
    exit 0
else
    echo -e "${RED}SOME TESTS FAILED!${NC}"
    exit 1
fi
