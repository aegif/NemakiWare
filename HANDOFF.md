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

### 2.4 Concrete Adapters (8種)

| Adapter | sourceSystem | API | スケジューラ |
|---|---|---|---|
| IMAP | `imap` | Jakarta Mail IMAP/IMAPS | ✅ UIDVALIDITY checkpoint |
| Gmail | `gmail_mail` | Gmail API v1 | ✅ |
| M365 Mail | `m365_mail` | Graph API | ✅ |
| Notion | `notion` | Notion API v2022-06-28 | ✅ ページネーション対応 |
| Salesforce | `salesforce` | REST v59.0 | ✅ |
| Slack | `slack` | Web API | ✅ |
| Teams | `teams` | Graph API v1.0 | ✅ |
| Mattermost | `mattermost` | REST v4 | ✅ |

### 2.5 Profile フィールド Runtime Enforce

| フィールド | 状態 |
|---|---|
| targetFolderId / targetFolderPath | ✅ (path は getObjectByPath で解決) |
| defaultObjectTypeId | ✅ |
| allowedArchetypes / allowedConnectorIds | ✅ |
| dedupePolicy (skip/new_version/replace) | ✅ (source-identity dedupe) |
| updatePolicy (version_up_on_content_change / update_metadata_only) | ✅ (SHA-256 content hash 比較) |
| versioningPolicy (major/minor/none) | ✅ |
| secondaryTypeIds | ✅ (profile 定義の型を自動付与) |
| retentionDays | ✅ (cmis:rm_clientMgtRetention 付与) |
| relationshipPolicy | ✅ (parentObjectId → cmis:relationship) |
| aclSyncPolicy | ✅ (CMIS inherit_from_folder がデフォルト) |
| schedulerEnabled | ✅ (IngestSchedulerService) |
| defaultClassification | ⚠️ (分類スキーマ定義待ち) |

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
- executeFetch(): archetype + sourceSystem で全 8 adapter に自動ルーティング
- IMAP checkpoint (UIDVALIDITY + UID, partial failure aware)

## 3. 設計書の未決定事項の解決状態

| 未決定事項 | 解決 |
|---|---|
| connector/profile を UI から管理するか | ✅ UI タブで管理 |
| page 本文の保存形式 | HTML (Notion adapter の blockToHtml) |
| externalIntegration を拡張するか | ✅ archetype 別 secondary type を追加 |
| chat context を別文書化するか | ✅ 添付は別文書 + chatContextMetadata |
| mail 原本を .eml として保存するか | 本文抽出 + 添付分離 (.eml 原本保持は将来拡張) |
| mail stable key | UIDVALIDITY:UID (IMAP), Gmail message ID, Graph message ID |
| ImportProfileDefinition の persisted-only 項目 | ✅ 全フィールド runtime enforce (defaultClassification 除く) |

## 4. テスト

- ユニットテスト: 2,523 件 / 0 failures
- TCK: 38/38
- E2E: 864+ passed

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

### Scheduler
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java`
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerController.java`

### UI
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorManagementTab.tsx`
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ImportProfileManagementTab.tsx`
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ManualIngestTab.tsx`
- `core/src/main/webapp/ui/src/services/externalIngest.ts`
