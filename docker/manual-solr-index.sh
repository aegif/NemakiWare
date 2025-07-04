#!/bin/bash

# Manual Solr indexing script for NemakiWare
# Retrieves documents from CouchDB and indexes them in Solr

set -e

echo "===========================================" 
echo "Manual Solr Indexing for NemakiWare"
echo "==========================================="

# Check if CouchDB is accessible
if ! curl -s -u admin:password "http://localhost:5984/_up" > /dev/null; then
    echo "ERROR: CouchDB is not accessible"
    exit 1
fi

# Check if Solr is accessible
if ! curl -s "http://localhost:8983/solr/nemaki/admin/ping" > /dev/null; then
    echo "ERROR: Solr nemaki core is not accessible"
    exit 1
fi

echo "✓ CouchDB and Solr are accessible"

# Get all documents from bedroom repository
echo "Retrieving documents from CouchDB bedroom repository..."
DOCS=$(curl -s -u admin:password "http://localhost:5984/bedroom/_all_docs?include_docs=true")

# Count total documents
TOTAL_DOCS=$(echo "$DOCS" | jq -r '.rows | length')
echo "Found $TOTAL_DOCS documents in CouchDB"

# Filter and index folder documents
echo "Processing folder documents..."
FOLDER_COUNT=0

echo "$DOCS" | jq -r '.rows[] | select(.doc.type == "cmis:folder") | .doc' | while read -r doc; do
    if [ -n "$doc" ] && [ "$doc" != "null" ]; then
        # Extract key fields for Solr
        ID=$(echo "$doc" | jq -r '._id // empty')
        NAME=$(echo "$doc" | jq -r '.name // "Unknown"')
        TYPE=$(echo "$doc" | jq -r '.type // "cmis:folder"')
        OBJECT_TYPE=$(echo "$doc" | jq -r '.objectType // "cmis:folder"')
        CREATED=$(echo "$doc" | jq -r '.created // "2025-01-01T00:00:00.000Z"')
        
        if [ -n "$ID" ] && [ "$ID" != "null" ]; then
            echo "  Indexing folder: $NAME (ID: $ID)"
            
            # Create Solr document JSON
            SOLR_DOC=$(cat <<EOF
{
  "id": "$ID",
  "cmis_name": "$NAME",
  "cmis_objectTypeId": "$OBJECT_TYPE",
  "type": "$TYPE",
  "created": "$CREATED",
  "content_type": "application/octet-stream"
}
EOF
)
            
            # Index in Solr
            curl -s -X POST "http://localhost:8983/solr/nemaki/update" \
                -H "Content-Type: application/json" \
                -d "[$(echo "$SOLR_DOC")]" > /dev/null
            
            FOLDER_COUNT=$((FOLDER_COUNT + 1))
        fi
    fi
done

# Commit the changes
echo "Committing changes to Solr..."
curl -s -X POST "http://localhost:8983/solr/nemaki/update" \
    -H "Content-Type: application/json" \
    -d '{"commit":{}}' > /dev/null

echo "✓ Indexed $FOLDER_COUNT folders"

# Verify indexing
echo "Verifying Solr index..."
INDEXED_COUNT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0" | jq -r '.response.numFound')
echo "✓ Total documents in Solr index: $INDEXED_COUNT"

# Test CMIS query after indexing
echo "Testing CMIS query after manual indexing..."
QUERY_RESULT=$(curl -s -u admin:admin -X POST \
    -H "Content-Type: application/cmisquery+xml; charset=UTF-8" \
    -d '<?xml version="1.0" encoding="UTF-8"?>
<cmis:query xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <cmis:statement>SELECT * FROM cmis:folder</cmis:statement>
</cmis:query>' \
    http://localhost:8080/core/atom/bedroom/query | grep -o 'numItems="[0-9]*"' | grep -o '[0-9]*')

echo "✓ CMIS folder query result: $QUERY_RESULT items"

echo ""
echo "========================================"
echo "Manual Solr Indexing Complete"
echo "========================================"
echo "CouchDB documents: $TOTAL_DOCS"
echo "Folders indexed: $FOLDER_COUNT" 
echo "Solr total index: $INDEXED_COUNT"
echo "CMIS query result: $QUERY_RESULT"

if [ "$QUERY_RESULT" -gt 0 ]; then
    echo "🎉 SUCCESS: CMIS queries now return data!"
    echo "The indexing problem has been resolved."
else
    echo "⚠️  Issue persists: CMIS queries still return 0 results"
    echo "Further investigation needed on CMIS-Solr integration"
fi