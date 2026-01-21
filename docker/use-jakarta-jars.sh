#!/bin/bash

# Script to replace javax JARs with Jakarta converted JARs for Jakarta EE deployment

# Allow overriding paths via environment variables
NEMAKI_HOME="${NEMAKI_HOME:-${PROJECT_ROOT:-$(pwd)}}"
CORE_LIB_DIR="${CORE_LIB_DIR:-$NEMAKI_HOME/core/target/core/WEB-INF/lib}"
JAKARTA_LIB_DIR="${JAKARTA_LIB_DIR:-$NEMAKI_HOME/lib/jakarta-converted}"

# Option to use SNAPSHOT versions (default: false)
USE_SNAPSHOT="${USE_SNAPSHOT:-false}"

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

if [ "$USE_SNAPSHOT" = "true" ]; then
    echo "Using 1.2.0-SNAPSHOT versions..."
    # Copy SNAPSHOT versions
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-bindings-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-server-bindings-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-impl-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-commons-impl-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-api-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-commons-api-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-client-bindings-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-client-bindings-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-support-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-server-support-1.1.0.jar"
    
    # Also copy client API and impl if they exist in the target
    if [ -f "$CORE_LIB_DIR/chemistry-opencmis-client-api-0.14.0.jar" ]; then
        rm -f "$CORE_LIB_DIR/chemistry-opencmis-client-api-0.14.0.jar"
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-client-api-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-client-api-0.14.0.jar"
    fi
    if [ -f "$CORE_LIB_DIR/chemistry-opencmis-client-impl-0.14.0.jar" ]; then
        rm -f "$CORE_LIB_DIR/chemistry-opencmis-client-impl-0.14.0.jar"
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-client-impl-1.2.0-SNAPSHOT-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-client-impl-0.14.0.jar"
    fi
else
    echo "Using 1.1.0 stable versions..."
    # Copy stable 1.1.0 versions
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-bindings-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-server-bindings-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-impl-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-commons-impl-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-api-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-commons-api-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-client-bindings-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-client-bindings-1.1.0.jar"
    cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-support-1.1.0-jakarta.jar" "$CORE_LIB_DIR/chemistry-opencmis-server-support-1.1.0.jar"
fi

# Always copy jaxws-rt
cp "$JAKARTA_LIB_DIR/jaxws-rt-4.0.2-jakarta.jar" "$CORE_LIB_DIR/jaxws-rt-4.0.2.jar"

echo "Jakarta JAR replacement completed successfully!"
echo "You can now deploy to Jakarta EE containers (Tomcat 10+, Jetty 11+)"

# List replaced JARs
echo -e "\nReplaced JARs:"
ls -la "$CORE_LIB_DIR"/chemistry-opencmis-*.jar "$CORE_LIB_DIR"/jaxws-rt-*.jar