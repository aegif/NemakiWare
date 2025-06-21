#!/bin/bash

echo "=== NemakiWare TCK Files Cleanup ==="
echo "This will remove redundant and obsolete TCK test files"
echo ""

# Show what will be removed
echo "Files to be removed:"
echo ""

echo "1. Redundant root-level TCK scripts (11 files):"
ls -la run-*tck*.sh generate-*tck*.sh test-crud-fix.sh 2>/dev/null || echo "   (No redundant scripts found)"

echo ""
echo "2. Log files:"
ls -la *.log docker/*.log 2>/dev/null || echo "   (No log files found)"

echo ""
echo "3. Generated report directories:"
ls -la tck-reports/ 2>/dev/null || echo "   (No report directory found)"

echo ""
echo "4. Generated Java files:"
ls -la *TckRunner.java *QueryTest.java 2>/dev/null || echo "   (No generated Java files found)"

echo ""
echo "5. Duplicate configuration files:"
ls -la docker/cmis-tck-*docker.properties 2>/dev/null || echo "   (No duplicate configs found)"

echo ""
echo "Files to be KEPT (essential):"
echo "✓ /docker/test-all.sh - Main test orchestration"
echo "✓ /docker/test-simple.sh - Simplified testing"
echo "✓ /docker/execute-tck-tests.sh - TCK execution"
echo "✓ /docker/run-tck.sh - TCK automation"
echo "✓ /core/src/test/java/jp/aegif/nemaki/cmis/tck/ - Core test classes"
echo "✓ /core/src/test/resources/cmis-tck-*.properties - Configuration files"

echo ""
read -p "Do you want to proceed with cleanup? (y/N): " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    echo "=== Starting Cleanup ==="
    
    # Remove redundant root-level scripts
    echo "Removing redundant TCK scripts..."
    rm -f run-basic-tck.sh run-extended-tck.sh run-query-tck.sh run-standard-tck.sh
    rm -f run-tck-optimized.sh run-tck-sequential.sh run-tck-simple.sh
    rm -f run-tck-with-reports.sh run-full-tck.sh run-tck-limited.sh
    rm -f generate-manual-tck-report.sh generate-verification-tck-report.sh
    rm -f test-crud-fix.sh
    
    # Remove generated Java files
    echo "Removing generated Java files..."
    rm -f *TckRunner.java *QueryTest.java SimpleQueryTest.java
    rm -f *.class
    
    # Remove log files
    echo "Removing log files..."
    rm -f *.log
    rm -f docker/*.log
    
    # Remove generated reports (but preserve directory structure for future use)
    echo "Cleaning up report files..."
    rm -rf tck-reports/*.txt tck-reports/*.xml tck-reports/*.html 2>/dev/null
    
    # Remove duplicate configuration files
    echo "Removing duplicate configuration files..."
    rm -f docker/cmis-tck-parameters-docker.properties
    rm -f docker/cmis-tck-filters-docker.properties
    
    # Clean up any temporary files
    echo "Removing temporary files..."
    rm -f *.tmp *.bak
    
    echo ""
    echo "=== Cleanup Complete ==="
    echo ""
    echo "Removed:"
    echo "✓ 11+ redundant TCK scripts"
    echo "✓ Generated Java files"
    echo "✓ Log files"
    echo "✓ Generated reports"
    echo "✓ Duplicate configuration files"
    echo ""
    echo "Essential TCK infrastructure preserved in /docker/ and /core/src/test/"
    echo ""
    echo "To run TCK tests in the future, use:"
    echo "  ./docker/test-simple.sh    # For basic testing"
    echo "  ./docker/test-all.sh       # For comprehensive testing"
    
else
    echo ""
    echo "Cleanup cancelled. No files were removed."
fi

echo ""
echo "=== Summary ==="
echo "Current TCK infrastructure:"
ls -la docker/test*.sh docker/run-tck.sh docker/execute-tck*.sh 2>/dev/null
echo ""
echo "Core test classes:"
find core/src/test -name "*tck*" -o -name "*TCK*" 2>/dev/null | head -5
echo "..."