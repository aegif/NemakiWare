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
| **BagIt (`zipped bag`) 接続層** | **未**。Archivematica は E-ARK SIP を直接取り込めない (転送 type 8 種に相当が無い) ので必須。`gov.loc:bagit` は commons-ip2 経由で既にクラスパスに在る |
| **署名検証** | **未**。先方の鍵素材の受け渡しが submission agreement 側の話 |
| **永続化** (transfer の store) | **未**。現状はメモリ上の型のみ |
| **fail-closed を強制する呼び出し元** | **未**。`recordVerifiedReceipt` の戻り値を読んで `CUSTODY_TRANSFERRED` へ進む/進まないを決めるコードが無い。型は在るが規則は誰も執行していない |
| **スレッド安全性** | **未**。`state` / `receipt` / `history` は非同期化で、`advance` は check-then-act。呼び出し元が無いので現時点で実害は無いが、"workflow object" と説明する以上は同期か明記が要る |
| **`reportsSuccess()` の語彙を実機で確認** | **未**。`PASSED/PASS/VALID/SUCCESS/ACCEPTED/OK` は**こちらが決めた綴り**で、RODA / Archivematica が実際に何を返すか照合していない。未知語は成功ではないので**外れても fail-closed** (受け入れてしまうのではなく、正当な受領証を拒否する) が、そのままでは使えない。実機受入試験で語彙を固定すること |
| **submission agreement の明文化** (失敗・再送・重複取込・部分受入・先方 AIP 再生成) | **未** |
| 実機受入試験 | **未**。RODA は arm64 で起動することだけ確認済み |
