#!/bin/bash

# Custom Type and Property Test Script
# Tests custom types, property editing, and search functionality

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Base configuration
BASE_URL="http://localhost:8080"
REPO_ID="bedroom"
USER="admin"
PASS="admin"

# Counters
total_tests=0
success_count=0
failed_tests=""

# Helper function for API calls
cmis_post() {
    local endpoint="$1"
    shift
    curl -s -u "$USER:$PASS" -X POST "$BASE_URL/core/browser/$REPO_ID$endpoint" "$@" 2>/dev/null
}

cmis_get() {
    local endpoint="$1"
    shift
    curl -s -u "$USER:$PASS" "$BASE_URL/core/browser/$REPO_ID$endpoint" "$@" 2>/dev/null
}

# Test function
run_test() {
    local test_name="$1"
    local result="$2"
    local expected="$3"

    total_tests=$((total_tests + 1))
    echo -n "Testing: $test_name ... "

    if [[ "$result" == *"$expected"* ]]; then
        echo -e "${GREEN}PASSED${NC}"
        success_count=$((success_count + 1))
        return 0
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Expected: $expected"
        echo "  Got: ${result:0:200}..."
        failed_tests="$failed_tests\n  - $test_name"
        return 1
    fi
}

echo ""
echo "========================================"
echo "  NemakiWare Custom Type Test Suite"
echo "========================================"
echo ""

# Get root folder ID
echo -e "${BLUE}[Setup] Getting root folder ID...${NC}"
ROOT_INFO=$(cmis_get "/root?cmisselector=object")
ROOT_ID=$(echo "$ROOT_INFO" | python3 -c "import json,sys; print(json.load(sys.stdin)['properties']['cmis:objectId']['value'])" 2>/dev/null)
echo "Root folder ID: $ROOT_ID"
echo ""

# ===========================================
# Test 1: Verify Custom Document Type Exists
# ===========================================
echo -e "${BLUE}[Section 1] Custom Document Type Tests${NC}"

TYPE_DEF=$(cmis_get "?cmisselector=typeDefinition&typeId=test:customDocument")
run_test "Custom type 'test:customDocument' exists" "$TYPE_DEF" '"id":"test:customDocument"'

# Check custom property exists in type definition
run_test "Custom property 'test:customProperty' defined" "$TYPE_DEF" '"test:customProperty"'

# ===========================================
# Test 2: Create Document with Custom Type
# ===========================================
echo ""
echo -e "${BLUE}[Section 2] Document Creation with Custom Type${NC}"

# Create a test document with custom type and custom property
CREATE_RESULT=$(cmis_post "" \
    -F "cmisaction=createDocument" \
    -F "folderId=$ROOT_ID" \
    -F "propertyId[0]=cmis:objectTypeId" \
    -F "propertyValue[0]=test:customDocument" \
    -F "propertyId[1]=cmis:name" \
    -F "propertyValue[1]=CustomTypeTestDoc-$(date +%s).txt" \
    -F "propertyId[2]=test:customProperty" \
    -F "propertyValue[2]=InitialValue-TestData" \
    -F "content=@-" <<< "Test content for custom type document")

DOC_ID=$(echo "$CREATE_RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin)['properties']['cmis:objectId']['value'])" 2>/dev/null)

run_test "Create document with custom type" "$CREATE_RESULT" '"cmis:objectTypeId":{"id":"cmis:objectTypeId","localName":"cmis:objectTypeId"'

if [[ -n "$DOC_ID" ]]; then
    echo "  Created document ID: $DOC_ID"

    # Verify custom property value (use /{objectId}?cmisselector=object format)
    DOC_INFO=$(cmis_get "/$DOC_ID?cmisselector=object")
    run_test "Custom property 'test:customProperty' set" "$DOC_INFO" 'InitialValue-TestData'
fi

# ===========================================
# Test 3: Update Custom Property
# ===========================================
echo ""
echo -e "${BLUE}[Section 3] Custom Property Update Tests${NC}"

if [[ -n "$DOC_ID" ]]; then
    # Get change token for update (re-fetch to ensure we have DOC_INFO)
    if [[ -z "$DOC_INFO" ]] || [[ "$DOC_INFO" == *"exception"* ]]; then
        DOC_INFO=$(cmis_get "/$DOC_ID?cmisselector=object")
    fi
    CHANGE_TOKEN=$(echo "$DOC_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['properties'].get('cmis:changeToken',{}).get('value',''))" 2>/dev/null)
    echo "  Change token: ${CHANGE_TOKEN:-'(empty)'}"

    # Update custom property (changeToken is required)
    if [[ -n "$CHANGE_TOKEN" ]]; then
        UPDATE_RESULT=$(cmis_post "" \
            -F "cmisaction=updateProperties" \
            -F "objectId=$DOC_ID" \
            -F "changeToken=$CHANGE_TOKEN" \
            -F "propertyId[0]=test:customProperty" \
            -F "propertyValue[0]=UpdatedValue-$(date +%s)")
    else
        UPDATE_RESULT=$(cmis_post "" \
            -F "cmisaction=updateProperties" \
            -F "objectId=$DOC_ID" \
            -F "propertyId[0]=test:customProperty" \
            -F "propertyValue[0]=UpdatedValue-$(date +%s)")
    fi

    run_test "Update custom property" "$UPDATE_RESULT" '"test:customProperty"'

    # Verify updated value (use correct URL format)
    UPDATED_INFO=$(cmis_get "/$DOC_ID?cmisselector=object")
    run_test "Custom property value updated" "$UPDATED_INFO" 'UpdatedValue-'
fi

# ===========================================
# Test 4: Query with Custom Properties
# ===========================================
echo ""
echo -e "${BLUE}[Section 4] Query Tests with Custom Properties${NC}"

# Search for documents with custom type
QUERY_RESULT=$(cmis_post "" \
    -F "cmisaction=query" \
    -F "q=SELECT * FROM test:customDocument WHERE test:customProperty LIKE '%UpdatedValue%'" \
    -F "maxItems=10")

run_test "Query by custom property value" "$QUERY_RESULT" '"numItems"'

# Count results
NUM_RESULTS=$(echo "$QUERY_RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('numItems', 0))" 2>/dev/null)
if [[ "$NUM_RESULTS" -gt 0 ]]; then
    echo -e "  ${GREEN}Found $NUM_RESULTS documents with custom property${NC}"
else
    echo -e "  ${YELLOW}Query returned 0 results - may need Solr indexing${NC}"
fi

# Query by type only
TYPE_QUERY=$(cmis_post "" \
    -F "cmisaction=query" \
    -F "q=SELECT * FROM test:customDocument" \
    -F "maxItems=10")

run_test "Query all documents of custom type" "$TYPE_QUERY" '"numItems"'
TYPE_COUNT=$(echo "$TYPE_QUERY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('numItems', 0))" 2>/dev/null)
echo "  Found $TYPE_COUNT documents of type 'test:customDocument'"

# ===========================================
# Test 5: Secondary Type Tests
# ===========================================
echo ""
echo -e "${BLUE}[Section 5] Secondary Type Tests${NC}"

# Check if secondary type exists
SEC_TYPE_DEF=$(cmis_get "?cmisselector=typeDefinition&typeId=test:metadataAspect" 2>&1)

if [[ "$SEC_TYPE_DEF" == *"objectNotFound"* ]]; then
    echo -e "  ${YELLOW}WARNING: Secondary type 'test:metadataAspect' not loaded${NC}"
    echo -e "  ${YELLOW}This is a known issue - secondary types not properly cached${NC}"

    # Try to create secondary type
    echo "  Attempting to create secondary type..."
    SEC_CREATE=$(cmis_post "" \
        -F "cmisaction=createType" \
        -F 'type={"id":"nemaki:testAspect","localName":"testAspect","localNamespace":"http://www.aegif.jp/NEMAKI","displayName":"Test Aspect","queryName":"nemaki:testAspect","description":"Test secondary type","baseId":"cmis:secondary","parentId":"cmis:secondary","creatable":false,"fileable":false,"queryable":true,"fulltextIndexed":false,"includedInSupertypeQuery":true,"controllablePolicy":false,"controllableACL":false,"propertyDefinitions":{"nemaki:testProp":{"id":"nemaki:testProp","localName":"testProp","localNamespace":"http://www.aegif.jp/NEMAKI","displayName":"Test Property","queryName":"nemaki:testProp","description":"A test property","propertyType":"string","cardinality":"single","updatability":"readwrite","inherited":false,"required":false,"queryable":true,"orderable":true}}}')

    if [[ "$SEC_CREATE" == *"runtime"* ]] || [[ "$SEC_CREATE" == *"exception"* ]]; then
        echo -e "  ${RED}Secondary type creation failed: Known baseId issue${NC}"
        total_tests=$((total_tests + 1))
        failed_tests="$failed_tests\n  - Secondary type creation (baseId null issue)"
    else
        run_test "Secondary type creation" "$SEC_CREATE" '"id":"nemaki:testAspect"'
    fi
else
    run_test "Secondary type 'test:metadataAspect' exists" "$SEC_TYPE_DEF" '"id":"test:metadataAspect"'

    # Try to add secondary type to document
    if [[ -n "$DOC_ID" ]]; then
        # Refresh document info to get latest changeToken after Section 3 updates
        FRESH_DOC_INFO=$(cmis_get "/$DOC_ID?cmisselector=object")
        SEC_CHANGE_TOKEN=$(echo "$FRESH_DOC_INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['properties'].get('cmis:changeToken',{}).get('value',''))" 2>/dev/null)
        echo "  Secondary type update change token: ${SEC_CHANGE_TOKEN:-'(empty)'}"

        # Add secondary type (changeToken is required)
        if [[ -n "$SEC_CHANGE_TOKEN" ]]; then
            ADD_SEC_RESULT=$(cmis_post "" \
                -F "cmisaction=updateProperties" \
                -F "objectId=$DOC_ID" \
                -F "changeToken=$SEC_CHANGE_TOKEN" \
                -F "propertyId[0]=cmis:secondaryObjectTypeIds" \
                -F "propertyValue[0]=test:metadataAspect")
        else
            ADD_SEC_RESULT=$(cmis_post "" \
                -F "cmisaction=updateProperties" \
                -F "objectId=$DOC_ID" \
                -F "propertyId[0]=cmis:secondaryObjectTypeIds" \
                -F "propertyValue[0]=test:metadataAspect")
        fi

        run_test "Add secondary type to document" "$ADD_SEC_RESULT" 'cmis:secondaryObjectTypeIds'
    fi
fi

# ===========================================
# Test 6: Type Hierarchy Tests
# ===========================================
echo ""
echo -e "${BLUE}[Section 6] Type Hierarchy Tests${NC}"

# Get type descendants for cmis:document
DOC_DESCENDANTS=$(cmis_get "?cmisselector=typeDescendants&typeId=cmis:document&depth=1")
run_test "Get document type descendants" "$DOC_DESCENDANTS" '"test:customDocument"'

# Get type children
TYPE_CHILDREN=$(cmis_get "?cmisselector=typeChildren&typeId=cmis:document")
run_test "Get document type children" "$TYPE_CHILDREN" '"test:customDocument"'

# ===========================================
# Cleanup
# ===========================================
echo ""
echo -e "${BLUE}[Cleanup] Removing test documents...${NC}"

if [[ -n "$DOC_ID" ]]; then
    DELETE_RESULT=$(cmis_post "" \
        -F "cmisaction=delete" \
        -F "objectId=$DOC_ID")

    if [[ -z "$DELETE_RESULT" ]] || [[ "$DELETE_RESULT" == "{}" ]]; then
        echo "  Test document deleted successfully"
    else
        echo "  Delete result: $DELETE_RESULT"
    fi
fi

# ===========================================
# Summary
# ===========================================
echo ""
echo "========================================"
echo "  Test Summary"
echo "========================================"
echo ""
echo -e "Total tests: $total_tests"
echo -e "Passed: ${GREEN}$success_count${NC}"
echo -e "Failed: ${RED}$((total_tests - success_count))${NC}"

if [[ $success_count -eq $total_tests ]]; then
    echo ""
    echo -e "${GREEN}🎉 All tests passed!${NC}"
    exit 0
else
    echo ""
    echo -e "${RED}Failed tests:${NC}"
    echo -e "$failed_tests"
    exit 1
fi
