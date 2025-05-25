#!/bin/bash

# Exit on error
set -e

# Get the absolute path of the NemakiWare repository
NEMAKI_HOME=$(cd $(dirname $0)/.. && pwd)

# Build the cloudant-init JAR
echo "Building cloudant-init JAR..."
cd $NEMAKI_HOME/setup/couchdb/cloudant-init
mvn clean package

# Create the initializer directory
echo "Creating initializer directory..."
mkdir -p $NEMAKI_HOME/docker/initializer

# Copy the JAR file
echo "Copying JAR file..."
cp $NEMAKI_HOME/setup/couchdb/cloudant-init/target/cloudant-init.jar $NEMAKI_HOME/docker/initializer/

# Copy the dump files
echo "Copying dump files..."
cp -r $NEMAKI_HOME/setup/couchdb/dump $NEMAKI_HOME/docker/initializer/

# Create the Dockerfile
echo "Creating Dockerfile..."
cat > $NEMAKI_HOME/docker/initializer/Dockerfile << EOL
FROM openjdk:8-jre-alpine

WORKDIR /app

COPY cloudant-init.jar /app/
COPY dump /app/dump

COPY entrypoint.sh /app/
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
EOL

# Create the entrypoint.sh script
echo "Creating entrypoint.sh script..."
cat > $NEMAKI_HOME/docker/initializer/entrypoint.sh << EOL
#!/bin/sh

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready..."
until curl -s http://${COUCHDB_HOST}:5984 > /dev/null; do
    echo "CouchDB is unavailable - sleeping"
    sleep 1
done

# Create admin user if auth is enabled
if [ "${COUCHDB_AUTH_ENABLED}" = "true" ]; then
    echo "Creating admin user..."
    curl -X PUT http://${COUCHDB_HOST}:5984/_node/nonode@nohost/_config/admins/${COUCHDB_USER} -d '"${COUCHDB_PASSWORD}"'
fi

# Initialize databases
echo "Initializing databases..."
java -jar /app/cloudant-init.jar     --host=${COUCHDB_HOST}     --port=5984     --auth-enabled=${COUCHDB_AUTH_ENABLED}     --username=${COUCHDB_USER}     --password=${COUCHDB_PASSWORD}     --dump-dir=/app/dump

echo "Initialization complete!"
EOL

echo "cloudant-init.jar and Dockerfile prepared successfully!"
