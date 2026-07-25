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

### 外部取込 (External Ingest) の skip ルール

コネクタ取込で「値のない添付」を文書として永続化しないための共通ルール。
single choke point は `CanonicalImportServiceImpl.execute()`。全コネクタの
添付はここを通る。

**何を skip するか:**
- **0バイト添付**: `contentBytes.length == 0` かつ `sourceObjectType == "attachment"`
  のみ。message/page/record 本体と file-share 本体は対象外（本体は
  metadata で価値を持ち、0バイトの Box/Dropbox file は利用者が意図的に
  置いた placeholder の可能性があるため落とさない）
- **OS/desktop 擬似ファイル**: `FetchSupport.isPseudoSystemFile(fileName)` で
  判定（`.textclipping` / `.ds_store`、`strip()` で前後空白除去 + 大小無視）。
  非0バイトなのでサイズ check では捕まらず、ファイル名で filter。全コネクタ
  backstop。Notion 等は `extractFiles()` で download 前に早期除外

**skip の表現と伝播 (`ExternalIngestResult`):**
- `skipped()` の結果は errors 空のため **`isSuccess()` も true** を返す。
  よって orchestrator のカウントは **必ず `skipped()` を先に判定**してから
  `isSuccess()`（先に isSuccess を見ると skip が imported に誤算入される）
- archetype wrapper（`executeChatContextImport` / `executeMailImport` /
  `executeNoteImport` / `executeBusinessRecordImport`）は execute() の skip を
  握り潰さない:
  - **empty/pseudo skip (objectId == null)**: 装飾対象がないので即 return
    （null objectId への metadata 付与 / getContent を回避）
  - **dedupe skip (objectId != null)**: 既存オブジェクトへの
    metadata/relationship 再適用（冪等）と添付 retry を継続しつつ、最終
    return で `skipped` フラグ / `skipReason` を保持（`skipped=false` ハード
    コード禁止）
  - note `files_and_body` のみ「本体 dedupe だが新規添付 import あり」→
    imported 扱い（複合 import の性質上）。mail は本体 skip を全体 skip と
    同一視

**新コネクタ / 新 orchestrator を追加するときの遵守事項:**
1. 添付は `execute()`（または archetype wrapper）経由で取り込む（choke point
   を迂回しない）
2. カウントは `if (result.skipped()) skipped++; else if (result.isSuccess())
   imported++; else error`
3. 新 archetype wrapper を作る場合、execute() の skip を上記2分岐で扱い、
   最終 return で skip フラグを落とさない

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

**CMIS Browser Binding (`/core/browser/...`) の CSRF**: 完全な token / `X-Requested-With`
必須化は非ブラウザ CMIS クライアント (cmislib / TCK / スクリプト) を壊すため行わないが、
POST に対し `CsrfValidator.validateBrowserBindingCsrf` による**軽量チェック**を適用する
(v3.3〜)。**`Sec-Fetch-Site: cross-site` を拒否**し、**`Origin` があれば同一オリジン必須**
(cross-origin は 403)。`Origin`/`Sec-Fetch-Site` の**どちらも持たない**リクエスト
(非ブラウザ CMIS クライアント) は**従来どおり許可**。これによりブラウザ由来の cross-site
偽装 POST を遮断しつつ CMIS クライアント互換を維持する。curl 等の直接呼び出しは
ヘッダーを送らないので影響なし。`/core/api/v1/...` (Spring MVC) は `CsrfInterceptor`
(HandlerInterceptor) で完全検証される（Webhook receiver パスを除く）。

**Tomcat RemoteIpValve**: `docker/core/server.xml` に設定済み。信頼proxyからのX-Forwarded-Proto/Host/PortをservletAPI値に反映する。アプリ側ではforwardedヘッダーを自前パースしない。

---

## セキュリティステータス (2026-06-02)

- Dependabot 棚卸し (2026-07-07, post-3.2.3): open 32 件の実体は 3 件で全て解消。
  (1) jackson-databind 2.21.x の CVE 群 (high 8 + medium 20 = 4 pom × 複数
  advisory の重複集計) → jackson-bom + 直接ピン計 9 箇所を 2.21.5 に統一。
  (2) logback-core ≤1.5.32 (low 3) → 1.5.37 に統一 (core/solr/docker-solr/
  cloudant-init)。(3) esbuild 0.27.3 (low 1, dev 限定) → npm overrides で
  0.28.1 (vite build + vitest 191/191 互換確認、npm audit 0 件)。
  検証: 単体 471/471、QA 統合 94/94、RAG/MCP スモーク
- CI 修正 (2026-07-07): `ui-tests.yml` / `playwright.yml` の timeout-minutes
  (60/90) がフル Playwright (~1.3h + CI ビルド ~30min) に不足し master push
  毎に cancelled → 180 に引き上げ。playwright.yml は 1h38m で完走 (935 passed
  / 1 governance-UI flake / 4 flaky-retry-pass / 93 skipped) したが、同一
  スイートを重複実行していた ui-tests.yml (手動サーバ起動経路) は 180 分でも
  完走不能 → **playwright.yml に一本化** (ui-tests.yml 削除、トリガーパスに
  core/src/main/java・core/pom.xml・common・solr を追加してカバレッジ維持)
- Codex audit follow-up batch 2 — DoS / leak / SSRF / Solr injection (2026-06-02, commit `25870fb58`): 対応済み (Codex 支援の repo-wide 監査で発見した既存 finding 7 件。いずれも RC6.11-6.13 XXE とは別件。(1) `AuthenticationUtil` 空パスワード fail-open → blank candidate/hash で常に false に fail-closed。(2) export パストラバーサル → `ImportExportUtils.sanitizeExportName` + resolve-under-target containment を ZipExporter/FilesystemExporter/selected-object export に適用。(3) webhook URL userinfo 漏洩 → 設定保存時 + testWebhook + dispatcher の 3 層で `user:pass@host` を拒否、credential が storage/log/API に到達しない。(4) ingest stream 無制限 buffer → `readBounded` で 100MB cap (手動 multipart と整合)。(5) MCP debug ログが password/apiKey/sessionToken を含む全 request 出力 → method+id のみに。(6) RAG 類似検索が元文書 read 権限未確認でベクトル取得 → seed lookup に reader ACL filter 適用。(7) secondary-type WHERE の Solr injection → equals/not-equals/IN/secondaryObjectTypeIds を `escapeAndQuote` phrase 化、LIKE は char-by-char で wildcard 保持 + 空白含む全文字エスケープ。加えて FROM 句の `repository_id` / `objecttype` フィルタ連結 (`:` のみ escape だった) も `escapeAndQuote` 化。(8) LDAP `Context.REFERRAL` follow→ignore で referral SSRF/credential 転送防止。(9) Purview/Atlas endpoint SSRF → opt-in `SsrfGuard.assertOutboundUrlAllowed` を `nemakiware.security.outbound.validateInternal` (default false) 下で IntegrationSettings 保存時に適用。595/595 PASS、source-tree NUL scan 0 hits/1687 files。Codex 複数ラウンドで webhook userinfo の delivery-log 漏洩 + Solr の operator injection を捕捉、最終 blocker-free 判定。CMIS query 実機検証で FROM/WHERE/LIKE 正常)
- Codex audit follow-up batch 1 — access-control / SSRF / CSRF (2026-06-01, commit `398a5b6a0`): 対応済み (P1 4 件。(t6) `ObjectServiceImpl.createDocumentFromSource` がコピー元 read 権限未確認 → `CAN_GET_PROPERTIES_OBJECT` + `CAN_VIEW_CONTENT_OBJECT` 追加 (ACL バイパス/IDOR、OData Copy も同経路)。(t3) webhook 起点 fetch が delegated profile 認可未再評価 → `IngestSchedulerService.authorizeDelegatedFetch` 抽出し webhook path で creator active/cmis:all/connector delegation を再評価、合成 CallContext で実行。(t7) `/api/v1/cmis/*` (Jersey) に CSRF 未適用 → `ApiCsrfFilter` 新設 (Spring MVC 側 CsrfInterceptor と同じ CsrfValidator)。(t1) SAML strict mode が未署名 outer Response の InResponseTo を binding に使用 → 署名カバー由来のみ許可。377/377 PASS、Codex 検証済み)
- 新 property `nemakiware.security.outbound.validateInternal` (default false): admin 設定の外部カタログ endpoint (Purview/Atlas) を internet-facing 配備で opt-in SSRF 検証。internal/on-prem endpoint を既定で壊さない
- ACP import XXE (CWE-611) — `ZipImporter` SAXReader 非堅牢化による任意ファイル読取 + SSRF (RC6.11): 対応済み (`importAcpFormat` の `SAXReader` に `disallow-doctype-decl` + `external-general-entities=false` + `external-parameter-entities=false` を設定。修正前は cmis:write 1 個を持つ非 admin user が `<!DOCTYPE r [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>` 入り ACP zip を upload して `/etc/passwd` を folder name として永続化可能、CMIS API で読み戻せる read-capable XXE。修正後は "DOCTYPE is disallowed" error で reject、benign ACP は引き続き import 可。GHSA reporter tonghuaroot。+4 regression test `ZipImporterXxeTest`、focused 25-class 377/377 PASS。Repo-wide audit 実施 — 他の `SAXReader`/`DocumentBuilderFactory` sink (TypeResource / AuthTokenResource / SolrResource / SolrAllResource / SamlSignatureVerifier) は RC13 既に hardening 済)
- Connector SSRF — AdapterHttpClient Host header preservation on HTTP IP-pin + Javadoc honesty (RC6.9): 対応済み (HTTP IP-pin が `Host: <IP>` 送信で shared-vhost HTTP deployment を misroute する RC6.8 post-tag P3 compat caveat を fix。`pinRequestToValidatedAddress` で URI rewrite + `Host: <original-hostname[:port]>` を明示 set、JDK の restricted-headers check を `-Djdk.httpclient.allowRestrictedHeaders=host` の JVM property で escape (Dockerfile + surefire argLine 両方に追加、AdapterHttpClient の static init で defensive fallback)。Javadoc も RC6.8 post-tag P2 の HTTPS residual TCP-connect SSRF 注記と整合化。+2 regression tests、計 343/343 PASS)
- Connector SSRF — AdapterHttpClient deeper closure: DNS rebinding pin (HTTP fully closed、HTTPS は TLS-bounded で TCP-connect class が residual) + runtime revalidation + multi-hop redirect resolve (RC6.8): 対応済み (`sendWithRetry`/`sendWithRedirectValidation` で send 時に `pinRequestToValidatedAddress` 経由で再 resolve + validate、HTTP は URI を validated IP literal に rewrite で network 層完全閉、HTTPS は再 validate のみ + TLS cert verification で data-exchange SSRF を遮断するが TCP-connect SSRF が microsecond race window で残存 — Medium 残余として §6 に記録 + 将来の custom SocketFactory が real fix。Mattermost/Salesforce orchestrator に explicit `validateExternalUrl` 追加。Multi-hop redirect で `currentRequest.uri().resolve(location)` に修正。+5 regression tests、計 265/265 PASS。Compat 注意: HTTP IP-pin は `Host: <IP>` 送信のため shared-vhost HTTP deployment が misroute する可能性 — §6 に Medium 互換性 risk として記録)
- Connector SSRF — AdapterHttpClient horizontal fix (RC6.7): 対応済み (`AdapterHttpClient.validateExternalUrl` に RC6.5+RC6.6 と同一の `isAddressSafe` + `extractEmbeddedIpv4` 移植。11 connector adapter / ConnectorDefinitionServiceImpl / IngestWebhookController から呼ばれる全 outbound HTTP path を保護。SHARED HttpClient redirect 設定を NORMAL → NEVER、relative Location の元 URI resolve も追加。+3 regression tests、78/78 PASS for SSRF surface)
- Webhook SSRF — IPv4 special-use 追加 block + Teredo + RFC 6052 /48 NAT64 (RC6.6): 対応済み (`HttpWebhookDispatcher` に IPv4 `0/8` + `100.64/10` + `192.0.0/24` + `198.18/15` + `240/4` + `255.255.255.255` を追加、IPv6 transition extractor に `64:ff9b:1::/48` の RFC 6052 §2.2 /48 layout + Teredo `2001::/32` を追加。+7 regression tests、計 59/59 PASS)
- Webhook SSRF — IPv6 transition wrap bypass (RC6.5): 対応済み (`HttpWebhookDispatcher` で NAT64 `64:ff9b::/96` + `64:ff9b:1::/48` / 6to4 `2002::/16` / IPv4-compatible `::a.b.c.d` から embedded IPv4 を抽出 → 再分類で loopback / RFC 1918 / link-local 169.254 を block。外部 reporter tonghuaroot 経由の GHSA、PoC で 5 形式すべて bypass を確認、修正後すべて block + 15 regression tests 追加)
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

**3.3.0** (2026-07-22、`deps/v3.3-breaking-majors` → master 予定) — breaking-major
依存アップリフト (Olingo 5.0 / Solr・Lucene 10 / netty 4.2 / react-router 7 /
antd 6) + ネイティブ arm64 スタック (TEI/Atlas) + OData バインディング修復。
既存 CouchDB view/schema/2.4 データ持ち越しパスは無変更。**唯一の追加**は後続の
ACL-in-Solr reconciliation キュー (`nemaki_conf` に新 record type
`searchIndexAclReindexTask` + Mango index を追加する `Patch_SearchIndexReconcile*`。
既存 view/2.4 持ち越しには非タッチ。下記「reconciliation キュー」節参照)。
バージョン反映箇所は 3.2.1 以降と同一。下記 3.3.0 節。

### 3.3.0 (2026-07-22) — breaking-major 依存 + arm64 + OData 修復

ブランチ: `deps/v3.3-breaking-majors` (off `master`)、統合検証は
`test/v3.3-arm64-full` (deps + infra/tei-arm64-native + infra/atlas-arm64-native
のマージ)。既存 schema/view/2.4 持ち越しパスは無変更 — 全変更は依存・コンテナ・
OData/ランタイムコードのみ。**例外**: 後続の ACL-in-Solr reconciliation キューが
`nemaki_conf` に新 record type + Mango index を追加(既存 view/2.4 パスには非タッチ)。

**breaking-major 依存**:
- **Olingo (OData) 4.10 → 5.0** (全 6 モジュール、Java17+/jakarta.servlet)
- **Solr/Lucene 9 → 10** (solr-solrj 10.0.0、lucene 10.3.2)。`HttpSolrClient`/
  `Http2SolrClient` 廃止 → `HttpJdkSolrClient` + `useHttp1_1(true)` (Jetty 12 との
  HTTP/2 RST_STREAM 回避)。Solr 10 は SolrCloud 既定のためコンテナは
  `solr-foreground --user-managed`。旧 fat-jar solr モジュールを reactor から除去
- **netty 4.1 → 4.2** (netty-bom 4.2.16.Final)、**react-router-dom 6 → 7**
  (HashRouter)、**antd 5 → 6** (@ant-design/icons 6)、Tier1/2 (jakarta.annotation 3
  / i18next 26 / jsdom 29)

**OData バインディング修復** (`ODataServlet` / `CmisEdmProvider` /
`CmisEntity*Processor` / 新 `ODataExceptions`): エンティティセット読取が常に
「Function not found in URI」400 だった根本原因 (= `CmisFunctionProcessor` が同一
Olingo インターフェースを実装し登録衝突で上書き。Olingo 4.10/5.0 両方で再現＝
pre-existing) を解消。関数 URI を委譲して 1 インターフェース 1 プロセッサ化、
手製 `ODataHandlerImpl` を jakarta ネイティブ `ODataHttpHandler`+`setSplit(1)` に
置換、unbound 関数を FunctionImport 公開、CMIS 例外→正しい HTTP コード
(409/400/404/403/405)、`@odata.count` 常時 emit、`$expand=children` の null Holder
NPE 修正。検証: OData IT **65/65**、Olingo **client 4/4**、`$metadata` が OASIS
OData 4.0 CSDL **XSD 適合**、conformance チェックリスト **21/21** (`tools/odata-conformance/`)。

**ネイティブ arm64 イメージ**:
- **TEI** (`docker/tei/Dockerfile.arm64`): MKL 除去・`ort,candle` バックエンドで
  ネイティブビルド (同一 `/embed` API)、非root、`NEMAKI_TEI_IMAGE`/`_PLATFORM` で opt-in
- **Atlas** (`docker/atlas/Dockerfile.arm64`): Atlas 2.3.0 ソースビルド。死んだ
  expired-cert Hortonworks repo を clojars にミラーし **Maven TLS 検証を全 repo で
  維持** (グローバル無効化を撤廃)、Atlas プロセス死亡時に**コンテナ終了** (supervision
  CMD + `restart: unless-stopped`)、非root、ビルド context を SHA ピン、
  `NEMAKI_ATLAS_IMAGE`/`_PLATFORM` で opt-in。両 overlay はポートを 127.0.0.1 bind

**セキュリティ/衛生**: npm audit HIGH 解消 (brace-expansion 5.0.7 / js-yaml 4.3.0)、
誤コミットされていた Solr 実行データ (index/tlog) を履歴除去 + gitignore。

**検証**: v3.3 ツリーで Java 単体 + フル TCK green (Connection/Basics/Control/
Versioning/CRUD1/CRUD2/Query/Types 全通過。Types は E2E 残骸カスタム型
(queryName null) の掃除が前提＝データ汚染で非回帰)、UI `tsc`+vite build クリーン、
vitest 191/191、OData 65/65 + Olingo client 4/4 + CSDL XSD 適合 + conformance
21/21、5 サービス arm64 スタック (core + CouchDB + Solr10 + ネイティブ TEI +
ネイティブ Atlas) healthy で CMIS/RAG/OData 全稼働。

**既知の制限 (適合性違反ではない)**: unbound 関数は全宣言パラメータ必須 (OData
オーバーロード解決)、`GetObjectByPath` のパス内 `/` は Tomcat のエンコード済み
スラッシュ既定拒否 (Query で代替)、`Types`/`Users`/`Groups` は EDM 宣言のみで
データ未配線 (空 200)。

**アップグレード注意**: (1) **RAG index 再構築必須** (Solr 10 / TEI 差替時)。
(2) 非root TEI 採用時は root 所有の `tei_cache` volume を再作成 (初回モデル再 DL)。
(3) dev/eval overlay の 127.0.0.1 bind でホスト外到達不可に (常設デモ等は要確認)。

**レビュー remediation (2件のレビューを受けて追加修正)**:
- **[P1] OData/CMIS query のページング・$count 修復**: Solr で先ページング→ACL
  フィルタのため `$count` がページ生存数・`$top`/`$skip` が空穴になっていた
  (`/Documents?$count=true&$top=1`→count=0/0件で再現)。**Solr を上限付き全取得
  →ACL 全件フィルタ→numItems=認可後総件数→メモリ内ページング**に変更
  (`SolrQueryProcessor`/`CompileServiceImpl`。CMIS Browser query も同時修復)。
  上限は `-Dnemakiware.cmis.query.aclScanMaxRows` (既定 10000)。**超過時は 400
  エラー** (統合レビュー P1 で「下限値 + `hasMoreItems=true`」は到達不能を隠す
  欠陥と判定され是正。下記「ORDER BY×ページング」節末尾参照)。
- **[P1] GetContentChanges の nextLink**: token のみで追従 400 だったのを全6
  パラメータ付与。
- **[P1] npm audit ゲート**: immutable→4.3.9 / dompurify→3.4.12 を
  swagger-ui-react override で `--omit=dev --audit-level=high` を 0 件に。
- **[P1] 検証の甘さ**: Olingo IT / conformance_check.py が空ページを PASS して
  いたのを、count=認可後総件数・ページ非空を検証するよう厳格化。
- **[P2] OData 500 で内部例外 message を露出→汎用文言 + 相関ID (サーバーログ)**。
- **[P1] atlas overlay の CATALINA_OPTS 置換を撤廃**: Atlas opts を
  `JAVA_TOOL_OPTIONS` (JVM が CATALINA_OPTS に加えて読む) へ分離。base 値
  (heap/nemakiware.properties/CouchDB 資格情報/solr.*) が保持され、
  `COUCHDB_USER/PASSWORD` 以外の事前 export 不要に。
- **残 P2 (次イテレーション)**: Atlas ソース tarball / TEI タグの供給チェーン
  検証 (checksum/署名)、OData IT の CI ゲート化 (現状 `@Disabled`)。

**レビュー remediation (3件目のレビュー: ORDER BY×ページング)**:
- **[P1] ORDER BY / 既定順がページング前に適用されず、ページ内だけソートされて
  いた**: `SolrQueryProcessor` は ACL フィルタ後に `permitted.subList(skip,…)` で
  先にページを切り、その後 `compileObjectDataListForSearchResult` 内でページ内
  だけ `sortUtil.sort` していた。`maxItems=1` だとソートが実質 no-op になり、
  ページ列は Solr の `modified desc` 順のまま → ページ連結が全件 ORDER BY 順と
  不一致 (`ORDER BY cmis:name ASC` + `maxItems=1&skipCount=0..3` で再現)。加えて
  `capability.extended.orderBy.default=cmis:creationDate DESC` の既定順も同様に
  壊れていた。**修正**: `CompileService.sortContentsForSearchResult` を新設し、
  ACL 認可済み全件を ORDER BY (または既定順) でソート**してから** subList、ページ
  compile には `orderBy="NONE"` を渡して再ソートを抑止。軽量 compile (プロパティ
  のみ) は `objectDataCache` にキャッシュされるので、ページ compile は AA/ACL のみ
  再計算。CMIS Browser / OData 双方の query 経路を同時修復。
- **[P1] OData `$orderby` が完全に無視されていた (既存バグ)**: `convertOrderByToClause`
  が `item.getExpression().toString()` を使っており、`$orderby=name` の `Member` 式で
  生プロパティ名にならず `mapODataPropertyToCmis` が null → ORDER BY 句が付かず
  既定順にフォールバック。`$filter` と同じく `member.getResourcePath().getUriResourceParts()
  .get(0)` からプロパティ名を抽出するよう修正。`name asc/desc`・`creationDate` が
  実際に反映されることを実機確認。
- **検証**: 実機 pre/post (旧ビルドでバグ再現→修正ビルドで全ケース一致)、Olingo
  client IT **6/6** (新規 `olingoClientOrderByIsAppliedAndOrderedPagingMatches` =
  desc==reverse(asc) + 順序付き `$top=1` ページ連結==全件順)、OData 機能 IT
  **65/65**、conformance `$orderby` を「HTTP 200 のみ」から実際の並び替え検証に
  厳格化し **25/25**、CMIS Browser ページング無回帰。
- **ORDER BY×ページングの相互作用は解消済み** (旧「残 P2」から除外)。

**P2 remediation (旧「残 P2」の #1 / #2 を対応)**:
1. **OData IT を CI ゲート化 (完了)**: `integration-tests.yml` に `odata-tests`
   ジョブを追加。ライブスタックを起動 → `ci-complete-setup.sh` → 新設
   `scripts/ci-seed-odata-docs.sh` で文書をシード (CMIS Browser Binding、
   Solr 索引待ち) → `ODataDocumentsIT`/`ODataFoldersIT`/`ODataOlingoClientValidationIT`
   を `-Djunit.jupiter.conditions.deactivate='*'` で実行 (計 71 テスト)。シードは
   ページング/`$orderby` 回帰テストの `assumeTrue(total>=2)`/`assumeTrue(distinct)`
   がフレッシュ DB でスキップして gate が空振りするのを防ぐため。**fail-closed**:
   固定名 (`odata-ci-seed-{a,b,c}.txt`) を冪等作成 (409 許容) し、query 経由で
   件数≥3・全シード名 queryable・全名 distinct を満たすまで待機、満たせなければ
   `exit 1` (空振り PASS を旧 empty-early-return と同型欠陥として排除)。
   master/`release/**`/PR で常時実行。
2. **供給チェーン検証 (完了)**: arm64 ビルドの外部取得物を固定ハッシュ/コミットに
   ピンして検証で fail-closed 化。
   - **Atlas**: `Dockerfile.arm64` に `ARG ATLAS_SRC_SHA512` を追加し、
     `apache-atlas-2.3.0-sources.tar.gz` を `sha512sum -c` で検証 (Apache 公式
     `downloads`/`archive` 両ソースが一致する値をピン)。改ざんミラー/MITM を遮断。
   - **TEI**: `build-arm64.sh` に `TEI_EXPECTED_COMMIT` を追加し、`git clone
     --branch v1.7.4` (可変タグ) の後 `rev-parse HEAD` が固定コミット
     `6e900af…` と一致するか検証 (不一致で abort)。ls-remote + GitHub API 両者が
     一致するコミットをピン。
**統合レビュー remediation (P1 ×2、マージ前ブロッカー)**:
- **[P1] `aclScanMaxRows` 到達性欠陥を是正 (旧「下限 + hasMoreItems=true」を撤回)**:
  `SolrQueryProcessor` は `START=0/ROWS=cap` で先頭 cap 件だけ取得しメモリ内で
  認可・ソート・ページングするため、cap 超過時に (a) cap+1 件目以降が永久に
  取得不能、(b) それでも `hasMoreItems=true` を返してページングクライアントを
  無限ループさせ、(c) `$orderby` が先頭 cap 件だけの並べ替えで全体順序が誤り、
  (d) `$top=1` でも cap 件の getContent+ACL+ObjectData+lock で低権限 DoS、という
  欠陥だった。**修正**: Solr の `numFound`(認可前) が cap を超えたら **getContent
  ループ前に 400 (`invalidArgument`)** で明確に拒否 (到達不能を嘘の hasMoreItems で
  隠さない + DoS を早期打ち切り)。cap 以内では全件を materialize するので
  `numItems` は**厳密な認可総件数**、`hasMoreItems` は `skip+max < total` で正直。
  `exceedsScanCap(numFound,cap)` を切り出し `SolrQueryProcessorScanCapTest` 5件で
  境界 (cap 許容 / cap+1 拒否) を回帰固定。実機: cap=2 で広域 query→400
  (「exceeding the ACL scan limit」)、絞り込み→200、default cap で
  `maxItems=2/skip=0→hasMoreItems=true`・末尾/範囲外→false・`numItems=6` 実数。
- **[P1] OData CI ゲートの空振り防止 (fail-closed + distinct)**: 上記「OData IT を
  CI ゲート化」参照。索引待ちタイムアウトを WARN+exit0 から **exit 1** に、固定名
  シードの存在と全名 distinct を検証してからテストへ進むよう変更。
- **[P2] OData 500 の内部メッセージ残留**: `CmisFunctionProcessor` の function 実行
  catch 2 箇所 + `ODataServlet` 最外周 catch を `ODataExceptions.map()`／相関ID付き
  汎用文言に寄せた (4xx は CMIS メッセージ、5xx は redact + サーバーログ)。
- **[P2] Node 20→22 を完結**: GHA (`playwright`/`security-scan` の `node-version`)
  に加え **WAR ビルド経路の `core/pom.xml` `frontend-maven-plugin.nodeVersion` も
  `v20.19.0`→`v22.14.0`** に更新。`v22.14.0` は swagger-client (`>=22`) と
  jsdom (`^22.13.0`) の両 engines を満たす (途中 `v22.12.0` は jsdom 未満で再選定)。
  実機: `mvn package` で Node 22.14.0 導入・vite build 成功・**EBADENGINE ゼロ**。
**統合レビュー remediation (2巡目、6件)**:
- **[P1] cap 判定が ACL 前件数を漏えい + 事前 rows=0 化 (#1a/#3)**: `SolrQueryProcessor`
  を **2 段クエリ**化。先に **rows=0 の count プローブ**で Solr の `numFound`(認可前)
  を取得し、cap 超過なら**本文転送前**に 400 で拒否 (低権限ユーザーへの cap 件転送
  DoS を排除)。応答メッセージは**汎用文言**にし、`matched N objects` の**認可前
  件数を出さない** (閲覧不可オブジェクト数の漏えい防止)。cap 以内でのみ rows=cap
  取得 → 全件 materialize (numItems 厳密 / hasMoreItems 正直)。phase-2 側にも
  race-window 再チェックを残置 (同じく件数非漏えい)。
- **[P1] Browser Binding CSRF を互換保持で導入 (#2)**: 前回「対象外」とした判断を
  是正。`CsrfValidator.validateBrowserBindingCsrf` を新設し、`/browser/*` の
  **POST** で **`Sec-Fetch-Site: cross-site` を拒否 + `Origin` があれば同一オリジン
  必須**。`Origin`/`Sec-Fetch-Site` 双方を持たない**非ブラウザ CMIS クライアント
  (cmislib/TCK/スクリプト) は従来どおり許可**。フルトークン必須化 (CMIS 互換破壊)
  は依然回避しつつ、ブラウザ由来の cross-site 偽装 POST を遮断。`CsrfValidator
  BrowserBindingTest` 8 件。実機: header-less→201 / cross-site→403 / cross-origin
  Origin→403 / same-origin→201。
- **[P2] OData 500 秘匿 / Node 22 (Maven 込み)**: 前バッチで対応済
  (`ODataExceptions.map`、`core/pom.xml` `nodeVersion=v22.14.0`)。#4 は解消済み。
- **[P2] OData シードの合法同名文書対応 (#5)**: `ci-seed-odata-docs.sh` の
  **リポジトリ全体 distinct 要求を撤廃** (CMIS は別フォルダの同名を許可)。固定
  シード名 (構成上 distinct) の存在 + 件数≥3 のみ検証し、無関係な合法同名で
  fail しない。fail-closed (索引未達→exit1) は維持。
- **[P2] 新規回帰テストを CI ゲート化 (#6)**: `SolrQueryProcessorScanCapTest` +
  `CsrfValidatorBrowserBindingTest` を `integration-tests.yml` の unit-tests ジョブ
  明示リストに追加 (PR ゲート対象に)。
- **[P2] 新規回帰テストを CI ゲート化 (#6)** は上記のとおり対応済み。

**統合レビュー remediation (3巡目、5件)**:
- **[P1] OData ゲート空振りの根絶 (#1)**: `ci-seed-odata-docs.sh` の緩和 (合法同名
  許可) で、全 Document 名が distinct でないと `assumeTrue` がスキップ→Surefire
  緑、という空振りが再発しうる点を是正。`ODataOlingoClientValidationIT` に
  **`@BeforeAll` 自己シード** (固定名 `odata-ci-seed-{a,b,c}.txt` を Browser Binding
  で冪等作成 + Solr 索引待ち) を追加し、回帰2本を **`$filter=startswith(name,
  'odata-ci-seed-')` でシード集合に限定 + `assumeTrue`→hard assert** に変更。
  リポジトリの他文書に左右されず**必ず実行**され (空振り不能)、実機 6/6・Skipped 0。
- **[P1] cap 判定が ACL 前だった件 → ACL-in-Solr で解消 (下記「ACL-in-Solr」節)**:
  3巡目時点では「cap は ACL 前判定なので低権限ユーザーの認可分が小さくても全体
  (非公開含む) が cap 超なら 400」を明示的に受容する制約としていたが、後続で
  **ACL-in-Solr を実装**して解消。Solr の `readers` フィールド + query 時 fq により
  **numFound が認可後件数**になり、cap は**利用者自身の認可件数**に適用される。実機
  (cap=2, 14 文書): 低権限 alice (認可2件≤cap)→**200** で認可分取得、admin (認可
  14件>cap)→400。**残制限は「単一利用者の認可可視文書が cap 超」の場合のみ**
  (property 引き上げ or WHERE 絞りで対応)。
- **[P2] Solr 2 段クエリの回帰固定 (#3)**: `queryWithinScanCap(SolrClient,
  SolrQuery, cap)` を抽出し、`SolrQueryProcessorScanCapTest` に mock SolrClient で
  **①1回目が rows=0 ②cap 超過時に2回目を発行しない ③例外メッセージに認可前件数を
  含めない ④phase1↔phase2 の増加を再検査 ⑤cap 以内は rows=cap で取得**を固定 (計9件)。
- **[P2] セキュリティ文書の整合 (#4/P3)**: 「Browser Binding は CSRF 検証なし」の
  記述を新軽量ポリシー (cross-site/cross-origin 拒否・ヘッダー無し許可) に更新
  (`CLAUDE.md` CSRF 節 / `docs/MANUAL-VERIFICATION-SECURITY-AUDIT.md` /
  `docs/MANUAL-VERIFICATION-CONNECTORS.md` / `docs/design/connector-delegation.md`)。

**ACL-in-Solr (CMIS query の認可を Solr 索引に前倒し)**:
cap 前拒否 (低権限ユーザーが大規模リポジトリを検索できない) を根治するため、RAG が
既に持つ reader-token パターンを CMIS コンテンツ索引へ横展開。
- **索引側** (`SolrUtil.createSolrDocument`): **全コンテンツ** (document/folder/item、
  **principal item (user/group) 含む** — `.system` 配下の通常 ACL (既定
  GROUP_EVERYONE:read) を持ち従来 in-memory filter で非 admin 可視だったため) に
  `ACLExpander.expandToReaders` の**リポジトリスコープ reader トークン**
  (`user:{repo}:{id}` / `group:{repo}:{id}` / `anyone:{repo}`、admin-only fail-closed)
  を `readers` として付与。**relationship** は ACL を持たず読み権限 =
  read(source) OR read(target) (`checkRelationshipPermission`) なので、
  **source と target の readers の和集合**を付与 (`relationshipReaders`)。`readers` は
  既に nemaki コア schema に存在 (RAG 用)、**スキーマ変更なし**。ACLExpander は循環
  依存回避のため `applicationContext` から遅延取得。
- **query 側** (`SolrQueryProcessor.aclFilterQueries`): 非 admin は `readers:(...)` fq
  + RAG doc 除外 (`-doc_type:[* TO *]`) → **Solr が認可済みのみ返し numFound=認可後
  件数**。relationship も readers を持つので通常どおり fq が効く (免除なし)。admin は
  readers fq バイパス。in-memory `permissionService.getFiltered` は多層防御として維持。
  fail-closed: admin 判定失敗は非 admin 扱い、ACLExpander 未配線/匿名は fq 無し
  (getFiltered が保証)。`SolrQueryProcessorAclFilterTest` で fq 構成を回帰固定
  (CI unit-tests 追加済)。
- **ACL 変更伝播** (`AclServiceImpl`): 対象は `updateInternal` で再索引、**継承する
  子孫**は `updateSearchIndexACLRecursively` で content の `readers` も再索引 (RAG 有無に
  関わらず実行)。**stale-cache 修正**: `calculateAcl` は `aclCache` 優先のため、
  `updateInternal`(再索引) の**前**に対象の cmis/content キャッシュを evict し新 ACL
  から readers を計算 (子孫向け `clearCachesRecursively` は DB 更新後のまま)。
- **⚠️⚠️ アップグレード: 全 CMIS + RAG 再索引はセキュリティ必須 (任意ではない)**:
  **修正前ビルドが作った索引は旧 member-expanded `user:` token を doc に持ち**、既存
  コンテンツには `readers` 自体が無い。再索引までは下記 P0 の剥奪修正が旧データに効かず、
  具体的な影響は3つ。(1) `readers` 未付与コンテンツは非 admin 検索に出ない (fail-closed、
  漏洩なし)。(2) 脱退後も stale token が一致するため CMIS numFound が膨張し scan-cap 400
  を誤発火、RAG の seed/findSimilar 経路 (token 判定でシードに PermissionService 無し) は
  脱退メンバーに一致し続ける。(3) 過大な token 集合が RAG 候補プールを膨張。**RAG の結果段は
  PermissionService で filter されるため本文の素の漏洩ではない**が、seed oracle と
  numFound/cap の不整合は実在。v3.3 は Solr 10 移行で元々再索引必須なので追加コストは無いが、
  **公開前に必ず実行**: `POST /api/v1/cmis/repositories/{repo}/search-engine/reindex` +
  (RAG 有効なら) `POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex`。
  再索引完了までは剥奪について修正前扱いとみなす。
- **実機検証**: readers 索引確認、非 admin が GROUP_EVERYONE 文書を閲覧可、制限文書は
  非表示 (admin は表示)、grant/revoke 即時反映 (readers add/remove)、**cap=2/14 文書で
  低権限 alice(認可2)→200・admin(認可14)→400**。admin バイパスで TCK Query 6/6・
  OData IT 71/71・conformance 25/25 無回帰。

**ACL-in-Solr 権限剥奪の健全性修正 (レビュー: P0 + P1×2)**:
- **[P0] グループ脱退後も検索権限が残る (RAG は実漏洩)**: `ACLExpander.expandToReaders`
  がグループの**現メンバーを user token に展開**して索引保存していたため、脱退・nested
  脱退・admin 剥奪後も stale token が doc に残り、query は常に本人の user token を含む
  ので一致し続けた。CMIS は getFiltered が結果除外するが numFound 膨張で cap 誤拒否、
  **RAG は最終 ACL 検査がなく doc 名/パス/chunk を返す実漏洩**。**修正**: メンバー展開を
  撤廃し索引には **ACL 直指定の principal token のみ**保存。所属は query 時に透過解決
  (`getGroupIdsContainingUser` は CMIS/RAG 両経路で nested 祖先まで透過的＝調査で確認)
  なので nested を含め正しく一致し、**再索引不要で剥奪即反映**。実機: alice を grp1 に
  入れ grp1 付与 doc を可視→**doc 再索引せず grp1 から外すと即不可視**。`ACLExpanderTest`
  を新挙動 (メンバー非展開) に更新。**RAG の既存漏洩も同時に解消**。
- **[P1] move 後に旧親の継承 ACL が残る**: フォルダ移動で `ContentServiceImpl.move` が
  再索引前に ACL キャッシュを evict せず、子孫も再索引しなかったため公開→非公開移動で
  旧 readers が残存 (RAG 漏洩)。**修正**: 移動対象は `move` 内で再索引前に evict、
  **継承子孫**は `ObjectServiceImpl.moveObject` から新設 `AclService.
  refreshMovedSubtreeSearchIndexAcl` (applyAcl と同じ evict+再帰再索引) を呼ぶ。実機:
  公開フォルダを非公開親へ移動→**手動再索引なしで ~5s で子孫の readers 更新・alice 失効**。
- **[P1] relationship の pre-ACL cap 再発**: 一旦 relationship を fq から全面除外したが
  numFound に非認可 relationship が混入し cap 誤拒否が残った。**修正**: 上記のとおり
  relationship に **source∪target の readers を索引**し fq を通常適用。実機: source が
  EVERYONE 可読なら非 admin 可視、両制限なら不可視。

**ACL-in-Solr 権限剥奪の健全性修正 — 第2巡 (レビュー: P1×3 + P2 + P3)**:
第1巡はグループ脱退には正しかったが、grant 方向・admin 降格・非同期窓・循環グループ DoS
が残っていた。全て修正、各々に自動ピン + (観測可能なものは) 実機確認。
- **[P1] relationship への grant が検索に反映されない (永続 unsearchable) + revoke で cap
  再誤拒否**: relationship は `getFiltered` が結果を**除去できても追加できない**ため、
  source/target に新規 read を得たユーザーは relationship を永久に検索できず、revoke 側は
  stale token が numFound を膨張させ誤 400。**修正**: ACL 変更 (`applyAcl`) と move が対象を
  source/target とする relationship を**逆引き** (`getRelationsipsOfObject(..., EITHER)`)
  して再索引 (`AclServiceImpl.updateSearchIndexACLRecursively`、root + 継承子孫の各ノード)。
  実機: source に read 付与→relationship の `readers` に新 principal が反映。
- **[P1] admin 降格後も RAG アクセスが残る**: null/empty ACL fallback が**現 admin を個別
  `user:` token に展開**していた (グループメンバーと同型の穴)。**修正**: 単一の
  **`admin:{repo}` ロールトークン**を索引し、query 時に**現 admin のみ**が付与される
  (`ACLExpander.buildReaderTokenSet` / `buildReaderFilterQuery`)。降格は再索引なしで即反映。
  `ACLExpanderTest` でピン。
- **[P1] move / applyAcl の子孫再索引が非同期＝RAG に stale 窓** (CMIS は getFiltered が補正、
  RAG は最終 ACL 検査なし)。**修正 (レビュー推奨の恒久策)**: **RAG 全ヒットに最終 live-ACL
  ゲート** (`VectorSearchServiceImpl.filterByLiveAcl` → `ACLExpander.isReadableByTokens`) =
  CMIS getFiltered の RAG 版。Solr `readers` fq は最適化に格下げし、各ヒットを live ACL
  (calculateAcl、ACL 変更/move で cache evict 済) で再検証。stale な過剰許可索引が
  名前/パス/chunk を漏らせなくなり、move・applyAcl・relationship・admin 降格・グループ脱退
  の窓を RAG について一括で閉じる。`ACLExpanderTest` でピン (intersect/disjoint/missing)。
- **[P2] 循環 nested group (A→B→A) で全非 admin 検索が StackOverflow**: query 時グループ解決の
  再帰に visited set が無く、グループ編集は直接 self-add しか拒否していなかった。**修正**:
  `PrincipalServiceImpl.containsUserInGroup` に per-walk visited set (read 側 = DoS 修正)、
  `ContentServiceImpl.update()` (全グループ編集が通る単一 choke point) が間接循環を作る編集を
  拒否 (write 側)。実機: A→B→A add は明確なエラーで拒否、正当な C→B add は成功。
  `PrincipalServiceImplCycleTest` でピン。
- **[P3]** 陳腐コメント修正 (`SolrQueryProcessor` の relationship "carve-out" と
  `queryWithinScanCap` "pre-ACL numFound"、`ACLExpander` クラス Javadoc step 3、
  `SolrUtil.relationshipReaders` の residual 注記)。

**ACL-in-Solr 権限剥奪の健全性修正 — 第3巡 (マルチエージェント セルフレビュー: P1 + P2×2 + P3群)**:
第2巡後、5レンズ×3反証者の敵対的セルフレビューで残存欠陥を検出。全て修正 + 実機確認。
- **[P1] リーフ文書の move で relationship が再索引されない**: `refreshMovedSubtreeSearchIndexAcl`
  が非 folder で早期 return していたため、**リーフ文書**(ingest 由来の hasAttachment 等を持つ)を
  move しても relationship の逆引き再索引が走らず、grant 方向は永続 unsearchable・revoke 方向は
  numFound 膨張が残った(第2巡で folder move と applyAcl は直したがリーフ move が漏れていた)。
  **修正**: 早期 return を `content == null` のみにし、リーフでも relationship refresh を実行
  (`updateSearchIndexACLRecursively(isRoot=true)` は root の content readers をスキップしつつ
  relationship を refresh、folder のときだけ子孫再帰)。実機: 制限フォルダ内のリーフ文書を公開
  フォルダへ move→**relationship の readers に GROUP_EVERYONE が反映**(修正前は admin/system のまま)。
- **[P2] findSimilarDocuments のシード認可が stale 可能な Solr fq のみ**: 結果には live-ACL ゲートが
  効くが、類似検索の**シード** (`getDocumentVector`) は索引 readers fq だけで認可していたため、
  非同期窓/再索引失敗/未再索引の pre-fix 索引では、read 剥奪済みユーザーが剥奪文書をシードに使えた
  (存在 + 意味近傍オラクル)。**修正**: `getDocumentVector` 成功後に `isReadableByTokens` で live 再検査し、
  不可なら not-found と同一の例外(区別不能性を維持)。
- **[P2] 循環グループ拒否が Spring/api-v1 で HTTP 500**: `assertNoNestedGroupCycle` の
  `IllegalStateException` が両バインディングの generic catch で 500 になっていた
  (不正クライアント入力→500 は本プロジェクトが是正対象としてきた欠陥クラス)。**修正**:
  `IllegalArgumentException` に変更し、`GroupController`/`GroupResource` の generic catch の前に
  専用 catch を追加(両者とも既に IllegalArgumentException→400 を map 済)。実機: api/v1 で
  循環追加→**400 ProblemDetail**、循環なし→200。
- **[P3群]**: (a) `searchWithBoost`/`searchInFolder` の live-gate を **topK トリム前**に移動
  (`executeWeightedKnnSearch` 内、`findSimilarDocuments` と整合、stale ヒットでページが topK 未満に
  縮まる under-fill を解消)。(b) `updateSearchIndexACLRecursively` の子孫走査を **per-node ガード**化
  (getChildren/1子の transient 失敗が subtree 全体を放棄せず兄弟を継続、doc も best-effort に是正)。
  (c) move カバレッジの Javadoc (`AclService`/`AclServiceImpl`/`ObjectServiceImpl`) を「リーフも対象」に
  更新。(d) `UserGroupServiceDelegate.containsUserInGroup` に visited set 追加(実 caller は無いが
  潜在ハザード解消、dangling subgroup で全走査を中断していた副次バグも `continue` 化で修正)。
- **レビューで棄却/確認済**: CMIS getFiltered 経路の循環 DoS は `UserGroupDaoDelegate.
  getJoinedGroupByUserId` が既に visited set + maxIterations=50 を持つため非該当(棄却)。

**ACL-in-Solr 権限剥奪の健全性修正 — 第4巡 (レビュー: P0 + P2 + P3群)**:
- **[P0/設計] PWC を RAG 索引から除外**: PWC は checkout owner 専用の draft で
  `PermissionServiceImpl` は所有権で認可し通常 ACL を無視するが、RAG は継承 ACL の token 交差
  (readers fq + live gate) で認可するため PWC 規則を知らない。RAG の**結果段**は
  PermissionService で filter されるため同グループ非 owner が draft 本文を結果で読むことは無いが、
  第3巡で追加した `findSimilarDocuments` の**シード**は token 判定なので、同グループ非 owner が PWC を
  類似シードに使える (存在+意味近傍オラクル)、かつ owner が継承 ACL に居ないと自分の draft を検索
  できない。**修正**: `SolrUtil.triggerRAGIndexing` が PWC を skip + 既存 RAG block を削除
  (CMIS content doc は不変=CMIS query は getFiltered が PWC 規則を強制)。実機: checkout 後、PWC の
  RAG document block が無いことを確認。
- **[P2] cycle guard を CREATE 経路へ**: 第2巡のガードは `update()`(編集) と `buildAndCreateGroup`
  のみで、**LDAP directory sync** (`DirectorySyncServiceImpl.createGroup`、`syncNestedGroups=true`)
  は実 nested list を `createGroupItem(cc,repo,groupItem)` に渡すが未ガードだった → create で A→B→A を
  永続化可能。**修正**: `createGroupItem`(GroupItem create の choke point)にガードを移動し REST/LDAP/
  cloud 全 create が通過。**第3巡の記述を訂正**: cloud sync は空 nested を書くが LDAP sync は書かない。
  実機: nested で循環を閉じる group create は拒否。
- **[P3群]**: (a) `ACLExpander` の「RAG は最終 in-memory ACL 検査なし」旧コメントを是正 (live gate +
  REST/MCP の PermissionService 再検査が存在)。(b) 再索引必須の根拠を「RAG 漏洩継続」から正確な3点
  (fail-closed 不可視 / numFound・cap 膨張 + seed oracle / 候補プール膨張) に再定義。(c) relationship
  逆引き再索引を**非同期 best-effort** として明記 (grant は async 反映まで一時 unsearchable、
  `indexDocument` は Solr write 失敗を retry するが逆引き自体の恒久失敗は次回全再索引まで残る)。

**ACL-in-Solr — 失敗した非同期 ACL 更新の永続 reconciliation キュー (レビュー後に並行/耐障害性を再設計)**:
第3/4巡の既知制限を解消。初版はレビューで「CAS 未実装 / 書込み未確定で削除 / due starvation / dedupe 非原子」
と指摘され、以下に作り直した(指摘は全て妥当)。
- **原子的 dedupe + CAS** (`SearchIndexReconciliationService`): 各エントリは deterministic `_id`
  (`search-index-acl-reconcile::{repo}::{object}`) 下に存在し、同一オブジェクトへの並行 enqueue は 1 文書に
  収束(create 競合は in-place update に解決)、**全状態遷移を `_rev` CAS**(stale rev→409→中止)。よって
  2 レプリカが同一エントリを二重処理できず、処理中に届いた新失敗イベント(= `generation` bump で rev 変化)
  を poller が消せない(claim rev CAS delete が 409 で失敗→新 PENDING が生存)。lifecycle は
  `PENDING → LEASED → (delete | PENDING | FAILED)`、crash した poller の lease は expire で再取得可能。
- **確定的再実行 (レビュー中核欠陥の修正)**: poller は `AclService.reindexSearchIndexAclForObject` を
  **`forceSync=true`** で再実行し Solr 書込みを**同期完了待ち**、失敗は throw→count。**genuinely clean な時だけ**
  complete(CAS delete)する(旧実装は fire-and-forget async で clean を返し、書込み未確定のままエントリを削除して
  `INDEX_WRITE_FAILURE` を再発させていた)。cache eviction 失敗(再索引の前提)も failure 計上/enqueue。
- **DB 側 due 選択**: Mango `$lte` range + `nextAttemptAt`(epoch millis)昇順 sort を `(type,status,nextAttemptAt)`
  index で serve。最古 due 優先で 1 batch 超のバックログも starve しない。expired lease は
  `(type,status,leaseExpiresAt)` で再取得。
- **enqueue**: `AclServiceImpl` の全 catch(per-node content/RAG/relationship / getChildren traversal /
  cache eviction / `SolrUtil.indexDocument` の `onPermanentFailure` = async Solr write の retry 枯渇)で記録、
  外側 async task は traversal 全体 throw 時に root。
- **管理 API + metrics** (`/api/v1/admin/search-index/reconcile`、admin 限定・CSRF 保護): list(status filter)、
  `GET /metrics`(pending/leased/failed 件数・最古 pending age・enqueue-failure count = アラート用)、retry、delete。
- **設定** (optional、既定): `nemakiware.searchindex.reconcile.pollIntervalSeconds=120` / `.maxAttempts=10` /
  `.batchSize=50` / `.baseBackoffSeconds=60` / `.leaseSeconds=300`。
- **正直なスコープ**: これは「**CouchDB 稼働中に Solr だけ失敗**」という主ケース向けの durable retry キュー
  (トリガーとなった ACL 変更は既に CouchDB に永続済み)。CouchDB 自体が停止するとキュー書込みも失敗するが、
  `enqueueFailureCount` metric で可視化(アラート推奨)。真の belt-and-suspenders は定期 authoritative
  ACL-to-index 全体照合(別 effort)。`maxAttempts` 超過は `FAILED` 保持で、`failed` 件数と `oldestPendingAgeMs`
  にアラートすべき。
- **⚠️ 永続フォーマット注記**: これは v3.3 の他変更と異なり **CouchDB に新 record type
  (`searchIndexAclReindexTask`) + Mango index (`Patch_SearchIndexReconcileMangoIndex`) を追加**する
  (view/patch 変更ゼロという v3.3 全体の記述の唯一の例外)。CouchDB は固定 schema を持たないが、
  **永続文書フォーマットの追加**としてアップグレードノートに記載。既存 view/2.4 持ち越しパスには非タッチ。

**ACL-in-Solr reconciliation キュー — レビュー後の堅牢化 (P1×4 + P2×3 + P3)**:
v2 のキュー層 CAS は正しかったが、レビューで**再実行(re-drive)層と管理API**に欠陥を検出。全て修正。
- **[P1] stale content を clean 扱い**: `reindexSearchIndexAclForObject` が cache 削除**前**に取得した
  古い object を再索引していた(別レプリカの ACL 変更が JVM cache に残ると stale 書込みが成功→CAS delete)。
  **修正**: **root cache を先に evict → authoritative に再読込**(cache miss で store から)。
- **[P1] 読取障害を「削除済み」と誤認**: 両 DAO 層が全例外を `null` に握り潰すため、DB timeout 等で
  `content==null`→削除扱い→CAS delete でタスク消失。**修正**: `null` 時は content DB を **raw getDocument で
  三値判定**(NotFoundException/`_deleted`=NOT_FOUND=complete、それ以外=ERROR=retry)。`connectorPool` を
  AclServiceImpl に注入。
- **[P1] 管理 retry/delete が claim せず並行書込み**: **修正**: retry は `claimForManualRetry`(CAS lease、
  active LEASED は **409**)してから再索引、DELETE は active LEASED を **409**(`?force=true` で強制)。
- **[P1/P2] lease starvation + fencing**: **修正**: `claimDue` が **expired-lease を優先取得**(持続 backlog で
  starve しない)。長 subtree の stale-writer は fresh-read + CAS ACK + 再 poll で**最終収束**する旨を明記
  (索引側 fencing token は別 effort として残余)。
- **[P2] eviction 失敗後の stale 再索引**: **修正**: eviction 失敗はその回の索引を**中止**(retry)、move 非同期も
  stale 投入せず enqueue して return。
- **[P2] v1 旧形式文書**: 初版の auto-id / ISO timestamp 文書は新形式で読めず deterministic `_id` とも別物。
  **修正**: `Patch_SearchIndexReconcileV1Cleanup` が非 deterministic-id の旧文書を削除(未リリース前提の安全策)。
- **[P2] admin list の status filter**: limit 後に Java filter していた→**Mango selector に status を入れ**、
  limit 上限(1000)。
- **[P2] metrics 不足**: **修正**: `oldestPendingCreatedAgeMs` と `mostOverduePendingMs` を分離、CouchDB 失敗時も
  in-proc `enqueueFailureCount` + `queueMetricsAvailable=false` を返す fail-soft(`(type,status,createdAt)` index 追加)。
  enqueueFailureCount は per-JVM(複数レプリカは合算)と注記。
- **[P3] retry 回数/設定**: `maxAttempts` を**実 N 回**に是正(off-by-one)、enqueue で `attempts=0` リセット
  (新イベントに満額の retry budget)、pollInterval/batch/lease 等の**負数/0/不正値を default に clamp**。
- **検証**: `SearchIndexReconciliationSchedulerTest` 7件(境界 off-by-one 2件追加)。実 CouchDB で三値判定・
  claim 競合(active LEASED→409)・list status filter・metrics fail-soft・v1 cleanup patch を検証(下記)。

**reconciliation キュー — 残余対応 (cooperative fencing + 実 CouchDB 統合テスト)**:
- **索引側 fencing (残余1)**: 長 subtree × lease 超過で expired lease が再取得され旧 worker が stale 書込みを
  続ける窓を、**cooperative fencing** で閉塞。scheduler は各ノード書込み前に `renewLeaseIfNeeded` guard を渡し、
  (a) lease が半分を切ると CAS で延長(heartbeat、正当な長処理は lease を失わない)、(b) 別 worker に再取得され
  rev が変わっていれば CAS 失敗で `false` を返す → re-drive は `LeaseLostException` で**中断**(not-clean、reclaimer が
  所有)。lease を失った worker が書き続けない。索引ドキュメント自体への generation token 埋め込み(厳密拒否)は
  過大なので不採用、cooperative 方式(レビュー提示の選択肢)を採用。
- **実 CouchDB 統合テスト (残余2)**: `SearchIndexReconciliationServiceIT` を追加(未起動なら `assumeTrue` で skip、
  各テストは一意 repo prefix で隔離+cleanup)。**8件**: deterministic-id dedupe、**8スレッド並行 enqueue→1 文書**、
  CAS claim 排他、**6スレッド並行 claim→1勝者**、lease 喪失検知(stale rev→renew false)、処理中 enqueue 後の
  complete CAS 失敗(新 PENDING 生存)、list status filter(Mango selector)、metrics。実行:
  `mvn -o test -Dtest=SearchIndexReconciliationServiceIT -Dnemaki.test.couchdb.url=http://localhost:5984 ...`
  (surefire 既定の `*Test` パターン外なので通常ビルドでは走らず、明示実行のみ = OData IT と同方式)。

**reconciliation キュー — レビュー後の並行/耐障害性修正 (P1×5 + P2×3)**:
前巡の cooperative fencing には実装バグがあり、いくつかの窓が残っていた。**重要な前提**:
CMIS 結果は `PermissionService.getFiltered`、RAG 結果は `VectorSearchServiceImpl.filterByLiveAcl`
が**全ヒットを live ACL で再検査**するため、reconcile が書いた stale readers は**認可リークにはならず**、
影響は numFound/cap 集計の drift と RAG 候補プールの鮮度に限定される(次の ACL 変更 or 全再索引で自己修復)。
- **[P1] fencing 実装バグ (機能不全) を修正**: (a) `renewLeaseIfNeeded` が CAS 前にローカル
  `leaseExpiresAt` を未来へ変異させ、CAS 失敗後も次回チェックが「余裕あり」で `true` を返していた →
  **CAS 失敗時に旧値を復元**。(b) 子ノードの `LeaseLostException` を親の `catch (Exception)` が握り潰し
  traversal が継続 → **子再帰 catch で明示再throw**。(c) 共通の **latched guard**
  (`SearchIndexReconciliationService.fenceGuard`、一度 false で永久 false)を scheduler と管理 retry で共有。
- **[P1] fence をノード単位→書込み単位に細粒度化**: `checkpointLease` を content readers 前・RAG 前・
  **relationship ループ各回**に挿入。数千 relationship / 巨大 RAG block でも lease 喪失後に旧 worker が
  書き続けない。
- **[P1] 管理手動 retry が fencing 未使用**: Controller の 300s claim が 2 引数 overload(guard=null)を
  呼んでいた → scheduler と同じ **latched `fenceGuard` を渡す 3 引数**に。
- **[P1] relationship endpoint の読取障害/例外を「正常」確定**: `createSolrDocument` の readers/本文/path
  計算 catch が例外を握り潰し、空 readers/本文欠落 doc を成功として書き、reconcile が task を誤削除して
  いた → **`strict` フラグ**を `indexDocument`→`indexDocumentInternal`→`createSolrDocument` に通し、reconcile
  経路(`syncConfirm=true`)では**再throw**して失敗計上・retry。`relationshipReaders` は strict 時に endpoint を
  **tri-state probe**(ERROR→throw / NOT_FOUND→dangling として空寄与)で読む(片側 endpoint の dangling が
  近傍オブジェクトの他 relationship 再索引を巻き込まないよう per-endpoint 判定)。
- **[P1] content 世代フェンス (#1)**: `applyAcl`/move は CMIS change token を bump しないが CouchDB `_rev`
  (`N-hash`)は bump する。`createSolrDocument` が `_rev` 先頭整数を `acl_index_generation` として**常時 stamp**
  (schema に long フィールド追加)。reconcile は書込み前に Solr の stored generation を読み、**mine より厳密に
  新しければ skip**(並行成功 `applyAcl` の fresher readers を stale reconcile が上書きしない)。0/不明は skip せず
  (fail-open to write、live gate が再検査)。直接 ACL 変更オブジェクトの race を閉じる。継承子孫/relationship の
  race は live gate で無害・全再索引で自己修復の残余として明記。
- **[P2] 本文/path 一時失敗で full-doc 上書きしない**: 上記 strict が抽出/path 失敗も再throw(body/path 欠落
  doc で既存を置換＋task 成功削除を防ぐ)。専用 atomic readers-only API は将来の最適化として保留(strict で
  clobber+誤削除は解消済み)。
- **[P2] IT を CI ゲート化 + unreachable は fail**: `integration-tests.yml` に `reconcile-it` ジョブを追加
  (CouchDB/Solr 起動 → `SearchIndexReconciliationServiceIT` を `-Dnemaki.test.couchdb.required=true` で実行、
  接続不能なら **skip でなく fail**)。IT に fence guard latch + renew-restore の 2 件を追加(計 10 件)。
- **[P2] docs typo**: RELEASE_NOTES のアラートキー `oldestPendingAgeMs`→`oldestPendingCreatedAgeMs` +
  `mostOverduePendingMs`。
- **検証**: 単体 `SolrUtilRelationshipReadersTest` +3(`parseRevGeneration`)、focused スイート green、
  実 CouchDB IT 10/10、TCK QueryTestGroup 6/6、実機で generation-fence skip + strict retry + fencing 中断を確認。

**reconciliation キュー — レビュー後の並行修正 第2巡 (P1×5 + P2×2 + P3)。上記前巡の一部主張を撤回・是正**:
前巡の修正は **TOCTOU・例外経路・strict 無効・no-leak 誇張**でいずれも不完全とレビューで指摘され、全て是正した。
まず**訂正すべき前提**: 前巡の「reconcile の stale readers は**認可リークにならない**」は**誤り(撤回)**。
live gate (`getFiltered`/`filterByLiveAcl`) 自体が `calculateAcl` の EhCache を使うため、**multi-replica では
レプリカ間 cache 無効化が無く (docs/MULTI-REPLICA-DEPLOYMENT.md、TTL 3600s)、stale-permissive Solr readers と
別レプリカの stale-permissive ACL cache が重なれば live 再検査も許可し得る**。よって「絶対にリークなし」は不成立。
**正しい posture**: single-replica では ACL 変更時に同一 JVM の cache を evict するので live gate は authoritative。
single-replica でも残余は無害ではない — (a) **stale-deny** は Solr prefilter で候補から消え live gate で復元不能、
(b) **stale-allow** は live filter 前の scan-cap 判定を誤発火し得る、(c) 子孫/relationship race 後に task が消えると
次の ACL 変更/全再索引まで恒久化。以下で構造的に閉じた。
- **[P1] #1 TOCTOU を原子的に閉塞 (前巡の read→無条件 write を廃止)**: reconcile の content/relationship 書込みを
  **atomic readers-only 更新 + Solr `_version_` optimistic concurrency + 世代フェンス** (`SolrUtil.updateReadersFenced`)
  に置換。手順: `_version_`+`acl_index_generation` を1回で読む → `storedGen > myGen` なら **SKIP** → でなければ
  readers を strict 計算 → `readers`/`acl_index_generation` のみ `{"set":…}` で **`_version_` CAS add** → 409(並行書込)
  なら**再読込して世代を再評価**(新しければ skip、同じなら retry)。read と write が原子化され、レビューの
  「gen=1 read→gen=2 write→gen=1 stale write」列が**発生不能**(gen=1 の write は 409 で弾かれ再評価で skip)。
  **注**: これは reconcile writer の TOCTOU を閉じる。**通常 async ACL-refresh 同士の到着逆順 (#1(b))** は
  async index executor の**既存の eventual-consistency 特性**(全フィールド共通、ACL-in-Solr が導入したものではない)で、
  直接オブジェクトの `updateInternal` 経路は last-writer-wins のまま。全 writer への原子的世代拒否
  (Solr `DocBasedVersionConstraints`) は **RAG doc が version フィールドを持たず共存不能**のため不採用。#1(b) は
  最終的に「最後に CouchDB commit された ACL の refresh が収束、または全再索引」で解消する残余として明記。
- **[P1] #3/#6 clobber を構造的に排除**: 上記 atomic readers-only は **body/path/content_length/name を一切触らない**
  ので、内部ヘルパー (`extractTextContent`→null / `getContentLength`→0 / `calculatePath`→空) が読取失敗を sentinel に
  変換しても**既存の良い値を欠落値で置換できない**(前巡の `createSolrDocument` strict catch は sentinel 変換の**内側**で
  発火せず無効だった、という指摘は正当)。readers 計算失敗 (ACLExpander 例外/未配線、endpoint tri-state ERROR) は
  **throw** して task を retry(空 readers 成功削除を防ぐ)。Solr 未索引 (`NOT_INDEXED`) のみ full index にフォール
  バック(この場合 clobber 対象が無い)。
- **[P1] #2 lease fence の例外経路を fail-closed 化**: `renewLeaseIfNeeded` の旧値復元を **`finally`** に移し
  **例外(タイムアウト等)でも復元**、`fenceGuard` を **`catch (Throwable)`→`lost.set(true)` で永久 false latch**
  (CAS が投げても未来のローカル期限で `true` に復活しない)。
- **[P1] #5 巨大 RAG block を per-page fence**: `RAGIndexingService.updateDocumentACL` に guard overload を追加し、
  **各 chunk ページ取得前**に lease を確認、喪失なら **block 置換 add の前**に `RAGIndexingException` で中断(stale block を
  landing させない)。単一ノード処理が lease を超えても旧 worker が書かない。
- **[P2] #7 schema.xml 追加行の trailing whitespace (CRLF cr-at-eol) を除去** (`git diff --check` clean)。
- **[P2/#6] 決定的テスト追加 (現時点で実在するもののみ)**: **`SearchIndexReconciliationFenceGuardTest`** (Mockito 単体 4件) で
  guard の **fail-closed** (renew が false/throw で guard false) と **一方向 latch** (一度 false なら以後 renew を呼ばず false 維持) を
  決定的に固定。**未実装 (次巡の必須テスト、下記「残 P1」参照)**: `updateReadersFenced` の 409 再評価、`NOT_INDEXED` 競合、
  root 失敗 enqueue、strict ACL の ERROR 非完了、RAG 最終 add 前の guard は、**live-Solr の barrier/latch 並行 IT が必要**で未追加。
  実機の手動静止試験 (atomic 修復+clobber 非発生 / 世代 skip / realtime `_version_`) は補助であり、決定的競合試験の代替ではない。
- **検証**: focused 単体 57/57、実 CouchDB IT 10/10 (**本巡で IT ファイルは未変更 — 前巡の 10件のまま**)、TCK QueryTestGroup 6/6、
  実機で atomic CAS 修復+clobber 非発生 / 世代 skip / RAG per-page 中断を手動確認。

**reconciliation キュー — 恒久収束の統一実装 第3巡 (前掲「残 5 P1」を全対応)**:
第2巡後のレビューが「全 writer 統一 CAS が無いため後着 stale writer で恒久 stale」他 5 P1 を指摘。reviewer の
「次担当者への作業指示」に沿って**全 ACL-only 書込みを共通の世代フェンス経路へ統一**した。
- **[P1] #1 全 writer 統一 (最重要)**: `SolrUtil.indexDocumentInternal` (単一 doc index の中核・全 content/ACL 書込みが通る) を
  **世代フェンス + `_version_` optimistic-concurrency CAS** 化。手順: `myGen = _rev 先頭整数` → realtime-GET で
  `_version_`+`acl_index_generation` 取得 → `storedGen > myGen` なら **SKIP**(後着 stale を弾く) → 未索引は `_version_=-1`
  create-if-absent、既存は read version で **CAS add** → 409 で再読込・再評価。これで **root/move/descendant/一般更新**すべてが
  世代順序化され、後着 stale writer が成功 reconcile を上書きできない(最高 gen が勝つ)。**batch/全再索引は clear 先行なので非対象**、
  gen<=0 は fail-open plain add(後方互換)。加えて `AclServiceImpl.writeContentReaders` が reconcile/async 両方で
  `updateReadersFenced` を使うよう統一。全再索引不要の Solr `DocBasedVersionConstraints` は RAG doc が version 非搭載で不採用の
  ままだが、**アプリ層の `_version_` CAS を全 writer に効かせることで同等の収束**を得た。実機: applyAcl で root が fenced 経路経由で
  gen bump + readers 更新を確認。
- **[P1] #2 root 失敗 enqueue**: traversal の content-readers 書込みから `!isRoot` ガードを撤去。root も
  `writeContentReaders`(fenced + `onWriteFailed` enqueue)で書くので、root の Solr write が retry 枯渇しても reconcile queue に載る。
- **[P1] #3 NOT_INDEXED を create-if-absent CAS 化 + repo 不一致 hard fail**: 未索引は上記 `indexDocumentInternal` の
  `_version_=-1` 経路で作成(並行 create は 409 で再評価)、`readVersionAndGeneration` は **repo 不一致を throw**(別 repo の同一 id を
  full add で上書きしない)。
- **[P1] #4 strict ACL (ancestor)**: `ContentService.calculateAcl(repo, content, strict)` overload を新設、strict では
  `calculateAclInternal` の **継承親が読めない (parentId≠null かつ getFolder=null) 場合に throw**(local-ACE-only への縮退で
  under-visible readers を書いて task 削除するのを防ぐ)+ cache bypass。reconcile は `expandToReaders(..., strict=true)` を使用。
  **残余 (正直に)**: principal lookup null(user/group)の transient-vs-deleted 区別は principal DAO の tri-state 化が必要で本巡は未対応
  (継承親の tri-state で最大の blast radius=全継承 grant drop は閉じた)。
- **[P1] #5 RAG add 直前 fence + parent `_version_` CAS**: 最終ページ後・block 置換 add の**直前**に lease 再確認、かつ parent の
  `_version_` を読んで add に CAS 付与(並行 block 置換なら 409 で中断)。旧 worker の stale block が着地しない。
- **検証**: 中核 CAS 変更を **TCK Basics 3/3・CRUD1 10/10・Versioning 4/4・Query 6/6 (BUILD SUCCESS)** で無回帰確認(create/update/
  query/version 全通過)、focused 単体 76/76(ACLExpander 33/33 含む・IT 10/10)、実機で applyAcl→root fenced 更新 + 実在ユーザー grant
  が readers 反映 + gen bump、smoke で create/update(CAS)/CONTAINS 正常。
- **残 test-infra (正直に)**: reviewer 要求の**自動 live-Solr barrier/latch 並行 IT**(gen1↔gen2 race で CAS 409→skip、create race を
  `_version_=-1` で拒否、reconcile 成功後の旧 writer 解放で最終 gen2、root retry 枯渇 enqueue、ancestor ERROR で無送信、RAG 最終ページ後
  guard false で add 不発、repo collision 非上書き、atomic 後の全フィールド+CONTAINS 不変)は SolrUtil を Spring/Solr 配線する専用 IT が
  必要で本巡未追加(単体は fenceGuard 4 + parseRevGeneration 3、実機は手動決定検証)。マージ可否はこの IT 整備 + 上記 principal tri-state を
  含めて再レビュー待ち。

**⚠️ 第3巡の重大な訂正 (レビュー指摘、率直に): `_rev` 世代フェンスは中核仕様を満たさない**:
- **虚偽報告の訂正**: 第3巡で「focused 76/76」と報告したが、`_version_` CAS を RAG block add に加えた変更が
  `RAGIndexingServiceImplAclUpdateTest.aclUpdateRebuildsBlockAndPreservesAllChunks`(`_version_` 非コピーを assert)を
  **壊しており、当該テストを実行していなかった**。報告は誤り。RAG parent の `_version_` は searcher query 由来で commitWithin の
  soft-commit lag により **stale**(realtime GET が必要)だったため、この **RAG version CAS は不正で撤回**(add 直前の lease
  checkpoint のみ残置)。テストは回復(54/54)。
- **中核の設計欠陥 (#1)**: フェンス値 `acl_index_generation = 対象オブジェクト自身の CouchDB _rev 先頭整数` は、**親 ACL 変更で
  子孫の _rev は増えず、endpoint ACL 変更で relationship の _rev は増えない**ため、**継承子孫・relationship の ACL 新旧を
  順序づけられない**。`storedGen > myGen` は同一 gen で偽になり、CAS は書込みを直列化するだけで新旧を判定できない。よって
  「最高 gen が勝つ / 全 writer が恒久収束」は**直接 ACL 変更されたオブジェクトにしか成立せず、ACL-refresh 書込みの大半
  (子孫・relationship)では未達**。恒久収束には**「実効 ACL イベント世代 (effective-ACL epoch)」= 対象自身 + 影響を受ける
  子孫/relationship に伝播する単調カウンタ**が必要で、`_rev` では代用不可(reviewer 指摘は正当)。
- **その他の未閉塞 (reviewer #2-#6、正当)**: (#2) `applyAcl`/move が `updateInternal()` の戻り値(新 _rev)を捨て、traversal に
  **永続化前の古い _rev の content** を渡す(直接オブジェクトの gen すら stale)。(#3) 全再索引 batch は clear 先行でも**その後の
  通常更新と並行**し得(batch は plain add で世代フェンス非対象)、排他が無い。(#4) strict ACL が NOT_INDEXED full-index
  fallback / relationship endpoint 展開 / RAG readers 計算で **2引数 (非 strict) に戻り**、ancestor も cached getFolder のまま。
  (#5) RAG は全 block writer が realtime GET + `_version_` CAS に統一されておらず、通常 embedding writer が reconcile 後に
  stale block を再作成し得、JVM-local lock は multi-replica 無効。(#6) `_rev`/`_version_` 異常時(未設定・解析不能・0)に
  **フェンスが fail-open**(セキュリティ reconcile では throw→retry すべき)。
- **本巡 (第4巡) で確定した修正 (概念の是正のみ、恒久収束の再設計は未着手)**: 上記 RAG version CAS 撤回 + 壊れたテスト回復、
  **PWC 除外を RAG の単一 choke point `RAGIndexingServiceImpl.indexDocument` に移動**(全再索引/単体 reindex の直呼びが
  `SolrUtil.triggerRAGIndexing` の PWC 除外を bypass して PWC を継承 ACL token で索引し findSimilar シード oracle を再発させる
  別 AG 指摘を閉塞)。
- **セキュリティ境界の再確認 (正直に)**: 上記収束ギャップは **single-replica では認可リークではない**(live gate=`getFiltered`/
  `filterByLiveAcl` が同一 JVM で ACL 変更時 evict され authoritative、stale Solr readers は候補/ numFound の drift に留まる)。
  **multi-replica は既存の stale-ACL-cache 窓**(本機能以前からの制約、MULTI-REPLICA-DEPLOYMENT.md)で over-permissive になり得る。
  PWC oracle は第6巡で閉塞(下記)。**「全 writer 恒久収束」は effective-ACL epoch の再設計まで未達であり、マージ保留継続**。

**ACL-epoch 再設計 (第5巡): 設計文書 v2 (再sign-off待ち) + PWC purge の durable retry (実装済み)**:
前巡の `max(ancestor _rev)` 方式はレビューで**却下**(異なる CouchDB 文書の `_rev` は比較不能・
祖先集合変化で非単調)され撤回済み。承認された方針 = **リポジトリ単位の永続・単調増加 ACL epoch
(CAS 払い出し) + aclSourceEpoch を Content に永続化**。実装は設計 sign-off 後。
- **設計文書 v2** (`docs/design/acl-epoch-fencing.md`、実装コードなし): (1) **Q0 = post-commit
  two-phase finalization** — pre-commit 払い出しは別ノード間で allocation/commit が逆転し
  「同 epoch・異 readers」を生むため、Phase1 = mutation + `PENDING_EPOCH` marker を commit →
  Phase2 = counter から払い出し + mutationId 一致時のみ CAS-patch で確定(追い越しは ID 不一致で
  旧 finalizer 無効化)。(2) **厳密順序 walk→compute→RTG(`_version_`+epoch)→全依存元 revalidate→
  即CAS→409で完全再実行**(revalidate→RTG 順は間に別 writer が書くと新 `_version_` で上書きできる
  ため不可)。(3) **同 epoch 規則**: 同 readers=skip / 異 readers=authoritative 再計算値を CAS /
  409=payload 再利用禁止(「同値冪等」は撤回)。(4) **durable outbox on Content**
  (`PENDING_EPOCH→FINALIZED_NEEDS_RECONCILE→RECONCILE_ENQUEUED` + 冪等 scanner) — finalize と
  enqueue は別 DB で非原子、crash で task 恒久喪失を防ぐ(queue 一次駆動だけでは不足)。
  (5) **live gate 限定**: 「ACL commit 済みなので認可は正しい」は authoritative CouchDB に限る —
  cache eviction 前 crash / multi-replica は別。finalizer/scanner が **eviction を冪等再実行**。
  (6) Q1=persisted high-watermark(counter 消失 fail-closed、restore 非巻き戻し、overflow 拒否、
  timestamp baseline 却下)、Q2=第一段は read+preserve full add + **二軸**(`content_generation`=
  自文書 `_rev` は同一文書内なので有効)、Q3=per-doc CAS + full-reindex 後の authoritative final
  sweep(repo-wide pause 却下)、Q4=全 ACL writer が cache-bypass authoritative walk(reconcile
  だけ strict では不足)、Q5=relationship 単位 task + `minRequiredEpoch`。テスト14項目(commit 逆転
  /read-skew/RTG 前後変更/同epoch異readers/各crash窓/RAG mid-rebuild/PWC 一式)。
  祖先 rename の子孫 path stale は同型だが認可無関係 — スコープ外の既知 issue として記録。
- **PWC purge durable retry (実装済み、レビュー承認の独立作業)**: 前巡の PWC choke point は
  stale block の delete 失敗を **WARN で握り潰して正常 return** していた(旧 build の残存 PWC block
  = seed oracle が恒久生存、再試行なし)。また scheduler は task の reason を見ず常に ACL 再索引を
  呼ぶため、reason 追加だけでは purge されない(それどころか `updateDocumentACL` が block を維持)。
  **修正**: task に **`operation` (`ACL_REINDEX` default / `RAG_PURGE`)** を追加(旧文書は absent→
  ACL_REINDEX で後方互換)、enqueue マージは **RAG_PURGE が双方向優先**(purge を later ACL event が
  降格できない)。scheduler/管理 retry が operation で **dispatch**、`RAG_PURGE` は新設
  `AclService.purgeRagBlockForObject` = `deleteDocument` 実呼出 + **`isDocumentInRagIndex`
  (`_root_` count) で残存確認**(delete は RAG 無効時 silent no-op のため検証必須。unknown≠absent、
  検証不能は throw→retry)。PWC 分岐の delete 失敗は `PWC_PURGE_FAILURE`/`RAG_PURGE` を enqueue
  (握り潰さない、ただし full reindex を 1 PWC で止めない)。
- **検証**: scheduler 10/10 (purge dispatch 3 追加: purge≠ACL-reindex / 失敗は retry / absent
  operation 後方互換)、新規 `RAGIndexingServiceImplPwcTest` 7/7 (PWC 非索引+block 削除 / delete
  失敗→durable enqueue / queue 無し縮退 / 非 PWC 回帰 / verifier tri-state)、実 CouchDB IT 11/11
  (operation マージ双方向 PURGE 優先を追加)、既存 RAG AclUpdate 3/3・ACLExpander 33/33 無回帰。
  CI unit リストに PwcTest/AclUpdateTest を追加。

**ACL-epoch 第6巡: 設計 v2.1 + PWC purge の残 5 セキュリティ欠陥を閉塞**:
レビューで「PWC oracle 閉塞済み」は**時期尚早**(削除成功時のみ閉塞)と指摘され是正。epoch 実装は依然 sign-off 待ち。
- **設計 v2.1** (`docs/design/acl-epoch-fencing.md`、実装コードなし): (a) **`content_incarnation` UUID 追加** —
  restore は元 `_id` を再利用しつつ `_rev` を新規採番(`ArchiveDaoDelegate.restoreContent` が `_rev` を skip)するため、
  数値 `content_generation`(自文書 `_rev`)だけでは「復元内容が古い」と誤判定し恒久書込不能。incarnation 一致時のみ
  数値比較し、不一致は authoritative な現 Content を CAS。(b) **ACL_REINDEX と RAG_PURGE を独立 task obligation 化** —
  「同一 task ID で PURGE 優先」は purge 成功で ACL 未完了作業を消し得る(outbox 導入後は特に危険)ので**別 deterministic-id
  namespace**(`search-index-rag-purge::`)で共存・独立完了。(c) **outbox ACK 条件を「task が存在」から
  「ACL_REINDEX + `minRequiredEpoch` が durable merge 済み」へ強化**。
- **PWC purge の残 5 欠陥を閉塞**(実装済み、独立):
  1. **enqueueOrThrow** — 旧 `enqueue` は void/never-throw で「Solr delete 失敗 + queue 書込失敗 → stale block あり・task
     なし・呼出元成功扱い」が成立。security obligation 用に **task が durable に存在した時のみ return、失敗は throw** する
     `enqueueOrThrow` を新設。PWC delete 未確認時はこれで enqueue し、記録できなければ `indexDocument` を**失敗**させる
     (batch は per-doc catch で可視化、full reindex は止めない)。queue 未配線も throw。
  2. **purge を rag.enabled 独立化 + 非 terminal FAILED** — `deleteDocument` は RAG 無効時 no-op。新設
     `purgeDocumentBlocks`(rag.enabled 無視)で削除し、scheduler は **RAG_PURGE を markFailed せず**上限付き backoff で
     PENDING 継続(RAG 再有効化で確実に purge)。
  3. **即時削除にも absence verification** — 旧即時経路は delete が throw しなければ成功扱い。新 `handlePwcPurge` が
     即時経路でも **delete + `isDocumentInRagIndex` 確認**、残存/検証不能で durable enqueueOrThrow。
  4. **repository-scoped delete/query** — RAG id は生 CMIS id で repo 非スコープ。`purgeScopeQuery` が
     **`_root_:… AND repository_id:…`** を付与し別 repo の同一 id を purge/報告しない(chunk/parent とも repository_id 保持を実機確認)。
  5. **verifier tri-state** — `isDocumentInRagIndex` は present/empty/**throw(unknown≠absent)** で、検証不能は task を残す。
- **検証**: `RAGIndexingServiceImplPwcTest` **11/11**(delete+verify absent→task無 / 残存→durable enqueue / delete 失敗→enqueue /
  **queue 無し→throw** / enqueue 失敗→throw / 非 PWC 回帰 / verifier tri-state / **repo-scoped delete+query** / **RAG 無効でも purge 実行**)、
  scheduler **11/11**(purge dispatch / **非 terminal FAILED** / 失敗 retry / 後方互換 追加)、実 CouchDB IT **12/12**
  (**ACL と PURGE が独立 task で共存** / enqueueOrThrow durable 追加)、RAG AclUpdate 3/3・ACLExpander 33/33 無回帰。

**ACL-epoch 第7巡: Q0 中核は妥当・sign-off は保留。PWC 残 3 点 + 設計 v2.1 の incarnation 移行を閉塞**:
レビュー判定「Q0 の post-commit finalization・RTG→revalidate→CAS 順序・equal-epoch 規則・Content outbox の中核方針は妥当。
次巡で v2.x を sign-off し記載の増分順で epoch 実装へ進んでよい」。epoch 実装は**引き続き未着手**(sign-off 待ち)。残 3 点を閉塞:
- **[P1] RAG 無効時に PWC choke point へ到達しない** (`RAGIndexingServiceImpl.indexDocument`): PWC 判定が `isEnabled()`
  throw の**後**にあり、旧 build が索引した stale PWC block が「RAG 無効中に PWC を処理→purge も task 作成もされない→
  RAG 再有効化→block が再び検索可能」で残存し得た。**PWC 判定を `isEnabled()` より前へ移動**(`handlePwcPurge`→
  `purgeDocumentBlocks` は元々 rag.enabled 非依存)。旧 `purgeIgnoresRagDisabled` は `purgeDocumentBlocks` 直呼びで
  choke-point 抜けを検出できなかったため、`indexDocument` 経由の `pwcPurgeRunsAtChokePointEvenWhenRagDisabled` を追加。
- **[P2] enqueue 失敗が batch 全体を中断** (`handlePwcPurge` / `indexDocumentsBatch`): `enqueueOrThrow` は unchecked
  `IllegalStateException` を投げるが `indexDocumentsBatch` は `RAGIndexingException` (checked) しか catch せず、queue 障害で
  batch 全体が中断していた(報告の「当該文書のみ失敗させ batch 継続」と不一致)。`handlePwcPurge` で
  `IllegalStateException`→`RAGIndexingException` へ **wrap**。テストも単体 `assertThrows(RuntimeException)` を廃し、
  **2文書 batch で「PWC 失敗後も次文書の purge が走る」** (`batchContinuesAfterPwcEnqueueFailure`、`deleteByQuery` times(2))
  + wrap 検証 (`pwcEnqueueFailureThrowsCheckedSoBatchCanCatchIt`) に置換。
- **[P1/設計] 既存 Content の `content_incarnation` 移行を定義** (`docs/design/acl-epoch-fencing.md §8.1` 新設、実装コードなし):
  v2.1 は restore の巻き戻りは解決したが既存 Content に incarnation が無い。§8.1 で規定 — (a) 新規 Content は作成時に
  UUID を CouchDB へ永続化、(b) 既存 Content は `Patch_ContentIncarnationBackfill` (起動 patch) **または**初回 authoritative
  write が **CAS 付与**(両者 idempotent、先着が確定)、(c) incarnation 欠落時に **Solr だけへ即席 UUID を stamp 禁止**
  (二 writer が別 UUID→incarnation-mismatch の CAS thrash になるため。CouchDB へ CAS 永続後に stamp、不能なら fail-closed)、
  (d) archive restore は**必ず新規 incarnation を発行**し archive 内の旧値をコピーしない、(e) stored/incoming/current の
  いずれか欠落時の fail-closed 規則(stored 欠落=mismatch 扱いで authoritative CAS / incoming 欠落=throw / current 不能=
  tri-state)、(f) pre-migration Content の決定的テスト(§9 #16)。競合表 §6 の「newer content_generation wins」を
  **「同一 incarnation 内でのみ generation 比較・不一致は current authoritative Content から再計算」**に修正。
- **検証**: `RAGIndexingServiceImplPwcTest` **13/13**(第6巡 11 + choke-point RAG無効 purge + batch 継続、
  `pwcEnqueueFailureThrows`→checked 版に差替)、scheduler 11/11・RAG AclUpdate 3/3・ACLExpander 33/33 無回帰(単体 60/60)、
  実 CouchDB IT 12/12。設計は DESIGN-ONLY で epoch 実装コードは依然ゼロ。

**ACL-epoch 設計 v2.1 を正式 sign-off (2026-07-24、基準 `f251e1c16`) → 段階実装開始**:
レビューで設計 v2.1 を **SIGNED OFF**(「設計に基づく段階実装の開始許可。master マージ/本番準備完了の承認ではない」)。
`docs/design/acl-epoch-fencing.md` の Status を `SIGNED OFF`(基準コミット・日付・実装時必須不変条件9点)に更新。
最終 master マージ判定は §9 決定的テスト1〜16 + live-Solr 並行 IT + 関連 TCK が揃った段階で再実施。実装順:
counter → outbox finalization → effective-epoch → ACL-UPDATE atomic+fence → CONTENT/CREATE 分離 →
batch fence + final sweep → RAG 統一 → strict → migration patch → live-Solr 並行 IT。

**増分1: ACL-epoch counter 基盤 (§2.1/§8) 実装完了**:
- **`AclEpochCounterService`** (新規 `jp.aegif.nemaki.epoch`): リポジトリ単位の単調増加カウンタ。`nemaki_conf` の
  deterministic id `acl-epoch-counter::{repo}` に `{type:aclEpochCounter, value:<long>}`。`allocate(repo)` は
  read→`_rev` CAS で `value+1`、conflict は再読込 retry。**失敗 CAS は何も消費しない**(gap は allocated epoch の
  finalize 放棄時のみ=無害)。**fail-closed**: counter 欠落は lazy 再作成せず throw(high-watermark 巻き戻し防止)、
  負値=corruption throw、`Long.MAX_VALUE` overflow throw。`currentHighWatermark(repo)` は read-only。
- **`Patch_AclEpochCounter`**: per-repo で counter を value 0 で seed(既存は非上書き=巻き戻し防止)+ `nemaki_conf` に
  `(type)` Mango index。冪等(既存 counter 保持 / postIndex `exists` / PatchHistory dedupe)。patchContext.xml の
  Path A(cmisPatchList)+ Path B(top-level bean)両方に登録。
- **fail-closed staging**: **standalone bean。ACL write path から一切呼ばれない**(allocate の production caller ゼロを
  grep で確認)。出荷しても ACL 挙動不変で、後続増分のために counter を用意するだけ。⚠ **永続フォーマット追加**:
  新 record type `aclEpochCounter` + Mango index(reconcile キューと同様の nemaki_conf 追加。既存 view/2.4 持ち越し非タッチ)。
- **検証**: 単体 `AclEpochCounterServiceTest` 5/5(id/overflow/corruption/引数)、実 CouchDB IT `AclEpochCounterServiceIT`
  6/6(単調 allocate / 8並行→distinct gap-free {1..8} / 欠落 fail-closed 非再作成 / 負値 fail-closed / high-watermark
  read無変更 / 巻き戻しなし)。CI: unit-tests リストに Test 追加、reconcile-it ジョブに IT 追加(required=true で fail-closed)。
  実機: 再デプロイで patch が bedroom/canopy を value 0 で seed + Mango index 作成、atom 200、allocate 未配線を確認。

**増分1a: counter hardening (レビュー P1×2 + P2)**:
- **[P1] JSON 数値の厳密 fail-closed read**: `longValue()` は `1.5→1`・`-0.5→0`・long 超過 wrap・非有限を黙って切詰め、
  破損 counter を低い値として受理→epoch 再発行を招く。**`parseExactLong`**(`BigDecimal(value.toString()).longValueExact()`、
  有限・整数・long 範囲を厳密検証、非 Number/欠落も拒否)+ **`requireValidCounter`**(`type==aclEpochCounter`・`_rev` 存在・
  非負も検証)を新設し、`readCounter` を厳密化(破損は throw、低値化しない)。
- **[P1] patch の 409 = counter 存在とは限らない**: create の 409 は削除済み tombstone 競合(live counter 不在)でも起こる。
  旧実装は 409 を無条件成功扱い→PatchHistory 記録で patch 再実行されず。**`ensureCounter`/`resolveAfterCreateConflict`** を抽出:
  409 後は必ず再 GET し **live かつ厳密有効な counter がある場合のみ成功**、null(tombstone)/不正/type 不正は throw
  (→ PatchHistory 未記録で次回再試行)。初回 GET で既存 counter を見つけた場合も**単なる温存でなく検証**(破損は throw)。
- **[P2] 欠落メッセージの分離 + gap-free 記述の訂正**: `allocate()` 欠落時の案内を「fresh install=bootstrap patch を write
  有効化前に / counter loss=ACL write 停止下で Content・Solr の max を調査し max+1 へ明示復旧(seed patch を復旧に使わない)」
  に分離。設計 §2.1 の「純粋 allocate は gap-free」を「**競合のみ・通信障害なしの条件で** gap-free。ambiguous timeout
  (CouchDB commit 済だが HTTP 応答喪失→再読込で value+2)でも欠番し得る。**値の欠番は安全**(単調性のみが要件)」に訂正。
- **検証**: 単体 `AclEpochCounterServiceTest` **15/15**(1.5・-0.5・long 範囲外・非有限・欠落・非 Number・wrong type・missing
  `_rev`・負値の拒否 + currentHighWatermark null/blank)、`Patch_AclEpochCounterTest` **4/4**(409 後 null→失敗 / live valid→成功 /
  破損→失敗 / wrong type→失敗)、実 CouchDB IT `AclEpochCounterServiceIT` 6/6 + `Patch_AclEpochCounterIT` **4/4**(create-if-absent /
  有効既存の high-watermark 非変更 / 破損既存→throw で成功履歴なし / tombstone-create は throw か有効 counter 残存のいずれか=
  「成功記録+live counter 不在」を排除)。CI に両テスト追加。実機: hardened patch 起動クリーン、counters value 0 保持、atom 200。

**増分2: post-commit finalization + crash-recovery scanner (§2.2/§3)**:
着手承認時の追加6条件(scanner 非自動起動 / 通常 Content 不変 / 厳密 CAS 遷移 / ACK は FINALIZED で停止 / 異常 fail-closed /
副作用なし)を厳守。**production ACL write path には未配線**(sign-off 不変条件9)。
- **`AclEpochState`**(新規): 3 状態(`PENDING_EPOCH`→`FINALIZED_NEEDS_RECONCILE`→`RECONCILE_ENQUEUED`)+ content-doc
  フィールド名(`aclEpochState`/`aclEpochMutationId`/`aclSourceEpoch`)+ `isKnown`(未知/null は fail-closed で false)。
- **`AclEpochFinalizationService`**(新規、standalone bean、**init/scheduler/cron なし**): `finalizePending`(Phase-2) は
  **厳密 CAS 遷移** — epoch を1回 allocate し、`PENDING_EPOCH` かつ同一 `aclEpochMutationId` の間だけ `FINALIZED_NEEDS_RECONCILE`
  へ commit。409 は再読込し、別 mutation/既 finalized なら **ABANDONED**(再割当・上書き・後退なし、per-JVM lock 非依存、
  割当済 epoch は破棄=安全な gap)。`scan`(crash 復旧) は Mango `aclEpochState $in [PENDING,FINALIZED]` で **状態付き文書のみ選択**
  (state-less な通常 Content は不可視)、`PENDING`→finalize、`FINALIZED`→**検証のみで据え置き**(enqueue/ACK は次増分)、異常
  (未知状態/mutationId 欠落/不正 epoch)は **error 記録 + 未処理保持**(黙殺しない)、bookmark ページング + 処理上限。**Solr/reconcile
  task/ACL cache に一切触れない**。
- **`Patch_AclEpochStateMangoIndex`**: 各 content DB に `(aclEpochState)` Mango index(§3 の (type,aclEpochState) は複数型跨ぎの
  ため state 単独に。state-less 除外 + `$in` 直サーブ)。冪等、Path A/B 登録。
- **fail-closed staging**: `finalizePending`/`scan` の **production caller ゼロ**(grep 確認)、bean に init/scheduler/cron なし、
  通常 Content を PENDING_EPOCH 等に初期化しない。⚠ 永続フォーマット追加: content DB に新フィールド
  `aclEpochState`/`aclEpochMutationId`/`aclSourceEpoch`(既定 absent)+ `(aclEpochState)` Mango index(view/2.4 持ち越し非タッチ)。
- **検証**: 単体 `AclEpochStateTest` 2/2、実 CouchDB IT `AclEpochFinalizationServiceIT` **9/9**(専用 throwaway DB で完全隔離 —
  finalize+epoch 割当+他フィールド保持 / 既 finalized 冪等・非再割当 / state-less skip / **mutationId 変化で ABANDONED** /
  **6並行 finalize→ちょうど1つ FINALIZED**(CAS・lock 非依存) / scan は PENDING を finalize し FINALIZED で停止 / **state-less を
  選択しない** / PENDING に mutationId 欠落=anomaly 記録+PENDING 保持 / FINALIZED に不正 epoch=anomaly)。CI に両テスト追加。
  実機: 再デプロイで (aclEpochState) index を bedroom/canopy に作成、atom 200、**scanner 非自動起動**(scan/finalize ログなし)、
  実 content に epoch-state doc ゼロ(通常 Content 不変)。

**増分2a: scanner 恒久飢餓ほか P1×3 + P2×2 + テスト強化**:
増分2 レビューで P1×3(実 CouchDB で FINALIZED が PENDING より先に返る→PENDING 恒久飢餓 / 未知状態が selector 外で不可視 /
`_rev` なし Document を finalize 可能)+ P2×2 + テスト不足を指摘され是正。修正順: scanner 優先→anomaly 可視化・共通 validator→
snapshot 前提→patch 失敗→決定的 IT。
- **[P1]#1 PENDING 優先 3-pass**: 旧 `$in [PENDING,FINALIZED]` 単一 pass は FINALIZED が cap を食い潰し PENDING を永久に
  finalize しない。**Pass1=PENDING 専用(常に最優先)→Pass2=FINALIZED 検証(残容量)→Pass3=bounded anomaly audit**。全 pass
  bookmark ページング + 共有 cap。PENDING が FINALIZED backlog で飢餓しない。
- **[P1]#2 未知状態の bounded audit + 共通 validator**: `$in` 外の未知状態は永久不可視、FINALIZED の mutationId 欠落も正常
  受理だった。共通 `validate()`(非String/未知 state / mutationId 欠落 / 不正 epoch を anomaly)を finalize・409 再読込・全 scan
  pass で共有。Pass3 は `{$exists:true, $nin:[PENDING,FINALIZED]}` で未知/非String/RECONCILE_ENQUEUED を bounded に検査。
- **[P1]#3 snapshot 前提(id/_rev 必須)**: public Document overload に `_rev` なし文書を渡すと epoch allocate 後に新規
  文書として PUT され得た。**allocate 前に id/_rev を必須検証**(Phase-2 = commit 済み Phase-1 文書のみ)。さらに CAS 書込みは
  必ず `getDoc`(添付スタブ付き)に対して行い、`_find` hint は所有 mutationId + 前提確認のみに使う→**添付を保持**。
- **[P2]#4 409 後破損は anomaly**: 再読込が未知状態/mutationId 欠落なら旧実装は ABANDONED_SUPERSEDED にしていた。共通
  validator を通し、**真正な別 mutation / 正常 finalized のみ ABANDONED**、破損は anomaly として throw。
- **[P2]#5 index patch を throw**: `connectorPool == null` で return だと PatchHistory 成功記録で再実行されない→**throw**。
- **§3 更新**: canonical の `(type, aclEpochState)` を採用した `(aclEpochState)` に是正(複数型跨ぎ + state 単独 `$in`/audit)。
- **検証**: `AclEpochStateTest` 2/2、実 CouchDB IT `AclEpochFinalizationServiceIT` **16/16**(専用 throwaway DB は **本物の
  (aclEpochState) index を作成**し `_explain` で index 使用を確認 = `_all_docs` fallback 排除)。新規/強化: **>cap FINALIZED +
  PENDING で PENDING 飢餓なし** / 未知状態 anomaly / FINALIZED の mutationId 欠落 anomaly / 409 後破損 anomaly / `_rev` 欠落は
  allocate 前に拒否・新規作成なし・epoch 非消費 / **inline attachment 保持** / 並行 6 worker **例外を捕捉して fail**(握り潰さない)。
  CI に両テスト追加。実機: patch success、index 存在、atom 200、scanner 非自動起動、実 content の epoch-state doc ゼロ。

**増分2b: scanner 別形態の恒久飢餓 P1×2 + UUID 強制 P2**:
増分2a レビューで「3-pass が同じ `summary.scanned` cap を共有 → (a) cap 以上 FINALIZED + UNKNOWN 1件で Pass3 が毎回一度も
走らず UNKNOWN 永久不可視、(b) cap 以上の異常 PENDING が selector 内に残り後方の正常 PENDING を永久に塞ぐ」+ state 消失の
ABANDON 誤り + mutationId 形式無保証、を指摘され是正。
- **[P1] 各 pass 独立 budget + valid/anomaly selector 分離**: 共有 cap を廃止し **4-pass 各々に独立 budget**(どの pass も他を
  飢餓させない)。各 pass を **valid selector**(`{state:PENDING/FINALIZED, mutationId:$exists:true}`)と **anomaly selector**
  (`{state:$in[live], mutationId:$exists:false}` / `{state:$exists, $nin:[PENDING,FINALIZED]}`)に分離し、**異常文書は valid
  selector に入らない**ため cap 超でも正常文書を塞がない。両飢餓モードを構造的に閉塞。**全4 selector が idx_aclEpochState を
  使用**(`_explain` で確認)。
- **[P1] state 消失は anomaly**: live 文書が**存在するのに `aclEpochState` だけ消失**した場合(marker 消失=破損)を、旧実装は
  `current==null`(delete race)と同じ ABANDONED にしていた。**delete race のみ ABANDONED、存在+state 消失は anomaly として
  throw・保持・報告**。pre-allocate 再読込で検出するため **epoch 非消費**。
- **[P2] mutationId UUID 形式強制**: 共通 validator が非blank String までしか確認していなかった。**canonical UUID を validator
  で強制**(非UUID は anomaly)+ `AclEpochState.newMutationId()`(Phase 1 が毎回新規 UUID を永続化する契約)を新設。
- **検証**: `AclEpochStateTest` **4/4**(newMutationId が毎回新規 UUID / canonical UUID 検証)、実 CouchDB IT
  `AclEpochFinalizationServiceIT` **19/19**。新規: **>cap FINALIZED + UNKNOWN→UNKNOWN 報告**(Mode A)/ **>cap 異常 PENDING +
  正常 PENDING→正常 finalize**(Mode B)/ 全 pass 進行 / **state 消失→anomaly + epoch 非消費** / **全4 selector が index 使用** /
  非UUID mutationId 拒否 + epoch 非消費。CI に両テスト。

**増分2c: durable quarantine で全異常型の進行性を保証(2b 残余の根治)**:
増分2b レビューで「valid selector(`mutationId:$exists`)が validator の有効条件(UUID + 有効 epoch)と不一致 → null/非String/blank/
非UUID mutationId・不正 epoch が valid pass に残り、>budget あると正常文書を永久に塞ぐ。『非UUID 限定』は受入理由にならない
(scanner 自身が破損を fail-closed で扱う機構)」と指摘され根治。
- **[P1] durable quarantine**: `validate()` が anomaly と判定した文書を見た瞬間、**`aclEpochQuarantined=true` を CAS 付与**
  (元フィールドは全保持=検査/修復用)。**全 scan selector が `{aclEpochQuarantined:{$exists:false}}` で quarantine 除外**。
  異常文書は最大1 scan で live selector から外れ、二度と正常文書を塞がない → **>budget の異常山でも有限 scan で解消し、後方の
  正常文書が finalize される**(進行性を構造的に保証)。selector を validator と完全一致させる代わりに、validator が弾く全型
  (null/非String/blank/非UUID mutationId・欠落・不正 epoch・未知 state)を quarantine で確実に除外。全4 selector は引き続き
  `idx_aclEpochState` 使用(`_explain` 確認)。Pass4 selector の `$nin` に `RECONCILE_ENQUEUED` を追加(有効 terminal を除外)。
- **[P2] cap 契約整合**: `DEFAULT_SCAN_MAX_DOCS` コメントを「全 pass 通算」→「per-pass budget」に、FINALIZED handler の
  「selector が UUID 保証」コメントを削除(selector は $exists のみ、UUID は validate が強制)。
- **検証**: 実 CouchDB IT `AclEpochFinalizationServiceIT` **22/22**。新規(複数 scan 進行性): **>budget 非UUID PENDING + 正常→
  有限 scan で finalize** / **>budget null・非String・blank mutationId + 正常→finalize** / **>budget 不正 epoch FINALIZED +
  正常 FINALIZED→正常は quarantine されず不正のみ quarantine** / 全4 selector index 使用(quarantine 除外込み)。既存の異常
  記録テストは quarantine 付与後も state 不変・errors 記録を確認。CI 済。
- **将来注記**: marker を ACK 後に消す段階(次増分)では、遅延 finalizer が「正常な marker 消去」を破損と誤認しない終端判定が
  別途必要(現状 `stillOursOrOutcome` は既存 marker 消失を anomaly 扱い=marker を消す増分がない今は正しい)。設計 §3 に記載。

**増分2d: quarantine の race / 回避経路 / terminal / 失敗報告を閉塞**:
増分2c レビューで「quarantine という新永続状態に race と回避経路がある」と P1×3 + P2×2 を指摘され是正。
- **[P1] 修復済み文書の quarantine 阻止(race)**: `quarantine()` が再GET後に**再検証していなかった**ため、非UUID PENDING を
  scanner が読む→正常 Phase 1 が新UUIDで修復→quarantine が修復後を quarantine、で正常 mutation を永続隔離し得た。**CAS retry
  ごとに `validateEpochFields` で再検証し、正常化(有効 epoch + 余分な marker なし)なら quarantine 中止**。
- **[P1] marker「存在」だけの回避経路**: 全 selector が `{$exists:false}` だったため `false`/`null`/`"false"`/非Boolean でも全 pass
  から不可視だった。CouchDB Mango の `$ne`/`$not` は**欠落フィールドに非マッチ**(MongoDB と異なる、実機確認)なので、true のみ
  除外し absent+非true を含めるには **`$or({$exists:false},{$ne:true})`** が必要(index 使用も実機確認)。全 selector をこれに変更、
  非true marker は**検出して true へ正規化**。marker 契約を厳密化(absent=処理 / true=quarantine 済 / それ以外=anomaly→正規化)。
- **[P1] direct finalizer の quarantine 無視**: `finalizePending`/`validate` が quarantine を見ず、修復が flag を消し忘れると
  inline finalizer が `FINALIZED + quarantined=true` を作り scanner が永久に task 化不能だった。**validate が quarantine を
  fail-closed で拒否**(Phase 1 は同一 commit で quarantine 解除する契約を設計に明記)。
- **[P2] terminal 監査 + 失敗報告**: Pass5 で **`RECONCILE_ENQUEUED` も validate**(不正 UUID/epoch/marker は quarantine、正常は
  カウント)。quarantine 書込み失敗を**握り潰さず** `quarantineFailures` 計上 + error 記録 + `more=true`。
- **検証**: 実 CouchDB IT `AclEpochFinalizationServiceIT` **29/29**。新規: 修復済み→非quarantine / 依然異常→quarantine /
  `false`・`"false"`・`0` marker→回避せず true 正規化 / quarantined の direct finalize→拒否・epoch非消費 / quarantine時
  attachment 保持 / 不正 RECONCILE_ENQUEUED→quarantine・正常→カウント / **quarantine 失敗→summary 反映(並行 bumper)** /
  全5 selector が `$or` 込みで idx 使用。統合 unit 80/80。CI 済。実機: patch success、index 存在、atom 200、scanner 非自動起動。

**増分2e: explicit-null marker(presence 契約) + doc 残差**:
増分2d レビューで「`null` marker に実装上の穴が残る」と P1 を指摘され是正(IBM Cloudant SDK の `DynamicModelTypeAdapterFactory`
は追加プロパティの JSON `null` を map に**present として格納**するため、`get()!=null` では「欠落」と「明示 null」を区別できない)。
- **[P1] presence 契約に統一**: `validate()` と `quarantine()` を **`containsKey` 判定**に変更(absent=処理 / Boolean `true`=
  quarantine 済 / それ以外の present 値=malformed anomaly→true 正規化)。明示 null は selector の `$ne:true` 枝で選択されるが、
  旧 `get()!=null` では un-marked 扱いで finalize/非正規化されていた穴を閉塞。marker/epoch 契約の対象を **epoch-state 保持文書に
  限定**と明記(state-less は通常 content で全 pass 非マッチ)。
- **[P2] Javadoc 残差**: 「four passes」→「five passes」、「`$exists:false` のみ除外」→ `$or` 説明、「ALL original fields
  preserved」→「malformed marker のみ true 正規化、他は保持」、scope を epoch-state 保持文書に限定と明記。
- **検証**: 実 CouchDB IT **33/33**(2d 29 + explicit-null 4: raw JSON `null` を scan が true 正規化 / direct finalize 拒否・
  epoch 非消費 / re-GET で null→quarantine / marker 除去+epoch 有効→abort)。raw HTTP PUT で明示 JSON null を保証。統合 unit 80/80。

**増分2f: 敵対的 workflow レビュー(6視点×独立検証)の confirmed 6件を閉塞**:
コミット前に epoch モジュール全体を多視点(concurrency/Mango/presence-null-type/fail-closed/progression/doc-drift)で敵対的
workflow レビューし、各 finding を独立 skeptic が default-refute で検証。**confirmed 6件**を全対応(核心は 2e の containsKey 修正を
STATE フィールドで漏らしていた同一クラス)。
- **[P2×2] presence 契約を STATE フィールドにも適用**: `quarantine()` と `finalizePending()` が `get(FIELD_STATE)==null` を使い、
  explicit-null state を「repaired state-less」と誤判定(quarantine されず / silent skip)。**両者を `containsKey` に**(present-null
  は corruption として validate→quarantine / anomaly)。
- **[P3] CAS 非収束を contention として分離**: finalize の CAS livelock(valid doc への競合)を `AclEpochAnomalyException` で投げて
  quarantine 経路(silent abort)に流していた。**`AclEpochContentionException` を新設**し、runPass で `contended`+`more` 記録・
  **quarantine しない**(valid doc を隔離しない)。
- **[P1] terminal 飢餓を永続カーソルで根治**: FINALIZED/RECONCILE_ENQUEUED は ACK 未実装で terminal-parked のため valid doc が
  selector を離れず、stateless bookmark=null では >budget valid backlog 背後の corrupt terminal を永久に到達不能だった。2つの
  per-doc terminal pass を**1つの cursored terminal audit** に統合し、**per-content-DB 永続カーソル**(`acl-epoch-audit-cursor`、
  aclEpochState なし=全 pass 非マッチ)で resume + 枯渇時 wrap。terminal 集合全体を scan 跨ぎで巡回し、corrupt terminal を**有限
  scan で quarantine**。⚠ 永続フォーマット追加(cursor doc)。
- **[P3] Javadoc**: `FIELD_QUARANTINED` の除外条件を実際の `$or({$exists:false},{$ne:true})` に是正。
- **検証**: 実 CouchDB IT **37/37**(2e 33 + 2f 4: present-null state→quarantine / present-null direct finalize→anomaly /
  contention→非quarantine / **corrupt terminal を >budget valid backlog 背後で有限 scan quarantine**)、`AclEpochStateTest` 4/4、
  統合 unit 80/80。workflow: 14 agents、6視点レビュー→各 finding 独立検証。

**増分2g: terminal-audit resume cursor の自己防衛(bookmark 回復 / CAS 保存 / type 検証 / contention 決定的検証)**:
2f レビューで「cursor は terminal 飢餓を閉じたが cursor 自身の障害モードが未閉塞」と P1×3 + P2 + P3 群を指摘され是正。
- **[P1] 不正/失効 stored bookmark で terminal audit が恒久停止**: `runCursoredPass` は保存 bookmark を無検証で使い、`invalid_bookmark`
  で毎 scan 400 → terminal audit が永久に進まなかった。**自己回復**: `BadRequestException` の body/message が `invalid_bookmark` を含み、
  かつ **STORED(当該 scan 由来でない)bookmark** の場合に限り、cursor を **CAS-clear して先頭から 1 回だけ再試行**。ピン索引欠落等の
  **一般的な 400 は invalid_bookmark 扱いせず伝播**(scan を fail させ設定不備を surface)。
- **[P1] terminal query を索引にピン**: `use_index=["acl-epoch-indexes","idx_aclEpochState"]` で固定。⚠ **`allow_fallback=false` は
  CouchDB 3.4+/Cloudant のパラメータで CouchDB 3.3.x(本番採用版)が `invalid_key` で拒否**するため送らない。full-scan fallback を
  起こさない保証は **use_index ピン + `_explain` 検証済みの索引 served selector** に依拠する(allow_fallback には依拠しない)。
- **[P1] 固定 cursor ID の異種文書衝突**: cursor doc に `type=aclEpochAuditCursor` + `schemaVersion` を持たせ、cursor ID を **異種文書が
  占有していれば `cursorFailure` を記録して当該文書を一切変更せず terminal audit を skip**(fail-closed。無関係文書を clobber しない)。
- **[P1] cursor 保存の握り潰し**: cursor 保存を **bounded `_rev` CAS retry** にし、`putBack()==null`(409)を成功扱いせず再試行、
  恒久失敗で `cursorFailures`++ / error 記録 / `more`=true(silent swallow しない)。
- **[P2] contention の決定的検証**: `putBack` を package-private 化し、**target の PUT を必ず 409 にする subclass** で finalize CAS を
  8 回確実に競合させ、`contended==1` / `more==true` / doc は valid `PENDING_EPOCH` 維持 / **非 quarantine** を assert。
- **検証**: 実 CouchDB IT **43/43**(37 + 2g 6: 不正 stored bookmark 自己回復→後方 anomaly 到達 / rebuild+失効 bookmark 自己回復 /
  異種 cursor 文書を attachment ごと不変で fail-closed / cursor 保存の恒久 conflict が summary に出現 / **service を scan 毎に再生成しても
  durable cursor で前進** / 決定的 8-CAS contention)、epoch unit/IT 合計 **76/76**。fail-closed staging 継続(standalone bean・production
  caller ゼロ・scheduler/init/cron なし)。実機: atom 200・両 patch success・scanner 非自動起動・実 content に epoch-state/cursor 文書
  ゼロ・counter 存在・CMIS create/read 無影響。sign-off 判定は §9 決定的テスト全 16 + live-Solr 並行 IT が揃う段階で継続。

**増分2h: cursor `schemaVersion` の厳密検証(配線前の必須条件として先行実施)**:
2g は承認されたが「`schemaVersion` が書かれるだけで検証されていない」P2 が残り、**scanner 自動起動 / ACL write path 配線の前に必ず
閉じる条件**とされたため先行対応。旧実装は type のみ確認していたので、**欠落 / null / 1.5 / `"1"` / 将来版 2 のいずれも現行 v1 として
受理し、保存時に 1 で上書き**していた。
- **read/save 共通 validator**: `cursorUnusableReason(Document)` を唯一の「この build がこの cursor を使ってよいか」の定義とし、
  **read と save の両方が同一関数を使用**(両者が乖離し得ない)。save は **CAS 試行ごとに再チェック**(試行間に別 writer が異種 /
  新版文書へ差し替える race に対応)。
- **厳密整数**: `type` 完全一致 + `schemaVersion` が **present かつ厳密整数**(`parseExactLong`: 非 Number `"1"` / 非整数 1.5 /
  範囲外 / 非有限を拒否)かつ **build の版と完全一致**。将来版 2 も拒否(新しい build の cursor を古い build が黙って降格しない)。
- **明示的 migration 契約**: 使用不能 cursor は**黙示的な upgrade / downgrade / 上書きを一切しない**。`cursorFailure`(+`more`)を記録し
  terminal audit を **skip**、**文書は一切変更しない**。運用者の対処は **cursor 文書の削除**(保持しているのは resume bookmark のみ
  なので、削除しても sweep が先頭から再開するだけ=通常の wrap と同一で正当性の損失なし)をエラーメッセージに明記。2f 時代の cursor
  (type あり・`schemaVersion` なし)は**黙って引き継がない**。
- **Javadoc 是正**: `runCursoredPass` の「any cursor read/write failure は bookmark=null へ縮退」は現行契約と不一致だったので、
  実際の三分岐(**使用不能な文書 → fail-closed skip / 一時的な read エラー → 報告して先頭から / save 失敗 → 報告 + `more`**)に修正。
- **検証**: 実 CouchDB IT **49/49**(43 + 2h 6: absent / explicit-null / `1.5` / `"1"` / `2` の各々で **cursorFailure + terminal audit
  skip + `_rev`・properties・inline attachment が完全不変** / `schemaVersion=1` は正常使用の positive control)、epoch unit+IT 合計
  **82/82**、`git diff --check` clean。**mutation test で bind を証明**: 版検証を削除すると**当該 5 件だけが失敗**する。

**増分3: effective-epoch(authoritative walk + pending gate + revalidation)**:
新規 `AclEffectiveEpochService`(§4.1 + §4.2 step 1/2/4)。統一 write 契約の **read 側半分**で、Solr realtime-GET /
`_version_` CAS / fence 判定(step 3/5/6 + §4.3)は次の ACL-UPDATE 増分。**ACL write path には未接続**(production caller ゼロ・
scheduler/init/cron なし・**書込みを一切行わない** read-only)。
- **authoritative walk**: `snapshot(repo, id)` が **CouchDB を直接読み**(ACL/content cache を経由しない、§4.6)、全依存
  (self + 継承祖先、relationship は両 endpoint chain)の `_rev` / `aclSourceEpoch` / `aclEpochState` / `parentId` /
  `aclInherited` を記録。継承規則は `AclServiceDelegate.calculateAclInternal` に完全準拠(`aclInherited==false`(root は常に
  これ)か親なしで停止、**absent は TRUE 既定**)。
- **pending gate**: 依存のいずれかが `PENDING_EPOCH` **または** `FINALIZED_NEEDS_RECONCILE`(mid-CAS 曖昧)なら
  `AclEpochPendingException` で**書かせない**。`RECONCILE_ENQUEUED` は確定済みなので gate しない。
- **effective epoch**: `max(aclSourceEpoch over self + 継承祖先)`。relationship(= `sourceId`+`targetId` を持つ文書 =
  `CouchRelationship` の永続形)は **両 endpoint chain と自身の max**(read 権限 = read(source) OR read(target) と整合)。
- **revalidate**: 記録した全依存を再読込し、**1つでも差があれば false**(caller は walk からやり直し)。祖先の挿入/削除/
  付け替えは必ずどれかの記録済み文書を書き換える(`parentId` は子側)ため、**topology 変化も `_rev` 差分で捕捉**。
- **fail-closed 群**: `aclSourceEpoch` は **absent=0**(§4.1 移行前)だが **present-null / 非整数 / 負値は corruption**
  (2e/2f の presence 契約)/ 未知 state・**quarantine 済み依存**は信頼不能 / **継承中の親が読めない場合は retryable**
  (`AclEpochUnavailableException`。strict `calculateAcl` と同契約で、継承 grant を黙って落として under-visible な fence 値を
  書かない)/ 循環・hop cap 超過(既定128)は fail-closed / **dangling endpoint は寄与ゼロ**(`SolrUtil.relationshipReaders`
  の前例)/ **target が実在しない場合のみ null 返却**(caller は retry でなく complete)。
- **quarantine 方針(レビューで確認済)**: **quarantine された祖先はその subtree 全体の ACL 索引更新を修復まで停止**させる。
  「epoch だけ読めるなら使う」例外は quarantine の意味を崩すため**却下**。ただし影響範囲が subtree 全体なので、**本番配線前の
  必須義務**を設計 §5.1 に明文化(task を削除/terminal FAILED にせず保持 / 阻害している祖先 id を metric+WARN で特定可能 /
  capped backoff / 修復は epoch state と quarantine marker を**同一 CAS** で正常化 / 修復後は同一 task が自動再開することを IT で証明)。
- **検証**: 実 CouchDB IT **28/28**(実際の永続形に対して: 継承 max / 非継承ノードで停止 / absent 既定 / 移行前 0 /
  pending gate(self・祖先・relationship 祖先・FINALIZED gate と ENQUEUED 非gate) / quarantine・非整数・負・present-null・
  未知 state 拒否 / 親不読 retryable / 循環 / hop cap / relationship の両 chain max・dangling・共有祖先を循環誤検知しない /
  revalidate(不変=true、祖先 epoch bump・move・継承 flip・削除・無関係 touch=false、pending 再適用))、epoch unit+IT
  **110/110**。**mutation test 3種で bind 証明**(pending gate 削除→5件失敗 / `aclInherited=false` 停止削除→walk-stop 失敗 /
  revalidate 常時 true→6件失敗)。実機: atom 200・bean context エラーなし・**何も自動実行されない**・実 content に
  `aclEpochState` / `aclSourceEpoch` 文書ゼロ・CMIS 正常。

**増分3a: 増分3 の差し戻し理由 P1×4 を閉塞(effective-epoch の正当性)**:
- **[P1] dangling endpoint の「不存在」を snapshot に記録していなかった**: `walkChain` は 404 で 0 を返すだけ、`revalidate` は
  **記録済み依存しか再検査しない**。よって「source が 404 の状態で snapshot → 同一 ID で endpoint が復活 → **relationship 文書自体は
  不変**なので revalidate が true → endpoint 抜きで計算した epoch を CAS」が成立していた。**negative dependency**(`exists=false`)を
  記録し、revalidate で**再出現したら false** にした。
- **[P1] quarantine presence 契約が state machine と不一致**: `aclEpochQuarantined=false` を正常受理していた(既存契約は absent のみ
  処理可・true は quarantine 済・**その他の present 値は malformed**)。false marker の破損文書から高い epoch を取り込むと後続の
  正常 writer を fence し得る。**containsKey で presence 判定**し、値に関わらず利用禁止に。
- **[P1] `RECONCILE_ENQUEUED` の不変条件を未検証**: ENQUEUED は**意図的に gate しない**ため、mutationId 欠落/非UUID や epoch
  欠落/0/負でも settled 依存として fence 値になっていた。**FINALIZED と ENQUEUED は canonical UUID + 正の epoch 必須**に。
- **[P1] relationship 判定と topology 抽出が fail-open**: 「両 endpoint field を持つか」で判定していたため、**片方が欠落/null/
  非String/blank だと通常 content に降格して両 endpoint chain が丸ごと消失**。`str()` も型不正を全て「欠落」に縮退していた。
  判定を**永続化された基底型 `type=="cmis:relationship"`**(`Relationship` の ctor が書く値。**サブタイプ**
  `nemaki:hasAttachment` 等も検出可)に変更し、relationship は **sourceId/targetId が present・非blank String 必須**、
  **ID はあるが参照先 404 の場合だけ dangling**、`parentId`/`sourceId`/`targetId` の present-null・非String・blank は anomaly、
  **非 relationship が endpoint field を持つ場合も anomaly**(安全側)。`type` 欠落は後方互換で通常 content 扱い(endpoint field
  を持てば上記規則で弾かれるので安全)。IT fixture も実永続形どおり `type` を持たせた。
- **構造的修正(再発防止)**: 上記 #2/#3 は「finalization と effective-epoch が別々の検証と別々の nested
  `AclEpochAnomalyException` を持っていた」ことが原因。**top-level `AclEpochAnomalyException` + 共通
  `AclEpochFields`**(`requireNotQuarantined` + `validate(..., stateRequired)`)に集約し、state machine の所有者と消費者が
  二度と乖離しないようにした。
- **検証**: effective-epoch IT **43/43**(+15)、finalization IT **49/49 据え置き**(共通化は挙動保存)、epoch unit+IT **125/125**。
  **4修正すべて mutation-bound**(absence 未記録→2件失敗 / false marker 受理→walk 1件+finalization 1件失敗 / ENQUEUED 不変条件
  削除→walk 4件+finalization 2件失敗 / endpoint-field ヒューリスティックへ戻す→2件失敗)。実機: atom 200・何も自動実行されない・
  実 content 無変更。

### 3.2.8 (2026-07-08) — マルチパートファイル名不正の 400 化

ブランチ: `release/3.2.8` (off `master`)。ファズ波で最後まで残っていた低重要度
残件 (CMIS 名/ファイル名の NUL バイト) を解消。スキーマ/永続化変更なし。

- **[Low] マルチパートのパートファイル名に NUL 等が含まれると 500** → 400 に
  (`NemakiBrowserBindingServlet.service`): `POST /core/browser/{repo}`
  (createDocument) は最初のパラメータアクセスでマルチパート本体を解析するが、
  `nul\0name.txt` のようなファイル名は Tomcat の file-upload パーサが
  `InvalidFileNameException` を投げ、CMIS ディスパッチ前に素の 500 として
  漏れていた。multipart POST では冒頭で解析を強制し、この
  **ファイル名不正例外のみ 400** (`invalidArgument`) に変換 (cause 連鎖を
  クラス名/メッセージで判定)、それ以外の解析失敗は元通り再送出。実機検証:
  `nul\0name.txt`→400、正常アップロード→201、プロパティ値内 NUL は従来通り
  作成 (切り詰め)、edge_fuzz2 再実行で 5xx ゼロ。
  注: NUL 以外の制御文字 (0x01 等) は Tomcat が受理し 201 (500 ではない) の
  ため対象外。

**検証**: 単体 431/431、QA 94/94、修正のライブ確認 (`nul\0name.txt`→400 /
正常→201)、モンキー/ファズ再実行で 5xx ゼロ。TCK フルは検索非依存の 6 グループ
(Connection/Basics/Control/Versioning/CRUD1/CRUD2) green、検索系 (Query/Types)
はローカル多重スイート連続実行による **Solr 非同期索引ラグ** (create 直後 query
のタイミング、失敗ケースは run 毎に入れ替わる) + TCK 自身の CRUD が作る残骸型
(queryName null) の既知環境要因で fail。非回帰確認: 索引キューをリセットすると
新規 doc は ~3 秒で索引され CONTAINS/query が返す、本リリース差分は索引/クエリ
経路に非タッチ。クリーン DB の権威的 E2E は CI (push 毎の fresh DB)。Playwright
ローカル通しはこの索引ラグで検索系スペックが同様に flaky (非回帰)。

### 3.2.7 (2026-07-08) — 同一 PWC 並行 checkIn の版重複修正

ブランチ: `release/3.2.7` (off `master`)。バージョニング系ファズ
(`version_fuzz.py`) で発見したデータ整合性バグ。スキーマ/永続化変更なし。

- **[Medium] 同一 PWC への並行 checkIn が版を重複生成**
  (`VersioningServiceImpl.checkIn` / `ContentServiceImpl.cancelCheckOut`):
  1 つの PWC に同時 checkIn すると、それぞれが新版を作成しうる (負荷実測:
  12 並行 checkIn が全成功 → 1 PWC から 12 版)。per-PWC write ロックで直列化
  はされているが、guard 読取 (`getDocument(pwc)`) が **contentCache の stale**
  を返し、先行 checkIn が既に PWC を消費 (削除) 済みでも後続が同一 PWC を
  再 checkIn していた。原因は `removeCmisCache` が objectDataCache のみ消し、
  `getDocument`→`getContent` が読む **contentCache を消していなかった** こと。
  修正: checkIn の guard 読取**前**に `removeCmisAndContentCache` で content/
  ACL/data を無効化して DB 鮮度で読み (削除を必ず観測→404)、`isPrivateWorking
  Copy()` ガードを追加。`cancelCheckOut` も削除後に `removeCmisAndContentCache`
  で PWC を全キャッシュから排除。実機検証: 20×12並行バリア checkIn バースト
  → 各 1 成功のみ (修正前は ~5/20 バーストで重複)、逐次 checkIn と版ライフ
  サイクルは不変。
- **同梱**: `tools/test-env/monkey/version_fuzz.py`

**本ファズ波でクリーンと確認**: 単発エッジ (checkout無しcheckin/二重checkout・
checkin/消費済みPWCのcancel) は 4xx (5xxなし)、並行 checkOut は PWC 1つのみ、
スタックした checked-out なし、PWC/フォルダ削除でオーファンなし。

**検証**: 20×12並行 checkIn → 各1成功、version_fuzz ×3 clean、QA 94/94。

### 3.2.6 (2026-07-08) — Browser Binding の認証ステータスコード

ブランチ: `release/3.2.6` (off `master`)。第2ファズ波 (`edge_fuzz3.py`) の
1 件。スキーマ/永続化変更なし。

- **[Low] 存在しないリポジトリ / 認証失敗が Browser Binding で 500** →
  401 に (`NemakiBrowserBindingServlet.getHttpStatusCode`): `/core/browser/
  {repo}/…` が未知 repo (や誤認証) で `CmisUnauthorizedException` を投げるが、
  ステータスマッピングに当該分岐が無くデフォルト 500 に落ちていた。
  `CmisUnauthorizedException → 401` を追加 (応答 body は既に
  `unauthorizedException` を返しており、ステータスのみ誤り)。
- **同梱**: `tools/test-env/monkey/edge_fuzz3.py` (認証境界 / 複数リポジトリ
  分離 / Webhook 受信プローブ)

**本ファズ波でクリーンと確認できた面** (最大の収穫): 不正な
`Authorization`/token/API-key ヘッダは全て 401 (バイパス・5xx なし)、
CSRF なし state-change は拒否、`bedroom`↔`canopy` 間で object/ACL/RAG の
漏洩なし、Webhook 受信は不正/巨大/無署名 payload で 4xx (5xx なし)。

**検証**: 未知repo→401 / 正常repo→200 / object-not-found→404 / 誤PW→401 を
実機確認、edge_fuzz3 再実行 0 findings、QA 統合 94/94。

### 3.2.5 (2026-07-08) — 入力堅牢性 + 並行性ハードニング (探索ファズ由来)

ブランチ: `release/3.2.5` (off `master`)。`tools/test-env/monkey` の
モンキー/ファズ一巡で発見した「不正・極端な入力が 4xx でなく HTTP 500」系 +
データ整合性レース 1 件を修正。スキーマ/永続化モデル変更なし。

- **[Medium] 同名の子を並行作成すると重複が作られる** (`ObjectServiceImpl`):
  CMIS 名一意チェックと実作成が非原子で、逐次は `nameConstraintViolation`
  (409) を返すのに並行だと全員がチェックを通過後に挿入 → 同一フォルダに同名の
  文書/フォルダが N 個永続化。`createDocument` / `createDocumentFromSource` /
  `createFolder` を親フォルダ単位の write ロック (`threadLockService`) で
  チェック+作成を原子化。実機: 10 並行同名作成 → 1 作成 / 9×409 (修正前は 10 重複)
- **[Medium] 全文 `CONTAINS()` に特殊文字を含めると 500** (`SolrPredicateWalker`):
  `buildDualFieldQuery` が語を `"…"` 括りにして `toString()`→Solr 再パースする
  経路で、裸の `"` / `\` が不均衡クエリを生み既定フィールド `_text_` に落ちて
  500。語値を Solr クエリ文字列メタ文字用にエスケープ (`\` → `\\`、`"` → `\"`)。
  通常検索は不変。+3 regression (SolrPredicateWalkerMultilingualTest)
- **[Medium] MCP の JSON-RPC `params` が非オブジェクトだと 500 HTML**
  (`NemakiwareMcpServer`): `params` の Map キャストが try/catch 外にあり
  ClassCastException が素通り → Tomcat 500。`instanceof Map` チェックで
  非オブジェクトは空 params として許容。+1 regression
  (NemakiwareMcpServerRedactionTest)
- **[Low] 不正インポートアーカイブを 400 に** (`ImportExportResource`):
  破損/非ZIP アップロードが 500 だったのを、`isMalformedArchive` で ZipException
  系を判定してクライアントエラー (400) に。真のサーバ障害は 500 のまま
- **[Low] RAG 検索の超長クエリを 400 に** (`RAGSearchResource`): 埋め込み
  モデル上限超過が backend で 500 になっていたのを、8000 字上限で 400 に
- **同梱**: `tools/test-env/monkey` (ui_monkey.cjs / api_fuzz.py / write_fuzz.py /
  edge_fuzz2.py) — 本 findings の出所。回帰用に保持

**既知の低重要度残**: CMIS 名に NUL バイトを埋めるとリクエストディスパッチ前の
マルチパート解析層で 500 になる (不正入力の拒否、security/data 問題ではない)。

**検証**: 新規 regression 4 + 単体ネット 393/393、5 修正すべて実機デプロイで
確認、モンキー/ファズ再実行で NUL 残件以外の 5xx なし。UI レンダリング系は
~1,400 ランダム操作でクラッシュ 0 (v3.2.4 の Table key 修正が保持)。

### 3.2.4 (2026-07-08) — ドキュメント一覧のフォルダ遷移時クラッシュ修正

ブランチ: `release/3.2.4` (off `master`)。デモ動画収録中に写り込んだ UI
クラッシュを修正。サーバ/API/スキーマ変更なし、UI 1 ファイルのみ。

- **[High, UI] フォルダ遷移でドキュメント一覧がエラー画面に落ちる**
  (`DocumentList.tsx` の Ant `Table`): ログイン直後やフォルダ切替時に
  `Failed to execute 'insertBefore' on 'Node': ... not a child of this node`
  で致命的エラーバウンダリ (「エラーが発生しました」) に落ちることがあった
  (実測 ~6 回に 1 回)。原因は Table の `loading` フラグと `dataSource` が
  重なったコミットで切り替わる際、rc-table が**旧フォルダの行を別フォルダの
  行へ再構成**しようとしてコミットフェーズで DOM 不整合を起こすこと。
  minify された本番ビルドでのみ顕在化し dev では出ないため見逃されていた。
  Table にフォルダ/検索単位の `key`
  (`key={isSearchMode ? 'search' : (selectedFolderId || 'root')}`) を与え、
  遷移時は行を再利用せず table body を作り直させることで、データセットを
  跨ぐ再構成を根絶。診断は一時的な sourcemap ビルドで本番エラーの React
  component stack (AuthContext → Layout → DocumentList → Table → Spin) を
  de-map して特定。検証: 本番ビルドに対し login→navigate を 30 連続で
  クラッシュ 0 (修正前 ~1/6)、UI vitest 191/191

### 3.2.3 (2026-07-07) — ネストグループACL解決 + RAGチャンク消失修正

ブランチ: `release/3.2.3` (off `master`)。`tools/test-env` (権限多様性×ベクトル
検索×MCP のデモ環境シードツール、本リリースに同梱) の構築中に発見した 2 バグを
修正。CouchDB view / patch / schema / Mango 変更なし (2.4 データ持ち越しパス
無変更)。

- **[High] ネストグループのメンバーに親グループ宛 ACL の権限が付与されない**
  (`UserGroupDaoDelegate.checkIndirectGroup`): 実効 ACL 評価が参照する
  `joinedDirectGroupsByGroupId` ビューは複合配列キー `[groupId, n]` を emit
  するが、startkey/endkey を**事前シリアライズした文字列**で渡していたため
  Cloudant SDK が JSON 文字列キーとして送信し配列キーに不一致 → 祖先グループ
  展開が常に 0 行でサイレント不発。課グループのメンバーが本部グループ宛 ACL の
  フォルダに permissionDenied になっていた (CMIS/クエリ/RAG 全経路)。List で
  渡すよう修正。RAG インデックス側 `ACLExpander` も nested groups
  (`getGroups()` = nemaki:groups) を走査していなかったため追加 (visited 先行
  チェックつき)。cached DAO の GroupItem **create()** が joinedGroupCache を
  無効化していないギャップ (update/delete のみ対応) も修正。
  +2 UserGroupDaoDelegateNestedGroupTest / +2 ACLExpanderTest。実機検証:
  素のネストグループ (非展開) で子グループのメンバーが親グループ宛 read の
  フォルダを閲覧可 + RAG ヒット、撤回で消失
- **[High] ACL 変更が配下文書の RAG チャンクを全消失させる**
  (`RAGIndexingServiceImpl.updateDocumentACL`): block-join 親への Solr atomic
  update はブロック全体を置換し子チャンクを削除する (実測 300→0、検索は
  document_vector のみに劣化し類似度 0.94→0.27)。**保存済みフィールド
  (chunk_text / 各 vector は stored=true) からブロック全体を再構築**して
  readers を差し替え、**単一 add リクエスト**で置換 (root id 再投入の
  delete-by-_root_ カスケードで旧ブロックが消えるため明示 delete 不要 =
  delete後add失敗でインデックス消失する窓なし)。再エンベディング不要。
  ragId 単位の Guava Striped ロック (64 stripes) で indexToSolr と直列化
  (stale スナップショットによるクロバー防止)。チャンクは全ページ取得 (旧実装
  の `rag.acl.chunk.update.limit` 超過分 ACL 未更新ギャップも解消、limit は
  ページサイズに転用)。commit は `rag.solr.commitWithin` に整合 (addBlock
  ヘルパーで indexToSolr と共有)。+3 RAGIndexingServiceImplAclUpdateTest。
  **既存環境の注意**: 本修正前に RAG 索引済みフォルダの ACL を変更した環境は
  チャンクが消えている可能性があるため
  `POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex` を一度
  実行して復旧すること
- **[Medium] Range 指定の getContentStream が Content-Length を誤申告**
  (`ObjectServiceImpl.getContentStreamInternal`): 本文は range で切り出す
  (AttachmentNode.getInputStream) のに ContentStream.length に**全長**を
  渡していたため、AtomPub が「Content-Length=全長 + 切り詰め本文」を返し
  クライアントが Premature EOF (TCK ContentRangesTest。実測: 33 バイト
  本文に Content-Length 36)。**master 由来の pre-existing** (master ビルド
  WAR との A/B で実証、2026-02-16 のメタデータ長最適化コミット由来) を本
  リリースの QA で発見・修正。`computeRangeAwareLength` が切り出しと同一
  セマンティクス (offset 超過→0、残量クランプ、負 offset→0) で申告長を
  計算。+9 ObjectServiceImplRangeLengthTest (TCK ケース行列)。実機で
  `Content-Length: 33` / `Content-Range: bytes 3-35` を確認
- **同梱: `tools/test-env`** — 階層組織 15 ユーザ / ネストグループ 13 /
  フォルダ 31 + エリア別 ACL / 日本語 Office 文書 300 件の宣言的シード +
  MCP シナリオランナー (同一クエリのユーザ別応答差デモ)。既定はグループ
  メンバーを推移的展開して投入 (3.2.3 未満との互換)、`--no-flatten` で
  ネスト解決自体を検証可能。リポジトリルートへの set_acl/deleteTree を拒否
  する安全ガード付き。詳細は `tools/test-env/README.md`

**TCK 備考**: 永続 volume 上のフル run は初回 `baseTypesTest` が E2E 残骸
カスタム型 (queryName null の `test:customFolderForE2E` 等 20 型) で fail
する既知の汚染クラス。残骸型を type delete API で掃除して再実行し green を
確認 (非回帰)。`contentRangesTest` は上記 Range バグ (pre-existing) が原因
で、修正後 green。

**検証**: 新規 regression 16 件 (nested-group 4 + ACL update 3 + range 9) +
ACLExpanderTest 26/26、RAG パッケージ 306/306、隣接スイート
(UserGroupSearch/MCP auth+tools/IngestAuthorization) 136/136、QA 統合
94/94、**TCK 実効 38/38** (初回 36/38 → 残骸型掃除 + range 修正で全 green)、
**Playwright chromium フル 938 passed / 92 skipped** (fail 2 は TEI 稼働で
初解禁された rag-search spec の 403 未追随 = ApiCsrfFilter 由来のテストバグ、
修正して 15/15。flaky 1 は retry 通過)、実機 grant/revoke 検証 (75 文書
フォルダでチャンク数不変 + reader 双方向反映)。マルチアングルレビュー
(correctness 3 + cleanup/altitude/conventions 5 観点) で delete-without-add
窓 / 並行クロバー / create 時キャッシュ無効化漏れ等 10 findings を検出し
全件反映済み。

### 3.2.2 (2026-07-03) — Codex セキュリティレビュー remediation

ブランチ: `release/3.2.1-security`。3.2.1 tag に対する Codex deep-repository
スキャンの follow-up。Medium 2 件を regression test + 実機 PoC 付きで修正、Low 1
件は既知 residual として再確認。CouchDB view/patch/schema/Mango 変更なし
(2.4 データ持ち越しパス無変更)。

- **[Medium] Diagram rendition の PlantUML preprocessor include 対策**
  (`DiagramRenditionManagerImpl`): `.puml`/`.dot` の文書内容をサーバ側で SVG
  レンダリングするが、PlantUML の既定 profile は **LEGACY** で `!include` /
  `!includeurl` によるローカルファイル読取 + URL fetch (local-file-read / SSRF)
  を許す。profile を **SANDBOX** (ファイル/ネットワークアクセス不可) に静的
  初期化で強制 (PlantUML が profile を cache する前) + source size (512KB) /
  render timeout (15s、virtual-thread executor) / output size (20MB、bounded
  stream) を制限。container image にも `-DPLANTUML_SECURITY_PROFILE=SANDBOX` を
  付与。`DiagramRenditionSecurityTest` 4/4 (SANDBOX profile 確認 + ローカル
  ファイル `!include` が secret を SVG に漏らさない + size cap + benign render)。
- **[Medium] Archive import が非 admin に attacker-supplied ACL を適用しない**
  (`ImportExportUtils.isAclApplyAllowed` + `ZipImporter` + `FilesystemImporter`):
  ZIP/ACP import が archive 由来 ACE を `updateInternal` で直接永続化しており、
  通常 ACL 経路の `CAN_APPLY_ACL_OBJECT` gate を迂回 → create-child しか持たない
  importer が imported object に任意 ACL を設定可能だった。archive ACL の適用を
  **admin (と SystemCallContext による system 復元) 限定**に変更、非 admin import
  は object の既定/継承 ACL を保持し warning + result status `partial` を返す。
  admin-only 側の filesystem-import path にも同 guard を追加 (Codex は未指摘だが
  横展開)。permissionDenied 方式は非 admin で `calculateAcl` の side-effect が
  runtime 挙動を不明瞭にしたため、明示的 admin gate (Codex の alternative
  remediation) に変更 = 決定論的で side-effect free。実機 PoC 検証: admin import
  は ACL 適用 (復元機能維持)、非 admin `cmis:write` importer は injected ACE
  blocked (warning emit、"Skipping archive ACL" ログ)。import/rendition regression
  70/70。
- **[Low] HTTPS connect-only DNS rebinding residual — 再確認 (新規修正ではない)**:
  outbound HTTPS は send 前に再 validate + 危険アドレス reject するが TCP 接続先を
  pin しないため microsecond connect-race が残る。TLS 証明書検証で data-exchange
  SSRF は既に閉 (body read / token leak なし)、TCP-connect side-effect のみ残余。
  `AdapterHttpClient` Javadoc + `REVIEW_PACKET §6` で既知として追跡済。恒久策は
  custom connect-time IP-pin transport (別 effort)。

**E2E flaky-test 安定化 (product-code 変更なし、`f762a18fb`〜`646697d14`)**: 既存の
Playwright flaky 7 spec を根治 (いずれもセキュリティ修正と無関係、受理済み 3.2.1 も
同じ間欠 tail を抱えて出荷):
- `group-hierarchy-members` 循環参照: グループ一覧がページネーションされ循環検出が
  現ページのみ参照 → step3/4 は `circ-` 検索で A/B だけをページに載せてから検証、
  step1/2 は API 作成 (member は `groups` JSON 配列) で serial retry を冪等化
- `custom-property-input` / `config-viewer`: 型 option を id で filter + Escape close、
  config テーブルが populate してから行数比較
- `property-editor` / `archive-restore-consistency`: 共有 setup 文書/フォルダを遅い
  UI upload ではなく CMIS API で作成 (文書は folder 内に配置)
- `document-viewer-auth` / `verify-cmis-404-handling`: 手動 login を AuthHelper (3×
  retry) に置換 + documents テーブル reload-retry

**⚠️ 既知の未達 (deferred) → 解消 (test-infra ブランチ)**: 「毎回 0 hard-failure の
フル Playwright run」は 3.2.2 リリース時点では未達だったが、後続の test-infra 作業
(ブランチ `test-infra/e2e-stabilization`、off `release/3.2.1-security`) で**収束を達成**
(まだ push/merge/tag していない)。詳細は下記「### test-infra E2E 安定化」節。**検証**:
security 修正の DiagramRenditionSecurityTest 4/4・import/rendition 70/70・実機 PoC、
clean-DB 3.2.2 で **TCK 38/38**・Java 130/130・vitest 191/191、フル Playwright は 3.2.2
時点で 926〜933 passed / 99 skipped (小さく run-varying な間欠 flaky tail あり)。

### test-infra E2E 安定化 (2026-07-05、ブランチ `test-infra/e2e-stabilization`)

3.2.2 で deferred した「毎回 0 hard-failure のフル Playwright run」を根治。**製品コードは
1 箇所** (client-side timeout)、他はすべてテスト側 + テストデータ衛生。5 commit
(`64fad552c`〜`2189cd719`、off `release/3.2.1-security`)。**未 push / 未 merge / 未 tag**。

**根本原因 (データ蓄積説を否定して特定)**: flake の実体は「読み込み中...」でハングする
**client-side metadata XHR の停止**。サーバは健全 (`getChildren(root)` 0.27s、repo 26 docs)
で蓄積は無関係。停止した XHR がタイムアウトを持たず SPA のロード状態が永久に未解決に
なるのが個々の flake。

**修正**:
- **製品側 (最大レバレッジ)** `CmisHttpClient.ts`: metadata 系リクエスト
  (getObject/getChildren/型定義/ACL) に `DEFAULT_METADATA_TIMEOUT_MS=60000` を導入。
  停止時に reject → UI が error/retry 可能に。コンテンツ転送 (arraybuffer/blob/FormData)
  は従来通りノータイムアウト (明示指定時を除く)。
- **横断 (テスト側)** `auth-helper.ts` の `login` + `test-helper.ts` の
  `navigateToDocuments` に reload-retry ループ (3× `.ant-table` 可視待ち)。
- **個別** `document-viewer-auth.spec.ts`: (a) multi-access テストがナビ後の非同期
  getObject を待たず `.ant-descriptions` を即 count していたレースに settle-wait 追加、
  (b) back 復帰後に行再ロードを待ってから再クエリ → 謳い文句どおり 3 文書を実際に検証
  (従来は 0 行検出で早期 break し実質 1 文書)。
- **テストデータ衛生**: (a) `ingest-pipeline-e2e.spec.ts` が全 doc を
  `targetFolderPath:'/'` で import し `cleanupOrphans` は stub connector/profile しか
  消していなかった (imported doc が root に ~16/run 蓄積) → beforeAll+afterAll で
  root doc を sweep。(b) 休眠していた `global-teardown.ts` (config でコメントアウト) を
  有効化し、`test-*` + 8 ingest prefix の root backstop sweep (doc=delete/folder=deleteTree、
  seed 5 objects は prefix 不一致で安全、suite 全体完了後に 1 回のみ実行)。
- **skip の棚卸し + 真のバグ 1 件**: ラン時 100 skipped のうち ~84 は環境ゲート
  (Atlas/Keycloak/LDAP/TEI 未配備で正しく skip、simple スタックでは un-skip 不可)。
  唯一の**真のバグ**は `permissions/access-control.spec.ts` の 3 skip —
  beforeAll の test user 作成が `POST /core/api/v1/cmis/.../users` に X-Requested-With
  欠落で **CSRF 403** され (3.2.1 の ApiCsrfFilter 追加が原因)、user 未作成 → 3 test が
  毎回サイレント skip だった。CSRF ヘッダ追加 + afterAll に api/v1 DELETE (同ヘッダ) で
  user cleanup 追加 → **6 passed/3 skipped → 9 passed/0 skipped**、user 蓄積なし。
  他 spec は `ApiHelper.createUser` (REST_REPO_CSRF_HEADERS 込み、legacy `/core/rest/repo/`)
  経由なので無傷。

**検証 (フル chromium、workers=1 / retries=2 / ~1033 tests / 各 1.2h)**:

| Run | passed | failed | skipped | 備考 |
|---|---:|---:|---:|---|
| Run 1 | 934 | 1 | 98 | 1 = doc-viewer:320 レース (本作業で修正) |
| Run 2 | 933 | **0** | 100 | timeout 修正稼働 |
| Run 3 | 933 | **0** | 100 | 全修正込み + teardown で root 27→5 (seed のみ)、0 residue |
| Run 4 | 936 | **0** | 97 | + access-control CSRF 修正 = +3 pass/-3 skip、回帰ゼロ、root 5 seed、user 蓄積なし |

以前 flaky だった config-viewer / group-hierarchy / archive-restore / custom-property-input /
property-editor / verify-cmis-404 / document-viewer-auth は全 run で green。pass/skip の
微差 (934/98↔933/100) は条件付きスキップの通常変動 (global-setup の test user provisioning
タイミング等) で回帰ではない。root は run 後に seed baseline (5 objects) へ復帰。

### 3.2.1 (2026-07-02) — セキュリティ監査 remediation + cross-repository 分離 + 依存 CVE

ブランチ: `release/3.2.1-security` (off `master` = `3cb56a92b`)。Fable5
マルチエージェント包括セキュリティ監査 (6 ドメイン: 認証 / 認可-IDOR /
インジェクション-XML / SSRF / ファイル-CSRF-DoS / フロント-暗号-設定) +
第2弾 (依存 CVE / リポジトリ間分離) で確定した課題を優先順位順に修正。各修正に
regression test + 実機検証 (TCK + Playwright + redeploy)。

**セキュリティ (認証/認可)**:
- [High] `nemaki:allowedAuthMethods` 認証ポリシーのバイパス
  (`a40960089`): `disabled` / `cloud`-only アカウントのパスワードログインを
  拒否する gate が主要 CMIS 経路 (`AuthenticationServiceImpl.
  getAuthenticatedUserItem`) のみで強制され、**3 つの他経路が迂回**していた:
  api/v1 `AuthResource.login`、MCP `McpAuthenticationHandler.validateCredentials`
  (Basic auth + login tool)、legacy `UserItemResource.isAdminOperaiton`
  (admin 操作 re-auth)。policy を `AuthenticationUtil.isAuthMethodAllowed
  (UserItem, method)` に単一情報源化し `AuthenticationServiceImpl` は委譲、
  3 経路で enforce。拒否は誤パスワードと同じ 401 / "Invalid credentials"
  (正パスワードの disabled アカウントを oracle 化しない)。MCP は legacy
  `User` が policy を持たないため optional `ContentService` で `UserItem`
  解決。+6 AuthenticationUtilTest / +3 McpAuthenticationHandlerTest
- [Low] セッショントークン検証の非定数時間比較 (`92f7ad752`):
  `TokenServiceImpl.TokenMap.validate()` の `String.equals()` を
  `MessageDigest.isEqual` に統一 (主要トークン検証経路。他経路は既に定数時間)。
  期限切れ掃除の full scan は維持
- **cross-repository テナント分離** (`ba18af200`): `/v1/admin/*` は常に
  デフォルト repo で認証するのに、connector 委譲ガバナンスと import-profile
  操作は任意の対象 repositoryId に作用していた → (1) デフォルト repo admin が
  他 repo の import-profile を操作/ガバナンス列挙可 (admin が per-repo persona
  でない)、(2) 非 admin の委譲認可が (default-repo 認証の) username を対象 repo
  の ACL に突合 → 他 repo の同名ユーザーに自動アクセス。修正:
  `IngestAuthorizationService.canManageProfileForFolder` /
  `canUseConnectorForDelegatedProfile` に「対象 repo == 認証 repo」不変条件
  (`isAuthenticatedRepository`) を admin 短絡より前に追加 (fail-closed、他 repo
  の ACL を一切参照しない)。`AuthenticationFilter` は per-repo 面
  (import-profiles / connectors) のみ `X-Nemaki-Repository` ヘッダで対象 repo
  認証、他の `/v1/admin/*` (Purview / lineage / integration-settings /
  ingest jobs-scheduler / connector CRUD) はデフォルト repo 固定 (グローバル
  設定 = デフォルト repo admin のみ、既存面を広げない)。`ImportProfile
  DefinitionController` は全操作を認証 repo に限定 (list scope / by-id は
  profile.repositoryId 一致必須、他 repo は 404)。`ConnectorDefinition
  Controller` は connector CRUD をデフォルト repo admin 限定、ガバナンスは
  repositoryId 一致必須。UI は管理系 ingest 呼び出しにログイン repo ヘッダ付与。
  +3 IngestAuthorizationServiceTest cross-repo / 6 controller test 更新
  (ingest suite 171/171)。**OIDC 等での「同一人物なら他 repo も見える」は別問題
  (未着手)**

**依存 CVE** (`39218c4be`): `mvn dependency:tree` (494 artifact) 精査で 2 件:
- commons-compress 1.24.0 (compile scope、Tika/POI のアーカイブ解析で到達) →
  1.27.1 (CVE-2024-25710 DUMP 無限ループ DoS / CVE-2024-26308 pack200 メモリ
  DoS、POI 5.4.1 要件とも整合)
- lucene-queries/-core を 9.11.1 固定していたが solr-core 9.10.1 は他 Lucene
  モジュールを 9.12.3 で持ち込む → Lucene は全モジュール同一版必須のため 9.12.3
  に整合 (全 21 モジュール収束)
- npm 本番依存 0 件 (dev の esbuild low のみ、Windows dev サーバ限定)

**RAG cross-repository** (`f1344966b`): ベクトル検索の補助 Solr クエリ 3 本
(`getDocumentVector` シード / `enrichWithFirstChunk` / `enrichResultsWith
ParentInfo`) が共有 `nemaki` コアに対し `repository_id` fq を欠いていた
(RAG id は非 repo スコープの生 CMIS ID)。ID 衝突 + 非 repo スコープの reader
ACL トークンと組み合わさると他 repo のベクトル/メタデータ/チャンクが混入し得た
→ 全 6 RAG クエリに `escapeAndQuote` 付き `repository_id` fq を付与。RAG
regression 164/164

**衛生**: 追跡されていた `AclServiceImpl.java.rej` (一時 ACL debug ログの適用
失敗パッチ、セキュリティロジックなし) を削除 (`92f7ad752`)

**アップグレード安全性**: 全修正は**ランタイム認可 + 認証フィルタ + UI + pom のみ**
で、CouchDB view (dump) / patch / 永続化モデル / Mango index の変更ゼロ。2.4
時代からの CouchDB データ持ち越しパスに一切触れない。`allowedAuthMethods`
gate は「プロパティ未設定 → 全許可」の後方互換デフォルトで、当該プロパティを
持たない旧データはパスワードログイン継続可。

**検証**:
- Java unit/regression: AuthenticationUtilTest 13/13、McpAuthenticationHandler
  Test 24/24、TrustedProxyTest 10/10、IngestAuthorizationServiceTest 37/37、
  ingest suite 171/171、RAG 164/164
- reactor `mvn clean install`: BUILD SUCCESS、core.war 3.2.1、UI `tsc` クリーン
- **TCK 実効 38/38**: 永続 volume の初回 run は `baseTypesTest` (残骸カスタム型
  `test:customFolderForE2E`) と `contentChangesSmokeTest` (蓄積データ) で 2 fail
  だったが、クリーン DB (fresh init、`ci-complete-setup.sh` = Setup Wizard
  `/apply`) で Types 3/3・Query 6/6 green を実証 → データ汚染で回帰ではない
- **Playwright chromium フル**: 928 passed / 3 flaky-fail / 3 flaky / 99 skipped。
  3 fail はいずれも本修正と無関係な UI (group-hierarchy 循環参照防止の Ant
  モーダル `キャンセル` クリック timeout、custom-property-input) で、再実行・
  エラー解析により flaky (非回帰) と確定
- productVersion 3.2.1 をライブ検証 (setup/state + cmis:productVersion)

**Low ハードニング (v3.2.1 tag 内、上記の後続コミット)**: 監査の Low 項目を
5 コミットで解消:
- SetupVector SSRF: `UrlValidator` が `SsrfGuard.extractEmbeddedIpv4` で IPv6
  transition (NAT64/6to4/Teredo/IPv4-mapped/-compatible) を unwrap して
  metadata/private を検出 (`allowPrivateNetworks` 挙動不変)。+6 test
- ZipImporter コンテンツ再 bound: `createContentStreamFromZip` が申告
  `getSize()` だけでなく実バイト数を bounded `FilterInputStream` で cap (streaming
  維持、超過で IOException)。+4 test
- UI セキュリティヘッダ: 新 `UiSecurityHeadersFilter` (`/ui/*`) が nosniff /
  X-Frame-Options: SAMEORIGIN / Referrer-Policy を enforcing、CSP は **Report-Only**
  (Ant Design CSS-in-JS + pdf.js を壊さない非ブロック baseline。違反レビュー後に
  enforcing 昇格)
- FilesystemStorageAdapter パス containment: `buildPath` を normalize +
  storage-root `startsWith` check。+4 test
- 依存 hygiene: spring-tx 7.0.4→7.0.7 / cxf-rt-ws-policy 4.1.3→4.2.0 の skew 整合、
  旧 `woodstox-core-asl 4.4.1` 除去 (modern woodstox 6.5.0 が StAX provider)、
  jackson 全モジュールを `jackson-bom` で 2.21.1 に整合
- dev-compose 注記: `docker-compose-simple.yml` に「開発専用・0.0.0.0 公開」バナー、
  `docker/realm-export.json` に dev-only `_NOTE`

**deferred 2 件の解消 (Low ハードニング続き、v3.2.1 tag 内)**:
- **RAG `readers` トークンの repo スコープ化 (恒久策)**: `ACLExpander` を単一
  情報源として index/query 両側のトークンを `user:{repo}:{id}` /
  `group:{repo}:{id}` / `anyone:{repo}` に変更 (repo は index/query で verbatim
  一致、user/group のみ Solr escape)。**⚠️ BREAKING (index 形式変更)**: 後方
  互換にすると衝突が再発するため意図的にハードブレーク。**アップグレード後は
  RAG index の再構築が必須**。再構築前は fail-closed (旧形式 doc が RAG 検索に
  出なくなるだけで、リポジトリ間漏洩はしない)。RAG は新機能で 2.x→3.x 移行自体
  再インデックス必須。ACLExpanderTest 24/24
- **UI CSP の昇格手順を実施**: 稼働アプリをブラウザで walkthrough (Report-Only)
  し違反収集 → 核 SPA (ログイン/一覧/Ant/pdf.js) は全て same-origin で違反ゼロ
  (pdf.js worker は `/core/ui/pdf-worker/` = `'self'`)、唯一の cross-origin は
  optional な Google Drive / Microsoft(MSAL) / Purview 連携 → connect-src に
  `googleapis.com` / `accounts.google.com` / `graph.microsoft.com` /
  `login.microsoftonline.com` / `*.purview.azure.com`、frame-src に MSAL silent
  iframe origin を追加。**設定可能化**: `-Dnemakiware.ui.csp.mode`
  (report-only 既定 / enforce / off) と `-Dnemakiware.ui.csp.extraOrigins`
  (カスタム IdP / 非 global Azure cloud 用)。既定は upgrade 安全な report-only の
  まま、operator は自 deployment の cloud/IdP flow 確認後に 1 property で
  enforce へ昇格可。enforce モードでログインページが違反ゼロで完全描画されること
  を実機確認

**残 (真に deferred)**:
- CSP enforce をデフォルト化するか (現状 report-only 既定。cloud deployment の
  OAuth flow は実クレデンシャルでの検証後が安全)
- 2.4→3.2.1 アップグレードスモーク (2.4 dump 用意待ち)

#### 3.2.1 追加機能 (post-audit、v3.2.1 tag 内): 管理UIからのクラウド/SSO認証設定 + Markdown画像埋め込み解決

初回 audit remediation の後、運用性 UX を 2 件追加 (いずれも runtime authz /
永続化モデル不変、CouchDB view/patch/schema/Mango 変更なし)。

- **管理UIからのクラウド/SSO認証設定 (admin-managed precedence)** (`4ded14653`
  / `5c16ed090`): Setup Wizard は Google/Microsoft の clientId や
  Keycloak/OIDC/SAML 設定を `-D` システムプロパティとして書き込むため、
  統合設定画面がこれらを `source=system_property` としてフィールドをロックし
  「システムプロパティで上書きされているため管理画面からは変更できません」と
  表示 → 設定ファイル編集 + redeploy 無しに clientId 変更や OIDC の Keycloak
  realm 変更ができなかった。`PropertyManager.isAdminManagedDynamicKey` の対象を
  `cloud.auth.` / `cloud.drive.` / `sso.` / `oidc.` / `saml.` に拡張し、これら
  admin-managed key は **nemaki_conf(管理UIの値)が `-D`/env bootstrap より優先**
  (blank 値は deploy 既定へ fall-through = クリアで復帰)。`IntegrationSettings
  Controller` は両応答ビルダーで per-key `overridable` map を返し、
  `IntegrationSettingsService.readSettingSource` は admin-managed key に非空
  nemaki_conf 値があれば `couchdb` を報告。UI (`SettingsFormFields`) は
  admin-managed key を source=system_property でも編集可能なまま維持し、ロック
  警告の代わりに info notice (`overridableNotice`) 表示。`overridable` を
  service 型 / `useSettingsTab` / 全 settings tab に配線。i18n ja/en。
  PropertyManagerConfigTest 12/12。実機検証: admin-UI 値が `-D` を上書き
  (source `system_property → couchdb`)、blank で deploy 既定へ復帰。**設計上の
  注意**: admin-managed 化は auth 統合 key に限定 (DB credential 等は従来の
  「system property first」を維持)。
- **Markdown プレビューの画像埋め込み解決** (`ddfb831e8`): `MarkdownPreview` は
  react-markdown を素で呼んでおり画像未解決 (相対参照 `images/foo.png` /
  `../assets/a.png` が SPA route 基準で 404、絶対URLのみ表示)。カスタム `img`
  renderer を追加: 相対参照を対象 .md の CMIS フォルダ基準で解決 —
  `getObjectParents` で親フォルダ path → `resolveRelativeCmisPath` (サブフォルダ
  / `./` / `../` / 先頭 `/`=repo root / query,hash 除去 / percent-decode) →
  `getObjectByPath` → `getContentStream` → **blob URL** (CSP `img-src` は既に
  `blob:` 許可) 化、unmount で revoke。絶対/`data:`/`blob:` は素通し。解決失敗は
  alt テキスト + 壊れアイコン (`imageUnresolved`) にフォールバック。純ロジック
  `resolveRelativeCmisPath` を export し vitest 11 ケース。i18n ja/en。実機
  (ブラウザ) 3 ケース検証: 相対サブフォルダ→blob URL 描画、不在→フォールバック、
  絶対URL→素通し。**HTML は従来通り Monaco ソース表示 (レンダリングしない) の
  ままで挙動不変** (信頼できない HTML の XSS 回避)。

**リリース再検証 (clean DB)**: 上記2機能を含む deploy で全面テスト実施 — 関連
Java unit 60/60 (PropertyManager / IntegrationSettings controller /
AuthenticationUtil / MCP auth)、UI vitest 191/191 (Markdown resolver +11、
LineageSettingsTab test を MemoryRouter wrap する pre-existing 修正込み)、
**TCK フル 38/38 BUILD SUCCESS** (永続 volume 汚染で `rootFolderTest` が初回
fail するが clean 再init で green = 非回帰を再確認)、Playwright chromium フル
911 passed / 102 skipped。fail spec は既知 flaky (group-hierarchy 循環参照 /
custom-property-input) + 環境 serial timeout flake (archive-restore-consistency
/ config-viewer の render 前 row-count race) で変更コード経路外。overridable
notice 挙動変更で影響した integration-settings assertion 1 件のみ更新し単体
再実行 17/17 green。

### 3.2.0 (2026-06-20) — IaaS ワンステップデプロイ (公開イメージ + cloud bootstrap)

ブランチ: `release/3.2-iaas-setup` (off `release/3.1.3`)。AWS / Azure などの
IaaS で「ターゲットホスト上で WAR をビルドする」摩擦を排除し、**素の VM で
公開イメージを pull するだけ**でフルスタックが立ち上がる構成を追加。ホストに
Java / Maven / Node のツールチェーンは不要。詳細は `RELEASE_NOTES.md` の
3.2.0 セクション、運用手順は `deploy/README.md`。

**追加物**:
- **公開イメージ CI** `.github/workflows/release-images.yml`: `v*` tag push
  (または `workflow_dispatch`) で WAR をビルド (integration-tests.yml と同じ
  実証済み手順) → GHCR に `nemakiware-core` / `nemakiware-solr` を発行
  (`:<version>` + tag build 時 `:latest`)。CouchDB / TEI は upstream のまま。
  linux/amd64、GHA build cache、OCI ラベル付与。workflow_dispatch 入力は
  injection-hardening のため `env:` 経由。
- **本番 compose** `docker/docker-compose-prod.yml`: `build:` ではなく
  `${NEMAKI_IMAGE_PREFIX}` / `${NEMAKI_VERSION}` で公開イメージ参照。
  posture 強化 — CouchDB / Solr は**ホストポート非公開**(内部ネットワーク
  限定)、core は `${NEMAKI_HTTP_BIND:-127.0.0.1}:8080` バインドで TLS 前段
  前提。`restart: unless-stopped`、`--profile rag` で TEI 任意追加。
- **環境テンプレート** `docker/.env.prod.example`。
- **ブートストラップ** `deploy/aws/user-data.sh` (Amazon Linux 2023) /
  `deploy/azure/custom-data.sh` (Ubuntu 22.04/24.04): Docker 導入 → 指定 tag を
  clone → CouchDB パスワード解決 (既定ランダム / AWS Secrets Manager /
  Azure Key Vault via managed identity) → `.env` 生成 → `docker compose pull
  && up -d` → reboot 耐性のため systemd unit 登録。AWS 版はインスタンスタグ
  からの上書きにも対応。
- **運用ドキュメント** `deploy/README.md` (AWS/Azure quickstart + 起動後の
  ハードニング checklist)。
- **Terraform モジュール** `deploy/terraform/aws/` + `deploy/terraform/azure/`:
  VM・ネットワーク・IAM を用意し、同じブートストラップを user-data /
  custom-data として渡す `terraform apply` 一発経路。デプロイ座標は env export
  をスクリプト先頭に prepend して**決定的に注入**(タグ伝播レースなし)。
  AWS は公開 SSM パラメータから最新 AL2023 を解決 (AMI ハードコードなし) +
  IMDSv2 必須 + gp3 暗号化 + Secrets Manager 1 シークレットに絞った IAM。
  Azure は Ubuntu 22.04 + VNet/NSG/Public IP + SSH 鍵認証 + Key Vault 用の
  system-assigned identity (任意)。`deploy/terraform/.gitignore` で state /
  `.terraform` / 実 tfvars を除外。

**設計判断**:
- `nemakiware-core` は `Dockerfile.simple` ベース。runtime 設定は compose env
  からの `-D` system property (既存規約) で注入、`nemakiware.properties` の
  完全上書きは optional volume mount で対応。
- backing services (CouchDB / Solr) は AWS/Azure にマネージド同等品が無いため
  自己ホスト維持。永続化は EBS / Managed Disk スナップショットに誘導。

- **回帰防止 CI** `.github/workflows/deploy-validate.yml`: `deploy/**` / prod
  compose 変更時に bootstrap の `bash -n`+shellcheck、`docker compose config`
  (base+rag) + **JSON posture ガード** (CouchDB/Solr ポート非公開 & core が
  127.0.0.1 バインドを assert)、Terraform 両モジュールの `fmt -check`+`validate`
  を実行。

**Codex レビュー remediation** (HIGH 1 + MED 5 + LOW 2、commit `4a2b5e6cf`):
- [HIGH] タグ値代入を `eval` → `printf -v` (literal、再評価なし。タグ値は
  attacker-influenceable)
- [MED] スクリプト既定 `NEMAKI_HTTP_BIND` を 0.0.0.0 → 127.0.0.1 (safe by
  default、compose 既定と一致)
- [MED] Terraform→shell の env 注入をシングルクオート化、AWS IAM を検証済み
  Secrets Manager **ARN** (`couchdb_secret_arn`) に scope
- [MED] Secrets Manager/Key Vault 取得失敗を明示エラー+exit に。AL2023 で
  Compose v2 plugin 不在時のフォールバック導入
- [LOW] release-images.yml で VERSION を Docker tag 文法で検証

**検証**: `docker compose -f docker-compose-prod.yml config` (base + rag
profile) PASS + posture ガード両方向 (正常 PASS / 0.0.0.0 で FAIL) 確認、両
ブートストラップ `bash -n`+shellcheck (`-S warning -e SC2086`) PASS、`printf -v`
非インジェクション確認、Terraform 両モジュール `tofu validate` (実 aws/azurerm
provider) PASS + `fmt` クリーン。GHA workflow / イメージの実 push は tag 発行時に
走る (本ブランチでは未 push)。

### 3.1.3 (2026-06-11) — 全面レビュー remediation (security + correctness)

ブランチ: `release/3.1.3`。Fable マルチエージェントレビュー + Codex
独立検証で確定した課題を優先順位順に修正。各修正に regression test +
実機検証 (TCK + redeploy)。詳細は `RELEASE_NOTES.md` の 3.1.3 セクション。

**コミット**: `ef33e0b4f` (主要18修正) + 後続 (P1 follow-up: checkIn 原子性
完成 / パスワードポリシー test / apiKey index / docs)

**セキュリティ**:
- [CRITICAL] WebAuthn 認証バイパス: `NemakiCredentialRepository.lookup()`
  が discoverable flow でクライアント提供の `userHandle` を echo し、保存
  所有者を無視 → ライブラリの userHandle 一致チェックがトートロジー化。
  自分の passkey + `response.userHandle=bytes("admin")` で任意ユーザー
  (admin含む) になりすまし可能だった。`cred.getUserId()` バインド +
  不一致拒否で修正 (`lookupAll()` と同パターン)。+`WebAuthnResourceLookupTest`
- パスワードポリシーバイパス: `UserController`(create/update) + api/v1
  `UserResource`(update) + legacy `UserItemResource`(update/updateJson) が
  `PasswordPolicyService.validate()` を呼ばず迂回。全経路で enforce。
  +`UserControllerPasswordPolicyTest` (UserController を field 注入対応に)
- IMAP IDLE 委譲認可バイパス: `ImapIdleMonitor` が `CallContext=null` で
  実行し scheduler/webhook の委譲再認可を迂回。起動時 + メッセージ毎に
  `authorizeDelegatedFetch` を適用し合成 context で実行 (admin は不変)。
  serviceContext.xml で `imapIdleMonitor ↔ ingestSchedulerService` の
  setter 循環参照 (Spring singleton で解決、起動確認済)
- Content-Disposition ファイル名注入: 無サニタイズ連結による quote-breakout
  / 拡張子偽装。`ImportExportUtils.contentDispositionAttachment`
  (サニタイズ `filename=` + RFC 5987 `filename*`) に集約、CMIS Browser /
  api/v1 Object/Rendition / ImportExport / ArchiveDownload に適用。
  +`ContentDispositionTest`

**正確性 / データ整合性**:
- checkIn データ損失: 新バージョン create 前に PWC + 旧版 latest フラグを
  変更していた → create 失敗で編集内容喪失 + 版系列に最新版なし。新版を
  **先に永続化**し、旧版フラグ flip と PWC 削除を post-create cleanup 化。
  `VersioningServiceDelegate.updateVersionProperties` に `updateFormerNow`
  オーバーロード追加。TCK VersioningTestGroup で検証
- changeLog token: ミリ秒生値で非ユニーク (Virtual Threads 衝突 → 重複/
  欠落)。AtomicLong で JVM 内単調増加 (数値トークン維持)。別件で
  `getLatestChangeToken` が CouchDB `_id` を返し `hasMoreItems` が永遠に
  true (endless drain)・published cursor が使用不能だった → token を返す
- getApiKeys メモリ: `_all_docs + include_docs` (全DB を JVM ロード) を
  Mango `_find` に置換 + content DB へ `(type)` index 追加
  (`Patch_ApiKeyMangoIndex`、per-repository、実機で idx_type 作成確認)。
  `CloudantClientWrapper.findRawBySelector` 追加

**UI**:
- group create/update/delete がサーバ `{status:"failure"}`(HTTP200) を
  未検査で成功通知 → status 検査追加。`restoreObject` の失敗握り潰し修正。
  `DocumentList` のフォルダ高速移動時 stale レスポンス上書き防止 (連番ガード)

**品質 / 保守性**:
- `getOrCreateSystemSubFolder` の NPE 危険3コピーに null ガード。
  `userItemResource` の `threadLockService` 明示 DI 配線 (load-bearing な
  SpringContext フォールバック解消)。+`executeChatContextImport` の
  dedupe-skip 伝播 regression test

**Ingest robustness (P2 follow-up、後続コミット)**:
- fetch-timeout 誤記録 (#13): timeout を STUCK として記録 (FAILED/imported=0 の
  誤断定をやめる)。`IngestJobService.markTimedOut`
- checkpoint cross-item gap (#3): execute() 到達前の download 失敗 (Box/Dropbox
  file、Notion/Teams/Mattermost attachment) を DLQ 退避し、checkpoint leapfrog に
  よる取りこぼしを防止。`FetchSupport.saveToDlq`。orchestrator 経路は adapter
  非注入で単体不可 (ヘルパーのみ bound) — WireMock orchestrator IT を follow-up 推奨

**Deferred → 完了**:
- ~~REST 3系統統合 (#14): 大規模 phased maintainability refactor~~ →
  **完了**（次セクション参照）。`release/3.1.3` に fast-forward マージ済み
  (HEAD `c92c2adb6`)

### REST 3系統統合 (#14) — ContentService 集約 (2026-06-13、merged to release/3.1.3)

legacy Jersey (`rest/*`) / Spring MVC (`rest/controller/*`) / api/v1 JAX-RS
(`api/v1/resource/*`) の 3 つの REST バインディングに重複していた
User / Group / Rendition / Archive のドメインロジックを `ContentService`
(impl: `ContentServiceImpl`) に単一情報源として集約。各バインディングは
検証 / 認可 / 応答整形の「契約」を温存し、共通の build / persist / guard tail
のみ委譲する方針（応答 shape・ステータスコードは不変）。

増分1〜6 + Codex 反映の 9 コミット（`7f24b1a9b` 〜 `c92c2adb6`）:

| 増分 | 対象 | 新規 API |
|---|---|---|
| 1 | system sub-folder | `getOrCreateSystemSubFolder`（`.system` bootstrap fallback 込み） |
| 2 | group member add/remove | `GroupMembershipEditor`（純粋 util、Outcome enum） |
| 3 | group create | `validateNewGroup` / `buildAndCreateGroup` / `GroupValidation` enum |
| 3b | group update/delete | `applyGroupUpdate` / `deleteGroup`（+nested 参照掃除） |
| 4 | user create | `buildAndCreateUser`（BCrypt 集中） |
| 4b | user update/delete/changePassword | `hashPassword` / `applyUserUpdate` / `deleteUser`（+group membership 掃除） |
| 5 | rendition generate tail | `createPreviewRendition` |
| 6 | archive restore guard | `isArchiveAccessible` / `restoreArchiveGuarded` / `ArchiveRestoreOutcome` enum |

**集約と同時に解消した潜在バグ/ギャップ 5 件**:
- group delete の dangling nested 参照（legacy/Spring は未掃除だった）
- user delete の dangling group membership（同上）
- `removeGroupFromAllNestedGroups`/`removeUserFromAllGroups` が `getGroupItems`
  の revision なし戻りを update して "no revision" 例外になる潜在バグ →
  `getGroupItemById` 再取得で修正
- api/v1 archive restore に cold-storage guard が欠落（cold archive を直接復元
  しようとしていた）→ `restoreArchiveGuarded` で全スタックに補完
- `getOrCreateSystemSubFolder` 集約版が `.system` 欠如時に即 throw（legacy
  user create の bootstrap 復旧を喪失）→ root 配下に `.system` 自動作成を移植

**Codex 独立レビュー**: Blocker/High なし。Medium 1（system folder bootstrap）+
Low 2（changePassword admin 分岐の hash 集約漏れ / デッドコード約177行）を反映。

**検証**: 集約系ユニット 36 PASS、TCK フルスイート **38/38**（当初の Basics
rootFolder / Types baseTypes 失敗は前セッション残骸 doc + E2E カスタム型の
データ汚染で、掃除後 green = 回帰ではないと確証）、Playwright chromium フル
**932 passed / 0 failed / 2 flaky(retry通過) / 99 skipped**、実機 3 スタック
手動 API 検証。

### RC37 / RC6.13 (2026-05-31) — Test quality: feature-readback now binds to production reader (closes RC6.12 P3) (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.12` = `f8ec0326c`)

RC6.12 test-quality follow-up に対する 2 段目の reviewer P3 を解消。
actual security guard は不変 (3 つの SAX feature は依然同じ単一の
helper method で設定される)、ただし feature-readback assertion が
test-local probe ではなく production-configured reader instance を
直接見るように構造を変更。

#### P3: readback が test-local probe を見ていた

RC6.12 の `productionParserHasAllThreeFeaturesEnabled` test は
1 つの method で 2 つの無関係なことをやっていた:
1. `ZipImporter.parseAcpPackageXml(...)` を benign payload で 1 回
   call して production path が reachable であることを確認
2. その後、**独自の** `SAXReader` を作って 3 つの `setFeature(...)`
   を手動で再適用し、**その probe** に対して `getXMLReader().
   getFeature(...)` を query

reviewer が指摘した穴: production から例えば
`external-general-entities=false` だけが削除されて
`disallow-doctype-decl=true` が残った場合、DOCTYPE-rejection test
は依然 pass (DOCTYPE check が先に PoC payload を捕捉)、readback
test も依然 pass (probe を見ているので production の状態と無関係)。
production の 3 feature のうち 2 つを削除しても、どの test も落ち
ない。

#### 修正: `configureHardenedSaxReader()` を抽出 — single source of truth

RC6.12 production helper を 2 つに分割:
- **`ZipImporter.configureHardenedSaxReader()`** (新規、package-
  private static): `SAXReader` を build + 3 `setFeature(...)` 適用 +
  configured reader を return。**SAXReader 設定の単一情報源**
- **`ZipImporter.parseAcpPackageXml(byte[])`**: 上記 helper を call
  して `.read(...)` するだけ。production path は RC6.12 と
  byte-equivalent

`ZipImporterXxeTest.productionParserHasAllThreeFeaturesEnabled`
は actual production-configured reader を保持:
```java
org.dom4j.io.SAXReader productionReader = ZipImporter.configureHardenedSaxReader();
productionReader.read(...);  // force XMLReader instantiation
org.xml.sax.XMLReader xmlReader = productionReader.getXMLReader();
assertTrue (xmlReader.getFeature(".../disallow-doctype-decl"), ...);
assertFalse(xmlReader.getFeature(".../external-general-entities"), ...);
assertFalse(xmlReader.getFeature(".../external-parameter-entities"), ...);
```

`configureHardenedSaxReader()` から 3 つの `setFeature` のどれかが
削除されれば、該当 assertion が「その feature 名を含む明確な
diagnostic」で落ちる。

#### 3-way mutation test — 3 feature 全てで binding 証明

3 mutation を 1 つずつ実行:

| Mutation | failing tests | readback diagnostic |
|---|---|---|
| `disallow-doctype-decl` 削除 | **3/4** (DOCTYPE-reject 2 + readback) | `disallow-doctype-decl must be true on the production-configured reader` |
| `external-general-entities` 削除 | **1/4** (readback のみ) | `external-general-entities must be false on the production-configured reader ==> expected: <false> but was: <true>` |
| `external-parameter-entities` 削除 | **1/4** (readback のみ) | `external-parameter-entities must be false on the production-configured reader ==> expected: <false> but was: <true>` |

mutation はすべて local source-edit + revert、commit には含まれず。
3 line restore 後: 4/4 PASS。

真ん中の 2 ケース (external-*-entities のどちらかを削除) が、
**RC6.12 reviewer が懸念した regression class**。RC6.12 では
これを検知できなかった。RC6.13 で検知可能に。

#### Tests

- `ZipImporterXxeTest`: 4/4 PASS — readback は
  `ZipImporter.configureHardenedSaxReader()` を直接 query
- **3-way mutation test** (上表): 3 feature それぞれを独立に削除
  すると readback test が当該 feature 名入り diagnostic で fail
- 25 class focused regression: **377/377 PASS** (RC6.12 と同 count、
  behaviour-equivalent refactor)
- SOC validator full run: 17 PASS / 7 SKIP、NUL scan 1681 files /
  0 hits (RC6.12 と不変)

#### Files touched

- `core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
  (`parseAcpPackageXml` を `configureHardenedSaxReader()` +
  `parseAcpPackageXml(byte[])` に分割。production path 挙動不変)
- `core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
  (`productionParserHasAllThreeFeaturesEnabled` を
  `ZipImporter.configureHardenedSaxReader()` 直呼びに rewrite。
  test-local probe block 削除)
- `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`

#### Migration / compatibility

public API 変更なし (`configureHardenedSaxReader` は package-private、
唯一の production caller は同 class 内 sibling `parseAcpPackageXml`)。
property / schema / patch / view / Mango / migration 一切無変更。
operator 挙動変化なし。RC6.11 GHSA XXE security boundary 不変、
RC6.12 production path 挙動不変。

#### Commit + tag 関係

- RC6.13 test-binding refactor commit: 後続
- doc closure commit: 後続
- **`v3.1.1-RC6.13` annotated tag target**: doc-closure commit

RC6.12 tag (`f8ec0326c`) は force-update せず歴史的マイルストーン
として保持。

#### Credit

RC6.12 external reviewer P3 — readback assertion が test-local
probe を query していて production-configured reader を見ていない、
という P3 指摘を構造的に解消。

### RC36 / RC6.12 (2026-05-31) — Test quality: bind XXE regression to production parser (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.11` = `8e52d95d2`)

RC6.11 GHSA XXE fix の **test-quality follow-up**。actual security
guard には変更なし、ただし RC6.11 review の P2 で「test が
production の parser を呼んでいない」(test 内に SAXReader 設定を
duplicate していた) という指摘を解消。

#### P2: production helper を package-private に切り出し

RC6.11 では `ZipImporterXxeTest` が独自に `hardenedReader()` を
持っており、production の `ZipImporter.importAcpFormat` で
duplicate された SAXReader 設定 block とは disjoint。production 側
から 3 つの `setFeature` 行を削除しても test は green のまま、と
いう regression guard としては弱い構造だった。

修正:
- 新 method `ZipImporter.parseAcpPackageXml(byte[]) throws DocumentException`
  に SAXReader construction + 3-`setFeature` + `read()` を集約。
  package-private (public ではない) — legitimate caller は
  importAcpFormat 1 つ + test class のみ。
- `importAcpFormat` は inline 設定の代わりに
  `parseAcpPackageXml(xmlData)` を呼ぶように変更。production path
  上は byte-equivalent (同じ DOCTYPE input で同じ DocumentException、
  同じ benign input で同じ Document 返却)。
- `ZipImporterXxeTest` の 4 ケースは
  `ZipImporter.parseAcpPackageXml(...)` を直接呼ぶように rewrite。
  test 内の `hardenedReader()` helper は削除。

#### Mutation test で binding を検証

test が本当に production の挙動を捕捉するか mutation で確認:
production の `disallow-doctype-decl` 行を一時 comment out → test
実行:
```
[ERROR] Tests run: 4, Failures: 2, Errors: 0
[ERROR]   rejectsDoctypeWithFileSystemEntity:58 ... ==> expected: not <null>
[ERROR]   rejectsDoctypeWithExternalParameterEntity:80 ... ==> expected: not <null>
```
production を restore → `Tests run: 4, Failures: 0`。
将来 production hardening が削られたら test が即落ちる構造。
(mutation 自体は commit せず、local source-edit + revert のみ)

#### P3: NUL scan file count を訂正

RC6.11 docs は "1683 source files / 0 hits" と書いていたが、
reviewer の手元実行で **1681 source files / 0 hits** と判明。
RC6.11 doc は私の算術ミスで off-by-2。実 validator output は
1681 が正解。

RC6.12 では validator の actual output を採用: **1681 source
files / 0 hits**。RC6.11 historical record も RELEASE_NOTES で
訂正 + 補足追記。

#### Tests

- `ZipImporterXxeTest`: 4/4 PASS — `ZipImporter.parseAcpPackageXml(...)` 直呼び経由
- Mutation test: production guard 削除で 2/4 fail (DOCTYPE-reject の
  2 ケース)、restore で 4/4 pass。commit 前に restore 済
- 25 class focused regression: **377/377 PASS** (RC6.11 と同 count、
  behaviour-equivalent refactor)
- SOC validator full run: 17 PASS / 7 SKIP、Phase 1.4.1 source-tree
  NUL scan = **1681 files / 0 hits**

#### Files touched

- `core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
  (`parseAcpPackageXml` を抽出、`importAcpFormat` 内 inline 設定を
  delegate 化。production path 挙動不変)
- `core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
  (4 ケース rewrite、test-local `hardenedReader()` 削除)
- `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`

#### Migration / compatibility

public API 変更なし (`parseAcpPackageXml` は package-private)。
property / schema / patch / view / Mango / migration 一切無変更。
production path は byte-equivalent。operator は挙動変化なし。

#### Commit + tag 関係

- RC6.12 test-quality refactor commit: 後続
- doc closure commit: 後続
- **`v3.1.1-RC6.12` annotated tag target**: doc-closure commit

RC6.11 tag (`8e52d95d2`) は force-update せず歴史的マイルストーン
として保持。

#### Credit

RC6.11 external review reviewer の P2 (test binding) + P3
(NUL count) 指摘 — test quality 改善 + doc 訂正に直結。

### RC35 / RC6.11 (2026-05-31) — Security: ACP import XXE (CWE-611) — GHSA reporter tonghuaroot (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.10` = `cf2f499f3`)

GHSA で **High** 脆弱性報告。RC6.5 と同じ reporter (tonghuaroot)、
今度は **XXE on ACP import**。`ZipImporter.importAcpFormat` の
SAXReader が DOCTYPE / 外部エンティティを resolve しており、
**cmis:write 1 個** あれば非 admin user が任意ファイル読取 + SSRF
を起動可能。reporter が exact code fix + live verify 済みで添付。

#### 脆弱性

`core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
line 191-192:
```java
SAXReader reader = new SAXReader();
xmlDoc = reader.read(new ByteArrayInputStream(xmlData));
```

dom4j の `SAXReader` は default で external entity resolve する。
ACP ZIP の top-level `*.xml` が攻撃者制御 (`importAcpFormat` で
中身全体を `byte[] xmlData` に読み込み)、解決されたエンティティは
`<name>` 要素の text → `getAcpChildName(...)` → 作成される CMIS
folder の `cmis:name` に **verbatim 永続化** される。CMIS API で
読み戻せる = read-capable XXE。

攻撃シナリオ:
```xml
<?xml version="1.0"?>
<!DOCTYPE r [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<root><folder><name>&xxe;</name></folder></root>
```
これを ZIP に入れて `POST /core/rest/repo/{repo}/importexport/import/{folderId}`
に流すと、サーバ container の `/etc/passwd` が folder name として
登録される。Tomcat process が読める任意ファイル (app config、
credential、key material) が同様に抜き取れる。SYSTEM/外部 parameter
entity を使えば SSRF も可。

#### 権限境界 (重要)

要件は `hasCreateChildrenPermission(cs, repositoryId, callContext, targetFolder)`
だけ。admin 不要。**1 つのフォルダに `cmis:write` を持つ通常
ユーザ** がアタッカー。`applyACL` で `addACEPrincipal[N]=bob` +
`addACEPermission[N][0]=cmis:write` で簡単に到達。

#### 修正

`ZipImporter.importAcpFormat` の SAXReader を `TypeResource.parse`
(RC13 で既に hardening 済) と同じ pattern に揃え:
```java
SAXReader reader = new SAXReader();
try {
    reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
} catch (org.xml.sax.SAXException e) {
    throw new DocumentException("Failed to configure XXE protection on SAXReader", e);
}
xmlDoc = reader.read(new ByteArrayInputStream(xmlData));
```

#### Repo-wide audit (reporter 依頼)

`new SAXReader()` / `DocumentBuilderFactory.newInstance` / その他
XML parser instantiation を全件 grep:

| Sink | 状態 |
|---|---|
| `ZipImporter.java:191` | **本 RC で修正** |
| `TypeResource.java:1721` | RC13 で hardening 済 |
| `AuthTokenResource.java:475` (SAML response) | RC13 で full 5 feature 設定済 |
| `SolrResource.java:403`, `SolrAllResource.java:143` | RC13 で hardening 済 |
| `SamlSignatureVerifier` | XML parse しない (Document を受け取るだけ) |

他に unhardened sink なし。

#### 新規 ZipImporterXxeTest (4 ケース)

`core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`:
1. `rejectsDoctypeWithFileSystemEntity`: reporter PoC そのまま、
   `DocumentException` + "DOCTYPE is disallowed" 含むこと
2. `rejectsDoctypeWithExternalParameterEntity`: blind/OOB variant
   (`<!DOCTYPE root SYSTEM "http://...">`) も block
3. `acceptsBenignDoctypeFreePackageXml`: 正当な DOCTYPE-free ACP は
   通る (over-block regression guard)
4. `hardenedReaderHasAllThreeFeaturesEnabled`: `getXMLReader().
   getFeature(...)` で 3 feature 値を読み戻し、将来 setFeature が
   reorder / drop された場合に検知

JVM-level test なので Tomcat 不要、focused regression に含められる。

#### Live PoC 検証

本 session の RC6.10 stack に対して reporter PoC をそのまま実行:

1. **Pre-fix**: bob (非 admin) で xxe_passwd.zip upload → HTTP 200,
   `foldersCreated: 1`, `/etc/passwd` の中身が folder name として
   CouchDB に保存されたことを確認
2. **Post-fix** (patched WAR redeploy 後): 同じ upload → HTTP 200,
   `foldersCreated: 0`, `errors: ["Failed to parse package XML: ...
   DOCTYPE is disallowed when the feature \"http://apache.org/xml/
   features/disallow-doctype-decl\" set to true."]`, `status:
   partial`
3. **Benign control**: DOCTYPE なし ACP zip → `foldersCreated: 1`,
   `status: success` (over-block していないこと確認)
4. **Cleanup**: 漏洩した folder + bob + parent folder + archive db
   copy 全て sweep 済

#### Tests

- **新規** `ZipImporterXxeTest`: 4/4 PASS
- 25 class focused regression: **377/377 PASS** (RC6.10 baseline
  373 + 4 新規 ZipImporterXxeTest)
- SOC validator full run: 17 PASS / 7 SKIP (Phase 1.4.1 source-tree
  NUL scan: 1681 files / 0 hits — RC6.10 の 1680 から +1。RC6.11
  当初は "1683 files" と記載していたが算術ミスで、RC6.12 で訂正)

#### Files touched

- `core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
  (+11 line, 3-feature hardening block)
- **新規** `core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
  (4 cases)
- `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`

#### Migration / compatibility

public API 変更なし。新 property 追加なし。schema / patch / view /
Mango index / migration 一切無変更。正当な ACP import (DOCTYPE
なし、NemakiWare の ACP format には DOCTYPE 不要) は従来通り動作。
DOCTYPE 入り payload (NemakiWare に legitimate な use case なし)
だけが明示的 error message で拒否される。

#### Commit + tag 関係

- RC6.11 security fix + test commit: 後続
- doc closure commit: 後続
- **`v3.1.1-RC6.11` annotated tag target**: doc-closure commit

RC6.10 tag (`cf2f499f3`) は force-update せず歴史的マイルストーン
として保持。

#### Credit

Reported by **tonghuaroot** via GitHub Security Advisory.
RC6.5 と同じ reporter。

### RC34 / RC6.10 (2026-05-31) — Refactor: SsrfGuard 共有化 + source-tree NUL pre-commit scan (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.9` = `76695f46c`)

SSRF hardening cycle 6 本目。新規セキュリティ穴の closure はなし、
RC6.7 で AdapterHttpClient に horizontal 化した address classifier
が `HttpWebhookDispatcher` と完全重複している状態を解消し、
RC6.1 / RC6.7 で 2 回 ship してしまった literal NUL byte regression
class に pre-commit scan を入れる。

#### Rule of Three refactor

新規: `jp.aegif.nemaki.security.SsrfGuard`
- `isAddressSafe(InetAddress)`: 全 classification (JDK predicates
  + 9 IPv4 special-use ranges + IPv6 ULA + 6 IPv6 transition
  format で embedded IPv4 unwrap + recursive re-classify)
- `extractEmbeddedIpv4(InetAddress)`: 6 transition format から
  embedded IPv4 抽出、それ以外は null

2 callsite が delegate:
- `HttpWebhookDispatcher.isAddressSafe(InetAddress, String)`:
  classification は SsrfGuard、cheap top-level predicates だけ
  operator log categorization 用に local 再評価
- `AdapterHttpClient.isAddressSafe(InetAddress)`: 薄い wrapper
  (`pinRequestToValidatedAddress`/`validateExternalUrl` の
  callsite を byte-equivalent に保つため残す)

byte-for-byte extraction。RC6.9 の classification rules と一切
の挙動差なし。3rd consumer (Purview/Atlas/OIDC/Graph 等の
outbound URL validator — REVIEW_PACKET §6 で deferred として
追跡) が将来追加されたとき、duplicate を 3 箇所更新する必要が
なくなる。

#### SsrfGuardTest 新設 — 30 ケース

- 5 JDK predicate (loopback / link-local / RFC 1918 / any-local /
  multicast)
- 6 IPv4 special-use (CGNAT 100.64/10 boundary 含む、0/8、
  192.0.0/24、198.18/15、240/4、broadcast)
- IPv6 ULA
- 6 IPv6 transition format (NAT64 well-known + local-use /48、
  6to4、Teredo、IPv4-mapped、IPv4-compatible) を private + public
  両方の wrap で
- public allowlist (over-block しないこと、特に 2001:db8::
  documentation prefix が Teredo 厳密 prefix にひっかからないこと)
- `extractEmbeddedIpv4` 直接テスト

`HttpWebhookDispatcherTest.testExtractEmbeddedIpv4PublicPassthrough`
は reflection を廃止して `SsrfGuard.extractEmbeddedIpv4` 直呼び
に変更 (public static)。

#### Source-tree NUL pre-commit scan

`scripts/validate-soc-templates.sh` に Phase 1.4.1 を追加。
`.java`/`.ts`/`.tsx`/`.js`/`.jsx` を repo 全体 scan して literal
NUL (0x00) を検出。

過去 2 回 ship してしまった regression:
- RC6.1 P2-3: `ConnectorGovernanceTab.tsx` の
  `simulateRemove.join('\0')` separator
- RC6.7 P3: `HttpWebhookDispatcherTest.java` の string literal

両方とも Java / TS compilation を通過、grep / rg / file が
binary 扱いするまで気付かれなかった。Phase 1.4.1 で 1680 source
files を scan、0 hits 確認。

除外: `node_modules`, `target`, `dist`, `build`, `.git`,
`coverage`, `playwright-report`, `test-results`。clean tree 環境
向けに `VALIDATE_SOURCE_NUL=0` で無効化可。

#### Follow-up R3 closure (REVIEW_PACKET §6)

「他の orchestrator が SSRF guard を bypass していないか」検証完了:
- 11 connector adapter のうち、`connector.getEndpoint()` を HTTP
  call に渡す orchestrator は 3 つだけ
- `MattermostFetchOrchestrator` / `SalesforceFetchOrchestrator`:
  RC6.8 で explicit `validateExternalUrl` 配置済 (orchestrator 入口)
- `ImapFetchOrchestrator`: `imap://` scheme なので
  `validateExternalUrl` の http/https-only check で reject される
  (意図的に guard 配下に置かない)
- 残り 8 (Slack/Teams/Gmail/M365Mail/Notion/Chatwork/Box/Dropbox):
  vendor API URL hardcoded、user-controlled endpoint 不在
- 加えて `AdapterHttpClient.pinRequestToValidatedAddress` が
  send-time に必ず再検証するので、将来 endpoint configurable 化
  しても HTTP 層で guard が効く

→ R3 は documentation-only にクローズ、code 変更なし

#### Tests

- **新規** `SsrfGuardTest`: 30/30 PASS
- `HttpWebhookDispatcherTest`: 59/59 PASS (挙動不変、reflection 廃止)
- `AdapterRegistryTest`: 26/26 PASS
- 7 adapter contract test (Slack 12 / Teams 11 / Mattermost 12 /
  Notion 8 / Salesforce 11 / M365 9 / Chatwork 13): 76/76 PASS
- 24 class focused regression: **373/373 PASS** (RC6.9 baseline
  343 + 30 新規 SsrfGuardTest)
- SOC validator full run: 17 PASS / 7 SKIP (Docker phase opt-in)

#### Files touched

- **新規** `core/src/main/java/jp/aegif/nemaki/security/SsrfGuard.java`
  (263 lines, extracted helper)
- `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`
  (-240 line duplicate classifier, SsrfGuard delegate に置換)
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/AdapterHttpClient.java`
  (-110 line duplicate classifier、SsrfGuard delegate に置換)
- **新規** `core/src/test/java/jp/aegif/nemaki/security/SsrfGuardTest.java`
  (30 cases)
- `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java`
  (reflection → SsrfGuard 直呼び)
- `scripts/validate-soc-templates.sh` (Phase 1.4.1 source-tree NUL scan)
- `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`

#### Migration / compatibility

public API 変更なし、property 追加なし、DB / patch / view / Mango
index 一切無変更。classifier output レベルで byte-equivalent。
operator は connector / webhook / scheduler の挙動変化なし。

RC6.9 で設定済の JVM-wide `jdk.httpclient.allowRestrictedHeaders=host`
は本 RC では触らない。

#### Follow-up (post-RC6.10)

- **#1 HTTPS SocketFactory pin** (Medium residual TCP-connect
  SSRF): JDK HttpClient が injection point を expose しない、
  HttpURLConnection 切替か大規模 refactor 必要 — 別 RC
- **#4 Purview/Atlas/OIDC/Graph SSRF guard**: admin-config 面、
  opt-in mechanism 設計が要る — 別 RC、本 RC で抽出した
  `SsrfGuard` がそのまま使える

#### Commit + tag 関係

- RC6.10 refactor commit: 後続
- doc closure commit: 後続
- **`v3.1.1-RC6.10` annotated tag target**: doc-closure commit

RC6.9 tag (`76695f46c`) は force-update せず歴史的マイルストーン
として保持。

### RC33 / RC6.9 (2026-05-31) — Security: HTTP IP-pin の original Host header 保持 + Javadoc 誠実化 (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.8` = `cd82452f4`)

RC6.8 post-tag review が Medium に raise した 2 件:
- **P2**: HTTPS DNS rebinding wording が overstated (TCP-connect SSRF が
  residual)。doc 層は `d910820d7` で対応済、本 RC で Javadoc も整合
- **P3**: HTTP IP-pin が `Host: <IP>` 送信のため shared-vhost HTTP deployment
  が misroute。本 RC で fix

#### P3 fix — HTTP IP-pin で original Host header を保持

`pinRequestToValidatedAddress` は RC6.8 まで HTTP URI を IP literal に rewrite
した後、JDK の default で `Host: <IP>` を送っていた。Shared-vhost reverse proxy
(1 IP で複数 Mattermost / Salesforce on-prem を hostname 別に配信) で misroute /
404。

修正: URI rewrite + 明示的に `b.header("Host", originalHostHeader)`。JDK の
restricted-headers check を escape する documented JVM startup property
`-Djdk.httpclient.allowRestrictedHeaders=host` を:
- Production: `docker/core/Dockerfile{,.jakarta,.simple}` の CATALINA_OPTS /
  JAVA_OPTS に追加
- Test: `core/pom.xml` の surefire `<argLine>` に追加
- Defensive fallback: `AdapterHttpClient` の static `{}` initializer で class
  load 時に additively set (operator が他の restricted header を `-D...=connection,host`
  のように指定していても preserve)

JVM-wide effect: 同 JVM 内の他 code が `HttpRequest.Builder.header("Host", ...)`
を呼ぶと従来は `IllegalArgumentException` だったのが成功するようになる。
意図的 (JDK の documented escape hatch)。

#### Javadoc honesty fix

`pinRequestToValidatedAddress` Javadoc が actual security boundary を正確に反映
(post-RC6.8 doc fix `d910820d7` と整合):
- **HTTP**: "DNS rebinding closed at the network layer" (IP-pin で rebound IP
  への TCP connection 不可)。Host header preservation も明記
- **HTTPS**: "TLS-bounded, NOT fully closed" — pre-resolve rebind は catch するが
  microsecond race 残存、TLS cert verification で data-exchange SSRF は遮断するが
  TCP-connect SSRF (port scan / service fingerprint / inbound-TCP side-effect) は
  残余。real fix は custom SocketFactory で TCP-connect 時に IP pin

#### Tests

- 2 新規 regression in `AdapterRegistryTest`:
  - `pinRequestPreservesOriginalHostHeaderOnHttpPin`: rewritten URI = IP literal、
    Host header = original `hostname:port`
  - `pinRequestPreservesOriginalHostHeaderWithoutPort`: default port 80 (no :port suffix)
- 7 adapter contract tests **76 PASS** (WireMock は任意 Host を受理するため透過的)
- **Full focused regression: 343/343 PASS** (was 265 in RC6.8; +78 from 7 adapter
  contract test class を focused set に追加 + 2 新規)

#### Change scope vs RC6.8 (precise)

- **変更あり (RC6.9)**:
  - `AdapterHttpClient.java` (+76 行: static init JVM property、Javadoc 修正、
    Host header preservation)
  - `AdapterRegistryTest.java` (+31 行: 2 new Host-preserve tests)
  - `core/pom.xml` (surefire argLine 1 property 追加)
  - `docker/core/Dockerfile{,.jakarta,.simple}` (CATALINA_OPTS / JAVA_OPTS 1 property 追加)
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`, `AGENTS.md`
- **無変更 (RC6.8 と byte-equal)**:
  - `HttpWebhookDispatcher.java` (RC6.5+RC6.6 canonical 実装)
  - 他 Java surface 全件
  - TS surface 全件
  - properties / patches / views / Mango / migration / DB bootstrap
  - SOC templates + validator script
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md`

#### Commit + tag 関係

- Security fix + Javadoc + JVM property: `e45d172bb`
- RC6.9 release-package commit: 後続
- **`v3.1.1-RC6.9` annotated tag target**: release-package commit

RC6.8 tag (`cd82452f4`) は force-update せず歴史的マイルストーンとして保持。

#### Follow-up status

**Resolved in this RC**:
- HTTP IP-pin shared-vhost compat caveat (RC6.8 post-tag P3)
- AdapterHttpClient Javadoc honesty (RC6.8 post-tag P2 echo)

**Remaining (Medium 残余 + carry-forward)**:
- **HTTPS DNS pinning via SocketFactory** (Medium 残余 SSRF) — HTTPS TCP-connect
  SSRF class は本 RC でも未閉。real fix は custom SocketFactory or
  HttpURLConnection 切替
- `isAddressSafe`+`extractEmbeddedIpv4` shared utility extraction (tech debt)
- 他 orchestrator (Slack/Teams/Notion/Chatwork/M365 等) の explicit validateExternalUrl
- Purview / Atlas / OIDC discovery / Graph download (admin-config surface)
- Repo-wide NUL byte pre-commit scan

### RC32 / RC6.8 (2026-05-30) — Security deeper SSRF closure: DNS rebinding pin + runtime revalidation + multi-hop redirect resolve (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.7` = `b48d9e0c1`)

別エージェントの deeper adversarial pass が RC6.7 `AdapterHttpClient` fix
に 3 件の残存 gap を発見 → 全件 fix。

#### P1 — DNS rebinding gap

`validateExternalUrl` は config 時に 1 度 resolve+validate するが、
`sendWithRetry` は元 `HttpRequest` を `HttpClient.send` に渡し、JDK が
**改めて DNS 解決** する。攻撃者が controlled hostname で:
- validate 時: public IP
- send 時: 127.0.0.1 / 169.254.169.254 / private IP
と DNS rebind すれば SSRF。

修正: 新 `pinRequestToValidatedAddress(request)` を `sendWithRetry` と
`sendWithRedirectValidation` 内で呼ぶ。
- **HTTP**: send 時に再 resolve + validate + URI を validated IP リテラル
  (IPv6 は `[...]` bracket) に書き換え。JDK は pin された IP に接続するため
  rebinding 不可。`Host` header は overrideしない (JDK の `HttpClient.Builder`
  が default で restricted header を reject、`-Djdk.httpclient.allowRestricted
  Headers=host` JVM property が必要)。多くの adapter は named API endpoint
  (Mattermost on-prem、Salesforce 等) で `Host: <IP>` でも応答するため運用上
  問題は少ない。shared-vhost は misroute 可能性あり (§6 follow-up 記載)
- **HTTPS**: URI 不変 (TLS cert verification が rebinding を mitigate)。
  re-validation は belt-and-suspenders として実行
- Unresolvable host → `SecurityException` (behaviour 変更: "let HttpClient
  try and fail with network error" → "fail fast with security flavour")

#### P2 — Runtime endpoint revalidation gap

`ConnectorDefinitionServiceImpl` は save 時に validate するが、
orchestrator (`MattermostFetchOrchestrator` L42 / `SalesforceFetchOrchestrator`
L45) は `connector.getEndpoint()` を adapter にそのまま渡す。RC6.7 hardening
の前に save された endpoint or CouchDB 直接編集された endpoint は revalidate
されずに adapter に到達。

修正: 両 orchestrator entry で `AdapterHttpClient.validateExternalUrl(
connector.getEndpoint())` を明示呼出。P1 fix が send 時に re-validate するため
SSRF 自体は塞がるが、orchestrator level の早期 fail で:
- audit message が明確 (どこで reject されたかが直接見える)
- 明らかに bad な endpoint で adapter を構築しない

#### P3 — Multi-hop relative redirect resolve correctness

`sendWithRedirectValidation` が `request.uri().resolve(location)` を毎 loop
iteration で呼ぶが、`request` は update されない。2 hop 目の relative
`Location` (例: `/file` を別 host への redirect 後に返す) が **元 URL** に
対して resolve され、現 hop の host の relative path として解釈されない。

修正: `currentRequest` を loop 内で track し、`currentRequest.uri().resolve(
location)` を使う。correctness fix で単独 SSRF ではない (送る URL も同じく
誤解釈) が、multi-host redirect chain で intermediate host の relative path が
正しく解釈されるようになる。

#### Tests

- 5 新規 regression in `AdapterRegistryTest`:
  - HTTP IPv4 URI rewrite (path/query 保持)
  - HTTPS URI 不変
  - SecurityException on rebound 127.0.0.1
  - SecurityException on rebound NAT64-wrapped metadata
  - Non-restricted header preservation on HTTP pin (Authorization /
    X-Custom-* が pin 済 request に carry-over)
- Test infrastructure: NotionConnectorAdapterTest /
  SalesforceConnectorAdapterTest / MattermostConnectorAdapterTest が
  `nemaki.ingest.allowLocalhost=true` を BeforeEach で set + AfterEach で
  clear。P1 fix で sendWithRetry が全 request validate するため WireMock
  localhost endpoint を使う test は opt-in 必要。他 4 adapter test は
  既に設定済
- HttpWebhookDispatcherTest + AdapterRegistryTest: **78 PASS**
- 7 adapter contract test: **71 PASS** (Slack 12 + Teams 11 +
  Mattermost 12 + Chatwork 13 + M365 9 + Notion + Salesforce)
- 16-class focused regression: **265/265 PASS** (was 260 in RC6.7; +5)

#### Change scope vs RC6.7 (precise)

- **変更あり (RC6.8)**:
  - `AdapterHttpClient.java` (+170 行: pinRequestToValidatedAddress +
    isRestrictedHeaderForJdkHttpClient + multi-hop redirect fix +
    sendWithRetry 内呼出)
  - `MattermostFetchOrchestrator.java` (+9 行: validateExternalUrl 追加)
  - `SalesforceFetchOrchestrator.java` (+9 行: 同上)
  - `AdapterRegistryTest.java` (+65 行: 5 new pin tests)
  - 3 adapter test class (Notion/Salesforce/Mattermost) に test-mode property
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`, `AGENTS.md`
- **無変更 (RC6.7 と byte-equal)**:
  - `HttpWebhookDispatcher.java` (RC6.5+RC6.6 canonical 実装)
  - 他 Java surface 全件 (他 9 connector adapter 等)
  - TS surface 全件
  - properties / patches / views / Mango / migration / DB bootstrap
  - SOC templates + validator script
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md`

#### Commit + tag 関係

- Security fix (P1+P2+P3): `892ccfdd9`
- RC6.8 release-package commit: 後続
- **`v3.1.1-RC6.8` annotated tag target**: release-package commit

RC6.7 tag (`b48d9e0c1`) は force-update せず歴史的マイルストーンとして保持。

#### Follow-up status

**Resolved in this RC**:
- AdapterHttpClient DNS rebinding (P1)
- Mattermost/Salesforce orchestrator endpoint revalidation (P2 — P1 が
  send 時 revalidate するため SSRF 自体は subsume されるが、defense-in-depth)
- Multi-hop relative redirect resolve correctness (P3)

**Remaining (一部 Medium に raise、RC6.8 post-tag review で発覚)**:
- **HTTPS DNS pinning via custom SocketFactory** (Medium 残余、
  RC6.8 post-tag review で raise) — RC6.8 P1 は HTTPS data-exchange SSRF
  は塞いだが、TCP-connect SSRF が microsecond race window で残存
  (`InetAddress.getAllByName` revalidate と JDK 内 resolve の間で rebind
  すれば TCP 接続自体は到達)。TLS handshake は cert mismatch で失敗するため
  data exfil は不可だが、port-scan / service fingerprint / inbound-TCP
  side-effect trigger は可能。real fix は SocketFactory で TCP-connect
  時に validated IP を pin (SNI/hostname-verification は元 hostname のまま)
- **HTTP Host header preservation under IP pin** (Medium 互換性、同上)
  — RC6.8 P1 の HTTP URI rewrite は JDK 制約で `Host: <IP>` 送信。Mattermost
  / Salesforce 等の単独 server 配備では影響なしだが、**name-based
  virtual-host deployment** (1 IP の reverse proxy が複数 hostname 配信)
  では misroute。endpoint を IP 直接設定しても vhost match に hostname が
  必要なので解消されない。real fix は JVM startup property
  `-Djdk.httpclient.allowRestrictedHeaders=host` + host-preserving overload
- `isAddressSafe`+`extractEmbeddedIpv4` shared utility extraction (2 consumer、
  3 つ目で refactor)
- 他 orchestrator (Slack/Teams/Notion/Chatwork/M365 等) の explicit
  validateExternalUrl 呼出 (cosmetic、P1 で send 時 revalidate するため
  security 上は不要)
- Purview / Atlas / OIDC discovery / Graph download (admin-config surface)
- Repo-wide NUL byte pre-commit scan

### RC31 / RC6.7 (2026-05-30) — Security: AdapterHttpClient horizontal SSRF fix (11 connectors) + literal NUL cleanup (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.6` = `c8b37150a`)

別エージェントの cross-review により、RC6.5+RC6.6 で `HttpWebhookDispatcher`
に入れた SSRF guard と **同じ class の脆弱性** が `AdapterHttpClient`
にも残っていることが判明 → fix。

#### Horizontal SSRF fix — AdapterHttpClient

`AdapterHttpClient.validateExternalUrl` は以下から呼ばれる
**全 outbound HTTP 共通の guard**:
- 11 connector adapter (Slack / Teams / Mattermost / Notion /
  Salesforce / M365 Mail / Gmail / Chatwork / Box / Dropbox / IMAP)
  の `download*File*` / API call
- `ConnectorDefinitionServiceImpl` (connector config save 時の
  endpoint validation)
- `IngestWebhookController` (notification callback URL validation)

RC6.6 までの guard は JDK の `isLoopback` / `isLinkLocal` /
`isSiteLocal` / `isAnyLocal` predicate のみ。攻撃経路:
- NAT64 `64:ff9b::/96` + `64:ff9b:1::/48`、6to4 `2002::/16`、
  Teredo `2001::/32`、IPv4-compatible `::a.b.c.d` で embedded IPv4
  (127.0.0.1、169.254.169.254 cloud metadata、RFC 1918、etc.) に到達
- IPv4 special-use range (`0/8`、`100.64/10`、`192.0.0/24`、
  `198.18/15`、`240/4`、`255.255.255.255`) — JDK predicate で
  classify されない

修正 (`AdapterHttpClient.java`): `HttpWebhookDispatcher` の RC6.5+RC6.6
パターン (`isAddressSafe` + `extractEmbeddedIpv4`) を移植。同一の
6 transition format detection + 9 IPv4 special-use range block。

#### Redirect handling tightened

- `SHARED` HttpClient: `Redirect.NORMAL` → **`Redirect.NEVER`**。
  JDK の auto-follow が target を revalidate せずに redirect 追従
  していた (既知の SSRF anti-pattern)
- `sendWithRedirectValidation` で relative `Location` (`Location: /admin` 等)
  を元 URI に対して resolve してから `validateExternalUrl` に渡す。
  以前は relative form が verbatim で渡され URL parse 失敗 or 誤解釈

#### Test NUL cleanup (P3 from prior review round)

`HttpWebhookDispatcherTest.java` line 481 のリテラル NUL byte
(`"with\x00nul"`) を Java の `\0` octal escape に置換。`.class` は
byte-equivalent (compiler が `\0` を NUL に解決) で runtime 動作
同一だが、source file が binary 扱いされず grep / rg / diff /
auto-review tool で正しく扱える。commit `14b232475` (RC6.7 release-
package commit 前に push 済、本 tag に含まれる)。

#### Tests

- 3 新規 regression in `AdapterRegistryTest` (IPv6 transition wrap
  5 形式 / IPv4 special-use 3 範囲 / SHARED redirect 設定)
- `HttpWebhookDispatcherTest` (59) + `AdapterRegistryTest` (19) =
  **78 PASS** for SSRF surface
- 6 connector adapter contract tests (Slack / Teams / Mattermost /
  Notion / Salesforce / M365 Mail) **63 PASS** — `Redirect.NEVER` への
  flip が legitimate adapter API call pattern を壊さないことを確認
- 16-class focused regression: **260/260 PASS** (was 241 in RC6.6;
  +19 from AdapterRegistryTest を focused set に追加 + 3 新規)

#### Out-of-scope (意図的に未対応)

Purview / Atlas / OIDC discovery / Microsoft Graph download — admin
が configure した IdP / on-prem endpoint への outbound surface。同 block
list を無条件適用すると正当な内部統合を壊すリスクがあるため、
別途 opt-in "production-mode" property or explicit allowlist 方式で
対応するのが適切。本 RC では touch しない。

#### Code duplication (recognized tech debt)

`isAddressSafe` + `extractEmbeddedIpv4` が `HttpWebhookDispatcher` と
`AdapterHttpClient` で重複。**Rule of three** に従い、3 つ目の transition
format が必要になった時点で shared `SsrfGuard` utility に extract
する。今回の hot security fix では in-place duplication が安全。

#### Change scope vs RC6.6 (precise)

- **変更あり (RC6.7)**:
  - `AdapterHttpClient.java` (+124 行: isAddressSafe + extractEmbeddedIpv4 +
    redirect 強化)
  - `AdapterRegistryTest.java` (+30 行: 3 new tests)
  - `HttpWebhookDispatcherTest.java` (literal NUL → `\0` escape; .class 同一)
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`, `AGENTS.md`
- **無変更 (RC6.6 と byte-equal)**:
  - 他 Java surface 全件 (含む `HttpWebhookDispatcher.java` — RC6.5+RC6.6 の
    canonical 実装をそのまま参照)
  - TS surface 全件
  - properties / patches / views / Mango / migration / DB bootstrap
  - SOC templates + validator script
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md`

#### Commit + tag 関係

- Test NUL cleanup: `14b232475`
- Security fix (AdapterHttpClient): `12994c342`
- RC6.7 release-package commit: 後続
- **`v3.1.1-RC6.7` annotated tag target**: release-package commit

RC6.6 tag (`c8b37150a`) は force-update せず歴史的マイルストーンとして保持。

#### Follow-up status

**Resolved in this RC**:
- AdapterHttpClient horizontal SSRF (NAT64 + 6to4 + Teredo +
  IPv4-compatible + IPv4 special-use)
- SHARED HttpClient auto-follow redirect → `NEVER`
- `sendWithRedirectValidation` relative `Location` 解決
- HttpWebhookDispatcherTest literal NUL → `\0` escape

**Remaining (informational, not blocking, RC6.6 から継続)**:
- Purview / Atlas / OIDC discovery / Graph download outbound (上記
  "Out-of-scope" 参照)
- HTTPS DNS pinning via SocketFactory
- isAddressSafe + extractEmbeddedIpv4 extraction to shared utility

### RC30 / RC6.6 (2026-05-30) — Security hardening: SSRF guard で IPv4 special-use + Teredo + RFC 6052 /48 NAT64 を追加 block (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.5` = `94de9d269`)

RC6.5 で塞いだ「明白な」 IPv6 transition 穴 (NAT64 well-known + 6to4 +
IPv4-compatible + IPv4-mapped) の **第2波** を別エージェントの追加
レビューで指摘 → fix。攻撃者が RC6.5 後に pivot 可能な surface を
network special-use range 観点で塞ぐ。

#### 追加 IPv4 special-use block (5 range)

`isAddressSafe` に IANA special-purpose registry の 5 range を追加:

| Range | RFC | block 理由 |
|---|---|---|
| `0.0.0.0/8` | RFC 1122 §3.2.1.3 | "this" network — 0.0.0.0 literal (既存 catch) 以外も |
| `100.64.0.0/10` | RFC 6598 | Carrier-grade NAT / shared address space — 多くの ISP / cloud で internal |
| `192.0.0.0/24` | RFC 6890 | IETF protocol assignments (DS-Lite、NAT64 well-known 等) |
| `198.18.0.0/15` | RFC 2544 | Benchmarking / interconnect-test networks |
| `240.0.0.0/4` | RFC 1112 §4 | 将来予約 + `255.255.255.255` 限定ブロードキャスト |

#### IPv6 transition format 拡張 (2 form 追加)

`extractEmbeddedIpv4` に 2 form 追加:

- **`64:ff9b:1::/48` (NAT64 local-use, RFC 8215)** — RC6.5 では
  best-effort /96-PLR 抽出 (bytes 12-15) のみだったが、本 RC で
  RFC 6052 §2.2 の /48 layout を正式 handle:
  - IPv4[0..15] = bytes 6-7
  - reserved "u" octet = byte 8
  - IPv4[16..31] = bytes 9-10
  - suffix = bytes 11-15 (全 zero でないと /96-PLR fallback)
- **Teredo `2001::/32` (RFC 4380 §4)** — IPv6 tunnel-over-UDP prefix。
  strict prefix check (`bytes 0-3 == 20:01:00:00`) で他の 2001::/16
  (`2001:db8::` documentation、`2001:4860::` Google 等) を mis-extract
  しない。bytes 12-15 = client IPv4 の one's complement を `(byte) ~b[i]`
  で decode → 既存 classification に流す

#### Tests

`HttpWebhookDispatcherTest`: **59/59 PASS** (RC6.5 の 52 + 新規 7
test method + extractor test 内に 2 assertion 追加)。
- 3 IPv4 special-use block test (100.64 / 198.18 / 240/4)
- 2 RFC 6052 /48 NAT64 (loopback wrap blocked、8.8.8.8 wrap allowed)
- 2 Teredo (`ffff:fffe` 反転 = 0.0.0.1 wrap blocked、`f7f7:f7f7` 反転
  = 8.8.8.8 wrap allowed)

#### 残存 note (regression ではない、informational)

HTTPS dispatch は元 hostname URL で接続して TLS cert verification を
活用する設計のため、DNS rebinding は TLS で mitigated。将来的に
HTTPS を resolved IP に pin する SocketFactory hardening は可能だが
本 RC 範囲外。

#### Change scope vs RC6.5 (precise)

- **変更あり (RC6.6)**:
  - `HttpWebhookDispatcher.java` (+67 行: 5 IPv4 range + RFC 6052 /48 + Teredo)
  - `HttpWebhookDispatcherTest.java` (+67 行: 7 新規 test method + 2 inline)
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`, `AGENTS.md`
- **無変更 (RC6.5 と byte-equal)**:
  - 他 Java surface 全件
  - TS surface 全件
  - properties / patches / views / Mango / migration / DB bootstrap
  - SOC templates + validator script
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md` (RC6.5 の closure 維持)

#### Commit + tag 関係

- Security hardening: `ce2abf646`
- RC6.6 release-package commit: 後続
- **`v3.1.1-RC6.6` annotated tag target**: release-package commit

RC6.5 tag (`94de9d269`) は force-update せず歴史的マイルストーン
として保持。

#### Follow-up status

**Resolved in this RC**:
- 5 IPv4 special-use bypass surface (0/8 + 100.64/10 + 192.0.0/24 +
  198.18/15 + 240/4 + broadcast)
- `64:ff9b:1::/48` の RFC 6052 §2.2 /48 layout 対応 (RC6.5 では
  /96-PLR best-effort のみ)
- Teredo `2001::/32` の embedded IPv4 unwrap

**Remaining (informational, not blocking)**:
- HTTPS DNS pinning via SocketFactory (現状は TLS cert verification
  で mitigated)
- 他 deployment-side items は RC6.5 から継続

### RC29 / RC6.5 (2026-05-30) — Security: SSRF guard IPv6 transition unwrap + manual-verification doc 3-round closure (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.4` = `afdf4d832`)

#### Security fix — SSRF via IPv6 transition addresses (CWE-918)

外部 (tonghuaroot) からの GitHub security advisory:
`HttpWebhookDispatcher.isAddressSafe` の IPv6 側チェックが ULA
(`fc00::/7`) しか弾いておらず、IPv6 transition addresses で internal
IPv4 destination を encode すると素通り。dual-stack / NAT64 ネット
ワークでは kernel が embedded IPv4 にルーティング → 内部リソース
(cloud metadata 含む) に到達。

確認した bypass 経路 (PoC が示した 5 形式):
- `64:ff9b::7f00:1`     → 127.0.0.1 (NAT64 wrap, RFC 6052)
- `64:ff9b::a9fe:a9fe`  → 169.254.169.254 (cloud metadata)
- `64:ff9b:1::7f00:1`   → 127.0.0.1 (NAT64 local-use, RFC 8215)
- `2002:7f00:1::`       → 127.0.0.1 (6to4 wrap, RFC 3056)
- `::7f00:1`            → 127.0.0.1 (IPv4-compatible, RFC 4291)

修正 (`HttpWebhookDispatcher.java`):
- 新規 `extractEmbeddedIpv4(InetAddress)` ヘルパー。5 形式を byte-prefix
  で recognize、`Inet4Address` を返す。NAT64 local-use の non-/96 PLR は
  best-effort 抽出 (再分類で safety net)
- `isAddressSafe` の IPv6 ULA check の後で extract → 再帰呼び出し。
  embedded IPv4 が block rule にヒットすれば transition literal も block
- attempted bypass を WARN ログ可視化 (既存の plain block は DEBUG)

テスト (`HttpWebhookDispatcherTest.java`):
- 15 新規 regression: NAT64 / NAT64-local-use / 6to4 / IPv4-compatible の
  blocked / allowed 両ケース + extractor 単体
- 計 52/52 PASS (既存 37 + 新規 15)

Read-capable SSRF (`/webhook/test` がレスポンス body を呼び出し元に
返す) のため重要度は **High**。advisory に reporter (tonghuaroot) を
credit。

#### Manual-verification doc — 3 rounds of external review closure

RC6.4 で出荷した `docs/MANUAL-VERIFICATION-CONNECTORS.md` (1300+ 行)
が、3 巡の外部 review (live 実行) で doc/actual drift を 15 件 + 自己
review 9 件、計 24 件発見 → 全件 live で再確認 → 修正 → push。

- **Round 1** (P1 ×4 + P2 ×3): zsh env-var word-splitting、multipart
  ingest `request` part 必須、POST/PUT は slim response、
  `allowedFolderIds=[]` + `delegated=true` → 400、scheduler status
  wrapper、by-group field name (`groupType` / `userId`)、UI に
  credentialRef Form.Item なし
- **Round 2** (P1 ×2 + P2 ×1): ACL parameter name (`addACEPrincipal[n]`
  / `addACEPermission[n][m]` 必須、旧形式は silent no-op)、delegated
  profile `defaultConnectorId` collision (`Only one enabled profile
  per defaultConnectorId`)、Import Profile GET wrapper
  `{"profile":{...},"warnings":[...]}`
- **Round 3** (P2 ×1 + self-review of 8): delegated schedulerEnabled=true
  → HTTP **403** `denialReason="SCHEDULER_REQUIRES_ADMIN"` (旧 "400 or
  200 normalized")、+ 全 "expect:" 行を「単一 HTTP code + 完全 message
  snippet」形式に tighten

結果: 全 documented HTTP code と message snippet が live RC6 HEAD stack
に対して verify 済み。「X もしくは Y」の曖昧表現は doc 内で根絶。§14 に
`addACEPrincipal[n]` silent no-op trap と `defaultConnectorId` 一意性
制約の注記追加。

#### Change scope vs RC6.4 (precise)

- **変更あり (RC6.5)**:
  - `HttpWebhookDispatcher.java` (security fix)
  - `HttpWebhookDispatcherTest.java` (15 new tests)
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md` (3 round 累積 fix)
  - `docs/soc-templates/VALIDATION.md` (regenerated; validator state 同一)
  - `README.md`, `AGENTS.md` (RC6.5 references)
  - `REVIEW_PACKET.md`, `RELEASE_NOTES.md`, `CLAUDE.md` (本 RC)
- **無変更 (RC6.4 と byte-equal)**:
  - 他 Java surface 全件
  - TS surface 全件 (UI、services、tests)
  - properties / patches / views / Mango index / migrations / DB bootstrap
  - SOC templates + `scripts/validate-soc-templates.sh`

#### Commit + tag 関係

- Security fix: `94d3355a4`
- Manual-verification doc rounds 1/2/3: `a3ac2bc94` / `5b43eb7b4` / `343fe5545`
- RC6.5 release-package commit (本 doc + RELEASE_NOTES + REVIEW_PACKET): 後続
- **`v3.1.1-RC6.5` annotated tag target**: release-package commit

RC6.4 tag (`afdf4d832`) は force-update せず歴史的マイルストーンとして保持。

#### Tests + verification

- `HttpWebhookDispatcherTest`: **52/52 PASS** (37 既存 + 15 新規)
- `mvn clean package -f core/pom.xml -Pdevelopment -DskipTests`: BUILD SUCCESS
- Manual-verification §2 → §11: live RC6 HEAD stack で全 path verify 済

#### Follow-up status

**Resolved in this RC**:
- GHSA SSRF via IPv6 transition wrap (5 形式すべて block)
- 3 round の manual-verification doc drift 全 24 件

**Remaining (operator-side, RC6.4 から継続)**:
- Network/TLS、SIEM credentials、通知ルーティング、threshold tuning
- Kibana Detection NDJSON + Splunk savedsearches CLI validation

**Remaining (test-skip triage backlog, RC6.4 から継続)**:
- 155 persistent Playwright failure + 195 explicit skip は memory
  `test-skip-triage` で track

### RC28 / RC6.4 (2026-05-23) — SOC template validation gate + RC5.6 vs RC6 HEAD Playwright baseline diff (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.3` = `77ddfe071`)

Quality-improvement RC。2 epics:

- **Epic 1**: RC6 → RC6.3 で毎 cycle 「template body bug が
  external review でしか surface しない」 pattern が続いた (Filebeat
  env / Vector VRL / Fluent Bit DST)。**vendor CLI を Docker image
  で走らせる validator** を追加して、RC6.5+ で同じ class の bug を
  ship しないようにする。
- **Epic 2**: RC6 が RC5.6 に対して behavioural regression を 1 件も
  入れていないことを、**full Playwright chromium suite (1032 tests) を
  RC5.6 と RC6 HEAD の両方で実行 + per-test diff** で実証。

Java + TypeScript ソース: RC6.3 と byte-equal。本 RC の変更は docs /
shell scripts / SOC template content fix (validator が catch したもの)
のみ。

#### Epic 1 — SOC template validation gate

新 file: `scripts/validate-soc-templates.sh` (16 KB Bash)

Phase 1 (常時、`python3` のみ): JSON / YAML / TOML parse、NUL-byte
smoke (Python — bash の argv NUL stripping を回避)、file-type smoke、
placeholder enumeration

Phase 2 (`VALIDATE_DOCKER=1` opt-in、Docker 必須):
- `vector validate --skip-healthchecks` (timberio/vector)
- `fluent-bit -c … --dry-run` (fluent/fluent-bit)
- `filebeat test config` (docker.elastic.co/beats/filebeat、
  8.x の ownership refusal を tmpfs + chown root で回避)
- `cortextool rules check --backend=loki` (grafana/cortex-tools、
  LogQL は `${VAR:-default}` を native interpolate しないので
  Python envsubst で前処理)

Phase 3 (`WRITE_VALIDATION_MD=1` opt-in):
- `docs/soc-templates/VALIDATION.md` に最新 run state を書き出し

**Validator bring-up で 5 件の real template bug を catch** (syntax-spec-
confidence approach が見逃していたもの):

1. **Vector header comment の `${...}` interpolation** — Vector は
   comment 内の dollar-brace token も env var として解釈する。header
   の "Replace every `${...}`" という example が
   `Missing environment variable in config. name = "..."` を発生
2. **Fluent Bit `Code |` heredoc** — classic INI parser が line 59
   で "extra indentation level found" で reject。Lua を
   `fluent-bit-nemakiware-time-enrichment.lua` に外出しして
   `Script` directive で参照
3. **VRL `??` on infallible `."@timestamp"`** — VRL field access は
   infallible (missing path で null 返却、error 出さない) なので
   `??` は strict mode で "unnecessary error coalescing operation"
   error。conditional assignment に置換
4. **Vector `buffer.max_size = 268435456`** (ちょうど 256 MiB) は
   `>= 268435488` (256 MiB + 32 B 内部 overhead) 制約未満。
   536870912 (512 MiB) に bump して Vector minor version drift に
   余裕を持たせる
5. **LogQL `offset 1h` placement** — `count_over_time(...)` の外側に
   置くと "syntax error: unexpected offset"。LogQL の正しい placement
   は range-vector selector の内側: `[7d] offset 1h`

#### Epic 2 — full Playwright baseline diff (RC5.6 vs RC6 HEAD)

同一 Docker stack で WAR だけ swap して full chromium suite (1032 tests)
を 2 回実行:

| Label | Tag / commit | WAR SHA-256 |
|---|---|---|
| RC5.6 | `v3.1.1-RC5.6` = `adf8db3b4` | `749dedd883c8146516d4f618859db2b8c317f9f972939e432cf4a7989feb592e` |
| RC6 HEAD | `release/3.1.1-RC6` HEAD = `1ba21bc59` | `9df81beb10e8f3309534e8d830c734fb9485a3bc32d38c36a52cf54e5af56328` |

(RC6 HEAD `1ba21bc59` = RC6.4 Epic 1 commit。Epic 1 は doc/script のみ
で Java/TS 一切触らないので、RC6 HEAD の behaviour は RC6.3 と等価)

Aggregate:

| Stat | RC5.6 | RC6 HEAD | Δ |
|---|---:|---:|---:|
| Passed | 673 | 679 | **+6** |
| Failed | 162 | 156 | **−6** |
| Flaky | 2 | 2 | 0 |
| Skipped | 195 | 195 | 0 |
| Total | 1032 | 1032 | — |
| Duration | 77 min | 76 min | −1 min |

Per-test classification (RC6.4 spec の 5 bucket):

- **RC6 regression: 0** — 1 candidate (`group-management-crud.spec.ts:315`
  `should add member to group`) は flaky に再分類 (Ant `Select` dropdown
  の viewport positioning、group-management code は RC5.6 → RC6 HEAD で
  一切変更されていないため real regression と矛盾)
- **Improved by RC6: 6** — 5 件は RC6 新規の `/v1/admin/connectors/by-group`
  endpoint、1 件は RC6.1 で追加された `removePrincipalIds > MAX` 400 cap
- **Pre-existing fail: 155** — RC5.6 と RC6 HEAD の両方で fail。85 spec
  file に均等分散 (各 file:line ユニーク、cluster なし)。
  test-skip-triage memory で track されている長期 stabilization backlog
  と同じ群。top file group: `components/layout-navigation` (14)、
  `search/custom-property-search` (14)、`components/protected-route` (12)、
  `user-scenarios` (10)
- **Persistent pass: 672** — core production behaviour は RC5 → RC6
  cycle 全体で stable
- **Skipped (`test.skip`): 192** — explicit annotation、想定通り

**結論**: RC6 は regression ゼロ + 6 net 新規 green を出荷。
155 backlog は RC6.4 で変化なし。

#### Change scope vs RC6.3 (正確な分類)

- **変更あり (RC6.4)**:
  - `scripts/validate-soc-templates.sh` (新規 16 KB)
  - `docs/soc-templates/fluent-bit-nemakiware-time-enrichment.lua` (新規 Lua 外出し)
  - `docs/soc-templates/fluent-bit-nemakiware.conf` (Code → Script directive)
  - `docs/soc-templates/vector-nemakiware.toml` (header comment + VRL conditional + buffer.max_size)
  - `docs/soc-templates/loki-ruler-rules.yml` (offset placement + comment)
  - `docs/soc-templates/README.md` (§Template validation status 書き換え、validation matrix 4/6 CLI-validated に flip)
  - `docs/soc-templates/VALIDATION.md` (新規 generated artefact)
  - `REVIEW_PACKET.md` (§10 inline baseline diff + classification)
  - `RELEASE_NOTES.md` (RC6.4 セクション)
  - `CLAUDE.md` (本セクション)
- **無変更 (RC6.3 と byte-equal)**:
  - 全 Java surface
  - 全 TypeScript surface (UI、services、specs)
  - 全 properties、patches、views、Mango index、migrations、DB bootstrap
  - Kibana NDJSON / Splunk SPL templates (offline parser 不在で operator gate のみ)

#### Tests + verification

- **SOC validator**: `VALIDATE_DOCKER=1 scripts/validate-soc-templates.sh` →
  PASS 20 / SKIP 3 / FAIL 0 / total 23。3 SKIP は host に Python
  tomllib なし (Vector は Phase 2.1 で cover)、Kibana NDJSON operator
  gate、Splunk btool operator gate
- **Playwright full chromium ×2**: RC5.6 = 673/162/2/195 (4622s)、
  RC6 HEAD = 679/156/2/195 (4538s)。両 run とも clean exit (Playwright
  exit 0)
- **Java tests**: 182/182 focused 14 Java test classes pass (RC6.3 と
  byte-equal — 本 RC で Java は一切触らない)
- **TypeScript build**: `npm run build` clean (Epic 2 の WAR build 時)
- **Vector / Fluent Bit / Filebeat / cortextool**: 4 件すべて
  公式 Docker image の vendor CLI で validate 済み (Epic 1 acceptance)

#### Commit + tag 関係

- Epic 1 (validator + 5 template fix): `1ba21bc59`
- Epic 2 (REVIEW_PACKET §10 inline): `c077dc55d`
- RC6.4 release-package commit (RELEASE_NOTES + CLAUDE + REVIEW_PACKET retitle): 後続
- **`v3.1.1-RC6.4` annotated tag target**: release-package commit

RC6.3 tag (`77ddfe071`) は force-update せず歴史的マイルストーン
として保持。

#### Follow-up status

**Resolved in this RC**:
- 5 real template bug (validator が bring-up で catch したもの)
- RC6-cycle の "template body bug が external review でしか surface
  しない" pattern — validator gate が tag 前に catch するようになった
- 長年の question "RC6 は RC5.6 vs 155-failure cluster に regression
  を入れているか?" — full Playwright baseline diff で **NO** と回答

**Remaining (operator-side, by design)**:
- Network/TLS、SIEM credentials、通知ルーティング、threshold tuning
- Kibana Detection NDJSON + Splunk savedsearches の CLI validation —
  両者 offline parser 不在、live cluster import が必須

**Remaining (test-skip triage backlog)**:
- Playwright の 155 persistent failure + 195 explicit skip は memory
  `test-skip-triage` (Playwright 421件のtest.skip分類と改善方針) で
  track。RC6.4 scope ではない

### RC27 / RC6.3 (2026-05-23) — RC6.2 review 5件 全件解消 + tag/branch 再整合 (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.2` = `02afee891`)

RC6.2 ship 直後の external review で挙がった 5 件 (P1 ×2 + P2 ×2 +
P3 ×1) を全件解消。**RC6.2 と同様 P1 の 2件は「tag と shipping
artifact 不一致 + divergence rule 矛盾」** で、RC5.5→RC5.6 /
RC6→RC6.1 / RC6.1→RC6.2 と同じ pattern。新 tag を切ることで両方
解消。

#### P1 ×2: tag/branch 再整合 → RC6.3 tag 発行で解消

- **P1-A**: RC6.2 tag (`02afee891`) には post-tag 5-fix commit
  (`bf7c07b3f`) が含まれず、reviewer が tag を checkout すると
  Filebeat / Fluent Bit / Vector の修正前 buggy 状態をレビュー
  する状況
- **P1-B**: REVIEW_PACKET §3 divergence rule で
  `docs/soc-templates/**` を "review-time clarifying additions
  only" としていたが、実際は executable config 本体の修正

→ **解消方法**: RC6.3 tag を branch HEAD で切る + divergence rule
の "clarifying additions only" 制約を緩和 (RC6.2 で SOC templates
が tag 内に取り込まれた前例どおり)

#### P2 ×2: Fluent Bit DST + Vector ??

- **P2-A** (`fluent-bit-nemakiware.conf`): `utc_offset` 計算が
  `now` 固定 → DST のある TZ で過去ログ処理時に 1h ズレ。per-record
  で `offset_at(epoch)` を算出、DST 境界対応の re-validation を
  追加。非 DST TZ では従来と同じ動作 (定数 offset)
- **P2-B** (`vector-nemakiware.toml`): `parse_timestamp(...)`
  は fallible function、VRL strict mode で error handling 必須。
  `?? null` coalesce 追加 → malformed timestamp で transform 全体
  落ちず null-guard を通る

#### P3 ×1: REVIEW_PACKET §5 残った断定

`"none of the 155 failures are attributable to RC6 / RC6.1 /
RC6.2"` という強い主張が §5 に残存。§2 note 4 で確立した evidence
boundary に合わせて `"none show up in the 6 directly-touched specs"`
程度に弱化 (実証範囲のみに主張を絞る)

#### Tests + verification

- 182/182 focused 14 Java test classes pass (RC6.2 から変化なし)
- 66/66 RC5/RC6-area Playwright smoke (no flake)
- Full chromium suite 未再実行 — RC6.2 closure 時の 684/155/94/97
  baseline を継承 (config-only 変更で UI 動作影響なし)
- NDJSON / YAML / SPL syntax validates
- Vector VRL は live `vector validate` 未実行 (本 repo に vector
  binary なし、syntax fix は VRL 仕様準拠の confidence fix)
- Fluent Bit Lua は inline 数式トレースで plausibility check
  (UTC / JST / DST 境界 3 ケース)。**実機 (Fluent Bit binary) で
  の live test は未実施** — operator pre-deploy で DST 日の
  synthetic input を入れて検証推奨 (REVIEW_PACKET §2 note 3)

#### Commit + tag 関係

- P2/P3 fixes (Fluent Bit DST + Vector ?? + REVIEW_PACKET tone):
  `3afd284f5`
- RC6.3 docs closure: 後続
- **`v3.1.1-RC6.3` annotated tag target**: doc-closure commit

RC6.2 tag (`02afee891`) は force-update せず歴史的マイルストーン
として保持。

#### Follow-up status

**Resolved**: 5 RC6.2 review findings (P1 ×2 via tag cut +
P2 ×2 code fix + P3 ×1 doc tone)

**Remaining**:

- **R1 deployment-side** (network/TLS、SIEM credentials、通知ルー
  ティング、threshold baseline): 本質的に repo 出荷不可
- **Vector live validation**: vector CLI 利用可能時に
  `vector validate vector-nemakiware.toml` で確認
- **Full Playwright RC5.6 baseline-diff**: full-suite green-up
  epic と一緒に取り組む

### RC26 / RC6.2 (2026-05-22) — RC6.1 review + self-review 17件 全件解消 (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6.1` = `595754b8c`)

RC6.1 ship 後の **2 review (外部 R1 4件 + 自己批判 13件 = 17件)** を全件
解消した closure RC。Tier 1 (送付前必須) + Tier 2 (強く推奨) + Tier 3
(整理) を全部完了。RC6.1 tag (`595754b8c`) は force-update せず歴史的
マイルストーンとして保持。

#### Tier 1: 送付前必須 6件

- **#3 Splunk `startswith=eval(...)`** が invalid SPL — `startswith=(...)`
  parens-only form に revert
- **#2 Kibana NDJSON schema** — EQL `.keyword` syntax は default Filebeat/
  Vector dynamic mapping 想定 (description で明示)。`new_terms` rule に
  `query` field 共存 OK 確認
- **#14 Kibana off-hours rule の field 依存** — `hour_of_day_local` +
  `day_of_week_local` enrichment を vector (VRL `format_timestamp!`) +
  fluent-bit (Lua filter) + filebeat (JS processor) 全 3 shipper に追加。
  README に TZ env 説明追記
- **#15 Kibana threshold ハードコード** — JSON は `${VAR}` 不可なので
  defaults (20/50) を残し、README に sed cookbook + override 手順を明記
- **#16 stale `kibana-alerting-rules.json` 参照** — filebeat ×2 +
  fluent-bit ×1 を `kibana-detection-rules.ndjson` に置換
- **#17 README placeholder grep が `*.json` のみ** — `*.ndjson` 追加 +
  `2>/dev/null` で missing-glob warning 抑止

#### Tier 2: 強く推奨 4件

- **#11 perMemberImpact member 順序非決定的** —
  `Collections.sort(allMemberUserIds)` 追加。memberLimit truncation が
  alphabetical first-N に統一。2 unit test 追加 (`...sortedAlphabetically`
  / `...takesFirstNAlphabetically`)
- **#7 P2-1 cap の identity 不可視問題** — API 追加なしで解消:
  `docs/SOC-AUDIT-INTEGRATION.md §5.6` に per-user fallback
  (`/by-principal/{userId}?expand=true`) を documented escape hatch として
  記載
- **#8 Filebeat `${HOSTNAME}` env interpolation** — Filebeat 自身の env-var
  syntax に統一 + README に process env 渡し方を明記 (systemd /
  docker-compose 別)
- **#9 Loki TZ doc** — JST operator 向け regex 書き換え例
  (`^(1[3-9]|20)$` for 22:00-05:59 JST = 13:00-20:59 UTC) を README に追加。
  TZ env 必須箇所を per-shipper で明示

#### Tier 3: 整理 4件

- **#6 `externalIngest.ts` を REVIEW_PACKET §3 allowed-divergence から削除**
- **#10 test count drift** — 実機再カウントで `182` (RC6.2 +2 sort tests
  added、177→180→182 と整合)
- **#12 useMemo コメント書き直し** — perf 主張削除、dep-array stability の
  意味だけ正直に書く
- **#13 "解消" framing** — repo 出荷可能スコープ完了 + 4 deployment 固有
  items は本質的に残るとトーン統一

#### Tier 1 別件: #1 "66/66 regression" honest re-label

RC6.1 まで citing していた "66/66 Playwright regression" は 118 spec
中 6 spec の **RC5/RC6-area smoke**。RC6.2 で初めて全 chromium suite
(118 specs / 1030 tests) を 1.3h かけて実行:

- **684 passed**
- **155 failed**
- **94 skipped**
- **97 did not run** (serial-mode chain abort)

155 failure は RC5/RC6-area 6 spec **外** に集中 (documents / permissions /
search / versioning 等の古い spec、React 19 / AntD 5 drift 由来と推定)。
**実証されている範囲**: RC6.x が直接触れた 6 spec は **66/66 PASS** (2連続
runs、no flake)。**実証していない範囲**: RC5.6 ベースラインで同じ
suite を流して 155 件と差分比較していない (この比較は別 epic、本 RC
範囲外)。よって "155 件 pre-existing" は **作業仮説** として扱い、
proven claim ではない。

#### Java focused 14 test class

- **182/182 PASS** (RC6 177、RC6.1 180、RC6.2 +2 sort)

#### Commit + tag 関係

- SOC fixes (Splunk + Kibana NDJSON + shipper enrichment): `750d70d85`
- sort + useMemo comment: `fd03d4ab4`
- 本 doc commit: 後続
- **`v3.1.1-RC6.2` annotated tag target**: 本 doc commit

#### 残課題

- **R1**: repo 出荷可能スコープ完了 (RC6.1 + RC6.2 で完全解消)。残りは
  deployment 固有 (network/TLS、SIEM 認証、通知ルーティング、threshold
  baseline) のみで repo 出荷不可
- **Full Playwright suite green 化**: 155 failures (RC5.6 baseline 未比較、
  作業仮説として pre-existing と推定)。RC6 cycle 外、別 epic + baseline-diff
  で取り組む

### RC25 / RC6.1 (2026-05-22) — RC6 external review fixes P2-1 / P2-2 / P2-3 / P3 (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC6` = `9dfd87adb`)

RC6 受け入れ後に外部 review で挙がった 4 件 (P2 ×3 + P3 ×1) を解消。
public API 不変、追加された response field は additive。RC6 tag
(`9dfd87adb`) は force-update せず歴史的マイルストーンとして保持。

#### P2-1 /by-group response amplification

`perMemberImpact[]` の各 member entry が group-only connector の
full object をすべて保持 → `members × connectors` で爆発。
M3 list() cache でも shape 自体の amplification は残っていた。

- `MAX_LOST_PER_MEMBER = 50` 定数追加
- 各 member の `lostIfGroupRemoved` array を 50 件で cap
- 新 field `lostCount` (untruncated 件数) と
  `lostIfGroupRemovedTruncated` (boolean) で SOC + UI が truncation
  を検知可

#### P2-2 buildMatches order regression

M3 の "smaller side iterate" 最適化が `principalsToMatch` 順を
emit してしまい、REVIEW_PACKET §2 "byte-identical" 主張に違反。
- direction switch を revert、常に `allowed` を iterate
- `principalsToMatch` は callsite で常に Set なので contains() は
  すでに O(1)、HashSet wrap も不要

#### P2-3 NUL byte in ConnectorGovernanceTab.tsx

L1 fix で `simulateRemove.join(' ')` を意図したが、実 file content
に NUL (`'\0'`) が混入。`file` utility で `data` 扱い、grep/IDE が
binary とみなす。

- `JSON.stringify(simulateRemove)` に置換 (reviewer 推奨)
- 防止コメント追加: single-char separator は今後使わない

#### P3 initialFetchDoneRef per-kind tracking

`initialFetchDoneRef` (boolean) が mode 間で共有 → group mode を
先に開く → flag 立つ → principal mode に切替 → USER fetch されない。

- `Set<'USER' | 'GROUP'>` に変更、kind 単位で track
- 不足 kind のみ fetch
- `fetchPrincipals` も merge 動作に: 非 fetch 対象 kind の options +
  totals を保持

#### Tests

- ConnectorByPrincipalGovernanceTest: 27 → **30 (P2-1 +2, P2-2 +1)**
- 14 focused Java test class: 177 → **180 PASS**
- RC5+RC6 Playwright regression: **66/66 PASS** (変更なし)

#### Commit + tag 関係

- P2-1+P2-2 (server): `be7160d48`
- P2-3+P3 (UI): `a246ffe81`
- **`v3.1.1-RC6.1` annotated tag target**: docs commit 後にカット

#### Follow-up

- **R1** (Low, ops) — **解消** (repo 出荷可能部分完了)。`docs/SOC-AUDIT-INTEGRATION.md` playbook + `docs/soc-templates/` **import-ready テンプレ (operator validation required)** (Filebeat / Fluent Bit / Vector shipper + Kibana Detection Engine NDJSON / Loki Ruler / Splunk savedsearches)。残るは deployment 固有 (network/TLS、SIEM 認証、通知ルーティング、threshold チューニング) のみで repo 出荷不可。**Note**: 各テンプレートは syntax-spec confidence draft、live import 未済 — operator は `docs/soc-templates/README.md` の "Template validation status" / "Operator pre-deploy validation commands" 表を pre-deploy で実行する必要

### RC24 / RC6 (2026-05-21 → 2026-05-22) — B3-2 group view + V8/G2 + governance med/low + Dependabot (shipped)

ブランチ: `release/3.1.1-RC6` (off `v3.1.1-RC5.6` = `adf8db3b4`)

RC5.x の correction series 終了後、最初の独立 feature RC。RC5.5 closure
の follow-up テーブル (B3-2 / V8/G2 / H2 / M2 / M3 / L1 / L2) と
35 件の Dependabot backlog を全件解消。public API 変更なし、DB / patch /
view / migration なし。RC5.6 tag (`adf8db3b4`) は force-update せず
歴史的マイルストーンとして保持。

#### B3-2: group-membership impact view

新 endpoint:
```
GET /v1/admin/connectors/by-group/{groupId}
    ?repositoryId=...&includeMembers=true|false&memberLimit=200
```

既存 `/by-principal/{id}` の補完。「group を削除したら誰が何を失うか」
を per-member sole-route detection で返す。

レスポンス: `memberUserIds[]` (memberLimit cap)、`memberUserIdsTruncated`、
`memberCount` (untruncated)、`directGrants[]`、`perMemberImpact[]`、
`perMemberImpactTruncated`。`includeMembers=false` は per-member
expansion を skip する fast path。`MAX_MEMBER_LIMIT=1000` server cap。

UI: `ConnectorGovernanceTab` に Radio toggle (Principal / Group mode)。
group mode は GROUP-only picker + directGrants Card + per-member impact
Card。i18n ja/en に 22 新規 keys。

検証: 27/27 governance Java test、5/5 server contract Playwright spec
+ live (dev bedroom の 39-member `cloud-google:a13@aegif.jp` で truncation
flag 動作確認)。

#### V8/G2: principal picker scale-out (10k+ directory)

- `fetchPrincipals(query, kinds)` で USER/GROUP fetch を選択切替
- **offset=0 fix**: 既存 server pagination 条件は `offset>=0 && limit>0`。
  `limit` のみだと "全件返却" にフォールバック (bug。`/user/list` が live
  で 112/112 → 50/112 に修正)
- `totalCount` を dropdown footer に表示 + truncated 警告
- 初回 fetch を `onDropdownVisibleChange` まで遅延

#### Dependabot security pass

Maven 10 件 (全件 real):
- spring-webmvc 7.0.5 → 7.0.7 (DoS / Script View Templates /
  cache poisoning / SSE)
- logback-core+classic 1.5.19 → 1.5.25 (×3 pom、ACE + class
  instantiation)
- commons-lang3 3.17.0 → 3.18.0 (×2 pom、uncontrolled recursion)

npm 25 件 (実 2 件):
- `npm audit` 直接実行で brace-expansion 5.0.5→5.0.6 と ws<8.20.1 のみ
  real。残り 23 件は master baseline の stale alert (lockfile 既 patched)
- `npm audit fix` で override なしに解決
- post-fix: `npm audit` → 0 vulnerabilities

#### H2 / M2 / M3 / L1 / L2

- **H2**: `connector-governance-simulate-button.spec.ts` 新規 (9 cases — 7
  server contract + 1 UI happy path + 1 M2 contract)
- **M2**: `simulate-remove` に `MAX_REMOVE_PRINCIPAL_IDS=500` +
  `MAX_PRINCIPAL_ID_LENGTH=512` cap。loop allocate 前に early reject、
  個別 entry length も per-entry check
- **M3**: `buildMatches(principalId, principalsToMatch, connectors)` overload
  追加。`listByGroup` で `allConnectors` を method scope で 1 回取得、
  member loop で再利用。**list() 呼出 O(members+1) → O(1)**。内側 loop
  方向最適化 (smaller side as driver + HashSet contains)
- **L1**: `simulateLastAuditedAt` reset useEffect の dep array を
  `useMemo(simulateRemove.join(' '))` に。content-stable
- **L2**: `buildMatches` で `connectorDefinitionService.list()` null
  フォールバック → empty matches[]

#### 検証

- 27/27 ConnectorByPrincipalGovernanceTest (was 13)
- 15/15 ConnectorSimulateRemoveTest (was 11)
- 129/129 ingest+governance Java regression
- 66/66 full RC5/RC6 Playwright regression
- `npm audit` 0 件
- TypeScript clean + UI build green
- Live deploy + atom 200

#### Commit + tag 関係

- B3-2 server: `15936c6b3`
- B3-2 review M+L: `7f31c1d64`
- B3-2 UI: `ca8295b39`
- V8/G2: `507d65253`
- Maven security: `9204d3a95`
- npm security: `9ea197c9a`
- H2 spec: `581694272`
- M2: `06ac804cd`
- M3: `82012a221`
- L1+L2: `8db0eb254`
- **`v3.1.1-RC6` annotated tag target**: 本 doc commit 後にカット

#### Follow-up (post-RC6、release blocker なし)

- **R1** (Low, ops): SOC tooling integration for
  `EXTERNAL_GOVERNANCE_SIMULATE` (NemakiWare repo 外、operator
  monitoring stack 領域)

RC5.5 closure 時の H2 / M2 / M3 / L1 / L2 / B3-2 / V8/G2 全件解消、
Dependabot Maven 全件 / npm real 全件解消、残るは external R1 のみ。

### RC23 / RC5.6 (2026-05-21) — R5 denialReason 精度 + A2 spec CSRF cleanup (shipped)

ブランチ: `release/3.1.1-RC5.5` (off `v3.1.1-RC5.5` = `dfb912da9`)

RC5.5 受け入れ後に残っていた最後の累積 follow-up **R5** を解消し、RC5.5
の Playwright spec CSRF 修正を repo 全体に拡張した cleanup RC。
public API contract / DB / patch / view / migration いずれも変更なし。
RC5.5 tag (`dfb912da9`) は **force-update せず** 歴史的マイルストーンとして
保持。

#### R5: scheduler audit denialReason 精度

`IngestSchedulerService.pollScheduledProfiles` の connector 再評価
で 2 度目の `resolveFolderId(...)` を inline していたため、null 返却時に
`canUseConnectorForDelegatedProfileAsUser` が false を返し、audit の
`denialReason` が `CONNECTOR_NOT_DELEGATED` で emit されていた (実際の
原因は folder 解決失敗)。

- `resolveFolderId(...)` を local 変数に extract
- null → `TARGET_FOLDER_UNRESOLVABLE` を emit して skip (audit エントリ
  shape は不変、reason ラベルのみ accurate に)
- non-null → 既存 connector check 経路へ
- `prepareDelegatedTick` step 5 と同じパターンに揃えた
- 2 新規 unit test (R5 race scenario + legitimate connector denial の
  regression guard)

#### A2: spec CSRF cleanup (repo 全体)

RC5.5 は RC5 area の 3 spec のみ修正。RC5.6 は state-change を発行する
全 43 spec を audit。CsrfInterceptor 装着スコープは Spring MVC
dispatcher (`/api/v1/admin/*`) のみで、Jersey servlet
(`/api/v1/cmis/*`) と CMIS Browser Binding (`/core/browser/*`) は対象外。
実際に修正必要な spec は 2 件のみ:

- `tests/admin/integration-settings.spec.ts` — 12 PUT/POST sites に
  `X-Requested-With: XMLHttpRequest` 追加 + stale な tab 数 assertion
  (15→17) 修正 + loose な `/Connector|コネクタ/i` regex を anchored
  `/^(コネクタ ベータ|Connectors\s+Beta)$/` に置換 (management tab が
  消えても governance tab で false positive PASS する穴を塞ぐ)
- `tests/admin/purview-atlas-e2e.spec.ts` — 7 POST/PUT sites に
  X-Requested-With 追加

RC5.5 ship 直後の Playwright E2E 修正コミット (`7f4b268ba`) も RC5.5 tag
には未含のため、RC5.6 が canonical artifact として束ねる
(CSRF header + serial mode + tab selector + valid sourceSystem を
RC5 area 3 spec に適用済)。

#### 検証

- 96/96 ingest 関連 Java test pass (10 IngestSchedulerDelegatedRunTest
  = 既存 8 + R5 +2)
- integration-settings.spec.ts: **8 failed → 17/17 pass**
- purview-atlas-e2e.spec.ts: 17 pass / 25 skip (Atlas 未配置 env)
- RC5.5 で fix した 35 RC5-area spec も依然 35/35 pass (regression なし)

#### Commit + tag 関係

- R5 feature commit: `cee66573e`
- A2 spec CSRF cleanup commit: `dc0ba6dac`
- RC5.5 post-tag Playwright follow-up: `7f4b268ba`
- Low fix (tab regex tightening) + 本 doc 更新: RC5.6 doc commit
- **`v3.1.1-RC5.6` annotated tag target**: 本 doc commit

#### Follow-up status (cumulative across RC5 cycle)

**Resolved in this RC**: R5, A2 (spec CSRF cleanup repo-wide)

**Remaining** (post-release / RC5.7+ 候補、release blocker なし):

- **R1** (Low, ops): SOC tooling integration for
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event (NemakiWare repo 外)
- **H2** (Medium, test coverage): R3 Simulate button の test
- **M2** (Medium, security): `simulate-remove` request body size limit
- **M3** (Low, scale): `buildMatches` full-scan per call
- **L1 / L2**: nit findings

### RC22 / RC5.5 (2026-05-20) — 外部レビュー C1 blocker fix + H1/M1/M4 (shipped)

ブランチ: `release/3.1.1-RC5.5` (off `release/3.1.1-RC5.4` HEAD `8629782bb`)

外部レビューで指摘された **C1 (epoch overflow による 500 leak)** を
blocker 修正。併せて H1 (audit silent catch) と M1/M4 の doc 整理。
RC5.4 で確立した API contract は維持、C1 fix は overflow 入力時の挙動を
500 → 400 に揃えるだけで、正常入力には影響なし。

#### C1: `autoDisabledSince` epoch overflow → 400 (was 500)

- `ImportProfileDefinitionController.applyAutoDisabledSinceFilter`
  内で `Instant.parse(...).toEpochMilli()` の `ArithmeticException`
  (long overflow) を catch
- cutoff 側 → 400 (`IllegalArgumentException` 経由、R4 と同経路)
- profile-marker 側 → defensive exclude (DateTimeParseException と
  同等扱い、1 件 corrupted で list 全体が 500 にならない)
- live で +999999999-12-31T23:59:59Z → 500 → 400 確認
- 2 ケース unit test 追加

#### H1: audit silent catch を logger.warn + safeAudit helper 化

- `catch (RuntimeException ignored)` は 5 箇所、設計意図 (audit failure
  が業務 path を壊さない) は維持
- 重複を `AuditEmitSupport.safeEmit(...)` ヘルパーに集約
- failure 時 `logger.warn` で op + error message のみログ
  (audit detail body 自体は log しない → secret leak 防止)

#### M1: REVIEW_PACKET.md test evidence 表記訂正

- 「155 focused tests」と「broader pattern (287 tests)」を併記
- focused 12 test class の明示リスト

#### M4: design doc §12.6 / §12.9 strikethrough 統一

- G1-G3 / H1-H3 shipped 反映 (`~~Post-RC5.x follow-up~~ (resolved in RC5.y)`)

#### 設計原則 (RC5.4 から継続)

- 既存 patch / view / Mango / migration / property / Java scheduler
  本体は触らない
- API additive 維持 (C1 は overflow 入力の strictness 完成)
- 既存 unit test 退行ゼロ

### RC21 / RC5.4 (2026-05-20) — R3 + R4 closure review code corrections (shipped)

ブランチ: `release/3.1.1-RC5.4` (off `release/3.1.1-RC5.3` HEAD `01fe84ac5`、
RC5.3 closure correction doc commit を含む)

外部レビュー前に code 反映すべき 2 件 (cumulative closure 批判 review
の R3 / R4) を独立 RC で対応。RC5.3 で確立した API は additive、
R4 は malformed input の挙動を厳格化 (forgiving pass-through → 400)
で API 仕様変更となるため別 RC として扱う。

#### R3: V7 audit fire を debounce → explicit Simulate button

- `ConnectorGovernanceTab` の 800ms debounce useEffect を廃止
- 「Simulate (audit)」button を simulate Select 隣に追加
- click 時のみ W2 `simulate-remove` endpoint 発火 + audit
- audit エントリ 1:1 mapping 「人間が deliberately query した」
- ノイズ削減、SOC review 価値向上

#### R4: autoDisabledSince malformed → HTTP 400

- `applyAutoDisabledSinceFilter` が malformed 時 IllegalArgumentException
- `ImportProfileDefinitionController.list` の catch block で 400 応答
- 既存 pass-through テストを「無効値 → 400」に書き換え
- 利点: typo を運用者が即検知、admin tab で誤認しない
- 軽微な breaking change (R4 は今後の RC5.x ではなく next major で
  追加するか議論あり): pass-through → 400 は old UI bug を弾く可能性
  あり。ただし RC5.3 UI は `Date().toISOString()` のみ送信なので
  実環境では影響なし

#### 設計原則 (RC5.3 まで継続)

- 既存 patch / view / Mango / migration / property / Java スケジューラ
  本体 (DelegatedCallContextFactory / IngestSchedulerService) は触らない
- API additive 範囲を維持 (R4 のみ strictness 強化)
- 既存 unit test 退行ゼロ

#### Change scope vs RC5.3 (正確な分類)

- **変更あり**: `ImportProfileDefinitionController` (R4 catch block)、
  `ConnectorGovernanceTab.tsx` (R3 button 化)、`ImportProfileSinceFilterTest`、
  4 i18n keys (ja+en)
- **無変更** (RC5.3 から byte 等価): scheduler / property / patch /
  view / Mango index / DB bootstrap / `ConnectorDefinitionController` /
  `DelegatedCallContextFactory` / `AuditOperation` / `DenialReason` /
  `serviceContext.xml`

#### Commit + tag 関係

- R3 + R4 feature commit: `6283afc96`
- pre-tag doc closure commit (status flip): `014939eeb`
- annotated tag `v3.1.1-RC5.4` target: `014939eeb`
  (annotated object: `d0a4a4f3d0f40482b0ca45cae47f75305235588b`)
- **post-tag supplemental docs (NOT in tag)**: `release/3.1.1-RC5.4`
  branch HEAD は tag 後に追加された doc-only コミットを含む
  (`REVIEW_PACKET.md`、本セクション、`RELEASE_NOTES.md` 追記、
  `externalIngest.ts` の JSDoc 訂正)。コード本体は tag が canonical、
  review-time のフレーミング情報は branch HEAD が canonical。
  対象ファイルの一覧は `REVIEW_PACKET.md` §3 参照。

#### Follow-up cumulative 状態

- **Remaining**:
  - **R1** (Low, ops): SOC tooling integration —
    `EXTERNAL_GOVERNANCE_SIMULATE` query / alert template
    (NemakiWare リポジトリ外、ops 領域)
  - **R5** (Low, audit accuracy): `IngestSchedulerService` 内 connector
    re-check 時の 2 度目 `resolveFolderId` が null を返すレース時、
    audit `denialReason` が `CONNECTOR_NOT_DELEGATED` で emit される
    (本来は `TARGET_FOLDER_UNRESOLVABLE` がより正確)。safety は
    維持、denial reason ラベルのみ不正確。小規模 refactor で解消可
- **Resolved**: R2 (RC5.3 `01fe84ac5`)、R3 (RC5.4 `6283afc96`)、
  R4 (RC5.4 `6283afc96`)

### RC20 / RC5.3 (2026-05-20) — W1 + W2 server-side governance/scalability (shipped)

ブランチ: `release/3.1.1-RC5.3` (off `v3.1.1-RC5.2` = `e18e020f6`)

RC5.1 で vNext 分類した W1 / W2 を統合 RC として実施。**Java 側追加あり**
(RC5.x で初): import-profiles list endpoint に since filter を追加、
governance に simulate-remove endpoint を新設。RC5 で確立した既存 API
の応答 shape は不変、追加のみ。

#### W1: import-profiles since filter (server-side)

- `GET /v1/admin/import-profiles?autoDisabledSince=ISO-8601` で
  `lastAutoDisabledAt >= since` を満たす profile のみ返却
- 既存 endpoint の応答 shape / 既存 query 動作は不変 (param 不在で
  従来通り)
- 大量 profile 環境で V6 client filter を server side に押し下げ、
  ネットワーク + render コスト削減
- 不正 ISO-8601 値は fail-safe (filter 無効化 + WARN ログ、API は
  400 でなく 200 維持で backward compat)

#### W2: governance simulate-remove endpoint

- `POST /v1/admin/connectors/by-principal/{principalId}/simulate-remove`
- Body: `{"repositoryId": "...", "expand": true|false, "removePrincipalIds": [...]}`
- Response: `{"lost": [...matches...], "kept": [...matches...]}`
  - lost: 全 matchedPrincipalIds が removePrincipalIds に含まれる → 削除で完全失う
  - kept: その他の matches (alternate route 残存)
- admin only (`requireAdmin()`)
- audit: `EXTERNAL_GOVERNANCE_SIMULATE` op、principalId + removeIds + lost count を記録
- 既存 V5/V7 client-computed ロジックと同じ「sole-route 検出」を server 化
- CLI / スクリプトから直接呼べる、UI も V7 が大量 matches の時に server に offload 可

#### 設計原則

- 既存 patch / view / Mango index / migration には触らない
- API additive only / no breaking change (W1 で optional query param 追加、W2 で新 endpoint 追加、既存 endpoint の必須 param / 応答 field は変更なし)
- default 安全側 (W1 default no-filter、W2 admin gate)
- 既存 unit test 退行ゼロ

### RC19 / RC5.2 (2026-05-20) — H1-H3 UI polish (shipped)

ブランチ: `release/3.1.1-RC5.2` (off `v3.1.1-RC5.1` = `cc1ac2b54`)

RC5.1 受け入れレビューで挙げた H1-H3 (低優先 UX polish) を独立 RC で
解消。RC5 で確立した API は additive only (breaking change なし)、
property / Java / DB は無触。

#### H1-H3 scope

- **H1**: V8 debounce setTimeout の unmount cleanup を `useEffect`
  return で追加 — single-tab admin UI で実害低いが best practice
- **H2**: V7 multi-removal Select に `maxCount` 上限 + Tooltip —
  全 expansion を選択した「lose everything」noise を防ぐ UX 保護
- **H3**: V6 window selector を「Custom...」option 含む拡張 →
  選択時 InputNumber に切替で任意 N 日入力可能

#### 設計原則 (RC5.1 から継続)

- 既存 patch / view / Mango index / migration / Java / property 無触
- API contract 不変
- UI のみ、i18n 追加可
- 既存 unit test 退行ゼロ

### RC18 / RC5.1 (2026-05-20) — G1-G3 polish + V6-V8 governance/scalability 拡張 + B1 fix

ブランチ: `release/3.1.1-RC5.1` (off `v3.1.1-RC5` = `f47d3273d`)

RC5 受け入れレビューで surface した post-RC5 follow-up (G1-G3) と
vNext 候補 (V6-V8) を同 RC で一括対応。RC5 で確立した API contract と
default-off opt-in property は不変。UI 改善 + 拡張 + scalability 中心。

#### G1-G3 polish

- **G1**: `ImportProfileManagementTab` の auto-disabled filter Switch
  state leak (count==0 で UI unmount → state 残置) を `useEffect` で
  reset
- **G2**: F3 AutoComplete の 1 ショット limit=500 ロードを廃止 →
  V8 に統合 (Select with virtual + onSearch debounce)
- **G3**: V5 simulate-removal Select で `GROUP_EVERYONE` 等の pseudo-
  principal を候補から除外

#### V6-V8 拡張

- **V6**: V4 に「過去 N 日に auto-disable」filter 追加 — 新規発生と
  legacy 蓄積の triage を簡単に
- **V7**: V5 を multi-principal removal シミュレーション化 — 「group
  A AND group B から外したら何を失う?」
- **V8 (+G2)**: `ConnectorGovernanceTab` principal picker を Ant
  Design `Select` (virtual scroll + `onSearch` debounce + server-side
  filter) に置換 — 10k+ principal directory 対応

#### 設計原則 (RC5 から継続)

- 既存 patch / view / Mango index / migration には触らない
- API contract 不変 (governance API / profile API 既存応答 shape は維持)
- default 安全側継続
- 既存 unit test 退行ゼロ

#### B1 fix (RC5.1 受け入れレビュー後の regression 修正)

V8 が AutoComplete → `Select` 置換で free-text 入力 path を失った
regression を解消。`Select` は options からのみ submit 可能で、pseudo-
principal (例: `anyone`)、外部 IdP ID、未 sync の new principal は
typed-text で submit できなかった。`AutoComplete` に revert しつつ V8
の server-side `onSearch` + 300 ms debounce + 50件 fetch は維持。
virtual scrolling は明示的に trade-off (50件は DOM cost 軽微で不要)。

V5/V7 multi simulate-removal Select は別 control なので影響なし。

Commit: `fc124aea1` (推奨 tag 対象)

#### Post-RC5.1 follow-up (RC5.2 候補、release blocker なし)

- **H1** (Low): V8 debounce timer の unmount cleanup — 単一 admin tab で実害低、best practice
- **H2** (Low, UX): V7 multi-removal Select に最大選択件数制限 — 全 expansion 一括選択で「全失う」noise 回避
- **H3** (Low, UX): V6 window に「カスタム日数」入力欄 — 現在は 1/7/30 固定

#### vNext (RC5.2 ではなく別 scope)

- **W1**: V6 server-side filter 化 (大量 profile 環境向け、現状 client filter)
- **W2**: V7 server-side simulate endpoint — CLI/スクリプトから同じロジックを呼べる

### RC17 / RC5 (2026-05-19) — v2: scheduled delegated profiles + connector governance view

ブランチ: `release/3.1.1-RC5` (off `v3.1.1-RC4.1` = `572aad18b`)

v1 (RC3) で意図的に v2 へ繰越した 2 件を独立 RC で対応完了。設計参照:
- `docs/design/connector-delegation.md` §12.1 (scheduled delegated profile)
- 同 §12.3 (governance view)

#### §12.1 Scheduled delegated profiles (shipped)

- 新クラス `DelegatedCallContextFactory` (`SyntheticCallContext` 内包)
  - `createdByUserId` から `UserItem` 引き当て、毎 tick 新規 lookup (cache なし)
  - `UserItem.isAdmin()` が true でも合成 context は `IS_ADMIN=false` を強制 (delegation gate は必ず走る)
  - lookup 例外は inactive 扱い (fail-shut)
- `IngestSchedulerService.prepareDelegatedTick`: tick ごとに 5 段ガード
  1. `nemakiware.ingest.delegated.schedulerEnabled=true` (operator opt-in)
  2. 必要 wiring (`DelegatedCallContextFactory`, `IngestAuthorizationService`)
  3. `createdByUserId` 必須
  4. `UserItem` 引き当て可能 (== "active")
  5. creator がまだ `cmis:all` を target folder に保持
- connector 解決後の二段目チェック: `canUseConnectorForDelegatedProfileAsUser` を再評価
- Creator deactivation:
  - default fail-shut + `CREATOR_USER_INACTIVE` audit
  - `nemakiware.ingest.delegated.autoDisableInactiveOwners=true` opt-in で N 回連続失敗後 `enabled=false`
  - 閾値は `nemakiware.ingest.delegated.inactiveOwnerFailureThreshold` (default 3)
  - active user 復帰で streak リセット (一時障害が誤検知にならない)
- `ImportProfileDefinitionController`: `SCHEDULER_REQUIRES_ADMIN` ガードを property 連動化
  - 同 property OFF → v1 動作 (refuse)
  - 同 property ON → 非 admin の `schedulerEnabled=true` を受理、scheduler が per-tick で再評価
- 新 `DenialReason`: `CREATOR_USER_INACTIVE`, `CREATOR_CMIS_ALL_LOST`, `DELEGATED_SCHEDULING_DISABLED`
- audit `EXTERNAL_INGEST_FAILED.details`: `scheduled`, `delegated`, `actorUserId`, `creatorUserId`, `creatorActive`, `profileId`, `connectorId`, `targetFolderId`, `denialReason`
- 既存 RC4 動作 (property OFF 時の WARN-once + skip) は `IngestSchedulerDelegationSkipTest` で退行ピン

#### §12.3 Connector governance view (shipped)

- 新 endpoint: `GET /v1/admin/connectors/by-principal/{principalId}?repositoryId={repo}&expand={true|false}` (admin only)
- 指定 principal (user ID または group ID) が `allowedPrincipalIds` で参照される connector を一覧
- `expand=true` で `IngestAuthorizationService.expandPrincipals` 経由で group 経由マッチも含む (runtime gate と同じ展開ロジック)
- match entry に `matchType`: `direct` / `group` / `direct+group` + `matchedPrincipalIds` を返却 (冗長 grant 検出に有用)
- 「group X を消すと何が壊れる?」「user Y は何の credential 経由でデータにアクセスできる?」を operator が即答できる

#### 新規 properties (`nemakiware.properties`)

```properties
# v2 §12.1 operator opt-in
nemakiware.ingest.delegated.schedulerEnabled=false
nemakiware.ingest.delegated.autoDisableInactiveOwners=false
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```

#### テスト

- 新規 4 ファイル / 36 テスト (`DelegatedCallContextFactoryTest`, `IngestSchedulerDelegatedRunTest`, `ImportProfileSchedulerGateTest`, `ConnectorByPrincipalGovernanceTest`) + 既存 `IngestSchedulerDelegationSkipTest` (5) で計 41 テスト全 PASS
- ingest delegation 関連の関連既存テスト合計 133 テスト退行なし

#### V1-V3 拡張 (RC5 受け入れレビュー後の vNext 取り込み)

§12.1 / §12.3 受け入れ完了後、operator UX を高める 3 件を同一 RC5 内に追加。再レビュー対象。

##### V1: auto-disable 後の re-enable handshake
- `ImportProfileDefinition` に `lastAutoDisabledAt` (ISO-8601) / `lastAutoDisabledReason` (String) を追加
- `IngestSchedulerService.handleInactiveCreator` が auto-disable 時に両 field を書き込み
  - reason = `CREATOR_USER_INACTIVE: creator '{user}' inactive for {N} consecutive ticks`
- `ImportProfileDefinitionController.update`:
  - 既存 `enabled=false` + marker あり → 新 `enabled=true` 受信 → marker クリア + 新 audit `clearedAutoDisableMarker=true`
  - 無関係な PUT (例: rateLimitRpm のみ flip) で payload に marker 無し → 既存 marker を保持 (audit trail 残置)
- UI `ImportProfileManagementTab`: `enabled=false` 行に「auto-disabled」橙 Tag + Tooltip で reason 表示
- audit 追跡: scheduler が disable した event と admin が re-enable した event の両方が SOC で見える

##### V2: governance view に `principalType` 追加
- `ConnectorDefinitionController.listByPrincipal` 応答 top-level に `principalType` (`USER` / `GROUP` / `UNKNOWN`)
- `PrincipalService.getUserById` → `getGroupById` で解決 (両方 null = UNKNOWN, lookup 失敗も UNKNOWN, bean 未注入も UNKNOWN)
- GROUP の場合は `expand=true` でも group 展開 skip (NemakiWare の group は nest しない仕様、PrincipalService impl の挙動依存を回避)
- UI badge: USER=geekblue / GROUP=purple / UNKNOWN=default + Tooltip
- 既存の matchType (direct / group / direct+group) の挙動は変更なし、principal type は orthogonal な情報

##### V3: governance UI dashboard
- 新 tab `ConnectorGovernanceTab.tsx` (admin only, `key='connector-governance'`)
- Form: principalId 入力 + repositoryId (props) + expand toggle (default true)
- 検索結果: 4 列 table (Connector / Source / Status / Match)
- matchType badge 色分け: direct=green / via group=blue / direct+group=orange
- ヘッダーカード: queried principal + principalType badge + expanded principal IDs list
- 0 件マッチ時は warning Alert
- i18n: ja/en に `tabs.connectorGovernance` (短縮、UI tab name のみ)、他は defaultValue 経由
- 新 TS API: `getConnectorsByPrincipal()` + interface `ConnectorByPrincipalResponse` / `ConnectorPrincipalMatch`

##### V1-V3 テスト追加 (8 新規)
- `IngestSchedulerDelegatedRunTest`: V1 marker 書き込み (threshold=2 で auto-disable + 両 field set + reason 内容検証)
- `ImportProfileSchedulerGateTest`: V1 marker クリア (admin re-enable) + V1 marker 保持 (無関係 PUT)
- `ConnectorByPrincipalGovernanceTest`: V2 principalType (USER / GROUP / UNKNOWN-no-resolve / UNKNOWN-no-bean) + GROUP の expand skip 検証

#### F1-V5 follow-up 取り込み (V1-V3 acceptance 後)

V1-V3 受け入れレビューで挙げた follow-up (F1-F3 low + V4-V5 vNext) を同 RC5 cycle 内に追加。

- **F1**: 非 admin payload で marker フィールド spoof を防止。`update()` の handshake 前 + `enforceDelegationOnCreate` 末尾で `lastAutoDisabledAt` / `lastAutoDisabledReason` を null に強制。admin は data repair 用に書き込み可 (live 検証済)。
- **F2**: `connectorGovernance` top-level i18n section を ja/en に明示追加 (~17 keys)、`importProfileManagement.autoDisabled*` 4 keys 追加。defaultValue fallback 廃止 → 翻訳更新時のキャッチが確実に。
- **F3**: `ConnectorGovernanceTab` の principalId input を Input → `AutoComplete` 化。`CMISService.getUsers/getGroups` で repo の users + groups を mount 時に取得 (limit 500)、`{id} · {label} (USER/GROUP)` 形式で suggest。pseudo-principal / 外部 IdP 由来は free-text 継続。lookup 失敗は AutoComplete 候補を空にして fail-soft。
- **V4**: `ImportProfileManagementTab` に「自動無効化のみ表示」filter Switch (auto-disabled が 0 件のとき非表示)、`{count}` Tag、`Alert` banner を追加。Admin の triage 用途。
- **V5**: `ConnectorGovernanceTab` に「principal X を expanded set から外したら失う connector」drill-down を追加。`expandedPrincipals - {queriedPrincipal}` から `Select` で principal を選び、client side で `matchedPrincipalIds.every(p => p === X)` の match のみ表示。server 側追加なし (既存 API のみで成立)。

##### F1-V5 テスト追加 (3 新規 → ingest 計 136 テスト)
- `ImportProfileSchedulerGateTest`: F1 非 admin update spoof / F1 非 admin create spoof / F1 admin data repair (admin は marker 書込可)

#### Post-RC5 follow-up (low priority — RC5.1 候補)

F1-V5 受け入れ再々レビューで surface。release blocker ではない:

- **G1** (Low, UX): V4 filter ON 状態で全 auto-disabled を re-enable → count=0 で filter Switch unmount + 内部 state 残置 → 表 empty で confusing。`useEffect` で count==0 時 `setOnlyAutoDisabled(false)` 3 行 fix。
- **G2** (Low, scale): F3 AutoComplete が `getUsers/getGroups` を `limit=500` で 1 ショット。10k+ principal 環境ではページング or server-side filter 化が必要 (現状 single-tenant 想定)。
- **G3** (Low, UX): V5 simulate-removal Select に `GROUP_EVERYONE` が含まれる。removal シミュレートしても通常 0 lost で害なし、ただし noise。pseudo-principal を除外する polish 候補。

#### vNext (RC5.1 ではなく別 scope)

- **V6**: V4 拡張 — 「過去 N 日に auto-disable された profile」filter
- **V7**: V5 拡張 — 複数 principal 同時 removal シミュレーション
- **V8**: F3 AutoComplete の virtual scroll + lazy load (大規模 principal directory 対応)

### RC16 / RC4 (2026-05-18) — パッチ機構の構造改修

ブランチ: `release/3.1.1-RC4` (off RC3 HEAD `9bdfb8383`)

RC3 のマイグレーション静的レビューで pre-existing follow-up として記録した R1-R4 を独立 RC で完結。RC3 機能には触れない。

#### 実装内容

- **R4** (Low): `Patch_StandardCmisViews` の重複登録削除 (`patchService.patchList` から除外、`cmisPostInitializer` 側に統一)。起動ログの "Applying patch: standard-cmis-views" 重複が解消
- **R3** (Medium): `REQUIRED_VIEWS_MAIN = 38` ハードコード → 起動時に `bedroom_init.dump` / `archive_init.dump` から view name set をパースして subset 比較。dump が読めない場合は legacy int threshold にフォールバック。完全性チェックは missing view name の集合を WARN で出力するので運用時に直接対処可能
- **R1** (High): patch fallback asymmetry 解消
  - 欠落していた 8 patches に top-level bean id 付与 (`Patch_IngestRelationshipTypes`, `Patch_BusinessRecordMetadataSecondaryType`, `Patch_ChatContextMetadataSecondaryType`, `Patch_MessageMetadataSecondaryType`, `Patch_NoteMetadataSecondaryType`, `Patch_ExternalIntegrationSourceFields`, `Patch_DefaultCloudDriveConnectorProfile`, `Patch_PurviewStateMigration`)
  - `NemakiPatchInitializationListener` を `WebApplicationContext.getBeansOfType(AbstractNemakiPatch.class)` 自動収集化。ハードコード配列廃止 → 短い `ORDERED_SEED_PATCHES` (8 件) が dependency-sensitive な順序を維持、残りは alphabetical
  - 例外 / `apply()=false` でも残りの patch は実行継続
- **R2** (Medium): `Patch_IngestMangoIndexes` 新設、`nemaki_conf` に 7 compound index を起動時登録: `(type, connectorId)`, `(type, sourceArchetype)`, `(type, sourceSystem, sourceArchetype, enabled)`, `(type, profileId)`, `(type, repositoryId)`, `(type, jobId)`, `(type, dlqEntryId)`。Cloudant 側で idempotent (`result="exists"` 返却)。失敗時は `RuntimeException` で PatchHistory 未マークにして次回 retry

#### 検証

- 17 新規 unit test pass (5 + 6 + 6)
- 169 ingest+init+patch 関連 test pass
- live deployment で `Patch_IngestMangoIndexes` が 7 indexes 全て created (CouchDB `_index` 直接確認)
- RC3 API E2E 21 / 21 pass (RC4 patches 込みでデプロイ)

#### 設計原則 (守られた)

- 既存 patch / view / data に手を入れない (追加のみ)
- すべて idempotent (PatchHistory + Cloudant `result="exists"`)
- 既存 test 退行ゼロ

#### RC4.1 (2026-05-19) — 受け入れレビュー F1-F3 対応

Commit 構成:
- **RC4 baseline**: `cc63d960e` (R1-R4 patch-machinery cleanup)
- **RC4.1 code hardening**: `7823b60f7` (F1-F3 fix。6 files, +229/-16 行)
- **Final tag target**: 上記 + release-review の doc-only fix を含む。詳細は `git log v3.1.1-RC4.1 --oneline` 参照
- **Release tag**: `v3.1.1-RC4.1`

acceptance review で挙がった 5 件 (F1-F5) のうち F1-F3 を小規模修正で取込。F4/F5 は documentation-only:

- **F1** (Medium): `NemakiPatchInitializationListener.ORDERED_SEED_PATCHES` に `patch_ExternalIntegrationSecondaryType` + `patch_ExternalIntegrationSourceFields` を追加。fallback path での依存順序を alphabetical 偶然依存から explicit seed に変更。新 test で hostile な間に割り込む patch を入れても依存順守を確認
- **F2** (Low): `Patch_IngestMangoIndexes` の dead index `idx_type_dlqEntryId` を `idx_type_dlqId` に修正 (実フィールド名と一致)。既存 dead index は RELEASE_NOTES に手動 cleanup コマンド記録、自動削除はしない。新 test で `INDEXES` リストを reflection で検証
- **F3** (Low): log を `processed=N, failed=M (out of K)` 形式に簡略化。`failed > 0` → `RuntimeException` の failure 検知は維持
- **F4**: 修正なし。`applySystemPatch()` の毎起動再実行は cheap (~16ms / 7 indexes)、Cloudant idempotent
- **F5**: 修正なし。`archive_init.dump` (14 views) と旧 threshold (8) の乖離は merge で self-heal

検証: 171 unit tests pass (2 new), live restart で `processed=7, failed=0` 確認

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

- 非 admin の scheduled profile (詳細設計は `docs/design/connector-delegation.md` §12.1): CallContext 合成 + per-tick ACL 再評価 + creator deactivation policy + 5 つの新 DenialReason + ~600-1000 LOC + ~30 unit tests。独立した RC サイクルで対応すべき規模
- Connector group-membership view (「group X が使える connector は何か」のガバナンス用)

#### 本 RC で対応済 (元 v2 候補)

- ~~Admin-owned → delegated 移行 / 移管ツール~~ → `POST /v1/admin/import-profiles/{id}/ownership` で実装。`mode=delegated|admin`、新 owner の cmis:all + connector 委譲を per-user で再評価、`details.transferTo` を audit。delegated mode では `defaultConnectorId ∈ allowedConnectorIds` も検証、全失敗パスで `auditTransferDenial` 経由 audit
- ~~Connector PUT partial payload で scope clobber~~ → list field omit (null) は existing 保持、explicit `[]` は clear として尊重
- ~~フォルダ選択 UI~~ → `FolderPickerModal` で実装。CMIS Browser Binding の lazy expand + 選択時に `/summary` で cmis:all を probe、403 で Confirm disable。admin にも同じ picker を提供 (informational)

#### マイグレーション安全性 (RC3 静的レビュー結論)

詳細: `docs/design/connector-delegation.md` §9.5 / `RELEASE_NOTES.md` "Migration safety (RC3)"

- RC3 は新 CouchDB view / patch / type definition を追加しない。dump file 変更なし
- 追加永続化は JSON field のみ。`@JsonIgnoreProperties(ignoreUnknown=true)` + プリミティブ default で pre-RC3 record は安全側 (`delegated=false`) で読まれる
- mango `_find` selector は新 field を条件にしないため既存・新規 record の検索互換性は維持
- 不整合 record は読み込みでは落とさず runtime gate で fail-closed

#### RC3 範囲外の follow-up (静的レビュー副産物、独立 PR で対応)

| ID | 重要度 | 概要 |
|---|---|---|
| R1 | 🔴 | `NemakiPatchInitializationListener` の fallback patchBeanNames (23) と `CMISPostInitializer.cmisPatchList` (28) が非対称。9 patches 欠落、うち 8 patches は top-level bean id 不在 |
| R2 | 🟠 | `nemaki_conf` の mango `_find` に index 未登録。10k+ records でスケール問題 |
| R3 | 🟠 | `REQUIRED_VIEWS_MAIN = 38` がハードコード、dump は 40 views で乖離 |
| R4 | 🟡 | `Patch_StandardCmisViews` が cmisPostInitializer / patchService に二重登録 (PatchHistory で dedupe) |

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
