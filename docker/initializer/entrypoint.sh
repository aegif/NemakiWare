#!/bin/bash

# Convert environment variables to command-line arguments
# Default values
COUCHDB_URL=${COUCHDB_URL:-"http://localhost:5984"}
COUCHDB_USERNAME=${COUCHDB_USERNAME:-""}
COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-""}
REPOSITORY_ID=${REPOSITORY_ID:-"bedroom"}
DUMP_FILE=${DUMP_FILE:-"/app/bedroom_init.dump"}
FORCE=${FORCE:-"false"}

echo "Initializing CouchDB database:"
echo "URL: $COUCHDB_URL"
echo "Username: $COUCHDB_USERNAME"
echo "Password: [hidden]"
echo "Repository ID: $REPOSITORY_ID"
echo "Dump file: $DUMP_FILE"
echo "Force: $FORCE"

echo "Files in /app directory:"
ls -la /app

echo "Checking network connectivity..."
COUCHDB_HOST=$(echo $COUCHDB_URL | sed -e 's|^[^/]*//||' -e 's|:.*||')
echo "CouchDB host: $COUCHDB_HOST"
ping -c 3 $COUCHDB_HOST || echo "Warning: Cannot ping CouchDB host. This might be normal if ICMP is blocked."

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
    exit 1
  fi
fi

echo "Waiting for CouchDB to be ready..."
max_attempts=30
attempt=0
while [ $attempt -lt $max_attempts ]; do
  response=$(curl -s -w "%{http_code}" -o /dev/null -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL")
  if [ "$response" = "200" ]; then
    echo "CouchDB is ready! (HTTP 200)"
    break
  fi
  attempt=$((attempt+1))
  echo "Waiting for CouchDB... attempt $attempt/$max_attempts (HTTP $response)"
  sleep 2
done

if [ $attempt -eq $max_attempts ]; then
  echo "Error: CouchDB is not available after $max_attempts attempts"
  echo "Last response from CouchDB: $(curl -s -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL")"
  exit 1
fi

echo "CouchDB info:"
curl -s -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL"

echo "Creating database $REPOSITORY_ID directly using curl..."
create_response=$(curl -s -X PUT -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/$REPOSITORY_ID")
echo "Database creation response: $create_response"

max_db_attempts=5
db_attempt=1
db_created=false

while [ $db_attempt -le $max_db_attempts ] && [ "$db_created" = "false" ]; do
  echo "Verifying database exists (attempt $db_attempt/$max_db_attempts)..."
  verify_response=$(curl -s -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/$REPOSITORY_ID")
  echo "Verification response: $verify_response"
  
  if echo "$verify_response" | grep -q "\"db_name\""; then
    echo "Database $REPOSITORY_ID exists and is ready!"
    db_created=true
  else
    echo "Database $REPOSITORY_ID does not exist yet, retrying creation..."
    create_response=$(curl -s -X PUT -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/$REPOSITORY_ID")
    echo "Database creation response: $create_response"
    db_attempt=$((db_attempt+1))
    sleep 3
  fi
done

if [ "$db_created" = "false" ]; then
  echo "ERROR: Failed to create database $REPOSITORY_ID after $max_db_attempts attempts"
  echo "Listing all databases to check if it exists with a different name:"
  curl -s -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/_all_dbs"
  
  if [ "$FORCE" = "true" ]; then
    echo "Continuing due to FORCE=true, but import will likely fail"
  else
    exit 1
  fi
fi

echo "Waiting for database to be fully available..."
sleep 5

echo "Creating design documents if needed..."
design_response=$(curl -s -X PUT -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/$REPOSITORY_ID/_design/cmis" -H "Content-Type: application/json" -d '{}')
echo "Design document creation response: $design_response"

# Execute CouchDBInitializer with arguments
echo "Executing CouchDBInitializer with arguments:"
echo "java -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \"$COUCHDB_URL\" \"$COUCHDB_USERNAME\" \"$COUCHDB_PASSWORD\" \"$REPOSITORY_ID\" \"$DUMP_FILE\" \"$FORCE\""

FORCE="true"

java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "$COUCHDB_URL" "$COUCHDB_USERNAME" "$COUCHDB_PASSWORD" "$REPOSITORY_ID" "$DUMP_FILE" "$FORCE"

echo "Verifying database exists after import..."
final_verify=$(curl -s -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/$REPOSITORY_ID")
if echo "$final_verify" | grep -q "\"db_name\""; then
  echo "Database $REPOSITORY_ID exists after import!"
  echo "Database details:"
  curl -s -u "$COUCHDB_USERNAME:$COUCHDB_PASSWORD" "$COUCHDB_URL/$REPOSITORY_ID"
else
  echo "ERROR: Database $REPOSITORY_ID does not exist after import!"
fi
