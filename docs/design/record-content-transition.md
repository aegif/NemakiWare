# 設計 — 内容を失う側の遷移を台帳に載せる（Phase 3 の残り 6 経路）

2026-09-21。計画 [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md)
§16 Phase 3 の「未」。E1 の ADR は [`adr-e1-durable-commitment.md`](adr-e1-durable-commitment.md)。

**これは設計であって実装ではない。** ここに書いた型・kind・検査はどれもまだ存在しない。
実装したら、この文書の「決めたこと」を 1 つずつ錠と control に写し、写せなかったものは
残件表に戻す。

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
| **crash test** | 案 B の「落ちた場所ごとに何が残るか」を W10〜W14 で列挙する作業。実装と同時でないと列挙が机上になる |
| E1 の錠の **14 → 何本** | 棚卸し表を W11 の kind 変更込みで数え直す。数は数えてから書く |
| `ARCHIVE_DESTROYED_LEAVING_COLD_BLOB` を W14 の**削除経路に確かめさせる**か | 確かめるなら `LongTermStorageAdapter` に問い合わせる = 外向き。範囲が変わる |
| `bytesNow: UNKNOWN` を運用文書でどう見せるか | 「不明」を「無い」とも「在る」とも読ませない文言が要る |

---

## 3. 錠と control（実装時に写すもの）

- **型の錠**: `RecordContentTransitionV1` は `contentDigest` 欄を**持たない**（足すと V1 との
  境界が溶ける）。`priorContentDigest` を出所無しで持てない
- **配線の錠**: `WriteKind` の 15 本すべてが `CHAINED` か耐久 unresolved（E1 の錠を広げる）
- **矛盾の錠**: `MOVED_TO_COLD` の遷移文が在るのに `DISPOSITION` 行が無い package を
  verifier が `FAILED` にする（1.3）
- **control**: `bytesNow` を `UNKNOWN` から `COLD` に変える細工（確かめていないことを
  確かめたと言う）／`priorContentDigest` を写さず計算する細工（別の観測を先行文と偽る）／
  W11 を `CAPTURED` で記録する細工

---

## 4. この設計が主張しないこと

- 遷移文が在っても、**遷移前に bytes が改変されていなかった**ことは言えない
  （先行文が在れば、そこからの連続性までは言える）
- `bytesNow: COLD` は「cold に書いた」であって「cold から読める」ではない
- **6 経路が全部これで閉じる**とは言わない。W14 の孤児化は削除経路が確かめない限り `UNKNOWN`
