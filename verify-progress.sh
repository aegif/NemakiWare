#!/bin/bash

# 進捗確認スクリプト - 修正された問題と残った問題の検証

echo "=========================================="
echo "NemakiWare TCK 修正進捗確認"
echo "=========================================="

CMIS_BASE="http://localhost:8080/core/atom/bedroom"
AUTH="admin:admin"

echo "✅ 1. 認証問題の修正確認..."
STATUS=$(curl -s -u $AUTH -o /dev/null -w "%{http_code}" $CMIS_BASE)
if [ "$STATUS" = "200" ]; then
    echo "   ✓ CMIS認証: 正常に動作 (HTTP $STATUS)"
else
    echo "   ✗ CMIS認証: 失敗 (HTTP $STATUS)"
    exit 1
fi

echo ""
echo "✅ 2. CMIS機能設定の確認..."
echo "  - Content Stream Updatability:"
CONTENT_STREAM=$(curl -s -u $AUTH $CMIS_BASE | xmllint --format - | grep "capabilityContentStreamUpdatability" | sed 's/.*>\(.*\)<.*/\1/')
echo "    設定値: $CONTENT_STREAM (期待値: anytime)"

echo "  - Multifiling Support:"
MULTIFILING=$(curl -s -u $AUTH $CMIS_BASE | xmllint --format - | grep "capabilityMultifiling" | sed 's/.*>\(.*\)<.*/\1/')
echo "    設定値: $MULTIFILING (期待値: false)"

echo "  - Query Support:"
QUERY=$(curl -s -u $AUTH $CMIS_BASE | xmllint --format - | grep "capabilityQuery" | sed 's/.*>\(.*\)<.*/\1/')
echo "    設定値: $QUERY (期待値: bothcombined)"

echo ""
echo "🔄 3. 残っている問題のテスト..."

echo "  - 空のリポジトリでのクエリ実行:"
QUERY_RESULT=$(curl -s -u $AUTH "$CMIS_BASE/query?q=SELECT+*+FROM+cmis:folder" | grep "numItems" | sed 's/.*>\(.*\)<.*/\1/')
echo "    結果: $QUERY_RESULT items (期待値: 空でもエラーにならない)"

echo "  - ドキュメントタイプのcontentStreamAllowed設定:"
DOC_CONTENT_STREAM=$(curl -s -u $AUTH "$CMIS_BASE/type?id=cmis:document" | xmllint --format - | grep "contentStreamAllowed" | sed 's/.*>\(.*\)<.*/\1/')
echo "    現在値: $DOC_CONTENT_STREAM (期待値: allowed)"

if [ "$DOC_CONTENT_STREAM" = "required" ]; then
    echo "    📝 問題: contentStreamAllowedがまだrequiredのまま"
    echo "      解決方法: runtime設定反映またはソースコード修正が必要"
fi

echo ""
echo "🧪 4. 基本的なCMIS操作のテスト..."

echo "  - フォルダ作成テスト:"
FOLDER_RESULT=$(curl -s -u $AUTH -X POST \
  -H "Content-Type: application/atom+xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
  <atom:title>test-folder</atom:title>
  <cmisra:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:folder</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>test-folder</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmisra:object>
</atom:entry>' \
  "$CMIS_BASE/children?id=e02f784f8360a02cc14d1314c10038ff")

if echo "$FOLDER_RESULT" | grep -q "test-folder"; then
    echo "    ✓ フォルダ作成: 成功"
    
    # Extract folder ID for cleanup
    FOLDER_ID=$(echo "$FOLDER_RESULT" | xmllint --format - | grep "cmis:objectId" | head -1 | sed 's/.*>\(.*\)<.*/\1/')
    
    echo "  - フォルダ削除テスト:"
    DELETE_RESULT=$(curl -s -u $AUTH -X DELETE "$CMIS_BASE/id?id=$FOLDER_ID")
    if [ $? -eq 0 ]; then
        echo "    ✓ フォルダ削除: 成功"
    else
        echo "    ⚠ フォルダ削除: 警告"
    fi
else
    echo "    ✗ フォルダ作成: 失敗"
    echo "    レスポンス: $(echo "$FOLDER_RESULT" | head -1)"
fi

echo ""
echo "=========================================="
echo "修正進捗サマリー"
echo "=========================================="

FIXED_COUNT=0
REMAINING_COUNT=0

if [ "$STATUS" = "200" ]; then
    echo "✅ CMIS認証問題: 修正完了"
    ((FIXED_COUNT++))
fi

if [ "$CONTENT_STREAM" = "anytime" ] && [ "$MULTIFILING" = "false" ] && [ "$QUERY" = "bothcombined" ]; then
    echo "✅ CMIS機能設定: 修正完了"
    ((FIXED_COUNT++))
else
    echo "🔄 CMIS機能設定: 一部適用済み"
    ((REMAINING_COUNT++))
fi

if [ "$DOC_CONTENT_STREAM" = "allowed" ]; then
    echo "✅ ドキュメントタイプ設定: 修正完了"
    ((FIXED_COUNT++))
else
    echo "🔄 ドキュメントタイプ設定: 未解決"
    ((REMAINING_COUNT++))
fi

echo ""
echo "📊 進捗状況:"
echo "  修正完了: $FIXED_COUNT 件"
echo "  残り作業: $REMAINING_COUNT 件"

if [ $REMAINING_COUNT -eq 0 ]; then
    echo ""
    echo "🎉 すべてのTCK compliance問題が解決されました！"
    echo "   TCKテストの実行準備が整いました。"
    exit 0
else
    echo ""
    echo "⚠️  まだ解決が必要な問題があります。"
    echo "   引き続き修正作業を進めてください。"
    exit 1
fi