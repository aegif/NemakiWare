#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}

echo "Using CouchDB with authentication (required for CouchDB 3.x):"
echo "Username: $COUCHDB_USER"
echo "Password: $COUCHDB_PASSWORD (masked for security)"

echo "Stopping any running containers..."
cd $SCRIPT_DIR
docker compose -f docker-compose-war.yml down --remove-orphans

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

// addSbtPlugin("com.typesafe.sbt" % "sbt-play-ebean" % "1.0.0")

addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "1.4.0")

//addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.5.0")

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
  sed -i '.bak2' 's/ContentType.APPLICATION_XML.getMimeType()/"application\/xml"/' app/controllers/Application.java
  echo "Fixed ContentType usage in Application.java"
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

# Build the UI WAR file
echo "Building UI WAR with SBT (this may take a few minutes)..."
echo "Running: sbt clean compile war"

# Set SBT options for better performance and to avoid timeout
export SBT_OPTS="-Xmx2G -XX:+UseG1GC -Dsbt.global.base=$HOME/.sbt/global"

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

# EktorpThreadKiller is no longer needed since we disable cleanupIdleConnections at source
echo "Ektorp thread management: cleanupIdleConnections disabled in source code - no external thread killer needed"

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

# Fix Ektorp IdleConnectionMonitor issue by disabling cleanupIdleConnections in ConnectorPool
echo "Fixing Ektorp IdleConnectionMonitor issue in ConnectorPool..."
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java; then
  echo "Disabling cleanupIdleConnections to prevent thread leaks..."
  sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java
  echo "ConnectorPool cleanupIdleConnections disabled"
fi

# First, update the source nemakiware.properties with complete configuration
echo "Updating source nemakiware.properties with complete configuration..."
cat > $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/nemakiware.properties << EOF_PROPERTIES
###Database
db.couchdb.url=http://couchdb2:5984
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

# Create enhanced Dockerfile with EktorpThreadKiller integration
echo "Creating enhanced Dockerfile with EktorpThreadKiller integration..."
cat > $NEMAKI_HOME/docker/core/Dockerfile << 'EOF_DOCKERFILE'
FROM tomcat:9-jre8

# Set environment variables
ENV CATALINA_HOME /usr/local/tomcat
ENV CATALINA_BASE /usr/local/tomcat

# Install OpenJDK for compilation and curl for CouchDB connectivity testing
RUN apt-get update && apt-get install -y openjdk-8-jdk-headless curl && rm -rf /var/lib/apt/lists/*

# Remove default Tomcat applications
RUN rm -rf $CATALINA_HOME/webapps/*

# Create necessary directories
RUN mkdir -p $CATALINA_HOME/conf/Catalina/localhost/
RUN mkdir -p $CATALINA_HOME/lib/custom/

# Copy the WAR file
COPY ./core.war $CATALINA_HOME/webapps/core.war

# Extract WAR to ensure proper deployment structure - remove any existing extraction first
RUN cd $CATALINA_HOME/webapps && rm -rf core && mkdir -p core && cd core && jar -xf ../core.war

# Copy context configuration
COPY ./context.xml $CATALINA_HOME/conf/Catalina/localhost/core.xml

# Copy configuration files - using config2 as default, can be overridden at runtime
COPY ./config2/nemakiware.properties $CATALINA_HOME/conf/nemakiware.properties
# Also copy to classpath so Spring can find it (overwrite the one in WAR)
COPY ./config2/nemakiware.properties $CATALINA_HOME/webapps/core/WEB-INF/classes/nemakiware.properties
# Don't override Spring config files - they are already properly included in the WAR
COPY ./repositories.yml $CATALINA_HOME/conf/repositories.yml
COPY ./log4j.properties $CATALINA_HOME/conf/log4j.properties

# Note: No longer using wait-for-couchdb.sh as we implement lazy initialization

# Copy and compile the thread killers for Ektorp and EHCache
COPY ./EktorpThreadKiller.java /tmp/
COPY ./EHCacheThreadKiller.java /tmp/
COPY ./EHCacheShutdownListener.java /tmp/
RUN javac -cp $CATALINA_HOME/lib/*:$CATALINA_HOME/webapps/core/WEB-INF/lib/* -d $CATALINA_HOME/lib/custom/ /tmp/EktorpThreadKiller.java
RUN javac -cp $CATALINA_HOME/lib/*:$CATALINA_HOME/webapps/core/WEB-INF/lib/* -d $CATALINA_HOME/lib/custom/ /tmp/EHCacheThreadKiller.java
RUN javac -cp $CATALINA_HOME/lib/*:$CATALINA_HOME/webapps/core/WEB-INF/lib/* -d $CATALINA_HOME/lib/custom/ /tmp/EHCacheShutdownListener.java

# Create a startup script that waits for CouchDB and runs the thread killers before starting Tomcat
RUN echo '#!/bin/bash\n\
echo "Clearing Tomcat cache directories..."\n\
rm -rf $CATALINA_HOME/work/*\n\
rm -rf $CATALINA_HOME/temp/*\n\
echo "Cache cleared successfully."\n\
\n\
echo "Re-extracting WAR file for clean deployment..."\n\
cd $CATALINA_HOME/webapps && rm -rf core && mkdir -p core && cd core && jar -xf ../core.war\n\
echo "WAR re-extraction completed."\n\
\n\
echo "Copying configuration files based on environment..."\n\
if [ -f "/usr/local/tomcat/conf/nemakiware.properties" ]; then\n\
  cp /usr/local/tomcat/conf/nemakiware.properties /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties\n\
  echo "Configuration files copied."\n\
fi\n\
\n\
# Check if this is Core3 and copy Core3-specific configuration\n\
if [ -d "/usr/local/tomcat/core-config3" ]; then\n\
  echo "Detected Core3 environment - applying Core3-specific configuration..."\n\
  if [ -f "/usr/local/tomcat/core-config3/nemakiware.properties" ]; then\n\
    cp /usr/local/tomcat/core-config3/nemakiware.properties /usr/local/tomcat/conf/nemakiware.properties\n\
    cp /usr/local/tomcat/core-config3/nemakiware.properties /usr/local/tomcat/webapps/core/WEB-INF/classes/nemakiware.properties\n\
    echo "Core3 nemakiware.properties applied"\n\
  fi\n\
  if [ -f "/usr/local/tomcat/core-config3/WEB-INF/classes/ehcache.xml" ]; then\n\
    cp /usr/local/tomcat/core-config3/WEB-INF/classes/ehcache.xml /usr/local/tomcat/webapps/core/WEB-INF/classes/ehcache.xml\n\
    echo "Core3 ehcache.xml applied"\n\
  fi\n\
  if [ -f "/usr/local/tomcat/core-config3/WEB-INF/classes/businesslogicContext.xml" ]; then\n\
    cp /usr/local/tomcat/core-config3/WEB-INF/classes/businesslogicContext.xml /usr/local/tomcat/webapps/core/WEB-INF/classes/businesslogicContext.xml\n\
    echo "Core3 businesslogicContext.xml applied"\n\
  fi\n\
  echo "Core3 configuration application completed"\n\
fi\n\
\n\
echo "Waiting for CouchDB to be ready..."\n\
# Wait for CouchDB to be accessible using curl (which works)\n\
# Check if this is Core3 environment and use appropriate CouchDB\n\
COUCHDB_URL="http://couchdb2:5984"\n\
if [ -d "/usr/local/tomcat/core-config3" ]; then\n\
  COUCHDB_URL="http://couchdb3:5984"\n\
fi\n\
for i in {1..60}; do\n\
  if curl -s $COUCHDB_URL > /dev/null 2>&1; then\n\
    echo "CouchDB is ready!"\n\
    break\n\
  fi\n\
  echo "Waiting for CouchDB (attempt $i/60)..."\n\
  sleep 5\n\
done\n\
\n\
# Set system properties to disable both Ektorp and EHCache thread creation BEFORE starting Tomcat\n\
export JAVA_OPTS="$JAVA_OPTS -Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=true -Dnet.sf.ehcache.enableShutdownHook=true -Dnet.sf.ehcache.statisticsEnabled=false -Dnet.sf.ehcache.cache.statisticsEnabled=false -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"\n\
\n\
# Create a shutdown hook script to clean up both Ektorp and EHCache threads on Tomcat shutdown\n\
echo "#!/bin/bash" > $CATALINA_HOME/bin/cleanup-threads.sh\n\
echo "java -cp $CATALINA_HOME/lib/custom/ EktorpThreadKiller" >> $CATALINA_HOME/bin/cleanup-threads.sh\n\
echo "java -cp $CATALINA_HOME/lib/custom/ EHCacheThreadKiller" >> $CATALINA_HOME/bin/cleanup-threads.sh\n\
chmod +x $CATALINA_HOME/bin/cleanup-threads.sh\n\
\n\
# Start Tomcat in background and setup cleanup\n\
trap "$CATALINA_HOME/bin/cleanup-threads.sh; exit" SIGTERM SIGINT\n\
\n\
# Start Tomcat\n\
exec catalina.sh run\n\
' > $CATALINA_HOME/bin/startup-with-thread-killer.sh

# Make the startup script executable
RUN chmod +x $CATALINA_HOME/bin/startup-with-thread-killer.sh

# Set environment variables for Ektorp and EHCache thread management
ENV JAVA_OPTS="-Dorg.ektorp.support.AutoUpdateViewOnChange=false -Dorg.ektorp.http.IdleConnectionMonitor.enabled=false -Dorg.ektorp.support.DesignDocument.UPDATE_ON_DIFF=false -Dorg.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE=true -Dnet.sf.ehcache.enableShutdownHook=true -Dnet.sf.ehcache.statisticsEnabled=false -Dnet.sf.ehcache.cache.statisticsEnabled=false -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true -Dorg.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip=*.jar"
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= -Dhttp.nonProxyHosts=* -Djava.net.useSystemProxies=false -Dnetworkaddress.cache.ttl=60 -Dnemakiware.properties=/usr/local/tomcat/conf/nemakiware.properties -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml -Dlog4j.configuration=file:/usr/local/tomcat/conf/log4j.properties"

# Expose the default Tomcat port
EXPOSE 8080

# Start directly with the startup script (lazy initialization handles CouchDB connectivity)
CMD ["/usr/local/tomcat/bin/startup-with-thread-killer.sh"]
EOF_DOCKERFILE

echo "Enhanced Dockerfile created with EktorpThreadKiller integration"

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

mkdir -p $NEMAKI_HOME/docker/core/config2/WEB-INF/classes
mkdir -p $NEMAKI_HOME/docker/core/config3/WEB-INF/classes

# Copy essential Spring configuration files to config2
echo "Copying Spring configuration files to config2..."
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/applicationContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/businesslogicContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/couchContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/daoContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/logContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/propertyContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/
cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/serviceContext.xml $NEMAKI_HOME/docker/core/config2/WEB-INF/classes/

# Copy Spring configuration files to config3 - skip if custom versions already exist
echo "Copying Spring configuration files to config3..."
# Only copy files that don't have custom versions for Core3
[ ! -f "$NEMAKI_HOME/docker/core/config3/WEB-INF/classes/applicationContext.xml" ] && cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/applicationContext.xml $NEMAKI_HOME/docker/core/config3/WEB-INF/classes/
echo "Preserving custom businesslogicContext.xml for Core3 (cache disabled)"
# Skip businesslogicContext.xml - using custom version for Core3
[ ! -f "$NEMAKI_HOME/docker/core/config3/WEB-INF/classes/couchContext.xml" ] && cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/couchContext.xml $NEMAKI_HOME/docker/core/config3/WEB-INF/classes/
[ ! -f "$NEMAKI_HOME/docker/core/config3/WEB-INF/classes/daoContext.xml" ] && cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/daoContext.xml $NEMAKI_HOME/docker/core/config3/WEB-INF/classes/
[ ! -f "$NEMAKI_HOME/docker/core/config3/WEB-INF/classes/logContext.xml" ] && cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/logContext.xml $NEMAKI_HOME/docker/core/config3/WEB-INF/classes/
[ ! -f "$NEMAKI_HOME/docker/core/config3/WEB-INF/classes/propertyContext.xml" ] && cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/propertyContext.xml $NEMAKI_HOME/docker/core/config3/WEB-INF/classes/
[ ! -f "$NEMAKI_HOME/docker/core/config3/WEB-INF/classes/serviceContext.xml" ] && cp $NEMAKI_HOME/core/src/main/webapp/WEB-INF/classes/serviceContext.xml $NEMAKI_HOME/docker/core/config3/WEB-INF/classes/
echo "Preserving custom ehcache.xml for Core3 (unique cache configuration)"
# ehcache.xml - using custom version for Core3

# Ensure repositories.yml is properly copied
echo "Copying repositories.yml to core directory..."
if [ -f "$NEMAKI_HOME/docker/core/repositories.yml" ]; then
  cp $NEMAKI_HOME/docker/core/repositories.yml $NEMAKI_HOME/docker/core/config2/
  cp $NEMAKI_HOME/docker/core/repositories.yml $NEMAKI_HOME/docker/core/config3/
fi

# Generate complete nemakiware.properties for Docker config2
cat > $NEMAKI_HOME/docker/core/config2/nemakiware.properties << EOF3
###Database
db.couchdb.url=http://couchdb2:5984
db.couchdb.max.connections=20
db.couchdb.connection.timeout=30000
db.couchdb.socket.timeout=60000
db.couchdb.auth.enabled=true
db.couchdb.auth.username=${COUCHDB_USER:-admin}
db.couchdb.auth.password=${COUCHDB_PASSWORD:-password}

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
solr.host=solr2
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
EOF3

# Copy the Core3 configuration to the main docker/core directory (for Core3 containers)
cp $NEMAKI_HOME/docker/core/config3/nemakiware.properties $NEMAKI_HOME/docker/core/nemakiware.properties

#COMMENTED: cat > $NEMAKI_HOME/docker/core/config3/nemakiware.properties << EOF4
#COMMENTED: db.couchdb.url=http://couchdb3:5984
#COMMENTED: db.couchdb.user=${COUCHDB_USER:-admin}
#COMMENTED: db.couchdb.password=${COUCHDB_PASSWORD:-password}
#COMMENTED: EOF4

echo "Creating log4j.properties if it doesn't exist..."
if [ ! -f $NEMAKI_HOME/docker/core/log4j.properties ]; then
  touch $NEMAKI_HOME/docker/core/log4j.properties
fi

echo "Starting Docker containers with health checks..."
cd $SCRIPT_DIR

# Stop and remove all containers to ensure clean state
docker compose -f docker-compose-war.yml down --remove-orphans

# Clear any cached volumes
echo "Clearing Docker volumes and cached data..."
docker volume prune -f

echo "Building and starting containers..."
docker compose -f docker-compose-war.yml build initializer2 ui2-war
docker compose -f docker-compose-war.yml up -d --remove-orphans

echo "Waiting for Core containers to become healthy..."
# Wait for health checks to pass instead of manual restart
echo "Checking Core health status..."
for i in {1..20}; do
    CORE2_HEALTH=$(docker inspect docker-core2-1 --format='{{.State.Health.Status}}' 2>/dev/null || echo "unknown")
    CORE3_HEALTH=$(docker inspect docker-core3-1 --format='{{.State.Health.Status}}' 2>/dev/null || echo "unknown")
    echo "Attempt $i/20: Core2=$CORE2_HEALTH, Core3=$CORE3_HEALTH"
    
    if [ "$CORE2_HEALTH" = "healthy" ] && [ "$CORE3_HEALTH" = "healthy" ]; then
        echo "✓ Both Core containers are healthy"
        break
    fi
    sleep 30
done

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
    elif [[ "${repo_id}" == "canopy" ]]; then
        dump_file="/app/bedroom_init.dump"
        echo "Using bedroom dump file for canopy repository (no canopy-specific dump available)"
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
    
    # Always use force=true to ensure proper repository initialization even if database exists
    # This is necessary because database creation and data initialization are separate steps
    local force_param="true"
    
    # Always use authentication (required for CouchDB 3.x compatibility)
    couchdb_url="http://${COUCHDB_USER}:${COUCHDB_PASSWORD}@${container_name}:5984"
    echo "Using authenticated CouchDB URL for bjornloka.jar"
    echo "Executing: bjornloka.jar ${couchdb_url} ${repo_id} ${dump_file} ${force_param}"
    
    docker compose -f docker-compose-war.yml run --rm --remove-orphans \
      -e COUCHDB_URL=http://${container_name}:5984 \
      -e COUCHDB_USERNAME=${COUCHDB_USER} \
      -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
      -e REPOSITORY_ID=${repo_id} \
      -e DUMP_FILE=${dump_file} \
      -e FORCE=${force_param} \
      --entrypoint java \
      initializer${couchdb_version} -Xmx512m -Dlog.level=DEBUG -cp /app/bjornloka.jar jp.aegif.nemaki.bjornloka.Load \
      ${couchdb_url} ${repo_id} ${dump_file} ${force_param}
}

echo "=== STARTING REPOSITORY INITIALIZATION ==="
echo "Initializing CouchDB 2.x repositories..."

echo "1/4: Initializing bedroom..."
initialize_database "2" "bedroom" "5984" "couchdb2"

echo "2/4: Initializing bedroom_closet..."
initialize_database "2" "bedroom_closet" "5984" "couchdb2"

echo "3/4: Initializing canopy..."
initialize_database "2" "canopy" "5984" "couchdb2"

echo "4/4: Initializing canopy_closet..."
initialize_database "2" "canopy_closet" "5984" "couchdb2"

echo "=== CouchDB 2.x REPOSITORY INITIALIZATION COMPLETED ==="

echo "Initializing CouchDB 3.x repositories..."

echo "1/4: Initializing bedroom..."
initialize_database "3" "bedroom" "5985" "couchdb3"

echo "2/4: Initializing bedroom_closet..."
initialize_database "3" "bedroom_closet" "5985" "couchdb3"

echo "3/4: Initializing canopy..."
initialize_database "3" "canopy" "5985" "couchdb3"

echo "4/4: Initializing canopy_closet..."
initialize_database "3" "canopy_closet" "5985" "couchdb3"

echo "=== CouchDB 3.x REPOSITORY INITIALIZATION COMPLETED ==="

echo "=============================================="
echo "MODULE TESTING - INDEPENDENT COMPONENT CHECKS"
echo "=============================================="

echo "Verifying database initialization..."
echo "CouchDB 2.x database:"
echo "DEBUG: Running: curl -s -u \"${COUCHDB_USER}:${COUCHDB_PASSWORD}\" http://localhost:5984/bedroom"
curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/bedroom | tee /tmp/couchdb2_response.json
cat /tmp/couchdb2_response.json | grep -q "db_name" && echo "✓ SUCCESS: CouchDB 2.x database exists" || echo "✗ ERROR: CouchDB 2.x database does not exist"

echo ""
echo "1. COUCHDB MODULE TEST:"
echo "------------------------"
COUCHDB_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:5984/ || echo "connection_error")
if [ "$COUCHDB_STATUS" = "200" ]; then
    echo "✓ CouchDB 2.x is accessible (HTTP $COUCHDB_STATUS)"
    COUCHDB_AUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/_all_dbs || echo "connection_error")
    if [ "$COUCHDB_AUTH_STATUS" = "200" ]; then
        echo "✓ CouchDB 2.x authentication works (HTTP $COUCHDB_AUTH_STATUS)"
        DB_COUNT=$(curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" http://localhost:5984/_all_dbs | jq length 2>/dev/null || echo "0")
        echo "✓ CouchDB 2.x has $DB_COUNT databases"
    else
        echo "✗ CouchDB 2.x authentication failed (HTTP $COUCHDB_AUTH_STATUS)"
    fi
else
    echo "✗ CouchDB 2.x is not accessible (HTTP $COUCHDB_STATUS)"
fi

echo ""
echo "2. SOLR MODULE TEST:"
echo "--------------------"
SOLR_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8983/solr/ || echo "connection_error")
if [ "$SOLR_STATUS" = "200" ]; then
    echo "✓ Solr 2.x is accessible (HTTP $SOLR_STATUS)"
    SOLR_CORES=$(curl -s http://localhost:8983/solr/admin/cores?action=STATUS 2>/dev/null | grep -o '"name":"[^"]*"' | wc -l || echo "0")
    echo "✓ Solr 2.x has $SOLR_CORES cores configured"
else
    echo "✗ Solr 2.x is not accessible (HTTP $SOLR_STATUS)"
fi

echo ""
echo "3. CORE MODULE TEST:"
echo "--------------------"
CORE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ || echo "connection_error")
echo "Core Tomcat server status: HTTP $CORE_STATUS"

# Test Core application deployment
CORE_APP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ || echo "connection_error")
echo "Core application status: HTTP $CORE_APP_STATUS"

# Test specific CMIS endpoints with proper authentication
echo "Testing CMIS endpoints with admin:admin authentication..."
CMIS_ATOM_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/bedroom || echo "connection_error")
echo "CMIS AtomPub (bedroom): HTTP $CMIS_ATOM_STATUS"

CMIS_ATOM_CANOPY_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/atom/canopy || echo "connection_error")
echo "CMIS AtomPub (canopy): HTTP $CMIS_ATOM_CANOPY_STATUS"

CMIS_BROWSER_STATUS=$(curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8080/core/browser/bedroom || echo "connection_error")
echo "CMIS Browser (bedroom): HTTP $CMIS_BROWSER_STATUS"

CMIS_SERVICES_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/services || echo "connection_error")
echo "CMIS Web Services: HTTP $CMIS_SERVICES_STATUS"

# Check if Core application started successfully
echo "Checking Core application startup status..."
CORE_STARTUP_SUCCESS=$(docker logs docker-core2-1 2>&1 | grep -c "Server startup in" 2>/dev/null || echo "0")
CORE_STARTUP_ERRORS=$(docker logs docker-core2-1 2>&1 | grep -c "startup failed\|SEVERE\|Exception" 2>/dev/null || echo "0")

if [ "$CORE_STARTUP_SUCCESS" -gt "0" ]; then
    echo "✓ Core Tomcat server started successfully"
else
    echo "✗ Core Tomcat server startup unclear"
fi

if [ "$CORE_STARTUP_ERRORS" -gt "0" ]; then
    echo "✗ Core application has $CORE_STARTUP_ERRORS startup errors"
    echo "Recent Core errors:"
    docker logs docker-core2-1 2>&1 | grep -i "severe\|exception\|error" | tail -5
else
    echo "✓ Core application started without errors"
fi

# Test Core's CouchDB connectivity from inside container
echo "Testing Core's CouchDB connectivity:"
CORE_TO_COUCHDB=$(docker exec docker-core2-1 curl -s -o /dev/null -w "%{http_code}" http://couchdb2:5984/ || echo "connection_error")
echo "Core -> CouchDB connection: HTTP $CORE_TO_COUCHDB"

if [ "$CORE_TO_COUCHDB" = "200" ]; then
    echo "✓ Core can reach CouchDB"
else
    echo "✗ Core cannot reach CouchDB"
fi

echo ""
echo "4. UI MODULE TEST:"
echo "------------------"
UI_TOMCAT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ || echo "connection_error")
echo "UI Tomcat server status: HTTP $UI_TOMCAT_STATUS"

# Test UI application deployment
UI_APP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui/ || echo "connection_error")
echo "UI application status: HTTP $UI_APP_STATUS"

# If UI containers haven't started due to Core health issues, start them manually
if [ "$UI_TOMCAT_STATUS" = "connection_error" ]; then
    echo "UI containers not started due to Core health dependency. Starting UI manually..."
    docker compose -f docker-compose-war.yml up -d ui2-war ui3-war --no-deps
    sleep 20
    UI_TOMCAT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ || echo "connection_error")
    echo "UI Tomcat server status after manual start: HTTP $UI_TOMCAT_STATUS"
fi

# Check UI application startup
echo "Checking UI application startup status..."
UI_STARTUP_SUCCESS=$(docker logs docker-ui2-war-1 2>&1 | grep -c "Server startup in" || echo "0")
UI_STARTUP_ERRORS=$(docker logs docker-ui2-war-1 2>&1 | grep -c "startup failed\|SEVERE\|Exception" || echo "0")

if [ "$UI_STARTUP_SUCCESS" -gt "0" ]; then
    echo "✓ UI Tomcat server started successfully"
else
    echo "✗ UI Tomcat server startup unclear"
fi

if [ "$UI_STARTUP_ERRORS" -gt "0" ]; then
    echo "✗ UI application has $UI_STARTUP_ERRORS startup errors"
    echo "Recent UI errors:"
    docker logs docker-ui2-war-1 2>&1 | grep -i "severe\|exception\|error" | tail -3
else
    echo "✓ UI application started without errors"
fi

# Check UI WAR deployment
echo "Checking UI WAR deployment:"
UI_WAR_DEPLOYED=$(docker exec docker-ui2-war-1 ls /usr/local/tomcat/webapps/ | grep -c "ui" || echo "0")
if [ "$UI_WAR_DEPLOYED" -gt "0" ]; then
    echo "✓ UI WAR file is deployed"
    UI_CLASSES_COUNT=$(docker exec docker-ui2-war-1 find /usr/local/tomcat/webapps/ui/WEB-INF/classes -name "*.class" 2>/dev/null | wc -l || echo "0")
    echo "UI compiled classes found: $UI_CLASSES_COUNT"
    if [ "$UI_CLASSES_COUNT" -eq "0" ]; then
        echo "⚠ WARNING: UI WAR contains no compiled classes (this may cause 404 errors)"
    fi
else
    echo "✗ UI WAR file is not deployed"
fi

# Test UI to Core connectivity
echo "Testing UI to Core connectivity:"
UI_TO_CORE=$(docker exec docker-ui2-war-1 curl -s -o /dev/null -w "%{http_code}" http://core2:8080/ || echo "connection_error")
echo "UI -> Core Tomcat: HTTP $UI_TO_CORE"

UI_TO_CORE_APP=$(docker exec docker-ui2-war-1 curl -s -o /dev/null -w "%{http_code}" http://core2:8080/core/ || echo "connection_error")
echo "UI -> Core App: HTTP $UI_TO_CORE_APP"

if [ "$UI_TO_CORE" = "200" ]; then
    echo "✓ UI can reach Core Tomcat server"
else
    echo "✗ UI cannot reach Core Tomcat server"
fi

echo ""
echo "=============================================="
echo "INTEGRATION TEST SUMMARY"
echo "=============================================="

# Overall status summary
echo "Component Status Summary:"
echo "- CouchDB 2.x: $([ "$COUCHDB_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed")"
echo "- Solr 2.x: $([ "$SOLR_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed")"
echo "- Core Tomcat: $([ "$CORE_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed")"
echo "- Core App: $([ "$CORE_APP_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed (HTTP $CORE_APP_STATUS)")"
echo "- UI Tomcat: $([ "$UI_TOMCAT_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed")"  
echo "- UI App: $([ "$UI_APP_STATUS" = "200" ] && echo "✓ Running" || echo "✗ Failed (HTTP $UI_APP_STATUS)")"

echo ""
echo "Service Endpoints:"
echo "- CouchDB 2.x: http://localhost:5984/ ($([ "$COUCHDB_STATUS" = "200" ] && echo "accessible" || echo "inaccessible"))"
echo "- Solr 2.x: http://localhost:8983/solr/ ($([ "$SOLR_STATUS" = "200" ] && echo "accessible" || echo "inaccessible"))"
echo "- Core CMIS AtomPub (bedroom): http://localhost:8080/core/atom/bedroom (HTTP $CMIS_ATOM_STATUS)"
echo "- Core CMIS AtomPub (canopy): http://localhost:8080/core/atom/canopy (HTTP $CMIS_ATOM_CANOPY_STATUS)"
echo "- Core CMIS Browser: http://localhost:8080/core/browser/bedroom (HTTP $CMIS_BROWSER_STATUS)"
echo "- Core CMIS Services: http://localhost:8080/core/services (HTTP $CMIS_SERVICES_STATUS)"
echo "- UI Interface: http://localhost:9000/ui/ (HTTP $UI_APP_STATUS)"
echo ""
echo "Authentication: Use admin:admin for CMIS endpoints"

# Provide specific troubleshooting information
if [ "$CORE_APP_STATUS" != "200" ]; then
    echo ""
    echo "CORE TROUBLESHOOTING:"
    echo "The Core application is not responding properly. This could be due to:"
    echo "1. Spring application context failed to start (check logs above)"
    echo "2. CouchDB connection issues (connection status: HTTP $CORE_TO_COUCHDB)"
    echo "3. Missing configuration files or dependencies"
    echo "To debug: docker logs docker-core2-1 | grep -E 'SEVERE|Exception|startup failed'"
fi

if [ "$UI_APP_STATUS" != "200" ]; then
    echo ""
    echo "UI TROUBLESHOOTING:"
    echo "The UI application is not responding properly. This could be due to:"
    echo "1. Missing compiled Java classes in WAR file (found: $UI_CLASSES_COUNT classes)"
    echo "2. Play Framework dependencies not available"
    echo "3. UI configuration issues"
    echo "To debug: docker logs docker-ui2-war-1 | grep -E 'SEVERE|Exception|startup failed'"
fi

echo "Test complete!"
