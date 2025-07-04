#!/bin/bash

echo "Testing CMIS Query functionality..."
echo "================================="

# Test basic CMIS endpoints
echo -e "\n1. Testing CMIS AtomPub endpoint..."
ATOM_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom)
echo "AtomPub status: $ATOM_STATUS"

# Test simple folder query using GET
echo -e "\n2. Testing folder query with GET..."
echo "Query: SELECT * FROM cmis:folder"
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis%3Afolder&maxItems=2" -o query_result.xml

if [ -f query_result.xml ]; then
    if grep -q "<feed" query_result.xml; then
        echo "✓ Query returned valid ATOM feed"
        ENTRIES=$(grep -c "<entry>" query_result.xml)
        echo "✓ Found $ENTRIES folder entries"
    else
        echo "✗ Query failed - checking error:"
        cat query_result.xml | head -20
    fi
else
    echo "✗ No response received"
fi

# Test document query
echo -e "\n3. Testing document query..."
echo "Query: SELECT cmis:name FROM cmis:document"
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20cmis%3Aname%20FROM%20cmis%3Adocument&maxItems=2" -o doc_query_result.xml

if [ -f doc_query_result.xml ]; then
    if grep -q "<feed" doc_query_result.xml; then
        echo "✓ Document query returned valid ATOM feed"
        DOC_ENTRIES=$(grep -c "<entry>" doc_query_result.xml)
        echo "✓ Found $DOC_ENTRIES document entries"
    else
        echo "✗ Document query failed"
    fi
fi

# Test query with WHERE clause
echo -e "\n4. Testing query with WHERE clause..."
echo "Query: SELECT * FROM cmis:folder WHERE cmis:name = 'root'"
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis%3Afolder%20WHERE%20cmis%3Aname%20%3D%20%27root%27" -o where_query_result.xml

if [ -f where_query_result.xml ]; then
    if grep -q "<feed" where_query_result.xml; then
        echo "✓ WHERE clause query returned valid ATOM feed"
    else
        echo "✗ WHERE clause query failed"
    fi
fi

echo -e "\n================================="
echo "Query test completed"

# Clean up
rm -f query_result.xml doc_query_result.xml where_query_result.xml