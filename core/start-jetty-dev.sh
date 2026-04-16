#!/bin/bash

# NemakiWare Jakarta EE 11 Development Environment Startup Script
# This script configures the Java 21 module system and starts Jetty for development

echo "=== NemakiWare Jakarta EE 11 Development Environment ==="
echo "Setting up Java 21 module system compatibility..."

# Set MAVEN_OPTS for Java 21 module system compatibility
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
echo "  - CMIS Service: http://localhost:8081/core/atom/bedroom (admin:admin)"
echo "  - Repository Info: http://localhost:8081/core/atom/bedroom"
echo "  - UI:             http://localhost:8081/core/ui/"
echo ""
echo "Development Features:"
echo "  - Jakarta EE 11 Compatible"
echo "  - Automatic code reloading (scan: 1s)"
echo "  - CouchDB + Solr Docker dependencies"
echo ""
echo "NOTE: Port 8081 is fixed by core/src/main/webapp/WEB-INF/jetty-forwarded.xml"
echo "      to avoid colliding with the Tomcat Docker container on 8080."
echo ""
echo "Press Ctrl+C to stop the server"
echo "========================================"
echo ""

# Start Jetty with development profile.
# Port is set in jetty-forwarded.xml; -Djetty.port has no effect on
# the connector defined there.
mvn jetty:run -f core/pom.xml -Pdevelopment