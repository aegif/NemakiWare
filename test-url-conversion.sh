#!/bin/bash

# Test URL-to-Parameter conversion for object-specific URLs
# This script tests the servlet's ability to handle TCK-style object-specific POST requests

echo "=== URL-to-Parameter Conversion Test ==="
echo "Timestamp: $(date)"
echo

# Test configuration
REPOSITORY_ID="bedroom"
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"
BASE_URL="http://localhost:8080/core"
AUTH="admin:admin"

echo "1. Testing Object-Specific URL Patterns:"
echo

# Test 1: Object-specific POST URL (this should trigger conversion)
TIMESTAMP=$(date +%s)
DOC_NAME="url-conversion-test-$TIMESTAMP.txt"

echo "  Test 1a: Object-specific createDocument URL"
echo "  URL Pattern: POST /browser/{repositoryId}/{objectId}"
echo "  Expected: Should convert objectId to folderId parameter"

OBJECT_SPECIFIC_URL="$BASE_URL/browser/$REPOSITORY_ID/$ROOT_FOLDER_ID"
echo "  Testing URL: $OBJECT_SPECIFIC_URL"

# Use form data that TCK would send
FORM_DATA="cmisaction=createDocument&propertyId[0]=cmis:objectTypeId&propertyValue[0]=cmis:document&propertyId[1]=cmis:name&propertyValue[1]=$DOC_NAME"

echo "  Form Data: $FORM_DATA"

# Test the object-specific URL
RESPONSE_CODE=$(curl -s -o /tmp/url_conversion_test.json -w "%{http_code}" \
    -u "$AUTH" \
    -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "$FORM_DATA" \
    "$OBJECT_SPECIFIC_URL")

echo "  Response Code: HTTP $RESPONSE_CODE"

if [ "$RESPONSE_CODE" = "200" ] || [ "$RESPONSE_CODE" = "201" ]; then
    echo "  ✅ Object-specific URL handled successfully"
    
    # Check if document was created
    if [ -f "/tmp/url_conversion_test.json" ]; then
        echo "  📄 Response received - checking for document creation..."
        if grep -q "cmis:objectId" /tmp/url_conversion_test.json; then
            CREATED_DOC_ID=$(grep -o '"cmis:objectId"[^}]*"value":"[^"]*"' /tmp/url_conversion_test.json | sed 's/.*"value":"\([^"]*\)".*/\1/' | head -1)
            echo "  ✅ Document created with ID: $CREATED_DOC_ID"
            echo "$CREATED_DOC_ID" > /tmp/url_test_doc_id.txt
        else
            echo "  ⚠️  Response doesn't contain expected objectId"
            echo "  First few lines of response:"
            head -3 /tmp/url_conversion_test.json | sed 's/^/    /'
        fi
    fi
else
    echo "  ❌ Object-specific URL failed with HTTP $RESPONSE_CODE"
    if [ -f "/tmp/url_conversion_test.json" ]; then
        echo "  Error response:"
        head -3 /tmp/url_conversion_test.json | sed 's/^/    /'
    fi
fi

echo

# Test 2: Standard repository-level URL (for comparison)
echo "  Test 1b: Standard repository-level createDocument URL (comparison)"
echo "  URL Pattern: POST /browser/{repositoryId}"
echo "  Expected: Should work with explicit folderId parameter"

STANDARD_URL="$BASE_URL/browser/$REPOSITORY_ID"
STANDARD_FORM_DATA="cmisaction=createDocument&folderId=$ROOT_FOLDER_ID&propertyId[0]=cmis:objectTypeId&propertyValue[0]=cmis:document&propertyId[1]=cmis:name&propertyValue[1]=standard-$DOC_NAME"

echo "  Testing URL: $STANDARD_URL"
echo "  Form Data: $STANDARD_FORM_DATA"

STANDARD_RESPONSE=$(curl -s -o /tmp/standard_url_test.json -w "%{http_code}" \
    -u "$AUTH" \
    -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "$STANDARD_FORM_DATA" \
    "$STANDARD_URL")

echo "  Response Code: HTTP $STANDARD_RESPONSE"

if [ "$STANDARD_RESPONSE" = "200" ] || [ "$STANDARD_RESPONSE" = "201" ]; then
    echo "  ✅ Standard URL works as expected"
    
    if [ -f "/tmp/standard_url_test.json" ]; then
        if grep -q "cmis:objectId" /tmp/standard_url_test.json; then
            STANDARD_DOC_ID=$(grep -o '"cmis:objectId"[^}]*"value":"[^"]*"' /tmp/standard_url_test.json | sed 's/.*"value":"\([^"]*\)".*/\1/' | head -1)
            echo "  ✅ Standard document created with ID: $STANDARD_DOC_ID"
            echo "$STANDARD_DOC_ID" > /tmp/standard_test_doc_id.txt
        fi
    fi
else
    echo "  ❌ Standard URL failed with HTTP $STANDARD_RESPONSE"
fi

echo

echo "2. Servlet Activity Analysis:"
echo

# Check servlet logs for URL conversion activity
echo "  Checking servlet logs for URL conversion activity..."
sleep 2

# Look for URL conversion debug messages
CONVERSION_LOGS=$(docker logs docker-core-1 --tail 50 | grep -E "(URL-TO-PARAMETER|CONVERSION TRIGGERED|New RequestURI)")

if [ ! -z "$CONVERSION_LOGS" ]; then
    echo "  ✅ URL conversion activity found in logs:"
    echo "$CONVERSION_LOGS" | head -10 | sed 's/^/    /'
else
    echo "  ⚠️  No URL conversion activity found in logs"
    echo "    This suggests the conversion logic may not have been triggered"
fi

echo

# Check for parameter analysis logs
PARAM_LOGS=$(docker logs docker-core-1 --tail 50 | grep -E "(PARAMETER VERIFICATION|folderId parameter|ALL PARAMETERS)")

if [ ! -z "$PARAM_LOGS" ]; then
    echo "  ✅ Parameter analysis logs found:"
    echo "$PARAM_LOGS" | head -5 | sed 's/^/    /'
else
    echo "  ⚠️  No parameter analysis logs found"
fi

echo

echo "3. TCK Compatibility Test:"
echo

# Test the pattern that TCK actually uses
echo "  Testing TCK-style request patterns..."

# Test various object-specific URL patterns that TCK might use
echo "  Test 3a: TCK createDocument (object-specific URL without content)"

TCK_FORM_DATA="cmisaction=createDocument&propertyId[0]=cmis:objectTypeId&propertyValue[0]=cmis:document&propertyId[1]=cmis:name&propertyValue[1]=tck-style-$TIMESTAMP.txt"

TCK_RESPONSE=$(curl -s -o /tmp/tck_test.json -w "%{http_code}" \
    -u "$AUTH" \
    -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "$TCK_FORM_DATA" \
    "$OBJECT_SPECIFIC_URL")

echo "  TCK-style Response: HTTP $TCK_RESPONSE"

if [ "$TCK_RESPONSE" = "200" ] || [ "$TCK_RESPONSE" = "201" ]; then
    echo "  ✅ TCK-style request succeeded"
    
    if [ -f "/tmp/tck_test.json" ] && grep -q "propertyDefinitionId" /tmp/tck_test.json; then
        echo "  ✅ Response contains propertyDefinitionId (JSONConverter compatible)"
    elif [ -f "/tmp/tck_test.json" ] && grep -q "cmis:objectId" /tmp/tck_test.json; then
        echo "  ⚠️  Response contains objectId but missing propertyDefinitionId"
        echo "      JSONConverter compatibility may need fixing"
    fi
else
    echo "  ❌ TCK-style request failed with HTTP $TCK_RESPONSE"
    if [ -f "/tmp/tck_test.json" ]; then
        echo "  Error details:"
        head -3 /tmp/tck_test.json | sed 's/^/    /'
        
        # Check if this is the "folderId must be set" error
        if grep -q "folderId must be set" /tmp/tck_test.json; then
            echo "  🔍 DIAGNOSIS: 'folderId must be set' error detected"
            echo "     This indicates URL-to-Parameter conversion is not working properly"
            echo "     The objectId from URL path should be converted to folderId parameter"
        fi
    fi
fi

echo

echo "4. Cleanup Test Documents:"
echo

# Cleanup created documents
for doc_file in /tmp/url_test_doc_id.txt /tmp/standard_test_doc_id.txt; do
    if [ -f "$doc_file" ]; then
        DOC_ID=$(cat "$doc_file")
        echo "  Cleaning up document: $DOC_ID"
        
        DELETE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
            -u "$AUTH" \
            -X POST \
            -H "Content-Type: application/x-www-form-urlencoded" \
            -d "cmisaction=delete&objectId=$DOC_ID" \
            "$BASE_URL/browser/$REPOSITORY_ID")
        
        if [ "$DELETE_RESPONSE" = "200" ] || [ "$DELETE_RESPONSE" = "204" ]; then
            echo "    ✅ Deleted successfully"
        else
            echo "    ⚠️  Delete returned HTTP $DELETE_RESPONSE"
        fi
    fi
done

echo

echo "5. URL Conversion Test Summary:"
echo

# Analyze results
if [ "$RESPONSE_CODE" = "200" ] || [ "$RESPONSE_CODE" = "201" ]; then
    if [ "$TCK_RESPONSE" = "200" ] || [ "$TCK_RESPONSE" = "201" ]; then
        echo "  ✅ URL-to-Parameter conversion is working correctly"
        echo "  ✅ Both object-specific and TCK-style requests succeed"
    else
        echo "  ⚠️  Basic conversion works but TCK compatibility needs improvement"
    fi
else
    if [ "$STANDARD_RESPONSE" = "200" ] || [ "$STANDARD_RESPONSE" = "201" ]; then
        echo "  ❌ URL-to-Parameter conversion is not working"
        echo "  ✅ Standard repository-level URLs work fine"
        echo "  🔧 Need to fix object-specific URL handling"
    else
        echo "  ❌ Both object-specific and standard URLs are failing"
        echo "  🔧 Fundamental CMIS Browser Binding issue"
    fi
fi

echo
echo "  Recommendations:"
if [ "$RESPONSE_CODE" != "200" ] && [ "$RESPONSE_CODE" != "201" ]; then
    echo "    1. Check servlet logs for URL conversion debug messages"
    echo "    2. Verify object-specific URL pattern matching logic"
    echo "    3. Ensure folderId parameter is properly injected"
fi

if [ "$TCK_RESPONSE" != "200" ] && [ "$TCK_RESPONSE" != "201" ]; then
    echo "    1. Debug TCK-style parameter format requirements"
    echo "    2. Check for 'folderId must be set' errors in logs"
    echo "    3. Verify OpenCMIS service parameter expectations"
fi

# Cleanup temp files
rm -f /tmp/url_*.json /tmp/standard_*.json /tmp/tck_*.json /tmp/*_test_doc_id.txt

echo
echo "=== URL Conversion Test Complete ==="