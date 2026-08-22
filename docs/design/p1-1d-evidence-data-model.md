# P1-1(d) — 事実が確定する時点 (モデル本体)

> **この文書の範囲は棚卸し §7 の 1 番目だけ。** 空コンテンツ / version ごとの hash /
> メタデータ hash / 「どの型が証拠か」 / PII / 会話の範囲 / aspect 付与の位置 は、
> **ここが決まってからでないと決められない**ので、この文書には書かない。
> 分類は [`p1-1d-scope-inventory.md`](p1-1d-scope-inventory.md)。

---

## 0. 問いの立て方

「どのフィールドを持つか」ではない。取込が記録する事実それぞれについて、

1. **いつ真になるか** (成立)
2. **いつ記録されるか** (記載)
3. **その間に何が起こりうるか**

を決める。1 と 2 がずれる箇所が、後から「この記録は何を証明しているのか」と聞かれて
答えられなくなる箇所である。**現在は 1 と 2 の区別が製品のどこにも無い。**

---

## 1. 現在の順序 (コードから起こしたもの、推測なし)

`CanonicalImportServiceImpl.execute()` — [1877](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1877) 以降:

| # | 起こること | 位置 |
|---|---|---|
| 1 | 外部からバイト列を取得し `computeContentHash(contentBytes)` | `:1877` |
| 2 | `createDocument` / `checkOut`+`checkIn` | `:2095` / `:2041` |
| 3 | `applySourceMetadata` — `nemaki:contentHash` ほかを aspect に書く | `:2105` |
| 4 | `applyRelationship` | `:2113` |
| 5 | 名前を読み戻し **`emitLineageEvent`** | `:2132` |

そのあと **`execute()` が返ってから**、wrapper (`executeChatContextImportInternal`) が:

| # | 起こること | 位置 |
|---|---|---|
| 6 | `applyArchetypeMetadata` — `nemaki:chatWorkspaceId` 〜 `nemaki:chatEvidenceScope` の 8 個 | `:822` |
| 7 | `applyChatCapturedAt` | `:843` |
| 8 | `applyCaptureWindow` — `chatCaptureWindowStart/End` | `:846` |

**来歴イベントは 5 で出る。証拠プロパティ 11 個のうち 10 個は 6〜8 で書かれる。**

---

## 2. 事実は 3 種類あり、確定の仕方が違う

現在のモデルは全部を「取込が書いたフィールド」として平らに扱っている。分けると、ずれの
在り処がそのまま出る。

| 種別 | 何か | 確定するのは | 例 |
|---|---|---|---|
| **観測** | 外の世界を見て得た | **見た瞬間**。あとから確かめ直せない | 取得したバイト列とその digest、`sourceObjectId`、`chatChannelId`、`participants` |
| **決定** | この取込が選んだ | **決めた瞬間**。実行前から決まっている場合もある | `targetFolderId`、`importMode`、`captureWindowStart/End`、`selectionReason`、`evidenceScope` |
| **結果** | 書いた結果そうなった | **書き込みが成功した瞬間**。読み戻さないと分からない | `objectId`、version、保管されたコンテンツの有無 |

**この 3 つは失敗の仕方が違う。** 観測は「もう確かめられない」、決定は「後から書き換えられる」、
結果は「本当にそうなったか読まないと分からない」。同じ `attributes` map に混ぜている限り、
読む側はどれがどれか分からない。

---

## 3. ずれの棚卸し

### D1 — 証拠の大半は、それを証するイベントより **後** に書かれる

§1 の 5 と 6〜8。イベントは object を名指すが、`chatChannelId` も `capturedAt` も
`captureWindowStart/End` も**その時点では存在しない**。

現状の記述はこれを認めている ([`:838-841`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L838))
が、続けて「**刻印を emit より前に動かす**」を対処として挙げている。
**これは (b) §8 で撤回済み** — 刻印先の aspect を作るのは `execute()` が返った後の
wrapper なので、前倒しは空振りする。**このコメントは現在の設計と矛盾しているので直す。**

(a) の outbox は「途中で落ちた」を `UNRESOLVED` として拾う。**拾わないのは、
6〜8 が全部成功したがイベントの中身が 5 時点のものである、という通常経路**である。

### D2 — `contentHash` は「取得したバイト列」の digest であって「保管された記録」の digest ではない

`computeContentHash` は fetch 直後のバイト列に対して走る ([`:1877`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1877))。
`describeCapturedContent` は `computedHash != null` なら**読み戻さずに** `hashed(...)` を返す
([`:971-975`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L971))
— javadoc は「この取込が供給しハッシュしたバイト列を保管した」と書いている。

`createDocument` が成功を返したことは、**リポジトリがストリームを受理した**ことは示すが、
**保管されたバイト列が取得したバイト列と一致する**ことは示さない。現状の文言はその差を
またいでいる。`CapturedContent` は既に STORED / NONE / UNKNOWN を honest に分けている
([`:960-1018`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L960))
ので、**足りないのは digest が「何のバイト列の digest か」を名乗ること**だけである。

> 読み戻して digest を取り直すのは **fixity (P1-2)** の仕事で、そのコスト予算は別にある。
> (d) が決めるのは**名乗り**であって、検証を足すことではない。

### D3 — version ごとの hash は既に成立している (要点は「消さない」側だった)

確かめた: version-up 経路は `objectId = checkinHolder.getValue()`
([`:2079`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L2079))
で**新しい version の id** を掴み、`applySourceMetadata` はその version に書く。
aspect は document ごとなので、**古い version は自分の hash を保持する**。

`compareContent` は「内容が変わっていない」とき `hashToRecord = null` を返し
([`:940-953`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L940))、
書き込み側は null/空を**書かない** ([`:2387`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L2387))
ので既存値が残る。**ここは正しい。**

残るのは「hash を持たない version が在る」場合で、それは `CapturedContent.unknown(...)` に
理由つきで出る。**モデルとして足すものは無い。棚卸し §3 の「version ごとの hash」は
ここで閉じる。**

### D4 — `chatCapturedAt` は「取込が走った時刻」であって「会話が起きた時刻」ではない

サーバクロックから押される ([`:829`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L829))。
名前は「captured at」なので**取込の時刻で正しい**が、`captureWindowStart/End` が
会話側の時間軸を指すので、**同じ aspect の中に 2 つの時間軸が名前で区別されずに並んでいる**。

これは §2 の「観測」と「決定」が混ざっている実例である。

### D5 — 空コンテンツは digest としては閉じている

`computeContentHash` は 0 バイトにも正当な digest を返す
([`:1249-1256`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/CanonicalImportServiceImpl.java#L1249))。
`CapturedContent.none()` は「attachment が無い」に対してだけ使われる。
**「空を保管した」と「何も保管していない」は既に別物として出る。**
棚卸し §3 の「空コンテンツ」も**ここで閉じる**。

---

## 4. このモデルが採る規則

### R1 — 記載時刻と成立時刻を混ぜない

来歴イベントは「**いつ書かれたか**」と「**いつ真になったか**」を別に持つ。一致を既定にしない。
一致しているときに同じ値が入るのは構わないが、**一致していることを読む側に仮定させない**。

### R2 — digest は主語を名乗る

`contentDigest` を単独で置かない。**何のバイト列の digest かを持つ**。
現状で名乗れるのは `input` (取得したバイト列) だけで、`stored` は fixity (P1-2) が
入るまで**発行しない**。`hashed(...)` の javadoc も input に直す。

### R3 — 3 種別を型で分ける

`observed` / `decided` / `resulted` を、読む側が判別できる形で持つ。**名前の規約ではなく型**
— 名前の規約は次に足す人が破る。

### R4 — 「まだ真になっていない事実」をイベントに載せない

5 の時点で存在しない 6〜8 の値を、5 のイベントが持つことはできない。**したがって
選択肢は 2 つしかない**:

| | 内容 | 代償 |
|---|---|---|
| **(i) 事実の側を前に出す** | 6〜8 を `execute()` の中、emit の前に移す | wrapper が aspect を作る前提を崩す。**(b) §8 で撤回済み**。再提案しない |
| **(ii) イベントの側を後に出す** | emit を wrapper の後に移す | emit までの窓が伸びる。窓は (a) の outbox が既に覆う |

**(ii) を採る。** ただし **(d) では決めるだけで動かさない** — 動かすと
`lineageOperationId` の採番位置、`ExternalIngestResult` の組み立て、
outbox の `CAPTURED` 完了位置が同時に動く。**実装は (e) の隔離と同じ変更で行う**
(失敗時の隔離を入れる時点で、どこまでが 1 つの取込かを結局引き直すため)。

> **(a) の outbox は (ii) の前提条件であって代替ではない。** outbox は「落ちた」を拾う。
> (ii) が直すのは「落ちていないのに中身が古い」である。

### R5 — 時間軸は名前で区別する

`capturedAt` (取込の時刻 = 観測) と `captureWindowStart/End` (会話の時刻 = 決定) を
別種別に置く。**プロパティ名は変えない** — 既に READONLY で保護済みで、改名は移行を伴う。
分けるのはモデル側の種別であって、CMIS 上の名前ではない。

---

## 5. この文書で **決めないこと**

- 6〜8 を実際に動かすこと (R4 のとおり **(e)** と同じ変更で行う)
- `stored` digest の発行 (**P1-2** fixity)
- どの型が証拠か / PII / 会話の範囲 / aspect 付与の位置
  (棚卸し §7 の 3〜6。**このモデルが通ってから**)
- InterPARES 逐条 (棚卸し §7 の 7)

---

## 6. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | イベントは記載時刻と成立時刻を**別々に**持ち、成立時刻を欠く事実は**欠くと分かる** | 片方を他方で埋めると落ちる |
| 2 | digest は主語 (`input`) を持ち、**`stored` を名乗る経路が存在しない** | `stored` を発行できるようにすると落ちる |
| 3 | `observed` / `decided` / `resulted` が**型で**分かれる | 3 つを同じ map に入れると落ちる |
| 4 | version ごとの hash が**保たれる** — 内容不変の再取込で既存 hash が消えない | 書込側の null ガードを外すと落ちる |
| 5 | 0 バイトの保管が `none()` に**ならない** | 空を「コンテンツ無し」に倒すと落ちる |
| 6 | `:838-841` のコメントが**撤回済みの対処を指していない** | 文言を戻すと (b) §8 と矛盾する |

条件 4・5 は既に成立している (§3 D3・D5)。**AC に残すのは、モデルを入れる変更が
それを壊さないことの control としてである。**

---

## 7. 次

1. この文書のレビュー
2. R1〜R3 を型として入れる (イベント側。CMIS プロパティは触らない)
3. `:838-841` のコメント修正
4. 通ってから棚卸し §7 の 3〜6
