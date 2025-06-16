#!/bin/bash

echo "=== Testing CMIS Query Fixes ==="

CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

if [ -z "$CORE_CONTAINER" ]; then
    echo "ERROR: Core container not found"
    exit 1
fi

echo "Using core container: $CORE_CONTAINER"

echo "0. Fixing Solr connectivity and repository ID issues:"
./fix-solr-connectivity.sh

echo -e "\n1. Checking Solr index status:"
curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&wt=json&rows=0" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'Solr index contains {data[\"response\"][\"numFound\"]} documents')
except Exception as e:
    print(f'Error: {e}')
"

echo -e "\n1.1. Testing Solr connectivity from core container:"
docker exec $CORE_CONTAINER curl -s "http://solr:8080/solr/nemaki/select?q=*:*&wt=json&rows=0" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'Core container can access Solr: {data[\"response\"][\"numFound\"]} documents')
except Exception as e:
    print(f'Core container Solr access failed: {e}')
" || echo "Core container cannot access Solr"

echo -e "\n2. Testing root folder query via CMIS AtomPub:"
QUERY_RESULT=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20cmis:name%20AS%20folderName,%20cmis:objectId%20AS%20folderId%20FROM%20cmis:folder%20WHERE%20cmis:objectId%20=%20'$ROOT_FOLDER_ID'")
echo "$QUERY_RESULT" | head -10

if echo "$QUERY_RESULT" | grep -q "numItems.*[1-9]"; then
    echo "✓ Root folder query returned results"
else
    echo "✗ Root folder query returned no results"
fi

echo -e "\n3. Testing simple folder query:"
FOLDER_QUERY=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis:folder")
echo "$FOLDER_QUERY" | head -10

if echo "$FOLDER_QUERY" | grep -q "numItems.*[1-9]"; then
    echo "✓ Folder query returned results"
else
    echo "✗ Folder query returned no results"
fi

echo -e "\n4. Testing individual queries:"
./test-individual-queries.sh

echo -e "\n5. Checking NemakiWare logs for query processing:"
docker logs $CORE_CONTAINER --tail 30 | grep -E "(Query Debug|WHERE|FROM|Solr query|SolrServerException)" || echo "No recent query debug logs found"
