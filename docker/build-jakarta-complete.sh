#!/bin/bash
# Complete Jakarta EE build script with permanent fixes
# This script ensures all fixes are applied and survive rebuilds

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEMAKI_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Starting complete Jakarta EE build with permanent fixes ==="
echo "NemakiWare home: $NEMAKI_HOME"

# Step 1: Apply source-level fixes before building
echo "=== Step 1: Applying source-level fixes ==="

# Fix TypeManagerImpl for Cloudant Document casting
echo "Fixing TypeManagerImpl..."
if [ -f "$NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java" ]; then
    # Backup original
    cp "$NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java" \
       "$NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java.backup"
    
    # Apply fix using sed
    sed -i '' '/@SuppressWarnings("unchecked")/,/Map<String, Object> typeMap = objectMapper.convertValue(doc, Map.class);/ {
        s/Map<String, Object> typeMap = objectMapper.convertValue(doc, Map.class);/Map<String, Object> typeMap;\
                if (doc instanceof com.ibm.cloud.cloudant.v1.model.Document) {\
                    typeMap = new HashMap<>();\
                    com.ibm.cloud.cloudant.v1.model.Document cloudantDoc = (com.ibm.cloud.cloudant.v1.model.Document) doc;\
                    cloudantDoc.getProperties().forEach((k, v) -> typeMap.put(k, v));\
                } else {\
                    typeMap = objectMapper.convertValue(doc, Map.class);\
                }/
    }' "$NEMAKI_HOME/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java"
    echo "TypeManagerImpl fix applied"
fi

# Step 2: Ensure Solr module is included in core pom.xml
echo "=== Step 2: Verifying Solr module dependency ==="
if ! grep -q "<artifactId>solr</artifactId>" "$NEMAKI_HOME/core/pom.xml"; then
    echo "ERROR: Solr module dependency missing in core/pom.xml"
    echo "Please add the following to core/pom.xml dependencies:"
    echo "
    <dependency>
        <groupId>jp.aegif.nemaki.solr</groupId>
        <artifactId>solr</artifactId>
        <version>2.4.1</version>
    </dependency>"
    exit 1
else
    echo "Solr module dependency found in core/pom.xml"
fi

# Step 3: Build all modules
echo "=== Step 3: Building all modules ==="
cd "$NEMAKI_HOME"

# Set Java 17
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "Java version:"
java -version

# Build Solr module first
echo "Building Solr module..."
mvn clean install -f solr/pom.xml -Pdevelopment

# Build core module with Solr integration
echo "Building core module with Solr integration..."
mvn clean package -f core/pom.xml -Pdevelopment

# Verify the WAR contains our custom modules
echo "=== Step 4: Verifying WAR contents ==="
cd "$NEMAKI_HOME/docker"
rm -rf verify-war
mkdir verify-war
cd verify-war
jar xf "$NEMAKI_HOME/core/target/core.war"

echo "Checking for critical JARs..."
if ls WEB-INF/lib/solr-2.4.1.jar >/dev/null 2>&1 && ls WEB-INF/lib/quartz-2.3.2.jar >/dev/null 2>&1; then
    echo "✓ Custom Solr module found: $(ls -la WEB-INF/lib/solr-2.4.1.jar)"
    echo "✓ Quartz scheduler found: $(ls -la WEB-INF/lib/quartz-2.3.2.jar)"
else
    echo "✗ ERROR: Custom modules missing from WAR!"
    exit 1
fi

cd "$NEMAKI_HOME/docker"
rm -rf verify-war

# Step 5: Copy WAR to Docker directory
echo "=== Step 5: Copying WAR to Docker directory ==="
cp "$NEMAKI_HOME/core/target/core.war" "$NEMAKI_HOME/docker/core.war"
echo "WAR copied: $(ls -la "$NEMAKI_HOME/docker/core.war")"

# Step 6: Create a custom Dockerfile that preserves our JARs
echo "=== Step 6: Creating enhanced Dockerfile ==="
cat > "$NEMAKI_HOME/docker/Dockerfile.jakarta-fixed" << 'EOF'
FROM tomcat:10.1-jdk17

# Install required packages
RUN apt-get update && apt-get install -y \
    curl \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Set up Tomcat
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy configuration files
COPY context.xml /usr/local/tomcat/conf/
COPY nemakiware.properties /usr/local/tomcat/conf/
COPY repositories.yml /usr/local/tomcat/conf/
COPY log4j.properties /usr/local/tomcat/conf/

# Copy the WAR file and Jakarta libraries for processing
COPY core.war /tmp/core.war
COPY jakarta-lib/* /tmp/jakarta-lib/

# Install Jakarta JARs into WAR file during build
# CRITICAL: Preserve custom Solr and Quartz JARs
RUN cd /tmp && \
    mkdir -p jakarta-war && \
    cd jakarta-war && \
    jar xf ../core.war && \
    echo "=== WAR Contents Before Jakarta Conversion ===" && \
    ls -la WEB-INF/lib/ | grep -E "solr-2|quartz-2" && \
    echo "Backing up critical JARs..." && \
    mkdir -p /tmp/critical-jars && \
    cp WEB-INF/lib/solr-2.4.1.jar /tmp/critical-jars/ || true && \
    cp WEB-INF/lib/quartz-2.3.2.jar /tmp/critical-jars/ || true && \
    echo "Removing original OpenCMIS JARs..." && \
    rm -f WEB-INF/lib/chemistry-opencmis-*.jar && \
    rm -f WEB-INF/lib/jaxws-rt-*.jar && \
    echo "Installing Jakarta converted JARs..." && \
    cp /tmp/jakarta-lib/chemistry-opencmis-*-1.1.0-jakarta.jar WEB-INF/lib/ && \
    cp /tmp/jakarta-lib/jaxws-rt-*-jakarta.jar WEB-INF/lib/ && \
    for f in WEB-INF/lib/*-jakarta.jar; do \
        mv "$f" "${f%-jakarta.jar}.jar"; \
    done && \
    echo "Restoring critical JARs..." && \
    cp /tmp/critical-jars/*.jar WEB-INF/lib/ || true && \
    echo "=== WAR Contents After Jakarta Conversion ===" && \
    ls -la WEB-INF/lib/ | grep -E "solr-2|quartz-2" && \
    echo "Building updated WAR file..." && \
    jar cf /usr/local/tomcat/webapps/core.war . && \
    echo "=== Verifying Final WAR ===" && \
    jar tf /usr/local/tomcat/webapps/core.war | grep -E "solr-2|quartz-2" || echo "WARNING: Critical JARs may be missing!" && \
    cd / && rm -rf /tmp/jakarta-war /tmp/core.war /tmp/jakarta-lib /tmp/critical-jars && \
    echo "Jakarta JAR integration completed"

# Copy utilities
COPY wait-for-couchdb.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/wait-for-couchdb.sh

# Environment variables for Jakarta EE
ENV CATALINA_OPTS="-Xms512m -Xmx1024m -XX:+DisableExplicitGC \
  -Djakarta.xml.bind.JAXBContext=com.sun.xml.bind.v2.ContextFactory \
  -Djakarta.xml.ws.spi.Provider=com.sun.xml.ws.spi.ProviderImpl"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/core || exit 1

EXPOSE 8080

# Wait for dependencies and start Tomcat
CMD ["/usr/local/bin/wait-for-couchdb.sh", "catalina.sh", "run"]
EOF

echo "Enhanced Dockerfile created"

# Step 7: Build Docker image
echo "=== Step 7: Building Docker image ==="
cd "$NEMAKI_HOME/docker"
docker build --no-cache -t nemakiware-tomcat10 -f Dockerfile.jakarta-fixed .

echo "=== Build complete! ==="
echo "To deploy, run: docker compose -f docker-compose-tomcat10.yml up -d"