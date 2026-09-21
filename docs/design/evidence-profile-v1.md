# 証拠 profile v1 — 第三者が verifier を書くための仕様

**状態**: 計画 Phase 2。**P0〜P5 の検証規則を規定する**。
規定しないものは §0 に列挙する（先に読むこと）。

この文書の読者は、**NemakiWare のコードを見ずに** package を検証するプログラムを書く人である。
したがって「製品がそうしている」ではなく、**バイト列として何をどう計算するか**だけを書く。

書いてあるアルゴリズムは `docs/evidence-profile/v1/vectors/profile-v1-vectors.json` の
ベクタで固定され、`docs/evidence-profile/v1/vectors/reference_verify.py` が
**Java を書き写さずにこの文書から**実装して同じ値を出すことを
`EvidenceProfileV1VectorsTest` が毎回実行して確かめる。

---

## 0. この文書が規定しないもの（先に読む）

| 規定しない | どこで決まるか |
|---|---|
| 機械可読な JSON Schema と verifier の結果 schema | Phase 5（独立 verifier の成果物）。§5 の必須 field は**この文書が正典**で、schema はその写しになる |
| trust profile の**ファイル形式** | Phase 6。**意味**（何を信頼根拠と認め、何を不足と呼ぶか）は §12 で規定する |
| P4 の block header をどこから得るか | 配備の方針。§13 は「得られなければ `INDETERMINATE`」とだけ規定する |
| `renewal` の実行方針（いつ打ち直すか） | 運用。§14 は「打ち直された記録をどう検証するか」だけを規定する |

**「規定しない」は「検証しなくてよい」ではない。**

### 今の製品が書く package はこの contract を満たしていない

3.4.0 が今日書く package には `metadata/other/nemaki-evidence.json` が 1 本あるだけで、
§4.2 の 9 エントリは**存在しない**。この形の package は **`legacy`** であり、

- **P0 だけが評価できる**
- P1 以上は `INDETERMINATE`、reason code **`LEGACY_PACKAGE_LAYOUT`**

v1 の配置を書き出すのは Phase 4 である。
**旧 package を「検証した」と述べてはならない** — 検査していないのだから。

---

## 1. 適合性

| 役割 | 意味 |
|---|---|
| **package** | §4.2 の配置を満たす SIP。満たさなければ `legacy` |
| **verifier** | この文書だけを読んで書かれた検証器。製品の hash helper を呼ばない |
| **profile** | 必須 check の集合（§9〜§14）。profile ごとに必須集合が違う |

verifier は **既定で network を使わない**。network を使う check は、使えないなら
`NOT_CHECKED` であり、`PASS` でも `FAIL` でもない。

---

## 2. 16 進表記

すべてのダイジェストは **SHA-256 の小文字 16 進 64 文字**。
以下 `hex(x)` と書いたら常にこれを指す。

---

## 3. 正準エンコーディング

台帳の hash と `.c14n` は **JSON の上ではなく、型付きバイト列の上**で計算する。
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
  UTF-16 の順ではない（補助文字が U+E000..U+FFFF より前に来てしまう）。
- **Unicode 正規化はしない。** 受け取ったバイト列のまま符号化する。
- `enc(v)` と書いたら、この規則で値 `v` を符号化したバイト列を指す。

### 3.2 JSON 文書の正準形（`.c14n`）

JSON 文書 `D` の正準形は `enc(parse(D))` である。対応は次のとおり。

| JSON | 符号化 |
|---|---|
| object | MAP |
| array | LIST |
| string | STRING |
| 整数 | LONG |
| `true` / `false` | BOOL |
| `null` | NULL |

**受け付けない形（いずれも文書が malformed であり、判定は `FAILED`）**:

- **整数でない数**（小数点・指数）。int64 に収まらない整数も同じ。
  丸めて符号化してはならない — 丸めた値の hash は元の文書の hash ではない。
- **同じ object の中に重複する key**。「最後の勝ち」で読んではならない。
  重複を許すと、同じバイト列が 2 つの意味を持ち、正準形が 2 つになる。
- object の key でない位置の非文字列 key（JSON では起こらないが、実装が Map を
  経由するときに起こり得る）。

定義:

```
c14n(D)           = enc(parse(D))
documentDigest(D) = hex(SHA-256(c14n(D)))
```

**同梱された `.c14n` は信頼の対象ではなく、検査の対象である。**
`X.json` の隣に `X.c14n` があるとき、verifier は

1. `X.json` を parse して `c14n` を**自分で計算**し、
2. `X.c14n` のバイト列と**バイト単位で一致すること**を確かめる。

一致しなければ `FAILED`。**`X.json` が parse できないときに `X.c14n` を代わりに読んではならない**
（読めなかったものを読めたことにする形。§15）。

### 3.3 `hash(parts...)`

```
canonical(parts) = LIST(parts)          -- 3.1 の規則で符号化
hash(parts)      = hex(SHA-256(canonical(parts)))
```

つまり `hash("a","bc")` と `hash("ab","c")` は**別の値**（長さ前置があるため）。

---

## 4. package の配置

### 4.1 実測（今の 3.4.0 が書くもの — `legacy`）

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
- `ers.der` は**あるときだけ在る**。無いことは欠陥ではない。
- ZIP エントリの**順序に意味は無い**。集合として扱うこと。

### 4.2 v1 の contract（Phase 4 で書き出す）

```
metadata/other/nemaki-evidence/
  profile.json
  bundle-manifest.json
  record-content-statement.json      + record-content-statement.c14n
  ledger-entry.json                  + ledger-entry.c14n
  inclusion-proof.json
  covering-checkpoint.json           + covering-checkpoint.c14n
  checkpoint-chain.json
  anchor-target-checkpoint.json      + anchor-target-checkpoint.c14n
  anchors/
```

9 エントリ・**12 ファイル** + `anchors/` ディレクトリ。

- `anchors/` は**種別ごとに 1 ファイル**（`rfc3161.der` / `ots.ots` / `atlas.json`）。
  **無い種別は file を置かない。** 置かない理由は `bundle-manifest.json` の
  `anchors[].state` に `NOT_PRESENT` / `NOT_CONFIGURED` / `UNAVAILABLE` のいずれかで書く。
  **空の DER を置いてはならない**（無いものを在るように見せる形）。
- `metadata/other/nemaki-evidence.json`（legacy の 1 本）は v1 package では**置かない**。
  両方在る package は `FAILED` — どちらが正かを verifier が選ぶことになるため。

---

## 5. 文書ごとの必須 field

**どれか 1 つでも欠ければ、その文書を入力とする check は `NOT_PRESENT`** であり、
`PASS` ではない（§15）。型が違えば `FAILED`。

### 5.1 `profile.json`

| field | 型 | 意味 |
|---|---|---|
| `profileVersion` | STRING | `"1"` 固定。他の値はこの文書では検証できない → `INDETERMINATE` |
| `declaredProfiles` | LIST of STRING | `PACKAGE_INTEGRITY_V1` 〜 `LONG_TERM_ERS_V1` のうち、この package が満たすと主張するもの |

**`declaredProfiles` は主張であって根拠ではない。** verifier は要求された profile を
自分で評価し、宣言と食い違えば宣言のほうを無視する。

### 5.2 `bundle-manifest.json`

| field | 型 | 意味 |
|---|---|---|
| `bundleId` | STRING | この束の識別子 |
| `createdAt` | STRING | 束を固定した時刻（RFC 3339） |
| `files` | LIST of MAP | `{path, sha256}`。**package 内の相対 path** |
| `anchors` | LIST of MAP | `{kind, state, path?}`。`kind` は `RFC3161_TSA` / `OPENTIMESTAMPS` / `ATLAS_CATALOG` |

`files` は §4.2 の 12 ファイルを**漏れなく**含む。manifest に無いファイルが
`nemaki-evidence/` 配下に在れば `FAILED`（未参照の追加物）。

### 5.3 `record-content-statement.json`

| field | 型 | 意味 |
|---|---|---|
| `repositoryId` | STRING | |
| `objectId` | STRING | |
| `versionObjectId` | STRING | **不変の版キー**。可変の「最新」を指す id を入れてはならない |
| `contentStreamId` | STRING or NULL | |
| `contentDigest` | STRING | payload bytes の `hex(SHA-256)` |
| `contentLength` | LONG | |
| `commitmentKind` | STRING | `CAPTURED` / `UPDATED` / `OBSERVED` / `RESTORED` |
| `captureIntentId` | STRING or NULL | 外部取込のときだけ非 NULL |
| `recordedAt` | STRING | **台帳へ書いた時刻**。source の作成時刻ではない |

**`OBSERVED` を `CAPTURED` と同じ強さで読んではならない。**
`OBSERVED` は「この観測時点で存在し、その後 anchor された」までで、
受領時から不変だったとは言っていない。

**`RESTORED`**（2026-09-22）は「アーカイブから戻した」— bytes は archive が持っていたもので、内容を持たなかった版に
書き戻された。`CAPTURED`（最初の bytes）でも `UPDATED`（差し替え）でもない。digest は書き戻す pass で取る。

#### 5.3b `record-content-statement.json` が**遷移文**のとき

同じファイル名で、`ledger-entry.json` の `subjectKind` が **`RECORD_CONTENT_TRANSITION`** の package は、
statement が「bytes に何が起きたか」を述べる別の文書である（設計
[`record-content-transition.md`](record-content-transition.md)）。**`contentDigest` を持たない。**

| field | 型 | 意味 |
|---|---|---|
| `repositoryId` / `objectId` / `versionObjectId` | STRING | 5.3 と同じ。`versionObjectId` は不変の版キー |
| `transition` | STRING | `ARCHIVED` / `COPIED_TO_COLD` / `MOVED_TO_COLD` / `ARCHIVE_DESTROYED` / `ARCHIVE_DESTROYED_LEAVING_COLD_BLOB` / `CONTENT_REMOVED` |
| `bytesNow` | STRING | 遷移**後**に bytes が在る場所: `ARCHIVE_DB` / `COLD` / `NONE` / `UNKNOWN`。**`UNKNOWN` は「確かめていない」**であり「無い」でも「在る」でもない |
| `priorContentDigest` | STRING or NULL | 遷移**前**の digest。台帳が先行する statement を持つときだけ、そこから**写す**。NULL は「知らない」 |
| `priorStatementEntrySequence` | LONG or NULL | `priorContentDigest` の出所の entry。digest と**対**（片方だけは不正） |
| `recordedAt` | STRING | 台帳へ書いた時刻 |

**この版の verifier は遷移文を読まない**（P1 の `CONTENT_BINDING` は `contentDigest` が無いので `NOT_PRESENT` になる。
遷移文の検査 — payload 同梱は矛盾、`transition continuity` — は残件 R67）。

### 5.4 `ledger-entry.json`

§6 の全 field。**`subjectId` を省略しない** — 省略すると entryHash を再計算できない。

### 5.5 `inclusion-proof.json`

| field | 型 |
|---|---|
| `leafHash` | STRING（`hashLeaf(entryHash)`） |
| `steps` | LIST of MAP `{siblingHash: STRING, siblingIsLeft: BOOL}` |

### 5.6 `covering-checkpoint.json` / `anchor-target-checkpoint.json`

§7 の全 field。

### 5.7 `checkpoint-chain.json`

| field | 型 |
|---|---|
| `links` | LIST of MAP。covering から anchor target まで、**順に**並べた checkpoint の全 field |

同一なら `links` の長さは 1。

---

## 6. 台帳エントリ

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
- `subjectKind` は enum の**名前**（例 `CAPTURE_COMPLETED`、`RECORD_CONTENT_STATE`、`RECORD_CONTENT_TRANSITION`）。序数ではない。

### 検証

1. package 内の entry の各 field から上式で再計算する。
2. 記録された `entryHash` と一致しなければ **FAIL**。
3. `prevEntryHash` が直前の entry の `entryHash` と一致しなければ **FAIL**。
4. **どれか 1 つでも field が package に無ければ `NOT_PRESENT`**（再計算していないものを
   PASS と言ってはならない）。

---

## 7. checkpoint

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

## 8. Merkle 木と inclusion proof

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
  step が無いのに一致しない場合は **FAIL**（`NOT_PRESENT` ではない — 計算はできた）。
- proof が**無い**場合は `NOT_PRESENT`。「proof が無い」と「proof が合わない」は別の答え。

---

## 9. P0 `PACKAGE_INTEGRITY_V1`

必須 check:

| check | PASS の条件 |
|---|---|
| `ZIP_SAFE` | 全エントリ名が相対で、`..` 成分を含まず、絶対 path でなく、**重複しない** |
| `ZIP_LIMITS` | 展開後の合計 size とエントリ数が verifier の上限内 |
| `METS_CLOSURE` | METS が名指す全 file が package に在り、**逆に** `representations/*/data/` 配下の全 file が METS に名指されている |
| `PAYLOAD_FIXITY` | 各 payload と PREMIS の fixity が**一対一**で一致する |

主張しないこと: 台帳・外部 anchor。

- **重複エントリ名は `FAILED`**。片方だけ検査して PASS と言う形を封じる。
- **上限到達は crash でも FAIL でもなく `UNAVAILABLE` + reason `RESOURCE_LIMIT`**
  （→ `INDETERMINATE`）。「大きすぎて調べられなかった」は「調べて問題が無かった」ではない。
- PREMIS が 1 つの payload に 2 つ fixity を持つ、あるいは 2 つの payload が同じ
  PREMIS object を指す場合は `FAILED`（一対一が崩れている）。

---

## 10. P1 `RECORD_LEDGER_V1`

必須 check: **P0 の全部** + 下表。

| check | PASS の条件 |
|---|---|
| `STATEMENT_C14N` | `record-content-statement.c14n` が §3.2 の再計算と**バイト一致** |
| `CONTENT_BINDING` | statement の `contentDigest` / `contentLength` が package 内 payload と一致 |
| `ENTRY_RECOMPUTE` | §6 で再計算した `entryHash` が記録と一致 |
| `ENTRY_BINDS_STATEMENT` | entry の `payloadDigest` == `documentDigest(record-content-statement.json)` |
| `INCLUSION_PROOF` | §8 の手順で `covering-checkpoint.merkleRoot` に到達 |
| `COVERING_RANGE` | `covering.fromSequence ≤ entry.sequence ≤ covering.toSequence` |
| `CHECKPOINT_RECOMPUTE` | §7 で再計算した `checkpointHash` が記録と一致 |

主張しないこと: 外部の暗号的信頼。

**`ENTRY_BINDS_STATEMENT` と `CONTENT_BINDING` は別の check であり、両方が要る。**
片方だけでは「payload と statement は合っているが、台帳が指しているのは別の statement」
または「台帳と statement は合っているが、同梱の payload は別物」が通る。

---

## 11. P2 `ANCHORED_CHECKPOINT_V1`

必須 check: **P1 の全部** + 下表。

| check | PASS の条件 |
|---|---|
| `CHAIN_LINKED` | `links[i].prevCheckpointHash == links[i-1].checkpointHash`（全 i） |
| `CHAIN_ENDS` | `links[0]` が covering、`links[last]` が anchor target |
| `CHAIN_FORWARD` | `links[i].toSequence` が狭義単調増加。covering が target より後なら `FAILED` |
| `CHAIN_RECOMPUTE` | 各 link の `checkpointHash` を §7 で再計算して一致 |
| `ANCHOR_COMMITS_ROOT` | `anchors[].anchoredDigest == anchor-target-checkpoint.merkleRoot` |
| `ROLLBACK` | `--expected-checkpoint` が与えられたとき、chain 上にその hash が在る |

主張しないこと: token の PKIX（それは P3）。

### この profile が**証明しない**こと

**anchor が commit しているのは `merkleRoot` であって `checkpointHash` ではない。**
したがって anchor それ自体は `prevCheckpointHash`（= 過去の期間との連結）を固定しない。
過去との連結を運んでいるのは package 内の `checkpoint-chain.json` であり、
**package は検証の対象であって信頼根拠ではない**。

言えるのは「anchor target の期間に含まれる entry 集合は、anchor の時点で確定していた」まで。
**それ以前の期間の書き換えを検出するには、外部で保持した checkpoint
（`--expected-checkpoint`）が要る。** 無ければ `ROLLBACK` は `NOT_CHECKED`。

`links` が空、重複、逆順、欠落のいずれかなら `FAILED`。

---

## 12. P3 `TRUSTED_RFC3161_V1`

必須 check: **P2 の全部** + 下表。対象は `anchors/rfc3161.der`。

| check | PASS の条件 |
|---|---|
| `TOKEN_PARSE` | RFC 3161 `TimeStampToken` として読める |
| `TOKEN_IMPRINT` | `messageImprint` が anchor target の `merkleRoot` に一致 |
| `TOKEN_CMS` | CMS 署名が埋め込み証明書で検証できる |
| `TOKEN_EKU` | 署名者証明書が `id-kp-timeStamping` を**critical で**持つ |
| `TOKEN_PKIX` | **trust profile が名指す anchor** への path が作れる |
| `TOKEN_POLICY` | token の policy OID が trust profile の許可集合に在る |
| `TOKEN_REVOCATION` | **発行時に取得した** CRL / OCSP が package 内に在り、署名者を失効と言っていない |

主張しないこと: 認定業務であること。

### trust の意味（ここが P3 の本体）

- **package 内の root を trust anchor にしてはならない。** anchor は verifier に
  外から与える（trust profile）。package が自分で自分を信頼根拠にできるなら、
  署名を差し替えた package が `VERIFIED` になる。
- **`revocationDataCapturedAt` が `never` なら `VERIFIED` にしない。**
  後から取った current OCSP を「発行時に取得済み」として扱ってはならない。
  失効材料が無いときの答えは `INDETERMINATE`（`FAILED` ではない — 失効していたとは
  言えていない）。
- **accreditation を示す文字列を事実として読まない。** token / 証明書に
  「認定」「適格」と書いてあっても、それは発行者の主張である。
- **no-network で、この版に network モードは無い**（`--allow-network` は受け付けず、package を
  開く前に exit 4。何も報告しない）。OCSP を**その場で**取りに行くことは**しない** — 取れたとしても
  「発行時の材料」ではなく、失効は verify 時に取らない設計（§12）。socket が要る検査は OTS の
  attestation だけで、`NO_BLOCK_HEADER_SOURCE` を報告する。

---

## 13. P4 `ANCHORED_OTS_V1`

必須 check: **P2 の全部** + 下表。対象は `anchors/ots.ots`。

| check | PASS の条件 |
|---|---|
| `OTS_PARSE` | OpenTimestamps proof として読める |
| `OTS_COMMITS_ROOT` | proof の起点が anchor target の `merkleRoot` |
| `OTS_ATTESTATION` | Bitcoin block attestation まで upgrade 済み |
| `OTS_BLOCK` | その block header が、verifier に与えた header source と一致 |

- **既定 no-network。** header source が無ければ `OTS_BLOCK` は `NOT_CHECKED` →
  全体は `INDETERMINATE`。**「proof は読めた」を「時刻が確かめられた」と言ってはならない。**
- **calendar への未 upgrade（pending）は失敗ではない** → `NOT_PRESENT`。
- 時刻の意味は**上限のみ** — 「その時刻より前に存在した」であって、
  「その時刻に作られた」ではない。

---

## 14. P5 `LONG_TERM_ERS_V1`

必須 check: **P3 または P4 のいずれかの全部** + 下表。対象は `metadata/other/ers.der`。

| check | PASS の条件 |
|---|---|
| `ERS_PARSE` | RFC 4998 `EvidenceRecord`（version 1）として読める |
| `ERS_DATA_OBJECT` | 最初の hash list が `SHA-256(anchor-target-checkpoint.c14n)` を含む |
| `ERS_CHAIN` | 各 ArchiveTimeStamp の imprint が前段を覆う |
| `ERS_ALGORITHMS` | 宣言された digest algorithm を verifier が**知っている** |

- **data object は `anchor-target-checkpoint.c14n` のバイト列**であり、payload ではない。
  ERS が覆っているのは checkpoint であって、個別文書の長期署名ではない。
- **未知の algorithm は `INDETERMINATE`** であり、「不一致」と呼んではならない。
  計算できなかったことを、計算して違ったことにする形。
- renewal は**前段を覆っていなければ `FAILED`**。覆っているかを調べずに
  「打ち直されている」だけで PASS にしない。

---

## 15. 判定の合成

check の結果は **4 値**。「調べて正しい」「調べて誤り」「無いので調べられない」
「調べられなかった」は互いに別である。

| 結果 | 意味 |
|---|---|
| `PASSED` | 調べて正しい |
| `FAILED` | 調べて誤り |
| `NOT_PRESENT` | package がその check に要るものを持っていない |
| `UNAVAILABLE` | 調べられなかった。**理由は reason code で述べる** — `RESOURCE_LIMIT`（上限到達）、`NO_BLOCK_HEADER_SOURCE / REVOCATION_NOT_CAPTURED`、`UNKNOWN_ALGORITHM`、`LEGACY_PACKAGE_LAYOUT` など |

合成:

| 状況 | verdict |
|---|---|
| 要求された profile の必須 check がすべて `PASSED` | `VERIFIED` |
| いずれかの check が `FAILED` | `FAILED` |
| いずれかの必須 check が `NOT_PRESENT` / `UNAVAILABLE` | `INDETERMINATE` |
| 何も調べていない | `INDETERMINATE` |

**`FAILED` は `NOT_PRESENT` より強い** — 1 つでも誤りが見つかれば、他が調べられなくても
`FAILED` である（誤りを「分からない」に薄めない）。

- **`NOT_PRESENT` を PASS に昇格させてはならない。** 「無かったので確かめられなかった」は
  「確かめて問題が無かった」ではない。
- **`FAILED` を `INDETERMINATE` に薄めてはならない。** 逆向きの同じ誤り。
- 必須でない check の結果は verdict を動かさない。**が、報告からは消さない** —
  「調べたが必須ではなかった」と「調べていない」は別である。

exit code: `0` = `VERIFIED` / `2` = `FAILED` / `3` = `INDETERMINATE` / `4` = usage / `5` = internal。

---

## 16. ベクタと独立実装

- `docs/evidence-profile/v1/vectors/profile-v1-vectors.json` — 入力と期待値。
- `docs/evidence-profile/v1/vectors/reference_verify.py` — **この文書だけから**書いた実装。

```
python3 docs/evidence-profile/v1/vectors/reference_verify.py
```

は、不一致があれば非 0 で終了する。`EvidenceProfileV1VectorsTest` は
**Java の計算がベクタと一致すること**と、**その Python を実際に実行して一致すること**の両方を測る。
両者が同じ 1 つのベクタファイルを読むので、片方だけを書き換えて辻褄を合わせることはできない。

**この節が「実行している」と書いているのは、実行しているからである。**
lineage 側の先例（`reference_hash.py`）は手で走らせる前提で、走らせなければ何も言わない。
