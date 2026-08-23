# P1-1(d) — 事実が確定する時点 (モデル本体)

> **この文書の範囲は棚卸し §7 の 1 番目と 2 番目。** 「どの型が証拠か」 / PII / 会話の範囲 /
> aspect 付与の位置 は、**ここが決まってからでないと決められない**ので書かない。
> 分類は [`p1-1d-scope-inventory.md`](p1-1d-scope-inventory.md)。
>
> **初稿は空コンテンツと version ごとの hash を「閉じた」と書いたが、どちらも早かった**
> (§3 D3・D5、外部レビュー)。棚卸しへ差し戻してある。

---

## 0. 問いの立て方

「どのフィールドを持つか」ではない。取込が記録する事実それぞれについて、

1. **いつ真になるか** (成立)
2. **いつ記録されるか** (記載)
3. **その間に何が起こりうるか**

を決める。1 と 2 がずれる箇所が、後から「この記録は何を証明しているのか」と聞かれて
答えられなくなる箇所である。**現在は 1 と 2 の区別が製品のどこにも無い。**

---

## 1. 現在の順序 (コードから起こしたもの、推測なし)

`CanonicalImportServiceImpl.execute()` — [1877](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1877) 以降:

| # | 起こること | 位置 |
|---|---|---|
| 1 | 外部からバイト列を取得し `computeContentHash(contentBytes)` | `:1877` |
| 2 | `createDocument` / `checkOut`+`checkIn` | `:2095` / `:2069`・`:2075` (既定は `version_up_on_content_change`。`:2041` は `always_version_up` 分岐) |
| 3 | `applySourceMetadata` — `nemaki:contentHash` ほかを aspect に書く | `:2105` |
| 4 | `applyRelationship` | `:2113` |
| 5 | 名前を読み戻し **`emitLineageEvent`** | `:2132` |

そのあと **`execute()` が返ってから**、wrapper (`executeChatContextImportInternal`) が:

| # | 起こること | 個数 | 位置 |
|---|---|---|---|
| 6 | `applyArchetypeMetadata` — `chatWorkspaceId` / `chatChannelId` / `chatChannelName` / `chatThreadId` / `chatMessageId` / `chatParticipants` / `chatSelectionReason` / `chatEvidenceScope` | **8** | `:812-822` |
| 7 | `applyChatCapturedAt` — `chatCapturedAt` (刻印は `:1094-1096`) | **1** | `:843` |
| 8 | `applyCaptureWindow` — `chatCaptureWindowStart` / `chatCaptureWindowEnd` | **2** | `:846` |

8 + 1 + 2 = **11**。`Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES` と同じ 11 で、
**証拠プロパティは 11 個とも 6〜8 で書かれる**。

> 初稿は「11 個のうち 10 個」と書いていた。**検算していない数** — `chatFields` 配列は 8 で、
> そこへ `capturedAt` と window 2 個が足されるだけである。(c) の受入条件に流れた「10」と
> 同じ種類の誤りなので、数は**製品の配列から取る** (外部レビュー)。
>
> `nemaki:contentHash` は `nemaki:externalIntegration` 側の別 aspect で、この 11 の
> 内数ではない。書かれるのは 3 (emit の前) である。

### 1.1 装飾のある入口とない入口

**この非対称は chat 固有ではない。** 公開の入口は 5 つあり、うち 4 つが
`execute()` の後も書き続ける:

| 入口 | `execute()` の後に書くか | 後から書かれるもの |
|---|---|---|
| `executeChatContextImport` `:766` | **書く** | `nemaki:chatContextMetadata` (11。**(c) で READONLY**) |
| `executeMailImport` `:196` | **書く** | `applyMessageMetadata` `:314`、生 .eml、添付とその関係 |
| `executeNoteImport` `:439` | **書く** | `applyNoteMetadata` `:509`、添付 `:664` |
| `executeBusinessRecordImport` `:690` | **書く** | `applyArchetypeMetadata` `:740` |
| `execute(callContext, request)` `:1714` | **書かない** | — (装飾なし。emit は既に最後) |

4 つとも `withCaptureOutcome(...Internal(...), captureScope)` の形で、
「wrapper が root scope を持つ」ことがコメントに明記されている
([`:197-201`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L197))。

**したがって順序問題は chat だけの話ではない。** ただし (c) が READONLY にしたのは chat の 11 だけ
なので、**「証拠として保護されている値が emit の後に書かれる」のは今は chat** である。

---

## 2. 事実は 3 種類あり、確定の仕方が違う

現在のモデルは全部を「取込が書いたフィールド」として平らに扱っている。分けると、ずれの
在り処がそのまま出る。

| 種別 | 何か | 確定するのは | 例 |
|---|---|---|---|
| **観測** | 外の世界を見て得た | **見た瞬間**。あとから確かめ直せない | 取得したバイト列とその digest、`sourceObjectId` |
| **決定** | この取込が選んだ | **決めた瞬間**。実行前から決まっている場合もある | `targetFolderId`、`importMode`、`selectionReason`、`evidenceScope`、**各種 skip** |
| **結果** | 書いた結果そうなった | **書き込みが成功した瞬間**。読み戻さないと分からない | `objectId`、version、保管されたコンテンツの有無 |

**この 3 つは失敗の仕方が違う。** 観測は「もう確かめられない」、決定は「後から書き換えられる」、
結果は「本当にそうなったか読まないと分からない」。同じ `attributes` map に混ぜている限り、
読む側はどれがどれか分からない。

> **3 分類に収まらないものが 2 つある** (外部レビュー)。どちらも「主張」であって観測ではない:
>
> - **呼び出し側が申告した値** — `chatChannelId` / `participants` / `captureWindowStart/End` は
>   `request.getMetadata()` から来る。**製品はこれを検証しない**。window は未来でもよいし、
>   対象メッセージを含まなくてもよい (`Instant.parse` するだけ `:862`/`:867`)。
>   我々が観測したのは「呼び出し側がこう言った」であって、会話の事実ではない
> - **各種 skip** — 0 バイト `:1860` / 擬似ファイル `:1874` / dedupe `:1959` /
>   idempotency `:1919` / `files_only` `:624`。「観測に基づいて保管しないと決めた」という
>   **決定**だが、**イベントが出ない** (§3 D6)

---

## 3. ずれの棚卸し

### D1 — イベントは「要求された値」を主張し、object は「実際に載った値」を持つ

**初稿は「emit 時点で証拠は存在しない」と書いた。誤りだった** (外部レビュー)。
`IngestLineageEmitter.buildV1Snapshot` は 11 個のうち **10 個** を emit 時点で v1 snapshot に
書いている ([`:319-334`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestLineageEmitter.java#L319))。
読み元は `request.getMetadata()` で、**wrapper が読むのと同じ map・同じキー**である。
emit 時点で本当に存在しないのは `chatCapturedAt` **1 個だけ**。

**実際のずれはこれである**:

| | イベントが言っていること | object が持っていること |
|---|---|---|
| 出所 | `request.getMetadata()` — **要求された値** | `mergeAspect` が書けた値 — **実際に載った値** |
| ずれる契機 | — | `applyArchetypeMetadata` の失敗は**自分で catch して文字列を返す** ([`IngestMetadataService:156-159`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestMetadataService.java#L156))。wrapper は `:824` でそれを warning に足して**成功を返す** |

**CouchDB の一過性障害 1 回で、「イベントは `chat.channelId` を主張、document に aspect は無い」
が成立する。** 更に、archetype が null の connector を DLQ から再実行すると素の `execute()` に
落ちる ([`IngestDlqController:214`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestDlqController.java#L214))
ので、chat aspect は**一度も付かない**のにイベントは chat の事実を全部載せる。

> **これは順序の問題ではなく、主張の強さの問題である。** イベントは「こう要求された」しか
> 知らないのに、読む側には「こう記録された」と読める。§4 R2・R6 がここに効く。

### D6 — 再取込が証拠を上書きし、**イベントは 1 件も出ない** (最大の穴)

「事実がいつ確定するか」の答えは、現在この 10 個について「**最後に誰かが再取込した時**」
であり、**その時刻はどこにも無い**。

1. `skip_if_same_version` は emit (`:2132`) より前の [`:1959`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1959) で return する。
   idempotency skip (`:1919`/`:1924`/`:1929`) も同じ。**したがって来歴イベントは出ない**
2. ところが chat wrapper は「dedupe skip かつ objectId 有り」のとき**意図的に落ちてくる**
   ([`:797-804`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L797))
3. `applyArchetypeMetadata` (`:822`) と capture-window 書込 (`:850-877`) が走り、
   `mergeAspect` は既存値を**上書きする** ([`IngestMetadataService:147-153`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestMetadataService.java#L147))

**同じ `sourceObjectId` で 2 度目を投げるだけで、chat 証拠 8 個と window 2 個が黙って
書き換わる。** (c) は CMIS 経由を READONLY で塞いだが、**取込は `injectPropertyValue` を
意図的に迂回する**ので効かない。

`applyChatCapturedAt` だけは `createdObject` ガード (`:1041`) と `containsKey` ガード (`:1083`)
で守られている。**11 個のうち 1 個だけが守られている**、という状態である。

> 落ちてくる設計自体は理由がある — 前回失敗した添付の再試行がここに乗っている。
> **止めるべきは「書き換え」であって「再試行」ではない。**

### D2 — `contentHash` は「取得したバイト列」の digest であって「保管された記録」の digest ではない

`computeContentHash` は fetch 直後のバイト列に対して走り ([`:1877`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1877))、
**その同じ配列**が `:1878-1879` で `ContentStreamImpl` に渡る。
`describeCapturedContent` は `computedHash != null` なら**読み戻さずに** `hashed(...)` を返す
([`:969-975`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L969))
— 行内コメントは「この取込が供給しハッシュしたバイト列を保管した」と書いている。

`createDocument` が成功を返したことは、**リポジトリがストリームを受理した**ことは示すが、
**保管されたバイト列が取得したバイト列と一致する**ことは示さない。

`CapturedContent` は既に STORED / NONE / UNKNOWN を honest に分けており、
`CaptureEvidenceField` に主語を名乗るフィールドは**無い** (あるのは
`CONTENT_HASH_ALGORITHM` = アルゴリズム名と `CONTENT_STORED` = 三値だけ)。
**足りないのは digest が「何のバイト列の digest か」を名乗ること**である。

> **1 箇所だけ、名乗りを下げすぎている経路がある** (外部レビュー)。
> `compareContent` は「今取ってきたバイト列の digest が記録済み digest と一致した」を
> 確認した上で `hashToRecord = null` を返す ([`:945-950`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L945))。
> すると `describeCapturedContent` は読み戻し経路に落ち、
> 「supplied もしていないし verify もしていない」と言う。**この分岐に限ってその文は偽で、
> 実際には供給もハッシュも一致確認もしている。** 唯一持っている fixity の事実を捨てている。

### D3 — version ごとの hash — **結論は今のところ真だが、理由が偽だった。閉じない**

初稿は「aspect は document ごとなので古い version は自分の hash を保持する」と書いた。
**aspect は document ごとではない。参照で共有されている** (外部レビュー):

`buildCopyDocument` は [`ContentServiceImpl:1970`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L1970)
で `copy.setAspects(original.getAspects())` — **同じ List、同じ `Aspect` オブジェクト**を渡す。
これを通るのは `checkOut` (`:1620`)、`checkIn` (`:1786`)、`copy` (`:1271`/`:1370`)、
そして **`updateWithoutCheckInOut` (`:1876`)** である。

既定の取込経路で結論が救われているのは、`checkIn` 内部の `cancelCheckOut` が
`getAllVersions` (非キャッシュ) で全 version を読み直して両方のキャッシュ実体を差し替える
**副作用**による。`applySourceMetadata` の aspect 書込 (`:2410`) はその後に走るので、いまは当たらない。

**その保険が無い経路が実在する**: `updateWithoutCheckInOut` は `cancelCheckOut` を通らず、
呼び元は `rest/BulkCheckInResource.java:420`。

**したがって「モデルとして足すものは無い」は成立しない。** 必要なのは invariant である —
**証拠 aspect は version 間で参照共有してはならない**。棚卸しへ差し戻す。

### D4 — 時間軸は 2 本ではなく 4 本

| 時刻 | 出所 | 種別 |
|---|---|---|
| `occurredAt` | emit 時の `Instant.now()` ([`IngestLineageEmitter:93`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestLineageEmitter.java#L93)) | 記載 |
| `intentOpenedAtMs` | request 入口の `System.currentTimeMillis()` (`:1660`) | 記載 |
| `nemaki:chatCapturedAt` | サーバクロック (`:1094-1096`) | 観測 (取込の時刻) |
| `nemaki:externalContextUpdatedAt` | `new GregorianCalendar()` (`:2376`) | 記載 |
| `chatCaptureWindowStart/End` | **呼び出し側の文字列**。`Instant.parse` するだけ (`:862`/`:867`) | **申告** (§2 の但し書き) |

初稿は「2 つの時間軸が名前で区別されずに並んでいる」と書いたが、**4 本ある**。
そして window は**別の軸ではなく未検証の主張**である。

### D5 — 空コンテンツ — **主要経路を見ていなかった。閉じない**

> **2026-08-23 追記: 「取り込まなかった」の記録は実装した。** 0 バイト / 擬似ファイルで
> skip された添付は、**親 pass の outbox 完了 evidence** に `attachmentsNotIngested`
> (fileName + reason) として残る (mail / note 両 wrapper)。document も event も無い skip の
> 唯一の恒久記録である。親が row を開かない再 poll では再記録しない (D6 と同じ洪水防止 —
> 事実は capture 時の row に在る)。**「第 3 の答えに名前が無い」問題のモデル側 (
> `CapturedContent` に NOT_INGESTED を足すか) は未決のまま残る。**

確かめた部分は正しい: `computeContentHash` ([`:1249-1259`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1249))
は 0 バイトに正当な digest を返し、null 配列のときだけ null。
`CapturedContent.none()` の呼出は `:982` の 1 箇所で、`getAttachmentNodeId()` が null/blank のときだけ。

**しかし** `:1856-1862` が、`sourceObjectType == "attachment"` の 0 バイトを
`computeContentHash` (`:1877`) より**前に** `skipped` で返す (外部レビュー)。
document も aspect もイベントも作られない。

| 入力 | 結果 |
|---|---|
| null stream | hash 無し → 読み戻し → `none()` / `unknown()` |
| 0 バイト・**attachment** | **そもそも保管されない** (skip)。イベントも出ない |
| 0 バイト・非 attachment | `hashed(SHA256(""))` + `contentStored=true` |

**製品には「取り込まなかった」という第 3 の答えがあり、モデルはそれを名前で持っていない。**
また、0 バイト本体を保管した直後のイベントは `contentStored=true` を言い、後日の
metadata-only 再取込は読み戻し経路に落ちて `none()` を出しうる — **同じ object について
2 つのイベントが矛盾する**。棚卸しへ差し戻す。

### D7 — 同じ操作の実行主体が、イベントと outbox で食い違う

イベント側は正直に作ってある: `resolveExecutedBy` (`:917-923`) は合成コンテキストの名前を
出さず `"unknown: delegated profile …"` を返し、`resolveOnBehalfOf` (`:926-928`) が
`profile.getCreatedByUserId()` を返す。

ところが同じ取込の outbox 行は `newCaptureScope` (`:1657-1672`) で:

- `executedBy` = `callContext.getUsername()` = **合成された profile 作成者** —
  イベントが避けている「権限を実行者に見せる」混同そのもの
- `onBehalfOf` = **呼び出し側が request metadata に書いた文字列** (イベント側は profile の記録値)

`CaptureIntent` の javadoc は両者を同じ意味だと定義している。**1 つの操作について 2 つの記録が、
同じ名前のフィールドに、別の出所から、矛盾する値を書いている。**

> 実行起源そのものは **(e)** の担当だが、**「同じ名前で違う意味」はモデルの問題**なので
> ここで名指す。(e) が直すときの前提になる。

---

## 4. このモデルが採る規則

### R1 — 記載時刻と成立時刻を混ぜない

来歴イベントは「**いつ書かれたか**」と「**いつ真になったか**」を別に持つ。一致を既定にしない。

### R2 — 主張の強さを名乗る (**D1 の本体**) — **2026-08-22 実装済み**

イベントの各事実は、それが**どこまで確かめられたか**を持つ:

| | 意味 | 例 |
|---|---|---|
| `asserted` | 呼び出し側がそう言った。**我々は確かめていない** | `chatChannelId`、`captureWindowStart/End` |
| `observed` | 我々が見た | 取得したバイト列の digest |
| `applied` | 我々が書き、成功を確認した | `objectId`、書けた aspect |

**現在 v1 snapshot に載っている chat 10 個は全部 `asserted` である。** それを `applied` と
読ませているのが D1 のずれで、**emit を動かさなくても直る**。

### R3 — digest は主語を名乗る — **2026-08-22 実装済み**

`contentDigest` を単独で置かない。名乗れるのは `input` (取得したバイト列) だけで、
`stored` は fixity (P1-2) が入るまで**発行しない**。
ただし `compareContent` の一致確認は `input-matched-recorded` として**名乗れる** (D2 の但し書き)。

> **既存の `ContentState.STORED` とは別物である。** あちらは「保管されているか」の三値で、
> 既に `contentStored` として出荷済み。R3 が禁じるのは「**保管されたバイト列の digest**」を
> 名乗ることであって、`contentStored` ではない。

**入ったもの** (2026-08-22):

- `CapturedContent.DigestSubject` — 値は `input` と `input-matched-recorded` の **2 つだけ**。
  「保管されたバイト列」を意味する値は**存在しない**。足すには読み戻して取り直す経路が
  要る (P1-2)。テストが enum の中身を直接見て、`stored` 相当が足されたら落ちる
- `contentHashSubject` を v1 snapshot と v2 output attribute の**両方**に出す。
  Atlas 型定義・`EndpointKind`・整合テストの 3 箇所を揃えた
- **主語なしで digest が出る経路を無くした** — `digestSubjectValue` は null なら `input` を
  返す。負のコントロール: null を返すようにすると `expected: <input> but was: <null>`
- **配送先カタログに届くのは v2 flip 以降** (2026-08-23 追記、外部レビュー)。v1 sink は
  snapshot を Process 型に載せるが、`nemaki_import_process` は content 系属性を
  **宣言していない** (contentHash 自体も同様) ので到着時に落ちる。SCHEMA_VERSION 16 で
  宣言したのは `nemaki_document` 側で、そこへ書くのは v2 の endpoint attribute 経路。
  **flip までの主語の在処は journal と REST である** — 「カタログに主語が在る」とは
  読まないこと。(b) §3.4 の Process 属性供給と同根
- D2 の但し書きを実装。`compareContent` は一致したことを `matchedRecordedHash` として
  覚え、`describeCapturedContent` が「取得もハッシュも一致確認もした。ただし記録済み
  digest との比較であって保管バイト列との比較ではない」と言う。
  負のコントロール: 捨てるように戻すと 2 件落ちる

### R4 — 3 種別 + 申告を型で分ける — **2026-08-22 実装済み**

`CaptureEvidenceField.Assurance` として、v1 キー・v2 home の隣に置いた。
**名前の規約ではなく型** — 規約は次に足す人が破るが、コンストラクタが要求すれば破れない。

**実装して 4 値になった。** 設計は 3 値 (`asserted` / `observed` / `applied`) だったが、
`sourceSystem` / `sourceArchetype` が収まらない。connector 定義から来るので
呼び出し側の申告ではないし、我々が観測したものでもない。**`configured`** を足した —
「別の当事者が、別の時点で主張した」ことを畳んでしまうと、証拠として誰の主張かが消える。

| | 意味 | 例 |
|---|---|---|
| `asserted` | このリクエストの呼び出し側がそう言った。**何も確かめていない** | chat 10 個、`sourceObjectId` |
| `configured` | この配備自身の設定から来た。**これも未検証** | `sourceSystem`、`onBehalfOf` |
| `observed` | この pass で自分で計算・読み取りした | digest、`contentStored`、`executedBy` |
| `applied` | 書いて、書込が返った | `targetFolderId`、reimport 3 個 |

**wire に出すのは `assuranceAsserted` の 1 キーだけ。** 残り 3 つは「自分でやった」の
度合いで、取り違えても害が小さい。害があるのは**未検証の主張を検証済みと読むこと**である。
イベント側に持たせるのは、5 年後に読む人がその時のビルドを持っていないため —
表はコードで、イベントは記録である。

一覧は**そのイベントが実際に載せたキーだけ**から作る。表の全 ASSERTED を並べると、
呼び出し側がしていない主張を書くことになる。順序は表の宣言順に固定した (2 つのイベントを
diff したとき、起きていない変化が見えないように)。

### R5 — 確定した事実は、記録なしに上書きされない (**D6 の本体**)

> **範囲は chat の 11 個に限る。** 「証拠が記録なしに消える経路」は D6 だけではない —
> (c) §5.2 の `cmis:secondaryObjectTypeIds` はまだ開いているし、同じ
> 「skip 落ち + `mergeAspect`」は mail (`:295`) / note (`:509`) / record (`:725`) にもある。
> ただし (c) が READONLY にしたのは chat の 11 個だけなので、**保護された証拠の
> 取込上書き**としては D6 が残件である (外部レビュー)。他の 3 つは同じ形の穴だが、
> 保護がまだ無いので「確定した事実が壊れる」とはまだ言えない。

再取込が既存の証拠を書き換えるなら、**それは新しい取込であって、イベントが要る**。
選択肢は 2 つ:

| | 内容 |
|---|---|
| **(A) 書き換えない** | dedupe skip 経路では証拠 aspect を**上書きしない** (欠けているものだけ埋める)。添付の再試行は続けられる |
| **(B) 書き換えるならイベントを出す** | skip 経路でも emit する。「skip した」も決定なので記録に値する (§2 の但し書き) |

> **(B) には実装して初めて分かった罠がある。** 「skip したら emit する」を素直に入れると、
> 5 分間隔のポーラが**同じメッセージ 1 件につき 1 日 288 件**のイベントを出す。記録すべき
> ものが埋まる。
>
> **したがって規則は「pass ごと」ではなく「変化と拒否ごと」**である:
>
> | この pass がしたこと | 記録 |
> |---|---|
> | 欠けていた値を埋めた | **出す** |
> | 違う値を求められて拒んだ | **出す** — 呼び出し側がこの記録について別のことを信じている、という重要な事実 |
> | 同じ値を再送された | **出さない**。変化でも拒否でもない |
>
> 同じ理由で**警告も出さない** — 出すと毎回のポーリングが警告まみれになる。
>
> **実装 1 巡目はこの規則を書きながら、比較が効いていなかった** (外部レビュー)。
> aspect のプロパティ値は `CouchContent` を untyped で通り、
> `ContentDaoServiceImpl.normalizeJsonNumber` は**aspects の中へ再帰しない**ので、
> epoch millis で書いた datetime は `Long` / `Double` で戻る。`instanceof Calendar` は
> **決して真にならず**、毎回のポーリングが「違う値」に見えて 288 件/日が出ていた。
> 製品自身の読み側 (`CompileServiceImpl`) が DATETIME を `GregorianCalendar` /
> `String` / `Long` から coerce しているのが傍証である。
>
> **したがってテストの fixture は、DAO が返す形 (数値) で持たせる。** 生きた
> `GregorianCalendar` を置いた fixture は、production で決して真にならない比較を
> 「正しく見せる」。`instanceof Calendar` に戻すと `anIdenticalPassIsNotRecorded` が
> `No interactions wanted` で落ちることを実測した。
>
> **報告するのは「違うと積極的に示せたとき」だけ**にした。読めない値は「呼び出し側が
> 別のことを信じている証拠」ではないし、「読めない=違う」に倒すと別経路で洪水が戻る。
> 保護そのものはどちらでも変わらない — 在る値はこの経路では決して置き換えられない。

**(A) を先に入れ、そのあと (B) を足す。2026-08-22 に両方実装済み。** (A) だけだと「2 度目に何が起きたか」が
依然どこにも無く、(B) だけだと保護されたはずの値が書き換わり続ける。

**順序を守ること。** `execute()` の early return に emit を足すと、**その直後に wrapper が
またイベントの後ろで上書きする** — D1 の形が skip 経路にそのまま残る (外部レビュー)。
skip のイベントは「上書きしないと決めたあと」、つまり **wrapper 側**で出す。
添付の再試行は `objectId` を渡すだけでよく、**aspect の merge とは分けられる**。

### R6 — emit の位置は動かさない

初稿は「(i) 事実を前に出す / (ii) emit を後ろに出す」の二択を立て (ii) を採ったが、
**前提の D1 が偽だったので二択ではない** (外部レビュー)。R2 が主張の強さを名乗れば、
**emit を動かさずに D1 は解ける**。

繰り延べ理由として挙げた 3 つも検算した:

| 初稿の理由 | 判定 |
|---|---|
| `lineageOperationId` の採番位置が動く | **偽に近い**。`:1728` で採番して外へ渡すだけで足りる |
| `ExternalIngestResult` の組み立てが動く | **真**。各 wrapper が `:425`/`:641`/`:760`/`:903` で再構築する |
| outbox の `CAPTURED` 完了位置が動く | **偽**。`withCaptureOutcome` (`:1682`) は既に wrapper 入口 `:201`/`:450`/`:701`/`:777` から呼ばれており、**6〜8 の後**にある |

**加えて、動かすと mail / note で意味が定まらない**: これらは `execute()` を複数回呼ぶ
(message `:285`、生 .eml `:343`、添付ごとに `:391`)。「wrapper の後に 1 回」にすると
message の emit が添付ループ `:366-415` を丸ごと跨ぐ。

> **「刻印を emit の前に移す」も従来どおり再提案しない** ((b) §8 で撤回済み)。

---

## 5. この文書で **決めないこと**

- `stored` digest の発行 (**P1-2** fixity)
- 実行起源そのものの是正 (**(e)**。D7 は名指すだけ)
- どの型が証拠か / PII / 会話の範囲 / aspect 付与の位置 (棚卸し §7 の 3〜6)
- **メタデータ hash** — 棚卸し §7 の 2 番目。D1・D6 の解消を受けて
  **設計済み (2026-08-23)**: [`p1-1d-metadata-hash.md`](p1-1d-metadata-hash.md)。
  載った値を hash する — 要求された値ではない (D1 の帰結)
- InterPARES 逐条 (棚卸し §7 の 7)

---

## 6. 受入条件 (負のコントロールつき)

初稿の AC 1・3・6 は**判別力が無かった** — 1 は通常経路で両者が一致するので「片方で埋める」
実装と区別できず、3 は規則の言い換え、6 はコメントに対する試験だった (外部レビュー)。
**振る舞いで書き直す。**

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | イベントが、自分の載せた事実のうち**未検証のものを名指す** (D1) | **実測**: chat の 1 個を `OBSERVED` に付け替えると `expected: <ASSERTED> but was: <OBSERVED>`、一覧の出力を外すと対応表テストが `[assuranceAsserted]` で落ちる |
| 2 | **同じ `sourceObjectId` の 2 度目**が、既存の証拠 11 個を**書き換えない** (D6 A) | **実測**: `noEventForThisPass` を false に戻すと `expected: <C-CAPTURED> but was: <C-REWRITTEN>` |
| 3 | **値を埋めた/拒んだ** 2 度目が、**イベントを 1 件出す** (D6 B) | **実測**: emit の呼出を外すと `Wanted but not invoked` |
| 3b | **同じ値を再送しただけ**の 2 度目は、**イベントを出さない** — 条件 3 の control | 「skip なら必ず出す」にすると落ちる。5 分ポーリングで 1 日 288 件になる |
| 4 | 欠けていた値の穴埋めは 2 度目でも**動く** — 条件 2 の control | 「skip なら何もしない」にすると落ちる |
| 5 | digest は主語を持ち、**`stored` を名乗る経路が存在しない**。`contentStored` は従来どおり出る | **実測**: 主語を出さなくすると `expected: <input> but was: <null>`。`stored` 相当を enum に足すと `thereIsNoStoredSubject` が落ちる |
| 6 | 内容一致で version を上げなかった取込が、**「確かめていない」と言わない** (D2 但し書き) | **実測**: `matchedRecordedHash` を捨てると 2 件落ちる |
| 7 | 証拠 aspect が **version 間で参照共有されない** (D3) | `buildCopyDocument` の防御コピーを外すと落ちる。**`updateWithoutCheckInOut` 経路で試験する** — `checkIn` 経路は `cancelCheckOut` の副作用で偶然通る |
| 8 | 0 バイト attachment の**skip がイベントに残る** (D5) | skip の early return を戻すと落ちる |
| 9 | outbox の `executedBy` / `onBehalfOf` が、**同じ取込のイベントと一致する** (D7) | どちらかの出所を戻すと落ちる |

---

## 7. 陳腐化したコメント — **2026-08-22 修正済み**

(c) が 11 個を READONLY にした時点で偽になったものが、初稿が挙げた 1 箇所以外にもある
(外部レビュー):

| 位置 | 何が偽か |
|---|---|
| `CanonicalImportServiceImpl:834-836` | 「The property is still READWRITE, so a client with update permission can change it afterwards」 |
| `:838-842` | 「moving the stamp ahead of emission」— (b) §8 で撤回済みの対処を指す |
| `:1086-1088` | 「while the property is READWRITE this also preserves a value a client planted」 |
| `IngestLineageEmitter:316-318` | 「carrying it needs the stamp to move ahead of emission, which is P1-1(b) work」— 同上 |

---

## 8. 次

1. この改訂のレビュー
2. ~~**D6 (R5)**~~ — **2026-08-22 実装済み** (A・B とも)。負のコントロールは 3 つとも実際に落として確認
3. ~~R2・R3・R4~~ — **全て済** (2026-08-22)
4. ~~§7 のコメント修正~~ — **済**
5. 通ってから棚卸し §7 の 2〜6
