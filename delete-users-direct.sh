#!/bin/bash

echo "=== Deleting test users directly from CouchDB ==="

for i in {1..5}; do
    USER="concurrent_user_$i"
    echo "Getting $USER document..."
    DOC=$(curl -s -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/userItemsById?key=\"$USER\"")
    
    ID=$(echo "$DOC" | jq -r '.rows[0].id // empty')
    REV=$(echo "$DOC" | jq -r '.rows[0].value._rev // empty')
    
    if [ -n "$ID" ] && [ -n "$REV" ]; then
        echo "Deleting $USER (ID: $ID, Rev: $REV)..."
        curl -s -u admin:password -X DELETE "http://localhost:5984/bedroom/$ID?rev=$REV"
        echo ""
    else
        echo "$USER not found"
    fi
done

# Delete test group
echo "Getting testgroup document..."
GROUP_DOC=$(curl -s -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/groupItemsById?key=\"testgroup\"")
GROUP_ID=$(echo "$GROUP_DOC" | jq -r '.rows[0].id // empty')
GROUP_REV=$(echo "$GROUP_DOC" | jq -r '.rows[0].value._rev // empty')

if [ -n "$GROUP_ID" ] && [ -n "$GROUP_REV" ]; then
    echo "Deleting testgroup (ID: $GROUP_ID, Rev: $GROUP_REV)..."
    curl -s -u admin:password -X DELETE "http://localhost:5984/bedroom/$GROUP_ID?rev=$GROUP_REV"
    echo ""
else
    echo "testgroup not found"
fi

echo "=== Cleanup completed ==="
