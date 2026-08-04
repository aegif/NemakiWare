# `CouchLineageJournalStore` の責務対応表

3,441 行・6 インターフェース・58 の override を 1 クラスが持っている状態の棚卸しと、
挙動を変えない分割の設計。**分割線はインターフェース側に既に引かれている**ので、新しい
抽象を作らず実装をそのまま委譲先へ移す。

## 分割の原則 (この文書の適用範囲すべてに効く)

- 公開インターフェース・Spring bean の外形・CouchDB 文書形式・`_id`・view・CAS 条件・
  retry 回数・例外分類・ログと metric の意味を**変えない**。
- `_rev` の read-modify-write と競合判定を**途中で分断しない**。1 つの CAS は 1 つの
  委譲先の中で完結する。
- CAS を一般化する抽象を**先に作らない**。共通化は、複数の抽出先に同一処理が実際に
  残ったことを確認してから。
- 元クラスは互換 facade / composition root として残すが、最終的に業務ロジックは持たない。

## 共有基盤 — 元クラスに残す

委譲先はこれらを元クラス経由で使う。`private` → package-private への変更は可視性の
**拡大**なので既存テストを弱めない。

| 処理 | 理由 |
|---|---|
| `ensureDatabase` / `ensureClientForRead` / `getLineageClient` | DB provisioning と lazy discovery。全責務が通る唯一の入口 |
| `buildViews` / `deployViews` / `DESIGN_DOC` | design document は 1 つ。責務ごとに分割すると view 署名検査 (`viewSignatureViolations`) が壊れる |
| `readRaw` / `readRawStrict` / `updateStrictCas` / `createIfAbsent` | strict IO の三分類 (404 / 409 / 障害)。**複数責務が同一実装を共有している**ことを確認済み — 先に共通化するのではなく、元々共有なのでここに残す |
| `objectMapper` / `connectorPool` / `lineageConfig` / `lineageMetrics` | 注入点。composition root の役目 |
| `dbProvisioned` | provisioning の一度きり性 |

## 責務ごとの内訳

### 1. `LineageBarrierStore` → `CouchLineageBarrierStore`

| | |
|---|---|
| public method | `readBarrierRaw` `casBarrier` `readWitness` `writeWitnessIfAbsent` `readNodeId` `allocateNodeIdIfAbsent` |
| 文書 | `lineage_write_version` / `lineage_barrier_witness` / `lineage_node_identity` (すべて `nemaki_lineage`) |
| CAS | `casBarrierDoc` — `_rev` 一致で PUT、409 は false、他は `BarrierStorageException` |
| 固有 IO | **専用 client** (`barrierClient`)。`ensureClientForRead` を使わない — あれは検証済み不在と障害を同じ `false` に潰し、view も配る。4a の Pristine / Indeterminate 判定はその区別の上に立っている |
| 既存テスト | `LineageBarrierTest` (50) / `LineageBarrierCouchIT` (3) |
| 依存 | 共有基盤に**依存しない**。最も独立している = 最初に抽出する |

### 2. `LineageMaterializationStore` → `CouchLineageMaterializationStore`

| | |
|---|---|
| public method | `createDecisionIfAbsent` `readDecision` `readMaterializedV1RowStrict` `createMaterializedV1RowIfAbsent` `appendV2Classified` `findV2SequencedRepositoryIds` |
| 文書 | `lineage_materialization:{spoolRecordId}` / v1 event 行 / v2 event 行 |
| CAS | create-if-absent (409 = 既存) + digest 一致検証。`appendV2Classified` は `document_too_large` を D1 分類 |
| 固有 codec | `decisionToRaw` / `decisionFromRaw` / `strictV1Event` / `verifyMaterializedV1Digest` / `requireString` 系 |
| 既存テスト | `LineageMaterializationTest` (19+) / `LineageChunkingTest` (26) / `LineageParkingCouchIT` (2) |

### 3. `LineageV2ReplayStore` → `CouchLineageReplayStore`

| | |
|---|---|
| public method | `requestReplay` `advanceReplay` `failReplay` `findUnackedReplayRequests` |
| 文書 | v2 event 行の `v2ReplayByTarget` |
| CAS | generation CAS。REQUESTED→CREATED→ACKED、FAILED は永続 |
| 既存テスト | `LineageV2TransitionMachineTest$ReplayRequestShapes` ほか |

### 4. `LineageV2TransitionStore` → `CouchLineageV2TransitionStore`

| | |
|---|---|
| public method | `claimForProjection` `transitionV2` `transitionV2Unclaimed` `renewClaim` `reapExpiredClaims` `findV2ByRepositoryAndSequenceRange` `findV2ByRecordId` `findV2NonTerminalRepositoryIds` `findV1ByRepositoryAndSequenceRangeStrict` |
| 文書 | v2 event 行 (`lineage_event_v2`) |
| CAS | token-fenced CAS。§8-b の遷移表 (claimed 6 対 + unclaimed 6 対) |
| 固有 IO | `readV2RawStrict` / `decodeV2Strict` / `queryV2RowsInClaimOrder` |
| 既存テスト | `LineageV2TransitionMachineTest` (41) |
| 注意 | replay と同じ v2 行を触る。**行の codec (`decodeV2Strict`) は両者が使う**ので、片方の中に隠さず共有基盤側に置く |

### 5. `LineageSequencingStore` → `CouchLineageSequencingStore`

| | |
|---|---|
| public method | `acquireSequencerLease` `renewSequencerLease` `releaseSequencerLease` `readSequencerLease` `findUnsequencedV2` `findSequencingV2` `claimForSequencing` `reclaimForSequencing` `finalizeSequence` `allocateSequenceFenced` `sequenceHighWatermark` |
| 文書 | `lineage_seq_lease:{repositoryId}` / `lineage_seq:{repositoryId}` |
| CAS | lease の generation CAS + `casSequencingWrite`。`FencedEffect` |
| 固有 helper | `leaseDocumentId` / `exactLong` / `matchesGrant` / `isExpired` |
| 既存テスト | `LineageSequencingCouchIT` (18、実 CouchDB) |

### 6. `LineageJournalStore` (v1) → 元クラスに残す

21 の override。v1 event 行の CRUD・view 問い合わせ・purge・retry・backlog。**最後まで
元クラスに残す**理由は 2 つある: (a) `isActive` / `ensureDatabase` と不可分で、これは
composition root そのもの、(b) 他の 5 責務が「同じ DB の別文書」であるのに対し、v1 は
`ensureDatabase` の副作用 (provisioning) を持つ唯一の書込み経路。

## 抽出順序

依存の少ない順・テストの支えが厚い順:

1. **barrier** — 共有基盤に依存せず、専用 client を持つ
2. **materialization** — codec が固有、テストが厚い
3. **replay** — v2 行を触るが遷移表とは独立
4. **v2 transition** — replay と codec を共有するので replay の後
5. **sequencing** — 実 CouchDB IT が唯一の支え。最後
6. v1 journal + 共有基盤は元クラスに残す

## 現状固定した疑義 (今回は意味を変えない)

抽出中に見つけた仕様上の疑義は、**現状の挙動をテストで固定**して別課題として記録する。
リファクタリングの差分で意味を変えると、失敗したときにどちらが原因か分からなくなる。
