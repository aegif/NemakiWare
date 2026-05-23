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
