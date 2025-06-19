#!/bin/bash
set -e


SCRIPT_DIR=$(cd $(dirname $0); pwd)

COUCHDB_URL=${COUCHDB_URL:-"http://couchdb:5984"}
COUCHDB_USERNAME=${COUCHDB_USERNAME:-"admin"}
COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-"password"}

echo "=========================================="
echo "NemakiWare Design Document Setup"
echo "=========================================="

wait_for_couchdb() {
    echo "Waiting for CouchDB to be ready at ${COUCHDB_URL}..."
    local max_attempts=60
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        if curl -s -f "${COUCHDB_URL}" > /dev/null 2>&1; then
            echo "✓ CouchDB is ready!"
            return 0
        fi
        echo "Attempt $attempt/$max_attempts: CouchDB is unavailable - waiting..."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo "ERROR: CouchDB failed to become ready after $max_attempts attempts"
    return 1
}

create_design_document() {
    local database=$1
    local doc_type=$2
    
    echo "Creating design document for database: $database (type: $doc_type)"
    
    local design_doc='{
        "_id": "_design/_repo",
        "views": {
            "configuration": {
                "map": "function(doc) { if (doc.type == '\''configuration'\'') emit(doc._id, doc) }"
            },
            "content": {
                "map": "function(doc) { if (doc.type == '\''content'\'') emit(doc._id, doc) }"
            },
            "changes": {
                "map": "function(doc) { emit(doc._id, doc) }"
            },
            "admin": {
                "map": "function(doc) { if (doc.type == '\''user'\'' && doc.isAdmin) emit(doc._id, doc) }"
            },
            "user": {
                "map": "function(doc) { if (doc.type == '\''user'\'') emit(doc._id, doc) }"
            },
            "group": {
                "map": "function(doc) { if (doc.type == '\''group'\'') emit(doc._id, doc) }"
            },
            "type": {
                "map": "function(doc) { if (doc.type == '\''type'\'') emit(doc._id, doc) }"
            },
            "acl": {
                "map": "function(doc) { if (doc.type == '\''acl'\'') emit(doc._id, doc) }"
            }
        }
    }'
    
    if [[ "$database" == *"_closet" ]]; then
        design_doc='{
            "_id": "_design/_repo",
            "views": {
                "configuration": {
                    "map": "function(doc) { if (doc.type == '\''configuration'\'') emit(doc._id, doc) }"
                },
                "content": {
                    "map": "function(doc) { if (doc.type == '\''content'\'') emit(doc._id, doc) }"
                },
                "changes": {
                    "map": "function(doc) { emit(doc._id, doc) }"
                }
            }
        }'
    fi
    
    local response=$(curl -s -X PUT "${COUCHDB_URL}/${database}/_design/_repo" \
        -u "${COUCHDB_USERNAME}:${COUCHDB_PASSWORD}" \
        -H "Content-Type: application/json" \
        -d "$design_doc")
    
    if echo "$response" | grep -q '"ok":true'; then
        echo "✓ Design document created successfully for $database"
        return 0
    else
        echo "✗ Failed to create design document for $database"
        echo "Response: $response"
        return 1
    fi
}

verify_design_document() {
    local database=$1
    
    echo "Verifying design document for database: $database"
    
    local check_response=$(curl -s -u "${COUCHDB_USERNAME}:${COUCHDB_PASSWORD}" \
        "${COUCHDB_URL}/${database}/_design/_repo" 2>/dev/null)
    
    if echo "$check_response" | grep -q '"_id":"_design/_repo"'; then
        echo "✓ Design document exists for $database"
        
        local view_response=$(curl -s -u "${COUCHDB_USERNAME}:${COUCHDB_PASSWORD}" \
            "${COUCHDB_URL}/${database}/_design/_repo/_view/changes?limit=0" 2>/dev/null)
        
        if echo "$view_response" | grep -q '"total_rows"'; then
            echo "✓ Changes view is functional for $database"
            return 0
        else
            echo "✗ Changes view is not functional for $database"
            return 1
        fi
    else
        echo "✗ Design document does not exist for $database"
        return 1
    fi
}

get_databases_needing_design_docs() {
    echo "Discovering databases that need design documents..."
    
    local all_dbs=$(curl -s -u "${COUCHDB_USERNAME}:${COUCHDB_PASSWORD}" \
        "${COUCHDB_URL}/_all_dbs" 2>/dev/null)
    
    if [ $? -ne 0 ] || [ -z "$all_dbs" ]; then
        echo "ERROR: Failed to retrieve database list from CouchDB"
        return 1
    fi
    
    local databases=$(echo "$all_dbs" | grep -o '"[^"]*"' | sed 's/"//g' | grep -v '^_')
    
    echo "Found databases: $databases"
    echo "$databases"
}

main() {
    echo "Starting design document setup process..."
    
    if ! wait_for_couchdb; then
        echo "ERROR: CouchDB is not accessible"
        exit 1
    fi
    
    local databases=$(get_databases_needing_design_docs)
    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to get database list"
        exit 1
    fi
    
    local success_count=0
    local total_count=0
    
    for db in $databases; do
        if [ -n "$db" ]; then
            total_count=$((total_count + 1))
            echo ""
            echo "Processing database: $db"
            
            local db_type="main"
            if [[ "$db" == *"_closet" ]]; then
                db_type="archive"
            elif [[ "$db" == "nemaki_conf" ]]; then
                db_type="config"
            fi
            
            if create_design_document "$db" "$db_type"; then
                if verify_design_document "$db"; then
                    success_count=$((success_count + 1))
                    echo "✓ Successfully set up design document for $db"
                else
                    echo "✗ Design document verification failed for $db"
                fi
            else
                echo "✗ Failed to create design document for $db"
            fi
        fi
    done
    
    echo ""
    echo "=========================================="
    echo "Design Document Setup Summary"
    echo "=========================================="
    echo "Total databases processed: $total_count"
    echo "Successful setups: $success_count"
    echo "Failed setups: $((total_count - success_count))"
    
    if [ $success_count -eq $total_count ] && [ $total_count -gt 0 ]; then
        echo "✓ All design documents set up successfully!"
        echo ""
        echo "IMPORTANT: The following design documents are now configured:"
        for db in $databases; do
            if [ -n "$db" ]; then
                echo "  - $db: _design/_repo with views for CMIS change log tracking"
            fi
        done
        echo ""
        echo "This setup enables:"
        echo "  ✓ CMIS change log tracking between CouchDB and Solr"
        echo "  ✓ Proper data synchronization for query operations"
        echo "  ✓ Reproducible TCK test results across environments"
        return 0
    else
        echo "✗ Some design documents failed to set up properly"
        echo "Please check the errors above and retry"
        return 1
    fi
}

main "$@"
