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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Historical publish intents over the lineage database.
 *
 * <p>Every transition is a {@code _rev} CAS guarded by both the claim token and the expected
 * {@code from} state, so a worker that slept through a reclaim cannot re-apply a transition
 * another worker already made.
 *
 * <p>The subject fence is a separate document keyed by the subject, holding the intent id that
 * currently owns the catalog entity. It is leased, so an abandoned holder cannot block a subject
 * forever.
 */
public class CouchLineageHistoricalPublishIntentStore
        implements LineageHistoricalPublishIntentStore {

    private static final String FENCE_ID_PREFIX = "lineage_historical_fence:";
    private static final String FENCE_TYPE = "lineage_historical_fence";

    private final LineageStoreSupport support;

    public CouchLineageHistoricalPublishIntentStore(LineageStoreSupport support) {
        this.support = support;
    }

    @Override
    public LineageHistoricalPublishIntent createIfAbsent(LineageHistoricalPublishIntent intent) {
        support.ensureDatabase();
        Map<String, Object> existing = support.readRawStrict(intent.documentId());
        if (existing != null) {
            return adopt(intent, fromRaw(existing));
        }
        Map<String, Object> raw = toRaw(intent);
        raw.put("_id", intent.documentId());
        try {
            support.client().create(raw);
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException raced) {
            Map<String, Object> now = support.readRawStrict(intent.documentId());
            if (now == null) {
                throw new IntentStorageException(
                        "an intent conflicted on create and then vanished");
            }
            return adopt(intent, fromRaw(now));
        }
        return read(intent.intentId()).orElseThrow(() -> new IntentStorageException(
                "an intent is not readable after being created"));
    }

    private LineageHistoricalPublishIntent adopt(LineageHistoricalPublishIntent wanted,
            LineageHistoricalPublishIntent existing) {
        if (!wanted.samePlanAs(existing)) {
            // The id is derived from the plan, so this cannot happen from a different plan —
            // it is corruption or tampering, and adopting it would publish something else.
            throw new IntentPlanConflictException(
                    "an intent with this id describes a different plan");
        }
        return existing;
    }

    @Override
    public Optional<LineageHistoricalPublishIntent> read(String intentId) {
        if (intentId == null || intentId.isBlank()) {
            return Optional.empty();
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(
                LineageHistoricalPublishIntent.DOCUMENT_ID_PREFIX + intentId);
        return raw == null ? Optional.empty() : Optional.of(fromRaw(raw));
    }

    @Override
    public Optional<IntentClaim> claim(String intentId, String owner, Duration lease,
            long nowMs) {
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(
                LineageHistoricalPublishIntent.DOCUMENT_ID_PREFIX + intentId);
        if (raw == null) {
            return Optional.empty();
        }
        LineageHistoricalPublishIntent current = fromRaw(raw);
        if (current.token() != null && !current.leaseExpired(nowMs)) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        long until = Math.addExact(nowMs, lease.toMillis());
        raw.put("owner", owner);
        raw.put("token", token);
        raw.put("leaseUntilMs", until);
        if (!support.updateStrictCas(raw)) {
            return Optional.empty();
        }
        // The state AS OF THE CAS, so the caller does not branch on a pre-claim reading.
        return Optional.of(new IntentClaim(intentId, owner, token, until, current.state()));
    }

    @Override
    public Optional<IntentClaim> renew(IntentClaim claim, Duration lease, long nowMs) {
        Map<String, Object> raw = heldBy(claim);
        if (raw == null) {
            return Optional.empty();
        }
        long until = Math.addExact(nowMs, lease.toMillis());
        raw.put("leaseUntilMs", until);
        if (!support.updateStrictCas(raw)) {
            return Optional.empty();
        }
        return Optional.of(new IntentClaim(claim.intentId(), claim.owner(), claim.token(),
                until, fromRaw(raw).state()));
    }

    @Override
    public boolean transition(IntentClaim claim, LineageHistoricalPublishIntent.State from,
            LineageHistoricalPublishIntent.State to, String reason) {
        Map<String, Object> raw = heldBy(claim);
        if (raw == null || !from.name().equals(raw.get("state"))) {
            return false;
        }
        raw.put("state", to.name());
        raw.put("reason", reason);
        return support.updateStrictCas(raw);
    }

    @Override
    public boolean recordAttempt(IntentClaim claim, String reason) {
        Map<String, Object> raw = heldBy(claim);
        if (raw == null) {
            return false;
        }
        raw.put("attempts",
                (int) LineageStoreDecoding.exactLong(raw.getOrDefault("attempts", 0L),
                        "attempts") + 1);
        raw.put("reason", reason);
        // The hold is given up with the attempt: the next pass re-reads and re-decides.
        raw.remove("owner");
        raw.remove("token");
        raw.put("leaseUntilMs", 0L);
        return support.updateStrictCas(raw);
    }

    @Override
    public List<LineageHistoricalPublishIntent> findByState(
            LineageHistoricalPublishIntent.State state, int limit) {
        support.ensureDatabase();
        List<LineageHistoricalPublishIntent> found = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", state.name());
        params.put("limit", Math.max(1, limit));
        params.put("include_docs", true);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                support.client().queryView(support.designDoc(), "historicalIntentsByState",
                        params);
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
        return found;
    }

    @Override
    public Optional<SubjectFence> acquireSubjectFence(String subjectKey, String intentId,
            Duration lease, long nowMs) {
        support.ensureDatabase();
        String documentId = FENCE_ID_PREFIX + subjectKey;
        Map<String, Object> raw = support.readRawStrict(documentId);
        String token = UUID.randomUUID().toString();
        long until = Math.addExact(nowMs, lease.toMillis());
        if (raw == null) {
            Map<String, Object> fresh = new LinkedHashMap<>();
            fresh.put("_id", documentId);
            fresh.put("type", FENCE_TYPE);
            fresh.put("subjectKey", subjectKey);
            fresh.put("intentId", intentId);
            fresh.put("token", token);
            fresh.put("leaseUntilMs", until);
            try {
                support.client().create(fresh);
            } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException raced) {
                // Someone took it between the read and the write.
                return Optional.empty();
            }
            return Optional.of(new SubjectFence(subjectKey, intentId, token, until));
        }
        long heldUntil = LineageStoreDecoding.exactLong(
                raw.getOrDefault("leaseUntilMs", 0L), "leaseUntilMs");
        String holder = raw.get("intentId") instanceof String s ? s : null;
        if (heldUntil > nowMs && !intentId.equals(holder)) {
            // Another intent is writing this entity. Waiting is the point.
            return Optional.empty();
        }
        raw.put("intentId", intentId);
        raw.put("token", token);
        raw.put("leaseUntilMs", until);
        if (!support.updateStrictCas(raw)) {
            return Optional.empty();
        }
        return Optional.of(new SubjectFence(subjectKey, intentId, token, until));
    }

    @Override
    public boolean releaseSubjectFence(SubjectFence fence) {
        if (fence == null) {
            return false;
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(FENCE_ID_PREFIX + fence.subjectKey());
        if (raw == null || !fence.token().equals(raw.get("token"))) {
            return false;
        }
        raw.put("leaseUntilMs", 0L);
        raw.remove("token");
        return support.updateStrictCas(raw);
    }

    /** The document, but only if this claim's token still holds it. */
    private Map<String, Object> heldBy(IntentClaim claim) {
        if (claim == null || claim.token() == null) {
            return null;
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(
                LineageHistoricalPublishIntent.DOCUMENT_ID_PREFIX + claim.intentId());
        return raw != null && claim.token().equals(raw.get("token")) ? raw : null;
    }

    static Map<String, Object> toRaw(LineageHistoricalPublishIntent intent) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", LineageHistoricalPublishIntent.DOCUMENT_TYPE);
        raw.put("intentId", intent.intentId());
        raw.put("taskKey", intent.taskKey());
        raw.put("target", intent.target());
        raw.put("repositoryId", intent.repositoryId());
        raw.put("endpointKind", intent.endpointKind().name());
        raw.put("subjectDigest", intent.subjectDigest());
        raw.put("snapshotEvidenceDigest", intent.snapshotEvidenceDigest());
        raw.put("sourceEvidenceDigest", intent.sourceEvidenceDigest());
        raw.put("plannedOperationDigest", intent.plannedOperationDigest());
        raw.put("payloadSchemaVersion", intent.payloadSchemaVersion());
        raw.put("state", intent.state().name());
        raw.put("attempts", intent.attempts());
        raw.put("createdAtMs", intent.createdAtMs());
        raw.put("leaseUntilMs", intent.leaseUntilMs());
        if (intent.owner() != null) {
            raw.put("owner", intent.owner());
        }
        if (intent.token() != null) {
            raw.put("token", intent.token());
        }
        if (intent.reason() != null) {
            raw.put("reason", intent.reason());
        }
        return raw;
    }

    static LineageHistoricalPublishIntent fromRaw(Map<String, Object> raw) {
        if (!LineageHistoricalPublishIntent.DOCUMENT_TYPE.equals(raw.get("type"))) {
            throw new IntentStorageException("document is not a historical publish intent");
        }
        return new LineageHistoricalPublishIntent(
                asString(raw.get("_rev")), requireString(raw, "intentId"),
                requireString(raw, "taskKey"), requireString(raw, "target"),
                requireString(raw, "repositoryId"),
                EndpointKind.valueOf(requireString(raw, "endpointKind")),
                requireString(raw, "subjectDigest"),
                requireString(raw, "snapshotEvidenceDigest"),
                requireString(raw, "sourceEvidenceDigest"),
                requireString(raw, "plannedOperationDigest"),
                (int) LineageStoreDecoding.exactLong(
                        raw.getOrDefault("payloadSchemaVersion", 1L), "payloadSchemaVersion"),
                LineageHistoricalPublishIntent.State.valueOf(requireString(raw, "state")),
                asString(raw.get("owner")), asString(raw.get("token")),
                LineageStoreDecoding.exactLong(raw.getOrDefault("leaseUntilMs", 0L),
                        "leaseUntilMs"),
                (int) LineageStoreDecoding.exactLong(raw.getOrDefault("attempts", 0L),
                        "attempts"),
                LineageStoreDecoding.exactLong(raw.getOrDefault("createdAtMs", 0L),
                        "createdAtMs"),
                asString(raw.get("reason")));
    }

    private static String requireString(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IntentStorageException("an intent has no usable '" + field + "'");
        }
        return s;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
