#!/bin/bash

# UI機能のエンドツーエンドテスト - 実際のCRUD操作を検証
# Test React UI functionality with actual CRUD operations

set -e

echo "=== NemakiWare UI CRUD エンドツーエンドテスト ==="
echo "Testing React UI with actual CRUD operations..."
echo

# テスト結果カウンタ
PASSED_TESTS=0
TOTAL_TESTS=0

# テスト実行関数
run_test() {
    local test_name="$1"
    local test_command="$2"
    local expected_result="$3"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "✓ $test_name: "
    
    if result=$(eval "$test_command" 2>/dev/null); then
        if [[ "$result" == *"$expected_result"* ]] || [[ "$expected_result" == "200" && "$result" == "200" ]]; then
            echo "OK"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "FAILED (Expected: $expected_result, Got: $result)"
        fi
    else
        echo "FAILED (Command failed)"
    fi
}

# 基本UIアクセステスト
echo "=== 基本UIアクセステスト ==="
run_test "React UIアクセス" "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/dist/" "200"
run_test "UI静的ファイル配信" "curl -s http://localhost:8080/core/ui/dist/ | grep -o 'src=\"[^\"]*\"' | head -1" "src="

#認証エンドポイントテスト
echo
echo "=== 認証システムテスト ==="
run_test "認証トークン取得" "curl -s -X POST -u admin:admin http://localhost:8080/core/rest/auth/token | grep -o 'token'" "token"
run_test "リポジトリ一覧取得" "curl -s -u admin:admin http://localhost:8080/core/rest/all/repositories | grep -o 'bedroom'" "bedroom"

# CMIS Browser Binding テスト（UI が使用するAPI）
echo
echo "=== CMIS Browser Binding APIテスト ==="
run_test "リポジトリ情報取得" "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo' | grep -o 'productName'" "productName"
run_test "ルートフォルダ子要素取得" "curl -s -u admin:admin 'http://localhost:8080/core/browser/bedroom/root?cmisselector=children' | grep -o 'objects'" "objects"

# 実際のCRUD操作テスト
echo
echo "=== 実際のCRUD操作テスト ==="

# テスト用の一意なファイル名を生成
TIMESTAMP=$(date +%s)
TEST_FOLDER_NAME="ui-test-folder-$TIMESTAMP"
TEST_DOC_NAME="ui-test-document-$TIMESTAMP.txt"
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

# CREATE操作: フォルダ作成
echo -n "✓ フォルダ作成テスト: "
TOTAL_TESTS=$((TOTAL_TESTS + 1))
FOLDER_RESULT=$(curl -s -u admin:admin -X POST \
  -F "cmisaction=createFolder" \
  -F "folderId=$ROOT_FOLDER_ID" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:folder" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=$TEST_FOLDER_NAME" \
  "http://localhost:8080/core/browser/bedroom")

if echo "$FOLDER_RESULT" | grep -q "objectId"; then
    FOLDER_ID=$(echo "$FOLDER_RESULT" | grep -o '"cmis:objectId":{"value":"[^"]*"' | sed 's/.*"value":"\([^"]*\)".*/\1/')
    echo "OK (ID: $FOLDER_ID)"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo "FAILED"
    echo "Error response: $FOLDER_RESULT"
fi

# CREATE操作: ドキュメント作成
if [ ! -z "$FOLDER_ID" ]; then
    echo -n "✓ ドキュメント作成テスト: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    DOC_RESULT=$(curl -s -u admin:admin -X POST \
      -F "cmisaction=createDocument" \
      -F "folderId=$FOLDER_ID" \
      -F "propertyId[0]=cmis:objectTypeId" \
      -F "propertyValue[0]=cmis:document" \
      -F "propertyId[1]=cmis:name" \
      -F "propertyValue[1]=$TEST_DOC_NAME" \
      -F "content=UIテスト用ドキュメントの内容" \
      "http://localhost:8080/core/browser/bedroom")

    if echo "$DOC_RESULT" | grep -q "objectId"; then
        DOC_ID=$(echo "$DOC_RESULT" | grep -o '"cmis:objectId":{"value":"[^"]*"' | sed 's/.*"value":"\([^"]*\)".*/\1/')
        echo "OK (ID: $DOC_ID)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
        echo "Error response: $DOC_RESULT"
    fi
fi

# READ操作: オブジェクト取得
if [ ! -z "$DOC_ID" ]; then
    echo -n "✓ ドキュメント読み取りテスト: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    READ_RESULT=$(curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/root/$DOC_ID")
    
    if echo "$READ_RESULT" | grep -q "$TEST_DOC_NAME"; then
        echo "OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
    fi
fi

# UPDATE操作: プロパティ更新
if [ ! -z "$DOC_ID" ]; then
    echo -n "✓ ドキュメント更新テスト: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    UPDATED_NAME="$TEST_DOC_NAME-updated"
    UPDATE_RESULT=$(curl -s -u admin:admin -X POST \
      -F "cmisaction=update" \
      -F "objectId=$DOC_ID" \
      -F "propertyId[0]=cmis:name" \
      -F "propertyValue[0]=$UPDATED_NAME" \
      "http://localhost:8080/core/browser/bedroom")

    if echo "$UPDATE_RESULT" | grep -q "$UPDATED_NAME"; then
        echo "OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
        echo "Error response: $UPDATE_RESULT"
    fi
fi

# DELETE操作: オブジェクト削除
if [ ! -z "$DOC_ID" ]; then
    echo -n "✓ ドキュメント削除テスト: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    DELETE_RESULT=$(curl -s -u admin:admin -X POST \
      -F "cmisaction=delete" \
      -F "objectId=$DOC_ID" \
      "http://localhost:8080/core/browser/bedroom")

    # 削除成功の場合、レスポンスが空またはエラーが無い
    if [ -z "$DELETE_RESULT" ] || ! echo "$DELETE_RESULT" | grep -q "exception"; then
        echo "OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
        echo "Error response: $DELETE_RESULT"
    fi
fi

# フォルダも削除
if [ ! -z "$FOLDER_ID" ]; then
    echo -n "✓ フォルダ削除テスト: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    FOLDER_DELETE_RESULT=$(curl -s -u admin:admin -X POST \
      -F "cmisaction=delete" \
      -F "objectId=$FOLDER_ID" \
      "http://localhost:8080/core/browser/bedroom")

    if [ -z "$FOLDER_DELETE_RESULT" ] || ! echo "$FOLDER_DELETE_RESULT" | grep -q "exception"; then
        echo "OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
        echo "Error response: $FOLDER_DELETE_RESULT"
    fi
fi

# UI固有機能のテスト
echo
echo "=== UI固有機能テスト ==="

# Sites フォルダ（パッチシステムで作成）の存在確認
run_test "Sitesフォルダ存在確認" "curl -s -u admin:admin 'http://localhost:8080/core/atom/bedroom/path?path=%2FSites' | grep -o 'Sites'" "Sites"

# 検索機能テスト（UIで使用される）
run_test "基本検索機能" "curl -s -u admin:admin 'http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis:folder%20WHERE%20cmis:name%20=%20%27Sites%27' | grep -o 'Sites'" "Sites"

# ファイルアップロード機能の基盤テスト
echo -n "✓ ファイルアップロード基盤テスト: "
TOTAL_TESTS=$((TOTAL_TESTS + 1))
UPLOAD_TEST=$(curl -s -u admin:admin -X POST \
  -F "cmisaction=createDocument" \
  -F "folderId=$ROOT_FOLDER_ID" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=upload-test-$TIMESTAMP.txt" \
  -F "content=アップロードテスト内容" \
  "http://localhost:8080/core/browser/bedroom")

if echo "$UPLOAD_TEST" | grep -q "objectId"; then
    UPLOAD_ID=$(echo "$UPLOAD_TEST" | grep -o '"cmis:objectId":{"value":"[^"]*"' | sed 's/.*"value":"\([^"]*\)".*/\1/')
    echo "OK"
    PASSED_TESTS=$((PASSED_TESTS + 1))
    
    # テスト用ファイルを削除
    curl -s -u admin:admin -X POST \
      -F "cmisaction=delete" \
      -F "objectId=$UPLOAD_ID" \
      "http://localhost:8080/core/browser/bedroom" > /dev/null
else
    echo "FAILED"
fi

# パフォーマンステスト
echo
echo "=== パフォーマンステスト ==="
echo -n "✓ UI初期読み込み時間: "
TOTAL_TESTS=$((TOTAL_TESTS + 1))
START_TIME=$(date +%s%N)
curl -s -o /dev/null http://localhost:8080/core/ui/dist/
END_TIME=$(date +%s%N)
LOAD_TIME=$(( (END_TIME - START_TIME) / 1000000 ))

if [ $LOAD_TIME -lt 2000 ]; then
    echo "OK (${LOAD_TIME}ms)"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo "SLOW (${LOAD_TIME}ms - Expected < 2000ms)"
fi

# 結果表示
echo
echo "=== NemakiWare UI テスト結果 ==="
echo "合格テスト: $PASSED_TESTS / $TOTAL_TESTS"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
    echo "🎉 全テスト合格！React UIとCMISバックエンドは正常に動作しています。"
    exit 0
else
    FAILED_TESTS=$((TOTAL_TESTS - PASSED_TESTS))
    echo "⚠️  $FAILED_TESTS 個のテストが失敗しました。"
    exit 1
fi