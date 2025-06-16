#!/bin/bash

SCRIPT_DIR=$(cd $(dirname $0); pwd)
REPORT_FILE="$SCRIPT_DIR/tck-reports/tck-report.txt"

if [ ! -f "$REPORT_FILE" ]; then
    echo "ERROR: TCK report not found at $REPORT_FILE"
    echo "Please run './execute-tck-tests.sh' first to generate the report."
    exit 1
fi

echo "=========================================="
echo "NemakiWare CMIS TCK Test Report Summary"
echo "=========================================="

# Extract report date
echo -n "Report Date: "
grep "Test Report:" "$REPORT_FILE" | cut -d: -f2-

echo ""

# Extract configuration
echo "Configuration:"
grep -E "^org\.apache\.chemistry\.opencmis\." "$REPORT_FILE" | grep -v "revision\|timestamp" | while read line; do
    echo "  $line"
done

echo ""

# Count results
PASSED=$(grep -E "^  OK:" "$REPORT_FILE" | wc -l | tr -d ' ')
FAILED=$(grep -E "^  FAILURE:" "$REPORT_FILE" | wc -l | tr -d ' ')
WARNINGS=$(grep -E "^  WARNING:" "$REPORT_FILE" | wc -l | tr -d ' ')
TOTAL=$((PASSED + FAILED + WARNINGS))

echo "Test Results:"
echo "  Total Tests: $TOTAL"
echo "  Passed:      $PASSED ($((PASSED * 100 / TOTAL))%)"
echo "  Failed:      $FAILED ($((FAILED * 100 / TOTAL))%)"
echo "  Warnings:    $WARNINGS ($((WARNINGS * 100 / TOTAL))%)"

echo ""

# Show test groups
echo "Test Groups:"
grep -E "^===============================================================" -A 1 "$REPORT_FILE" | grep -v "^===============================================================" | grep -v "^--$" | while read group; do
    if [ -n "$group" ]; then
        echo "  - $group"
    fi
done

echo ""

# Show major failure categories
echo "Top Failure Categories:"
grep -E "^  FAILURE:" "$REPORT_FILE" | sed 's/^  FAILURE: //' | cut -d'(' -f1 | sort | uniq -c | sort -nr | head -5 | while read count message; do
    echo "  $count× $message"
done

echo ""

echo "Detailed reports available in:"
echo "  Text: $SCRIPT_DIR/tck-reports/tck-report.txt"
echo "  XML:  $SCRIPT_DIR/tck-reports/tck-report.xml"
echo ""

# Show a few sample failures for context
echo "Sample Failed Tests:"
grep -B 2 -A 1 "^  FAILURE:" "$REPORT_FILE" | head -15 | while read line; do
    if [[ "$line" =~ ^-- ]]; then
        continue
    elif [[ "$line" =~ ^----------- ]]; then
        echo ""
    else
        echo "$line"
    fi
done

echo ""
echo "=========================================="