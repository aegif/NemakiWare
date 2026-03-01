# OpenCMIS Source Detach Runbook (NemakiWare)

## Purpose
NemakiWare 本体リポジトリから OpenCMIS ソース同梱を外し、GitHub Packages + `lib/built-jars` 運用へ一本化する。

## Current status check (as of this runbook creation)
- `core/pom.xml` に GitHub Packages 取得プロファイル `opencmis-github-packages` が追加済み。
- `scripts/fetch-opencmis-from-github-packages.sh` が追加済み（未追跡なら必ず `git add`）。
- 本体ビルドは `lib/built-jars` の JAR を `systemPath` 参照する構成。
- OpenCMIS 同梱ソースは主に以下が Git 管理下に残存:
  - `chemistry-opencmis-1.1.0/`
  - `lib/nemaki-opencmis-1.1.0-jakarta/`
  - `chemistry-opencmis-1.1.0-source-release.zip`
  - `chemistry-opencmis-commons-impl-1.1.0-nemakiware.jar` (root)

## Gate (Go / No-Go)
以下を満たした場合のみ「削除フェーズ」に進む:

1. GitHub Packages から OpenCMIS JAR 同期が成功
2. クリーンビルド成功
3. 必須テスト成功
4. ログ実装競合が解消済み（SLF4J binding が単一）

### Gate command set
```bash
# 1) OpenCMIS JAR 同期
./scripts/fetch-opencmis-from-github-packages.sh

# 2) クリーンビルド
mvn -pl core -DskipTests clean package

# 3) 最低限のテスト
mvn -pl core -Dtest=WebhookServiceTest test
./qa-test.sh

# 4) SLF4J binding の実体確認（core.war）
jar tf core/target/core.war | rg "WEB-INF/lib/(logback-classic|log4j-slf4j2-impl|slf4j-simple|slf4j-reload4j|slf4j-jdk14)"
```

## Detach execution steps

### Step 0: 作業ブランチ
```bash
git checkout -b codex/opencmis-source-detach
```

### Step 1: 参照残りの最終確認
```bash
rg -n "chemistry-opencmis-1\\.1\\.0|nemaki-opencmis-1\\.1\\.0-jakarta|chemistry-opencmis-1\\.1\\.0-source-release\\.zip" -S \
  --glob '!chemistry-opencmis-1.1.0/**' \
  --glob '!lib/nemaki-opencmis-1.1.0-jakarta/**'
```

期待値:
- 実行手順ドキュメント以外に実稼働参照がないこと。

### Step 2: 同梱ソースの削除
```bash
git rm -r chemistry-opencmis-1.1.0
git rm -r lib/nemaki-opencmis-1.1.0-jakarta
git rm chemistry-opencmis-1.1.0-source-release.zip
git rm chemistry-opencmis-commons-impl-1.1.0-nemakiware.jar
```

### Step 3: 再流入防止（推奨）
`.gitignore` に次を追加:
```gitignore
/chemistry-opencmis-1.1.0/
/lib/nemaki-opencmis-1.1.0-jakarta/
/chemistry-opencmis-1.1.0-source-release.zip
```

### Step 4: 再検証
```bash
./scripts/fetch-opencmis-from-github-packages.sh
mvn -pl core -DskipTests clean package
mvn -pl core -Dtest=WebhookServiceTest test
./qa-test.sh
```

必要に応じて:
```bash
cd core/src/main/webapp/ui
npx playwright test tests/basic-connectivity.spec.ts --project=chromium --workers=1
```

### Step 5: コミット
```bash
git add -A
git commit -m "chore: detach bundled OpenCMIS sources and keep GitHub Packages workflow only"
```

## Logging conflict checkpoint (important)
現時点で `core.war` に `logback-classic` と `log4j-slf4j2-impl` が同居し得るため、
OpenCMIS 切り離し前にログ実装を一本化すること。

確認コマンド:
```bash
mvn -pl core -DskipTests dependency:tree \
  -Dincludes=ch.qos.logback:logback-classic,ch.qos.logback:logback-core,org.apache.logging.log4j:log4j-slf4j2-impl \
  -Dverbose
```

対応方針:
- `core` の実行時 SLF4J binding を 1 つに固定する。
- `cloudant-init` / `solr` から入る `logback-classic` を除外するか、逆に Log4j2 側を排除して Logback 統一する。

## Rollback
問題が出た場合:
```bash
git restore --staged .
git restore .
```
または対象コミットを revert する。

