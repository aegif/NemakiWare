#!/bin/bash

# NemakiWare Selective TCK Test Runner
# Executes specific CMIS TCK test groups

echo "=== NemakiWare Selective TCK Test Runner ==="
echo "Jakarta EE 10 Development Environment"
echo ""

# Function to show usage
show_usage() {
    echo "Usage: $0 [group1] [group2] ..."
    echo ""
    echo "Available test groups:"
    echo "  basics     - BasicsTestGroup (repository info, types, basic functionality)"
    echo "  control    - ControlTestGroup (ACL, permissions, inheritance)"  
    echo "  crud       - CrudTestGroup (create, read, update, delete operations)"
    echo "  filing     - FilingTestGroup (filing, unfiling, multifiling)"
    echo "  query      - QueryTestGroup (CMIS SQL queries)"
    echo "  types      - TypesTestGroup (type definitions, properties)"
    echo "  versioning - VersioningTestGroup (versioning, check-in/out)"
    echo ""
    echo "Examples:"
    echo "  $0 query                    # Run only query tests"
    echo "  $0 basics crud              # Run basics and CRUD tests"
    echo "  $0 versioning types filing  # Run versioning, types, and filing tests"
    echo ""
    echo "If no groups are specified, all groups will be executed."
}

# Check for help flag
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_usage
    exit 0
fi

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

# Show selected groups
if [ $# -eq 0 ]; then
    echo "No groups specified, running all available test groups"
else
    echo "Selected test groups: $*"
fi

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

# Run selective TCK tests with arguments
echo "Executing selective TCK tests..."
if [ $# -eq 0 ]; then
    # No arguments - run all groups
    mvn exec:java@run-jetty-tck -Pjakarta
else
    # Pass arguments to the test runner
    mvn exec:java@run-jetty-tck -Pjakarta -Dexec.args="$*"
fi

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Selective TCK execution completed successfully!"
    echo ""
    echo "Reports generated in: /tmp/tck-reports/"
    echo "Latest report directory: $(ls -t /tmp/tck-reports/ | head -1)"
    echo ""
    echo "Available reports:"
    if [ -d "/tmp/tck-reports" ]; then
        latest_dir="/tmp/tck-reports/$(ls -t /tmp/tck-reports/ | head -1)"
        if [ -d "$latest_dir" ]; then
            for report in "$latest_dir"/*-report.txt; do
                if [ -f "$report" ]; then
                    echo "  $(basename "$report"): $report"
                fi
            done
            if [ -f "$latest_dir/combined-report.txt" ]; then
                echo "  Combined Report: $latest_dir/combined-report.txt"
            fi
        fi
    fi
else
    echo ""
    echo "❌ Selective TCK execution failed"
    echo "Check the logs above for details"
    exit 1
fi