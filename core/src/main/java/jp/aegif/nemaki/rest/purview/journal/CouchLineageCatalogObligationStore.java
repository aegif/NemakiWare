/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.journal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The obligation machine over the lineage database (§2).
 *
 * <h2>Fencing</h2>
 *
 * <p>A claim is {@code (owner, token, leaseUntilMs)}. Every later transition requires the token
 * to still be the one on the document. Reclaiming an expired claim mints a <em>new</em> token,
 * which is what makes the old worker's write fail rather than land on top of the new one — a
 * lease alone would not, because a worker that stalled past its lease has no way to know it did.
 *
 * <p>The token is compared, never logged. It authorises a write, so it is a credential.
 */
public class CouchLineageCatalogObligationStore implements LineageCatalogObligationStore {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(CouchLineageCatalogObligationStore.class);

    private static final int MAX_CAS_ATTEMPTS = 5;

    /** Backoff used by the reclaimer, which has no caller to supply one. */
    static final long DEFAULT_BACKOFF_BASE_MS = 5_000L;
    static final long DEFAULT_BACKOFF_MAX_MS = 300_000L;

    private final LineageStoreSupport support;

    public CouchLineageCatalogObligationStore(LineageStoreSupport support) {
        this.support = support;
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Override
    public LineageCatalogObligation createIfAbsent(LineageCatalogObligation obligation) {
        if (obligation == null) {
            throw new IllegalArgumentException("obligation must not be null");
        }
        support.ensureDatabase();
        String documentId = obligation.documentId();

        Map<String, Object> existingRaw = support.readRawStrict(documentId);
        if (existingRaw != null) {
            return adopt(obligation, existingRaw);
        }
        Map<String, Object> raw = toRaw(obligation);
        raw.put("_id", documentId);
        try {
            support.client().create(raw);
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException lost) {
            // Someone created it between the read and the write. Re-read and adopt, which is
            // the same answer this call would have given a moment earlier.
            Map<String, Object> raced = support.readRawStrict(documentId);
            if (raced == null) {
                throw new ObligationStorageException(
                        "obligation '" + documentId + "' conflicted on create and then vanished");
            }
            return adopt(obligation, raced);
        } catch (RuntimeException e) {
            throw new ObligationStorageException(
                    "could not create obligation '" + documentId + "'", e);
        }
        return read(obligation.taskKey()).orElseThrow(() -> new ObligationStorageException(
                "obligation '" + documentId + "' is not readable after being created"));
    }

    /**
     * Accepts an existing document as this obligation, or refuses it.
     *
     * <p>A task key holding a different subject is not "already done" — it is two different
     * things claiming one identity, and adopting it would make the caller wait for an entity it
     * never asked about.
     */
    private LineageCatalogObligation adopt(LineageCatalogObligation wanted,
            Map<String, Object> existingRaw) {
        LineageCatalogObligation existing = fromRaw(existingRaw);
        if (!wanted.sameSubjectAs(existing)) {
            throw new ObligationSubjectConflictException(
                    "obligation '" + wanted.documentId() + "' already exists describing a"
                            + " different subject (kind=" + existing.endpointKind()
                            + " target=" + existing.target() + "); refusing to treat two"
                            + " subjects as one obligation");
        }
        return existing;
    }

    @Override
    public Optional<LineageCatalogObligation> read(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return Optional.empty();
        }
        support.ensureDatabase();
        Map<String, Object> raw =
                support.readRawStrict(LineageCatalogObligation.DOCUMENT_ID_PREFIX + taskKey);
        return raw == null ? Optional.empty() : Optional.of(fromRaw(raw));
    }

    // ------------------------------------------------------------------
    // Claim and fencing
    // ------------------------------------------------------------------

    @Override
    public Optional<Claim> claim(String taskKey, String owner, Duration lease, long nowMs) {
        if (owner == null || owner.isBlank() || lease == null || lease.isNegative()) {
            throw new IllegalArgumentException("a claim needs an owner and a non-negative lease");
        }
        support.ensureDatabase();
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Map<String, Object> raw = support.readRawStrict(
                    LineageCatalogObligation.DOCUMENT_ID_PREFIX + taskKey);
            if (raw == null) {
                return Optional.empty();
            }
            LineageCatalogObligation current = fromRaw(raw);
            // Checked again HERE, under the read this CAS is about to use: the query that
            // found this obligation may have run before its backoff, and the state may have
            // moved since. Eligibility filtered only at query time would be advisory.
            if (!current.claimableAt(nowMs)) {
                return Optional.empty();
            }
            // A fresh token on every claim, including a reclaim: this is the fence, and the
            // reason the previous holder cannot finish on top of the new one.
            String token = UUID.randomUUID().toString();
            long leaseUntil = Math.addExact(nowMs, lease.toMillis());
            raw.put("state", LineageCatalogObligation.State.CLAIMED.name());
            raw.put("owner", owner);
            raw.put("token", token);
            raw.put("leaseUntilMs", leaseUntil);
            if (support.updateStrictCas(raw)) {
                return Optional.of(new Claim(taskKey, owner, token, leaseUntil));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Claim> renew(Claim claim, Duration lease, long nowMs) {
        if (claim == null || lease == null) {
            return Optional.empty();
        }
        Map<String, Object> raw = readForClaim(claim);
        if (raw == null) {
            return Optional.empty();
        }
        long leaseUntil = Math.addExact(nowMs, lease.toMillis());
        raw.put("leaseUntilMs", leaseUntil);
        if (!support.updateStrictCas(raw)) {
            return Optional.empty();
        }
        return Optional.of(new Claim(claim.taskKey(), claim.owner(), claim.token(), leaseUntil));
    }

    @Override
    public boolean resolve(Claim claim, LineageCatalogObligation.Outcome outcome, String reason,
            String evidence) {
        return finish(claim, LineageCatalogObligation.State.RESOLVED, outcome, reason, evidence);
    }

    @Override
    public boolean giveUp(Claim claim, LineageCatalogObligation.Outcome outcome, String reason,
            String evidence) {
        if (outcome == LineageCatalogObligation.Outcome.SOURCE_ERROR) {
            // Refused at the store, not left to the caller: a transient catalog failure
            // recorded as terminal makes every event waiting on this obligation permanently
            // unprojectable, and nothing later can tell it apart from a real one.
            throw new IllegalArgumentException(
                    "SOURCE_ERROR is retryable and must not be recorded as UNRESOLVED");
        }
        return finish(claim, LineageCatalogObligation.State.UNRESOLVED, outcome, reason, evidence);
    }

    private boolean finish(Claim claim, LineageCatalogObligation.State state,
            LineageCatalogObligation.Outcome outcome, String reason, String evidence) {
        if (reason == null || reason.isBlank() || outcome == null
                || outcome == LineageCatalogObligation.Outcome.NONE) {
            throw new IllegalArgumentException(
                    "a terminal obligation must carry an outcome and a reason");
        }
        Map<String, Object> raw = readForClaim(claim);
        if (raw == null) {
            return false;
        }
        raw.put("state", state.name());
        raw.put("outcome", outcome.name());
        raw.put("reason", reason);
        raw.put("evidence", evidence);
        // The claim is over; leaving the token would let a replayed write look authorised.
        raw.remove("token");
        raw.put("leaseUntilMs", 0L);
        return support.updateStrictCas(raw);
    }

    @Override
    public boolean release(Claim claim, String reason, long nowMs, long baseMs, long maxMs) {
        Map<String, Object> raw = readForClaim(claim);
        if (raw == null) {
            return false;
        }
        int attempts = asInt(raw.get("attempts")) + 1;
        raw.put("state", LineageCatalogObligation.State.PENDING.name());
        raw.put("attempts", attempts);
        // Durable: a restart must not reset the schedule and turn an outage into a hot loop.
        raw.put("notBeforeMs",
                LineageCatalogObligation.backoffUntil(nowMs, attempts, baseMs, maxMs));
        raw.put("reason", reason);
        raw.remove("owner");
        raw.remove("token");
        raw.put("leaseUntilMs", 0L);
        return support.updateStrictCas(raw);
    }

    /**
     * The document, but only if {@code claim}'s token still holds it.
     *
     * <p>{@code null} for every other case — gone, terminal, taken by someone else, or the
     * token replaced by a reclaim. The caller cannot tell those apart, and should not: in all
     * of them the answer is the same, this worker no longer has the right to write.
     */
    private Map<String, Object> readForClaim(Claim claim) {
        if (claim == null || claim.token() == null) {
            return null;
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(
                LineageCatalogObligation.DOCUMENT_ID_PREFIX + claim.taskKey());
        if (raw == null) {
            return null;
        }
        if (!LineageCatalogObligation.State.CLAIMED.name().equals(raw.get("state"))) {
            return null;
        }
        return claim.token().equals(raw.get("token")) ? raw : null;
    }

    // ------------------------------------------------------------------
    // Recovery and queries
    // ------------------------------------------------------------------

    @Override
    public int reclaimExpired(int limit, long nowMs) {
        int reclaimed = 0;
        for (LineageCatalogObligation obligation
                : findByState(LineageCatalogObligation.State.CLAIMED, Math.max(1, limit))) {
            if (!obligation.leaseExpired(nowMs)) {
                continue;
            }
            Map<String, Object> raw = support.readRawStrict(obligation.documentId());
            if (raw == null
                    || !LineageCatalogObligation.State.CLAIMED.name().equals(raw.get("state"))) {
                continue;
            }
            // Re-check under the read we are about to CAS on: the holder may have renewed
            // between the query and here, and reclaiming a live claim would be the very
            // double-ownership this is meant to prevent.
            if (asLong(raw.get("leaseUntilMs")) > nowMs) {
                continue;
            }
            int attempts = asInt(raw.get("attempts")) + 1;
            raw.put("state", LineageCatalogObligation.State.PENDING.name());
            raw.put("attempts", attempts);
            // A worker that died mid-check gets the same backoff as one that failed: coming
            // straight back would hammer whatever killed it.
            raw.put("notBeforeMs", LineageCatalogObligation.backoffUntil(
                    nowMs, attempts, DEFAULT_BACKOFF_BASE_MS, DEFAULT_BACKOFF_MAX_MS));
            raw.put("reason", "lease expired");
            raw.remove("owner");
            raw.remove("token");
            raw.put("leaseUntilMs", 0L);
            if (support.updateStrictCas(raw)) {
                reclaimed++;
                logger.info("Reclaimed an expired catalog obligation: {}", obligation);
            }
        }
        return reclaimed;
    }

    @Override
    public List<LineageCatalogObligation> findClaimable(int limit, long nowMs) {
        List<LineageCatalogObligation> claimable = new ArrayList<>();
        // Over-read, because the view cannot filter on notBeforeMs without a second index and
        // the eligible ones may be sparse. Bounded either way.
        for (LineageCatalogObligation o
                : findByState(LineageCatalogObligation.State.PENDING, Math.max(1, limit) * 4)) {
            if (o.claimableAt(nowMs) && claimable.size() < Math.max(1, limit)) {
                claimable.add(o);
            }
        }
        return claimable;
    }

    @Override
    public List<LineageCatalogObligation> findByState(LineageCatalogObligation.State state,
            int limit) {
        support.ensureDatabase();
        List<LineageCatalogObligation> found = new ArrayList<>();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("key", state.name());
            params.put("limit", Math.max(1, limit));
            params.put("include_docs", true);
            com.ibm.cloud.cloudant.v1.model.ViewResult result =
                    support.client().queryView(support.designDoc(), "obligationsByState", params);
            if (result == null || result.getRows() == null) {
                return List.of();
            }
            for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                if (doc == null) {
                    continue;
                }
                Map<String, Object> raw = new LinkedHashMap<>();
                if (doc.getId() != null) {
                    raw.put("_id", doc.getId());
                }
                if (doc.getRev() != null) {
                    raw.put("_rev", doc.getRev());
                }
                if (doc.getProperties() != null) {
                    raw.putAll(doc.getProperties());
                }
                found.add(fromRaw(raw));
            }
        } catch (RuntimeException e) {
            throw new ObligationStorageException("obligation query failed for state " + state, e);
        }
        return found;
    }

    @Override
    public Map<LineageCatalogObligation.State, Long> countByState() {
        Map<LineageCatalogObligation.State, Long> counts =
                new EnumMap<>(LineageCatalogObligation.State.class);
        for (LineageCatalogObligation.State state : LineageCatalogObligation.State.values()) {
            counts.put(state, (long) findByState(state, 10_000).size());
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // Codec
    // ------------------------------------------------------------------

    static Map<String, Object> toRaw(LineageCatalogObligation obligation) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", LineageCatalogObligation.DOCUMENT_TYPE);
        raw.put("taskKey", obligation.taskKey());
        raw.put("target", obligation.target());
        raw.put("repositoryId", obligation.repositoryId());
        raw.put("endpointKind", obligation.endpointKind().name());
        raw.put("catalogQualifiedName", obligation.catalogQualifiedName());
        raw.put("state", obligation.state().name());
        raw.put("attempts", obligation.attempts());
        raw.put("createdAtMs", obligation.createdAtMs());
        raw.put("outcome", obligation.outcome().name());
        if (obligation.owner() != null) {
            raw.put("owner", obligation.owner());
        }
        if (obligation.token() != null) {
            raw.put("token", obligation.token());
        }
        raw.put("leaseUntilMs", obligation.leaseUntilMs());
        raw.put("notBeforeMs", obligation.notBeforeMs());
        if (obligation.reason() != null) {
            raw.put("reason", obligation.reason());
        }
        if (obligation.evidence() != null) {
            raw.put("evidence", obligation.evidence());
        }
        if (obligation.rev() != null) {
            raw.put("_rev", obligation.rev());
        }
        return raw;
    }

    /** Strict: a shape that cannot mean anything is refused rather than partly understood. */
    static LineageCatalogObligation fromRaw(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        if (!LineageCatalogObligation.DOCUMENT_TYPE.equals(raw.get("type"))) {
            throw new ObligationStorageException(
                    "document '" + raw.get("_id") + "' is not a catalog obligation");
        }
        return new LineageCatalogObligation(
                asString(raw.get("_rev")),
                requireString(raw, "taskKey"),
                requireString(raw, "target"),
                requireString(raw, "repositoryId"),
                parseKind(requireString(raw, "endpointKind")),
                requireString(raw, "catalogQualifiedName"),
                parseState(requireString(raw, "state")),
                asString(raw.get("owner")),
                asString(raw.get("token")),
                asLong(raw.get("leaseUntilMs")),
                asLong(raw.get("notBeforeMs")),
                asInt(raw.get("attempts")),
                asLong(raw.get("createdAtMs")),
                parseOutcome(asString(raw.get("outcome"))),
                asString(raw.get("reason")),
                asString(raw.get("evidence")));
    }

    private static EndpointKind parseKind(String name) {
        for (EndpointKind kind : EndpointKind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        throw new ObligationStorageException("unknown endpoint kind '" + name + "'");
    }

    private static LineageCatalogObligation.State parseState(String name) {
        for (LineageCatalogObligation.State state : LineageCatalogObligation.State.values()) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        throw new ObligationStorageException("unknown obligation state '" + name + "'");
    }

    private static LineageCatalogObligation.Outcome parseOutcome(String name) {
        if (name == null) {
            return LineageCatalogObligation.Outcome.NONE;
        }
        for (LineageCatalogObligation.Outcome outcome
                : LineageCatalogObligation.Outcome.values()) {
            if (outcome.name().equals(name)) {
                return outcome;
            }
        }
        throw new ObligationStorageException("unknown obligation outcome '" + name + "'");
    }

    private static String requireString(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ObligationStorageException(
                    "obligation '" + raw.get("_id") + "' has no usable '" + field + "'");
        }
        return s;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static long asLong(Object value) {
        return value == null ? 0L : LineageStoreDecoding.exactLong(value, "obligation number");
    }

    private static int asInt(Object value) {
        return (int) asLong(value);
    }
}
