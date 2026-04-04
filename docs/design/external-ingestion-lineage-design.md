# NemakiWare 外部取込・Lineage 拡張設計

最終更新: 2026-04-02  
対象ブランチ: `release/3.1.1-RC2`

## 1. 目的

NemakiWare に対して、外部システム由来の文書・本文・添付・関連文脈を安全に取り込み、以下を同時に実現する。

- NemakiWare 内で正式保管対象として扱えること
- どこから来たデータかを来歴として保持できること
- 取り込み時の元レコード / 元ページ / 元会話 / 元メールとの関係を辿れること
- Purview / Dataplex など外部ガバナンス基盤に必要最小限の lineage を連携できること
- UI 手動取込、API 取込、定期同期 / イベント駆動取込の 3 系統を同じ内部パイプラインで扱えること

本設計は、今後の具体的な Notion / Box / Dropbox / Slack / Salesforce / SAP / IMAP / Gmail / Microsoft 365 Mail など個別コネクタ実装に先立って、抽象モデルと内部責務を固定するためのものである。

## 2. 現状の土台

2026-04-02 時点で、NemakiWare は次の土台を持っている。

- `nemaki:externalIntegration` secondary type
  - `nemaki:externalContext`
  - `nemaki:externalContextUpdatedAt`
  - `nemaki:externalSourceType`
  - `nemaki:externalSourceId`
- `nemaki:cloudDriveMetadata` secondary type
  - `nemaki:cloudProvider`
  - `nemaki:cloudFileId`
  - `nemaki:cloudFileUrl`
  - `nemaki:cloudLastSyncedAt`
  - `nemaki:cloudComments`
- lineage event の canonical モデル
  - `inputs`
  - `outputs`
  - `processType`
  - `snapshotAttributes`
  - multi-target publish (`purview`, `atlas`, `dataplex`)
- cloud import / push / pull に対する external context 保存
- cloud import / push / pull に対する lineage emit

つまり、外部由来文書の「保管」と「来歴」の最初の器はすでに存在する。ただし、現在は cloud drive を中心に実装されており、一般化された external ingest モデルにはまだなっていない。

### 2.1 現在の実装段階

2026-04-02 時点で、external ingest の backend 足場はすでに導入済みである。

- `ConnectorDefinition`
- `ImportProfileDefinition`
- `ExternalIngestRequest` / `ExternalIngestResult`
- `CanonicalImportService`
- admin CRUD API for connector / import profile
- canonical ingest API

一方で、実装はまだ Phase 1 の途中段階であり、次の制約が残っている。

- connector / import profile / manual ingest の UI は未実装
- `ImportProfileDefinition` に定義済みの項目の多くは runtime 未反映
- archetype ごとの dedupe / versioning / relationship / retention は未実装
- current cloud import は共通モデルへ移行途中
- Notion / CRM / mail / chat の個別 connector adapter は未実装

したがって、本設計の目的は「ゼロから構想する」ことではなく、「すでに入った generic ingest の足場を壊さず、第5類型を含む本来の抽象モデルへ育てる」ことにある。

## 3. 基本方針

### 3.1 First-class にするのは製品名ではなく類型

First-class の分類は vendor / product ではなく、データの意味と取り込み後の振る舞いに基づく archetype とする。

採用する archetype:

- `compound_note`
- `file_share`
- `chat_context`
- `business_record`
- `message_context`

vendor / product は connector の属性として扱う。

例:

- `sourceArchetype=file_share`, `sourceSystem=google_drive`
- `sourceArchetype=compound_note`, `sourceSystem=notion`
- `sourceArchetype=business_record`, `sourceSystem=salesforce`
- `sourceArchetype=message_context`, `sourceSystem=imap`

### 3.2 入口は 3 系統、内部パイプラインは 1 本

外部取込の入口は次の 3 系統を前提とする。

1. UI 手動取込
2. API 経由取込
3. 定期同期 / イベント駆動取込

ただし内部では共通の canonical import pipeline を通す。

```text
UI / API / Scheduler / Webhook
  -> ExternalIngestFacade
  -> ConnectorAdapter
  -> CanonicalImportService
  -> Document create/update
  -> Secondary type / relationship 付与
  -> Lineage emit
  -> Job / audit / dead-letter 記録
```

これにより、入口ごとに来歴や relationship の表現がずれることを防ぐ。

### 3.3 NemakiWare と Purview/Dataplex の責務分離

- NemakiWare:
  - 文書本体
  - 詳細文脈
  - 元レコードや元会話との関係
  - 版管理 / ACL / retention
- Purview / Dataplex:
  - 高レベルな系譜グラフ
  - 外部ソースとの関係
  - 横断カタログ / ガバナンス可視化

詳細な `externalContext` JSON 全体を Purview / Dataplex に送ることは目指さない。そこでは stable id, source system, process, relationship を中心に出す。

## 4. Archetype モデル

### 4.1 `compound_note`

対象例:

- Notion
- Lotus Notes
- Evernote

特徴:

- 主体は本文台紙
- 添付は従属オブジェクト
- ページやノート自体が 1 つの記録単位になりやすい

推奨 Nemaki モデル:

- ページ / ノート本体を 1 文書として取り込む
- 添付は別文書として取り込む
- `has_attachment` relationship を張る
- ページ文書に外部文脈を保持する

### 4.2 `file_share`

対象例:

- OneDrive
- Google Drive
- Box
- Dropbox

特徴:

- 主体はファイル
- フォルダ / 共有 / ACL / 共有リンクの意味が強い
- 同期や pull / push が主ユースケースになる

推奨 Nemaki モデル:

- ファイル本体を 1 文書として取り込む
- 必要に応じて source folder / share のメタデータを secondary type に保持
- source folder と target folder の紐付けを import profile で持つ

### 4.3 `chat_context`

対象例:

- Slack
- Microsoft Teams
- Chatwork

特徴:

- 主体は会話
- 添付は message / thread の文脈に従属する
- 文脈切り出しの恣意性が高い

推奨 Nemaki モデル:

- 添付は別文書として取り込む
- 必要なら会話抜粋を別文書として持つ
- `attached_in_message` / `derived_from_thread` relationship を張る
- 採用した会話範囲を `externalContext` に必ず残す

### 4.4 `business_record`

対象例:

- CRM
- ERP
- BPM

特徴:

- 主体は定型レコード
- 業務キー、ステータス、担当、日付などの structured metadata が安定している
- RM 的には最も説明しやすい

推奨 Nemaki モデル:

- record summary 文書を 1 文書として持つか、添付だけを文書として持つ
- 添付は `attached_to_record` relationship で record summary と結ぶ
- process instance / case / opportunity / invoice 等の stable key を typed property に昇格しやすい

### 4.5 `message_context`

対象例:

- IMAP
- Gmail API
- Microsoft 365 Mail / Graph Mail

特徴:

- 主体はメールメッセージ
- 添付は message の MIME 構造に従属する
- `From/To/Cc/Bcc/Subject/Date/Message-ID/In-Reply-To/References` の envelope metadata が強い
- `chat_context` より文脈境界が自然で、RM 的にも 1 通単位で扱いやすい

推奨 Nemaki モデル:

- メール本文または `.eml` 原本を 1 文書として取り込む
- 添付は別文書として取り込む
- `attached_to_message` / `derived_from_message` relationship を張る
- 必要に応じて `internetMessageId`, mailbox, folder, sentAt, receivedAt などを typed property に昇格する

重要:

- `imap` は接続方式であって archetype ではない
- First-class は `message_context`
- `imap`, `gmail_mail`, `m365_mail` は connector の `sourceSystem` として表現する

## 5. 設定モデル

外部取込の設定は 3 層に分ける。

### 5.1 Archetype 定義

実装側で固定する enum / type。

- `compound_note`
- `file_share`
- `chat_context`
- `business_record`
- `message_context`

運用者が自由入力する対象ではない。

### 5.2 Connector 定義

system / global スコープの named connector。

例:

- `notion-main`
- `google-drive-sales`
- `box-legal`
- `salesforce-prod`
- `slack-support`
- `imap-legal-hold`
- `gmail-operations`
- `m365-mail-compliance`

保持項目:

- `connectorId`
- `sourceSystem`
- `sourceArchetype`
- auth type / credential reference
- endpoint / workspace / tenant / site
- adapter class / adapter kind
- rate limit / retry policy
- enabled

役割:

- 外部システムへの接続方法を表す
- auth / tenant / workspace / endpoint を保持する
- destination は持たない

connector は「どこから取るか」を表す層であり、「どこへ入れるか」を表す層ではない。

### 5.3 Import Profile 定義

repository スコープの named profile。

例:

- `notion-product-specs`
- `crm-contract-attachments`
- `slack-incident-evidence`

保持項目:

- `profileId`
- `repositoryId`
- `targetFolderId` または `targetFolderPath`
- default object type
- attach する secondary types
- relationship policy
- dedupe policy
- update policy
- versioning policy
- classification / retention default
- ACL sync policy
- UI visible flag
- scheduler / webhook enabled flag
- `allowedArchetypes`
- `allowedConnectorIds` (optional)
- `defaultConnectorId` (optional)

役割:

- repository 内の destination と保管ポリシーを表す
- target folder, object type, relationship, retention, ACL などを固定する
- auth や tenant 設定を直接持たない

import profile は「どこへどう保管するか」を表す層であり、「どう認証するか」を表す層ではない。

同じ profile を複数 connector から利用できるようにする。  
例:

- `sales-documents` profile に対して `google-drive-sales` と `box-sales` の両方を許可
- `legal-evidence` profile に対して `slack-legal` と `teams-legal` を許可

`allowedConnectorIds` はガードレールであり必須ではない。  
`defaultConnectorId` は UI 初期値や scheduler の既定選択のための補助値であり、binding ではない。

### 5.4 Import Request / Job

実行時入力。UI, API, Scheduler のどれから来てもこの形へ寄せる。

保持項目:

- `requestId`
- `profileId`
- `connectorId`
- source object selection
- execution mode (`manual`, `api`, `scheduled`, `event`)
- dry-run flag
- idempotency key / correlation id
- limited override fields

原則:

- request は `profileId` を必須とする
- request は `connectorId` を明示指定する
- `connectorId` は profile の `allowedConnectorIds` / `allowedArchetypes` に合致している必要がある
- `targetFolderId` のような destination override は通常禁止し、例外的な admin-only override に限定する

### 5.5 設定の置き場

- `repositories.yml`
  - repository bootstrap のみ
- `nemaki_conf`
  - connector 定義
  - import profile 定義
  - runtime tuning

理由:

- 既存 NemakiWare でも integration 設定は `nemaki_conf` に寄せる設計
- connector や import profile は運用で増減しやすい
- ブランチ切替や Docker image 差し替えと切り離して調整できる

### 5.6 既存 cloud import からの移行方針

既存の cloud import は新方式へ吸収する前提とする。  
`file_share` だけ旧方式で残すことはしない。

current の概念対応:

- current cloud auth setting
  -> `connector`
- current import destination setting
  -> `import profile`
- current manual import execution
  -> `import request`

移行時の注意:

- current UI が `cloud auth` と `target folder` を直接結びつけていても、新方式ではそれを分離する
- 移行初期は current の 1 組を `connector + profile + defaultConnectorId` に展開して互換表示してよい
- ただし内部 canonical model では `profile` が `connector` を所有しない
- cloud import の service は新しい `CanonicalImportService` を使う pilot 実装に置き換える

この方針により、

- 同じ destination profile に対して複数 source system を接続できる
- 同じ connector から複数 destination profile へ振り分けられる
- UI 手動取込と API / ETL / agent 取込で同じ profile を共有できる

## 6. 共通 canonical metadata

全 archetype で最低限そろえる項目。

- `sourceArchetype`
- `sourceSystem`
- `sourceObjectType`
- `sourceObjectId`
- `sourceUrl`
- `capturedAt`
- `capturedBy`
- `ingestionRunId`
- `contextSchemaVersion`

このうち、現在の `nemaki:externalIntegration` では次のように対応させる。

- `externalSourceType` -> `sourceArchetype`
- `externalSourceId` -> `sourceSystem`
- `externalContext` -> vendor 固有 JSON

ただし、今後は `externalSourceType` / `externalSourceId` だけでは意味が弱いため、typed property の追加を推奨する。

推奨追加候補:

- `nemaki:sourceArchetype`
- `nemaki:sourceSystem`
- `nemaki:sourceObjectType`
- `nemaki:sourceObjectId`
- `nemaki:sourceUrl`
- `nemaki:ingestionRunId`
- `nemaki:sourceContainerType`
- `nemaki:sourceContainerId`

## 7. 類型ごとの保持項目

### 7.1 `compound_note`

最低限:

- page / note id
- page url
- parent page id
- notebook / database id
- author
- last edited by
- last edited at

### 7.2 `file_share`

最低限:

- provider
- file id
- folder id
- folder path
- web url
- last synced at
- shared link / permission summary

### 7.3 `chat_context`

最低限:

- workspace / tenant id
- channel id
- thread id
- anchor message id
- selected message ids
- capture window
- participants
- selection reason

### 7.4 `business_record`

最低限:

- record type
- record id
- record url
- status
- owner
- business dates
- process / case / ticket / opportunity id

### 7.5 `message_context`

最低限:

- account / mailbox tenant id
- mailbox id or folder path
- stable message id
- internet message id
- subject
- from / to / cc
- sent at
- received at
- in-reply-to
- references
- attachment id / MIME part id

## 8. 取り込み単位と Nemaki オブジェクト設計

### 8.1 Compound note は本文と添付を分ける

Notion, Notes, Evernote のような系統は、

- ページ本体を 1 文書
- 添付を別文書
- relationship で接続

が最も自然である。

理由:

- 本文と添付は版管理・分類・保持期間が異なる
- ページ単体でも検索 / 参照の価値がある
- 添付だけを再利用したいケースがある
- Purview / Dataplex でも page -> attachment -> Nemaki doc の系譜に分解しやすい

推奨 relationship:

- `has_attachment`
- `attached_to_record`
- `derived_from_context`
- `generated_by_process`

vendor 固有 relationship は原則避ける。

### 8.2 Message context は本文と添付を分け、可能なら原本 MIME も保持する

mail 系は次の 2 層で持てると望ましい。

1. 原本メール
   - `.eml` 相当の MIME message
   - 監査 / 証跡 / 改ざん検知に強い
2. 利用しやすい抽出表現
   - 本文テキスト / HTML
   - 添付ファイル群
   - envelope metadata

最低限の初期実装では、

- メール本文を 1 文書
- 添付を別文書
- `attached_to_message` relationship

でもよい。ただし将来的な監査要件を考えると `.eml` 原本保持の拡張余地を残すべき。

## 9. Lineage モデル拡張

### 9.1 基本方針

lineage process type は vendor 名ではなく archetype / operation で切る。

追加候補:

- `EXTERNAL_NOTE_IMPORT`
- `EXTERNAL_ATTACHMENT_IMPORT`
- `BUSINESS_RECORD_IMPORT`
- `CHAT_ATTACHMENT_IMPORT`
- `MAIL_MESSAGE_IMPORT`
- `MAIL_ATTACHMENT_IMPORT`
- `FILE_SHARE_SYNC_UPLOAD`
- `FILE_SHARE_SYNC_DOWNLOAD`

### 9.2 URI 命名規約

入力 URI は stable id ベースで定義する。

例:

- `notion://workspace/{workspaceId}/pages/{pageId}`
- `notion://workspace/{workspaceId}/files/{fileId}`
- `box://enterprise/{enterpriseId}/files/{fileId}`
- `slack://workspace/{workspaceId}/channels/{channelId}/messages/{messageId}`
- `salesforce://org/{orgId}/records/{objectType}/{recordId}`
- `bpm://system/{systemId}/process-instances/{instanceId}`
- `mail://tenant/{accountId}/mailboxes/{mailboxId}/messages/{messageStableId}`
- `mail://tenant/{accountId}/mailboxes/{mailboxId}/messages/{messageStableId}/attachments/{attachmentId}`

出力は既存 Nemaki object qualified name を使う。

- `nemaki://{repositoryId}/objects/{objectId}`

### 9.3 Snapshot attributes に入れるもの

lineage の `snapshotAttributes` には詳細 JSON 全体は入れない。次のような軽量項目に絞る。

- `sourceSystem`
- `sourceArchetype`
- `sourceObjectType`
- `sourceObjectId`
- `sourceUrl`
- `importMode`
- `provider`
- `recordType`
- `threadId`
- `mailboxId`
- `messageId`
- `internetMessageId`

詳細文脈は secondary type 側に保持する。

mail 系の注意:

- `Message-ID` は重要だが唯一の stable key にはしない
- IMAP connector では `account + mailbox + UIDVALIDITY + UID` 相当の stable key を正規化して使う
- `Message-ID` は説明用 / 検索用属性として保持する

## 10. UI / API / Scheduler の役割

### 10.1 UI 手動取込

用途:

- 管理者または業務ユーザーが対象を選んで取り込む

必要項目:

- profile 選択
- source object 選択
- dry-run
- duplicate 判定結果の確認
- target folder 確認
- relationship 生成予定の確認

### 10.2 API 取込

用途:

- AI エージェント
- ETL
- iPaaS
- custom batch

基本原則:

- 本番では `profileId` 指定を基本とする
- ad-hoc import は admin 限定

最低限必要:

- idempotency key
- correlation id
- source stable id
- overwrite / new version / skip policy
- dry-run

### 10.3 定期同期 / イベント駆動

用途:

- Notion 差分同期
- CRM 更新トリガ
- チャット添付の継続収集
- メールボックス監視 / IMAP IDLE / Graph webhook

必要事項:

- scheduler / webhook 定義
- cursor / checkpoint
- retry / dead-letter
- replay

## 11. Dedupe / versioning / update policy

外部取込では同一ソースの再取込が前提になる。

最低限、profile ごとに次を持つ。

- `skip_if_same_source_version`
- `create_new_version_if_content_changed`
- `create_new_document_if_parent_context_changed`
- `replace_relationships_on_resync`

推奨:

- 同じ `sourceSystem + sourceObjectType + sourceObjectId` は stable key とみなす
- content が同じで context のみ更新なら secondary type 更新のみ
- content が変われば version up
- page と attachment は別判定
- mail message と mail attachment も別判定

## 12. ACL と配置

### 12.1 target folder は profile に事前定義する

API 呼び出しごとに自由な folder 指定を許すと、AI agent / ETL / 運用バッチがバラバラの場所へ投入しやすい。通常運用では target folder を import profile で固定する。

許容される override は限定する。

許可してよい:

- dry-run
- source object id
- overwrite / versioning 指定
- 実行メモ

慎重にすべき:

- target folder
- source archetype
- source system
- relationship policy
- classification / retention

### 12.2 chat / file_share の ACL 同調

将来的に次のような拡張余地がある。

- Teams / Slack channel と folder ACL の対応
- Box / Drive folder share と Nemaki ACL の初期反映

ただし初期実装では完全同期よりも、

- import profile による target folder 制御
- 初期 ACL policy 適用

を優先する。

### 12.3 message_context の ACL

mail は channel や folder ACL を Nemaki 側へ厳密同調するよりも、

- import profile による target folder 固定
- 初期 classification / retention
- message metadata の保持

を優先すべきである。  
mailbox ACL の完全反映は初期スコープに入れない。

## 13. Purview / Dataplex 連携方針

### 13.1 Purview

Purview には次を出す。

- process
- input / output asset
- stable key
- source system
- document / record / page / attachment の関係

出さないもの:

- 会話全文
- 詳細 external context JSON
- 長文の CRM / ERP payload

### 13.2 Dataplex

Dataplex についても考え方は同じ。

- Google platform 側には lineage graph を出す
- 詳細文脈は NemakiWare に残す

なお Dataplex sink 自体は実装済みだが、現在の管理 UI はまだ発展途上である。個別 connector 導入時には Dataplex も Purview と同じ canonical event から投影させる。

## 14. 実装フェーズ案

### Phase 1: 設計固定

- archetype enum
- connector 定義モデル
- import profile 定義モデル
- canonical import service の責務固定
- lineage URI 命名規約

### Phase 2: file_share 一般化

- 既存 cloud import の共通化
- Box / Dropbox の足場
- connector / profile 経由化

### Phase 3: message_context 導入

- archetype 追加
- IMAP / Gmail / M365 Mail connector 方針の固定
- mail URI 命名規約
- message / attachment / original MIME の object model 固定
- `MAIL_MESSAGE_IMPORT` / `MAIL_ATTACHMENT_IMPORT` の lineage 追加

### Phase 4: compound_note 導入

- Notion ページ取込
- ページ本文 + 添付分離
- relationship 生成
- lineage emit

### Phase 5: business_record 導入

- CRM / ERP / BPM 用 typed fields
- record summary + attachment モデル
- process / case / ticket 系 relationship

### Phase 6: chat_context 導入

- 会話スパン capture model
- evidence boundary 固定
- 監査 / explanation ルール

## 15. Claude Code への引き継ぎメモ

次のエージェントは、まず「IMAP connector を作る」「Notion connector を作る」よりも先に次の抽象レイヤを入れるべき。

1. `sourceArchetype` と `sourceSystem` の整理
2. connector / import profile の設定モデル
3. canonical import service の導入
4. current cloud import をその service に寄せる

これを飛ばして vendor 個別実装から入ると、Google Drive / OneDrive / IMAP / Gmail / Notion / CRM / chat で来歴・relationship・配置がばらける。

Claude Code への追加指示:

1. `message_context` を `SourceArchetype` に追加する
2. `LineageProcessType` に mail message / attachment 用 process type を追加する
3. `ExternalSourceUri` に mail message / attachment URI helper を追加する
4. `ExternalIngestRequest` に mail を first-class に表現するための typed field 追加可否を決める
5. `ImportProfileDefinition` の Phase 1 未反映項目を広げる前に、未サポート項目を UI/API でどう扱うかを固定する
6. IMAP connector を作る前に、mail connector 共通の stable key ルールを定義する

## 16. 未決定事項

- connector / import profile を CouchDB のどの document shape で持つか
- profile ごとの relationship type 名をどこまで固定するか
- page 本文を HTML / Markdown / plain text のどれで正規保存するか
- chat context を別文書化するか、external context JSON のみにするか
- mail 原本を `.eml` として保存するか、本文抽出だけを先に実装するか
- mail connector の stable key を `UIDVALIDITY + UID` 基準にするか、provider 正規化キーを別に持つか
- records manager 目線で必須の typed property をどこまで最初に昇格するか

## 17. 今回の推奨結論

- First-class は archetype
- vendor 名は connector
- target folder や retention / relationship policy は import profile
- UI / API / scheduler は入口として分けるが、内部パイプラインは共通
- `externalContext` は残すが、検索・監査・再同期に効くキーは typed property に昇格する
- mail は `message_context` として扱い、`imap` は connector の `sourceSystem` に置く
