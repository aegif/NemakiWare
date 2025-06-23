# NemakiWare Docker テスティングガイド

このドキュメントは、NemakiWareのDocker環境でのテスト実行方法について説明します。

## 概要

NemakiWareは以下の2つのDockerテスト環境を提供します：

1. **Simple Environment** (`docker-compose-simple.yml`) - 基本的な統合テスト用
2. **WAR Environment** (`docker-compose-war.yml`) - 本格的なテスト・デプロイメント用

## 事前準備

### 必要ソフトウェア
- Docker Desktop または Docker Engine
- Docker Compose v2+
- Java 8 (ビルド用)
- Maven 3.6+ (ビルド用)

### システム要件
- メモリ: 8GB以上推奨
- ディスク容量: 10GB以上の空き容量

## Simple Environment テスト

### 1. 基本テストの実行

```bash
# プロジェクトルートで実行
cd /path/to/NemakiWare

# Simple環境でのテスト実行
./docker/test-simple.sh
```

### 2. テスト内容
- CouchDB 2.x環境での基本動作確認
- Core CMIS API の動作確認
- UI アプリケーションの動作確認
- 基本的な認証機能の確認

### 3. 期待される結果
```
✅ Simple Environment Test Results:
- CouchDB: ✓ Running (HTTP 200)
- Core CMIS AtomPub: ✓ Working (HTTP 200) 
- Core CMIS Browser: ✓ Working (HTTP 200)
- Core CMIS Web Services: ✓ Working (HTTP 200)
- UI Login Page: ✓ Accessible (HTTP 200)

All simple environment tests passed successfully!
```

### 4. 接続確認
```bash
# CouchDB確認
curl -u admin:password http://localhost:5984/_all_dbs

# Core API確認
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# UI確認（ブラウザで）
open http://localhost:9000/ui/login?repositoryId=bedroom
```

## WAR Environment テスト

### 1. 包括的テストの実行

```bash
# WAR環境での包括的テスト実行
./docker/test-war.sh
```

### 2. テスト内容
- CouchDB 2.x と 3.x 両環境での動作確認
- Core、UI、Solr の完全統合テスト
- リポジトリ初期化とデータ投入テスト
- 変数置換とプロパティ設定の確認
- クロスバージョン互換性テスト

### 3. 期待される結果
```
✅ WAR Environment Test Results:
Environment 2 (CouchDB 2.x):
- CouchDB: ✓ Running (HTTP 200)
- Core CMIS AtomPub: ✓ Working (HTTP 200)
- Core CMIS Browser: ✓ Working (HTTP 200) 
- Core CMIS Web Services: ✓ Working (HTTP 200)
- UI Login Page: ✓ Accessible (HTTP 200)
- Solr Search: ✓ Working (HTTP 200)

Environment 3 (CouchDB 3.x):
- CouchDB: ✓ Running (HTTP 200)
- Core CMIS AtomPub: ✓ Working (HTTP 200)
- Core CMIS Browser: ✓ Working (HTTP 200)
- Core CMIS Web Services: ✓ Working (HTTP 200)  
- UI Login Page: ✓ Accessible (HTTP 200)
- Solr Search: ✓ Working (HTTP 200)

All WAR environment tests passed successfully!
```

### 4. 高度な接続確認
```bash
# 環境2 (CouchDB 2.x)
curl -u admin:password http://localhost:5984/_all_dbs
curl -u admin:admin http://localhost:8080/core/atom/bedroom
curl http://localhost:8983/solr/nemaki/admin/ping

# 環境3 (CouchDB 3.x)  
curl -u admin:password http://localhost:5985/_all_dbs
curl -u admin:admin http://localhost:8081/core/atom/bedroom
curl http://localhost:8984/solr/nemaki/admin/ping
```

## 個別コンポーネントテスト

### CouchDBテスト
```bash
# データベース一覧確認
curl -u admin:password http://localhost:5984/_all_dbs

# 特定リポジトリ確認
curl -u admin:password http://localhost:5984/bedroom
curl -u admin:password http://localhost:5984/canopy

# デザインドキュメント確認
curl -u admin:password http://localhost:5984/bedroom/_design/_repo
```

### Core API テスト
```bash
# CMIS リポジトリ情報
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# CMIS クエリテスト
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT%20*%20FROM%20cmis:document&maxItems=5"

# ルートフォルダ取得
curl -u admin:admin http://localhost:8080/core/atom/bedroom/children
```

### UI テスト
```bash
# ログインページ
curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui/login?repositoryId=bedroom

# 認証後のアクセス（ブラウザで）
open http://localhost:9000/ui/repo/bedroom/login
```

## トラブルシューティング

### 一般的な問題

#### 1. コンテナ起動失敗
```bash
# ログ確認
docker compose -f docker-compose-simple.yml logs

# 特定サービスのログ
docker compose -f docker-compose-simple.yml logs core

# 全コンテナ再起動
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d
```

#### 2. CouchDB接続エラー
```bash
# CouchDB健全性確認
curl -u admin:password http://localhost:5984/

# データベース存在確認
curl -u admin:password http://localhost:5984/_all_dbs

# 認証情報確認
echo "admin:password" | base64
```

#### 3. Core API エラー
```bash
# Core起動状態確認
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core

# Tomcatログ確認
docker exec docker-core-1 cat /usr/local/tomcat/logs/catalina.out

# 設定ファイル確認
docker exec docker-core-1 cat /usr/local/tomcat/shared/classes/app-server-core.properties
```

#### 4. UI接続エラー
```bash
# UI起動確認
curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui

# UI設定確認
docker exec docker-ui-1 grep "nemaki.core.uri" /usr/local/tomcat/webapps/ui/WEB-INF/classes/application.conf

# PlayFrameworkログ確認
docker logs docker-ui-1
```

### リセット手順
```bash
# 完全環境リセット
docker compose -f docker-compose-simple.yml down -v
docker compose -f docker-compose-war.yml down -v

# イメージ再ビルド
docker compose -f docker-compose-simple.yml build --no-cache

# 再起動
docker compose -f docker-compose-simple.yml up -d
```

## パフォーマンステスト

### 基本的な負荷テスト
```bash
# CMIS API並行アクセステスト
for i in {1..10}; do
  curl -u admin:admin http://localhost:8080/core/atom/bedroom &
done
wait

# UI同時アクセステスト
for i in {1..5}; do
  curl http://localhost:9000/ui/login?repositoryId=bedroom &
done
wait
```

### メモリ・CPU使用量確認
```bash
# コンテナリソース使用量
docker stats

# 特定コンテナの詳細
docker stats docker-core-1 docker-ui-1 docker-couchdb-1
```

## ベンチマーク

### 期待パフォーマンス
- **Core API応答時間**: < 200ms (単一リクエスト)
- **UI初回ロード**: < 3秒
- **CouchDB クエリ**: < 100ms (基本クエリ)
- **Solr検索**: < 500ms (基本検索)

### 測定方法
```bash
# API応答時間測定
time curl -u admin:admin http://localhost:8080/core/atom/bedroom

# 詳細時間測定
curl -u admin:admin -w "@curl-format.txt" -o /dev/null -s http://localhost:8080/core/atom/bedroom
```

## 継続的インテグレーション

### CI/CD パイプラインでの使用
```yaml
# GitHub Actions例
- name: Run Docker Tests
  run: |
    ./docker/test-simple.sh
    ./docker/test-war.sh

- name: Check Test Results
  run: |
    # テスト結果の確認とレポート生成
    docker compose -f docker-compose-simple.yml logs > test-logs.txt
```

## サポート

問題が発生した場合は、以下の情報を含めてissueを作成してください：

1. 実行環境 (OS、Docker バージョン)
2. 実行コマンド
3. エラーメッセージ
4. ログファイル (`docker compose logs`)
5. `docker ps` の出力

---

更新日: 2024年6月23日