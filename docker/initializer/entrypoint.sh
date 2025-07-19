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

# Initialize the main repository using cloudant-init (modern HTTP Client 5.x)
echo "Creating ${REPOSITORY_ID} repository..."
java -jar /app/cloudant-init.jar \
    --url ${COUCHDB_URL} \
    --username ${COUCHDB_USERNAME} \
    --password ${COUCHDB_PASSWORD} \
    --repository ${REPOSITORY_ID} \
    --dump ${DUMP_FILE} \
    --force ${FORCE}

echo "Initialization complete!"
