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
| **`reportsSuccess()` の語彙を実機で確認** | **RODA については採れた** (§10 追試 3)。RODA に受領証のリソースは無く、材料は `JobReport.pluginState` (`SUCCESS`/`PARTIAL_SUCCESS`/`FAILURE`/`RUNNING`/`SKIPPED`) と `AIP.state` (`ACTIVE` ほか)。**`pluginState` を入れるなら噛み合う** — `SUCCESS` は通り `PARTIAL_SUCCESS` は通らない (§1.4 と一致)。**`AIP.state` を入れると壊れる**: 受入完了の `ACTIVE` がこの語彙に無い。**未**: Archivematica の語彙、受領証を実際に組み立てて検証する経路 |
| **submission agreement の明文化** (失敗・再送・重複取込・部分受入・先方 AIP 再生成) | **雛形あり** (2026-08-26): [`docs/operations/custody-submission-agreement.md`](../operations/custody-submission-agreement.md)。7 項目と、本製品が既に決めていて交渉できない側の分離。**合意そのものは当事者間の作業で、software では閉じない** |
| 実機受入試験 | **RODA 6.3.0 の SIP→AIP プラグインだけ実施済み** (§10)。E-ARK SIP は `EARKSIP2ToAIPPlugin` で AIP object になり、**`ers.der` も `metadata/other` なら取り込まれて残る** (`metadata/preservation` に置くと package ごと rollback する — §11 で直した)。bag は **manifest 1 本なら** AIP object になり、**現行の出荷形 (2 本) は rollback する**。**受入承認まで含む ingest workflow は未実施**で、AIP は `INGEST_PROCESSING` 止まり。**我々の PREMIS 文書は AIP の PREMIS metadata に無い**。**未**: full ingest workflow、**Archivematica (bag も E-ARK も)**、受領証を組み立てる経路、他版の RODA |

---

## 6. BagIt 接続層 (2026-08-26)

Archivematica の転送 type は `standard / zipfile / unzipped bag / zipped bag / dspace /
maildir / TRIM / dataverse` の 8 種で、**この一覧に E-ARK / CSIP 専用のものは無い**。
そこで `zipped bag` が**実装可能な候補経路**になる。

> **「必須」とは書かない** (外部レビュー指摘 2026-08-27)。測れているのは
> 「8 種に E-ARK 専用が無い」までで、**`standard` / `zipfile` / custom processing で
> E-ARK SIP を扱えないことは確認していない**。BagIt が唯一の道かどうかは未測定である。
>
> **この層は「E-ARK 経路を持たない受け手」のためのものである。** RODA 6.3.0 は
> E-ARK SIP から AIP object を作れることを実測した (§10 結果 1) ので、RODA に対しては
> bag 経路を選ぶ理由が無い — E-ARK 経路の方が本文も METS も運ぶ。

**これは受け取り側が package を理解するようにする層ではない。** 向こう側では SIP は
payload の中の 1 ファイルで、METS は読まれず、構造は尊重されず、これによって
Archivematica の AIP が E-ARK AIP になることもない。「BagIt コネクタが在る」を
「Archivematica が我々の E-ARK SIP を取り込む」と読まれると、このコードがしないことを
言ったことになるので、`LIMITS` が全 bag に同行する。

**「bag の中に IP を封入して搬送」という語り方もしない** — RFC 8493 は serialization を
規定しないので、その言い方は標準がしていない保証を主張することになる (外部レビュー指摘)。
真なのはもっと狭い: payload と manifest を持つディレクトリを zip したもので、
受け取り側の `zipped bag` type が読むのはそれである。

payload manifest は **SHA-512 と SHA-256 の 2 本**。後者は本製品の証跡が SHA-256 なので、
受け取り側が bag manifest を我々の連鎖と突き合わせるのに計算し直さずに済む —
しかも manifest に在れば **path→digest の束縛として受け取り側の検証が照合する**。
`bag-info.txt` の 1 行は誰も照合しない自由記述なので、そこだけでは同じ意味を持たない。

### 2026-08-26 は 1 本だった。戻した理由 (2026-08-27)

**1 本は RFC 違反ではない。** §2.1.3 は複数を「許す」ので、1 本も適法である。
失っていたのは準拠ではなく、**SHA-256 が BagIt 検証器の照合対象でなくなったこと**である。

1 本にした唯一の実測理由は、RODA 6.3.0 の `BagitToAIPPlugin` が 2 本の bag を
rollback すること (§10 結果 2)。**だがその受け手は、この層の受け手ではない。**
8-27 に E-ARK 経路が通ることを実測したので、RODA に bag を送る理由は無い。
1 本を既定に残すと、**使わせない受け手のパーサ欠陥が、まだ測っていない正の受け手向けの
形式を決め続ける**。それが戻した理由であって、「理由が消えたから」ではない。

**この層の本来の受け手 (Archivematica) では、どちらの形も測っていない。**

| | 実測された取込 |
|---|---|
| manifest 2 本 (現行の出荷形) | RODA の bag 経路で **rollback** (2026-08-27 実測)。ほかの受け手では未測定 |
| manifest 1 本 | RODA の bag 経路で 1 件成功。ただし**この設計が「選ぶ理由が無い」と書いた経路** |

つまりこれは「2 本なら通る」ではない。**「規格が許す形に戻し、SHA-256 を検証器の
照合対象に戻した」**であり、**その形で落ちると分かっている受け手が 1 つ在る (RODA の
bag 経路。そこには SIP を送るので使わない)**、である。Archivematica はどちらの形も未測定。

> **この bag を RODA の `BagitToAIPPlugin` に入れないこと。** rollback する。
> RODA には SIP を直接渡す。`LIMITS` にも書いてある。

機構は [`TwoPayloadManifestsBreakTheLegacyBagParserTest`](../../core/src/test/java/jp/aegif/nemaki/custody/TwoPayloadManifestsBreakTheLegacyBagParserTest.java)
が固定している — 2 本に戻しても commons-ip v1 の欠陥は消えないので、テストも残る。

### 踏んだ落とし穴 2 つ

1. **`bagInPlace` は root 直下を自分で `data/` へ移す。** 先に `data/` を作って
   そこへ置くと `data/data/` になり、manifest はそれと整合するので**何も落ちない**。
   受け取り側が期待するレイアウトでないだけ。移動後の位置を確認して、違えば送らない。
2. **タグマニフェストの行順が絶対パス依存。** 同じ package を別ディレクトリで包むと
   同じ行が別の順で出て、**deflate の圧縮結果が変わり archive の長さが変わる**。
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

---

## 10. RODA 実機受入試験 (2026-08-26 / 2026-08-27 実測)

**RODA 6.3.0 を立てて、NemakiWare が作った本物の SIP / bag を投入した。**
**E-ARK SIP も bag も、SIP→AIP プラグインが処理して AIP object を作った。**
ただし走らせたのはそのプラグインだけで、**受入承認まで含む ingest workflow は通していない**
(E-ARK 側の AIP は `INGEST_PROCESSING` のまま)。

> **この節は一度書き直している。** 2026-08-26 の初回、`EARKSIPToAIPPlugin` に投げて
> 拒否されたのをもって「E-ARK SIP は取り込めない」と書いた。**プラグインの選択を
> 誤っていた。** RODA 6.3.0 には E-ARK 系が 2 本あり、CSIP 2.x を読むのは
> `EARKSIP2ToAIPPlugin` の方である。3 系統のレビューが独立に同じ点を指摘し、
> 8-27 に測り直して**結論が反転した**。誤りの中身は本節の末尾「初回の誤り」に残す — 同じ罠を
> 次に踏まないために、消さずに書いておく。

### 投入 API — 前回「未特定」としていたもの

解けた。`/api/v1/**` も `/api/v2/**` も 404 に見えたのは、**存在しないパスを
叩いていた**だけだった。

```
POST /api/v2/transfers/create/resource     multipart, part 名は "resource" → 201
POST /api/v2/transfers/refresh                                            → 204
POST /api/v2/transfers/find                (検索)                          → 200
POST /api/v2/jobs                          (取込ジョブ)                    → 201
```

`POST /api/v2/jobs` の body は `CreateJobRequest`。ハマった点:

- `sourceObjects` の多相判別子は **`@type`** で、値は `"SelectedItemsListRequest"`
  (`"list"` でも `"object"` でもない。バイトコードの `JsonTypeInfo` から読んだ)
- **`priority` と `parallelism` は必須**。省略すると enum 変換が
  `NullPointerException: Name is null` になり、**HTTP 500** が返る
- `GET /api/v2/jobs/plugin-info` は `plugin-info.json` が未生成だと 404。
  プラグイン ID は fat jar の中の `roda-core-6.3.0.jar` から読める
- **プラグインは 1 本ではない。** `org.roda.core.plugins.base.ingest` に E-ARK が
  2 本ある。どちらを指すかで結果が変わる (末尾「初回の誤り」)

### この環境では索引を読む API が全部 0 を返していた — 集計欄も含めて

**8-27 の再測定環境には、索引読み取りの障害があった。** `POST /api/v2/*/find` も
`/count` も**全コレクションで 0** を返す一方、Solr を直接引くと `Job` 16 件・
`AIP` 2 件・`TransferredResource` 6 件が在る。

同じ症状の一部として、`GET /api/v2/jobs/{id}` の `jobStats` も更新後の結果を
返さなかった。成功したジョブも失敗したジョブも**揃って**こうである:

```
state = COMPLETED   completionPercentage = 0
sourceObjectsProcessedWithSuccess = 0   ...WithFailure = 0   ...WaitingToBeProcessed = 1
```

`AIP` が実際に 1 件生まれた側もこの表示だった。

> **これは「RODA 6.3.0 の `jobStats` は信用できない」ではない** (外部レビュー指摘)。
> 8-26 の初回測定では集計欄は正しく動いていた。**健全な環境で `jobStats` が
> 非信頼だ、ということは確かめていない。** 分かっているのは、
> **索引読み取りが壊れた環境では集計欄も一緒に壊れる**ということだけである。

**この症状が出ている環境での読み方** (実際に使った手順):

- **AIP がディスクに在るか** — `docker exec roda-roda-1 find /roda/data/storage/aip/... -type f`。
  索引ではなく storage を見るので、この障害の影響を受けない
- **`JobReport.pluginState`** — API では引けなかったので、**Solr を直接引いた**:
  `docker exec roda-solr-1 curl -s 'http://localhost:8983/solr/JobReport/select?q=jobId:<id>&wt=json'`。
  ここに `pluginState = SUCCESS / FAILURE` と `outcomeObjectId` が入っている

集計欄だけを見ると、**成功を「何も起きなかった」と読む**。初回に
「プラグインが違う」と気づけなかったのも、索引を読む層を信じたことが一因である。

### 結果 1 — E-ARK SIP は **取り込める** (`EARKSIP2ToAIPPlugin`)

**同一の package を、同一インスタンスに、数分差で 2 本のプラグインへ投げた。**

| プラグイン | 呼ぶパーサ | `JobReport.pluginState` | 生成された AIP |
|---|---|---|---|
| `EARKSIP2ToAIPPlugin` | `commons_ip2...EARKSIP.parse` (CSIP 2.x) | **SUCCESS** | `28da89b5-…` |
| `EARKSIPToAIPPlugin` | `commons_ip...EARKSIP.parse` (**v1**, E-ARK SIP 1.x) | **FAILURE** | `NO_OUTCOME_ID` |

生まれた AIP の中身 (`/roda/data/storage/aip/28da89b5-…/`):

```
representations/rep1/data/_________.pdf        ← 本文がそのまま入っている
metadata/descriptive/dc.xml
metadata/descriptive/nemaki-authenticity-report.json
metadata/descriptive/nemaki-evidence.json
metadata/preservation/urn:roda:premis:event:….xml   (2 件)
schemas/{mets1_12,DILCISExtensionMETS,DILCISExtensionSIPMETS,xlink}.xsd
```

**RODA は METS を読んでいる。** 根拠 2 つ:

- **`AIP.ingestSIPIds` が zip 名ではなく `nemaki-bedroom-2878786f…`** になっていた。
  これは我々の METS の `@OBJID` である — バイトコードで確認: commons-ip2 の
  `EARKUtils` が `Mets.getOBJID()` を読んで `IPInterface.setIds` に渡している
  (zip 内のフォルダ名から derive しているのではない)。
- **`metadata/other/` に入れた JSON 2 本が `metadata/descriptive/` へ移された。**
  展開するだけならディレクトリは動かない。**metadata の分類を解釈した**跡である。

> **配置そのものは根拠にならない。** 本文が `representations/rep1/data/` に在ることは
> METS と**矛盾しない**が、CSIP の zip は元からその構造を持っているので、
> METS を読まずに展開しても同じ場所に出る。bag 経路との違い
> (丸ごと 1 ファイル / 中身が展開された) も、展開したかどうかの差でしかない。
> 上の 2 つと違って、これは METS をパースした証明にならない (外部レビュー指摘)。

#### ただし、渡したものが全部そのまま残るわけではない

測り方: `docker exec roda-roda-1 find /roda/data/storage/aip/{aipId} -type f` で
**AIP 全体を 12 ファイル**列挙し (representation 配下も含む — `representations/rep1/metadata/`
のような場所は存在しなかった)、その上で `grep -rl` / `grep -c` を掛けた。

- **我々の `premis.xml` と、その PREMIS object / event / agent レコードは、
  生成された AIP の PREMIS metadata に無い。** `metadata/preservation/` に在るのは
  RODA が自分で作った 2 件だけで、`eventType` は `wellformedness check` と `unpacking`。
  PREMIS の **object レコードは 0 件**。我々の object identifier
  (`bedroom/2878786f…`) が現れるのは `descriptive/` の 3 ファイルと
  `other/OTHER/{aipId}` と `aip.json` だけだった。

  > **「値が別の形に取り込まれた」可能性は否定していない** (外部レビュー指摘)。
  > 測ったのは「元の PREMIS 文書とそのレコードが無い」ことであって、
  > RODA が一部の値を PREMIS でない AIP のフィールドへ写したかどうかは調べていない。
  >
  > **「object レコード 0 件」の方は交絡している。** RODA 自身の PREMIS object は
  > 後続プラグインが書くので、SIP→AIP だけ走らせた AIP では**どんな SIP でも 0 件**に
  > なりうる。**残っている主張は前半 (我々の premis.xml が無い) だけ**で、
  > こちらは SIP の metadata を写すのが SIP→AIP 段しかない以上、成り立つ。

- 我々が `metadata/other/` に置いた JSON 2 本は **`metadata/descriptive/` に在った**
  (`metadata/other/OTHER/{aipId}` にも同じ 1642 バイトが在る)。
- **`metadata/preservation/ers.der` は測っていない。** ERS を同梱する経路は在る
  (`ErsFormat.CSIP_LOCATION = "metadata/preservation"`) が、**投入した package に
  ers.der は入っていなかった** (zip の中身は 11 ファイルで、`metadata/preservation/` は
  `premis.xml` だけ)。同じディレクトリに置く `premis.xml` が残らなかった以上、
  **ERS も残らない可能性が高いが、それは推測であって測定ではない**。
  タイムスタンプ証跡はここで運ぶ物の中で最も重いので、**別途測ること**。
- AIP の `state` は **`INGEST_PROCESSING`**。`ACTIVE` ではない。SIP→AIP の
  プラグインだけを走らせたので、受入承認まで含む ingest workflow は通していない。

> **「AIP object になった」はここまでである。** 本文と JSON は届き、METS は読まれた。
> **我々の PREMIS 文書は届いていない**、**ERS は未測定**、
> **受入が承認された状態にもなっていない**。
>
> **→ ERS はこの後の追試 1 で測った。結論は覆っている**: `metadata/preservation` に
> 置くと **package ごと落ちる**ので、この節が「AIP object になった」と書けたのは
> たまたま `ers.der` が書かれなかったからである。`metadata/other` へ移して測り直した。
> ここで読み終えないこと。

### 結果 2 — BagIt も AIP object になった。ただし manifest 1 本の bag だけ

`BagitToAIPPlugin` に投げると、最初は失敗した:

```
Binary already exists: .../representations/rep1/data/nemaki-....zip
Transaction was rolled back
```

引き金は我々の側にある。`BagItTransferPackager` は SHA-512 と SHA-256 の
**manifest を 2 本**書いていた。**ただしそれは規格違反ではない** — RFC 8493 §2.1.3 は
複数の payload manifest を明示的に許す。**落ちるのは受け手の側**で、機構は
バイトコードで確かめてある: `BagitSIP.parse` (これも **commons-ip v1**) が
`Bag.getPayLoadManifests()` を回し、manifest ごとに `getFileToChecksumMap()` の
各エントリから `IPFile` を作って `IPRepresentation.addFile` する — 重複を落とさない。
`BagitToAIPPluginUtils` はそれを 1 件ずつ `ModelService.createFile` に渡すので、
2 本目で「もう在る」になる。

manifest を 1 本にした同一の bag を投入 → **SUCCESS**。
`AIP` が 1 件生成され、representation と payload ファイルが入った。

そこで一度 **SHA-512 の 1 本だけを書く**ようにした。**翌 8-27 に 2 本へ戻している** (§6)。

戻した理由は「1 本にした理由が消えたから」ではない。**この層の受け手は RODA ではないのに、
RODA の bag パーサの欠陥が既定の形式を決めていた**からである。E-ARK 経路が通ると分かった
時点で、RODA に bag を送る理由は無くなった。1 本のまま置くことは、**使わせない受け手向けの
回避策を、まだ測っていない正の受け手に渡し続ける**ことになる。

失っていたものは準拠ではない (§2.1.3 は 1 本も許す)。**SHA-256 が
`manifest-sha256.txt` という path→digest の束縛でなくなり、`bag-info.txt` の
照合されない 1 行だけになっていた**ことである。

**ただし 2 本の bag には、どの受け手でも取込の実績が無い。** 言えるのは
「規格が許す形に戻した」と「commons-ip v1 の bag パーサでは rollback する (実測)」まで。
Archivematica は bagit-python 系の検証器で読むので同じ機構には当たらないはずだが、
**そこも測っていない**。

### 結果 3 — つまり RODA には 2 つの経路があり、E-ARK の方が多くを運ぶ

| | 経路 | 先方が読むもの | 本文の在り処 |
|---|---|---|---|
| E-ARK | `EARKSIP2ToAIPPlugin` | **METS を読む**。`OBJID` が AIP に入る | `representations/rep1/data/` に**そのまま** |
| bag | `BagitToAIPPlugin` | payload の中身は見ない | SIP の zip が**丸ごと 1 ファイル**として |

**bag 経路は RODA に入るための必須条件ではなくなった。** BagIt 接続層が要るのは
E-ARK 相当の transfer type を持たない受け手 — 今のところ Archivematica — のためである
(§6)。`LIMITS` が「受け手は payload を不透明なファイルとして読む」と言うのは
**bag 経路について**であって、E-ARK 経路には当てはまらない。

### 追試 (2026-08-27 夕) — 残していた 3 点を測った

RODA を立て直し、前の節が「未測定」と書いた 3 つを潰した。

#### 1. `ers.der` は置き場で結果が割れる。`metadata/preservation` に置くと package ごと落ちる

前回は投入した package に `ers.der` が入っていなかったので未測定だった
(このノードに確定した RFC 3161 トークンが無く `EvidenceRecordService.latest` が absent を
返すため)。スタブの DER を注入して exporter を走らせ、**同じ exporter・同じ object で
置き場だけを変えた 3 本**を投げた:

| package | `ers.der` の位置 | `pluginState` | AIP に残ったか |
|---|---|---|---|
| `nemaki-sip-ers.zip` | `metadata/preservation/` | **FAILURE** — `Failed to load PREMIS: null` で rollback | — (1 件も入らない) |
| `nemaki-sip-noers.zip` | 無し (対照) | **SUCCESS** — AIP `fce8e101-…` | — |
| `nemaki-sip-ers-other.zip` | **`metadata/other/`** | **SUCCESS** — AIP `b0c6a41b-…` | **残った** — `metadata/descriptive/ers.der` にバイト列ごと |

> **RODA は `metadata/preservation/` の中身を全部 PREMIS として読む。**
> PREMIS でないファイルが 1 つ在ると、**その package は 1 件も取り込まれない**。

**変数はディレクトリだけである。** 3 本目の METS を見ると、`mdRef` の
`MIMETYPE="application/x-x509-ca-cert"` も `MDTYPE="OTHER"` も `CHECKSUM` も
1 本目と**完全に同一**で、違うのは `xlink:href` だけだった。
→ **「commons-ip2 が probe した media type で分岐した」という説は消える** (外部レビュー指摘)。

**そしてこれは我々の側の問題だった。** CSIP の `metadata/preservation` は
**PREMIS の置き場**であり、「証拠記録は保存メタデータだから」というのは OAIS の分類を
CSIP のディレクトリに載せた読み違いである。RODA が `MDTYPE` を見ないのは、その上に
乗った受け手の癖にすぎない。**同じ増分で BagIt については逆の判断をしている**
(使わない受け手のパーサ欠陥に形式を合わせない) が、あちらは我々の形が正しく、
こちらは我々の形が間違っていた。→ **`metadata/other` へ移した** (§11)。

> **測っていないこと**: 使った DER は**スタブのバイト列**である。本物の RFC 3161 ベースの
> ERS でも同じかは確かめていない — ただし置き場が原因だと分かった以上、
> 中身によらず同じになる公算が高い。**「高い」は測定ではない。**
>
> **`metadata/descriptive/` へ移されたことは、残ったこととは別の話である。** 証拠記録は
> 記述メタデータではない。**AIP を受け取った側が `other/` を探しても見つからない。**

#### 2. 現行の bag (manifest 2 本) は RODA では **rollback する**

§6 が「どの受け手でも取込実績が無い」と書いていた形を、実際に投げた。予測どおり:

```
Binary already exists: …/representations/rep1/data/nemaki-sip-noers.zip
Transaction was rolled back
```

**これで現行の出荷形について、RODA では否定的な結果が 1 件付いた。**
Archivematica は依然として未測定である。

#### 3. RODA は受領証を返さない。語彙は 2 つの enum

`/api/v2/**` のコントローラを全部数えた (28 本)。**受領証に相当するものは無い。**
接続層が組み立てるなら、材料は `JobReportController` と `AIPController` の 2 つになる。

| 出所 | 値 |
|---|---|
| `JobReport.pluginState` | `SUCCESS` / `PARTIAL_SUCCESS` / `FAILURE` / `RUNNING` / `SKIPPED` |
| `AIP.state` | `CREATED` / `INGEST_PROCESSING` / `UNDER_APPRAISAL` / `ACTIVE` / `DELETED` / `DESTROYED` / `DESTROY_PROCESSING` / `RESTORE_PROCESSING` |

`CustodyReceipt.reportsSuccess()` が受ける語は
`PASSED / PASS / VALID / SUCCESS / ACCEPTED / OK`。突き合わせると:

- **`pluginState` を入れるなら合っている。** `SUCCESS` は通り、`PARTIAL_SUCCESS` は
  通らない (§1.4 が「部分受入は成功として扱わない」と決めているのと一致)。
  `FAILURE` / `SKIPPED` / `RUNNING` も通らない。
- **`AIPState` を入れると壊れる。** 受入が完了した状態である **`ACTIVE` は
  この語彙に無い**ので、正常に受け入れられた AIP が「成功ではない」と読まれる。
  接続層を書くときに踏む。

**署名は無い。** RODA が返せるものはどれも認証されていないので、
RODA から組み立てた受領証は `signatureVerified = false` のままになる
(その扱いは §9 と `limits()` が既に持っている)。

> **語彙は「実機で見た」ところまで固定できた**が、**受領証そのものを組み立てて
> 検証する経路はまだ書いていない**。`reportsSuccess()` を RODA に対して
> 「確かめた」と言えるのは `pluginState` を入れる場合だけである。

### この試験が確かめたこと / 確かめていないこと

**確かめた**: NemakiWare の E-ARK SIP は RODA 6.3.0 の `EARKSIP2ToAIPPlugin` が
AIP object にする — 本文・`dc.xml`・我々の JSON 2 本が入る。同じ package を
`EARKSIPToAIPPlugin` (E-ARK SIP 1.x) に投げると FAILURE になる。
manifest 1 本の bag も AIP object になり、**2 本だと rollback する**。

**追試で確かめた** (上): `ers.der` は `metadata/other` なら取り込まれ、AIP に残る
(`metadata/descriptive/` へ移されて)。`metadata/preservation` に置くと package ごと落ちる。
**現行の出荷形 (manifest 2 本の bag) は RODA では rollback する。**
RODA に受領証のリソースは無く、語彙は `JobReport.pluginState` と `AIP.state` の 2 つ。

**確かめていない**: 他版の RODA、**Archivematica (bag も E-ARK も、どちらの形も)**、
受入承認まで含む ingest workflow、受領証を組み立てて検証する経路そのもの、
本物の RFC 3161 ベース ERS での再現。
**AIP object ができたことは「先方が保持し続ける」ことでも「AIP が正しい」ことでもない。**
AIP は `INGEST_PROCESSING` のままで、**受入が承認された状態ではない**。
**我々の PREMIS 文書は AIP の PREMIS metadata に無い。**

> なお `EXPORT_LIMITS` は最初から「**NOT a statement that any particular archive
> will accept it**」と書いていた。この但し書きは、**受け入れられた今も**必要である
> — 通ったのは RODA 6.3.0 の 1 プラグインであって、「どの archive でも通る」ではない。

### 初回の誤り — 何を間違えたか

**消さずに残す。** 同じ形の誤りを次に踏まないために書いておく。

**誤り**: 「E-ARK SIP は RODA 6.3.0 が取り込まない」「RODA は commons-ip2 の検証も
パースも使わず、独自の METS 検証で落としている」「両方を同時に満たす METS は書けない」。
**3 つとも成り立たない。**

**何が起きていたか**。`EARKSIPToAIPPlugin` は **commons-ip v1** の
`org.roda_project.commons_ip.model.impl.eark.EARKSIP.parse` を呼ぶ。この v1 API は
**commons-ip2 の jar の中に同居していて**、自分の `schemas/mets1_11.xsd` で JAXB 検証する。
METS 1.11 の `note` は `type="xsd:string"` — **単純型**。CSIP 2.2.0 (METS 1.12、DILCIS 修正版)
の `note` は complexType で `csip:NOTETYPE` を持つ。だからあのエラーが出た。
**RODA 独自の検証ではなく、commons-ip 自身の、古い profile 版の検証**である。

**手元で再現した。RODA が積んでいる 2.11.3 そのものを使った** — 具体的には
`roda-wui-6.3.0.jar` の `BOOT-INF/lib` を展開してクラスパスにし、
`org.roda_project.commons_ip.model.impl.eark.EARKSIP.parse` を直接呼んだ。コンテナは不要:

```
V1 PARSER isValid = false
  [ERROR] Main METS.xml file is not valid. | jakarta.xml.bind.UnmarshalException
    lineNumber: 6; columnNumber: 52; cvc-type.3.1.1: 要素'note'は単純型であるため …
    属性'csip:NOTETYPE'が見つかりました
```

`csip:NOTETYPE` だけを取り除いて同じ parser に渡すと、次は
`METS 'TYPE' attribute does not contain a valid value` になった。**二段ある。**

> **この 2.11.3 の再現は 1 回きりの手元実行で、CI には入っていない** — この build が
> 依存しない jar が要るため。CI に入れた `LegacyEarkParserRejectsOurSipTest` が
> 固定するのは、**我々がビルドに使う commons-ip2 (現 2.12.0) の中の v1 と v2 の差**で
> あって、RODA の取込そのものではない。**実機の結論は実機の測定として残る。**
> ここを曖昧にすると、まさに今回の誤り —「同じ jar に入っている、測っていない側の
> API を、測った側と同一視する」— を繰り返すことになる。

**そして測定表が的を外していた。**「commons-ip2 2.11.3 (RODA が積んでいる版) の
validator は valid」と書いたが、**RODA はこの取込経路でその API を呼ばない**。
同じ jar の中の別の (v1 の) API を呼ぶ。**同じ jar だから同じ判定だろう、と
確かめずに書いた** — これが誤りの本体である。

**「両方を満たす METS は書けない」は前提から崩れている。** そもそも両方を満たす
必要が無い (版に合ったプラグインを指せばよい)。加えてこの命題自体も強すぎた:
`mets1_12.xsd` の `note` は `minOccurs="0"` なので、note を出さなければ両方の
スキーマを満たす。成り立つのは「commons-ip2 の v2 writer は `createMETSAgent` で
note を**無条件に**書く (バイトコードに分岐が無い) ので、**我々の生成器では**
note 無しの package は作れない」までである。

**教訓 3 つ**:

1. **受け手のプラグインは 1 本とは限らない。** 「拒否された」は「その実装が拒否した」
   であって、製品全体の答えではない。同名同系統の実装が複数在るかを先に見る。
2. **「同じ jar だから同じ経路」ではない。** 呼ばれるクラスの**完全修飾名**まで
   確かめる。`commons_ip` と `commons_ip2` は 1 文字違いで別のライブラリだった。
3. **足りなかったのは「同じ入力」ではなく、正のコントロールである。**
   初回も同一 package を同一インスタンスに投げている。欠けていたのは
   **比較対象になるもう 1 つの実装**で、「拒否された」を「拒否しない実装は無いのか」と
   突き合わせる相手が居なかった。再測定で SUCCESS / FAILURE が並んだのは、
   その相手を用意したからである。**否定的な結果は、単独では環境の故障と
   区別できない。**


---

## 11. 証拠記録の置き場を `metadata/other` に変えた (2026-08-27)

**決着済み。** §10 の追試 1 で測って浮き、同じ日に直して測り直した。

### 何が間違っていたか

`ErsFormat.CSIP_LOCATION` は `metadata/preservation` を選んでいた。理由は
「証拠記録は保存メタデータであって、記述メタデータでも documentation でもない」。
**これは OAIS の分類を CSIP のディレクトリに載せた読み違いである。**
CSIP の `metadata/preservation` は「保存メタデータ一般」の場所ではなく、
**PREMIS の場所**である。RFC 4998 の証拠記録は ASN.1 の DER で、PREMIS ではない。

RODA 6.3.0 がそこを PREMIS として読み、読めないと package ごと rollback するのは、
その読み違いの上に乗った受け手の癖にすぎない。**癖が無ければ気づかなかっただけで、
置き場は元から間違っていた。**

> **同じ増分で BagIt については逆の判断をしている** (§6): 使わない受け手のパーサ欠陥に
> 出荷形を合わせない。矛盾ではない。**あちらは我々の形が正しく、こちらは間違っていた。**
> 「RODA が `MDTYPE` を見るべきだ」で押し通すのは、bag でやらなかったことを
> **使う受け手に対して逆向きにやる**ことになる。

### 直した内容と、測り直した結果

`metadata/other` へ移した。**定数と呼び分けの両方**を変えている — `CSIP_LOCATION` は
位置を*記述*しているだけで、*決めて*いるのは `addPreservationMetadata` /
`addOtherMetadata` のどちらを呼ぶかである (負のコントロール `cg12` が空振りして分かった)。

測り直し (§10 追試 1 の 3 本目):

- **取り込まれた** — `pluginState = SUCCESS`、AIP `b0c6a41b-…`
- **残った** — バイト列ごと。**ただし `metadata/descriptive/ers.der` へ移されていた**

> **`descriptive/` へ移されたことは別の問題である。** 証拠記録は記述メタデータではない。
> **AIP を受け取った側が `other/` を探しても見つからない。** 我々の JSON 2 本も同じ扱いを
> 受けているので、これは RODA が `other/` に対して一貫してやることらしい。
> **「らしい」は測定ではない** — 確かめたのは我々の 3 ファイルについてだけである。

### 併せて分かった小さいこと — `ErsFormat.mediaType()` は使われていない

enum は `application/octet-stream` を「この file が宣言される media type」として持ち、
ASiC-E ではない理由まで書いてある。**だが METS には反映されていない。** commons-ip2 が
ファイルを probe した結果が入り、今回のスタブでは
`MIMETYPE="application/x-x509-ca-cert"` になっていた。`IPFile` に media type の setter が
無いので、公開 API のままでは反映できない。**javadoc の言い方を実態に合わせた。**

なおこの probe された型は、**置き場を変えた 3 本目でも同じ値**だった
(`MDTYPE` も `CHECKSUM` も同一で、違うのは `xlink:href` だけ)。
つまり **1 本目が落ちた原因は media type ではなくディレクトリである** — これが
「ディレクトリを全部 PREMIS として読む」という診断の対照になっている。
