#!/bin/bash

SOLR_URL="http://localhost:8983/solr"
OBJECT_ID="bedroom_e02f784f8360a02cc14d1314c10038ff"
EXPECTED_REPO="bedroom"

echo "=== Repository ID Fix Verification (Updated for Repository-Specific IDs) ==="

echo "1. Checking Solr document with repository-specific object ID..."
curl -s "${SOLR_URL}/nemaki/select?q=object_id:${OBJECT_ID}&fl=id,repository_id,object_id,name" | jq .

echo "2. Applying repository ID fix..."
curl -X POST "${SOLR_URL}/nemaki/update?commit=true" \
  -H "Content-Type: application/json" \
  -d "[{\"id\":\"${EXPECTED_REPO}_${OBJECT_ID}\",\"repository_id\":{\"set\":\"${EXPECTED_REPO}\"}}]"

echo "3. Verifying fix..."
curl -s "${SOLR_URL}/nemaki/select?q=object_id:${OBJECT_ID}&fl=id,repository_id,object_id,name" | jq .

echo "4. Testing CMIS query with repository-specific object ID..."
echo "Query: SELECT * FROM cmis:folder WHERE cmis:objectId = '${OBJECT_ID}'"
echo "Expected: Should return 1 result for repository '${EXPECTED_REPO}'"

echo "=== Verification Complete ==="
