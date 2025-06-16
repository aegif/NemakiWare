#!/bin/bash

echo "=== Comprehensive CMIS Query Testing ==="

CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

if [ -z "$CORE_CONTAINER" ]; then
    echo "ERROR: Core container not found"
    exit 1
fi

echo "Using core container: $CORE_CONTAINER"

echo "1. Fixing Solr connectivity and repository ID issues:"
./fix-solr-connectivity.sh

echo -e "\n2. Testing individual CMIS queries:"
./test-individual-queries.sh

echo -e "\n3. Checking enhanced query debug logs:"
docker logs $CORE_CONTAINER --tail 50 | grep -E "(CMIS Query Debug|WHERE clause|Solr query executed|Results found)" || echo "No enhanced debug logs found"

echo -e "\n4. Verifying Solr index population:"
curl -s "http://localhost:8983/solr/nemaki/select?q=repository_id:bedroom&wt=json&rows=0" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'Documents in bedroom repository: {data[\"response\"][\"numFound\"]}')
    if data['response']['numFound'] > 0:
        print('✓ Solr index is properly populated for bedroom repository')
    else:
        print('✗ Solr index is empty for bedroom repository')
except Exception as e:
    print(f'Error: {e}')
"

echo -e "\n5. Testing root folder query specifically:"
ROOT_QUERY="SELECT cmis:name, cmis:objectId FROM cmis:folder WHERE cmis:objectId = '$ROOT_FOLDER_ID'"
ENCODED_QUERY=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$ROOT_QUERY'))")
RESULT=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=$ENCODED_QUERY")

if echo "$RESULT" | grep -q "numItems.*[1-9]"; then
    echo "✓ Root folder query returned results"
    echo "$RESULT" | grep -o 'numItems">[0-9]*' | head -1
else
    echo "✗ Root folder query returned no results"
    echo "Response preview:"
    echo "$RESULT" | head -5
fi

echo -e "\n6. Ready for comprehensive TCK execution"
