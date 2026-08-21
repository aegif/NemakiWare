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

**`contentHash` は既に v2 の home を持っている。** 足りないのはそれ以外。

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
| `contentHashUnavailable` | `contentStateReason` | 追加。散文なので `displayText` + companion が要る (§1 最終行) |

**`contentStored` を推測させない。** `contentHash` の有無からは `UNKNOWN` が復元できず、
「digest が無い = 保存していない」と読まれる。

### 3.2 input endpoint (`EXTERNAL_ASSET`) に足す — **会話の同一性だけ**

| snapshot キー | v2 attribute |
|---|---|
| `sourceObjectType` | `sourceObjectType` |
| `chat.workspaceId` / `channelId` / `channelName` / `threadId` / `messageId` | `chatWorkspaceId` ほか |

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
| `targetFolderId` | endpoint を増やせない (§3.0)。`nemaki_import_process.folderId` は型定義には在るが、**`AtlasLineageSink` は素の `Process` を送るので配送されない** — 関係でも属性でも今は置き場所が無い | v1 projection |
| `chat.participants` | §6 の判断による | v1 projection |

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

現状 `chat.*` は v1 `snapshotAttributes` に在り、`AtlasLineageSink.buildAtlasPayload` は
qualifiedName / name / description / inputs / outputs しか送らないので**配送されていない**。

endpoint attribute にした瞬間に `PurviewLineageSink` が payload に載せる。journal 側は
`lineage.retention.days` (既定 90) で purge されるが、**カタログ側にその規則は無い**。

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

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `contentStored` が `unknown` の取込で、output endpoint の `contentStored` が `"unknown"` | `contentHash` の有無から推測する実装にすると落ちる |
| 2 | **`buildV1Snapshot` のキー集合と endpoint attributes の間の対応表**を固定し、対応表に無いキーが**どちらか片方に現れたら落ちる** | v1 に足して v2 に足し忘れる / その逆、どちらでも落ちる |
| 3 | §3.3 の 4 群が **v1 のみ**に在り、対応表で「v2 に home 無し」と明示されている | v2 にこっそり足すと対応表と食い違って落ちる |
| 4 | allowlist の全 attribute が Atlas 型に在る | 型定義を足さずに allowlist だけ足すと `EndpointKindSchemaAlignmentTest` が落ちる |
| 5 | `chat.participants` が **endpoint attribute に無い** | 足すと落ちる (個人名の常駐を止める) |
| 6 | `captureWindowStart/End` / `evidenceScope` / `selectionReason` が **endpoint attribute に無い** | 足すと落ちる (再取込の上書きを止める) |
| 7 | endpoint を 3 つ以上にすると **fact 構築が例外**になる | shape の検証を外すと落ちる |
| 8 | 散文 attribute (`contentStateReason`) に **companion (`…OriginalSha256`) が在る** | companion を外すと enum のクラス初期化で落ちる |

**条件 2 が本体。** 「両方に何か入っている」では通ってしまうので、**全単射の対応表**を
テスト側に置き、両方向の欠落を捕まえる。

---

## 10. やらないこと

- **既存 v1 行の移行**
- **`executedBy` / `onBehalfOf` の v2 化** — P1-1(e) (§5)
- **刻印の前倒し** — P1-1(d) (§7)
- **`targetFolderId` / 会話範囲 / participants の v2 化** — P1-1(d) (§3.3)
- **v2 write 経路の実装** — Slice 4。したがって本作業の end-to-end 検証は
  **手組みの `LineageEventV2` に対する単体テスト**にとどまる。この限界を条件 2 の
  対応表で補う (対応表は v2 write 経路の有無に依らず成立する)
