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

---

## 未対応 (レビュー由来。**下記はこちらで未検証**)

以下はレビューからの指摘で、**まだコードで裏を取っていない**。着手前に必ず再確認すること。

### T3 型 — 切れる見込み

| ID | 場所 | 指摘内容 | 素朴に消すと |
|---|---|---|---|
| **C4** | バージョン削除 / アーカイブ | `getAttachmentRef` の直後に `getAttachmentActualSize`。非 gzip では同じ文書の GET がもう 1 回、gzip では本体を全部読んでバイト数を数える。`convertRef` は既に `_attachments` とアップロード時の非圧縮長を持っている | gzip アーカイブが圧縮後サイズを記録し得る |
| **B1** | `replacePwc` / `setContentStream` | 古い本体を開いて読まず、`updateAttachment` が `_rev` のためにまた GET し、プレビューのために今書いた本体をもう一度落とす | プレビューはリクエストの `ContentStream` で足りるはず |

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

**C1 → B2 → C3** の順で実施済み。残りは上の「未対応」から、
**C4 → B1 → V3 / V4** の順が素直 (V2 / V5 は判断が要る、T2 は設計判断)。
