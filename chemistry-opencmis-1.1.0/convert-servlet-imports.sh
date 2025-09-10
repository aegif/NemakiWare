#!/bin/bash

# OpenCMIS 1.1.0-nemakiware Jakarta Servlet Conversion Script
# Systematically converts all javax.servlet imports to jakarta.servlet

set -e

echo "Starting comprehensive javax.servlet -> jakarta.servlet conversion for OpenCMIS 1.1.0-nemakiware"

TOTAL_FILES=0
CONVERTED_FILES=0

# Convert server modules (highest priority)
echo ""
echo "📁 Converting chemistry-opencmis-server modules..."

# Server Bindings (critical - contains CallContextImpl)
echo "  🎯 chemistry-opencmis-server-bindings (CRITICAL)"
find chemistry-opencmis-server/chemistry-opencmis-server-bindings -name "*.java" | while read file; do
    convert_servlet_imports "$file"
done

# Server Support  
echo "  📦 chemistry-opencmis-server-support"
find chemistry-opencmis-server/chemistry-opencmis-server-support -name "*.java" | while read file; do
    convert_servlet_imports "$file"
done

# Server Async
echo "  ⚡ chemistry-opencmis-server-async"
find chemistry-opencmis-server/chemistry-opencmis-server-async -name "*.java" | while read file; do
    convert_servlet_imports "$file"
done

# Bridge modules
echo ""
echo "📁 Converting chemistry-opencmis-bridge modules..."
find chemistry-opencmis-bridge -name "*.java" | while read file; do
    convert_servlet_imports "$file"
done

# Test modules  
echo ""
echo "📁 Converting chemistry-opencmis-test modules..."
find chemistry-opencmis-test -name "*.java" | while read file; do
    convert_servlet_imports "$file"
done

# Android client (lower priority but comprehensive)
echo ""
echo "📁 Converting chemistry-opencmis-android modules..."
find chemistry-opencmis-android -name "*.java" | while read file; do
    convert_servlet_imports "$file"
done

echo ""
echo "✅ Jakarta Servlet Conversion Complete!"
echo "📊 Summary:"
echo "   - Total Java files processed: $TOTAL_FILES"
echo "   - Files with servlet imports converted: $CONVERTED_FILES"

# Verification step
echo ""
echo "🔍 Verification: Checking for remaining javax.servlet imports..."
REMAINING=$(find . -name "*.java" -exec grep -l "import javax\.servlet" {} \; | wc -l)
if [ "$REMAINING" -eq 0 ]; then
    echo "✅ SUCCESS: All javax.servlet imports successfully converted to jakarta.servlet"
else
    echo "⚠️  WARNING: $REMAINING files still contain javax.servlet imports"
    echo "Remaining files:"
    find . -name "*.java" -exec grep -l "import javax\.servlet" {} \;
fi