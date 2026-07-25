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

### 5.1 Quarantined dependency: operational contract (pre-wiring obligation)

A QUARANTINED document is a declaration that its state/epoch may NOT be used as a
correctness basis, so the effective-epoch walk refuses it (increment 3/3a). Accepted
consequence, confirmed in review: **a quarantined ANCESTOR blocks its whole subtree's
ACL-index refresh until repaired.** Reading "just the epoch" from a quarantined document
was explicitly rejected — an exception like that dissolves what quarantine means.

Because the blast radius is subtree-wide, the following are **required before the
effective-epoch walk is wired into the production ACL write path** (they belong to the
ACL-UPDATE / queue increments; none of them exists yet):

1. **The task is RETAINED** — a walk that fails on a quarantined dependency must neither
   delete the reconciliation task nor mark it terminal-FAILED. It stays PENDING so it
   resumes automatically once the quarantine is cleared (the same posture as the
   `RAG_PURGE` blocked-by-disabled-RAG case in §5).
2. **The blocker is identifiable** — the failure must name the QUARANTINED ancestor id (not
   only the blocked object), and expose a metric (e.g. `quarantineBlockedTasks` plus the
   distinct blocking ancestor ids) so an operator can find the one document whose repair
   unblocks an entire subtree. A log line per occurrence at WARN, deduplicated per
   ancestor, so a large subtree does not flood.
3. **Capped backoff** — retries use the queue's existing capped exponential backoff; a
   quarantine is repaired on human timescales, so the retry rate must not be tied to the
   normal poll interval.
4. **Repair is a SINGLE CAS** — the documented repair normalizes the epoch fields AND clears
   `aclEpochQuarantined` in ONE `_rev` CAS. Clearing the marker first would let a scanner
   pass observe the still-anomalous document and immediately re-quarantine it (and the
   direct-finalizer rejection of increment 2d assumes a repair clears the marker in the
   same commit).
5. **Automatic resumption** — after repair, no manual re-enqueue may be necessary: the
   retained task's next attempt succeeds. An IT must prove quarantine → blocked → repair →
   the SAME task completes.

Until these exist, the walk is correct but the operational story is not, which is one
reason increment 3/3a stays off the write path.

### 5.2 `ReadersComputer`: obligations for the WIRING increment

The fenced writer (increment 4) takes its readers from a `ReadersComputer` SPI. That boundary
pins WHEN the readers are computed (between the walk and the RTG) and WHAT is rejected (a null
list, an EMPTY list, or any null/blank token — increment 4b), but it can NOT pin HOW they are
computed: the `Snapshot` deliberately carries epochs, revisions and topology, not the ACL entries
themselves. A computer that read from the ACL cache, or from a stale event payload, would pass
revalidation while writing readers derived from a DIFFERENT ACL state.

The following are therefore **required of the production implementation, in the wiring increment**
(they are contract-only today, stated on the SPI Javadoc):

1. **Authoritative, cache-bypassing** — the strict inherited-ACL walk, never
   `calculateAcl`'s cached result and never an event payload captured earlier.
2. **Including the object ITSELF**, not only its ancestors: its own local ACEs are part of the
   effective readers, and a computer that only re-walked ancestors would silently under- or
   over-grant on a direct `applyAcl`.
3. **Strict failure** — a dependency that cannot be read must THROW, never degrade to a shorter
   set. The writer's empty/null/blank rejection is a backstop, not the primary guarantee: a
   computation that drops ONE inherited grant still returns a plausible non-empty list.
   <br>**CORRECTION (increment 4c, RE-CORRECTED before 5T — the 4c wording was itself wrong).**
   An earlier revision claimed this already held for "an unreadable ancestor / principal". Only the
   ANCESTOR half is true. 4c then replaced it with "a transient principal-DAO fault silently
   shortens the reader set", which is ALSO wrong. Verified in code, the split is:
   <ul>
     <li><b>A TRANSIENT fault does NOT return null.</b>
         `CloudantClientWrapper.queryView` rethrows it as `CmisRuntimeException`, and nothing on the
         path (`PrincipalServiceImpl` → cached DAO → couch DAO) catches it. WHERE it lands differs by
         caller, and that is the real defect: the strict/reconcile path and `AclServiceImpl`'s
         ACL-refresh traversal propagate it (durable retry), but `SolrUtil.createSolrDocument`'s
         NON-strict catch swallows it with a `log.warn`, INDEXES THE DOCUMENT WITH AN EMPTY
         `readers` SET, and enqueues NOTHING. That is the most frequent path — every create/update —
         so the stale-deny persists until the next ACL change or a full reindex.</li>
     <li><b>What genuinely collapses is SYSTEMIC unavailability.</b> `queryView` maps a
         `NotFoundException` (missing design document / view / database — i.e. "the query could not
         be served", not "the principal is absent") to `null`, and `getUserByIdInternal` /
         `getGroupByIdInternal` then treat that null identically to an empty result via
         `CollectionUtils.isEmpty`. On the STRICT path this raises NO signal at all: every principal
         resolves to nothing, rule 2 yields admin-only, the CAS write SUCCEEDS and the reconcile task
         is deleted. Only a tri-state can close this — there is nothing else to observe.</li>
   </ul>
   <b>Security rating, stated plainly:</b> every principal-resolution failure shrinks the token set;
   there is NO over-grant path. 5T is an AVAILABILITY / index-health defect, NOT a confidentiality
   leak. It stays on the wiring gate for that reason.
   <br>Consequently 5T has TWO halves, and shipping only the first would leave the same asymmetry as
   3b (closed under strict, open on the ordinary path): <b>(1)</b> tri-state the principal DAO and
   THROW on `UNAVAILABLE` under strict (`NOT_FOUND` keeps today's omit); <b>(2)</b> stop the
   non-strict index path at `SolrUtil` from ending at a bare `log.warn` — keep the fail-closed empty
   `readers` for visibility, but ENQUEUE the object for reconciliation so the deny is retried
   durably instead of waiting for the next ACL change.
4. **Tested at wiring time** — at minimum: a direct `applyAcl` on the object itself must be
   reflected; an unreadable ancestor must fail the write rather than shorten the readers.
   (A "poisoned ACL cache must not change the readers" test is deliberately NOT listed: under the
   adopted Option A the readers are computed from the SAME raw documents as the epoch, so no cache
   is consulted and such a test would only assert that code we do not call was not called.)

**WIRING GATES (all FOUR must be closed before `AclEpochIndexWriter.write()` is put on the ACL
path):** outbox ACK / enqueue (invariant 5) · migration stamping the initial
`effective_acl_epoch` (else EVERY ACL update fails closed, by design — increment 4a) ·
`content_incarnation` + content-writer fence (else a full-doc add re-clobbers `readers`) · §5.1
quarantine operational contract.

*Principal tri-state (item 3 above) was the fifth gate; it is **CLOSED by increment 5T** and is no
longer a gate. Item 3 is retained as the statement of the requirement 5T satisfies, not as
outstanding work.*

### 5.3 Adopted plan for the readers side (Option A) — increments 5R / 5T / 5S

The `ReadersComputer` SPI introduced in increment 4 is itself the design smell: it exists only
because the authoritative walk does not carry ACLs, and it turns cache-bypass into a contract that
the boundary cannot enforce. **Option A is adopted: the authoritative read happens ONCE and is
projected twice (epoch and readers).** Option B (re-read outside the cache) is rejected — it fixes
the cache but leaves TWO traversals, and a divergent dependency set is exactly the increment-3b
defect class.

- **5R — extract the ACL semantics into pure functions (zero behaviour change).**
  `effectiveAces(chain)` (inheritance merge, inherit-stop / root / `convertSystemPrincipalId` all
  unified HERE), `relationshipReaders(sourceChain, targetChain)` (endpoint union) and
  `readerTokens(aces, resolver)` (token projection, including the empty/absent-ACL → admin-only
  fail-closed rule). The three existing callers — `AclServiceDelegate.calculateAclInternal`,
  `ACLExpander.expandToReaders`, `SolrUtil.relationshipReaders` — are rewired onto that one
  implementation. Split into 5R-a (differential harness + corpus + a cross-path report) and 5R-b
  (the extraction itself, which must not move the golden by one byte).
  - **AUTHORITY WHEN THE THREE PATHS ALREADY DISAGREE: `calculateAclInternal` wins.** It is the
    authorization main line. Where `expandToReaders` / `relationshipReaders` differ from it, they
    are converged onto it in an EXPLICIT behaviour-convergence commit that lands BEFORE 5R-b —
    never folded silently into the extraction, which would make the "golden unchanged" claim
    self-contradictory.
  - **NO NEW RELATIONSHIP SEMANTICS.** Today a relationship's own local ACL is not consulted; the
    readers are the endpoint union. 5R preserves exactly that. If an `ownAces` parameter survives
    on the shared function it is documented as always-empty / ignored; inventing relationship-local
    ACE meaning is out of scope for an extraction.
  - **GOLDEN SCOPE vs 5T.** The 5R golden covers merge + token projection under KNOWN principals
    and a STABLE principal DAO only. Fault injection is deliberately excluded: the
    `UNAVAILABLE → throw` behaviour is a 5T CHANGE, pinned by its own ITs, and mixing it into the
    5R corpus would blur "zero behaviour change".
  - Preserved as-is (reported separately, NOT fixed here): `getAclInheritedWithDefault`'s two
    branches are byte-identical, so `capability.extended.permission.inheritance.toplevel` currently
    has no effect. Changing it would be a behaviour change and would invalidate the golden.
- **5T — tri-state `PrincipalResolver`** (`FOUND` / `NOT_FOUND` / `UNAVAILABLE`): **DONE.** Two
  halves, shipped together (per §5.2 item 3 — the corrected model; an earlier draft of this bullet
  wrongly named the CMIS runtime as the non-strict consumer, which it is not: the CMIS runtime
  compares principal ids as STRINGS and never resolves them here).
  - **(1)** The real work is in the principal DAO, which collapsed BOTH "absent" and "the query
    could not be served" to `null` via a single `CollectionUtils.isEmpty`. `lookupUserById` /
    `lookupGroupById` now return the tri-state from that one collapse point — no new `try`/`catch`,
    because `queryView` already separates `NotFoundException → null` from a 0-row empty list.
    `UNAVAILABLE` throws `PrincipalUnavailableException`; `NOT_FOUND` keeps today's omit. The throw
    is deliberately path-INDEPENDENT: both callers need the same fact and differ only in what they
    do with it.
  - **(2)** The real non-strict consumers are the ORDINARY INDEX WRITE and the RAG live gate. The
    ordinary path in `SolrUtil` no longer ends at a bare `log.warn`: the strict/ordinary decision is
    extracted into `onReadersComputationFailed`, which throws under strict and, on the ordinary
    path, keeps the fail-closed empty `readers` for visibility **but ENQUEUES the object for
    reconciliation** (`READERS_COMPUTATION_FAILURE`), so the stale-deny is retried durably instead
    of waiting for the next ACL change or a full reindex.
  - Verification: 4 ITs on a real CouchDB (unavailability produced by DELETING the design document,
    not by a mock returning null) + 4 unit tests on the seam. Mutation-bound: collapsing the DAO
    back to `null → NOT_FOUND` fails exactly the two `UNAVAILABLE` ITs and nothing else.
  - Accepted residual (P3): the one-line `catch → seam` call inside `createSolrDocument` is not
    itself mutation-covered; `SolrUtilReadersFailureTest` states in its own Javadoc what it does not
    claim.
- **5S — the snapshot carries the RAW LOCAL (pre-merge) ACL of every dependency**, `readers` are
  computed from that same chain at the same `_rev`, and the `ReadersComputer` SPI plus §5.2's
  unenforceable contract are DELETED. Merged results are never carried — only raw local ACEs, so
  the merge has exactly one implementation. Required test: a mutation that forks or disables the
  shared merge must make `calculateAcl` and `Snapshot.readers` DISAGREE.

**5R-a findings (the golden is captured; extraction has NOT started).** Committed as
`AclSemanticsCorpus` (data only), `AclSemanticsGoldenTest` and
`src/test/resources/acl-semantics-golden.txt`. The golden is stable across runs and BINDS: flipping
the absent-`aclInherited` default from TRUE to FALSE fails it. Two properties of the CURRENT
semantics were surfaced while building it, and both must be carried into 5R-b unchanged:

1. **A non-inheriting object's OWN local ACEs are labelled `inherited`, not `direct`.**
   `calculateAcl` only runs the merge (which sets the direct flags) when `!isRoot && inherits`;
   otherwise it returns `content.getAcl()` RAW, and a raw local ACE has `direct == false` by
   default. So `leaf-does-not-inherit`, `the-ROOT-itself-is-the-subject` and
   `orphan-no-parent-but-inherits` all render their own ACEs as `inherited`. This is almost
   certainly not intended (the flag is surfaced to CMIS clients through `compileAcl`), but changing
   it is a BEHAVIOUR change: it must not ride along in the extraction. Raised separately.
2. **The strict/non-strict divergence is exactly one case**: an inheriting node whose parent does
   not resolve. Non-strict degrades to the local ACEs; strict throws. Everything else in the corpus
   is identical under both, which is what makes "strict = same semantics, stricter failure" a safe
   thing to assert.

Also confirmed while writing the corpus: the system-principal conversion is keyed on
`PrincipalId.ANYONE_IN_DB` / `ANONYMOUS_IN_DB` (`"CMIS_ANYONE"` / `"CMIS_ANONYMOUS"`), and it
rewrites BOTH direct and inherited ACEs.

**5R-a cross-path report (DONE — `AclSemanticsCrossPathReportTest`).** Reported in three separate
layers so that "the paths disagree and one must win" is never confused with "this layer legitimately
adds a rule".

- **LAYER 1 (ACE) — no second ACE computation exists.** `expandToReaders` does not re-derive the
  effective ACEs; it calls `calculateAcl`. Pinned for every corpus case by projecting
  `calculateAcl`'s own output with the layer-2 rules and requiring it to equal the production
  expander's output. **No behaviour-convergence commit is needed for the merge itself**, with ONE
  exception, below.
- **LAYER 2 (token) — the token layer adds exactly three rules, all MUST-CARRY into the shared
  `readerTokens`.** (A) a read-permission filter: an ACE without `cmis:read`/`cmis:all` yields no
  token; (B) an absent/empty ACL — and also "nothing survived rule A" — becomes ADMIN-ONLY
  (fail-closed); (C) a principal that resolves to neither a user nor a group contributes nothing
  (this is the under-grant 5T converts to a tri-state; here it is only PINNED). None of these is a
  path disagreement; losing any of them in the move would be a fail-closed regression.
- **LAYER 3 (relationship) — expanding a relationship as ordinary content collapses to admin-only**,
  i.e. the readers of NEITHER endpoint, because a relationship persists an empty local ACL with
  `aclInherited=true` and no parent, so it hits rule B. This is why the index path branches on kind
  and why §5.3 forbids `self expandToReaders`. The union (`read(source) OR read(target)`) and the
  dangling-endpoint-contributes-nothing rule are pinned alongside it.

**LAYER 1 — system principals: a claimed "finding" that was WITHDRAWN after review.** An earlier
revision of this section asserted that `CMIS_ANYONE` grants are lost between the ACE and token
layers, "verified against the live deployment: `principalAnyone` is null". **That assertion was
wrong, and so was the method that produced it.** The check read a key named `principalAnyone` out of
the Browser Binding's `aclCapabilities`; that object exposes only
`{permissionMapping, permissions, propagation, supportedPermissions}`, so the key was simply ABSENT
and the resulting `None` was misread as "the value is null". A missing key was turned into a fact,
and the fact was then written into a test that is meant to be the specification for 5R-b.

The actual, re-verified behaviour — this IS what the shared `readerTokens` must preserve:

- `repositories-default.yml` sets `principal.anyone: GROUP_EVERYONE` (and
  `principal.anonymous: anonymous`); `docker/core/repositories.yml` does not override it and the
  override map merges, so the default survives.
- `convertSystemPrincipalId` therefore rewrites a `CMIS_ANYONE` ACE to
  `principalId = GROUP_EVERYONE`. On the live document `bbc119345228953e3405c85bdb36b096` the
  computed ACL shows BOTH `GROUP_EVERYONE cmis:read direct=true` (that IS the converted local ACE)
  and `GROUP_EVERYONE cmis:read direct=false` (the inherited grant) — so the grant is NOT lost, and
  the indexed `group:bedroom:GROUP_EVERYONE` token is not merely inherited.
- `GROUP_EVERYONE` is a real GroupItem, so `ACLExpander` resolves it and emits
  `group:{repo}:GROUP_EVERYONE`; on the query side `PrincipalServiceImpl` adds the anyone group to
  EVERY authenticated user's token set unconditionally, so it matches.
- **The `anyone:{repo}` token is simply not the channel this deployment uses**, and ACLExpander's
  `PRINCIPAL_ANYONE = "cmis:anyone"` constant is dead code in the shipped configuration — harmless,
  not a defect. Unifying onto an `anyone:` token would be a BEHAVIOUR CHANGE requiring a full
  reindex, so **5R-b must not do it**.

No convergence commit and no configuration change is required. Two things are pinned instead:
`layer1_shippedConfiguration_anyoneIsCarriedOnTheGROUP_EVERYONE_channel` (the path above) and
`layer1_MISCONFIGURATION_anUnresolvableAnyoneIdSilentlyDropsTheGrant` (if `principal.anyone` is
removed or unresolvable the converted ACE contributes no token, and
`PermissionServiceImpl`'s `ace.getPrincipalId().equals(...)` becomes an NPE path on null — there is
no guard). The misconfiguration hardening belongs with the **5T principal tri-state**, not with the
extraction. The genuine asymmetry that survives is narrow: the CMIS runtime's `calcAnyonePermission`
needs only a STRING COMPARISON, whereas the index path additionally needs
`getGroupById("GROUP_EVERYONE")` to RESOLVE — so a missing GroupItem or a transient DAO fault drops
it silently. That is an instance of the already-pinned 5T gap, not a new anyone-specific defect.

**Consistency follow-up (review).** The withdrawal above initially left the report's own
specification helper contradicting it: `projectTokens` still mapped the converted anyone id to an
`anyone:` token — the very unification just ruled out — and the layer-1 loop SKIPPED the
`system-principal*` cases with a stale "known finding" comment, which was precisely what hid the
contradiction. An implementer reading `projectTokens` as the spec would have extracted the opposite
of the pinned behaviour. Fixed: the default fixture now uses the SHIPPED ids
(`principal.anyone = GROUP_EVERYONE`, `principal.anonymous = anonymous`, as separate settings),
`projectTokens` resolves the already-converted id as GROUP-then-USER with no `anyone:` special case,
the `system-principal*` cases are back in the layer-1 loop, and the shipped pin asserts an EXACT
token list plus the ABSENCE of any `anyone:` token. Both directions are mutation-bound:
re-introducing the `anyone:` mapping fails layer 1, and removing the fixture's group resolution
fails both the shipped pin and layer 1.

**5R-b step 1 (DONE — merge + system-principal conversion extracted).** `AclSemantics` now holds
`mergeAces` and `convertSystemPrincipalIds`, and `AclServiceDelegate` delegates to them.
**The golden did not move by one byte.** Two non-obvious properties of the original are preserved
DELIBERATELY and documented on the class: (a) the merge CONVERTS THE TARGET LIST IN PLACE, and the
target is the node's own live `Acl.getLocalAces()`, so the conversion is visible on the (cached)
Content afterwards — making the merge side-effect-free would be a behaviour change, not a cleanup;
(b) the result is built through a `HashMap`, so its ORDER is not contractual. The traversal stays
with each caller (that is the whole point: cached traversal for the CMIS runtime, authoritative raw
traversal for the epoch side, ONE meaning of the ACEs).

**5R-b step 2 (DONE — token layer + relationship union).** `AclSemantics` now also holds
`readerTokens(repo, aces, PrincipalResolver)` — the three rules the cross-path report pinned to this
layer — and `relationshipReaders(source, target)` (the endpoint union). `ACLExpander.expandToReaders`
and `SolrUtil.unionReaders` delegate to them. The `PrincipalResolver` SPI keeps the class free of
I/O; its Javadoc records the PRODUCTION order explicitly (USER first, then GROUP), since that is the
one thing a re-implementation would most easily invert.

The `cmis:anyone` / `cmis:anonymous` literal branch is preserved VERBATIM even though it is dead
code in the shipped configuration: removing it, or conversely routing the shipped anyone id to an
`anyone:` token, would both be behaviour changes requiring a full reindex.

**5R-b gating:** golden UNMOVED across both steps (git diff zero); golden + cross report 7/7;
ACL/permission units 53/53 (`ACLExpanderTest` 33/33 covers the token rules directly); **TCK
Control + Basics + Query 10/10** against a redeployed WAR, with Query exercising the readers `fq`
path; and a live re-index of `bbc119345228953e3405c85bdb36b096` producing byte-identical readers
`[group:bedroom:GROUP_EVERYONE, user:bedroom:admin, user:bedroom:system]`. **Playwright has NOT been
run** — it remains outstanding for 5R-b.

**Next:** 5S — the snapshot carries the raw local (pre-merge) ACLs, `readers` are computed from the
same chain at the same `_rev`, and the `ReadersComputer` SPI plus §5.2's unenforceable contract are
DELETED. (5R and 5T are done.) Production wiring remains NO-GO until the four gates above are closed.

**Process correction:** any "verified live" claim in this document or in a test comment must carry
the command and its raw output. This one did not, and the reviewer's independent Browser-Binding,
CouchDB and REST checks all returned the opposite result.

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
  - **Increment 2h — strict cursor `schemaVersion` (a pre-wiring condition).** 2g wrote a
    `schemaVersion` but never validated it, so an absent / null / non-integral / string / FUTURE
    version was accepted as the current format and OVERWRITTEN with `1` on save. A single
    `cursorUnusableReason(Document)` is now the ONE definition of "this build may use this cursor",
    shared by the read AND the save path (they can never disagree), and re-checked on every save
    CAS attempt: `type` must match exactly, and `schemaVersion` must be PRESENT and a STRICT
    integer (`parseExactLong`) EQUAL to the build's version. **Migration contract: an unusable
    cursor is never implicitly upgraded, downgraded, or clobbered** — it is reported as a
    `cursorFailure` (+ `more`), the terminal audit is SKIPPED, and the document is left untouched;
    the documented operator remedy is to DELETE the cursor (it holds only a resume bookmark, so
    the sweep restarts from the top — exactly the normal wrap — with no correctness loss). A 2f-era
    cursor (type present, no `schemaVersion`) is therefore NOT adopted silently. The stale
    `runCursoredPass` Javadoc ("any cursor read/write failure degrades to bookmark=null") was
    corrected to the real three-way contract: unusable DOCUMENT → skip fail-closed; transient READ
    error → reported + start from the top; SAVE failure → reported + `more`. Six ITs pin
    absent / explicit-null / `1.5` / `"1"` / `2` (each: cursorFailure, terminal audit skipped, and
    `_rev` + properties + inline attachment byte-unchanged) plus a positive control that
    `schemaVersion=1` is used normally. Mutation-tested: deleting the version check fails exactly
    those five ITs. Epoch IT 49/49, epoch unit+IT 82/82.
  - **Increment 3 — effective epoch: authoritative walk + pending gate + revalidation**
    (`AclEffectiveEpochService`, §4.1 and §4.2 steps 1/2/4). The READ half of the unified write
    contract; the Solr realtime-GET, the `_version_` CAS and the fence decision (steps 3/5/6 +
    §4.3) remain for the ACL-UPDATE increment. `snapshot(repo, id)` walks the authoritative
    sources STRAIGHT from CouchDB (never the ACL/content caches, §4.6), recording every
    dependency's `_rev`, `aclSourceEpoch`, `aclEpochState`, `parentId` and `aclInherited`;
    `revalidate(snapshot)` re-reads them all and returns false on ANY difference so the caller
    restarts. The inheritance rule mirrors `AclServiceDelegate.calculateAclInternal` exactly (stop
    at `aclInherited == false` — which the root always has — or at a missing parent; an ABSENT
    `aclInherited` defaults to TRUE). A relationship (a document carrying both `sourceId` and
    `targetId`, i.e. what `CouchRelationship` persists) takes the max over BOTH endpoint chains
    plus its own epoch, matching the read-permission rule `read(source) OR read(target)`.
    Topology changes need no separate check: inserting / removing / re-parenting an ancestor
    necessarily rewrites a recorded document, so its `_rev` differs.
    <br>**Fail-closed rules** (each throws rather than guessing): PENDING gate on
    `PENDING_EPOCH` *and* `FINALIZED_NEEDS_RECONCILE` (mid-CAS ambiguity) — `RECONCILE_ENQUEUED`
    is settled and does NOT gate; ABSENT `aclSourceEpoch` = 0 (§4.1 pre-migration) but a PRESENT
    null / non-integer / negative value is corruption (the 2e/2f presence contract); an unknown or
    non-String state, or a QUARANTINED dependency, is untrustworthy; an inheriting object whose
    parent cannot be read is RETRYABLE (`AclEpochUnavailableException`) rather than silently
    degraded to local ACEs (the strict `calculateAcl` contract — dropping inherited grants would
    compute an under-visible fence value); a cycle or a chain past the hop cap (default 128) fails
    closed; a genuinely dangling relationship endpoint contributes nothing (the
    `SolrUtil.relationshipReaders` precedent); a genuinely deleted TARGET returns `null` so the
    caller completes instead of retrying.
    <br>**Quarantine decision — CONFIRMED in review:** a QUARANTINED *ancestor* blocks its whole
    subtree's ACL-index refresh until repaired; reading "just the epoch" from a quarantined
    document was explicitly rejected. The operational obligations that must accompany it before
    production wiring are §5.1.
    <br>**Staging:** standalone bean, ZERO production callers, no scheduler/init/cron, and it
    performs NO writes at all (read-only against CouchDB). 28 ITs against live CouchDB over the
    real persisted document shape; mutation-tested three ways (removing the pending gate fails 5
    ITs; ignoring `aclInherited=false` fails the walk-stop IT; a always-true `revalidate` fails 6
    ITs). Epoch unit+IT 110/110. Live: atom 200, no bean-context error, nothing auto-runs, and
    zero `aclEpochState` / `aclSourceEpoch` documents in real content.
  - **Increment 3a — four correctness defects in the increment-3 walk (review; 3 was rejected).**
    1. **Dangling endpoints were not recorded.** `walkChain` returned 0 for a 404 endpoint without
       recording anything, and `revalidate` only re-checks RECORDED dependencies — so an endpoint
       recreated under the same id left the relationship document untouched, revalidation passed,
       and a fence value computed WITHOUT that chain could be CAS-ed. Absences are now recorded as
       NEGATIVE dependencies (`exists=false`) and revalidation fails if one has reappeared.
    2. **Quarantine presence contract.** The walk accepted `aclEpochQuarantined=false` as normal,
       contradicting the state machine (absent = usable, `true` = quarantined, ANY other present
       value = malformed). A false-marked corrupt document could contribute a high epoch and fence
       out later correct writers. PRESENCE alone now disqualifies.
    3. **`RECONCILE_ENQUEUED` invariants were unchecked.** Because ENQUEUED deliberately does NOT
       gate, an ENQUEUED document with a missing/non-UUID mutation id or a missing/zero/negative
       epoch fed a wrong fence value directly. FINALIZED and ENQUEUED now both require a canonical
       UUID and a strictly positive epoch.
    4. **Relationship detection and topology extraction were fail-open.** Detection used "carries
       both endpoint fields", so a relationship with one malformed endpoint field was demoted to
       ordinary content and BOTH chains vanished from the fence value; and the id extractor
       collapsed present-null / non-String / blank to "absent". Detection is now the PERSISTED base
       type (`type == "cmis:relationship"` — the value `Relationship`'s constructor writes, so
       SUBTYPED ingest relationships such as `nemaki:hasAttachment` are still detected); a
       relationship MUST carry two present, non-blank endpoint ids; only a resolvable-id-but-404
       referent is dangling; `parentId`/`sourceId`/`targetId` present-null / non-String / blank are
       anomalies; and a NON-relationship carrying endpoint fields is refused rather than guessed
       at. A document with no `type` at all is walked as ordinary content (backward compatibility)
       — safe because the endpoint-field rule rejects it if it might be a relationship.
    <br>**Shared validator (the structural fix):** the finalization service and the effective-epoch
    service each had their own validation and their own nested `AclEpochAnomalyException`, which is
    how #2 and #3 arose. There is now ONE top-level `AclEpochAnomalyException` and ONE
    `AclEpochFields` validator (`requireNotQuarantined` + `validate(..., stateRequired)`) that both
    sides call, so the state machine's owner and its consumer cannot diverge again.
    <br>Verification: 43 effective-epoch ITs (+15) and 49 finalization ITs unchanged (the refactor
    is behaviour-preserving); epoch unit+IT 125/125. Each of the four fixes is mutation-bound:
    not recording absences fails 2 ITs; accepting a `false` marker fails 1 walk IT + 1 finalization
    IT; dropping the ENQUEUED invariants fails 4 walk ITs + 2 finalization ITs; reverting to the
    endpoint-field heuristic fails 2 ITs. Live: atom 200, nothing auto-runs, real content untouched.
  - **Increment 3b — state loss, kind validation, hop-cap boundary (review).**
    1. **(P1) State lost, mutation id survived.** `validate` returned immediately when
       `aclEpochState` was absent, without checking `aclEpochMutationId` — but the steady state
       clears BOTH. A move whose marker was dropped therefore left a document whose stale
       `aclSourceEpoch` was consumed as "settled", and because EVERY scanner selector keys on
       `aclEpochState` it was invisible to all of them: permanently stale, able to fence out later
       correct writers. Now state-absent + mutation-id-present (any value) is an ANOMALY, AND the
       scanner has a dedicated pass for that shape, served by a new `(aclEpochMutationId)` Mango
       index (`Patch_AclEpochMutationIdMangoIndex` — a JSON index cannot serve `$exists:false` on
       the field it indexes, and this one holds only mutation-bearing documents, i.e. nothing in
       steady state). `quarantine()`'s "repaired" definition was corrected in the same change:
       state-less counts as repaired ONLY if the mutation id is gone too — otherwise the quarantine
       aborts and the new pass re-selects the same document on every scan for ever.
    2. **(P1) Document-kind validation.** `type` absent / null / unrecognised was accepted as
       ordinary content, and ANY document at `parentId` was accepted as an ancestor — while the
       real readers computation resolves ancestors through `getFolder()`, which returns null for a
       non-folder and then fails closed under strict mode. The two layers could therefore walk
       DIFFERENT dependency sets. A `ContentKind` is now resolved exactly as
       `ContentDaoServiceImpl.getContent` does (`type` else `objectType`; the legacy short forms
       `"folder"` / `"document"` / … are accepted because the DAO accepts them), an absent /
       malformed / unrecognised discriminator is an anomaly (no runtime guessing — pre-discriminator
       data needs an explicit migration), and an ANCESTOR must be `FOLDER`. The old test that
       pinned the guessing behaviour is inverted.
    3. **(P2) Hop-cap off-by-one.** The stop condition was only evaluated at the top of the next
       iteration, so a chain of EXACTLY `maxAncestorHops` ancestors threw — permanently blocking a
       legitimately deep subtree from being re-indexed. The cap is now checked only when another
       ancestor is actually required, with boundary ITs for exactly-at and one-past.
    <br>Verification: 52 effective-epoch ITs + 52 finalization ITs; epoch unit+IT 137/137. Five
    mutations, each failing only its own tests: accepting a leftover mutation id fails 2 walk ITs;
    reverting the quarantine "repaired" definition leaves the document unquarantined after 6 scans
    (the starvation this closes); guessing a kind fails the inverted IT; dropping the folder
    requirement fails 1 IT; restoring the off-by-one fails the exactly-at-cap IT. Live: the new
    patch registered `(aclEpochMutationId)` on both repositories, atom 200, nothing auto-runs, and
    zero mutation-bearing documents exist in real content.
    <br>⚠ Persistent-format addition: a second Mango index `(aclEpochMutationId)` per content DB.
  - **Increment 3c — direct finalizer + index pinning (review).**
    1. **(P1) The direct finalizer still skipped state loss.** `finalizePending(repo, docId)`
       returned `SKIPPED_NOT_PENDING` whenever `aclEpochState` was absent, without consulting the
       shared validator — so the "state absent + mutation id present" corruption that 3b defined
       was reported as a CLEAN finish on the public entry point (the scanner caught it, but a
       future Phase 2 calling this path directly would not). The contract is now: NEITHER field →
       ordinary content, clean skip; state absent BUT mutation id present → anomaly; any other
       violation (quarantine marker, non-UUID id, invalid epoch) → anomaly. Every check runs BEFORE
       `counterService.allocate`, so a rejected finalize consumes no epoch.
    2. **(P2) The scan passes were not pinned to their indexes.** `runPass` sent no `use_index`, so
       a missing or half-rebuilt index let CouchDB fall back to `_all_docs` — a full scan of a
       large content database on every scheduler tick once the scanner is auto-started. All four
       passes are now pinned (`idx_aclEpochState`, and `idx_aclEpochMutationId` for the new one),
       and the scan FAILS rather than full-scanning. **Measured CouchDB 3.3.3 behaviour:** a
       `use_index` naming a missing index is NOT an error — it returns HTTP 200 having silently
       used `_all_docs`, with only a `warning` field to show for it (and `allow_fallback=false`,
       which would make it an error, is a 3.4+/Cloudant parameter 3.3.x rejects). The guarantee is
       therefore a DETERMINISTIC pre-flight existence check on both indexes at the top of `scan()`,
       with the per-query warning as defence in depth — narrowed to the genuine "was not used" /
       "no matching index found" phrases, because CouchDB also emits a purely advisory
       "documents examined is high" warning for the anomaly passes, which ARE index-served.
    <br>**Javadoc corrections (also review 3c):** "a state-less document is matched by no pass" is
    no longer true after 3b (a leftover mutation id IS selected), and "resolved EXACTLY as
    `ContentDaoServiceImpl.getContent`" overstated it — the DAO falls back to a generic
    `CouchContent` for an unknown type whereas the epoch side treats that as an anomaly. Both now
    say what the code does.
    <br>Verification: 57 finalization ITs + 52 walk ITs + 4 new patch ITs; epoch unit+IT 146/146.
    Mutation-bound: reverting the direct-finalizer guard fails its IT; removing the pre-flight (and
    the warning backstop) fails both missing-index ITs. New ITs cover the direct finalizer (anomaly
    + no epoch consumed, and the clean-skip counterpart), null/numeric/blank mutation ids found via
    the REAL index, scan failure with either index missing, and the patch's idempotency + its
    throw-so-PatchHistory-is-not-recorded failure paths. Live: both indexes present, atom 200,
    nothing auto-runs, CMIS unaffected.
  - **Increment 3d — the last validator bypass, and index DEFINITION verification (review).**
    1. **(P1) A short-circuit still sat ahead of the shared validator.** 3c kept a "NEITHER state
       nor mutation id → ordinary content" fast path, which meant a leftover
       `aclEpochQuarantined` (an INCOMPLETE repair: both fields cleared, marker forgotten) and a
       corrupt `aclSourceEpoch` (`-1` / explicit null / `1.5`) were still reported as a CLEAN skip
       by the direct finalizer. The order is now exactly: `requireNotQuarantined` →
       `validate(…, stateRequired=false)` → clean skip iff the VALIDATED state is null → id/rev +
       finalize. Ordinary content still skips, but on the validated result rather than a guess, so
       the shared validator is genuinely the only definition of "usable".
    2. **(P2) The index pre-flight matched names only.** A same-named index in a different design
       document, of a different type, over a different field, with a different direction, with
       extra fields, or carrying a partial-filter selector would pass — and then NOT serve the
       query, so every scheduler tick would full-scan, permanently (the warning backstop only fires
       AFTER a full scan has already happened). The pre-flight now exact-matches the reported
       `IndexInformation`: `ddoc == _design/acl-epoch-indexes`, name, `type == json`, exactly one
       field `{expected: "asc"}`, and no partial-filter selector / text-index knobs.
    <br>Verification: 66 finalization ITs + 52 walk ITs + 4 patch ITs; epoch unit+IT 155/155.
    Mutation-bound: restoring the fast path fails the two new direct-finalizer ITs; reverting to a
    name-only pre-flight fails all FIVE mis-defined-index ITs (wrong ddoc / wrong field / wrong
    direction / partial / extra fields), confirming that a permanently wrong definition would
    otherwise full-scan on every tick. Positive controls pin that the steady state (marker cleared,
    valid epoch, no state) is still a clean skip and that a correctly-defined database still scans.
  - **Increment 4 — the fenced ACL-group writer (`AclEpochIndexWriter`, §4.2 steps 3/5/6 + §4.3).**
    Completes the unified write contract whose read half is increment 3. The order is fixed exactly
    as specified: walk → compute → **RTG** → dependency **revalidate** → **CAS**. The realtime GET
    precedes the revalidation so that ANY Solr write landing after it — including a correct
    writer's — fails our CAS; a searcher query is never used (it lags by the soft-commit interval).
    On **409 the payload is discarded and the whole walk restarts** — never a CAS retry with the
    same readers. The §4.3 decision is implemented verbatim: stored epoch **>** mine → skip;
    **<** → CAS; **==** with identical canonical readers → idempotent skip; **==** with divergent
    readers → **authoritative recompute, then CAS the recomputed value** (never "my payload wins").
    Only the ACL group (`readers` + `effective_acl_epoch`) is written, as an atomic `{set}` — name,
    path, content and every other field are untouched (§4.4). Pending / quarantine / unavailable
    propagate so the caller RETAINS its task (§5.1); exhausted restarts raise a retryable contention
    exception rather than a silent success; a missing / non-numeric `_version_` fails closed; a
    cross-repository id collision is refused.
    <br>Readers are supplied through a `ReadersComputer` SPI rather than hard-wired to
    `ACLExpander`, which is what keeps this increment unwired AND lets the concurrency ITs drive the
    protocol deterministically.
    <br>**Staging:** standalone bean, ZERO production callers, no scheduler / init / cron.
    <br>**Verification:** 14 ITs against LIVE CouchDB + LIVE Solr; epoch unit+IT 169/169. The
    determinism comes from overriding the package-private `realtimeGet` — exactly the point between
    step 3 and step 5 — to inject a competing Solr write or a CouchDB dependency mutation, so the
    race always happens instead of depending on timing. Covered: same-epoch collision (identical →
    idempotent, divergent → authoritative recompute), 409 (restart + convergence, and a persistent
    conflict → retryable contention), a dependency changed after the walk (restart, and the final
    write carries the NEW epoch), pending and quarantine (NOTHING is written), ACL-group atomicity
    (name / path / content / content_length survive), NOT_INDEXED, deleted, null-readers refusal and
    cross-repository collision. Three mutations bind: skipping the revalidation fails the
    dependency-change IT; letting the payload win on an equal epoch fails the divergence IT; caching
    the snapshot + payload across retries (genuine payload reuse) fails three ITs.
    <br>⚠ Persistent-format addition: a new Solr field `effective_acl_epoch` (long, optional).
    Existing deployments must apply the updated `schema.xml` and RELOAD the core — the Solr data
    directory is a volume, so an image rebuild alone does NOT update the schema. The field is
    additive and optional, so an un-updated core keeps working until the writer is wired.
  - **Increment 4a — five defects in the fenced writer (review).**
    1. **(P1) The equal-epoch recompute could roll an epoch BACKWARDS.** With the recompute flag
       set, the next attempt bypassed the WHOLE fence decision — including `stored > mine`. If
       another writer landed epoch 9 between the divergence observation and the recompute's RTG,
       the recompute read THAT document's `_version_` and CAS-ed epoch 7 over it successfully.
       The flag now authorises exactly one thing — writing an equal-epoch value whose readers
       diverge — while `stored > mine` and equal+identical are re-evaluated on EVERY attempt.
    2. **(P1) `_version_` was not validated as a real compare-and-set.** Solr overloads it: `0` =
       no concurrency check, `1` = "any existing version", negative = must-not-exist. A strict
       integral conversion plus `> 1` is now required, so the documented CAS cannot silently
       degrade into an unconditional write.
    3. **(P1) An absent stored epoch was accepted as 0**, contradicting §4.3 — and the test that
       claimed to pin this was named "non-numeric fails closed" while actually asserting that the
       ABSENT case SUCCEEDS. An unfenced document now fails closed; stamping the initial epoch
       belongs to the migration / full-reindex path, not to a normal ACL-UPDATE.
    4. **(P2) A missing `repository_id` was accepted.** Absent / blank / non-String now fails
       closed. The check also MOVED out of `realtimeGet` into the caller, so no alternate or
       overridden fetch path can bypass the repository boundary.
    5. **(P2) Invalid tokens in the AUTHORITATIVE readers were silently dropped.** A partial SPI
       failure would have been persisted as a normal-looking SHORTER (under-granting) set. Incoming
       readers now reject a null list and any null/blank token; normalization of ALREADY-STORED
       readers stays lenient (it is a fact to compare, not a computation to validate — and
       rejecting it would block the very write that repairs it).
    <br>The `ReadersComputer` Javadoc now states the mandatory production contract: compute from
    the AUTHORITATIVE, cache-bypassing strict walk for the object ITSELF as well as its ancestors,
    never from a stale event payload or the ACL cache. The `Snapshot` pins epochs / revisions /
    topology but deliberately does not carry the ACL entries, so the SPI boundary alone cannot
    enforce this.
    <br>Verification: 19 ITs (14 + the 6 required, one replacing the mis-named test); epoch unit+IT
    174/174. All five fixes are mutation-bound. Note: the first version of the epoch-rollback test
    did NOT bind — it injected the newer epoch BEFORE attempt 1's RTG, so attempt 1 skipped as
    fresher and the divergence path was never entered; moving the injection to AFTER attempt 1's
    RTG makes the mutation reproduce the rollback exactly (UPDATED instead of SKIPPED_FRESHER).
  - **Increment 4b — three P3 consistency fixes (review).** (1) `realtimeGet`'s Javadoc still read
    as though IT performed the repository-mismatch check, which moved to `write()` in 4a — a future
    override author could have assumed the boundary was enforced inside the method they were
    replacing. It now says the opposite explicitly. (2) The SPI Javadoc forbade an empty readers
    list but the writer only rejected null / null-or-blank TOKENS, so an empty list would have been
    written: the writer now ENFORCES what the SPI promised (an authoritative expansion is itself
    fail-closed and always emits at least the admin role token, so empty can only mean the
    computation failed). (3) The `ReadersComputer` cache-bypass / self-inclusive / strict
    requirements — which the SPI boundary cannot enforce — are recorded as §5.2, a pre-wiring
    obligation with its own required tests, so they are not lost between increments.
    <br>Verification: 20 ITs; the empty-list rejection is mutation-bound.
  - **Increment 4c — §5.2 over-claim withdrawn, wiring gates named, Option A adopted.** §5.2 item 3
    had claimed unreadable ancestors AND principals both throw; only the ancestor half was true, so
    the principal half was re-stated as an open gate (now closed by 5T). See §5.3.
  - **Increment 5R — ACL semantics extracted into pure functions (zero behaviour change).** 5R-a
    captured the golden + the cross-path report; 5R-b moved the inheritance merge, the token layer
    and the relationship union into `AclSemantics`, leaving ONE implementation for all three former
    paths. The golden did not move. See §5.3.
  - **Increment 5T — principal tri-state + durable retry on the ordinary index path: DONE.**
    Detail in §5.3; this closed the fifth wiring gate.
