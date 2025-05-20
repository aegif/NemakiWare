set -e

URL=${1:-"http://localhost:5984"}
USERNAME=${2:-""}
PASSWORD=${3:-""}
REPOSITORY_ID=${4:-"bedroom"}
DUMP_FILE=${5:-"/app/data/bedroom_init.dump"}
FORCE=${6:-"true"}

echo "Initializing CouchDB database:"
echo "URL: $URL"
echo "Repository ID: $REPOSITORY_ID"
echo "Dump file: $DUMP_FILE"
echo "Force: $FORCE"

if [ ! -f "$DUMP_FILE" ]; then
    echo "ERROR: Dump file $DUMP_FILE does not exist!"
    ls -la /app
    ls -la /app/data
    exit 1
fi

echo "Executing CouchDBInitializer with arguments:"
echo "java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \"$URL\" \"$USERNAME\" \"$PASSWORD\" \"$REPOSITORY_ID\" \"$DUMP_FILE\" \"$FORCE\""

java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "$URL" "$USERNAME" "$PASSWORD" "$REPOSITORY_ID" "$DUMP_FILE" "$FORCE"

exit $?
