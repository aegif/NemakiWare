# ACL-in-Solr: Repository-wide monotonic ACL epoch fencing (design v2.1)

Status: **SIGNED OFF (design v2.1) — 2026-07-24, baseline `f251e1c16` on
`test/v3.3-arm64-full`.** This authorizes staged implementation in the report order
(counter → outbox finalization → effective-epoch → ACL-UPDATE atomic+fence →
CONTENT/CREATE separation → batch fence + final sweep → RAG unification → strict →
migration patch → live-Solr concurrency IT). It is NOT master-merge or
production-ready approval; the final master-merge decision is re-made once §9 tests
1–16 + the live-Solr concurrency IT + related TCK are all green.

**Mandatory implementation invariants (sign-off conditions — every increment):**
1. Do NOT allocate an epoch before the Phase-1 commit.
2. `PENDING_EPOCH` + `aclEpochMutationId` are written in the SAME commit as the ACL change.
3. The ACL-write order is walk → compute → RTG → revalidate → CAS — never reordered.
4. On 409, NEVER reuse the payload — restart from the walk.
5. The outbox marker is cleared only after confirming an `ACL_REINDEX` task durably
   exists AND its `minRequiredEpoch` >= the finalized epoch.
6. EVERY ACL writer, RAG, and batch participates in the one §4.2 contract.
7. `content_incarnation` is CAS-persisted to CouchDB BEFORE it is stamped to Solr.
8. Restore ALWAYS issues a fresh `content_incarnation`.
9. Each increment stays fail-closed; half-finished code is not enabled in a write path.

v2.1 adds, per review: `content_incarnation` (restore resets `_rev`, §4.4) WITH a full
existing-Content migration lifecycle (§8.1, round-7), independent per-operation task
obligations (§5), and a strengthened outbox ACK condition (§3). Supersedes v1 after two
review rounds. v1's flaws corrected in v2:

- v1 allocated the epoch BEFORE the CouchDB commit (under the per-object lock) and
  claimed "allocation order == commit order". That holds only per-object; across
  DIFFERENT chain nodes (ancestors, relationship endpoints) allocation and commit
  reorder, producing EQUAL effective epochs for DIFFERENT ACL states. v2 uses
  **post-commit allocation** (§2).
- v1's invariant "equal epochs are idempotent (same ACL source)" is **false** and is
  replaced by the equal-epoch convergence rule (§4.3).
- v1 had no durable link between epoch finalization and reconciliation-task creation
  (crash window losing the task forever). v2 adds a **durable outbox state on the
  Content** (§3).
- v1 said "a failed counter CAS may waste a value" — wrong: a failed CAS persists
  nothing; gaps arise only when an ALLOCATED epoch's finalization is abandoned (§2.1).

## 1. Problem recap (unchanged from v1)

Solr docs carry a `readers` token set (ACL-in-Solr). All ACL-refresh writers (normal
async applyAcl/move refresh, reconcile re-drive, batch/full reindex, RAG block writer)
must converge so each doc's readers reflect the LATEST committed CouchDB ACL, and a
late stale writer can never permanently overwrite a fresher write. Rejected fences:
the object's own `_rev` (cannot order inherited/relationship writes — a parent ACL
change doesn't bump a child's `_rev`); `max(ancestor _rev)` (different documents'
`_rev` counters are not comparable; non-monotonic under ancestor-set changes).

## 2. Epoch issuance: post-commit two-phase finalization (Q0)

### 2.1 Global counter

- One per-repository counter doc in `nemaki_conf`: `_id = acl-epoch-counter::{repo}`,
  `{type: "aclEpochCounter", value: <long>}`. Allocation = read + CAS(`_rev`) write of
  `value+1`; on conflict re-read + retry. **Value gaps are SAFE — only strict
  monotonicity matters.** Allocation is gap-free ONLY under conflict-only,
  no-communication-failure conditions (a lost CAS persists nothing); an **ambiguous
  timeout** (CouchDB commits `value+1` but the HTTP response is lost, so the caller
  re-reads and allocates `value+2`) skips a value, and an allocated epoch whose
  finalization is later abandoned skips one too — all harmless. Long overflow is
  explicitly rejected (throw). **Fail-closed read (increment 1a): a stored value is
  accepted only if it is a finite, integral, in-`long`-range, non-negative number with
  `type == aclEpochCounter` and a `_rev` — a fractional (`1.5`), out-of-range,
  non-finite, wrong-type, or negative value throws (never read as a lower value, which
  would re-issue epochs).**

### 2.2 Two-phase mutation (the Q0 fix)

An ACL-affecting mutation on object X (applyAcl / move / inheritance toggle /
relationship re-point) proceeds:

1. **Phase 1 — commit with pending marker** (inside the existing per-object lock):
   persist the mutation itself PLUS `aclEpochState = PENDING_EPOCH` and a fresh
   `aclEpochMutationId` (UUID) on X. The ACL change is now durable and
   **authoritatively effective for authorization** (with the cache caveat in §7).
   No epoch is allocated yet.
2. **Phase 2 — finalize** (same request continues; a crash here is recovered by the
   scanner, §3): allocate `e` from the counter (§2.1), then CAS-patch X (`_rev` CAS):
   if `aclEpochMutationId` still matches, set `aclSourceEpoch = e`,
   `aclEpochState = FINALIZED_NEEDS_RECONCILE`. If the mutation id does NOT match, a
   NEWER mutation superseded this one — abandon this finalization (the newer
   mutation's own finalizer assigns a newer epoch to the newest state).

Consequences: epochs are assigned in **finalize order over committed states**; the
last-finalized mutation of any object holds the strictly-highest epoch among that
object's mutations; and (critically) a writer can DETECT an in-flight mutation via
the pending marker instead of silently reading a pre-epoch state.

### 2.3 Why post-commit allocation alone is still not enough: read skew

A writer whose read of chain node P PRE-DATES P's phase-1 commit sees neither the new
ACL nor the pending marker; if it then reads another node A AFTER A's finalization,
it computes an effective epoch equal to a later correct writer's, with different
readers. The pending gate cannot catch a reader that never saw the pending marker.
Convergence therefore requires the full contract of §4 (gate + ordered
RTG-revalidate-CAS + conflict-recompute), not the gate alone.

## 3. Durable outbox on the Content (task-creation atomicity)

Finalizing X (CouchDB) and creating the reconciliation task (`nemaki_conf`) are
writes to different documents/databases — NOT atomic. A crash between them must not
lose the refresh forever. Durable state ON THE CONTENT:

```
PENDING_EPOCH                (ACL committed; epoch not yet assigned)
  → FINALIZED_NEEDS_RECONCILE (epoch assigned; task creation not yet confirmed)
  → RECONCILE_ENQUEUED        (task durably exists; steady state = marker cleared)
```

- After phase 2, the mutating request enqueues the subtree-root reconciliation task
  (idempotent deterministic `_id`), then CAS-patches X to `RECONCILE_ENQUEUED` /
  clears the marker. **v2.1 ACK condition (strengthened): the outbox may be cleared
  only after confirming that an `ACL_REINDEX` task for X durably exists AND its
  `minRequiredEpoch` >= the epoch finalized in phase 2** (a merge with an existing
  task must have kept the max). "A task document exists" alone is insufficient — a
  concurrently-completing older task could delete it between our check and the ACK,
  or an existing task could carry a lower obligation; the `minRequiredEpoch` merge
  plus the queue's generation CAS make the obligation itself durable.
- A **scanner** (piggybacked on the existing reconciliation scheduler tick) sweeps
  Contents in `PENDING_EPOCH` (crash before finalize → finalize them, §2.2 step 2)
  and `FINALIZED_NEEDS_RECONCILE` (crash before enqueue → enqueue idempotently, then
  advance). Duplicate enqueues are permitted (dedupe by task `_id`); task completion
  racing a re-enqueue is resolved by the queue's existing generation/`_rev` CAS.
- The inline async refresh (today's `ragAclExecutor` traversal) remains as
  best-effort ACCELERATION; the durable queue is the correctness path.

Mango index addition: **`(aclEpochState)`** on content DBs for the scanner (increment 2
adopted a state-first single-field index rather than the originally-sketched `(type,
aclEpochState)`: epoch state spans MULTIPLE content types and the scanner selects purely
by state (`aclEpochState $in [PENDING_EPOCH, FINALIZED_NEEDS_RECONCILE]` and a bounded
`$exists`/`$nin` audit), so a state-first index serves those directly and excludes
state-less documents, whereas a type-first compound index would require enumerating
types). (Persistent-format note for the release notes: new Content fields
`aclSourceEpoch`, `aclEpochState`, `aclEpochMutationId`; new counter doc type; new
`(aclEpochState)` Mango index.)

## 4. The unified write contract (every ACL writer)

### 4.1 Effective epoch

- Object X: `effectiveEpoch(X) = max(aclSourceEpoch over X + inheriting ancestors)`
  (walk stops at root / non-inheriting node). All values come from ONE counter →
  comparable and monotonic per object (move bumps X's own epoch above any prior
  chain member, so ancestor-set changes cannot decrease it).
- Relationship R: `max(effectiveEpoch(source), effectiveEpoch(target),
  R.aclSourceEpoch)`.
- Pre-migration docs: absent `aclSourceEpoch` = 0 (§8).

### 4.2 Mandatory operation order (fixed; review-required)

Every ACL-group write (normal async refresh, reconcile, batch, RAG) MUST:

1. **Walk** authoritative sources (cache-bypassing): record for EVERY dependency
   (self + inheriting ancestors; for a relationship also both endpoints) its `_rev`,
   `aclSourceEpoch`, `aclEpochState`, parent id, inheritance flag, endpoint ids.
   **If any dependency is `PENDING_EPOCH` (or `FINALIZED_NEEDS_RECONCILE` mid-CAS
   ambiguity): do not write — retry/back off** (the pending gate).
2. **Compute** readers + effectiveEpoch from the recorded snapshot.
3. **Realtime GET** (`/get`, never a searcher query) the Solr doc's `_version_` +
   stored `effective_acl_epoch` (+ stored readers for the equal-epoch rule).
4. **Revalidate**: re-read every dependency recorded in step 1 and require identical
   `_rev`/epoch/state/topology. Any change → restart from step 1.
5. **Immediately CAS** the atomic ACL-group update (`readers` +
   `effective_acl_epoch` `{set}`) with the step-3 `_version_`.
6. On **409**: restart from step 1. **Payload reuse after a conflict is forbidden.**

Rationale for the order (review round-2 refinement): the `_version_` must be read
BEFORE revalidation so that any Solr write after step 3 — including a correct
writer's — fails our CAS at step 5. The reverse order (revalidate → RTG → CAS) lets
a stale-but-revalidated writer acquire a fresher `_version_` written in between and
overwrite it. With this order: source changes are caught by step 4 + restart; Solr
changes are caught by the step-5 CAS; the residual TOCTOU (source changes between
step 4 and step 5) resolves via §4.3 because the NEXT writer's step-1 recompute uses
the newest sources.

### 4.3 Fence decision + equal-epoch convergence rule (replaces v1 §6 invariant)

At step 5, comparing `mine` vs stored:

- stored epoch **>** mine → **skip** (clean no-op; a fresher effective ACL landed).
- stored epoch **<** mine → CAS-update.
- stored epoch **==** mine:
  - stored readers **==** canonical(mine) → **skip** (true idempotence).
  - stored readers **≠** mine → **recompute from authoritative sources (step 1) and
    CAS-update the recomputed value** — never "my payload wins by default". Equal
    epochs with different readers exist only transiently (read-skew windows); every
    conflicting writer recomputing from source converges to the last-finalized state.
- 409 at any point → full restart (step 1), never payload retry.
- Missing/unparsable stored epoch or `_version_` on the reconcile path →
  **fail-closed** (throw → task retained/retried). Optional observability:
  `effective_acl_fingerprint` (hash of canonical readers) may be stamped for
  diagnosis but is NEVER a correctness input.

### 4.4 Field-group separation and the two ordering axes (Q2)

- Solr fields split into **content group** (name/path/body/…) and **ACL group**
  (`readers`, `effective_acl_epoch`). ACL-group writes are atomic `{set}` only.
- **Stage 1 for content writers (accepted): read+preserve full add** — realtime GET
  the existing ACL group and copy it verbatim into the full doc, `_version_` CAS,
  on 409 re-read + re-preserve. If the ACL group is absent on an EXISTING doc, do
  not full-add unconditionally — hand off to reconcile. (Full atomic-content-update
  migration is a later stage; too much regression surface now.)
- **Second axis — `content_generation` + `content_incarnation`**: the doc's OWN
  CouchDB `_rev` leading int, stamped by content writers and fenced among content
  writers (skip if stored content_generation is newer) — own-`_rev` IS comparable
  within one document lifetime. **BUT a restore re-uses the original `_id` while
  letting CouchDB assign a FRESH `_rev` (`ArchiveDaoDelegate.restoreContent`
  explicitly skips `_rev`), so `_rev` numbering RESTARTS at 1-*: a numeric-only
  fence would judge the correctly-restored content "older" than the pre-delete
  Solr doc (e.g. stored 50 vs restored 1) and permanently refuse to write it.**
  Therefore v2.1 adds `content_incarnation`, a UUID persisted on the Content and
  stamped alongside `content_generation`:
  - numeric `content_generation` comparison applies ONLY when the stored and
    incoming `content_incarnation` are EQUAL;
  - restore / recreate-under-the-same-id issues a NEW incarnation;
  - on incarnation MISMATCH the writer does not compare generations at all — it
    CAS-updates from the current authoritative Content (the live CouchDB state is
    by definition the truth for a new incarnation), establishing the new
    incarnation + its generation in Solr.
  ACL epoch orders the ACL group; (incarnation, generation) orders the content
  group. Both axes ride the same `_version_` CAS.
- CREATE (doc absent) uses `_version_ = -1` create-if-absent carrying both groups;
  409 → the doc appeared → fall back to the update modes.

### 4.5 Batch / full reindex (Q3)

- **No repository-wide ACL-write pause** (multi-replica pause validity, crash
  leakage, doesn't stop content races, doesn't fix clear-vs-live-write deletion).
- Every batch doc write follows §4.2 (create-if-absent + fences). After the batch, an
  **authoritative final sweep** reconciles CouchDB vs Solr (docs deleted by the
  initial clear that raced a live write; mid-batch creations; orphans). Performance
  optimizations (bounded parallelism, multi-get) come after correctness.

### 4.6 All writers authoritative (Q4)

- The §4.2 walk is cache-bypassing for EVERY ACL writer, not just reconcile ("only
  reconcile strict" is insufficient — a normal writer on a stale cache could stamp a
  max-epoch wrong readers set and fence out the correct writer).
- Within ONE traversal, a child may reuse the ancestor chain read by its parent (one
  authoritative snapshot per traversal); per-node revalidation (step 4) still applies
  before each node's CAS.
- RAG long block rebuilds re-run step 4 against the source snapshot immediately
  before the block add (in addition to the lease checkpoint).
- Caches may later be reintroduced as advisory acceleration only, never correctness.

### 4.7 Relationship tasks (Q5)

- Refresh scope of an ACL mutation on X: X itself; all inheriting descendants; all
  relationships with source or target in that set (EITHER); on re-point, the
  relationship itself.
- Traversal dedupes relationship ids; a failed relationship write enqueues a
  **relationship-scoped task** (own deterministic `_id`); failure of the reverse
  lookup / child enumeration ALSO retains the subtree-root task. Relationship tasks
  re-drive standalone with fresh tri-state endpoint reads (dangling endpoint ≠
  transient ERROR).
- Tasks carry `minRequiredEpoch` (dedupe keeps the max) for completion checks,
  monitoring and forensics; the re-drive still recomputes the CURRENT epoch.

## 5. Reconciliation queue: INDEPENDENT per-operation obligations (v2.1)

Task gains an `operation` field: `ACL_REINDEX` (default; absent = ACL_REINDEX for
old tasks) | `RAG_PURGE`, and the scheduler/manual-retry dispatch on it (the prior
scheduler ignored reason and always ACL-reindexed — insufficient for a purge, which
an ACL reindex would leave alive or even refresh).

**v2.1 correction — the operations are NOT exclusive and must not share one task
document.** An ACL_REINDEX obligation covers CMIS content readers + descendants +
relationships (+ RAG readers); a RAG_PURGE covers only the RAG block. The v2 scheme
(one deterministic `_id`, PURGE wins on merge) could REPLACE a pending ACL task with
a purge: the purge completes, the task is deleted, and the unfinished ACL work —
soon to be the durable outbox's obligation — is silently lost. Therefore:

- **Per-operation deterministic ids**:
  `search-index-acl-reconcile::{repo}::{obj}` (ACL_REINDEX, unchanged — backward
  compatible with existing queue documents) and
  `search-index-rag-purge::{repo}::{obj}` (RAG_PURGE). The same object may hold BOTH
  tasks; each completes independently. No cross-operation merge rule exists.
- `RAG_PURGE` guarantees: actually deletes the block via a purge-dedicated,
  **`rag.enabled`-independent** delete (a disabled RAG must not turn the purge into
  a silent no-op); the delete and the absence verification are both
  **repository-scoped** (`repository_id` condition — the RAG id is the raw CMIS id,
  not repo-scoped); never completes without a verified-absent read;
  **never becomes terminal FAILED** — a purge that cannot run (e.g. RAG disabled,
  Solr down) stays PENDING under capped backoff so it resumes when the blocker
  clears (a terminal FAILED purge would let the block silently return with RAG
  re-enablement).
- **Enqueue durability**: the plain `enqueue()` is fire-and-fail-soft (metrics
  only). Security obligations (the PWC purge) use `enqueueOrThrow`, which returns
  only after the task durably exists and THROWS otherwise — the caller must fail
  (per-document, so a batch reindex records the failure without aborting the run).
  "Solr delete failed AND queue write failed AND caller reports success" must be
  impossible.

(The PWC purge fix is implemented AHEAD of the epoch work — approved as
independent.)

## 6. Conflict table (v2)

| A ↓ \ B → | ACL-UPDATE (higher e) | ACL-UPDATE (equal e) | ACL-UPDATE (lower e) | CONTENT-UPDATE | CREATE/batch | RAG block |
|---|---|---|---|---|---|---|
| ACL-UPDATE | B wins; A skips on re-read | equal-epoch rule §4.3 (same readers → skip; else recompute+CAS) | A wins; B skips | disjoint groups; both preserve the other via RTG copy + `_version_` CAS | fenced create-if-absent; higher epoch wins | separate doc; same contract |
| CONTENT-UPDATE | disjoint | disjoint | disjoint | same incarnation → `content_generation` fence + `_version_` CAS (newer own-`_rev` wins); incarnation mismatch → authoritative CAS from current Content | 409 → update path | disjoint |
| CREATE/batch | epoch fence | §4.3 | batch skips (stored ≥) | 409 → content path | one create wins; loser re-reads | independent |

Invariant (v2): **within the ACL group, strictly-higher effective epoch wins; equal
epoch resolves by the §4.3 recompute rule (equal ≠ idempotent); lower is skipped;
every CAS loser recomputes from authoritative sources.** Within the content group,
`content_generation` is compared **only within the same `content_incarnation`** (newer
own-`_rev` wins); on incarnation MISMATCH the writer does not compare generations and
CAS-updates from the current authoritative Content (§4.4 / §8.1) — a restore's
`_rev`-restart must not be misjudged "older".

## 7. Authorization during the pending window (scoped claim)

"The ACL is committed in phase 1" makes the **authoritative CouchDB ACL** correct
before finalization — but live authorization reads `calculateAcl`'s EhCache, so this
does NOT unconditionally extend to the live gate:

- crash after commit but before cache eviction (descendant eviction runs
  post-commit) leaves same-JVM stale cache up to TTL;
- multi-replica caches are never invalidated cross-replica (pre-existing, documented);
- pending state does not itself bypass the cache.

v2 requires ONE of (decided: the second):
(a) live authorization bypasses the ACL cache when the Content is `PENDING_EPOCH`, or
(b) **the finalizer/scanner re-runs the cache eviction (idempotent, self + inheriting
descendants) as part of finalize**, so a crash between commit and eviction is healed
by the same recovery that heals the epoch. Multi-replica remains the pre-existing
stale-cache limitation and is documented, not silently claimed solved.

## 8. Counter safety, migration, restore (Q1 decided: persisted high-watermark)

- The counter doc is the sole persisted high-watermark. Invariant:
  `counter.value >= every aclSourceEpoch in the repository`.
- Counter missing while epoch-bearing Content exists → **fail closed** (no lazy
  recreation). Recovery procedure: with writes stopped, scan max epoch over Content
  (and Solr) and restore `max+1`.
- Solr stored epoch > counter → treat as index corruption → fail, do not skip-as-newer.
- Repository restore must not roll the counter back; restored ACLs get NEW epochs
  from the current counter.
- No timestamp baselines (clock rollback / counter-beyond-clock reissue is not a
  safety argument). Baseline for a fresh repository: 1.
- Pre-migration docs: epoch 0; the mandatory v3.3 full reindex stamps ACL groups;
  the first ACL mutation allocates a real epoch. `Patch_AclEpochCounter` creates the
  counter + Mango index.

### 8.1 `content_incarnation` lifecycle & migration of existing Content (round-7 [P1])

`content_incarnation` (§4.4) is the second axis' identity: numeric `content_generation`
(own-`_rev`) is compared ONLY within one incarnation, and a restore mints a new one so
its `_rev`-restart-at-1 is not misjudged "older". This only holds if EVERY Content
carries an incarnation and no writer ever invents an ephemeral one. Rules:

- **New Content — persist at creation.** A `content_incarnation` UUID is generated and
  persisted ON THE CONTENT in the SAME CouchDB commit that creates it (authoritative
  write). It is never Solr-only.
- **Existing (pre-migration) Content — CAS-assigned, two convergent paths.** Old
  Contents have no incarnation. Each acquires one exactly once, via whichever runs
  first, both `_rev`-CAS and idempotent (skip if already present):
  1. `Patch_ContentIncarnationBackfill` (startup migration patch) CAS-assigns a fresh
     UUID to every incarnation-less Content; and
  2. lazily, the first AUTHORITATIVE write (ACL-group or content-group) that touches an
     incarnation-less Content CAS-assigns one on the CouchDB Content BEFORE it stamps
     Solr.
  Whichever wins the `_rev` CAS establishes the value; the other reads it present and
  proceeds. There is exactly one authoritative incarnation per Content.
- **NEVER stamp an ad-hoc incarnation to Solr only.** A writer that finds the Content
  lacking an incarnation MUST persist one on CouchDB (CAS) and THEN stamp that same
  value to Solr. Minting a UUID and writing it to Solr without persisting it would let
  two concurrent writers pick DIFFERENT UUIDs → perpetual incarnation-mismatch → CAS
  thrash / clobber loop. If the writer cannot persist the incarnation (CAS contention,
  CouchDB down), it **fails closed** (retry) — it does not Solr-stamp.
- **Archive restore issues a NEW incarnation; never copies the archived one.**
  `ArchiveDaoDelegate.restoreContent` must generate a fresh UUID for the restored
  Content and MUST NOT copy the incarnation stored in the archived copy — that value
  belongs to the pre-delete lifetime, and reusing it would make the restored write look
  "same incarnation, generation 1 < stored 50" and be permanently refused. A new
  incarnation forces the §4.4 "mismatch → authoritative CAS from current Content" path,
  which correctly overwrites the pre-delete Solr doc.
- **Fail-closed when any of {stored, incoming, current} incarnation is missing.**
  - *stored* (Solr) absent while the doc exists → treat as incarnation MISMATCH →
    recompute + CAS from authoritative Content (do NOT skip-as-newer on generation).
  - *incoming* (the writer's resolved incarnation) absent → the writer could not
    establish an authoritative incarnation → **throw / retry**; never Solr-stamp.
  - *current* (authoritative CouchDB Content) unreadable → three-valued: NOT_FOUND =
    deleted (hand to the purge path), ERROR = **retry**; never proceed on a guess.

Persistent-format note (release notes): new Content field `content_incarnation`;
new patch `Patch_ContentIncarnationBackfill`; the mandatory v3.3 full reindex stamps
`content_incarnation` + `content_generation` alongside the ACL group.

## 9. Required deterministic tests (live-Solr concurrency IT; §8-v1 list superseded)

1. Commit-order inversion across different ancestors (post-commit allocation:
   later-finalized gets the higher epoch; converged readers correct).
2. Cross-document read skew spanning the pending-marker visibility boundary
   (reader saw pre-pending P + post-finalize A) → §4.2/§4.3 converge.
3. ACL source change between step 1 and step 3 (before RTG) → caught by step 4.
4. Source change after step 4 / immediately before the step-5 CAS → next writer's
   recompute converges; no stale final state.
5. Equal epoch + different readers → recompute rule ends at last-finalized readers.
6. Crash after phase-1 commit, before allocation → scanner finalizes; authorization
   cache eviction re-run (§7b).
7. Crash after finalize, before enqueue → scanner enqueues; no lost task.
8. Duplicate outbox enqueue racing task completion → generation CAS keeps the newer.
9. Concurrent endpoint changes on both relationship endpoints.
10. RAG block rebuild with a source change mid-rebuild → pre-add revalidation aborts.
11. Full-reindex batch vs live ACL-UPDATE → higher epoch survives; final sweep
    restores docs deleted by clear-races.
12. Content writer preserves the ACL group byte-identically (read+preserve mode);
    `content_generation` fences stale content writers.
13. Missing `_version_` / unparsable epoch → fail-closed, task retained.
14. PWC: full/single RAG reindex adds no PWC block; existing PWC block deleted;
    delete failure does not report success; durable retry deletes it after restart;
    duplicate purge tasks merge; deletion verified by absence check; no regression
    for non-PWC docs; **delete failure + queue-write failure is impossible to report
    as success (enqueueOrThrow)**; purge runs with RAG disabled and never terminal-
    FAILs; delete/verify are repository-scoped (a same-id doc in another repository
    is untouched).
15. Restore/incarnation: delete → restore (same `_id`, fresh `_rev` 1-*) → the
    restored content IS written to Solr (incarnation mismatch → authoritative CAS,
    never "older-generation" refusal); an ACL_REINDEX task and a RAG_PURGE task on
    the same object complete independently (neither erases the other's obligation).
16. Pre-migration Content (§8.1): a Content with NO `content_incarnation` plus a stale
    Solr doc → the first authoritative write CAS-persists an incarnation on the CouchDB
    Content (never Solr-only) and then stamps Solr; a second concurrent writer reads the
    SAME persisted incarnation and compares generations (no dual-UUID clobber loop);
    `Patch_ContentIncarnationBackfill` is idempotent (skips a Content that already has
    one); a writer that cannot persist the incarnation fails closed (no Solr-only stamp).

## 10. Known adjacent issue (out of scope, tracked)

Descendant `path` staleness on ancestor rename/move is the same "own-`_rev` cannot
order it" class, but is NOT authorization-relevant (display/IN_TREE correctness). It
is explicitly out of the ACL-epoch sign-off scope, must be filed as its own issue,
and the §4.4 content-writer changes must not make it worse (read+preserve mode
copies, never recomputes, the ACL group; path handling is unchanged in stage 1).

---

Implementation is UNBLOCKED as of the 2026-07-24 sign-off (baseline `f251e1c16`) and
proceeds in the staged report order; each increment must land fail-closed (§ sign-off
invariant 9) and is not enabled in a write path until its stage is complete. The PWC
purge fix (§5) was approved and implemented ahead of the epoch work (rounds 5–7).

### Implementation progress

- **Increment 1 — counter foundation (§2.1, §8): DONE.** Per-repository monotonic
  `acl-epoch-counter::{repo}` doc + `_rev`-CAS allocation (overflow-reject,
  conflict-retry, no-consume-on-failed-CAS) + `Patch_AclEpochCounter` (counter doc +
  `(type)` Mango index on `nemaki_conf`). Standalone service; NOT wired into any ACL
  write path (fail-closed until the ACL-UPDATE increment).
- **Increment 1a — counter hardening: DONE.** Strict fail-closed value read
  (`BigDecimal.longValueExact` — rejects fractional / out-of-range / non-finite /
  wrong-type / missing-`_rev` / negative rather than truncating to a lower value); the
  patch re-GETs and requires a live valid counter after a create `409` (a tombstone
  conflict is not "exists"), validates an existing counter (throws → no PatchHistory on
  corruption), and never overwrites a valid one; the missing-counter error separates
  fresh-install bootstrap from live-repository recovery (`max+1`, never reseed).
- **Increment 2 — post-commit finalization + crash-recovery scanner (§2.2/§3): DONE.**
  `AclEpochState` (the 3 outbox states + content-doc field names) +
  `AclEpochFinalizationService.finalizePending` (strict CAS: allocate once, commit to
  `FINALIZED_NEEDS_RECONCILE` only while still `PENDING_EPOCH` with the SAME
  `aclEpochMutationId`; a 409 re-reads and ABANDONS on a different mutation or an
  already-finalized doc — never re-allocates, overwrites, or regresses; no JVM lock) +
  `scan` (Mango `aclEpochState $in [PENDING,FINALIZED]` — a state-less doc is NEVER
  selected; `PENDING → finalize`; `FINALIZED → counted but LEFT` because the enqueue/ACK
  is a later increment; anomalies recorded + retained, never silently skipped;
  bookmark-paged + capped). `Patch_AclEpochStateMangoIndex` adds the `(aclEpochState)`
  index per content DB. **Fail-closed staging: standalone bean, NO scheduler / init /
  cron; `scan`/`finalizePending` have ZERO production callers; it never touches Solr,
  reconcile tasks, or the ACL cache, and never initializes a state-less document.**
  Persistent-format additions (release-note them; view/2.4 carry-over untouched): new
  content-DB document fields `aclEpochState`, `aclEpochMutationId`, `aclSourceEpoch`
  (absent by default) + the `(aclEpochState)` content-DB Mango index.
  - **Increment 2a — scanner PENDING-first, anomaly visibility, snapshot precondition,
    attachment preservation, patch-throw.**
  - **Increment 2b — no cross-pass starvation + UUID mutation ids.** Four passes, each
    with its OWN budget (no shared cap). `aclEpochMutationId` MUST be a canonical UUID
    (`AclEpochState.newMutationId()` for Phase 1); a non-UUID is a fail-closed anomaly. A
    document that still exists but LOST its `aclEpochState` is marker loss (anomaly), not a
    delete-race supersede.
  - **Increment 2c — durable quarantine → GUARANTEED finite-scan progression.** A
    `mutationId:$exists` selector does not match the validator's full validity (a
    null / non-String / blank / non-UUID id, or an invalid epoch, all pass `$exists` yet
    `validate()` rejects them), so such a document sat in a valid selector and, with a
    `>budget` pile of them, blocked trailing valid documents forever (each scan restarts at
    bookmark=null). Fixed by DURABLE QUARANTINE: the instant the scanner sees ANY document
    `validate()` rejects, it CAS-adds `aclEpochQuarantined=true` (ALL original fields
    preserved — inspection/repair) and every scan selector excludes
    `{aclEpochQuarantined:{$exists:false}}`. An anomalous document therefore leaves the live
    selectors after at most one scan and can never block a valid document again; even a
    `>budget` anomaly pile clears in a FINITE number of scans and the trailing valid
    documents are then finalized. The four selectors (all still `(aclEpochState)`-index-
    served, verified by `_explain`): (1) `{state:PENDING, mutationId:$exists, !quarantined}`,
    (2) `{state:FINALIZED, mutationId:$exists, !quarantined}`, (3) `{state:$in[live],
    mutationId:$exists:false, !quarantined}`, (4) `{state:$exists,
    $nin:[PENDING,FINALIZED,RECONCILE_ENQUEUED], !quarantined}`. **Future consideration (out
    of this increment):** when a later increment CLEARS the marker after the RECONCILE_ENQUEUED
    ACK, a delayed finalizer that re-reads the (legitimately) marker-less document must
    distinguish that terminal state from corruption — a separate terminal check is needed
    then (today `stillOursOrOutcome` treats an existing marker-less document as an anomaly,
    which is correct while no increment clears the marker).
  - **Increment 2d — quarantine race / bypass / terminal / failure closed.** (1) The
    quarantine write RE-VALIDATES the freshly-read document on every CAS attempt and
    ABORTS if a concurrent normal Phase 1 already repaired it (valid epoch fields + no
    stray marker) — a repaired ACL mutation is never permanently isolated. (2) The
    quarantine field's contract is strict: absent = process, Boolean `true` = quarantined,
    anything else (`false` / `null` / string / …) is itself an anomaly and is NORMALIZED to
    `true`. CouchDB Mango `$ne`/`$not` do NOT match an absent field, so every selector
    excludes a true marker via `$or({$exists:false}, {$ne:true})` (still index-served) — a
    malformed marker can no longer hide a document. (3) A direct `finalizePending` /
    `validate` REJECTS a quarantined document fail-closed (a repair must clear the marker in
    the same Phase-1 commit — a design contract for the wiring increment). (4) A terminal
    audit pass validates `RECONCILE_ENQUEUED` too (a malformed marker / non-UUID id / invalid
    epoch there is quarantined; a valid one is counted). (5) A quarantine that cannot durably
    persist is NOT swallowed — it increments `quarantineFailures`, records an error, and sets
    `more` so the driver re-scans.
  - **Increment 2e — explicit-null marker (presence contract).** The Cloudant SDK stores an
    explicit JSON `null` as a PRESENT map entry, so `get()!=null` mistook a
    `{aclEpochQuarantined:null}` marker for an ABSENT one (the `$ne:true` branch still selected
    it, but `validate`/`quarantine` treated it as un-marked → finalized / not normalized). Both
    now decide by `containsKey`: absent = process; Boolean `true` = quarantined; any other
    PRESENT value (incl. explicit null) = malformed anomaly → normalized to true. The
    marker/epoch contract is scoped to EPOCH-STATE-BEARING documents (a state-less document is
    normal content, matched by no pass). Javadoc corrected (`$or` exclusion; "all OTHER fields
    preserved, the malformed marker normalized to true").
  - **Increment 2f — adversarial-audit closure (presence on STATE, contention, terminal
    cursor).** A pre-commit multi-lens adversarial workflow (6 lenses, each finding
    independently verified) found six issues; all fixed. (1)+(2) The 2e `containsKey` presence
    fix was applied to the marker only — `quarantine()` and `finalizePending()` still used
    `get(FIELD_STATE)==null`, so an explicit-null `aclEpochState` was mistaken for "repaired
    state-less" (never quarantined / silently skipped). Both now use `containsKey`. (3) A
    finalize CAS non-convergence (CONTENTION on a VALID doc) was thrown as
    `AclEpochAnomalyException` and routed to `quarantine()` (which then aborted silently); it is
    now a distinct `AclEpochContentionException` — recorded (`contended` + `more`), NEVER
    quarantined. (4, P1) The FINALIZED / RECONCILE_ENQUEUED terminal states are TERMINAL-parked
    (no ACK yet), so a VALID terminal doc never leaves the selector; a stateless bookmark=null
    pass re-counted the same `>budget` valid pile every scan and STARVED an anomalous terminal
    doc behind it. Replaced the two per-doc terminal passes with a single CURSORED terminal
    audit that resumes from a PERSISTENT per-content-DB cursor (`acl-epoch-audit-cursor`, no
    `aclEpochState`, matched by no pass) and wraps on exhaustion, so it cycles through the whole
    terminal set across scans — every corrupt terminal doc is reached and quarantined in a
    FINITE number of scans. (5) `FIELD_QUARANTINED` Javadoc corrected to the real `$or`
    exclusion form. Persistent-format addition: the audit-cursor document (release-note it).
  - **Increment 2g — terminal-audit resume-cursor robustness.** The 2f cursor closed terminal
    starvation but its OWN failure modes were open; all closed. (1, P1) An invalid / expired
    STORED bookmark made the terminal audit stall permanently. The pass now self-heals: on a
    `BadRequestException` whose body/message contains `invalid_bookmark` — and ONLY when it came
    from a STORED (not this-scan) bookmark — it CAS-clears the cursor and retries from the top
    ONCE; a general 400 (e.g. a missing pinned index) is NOT mistaken for an invalid bookmark and
    propagates (fail the scan, surface the misconfiguration). (2, P1) The terminal query is PINNED
    to the `(aclEpochState)` index via `use_index=["acl-epoch-indexes","idx_aclEpochState"]`. NOTE:
    the belt-and-suspenders `allow_fallback=false` is a CouchDB 3.4+/Cloudant parameter that
    CouchDB 3.3.x (this project's deployed version) rejects with `invalid_key`, so the
    no-full-scan-fallback guarantee rests on the `use_index` pin plus the `_explain`-verified
    index-served selector, not on `allow_fallback`. (3, P1) The cursor document carries a
    `type=aclEpochAuditCursor` + `schemaVersion`; a FOREIGN document occupying the cursor id is
    reported as a `cursorFailure` and left UNTOUCHED (fail-closed — the terminal audit is skipped
    that scan rather than clobbering an unrelated document). (4, P1) The cursor save is a BOUNDED
    `_rev` CAS retry; a `putBack()==null` (409) is NOT treated as success — it retries, and a
    persistent failure increments `cursorFailures`, records an error, and sets `more` (never a
    silent swallow). (5, P2) A finalize CAS livelock is now covered by a DETERMINISTIC test (a
    subclass forces every target PUT to conflict): `contended==1`, `more==true`, the doc stays a
    valid `PENDING_EPOCH`, never quarantined. Six deterministic ITs were added (invalid stored
    bookmark self-heal reaching a rear anomaly; rebuild + invalidated bookmark self-heal;
    foreign-doc-at-cursor-id untouched fail-closed with its attachment; persistent cursor-save
    conflict surfaced in the summary; a fresh service instance PER scan still advances via the
    durable cursor; deterministic 8-CAS contention). Epoch code remains fail-closed staging
    (standalone bean, zero production callers, no scheduler/init/cron). Verified live: atom 200,
    both patches success, scanner NOT auto-run, zero epoch-state / cursor docs in real content,
    counter present, CMIS create/read unaffected.
