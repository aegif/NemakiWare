# 設計増分 A — Atlas lineage endpoint 型体系と多重AP状態遷移

status: **v2.2 draft / 実装着手 sign-off 待ち**
revision:
- v2 — §4 §6 §8 §9 を全面改訂。v1 の採番一体化案・caller 例外案・raw URI QN 案・
  `upload://` 空 input 案・`REPLAYED` 上書き案は**撤回**。撤回理由は各節に残す。
- v2.1 — 7 判断の条件を反映し、endpoint-local snapshot と shell 排除 (§2 §10)、
  file dead letter の durable spool (§8 §9)、sequencer lease/fencing 状態表 (§8)、
  replay generation の CAS 状態機械 (§8)、endpoint 件数・payload size 上限 (§2) を追加。
- v2.2 — 状態機械の内部矛盾を訂正。lease は削除せず generation high-watermark を保持、
  stale `SEQUENCING` の世代付き reclaim 遷移を追加、`VERIFYING` を状態表に完全記載、
  spool の起動影響と file 安全性契約、chunk の sequence 連番要件を**撤回**、
  oversize 時の silent attribute drop を**禁止**、catalog obligation を lineage 専用 service に確定。
  **`sequence` を「sequencer finalization order」と再定義**した (§8 冒頭)。
scope: v3.3 内で Atlas 連携を完成させるための設計。実装は sign-off 後に A〜E の独立コミットで行う。
関連: [`docs/design/acl-epoch-fencing.md`](acl-epoch-fencing.md) (同じ outbox/cursor の考え方を使う)

この文書は**設計のみ**である。ここに書かれた変更はまだ1行も入っていない。

---

## 0. なぜこの増分が要るのか

Cloudant `_id` 修正 (`21ab95782`) までは `LineageProjectionLoop` が1件も publish できず、
lineage の実挙動は誰にも見えていなかった。動き始めた結果、次が同時に露見した。

- Process の input/output が既存 entity に**結線されない** (qualifiedName 二重付与は `a81a516f9` で修正済み。
  残るのは型不一致)。
- 採番競合で event が作られず dead letter に落ちる。**実測: 未replay dead letter 93件中 92件がこれ**
  (`Failed to assign sequence number after 5 CAS retries for bedroom`)。
- projector・replay・cursor が多重AP前提になっていない。

fail-closed preflight (`e4b430584`) は差し戻した (`a37187861`)。ordered projector では
1イベントの失敗がリポジトリ単位の恒久停止になり、フォルダ export のような正常操作で投影が止まるため。
**「表現できない endpoint を弾く」のではなく「表現できるようにする」のが本増分の主旨**である。

### 採用しない案 (指示により明示的に除外)

| 案 | 不採用の理由 |
|---|---|
| `nemaki_folder` の supertype を直接 `DataSet` 系へ変更 | Atlas の型変更は破壊的。既存 entity の再作成と全 relationship の張り直しが要り、ロールバック不能。§3 の additive proxy で置き換える |
| 疎な document shell を bulk 作成して参照を埋める | catalog に実体のない entity を作る。governance の信頼性を損ない、削除・ACL・監査のいずれとも整合しない |
| cursor 通過済み event を同じ sequence の PENDING へ戻す | 同一 sequence の再投入は二重 publish か永久 PENDING のいずれかにしかならない (§8)。replay は**新しい sequence obligation を発行**する |

---

## 1. 全 LineageProcessType の producer 実態と input/output kind 対応表

実装から採取 (推測なし)。`E` = external URI、`O` = `nemaki://{repo}/objects/{id}`、
`A` = `nemaki://{repo}/archives/{id}`。

| processType | producer | inputs | outputs |
|---|---|---|---|
| `IMPORT_UPLOADED` | [ImportExportResource:455](../../core/src/main/java/jp/aegif/nemaki/rest/ImportExportResource.java#L455) | `upload://{importMode}` | `O` (**folder**) |
| `IMPORT_UPLOADED` | [IngestLineageEmitter:129](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestLineageEmitter.java#L129) | `E` | `O` (document) |
| `IMPORT_FILESYSTEM` | ImportExportResource:948 | `file://{sourceDir}` | `O` (**folder**) |
| `EXPORT_ZIP_FOLDER` | ImportExportResource:611 | `O` (**folder**) | **なし** |
| `EXPORT_SELECTED_OBJECTS` | ImportExportResource:794 | `O` × N (**document と folder が混在**) | **なし** |
| `EXPORT_FILESYSTEM` | ImportExportResource:1107 | `O` (**folder**) | `file://{targetDir}` |
| `CLOUD_SYNC_UPLOAD` | CloudDriveResource:483 | `O` (document) | `cloud://{provider}/{fileId}` |
| `CLOUD_SYNC_DOWNLOAD` | CloudDriveResource:662, :947 | `cloud://{provider}/{fileId}` | `O` (document) |
| `ARCHIVE_LOCAL` | RetentionScheduler:493, ArchiveResource:676 | `O` (document) | `A` |
| `ARCHIVE_COLD` | RetentionScheduler:591 | `A` | `cold://{storageRef}` |
| `FILE_SHARE_SYNC_DOWNLOAD` | IngestLineageEmitter:132 | `E` | `O` (document) |
| `EXTERNAL_NOTE_IMPORT` | IngestLineageEmitter:135 | `E` | `O` (document) |
| `EXTERNAL_ATTACHMENT_IMPORT` | IngestLineageEmitter:134, :137 | `E` | `O` (document) |
| `BUSINESS_RECORD_IMPORT` | IngestLineageEmitter:139 | `E` | `O` (document) |
| `CHAT_ATTACHMENT_IMPORT` | IngestLineageEmitter:138 | `E` | `O` (document) |
| `MAIL_MESSAGE_IMPORT` | IngestLineageEmitter:142 | `E` | `O` (document) |
| `MAIL_ATTACHMENT_IMPORT` | IngestLineageEmitter:141 | `E` | `O` (document) |
| `FILE_SHARE_SYNC_UPLOAD` | **producer なし** (enum のみ・RESERVED) | — | — |

### 現在の解決可能性

`Process.inputs/outputs` は `array<DataSet>`。既存 schema
([PurviewSchemaPayloadFactory](../../core/src/main/java/jp/aegif/nemaki/rest/purview/payload/PurviewSchemaPayloadFactory.java)) の supertype は:

| entity type | supertype | DataSet か |
|---|---|---|
| `nemaki_document` | `DataSet` | ✅ |
| `nemaki_archive` | `DataSet` | ✅ |
| `nemaki_external_asset` | `DataSet` | ✅ |
| `nemaki_folder` | `Referenceable` | ❌ |
| `nemaki_type_definition` | `Referenceable` | ❌ (lineage endpoint ではない) |

したがって**未解決なのは 2 種類だけ**である。想定より狭い。

1. **folder endpoint** — `EXPORT_ZIP_FOLDER` / `EXPORT_SELECTED_OBJECTS` / `EXPORT_FILESYSTEM` の input、
   `IMPORT_UPLOADED` / `IMPORT_FILESYSTEM` (ImportExportResource 経路) の output
2. **raw scheme endpoint** — `upload://` `file://` `cloud://` `cold://`。entity が存在しない

`E` (external URI) は `nemaki_external_asset` が DataSet なので**型としては解決可能**。ただし
external ingest 経路が実際にその entity を publish しているかは未確認 (§4 で規定し、E 増分で検証)。

### 併せて判明した欠落

`EXPORT_ZIP_FOLDER` と `EXPORT_SELECTED_OBJECTS` は **outputs が空**。生成された zip が lineage に現れない。
export artifact を表現しない限り「何を持ち出したか」の追跡が片側しかない。§3 で扱う。

---

## 2. typed LineageEndpoint schema

現在 endpoint は**ただの文字列**である
([LineageEventBuilder:60](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageEventBuilder.java#L60) `addInput(String)`)。
sink 側は文字列から型を推測できず、`AtlasLineageSink` は全部 `DataSet` と決め打ちしていた。
型情報は**producer が持っている**のに捨てられている。ここを直すのが起点。

```java
public record LineageEndpoint(
        EndpointKind kind,             // CMIS_DOCUMENT / CMIS_FOLDER / ARCHIVE / EXTERNAL_ASSET
                                       // / IMPORT_ARTIFACT / EXPORT_ARTIFACT / CLOUD_OBJECT / COLD_STORAGE
        String catalogQualifiedName,   // canonical。§4 の規則で生成
        String repositoryId,           // repo 内 endpoint のみ。外部は null
        String objectId,               // 同上
        String operationId,            // artifact 系。§3
        Map<String, Object> attributes // kind 別 allowlist。immutable
) {}
```

### endpoint-local snapshot (v2.1)

event-level の `snapshotAttributes` は**複数 endpoint に対応できない**。
同一 bulk で entity を完全生成するには endpoint ごとの属性が要る。

| kind | allowlist |
|---|---|
| `CMIS_DOCUMENT` | `name`, `mimeType`, `contentLength`, `versionLabel` |
| `CMIS_FOLDER` | `name`, `path` |
| `ARCHIVE` | `name`, `originalId`, `archivedAt` |
| `EXTERNAL_ASSET` | `sourceSystem`, `externalStableKey` (protected), `tenantId` |
| `IMPORT_ARTIFACT` | `importMode`, `byteLength`, `contentHash`, `originalFileName` |
| `EXPORT_ARTIFACT` | `artifactKind`, `objectCount`, `name` |
| `CLOUD_OBJECT` | `provider`, `externalStableKey` (protected) |
| `COLD_STORAGE` | `storageClass`, `externalStableKey` (protected) |

- allowlist 外のキーは `build()` で拒否する (schema にない属性が黙って捨てられる §5 の問題の再発防止)。
- `attributes` は immutable。**point-in-time 記録**であり、後から更新しない。
- 削除済み document を replay するときも、この snapshot があれば entity を再構成できる。

### document / archive endpoint の扱い

document と archive は catalog sync が publish する。lineage が同一 bulk で作り直すと
catalog sync と二重管理になるため、**同一 bulk には含めない**。代わりに publish 前に
authoritative な catalog entity の存在を確認し、無ければ obligation を作る。

**`LineageCatalogReconciliationService` を lineage 専用に新設する (v2.2 確定)。**
既存 `SearchIndexReconciliationService` は Solr / ACL / RAG の再索引が責務で、
Atlas/Purview の entity 存在確認・target 別状態・historical entity 復元とは**完了条件が違う**。
汎用化すると既存の security reconciliation に影響が及ぶため、共有しない。

```
task key : target + repositoryId + endpointKind + hash(catalogQualifiedName)

state    : PENDING
           CLAIMED(owner, token, lease)
           RESOLVED
           UNRESOLVED

outcome  : SOURCE_EXISTS       → 既存の authoritative publisher で同期させる
           SOURCE_PURGED       → endpoint snapshot (§2) から historical / tombstone entity を作る
           SOURCE_ERROR        → capped backoff で再試行
           SNAPSHOT_INCOMPLETE → durable UNRESOLVED。event を terminal 化して cursor を進める
```

- `SOURCE_PURGED` を扱えるのがこの service を分ける理由の一つ。削除済み source の
  historical entity は endpoint snapshot からしか作れない (§2)。
- `SearchIndexReconciliationService` と共有するのは **CAS task / lease / backoff の低レベル部品だけ**。
  service と operation namespace は共有しない。
- obligation が `PENDING` / `CLAIMED` の間、当該 event は `PENDING` のままにし cursor は進めない。
  `UNRESOLVED` で terminal 化したときだけ cursor を進める。

### 件数・サイズ上限 (v2.1)

`EXPORT_SELECTED_OBJECTS` は選択件数だけ endpoint が並ぶ。無制限は Atlas payload と
CouchDB document の双方を壊す。

| 制限 | 既定 | 超過時 |
|---|---|---|
| 1 event あたりの endpoint 数 | 1,000 | **chunk する**。`operationId` を共有し `chunkIndex` / `chunkCount` を持つ複数 event に分割 |
| 1 event の payload size | 1 MiB | 同上 |
| chunk 間の順序 | — | **sequence の連番は要求しない** (下記) |
| 1 endpoint が単独で上限超過 | — | durable `UNRESOLVED_OVERSIZE`。**属性を黙って落とさない** (下記) |

**chunk の sequence 連番要件は撤回する (v2.2)。** repository 内では他の event と並行して
finalize されるため、batch reservation なしに連番にはならない。§8 の定義どおり
`sequence` は finalization order でしかない。再構成は `operationId` / `chunkIndex` / `chunkCount`
の 3 つで行い、部分適用 (chunk 単位の PUBLISHED) を許容する。

**oversize 時の silent attribute drop を禁止する (v2.2)。**
必須属性を黙って落とすと E-17 の shell 判定と矛盾し、「shell ではない」ことを検証できなくなる。

| 規則 | 内容 |
|---|---|
| 事前上限 | endpoint 属性ごとに最大長を**事前に固定**する (allowlist と同じ表に持つ) |
| optional 表示値 | 上限超過は truncate + 元値の SHA-256 を併記する (`nameTruncated=true`) |
| 必須値 | `externalStableKey` などの protected stable key と kind 別必須属性は**落とさない** |
| 単独超過 | 1 endpoint だけで上限を超えるなら、その event を durable `UNRESOLVED_OVERSIZE` (terminal) にし、reason と endpoint hash を残す。cursor は進める |
| 禁止 | silent attribute dropping は**いかなる場合も行わない** |

上限値は `lineage.endpoint.max-per-event` / `lineage.event.max-payload-bytes` で設定可能にする。

**schemaVersion を 1 → 2 に上げる**。旧形式は §6 で扱う。

---

## 3. folder 用 additive DataSet proxy 設計

`nemaki_folder` の supertype は変えない (採用しない案)。代わりに **folder ごとに DataSet 側の相棒を作る**。

```
nemaki_folder_dataset   superTypes: [DataSet]
  qualifiedName : nemaki://{repo}/folders/{objectId}/dataset
  name          : (folder 名。DataSet が name を持つので保持できる)
  folderRef     : nemaki_folder への relationship (1:1)
```

- 既存 `nemaki_folder` は**一切変更しない**。additive に新 type と 1:1 relationshipDef を足すだけなので、
  schema apply は既存 entity を触らない。
- catalog sync が folder を publish するとき、`nemaki_folder` と `nemaki_folder_dataset` を
  **同一 bulk で**作る。片方だけ存在する状態を作らない。
- lineage の folder endpoint は `nemaki_folder_dataset` の qualifiedName を参照する。
  `Process.inputs` の型制約を満たしつつ、UI/governance の folder 実体は従来どおり `nemaki_folder`。
- 「疎な shell を作る」案との違い: proxy は**実在する folder に 1:1 で対応し、同時に作られ、同時に消える**。
  実体のない参照を埋めるための空 entity ではない。

### proxy の lifecycle (v2 追加)

「同一 bulk だから片方だけ存在しない」とは**断言しない**。Atlas の bulk は partial success を返しうる。

| 事象 | proxy の扱い |
|---|---|
| folder 作成 | `nemaki_folder` と proxy を同一 bulk。**応答を検査し**、片方欠落なら reconcile キューへ |
| folder rename | proxy の `name` を更新。QN は objectId 由来なので不変 |
| folder move | proxy は不変 (QN も name も親に依存しない)。`nemaki_folder` 側の path 属性のみ更新 |
| folder delete (archive 行き) | proxy は**残す**。過去の lineage が参照しているため。`active=false` / `sourceState=ARCHIVED` |
| folder restore | `active=true` / `sourceState=ACTIVE` に戻す |
| archive purge | proxy は**残す**。`active=false` / `sourceState=PURGED` — 正常な履歴保持であり orphan ではない |
| 真の orphan | proxy はあるが folder が**存在した記録もない** (注入・障害由来)。`sourceState=ORPHAN` として区別し、レポートに出す |
| GC / retention | `sourceState=PURGED` かつ**参照する Process が 0 件**かつ `lineage.retention.days` 経過で削除可。参照が 1 件でもあれば削除しない |
| 既存 folder の backfill | **authoritative full-sync が必須**。B 増分の完了条件に含める |
| orphan reconciliation | proxy はあるが folder がない / その逆を定期照合し、metric とレポートに出す |

backfill は「全 folder を列挙して proxy の有無を確認し、無ければ作る」を冪等に回す。
`full-sync` の一部として実装し、単独でも起動できるようにする。

### import / export artifact (v2)

v1 は `upload://` を endpoint から外して snapshotAttribute にするとしていた。**撤回する。**

理由: `computeEventKey` は `repositoryId:processType:inputsHash:outputsHash` である
([LineageEvent:128](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageEvent.java#L128))。
input を空にすると、**同じ folder への2回目以降の import が1回目と同一 eventKey になり、
`append()` の冪等判定で黙って捨てられる**。lineage が消えるだけでなく、
「同じ folder に何度 import したか」が原理的に表現できなくなる。

```
nemaki_import_artifact   superTypes: [DataSet]
  qualifiedName    : nemaki://{repo}/imports/{operationId}
  name             : originalFileName (取得できる場合)
  importMode       : zip-upload | filesystem | ...
  byteLength       : long
  contentHash      : SHA-256 (取得できる場合)

nemaki_export_artifact   superTypes: [DataSet]
  qualifiedName    : nemaki://{repo}/exports/{operationId}
  name             : エクスポート名
  artifactKind     : ZIP | FILESYSTEM
  objectCount      : int
```

- `upload://` 文字列は**廃止**。`IMPORT_UPLOADED` の input は `nemaki_import_artifact`、
  output は folder proxy。
- **`operationId` は内部契約として必須。公開 API の必須パラメータにはしない。**
  現状 `.runId(...)` を設定している producer は**存在しない** (実測 0 件)。v1 の
  `/exports/{runId}` は全 event が空値へ衝突する設計だった。
  - サーバーが業務処理の**開始時に UUID を発行**する。既存 import/export API のリクエスト形式は変えない。
  - streaming 開始前に生成し、完了時の lineage event まで同一値を持ち回る。
  - response header (`X-Nemaki-Operation-Id`) と body に返し、追跡可能にする。
  - client 供給の `Idempotency-Key` は**将来の任意機能**とし、本増分では実装しない。
- artifact entity は lineage publish と同一 bulk で作る (CMIS オブジェクトではないため catalog sync 対象外)。

### eventKey を SHA-256 にする (v2)

現行は 32bit Java hash 2つの連結で、衝突すると別操作が同一イベント扱いになる。

```
idempotencyKeyVersion = 2
eventKey       = "v2:" + SHA-256(repositoryId + "\u0000" + processType + "\u0000" +
                                 canonical(inputs) + "\u0000" + canonical(outputs) + "\u0000" +
                                 operationId + "\u0000" + schemaVersion)
legacyEventKey = (v1 からの写像・repair 時のみ保持。v1 の 32bit hash 形式)
```

`canonical(...)` は §4 の canonical QN を辞書順に連結したもの。`operationId` を含めることで、
同一 endpoint 集合の反復操作が別 event になる。

- **`idempotencyKeyVersion` を明示的に永続化する。** v1 と v2 の eventKey を暗黙に同一視しない。
  冪等判定は `(idempotencyKeyVersion, eventKey)` の対で行う。
- **v1 の replay / repair は既存 Process を更新しない。** v2 の補償 event を新設し、
  その Process QN は v2 の eventKey 由来になる。v1 の Process はそのまま残る (監査事実)。
  結果として同一業務操作に v1/v2 二つの Process が並ぶことを許容する
  (`replayOf` / `legacyEventKey` で対応関係を追える)。
- **補償 event は元の `operationId` を維持する。** 業務操作は同一だから。
  区別は `replayGeneration` が担う。

## 4. external asset canonical QN 規則 (v2)

### v1 案の撤回

v1 は `cloud://` `file://` `cold://` を**そのまま qualifiedName**にするとしていた。撤回する。

既存の catalog sync は既に repo-scoped の canonical QN を持っている:

```java
// PurviewEntityPayloadFactory.buildExternalAssetQualifiedName
"nemaki://" + repositoryId + "/external-assets/"
    + Base64.getUrlEncoder().withoutPadding().encodeToString(stableKey.getBytes(UTF_8))
```

raw URI を QN にすると、**同一の external asset が Purview 既存経路と journal 経路で別 entity に割れる**。
さらに raw QN は repository namespace を持たないため §7 の cross-repository 禁止とも矛盾する。
`file://` の絶対パスをそのまま QN にすると、ホスト構成が catalog の主キーとして永続化される。

### v2 規則

**すべての external asset は repo-scoped canonical QN に統一する。**

```
qualifiedName = nemaki://{repositoryId}/external-assets/{base64url(stableKey)}
```

`stableKey` は endpoint kind ごとに決める。

| kind | stableKey |
|---|---|
| `EXTERNAL_ASSET` (ingest) | `ExternalSourceUri.build(...)` の出力 |
| `CLOUD_OBJECT` | `cloud://{provider}/{fileId}` |
| `COLD_STORAGE` | `cold://{storageRef}` |
| `FILESYSTEM_PATH` | `file://{正規化パス}` |

- raw URI は QN にはせず、`externalStableKey` 属性として entity に保持する。
- **`externalStableKey` は protected 扱い**とし、ログ・エラーメッセージ・dead letter reason に
  そのまま出さない。出力する場合は SHA-256 の先頭 12 桁のみ。`file://` の絶対パスと
  cloud の識別子が対象。
- canonical 化は `LineageEndpointCatalog` (新規) の1箇所。既存 `PurviewEntityPayloadFactory` の
  メソッドをそこへ移し、catalog sync と journal の両方が同じ実装を通る (§5)。

### `upload://` の扱いは §4 から §3 へ移した

v1 の「`upload://` を endpoint から外して snapshotAttribute にする」は撤回。理由は §3 を参照。

## 5. Atlas / Purview 共通 payload factory 境界

現状は三者が別々に payload を組んでいる。

| クラス | 役割 | 問題 |
|---|---|---|
| [PurviewSchemaPayloadFactory](../../core/src/main/java/jp/aegif/nemaki/rest/purview/payload/PurviewSchemaPayloadFactory.java) | 型定義 | — |
| [PurviewEntityPayloadFactory](../../core/src/main/java/jp/aegif/nemaki/rest/purview/payload/PurviewEntityPayloadFactory.java) | catalog sync の entity | schema にない属性 (`name` 等) を送り、Atlas に捨てられている |
| [AtlasLineageSink.buildAtlasPayload](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/AtlasLineageSink.java) | lineage の Process | 型・QN 規則を独自に持っていた (今回の bug の温床) |

境界を次のように引く。

- **`CatalogPayloadFactory` (新規・唯一の payload 生成点)**
  - `entityFor(LineageEndpoint)` — endpoint → Atlas entity reference (typeName + uniqueAttributes)
  - `processFor(LineageEvent)` — Process entity
  - `entityFor(Content)` — catalog sync の document/folder/folder_dataset
- **schema と payload の整合を型で縛る**。`PurviewSchemaPayloadFactory` が宣言した属性集合を定数化し、
  payload factory はその集合外の属性を組み立てられないようにする (`name` が黙って捨てられる現状の再発防止)。
- `AtlasLineageSink` は HTTP と retry だけを持ち、payload を組まない。
- Purview backend も同一 factory を使う。両者の差は endpoint URL と認証のみ。

---

## 6. legacy event migration (v2)

`schemaVersion` 1 の event が journal に残る。**書き換えない**。

reader (`CouchLineageEvent.toLineageEvent`) が v1 を読むとき、文字列 endpoint を
`LineageEndpoint` へ推測変換する: `nemaki://{repo}/objects/{id}` は CouchDB を引いて
document/folder を判定、`nemaki://{repo}/archives/` は ARCHIVE、scheme 付きは §4 の
canonical QN へ写像、判定不能は `UNRESOLVED`。

### v1 案の撤回: 単に SKIPPED にして進めない

v1 は「`UNKNOWN` を含む v1 event は `SKIPPED` にして cursor を進める」としていた。撤回する。
それでは**有効な履歴が静かに消える**。terminal にすること自体は正しいが、記録を残さねばならない。

### v2

`UNRESOLVED` を含む v1 event は:

1. publish しない。target 状態は `UNRESOLVED` (新 terminal) にし、cursor は進める
   (ordered projector を止めないため)。
2. **durable unresolved 記録**を `nemaki_lineage` に残す。dead letter と同じ耐久性で保持する。
   - `reason` — なぜ写像できなかったか
   - `endpointHash` — 該当 endpoint の SHA-256 先頭 12 桁 (**raw は残さない**。§4 の protected 契約)
   - `blockingObjectId` — 判定に失敗した CMIS object id (存在する場合)
   - `schemaVersion` / `occurredAt` / `processType`
3. metric `lineage.unresolved.count{repo,processType}` を出し、閾値でアラート可能にする。
4. §9 の repair API の対象に含める。後日 endpoint が解決可能になれば補償 event を作れる。

新規 event は必ず v2。v1 は retention で自然に消える。migration patch は書かない。

## 7. cross-repository 方針

**cross-repository lineage は認めない。**

- `LineageEvent.repositoryId` と全 endpoint の `repositoryId` が一致することを
  `LineageEventBuilder.build()` で検証し、違反は `IllegalArgumentException`。
- 外部 endpoint (external / cloud / cold / file) は repositoryId を持たないので対象外。
- 検証は builder だけでは足りない。**store・legacy reader・sink の3箇所でも再検証**する。
  CouchDB に直接注入された event や v1 からの写像が builder を通らないため、
  **sink の直前 (publish 呼び出しの手前) が最後の関門**になる。違反は publish せず
  `REJECTED` (terminal) + durable 記録 (§6 と同じ形式)。
- 現在の E2E は event に合成 repo、endpoint に `bedroom` を使っており**この規則に違反する**。
  E 増分で「同一 repository 内で完結する fixture」に直す。tenant 境界の確認テストを追加する。
- 将来 cross-repo を認める場合は、両 repository の認可確認と専用監査イベントを設計してから。
  本増分では**禁止を固定する**。

---

## 8. sequence / status / cursor / replay の多重AP状態遷移 (v2.2)

### `sequence` の定義 (v2.2)

**`sequence` は sequencer finalization order を表す。** それ以上のことは保証しない。

- domain commit の全順序は**保証しない**。spool へ落ちた event は、後から別 AP が作った event より
  遅く finalize されうる。
- spool を跨ぐ厳密な因果順序も**保証しない**。
- `occurredAt` は AP ローカル時計であり、**表示・監査用**。fencing や順序判定の根拠にしない。
- 厳密な domain commit 順序が要るなら、各業務 DB と同一トランザクションの outbox が必要になり、
  本増分の範囲を超える。

読み手 (projector / cursor / UI) はこの定義にのみ依存する。

### 現在の欠陥 (実装で確認済み)

| # | 箇所 | 内容 |
|---|---|---|
| 8-a | [CouchLineageJournalStore:257](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/CouchLineageJournalStore.java#L257) `append()` | 採番 → event 作成の順。crash すると番号だけ焼ける。CAS 5回失敗で event を作らず dead letter → **実測 92件** |
| 8-b | CouchLineageJournalStore:351 `updatePublishStatus` | 期待旧状態も claim token も検証しない。逆行と二重 publish が可能 |
| 8-c | [LineageProjectionLoop:270](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageProjectionLoop.java#L270) | `PUBLISHED` の永続化結果を確認せず `advanceCursor` |
| 8-d | LineageJournalController:195 replay | `PROJECTING` も cursor 通過済みも PENDING へ戻せる |

### 8-a v1 案の撤回

v1 は「採番と event 作成を1つの CouchDB 書込みにする」としていた。**成立しない。**

- `lineage_seq:{repo}` と event document は**別文書**であり、CouchDB では原子的に更新できない。
- v1 が提案した `_id = lineage_event:{repo}:{seq}:{eventId}` は、AP ごとに `eventId` が異なるため
  **同じ sequence でも別 `_id` になり 409 が起きない**。重複 sequence がそのまま作られる。

### 8-a v2: event-first UNSEQUENCED + fenced finalizer

```
1. eventKey 由来の決定的 _id で、完全な payload を state=UNSEQUENCED として1書込みで作成
   (409 = 既存 → 冪等に成功)
2. per-repository の fenced sequencer が UNSEQUENCED を occurredAt 順に1件ずつ claim
   (claim token = fencing token。ACL-epoch の lease/fencing と同じ形)
3. counter を CAS で払い出す
4. claim token が一致するときだけ event に sequence を確定 (CAS)
5. 確定後に次の event へ進む
```

### sequencer lease / fencing 状態表 (v2.1)

```
_id        : lineage_sequencer_lease:{repositoryId}
generation : long  — 単調増加。取得のたびに +1
owner      : nodeId
expiresAt  : ISO8601
```

| 操作 | 条件 | 効果 |
|---|---|---|
| acquire | lease 不在 または `expiresAt < now` | `generation+1` で CAS 取得。失敗は他ノードに譲る |
| renew | `owner` 一致 かつ `generation` 一致 | `expiresAt` 延長を CAS。**失敗したら fence latch を落とし、以後この世代では一切書かない** |
| release | `owner` 一致 かつ `generation` 一致 かつ `_rev` 一致 | `owner=null` / `expiresAt=過去` に CAS。**`generation` は維持し、document は削除しない** |

**lease document を削除してはならない。** 削除すると generation の high-watermark も消え、
次の acquire が古い generation を再利用できてしまう。そうなると old leader の
`sequencerGeneration` と一致してしまい、fencing が成立しない。
削除操作は API・管理経路のいずれからも**拒否**する (存在チェックを伴う CAS のみ許す)。

event の claim / 確定は必ず `(generation, owner)` を伴う CAS:

```
claim   : expected state=UNSEQUENCED, _rev 一致
          → state=SEQUENCING, sequencerGeneration=G

reclaim : expected state=SEQUENCING, sequencerGeneration=G_old, _rev 一致
          かつ G_old < 現 lease の generation (= old lease は期限切れ)
          → state=SEQUENCING, sequencerGeneration=G_new
          ※ old leader が停止した event はこの遷移でしか回収できない。
            claim (UNSEQUENCED からのみ) では拾えない

finalize: expected state=SEQUENCING, sequencerGeneration=G, _rev 一致
          → state=SEQUENCED, sequence=N
```

**old leader 復活系列の証明**

1. old leader (G=5) が counter から N を払い出した直後に stall。
2. lease 期限切れ → new leader (G=6) が acquire。
3. new leader は同じ event を claim し (`sequencerGeneration` を 6 に上書き)、N+1 を払い出して確定。
4. old leader が復帰し finalize を試みる → expected `sequencerGeneration=5` が現在値 6 と不一致 → **CAS 失敗**。
   さらに renew 失敗で fence latch が落ちているため、そもそも書込みを発行しない。
5. N は消費されたまま使われない (gap)。**gap は許容する** (I-1〜I-4)。

**crash 再開規則**

| crash 位置 | 再開時の扱い |
|---|---|
| event 作成前 | 何も起きていない。file spool (§8-a) に payload が残るのみ |
| `UNSEQUENCED` 作成後 | scanner が拾い、通常経路で claim される |
| counter 払い出し後・`SEQUENCED` 確定前 | event は **`SEQUENCING(G_old)`** で残る。番号は捨てられる (gap)。new leader が **reclaim 遷移**で `SEQUENCING(G_new)` にしてから再度払い出す |
| `SEQUENCING` 中に crash | lease 期限切れ後、new leader が **reclaim 遷移**で `sequencerGeneration` を上書き |
| `SEQUENCED` 確定後 | 通常の projection 経路へ |

**per-repository ordering と公平性**

- sequencer は repository ごとに 1 つ。`occurredAt` 昇順、同値は `_id` 昇順で決定的に処理する。
- scanner は bookmark で再開し、1 pass あたりの処理件数に上限を置く
  (`lineage.sequencer.batch-size`)。
- UNSEQUENCED backlog が `lineage.sequencer.backlog-cap` を超えたら metric とアラートを出す。
  **処理は止めない** (止めると回復しないため)。repository 間はラウンドロビンで公平に回す。

**不変条件はこれである。「gap-free」は要件ではない。**

| # | 不変条件 |
|---|---|
| I-1 | event を保存する前に sequence を消費しない |
| I-2 | 一度可視化した高 sequence の後から低 sequence が出現しない |
| I-3 | sequence 未確定 event は、**依存先 (CouchDB・counter) が健全で公平に再試行される条件下で** eventual に finalize される。CouchDB 障害中や counter 破損中は成立しない (I-4 が優先) |
| I-4 | counter の消失・巻き戻りは **fail-closed** (確定を止める。event は UNSEQUENCED のまま残る) |

**counter 巻き戻し / 消失時の復旧手順 (v2.1)** — 自動 seed はしない。

1. sequencer を停止する (lease を release し、以後 acquire を拒否する管理フラグを立てる)
2. finalized event の最大 sequence と、全 target の cursor 値から
   repository ごとの **high-watermark** を調べる
3. counter を `max(high-watermark) + 1` へ**管理操作で**復旧する
4. **counter 欠落を自動 seed しない。** 0 や 1 から再開すると I-2 を破る
5. 復旧後に sequencer を再開し、UNSEQUENCED scanner を再開する
6. `lineage.sequencer.health` を health endpoint と metric に出す
   (`FENCED_OK` / `COUNTER_MISSING` / `COUNTER_REWOUND` / `STOPPED`)

- counter だけ進んで crash した場合の gap は**許容する**。読み手は sequence の連続性に依存しない。
- 古い sequencer は fencing token の世代比較で確定不能にする。

### 8-a v2: 採番枯渇時も fail-open を維持

v1 の「caller へ例外」は**撤回**する。既存契約に反する:

- [JournaledLineageEmitter:71](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/JournaledLineageEmitter.java#L71)
  に `// Fail-open: never block the business operation` があり、例外は捕捉して
  `LineageDeadLetterSink` (ファイル) へ落としている。
- emit 時点で import / export / archive / cloud 操作は**既に commit 済み**。ここで例外を返すと
  利用者は業務操作そのものが失敗したと誤認し、再実行による**重複 import / 重複 archive** を招く。

v2 の挙動:

| 状況 | 挙動 |
|---|---|
| event document を作れた | 業務レスポンスは成功。UNSEQUENCED で durable に保持。sequencer が後で確定 |
| event document 自体を作れない | 業務レスポンスは成功のまま。**durable spool** へ (下記) |
| いずれの場合も | metric `lineage.unsequenced.count` / `lineage.deadletter.count` を出しアラート必須 |
| 回収 | scanner が UNSEQUENCED を、spool scanner が spool を回収する |

### durable spool (v2.1) — 既存 file dead letter では回収できない

既存 `LineageDeadLetterSink.record` は **SLF4J logger への 1 行 JSON 出力**であり
([LineageDeadLetterSink:100](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageDeadLetterSink.java#L100))、
CouchDB store への保存は `// Store persistence is best-effort; log file is the primary record` と
明記された副次経路である。したがって:

- AP ローカルであり、container 置換で失われうる
- rotation で消える
- ACK / claim 状態を持たない
- §9 の API から**列挙できない**
- CouchDB 停止時は CouchDB 側 store にも書けない

「scanner + repair で回収」は**現状のままでは成立しない**。v2.1 では専用 spool を設計する。

```
{lineage.spool.dir}/{repositoryId}/{yyyyMMdd}/{eventKey}.json     ← payload (完全)
{lineage.spool.dir}/{repositoryId}/{yyyyMMdd}/{eventKey}.ack      ← ACK marker
```

#### 起動時の扱い (v2.2) — Core 全体は落とさない

「volume 未設定なら起動時 fail-closed」を Core 全体の起動失敗と読むと、
`LineageEmitter` の fail-open 契約と矛盾する。範囲を限定する。

| 構成 | 挙動 |
|---|---|
| `lineage.mode=disabled` | spool 不要。何も検査しない |
| `journaled` / `direct` かつ spool 未設定・書込み不能 | **lineage subsystem を `NOT_READY` にして無効化**。Core / CMIS 本体は起動を継続する。health と metric に出す |
| `lineage.spool.strict=true` (明示設定時のみ) | Core の起動を拒否する。strict 運用を望む環境向け |

#### file 安全性契約 (v2.2)

| 項目 | 設計 |
|---|---|
| path | `repositoryId` を**そのまま path に使わない**。safe encode (URL-safe base64) + hash を使う |
| 権限 | directory `0700` / file `0600` |
| symlink | **追わない** (`NOFOLLOW`)。spool 配下に symlink があれば異常として拒否 |
| temp file | **同一 directory 内**に作る (rename が atomic であることを保証するため) |
| 書込み | file を `fsync` → atomic rename → **parent directory も `fsync`** |
| 冪等 | 既存 spool file があるときは、payload digest が一致する場合のみ成功扱い。不一致は異常として別名で退避 |
| サイズ上限 | `lineage.spool.max-file-bytes` / `lineage.spool.max-bundle-bytes` |
| ACK | `.ack` も `fsync`、または `pending` → `acked` の atomic rename |

| 項目 | 設計 |
|---|---|
| 内容 | repair 可能な**完全 payload**。`externalStableKey` を含む |
| 権限 | spool ディレクトリは運用者のみ読める権限に置く。通常ログには**出さない** |
| 通常ログ | `eventKey` と endpoint hash (SHA-256 先頭12桁) と reason のみ。raw URI・パスは出さない |
| scanner | spool を走査し、CouchDB 復旧後に §8-a の event-first append を再実行。成功で `.ack` を書く |
| ACK 済み | `lineage.spool.retention.days` 経過で削除 |
| 列挙 | **node-local の admin API のみ**。集約 endpoint は v3.3 では**実装しない** (node discovery と routing が未設計のため) |
| node 可視化 | `nodeId` を health / inventory に公開し、各 node の永続 volume を運用対象として明示する |
| node 間移送 | **署名付き DLQ bundle の upload repair API** を移送手段とする。別 node の spool を運用者が bundle 化して投入する |

#### 署名付き bundle の契約 (v2.2)

| 項目 | 要件 |
|---|---|
| 署名 | HMAC-SHA256。`lineage.repair.bundle.hmac-key` |
| key ID | bundle header に `keyId`。鍵ローテーションに対応する |
| 期限 | bundle header の `expiresAt` を超えたものは拒否 |
| nonce | 一度使った nonce は再利用不可 (CouchDB に短期記録し CAS) |
| 上限 | 最大件数 `max-entries` / 最大サイズ `max-bundle-bytes` |
| 展開 | **zip bomb 拒否** (展開後サイズ上限と圧縮比上限)、**path traversal 拒否** (`..` / 絶対 path / symlink) |
| 監査 | 誰が・どの keyId で・何件を投入したかを audit log に記録 |

既存 `LineageDeadLetterSink` は**残す** (メトリクスと可観測性のため) が、
repair の入力源としては spool を正とする。

### 8-b: 状態遷移を CAS + claim lease にする

`updatePublishStatus(eventId, target, expected, next, claimToken)`。許可する遷移だけを持つ:

```
PENDING     → PROJECTING  (claim token 発行 + lease 期限)
PROJECTING  → VERIFYING   (Atlas POST 成功。同一 claim token)
VERIFYING   → PUBLISHED   (verify 成功。同一 claim token)
VERIFYING   → VERIFYING   (retryable read lag。同一 claim token + lease renew)
VERIFYING   → FAILED      (semantic mismatch、または verifyMaxAge 超過。同一 claim token)
PROJECTING  → FAILED      (POST 自体の失敗。同一 claim token)
FAILED      → PROJECTING  (claim token 再発行)
PENDING     → DISCARDED   (管理操作のみ)
FAILED      → DISCARDED   (管理操作のみ)
PROJECTING  → FAILED      (reaper: lease 期限切れ かつ 同一 claim token のみ)
VERIFYING   → FAILED      (reaper: lease 期限切れ かつ 同一 claim token のみ)
(v1 legacy)  → UNRESOLVED  (§6)
(cross-repo) → REJECTED    (§7)
PUBLISHED / DISCARDED / UNRESOLVED / REJECTED = terminal。**いかなる遷移も出ない**
```

### `VERIFYING` の滞留防止 (v2.2)

`VERIFYING` は publish retry 回数を消費しない。だからこそ**永遠に残らない別の上限**が要る。

| 設定 | 既定 | 意味 |
|---|---|---|
| `lineage.verify.timeout` | 30s | 1 回の verify poll の総 deadline |
| `lineage.verify.interval` | 2s | poll 間隔 |
| `lineage.verify.max-age` | 10m | **`VERIFYING` に入ってからの絶対上限**。超えたら `FAILED` |

- `VERIFYING` 中も lease を renew する。renew に失敗したら fence latch が落ち、以後書かない。
- stale `VERIFYING` (lease 期限切れ) は reaper が**同一 claim token でのみ** `FAILED` にする。
- metric: `lineage.verifying.count{repo,target}` と `lineage.verifying.oldest.age`。

v1 の `* → DISCARDED` / `* → SKIPPED` は広すぎたので撤回。terminal からの逆行を禁止し、
reaper は**期限切れかつ同一 claim token** のものだけを操作する。

### 8-c: cursor は単調 CAS

- `updatePublishStatus` が 0 を返したら cursor を進めず、その repository の処理を止めて次 poll に委ねる。
- cursor 更新自体も `max(existing, incoming)` の CAS にする。並行 AP が古い値で巻き戻すのを防ぐ。

### 8-d v2: replay は durable compensation outbox

v1 の「元 event を `REPLAYED` にする」は**撤回**。実際に `PUBLISHED` だったという監査事実を失い、
multi-target event にも適合しない。

```
元 event:
  publishStatusByTarget          — 不変 (監査事実として保持)
  replayRequestsByTarget[target] — REQUESTED → CREATED → ACKED

補償 event:
  _id       = deterministic: hash(originalEventId, target, replayGeneration)
  replayOf  = originalEventId
  publishStatusByTarget = { 要求された target のみ PENDING }
  sequence  = 新規に払い出す (8-a と同じ経路)
```

### replay generation の CAS 状態機械 (v2.1)

2 AP が同時 replay したときの generation 払い出しを固定する。

```
1. original event に target 単位の REQUESTED record を CAS 作成
   expected: replayRequestsByTarget[target] が不在 または 直前が ACKED
   効果    : { generation = 直前+1, requestId = UUID, state = REQUESTED }
   CAS 失敗: 409。既に進行中の request がある
2. record が generation と requestId を所有する (以後この 2 つは不変)
3. 補償 event を決定的 ID で create-if-absent
   _id = hash(originalEventId, target, generation)
   409 = 既存 → 成功として扱う (冪等)
4. 補償 event を reread し、payload が期待と一致することを確認
   不一致 = 別 request が同じ ID を使った異常 → REQUESTED を FAILED にして 500
5. original の record を REQUESTED → CREATED → ACKED へ CAS
6. crash scanner が未 ACK の request を回収し、3 から再開する
```

- **同時要求は片方を 409 にする。** 同一 generation を複数要求が共有しない。
  進行中 request がある間は新規 replay を受け付けない。
- 直前が `ACKED` なら次の generation で再 replay できる。
- 決定的 ID により、**作成後 ACK 前に crash しても同じ ID を再利用して二重生成しない**。
- `PROJECTING` の replay は**拒否** (409)。claim を横取りするため。stale claim は reaper が
  `FAILED` に落としてから replay 可能になる。

## 9. 既存 dead letter の repair 方針 (v2)

実測: 未replay dead letter 93件。うち 92件が
`Failed to assign sequence number after 5 CAS retries for bedroom` (8-a)、1件が retry-age-exceeded。
processType 内訳は `EXPORT_ZIP_FOLDER` 39 / `IMPORT_UPLOADED` 22 /
`FILE_SHARE_SYNC_DOWNLOAD` 22 / `EXPORT_FILESYSTEM` 8 / `IMPORT_FILESYSTEM` 2。
このうち original event が存在しない (採番前に落ちた) ものが 70 件。

8-a v2 を入れれば**この失敗様式は今後発生しない** (event が先に durable になるため)。
既存分は repair で回収する。

### repair API

```
POST /api/v1/admin/lineage-journal/repair?dryRun=true
     &repositoryId={repo}&processType={type}[&occurredBefore=...]
  → { candidateCount, repositoryId, processType, digest, revision,
      confirmationToken, expiresAt }

POST /api/v1/admin/lineage-journal/repair
     &confirmationToken={token}
  → 実行。token が示す対象集合とだけ一致する場合のみ受理
```

- **dry-run を「先に呼ぶべき」ではなく構造的に必須にする。** 実行 API は
  `confirmationToken` を必須引数とし、token は dry-run が発行する。

  | token 要件 | 設計 |
  |---|---|
  | 形式 | **opaque なサーバー保存型**。内容は CouchDB の短命 document。client には ID のみ返す |
  | bind 対象 | 発行 admin の principal、`repositoryId`、`processType`、対象集合の digest・revision・count |
  | 使用回数 | **1 回限り**。実行時に未使用 → 使用済みを **CAS** で遷移させる |
  | TTL | `lineage.repair.token.ttl` (既定 **5 分**)。設定可能。期限切れは利用不能 |
  | 不一致 | 実行時に digest / revision / count を再計算し、一致しなければ **409** → 再 dry-run を要求 |
  | 保護 | CSRF 検証必須 (`X-Requested-With`)。発行・使用の両方を audit log に記録 |
- **範囲指定必須**: `repositoryId` と `processType` は省略不可。`replay-all` の轍を踏まない。
- **冪等 repair ID**: 補償 event の `_id` は `hash(deadLetterId, "repair", repairGeneration)`。
  同じ dead letter を二度 repair しても event は増えない。
- **並行実行防止**: repository 単位の repair lock を取る。既存の
  `PurviewLockStateService` と同じ形。lock は TTL 付きで、期限切れは reaper が回収する
  (今回のセッションで実際に orphan lock が1時間残った事象があるため、TTL を必須にする)。
- **監査**: 誰が・いつ・どの token で・何件を repair したかを audit log に残す。
  dry-run も記録する。
- 元 dead letter は削除しない。`repairedAt` と新 eventId を持って terminal になる。
- endpoint は §6 と同じ写像を通す。`UNRESOLVED` を含むものは publish せず durable unresolved にする。
- `occurredAt` は元の値を保持する。時系列は復元される。

## 10. 決定的並行 IT と Atlas-enabled E2E 受入表

### 決定的並行 IT (実 CouchDB、`*IT.java`、専用 CI ジョブ)

| # | 検証 | 期待 |
|---|---|---|
| IT-1 | 2 AP が同時に append × 各 100 件 | **重複 sequence なし・後着低値なし・全 200 件が最終可視**。穴の有無は要件にしない (§8 I-1〜I-4) |
| IT-2 | append 中に AP を kill | event なしで sequence を消費しない (I-1)。UNSEQUENCED は次の sequencer が回収する (I-3) |
| IT-3 | 2 AP が同一 event を同時に claim | 片方だけ成功。claim token 不一致の書込みは 0 件 |
| IT-4 | `PUBLISHED` 後に `PROJECTING` を試みる | 拒否。状態は `PUBLISHED` のまま |
| IT-5 | `updatePublishStatus` を失敗注入 | cursor が進まない。次 poll で再試行される |
| IT-6 | cursor 通過済み event を replay | 新 sequence の補償 event ができる。**元の `publishStatusByTarget` は不変**、`replayRequestsByTarget` だけ進む |
| IT-7 | `PROJECTING` を replay | 拒否 (409) |
| IT-8 | dead letter repair × 70 件相当 | 全件が新 sequence で可視。二重生成なし |
| IT-9 | 補償 event 作成後・ACK 前に kill | 再実行が同じ決定的 ID を再利用し、event は増えない |
| IT-10 | counter を巻き戻す / 消す | fail-closed。確定が止まり UNSEQUENCED が残る。誤った sequence を確定しない |
| IT-11 | cursor を古い値で並行更新 | `max(existing, incoming)` CAS により巻き戻らない |
| IT-12 | lease 期限切れの `PROJECTING` を別 claim token で操作 | 拒否。reaper のみが同一 token で `FAILED` にできる |
| IT-13 | old leader が counter 払い出し後に復活し finalize を試みる | `sequencerGeneration` 不一致で CAS 失敗。new leader の確定が生き残る |
| IT-14 | 2 AP が同時 replay | 片方が 409。generation は 1 つだけ払い出される |
| IT-15 | 補償 event 作成後・ACK 前に kill → scanner 再開 | 同じ決定的 ID で create-if-absent。payload reread が一致し ACK される |
| IT-16 | CouchDB 停止中に emit → 復旧 | 業務レスポンスは成功。spool に完全 payload。復旧後 spool scanner が append し `.ack` を書く |
| IT-17 | release 後に再 acquire | `generation` が**必ず増える** (high-watermark が保持されている) |
| IT-18 | lease document の削除を試みる | 拒否される |
| IT-19 | stale `SEQUENCING(G_old)` を new leader が回収 | reclaim 遷移で `SEQUENCING(G_new)` になり finalize される |
| IT-20 | reclaim 後に old generation が finalize を試みる | CAS 失敗。二重確定しない |
| IT-21 | `VERIFYING` を `verifyMaxAge` 超過まで放置 | `FAILED` へ落ちる。publish retry 回数は消費していない |
| IT-22 | spool 未設定で `journaled` 起動 | Core は起動する。lineage subsystem が `NOT_READY` |
| IT-23 | 1 endpoint が単独で size 上限超過 | `UNRESOLVED_OVERSIZE` (terminal)。属性は落ちていない |
| IT-24 | catalog obligation の 4 outcome | 各 outcome へ決定的に遷移する。`SOURCE_PURGED` は snapshot から historical entity を作る |

### Atlas-enabled E2E 受入表 (v3.3 release gate)

**`atlas.enabled=false` の緑は完了条件にしない。** CI に Atlas-enabled ジョブを追加し、下表を必須にする。

`FILE_SHARE_SYNC_UPLOAD` は **将来予約として維持する** (判断済み)。存在しない業務機能を
E2E のためだけに実装しない。

- enum は legacy deserialization 互換のため**残す**。javadoc に `RESERVED / producer なし` と明記する。
- business API E2E の分母は **17 種**。
- synthetic payload の単体テストのみ行う。
- **`LineageEventBuilder` からの新規生成は拒否する** (`IllegalArgumentException`)。
  正式な producer を実装するまで、この値の event が新規に生まれない。

| # | 受入項目 | 判定 |
|---|---|---|
| E-1 | schema apply が `applied:true` で完了 | `nemaki_folder_dataset` / `nemaki_import_artifact` / `nemaki_export_artifact` を含む |
| E-2 | **producer のある 17** `LineageProcessType` の producer-shape matrix | 実 business API から発火し、各 Process の inputs/outputs が期待 entity と E-17 の条件で**完全一致**。`FILE_SHARE_SYNC_UPLOAD` は synthetic payload の単体テストのみ |
| E-3 | folder endpoint | `nemaki_folder_dataset` GUID に結線される |
| E-4 | export artifact | `nemaki_export_artifact` が outputs に現れる |
| E-5 | external / cloud / cold endpoint | `nemaki_external_asset` GUID に結線される |
| E-6 | `IMPORT_UPLOADED` | input が `nemaki_import_artifact`、output が folder proxy。`upload://` 文字列は現れない |
| E-7 | inputs と outputs を**別々の**実 entity で完全一致検証 | 片側欠落・方向逆転を検出できる |
| E-8 | cross-repository event | `build()` で拒否される |
| E-9 | v1 legacy event | `UNRESOLVED` (terminal) になり cursor が進む。durable unresolved 記録と metric が残り、repair 対象になる。ordered projector が止まらない |
| E-10 | replay | 新 sequence の補償 event が publish され、Atlas に Process が現れる |
| E-11 | mutation | §5 の QN 規則を1つ壊すと E-2 が落ちる |
| E-12 | cleanup | 中間テストを故意に失敗させても CMIS / Atlas / CouchDB の fixture が残らない |
| E-13 | 同一 folder への import を 2 回 | 別 event になる (eventKey が operationId を含む)。1回目が握り潰されない |
| E-14 | folder backfill | 既存全 folder に proxy がある。orphan reconciliation が 0 を報告 |
| E-15 | folder delete → restore | proxy が `active=false` → `true`。過去 Process の参照が壊れない |
| E-16 | bulk partial response | 片方欠落を検出し reconcile される |
| E-17 | production sink の成功条件 | 下記 verify 契約を満たした場合のみ success |

### E-17 verify 契約 (v2.1)

「POST 直後に GET 1 回」では Atlas の read-after-write 遅延で偽陰性になる。

- **bounded poll**: `lineage.verify.timeout` (既定 30s) / `lineage.verify.interval` (既定 2s)。
  総 deadline を超えたら verify 失敗。
- **verify 待ちは publish retry を消費しない。** target 状態に `VERIFYING` を追加し、
  `PROJECTING → VERIFYING → PUBLISHED | FAILED` とする。`VERIFYING` からの再試行は
  **同じ Process QN への冪等 upsert** であり、新しい Process を作らない。
- GUID 一致だけでは不十分。Atlas は dangling reference に対して疎な shell entity を自動生成しうるため、
  **shell を掴んで合格してしまう**。次を全て検証する:

全 kind 共通:

| 検証項目 | 内容 |
|---|---|
| 具体型 | 期待する具体型と一致。`DataSet` そのものは不可 |
| status | `ACTIVE` (tombstone でない) |
| shell 判定 | `qualifiedName` 以外の属性が全て空なら shell とみなし**失敗** |

kind 別 (external / import / export artifact は `objectId` を持たないため、
全 kind に `repositoryId`/`objectId` 一致を課すことはできない):

| kind | 期待具体型 | 同一性 | 必須属性 |
|---|---|---|---|
| `CMIS_DOCUMENT` | `nemaki_document` | `repositoryId` + `objectId` | `name` |
| `CMIS_FOLDER` | `nemaki_folder_dataset` | `repositoryId` + `objectId` | `name` |
| `ARCHIVE` | `nemaki_archive` | `repositoryId` + `originalId` | `archivedAt` |
| `EXTERNAL_ASSET` / `CLOUD_OBJECT` / `COLD_STORAGE` | `nemaki_external_asset` | `repositoryId` + `externalStableKey` の hash | `sourceSystem` |
| `IMPORT_ARTIFACT` | `nemaki_import_artifact` | `repositoryId` + `operationId` | `importMode` |
| `EXPORT_ARTIFACT` | `nemaki_export_artifact` | `repositoryId` + `operationId` | `artifactKind` |

E-12 の「故意に失敗させる」テストは、cleanup が file-scope で動くことの決定的証拠として必須。

---

## 実装増分 (再 sign-off 後)

| 増分 | 内容 | 独立に検証できるか |
|---|---|---|
| **A** | typed `LineageEndpoint` + endpoint-local snapshot allowlist + kind 明示 builder + producer 全書き換え + サーバー発行 `operationId` + eventKey SHA-256 と `idempotencyKeyVersion` + endpoint 件数/payload 上限と chunking + `FILE_SHARE_SYNC_UPLOAD` 生成拒否 + cross-repo 検証 4層 (§2 §3 §7) | 単体。Atlas 不要 |
| **B** | schema additive 拡張 (`nemaki_folder_dataset` / `nemaki_import_artifact` / `nemaki_export_artifact`) + catalog sync の同一 bulk 作成と **partial response の reconcile** + **既存 folder の authoritative backfill** + lifecycle (rename/move/delete/restore/`sourceState`) + **`LineageCatalogReconciliationService`** (§2 §3) | schema apply + backfill + orphan reconciliation + obligation IT |
| **C** | `CatalogPayloadFactory` へ payload 生成を集約、canonical QN を1箇所化 (既存 `buildExternalAssetQualifiedName` を移設)、`AtlasLineageSink` から payload を剥がす、**POST 後 GUID 完全一致を production の成功条件に** (§4 §5) | 単体 (payload golden) + sink IT |
| **D** | event-first UNSEQUENCED + fenced sequencer (lease/generation/crash 再開) + 状態遷移 CAS / claim lease + cursor 単調 CAS + counter 復旧手順 + durable spool と spool scanner + replay generation CAS (§8) + IT-1〜IT-16 | 実 CouchDB IT |
| **E** | v1 legacy reader + durable unresolved (§6)、repair API + opaque confirmation token + DLQ bundle upload (§9)、`VERIFYING` と E-17 verify 契約 (§10)、Atlas-enabled E2E 受入表 E-1〜E-17 | E2E |

依存: A → B → C、D は A と独立に着手可、E は A〜D 完了後。

---

## v2.2 で閉じた 6 点

| # | 指摘 | 反映 |
|---|---|---|
| 1 | lease 削除で generation high-watermark が消える | §8 — release は `owner=null` / `expiresAt=過去` の CAS。`generation` 維持。**削除は拒否**。IT-17 / IT-18 |
| 2 | stale `SEQUENCING` を回収する遷移がない | §8 — `SEQUENCING(G_old) → SEQUENCING(G_new)` の reclaim 遷移を明記。crash 表の「`UNSEQUENCED` のまま」も `SEQUENCING(G_old)` に訂正。IT-19 / IT-20 |
| 3 | `VERIFYING` が状態表にない | §8 — 5 遷移を状態表に追加。lease renew、`verifyMaxAge` (既定 10m)、reaper の条件、metric。verify 項目は **kind 別**に分離 (artifact 系に `objectId` は無いため)。IT-21 |
| 4 | spool の起動影響・fsync・path・node 運用・bundle 署名 | §8 — Core は落とさず lineage subsystem を `NOT_READY`。strict は明示設定時のみ。path safe encode / `0700`・`0600` / NOFOLLOW / 同一 dir temp / file+parent fsync / digest 冪等 / サイズ上限 / ACK fsync。**集約 endpoint は v3.3 では実装しない**。bundle は HMAC + keyId + 期限 + nonce + 上限 + zip bomb/traversal 拒否 + 監査。IT-22 |
| 5 | chunk 連番要件と silent attribute drop | §2 — **連番要件を撤回**。再構成は `operationId`/`chunkIndex`/`chunkCount`。属性の事前上限、optional は truncate+hash、必須値は落とさない、単独超過は `UNRESOLVED_OVERSIZE`。**silent drop 禁止**。IT-23 |
| 6 | catalog obligation の実装形態 | §2 — **`LineageCatalogReconciliationService` を lineage 専用に新設**。task key / 4 state / 4 outcome (`SOURCE_EXISTS` / `SOURCE_PURGED` / `SOURCE_ERROR` / `SNAPSHOT_INCOMPLETE`)。既存 service とは CAS task/lease/backoff の低レベル部品のみ共有。IT-24 |

併せて **`sequence` を「sequencer finalization order」と再定義**した (§8 冒頭)。
domain commit の全順序も spool を跨ぐ因果順序も保証せず、`occurredAt` は表示・監査用で
fencing の根拠にしない。厳密な domain commit 順序は業務 DB と同一トランザクションの
outbox が要るため本増分の範囲外、と明記した。

IT は 24 件になった。

---

## v2.1 で閉じた 6 点

| # | 指摘 | 反映先 |
|---|---|---|
| 1 | endpoint-local snapshot と shell 排除条件 | §2 (kind 別 allowlist / catalog reconciliation obligation) / §10 E-17 verify 契約 |
| 2 | file dead letter の durable spool / import / ACK | §8 「durable spool」。既存 sink が SLF4J logger である事実を明記 |
| 3 | sequencer lease / fencing / crash 状態表 | §8 「sequencer lease / fencing 状態表」+ old leader 復活系列の証明 + crash 再開規則 |
| 4 | replay generation の CAS 状態機械 | §8 「replay generation の CAS 状態機械」 |
| 5 | E-1 / E-2 / E-9 の表記修正 | §10 (import artifact 追加 / 17 種に統一 / `UNRESOLVED` へ修正) |
| 6 | endpoint 件数・payload size 制限 | §2 「件数・サイズ上限」(1,000 / 1 MiB / chunking) |

7 判断の条件も反映済み: proxy の `sourceState=PURGED` と GC 条件 (§3)、
`operationId` のサーバー発行・公開 API 非必須 (§3)、`idempotencyKeyVersion` の永続化と
v1 replay が既存 Process を更新しないこと (§3)、counter 巻き戻し復旧手順 (§8)、
token の opaque / bind / 1回限り / CAS / TTL 設定可能 / CSRF・監査 (§9)、
E-17 の bounded poll と `VERIFYING` 状態 (§10)、`FILE_SHARE_SYNC_UPLOAD` の RESERVED 化 (§1 §10)。

---

## 実装着手 sign-off の前に

この文書の内容で A〜E に着手してよいか。着手後も各増分の完了時にレビューを受ける。

未確定点は残っていない。v2.1 で残していた catalog reconciliation obligation の実装形態は
lineage 専用 service に確定した (§2)。
