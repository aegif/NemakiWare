# 設計増分 A — Atlas lineage endpoint 型体系と多重AP状態遷移

status: **v2.3.35 — increment A sign-off 済み・**§6-a 再 sign-off 承認済み (2026-08-03)**。A-1〜A-1k + A-2 Slice 1a〜3 + producer P-1〜P-3c + D-spool + **D-rest 全 4 slice** (fenced sequencer / v2 遷移 CAS・単調 cursor・schema routing / replay CAS + crash 回収 / 収束 materializer + capability provider + scanner 入口) + chunking 実装済み — writer は v1 のまま・全 D-rest driver は非活性 (readiness gate 既定 false・resolver 既定 unavailable)。残: 4b (**運用証跡待ち** — preflight は実装済み)。**§2 の属性別上限は実装済み (v2.3.26)**。**Slice 4a 実装済み — writer は v1 のまま (barrier 文書が無い＝pristine)**。**増分 B 実装済み (v2.3.35)** — 型追加 / 属性追加 / secret 境界 / backfill / lifecycle / reconciliation / runbook。live Atlas は起動できず B-E1〜B-E4 を EXTERNAL_EVIDENCE_REQUIRED として [`lineage-increment-b-runbook.md`](../operations/lineage-increment-b-runbook.md) に固定

revision 履歴と、過去に閉じた指摘の一覧は
[`atlas-lineage-endpoints-changelog.md`](atlas-lineage-endpoints-changelog.md) にあります
(この文書が正典で、changelog は履歴です)。

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

実装から採取 (推測なし)。**P-2 変換前の v1 実装の棚卸し** (行番号は当時のもの) —
変換済み producer (Retention / Archive / CloudDrive / Ingest) は現在 `LineageFact` 経由で
emit し、v1 文字列は LegacyV1Projection が verbatim に運ぶ (v2.3.15)。archetype null の
IngestLineageEmitter 行は fact 側 `GENERIC_EXTERNAL_INGEST` / v1 側 `IMPORT_UPLOADED` の
両建てになった。`E` = external URI、`O` = `nemaki://{repo}/objects/{id}`、
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
        EndpointKind kind,             // CMIS_DOCUMENT / CMIS_FOLDER / ARCHIVE
                                       // / EXTERNAL_ASSET / CLOUD_OBJECT / COLD_STORAGE
                                       // / IMPORT_ARTIFACT / EXPORT_ARTIFACT
        String catalogQualifiedName,   // canonical。§4 の規則で生成
        String repositoryId,           // 【必須】所有 Nemaki repository。external でも null 不可
        String objectId,               // kind により nullable (下表)
        String operationId,            // artifact 系のみ。§3
        Map<String, Object> attributes // kind 別 allowlist。immutable
) {}
```

### repositoryId は全 kind で必須 (v2.3)

v2.2 は external endpoint の `repositoryId` を null としていた。**撤回する。**
external QN も E-17 も `repositoryId` を同一性の一部にしているのに、null かつ §7 の検査対象外では、
直接注入された event が**別 repository の external asset QN を参照しても 4 層検査で拒否できない**。

- `repositoryId` は**全 kind で必須**。外部資源であっても「どの Nemaki repository が
  その関係を主張しているか」は常に存在する。
- §7 の cross-repository 検査は **external / cloud / cold を含む全 endpoint** を対象にする。
- 検査は 2 段: `endpoint.repositoryId == event.repositoryId`、かつ
  **`catalogQualifiedName` に埋め込まれた `{repositoryId}` 部分とも一致**すること。
- **nullable なのは `objectId` だけ**で、それも kind による。

| kind | `objectId` | 同一性を担うもの |
|---|---|---|
| `CMIS_DOCUMENT` / `CMIS_FOLDER` | 必須 | `repositoryId` + `objectId` |
| `ARCHIVE` | 必須 | `repositoryId` + `objectId` (archive id) |
| `EXTERNAL_ASSET` / `CLOUD_OBJECT` / `COLD_STORAGE` | **null** | `repositoryId` + `externalStableKey` |
| `IMPORT_ARTIFACT` / `EXPORT_ARTIFACT` | **null** | `repositoryId` + `operationId` |

### `FILESYSTEM_PATH` の解決 (v2.3)

v2.2 は `FILESYSTEM_PATH` を §4 の stableKey 表にだけ書き、`EndpointKind` にも E-17 にも
入れていなかった。**独立 kind にはせず `EXTERNAL_ASSET` に統合する。**
filesystem path は Nemaki の外にある資源であって cloud / cold と同種であり、§4 の canonical QN 規則
(`external-assets/{base64url(stableKey)}`) にそのまま乗る。`stableKey` が
`filesystem:{絶対正規化パス}` (既存 catalog sync と同一書式、§4) になるだけの違いとして扱う。

### endpoint-local snapshot (v2.1)

event-level の `snapshotAttributes` は**複数 endpoint に対応できない**。
同一 bulk で entity を完全生成するには endpoint ごとの属性が要る。

**allowlist は既存 Atlas schema と一致していなければならない。** 型に無い属性を宣言すると
Atlas が受信時に捨て、allowlist が防ぐはずの失敗を allowlist 自身が作る。
下表は `PurviewSchemaPayloadFactory` の現行定義に一致しており、
`EndpointKindSchemaAlignmentTest` が名前・型・mandatory を機械的に突き合わせる。

| kind | allowlist (必須は **太字**) | 型 |
|---|---|---|
| `CMIS_DOCUMENT` | **`name`**, `versionLabel`, `folderPath` | すべて string |
| `CMIS_FOLDER` | **`name`** | string |
| `ARCHIVE` | **`archivedAt`**, **`originalObjectId`**, `name`, `versionLabel`, `archiveState` | `archivedAt` は **long (epoch millis)**、他は string |
| `EXTERNAL_ASSET` | **`sourceSystem`**, **`externalStableKey`** (protected), `externalPath` | すべて string |
| `CLOUD_OBJECT` | 同上 (provider を `sourceSystem` に載せる) | 同上 |
| `COLD_STORAGE` | 同上 (**storage adapter type** を `sourceSystem` に載せる。既存 sync が `contentRef.type` を入れているのと同じ値。storage class (`GLACIER` 等) は別物で、増分 B の独立属性) | 同上 |
| `IMPORT_ARTIFACT` | **`importMode`**, `byteLength`, `contentHash`, `originalFileName` | `byteLength` は long |
| `EXPORT_ARTIFACT` | **`artifactKind`**, `objectCount`, `name` | `objectCount` は long |

**増分 B で schema と allowlist の両方に足すもの** (片方だけでは値が届かない):
`nemaki_document` に `mimeType` / `contentLength`、`nemaki_external_asset` に
`tenantId` (EXTERNAL_ASSET) / `provider` (CLOUD_OBJECT) / `storageClass` (COLD_STORAGE — storage
class は `sourceSystem` とは別の値であり、B で独立属性として足すまでは運ばない)。
同じ Atlas 型を共有する kind でも allowlist が同一である必要はない
(B 後の external 3 kind がまさにそれ)。

- allowlist 外のキーは構築時に拒否する (schema にない属性が黙って捨てられる §5 の問題の再発防止)。
- 値は **scalar (string / 非負整数) のみ**。`Map.copyOf` は浅いコピーなので、List/Map を許すと
  発行済み event の下で snapshot が変わる。
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
#### projector との接続 (v2.3)

v2.2 の「event を `PENDING` のままにする」だけでは足りない。既存 projector は `PENDING` を
毎 poll 再 claim するため、retry 消費や `DISCARDED` 化が起きる。
**`WAITING_FOR_CATALOG` を独立した target 状態として追加する。**

```
PENDING             → WAITING_FOR_CATALOG  (publish 前検査で obligation を作った / 既存を見つけた)
WAITING_FOR_CATALOG → PENDING              (obligation が RESOLVED。再開)
WAITING_FOR_CATALOG → UNRESOLVED           (obligation が SNAPSHOT_INCOMPLETE。terminal)
WAITING_FOR_CATALOG → DISCARDED            (管理操作のみ)
PENDING             → UNRESOLVED           (v2.3.24 F1: 行は作られたが plan が unstorable と
                                            判明。UNSEQUENCED 行で合法な唯一の終端であり、
                                            durable reason を持つ)
```

| 論点 | 契約 |
|---|---|
| target との関係 | obligation task key に `target` を含む。**target ごとに独立**して待つ |
| retry 消費 | `WAITING_FOR_CATALOG` は **publish retry を消費しない**。`VERIFYING` と同じ扱い |
| 滞留上限 | `lineage.catalog-wait.max-age` (既定 24h)。超過で `UNRESOLVED` へ。metric `lineage.waiting-catalog.oldest.age` |
| cursor | `WAITING_FOR_CATALOG` の間は **cursor を進めない** (順序保証のため)。terminal 化したときだけ進める |
| 再開 | event は `waitingTaskKeys` (複数可) を持つ。**全件が `RESOLVED` になって初めて** `PENDING` へ戻す。1 件解決しただけでは戻さない |
| 滞留計測 | `waitingSince` は最初に `WAITING_FOR_CATALOG` へ入った時刻。`PENDING` へ戻って再び待機に入っても**リセットしない** (往復で待機上限を回避できないようにする) |
| 複数 event が同一 task を待つ | task は 1 つ。**ACK は task 側で 1 回**。`RESOLVED` 後に task key から待機 event を逆引きして全件戻す (逆引き用の index を張る) |
| task の GC | 待機 event が 0 件かつ `RESOLVED` から `lineage.catalog-task.retention` 経過で削除 |

#### obligation machine の契約表 (v2.3.37 — 実装前に §2 / reconciliation / materialization / projector から抽出)

**4b は deploy を伴わない flag flip を目標とする。** したがって activation で必要になる
producer・consumer・回収処理は、**非活性状態で先に配布・検証されている**必要がある。
「projector が非活性だから未実装」は、D-rest を有効化してから不足に気付く構成であり、採らない。

| 論点 | 契約 |
|---|---|
| **作成条件** | v2 projection の **publish 前検査**で、endpoint が指す catalog entity が `PRESENT` と確定できないとき。`ABSENT` と `UNKNOWN` の両方が対象で、後者を「無い」に潰さない |
| **deterministic identity** | `taskKey = LineageCanonicalHash.hash("LINEAGE_CATALOG_OBLIGATION_V1", target, repositoryId, endpointKind, catalogQualifiedName)`。**入力順序にも時刻にも依存しない**。同じ対象からは常に同じ id |
| **文書 ID** | `lineage_catalog_obligation:{taskKey}`。`nemaki_lineage` DB。`type = lineage_catalog_obligation` |
| **状態** | `PENDING` → `CLAIMED(owner, token, leaseUntilMs)` → `RESOLVED` / `UNRESOLVED`。`CLAIMED` → `PENDING` (lease 失効の回収、または retryable 失敗) |
| **CAS** | 全遷移が `_rev` 一致の PUT。409 は CAS 敗北 (ordinary)、それ以外は例外。**1 つの CAS が 1 つの委譲先の中で完結する** |
| **claim owner / lease / fencing** | `owner` = node id、`token` = claim ごとの UUID、`leaseUntilMs`。**更新は token 一致が必須**。期限切れ claim は誰でも `PENDING` へ戻せるが、戻した後の token は新しいので、**古い claimant の更新は必ず拒否される** |
| **retryable な失敗** | `SOURCE_ERROR` (catalog 未到達・5xx・timeout)。`attempts` を増やし capped backoff で `PENDING` へ戻す。**terminal にしない** |
| **terminal な失敗** | `SNAPSHOT_INCOMPLETE` のみ。`UNRESOLVED` は **reason と証拠 (`evidence`) に束縛**される — 理由なしの `UNRESOLVED` は作れない |
| **outcome** | `SOURCE_EXISTS` → `RESOLVED` / `SOURCE_PURGED` → snapshot から historical entity を作って `RESOLVED` / `SOURCE_ERROR` → retry / `SNAPSHOT_INCOMPLETE` → `UNRESOLVED` |
| **対応する endpoint** | artifact (`nemaki_import_artifact` / `nemaki_export_artifact`)、folder companion (`nemaki_folder_dataset`)、external asset、document。**kind は taskKey に入る**ので、同じ QN でも kind が違えば別 task |
| **catalog entity 準備完了の判定** | `PRESENT` のみ。`ABSENT` は未完了、**`UNKNOWN` (catalog が答えない) も未完了**。UNKNOWN を resolved / in-sync として扱わない |
| **projector が `WAITING_FOR_CATALOG` へ入る条件** | publish 前検査で obligation を作った、または既存の未 `RESOLVED` obligation を見つけたとき。target ごとに独立 |
| **cursor** | `WAITING_FOR_CATALOG` の間は **cursor を進めない** (順序保証)。terminal 化 (`UNRESOLVED`) したときだけ進める |
| **publish 抑止** | `WAITING_FOR_CATALOG` の間は publish しない。retry も消費しない (`VERIFYING` と同じ扱い) |
| **再開** | event は `waitingTaskKeys` を持つ。**全件が `RESOLVED` になって初めて** `PENDING` へ戻す。1 件では戻さない |
| **滞留計測** | `waitingSince` は最初に待機へ入った時刻。`PENDING` へ戻って再び待機しても**リセットしない** (往復で上限を回避させない) |
| **滞留上限** | `lineage.catalog-wait.max-age` (既定 24h) 超過で `UNRESOLVED` |
| **reconciliation との関係** | reconciliation が catalog を照合し、`PRESENT` を確認できた obligation を `RESOLVED` にする。`UNDETERMINED` では解決しない |
| **replay / 再起動 / 重複配送** | taskKey が deterministic なので、再起動後も replay 後も**同じ task に合流**する。create は create-if-absent で、既存文書と意味が食い違えば**成功扱いにしない** |
| **dead letter / metric / admin status** | metric は件数のみ (`lineage.obligation.{pending,claimed,resolved,unresolved}`)。**secret・QN の protected 部分・cursor 値をログ / 例外 / dead letter に出さない** |
| **D-rest 無効時** | 作成・claim・scan・回収・projector 待避の**すべてが動かない**。gate は `LineageDrestReadiness` 1 箇所で、各所に散らさない |
| **barrier 統合** | 新 capability `catalog:obligations` を **server-defined 必須集合**へ追加する。文書だけに前提を書かず、**barrier が知っている**状態にする。これを持たない古い binary の ACK では activation が拒否される |

#### historical snapshot の順序契約 (v2.3.47)

historical 再構成で「どちらの snapshot が新しいか」を決める順序は、**projector と cursor が
既に使っている durable な座標**を再利用する。新しい時系列は作らない。

| 要素 | 値 | 根拠 |
|---|---|---|
| partition | `repositoryId` | sequence は **repository 単位の counter** なので、別 repository の 41 は別物。跨いだ比較は無意味であり corruption |
| 主順序 | `sequenceNumber` | `v2_by_repository_and_sequence` の view key `[repositoryId, sequenceNumber]` が正典。§8 で `sequence` は finalization order と定義済み |
| tie-break | `deliveryId` | 同順位のときの**決定性のためだけ**。単独では新旧を決めない |
| 順序不能 | `sequenceNumber == 0` (UNSEQUENCED) | stream 上の位置がまだ無い。「最初」ではなく `INDETERMINATE` |

**deliveryId の辞書順を新旧判定に使わない。** 安定した識別子ではあるが時刻とは無関係で、
その順の「最後」が実際には最初に起きたものになり得る。

#### 複数 snapshot の選択規則 (v2.3.47)

**同一 subject で attributes が違うこと自体は corruption ではない。** `name` /
`folderPath` / `versionLabel` は rename・移動・version 更新で正当に変わる。当初案は
digest 差を corruption としていたが、それでは**一度でも rename された object が
永久に再構成不能**になる。複数 snapshot は普通の状態であり、その object の履歴である。

1. 全候補の subject 一致と integrity を検証
2. journal の正規順序で並べる
3. **最新候補の source disposition を現在の証拠**とする
4. 最新が `SOURCE_EXISTS` → historical entity を作らない (restore された場合を含む)
5. 最新が `SOURCE_UNKNOWN` → retryable
6. 最新が `SOURCE_PURGED` → その snapshot から再構成を試みる
7. 最新が構造的に不足していても、**古い snapshot と黙って merge しない**

corruption: subject 不一致 / **同一順序座標に異なる snapshot** / evidence digest 不一致 /
decode 失敗 / disposition 不正 / unsupported schema / repository 跨ぎ。

corruption ではない: 時系列の異なる event で name や folderPath が違う /
`EXISTS→PURGED` の正規遷移 / purge 後の restore による `PURGED→EXISTS` /
互換 decoder を通った表現変更。

#### evidence digest は disposition を含む (v2.3.47)

domain を `LINEAGE_WAITING_SNAPSHOT_V2` へ上げた。V1 は `sourceDisposition` を
含んでいなかったので、**属性が同じで disposition だけ違う 2 候補が同じ digest** になり、
resolver が「一致」と判定できた。これは**生存 object へ tombstone を作る経路**である。
domain を黙って再定義せず version を上げたのは、旧式で計算された digest が新式の下で
妥当と見なされないようにするため。

canonical constructor が検証する: 64 桁小文字 hex / 内容から再計算した digest と
constant-time 比較 / kind の allowlist 内 / scalar のみ (List・Map・配列・任意 Object を拒否) /
secret 境界 / schema version が対応範囲内。**factory 利用は javadoc の約束ではなく、
public constructor から不正な snapshot を作れない**。

`Map.copyOf` は浅いコピーなので、nested List/Map は「不変」を満たさない
(呼び手の参照から変更でき、digest が記述しない内容になる)。deep copy ではなく
**拒否**する — §2 が endpoint 属性で既に禁じているのと同じ理由。

### 件数・サイズ上限 (v2.1)

`EXPORT_SELECTED_OBJECTS` は選択件数だけ endpoint が並ぶ。無制限は Atlas payload と
CouchDB document の双方を壊す。

| 制限 | 既定 | 超過時 |
|---|---|---|
| 1 event あたりの endpoint 数 | 1,000 | **chunk する**。`operationId` を共有し `chunkIndex` / `chunkCount` を持つ複数 event に分割 |
| 1 event の payload size | 1 MiB | 同上 |
| chunk 間の順序 | — | **sequence の連番は要求しない** (下記) |
| 1 endpoint が単独で上限超過 | — | durable `UNRESOLVED(reason=OVERSIZE)`。**属性を黙って落とさない** (下記) |

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
| 単独超過 | 1 endpoint だけで上限を超えるなら、その event を `UNRESOLVED(reason=OVERSIZE)` (terminal) にし、endpoint hash を残す。cursor は進める |
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

### 増分 B の契約表 (v2.3.30 — 実装前に既存設計から抽出)

実装の対象は 3 つ。identity・属性・relationship・更新主体・削除規則を先に固定する。
**未確定箇所は安全側 (fail-closed / 削除しない / secret を運ばない) を採り、test で固定する。**

| 型 | identity | 追加する属性 | relationship | 更新主体 | 削除規則 |
|---|---|---|---|---|---|
| `nemaki_folder_dataset` (新規) | `nemaki://{repo}/folders/{objectId}/dataset` — 正典は `LineageEndpoint.folderProxyQualifiedName` | `repositoryId` `objectId` (必須) / `active` (boolean) / `sourceState` | `nemaki_folder_has_dataset` 1:1 → `nemaki_folder` | catalog sync が folder と**同一 bulk**で作る + backfill | **削除しない**。delete→`ARCHIVED`、purge→`PURGED`、restore→`ACTIVE`、実体の記録も無い→`ORPHAN`。GC は `PURGED` かつ参照 Process 0 件かつ retention 経過のみ |
| `nemaki_document` (属性追加) | 既存 `nemaki://{repo}/objects/{objectId}` (不変) | `mimeType` `contentLength` `versionObjectId` `changeToken` `contentHash` | 既存のまま | catalog sync (authoritative) | 既存のまま |
| `nemaki_external_asset` (属性追加) | 既存 (stable key 由来・不変) | `tenantId` (EXTERNAL_ASSET) / `provider` (CLOUD_OBJECT) / `storageClass` (COLD_STORAGE) / `sourceRevision` `sourceModifiedAt` `sourceContentHash` `sourceContentLength` | 既存のまま | catalog sync / cloud metadata sync | 既存のまま |

**identity は既存を一切動かさない。** 追加は属性と新型だけで、既存 entity の QN が変われば
過去の lineage が参照先を失う。`nemaki_folder_dataset` の QN も新規ではなく、A-1 の
`folderProxyQualifiedName` が既に持っている値である。

**secret 非保持**: 追加属性に URL・query・fragment・userinfo・token・credential・
ローカル絶対パスは 1 つも含まない。`sourceRevision` は provider の revision id、
`storageClass` は `GLACIER` のような列挙値、`tenantId` は tenant の識別子である。
artifact 型と同じ否定検査を適用する。

**§2 の属性上限**: 追加分のうち表示値は無い (すべて識別子・機械値・数量) ため
companion は増えない。identity に使う値は切り詰めない。

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

- `upload://` 文字列は**廃止**。`IMPORT_UPLOADED` の input は `nemaki_import_artifact`。

#### artifact 型の 3 契約 (v2.3.29 — 実装済み)

Atlas 型定義を `PurviewSchemaPayloadFactory` に追加した (schema version 13 → 14)。
`EndpointKind` 側は A-1 で既にあったので、B が負っていたのは**受け側**である。

| 契約 | 規則 | pin |
|---|---|---|
| identity | `Identity.OPERATION_ID`。QN は `nemaki://{repo}/{imports\|exports}/{operationId}` | `anArtifactIsIdentifiedByItsOperation` |
| 属性上限 (§2) | 表示値だけが `TRUNCATE_WITH_EVIDENCE` — import は `originalFileName`、export は `name`。`importMode` / `artifactKind` / `contentHash` は機械値と digest なので `PRESERVE` | `artifactDisplayValuesCarryTheirTruncationEvidence` |
| secret 非保持 (§4) | **場所を表す属性を一切持たない**。`url` / `uri` / `link` / `href` / `path` / `token` / `secret` / `credential` / `signature` / `sas` を含む名前は、allowlist にも entityDef にも現れてはならない | `anArtifactTypeCarriesNoLocationOrSecretAttribute` |

- **なぜ folder ではなく operation で識別するか**: folder で識別すると
  `computeEventKey` が 2 回目の import に 1 回目と同じ key を与え、`append()` が
  **黙って捨てる**。この失敗は無音なので、設計文だけに置かず test で pin した。
- **なぜ場所属性を置かないか**: 出所・宛先は operation の性質であり、
  `nemaki_import_process.sourceDescription` / `nemaki_export_process.targetDescription`
  が既に運んでいる。artifact 側にも置くと、同じ operator 由来のパスが
  **2 つ目のより長命な保持規則**の下に入る。`originalFileName` は leaf name であって path ではない。
- 併せて **manifest と payload の突合せ**を追加した (`manifestTypeNamesMatchTheTypesThePayloadCreates`)。
  両者は別ファイルで編集され、片方だけに型を足しても従来は誰も気づけなかった —
  payload だけに足すと manifest hash が変わらず、適用済みのデプロイは「最新」と判断して
  型を作らない。

### 移動したのは folder ではなく内容である (v2.3.13)

v2.3.12 まで import / export の shape は folder を端点にしていた (import は output が
folder proxy、export は input が folder proxy)。**目的に照らして誤りだった。** この機能が
追跡するのはコンテンツ移動チェーンであり、folder は容れ物である。後から folder の中身が
変われば、「その時点で ZIP に何が入っていたか」を lineage から復元できない。

| processType | v2 shape (chunk 単位) |
|---|---|
| `IMPORT_FILESYSTEM` / `IMPORT_UPLOADED` | `nemaki_import_artifact` ×1 → **移動した document / folder 群** ×1..n |
| `EXPORT_FILESYSTEM` / `EXPORT_ZIP_FOLDER` / `EXPORT_SELECTED_OBJECTS` | **移動した document / folder 群** ×1..n → `nemaki_export_artifact` ×1 |

- 容れ物 folder (取込先 / 搬出元) は endpoint ではなく **Process 属性** (`folderId` /
  `targetFolderId`) として残す。補助情報であって、移動したものではない。
- **shape 規則は v2 書込み開始後、widening-only とする (v2.3.14)。** v2 の decoder は
  正典 constructor 経由で復元し、そこには shape 検証が入っている — つまり保存済み行は
  読み直すたびに現行の shape 表を通る。今 (writer が v1 の間) は表を自由に直せるが、
  Slice 4b 以降に規則を**狭める**と、書込み時に合法だった行が読めなくなる。広げるのは常に
  互換 (旧行は通り続ける)。狭める必要が生じたら、それは新しい schemaVersion である。
- 大量文書は §2 の chunking (1,000 endpoint / 1 MiB) がそのまま適用される。chunk ごとに
  自分の Process を publish し、全体は `operationId` と manifest (増分 B の artifact 属性)
  が束ねる。
- `IMPORT_UPLOADED` の第二 shape (external asset → document) は**削除**。あれは
  archetype null fallback (confirmed bug、下記) の写しであって、業務の形ではない。
- 空 folder だけの import / export も `CMIS_FOLDER` が内容として現れるので表現できる。
  v1 producer は成果 0 件では emit しない (`objCount > 0` guard) ため、空集合の shape は
  要らない。
- **`operationId` は内部契約として必須。公開 API の必須パラメータにはしない。**
  現状 `.runId(...)` を設定している producer は**存在しない** (実測 0 件)。v1 の
  `/exports/{runId}` は全 event が空値へ衝突する設計だった。
  - サーバーが業務処理の**開始時に UUID を発行**する。既存 import/export API のリクエスト形式は変えない。
  - streaming 開始前に生成し、完了時の lineage event まで同一値を持ち回る。
  - response header (`X-Nemaki-Operation-Id`) と body に返し、追跡可能にする。
  - client 供給の `Idempotency-Key` は**将来の任意機能**とし、本増分では実装しない。
- artifact entity は lineage publish と同一 bulk で作る (CMIS オブジェクトではないため catalog sync 対象外)。

### processKey と deliveryId の分離 (v2.3)

v2.2 は `eventKey` 一つで論理的な業務同一性と journal の配送同一性を兼ねていた。**成立しない。**

- `eventKey` には `replayGeneration` も chunk 座標も入っていないのに、補償 event は別 `_id` で作る設計だった。
- 現実装は
  [eventKey が既存なら append を捨てる](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/CouchLineageJournalStore.java#L257)。
  補償や chunk が同一 `eventKey` を持てば**握り潰される**。
- spool の file 名も `eventKey` なので、補償と chunk が**同じファイルに衝突する**。

二軸に分ける。

```
processKey = "v2:" + H("PROCESS", repositoryId, processType, canonical(inputs), canonical(outputs),
                       operationId, schemaVersion, chunkIndex, chunkCount)
  → Atlas Process の qualifiedName に使う論理的業務 ID
  → chunk を別 Process として部分 publish するため chunkIndex/chunkCount を含む

deliveryId は deliveryKind ごとの tagged union とする (v2.3.1):

  ORIGINAL = H("ORIGINAL", processKey, canonicalTargetSet)
  REPLAY   = H("REPLAY",   originalDeliveryId, target, replayGeneration)
  REPAIR   = H("REPAIR",   deadLetterId, repairGeneration)

  → journal document の _id、spool の file 名、冪等判定に使う
```

v2.3 の式は単数の `target` を含んでいたが、ORIGINAL は `publishStatusByTarget` を持つ
**multi-target 文書**であり、そこに渡す `target` が定義できなかった。ORIGINAL は
`canonicalTargetSet` (target 名を辞書順に正規化した集合) を使い、target 単位の同一性が要るのは
REPLAY だけ、と分ける。

### hash 入力の直列化 (v2.3.1)

`H(...)` は SHA-256 だが、**単純連結にしない**。連結は
`("ab","c")` と `("a","bc")` を区別できず、ID 衝突を作れてしまう。

- 各要素を **長さ prefix 付き UTF-8** (`len:bytes`) で直列化するか、canonical JSON を使う。
  どちらかを実装時に固定し、golden test で凍結する。
  → **実装時に前者を採用**。型 tag 1 byte + 長さ/整数は big-endian 固定幅
  (`LineageCanonicalHash` の javadoc が正典)。
- `null` と空文字は**別の表現**にする (`null` は専用マーカー)。
- **並び順は符号なし UTF-8 バイト辞書順**とする。Java の自然順 (UTF-16 code unit) では
  Python / Go の実装と U+E000 以上で食い違い、外部の repair / DLQ ツールが別 ID を作る。
  `core/src/test/resources/lineage/reference_hash.py` が仕様のみから書いた別実装で、
  golden vector が一致することを確認済み。

### digest は 4 種類ある (v2.3.28)

すべて SHA-256 の小文字 hex なので**見た目では区別できない**。1 つに寄せると壊れるので、
種類と比較可能性を固定する。正典は `LineageDigests` の javadoc。

| 種類 | 実装 | domain 分離 | 幅 | 比較してよい相手 |
|---|---|---|---|---|
| identity | `LineageCanonicalHash.hash` | あり (型 tag + 長さ prefix そのもの) | 64 hex | 同じ型構造の identity hash のみ |
| distribution | `LineageBinaryDigest` | あり (`BARRIER_BINARY_V1` tag) | 64 hex | 設定の `approvedBinaryDigests` |
| evidence | `LineageDigests.evidenceDigest` | **なし** (元値の UTF-8 の素の SHA-256) | 64 hex | 同じ元値からの再計算 |
| redaction | `LineageDigests.redactionDigest` | **なし** | **12 hex (切詰め)** | **なし。**ログで「同じ値か違う値か」を人に伝えるだけ |

- redaction は evidence の **prefix** である。取り違えると「digest らしい」検査を通りながら
  衝突耐性が 52 hex 分減る。`EndpointAttribute.isEvidenceDigest` が幅で拒否し、
  `LineageDigestKindsTest` が pin している。
- identity / distribution は**共通化しない**。SHA-256 に渡すバイト列が型 tag と長さを
  持つことが契約そのもので、素の helper を経由させると golden vector が静かに壊れる。
- 切詰めも 2 種類あった。§2 の `EndpointAttribute.truncationLength` (surrogate 対応) と、
  `LineageEventBuilder` にあった素の `substring` である。後者は本番呼出しが無く、
  最終文字を壊し得たので**削除**し、snapshot 系も §2 の規則に合流させた。

### endpoint 集合の正規化 (v2.3.1)

`canonical(inputs)` / `canonical(outputs)` / `canonicalTargetSet` の規則を固定する。

| 論点 | 規則 |
|---|---|
| 重複 endpoint | **許容しない**。同一 `catalogQualifiedName` が 2 度現れたら `build()` で拒否 |
| 順序 | `catalogQualifiedName` の**符号なし UTF-8 バイト辞書順**に並べる。producer の指定順は同一性に影響しない |
| null | 要素の null は拒否。空リストは「空」を表す専用マーカーで直列化する |
| target 集合 | target 名を**符号なし UTF-8 バイト辞書順**、trim、重複除去 |

### event-level `operationId` (v2.3.1)

**全 v2 event に event-level の `operationId` を必須**とする。
§3 の artifact 用 `operationId` (endpoint 側) とは**別契約**である。

契約は「サーバー発行・非 blank」であって、**canonical UUID 形式ではない**。
`LineageIdentity` は非 blank だけを検査する。値域検査をそこに置いたのは
chunk 3/2 や generation 0 のような**業務状態として存在し得ない**値を弾くためで、
UUID でない `operationId` は存在し得ない値ではなく「うちの producer が出さない値」に過ぎない。
形式まで縛ると v1 由来の record を legacy reader が読めなくなる。
producer 側で UUID を発行することは §3 の責務のままとする。

- event-level: 業務操作 1 回を識別する。`processKey` に入る。
- endpoint-level: artifact entity の QN に入る (`imports/{operationId}` / `exports/{operationId}`)。
- artifact を持たない processType (`ARCHIVE_LOCAL` など) でも event-level は必須。
  これにより**同じ endpoint 集合で繰り返す非 artifact 操作も別 process になる**。

### `creationPayloadDigest` の対象 (v2.3.1)

create-if-absent の 409 で「同一かどうか」を判定する digest の対象を固定する。

| 含める | 除外する |
|---|---|
| `processKey` / `deliveryId` / `schemaVersion` / `repositoryId` / `processType` | `sequence` / `state` / `sequencerGeneration` |
| `operationId` / `occurredAt` / `inputs` / `outputs` (endpoint 属性込み) | `publishStatusByTarget` / `claimToken` / `replayRequestsByTarget` |
| `chunkIndex` / `chunkCount` | `_rev` / `_id` 以外の CouchDB メタ |
| | **`spoolRecordId`** (§6-a。監査・逆引き専用) |

`spoolRecordId` を**除外する**のは避けようのない罠があるからである。同じ業務 fact が
「直接 emit された event」と「spool から materialize された event」の両方で現れうるが、
`deliveryId` はどちらも同じ (`processKey` に `spoolRecordId` は入らない) なので、digest に入れると
**同一 `_id` で digest 不一致**という偽の integrity 違反が立つ。監査に必要な値ではあるので
持つが、同一性の判定には使わない。

**digest 不一致時の扱い (v2.3.1)**

| 経路 | 挙動 |
|---|---|
| 通常 emit | 業務 caller に 500 を返さない (fail-open 契約)。**integrity 例外として spool へ落とし、metric `lineage.digest.mismatch` を出す** |
| 管理 replay / repair | `FAILED` にして 500 を返す。運用者が異常を認識すべき経路だから |

- 冪等判定 (`append` の重複検出) は **`deliveryId`** で行う。`eventKey` では行わない。
- `idempotencyKeyVersion` は `processKey` の版として持つ (§3 v2.1 の記述をここに集約)。
- **create-if-absent の 409 は「成功」ではない。** 既存文書を読み直し、
  **immutable payload の digest が完全一致した場合のみ**成功として扱う。
  不一致は ID 衝突という異常であり、**扱いは上表のとおり経路で分かれる** — 通常 emit で 500 を
  返せば fail-open 契約を破ることになるので、そちらは integrity 例外 → spool + metric である。
  この規則は §8-a の event-first append、§8-d の補償 event、§9 の repair、§6-a の materialize の
  **すべてに適用される**。「409 = 冪等に成功」と書いてある箇所は無い。
- 補償 event は元の `processKey` を保つ (同一業務操作だから)。区別は `deliveryId` が担う。

#### `occurredAt` は一度だけ採番する (v2.3.12)

`occurredAt` は `creationPayloadDigest` に入り、§6-a では `spoolRecordId` にも入る。したがって
**再試行のたびに値が変われば、同じ業務 fact が別の identity を持つ**。

現行 [`LineageEventBuilder.build()`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageEventBuilder.java#L194)
は `Instant.now()` を `build()` の中で呼んでおり、**同じ builder から二度 build すれば別の event に
なる**。v1 では `_id` が UUID だったので害が無かったが、v2 では identity が壊れる。

A-2 Slice 1 で次を契約にする。

| 項目 | 規則 |
|---|---|
| 採番点 | 業務 fact が成立した瞬間に **emitter が一度だけ**採る。あらゆる fallible 操作より前 |
| 受け渡し | builder の必須入力とする。`build()` の中で現在時刻を読まない |
| `build()` | **入力の純関数**とする。同じ入力から二度呼べば `eventId` を除き完全に同一の event になる |
| 再試行 | spool・bundle・再起動・repair のどの経路でも、最初に採った値を**そのまま運ぶ**。再構成しない |
| 検査 | 同一 builder から 2 回 build して digest が一致することを単体テストで固定する |

`eventId` (UUID) は identity に入らない監査用フィールドなので純関数性の対象外とする。

**この契約が破れると実行時にも露見する。** `occurredAt` は `creationPayloadDigest` に入るが
`processKey` には入らないので、同じ業務操作を `occurredAt` だけ変えて二度書くと
**`deliveryId` は同じで digest だけ違う** — create-if-absent が 409 を返し、digest 不一致で
integrity 例外になる。つまり「`lineage.digest.mismatch` が同一 `deliveryId` で繰り返し上がる」は
ID 衝突ではなく **`occurredAt` を引き直している**ことの症状である。metric の解釈として明記する。

### processKey の直列化 (v2 / v2.3.1 で `deliveryId` に分離)

現行は 32bit Java hash 2つの連結で、衝突すると別操作が同一イベント扱いになる。

```
idempotencyKeyVersion = 2   // processKey の版
processKey     = 上記 (v2.3)
deliveryId     = 上記 (v2.3)  // 冪等判定はこちら
legacyEventKey = (v1 からの写像・repair 時のみ保持。v1 の 32bit hash 形式)
```

`canonical(...)` は §4 の canonical QN を辞書順に連結したもの。`operationId` を含めることで、
同一 endpoint 集合の反復操作が別 event になる。

- **`idempotencyKeyVersion` を明示的に永続化する。** v1 と v2 を暗黙に同一視しない。
  **v2 経路の冪等判定は `deliveryId` のみで行い、`eventKey` は使わない (v2.3.1)。**
  `legacyEventKey` は **v1 読取と監査専用**であり、v2 の判定・ID 生成には一切関与しない。
- **v1 の replay / repair は既存 Process を更新しない。** v2 の補償 event を新設し、
  その Process QN は v2 の `processKey` 由来になる。v1 の Process はそのまま残る (監査事実)。
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
filesystem の絶対パスをそのまま QN にすると、ホスト構成が catalog の主キーとして永続化される。

### v2 規則

**すべての external asset は repo-scoped canonical QN に統一する。**

```
qualifiedName = nemaki://{repositoryId}/external-assets/{base64url(stableKey)}
```

`stableKey` は endpoint kind ごとに決める。**既存 catalog sync が書いている書式が正典**であり、
下表はそれと一致している (v2.3.2 で `cloud://` / `cold://` / `file://` を撤回。あれは
実装時に新設した独自 scheme で、既存 entity と別 QN になっていた)。

| kind | stableKey | 生成 |
|---|---|---|
| `EXTERNAL_ASSET` (ingest) | connector が持つ安定 ID をそのまま | `ExternalAssetIdentity.opaque` |
| `CLOUD_OBJECT` | `{provider}:{externalFileId}` | `ExternalAssetIdentity.cloud` |
| `COLD_STORAGE` | archive の `contentRef.ref` をそのまま | `ExternalAssetIdentity.opaque` |
| `EXTERNAL_ASSET` (filesystem, `sourceSystem=filesystem`) | `filesystem:{絶対正規化パス}` | `ExternalAssetIdentity.filesystem` |

**`ExternalAssetIdentity` が唯一の実装**である。key の生成・正規化・安全検査・QN 符号化が
すべてそこにあり、lineage と catalog sync の両方が通る。`qualifiedName` は検証済みの
`StableKey` しか受け取らないので、未検査の String が QN になる経路が型として存在しない。
(v2.3.1 の「`LineageEndpointCatalog` (新規) の1箇所」は、この既存 factory を置き換える別クラスを
作る想定だった。既に本番 entity を書いている実装がある以上、新設ではなく**集約**が正しい。)

#### `base64url` は保護ではない (v2.3)

`base64url(stableKey)` は**可逆**である。既存 QN 互換のため方式は維持するが、
これは「raw URI や絶対パスを事実上 Atlas の QN に格納している」ことを意味する。
HMAC 付き bundle (§9) も**完全性の保証であって暗号化ではない**。明示的に契約を置く。

| 項目 | 契約 |
|---|---|
| stableKey に入れないもの | **認証情報 (userinfo)、署名付き URL、query string、fragment、制御文字、前後空白**。producer 側で除去する。`ExternalAssetIdentity.parse` が除去漏れを**両経路で拒否**する (QN は可逆 base64 なので、漏れると catalog entity から復元できてしまう) |
| 各規則の適用範囲 | `?` `#` は **filesystem path 以外の全 key** で拒否する (URI 形状かどうかを問わない)。opaque な connector ID にこれらが入っている場合、剥がし忘れた URL である可能性の方が高く、fail-closed 側を採る。filesystem path だけは例外で、`?` `#` はファイル名に使える普通の文字であり、拒否すると正当なファイルが lineage に載らなくなる。userinfo は authority が存在する key (`://` を含むもの) でのみ検査する — `@` 単体は mailbox path 等で正当 |
| 検査の限界 | 区切り文字を伴わない token が opaque ID に埋まっている場合は通常の ID と区別できず、そこは producer の責任として残る。この検査は「producer が剥がしたことの確認」であって代替ではない |
| stableKey の書式 | **既存 catalog sync が正典**。cloud = `{provider}:{externalFileId}`、filesystem = `filesystem:{絶対正規化パス}`、cold = archive の `contentRef.ref` をそのまま。`ExternalAssetIdentity` が唯一の実装で、lineage と catalog sync の両方がそこを通る。独自 scheme (`cloud://` 等) を作ると同一資産が Atlas 上で 2 entity に割れ、A-2 以降は `processKey` まで変わるので後から直せない |
| custom property mapping | 出力名 (`catalogName`) が予約名・空白の mapping に加え、**入力 (`cmisPropertyId`) が禁止集合のものも拒否**する。`nemaki:cloudFileUrl` は禁止 — 出力名を無害なものにすれば (`legacyCloudUrl` 等) 出力側の検査を全部通り、A-1g が取り除いた生 URL が別名で載るため。旧文書は cloud metadata を `subTypeProperties` に持つので、この保存形は仮定ではない。検査は save / load / payload 最終境界の 3 箇所で、全て同じ規則集合を通る — save と payload 境界は `rejectionFor(cmisPropertyId, catalogName)`、load (保存済み mapping) は同規則 + 保存形固有の検査を加えた `rejectionForStored` で、後者は前者へ委譲するため規則が分裂しない (理由 enum を返すので、save は「どちらの端が悪いか」を operator に出せる)。payload 境界の `containsKey` は mapping ではなく**構築中の entity** を見る別規則であり、統合しない |
| 壊れた mapping 設定 | `catalogName` が JSON null / 非文字列 / 欠落の場合、enabled なら**その 1 件のみ**除外 (WARN + `getRejectedMappingCount()`)、他の mapping は投影を継続。CMIS property ID への流用は**廃止** (誰も設定していない出力名を黙って作るため)。disabled mapping は形が壊れていても**保持**し、rejection に数えない — 投影しないので無害であり、admin UI が表示・修正する対象だから |
| 既発行値の除去 | **本増分が保証するのは新規発行の停止のみ。** null は payload に載る (client の ObjectMapper は Jackson 既定の inclusion) が、backend が既存 property を削除するか null を無視するかは Atlas OSS と Purview で異なり、**どちらも未検証**。A-1g 以前に発行済みの raw URL の除去は別作業とし、release gate の live Atlas E2E で「republish 後に旧 URL が実際に消えているか」を確認する。消えない backend なら明示的な purge / entity 再作成手順を用意する |
| 表示 URL (`cloudFileUrl` / cloud の `externalPath`) | **Atlas 永続化境界は保存 URL を一切運ばない**。query 剥離では足りない — SharePoint の modern sharing link は token を **path** に置き (`/:x:/g/…TOKEN`、`CloudDriveResource` が受理する形)、`%3Ftoken` や `;auth=` のような path 内表現もあるため、保存 URL のいかなる変形も secret-free を保証できない (v2.3.2 の sanitize 案は撤回)。現契約: cloud の lineage endpoint は `externalPath` を持たない (allowlist 外・表現不能)、sync external asset の `externalPath` と process の `targetDescription` は `externalFileId`、document の `cloudFileUrl` は **null**。表示 URL が要るなら増分 B で `{provider, fileId}` から provider 別 canonical URL を**再構成**する — 保存値の変形ではなく |
| stableKey の保持 | external / cloud / cold は `attributes.externalStableKey` に**必須**で持つ。QN はそこから再計算でき、§7 の検証は prefix 一致ではなく**完全一致**で行う (QN が key A、attribute が key B という endpoint は、catalog が名前で解決する実体と snapshot が attribute で解決する実体が食い違う) |
| filesystem path | 正規化済み絶対パス。`/srv/in/./a.pdf` と `/srv/in/b/../a.pdf` が 2 資産にならないこと |
| Atlas 閲覧権限 | QN からホスト構成・外部識別子が読めるため、**Atlas の閲覧権限は catalog 管理者に限定**する運用前提を明記する |
| spool / bundle | **encryption at rest を要件**とする (volume 暗号化 or アプリ層暗号化)。完全 payload を含むため |
| 「protected」の意味 | **ログに出さない**ことのみを指す。**秘匿化ではない** |
| 秘匿性が必要な場合 | HMAC / SHA-256 ベースの QN へ移行する設計が別途必要。**本増分では扱わない** (既存 QN 互換を優先) |

- raw URI は QN にはせず、`externalStableKey` 属性として entity に保持する。
- **ログ・例外に出さない対象は「external kind の QN と stableKey」**である (v2.3.1 までは `file://`
  と書いていたが、その scheme 自体を撤回した)。判定は文字列の形ではなく
  `EndpointKind.Identity.STABLE_KEY` で行う。`LineageEndpoint.toString()` も redact 済み。
- `externalPath` は stableKey から**導出**する。入力値をそのまま保存すると、正規化された key と
  食い違い、同一 entity が 2 通りのパス表記を主張する (A-2 の `creationPayloadDigest` は
  endpoint 属性を含むため、再試行で digest 不一致になり得る)。
- **`externalStableKey` は protected 扱い**とし、ログ・エラーメッセージ・dead letter reason に
  そのまま出さない。出力する場合は SHA-256 の先頭 12 桁のみ。`file://` の絶対パスと
  cloud の識別子が対象。
- canonical 化は `ExternalAssetIdentity` の1箇所 (v2.3.2: 新設ではなく**集約**。既に本番 entity を
  書いている実装があるため置き換えではない)。既存 `PurviewEntityPayloadFactory` の
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

## 6-a. v2 書込みの rollout fence (v2.3.11)

### 撤回 1: 停止条件が片方向だった (v2.3.8)

停止条件を「新 projector が v1 と v2 を両方読めるか」と書いていた。危険なのは逆向き —
**旧 AP が v2 を読む**方であり、コードを 1 コミットで変えてもデプロイは同時でないため消えない。
旧 AP が leader のまま v2 event を掴み、`FAILED` 化するか、最悪は **cursor を通過**させて静かに
欠落させる。

### 撤回 2: 旧バイナリに新契約を守らせようとしていた (v2.3.9)

「v1-only AP は自分で fail-closed になる」「stale leader は読めない版を claim しない」と書いた。
**成立しない。** 旧バイナリは既にデプロイ済みで、その guard を持たない。projector の view 選択は
`doc.type === 'lineage_event'` **だけ**で、schema version で分離していない。新バイナリに書いた
fail-closed は *次に起動する新バイナリ* にしか効かない。

「`readSchemaVersions=[1]` を設定した新 reader」のテストも旧バイナリの証拠にはならない
(新しい claim guard を持っているため)。残すが、**新版どうしの混在保証**であることを
テスト名と javadoc に明記する。

**旧バイナリの排除はアプリ外の完了条件**とする — 旧 ReplicaSet / Deployment revision 削除、
LB / Service target からの除外、旧 Pod の消滅。アプリは検証できない。

#### v2 文書は別の document type を持つ (v2.3.14)

撤回 2 の事実 — 旧 projector の view 選択は `doc.type === 'lineage_event'` **だけ** — は、
裏返すと防御に使える。**v2 文書の `type` を `lineage_event_v2` にする** (A-2 Slice 2d-1 で
codec に実装済み)。旧バイナリの view は v2 行を**構造的に返せない**ので、claim も publish も
cursor 前進も起こしようがない — scale-to-one 手順が「存在しないはず」と言った混在クラスタに
v2 文書が紛れ込んでも、である。手順で禁じていたこと (Slice 2 レビュー F9 の「共有環境への
手動 v2 投入禁止」) が、構造的性質になる。

**fence は規範のまま**である。これは防御の重ね掛けであって、代替ではない — 旧バイナリは
view 以外の経路 (直接 `_id` 参照など) を持ちうるし、`minReaderSchemaVersion` が守るのは
これとは別の不変量である。

代償は恒久的で、意図して払う: **Slice 2d-2 以降に足す全 view / query は両 type を対象に
しなければならない**。漏らすと v2 が新バイナリから見えなくなる (静かな欠落)。旧バイナリは
直せないが新バイナリはテストできる、というのがこの取引を選ぶ理由である。2d-2 は
「全 view が両 type を返す」ことをテストで固定する。

decoder は逆向きも拒否する — `schemaVersion=2` なのに `type=lineage_event` の文書
(旧 view に見える形で v2 payload を持ち込む偽装) は復号を拒む。

### 撤回 3: barrier に鮮度が無く、昇格が原子的でなかった (v2.3.10)

v2.3.9 は ACK を別文書に置き、`minReaderSchemaVersion` は「v2 を最初に永続化した時点で 2 になる」
と書いた。**二つとも穴がある。**

- ACK に `bootId` も期限も無いので、**同じ nodeId が再起動して旧バイナリになっても過去の ACK が
  残る**。ACK は「今この node が新版で動いている」証拠であるべきなのに、そうなっていない。
- 昇格が別書込みなので、`writeSchemaVersion=2` と `minReaderSchemaVersion=2` の間に窓ができる。
  その窓で v1-only reader が正当に参加できてしまう。

**ACK を barrier 文書そのものへ CAS で追加する。** そうすれば `ACTIVE` への CAS が同一 `_rev`
に対して全条件の検査と両 flag の更新を**一度に**行える。

### barrier 文書

```
_id: lineage_write_version
  state                      : IDLE | PREPARING | ACTIVE
  generation                 : 単調増加
  expectedMembershipDigest   : SHA-256(sorted expectedNodes)
  expectedMembershipRevision : 外部 control-plane が返した membership revision
  expectedNodes              : [ { nodeId, bootId } ]
  requiredCapabilities       : [ ... ]   // 下表。ACK 側が満たすべき能力
  approvedBinaryDigests      : [ ... ]   // 空なら digest 検査を課さない
  acks:
    {nodeId}:
      generation             : この ACK が答えた barrier generation (v2.3.23)。世代が上がれば
                             //   古い ACK は条件 3〜11 で無効 — 「世代を上げれば無効」と
                             //   本文が言うのに ACK が世代を持たなければ効かない
      bootId                 : この boot の識別子。再起動で変わる
      binaryDigest           : 動作中バイナリの識別子
      capabilities           : [ ... ]   // この binary が実際に持つ能力
      readSchemaVersions     : [1, 2]
      spoolRecordSchemaVersions  : [1]      // 読み書きできる spool RECORD の版 (実装は 1 のみ)
      materializeEventSchemaVersions : [1, 2] // spool fact から材料化できる EVENT の版
      spoolReady             : true     // 永続 volume + write + fsync 検証済み
      drestReady             : true     // ACK 時点の LineageDrestReadiness.ready (v2.3.23)
      drestViolations        : [ ... ]  // 上が false のときの違反列挙 (診断用)
      ackedAt / expiresAt    : 鮮度。期限切れ ACK は無効
  writeSchemaVersion         : 1 | 2
  minReaderSchemaVersion     : 1 | 2    // 単調増加のみ
```

#### capability は「読めるか」だけでなく「直っているか」を表す (v2.3.12)

v2.3.11 は「4b の CAS は D-rest 配布済みであることも前提に含める」と本文に書きながら、
**barrier 文書にも CAS 条件にもそれが無かった**。`binaryDigest` は記録するだけで誰とも比較せず、
改訂前の条件 1〜8 は reader と spool の能力しか見ていない。scale-to-one は旧 AP の**同時実行**を防ぐが、
「唯一の AP が、v2 を読めるが D-rest を持たない中間ビルドである」ことは防がない。
それでは §6-a が守ろうとしている「v2 event を既知の壊れた journal 経路へ流さない」が破れる。

capability を明示的な語彙にして、ACK と barrier の両方に持たせる。

| capability | 意味 | 由来 |
|---|---|---|
| `read:v2` | v2 event を復号し projector へ流せる | **A-2 Slice 2 の最終 commit** — 型が在るだけの Slice 1 ではない |
| `spool:v2` | 版非依存 fact を materialize できる | **D-rest** (v2.3.17 ⑥ — 表明は「materialize できる」であり、store と scanner だけの D-spool では立てられない) |
| `sequencer:event-first` | 8-a v2 (event-first UNSEQUENCED + fenced finalizer) | D-rest |
| `cursor:cas` | 8-c (単調 CAS。publish 永続化を確認してから前進) | D-rest |
| `replay:generation-cas` | 8-d (replay generation の CAS 状態機械) | D-rest |

`requiredCapabilities` は barrier 文書側に置く。**server-defined かつ弱められない** (v2.3.23):
必須集合 {`read:v2`, `sequencer:event-first`, `cursor:cas`, `replay:generation-cas`,
`spool:v2`} はバイナリ側の定数であり、barrier 文書がそれより**狭い**集合を宣言していても
CAS は定数側で検査する — 文書を書き換えて gate を緩められる形にしない。文書側で
**足す**ことはできる。**ACK 側の宣言だけを信じる形にしない**ため、
運用が承認したビルドの `binaryDigest` を `approvedBinaryDigests` に列挙できる
(空なら課さない。単一 AP 運用では digest を 1 つ書くだけで足りる)。

#### `ACTIVE` への CAS が同一 `_rev` で検査すること

1. `state == PREPARING` かつ `generation` が要求と一致
2. 要求が再提示した `expectedMembershipRevision` と `expectedMembershipDigest` が文書と一致
3. `expectedNodes` の**全 node**に ACK があり、各 ACK の `generation` が文書の `generation`
   と**一致する** (v2.3.23)
4. 各 ACK の `nodeId` / `bootId` が `expectedNodes` の値と一致
5. 各 ACK が**未期限切れ**
6. 各 ACK の `readSchemaVersions` が 2 を含む
7. 各 ACK の `spoolReady == true` かつ **`materializeEventSchemaVersions` が 2 を含む**
   (v2.3.23 訂正: 旧文の「`spoolSchemaVersions` が 2 を含む」は**実装に対して充足不能**だった —
   spool record の版は 1 だけで、codec は他の値を拒否する。2 を要求すべきは「fact から
   materialize できる **event** の版」であって record の版ではない。record 側は
   `spoolRecordSchemaVersions` として別に報告する)
8. 各 ACK の `capabilities` が `requiredCapabilities` を**包含**する
9. `approvedBinaryDigests` が空でないなら、各 ACK の `binaryDigest` がそこに**含まれる**
10. **各 ACK の `drestReady == true`** (v2.3.23 追加)。capability は「このバイナリに配線がある」
    という**静的事実**であり、readiness は「今この node で実際に動く」という**動的事実**である。
    両方を要求しないと、`ACTIVE` が v2 書込みを開くのに sequencer/projector が dormant のまま —
    という状態が作れてしまう (`lineage.drest.enabled` が false・view 署名 drift・検証不能 sink・
    spool probe 失敗のいずれでも起きる)。ACK 受理時と `ACTIVE` CAS 時の**両方**で新鮮な
    readiness を要求し、違反ごとに決定的な否定テストを置く。
11. **同じ書込みで** `writeSchemaVersion = 2` と `minReaderSchemaVersion = 2` を設定

11 が同一 CAS に入ることで、`writeSchemaVersion=2` だが `minReaderSchemaVersion=1` という状態は
**存在し得なくなる**。8 と 9 が入ることで D-rest を持たないビルドでは 4b が通らず、10 が入ることで
**配線はあるが動いていない**ビルドでも通らない。

`generation` が変われば過去の ACK は無効 — ACK は `(nodeId, bootId, generation)` に紐づく。

#### membership revision は activation 要求時に再提示する

`PREPARING` 作成時の digest だけでは、テスト #2 (確認後・CAS 前の membership 変更) が
**発火しない**。要求側が外部 control-plane から取得した `expectedMembershipRevision` を
`ACTIVE` 要求時に**もう一度**渡し、barrier 文書の値および `_rev` に bind する。
control-plane 側で node が増減すれば revision が変わり、CAS が落ちる。

#### 撤回 5: revision の再提示では TOCTOU が閉じない (v2.3.11)

v2.3.10 は「activation 要求時に membership revision を再提示すれば閉じる」と書いた。**閉じない。**

- アプリは、渡された revision が**最新かどうかを検証できない**。control-plane に問い合わせる
  手段を持たないなら、それは単に「要求者が言った値」である。
- revision を取得してから `ACTIVE` CAS までの窓で membership が変わり得る。署名付き token に
  しても「取得後の変更」は防げない。

オンライン多重 AP 切替を**安全と主張するには**、control-plane 側が membership を freeze する
lock / rollout pause を保持したまま CAS する必要がある — lock ID・revision・期限を
control-plane が保証する形である。NemakiWare は現時点でその連携を持たない。

**したがって v3.3 の規範的 rollout は単一 AP 切替とする。**

| 手順 | 内容 |
|---|---|
| 1 | 全旧 AP を停止する (Terminating ではなく消滅を確認) |
| 2 | 新版を **1 台だけ**起動する |
| 3 | spool readiness (永続 volume + write + fsync) を確認する |
| 4 | barrier を `ACTIVE` へ CAS する |
| 5 | 新版のみで scale-out する |

membership の推測も再提示も要らず、旧バイナリが存在しないことは手順 1 で担保される。

#### rollback と再活性化の状態機械 (v2.3.23)

`writeSchemaVersion` が `1 ⇄ 2` であることだけ書いて、2 への経路が `PREPARING → ACTIVE`
しか定義されていなかった。往復を完全に定義する:

| 遷移 | 条件 | 効果 |
|---|---|---|
| `ACTIVE` → `ACTIVE` (rollback) | 管理操作。**条件検査なし** (安全側への移動なので緩める) | `writeSchemaVersion = 1`。`minReaderSchemaVersion` は 2 のまま (単調)。`generation` と `acks` は**そのまま** — rollback 自体は「今の構成のまま書込みだけ v1 に戻す」操作であり、ACK を捨てる必要が無い。ACK を捨てるのは**再活性化の入口** (次行) であって rollback ではない |
| `ACTIVE` → `PREPARING` (再活性化の開始) | 管理操作。`writeSchemaVersion == 1` であること | `generation` を **+1** し、**同じ書込みで `acks` を空にする**。ACK 側も `generation` を持つので条件 3 が二重に効く (世代不一致 + 不在)。再活性化は**新しい ACK を集め直すことを意図している** — 「一度 ACTIVE にした構成」を根拠に無検査で戻せてはならず、その間に readiness が赤化している可能性もあるため |
| `PREPARING` → `ACTIVE` | 条件 1・3〜11 (単一 AP では 2 は適用外) | `writeSchemaVersion = 2` と `minReaderSchemaVersion = 2` を同一書込みで設定 |

決定的テスト (4a 完了条件に追加): **#20** rollback 後に `PREPARING` を経ずに
`writeSchemaVersion` を 2 へ戻す要求は CAS が拒否する。**#21** 再活性化の `PREPARING` は
generation を上げ、旧 generation の ACK では `ACTIVE` に到達できない。

#### v3.3 の規範形 = 単一 AP 状態機械 (v2.3.23 で完全化)

前段の 11 条件は multi-node barrier の完全形で、`expectedNodes` / membership digest /
revision 再提示を要求する。**これは v3.3 では実装しない** — control-plane 連携が無く、
規範 rollout が単一 AP だからである。両方を並べたまま「仕様どおり実装せよ」と言うと矛盾
するので、ここで v3.3 が実装する形を固定する:

| | v3.3 (規範・実装対象) | 将来 multi-node (**保証しない**) |
|---|---|---|
| membership | **推測しない**。`expectedNodes` は自 node 1 件、`bootId` は自プロセスの boot id | control-plane の revision を再提示 |
| CAS 条件 | 1・3〜11 を自 node の ACK に対して適用 (2 は**適用外**) | 全条件 |
| 決定的テスト #2 (確認後・CAS 前の membership 変更) | **v3.3 の完了条件から外す** — 単一 AP に membership 変更は無い | 将来 protocol の条件 |

**永続化契約** (CAS テストが決定的であるために全て凍結する):

| 項目 | 固定値 |
|---|---|
| barrier 文書の所在 | `nemaki_lineage` DB (journal と同一 — 追加 DB を作らない)、`_id = lineage_write_version`。1 文書のみ |
| CAS 手段 | CouchDB `_rev` (journal と同じ strict IO: 404/409/障害を三分類) |
| `nodeId` | node 固有かつ**再起動をまたいで不変**。単一 AP では `lineage.node.id` (未設定なら barrier 作成時に採番して同 DB に保存) |
| `bootId` | プロセス起動ごとの UUID v4。再起動で必ず変わる |
| `binaryDigest` | WAR の `WEB-INF/lib` を含む配布物の SHA-256 を hex 小文字。起動時に 1 度計算 |
| `expectedMembershipDigest` | `LineageCanonicalHash.hash("BARRIER_MEMBERSHIP_V1", LIST[ MAP{nodeId, bootId} ])`。LIST は `nodeId` の unsigned UTF-8 昇順。**JSON 文字列を hash しない** |
| 時刻 | `ackedAt` / `expiresAt` は **epoch millis** (v2 の他の可変 field と同じ)。ISO 文字列は使わない |
| ACK の TTL | 既定 300000 ms。期限切れは条件 5 で無効 |
| `drestReady` | ACK 生成時に `LineageDrestReadiness.evaluate()` を呼んだ結果。`ACTIVE` CAS は**再評価**して再確認する (ACK 記録時から gate が赤化していないこと) |

**オンライン多重 AP barrier は「将来設計・v3.3 では非保証」**と明記する。§6-a の barrier 文書と
二段階 ACK はその将来設計として残すが、control-plane 連携が実装されるまで
**安全性を主張しない**。

### 版を二軸にする

| flag | 可変性 | 意味 |
|---|---|---|
| `writeSchemaVersion` | 1 ⇄ 2 | producer が今書く版。rollback で 1 に戻せる |
| `minReaderSchemaVersion` | **単調増加のみ** | `ACTIVE` 昇格と同一 CAS で 2 になり、二度と下がらない |

writer を 1 に戻すことと、v1-only バイナリへ戻すことは**別物**。前者は運用上の rollback として
常に可能で、後者は不可逆である。

#### 撤回 6: rollback 条件を spool の大域走査に置いていた (v2.3.12)

v2.3.11 は「journal / dead letter / spool に v2 が 1 件でも残る限り v1-only バイナリへは戻せない」
と書いた。**判定できない条件だった。** spool の列挙は §8 のとおり **node-local の admin API のみ**で、
集約 endpoint は v3.3 では実装しない。しかも規範的 rollout は scale-to-one なので、rollback を
検討する時点で **v2 を書いた node は既に停止している**ことが普通であり、その永続 volume は
唯一動いている AP から見えない。アプリにも手順にも「どこにも残っていない」を確かめる術が無い。

存在しない検査を条件に書くと、実装は必ずそれを省くか、見えた範囲だけを見て「無い」と答える。
後者の方が危険である。

**fence は `minReaderSchemaVersion` に置く。** これは CouchDB の barrier 文書 1 つで、
どの node からも読め、単調増加で、`ACTIVE` 昇格と同一 CAS で 2 になる。

| 操作 | 条件 | 誰が保証するか |
|---|---|---|
| `writeSchemaVersion` 2 → 1 | いつでも可 | アプリ (CAS) |
| `minReaderSchemaVersion` 2 → 1 | **不可**。単調増加 | アプリ (CAS) |
| 新バイナリを `readSchemaVersions=[1]` で起動する | `minReaderSchemaVersion == 1` のときのみ。2 なら**起動時に lineage subsystem を fail-closed** | アプリ (起動検査) |
| **v1-only の旧バイナリ**を起動する | 禁止 | **運用のみ**。アプリは検証できない (撤回 2) |

3 行目と 4 行目を分けているのは、撤回 2 と同じ理由である。旧バイナリは既にデプロイ済みで
この検査を持たないので、アプリが保証できるのは「**新**バイナリが v1-only reader として
参加しないこと」と「`minReaderSchemaVersion` を下げないこと」までである。旧バイナリを
起動しないことは scale-to-one 手順の step 1 (旧 AP の消滅確認) が担う。

`ACTIVE` 昇格の時点ではまだ v2 event が 1 件も無いので、この規則は「v2 を 1 件も書かずに
activate だけした」場合にも rollback を禁じる。**意図的にそうする** — 緩めるには「本当に 0 件か」を
確かめる必要があり、それがまさに確かめられない条件だからである。activate は片道であると
運用手順に明記する。

**spool の at-rest 暗号化は §4 の要件のまま** (完全 payload を含むため volume 暗号化 or
アプリ層暗号化 — 撤回 7 の表)。D-spool 実装が与えるのは 0700/0600 と safe path であって
暗号化ではない。運用受入 (4b) までに volume 暗号化の確認を release 手順へ含める。
ingest の opaque source id に紛れた秘密は producer (connector) の責任であり続ける
(§4「producer が剥がすことの検査であって、代行ではない」)。

**spool の残留は安全 fence ではなく回収対象**として扱う。停止した node の永続 volume は
`nodeId` 付きで運用 inventory に載せ (§8 の「node 可視化」)、署名付き bundle で回収する。
回収し損ねた fact は lineage の欠落であって、rollback の可否を左右する事実ではない。

### 版非依存 spool (v2.3.10)

flag が読めないとき、**event を spool してはならない** — どちらの版で符号化するか決められない
からである。保存するのは**版に依存しない business fact** とする。

```
{lineage.spool.dir}/{repoSegment}/{yyyyMMdd}/fact-{spoolRecordId}.json
  // repoSegment = urlsafe_base64(repositoryId) + "-" + sha256(repositoryId)[0:16 hex] —
  // 生の repositoryId は path に使わない (§8 の安全性表: traversal / 大文字小文字衝突)。
  // {yyyyMMdd} は occurredAt から UTC で導出 (書込み時刻ではない — retry の path 収束)。
  spoolSchemaVersion   // この spool record 自身の版
  spoolRecordId        // 版非依存の一意 ID。ファイル名に使う
  operationId / repositoryId / processType
  inputs / outputs     // typed LineageEndpoint (A-1 の型。版に依存しない)
                       // endpoint 属性 (§2 の endpoint-local snapshot) は各 endpoint の中にある
  canonicalTargetSet
  chunkIndex / chunkCount   // producer レベルの fact は 0 / 1 固定 (v2.3.17 ⑤)
  occurredAt
  correlationId             // STRING | NULL (v2.3.17 ①)
  legacyV1Projection        // 上記 MAP | NULL — v1 材料化の素材 (v2.3.17 ①)
  payloadDigest        // この fact の digest。materialize 後の digest とは別
```

`snapshot` という単独フィールドは**無い**。v2.3.10〜11 はここに `snapshot` と書いていたが、
§2 (v2.1) が event-level の `snapshotAttributes` を廃止して**属性を endpoint ごとに持たせた**ので、
その名前の値はもう存在しない。`inputs` / `outputs` の各 endpoint が自分の属性を持つ。

#### `spoolRecordId` と `payloadDigest` の正規仕様 (v2.3.11)

v2.3.10 は `spoolRecordId` を「版非依存の一意 ID」としか書かなかった。ファイル名・重複判定・
materialize の基点である以上、それでは足りない。**A-1 の `LineageCanonicalHash` を再利用する** —
型付き長さ prefix、符号なし UTF-8 バイト順、`null` と空文字を区別、という既に凍結済みの規則を
別に作り直さない。

```
spoolRecordId = H("SPOOL_FACT_V1",
                  repositoryId, processType, operationId,
                  canonical(inputs), canonical(outputs), canonicalTargetSet,
                  chunkIndex, chunkCount, occurredAt)        // 64 hex

payloadDigest = H("SPOOL_PAYLOAD_V1", spoolRecordId, spoolSchemaVersion,
                  endpointRecords(inputs), endpointRecords(outputs),
                  correlationId,                     // STRING | NULL — null と空文字は別
                  legacyProjectionRecord | NULL)     // 下記 MAP。v2.3.17 ①
```

`legacyProjectionRecord` は `MAP{ processType: STRING, inputs: LIST[STRING],
outputs: LIST[STRING], snapshotAttributes: MAP{STRING→STRING}, presetEventId: STRING|NULL }`。
inputs / outputs は**宣言順・重複保持のまま** (正規化しない — 生文字列が v1 eventKey を
駆動する)。schema-1 材料化が正式に退役するまで生成し続ける (v2.3.17 ①)。

`canonical(...)` は §3 と同じ **`catalogQualifiedName` の列**であって、endpoint 属性を含まない。
したがって identity (`spoolRecordId`) だけでは「同じ endpoint 集合で属性だけ違う fact」を区別
できない。属性が変わったことを検出するのが `payloadDigest` の役目なので、そこは
**endpoint の完全な記録**を対象にする。

```
endpointRecords(list) = LIST[ record(e) を catalogQualifiedName の符号なし UTF-8 バイト順に並べたもの ]

record(e) = MAP{
  "kind"                 : STRING(e.kind().name())
  "repositoryId"         : STRING
  "catalogQualifiedName" : STRING
  "objectId"             : STRING | NULL
  "operationId"          : STRING | NULL
  "attributes"           : MAP{ TEXT → STRING、COUNT → LONG }
}
```

型 tag は A-1 で凍結済みのもの (`NULL 0x00` / `STRING 0x01` / `LONG 0x02` / `LIST 0x03` /
`MAP 0x04`) をそのまま使う。**新しい符号化は作らない。**

MAP のキー順を仕様に書く必要は無い。`LineageCanonicalHash` の MAP 符号化が**キーを符号なし
UTF-8 バイト順にソートしてから書く**ので、宣言順は結果に影響しない (v2.3.12 まで「キー順に
依存する」と書いていたのは誤り)。`objectId` / `operationId` の `NULL` は型 tag が空文字と
区別するので、identity を持たない kind の欠落値と空値は別の record になる。

| 論点 | 規則 |
|---|---|
| ID 方式 | domain-separated canonical hash (UUID ではない)。**同一 business fact の再試行は同一 ID** になり、部分失敗後の再書込みが重複を作らない |
| `occurredAt` を含める理由 | fact は業務 fact 成立時に一度だけ採番され (§3「`occurredAt` は一度だけ採番する」)、再試行はその値をそのまま運ぶ。含めないと、同じ endpoint 集合の操作を繰り返したときに別の fact が同一 ID へ潰れる |
| `occurredAt` の危険 | 再構成で `Instant.now()` を引き直すと ID が変わり、**同じ fact が二重に materialize される**。§3 の純関数契約がこれを閉じる。契約が無い状態でこの式を実装してはならない |
| 別 operation の区別 | 利用者が同じ import をやり直したなら **`operationId` が違う** ので別 fact になる (§3 の event-level `operationId` 必須契約) |
| endpoint 集合 | `canonical(...)` は §3 と同一 — `catalogQualifiedName` を符号なし UTF-8 バイト順、**重複は拒否**、null 要素は拒否 |
| target 集合 | `canonicalTargetSet` と同一 — trim、非空、重複除去、ソート |
| null / 空文字 | `LineageCanonicalHash` の型 tag が区別する。`operationId=null` と `""` は別の fact |
| `payloadDigest` の対象 | `spoolRecordId` + `spoolSchemaVersion` + **endpoint の完全記録** (属性込み) + `correlationId` (NULL 可) + `legacyProjectionRecord` (NULL 可) — v2.3.17 ①。identity に入る QN は `spoolRecordId` 経由で 1 度だけ数える |
| 同一 ID・digest 不一致 | **上書きしない**。`fact-{id}.quarantine.json` へ退避し `lineage.spool.quarantine{reason=digest_mismatch}` を上げる。同じ ID で内容が違うのは、識別子の規則が破れたか改竄であり、どちらも黙って上書きしてよい事象ではない |
| ファイル名 | `fact-{64 hex}.json` — 74 文字。hex なので path-safe で、大文字小文字を区別しない FS でも衝突しない |
| golden vector | `spoolRecordId` / `payloadDigest` / `endpointRecords` も A-1 と同じ仕組みで凍結する — `identity-golden-vectors.json` に追加し、`reference_hash.py` (仕様のみから書いた別実装) が一致することを CI で確認する |

#### materialize は「一度だけ」ではなく「収束する」 (v2.3.11)

v2.3.10 は「`spoolRecordId` → `deliveryId` の対応を記録して一度だけ materialize する」と書いた。
**クラッシュ安全ではない。** 対応記録・CouchDB event 作成・fact ACK は**別の永続先**であり、
その間のどの窓でも落ちうる:

| # | 窓 | v2.3.10 での帰結 |
|---|---|---|
| 1 | event 作成成功 → mapping 保存前にクラッシュ | 再起動後に再 materialize し、**二重 event** |
| 2 | mapping 保存 → event 作成前にクラッシュ | mapping があるので「済み」と誤判定し、**event が永久に作られない** |
| 3 | flag を読んだ後に v1→v2 が切り替わる | 同じ fact が版違いで materialize されうる |
| 4 | 2 つの scanner が同じ fact を同時処理 | 両方が作る |
| 5 | bundle 経由で同一 fact が別ノードへ複製 | 別ノードで再 materialize |
| 6 | append の timeout で成否不明 | 再試行すると重複、しないと欠落 |

必要なのは「一度だけ実行」ではなく、**何度実行しても同じ結果へ収束するプロトコル**である。

#### 撤回 7: 決定をローカル sidecar に置いていた (v2.3.12)

v2.3.11 は決定を `fact-{spoolRecordId}.decision.json` に atomic rename で書き、
「再起動時は sidecar の決定を再利用する」とした。**排他になっていない。**

- **atomic rename は「置換」であって「不在なら作成」ではない。** 2 つの scanner が
  activation を跨いで別々の `writeSchemaVersion` を読み、それぞれ v1 と v2 の決定を作り、
  同じファイルを上書きし合い、**各自が自分のメモリ上の `deliveryId` で event を作れる**。
  版が違えば `deliveryId` も違うので、CouchDB の決定的 `_id` では潰れない。
  窓 4 は「決定の後」ではなく「決定そのもの」で開いていた。
- sidecar は **node-local** である。窓 5 (bundle で別 node へ複製された同一 fact) には
  そもそも届かない。「決定的 `_id` が最終境界」は、両者が**同じ版を選んだ場合にしか**成り立たない。
- 「sidecar を失っても正しさは失われない」も支えが無かった。journal 作成が成功した後に
  sidecar と ACK を失うと、その event を**探す手段が無い** — `spoolRecordId` は event の
  payload に入っておらず、現在の flag から計算し直すと別の `deliveryId` になりうる。
  さらに「sidecar 消失時は quarantine」と書いたが、「まだ決めていない」と「決めたが消えた」を
  **見分ける規則が無い**ので、その分岐は書けない。
- torn / 切り詰め / 運用者が古い backup から戻した sidecar をどう扱うかも未定義だった。
  自前の digest を持たないので、検証しようも無い。

**決定を CouchDB に置く。** materialize は CouchDB が復旧してから始まるので、CouchDB を
使えることは前提条件として既に満たされている。ローカル sidecar は**廃止**する。

決定文書は **fact 単位の親**である (v2.3.17 ⑦)。単一の deliveryId / eventDigest では
1 fact → 複数 v2 chunk を表せないため、親は版と materialization-plan digest を凍結し、
chunk ごとの決定 / delivery record は plan から決定的に導出する。fact の ACK は
**全 chunk の永続化後**にのみ書く。v1 材料化 (chunk しない) は plan が単一 entry に退化する。

```
_id: lineage_materialization:{spoolRecordId}          // v2.3.23: 実装形に同期
  spoolRecordId
  factPayloadDigest          // 決定の根拠になった fact の payloadDigest
  materializeSchemaVersion   // ここで決めた版 (1 | 2)
  barrierGeneration          // 決めた時点の barrier generation
  allocatedEventId           // 監査 eventId を**一度だけ**採番 (v1 は fact の presetEventId が
                             //   あればそれ)。旧稿の presetV1EventId を一般化した名前 —
                             //   v2 側にも同じ「一度だけ」規則が要る (v2.3.21)
  planDigestVersion          // 2 | 3。3 = chunk-aware (v2.3.22)
  partitionVersion           // 3 のとき必須。分割アルゴリズムの同一性
  chunkLimits                // 3 のとき必須。MAP{maxEndpointsPerEvent, maxPayloadBytes}
  creationClassification     // MAP{target -> MAP{status, reason}}。OVERSIZE などの生成時終端
  planEntries                // **列**。v2: MAP{chunkIndex, deliveryId, eventDigest}、
                             //   v1: MAP{schemaVersion:1, eventId, v1EventDigest}
  materializationPlanDigest  // 上記全部を束縛する digest (V3 domain)
  createdAtMs
```

**単一の `deliveryId` / `eventDigest` は撤回した (v2.3.23)**。1 fact は K 個の journal 行に
なり得るので (chunking)、決定が固定するのは**列**である。旧稿の 2 field は chunk が 1 個の
場合だけ成立する形だった。
```

```
1. fact を読み、spoolRecordId と payloadDigest を再計算して自己照合する
     ファイル名の id と一致しない、または payloadDigest が一致しない
       → materialize しない。quarantine + lineage.spool.quarantine{reason=self_check_failed}
2. lineage_materialization:{spoolRecordId} を create-if-absent
     201 → 自分の決定が採用された
     409 → **既存文書を読み、その決定に従う** (自分の決定は捨てる)
           factPayloadDigest が自分の fact と違えば quarantine (同一 ID・別内容)
3. 決定の materializeSchemaVersion と **凍結された chunkLimits** で分割を再導出し、
   **各 plan entry i** について slice i を組み立て、その (deliveryId, creationPayloadDigest)
   が**その entry の値と一致すること**を書込み前に確認する (v2.3.23)
     entry 数の不一致・値の不一致 → **1 行も書かない**。integrity 例外 +
     lineage.materialization.divergence
4. **各 entry の** deliveryId を _id にして journal へ create-if-absent (K 行)
     409 は既存文書の creationPayloadDigest が**その entry の eventDigest と完全一致**した
     ときだけ成功扱い。不一致は integrity 例外 (§3)
     creationClassification がある場合は status + reason を同一文書に原子的に書き、
     409 収束もその一致を要求する
5. **全 entry の行を再読して digest (と v2 の allocatedEventId) を再検証**してから、
   fact の ACK を書く (write-temp → fsync → hard link → dir fsync)
6. 再起動時も 1 から実行してよい。2 の 409 が必ず同じ決定へ戻す
```

手順 1 の自己照合が、決定文書へ進む前に**壊れた fact を締め出す**。torn write は spool の
file 安全性契約 (§8: write → fsync → **hard link (create-if-absent)** → dir fsync、v2.3.17 ③) が防ぐが、運用者が戻した古い backup や
別 node から来た改竄済み bundle はそこを通らない。決定文書は一度作れば固定されるので、
**壊れた fact で決定を作らせないことが重要**である。

手順 3 の事前確認は、決定文書が固定しているのが版と `deliveryId` **だけではない**ことから来る。
`eventDigest` も固定されているので、fact → event の写像がバイナリ更新で変わった場合、
**同じ `_id` に別内容の event を書く前に**止まる。決定を作った時点と materialize する時点で
稼働バイナリが違うことは、まさにこの節が扱っている rollout の最中に起こりうる。比較先は
決定文書の値であって、その場で計算し直した値ではない — 計算し直した値どうしを比べても、
両方が新しい写像なら一致してしまう。

これで 6 つの窓はすべて収束する:

| 窓 | なぜ閉じるか |
|---|---|
| 1 | 再起動後、決定文書から同じ `deliveryId` を得て create-if-absent。409 + digest 一致で成功扱い |
| 2 | 決定文書は「決めた」だけで「作った」とは主張しない。3 を再実行する |
| 3 | 版は決定文書に固定済み。flag が動いても materialize の版は変わらない |
| 4 | **決定の create-if-absent が排他**。並行 scanner は必ず同じ版・同じ `deliveryId` を使う |
| 5 | 決定文書は CouchDB にあり **node-local ではない**。別 node の scanner も同じ決定を読む |
| 6 | 再試行して 409 + digest 一致に落ちるのが正しい挙動 |

排他が **CouchDB の 1 回の create-if-absent** に集約されているので、torn write も部分書込みも
存在しない — 文書は作られたか作られていないかのどちらかである。sidecar の検証規則
(digest・切り詰め・古い backup) が要らなくなったのは、そもそも検証すべきローカル状態を
持たなくなったからである。

**決定文書は削除しない。** 数百バイトであり、消すと、生き残った fact のコピー
(別 node の volume、古い bundle、運用者が戻した backup) が再走査されたときに
**別の版で決め直されて別の event になる**。retention の対象は fact と ACK だけとする。

**`spoolRecordId` を event の payload にも持たせる** (§3 の表のとおり `creationPayloadDigest`
からは除外する)。決定文書がある限り逆引きはできるが、監査で「この event はどの spool fact から
来たか」を答えるのに毎回 `deliveryId` から決定文書を逆引きするのは実用的でない。

**重複防止は 2 段で成り立つ。** どちらか一方では足りない。

| 段 | 何を防ぐか |
|---|---|
| 決定文書の create-if-absent | **版の分岐**。同じ fact に対して誰もが同じ `deliveryId` を計算する |
| journal の決定的 `_id` + digest 一致 | **同じ `deliveryId` の二重作成**。作成の競合と再試行を吸収する |

v2.3.11 が「決定的 `_id` が最終境界」とだけ書けたのは、**全員が同じ版を選ぶ**ことを暗黙に
仮定していたからである。その仮定を成り立たせるのが 1 段目で、そこがローカル file では
成り立たなかった。

**§8-d の「spool file 名は常に `deliveryId`」という契約を訂正する。** spool には 2 系統ある:

| 種別 | ファイル名 | いつ |
|---|---|---|
| materialized event | `{deliveryId}.json` / `{deliveryId}.ack` | 版が確定している通常経路 |
| unmaterialized fact | `fact-{spoolRecordId}.json` | flag 読取不能時。scanner が後で materialize |
| fact の ACK | `fact-{spoolRecordId}.ack` | その fact の event が durable になったことの印 |
| quarantine | `fact-{spoolRecordId}.quarantine.json` | 同一 ID で digest が違う fact。**上書きしない** |

`.decision.json` は撤回 7 で**廃止**した (決定は CouchDB の
`lineage_materialization:{spoolRecordId}`)。

**fact は `{deliveryId}.json` に改名しない。** 改名すると、fact と materialized event という
別種の記録が同じ名前空間に入り、`payloadDigest` (fact) と `creationPayloadDigest` (event) の
どちらで冪等判定するのかが名前から決まらなくなる。fact は fact のまま置き、ACK で終わらせる。

##### scanner の走査規則と retention (v2.3.12)

| 観測される状態 | scanner の動作 |
|---|---|
| `fact-{id}.json` あり / `.ack` あり | **skip**。既に durable |
| `fact-{id}.json` あり / `.ack` なし | プロトコルを 1 から実行する。決定文書があれば 409 でそこへ収束する |
| `fact-{id}.json` なし / `.ack` あり | 完了済みの残骸。retention で ACK を消す |
| `fact-{id}.quarantine.json` あり | 触らない。運用者の判断を待つ |

| 項目 | 規則 |
|---|---|
| 削除順序 | **event が durable になった後**に ACK を書き、retention 経過後に fact → ACK の順で消す。ACK の前に fact を消してはならない — 決定文書は payload を持たないので、fact を失うと event を作り直せない |
| retention | `lineage.spool.retention.days` (materialized event の spool と同じ設定) |
| 決定文書 | **削除しない** (撤回 7)。retention の対象外 |

上の表は spool を走査して分かることだけを扱う。**「決定文書はあるが event が無い」は
spool 側からは見えない** — fact ごと失われていれば、走査する対象そのものが無いからである。
これは決定文書の側から照合する:

**この節は将来仕様である (v2.3.23)。** 下の endpoint も `lineage.materialization.orphaned` も
実装が無く、決定的テスト #13b は 4a の完了条件から外してある。**規範として読まないこと** —
検出手段を持たない規則を「唯一の経路」と書いたままにすると、無い機能を根拠に活性化を
承認しかねない。実装は増分 E の運用面と合わせて設計する。

```
(将来) GET /api/v1/admin/lineage-journal/materializations/{repositoryId}?unresolved=true
  → 決定文書のうち plan entry の event が journal に存在しないものを列挙する
```

| 見つかったもの | 意味 | 対応 |
|---|---|---|
| 対応する fact がまだ spool にある | materialize 未完。scanner が次周で終わらせる | 放置してよい |
| fact がどこにも無い | **payload が失われている**。event は作れない | `lineage.materialization.orphaned` を上げ alert。他 node の volume / bundle から fact を回収する |

これが実装されれば「fact を ACK 前に消してはならない」という順序規則が**検出可能**になる。
**現時点では検出手段が無い** (v2.3.23) ので、順序規則は materializer の実装 (全 entry 再読
検証の後にのみ ACK) が守っており、破れた場合の事後検出は将来仕事である。

**残余**: fact の spool 書込みにも失敗した場合、その lineage は失われる。§9 の repair は
「journal か spool に記録がある」ことを前提とするので、**repair で回収できるとは言えない**。
`lineage.emit.dropped{reason=flag_unreadable_and_spool_failed}` を出し alert 対象とする。

**残余 2 (v2.3.13)**: spool より**前**にも窓がある。業務 commit が成功した直後・emitter が
fact を作る前に AP が落ちると、**業務は成功したのに lineage が最初から存在しない**。spool は
「作った記録を失わない」仕組みであって、「作られなかった記録」は検出できない。emitter の
fail-open 契約 (業務を止めない) を保つ限り、この窓は emitter 側では閉じない。

恒久対策は操作単位の durable operation record — inbound は作成された Content に
`operationId` + provenance を**同一 commit** で保存し、export は streaming 開始前に record を
永続化し、scanner が「`CONTENT_COMMITTED` だが lineage event が無い」ものを検出・再構築する。
これは業務 commit と結合する新しい仕組みなので **増分 F として本増分の範囲外**に切り出す
(v2.2 の「outbox は範囲外」判断と同じ線)。それまでは、この窓の存在自体を仕様として明記する。

### 撤回 4: D-rest を v2 有効化の後に置いていた (v2.3.11)

v2.3.10 は「D-rest は `deliveryId` を使うので A-2 Slice 4 の後」と書いた。**順序が逆である。**
D-rest が直すのは、設計上すでに確認済みの**採番欠落・二重 publish・cursor の先行・危険な replay**
であり、それを 4b の後に置くと、**v2 event が既知の壊れた journal / projector 経路へ流れる期間**が
生まれる。新しい書込み形式を、直すと決まっている経路へ先に流す理由は無い。

`deliveryId` の計算は Slice 1〜3 が additive に確定させる。したがって D-rest は
**v1/v2 dual として非活性のまま先行配布できる** — writer はまだ v1 なので、v2 経路は
コードとして存在するだけで通らない。

| | 内容 | 依存 |
|---|---|---|
| **D-spool** | 版非依存 fact spool + scanner + fsync 検証。`deliveryId` を要さない。**実装済み (v2.3.17・非活性)** | A-1 + P-1 (`LineageFact` が変換入力) |
| **D-rest** | event-first sequencer / cursor CAS / replay generation CAS。v1/v2 dual、非活性で配布 | A-2 Slice 1〜3 (型と `deliveryId` 計算)。**slice 分割と実装状態は下表 (v2.3.18 ⑧)** |

| D-rest slice | 内容 | 状態 |
|---|---|---|
| **D-rest-1** | 型付き可変 envelope (`LineageJournalRowV2` + strict codec)・`sequencerLeaseToken` 付き lease (acquire/renew/release CAS・一方向 latch)・**bootstrap patch (`Patch_LineageSequencerBootstrap`: lease 生成 + 履歴なし repo の counter=0 seed、既存は検証のみ・上書き禁止・破損は throw 再実行)**・fenced allocator (auto-seed 禁止・**v1+v2+cursor 横断の watermark 検査**)・fenced sequencer (claim/reclaim/finalize + pre-write 再確認・**infra 失敗は latch**)・sequencer view 3 種・実 CouchDB IT + **skip 不能な専用 CI job** (-Dlineage.it.required)。diff レビュー対応: appendV2 は seq≠0 拒否・409 収束は占有行を decode して再計算比較 (保存 digest 文字列を信用しない)・strict IO (404/409/障害の三分類)・整数は exact 変換・破損行は scan の barrier (追い越し禁止) | **完了 (非活性 — driver/scheduler なし)** |
| **D-rest-2** | 8-b の v2 遷移 CAS・8-c 単調 cursor・schema 別 projector routing。その後に無効化済み admin sequencer 入口 | **完了 (非活性)** — v2.3.19。readiness gate 既定 false、v2 branch/reaper/purge-v2/admin POST 全て gate 背後 |
| **D-rest-3** | 8-d replay request CAS 機械 + crash 回収 | **完了 (非活性)** — v2.3.20。readiness gate 背後、期待集合 {不在, ACKED} 凍結どおり |
| **D-rest-4** | 収束 materializer (親決定 + plan entry + 検証付き ACK)・aggregate capability provider・scanner 入口 | **完了 (非活性)** — v2.3.21。resolver 既定 unavailable = 決定ゼロ、scanner は readiness gate + spool 条項の背後 |

順序: **A-2 Slice 1〜3 → D-spool → D-rest (dual・writer は v1) → Slice 4a → 4b → E**。

4b の ACTIVE CAS は、**D-rest が配布済みであること**も前提条件に含める。それは本文の約束では
足りないので、barrier の `requiredCapabilities` に `sequencer:event-first` / `cursor:cas` /
`replay:generation-cas` を置き、CAS 条件 8・9 が検査する (「capability は『読めるか』だけでなく
『直っているか』を表す」)。

### 決定的テスト (Slice 4a の完了条件)

| # | 系列 | 期待 |
|---|---|---|
| 1 | capability を一切登録しない legacy node が `expectedNodes` に居る | `ACTIVE` CAS が失敗し、ACK 欠落の nodeId を返す |
| 2 | ACK 収集後・`ACTIVE` CAS 前に membership が変わる | 再提示された `expectedMembershipRevision` が不一致で CAS 失敗。**v3.3 の完了条件から除外** (v2.3.23) — 規範形は単一 AP で membership 変更が存在しない。将来の multi-node protocol の条件として保持する |
| 3 | 古い `generation` の ACK が残っている | 新 generation では無効 |
| 3b | 同じ `nodeId` が再起動し `bootId` が変わった | 旧 `bootId` の ACK は無効 |
| 3c | ACK が `expiresAt` を過ぎている | 無効 |
| 4 | v1 専用 view が v2 行を露出しない | **v2.3.23 で書き換え**: 旧文「stale な旧 leader 相当が v2 view を読んでも claim も cursor 前進も起きない」は実装に矛盾する — 活性化した新 reader は v2 を**意図して** claim し cursor を進める。正しい不変条件は「v1 機構が読む 6 view が v2 行を 1 件も emit しない」で、`LineageJournalViewCoverageTest` が既に固定している (旧バイナリはその view 名しか照会しないので、これが旧バイナリに対する唯一の実効的保証でもある) |
| 5 | `writeSchemaVersion` を 1 へ戻す | `minReaderSchemaVersion` は 2 のまま。**rollback が変えるのは「新しい ORIGINAL fact をどの版で材料化するか」だけ** (v2.3.23) — 既に凍結された V3 決定はその版で材料化を完了し、v2 行への replay も動き続ける。「以後 v2 行は 1 件も作られない」という保証ではない |
| 5c | rollback 後にもう一度 v2 へ上げる | **`PREPARING` からやり直す** (下記の状態機械)。`ACTIVE` から直接 `writeSchemaVersion` を 2 へは戻せない |
| 5b | `ACTIVE` CAS 後の任意の瞬間 | `writeSchemaVersion=2 && minReaderSchemaVersion=1` が観測されない |
| 6 | `minReaderSchemaVersion == 2` の状態で新バイナリを `readSchemaVersions=[1]` で起動する | lineage subsystem が起動時に fail-closed。**spool の走査結果には依存しない** |
| 6b | `minReaderSchemaVersion` を 2 → 1 へ落とす要求 | CAS が拒否する (単調増加) |
| 7 | flag 読取不能 | event ではなく **fact** を spool。scanner が復旧後に materialize し、そこで `deliveryId` を計算 |
| 7b | fact spool 書込みも失敗 | `lineage.emit.dropped{reason=flag_unreadable_and_spool_failed}` が上がる。repair 可能とは主張しない |
| 8 | 同一 fact を 2 つの scanner が並行処理 | 決定文書が **1 つだけ**作られ、journal 行は**その決定の plan entry と同数 (K 件) ちょうど**。敗者は勝者の決定 (版・割当・plan) を採用する (v2.3.23: 「1 件だけ」は chunk が 1 個の場合の特殊形だった) |
| 8b | 2 つの scanner が **別々の `writeSchemaVersion`** を読んだ状態で並行処理 | 決定文書の create-if-absent が排他になり、**両者が同じ版・同じ plan (同じ deliveryId 列)** を使う |
| 9 | journal 成功後・ACK 前にクラッシュ | 再起動後、決定文書 → 409 + digest 一致で**全 entry が**収束。重複しない |
| 9b | K 行のうち K-1 行だけ書けた状態でクラッシュ (v2.3.23) | ACK は書かれておらず、次の pass が残り 1 行を作って**全 entry 再読検証の後**に ACK する |
| 10 | 決定文書の作成後・journal 作成前にクラッシュ | 再起動後に**全 entry の** event が作られる (決定文書は「作った」と主張しない) |
| 11 | materialize 中に write-version が切り替わる | 決定文書に固定した schema version・**chunkLimits**・plan (deliveryId 列) が**変わらない** (v2.3.23: 限界値も凍結対象。live config を読むと再分割が起きて fact が詰まる) |
| 12 | 同一 `spoolRecordId` で fact の内容が違う | 上書きせず quarantine。metric が上がる |
| 12b | 決定文書の `factPayloadDigest` と手元の fact が不一致 | materialize せず quarantine |
| 13 | bundle 経由で別 node へ複製された fact | 同一の決定文書を読み、同一 delivery へ収束する |
| 13b | ACK 前に fact を消す | ~~`materializations?unresolved=true` が拾い、`lineage.materialization.orphaned` が上がる~~ → **v3.3 の完了条件から除外 (v2.3.23)**: この admin endpoint も metric も実装が無く、fact が消えた時点で決定文書だけが残る (journal 行は既に durable で、ACK は fact に紐づくので書けない)。孤児決定の列挙は増分 E の運用面と合わせて設計する |
| 13c | 壊れた / 改竄された fact を投入する | 手順 1 の自己照合で弾かれ、**決定文書が作られない** |
| 13d | 決定の作成後、fact → event の写像が変わったバイナリで materialize する | 手順 3 で `eventDigest` 不一致を検出し、**journal へ書かない** |
| 14 | `requiredCapabilities` を満たさない ACK (D-rest 未配布) で 4b を要求 | `ACTIVE` CAS が拒否し、不足 capability を返す |
| 14b | `approvedBinaryDigests` に無い `binaryDigest` の ACK | 同上 |
| 15 | scale-to-one 切替 | 旧 AP が 1 台も存在しないことを**運用受入条件**として確認する (アプリでは検証できない) |
| 16 | 同一入力から `build()` を 2 回呼ぶ | `eventId` 以外が完全に一致し、`creationPayloadDigest` と `spoolRecordId` が同値 (§3 の `occurredAt` 純関数契約) |
| 18 | `drestReady == false` の ACK で `ACTIVE` を要求する (v2.3.23) | 各 readiness 違反 (switch off / config 域外 / view 署名 drift / 検証不能 sink / spool probe 失敗) ごとに CAS が失敗し、違反を返す |
| 19 | ACK 記録時は green、`ACTIVE` CAS 直前に gate が赤化 (v2.3.23) | CAS 時の**再評価**で失敗する — ACK の記録値だけを信じない |
| 17 | `spoolRecordId` / `payloadDigest` の golden vector | Java と `reference_hash.py` が一致する (A-1 と同じ CI ジョブ)。**達成 (v2.3.17)** — fixture に 4 vector を凍結、双方 25 vector 一致 |

### §6-a 再 sign-off: **承認済み (2026-08-03, v2.3.23)**

Codex による GO/NO-GO レビュー 4 巡 (NO-GO×3 → **sign-off**) を経て承認。指摘は全て
「§6-a が D-rest / chunking の着地に追い越されていた」箇所で、v2.3.23 で解消済み:
capability と readiness の二重要求 (+ `read:v2`)・充足不能だった `spoolSchemaVersions` の
分離・単一 AP 状態機械と永続化契約の凍結・決定 schema と 4a テスト表の chunking 同期・
rollback / 再活性化の完全な状態機械 (ACK の generation と `acks` の消去)。

**したがって Slice 4a に着手してよい。** Slice 4b (activation) は引き続き E-19/E-20
(実測済み・PASS) に加えて運用受入条件 (scale-to-one の確認・spool の at-rest 暗号化・
cursor 残渣・`approvedBinaryDigests` の決定) を要する。

### この節が閉じるまで Slice 4 は着手しない (履歴 — 上記で閉じた)

Slice 1〜3 (additive、production writer は v1 のまま) は本節と独立に進められる。

**Slice 4b (activation) の追加 gate — 秘密面 (v2.3.14)。** 並行レビュー (2026-08-01) の指摘を
受け、次を 4b の前提に含める:

| gate | 内容 | 実測 (2026-08-03) |
|---|---|---|
| E-19 | live Atlas で、token 付き URL を持つ document を sync し、**backend が保存した実体**に token が無いことを確認する (§10 の表の定義どおり) | **PASS** — Apache Atlas 2.3.0 (OSS, ローカル docker)。実 `PurviewEntityPayloadFactory` が組んだ entity を publish し、`entity/uniqueAttribute` で読み戻した**保存実体の全文**に `?authkey=` 型・`/:x:/g/…` 型のどちらの token も無い |
| E-20 | A-1g 以前の raw URL 入り entity が republish で消えるかを実測する。**消えなければ purge / entity 再作成の runbook を release 手順に追加してから** activate する | **PASS (消える)** — 同一 qualifiedName に raw URL 入り entity を作成 → 今日の factory (`cloudFileUrl: null`) で republish → **同一 guid のまま `cloudFileUrl` が null になり token は実体のどこにも残らない**。したがって Atlas OSS では purge / 再作成 runbook は不要。**Purview は別 backend であり、Purview へ activate する場合は再実測が必要** (設計が「Atlas OSS と Purview で異なる」と述べているとおり) |

再実行可能な gate として `PurviewLiveAtlasSecretsIT` に実装済み (env-gated:
`NEMAKI_ATLAS_IT_URL` / `NEMAKI_ATLAS_IT_USER` / `NEMAKI_ATLAS_IT_PASSWORD`、CI では
`-Datlas.it.required=true` で skip 不能)。E-19 は「期待した属性が null か」ではなく
**保存実体の全文に token が現れないこと**を検査する (思いつかなかった属性こそ gate の目的)。
| cursor 残渣 | `cloud-metadata-snapshot` cursor が URL-free 形式であること (旧形式は成功 sync 1 周期で scrub される。sync が回らない repository は admin API 側で sanitize 済みだが、保存値の確認を運用受入に含める) |

## 7. cross-repository 方針

**cross-repository lineage は認めない。**

- `LineageEvent.repositoryId` と全 endpoint の `repositoryId` が一致することを
  `LineageEventBuilder.build()` で検証し、違反は `IllegalArgumentException`。
- **外部 endpoint も対象**。v2.2 の「repositoryId を持たないので対象外」は §2 (v2.3) で撤回した。
  `endpoint.repositoryId == event.repositoryId` と、canonical QN 内の `{repositoryId}` の
  両方を検査する。
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

### mode 別の保証境界 (v2.3)

[DirectLineageEmitter](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/DirectLineageEmitter.java)
は sink へ直接非同期 dispatch するだけで、journal / cursor / `VERIFYING` / catalog obligation の
**いずれも通らない**。v2.2 は `direct` にも spool 要件を書いていたが、整合しない。

**`JOURNALED` を唯一の reliable / supported mode とする。**

| mode | 保証 |
|---|---|
| `JOURNALED` | 本文書の全保証が適用される。**Atlas-enabled release gate はこの mode のみを対象とする** |
| `DIRECT` | **legacy best-effort**。at-most-once、順序保証なし、verify なし、catalog obligation なし、spool なし。障害時は失われる。新規導入では選ばない |
| `DISABLED` | event を作らない |

**共通と限定の線引き (v2.3.1)。** producer と builder を typed endpoint に全面変更する以上、
`DIRECT` が同じ event schema と payload factory を通らなければコンパイルも publish も成立しない。
v2.3 の「新機能を `DIRECT` に実装しない」は不正確だった。

| 全 mode 共通 | `JOURNALED` のみ |
|---|---|
| typed `LineageEndpoint`、canonical QN、artifact 表現、endpoint-local snapshot、cross-repo 検証、`processKey` | journal、spool、ordering (sequence/cursor)、`VERIFYING`、catalog obligation、replay、repair |

- **spool の設定検査は `JOURNALED` のみ**で行う。§8 の「`journaled` / `direct` かつ spool 未設定」は
  `journaled` のみに訂正する (下表)。
- `DIRECT` で起動したときは起動ログと health に
  `lineage.mode=DIRECT (best-effort, unsupported for governance)` を明示する。
- §10 の E-1〜E-17 は全て `JOURNALED` 前提。`DIRECT` は E-18 (下記) のみ。

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
1. **`deliveryId` 由来**の決定的 _id で、完全な payload を state=UNSEQUENCED として1書込みで作成
   409 は既存文書を読み直し、**creationPayloadDigest が完全一致したときだけ**成功扱い (§3)。
   不一致は ID 衝突であり、経路別に integrity 例外 → spool + metric / 500 (§3 の表)
2. per-repository の fenced sequencer が UNSEQUENCED を occurredAt 順に1件ずつ claim
   (claim token = fencing token。ACL-epoch の lease/fencing と同じ形)
3. counter を CAS で払い出す
4. claim token が一致するときだけ event に sequence を確定 (CAS)
5. 確定後に次の event へ進む
```

v2.3.11 まで 1 に「409 = 既存 → 冪等に成功」と書いていたのは **§3 と矛盾していた**。
digest を見ずに 409 を成功にすると、ID 衝突がそのまま黙って受理される — §3 が閉じた穴を
この疑似コードだけが開けたままにしていた。

### sequencer lease / fencing 状態表 (v2.1)

```
_id                 : lineage_sequencer_lease:{repositoryId}
generation          : long  — 単調増加。取得のたびに +1
sequencerLeaseToken : string — acquire ごとの暗号学的乱数 (v2.3.18 ①)。event にも stamp し、
                      generation が万一再利用されても old leader の書込みを CAS 不一致にする
owner               : nodeId
expiresAt           : ISO8601
```

| 操作 | 条件 | 効果 |
|---|---|---|
| acquire | **lease が存在し**、かつ `owner=null` または `expiresAt < now` | `generation+1` で CAS 取得。失敗は他ノードに譲る。**lease 不在では絶対に取得しない (作らない)** |
| renew | `owner` 一致 かつ `generation` 一致 | `expiresAt` 延長を CAS。**失敗したら fence latch を落とし、以後この世代では一切書かない** |
| release | `owner` 一致 かつ `generation` 一致 かつ `_rev` 一致 | `owner=null` / `expiresAt=過去` に CAS。**`generation` は維持し、document は削除しない** |

**lease document を削除してはならない。** 削除すると generation の high-watermark も消え、
次の acquire が古い generation を再利用できてしまう。そうなると old leader の
`sequencerGeneration` と一致してしまい、fencing が成立しない。
削除操作は API・管理経路のいずれからも**拒否**する (存在チェックを伴う CAS のみ許す)。

**lease は bootstrap patch でのみ新規作成する (v2.3)。**

| 状況 | 挙動 |
|---|---|
| 初回導入 | bootstrap patch が `generation=0` で作成する |
| 運用中に document が消えた | **自動再作成しない。fail-closed** で sequencer を停止し、health を `LEASE_MISSING` にする |
| 復旧 | 下記の手順で管理復旧する。**event 中の最大 generation だけを見るのは不十分** |

**lease 復旧手順 (v2.3.1)。** event を claim する前に停止した old leader は、その generation を
どの event にも書いていない。`max(event の sequencerGeneration) + 1` で復旧すると、
**その old leader の generation を再利用してしまう**。

1. **全 AP で sequencer acquire を禁止する durable な管理 flag** を立てる
   (CouchDB の管理 document。起動時と acquire 前に必ず読む)
2. 全 old sequencer worker の停止を確認する (health / metric で無音を確認)
3. その状態でのみ `max(event の sequencerGeneration) + 1` で lease を復旧する
4. flag を解除する

**あるいは** — generation とは別に**ランダムな `leaseToken`** を lease 取得のたびに発行し、
event にも stamp する。generation が再利用されても `leaseToken` が一致しないため、
old leader は書けない。実装時にどちらを採るか決める (後者の方が手順への依存が少ない)。

**書込み直前の再確認 (v2.3)。** lease の期限切れは時間経過で起きるため、acquire 時の確認だけでは
「期限切れ後・new leader の reclaim 前」に old leader が復帰して finalize できてしまう。
`claim` / `reclaim` / counter allocate / `finalize` の**直前ごとに**、
`owner` 一致・`generation` 一致・**未期限切れ**を再確認する。

**一方向 latch (v2.3)。** renew 失敗、または再確認で期限切れを検出した worker は
その場で latch を落とし、**以後この世代では一切書かない**。latch は解除できない
(新しい世代を acquire した別インスタンスとして再開する)。

event の claim / 確定は必ず `(generation, owner)` を伴う CAS:

```
claim   : expected state=UNSEQUENCED, _rev 一致
          → state=SEQUENCING, sequencerGeneration=G, sequencerLeaseToken=T

reclaim : expected state=SEQUENCING, sequencerGeneration=G_old, _rev 一致
          かつ G_old < 現 lease の generation (= old lease は期限切れ)
          → state=SEQUENCING, sequencerGeneration=G_new, sequencerLeaseToken=T_new
          ※ old leader が停止した event はこの遷移でしか回収できない。
            claim (UNSEQUENCED からのみ) では拾えない

finalize: expected state=SEQUENCING, sequencerGeneration=G, sequencerLeaseToken=T, _rev 一致
          → state=SEQUENCED, sequence=N (token は監査用に残す)
```

いずれの書込みも直前の pre-write 再確認 (owner・generation・token 一致 + 未期限切れ) を
伴い、appendV2 が書く初期行は `sequenceNumber == 0` である (finalize が sequence と
state を単一 CAS で設定する — v2.3.18 ②)。

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
   (`FENCED_OK` / `COUNTER_MISSING` / `COUNTER_REWOUND` / `STOPPED` / `LEASE_MISSING`)

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
{lineage.spool.dir}/{repoSegment}/{yyyyMMdd}/{deliveryId}.json    ← payload (完全)
{lineage.spool.dir}/{repoSegment}/{yyyyMMdd}/{deliveryId}.ack    ← ACK marker
{lineage.spool.dir}/{repoSegment}/{yyyyMMdd}/fact-{spoolRecordId}.json  ← 版非依存 fact
{lineage.spool.dir}/{repoSegment}/{yyyyMMdd}/fact-{spoolRecordId}.ack   ← fact の ACK
  (repoSegment = §6-a の凍結済み safe encoding — 生 repositoryId は path に使わない)
```

**`deliveryId` を名前に使えるのは版が確定している場合だけ**である。write-version flag が
読めないときは版を決められないので、§6-a の**版非依存 fact** として `fact-{spoolRecordId}` に
書き、scanner が flag 復旧後に event へ materialize して `deliveryId` を計算する。

materialize の**決定はローカル file に置かない** — CouchDB の
`lineage_materialization:{spoolRecordId}` である (§6-a 撤回 7)。走査規則・削除順序・retention も
そちらが正典。ここは spool の file 名だけを定める。

#### 起動時の扱い (v2.2) — Core 全体は落とさない

「volume 未設定なら起動時 fail-closed」を Core 全体の起動失敗と読むと、
`LineageEmitter` の fail-open 契約と矛盾する。範囲を限定する。

| 構成 | 挙動 |
|---|---|
| `lineage.mode=disabled` / `direct` | spool 不要。何も検査しない (`direct` は spool を使わない) |
| **`journaled`** かつ spool 未設定・書込み不能 | **lineage subsystem を `NOT_READY` にして無効化**。Core / CMIS 本体は起動を継続する。health と metric に出す |
| `lineage.spool.strict=true` (明示設定時のみ) | Core の起動を拒否する。strict 運用を望む環境向け |

#### file 安全性契約 (v2.2)

| 項目 | 設計 |
|---|---|
| path | `repositoryId` を**そのまま path に使わない**。safe encode (URL-safe base64) + hash を使う |
| 権限 | directory `0700` / file `0600` |
| symlink | **追わない** (`NOFOLLOW`)。spool 配下に symlink があれば異常として拒否 |
| temp file | **同一 directory 内**に作る (同一 FS を保証するため) |
| 書込み (fact spool) | file を `fsync` → **hard link (原子的 create-if-absent)** → **parent directory も `fsync`** (v2.3.17 ③ — rename は「置換」であり並行 writer の last-wins を許すため fact spool では用いない)。dir fsync の失敗は**握り潰さず伝播**し、readiness probe が非対応 FS を fail-closed にする |
| 書込み (決定文書 ACK 等の置換系) | file を `fsync` → atomic rename → **parent directory も `fsync`** |
| 冪等 | 既存 spool file があるときは、payload digest が一致する場合のみ成功扱い。不一致は異常として別名で退避 |
| サイズ上限 | `lineage.spool.max-file-bytes` / `lineage.spool.max-bundle-bytes` |
| ACK | `.ack` も `fsync`、または `pending` → `acked` の atomic rename |

| 項目 | 設計 |
|---|---|
| 内容 | repair 可能な**完全 payload**。`externalStableKey` を含む |
| 権限 | spool ディレクトリは運用者のみ読める権限に置く。通常ログには**出さない** |
| 通常ログ | `deliveryId` と endpoint hash (SHA-256 先頭12桁) と reason のみ。raw URI・パスは出さない |
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

実装 (D-rest-2, v2.3.19): `LineageV2TransitionStore` — `claimForProjection` (token+lease 発行、
SEQUENCED 行のみ) / `transitionV2(recordId, target, expected, next, claimToken, reason)` /
`transitionV2Unclaimed` / `renewClaim` / `reapExpiredClaims`。保存形は
`v2ClaimByTarget[target]` (token / claimedAtMs / leaseExpiresAtMs / verifyingSinceMs /
retryCount — epoch millis) と `v2TerminalReasonByTarget[target]`。v1 の 3 引数
`updatePublishStatus` は v2 文書を拒否し、v1 行に対して byte-identical。許可する遷移だけを持つ:

```
PENDING     → WAITING_FOR_CATALOG (§2 obligation あり。retry 非消費)
WAITING_FOR_CATALOG → PENDING     (obligation RESOLVED)
WAITING_FOR_CATALOG → UNRESOLVED  (obligation SNAPSHOT_INCOMPLETE / 待機上限超過)
PENDING     → PROJECTING  (claim token 発行 + lease 期限)
PROJECTING  → VERIFYING   (Atlas POST 成功。同一 claim token)
VERIFYING   → PUBLISHED   (verify 成功。同一 claim token)
VERIFYING   → VERIFYING   (retryable read lag。同一 claim token + lease renew)
VERIFYING   → FAILED      (`lineage.verify.max-age-minutes` 超過。同一 claim token) ※ semantic mismatch は下の UNPROJECTABLE
PROJECTING  → FAILED      (POST 自体の失敗。同一 claim token)
FAILED      → PROJECTING  (claim token 再発行)
PENDING     → DISCARDED   (管理操作のみ)
FAILED      → DISCARDED   (管理操作のみ)
PROJECTING  → FAILED      (reaper: lease 期限切れ かつ 同一 claim token のみ)
VERIFYING   → FAILED      (reaper: lease 期限切れ かつ 同一 claim token のみ)
VERIFYING   → UNPROJECTABLE (semantic mismatch。**再試行しない**。下記)
(v1 legacy)  → UNRESOLVED  (reason=LEGACY_ENDPOINT。§6)
(oversize)   → UNRESOLVED  (reason=OVERSIZE。§2)
(cross-repo) → REJECTED    (§7)
PUBLISHED / DISCARDED / UNRESOLVED / REJECTED / UNPROJECTABLE = terminal。
**いかなる遷移も出ない**
```

**`UNRESOLVED` は reason 付きの単一状態に統一する (v2.3)。** v2.2 は `UNRESOLVED_OVERSIZE` を
本文で使いながら terminal 一覧に入れていなかった。独立状態は作らず
`UNRESOLVED(reason = LEGACY_ENDPOINT | OVERSIZE | SNAPSHOT_INCOMPLETE | CATALOG_WAIT_EXPIRED)` とする。

**決定的な semantic mismatch は再試行しない (v2.3)。** verify で「型が違う」「repositoryId が違う」
「shell である」を検出した場合、同じ payload を何度送っても結果は変わらない。
通常の `FAILED` (再試行対象) ではなく **`UNPROJECTABLE` (terminal)** にし、
reason と検出内容を durable に残す。read lag による不一致だけが `VERIFYING` 内で再試行される。

### `VERIFYING` の滞留防止 (v2.2)

`VERIFYING` は publish retry 回数を消費しない。だからこそ**永遠に残らない別の上限**が要る。

| 設定 | 既定 | 意味 |
|---|---|---|
| `lineage.verify.timeout-seconds` | 30 | 1 回の verify poll の総 deadline |
| `lineage.verify.interval-seconds` | 2 | poll 間隔 |
| `lineage.verify.max-age-minutes` | 10 | **`VERIFYING` に入ってからの絶対上限**。超えたら `FAILED` |

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
  _id       = REPLAY deliveryId = H("REPLAY", originalDeliveryId, target, replayGeneration)
  replayOf  = originalDeliveryId
  publishStatusByTarget = { 要求された target のみ PENDING }
  sequence  = 新規に払い出す (8-a と同じ経路)
```

**式は §3 の tagged union が正典である。** v2.3.11 まで、ここには
`hash(originalEventId, target, replayGeneration)` という **domain tag の無い、しかも
`originalEventId` (UUID) を基点にした**別の式が書いてあった。§3 は `originalDeliveryId` を
基点にした `H("REPLAY", ...)` を規定しており、A-1 の
[`LineageIdentity`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageIdentity.java)
は既にそちらで実装済みである。二つの式が並んでいると、実装者はどちらを読んでもよいことになる。
**この節に identity の式を再掲しない** — 参照だけを置く。

### replay generation の CAS 状態機械 (v2.1)

2 AP が同時 replay したときの generation 払い出しを固定する。

```
1. original event に target 単位の REQUESTED record を CAS 作成
   expected: replayRequestsByTarget[target] が不在 または 直前が ACKED
   効果    : { generation = 直前+1, requestId = UUID, state = REQUESTED }
   CAS 失敗: 409。既に進行中の request がある
2. record が generation と requestId を所有する (以後この 2 つは不変)
3. 補償 event を決定的 ID で create-if-absent
   _id = REPLAY deliveryId (§3)
4. 409 は既存文書を reread し、**creationPayloadDigest が完全一致したときだけ**成功扱い
   不一致 = 別 request が同じ ID を使った異常 → REQUESTED を FAILED にして 500
   (管理経路なので §3 の表どおり 500。通常 emit とは扱いが違う)
5. original の record を REQUESTED → CREATED → ACKED へ CAS
6. crash scanner が未 ACK の request を回収し、3 から再開する
```

- **同時要求は片方を 409 にする。** 同一 generation を複数要求が共有しない。
  進行中 request がある間は新規 replay を受け付けない。
- 直前が `ACKED` なら次の generation で再 replay できる。
- 決定的 ID により、**作成後 ACK 前に crash しても同じ ID を再利用して二重生成しない**。
- `PROJECTING` / `VERIFYING` の replay は**拒否** (409)。token-fenced claim を横取りする
  ため (v2.3.20 で VERIFYING を明記)。stale claim は reaper が `FAILED` に落とし、§8-b 機械が
  自動再 claim する — **replay 適格にはならない** (replay は terminal な配達のみ。v2.3.20 で
  訂正: 旧文「FAILED に落としてから replay 可能になる」は §8-b の自動再試行と矛盾していた)。

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
- **冪等 repair ID**: 補償 event の `_id` は §3 の REPAIR deliveryId
  = `H("REPAIR", deadLetterId, repairGeneration)`。v2.3.11 まで
  `hash(deadLetterId, "repair", repairGeneration)` と書いていたが、domain tag は hash の
  **第 1 引数**であって位置引数ではない。式の正典は §3 とし、ここでは参照だけを置く。
- **「二度 repair しても増えない」の正確な意味 (v2.3.12)**: 増えないのは
  **同一 `repairGeneration` での再実行**である。`repairGeneration` が上がれば別の `deliveryId` に
  なるので、event は**増える** — それは意図した新しい補償である。両者を混同しないよう
  generation の払い出しを §8-d の replay と同じ CAS 状態機械にする。

  | 事象 | 挙動 |
  |---|---|
  | 同じ token での再送・timeout 後の再試行・crash 後の再開 | 同一 `repairGeneration` → 同一 `_id` → create-if-absent の 409 + digest 一致で成功扱い。event は増えない |
  | 直前の repair が `ACKED` の後に、運用者が改めて repair を要求 | `repairGeneration` を +1。**別の補償 event が増える**。監査上そうあるべき |
  | 直前の repair が進行中 (`REQUESTED` / `CREATED`) | 409。新規 repair を受け付けない |

  `confirmationToken` は `repairGeneration` にも bind する。誤って二重送信しても
  generation が上がらず、上の 1 行目に落ちる。
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
| IT-23 | 1 endpoint が単独で size 上限超過 | `UNRESOLVED(reason=OVERSIZE)` (terminal)。属性は落ちていない |
| IT-24 | catalog obligation の 4 outcome | 各 outcome へ決定的に遷移する。`SOURCE_PURGED` は snapshot から historical entity を作る |
| IT-25 | lease document を消して sequencer を回す | **自動再作成しない**。fail-closed で停止し health が `LEASE_MISSING` |
| IT-26 | old leader が期限切れ後・new leader の reclaim **前**に復帰 | 書込み直前の再確認で期限切れを検出し、latch が落ちて一切書かない |
| IT-27 | 同一 `processKey` を original / replay / repair で発行 | `deliveryId` が 3 つとも異なり、3 件とも journal に存在する |
| IT-28 | 同一 endpoint 集合を持つ chunk を複数作成 | `deliveryId` が異なり衝突しない。spool file 名も衝突しない |
| IT-29 | `WAITING_FOR_CATALOG` を obligation 解決まで放置 | publish retry 回数が**増えない**。`RESOLVED` 後に `PENDING` へ戻り publish される |
| IT-30 | 別 repository の external QN を持つ event を CouchDB へ直接注入 | builder / store / legacy reader / **sink 直前**の 4 層すべてで拒否される |
| IT-31 | 1,000 endpoint の verify | 逐次 GET にならない (bulk か上限付き並列)。全体 deadline 内に終わる。集合比較で過不足を検出 |
| IT-32 | `DIRECT` mode で emit | typed payload を best-effort で 1 回 publish できる。journal / cursor / verify / obligation は通らない |
| IT-33 | multi-target ORIGINAL の `deliveryId` | target 集合が同じなら決定的に同一。target 集合が違えば別 ID |
| IT-34 | 同一 ORIGINAL に対する target 別 REPLAY | ORIGINAL とも互いとも `deliveryId` が衝突しない |
| IT-35 | event-level `operationId` を欠いた v2 event | `build()` で拒否される。artifact を持たない processType でも必須 |
| IT-36 | lease document を消した状態で通常 acquire | **lease を作らない**。取得も失敗する |
| IT-37 | 復旧手順の途中 (管理 flag 立ち) で old AP が書き込む | 拒否される |
| IT-38 | 2 つの catalog task を待つ event の片方だけ `RESOLVED` | `WAITING_FOR_CATALOG` のまま。両方解決で `PENDING` へ戻る。`waitingSince` はリセットされない |
| IT-39 | hash 直列化の golden | `("ab","c")` と `("a","bc")` が別 ID。`null` と空文字が別 ID |
| IT-40 | 重複 endpoint / 順序違い | 重複は `build()` で拒否。順序違いは同一 `processKey` |

### Atlas-enabled E2E 受入表 (v3.3 release gate)

**`atlas.enabled=false` の緑は完了条件にしない。** CI に Atlas-enabled ジョブを追加し、下表を必須にする。

`FILE_SHARE_SYNC_UPLOAD` は **将来予約として維持する** (判断済み)。存在しない業務機能を
E2E のためだけに実装しない。

- enum は legacy deserialization 互換のため**残す**。javadoc に `RESERVED / producer なし` と明記する。
- business API E2E の分母は **producer のある 18 種**。v2.3.12 まで「17 種 (全数)」→ v2.3.13 で
  「16 + 1 拒否」→ v2.3.15 で GENERIC_EXTERNAL_INGEST (+1) → v2.3.16 で CHAT_MESSAGE_IMPORT
  (+1、CHAT_CONTEXT 非 attachment の fact 分類) が加わり **18 positive + 1 生成拒否
  (negative)** が現在の受入条件。
  注: 新分類の v2 event が現れるのは writer flip 後 — flip 前の E2E では同経路の v1 event は
  歴史的ラベル (IMPORT_UPLOADED / CHAT_ATTACHMENT_IMPORT) で観測される (LegacyV1Projection)。
- synthetic payload の単体テストのみ行う。
- **`LineageEventBuilder` からの新規生成は拒否する** (`IllegalArgumentException`)。
  正式な producer を実装するまで、この値の event が新規に生まれない。

| # | 受入項目 | 判定 |
|---|---|---|
| E-1 | schema apply が `applied:true` で完了 | `nemaki_folder_dataset` / `nemaki_import_artifact` / `nemaki_export_artifact` を含む |
| E-2 | **producer のある 18** `LineageProcessType` の producer-shape matrix **+ RESERVED 1 種の生成拒否** | 実 business API から発火し、各 Process の inputs/outputs が期待 entity と E-17 の条件で**完全一致**。`FILE_SHARE_SYNC_UPLOAD` は生成拒否 (negative) + synthetic payload の単体テストのみ |
| E-3 | folder endpoint | `nemaki_folder_dataset` GUID に結線される |
| E-4 | export artifact | `nemaki_export_artifact` が outputs に現れる |
| E-5 | external / cloud / cold endpoint | `nemaki_external_asset` GUID に結線される |
| E-6 | `IMPORT_UPLOADED` | input が `nemaki_import_artifact`、output が**移動した内容 (document / folder 群)** (§3 v2.3.13)。容れ物 folder は Process 属性。`upload://` 文字列は現れない |
| E-7 | inputs と outputs を**別々の**実 entity で完全一致検証 | 片側欠落・方向逆転を検出できる |
| E-8 | cross-repository event | `build()` で拒否される |
| E-9 | v1 legacy event | `UNRESOLVED` (terminal) になり cursor が進む。durable unresolved 記録と metric が残り、repair 対象になる。ordered projector が止まらない |
| E-10 | replay | 新 sequence の補償 event が publish され、Atlas に Process が現れる |
| E-11 | mutation | §5 の QN 規則を1つ壊すと E-2 が落ちる |
| E-12 | cleanup | 中間テストを故意に失敗させても CMIS / Atlas / CouchDB の fixture が残らない |
| E-13 | 同一 folder への import を 2 回 | 別 event になる (`processKey` が event-level `operationId` を含む)。1回目が握り潰されない |
| E-14 | folder backfill | 既存全 folder に proxy がある。orphan reconciliation が 0 を報告 |
| E-15 | folder delete → restore | proxy が `active=false` → `true`。過去 Process の参照が壊れない |
| E-16 | bulk partial response | 片方欠落を検出し reconcile される |
| E-17 | production sink の成功条件 | 下記 verify 契約を満たした場合のみ success |
| E-18 | `DIRECT` mode | **positive control**: typed event が best-effort で 1 回 publish できる。加えて journal / cursor / verify / obligation を通らないこと。E-1〜E-17 の耐久保証は対象外 |
| E-19 | **cloud 資産に秘密が載らない** (live Atlas) | `?authkey=` / `/:x:/g/…TOKEN` を持つ URL を `nemaki:cloudFileUrl` に格納した document を sync し、Atlas に実際に作られた entity を取得して token がどの属性にも無いこと。単体は payload を検査するが、これは**backend が保存した実体**を検査する |
| E-20 | **既発行値の除去** (live Atlas) | A-1g 以前の形式 (raw URL 入り) の entity を先に作り、republish 後に `cloudFileUrl` が実際に消えているかを取得して確認する。**消えない場合はそれが仕様であり**、明示的な purge / entity 再作成手順を release 手順に加える (§4「既発行値の除去」)。null が既存 property を削除するかは backend 依存で、**設計上の推論では確定できない** |

### E-17 verify 契約 (v2.1)

「POST 直後に GET 1 回」では Atlas の read-after-write 遅延で偽陰性になる。

- **bounded poll**: `lineage.verify.timeout-seconds` (既定 30) / `lineage.verify.interval-seconds` (既定 2)。
  総 deadline を超えたら verify 失敗。
- **endpoint を 1 件ずつ GET しない (v2.3)。** 最大 1,000 endpoint を逐次 GET すると、
  差し戻した fail-closed preflight と同じ長時間停止を再現する。
  - Atlas の **bulk 取得** (`/entity/bulk?guid=...` または `search/basic` の一括問い合わせ) を使う。
  - bulk が使えない場合のみ、`lineage.verify.parallelism` (既定 8) の**上限付き並列**にする。
  - 個別の GET timeout とは別に**全体 deadline** を持つ。
  - 比較は 1 件ずつではなく **endpoint 集合の完全比較** (期待集合と実際の集合が等しいこと)。
    過不足のどちらも検出する。
- **verify 待ちは publish retry を消費しない。** target 状態に `VERIFYING` を追加し、
  `PROJECTING → VERIFYING → PUBLISHED | FAILED | UNPROJECTABLE` とする。`VERIFYING` からの再試行は
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
| `ARCHIVE` | `nemaki_archive` | `repositoryId` + **`archiveId`** | `archivedAt` (epoch millis) + `originalObjectId` |
| `EXTERNAL_ASSET` | `nemaki_external_asset` | `repositoryId` + `externalStableKey` の hash | `sourceSystem` + `externalStableKey` |
| `CLOUD_OBJECT` | `nemaki_external_asset` | 同上 | 同上 (provider は `sourceSystem` に載せる) |
| `COLD_STORAGE` | `nemaki_external_asset` | 同上 | 同上 (storageClass は `sourceSystem` に載せる) |
| `IMPORT_ARTIFACT` | `nemaki_import_artifact` | `repositoryId` + `operationId` | `importMode` |
| `EXPORT_ARTIFACT` | `nemaki_export_artifact` | `repositoryId` + `operationId` | `artifactKind` |

**v2.3.2 訂正 (実装時に既存 schema と突き合わせて判明)**

| 旧記述 | 実際 | 理由 |
|---|---|---|
| `ARCHIVE` の同一性は `repositoryId` + `originalId` | `repositoryId` + **`archiveId`** | 既存 catalog sync が既に `nemaki://{repo}/archives/{archiveId}` を書いている。1 文書を 2 回アーカイブすれば archive は 2 つで、original では区別できない |
| `originalId` | **`originalObjectId`** | `nemaki_archive` の属性名。`originalId` という属性はどの型にも無い |
| `archivedAt` は文字列 | **`long` (epoch millis)** | `nemaki_archive.archivedAt` は `long`。書式化した時刻は落ちる |
| `CLOUD_OBJECT` 必須 = `provider` / `COLD_STORAGE` 必須 = `storageClass` | どちらも `sourceSystem` + `externalStableKey` | `nemaki_external_asset` に `provider` / `storageClass` / `tenantId` は**無い**。宣言しても Atlas が捨てるだけで、allowlist が防ぐはずの失敗を allowlist 自身が作る |
| — | `CMIS_DOCUMENT` から `mimeType` / `contentLength` を削除 | 同上。`nemaki_document` にどちらも無い |

`provider` / `storageClass` / `tenantId` / `mimeType` / `contentLength` を属性として持たせたい場合は、
**増分 B の additive schema 変更**として `nemaki_external_asset` / `nemaki_document` に足したうえで
`EndpointKind` に戻す。順序を逆にしてはいけない。
`EndpointKindSchemaAlignmentTest` が両者を機械的に突き合わせており、型が増えれば
`AWAITING_SCHEMA` を縮める必要があるので、B の作業から漏れない。

E-12 の「故意に失敗させる」テストは、cleanup が file-scope で動くことの決定的証拠として必須。

---

## 実装増分 (再 sign-off 後)

| 増分 | 内容 | 独立に検証できるか |
|---|---|---|
| **A-2 Slice 4** | v2 write への切替。**§6-a の rollout fence (4a/4b) が前提**。Slice 1〜3 は additive で本節に依存しない |
| **A** | typed `LineageEndpoint` (全 kind で `repositoryId` 必須) + endpoint-local snapshot allowlist + kind 明示 builder + producer 全書き換え + サーバー発行 `operationId` + **`processKey` / `deliveryId` 分離** + endpoint 件数/payload 上限と chunking + `FILE_SHARE_SYNC_UPLOAD` 生成拒否 + cross-repo 検証 4層 (external 含む) (§2 §3 §7) | 単体。Atlas 不要 |
| **B** | schema additive 拡張 (`nemaki_folder_dataset` / **`nemaki_import_artifact` / `nemaki_export_artifact` — 実装済み (v2.3.29)**、および `nemaki_external_asset` への `provider`/`storageClass`/`tenantId`、`nemaki_document` への `mimeType`/`contentLength`) + catalog sync の同一 bulk 作成と **partial response の reconcile** + **既存 folder の authoritative backfill** + lifecycle (rename/move/delete/restore/`sourceState`) + **`LineageCatalogReconciliationService`** (§2 §3) | schema apply + backfill + orphan reconciliation + obligation IT |
| **C** | `CatalogPayloadFactory` へ payload 生成を集約、canonical QN を1箇所化 (既存 `buildExternalAssetQualifiedName` を移設)、`AtlasLineageSink` から payload を剥がす、**POST 後 GUID 完全一致を production の成功条件に** (§4 §5) | 単体 (payload golden) + sink IT |
| **D** | event-first UNSEQUENCED + fenced sequencer (bootstrap 専用 lease / 書込み直前再確認 / 一方向 latch / crash 再開) + 状態遷移 CAS (`VERIFYING` / `WAITING_FOR_CATALOG` 含む) + cursor 単調 CAS + counter・lease 復旧手順 + durable spool と spool scanner + replay generation CAS (§8) + IT-1〜IT-32 | 実 CouchDB IT |
| **E** | v1 legacy reader + durable unresolved (§6)、repair API + opaque confirmation token + DLQ bundle upload (§9)、`VERIFYING` と E-17 verify 契約 (§10)、Atlas-enabled E2E 受入表 E-1〜E-17 | E2E |

依存: A → B → C、**D は分割** (下記)、E は A〜D 完了後。

D を 1 つの増分にすると **D → A-2 Slice 4a → D** の循環になる (Slice 4a の完了条件に
spool readiness が入るため)。§6-a のとおり割る:

- **D-spool** — 版非依存 fact spool + scanner + fsync 検証。`deliveryId` を要さない。依存は
  A-1 + P-1 (`LineageFact` が変換入力 — v2.3.17 ⑥ で「A-1 のみ」から訂正)。**実装済み (非活性)**
- **D-rest** — sequencer / cursor CAS / replay generation CAS。`deliveryId` の**計算**を使うので
  **A-2 Slice 1〜3 の後**。ただし v1/v2 dual・非活性で配布するので、**Slice 4 より前**である
  (§6-a 撤回 4)。v2.3.10 の「Slice 4 の後」は撤回済み

順序: **A-2 Slice 1〜3 → D-spool → D-rest (dual・writer は v1) → A-2 Slice 4a → 4b → E**。

D の内部部品 (fenced sequencer、cursor CAS、counter/lease 復旧) は A と並行に書けるが、
spool file 名 (materialize 済みのもの)・journal `_id`・冪等判定はいずれも `deliveryId` であり、
`deliveryId` の計算は A-2 Slice 1〜3 が確定させる。したがって**統合は Slice 1〜3 の後**
(Slice 4 の後ではない)。v2.3.1 までの「D は A と独立」は §3 の
identity 分離を入れる前の記述で、成立しない。

### A の分割 (実装時)

| | 内容 | 状態 |
|---|---|---|
| **A-1** | `EndpointKind` / `EndpointAttribute` / `LineageEndpoint` / `LineageIdentity` / `LineageCanonicalHash` / `LineageRepositoryScope`。型・属性契約・identity 符号化 | 完了 (producer 配線は P-1〜P-3c で完了) |
| **A-2** | `LineageEvent` を v2 形状へ移行、`creationPayloadDigest` と integrity 検査、cross-repo 検証 4層の**配線**、producer 全書き換え、chunking、`FILE_SHARE_SYNC_UPLOAD` 生成拒否 | **Slice 1a〜3 + producer P-1〜P-3c 完了** (下表)。writer は v1 のまま。残: chunking (fact→v2 写像内・設計固定済み)・生成拒否 E2E・4a/4b |

| A-2 slice | 実装状態 |
|---|---|
| 1a (v2 型・delivery union・shape 表・純関数 builder) | 完了 |
| 1b (版非依存 read model `LineageRecord` / `LineageAssetRef`) | 完了 |
| 2a (sink 契約 → `LineageRecord`、3 sink 移行) | 完了 |
| 2b (admin API 表示経路、版非依存キー併記) | 完了 |
| 2c (projector の判断を record に、quarantine、occurredAt 比較修正) | 完了 |
| 2d-1 (無損失 `LineageJournalEntry` + v2 codec、`type=lineage_event_v2`) | 完了 (production 未配線) |
| 2d-2a (view 両 type 化 + Rhino 実行検証、型付き行結果、recordId helper) | 完了 |
| 2d-2b (store read の一斉切替、recordId 分離、Undecodable の無変異、v2 の DISCARD 禁止) | 完了 |
| 3 (appendV2 の 409 完全一致・`LineageIntegrityException`・§7 pre-sink gate・`REJECTED`) | 完了 (writer は v1 のまま — appendV2 は production 未呼出) |
| P-1 (`LineageFact` + LegacyV1Projection・emitter seam・fail-open facade) | 完了 (seam は無条件で v1 射影) |
| P-2a (RetentionScheduler: ARCHIVE_LOCAL / ARCHIVE_COLD) | 完了 (preservation test で eventKey 固定) |
| P-2b (ArchiveResource: ARCHIVE_LOCAL force) | 完了 (削除前の名前読取りは guard 済み) |
| P-2c (CloudDriveResource ×3・IngestLineageEmitter + `GENERIC_EXTERNAL_INGEST` 新設) | 完了 (v2.3.15 ①〜⑥) |
| P-3a (moved-content 配管: ImportResult.createdObjects / ExportedObjectCollector) | 完了 (v2.3.16 ①②) |
| P-3b (ImportExportResource 5 箇所変換 + publish* fail-open + operationId 返却) | 完了 (v2.3.16 ③〜⑥) |
| P-3c (`CHAT_MESSAGE_IMPORT` 新設 + CHAT_CONTEXT 分類逆転の v2 側訂正) | 完了 (v2.3.16 ⑦。restore 系は flip 後へ延期 — ⑧) |

A-1 を分けたのは `LineageEvent` の移行対象が 14 ファイル・`inputs()/outputs()` 参照 79 箇所に
及び、片方だけ入った木が壊れるため。A-1 単体で型と identity は閉じている。

#### A-2 の分割 (v2.3.12)

「型を足すだけ」の Slice 1 と「writer を切替える」Slice 4 の 2 つに割ると、**間の全部が
Slice 4 に落ちる** — v1 `LineageEvent` は store 契約
([`LineageJournalStore`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageJournalStore.java))
と sink 契約
([`LineageTargetSink`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/LineageTargetSink.java))
の両方に型として現れているので、writer を切り替える commit が controller・projector・
dead letter・3 sink をまとめて書き換えることになる。それは「中間状態を壊さない」を満たすが、
**レビューできない大きさ**であり、壊れたときに戻す先が無い。

読取り側を先に版非依存にする。

| slice | 内容 | この時点で production は |
|---|---|---|
| **1** | v2 型・delivery の tagged union・identity 束縛・**`occurredAt` を要求する純関数 builder** (§3)・processType ごとの shape 表・**正規化 read model** (v1 からも v2 からも作れる)。配線なし | v1 を書き、v1 を読む |
| **2** | codec (CouchDB JSON ⇄ v2) と consumer の read model 移行。**供給するのは v1 だけ**で振る舞いは変えない | v1 を書き、**版非依存の経路で** v1 を読む |
| **3** | `creationPayloadDigest` + 409 完全一致 + integrity 例外 → spool + metric、`operationId` 検査 (store・sink 直前) | v1 を書く |
| **4a / 4b** | §6-a の rollout fence と writer 切替 | — |

Slice 2 の内訳 (各 commit が単独で green・v1 のまま):

| | 内容 | なぜここで切れるか |
|---|---|---|
| **2a** | sink 契約を `publish(LineageRecord)` に。3 sink を移行 | 呼び出し側が envelope を**まだ持っている**ので、失敗時の dead letter は現行のまま渡せる |
| **2b** | admin API の表示経路を projection 経由に | store の署名を変えない |
| **2c** | projector の claim / cursor / status 判断を `LineageRecord` に。**envelope は失敗時のために保持する** | 同上 |
| **2d-1** | 版タグ付き**無損失 journal entry** 抽象と v2 codec を足す。**store の公開戻り値は変えない** | 追加のみ |
| **2d-2** | store の read 署名を `LineageRecord` へ**一斉に**切替。`NoopLineageJournalStore`・Couch の内部 helper・purge/reaper・controller/projector の呼び出し・`CouchLineageDeadLetterStore.replay` の lookup を同時に | 部分適用できない (下記) |

`append` / `appendAll` は Slice 2 の全期間で **v1 のまま**である。

**2d を 1 commit にできない理由。** store の read が `LineageRecord` だけを返すようになると、
失敗時に dead letter へ渡す envelope が**どこからも得られなくなる**。`LineageRecord` は復旧
payload ではない (上記) ので、そこから envelope を作ることは意図的にできない。
`CouchLineageDeadLetterStore.replay` も `findByEventId` の戻りを `LineageEvent` に代入している。
したがって**無損失 entry 抽象が先**で、署名の切替はその後の一斉適用になる。

**`recordId` と `eventId` は v2 で別物になる。** `updatePublishStatus` / `discardEvent` /
`getRetryCount` は現在いずれも引数を eventId とみなし、Couch の `_id` を
`lineage:` + その値で作る。v2 では journal `_id` は `deliveryId` 由来なので、この 3 つは
**`recordId` を受け取る**ように再定義し、`recordId → _id` の変換を 1 箇所に集約する。
`findByEventId` (監査上の eventId で引く) と `findByRecordId` (journal 文書を引く) も分ける —
v2 では前者で引いて後者の値で更新すると**別の文書を叩く**。

#### producer 書き換えの設計 (v2.3.14 — Codex レビュー済み・safe with named changes (B))

producer は版非依存の **`LineageFact`** (§6-a の spool fact と同じ意味形状) を 1 つ作り、
emitter が v1/v2 へ写像する。Slice 4 の writer flip は emitter 内の写像選択の変更になり、
producer 12 箇所は触らない。

中核の危険は **v1 の `eventKey` が input/output 文字列の hash** であること — 写像が 1 文字
ずれれば同一業務操作が deploy 前後で別 Process になり冪等性も壊れる。よって:

- **(B) LegacyV1Projection 方式** — fact は typed endpoint に加えて **v1 の processType・
  input/output 文字列を明示的に併載**する (推論表は不採用 — 検証の結果、null-archetype
  ingest の v1 型が IMPORT_UPLOADED である・ZIP 系 export に output が無い・import の
  output が作成物でなく folder である等、推論では汚い分岐が避けられない)。保存は構成で
  保証し、旧 builder 出力との equals テストを producer ごとに置く。v1 文字列は flip で削除。
- **fact は構築時に v2 shape 表で検証**する — producer は初日から v2 正のかたち (export
  artifact 込み) を供給し、v1 射影がそれを落とす。
- **前提配管**: import/export の実装は現在「件数と folder」しか持ち帰らない。v2 shape が
  要求する「作成/搬出した全 object」の ID 収集を先行 commit で足す。これにより
  EXPORT_SELECTED_OBJECTS 以外 (bulk import・再帰 export) も chunking 対象になる。
- **chunking は fact→v2 写像内・canonical UTF-8 順で分割** (producer の走査順に依存すると
  chunk 割りが揺れて process identity が揺れる)。必須データだけで 1 MiB を超える場合の
  terminal `UNRESOLVED(OVERSIZE)` を含む。v1 射影は chunk しない (現行どおり)。
- **fail-open facade**: typed factory と shape 検証は v1 builder より遥かに厳しく、fact
  構築が throw しうる。業務レスポンスを握る broad catch の中で構築してはならない —
  lineage 専用の非 throw facade (metric + dead-letter 報告) を通す。
- **operationId は業務開始時に発行**し X-Nemaki-Operation-Id で返す。**v1 の runId には
  書かない** (eventKey 外だが v1 envelope を変えない)。**occurredAt は業務 fact 成立時**
  (request 開始時ではない) に一度だけ採る。
- **spool payload は fact の Java 型を直接使わない** — 版付き `LineageSpoolPayloadV1` +
  明示変換 (fact の進化や v1 文字列削除が durable 記録を壊さないため)。
- **emitter seam**: `emit(LineageFact)` を追加し fact→v1 射影を無条件適用。journaled mode が
  appendV2 を呼ばないことをテストで固定し、選択の変更は fence された Slice 4 のみ。

**`read:v2` capability (§6-a) を名乗ってよいのは Slice 2 の完了後**である。decoder クラスが
存在することと「v2 を復号して projector へ流せる」ことは別で、前者だけで capability を
立てると §6-a の CAS 条件 8 が**満たされていないのに通る**。capability は経路が通っている
ことの表明であって、クラスの存在の表明ではない。

#### producer 書換え時に直す confirmed bug と追加 (v2.3.13)

外部分析で確認した producer 側の問題。**v1 の挙動は変えず**、v2 producer を書くときに直す。

| # | 事実 | 対応 |
|---|---|---|
| 1 | [`IngestLineageEmitter.resolveProcessType`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestLineageEmitter.java#L136) の `CHAT_CONTEXT` 分岐が**逆** — 実 attachment が汎用 `EXTERNAL_ATTACHMENT_IMPORT`、非 attachment が `CHAT_ATTACHMENT_IMPORT` になる。`MESSAGE_CONTEXT` (attachment → `MAIL_ATTACHMENT_IMPORT`) と不整合 | **`CHAT_MESSAGE_IMPORT` を新設**し、attachment → `CHAT_ATTACHMENT_IMPORT` / 非 attachment → `CHAT_MESSAGE_IMPORT` に正す。enum が増えると `LineageProcessShapeTest` の網羅検査が shape 規則と件数の更新を強制する (そのための検査) |
| 2 | archetype null の非 attachment を `IMPORT_UPLOADED` に**縮退**させている — 「分類できなかった connector 取込」を「利用者の upload」と偽る | **`GENERIC_EXTERNAL_INGEST` を新設** (external asset ×1 → document ×1)。UNRESOLVED 化はチェーン自体を失うので採らない — 「external asset X が document Y になった」は真であり、偽なのは `IMPORT_UPLOADED` というラベルだけである |
| 3 | 監査には `ARCHIVE_RESTORE` があるが lineage に無い。restore / rehydrate の方向 (archive→active、cold→archive) が表現できない | **`ARCHIVE_RESTORE`** (`ARCHIVE` ×1 → `CMIS_DOCUMENT` ×1) と **`COLD_RESTORE`** (`COLD_STORAGE` ×1 → `ARCHIVE` ×1) を新設。復元は新しい active 版を作る事実として記録する |

いずれも enum 追加を伴うため、E-2 の分母 (16 positive + 1 negative) はその時点で更新する。

#### 増分 B に追加する版・manifest 属性 (v2.3.13)

「どの版が動いたか」を固定できなければ、同じ外部 file を 3 回取り込んで 3 版できても
グラフ上は毎回「同じ external asset → 同じ document」に見える。追跡機能としてはそこが本体
なので、B の additive schema 変更に以下を含める (すべて `EndpointKindSchemaAlignmentTest` の
`AWAITING_INCREMENT_B` 方式で両側同時に足す):

| 型 | 追加属性 |
|---|---|
| `nemaki_document` | `versionObjectId` / `changeToken` / `contentHash` (既載の `mimeType` / `contentLength` と併せて)。`versionSeriesId` は **schema に既在**のため v2.3.13 で allowlist へ即時追加済み (`ARCHIVE` も同様) |
| `nemaki_external_asset` | `sourceRevision` (ETag 等) / `sourceModifiedAt` / `sourceContentHash` / `sourceContentLength` |
| `nemaki_import_artifact` / `nemaki_export_artifact` | `manifestDigest` / `totalObjectCount` / `totalByteLength` / `completedObjectCount` / `failedObjectCount` / `businessResult` (`SUCCESS` \| `PARTIAL`) — chunk 群を束ねる manifest。E-17 の verify にも版・hash・chunk 完全性の検査を足す |

#### 採らなかった提案 (v2.3.13)

| 提案 | 判断 |
|---|---|
| `movementKind` (INGEST / EGRESS / ARCHIVE / …) 軸への再編 | **保留**。processType から純関数で導出できる表示属性としてなら安全だが、identity・sink 写像・Atlas Process 型に波及する再編は B 以降の設計判断。event の必須フィールドにはしない |
| `CLOUD_SYNC_DOWNLOAD` と `FILE_SHARE_SYNC_DOWNLOAD` の統一 | **保留**。同じ移動が API 経路で別型になるのは事実だが、統一は「どちらかの型の producer を無くす」ことであり、sink 写像 (`nemaki_cloud_sync_process`) と v1 監査の連続性に触る。producer 書換え slice の設計で決める |
| Teams の文脈 (message) と実体 (SharePoint file) の分離 | **保留**。stableKey の書式変更 = catalog sync を含む identity migration。B で provider-canonical URL / tenant を扱うときに一緒に設計する |
| 外部 identity canonicalization の欠陥列挙 (QN/stableKey 不一致・query 受理・未正規化 等) | **陳腐化として棄却**。挙げられた 5 点は A-1c〜A-1k で修正済み — 完全一致 QN 検査 (`LineageRepositoryScope`)、`requireCanonicalStableKey`、`requireNoUriBorneSecrets`、`normalisedAbsolutePath`、redacted `describeQualifiedName`。集約先も既に `ExternalAssetIdentity` 1 箇所である |

##### 正規化 read model は復旧 payload ではない (v2.3.12)

`LineageRecord` (Slice 1b) が担うのは **表示・振り分け・catalog 発行**の 3 経路だけである。
**dead letter / spool / replay の payload には使わない。**

v2 の同一性は delivery union・`operationId`・chunk 座標・`creationPayloadDigest` に依存し、
read model はそのどれも持たない。持たせれば read model が第二の envelope になり、
「表示のための型」と「復旧のための型」が同じものになる。

v1 の dead letter は**既にこの意味で lossy** である —
[`CouchLineageDeadLetterStore.reconstructEvent`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/CouchLineageDeadLetterStore.java#L310)
は `schemaVersion=1` / `sequenceNumber=0` / `runId=""` / `correlationId=""` / `version=1` を
**焼き込んで**再構成する。v1 では `_id` が UUID だったので害が無かったが、v2 で同じことを
すると **再計算した identity が保存時の identity と一致しない**記録ができる。

復旧経路は**版タグ付きの無損失 envelope**を持つ。どちらの版かを記録し、その版の型へ
そのまま戻せること。D-spool と §9 の repair の入力仕様はこれに従う。

##### v1 と v2 で Process QN が変わることは read model の責務ではない

v1 は `eventKey`、v2 は `processKey` から catalog の Process QN を作るので、同一業務操作に
**別の Process entity** が生まれる。§3 はこれを既に決めている — v1 の replay / repair は
既存 Process を更新せず、v2 の補償 event を新設し、v1 の Process は監査事実として残す。

`LineageRecord` は `idempotencyKeyVersion` で**どちらの規則で作られた名前か**を報告するだけで、
この決定を実装しない。実装するのは replay / repair のコマンド経路である。Slice 2 は
**現行の v1 replay の振る舞いを変えない**。

---
