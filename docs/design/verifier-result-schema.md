# 設計 — 独立 verifier の結果 schema（R66）

2026-09-21。残件 R66（[`fail-closed-reads.md`](fail-closed-reads.md) §4）。
CLI の現状は [`evidence-verifier-release.md`](../operations/evidence-verifier-release.md)。

**これは設計であって実装ではない。** schema ファイルも、それを検証する錠もまだ無い。

---

## 0. 何が問題か

R66 は「書けるようになる条件: 段階レポートの語彙が固まること（R64 の配線が決まると変わる）」
としていた。**R64 は取り下げで閉じた**ので、語彙は固まっている。CLI の `--json` は
`Verify.asJson` が手組みで出す**固定の 4 キー**で、受け取る側は今それを目視で読んでいる。

| 出力（コードで確認） | 型 | 出所 |
|---|---|---|
| `profile` | STRING | `--profile` の値 |
| `verdict` | STRING: `VERIFIED` / `FAILED` / `INDETERMINATE` | `Outcome.Verdict` |
| `checks[]` | LIST of MAP | 各検査 |
| `checks[].name` | STRING | 検査名（各 verifier クラスの `REQUIRED` と同じ語彙） |
| `checks[].outcome` | STRING: `PASSED` / `FAILED` / `NOT_PRESENT` / `UNAVAILABLE` | `Outcome.Check` |
| `checks[].reasonCode` | STRING、**省略可** | `UNAVAILABLE` のときだけ在る |
| `checks[].detail` | STRING、**省略可** | 人向けの文 |
| `limits` | STRING | `Verify.LIMITS`。**JSON にも入っている**（コードで確認。運用文書の記述はコードどおり） |

---

## 1. 決めたこと

### 1.1 置き場所と版

`docs/evidence-profile/v1/verifier-result.schema.json`。ベクタと同じ場所 —
**受け取る側が持っていくもの**は 1 か所に置く（計画 §10）。`$id` は
`https://nemakiware.org/evidence-profile/v1/verifier-result`。版は URL の `v1` で持ち、
JSON Schema の draft は **2020-12**。

### 1.2 形 — 閉じた schema

- トップは `additionalProperties: false`、`required: [profile, verdict, checks, limits]`
- `verdict` と `checks[].outcome` は **enum**（上の値以外を拒否）
- `checks[].name` は **enum にしない**。profile ごとに検査名が増えるのは正常な進化で、
  schema が名前を固定すると verifier の版と schema の版が毎回同時に動く。代わりに
  「空でない STRING」
- `checks[].reasonCode` は **enum にする**。理由コードは受け取る側が分岐する値であり、
  自由文にすると `REVOCATION_NOT_REQUIRED` と `REVOCATION_NOT_REQUIRE` が別物になる。
  値は**ソースから導出**する（`unavailable("…", "REASON", …)` の第 2 引数を grep）— 手で
  写した表は必ず古びる（この木で数字が 3 度古びた）
- `limits` は `minLength: 1`。**空の limits を通す schema は、限界文を落とした CLI を通す**
- `checks` の**順序に意味を持たせない**。CLI が出す順は実装の都合であって契約ではない、
  と schema の `description` に書く

### 1.3 verifier は schema を**読まない**

CLI に schema 検証を組み込まない。verifier の依存は BouncyCastle だけ（錠が pom を読む）で、
JSON Schema validator を足すと**受け取る側が信頼するものが 1 つ増える**。
schema は受け取る側が**自分の**道具で検証するためのもの。

### 1.4 錠 — 出力が schema に**適合する**ことを、依存を足さずに測る

`~/.m2` に JSON Schema validator は無い（オフライン）。**足さない**。
代わりに錠は「schema が要求すること」を**自前で**検査する:

- `Verify.asJson` の出力を Jackson で読み、4 キー・enum・`limits` 非空・
  `additionalProperties` 無しを assert
- **schema ファイルの `enum` 配列**と、ソースから導出した `reasonCode` の集合が**一致**する
  （両方向 — schema にだけ在る値も、ソースにだけ在る値も落とす）
- schema の `required` と `asJson` が常に出すキーが一致する

これは「validator 相当を手で書く」のではなく「schema と実装が**同じ集合を指す**ことを
測る」錠。validator が無くても、schema が実装から乖離したら赤になる。

---

## 2. 決めていないこと

| 未決 | なぜここで決めないか |
|---|---|
| `detail` を契約に含めるか | 今は人向けの文。機械が分岐するなら `reasonCode` に寄せるべきで、`detail` に依存する受け取り側を作らないため**明示的に「契約外」と書く**方に傾いているが、受け取る側の要望を聞いていない |
| schema の**署名** | Phase 5 の detached signature と同じ問題（鍵を持っていない）。schema も jar と同じ `SHA-256SUMS` に載せる、までは決める |
| `profile` を enum にするか | v1 は 6 profile 固定だが、未知の profile 名は CLI が usage error（exit 4）で拒否するので、schema で二重に閉じる必要は無い。**閉じない**方に傾いている |

---

## 3. 錠と control

- **集合一致の錠**（1.4）: schema の `reasonCode` enum ＝ ソースの理由コード集合
- **出力適合の錠**: 6 profile × 代表的な package で `asJson` を読み、schema の要求を満たす
- **control**: schema から `limits` の `required` を外す細工／`reasonCode` を 1 つ schema に
  足すだけの細工（ソースに無い理由が契約に入る）／`asJson` が `limits` を落とす細工

---

## 4. この設計が主張しないこと

- schema に適合する出力が**正しい** verdict であるとは言わない。形の契約であって、意味の契約ではない
- 受け取る側が schema で検証すれば安全、とは言わない。**verifier の jar 自体の真正性**は
  `SHA-256SUMS.asc` の話で、schema はそれを代替しない
