#!/bin/bash

# Cleanup script to delete accumulated test types directly from CouchDB
# This bypasses the slow typeManager.refreshTypes() operation in the UI

COUCH_URL="http://admin:password@localhost:5984"
DB_NAME="bedroom"

echo "=== Starting CouchDB test type cleanup ==="

# Function to get all documents matching a pattern
get_test_type_ids() {
    local pattern=$1
    curl -s "${COUCH_URL}/${DB_NAME}/_all_docs?start_key=\"${pattern}\"&end_key=\"${pattern}\uffff\"" | \
        jq -r '.rows[] | select(.id | startswith("'${pattern}'")) | .id'
}

# Function to delete a document
delete_document() {
    local doc_id=$1
    local doc_info=$(curl -s "${COUCH_URL}/${DB_NAME}/${doc_id}")
    local rev=$(echo $doc_info | jq -r '._rev')

    if [ "$rev" != "null" ] && [ -n "$rev" ]; then
        curl -s -X DELETE "${COUCH_URL}/${DB_NAME}/${doc_id}?rev=${rev}" > /dev/null
        echo "  ✓ Deleted: ${doc_id}"
        return 0
    else
        echo "  ! Document not found: ${doc_id}"
        return 1
    fi
}

# Patterns for test types
patterns=("test:uploadTest" "test:editTest" "test:cancelTest" "test:correctField")

total_deleted=0

for pattern in "${patterns[@]}"; do
    echo ""
    echo "Searching for types matching: ${pattern}*"

    # Get all matching document IDs
    ids=$(get_test_type_ids "${pattern}")

    if [ -z "$ids" ]; then
        echo "  No types found matching ${pattern}*"
        continue
    fi

    # Delete each matching document
    count=0
    while IFS= read -r doc_id; do
        if [ -n "$doc_id" ]; then
            if delete_document "$doc_id"; then
                ((count++))
                ((total_deleted++))
            fi
        fi
    done <<< "$ids"

    if [ $count -gt 0 ]; then
        echo "  Total deleted for ${pattern}: $count"
    fi
done

echo ""
echo "=== Cleanup complete: $total_deleted test types deleted ==="

# Wait a moment for CouchDB to stabilize
sleep 2

echo "Database cleanup complete. You can now run the tests."
