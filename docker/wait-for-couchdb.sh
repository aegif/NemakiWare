#!/bin/bash

# Wait for CouchDB to be ready
echo "Waiting for CouchDB to be ready..."

COUCHDB_HOST="${COUCHDB_HOST:-couchdb}"
COUCHDB_PORT="${COUCHDB_PORT:-5984}"
COUCHDB_USER="${COUCHDB_USER:-admin}"
COUCHDB_PASSWORD="${COUCHDB_PASSWORD:-password}"

# Wait for CouchDB to be accessible
until curl -sf "http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@${COUCHDB_HOST}:${COUCHDB_PORT}/_up" > /dev/null 2>&1; do
  echo "CouchDB is unavailable - sleeping"
  sleep 2
done

echo "CouchDB is up and ready!"

# Execute the command passed as arguments
exec "$@"