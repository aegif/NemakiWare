# ADR — E1 の耐久コミットメント方式と、digest をどこで取るか

2026-09-20。計画 [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md) §8 が
Phase 0 で決めよと書いていた 2 つを、Phase 3 の着手時に決める。
棚卸しは [`evidence-phase0-inventory.md`](evidence-phase0-inventory.md)。

**Supersedes** [`e1-content-state-commitment-adr.md`](e1-content-state-commitment-adr.md)（2026-09-19 の spike 期 ADR。決定は同じ案 B だが、digest の取り方（決定 2）はこちらで初めて決めた）。

---

## 決定 1 — 案 B（耐久 intent 行）を採る

**問い**: content は CouchDB に、台帳は証拠 DB に在り、両者をまたぐ transaction は無い。
bytes を書いてから statement を記録するまでの窓で落ちると、statement の無い content が残る。
この窓は閉じられない。**閉じられないものを「閉じた」と書かないために、何を残すか**を決める。

| 案 | 形 | 採らない理由 |
|---|---|---|
| A: 文書に marker | content を書くのと**同じ revision** に「statement 未記録」の印を置く | **印を消す書き込みが第 2 の窓を作る。** そこで落ちると、印が付いたままの文書が永久に残り、本物の gap と見分けが付かない。「未記録」と「消し損ね」が同じ値になる — このブランチが名前にしている欠陥そのもの |
| **B: 耐久 intent 行**（採用） | 証拠 DB に「この write が始まった」行を**先に**書き、statement を記録してから閉じる | — |

**採る理由**は 3 つ。

1. **開いている行は積極的に観測できる。** 「印が無いこと」は観測ではない（印を置けなかったのか、
   置く必要が無かったのかを区別できない）。開いている行は列挙できる。
2. **記録と解決が同じ store の関心事になる。** 台帳と intent 行が同じ DB に在るので、
   「台帳に入った」と「行を閉じた」の間の窓は 1 つの store の中に閉じる。
3. **冪等キーが自然に手に入る。** intent id がそのまま retry の鍵になる。

### 落ちた場所ごとに何が残るか（`E1LeavesNoSilentGapTest` が測る）

| 落ちた場所 | 残るもの | 読み方 |
|---|---|---|
| 行を開く前 | 何も無い | 正しい（bytes も無い） |
| 開いた後・bytes の前 | 開いた行、bytes 無し | 未解決として列挙される。verifier からは statement 不在 = `NOT_PRESENT` |
| bytes の後・台帳の前 | **bytes と開いた行** | **この設計が存在する理由**。gap が列挙できる |
| 台帳の後・行を閉じる前 | 台帳の entry と開いた行 | `CHAINED_ROW_STILL_OPEN`。entry は在るので「statement が無い」と言ってはならない |
| journal 自体が落ちている | bytes だけ | `UNRECORDED_GAP_UNLISTABLE`。**列挙できない gap**であり、`UNRECORDED_GAP_OPEN` と別の値で返す |

**最後の行が重要**: journal が書けないときに business 操作を失敗させない（計画 §8 の
「失敗を業務の失敗へ誤変換しない」）。代わりに、**その gap は列挙できない**と正直に述べる。
列挙できないことは「gap が無い」ではない。`unresolved()` が空でも
`isActive()` が偽なら何も言えない。

### 主張しないこと

- **二重記録を防ぐとは書かない。** intent 行の `ALREADY_CLOSED` が防ぐのは第 2 の**行**であって、
  第 2 の**entry** ではない。呼び出し側が操作ごと retry すれば statement は 2 度 chain に入る。
  同じ statement が 2 つ在ることは偽ではないが、無料でもない（錠がこの現状を固定している）。
- **未列挙の経路について「記録した」と書かない。** 対象は棚卸しの W1〜W14 +
  `deleteContentStream` の 15 本だけで、錠が棚卸しの表と `WriteKind` の本数を突き合わせる。

---

## 決定 2 — digest は**受け取った bytes** から 1 パスで取る

**問い**: statement の `contentDigest` は「受け取った bytes」か「保存された bytes を読み直したもの」か。

**決定: 受け取った bytes**（書きながら 1 パス）。

| | 受け取った bytes | 読み直した bytes |
|---|---|---|
| I/O | write の 1 パスに乗る | **write ごとに全読み直しが増える** |
| 保存側の破損 | package の payload と**食い違う** → verifier が `FAILED` を出す | 破損後の値を記録するので、**破損したまま整合してしまう** |
| 言えること | 「この版の内容として受け取った bytes はこれ」 | 「読み直した時点で保存されていた bytes はこれ」 |

**破損の扱いが決め手**である。読み直しで記録すると、保存側が壊れた bytes を
「正しい」として台帳に焼き付ける。受け取り側で記録しておけば、後で package を作ったときに
payload と statement が食い違い、profile の `CONTENT_BINDING` が `FAILED` を出す —
**検出できる形で残る**。

保存側の drift を見るのは別の仕事で、それは既に `FixityVerifier` が持っている
（`FIXITY_RESULT` として台帳に入る）。2 つの主張を 1 つの値に畳まない。

### 主張しないこと

- 「保存された bytes がこれである」とは書かない。statement が言うのは**受け取った**ほうである。
- W7（追記）は 1 呼び出しごとに 1 statement で、**どれが最終かは書かない**
  （製品が `isLastChunk` を使っていないので知らない）。
