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

```bash
cp core/target/core.war docker/core/core.war
cd docker
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

## セキュリティステータス (2026-03-16)

- npm脆弱性: 0件 (dompurify 3.3.2 overrides適用、immutable 3.8.3 へ更新)
- Maven要注意: netty 4.1.97 (odata-server-core経由、CVE-2025-24970/58056)、logback 1.4.14 (CVE-2025-11226/CVE-2026-1225)
- PDF.js CVE-2024-4367: 対応済み (react-pdf 10.0.1)
- エクスポートACLリーク: 対応済み (CAN_GET_ACL権限チェック追加)
- アーカイブDAO例外伝播: 対応済み (null返却→CmisRuntimeException)
- Webhook REST API: CMIS権限チェックに移行済み (admin限定→CAN_GET/UPDATE_PROPERTIES)
- ContentChanges 削除済み型フォールバック: 対応済み (JSONConverter型解決エラー防止)
- SAML署名ラッピング攻撃: 対応済み (Reference URI DOM解決 + duplicate-ID防御)
- SAML DEFLATE DoS: 対応済み (10MB上限 + ByteArrayOutputStream)
- Setup Wizard設定上書き防止: 対応済み (ハイドレーションゲート + エラーリトライ)

---

## 現在のバージョン

**3.1.1** (2026-04-02)

### RC12 (2026-04-05)
- External Ingestion Phase 4 完成:
  - Concrete Adapters 10種 (+Box, Dropbox)
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
