#!/bin/bash

# UI機能のエンドツーエンドテスト - AtomPub bindingを使用した実際のCRUD操作検証
# Test React UI functionality with actual CRUD operations using AtomPub binding

set -e

echo "=== NemakiWare UI CRUD エンドツーエンドテスト (AtomPub) ==="
echo "Testing React UI backend APIs with AtomPub binding CRUD operations..."
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

# 認証エンドポイントテスト
echo
echo "=== 認証システムテスト ==="
run_test "リポジトリ一覧取得" "curl -s -u admin:admin http://localhost:8080/core/rest/all/repositories | grep -o 'bedroom'" "bedroom"

# CMIS AtomPub Binding テスト（UIが使用するAPI）
echo
echo "=== CMIS AtomPub Binding APIテスト ==="
run_test "リポジトリ情報取得" "curl -s -u admin:admin http://localhost:8080/core/atom/bedroom | grep -o 'repositoryId'" "repositoryId"
run_test "ルートフォルダ子要素取得" "curl -s -u admin:admin 'http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff' | grep -o 'atom:entry'" "atom:entry"

# 実際のCRUD操作テスト（AtomPub binding使用）
echo
echo "=== 実際のCRUD操作テスト（AtomPub）==="

# テスト用の一意なファイル名を生成
TIMESTAMP=$(date +%s)
TEST_FOLDER_NAME="ui-test-folder-$TIMESTAMP"
TEST_DOC_NAME="ui-test-document-$TIMESTAMP.txt"
ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

# CREATE操作: フォルダ作成
echo -n "✓ フォルダ作成テスト（AtomPub）: "
TOTAL_TESTS=$((TOTAL_TESTS + 1))

cat << EOF > /tmp/create_folder.xml
<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>$TEST_FOLDER_NAME</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
                <cmis:value>cmis:folder</cmis:value>
            </cmis:propertyId>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>$TEST_FOLDER_NAME</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
</atom:entry>
EOF

FOLDER_RESULT=$(curl -s -u admin:admin -H "Content-Type: application/atom+xml" -d @/tmp/create_folder.xml "http://localhost:8080/core/atom/bedroom/children?id=$ROOT_FOLDER_ID")

if echo "$FOLDER_RESULT" | grep -q "cmis:objectId"; then
    FOLDER_ID=$(echo "$FOLDER_RESULT" | grep -o '<cmis:value>[^<]*</cmis:value>' | head -1 | sed 's/<cmis:value>//;s/<\/cmis:value>//')
    echo "OK (ID: $FOLDER_ID)"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo "FAILED"
    echo "Error response: $FOLDER_RESULT" | head -3
fi

# CREATE操作: ドキュメント作成
if [ ! -z "$FOLDER_ID" ]; then
    echo -n "✓ ドキュメント作成テスト（AtomPub）: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    cat << EOF > /tmp/create_document.xml
<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>$TEST_DOC_NAME</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
                <cmis:value>cmis:document</cmis:value>
            </cmis:propertyId>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>$TEST_DOC_NAME</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
    <cmisra:content>
        <cmisra:mediatype>text/plain</cmisra:mediatype>
        <cmisra:base64>VUljg4njgrnjg4jnlKjjg4njgq3jg6Ljg6Hjg7Pjg4jjga7lhoXlrrkK</cmisra:base64>
    </cmisra:content>
</atom:entry>
EOF

    DOC_RESULT=$(curl -s -u admin:admin -H "Content-Type: application/atom+xml" -d @/tmp/create_document.xml "http://localhost:8080/core/atom/bedroom/children?id=$FOLDER_ID")

    if echo "$DOC_RESULT" | grep -q "cmis:objectId"; then
        DOC_ID=$(echo "$DOC_RESULT" | grep -o '<cmis:value>[^<]*</cmis:value>' | head -1 | sed 's/<cmis:value>//;s/<\/cmis:value>//')
        echo "OK (ID: $DOC_ID)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
        echo "Error response: $DOC_RESULT" | head -3
    fi
fi

# READ操作: オブジェクト取得
if [ ! -z "$DOC_ID" ]; then
    echo -n "✓ ドキュメント読み取りテスト（AtomPub）: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    READ_RESULT=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/entry?id=$DOC_ID")
    
    if echo "$READ_RESULT" | grep -q "$TEST_DOC_NAME"; then
        echo "OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
    fi
fi

# UPDATE操作: プロパティ更新
if [ ! -z "$DOC_ID" ]; then
    echo -n "✓ ドキュメント更新テスト（AtomPub）: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    UPDATED_NAME="$TEST_DOC_NAME-updated"

    cat << EOF > /tmp/update_document.xml
<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <atom:title>$UPDATED_NAME</atom:title>
    <cmisra:object>
        <cmis:properties>
            <cmis:propertyString propertyDefinitionId="cmis:name">
                <cmis:value>$UPDATED_NAME</cmis:value>
            </cmis:propertyString>
        </cmis:properties>
    </cmisra:object>
</atom:entry>
EOF

    UPDATE_RESULT=$(curl -s -u admin:admin -X PUT -H "Content-Type: application/atom+xml" -d @/tmp/update_document.xml "http://localhost:8080/core/atom/bedroom/entry?id=$DOC_ID")

    if echo "$UPDATE_RESULT" | grep -q "$UPDATED_NAME"; then
        echo "OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "FAILED"
        echo "Error response: $UPDATE_RESULT" | head -3
    fi
fi

# DELETE操作: オブジェクト削除
if [ ! -z "$DOC_ID" ]; then
    echo -n "✓ ドキュメント削除テスト（AtomPub）: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    DELETE_RESULT=$(curl -s -u admin:admin -X DELETE "http://localhost:8080/core/atom/bedroom/entry?id=$DOC_ID")

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
    echo -n "✓ フォルダ削除テスト（AtomPub）: "
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    FOLDER_DELETE_RESULT=$(curl -s -u admin:admin -X DELETE "http://localhost:8080/core/atom/bedroom/entry?id=$FOLDER_ID")

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

# バッチ操作テスト（複数オブジェクト取得）
run_test "フォルダ子要素一覧取得" "curl -s -u admin:admin 'http://localhost:8080/core/atom/bedroom/children?id=$ROOT_FOLDER_ID&maxItems=10' | grep -o 'atom:entry' | wc -l | awk '{if(\$1>0) print \"OK\"}'" "OK"

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

# CMIS Query パフォーマンステスト
echo -n "✓ CMIS Query レスポンス時間: "
TOTAL_TESTS=$((TOTAL_TESTS + 1))
START_TIME=$(date +%s%N)
curl -s -u admin:admin 'http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis:document&maxItems=10' > /dev/null
END_TIME=$(date +%s%N)
QUERY_TIME=$(( (END_TIME - START_TIME) / 1000000 ))

if [ $QUERY_TIME -lt 5000 ]; then
    echo "OK (${QUERY_TIME}ms)"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo "SLOW (${QUERY_TIME}ms - Expected < 5000ms)"
fi

# テンポラリファイルのクリーンアップ
rm -f /tmp/create_folder.xml /tmp/create_document.xml /tmp/update_document.xml

# 結果表示
echo
echo "=== NemakiWare UI テスト結果 ==="
echo "合格テスト: $PASSED_TESTS / $TOTAL_TESTS"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
    echo "🎉 全テスト合格！React UIとCMISバックエンド（AtomPub）は正常に動作しています。"
    echo
    echo "=== 手動UI検証チェックリスト ==="
    echo "ブラウザで以下のUI機能を手動確認してください："
    echo "1. [ ] http://localhost:8080/core/ui/dist/ でログイン画面が表示される"
    echo "2. [ ] admin:admin でログインが成功する"
    echo "3. [ ] ドキュメント一覧が正しく表示される"
    echo "4. [ ] フォルダ作成機能が動作する"
    echo "5. [ ] ファイルアップロード機能が動作する"
    echo "6. [ ] ドキュメントのダウンロードが可能"
    echo "7. [ ] フォルダ階層のナビゲーションが機能する"
    echo "8. [ ] 検索機能が動作する"
    echo "9. [ ] ログアウト機能が正常に動作する"
    echo "10. [ ] ブラウザコンソールにエラーが無い"
    exit 0
else
    FAILED_TESTS=$((TOTAL_TESTS - PASSED_TESTS))
    echo "⚠️  $FAILED_TESTS 個のテストが失敗しました。"
    exit 1
fi