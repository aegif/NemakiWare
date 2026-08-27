# P2-3 — 長期有効性 (第 1 段: アルゴリズム失効レジストリ)

作成 2026-08-25。ロードマップ §4 Phase 2 の 4 番目。前提は P2-0〜P2-2。

---

## 0. 第 1 段が実装するのは ERS ではない (第 2 段で実装した)

**この節は第 1 段 (2026-08-25) について書かれている。** 第 2 段 (2026-08-26) で
**RFC 4998 の生成と検証を実装した** — §8 を読むこと。data object は checkpoint の
正規化バイト列であって文書ではなく、TSA 署名は検証していない。

第 1 段が実装したのは、**ERS が「本文書の範囲外」と明記して肩代わりしてくれない部分**である。

RFC 4998 は renewal の 2 種を定義するが、**いつ renewal が必要になるかは
out-of-band で監視せよ**と書いている (ロードマップ §9-5)。つまり:

> **ERS を採用しても、「そろそろ危ない」と気づく仕組みは自分で持つ必要がある。**

そして気づけなければ、renewal の実装があっても**発火しない**。
順序として、監視が先である。

---

## 1. renewal 2 種は別物 — 「再タイムスタンプ」で潰さない

| 種類 | 発火条件 | 元データ | 何に被せるか |
|---|---|---|---|
| **Timestamp Renewal** | TSU 秘密鍵の危殆化、または**タイムスタンプ生成に使った**アルゴリズムの失効 | **不要** | 既存トークンの上 |
| **Hash-Tree Renewal** | **ハッシュツリー構築に使った**ハッシュの失効 | **必要** | ツリーを作り直す |

この違いは費用と実行可能性に直結する。hash-tree renewal は
**保存された全データを読み直す**ので、fixity 全走査と同じ規模の作業になる
(P1-2 §0 と同じ理由でスケジュールを既定 on にしていない)。
一方 timestamp renewal はトークンだけで済む。

**「再タイムスタンプが要る」と 1 語で言うと、要らない方の費用まで見積もることになる。**

---

## 2. 何を「失効」と呼ぶか — 判定しない、宣言する

暗号アルゴリズムの安全性は**この製品が判定できるものではない**。
できるのは、**運用者が宣言した表を持ち、それに照らして機械的に答える**ことである。

したがってレジストリは:

- **宣言的**。「SHA-1 は 2030-01-01 以降 unsound と扱う」という**運用者の判断**を持つ
- **既定値は保守的だが、権威ではない**。同梱の既定は NIST SP 800-131A / BSI TR-02102
  等の公開勧告を**参照して書いた出発点**であって、**当社が安全性を保証するものではない**
- **日付つき**。「今は sound、この日から unsound」を表現できないと、
  「いつまでに renewal すべきか」が言えない

### 3 値であって boolean ではない

| 値 | 意味 |
|---|---|
| `SOUND` | 宣言表で健全とされている |
| `DEPRECATED` | **移行期間中**。新規使用は避けるが、既存の証拠は直ちに無効ではない |
| `UNSOUND` | 表の失効日を過ぎた |

`DEPRECATED` を落として boolean にすると、**移行の猶予が消える**。
「まだ使える」と「もう使えない」の間に、**計画を立てる期間**が要る。

**未知のアルゴリズムは `SOUND` にしない。** 表に無いものは
`UNKNOWN` として報告し、**健全とも失効とも言わない**。表に無いものを健全と
みなす既定は、表を更新し忘れた瞬間に全部を健全と report する。

---

## 3. 何を評価するか

| 対象 | 使っているアルゴリズム | 失効したら要る renewal |
|---|---|---|
| evidence ledger の entryHash / Merkle | SHA-256 (`MerkleTree`) | **hash-tree renewal** (元データ必要) |
| RFC 3161 トークンの **imprint** | 受領証の `digestAlgorithm` 属性 | **hash-tree renewal** (値を再ハッシュするので元データが要る) |
| RFC 3161 トークンの**署名** | **どの段も記録していない** | timestamp renewal — **評価できない**。§5.5 参照 |
| OpenTimestamps の commitment | SHA-256 (プロトコル既定) | **hash-tree renewal 相当**。ただし**再アンカーは新しい時刻しか証明しない** — §4 |
| `nemaki:contentHash` (P1-2) | SHA-256 | 再計算と再記録。**元の取得時刻は証明し直せない** |

---

## 4. renewal が回復できないもの

**renewal は時間を巻き戻さない。** SHA-256 が破れた後に打ち直した
タイムスタンプが証明するのは「**打ち直した時点**でこの値が存在した」であって、
元の記録時刻ではない。だから renewal は**破れる前に**打つ必要がある。

> **これが、監視を先に作る理由である。** 気づいたときには手遅れ、という
> 失敗の形が構造的に存在する。

レポート (P1-4) はこれを言う責任がある。「renewal 済み」は
「元の時刻が今も証明できる」を意味しない。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | 表に無いアルゴリズムは `UNKNOWN` であって `SOUND` ではない | 既定を SOUND にすると落ちる |
| 2 | `DEPRECATED` が `SOUND` にも `UNSOUND` にも潰れない | 2 値にすると落ちる |
| 3 | 失効日の前後で判定が変わる | 日付を無視すると落ちる |
| 4 | timestamp renewal と hash-tree renewal が別々に報告される | 1 語にすると落ちる |
| 5 | 既定表が「当社の保証ではない」と述べる | 落とすと落ちる |
| 6 | 評価結果は「renewal すれば元の時刻が救われる」と読ませない | 但し書きを落とすと落ちる |

---

## 5.5. 実装 (2026-08-25)

| 物 | 場所 |
|---|---|
| 宣言表 | `evidence/validity/AlgorithmRegistry.java` |
| renewal 種別 | `evidence/validity/RenewalNeed.java` |
| 評価 | `evidence/validity/LongTermValidityService.java` |
| API | `GET /core/api/v1/admin/anchor/long-term-validity?repositoryId=&asOf=` |
| テスト | `LongTermValidityTest` (13) |

`asOf` は**未来を聞くための引数**である。「今どうか」だけ答えられても、
renewal は破れた後では元の時刻を救えない (§4) ので手遅れを確認するだけになる。

**綴りは正規化する** (`sha256` / `SHA-256` / `SHA_256` は同じ)。OID 由来の名前と
手書きの名前はちょうどこれだけ違い、取り違えると `UNKNOWN` が返る —
「言えなかった」と読めるが、実際は「別の綴りで言ってある」。

**DEPRECATED は既に「要る」と数える。** UNSOUND を待って動くのは破れた後に
動くことで、そのとき打ち直したトークンが証明するのは打ち直した時刻である。

負のコントロール **6 本実測** (V1 未登録を SOUND / V2 DEPRECATED を潰す /
V3 境界日を除外 / V4 tree renewal を timestamp と呼ぶ / V5 DEPRECATED を
未着手扱い / V6 未配線 store を「0 件」と報告)。

### 初稿の timestamp renewal は二重に死んでいた (2026-08-25 訂正)

レビュー 3 者が独立に指摘。`timestampRenewalsDue` は**構造的に常に 0**だった:

1. `pending()` (PENDING 行しか index しない) を引いてから `!= CONFIRMED` を
   `continue` していたので、ループ本体に到達しない
2. 仮に直しても、探していた `signatureAlgorithm` は**どの段も書いていない**
   (`Rfc3161AnchorTarget` が書くのは `digestAlgorithm`)

`confirmed()` を store に足し (専用 view)、属性名を実在するものに直した。

**さらにもう 1 段の誤りがあった (2 巡目レビュー)。** `digestAlgorithm` は
**message imprint** のハッシュであって、トークンに**署名**したアルゴリズムではない。
RFC 4998 の timestamp renewal が発火するのは後者で、**それはどの段も記録していない**。
imprint の失効は値の再ハッシュ = **hash-tree renewal (高価な方)** である。

したがって:

- imprint は `kindForTree` に載せ、理由文も「imprint の」と書く
  (初稿の production 文言は "the token's own signature algorithm" のままだった)
- `timestampRenewalsDue` は**構造的に 0 のまま**である。**0 を黙って出さず**、
  応答に「署名アルゴリズムを記録している段が無いので評価していない。この 0 は
  『問題なし』ではなく『見ていない』である」と書く
- 受領証の走査は 1000 件で打ち切るが、**打ち切ったことを応答に書く**
  (`receiptsTruncated`)。黙って切った集計は「全部を見た」と読まれる
**§5 AC4 の「別々に報告される」は片側しか測っていなかった** —
確定済みトークンを本番の列挙経路に通す判別テストを足し、
fixture は定数ではなく**実在のリテラル**を使う (定数を両側に使うと同語反復に
なる。実際に一度そう書いて落ちなかった)。加えて**生産側がそのリテラルを
書くこと**をソース走査で別途固定した。

---

## 6. やらないこと (この増分では)

- **ERS の生成・検証** (RFC 4998 ASN.1 / RFC 6283 XML) — **形式は §7 で決めた
  (2026-08-26)。生成・検証は依然として未実装。**
- **自動 renewal** — hash-tree renewal は全データ読み直しで、fixity 全走査と同じ規模。
  既定 on にできる根拠がまだ無い (P1-2 §0 と同じ)
- **アルゴリズム安全性の判定** — §2。宣言を持つだけ
- **外部レジストリの取り込み** (NIST/BSI の機械可読フィード) — 取り込み先の
  可用性と版固定が別途要る

---

## 7. ERS の形式を決める (2026-08-26)

§6 が「P3-1 の同梱要件と一緒に決める」として保留していた問いに、
P3-1 が CSIP 2.2.0 を選んだので答えられるようになった。

**採る: RFC 4998 (ASN.1/CMS)。** 実装は `ErsFormat`。

両者は同じ evidence record の 2 つの符号化で、どちらかが上位集合ということはない。
判断材料は「既にパッケージに入っているもの」と「受け側が読めそうなもの」である。

| 論点 | RFC 4998 (採用) | RFC 6283 (不採用) |
|---|---|---|
| 既存トークンとの符号化 | **RFC 3161 のトークンは CMS SignedData そのもの**。同じ符号化で包める | ASN.1 を base64 で XML に戻すことになり、**符号化の境界が 1 つ増える** |
| 実装の存在 | アーカイブ用タイムスタンプ製品が実際に出荷している | 仕様は在るが実装が乏しい。**受け側が読めない形式は相互運用ではない** |
| CSIP との相性 | パッケージは METS+XML だが、**マニフェストが XML であることは参照先の中身までは及ばない** (`mdRef` が外部ファイルを指すだけ)。**2026-08-27 訂正**: ここには「保存オブジェクトとして置かれる」と 書いていたが、現在は `dmdSec` から参照する形で `metadata/other/ers.der` に置いており、RODA は AIP で `metadata/descriptive/` へ移す。CSIP32 が `digiprovMD` を PREMIS の枠と定めているためで、経緯は [`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §11 | — |

**却下側の利点も書いておく**: XMLERS なら ASN.1 デコーダ無しで構造が読め、
パッケージの他の中身と同じように検分できる。**これは本物の損失**であり、
上の 3 点と引き換えにしている。

### 置き場所

`metadata/other` (**2026-08-27 変更**)。

当初は `metadata/preservation` — 「evidence record は保存メタデータであって記述メタデータでも
documentation でもない」— としていた。**これは OAIS の分類を CSIP のディレクトリに載せた
読み違いだった。ただし間違っていたのはフォルダではない。**

フォルダを決めているのは exporter が呼ぶ API で、`addPreservationMetadata` は METS の
`<amdSec><digiprovMD>` に宣言を書く。**CSIP32 が preservation 情報に PREMIS を使うと
述べているのがその枠**なので、ASN.1 の DER をそこに宣言していたのが defect である。

**「フォルダは SHOULD にすぎず CSIP32 が拘束する」という論の立て方はしない** —
CSIP32 も CSIPSTR6 と同じ **SHOULD** (`0..n`) で、しかも「PREMIS ⇒ `digiprovMD` 1 つ」の
片方向しか書いていない。**規格違反ではなく、PREMIS のための枠に PREMIS でないものを
載せた**、が言える範囲である ([`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §11)。

実測で浮いた: そこに置いた SIP を RODA 6.3.0 に投げると、`digiprovMD` の中身を
`PremisV3Utils.binaryToGenericPremis` に通そうとして `Failed to load PREMIS` になり、
**package ごと rollback** する。
`metadata/other` へ移すと取り込まれ、記録も残った (RODA 側で `metadata/descriptive/` へ
移されて)。**測ったのはスタブの DER で、本物の RFC 3161 ベース ERS では測っていない。**経緯と対照は
[`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §10 追試 1 / §11。

**1 か所で決めておく方針は変えていない**が、`ErsFormat.CSIP_LOCATION` は位置を
*記述*しているだけで、*決めて*いるのは exporter が `addPreservationMetadata` と
`addOtherMetadata` のどちらを呼ぶかである。**変えるときは両方**。

### 何も生成していない

**この宣言は決定であって実装ではない。** それでも書く価値があるのは、
renewal monitor が既に在るからである — `RenewalNeed` は「そろそろ危ない」と言えて
**「何に renew するのか」を言えなかった**。行動できない警報は半分の警報である。

名前の隣には必ず `ErsFormat.LIMITS`「この製品は生成も検証もしない」が付いて回る
(応答の同じ map に入ることをテストで固定した)。**標準の名前を出す製品は
実装しているものとして読まれる**ので、そこは構造で守る。

負のコントロール 2 本実測 (何も要らないときにも形式を名乗る / 但し書きを map から落とす)。

---

## 8. RFC 4998 evidence record の生成と検証 (2026-08-26)

§7 で形式を決めた。ここで作った。`ErsRecord` (生成・DER 往復) と `ErsVerifier` (検証)。

### data object は checkpoint の**正規化バイト列**であって checkpoint hash ではない

**2026-08-26 訂正 (Codex 指摘・RFC 本文で確認)。** 最初の実装はここを取り違えており、
その結果**どの標準ツールでも読めない記録**を出していた。自前の検証器が同じ取り違えを
していたので、テストは緑だった。

RFC 4998 §4.3 は検証器に **`h = H(d)` を計算させ、最初のハッシュリストに `h` が
在ることを確かめさせる**。checkpoint hash `C` を「data object」と呼びながら
リストに `C` を入れると、検証器は `H(C)` を探して `C` を見つけ、
**token を見る前に**落とす。

正しくはもっと単純だった。`C` は既に `H(checkpoint の正規化バイト列)` であり、
本製品の RFC 3161 アンカーは **message imprint がちょうど `C` の token** である。
つまり data object はその正規化バイト列で、`h = C`。そして **reducedHashtree は
要らない** — RFC 4998 §4.2 が明示的に許している:「An Archive Timestamp may consist
... only of a timestamp with no hash value lists」。§4.3 は
「root hash value must correspond to hashedMessage」に縮退し、root は `h` そのもの。

この形は**適合し、かつ既存アンカーをそのまま使える**。代案 —— `H(C)` を 1 ノードの
木に入れる —— は `H(H(C))` を覆う**新しい token** が要る。§4.3 step 3 は
要素が 1 つでもリストを hash するからである (単一要素の例外は RFC に無い。本文で確認)。

**採らなかった案**: entry を RFC 4998 の規則で縮約する。checkpoint ごとに
2 つ目の root と 2 つ目のアンカーが要る (手元の token は RFC 6962 root ではなく
checkpoint hash を覆っている)。アンカー設計の変更であり、ここでは採らない。

### 更新は §5.2 と §5.3 の別物である

**timestamp renewal (§5.2)**: 古い `timeStamp` フィールドの**内容**が hash され
タイムスタンプされる。「The new Archive Timestamp MAY not contain a reducedHashtree
field, if the timestamp only simply covers the previous timestamp」— したがって
reducedHashtree は作らず、新 token の imprint は `H(直前の ContentInfo DER)`。
**同じ鎖**に留まり、**同じアルゴリズム**を使う。

**hash-tree renewal (§5.3)**: data object と**過去の全鎖**を新アルゴリズムで hash する。
`ha = H(直前までの ArchiveTimeStampSequence の DER)`、
`h' = H(sorted concat(h(d), ha))`、新 Archive Timestamp の第 1 リストが `h'` を持ち、
**新しい鎖**を始める。`ha` の項が無いと、新しい鎖は古い鎖に**コミットしていない** ——
横に並べただけの無関係なタイムスタンプになる。最初の実装が出していたのはそれだった。

**何を覆う token が要るかは製品側が計算して渡す** (`imprintForFirst` /
`imprintForTimestampRenewal` / `inputsForHashTreeRenewal`)。ここを呼び手に委ねると、
正しく見えてどの標準ツールも受け付けない記録が出る — 一度出した。

### それでも作る価値 — 鎖が本体

素の RFC 3161 token は 1 度 1 つのことを証明する。`ArchiveTimeStampChain` は
**更新が積み上がる場所**で、それこそ `RenewalNeed` が要求しているもの。
`TIMESTAMP_RENEWAL` は現在の鎖に追加し、`HASH_TREE_RENEWAL` は新しい鎖を始める
(digest algorithm が変わったのに同じ鎖に足すと「新しいアルゴリズムを最初から
使っていた」と言うことになる)。

### 検証が言わないこと

**TSA の署名・証明書鎖・失効状態は検証していない。** trust anchor を 1 つも持たないから。
だから結果は `valid` ではなく **`linksHold`** と呼ぶ。内部の結び付きが保たれている、
という意味しかない。`ErsVerifier.NOT_CHECKED` がそう書き、`asMap()` は
**limits と notChecked を先頭に**置く (判定に見える語より先に読ませる)。

### 実 TSA での測定

テストは stub トークンを使わない。鍵・自己署名証明書 (timeStamping EKU)・
BouncyCastle の token generator で**本物の RFC 3161 トークン**を発行させ、
message imprint はこちらが選んでいない値になる。信頼された TSA では**ない** —
それが後半のテストの主題で、誰も保証していない証明書で「検証済み」と言わないことを測る。

| 壊した箇所 | 落ちたテスト |
|---|---|
| 最初の imprint をもう一度 hash する (旧実装の形) | `theFirstTokenCoversTheDataObjectHash` ほか 4 |
| 更新の imprint を直前 token 以外にする | `timestampRenewalCoversThePreviousToken` |
| 新しい鎖と古い鎖の関係を検査しない | `anUnrelatedNewChainIsCaught` |
| `ha` を別の入力の上で計算する | `hashTreeRenewalCommitsToTheOldChains` |
| 期待値との突き合わせを外す | `aRecordAboutAnotherPartysDataIsRefused` |
| limits を先頭から動かす | `theUncheckedPartIsSaidFirst` |

> 旧実装が出していた形 (`H(H(d))` を覆う token) は
> `theOldWrongInterpretationIsCaught` が**明示的に拒否**する。
> 自前の検証器が自前の誤りに同意していたのが元の欠陥なので、
> 誤った形を名指しで落とすテストを残す。

### 縮約木の歩き方 (2026-08-26、3 巡目で訂正)

**計算した親は次のリストに「入っている」のではなく、次のリストに「加えて」ハッシュする。**
RFC 4998 §4.3 step 3:「This hash value h' MUST become a member of the next higher list」。

RFC 自身の例 (§4.2 Figure 2) で確かめる:

```
pht1 = SEQ (h2abc, h1)      h1 = H(d1)
pht2 = SEQ (h3)
h12  = H(sorted(h2abc, h1))          ← pht1 から計算
h123 = H(sorted(h12, h3))            ← h12 を pht2 に「加えて」ハッシュ
```

`h12` は **pht2 のどこにも保存されていない**。縮約木が保存するのは各段の**兄弟**だけである。

2 巡目の実装は「親が次のリストに保存されていること」を要求していた。これは
**標準の記録を全部拒否し、本製品が書いていた形だけを受理する** — また
エンコーダと検証器が互いに同意していた。テストの「well-formed」fixture も
同じ非標準形だったので緑だった。RFC の例そのものを fixture にした。

### 「検査できなかった」を型で持たせた

`UncheckableLinkException` は `linksHold=false` にしていたが、`asMap()` の出力は
boolean と件数しか無かったので、**呼び手が新アルゴリズムの H(d) を渡さなかっただけの
健全な記録が、壊れた記録と機械的に区別できなかった**。散文は正直でも、
構造化された判定のほうが強く、しかも逆を言っていた。

`TimestampResult.Status` を 3 値 (`MATCHES` / `DOES_NOT_MATCH` / `NOT_CHECKED`) にし、
`timestampsNotChecked` を報告に足した。検査できなかった位置は
**checked に数えない** (数えると「見ていないものを見た」と言うことになる)。

| 壊した箇所 | 落ちたテスト |
|---|---|
| 親が次のリストに保存されている必要がある形に戻す | `theRfcsOwnExampleVerifies` |
| 第 1 リストの所属検査を外す | `theDataObjectMustBeInTheFirstList` ほか 1 |
| 未検査を DOES_NOT_MATCH にする | `anUncheckableLinkIsNotClaimed` |
| 未検査を checked に数える | 同上 |

---

## 9. 生成の自動化と SIP への同梱 (2026-08-26)

### 新しくタイムスタンプは取らない

§8 の帰結。checkpoint hash は **checkpoint の正規化バイト列の SHA-256** であり、
本製品の RFC 3161 アンカーは **message imprint がちょうどその値の token** である。
だから evidence record は**既に在るものの組み立て**であって、
2 度目の TSA 往復も 2 つ目のアンカーも要らず、**新しい主張も生まれない**。

裏返すと、**配備が持っていないものは作れない**。CONFIRMED な RFC 3161 受領証が
無い checkpoint には evidence record が無く、それは「アンカーが無い」という
**配備についての言明**であって、checkpoint が覆う記録についての言明ではない。
OpenTimestamps 受領証は代用にならない (RFC 4998 の timestamp は RFC 3161 token である)。

### 取り違えを防ぐ検査

アンカー層は呼び手から hex digest を受け取る設計で、**merkle root も手元にある**。
間違ったほうの上の token を包むと、**内部的には検証できて別のものについての記録**が
出来る — 検証できるので信じられる。

**受領証のフィールドだけを読んでいたのは不足だった** (2026-08-26 訂正・レビュー指摘)。
`anchoredDigest` は**こちらが何を頼んだかについての自分のメモ**で、
imprint は**当局が実際に署名した対象**である。別の事実である。
警戒していた取り違え —「フィールドは checkpoint、proof は merkle root」— は
まさにフィールドだけ読むと素通りする組み合わせだった。

いまは 2 段階で見る: 安い診断としてフィールドを見て、そのあと
**proof を parse して token の `hashedMessage` を checkpoint hash と突き合わせる**。

さらに、**組み立てたものを `ErsVerifier` に通してから返す**。組み立ては安く、
標準ツールが落とす記録を他組織へ送るのは高い。「exporter から出てきた」は
受け取る側が受理する理由にならない。

| 壊した箇所 | 落ちたテスト |
|---|---|
| フィールドだけ読んで token を見ない | `theTokenIsWhatCounts` |
| 読めない token から組み立てる | `anUnreadableTokenIsNotAFinding` |
| 組み立てたものを検証せずに返す | `anUnverifiableRecordIsNotShipped` |
checkpoint 行自体が自己検証しないときも組み立てない
(壊れた root を標準の容器に入れると見栄えが良くなるだけである)。

### SIP への同梱

`metadata/other/ers.der` — `ErsFormat.CSIP_LOCATION` が宣言している位置。
**`digiprovMD` からは参照しない**: CSIP32 がそこを PREMIS の枠と定めており、
DER を宣言すると少なくとも 1 つの受け手 (RODA 6.3.0) が package ごと拒否する
(上記「置き場所」と [`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §11)。

**受け手が誤読しないように**: `ers.der` は記録の隣に在るが、その data object は
**checkpoint** であって隣の文書ではない。evidence package の `evidenceRecord` 節と
`ErsRecord.LIMITS` の両方がそう書く。

| 壊した箇所 | 落ちたテスト |
|---|---|
| token の imprint を checkpoint hash と照合しない | `aTokenAboutSomethingElseIsRefused` |
| OpenTimestamps 受領証を代用にする | `otsDoesNotSubstitute` |
| 自己検証しない checkpoint の上に組み立てる | `anEditedCheckpointIsNotDressedUp` |

