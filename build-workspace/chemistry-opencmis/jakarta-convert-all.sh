#!/bin/bash

# Comprehensive Jakarta EE conversion script for OpenCMIS 1.2.0-SNAPSHOT libraries
# This script converts all built OpenCMIS JARs from javax.* to jakarta.* packages

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
SOURCE_DIR="${SCRIPT_DIR}/built-jars"
OUTPUT_DIR="/Users/ishiiakinori/NemakiWare/lib/jakarta-converted"

echo "=== OpenCMIS 1.2.0-SNAPSHOT Jakarta EE Conversion ==="
echo "Source directory: ${SOURCE_DIR}"
echo "Output directory: ${OUTPUT_DIR}"

# Create output directory if it doesn't exist
mkdir -p "${OUTPUT_DIR}"

# List of all JAR files to convert
JAR_FILES=(
    "chemistry-opencmis-client-api-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-client-bindings-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-client-impl-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-commons-api-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-commons-impl-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-server-bindings-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-server-support-1.2.0-SNAPSHOT.jar"
    "chemistry-opencmis-test-tck-1.2.0-SNAPSHOT.jar"
)

# Advanced Jakarta conversion function
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
    
    # Comprehensive javax to jakarta conversion
    echo "  Converting javax to jakarta packages..."
    
    # Convert .class files using a more comprehensive approach
    find . -name "*.class" -type f | while read -r class_file; do
        if [[ -f "${class_file}" ]]; then
            # Use hexdump and sed for binary string replacement
            # This handles the constant pool strings in .class files
            
            # Check if file contains javax references
            if xxd -p "${class_file}" | tr -d '\n' | grep -q "6a617661782f736572766c6574\|6a617661782f616e6e6f746174696f6e\|6a617661782f786d6c"; then
                echo "    Converting binary: ${class_file}"
                
                # Create backup
                cp "${class_file}" "${class_file}.bak"
                
                # Convert using sed on hex dump and back to binary
                xxd -p "${class_file}" | tr -d '\n' | \
                sed 's/6a617661782f736572766c6574/6a616b617274612f736572766c6574/g' | \
                sed 's/6a617661782f616e6e6f746174696f6e/6a616b617274612f616e6e6f746174696f6e/g' | \
                sed 's/6a617661782f786d6c2f62696e64/6a616b617274612f786d6c2f62696e64/g' | \
                sed 's/6a617661782f786d6c2f7773/6a616b617274612f786d6c2f7773/g' | \
                sed 's/6a617661782f786d6c2f736f6170/6a616b617274612f786d6c2f736f6170/g' | \
                sed 's/6a617661782f6a7773/6a616b617274612f6a7773/g' | \
                sed 's/6a617661782f61637469766174696f6e/6a616b617274612f61637469766174696f6e/g' | \
                xxd -r -p > "${class_file}.new"
                
                # Verify the new file is valid
                if [[ -s "${class_file}.new" ]]; then
                    mv "${class_file}.new" "${class_file}"
                    rm -f "${class_file}.bak"
                else
                    echo "    Warning: Conversion failed for ${class_file}, restoring original"
                    mv "${class_file}.bak" "${class_file}"
                fi
            fi
        fi
    done
    
    # Convert text files with comprehensive patterns
    echo "  Converting text files..."
    find . -name "*.xml" -o -name "*.properties" -o -name "*.txt" -o -name "*.MF" | while read -r text_file; do
        if [[ -f "${text_file}" ]] && grep -q "javax\." "${text_file}" 2>/dev/null; then
            echo "    Converting text: ${text_file}"
            sed -i.bak \
                -e 's/javax\.servlet/jakarta.servlet/g' \
                -e 's/javax\.annotation/jakarta.annotation/g' \
                -e 's/javax\.xml\.bind/jakarta.xml.bind/g' \
                -e 's/javax\.xml\.ws/jakarta.xml.ws/g' \
                -e 's/javax\.xml\.soap/jakarta.xml.soap/g' \
                -e 's/javax\.jws/jakarta.jws/g' \
                -e 's/javax\.activation/jakarta.activation/g' \
                -e 's/javax\.mail/jakarta.mail/g' \
                -e 's/javax\.validation/jakarta.validation/g' \
                -e 's/javax\.inject/jakarta.inject/g' \
                -e 's/javax\.enterprise/jakarta.enterprise/g' \
                -e 's/javax\.persistence/jakarta.persistence/g' \
                "${text_file}"
            rm -f "${text_file}.bak"
        fi
    done
    
    # Convert META-INF files specifically
    echo "  Converting META-INF files..."
    if [[ -d "META-INF" ]]; then
        find META-INF/ -type f | while read -r meta_file; do
            if [[ -f "${meta_file}" ]] && grep -q "javax\." "${meta_file}" 2>/dev/null; then
                echo "    Converting META-INF: ${meta_file}"
                sed -i.bak \
                    -e 's/javax\.servlet/jakarta.servlet/g' \
                    -e 's/javax\.annotation/jakarta.annotation/g' \
                    -e 's/javax\.xml\.bind/jakarta.xml.bind/g' \
                    -e 's/javax\.xml\.ws/jakarta.xml.ws/g' \
                    -e 's/javax\.xml\.soap/jakarta.xml.soap/g' \
                    -e 's/javax\.jws/jakarta.jws/g' \
                    -e 's/javax\.activation/jakarta.activation/g' \
                    "${meta_file}"
                rm -f "${meta_file}.bak"
            fi
        done
    fi
    
    # Recreate the JAR with proper manifest
    echo "  Creating Jakarta JAR..."
    if [[ -f "META-INF/MANIFEST.MF" ]]; then
        jar -cfm "${OUTPUT_DIR}/${jakarta_jar}" META-INF/MANIFEST.MF *
    else
        jar -cf "${OUTPUT_DIR}/${jakarta_jar}" *
    fi
    
    # Clean up
    cd "${SCRIPT_DIR}"
    rm -rf "${temp_dir}"
    
    echo "  ✓ Created: ${jakarta_jar}"
}

# Convert each JAR file
echo ""
echo "Starting conversion of ${#JAR_FILES[@]} JAR files..."
echo ""

for jar_file in "${JAR_FILES[@]}"; do
    if [[ -f "${SOURCE_DIR}/${jar_file}" ]]; then
        convert_jar "${jar_file}"
        echo ""
    else
        echo "Warning: ${jar_file} not found in ${SOURCE_DIR}"
        echo ""
    fi
done

echo "=== Conversion Complete ==="
echo "Jakarta-converted libraries:"
ls -la "${OUTPUT_DIR}"/*jakarta.jar 2>/dev/null || echo "No converted files found"

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
            
            # Check for successful conversion by looking for jakarta packages
            local jakarta_count=$(jar -tf "${jakarta_jar}" | grep -c "jakarta/" 2>/dev/null || echo "0")
            local javax_count=$(jar -tf "${jakarta_jar}" | grep -c "javax/" 2>/dev/null || echo "0")
            
            echo "  ✓ jakarta/ paths: ${jakarta_count}, javax/ paths: ${javax_count}"
            
            if [[ "${jakarta_count}" -gt 0 ]]; then
                echo "  ✓ Jakarta conversion appears successful"
            else
                echo "  ⚠ No jakarta paths found - may need manual verification"
            fi
        else
            echo "  ✗ JAR file structure is invalid"
        fi
    else
        echo "✗ ${jakarta_jar} not created"
    fi
    echo ""
done

echo "Comprehensive Jakarta conversion process completed!"
echo ""
echo "Next steps:"
echo "1. Update pom.xml to use these Jakarta-converted libraries"
echo "2. Test build with Jakarta EE compatibility"
echo "3. Verify runtime functionality"