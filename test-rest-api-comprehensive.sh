#!/bin/bash

# NemakiWare REST API 包括的テストスクリプト
# 実装された新規REST APIの機能をテスト

set -e

echo "=== NemakiWare REST API 包括的テスト ==="
echo "テスト開始時刻: $(date)"
echo

# テスト設定
BASE_URL="http://localhost:8080/core"
REPO_ID="bedroom"
AUTH="admin:admin"
TEMP_DIR="/tmp/nemaki_rest_test"
mkdir -p "$TEMP_DIR"

# テスト結果カウンター
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# テスト結果記録関数
test_result() {
    local test_name="$1"
    local expected_status="$2"
    local actual_status="$3"
    local response="$4"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    if [ "$actual_status" = "$expected_status" ]; then
        echo "✅ $test_name: HTTP $actual_status (期待値: $expected_status)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "❌ $test_name: HTTP $actual_status (期待値: $expected_status)"
        echo "   レスポンス: $(echo "$response" | head -c 200)..."
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

# JSON形式チェック関数
check_json_response() {
    local response="$1"
    if echo "$response" | jq . >/dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

echo "=== 1. Type管理REST API テスト ==="
echo

# 1.1 Type一覧取得テスト
echo "1.1 Type一覧取得テスト"
TYPE_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/type/list" -w "%{http_code}")
TYPE_LIST_STATUS="${TYPE_LIST_RESPONSE: -3}"
TYPE_LIST_BODY="${TYPE_LIST_RESPONSE%???}"
test_result "Type一覧取得" "200" "$TYPE_LIST_STATUS" "$TYPE_LIST_BODY"

if check_json_response "$TYPE_LIST_BODY"; then
    echo "   JSON形式: ✅"
    TYPE_COUNT=$(echo "$TYPE_LIST_BODY" | jq -r '. | length' 2>/dev/null || echo "不明")
    echo "   取得型定義数: $TYPE_COUNT"
else
    echo "   JSON形式: ❌"
fi

# 1.2 個別Type詳細取得テスト
echo
echo "1.2 個別Type詳細取得テスト (cmis:document)"
TYPE_SHOW_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/type/show/cmis:document" -w "%{http_code}")
TYPE_SHOW_STATUS="${TYPE_SHOW_RESPONSE: -3}"
TYPE_SHOW_BODY="${TYPE_SHOW_RESPONSE%???}"
test_result "Type詳細取得" "200" "$TYPE_SHOW_STATUS" "$TYPE_SHOW_BODY"

if check_json_response "$TYPE_SHOW_BODY"; then
    echo "   JSON形式: ✅"
    TYPE_ID=$(echo "$TYPE_SHOW_BODY" | jq -r '.id' 2>/dev/null || echo "不明")
    echo "   取得型ID: $TYPE_ID"
else
    echo "   JSON形式: ❌"
fi

echo
echo "=== 2. Permission/ACL管理REST API テスト ==="
echo

# テスト用オブジェクトID（ルートフォルダ）
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

# 2.1 ACL取得テスト
echo "2.1 ACL取得テスト (ルートフォルダ)"
ACL_GET_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/node/$ROOT_FOLDER_ID/acl" -w "%{http_code}")
ACL_GET_STATUS="${ACL_GET_RESPONSE: -3}"
ACL_GET_BODY="${ACL_GET_RESPONSE%???}"
test_result "ACL取得" "200" "$ACL_GET_STATUS" "$ACL_GET_BODY"

if check_json_response "$ACL_GET_BODY"; then
    echo "   JSON形式: ✅"
    ACL_PERMISSIONS=$(echo "$ACL_GET_BODY" | jq -r '.result.acl.permissions | length' 2>/dev/null || echo "不明")
    echo "   権限エントリ数: $ACL_PERMISSIONS"
    
    # ACL詳細を一時ファイルに保存（後で復元用）
    echo "$ACL_GET_BODY" > "$TEMP_DIR/original_acl.json"
else
    echo "   JSON形式: ❌"
fi

# 2.2 ACL設定テスト
echo
echo "2.2 ACL設定テスト (testuser読み取り権限追加)"

# テスト用ACL JSON作成
cat > "$TEMP_DIR/test_acl.json" << 'EOF'
{
  "permissions": [
    {
      "principalId": "admin",
      "permissions": ["cmis:all"],
      "direct": true
    },
    {
      "principalId": "testuser",
      "permissions": ["cmis:read"],
      "direct": true
    }
  ]
}
EOF

ACL_SET_RESPONSE=$(curl -s -u "$AUTH" \
    -X POST \
    -H "Content-Type: application/json" \
    -d @"$TEMP_DIR/test_acl.json" \
    "$BASE_URL/rest/repo/$REPO_ID/node/$ROOT_FOLDER_ID/acl" \
    -w "%{http_code}")
ACL_SET_STATUS="${ACL_SET_RESPONSE: -3}"
ACL_SET_BODY="${ACL_SET_RESPONSE%???}"
test_result "ACL設定" "200" "$ACL_SET_STATUS" "$ACL_SET_BODY"

echo
echo "=== 3. User/Group JSON対応API テスト ==="
echo

# 3.1 JSON形式でのUser更新テスト
echo "3.1 JSON形式User更新テスト (testuser)"

# テスト用User JSON作成
cat > "$TEMP_DIR/test_user.json" << 'EOF'
{
  "userId": "testuser",
  "userName": "Test User Updated",
  "firstName": "Test",
  "lastName": "User",
  "email": "testuser.updated@example.com",
  "admin": false
}
EOF

USER_UPDATE_RESPONSE=$(curl -s -u "$AUTH" \
    -X PUT \
    -H "Content-Type: application/json" \
    -d @"$TEMP_DIR/test_user.json" \
    "$BASE_URL/rest/repo/$REPO_ID/user/update-json/testuser" \
    -w "%{http_code}")
USER_UPDATE_STATUS="${USER_UPDATE_RESPONSE: -3}"
USER_UPDATE_BODY="${USER_UPDATE_RESPONSE%???}"
test_result "User JSON更新" "200" "$USER_UPDATE_STATUS" "$USER_UPDATE_BODY"

if check_json_response "$USER_UPDATE_BODY"; then
    echo "   JSON形式: ✅"
    UPDATED_USER_NAME=$(echo "$USER_UPDATE_BODY" | jq -r '.result.user.userName' 2>/dev/null || echo "不明")
    echo "   更新後ユーザー名: $UPDATED_USER_NAME"
else
    echo "   JSON形式: ❌"
fi

echo
echo "=== 4. Archive管理REST API テスト ==="
echo

# 4.1 Archive一覧取得テスト
echo "4.1 Archive一覧取得テスト"
ARCHIVE_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/archive/index" -w "%{http_code}")
ARCHIVE_LIST_STATUS="${ARCHIVE_LIST_RESPONSE: -3}"
ARCHIVE_LIST_BODY="${ARCHIVE_LIST_RESPONSE%???}"
test_result "Archive一覧取得" "200" "$ARCHIVE_LIST_STATUS" "$ARCHIVE_LIST_BODY"

if check_json_response "$ARCHIVE_LIST_BODY"; then
    echo "   JSON形式: ✅"
    ARCHIVE_COUNT=$(echo "$ARCHIVE_LIST_BODY" | jq -r '.result.archives | length' 2>/dev/null || echo "0")
    echo "   アーカイブ数: $ARCHIVE_COUNT"
    
    # アーカイブがある場合、最初のアーカイブIDを取得
    if [ "$ARCHIVE_COUNT" != "0" ] && [ "$ARCHIVE_COUNT" != "null" ]; then
        FIRST_ARCHIVE_ID=$(echo "$ARCHIVE_LIST_BODY" | jq -r '.result.archives[0].id' 2>/dev/null)
        echo "   テスト用アーカイブID: $FIRST_ARCHIVE_ID"
        
        # 4.2 個別Archive詳細取得テスト
        echo
        echo "4.2 個別Archive詳細取得テスト"
        ARCHIVE_SHOW_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/archive/show/$FIRST_ARCHIVE_ID" -w "%{http_code}")
        ARCHIVE_SHOW_STATUS="${ARCHIVE_SHOW_RESPONSE: -3}"
        ARCHIVE_SHOW_BODY="${ARCHIVE_SHOW_RESPONSE%???}"
        test_result "Archive詳細取得" "200" "$ARCHIVE_SHOW_STATUS" "$ARCHIVE_SHOW_BODY"
        
        if check_json_response "$ARCHIVE_SHOW_BODY"; then
            echo "   JSON形式: ✅"
            ARCHIVE_NAME=$(echo "$ARCHIVE_SHOW_BODY" | jq -r '.result.archive.name' 2>/dev/null || echo "不明")
            echo "   アーカイブ名: $ARCHIVE_NAME"
        else
            echo "   JSON形式: ❌"
        fi
    else
        echo "   アーカイブが存在しないため個別詳細テストはスキップ"
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
        echo "⏭️  Archive詳細取得: スキップ (データなし)"
    fi
else
    echo "   JSON形式: ❌"
fi

echo
echo "=== 5. Config管理REST API テスト ==="
echo

# 5.1 Config一覧取得テスト
echo "5.1 Config一覧取得テスト"
CONFIG_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/config/list" -w "%{http_code}")
CONFIG_LIST_STATUS="${CONFIG_LIST_RESPONSE: -3}"
CONFIG_LIST_BODY="${CONFIG_LIST_RESPONSE%???}"
test_result "Config一覧取得" "200" "$CONFIG_LIST_STATUS" "$CONFIG_LIST_BODY"

if check_json_response "$CONFIG_LIST_BODY"; then
    echo "   JSON形式: ✅"
    CONFIG_COUNT=$(echo "$CONFIG_LIST_BODY" | jq -r '.result.configurations | length' 2>/dev/null || echo "不明")
    echo "   設定項目数: $CONFIG_COUNT"
    
    # 設定がある場合、最初の設定キーを取得してテスト
    if [ "$CONFIG_COUNT" != "0" ] && [ "$CONFIG_COUNT" != "null" ]; then
        FIRST_CONFIG_KEY=$(echo "$CONFIG_LIST_BODY" | jq -r '.result.configurations[0].key' 2>/dev/null)
        echo "   テスト用設定キー: $FIRST_CONFIG_KEY"
        
        # 5.2 個別Config詳細取得テスト
        echo
        echo "5.2 個別Config詳細取得テスト"
        CONFIG_SHOW_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/config/show/$FIRST_CONFIG_KEY" -w "%{http_code}")
        CONFIG_SHOW_STATUS="${CONFIG_SHOW_RESPONSE: -3}"
        CONFIG_SHOW_BODY="${CONFIG_SHOW_RESPONSE%???}"
        test_result "Config詳細取得" "200" "$CONFIG_SHOW_STATUS" "$CONFIG_SHOW_BODY"
        
        if check_json_response "$CONFIG_SHOW_BODY"; then
            echo "   JSON形式: ✅"
            CONFIG_VALUE=$(echo "$CONFIG_SHOW_BODY" | jq -r '.result.configuration.value' 2>/dev/null || echo "不明")
            echo "   設定値: $CONFIG_VALUE"
        else
            echo "   JSON形式: ❌"
        fi
    else
        echo "   設定項目が存在しないため個別詳細テストはスキップ"
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
        echo "⏭️  Config詳細取得: スキップ (データなし)"
    fi
else
    echo "   JSON形式: ❌"
fi

echo
echo "=== 6. 関連CMIS標準API テスト（参考） ==="
echo

# 6.1 Repository情報取得
echo "6.1 Repository情報取得テスト"
REPO_INFO_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/atom/$REPO_ID" -w "%{http_code}")
REPO_INFO_STATUS="${REPO_INFO_RESPONSE: -3}"
test_result "Repository情報取得" "200" "$REPO_INFO_STATUS" "AtomPub XML"

# 6.2 Browser Binding Repository情報
echo "6.2 Browser Binding Repository情報テスト"
BROWSER_REPO_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/browser/$REPO_ID?cmisselector=repositoryInfo" -w "%{http_code}")
BROWSER_REPO_STATUS="${BROWSER_REPO_RESPONSE: -3}"
BROWSER_REPO_BODY="${BROWSER_REPO_RESPONSE%???}"
test_result "Browser Repository情報" "200" "$BROWSER_REPO_STATUS" "$BROWSER_REPO_BODY"

echo
echo "=== テスト結果サマリー ==="
echo "総テスト数: $TOTAL_TESTS"
echo "成功: $PASSED_TESTS"
echo "失敗: $FAILED_TESTS"
echo

if [ $FAILED_TESTS -eq 0 ]; then
    echo "🎉 全テストが成功しました！実装されたREST APIは正常に動作しています。"
    OVERALL_RESULT="SUCCESS"
else
    echo "⚠️  $FAILED_TESTS 個のテストが失敗しました。詳細を確認してください。"
    OVERALL_RESULT="PARTIAL_FAILURE"
fi

echo
echo "テスト完了時刻: $(date)"
echo "テスト結果: $OVERALL_RESULT"
echo

# テスト結果をファイルに保存
cat > "$TEMP_DIR/test_results.json" << EOF
{
  "timestamp": "$(date -Iseconds)",
  "overall_result": "$OVERALL_RESULT",
  "total_tests": $TOTAL_TESTS,
  "passed_tests": $PASSED_TESTS,
  "failed_tests": $FAILED_TESTS,
  "success_rate": "$(echo "scale=1; $PASSED_TESTS * 100 / $TOTAL_TESTS" | bc -l)%"
}
EOF

echo "詳細なテスト結果は $TEMP_DIR/test_results.json に保存されました。"
echo "テスト用ファイルは $TEMP_DIR/ に保存されました。"

# 終了コード設定
if [ $FAILED_TESTS -eq 0 ]; then
    exit 0
else
    exit 1
fi