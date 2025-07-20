#!/bin/bash

# NemakiWare Simple Test Environment Setup
# Enhanced version supporting database initialization only

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEMAKI_HOME="$(dirname "$SCRIPT_DIR")"

# Default values
INIT_ONLY=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        init-only)
            INIT_ONLY=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [init-only]"
            exit 1
            ;;
    esac
done

# Set environment variables
export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "=== NemakiWare Simple Test Environment ==="
echo "Script directory: $SCRIPT_DIR"
echo "NemakiWare home: $NEMAKI_HOME"
echo "CouchDB credentials: $COUCHDB_USER/$COUCHDB_PASSWORD"

if [[ "$INIT_ONLY" == "true" ]]; then
    echo "Mode: Database initialization only"
else
    echo "Mode: Full environment setup"
fi

# Function to wait for CouchDB
wait_for_couchdb() {
    local container_name=$1
    local max_attempts=30
    local attempt=1
    
    echo "Waiting for CouchDB container '$container_name' to be ready..."
    
    while [[ $attempt -le $max_attempts ]]; do
        if docker exec "$container_name" curl -s -f http://localhost:5984/_up >/dev/null 2>&1; then
            echo "CouchDB is ready after $attempt attempts"
            return 0
        fi
        
        echo "Attempt $attempt/$max_attempts: CouchDB not ready yet..."
        sleep 2
        ((attempt++))
    done
    
    echo "ERROR: CouchDB failed to become ready after $max_attempts attempts"
    return 1
}

# Function to initialize database
initialize_database() {
    local container_name=$1
    local repo_id=$2
    local dump_file=$3
    local force_param=$4
    
    echo "Initializing repository '$repo_id' using dump file '$dump_file'..."
    
    # Run cloudant-init.jar with proper authentication
    docker compose -f docker-compose-simple.yml run --rm --remove-orphans \
        -e COUCHDB_URL=http://${container_name}:5984 \
        -e COUCHDB_USERNAME=${COUCHDB_USER} \
        -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
        -e REPOSITORY_ID=${repo_id} \
        -e DUMP_FILE=${dump_file} \
        -e FORCE=${force_param} \
        initializer-${repo_id}
    
    echo "Repository '$repo_id' initialization completed"
}

if [[ "$INIT_ONLY" != "true" ]]; then
    # Stop existing containers
    echo ""
    echo "Stopping existing containers..."
    docker compose -f docker-compose-simple.yml down -v 2>/dev/null || true
    
    # Start services
    echo ""
    echo "Starting services..."
    docker compose -f docker-compose-simple.yml up -d couchdb solr ui
fi

# Wait for CouchDB
echo ""
wait_for_couchdb "docker-couchdb-1"

# Initialize all repositories
echo ""
echo "Initializing repositories..."

# Repository configuration (compatible bash syntax)
repositories_bedroom="/app/bedroom_init.dump"
repositories_bedroom_closet="/app/archive_init.dump"
repositories_canopy="/app/canopy_init.dump"
repositories_canopy_closet="/app/archive_init.dump"

# Initialize each repository
for repo_id in bedroom bedroom_closet canopy canopy_closet; do
    case $repo_id in
        bedroom)
            dump_file="$repositories_bedroom"
            ;;
        bedroom_closet)
            dump_file="$repositories_bedroom_closet"
            ;;
        canopy)
            dump_file="$repositories_canopy"
            ;;
        canopy_closet)
            dump_file="$repositories_canopy_closet"
            ;;
    esac
    
    force_param="true"  # Always force to ensure proper initialization
    initialize_database "couchdb" "$repo_id" "$dump_file" "$force_param"
done

if [[ "$INIT_ONLY" == "true" ]]; then
    echo ""
    echo "=== Database Initialization Completed ==="
    echo "Repositories initialized:"
    echo "  ✓ bedroom"
    echo "  ✓ bedroom_closet"  
    echo "  ✓ canopy"
    echo "  ✓ canopy_closet"
    echo ""
    echo "CouchDB is ready for Core application connection"
    exit 0
fi

echo "Stopping any running containers..."
cd $SCRIPT_DIR
docker compose -f docker-compose-simple.yml down --remove-orphans --volumes

echo "Cleaning all build artifacts and untracked files..."
# Clean all Docker build artifacts (WAR files, generated directories, etc.)
cd $NEMAKI_HOME
# Preserve important assembly and source files while cleaning build artifacts
git clean -fdx docker/ --exclude docker/solr/src/assembly/ || echo "Warning: Some files could not be cleaned"

# Clean Maven targets
mvn clean -q || echo "Warning: Maven clean failed"


# Clean SBT cache
if [ -d "$HOME/.sbt" ]; then
  echo "Cleaning SBT cache for fresh build..."
  rm -rf "$HOME/.sbt/1.0/plugins"
fi

echo "Clean state achieved - all build artifacts removed"

# Set JAVA_HOME for Java 17 
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "Preparing initializer (will build JAR files automatically)..."
cd $NEMAKI_HOME/docker
./prepare-initializer.sh

echo "Building Solr custom JAR..."
cd $NEMAKI_HOME

# Set JAVA_HOME for Java 17
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Verify Java version
echo "Using Java version: $(java -version 2>&1 | head -1)"

mvn clean package -f docker/solr/pom.xml -Pdevelopment -q
if [ $? -eq 0 ]; then
    echo "Solr custom JAR built successfully"
    # Ensure lib directories exist
    mkdir -p docker/solr/solr/nemaki/lib
    mkdir -p docker/solr/solr/token/lib
    
    # Copy main JAR file
    cp docker/solr/target/solr.jar docker/solr/solr/nemaki/lib/
    cp docker/solr/target/solr.jar docker/solr/solr/token/lib/
    
    # Copy dependencies from Maven dependency plugin output
    if [ -d "docker/solr/target/lib" ]; then
        echo "Copying Maven dependencies..."
        cp docker/solr/target/lib/*.jar docker/solr/solr/nemaki/lib/ 2>/dev/null || echo "Note: No additional dependencies found in target/lib"
        cp docker/solr/target/lib/*.jar docker/solr/solr/token/lib/ 2>/dev/null || echo "Note: No additional dependencies found in target/lib"
    fi
    
    # Copy legacy dependencies if they exist
    if [ -d "docker/lib" ] && [ "$(ls -A docker/lib/*.jar 2>/dev/null)" ]; then
        echo "Copying legacy dependencies from docker/lib..."
        cp docker/lib/*.jar docker/solr/solr/nemaki/lib/
        cp docker/lib/*.jar docker/solr/solr/token/lib/
    fi
    
    echo "Solr JAR and dependencies deployed to lib directories"
    echo "Dependencies in nemaki/lib: $(ls -1 docker/solr/solr/nemaki/lib/*.jar | wc -l) JAR files"
    echo "Dependencies in token/lib: $(ls -1 docker/solr/solr/token/lib/*.jar | wc -l) JAR files"
else
    echo "Warning: Solr custom JAR build failed"
    exit 1
fi

echo "Legacy Play Framework UI module removed in NemakiWare 3.0.0"
echo "React SPA UI is now integrated within the core module at core/src/main/webapp/ui/"

# Comment out UI build section
: << 'SKIP_UI_BUILD'
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

# Clean any previous build artifacts (already done above)
echo "Verifying UI directory is clean..."

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
-XX:+UseG1GC
-Dsbt.boot.directory=$HOME/.sbt/boot
-Dsbt.global.base=$HOME/.sbt/global
-Dsbt.ivy.home=$HOME/.ivy2
-Dsbt.coursier.home=$HOME/.coursier
-Djline.terminal=jline.UnsupportedTerminal
-Dsbt.log.noformat=true
-Djdk.http.auth.tunneling.disabledSchemes=""
-Djdk.http.auth.proxying.disabledSchemes=""
EOF_SBTOPTS


# Set SBT options for better performance and to avoid timeout
export SBT_OPTS="-Xmx2G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -Dsbt.global.base=$HOME/.sbt/global"

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
else
  echo "No timeout command available, running SBT without timeout"
fi

# Check build results
if [ $SBT_RESULT -ne 0 ]; then
fi

SKIP_UI_BUILD
# End of skipped UI build section

echo "Building core.war with complete configuration..."

# Verify core.war was cleaned (should not exist after git clean)
if [ -f "$NEMAKI_HOME/docker/core/core.war" ]; then
  echo "Warning: core.war still exists, removing..."
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

# Cloudant migration: Old Ektorp files no longer exist after migration to Cloudant SDK
echo "Skipping Ektorp fixes - migrated to Cloudant SDK"

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
docker run --rm -v $NEMAKI_HOME:/app -v ~/.m2:/root/.m2 -w /app maven:3.9.6-eclipse-temurin-11 mvn clean package -f core/pom.xml -Pdevelopment
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

echo "Skipping Solr custom build - using official Solr 9.x Docker image..."
# Solr 9.x uses official Docker image instead of custom WAR/JAR build

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

# Create UI configuration - SKIPPED (UI disabled for Java 8/11 compatibility)
echo "Skipping UI configuration (UI container disabled)..."
# mkdir -p $NEMAKI_HOME/docker/ui-war
# cat > $NEMAKI_HOME/docker/ui-war/nemakiware_ui.properties << EOF_UI
# nemaki.core.uri=http://core:8080/core
# nemaki.core.uri.archive=http://core:8080/core
# nemaki.auth.superuser.id=admin
# nemaki.auth.superuser.password=admin
# EOF_UI

echo "Creating log4j.properties if it doesn't exist..."
if [ ! -f $NEMAKI_HOME/docker/core/log4j.properties ]; then
  touch $NEMAKI_HOME/docker/core/log4j.properties
fi

echo "Starting Docker containers..."
cd $SCRIPT_DIR

# Ensure containers are still down (already done in cleanup phase)
echo "Ensuring all containers are stopped..."
docker compose -f docker-compose-simple.yml down --remove-orphans --volumes 2>/dev/null || true

# Prune any remaining Docker resources
echo "Pruning Docker system resources..."
docker system prune -f 2>/dev/null || true

echo "Building and starting containers..."
docker compose -f docker-compose-simple.yml build
docker compose -f docker-compose-simple.yml up -d --remove-orphans

echo "Waiting for services to initialize..."
echo "Docker Compose will automatically initialize all 4 repositories:"
echo "- bedroom (with bedroom_init.dump)"
echo "- bedroom_closet (with archive_init.dump)" 
echo "- canopy (with canopy_init.dump - CMIS format)"
echo "- canopy_closet (with archive_init.dump)"
echo ""
echo "This may take 2-3 minutes..."
sleep 60

echo "Waiting for Core to become healthy..."
sleep 30

echo "Restarting Core container to ensure proper patch application..."
docker compose -f docker-compose-simple.yml restart core
sleep 20

echo "Skipping UI runtime configuration verification (UI container disabled)..."
# UI verification skipped - container is not running due to Java 8/11 compatibility issues

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
echo "SKIPPED - UI container disabled due to Java 8/11 compatibility issues"
echo "Note: UI module requires Java 8 which conflicts with Core Java 11+ requirements"
UI_LOGIN_BEDROOM="skipped"

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
echo "- UI Login Page: Skipped (Java 8/11 compatibility)"

echo ""
echo "Service Endpoints:"
echo "- CouchDB: http://localhost:5984/ ($([ "$COUCHDB_STATUS" = "200" ] && echo "accessible" || echo "inaccessible"))"
echo "- Solr: http://localhost:8983/solr/ ($([ "$SOLR_STATUS" = "200" ] && echo "accessible" || echo "inaccessible"))"
echo "- Core CMIS AtomPub: http://localhost:8080/core/atom/bedroom (HTTP $CMIS_ATOM_STATUS)"
echo "- Core CMIS Services: http://localhost:8080/core/services (HTTP $CMIS_SERVICES_STATUS)"
echo ""
echo "UI Access: DISABLED (Java 8/11 compatibility issues)"
echo "- Note: UI module requires separate environment with Java 8"
echo ""
echo "Authentication: Use admin:admin for all endpoints"

echo ""
echo "Test complete! All services should be running on CouchDB 2.x"
