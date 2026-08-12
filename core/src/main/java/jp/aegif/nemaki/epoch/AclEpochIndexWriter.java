package jp.aegif.nemaki.epoch;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.acl.AclSemantics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * The fenced ACL-group writer: §4.2 steps 3/5/6 plus the §4.3 fence decision of
 * {@code docs/design/acl-epoch-fencing.md} — increment 4.
 *
 * <p>Completes the unified write contract whose read half is {@link AclEffectiveEpochService}.
 * The MANDATORY order is fixed and must not be rearranged:
 * <ol>
 *   <li><b>Walk</b> the authoritative sources + apply the pending gate (step 1).</li>
 *   <li><b>Compute</b> readers and the effective epoch from that recorded snapshot (step 2).</li>
 *   <li><b>Realtime GET</b> the Solr document's {@code _version_}, stored
 *       {@code effective_acl_epoch} and stored {@code readers} — never a searcher query, which
 *       lags behind by the soft-commit interval (step 3).</li>
 *   <li><b>Revalidate</b> every recorded dependency; any change restarts from the walk (step 4).</li>
 *   <li><b>CAS</b> the atomic ACL-group update with the step-3 {@code _version_} (step 5).</li>
 *   <li>On <b>409</b>: restart from the walk. <b>Payload reuse after a conflict is forbidden</b>
 *       (step 6).</li>
 * </ol>
 *
 * <p><b>Why the RTG must precede the revalidation:</b> the {@code _version_} has to be read BEFORE
 * the dependencies are re-checked so that ANY Solr write landing after step 3 — including a
 * correct writer's — fails our CAS at step 5. The reverse order (revalidate → RTG → CAS) lets a
 * stale-but-revalidated writer pick up a fresher {@code _version_} written in between and overwrite
 * it. With this order, source changes are caught by step 4 + restart, and Solr changes are caught by
 * the step-5 CAS.
 *
 * <p><b>Only the ACL group is written</b> ({@code readers} + {@code effective_acl_epoch}, as an
 * atomic {@code {"set": …}}). {@code name} / {@code path} / body / {@code content_length} and every
 * other field are NEVER touched, so a transient failure in an unrelated extractor can never clobber
 * good content (§4.4).
 *
 * <p><b>Fail-closed staging (sign-off invariant 9):</b> a standalone bean with NO production
 * callers, no scheduler / init / cron. It is driven only by tests until the wiring increment.
 */
public class AclEpochIndexWriter {

    private static final Logger logger = LoggerFactory.getLogger(AclEpochIndexWriter.class);

    /** Solr ACL-group fields (the ONLY fields this writer ever sets). */
    static final String FIELD_READERS = "readers";
    static final String FIELD_EFFECTIVE_EPOCH = "effective_acl_epoch";
    static final String FIELD_VERSION = "_version_";
    static final String FIELD_REPOSITORY_ID = "repository_id";

    /** Bound on full restarts (each restart re-walks; a persistent loser must not spin for ever). */
    public static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final int COMMIT_WITHIN_MS = 1000;

    private AclEffectiveEpochService effectiveEpochService;
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    public void setEffectiveEpochService(AclEffectiveEpochService s) { this.effectiveEpochService = s; }

    public int getMaxAttempts() { return maxAttempts; }

    /** Non-positive values fall back to the default (config hardening, as elsewhere). */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
    }

    // ── readers ────────────────────────────────────────────────────

    /**
     * The reader tokens for this write, projected from the SAME snapshot that produced the effective
     * epoch (increment 5S step 3).
     *
     * <p>This replaced a pluggable {@code ReadersComputer} SPI. The SPI's contract — "compute from
     * the authoritative, cache-bypassing sources, for the object itself as well as its ancestors" —
     * was <b>unenforceable at the boundary</b> (design §5.2, now deleted): the snapshot did not carry
     * the ACL entries, so an implementation that read from the ACL cache would pass revalidation
     * while writing readers derived from a different ACL state. Now the snapshot carries the raw
     * local ACLs and the projection is structural, so there is nothing left to promise.
     *
     * <p>The {@code resolver} is NOT a replacement SPI. It answers one question — does this principal
     * id exist as a user or a group — and cannot change the ACL semantics. Every one of its failure
     * modes SHRINKS the token set ({@code UNAVAILABLE} throws rather than dropping, increment 5T), so
     * it can cause a stale-deny but never an over-grant.
     *
     * <p>Package-private and overridable for the same reason as {@link #realtimeGet}: the protocol
     * ITs pin the fence decision, the 409 restart and the repository boundary, and those need a
     * chosen readers value rather than one that happens to fall out of a fixture. Production
     * {@link #write} has NO parameter that can substitute a different computation.
     */
    List<String> computeReaders(AclEffectiveEpochService.Snapshot snapshot,
                                AclSemantics.PrincipalResolver resolver) {
        return snapshot.readers(resolver);
    }

    // ── outcome ────────────────────────────────────────────────────

    public enum WriteResult {
        /** The ACL group was CAS-updated. */
        UPDATED,
        /** Solr already holds a STRICTLY newer effective epoch — a fresher ACL landed (clean no-op). */
        SKIPPED_FRESHER,
        /** Same epoch AND identical readers — true idempotence (clean no-op). */
        SKIPPED_IDEMPOTENT,
        /** The object no longer exists in CouchDB (deleted — the caller completes, not retries). */
        SKIPPED_DELETED,
        /** The object is not in the Solr index at all — nothing to fence (the caller indexes it first). */
        NOT_INDEXED
    }

    public static final class WriteOutcome {
        public final WriteResult result;
        public final long epoch;            // the effective epoch this attempt computed
        public final List<String> readers;  // canonical readers written (non-null only for UPDATED)
        public final int attempts;

        WriteOutcome(WriteResult result, long epoch, List<String> readers, int attempts) {
            this.result = result;
            this.epoch = epoch;
            this.readers = readers == null ? null : Collections.unmodifiableList(readers);
            this.attempts = attempts;
        }
    }

    /** A conflict-driven restart budget was exhausted — retryable, never a silent success. */
    public static final class AclEpochWriteContentionException extends RuntimeException {
        public AclEpochWriteContentionException(String message) { super(message); }
    }

    // ── the write protocol ─────────────────────────────────────────

    /**
     * Run the full contract for one object.
     *
     * @throws AclEffectiveEpochService.AclEpochPendingException     a dependency is mid-mutation —
     *         the caller must RETAIN its task and back off (never delete / terminal-fail it)
     * @throws AclEpochAnomalyException                              corrupt epoch data (including a
     *         QUARANTINED dependency) — retain the task, repair required (design §5.1)
     * @throws AclEffectiveEpochService.AclEpochUnavailableException a dependency could not be read
     * @throws jp.aegif.nemaki.acl.PrincipalUnavailableException     a principal lookup could NOT BE
     *         SERVED (a missing design document / view / database — increment 5T). RETRYABLE, and
     *         deliberately distinct from a principal that is genuinely absent, which is simply
     *         omitted: dropping on an unservable lookup would degrade every principal to "absent"
     *         and hand the fail-closed fallback an admin-only set as if it had been computed. The
     *         caller RETAINS its task.
     * @throws AclEpochWriteContentionException                      restarts exhausted (retryable)
     */
    public WriteOutcome write(String repositoryId, String objectId, SolrClient solrClient,
                              AclSemantics.PrincipalResolver resolver) throws Exception {
        return run(repositoryId, objectId, solrClient, resolver, false);
    }

    /**
     * MIGRATION (wiring gate 2): stamp the INITIAL {@code effective_acl_epoch} — and the readers
     * computed from the same snapshot — onto a document that has never been fenced.
     *
     * <p>Identical to {@link #write} in every respect but one: an ABSENT stored epoch is treated as
     * {@code 0} instead of throwing. Deliberately the same method rather than a parallel
     * implementation — the RTG-before-revalidate order, the §4.3 fence decision, the CAS and the
     * 409 restart are exactly the properties migration must also honour, and a second copy of them
     * is the defect class increments 3a/3b/4b and review P1-1 were each an instance of.
     *
     * <p>The value stamped is {@code snapshot().effectiveEpoch}. For pre-migration content every
     * {@code aclSourceEpoch} is absent, so that is {@code 0}; the counter's first allocation is
     * {@code 1}, so any epoch a production writer later pays STRICTLY beats the stamp and the fence
     * orders correctly from the start.
     *
     * <p><b>It writes SOLR ONLY.</b> The CouchDB {@code aclSourceEpoch} is NOT filled in: allocating
     * that is the post-commit two-phase mutation's job (§2.2), and pre-seeding it would manufacture
     * an epoch no mutation ever paid for.
     *
     * <p>An already-stamped document is left alone unless its readers disagree — the ordinary fence
     * decision handles that — so the migration is idempotent and resumable.
     *
     * <p><b>Still no production caller</b> (sign-off invariant 9). Running the migration is an
     * operational step, and the writer stays off the ACL path until all four gates close.
     */
    public WriteOutcome stampInitialEpoch(String repositoryId, String objectId, SolrClient solrClient,
                                          AclSemantics.PrincipalResolver resolver) throws Exception {
        return run(repositoryId, objectId, solrClient, resolver, true);
    }

    /**
     * The UNIFIED production ACL-group write (design §11.3, approved decision D2): identical to
     * {@link #write} for a FENCED document — the full §4.3 rules, no exceptions — and identical to
     * {@link #stampInitialEpoch} for an UNFENCED one, which it CAS-creates from the authoritative
     * walk instead of throwing.
     *
     * <p>This AMENDS §4.3's "migration is the only caller allowed to see an unfenced document":
     * that restriction belonged to the pre-wiring period. Post-wiring, unfenced means a fresh
     * create (§11.5) or a reindex whose per-deployment re-stamp has not run yet, and the strict
     * alternative — throw, enqueue, and have the re-drive perform the IDENTICAL bootstrap — adds a
     * queue round-trip with zero safety gain. Same method as the migration on purpose: one
     * implementation of "what may write the fence", not two.
     */
    public WriteOutcome writeAllowingBootstrap(String repositoryId, String objectId, SolrClient solrClient,
                                               AclSemantics.PrincipalResolver resolver) throws Exception {
        return writeAllowingBootstrap(repositoryId, objectId, solrClient, resolver, null);
    }

    /**
     * As above, reusing {@code memo}'s ancestor reads across the objects of ONE traversal.
     *
     * <p>A null memo is exactly today's behaviour. When one is supplied this method owns its
     * invalidation: a refused revalidation clears it before restarting, because the restart
     * re-runs the walk and would otherwise be handed the same stale ancestor that was just
     * rejected — detecting the change but never getting past it.
     */
    public WriteOutcome writeAllowingBootstrap(String repositoryId, String objectId, SolrClient solrClient,
                                               AclSemantics.PrincipalResolver resolver,
                                               AclEffectiveEpochService.TraversalMemo memo) throws Exception {
        return run(repositoryId, objectId, solrClient, resolver, true, memo);
    }

    private WriteOutcome run(String repositoryId, String objectId, SolrClient solrClient,
                             AclSemantics.PrincipalResolver resolver, boolean bootstrap) throws Exception {
        return run(repositoryId, objectId, solrClient, resolver, bootstrap, null);
    }

    private WriteOutcome run(String repositoryId, String objectId, SolrClient solrClient,
                             AclSemantics.PrincipalResolver resolver, boolean bootstrap,
                             AclEffectiveEpochService.TraversalMemo memo)
            throws Exception {
        if (effectiveEpochService == null) {
            throw new AclEpochWiringException("effectiveEpochService not wired on AclEpochIndexWriter");
        }
        if (solrClient == null) {
            // A missing Solr client is a WIRING fault, not this object's problem: every other
            // IllegalStateException in this class describes the DOCUMENT (a magic _version_, an
            // unfenced epoch, a repository mismatch) and is per-task fail-closed. A caller deciding
            // "retry or terminal-fail?" must be able to tell those apart from "the bean is missing".
            throw new AclEpochWiringException("Solr client unavailable for " + objectId);
        }
        if (resolver == null) {
            throw new IllegalArgumentException("principal resolver is required");
        }

        // A single authoritative recompute is FORCED after an equal-epoch/different-readers
        // observation (§4.3): the recomputed value is then CAS-written rather than compared again,
        // which is what makes every conflicting writer converge instead of ping-ponging.
        boolean forceWriteAfterEqualEpochDivergence = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // ── steps 1-2: authoritative walk (+ pending gate) and compute ──
            AclEffectiveEpochService.Snapshot snapshot =
                    effectiveEpochService.snapshot(repositoryId, objectId, memo);
            if (snapshot == null) {
                return new WriteOutcome(WriteResult.SKIPPED_DELETED, 0L, null, attempt);
            }
            List<String> readers = strictComputedReaders(computeReaders(snapshot, resolver),
                    objectId, snapshot);
            long myEpoch = snapshot.effectiveEpoch;

            // ── step 3: realtime GET (never a searcher query) ──
            SolrDocument current = realtimeGet(solrClient, repositoryId, objectId);
            if (current == null) {
                return new WriteOutcome(WriteResult.NOT_INDEXED, myEpoch, null, attempt);
            }
            requireSameRepository(current, repositoryId, objectId);
            long version = requireCasVersion(current, objectId);
            // Migration is the ONLY caller allowed to see an unfenced document; for everyone else
            // this throws (§4.3).
            boolean epochWasAbsent = current.getFieldValue(FIELD_EFFECTIVE_EPOCH) == null;
            long storedEpoch = epochWasAbsent && bootstrap
                    ? 0L : requireStoredEpoch(current, objectId);
            List<String> storedReaders = normalizeStoredReaders(stringList(current.getFieldValues(FIELD_READERS)));

            // ── step 4: revalidate every recorded dependency; ANY change restarts ──
            if (!effectiveEpochService.revalidate(snapshot)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("ACL epoch write: dependencies changed under {} — restarting", objectId);
                }
                // MANDATORY with a memo (ledger A3): the restart re-runs the walk, and an
                // un-invalidated memo would hand it the very ancestor revalidation just rejected
                // — the same snapshot, the same refusal, forever. Everything is dropped rather
                // than the differing ids: computing that subset slightly wrong is a silent stale
                // read, which is the one failure this must not be able to cause.
                if (memo != null) {
                    memo.invalidateAll();
                }
                forceWriteAfterEqualEpochDivergence = false; // the payload is stale; recompute clean
                continue;
            }

            // ── §4.3 fence decision — evaluated on EVERY attempt ──
            // The recompute flag authorises exactly ONE thing: writing an equal-epoch value whose
            // readers diverge. It must NEVER bypass the fence itself (review 4a [P1]): between the
            // divergence observation and this attempt's RTG another writer may have landed a
            // STRICTLY NEWER epoch, and skipping the check would CAS the older epoch over it —
            // the CAS succeeds, because this attempt read that newer document's _version_.
            long stored = storedEpoch;
            if (stored > myEpoch) {
                return new WriteOutcome(WriteResult.SKIPPED_FRESHER, myEpoch, null, attempt);
            }
            if (stored == myEpoch) {
                if (storedReaders.equals(readers) && !epochWasAbsent) {
                    // Also re-checked every attempt: the recompute may simply agree with what is
                    // already stored, which is idempotence, not a reason to write.
                    //
                    // NOT when the epoch field was ABSENT, even if the readers happen to match what
                    // the ordinary index already wrote: the whole point of the migration is to CREATE
                    // the field, and short-circuiting here would leave the document unfenced while
                    // reporting success — the writer would then refuse every later ACL update on it.
                    return new WriteOutcome(WriteResult.SKIPPED_IDEMPOTENT, myEpoch, null, attempt);
                }
                if (!forceWriteAfterEqualEpochDivergence && !epochWasAbsent) {
                    // EQUAL epoch, DIFFERENT readers: a transient read-skew artefact. Never "my
                    // payload wins by default" — recompute from the authoritative sources and write
                    // THAT (§4.3), so every conflicting writer converges on the last-finalized state.
                    logger.info("ACL epoch write: equal epoch {} with divergent readers on {} — "
                            + "recomputing authoritatively", myEpoch, objectId);
                    forceWriteAfterEqualEpochDivergence = true;
                    continue;
                }
                // Second observation: this payload IS the authoritative recompute — write it.
            }

            // ── step 5: atomic ACL-GROUP-ONLY update, CAS-guarded by the step-3 _version_ ──
            SolrInputDocument upd = new SolrInputDocument();
            upd.addField("id", objectId);
            upd.setField(FIELD_READERS, Collections.singletonMap("set", readers));
            upd.setField(FIELD_EFFECTIVE_EPOCH, Collections.singletonMap("set", myEpoch));
            upd.addField(FIELD_VERSION, version); // optimistic concurrency
            try {
                UpdateRequest req = new UpdateRequest();
                req.add(upd);
                req.setCommitWithin(COMMIT_WITHIN_MS);
                UpdateResponse resp = req.process(solrClient);
                if (resp.getStatus() != 0) {
                    throw new IllegalStateException("Solr ACL-group update failed with status "
                            + resp.getStatus() + " for " + objectId);
                }
                return new WriteOutcome(WriteResult.UPDATED, myEpoch, readers, attempt);
            } catch (org.apache.solr.client.solrj.RemoteSolrException e) {
                if (e.code() != 409) {
                    throw e;
                }
                // ── step 6: 409 → FULL restart from the walk. The payload is discarded: reusing it
                // would re-apply readers computed against sources that may have moved on.
                if (logger.isDebugEnabled()) {
                    logger.debug("ACL epoch write: CAS conflict on {} (attempt {}) — full restart",
                            objectId, attempt);
                }
                forceWriteAfterEqualEpochDivergence = false;
            }
        }
        throw new AclEpochWriteContentionException("ACL epoch write did not converge for " + objectId
                + " after " + maxAttempts + " attempts — retryable (the task must be RETAINED)");
    }

    // ── Solr primitives ────────────────────────────────────────────

    /**
     * Realtime GET — {@code /get}, NOT a searcher query. A searcher lags by the soft-commit
     * interval, so a searcher-read {@code _version_} would be stale and the CAS would loop to
     * exhaustion whenever the document was written in the last second.
     *
     * <p>This method performs the RAW fetch ONLY. The repository-boundary check is deliberately
     * NOT here: {@link #write} applies {@link #requireSameRepository} to the RESULT, so that an
     * overridden or alternate fetch path cannot bypass it (review 4a [P2]). An override therefore
     * does NOT need to — and must not be relied upon to — enforce the boundary itself.
     *
     * <p>Package-private so a concurrency IT can override it to inject a competing write (or a
     * dependency mutation) at EXACTLY the point between step 3 and step 5, making the 409 and
     * dependency-change paths deterministic rather than timing-dependent.
     */
    SolrDocument realtimeGet(SolrClient solrClient, String repositoryId, String objectId)
            throws Exception {
        SolrDocument doc = solrClient.getById(objectId);
        if (doc == null) {
            return null;
        }
        return doc;
    }

    /**
     * The CAS {@code _version_} of an EXISTING document, validated strictly (review 4a [P1]).
     *
     * <p>Solr overloads {@code _version_}: {@code 0} means "no concurrency check at all",
     * {@code 1} means "any existing version" and a NEGATIVE value means "must not exist". Passing
     * any of those through would silently turn the documented compare-and-set into an unconditional
     * write, so only a real existing-document version ({@code > 1}, strictly integral) is accepted.
     * Anything else — missing, non-numeric, fractional, or one of the magic values — fails closed
     * (§4.3) so the caller retains and retries rather than writing unfenced.
     */
    private static long requireCasVersion(SolrDocument doc, String objectId) {
        Object v = doc.getFieldValue(FIELD_VERSION);
        long version = exactLong(v, FIELD_VERSION, objectId);
        if (version <= 1L) {
            throw new IllegalStateException("Solr document " + objectId + " reported " + FIELD_VERSION
                    + "=" + version + ", which is a Solr magic value (0 = no check, 1 = any version, "
                    + "negative = must-not-exist) rather than an existing document's version — "
                    + "refusing to write the ACL group without a real compare-and-set");
        }
        return version;
    }

    /**
     * The stored effective epoch, FAIL-CLOSED when absent (§4.3, review 4a [P1]).
     *
     * <p>An ABSENT epoch means the document has never been fenced. A normal ACL-UPDATE must NOT
     * bootstrap it implicitly — "no fence yet, so any writer wins" is precisely the property the
     * fence exists to remove, and it would let an arbitrarily stale writer claim an unfenced
     * document. Stamping the initial epoch belongs to the migration / full-reindex path.
     */
    private static long requireStoredEpoch(SolrDocument doc, String objectId) {
        Object v = doc.getFieldValue(FIELD_EFFECTIVE_EPOCH);
        if (v == null) {
            throw new IllegalStateException("Solr document " + objectId + " has no "
                    + FIELD_EFFECTIVE_EPOCH + " — it has never been fenced. A normal ACL-UPDATE does "
                    + "not bootstrap the fence implicitly; run the migration / full reindex first");
        }
        return exactLong(v, FIELD_EFFECTIVE_EPOCH, objectId);
    }

    /** Strictly integral numeric conversion (rejects non-Number, fractional and out-of-range). */
    private static long exactLong(Object v, String field, String objectId) {
        if (!(v instanceof Number)) {
            throw new IllegalStateException("Solr document " + objectId + " has a missing / non-numeric "
                    + field + " (" + v + ") — refusing to write the ACL group unfenced");
        }
        try {
            return new java.math.BigDecimal(v.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("Solr document " + objectId + " has a non-integral / "
                    + "out-of-range " + field + " (" + v + ") — refusing to write the ACL group unfenced");
        }
    }

    /**
     * The readers to WRITE, validated as an INTERNAL INVARIANT (review 4a [P2]; re-scoped in 5S
     * step 3). A {@code null} list, or ANY null / blank element, would persist a SHORTER reader set
     * that looks perfectly normal — a silent under-grant — so the write is refused instead.
     *
     * <p>Since 5S step 3 these checks guard against a BUG in the shared projection rather than
     * against a third-party plugin: {@link AclSemantics#readerTokens} cannot produce a null list,
     * a null token or a blank token. They are kept as defence in depth, not as a boundary contract.
     *
     * <p><b>EMPTY is the one case that is not always a failure.</b> {@code readerTokens} never
     * returns empty — its rule 2 falls back to admin-only — so the only legitimate empty result is a
     * relationship whose source AND target are both genuinely gone, whose reader set production
     * already persists as empty ({@code SolrUtil.unionReaders}: the query-side filter then hides it
     * from every non-admin). Refusing that unconditionally would leave such a relationship's
     * reconcile task failing and retrying for ever, so the SNAPSHOT is asked whether the emptiness is
     * authoritative — a question only it can answer, because only it recorded the endpoints' absence.
     */
    private static List<String> strictComputedReaders(List<String> computed, String objectId,
                                                      AclEffectiveEpochService.Snapshot snapshot) {
        if (computed == null) {
            throw new IllegalStateException("the readers projection returned null for " + objectId
                    + " — refusing to write an empty ACL group");
        }
        if (computed.isEmpty()) {
            if (snapshot.emptyReadersIsAuthoritative()) {
                // A relationship with both endpoints deleted: empty IS the fail-closed answer.
                return Collections.emptyList();
            }
            throw new IllegalStateException("the readers projection returned an empty list for "
                    + objectId + ", which is not an authoritative empty (only a relationship with "
                    + "both endpoints absent may be empty) — refusing to write an empty ACL group");
        }
        for (String r : computed) {
            if (r == null || r.isBlank()) {
                throw new IllegalStateException("the readers projection returned a null / blank token for "
                        + objectId + " — a partial computation must never be written as a shorter "
                        + "reader set");
            }
        }
        return canonical(computed);
    }

    /**
     * The repository boundary, fail-closed (review 4a [P2]). Applied to the RESULT of the fetch by
     * {@link #write} rather than inside {@link #realtimeGet}, so no alternate or overridden fetch
     * path can bypass it. The Solr core is SHARED across repositories, so a restored / legacy /
     * corrupt document with a missing, blank or non-String {@code repository_id} cannot be proven
     * to belong to THIS repository and must not be written.
     */
    private static void requireSameRepository(SolrDocument doc, String repositoryId, String objectId) {
        Object repo = doc.getFieldValue(FIELD_REPOSITORY_ID);
        if (!(repo instanceof String) || ((String) repo).isBlank()) {
            throw new IllegalStateException("Solr document '" + objectId + "' has a missing / blank / "
                    + "non-String " + FIELD_REPOSITORY_ID + " (" + repo + ") — refusing to write "
                    + "without a provable repository boundary");
        }
        if (!repositoryId.equals(repo)) {
            throw new IllegalStateException("Solr id collision: '" + objectId + "' belongs to repository '"
                    + repo + "', not '" + repositoryId + "' — refusing to write");
        }
    }

    /**
     * Canonical reader form for the equal-epoch comparison: de-duplicated and sorted, so two writers
     * that computed the SAME grants in a different order compare equal (an order-only difference
     * must be idempotent, not an endless divergence).
     */
    static List<String> canonical(List<String> readers) {
        if (readers == null) {
            return null;
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String r : readers) {
            if (r != null) sorted.add(r);
        }
        return new ArrayList<>(sorted);
    }

    /**
     * Normalize what is ALREADY stored, for comparison only. Deliberately lenient (unlike
     * {@link #strictComputedReaders}): whatever is in the index is a fact to be compared, not a
     * computation to be validated, and rejecting it would block the very write that repairs it.
     */
    private static List<String> normalizeStoredReaders(List<String> stored) {
        return canonical(stored);
    }

    private static List<String> stringList(java.util.Collection<Object> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (Object v : values) {
                if (v != null) out.add(v.toString());
            }
        }
        return out;
    }
}
