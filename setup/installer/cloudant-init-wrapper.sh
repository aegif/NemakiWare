#!/bin/bash

# Wrapper script for cloudant-init.jar for CouchDB initialization
# Usage: cloudant-init-wrapper.sh <jar_path> <couchdb_url> <username> <password> <repo_id> <closet_id> <init_dump> <archive_dump>

JAR_PATH=$1
COUCHDB_URL=$2
USERNAME=$3
PASSWORD=$4
REPO_ID=$5
CLOSET_ID=$6
INIT_DUMP=$7
ARCHIVE_DUMP=$8

# Initialize main repository with cloudant-init
echo "Initializing repository: $REPO_ID"
java -jar $JAR_PATH \
    --url "$COUCHDB_URL" \
    --username "$USERNAME" \
    --password "$PASSWORD" \
    --repository "$REPO_ID" \
    --dump "$INIT_DUMP" \
    --force true

if [ $? -ne 0 ]; then
    echo "Error: Failed to initialize repository $REPO_ID"
    exit 1
fi

# Initialize archive repository with cloudant-init
echo "Initializing archive: $CLOSET_ID"
java -jar $JAR_PATH \
    --url "$COUCHDB_URL" \
    --username "$USERNAME" \
    --password "$PASSWORD" \
    --repository "$CLOSET_ID" \
    --dump "$ARCHIVE_DUMP" \
    --force true

if [ $? -ne 0 ]; then
    echo "Error: Failed to initialize archive $CLOSET_ID"
    exit 1
fi

echo "Repository initialization completed successfully"