#!/bin/bash

# Optimized Build Script for NemakiWare
# Addresses common build inefficiencies and test skipping issues

set -e

# Change to the project root directory
cd "$(dirname "$0")"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== NEMAKIWARE OPTIMIZED BUILD SCRIPT ===${NC}"
echo

# Java environment verification
echo -e "${BLUE}1. ENVIRONMENT VERIFICATION${NC}"
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

java_version=$(java -version 2>&1 | grep 'version "17')
if [[ -n "$java_version" ]]; then
    echo -e "${GREEN}✅ Java 17 Environment: $java_version${NC}"
else
    echo -e "${RED}❌ Java 17 Required${NC}"
    exit 1
fi

# Maven configuration
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"

echo

# Build mode selection
BUILD_MODE=${1:-"development"}
echo -e "${BLUE}2. BUILD MODE: $BUILD_MODE${NC}"

case $BUILD_MODE in
    "clean")
        echo -e "${YELLOW}🧹 CLEAN BUILD MODE${NC}"
        BUILD_ARGS="clean package -Pdevelopment -U"
        ;;
    "fast")
        echo -e "${YELLOW}⚡ FAST BUILD MODE (Skip Tests)${NC}"
        BUILD_ARGS="package -Pdevelopment -DskipTests=true -Dmaven.test.skip=true"
        ;;
    "test")
        echo -e "${YELLOW}🧪 TEST BUILD MODE${NC}"
        BUILD_ARGS="package -Pproduct"
        ;;
    "development"|*)
        echo -e "${YELLOW}🔨 DEVELOPMENT BUILD MODE${NC}"
        BUILD_ARGS="package -Pdevelopment"
        ;;
esac

echo

# Module build optimization
echo -e "${BLUE}3. MODULE BUILD OPTIMIZATION${NC}"
echo -e "${GREEN}Active Modules:${NC}"
echo "  - common (shared utilities)"
echo "  - core (CMIS server + React UI)"  
echo "  - solr (search engine)"
echo "  - cloudant-init (database tool)"

echo -e "${YELLOW}Suspended Modules (skipped):${NC}"
echo "  - suspended-modules/action"
echo "  - suspended-modules/action-sample"
echo "  - suspended-modules/aws"

echo

# Build execution with timing
echo -e "${BLUE}4. BUILD EXECUTION${NC}"
start_time=$(date +%s)

echo -e "${YELLOW}Building all active modules...${NC}"
if mvn $BUILD_ARGS -q; then
    end_time=$(date +%s)
    duration=$((end_time - start_time))
    echo -e "${GREEN}✅ Build completed in ${duration}s${NC}"
else
    echo -e "${RED}❌ Build failed${NC}"
    exit 1
fi

echo

# Docker preparation (optional)
if [[ "$2" == "docker" ]]; then
    echo -e "${BLUE}5. DOCKER PREPARATION${NC}"
    
    if [[ -f "core/target/core.war" ]]; then
        cp core/target/core.war docker/core/core.war
        echo -e "${GREEN}✅ WAR file copied to Docker context${NC}"
        
        # Verify WAR file
        war_size=$(ls -lh docker/core/core.war | awk '{print $5}')
        echo -e "${GREEN}WAR file size: $war_size${NC}"
    else
        echo -e "${RED}❌ core.war not found${NC}"
        exit 1
    fi
fi

echo

# Build summary
echo -e "${BLUE}=== BUILD SUMMARY ===${NC}"
echo -e "${GREEN}✅ Java 17 Environment: Verified${NC}"
echo -e "${GREEN}✅ Maven Build: Success${NC}"
echo -e "${GREEN}✅ Active Modules: Built${NC}"
echo -e "${GREEN}✅ Suspended Modules: Skipped${NC}"

if [[ "$2" == "docker" ]]; then
    echo -e "${GREEN}✅ Docker Preparation: Complete${NC}"
fi

echo
echo -e "${BLUE}Usage Examples:${NC}"
echo "  ./build-optimize.sh clean docker    # Clean build + Docker prep"
echo "  ./build-optimize.sh fast            # Fast build (skip tests)"
echo "  ./build-optimize.sh test            # Build with tests"
echo "  ./build-optimize.sh development     # Default development build"

echo
echo -e "${GREEN}🎉 BUILD OPTIMIZATION COMPLETE${NC}"