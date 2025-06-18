#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "=========================================="
echo "NemakiWare Simple Docker Test Environment"
echo "=========================================="
echo "Using CouchDB 2.x with authentication:"
echo "Username: $COUCHDB_USER"
echo "Password: $COUCHDB_PASSWORD"

echo "Stopping any running containers..."
cd $SCRIPT_DIR
docker compose -f docker-compose-simple.yml down --remove-orphans

echo "Building cloudant-init JAR..."
cd $NEMAKI_HOME/setup/couchdb/cloudant-init
mvn clean package -DskipTests

echo "Preparing initializer..."
cd $NEMAKI_HOME/docker
./prepare-initializer.sh

echo "Building UI WAR with SBT..."
cd $NEMAKI_HOME/ui

# Ensure SBT configuration is properly set up for HTTPS repositories
echo "Verifying SBT configuration..."
if ! grep -q "https://repo.typesafe.com" project/plugins.sbt; then
  echo "Updating SBT plugins.sbt with HTTPS repositories..."
  cat > project/plugins.sbt << 'EOF_PLUGINS'
resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/ivy-releases/"
resolvers += "Typesafe Maven" at "https://repo.typesafe.com/typesafe/maven-releases/"
resolvers += "Maven Central" at "https://repo1.maven.org/maven2/"

// The Play plugin
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.4.0")

// web plugins
addSbtPlugin("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-jshint" % "1.0.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-rjs" % "1.0.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-mocha" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-play-enhancer" % "1.1.0")
addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "1.4.0")
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "4.0.0")
EOF_PLUGINS
fi

# Ensure build.properties has the correct SBT version
if ! grep -q "sbt.version=0.13.18" project/build.properties; then
  echo "Updating SBT version to 0.13.18..."
  cat > project/build.properties << 'EOF_BUILD'
#Activator-generated Properties
#Mon Aug 25 22:31:17 JST 2014
template.uuid=5a83682a-28e7-4d0c-90de-430dc913edb2
sbt.version=0.13.18
EOF_BUILD
fi

# Clean any previous build artifacts
echo "Cleaning previous UI build artifacts..."
if [ -d "target" ]; then
  rm -rf target
fi
if [ -d "project/target" ]; then
  rm -rf project/target
fi
# Also clean SBT cache to ensure fresh build with updated configuration
if [ -d "$HOME/.sbt" ]; then
  echo "Cleaning SBT cache for fresh build..."
  rm -rf "$HOME/.sbt/1.0/plugins"
fi

# Fix HttpComponents dependency issue in build.sbt
echo "Fixing HttpComponents dependency for ContentType class..."
if ! grep -q "httpcore.*4.4" build.sbt; then
  # Add httpcore dependency right after httpclient
  sed -i '.bak' '/httpclient.*4.4-beta1/a\
  "org.apache.httpcomponents" % "httpcore" % "4.4.13",
' build.sbt
fi

# Fix ContentType import issue in Application.java
echo "Fixing ContentType import in Application.java..."
if grep -q "import org.apache.http.entity.ContentType;" app/controllers/Application.java; then
  # Remove the import line
  sed -i '.bak' '/import org.apache.http.entity.ContentType;/d' app/controllers/Application.java
  # Replace the usage with a direct string
  sed -i '.bak2' 's/ContentType.APPLICATION_XML.getMimeType()/\"application\/xml\"/' app/controllers/Application.java
  echo "Fixed ContentType usage in Application.java"
fi

# Fix UI application.conf for Docker environment
echo "Fixing UI application.conf for Docker environment..."
if grep -q 'nemaki.core.uri.host="127.0.0.1"' conf/application.conf; then
  echo "Updating Core URI host from 127.0.0.1 to core for Docker..."
  sed -i '.bak' 's/nemaki.core.uri.host="127.0.0.1"/nemaki.core.uri.host="core"/' conf/application.conf
  echo "Core URI host updated in application.conf"
fi

# Also fix core2 references that may exist from test-war.sh
if grep -q 'nemaki.core.uri.host="core2"' conf/application.conf; then
  echo "Updating Core URI host from core2 to core for simple Docker environment..."
  sed -i '.bak2' 's/nemaki.core.uri.host="core2"/nemaki.core.uri.host="core"/' conf/application.conf
  echo "Core URI host updated from core2 to core in application.conf"
fi

# Create .sbtopts file for optimized SBT performance
echo "Creating .sbtopts for optimized SBT performance..."
cat > .sbtopts << 'EOF_SBTOPTS'
-Xmx2G
-Xms1G
-XX:+UseConcMarkSweepGC
-XX:+CMSClassUnloadingEnabled
-Dsbt.boot.directory=$HOME/.sbt/boot
-Dsbt.global.base=$HOME/.sbt/global
-Dsbt.ivy.home=$HOME/.ivy2
-Dsbt.coursier.home=$HOME/.coursier
-Djline.terminal=jline.UnsupportedTerminal
-Dsbt.log.noformat=true
-Djdk.http.auth.tunneling.disabledSchemes=""
-Djdk.http.auth.proxying.disabledSchemes=""
EOF_SBTOPTS

# Build the UI WAR file
echo "Building UI WAR with SBT (this may take a few minutes)..."
echo "Running: sbt clean compile war"

# Set SBT options for better performance and to avoid timeout
export SBT_OPTS="-Xmx2G -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=512M -Dsbt.global.base=$HOME/.sbt/global"

# Initialize SBT result variable
SBT_RESULT=0

# Use timeout to prevent hanging, with extended time for initial dependency resolution
# Try different timeout commands based on OS
if command -v gtimeout > /dev/null; then
  TIMEOUT_CMD="gtimeout 1200"
elif command -v timeout > /dev/null; then
  TIMEOUT_CMD="timeout 1200"
else
  TIMEOUT_CMD=""
fi

# Run SBT build with timeout if available
if [ -n "$TIMEOUT_CMD" ]; then
  echo "Using timeout command: $TIMEOUT_CMD"
  $TIMEOUT_CMD sbt clean compile war || SBT_RESULT=$?
else
  echo "No timeout command available, running SBT without timeout"
  sbt clean compile war || SBT_RESULT=$?
fi

# Check build results
if [ $SBT_RESULT -ne 0 ]; then
  echo "SBT build failed or timed out. Checking for partial WAR files..."
  if [ -f "target/ui.war" ]; then
    echo "Found WAR file in target directory, proceeding..."
  elif [ -f "target/universal/ui.war" ]; then
    echo "Found WAR file in target/universal directory, copying..."
    cp target/universal/ui.war target/ui.war
  else
    echo "No WAR file found. Using existing WAR files if available..."
    if [ ! -f "$NEMAKI_HOME/docker/ui-war/ui.war" ]; then
      echo "ERROR: No UI WAR file available. Please run 'cd ui && sbt war' manually to build UI."
      exit 1
    fi
  fi
fi

# Copy the built WAR files
if [ -f "target/ui.war" ]; then
  echo "Copying newly built UI WAR files..."
  mkdir -p $NEMAKI_HOME/docker/ui-war
  cp target/ui.war $NEMAKI_HOME/docker/ui-war/ui.war
  cp target/ui.war $NEMAKI_HOME/docker/ui-war/ui##.war
  echo "UI WAR files successfully created"
else
  echo "Using existing UI WAR files..."
  if [ ! -f "$NEMAKI_HOME/docker/ui-war/ui.war" ]; then
    echo "ERROR: No UI WAR file available"
    exit 1
  fi
fi

echo "Building core.war with complete configuration..."

# Always remove existing core.war to ensure fresh build
if [ -f "$NEMAKI_HOME/docker/core/core.war" ]; then
  echo "Removing existing core.war..."
  rm -f "$NEMAKI_HOME/docker/core/core.war"
fi

# Apply critical Core fixes before building
echo "Applying critical Core source code fixes..."

# Remove @PostConstruct from PatchService to prevent Spring initialization conflicts
if grep -q "@PostConstruct" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java; then
  echo "Removing @PostConstruct from PatchService to prevent initialization conflicts..."
  sed -i '.bak' '/@PostConstruct/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
  sed -i '.bak2' '/import javax.annotation.PostConstruct;/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
  echo "PatchService @PostConstruct removed"
fi

# Fix Ektorp IdleConnectionMonitor issue by disabling cleanupIdleConnections in both files
echo "Fixing Ektorp IdleConnectionMonitor issue in ConnectorPool and CouchConnector..."

# Fix ConnectorPool.java
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java; then
  echo "Disabling cleanupIdleConnections in ConnectorPool..."
  sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java
  echo "ConnectorPool cleanupIdleConnections disabled"
fi

# Fix CouchConnector.java  
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CouchConnector.java; then
  echo "Disabling cleanupIdleConnections in CouchConnector..."
  sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CouchConnector.java
  echo "CouchConnector cleanupIdleConnections disabled"
fi

# Update the source nemakiware.properties with complete configuration
echo "Updating source nemakiware.properties with complete configuration..."
cat > $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/nemakiware.properties << EOF_PROPERTIES
###Database
db.couchdb.url=http://couchdb:5984
db.couchdb.max.connections=20
db.couchdb.connection.timeout=30000
db.couchdb.socket.timeout=60000
db.couchdb.auth.enabled=true
db.couchdb.auth.username=admin
db.couchdb.auth.password=password

###CMIS server default parameter
cmis.server.default.max.items.types=50
cmis.server.default.depth.types=-1
cmis.server.default.max.items.objects=200
cmis.server.default.depth.objects=10

###Repository
repository.definition.default=repositories-default.yml
repository.definition=repositories.yml

###Search engine
solr.protocol=http
solr.host=solr
solr.port=8983
solr.context=solr
solr.indexing.force=false
solr.nemaki.userid=solr

###Permission
permission.definition=permission.yml
permission.mapping.definition=permission-mapping.yml

###Spring configuration
context.log=logContext.xml
context.businesslogic=businesslogicContext.xml
context.dao=daoContext.xml
context.dao.implementation=couchContext.xml

###NemakiWare extended capability
capability.extended.orderBy.default=cmis:creationDate DESC
capability.extended.preview=false
capability.extended.include.relationships=true
capability.extended.unique.name.check=true
capability.extended.auth.token=true
capability.extended.permission.toplevel=false
capability.extended.permission.inheritance.toplevel=true
capability.extended.user.item.folder=e02f784f8360a02cc14d1314c10038ff

###Rest API
rest.user.enabled=true
rest.group.enabled=true
rest.type.enabled=true
rest.archive.enabled=true
rest.authtoken.enabled=true

###Rendition
jodconverter.registry.dataformats=rendition-format.yml

###Logging
log.aspect.class=jp.aegif.nemaki.util.spring.aspect.log.JsonLogger
log.aspect.expression=execution(* jp.aegif.nemaki.cmis.service..*Impl.*(..)) and !execution(* jp.aegif.nemaki.cmis.service.impl.RepositoryServiceImpl.*(..))
log.config.json.file=log-json-config.json

###Cache
cache.config=ehcache.yml

###Auth token
auth.token.expiration=86400000

###Thread
thread.max=200

###proxyHeadear
external.authenticaion.proxyHeader=X-NemakiWare-Remote-User
external.authenticaion.isAutoCreateUser=false
EOF_PROPERTIES

# Build core.war with Maven
echo "Building core.war with Maven..."
cd $NEMAKI_HOME
mvn clean package -f core/pom.xml -Pdevelopment -q
if [ $? -ne 0 ]; then
  echo "ERROR: Maven build failed for core.war"
  exit 1
fi

# Copy the built core.war to docker directory
echo "Copying built core.war to docker directory..."
cp core/target/core.war docker/core/core.war
if [ ! -f "$NEMAKI_HOME/docker/core/core.war" ]; then
  echo "ERROR: core.war was not created successfully"
  exit 1
fi

echo "core.war built successfully with updated configuration"

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

# Create UI configuration
echo "Creating UI configuration..."
mkdir -p $NEMAKI_HOME/docker/ui-war
cat > $NEMAKI_HOME/docker/ui-war/nemakiware_ui.properties << EOF_UI
nemaki.core.uri=http://core:8080/core
nemaki.core.uri.archive=http://core:8080/core
nemaki.auth.superuser.id=admin
nemaki.auth.superuser.password=admin
EOF_UI

echo "Creating log4j.properties if it doesn't exist..."
if [ ! -f $NEMAKI_HOME/docker/core/log4j.properties ]; then
  touch $NEMAKI_HOME/docker/core/log4j.properties
fi

echo "Starting Docker containers..."
cd $SCRIPT_DIR

# Stop and remove all containers to ensure clean state
docker compose -f docker-compose-simple.yml down --remove-orphans

# Clear any cached volumes
echo "Clearing Docker volumes and cached data..."
docker volume prune -f

echo "Building and starting containers..."
docker compose -f docker-compose-simple.yml build
docker compose -f docker-compose-simple.yml up -d --remove-orphans

echo "Waiting for containers to start..."
sleep 20

echo "Running repository initializers..."

initialize_database() {
    local repo_id=$1
    local container_name="couchdb"
    
    local dump_file="/app/bedroom_init.dump"
    if [[ "${repo_id}" == *"_closet" ]]; then
        dump_file="/app/archive_init.dump"
        echo "Using archive dump file for ${repo_id}"
    elif [[ "${repo_id}" == "canopy" ]]; then
        dump_file="/app/canopy_init.dump"
        echo "Using canopy-specific dump file for canopy repository with unique IDs"
    else
        echo "Using bedroom dump file for ${repo_id}"
    fi
    
    echo "CouchDB initializer for ${repo_id}:"
    
    echo "Checking CouchDB database ${repo_id}..."
    echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5984/${repo_id}"
    curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/${repo_id} | tee /tmp/couchdb_${repo_id}_check.json
    if ! cat /tmp/couchdb_${repo_id}_check.json | grep -q "db_name"; then
        echo "CouchDB database ${repo_id} does not exist"
        echo "Creating CouchDB database ${repo_id}..."
        echo "DEBUG: Running: curl -X PUT -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5984/${repo_id}"
        curl -X PUT -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/${repo_id} | tee /tmp/couchdb_${repo_id}_create.json
    else
        echo "CouchDB database ${repo_id} exists"
    fi
    
    # Always use force=true to ensure proper repository initialization even if database exists
    # This is necessary because database creation and data initialization are separate steps
    local force_param="true"
    
    # Always use authentication (required for CouchDB 3.x compatibility)
    couchdb_url="http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@${container_name}:5984"
    echo "Using authenticated CouchDB URL for bjornloka.jar"
    echo "Executing: bjornloka.jar ${couchdb_url} ${repo_id} ${dump_file} ${force_param}"
    
    docker compose -f docker-compose-simple.yml run --rm --remove-orphans \
      -e COUCHDB_URL=http://${container_name}:5984 \
      -e COUCHDB_USERNAME=${COUCHDB_USER} \
      -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
      -e REPOSITORY_ID=${repo_id} \
      -e DUMP_FILE=${dump_file} \
      -e FORCE=${force_param} \
      initializer
}

echo "=== STARTING REPOSITORY INITIALIZATION ==="

echo "1/4: Initializing bedroom..."
initialize_database "bedroom"

echo "2/4: Initializing bedroom_closet..."
initialize_database "bedroom_closet"

echo "3/4: Initializing canopy..."
initialize_database "canopy"

echo "4/4: Initializing canopy_closet..."
initialize_database "canopy_closet"

echo "=== REPOSITORY INITIALIZATION COMPLETED ==="

echo "Waiting for Core to become healthy..."
sleep 30

echo "Restarting Core container to ensure proper patch application..."
docker compose -f docker-compose-simple.yml restart core
sleep 20

echo "Verifying and fixing UI runtime configuration..."
# Check if UI container has incorrect core host settings and fix them
if docker exec docker-ui-1 test -f /usr/local/tomcat/webapps/ui/WEB-INF/classes/application.conf 2>/dev/null; then
  if docker exec docker-ui-1 grep -q 'nemaki.core.uri.host="core2"' /usr/local/tomcat/webapps/ui/WEB-INF/classes/application.conf 2>/dev/null; then
    echo "Fixing UI runtime Core host configuration from core2 to core..."
    docker exec docker-ui-1 sed -i 's/nemaki.core.uri.host="core2"/nemaki.core.uri.host="core"/' /usr/local/tomcat/webapps/ui/WEB-INF/classes/application.conf
    echo "Restarting UI container to apply configuration changes..."
    docker compose -f docker-compose-simple.yml restart ui
    sleep 15
    echo "UI configuration fixed and container restarted"
  else
    echo "UI Core host configuration is already correct"
  fi
else
  echo "UI application.conf not found in expected location"
fi

echo "=============================================="
echo "SIMPLE ENVIRONMENT TEST"
echo "=============================================="

echo "1. COUCHDB TEST:"
echo "----------------"
COUCHDB_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:5984/ || echo "connection_error")
if [ "$COUCHDB_STATUS" = "200" ]; then
    echo "✓ CouchDB is accessible (HTTP $COUCHDB_STATUS)"
    COUCHDB_AUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/_all_dbs || echo "connection_error")
    if [ "$COUCHDB_AUTH_STATUS" = "200" ]; then
        echo "✓ CouchDB authentication works (HTTP $COUCHDB_AUTH_STATUS)"
        DB_COUNT=$(curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/_all_dbs | jq length 2>/dev/null || echo "0")
        echo "✓ CouchDB has $DB_COUNT databases"
    else
        echo "✗ CouchDB authentication failed (HTTP $COUCHDB_AUTH_STATUS)"
    fi
else
    echo "✗ CouchDB is not accessible (HTTP $COUCHDB_STATUS)"
fi

echo ""
echo "2. SOLR TEST:"
echo "-------------"
SOLR_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8983/solr/ || echo "connection_error")
if [ "$SOLR_STATUS" = "200" ]; then
    echo "✓ Solr is accessible (HTTP $SOLR_STATUS)"
    SOLR_ADMIN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8983/solr/admin/cores || echo "connection_error")
    if [ "$SOLR_ADMIN_STATUS" = "200" ]; then
        echo "✓ Solr admin interface accessible (HTTP $SOLR_ADMIN_STATUS)"
        SOLR_CORES=$(curl -s "http://localhost:8983/solr/admin/cores?action=STATUS" 2>/dev/null | grep -o '"name":"[^"]*"' | wc -l 2>/dev/null || echo "0")
        echo "✓ Solr has $SOLR_CORES cores configured"
    else
        echo "⚠ Solr admin interface not accessible (HTTP $SOLR_ADMIN_STATUS)"
    fi
elif [ "$SOLR_STATUS" = "500" ]; then
    echo "⚠ Solr partially working - deployment issues with Restlet dependencies (HTTP $SOLR_STATUS)"
    echo "  Container running but application initialization incomplete"
else
    echo "✗ Solr is not accessible (HTTP $SOLR_STATUS)"
fi

echo ""
echo "3. CORE TEST:"
echo "-------------"
# Test specific CMIS endpoints with proper authentication
echo "Testing CMIS endpoints with admin:admin authentication..."
CMIS_ATOM_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom || echo "connection_error")
echo "CMIS AtomPub (bedroom): HTTP $CMIS_ATOM_STATUS"

CMIS_SERVICES_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/services || echo "connection_error")
echo "CMIS Web Services: HTTP $CMIS_SERVICES_STATUS"

echo ""
echo "4. UI TEST:"
echo "-----------"
# Test UI login page accessibility (note: actual usage will redirect to 0.0.0.0)
echo "Testing UI login page accessibility..."
UI_LOGIN_BEDROOM=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:9000/ui/repo/bedroom/login" || echo "connection_error")
echo "UI login page (bedroom): HTTP $UI_LOGIN_BEDROOM"

echo ""
echo "NOTE: UI is accessible via localhost:9000 but login will redirect to 0.0.0.0:9000"
echo "For actual usage, access http://0.0.0.0:9000/ui/repo/bedroom/login directly"

echo ""
echo "=============================================="
echo "SIMPLE ENVIRONMENT SUMMARY"
echo "=============================================="

# Overall status summary
echo "Component Status Summary:"
echo "- CouchDB: $([ "$COUCHDB_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed (HTTP $COUCHDB_STATUS)")"
echo "- Solr: $(if [ "$SOLR_STATUS" = "200" ]; then echo "✓ Running"; elif [ "$SOLR_STATUS" = "500" ]; then echo "⚠ Partial (HTTP $SOLR_STATUS)"; else echo "✗ Failed (HTTP $SOLR_STATUS)"; fi)"
echo "- CMIS AtomPub: $([ "$CMIS_ATOM_STATUS" = "200" ] && echo "✓ Working" || echo "✗ Failed (HTTP $CMIS_ATOM_STATUS)")"
echo "- CMIS Web Services: $([ "$CMIS_SERVICES_STATUS" = "200" ] && echo "✓ Working" || echo "✗ Failed (HTTP $CMIS_SERVICES_STATUS)")"
echo "- UI Login Page: $([ "$UI_LOGIN_BEDROOM" = "200" ] && echo "✓ Accessible" || echo "✗ Failed (HTTP $UI_LOGIN_BEDROOM)")"

echo ""
echo "Service Endpoints:"
echo "- CouchDB: http://localhost:5984/ ($([ "$COUCHDB_STATUS" = "200" ] && echo "accessible" || echo "inaccessible"))"
echo "- Solr: http://localhost:8983/solr/ ($([ "$SOLR_STATUS" = "200" ] && echo "accessible" || echo "inaccessible"))"
echo "- Core CMIS AtomPub: http://localhost:8080/core/atom/bedroom (HTTP $CMIS_ATOM_STATUS)"
echo "- Core CMIS Services: http://localhost:8080/core/services (HTTP $CMIS_SERVICES_STATUS)"
echo ""
echo "UI Access (Note: Login redirects to 0.0.0.0):"
echo "- Primary: http://0.0.0.0:9000/ui/repo/bedroom/login"
echo "- Fallback: http://localhost:9000/ui/repo/bedroom/login (will redirect)"
echo ""
echo "Authentication: Use admin:admin for all endpoints"

echo ""
echo "Test complete! All services should be running on CouchDB 2.x"