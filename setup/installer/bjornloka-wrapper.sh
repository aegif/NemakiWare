#!/bin/bash

# Wrapper script for bjornloka.jar to handle CouchDB authentication
# Usage: bjornloka-wrapper.sh <jar_path> <couchdb_url> <username> <password> <repo_id> <closet_id> <init_dump> <archive_dump>

JAR_PATH=$1
COUCHDB_URL=$2
USERNAME=$3
PASSWORD=$4
REPO_ID=$5
CLOSET_ID=$6
INIT_DUMP=$7
ARCHIVE_DUMP=$8

# Construct authenticated URL if username and password are provided
if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
    # Add authentication to URL
    AUTH_URL=$(echo $COUCHDB_URL | sed "s|http://|http://${USERNAME}:${PASSWORD}@|")
else
    AUTH_URL=$COUCHDB_URL
fi

# Execute bjornloka.jar for main repository
echo "Initializing repository: $REPO_ID"
java -cp $JAR_PATH jp.aegif.nemaki.bjornloka.Load $AUTH_URL $REPO_ID $INIT_DUMP true

# Execute bjornloka.jar for archive repository
echo "Initializing archive: $CLOSET_ID"
java -cp $JAR_PATH jp.aegif.nemaki.bjornloka.Load $AUTH_URL $CLOSET_ID $ARCHIVE_DUMP true