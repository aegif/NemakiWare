# NemakiWare ビルド・デプロイ・テスト 完全ガイド

## 🎯 このガイドの目的

NemakiWareの開発で**最も混乱しやすいビルド・デプロイ・テスト手順**を、明確かつ簡潔にまとめました。

---

## 📁 重要：作業ディレクトリの理解

### Git Worktreeとメインリポジトリ

NemakiWareプロジェクトでは、以下の2つの場所が存在する可能性があります：

1. **メインリポジトリ**: `/Users/ishiiakinori/NemakiWare/`
2. **Git Worktree**: `/private/var/folders/.../worktrees/368c-tck/`

**⚠️ 重要**: どちらで作業しているか常に確認してください。

```bash
# 現在の作業ディレクトリを確認
pwd

# Git worktreeかどうかを確認
git rev-parse --show-toplevel
```

**推奨**: Git worktreeで作業している場合は、**worktree内でビルド**してください。

---

## 🔨 ビルド手順（確実な方法）

### 前提条件

```bash
# Java 17が必須
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

# 確認
java -version  # 17.x.xが表示されること
mvn -version   # Java 17を使用していること
```

### ステップ1：クリーンビルド

```bash
# 作業ディレクトリに移動（worktreeまたはメインリポジトリ）
cd /path/to/your/working/directory

# クリーンビルド（テストはスキップ）
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
```

**✅ 成功の確認**:
```bash
ls -lh core/target/core.war
# 約150MBのWARファイルが表示されること
```

### ステップ2：WARファイルのコピー

```bash
# WARファイルをDocker用ディレクトリにコピー
cp core/target/core.war /Users/ishiiakinori/NemakiWare/docker/core/core.war

# 確認
ls -lh /Users/ishiiakinori/NemakiWare/docker/core/core.war
```

**⚠️ 重要**: コピー先は**常に** `/Users/ishiiakinori/NemakiWare/docker/core/core.war` です。

---

## 🐳 Dockerデプロイ手順（確実な方法）

### ステップ3：Dockerイメージのリビルドとコンテナの再作成

```bash
# Dockerディレクトリに移動
cd /Users/ishiiakinori/NemakiWare/docker

# 完全なリビルドと再作成（重要：--buildと--force-recreateの両方が必要）
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core
```

**❌ 間違った方法**:
```bash
# ❌ これだけでは不十分！イメージが更新されません
docker compose -f docker-compose-simple.yml restart core
```

**✅ 正しい方法の理由**:
- `--build`: Dockerイメージを再ビルド（新しいWARファイルをコピー）
- `--force-recreate`: コンテナを強制的に再作成
- 両方を指定しないと、古いWARファイルが使われ続けます

### ステップ4：起動待ち

```bash
# コンテナ起動を待つ（30-40秒）
sleep 35

# ヘルスチェック
docker ps
# docker-core-1が"Up"または"healthy"であること

# サービス確認
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# HTTP 200が返されること
```

---

## 🧪 テスト実行手順

### Playwright UI テスト

```bash
# UIテストディレクトリに移動
cd /Users/ishiiakinori/NemakiWare/core/src/main/webapp/ui

# 前提：npm依存関係がインストール済み
npm install  # 初回のみ

# 全テスト実行（6ブラウザ × 81テスト = 486実行）
npx playwright test

# 特定のテストファイルのみ
npx playwright test tests/admin/initial-content-setup.spec.ts

# ブラウザを表示してデバッグ
npx playwright test --headed --project=chromium
```

**期待される結果**:
```
Running 486 tests using 6 workers
  ✓ 400+ passed (depends on implementation status)
  ✗ Some tests may fail (expected for WIP features)
```

### TCK コンプライアンステスト

```bash
# プロジェクトルートに移動
cd /Users/ishiiakinori/NemakiWare

# Java 17環境設定
export JAVA_HOME=/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home

# 特定のテストグループを実行
timeout 300s mvn test -Dtest=BasicsTestGroup -f core/pom.xml -Pdevelopment

# 複数のテストグループを実行
timeout 600s mvn test -Dtest=BasicsTestGroup,TypesTestGroup,VersioningTestGroup \
  -f core/pom.xml -Pdevelopment
```

**期待される結果**:
```
Tests run: X, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### QA統合テスト

```bash
# プロジェクトルートで実行
cd /Users/ishiiakinori/NemakiWare

# 全QAテスト実行
./qa-test.sh

# 期待される結果：
# Tests passed: 56 / 56 (100% success rate)
```

---

## 🔍 トラブルシューティング

### 問題1: "変更が反映されない"

**症状**: コードを修正してビルド・デプロイしても、変更が反映されない

**原因**:
1. 異なるディレクトリでビルドしている
2. `--build`フラグを忘れている
3. WARファイルのコピーを忘れている

**解決方法**:
```bash
# 1. 作業ディレクトリを確認
pwd
git rev-parse --show-toplevel

# 2. 正しい場所でビルド
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# 3. WARファイルをコピー
cp core/target/core.war /Users/ishiiakinori/NemakiWare/docker/core/core.war

# 4. 完全なリビルド
cd /Users/ishiiakinori/NemakiWare/docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

### 問題2: "コンテナが起動しない"

**症状**: `docker ps`でコンテナが表示されない、または"Restarting"状態

**診断**:
```bash
# ログを確認
docker logs docker-core-1 --tail 50

# よくあるエラー：
# - "Address already in use" → ポート8080が使用中
# - "Cannot allocate memory" → Dockerメモリ不足
```

**解決方法**:
```bash
# ポート競合の場合
lsof -i :8080  # 使用中のプロセスを確認
kill <PID>     # プロセスを停止

# メモリ不足の場合
# Docker Desktop → Preferences → Resources → Memory を8GB以上に設定
```

### 問題3: "テストがタイムアウトする"

**症状**: Playwrightテストが"Timeout exceeded"エラー

**原因**: コンテナが完全に起動していない

**解決方法**:
```bash
# コンテナのヘルスチェックを待つ
docker ps
# STATUSが"healthy"になるまで待つ（最大90秒）

# サービス確認
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# HTTP 200が返されることを確認してからテスト実行
```

---

## 📝 クイックリファレンス

### フルリビルド・デプロイ（ワンライナー）

```bash
# Git worktree内で実行する場合
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q && \
cp core/target/core.war /Users/ishiiakinori/NemakiWare/docker/core/core.war && \
cd /Users/ishiiakinori/NemakiWare/docker && \
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core && \
sleep 35 && \
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

**成功の確認**: 最後のcurlコマンドでXMLレスポンスが返されること。

### ログ確認コマンド

```bash
# コアサービスのログ（最新50行）
docker logs docker-core-1 --tail 50

# リアルタイムでログを追跡
docker logs docker-core-1 -f

# エラーのみ表示
docker logs docker-core-1 2>&1 | grep -i error
```

---

## ⚡ ベストプラクティス

1. **常にworktree内でビルド**: 作業ディレクトリで`pwd`を確認
2. **`--build --force-recreate`を忘れない**: コンテナ再起動だけでは不十分
3. **起動待ちを入れる**: `sleep 35`またはヘルスチェック確認
4. **WARファイルサイズを確認**: 約150MBが正常
5. **テスト前にサービス確認**: curlでHTTP 200を確認してからテスト実行

---

## 🎓 次のエージェントへの引き継ぎポイント

### 最も重要な3つのポイント

1. **ビルドとデプロイは別プロセス**
   - ビルド: `mvn clean package`
   - コピー: `cp core/target/core.war /Users/ishiiakinori/NemakiWare/docker/core/core.war`
   - デプロイ: `docker compose up -d --build --force-recreate`

2. **restartは不十分、rebuildが必要**
   - `docker compose restart` → ❌ 古いWARが使われる
   - `docker compose up -d --build --force-recreate` → ✅ 新しいWARが使われる

3. **起動待ちが必須**
   - デプロイ直後のテスト実行 → ❌ タイムアウト
   - 35秒待ってからテスト実行 → ✅ 成功

### よくある混乱ポイント

| 混乱しやすい点 | 正しい理解 |
|--------------|----------|
| WARファイルの場所 | 常に `/Users/ishiiakinori/NemakiWare/docker/core/core.war` にコピー |
| Dockerイメージの更新 | `restart`では更新されない、`--build`が必要 |
| テストの実行タイミング | コンテナ起動後35秒待つ |
| Java環境 | 必ずJava 17を使用（`java -version`で確認） |

---

**作成日**: 2025-11-01
**最終更新**: 2025-11-01
**対象ブランチ**: vk/368c-tck
