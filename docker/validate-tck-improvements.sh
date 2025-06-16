#!/bin/bash

echo "=== Validating TCK Test Improvements ==="

REPORTS_DIR="$PWD/tck-reports"

if [ ! -d "$REPORTS_DIR" ]; then
    echo "ERROR: TCK reports directory not found"
    exit 1
fi

echo "1. Analyzing current TCK results:"
if [ -f "$REPORTS_DIR/tck-execution.log" ]; then
    TOTAL_TESTS=$(grep -o "Tests run: [0-9]*" "$REPORTS_DIR/tck-execution.log" | tail -1 | grep -o "[0-9]*")
    FAILURES=$(grep -o "Failures: [0-9]*" "$REPORTS_DIR/tck-execution.log" | tail -1 | grep -o "[0-9]*")
    ERRORS=$(grep -o "Errors: [0-9]*" "$REPORTS_DIR/tck-execution.log" | tail -1 | grep -o "[0-9]*")
    
    if [ -n "$TOTAL_TESTS" ] && [ -n "$FAILURES" ] && [ -n "$ERRORS" ]; then
        PASSED=$((TOTAL_TESTS - FAILURES - ERRORS))
        PASS_RATE=$(python3 -c "print(f'{($PASSED/$TOTAL_TESTS)*100:.2f}')")
        
        echo "Total Tests: $TOTAL_TESTS"
        echo "Passed: $PASSED"
        echo "Failed: $FAILURES"
        echo "Errors: $ERRORS"
        echo "Pass Rate: ${PASS_RATE}%"
        
        if (( $(echo "$PASS_RATE > 75.0" | bc -l) )); then
            echo "✓ TARGET ACHIEVED: Pass rate ${PASS_RATE}% exceeds 75% target"
        else
            echo "⚠ Target not met: Pass rate ${PASS_RATE}% below 75% target"
        fi
    else
        echo "Could not parse test results from execution log"
    fi
else
    echo "TCK execution log not found"
fi

echo -e "\n2. Checking for query-specific improvements:"
if [ -f "$REPORTS_DIR/tck-execution.log" ]; then
    QUERY_TESTS=$(grep -i "query" "$REPORTS_DIR/tck-execution.log" | wc -l)
    echo "Query-related test entries found: $QUERY_TESTS"
    
    if grep -q "QueryRootFolderTest" "$REPORTS_DIR/tck-execution.log"; then
        echo "✓ Root folder query tests were executed"
    else
        echo "⚠ Root folder query tests not found in logs"
    fi
fi

echo -e "\n3. Generating final summary report:"
cat > "$REPORTS_DIR/improvement-summary.txt" << EOF
=== NemakiWare TCK Test Improvement Summary ===
Date: $(date)
Target: >75% pass rate
Previous: 70.12% (108/154 tests)

Current Results:
- Total Tests: ${TOTAL_TESTS:-"N/A"}
- Passed: ${PASSED:-"N/A"}
- Pass Rate: ${PASS_RATE:-"N/A"}%

Key Improvements Applied:
1. Enhanced CMIS query debug logging
2. Fixed Solr connectivity issues
3. Resolved repository ID mismatches
4. Improved property mapping error handling
5. Added comprehensive query translation logging

Status: $(if (( $(echo "${PASS_RATE:-0} > 75.0" | bc -l) )); then echo "TARGET ACHIEVED ✓"; else echo "NEEDS FURTHER WORK ⚠"; fi)
EOF

echo "Summary report generated: $REPORTS_DIR/improvement-summary.txt"
