#!/bin/bash

echo "=== Complete Repository Isolation Test ==="

echo "1. Testing bjornloka.jar object ID transformation..."

cd setup/couchdb/bjornloka
mvn clean package -q
if [ $? -eq 0 ]; then
    echo "✓ bjornloka.jar built successfully"
else
    echo "✗ bjornloka.jar build failed"
    exit 1
fi
cd ../../..

echo "2. Testing dump file processing..."

for repo in bedroom canopy; do
    echo "Testing ${repo} repository initialization..."
    
    curl -X DELETE -s "http://localhost:5984/${repo}" 2>/dev/null || true
    
    java -cp setup/couchdb/bjornloka/target/bjornloka.jar \
         jp.aegif.nemaki.bjornloka.Load \
         "http://localhost:5984" \
         "${repo}" \
         "setup/couchdb/initial_import/${repo}_init.dump" \
         "true"
    
    if [ $? -eq 0 ]; then
        echo "✓ ${repo} initialization completed"
    else
        echo "✗ ${repo} initialization failed"
        exit 1
    fi
done

echo "3. Verifying object ID uniqueness..."

for repo in bedroom canopy; do
    echo "Checking ${repo} repository object IDs..."
    
    doc_ids=$(curl -s "http://localhost:5984/${repo}/_all_docs" | jq -r '.rows[].id' | grep -v '^_design')
    
    prefixed_count=$(echo "$doc_ids" | grep "^${repo}_" | wc -l)
    total_count=$(echo "$doc_ids" | wc -l)
    
    echo "  Repository ${repo}: ${prefixed_count}/${total_count} documents have correct prefix"
    
    if [ "$prefixed_count" -eq "$total_count" ]; then
        echo "✓ All object IDs in ${repo} are properly prefixed"
    else
        echo "✗ Some object IDs in ${repo} are not properly prefixed"
        exit 1
    fi
done

echo "=== Repository Isolation Test Complete ==="
