#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "========================================"
echo "Quick Fix for Core Startup Issues"
echo "========================================"

echo "1. Ensuring dependencies are built..."

# Build cloudant-init JAR if missing
if [ ! -f "$NEMAKI_HOME/setup/couchdb/cloudant-init/target/cloudant-init-jar-with-dependencies.jar" ]; then
    echo "Building cloudant-init JAR..."
    cd $NEMAKI_HOME/setup/couchdb/cloudant-init
    mvn clean package -DskipTests
else
    echo "✓ cloudant-init JAR exists"
fi

# Prepare initializer
echo "2. Preparing initializer..."
cd $NEMAKI_HOME/docker
./prepare-initializer.sh

# Apply critical source code fixes
echo "3. Applying critical Core fixes..."

# Remove @PostConstruct from PatchService
if grep -q "@PostConstruct" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java; then
    echo "Removing @PostConstruct from PatchService..."
    sed -i '.bak' '/@PostConstruct/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
    sed -i '.bak2' '/import javax.annotation.PostConstruct;/d' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/patch/PatchService.java
    echo "✓ PatchService @PostConstruct removed"
else
    echo "✓ PatchService @PostConstruct already removed"
fi

# Fix Ektorp cleanupIdleConnections
if grep -q "cleanupIdleConnections(true)" $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java; then
    echo "Fixing Ektorp IdleConnectionMonitor..."
    sed -i '.bak' 's/cleanupIdleConnections(true)/cleanupIdleConnections(false)/' $NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/ConnectorPool.java
    echo "✓ ConnectorPool cleanupIdleConnections disabled"
else
    echo "✓ ConnectorPool cleanupIdleConnections already disabled"
fi

# Build Solr WAR if missing
echo "4. Checking Solr WAR..."
if [ ! -f "$NEMAKI_HOME/docker/solr/solr.war" ]; then
    echo "Building Solr WAR..."
    ./build-solr.sh
else
    echo "✓ Solr WAR exists"
fi

# Build UI WAR if missing
echo "5. Checking UI WAR..."
if [ ! -f "$NEMAKI_HOME/docker/ui-war/ui.war" ]; then
    echo "UI WAR missing. Please run UI build manually:"
    echo "  cd $NEMAKI_HOME/ui"
    echo "  sbt clean compile war"
    echo "  cp target/ui.war $NEMAKI_HOME/docker/ui-war/"
else
    echo "✓ UI WAR exists"
fi

echo ""
echo "========================================"
echo "Quick fix completed!"
echo "Now you can run: ./test-simple.sh"
echo "========================================"