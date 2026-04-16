# Testing Environments

NemakiWare のテスト実行環境の使い分けガイド。

## サマリ

| テスト種別 | サーバー | DB/検索 | 起動コマンド |
|---|---|---|---|
| ユニットテスト (JUnit) | 不要 | 不要 | `mvn test -Dtest=...` |
| TCK / 結合テスト | Tomcat (Docker) | CouchDB (Docker), Solr (Docker) | `./tck-test-clean.sh` |
| QA 統合テスト | Tomcat (Docker) | CouchDB, Solr (Docker) | `./qa-test.sh qa` |
| Playwright E2E | Tomcat (Docker) | CouchDB, Solr (Docker) | `npx playwright test` |
| 開発時のコード確認 | Jetty (Maven) | CouchDB (Docker), Solr (検索時のみ) | `bash core/start-jetty-dev.sh` |

**結論**: CouchDB は **常に必要**。Solr は検索系（QA, TCK Query, E2E search テスト、Jetty dev での検索操作）で必要。Tomcat は CI/QA/E2E すべての結合テストで必要。Jetty は開発時のコード確認用に限定。

---

## 1. ユニットテスト — サーバー不要

### 対象
- `core/src/test/java/jp/aegif/nemaki/webhook/*Test.java`
- `core/src/test/java/jp/aegif/nemaki/cmis/aspect/query/solr/*Test.java`
- `core/src/test/java/jp/aegif/nemaki/rest/ResourceBaseCsrfProtectionTest.java`
- `core/src/test/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceTest.java`
- その他 `*Test.java` でJVM内完結するもの

### 実行方法
```bash
mvn test -Dtest=ResourceBaseCsrfProtectionTest,CanonicalImportServiceTest,WebhookServiceTest -f core/pom.xml -Pdevelopment
```

### 依存
- なし（JVM内のMockito/Stub）

### 用途
- ロジック検証
- リファクタリング時の回帰確認
- CI 高速 fail-fast

---

## 2. TCK / 結合テスト — Tomcat + CouchDB + Solr

### 対象
- 38 テスト: `ConnectionTestGroup`, `BasicsTestGroup`, `TypesTestGroup`, `ControlTestGroup`, `VersioningTestGroup`, `CrudTestGroup1`, `CrudTestGroup2`, `QueryTestGroup`
- CMIS 1.1 仕様準拠の検証
- ATOMPUB / Browser Binding 経由の HTTP 呼び出し

### 実行方法
```bash
# DBを再作成してから実行（推奨。テスト蓄積でchildren取得が遅くなるため）
./tck-test-clean.sh

# 個別実行
mvn test -Dtest=BasicsTestGroup -f core/pom.xml -Pdevelopment
```

### 依存
- **CouchDB** (Docker, `docker-core-1` 経由でアクセス)
- **Solr** (QueryTestGroup のみ)
- **Tomcat** (Docker `docker-core-1`, ポート 8080)

### 用途
- CMIS API互換性検証
- リリース前の最終確認

---

## 3. QA 統合テスト — Tomcat + CouchDB

### 対象
- 94 テスト: 環境/DB/CMIS/RAG/AuthToken/MCP 等の HTTP 呼び出しチェック
- `./qa-test.sh fast|core|qa|full`

### 実行方法
```bash
./qa-test.sh qa  # 標準（94テスト, 数分）
```

### 依存
- **CouchDB** (Docker)
- **Solr** (Docker)
- **Tomcat** (Docker, ポート 8080)
- **TEI** (RAG embedding, Docker)

### 用途
- 開発中の health check
- デプロイ後の smoke test

---

## 4. Playwright E2E — Tomcat + CouchDB + Solr + ブラウザ

### 対象
- 976 テスト: UIからの操作、認証、ドキュメントCRUD、検索、プレビュー、管理画面
- `core/src/main/webapp/ui/tests/`

### 実行方法
```bash
cd core/src/main/webapp/ui
npx playwright test --project=chromium                      # 全量
npx playwright test tests/api/ingest-pipeline-e2e.spec.ts  # 特定ファイル
```

### 依存
- **CouchDB**, **Solr**, **Tomcat** (Docker)
- **Keycloak** (Docker, OIDC/SAML テスト時のみ; `docker compose -f docker/docker-compose.keycloak.yml up -d`)
- Playwright が起動するchromium/firefox/webkit

### 用途
- リグレッションテスト
- リリース前のフル検証

---

## 5. Jetty 開発サーバー — CouchDB のみ

### 用途
- IDEからのコード変更を即座に反映して動作確認したい場合
- Mavenプラグイン経由で `mvn jetty:run` で起動
- ポート8081 (Tomcatの8080と競合しない)

### 実行方法
```bash
# 前提: CouchDB と Solr は Docker で起動済み
bash core/start-jetty-dev.sh
# または
mvn jetty:run -f core/pom.xml -Pdevelopment
```

### 依存
- **CouchDB** (Docker, ポート 5984)
- **Solr** (Docker, ポート 8983) — 検索を使う機能の場合
- 自身は Maven Jetty プラグイン (12.1.5, ee11)

### 制約
- TCK / QA / E2E は **Tomcat を前提** にしているため、Jetty で実行すると port 不一致 (8081 vs 8080) で動かない
- Jetty開発サーバーは「単発の動作確認」用途
- **X-Forwarded-* は信頼しない**: Jetty 12 の `ForwardedRequestCustomizer` には Tomcat `RemoteIpValve` の `internalProxies` のような信頼proxy制限がないため、Jetty dev では一切登録していない。reverse proxy 経由のテストは Tomcat Docker でのみ可能 (`docker/core/server.xml`)
- `repositories.yml` の `file:` URI は `StartupProbeService` が解決
- CouchDB URL は `DatabasePreInitializer` のコンストラクタで `db.couchdb.url` system propertyから上書き

### Jetty で **動かない** もの
- TCK テスト（Tomcat ポート前提）
- QA テストスクリプト（同上）
- Playwright E2E（同上）

### Jetty で **できる** こと
- REST API endpoint の単発テスト (curl)
- UI からの操作確認 (`http://localhost:8081/core/ui/`)
- Spring Bean の wiring 確認

---

## CouchDB は常に必要か?

**ほぼYES**。

- ユニットテスト以外は全てCouchDBに依存
- 純粋なJVM内テストでもDAO層のMockが面倒な場合は CouchDB を使う方が現実的
- ローカル開発でも管理データ (typeDef, ACL, users) は CouchDB に保存される

**ユニットテストのみ** CouchDB 不要。

---

## 推奨ワークフロー

### 開発中 (高速サイクル)
1. ロジック変更 → ユニットテスト
2. UI/API 確認 → Jetty (`mvn jetty:run`)
3. Docker は CouchDB のみ起動

### PR 前 (中サイクル)
1. WAR 再ビルド + Docker再構築
2. `./qa-test.sh qa` (94テスト, ~2分)
3. 影響範囲の Playwright E2E (~5-10分)

### リリース前 (フル)
1. WAR 再ビルド + Docker再構築 + DBクリーン
2. `./tck-test-clean.sh` (38テスト, ~37分)
3. `./qa-test.sh qa`
4. `npx playwright test --project=chromium` (976テスト, ~1.7時間)
