#!/bin/sh

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready..."
until curl -s http://:5984 > /dev/null; do
    echo "CouchDB is unavailable - sleeping"
    sleep 1
done

# Create admin user if auth is enabled
if [ "" = "true" ]; then
    echo "Creating admin user..."
    curl -X PUT http://:5984/_node/nonode@nohost/_config/admins/admin -d '"password"'
fi

# Initialize databases
echo "Initializing databases..."
java -jar /app/cloudant-init.jar     --host=     --port=5984     --auth-enabled=     --username=admin     --password=password     --dump-dir=/app/dump

echo "Initialization complete!"
