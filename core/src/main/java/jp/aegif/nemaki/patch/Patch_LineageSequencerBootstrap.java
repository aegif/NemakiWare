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
package jp.aegif.nemaki.patch;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * §8-a v2's bootstrap patch (v2.3.18 ③, D-rest-1): provisions, per repository, the sequencer
 * lease document ({@code generation=0}, free) and — only where the repository has no lineage
 * history — the sequence counter at {@code seq=0}.
 *
 * <p>The operational rules this patch encodes:
 *
 * <ul>
 *   <li>the lease is created here and <b>only</b> here — operation never creates it, and a
 *       corrupt existing lease makes this patch THROW (no PatchHistory recorded, so it re-runs
 *       next startup) rather than overwrite: overwriting would restart the generation
 *       high-watermark that fencing depends on;</li>
 *   <li>the counter is seeded {@code 0} only for a repository with no lineage events at all.
 *       History with a missing counter is a rewind in progress — seeding would violate I-2 —
 *       so the patch throws and recovery stays a deliberate management operation. (In
 *       practice v1's allocator auto-created counters wherever v1 events exist, so this arm
 *       fires only after manual deletion or partial restores.)</li>
 * </ul>
 */
public class Patch_LineageSequencerBootstrap extends AbstractNemakiPatch {

    private static final Logger log =
            LoggerFactory.getLogger(Patch_LineageSequencerBootstrap.class);

    private static final String PATCH_NAME = "LineageSequencerBootstrap-20260802";
    private static final String LINEAGE_DB = "nemaki_lineage";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // Everything this patch owns is per-repository.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        Cloudant cloudant = lineageCapableClient();
        if (cloudant == null) {
            throw new IllegalStateException("CouchDB client unavailable for " + PATCH_NAME);
        }
        ensureLineageDatabase(cloudant);
        ensureLease(cloudant, repositoryId);
        ensureCounter(cloudant, repositoryId);
    }

    private Cloudant lineageCapableClient() {
        if (patchUtil == null || patchUtil.getConnectorPool() == null) {
            return null;
        }
        try {
            CloudantClientWrapper client =
                    patchUtil.getConnectorPool().getClient(SystemConst.NEMAKI_CONF_DB);
            return client == null ? null : client.getClient();
        } catch (Exception e) {
            log.error("[patch={}] could not obtain CouchDB client: {}", PATCH_NAME,
                    e.getMessage());
            return null;
        }
    }

    private static void ensureLineageDatabase(Cloudant cloudant) {
        try {
            cloudant.getDatabaseInformation(new GetDatabaseInformationOptions.Builder()
                    .db(LINEAGE_DB).build()).execute();
        } catch (NotFoundException absent) {
            cloudant.putDatabase(new PutDatabaseOptions.Builder().db(LINEAGE_DB).build())
                    .execute();
            log.info("[patch={}] created {}", PATCH_NAME, LINEAGE_DB);
        }
    }

    /** Package-static so the IT can drive it against a live CouchDB. */
    static void ensureLease(Cloudant cloudant, String repositoryId) {
        String docId = "lineage_sequencer_lease:" + repositoryId;
        Document existing = read(cloudant, docId);
        if (existing != null) {
            Map<String, Object> props = existing.getProperties() == null
                    ? Map.of() : existing.getProperties();
            String reason = leaseCorruption(props);
            if (reason != null) {
                // Never overwrite: the generation IS the fencing high-watermark. But a lease
                // the acquire path can never free (unparseable expiry = held forever) is just
                // as operationally dead as a corrupt generation — both throw for repair.
                throw new IllegalStateException("[patch=" + PATCH_NAME + "] existing sequencer"
                        + " lease for '" + repositoryId + "' is corrupt (" + reason
                        + ") — manual repair required; refusing to reseed");
            }
            return;
        }
        Document lease = new Document();
        lease.setId(docId);
        lease.put("type", "lineage_sequencer_lease");
        lease.put("generation", 0L);
        lease.put("owner", null);
        lease.put("expiresAt", java.time.Instant.EPOCH.toString());
        try {
            cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(LINEAGE_DB).docId(docId).document(lease).build()).execute();
            log.info("[patch={}] created sequencer lease for {}", PATCH_NAME, repositoryId);
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException raced) {
            // A concurrent node bootstrapped it; validation on the next run covers it.
            log.info("[patch={}] sequencer lease for {} was created concurrently", PATCH_NAME,
                    repositoryId);
        }
    }

    /**
     * Every field the acquire path relies on must be usable: a non-negative exact-integral
     * generation, an owner that is null or a non-blank string, and — whenever an owner is
     * held or an expiry is present — a parseable ISO-8601 {@code expiresAt} (the store treats
     * an unparseable expiry as held-forever, which no acquire can ever free).
     *
     * @return null when valid, else the reason
     */
    private static String leaseCorruption(Map<String, Object> props) {
        Object generation = props.get("generation");
        try {
            if (!(generation instanceof Number n)
                    || new java.math.BigDecimal(n.toString()).longValueExact() < 0) {
                return "generation=" + generation;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return "generation=" + generation;
        }
        Object owner = props.get("owner");
        if (owner != null && (!(owner instanceof String o) || o.isBlank())) {
            return "owner=" + owner;
        }
        Object expiresAt = props.get("expiresAt");
        boolean needsExpiry = owner != null || expiresAt != null;
        if (needsExpiry) {
            if (!(expiresAt instanceof String e)) {
                return "expiresAt=" + expiresAt;
            }
            try {
                java.time.Instant.parse(e);
            } catch (RuntimeException unparseable) {
                return "expiresAt=" + expiresAt;
            }
        }
        return null;
    }

    /** Package-static so the IT can drive it against a live CouchDB. */
    static void ensureCounter(Cloudant cloudant, String repositoryId) {
        String docId = "lineage_seq:" + repositoryId;
        Document existing = read(cloudant, docId);
        if (existing != null) {
            Object seq = existing.getProperties() == null ? null
                    : existing.getProperties().get("seq");
            long value;
            try {
                if (!(seq instanceof Number n)) {
                    throw new NumberFormatException();
                }
                value = new java.math.BigDecimal(n.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                value = -1L;
            }
            if (value < 0) {
                throw new IllegalStateException("[patch=" + PATCH_NAME + "] existing sequence"
                        + " counter for '" + repositoryId + "' is corrupt (seq=" + seq
                        + ") — manual recovery per §8-a; refusing to reseed");
            }
            // A well-formed counter can still be rewound (a restored backup): validate it
            // against the combined watermark. Marking a rewound counter "patched" would
            // hand out already-used sequences at activation (I-2).
            long watermark = sequenceWatermark(cloudant, repositoryId);
            if (value < watermark) {
                throw new IllegalStateException("[patch=" + PATCH_NAME + "] sequence counter"
                        + " for '" + repositoryId + "' is at " + value + ", below the"
                        + " high-watermark " + watermark + " — rewound; manual recovery per"
                        + " §8-a (max high-watermark + 1)");
            }
            return;
        }
        if (hasLineageHistory(cloudant, repositoryId)) {
            // v2.3.18 ③: history with a missing counter is a rewind in progress. Seeding
            // here would hand out already-used sequence numbers (I-2).
            throw new IllegalStateException("[patch=" + PATCH_NAME + "] repository '"
                    + repositoryId + "' has lineage history but no sequence counter —"
                    + " manual recovery per §8-a (max high-watermark + 1); refusing to seed");
        }
        Document counter = new Document();
        counter.setId(docId);
        counter.put("type", "lineage_sequence");
        counter.put("repositoryId", repositoryId);
        counter.put("seq", 0L);
        try {
            cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(LINEAGE_DB).docId(docId).document(counter).build()).execute();
            log.info("[patch={}] seeded sequence counter 0 for fresh repository {}",
                    PATCH_NAME, repositoryId);
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException raced) {
            log.info("[patch={}] sequence counter for {} was created concurrently",
                    PATCH_NAME, repositoryId);
        }
    }

    private static boolean hasLineageHistory(Cloudant cloudant, String repositoryId) {
        Map<String, Object> selector = new HashMap<>();
        selector.put("repositoryId", repositoryId);
        Map<String, Object> typeIn = new HashMap<>();
        // Cursors count as history: a cursor-only repository (events purged, cursor kept)
        // still bounds the counter, and seeding it at zero would rewind past the cursor.
        typeIn.put("$in", java.util.List.of("lineage_event", "lineage_event_v2",
                "projection_cursor"));
        selector.put("type", typeIn);
        var result = cloudant.postFind(new PostFindOptions.Builder()
                .db(LINEAGE_DB).selector(selector).limit(1L).build()).execute().getResult();
        return result.getDocs() != null && !result.getDocs().isEmpty();
    }

    /**
     * The combined watermark via the {@code sequence_watermark} view. When the view is not
     * deployed yet: a repository WITHOUT history is definitionally at 0; with history the
     * counter cannot be validated, and an unvalidatable counter is treated exactly like a
     * corrupt one — throw, re-run after the store has provisioned its views.
     */
    private static long sequenceWatermark(Cloudant cloudant, String repositoryId) {
        try {
            var result = cloudant.postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(LINEAGE_DB).ddoc("lineage").view("sequence_watermark")
                            .keys(java.util.List.of(repositoryId)).group(true).reduce(true).build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                // No result at all is an abnormal answer — recording the patch as successful
                // without validating the counter would defeat its purpose.
                throw new IllegalStateException("sequence_watermark returned no result");
            }
            if (result.getRows().isEmpty()
                    || result.getRows().get(0).getValue() == null) {
                return 0L; // grouped reduce over zero rows: genuinely no history
            }
            Object value = result.getRows().get(0).getValue();
            if (value instanceof Map<?, ?> stats && stats.get("max") instanceof Number max) {
                return new java.math.BigDecimal(max.toString()).longValueExact();
            }
            throw new IllegalStateException("malformed sequence_watermark reduce: " + value);
        } catch (NotFoundException viewMissing) {
            if (hasLineageHistory(cloudant, repositoryId)) {
                throw new IllegalStateException("[patch=" + PATCH_NAME + "] repository '"
                        + repositoryId + "' has history but the sequence_watermark view is"
                        + " not deployed — cannot validate the counter; re-run after the"
                        + " lineage store has provisioned its views");
            }
            return 0L;
        }
    }

    private static Document read(Cloudant cloudant, String docId) {
        try {
            return cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(LINEAGE_DB).docId(docId).build()).execute().getResult();
        } catch (NotFoundException absent) {
            return null;
        }
    }
}
