# 設計 — 独立 verifier の結果 schema（R66）

2026-09-21。残件 R66（[`fail-closed-reads.md`](fail-closed-reads.md) §4）。
CLI の現状は [`evidence-verifier-release.md`](../operations/evidence-verifier-release.md)。

**2026-09-22 に実装した。** schema は `docs/evidence-profile/v1/verifier-result.schema.json`、錠は `TheExitCodeIsTheInterfaceTest`（cli: 出力の schema 適合）と `TheReasonCodeIsARegistryTest`（core: 登録簿 ↔ schema、構築時 guard — **登録簿の在る module に置く**。cli のテストは install 済み core jar を読むので、core の細工は cli の錠に届かない）。以下の「決めたこと」は実装に写し、「決めていないこと」は所有者が 2026-09-21 に決めた（§2 に回答を記す）。

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
  値は **`Outcome.Check.REASON_CODES` という登録簿**から取る — `REASON_CODES`（23 値）。**初版は grep で導出して 5 つを落とした**
  — 第 1 引数が変数の呼び出しと、第 2 引数がメソッド呼び出し（`refusal.reasonCode()`）を拾えなかった。
  grep は「コードがどう書かれているか」の推測であり、constructor はそうではない。**未登録の理由は構築時に拒否**する
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

- `Verify.asJson` の出力を **verifier 自身の `Json.parse`** で読み（cli の pom に Jackson は無い。この parser は整数のみ・重複 key 拒否・深さ 64 で、schema はその範囲内）、
  **schema の語彙を再帰的に評価する小さな validator** で判定する。**適用は JSON Schema どおりインスタンス駆動**（値が object なら required / properties / additionalProperties、array なら items / minItems、string なら minLength — `type` の有無とは独立）。初版は `type` の枝の中でしか評価せず、`type` を持たない `if` / `then` / `else` / `not` が**全部 no-op**だった（2 つの誤りが打ち消し合って緑。両レビュー P1）。**実装していないキーワードは throw**する — 読めなかった制約を「制約なし」と報告しない
- **schema ファイルの `enum` 配列**と、登録簿 `Outcome.Check.REASON_CODES` が**一致**する
  （両方向 — schema にだけ在る値も、登録簿にだけ在る値も落とす）
- schema の `required` のキーを `asJson` が常に出す（片方向。逆は閉じた schema の `additionalProperties` が担う）

これは「validator 相当を手で書く」のではなく「schema と実装が**同じ集合を指す**ことを
測る」錠。validator が無くても、schema が実装から乖離したら赤になる。

---

## 2. 決めていないこと

| 未決 | なぜここで決めないか |
|---|---|
| `detail` を契約に含めるか | **決定（2026-09-21）: 契約外。** 機械の分岐は `reasonCode` |
| schema の**署名** | **決定: 今はしない**（鍵が無い）。**`SHA-256SUMS` に載せる**、まで |
| `profile` を enum にするか | **決定: enum にしない。** 未知の profile は CLI が exit 4 で先に拒否する |

---

## 3. 錠と control

- **集合一致の錠**（1.4）: schema の `reasonCode` enum ＝ 登録簿 `REASON_CODES`。**登録簿の錠**（core）: 未登録の理由と UNAVAILABLE 以外の理由は構築時に拒否、`PackageReader.Refusal` は全部登録済み
- **出力適合の錠**: `Verify.KNOWN_PROFILES` の**全 profile × 正常 package**（要求した profile の echo も確認）＋ **refusal ごとに 1 package**（非 zip / `../` / 重複エントリ / エントリ過多）。**期待する reasonCode が出力に現れたこと**を assert する — 「分岐を通した」を測る（初版の「6 × 2 = 12 出力」は非 zip が profile 分岐の手前で終わるので 1 ケースの 6 回で、通った code は 2 / 17 だった）。判別の錠: UNAVAILABLE に理由無し／PASSED に理由有りを schema の `items` に通して拒否されること
- **control**: LP3 schema から `limits` の `required` を外す／LQ3 schema にだけ code を足す／LR3 `asJson` が `limits` を落とす／LS3 登録簿から実在の code を落とす／LT3 PASSED+理由の guard を外す／LU3 登録簿にだけ code を足す／**LV3 `asJson` が `reasonCode` を落とす**（refusal の fixture が期待 code を見失って落ちる。`if/then` の生存を単独で測るのは LX3）／**LW3 未登録拒否の guard を外す**／**LX3 schema の `then` を空にする**

---

## 4. この設計が主張しないこと

- schema に適合する出力が**正しい** verdict であるとは言わない。形の契約であって、意味の契約ではない
- 受け取る側が schema で検証すれば安全、とは言わない。**verifier の jar 自体の真正性**は
  `SHA-256SUMS.asc` の話で、schema はそれを代替しない
