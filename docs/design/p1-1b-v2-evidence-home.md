# P1-1(b) — 取込 snapshot に v2 の居場所を与える

対象: [`authenticity-roadmap.md`](authenticity-roadmap.md) §1-3 が P1-1(b) として残した 2 件。

1. 取込 snapshot は **v1 projection 限定**で、`LineageFact` の設計上 v2 に home が無い。
   **v2 write flip で失われる**
2. `nemaki:chatCapturedAt` は**オブジェクトに刻まれるが snapshot には入らない** — 刻印が
   emit の**後**に走るため

> **この文書は 1 巡目のレビューで 2 つの案を撤回して書き直したもの。** どちらも「置き場所が
> 在るか」を確かめずに割り当てており、しかも**コード側に「そうしなかった理由」が javadoc で
> 明記されていた**。撤回の記録は §8。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 取込が記録する証拠のうち **endpoint に属する事実**が、v2 でも運ばれる。
v2 write flip が来ても `contentStored` / `contentHash` / 会話の同一性が消えない。

**主張しない**:

- **これは真正性の証明ではない。** 事実が運ばれることと、その事実が正しいことは別
- **既存の v1 行は移行しない。** flip 以前の行は v1 のまま。読み側が両方読む
- **v2 で全部が救われるとは言わない。** §3 のとおり、v2 に home を作れないものが残る。
  **残るものは残ると書く**
- **flip がいつ来るかは、この作業では決まらない。** §7 のとおり、取込から v2 イベントが
  出る経路は**まだ存在しない** (Slice 4 未着)

---

## 1. 現状の事実 (コードで確認)

| | |
|---|---|
| v1 の event-level `snapshotAttributes` | `LineageFact.LegacyV1Projection` が運ぶ。取込は `buildV1Snapshot` で約 20 キーを詰める |
| v2 の `LineageEventV2` | **`snapshotAttributes` を持たない**。javadoc が「§2 の endpoint-local attributes が置き換える」と明記 |
| v2 で属性を運べるもの | **`LineageEndpoint.attributes` だけ** |
| allowlist 外の attribute | **`IllegalArgumentException`** (`EndpointKind.validateAttributes`)。`LineageEndpoint` の正準コンストラクタが呼ぶので、**fact 構築が例外になる**。「到着時に落ちる」のは配送先 (Atlas) の型に無い場合の話で、**別の現象** |
| 取込が作る endpoint | input = `externalAsset` 1 つ、output = `document` 1 つ。**それ以外は shape が禁じる** (§3.0) |
| `CMIS_DOCUMENT` allowlist | `name` / `versionLabel` / `folderPath` / **`versionLabelOriginalSha256`** / **`folderPathOriginalSha256`** / `versionSeriesId` / `mimeType` / `contentLength` / `versionObjectId` / `changeToken` / **`contentHash`** |
| `EXTERNAL_ASSET` allowlist | `sourceSystem` / `externalStableKey` / `externalPath` / `tenantId` / `sourceRevision` / `sourceModifiedAt` / `sourceContentHash` / `sourceContentLength` |
| allowlist と配送先の結合 | **`EndpointKindSchemaAlignmentTest` が機械的に突き合わせる**。allowlist に足して Atlas 型に足さないと**赤**。`eachKindDeclaresExactlyTheseAttributes` が kind ごとの正確なリストも固定 |
| `TRUNCATE_WITH_EVIDENCE` の attribute | **companion (`…OriginalSha256`) が無いと enum のクラス初期化で `IllegalStateException`** = lineage 全体が起動しない |

**`contentHash` は allowlist に枠が在るだけで、取込は出していない。**
`IngestLineageEmitter:101` は `LineageEndpoint.document(repositoryId, objectId, documentName)` の
**3 引数 factory** を呼んでおり、attribute を取る overload (`LineageEndpoint:260`) は
`RetentionScheduler` と `ImportExportResource` しか使っていない。

> **枠が在ることと値が出ることは別。** 「既存だから足りている」と書いた 1 巡目・2 巡目は
> どちらもここを確かめておらず、設計どおりに実装すると **§0 が名指しで守ると言った
> `contentHash` が v1 にしか無いまま flip を迎える**。

---

## 1.5 今日 Purview に何が届いているか (**2 巡目で作った表**)

**1 巡目も 2 巡目も、この表を作らずに割り当てて落ちた。** 1 巡目は「置き場所が在るか」を
確かめず、2 巡目は逆に「置き場所が無い / 配送されていない」を確かめずに書いた。原因は
どちらも同じで、**`AtlasLineageSink` だけを見て「配送」を結論した**こと。

事実:

- `PurviewLineageSink.publish` は **v1 snapshot を丸ごと Process 属性に写す** (`:66-70`)
- `LineageRecord` の対応表: **v2 record の `legacyEventAttributes` は空** (`:59`)
- `addProcessTypeAttributes` は取込 process type に対し
  `folderId` ← `targetFolderId`、`importMode` ← `sourceArchetype`、
  `sourceDescription` ← `sourceSystem:sourceObjectId` を**明示的に**詰める (`:390-404`)
- `nemaki_import_process` が宣言するのは
  `repositoryId` / **`folderId` (必須)** / **`importMode` (必須)** / `externalStableKey` /
  `sourceDescription` / `objectCount` **だけ**。それ以外は**到着時に落ちる**

| v1 snapshot のキー | 今日 Purview に届くか | flip 後 (v2) はどうなるか |
|---|---|---|
| `targetFolderId` | **届く** — `folderId` (必須属性) に写される | **`""` になる**。`snap` が空なので `getOrDefault` が既定を返す |
| `sourceArchetype` | **届く** — `importMode` (必須属性) | **`"external"` になる** (既定値) |
| `sourceSystem` / `sourceObjectId` | **届く** — `sourceDescription` に連結 | **`":"` になる** |
| `contentStored` / `contentHash` / `contentHashAlgorithm` / `contentHashUnavailable` | **落ちる** — 型に無い | 変わらず落ちる |
| `executedBy` / `onBehalfOf` | **落ちる** | 変わらず落ちる |
| `chat.*` (participants 含む) | **落ちる** — 型に無い | 変わらず落ちる |
| `sourceObjectType` | **落ちる** | 変わらず落ちる |

**この表が示すこと**: flip の被害は「v1 にしか無い証拠が消える」だけではない。
**今日 Purview に届いている 3 つの必須属性が、既定値に静かに置き換わる。**
`folderId` と `importMode` は `isOptional=false` なので、**必須属性が空文字で埋まる**。

**したがって `targetFolderId` は「置き場所が無い」ではない。** 置き場所は
`nemaki_import_process.folderId` で、今日そこに届いている。flip で断たれるのは
**endpoint ではなく Process 属性の供給経路**である。

---

## 2. digest と identity の被覆 (これが割り当てを決める)

| | attributes を含むか |
|---|---|
| `LineageIdentity.processKey` / `deliveryId` | **含まない** — endpoint の修飾名だけ |
| `LineageEventDigest.creationPayloadDigest` | **含む** (`endpointRecord` が `attributes` を入れる) |

したがって:

- endpoint attribute を足しても **`processKey` は動かない** → 同一性は壊れず、golden vector も動かない
- endpoint attribute は **digest に覆われる** → 証拠として意味がある
- **`LineageEventV2` にフィールドを足しても digest は覆わない** → **改竄が検出できない**。
  「digest の外にある home は home ではない」

---

## 3. 事実の割り当て

### 3.0 前提: 取込の endpoint は 1:1 で固定されている

`LineageProcessShape` が全 external-ingest process type を
`shape(one(EXTERNAL_ASSET), one(CMIS_DOCUMENT))` に束縛しており、種別と個数の両方で弾く。
`LineageFact` のコンストラクタが `validate` を呼ぶので、**endpoint を増やすと fact 構築が
例外になり、その取込 1 件の証拠が丸ごと消える** (`lastFailure` が立って null が返る)。

**したがって endpoint を増やす案は取れない。** 属性を足すことだけができる。

### 3.1 output endpoint (`CMIS_DOCUMENT`) に足す

| snapshot キー | v2 attribute | 備考 |
|---|---|---|
| `contentHash` | `contentHash` | **既存** |
| `contentHashAlgorithm` | `contentHashAlgorithm` | 追加 |
| `contentStored` | `contentStored` | 追加。**三値を落とさない** |
| `contentHashUnavailable` | `contentStateReason` | 追加。**`text()` (PRESERVE)** — 下記 |

**`contentStored` を推測させない。** `contentHash` の有無からは `UNKNOWN` が復元できず、
「digest が無い = 保存していない」と読まれる。

**`contentStateReason` は `displayText()` ではなく `text()`。** 2 巡目の稿は「散文だから
companion が要る」としたが、reason は利用者由来ではなく
`CanonicalImportServiceImpl:1002-1016` と `IngestLineageEmitter:212` の**コード内定数 4 種**で、
可変部は attachment node id 1 個だけ。1024 code unit を超えようがない。
`EndpointAttribute.Policy` の javadoc は「既定はどこでも PRESERVE、例外は 1 つずつ数え上げる」
と書いており、`importMode` / `archiveState` / `artifactKind` という同種の機械由来文字列は
すべて `text()` である。`displayText()` にすると **決して埋まらない companion 列**が
`nemaki_document` と exact-list テストに増える。

**列型は `string`。** `declaredTypesMatchTheAtlasColumnTypes` が TEXT → `string` を強制するので、
`contentStored` は三値の文字列であって `boolean` 列ではない。

### 3.2 input endpoint (`EXTERNAL_ASSET`) に足す — **会話の同一性だけ**

**3 巡目で「4 つのうち 3 つは identity に在る」と自己訂正したが、それも検算不足だった。**
4 巡目のレビューと、実装で書いた対応テストが**同時に**捕まえた。

実測 (`IngestEvidenceCorrespondenceTest`): チャットメッセージの stable key は

```
acme-chat://channels/C1/messages/1720000000.000200
```

— **workspace 区間が無い**。`ExternalSourceUri.build` の tenant 区間は
`connector.getTenantId()` から作られ、snapshot の `chat.workspaceId` は
`request.getMetadata()` から来る。**出所が別**で、しかも connector に tenant が無ければ
区間ごと落ちる。

`messageId` はもっと明確で、チャット**添付**の URI は `channels/{channelId}/files/{fileId}` —
key に入るのは**ファイルの id** で、`metadata.messageId` は**親メッセージ**である。
**添付を親メッセージに結ぶ唯一の識別子**なので、落とすと flip でその結び付きが切れる。

| snapshot キー | v2 の居場所 | 根拠 |
|---|---|---|
| `sourceObjectType` | `sourceObjectType` — `text()` | key の path 区間が暗示するが明言しない |
| `chat.workspaceId` | `chatWorkspaceId` — `text()` | **key に無い** (tenant は connector 由来) |
| `chat.messageId` | `chatMessageId` — `text()` | **添付では key に無い** (key はファイル id) |
| `chat.threadId` | `chatThreadId` — `text()` | key に無い |
| `chat.channelId` | **identity** | message / attachment のどちらの URI も、**同じ metadata から** `channels/{channelId}` を作る |
| `chat.channelName` | **足さない** | 下記 |

> **`identity` は最も危険な逃げ道である。** 「key が運ぶ」は理由文字列では検算できない。
> 対応テストは **stable key を実際に見て**、値が入っているかを確かめる
> (`V2Encoding.carries` の `IDENTITY` 分岐)。負のコントロールで、
> `chatMessageId` を `identity(...)` と偽ると落ちることを確認している。

**`chat.channelName` は入れない。** 呼び出し元の metadata から素通しで来る自由文字列で、
DM やグループ DM では**相手の氏名そのもの**になる — §6 が participants を外した理由が
そのまま当たる。

**policy は `text()` (PRESERVE)。** 識別子は切り詰めると別のものを指すので
(`versionSeriesId` が `text()` である理由と同じ)。1024 超は
`LineageChunkPlanner` の `UNRESOLVED(OVERSIZE)` に落ちる — **それは正しい挙動**で、
「切り詰めた識別子を証拠として保存する」より良い。

**`captureWindowStart/End` / `evidenceScope` / `selectionReason` は入れない。** 理由が 2 つ:

1. これらは**取込元の性質ではなくこの取込の判断**である。`externalAsset` の identity は
   source URI なので、同じメッセージを再取込すると**同じ qualified name** になり、
   配送先は upsert する — **2 回目の判断が 1 回目を黙って上書きする**
2. `LineageEndpoint` の javadoc が attributes を
   「captured at emission and never updated afterwards」と定義している。上書きされる値を
   そこに置くのは**型の契約に反する**

### 3.3 v2 に home を作らないもの — **残ると書く**

| 事実 | なぜ v2 に置かないか | どこに残るか |
|---|---|---|
| `executedBy` / `onBehalfOf` | §5 の判断による (下記) | v1 projection |
| `chat.captureWindowStart/End` / `evidenceScope` / `selectionReason` | §3.2 の 2 理由 | v1 projection |
| `targetFolderId` | **v2 に home を作らないのではなく、別の問題である** (§3.4) | — |
| `chat.participants` | §6 の判断による | v1 projection |

### 3.4 `targetFolderId` は「置き場所が無い」ではない — **Process 属性の供給が断たれる**

§1.5 の表のとおり、`targetFolderId` は今日 **Purview に届いている** —
`PurviewLineageSink.addProcessTypeAttributes` が `nemaki_import_process.folderId`
(**`isOptional=false` の必須属性**) に写している。`sourceArchetype` → `importMode` (必須) と
`sourceSystem:sourceObjectId` → `sourceDescription` も同じ。

**flip で断たれるのは endpoint ではなく、この供給経路である。** `snap` の出所は
`record.legacyEventAttributes()` で、v2 record ではそれが空になるので、
`getOrDefault` が既定を返し **必須属性が `""` と `"external"` と `":"` で埋まる**。

`LineageProcessShape` の javadoc が「the containing folder travels as a **Process attribute**,
not as an endpoint」と置き場所を名指ししている。2 巡目の稿はその文の**後半を落として**
「置き場所が無い」と書いていた。

**これは endpoint attribute では解けない。** Process 属性を v2 record から埋める経路が要る。
本作業の範囲外だが、**flip の前提条件**として記録する (§10)。

---

**flip が来たときにこれらが失われることは、この作業では解消しない。** 解消するには
`LineageEventV2` の被覆範囲そのものを広げる必要があり、それは digest の式 (§5) と
配送先スキーマ (§4) の両方に触る。**P1-1(d) の evidence data model の仕事**として残す。

---

## 4. 配送先スキーマは同一コミットで足す (別作業ではない)

`EndpointKindSchemaAlignmentTest.everyDeclaredAttributeExistsInTheAtlasType` が、allowlist の
全 attribute が `PurviewSchemaPayloadFactory` の Atlas 型に在ることを assert する。
`nemaki_document` に `contentStored` / `contentStateReason` / `contentHashAlgorithm` は無く、
`nemaki_external_asset` に `sourceObjectType` も chat 系も無い。

**したがって allowlist の追加と型定義の追加は同一コミットでなければ赤になる。**
1 巡目の設計はこれを「別作業」と切り離しており、誤りだった。

そして `PurviewLineageSink.typedAssetEntity` は `attrs.putAll(endpoint.attributes())` で
**実際に payload に載せる**。「allowlist に足しただけでは sink には届かない」も誤りだった。

---

## 5. `executedBy` / `onBehalfOf` は (b) では動かさない

3 つの形を検討した。

| | |
|---|---|
| (i) `LineageEventV2` の型付きフィールド | **digest が覆わない** (§2)。journal 上で actor を書き換えても compact constructor は残りから再計算して一致する。**改竄が検出できない** |
| (ii) digest の式に足す | `CREATION_DOMAIN` の入力構成が変わる。独立実装の `core/src/test/resources/lineage/reference_hash.py` も同時に追随が要る |
| (iii) output endpoint の attribute | digest に覆われる。ただし「この文書を誰が取り込んだか」を **CMIS_DOCUMENT の属性**にすると、同じ文書への 2 回目の取込が上書きする — §3.2 と同じ問題 |

**(b) では動かさない。** 理由:

- **roadmap:232 は実行起源の記録を P1-1(e) に置いている。** (b) に引き込む理由が要るが、
  上の 3 案はいずれも (e) が扱う問題 (委譲実行の `executedBy` が admitted-unknown) と
  同じ根に触れる。**(e) でまとめて決める方が、2 度式を動かすより安い**
- (ii) は digest の式を動かす唯一の案で、**動かすなら 1 度にしたい**

**したがって (b) の時点では actor は v1 projection のみ。** flip までに (e) が来ることを
前提とし、来なければ actor は失われる — **この依存関係を明示する**。

---

## 6. `chat.participants` を配送先に常駐させない

**訂正**: 2 巡目の稿は「`AtlasLineageSink` が送らないので配送されていない」と書いたが、
`PurviewLineageSink.publish` は **v1 snapshot を丸ごと Process 属性に写す** (`:66-70`)。
`chat.participants` は**今日すでに Purview へ送信されており**、`nemaki_import_process` が
宣言していないので**到着時に落ちている**だけである (§1.5)。

したがって正しい機構は「endpoint attribute にした瞬間に載る」ではなく、
**§4 の同一コミット規則で Atlas 型に宣言され、落とされずに保存されるようになる**。

journal 側は `lineage.retention.days` (既定 90) で purge されるが、
**カタログ側にその規則は無い**。

> **チャット参加者の個人名が、保持期限を抜けて外部カタログに常駐する。**

`EndpointKindSchemaAlignmentTest` の `FORBIDDEN_ON_ARTIFACTS` は artifact kind にしか
掛からないので、自動では止まらない。

**したがって `chat.participants` は endpoint attribute にしない。** §0 の「配送先に届くことは
保証しない」は**届かないこと**の免責であって、**届いてしまうこと**を検討しない理由にならない。

---

## 7. 刻印の前倒しは (b) では行わない

1 巡目は「`applyChatCapturedAt` を emit 直前に移す」としたが、**刻む先が無い**。

- `nemaki:chatCapturedAt` は独立プロパティではなく **`nemaki:chatContextMetadata` aspect の中**
- その aspect の唯一の production writer は `applyArchetypeMetadata(..., "nemaki:chatContextMetadata", ...)`
  で、**`execute()` が返った後の wrapper に在る**
- emit は `execute()` の中。刻印を emit 直前に移すと **aspect 生成より前**になり、
  `chatAspect == null` 分岐に入る。**create-new のチャット取込 100% で空振り**する
- 既存 javadoc が「It is also written after the aspect exists, because writing before would
  have nowhere to go.」と明記している

**順序を変えるには aspect 付与そのものを `execute()` に引き込む必要がある。** さらに
`execute()` は archetype 非依存の共通本体 (mail / note / record / plain も通る) なので、
チャット固有の刻印を置くには**今存在しないゲート**が要る。

**(b) の範囲を超える。** roadmap §1-3 の 2 点目は **P1-1(d) に送る** — evidence data model が
「どの事実がどの時点で確定するか」を決める作業であり、aspect 付与の位置はその帰結だから。

---

## 8. 撤回した 2 案 (再提案しないため)

| 案 | なぜ成立しないか |
|---|---|
| `targetFolderId` を `CMIS_FOLDER` endpoint で表す | shape が 1:1 に束縛しており fact 構築が例外になる。**しかも差し戻し済みの案** — `LineageProcessShape` の javadoc が「The first version of this table had imports produce a folder … the containing folder travels as a Process attribute, not as an endpoint」と経緯ごと記録している |
| 刻印を emit 直前に移す | 刻む先の aspect がまだ無い (§7)。既存 javadoc が同じことを書いている |

**どちらも「置き場所が在るか」を確かめずに割り当てていた。** コードに反論が書いてあるときは、
まずそれに反論する形で書く。

---

## 9. 受入条件 (負のコントロールつき)

1 巡目の 9 件のうち 4 件が判別しなかったので書き直した。

### 9.0 対応表は**製品側**に置く。テストに写さない

2 巡目の条件 2 は「対応表に無いキーが片方に現れたら落ちる」だったが、これは**定義域の
メンバシップ**しか見ておらず、自分で挙げた負のコントロール (「v1 に足して v2 に足し忘れる」)
を捕まえない — **足し忘れたキーは対応表に在る**からである。

しかも対応表をテストに書くと**製品と二重管理**になる。このセッションで
`static final String` のインライン化により**古い値のままテストが通った**件を既に踏んでいる。

**したがって対応表は製品側の単一の列挙にする。**

```
enum CaptureEvidenceField {
    CONTENT_HASH("contentHash", V2Home.outputAttribute("contentHash")),
    CONTENT_STORED("contentStored", V2Home.outputAttribute("contentStored")),
    SOURCE_OBJECT_TYPE("sourceObjectType", V2Home.inputAttribute("sourceObjectType")),
    EXECUTED_BY("executedBy", V2Home.none("digest が覆わない。P1-1(e)")),
    ...
}
```

- `buildV1Snapshot` は**この列挙を回して**v1 map を作る
- endpoint の attribute も**この列挙を回して**作る
- **`V2Home.none(...)` は理由を必須にする** — 「まだ足していない」と「意図的に置かない」が
  区別できる

これで足し忘れが**構造的に起きない**。テストが確かめるのは列挙の**全域性**と、
両ビルダーが列挙を消費していることになる。

### 9.1 endpoint の組み立てに継ぎ目を作る

条件 2 を書くには、v1 側と endpoint 側を**同じ入力から取り出せる**必要がある。
v1 側には `buildV1Snapshot` という package-private の継ぎ目が在るが、
**endpoint 側は `emitLineageEvent` のラムダ内にインラインで組まれている** (`:99-103`)。

同 javadoc が「`emitLineageEvent` 経由のテストは emitter の解決が要り、未配線だと snapshot
構築のはるか手前で落ちる — それが以前のテストが通りながら何も証明しなかった原因」と
書いている。**この継ぎ目の欠如は既に一度踏んでいる。**

**したがって `buildIngestEndpoints(...)` を `buildV1Snapshot` と同じ形で抽出する。**
これは実装の一部であって、あとで足す整理ではない。

### 9.2 受入条件

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `contentStored` が `unknown` の取込で、output endpoint の `contentStored` が `"unknown"` | `contentHash` の有無から推測する実装にすると落ちる |
| 1b | **`contentHash` が output endpoint に載る** (v1 だけでなく) | 3 引数 factory に戻すと落ちる |
| 2 | 同じ入力に対し、**列挙の各要素について「v1 に在る ⟺ v2 の home に在る」**。`none` の要素は v2 に無いことを確かめる | 片側だけに足すと落ちる。**条件が偽で不在**と**実装が忘れて不在**が分かれる |
| 3 | §3.3 の 4 群が **v1 のみ**に在り、対応表で「v2 に home 無し」と明示されている | v2 にこっそり足すと対応表と食い違って落ちる |
| 4 | allowlist の全 attribute が Atlas 型に在る | 型定義を足さずに allowlist だけ足すと `EndpointKindSchemaAlignmentTest` が落ちる |
| 5 | `chat.participants` と `chat.channelName` が **endpoint attribute に無い** | 足すと落ちる (個人名の常駐を止める) |
| 6 | `captureWindowStart/End` / `evidenceScope` / `selectionReason` が **endpoint attribute に無い** | 足すと落ちる (再取込の上書きを止める) |
| 7 | endpoint を 3 つ以上にすると **fact 構築が例外**になる | shape の検証を外すと落ちる |
| 8 | `contentStateReason` が **`text()` (PRESERVE)** で、companion 列を持たない | `displayText()` にすると exact-list テストが落ちる |
| 9 | `eachKindDeclaresExactlyTheseAttributes` の固定リストが**順序込みで**更新されている | 更新を忘れると落ちる |

**条件 2 が本体。** 対応表は §9.0 のとおり**製品側**に置く。

---

## 10. やらないこと

- **既存 v1 行の移行**
- **`executedBy` / `onBehalfOf` の v2 化** — P1-1(e) (§5)
- **刻印の前倒し** — P1-1(d) (§7)
- **会話範囲 / participants / channelName の v2 化** — P1-1(d) (§3.3)

### roadmap の更新 (実装と同一コミット)

主張は roadmap の**2 箇所**に在る。片方だけ直すと、次に読む人は表の行を読んで
「(b) は未完のまま完了扱いになった」と読む。

| 場所 | 直す内容 |
|---|---|
| `authenticity-roadmap.md:83` (§1-3 の散文) | 「どちらも下表 P1-1(b)」→ 刻印は (d) へ |
| `authenticity-roadmap.md:232` (P1-1 表の行) | 「(b) の v2 表現と `chatCapturedAt` の emit 前倒し」→ 前倒しを (b) から外す |
| 同 (d) の定義文 | 「空コンテンツ・version ごとの hash・メタデータ hash」に**刻印の位置・会話範囲・participants・channelName**を足す。今の定義文はそれらを含まない |
- **Process 属性の v2 供給** — §3.4。flip すると `folderId` / `importMode` /
  `sourceDescription` の**必須属性が既定値で埋まる**。endpoint attribute では解けないので
  **flip の前提条件**として別途扱う
- **v2 write 経路の実装** — Slice 4。したがって本作業の end-to-end 検証は
  **手組みの `LineageEventV2` に対する単体テスト**にとどまる。この限界を条件 2 の
  対応表で補う (対応表は v2 write 経路の有無に依らず成立する)
