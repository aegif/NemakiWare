#!/bin/bash

# Simple script to compile and run CMIS Query Test
# This script downloads the required OpenCMIS libraries if not present

echo "=== CMIS Query Test Setup ==="

# Create lib directory if it doesn't exist
if [ ! -d "lib" ]; then
    echo "Creating lib directory..."
    mkdir lib
fi

# Check if OpenCMIS libraries are present
OPENCMIS_VERSION="1.1.0"
REQUIRED_JARS=(
    "chemistry-opencmis-client-impl-${OPENCMIS_VERSION}.jar"
    "chemistry-opencmis-client-api-${OPENCMIS_VERSION}.jar"
    "chemistry-opencmis-commons-api-${OPENCMIS_VERSION}.jar"
    "chemistry-opencmis-commons-impl-${OPENCMIS_VERSION}.jar"
)

SLF4J_VERSION="1.7.30"
SLF4J_JARS=(
    "slf4j-api-${SLF4J_VERSION}.jar"
    "slf4j-simple-${SLF4J_VERSION}.jar"
)

# Function to download a jar from Maven Central
download_jar() {
    local group_path=$1
    local artifact=$2
    local version=$3
    local jar_name="${artifact}-${version}.jar"
    
    if [ ! -f "lib/${jar_name}" ]; then
        echo "Downloading ${jar_name}..."
        curl -L -o "lib/${jar_name}" \
            "https://repo1.maven.org/maven2/${group_path}/${artifact}/${version}/${jar_name}"
    else
        echo "${jar_name} already exists"
    fi
}

# Download OpenCMIS libraries
echo "Checking OpenCMIS libraries..."
for jar in "${REQUIRED_JARS[@]}"; do
    artifact=$(echo $jar | sed "s/-${OPENCMIS_VERSION}.jar//")
    download_jar "org/apache/chemistry/opencmis" "$artifact" "$OPENCMIS_VERSION"
done

# Download SLF4J libraries
echo "Checking SLF4J libraries..."
for jar in "${SLF4J_JARS[@]}"; do
    artifact=$(echo $jar | sed "s/-${SLF4J_VERSION}.jar//")
    download_jar "org/slf4j" "$artifact" "$SLF4J_VERSION"
done

# Build classpath
CLASSPATH="."
for jar in lib/*.jar; do
    CLASSPATH="${CLASSPATH}:${jar}"
done

echo ""
echo "=== Compiling CMIS Query Test ==="
javac -cp "${CLASSPATH}" CmisQueryTest.java

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "=== Running CMIS Query Test ==="
    java -cp "${CLASSPATH}" CmisQueryTest
else
    echo "Compilation failed!"
    exit 1
fi