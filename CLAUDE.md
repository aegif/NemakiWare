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

**注意**: `/core/browser/...` (CMIS Browser Binding) はCSRF検証なし。`/core/api/v1/...` (Spring MVC) は `CsrfInterceptor` (HandlerInterceptor) で検証される（Webhook receiver パスを除く）。

**Tomcat RemoteIpValve**: `docker/core/server.xml` に設定済み。信頼proxyからのX-Forwarded-Proto/Host/PortをservletAPI値に反映する。アプリ側ではforwardedヘッダーを自前パースしない。

---

## セキュリティステータス (2026-06-02)

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

**3.2.3** (2026-07-07、`release/3.2.3` → master) — ネストグループ ACL 解決 +
RAG チャンク消失の 2 バグ修正 + `tools/test-env` 同梱 (下記 3.2.3 節)。
バージョン表記は 3.2.1 と同じ箇所を bump: 5 reactor pom + core 内部依存座標、
UI `package.json` / `package-lock.json` / `Layout.tsx` フォールバック、
`repositories-default.yml` の `product.version`。Setup の serverVersion は
`version.properties=${project.version}` 経由で pom 追従。

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
