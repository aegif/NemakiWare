#!/bin/sh

# Legacy script - replaced by cloudant-init.jar
# Use setup/installer/cloudant-init-wrapper.sh for modern initialization

SCRIPT_HOME=$(cd $(dirname $0);pwd)
CLOUDANT_INIT_HOME=$SCRIPT_HOME/../installer

echo "This script is deprecated. Use cloudant-init-wrapper.sh instead:"
echo "$CLOUDANT_INIT_HOME/cloudant-init-wrapper.sh <jar_path> <couchdb_url> <username> <password> <repo_id> <closet_id> <init_dump> <archive_dump>"
exit 1