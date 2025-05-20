#!/bin/bash
set -e

# Get values from environment variables or command-line arguments
# Command-line arguments take precedence over environment variables
URL=${1:-${COUCHDB_URL:-"http://localhost:5984"}}
USERNAME=${2:-${COUCHDB_USERNAME:-""}}
PASSWORD=${3:-${COUCHDB_PASSWORD:-""}}
REPOSITORY_ID=${4:-${REPOSITORY_ID:-"bedroom"}}
DUMP_FILE=${5:-${DUMP_FILE:-"/app/bedroom_init.dump"}}
FORCE=${6:-${FORCE:-"true"}}

echo "Initializing CouchDB database:"
echo "URL: $URL"
echo "Username: $USERNAME"
echo "Repository ID: $REPOSITORY_ID"
echo "Dump file: $DUMP_FILE"
echo "Force: $FORCE"

if [ ! -f "$DUMP_FILE" ]; then
    echo "ERROR: Dump file $DUMP_FILE does not exist!"
    echo "Contents of /app directory:"
    ls -la /app
    exit 1
fi

echo "Executing CouchDBInitializer with arguments:"
echo "java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \"$URL\" \"$USERNAME\" \"$PASSWORD\" \"$REPOSITORY_ID\" \"$DUMP_FILE\" \"$FORCE\""

# Pass all 6 arguments explicitly to the Java application
java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "$URL" "$USERNAME" "$PASSWORD" "$REPOSITORY_ID" "$DUMP_FILE" "$FORCE"

exit $?
