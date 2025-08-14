# NemakiWare CMIS TCK Execution Guide

本ガイドでは、NemakiWare Jakarta EE 10開発環境でのCMIS TCK（Test Compatibility Kit）実行方法を詳しく説明します。

## 概要

NemakiWareには包括的なCMIS 1.1 TCK実行環境が整備されており、以下の機能を提供します：

- **7つの主要テストグループ**: Basics, Control, CRUD, Filing, Query, Types, Versioning
- **詳細レポート生成**: テキスト、XML、HTML形式のレポート
- **選択的実行**: 特定のテストグループのみ実行可能
- **Jakarta EE 10対応**: Jetty 11環境での完全動作
- **MockQueryProcessor**: Solr無効化での開発フレンドリーな環境

## 前提条件

### 1. Jetty開発サーバーの起動

```bash
cd /Users/ishiiakinori/NemakiWare/core
./start-jetty-dev.sh
```

**確認方法:**
```bash
curl -u admin:admin http://localhost:8081/core/atom/bedroom
# 期待: HTTP 200とXML応答
```

### 2. CouchDBの動作確認

```bash
curl -u admin:password http://localhost:5984/
# 期待: {"couchdb":"Welcome","version":"3.x.x",...}
```

## TCK実行方法

### 1. 全テストグループ実行

**自動スクリプト使用:**
```bash
cd /Users/ishiiakinori/NemakiWare/core
./run-tck-comprehensive.sh
```

**Maven直接実行:**
```bash
mvn exec:java@run-jetty-tck -Pjakarta
```

### 2. 選択的テストグループ実行

**自動スクリプト使用:**
```bash
# 単一グループ
./run-tck-selective.sh query

# 複数グループ
./run-tck-selective.sh basics crud query
```

**Maven直接実行:**
```bash
# 単一グループ
mvn exec:java@run-jetty-tck -Pjakarta -Dexec.args="query"

# 複数グループ
mvn exec:java@run-jetty-tck -Pjakarta -Dexec.args="basics crud query"
```

### 3. クエリ検証テスト実行

```bash
mvn exec:java@run-query-validation -Pjakarta
```

## 利用可能なテストグループ

| グループ名 | 説明 | 主要テスト内容 |
|-----------|------|---------------|
| `basics` | 基本機能テスト | リポジトリ情報、セキュリティ、ルートフォルダ |
| `control` | アクセス制御テスト | ACL、権限、継承 |
| `crud` | CRUD操作テスト | 作成、読取、更新、削除操作 |
| `filing` | ファイリングテスト | ファイリング、アンファイリング、マルチファイリング |
| `query` | クエリテスト | CMIS SQLクエリ、検索機能 |
| `types` | タイプシステムテスト | タイプ定義、プロパティ |
| `versioning` | バージョニングテスト | バージョン管理、チェックイン/アウト |

## レポート出力

### レポート格納場所

```bash
/tmp/tck-reports/jetty-[タイムスタンプ]/
```

### 生成されるレポートファイル

- **`summary-report.txt`**: 実行サマリー
- **`[グループ名]-report.txt`**: 各テストグループの詳細レポート

### レポート例

```text
=================================================================
NEMAKIWARE CMIS TCK EXECUTION SUMMARY
=================================================================
Generated: Mon Jul 07 21:12:09 JST 2025
Environment: Jakarta EE 10 Development (Jetty)
Repository: bedroom (localhost:8081)

OVERALL RESULTS:
-----------------------------------------------------------------
Test Groups Executed: 7
Test Groups Passed: 5
Test Groups Failed: 2
Group Success Rate: 71%

Total Tests Executed: 45
Total Test Failures: 8
Test Success Rate: 82%
Total Execution Time: 12.5 seconds
```

## 実行例

### 1. クエリテストのみ実行

```bash
$ ./run-tck-selective.sh query

=== NemakiWare Selective TCK Test Runner ===
Jakarta EE 10 Development Environment

✓ Jetty server is running and accessible
Selected test groups: query

MAVEN_OPTS configured for Java 17 module system
✓ Test classes compiled successfully

Executing selective TCK tests...
=== NemakiWare Jetty TCK Runner ===
Starting selective TCK execution...
Selected groups: query
============================================================

Running Test Group: QueryTestGroup
----------------------------------------
Starting test group: QueryTestGroup
  Running test: Query Smoke Test (ATOMPUB)
  Running test: Query Basic Folder Test (ATOMPUB)
Completed test group: QueryTestGroup
✓ QueryTestGroup completed successfully
  Tests: 12, Failures: 2, Time: 3.45s
  Report: /tmp/tck-reports/jetty-20250707-211509/querytestgroup-report.txt

============================================================
Selective TCK Execution Summary:
Selected Test Groups: 1
Passed Groups: 1
Failed Groups: 0
Total Tests: 12
Total Failures: 2
Success Rate: 83%
Execution Time: 3.5 seconds

✅ Selective TCK execution completed successfully!
```

### 2. 複数グループ実行

```bash
$ ./run-tck-selective.sh basics crud

Selected test groups: basics crud

Running Test Group: BasicsTestGroup
----------------------------------------
✓ BasicsTestGroup completed successfully
  Tests: 3, Failures: 0, Time: 1.2s

Running Test Group: CrudTestGroup  
----------------------------------------
✓ CrudTestGroup completed successfully
  Tests: 18, Failures: 1, Time: 5.8s

============================================================
Total Tests: 21
Total Failures: 1
Success Rate: 95%
```

## トラブルシューティング

### 1. 接続エラー

**症状:** `Connection refused` エラー

**原因:** Jettyサーバーが起動していない

**対処法:**
```bash
cd /Users/ishiiakinori/NemakiWare/core
./start-jetty-dev.sh
```

### 2. 認証エラー

**症状:** `401 Unauthorized` エラー

**原因:** 認証情報の不一致

**対処法:** 設定ファイルで認証情報を確認
```bash
# cmis-tck-parameters.properties
org.apache.chemistry.opencmis.user=admin
org.apache.chemistry.opencmis.password=admin
```

### 3. コンパイルエラー

**症状:** Maven test-compile失敗

**対処法:**
```bash
# Java 17モジュールシステム設定
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

mvn clean test-compile -Pjakarta
```

### 4. MockQueryProcessor関連の問題

**症状:** Solr関連エラー

**確認:** MockQueryProcessorが正しく設定されているか確認
```bash
# Jettyサーバーログでこのメッセージを確認
"MockQueryProcessor initialized - Solr disabled for development"
```

## 高度な使用方法

### 1. カスタム設定での実行

独自のTCK設定ファイルを作成:

```bash
cp src/test/resources/cmis-tck-parameters.properties src/test/resources/cmis-tck-parameters-custom.properties
# 設定をカスタマイズ
```

TestGroupBaseクラスでパラメータファイルを変更してカスタム設定を使用可能。

### 2. CI/CD統合

Jenkins、GitHub Actions等での自動TCK実行:

```yaml
- name: Run CMIS TCK Tests
  run: |
    cd core
    ./start-jetty-dev.sh &
    sleep 30
    ./run-tck-comprehensive.sh
    pkill -f jetty
```

### 3. パフォーマンス測定

TCK実行時間とリソース使用量の監視:

```bash
time ./run-tck-comprehensive.sh
# レポートファイルで詳細実行時間確認
```

## まとめ

NemakiWareのTCK実行環境は以下の特徴を持ちます：

- **包括性**: CMIS 1.1の全主要機能をテスト
- **柔軟性**: 選択的実行とカスタマイズ可能
- **開発フレンドリー**: Solr無効化、詳細レポート
- **Jakarta EE 10対応**: 最新の仕様に準拠
- **自動化対応**: スクリプト化とCI/CD統合可能

このガイドに従って、NemakiWareのCMIS適合性を継続的に検証し、品質を維持することができます。