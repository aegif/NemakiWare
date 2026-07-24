package jp.aegif.nemaki.epoch;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.ConflictException;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Repository-wide, strictly-monotonic ACL-epoch counter (design
 * {@code docs/design/acl-epoch-fencing.md} §2.1 / §8 — SIGNED OFF 2026-07-24,
 * increment 1 of the staged implementation).
 *
 * <p>One counter document per repository lives in {@code nemaki_conf} at the
 * deterministic id {@code acl-epoch-counter::{repo}} as {@code {type:"aclEpochCounter",
 * value:<long>}}. {@link #allocate(String)} reads the value and writes {@code value+1}
 * under a CouchDB {@code _rev} compare-and-swap, retrying on conflict; a FAILED CAS
 * consumes nothing (no value is persisted), so allocation is gap-free except when an
 * already-allocated epoch's finalization is later abandoned (harmless — only strict
 * monotonicity matters). The counter is the sole persisted high-watermark; the
 * invariant is {@code value >= every aclSourceEpoch in the repository}.
 *
 * <p><b>Fail-closed by design (§8):</b>
 * <ul>
 *   <li>A MISSING counter while epoch-bearing Content may exist is NEVER lazily
 *       recreated (that could roll the high-watermark back) — {@link #allocate} and
 *       {@link #currentHighWatermark} throw. {@code Patch_AclEpochCounter} seeds the
 *       counter (value 0 → first allocate returns 1, the fresh-repository baseline).</li>
 *   <li>A negative / non-numeric stored value is treated as corruption → throw.</li>
 *   <li>{@code Long.MAX_VALUE} overflow is explicitly rejected → throw.</li>
 * </ul>
 *
 * <p><b>Increment-1 scope / fail-closed staging:</b> this is a STANDALONE service. It
 * is wired as a bean but NOT yet called from any ACL write path — no writer allocates
 * an epoch until the later ACL-UPDATE increment. Shipping it now changes no ACL
 * behaviour; it only makes the counter available (and lets {@code Patch_AclEpochCounter}
 * seed it) for the subsequent increments.
 */
public class AclEpochCounterService {

    private static final Logger logger = LoggerFactory.getLogger(AclEpochCounterService.class);

    /** Counter document type in {@code nemaki_conf}. */
    public static final String DOC_TYPE = "aclEpochCounter";
    /** Deterministic id prefix; the full id is {@code acl-epoch-counter::{repo}}. */
    public static final String ID_PREFIX = "acl-epoch-counter::";
    /** Seed value written by the patch; the first {@link #allocate} returns 1. */
    public static final long SEED_VALUE = 0L;

    private static final int CAS_RETRIES = 8;

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    /** The deterministic {@code nemaki_conf} document id for a repository's counter. */
    public static String counterDocId(String repositoryId) {
        return ID_PREFIX + repositoryId;
    }

    /**
     * Overflow-/corruption-guarded successor of the current counter value (pure, so it
     * is unit-testable without CouchDB).
     *
     * @throws IllegalStateException on {@code Long.MAX_VALUE} (overflow) or a negative
     *         stored value (corruption)
     */
    static long nextValue(long current) {
        if (current < 0) {
            throw new IllegalStateException("ACL epoch counter corrupt (negative value " + current + ")");
        }
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("ACL epoch counter overflow (reached Long.MAX_VALUE)");
        }
        return current + 1;
    }

    /**
     * Allocate the next epoch for {@code repositoryId}: read the counter, CAS-write
     * {@code value+1}, retrying on a lost CAS. NEVER lazily creates a missing counter.
     *
     * @return the newly-allocated, strictly-monotonic epoch
     * @throws IllegalStateException if the counter is missing (fail-closed), the stored
     *         value is corrupt, the counter overflowed, or the CAS could not converge
     */
    public long allocate(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId is required to allocate an ACL epoch");
        }
        String docId = counterDocId(repositoryId);
        for (int attempt = 0; attempt < CAS_RETRIES; attempt++) {
            Counter c = readCounter(docId);
            if (c == null) {
                // Fail-closed: never recreate a missing counter — a lazily-recreated
                // counter would restart at the seed and roll the high-watermark back
                // below already-issued epochs. Recovery is an explicit operator step.
                throw new IllegalStateException("ACL epoch counter missing for repository '"
                        + repositoryId + "' — refusing to lazily recreate (would roll back the "
                        + "high-watermark). Run Patch_AclEpochCounter / the recovery procedure.");
            }
            long next = nextValue(c.value); // throws on overflow / corruption
            if (casWrite(docId, next, c.rev) != null) {
                return next;
            }
            // Lost CAS (another allocator won) — re-read and retry.
        }
        throw new IllegalStateException("ACL epoch allocation for '" + repositoryId
                + "' did not converge after " + CAS_RETRIES + " CAS attempts");
    }

    /**
     * Read the current high-watermark WITHOUT incrementing. Fail-closed if the counter
     * is missing or corrupt.
     */
    public long currentHighWatermark(String repositoryId) {
        Counter c = readCounter(counterDocId(repositoryId));
        if (c == null) {
            throw new IllegalStateException("ACL epoch counter missing for repository '"
                    + repositoryId + "'");
        }
        if (c.value < 0) {
            throw new IllegalStateException("ACL epoch counter corrupt (negative) for repository '"
                    + repositoryId + "'");
        }
        return c.value;
    }

    // ── CouchDB primitives ─────────────────────────────────────────

    /** Immutable (value, rev) snapshot for one CAS attempt. */
    private static final class Counter {
        final long value;
        final String rev;
        Counter(long value, String rev) { this.value = value; this.rev = rev; }
    }

    private Counter readCounter(String docId) {
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            Document doc = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(db).docId(docId).build()).execute().getResult();
            if (doc == null) {
                return null;
            }
            Object v = doc.getProperties() != null ? doc.getProperties().get("value") : null;
            if (!(v instanceof Number)) {
                throw new IllegalStateException("ACL epoch counter '" + docId
                        + "' has a missing / non-numeric value: " + v);
            }
            return new Counter(((Number) v).longValue(), doc.getRev());
        } catch (NotFoundException e) {
            return null;
        }
    }

    /** CAS-write {@code value} at {@code docId} with the captured {@code rev}. Returns the new rev, or null on 409. */
    private String casWrite(String docId, long value, String rev) {
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", DOC_TYPE);
        props.put("value", value);

        Document doc = new Document();
        doc.setId(docId);
        if (rev != null) {
            doc.setRev(rev);
        }
        doc.setProperties(props);
        try {
            var result = cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(db).docId(docId).document(doc).build()).execute().getResult();
            return (result != null && result.isOk()) ? result.getRev() : null;
        } catch (ConflictException e) {
            return null; // CAS lost — caller re-reads and retries
        }
    }

    private CloudantClientWrapper getConfClient() {
        if (connectorPool == null) {
            throw new IllegalStateException("connectorPool not wired on AclEpochCounterService");
        }
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            throw new IllegalStateException("nemaki_conf database client not available");
        }
        return client;
    }
}
