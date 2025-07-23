#!/bin/bash

# NemakiWare 包括的テストスクリプト
# CMIS、クエリ、RESTサービスの動作確認

set -e

BASE_URL="http://localhost:8080/core"
AUTH="admin:admin"

echo "=== NemakiWare 包括的テスト開始 ==="
echo "テスト対象: $BASE_URL"
echo

# 1. CMIS基本機能テスト
echo "1. CMIS基本機能テスト"
echo "------------------------"

# CMISリポジトリ情報
echo -n "CMISリポジトリ情報: "
CMIS_REPO_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $BASE_URL/atom/bedroom)
if [ "$CMIS_REPO_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $CMIS_REPO_STATUS)"
else
    echo "✗ FAILED (HTTP $CMIS_REPO_STATUS)"
fi

# CMISブラウザバインディング
echo -n "CMISブラウザバインディング: "
CMIS_BROWSER_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $BASE_URL/browser/bedroom)
if [ "$CMIS_BROWSER_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $CMIS_BROWSER_STATUS)"
else
    echo "✗ FAILED (HTTP $CMIS_BROWSER_STATUS)"
fi

# CMISルートフォルダ
echo -n "CMISルートフォルダ: "
ROOT_FOLDER_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" "$BASE_URL/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff")
if [ "$ROOT_FOLDER_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $ROOT_FOLDER_STATUS)"
else
    echo "✗ FAILED (HTTP $ROOT_FOLDER_STATUS)"
fi

echo

# 2. RESTサービステスト
echo "2. RESTサービステスト"
echo "------------------------"

# リポジトリ一覧
echo -n "リポジトリ一覧: "
REPO_LIST_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $BASE_URL/rest/all/repositories)
if [ "$REPO_LIST_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $REPO_LIST_STATUS)"
    REPO_LIST=$(curl -s -u $AUTH $BASE_URL/rest/all/repositories)
    echo "  リポジトリ: $REPO_LIST"
else
    echo "✗ FAILED (HTTP $REPO_LIST_STATUS)"
fi

# RESTテストエンドポイント
echo -n "RESTテストエンドポイント: "
REST_TEST_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $BASE_URL/rest/all/repositories/test)
if [ "$REST_TEST_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $REST_TEST_STATUS)"
    REST_TEST_RESPONSE=$(curl -s -u $AUTH $BASE_URL/rest/all/repositories/test)
    echo "  レスポンス: $REST_TEST_RESPONSE"
else
    echo "✗ FAILED (HTTP $REST_TEST_STATUS)"
fi

# Solr検索エンジン
echo -n "Solr検索エンジンURL: "
SOLR_URL_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $BASE_URL/rest/repo/bedroom/search-engine/url)
if [ "$SOLR_URL_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $SOLR_URL_STATUS)"
    SOLR_URL_RESPONSE=$(curl -s -u $AUTH $BASE_URL/rest/repo/bedroom/search-engine/url)
    echo "  Solr URL: $SOLR_URL_RESPONSE"
else
    echo "✗ FAILED (HTTP $SOLR_URL_STATUS)"
fi

# Solr初期化
echo -n "Solr初期化エンドポイント: "
SOLR_INIT_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $BASE_URL/rest/repo/bedroom/search-engine/init)
if [ "$SOLR_INIT_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $SOLR_INIT_STATUS)"
else
    echo "✗ FAILED (HTTP $SOLR_INIT_STATUS)"
fi

echo

# 3. CMISクエリテスト
echo "3. CMISクエリテスト" 
echo "------------------------"

# 基本クエリ
echo -n "基本ドキュメントクエリ: "
QUERY_DOCS_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" "$BASE_URL/atom/bedroom/query?q=SELECT+*+FROM+cmis:document&maxItems=5")
if [ "$QUERY_DOCS_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $QUERY_DOCS_STATUS)"
else
    echo "✗ FAILED (HTTP $QUERY_DOCS_STATUS)"
fi

# フォルダクエリ
echo -n "基本フォルダクエリ: "
QUERY_FOLDERS_STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" "$BASE_URL/atom/bedroom/query?q=SELECT+*+FROM+cmis:folder&maxItems=5")
if [ "$QUERY_FOLDERS_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $QUERY_FOLDERS_STATUS)"
else
    echo "✗ FAILED (HTTP $QUERY_FOLDERS_STATUS)"
fi

echo

# 4. Webサービスバインディングテスト
echo "4. Webサービスバインディングテスト"
echo "------------------------"

echo -n "CMISウェブサービス: "
WS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/services)
if [ "$WS_STATUS" = "200" ]; then
    echo "✓ OK (HTTP $WS_STATUS)"
else
    echo "✗ FAILED (HTTP $WS_STATUS)"
fi

echo

# テスト結果サマリ
echo "=== テスト結果サマリ ==="
TOTAL_TESTS=9
PASSED_TESTS=0

# パスしたテストをカウント（簡易版）
if [ "$CMIS_REPO_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$CMIS_BROWSER_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$ROOT_FOLDER_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$REPO_LIST_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$REST_TEST_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$SOLR_URL_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$SOLR_INIT_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$QUERY_DOCS_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi
if [ "$QUERY_FOLDERS_STATUS" = "200" ]; then ((PASSED_TESTS++)); fi

echo "合格テスト: $PASSED_TESTS/$TOTAL_TESTS"

if [ $PASSED_TESTS -eq $TOTAL_TESTS ]; then
    echo "🎉 全テスト合格！NemakiWareは正常に動作しています。"
    exit 0
else
    echo "⚠️  一部テストが失敗しました。詳細を確認してください。"
    exit 1
fi