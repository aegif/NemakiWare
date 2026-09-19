# P3-4 — 保存システムへの移管 (custody transfer)

> 取込の fail-closed（DLQ / IDLE / 設定読み）の**今の**主張・凍結・残件は
> [`fail-closed-reads.md`](fail-closed-reads.md)。
> 巡の本文は [`../history/fail-closed-review-rounds.md`](../history/fail-closed-review-rounds.md)
> （引用するな）。

作成 2026-08-26。ロードマップ §4 Phase 3 の 4 番目。前提は P3-1 (SIP) と P1-3 (台帳)。

---

## 0. 何を主張し、何を主張しないか

**主張する**: **管理責任の移転を 9 段階 + `FAILED` の状態機械として管理し、
「報告されたこと」と「こちらが確かめたこと」を別の状態として持つ**
(送信経路が入るまで、前半は**誰かが記録したこと**までである — §21)。
検証済み受領証を証拠連鎖に載せる型が在り、載せられなければ**拒否を返す**。

> **「載せられなければ custody は渡らない」は規約から構造になった** (2026-08-26、§7)。
> 当初は `advance(CUSTODY_TRANSFERRED, …)` が受領証の有無しか見ておらず、fail-closed は
> 呼び出し元の作法に依っていた — そしてその呼び出し元が存在しなかった。
> 現在は `CustodyTransferService.passCustody` が**先に記録し、効いたときだけ進む**唯一の扉で、
> `advance` は `CUSTODY_TRANSFERRED` を明示的に拒否する。
> **この段落は当初の状態を記録として残してある** — 直った経緯ごと消すと、
> なぜ扉が 1 つなのかが読めなくなる。

**主張しない**:

- **送信は実装していない。** package を受け手に渡す HTTP は無い (§5)。
  **受領証の組み立ては在る** (§14) — そこは受け手から digest を回収するために
  実際に GET を打つので、「HTTP は一切無い」はもう真ではない
- **署名は検証していない。** 検証の**機構は在る** (§8 — 鍵が設定されていれば到着時に検査する)
  が、**測った受け手はどちらも署名を返さない**ので、実際に検証したことは一度も無い。
  `signatureVerified` は既定 false で、それが正直な状態である
- **先方のコピーが健全であることは言わない。** そして**署名の無い受領証が establish
  するのは、そこまでですらない** — 受領証は REST で届く陳述で、`signature` が無ければ
  「**この製品が、我々の package を名指す陳述を受け取った**」までである。
  **署名を検証できても「先方が取り込んだ」ことにはならない** — 検証が establish するのは
  **「渡された鍵の持ち主がこの受領証を作った」**までで、その鍵が本当に相手組織のもので
  あることも、書かれた内容が真実であることも言わない (鍵の入手と信頼は submission
  agreement §1.7)。なお**測った受け手はどちらも署名を返さない**。
  `CustodyReceipt.limits()` が全受領証にそう書いて同行する

> **この段落は 12 個目の出口だった** (2026-08-27)。1 巡目に RELEASE_NOTES で
> 「署名を検証できたときにだけ言える」を取り下げながら、**正典の §0 に同じ文が
> 残っていた** — 読者が最初に当たる場所で、しかも 5 巡のレビューは
> **どれも §0 を開いていない**。§15〜§22 の訂正が届く先として、
> **文書の冒頭が最後に残る**というのが 6 巡目の形である。
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
| `AIP_CREATED` | **誰かが記録したこと**。保存コピーが在ると記録された (**誰の主張かは確かめていない**) |
| `RECEIPT_VERIFIED` | **こちらが確かめたこと**。受領証がこちらの package を指している |

一緒にすると、**このリポジトリの記録が、未検証の主張に依存する**ことになる。
custody 移転において、それが起きてはならない唯一の場所である。

> **1 行目は当初「先方が言ったこと。保存コピーが在ると先方が報告した」だった** —
> 4 巡目の訂正が届いていなかった (2026-08-27、§21)。`AIP_CREATED` は
> **`POST /advance` でしか到達せず、この版に送信経路は無い**ので、
> **先方から聞いた事実は 1 つも無い**。`CustodyState.limits()` を
> `SOMEBODY RECORDED` に直しながら、**正典の「意味」欄が古い方を教え続けていた** —
> ロードマップを直した理由と同じ種類の残骸である。
> 「先方が言ったこと ↔ こちらが確かめたこと」という**対比そのものは正しい**が、
> 送信経路が入るまで、左側は「誰かが記録したこと」までしか言えない。

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
`submissionId` / `aipId` / `receivingAgent` / `receivedAt` の
**どれか 1 つでも空なら拒否**する。

> **初稿はここに `aipChecksum` も入れていた** (ロードマップ行が挙げている中身をそのまま
> 必須にした)。**2026-08-27 に外した** — RODA の取込が返したものに自分の AIP の checksum は無かったので、
> 必須にすると成功した RODA の受領証が必ず拒否される。経緯と、外して何を失うのかは **§16**。
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
| **RODA / Archivematica への実際の送信** | **未**。§9-3 / §9-4 に API と落とし穴は調査済み。受領証の**組み立て**は §14 で実装済みだが、そこへ渡す識別子を得る経路 (投入と待ち) は無い |
| **`passCustody` の台帳先行窓** | **未 (錠なし)**。台帳へ書いてから row を書くので、row の書込に負けると**台帳に entry が在り transfer が反映していない**状態が残る。元から在った窓だが、§15 の rev 修正で**現実に踏むようになった**。言葉にはなっており (`persist` が負けた側に手元の object を返さず、サービスが「連鎖に、この transfer が反映していない entry が在る」と述べる)、再試行も安全 (`recordVerifiedReceipt` は digest 冪等で、状態が `RECEIPT_VERIFIED` でなければ拒否)。**台帳とサービスをまたぐ錠は無い** |
| **写像を受け手に束ねない緩さ** | **意図的・未決**。`isDerivableMapping` は「この製品が知る**どれか**の受け手が作る組か」で判定するので、`COMPLETE → SUCCESS` (AM の写像) は RODA の transfer でも通る。束ねるには自由記述の `receivingSystem` を enum に照合する必要があり、それは推測になる。**今日はどの受け手も署名しない**ので、偽造者がこの経路を選ぶ理由も無い。**署名が実際に入ったら決め直す** |
| **BagIt (`zipped bag`) 接続層** | **実装済み・REST から到達可能** (2026-08-26)。§6。`POST /v1/admin/eark/{repo}/objects/{id}/bag`。`gov.loc:bagit` は core/pom.xml に明示宣言した |
| **署名検証** | **配線済み** (2026-08-26、§8)。受領証が**到着した時点で**検査する (`custody.receipt.key.<agent>` が設定されたときだけ)。保存された `signatureVerified` は読み戻しで信じないので、所見はここで作るしかない。鍵が読めない・無いは「検査していない」であって不正ではない。**鍵の入手と信頼は依然 submission agreement 側** |
| **永続化** (transfer の store) | **実装済み** (2026-08-26、§7)。evidence-ledger DB に同居。読み出しは `restore` を通り、履歴が合法な歩みでなければ拒否される |
| **fail-closed を強制する呼び出し元** | **実装済み** (2026-08-26、§7)。`CustodyTransferService.passCustody` が先に記録し、記録が効いたときだけ進む。`advance` は `CUSTODY_TRANSFERRED` を明示的に拒否する (扉は 1 つ) |
| **スレッド安全性** | **危険の在り処が違っていた** (2026-08-27、§15)。`CustodyTransfer` インスタンスは 2 つのスレッドで共有されない — store は要求ごとに row から新しい object を decode し、cache は無い。**実害は object の data race ではなく、row の lost update だった**: `save()` が書込時に現在 rev を引き直していたので、同じ row を読んだ 2 要求が両方 update に 成功し、**先に書いた側の移動が黙って消える**。読んだ rev を持ち回る形に直し、負けた側は `false` で拒否される (`StaleWritesAreRefusedTest`)。**object 自体は今も非同期化**だが、共有する経路は無い |
| **`reportsSuccess()` の語彙を実機で確認** | **RODA については採れた** (§10 追試 3)。RODA に受領証と分かるリソースは無く、材料は同じ `Report` に載る `pluginState` (`SUCCESS`/`PARTIAL_SUCCESS`/`FAILURE`/`RUNNING`/`SKIPPED`) と `outcomeObjectState` (`ACTIVE` ほか)。**`pluginState` を入れるなら噛み合う** — `SUCCESS` は通り `PARTIAL_SUCCESS` は通らない (§1.4 と一致)。**`outcomeObjectState` を入れると壊れる**: 受入完了の `ACTIVE` がこの語彙に無い。さらに **応答フィールドには SIP の checksum が無い**ので、それだけで組み立てると `sipDigest` が自分の値との比較になる — `/transfers/{uuid}/download` で先方の bytes を取ってハッシュすること (§10 追試 3.1)。**Archivematica 1.18.0 も採れた** (§12): transfer/SIP の `status` は `COMPLETE` / `FAILED` (ほかソース上 `REJECTED` / `USER_INPUT` / `PROCESSING`)、SS の package は `UPLOADED`、`check_fixity` の `success` は boolean。**どれも語彙に無い** (`COMPLETE` も `UPLOADED` も通らない)。接続層は写像が要る。**組み立ての経路は実装した** (2026-08-27、`jp.aegif.nemaki.custody.connector` — §14): 受け手ごとの読む欄と写像、受け手が持っている物からの digest 回収、一致しなければ**組まない**。**両受け手で実機一周した** (2026-08-27、§16 RODA / §17 AM)。回収値は送った物と一致し、受領証は組み上がり、状態機械が `RECEIPT_VERIFIED` を受理した。**実機でしか出ない欠陥が 2 件出た** — `aipChecksum` 必須で成功した RODA 受領証が必ず拒否されていた (§16 で外した)、AIP ルートに **AM 自身の manifest という囮**が在った (§17。負のコントロール実測済み)。**未**: 送る口 (HTTP) |
| **submission agreement の明文化** (失敗・再送・重複取込・部分受入・先方 AIP 再生成) | **雛形あり** (2026-08-26): [`docs/operations/custody-submission-agreement.md`](../operations/custody-submission-agreement.md)。7 項目と、本製品が既に決めていて交渉できない側の分離。**合意そのものは当事者間の作業で、software では閉じない** |
| 実機受入試験 | **RODA 6.3.0 の SIP→AIP プラグイン** (§10) と **Archivematica 1.18.0 の automated ingest** (§12) を実施済み。RODA: E-ARK SIP は `EARKSIP2ToAIPPlugin` で AIP object になり、**`ers.der` も `metadata/other` なら取り込まれて残る** (`metadata/preservation` に置くと package ごと rollback する — §11 で直した。**投げたのはスタブの DER で、本物の RFC 3161 ベース ERS では未測定**)。bag は **manifest 1 本なら** AIP object になり、**現行の出荷形 (2 本) は RODA の bag 経路では rollback する**。RODA の AIP は `INGEST_PROCESSING` 止まり (受入承認の workflow は未実施)。**我々の PREMIS 文書は RODA の AIP PREMIS に無い**。AM: 出荷形 bag も E-ARK SIP の `zipfile` も AIP `UPLOADED` まで行った。**受領証の組み立ては RODA (§16) と Archivematica (§17) の両実機で通した** (2026-08-27)。**未**: NemakiWare からの HTTP 送信、他版、AM の default processing config、本物の ERS |

---

## 6. BagIt 接続層 (2026-08-26)

Archivematica の転送 type は `standard / zipfile / unzipped bag / zipped bag / dspace /
maildir / TRIM / dataverse` の 8 種で、**この一覧に E-ARK / CSIP 専用のものは無い**。
そこで `zipped bag` が**実装可能な候補経路**になる。

> **「必須」とは書かない** (外部レビュー指摘 2026-08-27)。**2026-08-27 に測った**
> (§12): 同じ E-ARK SIP を `zipfile` に投げても AIP になった。展開したディレクトリを
> `standard` に投げても AIP になった。**BagIt は必須ではない。** `zipped bag` を選ぶ
> 積極的な理由は残っている — その type だけが `Verify bag` を走り、出荷形の
> SHA-256 が受け手の検証器の照合対象になる。`zipfile` はそれを走らない。
>
> **この層は「E-ARK 経路を持たない受け手」のためのものである。** RODA 6.3.0 は
> E-ARK SIP から AIP object を作れることを実測した (§10 結果 1) ので、RODA に対しては
> bag 経路を選ぶ理由が無い — E-ARK 経路の方が本文も METS も運ぶ。

**これは受け取り側が package を理解するようにする層ではない。** METS は読まれず、
構造は尊重されず、これによって Archivematica の AIP が E-ARK AIP になることもない。
「BagIt コネクタが在る」を「Archivematica が我々の E-ARK SIP を取り込む」と読まれると、
このコードがしないことを言ったことになるので、`LIMITS` が全 bag に同行する。

> **「payload の中の 1 ファイルのまま残る」とは書かない** (2026-08-27 実測、§12)。
> `automated` の processing config は **packages を展開する**ので、bag の payload に
> 入れた SIP の zip はそのまま残らず、**AIP の `objects/` に SIP のツリーが入る**。
> 展開されないと書いていたのは推測だった。
> **それでも「理解される」ことにはならない** — 展開されたツリーは AM から見れば
> ただのファイル群で、METS は解釈されず、AIP は AM の AIP のままである。

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
1 本を既定に残すと、**使わせない受け手のパーサ欠陥が、当時まだ測っていなかった正の受け手向けの
形式を決め続ける**。それが戻した理由であって、「理由が消えたから」ではない。

**この層の本来の受け手 (Archivematica 1.18.0) では、2 本を測った** (§12)。

| | 実測された取込 |
|---|---|
| manifest 2 本 (現行の出荷形) | **AM 1.18.0 の `zipped bag` で AIP `UPLOADED`** (2026-08-27、§12)。RODA の bag 経路では **rollback** (同日、§10) — そこには SIP を送る |
| manifest 1 本 | RODA の bag 経路で 1 件成功。ただし**この設計が「選ぶ理由が無い」と書いた経路**。AM では未測定 |

つまりこれは「2 本ならどこでも通る」ではない。**「規格が許す形に戻し、SHA-256 を
検証器の照合対象に戻した」**であり、**その形で落ちると分かっている受け手が 1 つ在る
(RODA の bag 経路)**、**その形で AIP まで行った受け手が 1 つ在る (AM 1.18.0)**、である。

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
- **`Report.pluginState`** — API では引けなかったので、**Solr を直接引いた** (Solr の collection 名は `JobReport` で、こちらは型名ではない):
  `docker exec roda-solr-1 curl -s 'http://localhost:8983/solr/JobReport/select?q=jobId:<id>&wt=json'`。
  ここに `pluginState = SUCCESS / FAILURE` と `outcomeObjectId` が入っている

集計欄だけを見ると、**成功を「何も起きなかった」と読む**。初回に
「プラグインが違う」と気づけなかったのも、索引を読む層を信じたことが一因である。

### 結果 1 — E-ARK SIP は **取り込める** (`EARKSIP2ToAIPPlugin`)

**同一の package を、同一インスタンスに、数分差で 2 本のプラグインへ投げた。**

| プラグイン | 呼ぶパーサ | `Report.pluginState` | 生成された AIP |
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
> METS を読まずに展開しても同じ場所に出る。**RODA の** bag 経路との違い
> (丸ごと 1 ファイル / 中身が展開された) も、展開したかどうかの差でしかない
> (展開するかは受け手の設定次第で、AM は展開した — §12)。
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
- **`ers.der` は、この初回の package には入っていなかった** (zip の中身は 11 ファイルで、
  `metadata/preservation/` は `premis.xml` だけ)。当時の `ErsFormat.CSIP_LOCATION` は
  `metadata/preservation` で、「同じディレクトリの `premis.xml` が残らなかった以上、
  ERS も残らない可能性が高い」と書いていた。
  **→ 追試 1 で測った。推測より悪かった** — 残る残らない以前に、
  **package ごと落ちる**。置き場 (正確には METS の section) は §11 で直した。
  **この行の `CSIP_LOCATION` の値は当時のもので、現在は `metadata/other` である。**
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
回避策を、まだ測っていなかった正の受け手に渡し続ける**ことになる。
その受け手は同じ日に測った (§12)。

失っていたものは準拠ではない (§2.1.3 は 1 本も許す)。**SHA-256 が
`manifest-sha256.txt` という path→digest の束縛でなくなり、`bag-info.txt` の
照合されない 1 行だけになっていた**ことである。

**2 本の bag は、その時点ではどの受け手でも取込の実績が無かった。** 言えたのは
「規格が許す形に戻した」と「commons-ip v1 の bag パーサでは rollback する (実測)」まで。
同じ日の §12 で AM 1.18.0 がこの形を Verify bag し AIP にした。

> **→ 現行の 2 本 bag は、その後 RODA に投げた** (追試 2)。予測どおり
> `Binary already exists` で rollback した。**否定的な結果が 1 件付いている**ので、
> 「どの受け手でも実績が無い」はここで読み終えないこと。AM 側は §12。

### 結果 3 — つまり RODA には 2 つの経路があり、E-ARK の方が多くを運ぶ

| | 経路 | 先方が読むもの | 本文の在り処 |
|---|---|---|---|
| E-ARK | `EARKSIP2ToAIPPlugin` | **METS を読む**。`OBJID` が AIP に入る | `representations/rep1/data/` に**そのまま** |
| bag | `BagitToAIPPlugin` | payload の中身は見ない | SIP の zip が**丸ごと 1 ファイル**として |

**bag 経路は RODA に入るための必須条件ではなくなった。** BagIt 接続層は
E-ARK 相当の transfer type を持たない受け手 — Archivematica — のために残る (§6)。
**ただしそこでも必須ではなかった** — AM は同じ SIP を `zipfile` でも取り込む (§12)。
残る積極的な理由は `Verify bag` である。

> `LIMITS` の言い分は **bag 経路についてのもの**で、E-ARK 経路には当てはまらない。
> なお LIMITS は「不透明なファイルとして読む」とは**もう言っていない** — AM は
> 展開したので (§12)、「展開しうる。展開された ≠ 理解された」に直してある。

### 追試 (2026-08-27 夕) — 残していた 3 点を測った

RODA を立て直し、前の節が「未測定」と書いた 3 つを潰した。

#### 1. `ers.der` を `digiprovMD` に宣言すると package ごと落ちる

前回は投入した package に `ers.der` が入っていなかったので未測定だった
(このノードに確定した RFC 3161 トークンが無く `EvidenceRecordService.latest` が absent を
返すため)。スタブの DER を注入して exporter を走らせ、**同じ exporter・同じ object で
置き場だけを変えた 3 本**を投げた:

| package | `ers.der` の位置 | `pluginState` | AIP に残ったか |
|---|---|---|---|
| `nemaki-sip-ers.zip` | `metadata/preservation/` | **FAILURE** — `Failed to load PREMIS: null` で rollback | — (1 件も入らない) |
| `nemaki-sip-noers.zip` | 無し (対照) | **SUCCESS** — AIP `fce8e101-…` | — |
| `nemaki-sip-ers-other.zip` | **`metadata/other/`** | **SUCCESS** — AIP `b0c6a41b-…` | **残った** — `metadata/descriptive/ers.der` にバイト列ごと |

> **原因はディレクトリではなく、METS の section である** (外部レビュー指摘 2026-08-27)。
> `sip.addPreservationMetadata(...)` は METS の **`<amdSec><digiprovMD><mdRef>`** に宣言を書き、
> ディレクトリ名はその副作用でしかない。読み側もディレクトリを見ない:
>
> ```
> commons-ip2  EARKUtils.processPreservationMetadata
>                -> AmdSecType.getDigiprovMD() を回して SIP.getPreservationMetadata() に積む
> RODA         EARKSIP2ToAIPPluginUtils
>                -> その 1 件ずつを PremisV3Utils.binaryToGenericPremis に渡す
>                -> "Failed to load PREMIS: " はこの PremisV3Utils の中の文字列
> ```
>
> つまり **`digiprovMD` に PREMIS でないものを宣言したから落ちた**。
> `metadata/preservation/` に置いただけで `digiprovMD` の宣言が無いファイルは、
> そもそも PREMIS パーサに届かない。**「あのディレクトリに置くと落ちる」は誤り**で、
> 正しくは**「`digiprovMD` に宣言すると落ちる」**である。

**`MIMETYPE` は原因ではない。** 3 本目の `mdRef` の
`MIMETYPE="application/x-x509-ca-cert"` も `MDTYPE="OTHER"` も `CHECKSUM` も
1 本目と同一で、そこは変わっていない。
→ **「commons-ip2 が probe した media type で分岐した」という説は消える**。
**原因は METS の section** (`<amdSec><digiprovMD>` か `<dmdSec>` か) である。

> **「変数はディレクトリだけ」と書いていたのは誤りだった** (外部レビュー指摘 2026-08-27)。
> 呼び分けを変えると METS は **4 箇所**変わる。ディレクトリはそのうちの 1 つにすぎない:
>
> | | `addPreservationMetadata` | `addOtherMetadata` |
> |---|---|---|
> | section | **`<amdSec><digiprovMD>`** | `<dmdSec>` |
> | structMap div | `ADMID=…` LABEL=`Metadata` | `DMDID=…` LABEL=`Metadata/Other` |
> | `mdRef/@OTHERMDTYPE` | 無し | **在り** |
> | `xlink:href` | `metadata/preservation/ers.der` | `metadata/other/ers.der` |
>
> **効いているのは 1 行目である。** そして exporter はディレクトリだけを独立に変えられない
> (`ErsFormat.CSIP_LOCATION` は位置を記述しているだけ) ので、
> **「ディレクトリだけを変えた」実験は最初から作れなかった。**
> 上の比較が確かめたのは `MIMETYPE` / `MDTYPE` / `CHECKSUM` が同一だったことだけで、
> それは media type 説を消すのに十分であり、それ以上ではない。

**そしてこれは我々の側の問題だった。** ただし**フォルダの話ではない** — CSIP32 が
`digiprovMD` を「PREMIS 1 件ごとに 1 つ」の枠と定めているのに、**PREMIS でない DER を
そこに宣言していた**ことである。「証拠記録は保存メタデータだから」という当初の理由は
OAIS の分類を CSIP のディレクトリに載せたもので、**そのフォルダ自体は CSIPSTR6 の
SHOULD にすぎない** (§11)。RODA が `MDTYPE` を見ずにあの枠を全部 PREMIS として読むのは、
CSIP32 の「preservation 情報には PREMIS を使う」に対する**実装として妥当**である。
**同じ増分で BagIt については逆の判断をしている**
(使わない受け手のパーサ欠陥に形式を合わせない) が、あちらは我々の形が正しく、
こちらは我々の形が間違っていた。→ **`metadata/other` へ移した** (§11)。

> **測っていないこと**: 使った DER は**スタブのバイト列**である。本物の RFC 3161 ベースの
> ERS でも同じかは確かめていない — ただし原因が「PREMIS として読めないものを
> `digiprovMD` に宣言した」ことである以上、本物の DER でも同じになる公算が高い。
> **「高い」は測定ではない。**
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
同じ日に Archivematica 1.18.0 では AIP まで行った (§12)。

#### 3. 受領証と分かるリソースは無い。語彙は 2 つの enum

`/api/v2/**` の top-level コントローラを数えた — **26 本** (ほかに `Exportable` と
`RequestHandler` の 2 クラスが同じパッケージに在る)。**その中に受領証を返す専用の
リソースは無い。**

> **「RODA は受領証を返さない」とまでは言えない** (外部レビュー指摘 2026-08-27)。
> 確かめたのは「受領証としてそれと分かるリソースが 26 本の中に無い」ことで、
> job report や投入時の応答が受領証の役を果たせないことは示していない。
接続層が組み立てるなら、材料は `JobReportController` と `AIPController` の 2 つになる。

**2 つは同じオブジェクトに載っている** — `org.roda.core.data.v2.jobs.Report`
(`JobReportController` が endpoint。`JobReport` という型は無い)。

| 出所 | 値 |
|---|---|
| `Report.pluginState` | `SUCCESS` / `PARTIAL_SUCCESS` / `FAILURE` / `RUNNING` / `SKIPPED` |
| `Report.outcomeObjectState` (`AIPState`) | `CREATED` / `INGEST_PROCESSING` / `UNDER_APPRAISAL` / `ACTIVE` / `DELETED` / `DESTROYED` / `DESTROY_PROCESSING` / `RESTORE_PROCESSING` |

`CustodyReceipt.reportsSuccess()` が受ける語は
`PASSED / PASS / VALID / SUCCESS / ACCEPTED / OK`。突き合わせると:

- **`pluginState` を入れるなら合っている。** `SUCCESS` は通り、`PARTIAL_SUCCESS` は
  通らない (§1.4 が「部分受入は成功として扱わない」と決めているのと一致)。
  `FAILURE` / `SKIPPED` / `RUNNING` も通らない。
- **`outcomeObjectState` を入れると壊れる。** 受入が完了した状態である **`ACTIVE` は
  この語彙に無い**ので、正常に受け入れられた AIP が「成功ではない」と読まれる。
  **2 つが同じ応答本文に入っている**ので、これは接続層が実際に選ぶ分岐である。

#### 3.1 `sipDigest` は応答フィールドには無い。取りに行く口は在る

**受領証の最低条件が満たせない。** `CustodyReceipt` は `sipDigest` — 受領証が
**こちらの package を名指していること** — が無いと構築できない (§2)。ところが:

- RODA の `TransferredResource` は `uuid` / `id` / `fullPath` / `relativePath` /
  `size` / `creationDate` / `name` で、**checksum のフィールドが無い**
- `Report` が投入物に紐づくのは `sourceObjectId` / `sourceObjectOriginalName` —
  **名前であって内容ではない**

つまり **JSON の応答フィールドだけを見て組み立てると、`sipDigest` はこちら側の記録から
埋めるしかない**。すると `refusalReasonFor` は**自分の値を自分と比べる**ことになり、
§2 が「AIP checksum だけの受領証は何も証明しない」と言って避けたのと同じ形に戻る。

> **ただし「この受け手からは作れない」は誤りだった** (外部レビュー指摘 2026-08-27)。
> RODA は**先方が保持しているバイト列を返す口を持っている**:
>
> ```
> GET /api/v2/transfers/{uuid}/download      TransferredResourceController
> GET /api/v2/aips/{id}/download/submission  AIPController (downloadAipSubmission)
> ```
>
> **接続層はこれを取って自分でハッシュできる。** そうすれば `sipDigest` は
> 「先方が持っているもの」の digest になり、照合は意味を持つ。
>
> **実測した** (2026-08-27、§16): その bytes は我々の送ったものと byte-identical で、
> transferred resource は取込完了後も残っていた (同一ジョブ内での観測。**長期に残るかは
> 依然として未測定**)。
> **設計としてはこちらを採るべき**で、応答フィールドだけで組み立てるのは
> §2 が避けた形に戻る、というのが正しい書き方である。

**署名は無い。** 上記のどれにも署名は付かないので、
RODA から組み立てた受領証は `signatureVerified = false` のままになる
(その扱いは §9 と `limits()` が既に持っている)。

> **語彙は「実機で見た」ところまで固定できた。** 組み立ての経路は §14 で書いた。
> **まだ無いのは送る口**で、実機で組み立てて検証したこともない。
> `reportsSuccess()` を RODA に対して「確かめた」と言えるのは
> `pluginState` を入れる場合だけである。

### この試験が確かめたこと / 確かめていないこと

**確かめた**: NemakiWare の E-ARK SIP は RODA 6.3.0 の `EARKSIP2ToAIPPlugin` が
AIP object にする — 本文・`dc.xml`・我々の JSON 2 本が入る。同じ package を
`EARKSIPToAIPPlugin` (E-ARK SIP 1.x) に投げると FAILURE になる。
manifest 1 本の bag も AIP object になり、**2 本だと rollback する**。

**追試で確かめた** (上): `ers.der` は `metadata/other` なら取り込まれ、AIP に残る
(`metadata/descriptive/` へ移されて)。`metadata/preservation` に置くと package ごと落ちる。
**ただし投げたのはスタブの DER である** — 本物の RFC 3161 ベース ERS では測っていない。
**現行の出荷形 (manifest 2 本の bag) は RODA では rollback する。**
RODA に受領証と分かるリソースは無く、語彙は同じ `Report` の `pluginState` と `outcomeObjectState` の 2 つ。

**確かめていない**: 他版の RODA、RODA 側の受入承認まで含む ingest workflow、
受領証を組み立てて検証する経路そのもの、本物の RFC 3161 ベース ERS での再現。
**Archivematica は §12。**
**AIP object ができたことは「先方が保持し続ける」ことでも「AIP が正しい」ことでもない。**
RODA の AIP は `INGEST_PROCESSING` のままで、**受入が承認された状態ではない**。
**我々の PREMIS 文書は RODA の AIP の PREMIS metadata に無い。**

> なお `EXPORT_LIMITS` は最初から「**NOT a statement that any particular archive
> will accept it**」と書いていた。この但し書きは、**受け入れられた今も**必要である
> — 通ったのは RODA 6.3.0 の 1 プラグインと AM 1.18.0 の automated ingest であって、
> 「どの archive でも通る」ではない。

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
**これは OAIS の分類を CSIP のディレクトリに載せた読み違いである** — が、
**間違っていたのはフォルダではなく、そのフォルダを選ぶために呼んだ API のほうだった。**
`sip.addPreservationMetadata(...)` は METS の `<amdSec><digiprovMD>` に宣言を書き、
フォルダ名はその副作用である。

**ディレクトリの話ではなかった** (外部レビュー指摘 2026-08-27)。CSIP 2.2.0 の条文は
commons-ip2 の `ConstantsCSIPspec` に原文で入っており、こちらのリポジトリの依存から
そのまま読める。関係するのは 3 本:

| 要件 | 水準 | 本文 (原文) |
|---|---|---|
| **CSIPSTR6** | SHOULD | "If preservation metadata are available, they SHOULD be included in sub-folder **preservation**." |
| **CSIPSTR8** | MAY | "If any other metadata are available, they MAY be included in separate sub-folders, **for example** an additional folder named other." |
| **CSIP32** | **SHOULD** (`0..n`) | "For recording information about preservation **the standard PREMIS is used. It is mandatory to include one `<digiprovMD>` element for each piece of PREMIS metadata.**" |

> **`CSIP32` も SHOULD である。** ここを `—` のままにしていた版があった
> (外部レビュー指摘 2026-08-27)。**CSIPSTR6 と同じ水準**なので、
> 「フォルダ規則は SHOULD だから拘束しない、CSIP32 が拘束する」という論の立て方は
> **成り立たない**。水準は同じで、違うのは**何について言っているか**である。

読み取れることは 2 つ:

- **どちらの置き場も CSIP 違反ではない。** CSIPSTR6 は SHOULD、CSIPSTR8 は `other` を
  **for example** としか書いていない。「`metadata/other` は CSIP が定めた catch-all」も
  **言い過ぎ**だった。同梱の validator も `validateCSIPSTR6` でフォルダの存在しか見ない。
- **効くのは CSIP32 である。** 「preservation 情報には PREMIS を使う。**PREMIS 1 件ごとに
  `<digiprovMD>` を 1 つ含めることが必須**」。`addPreservationMetadata` はその枠に
  宣言を書いていた。**ASN.1 の DER をそこに宣言していたことが defect** であって、
  ディレクトリ名ではない。

  > **CSIP32 は SHOULD である** (`LEVEL = SHOULD` / `CARDINALITY = 0..n` — 同じ
  > `ConstantsCSIPspec` から確認)。しかも書いてあるのは「PREMIS ごとに `digiprovMD` を
  > 1 つ」という**片方向**で、「`digiprovMD` に PREMIS 以外を置くな」という逆向きでは
  > ない (外部レビュー指摘 2026-08-27)。
  >
  > だから「**CSIP32 に違反していた**」とは書かない。書けるのは
  > **「CSIP32 の趣旨から外れていた」**まで。そして RODA があの枠を全部 PREMIS として
  > 読み、読めなければ全体を失敗させるのも、**CSIP32 が義務づけている挙動ではない** —
  > 趣旨と整合する実装判断である。
  >
  > 我々の側の defect は「PREMIS のための枠に PREMIS でないものを載せた」で足りる。

**では、なぜ `metadata/other` なのか** — 「禁じられていないから」だけでは弱い。
commons-ip2 は **section とフォルダを一体で決める**ので、`digiprovMD` から逃げるには
`addDescriptiveMetadata` / `addOtherMetadata` / `addTechnical|Source|RightsMetadata` の
どれかを選ぶことになり、それぞれがフォルダも連れてくる。**`other` は、そのうち
「証拠記録について嘘にならない」唯一の分類名**である — 証拠記録は記述メタデータでも
技術メタデータでも権利記述でもない。(RODA が結局 `descriptive/` へ移すのは受け手の判断で、
こちらが `descriptive` と**宣言する**こととは別である。)

> **だから「我々の形が間違っていた」は成り立つ。** ただし理由は
> 「CSIP がそのフォルダを PREMIS 専用と定めているから」ではなく、
> **「CSIP32 が `digiprovMD` を PREMIS の枠と定めているのに、PREMIS でないものを
> そこに宣言していたから」**である。前者は確かめずに書いていた読みで、
> **後者は依存ライブラリの中の原文で確かめられる。**
>
> **RODA の実測は根拠ではなく、気づいた経緯である。** 受け手が緩ければ気づかなかった。

> **同じ増分で BagIt については逆の判断をしている** (§6): 使わない受け手のパーサ欠陥に
> 出荷形を合わせない。**矛盾ではなく、CSIP32 を踏まえると対比はむしろ鮮明になる**:
>
> | | 我々の側 | 受け手の側 | 代替の代償 |
> |---|---|---|---|
> | bag | RFC 8493 §2.1.3 が明示的に許す形 | `BagitSIP.parse` が manifest ごとに payload を足す (**受け手の欠陥**) | manifest 1 本 = **SHA-256 が検証器の照合対象でなくなる** |
> | ERS | **CSIP32 (SHOULD) の PREMIS の枠に非 PREMIS を宣言** | それを PREMIS として読む (**CSIP32 の趣旨と整合する実装判断**) | `addOtherMetadata` へ = **package の検証上は無い**。ただし AIP では `descriptive/` へ移されるので、**「保存証跡の場所に在る」という見え方は失う** |
>
> あちらは我々が規格どおりで受け手が欠陥、こちらは我々が CSIP32 の趣旨から外れていて
> 受け手の挙動はそれと整合する。**向きが逆だから、判断も逆になる。**
>
> **どちらも「規格違反 / 準拠」の話ではない** (外部レビュー指摘 2026-08-27)。CSIP32 の
> 水準は **SHOULD** (cardinality 0..n) で、「`digiprovMD` に PREMIS 以外を置くな」とも
> 「受け手は全体を失敗させよ」とも書いていない。**RODA を「仕様どおり」とは呼べない** —
> 呼べるのは「CSIP32 の趣旨に沿った実装判断」までである。

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
「`digiprovMD` の中身を PREMIS として読む」という診断の対照になっている。

---

## 12. Archivematica 1.18.0 受入試験 (2026-08-27)

**版**: Archivematica 1.18.0 / Storage Service 0.24.0。公開イメージ、
`platform: linux/amd64` を aarch64 ホストで QEMU エミュレーション。
**測っているのは AM 1.18.0 の挙動であって、arm64 ネイティブではない。**
processing config は **`automated`** (`auto_approve: true`)。
スタックは `docker/docker-compose-archivematica.yml`、プロジェクト名 `-p am`。

投入は NemakiWare の HTTP クライアントではない。出荷エンドポイントから bag / SIP を
取り、Dashboard `POST /api/v2beta/package` に置いた。**この試験の時点で接続層は無かった**
(受領証の組み立ては同日あとから実装した — §14。送る口は今も無い)。

対象 object: `bedroom` / `26b9bd3e3be50260cc7580be38113bbc`
(`am-trial-2026-08-27.txt`)。**ERS は入っていない** (この object に記録が無い)。
SIP は `X-Nemaki-Csip-Validated: true`。bag は payload manifest **2 本**
(`manifest-sha256.txt` と `manifest-sha512.txt`)。

### 起動で踏んだこと (compose だけでは足りない)

公式 `hack/` の Makefile が bootstrap するので、compose には無い:

1. MySQL に DB `MCP` / `SS` を作り、`archivematica`@`%` へ `GRANT ALL`
   — これが無いと mcp-server / storage-service は
   `Access denied for user 'archivematica'@'%' to database 'MCP'` で再起動する
2. 両方 `migrate`
3. SS: `create_user --username=test --password=test --email=test@example.com --api-key=test --superuser`
4. dashboard: `manage.py install` (`--ss-url=http://archivematica-storage-service:8000`、
   `--ss-user=test --ss-api-key=test`、`--site-url=http://archivematica-dashboard:8000`)

clamav の tag `1.4.3-57` は Docker Hub から消えていて `1.4.6` に寄せた (compose コメント)。

dashboard は installer 後 `/administration/accounts/login/` へ。pipeline UUID
`cbdc4cb3-e25d-4997-96b1-6709ea6869d8`。Transfer Source は `/home`
(`752793d2-6897-428a-a4fd-7d8cf22558f8`)。この `test` / `test` はローカル受入試験用。

**202 は path の存在を保証しない** (ロードマップ §9-4 の 1)。存在しないディレクトリを
指して 202 が返り、status は `Unable to determine the status of the unit` になった。

### 結果

投入口はどれも `POST /api/v2beta/package`、path は
`base64("<TS uuid>:<絶対パス>")`、`processing_config: automated`。
zipped bag の転送名は API の `name` ではなく **zip のファイル名** になった
(§9-4 の 7)。

| 入力 | type | transfer `status` | AIP (SS) |
|---|---|---|---|
| 出荷形 bag (manifest 2 本) | `zipped bag` | `COMPLETE`、`sip_uuid` あり | **`UPLOADED`** (`32eaa64b-…`、7z 44918 bytes) |
| 同じ E-ARK SIP zip | `zipfile` | `COMPLETE`、`sip_uuid` あり | **`UPLOADED`** (`4f38c5d4-…`) |
| 同じ SIP zip | `standard` | **`FAILED`** (`Failed compliance.`) | — |
| 同じ SIP を展開したディレクトリ | `standard` | `COMPLETE`、`sip_uuid` あり | **`UPLOADED`** (`48f400a3-…`) |

bag の `Verify bag, and restructure for compliance` は **COMPLETE / exit 0**。
2 本でも AM の BagIt 検証器は通った。zipfile / standard 側にこの job は無い。

`standard` に zip を渡した失敗は **E-ARK を拒否したのではない。** その type は
ディレクトリを期待し、zip ファイルに対して `Remove hidden files and directories` が
exit 1 になった。展開すれば通る。対照を置かないと「E-ARK が standard で落ちる」に
読める。

**BagIt は必須ではない。** 同じ SIP が `zipfile` でも AIP になる。
`zipped bag` を選ぶ理由は「他に道が無い」ではなく、**`Verify bag` が payload
manifest (SHA-256 を含む) を照合する**ことである。

どれも **Archivematica の AIP** である。E-ARK AIP にはならない。
`automated` は packages を展開するので、bag 経路でも payload の SIP zip は
1 ファイルのまま残らず、AIP の `objects/` に SIP のツリーが入る
(METS.xml / `representations/rep1/data/am-trial-2026-08-27.txt` ほか)。
「bag として読む」と「そのあと processing config が zip を展開する」は別である。

### 語彙 (`reportsSuccess()` との突き合わせ)

Dashboard `GET /api/transfer/status/{uuid}/` と `/api/ingest/status/{uuid}/` が返す
`status` は、ソースどおり **`FAILED` / `REJECTED` / `USER_INPUT` / `COMPLETE` /
`PROCESSING`**。今回見たのは `COMPLETE` と `FAILED`。

SS `GET /api/v2/file/{uuid}/` の `status` は **`UPLOADED`**。フィールドに checksum は無い
(keys: uuid / status / package_type / size / stored_date / path ほか)。

`GET /api/v2/file/{uuid}/check_fixity/` は `success: true` (JSON boolean)、
`timestamp: null`。`GET .../contents/` は `files: []` だった
(per-file checksum を常に返すかは、空配列なので「返す」とは言えない)。

`CustodyReceipt.reportsSuccess()` が受ける語は
`PASSED / PASS / VALID / SUCCESS / ACCEPTED / OK`。突き合わせると:

- **生の `COMPLETE` も `UPLOADED` も `FAILED` も通らない。** 正常に AIP まで行った
  transfer が、写像なしでは「成功ではない」
- `check_fixity` の `true` を文字列にしても `TRUE` であり、語彙に無い
- ジョブ名の `COMPLETE` も同じ

RODA の `outcomeObjectState=ACTIVE` と同じ形の罠である。違うのは語だけ。
**接続層は写像する** — 語彙を増やすのではなく、写像後を `verificationOutcome` に、
生の語を `reportedOutcome` に置く。**§13.1 で閉じた分岐**である。
写像しないまま入れると、genuine な受領が拒否される
(`reportsSuccess` が間違う方向として選んでいる側)。

`sipDigest`: 応答 JSON には無い。AIP の checksum は
`GET /api/v2/file/{uuid}/pointer_file/` の PREMIS `messageDigest`
(今回 `sha256` / `11214191bd63382ab86d2a6ed06ca0585e4730a87c2ec5b28a4e4fa5a25c1a73`)
で、**これは AIP (7z) のものであって送った bag/SIP のものではない。**
送った bag が積んでいた **SIP の SHA-256** は、AIP 内
`data/objects/metadata/transfers/…/manifest-sha256.txt` に残った
(**bag 自身の digest ではない** — manifest は payload を記述し、自分は記述しない。§13.2)
(**中間セグメントが uuid か名前かは確かめていない** — §14)。
先方の AIP bytes は `GET /api/v2/file/{uuid}/download/` で取れる。
**どれを採るかは §13.2 で閉じた** — AM は AIP 内の manifest 行、RODA は download した
バイト列である。pointer の digest は**採らない** (AIP の 7z のものだから)。
実装は `jp.aegif.nemaki.custody.connector`。

署名は無い。`signatureVerified = false` のままになる。

### この試験が確かめたこと / 確かめていないこと

**確かめた**: AM 1.18.0 は出荷形 (manifest 2 本) の zipped bag を Verify bag し、
automated processing の末に AIP を `UPLOADED` する。同じ E-ARK SIP は `zipfile` でも
AIP になる。`standard` は zip では落ち、展開ディレクトリでは AIP になる。
返る `status` の語は `reportsSuccess()` と重ならない。

**確かめていない**: NemakiWare からの送信、`default` processing config、
manifest 1 本の bag、`unzipped bag`、他版の AM、本物の ERS、
full な本番相当の AIP、arm64 ネイティブ。
**受領証の組み立ては §14 で実装し、§17 で実機に当てた** — 回収した値は送った物と
一致した。**AIP が `UPLOADED` なことは「先方が
保持し続ける」ことでも「E-ARK として読んだ」ことでもない。**

---

## 13. 接続層を書く前に決める 2 点 (2026-08-27)

**受け手を 2 つ測った結果、接続層が写像を持たないと両方で genuine な受領を拒否する。**
送る口 (HTTP クライアント) より先にここを決めておかないと、RODA と AM で別々の分岐を
書くことになる。

### 13.1 `verificationOutcome` に何を入れるか

測った語彙:

| 受け手 | 出所 | 成功を表す語 |
|---|---|---|
| RODA 6.3.0 | `Report.pluginState` | `SUCCESS` |
| RODA 6.3.0 | `Report.outcomeObjectState` (`AIPState`) | `ACTIVE` |
| AM 1.18.0 | transfer / SIP `status` | `COMPLETE` |
| AM 1.18.0 | SS package `status` | `UPLOADED` |

`reportsSuccess()` が受けるのは `PASSED / PASS / VALID / SUCCESS / ACCEPTED / OK`。
**通るのは RODA の `pluginState` だけ**である。

**決めること**: 語彙を増やすか、接続層で写像するか。

**写像を採る。** 理由は 3 つ:

- **語彙を増やすと fail-closed の向きが崩れる。** `ACTIVE` や `UPLOADED` を足すと、
  それらの語を別の意味で使う 3 つ目の受け手に対して**通してしまう**側へ倒れる。
  今の `reportsSuccess()` は「知らない語は成功ではない」で、外れると正当な受領証を
  拒否する — §4.1 が意図して選んだ向きである。
- **どのフィールドを採るかは受け手ごとの判断**であって、語の綴りの問題ではない。
  RODA では `pluginState` を採り `outcomeObjectState` を採らない、という選択が
  既に要る (§10 追試 3)。写像はその選択を書く場所になる。
- **写像なら「何を何に寄せたか」が記録に残る。** 語彙を増やすと、受領証を読んだ人には
  先方が `SUCCESS` と言ったのか `UPLOADED` と言ったのか区別できない。

#### どちらの欄に何を入れるか — 向きを間違えると写像した意味が無い

**`verificationOutcome` には写像後の語を入れる。生の語は `reportedOutcome` に置く。**

逆にすると成立しない。`CustodyTransfer.verifyReceipt` は
**`candidate.reportsSuccess()` を直接呼ぶ**ので、そこに AM の `COMPLETE` が入っていれば
**写像を採ったはずなのに genuine な受領が止まる** (外部レビュー指摘 2026-08-27)。
「両方を持つ」までは前版で書けていたが、**どちらが `verificationOutcome` かは
決まっていなかった** — 今のコードと両立するのは写像後を入れる側だけである。

`CustodyReceipt` に `reportedOutcome` を足した (null = 写像していない)。

**そして署名は生の語を覆う。** 先方は**自分が出した語に**署名しており、こちらの語彙を
知らない。`ReceiptSignatureVerifier.canonicalForm` は `asReported()` を使う —
写像後の語に署名を求めると、**写像した受領証が全部検証に落ちる**。
これは「生の語を状態機械が読む欄に入れる」のと同じ誤りが 1 層ずれただけである。

> **代償を書いておく: 写像後の語は先方の署名で覆われない。** 読み手が写像を検めたければ、
> **署名された生の語が隣に在る**ので再導出できる。
> 本製品側の台帳 digest (`CustodyLedgerRecorder.receiptDigest`) は**両方**に commit する —
> 写像後だけに commit すると、後から写像を書き換えても entry が変わらない。

錠: `theSignatureCoversWhatTheReceiverSaid` (canonicalForm に生の語が在り写像後が無い) と
`theDigestIsDomainSeparated` (digest の入力表)。負のコントロール 2 本発火済み。

### 13.2 `sipDigest` に何を入れるか — pointer の AIP digest ではない

**両方の受け手で、いちばん近くに在る digest が間違った digest である。**

| 受け手 | すぐ手に入る digest | それは何の digest か |
|---|---|---|
| RODA 6.3.0 | 応答フィールドに**無い** | — |
| AM 1.18.0 | pointer file の PREMIS `messageDigest` | **AIP の 7z** |

`sipDigest` は「**こちらが送った package**」を指していなければ意味が無い (§2)。
AIP の digest は先方が作った成果物のもので、こちらは一度も見ていない —
まさに §2 が「どんな値でも条件を満たす」と言って退けた形である。

**採るべきもの**:

- **RODA**: `GET /api/v2/transfers/{uuid}/download` (または AIP の
  `download/submission`) で**先方が持っているバイト列**を取り、こちらでハッシュする
- **AM**: 送った bag の `manifest-sha256.txt` が AIP 内
  `data/objects/metadata/transfers/…/` に残る (**中間セグメントは未確認** — §14)。
  取り出すのは `GET /api/v2/file/{uuid}/extract_file/`。
  **`download/` で AIP そのものを取ってハッシュしないこと** — それは 7z の digest で、
  pointer の PREMIS `messageDigest` を入れるのと同じ誤りである

> **両方とも実測した** (2026-08-27、§16 RODA / §17 AM)。RODA は投入した zip と byte 単位で
> 同一の bytes を返し、AM は同梱した manifest の行をそのまま保っていた。
> **AM 側の経路には囮が在った** — AIP ルート直下の `manifest-sha256.txt` は
> AM 自身のもので、正当な BagIt manifest として parse できる (§17)。

**一段の中身は受け手で違う** (外部レビュー指摘 2026-08-27):

- **RODA** — 取るのは**提出したバイト列そのもの**で、こちらでハッシュする。
  受入条件は「**そのバイト列が送ったものと一致するか**」。
- **AM** — `automated` は payload の zip を展開して消すので、**提出物は残っていない**。
  残るのは bag の `manifest-sha256.txt` の**コピー**である。したがって取るのは
  バイト列ではなく**manifest の行**で、受入条件は
  「**回収した SHA-256 が、送った SIP のそれと一致するか**」になる。

  > **比べる相手は SIP であって bag ではない。** manifest は自分が覆う payload を記述し、
  > 自分自身は記述しない。**transfer が覚える digest を bag の SHA-256 にすると、
  > RODA では通り AM だけが全部落ちる** — RODA は提出物そのものを返すので、bag を
  > 送っていれば bag と一致してしまい、間違いに気づけない。
  > 契約は `CustodyReceiptAssembler.Inputs.expectedSipDigest` の javadoc にも書いた
  > (遠くの節にだけ在ると、次の一段は引数を見て済ませる)。

  > **AIP 自体をハッシュする読みに戻らないこと。** AIP は 7z で、こちらが送った物では
  > ない。`GET /api/v2/file/{uuid}/download/` で AIP を取れるが、**その digest を
  > `sipDigest` に入れると pointer の PREMIS `messageDigest` を入れるのと同じ誤り**である。
  > 取りに行くのは AIP の中の manifest 行であって、AIP そのものではない
  > (`extract_file`)。

> **そして `zipfile` 経路にはそのファイルが無い。** bag を選ぶ積極的な理由の続きである —
> `Verify bag` が照合するだけでなく、**照合された値が AIP の中に残る**。

### 13.3 この 2 つが同じ形をしている

どちらも「**受け手がすぐ返してくるものは、こちらが必要としているものではない**」で
ある。語彙は先方の workflow の状態で、digest は先方の成果物のものである。
受領証が establish しようとしているのは**こちらの package について先方が何をしたか**
なので、両方とも一段取りに行く必要がある。

**接続層はその一段を書く場所**であり、送る口はそのあとで足りる。

---

## 14. 接続層 — 受領証の組み立て (2026-08-27 実装)

`jp.aegif.nemaki.custody.connector` に 3 つ。**送る口は入っていない** — 識別子は
引数で来る。この段が閉じるのは「受け手が持っている物から受領証を組む」までである。

> **呼び出し元はまだ無い。** bean にもしていないし、これを呼ぶ endpoint も無い。
> 識別子 (transfer / AIP の uuid) を得るには投入と待ちが要り、それが次の一段だからである。
> **`@Component` を付けないこと。** P3-1 で `EarkSipExporter` が scan されない
> パッケージに `@Component` のまま置かれ「未使用より悪い。配線済みに読める」と
> 指摘された前例があるが、**ここは事情が逆である** (外部レビュー指摘):
> `serviceContext.xml` が `jp.aegif.nemaki.custody` を scan しており sub-package も
> 対象なので、**付ければ本当に bean になる**。呼び出し元が無いまま bean にすると、
> 今度は「配線済みに見える」ではなく「配線されている」になる。
> **`ReceivingSystem` だけは、もう REST の実行経路に居る** — `CustodyReceipt`
> `.mappingRefusalReason()` が `isDerivableMapping` を呼ぶので、`verifyReceipt` と
> `restore()` を通る**全受領証**が触れる。呼び出し元が無いのは残り 2 つ
> (`SubmittedDigestRecovery` と `CustodyReceiptAssembler`) である。

| | 責務 | I/O |
|---|---|---|
| `ReceivingSystem` | どの欄を読むか / 語をどう写像するか (受け手ごと) | **無し** (純関数) |
| `SubmittedDigestRecovery` | 受け手が持っている物から digest を回収する | GET (429/503 なら `sendWithRetry` が最大 4 回) |
| `CustodyReceiptAssembler` | 両者を合わせて `CustodyReceipt` を返す、または**組まない** | — |

### 読む欄は受け手ごとの知識である (語の綴りの問題ではない)

| 受け手 | 採る | 採らない | 理由 |
|---|---|---|---|
| RODA | `Report.pluginState` | `Report.outcomeObjectState` | **同じ応答本文に載っている**。`ACTIVE` は受入済み AIP そのものに見えるので、採ってしまいやすい |
| AM | transfer / SIP の `status` | SS の `UPLOADED` | `UPLOADED` は AIP が今どこに在るかであって、受け入れられたかではない |

写像は 1 つだけ: AM の `COMPLETE` → `SUCCESS` (生の語は `reportedOutcome` に残る)。
RODA の `SUCCESS` は写像不要で `reportedOutcome = null`。
**それ以外は 2 つに分かれる**:

- `PARTIAL_SUCCESS` / `ACTIVE` / `FAILED` / `USER_INPUT` のように**こちらの語彙に無い**語は
  **素通し**して、`reportsSuccess()` が落とす形のまま受領証に載る。
- **その受け手が使わないのに、こちらの語彙には在る**語 (AM の `SUCCESS`、RODA の `OK`) は
  `UNRECOGNISED_BY_CONNECTOR` に置換し、生の語を `reportedOutcome` に残す。
  素通しすると**偶然で `reportsSuccess()` を通ってしまう** — §13.1 が「語彙を増やさない」
  理由に挙げた「同じ語が別の意味を持つ」が、写像の側から入り込む形である。**受領証は組む** (何かが届いた事実は
残る) が、`verifyReceipt` が先方の語をそのまま引用して拒否する。

### digest は「取りに行く」もので、返ってくるものではない

| 受け手 | 取るもの | 取らないもの |
|---|---|---|
| RODA | `/api/v2/transfers/{uuid}/download` の**バイト列**をこちらでハッシュ | 応答フィールド (checksum が無い) |
| AM | `/api/v2/file/{uuid}/extract_file/` で AIP 内の `manifest-sha256.txt` の**行** | pointer の PREMIS `messageDigest`、AIP (7z) 自体のハッシュ |

> **AIP 内のパスは定数にしていない。** 観察は 1 回だけで、しかも
> **§12 と回収側の javadoc で表記が食い違っていた** (`{transfer-uuid}` と
> `<transfer-name>` — 外部レビュー指摘)。実測で見えていたのは
> `data/objects/metadata/transfers/…/manifest-sha256.txt` までで、
> **中間のセグメントが uuid か名前かは確かめていない**。
> どちらかを定数にすると、次の一段がそれを信じて 404 になる。
> だから引数のままにしてあり、**送る口を書くときに実物で確かめること**。

**非対称なのは受け手の都合である。** AM の `automated` は payload の zip を展開して
消すので**提出物が残っていない**。残るのはこちらが送った manifest のコピーである。
**したがってこの回収は `zipped bag` 経路でしか成立しない** — `zipfile` で送った SIP は
AIP になるが manifest を残さないので、**受領証は組めない**。
「zipfile でも AIP になる」を「zipfile でも受領証が組める」にしないこと。

> **回収される値が何の digest かは、受け手で違う** (外部レビュー指摘 2026-08-27)。
>
> | 受け手 | 回収値は何の digest か |
> |---|---|
> | RODA | **提出した物そのもの**。bag を送ったなら bag、SIP を送ったなら SIP |
> | AM | **bag の payload = SIP zip** の digest。manifest の行だからである |
>
> **AM だけずれる。** bag 全体の SHA-256 は manifest には書かれていない
> (manifest は payload を記述するもので、自分自身は記述しない)。
>
> **したがって transfer が覚える `sipDigest` は SIP のものでなければならない。**
> 送る口を書くときに `open()` へ bag の SHA-256 を入れると、
> **genuine な AM 受領が組めなくなる** — RODA は対称なので気づかない。
> 現行の出荷エンドポイントは `digestOf(exported.sip())` を bag の
> `External-Description` に入れており、これは SIP の digest である。同じ値を使うこと。

### 一致しなければ組まない

回収した値が**この transfer が送った package の digest と一致しない**なら、受領証は
返らない。誘惑は「自分の記録から `sipDigest` を埋める」ことで、それをやると
**照合が自分の値と自分の比較になり、絶対に落ちない検査**になる。§2 が退けたのは
まさにその形で、接続層が在る状態で §2 を真に保つのがこの拒否である。

### 実機でどこまで測ったか

**RODA については測った** (2026-08-27、§16)。回収した値が送った物と一致し、
受領証が組み上がり、状態機械が受理するところまで一周した。
**そしてその一周でしか出ない欠陥が 1 件出た** (`aipChecksum` 必須) —
単体テストは両側とも緑のまま噛み合っていなかった。

**Archivematica についても測った** (2026-08-27、§17)。非対称な経路 —
同梱した manifest の 1 行 — でも回収値は送った物と一致し、
**AIP ルートに在る AM 自身の manifest (囮) は拒否される**ことも実機で撃った。
**在る錠は全部 fixture で閉じている** — 実機が止まっていても落ちる。
「全部の保護に錠が在る」とは書かない: 2 巡のレビューで、書いたのに錠が無い箇所が
毎回見つかっている (`cg29` / `cg30` / `cg35`〜`cg38`)。

> **そして fixture では見えない障害が 2 つ在る** (外部レビュー指摘 2026-08-27)。
> どちらも「口が在るのに取れない」形なので、次の一段が最初に踏む。
>
> 1. **`AdapterHttpClient` は既定で loopback / private アドレスを拒否する。**
>    測った RODA (`localhost:18080`) と AM (`localhost:62081`) は**両方ともそれに当たる**。
>    `-Dnemaki.ingest.allowLocalhost=true` は ingest 試験用に在るが、
>    **接続層の運用で何を使うかは決めていない** (常設の受け手なら公開ホスト名になる、
>    というのは推測であって測定ではない)。
> 2. **この回収は redirect を追わない。** 既定で使う `AdapterHttpClient.shared()` が
>    `Redirect.NEVER` だからで、**`AdapterHttpClient` 全体がそうなのではない** —
>    `sendWithRedirectValidation` は検証しながら追う口を別に持っている (外部レビュー指摘)。
>    `download` が 302 を返す実装なら、口が在っても回収は `unavailable` になる。
>    **どちらの受け手でも未確認。**
>
> SSRF の規則を緩める話ではない。**どちらも実機で当ててから決めること。**

### 負のコントロール (compile-errors 0、落ちたテスト名まで確認)

| 壊した箇所 | 落ちたテスト |
|---|---|
| RODA の `ACTIVE` を `SUCCESS` に写像する | `rodaActiveIsNotSuccess` |
| 回収値と送った digest の比較を飛ばす | `rodaMismatchRefuses` / `archivematicaWrongManifestLineRefuses` |
| REST が `reportedOutcome` をまた捨てる | `theReportedWordIsNotDropped` |
| AM で `extract_file` を `pointer_file` にする | `archivematicaReadsTheShippedManifest` |
| manifest の行を payload 名で照合しない | `archivematicaReadsTheShippedManifest` / `archivematicaWithoutOurLineIsUnavailable` |
| `Authorization` を RODA 側 (`hashOf`) で送らない | `rodaFetchesTheSubmittedBytes` |
| `Authorization` を **AM 側 (`bodyOf`) だけ**送らない | `archivematicaReadsTheShippedManifest` |
| 受け手 2 つの回収分岐を入れ替える | `theRecoveryIsWiredPerReceiver` |
| その受け手が使わない成功語を素通しする | `aWordThisReceiverNeverUsesIsNotSuccess` |
| 写像の再導出をやめる | `aForgedMappingIsRefused` |

> **最後の 2 行は、最初に書いたときは発火しなかった。** コードだけ書いて錠を書いて
> いなかったので、`cg29` / `cg30` が緑のまま通った。**「実装した」と「守られている」は
> 別である**、を自分で踏んだ回である。

### 写像を偽造できた — 欄を分けた代償 (レビュー指摘、同日修正)

**`verificationOutcome=SUCCESS` と `reportedOutcome=FAILED` を持つ受領証は、
受領証の中では矛盾していない。** 署名は生の語 (`FAILED`) を覆うので**検証を通り**、
状態機械は写像後の語 (`SUCCESS`) を読むので**受け入れる**。§13.1 で欄を 2 つに
分けたことの、そのままの代償である。

**対処**: 写像が**再導出できること**を要求する。
`CustodyReceipt.mappingRefusalReason()` が、この製品が実際に行う写像の表と突き合わせる。

> **置き場所を間違えていた** (2 度目の指摘)。最初は
> `CustodyTransferService.verifyReceipt` に置き「全受領証が通る唯一の funnel」と書いたが、
> **状態を動かすのは型の `CustodyTransfer.verifyReceipt` であり、`restore()` は
> 「verifyReceipt と同じ検査」を読み戻しに再適用する**。新しい規則だけがそこから
> 外れていたので、**DB に直接書いた `SUCCESS` / `FAILED` は読み戻しを通った** —
> 行は、まさに偽造された対が置かれる場所である。
>
> 規則を `CustodyReceipt` 側に移し (`missingRequiredField` / `refusalReasonFor` と同じ形)、
> 型の `verifyReceipt` と `restore()` の両方が呼ぶようにした。

**空白も 1 つの穴だった。** `reportedOutcome = "  "` は「受け手が何か言った」と読めるのに、
署名も再導出も働きかける中身が無く、再導出は「写像なし」として通していた。
compact constructor で null に畳み、**「何も写像していない」の表現を 1 つにした**。

> **残っている緩さ、書いておく**: 対の照合は**この製品が知る全受け手**に対して行い、
> **この transfer が向かう受け手には束ねていない**。`COMPLETE → SUCCESS` は AM の写像
> なので、RODA 向けの transfer でも derivable になる。束ねるには
> `CustodyTransfer.receivingSystem` (運用者が打つ自由文字列) を enum に対応づける必要が
> あり、それは推測である。**推測より記録を選んだ** — 今はどちらの受け手も署名を返さない
> ので、偽造する側にこの経路を選ぶ理由が無い。署名が入ったら決め直すこと。

| 壊した箇所 | 落ちたテスト |
|---|---|
| `restore()` が再導出をやめる | `aForgedMappingInARowIsRefused` |
| **規則をサービスへ戻す** (最初にやった間違いそのもの) | `aForgedMappingIsRefusedByTheType` |
| 空白の生の語を第 2 の「無し」として残す | `aBlankReportedWordIsNull` |

> **2 行目は 3 度目で正しくなった。** 最初は規則をサービスに置いて「唯一の funnel」と
> 書き、次に型へ移したが、**錠はサービス経由のまま**だった
> (`CustodyTransferServiceTest.aForgedMappingIsRefused`) — サービスが型を呼ぶので、
> 規則がどちらの層に在っても緑になる。**置き場所を測っていたのは `restore` 側の 1 本
> だけ**だった (外部レビュー指摘)。型を直接叩く `CustodyTransferTest` の 1 本を足して、
> 表が言っていることと錠が一致した。

### 主張しない

先方が保持し続ける。E-ARK として理解した。署名を確かめた (**どちらの受け手も署名しない**
ので `signatureVerified = false` のまま)。**送信した** — 送る口は次の一段である。

---

## 15. 「スレッド安全性」を見に行ったら、別の欠陥だった (2026-08-27)

§7 の残件表に **「スレッド安全性 — 未。`state` / `receipt` / `history` は非同期化で、
`advance` は check-then-act」** と書いてあった。同期化しに行って、**前提のほうが
間違っていた**ことが分かった。

### 共有されていない

`CustodyTransferService.load()` は毎回 `store.find()` を呼び、`find()` は毎回 CouchDB の
row を `decode` して**新しい object** を作る。cache は無い。
**2 つのスレッドが 1 つの `CustodyTransfer` を触る経路が存在しない。**

だから `synchronized` を足しても、防いだことになるものが無い。
**「型が可変だから同期が要る」は、共有されているかを見ずに書いた結論だった。**

### 実際に壊れるのは row のほう

危険は 1 段下に在った。`save()` はこう書いてあった。

```java
Document existing = client.get(id);        // ← 書く直前に、現在の rev を引き直す
...
doc.put("_rev", existing.getRev());
result = client.update(doc);
```

**この `get` は必ず成功し、必ず最新の rev を返す** — 直前に別の要求が書いた rev も
含めて。つまり:

| | 要求 A | 要求 B |
|---|---|---|
| 1 | rev 1 で読む | rev 1 で読む |
| 2 | `SENT` へ進める | `SENT` へ進める |
| 3 | `save` → get が rev 1 → update → **rev 2** | |
| 4 | | `save` → get が **rev 2** → update → **rev 3・成功** |

**A の移動は消え、A も B も「成功した」と返っている。**
下にある `catch (isConflict)` は、自分の `get` と `update` の間の
マイクロ秒だけを守っていて、**要求と要求の間の競合には一度も効かない**。
「楽観ロックが在る」ように読めるコードが、実際には最後に書いた者が勝つだけだった。

**状態の消失は欄の消失とは違う。** この機械の主張は
「詰まっている状態そのものが診断である」(§1) なので、移動が 1 つ消えると、
**起きていないことが起きたことになるか、起きた時刻が別の時刻になる**。

### 直し方

`CustodyTransfer` に**読んだときの rev** を持たせ (package-private・`asMap()` にも
履歴にも出ない)、`save()` はそれに対して書く。負けた側は CouchDB が 409 を返し、
`save` は `false` を返し、`persist()` が既に持っている
「**書けなかった移動は起きなかった**」経路に落ちる。呼び出し元の変更は無い。

書込に成功したら新しい rev を object に戻す。これが無いと、1 要求のなかで 2 回動かす
経路 (`passCustody` は記録 → 移動) が 2 回目で必ず落ちる。

### 負のコントロール 3 本

| 壊した箇所 | 落ちたテスト |
|---|---|
| `save()` が rev を引き直す (**元のコードそのもの**) | `aMoveMadeAgainstAStaleReadIsRefused` / `aFreshObjectDoesNotOverwriteAStoredTransfer` |
| 書込成功時に rev を戻さない | `twoMovesInOneRequestAreBothWritten` / `aFreshObjectDoesNotOverwriteAStoredTransfer` |

`twoMovesInOneRequestAreBothWritten` は**締めすぎの検出**である。
「最初の 1 回以外は全部拒否する」実装は他の 2 本を通してしまい、
移動を 1 回しか記録できない store になる。

> 2 行目が 2 本落ちるのは、`aFreshObject...` が下拵えで同じ object を 2 回書くから。
> **表に「1 本」と書きかけて実測で 2 本だった** — 落ちたテスト名まで見ないと、
> 錠が何を押さえているかは分からない。

錠は live CouchDB ではなく偽 row に対して掛けてある。**2 つの writer を実際に
交錯させるのは timing test で、通った timing test は証拠にならない。**

### この修正が「新しく到達可能にした」もの (2026-08-27 レビュー指摘)

**この窓は元から在った** — `save()` が false を返す経路 (結果が ok でない、client が無い等)
はいつでも在ったからである。**新しく作ったのではなく、現実に踏むようになった**:
塞ぐ前は書込時に rev を引き直していたので競合ではまず失敗せず、塞いだ後は失敗する。
`passCustody` は**台帳へ先に書いてから** row を書くので、
**台帳に entry が在り、transfer はそれを反映していない**状態が日常的に起き得る。

これは想定内で、既に言葉になっている — `persist()` は負けた側に**手元の object を返さない**
(進めてしまった投機的状態を「現在」として見せないため) で、サービスは
「連鎖に、この transfer が反映していない entry が在る」と述べる。再試行も安全である:
`recordVerifiedReceipt` は digest 冪等で、状態が `RECEIPT_VERIFIED` でなくなっていれば拒否する。

**ただし、この窓が広がったことを §15 の初稿は書いていなかった。** 直したものの副作用を
書かないのは、直した側だけを書くのと同じ欠陥である。
(**そして訂正の初稿は「新しく到達可能にした」と書いていた** — これも過大で、
実際は元から在った窓が広がっただけである。2 巡目の指摘で直した。)**錠は無い** — 台帳とサービスを
またぐ経路で、ここに錠を足すのは次の一段 (送信) と同じ設計判断になるため、
**未として数える**。

### 主張しない

`CustodyTransfer` が thread-safe になった、とは言わない。**object は今も非同期化**である。
言えるのは**共有する経路が無いこと**と、**row の lost update は塞いだこと**まで。
**`passCustody` の台帳先行窓には錠が無い** (上記)。

---

## 16. 接続層を実機 RODA に当てた (2026-08-27)

§13.2 が **「これが接続層の最初の受入条件」** と書いて未実測にしていたもの:
**回収した digest が、送った物と一致するか**。RODA 6.3.0 を上げて測った。

### 一周

| | |
|---|---|
| SIP | `POST /core/api/v1/admin/eark/export`、bedroom / `26b9bd3e…`。35,498 bytes、`X-Nemaki-Csip-Validated: true`、SHA-256 `abb00e20…4ef5b` |
| 投入 | `POST /api/v2/transfers/create/resource` → 201、uuid `381cff38-…e954` |
| 取込 | `POST /api/v2/jobs`、`EARKSIP2ToAIPPlugin` → 201 |
| 結果 | JobReport: **`pluginState: SUCCESS`**、`outcomeObjectState: INGEST_PROCESSING`、AIP `72ade485-…c5d9` |

**製品クラスをそのまま実機に当てた** (`SubmittedDigestRecovery` /
`ReceivingSystem` / `CustodyReceiptAssembler` / `CustodyTransfer`)。

- `GET /api/v2/transfers/{uuid}/download` の bytes は **投入した zip と byte 単位で同一**、
  SHA-256 も一致。§10 追試 3.1 の前提が実機で成立した
- 読む欄は `pluginState`、写像は無し (`verificationOutcome=SUCCESS`,
  `reportedOutcome=null`) — §14 の「RODA はこちらの語を話す」が実機で確認できた
- 受領証は組み上がり、状態機械が **`RECEIPT_VERIFIED` を受理**した
- 負のコントロール: 別の digest を送ったことにした transfer では
  **組み立てを拒否**し、理由に両方の値が入る

### `POST /api/v2/jobs` の判別子は 2 つある

§10 に `@type: "SelectedItemsListRequest"` とだけ書いていたが、**それだけでは 400** で
`Invalid value for classNameToReturn null` になる。`CreateJobRequest` に
**`sourceObjectsClass`** という別の欄があり、そこに
`org.roda.core.data.v2.ip.TransferredResource` が要る。
`SelectedItemsListRequest` 側は `ids` しか持たない (バイトコードで確認)。

### 見つかった食い違い — 層どうしが噛み合っていなかった

**この一周でしか出ない欠陥が 1 件出た。**

組み上がった受領証を状態機械が拒否した。理由は
`the receipt does not carry 'aipChecksum'`。

- `missingRequiredField()` は **ロードマップの受領証項目一覧をそのまま**必須にしていた
- **RODA の取込が返したものに、自分の AIP の checksum は無かった** (§10 追試 3 で
  「応答フィールドには SIP の checksum が無い」と書いていたのは投入物の話で、
  AIP 側も同じだった)
- つまり **成功した RODA の取込から組んだ受領証は、必ず拒否される**

単体テストでは出ない。組み立て側のテストは `aipChecksum` を渡し、状態機械側の
テストは自前で受領証を作るので、**両方緑のまま噛み合っていなかった**。

**必須から外した。** 理由は「RODA が返さないから」ではなく、
**この欄を照合に使っている場所が 1 つも無いから**である。照合は `sipDigest` —
**こちら**の package を先方が持っているか — に対して行う。`aipChecksum` は
先方が自分の成果物について述べた値で、こちらは再計算できず、比較もしていない。
**受け手が出せない必須欄は保護ではなく、文書化している受け手との handover を
自分から降りているだけ**である。

> **「何も失わない」と書きかけて、それは過大だった** (外部レビュー指摘)。
> `aipChecksum` は不活性ではない — **`canonicalForm()` に入っており**、鍵が在るときに
> 書き換えれば署名検証が落ちる。**台帳 entry の digest にも入る**。
> 失うのは照合ではなく、「**受理された受領証は必ず**、先方のコピーについての
> 署名済み・台帳確定済みの陳述を持っている」という保証のほうである。
> 在るときは今も両方が覆っている。**この訂正自体が、この文書が拒否している形の
> 言い過ぎだった** ので、消さずに残す。

他の 2 案は悪い。接続層で拒否するのは同じことを 1 層手前で言うだけ。
`downloadAipSubmission` を hash して「AIP checksum」と呼ぶのは、
**その bytes は submission なので SIP digest に別名を付けただけ** — 同じ値を
2 欄に入れて 2 つの事実に見せる、この製品が他所で拒否している代用そのものである。

代わりに **`limits()` に出す**: checksum が無い受領証は
「先方が今持っている物の完全性については何も言わない」と受領証自身が言う。

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| `aipChecksum` を必須に戻す | `aReceiptWithNoAipChecksumIsStillVerified` |

### 主張しない

- **送信は依然として未**。SIP を RODA に入れたのは `curl` であって、製品の口ではない
- **RODA の AIP は `INGEST_PROCESSING` 止まり**。受入承認の workflow は今回も未実施
- ~~**Archivematica に対しては未実測**~~ → **§17 で測った** (同日中)。RODA は「投入した
  bytes を返す」対称な経路で、AM は**同梱した manifest の 1 行**を読む非対称な経路である。
  §16 の時点で AM について言えることは無かった、というのがこの行の元の意味であり、
  それは正しかった
- 署名は無い (`signatureVerified = false`)。RODA は署名しない

---

## 17. Archivematica 実機に当てた — 経路の中に囮が在った (2026-08-27)

§16 で RODA を通したが、**AM は非対称な経路**なので何も言えていなかった。
RODA は投入した bytes をそのまま返す。AM は**こちらが同梱した manifest の 1 行**を返す。
測った。

材料は 8-27 の受入試験が残した AIP `32eaa64b-…c270` (出荷形の zipped bag)。
`down -v` していないので volume ごと残っていた。

### 記録していなかった 2 つが確定した

**(1) 中間のセグメントは transfer の uuid で、AIP の uuid ではない。**
§13.2 は `data/objects/metadata/transfers/…/manifest-sha256.txt` の
「…」を **uuid か名前か未確認**として残していた。実物はこうである。

```
am-trial-2026-08-27-32eaa64b-c506-4a43-9a04-43c2a17fc270/     ← AIP root = 名前-AIP uuid
  data/objects/metadata/transfers/
    am-trial-2026-08-27-30f03332-5b7a-43d0-8acc-ecd08229ab0d/ ← 名前-TRANSFER uuid
      manifest-sha256.txt     123 bytes  ← 我々のもの
      manifest-sha512.txt     187 bytes  ← 我々のもの (2 本とも残る)
```

**`30f03332-…` は `32eaa64b-…` ではない。** AIP の uuid を入れて組み立てると 404 になる。
`extract_file` の `relative_path_to_file` は **AIP root のディレクトリ名から**書く。

**(2) 出荷した manifest は 2 本とも残る。** `sha512` 側も同じ場所に在った。
現行の出荷形 (payload manifest 2 本) は AM 側では何も失わない。

### 囮 — より目につく場所に、正しく parse できる別物が在る

AIP の**ルート直下**にも `manifest-sha256.txt` が在る。**4,888 bytes**、
BagIt manifest として完全に正当で、`data/METS.*.xml` や `data/README.html` を並べている。
**AM が自分の AIP について作ったもの**である。

```
{AIP root}/manifest-sha256.txt                          ← AM のもの。4,888 bytes
{AIP root}/data/objects/metadata/transfers/{…}/manifest-sha256.txt  ← 我々のもの。123 bytes
```

浅いほう・見つけやすいほうが**間違ったほう**である。これを読むと、
「先方が持っている、我々の package の digest」のつもりで
**AM が自分の成果物について書いた値**を受領証に入れることになる。
§13.2 が `download/` について書いた誤り (「それは 7z の digest」) と同じ形が、
**BagIt manifest の顔をして**もう 1 か所在った。

**負のコントロールとして実機で撃った。** ルートの manifest を渡すと、
`fromArchivematicaManifest` は **payload 名の行が無い**ことを理由に拒否する
(「has no line for nemaki-bedroom-….zip」)。**最後のパスセグメント完全一致**で
探しているので、AM の manifest がどれだけ正当でも通らない。

### 一周

| | |
|---|---|
| 回収 | 我々の manifest 行 → `7e7fdfa4…02ad`。送った SIP と一致 |
| 語 | `status` = `COMPLETE` → `verificationOutcome=SUCCESS` / `reportedOutcome=COMPLETE` |
| 受領証 | 組み上がり、状態機械が **`RECEIPT_VERIFIED`** を受理 |
| 開示 | `limits()` が「この受領証は先方のコピーの checksum を持たない」と「署名が無い」の 2 つを述べる |

**これで §13.2 の受入条件は両受け手で閉じた。**

### 錠

| 何を押さえているか | テスト |
|---|---|
| 我々の行が無い manifest からは組まない (**囮の実機形はこれで落ちる**) | `archivematicaWithoutOurLineIsUnavailable` |
| 末尾一致ではなく最後のセグメント完全一致で探す | `archivematicaDoesNotAcceptASuffixMatch` |
| 読むのは `extract_file` であって `pointer_file` ではない | `archivematicaReadsTheShippedManifest` |

**実機の囮そのものを固定した錠は無い** — 錠は fixture に対して閉じており、
実機で撃ったのは 1 回きりの観測である。両者を混ぜて数えない。

### 主張しない

- **送信は依然として未。** bag を AM に入れたのは前回の受入試験の手作業である
- **AM の `default` processing config は未測定** (測ったのは `automated`)
- **1 回・1 版・1 構成**である。AIP は既に在ったものを読んだので、
  **取込そのものをこの日に走らせてはいない**

---

## 18. レビュー 1 巡目で出た 7 件 (2026-08-27)

Codex と別レビューを並行で回した。**P1 は 1 件、それは設計ではなく利用者向け文書に在った。**

### P1 — 署名の検証を「取り込まれた証拠」として書いていた

RELEASE_NOTES に **「先方が本当に取り込んだかどうかは、署名を検証できたときにだけ言える」**
と書いていた。`ReceiptSignatureVerifier` 自身の limits はその逆を言っている —
**検証が establish するのは「渡された鍵の持ち主がこの受領証を作った」まで**で、
その鍵が相手組織のものであることも、書かれた内容が真実であることも言わない。

**弱い事実が強い事実として読める形**そのものである。設計文書は正しく、
**利用者が読むほうだけが強く書いてあった** — 検算の向きとして覚えておくべき失敗である。

### 自己矛盾する拒否メッセージ (錠も無かった)

`verifyReceipt` の拒否は先方の生の語を引用する。ところが
`UNRECOGNISED_BY_CONNECTOR` の場合 —
**AM が `SUCCESS` と言った / RODA が `OK` と言った**、つまりこの受け手では測っていない語 —
こう出ていた。

> reports 'SUCCESS'. A receipt that says the receiving system did not accept the package...

**前半と後半が矛盾しており、後半は嘘である。** 先方は拒否していない。
連絡先を間違えさせるメッセージで、**運用上は「相手組織に無かった拒否を問い合わせる」**
という具体的な害になる。

さらに **`asReported()` に変えた行に錠が無かった**: 既存のテストは
`reportedOutcome = null` の受領証を使うので、`verificationOutcome()` に戻しても緑のままだった。
2 本足した (`anUnrecognisedWordIsNotCalledARejection` と、締めすぎを検出する
`aGenuineRejectionStillReadsAsARejection`)。

### `limits()` が RODA の強さを両経路について述べていた

「先方は**この package** を取り込んだ」は RODA については測れている (§16)。
**AM についてはそうではない** — 回収値は**我々が書いて先方が保管しただけの** manifest の
1 行である (§17)。1 文で両方を覆うなら**弱いほうを述べるしかない**ので、そう直した。

RODA の強い言い方は**していない**。するなら受領証に回収経路を持たせることになり、
それは台帳 digest と署名対象の両方が覆う component の追加になる。
**経路が呼び出し元に配線される時にやる判断**であって、今ではない。

> **1 巡目の修正は半分しか当たっていなかった** (2 巡目で両レビューが独立に指摘)。
> 冒頭は弱めたが、**`aipChecksum` が無いときの追記文が同じ強い主張を言い直していた** —
> 「先方が取り込んだ package はこのリポジトリが送ったものである」。
> しかも**この欄はどちらの受け手でも埋まらないのが普通**なので (組み立ては呼び出し元から素通しし、REST も必須にしていない)、これは稀な枝ではなく**接続層が作る
> 全受領証**が通る枝である。錠も無かった (唯一の assert は `"NO checksum"` を見ており、
> 新旧どちらの文でも通る)。冒頭と同じ言い方に直した。
> **「1 文を直した」で終わりにして、同じ主張の別の出口を見に行かなかった**のが誤りである。

### `aipChecksum` について「何も失わない」と書いていた

§16 に追記済み。`canonicalForm()` と台帳 digest の両方に入っているので不活性ではない。

### 残り 3 件 (小)

- `restore()` の受領証検査が `everVerified` の中に在り、**履歴が `RECEIPT_VERIFIED` に
  届いていない row に載った受領証は無検査**で読み戻され、describe が描画していた。
  写像の整合だけは**受領証が在れば常に**見るようにした
  (`aForgedMappingIsRefusedEvenWhereItUnlocksNothing`)
- AM の manifest 取得だけ `ofString()` で**上限が無かった**。RODA 側は最初から
  streaming + `MAX_BYTES` である。**同じ外部読取に 2 つの答え**を出していた。
  1 MiB で切るようにし、締めすぎ検出の control も付けた
- `bag` エンドポイントが `X-Nemaki-Csip-Validated` を返していなかった。
  **中の SIP は同じ検証を通っている**のに、bag を受け取った側だけ
  「検査した」と「検査していない」を区別できなかった

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| `canonicalForm()` から `aipChecksum` を落とす | `anAlteredAipChecksumIsCaught` |
| manifest の上限を外す (streaming は残す) | `archivematicaManifestIsBounded` |
| 拒否メッセージを 1 本に戻す | `anUnrecognisedWordIsNotCalledARejection` |
| `restore()` の整合検査を `everVerified` の中へ戻す | `aForgedMappingIsRefusedEvenWhereItUnlocksNothing` |

> **1 件目は「不活性に見える欄」の典型**である。照合に使われていないと分かった欄は
> 掃除の対象に見え、`canonicalForm()` から外しても**当時のテストは全部緑だった** —
> 既存の改竄テストが `sipDigest` を書き換えていたからである。

---

## 19. レビュー 2 巡目 — **1 巡目の訂正そのものが 2 件壊れていた** (2026-08-27)

Codex と別レビューを再度並行で回し、**「1 巡目の修正を検証せよ、1 巡目を繰り返すな」**
と指示した。**両者が独立に同じ 2 件を挙げた。**

### P1 — 訂正が反対側へ振れていた (`aipChecksum`)

1 巡目で「何も失わない」を訂正し、失うのは
**「受理された受領証は必ず、署名済み・台帳確定済みの陳述を持つ」保証**だと書いた。
**これも過大である。**

- `verifyReceipt` は**署名の無い受領証を受理する**
- 台帳 entry は受理時ではなく **`passCustody` のときに**書かれる

必須にしていたことが保証していたのは**存在**だけである。それが署名で覆われるかは
鍵の有無次第、台帳に入るかは custody が実際に渡るか次第で、**どちらも元から条件付き**だった。

**過大 → 過小 → 過大**と 2 度振れた。3 つとも消さずに残す。
**訂正は行き過ぎることがあり、行き過ぎた訂正は「より厳密になった」ように読める** —
これが 2 番目のほうが教訓として大きい理由である。

### P1 — `limits()` の修正が半分しか当たっていなかった

冒頭は弱めたが、**`aipChecksum` が無いときの追記文が同じ主張を言い直していた**。
しかも**この欄はどちらの受け手でも埋まらないのが普通**なので (組み立ては呼び出し元から素通しし、REST も必須にしていない)、これは稀な枝ではなく
**接続層が作る全受領証**が通る枝である。錠も無かった
(唯一の assert が `"NO checksum"` を見ており、新旧どちらの文でも通る)。

**「1 文を直した」で満足して、同じ主張の別の出口を探さなかった。**

### 拒否メッセージは、もう 1 段割る必要があった

1 巡目で `UNRECOGNISED_BY_CONNECTOR` を「先方が拒否した」と言わないよう割った。
**例がある case だけを割っていた。**

> **この表は 2 巡目時点の判断である。現行は §20 の表を見ること。**
> `PARTIAL_SUCCESS` はここで「拒否」に分類したが、**3 巡目で取り下げた** —
> `PARTIAL_SUCCESS` で grep してこの表だけを読むと、集合へ戻すことになる。

| 先方の語 | 1 巡目の後 | 2 巡目の判断 |
|---|---|---|
| `FAILED` / `FAILURE` / `REJECTED` | 拒否と表示 | **拒否** |
| `PARTIAL_SUCCESS` | 拒否と表示 | ~~**拒否**~~ → **§20 で取り下げ** (拒否ではない) |
| `UNRECOGNISED_BY_CONNECTOR` | 語彙の問題と表示 | **語彙の問題** |
| RODA `RUNNING` / `SKIPPED`、AM `PROCESSING` / `USER_INPUT` | **拒否と表示** | **終わっていないだけ** |
| 欄が空 (REST から到達可能) | **`'null'` を引用して拒否と表示** | **何も言っていない** |

4 行目・5 行目は**先方が何も断っていないのに、相手組織へ問い合わせさせる**。
`isMeasuredRefusal` (→ §20 で `isRecordedRefusal` に改名) を足し、4 分岐にした。
**拒否を主張するのは、測った受け手が実際に拒否に使う語のときだけ**である。

### 接続の漏れ — `ofString()` を streaming に替えたときに作った

上限を付けるため `BodyHandlers.ofString()` を `ofInputStream()` に替えた。
**`ofString()` は body を消費するが `ofInputStream()` はしない。**
status 判定で早期 return する 403 / 404 経路が body を閉じないまま返るようになり、
**接続が pin されたまま残る**。しかも 404 は**この経路が「zipfile で送った場合」として
想定している常路**である。同じファイルの `hashOf` は正しく閉じていた。

**「上限を付けた」修正が、上限と無関係の欠陥を持ち込んだ**形である。

### 利用者向けの誤り — 個人データ

RELEASE_NOTES に **「個人データは既定で入りません」**と書いていた。
`includeInternalOnly` が選ぶのは**メタデータの属性**であって、
**文書の本文は常に入る**。本文に個人データがあれば、既定でも出ていく。
**運用者が「このフラグがあるから安全」と読む**種類の誤りである。

### 錠 (負のコントロール 5 本、全部実測)

| 壊した箇所 | 落ちたテスト |
|---|---|
| 非 200 の body を閉じない | `archivematicaClosesTheBodyOfARefusedResponse` |
| 拒否メッセージを 2 分岐へ戻す | `anUnfinishedOutcomeIsNotCalledARejection` / `anAbsentOutcomeIsNotCalledARejection` (空欄も `'null'` を引用して拒否と表示するため 2 本落ちる。**表に「1 本」と書いていたのを 3 巡目に訂正**) |
| 空欄の分岐を消す | `anAbsentOutcomeIsNotCalledARejection` |
| bag の検証ヘッダを消す | `theBagResponseCarriesTheValidatorVerdict` |
| 台帳 digest から `aipChecksum` を落とす | `theReportedAipChecksumIsCommittedTo` / `theDigestIsDomainSeparated` |

> 最後の行は 2 本落ちる。`theDigestIsDomainSeparated` が golden 値を固定しているためで、
> **狙った 1 本ではない**。表に「1 本」と書かないのは §15 と同じ理由である。

### この 2 巡で学んだこと

1. **訂正には向きがあり、行き過ぎる。** 1 巡目の訂正 2 件のうち 2 件とも
   反対側へ振れていた。**訂正のレビューは、元のレビューと同じだけ要る。**
2. **1 つの主張には出口が複数ある。** `limits()` の冒頭を直しても、
   同じ主張が追記文に残っていた。**直した文ではなく、直した主張で grep する。**
3. **例がある case だけを割ってしまう。** 拒否メッセージは 2 回とも
   「手元にある例」に合わせて割り、残りをまとめて誤った側へ倒していた。
4. **手段を替えると、目的と無関係の契約も替わる。** `ofString()` → `ofInputStream()` は
   「上限」の話だが、**body を誰が消費するか**という別の契約も動かしていた。

---

## 20. レビュー 3 巡目 — **同じ 1 つの主張に、出口が幾つもあった** (2026-08-27)

3 巡目も両者を並行で回し、「2 巡目の修正を検証せよ」と指示した。**P1 が 3 件**。
**そのうち 2 件は、1・2 巡目で直したはずの主張が別の出口に残っていたもの**である。

### 「先方がこう報告した」は、署名が無ければ言えない

`limits()` の冒頭は **「A verified receipt establishes that the receiving system reported
this outcome」** だった。同じ文字列の末尾には、署名が無いとき
**「anything that could reach this endpoint could have sent it」**が付く。
**1 つの文の中で矛盾していた。**

言えるのは **「この受領証がこう報告している」**までである。誰が書いたかは別の問いで、
**この製品が検証できた署名だけが答える**。

### 同じ主張の出口を、4 巡かけて 10 か所見つけた

| 出口 | 何巡目で直したか |
|---|---|
| `RELEASE_NOTES` の「署名を検証できたときにだけ言える」 | **1 巡目** |
| `CustodyReceipt.limits()` の冒頭「先方はこの package を取り込んだ」 | **2 巡目** |
| 同 `aipChecksum` 欠落時の追記 (同じ主張の言い直し) | **3 巡目** |
| `CustodyState.RECEIPT_VERIFIED.limits()` 「the far end received and processed OUR package」 | **3 巡目** |
| `CustodyTransferController.CUSTODY_LIMITS` 「what the receiving system reported」 | **3 巡目** |
| `custody-submission-agreement.md` §2 「受領証は先方の陳述である」(**日本語**。英語 grep では当たらない) | **3 巡目・並行レビュー指摘** |
| `CustodyState` の `RECEIVED` / `VALIDATED` / `INGEST_ACCEPTED` / `AIP_CREATED` (**同じ switch の隣の case**) | **4 巡目** |
| `ReceivingSystem` の「RODA reported no pluginState」(**引数が空なだけ**) | **4 巡目** |
| `EarkSipExporter.withholdingPersonalData()` (**メソッド名**) | **4 巡目** |
| `authenticity-roadmap.md` の**次の一段への指示** | **4 巡目** |

**`CustodyState` と `CUSTODY_LIMITS` は差分に入っていなかった**ので、
1・2 巡目は開いてすらいない。しかも `CustodyState` の文と `receipt.limits()` の文は
**同じ応答ボディに並んで出る** (`stateLimits` / `stateMeans` と `receipt.limits`)。
読み手は**取り下げた主張とその取り下げを同時に**受け取っていた。

§19 の教訓 2 に「**直した文ではなく、直した主張で grep する**」と書いた。
**そう書いた同じ変更の中で守れていない。** grep が外したのは、
文が `+` 連結で割れていたからである — **教訓を書くことと、実行できる形にすることは別**である。

### 欄が空なのは、先方が省いた観測ではない

`limits()` は **「The receiving system reported NO checksum of its own copy」**と書いていた。
`aipChecksum` は**組み立ての呼び出し元から渡る**値で、必須でなくなった今、
**REST の呼び出し元が入れなかっただけ**の場合がある。
そのうえ **Archivematica は pointer file の PREMIS に AIP checksum を持っている** —
使わない理由は「**こちらの package の digest ではない**」であって「返さない」ではない。

**「この受領証は先方のコピーの checksum を持たない」**に直した。受領証について言える事実である。

### 直した主張が、機械可読な側に残っていた

RELEASE_NOTES は「これは属性の話で、本文は常に入る」に直した。
しかし **`X-Nemaki-Includes-Personal-Data: false`** が両エンドポイントから出続けており、
**テストがその値を固定していた**。散文より悪い — **呼び出し元が分岐できる**。

`X-Nemaki-Includes-Internal-Only-Properties` に改名し (フラグが実際に支配するもの)、
**`X-Nemaki-Content-Included: true` を足した** (本文は常に入る、を機械可読に言う)。

### 「測った」の意味が 1 ファイルの中で 2 つあった

`MEASURED_REFUSALS` に `REJECTED` と `PARTIAL_SUCCESS` を入れ、javadoc に
「MEASURED to use」と書いていた。実際に線で見たのは **`FAILURE` と `FAILED` だけ**で、
`REJECTED` は**先方のソースから読んだ**語である。同じファイルの
`measuredSuccessWord()` は両方とも実測なので、**同じ語が 2 つの意味で使われていた**。

`RECORDED_REFUSALS` / `isRecordedRefusal` に改名し、出所を語ごとに書いた。

### `PARTIAL_SUCCESS` は拒否ではない — 決め直した

2 巡目でこれを拒否集合に入れ、理由を「§1.4 が部分成功を成功として扱わないから」とした。
**それは `reportsSuccess()` が通さない理由**であって、
**先方が断ったと運用者に伝える理由ではない**。submission agreement §1.4 は
**「部分受入は受入か」を当事者間の未決事項として開いてある** — それを
「先方は受け入れなかった」に変換するのは、**製品が当事者の代わりに決めている**。

集合から外した。`reportsSuccess()` は依然として通さない (そこは変えていない)。

### 錠 (負のコントロール 6 本、全部実測)

| 壊した箇所 | 落ちたテスト |
|---|---|
| `CustodyState` の文を強い主張へ戻す | `everyStateCarriesItsLimits` |
| `PARTIAL_SUCCESS` を拒否集合へ戻す | `theWholeRecordedVocabularyIsClassified` |
| `REJECTED` を集合から落とす | 同上 |
| `X-Nemaki-Includes-Personal-Data` を戻す | `theOmissionsAreInTheHeaders` (`/export`) / `theBagResponseCarriesTheValidatorVerdict` (bag。**4 巡目に足した** — 3 巡目は `/export` しか錠に入れておらず、bag に旧名を戻しても緑だった) |
| bag の限定接頭辞を外す | `theBagResponseCarriesTheValidatorVerdict` (**3 巡目は 3 本のヘッダのうち 1 本しか見ていなかった**。4 巡目に残る 2 本を足し、`notes` を空にしていて一度も実行されていなかったループも動かした) |
| `hashOf` の非 200 close を落とす | `rodaNonOkIsUnavailable` |

語彙表は 8 語すべてを固定した (`FAILURE` / `FAILED` / `REJECTED` /
`PARTIAL_SUCCESS` / `RUNNING` / `SKIPPED` / `PROCESSING` / `USER_INPUT`)。
2 巡目は 2 語しか触れておらず、**集合をどちらへ動かしても緑のまま**だった。

### この 3 巡で分かったこと

**訂正は 3 巡とも「別の出口」を残した。** 1 巡目は散文を、2 巡目は同じメソッドの別の文を、
3 巡目は差分外のファイルとヘッダを。**主張は文ではなく分布として存在する**ので、
直したら**同じ主張を述べている場所を全部数え直す**しかない —
そしてそれは、文字列 grep では届かないことがある。

---

## 21. レビュー 4 巡目 — 出口はまだ 4 か所あった (2026-08-27)

「**取り下げた主張の、次の出口を探せ**」とだけ指示して 4 巡目を回した。
3 巡かけて 6 か所潰したつもりだったが、**4 か所残っていた**。

### 残っていた 4 か所

| どこ | なぜ 3 巡かけて見つからなかったか |
|---|---|
| `CustodyState` の `RECEIVED` / `VALIDATED` / `INGEST_ACCEPTED` / `AIP_CREATED` | **3 巡目に直した `RECEIPT_VERIFIED` と同じ `switch` の、隣の case**。1 つ直して満足した |
| `ReceivingSystem` の「RODA reported no pluginState」 | **引数が空なだけ**を「先方が言わなかった」と書いていた。3 巡目に `aipChecksum` で同じ誤りを訂正した、その**同じ理屈が別の欄に**在った |
| `EarkSipExporter.withholdingPersonalData()` | **メソッド名**。§20 が「機械可読な側は散文より悪い」と書いた当のものが、ヘッダの隣に在った |
| `authenticity-roadmap.md` の Phase 3-4 行 | **差分の外**、しかも**次の一段 (送信) への指示書**。「SIP の checksum は接続層で保持する」= 落ちない検査、「COMPLETE 自体がマニフェスト一致の証拠」= 受領証が記録していない推論。**直さなければ送信経路がそのまま誤る** |

`CustodyState` の 4 つは **`POST /advance` でしか到達しない**。
この版に送信経路は無いので、**この製品は受け手から一度も何も聞いていない**。
それを「先方がこう言った」と書いていた — **運用者が自分で入れた値を、先方の言葉として返していた**。

### 直したのに錠が無い、を今回もやった

`CustodyState` の 4 つを直したあと**負のコントロールが発火しなかった**。
既存の錠は `AIP_CREATED.contains("has not checked")` を見ており、
**強い文でも弱い文でも通る**。全 state を走査して帰属句を禁じる形に締め直した。

**3 巡連続で同じ形をやっている** — 直した文の錠が、隣の行を測っている。

### `PARTIAL_SUCCESS` が、今度は別の嘘のバケツに落ちた

3 巡目で拒否集合から外したのは正しかった。ところが**残りものバケツ**の文は
「**先方はまだ終わっていないのかもしれない**」と言う。**部分成功は終わっている。**
待たせるのは、苦情を言わせるのと同じだけ間違いである。

独立の分岐にした。言うのは「**拒否でも未完了でもない。部分的に成功した。
この製品は部分取込を受入として扱わないが、当事者がどう扱うかは submission agreement
§1.4 の問いで、このリポジトリが答えるものではない**」。

錠も締めた。語彙表は **3 つのメッセージ類を取り違えられない形**にした
(「did not accept」の有無だけ見ていたので、非拒否の 2 文は交換可能だった)。

### bag の接頭辞は 3 本のうち 1 本しか錠に入っていなかった

`X-Nemaki-Export-Limits` と `X-Nemaki-Export-Note` にも同じ接頭辞を付けたのに、
錠は `X-Nemaki-Csip-Validation-Limits` だけを見ていた。しかも **bag のテストは
`notes()` を空にしていた**ので、`X-Nemaki-Export-Note` のループは
**どのテストでも一度も実行されていなかった** — 丸ごと消しても緑である。
3 本とも錠に入れ、note を 1 本渡してループを動かした。

### 負のコントロール 3 本

| 壊した箇所 | 落ちたテスト |
|---|---|
| `AIP_CREATED` の帰属を戻す | `everyStateCarriesItsLimits` (**締め直す前は発火しなかった**) |
| `PARTIAL_SUCCESS` を残りものバケツへ戻す | `theWholeRecordedVocabularyIsClassified` |
| bag の note から接頭辞を外す | `theBagResponseCarriesTheValidatorVerdict` |

### 直したのに錠が無い、が同じ巡でさらに 3 本 (並行レビュー指摘)

`CustodyState` を締め直した直後に、**同じ形が 3 本残っていた**。

| 直した箇所 | 錠が無かった理由 |
|---|---|
| 空欄メッセージ (`no pluginState was given`) | 既存の錠は `assembled()==false` だけを見ており、**理由の文を見ていない**。しかも AM しか叩いていなかった (2 つの enum 定数が別々に文を持つ) |
| `verifyReceipt` の早期拒否 | **早い状態から `verifyReceipt` を呼ぶテストが 1 本も無かった**。旧文に戻しても誰も落ちない |
| メソッド名 `withoutInternalOnlyProperties()` | 呼び出し元は揃えたので旧名を**消せば**コンパイルが落ちるが、**別名で足せば緑**。ヘッダ側は旧名を `assertNull` したのに、メソッド側はしていなかった |

**帰属禁止の書き方自体も甘かった。** 旧 3 句 (`says` / `accepted` / `REPORTS`) を
禁じる形だったので、`INGEST_ACCEPTED` の旧文はどれも含まず素通りし、
言い換え (`The receiving system has the package`) も通った。
**`SOMEBODY RECORDED` で始まることを要求する**形に変えた —
**禁止語の列挙は言い換えで抜けられるが、要求は抜けられない**。

### 正典の「意味」欄が、直した文と逆を教えていた

§1 の表は `AIP_CREATED` を **「先方が言ったこと。保存コピーが在ると先方が報告した」**
のままにしていた。`limits()` を `SOMEBODY RECORDED` に直しながらである。
**次に読む人は正典を正として文を戻す** — ロードマップを直した理由と同じ種類の残骸が、
同じ文書の中に在った。

「先方が言ったこと ↔ こちらが確かめたこと」という**対比そのものは正しい**。
ただし送信経路が入るまで、左側は**「誰かが記録したこと」**までしか言えない。

### 負のコントロール (この節ぶん 3 本、いずれも実測)

| 壊した箇所 | 落ちたテスト |
|---|---|
| 空欄を「RODA reported no pluginState」へ戻す | `aBlankOutcomeIsNotCalledReceiverSilence` |
| 早期拒否を「the receiving system has reported an AIP」へ戻す | `verifyingBeforeAipCreatedDoesNotAttributeAnythingToTheReceiver` |
| 旧メソッド名を別名で足す | `noFactoryClaimsToWithholdPersonalData` |

### 4 巡で分かったこと

**主張の出口は、直すたびに増えて見える。** 実際には最初から 10 か所あって、
1 巡ごとに見える範囲が変わっただけである。見つかった順は
**散文 → 同じメソッドの別の文 → 同じ列挙子の別の定数 → 同じ switch の隣の case →
メソッド名 → 差分外の指示書**で、**後になるほど文字列検索から遠い**。

**「同じ主張で grep する」では足りない。** 4 巡目に効いたのは
「取り下げた主張を 6 つ並べ、それぞれについて**意味で**探せ」という指示のほうだった。

---

## 22. 5 巡目 — **錠が「半分だけ直した文」を固定していた** / 12 個目は §0 (2026-08-27)

11 個目の出口が出た。**そして今回いちばん重いのは、出口そのものより錠のほうである。**

### 半分だけ直して、その半分を錠で固定した

`CUSTODY_LIMITS` は 3 巡目に **「what the receiving system reported」** から
**「what a receipt SAYS the receiving system reported」** へ直した。
一見それで足りている — 主語が「受領証」になったからである。

**足りていない。** 受領証にあるのは outcome 欄で、
**その欄が「先方が報告した値」であること自体が、署名の検証でしか establish できない仮定**である。
「受領証は、先方がこう報告したと言っている」は、
**REST で誰かが打った値に先方の名前を貼っている**。

そして **3 巡目に私が書いた錠が `contains("a receipt SAYS")` を要求していた** —
つまり**残っている側を固定していた**。直した文に対して錠を書くと、
**打ち込んだものがそのまま固定される。間違っている部分ごと。**

直した形は、受領証から先方を外すことである: **「what outcome a receipt reports」**。
錠も裏返した — `contains("the receiving system ")` を**禁じ**、意味の側を要求する。

同じ形が `mappingRefusalReason()` の 409 本文にも在った
(「this receipt says the receiving system reported ...」)。
**「this receipt says」で始まるので直したつもりになる**が、
文の後半で先方の名前を値に貼っている。
「this receipt carries the reported word ... and claims it means ...」に直した。

### 接続層の契約 javadoc が、自分の実装コメントと逆を言っていた

`CustodyReceiptAssembler` のクラス javadoc は **「what a receiver holds」** で始まり、
**「what the receiver did with OUR package」**と続く。ところが同じファイルの
実装コメントは **「calling it 'what the far end has' would overstate it」** と書いてある。
**書いた本人が、同じファイルの中で両方を書いていた。**

`SubmittedDigestRecovery` のクラス javadoc (「what the RECEIVER holds」) と
`CustodyReceipt.sipDigest` の説明 (「what the far end holds of OUR package」) も同じ。
RODA では真だが AM では**我々が書いて先方が保管した 1 行**である、と書き直した。

### ロードマップが、実装が意図的に外した必須項目を要求したまま

P3-4 の要求行に **「署名付き受領」**が残っていた。この製品は先方の鍵を持たないので、
必須にすると**検証できない署名文字列を必須にする**ことになる。
`missingRequiredField()` は署名を要求せず、RELEASE_NOTES も「必須ではない」と書いている。
**正典だけが強いまま**だった。`aipChecksum` を外したときと同じ形である。

### 錠が無いと分かっている 1 か所 (据え置き)

`move()` の `next == CUSTODY_TRANSFERRED && receipt == null` ガードは**到達不能**で、
外しても全テストが緑である。ソースの中でそう書いてあり (「Not counted as a measured
protection」)、§4 も同じことを言っている。**測れた保護として数えていない**ので、
「全部に錠がある」とは書かない。

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| 「what a receipt SAYS the receiving system reported」へ戻す | `theEndpointLimitsSayWhoseStatementAReceiptIs` |

### 12 個目は、この文書の §0 に在った (自己点検)

レビューを待つあいだに**自分で文書の先頭を読み直して**見つけた。
§0 の「主張しない」に、**1 巡目に RELEASE_NOTES で取り下げた当の文**が残っていた。

> 「先方が取り込んで、この結果を報告した」と読めるのは**署名を検証できたときだけ**

検証が establish するのは **「渡された鍵の持ち主がこの受領証を作った」**までである。
鍵が相手組織のものであることも、書かれた内容が真実であることも言わない。

**5 巡のレビューはどれも §0 を開いていない。** 指示が毎回
「差分を見よ」「直した箇所を検証せよ」だったからで、
**§0 は差分にも入らず、直した箇所でもない**。
`RELEASE_NOTES` と `CustodyState` と `CUSTODY_LIMITS` を全部直したあとで、
**読者が最初に当たる場所が最後まで残っていた**。

**訂正は、その主張を最初に述べた場所へ最後に届く。** 出口を数えるときは
**文書の先頭から**数え直すこと。

### 5 巡で分かったこと

**錠は、直した文ではなく取り下げた主張に対して書く。**
文に対して書くと、**打ち込んだ文がそのまま正典になる** — 半分しか直っていなくても。
今回の錠は「この語が出てはならない」を先に置き、
そのうえで意味の側を要求する形にした。前者だけでは言い換えで抜けられ (4 巡目)、
後者だけでは半分残った文を固定する (5 巡目)。**両方要る。**

---

## Phase 1 / 2 への横展開 — 同じ失敗様式が 4 つの P1 として在った (2026-08-27)

P3-4 で 5 巡回したあと、**同じ失敗様式を Phase 1 / 2 に探しに行かせた**。在った。
しかも監査は、5 巡の revert→fail が**構造的に見つけられない**理由を名指した。

### その理由 — 「fan-out の片腕だけ直し、その片腕だけ測る」

見つかった 8 件のうち 5 件が同じ機構である。

| どこ | 直された腕 | 測られなかった腕 |
|---|---|---|
| 複製の開示 vs 行 | 変換器側 | 報告側 |
| 報告の 8 節 | `identity` | `versions` |
| `NOT_CHECKED` の生産者 2 つ | 読めないリンク | 読めないトークン |
| アンカー 3 段 | `RFC3161_TSA` | `ATLAS_CATALOG` |
| store 不達の 2 分岐 | 未配線 | 配線済みだが空 |

**共有行を revert すると、覆われている腕で赤くなる。** だから revert→fail は通り、
欠陥は残る。テストの `@DisplayName` は一般化して書いてあるのに、fixture は 1 腕しか
固定していない — **名前が「全部見た」と言い、fixture が「1 つ見た」と言っている。**

### P1 4 件

**1. 複製の節が、同じ map の中で否定と肯定を並べていた。**
`DUPLICATION_DISCLOSURE` は「**no output format produced here is requested or validated
against an archival profile**」「この報告は passed とも failed とも**言わない**」と書き、
2 キー先の行が `archivalProfileOutcome: CONFORMS` を平文で載せていた。
P3-2 §10 の規則 (**開示は置き換える、足さない**) に反しており、
**§9 が同じ分裂を 1 度記録している**。2 度目である。

錠は `duplications(entries, true)` を**rendition 無し**で呼んでいた —
**矛盾が起きない腕**を固定していたので緑だった。
verdict を持つ腕に対して書き直した。

**2. CouchDB 障害が「この記録は文書ではない」という所見になっていた。**
`versions` 節は `!(content instanceof Document)` で `ABSENT` を返す。
`content` は読取失敗のとき `null` で、`null instanceof Document` は false —
`ABSENT` は「**この種のものはこの記録に本当に無い**」の意味である。
`identity` と `content` は同じ入力を `UNAVAILABLE` にしており、
**fan-out の 1 本だけが漏れていた** (p1-4 AC6)。
錠 (`unreadableIsNotEmpty`) は `identity` だけを見ていた。
**全節を走査する**形に書き直した。

**3. `unanchoredEntries` が、封をした checkpoint から数えていた。**
段が 1 つも構成されていない配備、あるいは唯一の段が `FAILED` の配備で、
`POST /checkpoint-and-anchor` のあと `GET /status` は **`unanchoredEntries: 0`** と答える。
**全件が unanchored である。** これは運用者が「台帳がまだ静かに書き換えられる窓」を
測る唯一の数字 (p2-0 §0) で、**露出が全面のときに「露出なし」と読めていた**。
`CONFIRMED` の受領証から数える形にし、`entriesAfterLatestCheckpoint` を別の欄に分けた。
受領証 store が答えられないときは **0 ではなく null + 理由**である。
**この欄にはテストが 1 本も無かった。**

**4. フォルダの fixity 走査が、直下の子だけ見て `COMPLETE` と答えていた。**
`FixityScanService` は渡された iterable が尽きたら `COMPLETE` を立てる
(「対象範囲を最後まで見た」)。`FixityController.scanFolder` は
`getChildren` を 1 段だけ列挙する。**部分木の全文書が黙って範囲外**だった。
CLAUDE.md が 2 度名指している `COMPLETE` の罠と同じ形である。

しかも `FixityScanService` の javadoc は**その免疫を主張していた** —
「so a caller cannot get the honesty rules wrong by choosing a different source」。
**唯一の本番呼び出し元がまさにそれをやっていた。** 逆を書き直し、
呼び出し元が範囲を絞るなら**応答に範囲を書く**ことを義務にした
(`scope: IMMEDIATE_CHILDREN_ONLY` + `scopeLimits`)。再帰はしない —
深い木の無制限走査と `limit` の意味変更を伴い、レビューで妥当性を確認できる変更ではない。

### 負のコントロール 4 本

| 壊した箇所 | 落ちたテスト |
|---|---|
| 開示に「passed とも言わない」を戻す | `theDisclosureDoesNotDenyItsOwnRows` |
| `versions` の null 分岐を消す | `unreadableIsNotEmptyInAnySection` |
| 露出を封から数える形に戻す | `aSealedButUnanchoredCheckpointIsFullyExposed` |
| フォルダ走査から範囲の明示を消す | `aFolderScanNamesItsScope` |

### 残り (P2 以下、未着手)

`AnchorReceiptCodec` の semantics fallback が **`ATLAS_CATALOG` を昇格**させる、
`ErsVerifier` が読めないトークンを **checked に数える**、
checkpoint 文書が常に `anchored: false`、
`LongTermValidityService` の「配線済みだが空」が未配線より不誠実、
`ErsFormat.LIMITS` が作っていない成果物を説明している、ほか。

### P2 群 (2026-08-27) — うち 1 件は**測れなかったと書く**

**アンカー段の昇格** (`AnchorReceiptCodec`)。`timeSemantics` が読めないときの fallback は
`UPPER_BOUND_ONLY` だった。`RFC3161_TSA` には降格だが、
**`ATLAS_CATALOG` (既定 `NOT_A_TIME_PROOF`) には昇格**である。
破損した 1 行が、同一組織のカタログ受領証を
「この commitment はこの時刻より前に存在した」に見せる。
このクラスの契約は **「reload が受領証を強められてはならない」**である。

`TimeSemantics.weakerOf` を足し、**弱いほう**を採る形にした。
錠は 3 段すべてを走査する (既存の錠は `kind` を `RFC3161_TSA` に上書きしてから
assert しており、**fallback が正しく働く腕だけ**を測っていた)。
負のコントロール `AF` で確認済み。

**読めないトークンが checked に数えられていた** (`ErsVerifier`)。
`NOT_CHECKED` の生産者は 2 つ (リンクが辿れない / トークンが parse できない) で、
後者は `checked++` に落ちていた。結果は
`linksHold=false, timestampsNotChecked=0` — **機械可読な側では記録についての所見**で、
隣の散文は「これは間違いだという所見ではない」と言う。
p2-3 §8 が同じ形を 1 度直している。両方を数える形にした。
併せて `checked == 0` の分岐が **notChecked を捨てて「タイムスタンプが無い」と言う**
経路も分けた。

> **この修正の錠は 3 度目で掛かった。1 度目と 2 度目に何を書いたかを残す。**
>
> 1 度目 — トークンを**壊す** fixture を 3 つ作り、3 つとも空回りした。
> トークンはレコードの DER に**構造として埋まっている**ので、壊すと
> **レコード側の parse が先に落ち**、`verify()` は position を 1 つも見ないまま
> results 0 件で返る。ここで **「到達不能」**と書いた。
>
> 2 度目 — トークンは `ContentInfo` として格納される、と気づいた。
> **contentType が違う正当な `ContentInfo`** なら壊さずに済む。作って測って、
> やはり届かなかった (hash-tree renewal に載せたので、position は `check()` の手前で
> `expectedImprint` が落ちる)。ここで **「到達可能かは未解決」**に直した。
> 1 度目より正しいが、まだ違った。
>
> 3 度目 (外部レビュー指摘) — **`[0]` の algorithm 欄を省いた record** にすればよい。
> RFC が許す形で、`ErsRecord` は `imprintAlgorithmOf` を呼び、
> **それは全例外を捕まえて SHA-256 に落とす**。
> つまり**読めないトークンはレコードの parse を生き延びるように作られている** —
> **この経路を到達不能に見せていた寛容さが、到達可能にしている当のもの**だった。
> 負のコントロールは発火し、修正は測れている。
>
> **教訓は「1 つの攻め方が失敗したこと」を「できない」と書くなではなく、
> 2 つ失敗しても足りない**ということである。3 つ目は、
> 私が 2 度とも見ていなかった**製品側の寛容さ**から来た。
>
> 途中で **latent flake も 1 つ作った** — `tokenOver` は呼ぶたびに genTime が変わるので、
> 「生成し直したトークンをレコードの中から探す」fixture は**同一ミリ秒でしか一致しない**。
> 一度緑になっており、落ちるより悪い。トークンを 1 度だけ作って使い回す形に直した。

### P2 群 続き — 「聞けなかった」と「無い」が同じ答えになっていた 3 か所

**checkpoint 文書が常に `anchored: false` を名乗っていた。**
`toDocument()` が hard-code しており、その map は**保存行であると同時に REST の payload**
である。動く TSA に対して `POST /checkpoint-and-anchor` を 1 回打つと、応答に
**`checkpoint.anchored: false` が `anchor.confirmedRungs: ["RFC3161_TSA"]` と並ぶ。**
受領証 store を一度も見ずに立てた**否定の所見**で、しかも `AnchorService` が
「**段は list で運ぶ。単一の flag にはしない**」と明記している当の単語である。
キーを削り、note を「この行は自分が anchor されたかを言わない。答えは
`GET /v1/admin/anchor/status` に在る」に書き直した。
`AnchorController` 側に残っていた同名のキーは、**この呼び出しについての事実**なので
`anchoredAnything` に改名した (同じ語が 2 つの意味を持たないように)。

**`LongTermValidityService` で、配線済みの store が未配線より不誠実だった。**
未配線は `UNDETERMINED` + 「これはアンカーが無いという所見ではない」を出す。
ところが**配線済みで空**のときは**行を 1 本も出さず**、応答は
`hashTreeRenewalsDue: 0, undetermined: 0` になる。
CouchDB の view が構築中だと `[]` を返して例外は投げないので、
**「store が何も言わなかった」と「無い」が同じ答え**だった。
`AnchorReceiptStore.isActive()` の javadoc は
「聞けなかった store から『pending は無い』を読んではならない」と言っており、
**この呼び出し元は聞いていなかった**。3 状態に割った (未配線 / 聞けない / 聞いて空)。
3 本目は控えめな方向の control でもある。

**`ErsFormat.LIMITS` が、このビルドが作らない成果物を説明していた。**
`renewalFormatLimits` として呼び出し元へ出て行く文字列である。
「data object は checkpoint の **hash**」— p2-3 §8 が
**それをやると標準ツールが読めない記録になる**と記録して直した当の言い方で、
`ErsRecord.LIMITS` は正しく「canonical な**バイト列**」と言っている。
**出荷している 2 つの limits が食い違っていた。**

> **そして錠が、誤っている側を固定していた。** `ErsFormatTest` は
> `contains("checkpoint hash")` を**要求**していた — §8 が「それは誤り」と記録した当の語である。
> 直した瞬間にこのテストが赤くなり、**錠のほうが間違っていた**ことが分かった。
> 5 巡目に P3-4 で見つけたのと同じ形 (**直した文に対して錠を書くと、打ち込んだ文が
> 間違っている部分ごと正典になる**) が、別の機能で独立に起きていた。
> 意図 (「checkpoint についてであって document ではない、と言っていること」) を要求し、
> 誤った言い方を**禁じる**形に書き直した。
「reduced hash tree は 1 ノードを持つ」も §8 が却下した代案で、
`ErsRecord.first()` は `List.of()` を渡す — **木は無い**。
「自動生成はしない」は**同じ文の次の節が否定**していた。3 つとも直し、
**2 つの limits が同じ成果物を describe していること**を錠にした。

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| 「聞けない store」の分岐を消す | `anUnreachableStoreDoesNotReportZero` |
| `anchored: false` を checkpoint 行へ戻す | `aCheckpointDoesNotClaimToKnowWhetherItWasAnchored` |
| fallback がカタログ段を昇格する形に戻す | `anUnreadableSemanticsDoesNotPromoteACatalogAnchor` |

### この横展開で分かったこと

**5 巡の revert→fail が Phase 1/2 の 8 件を 1 つも見つけなかった理由は、
テストが弱かったからではない。** どれも
**「共有行を revert すると、覆われている腕で赤くなる」**形だったからである。
`@DisplayName` は一般化して書いてあり、fixture は 1 腕だけを固定していた。

構造で防ぐなら、`AnchorService.claimLimitsFor` が既に採っている形 —
**enum に対する網羅 switch** で、腕を足したらコンパイラが文言を要求する — が唯一効く。
テストの書き方では届かない。

### P3 群 — 「見なかった」が「無かった」に見える形が、あと 5 か所

**`EvidenceChainVerifier.verify(empty)` が `intact = true`。** 空の span では
**何も見ていないから何も見つからない**だけである。呼び出し元は全部 empty を先に弾いており
潜在的だが、**誰も歩いていない連鎖について「無傷か」に答える public static** は、
この製品が何度も踏んでいる `COMPLETE` / `EMPTY_INDEX` と同じ形である。
`intact` は true のまま (反転させると空の台帳が「破断」を報告する) にし、
**`walkedAnything` と limits の頭に「NOTHING WAS WALKED」**を足した。
控えめ側の control も置いた (歩いた span にこの但し書きが付いてはならない)。

**`renditionsNow` の「答えなし」経路が 3 本とも `List.of()` だった。**
未配線 / 読取が例外 / null 応答 — 3 本とも**キーごと消える**ので、
「読めなかった」と「無い」が同じ出力になっていた。
**ABSENT と UNAVAILABLE を分けることだけを目的にした節**の中でである。
3 本を 1 つのループで錠にした。

**`FixityScanReport` の findings が黙って 500 件で切れていた。** この層の他の打ち切りは
全部「切りました」と言う。(digest は件数にコミットしているので連鎖側は無事だった。)

**`FormatDuplicationRecorder` の「台帳未配線」が `logger.debug` だけだった。**
p1-3 §7.6 が**出荷 logback は debug を捨てる**と記録している。
クラス javadoc の約束は「gap は報告される、例外にはしない」で、
**報告されていなかった**。起動ごとに 1 回 WARN する形にした
(複製ごとに出すと、肝心の行が埋もれる)。

**`kindForToken` が dead。** 消さずに javadoc を書いた — p2-3 §5.5 が
「**署名アルゴリズムはどの段も記録しない**」と記録しており、
これを `digestAlgorithm` (= imprint の方) に配線するのは**別の問いに答えること**である。
一度やられているので、**消すと同じ配線がまた書かれる**。

### `GENESIS` は名前と意味が逆 (未改名)

javadoc もテストも **「意図的な連鎖の切断」** と言っている — 法的要求で span を
取り除いたとき、繋がって見せずに切断として残す種別である。
ところが `GENESIS` は監査人に **「ここが始まり」** と読ませる。共通しているのは
`prevEntryHash` が null なことだけで、**「ここで区間が消えた」の逆に近い**。

**改名していない。** この値は CouchDB の行に**文字列として永続化**されており、
読み側の別名なしに改名すると**既存の台帳が読めなくなる** — 言い換えではなく移行である。
コードと p1-3 §3 の両方に、次に台帳の格納を触るときの候補として書いた。

> 併せて **p1-3 の `subjectKind` 一覧に 3 種欠けていた**のを直した
> (`GENESIS` / `FORMAT_DUPLICATION` / `CUSTODY_RECEIPT`)。
> **一度 `CHAIN_BREAK` という実在しない値を書きかけた** — 意味から名前を推測したためで、
> 正典に「在るべき名前」を書くのは、在る名前を書くのとは別の行為である。

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| 空 span の「歩いていない」表示を消す | `anEmptySpanSaysItWalkedNothing` / `aWalkedSpanDoesNotSayItWalkedNothing` |
| rendition の「読めなかった」理由を消す | `notListingTheCurrentCopiesIsNeverSilentlyEmpty` |

### 機構 1 の 4 例目 — **同じ成果物の、片方の言語だけ直っていた** (2026-08-28 自己点検)

「応答より長生きするもの」を洗った。証拠台帳の scope は直したので、
SIP の中身・永続行・報告のモックを順に見た。

**真正性報告のモックで見つけた。** `report-mock-en.html` の footer には

> This report never asserts that an anchor is independent: it records what was checked and by
> whom, and leaves that judgement to the reader.

が在り、**日本語版の footer には対応する文が無かった**。
`render-mock.py` の `ja` 辞書と `en` 辞書は**別々の文字列**なので、
英語だけ直せば `check-mock.py` は通る — チェッカーが見ているのは
「禁じた語が現れていないこと」であって「両言語が同じことを言っていること」ではない。

**この製品の一次読者は日本語である** (CLAUDE.md)。**取り下げた主張の否認が、
主たる読者にだけ届いていなかった。**

これは今回名前を付けた機構 1 の 4 例目で、腕の切れ目が**言語**である点だけが新しい:

| 例 | 直した腕 | 残っていた腕 |
|---|---|---|
| 1 | 応答の scope | **証拠連鎖の scope** |
| 2 | 報告の 1 節 | **同じ fan-out の別の節** |
| 3 | 変換器側の開示 | **報告側の開示** |
| 4 | **英語の footer** | **日本語の footer** |

**併せて、モックの README が取り下げ済みの仕組みを「主張を守る仕組み」として掲げていた。**
`evidence.trustLevel.independentOfOperator` は `check-mock.py` が**現れたらビルドを落とす**
フィールドで、5 巡のレビューが「独立性を計算できる検査は無い」と結論した当のものである。
表の性質上、**読む人はそこに在るものを「効いている」と受け取る**。取り消し線と理由を入れた。

### 訂正のレビュー 2 巡目 — **私の理由づけが正典と矛盾していた** (2026-08-28)

7 件。うち 2 件は、訂正そのものが新しい誤りだった。

**WARN 化は前提が間違っていた。** `FormatDuplicationRecorder` の「台帳未配線」を
`debug` → 起動ごと 1 回の `WARN` に上げ、理由を
「p1-3 §7.6 は出荷 logback が debug を捨てると記録している」とした。
**§7.6 の結論は逆だった** — 同じ節が、`EvidenceLedgerService` はこのパッケージの
`@Component` で `serviceContext.xml` が scan するので
**「台帳が配線されていない」分岐は稼働中の配備では到達しない**と書いている。
私が語った「毎回 unchained になり誰にも見えない配備」は**存在しない**。

`debug` に戻した。**fail-open の 3 兄弟 (`EvidenceLedgerRecorder` /
`FixityLedgerRecorder` / `FormatDuplicationRecorder`) が同じ立場に揃った**。
`DispositionRecorder` だけ `warn` なのは**fail-CLOSED で操作を拒否する**からで、
別の状況である。

> **引用した節が自分の主張を否定していた。** 節番号は合っていて、中身が逆だった。
> 「§7.6 に書いてある」を根拠にするなら、§7.6 を読み直してから書く。

**`unanchoredEntries` が 1 件少なかった。** sequence は 0 起点なので
`highest = 9` は **10 件**である。露出を測る唯一の数字が、**控えめな方向に 1 件ずれていた**。
空の台帳 (`highestSequence` は `-1`) では **`-1`** を出す。
`Math.max(0, highest + 1)` にした。**錠はコードの出した 9 を固定していた** — 機構 2 の 5 例目。

### 機構 1 の 5 例目 — 機能を持つクラス自身が「PDF/A は作らない」と言い続けていた

`FormatDuplicationRecorder` のクラス javadoc:

> **This product converts to PDF. It does not produce PDF/A.**
> The rendition path runs LibreOffice through jodconverter with **no PDF/A profile and no
> validation**.

`rendition.pdfa.validate.flavour` を設定すれば profile は要求され veraPDF が走る。
**このクラスの `recordDuplication` は `PdfAValidation` を受け取って digest に畳み込んでいる** —
ファイルが自分と矛盾していた。報告側の同じ文は直し、テストが**禁止**までしているのに、
**変換器側は残っていた**。

### 錠に関する 3 件

- **`strongestConfirmed` の錠が「最後の 1 件が勝つ」でも通っていた。** 順序の無い view に
  対する解として、置き換えた「最初の 1 件が勝つ」と同じだけ恣意的である。**両方の順序**で
  回すようにし、**タイは本当に先着**になるよう実装も直した (`strongerOf(a, a)` は第 2 引数を
  返すので、以前の形はタイで置き換えていた — コメントが言っていたことの逆)
- **`strongerOf` に直接の錠が無かった。** 露出量の隣に**どの段を書くか**を決める関数で、
  逆にすると**最弱の段を「覆っている」と名指す**
- **段の総なめテストが、兄弟の持つ fixture 検査を落としていた。** `AnchorReceipt.failed(...)`
  の既定が `NOT_A_TIME_PROOF` — **`ATLAS_CATALOG` 腕の期待値そのもの**なので、
  codec がその行を FAILED に落とすようになると**空回りで通る**

### モックに ja/en の対称性検査を足した

4 例目 (言語で腕が切れる) を直したが、**検査は足していなかった**。
`check-mock.py` は「禁じた語が現れていないか」だけを見るので、
**否認を忘れたページは黙って通る**。両ページが非主張の文を**持っていること**を
要求する検査を足し、日本語側から文を消して発火することを確認した。

### 機構 1 の 6 例目 — **同じページの中で、行と footer が正反対**を言っていた (2026-08-28)

5 例目 (言語で腕が切れる) を直したあと、**同じ成果物の別の腕**が残っていた。

両言語の footer に **「本レポートはアンカーが独立であるとは一切主張しません」**が入った。
ところが同じページの Atlas の行は、こう言っていた。

> 同一 tenant のカタログは独立した証拠になりません。**独立性は OpenTimestamps の記録に
> よって与えられます。**

**後半が独立性の主張そのものである。** しかも「独立ではない」と正しく言った文の
**直後**に置かれているので、否認の文脈で読み流しやすい。

`check-mock.py` はこれを見逃した。**禁止語が `独立している` /
`independent of the operator` の 2 つだけ**で、`独立性は 〜 によって与えられます` は
その形をしていない。**主張のされ方ではなく、1 つの言い回しを禁じていた。**

行を「本デプロイにも OpenTimestamps のカレンダーにも接続せずに検証できる。
**独立かどうかの判断は読み手が行う**」に直し、禁止語を**主張のされ方の側**へ広げた。
実際に出荷ページに在った言い回しを全部入れ、戻して発火することを確かめた。

### `unanchoredEntries` が、古い確定アンカーを勘定に入れていなかった

**最新の checkpoint の受領証しか見ていなかった。** checkpoint 5 が確定、
checkpoint 10 は封をしたが未確定、head が 12 のとき、露出は **6〜12** である。
実装は **13 と答えていた**。

安全側の誤りだが、**欄の定義 (「CONFIRMED な受領証に覆われていない件数」) に対しては
誤り**で、しかも**古いアンカーが確定しても数字が減らない** — 運用者には
「アンカーが効いていない」と読める。

`confirmed()` で全 checkpoint を見て、**最も遠くまで届いている確定アンカー**から測る。
最新の checkpoint に確定があればそれが勝つ (いちばん覆う)。

> **私の javadoc は、この保守的な数字を意図的だと書いていた。** 意図的ではあったが、
> **欄が数えると言っているものではなかった。** 意図の記録は、正しさの記録ではない。

### Codex の 3 件は、私が直した後の版を読んでいた

`findingsTruncated` の述語・タイの扱い・古いコメントは、指摘の時点で既に直っていた。
**並行レビューは木が動いている間に読む**ので、こういう擦れ違いは起きる。
現物を確認してから直す、で扱った (指摘を鵜呑みにして「直す」と、既に正しいものを壊す)。

### 7〜10 例目 — **腕は「まだ在る」ではなく「探し方を変えると出る」** (2026-08-28)

「既知の 6 対を見せて 7 例目を探せ」と 2 本に投げたら、**4 つ出た**。
うち 3 つは、これまでの探し方では構造的に届かない場所である。

**7. 実行時の文字列は直り、クラスの契約 javadoc は残っていた。**
`CustodyReceipt`「What the receiving organisation sent back」、
`CustodyState`「what the far end SAYS」、service と controller の
「a note about what the far end said」。**実行時には出ない**ので、
5 巡の「応答を読む」レビューは届かない。しかし契約 javadoc は
**開発者が最初に読む場所**で、`CustodyState` の定数 javadoc 自身が
「**doc を正典として古い文を戻される**」と書いている当のものである。

> 同じファイルの中でも、**直している行から遠いほど残った** — service と controller の
> 2 か所は既に直していたが、それらは実行時文字列の隣に在り、
> 残った 2 か所は**ファイル冒頭**だった。

**8. ロードマップの中で、同じ主張が 2 通り書いてあった。**
`authenticity-roadmap.md:31` は 2026-08-26 に
「data object は checkpoint の**正規化バイト列**」へ直された。
**同じファイルの 385 行目**は「data object は checkpoint hash」のまま残っていた。
p2-3 §8 が「それをやると標準ツールが読めない記録になる」と記録した当の誤りである。

**そして私はこの巡で `ErsFormat.LIMITS` を、§8 を読んで直した。**
ロードマップを出口として数えなかった — §21 が
「ロードマップは差分の外の指示書だ」と**既に名指していた**にもかかわらず。
**一度名指した場所は、次も見る。**

**9. `custodyHasPassed()` の javadoc** — `CustodyState` で**3 つ目**。
enum 定数と `limits()` を直し、クラス javadoc も直し、メソッド javadoc が残った。

**10. footer が、否認の 1 節前で独立性を主張していた。**

> 本システムの管理者による**改変を排除する**検査は「独立検証可」と記したもののみです。
> **本レポートはアンカーが独立であるとは一切主張しません**…

**前半が主張、後半が否認**で、順番がこうなので読み流す。
`AnchorController` の limits は「アンカーは書き換えを **DETECTABLE** にする。
**防ぐものではない**」と明記している。

**そして禁止語リストは、直前の巡で「言い回しではなく主張を禁じる」ために広げたばかりだった。**
広げた語は `独立性は` / `Independence is supplied` 等で、
`改変を排除する` / `exclude alteration` はその形をしていない。
**「主張のされ方」を列挙している限り、次の言い方は通る。**

### この 4 つが示していること

**「7 例目を探せ」と言うだけでは足りず、「既知の 6 対を見せる」ことで 4 つ出た。**
対を見せると、レビューは**腕の切れ目の種類**を一般化する —
応答/永続、日本語/英語、行/footer、そして今回の**実行時/契約**。

裏返すと、**まだ見つかっていない腕は、まだ見せていない種類の切れ目に在る**。
「全部直した」と書ける状態には、この方法では到達しない。

### 11・12 例目 — 切れ目は「成功と失敗」と「独自チェッカーと可搬な契約」だった

**11 (自己点検)。** `bag` の**成功経路**には「この bag の中の SIP についてであって bag に
ついてではない」という限定を足したのに、**409 / 500 は素のまま**だった。
そこには package が無いのに、`EXPORT_LIMITS` は
「**This package is built to E-ARK CSIP 2.2.0**。検証器が走ったかは
`X-Nemaki-Csip-Validated` ヘッダに在る」と、**存在しない成果物と存在しないヘッダ**を説明する。
`/status` (能力を答えるだけ) も同じだった。

> **custody のコントローラは同じ形を構造で避けていた** — `limits` を分岐の**前**に置くので、
> どちらの腕からも落ちない。eark 側は 2 つの腕が本当に違うことを言うので同じ手は使えず、
> `NO PACKAGE WAS PRODUCED` を前置きする形にした。**構造で消せる seam と、
> 言葉で分けるしかない seam がある。**

**12 (外部レビュー)。** 報告モックの README は
**「主張が壊れないための型 (schema に埋め込んだ制約)」**と題した表を持つ。
そのうち 2 行 — `independentOfOperator` は無い / `ATLAS_CATALOG` は caveat が非 null 必須 —
は **`check-mock.py` (この repo 専用) だけが強制**しており、
**`schema.json` は素通しだった**。

**外部のツールがスキーマだけで検証すると、ローカルのチェッカーが禁じている当の主張を
受け入れる。** そしてスキーマは**可搬な側**、つまり他所へ渡る側である。
両方をスキーマに移し、`jsonschema` で拒否されることを確かめた。

> **「schema に埋め込んだ」と題した表は、埋め込まれていることを確かめてから書く。**
> 11 例目と 12 例目に共通するのは、**強制している場所と、強制していると書いた場所が違う**
> ことである。

---

## 23. 13 例目 — 切れ目は「検査が見分けたもの ↔ 器が持てるもの」 (2026-08-28)

**3 状態の事実を boolean 1 個に入れ、その先の散文が、boolean が運べない原因を名指していた。**

`signatureVerified == false` の生産者は**3 つ**あり、`ReceiptSignatureVerifier` は
3 つを別々の `detail` で書き分けている。

| 生産者 | 検査器が言うこと |
|---|---|
| 鍵が無い | 「これはこの配備についての陳述で、署名が悪いという所見ではない」 |
| **鍵が在り、検査が走り、一致しなかった** | **「この受領証はその鍵の持ち主のものではない — あるいは署名後に改変されている」** |
| 署名が読めない / algorithm が無い | 「悪いという所見ではない」 |

ところが `CustodyReceipt.limits()` は、**1 つ目を原因として名指していた**。

> This receipt carries a signature that has NOT been verified — **this product holds no key
> material for the receiving agent** — so it is stored for later checking and adds nothing today.

**3 つのうち最も強い所見が、最も弱いものとして読める。**

### そして見分けた結果は JVM を出ていなかった

`CustodyTransferService` は `Checked` を受け取って **`checked.receipt()` しか使っていない**。
`ran()` / `valid()` / `detail()` は捨てられ、**`Checked.asMap()` は main に呼び出し元が 0**。
落ちた検査の唯一の痕跡は WARN 1 行だった。

しかも `ReceiptSignatureIsCheckedOnArrivalTest` は
**別の鍵で署名した受領証が `RECEIPT_VERIFIED` に達すること**を既に固定している
(受入自体は設計どおり — 署名は必須ではない)。つまり
**「配備した鍵の持ち主のものでないと分かっている受領証」を受理し、
運用者には「鍵が無かった」と伝えていた。**

`Outcome` に `signatureCheck` を足し、**分岐の前**で応答に載せる
(片方の腕にだけ置くのが、そもそもこうなった経路である)。
`limits()` は原因を名指さず、**「この受領証は 3 つを区別しない。応答の `signatureCheck` を見よ」**
と言う。

### 台帳は直していない — 桁が足りないまま

`receiptDigest` は `hasSignature` と `signatureVerified` の **2 bit** を畳み込む。
**3 つの事実に 2 bit** なので、**検査して落ちた受領証と、誰も検査していない受領証が
同一の digest になる**。追記専用の entry の中で、永久に。

入力を足すと**既存の全 entry の digest が変わる** — 言い換えではなく移行なので、
ここには入れず候補として記録する。その javadoc は
「信用しただけの受領証と検証済みの受領証は別の事実で、両者が同じ digest になる entry は
肝心なときに区別を失う」と書いている — **3 つ目の事実について、いままさにそうなっている。**

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| `limits()` が再び「鍵が無い」と名指す | `anotherPartysSignatureIsNotVerified` |
| `signatureCheck` を応答から落とす | 同上 |

### 14〜16 例目 — 同じ巡で出た 3 つ、いずれも「片方だけ教わった」形

**14. 拒否の上に `200 / status:"success"`。** `checkpoint-and-anchor` は
`status: "success"` を**何かを試みる前**に書き、その後は書き換えない。
`AnchorService.anchor()` は**投げずに、返す `Outcome` の中で**拒否を報告する。
`AnchorService.store` は `@Autowired` の無い素の setter なので、
**bean を配線し損ねると全呼び出しが拒否され、全呼び出しが 200 success を返す。**

このメソッド自身のコメントが **「外側の status が内側の error の上で success と言っていた…
運用者は封が起きていないのに 200 を見た」と、この欠陥を直したと書いている**。
直っていたのは `closeCheckpoint` の**返り値 map** で、**16 行下の `anchor()` の返り値**は
直っていなかった。**同じ規約 (失敗は返り値に入る) の生産者が 1 つのメソッドに 2 つ在り、
消費者は片方だけを教わっていた。** 隣の endpoint は最初から写像している。

**15. 段の選択が、fallback の腕に効いていなかった。** 最新 checkpoint の受領証には
「最強の段を採る」を適用したが、**古い checkpoint を見に行く側は `toSequence` だけで選ぶ**ので、
同じ checkpoint に 2 段が確定していると**先に出た方**が勝つ — 規則が防ごうとした
`ATLAS_CATALOG` の名指しが、そのまま起きる。同じ関数を両腕に通した。

**16. 重複した transferId が「存在しない。何も送られていない」と答えていた。**
`transferId` は呼び出し元が決めるので**再 POST は普通**で、store 側は
「already stored under this id」と**ログには書き分けている**。応答は
「the transfer was not written, so **it does not exist. Nothing was sent and nothing is in
flight.**」— 既に在るから書けなかったのに「存在しない」と言い、
さらに**このノードには言えない世界についての断定**を足していた。

**同じクラスの 8 メソッド先に、慎重な版がある** — `notFound()` は
「これは handover が起きなかったという陳述ではない。**ここに何が保存されているか**についての
陳述である」と書いている。

### 負のコントロール

| 壊した箇所 | 落ちたテスト |
|---|---|
| 拒否を外側の status に写さない | `aRefusedAnchorIsNotSuccess` |
| `limits()` が再び「鍵が無い」と名指す | `anotherPartysSignatureIsNotVerified` |
| `signatureCheck` を応答から落とす | 同上 |

> **旧文言を固定していた錠がまた 1 本出た** — `anUnverifiedSignatureIsNotVerified` が
> `contains("has NOT been verified")` を要求しており、それは
> 「鍵が無い」と続く旧文でも真だった。**意味を要求し、名指してはならない原因を禁じる**形に直した。

---

