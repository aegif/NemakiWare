#!/bin/bash

# Script to replace javax JARs with Jakarta converted JARs for Jakarta EE deployment

CORE_LIB_DIR="/Users/ishiiakinori/NemakiWare/core/target/core/WEB-INF/lib"
JAKARTA_LIB_DIR="/Users/ishiiakinori/NemakiWare/lib/jakarta-converted"

echo "Replacing javax JARs with Jakarta converted JARs..."

# Check if core has been built
if [ ! -d "$CORE_LIB_DIR" ]; then
    echo "Error: Core WAR not found. Please build core first: mvn package -f core/pom.xml"
    exit 1
fi

# Check if Jakarta JARs exist
if [ ! -d "$JAKARTA_LIB_DIR" ]; then
    echo "Error: Jakarta converted JARs not found in $JAKARTA_LIB_DIR"
    exit 1
fi

# Remove original OpenCMIS JARs
echo "Removing original OpenCMIS JARs..."
rm -f "$CORE_LIB_DIR"/chemistry-opencmis-*.jar
rm -f "$CORE_LIB_DIR"/jaxws-rt-*.jar

# Copy Jakarta converted JARs with original names
echo "Copying Jakarta converted JARs..."
cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-bindings-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-server-bindings-1.1.0.jar"
cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-impl-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-commons-impl-1.1.0.jar"
cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-api-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-commons-api-1.1.0.jar"
cp "$JAKARTA_LIB_DIR/chemistry-opencmis-client-bindings-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-client-bindings-1.1.0.jar"
cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-support-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-server-support-1.1.0.jar"
cp "$JAKARTA_LIB_DIR/jaxws-rt-4.0.2-jakarta.jar" "$CORE_LIB_DIR/jaxws-rt-4.0.2.jar"

echo "Jakarta JAR replacement completed successfully!"
echo "You can now deploy to Jakarta EE containers (Tomcat 10+, Jetty 11+)"

# List replaced JARs
echo -e "\nReplaced JARs:"
ls -la "$CORE_LIB_DIR"/chemistry-opencmis-*.jar "$CORE_LIB_DIR"/jaxws-rt-*.jar