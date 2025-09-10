#!/bin/bash

# Staged testing script for NemakiWare TCK fixes
# This script runs comprehensive tests in logical order to verify all improvements

echo "=========================================="
echo "   NemakiWare Staged Testing Pipeline"
echo "=========================================="
echo "Timestamp: $(date)"
echo

# Configuration
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# Track test results
STAGE1_RESULT="PENDING"
STAGE2_RESULT="PENDING"  
STAGE3_RESULT="PENDING"
STAGE4_RESULT="PENDING"
STAGE5_RESULT="PENDING"

echo "🚀 STAGE 1: Code Deployment Verification"
echo "=========================================="
echo

echo "Step 1.1: WAR Content Verification"
if [ -x "./verify-war-deployment.sh" ]; then
    ./verify-war-deployment.sh
    if [ $? -eq 0 ]; then
        echo "✅ WAR deployment verification passed"
        STAGE1A_RESULT="PASS"
    else
        echo "❌ WAR deployment verification failed"
        STAGE1A_RESULT="FAIL"
    fi
else
    echo "❌ WAR verification script not found"
    STAGE1A_RESULT="SKIP"
fi

echo
echo "Step 1.2: Container Deployment Verification"
if [ -x "./verify-container-deployment.sh" ]; then
    ./verify-container-deployment.sh
    if [ $? -eq 0 ]; then
        echo "✅ Container deployment verification passed"
        STAGE1B_RESULT="PASS"
    else
        echo "❌ Container deployment verification failed"
        STAGE1B_RESULT="FAIL"
    fi
else
    echo "❌ Container verification script not found"
    STAGE1B_RESULT="SKIP"
fi

# Evaluate Stage 1
if [ "$STAGE1A_RESULT" = "PASS" ] && [ "$STAGE1B_RESULT" = "PASS" ]; then
    STAGE1_RESULT="PASS"
    echo
    echo "🎉 STAGE 1: PASSED - Code is properly deployed"
elif [ "$STAGE1A_RESULT" = "FAIL" ] || [ "$STAGE1B_RESULT" = "FAIL" ]; then
    STAGE1_RESULT="FAIL"
    echo
    echo "❌ STAGE 1: FAILED - Code deployment issues detected"
    echo "   RECOMMENDATION: Run ./clean-build-and-deploy.sh to resolve deployment issues"
    echo "   Continuing with tests but results may not reflect latest code changes..."
else
    STAGE1_RESULT="PARTIAL"
    echo
    echo "⚠️  STAGE 1: PARTIAL - Some verification scripts missing"
fi

echo
echo "🧪 STAGE 2: Basic CMIS Functionality"
echo "====================================="
echo

echo "Step 2.1: Supported Endpoints Test"
if [ -x "./test-supported-endpoints.sh" ]; then
    ./test-supported-endpoints.sh
    if [ $? -eq 0 ]; then
        echo "✅ Supported endpoints test passed"
        STAGE2A_RESULT="PASS"
    else
        echo "❌ Supported endpoints test failed"
        STAGE2A_RESULT="FAIL"
    fi
else
    echo "❌ Supported endpoints test script not found"
    STAGE2A_RESULT="SKIP"
fi

echo
echo "Step 2.2: JSONConverter Compatibility Test"
if [ -x "./test-jsonconverter-compatibility.sh" ]; then
    ./test-jsonconverter-compatibility.sh
    if [ $? -eq 0 ]; then
        echo "✅ JSONConverter compatibility test passed"
        STAGE2B_RESULT="PASS"
    else
        echo "❌ JSONConverter compatibility test failed"
        STAGE2B_RESULT="FAIL"
    fi
else
    echo "❌ JSONConverter compatibility test script not found"
    STAGE2B_RESULT="SKIP"
fi

# Evaluate Stage 2
if [ "$STAGE2A_RESULT" = "PASS" ] && [ "$STAGE2B_RESULT" = "PASS" ]; then
    STAGE2_RESULT="PASS"
    echo
    echo "🎉 STAGE 2: PASSED - Basic CMIS functionality working"
elif [ "$STAGE2A_RESULT" = "FAIL" ] || [ "$STAGE2B_RESULT" = "FAIL" ]; then
    STAGE2_RESULT="FAIL"
    echo
    echo "❌ STAGE 2: FAILED - CMIS functionality issues detected"
else
    STAGE2_RESULT="PARTIAL"
fi

echo
echo "🔄 STAGE 3: URL Conversion and Parameter Handling"
echo "================================================="
echo

echo "Step 3.1: URL-to-Parameter Conversion Test"
if [ -x "./test-url-conversion.sh" ]; then
    ./test-url-conversion.sh
    if [ $? -eq 0 ]; then
        echo "✅ URL conversion test passed"
        STAGE3A_RESULT="PASS"
    else
        echo "❌ URL conversion test failed"
        STAGE3A_RESULT="FAIL"
    fi
else
    echo "❌ URL conversion test script not found"
    STAGE3A_RESULT="SKIP"
fi

# Evaluate Stage 3
if [ "$STAGE3A_RESULT" = "PASS" ]; then
    STAGE3_RESULT="PASS"
    echo
    echo "🎉 STAGE 3: PASSED - URL conversion working correctly"
else
    STAGE3_RESULT="FAIL"
    echo
    echo "❌ STAGE 3: FAILED - URL conversion needs improvement"
fi

echo
echo "🏃 STAGE 4: Comprehensive QA Testing"
echo "===================================="
echo

echo "Step 4.1: Standard QA Test Suite"
if [ -x "./qa-test.sh" ]; then
    echo "Running comprehensive QA tests..."
    ./qa-test.sh
    QA_EXIT_CODE=$?
    
    if [ $QA_EXIT_CODE -eq 0 ]; then
        echo "✅ QA test suite passed"
        STAGE4A_RESULT="PASS"
    else
        echo "❌ QA test suite failed (exit code: $QA_EXIT_CODE)"
        STAGE4A_RESULT="FAIL"
    fi
else
    echo "❌ QA test script not found"
    STAGE4A_RESULT="SKIP"
fi

# Evaluate Stage 4
if [ "$STAGE4A_RESULT" = "PASS" ]; then
    STAGE4_RESULT="PASS"
    echo
    echo "🎉 STAGE 4: PASSED - QA test suite successful"
else
    STAGE4_RESULT="FAIL"
    echo
    echo "❌ STAGE 4: FAILED - QA test issues detected"
fi

echo
echo "🚀 STAGE 5: TCK Sample Tests"
echo "============================"
echo

echo "Step 5.1: Single TCK Test - Create Document Without Content"
echo "Testing the specific test that was originally failing..."

cd /Users/ishiiakinori/NemakiWare

# Test with timeout to prevent hanging
echo "Running CreateDocumentWithoutContentTest with 60 second timeout..."
timeout 60s mvn test -Dtest=CreateDocumentWithoutContentTest -f core/pom.xml -Pdevelopment -q

TCK_EXIT_CODE=$?

if [ $TCK_EXIT_CODE -eq 0 ]; then
    echo "✅ CreateDocumentWithoutContentTest PASSED"
    STAGE5A_RESULT="PASS"
elif [ $TCK_EXIT_CODE -eq 124 ]; then
    echo "⏰ CreateDocumentWithoutContentTest TIMED OUT (still has hanging issues)"
    STAGE5A_RESULT="TIMEOUT"
else
    echo "❌ CreateDocumentWithoutContentTest FAILED (exit code: $TCK_EXIT_CODE)"
    STAGE5A_RESULT="FAIL"
    
    # Check for specific error patterns
    if [ -f "core/target/surefire-reports/TEST-org.apache.chemistry.opencmis.tck.tests.CrudTestGroup.xml" ]; then
        echo "Checking for specific error patterns..."
        if grep -q "folderId must be set" core/target/surefire-reports/*.xml; then
            echo "🔍 Found 'folderId must be set' error - URL conversion issue"
        fi
        if grep -q "Invalid property" core/target/surefire-reports/*.xml; then
            echo "🔍 Found 'Invalid property' error - JSONConverter issue"
        fi
        if grep -q "content length" core/target/surefire-reports/*.xml; then
            echo "🔍 Found 'content length' error - original issue may persist"
        fi
    fi
fi

echo
echo "Step 5.2: Additional TCK Sample Test - Types Test"
echo "Testing a simpler TCK test for comparison..."

timeout 30s mvn test -Dtest=TypesTestGroup#baseTypesTest -f core/pom.xml -Pdevelopment -q

TYPES_EXIT_CODE=$?

if [ $TYPES_EXIT_CODE -eq 0 ]; then
    echo "✅ BaseTypesTest PASSED"
    STAGE5B_RESULT="PASS"
elif [ $TYPES_EXIT_CODE -eq 124 ]; then
    echo "⏰ BaseTypesTest TIMED OUT"
    STAGE5B_RESULT="TIMEOUT"
else
    echo "❌ BaseTypesTest FAILED (exit code: $TYPES_EXIT_CODE)"
    STAGE5B_RESULT="FAIL"
fi

# Evaluate Stage 5
if [ "$STAGE5A_RESULT" = "PASS" ] && [ "$STAGE5B_RESULT" = "PASS" ]; then
    STAGE5_RESULT="PASS"
    echo
    echo "🎉 STAGE 5: PASSED - TCK tests working!"
elif [ "$STAGE5A_RESULT" = "PASS" ] || [ "$STAGE5B_RESULT" = "PASS" ]; then
    STAGE5_RESULT="PARTIAL"
    echo
    echo "⚠️  STAGE 5: PARTIAL - Some TCK tests working"
else
    STAGE5_RESULT="FAIL"
    echo
    echo "❌ STAGE 5: FAILED - TCK tests still have issues"
fi

echo
echo "=========================================="
echo "   STAGED TESTING RESULTS SUMMARY"
echo "=========================================="
echo

# Display results summary
echo "📋 STAGE RESULTS:"
echo "   🚀 Stage 1 (Deployment): $STAGE1_RESULT"
echo "   🧪 Stage 2 (Basic CMIS): $STAGE2_RESULT"  
echo "   🔄 Stage 3 (URL Conversion): $STAGE3_RESULT"
echo "   🏃 Stage 4 (QA Testing): $STAGE4_RESULT"
echo "   🚀 Stage 5 (TCK Sample): $STAGE5_RESULT"

echo
echo "📊 DETAILED BREAKDOWN:"
echo "   Stage 1A (WAR Deployment): $STAGE1A_RESULT"
echo "   Stage 1B (Container Deployment): $STAGE1B_RESULT"
echo "   Stage 2A (Supported Endpoints): $STAGE2A_RESULT"
echo "   Stage 2B (JSONConverter): $STAGE2B_RESULT"
echo "   Stage 3A (URL Conversion): $STAGE3A_RESULT"
echo "   Stage 4A (QA Suite): $STAGE4A_RESULT"
echo "   Stage 5A (CreateDocument Test): $STAGE5A_RESULT"
echo "   Stage 5B (Types Test): $STAGE5B_RESULT"

echo

# Count successful stages
PASSED_STAGES=0
[ "$STAGE1_RESULT" = "PASS" ] && PASSED_STAGES=$((PASSED_STAGES + 1))
[ "$STAGE2_RESULT" = "PASS" ] && PASSED_STAGES=$((PASSED_STAGES + 1))
[ "$STAGE3_RESULT" = "PASS" ] && PASSED_STAGES=$((PASSED_STAGES + 1))
[ "$STAGE4_RESULT" = "PASS" ] && PASSED_STAGES=$((PASSED_STAGES + 1))
[ "$STAGE5_RESULT" = "PASS" ] && PASSED_STAGES=$((PASSED_STAGES + 1))

TOTAL_STAGES=5
SUCCESS_RATE=$((PASSED_STAGES * 100 / TOTAL_STAGES))

echo "🎯 OVERALL SUCCESS RATE: $PASSED_STAGES/$TOTAL_STAGES stages passed ($SUCCESS_RATE%)"

# Final assessment and recommendations
echo
if [ $SUCCESS_RATE -eq 100 ]; then
    echo "🎉 EXCELLENT: All staged tests passed!"
    echo "   ✅ Your TCK fixes are working correctly"
    echo "   ✅ Ready for full TCK test suite execution"
    echo
    echo "🚀 NEXT STEPS:"
    echo "   - Run full TCK test suite: mvn test -Dtest=TckSuite -f core/pom.xml -Pdevelopment"
    echo "   - Monitor for any remaining edge cases"
elif [ $SUCCESS_RATE -ge 80 ]; then
    echo "⚠️  GOOD: Most staged tests passed"
    echo "   ✅ Core functionality is working"
    echo "   📝 Some components need minor improvements"
    echo
    echo "🔧 RECOMMENDATIONS:"
    [ "$STAGE1_RESULT" != "PASS" ] && echo "   - Fix code deployment issues"
    [ "$STAGE2_RESULT" != "PASS" ] && echo "   - Improve CMIS basic functionality"
    [ "$STAGE3_RESULT" != "PASS" ] && echo "   - Fix URL-to-Parameter conversion"
    [ "$STAGE4_RESULT" != "PASS" ] && echo "   - Address QA test failures"
    [ "$STAGE5_RESULT" != "PASS" ] && echo "   - Debug TCK-specific issues"
elif [ $SUCCESS_RATE -ge 60 ]; then
    echo "⚠️  MODERATE: Some staged tests passed"
    echo "   📝 Significant improvements made but work remains"
    echo
    echo "🔧 PRIORITY FIXES NEEDED:"
    [ "$STAGE1_RESULT" != "PASS" ] && echo "   - HIGH: Fix code deployment pipeline"
    [ "$STAGE2_RESULT" != "PASS" ] && echo "   - HIGH: Fix basic CMIS operations"
    [ "$STAGE3_RESULT" != "PASS" ] && echo "   - MEDIUM: Fix URL conversion logic"
    [ "$STAGE5_RESULT" != "PASS" ] && echo "   - HIGH: Debug TCK test failures"
else
    echo "❌ POOR: Major issues remain"
    echo "   🔧 Fundamental problems need to be addressed"
    echo
    echo "🚨 CRITICAL ACTIONS NEEDED:"
    echo "   1. Run clean-build-and-deploy.sh to ensure fresh deployment"
    echo "   2. Check container logs for errors: docker logs docker-core-1 --tail 50"
    echo "   3. Verify basic CMIS endpoints are working manually"
    echo "   4. Debug servlet parameter extraction and response formatting"
fi

echo
echo "📝 TEST ARTIFACTS:"
echo "   - Surefire reports: core/target/surefire-reports/"
echo "   - Container logs: docker logs docker-core-1"
echo "   - Test responses: /tmp/*_test*.json"

echo
echo "=========================================="
echo "   STAGED TESTING COMPLETE"
echo "=========================================="

# Return appropriate exit code
if [ $SUCCESS_RATE -eq 100 ]; then
    exit 0
elif [ $SUCCESS_RATE -ge 80 ]; then
    exit 1
else
    exit 2
fi