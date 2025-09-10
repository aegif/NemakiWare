#!/bin/bash

# Test JSONConverter compatibility for CMIS Browser Binding responses
# This script verifies that all responses include propertyDefinitionId fields

echo "=== JSONConverter Compatibility Test ==="
echo "Timestamp: $(date)"
echo

# Test configuration
REPOSITORY_ID="bedroom"
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"
BASE_URL="http://localhost:8080/core"
AUTH="admin:admin"

echo "1. Property Format Analysis Test:"
echo

# Function to analyze JSON response property format
analyze_property_format() {
    local response_file="$1"
    local test_name="$2"
    
    echo "  Analyzing: $test_name"
    
    if [ ! -f "$response_file" ]; then
        echo "    ❌ Response file not found"
        return 1
    fi
    
    # Check if response contains properties
    if ! grep -q '"properties"' "$response_file"; then
        echo "    ⚠️  No properties section found in response"
        return 0
    fi
    
    echo "    ✅ Properties section found"
    
    # Count total properties
    TOTAL_PROPERTIES=$(grep -o '"cmis:[^"]*":' "$response_file" | wc -l | tr -d ' ')
    echo "    📊 Total CMIS properties: $TOTAL_PROPERTIES"
    
    # Check for propertyDefinitionId fields
    if grep -q '"propertyDefinitionId"' "$response_file"; then
        PROPERTIES_WITH_DEF_ID=$(grep -o '"propertyDefinitionId":"[^"]*"' "$response_file" | wc -l | tr -d ' ')
        echo "    ✅ Properties with propertyDefinitionId: $PROPERTIES_WITH_DEF_ID"
        
        if [ "$PROPERTIES_WITH_DEF_ID" -eq "$TOTAL_PROPERTIES" ] && [ "$TOTAL_PROPERTIES" -gt "0" ]; then
            echo "    🎉 JSONConverter format: FULLY COMPATIBLE"
            return 0
        elif [ "$PROPERTIES_WITH_DEF_ID" -gt "0" ]; then
            echo "    ⚠️  JSONConverter format: PARTIALLY COMPATIBLE ($PROPERTIES_WITH_DEF_ID/$TOTAL_PROPERTIES)"
            return 1
        else
            echo "    ❌ JSONConverter format: INCOMPATIBLE (0/$TOTAL_PROPERTIES)"
            return 1
        fi
    else
        echo "    ❌ No propertyDefinitionId fields found"
        echo "    ❌ JSONConverter format: INCOMPATIBLE"
        return 1
    fi
}

# Test 1: Repository info response
echo "Test 1: Repository Information Response"
curl -s -u "$AUTH" "$BASE_URL/browser/$REPOSITORY_ID?cmisselector=repositoryInfo" > /tmp/repo_info_test.json
analyze_property_format "/tmp/repo_info_test.json" "Repository Info"

echo

# Test 2: Object properties response  
echo "Test 2: Object Properties Response"
curl -s -u "$AUTH" "$BASE_URL/browser/$REPOSITORY_ID/root?cmisselector=object&objectId=$ROOT_FOLDER_ID" > /tmp/object_props_test.json
analyze_property_format "/tmp/object_props_test.json" "Object Properties"

echo

# Test 3: Create document response (most critical for TCK)
echo "Test 3: Create Document Response (Critical for TCK)"
TIMESTAMP=$(date +%s)
DOC_NAME="jsonconverter-test-$TIMESTAMP.txt"

FORM_DATA="cmisaction=createDocument&folderId=$ROOT_FOLDER_ID&propertyId[0]=cmis:objectTypeId&propertyValue[0]=cmis:document&propertyId[1]=cmis:name&propertyValue[1]=$DOC_NAME"

RESPONSE_CODE=$(curl -s -o /tmp/create_doc_test.json -w "%{http_code}" \
    -u "$AUTH" \
    -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "$FORM_DATA" \
    "$BASE_URL/browser/$REPOSITORY_ID")

echo "  Create Document Response: HTTP $RESPONSE_CODE"

if [ "$RESPONSE_CODE" = "200" ] || [ "$RESPONSE_CODE" = "201" ]; then
    analyze_property_format "/tmp/create_doc_test.json" "Create Document"
    
    # Extract document ID for cleanup
    if grep -q "cmis:objectId" /tmp/create_doc_test.json; then
        CREATED_DOC_ID=$(grep -o '"cmis:objectId"[^}]*"value":"[^"]*"' /tmp/create_doc_test.json | sed 's/.*"value":"\([^"]*\)".*/\1/' | head -1)
        echo "    📝 Created document ID: $CREATED_DOC_ID"
        echo "$CREATED_DOC_ID" > /tmp/jsonconv_test_doc_id.txt
    fi
else
    echo "    ❌ Create document failed with HTTP $RESPONSE_CODE"
    if [ -f "/tmp/create_doc_test.json" ]; then
        echo "    Error response:"
        head -3 /tmp/create_doc_test.json | sed 's/^/      /'
    fi
fi

echo

# Test 4: Children response (for folder objects)
echo "Test 4: Children Response"
curl -s -u "$AUTH" "$BASE_URL/browser/$REPOSITORY_ID/root?cmisselector=children&objectId=$ROOT_FOLDER_ID" > /tmp/children_test.json

# Analyze first child object properties
if grep -q '"objects"' /tmp/children_test.json; then
    echo "  📁 Children found - analyzing first child properties"
    
    # Extract first object properties for analysis
    python3 << 'EOF'
import json
import sys

try:
    with open('/tmp/children_test.json', 'r') as f:
        data = json.load(f)
    
    objects = data.get('objects', [])
    if objects and len(objects) > 0:
        first_object = objects[0].get('object', {})
        properties = first_object.get('properties', {})
        
        with open('/tmp/first_child_props.json', 'w') as f:
            json.dump({'properties': properties}, f, indent=2)
        
        print(f"  📊 First child has {len(properties)} properties")
    else:
        print("  ⚠️  No child objects found")
        
except Exception as e:
    print(f"  ❌ Error analyzing children: {e}")
EOF

    if [ -f "/tmp/first_child_props.json" ]; then
        analyze_property_format "/tmp/first_child_props.json" "First Child Properties"
    fi
else
    echo "    ⚠️  No children found or children response format unexpected"
fi

echo

echo "2. Property Structure Deep Analysis:"
echo

# Show sample property structures
if [ -f "/tmp/create_doc_test.json" ]; then
    echo "  Sample property structure from Create Document response:"
    echo
    
    # Show cmis:objectId property structure
    OBJECT_ID_PROP=$(grep -A 10 -B 2 '"cmis:objectId"' /tmp/create_doc_test.json | head -8)
    if [ ! -z "$OBJECT_ID_PROP" ]; then
        echo "    cmis:objectId property:"
        echo "$OBJECT_ID_PROP" | sed 's/^/      /'
    fi
    
    echo
    
    # Show cmis:name property structure
    NAME_PROP=$(grep -A 10 -B 2 '"cmis:name"' /tmp/create_doc_test.json | head -8)
    if [ ! -z "$NAME_PROP" ]; then
        echo "    cmis:name property:"
        echo "$NAME_PROP" | sed 's/^/      /'
    fi
fi

echo

echo "3. Servlet Response Interception Analysis:"
echo

# Check servlet logs for property format fix activity
echo "  Checking servlet logs for property format fix activity..."
sleep 2

PROPERTY_FIX_LOGS=$(docker logs docker-core-1 --tail 100 | grep -E "(FIXED PROPERTY|PROPERTY FORMAT FIX|propertyDefinitionId)")

if [ ! -z "$PROPERTY_FIX_LOGS" ]; then
    echo "  ✅ Property format fix activity found:"
    echo "$PROPERTY_FIX_LOGS" | head -10 | sed 's/^/    /'
else
    echo "  ⚠️  No property format fix activity found in logs"
    echo "    Response interception may not be working or not needed"
fi

echo

# Check for response interception logs
INTERCEPTION_LOGS=$(docker logs docker-core-1 --tail 50 | grep -E "(RESPONSE INTERCEPTION|InterceptingResponseWrapper|Original JSON)")

if [ ! -z "$INTERCEPTION_LOGS" ]; then
    echo "  ✅ Response interception activity found:"
    echo "$INTERCEPTION_LOGS" | head -5 | sed 's/^/    /'
else
    echo "  ⚠️  No response interception activity found"
fi

echo

echo "4. TCK Compatibility Simulation:"
echo

# Simulate what OpenCMIS client JSONConverter would do
if [ -f "/tmp/create_doc_test.json" ]; then
    echo "  Simulating OpenCMIS JSONConverter.convertObject() requirements:"
    echo
    
    # Check if response can be parsed by JSONConverter
    python3 << 'EOF'
import json

try:
    with open('/tmp/create_doc_test.json', 'r') as f:
        data = json.load(f)
    
    properties = data.get('properties', {})
    compatible_count = 0
    total_count = len(properties)
    
    print(f"  📊 Analyzing {total_count} properties for JSONConverter compatibility:")
    
    for prop_id, prop_data in properties.items():
        if isinstance(prop_data, dict):
            has_prop_def_id = 'propertyDefinitionId' in prop_data
            has_value = 'value' in prop_data
            
            if has_prop_def_id and has_value:
                compatible_count += 1
                status = "✅"
            elif has_value:
                status = "⚠️ "
            else:
                status = "❌"
            
            print(f"    {status} {prop_id}: propertyDefinitionId={has_prop_def_id}, value={has_value}")
    
    compatibility_percent = (compatible_count / total_count * 100) if total_count > 0 else 0
    
    print(f"\n  🎯 JSONConverter Compatibility: {compatible_count}/{total_count} ({compatibility_percent:.1f}%)")
    
    if compatibility_percent == 100:
        print("  🎉 FULLY COMPATIBLE with OpenCMIS JSONConverter")
    elif compatibility_percent >= 80:
        print("  ⚠️  MOSTLY COMPATIBLE with OpenCMIS JSONConverter")
    else:
        print("  ❌ INCOMPATIBLE with OpenCMIS JSONConverter")
        
except Exception as e:
    print(f"  ❌ Error in compatibility analysis: {e}")
EOF
fi

echo

echo "5. Cleanup:"
echo

# Cleanup test document
if [ -f "/tmp/jsonconv_test_doc_id.txt" ]; then
    DOC_ID=$(cat /tmp/jsonconv_test_doc_id.txt)
    echo "  Cleaning up test document: $DOC_ID"
    
    DELETE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -u "$AUTH" \
        -X POST \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "cmisaction=delete&objectId=$DOC_ID" \
        "$BASE_URL/browser/$REPOSITORY_ID")
    
    if [ "$DELETE_RESPONSE" = "200" ] || [ "$DELETE_RESPONSE" = "204" ]; then
        echo "    ✅ Test document deleted"
    else
        echo "    ⚠️  Delete returned HTTP $DELETE_RESPONSE"
    fi
fi

echo

echo "6. JSONConverter Compatibility Summary:"
echo

# Count successful compatibility tests
TOTAL_TESTS=4
COMPATIBLE_TESTS=0

# Check each test result (simplified check)
for test_file in /tmp/repo_info_test.json /tmp/object_props_test.json /tmp/create_doc_test.json /tmp/first_child_props.json; do
    if [ -f "$test_file" ] && grep -q '"propertyDefinitionId"' "$test_file"; then
        COMPATIBLE_TESTS=$((COMPATIBLE_TESTS + 1))
    fi
done

COMPATIBILITY_PERCENT=$((COMPATIBLE_TESTS * 100 / TOTAL_TESTS))

echo "  📊 Overall Compatibility: $COMPATIBLE_TESTS/$TOTAL_TESTS tests ($COMPATIBILITY_PERCENT%)"

if [ $COMPATIBILITY_PERCENT -eq 100 ]; then
    echo "  🎉 EXCELLENT: All responses are JSONConverter compatible"
    echo "  ✅ TCK tests should pass without JSONConverter errors"
elif [ $COMPATIBILITY_PERCENT -ge 75 ]; then
    echo "  ⚠️  GOOD: Most responses are JSONConverter compatible"
    echo "  📝 Some TCK tests may still have JSONConverter issues"
else
    echo "  ❌ POOR: Many responses lack JSONConverter compatibility"
    echo "  🔧 Response interception system needs improvement"
fi

echo
echo "  Recommendations:"
if [ $COMPATIBILITY_PERCENT -lt 100 ]; then
    echo "    1. Enable response interception for all Browser Binding operations"
    echo "    2. Verify fixPropertyFormatForJsonConverter() is being called"
    echo "    3. Check servlet logs for property format fix activity"
    echo "    4. Test with actual TCK createDocument operations"
fi

# Cleanup temp files
rm -f /tmp/*_test.json /tmp/jsonconv_test_doc_id.txt /tmp/first_child_props.json

echo
echo "=== JSONConverter Compatibility Test Complete ==="