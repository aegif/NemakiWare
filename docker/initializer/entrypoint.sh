#!/bin/sh

# Parse COUCHDB_URL to get host 
COUCHDB_HOST=$(echo ${COUCHDB_URL} | sed 's|http://||' | sed 's|:.*||')

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready at ${COUCHDB_URL}..."
until curl -s ${COUCHDB_URL} > /dev/null; do
    echo "CouchDB is unavailable - sleeping"
    sleep 1
done

echo "CouchDB is ready! Initializing databases..."

# First, create the nemaki_conf database with its configuration view
echo "Creating nemaki_conf database..."
# Create the database
curl -X PUT ${COUCHDB_URL}/nemaki_conf -u ${COUCHDB_USERNAME}:${COUCHDB_PASSWORD}

# Create the design document with configuration view
curl -X PUT ${COUCHDB_URL}/nemaki_conf/_design/_repo \
    -u ${COUCHDB_USERNAME}:${COUCHDB_PASSWORD} \
    -H "Content-Type: application/json" \
    -d '{
        "_id": "_design/_repo",
        "views": {
            "configuration": {
                "map": "function(doc) { if (doc.type == '\''configuration'\'')  emit(doc._id, doc) }"
            }
        }
    }'

# Create a configuration document with proper structure and default values
curl -X POST ${COUCHDB_URL}/nemaki_conf \
    -u ${COUCHDB_USERNAME}:${COUCHDB_PASSWORD} \
    -H "Content-Type: application/json" \
    -d '{
        "type": "configuration",
        "configuration": {
            "cmis.server.default.max.items.types": "50",
            "cmis.server.default.depth.types": "-1",
            "cmis.server.default.max.items.objects": "200",
            "cmis.server.default.depth.objects": "10"
        }
    }'

echo "nemaki_conf database created successfully!"

# Initialize the main repository using bjornloka (same as installer) with proper arguments
echo "Creating ${REPOSITORY_ID} repository..."
# Build authenticated URL for bjornloka
COUCHDB_AUTH_URL=$(echo ${COUCHDB_URL} | sed "s|http://|http://${COUCHDB_USERNAME}:${COUCHDB_PASSWORD}@|")
java -cp /app/bjornloka.jar jp.aegif.nemaki.bjornloka.Load \
    ${COUCHDB_AUTH_URL} \
    ${REPOSITORY_ID} \
    ${DUMP_FILE} \
    ${FORCE}

echo "Initialization complete!"

echo "Setting up comprehensive design documents for CMIS functionality..."
if [ -f /app/setup-design-documents.sh ]; then
    chmod +x /app/setup-design-documents.sh
    /app/setup-design-documents.sh
    if [ $? -eq 0 ]; then
        echo "✓ Design documents setup completed successfully"
    else
        echo "✗ Design documents setup failed - this may cause CMIS query issues"
    fi
else
    echo "⚠ Design document setup script not found - using basic initialization only"
fi
