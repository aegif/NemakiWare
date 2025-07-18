#!/bin/bash

# ExtractingRequestHandler クイック検証スクリプト
# 最小限のテストで動作確認

set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== ExtractingRequestHandler クイック検証 ===${NC}"

# Java環境設定
export JAVA_HOME="/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

cd /Users/ishiiakinori/NemakiWare

# Solr起動確認
if ! curl -s "http://localhost:8983/solr/admin/cores" > /dev/null 2>&1; then
    echo -e "${BLUE}Solrを起動中...${NC}"
    cd solr-9.8.0
    bin/solr start -p 8983 -s ../solr/solr/ -m 1g > /dev/null 2>&1
    echo "Solr起動待機中..."
    sleep 15
    cd ..
fi

# ExtractingRequestHandler確認
echo -e "${BLUE}ExtractingRequestHandler確認中...${NC}"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8983/solr/nemaki/update/extract?commit=true")
if [ "$STATUS" = "200" ]; then
    echo -e "${GREEN}✅ ExtractingRequestHandler: OK${NC}"
else
    echo -e "${RED}❌ ExtractingRequestHandler: Failed (HTTP $STATUS)${NC}"
    exit 1
fi

# 簡単なテストファイル処理
echo -e "${BLUE}文書処理テスト...${NC}"
echo "Hello ExtractingRequestHandler Quick Test!" > /tmp/quick-test.txt

RESPONSE=$(curl -s -X POST -H "Content-Type: text/plain" --data-binary @/tmp/quick-test.txt \
    "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=quick-test&literal.id=quick-test&commit=true")

if echo "$RESPONSE" | grep -q '"status":0'; then
    echo -e "${GREEN}✅ 文書処理: OK${NC}"
else
    echo -e "${RED}❌ 文書処理: Failed${NC}"
    exit 1
fi

# 検索テスト
sleep 2
echo -e "${BLUE}全文検索テスト...${NC}"
SEARCH_RESULT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=content:Quick&fl=object_id&rows=1" | jq -r '.response.numFound' 2>/dev/null)
if [ "$SEARCH_RESULT" -ge 1 ]; then
    echo -e "${GREEN}✅ 全文検索: OK (ヒット数: $SEARCH_RESULT)${NC}"
else
    echo -e "${RED}❌ 全文検索: Failed (ヒット数: $SEARCH_RESULT)${NC}"
    exit 1
fi

# クリーンアップ
rm -f /tmp/quick-test.txt

echo ""
echo -e "${GREEN}🎉 ExtractingRequestHandler クイック検証完了！${NC}"
echo -e "${BLUE}Solr管理画面: http://localhost:8983/solr/${NC}"
echo ""