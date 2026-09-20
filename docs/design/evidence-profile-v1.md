# 証拠 profile v1 — 第三者が verifier を書くための仕様

**状態**: 計画 Phase 2 の第 1 稿。**P0 と P1 の一部だけ**を規定する（下の「この文書が規定しないもの」を先に読むこと）。

この文書の読者は、**NemakiWare のコードを見ずに** package を検証するプログラムを書く人である。
したがって「製品がそうしている」ではなく、**バイト列として何をどう計算するか**だけを書く。
書いてあるアルゴリズムは `core/src/test/resources/evidence/profile-v1-vectors.json` の
ベクタで固定され、`core/src/test/resources/evidence/reference_verify.py` が
**Java を書き写さずにこの文書から**実装して同じ値を出すことを
`EvidenceProfileV1VectorsTest` が毎回実行して確かめる。

---

## 0. この文書が規定しないもの（先に読む）

計画 §6 の profile は P0〜P5 まであるが、**ここで規定するのは P0 と、P1 のうち台帳の再計算部分だけ**。

| 規定しない | 理由 |
|---|---|
| P1 の `record-content-statement.json` / `bundle-manifest.json` 等 | **まだ存在しない**。計画 §7 は `metadata/other/nemaki-evidence/` 配下に 9 エントリ（`.c14n` を含めて 12 ファイル）+ `anchors/` を求めるが、今 package に在るのは `metadata/other/nemaki-evidence.json` 1 本で、それは §7 の列挙のどれでもない |
| P2 の anchor target chain / P3 の RFC 3161 / P4 の OTS / P5 の ERS | 実装はあるが、この稿では仕様を書き切っていない |
| `.c14n` の正準化形式 | 同上。`.c14n` を同梱するという方針だけが計画にある |

**「規定しない」は「検証しなくてよい」ではない。** 上のどれかを検証したと述べる verifier は、
この文書を根拠にできない。

---

## 1. package の配置（実測）

設計文書の文字列ではなく、**commons-ip2 が実際に書いた path**。
`TheSipLayoutIsWhereCommonsIpPutsItTest` が package を作って固定している。

```
nemaki-<repositoryId>-<objectId>/
  METS.xml
  metadata/descriptive/dc.xml
  metadata/preservation/premis.xml
  metadata/other/nemaki-authenticity-report.json
  metadata/other/nemaki-evidence.json
  metadata/other/ers.der                      ← 任意。evidence-record service が配線されているときだけ
  representations/rep1/METS.xml
  representations/rep1/data/<payload>
  schemas/*.xsd
```

- **root ディレクトリ名は `nemaki-<repositoryId>-<objectId>`**。
- 証拠は `metadata/other/nemaki-evidence.json`。**ディレクトリではなくファイル**。
- `ers.der` は**あるときだけ在る**。無いことは欠陥ではない。
- ZIP エントリの**順序に意味は無い**。集合として扱うこと。

---

## 2. 16 進表記

すべてのダイジェストは **SHA-256 の小文字 16 進 64 文字**。
以下 `hex(x)` と書いたら常にこれを指す。

---

## 3. 正準エンコーディング（`entryHash` / `checkpointHash` の入力）

台帳の hash は **JSON の上ではなく、型付きバイト列の上**で計算する。
JSON の key 順・空白・数値表記に依存しない。

### 3.1 型タグ

| タグ | 値 | 符号化 |
|---|---|---|
| NULL | `0x00` | タグのみ |
| STRING | `0x01` | タグ ‖ **長さ (int32, big-endian)** ‖ UTF-8 バイト列 |
| LONG | `0x02` | タグ ‖ **値 (int64, big-endian, 2 の補数)** |
| LIST | `0x03` | タグ ‖ **要素数 (int32, big-endian)** ‖ 各要素の符号化 |
| MAP | `0x04` | タグ ‖ **要素数 (int32, big-endian)** ‖ 各 (key, value) の符号化 |
| BOOL | `0x05` | タグ ‖ `0x01` または `0x00` |

- **`null` と空文字列は別**。`""` は `0x01` + 長さ 0。
- **MAP の key は UTF-8 バイト列の符号なし昇順**に並べる。
- `hash(parts...)` は、**引数列を 1 つの LIST として**符号化し、その SHA-256 を取る。
  つまり `hash("a","bc")` と `hash("ab","c")` は**別の値**（長さ前置があるため）。

### 3.2 定義

```
canonical(parts) = LIST(parts)          -- 3.1 の規則で符号化
hash(parts)      = hex(SHA-256(canonical(parts)))
```

---

## 4. 台帳エントリ

```
entryHash = hash(
    "LEDGER_ENTRY_V1",      -- STRING、固定
    domain,                 -- STRING
    sequence,               -- LONG
    subjectKind,            -- STRING（enum 名。null なら NULL）
    subjectId,              -- STRING または NULL
    payloadDigest,          -- STRING または NULL
    occurredAt,             -- STRING または NULL
    prevEntryHash)          -- STRING または NULL
```

- **`sequence` が入力に入っている**。入っていなければ、2 つのエントリを入れ替えても
  すべての hash が通り、chain は「集合」だけを固定して順序を固定しないことになる。
- `subjectKind` は enum の**名前**（例 `CAPTURE_COMPLETED`）。序数ではない。

### 検証

1. package 内の entry の各 field から上式で再計算する。
2. 記録された `entryHash` と一致しなければ **FAIL**。
3. `prevEntryHash` が直前の entry の `entryHash` と一致しなければ **FAIL**。
4. **どれか 1 つでも field が package に無ければ `INDETERMINATE`**（再計算していないものを
   PASS と言ってはならない）。

---

## 5. checkpoint

```
checkpointHash = hash(
    "LEDGER_CHECKPOINT_V1", -- STRING、固定
    domain,                 -- STRING
    fromSequence,           -- LONG
    toSequence,             -- LONG
    merkleRoot,             -- STRING
    prevCheckpointHash,     -- STRING または NULL
    createdAt)              -- STRING または NULL
```

`toSequence < fromSequence` は**不正**。`merkleRoot` が空の checkpoint は**不正**
（何にも commit していない checkpoint は作ってはならない）。

---

## 6. Merkle 木と inclusion proof

**RFC 6962 ではない。** 葉と節の区別（domain separation）は RFC 6962 と同じ考え方だが、
**節は生バイトではなく 16 進文字列を連結する**。RFC 6962 の実装をそのまま持ってくると
一致しない。

```
hashLeaf(v)      = hex(SHA-256( 0x00 ‖ UTF-8(v) ))        -- v が null なら空文字列として
hashNode(l, r)   = hex(SHA-256( 0x01 ‖ UTF-8(l ‖ r) ))    -- l, r は 16 進文字列
```

木の作り方:

1. 葉の列 `L` は `hashLeaf(entryHash)` を順に並べたもの。
2. 段ごとに左から 2 つずつ `hashNode` で畳む。**奇数個のときは最後の 1 つをそのまま次段へ上げる**
   （複製しない）。
3. 1 つになったら、それが `merkleRoot`。

inclusion proof の検証:

```
current = hashLeaf(entryHash)
各 step (siblingHash, siblingIsLeft) について:
    current = siblingIsLeft ? hashNode(siblingHash, current)
                            : hashNode(current, siblingHash)
current == merkleRoot なら PASS
```

- **proof の step が 0 個で root と一致するのは、葉が 1 つのときだけ**。
  step が無いのに一致しない場合は **FAIL**（`INDETERMINATE` ではない — 計算はできた）。
- proof が**無い**場合は `INDETERMINATE`。「proof が無い」と「proof が合わない」は別の答え。

---

## 7. 判定の合成

| 状況 | verdict |
|---|---|
| 必須 check がすべて PASS | `VERIFIED` |
| いずれかの必須 check が FAIL | `FAILED` |
| いずれかの必須 check が `NOT_PRESENT` / `NOT_CHECKED` | `INDETERMINATE` |
| 何も調べていない | `INDETERMINATE` |

**`NOT_PRESENT` を PASS に昇格させてはならない。** 「無かったので確かめられなかった」は
「確かめて問題が無かった」ではない。

---

## 8. ベクタと独立実装

- `core/src/test/resources/evidence/profile-v1-vectors.json` — 入力と期待値。
- `core/src/test/resources/evidence/reference_verify.py` — **この文書だけから**書いた実装。

```
python3 core/src/test/resources/evidence/reference_verify.py
```

は、不一致があれば非 0 で終了する。`EvidenceProfileV1VectorsTest` は
**Java の計算がベクタと一致すること**と、**その Python を実際に実行して一致すること**の両方を測る。
両者が同じ 1 つのベクタファイルを読むので、片方だけを書き換えて辻褄を合わせることはできない。

**この節が「実行している」と書いているのは、実行しているからである。**
lineage 側の先例（`reference_hash.py`）は手で走らせる前提で、走らせなければ何も言わない。
