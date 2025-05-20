
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)
CLOUDANT_INIT_DIR=$NEMAKI_HOME/setup/couchdb/cloudant-init

echo "Building cloudant-init JAR..."
cd $CLOUDANT_INIT_DIR
mvn clean package -DskipTests

echo "Creating initializer directory..."
mkdir -p $NEMAKI_HOME/docker/initializer

echo "Copying JAR file..."
cp $CLOUDANT_INIT_DIR/target/cloudant-init.jar $NEMAKI_HOME/docker/initializer/

echo "Copying dump file..."
mkdir -p $NEMAKI_HOME/docker/initializer/data
cp $NEMAKI_HOME/setup/couchdb/initial_import/bedroom_init.dump $NEMAKI_HOME/docker/initializer/data/

echo "Creating Dockerfile..."
cat > $NEMAKI_HOME/docker/initializer/Dockerfile << 'EOF'
FROM openjdk:8-jre-slim

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY cloudant-init.jar /app/cloudant-init.jar
COPY data /app/data
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh


ENTRYPOINT ["/app/entrypoint.sh"]

CMD ["http://localhost:5984", "", "", "bedroom", "/app/data/bedroom_init.dump", "true"]
EOF

echo "Creating entrypoint.sh script..."
cat > $NEMAKI_HOME/docker/initializer/entrypoint.sh << 'EOF'
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
EOF

chmod +x $NEMAKI_HOME/docker/initializer/entrypoint.sh

echo "cloudant-init.jar and Dockerfile prepared successfully!"
