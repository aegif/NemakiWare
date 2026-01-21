# NemakiWare インストーラーテスティングガイド

このドキュメントは、NemakiWareのIzPack 5.2.4インストーラーのテスト実行方法について説明します。

## 概要

NemakiWareインストーラーは以下の方式でテスト可能です：

1. **GUI Installation** - インタラクティブなGUIインストール
2. **Console Installation** - コンソールベースの対話型インストール  
3. **Automated Installation** - 設定ファイルによる自動インストール

## 事前準備

### 必要ソフトウェア
- Java 8 以上
- Maven 3.6+ (インストーラービルド用)
- CouchDB (実行時テスト用)

### システム要件
- メモリ: 4GB以上推奨
- ディスク容量: 2GB以上の空き容量
- ネットワーク: インターネット接続 (Maven依存関係取得用)

## インストーラーのビルド

### 1. ソースからのビルド

```bash
# プロジェクトルートから
cd setup/installer

# Maven使用でのビルド
mvn clean compile izpack:izpack

# 生成されたインストーラー確認
ls -la target/NemakiInstaller-*.jar
```

### 2. ビルド検証

```bash
# JARファイル内容確認
jar -tf target/NemakiInstaller-*.jar | head -10

# インストーラー実行可能性確認
java -jar target/NemakiInstaller-*.jar --version
```

## テスト方式

### 1. GUI インストールテスト

```bash
# GUIモードでインストーラー起動
java -jar target/NemakiInstaller-*.jar

# または
java -jar target/NemakiInstaller-*.jar -gui
```

**テスト手順:**
1. インストールパス選択（例: `/tmp/gui-install`）
2. Tomcatポート設定（例: `8080`）
3. CouchDB接続設定
   - URL: `http://localhost:5984`
   - Username: `admin`
   - Password: `password`
4. リポジトリ設定（例: `bedroom`）
5. Core URI設定
   - Host: `localhost`
   - Port: `8080`
6. インストール実行

**期待される結果:**
- 全パネルが正常に表示される
- 変数入力が適切に検証される
- ファイルが正しいパスにコピーされる
- 設定ファイルで変数置換が正常に動作する

### 2. Console インストールテスト

```bash
# コンソールモードでインストーラー起動
java -jar target/NemakiInstaller-*.jar -console
```

**テスト手順:**
1. プロンプトでインストールパス入力
2. 各設定項目を順次入力
3. 確認プロンプトで設定内容確認
4. インストール実行

**期待される結果:**
- コンソール上で適切にプロンプトが表示される
- 入力検証が正常に動作する
- GUIと同様の設定ファイル生成

### 3. Automated インストールテスト

#### 3.1 設定ファイル準備

```bash
# テスト用設定ファイル作成
cat > test-defaults.properties << 'EOF'
INSTALL_PATH=/tmp/automated-install
tomcat.port=9080
tomcat.shutdown.port=9005
db.couchdb.url=http://localhost:5984
db.couchdb.username=admin
db.couchdb.password=password
cmis.repository.main=bedroom
nemaki.core.uri.host=localhost
nemaki.core.uri.port=9080
EOF
```

#### 3.2 自動インストール実行

```bash
# 自動インストール実行
java -jar target/NemakiInstaller-*.jar \
  -defaults-file test-defaults.properties \
  -auto

# インストール結果確認
echo "Exit code: $?"
```

#### 3.3 複数設定での並行テスト

```bash
# 複数の設定ファイルでテスト
for port in 8080 9080 9090; do
  cat > test-${port}.properties << EOF
INSTALL_PATH=/tmp/install-${port}
tomcat.port=${port}
tomcat.shutdown.port=$((port - 75))
db.couchdb.url=http://localhost:5984
db.couchdb.username=admin
db.couchdb.password=password
cmis.repository.main=bedroom
nemaki.core.uri.host=localhost
nemaki.core.uri.port=${port}
EOF

  echo "Testing installation with port ${port}..."
  java -jar target/NemakiInstaller-*.jar \
    -defaults-file test-${port}.properties \
    -auto
  
  if [ $? -eq 0 ]; then
    echo "✓ Port ${port} installation successful"
  else
    echo "✗ Port ${port} installation failed"
  fi
done
```

## インストール結果の検証

### 1. ファイル構造確認

```bash
# インストールディレクトリ構造確認
find /tmp/automated-install -type f -name "*.properties" | head -5

# 重要ファイル存在確認
ls -la /tmp/automated-install/apache-tomcat-*/shared/classes/app-server-core.properties
ls -la /tmp/automated-install/apache-tomcat-*/shared/classes/app-server-core-repositories.yml
```

### 2. 変数置換検証

```bash
# Core設定ファイル確認
cat /tmp/automated-install/apache-tomcat-*/shared/classes/app-server-core.properties

# 期待される内容例:
# cmis.server.port=9080
# db.couchdb.url=http://localhost:5984
# db.couchdb.auth.username=admin
# db.couchdb.auth.password=password
```

```bash
# UI設定ファイル確認
cat /tmp/automated-install/apache-tomcat-*/shared/classes/app-server-ui.properties

# 期待される内容例:
# ui.server.port=9080
# nemaki.core.uri.host=localhost
# nemaki.core.uri.port=9080
```

```bash
# リポジトリ設定確認
cat /tmp/automated-install/apache-tomcat-*/shared/classes/app-server-core-repositories.yml

# 期待される内容例:
# repositories:
#   - id: bedroom
#     name: bedroom
#     archive: bedroom_closet
#     root: e02f784f8360a02cc14d1314c10038ff
```

### 3. 設定値検証スクリプト

```bash
#!/bin/bash
# verify-installation.sh

INSTALL_DIR="$1"
EXPECTED_PORT="$2"

if [ -z "$INSTALL_DIR" ] || [ -z "$EXPECTED_PORT" ]; then
  echo "Usage: $0 <install_dir> <expected_port>"
  exit 1
fi

echo "Verifying installation in: $INSTALL_DIR"
echo "Expected port: $EXPECTED_PORT"

# Core設定検証
CORE_PROPS="$INSTALL_DIR/apache-tomcat-*/shared/classes/app-server-core.properties"
ACTUAL_PORT=$(grep "cmis.server.port=" $CORE_PROPS | cut -d'=' -f2)

if [ "$ACTUAL_PORT" = "$EXPECTED_PORT" ]; then
  echo "✓ Core port correctly set to $EXPECTED_PORT"
else
  echo "✗ Core port mismatch: expected $EXPECTED_PORT, got $ACTUAL_PORT"
fi

# CouchDB設定検証
COUCHDB_URL=$(grep "db.couchdb.url=" $CORE_PROPS | cut -d'=' -f2-)
if [ "$COUCHDB_URL" = "http://localhost:5984" ]; then
  echo "✓ CouchDB URL correctly set"
else
  echo "✗ CouchDB URL incorrect: $COUCHDB_URL"
fi

# UI設定検証
UI_PROPS="$INSTALL_DIR/apache-tomcat-*/shared/classes/app-server-ui.properties"
if [ -f $UI_PROPS ]; then
  UI_PORT=$(grep "nemaki.core.uri.port=" $UI_PROPS | cut -d'=' -f2)
  if [ "$UI_PORT" = "$EXPECTED_PORT" ]; then
    echo "✓ UI core URI port correctly set to $EXPECTED_PORT"
  else
    echo "✗ UI core URI port mismatch: expected $EXPECTED_PORT, got $UI_PORT"
  fi
fi

echo "Verification completed."
```

```bash
# 検証スクリプト実行例
chmod +x verify-installation.sh
./verify-installation.sh /tmp/automated-install 9080
```

## 実動作テスト

### 1. CouchDB準備

```bash
# CouchDB起動（Docker使用例）
docker run -d --name couchdb-test \
  -p 5984:5984 \
  -e COUCHDB_USER=admin \
  -e COUCHDB_PASSWORD=password \
  couchdb:3.3

# CouchDB健全性確認
sleep 10
curl -u admin:password http://localhost:5984/
```

### 2. NemakiWare起動テスト

```bash
# インストール済みTomcat起動
cd /tmp/automated-install/apache-tomcat-*
./bin/startup.sh

# 起動確認
sleep 30
curl -s -o /dev/null -w "%{http_code}" http://localhost:9080/core
```

### 3. 基本機能テスト

```bash
# CMIS API テスト
curl -u admin:admin http://localhost:9080/core/atom/bedroom

# UI アクセステスト
curl -s -o /dev/null -w "%{http_code}" http://localhost:9080/ui
```

### 4. クリーンアップ

```bash
# Tomcat停止
cd /tmp/automated-install/apache-tomcat-*
./bin/shutdown.sh

# CouchDB停止・削除
docker stop couchdb-test
docker rm couchdb-test

# テストディレクトリ削除
rm -rf /tmp/automated-install /tmp/gui-install /tmp/install-*
rm -f test-*.properties
```

## トラブルシューティング

### 1. ビルドエラー

#### Maven依存関係エラー
```bash
# キャッシュクリア
mvn dependency:purge-local-repository

# 強制的な依存関係再取得
mvn clean compile izpack:izpack -U
```

#### IzPack プラグインエラー
```bash
# プラグインバージョン確認
mvn help:describe -Dplugin=org.codehaus.izpack:izpack-maven-plugin

# 詳細ログ出力
mvn clean compile izpack:izpack -X
```

### 2. インストールエラー

#### パス関連エラー
```bash
# 書き込み権限確認
ls -ld /tmp
mkdir -p /tmp/test-install && echo "✓ Write permission OK" || echo "✗ Write permission failed"

# ディスク容量確認
df -h /tmp
```

#### 変数置換エラー
```bash
# install.xml構文確認
xmllint --noout setup/installer/install.xml

# 変数定義確認
grep -n "dynamicvariables" setup/installer/install.xml -A 20
```

### 3. 実行時エラー

#### Java バージョンエラー
```bash
# Java バージョン確認
java -version

# IzPack対応確認
java -jar target/NemakiInstaller-*.jar --help
```

#### 設定ファイルエラー
```bash
# Properties ファイル構文確認
for file in test-*.properties; do
  echo "Checking $file:"
  grep -v '^#' "$file" | grep -v '^$' | while read line; do
    if ! echo "$line" | grep -q '='; then
      echo "  ✗ Invalid line: $line"
    fi
  done
done
```

### 4. よくある問題と解決法

| 問題 | 症状 | 解決法 |
|------|------|--------|
| ClassNotFoundException | インストーラー起動時エラー | Java 8以上を使用、JARファイル再ビルド |
| 変数置換されない | ${var}がそのまま残る | dynamicvariables定義確認、parse type指定 |
| ポート競合 | インストール後起動失敗 | 異なるポート番号で再インストール |
| 権限エラー | ファイルコピー失敗 | インストールパスの書き込み権限確認 |

## パフォーマンステスト

### 1. インストール時間測定

```bash
# 自動インストール時間測定
time java -jar target/NemakiInstaller-*.jar \
  -defaults-file test-defaults.properties \
  -auto

# 期待時間: < 60秒
```

### 2. ファイルサイズ確認

```bash
# インストーラーサイズ
ls -lh target/NemakiInstaller-*.jar

# インストール後サイズ
du -sh /tmp/automated-install

# 期待サイズ: 
# - インストーラー: < 100MB
# - インストール後: < 200MB
```

## 継続的インテグレーション

### CI/CD パイプラインでの使用

```yaml
# GitHub Actions例
- name: Build Installer
  run: |
    cd setup/installer
    mvn clean compile izpack:izpack

- name: Test Automated Installation
  run: |
    cd setup/installer
    cat > ci-test.properties << EOF
    INSTALL_PATH=/tmp/ci-install
    tomcat.port=8080
    tomcat.shutdown.port=8005
    db.couchdb.url=http://localhost:5984
    db.couchdb.username=admin
    db.couchdb.password=password
    cmis.repository.main=bedroom
    nemaki.core.uri.host=localhost
    nemaki.core.uri.port=8080
    EOF
    
    java -jar target/NemakiInstaller-*.jar \
      -defaults-file ci-test.properties \
      -auto
    
    # 結果検証
    test -f /tmp/ci-install/apache-tomcat-*/shared/classes/app-server-core.properties
```

## バージョン履歴

| バージョン | 日付 | 変更内容 |
|-----------|------|----------|
| 5.2.4 | 2024-06-23 | IzPack 5.2.4対応、Maven plugin統合 |
| 5.1.3 | 2024-06-22 | 変数置換問題修正（廃止予定） |

## サポート

問題が発生した場合は、以下の情報を含めてissueを作成してください：

1. Java バージョン (`java -version`)
2. Maven バージョン (`mvn -version`) 
3. 実行コマンド
4. エラーメッセージ
5. 設定ファイル内容
6. インストールログ

---

更新日: 2024年6月23日