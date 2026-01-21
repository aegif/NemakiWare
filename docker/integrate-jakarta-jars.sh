#!/bin/bash

# Script to integrate Jakarta JARs into Docker builds
# This script is designed to be used during Docker image building to ensure
# Jakarta-converted JARs are properly included and won't be lost during rebuilds

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEMAKI_HOME="${NEMAKI_HOME:-$(dirname "$SCRIPT_DIR")}"
JAKARTA_LIB_DIR="${JAKARTA_LIB_DIR:-$NEMAKI_HOME/lib/jakarta-converted}"
USE_SNAPSHOT="${USE_SNAPSHOT:-false}"

# Function to copy Jakarta JARs to a target directory
copy_jakarta_jars() {
    local target_dir="$1"
    
    if [ ! -d "$target_dir" ]; then
        echo "Creating target directory: $target_dir"
        mkdir -p "$target_dir"
    fi
    
    echo "Copying Jakarta JARs to $target_dir..."
    
    if [ "$USE_SNAPSHOT" = "true" ]; then
        echo "Using 1.2.0-SNAPSHOT versions..."
        cp "$JAKARTA_LIB_DIR"/chemistry-opencmis-*-1.2.0-SNAPSHOT-jakarta.jar "$target_dir/" 2>/dev/null || true
    else
        echo "Using 1.1.0 stable versions..."
        cp "$JAKARTA_LIB_DIR"/chemistry-opencmis-*-1.1.0-jakarta.jar "$target_dir/" 2>/dev/null || true
    fi
    
    # Always copy jaxws-rt
    cp "$JAKARTA_LIB_DIR"/jaxws-rt-*-jakarta.jar "$target_dir/" 2>/dev/null || true
    
    echo "Jakarta JARs copied successfully"
}

# Function to replace javax JARs with Jakarta versions in a WAR file
replace_jars_in_war() {
    local war_file="$1"
    local temp_dir="/tmp/jakarta-war-$$"
    
    if [ ! -f "$war_file" ]; then
        echo "WAR file not found: $war_file"
        return 1
    fi
    
    echo "Replacing JARs in WAR file: $war_file"
    
    # Extract WAR
    mkdir -p "$temp_dir"
    cd "$temp_dir"
    jar xf "$war_file"
    
    # Remove original OpenCMIS JARs and conflicting javax JARs
    rm -f WEB-INF/lib/chemistry-opencmis-*.jar
    rm -f WEB-INF/lib/jaxws-rt-*.jar
    
    # Remove conflicting javax JAX-WS JARs that interfere with Jakarta EE
    rm -f WEB-INF/lib/jaxws-api-*.jar
    rm -f WEB-INF/lib/javax.xml.soap-api-*.jar
    rm -f WEB-INF/lib/javax.activation-api-*.jar
    rm -f WEB-INF/lib/jaxws-eclipselink-plugin-*.jar
    rm -f WEB-INF/lib/jaxws-tools-*.jar
    
    # Also remove specific versions that might not match wildcards
    rm -f WEB-INF/lib/jaxws-api-2.3.1.jar
    rm -f WEB-INF/lib/jaxws-eclipselink-plugin-2.3.5.jar
    rm -f WEB-INF/lib/jaxws-tools-2.3.5.jar
    
    echo "Removed conflicting javax JAX-WS libraries"
    
    # Copy Jakarta versions with appropriate names
    if [ "$USE_SNAPSHOT" = "true" ]; then
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-bindings-1.2.0-SNAPSHOT-jakarta.jar" WEB-INF/lib/chemistry-opencmis-server-bindings-1.1.0.jar
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-impl-1.2.0-SNAPSHOT-jakarta.jar" WEB-INF/lib/chemistry-opencmis-commons-impl-1.1.0.jar
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-commons-api-1.2.0-SNAPSHOT-jakarta.jar" WEB-INF/lib/chemistry-opencmis-commons-api-1.1.0.jar
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-client-bindings-1.2.0-SNAPSHOT-jakarta.jar" WEB-INF/lib/chemistry-opencmis-client-bindings-1.1.0.jar
        cp "$JAKARTA_LIB_DIR/chemistry-opencmis-server-support-1.2.0-SNAPSHOT-jakarta.jar" WEB-INF/lib/chemistry-opencmis-server-support-1.1.0.jar
    else
        cp "$JAKARTA_LIB_DIR"/chemistry-opencmis-*-1.1.0-jakarta.jar WEB-INF/lib/
        # Rename to remove -jakarta suffix
        for f in WEB-INF/lib/*-jakarta.jar; do
            mv "$f" "${f%-jakarta.jar}.jar"
        done
    fi
    
    cp "$JAKARTA_LIB_DIR/jaxws-rt-4.0.2-jakarta.jar" WEB-INF/lib/jaxws-rt-4.0.2.jar
    
    # Repackage WAR
    jar cf "$war_file.new" .
    mv "$war_file.new" "$war_file"
    
    # Cleanup
    cd - > /dev/null
    rm -rf "$temp_dir"
    
    echo "WAR file updated successfully"
}

# Main execution
case "${1:-help}" in
    copy)
        # Copy Jakarta JARs to a specified directory
        if [ -z "$2" ]; then
            echo "Usage: $0 copy <target-directory>"
            exit 1
        fi
        copy_jakarta_jars "$2"
        ;;
        
    war)
        # Replace JARs in a WAR file
        if [ -z "$2" ]; then
            echo "Usage: $0 war <war-file>"
            exit 1
        fi
        replace_jars_in_war "$2"
        ;;
        
    docker-core)
        # Prepare Jakarta JARs for Docker core build
        echo "Preparing Jakarta JARs for Docker core build..."
        DOCKER_CORE_DIR="$SCRIPT_DIR/core"
        
        # If core.war exists, update it
        if [ -f "$DOCKER_CORE_DIR/core.war" ]; then
            replace_jars_in_war "$DOCKER_CORE_DIR/core.war"
        fi
        
        # Also copy JARs to a lib directory for Dockerfile to use
        copy_jakarta_jars "$DOCKER_CORE_DIR/jakarta-lib"
        ;;
        
    *)
        echo "Jakarta JAR Integration Script for Docker"
        echo ""
        echo "Usage: $0 <command> [options]"
        echo ""
        echo "Commands:"
        echo "  copy <dir>     Copy Jakarta JARs to specified directory"
        echo "  war <file>     Replace JARs inside a WAR file"
        echo "  docker-core    Prepare Jakarta JARs for Docker core build"
        echo ""
        echo "Environment variables:"
        echo "  USE_SNAPSHOT   Use 1.2.0-SNAPSHOT versions (default: false)"
        echo "  JAKARTA_LIB_DIR  Directory containing Jakarta JARs"
        echo ""
        echo "Examples:"
        echo "  $0 copy /tmp/jakarta-jars"
        echo "  $0 war core.war"
        echo "  USE_SNAPSHOT=true $0 docker-core"
        ;;
esac