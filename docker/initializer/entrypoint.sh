#!/bin/sh
set -e

COUCHDB_URL=${COUCHDB_URL:-http://couchdb:5984}
COUCHDB_USERNAME=${COUCHDB_USERNAME:-admin}
COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}
REPOSITORY_ID=${REPOSITORY_ID:-bedroom}
DUMP_FILE=${DUMP_FILE:-/app/dump/bedroom_init.dump}
FORCE=${FORCE:-false}

echo "CouchDB initialization script starting..."
echo "CouchDB URL: $COUCHDB_URL"
echo "Repository: $REPOSITORY_ID"
echo "Using dump file: $DUMP_FILE"
echo "Force import: $FORCE"

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready..."
until curl -s $COUCHDB_URL > /dev/null; do
    echo "CouchDB is unavailable - sleeping"
    sleep 1
done

if [ ! -f "$DUMP_FILE" ]; then
  echo "Error: Dump file $DUMP_FILE not found!"
  ls -la /app/
  ls -la /app/dump/
  exit 1
fi

echo "Running CouchDBInitializer with classpath..."
java -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
  --url "$COUCHDB_URL" \
  --username "$COUCHDB_USERNAME" \
  --password "$COUCHDB_PASSWORD" \
  --repository "$REPOSITORY_ID" \
  --dump "$DUMP_FILE" \
  --force "$FORCE"

echo "CouchDB initialization completed successfully!"
