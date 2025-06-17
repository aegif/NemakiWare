#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "==========================================="
echo "NemakiWare Complete Docker Test Environment"
echo "==========================================="
echo "Using CouchDB 2.x with authentication:"
echo "Username: $COUCHDB_USER"
echo "Password: $COUCHDB_PASSWORD"
echo ""

# ========================================
# 1. PREREQUISITE CHECKS
# ========================================
echo "1. CHECKING PREREQUISITES..."
echo "----------------------------------------"

# Check required tools
MISSING_TOOLS=()
if ! command -v docker > /dev/null; then
    MISSING_TOOLS+=("docker")
fi
if ! command -v docker-compose > /dev/null && ! docker compose version > /dev/null 2>&1; then
    MISSING_TOOLS+=("docker-compose")
fi
if ! command -v mvn > /dev/null; then
    MISSING_TOOLS+=("maven")
fi
if ! command -v sbt > /dev/null; then
    MISSING_TOOLS+=("sbt")
fi
if ! command -v java > /dev/null; then
    MISSING_TOOLS+=("java")
fi

if [ ${#MISSING_TOOLS[@]} -ne 0 ]; then
    echo "✗ Missing required tools: ${MISSING_TOOLS[*]}"
    echo "Please install the missing tools before proceeding."
    exit 1
fi
echo "✓ All required tools are available"

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | grep "version" | awk '{print $3}' | sed 's/"//g' | cut -d'.' -f1-2)
echo "Java version: $JAVA_VERSION"
if [[ "$JAVA_VERSION" < "1.8" ]] || [[ "$JAVA_VERSION" == "1.8"* ]]; then
    echo "✓ Java 8 compatible version detected"
else
    echo "⚠ Warning: Java version may not be compatible (Java 8 recommended)"
fi

# Check Docker daemon
if docker info > /dev/null 2>&1; then
    echo "✓ Docker daemon is running"
else
    echo "✗ Docker daemon is not running"
    echo "Please start Docker daemon before proceeding."
    exit 1
fi

echo ""

# ========================================
# 2. SOURCE CODE VERIFICATION
# ========================================
echo "2. VERIFYING CRITICAL SOURCE CODE FIXES..."
echo "----------------------------------------"

FIXES_NEEDED=()
AUTO_FIX=false

# Check for --auto-fix flag
if [[ "$*" == *"--auto-fix"* ]]; then
    AUTO_FIX=true
    echo "Auto-fix mode enabled - will apply source code fixes automatically"
fi

# Check CouchConnector.java
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CouchConnector.java; then
    if [ "$AUTO_FIX" = true ]; then
        echo "Fixing CouchConnector.java cleanupIdleConnections..."
        sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CouchConnector.java
        echo "✓ CouchConnector.java fixed"
    else
        FIXES_NEEDED+=("CouchConnector.java needs cleanupIdleConnections(false)")
    fi
else
    echo "✓ CouchConnector.java cleanupIdleConnections is correct"
fi

# Check ConnectorPool.java
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java; then
    if [ "$AUTO_FIX" = true ]; then
        echo "Fixing ConnectorPool.java cleanupIdleConnections..."
        sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java
        echo "✓ ConnectorPool.java fixed"
    else
        FIXES_NEEDED+=("ConnectorPool.java needs cleanupIdleConnections(false)")
    fi
else
    echo "✓ ConnectorPool.java cleanupIdleConnections is correct"
fi

# Check PatchService.java
if grep -q "@PostConstruct" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java; then
    if [ "$AUTO_FIX" = true ]; then
        echo "Fixing PatchService.java @PostConstruct..."
        sed -i '.bak' '/@PostConstruct/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
        sed -i '.bak2' '/import javax.annotation.PostConstruct;/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
        echo "✓ PatchService.java fixed"
    else
        FIXES_NEEDED+=("PatchService.java needs @PostConstruct removed")
    fi
else
    echo "✓ PatchService.java @PostConstruct is correct"
fi

# Check EHCacheShutdownListener exists
if [ ! -f "$NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/util/EHCacheShutdownListener.java" ]; then
    if [ "$AUTO_FIX" = true ]; then
        echo "Creating EHCacheShutdownListener..."
        mkdir -p $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/util/
        cat > $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/util/EHCacheShutdownListener.java << 'EOF_EHCACHE'
package jp.aegif.nemaki.util;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * EHCacheShutdownListener - A ServletContextListener to properly shutdown EHCache
 * This ensures that all EHCache threads are properly cleaned up when the application stops.
 */
public class EHCacheShutdownListener implements ServletContextListener {
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("EHCacheShutdownListener: Context initialized - setting EHCache properties");
        
        // Set system properties to disable EHCache background operations
        System.setProperty("net.sf.ehcache.enableShutdownHook", "true");
        System.setProperty("net.sf.ehcache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.cache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.skipUpdateCheck", "true");
        System.setProperty("net.sf.ehcache.disabled", "false");
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("EHCacheShutdownListener: Context destroyed - cleaning up EHCache");
        
        try {
            // Force shutdown of EHCache Manager
            Class<?> cacheManagerClass = Class.forName("net.sf.ehcache.CacheManager");
            Object instance = cacheManagerClass.getMethod("getInstance").invoke(null);
            if (instance != null) {
                cacheManagerClass.getMethod("shutdown").invoke(instance);
                System.out.println("EHCacheShutdownListener: EHCache CacheManager shutdown completed");
            }
        } catch (Exception e) {
            System.out.println("EHCacheShutdownListener: Could not shutdown EHCache properly: " + e.getMessage());
        }
        
        // Force interrupt any remaining EHCache threads
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount()];
        rootGroup.enumerate(threads);
        
        for (Thread thread : threads) {
            if (thread != null && thread.getName() != null) {
                String name = thread.getName().toLowerCase();
                if (name.contains("statistics thread") || 
                    name.contains("__default__") ||
                    name.contains("ehcache") || 
                    name.contains("cache") ||
                    name.contains("disk") ||
                    name.contains("expiry")) {
                    System.out.println("EHCacheShutdownListener: Interrupting thread: " + thread.getName());
                    try {
                        thread.interrupt();
                        Thread.sleep(50);
                        if (thread.isAlive()) {
                            thread.stop();
                        }
                    } catch (Exception e) {
                        System.out.println("EHCacheShutdownListener: Could not stop thread: " + e.getMessage());
                    }
                }
            }
        }
        
        System.out.println("EHCacheShutdownListener: Context cleanup completed");
    }
}
EOF_EHCACHE
        echo "✓ EHCacheShutdownListener created"
    else
        FIXES_NEEDED+=("EHCacheShutdownListener.java missing")
    fi
else
    echo "✓ EHCacheShutdownListener exists"
fi

# Check web.xml has EHCacheShutdownListener registered
if ! grep -q "jp.aegif.nemaki.util.EHCacheShutdownListener" $NEMAKI_HOME/core/src/main/webapp/WEB-INF/web.xml; then
    if [ "$AUTO_FIX" = true ]; then
        echo "Adding EHCacheShutdownListener to web.xml..."
        # Create backup
        cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/web.xml $NEMAKI_HOME/core/src/main/webapp/WEB-INF/web.xml.bak
        # Add listener after ContextLoaderListener
        sed -i '' '/<listener-class>org.springframework.web.context.ContextLoaderListener<\/listener-class>/a\
    </listener>\
    \
    <!-- EHCache Shutdown Listener to prevent memory leaks and thread issues -->\
    <listener>\
        <listener-class>jp.aegif.nemaki.util.EHCacheShutdownListener</listener-class>' $NEMAKI_HOME/core/src/main/webapp/WEB-INF/web.xml
        echo "✓ EHCacheShutdownListener added to web.xml"
    else
        FIXES_NEEDED+=("web.xml needs EHCacheShutdownListener registration")
    fi
else
    echo "✓ EHCacheShutdownListener registered in web.xml"
fi

# Handle unfixed issues
if [ ${#FIXES_NEEDED[@]} -ne 0 ]; then
    echo ""
    echo "ERROR: The following critical fixes must be applied:"
    for fix in "${FIXES_NEEDED[@]}"; do
        echo "  ✗ $fix"
    done
    echo ""
    echo "Options:"
    echo "1. Apply fixes manually and commit them to git"
    echo "2. Run with --auto-fix flag to apply fixes automatically:"
    echo "   ./test-all.sh --auto-fix"
    echo ""
    exit 1
fi

echo ""

# ========================================
# 3. BUILD PROCESS
# ========================================
echo "3. BUILDING COMPONENTS..."
echo "----------------------------------------"

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

# Build the UI WAR file
echo "Building UI WAR with SBT (this may take a few minutes)..."
export SBT_OPTS="-Xmx2G -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled"

sbt clean compile war
if [ $? -ne 0 ]; then
  echo "ERROR: SBT build failed for UI"
  exit 1
fi

# Copy the built WAR files
if [ -f "target/ui.war" ]; then
  echo "Copying newly built UI WAR files..."
  mkdir -p $NEMAKI_HOME/docker/ui-war
  cp target/ui.war $NEMAKI_HOME/docker/ui-war/ui.war
  cp target/ui.war $NEMAKI_HOME/docker/ui-war/ui##.war
  echo "UI WAR files successfully created"
else
  echo "ERROR: No UI WAR file found"
  exit 1
fi

echo "Building core.war with complete configuration..."

# Always remove existing core.war to ensure fresh build
if [ -f "$NEMAKI_HOME/docker/core/core.war" ]; then
  echo "Removing existing core.war..."
  rm -f "$NEMAKI_HOME/docker/core/core.war"
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
solr.port=8080
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
log.aspect.expression=execution(* jp.aegif.nemaki.cmis.service..*Impl.*(..)) and !execution(* jp.aegif.nemaki.cmis.service.impl.RepositoryServiceImpl.*(..)
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

echo ""

# ========================================
# 4. DOCKER DEPLOYMENT
# ========================================
echo "4. DEPLOYING DOCKER ENVIRONMENT..."
echo "----------------------------------------"

echo "Starting Docker containers..."
cd $SCRIPT_DIR

# Stop and remove all containers to ensure clean state
docker compose -f docker-compose-simple.yml down --remove-orphans

echo "Building and starting containers..."
docker compose -f docker-compose-simple.yml build
docker compose -f docker-compose-simple.yml up -d --remove-orphans

echo "Waiting for containers to start..."
sleep 20

echo "Running repository initializers..."

# Clean initialization - remove all existing databases to ensure fresh start
echo "Cleaning existing databases for fresh initialization..."
for db in bedroom bedroom_closet canopy canopy_closet; do
    echo "Removing database ${db} if it exists..."
    curl -X DELETE -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" "http://localhost:5984/${db}" 2>/dev/null || echo "Database ${db} did not exist"
done

initialize_database() {
    local repo_id=$1
    local container_name="couchdb"
    
    local dump_file="/app/bedroom_init.dump"
    if [[ "${repo_id}" == *"_closet" ]]; then
        dump_file="/app/archive_init.dump"
        echo "Using archive dump file for ${repo_id}"
    elif [[ "${repo_id}" == "canopy" ]]; then
        dump_file="/app/bedroom_init.dump"
        echo "Using bedroom dump file for canopy repository (no canopy-specific dump available)"
    else
        echo "Using bedroom dump file for ${repo_id}"
    fi
    
    echo "CouchDB initializer for ${repo_id}:"
    
    echo "Checking CouchDB database ${repo_id}..."
    curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/${repo_id} | tee /tmp/couchdb_${repo_id}_check.json
    if ! cat /tmp/couchdb_${repo_id}_check.json | grep -q "db_name"; then
        echo "CouchDB database ${repo_id} does not exist"
        echo "Creating CouchDB database ${repo_id}..."
        curl -X PUT -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/${repo_id} | tee /tmp/couchdb_${repo_id}_create.json
    else
        echo "CouchDB database ${repo_id} exists"
    fi
    
    # Always use force=true to ensure proper repository initialization even if database exists
    # This is necessary because database creation and data initialization are separate steps
    local force_param="true"
    
    echo "Running bjornloka.jar initialization for ${repo_id} (force=${force_param})..."
    
    # Wait a moment between initializations to avoid conflicts
    sleep 2
    
    # Run the initializer with proper error handling
    if docker compose -f docker-compose-simple.yml run --rm --remove-orphans \
      -e COUCHDB_URL=http://${container_name}:5984 \
      -e COUCHDB_USERNAME=${COUCHDB_USER} \
      -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
      -e REPOSITORY_ID=${repo_id} \
      -e DUMP_FILE=${dump_file} \
      -e FORCE=${force_param} \
      initializer; then
        echo "✓ bjornloka.jar execution completed for ${repo_id}"
    else
        echo "✗ bjornloka.jar execution failed for ${repo_id}"
        return 1
    fi
    
    # Wait for database consistency
    sleep 3
    
    # Verify design documents were created
    echo "Verifying design documents for ${repo_id}..."
    local design_check=$(curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" "http://localhost:5984/${repo_id}/_design/_repo" | grep -o '"_id":"_design/_repo"' || echo "")
    if [ -n "$design_check" ]; then
        echo "✓ Design documents successfully created for ${repo_id}"
        
        # Also verify admin view exists (only for main repositories, not archives)
        if [[ "${repo_id}" != *"_closet" ]]; then
            local admin_view_check=$(curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" "http://localhost:5984/${repo_id}/_design/_repo/_view/admin?limit=0" 2>/dev/null | grep -o '"total_rows"' || echo "")
            if [ -n "$admin_view_check" ]; then
                echo "✓ Admin view is accessible for ${repo_id}"
            else
                echo "✗ WARNING: Admin view may not be accessible for ${repo_id}"
            fi
        else
            echo "✓ ${repo_id} is archive repository (admin view not required)"
        fi
    else
        echo "✗ ERROR: Design documents were not created for ${repo_id}"
        return 1
    fi
}

echo "=== STARTING REPOSITORY INITIALIZATION ==="

echo "1/4: Initializing bedroom..."
if ! initialize_database "bedroom"; then
    echo "ERROR: Failed to initialize bedroom repository"
    exit 1
fi

echo "2/4: Initializing bedroom_closet..."
if ! initialize_database "bedroom_closet"; then
    echo "ERROR: Failed to initialize bedroom_closet repository"  
    exit 1
fi

echo "3/4: Initializing canopy..."
if ! initialize_database "canopy"; then
    echo "ERROR: Failed to initialize canopy repository"
    exit 1
fi

echo "4/4: Initializing canopy_closet..."
if ! initialize_database "canopy_closet"; then
    echo "ERROR: Failed to initialize canopy_closet repository"
    exit 1
fi

echo "=== REPOSITORY INITIALIZATION COMPLETED ==="

# Final verification that all databases have the necessary views
echo "Final verification of all repositories..."
for repo in bedroom bedroom_closet canopy canopy_closet; do
    echo "Checking ${repo}..."
    
    # Check if design document exists
    if curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" "http://localhost:5984/${repo}/_design/_repo" 2>/dev/null | grep -q "_id"; then
        echo "✓ ${repo} design document exists"
        
        # Only check admin view for main repositories (not archive repositories)
        if [[ "${repo}" != *"_closet" ]]; then
            if curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" "http://localhost:5984/${repo}/_design/_repo/_view/admin?limit=0" 2>/dev/null | grep -q "total_rows"; then
                echo "✓ ${repo} admin view is working"
            else
                echo "✗ ERROR: ${repo} admin view is not accessible - this will cause Core startup failures"
                exit 1
            fi
        else
            echo "✓ ${repo} is archive repository (admin view not required)"
        fi
    else
        echo "✗ ERROR: ${repo} design document does not exist"
        exit 1
    fi
done
echo "✓ All repositories verified successfully"

echo "Waiting for Core to become healthy..."
sleep 30

echo "Restarting Core container to ensure proper patch application..."
docker compose -f docker-compose-simple.yml restart core
sleep 20

echo "Verifying and fixing UI runtime configuration..."
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

echo ""
echo "Configuring and initializing Solr indexing..."
configure_solr_indexing() {
    echo "1. Fixing Core nemakiware.properties for Solr internal communication..."
    # Fix Solr port in Core configuration (internal communication uses 8080, not 8983)
    if docker exec docker-core-1 grep -q "solr.port=8983" /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties 2>/dev/null; then
        docker exec docker-core-1 sed -i 's/solr.port=8983/solr.port=8080/' /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties
        echo "✓ Fixed Solr port in Core configuration"
        CORE_RESTART_NEEDED=true
    else
        echo "✓ Core Solr port configuration is already correct"
    fi
    
    # Enable force indexing
    if docker exec docker-core-1 grep -q "solr.indexing.force=false" /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties 2>/dev/null; then
        docker exec docker-core-1 sed -i 's/solr.indexing.force=false/solr.indexing.force=true/' /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties
        echo "✓ Enabled force indexing in Core configuration"
        CORE_RESTART_NEEDED=true
    fi
    
    echo ""
    echo "2. Fixing Solr nemakisolr.properties for CMIS server communication..."
    # Fix CMIS server host in Solr configuration
    if docker exec docker-solr-1 grep -q "cmis.server.host=127.0.0.1" /usr/local/tomcat/solr/conf/nemakisolr.properties 2>/dev/null; then
        docker exec docker-solr-1 sed -i 's/cmis.server.host=127.0.0.1/cmis.server.host=core/' /usr/local/tomcat/solr/conf/nemakisolr.properties
        echo "✓ Fixed CMIS server host in Solr configuration"
        SOLR_RESTART_NEEDED=true
    else
        echo "✓ Solr CMIS host configuration is already correct"
    fi
    
    # Restart containers if configuration changed
    if [ "$CORE_RESTART_NEEDED" = "true" ]; then
        echo "Restarting Core container to apply configuration changes..."
        docker compose -f docker-compose-simple.yml restart core
        sleep 20
    fi
    
    if [ "$SOLR_RESTART_NEEDED" = "true" ]; then
        echo "Restarting Solr container to apply configuration changes..."
        docker compose -f docker-compose-simple.yml restart solr
        sleep 20
    fi
    
    echo ""
    echo "3. Waiting for services to stabilize..."
    sleep 10
    
    echo ""
    echo "4. Initializing Solr indexes for all repositories..."
    # Initialize and index each repository
    for repo in bedroom canopy; do
        echo ""
        echo "Initializing index for repository: $repo"
        
        # Initialize repository
        INIT_RESPONSE=$(curl -s "http://localhost:8983/solr/admin/cores?action=INIT&core=nemaki&repositoryId=$repo")
        if echo "$INIT_RESPONSE" | grep -q "Successfully initialized"; then
            echo "✓ Successfully initialized $repo"
        else
            echo "⚠ Warning: Could not initialize $repo (may already be initialized)"
        fi
        
        # Perform full indexing
        echo "Performing full indexing for $repo..."
        INDEX_RESPONSE=$(curl -s "http://localhost:8983/solr/admin/cores?action=INDEX&core=nemaki&repositoryId=$repo&tracking=FULL")
        if echo "$INDEX_RESPONSE" | grep -q "Successfully tracked"; then
            echo "✓ Successfully indexed $repo"
        else
            echo "⚠ Warning: Indexing may have failed for $repo"
        fi
    done
    
    echo ""
    echo "5. Verifying Solr index status..."
    sleep 5
    INDEXED_DOCS=$(curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0&wt=json" | jq -r '.response.numFound // 0' 2>/dev/null || echo "0")
    echo "✓ Total documents indexed in Solr: $INDEXED_DOCS"
    
    if [ "$INDEXED_DOCS" -gt 0 ]; then
        echo "✓ Solr indexing is working correctly"
    else
        echo "⚠ Warning: No documents indexed yet. Indexing may take more time."
    fi
}

configure_solr_indexing

echo ""

# ========================================
# 5. SYSTEM TESTING
# ========================================
echo "5. TESTING SYSTEM FUNCTIONALITY..."
echo "----------------------------------------"

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
else
    echo "✗ Solr is not accessible (HTTP $SOLR_STATUS)"
fi

echo ""
echo "3. CORE TEST:"
echo "-------------"
echo "Testing CMIS endpoints with admin:admin authentication..."
CMIS_ATOM_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom || echo "connection_error")
echo "CMIS AtomPub (bedroom): HTTP $CMIS_ATOM_STATUS"

CMIS_SERVICES_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/services || echo "connection_error")
echo "CMIS Web Services: HTTP $CMIS_SERVICES_STATUS"

echo ""
echo "4. UI TEST:"
echo "-----------"
echo "Testing UI login page accessibility..."
UI_LOGIN_BEDROOM=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:9000/ui/repo/bedroom/login" || echo "connection_error")
echo "UI login page (bedroom): HTTP $UI_LOGIN_BEDROOM"

echo ""

# ========================================
# 6. SUMMARY REPORT
# ========================================
echo "=============================================="
echo "COMPLETE SYSTEM TEST SUMMARY"
echo "=============================================="

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
echo "UI Access:"
echo "- Primary: http://localhost:9000/ui/repo/bedroom/login"
echo ""
echo "Authentication: Use admin:admin for all endpoints"

# Check overall success
if [ "$COUCHDB_STATUS" = "200" ] && [ "$SOLR_STATUS" = "200" ] && [ "$CMIS_ATOM_STATUS" = "200" ] && [ "$CMIS_SERVICES_STATUS" = "200" ] && [ "$UI_LOGIN_BEDROOM" = "200" ]; then
    echo ""
    echo "🎉 SUCCESS: All components are running correctly!"
    echo "NemakiWare Docker environment is ready for use."
else
    echo ""
    echo "⚠ WARNING: Some components failed. Check the status above."
    echo "For debugging, run: docker compose -f docker-compose-simple.yml logs [service_name]"
fi

echo ""
echo "Test complete! NemakiWare running on CouchDB 2.x"

# ========================================
# ========================================

# Check for --run-tck flag
if [[ "$*" == *"--run-tck"* ]]; then
    echo ""
    echo "=========================================="
    echo "RUNNING TCK TESTS"
    echo "=========================================="
    echo "TCK execution requested via --run-tck flag"
    echo "Starting automated TCK test execution..."
    
    cd $SCRIPT_DIR
    ./run-tck.sh
    
    echo ""
    echo "=========================================="
    echo "TCK EXECUTION COMPLETED"
    echo "=========================================="
    echo "TCK reports available in: docker/tck-reports/"
    echo "View HTML summary: docker/tck-reports/tck-summary.html"
    
    if [ -f "$SCRIPT_DIR/tck-reports/current-score.txt" ]; then
        CURRENT_SCORE=$(cat "$SCRIPT_DIR/tck-reports/current-score.txt")
        echo "Current TCK Score: ${CURRENT_SCORE}%"
    fi
fi
