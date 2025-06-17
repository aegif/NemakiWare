#!/bin/bash

echo "=== Testing Repository Isolation ==="

echo "1. Document counts per repository:"
curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&facet=true&facet.field=repository_id&rows=0" | jq '.facet_counts.facet_fields.repository_id'

echo "2. Checking for duplicate object IDs across repositories:"
curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&fl=object_id,repository_id&rows=1000" | jq '.response.docs | group_by(.object_id) | map(select(length > 1))'

echo "3. Root folder repository assignments:"
curl -s "http://localhost:8983/solr/nemaki/select?q=name:\"/\"&fl=object_id,repository_id,name" | jq .

echo "=== Test Complete ==="
