#!/bin/bash

# TCK Test Analysis and Execution Script
# This script runs selective TCK tests and analyzes results to determine success rate

echo "=========================================="
echo "     NemakiWare TCK Analysis Tool"  
echo "=========================================="
echo "Timestamp: $(date)"
echo

# Configuration
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Test configuration
TIMEOUT_SECONDS=120
TEST_RESULTS=()
TEST_NAMES=()

# Function to run a single TCK test with analysis
run_tck_test() {
    local test_name="$1"
    local display_name="$2"
    local timeout="${3:-$TIMEOUT_SECONDS}"
    
    echo "🧪 Testing: $display_name"
    echo "   Class: $test_name"
    echo "   Timeout: ${timeout}s"
    
    # Clear previous results
    rm -f core/target/surefire-reports/TEST-*${test_name}*.xml 2>/dev/null
    
    # Run test with timeout
    echo "   Status: Running..."
    timeout ${timeout}s mvn test -Dtest="$test_name" -f core/pom.xml -Pdevelopment -q
    
    local exit_code=$?
    local result=""
    local details=""
    
    if [ $exit_code -eq 0 ]; then
        result="PASS"
        details="✅ Test completed successfully"
    elif [ $exit_code -eq 124 ]; then
        result="TIMEOUT"
        details="⏰ Test timed out after ${timeout}s"
    else
        result="FAIL"
        details="❌ Test failed with exit code $exit_code"
        
        # Analyze failure reasons
        local report_files=$(find core/target/surefire-reports -name "*${test_name}*.xml" 2>/dev/null)
        if [ ! -z "$report_files" ]; then
            echo "   📋 Analyzing failure reasons..."
            
            # Check for specific error patterns
            if grep -q "folderId must be set" $report_files; then
                details="$details (folderId parameter issue)"
            elif grep -q "Invalid property.*propertyDefinitionId" $report_files; then
                details="$details (JSONConverter property format issue)"  
            elif grep -q "content length" $report_files; then
                details="$details (content stream handling issue)"
            elif grep -q "Unknown operation" $report_files; then
                details="$details (unsupported operation/URL issue)"
            elif grep -q "ClassCast" $report_files; then
                details="$details (type casting issue)"
            else
                # Get the first error message
                local error_msg=$(grep -o "<error[^>]*message=\"[^\"]*\"" $report_files | head -1 | sed 's/.*message="\([^"]*\)".*/\1/')
                if [ ! -z "$error_msg" ]; then
                    details="$details ($error_msg)"
                fi
            fi
        fi
    fi
    
    echo "   Result: $result"
    echo "   Details: $details"
    echo
    
    # Store results
    TEST_NAMES+=("$display_name")
    TEST_RESULTS+=("$result")
    
    return $exit_code
}

echo "🚀 Starting TCK Test Analysis..."
echo "=================================="
echo

# Test 1: Basic Types Test (should be most stable)
run_tck_test "TypesTestGroup#baseTypesTest" "Base Types Test" 30

# Test 2: Create and Delete Document Test (our main target)
run_tck_test "CrudTestGroup#createAndDeleteDocumentTest" "Create/Delete Document Test" 60

# Test 3: Create Document Without Content Test (originally failing)
run_tck_test "CrudTestGroup#createDocumentWithoutContentTest" "Create Document Without Content" 60

# Test 4: Create and Delete Folder Test
run_tck_test "CrudTestGroup#createAndDeleteFolderTest" "Create/Delete Folder Test" 60

# Test 5: Query Test (basic)
run_tck_test "QueryTestGroup#queryForDocumentsTest" "Query Documents Test" 45

# Test 6: Versioning Test (if stable)
run_tck_test "VersioningTestGroup#versioningSmokeTest" "Versioning Smoke Test" 45

echo "📊 TCK Test Results Analysis"
echo "============================="
echo

# Count results
TOTAL_TESTS=${#TEST_NAMES[@]}
PASSED_TESTS=0
FAILED_TESTS=0  
TIMEOUT_TESTS=0

for i in "${!TEST_RESULTS[@]}"; do
    result="${TEST_RESULTS[$i]}"
    name="${TEST_NAMES[$i]}"
    
    case "$result" in
        "PASS")
            PASSED_TESTS=$((PASSED_TESTS + 1))
            echo "✅ $name: PASSED"
            ;;
        "FAIL")
            FAILED_TESTS=$((FAILED_TESTS + 1))
            echo "❌ $name: FAILED"
            ;;
        "TIMEOUT")
            TIMEOUT_TESTS=$((TIMEOUT_TESTS + 1))
            echo "⏰ $name: TIMEOUT"
            ;;
    esac
done

echo
echo "📈 Summary Statistics:"
echo "   Total Tests: $TOTAL_TESTS"
echo "   Passed: $PASSED_TESTS"
echo "   Failed: $FAILED_TESTS"
echo "   Timed Out: $TIMEOUT_TESTS"

if [ $TOTAL_TESTS -gt 0 ]; then
    SUCCESS_RATE=$((PASSED_TESTS * 100 / TOTAL_TESTS))
    FAILURE_RATE=$((FAILED_TESTS * 100 / TOTAL_TESTS))
    TIMEOUT_RATE=$((TIMEOUT_TESTS * 100 / TOTAL_TESTS))
    
    echo
    echo "📊 Success Rates:"
    echo "   Success Rate: $SUCCESS_RATE%"
    echo "   Failure Rate: $FAILURE_RATE%"
    echo "   Timeout Rate: $TIMEOUT_RATE%"
else
    SUCCESS_RATE=0
fi

echo

# Overall assessment
if [ $SUCCESS_RATE -eq 100 ]; then
    echo "🎉 EXCELLENT: All TCK tests passed!"
    echo "   Your fixes have successfully resolved the TCK issues"
    echo
    echo "🚀 READY FOR FULL TCK SUITE:"
    echo "   mvn test -Dtest=TckSuite -f core/pom.xml -Pdevelopment"
    
elif [ $SUCCESS_RATE -ge 80 ]; then
    echo "✅ VERY GOOD: Most TCK tests passed ($SUCCESS_RATE%)"
    echo "   Major improvements achieved with minor issues remaining"
    
    if [ $TIMEOUT_TESTS -gt 0 ]; then
        echo
        echo "⏰ TIMEOUT ISSUES:"
        echo "   Some tests are still experiencing hanging/performance issues"
        echo "   This may indicate infinite loops or deadlocks in the code"
    fi
    
    if [ $FAILED_TESTS -gt 0 ]; then
        echo
        echo "🔧 REMAINING FAILURES:"
        echo "   $FAILED_TESTS tests still failing - check surefire reports for details"
    fi
    
elif [ $SUCCESS_RATE -ge 50 ]; then
    echo "⚠️  MODERATE: Some TCK tests passed ($SUCCESS_RATE%)"
    echo "   Significant progress made but work remains"
    
    echo
    echo "🔧 PRIORITY FIXES:"
    if [ $TIMEOUT_TESTS -gt 0 ]; then
        echo "   - Fix hanging/timeout issues ($TIMEOUT_TESTS tests affected)"
    fi
    if [ $FAILED_TESTS -gt 0 ]; then
        echo "   - Debug remaining test failures ($FAILED_TESTS tests affected)"
    fi
    
elif [ $SUCCESS_RATE -gt 0 ]; then
    echo "⚠️  LIMITED: Few TCK tests passed ($SUCCESS_RATE%)"
    echo "   Some improvements made but fundamental issues remain"
    
    echo
    echo "🚨 MAJOR ISSUES:"
    echo "   - Review servlet parameter extraction logic"
    echo "   - Check JSONConverter property format fixes"
    echo "   - Verify URL-to-Parameter conversion is working"
    echo "   - Debug OpenCMIS service integration"
    
else
    echo "❌ POOR: No TCK tests passed"
    echo "   Fundamental problems prevent TCK execution"
    
    echo
    echo "🚨 CRITICAL ACTIONS:"
    echo "   1. Verify basic CMIS endpoints work manually"
    echo "   2. Check container logs for errors"
    echo "   3. Run clean-build-and-deploy.sh"
    echo "   4. Test with simple Browser Binding operations first"
fi

echo
echo "🔍 Detailed Analysis Recommendations:"

if [ $FAILED_TESTS -gt 0 ] || [ $TIMEOUT_TESTS -gt 0 ]; then
    echo
    echo "📋 Next Steps for Debugging:"
    echo "   1. Check surefire reports: ls -la core/target/surefire-reports/"
    echo "   2. Analyze failure patterns:"
    echo "      grep -r 'folderId must be set' core/target/surefire-reports/"
    echo "      grep -r 'propertyDefinitionId' core/target/surefire-reports/"
    echo "      grep -r 'content length' core/target/surefire-reports/"
    echo "   3. Check servlet logs:"
    echo "      docker logs docker-core-1 --tail 100 | grep -E '(ERROR|Exception)'"
    echo "   4. Run individual failing tests with verbose output:"
    echo "      mvn test -Dtest=FailingTestName -f core/pom.xml -Pdevelopment -X"
fi

if [ $TIMEOUT_TESTS -gt 0 ]; then
    echo
    echo "⏰ Timeout Investigation:"
    echo "   - Check for infinite loops in parameter processing"
    echo "   - Review permission checking logic (often causes hangs)"
    echo "   - Verify database query performance"
    echo "   - Consider enabling debug logging to identify hang points"
fi

echo
echo "🎯 Success Criteria Met:"
if [ $SUCCESS_RATE -ge 80 ]; then
    echo "   ✅ TCK test success rate target achieved ($SUCCESS_RATE% >= 80%)"
else
    echo "   ❌ TCK test success rate below target ($SUCCESS_RATE% < 80%)"
fi

if [ $PASSED_TESTS -gt 0 ]; then
    echo "   ✅ Some TCK tests passing (demonstrating core fixes work)"
else
    echo "   ❌ No TCK tests passing (fundamental issues remain)"
fi

echo
echo "📁 Test Artifacts Available:"
echo "   - Surefire Reports: core/target/surefire-reports/"
echo "   - Container Logs: docker logs docker-core-1"
echo "   - Test Configuration: core/pom.xml (Pdevelopment profile)"

echo
echo "=========================================="
echo "     TCK ANALYSIS COMPLETE"
echo "=========================================="

# Set exit code based on success rate
if [ $SUCCESS_RATE -eq 100 ]; then
    exit 0
elif [ $SUCCESS_RATE -ge 80 ]; then
    exit 1
elif [ $SUCCESS_RATE -ge 50 ]; then
    exit 2
else
    exit 3
fi
