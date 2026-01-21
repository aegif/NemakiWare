# ExtractingRequestHandler全文検索機能検証ガイド

## 概要

本ガイドでは、NemakiWareに実装されたApache Solr ExtractingRequestHandler（Solr Cell）による全文検索機能を、クリーン環境から一気通貫で検証する手順を説明します。

## 前提条件

### 必須環境
- **Java**: Java 17 (JAVA_HOME設定済み)
- **Maven**: 3.6+ 
- **Docker**: 20.10+ (オプション、Dockerテスト用)
- **OS**: macOS/Linux
- **ネットワーク**: ポート8983が利用可能

### 事前確認項目
```bash
# Java 17確認
java -version
# 期待値: openjdk version "17.0.x"

# Maven確認
mvn -version

# ポート確認
lsof -i:8983  # 空であること
lsof -i:8080  # 空であること
```

## 実装済み機能

### コア機能
- ✅ Apache Solr ExtractingRequestHandler設定
- ✅ Apache Tika 2.9.2統合
- ✅ PDF・Office・HTML・テキスト文書処理
- ✅ 全文検索・インデックス機能
- ✅ セキュリティ設定（外部パーサー無効化）

### 対応フォーマット
- ✅ **PDF文書**: Apache PDFBox 2.0.29
- ✅ **Microsoft Office**: Apache POI 5.2.4
  - Word (.docx/.doc)
  - Excel (.xlsx/.xls) 
  - PowerPoint (.pptx/.ppt)
- ✅ **OpenDocument**: Apache Tika標準サポート
  - Writer (.odt)
  - Calc (.ods)
  - Impress (.odp)
- ✅ **Web形式**: HTML、XML、RTF
- ✅ **プレーンテキスト**: .txt

### 依存関係
```
Apache Tika 2.9.2:
├── tika-core-2.9.2.jar
├── tika-parser-pdf-module-2.9.2.jar
├── tika-parser-microsoft-module-2.9.2.jar
├── tika-parser-html-module-2.9.2.jar
└── tika-parser-text-module-2.9.2.jar

Apache POI 5.2.4:
├── poi-5.2.4.jar
├── poi-ooxml-5.2.4.jar
├── poi-ooxml-lite-5.2.4.jar
└── poi-scratchpad-5.2.4.jar

Apache PDFBox 2.0.29:
├── pdfbox-2.0.29.jar
├── fontbox-2.0.29.jar
└── jempbox-1.8.17.jar

Solr関連:
├── solr-extraction-9.8.0.jar
└── solr-with-dependencies.jar

サポートライブラリ:
├── xmlbeans-5.1.1.jar
├── commons-compress-1.24.0.jar
├── tagsoup-1.2.1.jar
├── xercesImpl-2.12.2.jar
└── serializer-2.7.3.jar
```

## 一気通貫検証手順

### Method 1: 自動検証スクリプト実行（推奨）

```bash
# プロジェクトルートに移動
cd /path/to/NemakiWare

# 検証スクリプト実行
./verify-extracting-handler.sh
```

**実行時間**: 約3-5分

**検証内容**:
1. 環境クリーンアップ
2. Java環境設定
3. Solr起動とExtractingRequestHandler確認
4. 文書処理テスト（テキスト・PDF・HTML）
5. 全文検索機能テスト
6. 設定ファイル確認
7. 依存関係JAR確認
8. 完了レポート出力

### Method 2: 手動検証手順

#### Step 1: 環境準備
```bash
cd /path/to/NemakiWare

# Java環境設定
export JAVA_HOME="/path/to/java-17"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"

# 既存プロセス停止
pkill -f jetty 2>/dev/null || true
pkill -f solr 2>/dev/null || true
```

#### Step 2: Solr起動
```bash
# Solr 9.8.0起動
cd solr-9.8.0
bin/solr start -p 8983 -s ../solr/solr/ -m 1g

# 起動確認
curl -s "http://localhost:8983/solr/admin/cores"
cd ..
```

#### Step 3: ExtractingRequestHandler動作確認
```bash
# nemakiコア確認
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8983/solr/nemaki/update/extract?commit=true"
# 期待値: 200

# tokenコア確認  
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8983/solr/token/update/extract?commit=true"
# 期待値: 200
```

#### Step 4: 文書処理テスト
```bash
# テキスト文書テスト
echo "Hello ExtractingRequestHandler Test!" > /tmp/test.txt
curl -X POST -H "Content-Type: text/plain" --data-binary @/tmp/test.txt \
  "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=test-doc&literal.id=test-doc&commit=true"

# PDF文書テスト
curl -X POST -H "Content-Type: application/pdf" --data-binary @solr-9.8.0/example/exampledocs/solr-word.pdf \
  "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=pdf-doc&literal.id=pdf-doc&commit=true"

# HTML文書テスト  
curl -X POST -H "Content-Type: text/html" --data-binary @solr-9.8.0/example/exampledocs/sample.html \
  "http://localhost:8983/solr/nemaki/update/extract?literal.repository_id=bedroom&literal.object_id=html-doc&literal.id=html-doc&commit=true"
```

#### Step 5: 全文検索テスト
```bash
# 全文検索実行
curl -s "http://localhost:8983/solr/nemaki/select?q=content:test&fl=object_id,repository_id&rows=5"

# 抽出内容確認
curl -s "http://localhost:8983/solr/nemaki/select?q=object_id:pdf-doc&fl=content&rows=1" | jq -r '.response.docs[0].content[0]'
```

## 設定ファイル詳細

### solrconfig.xml設定

**場所**: 
- `solr/solr/nemaki/conf/solrconfig.xml`
- `solr/solr/token/conf/solrconfig.xml`

**キー設定**:
```xml
<requestHandler name="/update/extract" 
                startup="lazy"
                class="solr.extraction.ExtractingRequestHandler">
  <lst name="defaults">
    <str name="lowernames">true</str>
    <str name="uprefix">ignored_</str>
    <str name="fmap.content">content</str>
    <str name="tika.config">tika-config.xml</str>
  </lst>
</requestHandler>
```

### tika-config.xml設定

**場所**:
- `solr/solr/nemaki/conf/tika-config.xml`
- `solr/solr/token/conf/tika-config.xml`

**セキュリティ設定**:
```xml
<properties>
  <service-loader initializableProblemHandler="ignore" loadErrorHandler="IGNORE"/>
  
  <parsers>
    <parser class="org.apache.tika.parser.CompositeParser">
      <!-- 内部パーサーのみ許可 -->
      <parser class="org.apache.tika.parser.pdf.PDFParser"/>
      <parser class="org.apache.tika.parser.microsoft.OfficeParser"/>
      <parser class="org.apache.tika.parser.microsoft.ooxml.OOXMLParser"/>
      <!-- 外部パーサーは無効化 -->
    </parser>
  </parsers>
</properties>
```

## トラブルシューティング

### よくある問題

#### 1. ポート競合
```bash
# 問題: ポート8983が使用中
# 解決方法:
lsof -i:8983
kill -9 <PID>
```

#### 2. Java環境問題
```bash
# 問題: Java 17以外を使用
# 解決方法:
export JAVA_HOME="/path/to/java-17"
java -version  # 確認
```

#### 3. ClassNotFoundException
```bash
# 問題: Tika/POI依存関係不足
# 解決方法:
find solr/solr/nemaki/lib/ -name "*tika*" | wc -l  # 5以上であること
find solr/solr/nemaki/lib/ -name "*poi*" | wc -l   # 4以上であること
```

#### 4. ExtractingRequestHandler利用不可
```bash
# 問題: HTTP 404/500エラー
# 解決方法:
# 1. solrconfig.xml確認
grep -A 10 "ExtractingRequestHandler" solr/solr/nemaki/conf/solrconfig.xml

# 2. コア再読み込み
curl "http://localhost:8983/solr/admin/cores?action=RELOAD&core=nemaki"
```

### ログ確認方法

```bash
# Solr管理画面でログ確認
# http://localhost:8983/solr/#/~logging

# コマンドラインでのログ確認
tail -f solr-9.8.0/server/logs/solr.log | grep -E "(ERROR|WARN|ExtractingRequestHandler)"
```

## 成功時の期待値

### 検証スクリプト実行成功例
```
[INFO] === ExtractingRequestHandler全文検索機能検証開始 ===
[SUCCESS] Java環境: 17.0.12
[SUCCESS] Solr起動完了 (ポート8983)
[SUCCESS] nemakiコアのExtractingRequestHandler稼働確認
[SUCCESS] tokenコアのExtractingRequestHandler稼働確認
[SUCCESS] テキスト文書処理成功
[SUCCESS] PDF文書処理成功
[SUCCESS] HTML文書処理成功
[SUCCESS] テキスト抽出確認: OK
[SUCCESS] PDF抽出確認: OK
[SUCCESS] HTML抽出確認: OK
[SUCCESS] 全文検索テスト: OK (ヒット数: 2)
[SUCCESS] Solrキーワード検索: OK (ヒット数: 1)

✅ 検証完了項目:
  ・ExtractingRequestHandler動作確認
  ・PDF文書処理 (Apache Tika 2.9.2 + PDFBox 2.0.29)
  ・HTML文書処理 (TagSoup HTMLパーサー)
  ・テキスト文書処理
  ・全文検索機能 (キーワード検索)
  ・設定ファイル確認 (solrconfig.xml, tika-config.xml)
  ・依存関係JAR確認 (Tika, POI, PDFBox, Solr)

🚀 運用準備完了
ExtractingRequestHandlerは完全に実装され、本格的な全文検索対応
エンタープライズCMSとして機能します。
```

### Solr管理画面での確認

- **Solr管理画面**: http://localhost:8983/solr/
- **nemakiコア**: http://localhost:8983/solr/#/nemaki
- **tokenコア**: http://localhost:8983/solr/#/token

**Query画面での検索例**:
```
q: content:PDF
fq: repository_id:bedroom
fl: object_id,content
```

## 次のステップ

### 本番環境デプロイ

1. **Docker環境**: `docker-compose-simple.yml`を使用
2. **Maven/Jetty環境**: Jetty開発サーバーで動作確認
3. **統合環境**: CouchDB + Solr + Core アプリケーション連携

### CMIS統合確認

ExtractingRequestHandler検証完了後は、CMIS APIとの統合確認を実施してください：

```bash
# CouchDB起動
docker run -d --name couchdb -p 5984:5984 -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=password couchdb:3.3.3

# Core アプリケーション起動（Jetty）
cd core
mvn jetty:run -Djetty.port=8080

# CMIS統合テスト
curl -u admin:admin "http://localhost:8080/core/atom/bedroom"
```

## まとめ

本ガイドにより、ExtractingRequestHandlerの全文検索機能を確実に検証できます。一気通貫の自動検証により、試行錯誤なしに実装の完成度を確認可能です。