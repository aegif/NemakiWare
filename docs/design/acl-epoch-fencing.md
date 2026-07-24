# ACL-in-Solr: Repository-wide monotonic ACL epoch fencing (design v2)

Status: **DESIGN v2 — awaiting re-sign-off. No epoch implementation code exists or
may be written until sign-off.** Supersedes v1 (this file's previous revision) after
two review rounds. v1's flaws corrected here:

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
  `value+1`; on conflict re-read + retry. **A failed CAS consumes nothing**; gaps
  occur only when an allocated epoch's finalization is later abandoned (harmless —
  only strict monotonicity matters). Long overflow is explicitly rejected (throw).

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
  clears the marker **only after confirming the task document durably exists**.
- A **scanner** (piggybacked on the existing reconciliation scheduler tick) sweeps
  Contents in `PENDING_EPOCH` (crash before finalize → finalize them, §2.2 step 2)
  and `FINALIZED_NEEDS_RECONCILE` (crash before enqueue → enqueue idempotently, then
  advance). Duplicate enqueues are permitted (dedupe by task `_id`); task completion
  racing a re-enqueue is resolved by the queue's existing generation/`_rev` CAS.
- The inline async refresh (today's `ragAclExecutor` traversal) remains as
  best-effort ACCELERATION; the durable queue is the correctness path.

Mango index addition: `(type, aclEpochState)` on content DBs for the scanner.
(Persistent-format note for the release notes: new Content fields `aclSourceEpoch`,
`aclEpochState`, `aclEpochMutationId`; new counter doc type; new Mango index.)

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
- **Second axis — `content_generation`**: the doc's OWN CouchDB `_rev` leading int,
  stamped by content writers and fenced among content writers (skip if stored
  content_generation is newer). Own-`_rev` IS valid here — same document, so
  comparable. ACL epoch orders the ACL group; content_generation orders the content
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

## 5. Reconciliation queue: operations (also covers PWC purge)

Task gains an `operation` field: `ACL_REINDEX` (default; absent = ACL_REINDEX for
old tasks) | `RAG_PURGE`. The scheduler/manual-retry dispatch on it (the current
scheduler ignores reason and always ACL-reindexes — insufficient for purge).
`RAG_PURGE` guarantees: actually calls `ragIndexingService.deleteDocument`; does not
complete the task on failure; verifies absence after delete; shares the deterministic
`_id` with ACL tasks for the same object with **PURGE taking precedence on merge**;
completion CAS cannot delete a task superseded mid-flight (existing generation CAS).
(The PWC purge fix is implemented AHEAD of the epoch work — approved as independent.)

## 6. Conflict table (v2)

| A ↓ \ B → | ACL-UPDATE (higher e) | ACL-UPDATE (equal e) | ACL-UPDATE (lower e) | CONTENT-UPDATE | CREATE/batch | RAG block |
|---|---|---|---|---|---|---|
| ACL-UPDATE | B wins; A skips on re-read | equal-epoch rule §4.3 (same readers → skip; else recompute+CAS) | A wins; B skips | disjoint groups; both preserve the other via RTG copy + `_version_` CAS | fenced create-if-absent; higher epoch wins | separate doc; same contract |
| CONTENT-UPDATE | disjoint | disjoint | disjoint | `content_generation` fence + `_version_` CAS; newer own-`_rev` wins | 409 → update path | disjoint |
| CREATE/batch | epoch fence | §4.3 | batch skips (stored ≥) | 409 → content path | one create wins; loser re-reads | independent |

Invariant (v2): **within the ACL group, strictly-higher effective epoch wins; equal
epoch resolves by the §4.3 recompute rule (equal ≠ idempotent); lower is skipped;
every CAS loser recomputes from authoritative sources.** Within the content group,
newer `content_generation` wins.

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
    for non-PWC docs.

## 10. Known adjacent issue (out of scope, tracked)

Descendant `path` staleness on ancestor rename/move is the same "own-`_rev` cannot
order it" class, but is NOT authorization-relevant (display/IN_TREE correctness). It
is explicitly out of the ACL-epoch sign-off scope, must be filed as its own issue,
and the §4.4 content-writer changes must not make it worse (read+preserve mode
copies, never recomputes, the ACL group; path handling is unchanged in stage 1).

---

Implementation remains BLOCKED until this v2 is signed off. The PWC purge fix (§5)
is approved for independent implementation ahead of the epoch work.
