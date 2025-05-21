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

#COMMENTED: cat > $NEMAKI_HOME/docker/core/config3/nemakiware.properties << EOF4
#COMMENTED: db.couchdb.url=http://couchdb3:5984
#COMMENTED: db.couchdb.user=${COUCHDB_USER:-admin}
#COMMENTED: db.couchdb.password=${COUCHDB_PASSWORD:-password}
#COMMENTED: EOF4

echo "Creating log4j.properties if it doesn't exist..."
if [ ! -f $NEMAKI_HOME/docker/core/log4j.properties ]; then
  touch $NEMAKI_HOME/docker/core/log4j.properties
fi

echo "Building and starting containers..."
docker compose -f docker-compose-war.yml build --no-cache initializer2
docker compose -f docker-compose-war.yml up -d --remove-orphans

echo "Waiting for CouchDB services to fully initialize..."
sleep 20

#COMMENTED: echo "Checking CouchDB 3.x database..."
#COMMENTED: echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5985/bedroom"
#COMMENTED: curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | tee /tmp/couchdb3_check.json
#COMMENTED: if cat /tmp/couchdb3_check.json | grep -q "db_name"; then
#COMMENTED:   echo "CouchDB 3.x database exists"
#COMMENTED: else
#COMMENTED:   echo "CouchDB 3.x database does not exist"
#COMMENTED:   echo "Creating CouchDB 3.x database..."
#COMMENTED:   echo "DEBUG: Running: curl -X PUT -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5985/bedroom"
#COMMENTED:   curl -X PUT -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | tee /tmp/couchdb3_create.json
#COMMENTED:   echo "CouchDB 3.x database creation response:"
#COMMENTED:   cat /tmp/couchdb3_create.json
#COMMENTED: fi

echo "Running initializers..."

initialize_database() {
    local couchdb_version=$1
    local repo_id=$2
    local port=$3
    local container_name=$4
    
    local dump_file="/app/bedroom_init.dump"
    if [[ "${repo_id}" == *"_closet" ]]; then
        dump_file="/app/archive_init.dump"
        echo "Using archive dump file for ${repo_id}"
    else
        echo "Using bedroom dump file for ${repo_id}"
    fi
    
    echo "CouchDB ${couchdb_version} initializer for ${repo_id}:"
    
    echo "Checking CouchDB ${couchdb_version} database ${repo_id}..."
    echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:${port}/${repo_id}"
    curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:${port}/${repo_id} | tee /tmp/couchdb${couchdb_version}_${repo_id}_check.json
    if ! cat /tmp/couchdb${couchdb_version}_${repo_id}_check.json | grep -q "db_name"; then
        echo "CouchDB ${couchdb_version} database ${repo_id} does not exist"
        echo "Creating CouchDB ${couchdb_version} database ${repo_id}..."
        echo "DEBUG: Running: curl -X PUT -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:${port}/${repo_id}"
        curl -X PUT -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:${port}/${repo_id} | tee /tmp/couchdb${couchdb_version}_${repo_id}_create.json
    else
        echo "CouchDB ${couchdb_version} database ${repo_id} exists"
    fi
    
    docker compose -f docker-compose-war.yml run --rm --remove-orphans \
      -e COUCHDB_URL=http://${container_name}:5984 \
      -e COUCHDB_USERNAME=${COUCHDB_USER} \
      -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
      -e REPOSITORY_ID=${repo_id} \
      -e DUMP_FILE=${dump_file} \
      -e FORCE=true \
      --entrypoint java \
      initializer${couchdb_version} -Xmx512m -Dlog.level=DEBUG -cp /app/cloudant-init.jar jp.aegif.nemaki.cloudantinit.CouchDBInitializer \
      http://${container_name}:5984 "${COUCHDB_USER}" "${COUCHDB_PASSWORD}" ${repo_id} ${dump_file} true
}

initialize_database "2" "bedroom" "5984" "couchdb2"
initialize_database "2" "bedroom_closet" "5984" "couchdb2"
initialize_database "2" "canopy" "5984" "couchdb2"
initialize_database "2" "canopy_closet" "5984" "couchdb2"

#COMMENTED: initialize_database "3" "bedroom" "5985" "couchdb3"
#COMMENTED: initialize_database "3" "bedroom_closet" "5985" "couchdb3"
#COMMENTED: initialize_database "3" "canopy" "5985" "couchdb3"
#COMMENTED: initialize_database "3" "canopy_closet" "5985" "couchdb3"

echo "Verifying database initialization..."
echo "CouchDB 2.x database:"
echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5984/bedroom"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/bedroom | tee /tmp/couchdb2_response.json
cat /tmp/couchdb2_response.json | grep -q "db_name" && echo "SUCCESS: CouchDB 2.x database exists" || echo "ERROR: CouchDB 2.x database does not exist"

#COMMENTED: echo "CouchDB 3.x database:"
#COMMENTED: echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5985/bedroom"
#COMMENTED: curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5985/bedroom | tee /tmp/couchdb3_response.json
#COMMENTED: cat /tmp/couchdb3_response.json | grep -q "db_name" && echo "SUCCESS: CouchDB 3.x database exists" || echo "ERROR: CouchDB 3.x database does not exist"

echo "UI endpoints:"
echo "CouchDB 2.x UI: http://localhost:9000/ui/"
#COMMENTED: echo "CouchDB 3.x UI: http://localhost:9001/ui/"

echo "Checking UI server status..."
UI_UI_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui/ || echo "connection_error")
if [ "$UI_UI_STATUS" = "200" ]; then
    echo "✓ UI server is up and running at /ui/ path"
else
    echo "✗ UI server returned status $UI_UI_STATUS at /ui/ path"
    
    echo "Checking alternate UI paths:"
    UI_ROOT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ || echo "connection_error")
    UI_REPO_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/repo/bedroom/ || echo "connection_error")
    UI_UI_REPO_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui/repo/bedroom/ || echo "connection_error")
    
    echo "Root path (/): $UI_ROOT_STATUS"
    echo "UI path (/ui/): $UI_UI_STATUS"
    echo "Repo path (/repo/bedroom/): $UI_REPO_STATUS"
    echo "UI+Repo path (/ui/repo/bedroom/): $UI_UI_REPO_STATUS"
    
    echo "Checking UI server logs:"
    docker logs docker-ui2-war-1 | grep -i "error\|exception\|failure" | tail -20
    
    echo "Checking UI server configuration:"
    docker exec docker-ui2-war-1 ls -la /usr/local/tomcat/conf/Catalina/localhost/
    docker exec docker-ui2-war-1 cat /usr/local/tomcat/conf/Catalina/localhost/ui.xml 2>/dev/null || echo "ui.xml not found"
    
    echo "Checking UI server webapps:"
    docker exec docker-ui2-war-1 ls -la /usr/local/tomcat/webapps/
fi

echo "Checking UI to Core connectivity:"
echo "UI is configured to connect to: $(docker exec docker-ui2-war-1 cat /usr/local/tomcat/conf/nemakiware_ui.properties | grep nemaki.core.uri)"
echo "Testing Core server connectivity from UI container:"
docker exec docker-ui2-war-1 curl -s -o /dev/null -w "%{http_code}" http://core2:8080/core || echo "connection_error"

echo "Test complete!"
