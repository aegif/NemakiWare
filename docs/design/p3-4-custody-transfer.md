# P3-4 — 保存システムへの移管 (custody transfer)

作成 2026-08-26。ロードマップ §4 Phase 3 の 4 番目。前提は P3-1 (SIP) と P1-3 (台帳)。

---

## 0. 何を主張し、何を主張しないか

**主張する**: **管理責任の移転を 9 状態の状態機械として管理し、
「先方が言ったこと」と「こちらが確かめたこと」を別の状態として持つ**。
検証済み受領証を証拠連鎖に載せる型が在り、載せられなければ**拒否を返す**。

> **「載せられなければ custody は渡らない」は、まだ構造ではなく規約である**
> (2026-08-26 訂正・レビュー指摘)。`advance(CUSTODY_TRANSFERRED, …)` は受領証の有無しか
> 見ておらず、台帳を参照しない。fail-closed を成立させるのは
> `recordVerifiedReceipt` の戻り値を読む**呼び出し元**だが、**その呼び出し元は
> まだ存在しない** (§5)。javadoc も "The caller must not move to CUSTODY_TRANSFERRED" と、
> 規約であることを認めている。呼び出し元ができるまでは、これは**守られていない規則**である。

**主張しない**:

- **送信も受信も実装していない。** RODA / Archivematica への実際の HTTP は未実装 (§5)。
  この増分が持つのは protocol の型と規則であって、接続層ではない
- **署名は検証していない。** 受領証は署名を**運ぶ**が、先方の鍵素材を持っていないので
  検証はしない。`signatureVerified` は既定 false で、それが正直な状態である
- **先方のコピーが健全であることは言わない。** 受領証が establish するのは
  「先方がこの package を取り込み、この結果を報告した」まで
- **保管層の移動 (cold move) は custody 移転ではない。** 別物であり別の型である

---

## 1. なぜ 9 状態で、boolean ではないのか

**面白い失敗は状態の間に住んでいる。** SENT のまま RECEIVED にならない、
RECEIVED だが VALIDATED にならない、VALIDATED だが取込を拒否された —
それぞれ別の問題で、持ち主も違う。`transferred` フラグはこれを全部
「まだ」に潰す。**詰まっている状態そのものが診断である。**

とくに `AIP_CREATED` と `RECEIPT_VERIFIED` を分けている。

| | 意味 |
|---|---|
| `AIP_CREATED` | **先方が言ったこと**。保存コピーが在ると先方が報告した |
| `RECEIPT_VERIFIED` | **こちらが確かめたこと**。受領証がこちらの package を指している |

一緒にすると、**このリポジトリの記録が、引き取る側の未検証の主張に依存する**ことになる。
custody 移転において、それが起きてはならない唯一の場所である。

`FAILED` は列の途中ではなく、custody が渡る前ならどこからでも到達する。
**失敗状態の無い状態機械は、あらゆる実際の失敗を「1 つ前の段階のまま」として
記録させる** — 止まった移管が見えなくなる仕組みである。

`LOCAL_DISPOSITION` は「前段に成功したから」到達するのではない。
ローカルコピーの削除は P3-3 が支配する不可逆な行為で、**受領証が検証された後に
誰かが決めたから**起きる。

---

## 2. AIP checksum だけの受領証は何も証明しない

一番自然な受領証は「作った AIP の checksum はこれです」である。
**それはこのリポジトリが使える何も establish しない** — 見たことのない成果物の
hash なので、**どんな値でも条件を満たす**。

受領証を検証可能にするのは、**こちらの package を名指していること** (`sipDigest`) である。
だから `CustodyReceipt` は `sipDigest` が無いと**構築できない**。
「後で気をつける」にすると、以後の読み手が全員それを覚えていなければならない。

そして**別の submission についての受領証は、どんなに肯定的でも拒否する**。
「すべて順調でした」が他人の記録についてのものなら、この記録については何も言っていない。

---

## 3. 双方向参照は時系列で成立させる

**SIP を作る時点で先方の AIP ID は存在しない。** だから参照は 2 手で作る:

1. SIP は**連鎖の抜粋を外向きに運ぶ** (P3-1 §5)
2. 受領証が返ってきたら**証拠連鎖に追記し**、次のアンカーに含める

以後、両端の食い違いが**検出可能**になる。**凍結ではない** — どちらの端も
自分のコピーを変えられる。変えたことが見えるようになるだけである。

### fail-CLOSED — 処分と同じで capture と逆

custody が渡ることは、**ローカルコピーを消してよくなる直前の段**である。
記録できなかった移管で渡してしまうと、「誰が答責を負うことになったか」の
唯一の記録に、**まさに移管のところだけ穴が空く** — そして次の正当な手順は
ローカルコピーの破棄である。

拒否の代償は遅延だけ (RECEIPT_VERIFIED に留まり、記録はここに残り、次回また試す)。
比較にならない。

---

## 4. 負のコントロール 6 本実測

| 壊した箇所 | 落ちたテスト |
|---|---|
| どの受領証も一致させる | `aReceiptForAnotherPackageIsRefused` |
| どの遷移も許す | `aSkippedStepIsRefused` ほか 3 本 |
| 記録できなくても custody を渡す | `anUnrecordableHandoverIsRefused` |
| `AIP_CREATED` を custody 済みに数える | `aipCreatedDoesNotTransferCustody` ほか |
| 署名の有無・検証済みを digest から落とす | `trustAndVerificationAreDifferentFacts` ほか |
| 受領証なしで custody を渡す guard を外す | **発火しなかった** |

> **最後の 1 本は到達不能だった。** `CUSTODY_TRANSFERRED` は `RECEIPT_VERIFIED` からしか
> 到達せず、そこに至ると受領証は必ず入っている。つまりあの guard は
> **状態機械が先に塞いでいる**。残してあるのは、後から別の状態が
> `CUSTODY_TRANSFERRED` への辺を持ったときに効くからで、
> **測れた保護としては数えていない** (測れているのは状態機械のほう)。

### 4.1 2026-08-26 レビュー — 「検証済み」に受領証なしで着けた

**`advance(RECEIPT_VERIFIED)` が通っていた。** `allowedNext(AIP_CREATED)` に
`RECEIPT_VERIFIED` が入っていたので、`advance` を順番に呼ぶだけで
**受領証を 1 通も見ずに「我々が確かめた」という名前の状態**に着けた。
`verifyReceipt` の照合はすべて健在だったが、**通らずに済む道**が横に在った。
この機械の主張は「詰まった状態そのものが診断である」ことなので、
偽の診断が出せる時点で機械の役目が消えている。

`allowedNext` から外し、`RECEIPT_VERIFIED.isReachableFrom(from)` を新設して
`verifyReceipt` だけがそこへ行けるようにした。`advance` は明示的に拒否する。

同じレビューで 2 点追加:

- **`verificationOutcome` を誰も読んでいなかった。** `REJECTED` と書かれた受領証でも、
  digest さえ合えば `RECEIPT_VERIFIED` に進めた。次の 1 手が custody の移転である以上、
  これは「先方が受け取らなかった」を「受け渡し完了」の 1 歩手前に置くことになる。
  `CustodyReceipt.reportsSuccess()` を足し、**未知の語・空欄は成功ではない**とした
  (この build が知らない語を「たぶん成功」と読むと、語彙が増えるたびに緩む)。
- **custody 通過後の受領証**は状態機械が既に塞いでいたが、その理由は
  「順番違い」としか言わなかった。終わった受け渡しに後から受領証を出してきたのなら、
  運用者はそれを**そう**知らされる必要がある。guard は残し、
  テストは**理由の文面**で判別する (下表の 2 行目)。

### 4.2 2026-08-26 Codex レビュー — 名無しの受領証で custody が渡っていた

compact constructor が要求するのは `sipDigest` だけで、`verifyReceipt` が見るのは
digest と outcome だけだった。つまり **「OK / digest X」以外が全部空**の受領証が
`RECEIPT_VERIFIED` に着き、その後は通常の `advance` で `CUSTODY_TRANSFERRED` に行けた。
**誰に・いつ渡したのかがどこにも無い受け渡し**である。後からこの記録について
問い合わせる相手が居ないので、「受け渡した」と言えていない。

`CustodyReceipt.missingRequiredField()` を足し、`verifyReceipt` が
`submissionId` / `aipId` / `aipChecksum` / `receivingAgent` / `receivedAt` の
**どれか 1 つでも空なら拒否**する (ロードマップ行が挙げている中身と同じ)。
constructor は緩いままにした — 欠けた受領証も「何かが届いた」という事実で、
捨てると届いたことごと消える。**検証に足りないだけで、物として無効ではない。**

**署名は必須にしていない。** こちらは先方の鍵素材を持っておらず、検証しない署名文字列を
必須にしても見せかけにしかならない。この欠落は全受領証の `limits()` に書いてある。
必須化は submission agreement 側の判断で、§5 に未了として残す。

| 壊した箇所 | 落ちたテスト |
|---|---|
| `missingRequiredField()` を読まない | `aReceiptMustNameWhoIsAnswerable` / `everyIdentifyingFieldIsRequired` |
| `aipId` の判定だけ落とす | `everyIdentifyingFieldIsRequired` |

| 壊した箇所 | 落ちたテスト |
|---|---|
| `allowedNext(AIP_CREATED)` に `RECEIPT_VERIFIED` を戻す | `receiptVerifiedIsNotAnOrdinaryMove` |
| `custodyHasPassed()` guard を外す | `aLateReceiptDoesNotRewriteTheHandover` |
| `reportsSuccess()` を読まない | `aNegativeReceiptIsNotVerification` / `anUnknownOutcomeIsNotSuccess` |

---

## 5. まだ無いもの

| | 状態 |
|---|---|
| 状態機械・受領証・連鎖への追記 | **実装済み** |
| **RODA / Archivematica への実際の送信** | **未**。§9-3 / §9-4 に API と落とし穴は調査済み |
| **BagIt (`zipped bag`) 接続層** | **実装済み・REST から到達可能** (2026-08-26)。§6。`POST /v1/admin/eark/{repo}/objects/{id}/bag`。`gov.loc:bagit` は core/pom.xml に明示宣言した |
| **署名検証** | **配線済み** (2026-08-26、§8)。受領証が**到着した時点で**検査する (`custody.receipt.key.<agent>` が設定されたときだけ)。保存された `signatureVerified` は読み戻しで信じないので、所見はここで作るしかない。鍵が読めない・無いは「検査していない」であって不正ではない。**鍵の入手と信頼は依然 submission agreement 側** |
| **永続化** (transfer の store) | **実装済み** (2026-08-26、§7)。evidence-ledger DB に同居。読み出しは `restore` を通り、履歴が合法な歩みでなければ拒否される |
| **fail-closed を強制する呼び出し元** | **実装済み** (2026-08-26、§7)。`CustodyTransferService.passCustody` が先に記録し、記録が効いたときだけ進む。`advance` は `CUSTODY_TRANSFERRED` を明示的に拒否する (扉は 1 つ) |
| **スレッド安全性** | **未**。`state` / `receipt` / `history` は非同期化で、`advance` は check-then-act。呼び出し元が無いので現時点で実害は無いが、"workflow object" と説明する以上は同期か明記が要る |
| **`reportsSuccess()` の語彙を実機で確認** | **未**。`PASSED/PASS/VALID/SUCCESS/ACCEPTED/OK` は**こちらが決めた綴り**で、RODA / Archivematica が実際に何を返すか照合していない。未知語は成功ではないので**外れても fail-closed** (受け入れてしまうのではなく、正当な受領証を拒否する) が、そのままでは使えない。実機受入試験で語彙を固定すること |
| **submission agreement の明文化** (失敗・再送・重複取込・部分受入・先方 AIP 再生成) | **未** |
| 実機受入試験 | **未**。RODA は arm64 で起動することだけ確認済み |

---

## 6. BagIt 接続層 (2026-08-26)

Archivematica の転送 type は `standard / zipfile / unzipped bag / zipped bag / dspace /
maildir / TRIM / dataverse` の 8 種で、**E-ARK に相当するものが無い**。
そこで `zipped bag` がバイト列を渡す手段になる。

**これは受け取り側が package を理解するようにする層ではない。** 向こう側では SIP は
payload の中の 1 ファイルで、METS は読まれず、構造は尊重されず、これによって
Archivematica の AIP が E-ARK AIP になることもない。「BagIt コネクタが在る」を
「Archivematica が我々の E-ARK SIP を取り込む」と読まれると、このコードがしないことを
言ったことになるので、`LIMITS` が全 bag に同行する。

**「bag の中に IP を封入して搬送」という語り方もしない** — RFC 8493 は serialization を
規定しないので、その言い方は標準がしていない保証を主張することになる (外部レビュー指摘)。
真なのはもっと狭い: payload と manifest を持つディレクトリを zip したもので、
受け取り側の `zipped bag` type が読むのはそれである。

manifest は **SHA-512 と SHA-256 の 2 本**。前者は受け取り側が好みそうだから、
後者は本製品の証跡が SHA-256 だから — 受け取り側が bag manifest を我々の連鎖と
突き合わせるのに 2 つ目の digest を計算し直さずに済む。

### 踏んだ落とし穴 2 つ

1. **`bagInPlace` は root 直下を自分で `data/` へ移す。** 先に `data/` を作って
   そこへ置くと `data/data/` になり、manifest はそれと整合するので**何も落ちない**。
   受け取り側が期待するレイアウトでないだけ。移動後の位置を確認して、違えば送らない。
2. **タグマニフェストの行順が絶対パス依存。** 同じ package を別ディレクトリで包むと
   同じ 4 行が別の順で出て、**deflate の圧縮結果が変わり archive の長さが変わる**。
   「同じものを 2 度送ったか」に安い答えが無くなる。行を整列して正規化した。
   なお 2 つのディレクトリが偶然同じ順に hash することはあるので、
   この対照は**end-to-end 比較ではなく正規化そのものを直接測る**
   (実測: 整列を外しても end-to-end は緑のままだった)。

---

## 7. 永続化と、規則を執行する呼び出し元 (2026-08-26)

### 規則は型に在ったが、誰も執行していなかった

`recordVerifiedReceipt` は `Authorisation` を返し、javadoc は「拒否されたら
custody を渡してはならない」と書いていた。**呼び出し元が無かった。**
コメントに書かれた規則は、コメントを読まない最初の 1 人まで保つ。
そしてそれが守っているのは「この記録の唯一の複製を持つのはもう自分ではない」という
判断である。

`CustodyTransferService.passCustody` がその呼び出し元。**先に記録し、記録が
効いたときだけ進む。** 順序は capture 則の逆で、それが要点: capture は
chain しようとする時点で既に起きているので、拒否すると記録の対象そのものが壊れる。
custody は**まだ渡っていない**ので、拒否の代償は再試行だけである。

`advance` は `CUSTODY_TRANSFERRED` を明示的に拒否する。ここを通せば規則は
またコメントに戻る。REST も扉を 2 つに分けた (`/advance` と `/pass-custody`)。

### 保存された状態は「主張」ではない

state machine を永続化するとは、外から状態を設定できるようにすることである。
検査せずにそれをやると、**DB に書ける者は誰でも 1 フィールド編集して
`RECEIPT_VERIFIED` を自分に渡せる** — 機械が防いでいるはずの偽の診断に、
塞いだ経路より短い道で着く。

`CustodyTransfer.restore` は、保存された履歴が (a) 連続していること、
(b) 各段が機械の許す移動であること、(c) 保存された状態で終わっていることを
検査してから返す。偽造行は**読んだ時点で**拒否される — 誰かがそれに基づいて
行動し得る最初の瞬間である。

transfer は evidence-ledger DB に同居する (anchor receipt と同じ理由: 1 つの話に
1 つの保持方針)。ただし ledger entry と違い**更新される** — state machine とは
そういうものだから。append-only な handover の記録は `CUSTODY_RECEIPT` entry のほうで、
この行はそれを生んだ作業状態である。

| 壊した箇所 | 落ちたテスト |
|---|---|
| `Authorisation` を読まない | `anUnrecordableHandoverDoesNotPassCustody` |
| 記録より先に進める | `theRecordingComesFirst` ほか 2 |
| `advance` に CUSTODY_TRANSFERRED を通す | `custodyDoesNotPassThroughTheOrdinaryDoor` |
| `save` の戻り値を無視する | `aMoveThatDidNotReachTheStoreIsRefused` ほか 1 |
| `restore` の到達可能性検査を外す | `aSkippedStepIsRefused` |
| `restore` の終端状態検査を外す | `aForgedStateIsRefused` |
| `restore` の連続性検査を外す | `aHistoryThatDoesNotJoinUpIsRefused` |

---

## 8. 受領証の署名検証 (2026-08-26)

`signatureVerified` は**呼び手が立てる boolean** だった。receipt を作れる者なら誰でも
立てられた。`ReceiptSignatureVerifier` はそれを**検査の結果**にする。
REST の受け口はリクエスト本文から決して読まない (findings は入力として受け付けない)。

署名対象は識別フィールドを `\n` で連結した固定形。「先方が送ってきた直列化」ではなく
固定なのは、こちらが制御しない直列化の上の署名は再現できないから — 先方がこの文字列に
署名する必要があり、それを合意するのが submission agreement である。

**鍵の入手と信頼は閉じていない。** 鍵が無いのは「検査できなかった」であって
「署名が不正」ではない (前者はこちらについての言明である)。有効な署名が establish するのは
「この鍵を持つ者がこの receipt を作った」までで、**その鍵が受け取り組織のものかどうかは
言っていない**。

---

## 9. 引き渡しの記録は冪等 (2026-08-26)

`passCustody` は「記録 → 進める → 書く」の順で、書けなかったときは正直に
「chain には在る / transfer には無い」と言う。運用者は再試行する。
**そこで素朴に再記録すると、1 回の引き渡しに `CUSTODY_RECEIPT` が 2 本付く** —
連鎖が「この記録は 2 回引き渡された」と言うことになる。

`receiptDigest` は決定的 (同じ transfer と同じ受領証は同じ値) なので、
その digest を持つ entry が既に在れば**それがこの引き渡しである**。
追記せず granted を返す。

読めなかったときは `false` (追記を試みる)。「読めなかったから見つからない」を
「無い」と扱わないのと同じ理由で、**照会が走らなかったことを根拠に
「記録済み」と答えない**。

| 壊した箇所 | 落ちたテスト |
|---|---|
| 既記録の照会を外す | `aRetryDoesNotChainTheHandoverTwice` |
| subject だけで一致とみなす | `adifferentHandoverIsNotSuppressed` |

