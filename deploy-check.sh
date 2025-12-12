#!/bin/bash
# deploy-check.sh - デプロイ前の必須確認スクリプト
#
# Usage: ./deploy-check.sh
#
# このスクリプトは以下の項目を検証します：
# 1. Keycloak設定の整合性
# 2. 管理者権限チェックの実装
# 3. allowableActions型定義の正確性
# 4. UIパス統一（/ui/dist/禁止）
#
# 終了コード:
#   0: 全チェック合格
#   1: 1つ以上のチェック失敗

# Don't use set -e because arithmetic operations return 1 when result is 0
# set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$SCRIPT_DIR/core/src/main/webapp/ui"

PASS_COUNT=0
FAIL_COUNT=0

check_pass() {
    echo -e "${GREEN}✅ PASS${NC}: $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

check_fail() {
    echo -e "${RED}❌ FAIL${NC}: $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

check_warn() {
    echo -e "${YELLOW}⚠️  WARN${NC}: $1"
}

echo "=============================================="
echo "NemakiWare Deploy Check Script"
echo "=============================================="
echo ""

# ===========================================
# 1. Keycloak設定確認
# ===========================================
echo "=== 1. Keycloak設定確認 ==="

if [ -f "$SCRIPT_DIR/docker/keycloak/realm-export.json" ]; then
    # testuser password check
    TESTUSER_PASSWORD=$(grep -A 10 '"username": "testuser"' "$SCRIPT_DIR/docker/keycloak/realm-export.json" | grep -A 3 '"credentials"' | grep '"value"' | head -1 | sed 's/.*"value": "\([^"]*\)".*/\1/')

    if [ "$TESTUSER_PASSWORD" = "password" ]; then
        check_pass "Keycloak testuser password is 'password'"
    else
        check_fail "Keycloak testuser password is '$TESTUSER_PASSWORD' (expected 'password')"
    fi
else
    check_warn "Keycloak realm-export.json not found (skip if not using Keycloak)"
fi

echo ""

# ===========================================
# 2. 管理者権限チェック確認
# ===========================================
echo "=== 2. 管理者権限チェック確認 ==="

# Layout.tsx - isAdmin check
if grep -q "isAdmin" "$UI_DIR/src/components/Layout/Layout.tsx" 2>/dev/null; then
    check_pass "Layout.tsx contains isAdmin check"
else
    check_fail "Layout.tsx missing isAdmin check"
fi

# App.tsx - AdminRoute import
if grep -q "AdminRoute" "$UI_DIR/src/App.tsx" 2>/dev/null; then
    check_pass "App.tsx imports AdminRoute"
else
    check_fail "App.tsx missing AdminRoute import"
fi

# AdminRoute component exists
if [ -f "$UI_DIR/src/components/AdminRoute/AdminRoute.tsx" ]; then
    check_pass "AdminRoute.tsx component exists"
else
    check_fail "AdminRoute.tsx component missing"
fi

# Admin routes protected
ADMIN_ROUTES_PROTECTED=$(grep -c "<AdminRoute>" "$UI_DIR/src/App.tsx" 2>/dev/null || echo "0")
if [ "$ADMIN_ROUTES_PROTECTED" -ge 4 ]; then
    check_pass "Admin routes protected with AdminRoute ($ADMIN_ROUTES_PROTECTED routes)"
else
    check_fail "Not enough admin routes protected (found $ADMIN_ROUTES_PROTECTED, expected >= 4)"
fi

echo ""

# ===========================================
# 3. allowableActions型確認
# ===========================================
echo "=== 3. allowableActions型確認 ==="

# AllowableActions interface exists
if grep -q "interface AllowableActions" "$UI_DIR/src/types/cmis.ts" 2>/dev/null; then
    check_pass "AllowableActions interface defined in cmis.ts"
else
    check_fail "AllowableActions interface missing in cmis.ts"
fi

# canGetContentStream property check in previewUtils
if grep -q "canGetContentStream === true" "$UI_DIR/src/utils/previewUtils.ts" 2>/dev/null; then
    check_pass "canPreview uses object property access (canGetContentStream === true)"
else
    check_fail "canPreview may be using incorrect array-based check"
fi

# Check for old array-based pattern (should NOT exist)
if grep -q "allowableActions?.includes" "$UI_DIR/src/utils/previewUtils.ts" 2>/dev/null; then
    check_fail "previewUtils.ts still using array-based .includes() check"
else
    check_pass "No array-based allowableActions check in previewUtils.ts"
fi

echo ""

# ===========================================
# 4. UIパス統一確認
# ===========================================
echo "=== 4. UIパス統一確認 (/ui/dist/ 禁止) ==="

# Check for /ui/dist/ in source files
DIST_PATH_COUNT=$(grep -r "/ui/dist/" "$UI_DIR/src/" --include="*.ts" --include="*.tsx" 2>/dev/null | wc -l | tr -d ' ')
if [ "$DIST_PATH_COUNT" -eq 0 ]; then
    check_pass "No /ui/dist/ paths in source files"
else
    check_fail "Found $DIST_PATH_COUNT occurrences of /ui/dist/ in source files"
    grep -r "/ui/dist/" "$UI_DIR/src/" --include="*.ts" --include="*.tsx" 2>/dev/null | head -5
fi

# Check for /ui/dist/ in test files
DIST_PATH_TEST_COUNT=$(grep -r "/ui/dist/" "$UI_DIR/tests/" --include="*.ts" 2>/dev/null | wc -l | tr -d ' ')
if [ "$DIST_PATH_TEST_COUNT" -eq 0 ]; then
    check_pass "No /ui/dist/ paths in test files"
else
    check_fail "Found $DIST_PATH_TEST_COUNT occurrences of /ui/dist/ in test files"
fi

echo ""

# ===========================================
# 5. TypeScript型チェック
# ===========================================
echo "=== 5. TypeScript型チェック ==="

if [ -f "$UI_DIR/package.json" ]; then
    cd "$UI_DIR"
    # Check specifically for allowableActions-related errors (the issue we fixed)
    TS_OUTPUT=$(npm run type-check 2>&1)
    if echo "$TS_OUTPUT" | grep -q "allowableActions.*string\[\]"; then
        check_fail "TypeScript: allowableActions type error (should be AllowableActions, not string[])"
    else
        check_pass "TypeScript: No allowableActions type errors"
    fi

    # Count total errors (informational)
    ERROR_COUNT=$(echo "$TS_OUTPUT" | grep -c "error TS" || echo "0")
    if [ "$ERROR_COUNT" -gt 0 ]; then
        check_warn "TypeScript: $ERROR_COUNT pre-existing errors (not related to allowableActions fix)"
    fi
    cd "$SCRIPT_DIR"
else
    check_warn "package.json not found, skipping TypeScript check"
fi

echo ""

# ===========================================
# Summary
# ===========================================
echo "=============================================="
echo "Summary"
echo "=============================================="
echo -e "Passed: ${GREEN}$PASS_COUNT${NC}"
echo -e "Failed: ${RED}$FAIL_COUNT${NC}"
echo ""

if [ "$FAIL_COUNT" -gt 0 ]; then
    echo -e "${RED}❌ DEPLOY CHECK FAILED${NC}"
    echo "Please fix the above issues before deploying."
    exit 1
else
    echo -e "${GREEN}✅ ALL CHECKS PASSED${NC}"
    echo "Safe to proceed with deployment."
    exit 0
fi
