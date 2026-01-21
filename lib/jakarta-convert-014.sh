#!/bin/bash

# Jakarta EE conversion script for OpenCMIS 0.14.0 libraries
# This script converts javax.* packages to jakarta.* packages

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
SOURCE_DIR="${SCRIPT_DIR}/014-source"
OUTPUT_DIR="${SCRIPT_DIR}/jakarta-converted"

echo "=== OpenCMIS 0.14.0 Jakarta EE Conversion ==="
echo "Source directory: ${SOURCE_DIR}"
echo "Output directory: ${OUTPUT_DIR}"

# Create output directory if it doesn't exist
mkdir -p "${OUTPUT_DIR}"

# List of JAR files to convert
JAR_FILES=(
    "chemistry-opencmis-client-api-0.14.0.jar"
    "chemistry-opencmis-client-impl-0.14.0.jar"
    "chemistry-opencmis-test-util-0.14.0.jar"
)

# Function to convert a single JAR file
convert_jar() {
    local jar_file="$1"
    local base_name=$(basename "${jar_file}" .jar)
    local jakarta_jar="${base_name}-jakarta.jar"
    
    echo "Converting ${jar_file} to ${jakarta_jar}..."
    
    # Create temporary working directory
    local temp_dir=$(mktemp -d)
    echo "  Working in: ${temp_dir}"
    
    # Extract the JAR
    echo "  Extracting JAR..."
    cd "${temp_dir}"
    jar -xf "${SOURCE_DIR}/${jar_file}"
    
    # Find and convert Java class files using javap and custom bytecode modification
    echo "  Converting javax to jakarta in class files..."
    
    # Find all .class files and convert them
    find . -name "*.class" -type f | while read -r class_file; do
        # Use sed to do binary replacement of javax strings to jakarta
        # This is a simplified approach - for production use, consider using proper bytecode tools
        if grep -q "javax/servlet\|javax/annotation\|javax/xml" "${class_file}" 2>/dev/null; then
            echo "    Converting: ${class_file}"
            # Binary string replacement for common javax packages
            sed -i.bak \
                -e 's/javax\/servlet/jakarta\/servlet/g' \
                -e 's/javax\/annotation/jakarta\/annotation/g' \
                -e 's/javax\/xml\/bind/jakarta\/xml\/bind/g' \
                -e 's/javax\/xml\/ws/jakarta\/xml\/ws/g' \
                -e 's/javax\/xml\/soap/jakarta\/xml\/soap/g' \
                -e 's/javax\/jws/jakarta\/jws/g' \
                -e 's/javax\/activation/jakarta\/activation/g' \
                "${class_file}"
            rm -f "${class_file}.bak"
        fi
    done
    
    # Convert text files (if any)
    echo "  Converting text files..."
    find . -name "*.xml" -o -name "*.properties" -o -name "*.txt" | while read -r text_file; do
        if grep -q "javax\.servlet\|javax\.annotation\|javax\.xml" "${text_file}" 2>/dev/null; then
            echo "    Converting: ${text_file}"
            sed -i.bak \
                -e 's/javax\.servlet/jakarta.servlet/g' \
                -e 's/javax\.annotation/jakarta.annotation/g' \
                -e 's/javax\.xml\.bind/jakarta.xml.bind/g' \
                -e 's/javax\.xml\.ws/jakarta.xml.ws/g' \
                -e 's/javax\.xml\.soap/jakarta.xml.soap/g' \
                -e 's/javax\.jws/jakarta.jws/g' \
                -e 's/javax\.activation/jakarta.activation/g' \
                "${text_file}"
            rm -f "${text_file}.bak"
        fi
    done
    
    # Recreate the JAR
    echo "  Creating Jakarta JAR..."
    jar -cf "${OUTPUT_DIR}/${jakarta_jar}" *
    
    # Clean up
    cd "${SCRIPT_DIR}"
    rm -rf "${temp_dir}"
    
    echo "  ✓ Created: ${jakarta_jar}"
}

# Convert each JAR file
for jar_file in "${JAR_FILES[@]}"; do
    if [[ -f "${SOURCE_DIR}/${jar_file}" ]]; then
        convert_jar "${jar_file}"
    else
        echo "Warning: ${jar_file} not found in ${SOURCE_DIR}"
    fi
done

echo ""
echo "=== Conversion Complete ==="
echo "Jakarta-converted libraries:"
ls -la "${OUTPUT_DIR}"/*0.14.0-jakarta.jar 2>/dev/null || echo "No converted files found"

echo ""
echo "=== Verification ==="
for jar_file in "${JAR_FILES[@]}"; do
    local base_name=$(basename "${jar_file}" .jar)
    local jakarta_jar="${OUTPUT_DIR}/${base_name}-jakarta.jar"
    
    if [[ -f "${jakarta_jar}" ]]; then
        echo "✓ ${jakarta_jar} exists ($(ls -lh "${jakarta_jar}" | awk '{print $5}'))"
        
        # Basic verification - check if JAR is valid
        if jar -tf "${jakarta_jar}" > /dev/null 2>&1; then
            echo "  ✓ JAR file structure is valid"
        else
            echo "  ✗ JAR file structure is invalid"
        fi
    else
        echo "✗ ${jakarta_jar} not created"
    fi
done

echo ""
echo "Jakarta conversion process completed!"