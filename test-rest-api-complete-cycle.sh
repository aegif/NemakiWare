#!/bin/bash

# NemakiWare REST API 完全サイクルテスト
# 作成 → 確認 → 削除 → 確認 の完全なテストシナリオ

set -e  # エラーで停止

BASE_URL="http://localhost:8080/core"
REPO_ID="bedroom"
AUTH="admin:admin"
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

# テスト結果ディレクトリ
TEST_DIR="/tmp/nemaki_complete_test"
mkdir -p "$TEST_DIR"

# テスト結果集計
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

echo "=== NemakiWare REST API 完全サイクルテスト ==="
echo "テスト開始時刻: $(date)"
echo

# テストヘルパー関数
test_result() {
    local test_name="$1"
    local expected="$2"
    local actual="$3"
    local response_body="$4"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    if [ "$actual" = "$expected" ]; then
        echo "✅ $test_name: HTTP $actual (期待値: $expected)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        return 0
    else
        echo "❌ $test_name: HTTP $actual (期待値: $expected)"
        echo "   レスポンス: $response_body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        return 1
    fi
}

cleanup_test_data() {
    echo "=== テストデータクリーンアップ ==="
    
    # テストユーザー削除（存在する場合）
    if [ -f "$TEST_DIR/test_user_id.txt" ]; then
        TEST_USER_ID=$(cat "$TEST_DIR/test_user_id.txt")
        echo "テストユーザー削除中: $TEST_USER_ID"
        curl -s -u "$AUTH" -X DELETE "$BASE_URL/rest/repo/$REPO_ID/user/delete/$TEST_USER_ID" > /dev/null || true
    fi
    
    # テストグループ削除（存在する場合）
    if [ -f "$TEST_DIR/test_group_id.txt" ]; then
        TEST_GROUP_ID=$(cat "$TEST_DIR/test_group_id.txt")
        echo "テストグループ削除中: $TEST_GROUP_ID"
        curl -s -u "$AUTH" -X DELETE "$BASE_URL/rest/repo/$REPO_ID/group/delete/$TEST_GROUP_ID" > /dev/null || true
    fi
    
    # テストドキュメント削除（存在する場合）
    if [ -f "$TEST_DIR/test_document_id.txt" ]; then
        TEST_DOCUMENT_ID=$(cat "$TEST_DIR/test_document_id.txt")
        echo "テストドキュメント削除中: $TEST_DOCUMENT_ID"
        curl -s -u "$AUTH" -X DELETE "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_DOCUMENT_ID" > /dev/null || true
    fi
    
    # テストフォルダ削除（存在する場合）
    if [ -f "$TEST_DIR/test_folder_id.txt" ]; then
        TEST_FOLDER_ID=$(cat "$TEST_DIR/test_folder_id.txt")
        echo "テストフォルダ削除中: $TEST_FOLDER_ID"
        curl -s -u "$AUTH" -X DELETE "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_FOLDER_ID" > /dev/null || true
    fi
    
    echo "クリーンアップ完了"
}

# 初期クリーンアップ
cleanup_test_data

echo "=== 1. User管理 完全サイクルテスト ==="

# 1.1 テストユーザー作成
echo "1.1 テストユーザー作成"
TIMESTAMP=$(date +%s)
TEST_USER_ID="testuser_$TIMESTAMP"
TEST_USER_NAME="Test User $TIMESTAMP"
TEST_USER_PASSWORD="testpass123"

USER_CREATE_RESPONSE=$(curl -s -u "$AUTH" -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "userId=$TEST_USER_ID&name=$TEST_USER_NAME&password=$TEST_USER_PASSWORD&admin=false" \
  "$BASE_URL/rest/repo/$REPO_ID/user/create/$TEST_USER_ID" -w "%{http_code}")

USER_CREATE_STATUS="${USER_CREATE_RESPONSE: -3}"
USER_CREATE_BODY="${USER_CREATE_RESPONSE%???}"

if test_result "ユーザー作成" "200" "$USER_CREATE_STATUS" "$USER_CREATE_BODY"; then
    echo "$TEST_USER_ID" > "$TEST_DIR/test_user_id.txt"
    echo "   作成ユーザーID: $TEST_USER_ID"
else
    echo "⚠️  ユーザー作成失敗により以降のユーザーテストをスキップ"
    TEST_USER_CREATED=false
fi

# 1.2 作成したユーザーの存在確認
if [ "$USER_CREATE_STATUS" = "200" ]; then
    echo "1.2 作成ユーザーの存在確認"
    USER_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/user/list" -w "%{http_code}")
    USER_LIST_STATUS="${USER_LIST_RESPONSE: -3}"
    USER_LIST_BODY="${USER_LIST_RESPONSE%???}"
    
    if test_result "ユーザー一覧取得" "200" "$USER_LIST_STATUS" "$USER_LIST_BODY"; then
        if echo "$USER_LIST_BODY" | grep -q "$TEST_USER_ID"; then
            echo "✅ 作成ユーザー確認: $TEST_USER_ID が一覧に存在"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "❌ 作成ユーザー確認: $TEST_USER_ID が一覧に存在しない"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    
    # 1.3 JSON形式でユーザー更新テスト
    echo "1.3 JSON形式ユーザー更新テスト"
    UPDATED_USER_NAME="Updated $TEST_USER_NAME"
    
    USER_UPDATE_RESPONSE=$(curl -s -u "$AUTH" -X PUT \
      -H "Content-Type: application/json" \
      -d "{\"userId\":\"$TEST_USER_ID\",\"name\":\"$UPDATED_USER_NAME\",\"admin\":false}" \
      "$BASE_URL/rest/repo/$REPO_ID/user/update-json/$TEST_USER_ID" -w "%{http_code}")
    
    USER_UPDATE_STATUS="${USER_UPDATE_RESPONSE: -3}"
    USER_UPDATE_BODY="${USER_UPDATE_RESPONSE%???}"
    
    test_result "ユーザーJSON更新" "200" "$USER_UPDATE_STATUS" "$USER_UPDATE_BODY"
    
    # 1.4 ユーザー削除
    echo "1.4 ユーザー削除"
    USER_DELETE_RESPONSE=$(curl -s -u "$AUTH" -X DELETE \
      "$BASE_URL/rest/repo/$REPO_ID/user/delete/$TEST_USER_ID" -w "%{http_code}")
    
    USER_DELETE_STATUS="${USER_DELETE_RESPONSE: -3}"
    USER_DELETE_BODY="${USER_DELETE_RESPONSE%???}"
    
    test_result "ユーザー削除" "200" "$USER_DELETE_STATUS" "$USER_DELETE_BODY"
    
    # 1.5 削除後の確認
    echo "1.5 削除後の確認"
    USER_LIST_AFTER_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/user/list" -w "%{http_code}")
    USER_LIST_AFTER_STATUS="${USER_LIST_AFTER_RESPONSE: -3}"
    USER_LIST_AFTER_BODY="${USER_LIST_AFTER_RESPONSE%???}"
    
    if test_result "削除後ユーザー一覧" "200" "$USER_LIST_AFTER_STATUS" "$USER_LIST_AFTER_BODY"; then
        if echo "$USER_LIST_AFTER_BODY" | grep -q "$TEST_USER_ID"; then
            echo "❌ 削除確認: $TEST_USER_ID がまだ一覧に存在（削除失敗）"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        else
            echo "✅ 削除確認: $TEST_USER_ID が一覧から削除済み"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    
    rm -f "$TEST_DIR/test_user_id.txt"
fi

echo ""
echo "=== 2. Group管理 完全サイクルテスト ==="

# 2.1 テストグループ作成
echo "2.1 テストグループ作成"
TEST_GROUP_ID="testgroup_$TIMESTAMP"
TEST_GROUP_NAME="Test Group $TIMESTAMP"

GROUP_CREATE_RESPONSE=$(curl -s -u "$AUTH" -X POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "groupId=$TEST_GROUP_ID&name=$TEST_GROUP_NAME" \
  "$BASE_URL/rest/repo/$REPO_ID/group/create/$TEST_GROUP_ID" -w "%{http_code}")

GROUP_CREATE_STATUS="${GROUP_CREATE_RESPONSE: -3}"
GROUP_CREATE_BODY="${GROUP_CREATE_RESPONSE%???}"

if test_result "グループ作成" "200" "$GROUP_CREATE_STATUS" "$GROUP_CREATE_BODY"; then
    echo "$TEST_GROUP_ID" > "$TEST_DIR/test_group_id.txt"
    echo "   作成グループID: $TEST_GROUP_ID"
    
    # 2.2 作成したグループの存在確認
    echo "2.2 作成グループの存在確認"
    sleep 1  # グループ作成の完了を待つ
    GROUP_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/group/list" -w "%{http_code}")
    GROUP_LIST_STATUS="${GROUP_LIST_RESPONSE: -3}"
    GROUP_LIST_BODY="${GROUP_LIST_RESPONSE%???}"
    
    if test_result "グループ一覧取得" "200" "$GROUP_LIST_STATUS" "$GROUP_LIST_BODY"; then
        if echo "$GROUP_LIST_BODY" | grep -q "$TEST_GROUP_ID"; then
            echo "✅ 作成グループ確認: $TEST_GROUP_ID が一覧に存在"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "❌ 作成グループ確認: $TEST_GROUP_ID が一覧に存在しない"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    
    # 2.3 グループ詳細取得
    echo "2.3 グループ詳細取得"
    GROUP_SHOW_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/group/show/$TEST_GROUP_ID" -w "%{http_code}")
    GROUP_SHOW_STATUS="${GROUP_SHOW_RESPONSE: -3}"
    GROUP_SHOW_BODY="${GROUP_SHOW_RESPONSE%???}"
    
    test_result "グループ詳細取得" "200" "$GROUP_SHOW_STATUS" "$GROUP_SHOW_BODY"
    
    # 2.4 グループ削除
    echo "2.4 グループ削除"
    GROUP_DELETE_RESPONSE=$(curl -s -u "$AUTH" -X DELETE \
      "$BASE_URL/rest/repo/$REPO_ID/group/delete/$TEST_GROUP_ID" -w "%{http_code}")
    
    GROUP_DELETE_STATUS="${GROUP_DELETE_RESPONSE: -3}"
    GROUP_DELETE_BODY="${GROUP_DELETE_RESPONSE%???}"
    
    test_result "グループ削除" "200" "$GROUP_DELETE_STATUS" "$GROUP_DELETE_BODY"
    
    # 2.5 削除後の確認
    echo "2.5 削除後の確認"
    GROUP_LIST_AFTER_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/group/list" -w "%{http_code}")
    GROUP_LIST_AFTER_STATUS="${GROUP_LIST_AFTER_RESPONSE: -3}"
    GROUP_LIST_AFTER_BODY="${GROUP_LIST_AFTER_RESPONSE%???}"
    
    if test_result "削除後グループ一覧" "200" "$GROUP_LIST_AFTER_STATUS" "$GROUP_LIST_AFTER_BODY"; then
        if echo "$GROUP_LIST_AFTER_BODY" | grep -q "$TEST_GROUP_ID"; then
            echo "❌ 削除確認: $TEST_GROUP_ID がまだ一覧に存在（削除失敗）"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        else
            echo "✅ 削除確認: $TEST_GROUP_ID が一覧から削除済み"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        fi
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
    
    rm -f "$TEST_DIR/test_group_id.txt"
else
    echo "⚠️  グループ作成失敗により以降のグループテストをスキップ"
fi

echo ""
echo "=== 3. Document/Archive 完全サイクルテスト ==="

# 3.1 テストドキュメント作成
echo "3.1 テストドキュメント作成"
TEST_DOCUMENT_NAME="TestDocument_$TIMESTAMP.txt"
TEST_DOCUMENT_CONTENT="This is a test document created for archive testing at $(date)"

# Browser Bindingでドキュメント作成（正しいエンドポイント）
DOCUMENT_CREATE_RESPONSE=$(curl -s -u "$AUTH" -X POST \
  -F "cmisaction=createDocument" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=$TEST_DOCUMENT_NAME" \
  -F "content=$TEST_DOCUMENT_CONTENT" \
  "$BASE_URL/browser/$REPO_ID/root?objectId=$ROOT_FOLDER_ID" -w "%{http_code}")

DOCUMENT_CREATE_STATUS="${DOCUMENT_CREATE_RESPONSE: -3}"
DOCUMENT_CREATE_BODY="${DOCUMENT_CREATE_RESPONSE%???}"

if test_result "ドキュメント作成" "201" "$DOCUMENT_CREATE_STATUS" "$DOCUMENT_CREATE_BODY"; then
    # ドキュメントIDを抽出（jqまたはgrepを使用）
    if command -v jq >/dev/null 2>&1; then
        TEST_DOCUMENT_ID=$(echo "$DOCUMENT_CREATE_BODY" | jq -r '.properties."cmis:objectId".value' 2>/dev/null || echo "")
    else
        # jqがない場合は正規表現で抽出
        TEST_DOCUMENT_ID=$(echo "$DOCUMENT_CREATE_BODY" | grep -o '"cmis:objectId"[^}]*"value":"[^"]*"' | grep -o '"value":"[^"]*"' | cut -d'"' -f4)
    fi
    
    if [ -n "$TEST_DOCUMENT_ID" ] && [ "$TEST_DOCUMENT_ID" != "null" ]; then
        echo "$TEST_DOCUMENT_ID" > "$TEST_DIR/test_document_id.txt"
        echo "   作成ドキュメントID: $TEST_DOCUMENT_ID"
        
        # 3.2 ドキュメントの存在確認
        echo "3.2 ドキュメント存在確認"
        DOCUMENT_GET_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_DOCUMENT_ID" -w "%{http_code}")
        DOCUMENT_GET_STATUS="${DOCUMENT_GET_RESPONSE: -3}"
        DOCUMENT_GET_BODY="${DOCUMENT_GET_RESPONSE%???}"
        
        test_result "ドキュメント取得" "200" "$DOCUMENT_GET_STATUS" "$DOCUMENT_GET_BODY"
        
        # 3.3 ドキュメントをアーカイブに移動（削除）
        echo "3.3 ドキュメントをアーカイブに移動"
        DOCUMENT_DELETE_RESPONSE=$(curl -s -u "$AUTH" -X DELETE \
          "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_DOCUMENT_ID" -w "%{http_code}")
        
        DOCUMENT_DELETE_STATUS="${DOCUMENT_DELETE_RESPONSE: -3}"
        DOCUMENT_DELETE_BODY="${DOCUMENT_DELETE_RESPONSE%???}"
        
        test_result "ドキュメント削除（アーカイブ移動）" "204" "$DOCUMENT_DELETE_STATUS" "$DOCUMENT_DELETE_BODY"
        
        # 3.4 アーカイブ一覧で確認
        echo "3.4 アーカイブ一覧確認"
        sleep 2  # アーカイブ処理の完了を待つ
        
        ARCHIVE_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/archive/index" -w "%{http_code}")
        ARCHIVE_LIST_STATUS="${ARCHIVE_LIST_RESPONSE: -3}"
        ARCHIVE_LIST_BODY="${ARCHIVE_LIST_RESPONSE%???}"
        
        if test_result "アーカイブ一覧取得" "200" "$ARCHIVE_LIST_STATUS" "$ARCHIVE_LIST_BODY"; then
            # アーカイブされたドキュメントを検索
            if echo "$ARCHIVE_LIST_BODY" | grep -q "$TEST_DOCUMENT_NAME"; then
                echo "✅ アーカイブ確認: $TEST_DOCUMENT_NAME がアーカイブに存在"
                PASSED_TESTS=$((PASSED_TESTS + 1))
                
                # アーカイブIDを取得（jqまたはgrepを使用）
                if command -v jq >/dev/null 2>&1; then
                    ARCHIVE_ID=$(echo "$ARCHIVE_LIST_BODY" | jq -r '.archives[] | select(.name=="'"$TEST_DOCUMENT_NAME"'") | .id' 2>/dev/null || echo "")
                else
                    # jqがない場合は正規表現で抽出（簡易版）
                    ARCHIVE_ID=$(echo "$ARCHIVE_LIST_BODY" | grep -A5 -B5 "\"$TEST_DOCUMENT_NAME\"" | grep '"id"' | head -1 | cut -d'"' -f4)
                fi
                
                if [ -n "$ARCHIVE_ID" ] && [ "$ARCHIVE_ID" != "null" ]; then
                    echo "   アーカイブID: $ARCHIVE_ID"
                    
                    # 3.5 アーカイブ詳細取得
                    echo "3.5 アーカイブ詳細取得"
                    ARCHIVE_SHOW_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/archive/show/$ARCHIVE_ID" -w "%{http_code}")
                    ARCHIVE_SHOW_STATUS="${ARCHIVE_SHOW_RESPONSE: -3}"
                    ARCHIVE_SHOW_BODY="${ARCHIVE_SHOW_RESPONSE%???}"
                    
                    test_result "アーカイブ詳細取得" "200" "$ARCHIVE_SHOW_STATUS" "$ARCHIVE_SHOW_BODY"
                    
                    # 3.6 アーカイブから復元
                    echo "3.6 アーカイブから復元"
                    ARCHIVE_RESTORE_RESPONSE=$(curl -s -u "$AUTH" -X PUT \
                      "$BASE_URL/rest/repo/$REPO_ID/archive/restore/$ARCHIVE_ID" -w "%{http_code}")
                    
                    ARCHIVE_RESTORE_STATUS="${ARCHIVE_RESTORE_RESPONSE: -3}"
                    ARCHIVE_RESTORE_BODY="${ARCHIVE_RESTORE_RESPONSE%???}"
                    
                    test_result "アーカイブ復元" "200" "$ARCHIVE_RESTORE_STATUS" "$ARCHIVE_RESTORE_BODY"
                    
                    # 3.7 復元後の確認
                    echo "3.7 復元後確認"
                    sleep 2  # 復元処理の完了を待つ
                    
                    DOCUMENT_RESTORED_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_DOCUMENT_ID" -w "%{http_code}")
                    DOCUMENT_RESTORED_STATUS="${DOCUMENT_RESTORED_RESPONSE: -3}"
                    DOCUMENT_RESTORED_BODY="${DOCUMENT_RESTORED_RESPONSE%???}"
                    
                    test_result "復元後ドキュメント確認" "200" "$DOCUMENT_RESTORED_STATUS" "$DOCUMENT_RESTORED_BODY"
                    
                    # 3.8 最終的にドキュメントを完全削除
                    echo "3.8 テストドキュメント完全削除"
                    curl -s -u "$AUTH" -X DELETE "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_DOCUMENT_ID" > /dev/null || true
                fi
            else
                echo "❌ アーカイブ確認: $TEST_DOCUMENT_NAME がアーカイブに見つからない"
                FAILED_TESTS=$((FAILED_TESTS + 1))
            fi
            TOTAL_TESTS=$((TOTAL_TESTS + 1))
        fi
        
        rm -f "$TEST_DIR/test_document_id.txt"
    else
        echo "❌ ドキュメントID取得失敗"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
else
    echo "⚠️  ドキュメント作成失敗により以降のアーカイブテストをスキップ"
fi

echo ""
echo "=== 4. Permission/ACL 完全サイクルテスト ==="

# 4.1 テストフォルダ作成
echo "4.1 テストフォルダ作成"
TEST_FOLDER_NAME="TestFolder_$TIMESTAMP"

# AtomPub Bindingでフォルダ作成（Browser BindingはcreateFolder未対応）
FOLDER_CREATE_XML="<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<atom:entry xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:cmis=\"http://docs.oasis-open.org/ns/cmis/core/200908/\" xmlns:cmisra=\"http://docs.oasis-open.org/ns/cmis/restatom/200908/\">
  <atom:title>$TEST_FOLDER_NAME</atom:title>
  <cmisra:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId=\"cmis:objectTypeId\">
        <cmis:value>cmis:folder</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId=\"cmis:name\">
        <cmis:value>$TEST_FOLDER_NAME</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmisra:object>
</atom:entry>"

FOLDER_CREATE_RESPONSE=$(curl -s -u "$AUTH" -X POST \
  -H "Content-Type: application/atom+xml" \
  -d "$FOLDER_CREATE_XML" \
  "$BASE_URL/atom/$REPO_ID/children?id=$ROOT_FOLDER_ID" -w "%{http_code}")

FOLDER_CREATE_STATUS="${FOLDER_CREATE_RESPONSE: -3}"
FOLDER_CREATE_BODY="${FOLDER_CREATE_RESPONSE%???}"

if test_result "テストフォルダ作成" "201" "$FOLDER_CREATE_STATUS" "$FOLDER_CREATE_BODY"; then
    # AtomPub XMLレスポンスからフォルダIDを抽出
    TEST_FOLDER_ID=$(echo "$FOLDER_CREATE_BODY" | grep -o '<cmis:value>[^<]*</cmis:value>' | head -1 | sed 's/<cmis:value>//;s/<\/cmis:value>//' 2>/dev/null || echo "")
    
    if [ -n "$TEST_FOLDER_ID" ] && [ "$TEST_FOLDER_ID" != "null" ]; then
        echo "$TEST_FOLDER_ID" > "$TEST_DIR/test_folder_id.txt"
        echo "   作成フォルダID: $TEST_FOLDER_ID"
        
        # 4.2 初期ACL取得
        echo "4.2 初期ACL取得"
        ACL_GET_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/node/$TEST_FOLDER_ID/acl" -w "%{http_code}")
        ACL_GET_STATUS="${ACL_GET_RESPONSE: -3}"
        ACL_GET_BODY="${ACL_GET_RESPONSE%???}"
        
        test_result "初期ACL取得" "200" "$ACL_GET_STATUS" "$ACL_GET_BODY"
        
        # 4.3 ACL設定（testユーザーに読み取り権限追加）
        echo "4.3 ACL設定（テストユーザー権限追加）"
        ACL_JSON='{
            "permissions": [
                {"principalId": "admin", "permissions": ["cmis:all"], "direct": true},
                {"principalId": "testuser", "permissions": ["cmis:read"], "direct": true}
            ]
        }'
        
        ACL_SET_RESPONSE=$(curl -s -u "$AUTH" -X POST \
          -H "Content-Type: application/json" \
          -d "$ACL_JSON" \
          "$BASE_URL/rest/repo/$REPO_ID/node/$TEST_FOLDER_ID/acl" -w "%{http_code}")
        
        ACL_SET_STATUS="${ACL_SET_RESPONSE: -3}"
        ACL_SET_BODY="${ACL_SET_RESPONSE%???}"
        
        test_result "ACL設定" "200" "$ACL_SET_STATUS" "$ACL_SET_BODY"
        
        # 4.4 設定後ACL確認
        echo "4.4 設定後ACL確認"
        ACL_VERIFY_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/node/$TEST_FOLDER_ID/acl" -w "%{http_code}")
        ACL_VERIFY_STATUS="${ACL_VERIFY_RESPONSE: -3}"
        ACL_VERIFY_BODY="${ACL_VERIFY_RESPONSE%???}"
        
        if test_result "設定後ACL取得" "200" "$ACL_VERIFY_STATUS" "$ACL_VERIFY_BODY"; then
            if echo "$ACL_VERIFY_BODY" | grep -q "testuser"; then
                echo "✅ ACL設定確認: testuser権限が設定済み"
                PASSED_TESTS=$((PASSED_TESTS + 1))
            else
                echo "❌ ACL設定確認: testuser権限が設定されていない"
                FAILED_TESTS=$((FAILED_TESTS + 1))
            fi
            TOTAL_TESTS=$((TOTAL_TESTS + 1))
        fi
        
        # 4.5 ACLリセット（元に戻す）
        echo "4.5 ACLリセット"
        ACL_RESET_JSON='{
            "permissions": [
                {"principalId": "admin", "permissions": ["cmis:all"], "direct": true}
            ]
        }'
        
        ACL_RESET_RESPONSE=$(curl -s -u "$AUTH" -X POST \
          -H "Content-Type: application/json" \
          -d "$ACL_RESET_JSON" \
          "$BASE_URL/rest/repo/$REPO_ID/node/$TEST_FOLDER_ID/acl" -w "%{http_code}")
        
        ACL_RESET_STATUS="${ACL_RESET_RESPONSE: -3}"
        ACL_RESET_BODY="${ACL_RESET_RESPONSE%???}"
        
        test_result "ACLリセット" "200" "$ACL_RESET_STATUS" "$ACL_RESET_BODY"
        
        # 4.6 リセット後確認
        echo "4.6 リセット後確認"
        ACL_FINAL_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/node/$TEST_FOLDER_ID/acl" -w "%{http_code}")
        ACL_FINAL_STATUS="${ACL_FINAL_RESPONSE: -3}"
        ACL_FINAL_BODY="${ACL_FINAL_RESPONSE%???}"
        
        if test_result "リセット後ACL取得" "200" "$ACL_FINAL_STATUS" "$ACL_FINAL_BODY"; then
            if echo "$ACL_FINAL_BODY" | grep -q "testuser"; then
                echo "❌ ACLリセット確認: testuser権限がまだ存在（リセット失敗）"
                FAILED_TESTS=$((FAILED_TESTS + 1))
            else
                echo "✅ ACLリセット確認: testuser権限が削除済み"
                PASSED_TESTS=$((PASSED_TESTS + 1))
            fi
            TOTAL_TESTS=$((TOTAL_TESTS + 1))
        fi
        
        # 4.7 テストフォルダ削除
        echo "4.7 テストフォルダ削除"
        curl -s -u "$AUTH" -X DELETE "$BASE_URL/atom/$REPO_ID/entry?id=$TEST_FOLDER_ID" > /dev/null || true
        
        rm -f "$TEST_DIR/test_folder_id.txt"
    else
        echo "❌ フォルダID取得失敗"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
    fi
else
    echo "⚠️  フォルダ作成失敗により以降のACLテストをスキップ"
fi

echo ""
echo "=== 5. Type管理 完全サイクルテスト ==="

# 5.1 Type一覧取得
echo "5.1 Type一覧取得"
TYPE_LIST_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/type/list" -w "%{http_code}")
TYPE_LIST_STATUS="${TYPE_LIST_RESPONSE: -3}"
TYPE_LIST_BODY="${TYPE_LIST_RESPONSE%???}"

test_result "Type一覧取得" "200" "$TYPE_LIST_STATUS" "$TYPE_LIST_BODY"

# 5.2 個別Type詳細取得
echo "5.2 標準Type詳細取得（cmis:document）"
TYPE_SHOW_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/type/show/cmis:document" -w "%{http_code}")
TYPE_SHOW_STATUS="${TYPE_SHOW_RESPONSE: -3}"
TYPE_SHOW_BODY="${TYPE_SHOW_RESPONSE%???}"

test_result "Type詳細取得" "200" "$TYPE_SHOW_STATUS" "$TYPE_SHOW_BODY"

# 5.3 存在しないType詳細取得（エラーハンドリング確認）
echo "5.3 存在しないType詳細取得（エラーテスト）"
TYPE_INVALID_RESPONSE=$(curl -s -u "$AUTH" "$BASE_URL/rest/repo/$REPO_ID/type/show/nonexistent:type" -w "%{http_code}")
TYPE_INVALID_STATUS="${TYPE_INVALID_RESPONSE: -3}"
TYPE_INVALID_BODY="${TYPE_INVALID_RESPONSE%???}"

# 404 または 500 のいずれかを期待（実装による）
if [ "$TYPE_INVALID_STATUS" = "404" ] || [ "$TYPE_INVALID_STATUS" = "500" ]; then
    echo "✅ 存在しないType取得: HTTP $TYPE_INVALID_STATUS (適切なエラーレスポンス)"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo "❌ 存在しないType取得: HTTP $TYPE_INVALID_STATUS (期待値: 404 or 500)"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
TOTAL_TESTS=$((TOTAL_TESTS + 1))

echo ""
echo "=== 最終クリーンアップ ==="
cleanup_test_data

echo ""
echo "=== テスト結果サマリー ==="
echo "総テスト数: $TOTAL_TESTS"
echo "成功: $PASSED_TESTS"
echo "失敗: $FAILED_TESTS"
if command -v bc >/dev/null 2>&1; then
    SUCCESS_RATE=$(echo "scale=1; $PASSED_TESTS * 100 / $TOTAL_TESTS" | bc)
else
    # bcがない場合は整数計算
    SUCCESS_RATE=$(( $PASSED_TESTS * 100 / $TOTAL_TESTS ))
fi
echo "成功率: ${SUCCESS_RATE}%"

echo ""
echo "テスト完了時刻: $(date)"

# テスト結果をJSONで保存
cat << EOF > "$TEST_DIR/complete_test_results.json"
{
  "timestamp": "$(date -Iseconds)",
  "overall_result": "$([ $FAILED_TESTS -eq 0 ] && echo "SUCCESS" || echo "PARTIAL_FAILURE")",
  "total_tests": $TOTAL_TESTS,
  "passed_tests": $PASSED_TESTS,
  "failed_tests": $FAILED_TESTS,
  "success_rate": "${SUCCESS_RATE}%"
}
EOF

if [ $FAILED_TESTS -eq 0 ]; then
    echo "🎉 全テストが成功しました！完全サイクルテストは正常に完了しました。"
    exit 0
else
    echo "⚠️  一部のテストが失敗しました。詳細を確認してください。"
    exit 1
fi