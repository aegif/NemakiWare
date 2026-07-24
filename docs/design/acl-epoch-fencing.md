# ACL-in-Solr: Repository-wide monotonic ACL epoch fencing (design)

Status: **DESIGN — not yet implemented.** This document must be reviewed and
accepted before any code is written. It supersedes the round-3 `_rev`-generation
fence and the (rejected) round-4 `max(ancestor _rev)` approach.

## 1. Problem and why prior approaches fail

ACL-in-Solr stores a `readers` token set on every queryable Solr doc so the CMIS
query and RAG paths can pre-filter by the caller's principals. When an ACL changes
(applyAcl / move / inheritance toggle / relationship endpoint change) the affected
docs must be re-indexed with fresh `readers`. These re-index writes are async and
can fail (Solr down) → a durable reconciliation queue re-drives them.

The **convergence requirement**: after all writers settle, each Solr doc's `readers`
must reflect the LATEST committed CouchDB ACL, for **every** writer path (normal
async ACL-refresh, reconcile re-drive, CMIS batch/full-reindex, RAG block writer)
and must not be undone by a late "stale" writer.

Two rejected fence values:

- **Round 3 — the object's own CouchDB `_rev` leading integer.** Fails because a
  PARENT ACL change does not bump a CHILD's `_rev`, and an ENDPOINT ACL change does
  not bump a RELATIONSHIP's `_rev`. So for inherited descendants / relationships —
  the majority of ACL-refresh writes — two writers compute the SAME fence value and
  the CAS only serializes (last-writer-wins); a late stale writer overwrites a fresh
  one. "Highest generation wins" holds only for the directly-ACL-changed object.

- **Round 4 (rejected by review) — `max(_rev)` over the object + inheriting
  ancestors.** Fails because **`_rev` values of DIFFERENT CouchDB documents are not
  comparable** — each is an independent per-document counter. `max` over different
  documents is semantically meaningless and is **not monotonic** for a given object:
  it can DECREASE when the ancestor set changes (move to a shallower parent, or an
  ancestor dropping out of the inheritance chain), which would wrongly fence out a
  legitimately-newer write. **This approach is abandoned.**

## 2. Design: repository-wide monotonic ACL epoch

### 2.1 The counter (issuance)

- One **per-repository** counter document in `nemaki_conf`:
  - `_id = acl-epoch-counter::{repositoryId}`
  - fields: `{ "type": "aclEpochCounter", "repositoryId": "...", "value": <long> }`
- **Allocation (`allocateAclEpoch(repo) -> long`)**: read the counter doc, compute
  `next = value + 1`, CAS-write via CouchDB `_rev` (`putDocument` catching
  `ConflictException`). On conflict, re-read + retry (bounded loop; monotonic
  progress is guaranteed because every successful CAS strictly increases `value`).
  The counter is created lazily (create-if-absent at `value = <baseline>`).
- **Gap tolerance**: gaps are irrelevant — only strict monotonicity matters. A
  failed CAS that is retried may "waste" a value; that is fine.
- **Baseline / migration watermark**: the counter starts at a value strictly greater
  than any epoch a pre-migration doc could be interpreted as. Pre-migration docs have
  NO `aclSourceEpoch` (interpreted as epoch `0`), so a baseline of `1` suffices, but
  we start at a **timestamp-derived baseline** (e.g. `System.currentTimeMillis()` at
  first allocation, persisted) so that even a counter doc lost + recreated cannot
  re-issue an already-used low value that a stale in-flight writer still holds.
  (DECISION NEEDED — see §7 Q1.)

### 2.2 `aclSourceEpoch` on Content (persistence)

- New model + CouchDB field on `Content`: `aclSourceEpoch` (long, default absent →
  treated as `0`).
- It is set to a **newly-allocated** epoch on, and ONLY on, an operation that changes
  the object's OWN effective ACL SOURCE:
  - **applyAcl(X)**: `X.aclSourceEpoch = allocate()`.
  - **move(X)**: `X.aclSourceEpoch = allocate()` (the inheritance chain / effective
    ACL of X changed).
  - **inheritance toggle** (`aclInherited` flip on X): `X.aclSourceEpoch = allocate()`.
  - **relationship re-point** (source/target changed on a relationship R):
    `R.aclSourceEpoch = allocate()`.
- The epoch is allocated and the object persisted **inside the existing per-object
  write lock** (`threadLockService`), so concurrent applyAcls on the SAME object are
  serialized and the object's final `aclSourceEpoch` is the epoch of the
  last-committed change (allocation order == commit order under the lock). See the
  conflict table (§6, row A).
- Ordinary content changes (createDocument body, updateProperties, checkIn) do NOT
  allocate or change `aclSourceEpoch`.

### 2.3 Effective epoch (computed at index time)

The fence value stamped in Solr is `effective_acl_epoch`:

- **Regular object X**:
  `effectiveEpoch(X) = max(X.aclSourceEpoch, effectiveEpoch(parent(X)))` while X
  inherits; stop at the root or a non-inheriting node. Equivalent to
  `max(aclSourceEpoch)` over X + its inheriting ancestor chain. Because every
  `aclSourceEpoch` comes from the SAME global counter, this max IS comparable and IS
  monotonic for X: any effective-ACL change to X (own or an inheriting ancestor)
  allocates a strictly-greater epoch on some node in the chain, so `effectiveEpoch(X)`
  strictly increases; nothing in the chain can decrease a node's `aclSourceEpoch`.
  Move changing the ancestor set is safe: X's OWN `aclSourceEpoch` was bumped by the
  move to a value greater than any prior chain member, so even if the new (shallower)
  chain has smaller ancestor epochs, `effectiveEpoch(X) >= X.aclSourceEpoch` is still
  strictly greater than before the move.
- **Relationship R**:
  `effectiveEpoch(R) = max(effectiveEpoch(source), effectiveEpoch(target),
  R.aclSourceEpoch)`. An endpoint ACL change bumps that endpoint's effective epoch →
  R's; a re-point bumps `R.aclSourceEpoch`.
- Reads: the ancestor / endpoint walk uses the SAME reads `calculateAcl` makes. On
  the reconcile (strict) path the reads are cache-bypassing and an unreadable
  ancestor/endpoint THROWS (retry) rather than truncating the max (which would fence
  low and let a stale writer win).
- **Performance**: this is an ancestor walk per index write. It reuses `calculateAcl`'s
  walk; a follow-up may compute readers + epoch in ONE walk. Batch/full-reindex amortizes.

### 2.4 Solr field ownership (separation of concerns)

Each Solr doc has two disjoint field groups with different owners:

| Group | Fields | Owner | Write mode |
|---|---|---|---|
| Content | id, name, path, parent_id, body/`text`, `content_length`, dynamic props, `_root_`, … | CMIS content writers | create = full add; update = atomic `{set}` of changed content fields |
| ACL | `readers`, `effective_acl_epoch` | ACL writers only (applyAcl/move/reconcile/RAG) | atomic `{set}`, epoch-fenced + `_version_` CAS |

- **A full-content write MUST NOT recompute or overwrite `readers` /
  `effective_acl_epoch`.** A body/property update uses a Solr ATOMIC update of only
  the changed content fields, leaving the ACL group untouched.
- **CREATE is the only place both groups are written together** (a brand-new doc has
  no existing ACL group to preserve), as a create-if-absent (`_version_ = -1`) full
  add carrying the initial `readers` + `effective_acl_epoch`.

## 3. Write modes and the unified CAS / ordering contract

Three modes, all sharing the `_version_` optimistic-concurrency + epoch fence where
they touch the ACL group:

1. **CREATE (content + ACL, create-if-absent)** — `_version_ = -1`; on 409 (already
   exists) fall through to the appropriate update mode after a realtime GET.
2. **CONTENT-UPDATE (atomic content fields only)** — never touches the ACL group; no
   epoch fence needed (does not race the ACL group). `_version_` CAS on the doc to
   avoid lost updates vs another content update, re-read on 409.
3. **ACL-UPDATE (atomic `readers` + `effective_acl_epoch`, epoch-fenced)** — realtime
   GET the current `_version_` + `effective_acl_epoch`; **SKIP if stored epoch > mine**
   (a strictly-newer effective ACL already landed); else atomic `{set}` with the read
   `_version_`; on 409 re-read + re-evaluate; on missing/unparsable epoch or version
   **FAIL CLOSED** (throw → enqueue/retry) on the reconcile path.

Participation of every writer:

| Writer | Mode(s) | Notes |
|---|---|---|
| CMIS create | CREATE | initial epoch = effectiveEpoch at create |
| CMIS update-body / properties | CONTENT-UPDATE | never touches ACL group (fixes round-3 clobber) |
| applyAcl / move async refresh | ACL-UPDATE (self + inheriting descendants + reverse-looked-up relationships) | epoch-fenced |
| reconcile re-drive | ACL-UPDATE (strict epoch, fail-closed) | drives to CURRENT epoch (fresh compute) |
| CMIS batch / full reindex | CREATE per doc, but epoch-fenced (see §5) | must not lose to a concurrent ACL-UPDATE |
| RAG block writer (indexToSolr / updateDocumentACL) | block CREATE / ACL-UPDATE | realtime GET parent, `_version_` CAS, epoch-fenced (see §5.3) |

## 4. Reconciliation task dedupe (unchanged core + epoch note)

- Task keyed by deterministic `_id = search-index-acl-reconcile::{repo}::{object}`;
  concurrent enqueues collapse to one doc; `_rev` CAS lifecycle (PENDING/LEASED/…).
- The task does **NOT** carry a target epoch. The re-drive reads the object FRESH and
  computes the CURRENT `effectiveEpoch`, so it always drives to the latest ACL. If the
  ACL changes again after enqueue, the re-drive naturally targets the newer epoch.
- The epoch fence in ACL-UPDATE guarantees the re-drive's write is dropped if a fresher
  epoch already landed (no stale overwrite), and completes (deletes the task) only when
  the write lands OR is superseded by a strictly-newer epoch.

## 5. State transitions (the required tables)

### 5.1 Counter issuance

| Event | Pre | Action | Post | Conflict handling |
|---|---|---|---|---|
| allocate() first ever | counter absent | create `{value: baseline}` (create-if-absent) | `value = baseline` | 409 → someone created; re-read + increment |
| allocate() | `value = n` | CAS `value = n+1` on `_rev` | `value = n+1` | 409 → re-read + retry (monotonic) |

### 5.2 Content ACL-source persistence (per-object lock held)

| Event | Pre | Action (in lock) | Post |
|---|---|---|---|
| applyAcl(X) | `X.aclSourceEpoch = a` | `e = allocate()`; set ACL; `X.aclSourceEpoch = e`; persist | `e > a`; async ACL-UPDATE of X + descendants + rels |
| move(X) | `X.aclSourceEpoch = a` | `e = allocate()`; move; `X.aclSourceEpoch = e`; persist | `e > a`; async ACL-UPDATE of moved subtree + rels |
| inheritance toggle(X) | `X.aclSourceEpoch = a` | `e = allocate()`; `X.aclSourceEpoch = e`; persist | `e > a` |
| relationship re-point(R) | `R.aclSourceEpoch = a` | `e = allocate()`; `R.aclSourceEpoch = e`; persist | `e > a` |

### 5.3 RAG block state transitions

| State | Event | Action | Fence |
|---|---|---|---|
| absent | index | build block (parent+chunks) with `effective_acl_epoch`; add create-if-absent | `_version_=-1`; 409 → re-evaluate |
| present | ACL-UPDATE | realtime GET parent (`/get`) for `_version_`+epoch; SKIP if stored epoch > mine; else rebuild block readers+epoch; add with `_version_` CAS | 409 → re-read; lease checkpoint before add |
| present | content re-embed | rebuild block (chunks+vectors) — CONTENT-UPDATE semantics: preserve readers+epoch from realtime GET | `_version_` CAS |
| present | PWC detected | delete block (never index a PWC — single choke point `RAGIndexingServiceImpl.indexDocument`) | n/a |

### 5.4 Full reindex

| Phase | Action | Concurrency rule |
|---|---|---|
| clear | `deleteByQuery(repository_id)` | new writes after clear are fine (create-if-absent) |
| re-add (batch) | per doc: compute `effectiveEpoch` fresh; CREATE (content+ACL) create-if-absent | if a concurrent ACL-UPDATE already created/updated the doc with a **higher** epoch, the batch's create-if-absent 409s → batch re-reads → SKIP (stored epoch >= its own) so it never lowers the epoch; if lower-or-equal it may re-add. Batch participates in the epoch fence (NOT a plain add — this is the round-3 #3 fix). |
| RAG rebuild | `triggerFullRAGReindex` re-embeds; per block create-if-absent + epoch | same fence |

## 6. Conflict table (writer × writer, same object)

Legend: **W** = wins (its readers/epoch is final), **S** = skipped/dropped by fence,
**R** = retried.

| A ↓ \ B → | ACL-UPDATE(e2>e1) | ACL-UPDATE(e1, stale) | CONTENT-UPDATE | CREATE(batch) | RAG ACL-UPDATE |
|---|---|---|---|---|---|
| ACL-UPDATE(e1) | B **W** (e2>e1), A **S** on re-read | higher-epoch **W**, lower **S** | independent field group (no conflict) | epoch fence: higher **W** | separate doc (RAG block) — own epoch fence |
| CONTENT-UPDATE | independent (ACL vs content group) | independent | `_version_` CAS, later **W**, other **R** | CREATE only if absent; else content atomic | independent |
| CREATE(batch) | ACL-UPDATE higher epoch **W** | batch **W** if higher/equal | create-if-absent 409 → content atomic | one CREATE wins, other 409→update | independent |

Key invariant: **within the ACL field group, the strictly-highest `effective_acl_epoch`
always wins; equal epochs are idempotent (same ACL source); a lower epoch is always
skipped.** Because epochs are globally monotonic and allocated under the per-object
lock in commit order, the last-committed ACL change has the strictly-highest epoch, so
no late stale writer (any path) can undo it.

## 7. Migration / restore, and open decisions

- **Migration**: pre-epoch docs have no `aclSourceEpoch` (→ `0`). The mandatory v3.3
  full reindex stamps `effective_acl_epoch` (initially `0` for untouched docs). First
  ACL change on any doc allocates a real epoch. A doc at epoch `0` is fenced leniently
  (any real-epoch write wins), which is correct (any real ACL change supersedes the
  pre-migration state). A `Patch_AclEpochCounter` creates the counter doc + a Mango
  index if needed.
- **Restore** (archive restore of an object): treat as a new ACL source → allocate on
  restore so the restored object's readers are re-established with a fresh epoch.

Open decisions requiring sign-off before coding:

- **Q1 — counter baseline**: fixed `1`, or timestamp-derived, or a persisted
  high-watermark? (Affects counter-doc-loss safety.)
- **Q2 — CONTENT-UPDATE atomic-only**: switching CMIS body/property indexing from
  full add to atomic content-field updates is a broad change to `indexDocumentInternal`
  and every field it writes. Acceptable, or scope CONTENT-UPDATE to "full add that
  READS + preserves the existing readers/epoch" as a smaller first step?
- **Q3 — batch epoch fence**: per-doc create-if-absent + re-read on 409 slows full
  reindex. Acceptable, or gate full reindex behind a repository-wide "ACL writes
  paused" flag instead?
- **Q4 — effective-epoch walk cost**: compute per index (ancestor walk) now, or
  denormalize a cached `effectiveEpoch` (needs propagation on ancestor change)?
- **Q5 — relationship reverse-index scope**: on an endpoint ACL change, which
  relationships get an ACL-UPDATE (already reverse-looked-up today) and do they need
  their own re-drive tasks?

## 8. Required deterministic tests (live-Solr concurrency IT, to be built with impl)

1. Parent ACL change → child's `effective_acl_epoch` increases → a stale child writer
   (old epoch) is SKIPPED.
2. Endpoint ACL change → relationship epoch increases → stale relationship writer
   SKIPPED.
3. Full-reindex batch vs concurrent ACL-UPDATE → higher epoch wins (batch does not
   lower the epoch).
4. applyAcl/move root uses the POST-persist revision/epoch (not the pre-persist one).
5. Strict ancestor/endpoint ERROR → NO Solr add on any of CMIS / relationship / RAG.
6. RAG parent read via realtime GET (not searcher) during commitWithin window.
7. reconcile success + task delete, then release an old normal ACL writer → final
   readers = the newer epoch.
8. missing `_version_` / unparsable epoch → fail-closed (throw → task retained), no
   unconditional write.
9. CONTENT-UPDATE does not alter `readers` / `effective_acl_epoch`.
10. Existing RAG test (`RAGIndexingServiceImplAclUpdateTest`) + focused suite green.

---

Nothing in this document is implemented yet. On acceptance, implementation proceeds in
ordered increments: counter → `aclSourceEpoch` persistence → effective-epoch compute →
ACL-UPDATE atomic+fence → CONTENT/CREATE separation → batch fence → RAG unification →
strict end-to-end → migration patch → the live-Solr concurrency IT.
