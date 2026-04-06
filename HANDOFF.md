# External Ingestion / Lineage — 実装完了状態

最終更新: 2026-04-04
対象ブランチ: `release/3.1.1-RC2`

## 1. 実装状態サマリ

設計書 `docs/design/external-ingestion-lineage-design.md` の Phase 1-6 および全 concrete adapter の実装が完了した。

## 2. 実装済みコンポーネント

### 2.1 抽象レイヤ

- `SourceArchetype` enum (5類型: FILE_SHARE, COMPOUND_NOTE, CHAT_CONTEXT, BUSINESS_RECORD, MESSAGE_CONTEXT)
- `ConnectorDefinition` / `ImportProfileDefinition` (CouchDB CRUD + admin REST API)
- `ExternalIngestRequest` / `ExternalIngestResult` (canonical DTO)
- `CanonicalImportService` (パイプライン: バリデーション → dedupe → 作成/版更新 → メタデータ → lineage)
- `ExternalSourceUri` (archetype 別 URI 構築 + URI エンコーディング)

### 2.2 Secondary Types

| Type ID | Properties | 用途 |
|---|---|---|
| `nemaki:externalIntegration` + sourceFields | sourceArchetype, sourceSystem, sourceObjectId 等 11 | 共通 source tracking |
| `nemaki:messageMetadata` | internetMessageId, mailSubject, mailFrom, mailTo 等 11 | メール envelope |
| `nemaki:noteMetadata` | notePageId, notePageUrl, noteWorkspaceId 等 8 | ノート/ページ |
| `nemaki:businessRecordMetadata` | recordType, recordId, recordStatus 等 8 | CRM/ERP レコード |
| `nemaki:chatContextMetadata` | chatChannelId, chatThreadId, chatParticipants 等 8 | チャット会話 |

### 2.3 Import Flows

| Flow | sourceObjectType 自動検出 | 特徴 |
|---|---|---|
| `execute()` | default | 標準パイプライン |
| `executeMailImport()` | .eml / message | MIME パース → 本文 + 添付分離 → messageMetadata → direct relationship |
| `executeNoteImport()` | page | noteMetadata → 添付 base64 デコード → direct relationship |
| `executeBusinessRecordImport()` | record | businessRecordMetadata 自動付与 |
| `executeChatContextImport()` | chat_message / thread | chatContextMetadata 自動付与 |

### 2.4 Concrete Adapters (11種)

| Adapter | sourceSystem | API | スケジューラ |
|---|---|---|---|
| IMAP | `imap` | Jakarta Mail IMAP/IMAPS | ✅ UIDVALIDITY checkpoint + IDLE |
| Gmail | `gmail_mail` | Gmail API v1 | ✅ date checkpoint |
| M365 Mail | `m365_mail` | Graph API | ✅ receivedDateTime checkpoint |
| Notion | `notion` | Notion API v2022-06-28 | ✅ last_edited_time checkpoint |
| Salesforce | `salesforce` | REST v59.0 | ✅ LastModifiedDate checkpoint |
| Slack | `slack` | Web API | ✅ ts checkpoint |
| Teams | `teams` | Graph API v1.0 | ✅ createdDateTime checkpoint |
| Mattermost | `mattermost` | REST v4 | ✅ createAt checkpoint |
| Chatwork | `chatwork` | Chatwork API v2 | ✅ messageId checkpoint |
| Box | `box` | Box Content API v2.0 | ✅ modified_at checkpoint |
| Dropbox | `dropbox` | Dropbox API v2 | ✅ server_modified checkpoint |

### 2.5 Profile フィールド Runtime Enforce

| フィールド | 状態 |
|---|---|
| targetFolderId / targetFolderPath | ✅ (path は getObjectByPath で解決) |
| defaultObjectTypeId | ✅ |
| allowedArchetypes / allowedConnectorIds | ✅ |
| dedupePolicy (skip/new_version/replace/parent_context_changed/replace_relationships) | ✅ (source-identity dedupe + content hash + context 比較) |
| updatePolicy (version_up_on_content_change / update_metadata_only) | ✅ (SHA-256 content hash 比較) |
| versioningPolicy (major/minor/none) | ✅ |
| secondaryTypeIds | ✅ (profile 定義の型を自動付与) |
| retentionDays | ✅ (cmis:rm_clientMgtRetention 付与) |
| relationshipPolicy | ✅ (parentObjectId → cmis:relationship) |
| aclSyncPolicy | ✅ (CMIS inherit_from_folder がデフォルト) |
| schedulerEnabled | ✅ (IngestSchedulerService) |
| defaultClassification | ✅ (nemaki:classificationInfo 自動付与) |

### 2.6 管理 UI

| タブ | 機能 |
|---|---|
| コネクタ | CRUD (sourceArchetype, sourceSystem, authType, endpoint, tenantId) |
| インポートプロファイル | CRUD (targetFolder, dedupePolicy, versioningPolicy, secondaryTypeIds, allowedConnectorIds) |
| 手動インポート | connector/profile 選択 → ファイルアップロード → archetype 別フィールド → dry-run → 実行結果 |

### 2.7 Lineage

- 17 LineageProcessType 値
- ExternalSourceUri (全 archetype + UIDVALIDITY)
- PurviewLineageSink 全対応

### 2.8 Scheduler

- IngestSchedulerService: 定期ポーリング (5分間隔) + 手動 trigger API
- executeFetch(): archetype + sourceSystem で全 10 adapter に自動ルーティング
- 全 adapter にチェックポイント永続化 + per-request スロットル (rateLimitRpm)
- IMAP checkpoint (UIDVALIDITY + UID, partial failure aware) + IMAP IDLE 監視

### 2.9 Webhook / Event-Driven

- IngestWebhookController: `POST /v1/ingest-webhook/{connectorId}`
- Slack Events API (url_verification + event_callback, HMAC-SHA256 署名検証)
- Microsoft Graph changeNotification (validationToken + clientState 検証)
- Generic webhook (X-Webhook-Signature HMAC 検証)
- Graph subscription 作成/削除 API (`POST/DELETE /subscribe`)
- 複数プロファイル同時フェッチ対応 (many-to-many connector/profile)

### 2.10 Job History / Dead-Letter Queue

- IngestJobRecord: スケジューラ各実行のジョブ履歴 (RUNNING/COMPLETED/FAILED/PARTIAL, skipped 集計)
- IngestDeadLetterRecord: 失敗リクエストの CouchDB attachment 付き永続化
- DLQ リトライ: archetype 別フロー自動ルーティング + skipped 結果での自動解除
- admin REST API: `GET /v1/admin/ingest/jobs`, `/dlq`, `POST /dlq/{id}/retry`, `DELETE /dlq/{id}`
- IngestJobsTab UI: ジョブ履歴テーブル + DLQ 管理 (リトライ/削除)
- SchedulerStatusTab UI: scheduler-enabled プロファイル一覧 + 手動トリガー + IDLE 開始/停止

### 2.11 Typed Relationships

- `nemaki:hasAttachment` — メッセージ/ページと抽出添付ファイルのリンク
- `nemaki:attachedToRecord` — 添付ファイルとビジネスレコードのリンク
- `nemaki:derivedFromContext` — ドキュメントと会話/スレッドコンテキストのリンク
- Patch_IngestRelationshipTypes で cmis:relationship サブタイプとして定義
- フォールバック: カスタム type 未登録時は汎用 cmis:relationship を使用

### 2.12 追加の Runtime Enforce

- defaultClassification: nemaki:classificationInfo 自動付与
- aclSyncPolicy: inherit_from_folder (デフォルト) / none (継承切断) / copy_from_source (sourceAcl からの ACL 適用)
- preserveOriginalEml: 本文抽出 + raw .eml 原本を別ドキュメントとして保存
- content hash (SHA-256): 初回取込み時に計算・永続化、再インポート時の変更検出
- defaultProfile: auto-resolve 時の決定論的プロファイル選択
- Audit event: EXTERNAL_INGEST / EXTERNAL_INGEST_FAILED 操作ログ

## 3. 設計書の未決定事項の解決状態

| 未決定事項 | 解決 |
|---|---|
| connector/profile を UI から管理するか | ✅ UI タブで管理 |
| page 本文の保存形式 | HTML (Notion adapter の blockToHtml) |
| externalIntegration を拡張するか | ✅ archetype 別 secondary type を追加 |
| chat context を別文書化するか | ✅ 添付は別文書 + chatContextMetadata |
| mail 原本を .eml として保存するか | ✅ preserveOriginalEml プロファイルオプション (raw .eml を別ドキュメント保存) |
| mail stable key | UIDVALIDITY:UID (IMAP), Gmail message ID, Graph message ID |
| ImportProfileDefinition の persisted-only 項目 | ✅ 全フィールド runtime enforce |

## 4. テスト

### Ingest パイプライン テスト
- Java ユニット + WireMock 契約テスト: 97 件 / 0 failures
  - Adapter 契約テスト: 80 件 (Chatwork 14, Teams 10, Mattermost 10, Slack 10, Notion 9, Salesforce 10, Box/Dropbox 4, MailMessageParser 3)
  - パイプラインロジック: 17 件 (CanonicalImportServiceTest 17)
- Playwright API smoke: 46 件 (ingest-pipeline 13, external-ingest-api 16, webhook-api 17)

### 全体テスト
- TCK: 38/38
- QA: 94/94
- Playwright E2E: 879+ passed

## 5. 参照すべきファイル

### 抽象レイヤ
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/SourceArchetype.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinition.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ImportProfileDefinition.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalIngestRequest.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ExternalSourceUri.java`

### Adapters
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/ImapConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/GmailConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/M365MailConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/mail/MailMessageParser.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/note/NotionConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/record/SalesforceConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/chat/SlackConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/chat/TeamsConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/chat/MattermostConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/chat/ChatworkConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/fileshare/BoxConnectorAdapter.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/fileshare/DropboxConnectorAdapter.java`

### Scheduler / Webhook / DLQ
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestWebhookController.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestJobService.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java`

### Patches
- `core/src/main/java/jp/aegif/nemaki/patch/Patch_IngestRelationshipTypes.java`

### UI
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorManagementTab.tsx`
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ImportProfileManagementTab.tsx`
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ManualIngestTab.tsx`
- `core/src/main/webapp/ui/src/components/IntegrationSettings/IngestJobsTab.tsx`
- `core/src/main/webapp/ui/src/components/IntegrationSettings/SchedulerStatusTab.tsx`
- `core/src/main/webapp/ui/src/services/externalIngest.ts`
