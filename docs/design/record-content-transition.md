# 設計 — 内容を失う側の遷移を台帳に載せる（Phase 3 の残り 6 経路）

2026-09-21。計画 [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md)
§16 Phase 3 の「未」。E1 の ADR は [`adr-e1-durable-commitment.md`](adr-e1-durable-commitment.md)。

**2026-09-22 に台帳側を実装した（sub-batch 1）。** `SubjectKind.RECORD_CONTENT_TRANSITION`、`RecordContentTransitionV1`、
`CommitmentKind.RESTORED`、`RecordContentStateRecorder.recordTransition / priorFor / abandon`、journal の
`latestRecorded / abandon`、6 経路の配線（W10 / W11 / W12 / W13 / W14 / `deleteContentStream`）、E1 の錠 15 本、
crash test。**package 側（exporter が遷移文と prior を出す、verifier P1 の `content binding` / `transition continuity`、
仕様 §10 / §4.2、schema の `TRANSITION_PRIOR_NOT_IN_PACKAGE`）は sub-batch 2 で、それまで残件 R67。**
所有者の決定（2026-09-21）: 新 kind／digest を nullable にしない／`priorContentDigest` は台帳から写す／W12 は 2 行／
W11 は `RESTORED` — 採用。W14 はこのバッチでは削除経路に確かめさせない。`bytesNow: UNKNOWN` のまま。crash test は
実装と同じバッチで書く — すべて写した。

**実装して決まったこと（設計から動いた点）:**

- `Transition` に **`RESTORED` は無い**（§1.4 のとおり W11 は state statement）。`BytesNow` に **`LOCAL` は無い** —
  どの遷移も bytes を LOCAL に残さない（残すのは W11 で、それは state statement）。**生産者の無い値は enum に置かない**
  （錠 `everyBytesNowHasAProducer` / `everyTransitionSaysWhereTheBytesAre`）
- **`abandon`** を journal に足した — 「書いたが取り消した」形は E1 の 9 経路に無く、W12 にだけ在る（disposition が拒否され
  cold の object を消し戻した／ローカル削除に失敗して消し戻した）。取り消しが**確かめられた**ときだけ行を statement 無しで
  閉じる。消し戻しに失敗したら行は開いたまま（blob が残っているかもしれない、を一覧に残す）。錠 `abandonFollowsAVerifiedUndoOnly`
- W11 の digest は **DAO が書き戻す pass で取る**（`DigestInputStream` を PUT の下に置く。`ContentDaoService.RestoredBytes`）。
  CouchDB が保存した長さと数えた bytes が違えば digest は null（ADR の「宣言より短い書き込みに digest は無い」）で、行は開いたまま。
  archive に binary が無ければ `RestoredBytes.NOTHING`（既知の「何も書いていない」）で行は abandon。DAO が報告できない実装は
  null（「分からない」）で行は開いたまま — 3 つは別の値
- `deleteContentStream` は `CONTENT_REMOVED` の **1 行**（`bytesNow: ARCHIVE_DB` — 中の `deleteAttachment` が W10 として
  archive DB へ複製する）。`deleteDocument` の版ごとの `deleteAttachment` が `ARCHIVED` の 1 行
- **見つけた欠陥（直した）**: journal の `statementFor` は「最後の行 = 最新」と読んでいたが、行の id は乱数 UUID なので
  順序は時刻と無関係。同じ版が 2 度書かれた（W3 / W7 / W9）package は古い digest を出荷しえた。**最新は台帳 sequence の最大**
  （`latestRecorded`）。錠 `TheNewestStatementIsByLedgerSequenceTest`。旧の錠は「最後の行を取る」というコードの事実を
  固定していただけで、正しさを測っていなかった
- **prior の carry-forward**: 最新の記録が遷移文なら、その `priorContentDigest / priorStatementEntrySequence` をそのまま写す
  （複製も cold 移送も bytes を変えないので、2 つ目の遷移も最後に記録された state を指す）

---

## 0. 何が問題か（コードで確かめた事実）

| 事実 | どこ |
|---|---|
| `RecordContentStatementV1` は `contentDigest` を**正規表現で必須**にしている。null も空も拒否 | `RecordContentStatementV1.java:92` |
| したがって「内容が無くなった」「内容が別の場所へ移った」は**この型では表現できない** | 同上 |
| 仕様 §5.3 にも「不在」の概念が**無い** | `evidence-profile-v1.md` §5.3（grep 0 件） |
| 台帳 entry には `subjectKind`（enum 名の STRING）という**判別子が既にある** | 仕様 §6、`EvidenceLedgerEntry.SubjectKind` |
| verifier P1 の `content binding` は `statement.get("contentDigest")` を読んで payload と比べる | `RecordLedger.java:119` |
| **W12（cold MOVE）のローカル削除だけは既に台帳に在る** — `DISPOSITION` 行として | `DispositionRecorder.Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE`（唯一の値） |
| E1 の錠は棚卸しの **14 経路**と `WriteKind` の本数を突き合わせている | `E1LeavesNoSilentGapTest:585` |

未記録の遷移は 5 つ + 1 つ:

| # | 遷移 | bytes はどこへ | 今の記録 |
|---|---|---|---|
| W10 | アーカイブ（削除） | archive DB へ**複製** | 無し |
| W11 | 復元 | 本番へ **PUT**（受け取る bytes が在る） | 無し |
| W12 | cold へ COPY / MOVE | 外部保管へ書く。MOVE ならローカル削除 | **MOVE のローカル削除だけ** `DISPOSITION` |
| W13 | アーカイブの物理削除 | archive 行ごと消える | 無し |
| W14 | W13 を cold 化済みの文書に | cold blob が**孤児化**（消えない） | 無し |
| — | `deleteContentStream` | 版から内容が**無くなる** | 無し |

---

## 1. 決めたこと

### 1.1 新しい kind。既存の statement に nullable を足さない

**`SubjectKind.RECORD_CONTENT_TRANSITION`** を足し、statement は別の型
**`RecordContentTransitionV1`** にする。

**`RecordContentStatementV1.contentDigest` を nullable にする案は採らない。** 理由は 2 つ。

- verifier P1 の `content binding` と、`contentDigest` を前提にする読み手すべてが、
  null を「digest が壊れている」と読むか「無い」と読むかを**それぞれ勝手に決める**ことになる。
  読み手の数だけ解釈が生まれる形は、この木で何度も踏んだ「一つの主張に多くの出口」そのもの
- 「内容の状態」と「内容の**遷移**」は主語が違う。前者は「この版の bytes はこれ」、
  後者は「この版の bytes に**これが起きた**」。同じ型に押し込むと、片方の必須欄が
  もう片方では意味を持たなくなる

### 1.2 `RecordContentTransitionV1` の欄

| field | 型 | 意味 | 必須 |
|---|---|---|---|
| `repositoryId` / `objectId` / `versionObjectId` | STRING | V1 と同じ。`versionObjectId` は**不変の版キー** | 必須 |
| `transition` | STRING（enum 名） | `ARCHIVED` / `RESTORED` / `COPIED_TO_COLD` / `MOVED_TO_COLD` / `ARCHIVE_DESTROYED` / `ARCHIVE_DESTROYED_LEAVING_COLD_BLOB` / `CONTENT_REMOVED` | 必須 |
| `bytesNow` | STRING（enum 名） | 遷移**後**に bytes が在る場所: `LOCAL` / `ARCHIVE_DB` / `COLD` / `NONE` / `UNKNOWN` | 必須 |
| `priorContentDigest` | STRING or **NULL** | 遷移**前**の digest。**台帳に先行する statement が在るときだけ**そこから写す。無ければ NULL | 任意 |
| `priorStatementEntrySequence` | LONG or NULL | `priorContentDigest` の出所の entry。digest だけ写して出所を書かない形は禁止 | `priorContentDigest` と対 |
| `recordedAt` | STRING | 台帳へ書いた時刻 | 必須 |

**`priorContentDigest` は「今計算する」のではなく「台帳から写す」。** 遷移の瞬間に
bytes を読み直して digest を取る案は、W13 / W14 / `deleteContentStream` では
**読む相手がもう無い**か、読めても「消す直前に読んだ」という別の観測になる。
台帳に無ければ NULL と書く — 「知らない」を「無い」と同じ値にしない（この木の規則）。

**`bytesNow: UNKNOWN` を許す。** W14 は cold blob が残るかどうかを**削除経路が確かめない**
（`LongTermStorageAdapter.delete` は呼ばれない）。確かめていないものを `COLD` と書くと、
削除経路が変わった日に嘘になる。

### 1.3 W12 MOVE は 2 行になる。重複ではなく、事実が 2 つある

`DISPOSITION`（`LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE`）は**退避の認可と実施**の記録で、
`DispositionRecorder` が retention の設定を digest に含めている。
`RECORD_CONTENT_TRANSITION`（`MOVED_TO_COLD`, `bytesNow: COLD`）は**内容の遷移**の記録。

同じ出来事に 2 行入るが、**問いが違う**。「誰が何を根拠に許したか」と
「bytes は今どこか」。片方に寄せると、もう片方の問いに答えられなくなる。
仕様には「W12 MOVE では両方が在ること」を**期待として書く**（片方だけなら不整合）。

### 1.4 W11 復元は**この型ではない**

復元は本番へ bytes を PUT する — **受け取る bytes が在る**ので、ADR 決定 2 のとおり
1 パスで digest を取れる。したがって W11 は `RecordContentStatementV1`（既存）で記録する。
`commitmentKind` は `CAPTURED` でも `UPDATED` でもない — **「アーカイブから戻した」は
第 4 の kind**（`RESTORED`）にする。`CAPTURED` と書くと「初めて保存した」と読まれ、
`UPDATED` と書くと「置き換えた」と読まれる。どちらも偽。

**これは V1 の enum に 1 値足す変更**なので、V1 の canonical form とベクタに影響する。
Phase 2 のベクタに `RESTORED` の 1 件を足し、3 実装で一致を取り直す。

### 1.5 verifier P1 の扱い

`RECORD_CONTENT_TRANSITION` の statement を持つ package では:

| 検査 | 答え | 理由 |
|---|---|---|
| `content binding` | package に payload が**無ければ** `NOT_PRESENT`（「payload を主張していない」） | 遷移文は bytes を主張しない |
| 同上 | package に payload が**在れば** `FAILED` | 「内容は無くなった／移った」と言う statement と payload の同梱は矛盾 |
| **新設** `transition continuity` | `priorContentDigest` が非 NULL なら、`priorStatementEntrySequence` の entry の `contentDigest` と一致すること | 写した値が出所と食い違うのは改変 |
| 同上 | `priorContentDigest` が NULL なら `NOT_PRESENT` | 知らないと言っている。`FAILED` にしない |

**`INDETERMINATE` に落とす方向を既定にしない**が、`UNAVAILABLE` になるのは
「出所の entry が package に無い」ときだけ（`TRANSITION_PRIOR_NOT_IN_PACKAGE`）。

### 1.6 記録に失敗したとき

E1 と同じ **耐久 intent 行**（案 B）。`ContentWriteJournal` の `WriteKind` は既に
W10〜W14 と `CONTENT_REMOVED` を持っている（**未配線なだけ**）。open → close の形もそのまま。
違いは close 時に書く statement の型だけ。

---

## 2. 決めていないこと（実装前に決める）

| 未決 | なぜここで決めないか |
|---|---|
| **crash test** | **決定: 実装と同じバッチで書いた**（`TransitionsLeaveNoSilentGapTest`: 台帳拒否で行が開いたまま／prior は写す／carry-forward／journal に訊けなければ prior は null／abandon は確かめた取り消しだけ／abandon できなければ開いたまま） |
| E1 の錠の **14 → 何本** | **決定: 棚卸し 14 経路 + `deleteContentStream` = `WriteKind` 15 本、配線済み 15 本**（`onlyTheWiredPathsClaimToBeRecorded` が製品 4 ファイルから `WriteKind.X` を数える） |
| `ARCHIVE_DESTROYED_LEAVING_COLD_BLOB` を W14 の**削除経路に確かめさせる**か | **決定（所有者）: このバッチでは確かめさせない**（外向きが増える）。`bytesNow: UNKNOWN` |
| `bytesNow: UNKNOWN` を運用文書でどう見せるか | 運用文書 §O5-4 の行「cold blob が残っているかを削除経路が確かめること」— **やらないこと**として。「不明」は「無い」でも「在る」でもない |

---

## 3. 錠と control（実装時に写すもの）

- **型の錠** `theTypeHasNoContentDigest` / `thePriorIsPaired` / `nullsAreWrittenNotOmitted`: `RecordContentTransitionV1` は
  `contentDigest` 欄を**持たない**。`priorContentDigest` を出所無しで持てない。null は書く（省略しない）
- **配線の錠** `E1LeavesNoSilentGapTest#onlyTheWiredPathsClaimToBeRecorded`: 製品 4 ファイル（`ContentServiceImpl` /
  `AttachmentServiceDelegate` / `ArchiveServiceDelegate` / `RetentionScheduler`）の `WriteKind.X` ＝ 宣言 15 本 ＝ 計画の
  「配線済みは 15 本」。`everyTransitionSaysWhereTheBytesAre`: 遷移ごとの `bytesNow` が設計の表どおり、呼び出し側が 1 か所
- **kind の錠** `aTransitionIsChainedUnderItsOwnKind`: 遷移文は `RECORD_CONTENT_TRANSITION` で chain に入る
- **写しの錠** `thePriorIsCopiedFromTheLedgerNotComputed` / `aSecondTransitionCarriesThePriorForward` / `noPriorWhen…`
- **順序の錠** `TheNewestStatementIsByLedgerSequenceTest`: 行順が逆でも sequence 最大が最新
- **矛盾の錠**（`MOVED_TO_COLD` と `DISPOSITION` の対）: **sub-batch 2（verifier）で**。R67
- **control**: MK3 W14 の `UNKNOWN` を `COLD` に／ML3 W11 を `CAPTURED` に／MM3 prior を写さない／MN3 遷移文を
  `RECORD_CONTENT_STATE` で chain に入れる／MO3 取り消し未確認で abandon／MP3 E1 の宣言から 1 本落とす／
  MQ3 abandon が statement を書く／MR3 DAO が書き戻しを報告しない（既定の null に戻す）／IG3 順序を sequence 最小に

---

## 4. この設計が主張しないこと

- 遷移文が在っても、**遷移前に bytes が改変されていなかった**ことは言えない
  （先行文が在れば、そこからの連続性までは言える）
- `bytesNow: COLD` は「cold に書いた」であって「cold から読める」ではない
- **6 経路が全部これで閉じる**とは言わない。W14 の孤児化は削除経路が確かめない限り `UNKNOWN`
