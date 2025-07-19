#!/bin/bash

# NemakiWare Jakarta EE 10 Development Environment Startup Script
# This script configures the Java 17 module system and starts Jetty for development

echo "=== NemakiWare Jakarta EE 10 Development Environment ==="
echo "Setting up Java 17 module system compatibility..."

# Set MAVEN_OPTS for Java 17 module system compatibility
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

echo "MAVEN_OPTS configured: $MAVEN_OPTS"
echo ""

# Verify Java version
echo "Java version verification:"
java -version
echo ""

# Verify Maven version
echo "Maven version verification:"
mvn -version | head -3
echo ""

# Check if CouchDB is running
echo "Checking CouchDB connectivity..."
if curl -s -u admin:password http://localhost:5984/ > /dev/null 2>&1; then
    echo "✓ CouchDB is running and accessible"
else
    echo "⚠ CouchDB is not accessible. Please start CouchDB Docker container:"
    echo "  docker run -d --name couchdb-dev -p 5984:5984 \\"
    echo "    -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password \\"
    echo "    -v couchdb-dev-data:/opt/couchdb/data couchdb:3"
    echo ""
fi

echo "Starting Jetty development server..."
echo "Access Points:"
echo "  - CMIS Service: http://localhost:8080/core/atom/bedroom (admin:admin)"
echo "  - Repository Info: http://localhost:8080/core/atom/bedroom"
echo "  - Folder Operations: http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff"
echo ""
echo "Development Features:"
echo "  - Jakarta EE 10 Compatible"
echo "  - Solr Disabled (MockSolrUtil + MockQueryProcessor)"
echo "  - Automatic code reloading"
echo "  - CouchDB only dependency"
echo ""
echo "Press Ctrl+C to stop the server"
echo "========================================"
echo ""

# Start Jetty with development profile (Jakarta dependencies already unified)
mvn jetty:run -Pdevelopment -Djetty.port=8080