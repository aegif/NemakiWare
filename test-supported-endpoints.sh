#!/bin/bash

# Test supported CMIS Browser Binding endpoints only
# This script avoids unsupported object-specific POST URLs and tests only known-working endpoints

echo "=== NemakiWare Supported Endpoints Test ==="
echo "Timestamp: $(date)"
echo

# Test configuration
REPOSITORY_ID="bedroom"
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"
BASE_URL="http://localhost:8080/core"
AUTH="admin:admin"

# Function to test endpoint and capture response
test_endpoint() {
    local method="$1"
    local url="$2"
    local description="$3"
    local expected_code="$4"
    local post_data="$5"
    
    echo "Testing: $description"
    echo "  URL: $method $url"
    
    if [ "$method" = "GET" ]; then
        response_code=$(curl -s -o /tmp/test_response.json -w "%{http_code}" -u "$AUTH" "$url")
    elif [ "$method" = "POST" ] && [ ! -z "$post_data" ]; then
        response_code=$(curl -s -o /tmp/test_response.json -w "%{http_code}" -u "$AUTH" -X POST \
            -H "Content-Type: application/x-www-form-urlencoded" \
            -d "$post_data" \
            "$url")
    else
        echo "  ❌ Invalid test configuration"
        return 1
    fi
    
    if [ "$response_code" = "$expected_code" ]; then
        echo "  ✅ SUCCESS: HTTP $response_code"
        # Show first few lines of response for verification
        if [ -f "/tmp/test_response.json" ]; then
            echo "  Response preview:"
            head -3 /tmp/test_response.json | sed 's/^/    /'
        fi
        return 0
    else
        echo "  ❌ FAILED: Expected HTTP $expected_code, got HTTP $response_code"
        if [ -f "/tmp/test_response.json" ]; then
            echo "  Error response:"
            head -3 /tmp/test_response.json | sed 's/^/    /'
        fi
        return 1
    fi
}

# Clean up any previous test files
rm -f /tmp/test_response.json /tmp/test_doc_*.json

echo "1. Basic Repository Information Tests:"
echo

# Test 1: Repository info (GET with cmisselector)
test_endpoint "GET" \
    "$BASE_URL/browser/$REPOSITORY_ID?cmisselector=repositoryInfo" \
    "Repository Information (cmisselector)" \
    "200"

echo

# Test 2: Root folder children (GET with cmisselector)  
test_endpoint "GET" \
    "$BASE_URL/browser/$REPOSITORY_ID/root?cmisselector=children&objectId=$ROOT_FOLDER_ID" \
    "Root Folder Children (cmisselector)" \
    "200"

echo

echo "2. Property Format Tests (propertyId/propertyValue arrays):"
echo

# Test 3: Create document with proper property array format (POST to repository level)
TIMESTAMP=$(date +%s)
DOC_NAME="test-supported-endpoint-$TIMESTAMP.txt"

CREATE_DOC_DATA="cmisaction=createDocument&folderId=$ROOT_FOLDER_ID&propertyId[0]=cmis:objectTypeId&propertyValue[0]=cmis:document&propertyId[1]=cmis:name&propertyValue[1]=$DOC_NAME"

echo "  Creating document with standard property array format..."
test_endpoint "POST" \
    "$BASE_URL/browser/$REPOSITORY_ID" \
    "Create Document (standard property arrays)" \
    "201" \
    "$CREATE_DOC_DATA"

if [ $? -eq 0 ]; then
    # Extract created document ID for cleanup
    CREATED_DOC_ID=$(cat /tmp/test_response.json | grep -o '"cmis:objectId"[^}]*"value":"[^"]*"' | sed 's/.*"value":"\([^"]*\)".*/\1/' | head -1)
    if [ ! -z "$CREATED_DOC_ID" ]; then
        echo "  📝 Created document ID: $CREATED_DOC_ID"
        echo "$CREATED_DOC_ID" > /tmp/test_doc_id.txt
    fi
    
    # Save full response for analysis
    cp /tmp/test_response.json /tmp/test_doc_create_response.json
    echo "  💾 Full response saved to /tmp/test_doc_create_response.json"
fi

echo

echo "3. JSONConverter Property Format Analysis:"
echo

if [ -f "/tmp/test_doc_create_response.json" ]; then
    echo "  Analyzing property format in create response..."
    
    # Check for propertyDefinitionId fields
    if grep -q "propertyDefinitionId" /tmp/test_doc_create_response.json; then
        echo "  ✅ propertyDefinitionId fields found in response"
        echo "  📊 Property format is JSONConverter compatible"
    else
        echo "  ⚠️  propertyDefinitionId fields NOT found in response"
        echo "  📊 Response may need property format fix for JSONConverter compatibility"
        
        # Show sample property structure
        echo "  Sample property structure:"
        grep -A 3 -B 1 '"cmis:objectId"' /tmp/test_doc_create_response.json | head -5 | sed 's/^/    /'
    fi
    
    # Check for value fields
    if grep -q '"value"' /tmp/test_doc_create_response.json; then
        echo "  ✅ Property values found in response"
    else
        echo "  ❌ Property values NOT found in response"
    fi
else
    echo "  ⚠️  No create response available for analysis"
fi

echo

echo "4. Multipart Content Upload Test:"
echo

if [ -f "/tmp/test_doc_id.txt" ]; then
    CREATED_DOC_ID=$(cat /tmp/test_doc_id.txt)
    
    # Test multipart content upload
    echo "  Testing multipart content upload to document: $CREATED_DOC_ID"
    
    # Create test content
    echo "Test content from supported endpoints test - $(date)" > /tmp/test_content.txt
    
    # Upload content using multipart form data
    CONTENT_RESPONSE=$(curl -s -o /tmp/test_content_response.json -w "%{http_code}" -u "$AUTH" \
        -X POST \
        -F "cmisaction=setContentStream" \
        -F "objectId=$CREATED_DOC_ID" \
        -F "content=@/tmp/test_content.txt;filename=test.txt" \
        "$BASE_URL/browser/$REPOSITORY_ID")
    
    if [ "$CONTENT_RESPONSE" = "200" ] || [ "$CONTENT_RESPONSE" = "201" ]; then
        echo "  ✅ Multipart content upload successful (HTTP $CONTENT_RESPONSE)"
    else
        echo "  ❌ Multipart content upload failed (HTTP $CONTENT_RESPONSE)"
        if [ -f "/tmp/test_content_response.json" ]; then
            echo "  Error details:"
            head -3 /tmp/test_content_response.json | sed 's/^/    /'
        fi
    fi
fi

echo

echo "5. Cleanup Test:"
echo

if [ -f "/tmp/test_doc_id.txt" ]; then
    CREATED_DOC_ID=$(cat /tmp/test_doc_id.txt)
    echo "  Cleaning up test document: $CREATED_DOC_ID"
    
    DELETE_RESPONSE=$(curl -s -o /tmp/test_delete_response.json -w "%{http_code}" -u "$AUTH" \
        -X POST \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "cmisaction=delete&objectId=$CREATED_DOC_ID" \
        "$BASE_URL/browser/$REPOSITORY_ID")
    
    if [ "$DELETE_RESPONSE" = "200" ] || [ "$DELETE_RESPONSE" = "204" ]; then
        echo "  ✅ Test document deleted successfully (HTTP $DELETE_RESPONSE)"
    else
        echo "  ⚠️  Test document deletion returned HTTP $DELETE_RESPONSE"
        echo "  Manual cleanup may be needed for document: $CREATED_DOC_ID"
    fi
fi

echo

echo "6. Servlet Activity Verification:"
echo

echo "  Checking for servlet activity in container logs..."
sleep 2
SERVLET_ACTIVITY=$(docker logs docker-core-1 --tail 20 | grep -E "(VERSION CHECK|NEMAKI SERVICE METHOD)" | tail -3)

if [ ! -z "$SERVLET_ACTIVITY" ]; then
    echo "  ✅ Custom servlet activity detected:"
    echo "$SERVLET_ACTIVITY" | sed 's/^/    /'
else
    echo "  ⚠️  No recent custom servlet activity found"
    echo "    This may indicate servlet code deployment issues"
fi

echo

echo "7. Test Summary:"
echo

# Count successful tests
TOTAL_TESTS=6
BASIC_TESTS=2
PROP_TESTS=1  
FORMAT_TESTS=1
MULTIPART_TESTS=1
CLEANUP_TESTS=1

echo "  Test Categories:"
echo "    Basic Repository Tests: $BASIC_TESTS tests"
echo "    Property Format Tests: $PROP_TESTS tests"  
echo "    JSONConverter Analysis: $FORMAT_TESTS analysis"
echo "    Multipart Upload Tests: $MULTIPART_TESTS tests"
echo "    Cleanup Tests: $CLEANUP_TESTS tests"
echo "    Servlet Activity Check: 1 check"

echo
echo "  Key Findings:"
if [ -f "/tmp/test_doc_create_response.json" ]; then
    if grep -q "propertyDefinitionId" /tmp/test_doc_create_response.json; then
        echo "    ✅ Response format is JSONConverter compatible"
    else
        echo "    ⚠️  Response format needs JSONConverter compatibility fix"
    fi
else
    echo "    ❌ Document creation failed - needs investigation"
fi

echo
echo "  Recommendations:"
echo "    1. Use only repository-level POST URLs: /browser/{repositoryId}"
echo "    2. Always use propertyId[N]/propertyValue[N] array format"
echo "    3. Use cmisselector parameter for GET operations"
echo "    4. Use cmisaction parameter for POST operations"
echo "    5. Verify all responses include propertyDefinitionId fields"

# Cleanup temp files
rm -f /tmp/test_*.txt /tmp/test_*.json

echo
echo "=== Supported Endpoints Test Complete ==="