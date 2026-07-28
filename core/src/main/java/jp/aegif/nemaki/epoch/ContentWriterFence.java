package jp.aegif.nemaki.epoch;

import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrDocument;

/**
 * The CONTENT axis' fence (design §4.4 + §8.1, wiring gate 3).
 *
 * <p>The content writer rebuilds a WHOLE Solr document — name, path, body, extracted text — and in
 * doing so it also re-emits {@code readers}. That is the clobber this fence exists to stop: a slow
 * body re-extraction finishing after a fresh {@code applyAcl} would otherwise overwrite the new ACL
 * group with the one it computed minutes ago, and the fenced ACL writer's careful CAS would be
 * undone by a write that was never about ACLs at all.
 *
 * <p><b>The rule is PRESERVE, not recompute.</b> When Solr already holds an ACL group, the content
 * writer copies it back BYTE-IDENTICALLY rather than recomputing it — a recompute would be a second
 * ACL implementation racing the first, which is the defect class this whole design keeps closing.
 *
 * <h3>Why generation alone is not enough</h3>
 * {@code content_generation} is the Content's own {@code _rev} generation, and numbers only order
 * within one lifetime. A restore reuses the {@code _id} but restarts {@code _rev} at 1, so the
 * restored write looks "generation 1 &lt; stored 50" and would be refused for ever.
 * {@link ContentIncarnation} makes the comparison legal: generations are compared ONLY within the
 * same incarnation; a DIFFERENT one means "this is a new lifetime — recompute and CAS
 * authoritatively", never "skip as older".
 *
 * <h3>Fail-closed, per §8.1</h3>
 * <ul>
 *   <li><b>stored</b> incarnation absent while the Solr document exists → treated as a MISMATCH, so
 *       the write proceeds authoritatively. Never a generation comparison, which would be comparing
 *       numbers across unknown lifetimes.</li>
 *   <li><b>incoming</b> incarnation absent → the writer never established an authoritative identity,
 *       so it must not stamp at all. {@link Decision#FAIL_CLOSED}.</li>
 * </ul>
 * The third case — the authoritative Content being unreadable — is resolved before this class is
 * reached, by {@link ContentIncarnation#resolve} (NOT_FOUND is the caller's purge path; a read error
 * throws).
 */
public final class ContentWriterFence {

    private ContentWriterFence() {}

    /** What the content writer should do with its rebuilt document. */
    public enum Decision {
        /** Write it, preserving whatever ACL group Solr already holds. */
        WRITE_PRESERVING_ACL_GROUP,
        /** A strictly newer content write already landed in THIS incarnation — clean no-op. */
        SKIP_STALE,
        /** The incoming identity is not authoritative — do not stamp; retry. */
        FAIL_CLOSED
    }

    /**
     * The fence decision for one content write.
     *
     * @param stored   the Solr document as it is now, or {@code null} when the object is not indexed
     * @param incoming the incarnation this writer established authoritatively (never one it minted
     *                 for Solr only — see {@link ContentIncarnation#resolve})
     * @param myGeneration the Content's own {@code _rev} generation
     */
    public static Decision decide(SolrDocument stored, String incoming, long myGeneration) {
        if (incoming == null || incoming.isBlank()) {
            return Decision.FAIL_CLOSED;
        }
        if (stored == null) {
            return Decision.WRITE_PRESERVING_ACL_GROUP; // not indexed yet — nothing to preserve
        }
        Object storedIncarnation = stored.getFieldValue(ContentIncarnation.SOLR_FIELD);
        if (!(storedIncarnation instanceof String) || !incoming.equals(storedIncarnation)) {
            // Absent, malformed, or a DIFFERENT lifetime. Either way the stored generation is not
            // comparable, so this write is authoritative rather than potentially-stale.
            return Decision.WRITE_PRESERVING_ACL_GROUP;
        }
        // Same incarnation: the generations are comparable.
        Object gen = stored.getFieldValue(ContentIncarnation.SOLR_GENERATION_FIELD);
        long storedGeneration = (gen instanceof Number) ? ((Number) gen).longValue() : -1L;
        return storedGeneration > myGeneration ? Decision.SKIP_STALE : Decision.WRITE_PRESERVING_ACL_GROUP;
    }

    /** What {@link #preserveAclGroup} found, so the caller cannot ignore the third case. */
    public enum AclGroupOutcome {
        /** Solr held an ACL group; it was copied back byte-identically. */
        PRESERVED,
        /** Not indexed yet — the writer's own values stand (bootstrap). */
        BOOTSTRAP_NOT_INDEXED,
        /**
         * The document EXISTS but carries no ACL group. The writer's own values must NOT be stamped
         * as if authoritative — hand the object to reconciliation instead (§4.4).
         */
        MISSING_ON_EXISTING
    }

    /**
     * Copy the ACL group Solr already holds onto the rebuilt document, BYTE-IDENTICALLY.
     *
     * <p>Called after {@link #decide} returns {@link Decision#WRITE_PRESERVING_ACL_GROUP}. Whatever
     * the content writer computed for the ACL group is DISCARDED in favour of what is stored: the
     * ACL axis has its own writer, its own fence and its own CAS, and a content write must not be a
     * second opinion on it.
     *
     * <p><b>The group is TWO fields, and they move together.</b> {@code readers} and
     * {@code effective_acl_epoch}. Preserving only the readers while leaving a stale epoch beside
     * them would hand the ACL fence a value that does not describe what it sits next to — the fence
     * would then order writes against a number it did not produce.
     *
     * <p>Until increment 14 there was a THIRD field, {@code acl_index_generation}: the pre-epoch
     * ACL fence input, preserved here so that "old readers, new generation" could not freeze stale
     * readers in place with the very mechanism meant to protect them. That fence is gone (the ACL
     * axis is fenced by the epoch), so the field is gone with it.
     *
     * @return what was found; the caller MUST act on {@link AclGroupOutcome#MISSING_ON_EXISTING}
     */
    public static AclGroupOutcome preserveAclGroup(SolrDocument stored, Map<String, Object> rebuilt) {
        if (stored == null) {
            return AclGroupOutcome.BOOTSTRAP_NOT_INDEXED;
        }
        java.util.Collection<Object> storedReaders = stored.getFieldValues(AclEpochIndexWriter.FIELD_READERS);
        if (storedReaders == null || storedReaders.isEmpty()) {
            // An existing document with no ACL group: the content writer's own expansion is not an
            // authoritative answer for it (that is the ACL axis' job), so it is dropped and the
            // object is handed to reconciliation by the caller.
            rebuilt.remove(AclEpochIndexWriter.FIELD_READERS);
            return AclGroupOutcome.MISSING_ON_EXISTING;
        }
        rebuilt.put(AclEpochIndexWriter.FIELD_READERS, List.copyOf(storedReaders));
        copyIfPresent(stored, rebuilt, AclEpochIndexWriter.FIELD_EFFECTIVE_EPOCH);
        return AclGroupOutcome.PRESERVED;
    }

    private static void copyIfPresent(SolrDocument stored, Map<String, Object> rebuilt, String field) {
        Object v = stored.getFieldValue(field);
        if (v != null) {
            rebuilt.put(field, v);
        } else {
            rebuilt.remove(field);
        }
    }
}
