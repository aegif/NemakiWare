# CLAUDE.md

日本語で対話してください。
ファイルの読み込みは100行毎などではなく、常に一気にまとめて読み込むようにしてください。
---
## Tool Execution Safety

- Run tools **sequentially only**; do not issue a new `tool_use` until the previous `tool_result` arrives.
- If an API error reports a missing `tool_result`, pause immediately and ask for user direction—never retry automatically.

---

## プロジェクト概要

NemakiWare は CMIS 1.1 準拠のオープンソースエンタープライズコンテンツ管理システムです。

**技術スタック**:
- Backend: Spring Framework 7, Apache Chemistry OpenCMIS, Jakarta EE 11
- Database: CouchDB 3.x
- Search: Apache Solr 9.x
- UI: React 19 + TypeScript + Vite 7 + Ant Design 5
- Server: Tomcat 11.0+ (Jakarta EE 11, Virtual Threads 有効)
- Java: 21 (必須, Virtual Threads)

**モジュール構成**:
- `core/`: メインCMISリポジトリサーバー (WAR)
- `core/src/main/webapp/ui/`: React SPA UI
- `solr/`: 検索エンジン設定
- `common/`: 共有ユーティリティ

---

## 環境セットアップ

### Java 21 設定 (必須)
```bash
export JAVA_HOME=/path/to/java-21
export PATH=$JAVA_HOME/bin:$PATH
java -version  # 21.x.x を確認
```

### 認証情報
- NemakiWare: `admin:admin`
- CouchDB: `admin:password`

---

## ビルド・デプロイ

### UIビルド
```bash
cd core/src/main/webapp/ui
npm install
npm run build
```

### Coreビルド
```bash
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
```

### Dockerデプロイ

⚠️ **重要**: `docker compose restart` は使用禁止！WARはイメージビルド時にコピーされるため、`restart` では古いWARのまま動作します。必ず `--build --force-recreate` を使用してください。

⚠️ **必須 env**: 全 compose 構成で `COUCHDB_USER` と `COUCHDB_PASSWORD` を環境変数として設定する必要があります (RC13 以降、`${VAR:?...}` で fail-fast)。LDAP / Keycloak profile では `LDAP_ADMIN_PASSWORD` / `LDAP_CONFIG_PASSWORD` も必須。

```bash
cp core/target/core.war docker/core/core.war
cd docker

# 必須環境変数 (.env ファイルか shell でセット)
export COUCHDB_USER=admin
export COUCHDB_PASSWORD=password   # 本番では必ず強いパスワードに

# 全コンテナ再構築（初回・完全リセット時）
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# coreのみ再構築（通常のデプロイ時）
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core

sleep 90  # 起動待機
```

### ヘルスチェック
```bash
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# HTTP 200 + XML が正常
```

---

## テスト実行

### QA統合テスト (推奨)
```bash
./qa-test.sh qa
# 期待: 94/94 PASS
```

### TCKテスト (全38テスト)
```bash
# 基本テスト (11テスト、約4分)
timeout 900s mvn test -Dtest=ConnectionTestGroup,BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup -f core/pom.xml -Pdevelopment

# CRUDテスト (19テスト、約20分)
timeout 1800s mvn test -Dtest=CrudTestGroup1,CrudTestGroup2 -f core/pom.xml -Pdevelopment

# クエリ + 変更ログテスト (6テスト、約12分、Solr必須)
timeout 1800s mvn test -Dtest=QueryTestGroup -f core/pom.xml -Pdevelopment
# 前提条件: Solr + NemakiWare core が Docker で起動中

# 全TCK一括実行 (38テスト、約35分)
timeout 3600s mvn test -Dtest=ConnectionTestGroup,BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup,CrudTestGroup1,CrudTestGroup2,QueryTestGroup -f core/pom.xml -Pdevelopment

# 注意: テスト失敗時はCouchDBにゴミデータ(cmistck*, test-custom-*)が残る場合がある
# FilingTestGroup は NemakiWare が Multifiling/Unfiling 非対応のためスキップ
```

### Playwrightテスト
```bash
cd core/src/main/webapp/ui
npx playwright test --project=chromium
```

---

## CMIS API

### Browser Binding (推奨)
```bash
# GET リクエスト: cmisselector パラメータ
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# POST リクエスト: cmisaction + propertyId[N]/propertyValue[N]
curl -u admin:admin -X POST \
  -F "cmisaction=createDocument" \
  -F "folderId=ROOT_FOLDER_ID" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=test.txt" \
  "http://localhost:8080/core/browser/bedroom"
```

### リポジトリ
- `bedroom`: 主要リポジトリ (テスト用)
- `canopy`: 標準リポジトリ (初期作成・UIからは非表示、それ以外は通常リポジトリと同等)

---

## UI開発

### 開発サーバー
```bash
cd core/src/main/webapp/ui
npm run dev  # http://localhost:5173
```

### i18n (多言語対応)
- 翻訳ファイル: `src/i18n/locales/{ja,en}.json`
- デフォルト言語: 日本語
- 言語設定保存: localStorage (`nemakiware-language`)

### 主要コンポーネント
- `Layout.tsx`: メインレイアウト + LanguageSwitcher
- `AuthContext.tsx`: 認証状態管理
- `cmis.ts`: CMIS APIサービス

---

## 重要な設計決定

### OpenCMIS バージョン
- 使用: `1.1.0-nemakiware` (自己ビルド Jakarta EE 対応版)
- 禁止: 1.2.0-SNAPSHOT (不安定)

### Maven systemPath 警告
- 無視可: `/lib/jakarta-converted/` 内のカスタムJARによる警告は想定内

### ACLキャッシュ
- 修正済み: `removeCmisAndContentCache()` で両キャッシュを同期クリア

### テストユーザーパスワード
- BCryptハッシュ必須（平文は拒否される）

### Virtual Threads
- Tomcat Connector: `StandardVirtualThreadExecutor` で全HTTPリクエストをVirtual Threadで処理（VTはプール上限なし、JVM管理）
- アプリケーション内ThreadPoolExecutor: ThreadFactoryを `Thread.ofVirtual().name(...).factory()` に統一
- ThreadPoolExecutor構造（キューサイズ、CallerRunsPolicy等）はバックプレッシャー維持のため変更不可
- ScheduledExecutorService（McpAuthenticationHandler）はVT非対応のためプラットフォームスレッドのまま

### CMISクライアント互換性設定
cmislib等のCMISクライアントは `/atom` にリポジトリID未指定でアクセスしてサービスドキュメントを取得します。

```properties
# nemakiware.properties
# 必須: サービスドキュメント取得時の認証用デフォルトリポジトリ
cmis.server.default.repository=bedroom
```

**動作:**
- 設定あり → 指定リポジトリで認証（YAML順序を上書き）
- 設定なし → repositories.yml の定義順で最初のリポジトリを使用
- 無効値 → WARN出力 + YAML順序フォールバック

---

## トラブルシューティング

### コンテナ起動問題
```bash
docker logs docker-core-1 --tail 50
curl -u admin:password http://localhost:5984/_all_dbs
```

### UIが更新されない
1. `npm run build` 実行
2. WAR再ビルド
3. Docker再起動 (--force-recreate)
4. ブラウザキャッシュクリア

---

## CSRF保護 (REST API)

`/core/rest/repo/...` (Jersey) および `/core/api/v1/...` (Spring MVC) 配下のstate-changing request (POST/PUT/DELETE) は `CsrfValidator.validate()` でCSRF検証される（共通ロジック）。

**バイパス条件** (いずれか1つで通過):
- `Authorization: Bearer ...` ヘッダー (非ambient credential)
- `AUTH_TOKEN` / `nemaki_auth_token` ヘッダー
- `AUTH_TOKEN_APP` / `nemaki_auth_token_app` ヘッダー
- `X-API-Key` ヘッダー
- `Origin` ヘッダーがサーバーと一致
- `Referer` ヘッダーがサーバーと一致
- `X-Requested-With: XMLHttpRequest` ヘッダー

**Basic auth は CSRF バイパスしない** — ブラウザがrealm単位で自動付与するambient credentialのため。

```bash
# curl / shell / Python で REST API を呼ぶ場合の標準パターン:
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/rest/repo/bedroom/..."

# Python requests:
requests.post(url, auth=(user, pw), headers={"X-Requested-With": "XMLHttpRequest"})
```

**注意**: `/core/browser/...` (CMIS Browser Binding) はCSRF検証なし。`/core/api/v1/...` (Spring MVC) は `CsrfInterceptor` (HandlerInterceptor) で検証される（Webhook receiver パスを除く）。

**Tomcat RemoteIpValve**: `docker/core/server.xml` に設定済み。信頼proxyからのX-Forwarded-Proto/Host/PortをservletAPI値に反映する。アプリ側ではforwardedヘッダーを自前パースしない。

---

## セキュリティステータス (2026-05-09)

- npm脆弱性: 0件 (axios 1.15.2, postcss 8.5.10 など更新)
- Maven主要依存: netty 4.1.124, logback 1.5.19, commons-io 2.20.0, HttpClient 4.5.14
- xml-apis 1.4.01: 削除 (Java 9+ の java.xml で代替)
- PDF.js CVE-2024-4367: 対応済み (react-pdf 10.0.1)
- エクスポートACLリーク: 対応済み (CAN_GET_ACL権限チェック追加)
- アーカイブDAO例外伝播: 対応済み (null返却→CmisRuntimeException)
- Webhook REST API: CMIS権限チェックに移行済み (admin限定→CAN_GET/UPDATE_PROPERTIES)
- ContentChanges 削除済み型フォールバック: 対応済み (JSONConverter型解決エラー防止)
- SAML署名ラッピング攻撃: 対応済み (Reference URI DOM解決 + duplicate-ID防御)
- SAML DEFLATE DoS: 対応済み (10MB上限 + ByteArrayOutputStream)
- Setup Wizard設定上書き防止: 対応済み (ハイドレーションゲート + エラーリトライ)
- getAdmins() fail-open: 対応済み (CouchDB障害時の合成admin返却を廃止、fail-closed)
- BigInteger depth検証: 対応済み (== 参照比較 → compareTo 値比較)
- HMAC空文字返却: 対応済み (hmacSha256 例外時にRuntimeException throw)
- プロキシヘッダー認証: 対応済み (trustedProxies 必須化 + remoteAddr null 拒否)
- Docker資格情報: 対応済み (ENV層から除去、全 compose の COUCHDB/LDAP env 必須化)
- CouchDB credential fail-closed: 対応済み (DatabasePreInitializer/StartupProbeService の admin/password fallback 撤去)
- Token 定数時間比較: 対応済み (AuthenticationServiceImpl + SetupModeGuardFilter, MessageDigest.isEqual)
- XXE hardening: 対応済み (SolrResource/SolrAllResource の checkSuccess に disallow-doctype-decl 等を適用)
- UI reverse tabnabbing 防止: 対応済み (window.open に noopener,noreferrer 明示)
- Legacy 設定削除: docker/nemakiware.properties, docker/log4j.properties, docker/ui-war/, docker/solr/solr/conf/ (admin/admin 残骸)

### Multi-replica deployment

NemakiWare 3.1.1 は **single-replica posture** で出荷。multi-replica で運用する場合の必要条件・制限・セットアップ手順は **[`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md)** に集約してあります。

要点:

- JVM ローカル状態: 認証トークン / WebAuthn challenge / MCP session / SAML binding+replay / Setup token / SAML rate limit / Webhook queue / Ingest circuit breaker / EhCache (10 サブシステム)
- 必要条件 (R1-R6): LB cookie-based sticky session、`lineage.leader-election.enabled=true`、`nemakiware.deployment.singleReplica=false` AND `stickySession=true`、全 replica で同一 properties / CouchDB / Solr
- 強く推奨 (S1-S4): `nemakiware.public.scheme=https`、RemoteIpValve、LB sticky cookie TTL ≥ `auth.token.expiration`
- 未対応 (L1-L4): Setup Wizard (single-replica で初期化)、EhCache cluster、IP rate limit (per-replica)、IMAP IDLE (leader のみ)
- bootstrap 順: 1 replica で setup wizard → 完了後 scale out
- 失敗症状からの逆引き表あり (§5)

### MCP 認証方針

- `/mcp/health`, `/mcp/info`: 匿名アクセス可（監視ツール・クライアント互換性）
- `initialize`: 匿名アクセス可（MCP プロトコル仕様上、セッション確立前に呼ばれる）
- `tools/list`: デフォルト匿名公開。`mcp.tools.list.public=false` で認証必須に変更可能
- `tools/call` (search, get_document_content 等): `McpAuthenticationHandler` で認証必須
- 監査: `auditRequestContextFilter` で `/mcp/*` の全リクエストをログ記録

```properties
# nemakiware.properties — MCP ツール一覧の公開設定
mcp.tools.list.public=true   # デフォルト: 公開（MCP クライアント互換性優先）
mcp.tools.list.public=false  # インターネット公開環境向け: 認証必須
```

---

## 現在のバージョン

**3.1.1** (2026-04-02)

### RC15 / RC3 (2026-05-14〜17) — フォルダ管理者への ingest 委譲

ブランチ: `release/3.1.1-RC3`

主要コミット系列:
- `2186a40b0` — feat: 委譲実装本体
- `6812a78ff` — docs: 設計 doc + Help + 初版 E2E
- `b87bd0282` — hardening #1: audit 拒否経路 / scheduler 防御初版 / runtime fail-closed / controller test
- `875209434` — hardening #2: 構造化 `DenialReason` / ConnectorTab 委譲列 / E2E cleanup / runbook
- `5285d706b` — hardening #3: scheduler WARN once + scheduler defence JVM test
- `<next>` — hardening #4: ancestor walk cap → property 化 / list endpoint キャッシュ / Help admin runbook

**実装済み境界**: 非 admin が `cmis:all` を持つフォルダに対して、admin が明示委譲した connector を使った **manual-only** な delegated profile を作成・編集・削除できる。admin-owned profile と scheduled ingestion はそのまま admin 専用。

#### バックエンド

- `ConnectorDefinition` に 4 フィールド追加 (POJO + バリデーション):
  - `delegated` (default `false`) — 委譲 opt-in フラグ
  - `delegateAllFolders` (default `false`) — リポジトリ全体委譲(明示要求)
  - `allowedFolderIds` — 空は **委譲不可** として扱う(誤設定での広域委譲防止)
  - `allowedPrincipalIds` — user / group 両対応 (`PrincipalService` で展開、fail-closed)
- `ImportProfileDefinition` に `createdByUserId` / `delegated` 追加。非 admin の POST/PUT は `delegated=true` + `schedulerEnabled=false` + `defaultProfile=false` を強制
- `IngestAuthorizationService` (新規):
  - effective `cmis:all` 判定 (user + groups + Anyone principal の union × `Acl.getAllAces()` を直接走査、`PermissionMapping` 設定差の影響を受けない)
  - 配下判定は **folder ID + ancestor chain** (path-prefix 不可)
  - cycle 検出 + ancestor walk cap (default **128 hop**、`nemakiware.ingest.ancestorWalk.maxHops` で override 可、`PropertyManager` 経由、無効値は default に fall back + WARN)
  - cap 到達時は fail-closed + WARN で operator に通知(深いツリーで legitimate な場合はプロパティ調整)
  - 全パス fail-closed
  - 34 unit tests (admin / cmis:all-via-user / via-group / via-Anyone / read-only / null acl / fail-closed / cycle / cap exceeded / property handling)
- `ImportProfileDefinitionController`:
  - POST: folder 解決 → `canManageProfileForFolder` → 全 `allowedConnectorIds` を `canUseConnectorForDelegatedProfile` で検証 → 非空必須(暗黙の "any connector" を拒否)
  - PUT: TOCTOU 両方向 — 既存 + 新 target folder 両方で `cmis:all` 必要、connector list も既存 + 新の両方で許可確認
  - PUT/DELETE: 非 admin は `delegated=true` の profile のみ操作可
  - GET (list): profile を iterate して `canManageProfileForFolder` で per-profile filter (フォルダツリー全走査しない)。per-request `HashMap<repo+folderId, Boolean>` キャッシュで「N profiles on K folders → ACL eval K 回」(典型 5-20×)
  - 全拒否経路 (`enforceDelegationOnCreate/Update` / GET / DELETE) は構造化 `DenialReason` enum 経由で audit に記録(20 値、契約として固定)
- `ConnectorDefinitionController.GET /summary?repositoryId=&targetFolderId=`:
  - secret / endpoint / scope 一切含まないスリム DTO
  - `cmis:all` ガード + `canUseConnectorForDelegatedProfile` フィルタ
- `ExternalIngestController` 実行時:
  - `IngestAuthorizationService` を **required dependency** (Spring 起動時 fail-fast)
  - 念のため null check で 503 (defence in depth、fail-open しない)
  - 非 admin: `targetFolderOverride` 禁止 (403)、`profileId` 必須、profile.delegated 必須、profile.repositoryId 一致必須、`cmis:all` 実行時再評価、`connectorId` は profile.allowedConnectorIds 内かつ依然委譲済みであること
- `AuditLogger.logOperation` に `details` map を受ける overload 追加。新 ops: `EXTERNAL_PROFILE_CREATED/UPDATED/DELETED`。delegated ingest path も `{delegated, actorUserId, profileId, connectorId, denialReason?}` を audit 詳細に記録
- `IngestSchedulerService`: delegated profile (schedulerEnabled=true で書かれた壊れたレコード) を defensive に skip。`ConcurrentHashMap.newKeySet()` で profileId 別に **WARN 1 回 / JVM lifetime**、以降 DEBUG。`delegated=false` への戻りで memo 削除 → 再 flip 時に WARN 復活。`pollScheduledProfiles` を package-private にして JVM test で 5 シナリオ固定 (skip / WARN once / admin 進行 / mixed list / disabled 前段スキップ)

#### UI

- `IntegrationSettings` 全体の `AdminRoute` ガードを廃止。タブ単位フィルタへ:
  - admin: 全タブ
  - 非 admin: `import-profiles` + `manual-ingest` のみ + 「委譲ビュー」notice
- `ImportProfileManagementTab`:
  - schedulerEnabled / defaultProfile スイッチは非 admin で `disabled` + tooltip
  - connector picker は `connectorMap` 由来の options-driven `Select` (admin: `tags`、非 admin: `multiple` で自由タグ禁止)
  - 非 admin は form の `targetFolderId` 変更を watch して `/connectors/summary` から map を更新。`refreshConnectorMapForFolder` は呼び出し元に map を return するので `openEdit` は閉包経由の stale state を読まずに adapter を解決する
  - 非 admin の delegated profile は `targetFolderId` 必須 + `targetFolderPath` 入力欄を非表示。委譲フローは ID 単一決定を保証(picker / cache key / audit が全て同じ resolved ID を共有)。admin は path 入力を維持(レガシー / スクリプト用)
- `ManualIngestTab`:
  - admin: 従来 (connector → profile)
  - 非 admin: 順序逆転 (profile → connector)。profile 選択後に `listConnectorSummary(repo, targetFolderId)` を呼び `profile.allowedConnectorIds` と intersect。`listConnectors()` (admin-only) は非 admin path から一切呼ばない
- i18n ja/en: `delegatedNotice` / `noAccess` / `schedulerAdminOnly` / `defaultProfileAdminOnly` / `noDelegatedConnectors` / `selectProfileFirst`

#### ドキュメント

- 設計詳細: `docs/design/connector-delegation.md` (§10 `DenialReason` reference table、§11 Troubleshooting、§8 Operator runbook)
- 操作者向けヘルプ: README.md (Permission Model セクション) / AGENTS.md (Scalability + Operations) / HelpPage に「委譲を運用する」step-by-step + 監査クエリ例

#### 検証 (本 RC 終了時点)

- 単体テスト: 165+ ingest tests pass (29 IngestAuthorizationServiceTest 中 cap-property 関連 6 含む / 11 ExternalIngestControllerGateTest / 5 IngestSchedulerDelegationSkipTest / その他)
- API E2E: 21 / 21 pass (admin + 委譲 + 非委譲 × CRUD + 実行 + TOCTOU 全網羅、live deployment 実機検証、beforeAll/afterAll で残骸 sweep)
- Maven package + UI build: green
- レビュー指摘 P1/P2/P3 全反映済み

#### 設定可能項目

| プロパティ | デフォルト | 意味 |
|---|---|---|
| `nemakiware.ingest.ancestorWalk.maxHops` | 128 | folder ancestor チェーンを辿る最大 hop 数。深いツリーで legitimate に到達できない場合に増やす |

#### 将来課題 (v2 検討、本 RC では対応しない)

- 非 admin の scheduled profile: `createdByUserId` ベースで実行時 CallContext 再構築 + 対象 folder への `cmis:all` 再評価が必要
- createdBy ユーザの非アクティブ化検知 → profile 自動 disable
- フォルダ選択 UI (ツリーピッカー — 現状はオブジェクト ID/path 手入力 + summary endpoint 経由の即時 ✓/✗ feedback)
- Admin-owned → delegated 移行 / 移管ツール (現状は delete + recreate)

### RC14 (2026-05-10) — SAML strict-mode redesign + production hardening posture

- **SAML strict mode** (`saml.require.inResponseTo`):
  - **デフォルト `false`** (back-compat 優先 — 旧 SP-initiated UI / モバイル / スクリプトクライアント互換)
  - **本番 production posture では `true` を強く推奨**:
    - サーバ側で `POST /rest/all/saml/initiate` が AuthnRequest ID を発行 + HttpOnly+Secure+SameSite=Lax binding cookie をセット
    - 受信した SAML Response の `InResponseTo` を cookie 内 binding と照合
    - 一致しない unsolicited Response / captured Response の replay を拒否
  - 移行手順:
    1. 全 SAML クライアント (UI / モバイル / スクリプト) が `/saml/initiate` を経由する flow に移行済みか確認
    2. shipped React UI は自動対応済み
    3. property を `true` に切替
- **multi-replica deployment**:
  - `SamlAuthnRequestRegistry` と `SamlReplayCache` は JVM ローカル
  - **multi-replica + SAML strict mode = sticky session 必須** (load balancer の cookie ベース sticky を有効化)
  - sticky なしで multi-replica にすると IdP callback が別 replica に届いて strict 検証失敗 + replay 防御が replica 間で共有されない
  - 警告抑止: `nemakiware.deployment.singleReplica=false` AND `nemakiware.deployment.stickySession=true` を設定
- **`nemakiware.public.scheme`** (新):
  - `auto` (default): `request.isSecure()` を信頼 (Tomcat `RemoteIpValve` 想定)
  - `https`: 公開 URL が HTTPS であることを宣言。Cookie Secure flag を**常時付与**。proxy が `X-Forwarded-Proto` を落とす misconfig は per-minute throttled WARN で surface (cookie 強度を犠牲にしない)
  - `http`: 開発専用
- **OData**:
  - `/odata/{repositoryId}/` の URL pattern を `AuthenticationFilter` が認識するように修正 (それまでは常に 401)
  - `OData servlet` で CSRF 検証 + `ODataHandlerImpl` の null debugger NPE 修正
- **その他 (RC13 から継続)**: 依存版数 (axios/postcss/netty/logback/CXF/HttpClient/commons-io/xml-apis 削除), Audit IP spoofing 防止, Webhook header injection 防止, MCP arguments redaction, Solr query injection 防止, Docker compose CouchDB credential 必須化, scheduler leader election (3 scheduler), tck-test-clean.sh 安全化, etc.

### RC13 (2026-05-09) — Security hardening
- 依存脆弱性対応:
  - axios 1.15.0→1.15.2 (13 CVE: prototype pollution / SSRF / CRLF / null byte 等)
  - postcss 8.5.6→8.5.10 (XSS in CSS Stringify, CVE-2026-41305)
  - netty 4.1.118→4.1.124.Final (CVE-2025-55163 HTTP/2 MadeYouReset DoS)
  - logback 1.5.16→1.5.19 (core/solr/cloudant-init/docker-solr 統一, CVE-2025-11226 / CVE-2026-1225)
  - commons-io 2.18.0→2.20.0 (CVE-2024-47554 ReDoS)
  - Apache HttpClient 4.5.13→4.5.14 (CVE-2020-13956 URI 解析、rest-assured 経由 leak の DM 抑え込み)
  - xml-apis 1.4.01 削除 (Java 9+ の java.xml モジュールに統合済み)
  - logback-contrib 0.1.5 (commented-out) を pom から整理
- Java fail-closed credential:
  - DatabasePreInitializer/StartupProbeService の admin/password fallback 撤去
  - placeholder 値 (OVERRIDE_VIA_SYSTEM_PROPERTY) を明示的に拒否
  - Jetty dev は core/pom.xml の jetty-maven-plugin systemProperties で admin/password を明示
- 定数時間トークン比較:
  - AuthenticationServiceImpl: session token を MessageDigest.isEqual に置換
  - SetupModeGuardFilter: X-Setup-Token を MessageDigest.isEqual に置換
- XXE hardening:
  - SolrResource.checkSuccess / SolrAllResource.checkSuccess に disallow-doctype-decl + SECURE_PROCESSING + ACCESS_EXTERNAL_DTD/SCHEMA disabled を適用
- Docker / 設定:
  - docker-compose-simple/ldap/ldap-keycloak-test/auth-test の COUCHDB_USER/PASSWORD を `${VAR:?...}` 必須化
  - LDAP_ADMIN_PASSWORD / LDAP_CONFIG_PASSWORD も同様 (idp profile 含む全 compose)
  - Atlas overlay: ATLAS_USER/PASSWORD 環境変数化 (Atlas image の admin/admin デフォルトに合わせて :- 形式)
  - docker/core/nemakiware.properties: 旧 hardcoded LDAP "adminpassword" を空文字に
  - 未使用 legacy 削除: docker/nemakiware.properties, docker/log4j.properties, docker/ui-war/, docker/solr/solr/conf/ (admin/admin 残骸)
- Test scope cleanup:
  - solr/pom.xml の junit:junit を test scope に修正
  - core dependencyManagement に junit:test を強制、mockwebserver の transitive を exclusion で除去
- UI hardening:
  - window.open(_blank) 11 箇所すべてに 'noopener,noreferrer' 明示 (reverse tabnabbing 防止)
- 検証: Maven build SUCCESS, 36 unit tests PASS, QA 94/94 PASS, npm audit 0件
- 残置 (master マージ後に再評価): Dependabot の 32 件は default branch ベース集計

### RC12 (2026-04-05)
- External Ingestion Phase 4 完成:
  - Concrete Adapters 11種 (+Box, Dropbox, Chatwork)
  - 全 adapter にチェックポイント永続化 (Gmail date, M365 receivedDateTime, Notion last_edited_time, Salesforce LastModifiedDate, Slack ts, Teams createdDateTime, Mattermost createAt, Box modified_at, Dropbox server_modified)
  - Per-request adapter throttling (rateLimitRpm → ms/request 変換)
  - IMAP IDLE リアルタイム監視 (angus-mail IMAPFolder.idle + MessageCountListener)
  - Webhook/Event-Driven Ingest:
    - IngestWebhookController: Slack Events API + Microsoft Graph changeNotification + Generic HMAC
    - Graph subscription 作成/削除 API
    - Slack/Graph/Generic 署名検証 (webhookSecret)
    - 複数プロファイル同時フェッチ (many-to-many connector/profile)
  - Job History + Dead-Letter Queue:
    - IngestJobRecord (CouchDB 永続化, RUNNING/COMPLETED/FAILED/PARTIAL, skipped 集計)
    - IngestDeadLetterRecord (CouchDB attachment 付き content 保存)
    - DLQ リトライ: archetype 別フロー自動ルーティング + skipped 自動解除
    - admin REST API + IngestJobsTab UI + SchedulerStatusTab UI
  - Typed Relationships:
    - nemaki:hasAttachment, nemaki:attachedToRecord, nemaki:derivedFromContext (Patch_IngestRelationshipTypes)
    - mail/note attachment は nemaki:hasAttachment を使用 (フォールバック: cmis:relationship)
  - 追加 Runtime Enforce:
    - defaultClassification → nemaki:classificationInfo 自動付与
    - aclSyncPolicy: none (継承切断) + copy_from_source (sourceAcl → local ACE)
    - preserveOriginalEml: raw .eml を別ドキュメントとして保存 + relationship リンク
    - content hash (SHA-256) 永続化 + 再インポート時の変更検出
    - defaultProfile: auto-resolve 時の決定論的プロファイル選択
  - 高度 Dedupe ポリシー:
    - create_new_if_parent_context_changed: externalContext の parent context 比較
    - replace_relationships_on_resync: 既存 relationship 削除 → 再作成
  - Evidence Boundary:
    - chatCaptureWindowStart/End (DATETIME), chatEvidenceScope (STRING) を chatContextMetadata に追加
  - Audit: EXTERNAL_INGEST / EXTERNAL_INGEST_FAILED 操作ログ
  - Connector secret masking: GET 応答で credentialRef/webhookSecret を [configured] にマスク
  - Scheduler validation: adapter 別必須 schedulerParams チェック (Slack channelId, Teams teamId+channelId 等)
  - Webhook URL 表示: ConnectorManagementTab にコピー可能な URL カラム
  - UI: defaultProfile トグル, defaultClassification 入力, webhookSecret パスワード入力
  - Cloud Drive 統合: canonical pipeline で nemaki:cloudDriveMetadata 自動付与 (saveCloudMetadata 後付け廃止)
  - Chatwork Adapter: Chatwork API v2, message + file 取込, messageId checkpoint, webhook 対応
  - Security: webhook secret 必須化, constant-time HMAC (MessageDigest.isEqual), filename null byte 除去
  - M365 checkpoint: receivedDateTime ベース (wall-clock 依存排除)
  - Chat canonical ID: Slack/Teams/Mattermost/Chatwork の sourceObjectId から channelId プレフィックス除去
  - 曖昧 auto-resolve: CloudDriveResource でレガシーフォールバックを明示的にバイパス
  - FetchResult.skipped: first-class カウンタとして追加 (derived 計算から脱却)
  - updatePolicy 実装: always_version_up (明示的 checkOut/checkIn), update_metadata_only, version_up_on_content_change (null hash → metadata-only)
  - Profile save-time validation: defaultConnectorId / defaultProfile 一意性を create/update 時に強制
  - Chatwork 100件ギャップ検出: checkpoint と最古返却 ID の乖離を WARN + errors に報告
  - Checkpoint 管理 API: GET/DELETE /checkpoint/{profileId} (admin 用)
  - Salesforce/Gmail checkpoint: source timestamp ベース (wall-clock 廃止)
  - resolveTargetFolderId NPE 修正: IncludeRelationships.NONE 明示
  - Spring MVC multipart: DispatcherServlet に multipart-config + StandardServletMultipartResolver 追加
  - WireMock 契約テスト: 6 adapter × auth/pagination/attachment/download/failure = 80 テスト
  - Playwright API smoke: multipart content round-trip, content hash, dry-run 副作用検証

### RC11 (2026-04-03)
- External Ingestion 完全実装:
  - SourceArchetype 5類型 (FILE_SHARE, COMPOUND_NOTE, CHAT_CONTEXT, BUSINESS_RECORD, MESSAGE_CONTEXT)
  - ConnectorDefinition / ImportProfileDefinition (CouchDB CRUD + admin REST API)
  - CanonicalImportService: profile/connector バリデーション → source-identity dedupe → 作成/版更新 → メタデータ付与 → lineage emit
  - ExternalIngestController: JSON + multipart/form-data 対応、archetype auto-detect、HTTP ステータス分類
  - 全 Profile フィールド runtime enforce (dedupePolicy, updatePolicy, versioningPolicy, secondaryTypeIds, retentionDays, relationshipPolicy, aclSyncPolicy, schedulerEnabled)
- Secondary Types (5種):
  - nemaki:externalIntegration + sourceFields (sourceArchetype/sourceSystem/sourceObjectId等)
  - nemaki:messageMetadata (internetMessageId, mailSubject, mailFrom, mailTo, mailSentAt等 11プロパティ)
  - nemaki:noteMetadata (notePageId, notePageUrl, noteWorkspaceId, noteAuthor等 8プロパティ)
  - nemaki:businessRecordMetadata (recordType, recordId, recordStatus, processInstanceId等 8プロパティ)
  - nemaki:chatContextMetadata (chatChannelId, chatThreadId, chatParticipants等 8プロパティ)
- Archetype 別 Import Flow:
  - FILE_SHARE: execute() (標準パイプライン)
  - MESSAGE_CONTEXT: executeMailImport() (.eml MIME パース → 本文+添付分離 → messageMetadata → direct relationship)
  - COMPOUND_NOTE: executeNoteImport() (ページ本文 → noteMetadata → 添付個別取込 → direct relationship)
  - BUSINESS_RECORD: executeBusinessRecordImport() (businessRecordMetadata 自動付与)
  - CHAT_CONTEXT: executeChatContextImport() (chatContextMetadata 自動付与)
- IMAP Adapter: MailMessageParser (.eml パーサー) + ImapConnectorAdapter (IMAP/IMAPS接続)
- file_share 一般化: CloudDriveResource → CanonicalImportService 経由 (レガシーフォールバック deprecation warning付き)
- 管理 UI: コネクタ管理 + インポートプロファイル管理 + 手動インポート実行タブ (全 i18n ja/en)
- IngestSchedulerService: 定期同期スケジューラ基盤 + admin API (/v1/admin/ingest-scheduler/status, /trigger)
- 統一ディスパッチ: executeFetch() が archetype + sourceSystem で全 adapter に自動ルーティング
- Concrete Adapters (8種):
  - IMAP (ImapConnectorAdapter) — IMAP/IMAPS + UIDVALIDITY checkpoint
  - Gmail (GmailConnectorAdapter) — Gmail API + base64url .eml
  - M365 Mail (M365MailConnectorAdapter) — Graph API /messages/$value
  - Notion (NotionConnectorAdapter) — blocks→HTML変換 + ページネーション + ファイル抽出
  - Salesforce (SalesforceConnectorAdapter) — SOQL + レコード取得
  - Slack (SlackConnectorAdapter) — conversations.history + ファイルDL
  - Teams (TeamsConnectorAdapter) — Graph API channels/messages + ファイルDL
  - Mattermost (MattermostConnectorAdapter) — REST API v4 posts + ファイルDL
- 定期ポーリング: ScheduledExecutorService (5分間隔, daemon thread)
- Content hash 比較: version_up_on_content_change で SHA-256 ハッシュ検証
- Persisted idempotency: CouchDB に完了記録永続化
- デフォルト connector/profile 自動作成パッチ (Google Drive + OneDrive)

### RC10 (2026-04-01)
- マルチカタログバックエンド: MetadataCatalogConnectionResolver, CatalogBackendKind enum, Atlas/Dataplex 設定タブ
- カスタムプロパティマッピング: リポジトリ単位で CMIS カスタムプロパティを Purview/Atlas/Dataplex にマッピング
  - CatalogPropertyMappingResolver (repository-scoped 保存, TypeService 実行時型解決)
  - PurviewSchemaPayloadFactory: カスタム attributeDefs 動的生成 (全リポジトリ union)
  - PurviewEntityPayloadFactory: カスタムプロパティ値の schema-aligned payload 生成
  - 予約属性名・空名・型衝突バリデーション (保存時拒否)
  - UI: プロパティマッピングタブ (タイプ選択 → プロパティ on/off → カタログ属性名設定)
- レガシー purview.auth.type=basic → atlas.* 自動移行 (source-aware idempotency)
- デュアルバックエンド警告 (GET/PUT 両方, i18n 対応)
- Governance API: 非管理者向け公開エンドポイント追加
- E2E テスト: Microsoft タブロケータ曖昧性修正, Atlas E2E スキップ条件改善
- Cloud Sync Lineage: 手動クリーンアップでの shared stableKey external asset 保護

### RC9 (2026-03-16)
- SAML 2.0認証: POST binding ACS (SamlAcsServlet — sessionStorageブリッジ)
- SAML 2.0認証: SLO LogoutRequest生成 (NameID付きSAMLLogoutRequest)
- SAML署名検証: XML署名ラッピング攻撃対策 (Reference URI DOM解決 + duplicate-ID防御)
- SAML DEFLATE: ByteArrayOutputStream + 10MB上限 (DoS防止)
- Setup Wizard: 既存設定ハイドレーションゲート (ローディング/エラー/リトライ状態)
- Setup Wizard: SAML設定バリデーション追加 (IdP SSO URL/証明書必須チェック)
- Setup Wizard: `[configured]`プレースホルダ round-trip対応
- authConfig.ts: インターフェース補完 (googleEnabled/microsoftEnabled)
- VectorConfig.type: union型バリデーション追加 (TS2322修正)

### RC8 (2026-03-08)
- Setup Wizard: "bedroom" ハードコーディング除去 — _all_dbs ベース動的リポジトリ検出
- Setup Wizard: StartupProbeService/DatabasePreInitializer/SetupAdminResource/SetupApplyResource の全リポジトリ対応
- Setup Wizard: ProbeStep.tsx bedroom 固定参照除去
- TypeResource.create() 並行リクエスト衝突修正 (500→409 CONFLICT)
- WebAuthn パッチ常時実行化 (Patch_WebAuthnCredentialViews.apply() idempotent override)
- WebAuthn CouchDB ビューを bedroom_init.dump に追加 (新規インストール対応)
- npm 脆弱性 0 件達成 (dompurify 3.3.2 override + immutable 3.8.3)
- Playwright E2E テスト品質改善 (827 passed / 0 failed / 74 skipped)
  - type-definition-upload: beforeEach 直接URL遷移に修正
  - type-rest-api: 並行作成テスト厳密化 (409 CONFLICT 検証)
  - server-cascade-delete: ACL テスト test.skip→expect 検証に変更
- passkey テスト安定性向上

### RC7 (2026-03-05)
- OOM修正: getLatestChange() 全件ロード→limit=1最適化 (CouchDB changesByToken 78k行対応)
- 固定ROOT_FOLDER_ID除去: cmis.ts/DocumentList/DocumentViewer/テスト12ファイルから動的解決に移行
- Virtual Threads標準有効化 (Tomcat StandardVirtualThreadExecutor + アプリ内ThreadFactory統一)
- PropertyValue @JsonIgnore + ApiCrudSmokeIT 実API検証
- Playwright E2Eテスト品質改善 (832 passed / 0 failed / 69 skipped)
  - 管理画面ナビゲーション競合状態修正 (retry-navigate)
  - Solrポーリングタイムアウト拡張 (30s→60s)
  - API事前チェックによるテストユーザー存在確認 (120sタイムアウト回避)
  - Cleanup テスト削除失敗検知 (fetchレスポンス検証追加)
  - バルク削除セレクタ修正 (行ボタン誤マッチ防止 + 多言語対応)
  - OIDC skipガード分離 (サーバー到達性 vs クライアント設定)
- OOM負荷テストスクリプト追加 (7テスト、ピークヒープ1.26GB/3GB)
- React 19互換パッチ適用

### RC6 (2026-03-03)
- Dependabotアラート対応 (全モジュール依存関係更新)
- SLF4J 1.7→2.0バージョン統一
- レガシーファイル大規模整理 (49ファイル削除)
- レガシーモジュール削除

### RC5 (2026-03-01)
- systemPath依存をGitHub Packages通常解決に移行
- OpenCMIS同梱ソース切り離し
- SLF4Jバインディング一本化 (logback-classic)

### RC1-RC4 (〜2026-02-28)
- CMIS ContentChanges API 実装 (Cloudant SDK Document デシリアライゼーション修正、changeLogToken 数値キー対応)
- ContentChanges イベント欠落防止 + 削除済み型フォールバック
- CMIS 必須プロパティ null 安全化 (epoch フォールバック)
- childByName ビュー HashMap→LinkedHashMap 修正
- Solr 多言語検索 (text_ja + text_en デュアルインデックス)
- パフォーマンス改善 (deleteTree 並列化、getChildByName O(1) ビュー最適化)
- クラウド統合 (Google Workspace / Microsoft Entra ID ディレクトリ同期、Cloud Drive連携)
- OIDC認証 (Google / Microsoft)
- 多言語対応 (日本語/英語)
- タイプ管理機能強化
- React 19 + Vite 7 移行完了
- インポート/エクスポート機能改善 (同名上書き、リレーションシップ対応、ID読替)
- Webhook機能 (CMIS権限チェック、CHILD_BATCH配送)
- MCP (Model Context Protocol) サーバー
- RAGセマンティック検索
- アーカイブ管理強化 (検索・一括操作・ダウンロード)
- WebAuthnパスキー認証
- サーバーサイドページネーション + スケーラビリティ改善

---

## 関連ドキュメント

- `AGENTS.md`: 開発者向け詳細ガイド
- `README.md`: プロジェクト概要
- `docs/ARCHITECTURE.md`: システムアーキテクチャ概要
- `docs/CLOUD_INTEGRATION.md`: クラウド統合設定ガイド
- `docs/design/webhook-feature-proposal.md`: Webhook機能設計書
- `docs/AWS-DEPLOYMENT-GUIDE.md`: AWS本番デプロイガイド
