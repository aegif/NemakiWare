# Mac環境でのビルドとテスト実行ガイド

このドキュメントは、MacでNemakiWareのビルドとTCKテストを実行するための手順を説明します。

## 前提条件

### 必須ソフトウェア

1. **Java 17**
   ```bash
   # インストール確認
   /usr/libexec/java_home -v 17
   
   # 未インストールの場合、以下からダウンロード:
   # - Oracle JDK 17: https://www.oracle.com/java/technologies/downloads/#java17
   # - OpenJDK 17: https://adoptium.net/
   # - JetBrains Runtime 17: https://github.com/JetBrains/JetBrainsRuntime/releases
   ```

2. **Maven 3.6+**
   ```bash
   # Homebrewでインストール
   brew install maven
   
   # 確認
   mvn -version
   ```

3. **Node.js 20 LTS (推奨)**
   ```bash
   # Homebrewでインストール
   brew install node@20
   
   # または nvm を使用
   nvm install 20
   nvm use 20
   
   # 確認
   node -v
   npm -v
   ```

4. **Docker Desktop**
   ```bash
   # https://www.docker.com/products/docker-desktop からダウンロード
   
   # 設定:
   # - メモリ: 3GB以上推奨
   # - CPUs: 2コア以上推奨
   # - ファイル共有: プロジェクトディレクトリを追加
   
   # 確認
   docker --version
   docker compose version
   ```

5. **必須コマンドラインツール**
   ```bash
   # Homebrewでインストール
   brew install jq coreutils
   
   # jq: JSON処理用
   # coreutils: timeout コマンド用 (gtimeout として利用可能)
   ```

### ポート確認

以下のポートが空いていることを確認してください:
```bash
# ポート使用状況確認
lsof -i :8080  # Tomcat
lsof -i :5984  # CouchDB
lsof -i :8983  # Solr

# 使用中の場合は該当プロセスを停止
```

## ビルド手順

### 1. UIビルド

```bash
cd core/src/main/webapp/ui

# 依存関係インストール
npm ci

# ビルド実行
npm run build

# 成功確認
ls -la dist/
```

### 2. Coreビルド

```bash
# プロジェクトルートディレクトリで実行
mvn clean package -DskipTests -pl core -am

# 成功確認
ls -la core/target/core.war
```

### 3. Docker環境起動

```bash
cd docker

# コンテナ起動
docker compose -f docker-compose-simple.yml up -d

# 起動確認
docker compose -f docker-compose-simple.yml ps

# ヘルスチェック待機 (2-3分)
# Coreコンテナが "healthy" になるまで待つ
```

### 4. サーバー動作確認

```bash
# CMIS Browser Binding エンドポイント確認
curl -u admin:admin "http://localhost:8080/core/browser/bedroom" | jq '.repositoryId'

# 期待される出力: "bedroom"
```

## TCKテスト実行

### 包括的TCKテスト

```bash
# プロジェクトルートディレクトリで実行
./tck-test-clean.sh

# 実行時間: 5-40分 (テスト範囲による)
```

### 特定テストグループの実行

```bash
# 例: Queryテストグループのみ実行
./tck-test-clean.sh QueryTestGroup

# 例: 特定テストメソッドのみ実行
./tck-test-clean.sh QueryTestGroup#queryLikeTest
```

### テスト結果確認

```bash
# テストレポート確認
open core/target/surefire-reports/index.html

# または
cat core/target/surefire-reports/*.txt
```

## Playwrightテスト実行

```bash
cd core/src/main/webapp/ui

# Playwright依存関係インストール
npm ci
npx playwright install

# Mac では install-deps は不要 (Linux のみ)

# テスト実行
npm run test

# 特定テストのみ実行
npm run test -- tests/permissions/acl-inheritance-breaking.spec.ts --project=chromium
```

## トラブルシューティング

### Apple Silicon (M1/M2) での注意点

一部のDockerイメージがARM64に対応していない場合、`docker-compose-simple.yml`に以下を追加:

```yaml
services:
  solr:
    platform: linux/amd64  # エミュレーション使用
    # ... 他の設定
  
  core:
    platform: linux/amd64  # 必要に応じて
    # ... 他の設定
```

**注意**: エミュレーションはパフォーマンスに影響します。

### timeout コマンドが見つからない

Macでは `timeout` の代わりに `gtimeout` を使用:

```bash
# coreutils がインストールされていることを確認
brew install coreutils

# gtimeout が利用可能か確認
which gtimeout
```

`tck-test-clean.sh` は自動的に `timeout` を使用しますが、見つからない場合は手動で修正が必要です。

### Docker Desktop メモリ不足

TCKテストは3GB以上のJavaヒープを使用します:

1. Docker Desktop を開く
2. Settings → Resources → Memory
3. 最低4GB、推奨6GB以上に設定
4. Apply & Restart

### ポート競合

```bash
# 使用中のポートを確認
lsof -i :8080
lsof -i :5984
lsof -i :8983

# プロセスを停止
kill -9 <PID>

# または環境変数でポート変更 (docker-compose.yml に対応が必要)
```

## 既知の問題

### Solr権限エラー

TCKテストでSolr関連のクエリテストが失敗する場合があります:

```
SolrCore 'nemaki' is not available due to init failure: 
java.nio.file.AccessDeniedException: /var/solr/data/nemaki/data
```

**対処法**:
```bash
# Solrコンテナの権限修正
docker compose -f docker/docker-compose-simple.yml down
sudo rm -rf docker/solr/solr/nemaki/data
docker compose -f docker/docker-compose-simple.yml up -d
```

これはインフラストラクチャの問題であり、CMIS規格準拠には影響しません。

## 参考情報

- README.md: プロジェクト全体の概要
- CLAUDE.md: 詳細な開発ガイド
- core/src/main/webapp/ui/README.md: UI開発ガイド
