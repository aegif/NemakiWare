#!/bin/bash
set -e

echo "=== Running Comprehensive TCK Test with Query Fixes ==="

SCRIPT_DIR=$(cd $(dirname $0); pwd)
cd $SCRIPT_DIR

echo "1. Making all scripts executable..."
chmod +x *.sh

echo "2. Running comprehensive query test and fixes..."
./comprehensive-query-test.sh

echo "3. Executing full TCK test suite..."
./execute-tck-tests.sh

echo "4. Generating final improvement report..."
if [ -f "./validate-tck-improvements.sh" ]; then
    ./validate-tck-improvements.sh
fi

echo "=== Comprehensive TCK Test Complete ==="
