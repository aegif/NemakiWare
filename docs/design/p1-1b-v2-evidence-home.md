# P1-1(b) — 取込 snapshot に v2 の居場所を与える

対象: [`authenticity-roadmap.md`](authenticity-roadmap.md) §1-3 が P1-1(b) として残した 2 件。

1. 取込 snapshot は **v1 projection 限定**で、`LineageFact` の設計上 v2 に home が無い。
   **v2 write flip で失われる**
2. `nemaki:chatCapturedAt` は**オブジェクトに刻まれるが snapshot には入らない** — 刻印が
   emit の**後**に走るため

---

## 0. 何を主張し、何を主張しないか

**主張する**: 取込が記録する証拠が、v1 と v2 の**どちらのスキーマでも同じ事実を運ぶ**。
v2 write flip が来ても、`contentStored` / `contentHash` / 会話文脈 / 取込主体 / 刻印時刻が
消えない。

**主張しない**:

- **これは真正性の証明ではない。** 事実が運ばれることと、その事実が正しいことは別である
- **既存の v1 行は移行しない。** flip 以前に書かれた行は v1 のまま残り、v2 の形にはならない。
  読み側が両方を読む必要がある (Slice 1b が既にそうしている)
- **配送先 (Purview / Atlas) に届くことは保証しない。** 発行は従来どおり fail-open で、
  ここで増えるのは journal に残る事実であって、外部カタログの内容ではない

---

## 1. 現状の事実 (コードで確認)

| | |
|---|---|
| v1 の event-level `snapshotAttributes` | `LineageFact.LegacyV1Projection` が運ぶ。取込は `buildV1Snapshot` で 20 前後のキーを詰めている |
| v2 の `LineageEventV2` | **`snapshotAttributes` を持たない**。javadoc が明示的に「§2 の endpoint-local attributes が置き換える」と書いている |
| v2 で属性を運べるもの | **`LineageEndpoint.attributes` だけ**。`EndpointKind` の allowlist で型ごとに宣言され、宣言外は**到着時に落ちる** |
| 取込が作る endpoint | input = `externalAsset(repositoryId, sourceUri, sourceSystem)`、output = `document(repositoryId, objectId, documentName)` |
| `CMIS_DOCUMENT` の allowlist | `name` / `versionLabel` / `folderPath` / `versionSeriesId` / `mimeType` / `contentLength` / `versionObjectId` / `changeToken` / **`contentHash`** |
| `EXTERNAL_ASSET` の allowlist | `sourceSystem` / `externalStableKey` / `externalPath` / `tenantId` / `sourceRevision` / `sourceModifiedAt` / **`sourceContentHash`** / `sourceContentLength` |

**`contentHash` は既に v2 の home を持っている。** 足りないのはそれ以外である。

---

## 2. 事実を 3 つに分ける

`buildV1Snapshot` の中身は、v2 のどこに属するかで 3 群に割れる。

### 群 1 — output endpoint (`CMIS_DOCUMENT`) に属する

**リポジトリが今どうなっているか**の事実。

| snapshot キー | v2 の attribute | allowlist |
|---|---|---|
| `contentHash` | `contentHash` | **既に在る** |
| `contentHashAlgorithm` | `contentHashAlgorithm` | 足す |
| `contentStored` | `contentStored` | 足す |
| `contentHashUnavailable` | `contentStateReason` | 足す |

**`contentStored` を落とさない。** 三値 (`true` / `false` / `unknown`) は
「判定できない」を「保存していない」に潰さないために在る。`contentHash` の有無から
推測させると、**digest が無い = 保存していない**と読まれ、その推測は `UNKNOWN` の場合に偽になる。

### 群 2 — input endpoint (`EXTERNAL_ASSET`) に属する

**外部側で何を指していたか**の事実。

| snapshot キー | v2 の attribute | allowlist |
|---|---|---|
| `sourceSystem` | `sourceSystem` | **既に在る (必須)** |
| `sourceObjectId` | `externalStableKey` に含まれる | **既に在る** |
| `sourceObjectType` | `sourceObjectType` | 足す |

### 群 3 — endpoint に属さない (**設計上の穴**)

**操作そのもの**の事実で、v2 には運ぶ場所が無い。

- `executedBy` / `onBehalfOf` — 誰が実行したか
- `chat.*` (10 キー) — どの会話のどの範囲を根拠にしたか
- `targetFolderId` — どこへ入れたか
- `nemaki:chatCapturedAt` — いつ保管を開始したか (現状は snapshot にすら無い)

---

## 3. 群 3 をどうするか — 3 案と選択

### 案 A: `LineageEventV2` に属性 map を足す

**採らない。** v2 の javadoc は event-level の属性袋を**意図的に外した**と書いている
(「§2 の endpoint-local attributes が置き換える」)。戻すと、v2 が v1 の緩さを引き継ぐ。
allowlist による型検査も効かなくなる。

### 案 B: 操作を表す endpoint kind を新設する

`IMPORT_ARTIFACT` は既に `Identity.OPERATION_ID` を持ち、操作ごとに 1 つの entity になる。
同じ形で **`INGEST_CAPTURE`** を作り、input endpoint として並べる。

**採らない (単独では)。** endpoint は「lineage が流れる先」であって、**実行主体は endpoint
ではない**。actor を endpoint にすると、カタログ上「取込元」として現れる。

### 案 C: 事実の性質で割る — **採用**

| 事実 | 置き場所 | 理由 |
|---|---|---|
| `chat.*` / `sourceObjectType` | **input endpoint の attribute** | 「どの会話のどの範囲か」は**取込元の性質**であり、endpoint の記述として自然。`EXTERNAL_ASSET` の allowlist に会話文脈を足す |
| `targetFolderId` | **既存の output/`CMIS_FOLDER` endpoint** で表す | フォルダは既に endpoint kind を持つ。属性ではなく**関係**で表すのが v2 の grain |
| `executedBy` / `onBehalfOf` | **`LineageEventV2` の型付きフィールド** | actor は endpoint ではない。属性袋ではなく**名前の付いた 2 フィールド**なら、v2 が v1 の緩さを引き継ぐことにはならない |
| `chatCapturedAt` | **output endpoint の attribute** (刻印を emit の前に移す) | 刻印は**この取込が作ったオブジェクト**に打つので、object の性質である |

**actor だけ型付きフィールドにするのは、属性袋を戻すことではない。** 袋は「何でも入る」から
検査できないのであって、`executedBy` / `onBehalfOf` は**全イベントに共通する 2 つの問い**である。
A.1 が要求する「誰が」に、スキーマ上の home を与えることになる。

---

## 4. 刻印を emit の前に移す

現状: `execute()` が emit → wrapper が返った後に `applyChatCapturedAt`。

**変更**: 刻印を `execute()` の中、emit の**直前**に移す。

守らなければならない制約 (roadmap §1-3 が明記):

- **この取込が作ったオブジェクトに限る。** dedupe skip を含む毎回の取込で走るので、既に在る
  オブジェクトに「今」を刻むと、**何年も前から保管しているものが今日から保管開始に見える**
- **`cmis:creationDate` は答えではない。** 移行・アーカイブ復元で保存され、後の版は自分の
  作成時刻を持つ
- **既に値がある場合は上書きしない**
- **分からないものは記録しない。** 既存オブジェクトの保管開始を復元するには来歴イベントを
  読む必要があり、それは P1-1(d)

`createdObject` は `execute()` の中で既知なので、この制約は移動後も守れる。

**capture 境界との関係**: 刻印は追跡対象の変更なので、`CaptureScope` に
`applyChatCapturedAt` として記録する (現在 wrapper 側で記録しているものが移動する)。
**規則 1 は破らない** — 刻印は文書作成の後なので、intent は既に開いている。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `contentStored` が `unknown` の取込で、v2 endpoint の `contentStored` が `"unknown"` である | `contentHash` の有無から推測する実装に戻すと落ちる |
| 2 | v1 snapshot と v2 endpoint attributes が**同じ事実**を運ぶ (キー名は違ってよい) | 片方にだけ足すと落ちる |
| 3 | allowlist に無い attribute を渡すと**拒否される** (落ちるのではなく) | allowlist を素通しにすると落ちる |
| 4 | `executedBy` が v2 event の型付きフィールドとして読める | v1 projection からしか読めない実装だと落ちる |
| 5 | 認証コンテキストの無い取込で `executedBy` が「実行主体なし」と**明示**される (空でも null でもなく) | 空文字にすると落ちる |
| 6 | **この取込が作ったオブジェクト**にだけ `chatCapturedAt` が刻まれる | `createdObject` を見ない実装に戻すと落ちる |
| 7 | 既に `chatCapturedAt` がある object を再取込しても**上書きされない** | 無条件に刻むと落ちる |
| 8 | 刻印が emit の**前**に走り、snapshot と v2 endpoint の両方に載る | 順序を戻すと落ちる |
| 9 | 刻印が失敗したら `CaptureScope` に記録され、`CAPTURED` にならない | 記録を外すと落ちる |

---

## 7. 実装前にコードで確かめた 2 つの事実 (設計を左右する)

### 7.1 endpoint の attribute は **digest に入るが identity には入らない**

- `LineageEventDigest.endpointRecord` は `attributes` を **`creationPayloadDigest` に含める**
  (`:109`)。javadoc も「inputs と outputs を attributes 込みで」と書いている
- `LineageIdentity.processKey` は **attributes を見ない** — endpoint の qualified name だけ

**この分割は本件にとって都合がよい。** attribute を足しても

- `processKey` / `deliveryId` は**変わらない** → 同一性が壊れず、golden vector も動かない
- `creationPayloadDigest` は**変わる** → 証拠が digest に**覆われる**

### 7.2 したがって §3 案 C の actor の置き場所は**再考が要る**

`LineageEventV2` に型付きフィールドを足しても、**`creationPayloadDigest` はそれを含まない**
(digest は endpoint と chunk 座標からしか作られない)。つまり

> actor を event のフィールドにすると、**digest に覆われない証拠**になる。

証拠として弱い。取り得る形は 3 つ:

| | |
|---|---|
| (i) digest の式に actor を足す | golden vector が動く。既存イベントの digest 検証が壊れる |
| (ii) actor を **output endpoint の attribute** にする | digest に覆われる。`CMIS_DOCUMENT` に `capturedBy` / `capturedOnBehalfOf` を足す。「この文書を誰が取り込んだか」は文書の性質と読める |
| (iii) 覆われないことを承知で event フィールドにする | 「digest はこれを保証しない」と明記が要る |

**現時点の傾向は (ii)**。ただし「actor は endpoint ではない」という §3 案 B の理由と
衝突するので、レビューの結論を待って決める。**この矛盾を残したまま実装しない。**

---

## 6. やらないこと

- **既存 v1 行の移行**。flip 以前の行は v1 のまま。読み側が両方読む
- **配送先スキーマの変更**。Atlas / Purview の type 定義に新しい attribute を足すかは別作業
  (allowlist の javadoc が「型に無い attribute は到着時に落ちる」と書いており、
  **allowlist に足しただけでは sink には届かない**)
- **`chatCapturedAt` を既存オブジェクトに遡って刻むこと**。P1-1(d)
