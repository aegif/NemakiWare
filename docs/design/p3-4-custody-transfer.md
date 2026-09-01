# P3-4 — 保存システムへの移管 (custody transfer)

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

## 24. 6 巡目 — 「訊けなかった」を潰していた場所が、**store 側に 5 か所**残っていた (2026-08-28)

5 巡目までは**応答と散文**を直していた。6 巡目は Codex とサブエージェントを同時に回し、
**同じ形が 1 層下 (CouchDB view を読む所) に残っている**ことが出た。

### 17 例目 — `upgradePending` に失敗の返し口が無かった

`anchor()` と `retryUnsettled()` は拒否を `Outcome` に載せて返し、endpoint が写像する。
`upgradePending` だけ**素の `List` を返して**おり、

- 「receipt store が配線されていない」
- 「訊いた。何も確定していない」

が**同じ空リスト**だった。endpoint は `status: "success"` / 200 /
**「nothing had settled yet … not a failure — do not re-anchor」**。
つまり**訊けていない配備に対して、恒久的にアンカーを上げるなと助言していた**。

`AnchorReceiptStore.isActive()` の javadoc は
「呼び出し側は、訊けなかった store から『pending は無い』を読んではならない」と
**契約として書いてある**。`/status` も `LongTermValidityService` も
`EvidenceRecordService` も参照している。**このクラスだけが参照していなかった。**

`record Upgraded(List<AnchorReceipt> upgraded, String unavailable)` にして、
`unavailable != null` を 503 + `status: "unavailable"` に写した。

### 18 例目 — 出荷される package が、失敗した inclusion proof の上で `success` と言っていた

`evidencePackage` は `inclusionProof` の拒否を**入れ子キーに畳み込み**、外側の
`status` を触らなかった。この package は `nemaki-evidence.json` として
**SIP の中に書き出され、受け入れ側へ渡る** — 応答と違い、後から訂正できない。

錠も逆を固定していた: `theCaptureIsFoundThroughItsIntent` が
`status:"success"` を要求しており、**証明が作れない fixture の上でそれを要求していた**。

### 19〜23 例目 — **view が答えなかった** を「空だ」と読んでいた 5 か所

| 場所 | 潰していたもの | 下流が言うこと |
|---|---|---|
| `CouchEvidenceLedgerStore.highestSequence` | 行が読めない → `-1` | `unanchoredEntries: 0` (「露出なし」)。`append` は「前の hash が無い」と読み**別の鎖を 0 から始める** |
| `CouchEvidenceLedgerStore.firstCheckpoint` | 答えない / doc が読めない → `null` | `closeCheckpoint` が `from = 0` で**封印済みの範囲をもう一度封印**し、TSA token をもう 1 枚買う |
| `CouchEvidenceLedgerStore.findBySubject` / `range` | 答えない → `[]` | 真正性報告の `ABSENT`、**SIP に書き出される**「no capture entry was found」、custody の重複判定 |
| `CouchAnchorReceiptStore.rows` | 答えない → `[]` かつ `unreadableCount` は 0 | 17 例目で足した拒否機構が**そのまま素通り**する |
| `CouchLineageJournalStore.queryRawView` | `rows == null` → `List.of()` | **3 行上の `result == null` は 5 行のコメント付きで throw している** |
| `CouchCustodyTransferStore.findByObject` | 答えない → `[]` かつ counter 据え置き | `complete: true, transfers: []` = 「この記録はどこにも送られていない」 |

**`result == null` は直っていて `result.getRows() == null` は直っていない**、が 4 か所。
既存の錠は「null の ViewResult」と「空の行リスト」の**両方**を測っていて、
**その間の 3 つ目の答え**を誰も駆動していなかった (Codex の指摘)。

### 24 例目 — 在りもしないフォルダが `COMPLETE` で証拠鎖に入っていた

`/fixity/scan/folder` は `getChildren` の `[]` を検査対象 0 件として扱う。
**存在しない folderId (打ち間違い・文書 ID・削除済み) でも `[]`** なので、
`verdict: COMPLETE` / `scanned: 0` / `mismatch: 0` が出て、
そのまま **append-only の証拠鎖に `folder-children:{id}` として書かれていた**。
訂正できない場所に「誰も見ていないフォルダは綺麗だった」が永久に残る。

`getContent` で存在と種別を先に確かめ、404 / 400 で拒否する。
**この class の 5 テストは、フォルダが在るとは一度も言っていなかった。**

### 25 例目 — 「個人データ」の主張の 4 度目の出口

ヘッダ名 2 つと `withholdingPersonalData()` を直したコメントの**2 行上**に、
class javadoc と `@param` と `bag()` のコメントが残っていた。
`includeInternalOnly` が**メタデータプロパティしか選ばない** (本文は常に入る) ことは
既に 3 回直している。

錠は **`JavaSource.withoutComments` で code 側の名前を禁じ**、
**散文側は「payload はどちらでも入る」と言っているかを要求**する 2 本立てにした。
禁止だけでは言い換えで抜けられ、要求だけでは半分直った文を固定する。
**旧文は両方とも「properties」の語を含んでいた**ので、
「properties と書いてあるか」を要求する錠では通ってしまう — 要求は
**読みを決める事実の側**に置いた。

### 負のコントロール (この巡)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| AU | `upgradePending` の store 未配線ガードを外す | `anUnaskableStoreIsNotNothingSettled` |
| AV | package の status を proof に従わせない | `theCaptureIsFoundThroughItsIntent` |
| AW | 答えて空の rendition で key を出さない | `anEmptyRenditionListIsNotCalledUnreadable` |
| AX | `highestSequence` を `-1` に戻す | `anUnansweredViewIsNotAnEmptyChain` |
| AY | anchor receipt の非応答を数えない | `aViewThatDidNotAnswerIsNotAnEmptyStore` |
| AZ | `firstCheckpoint` を `null` に戻す | `aFailedCheckpointReadIsNotAnUnsealedChain` |
| BA | フォルダ存在確認を外す | `anAbsentFolderIsNotACleanScan` |
| BB | 個人データの javadoc を旧文に戻す | `theSupportableMeaningIsRequired` |
| BC | `findBySubject` / `range` を `[]` に戻す | `noReadTurnsAFailedViewIntoAnEmptyChain` |
| BD | lineage の `rows == null` を `List.of()` に戻す | `aResultWithNullRowsIsNotEmpty` |
| BE | custody の非応答で counter を据え置く | `anUnansweredViewIsNotACompleteEmptyHistory` |

> **BB は片側しか発火しない。** 禁止側 (`theRetractedClaimIsNotWritable`) は
> コメントを剥がしてから探すので、javadoc を旧文に戻しても鳴らない。
> 鳴ったのは要求側だけで、これは設計どおり (禁止側は名前と code を守る) だが、
> **1 本の錠で 2 つを守っているつもりでいると読み違える**ので書いておく。

### この巡で**直していない**もの (レビュー指摘のうち)

- `/checkpoint-and-anchor` の 3 つ目の出口が、**たった今封印したのに**
  「no checkpoint exists for this repository」と言う (指摘 P2-11)
- `/long-term-validity` の成功ボディにだけ `status` が無い (P2-10)
- `receiptsTruncated` が**読んだ行数ではなく生成した need の数**と上限を比べており、
  ストア未配線の 4 分岐でも `false` になる。注記の「there are more」も `>=` からは出ない (P2-12)
- `AnchorController` / `EarkSipExportController` の `requireAdmin()` / `unavailable()` が
  `limits` を落とす。`/checkpoint-and-anchor` と `/retry-unsettled` は全出口に無い (P2-5〜P2-7)
- `/status` の 200 件走査上限が応答のどこにも出ない (P2-8)
- javadoc ブロックが 2 連続で、前のブロックがどの宣言にも付いていない 3 か所 (P3-14)
- `open()` は package を作らないのに `PACKAGE_CREATED` の履歴に
  「a package was built」と書く (Codex #5)
- request body から来た語を javadoc が「the receiver's own word」と呼び、
  テストがその帰属を要求している (Codex #2) — **これは移行を伴う**

---

## 25. 6 巡目の後半 — 応答の「言わない」と、javadoc が届いていなかった 11 か所 (2026-08-28)

### 26〜29 例目 — レビュー指摘のうち、応答の側

**26. 「たった今封印した」直後に「checkpoint は存在しない」。**
`/checkpoint-and-anchor` は error / noop を先に return しているので、
この行に来た時点で `closed.status == "success"` = **数秒前に封印されている**。
読み戻せなかっただけなのに「no checkpoint exists for this repository」と言い、
さらに「This is NOT a statement that anchoring failed」を足していた。
**`/retry-unsettled` を呼ぶべき唯一の状態が、呼ぶ必要なしと表示されていた。**
500 + 「封印は失われていない。再封印ではなく retry を」に変えた。

**27. `/long-term-validity` の成功ボディにだけ `status` が無い。**
同じ endpoint の 3 つのエラー腕にも、同じ controller の他 4 endpoint にもある。
`status` で分岐するクライアントは**成功時だけキーが消える**。

**28. `receiptsTruncated` が「読んだ行」ではなく「作った need」を数えていた。**
`>=` を比べる相手が `anchors.size()`。ずれる経路が 3 つあり、いちばん悪いのは
**4 つの「訊けなかった」腕がどれも need を 1 個返す**こと — 何も読んでいない配備が
`receiptsTruncated: false` (=「全部見た」) と答える。
注記の「there are more」も `>=` からは出ない (ちょうど上限で以降が無い場合は偽)。
**同じ変更セットの `FixityScanReport.findingsTruncated` は正しく書けている。**
`AnchorNeeds(needs, rowsRead)` に分け、訊けていないときは `null` + 理由。

**29. 復号できなかった receipt 行が `/status` から黙って消えていた。**
store は落とした行を数えており (`unreadableCount`)、`AnchorService` の 2 つの動詞は
それを見る。**運用者が実際に読む `/status` だけが見ていなかった。**
同じメソッドの 16 行上に「『訊けなかった』を『露出なし』と読ませない」と書いてある。

**30. `limits` が共有ヘルパから落ちていた (3 controller とも)。**
各 endpoint は分岐前に `limits` を置くのに、`requireAdmin()` / `unavailable()` は
**その前置きの内側から return する**ので、約束を迂回する出口が共有ヘルパだった。
`/checkpoint-and-anchor` と `/retry-unsettled` は全出口に無かった。
テストは**3 controller × 全 mapped endpoint を回す 1 本**にした
(1 つ名指しの錠は、共有行を戻しても名指した方だけ赤くなって通る)。

**31. 200 件の走査上限が応答のどこにも出ていなかった。**
`unanchoredEntries` は昇順走査の**見つかった中で最遠**から測るので、
200 を超える配備では**恒久的に過大**になり、しかも増え続ける。安全側だが、
運用者は「アンカーが効いていない」と読む。上限と向きを応答に載せた。

### 32 例目 — **javadoc が 11 か所どこにも付いていなかった**

`*/` の次の行が `/**` = 前のブロックは**どの宣言にも届かない**。生成 doc から消え、
書かれた対象は説明を失う。飲み込まれていたものが軽くない:

- **`AuthenticityReport.REPORT_LIMITS`** — 判断の前に読ませるための段落そのもの
- `strongestConfirmed` の「なぜ newest ではなく strongest か」(`ATLAS_CATALOG` の事故)
- `DUPLICATION_DISCLOSURE` の「なぜ format を名指さないか」
- `CustodyLedgerRecorder.alreadyRecorded` の「読めなかったら false にする理由」
- `EvidenceLedgerService.inclusionProof` / `AnchorService.retryUnsettled` / `duplicationDigest`

**いずれも「これは何を establish しないか」を書いた文**で、いちばん消えてはいけない側。
レビューで見つからないのは、ソースを上から読む限り正しく見えるため。
隣接そのものを探す錠を足した (`NoJavadocIsOrphanedTest`)。

**2 か所は直していない**: `SearchIndexObservabilityController` の孤児は
**もう存在しない `/traversals`** を説明しており、付け直す先が無い。
`CaptureIntentController` も範囲外。**名前で除外**して、
パッケージを足したときに黙って飛ばされないようにした。

### 負のコントロール (後半)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| BF | 共有 `requireAdmin()` から `limits` を外す | `everyRefusalSaysWhatItDoesNotEstablish` |
| BG | `receiptsTruncated` を need 数に戻す | `truncationIsNotAnsweredByAStoreThatWasNeverAsked` |
| BH | 落とした receipt 行を `/status` に出さない | `droppedReceiptRowsAreDisclosed` |
| BI | javadoc を再び孤児にする | `everyJavadocBlockReachesADeclaration` |

> **BG は 1 度目に発火しなかった。** 直したときに錠を書いておらず、
> 「守っているつもりで何も測っていない」状態を自分で作っていた。
> 書いてから測り直して発火を確認した。**発火しなかったことの方が発見**である。

### 33〜35 例目 — 同じ switch の**最初の 2 腕**、と「弱すぎる訂正」

**33. `PACKAGE_CREATED` と `SENT` が、4 腕の訂正から取り残されていた。**
4 巡目に `RECEIVED` / `VALIDATED` / `INGEST_ACCEPTED` / `AIP_CREATED` を
「SOMEBODY RECORDED」に直したが、**同じ switch の前 2 腕**は
「A package exists here」「The package was handed over」のままだった。
transfer は**呼び出し元が渡した digest で開かれる**だけで、package を作りも読みもしない。

錠は列挙を**計算に変えた** — `sequence()` から `RECEIPT_VERIFIED` の手前まで。
列挙した錠は「4 腕を直した」ことしか固定できず、その前に 2 腕あることを見ていなかった。

**34. 永続履歴に「a package was built for this record」。**
応答は訂正できるが**履歴は追記済みで残る**。しかも `CustodyState` とは別ファイルなので、
switch を grep しても出てこない。

**35. `CUSTODY_LIMITS` が、同じ文の 3 語先で自分の検査を否定していた。**
「whether that report was about the package we sent」と書いた直後に
「**do not check any claim in a receipt**」。`verifyReceipt` は digest・必須欄・
outcome の写像を実際に検査する。**弱すぎる訂正も、製品の説明としては誤り**で、
これを信じた読者は `RECEIPT_VERIFIED` を「何も意味しない」と読む。

> 直しの 1 稿目は、取り下げた語句を**運用者が読む文字列の中に引用**していた
> (「かつてはこう書いてあった」)。**応答は変更履歴の置き場ではない**し、
> 禁止語句を自分の retraction の中に引用するのは
> この製品が 2 度出荷した罠でもある。注記はコメントへ移した。

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| BJ | `PACKAGE_CREATED`/`SENT` と履歴を旧文に戻す | `everyStateCarriesItsLimits` / `theOpeningStepDoesNotClaimAPackageWasBuilt` |
| BK | `CUSTODY_LIMITS` を「検査しない」に戻す | `theEndpointLimitsSayWhoseStatementAReceiptIs` |

**36. `/eark/status` の `available` が bean 1 個の有無だった。** `export` は
content service 未配線でも拒否するので、**`available: true` のまま全 export が失敗する**
ノードが在りうる。javadoc を「exporter が配線されているか」に直し、
応答に `availableMeans` を足した。

**37. `/eark/export` と `/eark/bag` が作業ディレクトリを毎回残していた。**
`Files.createTempDirectory` を呼ぶだけで削除がどこにも無い。
**拒否 (409) は「不完全なものは出荷しない」という設計どおりの出口**で、
運用者が最も繰り返し叩く経路 — つまり**いちばん漏らす経路が拒否だった**。
bag 側は SIP と bag の 2 段を作るので残る量も多い。

**成功経路は直していない。** 応答が返ったあとに Spring がファイルを流すので、
削除するにはファイル背後の応答をやめる (stream close で消す) 必要があり、
**ダウンロード経路の変更をこの巡で測れない**。塞いだのは拒否と例外の側だけで、
テストもそこしか測っていない — 測っていないものを覆うテストは書かない。

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| BL | 拒否経路の `deleteWorkDir` を外す | `aRefusedExportCleansUpAfterItself` |

---

## 26. 7 巡目 — **今回の訂正そのもの**を測った (2026-08-28)

「訂正が間違っている」がこの製品で最も再現する所見なので、**直したものだけ**を
レビューに掛けた。**1 層上で握り潰されている経路が 1 本**出た。

### 38 例目 (P1) — 訂正が届く先で `catch (RuntimeException) → false` にされていた

`CouchEvidenceLedgerStore.findBySubject` を throw に変えたとき、
**そのコメント自身が消費者を 3 つ名指ししている** —
「the custody duplicate check reads it as *not already recorded* and appends again」。
その 3 つ目、`CustodyLedgerRecorder.alreadyRecorded` は

```java
} catch (RuntimeException e) {
    logger.debug("Could not check whether the handover of {} is already chained: {}", ...);
}
return false;
```

**投げた例外がここで `false` に戻る。** しかも `logger.debug` なので既定では何も残らない。
[[fail-open-boundary-trap]] の形そのもので、**外側 (store) に足した錠が
内側の catch に届いていなかった**。

`false` を返すこと自体は設計判断 (javadoc に理由がある — 重複は運用者に見えるが、
記録されない handover は見えない) なので**挙動は変えない**。変えたのは
**沈黙をやめた**こと: WARN で「重複検査は走っていない。次の append は
2 本目の `CUSTODY_RECEIPT` を防げない」と言う。

### 39〜41 例目 — 同じファイルの隣のメソッドが、直した条件をまだ潰していた

| 場所 | 潰していたもの | 消費者の結論 |
|---|---|---|
| `CouchLineageJournalStore.countRawView` | `getRows() == null` → `0` | 「未解決 0 件」。**`queryRawView` の 80 行上で今回直した条件と同一** |
| `CouchLineageJournalStore.reduceCount` | `result == null` / `rows == null` → `0L` | **`null` (答えられない) という値を既に持っている**のに、正確な 0 を返していた |
| `EvidenceRecordService` | `unreadableCount()` を読まない | 「この checkpoint に RFC 3161 token は無い」。**この文字列は `nemaki-evidence.json` として SIP に入り組織外へ出る** |

`reduceCount` は「答えられない = `null` で scan にフォールバック」を**自分で設計してある**のに、
3 条件のうち 2 つがその値を使わず 0 を返していた。**正しい語彙が在って使われていない**形。

### この巡で確認できた「clean」

サブエージェントが全呼び出し経路を追い、**例外が空リストに戻る箇所は上記 1 本だけ**、
他 15 経路は 500 か honest な UNAVAILABLE に写像されることを確認した
(`AuthenticityReportAssembler` / `AnchorService` / `AnchorController` /
`EvidenceRecordService` / `EarkSipExporter` / `CaptureIntentController`)。
起動を止める新経路は無い (`CouchEvidenceLedgerStore` は lazy provisioning)。

### 直していない (記録のみ)

- `EvidenceLedgerService.append` の分類が `REFUSED` になる (意味は `UNAVAILABLE`)。
  人が読む `reason` 文字列は正しく、消費者は `recorded()` しか見ない
- `AnchorController.checkpointAndAnchor` の `latestCheckpoint` が try の外なので、
  読めないときは 500 になり「再 seal するな、`/retry-unsettled` を使え」の指示が消える
- `CouchLineageJournalStore` の残り 8 か所 (`findAll` 系・stats・`countNonTerminalByTarget`・
  `eventKeyExists`・`ensureClientForRead`)

  > **「3.4 の範囲外」と最初に書いたのは誤り。** 同じファイルの 3 メソッド
  > (`queryRawView` / `countRawView` / `reduceCount`) は直しているので、
  > ファイル単位の線ではない。実際に引いた線は**消費者を辿ったかどうか**で、
  > 辿らなかった理由は**時間**である。機能の範囲という言い方はそれを言い換えたもので、
  > 判断の記録としては誤り — 台帳に書く理由は、判断を後から検算できる形でなければならない。
  > (2026-08-28 訂正。§27 で 2 件を処理)

### 負のコントロール (7 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| BM | 重複検査の諦めを再び `logger.debug` に | `aDuplicateCheckThatCouldNotRunSaysSo` |
| BN | `countRawView` / `reduceCount` を 0 に戻す | `theCountingReadsRefuseAnUnansweredView` |
| BO | `unreadableCount` を読まずに「token 無し」と言う | `droppedReceiptRowsAreNotAnAbsentToken` |

> **BN は 1 度目、細工のスクリプトが例外で終わっていた** — ファイルは無傷のまま
> maven が走り、「落ちたテスト無し」と出た。**発火しなかったのではなく、
> 壊れていなかった**。細工が当たったことを確かめずにコントロールの結果を読むと、
> 守れていない錠を守れていると記録する。[[negative-control-self-deception]] の
> 「赤くなったかどうかだけ見るな」の裏側で、**緑だったときこそ細工を疑う**。

---

## 27. 8 巡目 — 「3 つ目の答え」は、直した出口の**隣**に残っていた (2026-08-28)

並行レビューが、6・7 巡の 11 か所を現行コードで確認したうえで
**同じ巡で名指した「3 つ目の答え」そのものが残っている**と指摘した。

### 42 例目 (P1) — `getChildren` の 3 つ目

`ContentDaoServiceImpl.getChildren` は `result == null` を throw する。
**`result.getRows() == null` は if を外れて空リストで返る** — 例外ではないので
下の fail-fast の catch にも入らない。そのすぐ上のコメントは
「an EMPTY folder is a result with no rows」と書いているが、それは
**空のリスト**の話で、**null のリスト**の話ではない。

`/fixity/scan/folder` はフォルダ存在確認 (24 例目) のあと、この戻りを検査対象にする。
**フォルダは在るのに列挙が答えなかったとき**、`verdict: COMPLETE / scanned: 0 / mismatch: 0`
が追記専用の鎖に入る。24 例目で塞いだのは「ID が無い」腕だった。

`getChildrenPaged` は **`result == null` も**まだ空ページだった (両方の置換が残っていた)。

### 43 例目 — 読めなかった行は、まだ黙って落ちていた

`getChildren` / `getChildrenPaged` / `CouchEvidenceLedgerStore.findBySubject` / `range` は
**decode できない行を飛ばす**。全行読めなければ消費者が受け取るのは
「このフォルダは空」「この subject は 0 件」。**view が答えなかった場合と同じ値**で、
throw を足した意味がそこで消える。

`highestSequence` は**読めないキーを throw している**ので、
**同じ store の中で方針が割れていた**。list read で 1 行のために全部を落とすのは違うので、
兄弟 store と同じ **counter** にした (`unreadableCount` / `lastUnreadableChildCount`)。
読む側は 3 つ:

| 消費者 | 直した内容 |
|---|---|
| `FixityController` | `status: "partial"` + `scopeLimits` に件数。**鎖の scope も** `folder-children-partial:{id}:unread=N` |
| `EarkSipExporter` | 「no ledger entry names this object」の代わりに `undecodableEntries` と「NOT a statement」 |
| `CustodyLedgerRecorder` | 「不完全な一覧に対して重複検査した」と WARN |

**鎖の scope を一緒に直したのは 24 例目の教訓**。応答だけ直して鎖に旧 scope が残ると、
訂正できない側に過大主張が固定される。

### 44 例目 — `verifyReceipt` の 409、5 腕のうち 2 腕

「nothing here knows what **the receiving system** found」
「a receipt that says **the receiving system** did not accept」。
outcome は REST の body から来る。**同じ応答に載る `CUSTODY_LIMITS` が
「署名を検証していなければ誰が書いたかは分からない」と言っている**ので、
撤回した主張とその撤回を同時に手渡していた。13 例目からの同じ主張の、これで 13・14 番目の出口。

錠は 5 腕を回す形にした。**既存の 2 本が `contains("did not accept")` を要求しており、
それは帰属ごと固定していた** — 直すとその 2 本が落ちた。意味 (`is a reason to stop`) に張り替えた。

> 張り替えの 1 稿目も間違えた。`contains("turn a package down")` にしたら、
> **leftover の腕が「no receiver uses this word to turn a package down」と否定形で
> 同じ語を含む**ので分類が壊れた。**文とその否定の両方に出る部分文字列は何も分類しない。**

### 45・46 例目 — lineage journal の 2 件 (利用者の承認を得て着手)

**`eventKeyExists` → `append` の冪等性。** 読めなかったら `false` = 「まだ無い」で、
**追記専用の journal に同じ eventKey の行が 2 本入る**。custody の重複と同型。
ここは **throw にした** — `JournaledLineageEmitter` は fail-open だが
**dead-letter sink を持つ**ので、例外は「失われる」ではなく「ファイルに残る」。
**追記専用の重複は消せないが、dead-letter は再生できる。**
(custody 側に dead-letter は無いので、あちらは fail-open のまま WARN だけ足した。
同じ形でも下地が違えば答えが違う。)

**`ensureClientForRead` の `catch → false`。** 「DB が無い」と「到達できない」が同じ値で、
**18 の read メソッドが一斉に空 / 0 / null** を返していた。三分岐 (`READY` / `ABSENT` /
`UNREACHABLE`) にし、**read は `UNREACHABLE` で refuse、`isActive()` は今までどおり false**。

> **`isActive()` を投げるようにしてはいけない。** projection loop と purge scheduler が
> 定期実行から呼んでおり、`scheduleAtFixedRate` のタスクから例外が出ると
> **以後の実行が全部キャンセルされる**。一過性の障害で projection が永久に止まる。
> 「訊けなかった」を正直に出す先は**読み取りの答え**であって、スケジューラの可否ではない。

### 負のコントロール (8 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| BP | `getRows() == null` を空フォルダに戻す | `aResultWithNullRowsIsNotAnEmptyFolder` |
| BQ | decode できない子を数えない | `anUndecodableChildIsCounted` |
| BR | scan が `lastUnreadableChildCount` を読まない | `unreadableChildrenAreNotACleanPass` |
| BS | 台帳の decode 失敗を数えない | `anUndecodableRowIsCounted` |
| BT | SIP が「never chained」と出荷する | `undecodableLedgerRowsAreNotAnUnchainedRecord` |
| BU | 409 に受け手の名を戻す | `noRefusalAttributesTheWordToTheReceiver` ほか 2 本 |
| BV | 到達不能を「event 無し」に戻す | `anUnreachableJournalIsNotAnEmptyOne` |
| BW | `eventKeyExists` を `false` に戻す | `anUncheckableEventKeyIsNotAnAbsentOne` |

### まだ残しているもの

- `CouchLineageJournalStore` の `findAll` 系 / stats / `countNonTerminalByTarget` /
  projector の巡回対象 (5 か所)。**`requireClientForRead` の三分岐で
  「到達できない」経路は塞がった**ので、残るのは
  **view が答えたが行が読めない**場合の一覧・統計。消費者は「一覧が空に見える」で、
  鎖にも出荷物にも入らない
- `/export` 成功経路の作業ディレクトリ、台帳 digest の 2 bit (移行)、
  `SearchIndexObservabilityController` の孤児 javadoc (付け直す先が無い)

### 47 例目 — **今回書いた錠が、今回の自分の編集を捕まえた**

`EvidenceLedgerService.lastUnreadableCount()` の javadoc を
`entriesFor` の javadoc の**直後**に挿し込み、`entriesFor` の説明を孤児にした。
32 例目で足した `NoJavadocIsOrphanedTest` がフルスイートで落ちて分かった。

**孤児 javadoc は「昔の誰か」がやったことではなく、いま普通に混入する。**
新しい javadoc を既存の宣言の前に足すとき、その位置に既に別のブロックが
付いているかどうかは、ソースを上から読む限り見えない。

---

## 28. 9 巡目 — 直した先が**本番で読まれない層**にあった (2026-08-28)

並行レビューが「couch / 単体の層では閉じている。壊れているのは
**直した先が本番で読まれない経路**と、**錠が取りこぼす帰属**」と指摘した。両方当たっていた。

### 48 例目 (P1) — `lastUnreadableChildCount` は本番で**常に 0**

`daoContext.xml` が `contentDaoService` に束ねているのは **cached の decorator** で、
couch 実装はその下に注入される。**decorator は `lastUnreadableChildCount()` を
実装していなかった**ので、interface の default 0 が勝つ。

つまり **43 例目の修正は全配備で死んでいた。**
それでも緑だったのは、store のテストが couch クラスを直接叩き、
controller のテストが `ContentService` を stub していたから — **どちらも
コンテナが実際に組む object を通っていない。**

さらに悪いことに、**tree cache が有効なとき decorator は store に行かない**。
tree の子 id を回して `getContent == null` を黙って飛ばす。
**同じ置換が、store の counter からは見えないループの中で起きている。**

decorator 自身に ThreadLocal を持たせ、委譲側は下位の値を、
tree cache 側は**自分のループで数えた値**を返すようにした。

> **層をまたぐ修正は、コンテナが組む object で測る。** 実装クラスを直接 new した
> テストと、その上のサービスを stub したテストが両方緑でも、
> **その 2 つの間に decorator が居れば何も測っていない。**

### 49 例目 (P1) — 錠が禁じていたのは「主張」ではなく「その 2 つの文字列」

44 例目で `verifyReceipt` の 2 腕から帰属を外し、
`contains("the receiving system ")` (**末尾スペース付き**) で禁じた。
これは

- leftover の **`the receiving system's` own documentation** に当たらない
- UNRECOGNISED の **`this receiving system` was never measured** に当たらない

**5 腕のうち 4 腕が帰属を持っていて、2 腕しか直っていなかった。**
禁止は `receiving system` (冠詞なし・スペースなし・小文字化) に張り替えた。

**さらに、錠が回していた 5 語は 5 腕ではなかった。**
`SOMETHING_NOBODY_USES` と `RUNNING` は**どちらも leftover** で、
`UNRECOGNISED` (＝**この製品が成功と認める語**を、受け手が測られていない場合。
RODA と `PASSED` など) は**一度も走っていなかった** —
コメントには "Every branch is driven" と書いてあった。
語ごとに**到達する腕を assert する**形にした。

> **「腕を回している」は、腕に着いたことの証明ではない。** 入力を N 個並べても
> 写像が N 対 1 なら、覆えていない腕が残る。到達先を assert すれば初めて数が合う。

### 50 例目 (P2) — `getChildrenCount` の 3 つ目

`getChildren` の隣。`result == null` も `getRows() == null` も catch に入らず **0**。
さらに**行は在るが値が数として読めない**場合も 0 に落ちていた (4 つ目)。

**count は list より悪い**。数だけを訊く呼び出し元には代替の探り方が無い。
`LineageCatalogReconciliationServiceImpl.childFolders` は 0 なら paged を呼ばずに終わる
ので、**フォルダが在るのに catalog が子 0 件として巡回しない**。

> 既存の錠 `countingChildrenFailsFast` は**投げる腕だけ**を測っていた。
> **BZ は 1 度目に発火しなかった** — 錠が無かった。書いてから測り直した。

### 51 例目 (P2) — 出荷物の「1 件でも読めたら success」

`EarkSipExporter` は `undecodableEntries` を**両方のリストが空のときだけ**見ていた。
**1 件デコードできれば `status: "success"`** で、落ちた行の件数は package に出ない。
「全部読めなかった」と「一部読めなかった」は、**そのリストが何を含んでいないか
という同じ事実**である。空リストの腕の隣を塞いだ。

### 負のコントロール (9 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| BX | decorator の `lastUnreadableChildCount` を消す | `theDelegatingBranchCarriesTheCount` / `theTreeCacheBranchCountsItsOwnDrops` |
| BY | tree cache 側で落とした子を数えない | `theTreeCacheBranchCountsItsOwnDrops` |
| BZ | `getChildrenCount` を 0 に戻す (2 箇所) | `theChildCountRefusesAnUnansweredView` |
| CA | 帰属を旧文に戻す (leftover / UNRECOGNISED) | `noRefusalAttributesTheWordToTheReceiver` / `theWholeRecordedVocabularyIsClassified` / `anUnrecognisedWordIsNotCalledARejection` |
| CB | 出荷物の gap 開示を空の腕だけに戻す | `droppedRowsAreDisclosedBesideTheEntriesThatWereRead` |

> **BZ は 1 度目に発火しなかった** — `getChildrenCount` の錠を書いていなかった。
> 書いてから測り直した。
>
> **CA と CB は、この表に書いた時点ではまだ測っていなかった。**
> 書いた直後にフルスイートを回して満足しかけ、表を読み返して気づいた。
> **コントロールの表は「測った記録」であって「測る予定」ではない。**
> 予定を記録の形で書くと、次に読む人 (と自分) が測ったものとして扱う。

### 補足 — この巡のフルスイートが 2 回落ちた理由

`CompiledClassesAreUsableTest` が `DataUtil.class` を「未解決のコンパイル問題を持つ」
として落とした。**差分外のファイル**で、IDE の language server が
`target/classes` に書き込んだ既知の混入 ([[jdtls-poisons-war-with-error-classes]])。
`mvn clean test` でも再発した — **clean の後、実行中に書き直されていた** (class の
mtime が実行の途中)。当該 class を削って再コンパイルしたら解消。
**退行ではないが、「ビルドが落ちた＝自分の変更が悪い」と読むと時間を失う。**

---

## 29. 10 巡目 — **据え置きにしていた 4 件を全部開けた** (2026-08-28)

### 52 例目 — journal listing の 3 通りの「無い」

`queryRowsFromView` は **(a) view が答えない (b) 行に document が付いてこない
(c) 例外**の 3 つを全部 `List.of()` にしていた。(c) は **ERROR ログを出したうえで**
空リストを返す — **ログは一方を言い、返り値は他方を言い、呼び出し元に届くのは返り値だけ**。

`findAll` / 日付範囲 / projector の `by_target_status` / stale claim の回収が全部ここを通る。
(a)(c) は throw、(b) は counter。

> **スケジューラは大丈夫。** `LineageProjectionLoop` の 3 つの
> `scheduleWithFixedDelay` は本体全体を `try/catch (Exception)` で囲んで log しているので、
> 拒否は 1 tick を飛ばすだけでタスクは死なない。
> **`isActive()` を投げてはいけない**のと結論が違うのは、好みではなくこの差である。

### 53 例目 — `/export` 成功経路の作業ディレクトリ (据え置きを開けた)

拒否経路は 8 巡目に塞いだ。成功経路は **Spring が controller の return 後に body を書く**ので
その場では消せず、「呼び出しより長生き」が静かに「**JVM より長生き**」になっていた。

`FileSystemResource` は `isFile() == true` を返すので、コンテナが
`getInputStream()` を開かない zero-copy 経路を採ることがある — **close に吊るした削除が
走らない**。`InputStreamResource` にして選択肢を奪い、長さは明示した。
テストは**「読む前は在る」「読み切ったら消えている」の両方**を測っている
(前だけ測ると、body を書く前に消す実装が通ってしまう)。

### 54 例目 — 台帳 digest は「2 bit / 3 事実」ではなかった。**入力が常に定数だった**

> **訂正 (レビュー指摘)。** 下の「常に同じ digest になる」は**強すぎる**。
> `hasSignature` の入力は生きているので、**署名の有無が違えば digest は違う**。
> 正確には「**検証の有無だけが違う 2 通の受領証**が同じ digest になる」。
> 失われるのは**検証**であって署名ではない。測ったテストは署名を揃えているので、
> 測ったのはまさにこの範囲だけだった — **測った範囲を超えて書いた**。


据え置きの理由を「入力を足せば直るが移行が要る」と書いていた。**測ったら違った。**

`passCustody` は transfer を**ストアから読み直す**。`decode` は
`signatureVerified` を**意図的に false へ固定する** —
「誰でも編集できる行から読み戻した所見は、所見の名を着た主張である」。
**だから digest を取る時点でこの入力は常に false** で、
**検証済みの受領証と、信用しただけの受領証は同じ digest になる**。常に。

digest の隣のコメントは「両者が同じ digest になる entry は肝心なときに区別を失う」と、
**この製品が持っていない性質**を説明していた。

**入力を足しても直らない** (足すべき事実がその時点に存在しない)。直すなら
**検証時に digest を取る**か、**ストアが拒否している所見を永続化する**かで、
どちらも言い換えではない。ストアの規則は正しいので、**直したのは主張の側**。

> 測ってから書いた (`theVerifiedFindingIsGoneByTheTimeTheDigestIsTaken`)。
> **据え置きの理由も主張である。**「移行が要る」と書いた時点で、
> 移行すれば直ると読める — 読んだ人は次にそれをやる。

### 55 例目 — 除外リストに載せた 1 件に、実は持ち主が居た

`NoJavadocIsOrphanedTest` の `NOT_COVERED` に `CaptureIntentController` を
「範囲外」で入れていたが、孤児の持ち主は **2 宣言先の `/verify-metadata`** だった。
**除外リストは主張が検査を免れる場所**なので、項目ごとに理由を書き、
リストを触るたびに読み直す形にした。

### 56 例目 — **自分の直しが、また 2 つ壊した**

`streaming` の javadoc を `deleteWorkDir` の javadoc の前に挿し、**`deleteWorkDir` を孤児にした**
(47 例目と同じ)。さらに `deleteWorkDir` の javadoc は
「成功経路は既知の leak。今回は測れないので直していない」と書いたままで、
**53 例目で直した直後にそれが偽になった**。

> **注意書きは、それが説明している状態より長生きする。** 直したら、
> 「直していない」と書いた文を探して消す。残った caveat は生きた制限として読まれる。

### 負のコントロール (10 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CC | journal listing を空リストに戻す (2 経路) | `theListingsRefuseAnUnreadView` |
| CD | document の無い行を数えない | `aRowWithoutItsDocumentIsCounted` |
| CE | 成功経路を `FileSystemResource` に戻す | `aSuccessfulExportCleansUpWhenTheBodyIsRead` |
| CF | `signatureVerified` を行から読み戻す | `theVerifiedFindingIsGoneByTheTimeTheDigestIsTaken` |

---

## 30. 11 巡目 — **前提が違っていた。throw では塞げない事故がある** (2026-08-28)

レビューが、6〜10 巡で積み上げた修正の**前提そのもの**を崩した。

### 最重要 — 実際に再現した事故は `null` ではなく「200 + 0 行」だった

`SolrIndexMaintenanceServiceImpl` のコメントが、起きた事故を記録している:

> a CouchDB view whose map function fails answers **HTTP 200 with zero rows**, so there is
> no exception to propagate. That was reproduced: ... totalDocuments=1, indexedCount=1,
> errorCount=0, status=completed

**空のリストは空のフォルダと原理的に区別できない。** 私が入れた throw が捕まえるのは
`result == null` と `getRows() == null` だけで、`CloudantClientWrapper` を読むと
`result == null` が返るのは **`isStartupPhase()` (スレッド名に main/startup/init を含む)
で design doc 未配備のとき**にほぼ限られる。

**つまり今回の一連の throw は、再現済みの事故そのものを塞いでいない。**
塞いでいるのは「起動フェーズで view が無い」窓と、**行は返ったが読めなかった**分である。
実際に効くのは **ENUMERATION_GUARD** — 「索引が既に持っている数」という、
同じ障害では黙らせられない物差し。§29 と `RELEASE_NOTES` はこれを言わずに書いていた。

### 57 例目 (P1) — **私の修正が、既知の重大障害を「確率的」から「確定的」に変えた**

`Patch_SystemFolderSetup` は root を列挙して `.system` の有無を調べ、
**無ければ作る**。列挙が失敗すると `catch → null` = 「無い」だった。

`getChildren` を throw に変えたことで、この経路は**列挙が失敗するたびに throw する**。
このクラス自身のコメントが、前回それが起きたとき CMIS のパス解決が壊れたと記録している。

> **「新規インストールで必ず起きる」と最初に書いたが、それは検算していなかった。**
> レビューは「このパッチが 1 番目、`children` view を作る `Patch_StandardCmisViews` が
> 3 番目だから初回は必ず失敗する」と述べ、私はそのまま書き写した。**確かめたら違う** —
> `DatabasePreInitializer` は `@Order(1)`、パッチを回す `CMISPostInitializer` は `@Order(2)` で、
> **dump (`bedroom_init.dump` / `canopy_init.dump`) は両方とも `children` view を含む**。
> 標準の新規インストールでは view はパッチより先に在る。
>
> 起きるのは「design document を作り直している最中」「dump 経由でない repository」など、
> **列挙が実際に失敗する場合**。頻度は下がるが、**起きたときの結果は同じ**なので修正は変えない。
> 同じ巡でレビューの指摘 1 件を「原典で確かめたら成立しない」と書いておきながら、
> **自分が書き写した方は確かめていなかった。**

「訊けなかった」を throw にするのは正しい。**その throw が届いた先が「無い」と答える
呼び出し元だと、直したはずの欠陥が別の形で確定する。** 修正は消費者まで見て初めて終わる。

> **しかも 1 稿目は効かなかった。** 内側の catch に refuse を足したが、
> **メソッド全体を包む外側の catch が `return null` に戻していた**。
> 負のコントロール CI が発火せず (錠が無かった) → 錠を書く → **今度は落ちない** →
> 外側の catch を見つけた。[[fail-open-boundary-trap]] を今週 3 度踏んでいる。

### 58 例目 (P1) — RAG 全再索引に物差しが無かった

CMIS 側には ENUMERATION_GUARD があり、RAG 側には**無い**。
`clearRAGIndex` → 歩行 → 途中の失敗は**再帰の内側の catch**で握って続行 →
`addError` は上限付きリストに足すだけで **`errorCount` を上げない** →
`status: "completed"` / `errors: 0`。

**CLAUDE.md はこの endpoint を、公開前の必須手順として案内している。**

同じ guard を移植し、`errorCount` を上げ、落とした場合は
`completed_with_errors` にした。

### 59 例目 (P1) — 取込の重複判定が「重複なし」に戻していた

`CanonicalImportServiceImpl.findExistingDocument` が列挙失敗を `catch → null`。
呼び出し元は null を「既存なし」と読んで**新規作成**する。痕跡は `logger.debug` 1 行。
`idempotencyKey` は同一リクエストの再送しか止めない。**refuse に変えた** —
重複の代償は消せない文書、拒否の代償は再試行。

### 60 例目 — tree cache 経路が、**健全なフォルダに `partial` を刻んでいた**

decorator の tree cache 分岐で `getContent == null` を「読めなかった」と数えていたが、
**「tree が古くて子が削除済み」と区別できない**。fixity はこの数を
`status: "partial"` と**追記専用の鎖の scope** に変えるので、
**stale なだけのフォルダに恒久的な不完全の主張**を刻む。
不一致は cache を信じない理由なので、その場合は store から読み直す形にした。
cold cache で store の数を捨てていた件も併せて直し、**テストは cold 経路を一度も
通っていなかった** (fixture が常に tree を stub していた) ので分けた。

### 61〜66 例目 — 11 巡目の残り (Codex 指摘分)

| # | 場所 | 潰していたもの |
|---|---|---|
| 61 | `EvidenceLedgerService.append` | fork 判定が**decode できた行だけ**を数える。良い行 1 本＋読めない行 1 本の tail は「fork なし」に見え、**選んだ覚えのない腕に link する** |
| 62 | `EvidenceLedgerService.closeCheckpoint` | 同上。span から欠けた行があっても端点と件数の照合は通り、**その set を名指す root が封印される**。追記専用 |
| 63 | `AuthenticityReportAssembler` の台帳節 / duplication 節 | 全行読めなければ `ABSENT`、一部なら**リンクの欠けた列で `VERIFIED`** |
| 64 | `EarkSipExporter.captureEntriesFor` | counter は**読みごとにリセット**されるのに、ループの外で 1 度しか読んでいなかった。intent ごとの損失が全部落ち、**SIP に「capture entry は無い」と書かれる** |
| 65 | `queryTargetStatusCount` | 4 通りを 0 に。この数は projector の **backlog 上限**なので、捏造 0 は上限を永久に発火させない。**兄弟の `countNonTerminalByTarget` は既に拒否していた** — 1 クラスで 2 通りの答え |
| 66 | `countByProcessType` / `findByRecordId` | 空 map → `/stats` の `totalEvents: 0`、null → 404 `Event not found`。**journal についての断定**を、このノードの失敗から出していた |

`lastUnreadableRowCount()` は**本番の呼び出し元が 0 件**だった (interface にも無い)。
interface に上げ、listing が `undecodableRows` として出すようにした。
**「数えたが誰も読まない」は、直していないのと同じ。**

### 負のコントロール (11 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CG | RAG の物差しを外す | `anUncountableIndexIsNotAnEmptyOne` / `theGuardComesBeforeTheClear` |
| CH | 飛ばしたフォルダを数えない | `aWalkThatSkippedFoldersIsNotCompleted` |
| CI | `.system` の拒否を外側 catch に飲ませる | `anUnknownRootDoesNotCreateASecondSystemFolder` |
| CJ | tail の読めない行を無視する | `anUndecodableTailRowStopsTheAppend` |
| CK | 台帳節の欠損を無視する | `undecodableLedgerRowsAreNeitherAbsentNorVerified` |
| CL | 3 つの読みを捏造に戻す | `theRemainingReadsRefuseRatherThanFabricate` |

> **CI は 2 度測って初めて発火した。** 1 度目は錠が無く、書いたら今度は落ちない —
> **外側の catch が refuse を `return null` に戻していた**。
> 「発火しない」は錠の問題とは限らず、**直っていないことの合図**でもある。

### レビュー指摘のうち、当たっていなかったもの

`NavigationServiceImpl` の `totalCount == 0` fallback が「この変更で到達不能になった」
という指摘は**成立しない**。`getChildrenCount` の catch は**変更前から rethrow** しており
(差分なし)、しかも**空フォルダでは 0 が正常に返る**ので fallback は生きている。
`canopy_init.dump` の `children` に `reduce` が無いのは事実だが、
これも変更前から同じ振る舞いである。**指摘は原典で確かめる。**

> **孤児 javadoc を、この巡でさらに 2 回作った** (計 4 回)。新しい説明を既存の宣言の
> 前に足すとき、その位置に既にブロックが付いているかはソースを上から読んでも見えない。
> 毎回フルスイートの `NoJavadocIsOrphanedTest` が捕まえている。
> **錠が効いているのと、癖が直っているのは別。** 足すときは宣言の「後ろ」に置く。

---

## 31. 12 巡目 — レビュー 2 巡目。**訂正の訂正**が 12 件 (2026-08-30)

「2 巡して安定」を目標に 2 巡目を回した。出てきた欠陥は**全件が私の訂正の中**にあった。

### 67 例目 (P1) — 真正性報告の窓が、末尾の fork を切り落として `VERIFIED` と答える

`ledgerSection` は `highest-999 .. highest` を **limit 1000** で読む。
**窓の幅と limit が同じ**なので、末尾に fork (同一 sequence に 2 行) があると 1001 行になり、
CouchDB は先頭 1000 行を返し、**余った腕が落ちる**。
`EvidenceChainVerifier` は隣接エントリしか比べないので、残りは切れ目なく見え、
**改竄検知のために読む唯一の節が「検証済み」と答える**。

`EvidenceLedgerService` は `closeCheckpoint` と `inclusionProof` で
**この同じ修正を、理由付きのコメント込みで既に入れていた**。3 本目の腕に届いていなかった。
エントリが 1000 件以上ある稼働台帳は**常時この条件下**にある。

> 錠は**引数**に対して書いた。store を mock している以上、truncation 自体は再現できない
> (切るのは CouchDB) ので、**この製品が制御しているのは limit だけ**である。

### 68 例目 (P1) — 兄弟メソッドだけが行の脱落を数えていた

`queryRowsFromView` を直したとき、**2 つの throw は `queryRawView` へ伝播させたのに、
行単位の脱落は伝播させなかった**。同じクラスの中で、同じ形に 2 通りの答え。
`queryRawView` の消費者は真正性報告の `ABSENT` と、**SIP に書かれる「capture entry は無い」**。

### 69 例目 (P2) — **温まった cache が、他人の数を自分の答えとして返していた**

decorator の tree cache 分岐で `nonCachedContentDaoService.lastUnreadableChildCount()` を
読んでいたが、**cache hit のとき store は呼ばれない**。したがって読んだ値は
**このスレッドで最後に走った別の列挙**のもの — 別フォルダ、別リクエストのこともある。

しかも危険な向きは逆で、**cache に載っている tree は、それを作った過去の読み取りが
落とした行の分だけ短い**。初回だけ損失を報告し、2 回目以降は 0 を返す。
fixity はそれを読んで `success` とし、**鎖に「このフォルダを検査して COMPLETE」と刻む**。

数を知り得ないので、**-1 = unknown** を返す形にした。fixity は
`status: "partial"` と `folder-children-uncounted:{id}` を使う。

> **これは §79 で取り下げた** (2026-08-30)。`-1` は cache hit のたびに出る、つまり
> **ふつうの状態**で発火する — 消費者は非 0 で拒否するので取込が全面停止する。
> 数を tree と一緒にキャッシュする形に置き換え、`folder-children-uncounted` は消した。
> **この段落は誤った解決として残す**: 取り下げた側に印が無いと、次に読む人が採用する。

> **この腕の「コントロール」は空振りだった。** `store.lastUnreadableChildCount()` を
> stub しておらず、**Mockito 既定の 0** が `assertEquals(0, ...)` を満たしていた。
> 7 を stub したら落ちた。**通っていたのは fixture のおかげ**だった。

### 70 例目 (P2) — 呼んでもいない読み取りについて数を報告していた

`ledgerSection` は `highest < 0` のとき `range` を呼ばないが、
そのあと `unreadableCount()` を読んでいた。counter は `findBySubject` / `range` の入口で
リセットされ **`highestSequence` は触らない**ので、
**前のリクエストが同じプールスレッドに残した値**を読む。
エントリが 1 件も無いリポジトリの報告書が `UNAVAILABLE / undecodableEntries: 2` と
**誰も読んでいない 2 行**を名指しする。

対応する control も両方 stub していたので、**定数を返す mock ではこの漏れは原理的に出ない**。

### 71 例目 (P2) — 重複判定の「隣の扉」

`getChildren` の throw は塞いだが、**復号できない子行は throw しない** (数えて短いリストを返す)。
`findExistingDocument` はその数を見ていなかったので、
**重複文書の行が読めないと重複を作る**。塞いだ扉のすぐ隣。

### 72〜74 例目

| # | 内容 |
|---|---|
| 72 | `AppendOutcome.REFUSED` の javadoc が「Nothing was recorded」と断定。**`store.append` の throw も REFUSED になる**ので、応答を失った書き込みは着いているかもしれない。このクラスが他所で禁じている置換 |
| 73 | `contentSection` だけ catch が無く、**添付 1 件が読めないと報告書全体が落ちる**。バイト列と無関係な 8 節も届かない |
| 74 | 孤児 javadoc の検出器が**1 行形式 (`/** ... */`) を見ていなかった**。`endsWith` に直したら 19 件出て、うち複数は私がこの巡で作ったもの。機械的に結合して 69 ブロックを直した |

### 負のコントロール (12 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CM | warm cache が store の数を読む形に戻す | `aFullyReadFolderCountsNone` |
| CN | 窓の limit を幅と同じに戻す | `theLedgerWindowCanCarryAFork` |
| CO | 重複判定が不完全な一覧を無視する | `dedupeRefusesOnAnIncompleteListing` |

> **CN と CO は 1 度目に発火しなかった** — どちらも錠を書いていなかった。
> この巡だけで 2 本。**直した数と錠の数は自動では一致しない。**

### 私が書き写した誤りの訂正

1 巡目のレビューが「パッチ順のせいで初回インストールでは必ず `.system` が 2 つできる」と述べ、
私はそれを**設計文書とコードのコメントとテストに書き写した**。**確かめたら成立しない** —
`DatabasePreInitializer` は `@Order(1)`、パッチ実行は `@Order(2)` で、
**両方の dump に `children` view が入っている**。修正自体は正しいが、頻度の主張は誤り。
3 か所とも直した。

**同じ巡で、別のレビュー指摘は原典に当たって退けている。**
退ける方だけ検算して受け入れる方を検算しないのは、検算ではない。

---

## 32. 13 巡目 — **一括編集をやって、取り消した** (2026-08-30)

3 巡目のレビューは「収束していない」と答えた。出た欠陥のうち最大のものは、
**前の巡で私がやった機械的な一括編集**だった。

### 75 例目 — 69 ブロックの javadoc 結合は、**32 件を別メンバーに付けた**

孤児 javadoc を潰すために、`/**` が連続する箇所をスクリプトで全部結合した。
レビューが全件を実ファイルで数え直した結果:

- **32 件が説明していない宣言に付いた。** SSRF の許可ホスト検証の説明が MIME 推定に、
  CSRF cookie の説明が別メソッドに、存在しない `@param` が付いたフィールドに、
  `boolean` を返すメソッドに「エラーメッセージを返す」`@return` が
- **GPL ヘッダを 5 ファイルで壊した。** `/*****…*****/` を javadoc と誤認して結合し、
  **1 ファイルに GPL 本文が 2 回**並んだ
- **23 ファイルが CRLF→LF に変換され**、差分の半分が行末だけの変更になった
  (`--ignore-cr-at-eol` で 6042 行 → 3075 行)

**全部取り消した。** コメントと改行しか変わっていない 61 ファイルは `git checkout`、
意図した変更を含む 10 ファイルは HEAD の元ブロックから逆算して 14 ブロックを復元。
残った 6 ファイルは、**私が手で持ち主を確認して移した**ものだけ。

> **直し方の失敗であって、見つけ方の失敗ではない。** 孤児を探す錠は正しかった。
> 誤ったのは「見つかった全部を機械的に直す」判断で、
> **持ち主が分かるものだけを 1 件ずつ**が正しかった。
> 既存の 19 件は `KNOWN_UNOWNED` に文言で載せ、**覆っていないことを明記**した。

### 76 例目 — その錠が、**この巡で作った孤児 2 件**を見逃した

検出器は `*/` の**次の行**が `/**` の場合しか見ていない。javadoc は空行を無視して
次の宣言に付くので、**間に空行が 1 本ある 2 連ブロック**も同じく最初が落ちる。
そしてこの巡は、まさにその形を 2 つ作った — **`-1` は負値になりうるという
今回の契約の核心**の javadoc と、exporter の 1 件。

1 巡目に 1 行形式を見落とし、2 巡目に空行形式を見落とした。
**「1 つの綴りしか知らない検出器は、他方について clean と報告する」**と
自分で書いた錠の javadoc が、そのまま 2 度目に当てはまった。

### 77 例目 — 同じ store の**3 番目**の呼び出し元

`LongTermValidityService` が `unreadableCount()` を一度も呼んでいない。
`AnchorService` (2 つの動詞) と `EvidenceRecordService` には訂正が届いていた。
出力先は `/long-term-validity` — **運用者が更新要否を判断する画面**で、
答えないビューが「nothing is anchored」＝「更新するものは無い」と読まれる。

### 負のコントロール (13 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CP | 3 番目の呼び出し元が数を見ない形に戻す | `unaccountedReceiptRowsAreNotAnUnanchoredRepository` |
| CQ | 空行を挟んだ孤児を作る | `everyJavadocBlockReachesADeclaration` |

> **CQ は 1 度目、測り方を間違えた。** 検出器の方を壊してから孤児を作ったので、
> 鳴らないのは当たり前だった。**負のコントロールで壊すのは「守っている側」ではなく
> 「守られている側」**である。検出器を直したまま孤児だけ作って測り直した。

### レビューが CLEAN と確認した範囲 (この巡)

`-1` = unknown 規約は全呼び出し元が三分岐で扱っており、`> 0` 単独比較・合計への加算・
生値の表示は 0 件。RAG の guard は `doc_type:document` の書き手が RAG だけなので単位が一致。
singleton の可変フィールドは全クラスで注入依存のみ、カウンタは全て ThreadLocal。
`completed_with_errors` は型・UI・i18n・RELEASE_NOTES すべてに通っている。

---

## 33. 型削除の依存チェックが、規模で恒久的に止まる (2026-08-30)

**自分のテスト実行が見つけた。** 本日フルスイートを 12 回ほど回した結果 `bedroom` が
**81 万文書 / 642MB** まで育ち、TCK の `createAndDeleteTypeTest` が落ちた:

```
Deleting type 'tck:testid_without_properties' failed: ... Could not determine whether
objects of type ... still exist: Mango query failed: timeout
```

`ContentDaoServiceImpl.confirmNoInstances` は `{objectType: id}` を Mango に投げて 1 件取る。
**この selector に索引が無い**ので CouchDB は全件を走査する。

> **訂正 (§38)**: ここには当初「規模のある配備では**必ず** timeout する」「型の削除が
> **恒久的に不能**になる」と書いていた。**どちらも強すぎる**。走査時間は文書数に比例するので、
> 小さなリポジトリは両方の問い合わせに問題なく答える。同じ 80 万文書の環境で実測すると
> `objectType` は **46 秒 (上限内)**、`secondaryIds` が timeout した。正しくは
> 「**ある大きさから** timeout し、**その規模では**削除できなくなる」。
> 索引を足しても所要時間は文書数に比例したままなので、
> **一桁大きい環境では同じ形に戻りうる** — 索引はその境界を押し上げただけである。
> RELEASE_NOTES 側を先に直し、**この台帳だけ元の強い版が残っていた**
> (訂正の腕がまた 1 本足りない)。

呼び出し元は fail-closed で「判定できなかった」を返すので、その規模では**型の削除ができない**。
しかも文面は一過性の障害のように読める。

**拒否そのものは正しい** — この fallback は「再構築中の view が、実体のある型に対して
『インスタンス無し』と答える」窓を塞ぐために在る。だが**規模で答えられない検査は検査ではない**。

`Patch_ObjectTypeMangoIndex` を足した (`Patch_IngestMangoIndexes` と同型。ただし
**per-repository** — 対象文書は各リポジトリ DB に在る)。失敗しても throw せず
`reportIncomplete` にした: 索引の無いリポジトリも**動作はする**、型を消せないだけである。

> **1 回目の timeout が型を消し残し、2 回目は「既に存在する」で落ちた。**
> 失敗の再現に見えたが、2 回目は 1 回目の**結果**であって独立の再現ではない。
> さらに CouchDB の文書を消しても直らなかった — TCK が叩いているのは
> **常駐コンテナ (8080)** で、その `TypeManager` のキャッシュに残っていたため。
> 使い捨ての型を 1 つ作って `refreshTypes()` を踏ませて解消した。
>
> **環境の汚れは自分が作った** ([[measurement-pollutes-tck]])。ただし
> **索引が無いという製品側の欠陥は本物**で、汚れはそれを閾値の向こうへ押しただけである。

### 補足 — この索引パッチ自身が、**片腕だった**

1 稿目は `objectType` だけを索引した。**依存チェックは 2 本のクエリを走らせる** —
主型を見る `confirmNoInstances` と、それが空振りしたら**必ず**進む
`isUsedAsSecondaryType` (`secondaryIds` に対する `$elemMatch`)。
つまり**「使われていない型を消す」= 成功するはずの経路**が、
索引の無い 2 本目に入って同じように timeout する。

同じ巡で「片腕だけ直す」を何度も書いておきながら、**自分の修正が片腕だった**。

錠は**問い合わせ側から索引側を検算する**形にした — ソースから selector の
フィールド名を抜き、パッチが覆っているかを見る。3 本目のクエリが足された日に落ちる。
逆向きの control も置いた: **どのクエリも使っていないフィールドを索引しない**
(索引は全リポジトリ DB のコストである)。

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CR | 空行を読み飛ばす行を消す | `theDetectorSeesEverySpelling` |
| CS | 索引を `objectType` だけに戻す | `everySelectedFieldIsIndexed` |

> **CR / CS とも 1 度目は発火しなかった。** 孤児検出は「今のツリーが綺麗」を
> 見ているだけで**検出器そのものを測っていなかった** (Codex の指摘)。
> 検出ロジックを取り出して**fixture に対して**測る形にし、3 つの綴り
> (隣接・1 行・空行挟み) を全部回すようにした。
> **綺麗なツリーに対する緑は測定ではない。**

---

## 34. 14 巡目 — 索引の効きを**実機で測った**。指摘の半分は成立しなかった (2026-08-30)

### 78 例目 (P1) — 索引パッチが 2 本のクエリのうち 1 本しか覆っていなかった

依存チェックは `confirmNoInstances || isUsedAsSecondaryType` で、
**前者が false のとき (＝削除が成功すべきとき) は必ず後者へ進む**。
`objectType` だけ索引しても、**成功経路が索引の無い 2 本目で timeout する**。
同じ巡で「片腕だけ直す」を何度も書いた直後に、自分の修正が片腕だった。

錠は**問い合わせ側から索引側を検算する**形にした (`TypeDependencyQueriesAreIndexedTest`)。
ソースから selector のフィールド名を抜き、パッチが覆っているかを見る。
逆向きの control も置いた: どのクエリも使っていないフィールドを索引しない。

### 測った — レビューの機序は成立しなかった

レビューは「`$elemMatch` は JSON 索引をシークできないので、索引を足しても解決しない」と述べた。
**81 万文書の `bedroom` で実測した**:

| クエリ | 索引なし | 索引あり (構築後) |
|---|---|---|
| `{objectType: X}` | 46 秒 〜 timeout | **即答** |
| `{secondaryIds: {$elemMatch: {$eq: X}}}` | 60 秒 timeout | **1〜2 秒** |

`_explain` は `$elemMatch` でも索引を選ぶ。**「シークできない」は成立しない。**
ただし "documents examined is high" の警告は残るので、**効くが最適ではない**。
索引を張る前の 1 回目の問い合わせは**構築を待つので timeout する** —
これを「索引が効かない」と読み違えかけた。

> **指摘の結論が正しくても、機序が誤っていることがある。** 結論
> (「1 本目だけでは足りない」) は正しく、機序 (「索引は使えない」) は誤りだった。
> 機序を信じて「索引では直らない」と書いていたら、**効く修正を捨てていた**。

### 79 例目 (P2) — `-1` (不明) が、**ふつうの状態**になっていた

前巡で「warm cache は数を知り得ない」として `-1` を返す形にした。**それが outage だった** —
cache hit は**working cache のふつうの状態**であって障害ではない。消費者は非 0 で拒否するので、
**一度一覧したフォルダへの外部取込が以後すべて拒否**され、fixity は毎回
`folder-children-uncounted` を追記専用の鎖に書く。

数を **tree と一緒にキャッシュする** (`Tree.unreadableAtBuild`) 形に変えた。
「正直だが常時発火する」は正直ではない — RAG の guard に自分で書いた
「ふつうの作業を拒否する guard は guard ではなく outage」がそのまま当てはまった。

### 80・81 例目 — 錠の守備範囲が、また変更範囲より狭かった

- `EveryRefusalCarriesItsLimitsTest` は「`requireAdmin()` と `unavailable()` の両方」と
  書いてあるが、fixture が CallContext を null にするだけなので**403 の腕しか通らない**。
  503 側の `limits` を消しても緑だった。admin で services 未配線の腕を足した
- `NoJavadocIsOrphanedTest` の ROOTS に `rest/ingest` と `fixity` が無かった (**3 度目の後追い**)。
  変更した package が入っていない

### 82 例目 — `/status` が新しい throw を包んでいなかった

store を refuse に変えた当のメソッドを、`AnchorController.status` は素で呼んでいた。
**兄弟の `/retry-unsettled` は最初から包んでいる** — 1 クラスの中で片腕。

### 負のコントロール (14 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CR | 空行の読み飛ばしを消す | `theDetectorSeesEverySpelling` |
| CS | 索引を `objectType` だけに戻す | `everySelectedFieldIsIndexed` |
| CT | build 時の数を tree に載せない | `aWarmHitReportsWhatTheBuildFound` / `aColdTreeCacheKeepsTheStoresCount` |
| CU | 503 ヘルパから `limits` を外す | `everyUnavailableSaysWhatItDoesNotEstablish` |
| CV | `/status` の try を外す | `statusReportsALedgerReadItCouldNotMake` |

> **CR・CS・CV は 1 度目に発火しなかった** — CR と CS は錠が無かった。
> **CV は 4 回目でようやく正しく測れた**: 錠を書いたあとも発火せず、
> 原因は**細工が別のメソッドに当たっていた**こと — `retryUnsettled` と `status` に
> 同じ文面の try があり、`index()` が先に現れる方 (既に包まれていた側) を壊していた。
> **「壊した」と「狙ったところを壊した」は別**で、前者しか確かめないと
> 「錠が弱い」と誤診する。壊した行番号まで見る。
>
> この巡だけで 3 本。**「直した」と「測った」は別の作業**である。

---

## 35. 15 巡目 — **実行時の指摘が P3 だけになった** (2026-08-30)

Codex 5 巡目: **「P1・P2 なし。実行時の訂正は妥当」**。出たのは文書とテスト網羅の 5 件。

| # | 内容 |
|---|---|
| 83 | 実装側の javadoc が、もう返さない `-1` を説明したままだった。**インタフェースの契約と食い違う** |
| 84 | パッチの javadoc が「どんな規模でも timeout する」と書いていたが、**自分の実測**は `objectType` 索引なしで 46 秒 (timeout していない)。小さいリポジトリは通る |
| 85 | 逆向きの control が**ファイル全体**を検索していた。`secondaryIds` は無関係な `getContentsBySecondaryType` にも、`type` は設定読み出しにも出るので、**不要な索引が偶然通る** — この control が防ぐはずだった偽陰性そのもの |
| 86 | `/status` の錠が `highestSequence` の**2 つの呼び出しのうち 1 つ**しか通らない。fixture が `latestCheckpoint` を null に固定していたので、checkpoint が在る側の wrap を外しても緑 |
| 87 | 孤児検出の ROOTS に `util/cache` が無い。**この巡で `Tree` を触っている** |

85 と 86 は**同じ形**である — 「1 つのメソッドに 2 つの呼び出しがあれば 2 本の腕」「control の
探索範囲が主張より広ければ偶然で通る」。実行時の欠陥は尽きたが、
**錠の作り方の癖はまだ出る**。

> **ROOTS の後追いは 4 度目。** 3 度目に「変更が触る package を、見落としてからではなく
> 変更時に決める」と書いたのに、4 度目をやった。今回は
> **`git diff --name-only` から広げよ**とテストに書いた。
> 「気をつける」で直らなかったものは、手順にしないと直らない。

### 負のコントロール (15 巡目)

| 記号 | 壊した箇所 | 落ちたテスト |
|---|---|---|
| CW | `/status` の 3 本目の読み (checkpoint 在り) の try を外す | `statusReportsALedgerReadItCouldNotMake` |

---

## 36. 15 巡目の後半 — 実行時に残っていた 3 件と、UI まで届いていなかった 1 件 (2026-08-30)

Codex が「P1・P2 なし」と答えた同じ巡で、サブエージェントが**実行時の欠陥を 3 件**出した。
**2 つのレビューは違うものを見る。**片方の「無し」は全体の「無し」ではない。

### 88 例目 — `checkpointAndAnchor` の `latestCheckpoint` が素のまま

3 か所のうち 2 か所を前巡で包み、**残る 1 つがいちばん高くつく側**だった:
そこに達した時点で**封は既に済んでおり**、包まないと
「封じた checkpoint は失われていない。再封印せず `/retry-unsettled` を叩け」という
指示が汎用 500 に置き換わる。しかも `/retry-unsettled` は**最新の checkpoint しか見ない**ので、
次が封じられた瞬間その 1 本は API から二度と retry できない。

### 89 例目 — `/status` は投げうる読みが 4 本、包んだのは 2 本だった

前巡で `latestCheckpoint` と `highestSequence` を包んだが、
`forCheckpoint` と `confirmed`(`coveredByAnyConfirmed` 経由) は素のまま。
`isActive()` は守らない — **store 自身のコメントが「到達可能な DB に使えない view があると
この行より上の guard は全部素通りする」と書いている**。

### 90 例目 — `-1` の分岐が全部死にコードになり、**テストが取り下げた挙動を固定していた**

`-1` を廃止したのに `< 0` の腕は 5 か所残り、`CanonicalImportServiceTest` は
**`-1` を今も駆動して**、取り下げた機序をコメントで現行として説明していた。
[[lock-the-claim-not-the-sentence]] の形が、**取り下げた側**に出た。
分岐と鎖の scope (`folder-children-uncounted`) を消し、テストの入力を正の 2 値にした。

### 91 例目 — 直しが **UI の 1 歩手前**で止まっていた

`LineageJournalController` は `undecodableRows` と「これは存在しないという判定ではない」を
出すのに、**TypeScript の型が `{ events, total }` 固定**で捨てていた。
**欠けたページが journal の中身として表示される** — サーバ側で塞いだ置換が、最後の一歩で戻る。
型・画面の警告・en/ja の文言まで通した (`tsc --noEmit` 通過)。

**同じ形が Solr 側にも**: RAG は `completed_with_errors` に直したのに、
Solr の再索引は errorCount>0 でも `"completed"` のままで、**UI のタグ写像は共有**。
エラー件数の隣で緑の Completed が出る。

> **一度戻して、実測して、入れ直した。取り下げの根拠が誤観測だった。**
>
> 戻したときの理由はこう書いてあった —「`errors` は空なのに `errorCount` が上がる。
> `errorCount.incrementAndGet()` は 3 か所とも必ずメッセージを足すので、
> **どこから来た数なのか説明できなかった**」。**両方とも誤り**だった。
>
> - 増加点は **4 か所**。4 つ目だけ `addAndGet` なので、
>   `incrementAndGet` の grep から漏れていた。
> - `errors` は空ではない。実測すると
>   `errorCount=1 errors=[Batch indexing: 1 documents failed in batch of 2,
>   Post-reindex health check: ...]` で **2 件**入っている。
>   **「空だった」を測り直さずに台帳へ書いた**。
>
> 数の出どころも一意に説明がつく: fixture の `batchOutcome(1)` が
> 「1 件書けた」と言う一方、walk が流すバッチは **2 件** (サブフォルダは
> `subFolders` に入れた**うえで**バッチにも入る)。`2 - 1 - 0 = 1`。
> しかもこれは、**隣のテストが同じ理由で既に `thenAnswer` に直していた形**で、
> その修正が届かなかった最後の 1 本だった。
>
> **教訓は「説明できない信号で切り替えるな」ではない** — それは正しい。
> 誤ったのは、**取り下げの根拠そのものを測らずに書いたこと**。
> 過大主張に印を打つ規律は身についていたが、
> **「分からない」も主張である**ことが抜けていた。分からないと書けば
> 慎重に見えるぶん見直されず、この一文は**本物のバグ (再索引が
> errorCount>0 でも緑の Completed) を 1 巡ぶん開いたままにした**。
>
> **入れ直した範囲**: `errorCount.get() > 0 → completed_with_errors`。
> 全再索引・フォルダ再索引の両入口。判別テストは
> `aRunThatFailedToIndexDocumentsIsNotReportedAsCompleted` (負のコントロール CX で発火)。
>
> **含めなかったもの**: post-reindex health check の不一致。
> あれはメッセージだけ足して `errorCount` を触らないが、それは意図的
> (`"health check is informational"`)。**理由は 2 つあり、最初にここへ書いたのは
> どちらでもなかった** — 「まだ commit していない Solr と比べるから」と書いたが、
> `forceCommitAndWait` は health check の**直前の行**で走る。コードのコメントは
> このとき直したのに、**台帳のこの一文だけが残った**(訂正の腕が 1 本足りない、また)。
> 本当の理由は (a) CouchDB 側が `collectDocumentIds` で、再索引と**同じ木の走査**なので
> 走査が短くなれば両側とも短くなり不一致が独立した証拠にならない、
> (b) フォルダ単位の再索引でも health check は**リポジトリ全体**を見るので、
> 既存のずれがあるだけで毎回 `completed_with_errors` になってしまう。
> この word の意味は「文書の索引付けが失敗した」であり、
> health check の結果は `errors` に別途出る — とコード側にも書いた。

## 37. 16 巡目 — レビュー 3 本、P2 10 件・P3 15 件 (2026-08-30)

Codex はクレジット切れで不参加。サブエージェント 3 本 (全体 1・EARK/ERS/custody 状態機械 5・
未追跡 connector 4) が並行で入り、**同じ巡の訂正の中**から 10 件の P2 が出た。

> 見出しは最初「P2 6 件」と書いていた。**下の表の行数 (6) を件数と取り違えた**もので、
> 実際は 1 + 5 + 4 = 10。台帳の**過小記載も過大記載と同じ欠陥**である
> ([[withdrawal-is-a-claim-too]] と同じ向き) ので、印を残して直す。
> 同じ数字を利用者への報告にも一度出しており、そちらでも訂正した。

### 実測が 1 件 — `timeout()` は body を覆わない

`HttpRequest.timeout()` はレスポンス行が来た時点で満たされる。
`BodyHandlers.ofString()` の間はそれが読み取り全体だったが、
**受け手が送る量を縛るために `ofInputStream()` へ替えた**とき、
body の読みがクライアントのタイマーの外へ出た。
「short enough to fail rather than hang」というコメントはそのまま残った。

レビュー側の実測 (Temurin 21): 2 秒 timeout で `send()` は 23ms で返り、
1 byte 送って止まったサーバに対し `read()` は例外なくブロックし続け、
外から kill するまで戻らなかった。`MAX_BYTES` は**量**を縛るが**時間**は縛らない
(毎秒 1 byte なら 2 GiB に 68 年)。

**§19 の教訓「手段を替えると、目的と無関係の契約も替わる」の、もう一段先。**
同じ差し替えで「リクエストタイムアウトが body を覆う」も外れていた。
直しは watchdog がストリームを閉じる形 —
**時計を見る形では効かない**。止まった受け手は `read()` の**内側**で
ブロックするので、ループ先頭で締切を見る実装はその判定に到達しない。

#### テストの偽物が実物と違った

最初の fake は「1 byte 返してから 5 分眠る」だった。閉じても眠りは覚めないので
watchdog は無効なまま、眠りが明けて `read()` が -1 を返し、**テストは 600 秒かけて落ちた**。
実物は「閉じられたら例外」なので、そこを写していなければ
**閉じる保護を、閉じても何も起きない相手で測っていた**ことになる。
lock + `notifyAll` で書き直した。

### 「訂正した腕の、隣の腕」がまた 6 件

| 直したもの | 残っていた腕 |
|---|---|
| fixity 応答の `status` | **鎖に書く scope**。死んだ pass が `folder-children-partial:{id}:unread=3` と恒久記録。`partial` は「意図的に止めた」の予約語で、`unread=3` は「3 件＋届かなかった不明数」を正確な会計として書く |
| `undecodableEntries` を SIP に出す | `else if` で **capture 読み失敗と排他**。両方起きたとき鍵ごと消え、「この object を名指す ledger entry は無い」だけが出荷される |
| proof 失敗時に status と `inclusionProofFailed` を足す | `limits` は**全アームで「audit path が証明する」と言い続けた**。しかも**その欠陥をコメントが名指ししていた**まま |
| 軽い失敗に note を足す | **重い失敗 (何も確立していない側) の 2 アームに note が無い**。zip をディスクに流す呼び出し元には JSON が見えないので、軽い方だけ通知され重い方は無通知 |
| `findBySubject` の counter リセット | `CouchAnchorReceiptStore.rows()` が **doc の来なかった行を数えずに捨てる**。消費者は表示ではなく**再アンカーの guard** で、すり抜けると RFC 3161 トークンを二重に買う |
| folder 側に 404 を足す | `verifyOne` に無い。打ち間違えた id が **200 / `status:"success"` / `outcome:"UNVERIFIABLE"`** |

### 「読めなかった」を受け手の所見として出す — 3 件目

`payloadName` が null のとき `equals(null)` が全行で false になり、
**実際に GET を打ったうえで**「AIP の manifest はこの transfer が送った package を
記述していない」と返っていた。運用者には**移管事故の顔**をして届く。
同じファイルの姉妹引数 (`relativePathToManifest`) は正しく守られており、
`ReceivingSystem` には規則が明文化されている —
**3 つの引数のうち 1 つだけが漏れていた**。

近い形が 2 つ:
- `parts[0]` を 64 桁 hex か見ずに `sha256Hex` として返す。`manifest-md5.txt` を
  指すのは現実的な誤入力で、32 桁の MD5 が比較に負け、
  **拒否文は「受け手が別物を取り込んだ」**と出る
- `lastUnreadable.set(1)` の 1 が「少なくとも」の意味なのに、
  消費者が「1 件の transfer が存在して読めなかった」と平叙で出す

### 「An ingest of something else」— 直した文の隣で主張が生き残る

`CustodyReceiptAssembler` の拒否文は、上のコメントで「NOT『受け手が別の package を
持っている』」と帰属を外したと宣言しながら、**最後の一文が同じ帰属**だった。
しかも AM 経路での最有力原因は**こちら側の bag/SIP 取り違え**で、
それを同じファイルが 100 行上で説明している。
[[lock-the-claim-not-the-sentence]] の教科書的な形。

### 取り下げた主張が、新しいファイルで復活していた

`CustodyReceiptAssembler` の冒頭が「Neither measured receiver returns a receipt
(P3-4 §10)」— **§10 は外部レビューを受けてその主張を取り下げた節**である。
tracked 側の `CustodyReceipt` は訂正後の弱い言い方を持っており、
**未追跡の新規ファイルだけが強い版を持っていた**。
`git diff` に出ないファイルは、掃かれる回数が少ない。

### 索引パッチは「行を足せば済む」ではなかった

`AbstractNemakiPatch.apply` は適用済みリポジトリを名前で飛ばす。
`INDEXED_FIELDS` に 3 本目を足しても**既存配備では二度と実行されない** —
テストは緑になり、文書を実際に持っている配備だけが timeout し続ける。
兄弟は `ApiKeyMangoIndex-20260611` のように**日付を名前に持つ**規約で、
定義が変われば名前を打ち直す。`patch_objectTypeMangoIndex` には
日付も規約も無く、javadoc は「adding a line」と書いていた。
`ObjectTypeMangoIndex-20260830` に改名し、javadoc に「一行では済まない」を書いた。

### 錠の作り直しが 2 回、fixture check と負のコントロールが両方仕事をした

前節 (5 つ目の値) の錠は 2 度作り直した。詳細はそちら。
今巡はさらに、**私の編集が `NoJavadocIsOrphanedTest` に 3 件捕まった** —
javadoc とメンバの間に定数やメソッドを差し込んでいた。
自分で書いた錠が自分の編集を止めた最初の例。

### 負のコントロール (DA〜DK)

11 本 (DA/DB/DC/DD/DE/DF/DG/DH/DI/DJ/DK) すべて発火。
ただし **DF は 1 度目が空振り**した —
細工が排他性ではなく「その隣の半分」を壊しており、
落ちたのは**既存のテスト**で新テストではなかった。
[[negative-control-self-deception]] の再現。faithful な細工に直して DF2 で発火。

### 5 つ目の値を足したら、待っている側が 4 本壊れた — 二度目

`completed_with_errors` を Solr 側に入れ直した直後に、
**その値を待つ側**を洗った。壊れていたのは 4 本、しかも**うち 1 本は前巡から**:

| script | 判定 | 起きること |
|---|---|---|
| `reindex_connection_watch.py` | `== "completed"` | **測定が変わる**。deadline まで sampling を続けるので、終わった後の ESTABLISHED を数え、peak が薄まる。CLAUDE.md が F3 で引用している数字はこの probe のもの |
| `reindex_wipes_index_probe.py` | `in ("completed","error","cancelled")` | deadline 消化後に最後の poll を返す |
| `reindex_phase_breakdown_probe.py` | 同上 (deadline 36000s) | **10 時間ハング** |
| `rag_revocation_seed.py` | `== "completed"` | 900s 待って「再索引が終わらなかった」。**RAG に値を足した前巡から壊れていた** |

UI は無事だった (`case 'completed_with_errors'` があり、`default` が生文字列を出す)。
**コンパイラも `tsc` もサービスのテストも、どれも見えない**:
producer は正しく、consumer もそれぞれ自分の知っている語については正しい。
**対応だけが誤っていて、その対応は言語境界を跨ぐ。**

#### 直した向き

accept-list を伸ばすだけにしなかった。probe には
**「知らない status は終端として止め、値を名指しして落ちる」**を入れた。
accept-list は**値が増えたとき**壊れ (今回 2 度目)、`!= "running"` は
**非終端の値が増えたとき**壊れる — 後者は probe にとってより悪い
(早く抜けて、部分的な測定を完全なものとして報告する)。
`rag_revocation_seed.py` だけは向きが違い、`completed_with_errors` を
**受理せず即座に落とす** — 落としたフォルダのある seed は測定の土台にならない。

#### 錠: `ReindexTerminalWordsHaveConsumersTest`

語を実装から導出し、poller を**何を fetch しているか**から導出する。
どちらも列挙しないので、6 つ目の語も新しい script も**足した日に**捕まる。

#### この錠は 2 回作り直した。両方とも自己欺瞞の実例

1. **fixture check が自分の正規表現バグを捕まえた。** `setStatus\(..."([a-z_]+)"`
   は 1 呼び出しにつき 1 語しか取らないが、実際の語は入れ子三項で書かれている
   (`cancelled ? ... : errors>0 ? "completed_with_errors" : "completed"`)。
   取れたのは `[error, cancelled]` だけ。**この 2 語は 4 本とも既に名前を書いている**ので、
   fixture check が無ければ**何も要求しないまま緑**だった。
2. **負のコントロールが発火しなかった。** tuple から語を消しても落ちない。
   理由は、私が probe に**その語を説明するコメントを入れた**から。
   ファイル全体を検索していたので、**散文が錠を満たしていた**。
   コメントと docstring を剥いでから探すように直し、剥ぎ取り器にも control を付けた
   (素通しで返す実装は**静かに**元の偽陽性に戻すため)。

§93 の「一つの主張に出口は 10 か所」と同じ形だが、**出口が別言語にある**のが新しい。
grep は当たる (単語は同じ) のに、**当たったのが散文だった**。

### 「分からない」と書いた文の隣に、絶対形が残っていた

上の訂正と同じ巡で見つかった対。`scopeLimits` には
「一覧についての陳述であって、データベースが今持っているフォルダについての陳述ではない」と
正直に書いたのに、その数を作っている `addToTreeCache` の側には
**「it can never under-report」**という絶対形が残っていた。
`addToTreeCache` の操作としては正しい (子を足しても数は下がらない) が、
**文が操作の範囲を超えて数そのものの性質を語っていた**。
tree を作ったあとに decode 不能になった子は次の再構築まで数えられないので、
低く出ることはある。**§89 の「取り下げた側に印を打つ」の、隣の扉**。

### 早期 return が ThreadLocal を持ち越していた

`findBySubject` の `lastUnreadable.set(0)` が、空 subject の guard の**下**にあった。
counter は singleton 上の ThreadLocal でスレッドはプールされるので、
空 subject で抜けると**前のリクエストが残した値**を次が読む。
消費者は真上にいる — authenticity report が `findBySubject` の直後に
`unreadableCount()` を読み、`duplications` を `UNAVAILABLE` +
`undecodableEntries: N` にする。**誰も読んでいない行**を「この記録の鎖の行」と名指しする。
`objectId` は未検証の request parameter なので `?objectId=` だけで届く。
1 行上へ動かした。判別テストは `anEarlyReturnDoesNotHandOnTheLastCount` (負のコントロール CY)。

§70 (「counter の帰属窓」) の再発。**総数は書かない** — 「5 例目」と書きかけて、
数えられる形で列挙できないことに気づいた。この節で数を 2 度間違えており
(見出しの P2 件数、負のコントロールの本数)、**確かめずに書いた数はこの文書で最も外れやすい**。
代わりに、確かめられる形で並べる:

| 場所 | 形 | いつ |
|---|---|---|
| `CouchEvidenceLedgerStore.highestSequence` | 例外を投げる読みの後で counter を読む | §70 |
| cached `ContentDaoServiceImpl` | tree cache hit が store を通らない | §82 付近 |
| `AuthenticityReportAssembler:382` | `highest < 0` を 0 と同一視 | §88 付近 |
| `CouchEvidenceLedgerStore.findBySubject` | 早期 return が reset の上 | 本節 |
| `CouchAnchorReceiptStore.rows()` | doc の来ない行を数えない | §37 |

機構の側に理由がある: リセットが「読む直前」に書かれていて、**関数の入口ではない**。
次に counter を足すときは入口で reset する。
`CouchLineageJournalStore` の 8 か所は今も guard の下にあるが、
`readiness()` が READY から ABSENT へ戻らないので**現状は漏れない** — 到達不能な既知点として
開けてある。

### tree cache の陳腐化 — 保証の範囲を狭めたので、文もそう直した

`unreadableAtBuild` が数えるのは「tree を作った読み取りが decode できなかった行」だけで、
**cache に載ったあと他レプリカが子を変えた**場合は 0 のまま通る。
機構を広げるのではなく、`scopeLimits` に**「一覧についての陳述であって、
データベースが今持っているフォルダについての陳述ではない」**と書いた。
古い entry は再読のときに evict するようにした (しないと TTL まで毎回二重に読む)。

### 文書側

- capture-boundary runbook の判定表が**三値のまま**だった。コードは `ABSENT` を含む 4 値を返す
- 引き渡しの取り決めが `reportedOutcome` を無条件に「両方持つ」と書いていた。
  **写像が要らなければ空にしなければならず、両方入れると 409**
- `RELEASE_NOTES` の「1〜2 秒」に規模の但し書きが無かった。
  全文書が `secondaryIds` を持つので**索引は疎にならず、所要時間は文書数に比例する**
- §69 の「`-1` にした」に、§79 で取り下げた印が無かった。**取り下げた側に印を打つ**
## 38. 17 巡目 — Codex が「錠が保護を測っていない」を 4 件 (2026-08-30)

Codex P2 7・P3 1、サブエージェント P2 3・P3 4。**新たな実行時欠陥は 1 件のみ**で、
残りは**錠の側**の指摘だった。両者が同じテストの弱さで独立に一致している。

### 主題: helper を直接叩くテストは、本番の配線を測っていない

4 件が同じ形だった。

| 錠 | 何を叩いていたか | 本番側を戻すと |
|---|---|---|
| `aBuildLimitIsNotAFindingAgainstTheRecord` | `isAboutThisBuild` を直接 + **手打ちした例外メッセージ** | 分岐を消しても、メッセージを改名しても**緑** |
| `anErrorListThatWasCutOffSaysSo` | `withTruncationNoted` を reflection で | 呼び出し側を戻すと**緑**、黙って切り捨てに戻る |
| `ReindexTerminalWordsHaveConsumersTest` | 語が**ファイルに在るか** | `is_terminal(...)` を `== "completed"` に戻し tuple を残せば**緑** |
| `TypeDependencyQueriesAreIndexedTest` | `INDEXED_FIELDS` だけ | フィールドを足しても `PATCH_NAME` を替えなくても**緑** = 既存配備に索引は作られない |

**私の負のコントロールは全部 helper を壊していた。** だから発火した。
Codex が指したのは「**呼び出し側**を壊す」細工で、そちらでは緑のままだった。
[[negative-control-self-deception]] の 3 度目だが、形が違う —
今回は「保護そのものを壊さず、保護と本番の**間の線**を切る」。
直したあとの EA/EB/EC/ED/EE すべてでこの細工が発火する。

`isAboutThisBuild` は特に悪く、**継ぎ目が文字列**なのにテストが文字列を手打ちしていた。
`ErsRecord.digest` を未知 OID で実際に投げさせる形に替えた。
[[claim-substitution-trap]] —「安く確かめられる代用品」を証拠に書いた例。

### 実行時の欠陥 1 件 — 一部だけ読めた package が `success` を名乗る

両方空のときは前巡で直したが、**何かが読めたときの腕**が残っていた。
`undecodableEntries` を別のキーに出しながら `status` は無条件に `"success"`。
読み手が最初に取る語が、隣のキーと逆のことを言う。しかも
capture 読み失敗の側は **note すら無い** (zip を直接保存する利用者には JSON が見えない)。

### 台帳と RELEASE_NOTES

- **台帳が、コードで否定した理由をそのまま持っていた** (§36 の health check 除外)。
  コードと RELEASE_NOTES は直したのに**台帳だけ残った** — 訂正の腕がまた 1 本足りない
- RELEASE_NOTES が索引の効果を**「必ずタイムアウト」「恒久的にできない」**と書き、
  同じ節で「規模が一桁上がれば同じ形に戻りうる」とも書いていた。実測は
  「46 秒 (上限内)」と「タイムアウト」で、**規模依存**が正しい
- 鎖に書かれる scope を **4 種のうち 2 種**しか挙げていなかった。
  `folder-children-incomplete` は文書のどこにも無く、
  指示どおり検索を足すと**落ちた検証だけが漏れる**
- `/fixity/scan/folder` の **HTTP コード変更 (500 / 404 / 400)** が挙動変更節に無かった

### 錠を書きながら 3 度、正しいコードを咎めた

新しいアサーションを書くたびに過検出した:

1. `setErrors` を全部数え、**初期化の 2 か所**を落とした
2. status 比較を全部拾い、**RAG の health チェック**を咎めた (別のフィールドの別の語彙)
3. 語を全部要求し、`silent = status in ("completed","completed_with_errors")` を咎めた
   — あれは「完了と言ったか」を見る行で、`cancelled` を挙げないのが**正しい**

3 度目でようやく、実際に二度壊れた**対**だけを見る形にした:
`completed` を名指す判断は `completed_with_errors` も名指さねばならない。
**正しいコードを咎める錠は、次の読み手に「何も咎めなくなるまで緩めろ」と教える。**

### `EG` — 私のテストが別の理由で通っていた

EARK の新テストは fixture の証明が失敗するため、`status` が**どのみち**
proof の status で上書きされていた。**細工しても緑**。
mock で証明を成功させて初めて、この腕を測るテストになった。

### 負のコントロール (EA〜EH)

8 本すべて発火 (EG は fixture を直した EG2 で)。

## 39. 18 巡目 — 「片腕」が兄弟サービスと controller 層に残っていた (2026-08-30)

Codex P2 2・P3 2、サブエージェント P2 4・P3 9。**新種の欠陥は無い。**
出たものは全部、このバッチが既に追っている 2 つの形の続きだった。

### 兄弟サービスに届いていなかった

- **RAG 側の error 一覧は今も無言で切り詰められていた。** CMIS 側を直した巡で
  RAG を見ていない。UI は**同じ部品**で両方を描くので、片方のタブでは説明が付き、
  もう片方では `errorCount: 5000` の隣に 100 件が並んだまま。
  しかも CLAUDE.md がアップグレード必須手順に挙げている方である
- **`formatDuplicationRecorder == null` が無言 return。** このバッチの他の
  「bean 未配線」腕は全部声を出す (SIP は `unavailable` + note、fixity と anchor は 503)。
  ここだけ黙り、RELEASE_NOTES は「**その 1 つが必ず記録します**」と書いていた

### 錠が service 層で止まり、JVM を出る場所が無防備だった

- `signatureCheck` は **controller の応答が唯一の出口**なのに、
  検査は service 層だけ。応答から消してもスイートは緑で、
  `CustodyReceipt` は運用者に「応答の signatureCheck を見よ」と言い続ける
- `AnchorController.upgradePending` の `unavailable` 腕も同じ。差し戻すと
  store に訊けなかった配備へ `success / upgradedCount:0` と
  「**まだ何も settle していないので再アンカーするな**」を返す —
  service のテストのコメントが名指ししている害そのもの

### 錠そのものの脆さ 9 件

差し戻しでは発火するが、**無関係な改名や書き換えで壊れる/黙る**もの:

| 形 | 例 |
|---|---|
| **発火しえない** assertFalse | 差し戻し前の実文字列は `"took in THIS package"` で、錠は `"reported"` を見ていた。保証は隣の assertTrue だけが担っていた |
| ローカル変数名への依存 | `setErrors\(errors\)` — `errorMessages` に改名すると raw=0 で**黙って**通る |
| catch 変数名と空白への依存 | `if (isAboutThisBuild(e))` — `ex` に改名すると正しいコードが落ちる |
| コメントを剥いでいない | 対象が「WARN か DEBUG か」を論じる catch なので、その議論に `logger.debug` と書くと落ちる |
| 綴りへの依存 | camelCase の `receivingSystem` に当たっているだけで、散文に言い換えると落ちる |
| 厳密な件数 | `assertEquals(6, …)` は 2 行上の「後から足した状態も当日に覆う」と矛盾する |

**壊れる向きと黙る向きの両方がある。** 黙る方が悪い。

### 台帳の体裁

節番号が **2 系統**になっていた (本文は「15 巡目」まで、新しい 2 節は「7/8 巡目」)。
順序も 36 → 38 → 37 だった。家の様式 (`## N. M 巡目 — … (日付)`) に揃えた。

### 私が持ち込んだ事故 1 件

trailing whitespace を消すスクリプトの mtime 閾値を誤り、
**無関係な 279 ファイル**を書き換えた。空白のみの差分は `git checkout` で戻し、
自分が編集したファイル内の「触っていない行」も difflib で位置合わせして
HEAD の形に戻した (492 行)。差分は 105 ファイルに復帰。

### 負のコントロール (FA〜FC)

3 本すべて発火。

## 40. 19 巡目 — **P1 が 1 件出た**。10 巡目にして削除経路 (2026-08-30)

Codex P1 1・P2 2・P3 1、サブエージェント P2 1・P3 6、
並行の手動レビュー (利用者側) P2 2。

### P1: `deleteTree` が読めない子の上から親を消していた

`getChildren` は decode できない行を**例外なしで**落とすので、
`deleteTree` は見えた子を消し、**親を消し**、読めない子は
「id では実在するのに、もう存在しないフォルダにぶら下がる」孤児になった。
読み経路の訂正は 9 巡かけて揃えたのに、**破壊経路は誰も見ていなかった**。
削除には reconcile が無いので、読み経路より一段重い。

ついでに**入れ子の `failureIds` が捨てられていた** — サブツリーが消し切れなくても
親には何も伝わらず、親は自分を消していた。祖先が子孫の上から消える同型の穴。

直し: 一覧が短い、または子孫に失敗があるとき、**そのフォルダ自身は消さず**
`failedToDelete` に載せる。錠 3 本 (short / control / nested)、負のコントロール GA・GB。

### P2: 両再索引の walk も短い一覧を「小さいフォルダ」として扱っていた

同じ機構の別消費者。decode できない行は walk から黙って抜け、run は `completed`。
その文書は**誰にも知られずに検索から消えたまま**になる。
CMIS・RAG 両方で `errorCount` に数え、`completed_with_errors` に落とすようにした
(今回は**両腕を同じ日に**直した — 前 3 対は毎回 1 巡ずれた)。GC2・GD 発火。

### P2: 私の今巡の訂正が新しい欠陥を作っていた (upgradePending)

`lastQueryFailed` の分岐を入れた際、**混合ケース** (一部 decode 失敗 + 一部 upgrade
成功・保存済み) が「`upgradedCount: 0` / 503」— **保存された仕事を、無かったと報告**する
形にした。狩っている substitution の逆向き。兄弟 verb (`retryUnsettled`) に揃え、
**行動する前に拒否**へ変更。FH 発火。

### 続き物

- `lastQueryFailed` の分離が **anchor 側の第 3・第 4 消費者** (EvidenceRecordService →
  SIP 焼き込み / LongTermValidityService) に届いていなかった。全 4 消費者済み
- 手動レビューの P2 2 件: 「null は silent by design」の**取り下げ印が javadoc と XML に
  無い** (WARN を設計違反として消される) / RAG の切り詰め錠が**注記の効果を測っていない**
  (add を消しても緑)。どちらも閉じ、FF は細工が 2 度壊れて 3 度目 (FF3) で発火
- 錠の総点検分: patch の錠に `getName()` と Spring 登録 2 件を追加 /
  `NoJavadocIsOrphanedTest` の除外を語境界に (途中切りのエントリ 2 件が発覚) /
  reflection をやめ public コンストラクタ直呼び (コンパイル時に落ちる方が早い) /
  「stopped」→「did not finish」 (watchdog は総時間で、stall を測っていない)

### 数字の訂正 (並行レビュー指摘)

- 未追跡「11 件」は `git status` の行数で、**展開すると 15 ファイル** (テスト 11)
- 負のコントロールの台帳記載が FA〜FC で止まっていた。以後: FD・FE・FF3・FG・FH・
  GA・GB・GC2・GD (今巡 9 本、すべて発火。ただし FF と GC は細工がコンパイルを壊して
  取り直し — **細工スクリプトが文字列内の `;` と括弧に二度噛まれた**)

## 41. 20 巡目 — **19 巡目の P1 修正が、本番で呼ばれないメソッドに入っていた** (2026-08-30)

サブエージェント P1 1・P2 1・P3 3、Codex P1 1 (同一)・P2 2・P3 1。
両者が独立に同じ P1 を出した。

### P1: 護りを付けた deleteTree は、誰も呼ばない方だった

19 巡目の修正は `ContentServiceImpl.deleteTree` に入れた。**このメソッドの本番呼び出し元は
0 件**である。CMIS 全バインディング・Browser servlet・REST v1 はすべて
`ObjectServiceImpl.deleteTree` → `deleteTreeDFS` に集約され、そちらは短い一覧のまま
親を消し続けていた。おまけに通常の `deleteObject` のフォルダ枝
(`ObjectServiceInternalImpl`) は、**読めない子しか居ないフォルダを「空」と見て**
constraint 検査を素通りさせていた。RELEASE_NOTES は直っていない挙動を直ったと書いていた。

**間違えた腕に護りを付けると、護った気になるぶん素通しより悪い。**
[[sabotage-the-call-site-not-the-helper]] の主語を「テスト」から「修正そのもの」に
替えた形で、このバッチ最古の教訓が自分の最新の修正に刺さった。

直し: `deleteTreeDFS` (counter 読み・自己削除の guard・入れ子失敗の伝播) と
`deleteObjectInternal` フォルダ枝 (constraint refuse) の両方。
`continueOnFailure` が**受け取られたまま無視されていた**のも併せて実装。
挙動錠 `DeleteTreeDfsKeepsFoldersOverInvisibleChildrenTest` は private walk を
reflection で直接駆動する — GG (guard だけ殺して読みは残す細工) で発火を確認。
roster 錠 (存在検査) では GG は**捕まらなかった**。存在と挙動の二段が要る。

### P2: find-or-create の棚卸しも 4 件漏れていた

19 巡目に 3 件 (DirectorySync×2 + 統合ヘルパーの呼び先) を塞いだが、
**統合ヘルパーそのもの** (`getOrCreateSystemSubFolder`)、`AuthTokenResource` の独立コピー、
`BulkCheckInResource` (check-in が重複 create に化ける)、`CloudDriveResource`
(update-or-create が重複 create に化ける) が残っていた。
さらに **Purview 増分同期**の子孫展開が、短い一覧の部分集合を完了として外部カタログに
発行していた (再試行義務も残らない)。全 5 件を塞いだ。

> **§42 で追い越し**: ここに「拒否+再試行」と書いた Purview の形は 21 巡目で
> poison-pill (恒久的に読めない行が全後続をブロック) と指摘され、
> **dead letter + バッチ続行**に変わった。さらにその dead letter が
> **同一バッチ内で自己消去する**ことも見つかり、最終形は §42 のとおり。

### 機構の錠: `ShortListingsDoNotReachDestructiveConsumersTest`

破壊的/不可逆な消費者 16 メソッドの roster。存在検査なので
「読みを残して効果だけ戻す」細工は通す — それは各消費者の挙動錠 (deleteTree 3 本、
DFS 3 本、reindex 2 本、canonical import) が受け持つ。
roster 自体の負のコントロール GE/GF2、挙動側 GG、いずれも発火。

### 巡回中の細工事故 (記録)

GC の細工がまた括弧を壊した (GC2 で取り直し)。roster のメソッド名を 4 回
書き間違えた (シグネチャ実在検査が全部捕まえた — 錠が錠を直した)。
既存テスト 1 本 (`ContentServiceImplSystemSubFolderTest`) が新しい読みで NPE になり、
harness に stub を 1 行足した (製品挙動の意図的変更に伴う正当な更新)。

## 42. 21 巡目 — Codex P1 2 件。**修正の 3 世代目でようやく形が定まったものがある** (2026-08-31)

Codex P1 2・P2 4・P3 1、サブエージェント (再走) P2 2・P3 7。
サブエージェント初回はセッション制限で死に、再走した。

### P1: `.system` patch の直接 CouchDB 経路が decode 失敗行を無言でスキップ

fallback 経路は 2 巡かけて塞いだのに、**その手前にある直接経路**が
`getDoc()==null` と per-row 例外をログだけで通過し、null → 呼び出し元が
2 個目の `.system` を作る。gate (`cmisViewsAreAnswering`) は「view が答えない」を
受け持つが、**行単位の decode 失敗は view が答えている**ので素通り。
両経路にカウンタを入れ、`.system` 未発見 + unreadable>0 で throw
(直接経路の throw は fallback に落ち、fallback 側のガードが受け止める連鎖を
サブエージェントが追認)。fallback 側の throw は**自分の catch に捕まる位置に
一度書いて**、催促される前に外へ出した。

### P1: 並列削除の割り込みが「状態不明」を「削除済み」として通していた

`InterruptedException` は interrupt flag を立てるだけ、`ExecutionException` は
「起きないはず」— どちらも failedIds に**入らない**ので、直後の guard
(failedIds が空なら親を消す) を素通りし、**実行中かもしれない子の上から親が消えた**。
両方を記録し、割り込み後は残り future を cancel してノードを保持。

### P2 群 (Codex → 同日修正)

- **保持したフォルダを Solr から消していた** — 公開メソッドの postlude が無条件
  `solrUtil.deleteDocument`。安全のために残したフォルダが検索から消える。
  条件付きに (source-text 錠 + HA 発火)
- **continueOnFailure=false が部分実装** — 検査が試行の「後」/並列 arm は全 submit /
  未試行が failedToDelete に載らない (仕様は「削除されなかった id の一覧」なので
  **省略は削除済みに読める**)。検査を前に、false は逐次、未試行を全列挙
- **Purview の poison-pill** — 20 巡目の throw は 1 行の恒久故障で全後続をブロック。
  dead letter + skip + 続行に変更
- **BulkCheckIn が Throwable を空 200 に畳む** — 拒否理由が全損 + temp file リーク。
  JSON error + finally 掃除

### サブエージェント再走の P2 (最終形はこれで決まった)

- **Purview の dead letter が同一バッチで自己消去** — フォルダ自身は decode できる
  (壊れているのは子の行) ので publish ループが upsert 成功 → 保存したての
  dead letter を削除。しかも retry service は**単体 re-upsert のみ**で子孫展開を
  再実行しないので、「再試行で回収」はそもそも成立しない。最終形:
  展開拒否フォルダは **run の failures に計上** (COMPLETED_WITH_ERRORS)、
  publish 成功でも dead letter を消さない、**「完全回復には行修復 + full sync」を
  失敗文言に明記**。3 世代 (throw → dead letter → failures+保護) でようやく
  「正直かつ運用可能」に着地
- **Browser binding が FailedToDeleteData を捨てて {} を返す** — 同梱 UI は
  この binding でフォルダを消すので、**guard が保持したフォルダが UI では削除成功**
  に見えた。servlet が `{ids:[...]}` を返し、UI が非空なら例外にする形へ
  (両端とも修正、tsc 通過)

### P3 で直した分

parentFolder==null の空 200 残存 (直したばかりの症状が 1 分岐上に) /
拒否経路の FileInputStream リーク (guard を open の前へ) /
interrupt 時の node id 重複 (dedup — **と書いたが半分だった**。nodeMarked は
future ループ内だけで、直後の guard が同じ id をもう一度足していた。
並行レビューが実測で指摘し、guard 側に contains() を足して閉じた。
「dedup した」という一文が、dedup し切る前に書かれていた) /
roster に `Patch_SystemFolderSetup` 追加 /
cof=null が continue-always のままである判断を**選択として明記**
(仕様の既定は false だが、初版からの挙動 + 既存呼び出し元の性能を優先)

### 意図的に変えなかったもの

- CloudDrive の status=false + HTTP 200 は **その API 全体の既存慣行** — reason は載る
- `aRetainedFolderStaysFindable` は source-text 錠 (正しいリファクタでも落ちる) —
  trade-off をテスト内に明記済み
- クラスタ (Terracotta) 限定の「旧形式 Tree 直列化が unreadableAtBuild=0 で蘇る」窓 —
  standalone は heap-only で無関係。ここに記録

### 負のコントロール

HA (Solr postlude) 発火。GG 系は前巡から有効。
Purview / BulkCheckIn / servlet / UI の新分岐は挙動錠なし —
**存在検査 (roster) と本節の記録のみ**である。次に触る人への正直な申し送り。

## 43. 22 巡目 — 負のコントロールを機械化し、**P1 が paged 版から出た** (2026-08-31)

Codex P1 1・P2 3・P3 2、サブエージェント P2 3・P3 3、並行手動レビュー P2 1・P3 1。

### 並行手動レビューの 2 件 (先に閉じた)

- RELEASE_NOTES が**追い越された Purview 世代**を現在形で書いていた
  (「拒否して再試行」— 実際は failures + dead letter + full sync)。19 巡目と同種の
  「直っていない挙動を直ったと書く」。最終形に書き換えた
- **「dedup した」と台帳に書いた dedup が半分だった** — nodeMarked は future ループ内
  だけで、直後の guard が同じ id をまた足していた。contains() で閉じ、台帳に印

### 負のコントロールの機械化 — `tools/negative-controls/run_negative_controls.py`

手作業の差し戻し実験は「発火した」と「発火したと言った」を第三者が区別できず、
本数を**二度**数え間違えた。11 本 (FE, FF3, FG, FH, GB, GC2, GD, GE, GF2, GG, HA) を
宣言的に移し、**11/11 発火・完走 (1003 秒) を機械記録にした**。
runner 自身への 22 巡目指摘も全部入れた: returncode を見る (fork crash を green と
読まない) / `-DfailIfNoTests=false` 廃止 (改名されたテストが 0 件緑にならない) /
期待メソッドの失敗が **assertion 由来**であることの確認 (「壊れて落ちた」を
「発火した」と読まない) / 未知 ID 拒否 / DID NOT FIRE でも復元検証 /
復元前に「ディスク == 細工」を確認 (並行編集を黙って巻き戻さない) /
中断復旧は `core/src` 配下限定。

**このセッションで踏んだ事故 3 件も記録する**:
1. **runner を背景で走らせたまま自分が `mvn compile` を打った** — runner の docstring に
   自分で書いた「並行 Maven 禁止」を、書いた直後に自分で破った。走行を kill し、
   復元を確認して単独で再走した
2. 2 連 python の 1 つ目が `s` を更新せず write し、**2 つ目が 1 つ目の編集を上書き**
   (Purview cap 修正が一度消えた。grep の件数確認で発覚)
3. rglob 全域の中断復旧が、**JDT LS が target/classes へリソースとしてコピーした
   backup** を拾い、compiled-classes ディレクトリに .java を書いた
   (既知の jdtls-poisons-the-WAR 罠の自作自演)。掃除して範囲を絞った

### Codex P1: `getChildrenPaged` の短縮が Purview 包含関係を**削除**していた

paged 版は counter を記録するが、containment reconciliation は読まず、しかも
1 行欠けるとページが短くなり**「最後のページ」と誤読して走査を打ち切る** —
1 行の decode 失敗がサブツリー丸ごとの不可視化に化け、見えなくなった辺は
「消えた関係」として**外部カタログから削除**された。
直し: 不完全な walk は「見えた辺の追加」だけ行い、**削除は 0**、snapshot は
**前のまま** (進めると次回の基準線が汚れる)。短いページでの打ち切りも廃止
(ループは元々 totalChildren で有界)。

> **§44 で訂正**: 「打ち切りも廃止」は書いた時点で半分だった —
> **全行 decode 失敗のページは空**になり、`isEmpty` の break が同じ打ち切りを
> 別の扉から起こしていた。空 + unreadable>0 なら continue に直した (両 walker)。

### 残りの対応

- cof=false の「未試行も列挙」は**直下のみ** — NOTES の文言を実装に合わせて限定
  (未試行フォルダの**子孫**までは列挙しない。walk しないものは列挙できない)
- NOTES「サブツリーは発行されず」→ フォルダ**自身**は発行される (自分の path は
  正しく計算できる)。文言修正
- Purview の **MAX_DESCENDANT_COUNT 到達枝**が warn+break のみだった (既存) —
  decode 失敗と同じ規則に揃え、failures 計上 (cap でも「打ち切った」は失敗)

### 台帳と runner の相互参照

台帳の §36〜§42 の「発火した」は手作業記録、11 本は runner で機械化済み、
FA〜FD・GA ほかは**手動履歴のみ** (runner 対象外) — この区別はここに書いてあるのが
すべてである。

## 44. 23 巡目 — 締めの巡で **P1 が 2 件、paged 消費者の棚卸しから** (2026-08-31)

Codex P1 2・P2 5、サブエージェント P2 2・P3 4。締めの巡としては多いままである。

### P1: dead-letter retry の再走が「見えない」を「消えた」として削除する

`retryRepositoryCloudSyncLineage` は同期腕に入れた incomplete guard を**持っていなかった**。
不完全 walk で見えなくなった文書が「removed」として reconcile され、
process entity と (共有されうる) 外部資産が消える。retry から到達可能。
throw に変更 — dead letter が生き、完全な walk まで absence 系は走らない。

### P1: FULL sync が短縮ページの上で COMPLETED し、change token を先へ進める

full sync は COMPLETED 後に**最新 change token を seed する** —
つまり取りこぼした既存オブジェクトは、その後の増分同期が**永遠に再訪しない**。
short page で throw (FAILED、cursor 据え置き、再試行可能) に変更。

### P2 群

- **「全行 decode 失敗のページ」= 空ページ**が isEmpty break で「最後のページ」と
  誤読される — 22 巡目の「打ち切り廃止」は半分だった (§43 に訂正印)。
  空 + unreadable>0 → continue (containment / cloud 両 walker)
- **他の paged 消費者 3 つ**: backfill の `childFolders` (COMPLETE を汚す) →
  throw / catalog reconciliation の `childFolders` (clean report を汚す) → throw /
  **NavigationServiceImpl** (CMIS getChildren の生読者) —
  `batch.size() < dbLimit` の end-of-data 誤読 + **skip が decode 行基準で進む**ため
  落ち行を永遠に再読しうる、の両方を raw 行基準で修正
- **cap arm の dead letter 未保存** — 「promised in a comment, saved nothing」。保存を追加
- **runner の起動時復旧が、前回 refuse が守った並行編集を次回起動で潰す** —
  復旧は「backup と同一 (no-op)」「既知の細工形」のみ書き戻し、
  不明状態は**触らず大声で残す**に変更。復旧範囲は前巡で core/src 限定済み
- **改名テストクラスの誤帰属メッセージ** (「build を壊した」) を両義に

### 錠 (サブエージェント P2-1: 「主修正 3 点が無錠」への応答)

containment / cloud に「不完全 walk は publish のみ・削除 0・snapshot 維持」の
挙動錠を追加。負のコントロール HB は**4 回作り直した**:
1 回目: incremental compile が細工前の class でテストを走らせ「発火せず」と誤読
(既知の罠を自分で再演)。2 回目: **私の grep が `<<< ERROR!` を見ておらず**、
発火していたのに「発火せず」と誤読 — **観測の grep が壊れていると、
発火も不発火も同じに見える**。3 回目: 発火はしたが assertion でなく
「GUID is not tracked」の ISE — runner 自身の新基準 (assertion 由来のみ FIRED) で不可。
4 回目 (HB4): stub に GUID を持たせ、削除が**呼ばれて verify の assertion で**落ちる形で
確定。HC (cloud) も同型で発火。roster に paged walker 6 メソッドを追加
(検査は counter 直読みと incompleteness flag の両綴りを認める)。

### 未対応で残すもの (P3、条件が狭い)

- incomplete round 中に **create された辺が snapshot に入らない** — その辺が
  complete walk 前に消えると、外部カタログに stale edge + stateStore の GUID が残る。
  発生条件: 手動 reconcile + unreadable row + 消滅、の全て重なり。
  対処案 (前 snapshot ∪ 発行辺) は把握済み、ここに記録して持ち越す
  → **§53 で閉鎖** (containment / cloud / incremental cursor の 3 点合流)
- NavigationServiceImpl の skip 補正は **早期 break (pageFilled) 時の decode 行基準**の
  近似を残す (numItems 系の既存の非厳密と同じ層)

## 45. 24 巡目 (並行手動レビュー) — **P1 は別 DAO に居た。棚卸しは `getChildren*` で止まっていた** (2026-08-31)

並行手動レビュー: P1 1 (getArchives 系)・P2 5。

### P1: アーカイブの一覧が同じ 2 形を両方持っていた

`ArchiveDaoDelegate.getArchives` は (a) decode 失敗を WARN で捨て counter 無し、
(b) **クエリ例外で空リスト** — getChildren が最初の巡でやめた置換が、
アーカイブ側にそのまま残っていた。消費者 `loadValidArchives` は短ページを最後と誤読し、
`syncRepositoryArchivesIfChanged` が snapshot と差分して
**実在するアーカイブを Purview から reconcile で削除**。dead-letter retry も同経路で、
成功扱いなら cursor 前進 + dead letter 消滅。

直し: delegate に `lastUnreadableArchiveCount` (行 null / decode 失敗を数える)、
例外は throw へ。`loadValidArchives` は unreadable>0 で **refuse**
(このループは総数が無くページを空まで回す形なので、folder walk の skip-forward が使えない
— 続行ではなく拒否が唯一の正直な形)。全経路 (増分 sync / retry / FULL / lineage /
snapshot) が同じ load を通るため 1 か所で全部守られることを確認。
増分は catch→dead letter、retry は per-entry catch、FULL は catch→FAILED。
錠 HD、roster に追加。

### P2 群

- **HB〜HF を runner に追加** — 「発火したと言った」に戻っていた 22〜23 巡の新錠 5 本を
  機械化し **5/5 発火** (Mockito の verify 例外を assertion として認める拡張込み)。
  runner は 16 controls
- **Navigation の probe** — 空 + unreadable>0 が「子なし」と答えていた。throw に
- **snapshot builder 2 本** (containment / cloud) に guard — 今日は呼び順で守られているが、
  それは「順序の事実」であってメソッドの性質ではない
- **publishRepositoryCloudSyncLineage** に同 guard (dead letter を消す成功が短い walk に
  乗らないように)
- **cloud retry の部分成功** — sync の incomplete arm は publish-only の部分成功なのに、
  retry が SUCCESS と読んで dead letter を消していた。Result に `walkIncomplete` を載せ、
  retry は保持へ

### ビルド事故 2 件 (記録)

1. `PurviewDeadLetterRetryServiceImpl` に **存在しない `log`** を書いたのに
   compile が通った — JDT LS との target 共有 race で javac が走らず、
   **JDT のエラー入り class** が残り、フルスイートで
   `CompiledClassesAreUsableTest` と実行時 Error として爆発 (この錠が仕事をした)
2. snapshot builder への guard 挿入が **return の後**に置かれ unreachable —
   同 race で隠れ、クリーン compile で発覚。どちらも即修正、以後この 2 修正は
   maven-status を消してから確かめた

## 46. 25 巡目 (並行手動レビュー) — **change log が FULL と同じ token 追い越しを持っていた** (2026-08-31)

並行手動レビュー: P1 1・P2 3。

### P1: `getLatestChanges` — 落ち行の上を cursor が越える

decode 失敗を WARN で捨て (counter 無し)、例外は空リスト。Purview 増分は
`resolveNextCursor` で**デコードできた最後の token** へ進むので、途中の 1 行が落ちると
cursor はその先へ行き、**その変更は二度と再訪されない** — DELETE なら外部カタログの
実体が full sync まで残る。FULL の token 追い越し (§44) と同じ形が、
change log という別の DAO 読みに居た。

直し: delegate に `lastUnreadableChangeCount` (doc null / decode 失敗を数える) +
例外は throw (空置換廃止) + **null rows も throw**。Purview の `loadChanges` は
読み直後に counter>0 → throw (catch → dead letter + cursor 据え置き、既存機構)。
錠 `anUnreadableChangeRowKeepsTheCursorWhereItWas` (HG、runner 経由で発火)。
なお CMIS DiscoveryService / RSS も同じ読みを使うため、
change log が読めないとき**空の変更一覧を返さず失敗する**ようになる — 意図的変更。

### P2 群

- **getArchives の null rows** — 「answered without rows」を空として返していた
  (getChildren が最初に塞いだ扉)。throw に
- **アーカイブ DAO の兄弟 3 本** (child / versionSeries / byCreator) の例外空置換 → throw。
  `emptyTrash` は読めなかった行を数え、**「Trash partially emptied」**へ
  (見えた分だけ destroy して "successfully" は、ゴミ箱に残っている物を無いと報告する)
- **retry に入れた双子が増分本体に無かった** — `syncCloudMetadataStream` が
  `walkIncomplete` を見ず COMPLETED + dead letter 削除。失敗として記録し letter 維持へ

### roster / runner

roster は change log 消費 (`loadChanges`) と `emptyTrash` を追加して 27 メソッド、
検査綴りに `lastUnreadableChangeCount` を追加。runner は HG を加えて 17 controls。

### ビルド事故 (この巡、3 件 — すべて JDT レース絡み)

1. 2 連 python の 2 本目が assert で死に**フィールド系 4 点が未適用**のまま、
   JDT レースの偽 compile (grep 0 件) を信じて先へ進んだ。フルスイートの
   `CompiledClassesAreUsableTest` が捕まえた
2. やり直しの挿入が `s.index` の**最初の出現**に当たり、同じ view を使う
   **単数の `getLatestChange` に入った** — そこでは外側 catch が throw を null に握り、
   reset の無いカウントは**他メソッドへの帰属漏れを私が新造**する形だった。
   grep の行番号 (使用行がフィールド宣言より前) で発覚し、正しい位置へ移設
3. 以後、この系の確認は `rm -rf maven-status` + **Compiling/BUILD SUCCESS の目視**を必須にした

### 現在地

「destructive → 配線 → paged → 別 DAO (archives) → **change log**」。
counter を持つ読みは children / paged / archives / changes の 4 系になった。
roster がその対応表だが、**表に載っていない読みが次の巡で見つかる**構図は
5 巡連続で変わっていない。

## 47. 26 巡目 (並行手動レビュー) — **同じ読みのもう一人の消費者: CMIS の changeLogToken** (2026-08-31)

並行手動レビュー: P1 1・P2 4。指摘の要約が正確だった:
「未知の DAO を探す前に、`getLatestChanges` を読んで token を進める経路が
**もう一本ある**」。deleteTree 対 DFS、unpaged 対 paged と同じ構図の 3 度目。

### P1: CMIS の変更フィードがクライアントの token を穴の上へ進める

`compileChangeDataList` の「連続成功」ガードは**受け取ったリストの中**の compile 失敗
しか守れない — DAO が既に抜いた行は連続に見える。`[100, 102]` は両方成功し、
クライアントの `changeLogToken` は 102 へ。**落ちた DELETE はそのクライアントに
二度と届かない**。さらに `skipFirst` が**先頭を無条件に捨てて**いた —
Purview の `normalizeChanges` は token 一致を確認するのに、CMIS 側はしていない。
startToken の行が消えていると、**次の本物の変更が「配達済み」として飲まれる**。

直し: counter>0 → throw / skipFirst は **token が一致する行だけ**捨てる。
錠 3 本 (`ChangeEventServiceDelegateTest`)、runner HH・HI で発火確認。

### P2 群

- **単数 `getLatestChange` の fail-open** — decode 失敗が null (「変更なし」) になり、
  FULL の seed が空・`latestChangeLogToken` が per-event token と不一致で
  hasMoreItems が終わらない。decode 失敗は throw、**空リポジトリの null は維持**
  (旧 catch の契約どおり)。§46 で誤挿入して外した当のメソッドが fail-open のまま、
  という指摘 — 誤挿入の「結果オーライを残さない」判断は正しかったが、
  **正しい形を入れ直すのを忘れていた**
- **counter が半分** — `readValue` null / `getProperties()` null を数えていなかった
  (archives 側は数えている)。両方数える
- **archive 兄弟の残り 6 本** (all / archivedBy / byState / paged×2 / coldTransition) と
  **`getObjectChanges`** の例外空置換 → すべて throw。
  retention の cold-transition sweep も「読めない一覧で動かない」側に倒れる
- RSS の不完全フィードは P2 のまま残す (persistent cursor が無く、
  counter を見る consumer 化は表示系の設計判断 — ここに記録)
  → **§53 で閉鎖** (拒否に倒した。フィードは 500、reader は再試行)

### roster / runner

roster 28 (`ChangeEventServiceDelegate.getLatestChanges` 追加)、runner **19 controls**
(HH・HI)。counter 4 系 (children / paged / archives / changes) の消費者で
cursor・token・破壊を持つものは、把握している限り全て表に載った。

### 現在地

構図は「同じ読みの、まだ表に居ない消費者」が 6 巡連続。今回で
**token を進める経路は Purview 増分と CMIS フィードの 2 本とも**閉じたが、
「把握している限り」以上の主張はしない。

## 48. 27 巡目 (並行手動レビュー) — 私の直しの「ずれ」4 点 (2026-08-31)

並行手動レビュー: P2 4 (すべて**前巡の私の修正の残り半分**)。

### skipFirst が申告より広く捨てていた

「token 一致のときだけ捨てる」と報告したが、実装は
`first == null || token == null || token.equals(startToken)` —
**null-token の先頭行も「配達済み」として消していた**。Purview の
`normalizeChanges` は equality only。合わせた
(`startToken.equals(first.getToken())` のみ)。錠 `aNullTokenFirstRowIsKept` 追加。
**HI の負のコントロールは「無条件 remove への差し戻し」しか測っておらず、
この中間形 (null も捨てる) は通していた** — 直した形が申告と一致しているかは、
細工の差し戻し先が「本当に元の欠陥」かに依存する、という教訓の再演。

### 単数 getLatestChange の扉が 1/3 しか閉じていなかった

decode の catch だけ throw にして、`getRows()==null` / `getDoc()==null` /
`readValue` null は「変更なし」のまま — **§46 で「入れ直すのを忘れていた」と
書いた修正が、今度は 3 分の 1 だけ入っていた**。3 つとも throw に
(rows が空のときの null は空リポジトリの正当な答えとして維持)。

### getArchivesByArchivedBy が半分だった

例外だけ throw で、decode 落としは数えず null rows は空リスト。
getArchives と同じ規約 (reset + doc null / readValue null / docMap null を数える +
null rows throw) に揃えた。

### 次の一覧 (レビュー指摘の「同じ 2 形」) も閉じた

- `getRelationshipsBySource / ByTarget` の例外空置換 → throw。
  **object 削除がこれを読んで関係を消す**ので、空置換は「関係の取り残し」
  (実体の無い object を指す edge が残る) だった
- `getGroupItems` ×2 → throw

### 現在地

27 巡のうち直近 4 巡の指摘は、**新しい欠陥より「私の修正の残り半分」が主**になった。
「直した」という報告と実装の間のずれ — 広すぎる捨て方、1/3 の扉、半分の counter —
を、並行レビューが毎巡拾っている。修正そのものと同じ強度で
**修正の報告を検証する**必要がある、という §36 以来の教訓の最終形。

## 49. 28 巡目 (並行手動レビュー) — HI の stale アンカーと、4 つ目の扉 (2026-08-31)

並行手動レビュー: P2 4 (今回も**私の修正の残り半分**が主)。

### HI が古い実装を指したままだった

skipFirst を equality-only に精密化した際、**runner の HI は消えた文字列をアンカーに
持ったまま**だった。走らせれば「anchor が無い」で止まる (発火ではない)。
「19 controls・変更なし」という報告は件数の話で、**HI が現行コードを測れるという
意味ではなかった** — §48 で書いた教訓 (細工の差し戻し先が本当の欠陥か) を、
その教訓を書いた次の巡で踏んだ。現行の equality 行をアンカーに更新し、
expect_fail に `aNullTokenFirstRowIsKept` を加え、**発火を確認** (両錠とも)。

### 単数 getLatestChange の 4 つ目の扉

「3 扉を閉じた」の隣に `getProperties() == null` が残っていた。throw に。
3 → 4 と数えるたびに 1 つ隣が見つかる形そのもの。

### getGroupItems が「半分」だった

例外だけ throw で、null rows は空リスト・decode 失敗は log 捨て —
archivedBy に対して自分が指摘された形と同じ。**認可データは fail-closed**:
decode できない group item はメンバーシップを**持っている**ので、
短い一覧から答えるのを拒否する (null rows / decode 失敗とも throw)。両 overload。

### 次の一覧 3 本 (レビュー指摘)

`getCheckedOutDocuments` / `getAllVersions` / `getAppliedPolicies` の
例外・null 空置換 → throw。cursor は進めないが、
**checkin / cancelCheckOut / deleteAllVersions がこの答えに基づいて行動する**
(短い版一覧の上の deleteAllVersions は版の取り残し)。

### 現在地

直近 5 巡の主戦場は「新しい欠陥」から「**私の修正の報告と実装のずれ**」へ移った。
skipFirst の广い捨て方 → null token 錠 → **その錠を runner が測れていない** →
更新して発火、という 3 段は、錠・runner・報告の**三層すべてに同じ検証**が要ることを
示している。RSS (§47) と意図的残置 3 件 (§44) は変わらず。

## 50. 29 巡目 (並行手動レビュー) — overload の片方と、throw した catch の隣の null 戻り (2026-08-31)

並行手動レビュー: P2 4。今回もすべて**前巡の私の修正の残り半分**。

### getGroupItems — 「両 overload」と報告して、片方 + 半分だった

28 巡目の while ループ挿入が**無ページ側に 2 回**入り (レビューが「同じ if が二重」と
指摘)、**ページ側には 0 回**だった。挿入の当たり先を数えず「両 overload」と報告した。
さらに fail-closed の理由が **catch にしか当たっておらず**、`doc == null` と
`convertValue` の null 戻りは捨てて続行 — 「decode の catch は閉じ、
decode の null 戻りは閉じていない」。両 overload とも null rows / doc null /
convert null / catch の 4 点を throw に統一し、二重 if を除去。

### 隣接 3 件 (レビュー指摘)

- **getUserItems** — 例外は throw だったが null rows は空・convert 失敗は skip。
  ディレクトリ同期がこの一覧から作成・削除を決めるので、identity データも
  fail-closed に (null rows / convert 失敗とも throw)
- **getGroupItemCount** — null rows / 例外で 0。「0 = 居ない」は
  この失敗が確立しない主張。throw に
- **getAppliedPolicies の要素 null** — decode できない applied policy は
  **適用されている** — 黙って外すと呼び出し元がその制御を静かに外す。throw に

### 現在地

「閉じた」の単位が overload → arm → catch/null-return と細かくなるほど、
報告と実装のずれの粒度も細かくなって続いている。数えられるものは数え、
挿入は当たり先を目視する — §48〜§50 で 3 巡連続の同型。
RSS (§47) と §44 の残置は変わらず。

## 51. 30 巡目 (並行手動レビュー) — users 側の残り。arm 表で締める (2026-08-31)

並行手動レビュー: P2 3。指摘は arm 表付きで、私の報告が「メソッド名だけで arm を
書いていない」ことも名指しされた — §50 で自分が宣言した運用を、宣言した次の報告で
守っていなかった。

### 対応 (arm 単位で列挙し、行番号を目視確認済み)

| メソッド | null rows | 非 Map 行 | 必須欠落 | convert()==null | catch |
|---|---|---|---|---|---|
| getUserItems 無ページ | throw (済) | **throw に** | **throw に** | **throw に** | throw (済) |
| getUserItems ページ | **throw に** | **throw に** | **throw に** | **throw に** | **throw に** |
| getUserItemCount | **throw に** (0 廃止) | — | — | — | throw (済) |

identity データも認可データと同じ fail-closed: この一覧からディレクトリ同期が
作成・削除を決め、count は画面の「登録ユーザー数」になる。

### 教訓の連鎖 (§48→§51)

細工の差し戻し先 → runner の stale アンカー → 挿入の当たり先 → **報告の粒度**。
4 巡連続で「私の直し」と「私の直したという報告」の間のずれが主戦場だった。
今回から「閉じた」には arm 表を付け、適用は行番号の目視で確認する
(本節の表がその 1 例目)。

### 現在地

users / groups / policies / versions / PWC / relationships / changes / archives /
children (paged 含む) — 把握している一覧読みの「訊けなかった ≠ 無かった」は
全系統で throw または counter に揃った。RSS (§47) と §44 の残置は変わらず。

> **§52 で訂正**: 「全系統で揃った」は書いた時点で成り立っていなかった。同じファイル
> (UserGroupDaoDelegate) 内に `parentGroupIdsFrom` の null-result arm、
> `getJoinedGroupByUserId` の直接系 4 arm とネスト系 (`checkIndirectGroup`) 4 arm、
> ArchiveDaoDelegate に count 2 本の「例外 → 0」が残っていた。31 巡目の並行レビューが
> arm 表で列挙。「全系統」は数えて言う語であって、直した勢いで言う語ではない。

## 52. 31 巡目 (並行手動レビュー) + 意図的残置への着手前整理 (2026-08-31)

並行手動レビュー: P2 3。30 巡目の修正は arm 表どおり確認された上で、§51 の
「全系統揃えた」が**成り立っていない**ことが同じファイルの中から示された
(§51 に訂正印)。

### 対応 (arm 単位、行番号目視確認済み)

**UserGroupDaoDelegate — 認可・削除整合の残り 3 系統**

| メソッド / arm | null result・null rows | 行 value null | 行 decode 失敗 | 外側 catch |
|---|---|---|---|---|
| parentGroupIdsFrom (削除時逆引き。呼出 2 か所は既に throw) | **throw に** (空戻り廃止) | throw (済) | throw (済) | throw (済・呼出側) |
| getJoinedGroupByUserId 直接系 | **throw に** | **throw に** | **throw に** (warn-skip 廃止) | **throw に** (空戻り廃止) |
| checkIndirectGroup ネスト系 (同じ membership の内側) | **throw に** | **throw に** | **throw に** | **throw に** (部分戻り廃止) |

- 逆引きは**削除の腕**: 空戻りは「誰も参照していない」と読まれ、削除が成功と
  報告しながら宙ぶらりんの nested 参照を残す。javadoc は最初からそう論じていて、
  arm だけが従っていなかった。
  > **§54 で追補**: この表自体が 1 arm 数え漏れ — decode 成功した Map 行の
  > **groupId 欠落**は skip のままだった (直接系・ネスト系とも)。さらに逆引きの
  > **後段** (親の再取得 null → continue) が同じ dangling を作る片割れとして残っていた。
- joined groups は**認可の腕**: 空戻り・skip は方向としては安全 (権限が増えない) だが、
  主張として偽 — ユーザーは黙って権限を失う。レビューの指摘どおり「安全な方向の嘘」も嘘。
- ネスト系はレビュー指摘の直接系に**私が足した片割れ**。直接系だけ閉じると、
  nested group 経由の権限だけが静かに消える構図が残る (test-env で実バグとして
  観測済みの系統)。

**ArchiveDaoDelegate — count 2 本**

| メソッド | 例外 |
|---|---|
| getSearchableArchivesCount | **throw に** (0 廃止) |
| getSearchableArchivesByStateCount | **throw に** (0 廃止) |

0 は「ゴミ箱は空」。ArchiveResource / ArchiveSearchResource の pager が
この値で numFound とページ数を組むため、失敗が「空のゴミ箱」として描画されていた。

### 錠

- `MembershipAnswersAreNeverSilentlyShortTest` — 12 本 (直接系 4 / ネスト系 3 /
  逆引き 3 / コントロール 2)
- `ArchiveCountsAreNotZeroOnFailureTest` — 3 本 (arm 2 + コントロール 1)
- 負のコントロール HJ〜HS (10 本) を runner に追加。**10/10 発火** (741 秒、復元後の
  re-run green まで機械確認)。runner は 29 controls に。

### 今巡の私の事故

- 新テストが 9 本 UnfinishedStubbing で落ちた: `thenReturn(helper())` の引数評価が
  開いた `when()` の内側で別モックを stub する — **隣の既存テストのコメントが
  まさにこの罠を警告していた**のに踏んだ。全部 when() の前に組み上げる形に修正。
- その修正で変数名を重複させ (`parents` 2 回)、JDT LS の毒入りクラスが
  「Duplicate local variable」の実行時 Error として噴出。実バグ (重複) を直し、
  test-classes を作り直して 17/17 green。

## 53. 残件着手 — §44-1 snapshot 合流 + 実機 E2E + RSS (2026-08-31)

### §44-1: incomplete round 中に create された辺の snapshot 合流

§44 で「対処案 (前 snapshot ∪ 発行辺) は把握済み、持ち越す」とした穴を閉じた。

**穴の形**: incomplete walk 中に**作られた**辺/文書は外部カタログへ発行されるが、
基線 (snapshot) は previous のまま。complete walk 前に消えると、その walk は
diff の**どちら側にも**見つけられず、外部の stale 辺・資産・GUID が永遠に残る。

**合流の規則** (3 点、いずれも「基線は狭めない、広げるだけ」):

| 箇所 | 合流 | 条件 |
|---|---|---|
| containment incomplete arm | previous ∪ 発行辺 | ループ完走 = 追加辺は外部に実在 (作成成功 or GUID 記録済み。失敗は throw で return 到達せず) |
| cloud metadata incomplete arm | previous ∪ 発行 entry | **全件発行時のみ** (upsertContents は skip を数で返すだけ — 部分発行を基線に入れると当該文書の再発行が永遠に来ない) → **§54 で訂正: この判定自体が偽**。バッチ戻り値は entity+関係の混合カウントで「文書数と等しい」は全件発行を意味しない。文書単位の呼び出しに作り替え |
| incremental の failure cursor | result の snapshot を保存 | 例外系 2 arm は従来と同値、incomplete arm だけ widened が届く |

**錠 5 本**: containment 2 (既存錠を「広げるが狭めない」形に更新 + created→vanished
roundtrip)、cloud 2 (widen + 部分発行は据え置き)、incremental 1 (failed stream でも
widened baseline が cursor に載る)。負のコントロール HT/HU/HV/HW。
HU・HW は初回発火。HT・HV は「発火したのに runner が誤読」(下記) — 判定修正後の
再走で **4/4 発火**。なお再走 1 回目は復旧漏れ (`log` フィールド宣言 —
再生 chunker が拾えない形式の編集だった) を runner 自身が
「nothing was measured (COMPILATION ERROR)」で正しく拒否して発見した。

### runner の判定欠陥 (HT/HV の誤 DID-NOT-FIRE)

surefire の .txt は Mockito verify 失敗を**メッセージ行から**始める
("Wanted but not invoked:") が、判定関数は camel-case のクラス名しか
見ていなかった。手で細工→実行して錠自身の verify (行番号まで一致) で
落ちることを確認し、メッセージ表記 3 種を判定に追加。

**併発した私の事故 2 件** (どちらも既知の罠の再演):

1. **`| tail` パイプが runner の exit 1 を 0 に変えた** — war-build-failure-masked-by-pipe
   と同じ形。発火数と exit code の矛盾に気づかず「完了 (exit 0)」と読んだ。
   以後 runner はパイプなしで起動する。
2. **手細工の復元に `git checkout <file>` を使い、未コミットの丸ごと 1 ファイル
   (containment サービス、§43 以降の walk ガード群 + 当日の合流) を HEAD に戻して
   消した。** 復旧: transcript (jsonl) から当該ファイルへの全編集操作 21 件を抽出し、
   HEAD から時系列再生。ただし再生スクリプトの 1 本が**複数ファイル一括**で、
   cloud 側 retry guard を**前方一致アンカーで二重挿入** (即検出・除去)、
   別の 1 本が**当日の細工そのもの**を再適用 (即戻し)。再生後、構造 grep +
   コンパイル + 対象 3 スイート green + HB/HT 再発火で同一性を確認。
   教訓: 「手細工の revert は必ず sabotage の逆置換で行う。checkout は
   uncommitted の全部を消す」。

### §44-3: 実機 E2E — 生きたスタックで deleteTree の保持を測った

nb33 スタック (bedroom、CouchDB 実体) に当日の WAR をデプロイして測定。

**decode 破壊の実測 3 連敗→1 勝** (机上の想定が 2 回外れた):

| 破壊 | 予想 | 実測 |
|---|---|---|
| `acl` を文字列に | readValue 失敗 | **通る** (setter が Object 受けで寛容) |
| `aspects` を object に | 失敗 | **通る** (同上) |
| `changeToken` を object に | — | **落ちる** (setter が String 型 → convertValue が throw → counter++) |

**前腕 (旧 WAR) はキャッシュに阻まれ無効**: 生成→一覧→破壊の順で触ったため、
一覧・削除ともキャッシュ経由で子が見え、バグ形 (孤児化) は再現しなかった。
直接 CouchDB を書いてもキャッシュは無効化されない、という別の既知性質の再確認。
後腕は生成→破壊→**JVM 再起動 (単純 restart、イメージ同一なので compose 再構築不要)**→
一覧の順に変更。

**後腕 (新 WAR) の測定**:

| 手順 | 結果 |
|---|---|
| コールドキャッシュで children 一覧 | `numItems: 0` — 子は CouchDB に実在するのに一覧から不可視 (再現成立) |
| deleteTree (continueOnFailure=true) | HTTP 200 + **`{"ids":["<folder>"]}`** (failedToDelete にフォルダ) |
| フォルダ GET | **200 — 保持** |
| 子の CouchDB GET | **200 — 孤児化なし** |
| コントロール (正常フォルダ + 子) | `{}` + フォルダ 404 — 完全削除 (保護は outage ではない) |
| 修復 (changeToken を文字列に戻す) → deleteTree | `{}` + フォルダ 404 + 子 404 — 修復後は普通に消える (運用手順の成立) |

後片付け済み (root 直下に e2e 残渣 0 件)。measurement-pollutes-tck の掟どおり
専用フォルダ + 即時削除。

### §47: RSS の不完全フィードを拒否に倒した

「表示系の設計判断」として残した件。フィードには cursor が無く、購読者は top-N
窓を読むだけ — 窓から抜けた行 (DELETE 等) は**再配送の機会そのものが無い**。
黙って短い窓を出すより、生成を拒否 (ISE → resource が 500) して reader の
再試行に任せる方が、この批評 (「訊けなかった ≠ 無かった」) と整合する。

| arm | 変更 |
|---|---|
| getChangesForFolder (窓) | counter>0 → throw |
| getChangesForDocument (窓・双子) | counter>0 → throw |
| collectChildFolderIds (フィードの folder filter) | children null / counter>0 → throw (subtree が黙って filter から抜けるのを拒否) |

錠 4 本 (`RssFeedsAreNeverSilentlyShortTest`: 窓 2 + filter 1 + コントロール 1)、
roster 31 メソッドに拡張、負のコントロール HX/HY/HZ。

### §44-2: Navigation 早期 break の近似 — 評価して意図的残置を維持

コードを読み直した結論: 残る近似は **hasMore / numItems の報告層に限定**される。
- ページ内の 2 つの continue 経路 (全滅ページ・不足ページ) は raw 行基準で補正済み
- 早期 break (pageFilled) 後、そのリクエストで dbSkip は**もう使われない**
- リクエスト間のページングは decode 済みストリーム上の論理 skip で一貫
  (取りこぼしも重複も出ない)
- 誤り方向は「hasMore=true を余分に返す」= 空ページを 1 回余計に取りに来るだけ

行の恒久取りこぼし・破壊は無いため、numItems 系の既存の非厳密と同じ層として残置。

### runner の総点検

HB (containment) / HE (cloud retry) のアンカーが、後から入った同文ガード
(builder / publish) と **2 重・3 重一致**になっていた — HI と同じ stale 化。
前行 (normalizeSnapshot 行) を含めて一意化。全 36 controls のアンカーを
機械 dry-check して 0 bad を確認後、通し発火を実行 (結果は本節末尾に追記)。

### fail-closed が実機 TCK で掘り出した既存欠陥 — 変更フィードの無クランプ limit

全 36 controls 発火後のフルスイートで、TCK `contentChangesSmokeTest` (実機) だけが赤。
E2E 汚染ではなく、**fail-closed 化が旧来の欠陥を初めて見えるようにした**ものだった。

**欠陥の形** (旧 WAR では無症状):
TCK は `maxItems=Integer.MAX_VALUE` を渡す → `intValue()` そのまま CouchDB の
limit へ → CouchDB は 2^28 超を `query_parse_error` (400) で拒否 → 旧コードの
fail-open catch が **400 を空リストに変換** → TCK は「変更なし」を正常として緑。
つまり「全部くれ」という要求は一度も応答されておらず、黙って無回答だった。

**直しの 2 段** (1 段目は私の誤り):
1. CouchDB の wire cap (2^28) にクランプ → 400 は消えるが**変更ログ全件が
   1 レスポンスで届き heap OOM** (数十万行、実測 3 回 OOM)。「上限を通す」と
   「応答できる」は別物。
2. アプリ層のページ上限 **10,000** (OpenCMIS 慣例) に変更。`compileChangeDataList`
   は token を**返した最後のイベント単位**で進め、hasMoreItems は repo-latest 比較
   なので、クランプされたページはクライアントを取り残さない (継続で全量届く)。
   併せて skipFirst の `limit + 1` が MAX_VALUE で**負にオーバーフロー**して
   「無制限」に化ける穴も塞いだ。

錠 3 本 (clamp 2 + overflow 1)、controls IA / IB 発火。
実機 TCK: QueryTestGroup **6/6 green** (608 秒 → 160 秒)。

### §53 締め

- runner: **38 controls / 38 発火** (36 本の通し 55 分 + IA・IB。HB/HE は
  再アンカー後に通しで発火確認)
- フルスイート: **6285 / 0** (実機 TCK 6 グループ込み、新 WAR デプロイ済み)
- 残件の現在地: §44-1 **閉鎖** / §44-3 (実機 E2E) **完了** / §47 RSS **閉鎖** /
  §44-2 は評価の上で**意図的残置を維持** (報告層限定、根拠は本節)
- 未コミット (依頼があるまでコミットしない)

## 54. 32 巡目 (Codex + サブエージェント 2 面) — fail-closed の「下の層」と「隣の系統」 (2026-08-31)

/goal「もう2巡のCodexレビューとサブエージェントレビュー」の 1 巡目。
Codex: P1 2・P2 3・P3 2 / 判別レビュー: ギャップ 3 + runner 注意 3 /
兄弟掃討: P1 3・P2 7・P3 2 (33 ファイル・全メソッド読みの表つき)。

### P1 対応

| 指摘 | 直し |
|---|---|
| `BigInteger.intValue()` が 2^31→負・2^32→0 に切り詰め、クランプを**素通り**して無制限クエリ復活 (Codex) | 変換前に `compareTo(MAX_INT)` で clamp。DAO 側も **maxItems ≤ 0 を常に 1 ページに** (「≤0 = 無制限」を廃止。意図的に使う caller 無しを grep で確認) |
| cloud 合流の「全件発行時のみ」が**偽の判定** — upsertContents の戻りは entity+関係の混合カウント (Codex) | **文書単位の発行**に作り替え (1 件ずつ upsert、>0 の文書だけ基線へ)。部分発行でも「載った分だけ widen」に強化。直列化順も fresh (objectId 昇順) に一致 (P3 の churn も同時に解消) |
| **型定義一覧が例外で「基本 2 型だけ」を合成** → 型 diff が全カスタム型の外部 entity を削除、GUID 作り直しで分類・用語が消える (掃討) | 例外→throw、decode 不能行→counted+throw。**空回答の bootstrap fallback は維持** (view が「答えて 0 行」の時だけ) |
| **`getContentFresh` の握り潰し→null が「原本消滅」** → tombstone 解決が実在文書の catalog entity を削除 / archive reconcile 同型 (掃討) | 根で修正: wrapper は既に NotFound→null / 他→throw を区別しており、**delegate の catch がそれを潰していた**。couch getContent の catch → rethrow、cached getContentFresh の握り潰しと「未配線→null」も throw に → **§55 で訂正: 「根で修正」は半分だった** — 本流の cached `getContent` と Fresh 兄弟 5 本・型付き wrapper get 2 本が同じ潰しを保持。TypeManager も同型 (下記 §55) |
| **principal 削除の参照剥がしで、親の再取得 null → continue** → dangling 参照、同 id 再作成で membership が黙って復活 (掃討) | null → throw で削除を中止 (view 半分は 31 巡目に fail-closed 済み、その後段) |

### P2 対応 (抜粋・全 arm)

- **membership の groupId 欠落 arm** (直接系・ネスト系) → throw。§52 の arm 表の数え漏れとして訂正印
- **wrapper の count×2 / paged×2 が catch→0/空** — 31 巡目までの delegate 側 throw が
  **CouchDB 障害では一度も発火しない**位置だった。generic → throw、
  view 未デプロイは **startup phase のみ**従来の猶予 (map 版 queryView と同じ方針)。
  patch gate (`cmisViewsAreAnswering`) は自前の catch→false で安全に受けることを確認
- **`getArchivesByCreator`** (非 admin ゴミ箱の片翼) を byArchivedBy 標準に (reset/null-rows/3 種 drop)
- **`getArchiveByOriginalId` catch→null** → throw (tombstone 解決と添付 archive 孤児化の根)
- **api/v1 `listArchives`** が counter 未読 + `batch < fetchSize` 早期終端 → counter>0 で refuse (1 行の破損が「以降全部不可視」に増幅する形)。roster 32 メソッドに
- **RSS token store**: getByUserId の部分リスト返し・doc-null skip → throw / getById catch→null → throw (「Token not found」の誤報)
- **RSS の limit 下限と maxDepth** (上限 16・負は default) — `?limit=-1` が空フィードの顔で無制限クエリを踏む形と、maxDepth 无限の全ツリー歩き
- **判別ギャップ 3** (G1 nested null-value / G2 深い再帰の short / G3 children==null) → テスト+コントロール
- **cross-replica の tokenCache 失効**は本巡は**記録のみ** (CrossReplicaCacheInvalidator への配線は別作業)。
  **containment の「GUID 記録 = 外部に実在」仮定** (out-of-band 削除は治らない) も**記録のみ** — コメントを正直化

### runner

- 判定に `ArgumentsAreDifferent` (FQCN) を追加 (メッセージ表記だけに依存していた)
- report 走査から `-output.txt` (stdout) を除外 (テストが `<<< FAILURE!` を print すると誤検出)
- 新コントロール **IC〜JA (25 本)**、計 **63 controls**。**25/25 発火** (2,242 秒、
  復元後 re-run green まで機械確認)。フルスイート **6317 / 0**

### 私の事故

- 入れ子スタブを**3 度目**に踏んだ (TypeDefinitions テスト、隣のファイルに 2 度目の教訓コメントを書いた直後)
- TypeDefinition の複数編集スクリプトが途中 assert で止まり、**宣言だけ落ちた状態**で後半を適用 (コンパイルが即検出)

## 55. 33 巡目 (Codex + サブエージェント 2 面) — 「もう一層上」と「catch の裏返し」 (2026-08-31)

/goal 2 巡目。Codex P1 6・P2 4・P3 1 / 呼び出し元掃討 (88 tool uses): P1 2・P2 3・P3 群 /
判別+主張監査: すり抜け 0 (25/25 判別)・アンカー失効 3・主張の過大 3。
3 面が同じ最重要点で一致した: **round-32 の「根で修正」は、その一層上の catch が
そのまま潰していた**。

### P1 対応 (arm 表)

| 指摘 (発見面) | 直し |
|---|---|
| **cached `getContent` :378 catch→null** が couch の新 throw を無効化 (→ **§56 追補**: catch は閉じたが、その**直上の配線 null 2 本**が同じ答えを返し続けていた) — CMIS deleteObject の偽成功 / user・group 削除の偽成功 / lineage 照合の誤 ORPHAN / backfill の subtree 黙殺 (Codex + 掃討 + 監査の 3 面一致) | rethrow。cached `getFolder` の外套 catch も除去。**Fresh 兄弟 5 本** (document/folder/relationship/policy/item) と `getGroupItemByIdFresh` も同時に閉鎖 |
| **`deleteDocument` が `getAllVersions` の throw を 2 回 catch して裏返す** — allVersions 腕: 「単版へフォールバック」しつつ**シリーズ文書は削除** (生存版の孤児化) / 単版腕: 空リスト→「唯一の版」→**シリーズ削除へ昇格** (掃討) | 両 catch → throw (削除中止、再実行可能) |
| **TypeManager `addSubTypes` catch→return** — refreshTypes が registry を clear 済みのため、握り潰しは「基本型のみ + initialized=true」で起動成功 — DAO が取り下げた 2 型 fallback の一層上での再建 (Codex + 掃討) | rethrow (起動失敗 = fail-fast。CouchDbVersionRequirement の「unknown means no」と同じ規則) |
| **maxItems=0 + 継続トークン**: limit=0+1=1 → その 1 行は配達済み行 → skipFirst が除去 → **token が進まない空ページを hasMoreItems=true のまま永遠に返す** (Codex) | 非正は service 側で「1 ページ」(Integer.MAX_VALUE → DAO で 10k) に正規化 — resume 加算の**前** |
| **cloud 完全腕が部分失敗の上を基線前進** — entity 失敗文書が「変更なし」化して再発行されず、document-entity の dead letter に retry 腕が無い (Codex) | 完全腕も**文書単位発行**に。失敗文書は基線 entry を**前回値に据え置き** (新規なら不掲載) → 次周で再検出・再発行。dead letter の retry 腕欠如は**記録** (回復経路は基線機構に一本化) → **§56 で訂正: 粒度は直したが判定式が偽のまま**だった。`upsertContents` の戻りは entity + companion + 関係の**混合カウント**で、`> 0` は「この文書の entity が載った」を意味しない |
| **principal 削除の再取得が cached 読み** — 別レプリカが足した membership を stale cache が「含まない」と読み、剥がし漏れ (Codex) | `getGroupItemByIdFresh` に切替 (view は cross-replica、再取得も fresh でなければ対にならない) |
| **`childrenNamesViewIsAlive` catch→true** — 「rebuild で 0 行 + count 障害」の組で盲目の一意性検査を祝福 → 重複名は恒久 (Codex) | catch → throw (拒否された create は再試行可能、重複は不可逆) |

### P2 対応

- **型付き wrapper `get(Class,id)` / revision 版**: catch→null (「This is normal during
  initial startup」を無条件に言う旧文言ごと) → NotFound→null / 他→throw。
  RSS token「取得失敗はエラー」の主張を空洞化していた根 (監査)
- **`getContentsByIds` の部分 Map** → 行変換失敗・全体失敗とも throw
  (incremental sync が「短い Map = 世界」と読み cursor を進めていた — 掃討)
  > **§56 で訂正**: 変換層だけだった。**その下の `getBulkDocuments` が
  > error 行を skip し、バッチ例外で次バッチへ続行**して短い Map を返すので、
  > この throw は本番の失敗経路では発火しない。32 巡目に wrapper で見つけたのと
  > 同じ構図の 3 度目
- **単数 `getLatestChange` の外側 catch→null** → throw (空リポジトリの正当な null は
  catch より上の arm が担当。FULL sync の空 checkpoint + COMPLETED の根 — Codex)
- **`getVersionSeries` / latest / latestMajor** catch→null → throw (restore が null で
  系列を再作成しにいく)
- **RSS token `delete` の握り潰し** → throw (「Token deleted」+ 監査成功の嘘 — 掃討) /
  **`getByToken`→`searchTokenInRepository`** catch→null → throw (有効トークンが
  CouchDB 障害で 401「Invalid or expired」— Codex + 監査。方向は安全、陳述が偽)
- **paged wrapper の doc-null / properties-null 行 skip** → throw (1 行でゴミ箱ページが
  黙って短くなる — Codex)
- **`getGroupItemByIdInternal`**: 必須欠落→null (「実在するが使えない」を「無い」と
  報告) と外側 catch→null → throw
- **groupId 欠落 arm の二重包み** (P3): ISE は再包みせず素通し + message を錠で固定

### 意図的残置 (記録)

- **untyped `get` の startup 猶予** (スレッド名ヒューリスティック) — provisioning が
  DB 未作成で走る正当経路。ヒューリスティックの弱さは既知 (Codex 6 の注記どおり
  「本番のスレッド名を確認するまで仮説」)
- **DirectorySync の私設 `removeUserFromAllGroups`** — 一覧が fail-closed 化済みで
  走査は完全。null ガードは死にコード (無害)
- **`verifyDeletionInternal`** — 掃討の「catch→true」指摘は現形と不一致 (generic は
  false=未確認、not_found のみ true)。型付き get 修正で not_found は null 経路に整流
- **stale-hit そのもの** (JM の逆) は Fresh 化で閉じたが、**cross-replica cache
  invalidation の一般問題**は §54 の tokenCache と同様に別作業

### 錠と runner

- 新テスト 24 本: cached 層 9 / TypeManager 1 / delete 流 4 / 変更フィード 2 /
  lookup 系 4 / token 2 / wrapper 2 (+membership の message 固定 2・内部読取 2)
- **IA・HU を再アンカー** (round-32 の自分の修正がアンカーを消していた —
  監査の指摘どおり「63 本」は 3 本失効の在庫だった)。**HW は JA が包含するため退役**
- 新コントロール **JB〜JY (23 本)**、計 **85 controls**。**23/23 発火** (2,084 秒、
  復元 green まで機械確認)

### §55 締め

- フルスイート **6345 / 0** (Fresh 切替で既存テスト 2 本のスタブずれ →
  getGroupItemByIdFresh に更新して再走 green)
- 実機: round-33 WAR をデプロイし TCK Basics 3/3・Query 6/6 green
- runner: **85 controls** 在庫、全アンカー機械 dry-check 0 bad
- 主張の訂正: RELEASE_NOTES の型定義「初期構築前」限定を「view が答えて 0 行」に言い直し
  (0 行の扉は既知の盲点として明記) / cloud 部分発行の記述を文書単位の現実装に /
  オブジェクト取得 bullet をキャッシュ層込みに拡張
- 未コミット (依頼があるまでコミットしない)


## 56. 34 巡目 (並行手動レビュー) — 直した隣に在る同じ潰し (2026-09-01)

指摘は 6 件、すべて**私が閉じたと書いた腕のすぐ隣**。「一層上」を直した 32/33 巡の次に
潰していたのは、**同じメソッドの配線扉**と、**throw する DAO の下の bulk wrapper** だった。
6 件とも現物で確認して修正。

### 対応 (arm 表)

| # | 腕 | 直す前 | 直した後 |
|---|---|---|---|
| 1 | cached `getContent` の `nemakiCachePool == null` / `nonCachedContentDaoService == null` | `return null`（**閉じたと書いた catch の直上**。Fresh 側は今巡 throw にしたのに本流は据え置き） | throw。deleteObject の偽成功・lineage の誤 ORPHAN・参照剥がしの素通しは全部ここを通る |
| 2 | `getBulkDocuments` の行 skip とバッチ例外 | error 行 skip / 例外で**次バッチへ続行** → 短い Map | `not_found` / `deleted` **だけ** skip、他の error・doc も error も無い行・バッチ例外は throw。DAO 側の throw が初めて意味を持つ |
| 3 | `childrenNamesViewIsAlive` の `client == null` | `return true`（view は生きている） | throw。catch と同じ「判別不能なら拒否」をこの扉にも |
| 4 | Navigation の `maxItems` / `skipCount` / `depth` | 生 `intValue()`（2³¹→負、2³²→0。さらに `_maxItems * oversampleFactor` で二重 overflow） | `compareTo` で clamp（page 上限 10,000・skip は非負・depth は −1 を保存）。change feed が実機で学んだ形をそのまま |
| 5 | cloud の `> 0` 判定 | 発行は文書単位にしたが、戻りは **entity + companion + 関係の混合カウント**。entity が落ちて edge だけ載っても `published` | `lastEntityPublishFailureCount()`（呼び出し単位・ThreadLocal、`lastUnreadable*Count` と同じ作法）を publish service に新設し、**entity が載ったかどうか**で判定 |
| 6 | TypeManager の skip | null subtype / BaseId・ParentId 欠落 / 処理例外を warn+continue し、そのあと `initialized = true` | 3 腕とも throw。2 型合成より小さいだけで、型 diff が「居ない」と読む向きは同じ |

### 錠

- 新規・追加 **17 本**: cached 配線 2 / bulk 4 (含む「not_found は今までどおり skip」の
  コントロール) / probe 1 / clamp 6 / cloud 混合カウント 1 / TypeManager 2、
  さらに **Navigation は「clamp の挙動」と「呼び出し側が clamp を通ること」の両方**を測る
  (clamp を作っても呼ばれなければ守らない、という本批評の最古の教訓)
- 負のコントロール **KA〜KO (15 本)**。`JA` は今回の変更でアンカーが失効したため
  カウンタ判定の新形へ再アンカー。runner は **100 controls**、
  今巡分は **17/17 発火** (KH・KM は 1 度目 DID NOT FIRE → 錠を測れる形に直して再発火)

### 実機プローブが見つけた 7 件目 — clamp の下流が生の値を受けていた

項目 4 を「直した」あと、**挙動変更なので実機で 1 回確かめた**のが分かれ目だった。
`maxItems=2^32` が **objects 0 件 + hasMoreItems=true**（200 応答）で返る。
WAR にクランプが入っていることを class から確認した上で経路を追うと:

- `getChildrenInternal` の **legacy 分岐 (totalCount ≤ 500、つまり大半のフォルダ)** は
  クランプ後の `_maxItems` を使わず、**生の BigInteger を `compileObjectDataList` へ**渡していた
- その `CompileServiceImpl` に **`intValue()` の paging ブロックが 2 つ**あり、
  2^32 → `_maxItems = 0` → `subList(0, 0)` で**空ページ**、`hasMoreItems` だけ true

つまり項目 4 の修正は**oversampling 分岐しか覆っていなかった**。共有経路
(navigation / query / relationships が通る) である compile service 側に clamp を置き、
legacy 分岐はクランプ後の値を渡すように統一。`skipCount + maxItems` の int overflow も
同時に閉じた。実機再測定: 2^32 → **270 件 (hasMoreItems=false)**、通常のページングは不変。

**教訓**: source ロックは「clamp を呼んでいるか」を見ていたが、
**同じメソッドのもう一方の分岐が clamp を迂回している**ことは見ていなかった。
挙動変更を実機で 1 回叩く手順がこれを拾った。

### 今巡の私の事故 (4 件、いずれも錠・runner・実機が捕まえた)

1. **source ロックを無スコープで書き**、clamp helper 自身の安全な `intValue()` に当たって
   赤くなった。**修正ではなく防御に当たる錠**を書いた形で、呼び出し側の形に限定して解決。
2. cloud の判定を混合カウントから失敗カウンタへ移した結果、**既存 4 テストの fixture が
   「発行失敗」を表現できなくなった**（戻り値 0 で表現していた）。新契約
   (混合カウント + 失敗カウンタ) を忠実に模す helper に置き換え。
   `when()` で組んだら setUp の answer が null 引数で走り NPE — `doAnswer` に変更。
3. **cloud のモックテストで測れない腕にコントロールを向けた** (KH)。publish service を
   丸ごとモックするテストに対して publish service 実装を細工しても何も変わらない —
   runner の「発火せず = 保護していない」でしか見えない形。実物を駆動する錠
   (`anUnbuildableEntityIsCountedAsAPublishFailure`) を書いて向け直した。
4. **compile service の source ロックが細工の綴りを素通しした** (KM)。
   `Integer _maxItems = ...` という**旧宣言の綴り**を禁止していたが、細工は
   `int _maxItems = ...` と書くので当たらない。clamp 2 行の**ペアを厳密に 2 回**数える形に変更。

### §56 締め

- フルスイート **6366 / 0**
- 実機: 第 1 群の WAR をデプロイし、**TCK Basics 3/3・Query 6/6・Control 1/1**
  (Filing は既存の skip 2 のまま)。navigation の実測は
  `maxItems` = 3 / 100 / 10,000 / 2³¹−1 / 2³² で 3 / 100 / 270 / 270 / 270 件、
  通常ページングは不変
- runner **100 controls**、アンカー全件 dry-check 0 bad
- 第 3 群 (申告の訂正) も同時に反映: §55 の
  「`getContentsByIds` → throw」「cloud 完全腕は文書単位」「`getContent` を根で修正」
  の 3 点に訂正印。RELEASE_NOTES も配線扉・一括読み取り・ページング上限・型定義の
  部分欠落を追記
- 未コミット (依頼があるまでコミットしない)

## 57. 35 巡目 — 第 1 群の続き 6 件 + 第 2 群 7 件 + 第 3 群 (2026-09-01)

並行手動レビューが第 1 群の直後に 6 件、いずれも**同じ「片方の分岐」**を指摘。
第 2 群に進む前にそちらを閉じ、続けて第 2 群 7 件と第 3 群 (申告の訂正) を消化した。

### 第 1 群の続き (指摘 6 件、すべて現物確認)

| 腕 | 直す前 | 直した後 |
|---|---|---|
| `addSubTypesInternal` (組み立て本体) | null type / `buildTypeDefinitionFromDB` の null / 構築例外 / 子の null subtype を warn して return・continue、そのあと `initialized = true` | 4 腕とも throw。`addSubTypes` で閉じた 3 腕を一段内側で再建していた |
| `getTypesChildren` / descendants | `skipCount.intValue()` ×2・`maxItems.intValue()`・`depth.intValue()` | clamp (page 10,000・skip 非負・depth −1 保存)。2³² → 0 → **空の型一覧** |
| `SolrQueryProcessor` | `Math.max(0, maxItems.intValue())` | clamp。`Math.max` は**切り詰めの結果**を丸めるだけで切り詰め自体は防がない |
| probe `childrenNamesViewIsAlive` | `getDatabaseInfo()` null / `docCount` null → true | throw。client==null の扉だけ閉じて判別不能の null 戻りが残っていた |
| `getBulkDocuments` | 返った行の形は閉じたが、**要求 ID に対して行自体が無い**場合は Map から欠けたまま | CouchDB は要求キーに必ず答える (不在は not_found 行) ため、行が無い = 応答が途中で切れた → throw |
| compile の `clampMaxItems` | 非正 → 0 (空ページ)。Navigation は同じ入力を 100 | 既定ページ 100 に統一。query / relationships が生の 0 を渡す経路で空ページが残っていた |

### 第 2 群 (7 件)

| # | 件 | 直した内容 |
|---|---|---|
| A1 | `document-entity` の dead letter に retry 腕が無い | dispatch に腕を追加。qualifiedName から object id を取り再発行し、**entity 失敗カウンタが 0 のときだけ**letter を消す。対象が実在しない場合は letter を消す (永久に失敗し続けるため)。従来は毎回 `Unsupported` で失敗計上され、基線経由で復旧した後も残っていた |
| A2 | RSS トークンの process-local キャッシュ | **キャッシュを廃止**し読み抜きに。TTL では窓を縮めるだけで、失効の非伝播は構造として残る。未配線時は「無効なトークン」ではなく throw |
| A3 | containment の「GUID 記録 = 外部に実在」 | 検知は据え置き (全関係の読み戻しは毎周期には高すぎる) が、**修復手段**を新設: `forgetRecordedRelationshipGuids()` で記録を落とすと次回同期が全辺を作り直す。コメントも「検知しない/修復できる」に |
| A4 | lookup 5 本の catch→null | `getPolicy` / `getItem` / `getUserItem` / `getUserItemById` / `getGroupItem` → throw |
| A5 | 保持期限スキャン 2 本の catch→空 | throw。併せて **scheduler が失敗を `incrementFailed()` で記録** (移行ログが「対象なしの完走」に見えないように) |
| A6 | startup 判定のスレッド名ヒューリスティック | `StartupPhase` を新設し、**プロビジョニングの窓を宣言**する形に (`DatabasePreInitializer` の try/finally)。既定は**厳格側**。"main-worker-3" のような名前が猶予を得る/プロビジョニングが猶予を失う、の両方が消えた |
| A7 | DirectorySync の私設 `removeUserFromAllGroups` | 正規の `deleteUser` に置換し私設コピーを削除。**さらに group 側は剥がし処理を一切通しておらず** (bare delete)、`deleteGroup` に置換 — 指摘は「二重実装」だったが、実際には**片翼が無実装**だった |

### 第 3 群 (申告の訂正)

§55 の 3 点は §56 で訂正印を入れ済み。今巡は RELEASE_NOTES を実装に合わせて更新
(配線扉・一括読み取りの不在判定・ページング上限・型定義の部分欠落・RSS の読み抜き)。

### 錠と runner

- 新規・追加の錠: 型 6 / paging 9 / probe 2 / bulk 1 / identity 6 / retention 2 /
  RSS 2 + store 化に伴う既存 16 本の配線替え / startup 5 / directory 2 /
  dead letter 2 / GUID 修復 1
- 負のコントロール **KP〜LJ (21 本)**、runner は **121 controls**、**21/21 発火**
  (LE は 1 度目 DID NOT FIRE)

### 今巡の私の事故 (3 件、いずれも runner か実機が捕まえた)

1. **静的既定値を実行時に測ろうとした** (LE)。`StartupPhase` の既定 false を
   `isProvisioning()` で確認する錠にしたが、同クラスの他テストの `@AfterEach end()` が
   先に静的変数を書き換えるため、**実行順によっては初期値を観測できない**。
   runner の「発火せず = 保護していない」でだけ見える形。初期化子を source で固定した。
2. **A6 が既存テスト 2 本の前提を壊した**。どちらも旧ヒューリスティック
   (「main という名前のスレッド = 起動時」) に依存していた:
   `createStaysLenientDuringStartup` はスレッド名で猶予を取りにいっていたので
   **窓を宣言する形**に、`aProjectionFallsBackToAReadById` は未 stub の mock が NPE を出し
   それが猶予で null に化けることに依存していたので、**「文書が無い」を明示的に模す**形に。
   どちらも旧実装の穴に寄りかかった fixture だった。
3. NotFoundException の生成に `okhttp3.Response` が要ることに気づかず 2 回コンパイルを
   落とした (最終的に「結果が null の応答」で不在を模した)。

### 締め

- フルスイート **6394 / 0**
- 実機: Group-2 WAR をデプロイ。**起動は clean** (新しい拒否ログ 0 件 —
  StartupPhase の窓が provisioning を覆えている)、TCK Basics 3/3・Query 6/6・Control 1/1。
  実測: 型一覧 `maxItems=2³²` → **6 型** (以前は空)、children `maxItems=0` →
  **既定ページ 100 件** (以前は空ページ)、`maxItems=-1` は OpenCMIS 側が 400 で拒否 (従来どおり)
- 未コミット (依頼があるまでコミットしない)


## 58. 36 巡目 — catch の隣の扉、3 度目 (2026-09-01)

指摘 3 件。いずれも**前巡で catch を閉じた当のメソッド**に、同じ失敗が別の扉から
入っていた。29 巡目の `getGroupItems` と同型が、これで 3 度目になる。

| 腕 | 直す前 | 直した後 |
|---|---|---|
| `getUserItemById` | 例外は throw。**null result / 空 rows は null**、非 Map 行は continue、必須欠落は null。認証とディレクトリ同期がこの null を「居ない」と読む。**同じ view を読む `getUserItems` は既に拒否していた** — 同じ答えに 2 通りあった | 4 腕とも throw。view が「答えて 0 行」だけが不在 |
| `getGroupItemByIdInternal` | 無応答は null (必須欠落と外側 catch は 33 巡で閉じ済み) | throw |
| 保持期限スキャン 2 本 | catch は throw、**無応答は空リスト**。scheduler は「候補 0 件で完走」と記録 | throw |
| 型一覧 / query の非正 maxItems | `clampPage` / `clampQueryPage` が 0 (空一覧・空ページ) を返す。compile / Navigation は同じ入力を 100 | 既定ページ 100 に統一。**同じ入力に 3 通りの答え**があった |

### 錠と runner

- 錠 8 本追加。負のコントロール **LK〜LQ (7 本)**、runner は **128 controls**、7/7 発火
- **LQ は 1 度目 DID NOT FIRE**: 既存テストが catch を踏んでおり、必須欠落の腕を
  測っていなかった。その腕を駆動する錠 (`anUnusableExistingUserRefuses`) を書いて向け直した。
  「catch のテストがあるから他の腕も測れている」は成り立たない、という同じ教訓の再演

### 締め

- フルスイート **6402 / 0**
- **コミット方針を変更** (依頼による): `master` から `fix/v34-fail-closed-reads` を切り、
  検証が済んだ単位でコミットする。現在 4 コミット
  (コード+テスト / 負のコントロール / 文書 / 本節の 4 件)。push はしていない
