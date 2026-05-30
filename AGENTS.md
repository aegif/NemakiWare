# NemakiWare エージェント間連携ガイド

**最終更新**: 2026-01-05
**対象**: Claude Code、Devin、Cursor、その他のAIエージェント
**目的**: エージェント間でスムーズにタスクを委譲できる体制を構築

---

## 📌 このドキュメントの目的

CLAUDE.mdはClaude Code固有の技術詳細を記録していますが、このAGENTS.mdは**全てのAIエージェント**が参照できる汎用的なガイドです。特に**テストの委譲**をスムーズにすることを重視しています。

---

## 🤝 エージェント別の推奨タスク

### Claude Code
**得意分野**: Javaバックエンド、CMIS仕様、アーキテクチャ設計、ドキュメント整備
**推奨タスク**:
- CMISサービス層の実装・修正
- TCK準拠テストの修正・デバッグ
- データベース層の最適化
- 技術ドキュメントの整備

### Devin
**得意分野**: UIテスト、E2Eテスト、フロントエンド実装、並列タスク実行
**推奨タスク**:
- **Playwrightテストの作成・修正**（最適）
- React UIコンポーネントの実装
- UI/UXの改善
- テストカバレッジの向上

**Devinへの委譲例**:
```markdown
タスク: Playwrightスキップテストの解除
範囲: tests/admin/custom-type-creation.spec.ts
前提条件: カスタムタイプ作成UIが実装済み
期待成果: test.describe.skip → test.describe に変更し、全テスト通過
```

### Cursor
**得意分野**: コード編集、リファクタリング、インタラクティブな修正
**推奨タスク**:
- 既存コードのリファクタリング
- バグ修正
- TypeScript型定義の改善
- 単体テストの作成

### その他のエージェント
**汎用的なタスク**:
- ドキュメント整備
- 設定ファイルの修正
- QAテストの実行とレポート作成

---

## 🔒 UI パス統一ルール（CRITICAL - 2025-12-09）

**重要**: NemakiWare UI の全パスは `/core/ui/` を使用します。`/core/ui/dist/` は**禁止**です。

### パス規約

| 正しいパス | 禁止パス |
|-----------|---------|
| `/core/ui/index.html` | `/core/ui/dist/index.html` ❌ |
| `/core/ui/oidc-callback.html` | `/core/ui/dist/oidc-callback.html` ❌ |
| `/core/ui/logo1.png` | `/core/ui/dist/logo1.png` ❌ |
| `/core/ui/assets/` | `/core/ui/dist/assets/` ❌ |

### 影響を受けるファイル

| カテゴリ | ファイル | チェック項目 |
|---------|---------|-------------|
| **認証コンテキスト** | `AuthContext.tsx` | ログアウトリダイレクト |
| **保護ルート** | `ProtectedRoute.tsx` | 認証エラーリダイレクト |
| **ログイン** | `login/index.html` | リダイレクト URL |
| **レイアウト** | `Layout.tsx` | ロゴパス |
| **ログイン画面** | `Login.tsx` | ロゴパス |
| **コールバック** | `oidc-callback.html`, `saml-callback.html` | アセット参照 |
| **テストファイル** | `tests/**/*.spec.ts` | 全URL参照 |

### パス問題の検出コマンド

```bash
# UI ソースコードで /ui/dist/ を検索（ゼロ件が正常）
grep -r "/ui/dist/" core/src/main/webapp/ui/src/ --include="*.ts" --include="*.tsx"

# テストファイルで /ui/dist/ を検索（ゼロ件が正常）
grep -r "/ui/dist/" core/src/main/webapp/ui/tests/ --include="*.ts"

# ログイン関連HTMLで /ui/dist/ を検索（ゼロ件が正常）
grep -r "/ui/dist/" core/src/main/webapp/ui/login/
grep -r "/ui/dist/" core/src/main/webapp/ui/public/
```

### 修正コマンド（問題発見時）

```bash
# テストファイル一括修正
find core/src/main/webapp/ui/tests -name "*.ts" -exec sed -i '' 's|/ui/dist/|/ui/|g' {} \;

# ソースファイルは個別に確認して修正
```

---

## 🧪 テスト委譲のプロセス

### 🚀 推奨デプロイ方法（Keycloak自動起動）

**重要**: コード変更後のデプロイには `deploy-with-verification.sh` を使用してください。
このスクリプトは Keycloak の起動確認・自動起動も行います。

```bash
# プロジェクトルートから実行
./deploy-with-verification.sh

# スクリプトが実行すること:
# - Step 1: UIビルド
# - Step 2: WARビルド
# - Step 3a: Keycloak起動確認・自動起動 ← 自動的にKeycloakを起動
# - Step 3b: NemakiWareビルド
# - Step 4: サーバー起動待機
# - Step 5: デプロイ検証（アセットハッシュ確認）
# - Step 6: 基本APIテスト
# - Step 7: 外部認証確認
```

**手動でKeycloakを起動する必要がある場合**:
```bash
cd docker
docker compose -f docker-compose.keycloak.yml up -d
sleep 60  # Keycloak起動待機
```

### ステップ1: 委譲前の準備（委譲元エージェント）

```bash
# 1. 環境の健全性確認（推奨: deploy-with-verification.sh を先に実行）
docker ps                       # 全コンテナ起動確認（keycloakを含む4コンテナ）
./qa-test.sh                    # QAテスト全通過確認（56/56）
git status                      # クリーンな状態確認

# 2. 外部認証テスト実行（CRITICAL - 必須）
cd core/src/main/webapp/ui
npx playwright test tests/auth/ --project=chromium
# 認証テスト通過確認（6/7以上）

# 3. ベースライン結果の記録
npx playwright test > baseline_results.txt
# 現在の通過率を記録

# 4. 委譲内容をHANDOFF.mdに記載
```

**委譲内容の明確化**:
- [ ] 何をテストするのか（例: カスタムタイプ作成機能）
- [ ] どのファイルが対象か（例: tests/admin/custom-type-creation.spec.ts）
- [ ] 前提条件は何か（例: UIが実装済み、スキップを解除）
- [ ] 期待される成果物（例: テスト通過率の向上、バグレポート）

### ステップ2: テスト実行（委譲先エージェント）

```bash
# 1. 環境セットアップ確認
docker ps                       # コンテナ起動確認
curl -u admin:admin http://localhost:8080/core/atom/bedroom  # サービス確認
cd core/src/main/webapp/ui
npx playwright --version        # Playwrightインストール確認

# 2. ベースラインテスト実行（委譲前の状態確認）
npx playwright test --project=chromium

# 3. タスク実行（例: スキップ解除）
# ファイルを編集してtest.describe.skip → test.describe

# 4. テスト再実行
npx playwright test tests/admin/custom-type-creation.spec.ts --project=chromium

# 5. 結果の記録
npx playwright show-report
```

### ステップ3: 成果物の記録（委譲先エージェント）

```bash
# 1. 変更のコミット
git add tests/admin/custom-type-creation.spec.ts
git commit -m "test: Enable custom type creation tests"

# 2. HANDOFF.mdの更新
# - 実行したテスト
# - 通過/失敗の詳細
# - 発見したバグ
# - 次のステップの提案

# 3. プッシュ
git push origin <branch-name>
```

---

## ✅ テスト委譲チェックリスト

### 委譲元エージェント（Claude Code等）

**環境準備**:
- [ ] **デプロイスクリプト実行推奨**（`./deploy-with-verification.sh` → Keycloak自動起動）⚠️ **推奨**
- [ ] Dockerコンテナ全て起動済み（`docker ps` → keycloakを含む4コンテナ）
- [ ] QAテスト全通過（`./qa-test.sh` → 56/56）
- [ ] **外部認証テスト通過**（`npx playwright test tests/auth/` → 19/19）⚠️ **必須**
- [ ] **UIパス統一確認**（`grep -r "/ui/dist/"` → ゼロ件）⚠️ **必須**
- [ ] **OIDC設定確認**（デプロイ済みJSに`localhost:8088`が含まれること）⚠️ **必須**
- [ ] Gitブランチがクリーン（`git status`）
- [ ] 最新のコミットがプッシュ済み

**委譲内容の明確化**:
- [ ] HANDOFF.mdに委譲内容を記載
- [ ] 対象ファイル/機能を特定
- [ ] 前提条件を明記（例: UI実装済み）
- [ ] 期待成果を定義（例: テスト通過、バグレポート）

### 委譲先エージェント（Devin、Cursor等）

**環境セットアップ**:
- [ ] Java 21確認（TCKテストの場合のみ）
- [ ] Node.js 18+確認（Playwrightテストの場合）
- [ ] Playwrightブラウザインストール済み（`npx playwright install`）
- [ ] Dockerコンテナ起動確認

**テスト実行前**:
- [ ] HANDOFF.mdを読んで委譲内容を理解
- [ ] BUILD_DEPLOY_GUIDE.mdでビルド手順を確認
- [ ] ベースラインテスト実行（委譲前の状態確認）

**テスト実行後**:
- [ ] テスト結果の記録（通過/失敗の詳細）
- [ ] バグを発見した場合は詳細をレポート
- [ ] 変更のコミット・プッシュ
- [ ] HANDOFF.mdを更新（次のエージェントへ）

---

# Repository Guidelines

## Project Structure & Modules
- `common/` — Shared utilities (JAR).
- `core/` — CMIS REST/Web Services server (WAR, Jakarta EE 11, Spring 7).
  - `core/src/main/webapp/ui/` — React SPA UI (Vite + TypeScript + Ant Design)
  - `core/src/main/webapp/ui/tests/` — Playwright E2E tests
- `solr/` — Search integration helpers.
- `cloudant-init/` — CouchDB/Cloudant bootstrap tools.
- `docker/` — Compose files, images, and runtime config.
- `setup/`, `war_content/`, `WEB-INF/` — Installer and packaging assets.
- Tests live under `*/src/test/java`; reports under `*/test-reports/`.

## Build, Test, and Run

### Backend Build
- Build all modules: `mvn -T 1C -DskipTests install` (root `pom.xml`).
- Build server only: `mvn -pl core -am package` (produces `core/target/core.war`).
- Run dev server (Jetty 11): `cd core && ./start-jetty-dev.sh`.
  - Access CMIS: `http://localhost:8080/core/atom/bedroom` (admin:admin).
- Run tests (JUnit 4): `mvn test` or `mvn -pl core test`.

### React UI Build and Deployment

**Important**: React UI must be built and deployed to WAR file for production use.

```bash
# 1. Build React UI (from UI directory)
cd core/src/main/webapp/ui
npm install  # First time only
npm run build

# 2. Build core WAR with UI assets
cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# 3. Copy WAR to Docker directory
cp core/target/core.war docker/core/core.war

# 4. Deploy via Docker
#    NOTE: Compose requires COUCHDB_USER and COUCHDB_PASSWORD to be set
#    in the host environment (RC13+). Production must use strong values.
cd docker
export COUCHDB_USER=admin COUCHDB_PASSWORD=password
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# 5. Wait for startup (約90秒)
sleep 90

# 6. Verify UI is accessible
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/ui/index.html
# Expected: 200
```

### Docker Environment
- Full stack via Docker (with required env):
  `COUCHDB_USER=admin COUCHDB_PASSWORD=password docker compose -f docker/docker-compose-simple.yml up -d`
- Services: CouchDB (5984), Solr (8983), Tomcat Core (8080)
- Check status: `docker compose -f docker/docker-compose-simple.yml ps`
- View logs: `docker compose -f docker/docker-compose-simple.yml logs core`
- LDAP/Keycloak overlay (`--profile idp`) additionally requires `LDAP_ADMIN_PASSWORD`

### Scalability — single replica vs multi-replica
NemakiWare 3.1.1 is **single-replica by default**. Multi-replica
deployments are supported but require explicit configuration. The
authoritative checklist is **[`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md)** —
read it end to end before scaling out, especially the §1 inventory of
JVM-local state (10 subsystems including auth tokens, passkey
challenges, MCP sessions, SAML binding) and the §3.3 bootstrap order
(Setup Wizard must run while N=1).

Quick summary of the must-haves:
- **R1**: cookie-based sticky sessions on the load balancer
- **R2**: `lineage.leader-election.enabled=true`
- **R3**: `nemakiware.deployment.singleReplica=false` AND
  `nemakiware.deployment.stickySession=true` on every replica
- **R4-R6**: identical `nemakiware.properties`, single CouchDB cluster,
  single Solr cluster across all replicas

Without sticky sessions, every session-bound interaction breaks
intermittently (login, SAML strict mode, passkey, MCP). Without leader
election, cron schedulers duplicate work N×.

### E2E Test Environment Setup and Prevention (2025-11-22) ⚠️

**IMPORTANT**: Before running Playwright tests, always ensure a clean test environment to prevent failures.

**Quick Reference**:
- 📖 **Full Documentation**: See `docs/e2e-test-environment.md` for comprehensive guide
- 🛠️ **Quick Reset**: `make reset-test-env` (one-command environment reset)
- ✅ **Validation**: `make validate-env` or `scripts/validate-test-env.sh`
- 🧪 **Run Tests**: `make test-e2e` (reset + validate + test)
- 🛡️ **SOC template validation** (RC6.4+): `VALIDATE_DOCKER=1 scripts/validate-soc-templates.sh` — runs Vector / Fluent Bit / Filebeat / cortextool CLIs in their official Docker images against `docs/soc-templates/`. Phase 1 (host-only, `python3`) always runs; Phase 2 requires Docker. Outputs `docs/soc-templates/VALIDATION.md` when `WRITE_VALIDATION_MD=1`

**Common Issue - Initial Content Setup Failures**:

**Symptom**: Tests fail claiming "Sites" or "Technical Documents" folders are missing

**Root Cause**: Stale Docker volumes, incomplete initialization, or outdated images

**Solution**: Complete environment reset (see `docs/e2e-test-environment.md`)
```bash
make reset-test-env  # One-command solution
# OR manually:
mvn clean package -DskipTests
cd docker
export COUCHDB_USER=admin COUCHDB_PASSWORD=password   # required env
docker-compose -f docker-compose-simple.yml build core
docker-compose -f docker-compose-simple.yml down -v  # IMPORTANT: -v wipes volumes
docker-compose -f docker-compose-simple.yml up -d    # Healthchecks enforce startup order
```

**Prevention Measures Implemented** (2025-11-22):
1. ✅ Docker Compose healthchecks (automatic startup order: CouchDB → Solr → Core)
2. ✅ Makefile targets for easy environment management
3. ✅ Pre-test validation script (`scripts/validate-test-env.sh`)
4. ✅ Comprehensive documentation (`docs/e2e-test-environment.md`)

**WebKit Browser Support**:
```bash
export PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1  # Required for Linux
```

### Playwright UI Tests

**Latest Test Results** (2025-11-22):
- **After Clean Rebuild**: Initial content setup tests now passing (5/5) ✅
- **Previous Results** (2025-11-09): 25/25 core tests PASS
- **Known Issues**: Group management timeout, custom type UI not implemented
- See "Playwright UI Test Status (2025-11-09)" section below for details

**Running Tests**:
```bash
# Core tests (recommended for smoke testing)
cd core/src/main/webapp/ui
npx playwright test tests/basic-connectivity.spec.ts --project=chromium --workers=1
npx playwright test tests/auth/login.spec.ts --project=chromium --workers=1
npx playwright test tests/documents/document-management.spec.ts --project=chromium --workers=1

# All tests (may include long-running or skipped tests)
npx playwright test --project=chromium --workers=1

# Specific test file
npx playwright test tests/versioning/document-versioning.spec.ts --project=chromium --workers=1

# Debug mode (with browser UI)
npx playwright test --project=chromium --debug

# Generate HTML report
npx playwright show-report
```

**Test Environment Requirements**:
- Docker containers must be running (see Docker Environment section)
- Server must be healthy: `http://localhost:8080/core/ui/index.html` returns 200
- Recommended: Single worker (`--workers=1`) to avoid race conditions

## Coding Style & Naming
- Java 21; use Jakarta APIs (`jakarta.*`), avoid `javax.*`.
- Indentation: 4 spaces, UTF-8, 120-col soft wrap.
- Packages: `jp.aegif.nemaki...`; Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Prefer SLF4J (`org.slf4j.Logger`) over `System.out`.
- Module boundaries: put shared code in `common/`; CMIS/server code in `core/`.
- React/TypeScript: Follow standard TypeScript/React conventions, use functional components with hooks.

## Testing Guidelines

### JUnit Tests (Backend)
- Framework: JUnit 4 (Surefire configured with Java 21 module opens).
- Place tests in `src/test/java`; name files `*Test.java`.
- Keep unit tests fast and isolated (mock Solr when applicable).
- Useful scripts: `qa-test.sh`, `test-rest-api-comprehensive.sh`.

### Playwright Tests (UI)
- Framework: Playwright (TypeScript)
- Test files: `core/src/main/webapp/ui/tests/**/*.spec.ts`
- Test helpers: `tests/utils/auth-helper.ts`, `tests/utils/test-helper.ts`
- Configuration: `playwright.config.ts`

**Key Test Helpers**:
- `AuthHelper`: Login/logout, session management
- `TestHelper`: Ant Design element waiting, common UI interactions
- `uploadDocument()`: Document upload with retry logic

**Important Fixes Applied** (Historical):
1. ✅ AtomPub parser: Now extracts ALL CMIS properties (not just hardcoded 8)
2. ✅ Cache invalidation: checkout/cancelCheckout operations
3. ✅ deleteTree operation: Browser Binding support added
4. ✅ Versioning: cmis:document.versionable=true
5. ✅ Advanced Search: CMIS Browser Binding query syntax

**Current Known Issues**: See "Playwright UI Test Status (2025-11-09)" section for latest test results and known issues

### i18n Testing Strategy (2025-12-20)

**Internationalization Support**: The UI now supports multiple languages (Japanese and English) using react-i18next.

**Test Selector Guidelines**:
- Use flexible regex patterns for menu/navigation selectors to support both languages
- Example: `.filter({ hasText: /管理|Admin/i })` instead of `:has-text("管理")`
- This ensures tests work regardless of the user's language preference

**Supported Patterns**:
```typescript
// Good: Flexible regex pattern supporting both languages
const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
const documentsMenu = page.locator('.ant-menu-item').filter({ hasText: /ドキュメント|Documents/i });
const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });

// Avoid: Hardcoded single-language selectors
const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');  // Not recommended
```

**Translation Files**:
- Japanese: `src/i18n/locales/ja.json`
- English: `src/i18n/locales/en.json`
- Default language: Japanese (fallback)
- Language detection order: localStorage → navigator → htmlTag

**Adding New Languages**:
1. Create new translation file: `src/i18n/locales/{lang}.json`
2. Import in `src/i18n/index.ts`
3. Add to `languages` object and `supportedLngs` array
4. Update test selectors to include new language patterns

**Container-based Integration Tests**:
- Actual container-based integration tests are delegated to a separate agent
- Current tests use mocks and in-memory stubs for unit/integration testing
- See TypeResourceTest.java for REST API test examples

## Commit & Pull Request Guidelines
- Use concise, imperative subjects. Conventional prefixes are common: `feat:`, `fix:`, `refactor:`, `chore:` (optionally add module tag, e.g., `[core] fix: ...`).
- Include context and rationale in the body; reference issues (`Fixes #123`).
- PRs should include: clear description, reproduction steps, test evidence (logs or report paths), config notes (e.g., CouchDB, ports), and any Docker compose variant used.

## Security & Configuration Tips
- Do not commit secrets. Local defaults: CouchDB `admin/password` (dev only).
- Primary config: `core/nemakiware.properties`, `docker/repositories.yml`.
- For Java 21, ensure `MAVEN_OPTS` includes required `--add-opens` (see `core/start-jetty-dev.sh`).
- **External Ingest delegation (3.1.1-RC3+)**: connectors are admin-only by default. To let a folder owner use a connector, set `delegated=true` AND either `allowedFolderIds=[...]` or `delegateAllFolders=true` (the latter only when truly needed — credential reach is repo-wide). Empty `allowedFolderIds` while `delegated=true` is treated as no delegation. See `docs/design/connector-delegation.md`.

## Current Work Status (2026-05-30)

### Active Branch
- **Branch**: `release/3.1.1-RC6` (latest tag: `v3.1.1-RC6.6`)
- **Focus**: SSRF guard hardening — RC6.5 closed the GHSA-reported IPv6 transition unwrap, RC6.6 follow-on adds 5 IPv4 special-use ranges + Teredo `2001::/32` + RFC 6052 §2.2 /48 NAT64 layout
- See `CLAUDE.md` for the canonical version log (RC1〜RC30) and security status; see `RELEASE_NOTES.md` for the user-facing per-RC narrative (14 sections RC5 → RC6.6).

### TCK Complete Success Achievement (2025-11-09) 🎉

**Status**: ✅ **39/39 Tests PASS (100% of implemented features)**
**TCK Compliance**: 92.9% (39/42 tests, 3 skipped for unimplemented multi-filing feature)

| Test Group | Tests | Status | Time |
|------------|-------|--------|------|
| BasicsTestGroup | 3/3 | ✅ PASS | 24.9s |
| ConnectionTestGroup | 2/2 | ✅ PASS | 1.4s |
| TypesTestGroup | 3/3 | ✅ PASS | 172.0s |
| ControlTestGroup | 1/1 | ✅ PASS | 30.4s |
| VersioningTestGroup | 4/4 | ✅ PASS | 358.1s |
| InheritedFlagTest | 1/1 | ✅ PASS | 1.2s |
| QueryTestGroup | 6/6 | ✅ PASS | Various |
| CrudTestGroup1 | 10/10 | ✅ PASS | 35m 2s |
| CrudTestGroup2 | 9/9 | ✅ PASS | 14m 57s |
| FilingTestGroup | 0/3 | ⊘ SKIP | - |

**Total**: 39 tests PASS, 0 FAIL, 3 SKIP (intentional)

### Critical Fix: queryRootFolderTest (2025-11-09)

**Problem**: WHERE clause queries with explicit SELECT + aliases were missing selected properties

**Example Query**:
```sql
SELECT cmis:name AS folderName, cmis:objectId AS folderId
FROM cmis:folder
WHERE cmis:creationDate > TIMESTAMP '2012-12-31T23:00:00.000Z'
```
❌ **Before**: Only returned objectTypeId, baseTypeId (required properties)
✅ **After**: Returns folderName, folderId, objectTypeId, baseTypeId (all expected)

**Root Cause**: WAR file corruption/stale class files preventing Spring Bean initialization

**Solution**: Clean build + proper Docker deployment
```bash
# 1. Stop containers
docker compose -f docker/docker-compose-simple.yml down

# 2. Clean build
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# 3. Copy WAR (verify 118MB size)
cp core/target/core.war docker/core/core.war

# 4. Force recreate containers (compose requires COUCHDB env)
export COUCHDB_USER=admin COUCHDB_PASSWORD=password
docker compose -f docker/docker-compose-simple.yml up -d --build --force-recreate

# 5. Wait for initialization
sleep 90
```

### Lessons Learned: Debugging Failed Approaches ⚠️

**What NOT to Do When Debugging**:

1. ❌ **Adding System.err.println() to production code**
   - **Problem**: Caused Spring BeanCreationException
   - **Error**: `ClassNotFoundException: org.apache.chemistry.opencmis.commons.data.ObjectInFolderData`
   - **Lesson**: Even simple debug statements can break Spring class loading
   - **Better Approach**: Use existing JSON logs or remote debugger

2. ❌ **Changing logback.xml without verifying output**
   - **Problem**: DEBUG level set but no output appeared
   - **Issue**: Logger may not be initialized or output suppressed
   - **Lesson**: Always verify logging changes produce expected output

3. ❌ **Incremental rebuilds without clean**
   - **Problem**: Stale class files cause unpredictable behavior
   - **Lesson**: Always use `mvn clean` for critical fixes

**What DOES Work** ✅:

1. ✅ **Clean build + force recreate deployment**
   - Most reliable way to eliminate build artifacts issues
   - Guarantees consistent state

2. ✅ **Analyze existing Docker JSON logs**
   - CMIS operations already produce detailed JSON logs
   - No code modifications needed

3. ✅ **Use tck-test-clean.sh for TCK tests**
   - Prevents database bloat issues
   - Provides consistent test environment

### Regression Prevention Guidelines

**Before Making Changes**:
1. Record current test status (QA: 56/56, TCK: 39/39)
2. Create git branch for changes
3. Document expected behavior

**After Making Changes**:
1. Clean build: `mvn clean package`
2. Force recreate: `docker compose down && up -d --build --force-recreate`
3. Verify QA tests: `./qa-test.sh` (should show 56/56)
4. Verify affected TCK tests
5. Commit with detailed description

**When Debugging**:
- Prefer analyzing existing logs over adding debug code
- If adding debug code is necessary, test in isolated environment first
- Always revert debug code before committing
- Document what was tried and why it failed

### Playwright UI Test Status (2025-11-09) ✅

**Core Test Results** - All passing, no regressions from TCK work:

| Test Suite | Result | Time | Details |
|------------|--------|------|---------|
| **Basic Connectivity** | ✅ 4/4 PASS | 6.3s | UI load, backend, assets, React init |
| **Authentication** | ✅ 7/7 PASS | 23.8s | Login/logout, session, permissions |
| **Document Management** | ✅ 9/9 PASS | 2m 0s | List, upload, delete, download |
| **Initial Content Setup** | ✅ 5/5 PASS | 1.9s | Folder creation, ACL validation |

**Total**: 25/25 PASS (100%) ✅

**Verified Functionality**:
- ✅ React app initialization and Ant Design rendering
- ✅ User authentication and session management
- ✅ CMIS Browser Binding integration (document CRUD)
- ✅ ACL permission management (multi-principal support)
- ✅ Folder hierarchy navigation

**Known Issues** (Timeouts/UI Not Implemented):

1. **group-management-crud.spec.ts** - Timeout (40+ seconds)
   - Test 1 (create group): Timeout waiting for group creation UI response
   - Tests 2-5: Skipped due to test 1 dependency
   - **Root Cause**: Group creation UI may not be implemented or extremely slow
   - **Status**: Requires UI team investigation

2. **custom-type-creation.spec.ts** - Partially Skipped
   - Test 1 (create custom type): ✅ PASS (17.2s)
   - Tests 2-3 (add properties, create document): ⊘ SKIP (UI elements not found)
   - **Root Cause**: Property definition tab and type selector not implemented in UI
   - **Status**: Marked as WIP in test file

3. **type-definition-upload.spec.ts** - Partially Failing
   - Test 1 (valid upload): ✅ PASS (14.3s)
   - Test 2 (conflict detection): ✅ PASS (11.9s)
   - Test 3 (JSON edit): ❌ FAIL (40.3s) - JSON edit modal timeout
   - **Root Cause**: JSON edit modal selector may have changed
   - **Status**: Requires investigation

**Impact Assessment**:
- ✅ **No TCK Regressions**: All core CMIS functionality unaffected by TCK fixes
- ✅ **No Core UI Regressions**: Essential UI features (auth, documents, ACL) working
- ⚠️ **Admin UI Issues**: Group management and type management UI require attention

### Next Steps

1. **High Priority - TCK Maintenance**:
   - Monitor for regressions using `./qa-test.sh` (56/56) and TCK tests
   - Keep database clean for reliable test execution (`tck-test-clean.sh`)
   - Document any new CMIS features with corresponding TCK tests

2. **Medium Priority - Playwright UI Tests**:
   - ✅ **COMPLETED**: Verified core UI tests (25/25 PASS)
   - ⚠️ Investigate group-management-crud timeout (UI implementation issue)
   - ⚠️ Review custom-type-creation skipped tests (property tab UI missing)
   - ⚠️ Fix type-definition-upload JSON edit modal selector

3. **Low Priority**:
   - Implement missing UI features (Group Management CRUD, Custom Type Properties)
   - Complete PDF Preview functionality
   - Improve CI/CD timeout handling

### Reference Documents
- **HANDOFF-DOCUMENT.md**: Detailed session handoff with technical findings
- **PLAYWRIGHT-TEST-PROGRESS.md**: Test progress tracking
- **CLAUDE.md**: Comprehensive project documentation and history

### Quick Troubleshooting

**Docker not running**:
```bash
# Check Docker daemon status
docker ps

# If not running, start Docker Desktop (macOS) or systemctl (Linux)
# Then restart containers (compose requires COUCHDB env)
cd docker
export COUCHDB_USER=admin COUCHDB_PASSWORD=password
docker compose -f docker-compose-simple.yml up -d
```

**UI not accessible**:
```bash
# Verify core container is running
docker compose -f docker/docker-compose-simple.yml ps

# Check core logs
docker compose -f docker/docker-compose-simple.yml logs core

# Restart if needed
docker compose -f docker/docker-compose-simple.yml restart core
sleep 90
```

**Tests failing with timeout**:
- Check server is responding: `curl http://localhost:8080/core/ui/index.html`
- Increase timeout in `playwright.config.ts`: `timeout: 60000`
- Use `--workers=1` to avoid race conditions
- Check Docker container logs for errors

**Build issues**:
```bash
# Clean rebuild
cd core/src/main/webapp/ui && npm run build
cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests

# Verify WAR file size (should be ~300MB)
ls -lh core/target/core.war
```

---

## 🧪 タイプ更新・削除機能のテスト戦略 (2025-12-20)

### テストレベルの分類

NemakiWareのタイプ更新・削除機能には、以下の3レベルのテストが必要です：

| レベル | 種類 | 担当 | 説明 |
|--------|------|------|------|
| **Level 1** | ユニットテスト（モック） | 現在のエージェント | サービス層のロジックをモックDAOでテスト |
| **Level 2** | 統合テスト（インメモリDAO） | 現在のエージェント | REST→Service→DAO協調をインメモリスタブでテスト |
| **Level 3** | E2Eテスト（実コンテナ） | **別のエージェント** | 実際のDocker環境でのフルスタックテスト |

### Level 1: ユニットテスト（実装済み）

**ファイル**: `core/src/test/java/jp/aegif/nemaki/rest/TypeResourceTests.java`

**カバー範囲**:
- タイプ作成のバリデーション
- サブタイプ検出ロジック
- リレーションシップ参照検出
- プロパティ型/カーディナリティ変換ロジック

### Level 2: 統合テスト（実装予定）

**目的**: REST API→TypeService→ContentService/TypeService協調の検証

**アプローチ**:
- インメモリDAOスタブを使用（CouchDB不要）
- Spring TestContextを使用してDI環境を構築
- 実際のHTTPリクエスト/レスポンスをシミュレート

**テスト対象シナリオ**:
1. タイプ定義の作成→更新→削除のライフサイクル
2. サブタイプが存在する場合の削除拒否
3. リレーションシップ参照がある場合の削除拒否
4. プロパティ型変更後の既存オブジェクト読み込み（coercion警告）
5. カーディナリティ変更後の既存オブジェクト読み込み

### Level 3: E2Eテスト（別エージェント担当）

**重要**: 実際のDockerコンテナを使用したE2Eテストは、別のエージェントに委譲します。

**委譲先エージェントへの指示**:
```markdown
タスク: タイプ更新・削除機能のE2Eテスト
前提条件: 
- PR #402 がマージ済み
- Docker環境が起動済み（CouchDB, Solr, Core）

テストシナリオ:
1. カスタムタイプを作成
2. そのタイプでドキュメントを作成
3. タイプ定義のプロパティ型を変更
4. ドキュメントを再読み込みし、coercion警告が表示されることを確認
5. ドキュメントを再保存し、警告が消えることを確認
6. タイプを削除しようとし、ドキュメントが存在するため削除できないことを確認

期待成果:
- Playwrightテストファイル: tests/admin/type-update-delete.spec.ts
- 全シナリオの通過
```

### Coercion警告のテスト

**バックエンド（CompileServiceImpl）**:
- `normalizeCardinality()`: multi→single変換時の警告
- `coerceElement()`: 型変換失敗時の警告
- `addCoercionWarningsExtension()`: CMIS Extensionへの警告追加

**フロントエンド（DocumentViewer）**:
- coercion警告がある場合のAlert表示
- 警告詳細のCollapse展開
- プロパティタブでの警告表示

### テスト実行コマンド

```bash
# Level 1: ユニットテスト
cd /path/to/NemakiWare
mvn test -pl core -Dtest=TypeResourceTests

# Level 2: 統合テスト（実装後）
mvn test -pl core -Dtest=TypeResourceIntegrationTests

# Level 3: E2Eテスト（別エージェント）
cd core/src/main/webapp/ui
npx playwright test tests/admin/type-update-delete.spec.ts --project=chromium
```
