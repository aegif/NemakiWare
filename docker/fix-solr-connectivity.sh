#!/bin/bash

echo "=== Fixing Solr Connectivity Issues ==="

CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
SOLR_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(solr|solr-1)" | head -1)

if [ -z "$CORE_CONTAINER" ] || [ -z "$SOLR_CONTAINER" ]; then
    echo "ERROR: Required containers not found"
    echo "Core: $CORE_CONTAINER, Solr: $SOLR_CONTAINER"
    exit 1
fi

echo "Core container: $CORE_CONTAINER"
echo "Solr container: $SOLR_CONTAINER"

echo -e "\n1. Checking current Solr configuration in core:"
docker exec $CORE_CONTAINER cat /usr/local/tomcat/nemakiware/nemakiware.properties | grep solr

echo -e "\n2. Testing Solr connectivity from core container:"
if docker exec $CORE_CONTAINER curl -s "http://solr:8080/solr/admin/cores" > /dev/null; then
    echo "✓ Core can connect to Solr"
else
    echo "✗ Core cannot connect to Solr - checking network"
    docker network ls
    docker exec $CORE_CONTAINER ping -c 2 solr || echo "Network connectivity failed"
fi

echo -e "\n3. Checking repository ID mismatch:"
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

echo -e "\n4. Updating repository IDs to 'bedroom':"
python3 -c "
import requests
import json

response = requests.get('http://localhost:8983/solr/nemaki/select?q=*:*&wt=json&rows=1000')
data = response.json()

updates = []
for doc in data['response']['docs']:
    if doc.get('repository_id') != 'bedroom':
        updates.append({
            'id': doc['id'],
            'repository_id': {'set': 'bedroom'}
        })

if updates:
    print(f'Updating {len(updates)} documents to bedroom repository')
    update_response = requests.post(
        'http://localhost:8983/solr/nemaki/update?commit=true',
        headers={'Content-Type': 'application/json'},
        data=json.dumps(updates)
    )
    print(f'Update status: {update_response.status_code}')
else:
    print('No updates needed - all documents already in bedroom repository')
"

echo -e "\n5. Verifying fix:"
curl -s "http://localhost:8983/solr/nemaki/select?q=repository_id:bedroom&wt=json&rows=0" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'Documents in bedroom repository: {data[\"response\"][\"numFound\"]}')
except Exception as e:
    print(f'Error: {e}')
"
