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

### どの package が v1 で、どれが `legacy` か

**3.4.0 は v1 の配置を書く**（Phase 4、`EvidenceBundleWriter`）。ただし**書けるのは、その版に
E1 の record content statement が台帳に在るときだけ**である。無い版の package は
`metadata/other/nemaki-evidence.json` が 1 本あるだけの **`legacy`** で、

- **P0 だけが評価できる**
- P1 以上は `INDETERMINATE`、reason code **`LEGACY_PACKAGE_LAYOUT`**

statement が無いのは、E1 の配線より前に書かれた版、台帳に届かなかった版、
そして**内容を失った版**（遷移文しか無く、書き出し自体が拒否される。正典 R67）である。
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

### 1.1 v1 は凍結した（2026-09-22）

**この版で v1 を凍結する。** 受け取る側が自分で verifier を書くための contract だから、
書いた後で意味が変わってよい部分と、変えてはならない部分を先に分ける。

| 変えない（変えるなら v2） | 足してよい（v1 のまま） |
|---|---|
| §3 の正準エンコーディングと `hash(parts...)` の入力 | 新しい reason code（verifier の登録簿と result schema が正典） |
| §4.2 の配置と、そこに**必須**と書いたファイル | 新しい profile（P6 以降） |
| §5 の各文書の**必須** field と、その型 | **必須でない** check（報告はするが verdict を動かさない） |
| §6 の entry hash の入力**と順序** | 既存文書の**任意** field（知らなくても検証できるもの） |
| §7 の checkpoint hash、§8 の Merkle の規則 | |
| §9〜§14 の**必須 check の集合** | |
| §15 の合成規則と exit code | |

**変えるときは v2。** `$id` の `v1`、`profile.json` の `profileVersion`、
`docs/evidence-profile/v1/` が一緒に動く。**v1 の package を読めなくする変更は、
v1 を壊さずに v2 を足すことでしか行わない。**

**凍結と「誤りの訂正」の境目**（2026-09-22）。判定は **1 つの規則**である —

> **契約に適合する package が、読めなくなるか、新たに拒否されるか。**
> **なる**なら contract の変更であり、v2 が要る。**ならない**なら訂正であり、v1 のまま直す。

規則は 1 つだが、**問いは 2 つ立てる**（片方だけでは取りこぼす）。

1. これまでに**出荷した**適合 package について。
2. **第三者が標準どおりに書いた**適合 package について。

> 問い 2 は後から足した（6 巡目）。1 だけだと、**自分が一度も書いたことのない形を狭める変更を
> 構造的に検出できない**。実例: §14 の `ERS_PARSE` に `digestAlgorithm [0]` 必須を書いたとき、
> 出荷物は 0 なので問い 1 は「変わらない」と答えたが、**BouncyCastle が作る適合記録は全部拒否される**
> ようになっていた。

**向きが効く。** 規則は「読めなくなるか」を問う。**緩める**変更（契約が許すものを受け入れる、
誤って狭めた規定を戻す）は、どの適合 package も読めなくしないので**常に訂正**である。
**狭める**変更（契約が任意と定めたものを必須にする、新しい拒否を足す）は、
適合 package を 1 つでも拒否するなら contract の変更である。

この版で 5 件、訂正として直した。

| 訂正 | 向き | なぜ contract の変更ではないか |
|---|---|---|
| §14 の ERS data object（`SHA-256(c14n)` → `merkleRoot` の bytes） | 訂正（誤りの是正） | どの token もその値を覆わないので、規定どおりの verifier は本物を必ず拒否した。かつ**この版は ERS を 1 本も出荷していない** |
| §9 に `V1_LAYOUT` を必須 check として追加 | 狭める | §4.2 と §5.2 が**既に**「legacy と併存 → `FAILED`」「manifest に無いファイル → `FAILED`」と規定していた。**拒否されるようになるのは、既に規定違反だった package だけ**で、適合 package は 1 つも動かない（両端の錠で示す） |
| §14 の `ERS_PARSE` から `digestAlgorithm [0]` 必須を外し、縮約を「2 つ以上のときだけ hash」に | **緩める** | **書いた規定のほうが RFC より狭かった**。間違った狭め方を戻すので、適合記録の判定は**通る方向にしか動かない** |
| §9 の矛盾判定を「1 object の digest の個数」から「**1 つの算法の中で食い違ったとき**」に | **緩める** | **`premis:fixity` は PREMIS で repeatable** であり、同じ bytes を MD5 と SHA-256 で記録するのがその用途。個数で見た規定のほうが PREMIS より狭く、**問い 2 に「拒否されるようになる」と答えていた**（2 名が独立に実測）。戻すので通る方向にしか動かない |
| §9 の METS href 解決を「どこかの entry が同名で終わる」から「**URI reference として、decode した綴りを、名前のついた base に対して厳密に**」に | 狭める + 緩める | **狭める側**: 別の METS の近所や payload の中の写しが参照を満たすのをやめる — これは fail-open の是正で、**適合 package は動かない**（参照は自分の場所から解決できるのが適合の条件）。**緩める側**: `../` を畳み、**producer が percent-encode した href を decode し**、`file:` を剥がし、`LOCTYPE` が指す非ローカル locator を数えないので、第三者の正当な METS が**拒否されなくなる**。**この「緩める側」は実測で必須だった** — commons-ip2 は href を `URLEncoder` で encode して zip entry は生のまま書くため、**空白や非 ASCII を含む名前の payload を持つ package は、この製品が書いたものを含めて全部拒否されていた**（日本語の repository では普通の場合）|

3 件目は「問い 2 に変わると答える」ように読めるが、**変わるのは拒否 → 受理の向き**である。
規則は「読めなくなるか」を問うので、これは訂正にあたる。**狭めた当初の規定のほうが
contract の変更だった** — それは出荷していない。

2 件目は必須 check の集合を動かすので、**表の左列に触れた唯一の例**である。
これを許す条件は上の規則だけで、「実装が楽だから」「見落としていたから」は理由にならない
— **適合 package が通る**ことを錠で示すこと
（`TheV1LayoutIsCheckedTest` と `TheWriterWritesWhatTheLayoutRequiresTest`、および
製品が build した package に CLI をかけた実測）。

**凍結の対象は contract であって、製品の実装ではない。** 例えば遷移文（§5.3b）は v1 の
一部だが、この版の NemakiWare は内容を失った版の package を**書き出せない**（正典 R67）。
contract が定義済みであることと、この製品が今それを出せることは、別の事実である。

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
  prior/                             （遷移文が prior を引くときだけ、§5.3b）
    record-content-statement.json    + record-content-statement.c14n
    ledger-entry.json                + ledger-entry.c14n
```

9 エントリ・**12 ファイル** + `anchors/` ディレクトリ + 任意の `prior/`（4 ファイル）。

- `prior/` は **statement が遷移文で `priorStatementEntrySequence` を持つときだけ**置く。中身は
  その sequence の entry と、その entry が commit する state statement（遷移文が digest を写した出所）。
  **2 つで 1 組** — 片方だけは置かない。exporter は出所が journal と台帳の両方から読めて、両者が
  一致するときだけ置く（一致しなければ置かず、verifier が `TRANSITION_PRIOR_NOT_IN_PACKAGE` で答える）。

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

**verifier は遷移文を entry の `subjectKind` で見分ける**（文書の形からではない — 形で決めると、鍵を持たない文書が
自分に掛かる検査を選べる）。遷移文の package での P1 は §10 の 2 行（`CONTENT_BINDING` の遷移文の読み方、
`TRANSITION_CONTINUITY`）。**遷移文の package は P1 で `VERIFIED` に届かない** — bytes を主張しない statement に
`CONTENT_BINDING` は `NOT_PRESENT` で、それは必須 check である。届くのは P0 まで。exporter の
`highestProfileSupported` もそう答え、P1 以上を要求する export は package を渡さず拒否する。

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
| `ONE_EVIDENCE_SECTION` | §4.2 の各文書名が package 内で 1 回しか現れない（**payload は数えない** — 内容が別 package の evidence フォルダの写しであっても、それは content であって section ではない）。**ただし重複の原因が「完全な section を持つ root が 2 つ以上」であれば `UNAVAILABLE` + `MULTIPLE_PACKAGES`** — `V1_LAYOUT` と同じ答えをここでも返す。この check の方が**先**に走るので、ここで `FAILED` にすると合成が `FAILED` に落ち、`V1_LAYOUT` 側の訂正が verdict に届かない |
| — | `V1_LAYOUT` は併せて **section を持つ root が 2 つ以上のとき**を見る（下表の最後の 2 行）|
| `V1_LAYOUT` | **v1 section が在るなら** §4.2 のとおりに在る（下記） |
| `METS_CLOSURE` | **payload でない全 METS**（CSIP では root と各 representation の 2 階層）が名指す file が package に在り、**逆に** `representations/*/data/` 配下の全 file がそのいずれかに名指されている（**local な参照が 1 本も無くても、payload が在るならこの逆方向は必ず通す** — 通さないと「METS は何も名指していない」が「名指されていない payload は無い」を意味してしまう）。**ただし数えなかった locator が 1 本でもあるなら、逆方向の結論は `FAILED` ではなく `UNAVAILABLE`（`AMBIGUOUS_PAYLOAD`）** — 見ていない参照がその payload を名指しているかもしれないので、「誰も約束していない content が入っている」は**établi していない**。**両方向とも同じ解決を読む**。参照は URI reference として解決する: fragment と query を落とし、`file://./` / `file:` を剥がし、**`xml:base` を RFC 3986 §5.2.2 で merge し**（base の最後の segment は**置換**される）、**locality は merge した結果で判定する**（`xml:base` に authority があるときは絶対参照 `/x` もその authority のものとして merge する。**参照が自分の authority を持つ（`//host/…`）なら base の authority を置換する** — §5.2.2 / §5.2.3）。綴りは**segment ごとに** percent-decode した形と**`+` を空白として decode した形**を試し、**どちらも decode できなかったときに限り**文字どおりの綴りを試す（最後の手段。先に試すと `a%20b.txt` という名前の file が `a b.txt` への参照を満たす）。**ただし「decode すると区切りや `..` を作る」ために捨てた参照には、この最後の手段も与えない**（そして**その参照は「package が運んでいない file」には数えない** — 運んでいるかもしれないので、`UNAVAILABLE` で「従わないと決めた」と述べる） — 「読めなかった」と「読んだが従わないと決めた」は別で、後者に literal を許すと `data/%2e%2e/secret.txt` という名前の file がその参照を満たしてしまう。**decode で区切りや dot segment が新たに生じる綴りは捨てる**（`%2F` で階層を合成させない）。**escape の 2 桁が両方とも hex でなく、または run が正当な UTF-8 でなければ decode しない**（`new String(bytes, UTF_8)` の U+FFFD 置換は別 file を claim させる）。**文字は文字のまま置く** — 1 文字ずつ byte に落とすとサロゲート対が壊れ、BMP 外の名前だけが拒否される。 base は**絶対参照（`/…`）なら package root だけ**、相対なら**その href を書いた METS 自身のディレクトリ → package root → zip root**。**package root は METS ごと**に「**自分の path を prefix する** METS のうち最も浅いもののディレクトリ」（効いているのは「自分より上」という限定で、prefix 同士なら深さ順と文字列長順は一致する）— zip に package が 2 つ入る形が想定内なので、zip に 1 つではない。dot segment は畳む（§5.2.4）が**余った `..` は破棄せず拒否**する。**横に出る参照は解決とみなさない** — candidate を持つ**最も深い** METS のディレクトリが「持ち主」で、それが自分の祖先でも子孫でもないなら別の package である（root METS が representation を指す**下向き**は正当）。**ただし限界がある**: 包み folder の無い zip で、`../` が package を出て**どの METS も持っていない file** に着地する形は拒否されない（境界が述べられていないため。拒否すると正当な上向き参照を巻き込む）。 **scheme を持つ locator（`urn:` / `doi:` / `hdl:` / `http:` …）、authority を持つ参照（`//host/…`。**UNC の `\\host\share` は参照を読む時点で `/` に正規化して同じ形にする** — 綴りごとに規則を足すと、4 か所のうち 1 か所にしか届かない）、`LOCTYPE` が `URL` 以外だと宣言している locator は「package 内の file への参照」ではないので数えない**（相対の `anyURI` で書かれた外部識別子は `LOCTYPE` でしか見分けられない）。**数えなかった locator の本数は、どの答えの detail にも書く**（PASSED だけでなく、FAILED も「何も名指していない」も）— 黙って飛ばすと「調べて完全だった」と「ほとんど調べなかった」が同じ文面になる。**`LOCTYPE` は英数字だけで照合し（属性を折り返した METS が落ちないように）、`URL` でなければ外部**。**`OTHERLOCTYPE` だけが例外的に path を宣言できる** — METS は `LOCTYPE` を列挙（ARK / URN / URL / PURL / HANDLE / DOI / OTHER）し、`OTHERLOCTYPE` を自由記述にしているので、同じ語でも 2 つは**別の問い**である（`LOCTYPE="FILE"` は列挙に無い値であって path 宣言ではない）。`file:` だけは scheme の例外（commons-ip2 が local path に使う。**authority が空 / `.` / `localhost` の3 形だけ** — 他の host を名指す `file://` は別の機械の file であって、この package への参照ではない。`file:///x` の**先頭スラッシュは落とさない**（落とすと絶対参照が相対になる）。なお `file://./x` は**相対**、`file:///x` と `file://localhost/x` は**絶対**として解決される — 3 形は「local である」点だけが等価で、base の扱いは異なる）。**Windows のドライブ文字は形で見分ける**（1 文字＋`:`＋区切り）— 1 文字 scheme を一律に禁じると `x:catalog-entry` のような正当な URI を巻き込む。**`LOCTYPE="OTHER"` は `OTHERLOCTYPE` が決める**（`SYSTEM` / `FILE` / `PATH` / `RELATIVE` / `RELATIVE_PATH` / `LOCAL` なら path、それ以外は外部。**照合は英数字だけを見る**ので `relativePath` / `RELATIVE PATH` / `relative_path` は同じ語）。**`+` の曖昧さは残る** — `a+b.txt` が literal な `+` なのか空白の encode なのかは producer を知らずに決められず、literal が package に在ればそちらを先に採る（commons-ip2 の package が通ることを優先している）|
| `PAYLOAD_FIXITY` | payload の bytes が PREMIS の記録する digest と一致する（不一致は `FAILED`）。**一対一そのものは判定しない**（下記の訂正）— digest が 2 つ以上、payload が 2 つ以上のときは `UNAVAILABLE`。**1 つの `premis:object` が 1 つの算法で 2 つの異なる digest を記録している**ときだけ、突き合わせる前に `FAILED` |

`V1_LAYOUT` の答え:

| package | 答え |
|---|---|
| v1 section が無い（§4.1 の legacy、あるいは evidence を持たない package） | `PASS` — §4.2 が縛るものが無い。P1 以上が `LEGACY_PACKAGE_LAYOUT` で別途答える |
| `profile.json` が無い / JSON object でない | `FAILED` |
| `profileVersion` が無い | `NOT_PRESENT`（§5: 欠落した必須 field） |
| `profileVersion` が STRING でない | `FAILED`（§5: 型違い） |
| `profileVersion` が `"1"` 以外 | `UNAVAILABLE` + `UNSUPPORTED_PROFILE_VERSION` — **v1 の規則を v2 の package に当てるのは、書かれてもいない契約の違反を報告すること** |
| legacy の `nemaki-evidence.json` と併存 | `FAILED`（§4.2） |
| `bundle-manifest.json` が無い / object でない / `files` が LIST でない | `FAILED` |
| `files` が無い、要素が `path` か `sha256` を欠く | `NOT_PRESENT` |
| manifest が名指すファイルが section に無い | `FAILED` |
| section のファイルが manifest に無い（`bundle-manifest.json` 自身を除く） | `FAILED`（§5.2 の未参照の追加物） |
| digest が合わない | `FAILED` |
| section 内で同じ相対名が 2 回現れる | `FAILED` |
| section を持つ root が 2 つ以上で、**そのすべてが `profile.json` と `bundle-manifest.json` の両方を持つ** | `UNAVAILABLE` + `MULTIPLE_PACKAGES` — **package が package を内包している**（CSIP の AIP が元 SIP を `submission/` に入れる形）。§4.2 は**どちらを訊かれたか**を定めていないので、言えない |
| section を持つ root が 2 つ以上で、**どれかが片方を欠く** | `FAILED` — **分割された 1 つの section**。片方に `profile.json` と manifest、もう片方に文書を置くと重複を作らず、manifest も閉じるので、これだけが捕まえる |

**順序が効く。** 入れ子の判定は**重複より先**に行う。内包された package は相対名を必ず重複させる
ので、先に重複を見ると CSIP の AIP が壊れた package として報告される。
どちらも `UNAVAILABLE` 以下（通ることはない）なので、先後で守りは緩まない。

**payload は数えない。** `representations/<id>/data/` 配下は content であって section ではない
（§4.1 の `ONE_EVIDENCE_SECTION` と同じ理由）。**同じ除外が lookup 側にも要る** —
数える側だけ緩めると、payload に置いた差し替えを zip の順で先に出すことで
「section は 1 つ」と答えながら上位の check がその差し替えを読む。

**`bundle-manifest.json` は自分を列挙しない。** 完成前に自分を hash することになるため。
この 1 ファイルだけが「manifest に無いファイル」の対象外。

主張しないこと: 台帳・外部 anchor。

- **重複エントリ名は `FAILED`**。片方だけ検査して PASS と言う形を封じる。
- **上限到達は crash でも FAIL でもなく `UNAVAILABLE` + reason `RESOURCE_LIMIT`**
  （→ `INDETERMINATE`）。「大きすぎて調べられなかった」は「調べて問題が無かった」ではない。
- **一対一は `FAILED` の規定だが、それは「どの digest がどの file を describe しているか」
  についての規定である。** この verifier は **PREMIS の object → file の結び付きを読まない**ので、
  一対一が崩れていることを**言えない**。したがって digest が 2 つ以上、あるいは payload が
  2 つ以上のときは **`UNAVAILABLE`（`AMBIGUOUS_PREMIS` / `AMBIGUOUS_PAYLOAD`）**。

  > **2026-09-22 の訂正（2 度目）。** 一度これを**数の比較**として実装した（digest 数 ≠ payload 数 →
  > `FAILED`）。**CSIP と Archivematica は file 1 つにつき `premis:object` 1 つを書く** —
  > METS も submission documentation も含めて — ので、payload 1 つの普通の package が
  > 日常的に digest を 2 つ 3 つ記録する。数の規則はそれを「一対一が崩れている」と呼んで
  > **exit 2** を返した。実測で否定して取り下げた。
  > **結び付きを読まないなら、その digest が payload のものかどうかも言えない** —
  > 片方の腕で「言えない」と述べ、もう片方で同じ数から断定するのは自己矛盾である。

- **ただし 1 つの `premis:object` が「同じ算法で異なる 2 つの digest」を記録していれば `FAILED`。**
  これは結び付きを読まなくても見える —「この object が describe している file」について
  PREMIS が**自分で 2 つの答えを書いている**。取り下げたのは**数の比較**であって、
  §9 が名指していたこの形ではない。**2 つの object に 1 つずつ**（普通の CSIP）は
  `UNAVAILABLE` のまま。

  > **2026-09-22 の訂正（3 度目）。** 一度これを「1 つの `premis:object` の中の
  > `messageDigest` の**個数**」で判定した。**`objectCharacteristics/fixity` は PREMIS で
  > repeatable** であり、同じ bytes を MD5 と SHA-256 の両方で記録するのがその反復の用途である。
  > 個数で見ると、**正しい digest を 2 つ持つ適合 package が「自己矛盾」で exit 2** になった
  > （2 名が独立に指摘、実測）。しかも 30 行下の腕は同じ文書の「算法が 2 つ」を**曖昧さ**として
  > `UNAVAILABLE` にしており、1 つの profile が同じ文書に 2 つの答えを出していた。
  > **digest は算法ごとに束ね、1 つの算法の中で食い違ったときだけ**矛盾とする。

  > **2 つの reader が同じ file に逆の答えを返してはならない。** この腕は独立 verifier
  > （`PackageIntegrity.payloadFixity`）に先に入り、製品の `/verify`
  > （`SipVerifier.payloadDigestCheck`）に入っていなかった。同じ zip に対して CLI が `FAILED`、
  > 運用者が叩く endpoint が `UNAVAILABLE` を返す状態が 1 バッチ続いた。
  > 錠は**両側に 1 本ずつ**（`aSecondDigestUnderAnotherPrefixIsFound` /
  > `aSecondDigestUnderAnotherPrefixIsAFindingHereToo`）、対照の CSIP の形も両側に置く。

---

## 10. P1 `RECORD_LEDGER_V1`

必須 check: **P0 の全部** + 下表。

| check | PASS の条件 |
|---|---|
| `STATEMENT_C14N` | `record-content-statement.c14n` が §3.2 の再計算と**バイト一致** |
| `CONTENT_BINDING` | statement の `contentDigest` / `contentLength` が package 内 payload と一致。**statement が遷移文（entry の `subjectKind` が `RECORD_CONTENT_TRANSITION`）なら**: payload が無ければ `NOT_PRESENT`（bytes を主張していない — だから遷移文の package は P1 に届かない）、payload が在れば **`FAILED`**（「bytes は無くなった／移った」と同梱の bytes は矛盾） |
| `ENTRY_RECOMPUTE` | §6 で再計算した `entryHash` が記録と一致 |
| `ENTRY_BINDS_STATEMENT` | entry の `payloadDigest` == `documentDigest(record-content-statement.json)` |
| `INCLUSION_PROOF` | §8 の手順で `covering-checkpoint.merkleRoot` に到達 |
| `COVERING_RANGE` | `covering.fromSequence ≤ entry.sequence ≤ covering.toSequence` |
| `CHECKPOINT_RECOMPUTE` | §7 で再計算した `checkpointHash` が記録と一致 |
| `TRANSITION_CONTINUITY`（**必須ではない**。全 P1 package で報告する） | statement が state なら `NOT_PRESENT`（続くものが無い）。遷移文で prior が両方 NULL なら `NOT_PRESENT`（「知らない」は主張であって欠陥ではない）。片方だけ NULL は `FAILED`。prior を引いていて `prior/` が無ければ `UNAVAILABLE`（`TRANSITION_PRIOR_NOT_IN_PACKAGE`）。`prior/` が在れば: `prior/ledger-entry.sequence == priorStatementEntrySequence`、その entry が §6 で再計算に一致、`payloadDigest == documentDigest(prior/record-content-statement.json)`、その entry が `RECORD_CONTENT_STATE`、そして `prior/record-content-statement.contentDigest == priorContentDigest` — 全部で PASS、1 つでも違えば `FAILED` |

主張しないこと: 外部の暗号的信頼。W12 MOVE で `DISPOSITION` 行と遷移文の**両方**が台帳に在ること
（設計 §1.3 の期待）— package は entry を 1 つしか運ばないので verifier は確かめない。
`TRANSITION_CONTINUITY` が必須でないのは、遷移文の package は `CONTENT_BINDING` で既に P1 に
届かず、必須にしても verdict が変わらないから。`FAILED` は §15 のとおり全体を `FAILED` にする。

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
| `ANCHOR_COMMITS_ROOT` | manifest が `PRESENT` と記録する rung の**材料を読む**（2026-09-22 まではファイルの有無だけを見て常に `UNAVAILABLE` だった）。RFC 3161（manifest の `kind` が `RFC3161_TSA` で path が `anchors/rfc3161.der` の rung**だけ**）: token を parse し、**`hex(messageImprint) == chain の末尾 link の `merkleRoot`**（root は既に SHA-256 digest なので、timestamp されるのは**その bytes**。hex 文字列を再度 hash しない。§12 の `TOKEN_IMPRINT` と同じ読み）。さらに **token の署名を、token が運ぶ証明書に対して検証**する（「誰かが発行した」を言うために必要。**誰が**は P3）。両方が通れば PASS。parse 不能・不一致は `FAILED`。**署名者証明書が token に無ければ `NOT_PRESENT`**（P3 と同じ答え — package についての事実であって、読めなかったのではない）。**imprint の算法が SHA-256 でない**、または**署名アルゴリズムをこの build が計算できない**ときは `UNAVAILABLE`（どちらも `UNKNOWN_ALGORITHM`。**detail がどちらかを述べる** — 1 つの reason code が 2 つの事情を指すので、機械は「この check は行われていない」までしか読めない）。**署名の判定は P3 と同じ 1 か所**（`TokenSignature`）で行う — 同じ token に 2 つの答えを出さないため。OTS / ERS / Atlas の材料はこの profile では**読まない**（P4 / P5 が読む）— 読める rung が 1 つも無ければ `UNAVAILABLE`（`ANCHOR_NOT_PARSED`）。rung が 1 つも `PRESENT` でなければ `NOT_PRESENT` |
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

**`ANCHOR_COMMITS_ROOT` が言うのは imprint の一致まで。** token の署名者が誰か、指定 trust anchor へ path が
繋がるか、失効していないかは P3 の問い。P2 の `VERIFIED` は「誰かが発行した RFC 3161 token が、この
checkpoint の root を commit している」であって、その誰かを信頼してよいとは言わない。

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
| `OTS_COMMITS_ROOT` | proof の `file_digest` が **`SHA-256(merkleRoot の bytes)`**（下記） |
| `OTS_ATTESTATION` | Bitcoin block attestation まで upgrade 済み |
| `OTS_BLOCK` | その block header が、verifier に与えた header source と一致 |

**OTS は RFC 3161 と 1 層ずれる。**（2026-09-22 訂正 — この行は当初 §11 / §12 と同じく
「proof の起点が `merkleRoot`」と書いていた。**製品が作る proof は 1 つもその形ではない**。
2 名が独立に指摘、R70 と同型。`AnchoredOts` はまだ proof を読まず `UNAVAILABLE` を返すので
出荷物の読み方は変わらない — 誤りの訂正であって contract の変更ではない。§1.1 の判定に従う。）

sidecar (`docker/ots/server.py`) は hex を **unhexlify した 32 バイトをファイルに書いて
`ots stamp <file>`** する。`ots stamp` は**ファイルを hash する**ので、detached proof の
起点は `SHA-256(その 32 バイト)` である。RFC 3161 は imprint を**そのまま**受け取るので
`hex(messageImprint) == merkleRoot`（§11 / §12）だが、**OTS は 1 層多い**。
sidecar 自身の `info()` も `hashlib.sha256(_digest_bytes(hex_digest)).digest()` と比べている。
**この差を「どちらも root を覆う」と丸めないこと** — 丸めた規定どおりに実装した verifier は
本物の proof を必ず拒否する。

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
| `ERS_PARSE` | RFC 4998 `EvidenceRecord`（version 1）として**構造ごと**読める（下記） |
| `ERS_DATA_OBJECT` | 最初の ArchiveTimeStamp が **anchor target の `merkleRoot`**（の bytes）を覆う（下記の 2 形） |
| `ERS_CHAIN` | 各 ArchiveTimeStamp の imprint が前段を覆う（§5.2 / §5.3） |
| `ERS_ALGORITHMS` | 記録が**使う** digest algorithm を verifier が**計算できる** |

**`ERS_PARSE` は構造を見る。** version・`digestAlgorithms`・
`archiveTimeStampSequence`（**最後の要素**。`cryptoInfos [0]` と `encryptionInfo [1]` が
間に入るので前から数えない）・各 chain が 1 本以上の ArchiveTimeStamp を持つこと・
各 ArchiveTimeStamp が **RFC 3161 token として parse できる `timeStamp`** を持つこと。
**「version 1 を名乗る DER」だけでは足りない**（2026-09-22 まではそれだけで、期待する
digest が DER のどこかに 32 バイトで落ちていれば 3 つとも PASS した。残件 R72）。

**`digestAlgorithm [0]` は必須ではない。** RFC 4998 §4.2:「If the optional field
digestAlgorithm is not present, the digest algorithm of the timestamp MUST be used」。
無ければ **token の message imprint の算法を使う**。

**ArchiveTimeStamp に `[0]` `[1]` `[2]` 以外の tagged field が在れば `FAILED`。**
RFC 4998 はこの 3 つしか定義していない。知らない field を**黙って飛ばしてはならない**
——「見なかった」を「調べた」として報告する形で、同じ bytes を BouncyCastle は
`Unexpected elements in sequence` で拒否する（bytecode で確認）。`[0]` と `[2]` の**重複**も
`FAILED`（どちらが正かを記録が決めていない）。`[1]` は SET でなければ `FAILED`。

**`ERS_CHAIN` の §5.2 は 2 形ある。** RFC 4998 §5.2 は「The new Archive Timestamp **MAY not**
contain a reducedHashtree field, if the timestamp only simply covers the previous timestamp」
——**禁止ではない**。

| renewal の形 | 判定 |
|---|---|
| reducedHashtree **無し** | token の `messageImprint` が `H(前段の timeStamp field の DER)` と一致すれば PASS |
| reducedHashtree **有り** | 第 1 list が `H(前段の timeStamp field の DER)` を含み、かつ縮約結果が `messageImprint` と一致すれば PASS |

> **2026-09-22 の訂正。** imprint だけを比べていたので、**木を持つ正当な renewal を拒否し、
> かつ任意の木を付けた renewal を通していた**（両方向同時）。

> **2026-09-22 の訂正。** この行は当初「各 ArchiveTimeStamp が `digestAlgorithm [0]` と …
> `timeStamp` を持つこと」と書いていた。**BouncyCastle の生成器はデータオブジェクトが 1 つのとき
> この欄を出さない**ので、規定どおりに実装した verifier は標準ツールが作った記録を必ず拒否する。
> 本製品自身の reader（`ErsRecord.parse`）は既に fallback を実装しており、**同じ形式の 2 つの
> reader が同じ bytes に逆の答えを出していた**。出荷した package の読み方は変わらない（ERS は
> 1 本も出荷していない）が、**第三者が標準どおりに書いた記録の読み方は変わる** — §1.1 の判定に
> この観点を足した。

**`ERS_DATA_OBJECT` の 2 形**（RFC 4998 §4.3）:

| 最初の ArchiveTimeStamp | 判定 |
|---|---|
| reducedHashtree **無し**（§4.2 が明示的に許す。**本製品が書くのはこちら**） | token の `messageImprint` が `merkleRoot` の bytes と一致すれば PASS |
| reducedHashtree **有り** | 第 1 list が `merkleRoot` を含み、かつ §4.3 の縮約結果が token の `messageImprint` と一致すれば PASS |

**縮約は「要素が 2 つ以上のときだけ hash」する。** RFC 4998 §4.2:「For each data group
containing **more than one document**, its respective document hashes are binary sorted in
ascending order, concatenated, and hashed」。**要素 1 つのリストの node hash はその値そのもの。**
BouncyCastle の `ERSUtil.computeNodeHash` も `values.length > 1` のときだけ hash する（bytecode で確認）。

> **2026-09-22 の訂正。** 本製品の verifier 2 つと生成側の 1 か所が、要素 1 つでも hash していた。
> **標準ツールが作った reduced tree を必ず拒否し**、§5.3 の更新では `H(h')` を覆う token を
> TSA に頼むよう指示していた（どの標準 reader も期待しない値）。3 か所を揃えた。

**「DER のどこかに root の 32 バイトが在る」を PASS にしてはならない。**
本製品の記録では root は token の `TSTInfo` の中にあり、hash list は空である —
DER を走査する読み方は**本物を必ず拒否し、偽物を通す**（両方向に誤る）。

- **data object は anchor target の `merkleRoot` の bytes** であり、payload ではない。
  ERS の最初の Archive Timestamp は **その checkpoint を anchor した RFC 3161 token そのもの**で、
  その token が覆っているのは root の bytes（§11・§12 と同じ規約）。ERS が覆っているのは
  checkpoint の**その root**であって、個別文書の長期署名ではない。

- **`h` の元 `d` は package に入っていない。** root は Merkle 木の最上位連結の hash であり、
  その連結はどの package にも無い。したがって受け取る側は RFC 4998 §4.3 step 1（`h = H(d)`）を
  **自分では実行できず**、「package が述べる `merkleRoot` を記録が覆っているか」までしか確かめられない。
  §9 が 2 度目の TSA 往復を禁じている以上これは選択の結果であり、`ErsRecord.LIMITS` にも同じ文が入る。
  **この制限を書かずに `ERS_DATA_OBJECT` の PASS を示すと、実際より強い主張になる。**

  > **2026-09-22 の訂正。** この行は当初 `SHA-256(anchor-target-checkpoint.c14n)` と書いていた。
  > **どの token もその値を覆わない**ので、規定どおりに実装した verifier は本物の record を
  > 必ず拒否する。v1 は凍結済み（§1.1）だが、**この版は ERS を 1 本も出荷しておらず**、
  > かつ**この訂正で読めなくなる適合記録は無い**（拒否 → 受理の向きにしか動かない）。
  > **誤りの訂正であって、contract の変更ではない** — 判定は §1.1 の規則による。
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
| `UNAVAILABLE` | 調べられなかった。**理由は reason code で述べる** — `RESOURCE_LIMIT`（上限到達）、`NO_BLOCK_HEADER_SOURCE / REVOCATION_NOT_CAPTURED`、`UNKNOWN_ALGORITHM`、`LEGACY_PACKAGE_LAYOUT`、`TRANSITION_PRIOR_NOT_IN_PACKAGE` など（全 23 値は verifier の登録簿 `Outcome.Check.REASON_CODES` と result schema の enum） |

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
