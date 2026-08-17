# A-2 Slice 4b — activation checklist

Activating the lineage write-version barrier switches producers from writing v1 events to
spooling version-free facts that materialize as v2. This document is the acceptance procedure.

**Read this first.** Activation sets `writeSchemaVersion = 2` **and**
`minReaderSchemaVersion = 2` in one CAS. `writeSchemaVersion` can be rolled back at any time;
**`minReaderSchemaVersion` never comes down**. What becomes irreversible is not "writing v2" —
it is *this deployment ever again running a reader that decodes only v1*. Nothing in the
application can undo it, by design: the alternative would require proving that no v2 row exists
anywhere, which is exactly the thing that cannot be proven.

And rollback is narrower than it sounds. It changes **which version new facts are materialized
at**. Decisions already frozen complete at their version, and replay into v2 rows keeps
working. It is not a promise that no further v2 row is created.

---

## 0. Preconditions

| | |
|---|---|
| D-rest enabled | `lineage.drest.enabled=true`. It defaults to false, and CAS condition 10 requires a **green readiness gate at ACK time and again at activation** — a build that is wired but dormant must not open v2 writes. |
| Barrier prepared | `POST /core/api/v1/admin/lineage-journal/barrier/prepare` |
| Purview? | If the target catalog is **Microsoft Purview and not Apache Atlas**, stop and do §3.6 first. Gate E-20 was measured on Apache Atlas 2.3.0 OSS; Purview is a different backend and the result does not transfer. **There is no automated procedure**: `PurviewLiveAtlasSecretsIT` targets Atlas's `/api/atlas/v2/...` with Basic auth, which Purview does not serve. §3.6 gives the manual one. |

---

## 1. Application preflight

```bash
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/lineage-journal/preflight"
```

**Pass condition:** `verdict` is `EXTERNAL_EVIDENCE_REQUIRED` **and**
`catalogObligations.blockingConditions` is empty. It is never `PASS` — the items
in §3 are measurable only outside the application, so the application declaring a pass would be
claiming something it did not check. `FAIL` means one of the sections below is red; fix it and
re-run.

**Evidence to keep:** the whole response body, with a timestamp and the node it came from.

Sections and what a failure means:

| section | failure means |
|---|---|
| `cursors` | see §2 |
| `spool.verdict` | the spool directory does not resolve, or the write/link/fsync probe failed. The spool becomes the **hot path** after activation (every fact goes through it), so this is not optional. |
| `drestReadiness.ready` | activation will be refused by condition 10 anyway; the violations list says why. |
| `readerAdmission.decision` | this node is not admitted to read; activation would open writes nobody may consume. |
| `barrier.binaryDigestMeasurable` | `ack()` refuses without a measurable distribution — see §4. |
| `barrier.approvedBinaryDigestsPolicy` | see §4. **`blockingConditions` will NOT report this**: CAS condition 9 deliberately skips an empty allowlist, so the policy is asserted here or nowhere. |
| `catalogObligations.blockingConditions` | non-empty. See the table below — every entry names one missing adapter, one unprovable source, or one budget that does not fit. |

### 1.1 `catalogObligations` — the §2 machine

4b is a flag flip with no deployment, so producers, consumers and recovery must all be present
*before* it. This section is how that is established rather than assumed.

| field | what it says |
|---|---|
| `obligations` / `historicalIntents` / `compensations` | counts per state, each with `exact` and `basis`. **A count that could not be read comes back as `lowerBound`, never as a clean zero** — zero is the one answer that makes a broken deployment look finished. |
| `subjectFences.active` / `.expired` | from two ranged reduces over `historicalFencesByLease`. `exact: false` means they could not be established, which is blocking. |
| `oldestWaitingAgeMs` | how long the oldest PENDING/CLAIMED obligation has waited. **Ordinary PENDING/CLAIMED work does not block activation** — refusing while any obligation is outstanding would mean never activating a system in use. This is the number that says whether the machine is working or stuck. `null` means it could not be established, which is blocking. |
| `wiring.violations` | missing publisher, probe, source resolver, intent/compensation store, republisher or purge ledger, per target and per kind. |
| `operationBudgets` | per target and kind: `worstCaseMs` for the whole fenced section (source re-check + publish + read-back, with retries and backoff), against `subjectFenceLeaseMs` and `safetyMarginMs`. `resolvable: false` is blocking — a target with no configuration of its own must not borrow another's. |
| `purgeLedger.available` | without it nothing can ever say `SOURCE_PURGED`, so obligations for genuinely purged sources retry for ever. |
| `historicalEntitySupportByKind` | which kinds can receive a tombstone at all — see below. |

### 1.2 Operation budget — the default timeouts do not fit

The fenced critical section is the source re-check, the historical publish and the read-back,
each with its connect and read timeouts, its retries and the total backoff between them. With
the shipped defaults that is:

```
2 catalog calls x (2000 + 30000) x 4 attempts   = 256,000 ms
+ 2 x 7,700 ms backoff                          =  15,400 ms
+ client overhead                               =   5,000 ms
+ source re-check (CMIS kinds)                  =   2,000 ms
                                                = 278,400 ms
```

against a 300,000 ms subject fence lease with a 150,000 ms safety margin. **It does not fit,
and readiness is right to refuse it**: a publish still in flight when the fence expires can be
overwritten by another intent that has taken the subject.

Either lower the per-target read timeout (`atlas.read.timeout.ms` /
`purview.timeout.read.ms`) or accept that historical publishing stays off. `2000/10000` brings
the section to about 118,000 ms, inside the margin. `docker-compose-4b-dryrun.yml` sets exactly
that for the rehearsal.

The preflight prints `worstCaseMs` per target and kind next to `subjectFenceLeaseMs` and
`safetyMarginMs`, so the arithmetic above does not have to be redone by hand.

### 1.3 A target with no catalog client gets no adapters

The catalog client answers for **one** active backend. A node with both `purview.enabled` and
`atlas.enabled` binds its probe and its historical publisher to purview, and the preflight then
reports atlas as having neither — correctly, because a probe bound to a catalog the client does
not point at would attribute an answer to a catalog it never reached. Enable exactly the
backend the lineage target names.

**`historicalEntitySupportByKind`.** `nemaki_external_asset`, `nemaki_import_artifact` and
`nemaki_export_artifact` declare neither `lifecycleState` nor `sourceState`, so there is
nowhere on those types to record that the source is gone. Atlas silently drops an undeclared
attribute, so publishing anyway would put an entity in the catalog that is indistinguishable
from a live one. The publisher refuses with `SNAPSHOT_INCOMPLETE` (terminal — retrying cannot
make a type grow an attribute). This is **not** an activation blocker: it is a limitation to
know about, and it shows up as terminal obligations for those kinds if they are ever purged.

---

## 2. Stored cursor residue

```bash
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/lineage-journal/preflight/cursors"
```

**Pass condition:** `verdict: "PASS"`, and `checked` equals the number of repositories you
expect. The check covers the **union** of configured repositories and every repository with a
stored `cloud-metadata-snapshot` cursor — a repository dropped from configuration keeps its
cursor, and that residue is exactly what this is looking for.

**Do not use `GET /purview/admin/cursor-state/...` for this.** That route normalizes on the way
out, deliberately, so it can never be evidence about what is *stored*.

The predicate is a strict parse: every non-blank line must split into exactly five fields with
the URL slot empty. An unrecognized shape is `malformed` and **fails** — comparing a cursor to
its own normalization would call an unrecognized shape clean, and a URL in an unexpected shape
is precisely the case this exists to catch.

| `presence` | meaning |
|---|---|
| `ABSENT` | no cursor stored — clean |
| `PRESENT_EMPTY` | stored and empty — clean |
| `PRESENT_VALUE` | parsed strictly; `clean` says the verdict |
| `ERROR` | **not clean.** A cursor that could not be read has not been checked. |

**On failure:** run a successful `cloud-metadata-snapshot` sync for the affected repository —
one successful cycle rewrites the cursor in the URL-free shape — then re-run this check. For a
repository no longer in configuration, delete its cursor key from the state store.

**Evidence to keep:** the response body. It contains counts and verdicts only; no cursor value,
URL, or token fragment appears in it, in a log, or in an exception — a check for residual
tokens must not become the thing that prints one.

---

## 3. What the application cannot check

Each of these is `EXTERNAL_EVIDENCE_REQUIRED`. They are listed in the preflight response under
`notCheckableByThisApplication` so that their absence is never read as "fine".

### 3.1 Old AP absence (scale-to-one)

**Who:** whoever owns the deployment platform.
**Why the app cannot:** old binaries are already deployed and do not carry the guard; their
projector's view selection is `doc.type === 'lineage_event'` with no schema separation. The v2
documents use `type: lineage_event_v2`, so old views cannot structurally return them — that is
defence in depth, not a substitute. The barrier's `expectedNodes` is this node alone; "no other
AP is running" is not a question it can answer.

**Commands (Kubernetes example — adapt to the platform):**

```bash
kubectl get pods -l app=nemaki-core -o wide
kubectl get rs -l app=nemaki-core
kubectl get endpoints nemaki-core -o yaml
kubectl get hpa nemaki-core
```

**Expected:** exactly one running pod on the approved image; no old ReplicaSet with a non-zero
replica count; the Service endpoint list containing only that pod's IP; no autoscaler or
rollback automation that could recreate an older revision during the window.

**Evidence:** the four command outputs with timestamps, plus the image digest of the running
pod, captured **immediately before** activation and again immediately after.

### 3.2 Spool volume encryption at rest

**Who:** storage/platform owner.
**Why the app cannot:** it can report the real path and the `FileStore` (§1's `spool` section);
whether that volume is encrypted, and by whom, is not visible from inside.

**Expected:** the FileStore the preflight reported is on an encrypted volume; the encryption
method and key custody are named; a named party can state the recovery procedure.

**Evidence:** trace the whole chain, because a FileStore string alone can be `overlay` — the
container's own filesystem — which says nothing about the volume underneath:

```bash
# realPath (from §1) → which volumeMount contains it → which claim → which disk
kubectl get pod <pod> -o jsonpath='{.spec.containers[*].volumeMounts}' | jq
kubectl get pod <pod> -o jsonpath='{.spec.volumes}' | jq
kubectl get pvc <claim> -o yaml
kubectl get pv <volume> -o yaml     # and the cloud disk it references
```

File all four outputs plus the cloud provider's encryption status for that disk. If the mount
containing `realPath` turns out to be the container filesystem rather than a persistent volume,
that is a **FAIL** — the spool would not survive a restart either (§3.5).

### 3.3 Backup and snapshot encryption

**Who:** backup owner.
**Expected:** snapshots and backups of that volume are encrypted with the same or stronger
custody. A spool holds complete lineage payloads; an unencrypted snapshot of it is the same
exposure as an unencrypted volume.
**Evidence:** backup policy showing encryption for that volume, with the retention period.

### 3.4 Key custody and recovery

**Who:** whoever holds the keys.
**Expected:** a named custodian, and a stated recovery path that does not depend on the node
being alive.
**Evidence:** the key management reference (KMS key id or equivalent) and the recovery runbook
reference.

### 3.5 Spool persistence across restart

**Why the app cannot:** the readiness probe is cached and tests write/link/fsync support only.
Persistence across a restart takes two boots, and one process cannot observe both.

**Two-boot marker procedure:**

```bash
# boot 1 — write a marker into the directory §1 reported as realPath
POD=$(kubectl get pods -l app=nemaki-core -o jsonpath='{.items[0].metadata.name}')
MARKER="$(date -u +%Y%m%dT%H%M%SZ)-$(uuidgen)"
kubectl exec "$POD" -- sh -c "echo $MARKER > <realPath>/.persistence-marker && sync"
echo "$MARKER"          # keep this: it is what boot 2 must produce

# restart, and WAIT for the replacement to be ready
kubectl delete pod "$POD"
kubectl wait --for=condition=Ready pod -l app=nemaki-core --timeout=300s
NEW_POD=$(kubectl get pods -l app=nemaki-core -o jsonpath='{.items[0].metadata.name}')
test "$NEW_POD" != "$POD" || { echo "FAIL: same pod, nothing was restarted"; exit 1; }

# boot 2 — the marker must still be there, with the same content
kubectl exec "$NEW_POD" -- cat <realPath>/.persistence-marker

# clean up
kubectl exec "$NEW_POD" -- rm -f <realPath>/.persistence-marker
```

**Expected:** boot 2 prints exactly the `$MARKER` from boot 1, from a pod with a different
name. **Evidence:** both pod names, the marker value, and both outputs.

### 3.6 E-20 on Purview

**Only if activating against Purview.** Atlas OSS's PASS does not transfer, and **no automated
gate exists for Purview**: `PurviewLiveAtlasSecretsIT` speaks Atlas's `/api/atlas/v2/...` with
Basic authentication, while Purview's Data Map is a different API behind AAD. Writing that IT
needs a Purview tenant, so this stays a manual measurement until one is available.

**Who:** whoever owns the Purview account, with an AAD principal that can read and write
entities.

**What E-20 asks:** does republishing an entity that PREVIOUSLY carried a raw cloud URL leave
any token in the stored entity? On Atlas the answer was no — the same GUID came back with
`cloudFileUrl: null` and no token anywhere in the entity — so no purge/recreate runbook was
needed. Purview may differ, and the consequence of assuming otherwise is a token that stays in
the catalog after the code stopped sending one.

```bash
set -euo pipefail
TOKEN=$(az account get-access-token --resource https://purview.azure.net --query accessToken -o tsv)
ENDPOINT="https://<account>.purview.azure.com"
QN="<qualifiedName>"
READ="$ENDPOINT/catalog/api/atlas/v2/entity/uniqueAttribute/type/nemaki_document?attr:qualifiedName=$QN"

# curl -f, not curl -s alone: a saved 401 or 404 body would make every check below pass
# vacuously — both GUIDs would be null and the grep would find no token because there is no
# entity. The request must fail loudly instead.
curl -sf -H "Authorization: Bearer $TOKEN" "$READ" -o before.json

# The fixture must actually be the case under test: an entity that ALREADY carries a raw URL.
# If it does not, seed one (a legacy entity, or a deliberate write of the pre-A-1g shape) —
# otherwise this measures nothing.
jq -e '.entity.guid != null' before.json >/dev/null \
  || { echo "FAIL: no entity at $QN"; exit 1; }
grep -Eq 'authkey=|/:[a-z]:/g/' before.json \
  || { echo "FAIL: the fixture carries no raw URL — there is nothing for E-20 to remove"; exit 1; }
BEFORE_GUID=$(jq -r .entity.guid before.json)

# --- republish that object through NemakiWare's normal sync, then read it back ---

curl -sf -H "Authorization: Bearer $TOKEN" "$READ" -o after.json
AFTER_GUID=$(jq -r .entity.guid after.json)
```

**Pass condition, checked against the WHOLE stored entity and not one attribute:**

```bash
test -n "$AFTER_GUID" && test "$AFTER_GUID" != "null" \
  || { echo "FAIL: no entity after republish"; exit 1; }
test "$BEFORE_GUID" = "$AFTER_GUID" \
  || { echo "FAIL: republish replaced the entity ($BEFORE_GUID -> $AFTER_GUID)"; exit 1; }
if grep -Eq 'authkey=|/:[a-z]:/g/' after.json; then
  echo "FAIL: a token is still in the stored entity"; exit 1
fi
echo "PASS: same GUID, no token anywhere in the stored entity"
```

Checking a named attribute would only prove that the attribute you thought of is clean; the
point of the gate is the one nobody thought of. And the `before` assertions matter as much as
the `after` ones: a run where the fixture never had a token would print PASS while measuring
nothing.

**On failure (a token remains):** do not activate. Purview needs a purge/recreate runbook for
the affected entities first — the case Atlas turned out not to need.

**Evidence:** `before.json` and `after.json` with tokens redacted, the GUID comparison, the
grep result, the account name and the timestamp. Delete the bearer token from the shell
history.

---

## 4. The approved binary digest

**Production policy: `approvedBinaryDigests` must not be empty.** An empty list is valid to the
CAS — condition 9 simply is not imposed — which means activation can succeed while nothing has
established that the running build is one anybody approved. The preflight reports this as
`empty-allowlist-not-acceptable-in-production`; `blockingConditions` will not.

**Compute the digest from the artifact, not from the node.** Reading the digest off
`GET /barrier` and approving it because that route reported it is circular: the node would be
vouching for itself. Compute it from the WAR you are about to approve, through the same code
the ACK uses:

```bash
unzip -q core.war -d /tmp/approved-war
# Run the classes FROM THE ARTIFACT, not from a local build tree: measuring an approved WAR
# with some other build's code proves nothing about the WAR.
java -cp "/tmp/approved-war/WEB-INF/classes:/tmp/approved-war/WEB-INF/lib/*" \
  jp.aegif.nemaki.rest.purview.journal.LineageBinaryDigest /tmp/approved-war
```

Exit code 0 prints the digest; 1 means the artifact could not be measured (a symlink under
`WEB-INF`, an unreadable file, or a filesystem without `SecureDirectoryStream`) and **nothing
is printed to approve** — fix the artifact rather than working around it.

The digest covers every regular file under `WEB-INF/lib/` and `WEB-INF/classes/`, so **any
dependency change moves it** — the list has to be updated with each approved build.

```bash
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  -H "Content-Type: application/json" \
  -d '{"approvedBinaryDigests":["<digest from the command above>"]}' \
  "http://localhost:8080/core/api/v1/admin/lineage-journal/barrier/prepare"
```

**The comparison itself happens in §5, after the ACK** — `prepare` clears the ACKs, so there
is nothing to compare against until `POST .../barrier/ack` has run. Keep the CLI's output; §5
asserts against it.

**Evidence:** the CLI output, the artifact it was computed from (name and its own checksum),
and the `GET /barrier` response showing the same value.

---

## 5. Activate

Only when §1 is `EXTERNAL_EVIDENCE_REQUIRED`, §2 is `PASS`, every item in §3 has its evidence,
and §4 matches.

```bash
set -euo pipefail
BARRIER="http://localhost:8080/core/api/v1/admin/lineage-journal/barrier"
APPROVED="<the digest the CLI printed from the artifact in §4>"

curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" -f "$BARRIER/ack"

curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" -f "$BARRIER" -o barrier.json

# ASSERT, do not eyeball: the node's measurement, every ACK, and the allowlist must all be
# the digest computed from the approved artifact. jq -e exits non-zero when the test is false.
jq -e --arg d "$APPROVED" '
  .measuredBinaryDigest == $d
  and (.acks | type == "object") and ((.acks | length) > 0)
  and (all(.acks[]; .binaryDigest == $d))
  # every allowlisted digest, not merely "the approved one is among them": an extra entry is
  # a build somebody approved that this check never measured
  and (.approvedBinaryDigests | type == "array")
  and ((.approvedBinaryDigests | length) > 0)
  and (all(.approvedBinaryDigests[]; . == $d))
' barrier.json >/dev/null \
  || { echo "FAIL: the running node is not the build you approved"; exit 1; }

# and nothing may still be blocking. The type check matters: jq reports length 0 for null and
# for a missing key too, so "length == 0" alone would pass on a response that has no such
# field at all.
jq -e '(.blockingConditions | type == "array") and ((.blockingConditions | length) == 0)' \
  barrier.json >/dev/null \
  || { echo "FAIL: $(jq -c .blockingConditions barrier.json)"; exit 1; }

curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" -f "$BARRIER/activate"
```

Read `blockingConditions`, not a count: it names the exact CAS conditions still failing.

**Rolling back** (`POST .../barrier/rollback`) returns `writeSchemaVersion` to 1 at any time.
It does **not** lower `minReaderSchemaVersion`, and it does not stop v2 rows arising from
already-frozen decisions or replay. To go to v2 again you must `prepare` again — the generation
bumps and the ACKs are cleared, so a fresh ACK is required.

---

## 5.7 ACTIVATION — 2026-08-06, local environment, operator-authorized

`POST /barrier/activate` was executed for the first time, on the local environment, under an
explicit operator decision (the environments in scope are local and the standing demo; there is
no production deployment). The §3 items remain production obligations and were not claimed.

| step | result |
|---|---|
| stale-ACK gate | the ACK from §5.6 had expired; activation was **refused by condition 5** until a fresh ACK — the freshness gate demonstrated live |
| activate | HTTP 200, `state = ACTIVE`, `writeSchemaVersion = 2`, `minReaderSchemaVersion = 2` — one-way |
| first v2 write | `EXPORT_ZIP_FOLDER` via the spool → convergent materialisation → `lineage_event_v2` (schemaVersion 2, ORIGINAL) |
| sequencer | explicit run: `finalized = 1, backlog = 0, health = FENCED_OK` |
| projection | entered `WAITING_FOR_CATALOG` with two obligations: `CMIS_FOLDER` and `EXPORT_ARTIFACT`, both PENDING |
| **defect found** | **nothing drives the obligation scanner** — no schedule, no admin route. The §2 machine could create obligations but never work them off; every waiting event would age out to terminal UNRESOLVED. Fixed on the spot (v2.3.85): scheduled passes in the projection loop + `POST /obligations/run` |
| obligations | after the fix: `CMIS_FOLDER → RESOLVED (LIVE_SOURCE_OBSERVATION_MATERIALIZED)` — the live-source route's first live run; `EXPORT_ARTIFACT → RESOLVED (SOURCE_EXISTS)` on the pass after the process publish created it |
| event | `atlas: PUBLISHED` (read-back verified) |
| Atlas | `nemaki_folder_dataset` present with `sourceState = ACTIVE`; `nemaki_export_artifact` present, ACTIVE |
| redeploy under ACTIVE | the barrier survives; a new binary is admitted by **capability** (`read:v2`), not by digest — the allowlist ratchets activation, not steady-state deploys. Re-arming an ACTIVE barrier is refused (`writeSchemaVersion == 1` required), by design |

## 5.6 Dry-run (rehearsal, second pass) — 2026-08-05, same environment, non-vacuous gate

The first rehearsal (§5.5) demonstrated the machine but left the gate unproven: the cursor
check errored and the allowlist was empty, so condition 9 was skipped rather than satisfied.
This pass closed both, with the v2.3.71–v2.3.80 build. **`POST /barrier/activate` was not
called, and `POST /barrier/rollback` was not called; `writeSchemaVersion` stayed 1.**

| item | result |
|---|---|
| §1 preflight | `verdict = EXTERNAL_EVIDENCE_REQUIRED`, `catalogObligations.verdict = PASS`, `blockingConditions = []`, `drestReadiness.ready = true`, `spool = PASS` |
| §2 cursors | **`verdict = PASS`**, `checked = 2` (bedroom `PRESENT_EMPTY` clean, canopy `ABSENT` clean) — after fixing the duplicate-key defect below |
| §4 digest | computed out of process in a separate Linux container from the image, over the image's own deployed tree; **equal to the node's `measuredBinaryDigest`** |
| §5 negative control | with a deliberately non-member digest approved, `blockingConditions = ["condition 9: the binary running on '<node>' is not in approvedBinaryDigests"]` — the gate blocks by membership |
| §5 final | `approvedBinaryDigests` non-empty, `measuredBinaryDigest == every ack == every allowlisted digest`, **`blockingConditions = []`** — condition 9 satisfied by membership, not skipped |

Two findings from this pass, both fixed rather than worked around:

1. **The cursor preflight could never PASS on a standard install.** The initialization dump
   seeds `system.version` as `system_config_001` while `Patch_SystemFolderSetup` derived
   `system_config_system_version` for the same key and checked existence by id only — so every
   standard install carried the key twice, the strict legacy reader refused to choose
   (correctly), and §2 reported ERROR for every repository. The patch now checks existence by
   logical key and removes its own duplicate (never the other holder's document) on the next
   boot. The check itself was not weakened.
2. **The artifact you approve is the image, not the WAR, in an image-based deployment.** The
   image build rewrites `WEB-INF/classes/nemakiware.properties` and
   `nemakiware-basetype.properties`, so the WAR-only digest differs from the running tree —
   and condition 9 correctly refused it (the negative control above). Compute the digest from
   the image's deployed tree, in a fresh container from that image: it reproduces the node's
   measurement exactly, and it is still out of the serving node's process. On this host the
   CLI also refused to measure at all (no `SecureDirectoryStream` on macOS), which is the
   documented fail-closed behaviour — measure on the platform the node runs on.

Also in this pass: the overlay's 2s/10s timeouts — computed under the pre-v2.3.72 two-call
budget model — were refused by the corrected per-route model (true worst case ~202s against a
150s bound) and tightened to 1s/6s (~142s worst route). The refusal is the check working.

Evidence files: preflight before/after, `/preflight/cursors`, both `prepare` responses, both
`ack` responses, the blocked and clean `GET /barrier` bodies, and the CLI digest output with
the artifact identity.

---

## 5.5 Dry-run (rehearsal) — 2026-08-05, local disposable environment

Executed with `docker-compose-4b-dryrun.yml` against local CouchDB 3.x, Solr and Apache Atlas,
using the real production adapters (no placeholders). **`POST /barrier/activate` was not called,
and `POST /barrier/rollback` was not called.**

| condition | result |
|---|---|
| real adapters, no placeholder | probe / historical publisher / read-back / republisher / per-kind source resolver / purge ledger all wired from production beans |
| readiness green | `drestReadiness.ready = true`, `violations = []` |
| preflight obligation section | `catalogObligations.verdict = PASS`, `blockingConditions = []`, `configuredTargets = [atlas]` |
| spool | `verdict = PASS` (real path, FileStore, write/link/fsync probe) |
| independent artifact digest | `ack.binaryDigest == measuredBinaryDigest` |
| prepare | `applied = true`, `state = PREPARING`, `generation = 1` |
| ack | `applied = true`, all 6 required capabilities present including `catalog:obligations` |
| activation conditions 1–11 | `blockingConditions = []` on the pre-activate `GET /barrier` |
| activate | **not called** |
| rollback | **not called** |

Not green in the rehearsal, and why. Both are honest reports of a disposable environment,
not defects — but neither may be carried into a production activation:

- `cursors.verdict = FAIL` — the legacy cursor store is absent in this disposable environment,
  so the check reports ERROR rather than clean. It is reporting honestly: it cannot establish
  the residue is clean, and equality with `normalize()` would call an unrecognised shape clean.
  A real environment either has the legacy store or has migrated off it.
- `approvedBinaryDigests = []` — nobody has approved a digest in a rehearsal. Condition 9
  deliberately skips an empty allowlist, so the barrier does not block; the preflight reports
  the policy as `empty-allowlist-not-acceptable-in-production`, which is correct for production
  and expected here.

### Closing both before a production activation

A rehearsal that leaves these two open has demonstrated the machine, not the gate. What is
still unproven is precisely the part that stops the wrong binary activating, so it has to be
closed against a real environment rather than argued about:

1. **`cursors.verdict = PASS`** — provision the legacy cursor store (or record the migration
   off it) so the check can establish the residue rather than report that it cannot. Do NOT
   satisfy this by relaxing the check: an equality that calls an unrecognised shape clean is
   the failure mode the ERROR exists to prevent.
2. **`approvedBinaryDigests` non-empty** — compute the digest with the CLI, out of process,
   and approve exactly that value. The ACK digest must equal the CLI-computed digest, and
   condition 9 must then be reported as satisfied by membership rather than skipped.

Only when both are green, and `blockingConditions = []` still holds, has the pre-activate
state been demonstrated. `POST /barrier/activate` remains out of scope for a rehearsal.

## 6. Evidence to file

1. `/preflight` response (before, and after activation)
2. `/preflight/cursors` response
3. Platform outputs for §3.1, with the running image digest
4. Volume encryption, backup encryption and key custody references for §3.2–3.4
5. Two-boot marker outputs for §3.5
6. Digest CLI output, artifact identity, and the matching `/barrier` response
7. `/barrier` response after activation, showing `writeSchemaVersion: 2` and
   `minReaderSchemaVersion: 2`
8. If Purview: the E-20 re-measurement
