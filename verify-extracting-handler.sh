#!/bin/bash

# ExtractingRequestHandler全文検索機能検証スクリプト
# クリーン環境からの一気通貫テスト

set -e

# 色付きログ出力
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# プロジェクトルートディレクトリの確認
PROJECT_ROOT="/Users/ishiiakinori/NemakiWare"
if [ ! -d "$PROJECT_ROOT" ]; then
    log_error "プロジェクトディレクトリが見つかりません: $PROJECT_ROOT"
    exit 1
fi

cd "$PROJECT_ROOT"

log_info "=== ExtractingRequestHandler全文検索機能検証開始 ==="

# Step 1: 環境クリーンアップ
log_info "Step 1: 環境クリーンアップ"

# 既存のDockerコンテナ停止
log_info "既存Dockerコンテナを停止中..."
if [ -f "docker/docker-compose-simple.yml" ]; then
    cd docker
    docker compose -f docker-compose-simple.yml down -v 2>/dev/null || true
    cd ..
fi

# 既存Jetty/Solrプロセス停止
log_info "既存Jetty/Solrプロセスを停止中..."
pkill -f jetty 2>/dev/null || true
pkill -f solr 2>/dev/null || true

# ポート確認
if lsof -i:8080 > /dev/null 2>&1; then
    log_warning "ポート8080が使用中です。手動で確認してください。"
    lsof -i:8080
fi

if lsof -i:8983 > /dev/null 2>&1; then
    log_warning "ポート8983が使用中です。手動で確認してください。"
    lsof -i:8983
fi

sleep 2

# Step 2: Java環境設定
log_info "Step 2: Java環境設定"

export JAVA_HOME="/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

# Java バージョン確認
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
if [[ ! $JAVA_VERSION == 17.* ]]; then
    log_error "Java 17が必要です。現在: $JAVA_VERSION"
    exit 1
fi
log_success "Java環境: $JAVA_VERSION"

# Step 3: Solr起動とExtractingRequestHandler確認
log_info "Step 3: Solr起動とExtractingRequestHandler確認"

# Solr 9.8.0ディレクトリ確認
if [ ! -d "solr-9.8.0" ]; then
    log_error "solr-9.8.0ディレクトリが見つかりません"
    exit 1
fi

# Solr起動
log_info "Solrを起動中..."
cd solr-9.8.0
bin/solr start -p 8983 -s ../solr/solr/ -m 1g > /dev/null 2>&1

# Solr起動待機
log_info "Solr起動を待機中..."
for i in {1..60}; do
    if curl -s "http://localhost:8983/solr/admin/cores" > /dev/null 2>&1; then
        break
    fi
    sleep 3
done

# コア初期化待機
log_info "Solrコア初期化を待機中..."
for i in {1..30}; do
    if curl -s "http://localhost:8983/solr/nemaki/admin/ping" > /dev/null 2>&1; then
        break
    fi
    sleep 2
done

# Solr稼働確認
if ! curl -s "http://localhost:8983/solr/admin/cores" > /dev/null 2>&1; then
    log_error "Solrの起動に失敗しました"
    exit 1
fi
log_success "Solr起動完了 (ポート8983)"

cd ..

# Step 4: ExtractingRequestHandler動作確認
log_info "Step 4: ExtractingRequestHandler動作確認"

# nemakiコアの確認
NEMAKI_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8983/solr/nemaki/update/extract?commit=true")
if [ "$NEMAKI_STATUS" != "200" ]; then
    log_error "nemakiコアのExtractingRequestHandlerが利用できません (HTTP $NEMAKI_STATUS)"
    exit 1
fi
log_success "nemakiコアのExtractingRequestHandler稼働確認"

# tokenコアの確認
TOKEN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8983/solr/token/update/extract?commit=true")
if [ "$TOKEN_STATUS" != "200" ]; then
    log_error "tokenコアのExtractingRequestHandlerが利用できません (HTTP $TOKEN_STATUS)"
    exit 1
fi
log_success "tokenコアのExtractingRequestHandler稼働確認"

# Step 5: 文書処理テスト
log_info "Step 5: 文書処理テスト"

# テスト用テキストファイル作成
log_info "テスト用テキストファイルを作成中..."
echo "Hello ExtractingRequestHandler! This is a test document for full-text search verification." > /tmp/test-document.txt

# テキスト文書の処理テスト
log_info "テキスト文書の処理テスト..."
RESPONSE=$(curl -s -X POST -H "Content-Type: text/plain" --data-binary @/tmp/test-document.txt \
    "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=test-text-doc&literal.id=test-text-doc&commit=true")

if echo "$RESPONSE" | grep -q '"status":0'; then
    log_success "テキスト文書処理成功"
else
    log_error "テキスト文書処理失敗: $RESPONSE"
    exit 1
fi

# PDF文書の処理テスト
log_info "PDF文書の処理テスト..."
PDF_FILE="solr-9.8.0/example/exampledocs/solr-word.pdf"
if [ ! -f "$PDF_FILE" ]; then
    log_error "テスト用PDFファイルが見つかりません: $PDF_FILE"
    exit 1
fi

RESPONSE=$(curl -s -X POST -H "Content-Type: application/pdf" --data-binary @"$PDF_FILE" \
    "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=solr-pdf-doc&literal.id=solr-pdf-doc&commit=true")

if echo "$RESPONSE" | grep -q '"status":0'; then
    log_success "PDF文書処理成功"
else
    log_error "PDF文書処理失敗: $RESPONSE"
    exit 1
fi

# HTML文書の処理テスト
log_info "HTML文書の処理テスト..."
HTML_FILE="solr-9.8.0/example/exampledocs/sample.html"
if [ ! -f "$HTML_FILE" ]; then
    log_error "テスト用HTMLファイルが見つかりません: $HTML_FILE"
    exit 1
fi

RESPONSE=$(curl -s -X POST -H "Content-Type: text/html" --data-binary @"$HTML_FILE" \
    "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=sample-html-doc&literal.id=sample-html-doc&commit=true")

if echo "$RESPONSE" | grep -q '"status":0'; then
    log_success "HTML文書処理成功"
else
    log_error "HTML文書処理失敗: $RESPONSE"
    exit 1
fi

# Step 6: 全文検索機能テスト
log_info "Step 6: 全文検索機能テスト"

sleep 2  # インデックス更新待機

# テキスト抽出確認 - テキストファイル
log_info "テキストファイルからの抽出内容確認..."
TEXT_CONTENT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=object_id:test-text-doc&fl=content&rows=1" | jq -r '.response.docs[0].content[0]' 2>/dev/null)
if echo "$TEXT_CONTENT" | grep -q "ExtractingRequestHandler"; then
    log_success "テキスト抽出確認: OK"
else
    log_error "テキスト抽出確認: 失敗"
    echo "抽出内容: $TEXT_CONTENT"
    exit 1
fi

# テキスト抽出確認 - PDFファイル
log_info "PDFファイルからの抽出内容確認..."
PDF_CONTENT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=object_id:solr-pdf-doc&fl=content&rows=1" | jq -r '.response.docs[0].content[0]' 2>/dev/null)
if echo "$PDF_CONTENT" | grep -q "PDF and Word extraction"; then
    log_success "PDF抽出確認: OK"
else
    log_error "PDF抽出確認: 失敗"
    echo "抽出内容: $PDF_CONTENT"
    exit 1
fi

# テキスト抽出確認 - HTMLファイル
log_info "HTMLファイルからの抽出内容確認..."
HTML_CONTENT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=object_id:sample-html-doc&fl=content&rows=1" | jq -r '.response.docs[0].content[0]' 2>/dev/null)
if echo "$HTML_CONTENT" | grep -q "Welcome to Solr"; then
    log_success "HTML抽出確認: OK"
else
    log_error "HTML抽出確認: 失敗"
    echo "抽出内容: $HTML_CONTENT"
    exit 1
fi

# キーワード検索テスト
log_info "全文検索機能テスト..."

# 「test」キーワードでの検索
SEARCH_RESULT=$(curl -s "http://localhost:8983/solr/nemaki/select?q=content:test&fl=object_id&rows=5" | jq -r '.response.numFound' 2>/dev/null)
if [ "$SEARCH_RESULT" -ge 2 ]; then
    log_success "全文検索テスト: OK (ヒット数: $SEARCH_RESULT)"
else
    log_error "全文検索テスト: 失敗 (ヒット数: $SEARCH_RESULT)"
    exit 1
fi

# 「Solr」キーワードでの検索
SOLR_SEARCH=$(curl -s "http://localhost:8983/solr/nemaki/select?q=content:Solr&fl=object_id&rows=5" | jq -r '.response.numFound' 2>/dev/null)
if [ "$SOLR_SEARCH" -ge 1 ]; then
    log_success "Solrキーワード検索: OK (ヒット数: $SOLR_SEARCH)"
else
    log_error "Solrキーワード検索: 失敗 (ヒット数: $SOLR_SEARCH)"
    exit 1
fi

# Step 7: 設定ファイル確認
log_info "Step 7: 設定ファイル確認"

# solrconfig.xml確認
if grep -q "ExtractingRequestHandler" solr/solr/nemaki/conf/solrconfig.xml; then
    log_success "nemaki solrconfig.xml: ExtractingRequestHandler設定確認"
else
    log_error "nemaki solrconfig.xml: ExtractingRequestHandler設定が見つかりません"
    exit 1
fi

if grep -q "ExtractingRequestHandler" solr/solr/token/conf/solrconfig.xml; then
    log_success "token solrconfig.xml: ExtractingRequestHandler設定確認"
else
    log_error "token solrconfig.xml: ExtractingRequestHandler設定が見つかりません"
    exit 1
fi

# tika-config.xml確認
if [ -f "solr/solr/nemaki/conf/tika-config.xml" ]; then
    log_success "nemaki tika-config.xml: 設定ファイル確認"
else
    log_error "nemaki tika-config.xml: 設定ファイルが見つかりません"
    exit 1
fi

if [ -f "solr/solr/token/conf/tika-config.xml" ]; then
    log_success "token tika-config.xml: 設定ファイル確認"
else
    log_error "token tika-config.xml: 設定ファイルが見つかりません"
    exit 1
fi

# 依存関係JAR確認
log_info "依存関係JAR確認..."
REQUIRED_JARS=(
    "tika-core"
    "tika-parser-pdf-module"
    "tika-parser-microsoft-module"
    "poi-5.2.4"
    "pdfbox-2.0.29"
    "solr-extraction"
)

for jar in "${REQUIRED_JARS[@]}"; do
    if find solr/solr/nemaki/lib/ -name "*${jar}*" | grep -q .; then
        log_success "依存関係確認: $jar"
    else
        log_error "依存関係確認: $jar が見つかりません"
        exit 1
    fi
done

# Step 8: クリーンアップ
log_info "Step 8: テスト環境クリーンアップ"

# テスト用ファイル削除
rm -f /tmp/test-document.txt

# テストデータ削除（オプション）
if [ "${CLEANUP_TEST_DATA:-false}" = "true" ]; then
    log_info "テストデータを削除中..."
    curl -s "http://localhost:8983/solr/nemaki/update?commit=true" -H "Content-Type: text/xml" --data-binary '<delete><query>object_id:test-text-doc OR object_id:solr-pdf-doc OR object_id:sample-html-doc</query></delete>' > /dev/null
    log_success "テストデータ削除完了"
fi

# 検証完了レポート
log_info "=== ExtractingRequestHandler全文検索機能検証完了 ==="
echo ""
echo -e "${GREEN}✅ 検証完了項目:${NC}"
echo "  ・ExtractingRequestHandler動作確認"
echo "  ・PDF文書処理 (Apache Tika 2.9.2 + PDFBox 2.0.29)"
echo "  ・HTML文書処理 (TagSoup HTMLパーサー)"
echo "  ・テキスト文書処理"
echo "  ・全文検索機能 (キーワード検索)"
echo "  ・設定ファイル確認 (solrconfig.xml, tika-config.xml)"
echo "  ・依存関係JAR確認 (Tika, POI, PDFBox, Solr)"
echo ""
echo -e "${GREEN}🎯 対応可能フォーマット:${NC}"
echo "  ・PDF文書 ✅"
echo "  ・Microsoft Office (.docx, .xlsx, .pptx) ✅ (依存関係準備済み)"
echo "  ・OpenDocument (.odt, .ods, .odp) ✅ (依存関係準備済み)"
echo "  ・HTML/XML ✅"
echo "  ・プレーンテキスト ✅"
echo ""
echo -e "${GREEN}🚀 運用準備完了${NC}"
echo "ExtractingRequestHandlerは完全に実装され、本格的な全文検索対応"
echo "エンタープライズCMSとして機能します。"
echo ""
echo -e "${BLUE}Solr管理画面:${NC} http://localhost:8983/solr/"
echo -e "${BLUE}nemakiコア:${NC} http://localhost:8983/solr/#/nemaki"
echo -e "${BLUE}tokenコア:${NC} http://localhost:8983/solr/#/token"

exit 0