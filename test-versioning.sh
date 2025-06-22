#!/bin/bash

# Versioning Test Script
echo "========================================"
echo "NemakiWare Versioning Test Investigation"
echo "========================================"

# Check if Docker environment is running
if ! docker compose -f docker-compose-simple.yml ps | grep -q "Up"; then
    echo "Error: Docker environment is not running"
    echo "Please run './test-simple.sh' first"
    exit 1
fi

echo "Testing CMIS endpoints for versioning capability..."

# Test basic document creation with content
echo "1. Testing basic document creation..."
CREATE_RESPONSE=$(curl -s -u admin:admin -X POST \
    -H "Content-Type: application/atom+xml;type=entry" \
    -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>Test Versioning Document</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
                <cmis:value>cmis:document</cmis:value>
            </cmis:propertyId>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>versioning-test-doc.txt</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
    <cmisra:content>
        <cmisra:mediatype>text/plain</cmisra:mediatype>
        <cmisra:base64>VGVzdCBjb250ZW50IGZvciB2ZXJzaW9uaW5nIHRlc3Q=</cmisra:base64>
    </cmisra:content>
</atom:entry>' \
    "http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff")

if echo "$CREATE_RESPONSE" | grep -q "error"; then
    echo "✗ Document creation failed"
    echo "Response: $CREATE_RESPONSE"
    exit 1
else
    echo "✓ Document creation successful"
fi

# Extract document ID from response
DOC_ID=$(echo "$CREATE_RESPONSE" | grep -o 'cmis:objectId[^<]*<cmis:value>[^<]*' | sed 's/.*<cmis:value>//')

if [ -z "$DOC_ID" ]; then
    echo "✗ Could not extract document ID"
    exit 1
fi

echo "Document ID: $DOC_ID"

# Test versioning properties
echo "2. Testing versioning properties..."
VERSION_RESPONSE=$(curl -s -u admin:admin \
    "http://localhost:8080/core/atom/bedroom/id?id=$DOC_ID&filter=cmis:isVersionSeriesCheckedOut,cmis:versionSeriesId,cmis:versionSeriesCheckedOutBy,cmis:isPrivateWorkingCopy,cmis:isLatestVersion,cmis:isMajorVersion")

echo "Versioning properties response:"
echo "$VERSION_RESPONSE" | grep -E "(cmis:isVersionSeriesCheckedOut|cmis:versionSeriesId|cmis:isLatestVersion|cmis:isMajorVersion)" || echo "No versioning properties found"

# Test check-out operation
echo "3. Testing check-out operation..."
CHECKOUT_RESPONSE=$(curl -s -u admin:admin -X POST \
    "http://localhost:8080/core/atom/bedroom/id/$DOC_ID/checkout")

if echo "$CHECKOUT_RESPONSE" | grep -q "error"; then
    echo "✗ Check-out failed"
    echo "Response: $CHECKOUT_RESPONSE"
else
    echo "✓ Check-out operation completed"
    echo "Response summary: $(echo "$CHECKOUT_RESPONSE" | head -5)"
fi

# Test version series query
echo "4. Testing version series query..."
VERSION_QUERY='SELECT * FROM cmis:document WHERE cmis:versionSeriesId = '"'"'$DOC_ID'"'"''
QUERY_RESPONSE=$(curl -s -u admin:admin -G \
    --data-urlencode "q=$VERSION_QUERY" \
    --data-urlencode "maxItems=10" \
    "http://localhost:8080/core/atom/bedroom/query")

if echo "$QUERY_RESPONSE" | grep -q "error"; then
    echo "✗ Version series query failed"
    echo "Response: $QUERY_RESPONSE"
else
    echo "✓ Version series query executed"
    VERSION_COUNT=$(echo "$QUERY_RESPONSE" | grep -c "cmis:document" || echo "0")
    echo "Found $VERSION_COUNT version(s)"
fi

# Test getAllVersions operation
echo "5. Testing getAllVersions operation..."
ALL_VERSIONS_RESPONSE=$(curl -s -u admin:admin \
    "http://localhost:8080/core/atom/bedroom/id/$DOC_ID/versions")

if echo "$ALL_VERSIONS_RESPONSE" | grep -q "error"; then
    echo "✗ getAllVersions failed"
    echo "Response: $ALL_VERSIONS_RESPONSE"
else
    echo "✓ getAllVersions operation completed"
    VERSION_ENTRIES=$(echo "$ALL_VERSIONS_RESPONSE" | grep -c "<atom:entry>" || echo "0")
    echo "Found $VERSION_ENTRIES version entries"
fi

echo "========================================"
echo "Versioning Test Investigation Complete"
echo "========================================"