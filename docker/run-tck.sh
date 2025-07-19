#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)

echo "=========================================="
echo "NemakiWare TCK Automation"
echo "=========================================="

if ! command -v bc > /dev/null; then
    echo "Installing bc for calculations..."
    if command -v apt-get > /dev/null; then
        sudo apt-get update && sudo apt-get install -y bc
    elif command -v yum > /dev/null; then
        sudo yum install -y bc
    else
        echo "Warning: bc not available, some calculations may not work"
    fi
fi

RUN_TESTS=true
GENERATE_REPORT=true
SPECIFIC_GROUP=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --no-tests)
            RUN_TESTS=false
            shift
            ;;
        --no-report)
            GENERATE_REPORT=false
            shift
            ;;
        --group)
            SPECIFIC_GROUP="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --no-tests      Skip test execution, only generate reports from existing results"
            echo "  --no-report     Skip report generation, only run tests"
            echo "  --group GROUP   Run specific test group only (e.g., BasicsTestGroup)"
            echo "  --help          Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0                          # Run all tests and generate reports"
            echo "  $0 --group BasicsTestGroup  # Run only basics tests"
            echo "  $0 --no-tests               # Generate reports from existing results"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

if [ "$RUN_TESTS" = true ]; then
    echo "Executing TCK tests..."
    $SCRIPT_DIR/execute-tck-tests.sh
else
    echo "Skipping test execution (--no-tests specified)"
fi

if [ "$GENERATE_REPORT" = true ]; then
    echo "Generating TCK reports..."
    $SCRIPT_DIR/generate-tck-report.sh
else
    echo "Skipping report generation (--no-report specified)"
fi

echo ""
echo "TCK automation completed successfully!"
echo "Check docker/tck-reports/ for detailed results"
