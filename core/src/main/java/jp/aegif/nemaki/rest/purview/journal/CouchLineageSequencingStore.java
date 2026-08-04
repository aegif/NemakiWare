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
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

// Nested in the interface, and the owner used to see it by implementing that interface. This
// class delegates rather than implements, so the name has to be imported explicitly.
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequencerHealth;

/**
 * §8-a's fenced sequencer (D-rest-1), moved out of {@link CouchLineageJournalStore} unchanged.
 *
 * <p>The lease generation CAS, the fenced allocator's retry count, and the claim/reclaim/
 * finalize triple moved as whole units — a sequencer whose {@code _rev} read-modify-write was
 * split across a boundary would be a different sequencer.
 *
 * <p>Its only test support is a real-CouchDB IT, which is why it was extracted last.
 */
final class CouchLineageSequencingStore {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(CouchLineageSequencingStore.class);

    private final LineageStoreSupport support;
    private final LineageConfig lineageConfig;

    CouchLineageSequencingStore(LineageStoreSupport support, LineageConfig lineageConfig) {
        this.support = support;
        this.lineageConfig = lineageConfig;
    }

    private static final String SEQUENCER_LEASE_PREFIX = "lineage_sequencer_lease:";
    private static final int ALLOCATOR_CAS_RETRIES = 5;

    private static String leaseDocumentId(String repositoryId) {
        return SEQUENCER_LEASE_PREFIX + repositoryId;
    }

    java.util.Optional<LineageSequencingStore.LeaseGrant> acquireSequencerLease(String repositoryId,
            String nodeId, java.time.Duration ttl) {
        support.ensureDatabase();
        Map<String, Object> lease = support.readRawStrict(leaseDocumentId(repositoryId));
        if (lease == null) {
            // §8-a: created by the bootstrap patch only. Operation never creates it — a
            // recreated lease would restart the generation high-watermark.
            throw new LineageSequencingStore.LeaseMissingException(repositoryId);
        }
        String owner = lease.get("owner") instanceof String o && !o.isBlank() ? o : null;
        String expiresAt = lease.get("expiresAt") instanceof String e ? e : null;
        boolean free = owner == null
                || (expiresAt != null && isExpired(expiresAt));
        if (!free) {
            return java.util.Optional.empty();
        }
        long generation;
        try {
            generation = CouchLineageJournalStore.exactLong(lease.get("generation"), "lease generation");
        } catch (IllegalArgumentException malformed) {
            generation = -1L;
        }
        if (generation < 0) {
            logger.error("Sequencer lease for {} has a malformed generation — refusing to"
                    + " acquire", repositoryId);
            return java.util.Optional.empty();
        }
        long nextGeneration = Math.addExact(generation, 1);
        String token = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
        String newExpiresAt = Instant.now().plus(ttl).toString();
        lease.put("generation", nextGeneration);
        lease.put("sequencerLeaseToken", token);
        lease.put("owner", nodeId);
        lease.put("expiresAt", newExpiresAt);
        if (support.updateStrictCas(lease)) {
            Map<String, Object> committed = support.readRawStrict(leaseDocumentId(repositoryId));
            String rev = committed != null && committed.get("_rev") instanceof String r
                    ? r : "";
            return java.util.Optional.of(new LineageSequencingStore.LeaseGrant(repositoryId, nextGeneration, token,
                    nodeId, newExpiresAt, rev));
        }
        return java.util.Optional.empty();
    }

    java.util.Optional<LineageSequencingStore.LeaseGrant> renewSequencerLease(LineageSequencingStore.LeaseGrant grant,
            java.time.Duration ttl) {
        if (grant == null) {
            return java.util.Optional.empty();
        }
        Map<String, Object> lease = support.readRawStrict(leaseDocumentId(grant.repositoryId()));
        if (lease == null || !matchesGrant(lease, grant)) {
            return java.util.Optional.empty();
        }
        String newExpiresAt = Instant.now().plus(ttl).toString();
        lease.put("expiresAt", newExpiresAt);
        if (support.updateStrictCas(lease)) {
            Map<String, Object> committed = support.readRawStrict(
                    leaseDocumentId(grant.repositoryId()));
            String rev = committed != null && committed.get("_rev") instanceof String r
                    ? r : "";
            return java.util.Optional.of(new LineageSequencingStore.LeaseGrant(grant.repositoryId(),
                    grant.generation(), grant.sequencerLeaseToken(), grant.owner(),
                    newExpiresAt, rev));
        }
        return java.util.Optional.empty();
    }

    void releaseSequencerLease(LineageSequencingStore.LeaseGrant grant) {
        if (grant == null) {
            return;
        }
        try {
            Map<String, Object> lease = support.readRawStrict(leaseDocumentId(grant.repositoryId()));
            if (lease == null || !matchesGrant(lease, grant)
                    || !grant.rev().equals(lease.get("_rev"))) {
                // The frozen contract is an owner/generation/token/_rev CAS: a grant whose
                // _rev is stale (the worker renewed since) must not release the newer hold.
                return;
            }
            lease.put("owner", null);
            lease.put("expiresAt", Instant.EPOCH.toString());
            // generation and token stay: the document is the generation high-watermark and
            // is never deleted (§8-a).
            support.updateStrictCas(lease);
        } catch (RuntimeException e) {
            // Release is best-effort by design: expiry frees the lease anyway, and a release
            // failure must not mask the run's real outcome.
            logger.debug("Sequencer lease release skipped for {}: {}", grant.repositoryId(),
                    e.getMessage());
        }
    }

    java.util.Optional<LineageSequencingStore.LeaseView> readSequencerLease(String repositoryId) {
        Map<String, Object> lease = support.readRawStrict(leaseDocumentId(repositoryId));
        if (lease == null) {
            return java.util.Optional.empty();
        }
        long generation;
        try {
            generation = CouchLineageJournalStore.exactLong(lease.get("generation"), "lease generation");
        } catch (IllegalArgumentException malformed) {
            generation = -1L;
        }
        return java.util.Optional.of(new LineageSequencingStore.LeaseView(
                generation,
                lease.get("sequencerLeaseToken") instanceof String t ? t : null,
                lease.get("owner") instanceof String o && !o.isBlank() ? o : null,
                lease.get("expiresAt") instanceof String e ? e : null));
    }

    private static boolean matchesGrant(Map<String, Object> lease, LineageSequencingStore.LeaseGrant grant) {
        long generation;
        try {
            generation = CouchLineageJournalStore.exactLong(lease.get("generation"), "lease generation");
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        String token = lease.get("sequencerLeaseToken") instanceof String t ? t : null;
        String owner = lease.get("owner") instanceof String o ? o : null;
        return generation == grant.generation()
                && grant.sequencerLeaseToken().equals(token)
                && grant.owner().equals(owner);
    }

    private static boolean isExpired(String expiresAt) {
        try {
            return Instant.parse(expiresAt).isBefore(Instant.now());
        } catch (RuntimeException e) {
            // A lease whose expiry cannot be parsed must not be treated as free — that would
            // let two nodes hold it. Unparseable = held forever = a management repair case.
            return false;
        }
    }

    List<LineageJournalRowV2> findUnsequencedV2(String repositoryId, int limit) {
        return queryV2RowsInClaimOrder("v2_sequencer_backlog", repositoryId, limit);
    }

    List<LineageJournalRowV2> findSequencingV2(String repositoryId, int limit) {
        return queryV2RowsInClaimOrder("v2_sequencer_in_flight", repositoryId, limit);
    }

    private List<LineageJournalRowV2> queryV2RowsInClaimOrder(String viewName,
            String repositoryId, int limit) {
        try {
            // Raw postView, not the shared wrapper: the wrapper returns null for a missing
            // design document, and an empty backlog and a broken index must not look alike —
            // the sequencer would release FENCED_OK over an outage instead of latching.
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view(viewName)
                            .startKey(List.of(repositoryId))
                            .endKey(List.of(repositoryId, new HashMap<>(), new HashMap<>()))
                            .includeDocs(true)
                            .reduce(false)
                            .limit((long) limit)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                // postView returning no result object at all is an abnormal answer, not an
                // empty backlog — empty is a result with zero rows.
                throw new IllegalStateException("view '" + viewName + "' returned no result");
            }
            List<LineageJournalRowV2> rows = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                Map<String, Object> props = new HashMap<>();
                if (doc != null) {
                    if (doc.getId() != null) props.put("_id", doc.getId());
                    if (doc.getRev() != null) props.put("_rev", doc.getRev());
                    if (doc.getProperties() != null) props.putAll(doc.getProperties());
                }
                try {
                    if (doc == null) {
                        // include_docs promised a document; its absence is view/store
                        // inconsistency, exactly as blocking as an undecodable row.
                        throw new IllegalStateException("view row without a document");
                    }
                    rows.add(CouchLineageJournalRowV2.fromRaw(props));
                } catch (RuntimeException e) {
                    // Deterministic order is the contract: sequencing PAST a broken row would
                    // hand later occurredAt values lower positions than the row will get when
                    // repaired. Healthy rows BEFORE the barrier proceed (the queue drains up
                    // to it over successive passes); once the broken row is at the head there
                    // is no progress to report, and pretending "empty backlog / FENCED_OK"
                    // over a blocked queue is the one forbidden answer — so an empty prefix
                    // throws, which the sequencer and the backlog probes surface as STOPPED.
                    if (rows.isEmpty()) {
                        throw new LineageSequencingStore.SequencingStorageException("corrupt v2 row '"
                                + props.get("_id") + "' blocks the head of the sequencing"
                                + " queue for '" + repositoryId + "'", e);
                    }
                    logger.error("Undecodable v2 row {} halts the sequencer scan at its"
                            + " position ({} healthy rows before it proceed): {}",
                            props.get("_id"), rows.size(), e.getMessage());
                    break;
                }
            }
            return rows;
        } catch (RuntimeException e) {
            throw new LineageSequencingStore.SequencingStorageException("sequencer view '" + viewName
                    + "' query failed", e);
        }
    }

    boolean claimForSequencing(LineageJournalRowV2 row, long generation,
            String sequencerLeaseToken) {
        return casSequencingWrite(row, LineageJournalRowV2.SequencingState.UNSEQUENCED, null,
                raw -> CouchLineageJournalRowV2.applySequencing(raw, generation,
                        sequencerLeaseToken));
    }

    boolean reclaimForSequencing(LineageJournalRowV2 row, long staleGeneration,
            long generation, String sequencerLeaseToken) {
        if (staleGeneration >= generation) {
            // §8-a: reclaim only takes rows from strictly older generations. An equal
            // generation is our own in-flight row; a newer one is not ours to touch.
            return false;
        }
        return casSequencingWrite(row, LineageJournalRowV2.SequencingState.SEQUENCING,
                staleGeneration,
                raw -> CouchLineageJournalRowV2.applySequencing(raw, generation,
                        sequencerLeaseToken));
    }

    boolean finalizeSequence(LineageJournalRowV2 row, long generation,
            String sequencerLeaseToken, long sequence) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive, got " + sequence);
        }
        return casSequencingWrite(row, LineageJournalRowV2.SequencingState.SEQUENCING,
                generation, raw -> {
                    Object token = raw.get(CouchLineageJournalRowV2.FIELD_LEASE_TOKEN);
                    if (!sequencerLeaseToken.equals(token)) {
                        throw new StaleRowException();
                    }
                    CouchLineageJournalRowV2.applyFinalize(raw, sequence);
                });
    }

    /** Signals "the stored row no longer matches what the caller claimed to hold". */
    private static final class StaleRowException extends RuntimeException {
    }

    /**
     * The shared CAS shape: re-read the raw document, verify it still is what the caller
     * holds ({@code _rev}, state, and — when given — generation), apply the mutation
     * field-preservingly, and write under the verified {@code _rev}. Any mismatch or update
     * conflict is {@code false}: the world moved, the caller re-reads.
     */
    private boolean casSequencingWrite(LineageJournalRowV2 row,
            LineageJournalRowV2.SequencingState expectedState, Long expectedGeneration,
            java.util.function.Consumer<Map<String, Object>> mutation) {
        if (row == null) {
            return false;
        }
        try {
            Map<String, Object> raw = support.readRawStrict(row.documentId());
            if (raw == null) {
                return false;
            }
            if (!row.rev().equals(raw.get("_rev"))) {
                return false;
            }
            Object state = raw.get(CouchLineageJournalRowV2.FIELD_STATE);
            if (!expectedState.name().equals(state)) {
                return false;
            }
            if (expectedGeneration != null) {
                Object generation = raw.get(CouchLineageJournalRowV2.FIELD_GENERATION);
                try {
                    if (CouchLineageJournalStore.exactLong(generation, "sequencerGeneration") != expectedGeneration) {
                        return false;
                    }
                } catch (IllegalArgumentException malformed) {
                    return false;
                }
            }
            mutation.accept(raw);
            return support.updateStrictCas(raw);
        } catch (StaleRowException stale) {
            return false;
        }
        // LineageSequencingStore.SequencingStorageException propagates: an outage is not a CAS loss, and the
        // sequencer must latch on it rather than re-read forever.
    }

    long allocateSequenceFenced(String repositoryId) {
        support.ensureDatabase();
        String seqDocId = CouchLineageJournalStore.SEQ_PREFIX + repositoryId;
        for (int attempt = 0; attempt < ALLOCATOR_CAS_RETRIES; attempt++) {
            Map<String, Object> seqDoc = support.readRawStrict(seqDocId);
            if (seqDoc == null) {
                // v1's allocator would create it here with seq=1. This one never seeds (I-4):
                // a missing counter under existing history means rewound sequences.
                throw new LineageSequencingStore.SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "sequence counter for '" + repositoryId + "' is missing — bootstrap"
                                + " provisions it; the fenced allocator never seeds");
            }
            Object stored = seqDoc.get("seq");
            Number n;
            try {
                CouchLineageJournalStore.exactLong(stored, "sequence counter");
                n = (Number) stored;
            } catch (IllegalArgumentException malformed) {
                throw new LineageSequencingStore.SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "sequence counter for '" + repositoryId + "' is malformed: " + stored);
            }
            if (CouchLineageJournalStore.exactLong(n, "sequence counter") < 0) {
                throw new LineageSequencingStore.SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "sequence counter for '" + repositoryId + "' is malformed: " + stored);
            }
            long current = CouchLineageJournalStore.exactLong(n, "sequence counter");
            long watermark = sequenceHighWatermark(repositoryId);
            if (current < watermark) {
                throw new LineageSequencingStore.SequenceCounterException(SequencerHealth.COUNTER_REWOUND,
                        "sequence counter for '" + repositoryId + "' is at " + current
                                + ", below the finalized high-watermark " + watermark
                                + " — refusing to allocate (I-2); recover manually");
            }
            long next = Math.addExact(current, 1);
            seqDoc.put("seq", next);
            if (support.updateStrictCas(seqDoc)) {
                return next;
            }
            // false = an ordinary CAS loss to a concurrent allocator; re-read and retry.
        }
        throw new LineageSequencingStore.SequenceCounterException(SequencerHealth.STOPPED,
                "fenced allocator for '" + repositoryId + "' lost " + ALLOCATOR_CAS_RETRIES
                        + " consecutive CAS attempts — transient contention, retry later");
    }

    long sequenceHighWatermark(String repositoryId) {
        try {
            ViewResult result = support.client().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(support.client().getDatabaseName())
                            .ddoc(support.designDoc())
                            .view("sequence_watermark")
                            .keys(List.of(repositoryId))
                            .group(true)
                            .reduce(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException("sequence_watermark returned no result");
            }
            if (result.getRows().isEmpty()) {
                return 0L; // grouped reduce over zero rows: genuinely no history
            }
            Object value = result.getRows().get(0).getValue();
            if (value == null) {
                return 0L; // reduce over zero rows
            }
            if (value instanceof Map<?, ?> stats && stats.get("max") instanceof Number max) {
                return CouchLineageJournalStore.exactLong(max, "sequence_watermark max");
            }
            // A reduce answer that is neither absent nor _stats-shaped is a broken index,
            // not a zero watermark.
            throw new IllegalStateException("malformed sequence_watermark reduce: " + value);
        } catch (RuntimeException e) {
            // The rewind check must not silently pass on a query failure: an unsafe failure
            // stops allocation (the sequencer latches) rather than allocating blind. A
            // missing view/design document lands here too — a sequencer without its views is
            // broken infrastructure, not an empty repository.
            if (e instanceof LineageSequencingStore.SequencingStorageException storage) {
                throw storage;
            }
            throw new LineageSequencingStore.SequencingStorageException(
                    "sequence watermark query failed for '" + repositoryId + "'", e);
        }
    }
}
