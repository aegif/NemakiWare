# 同じものを二度取る往復 — 棚卸し

2026-08-14。T3 (索引が添付を 2 回読む) を直したあと、「もう持っている値を取り直している」
同型が他にもあるかを調べた記録。

**分類を混ぜると判断を誤る**ので、性質ごとに分けてある。

| 区分 | 意味 |
|---|---|
| **T3 型** | 既にバイト列を持っている。二度目は一貫性を足さない。T3 と同じ形で切れる |
| **検証読み** | 「CouchDB を権威にする」ための読み直し。盲目的に消すと、CouchDB が持っていない値を書いたり削除済みを索引したりする |
| **意図的** | F3 の「メタデータ先・本体は変換できるときだけ」など、残すべきもの |

**別の文書への追加 GET は数えていない** (子の一覧 → 添付ノード、Solr の `_version_` →
CouchDB の content など)。

---

## 対応済み

| ID | 何を | 状態 |
|---|---|---|
| **T3** | 索引が添付を 2 回読む (`extractTextContent` + `getContentLength`) | ✅ `SolrUtil.readAttachment` に統合。CouchDB 読み 11,360 → 10,045 回 |
| **C1** | パス解決の `childByName` が `include_docs` で二通目を取り、両方捨てて `getContent` で三通目 | ✅ `include_docs=false` + 値を読む |
| **C2** | 同 fallback (`children` 経由) | ✅ 同上。名前フィルタは `row.getDoc()` ではなく**値**を読む |
| **C3** | `queryView(..., Class)` が常に `includeDocs(true)` | ✅ 値を読む。値が文書でなければ id で読み直す |
| **B2** | `getAttachment` が本体無しを返すと `setStream` が走る = **読み取り経路の書き込み** | ✅ 呼び出しを外した |
| **C4** | 削除経路 **とアーカイブ書き込み**が `getAttachmentRef` の直後に `getAttachmentActualSize` を優先 | ✅ 優先順を逆にし、実装を 1 本に統合した。**手元の値の方が正しい**ことを実測 (下記) |
| **B1** (一部) | `replacePwc` が `getAttachment` で**古い本体を落として捨て**、次の行で上書き | ✅ `getAttachmentRef` に変更。`updateAttachment` は本体を使わない |
| — | `getChildren` の `include_docs` 二重送信 | ✅ 以前に対応済み (50 子で 40ms/93KB → 5ms/49KB) |

### C3 の監査結果 (実際の design document で分類、2026-08-14)

`Class` overload は **`doc` を emit する view にしか使われていない**ことを確認した上で変更した。

- **48 view が `emit(..., doc)`** — 値が文書そのもの
- **5 view がスカラーを emit** — `childrenNames` (`doc.name`) /
  `documentsByExpirationDate` (`doc._id`) / `documentsByLastModification` (`doc._id`) /
  `dupLatestVersion` (`1`) / `dupVersionSeries` (`1`)
- **この 5 つはいずれも `Class` overload を通らない**
  (`dupLatestVersion` / `dupVersionSeries` は Java から未使用)

将来この overload に別の形を emit する view が来ても、`documentMapFromRow` が id で
読み直すので**静かに間違った答えを返すのではなく、旧来のコストに戻るだけ**。
判別子は `_id` ではなく **`_rev`** — projection (`{name: doc.name}` など) も Map なので、
`_id` を補ってしまうと文書に見えてフォールバックが効かなくなる (Codex 指摘)。
実機で全 view の値が `_id` と `_rev` の両方を持つことを確認済み。

**なお `Class` overload に届く view は 20 種類**で、48 は design document 中の
全文書 view の数。当初この 2 つを混同して書いていた (Codex 指摘)。

**フォールバックのテスト範囲は半分**。`aProjectionFallsBackToAReadById` が
「projection を文書として扱わない」ことは固定している (`_rev` の判別を外すと落ちる)。
**その後の id 読み直しが正しい文書を返すことは未固定** — モックに返す文書が無いため。
届く 20 view はいずれも `doc` を emit するので後半は誰も踏まない。
**別の形を emit する view をこの overload に載せるときに、そこまで書くこと。**
同じ注記を `documentMapFromRow` の javadoc にも置いた。

### C4 の実測 (2026-08-14、gzip 保存の 1.3MB PDF)

指摘は正しかったが、**理由は「冗長だから」ではなく「手元の値の方が正しいから」**だった。

| | 値 |
|---|---|
| `_attachments.content.length` (2 通目の GET が見る値) | **1,291,901** (圧縮後) |
| `attachment.getLength()` (`getAttachmentRef` が返す値) | **1,337,959** |
| 実際に落としたバイト数 | **1,337,959** |
| 同じ文書の Solr `content_length` | **1,337,959** |

`getAttachmentActualSize` は `attEncodingInfo` 付きで**同じ文書をもう一度 GET** し、
gzip では `length` が圧縮後なので**本体を全部落としてバイト数を数える** — 削除する直前に。
`getAttachmentRef` の値は `CouchAttachmentNode` がアップロード時の非圧縮長を保持しているので
既に正しい。よって**優先順を逆にした**: 手元の値が使えるならそれ、使えないとき
(length フィールドが無い旧データ) だけ高価な経路。

**レビューが挙げた懸念「gzip アーカイブが圧縮後サイズを記録し得る」は現実だった** —
ただし危険なのは削除する側ではなく、**変更前のコードが圧縮後サイズを優先していた**点。

#### 直し残しが 1 箇所あった (レビュー指摘)

初回の修正は `ContentServiceImpl` の削除 2 経路だけで、**`ArchiveServiceDelegate.createArchive`
に同じ形が残っていた**。しかもそこのコメントは実測と逆で
「Use actual content size from CouchDB (not metadata which may be stale/compressed)」
— 正しかったのは metadata の方。

**テストが捕まえられなかったのは、ヘルパーを直接呼んでいたから**。呼び出し側を戻しても
落ちない形のテストだった。実装を `ArchiveServiceDelegate.lengthForArchive` の 1 本に統合し、
アーカイブ経路は `createArchive` を実際に走らせて**永続化された Archive の
`contentStreamLength`** を見るテストにした (呼び出し側を戻すと
`expected: <1337959> but was: <1291901>` で落ちる)。

**このテストが縛るのは「正の長さのときの観測される振る舞い」であって、
ヘルパーへの構造的な委譲ではない** — 同じ結果を返すインラインなら通る (Codex 指摘)。
**削除側 2 経路を端から端まで走らせるテストは無い**ので、そちらは独立に退行し得る。

**併せて、私が入れた退行を 1 件直した** (Codex 指摘)。`known > 0` だけで判定していたため、
`長さ 0 かつ導出も失敗` のとき旧コードは `0` を記録したのに新コードは `null` を返していた
— **本当に空の添付で長さが失われる**。0 は「空」という実在の長さ、負値は
「アップローダが答えられなかった」という別物なので、区別して扱うようにした。

**併せて訂正**: C4 のコミットメッセージは触った箇所を「deleteContentStream 系」と書いたが誤り。
実際は `deleteDocument` / `deleteDocumentWithVisited` の 2 箇所で、`deleteContentStream` は
長さを取っていない。

---

## 未対応 (レビュー由来。**下記はこちらで未検証**)

以下はレビューからの指摘で、**まだコードで裏を取っていない**。着手前に必ず再確認すること。

### T3 型 — 切れる見込み

| ID | 場所 | 指摘内容 | 素朴に消すと |
|---|---|---|---|

### B1 の続き — 未対応 2 件 (2026-08-14 に調べた結果)

`replacePwc` の古い本体は切ったが、同じ指摘の残り 2 つは**性質が違う**ので分けた。

| ID | 場所 | 何が残っているか | なぜ今やらないか |
|---|---|---|---|
| **B1-a** | `replacePwc` のプレビュー生成 | 書き込んだ直後に `getAttachment` で**同じ本体をもう一度全部落として** byte 配列にする | リクエストの `ContentStream` は `updateAttachment` が消費済みなので、再利用するには**書き込み前にバッファする**必要がある。アップロード経路のメモリ特性が変わる (現在は書き込みがストリーミング)。リリース直前に入れる変更ではない |
| ~~**B1-b**~~ | ~~非バージョン文書の更新~~ | **✅ 直した (下記)。「未確認」は誤りで、コードを読めば決まる話だった** (レビュー指摘) |

**`appendAttachment` (`:3759`) は対象外** — 既存の本体と新規を `SequenceInputStream` で連結するので、
本体の取得は必要。

#### B1-b は往復ではなく**正しさ**の欠陥だった (2026-08-14 に修正)

`updateDocumentWithNewStream` (非バージョン文書の更新) はこうなっていた。

```
an = getAttachment(...)                    // 旧本体を開く
updateAttachment(an, contentStream)        // 新しいバイトを書く
... new ContentStreamImpl(..., an.getInputStream())   // ← 書き込み「前」に開いたストリーム
createPreview(previewCS, ...)
```

**プレビューが旧内容で作られる。** 新しいバイトはリクエストの `ContentStream` にあり、
`updateAttachment` がそれを消費する。`an` のストリームは更新前のもの。
`replacePwc` が fresh に読み直すのは、まさにこれを避けるため。こちらだけ避けていなかった。

**症状**: 更新したのにサムネイルが前の内容のまま。**間違ったプレビューも有効な画像**なので、
壊れているようには見えない。

**「本体が要るから `getAttachmentRef` にできない」は誤り** — 同じ `an` でプレビューまで
済ませようとするからそう見えるだけ (レビュー指摘)。直し方は `replacePwc` と同じ形:

- 書き込みは `getAttachmentRef` (メタデータのみ)
- **書いた後**、プレビューが要るときだけ `getAttachment`

結果として、プレビュー無効なら**旧本体のダウンロードが丸ごと消える**。有効ならダウンロードは
1 回のままで、中身が新しくなる。

テストは**順序**を縛る (`InOrder`) — 「`getAttachment` を呼ぶこと」では意味が無い
(壊れた版も呼んでいた、ただ早く)。プレビュー無効時に本体を落とさないことも別途固定した。

### 検証読み (T2 型) — 消すと壊れ得る

| ID | 場所 | 建前 | 実際に冗長な部分 | 判断 |
|---|---|---|---|---|
| **T2** | `ContentIncarnation.resolve` (索引の文書ごと) | CouchDB が持っていない UUID を刻まない / 走査と索引の間の削除を検出 | `Content` が既に UUID を持つとき、GET は同じ値を返す。**索引フェーズの約 24%** | `Content.getContentIncarnation()` が非 null のときだけ GET を省く案。**フェンスの正しさの入力なので設計判断が要る** |
| **V2** | `createAttachmentAtomic` / `copyAttachmentAtomic` | 作成直後の一貫読み | `create()` が既に id と rev を返す。検証は「在るか」を聞く全文書 GET | `create()` の失敗を失敗として扱うなら落とせる |
| **V3** | `setStream` / `createAttachment` STAGE 2 | binary 添付前に現在の `_rev` が要る | STAGE 1 の結果が既に rev を持つ。捨てているから GET している | STAGE 1 の rev を渡す。GET は fallback に |
| **V4** | `appendAttachment` | update 後の新しい change token | content の GET が 3 回 | `update()` の戻りを使う。`deleteContentStream` は同じ TCK 修正を済ませている |
| **V5** | `CompileService.getAttachmentWithRetry` | 作成直後の `_rev` 整合 | 既に 2 回リトライする `getAttachmentRef` をさらに 5 回包む | リトライ地点を 1 つに |

### 残すもの

| 形 | 理由 |
|---|---|
| MCP / Rendition / `createPreviewAtomic` の `getAttachmentRef` → `getAttachment` | **F3**。多くの MIME は本体の前に弾かれる。逆順にすると再ダウンロードと接続リークが戻る |
| RAG の `getMimeType` → `extractText` | 同じゲート。成功時だけメタデータ GET 1 回を払い、拒否時は本体を開かない |
| compile 時の `getChildren` → 文書ごとの `getAttachmentRef` | **別の文書**。CMIS の `contentStreamLength` は添付ノード側にある |
| 索引の文書ごとの Solr realtime GET (C8) | 保存済み `_version_` と incarnation が要る。省くと folder reindex が ACL-epoch フェンスを剥がす |

---

## 着手順 (レビュー推奨)

**C1 → B2 → C3 → C4 → B1 (一部)** の順で実施済み。残りは上の「未対応」から、
**V3 / V4** の順が素直 (V2 / V5 は判断が要る、B1-a はメモリ特性、T2 は設計判断)。
