#!/bin/bash

# Convert environment variables to command-line arguments
# Default values
COUCHDB_URL=${COUCHDB_URL:-"http://localhost:5984"}
COUCHDB_USERNAME=${COUCHDB_USERNAME:-""}
COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-""}
REPOSITORY_ID=${REPOSITORY_ID:-"bedroom"}
DUMP_FILE=${DUMP_FILE:-"/app/bedroom_init.dump"}
FORCE=${FORCE:-"false"}

if [ ! -f "$DUMP_FILE" ]; then
  echo "Warning: Dump file not found at $DUMP_FILE"
  echo "Checking if it exists in the mounted volume..."
  
  for possible_path in "/app/bedroom_init.dump" "../setup/couchdb/initial_import/bedroom_init.dump" "/bedroom_init.dump"; do
    if [ -f "$possible_path" ]; then
      echo "Found dump file at $possible_path"
      DUMP_FILE="$possible_path"
      break
    fi
  done
  
  if [ ! -f "$DUMP_FILE" ]; then
    echo "Error: Could not find dump file. Please check volume mapping."
    # exit 1
  fi
fi

echo "Initializing CouchDB database:"
echo "URL: $COUCHDB_URL"
echo "Repository ID: $REPOSITORY_ID"
echo "Dump file: $DUMP_FILE"
echo "Force: $FORCE"

echo "Files in /app directory:"
ls -la /app

# Execute CouchDBInitializer with arguments
java -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
  "$COUCHDB_URL" \
  "$COUCHDB_USERNAME" \
  "$COUCHDB_PASSWORD" \
  "$REPOSITORY_ID" \
  "$DUMP_FILE" \
  "$FORCE"
