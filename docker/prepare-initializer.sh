
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

echo "Copying dump files..."
cp $NEMAKI_HOME/setup/couchdb/initial_import/bedroom_init.dump $NEMAKI_HOME/docker/initializer/
cp $NEMAKI_HOME/setup/couchdb/initial_import/archive_init.dump $NEMAKI_HOME/docker/initializer/

echo "Creating Dockerfile..."
cat > $NEMAKI_HOME/docker/initializer/Dockerfile << 'EOF'
FROM openjdk:8-jre-slim

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY cloudant-init.jar /app/cloudant-init.jar
COPY bedroom_init.dump /app/bedroom_init.dump
COPY archive_init.dump /app/archive_init.dump
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh


ENTRYPOINT ["/app/entrypoint.sh"]

CMD ["http://localhost:5984", "", "", "bedroom", "/app/bedroom_init.dump", "true"]
EOF

echo "Creating entrypoint.sh script..."
cat > $NEMAKI_HOME/docker/initializer/entrypoint.sh << 'EOF'
#!/bin/bash
set -e

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

echo "DEBUG: 渡される引数:"
echo "1: $URL"
echo "2: $USERNAME" 
echo "3: $PASSWORD"
echo "4: $REPOSITORY_ID"
echo "5: $DUMP_FILE"
echo "6: $FORCE"

if [ ! -f "$DUMP_FILE" ]; then
    echo "ERROR: Dump file $DUMP_FILE does not exist!"
    echo "Contents of /app directory:"
    ls -la /app
    exit 1
fi

echo "DEBUG: 実行コマンド:"
echo "java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \"$URL\" \"$USERNAME\" \"$PASSWORD\" \"$REPOSITORY_ID\" \"$DUMP_FILE\" \"$FORCE\""

java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "$URL" "$USERNAME" "$PASSWORD" "$REPOSITORY_ID" "$DUMP_FILE" "$FORCE"

exit $?
EOF

chmod +x $NEMAKI_HOME/docker/initializer/entrypoint.sh

echo "cloudant-init.jar and Dockerfile prepared successfully!"
