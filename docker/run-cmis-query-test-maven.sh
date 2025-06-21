#!/bin/bash

# Alternative script that uses Maven dependencies from the core module

echo "=== CMIS Query Test using Maven Dependencies ==="

# Navigate to the project root
cd /Users/ishiiakinori/NemakiWare

# Build classpath from Maven dependencies
echo "Building classpath from Maven dependencies..."
MAVEN_CLASSPATH=$(mvn -f core/pom.xml dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)

if [ -z "$MAVEN_CLASSPATH" ]; then
    echo "Failed to get Maven classpath. Trying alternative method..."
    # Use the target directory if available
    if [ -d "core/target/dependency" ]; then
        CLASSPATH="docker:docker/CmisQueryTest.class"
        for jar in core/target/dependency/*.jar; do
            CLASSPATH="${CLASSPATH}:${jar}"
        done
    else
        echo "ERROR: Could not build classpath. Please run 'mvn dependency:copy-dependencies' in the core directory first."
        exit 1
    fi
else
    CLASSPATH="docker:docker/CmisQueryTest.class:${MAVEN_CLASSPATH}"
fi

# Compile the test
echo "Compiling CmisQueryTest.java..."
cd docker
javac -cp "${CLASSPATH}" CmisQueryTest.java

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "=== Running CMIS Query Test ==="
    java -cp "${CLASSPATH}" CmisQueryTest
else
    echo "Compilation failed!"
    echo "You may need to run: mvn -f ../core/pom.xml dependency:copy-dependencies"
    exit 1
fi