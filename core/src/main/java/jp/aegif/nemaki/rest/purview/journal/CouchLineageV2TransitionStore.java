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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

/**
 * §8-b's token-fenced transition machine (D-rest-2), moved out of
 * {@link CouchLineageJournalStore} unchanged.
 *
 * <p>The claimed and unclaimed transition tables, the token fencing, the lease renewal and the
 * reap-by-CAS are exactly the ones that were there: every {@code _rev} read-modify-write moved
 * as one piece, and no pair was added to or removed from either table.
 *
 * <p>{@code findV2SequencedRepositoryIds} lives here because the CONTRACT puts it here — it is
 * declared on {@link LineageV2TransitionStore}. In the source it used to sit inside the
 * materialization block, and classifying it by that position is what broke the first attempt
 * at this split.
 */
final class CouchLineageV2TransitionStore {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(CouchLineageV2TransitionStore.class);

    private final LineageStoreSupport support;

    /** The claim-lease knob. Read exactly where it was read before. */
    private final LineageConfig lineageConfig;

    CouchLineageV2TransitionStore(LineageStoreSupport support, LineageConfig lineageConfig) {
        this.support = support;
        this.lineageConfig = lineageConfig;
    }

    LineageV2TransitionStore.V2ClaimGrant claimForProjection(String recordId, String target,
            java.time.Duration lease) {
        if (recordId == null || recordId.isBlank() || target == null || target.isBlank()
                || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("recordId, target and a positive lease are"
                    + " required");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return null;
        }
        if (!"lineage_event_v2".equals(raw.get("type"))) {
            logger.error("claimForProjection refused: '{}' is not a v2 row", recordId);
            return null;
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        if (row.state() != LineageJournalRowV2.SequencingState.SEQUENCED) {
            // Not deliverable, whatever the status map says — claims exist only past the
            // sequencer's finalize.
            return null;
        }
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        LineagePublishStatus status = current == null
                ? LineagePublishStatus.PENDING : current.status();
        if (status != LineagePublishStatus.PENDING && status != LineagePublishStatus.FAILED) {
            return null;
        }
        long nowMs = Instant.now().toEpochMilli();
        long expiresMs = Math.addExact(nowMs, lease.toMillis());
        String token = java.util.UUID.randomUUID().toString();
        CouchLineageJournalRowV2.applyProjectionClaim(raw, target, token, nowMs, expiresMs);
        if (!support.updateStrictCas(raw)) {
            return null;
        }
        return new LineageV2TransitionStore.V2ClaimGrant(recordId, target, token, Instant.ofEpochMilli(expiresMs));
    }

    /** The token-fenced (expected→next) pairs and their effects, per the frozen §8-b table. */
    private record FencedEffect(boolean toVerifying, boolean incrementRetry,
                                boolean reasonRequired) {
    }

    private static final Map<List<LineagePublishStatus>, FencedEffect> FENCED_TRANSITIONS =
            Map.of(
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING),
                    new FencedEffect(true, false, false),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED),
                    new FencedEffect(false, false, false),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.FAILED),
                    new FencedEffect(false, false, false),
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.FAILED),
                    new FencedEffect(false, true, false),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.UNPROJECTABLE),
                    new FencedEffect(false, false, true),
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.REJECTED),
                    new FencedEffect(false, false, true));

    boolean transitionV2(String recordId, String target, LineagePublishStatus expected,
            LineagePublishStatus next, String claimToken,
            LineageTargetLifecycle.TerminalReason reason) {
        FencedEffect effect = FENCED_TRANSITIONS.get(List.of(expected, next));
        if (effect == null) {
            throw new IllegalArgumentException("transition " + expected + "->" + next
                    + " is not in the fenced §8-b table — caller bug, not a race");
        }
        if (effect.reasonRequired() && reason == null) {
            throw new IllegalArgumentException(next + " requires a durable terminal reason");
        }
        if (!effect.reasonRequired() && reason != null) {
            throw new IllegalArgumentException(next + " must not carry a terminal reason");
        }
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("fenced transitions require the claim token");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        if (current == null || current.status() != expected
                || !claimToken.equals(current.claimToken())) {
            return false;
        }
        long nowMs = Instant.now().toEpochMilli();
        if (current.leaseExpiresAtMs() == null || current.leaseExpiresAtMs() <= nowMs) {
            // F4: EVERY claimant write fails after expiry — an expired claimant racing the
            // reaper must lose, not settle. (The reaper's FAILED write CAS-beats us anyway;
            // this makes the fence explicit rather than a rev-timing accident.)
            return false;
        }
        if (effect.toVerifying()) {
            // PROJECTING→VERIFYING renews the lease ATOMICALLY in the same CAS (§8-b: the
            // transition row says "renews lease"). Lease policy is the store's (config).
            // Null-safe for the direct-client test construction (no Spring context);
            // production always injects the config.
            long leaseSeconds = lineageConfig != null
                    ? lineageConfig.getProjectionClaimLeaseSeconds() : 120L;
            long leaseMs = Math.addExact(nowMs,
                    java.time.Duration.ofSeconds(leaseSeconds).toMillis());
            CouchLineageJournalRowV2.applyVerifying(raw, target, nowMs, leaseMs);
        } else {
            CouchLineageJournalRowV2.applySettle(raw, target, next, effect.incrementRetry(),
                    reason);
        }
        return support.updateStrictCas(raw);
    }

    /** The pre-claim (expected→next) pairs: obligation + admin rows of the frozen table. */
    private static final Map<List<LineagePublishStatus>, Boolean> UNCLAIMED_TRANSITIONS =
            Map.of(
                    List.of(LineagePublishStatus.PENDING,
                            LineagePublishStatus.WAITING_FOR_CATALOG), false,
                    List.of(LineagePublishStatus.WAITING_FOR_CATALOG,
                            LineagePublishStatus.PENDING), false,
                    List.of(LineagePublishStatus.WAITING_FOR_CATALOG,
                            LineagePublishStatus.UNRESOLVED), true,
                    List.of(LineagePublishStatus.PENDING,
                            LineagePublishStatus.DISCARDED), false,
                    // v2.3.24 F1: the row was created but its plan turned out to be
                    // unstorable, so this target can never be delivered. UNRESOLVED is the
                    // same verdict the creation-time classification writes for an
                    // unsplittable fact — the only difference is that here it is learned
                    // AFTER the row exists. DISCARDED cannot serve: it forbids the durable
                    // reason, and it is illegal on the UNSEQUENCED row this always is.
                    List.of(LineagePublishStatus.PENDING,
                            LineagePublishStatus.UNRESOLVED), true,
                    List.of(LineagePublishStatus.FAILED,
                            LineagePublishStatus.DISCARDED), false);

    boolean transitionV2Unclaimed(String recordId, String target,
            LineagePublishStatus expected, LineagePublishStatus next,
            LineageTargetLifecycle.TerminalReason reason) {
        Boolean reasonRequired = UNCLAIMED_TRANSITIONS.get(List.of(expected, next));
        if (reasonRequired == null) {
            throw new IllegalArgumentException("transition " + expected + "->" + next
                    + " is not in the unclaimed §8-b table — caller bug, not a race");
        }
        if (reasonRequired && reason == null) {
            throw new IllegalArgumentException(next + " requires a durable terminal reason");
        }
        if (!reasonRequired && reason != null) {
            throw new IllegalArgumentException(next + " must not carry a terminal reason");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        LineagePublishStatus status = current == null
                ? LineagePublishStatus.PENDING : current.status();
        if (status != expected) {
            return false;
        }
        if (expected == LineagePublishStatus.FAILED) {
            // FAILED→DISCARDED: status-only write; the audit bundle rides along untouched
            // (field-preserving map mutation — nothing is removed).
            CouchLineageJournalRowV2.applySettle(raw, target, next, false, reason);
        } else {
            CouchLineageJournalRowV2.applyStatusOnly(raw, target, next, reason);
        }
        return support.updateStrictCas(raw);
    }

    boolean renewClaim(String recordId, String target, String claimToken,
            java.time.Duration lease) {
        if (claimToken == null || claimToken.isBlank() || lease == null
                || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("claim token and a positive lease are required");
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = support.decodeV2Strict(raw);
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        if (current == null || !current.hasLiveClaim()
                || !claimToken.equals(current.claimToken())) {
            return false;
        }
        long nowMs = Instant.now().toEpochMilli();
        if (current.leaseExpiresAtMs() == null || current.leaseExpiresAtMs() <= nowMs) {
            // An expired claim never self-resurrects — it goes through the reaper like
            // anyone's.
            return false;
        }
        CouchLineageJournalRowV2.applyRenew(raw, target, Math.addExact(nowMs, lease.toMillis()));
        return support.updateStrictCas(raw);
    }

    int reapExpiredClaims(String target, Instant cutoff) {
        if (target == null || target.isBlank() || cutoff == null) {
            throw new IllegalArgumentException("target and cutoff are required");
        }
        support.ensureDatabase();
        long cutoffMs = cutoff.toEpochMilli();
        int reaped = 0;
        // Mutation-safe pagination (F2): a successful reap REMOVES its row from this view, so
        // an anchor-plus-skip continuation would skip the first surviving candidate once its
        // anchor vanished. Instead: no skip, an in-memory examined set (bounded by this run),
        // and an anchor that advances to the last row of every page — corrupt or CAS-lost
        // rows are re-served by CouchDB but never re-examined, and can never pin a page.
        java.util.Set<String> examined = new java.util.HashSet<>();
        Object pageStartKey = List.of(target, 0L);
        String pageStartDocId = null;
        int pageSize = 100;
        while (true) {
            ViewResult result;
            try {
                var builder = new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                        .db(support.client().getDatabaseName())
                        .ddoc(support.designDoc())
                        .view("v2_claims_by_expiry")
                        .startKey(pageStartKey)
                        .endKey(List.of(target, cutoffMs))
                        .inclusiveEnd(false)
                        .reduce(false)
                        .limit((long) pageSize);
                if (pageStartDocId != null) {
                    builder.startKeyDocId(pageStartDocId);
                }
                result = support.client().getClient().postView(builder.build())
                        .execute().getResult();
            } catch (RuntimeException e) {
                throw new LineageSequencingStore.SequencingStorageException("v2_claims_by_expiry query failed for '"
                        + target + "'", e);
            }
            if (result == null || result.getRows() == null) {
                throw new LineageSequencingStore.SequencingStorageException("v2_claims_by_expiry returned no result"
                        + " for '" + target + "'", null);
            }
            List<ViewResultRow> rows = result.getRows();
            boolean sawNew = false;
            for (ViewResultRow viewRow : rows) {
                String docId = viewRow.getId();
                if (!examined.add(docId)) {
                    continue;
                }
                sawNew = true;
                try {
                    Map<String, Object> raw = support.readRawStrict(docId);
                    if (raw == null) {
                        continue;
                    }
                    LineageJournalRowV2 row = support.decodeV2Strict(raw);
                    LineageTargetLifecycle current = row.targetLifecycles().get(target);
                    if (current == null || !current.hasLiveClaim()
                            || current.leaseExpiresAtMs() == null
                            || current.leaseExpiresAtMs() >= cutoffMs) {
                        continue; // stale view entry, rotated claim, or no longer live
                    }
                    // Reap-by-CAS: the status/token just reread are what the CAS write rides
                    // on (_rev). No retry increment — a crashed claim is not an observed
                    // publish failure.
                    CouchLineageJournalRowV2.applySettle(raw, target,
                            LineagePublishStatus.FAILED, false, null);
                    if (support.updateStrictCas(raw)) {
                        reaped++;
                        if (support.metrics() != null) {
                            support.metrics().recordV2ClaimReaped(target);
                        }
                    }
                } catch (LineageSequencingStore.SequencingStorageException e) {
                    // Corrupt rows are refused loudly and cannot pin the page (the examined
                    // set carries the scan past them); infra failures abort the reap.
                    if (e.getCause() instanceof NotFoundException
                            || e.getMessage() != null
                            && e.getMessage().startsWith("undecodable v2 row")) {
                        logger.error("Reaper skipping corrupt v2 row {}: {}", docId,
                                e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
            if (rows.size() < pageSize) {
                return reaped;
            }
            if (!sawNew && rows.size() == pageSize) {
                // A full page of already-examined rows: force the anchor past it.
                ViewResultRow last = rows.get(rows.size() - 1);
                pageStartKey = last.getKey();
                pageStartDocId = last.getId();
                continue;
            }
            ViewResultRow last = rows.get(rows.size() - 1);
            pageStartKey = last.getKey();
            pageStartDocId = last.getId();
        }
    }

    List<LineageJournalRowV2> findV2ByRepositoryAndSequenceRange(String repositoryId,
            long fromSequence, int limit) {
        support.ensureDatabase();
        try {
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("v2_by_repository_and_sequence")
                            // Strictly-after AT THE QUERY (F1): filtering the cursor row out
                            // AFTER the limit shrinks a full page to batchSize-1, which the
                            // merge-window arithmetic reads as coverage-to-infinity and can
                            // skip an unreturned v2 row. Same pattern as the v1 method.
                            .startKey(List.of(repositoryId, Math.addExact(fromSequence, 1)))
                            .endKey(List.of(repositoryId, new HashMap<>()))
                            .includeDocs(true)
                            .reduce(false)
                            .limit((long) limit)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException(
                        "v2_by_repository_and_sequence returned no result");
            }
            List<LineageJournalRowV2> rows = new ArrayList<>();
            for (ViewResultRow viewRow : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = viewRow.getDoc();
                if (doc == null) {
                    throw new IllegalStateException("view row without a document");
                }
                Map<String, Object> props = new HashMap<>();
                if (doc.getId() != null) props.put("_id", doc.getId());
                if (doc.getRev() != null) props.put("_rev", doc.getRev());
                if (doc.getProperties() != null) props.putAll(doc.getProperties());
                rows.add(support.decodeV2Strict(props));
            }
            return rows;
        } catch (LineageSequencingStore.SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException(
                    "v2_by_repository_and_sequence query failed for '" + repositoryId + "'", e);
        }
    }

    /**
     * The §8-b VERIFYING gauges (F8): count via the view's reduce, oldest verifyingSinceMs via
     * the first ascending row. Diagnostic surface (admin GET) — never a drain input.
     */
    public Map<String, Object> verifyingStats(String target) {
        support.ensureDatabase();
        try {
            ViewResult countResult = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("v2_verifying_by_since")
                            .startKey(List.of(target))
                            .endKey(List.of(target, new HashMap<>()))
                            .reduce(true)
                            .build())
                    .execute().getResult();
            long count = 0;
            if (countResult != null && countResult.getRows() != null
                    && !countResult.getRows().isEmpty()
                    && countResult.getRows().get(0).getValue() instanceof Number n) {
                count = LineageStoreDecoding.exactLong(n, "verifying count");
            }
            Long oldestSinceMs = null;
            if (count > 0) {
                ViewResult oldest = support.client().getClient().postView(
                        new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                                .db(support.client().getDatabaseName())
                                .ddoc(support.designDoc())
                                .view("v2_verifying_by_since")
                                .startKey(List.of(target))
                                .endKey(List.of(target, new HashMap<>()))
                                .reduce(false)
                                .limit(1L)
                                .build())
                        .execute().getResult();
                if (oldest != null && oldest.getRows() != null && !oldest.getRows().isEmpty()
                        && oldest.getRows().get(0).getKey() instanceof List<?> key
                        && key.size() == 2 && key.get(1) instanceof Number since) {
                    oldestSinceMs = LineageStoreDecoding.exactLong(since, "oldest verifyingSinceMs");
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("oldestSinceMs", oldestSinceMs);
            return out;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException("verifying stats query failed for '"
                    + target + "'", e);
        }
    }

    LineageJournalRowV2 findV2ByRecordId(String recordId) {
        support.ensureDatabase();
        Map<String, Object> raw = support.readV2RawStrict(recordId);
        if (raw == null) {
            return null;
        }
        return support.decodeV2Strict(raw);
    }

    List<String> findV2NonTerminalRepositoryIds(String target) {
        support.ensureDatabase();
        try {
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("v2_non_terminal_by_target_repo")
                            .startKey(List.of(target))
                            .endKey(List.of(target, new HashMap<>()))
                            .reduce(true)
                            .group(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException(
                        "v2_non_terminal_by_target_repo returned no result");
            }
            List<String> repos = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                Object key = row.getKey();
                if (key instanceof List<?> parts && parts.size() == 2
                        && parts.get(1) instanceof String repo) {
                    repos.add(repo);
                }
            }
            return repos;
        } catch (LineageSequencingStore.SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException(
                    "v2_non_terminal_by_target_repo query failed for '" + target + "'", e);
        }
    }


    // ==================================================================
    // LineageV2ReplayStore — §8-d (D-rest-3). Deployed dual and inert like the rest of the
    // D-rest surface: nothing calls these in production until activation.
    // ==================================================================


    List<LineageJournalRow> findV1ByRepositoryAndSequenceRangeStrict(
            String repositoryId, long fromSequence, int limit) {
        support.ensureDatabase();
        try {
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("by_repository_and_sequence")
                            .startKey(List.of(repositoryId, Math.addExact(fromSequence, 1)))
                            .endKey(List.of(repositoryId, new HashMap<>()))
                            .includeDocs(true)
                            .reduce(false)
                            .limit((long) limit)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                // Empty is a result with zero rows; the ABSENCE of a result is an abnormal
                // answer — the merge window must halt, never read it as full coverage.
                throw new IllegalStateException(
                        "by_repository_and_sequence returned no result");
            }
            List<LineageJournalRow> rows = new ArrayList<>();
            for (ViewResultRow viewRow : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = viewRow.getDoc();
                if (doc == null) {
                    throw new IllegalStateException("view row without a document");
                }
                Map<String, Object> props = new HashMap<>();
                if (doc.getId() != null) props.put("_id", doc.getId());
                if (doc.getRev() != null) props.put("_rev", doc.getRev());
                if (doc.getProperties() != null) props.putAll(doc.getProperties());
                rows.add(LineageEventCodec.decodeRow(props));
            }
            return rows;
        } catch (LineageSequencingStore.SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException(
                    "strict v1 merge fetch failed for '" + repositoryId + "'", e);
        }
    }

    /** F4: the unacked gauge — the recovery view's _count reduce, for diagnostics. */
    public long countUnackedReplayRequests() {
        support.ensureDatabase();
        try {
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("v2_replay_requests_unacked")
                            .reduce(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
                return 0;
            }
            Object value = result.getRows().get(0).getValue();
            return value instanceof Number n ? LineageStoreDecoding.exactLong(n, "unacked replay count") : 0;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException("unacked replay count query failed", e);
        }
    }

    List<String> findV2SequencedRepositoryIds(String target) {
        support.ensureDatabase();
        try {
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("v2_sequenced_repositories")
                            .startKey(List.of(target))
                            .endKey(List.of(target, new HashMap<>()))
                            .reduce(true)
                            .group(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException("v2_sequenced_repositories returned no result");
            }
            List<String> repos = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                if (row.getKey() instanceof List<?> parts && parts.size() == 2
                        && parts.get(1) instanceof String repo) {
                    repos.add(repo);
                }
            }
            return repos;
        } catch (LineageSequencingStore.SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException(
                    "v2_sequenced_repositories query failed for '" + target + "'", e);
        }
    }
}
