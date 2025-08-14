#!/bin/bash

# 新しく実装したREST APIエンドポイントの詳細テスト

echo "=== 新規REST APIエンドポイント詳細テスト ==="
echo

BASE_URL="http://localhost:8080/core"
AUTH="admin:admin"

echo "1. 既存のエンドポイントテスト（動作確認）"
echo "Type一覧:"
curl -s -u "$AUTH" "$BASE_URL/rest/repo/bedroom/type/list" | jq '.status // "JSON形式エラー"' 2>/dev/null || echo "JSONパースエラー"

echo -e "\nUser一覧:"
curl -s -u "$AUTH" "$BASE_URL/rest/repo/bedroom/user/list" | jq '.status // "JSON形式エラー"' 2>/dev/null || echo "JSONパースエラー"

echo -e "\nArchive一覧:"
curl -s -u "$AUTH" "$BASE_URL/rest/repo/bedroom/archive/index" | jq '.status // "JSON形式エラー"' 2>/dev/null || echo "JSONパースエラー"

echo -e "\n2. 新規エンドポイントテスト"

echo -e "\n2.1 Type詳細表示（新規実装）:"
curl -s -u "$AUTH" "$BASE_URL/rest/repo/bedroom/type/show/cmis:document" | head -100

echo -e "\n2.2 UserItemResource JSON更新（新規実装）:"
curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"userId":"testuser","userName":"Test User JSON Updated","admin":false}' \
  -u "$AUTH" "$BASE_URL/rest/repo/bedroom/user/update-json/testuser" | head -100

echo -e "\n2.3 PermissionResource ACL取得（新規実装）:"
curl -s -u "$AUTH" "$BASE_URL/rest/repo/bedroom/node/e02f784f8360a02cc14d1314c10038ff/acl" | head -100

echo -e "\n3. Jersey REST Application設定確認"
echo "Jersey REST Application:"
curl -s -u "$AUTH" "$BASE_URL/rest/" | head -20

echo -e "\n4. 404エラーレスポンス詳細分析"
echo "404エラーの詳細ヘッダー:"
curl -I -u "$AUTH" "$BASE_URL/rest/repo/bedroom/node/e02f784f8360a02cc14d1314c10038ff/acl" 2>/dev/null | head -10

echo -e "\n=== テスト完了 ==="