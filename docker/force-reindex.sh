#!/bin/bash

# Force reindexing script for NemakiWare
# This script manually indexes all documents from CouchDB to Solr

COUCHDB_URL="http://localhost:5984"
COUCHDB_USER="admin"
COUCHDB_PASSWORD="password"
SOLR_URL="http://localhost:8983/solr"

echo "=========================================="
echo "NemakiWare Force Reindexing Script"
echo "=========================================="

# Function to index a repository
index_repository() {
    local repo=$1
    echo ""
    echo "Processing repository: $repo"
    
    # Get total document count
    TOTAL=$(curl -s -u "$COUCHDB_USER:$COUCHDB_PASSWORD" "$COUCHDB_URL/$repo/_all_docs" | jq '.total_rows')
    echo "Total documents in $repo: $TOTAL"
    
    if [ "$TOTAL" == "null" ] || [ "$TOTAL" == "0" ]; then
        echo "No documents found in $repo"
        return
    fi
    
    # Create a very old timestamp to force indexing of all documents
    # Using Unix epoch (1970-01-01)
    OLD_TIMESTAMP="1970-01-01T00:00:00.000Z"
    
    # Create or update token in Solr token core
    echo "Setting token timestamp to $OLD_TIMESTAMP for repository $repo"
    
    # Create token document
    cat > /tmp/token_$repo.json << EOF
[
  {
    "id": "token_$repo",
    "repositoryId": "$repo",
    "lastModified": "$OLD_TIMESTAMP",
    "type": "token"
  }
]
EOF
    
    # Send token to Solr
    curl -s -X POST "$SOLR_URL/token/update?commit=true" \
        -H "Content-Type: application/json" \
        -d @/tmp/token_$repo.json > /dev/null
    
    echo "Token updated for $repo"
    
    # Clean up
    rm -f /tmp/token_$repo.json
}

# Check if Solr is running
echo "Checking Solr status..."
if ! curl -s "$SOLR_URL/admin/cores?action=STATUS" > /dev/null; then
    echo "ERROR: Solr is not accessible at $SOLR_URL"
    exit 1
fi
echo "✓ Solr is running"

# Check if CouchDB is running
echo "Checking CouchDB status..."
if ! curl -s -u "$COUCHDB_USER:$COUCHDB_PASSWORD" "$COUCHDB_URL/_all_dbs" > /dev/null; then
    echo "ERROR: CouchDB is not accessible at $COUCHDB_URL"
    exit 1
fi
echo "✓ CouchDB is running"

# Get list of repositories
echo ""
echo "Getting list of repositories..."
REPOS=$(curl -s -u "$COUCHDB_USER:$COUCHDB_PASSWORD" "$COUCHDB_URL/_all_dbs" | jq -r '.[] | select(. != "_replicator" and . != "_users" and . != "nemaki_conf" and . != "_global_changes")')

echo "Found repositories:"
echo "$REPOS" | while read repo; do
    echo "  - $repo"
done

# Process each repository
echo "$REPOS" | while read repo; do
    if [[ "$repo" =~ _closet$ ]]; then
        echo ""
        echo "Skipping archive repository: $repo"
        continue
    fi
    index_repository "$repo"
done

echo ""
echo "=========================================="
echo "Force reindexing setup complete!"
echo ""
echo "The token timestamps have been set to 1970-01-01."
echo "Now restart the Core container to trigger full reindexing:"
echo "  docker restart docker-core-1"
echo ""
echo "After restart, monitor the indexing progress with:"
echo "  curl -s 'http://localhost:8983/solr/nemaki/select?q=*:*&rows=0&wt=json' | jq '.response.numFound'"
echo "=========================================="