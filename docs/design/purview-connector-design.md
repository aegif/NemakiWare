# NemakiWare Purview Connector 詳細設計・実装計画

最終更新: 2026-03-20  
対象ブランチ: `codex/purview-connector`

## 0. 実装状況

2026-03-20 時点の実装状況を設計と分けて明示する。

実装済み:

- Purview 接続設定と `test-connection`
- `schema-state` / `type-definitions/diff` / `type-definitions/apply`
- collection スコープ `TYPE_BOOTSTRAP` job
- `job state` / `lock state` / `stream cursor state`
- `GET /v1/admin/purview/state` による state overview
- `nemaki_repository` / `nemaki_folder` / `nemaki_document` を含む schema manifest / payload
- full sync の repository / folder / document page traversal と bulk upsert
- incremental sync の `CREATED` / `UPDATED` / `SECURITY` 系 folder / document upsert
- `DELETED` change の tombstone stage
- repository 単位 `DELETE_RESOLUTION` job
- live / archive / purge 判定に応じた tombstone 解決
- Purview entity delete-by-unique-attribute 呼び出し
- state overview での tombstone 可視化
- repository 単位 `ARCHIVE_RECONCILIATION` job
- `ARCHIVED` tombstone から archived document / `nemaki_archive` の upsert

未実装:

- type definition asset の実体同期
- archive / cloud metadata / type reconciliation
- lineage 実体生成
- 管理 UI

現時点の実装は「repository / folder / document metadata を Purview に載せる最初の縦スライス」を成立させることを優先している。設計上の初期スコープ全体はまだ完了していない。

## 1. 目的

NemakiWare を Microsoft Purview に統合し、NemakiWare 内のリポジトリ、フォルダ、文書、タイプ定義、アーカイブ、外部連携イベントを Purview 上でカタログ化できるようにする。

このコネクタの目的は、Purview を NemakiWare の代替ストアにすることではなく、NemakiWare を組織横断のガバナンス対象に載せることである。特に以下を実現する。

- Purview から NemakiWare 資産を検索、分類、発見できること
- Purview の custom types / business metadata / lineage で NemakiWare 特有の構造を表現できること
- NemakiWare の変更を Purview に継続同期できること
- NemakiWare の責務である ACL、版管理、全文検索、ベクトル検索を壊さずに導入できること

## 2. この設計で確定した重要判断

セルフレビューの結果、初期実装で曖昧にしない方針を先に固定する。

- Purview 連携は NemakiWare Core 内の非同期ジョブとして実装する
- 初期同期の主軸は REST ベースの Data Map API とし、Kafka endpoint は将来拡張とする
- 増分同期の主軸は change log とする
- ただし change log だけではタイプ定義、アーカイブ、添付、外部同期イベントを完全には拾えないため、補助リコンシリエーションを別途持つ
- Purview の upsert キーは `qualifiedName` で固定し、rename / move では変えない
- `DELETED` change event は即 purge 扱いせず tombstone 解決を挟む
- `ARCHIVED` は保持するが、`PURGED` は既定で Purview から物理削除する
- schema bootstrap は repository sync から分離し、collection スコープの一回性ジョブとして扱う
- glossary / classification / labels の本格同期は Phase 6 以降とし、初期段階では拡張ポイントのみに留める

## 3. 背景

NemakiWare には既に以下の機能がある。

- CMIS 1.1 ベースのオブジェクトモデル
- オブジェクト単位の ACL
- カスタムタイプ、カスタムプロパティ
- 変更イベントと change log
- Webhook 通知
- import / export
- Cloud Drive 連携
- archive / retention

Purview 側では、組み込みスキャン対象に存在しないソースを custom types / entity / relationship / lineage API で統合できる。NemakiWare の場合、Purview の built-in scanning source に無理に合わせるより、NemakiWare 専用のカスタムコネクタとして Data Map に資産を投入する方が実装上も運用上も現実的である。

## 4. 前提と制約

### 4.1 Purview 側前提

- Purview Data Map API を利用可能であること
- Entra ID の application registration と client credential を準備できること
- NemakiWare 用 collection を事前に確保できること
- type / entity / relationship / lineage を作成できる権限があること

運用上は、対象 collection に対して少なくとも読み取りと資産作成更新に必要な権限が必要である。PoC では Data Reader + Data Curator 相当を前提にし、本番ではテナントの権限ポリシーに合わせて最小権限化する。

### 4.2 API 前提

初期実装では以下を前提とする。

- エンドポイントは `https://{account}.purview.azure.com`
- Atlas 系 API の基本パスは `datamap/api/atlas/v2` を第一候補とする
- API version は `2023-09-01` 系を前提にする
- type definition / entity / relationship / glossary / business metadata は REST API で扱う
- entity 物理削除は `DELETE /datamap/api/atlas/v2/entity/uniqueAttribute/type/{typeName}?attr:qualifiedName=...` を使う方針とする

注意:

- Microsoft Learn のチュートリアルには `catalog/api/atlas/v2` の表記も残っている
- REST API リファレンスは `datamap/api/atlas/v2` を使う

このため実装では base path を固定値ベタ書きせず、`test-connection` 時に利用可能な base path を判定し、内部設定として保持する。

### 4.3 Purview 側の制約

- scanning API の built-in source kind に NemakiWare は存在しない
- custom classification rule は PDF / Office のような非構造文書を万能には扱えない
- Data Map API は大きい payload や大量ページングで 408 / 429 が起き得る
- collection / domain / role の表現は UI と API 文脈で揺れがあるため、実装では collection を正とする

### 4.4 NemakiWare 側の制約

既存の `getContentChanges()` は documents / folders を主対象としており、以下を change log だけで完全に拾える前提には立てない。

- type definition の更新
- archive の状態変化
- 添付単位の変更
- Cloud Drive 連携の外部ファイル ID 更新
- retention 設定の変更

このため、増分同期は change log 単独ではなく、補助ポーラーまたは定期整合処理を前提にする。

## 5. 設計原則

- Purview 連携は NemakiWare の書き込み処理を同期ブロックしない
- Purview 連携は metadata / lineage の同期に限定し、実データ保管や ACL 判定は NemakiWare に残す
- full sync と incremental sync を分離し、失敗時に再実行できるようにする
- `qualifiedName` を安定キーとして idempotent に upsert する
- Purview には ACE 生配列を持ち込まず、必要最小限の ACL 要約のみを同期する
- Purview の built-in type を優先し、custom type は不足分だけを補う
- 大量同期時の 429 / 408 を前提に batch / throttle / retry を最初から組み込む
- `ARCHIVED` は lifecycle として保持し、`PURGED` は catalog trust を優先して物理削除する

## 6. スコープ

### 6.1 初期実装スコープ

- Purview 接続設定と接続テスト
- Purview custom types / business metadata definitions の登録
- NemakiWare repository / folder / document / type definition / archive の同期
- full sync
- incremental sync
- import / export / archive / cloud sync の lineage 生成
- 管理者向け REST API
- ジョブ状態、カーソル、失敗状態の可視化

### 6.2 初期実装の対象外

- Purview を NemakiWare の認可基盤として使うこと
- Purview から NemakiWare の本文検索を実行すること
- Purview に本文やバイナリを保存すること
- NemakiWare を Purview の built-in scanning source として登録すること
- 初期段階での双方向同期
- 初期段階での glossary / classification / labels の本格同期
- 初期段階での Kafka endpoint 利用
- 初期段階での管理 UI 必須化

## 7. 既存 NemakiWare 機能の活用ポイント

### 7.1 full sync 起点

- repository 一覧
- folder / document 走査
- type definition 一覧
- archive 一覧

### 7.2 incremental sync 起点

既存の change log 実装を第一候補とする。

- `DiscoveryServiceImpl.getContentChanges()`
- `ContentService.getLatestChanges()`
- `ContentService.getLatestChangeToken()`
- `ContentService.writeChangeEvent()`

change log は change token による増分処理と相性が良く、Purview 連携を初期導入するうえで最も侵襲が少ない。

### 7.3 補助イベント

既存 webhook 系は将来の低レイテンシ化に使える。

- `WebhookService.triggerWebhook()`
- `WebhookService.triggerWebhookByEventType()`

ただし初期実装では、Purview 更新のために既存 webhook を自己呼び出しさせる構成は採らない。source of truth は change log と補助リコンシリエーションであり、webhook は最適化用途に限定する。

### 7.4 補助リコンシリエーションが必要な対象

- type definition
- archive
- cloud metadata
- relationship object

これらは repository ごとに別カーソルを持つか、定期的な checksum / 再走査で整合を取る。

## 8. アーキテクチャ設計

### 8.1 全体構成

Purview コネクタは NemakiWare Core 内に実装する。

構成要素:

- `PurviewConfigService`
- `PurviewTokenService`
- `PurviewApiClient`
- `PurviewSchemaStateService`
- `PurviewTypeDefinitionService`
- `PurviewEntityMapper`
- `PurviewExternalAssetService`
- `PurviewEntitySyncService`
- `PurviewLineageService`
- `PurviewDeletionResolver`
- `PurviewSyncStateService`
- `PurviewSyncPlanner`
- `PurviewSyncCoordinator`
- `PurviewJobLockService`
- `PurviewAdminResource`

### 8.2 責務分離

- `PurviewApiClient`
  - HTTP 呼び出しのみ担当
  - retry / throttle / auth header 付与
- `PurviewSchemaStateService`
  - collection スコープの schema hash / schema version 管理
- `PurviewEntityMapper`
  - NemakiWare ドメイン -> Purview payload 変換
- `PurviewExternalAssetService`
  - lineage 相手の外部 asset を解決または placeholder 作成
- `PurviewDeletionResolver`
  - `DELETED` event 後の archive / purge / hard delete 判定
- `PurviewSyncPlanner`
  - full / incremental の対象判定
  - change event -> sync operation 変換
- `PurviewSyncCoordinator`
  - batch 実行、checkpoint 更新、エラー処理
- `PurviewSyncStateService`
  - sync state 永続化
- `PurviewTypeDefinitionService`
  - custom types / relationship defs / business metadata defs の管理

### 8.3 実行モデル

Purview 反映はすべて非同期ジョブとして実行する。

- ユーザー更新処理は change log 生成までで完結
- Purview 障害でも NemakiWare の create / update / delete を失敗させない
- job lock により repository 単位の重複実行を防ぐ

### 8.4 ジョブ種別

- `TYPE_BOOTSTRAP`
- `FULL_SYNC`
- `INCREMENTAL_SYNC`
- `TYPE_RECONCILIATION`
- `ARCHIVE_RECONCILIATION`
- `DELETE_RESOLUTION`
- `RETRY_FAILED_BATCH`

## 9. Purview 側のモデリング方針

### 9.1 built-in type 優先

Purview では built-in type の方が UI や lineage 表示との相性が良い。従って次を原則とする。

- 文書は `DataSet` 系に寄せる
- import / export / archive / sync は `Process` 系に寄せる
- NemakiWare 固有属性は custom entity type または business metadata で補う

### 9.2 serviceType

- `NemakiWare`

すべての custom type は `NemakiWare` serviceType に紐づける。

### 9.3 entity type

- `nemaki_repository`
- `nemaki_folder`
- `nemaki_document`
- `nemaki_type_definition`
- `nemaki_archive`
- `nemaki_relationship`
- `nemaki_external_asset`
- `nemaki_import_process`
- `nemaki_export_process`
- `nemaki_archive_process`
- `nemaki_cloud_sync_process`

方針:

- `nemaki_document` は `DataSet` 系
- 各 process type は `Process` 系
- `nemaki_folder` / `nemaki_repository` は純粋 custom type

### 9.4 relationship type

- `nemaki_repository_contains_folder`
- `nemaki_folder_contains_folder`
- `nemaki_folder_contains_document`
- `nemaki_document_has_type_definition`
- `nemaki_document_has_archive`
- `nemaki_document_related_to_document`

### 9.5 business metadata 定義

初期段階では `NemakiGovernance` 相当の business metadata を定義し、custom type を増やし過ぎないようにする。

候補属性:

- `repositoryId`
- `objectId`
- `parentId`
- `baseTypeId`
- `typeId`
- `path`
- `versionSeriesId`
- `versionLabel`
- `isLatestVersion`
- `mimeType`
- `contentLength`
- `creator`
- `lastModifiedBy`
- `archivedAt`
- `archiveState`
- `coldMoveMode`
- `cloudProvider`
- `externalFileId`
- `aclSummary`
- `nemakiLifecycleState`

`aclSummary` は以下のような要約に限定する。

- local ACE 数
- inherited ACE 数
- public readable かどうか
- owner principal

### 9.6 custom type の UI 制約

Purview の custom type は built-in type と完全に同等の UI 表示を保証しない。初期設計では以下を前提にする。

- UI 上の見え方は built-in `DataSet` / `Process` より弱い可能性がある
- custom type には専用アイコンや built-in 相当の hierarchy 表示を期待しない
- そのため、検索・一覧・lineage の主役になる資産は built-in 系を優先する
- repository / folder / type definition のように built-in に乗せにくいものだけ custom type とする

## 10. `qualifiedName` 設計

Purview upsert の安定キーとして `qualifiedName` を使う。

### 10.1 repository

`nemaki://{repositoryId}`

### 10.2 folder / document

`nemaki://{repositoryId}/objects/{objectId}`

### 10.3 type definition

`nemaki://{repositoryId}/types/{typeId}`

### 10.4 archive

`nemaki://{repositoryId}/archives/{archiveId}`

### 10.5 process

`nemaki://{repositoryId}/process/{kind}/{stableId}`

方針:

- path ではなく `objectId` を基準にする
- repository を prefix に含める
- rename / move で `qualifiedName` を変えない
- path は属性として別保持する
- external source key がある場合でも `qualifiedName` の主キーは NemakiWare 側 ID に寄せる

補足:

- Purview では `qualifiedName` が検索・upsert の一意属性として使われる
- そのため repository prefix を必須にし、複数 repository 間での衝突を避ける

## 11. NemakiWare -> Purview マッピング

### 11.1 lineage 相手 asset の生成責務

前回レビューで最も大きかった曖昧点は、lineage の source / target 側 asset を誰が作るかだった。初期実装では以下で固定する。

- NemakiWare Purview connector 自身が lineage 相手 asset の作成責務を持つ
- ただし stable key を持つ外部対象に限って placeholder asset を作る
- stable key を持たない ad-hoc 操作は process metadata のみを記録し、外部 asset は作らない

stable key がある対象:

- OneDrive / Google Drive の fileId
- cold storage の bucket + key
- cloud sync の provider + externalFileId
- サーバー管理下の export job ID + managed target path

stable key がない、または運用上再現不能な対象:

- 管理者ローカル PC へのブラウザダウンロード
- 一時 ZIP ファイル
- 一時 filesystem export パス

これらは初期実装では lineage asset を作らず、`Process` に `targetDescription` 等の属性を持たせるだけに留める。

### 11.2 外部 asset 用 custom type

外部系 placeholder 用に最小 custom type を追加する。

- `nemaki_external_asset`

主要属性:

- `externalSystem`
- `externalTenantOrAccount`
- `externalObjectKey`
- `externalDisplayName`
- `externalAssetKind`

`qualifiedName` 例:

- `external://onedrive/{tenantId}/{driveItemId}`
- `external://gdrive/{tenantId}/{fileId}`
- `external://s3/{bucket}/{key}`

| NemakiWare | Purview |
|-----------|---------|
| Repository | `nemaki_repository` |
| Folder | `nemaki_folder` |
| Document | `nemaki_document` |
| Custom type | `nemaki_type_definition` |
| Archive item | `nemaki_archive` |
| Cloud import / push | `nemaki_cloud_sync_process` |
| ZIP / filesystem export | `nemaki_export_process` |
| Archive to cold storage | `nemaki_archive_process` |

同期対象属性:

- `cmis:name`
- `cmis:description`
- `cmis:objectTypeId`
- `cmis:baseTypeId`
- `cmis:createdBy`
- `cmis:lastModifiedBy`
- `cmis:creationDate`
- `cmis:lastModificationDate`
- version 情報
- custom properties
- archive 情報
- cloud drive metadata

除外方針:

- 文書本文
- binary attachment
- webhook secret 等の機密値
- API key や credential 類
- 埋め込みベクトルそのもの

## 12. lifecycle と削除戦略

ここは初稿で曖昧だったため、明示的に定義する。

### 12.1 live object

- `nemakiLifecycleState=ACTIVE`

### 12.2 `DELETED` event の解決フロー

NemakiWare では `deleteInternal()` の中で、先に `ChangeType.DELETED` が書かれ、その後 archive が作成される。このため Purview sync は `DELETED` を受けた瞬間に purge と断定してはいけない。

解決アルゴリズム:

1. `DELETED` を受けたら即 purge せず、`DELETE_RESOLUTION` queue に積む
2. 短い grace period 後に以下の順で状態確認する
3. live object が存在するなら event 競合として `ACTIVE` を維持する
4. `getArchiveByOriginalId(objectId)` が見つかれば tombstone を `ARCHIVED` に確定し、archive reconciliation に引き渡す
5. live object も archive も存在しなければ `PURGED` と判定する

必要設定:

```properties
purview.delete-resolution.delay.ms=5000
```

この tombstone 解決を経ることで、change log と archive 作成順序のズレを吸収する。

### 12.3 archived object

NemakiWare で delete -> archive になった場合:

- 元の `nemaki_document` entity は残す
- `nemakiLifecycleState=ARCHIVED`
- 必要に応じて `nemaki_archive` entity を別作成
- `nemaki_document_has_archive` を作る

2026-03-20 実装状況:

- live/archive/purge の三分岐判定自体は `DELETE_RESOLUTION` で実装済み
- ただし archive 検出時はまだ Purview asset を `ARCHIVED` に更新せず、tombstone state を `ARCHIVED` にして後続 reconciliation へ渡す段階

理由:

- lineage と資産履歴を維持したい
- 即物理削除すると Purview 上で履歴が見えなくなる

restore 時は NemakiWare 側で `ChangeType.CREATED` が再発行されるため、connector は同一 `qualifiedName` の `nemaki_document` を `ACTIVE` に戻し、archive reconciliation で消えた archive entity を整理する。

### 12.4 purged object

NemakiWare で archive destroy まで完了した場合:

- `PURGED` は Purview 上の永続 lifecycle 状態としては残さない
- `nemaki_document` と `nemaki_archive` は既定で Purview から物理削除する
- process 系 lineage は残してよいが、削除済み asset を通常検索に出さないことを優先する

理由:

- Purview は discovery カタログであり、存在しない資産を通常検索に残すと trust を損なう
- `ARCHIVED` と違い `PURGED` は業務上の参照価値より誤解リスクの方が大きい

将来、監査要件で purge 履歴を残したい場合のみ、別 collection の historical tombstone 方式を再検討する。

## 13. lineage 設計

初期段階で対応する lineage は以下の通り。

前提:

- stable key を持つ外部対象のみ asset として生成する
- stable key を持たない操作は process metadata のみ記録する

### 13.1 import lineage

- OneDrive / Google Drive -> `nemaki_document`
- filesystem / ACP / ZIP は、stable import job key がある場合のみ外部 asset 化する
- それ以外は import process の metadata に sourceDescription を残す

### 13.2 export lineage

- `nemaki_document` -> managed external target
- ローカル filesystem / ad-hoc ZIP export は process metadata のみとする

### 13.3 archive lineage

- `nemaki_document` -> `nemaki_archive` -> cold storage target

### 13.4 cloud sync lineage

- external file -> `nemaki_document`
- `nemaki_document` -> external file

### 13.5 後続フェーズ

将来拡張として、type migration、rendition 生成、文書変換、AI 要約生成などを `Process` として表現可能にする。

## 14. 同期方式

### 14.1 full sync

用途:

- 初回導入
- custom type 更新後の再整合
- Purview 側欠損の修復

方式:

- 管理 API から明示実行
- repository 単位の逐次または制御付き並列
- 1 batch あたりの件数は設定可能
- schema bootstrap 済みであることを前提に実行
- full sync 本体は type / metadata definition を変更しない

schema bootstrap の扱い:

- collection スコープの独立ジョブ `TYPE_BOOTSTRAP` として実行する
- full sync は schema hash を参照し、未適用なら fail-fast する
- `type-definitions/apply` API だけが schema mutation を許可する

2026-03-20 実装状況:

- repository entity を `nemaki_repository` として upsert するところまで実装済み
- root folder を含む folder tree を page traversal し、`nemaki_folder` / `nemaki_document` を bulk upsert するところまで実装済み
- type definition / archive の full sync は未着手

### 14.2 incremental sync

用途:

- 日常運用

方式:

- cron ベース polling
- repository ごとに `lastChangeToken` を保持
- change log から objectId と event type を取得
- 失敗時は token を進めず再試行

2026-03-20 実装状況:

- `CREATED` / `UPDATED` / `SECURITY` 相当の change は objectId から最新 folder / document を再取得して upsert するところまで実装済み
- `DELETED` は tombstone として stage され、grace period 後は `DELETE_RESOLUTION` job で解決する
- `DELETE_RESOLUTION` は live object 復活時は tombstone を削除し、document では archive 検出時に `ARCHIVED` へ遷移させ、live/archive 不在時は Purview delete API を呼ぶ
- dead-letter 専用 queue は未実装

### 14.3 supplemental reconciliation

change log では完全でない対象のために補助ジョブを持つ。

- type definition 再同期
- archive 再同期
- cloud metadata 再同期
- relationship 再同期

次の実装 slice:

- `ARCHIVED` tombstone を入力として `nemaki_document` の lifecycle を `ARCHIVED` に更新する
- `nemaki_archive` entity を upsert する
- 成功した tombstone を削除する
- `nemaki_document_has_archive` relationship は後続 slice に分離する

2026-03-20 実装状況:

- `ARCHIVE_RECONCILIATION` job を追加済み
- `ARCHIVED` tombstone から archived document と `nemaki_archive` を bulk upsert するところまで実装済み
- live object が戻っていた場合は tombstone を削除して処理を打ち切る
- relationship 作成と archive cursor 管理は未実装

### 14.4 optional webhook assist

将来、低レイテンシが必要な場合は webhook イベントを Purview sync queue のヒントとして使う。ただし source of truth は change log と補助リコンシリエーションであり、webhook だけで整合を保証しない。

## 15. スケール・スロットリング設計

Purview Data Map API は大量取得・大量 upsert で 408 / 429 が出やすい。初期設計から以下を入れる。

- batch size 可変
- collection / repository 単位の同時実行制限
- exponential backoff
- request timeout の明示設定
- page size 制御
- partial failure を記録して再試行可能にする

実装方針:

- 初期値 `batchSize=100`
- 初期値 `maxConcurrentRepositories=1`
- 429 / 5xx は retry
- 408 は payload / page size を縮小して再試行

## 16. 設定設計

`nemakiware.properties` に以下を追加する。

```properties
purview.enabled=false
purview.account.name=
purview.endpoint=https://{account}.purview.azure.com
purview.atlas.base-path=datamap/api/atlas/v2
purview.api.version=2023-09-01
purview.tenant.id=
purview.client.id=
purview.client.secret=
purview.collection=NemakiWare
purview.sync.cron=0 */10 * * * *
purview.sync.batch.size=100
purview.sync.change-log.page-size=100
purview.sync.max-concurrent-repositories=1
purview.sync.max-retries=5
purview.sync.retry.delay.ms=3000
purview.delete-resolution.delay.ms=5000
purview.sync.include-acl-summary=true
purview.timeout.connect.ms=5000
purview.timeout.read.ms=30000
```

保存方針:

- 非機密: dynamic config
- 機密: 暗号化保存

## 17. 状態管理設計

初稿では repository ごとの単一 state doc を想定していたが、それでは `FULL_SYNC` と `INCREMENTAL_SYNC` と各 reconciliation の状態が衝突する。ここでは state を 5 層に分離する。

### 17.1 schema state

collection スコープで 1 つ持つ。

候補キー:

- `purview-schema-state::{collection}`

保持項目:

- `schemaVersion`
- `schemaHash`
- `lastAppliedAt`
- `lastAppliedBy`
- `lastDiffSummary`

### 17.2 stream cursor state

repository と stream 種別ごとに持つ。

候補キー:

- `purview-sync-state::{repositoryId}::content-change-log`
- `purview-sync-state::{repositoryId}::type-reconcile`
- `purview-sync-state::{repositoryId}::archive-reconcile`
- `purview-sync-state::{repositoryId}::cloud-reconcile`

保持項目:

- `cursor`
- `cursorKind`
- `lastRunAt`
- `lastSuccessAt`
- `lastErrorAt`
- `lastErrorMessage`
- `consecutiveFailureCount`
- `deadLetterCount`

例:

- content-change-log では `cursor=lastChangeToken`
- type-reconcile では `cursor=lastSeenTypeHash`
- archive-reconcile では `cursor=lastSeenArchiveCheckpoint`

### 17.3 job execution state

ジョブごとの実行履歴を別に持つ。

候補キー:

- `purview-job::{jobId}`

保持項目:

- `jobKind`
- `repositoryId`
- `startedAt`
- `endedAt`
- `status`
- `processedCount`
- `failedCount`
- `checkpoint`
- `errorSummary`

### 17.4 lock state

二重実行防止は state doc ではなく lock doc または分散 lock で扱う。

候補キー:

- `purview-lock::collection::{jobKind}`
- `purview-lock::repository::{repositoryId}::{jobKind}`

### 17.5 tombstone state

`DELETED` change を即 purge しないため、repository と object ごとの tombstone state を持つ。

候補キー:

- `purview-tombstone-state::{repositoryId}::{objectId}`

保持項目:

- `typeName`
- `qualifiedName`
- `changeToken`
- `firstSeenAt`
- `dueAt`
- `status`

`status` は初期実装では以下を使う。

- `PENDING`
- `ARCHIVED`
- `ERROR`

## 18. 管理 API 設計

### 18.1 状態確認

- `GET /api/v1/admin/purview/schema-state`
- `GET /api/v1/admin/purview/state`
- `GET /api/v1/admin/purview/cursor-state/{repositoryId}/{streamKind}`
- `GET /api/v1/admin/purview/repositories`
- `GET /api/v1/admin/purview/jobs/{jobId}`
- `GET /api/v1/admin/purview/type-definitions/diff`

### 18.2 実行系

- `POST /api/v1/admin/purview/test-connection`
- `POST /api/v1/admin/purview/type-definitions/apply`
- `POST /api/v1/admin/purview/full-sync`
- `POST /api/v1/admin/purview/full-sync/{repositoryId}`
- `POST /api/v1/admin/purview/incremental-sync/{repositoryId}`
- `POST /api/v1/admin/purview/delete-resolution/{repositoryId}`
- `POST /api/v1/admin/purview/reconcile/types/{repositoryId}`
- `POST /api/v1/admin/purview/reconcile/archives/{repositoryId}`
- `POST /api/v1/admin/purview/reset-state/{repositoryId}`

### 18.3 運用系

- `GET /api/v1/admin/purview/logs`
- `GET /api/v1/admin/purview/dead-letters`
- `POST /api/v1/admin/purview/retry-failed`

権限は管理者限定とする。

## 19. UI 設計

初期段階では UI 必須ではないが、管理者向けに Purview 設定・同期状況を確認できる画面を追加する価値がある。

最小 UI:

- 接続設定表示
- 接続テスト
- full sync 実行
- repository ごとの last sync 時刻
- lastChangeToken 表示
- エラー表示

配置:

- Admin 配下に `Purview Connector`

## 20. パッケージ設計

候補:

- `core/src/main/java/jp/aegif/nemaki/purview/config`
- `core/src/main/java/jp/aegif/nemaki/purview/client`
- `core/src/main/java/jp/aegif/nemaki/purview/model`
- `core/src/main/java/jp/aegif/nemaki/purview/mapping`
- `core/src/main/java/jp/aegif/nemaki/purview/service`
- `core/src/main/java/jp/aegif/nemaki/purview/job`
- `core/src/main/java/jp/aegif/nemaki/api/v1/resource/PurviewConnectorResource.java`

## 21. エラーハンドリング

- 401 / 403: credential または権限不足。同期停止
- 404: collection / type definition 不整合。再初期化を提案
- 409: 競合。最新 entity を再取得して再送
- 429 / 5xx: exponential backoff で再試行
- 408: batch / page size を落として再試行

ログ方針:

- request body 全文は出さない
- secret をログに出さない
- `qualifiedName`, repositoryId, objectId, jobId を相関キーとして出す
- Purview response body は要約のみ記録する

dead-letter 方針:

- 同一オブジェクトが規定回数以上失敗した場合は dead-letter へ退避
- incremental sync 全体は止めず、失敗オブジェクトのみ再試行対象に分離する

## 22. テスト計画

### 22.1 Unit

- `qualifiedName` 生成
- attribute mapping
- business metadata mapping
- lifecycle state mapping
- Purview payload serialization
- retry / throttle policy

### 22.2 Integration

- Purview API mock に対する upsert
- type definition 適用
- full sync のページング
- incremental sync の token 前進
- archive / cloud metadata の lineage 生成
- 408 / 429 時の retry

### 22.3 E2E

- 管理 API から接続テスト成功
- full sync 実行
- change log による増分反映
- type / archive reconciliation 実行
- Purview 障害時に NemakiWare の文書更新は成功すること

## 23. 実装フェーズ

### Phase 0: 技術確認

- Purview dev tenant での接続性確認
- custom type 最小定義の PoC
- `qualifiedName` / collection / batch size 方針確定
- Data Map API での 408 / 429 の発生条件確認

### Phase 1: 基盤実装

- 設定クラス
- token 取得
- API client
- 管理 API の `test-connection`
- job lock
- schema state / job state / stream cursor state の基盤

2026-03-20 状態:

- 完了

### Phase 2: type bootstrap と full sync

- type definition 適用
- business metadata definition 適用
- `nemaki_external_asset` type 定義
- repository / folder / document / type definition 同期
- sync state ドキュメント

2026-03-20 状態:

- 一部完了
- 完了済み: type bootstrap, `nemaki_repository` / `nemaki_folder` / `nemaki_document` を含む schema 適用, repository / folder / document full sync, state overview
- 未完了: type definition / archive 同期

### Phase 3: incremental sync

- change log polling
- change token 保存
- CREATED / UPDATED / SECURITY / DELETED 反映
- dead-letter 基盤
- tombstone delete resolution

2026-03-20 状態:

- 進行中
- 完了済み: change log polling, change token 保存, CREATED / UPDATED / SECURITY の document upsert, tombstone stage, `DELETE_RESOLUTION`, archive / purge 判定, delete API 呼び出し, `ARCHIVE_RECONCILIATION` による archived document / archive upsert
- 未完了: dead-letter 基盤, relationship 作成, archive / type / cloud metadata の補助 reconciliation の残り

直近の次作業:

- `ARCHIVE_RECONCILIATION` job を追加し、`ARCHIVED` tombstone を実資産へ反映する

### Phase 4: supplemental reconciliation

- type reconciliation
- archive reconciliation
- cloud metadata reconciliation
- relationship reconciliation

### Phase 5: lineage

- stable key を持つ外部対象に限定した lineage 実装
- process-only metadata fallback 実装

### Phase 6: UI と拡張

- 管理 UI
- glossary / classification / labels の拡張
- webhook assist
- 双方向同期の検討

## 24. 受け入れ条件

- Purview 接続情報を安全に保存できる
- custom types と business metadata definitions を初期化できる
- schema bootstrap が collection スコープで一度だけ適用される
- 1 repository の full sync が成功する
- change log に基づく incremental sync が成功する
- type / archive の補助リコンシリエーションが成功する
- `DELETED` event が archive と purge を誤判定せず収束できる
- Purview 障害時も NemakiWare 本体の create / update / delete は継続できる
- stable key を持つ archive / cloud sync の代表的 lineage を確認できる

## 25. リスクと未解決課題

- collection を repository ごとに分けるか、NemakiWare 共通にするか
- type definition を Purview 側でどこまで細かくモデリングするか
- relationship object を初期実装に含めるか
- glossary / classification の source of truth を Purview に置くか NemakiWare に置くか
- 数十万件以上の資産数で REST API のみで十分か
- local filesystem / ad-hoc ZIP export を将来 asset 化するか
- purge 後の historical tombstone を別 collection に残す要件が出るか

## 26. 推奨実装順

短期で価値が出る順序は以下である。

1. 接続設定 + `test-connection`
2. custom types / business metadata definitions
3. repository / folder / document の full sync
4. change log ベース incremental sync
5. type / archive reconciliation
6. lineage
7. UI

## 27. 参考情報

- Microsoft Purview Custom Connector Solution Accelerator
- Microsoft Purview Custom Types Tool
- Microsoft Purview custom types
- Microsoft Purview Data Map entity API
- Microsoft Purview custom lineage guidance
- Microsoft Purview Data Map API timeout guidance

この文書では、既存 NemakiWare の change log を主軸にしつつ、拾い切れないイベントは補助リコンシリエーションで補う設計に改めた。初稿よりも「初期実装の現実解」と「後続拡張の境界」が明確になっている。
