#!/bin/bash

echo "=== Diagnosing Solr Connectivity Issues ==="

CORE_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(core|core-1)" | head -1)
SOLR_CONTAINER=$(docker ps --format "{{.Names}}" | grep -E "(solr|solr-1)" | head -1)

echo "Core container: $CORE_CONTAINER"
echo "Solr container: $SOLR_CONTAINER"

echo -e "\n1. Testing network connectivity between containers:"
docker exec $CORE_CONTAINER ping -c 2 solr || echo "Cannot ping solr from core"

echo -e "\n2. Checking Solr container port binding:"
docker port $SOLR_CONTAINER || echo "No port bindings found"

echo -e "\n3. Testing Solr access from core container:"
docker exec $CORE_CONTAINER curl -s "http://solr:8080/solr/admin/cores" | head -5 || echo "Cannot access Solr admin from core"

echo -e "\n4. Testing direct Solr query from core container:"
docker exec $CORE_CONTAINER curl -s "http://solr:8080/solr/nemaki/select?q=*:*&wt=json&rows=0" || echo "Cannot query Solr from core"

echo -e "\n5. Checking NemakiWare Solr configuration:"
docker exec $CORE_CONTAINER cat /usr/local/tomcat/nemakiware/nemakiware.properties | grep solr

echo -e "\n6. Testing external Solr access:"
curl -s "http://localhost:8983/solr/admin/cores" | head -5 || echo "Cannot access Solr externally"
