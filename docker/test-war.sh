#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "Using CouchDB credentials:"
echo "Username: $COUCHDB_USER"
echo "Password: $COUCHDB_PASSWORD (masked for security)"

echo "Stopping any running containers..."
docker compose -f docker-compose-war.yml down --remove-orphans

echo "Building cloudant-init JAR..."
cd $NEMAKI_HOME/setup/couchdb/cloudant-init
mvn clean package -DskipTests

echo "Preparing initializer..."
cd $NEMAKI_HOME/docker
./prepare-initializer.sh

echo "Preparing UI WAR..."
./prepare-ui-war.sh

echo "Building core server..."
cd $NEMAKI_HOME
mvn clean package -pl core -am -DskipTests
cp core/target/core.war $NEMAKI_HOME/docker/core/

echo "Building Solr WAR file using Docker with Java 8..."
cd $NEMAKI_HOME/docker
./build-solr.sh

echo "Creating core/repositories.yml if it doesn't exist..."
mkdir -p $NEMAKI_HOME/docker/core
if [ ! -f $NEMAKI_HOME/docker/core/repositories.yml ]; then
  echo "Creating default repositories.yml..."
  cat > $NEMAKI_HOME/docker/core/repositories.yml << EOF2
repositories:
  - id: canopy
    name: canopy
    archive: canopy_closet
  - id: bedroom
    name: bedroom
    archive: bedroom_closet
EOF2
fi

mkdir -p $NEMAKI_HOME/docker/core/config2
mkdir -p $NEMAKI_HOME/docker/core/config3

cat > $NEMAKI_HOME/docker/core/nemakiware.properties << EOF3
db.couchdb.url=http://couchdb2:5984
db.couchdb.user=${COUCHDB_USER:-admin}
db.couchdb.password=${COUCHDB_PASSWORD:-password}
EOF3

cp $NEMAKI_HOME/docker/core/nemakiware.properties $NEMAKI_HOME/docker/core/config2/

cat > $NEMAKI_HOME/docker/core/config3/nemakiware.properties << EOF4
db.couchdb.url=http://couchdb3:5984
db.couchdb.user=${COUCHDB_USER:-admin}
db.couchdb.password=${COUCHDB_PASSWORD:-password}
EOF4

echo "Creating log4j.properties if it doesn't exist..."
if [ ! -f $NEMAKI_HOME/docker/core/log4j.properties ]; then
  touch $NEMAKI_HOME/docker/core/log4j.properties
fi

echo "Starting containers..."
docker compose -f docker-compose-war.yml up -d --remove-orphans

echo "Waiting for CouchDB services to fully initialize..."
sleep 20

echo "Checking CouchDB 2.x database..."
echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5984/bedroom"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/bedroom | tee /tmp/couchdb2_check.json
cat /tmp/couchdb2_check.json | grep -q "db_name" && echo "CouchDB 2.x database exists" || echo "CouchDB 2.x database does not exist"

echo "Checking CouchDB 3.x database..."
echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5985/bedroom"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | tee /tmp/couchdb3_check.json
cat /tmp/couchdb3_check.json | grep -q "db_name" && echo "CouchDB 3.x database exists" || echo "CouchDB 3.x database does not exist"

echo "Running initializers..."
echo "CouchDB 2.x initializer:"
docker compose -f docker-compose-war.yml run --rm --remove-orphans \
  -e COUCHDB_URL=http://couchdb2:5984 \
  -e COUCHDB_USERNAME=${COUCHDB_USER} \
  -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
  -e REPOSITORY_ID=bedroom \
  -e DUMP_FILE=/app/bedroom_init.dump \
  -e FORCE=true \
  --entrypoint java \
  initializer2 -Xmx512m -Dlog.level=DEBUG -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
  http://couchdb2:5984 "${COUCHDB_USER}" "${COUCHDB_PASSWORD}" bedroom /app/bedroom_init.dump true

echo "CouchDB 3.x initializer:"
docker compose -f docker-compose-war.yml run --rm --remove-orphans \
  -e COUCHDB_URL=http://couchdb3:5984 \
  -e COUCHDB_USERNAME=${COUCHDB_USER} \
  -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
  -e REPOSITORY_ID=bedroom \
  -e DUMP_FILE=/app/bedroom_init.dump \
  -e FORCE=true \
  --entrypoint java \
  initializer3 -Xmx512m -Dlog.level=DEBUG -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
  http://couchdb3:5984 "${COUCHDB_USER}" "${COUCHDB_PASSWORD}" bedroom /app/bedroom_init.dump true

echo "Verifying database initialization..."
echo "CouchDB 2.x database:"
echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5984/bedroom"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/bedroom | tee /tmp/couchdb2_response.json
cat /tmp/couchdb2_response.json | grep -q "db_name" && echo "SUCCESS: CouchDB 2.x database exists" || echo "ERROR: CouchDB 2.x database does not exist"

echo "CouchDB 3.x database:"
echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5985/bedroom"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | tee /tmp/couchdb3_response.json
cat /tmp/couchdb3_response.json | grep -q "db_name" && echo "SUCCESS: CouchDB 3.x database exists" || echo "ERROR: CouchDB 3.x database does not exist"

echo "UI endpoints:"
echo "CouchDB 2.x UI: http://localhost:9000"
echo "CouchDB 3.x UI: http://localhost:9001"

echo "Test complete!"
