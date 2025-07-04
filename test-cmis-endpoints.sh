#!/bin/bash

echo "Testing CMIS Endpoints - Current Status"
echo "======================================="

# Base URL
BASE_URL="http://localhost:8080/core"
AUTH="admin:admin"

echo -e "\n1. Testing AtomPub Service Document"
echo "Endpoint: $BASE_URL/atom"
curl -s -u $AUTH -o /tmp/atom-service.xml "$BASE_URL/atom"
if [ -f /tmp/atom-service.xml ]; then
    if grep -q "service" /tmp/atom-service.xml; then
        echo "✓ AtomPub service document accessible"
        grep -o '<collection.*href="[^"]*"' /tmp/atom-service.xml | head -3
    else
        echo "✗ Invalid response"
        head -20 /tmp/atom-service.xml
    fi
fi

echo -e "\n2. Testing AtomPub Repository Info"
echo "Endpoint: $BASE_URL/atom/bedroom"
STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" "$BASE_URL/atom/bedroom")
echo "HTTP Status: $STATUS"
if [ "$STATUS" = "200" ]; then
    curl -s -u $AUTH "$BASE_URL/atom/bedroom" | grep -E "repositoryId|repositoryName" | head -5
fi

echo -e "\n3. Testing Browser Binding"
echo "Endpoint: $BASE_URL/browser/bedroom"
STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" "$BASE_URL/browser/bedroom")
echo "HTTP Status: $STATUS"

echo -e "\n4. Testing Simple Query"
echo "Query: SELECT * FROM cmis:folder WHERE cmis:name='root'"
QUERY_URL="$BASE_URL/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis%3Afolder%20WHERE%20cmis%3Aname%3D'root'"
curl -s -u $AUTH "$QUERY_URL" -o /tmp/query-result.xml
if [ -f /tmp/query-result.xml ]; then
    if grep -q "feed" /tmp/query-result.xml; then
        echo "✓ Query executed successfully"
        ENTRIES=$(grep -c "<entry>" /tmp/query-result.xml)
        echo "Found $ENTRIES entries"
    else
        echo "✗ Query failed"
        head -20 /tmp/query-result.xml
    fi
fi

echo -e "\n5. Testing Root Folder Access"
echo "Endpoint: $BASE_URL/atom/bedroom/root"
STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" "$BASE_URL/atom/bedroom/root")
echo "HTTP Status: $STATUS"

echo -e "\n======================================="
echo "Test completed"

# Cleanup
rm -f /tmp/atom-service.xml /tmp/query-result.xml