# Phase 0 の棚卸し — content を書く経路 / mapper / anchor

2026-09-19。`BASE_SHA` = `df6c1f2ad`。
計画は [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md)。

この文書が決めるのは **E1 の対象範囲**である。計画 §8 が
「Phase 0 で経路を列挙し、**列挙できたものだけ**を対象にする。列挙できなかった経路は
残件 1 行であり、成功扱いにしない」と定めているので、ここに書いていない経路について
「記録した」とは後段のどこでも書かない。

**読んで確かめた結果だけを書く。** 台帳の文言ではなくコードを根拠にする、という
計画 §0 の指示に従っている。

---

## 1. content bytes を書く経路

> **初版の表は誤っていた。** 「最下層は `createAttachmentAtomic` と `copyAttachmentAtomic`
> の 2 つだけ」と書いたが、**`checkIn` / `updateWithoutCheckInOut` は atomic 版を通さず
> `createAttachment` を直接呼び、その場書き換えの経路は 3 つ目の最下層
> （`contentDaoService.updateAttachment`）を使う**。確認レビューが指摘し、数え直したら
> **表に無い経路が 1 本出た**（W9 `replacePwc`）。E1 をこの表どおりに実装していたら
> W4 / W5 / W9 は計測を迂回していた。以下は数え直した後の表である。

最下層は **3 つ**あり、`ContentServiceImpl` の wrapper（`…Atomic`）は**常に通るわけではない**。

- `createAttachment(...)` — 新しい行に bytes を書く（wrapper: `createAttachmentAtomic`）
- `copyAttachment(...)` — 既存の attachment を新しい行に複製する
  （wrapper: `copyAttachmentAtomic` / 拒否付き: `copyAttachmentOrRefuse`）
- `contentDaoService.updateAttachment(...)` — **同じ行の bytes をその場で書き換える**（wrapper 無し）

各呼び出しの囲みメソッドは行番号から機械的に引いた（目視の帰属はしていない）。

| # | bytes を書くサービス API | 最下層（行） | 入口 | 版の扱い |
|---|---|---|---|---|
| W1 | `createDocument` | `createAttachmentAtomic`（:1186） | `ObjectServiceImpl.createDocument`、`Patch_InitialContentSetup`（2 か所） | 新規 |
| W2 | `createDocumentWithNewStream` | `createAttachmentAtomic`（:1414） | `ObjectServiceImpl.setContentStream` | 新しい版を作る側 |
| W3 | `updateDocumentWithNewStream` | **`updateAttachment`（:1591、その場）** / 内容が無かった文書には `createAttachmentAtomic`（:1594） | `ObjectServiceImpl.setContentStream` | 既存の版を書き換える側 |
| W4 | `checkIn` | stream 無し → `copyAttachmentOrRefuse`（:1844） / stream 有り → **`createAttachment`（:1847、atomic ではない）** | `VersioningServiceImpl.checkIn` | PWC を版にする |
| W5 | `updateWithoutCheckInOut` | **`createAttachment`（:1925、atomic ではない）** | `BulkCheckInResource`（REST） | checkOut を経ない更新 |
| W6 | `createDocumentFromSource` | `copyAttachmentAtomic`（:1316） | `ObjectServiceImpl.createDocumentFromSource` | 複製 |
| W7 | `appendAttachment` | **`updateAttachment`（:4571、その場）** | `ObjectServiceImpl.appendContentStream` | 追記 |
| W8 | `checkOut`（PWC 作成） | `copyAttachmentOrRefuse`（:1665） | `VersioningServiceImpl.checkOut` | PWC へ複製 |
| **W9** | **`replacePwc`** | **`updateAttachment`（:1488、その場）** | `ObjectServiceImpl.setContentStream`（対象が PWC のとき、:799） | PWC の内容差し替え |
| **W10** | **アーカイブ（削除）** | `ArchiveDaoDelegate.createAttachmentArchive`（:481。bytes を **archive DB へ複製**、:545） | `deleteContentStream` / `deleteDocument` → `deleteAttachment`（`ContentServiceImpl:3601` / :3788） | 本番からは消え、archive に残る |
| **W11** | **復元** | `ArchiveDaoDelegate.restoreAttachment`（:714。attachment 行を作り直し、:792 で **本番へ body を PUT**） | `ContentServiceImpl.restoreArchive`（:4805）/ `restoreArchiveGuarded` | bytes が本番に戻る |
| **W12** | **cold 移送**（COPY / MOVE） | `RetentionScheduler.moveToCold` → `LongTermStorageAdapter.put`（:599。**外部保管へ bytes を書く**）。MOVE なら `contentService.deleteArchiveContent`（:666 → `ArchiveDaoDelegate:1133`）で**ローカルの bytes を消す** | 保持ポリシーのスケジューラ | COPY は二重化、MOVE は所在の移動 |

**外部取込は独自の書き込み経路を持たない。** `CanonicalImportServiceImpl` は
`versioningService.checkIn`（2 か所）と `objectService.createDocument`（1 か所）を呼ぶので、
W1 と W4 に合流する。取込のために E1 を別に作る必要はない。

**その場書き換えは W3 / W7 / W9 の 3 本**である。

> **W12 は 3 巡目で 2 名が別々に指摘して足した。** 判定基準を「`createAttachment` /
> `copyAttachment` / `updateAttachment` のどれも通らない」に広げた結果として W10 / W11 を
> 足したのに、**同じ基準に当てはまる cold 移送を見落とした**。`deleteContentStream` を対象に
> した理由（「台帳の最後の statement がもう存在しない bytes を指したままになる」）は
> MOVE にもそのまま当てはまる。**なお cold から本番へ bytes を戻す経路は無い**
> （`adapter.get` で本番へ書き戻す呼び出しは main に 0 件。`restoreAttachment` は archive に
> binary が無ければ「no binary content」で終わる）ので、**cold 化した文書は W11 で戻せない**。
> これも表に書いていなかった。
>
> **W10 / W11 は 2 巡目の確認レビューで 2 名が別々に指摘して足した。** 初版の表は
> `createAttachment` / `copyAttachment` / `updateAttachment` の 3 つを最下層としていたが、
> **archive / restore はそのどれも通らない** — `CloudantClientWrapper` を直接叩く。
> W9 を落としたのと同じ形で、**同じ文書の中で 2 度数え違えた**。表どおりに E1 を配線すると
> **ゴミ箱から戻した文書の内容状態が記録されない**。
>
> **W11 には過剰拒否の窓もある**: 復元は「文書を戻す → attachment 行を作る → body を PUT」
> の順で、最後の 2 つは別の書き込みである。その間に checkOut が入ると、行はあって body が
> 無い状態を読む。R54 の追加処置はこの窓のために 1 度だけ読み直す（[`fail-closed-reads.md`](fail-closed-reads.md) の R54 / R56）。

### 読んで決めた（2026-09-19 追記）

**W7 の追記は「新しい attachment」ではない。** `appendAttachment`
（`ContentServiceImpl:4551`）は `contentDaoService.updateAttachment`（:4571） で**同じ attachment 行を
その場で書き換える**。新しい行も新しい版も作らない。bytes は
`SequenceInputStream(既存, 追記分)` で、**意図的に一度もメモリに載せない**（巨大ファイル用）。
したがって digest を取るなら書き込みの流れに `DigestInputStream` を挟むしかない
（1 パスで済むが、値が分かるのは書き終えた後）。

`isLastChunk` は**引数にあるだけで本体で使われていない**（:4552 の宣言以外に出現 0）。
**製品は中間チャンクと最終状態を区別できない。**

→ **決定**: W7 は E1 の対象にする。statement は **1 回の追記呼び出しごと**に 1 本
（CouchDB の添付は各呼び出しの後に完全な状態なので、中間状態も「その時点の内容」である）。
`commitmentKind` は W1〜W5 と同じ扱い。ただし**どれが最終かは記録しない** — 製品が
知らないことを台帳に書かない。これは limits に書く。

**W6 / W8 の複製は新しい bytes を作る。** `copyAttachment`
（`AttachmentServiceDelegate:107`）は元の stream を読んで
`contentDaoService.createAttachment` で**新しい行**を書く。参照の共有ではない。

→ **決定**: W6 / W8 も対象。statement は**複製自身の digest**を持ち、元の台帳 entry を
引き継がない。元の attachment と文書を「由来」として名指すだけにする。
（`createDocumentFromSource` が evidence aspect を剥がすのと同じ向き —
`stripEvidenceForNewObject`、`ContentServiceImpl:1312`。）

**`deleteContentStream` は bytes を消す。** attachment 行を削除し、参照を null にする
（`deleteAttachment` は :3601、参照を null にするのは :3605）。版を作らないので、非 versionable では戻せない。

→ **決定**: 対象にする。内容状態の遷移として「この時点で内容が無くなった」を書く。
書かないと、台帳の最後の statement が**もう存在しない bytes** を指したままになり、
弱い事実が強い事実として読める。

### この棚卸しで見つけた欠陥（処置済み）

複製の経路を読んでいて、**`checkOut` と `checkIn` が `copyAttachment` の null を
そのまま複製に書いている**のを見つけた。`copyAttachment` は 2 つの別のことに null を返す
（元が内容を持たない／元が名指す attachment 行が store に無い）。後者では、内容のある文書が
**内容の無い作業コピー**として checkout され、check-in するとその版が最新になる。
`createDocumentFromSource` は同じ状況で既に拒否していた（1 メソッド隣）。

`copyAttachmentOrRefuse` を 1 か所に置いて 3 呼び出し側を通した。詳細と錠は
[`fail-closed-reads.md`](fail-closed-reads.md) の **R54**。

### E1 にとって決定的な事実 — **CMIS 書き込み主経路に digest が無い**

> **初版はここを「現状どこも bytes を hash していない」と書いていた。誤りで、確認レビュー
> 2 名が別々に反例を出した。** 訂正して残す（取り下げの記録も記録である）。

真なのは狭い方だけである: **CMIS の書き込み主経路**
（`createAttachment` → `AttachmentServiceDelegate` → `AttachmentDaoDelegate` → CouchDB）に
digest の計算は無い。ここは読んで確かめた。

**既にある hash は 2 つある。**

| 何を hash するか | どこ | 語彙 |
|---|---|---|
| 取込が**取得した** bytes | `CanonicalImportServiceImpl.computeContentHash`（:2468、呼び出し :3568）→ `nemaki:contentHash` | `DigestSubject.INPUT` |
| **保存された** bytes を読み直して | `FixityScanService` → `FixityVerifier.verify(content, attachment.getInputStream())` | `SUBJECT_STORED_REVERIFIED`（`FixityVerifier:65`） |

したがって `nemaki:contentHash` は「外部取込元が申告した値」ではない — **サーバが取得した
bytes について自分で計算した値**である（`FixityVerifier` の javadoc がそう書いている）。
弱いのは別の点で、**取得した bytes であって保存された bytes ではない**。

E1 が新設するのは「**CMIS 経由で書かれた content についての digest**」であり、
取込経由のものについては既存の値と語彙（INPUT / STORED）に合流させる。
どこで取るか（書きながら 1 パス / 書いた後に読み直す）は ADR
[`e1-content-state-commitment-adr.md`](e1-content-state-commitment-adr.md) §6 で未決のまま。
**読み直す側は `FixityVerifier` として既に出荷されている**ので、新設ではなく配線になる。

---

## 2. mapper の定義

計画 A-1（R52）の対象。**CouchDB 永続化の族は 3 つ**だが、`JsonMapper` を組む場所は
**main だけで 6 か所**ある（2026-09-19 に grep で数え直した。初版は 3 と書いていた）。

| 定義 | 場所 | 何を復号するか | R51 の deserializer |
|---|---|---|---|
| `ObjectMapperFactory` | `config/ObjectMapperFactory.java:47` | default / nemaki / couchdb / debug の 4 profile | 無し |
| `DaoHelper.createConfiguredObjectMapper` | `dao/impl/couch/delegate/DaoHelper.java:27` | archive / usergroup / attachment / changeevent / typedefinition の delegate | **有り** |
| `ContentDaoServiceImpl.createConfiguredObjectMapper` | `dao/impl/couch/ContentDaoServiceImpl.java:201` | content（folder / document / item ほか）。private な複製 | 無し |

残る 3 か所は**永続化の族ではない**が、「唯一の定義」を名乗る以上は数に入れる:

| 場所 | 何のため | 寄せるか |
|---|---|---|
| `TypeDefinitionDaoDelegate:495` | view 行の復号。**メソッドの中で組んでいる**ので「定義」として grep に出ない | **寄せる**（永続化の族） |
| `AuditLogger:191` | 監査レコードの直列化。日付・並び順の設定が別 | 寄せない（理由を書く） |
| `LineageSpoolCodec:45` | spool JSON。`STRICT_DUPLICATE_DETECTION` が要る | 寄せない（理由を書く） |

**`ObjectMapperFactory` の javadoc は既に「The one place NemakiWare's mapper configurations
are defined」と書いている**（:31）。コードがそれを満たしていない。文書がコードより強い、
このブランチが潰してきた形そのものである。

**`ContentDaoServiceImpl` の複製に R51 の module が無くても実害が出ていない理由**も読んで
確かめた: この mapper が復号する `Couch*` モデルは日時を**自分で解釈する**
（`CouchNodeBase.setCreated(Object)` → `parseDateTime`、`Number` を受けるので SDK が広げた
`Double` も通る）。GregorianCalendar 型の mutator を持つのは archive / change / apikey /
webauthn 側で、そちらが `DaoHelper` の mapper を通る。**つまり両者は偶然すみ分けている**。
なお 2 つの方針は食い違っている — module は端数・範囲外を**拒否**し、`parseDateTime` は
`longValue()` で**黙って切り捨てる**。一本化のときに揃える対象。

A-1 は**定義元を `ObjectMapperFactory` に寄せる**。`DaoHelper` へ寄せない理由は、
そちらの javadoc が「唯一」を主張していないことと、profile を混ぜないためである
（DAO persistence / Couch persist / REST は別の profile であり、一本化とは
「定義の置き場所を 1 つにする」であって「設定を 1 つにする」ではない）。

---

## 3. 台帳 entry の現状（E1 の前提）

計画 §3 の指摘をコードで確認した。

`EvidenceLedgerRecorder.recordCaptureCompleted` が書く `CAPTURE_COMPLETED` の
`payloadDigest` は `captureDigest(...)`（:231）の戻り値で、その中身は

```
domain / repositoryId / intentId / connectorId / sourceObjectId
  + CaptureIntent.APPLIED_HASH_FIELDS の各値
```

である。**文書の bytes の digest は入っていない。** したがって現状の台帳 entry は
「この取込がこのメタデータで起きた」ことは結び付けるが、「その結果保存された bytes」は
結び付けていない。E1 が埋めるのはここ。

---

## 4. anchor の段

### 段の種類

`AnchorKind`（`rest/purview/anchor/AnchorKind.java`）は 3 つで、それぞれが
**時刻について何を言えるか**を型で持っている。

| 段 | `TimeSemantics` | 言えること |
|---|---|---|
| `ATLAS_CATALOG` | `NOT_A_TIME_PROOF` | 時刻の証明ではない |
| `OPENTIMESTAMPS` | `UPPER_BOUND_ONLY` | 「これより後ではない」だけ |
| `RFC3161_TSA` | `BIDIRECTIONAL_WITHIN_ACCURACY` | 精度の範囲で両方向 |

`AnchorService` は主張の限界文をこの enum から導く（テキストで渡させない）。
新しい段を足したときに限界文を書き忘れられない形になっている。

### receipt が保存しているもの

`AnchorReceiptCodec` が書くのは:

```
kind / status / timeSemantics / anchoredDigest / attemptedAt / anchoredAt
proofBase64 / proofDigest / attributes / failureReason
```

**失効材料（CRL / OCSP）は 1 語も無い**（`AnchorReceiptCodec` を grep して 0 件）。
計画 §3 の「RFC 3161 の失効情報を発行時に保存していない」はコードのとおり。
`revocation` / `OCSP` / `CRL` の語が出るのは `evidence/validity` の
`ErsVerifier` / `ErsRecord` だけで、**検証側にはあるが発行側の保存が無い**。

計画 §11 が足すのはここ — token DER・imprint/CMS アルゴリズム・chain・policy OID・
genTime に加えて、**発行時の** CRL/OCSP の生データと digest と取得時刻。
あとから取った current OCSP を発行時取得済みのように扱わない、という区別も
保存構造の側で持たせる必要がある（同じ欄に入れれば区別が消える）。

### 長期検証側の役割分担（2026-09-19 に読んだ）

| クラス | 役割 | 持たないもの |
|---|---|---|
| `ErsFormat` | **決定**: RFC 4998 を採る（6283 ではない）。実装はしない | — |
| `ErsRecord` / `ErsVerifier` | 組む / 検証する。data object は checkpoint の正準 bytes | — |
| `EvidenceRecordService` | 既にある anchor から **その場で** ERS を組む（`latest` / `forCheckpoint`） | **保存しない** |
| `LongTermValidityService` | 「何が古びつつあり、どの更新が要るか」を**報告する** | 更新は**しない**（hash-tree 更新は全読みになるため既定にしない） |
| `RenewalNeed` | timestamp 更新と hash-tree 更新を**1 語にまとめない**。更新が遡らないことも答えに含む | — |
| `AlgorithmRegistry` | 運用者の宣言を保持して機械的に答える。既定値は出発点であって保証ではない | — |

**Phase 6 にとっての要点: ERS は保存されていない。** `EvidenceRecordService` は
要求のたびに現在の材料から組み直す。RFC 4998 の timestamp 更新は**前の evidence record の
bytes の上に**新しい token を置くので、保存が無いままでは更新の連鎖はそもそも作れない。
Phase 6 の「ERS persistence」はここを指す。

なお `Built` は `present()` と `unavailable`（理由文字列）を持ち、
「組めなかった」を「無い」と混ぜない形には既になっている。

---

## 5. この文書で決めたこと

- E1 の対象は **W1〜W12 と `deleteContentStream`**（W10 = アーカイブ、W11 = 復元、W12 = cold 移送）。2026-09-19 に実装を読んで
  W6 / W7 / W8 / 消去の扱いを決め、確認レビューを受けて数え直したときに W9 を足した（§1）。
  **列挙できたのはここまで**であり、ここに書いていない経路について
  「記録した」とは後段のどこにも書かない。**3 度数え違えている**（W9 / W10・W11 / W12。いずれもレビューの指摘）という事実も含めて読むこと。**この表を「全部数えた」と読まないこと。**
- W7（追記）は 1 呼び出しごとに 1 statement。**どれが最終かは書かない** —
  製品が `isLastChunk` を使っていないので知らない。limits に明記する。
- W6 / W8（複製）は**自分の digest**を持ち、元の entry を引き継がない。
- `deleteContentStream` は「内容が無くなった」遷移として書く。
- **hash は CMIS 主経路にだけ新設する。** 取込は既に取得 bytes を hash しており
  （`DigestSubject.INPUT`）、保存 bytes の読み直し hash も `FixityVerifier` として
  出荷済みである（§1 末尾）。「書きながら 1 パス」か「読み直す」かは ADR で未決。
- A-1 の寄せ先は `ObjectMapperFactory`。
