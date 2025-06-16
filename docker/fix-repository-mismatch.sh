#!/bin/bash

echo "=== Fixing Repository ID Mismatch ==="

CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)

if [ -z "$CORE_CONTAINER" ]; then
    echo "ERROR: Core container not found"
    exit 1
fi

echo "Using core container: $CORE_CONTAINER"

echo "1. Checking current Solr index repository distribution:"
curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&wt=json&rows=0&facet=true&facet.field=repository_id" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'Total documents: {data[\"response\"][\"numFound\"]}')
    if 'facet_counts' in data and 'facet_fields' in data['facet_counts']:
        repos = data['facet_counts']['facet_fields']['repository_id']
        for i in range(0, len(repos), 2):
            print(f'Repository {repos[i]}: {repos[i+1]} documents')
except Exception as e:
    print(f'Error: {e}')
"

echo -e "\n2. Updating Solr documents to use 'bedroom' repository ID:"
curl -X POST "http://localhost:8983/solr/nemaki/update?commit=true" \
  -H "Content-Type: application/json" \
  -d '[{
    "id": "*",
    "repository_id": {"set": "bedroom"}
  }]' || echo "Bulk update failed, trying individual updates..."

echo -e "\n3. Force reindexing with correct repository ID:"
docker exec $CORE_CONTAINER curl -s "http://solr:8080/solr/admin/cores?core=nemaki&action=index&tracking=AUTO&repositoryId=bedroom"

echo -e "\n4. Verifying repository ID fix:"
curl -s "http://localhost:8983/solr/nemaki/select?q=repository_id:bedroom&wt=json&rows=0" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'Documents in bedroom repository: {data[\"response\"][\"numFound\"]}')
except Exception as e:
    print(f'Error: {e}')
"
