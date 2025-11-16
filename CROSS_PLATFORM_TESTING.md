# クロスプラットフォームテスト実行ガイド

このドキュメントは、Mac/Linux両環境でNemakiWareのビルドとテストを実行するための統一手順を説明します。

## 環境別の前提条件

### Linux (Ubuntu/Debian)

```bash
# Java 17
sudo apt-get update
sudo apt-get install openjdk-17-jdk

# Maven
sudo apt-get install maven

# Node.js 20 LTS
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# Docker
sudo apt-get install docker.io docker-compose-plugin

# 必須ツール
sudo apt-get install jq

# Playwright依存関係 (UIテスト用)
npx playwright install-deps
```

### macOS

```bash
# Homebrew (未インストールの場合)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Java 17
brew install openjdk@17

# Maven
brew install maven

# Node.js 20 LTS
brew install node@20

# Docker Desktop
# https://www.docker.com/products/docker-desktop からダウンロード

# 必須ツール
brew install jq coreutils
```

## 統一ビルド手順

以下の手順はMac/Linux両方で同じコマンドで実行できます:

### 1. 環境変数設定 (オプション)

```bash
# Java 17 を使用することを明示
export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # Mac
# または
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  # Linux

export PATH=$JAVA_HOME/bin:$PATH
```

### 2. UIビルド

```bash
cd core/src/main/webapp/ui
npm ci
npm run build
cd ../../../..
```

### 3. Coreビルド

```bash
mvn clean package -DskipTests -pl core -am
```

### 4. Docker環境起動

```bash
cd docker
docker compose -f docker-compose-simple.yml up -d
cd ..
```

### 5. サーバー起動確認

```bash
# 2-3分待機後
curl -u admin:admin "http://localhost:8080/core/browser/bedroom" | jq '.repositoryId'
```

## テスト実行

### TCKテスト (包括的)

```bash
# 自動的にOS検出してJAVA_HOMEを設定
./tck-test-clean.sh
```

### Playwrightテスト

```bash
cd core/src/main/webapp/ui

# Linux のみ: システム依存関係インストール
# npx playwright install-deps

# 両OS共通
npx playwright install
npm run test
```

## クロスプラットフォーム互換性チェックリスト

### ビルド互換性

- [x] UIビルド (Vite) - Mac/Linux両対応
- [x] Coreビルド (Maven) - Mac/Linux両対応
- [x] Docker Compose - Mac/Linux両対応
- [x] TCKテストスクリプト - OS自動検出実装済み

### 既知の差異

| 項目 | Linux | macOS | 対応状況 |
|------|-------|-------|----------|
| `timeout` コマンド | `/usr/bin/timeout` | `gtimeout` (coreutils) | ✅ スクリプトで対応 |
| `jq` | 標準 | Homebrew必要 | ✅ ドキュメント化 |
| Playwright deps | `install-deps` 必要 | 不要 | ✅ ドキュメント化 |
| Docker | Docker Engine | Docker Desktop | ✅ 両対応 |
| JAVA_HOME | `/usr/lib/jvm/...` | `/usr/libexec/java_home` | ✅ スクリプトで自動検出 |

### テスト結果の期待値

**成功基準**:
- UIビルド: エラーなし (警告は許容)
- Coreビルド: BUILD SUCCESS
- Docker起動: 全コンテナ healthy
- TCKテスト: 80%以上成功 (Solr権限エラーは既知の問題)
- Playwrightテスト: 全テスト成功

## CI/CD統合 (将来の改善案)

GitHub Actionsマトリックスビルドの例:

```yaml
name: Cross-Platform Tests

on: [push, pull_request]

jobs:
  test:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest]
        
    runs-on: ${{ matrix.os }}
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Java 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Set up Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '20'
      
      - name: Install dependencies (macOS)
        if: runner.os == 'macOS'
        run: brew install jq coreutils
      
      - name: Build UI
        run: |
          cd core/src/main/webapp/ui
          npm ci
          npm run build
      
      - name: Build Core
        run: mvn clean package -DskipTests -pl core -am
      
      - name: Run Playwright Tests
        run: |
          cd core/src/main/webapp/ui
          npx playwright install --with-deps chromium
          npm run test -- --project=chromium
```

## トラブルシューティング

### Mac特有の問題

**問題**: `timeout: command not found`
```bash
# 解決策
brew install coreutils
# gtimeout が利用可能になる
```

**問題**: Docker Desktop メモリ不足
```bash
# 解決策: Docker Desktop設定で6GB以上に増やす
```

**問題**: Apple Silicon イメージ互換性
```bash
# 解決策: docker-compose.yml に platform: linux/amd64 追加
```

### Linux特有の問題

**問題**: Docker権限エラー
```bash
# 解決策
sudo usermod -aG docker $USER
newgrp docker
```

**問題**: Playwright依存関係不足
```bash
# 解決策
npx playwright install-deps
```

## ベストプラクティス

1. **バージョン固定**: `.nvmrc` でNode.jsバージョンを固定 (推奨: 20 LTS)
2. **Docker Compose V2**: 常に `docker compose` (V2) を使用
3. **クリーンビルド**: テスト前に必ず `mvn clean` を実行
4. **ポート確認**: テスト前にポート8080/5984/8983が空いていることを確認
5. **ログ確認**: 失敗時は `docker compose logs` でコンテナログを確認

## 参考リンク

- [Docker Desktop for Mac](https://docs.docker.com/desktop/install/mac-install/)
- [Docker Engine for Linux](https://docs.docker.com/engine/install/)
- [Node.js Downloads](https://nodejs.org/)
- [Maven Installation](https://maven.apache.org/install.html)
