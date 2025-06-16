#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
REPORTS_DIR="$SCRIPT_DIR/tck-reports"

echo "=========================================="
echo "NemakiWare TCK Report Generation"
echo "=========================================="

if [ ! -d "$REPORTS_DIR" ]; then
    echo "ERROR: TCK reports directory not found: $REPORTS_DIR"
    echo "Please run './execute-tck-tests.sh' first to generate TCK reports"
    exit 1
fi

if [ ! -f "$REPORTS_DIR/tck-report.txt" ]; then
    echo "ERROR: TCK text report not found: $REPORTS_DIR/tck-report.txt"
    echo "Please run './execute-tck-tests.sh' first to generate TCK reports"
    exit 1
fi

echo "Parsing TCK test results..."

if [ -f "$REPORTS_DIR/tck-execution.log" ]; then
    TEST_SUMMARY=$(grep "Tests run:" "$REPORTS_DIR/tck-execution.log" | tail -1)
    
    if [ -n "$TEST_SUMMARY" ]; then
        TOTAL_TESTS=$(echo "$TEST_SUMMARY" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p')
        FAILED_TESTS=$(echo "$TEST_SUMMARY" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p')
        ERROR_TESTS=$(echo "$TEST_SUMMARY" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p')
        SKIPPED_TESTS=$(echo "$TEST_SUMMARY" | sed -n 's/.*Skipped: \([0-9]*\).*/\1/p')
        
        PASSED_TESTS=$((TOTAL_TESTS - FAILED_TESTS - ERROR_TESTS - SKIPPED_TESTS))
    else
        TOTAL_TESTS=$(grep -c "Test:" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
        PASSED_TESTS=$(grep -c "Test:.*PASSED" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
        FAILED_TESTS=$(grep -c "Test:.*FAILED" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
        SKIPPED_TESTS=$(grep -c "Test:.*SKIPPED" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
    fi
else
    TOTAL_TESTS=$(grep -c "Test:" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
    PASSED_TESTS=$(grep -c "Test:.*PASSED" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
    FAILED_TESTS=$(grep -c "Test:.*FAILED" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
    SKIPPED_TESTS=$(grep -c "Test:.*SKIPPED" "$REPORTS_DIR/tck-report.txt" 2>/dev/null || echo "0")
fi

TOTAL_TESTS=${TOTAL_TESTS:-0}
PASSED_TESTS=${PASSED_TESTS:-0}
FAILED_TESTS=${FAILED_TESTS:-0}
SKIPPED_TESTS=${SKIPPED_TESTS:-0}

if [ "$TOTAL_TESTS" -gt 0 ]; then
    PASS_RATE=$(echo "scale=2; $PASSED_TESTS * 100 / $TOTAL_TESTS" | bc -l 2>/dev/null || echo "0")
else
    PASS_RATE="0"
fi

cat > "$REPORTS_DIR/tck-summary.html" << EOF
<!DOCTYPE html>
<html>
<head>
    <title>NemakiWare TCK Test Results</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        .header { background-color: #f0f0f0; padding: 20px; border-radius: 5px; }
        .stats { display: flex; gap: 20px; margin: 20px 0; }
        .stat-box { background-color: #e8f4f8; padding: 15px; border-radius: 5px; text-align: center; flex: 1; }
        .passed { background-color: #d4edda; }
        .failed { background-color: #f8d7da; }
        .skipped { background-color: #fff3cd; }
        .pass-rate { font-size: 24px; font-weight: bold; }
        .details { margin-top: 30px; }
        .test-group { margin: 20px 0; padding: 15px; border: 1px solid #ddd; border-radius: 5px; }
        .timestamp { color: #666; font-size: 12px; }
    </style>
</head>
<body>
    <div class="header">
        <h1>NemakiWare CMIS TCK Test Results</h1>
        <p class="timestamp">Generated on: $(date)</p>
    </div>
    
    <div class="stats">
        <div class="stat-box">
            <h3>Total Tests</h3>
            <div class="pass-rate">$TOTAL_TESTS</div>
        </div>
        <div class="stat-box passed">
            <h3>Passed</h3>
            <div class="pass-rate">$PASSED_TESTS</div>
        </div>
        <div class="stat-box failed">
            <h3>Failed</h3>
            <div class="pass-rate">$FAILED_TESTS</div>
        </div>
        <div class="stat-box skipped">
            <h3>Skipped</h3>
            <div class="pass-rate">$SKIPPED_TESTS</div>
        </div>
        <div class="stat-box">
            <h3>Pass Rate</h3>
            <div class="pass-rate">${PASS_RATE}%</div>
        </div>
    </div>
    
    <div class="details">
        <h2>Test Group Results</h2>
EOF

if [ -f "$REPORTS_DIR/tck-report.txt" ]; then
    echo "        <div class=\"test-group\">" >> "$REPORTS_DIR/tck-summary.html"
    echo "            <h3>Detailed Results</h3>" >> "$REPORTS_DIR/tck-summary.html"
    echo "            <p>For detailed test results, please refer to the following files:</p>" >> "$REPORTS_DIR/tck-summary.html"
    echo "            <ul>" >> "$REPORTS_DIR/tck-summary.html"
    echo "                <li><a href=\"tck-report.txt\">Text Report (tck-report.txt)</a></li>" >> "$REPORTS_DIR/tck-summary.html"
    if [ -f "$REPORTS_DIR/tck-report.xml" ]; then
        echo "                <li><a href=\"tck-report.xml\">XML Report (tck-report.xml)</a></li>" >> "$REPORTS_DIR/tck-summary.html"
    fi
    echo "            </ul>" >> "$REPORTS_DIR/tck-summary.html"
    echo "        </div>" >> "$REPORTS_DIR/tck-summary.html"
fi

cat >> "$REPORTS_DIR/tck-summary.html" << EOF
    </div>
    
    <div class="details">
        <h2>Quality Management Metrics</h2>
        <div class="test-group">
            <h3>Current Score: ${PASS_RATE}%</h3>
            <p>This score represents the current CMIS compliance level of NemakiWare.</p>
            <p><strong>Recommendations:</strong></p>
            <ul>
                <li>Focus on failed tests to improve compliance</li>
                <li>Run TCK tests regularly during development</li>
                <li>Track score improvements over time</li>
                <li>Address critical CMIS functionality gaps</li>
            </ul>
        </div>
    </div>
</body>
</html>
EOF

echo ""
echo "=========================================="
echo "TCK TEST RESULTS SUMMARY"
echo "=========================================="
echo "Total Tests:    $TOTAL_TESTS"
echo "Passed:         $PASSED_TESTS"
echo "Failed:         $FAILED_TESTS"
echo "Skipped:        $SKIPPED_TESTS"
echo "Pass Rate:      ${PASS_RATE}%"
echo "=========================================="
echo ""
echo "Reports generated:"
echo "  - Text Report:    $REPORTS_DIR/tck-report.txt"
if [ -f "$REPORTS_DIR/tck-report.xml" ]; then
    echo "  - XML Report:     $REPORTS_DIR/tck-report.xml"
fi
echo "  - HTML Summary:   $REPORTS_DIR/tck-summary.html"
echo ""
echo "Quality Management Score: ${PASS_RATE}%"
echo ""

echo "$PASS_RATE" > "$REPORTS_DIR/current-score.txt"
echo "$(date): ${PASS_RATE}%" >> "$REPORTS_DIR/score-history.txt"

echo "TCK report generation completed!"
