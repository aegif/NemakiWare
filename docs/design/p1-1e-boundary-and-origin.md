# P1-1(e) — 取込境界の引き直しと実行起源 (+ flip 前提の v2 被覆拡張)

作成: 2026-08-24。**改訂 1** (同日): 実装前 Codex レビューが **BLOCK** — Critical 2 / High 7 /
Medium 6 / Low 2。全指摘を取り込んだのがこの版。初版の誤り (「保存済み v2 digest はゼロ」ほか)
は §7 に記録して残す。

入力: [`p1-1-remaining-plan.md`](p1-1-remaining-plan.md) §2 D-5 / §3 / §4、
[`p1-1b-v2-evidence-home.md`](p1-1b-v2-evidence-home.md) §2 §3.3-3.4 §5 §7-8、
[`p1-1d-metadata-hash.md`](p1-1d-metadata-hash.md) §5-5 §7、実装前調査 4 本 + Codex 設計レビュー。

**束ねる理由**: (b) §5 は「digest の式を動かすなら 1 度」と決め、(b) §3.4 は「flip 前提 =
LineageEventV2 の被覆範囲そのものを広げる仕事」と特定した。実行主体 (D7)、D-1 のイベント側
hash、D-4 の会話範囲、Process 属性供給、D-5 の第 2 の写し — 全部が同じ 1 回の被覆拡張に載る。

---

## 0. 主張すること / しないこと

**する**: 取込イベントの実行主体・適用メタデータ hash・取込判断 (会話範囲)・Process 供給事実が
v2 イベントの `creationPayloadDigest` に覆われる。**digest が検出するのは「payload と digest が
同期しない書換」(部分編集・破損・組立バグ) である。** outbox とイベントの実行主体が同じ
resolver の 1 回の解決から出る。委譲実行の実行主体が「unknown」でなく定義された形で記録される。

**しない**: **敵対的改竄証明** — journal 行を書き換えられる主体は式が公開の SHA-256 なので
digest ごと再計算できる (Codex H7)。それを主張するには HMAC / 署名 / 外部 anchor が要り、
この作業は主張しない (「管理者でも書き換えられない」を言わない従来方針の一貫)。
v2 write flip そのもの (Slice 4)。カタログ側の保持規則。INTERNAL_ONLY のカタログ配送
(構造的に不可能にする)。

---

## 1. 実行主体 — `ExecutionAttribution` (1 回解決・typed)

### 1.1 判定は CallContext の型。executionMode は使わない

`ExternalIngestRequest.executionMode` は request JSON で偽装可能。サーバ側の真実:

| 文脈 | 意味 |
|---|---|
| `DelegatedCallContextFactory` の合成 context | scheduler / webhook / IMAP IDLE の委譲実行 (`getUsername()` は権限者) |
| `null` | admin プロファイルの自律実行 |
| それ以外の実 context | 認証済み呼び出し者の手動実行 |

`DelegatedCallContextFactory.isSynthetic(CallContext)` を公開して分岐する。Codex 検証済み:
合成 context の生成元は factory のみ、手動系 (ExternalIngestController / IngestSchedulerController
の手動 trigger) は実 context — 分類は現 call graph で健全。

**L1 対応**: DLQ 永続化ゲート (`:2718` / `:2769`) も executionMode でなくサーバ側判定
(合成 or null context = 自律実行) に移す。executionMode は情報として残るがゲートには使わない。

### 1.2 typed pair — map に散らさない (Codex H2/L2)

```java
record ExecutionAttribution(String executedBy, String onBehalfOf) {
    // executedBy は必須非空。onBehalfOf は null 可。compact constructor で強制。
}
```

`resolveExecutionAttribution(profile, callContext)` が**唯一の解決点** (旧 resolveExecutedBy /
resolveOnBehalfOf の統合)。intent (outbox) は `CaptureScope.describe()` を widen して
execute() 内の profile 解決後にこの値で確定 (describe の未 open 前提は成立 — Codex 検証済み、
確定は `:2323` 相当の位置)。event は同じインスタンスを emitter へ。**同じ 1 回の解決の値が
両方に入る**ので AC9 が構造で立つ。

出力形式 (emitter の第 3 語彙は廃止):
- 手動: `executedBy = username`、`onBehalfOf` = 委譲なら作成者、直なら null。
- 委譲の自律実行: `executedBy = "scheduler: delegated profile {id}, schedule configured by
  {user}"`、`onBehalfOf = createdByUserId`。
- admin の自律実行: `executedBy = "scheduler: admin profile {id}, schedule configured by
  {user}"`、`onBehalfOf = null`。

### 1.3 schedule-configured-by は server-owned (Codex H3/H4)

`ImportProfileDefinition.scheduleConfiguredByUserId / scheduleConfiguredAtMs` を追加。
**全 PUT で client 供給値を破棄**し (現行の全置換 PUT は偽装・消去可能)、schedule に効く差分
(enabled / delegated / connectorId / cron 系) があるときだけ server が `ctx.getUsername()` で
上書き、なければ保存値を維持。create 時も server が stamp (admin create の body 素通しに
乗せない)。

**legacy fallback は「configured by」と呼ばない** (H4 — createdByUserId は構成操作の証明では
ない。admin create では body 由来ですらある)。フィールド未設定の profile は
`"schedule configured-by unrecorded (profile created by {createdByUserId})"` と明示する。

---

## 2. v2 被覆拡張 — 式は動かすが、保存済みを壊さない

### 2.0 前提の訂正 (Codex C1): 保存済み v2 行は存在しうる

初版は「production は v2 未書込」とした。**誤り**: `JournaledLineageEmitter` は
`WriteVersionResolver` が `Present(2)` を返せば spool へ書き、materializer は本番 `appendV2`
を呼び、barrier は管理 API で ACTIVE にできる。実 CouchDB IT も行を保存する。よって:

- **`creationDigestVersion` を永続化** (v2 行の新フィールド、欠落 = 1)。
  `LineageEventV2` の compact constructor は**バージョンに対応する構成**で再計算する:
  version 1 = 現構成 + domain `EVENT_CREATION_V1` (このとき新 map と attribution は空/null で
  なければ拒否)、version 2 = 新構成 + domain `EVENT_CREATION_V2`。旧行は永遠に version 1 の
  まま読める。
- **materializer の凍結 decision**: decision に version が無い既存行は version 1 として
  materialize する (凍結 digest と再構築値の照合は同じ式で行われる)。新 decision は
  version を持つ。
- codec (`CouchLineageEventV2`) は version・attribution・2 map を保存/復元する (Codex M1)。
  classified copy と replay compensation も同フィールドを運ぶ (replay が map を落とすと
  Process 必須属性がまた空になる — M1 の名指し)。

### 2.1 spool payload V2 (Codex C2)

v2 経路は `LineageFact` を直接保存しない — `LineageSpoolPayloadV1.of(fact)` が唯一の永続形で、
codec はフィールド集合を厳密一致させる。よって:

- **`LineageSpoolPayloadV2`** を新設: V1 の全フィールド + `attribution` + `processFacts` +
  `journalFacts`。自前の payload digest 構成 (V1 の digest は不変 — 保存済み V1 行は
  そのまま読めて materialize できる。V1 行は空 map / null attribution として version 2 event に
  materialize する — 事実が無いことの誠実な表現)。
- writer は V2 payload を書く。codec は kind で判別して両方 decode。
- `reference_hash.py` に `creation_payload_digest_v2` と `spool_payload_digest_v2` を追加し、
  vector を外部計算で固定。**creation digest v1 の関数と vector も同時に新設** (現状
  Java 側にしか式が無い — 式を動かす今が独立実装の被覆を作る機会)。

### 2.2 2 区画 + typed attribution、constructor が検証する (Codex H1)

| 置き場 | 配送 | 中身 |
|---|---|---|
| `attribution` (typed) | しない | executedBy / onBehalfOf (§1.2) |
| `processFacts` (Map) | Process 属性として sink が読む | `targetFolderId` / `sourceArchetype` / `sourceDescription` (派生キー — §2.3) / `reimportOutcome` / `reimportFilled` / `reimportRefused` / `assuranceAsserted` — 全て EXTERNAL_OK |
| `journalFacts` (Map) | しない | `chat.selectionReason` / `chat.evidenceScope` / `chat.captureWindowStart` / `chat.captureWindowEnd` / `chat.capturedAt` (D-5) / `appliedChatEvidenceHash` / `appliedSourceIdentityHash` / `metadataHashSubject` / `metadataHashFormula` |

- **compact constructor が両 map を検証**: キーは `CaptureEvidenceField` の
  PROCESS_FACT / JOURNAL_FACT 宣言 (+ 派生キー表) の閉集合。区画違い・未知キー・null/blank 値は
  例外。「sink が読まないから出ない」ではなく「INTERNAL_ONLY キーを processFacts に**構築
  できない**」を型が保証する。
- `externalStableKey` は processFacts に**入れない** (Codex M4 — v2 input endpoint の必須属性が
  正典。二重保持は不整合の新設)。sink の v2 分岐は typed input の attributes から読む。
- `sourceDescription` は enum に PROCESS_FACT の**派生エントリ**として追加 (Codex M5 —
  v1 では sink が結合して作っていた値。v2 では emitter が同じ式で作り digest に入れる)。
- actor の v1 側扱い (Codex M6): `EXECUTED_BY` / `ON_BEHALF_OF` の Disclosure を
  **INTERNAL_ONLY に変更** — v1 は今日、宛先が捨てるだけで**送信はしていた**。これは
  「パリティ維持」でなく**意図的なプライバシー変更**として記録する
  (`PersonalDataDoesNotLeaveForTheCatalogTest` に 2 エントリ追加)。
- 恒久 none: `chat.participants` / `chat.channelName` ((b) §6 維持)。

### 2.3 配送スキーマ同一コミット

`nemaki_import_process` に reimport 3 键 + assuranceAsserted を optional 宣言、
**SCHEMA_VERSION 16 → 17** (manifest hash は属性リストを含まない)。sink の
`addProcessTypeAttributes` v2 分岐は `record.processFacts()` + typed input attributes から埋める。
folderId / importMode は REQUIRED — v2 record で `""` にならないことを pin。

---

## 3. D-5 — aspect 付与を execute() へ (emit 前 hook)

- `execute()` に optional な `beforeEmit` hook。呼ぶのは create 成功後・emit 直前。
- chat wrapper が hook で chat aspect + capture window + capturedAt 刻印を実行。
- **hook 失敗の契約 (Codex H5)**: hook は例外を握らない。`CaptureIntentFailedException` は
  そのまま伝播 (fail-closed)。その他の例外は **scope に INDETERMINATE mutation を記録して
  から**伝播し、execute() の既存の失敗経路 (DLQ 判定含む) に届く。「warning にして emit
  続行」はしない — 書込結果不明のまま CAPTURED になる穴 (CaptureScope.complete の成功判定)
  を作るため。
- **mh1 のイベント側供給 (Codex M2)**: hook 完了後・emit 前に保存済みオブジェクトを 1 度
  読み戻して `EvidenceMetadataHash.compute()` し、**その同じ結果**を (a) event の journalFacts、
  (b) scope (完了 evidence 用) の両方へ渡す。完了時の再計算は従来どおり行い、**create 経路では
  両写しが一致することをテストで pin** (Codex M3 — emit 後に aspect 書込が無いことの検証を
  兼ねる)。
- skip (dedupe) 経路: hook は呼ばれない。fill 規則 (D6/D-7) は wrapper のまま
  (Codex 検証済み: skip 後の fill と reimport event は wrapper 側に現存)。
- mail/note/record の aspect は動かさない。chat attachment は adapter が
  `executeChatContextImport` を個別に呼ぶ構造で、hook が同一 root scope に再帰しないことは
  Codex 検証済み。

---

## 4. UNRESOLVED — 閾値連動は「緩和」であり「保証」ではない (Codex H6)

`CaptureIntentSweeper` の実効 stale 閾値を
`max(lineage.capture-boundary.stale.minutes, ingest.scheduler.fetchTimeoutMinutes + 5)` にする。
既定構成 (15/30) の誤判定窓は閉じるが、**保証にはならない**: scheduler の timeout は
interrupt するだけで join せず (ブロッキング fetch は生き残りうる)、webhook / IMAP IDLE は
timeout wrapper なしで import を呼ぶ。生きた pass の intent が swept される可能性は残り、
その pass が sweep 後かつ baseline 完了後に書くと verify の「swept-before は降格しない」規則が
偽 MISMATCH を出しうる — **この残余は runbook と verify の文書に限界として明記する**。
恒久解 (実行側が intent の lease を延長する) は heartbeat 基盤 (job record に既存) との統合
込みで別増分に送る — 今回は計画が併記した 2 案のうち閾値連動を採り、限界を隠さない。

---

## 5. 受入条件 (負のコントロール付き — 全件 revert→fail 実測)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | intent と event の attribution が同一取込で一致 (AC9)、**同じ 1 回の解決から** | intent 側を旧出所に戻すと落ちる |
| 2 | 委譲自律実行の executedBy が configured-by を含み「unknown」を含まない | resolver を旧文言に戻すと落ちる |
| 3 | executionMode="scheduled" を名乗る手動要求でも executedBy は username / DLQ ゲートも変わらない | 判定を executionMode 参照に変えると落ちる |
| 4 | journalFacts / processFacts / attribution の部分書換 (digest 据置) は decode で拒否される | digest 構成から外すと落ちる |
| 5 | creation digest v1 / v2 と spool payload digest v1 / v2 が python 参照実装の vector と一致 | Java 式のどこを変えても落ちる |
| 6 | `creationDigestVersion` 欠落の保存行 (旧構成) が読めて materialize できる | 常に新式で再計算すると落ちる |
| 7 | INTERNAL_ONLY キーを processFacts に入れた構築は例外 / 未知キー・blank も例外 | constructor 検証を外すと落ちる |
| 8 | v2 record 経由で folderId / importMode が sink の Process 属性に非空で到達 | sink の v2 分岐を legacy 読みに戻すと落ちる |
| 9 | journalFacts と attribution が sink payload に現れない | sink に読ませると落ちる |
| 10 | chat 取込: emit 前に aspect が在り、event の journalFacts の mh1 = 完了 evidence の mh1 | hook を post-execute に戻す / 二重計算を別状態にすると落ちる |
| 11 | hook の一般例外で intent が CAPTURED にならない (INDETERMINATE 記録) / CaptureIntentFailedException は伝播 | 握って warning 化すると落ちる |
| 12 | sweeper 実効閾値 ≥ fetchTimeout+5 | 連動を外すと落ちる |
| 13 | SCHEMA_VERSION 17 + 新属性宣言が同一コミット | 属性だけ足すと落ちる |
| 14 | codec round-trip / classified copy / replay compensation が version・attribution・2 map を保持 | どれかを落とすと落ちる |
| 15 | profile PUT: client 供給の scheduleConfiguredBy は常に破棄され、schedule 差分の時だけ server が stamp | handshake を外すと落ちる |

## 6. やらないこと

flip の実行 / mail・note・record aspect のイベント被覆 / カタログ保持規則 / 敵対的改竄証明
(§0) / executionMode の削除 (情報としては残す) / intent lease (別増分 — §4)。

## 7. 実装前レビューの記録 (BLOCK → 改訂)

Codex (2026-08-24) C2/H7/M6 ほか 17 件。要点: 「v2 未書込」の誤認 (C1 — barrier ACTIVE 配備と
IT が保存する)、spool payload の欠落 (C2)、map の構造検証欠如 (H1)、typed attribution (H2)、
profile PUT の偽装面 (H3/H4)、hook の握り潰し穴 (H5)、閾値連動の過大主張 (H6)、
digest の敵対的主張の過大 (H7)、codec/replay parity (M1)、mh1 供給経路欠落 (M2/M3)、
externalStableKey 二重保持 (M4)、sourceDescription の表駆動不成立 (M5)、actor parity 誤認
(M6)、DLQ ゲート (L1)、resolver 分離 (L2)。全て本版に反映。
