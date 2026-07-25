package jp.aegif.nemaki.epoch;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ── SPI ────────────────────────────────────────────────────────

    /**
     * Computes the canonical reader tokens from an AUTHORITATIVE snapshot. Production supplies an
     * {@code ACLExpander}-backed implementation in the wiring increment; keeping it an interface is
     * what lets this increment stay unwired and lets the concurrency ITs drive the protocol
     * deterministically.
     *
     * <p>It MUST fail (throw) rather than return an empty/partial list when it cannot compute the
     * readers: writing an empty {@code readers} would make the object invisible to every non-admin
     * search, which is a silent availability failure, and writing a PARTIAL list is worse.
     */
    @FunctionalInterface
    public interface ReadersComputer {
        List<String> compute(AclEffectiveEpochService.Snapshot snapshot);
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
     * @throws AclEpochWriteContentionException                      restarts exhausted (retryable)
     */
    public WriteOutcome write(String repositoryId, String objectId, SolrClient solrClient,
                              ReadersComputer computer) throws Exception {
        if (effectiveEpochService == null) {
            throw new IllegalStateException("effectiveEpochService not wired on AclEpochIndexWriter");
        }
        if (solrClient == null) {
            throw new IllegalStateException("Solr client unavailable for " + objectId);
        }
        if (computer == null) {
            throw new IllegalArgumentException("readers computer is required");
        }

        // A single authoritative recompute is FORCED after an equal-epoch/different-readers
        // observation (§4.3): the recomputed value is then CAS-written rather than compared again,
        // which is what makes every conflicting writer converge instead of ping-ponging.
        boolean forceWriteAfterEqualEpochDivergence = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // ── steps 1-2: authoritative walk (+ pending gate) and compute ──
            AclEffectiveEpochService.Snapshot snapshot = effectiveEpochService.snapshot(repositoryId, objectId);
            if (snapshot == null) {
                return new WriteOutcome(WriteResult.SKIPPED_DELETED, 0L, null, attempt);
            }
            List<String> readers = canonical(computer.compute(snapshot));
            if (readers == null) {
                // A computer that cannot produce readers must throw; a null return would otherwise
                // become an empty `readers` and make the object invisible to every non-admin search.
                throw new IllegalStateException("readers computer returned null for " + objectId
                        + " — refusing to write an empty ACL group");
            }
            long myEpoch = snapshot.effectiveEpoch;

            // ── step 3: realtime GET (never a searcher query) ──
            SolrDocument current = realtimeGet(solrClient, repositoryId, objectId);
            if (current == null) {
                return new WriteOutcome(WriteResult.NOT_INDEXED, myEpoch, null, attempt);
            }
            long version = requireLong(current, FIELD_VERSION, objectId);
            Long storedEpoch = optionalLong(current, FIELD_EFFECTIVE_EPOCH, objectId);
            List<String> storedReaders = canonical(stringList(current.getFieldValues(FIELD_READERS)));

            // ── step 4: revalidate every recorded dependency; ANY change restarts ──
            if (!effectiveEpochService.revalidate(snapshot)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("ACL epoch write: dependencies changed under {} — restarting", objectId);
                }
                forceWriteAfterEqualEpochDivergence = false; // the payload is stale; recompute clean
                continue;
            }

            // ── §4.3 fence decision ──
            if (!forceWriteAfterEqualEpochDivergence) {
                long stored = storedEpoch == null ? 0L : storedEpoch;
                if (stored > myEpoch) {
                    return new WriteOutcome(WriteResult.SKIPPED_FRESHER, myEpoch, null, attempt);
                }
                if (stored == myEpoch && storedEpoch != null) {
                    if (storedReaders.equals(readers)) {
                        return new WriteOutcome(WriteResult.SKIPPED_IDEMPOTENT, myEpoch, null, attempt);
                    }
                    // EQUAL epoch, DIFFERENT readers: a transient read-skew artefact. Never "my
                    // payload wins by default" — recompute from the authoritative sources and write
                    // THAT (§4.3), so every conflicting writer converges on the last-finalized state.
                    logger.info("ACL epoch write: equal epoch {} with divergent readers on {} — "
                            + "recomputing authoritatively", myEpoch, objectId);
                    forceWriteAfterEqualEpochDivergence = true;
                    continue;
                }
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
     * <p>Ids are globally-unique CMIS ids but the Solr core is SHARED across repositories, so a
     * {@code repository_id} mismatch is a HARD failure: returning "absent" would let the caller
     * create-if-absent or overwrite ANOTHER repository's document under the same unique key.
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
        Object repo = doc.getFieldValue(FIELD_REPOSITORY_ID);
        if (repo != null && !repositoryId.equals(repo.toString())) {
            throw new IllegalStateException("Solr id collision: '" + objectId + "' belongs to repository '"
                    + repo + "', not '" + repositoryId + "' — refusing to write");
        }
        return doc;
    }

    /**
     * Canonical reader form for the equal-epoch comparison: de-duplicated and sorted, so two
     * writers that computed the SAME grants in a different order compare equal (an order-only
     * difference must be idempotent, not an endless divergence).
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

    private static List<String> stringList(java.util.Collection<Object> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (Object v : values) {
                if (v != null) out.add(v.toString());
            }
        }
        return out;
    }

    /**
     * A REQUIRED numeric field. Missing / unparsable on the fenced path is FAIL-CLOSED (§4.3): the
     * write is abandoned with an exception so the caller retains and retries its task, rather than
     * writing without a fence.
     */
    private static long requireLong(SolrDocument doc, String field, String objectId) {
        Object v = doc.getFieldValue(field);
        if (!(v instanceof Number)) {
            throw new IllegalStateException("Solr document " + objectId + " has a missing / non-numeric "
                    + field + " (" + v + ") — refusing to write the ACL group unfenced");
        }
        return ((Number) v).longValue();
    }

    /**
     * An OPTIONAL numeric field: absent means "never fenced yet" ({@code null} → treated as 0 by the
     * caller, so any epoch wins), but a PRESENT non-numeric value is corruption and fails closed.
     */
    private static Long optionalLong(SolrDocument doc, String field, String objectId) {
        Object v = doc.getFieldValue(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number)) {
            throw new IllegalStateException("Solr document " + objectId + " has a non-numeric " + field
                    + " (" + v + ") — refusing to write the ACL group against an unparsable fence");
        }
        return ((Number) v).longValue();
    }
}
