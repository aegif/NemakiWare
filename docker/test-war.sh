
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "Stopping any running containers..."
docker compose -f docker-compose-war.yml down

echo "Building cloudant-init JAR..."
cd $NEMAKI_HOME/setup/couchdb/cloudant-init
mvn clean package -DskipTests

echo "Preparing initializer..."
cd $NEMAKI_HOME/docker
./prepare-initializer.sh

echo "Preparing UI WAR..."
./prepare-ui-war.sh

echo "Creating core/repositories.yml if it doesn't exist..."
mkdir -p $NEMAKI_HOME/docker/core
if [ ! -f $NEMAKI_HOME/docker/core/repositories.yml ]; then
  echo "Creating default repositories.yml..."
  cat > $NEMAKI_HOME/docker/core/repositories.yml << EOF
repositories:
  - id: canopy
    name: canopy
    archive: canopy_closet
  - id: bedroom
    name: bedroom
    archive: bedroom_closet
EOF
fi

echo "Starting containers..."
docker compose -f docker-compose-war.yml up -d

echo "Waiting for services to start..."
sleep 10

COUCHDB_USER=${COUCHDB_USER:-admin}
COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "Checking CouchDB 2.x database..."
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/bedroom | grep -q "db_name" && echo "CouchDB 2.x database exists" || echo "CouchDB 2.x database does not exist"

echo "Checking CouchDB 3.x database..."
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | grep -q "db_name" && echo "CouchDB 3.x database exists" || echo "CouchDB 3.x database does not exist"

echo "Running initializers manually..."
echo "CouchDB 2.x initializer:"
docker exec docker-initializer2-1 java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "http://couchdb2:5984" "${COUCHDB_USER}" "${COUCHDB_PASSWORD}" "bedroom" "/app/data/bedroom_init.dump" "true"

echo "CouchDB 3.x initializer:"
docker exec docker-initializer3-1 java -Xmx512m -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer "http://couchdb3:5984" "${COUCHDB_USER}" "${COUCHDB_PASSWORD}" "bedroom" "/app/data/bedroom_init.dump" "true"

echo "Verifying database initialization..."
echo "CouchDB 2.x database:"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/bedroom | grep -q "db_name" && echo "SUCCESS: CouchDB 2.x database exists" || echo "ERROR: CouchDB 2.x database does not exist"

echo "CouchDB 3.x database:"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | grep -q "db_name" && echo "SUCCESS: CouchDB 3.x database exists" || echo "ERROR: CouchDB 3.x database does not exist"

echo "Waiting for initialization to complete..."
sleep 5

echo "Checking initializer logs..."
echo "CouchDB 2.x initializer logs:"
docker logs docker-initializer2-1 | tail -n 20

echo "CouchDB 3.x initializer logs:"
docker logs docker-initializer3-1 | tail -n 20

echo "Checking core logs..."
echo "CouchDB 2.x core logs:"
docker logs docker-core2-1 | tail -n 20

echo "CouchDB 3.x core logs:"
docker logs docker-core3-1 | tail -n 20

echo "UI endpoints:"
echo "CouchDB 2.x UI: http://localhost:9000"
echo "CouchDB 3.x UI: http://localhost:9001"

echo "Test complete!"
