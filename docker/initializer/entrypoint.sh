set -e

URL=${COUCHDB_URL:-${1:-"http://localhost:5984"}}
USERNAME=${COUCHDB_USERNAME:-${2:-""}}
PASSWORD=${COUCHDB_PASSWORD:-${3:-""}}
REPOSITORY_ID=${REPOSITORY_ID:-${4:-"bedroom"}}
DUMP_FILE=${DUMP_FILE:-${5:-"/app/bedroom_init.dump"}}
FORCE=${FORCE:-${6:-"true"}}

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

java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "$URL" "$USERNAME" "$PASSWORD" "$REPOSITORY_ID" "$DUMP_FILE" "$FORCE"

exit $?
