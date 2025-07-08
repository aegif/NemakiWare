#!/bin/bash

# NemakiWare Comprehensive TCK Test Runner
# Executes all CMIS TCK test groups with detailed reporting

echo "=== NemakiWare Comprehensive TCK Test Runner ==="
echo "Jakarta EE 10 Development Environment"
echo ""

# Check if Jetty server is running
echo "Checking Jetty server status..."
if curl -s -u admin:admin -o /dev/null -w "%{http_code}" http://localhost:8081/core/atom/bedroom | grep -q "200"; then
    echo "✓ Jetty server is running and accessible"
else
    echo "⚠ Jetty server is not accessible. Please ensure it's running:"
    echo "  cd /Users/ishiiakinori/NemakiWare/core"
    echo "  ./start-jetty-dev.sh"
    echo ""
    exit 1
fi

echo ""
echo "Starting comprehensive TCK execution..."
echo "This will run all available test groups:"
echo "  - BasicsTestGroup (repository info, types, basic functionality)"
echo "  - ControlTestGroup (ACL, permissions, inheritance)"
echo "  - CrudTestGroup (create, read, update, delete operations)"
echo "  - FilingTestGroup (filing, unfiling, multifiling)"
echo "  - QueryTestGroup (CMIS SQL queries)"
echo "  - TypesTestGroup (type definitions, properties)"
echo "  - VersioningTestGroup (versioning, check-in/out)"
echo ""

# Set MAVEN_OPTS for Java 17 module system compatibility
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

echo "MAVEN_OPTS configured for Java 17 module system"
echo ""

# Compile test classes
echo "Compiling test classes..."
mvn test-compile -Pjakarta -q

if [ $? -ne 0 ]; then
    echo "✗ Test compilation failed"
    exit 1
fi

echo "✓ Test classes compiled successfully"
echo ""

# Run comprehensive TCK tests
echo "Executing comprehensive TCK tests..."
mvn exec:java@run-jetty-tck -Pjakarta

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Comprehensive TCK execution completed successfully!"
    echo ""
    echo "Reports generated in: /tmp/tck-reports/"
    echo "Latest report directory: $(ls -t /tmp/tck-reports/ | head -1)"
    echo ""
    echo "Available reports:"
    if [ -d "/tmp/tck-reports" ]; then
        latest_dir="/tmp/tck-reports/$(ls -t /tmp/tck-reports/ | head -1)"
        if [ -d "$latest_dir" ]; then
            echo "  Text Report: $latest_dir/tck-report.txt"
            echo "  XML Report: $latest_dir/tck-report.xml"
            echo "  HTML Report: $latest_dir/tck-report.html"
            echo "  Summary Report: $latest_dir/tck-summary.txt"
        fi
    fi
else
    echo ""
    echo "❌ Comprehensive TCK execution failed"
    echo "Check the logs above for details"
    exit 1
fi