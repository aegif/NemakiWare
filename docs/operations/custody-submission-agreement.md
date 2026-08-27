# Submission agreement — 移管の取り決め (P3-4)

**この文書は取り決めの雛形であって、実装の説明ではない。** 本製品が実装しているのは
状態機械と受領証の検査であり、**下記の各項目について「どうするか」を決めるのは
送り手と受け手の合意**である。合意が無いまま移管を始めると、最初の失敗のときに
「どちらの責任か」を決める根拠が無い。

ロードマップ P3-4 が「失敗・再送・重複取込・部分受入・先方 AIP 再生成の扱いを
submission agreement として明文化」と書いているのはこの文書のことである。

---

## 0. 本製品が既に決めていること (交渉の余地が無い側)

合意で上書きできない。これらは記録の性質に関する判断であって、運用の都合ではない。

| | 本製品の挙動 |
|---|---|
| custody が渡る条件 | **引き渡しが証拠連鎖に記録できたときだけ**。記録できなければ渡らない (fail-CLOSED)。capture の逆で、capture は既に起きているので拒否できないが、custody は**まだ起きていない**ので拒否の代償は再試行だけである |
| 受領証の最低条件 | `sipDigest` が**こちらが送った package を指すこと**。加えて `submissionId` / `aipId` / `aipChecksum` / `receivingAgent` / `receivedAt` が全部揃うこと。1 つでも欠ければ検証に進まない |
| 未知の検証結果 | **成功として扱わない**。`PASSED` / `PASS` / `VALID` / `SUCCESS` / `ACCEPTED` / `OK` 以外は不成功。**語彙は増やさない** — 受け手の語彙が違うときは接続層が写像し、生の語は `reportedOutcome` に残す (設計 §13.1) |
| 署名 | **必須ではない**。署名が無い受領証は「認証されていない陳述」として保存し、そう明記する。鍵があるときだけ検査し、鍵が無いのは「検査していない」であって不正ではない |
| 保存された状態 | 読み戻すとき履歴を検査する。1 フィールド編集して `RECEIPT_VERIFIED` を名乗る行は**読んだ時点で拒否**される |

---

## 1. 決めなければならないこと

### 1.1 失敗 (送信が届かない / 取込が落ちる)

- **どこまでが送り手の責任か。** 本製品は package を作り、bag に包み、送るところまで
  を行う。受け手の取込が落ちたとき、送り手は何を根拠に再送するのか
- **再送の期限。** いつまで待って再送するか。待たずに再送すると 1.3 になる
- **どちらが検知するか。** 受け手が失敗を通知するのか、送り手が問い合わせるのか

> 本製品側の状態: `SENT` のまま動かない。`FAILED` へ落とすのは運用判断であり、
> 自動では落ちない (落ちたことにすると、実は届いていた場合に記録が嘘になる)。

### 1.2 再送

- **同じ `submissionId` を使うのか、新しいものを振るのか。** 本製品の
  `receiptDigest` は `submissionId` を含むので、同じ id での再送は**同じ digest** に
  なる。別 id なら別の引き渡しとして記録される
- どちらが正しいかは合意次第で、**本製品は決めない**。決めないことを決めている

> **同じ digest であることと、追記が 1 回であることは別である。**
> 本製品は追記の前に、その digest を持つ entry が既に無いかを見る。見つかれば
> 追記しない。ただしこれは**best-effort であって保証ではない**:
>
> - 走査は直近 **500 件**まで。満杯なら「読めていないのは新しいほう」と warn を出すが、
>   その先に在る entry は見えない
> - **check-then-append である。** 2 つのノードが同時に再試行すると、両方とも
>   「無い」を見て両方が追記し得る。台帳の CAS は同一 sequence を防ぐだけで、
>   同一 digest に対する一意制約は無い
> - 台帳が読めなかったときは追記を試みる (「読めなかったから見つからない」を
>   「無い」と扱わない)
>
> したがって **1 回の引き渡しに `CUSTODY_RECEIPT` が 2 本付くことはあり得る。**
> 突き合わせの手順を合意に含めること。実装: `CustodyLedgerRecorder.alreadyRecorded`。

### 1.3 重複取込

- 受け手が同じ package を 2 度取り込んだとき、**AIP は 1 つか 2 つか**
- 2 つになるなら、受領証はどちらの AIP を指すのか
- 送り手側では、受領証が 2 通来ても**先に検証したものが記録**され、後から来たものは
  拒否される (custody が渡った後の受領証は「終わった受け渡しを書き換える」ため)

### 1.4 部分受入

- package の一部だけが受け入れられたとき、**それは受入か否か**
- 本製品は `verificationOutcome` を二値でしか読まない。`PARTIAL` は
  **成功として扱わない**ので、部分受入を成功と呼ぶ合意があるなら、
  受け手が返す語をこちらの語彙に合わせる必要がある
- **RODA の `PARTIAL_SUCCESS` はここに落ちる** (2026-08-27 実測)。`Report.pluginState` を
  `verificationOutcome` に入れる限り、この規則と受け手の語彙は一致する。
  **Archivematica 1.18.0 の語彙は採取した** (下記 3 / 設計 §12)

### 1.5 先方の AIP 再生成

- 受け手が AIP を作り直したとき、`aipId` / `aipChecksum` は変わるか
- 変わるなら、**こちらが持っている受領証は古くなる**。本製品はそれを検知しない
  (先方の artefact をこちらは一度も見ていない)
- 再生成の通知をするか、しないか。しないなら、こちらの記録は
  「あの時点でそう報告された」以上のことを言わない — それは**元々そうである**

### 1.6 保持と削除

- 受け手はいつまで保持するか。送り手はいつ元を消してよいか
- 本製品の `LOCAL_DISPOSITION` は custody が渡った**後**にしか到達しない。
  ただし到達したこと自体は「先方が今も持っている」証拠ではない

### 1.7 鍵

- 受領証に署名するか。するなら**どの鍵で、どうやって送り手に渡すか**
- 署名対象は本製品が固定した正規形 (`ReceiptSignatureVerifier.canonicalForm`) である。
  受け手はこの文字列に署名する必要があり、**それを合意するのがここ**
- **正規形に入る検証結果の語は、受け手が出した語そのもの**である (2026-08-27)。
  受け手の語彙が本製品と違うとき、接続層は写像するが、**署名は写像前の語を覆う** —
  先方はこちらの語彙を知らないので、写像後に署名を求めることはできない。
  受領証は両方を持つ (`verificationOutcome` = 写像後、`reportedOutcome` = 生)。
  **したがって写像後の語は先方の署名では覆われない** — 検めるなら署名された生の語から
  再導出すること (設計 §13.1)
- 鍵の失効・更新の手順

---

## 2. 本製品が言わないこと

- **移管したから安全になった、とは言わない。** `CUSTODY_TRANSFERRED` は
  「引き渡しを記録し、その受領証を検証した」であって、
  **先方が今も持っている**ことではない
- **受け手の検証が十分だった、とは言わない。** 受領証は先方の陳述である
- **bag に包んだから相互運用できる、とは言わない。** **bag 経路では** METS は読まれず、
  構造も尊重されない (E-ARK 経路で受け取る先方なら読まれる — 下記 3)。
  **ただし「payload の中の 1 ファイルのまま残る」とも言わない** — Archivematica の
  `automated` 設定は package を展開し、SIP のツリーが AIP の `objects/` に入る
  (2026-08-27 実測)。**展開された ≠ 理解された。**
- **取り込まれたから保持される、とは言わない。** RODA 6.3.0 で実測したところ、
  SIP→AIP プラグインが作った AIP は `INGEST_PROCESSING` 止まりで、
  受入承認まで進んでいない (その先の workflow は走らせていない)。
  Archivematica 1.18.0 では automated processing の末に AIP が `UPLOADED` になったが、
  それも「今も持っている」証拠ではない
- **bag がどこでも取り込まれる形だ、とは言わない。** payload manifest は **SHA-512 と
  SHA-256 の 2 本**。**AM 1.18.0 の `zipped bag` はこの形で AIP になった** (2026-08-27)。
  **RODA 6.3.0 の `BagitToAIPPlugin` は rollback する**ので、
  **RODA には bag ではなく SIP を渡すこと**

---

## 3. 実機受入試験 — RODA 6.3.0 と Archivematica 1.18.0 (2026-08-27)

**やって分かったこと** (詳細は設計 §10):

| | 結果 |
|---|---|
| **E-ARK SIP を直接** (`EARKSIP2ToAIPPlugin`) | **AIP object が作られる**。本文が `representations/rep1/data/` に入り、METS の `OBJID` が AIP に載る |
| **bag** (`BagitToAIPPlugin`) | manifest **1 本**の bag なら AIP object が作られた。**現行の出荷形 (2 本) は rollback する** (2026-08-27 実測) — RODA には bag を送らず SIP を送ること。なお bag 経路では METS は読まれない (RODA の bag 経路では payload はそのまま置かれる。展開するかは受け手の設定次第で、AM は展開した — §12) |
| 旧版 `EARKSIPToAIPPlugin` (E-ARK SIP 1.x) | 同じ package を**拒否**する。**プラグインの指定を間違えると「非対応」に見える** |
| 我々の `metadata/preservation/premis.xml` | **生成された AIP の PREMIS metadata に無い**。在るのは RODA 自身の event 2 件だけ。値が非 PREMIS のフィールドへ写されたかは未調査 |
| 我々の `ers.der` (タイムスタンプ証跡) | **`metadata/other` なら取り込まれ、残る** (ただし AIP では `metadata/descriptive/ers.der` へ移されている)。**`addPreservationMetadata` で出すと package ごと rollback する** — その呼び出しは METS の `<digiprovMD>` に宣言を書き、RODA はその枠の中身を PREMIS として読むためである (フォルダ名ではない)。本製品は 2026-08-27 に `addOtherMetadata` へ変えた。**測ったのはスタブの DER で、本物の ERS では未測定** |
| AIP の `state` | `INGEST_PROCESSING`。受入承認された状態ではない |

> **先方と話すときの実務**: 「E-ARK SIP を受けられますか」だけでなく
> **「どのプラグイン / どの profile 版で受けますか」**まで確認すること。
> 同じ製品の同じ版が、E-ARK 取込を 2 版ぶん持っていた。

**Archivematica 1.18.0** (詳細は設計 §12)。`automated` processing。AM の AIP であって
E-ARK AIP ではない。

| | 結果 |
|---|---|
| 出荷形 bag (manifest 2 本)、`zipped bag` | `Verify bag` COMPLETE、AIP `UPLOADED` |
| 同じ E-ARK SIP zip、`zipfile` | AIP `UPLOADED` — **BagIt は必須ではない** |
| 同じ SIP zip、`standard` | `FAILED` (`Failed compliance.`) — その type はディレクトリを期待する |
| 同じ SIP の展開ディレクトリ、`standard` | AIP `UPLOADED` |

**まだやること**:

1. ~~受け手が実際に返す `verificationOutcome` の語を採取する~~ **RODA については採れた**
   (2026-08-27)。**RODA の v2 API 26 本に、受領証と分かるリソースは無い** (job report や投入時の応答が代役になり得るかは未検証)。
   接続層が組み立てるなら材料は 2 つで、**どちらを選ぶかで結果が変わる**:

   | 出所 | 値 | `reportsSuccess()` との相性 |
   |---|---|---|
   | `Report.pluginState` | `SUCCESS` / `PARTIAL_SUCCESS` / `FAILURE` / `RUNNING` / `SKIPPED` | **合う**。`SUCCESS` は通り、`PARTIAL_SUCCESS` は通らない (1.4 と一致) |
   | 同じ `Report` の `outcomeObjectState` | `CREATED` / `INGEST_PROCESSING` / `UNDER_APPRAISAL` / `ACTIVE` / … | **壊れる**。受入完了の `ACTIVE` が語彙に無い |

   **接続層を書くときの罠**: RODA の `TransferredResource` には **checksum のフィールドが
   無く**、`Report` が投入物に紐づくのは名前 (`sourceObjectId` / `sourceObjectOriginalName`)
   である。応答フィールドだけで組み立てると `sipDigest` をこちら側の記録から埋めるしかなく、
   照合が自分の値を自分と比べる形になって §0 の「受領証の最低条件」が空回りする。

   **ただし逃げ道は在る**: `GET /api/v2/transfers/{uuid}/download` と AIP 側の
   `download/submission` が**先方の持っているバイト列**を返す。接続層はそれを取って
   自分でハッシュすること。**未検証**: そのバイト列が送ったものと同一か、
   受領証を作る時点まで transferred resource が残っているか。

   **Archivematica 1.18.0 の語彙は採取した** (2026-08-27、設計 §12)。
   Dashboard の transfer/SIP `status` は `COMPLETE` / `FAILED` (ほかソース上
   `REJECTED` / `USER_INPUT` / `PROCESSING`)、SS の package は `UPLOADED`、
   `check_fixity.success` は boolean。**どれも `reportsSuccess()` に無い** —
   正常終了の `COMPLETE` をそのまま入れると拒否になる。接続層は写像が要る。
   AIP checksum は pointer file の PREMIS `messageDigest` (AIP 7z のもの)。
   送った bag の SHA-256 は AIP 内 `metadata/transfers/.../manifest-sha256.txt` に残った。
   **受領証を実際に組み立てて検証する経路は未実装。** 署名も無い
2. ~~Archivematica で同じことを測る~~ **測った** (2026-08-27)。出荷形 (manifest 2 本) の
   `zipped bag` は `Verify bag` を通り AIP `UPLOADED`。同じ E-ARK SIP は `zipfile` でも
   AIP になるので **BagIt は必須ではない**。`standard` に zip を渡すと FAILED
   (ディレクトリを期待する)。展開ディレクトリなら `standard` でも AIP になる
3. 受領証の形式と署名の有無を確認し、1.7 を埋める
4. 上記 1.1〜1.6 を、実際に落として確かめる (合意は落ちたときにしか効かない)

設計: [`docs/design/p3-4-custody-transfer.md`](../design/p3-4-custody-transfer.md)
