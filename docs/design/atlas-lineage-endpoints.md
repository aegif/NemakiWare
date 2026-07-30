# 設計増分 A — Atlas lineage endpoint 型体系と多重AP状態遷移

status: **draft / sign-off 待ち**
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
| `FILE_SHARE_SYNC_UPLOAD` | **producer なし** (enum のみ) | — | — |

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
        EndpointKind kind,      // CMIS_DOCUMENT / CMIS_FOLDER / ARCHIVE / EXTERNAL_ASSET
                                // / EXPORT_ARTIFACT / IMPORT_SOURCE / CLOUD_OBJECT / COLD_STORAGE
        String qualifiedName,   // canonical。§4 の規則で生成
        String repositoryId,    // repo 内 endpoint のみ。外部は null
        String objectId) {      // 同上
}
```

- `LineageEvent.inputs()/outputs()` を `List<String>` → `List<LineageEndpoint>` に変更。
- `LineageEventBuilder` は `addInputDocument()` / `addInputFolder()` / `addInputExternal(...)` のように
  **kind を明示させる** API にする。`addInput(String)` は削除する (型を落とす唯一の経路なので残さない)。
- producer は既に document か folder かを知っている
  (例: `EXPORT_ZIP_FOLDER` の `folderId`)。呼び出し側の変更は機械的。
- `EXPORT_SELECTED_OBJECTS` だけは `Content` から動的に判定する
  (`content.isFolder()` 相当)。ここは実装時に型判定の単体テストを付ける。

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

### export artifact

`EXPORT_ZIP_FOLDER` / `EXPORT_SELECTED_OBJECTS` の output 欠落を埋める。

```
nemaki_export_artifact   superTypes: [DataSet]
  qualifiedName : nemaki://{repo}/exports/{runId}
  name          : エクスポート名 (zip ファイル名等)
  artifactKind  : ZIP | FILESYSTEM
  objectCount   : int
```

`runId` は `LineageEventBuilder.runId()` が既にある。artifact entity は lineage event と同じ bulk で作る
(catalog sync 経由ではない — export 成果物は CMIS オブジェクトではないため)。

---

## 4. external asset canonical QN 規則

`nemaki_external_asset` (DataSet) が既にあるので、**規則を確定して producer と sink で一つにする**のが要件。

| endpoint | canonical qualifiedName |
|---|---|
| external ingest | `ExternalSourceUri.build(system, tenant, typePath, objectId)` の出力を**そのまま**使う。既に canonical |
| `cloud://{provider}/{fileId}` | `cloud://{provider}/{fileId}` をそのまま canonical とする |
| `file://{path}` | `file://{正規化パス}`。パスは**ホスト側の絶対パスを含むため PII/構成情報**。§5 の redaction 対象 |
| `upload://{importMode}` | **endpoint にしない**。`upload://` は「ブラウザからのアップロード」というモードであって資源ではない。`snapshotAttributes.importMode` へ移し、inputs は空にする |
| `cold://{storageRef}` | `cold://{storageRef}` をそのまま canonical とする |

- `upload://` を endpoint から外すのが唯一の非対称な判断。理由は上記のとおりで、
  これを entity 化すると「全アップロードが同一 entity を指す」ため lineage として無意味になる。
- `file://` / `cloud://` / `cold://` は `nemaki_external_asset` として **lineage publish と同一 bulk で作成**する。
  catalog sync の対象ではない (CMIS オブジェクトではない)。
- canonical 化は**1箇所**に置く: `LineageEndpointCatalog` (新規)。producer も sink もここを通す。

---

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

## 6. legacy event migration

`schemaVersion` 1 の event が journal に残る。**書き換えない**。

- reader (`CouchLineageEvent.toLineageEvent`) が v1 を読むとき、文字列 endpoint を
  `LineageEndpoint` へ**推測変換**する: `nemaki://{repo}/objects/{id}` は
  CouchDB を引いて document/folder を判定、`nemaki://{repo}/archives/` は ARCHIVE、
  scheme 付きはそれぞれの kind、判定不能は `UNKNOWN`。
- `UNKNOWN` を含む v1 event は **publish せず `SKIPPED` (新 terminal 状態) にして cursor を進める**。
  失敗ではないので ordered projector を止めない。理由は `skipReason` に残す。
- 新規 event は必ず v2。v1 は時間とともに消える (retention)。migration patch は書かない。

---

## 7. cross-repository 方針

**cross-repository lineage は認めない。**

- `LineageEvent.repositoryId` と全 endpoint の `repositoryId` が一致することを
  `LineageEventBuilder.build()` で検証し、違反は `IllegalArgumentException`。
- 外部 endpoint (external / cloud / cold / file) は repositoryId を持たないので対象外。
- 現在の E2E は event に合成 repo、endpoint に `bedroom` を使っており**この規則に違反する**。
  E 増分で「同一 repository 内で完結する fixture」に直す。tenant 境界の確認テストを追加する。
- 将来 cross-repo を認める場合は、両 repository の認可確認と専用監査イベントを設計してから。
  本増分では**禁止を固定する**。

---

## 8. sequence / status / cursor / replay の多重AP状態遷移

### 現在の欠陥 (実装で確認済み)

| # | 箇所 | 内容 |
|---|---|---|
| 8-a | [CouchLineageJournalStore:257](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/CouchLineageJournalStore.java#L257) `append()` | 採番 → event 作成の順。AP-A が seq=1 を採番して書込む前に AP-B が seq=2 を publish し cursor が 2 になると、seq=1 は**永久に不可視**。また CAS 5回失敗で event 自体を作らず dead letter に落とす → **実測 92件がこれ** |
| 8-b | CouchLineageJournalStore:351 `updatePublishStatus` | 期待旧状態も claim token も検証しない。`PUBLISHED → PROJECTING` の逆行と二重 publish が可能 |
| 8-c | [LineageProjectionLoop:270](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageProjectionLoop.java#L270) | `PUBLISHED` の永続化結果を確認せず `advanceCursor`。永続化が落ちると event を恒久喪失 |
| 8-d | LineageJournalController:195 replay | `PROJECTING` も cursor 通過済みも PENDING へ戻せる。新 sequence obligation を作らないため二重 publish か永久 PENDING |

### 設計

**8-a: 採番と event 作成を1つの CouchDB 書込みにする。**
`lineage_seq:{repo}` を「次の番号」ではなく**予約済み最大値**として扱い、
event doc の `_id` に sequence を含める (`lineage_event:{repo}:{seq:012d}:{eventId}`)。
409 (同一 seq の衝突) を CAS 失敗として再試行する。event が存在しない sequence は原理的に生じない。
CAS 再試行は 5 回固定をやめ、指数バックオフ + 総 deadline にする。
再試行枯渇は **dead letter ではなく caller への例外**にする (呼び出し側の業務操作は既に成功しているため、
lineage 欠落を黙って残すより明示的に失敗させ、業務側で補償できるようにする)。

**8-b: 状態遷移を CAS にする。** `updatePublishStatus(eventId, target, expected, next, claimToken)`。
許可する遷移だけを表で持つ:

```
PENDING    → PROJECTING (claim token 発行)
PROJECTING → PUBLISHED  (同一 claim token のみ)
PROJECTING → FAILED     (同一 claim token のみ)
FAILED     → PROJECTING (claim token 再発行)
*          → DISCARDED  (reaper / 管理操作)
*          → SKIPPED    (v1 UNKNOWN endpoint)
PUBLISHED  → (遷移なし。terminal)
```

**8-c: cursor は永続化成功後にのみ進める。** `updatePublishStatus` が 0 を返したら
cursor を進めず、その repository の処理を止めて次 poll に委ねる。

**8-d: replay は新しい sequence obligation を発行する。**
cursor 通過済み event を同じ sequence で PENDING に戻すことはしない (採用しない案)。
replay は元 event を `REPLAYED` (terminal) にし、**同一 payload・新 eventId・新 sequence の
補償 event を append する**。`replayOf` に元 eventId を持たせる。
これで cursor は常に単調で、二重 publish も永久 PENDING も生じない。
`PROJECTING` の replay は**拒否する** (claim を横取りするため)。stale claim は reaper が
`FAILED` に落としてから replay 可能になる。

---

## 9. 既存 70 dead letter の repair 方針

実測: 未replay dead letter 93件。うち 92件が
`Failed to assign sequence number after 5 CAS retries for bedroom` (8-a)、1件が retry-age-exceeded。
processType 内訳は `EXPORT_ZIP_FOLDER` 39 / `IMPORT_UPLOADED` 22 /
`FILE_SHARE_SYNC_DOWNLOAD` 22 / `EXPORT_FILESYSTEM` 8 / `IMPORT_FILESYSTEM` 2。
このうち original event が存在しない (= 採番前に落ちた) ものが 70 件で、現実装では再生不能。

方針: **dead letter から補償 event を append する repair 管理 API を用意する** (自動実行はしない)。

- `POST /api/v1/admin/lineage-journal/dead-letters/{eventId}/repair`
  - dead letter の payload から §2 の v2 event を組み立て直し、8-a の新 append で採番する。
  - endpoint は §6 と同じ推測変換を通す。`UNKNOWN` を含むものは `SKIPPED` として記録し publish しない。
  - 元 dead letter は `repairedAt` / 新 eventId を持って terminal になる。削除はしない (監査のため)。
- 一括版は `replay-all` の轍を踏まないよう **repository と processType で必ず絞る**。
  対象件数を dry-run で返す `?dryRun=true` を必須の第一手順にする。
- 70件は「業務操作は成功したが lineage が欠けている」状態である。repair は
  `occurredAt` を元の値のままにするので、時系列は復元される。

---

## 10. 決定的並行 IT と Atlas-enabled E2E 受入表

### 決定的並行 IT (実 CouchDB、`*IT.java`、専用 CI ジョブ)

| # | 検証 | 期待 |
|---|---|---|
| IT-1 | 2 AP が同時に append × 各 100 件 | sequence に穴と重複がない。全 200 件が可視 |
| IT-2 | append 中に AP を kill | 採番済みで event なしの sequence が生じない |
| IT-3 | 2 AP が同一 event を同時に claim | 片方だけ成功。claim token 不一致の書込みは 0 件 |
| IT-4 | `PUBLISHED` 後に `PROJECTING` を試みる | 拒否。状態は `PUBLISHED` のまま |
| IT-5 | `updatePublishStatus` を失敗注入 | cursor が進まない。次 poll で再試行される |
| IT-6 | cursor 通過済み event を replay | 新 sequence の補償 event ができる。元は `REPLAYED` |
| IT-7 | `PROJECTING` を replay | 拒否 (409) |
| IT-8 | dead letter repair × 70 件相当 | 全件が新 sequence で可視。二重生成なし |

### Atlas-enabled E2E 受入表 (v3.3 release gate)

**`atlas.enabled=false` の緑は完了条件にしない。** CI に Atlas-enabled ジョブを追加し、下表を必須にする。

| # | 受入項目 | 判定 |
|---|---|---|
| E-1 | schema apply が `applied:true` で完了 | `nemaki_folder_dataset` / `nemaki_export_artifact` を含む |
| E-2 | 全 18 `LineageProcessType` の producer-shape matrix | 実 business API から発火し、各 Process の inputs/outputs GUID が期待 entity と**完全一致** |
| E-3 | folder endpoint | `nemaki_folder_dataset` GUID に結線される |
| E-4 | export artifact | `nemaki_export_artifact` が outputs に現れる |
| E-5 | external / cloud / cold endpoint | `nemaki_external_asset` GUID に結線される |
| E-6 | `upload://` | endpoint に現れず `snapshotAttributes.importMode` にある |
| E-7 | inputs と outputs を**別々の**実 entity で完全一致検証 | 片側欠落・方向逆転を検出できる |
| E-8 | cross-repository event | `build()` で拒否される |
| E-9 | v1 legacy event | `SKIPPED` になり cursor が進む。ordered projector が止まらない |
| E-10 | replay | 新 sequence の補償 event が publish され、Atlas に Process が現れる |
| E-11 | mutation | §5 の QN 規則を1つ壊すと E-2 が落ちる |
| E-12 | cleanup | 中間テストを故意に失敗させても CMIS / Atlas / CouchDB の fixture が残らない |

E-12 の「故意に失敗させる」テストは、cleanup が file-scope で動くことの決定的証拠として必須。

---

## 実装増分 (sign-off 後)

| 増分 | 内容 | 独立に検証できるか |
|---|---|---|
| **A** | typed `LineageEndpoint` + builder API + producer 全書き換え + schemaVersion 2 + cross-repo 検証 (§2 §7) | 単体。Atlas 不要 |
| **B** | schema additive 拡張 (`nemaki_folder_dataset`, `nemaki_export_artifact`) + catalog sync の同一 bulk 作成 (§3) | schema apply + entity 存在確認 |
| **C** | `CatalogPayloadFactory` へ payload 生成を集約、`AtlasLineageSink` から payload を剥がす + canonical QN 1箇所化 (§4 §5) | 単体 (payload golden) |
| **D** | append の採番一体化 / 状態遷移 CAS / cursor 順序 / replay の補償 event (§8) + IT-1〜IT-8 | 実 CouchDB IT |
| **E** | v1 legacy reader + `SKIPPED` (§6)、dead letter repair API (§9)、Atlas-enabled E2E 受入表 (§10) | E2E |

依存: A → B → C、D は A と独立に着手可、E は A〜D 完了後。

---

## sign-off で確認いただきたい点

1. **§3 の folder proxy** — `nemaki_folder_dataset` を additive に足す方針でよいか。
   UI/governance が参照するのは従来どおり `nemaki_folder` のままとする。
2. **§4 の `upload://` 除外** — endpoint ではなく snapshotAttribute にする判断。
3. **§7 cross-repository 禁止** — v3.3 では固定でよいか。
4. **§8-a の再試行枯渇** — dead letter ではなく caller への例外に変える点。
   業務操作は成功済みなので、lineage 欠落を黙って残すか明示的に失敗させるかの判断。
5. **§8-d の replay** — 補償 event 方式でよいか (元 event は `REPLAYED` terminal)。
6. **§9 の repair** — 自動実行せず管理 API + dry-run 必須とする運用でよいか。
