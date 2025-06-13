#!/bin/bash

# Exit on error
set -e

# Get the absolute path of the NemakiWare repository
NEMAKI_HOME=$(cd $(dirname $0)/.. && pwd)

# Build the cloudant-init JAR
echo "Building cloudant-init JAR..."
cd $NEMAKI_HOME/setup/couchdb/cloudant-init
mvn clean package

# Build the bjornloka JAR (installer-style initializer)
echo "Building bjornloka JAR (installer-style initializer)..."
cd $NEMAKI_HOME/setup/couchdb/bjornloka
mvn clean package

# Create the initializer directory
echo "Creating initializer directory..."
mkdir -p $NEMAKI_HOME/docker/initializer

# Copy the JAR files
echo "Copying JAR files..."
cp $NEMAKI_HOME/setup/couchdb/cloudant-init/target/cloudant-init.jar $NEMAKI_HOME/docker/initializer/
cp $NEMAKI_HOME/setup/couchdb/bjornloka/target/bjornloka.jar $NEMAKI_HOME/docker/initializer/

# Copy the dump files
echo "Copying dump files..."
cp -r $NEMAKI_HOME/setup/couchdb/initial_import $NEMAKI_HOME/docker/initializer/

# Create the Dockerfile
echo "Creating Dockerfile..."
cat > $NEMAKI_HOME/docker/initializer/Dockerfile << EOL
FROM openjdk:8-jre-alpine

# Install curl for health checks
RUN apk add --no-cache curl

WORKDIR /app

COPY cloudant-init.jar /app/
COPY bjornloka.jar /app/
COPY initial_import /app/initial_import

# Also copy dump files to root for backward compatibility
COPY initial_import/bedroom_init.dump /app/
COPY initial_import/archive_init.dump /app/

COPY entrypoint.sh /app/
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
EOL

# Create the entrypoint.sh script
echo "Creating entrypoint.sh script..."
cat > $NEMAKI_HOME/docker/initializer/entrypoint.sh << 'EOL'
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
EOL

echo "cloudant-init.jar and Dockerfile prepared successfully!"
