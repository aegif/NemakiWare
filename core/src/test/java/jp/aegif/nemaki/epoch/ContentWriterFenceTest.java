package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.epoch.ContentWriterFence.AclGroupOutcome;
import jp.aegif.nemaki.epoch.ContentWriterFence.Decision;

/**
 * The CONTENT axis' fence (design §4.4 + §8.1, wiring gate 3).
 *
 * <p>Two properties are pinned here, and the second is the one that bites in production:
 * <ol>
 *   <li>generations are compared ONLY within one incarnation, so a restore is not refused for ever;</li>
 *   <li>a content write PRESERVES the whole ACL group — all THREE fields — rather than re-emitting
 *       its own opinion of it.</li>
 * </ol>
 */
public class ContentWriterFenceTest {

    private static final String INC_A = "11111111-1111-4111-8111-111111111111";
    private static final String INC_B = "22222222-2222-4222-8222-222222222222";

    private static SolrDocument stored(String incarnation, Long generation, Object... aclGroup) {
        SolrDocument d = new SolrDocument();
        if (incarnation != null) d.setField(ContentIncarnation.SOLR_FIELD, incarnation);
        if (generation != null) d.setField(ContentIncarnation.SOLR_GENERATION_FIELD, generation);
        for (int i = 0; i < aclGroup.length; i += 2) {
            d.setField((String) aclGroup[i], aclGroup[i + 1]);
        }
        return d;
    }

    // ── decide ─────────────────────────────────────────────────────

    @Test
    public void aNewerGenerationInTheSAMEIncarnationIsSkipped() {
        assertEquals(Decision.SKIP_STALE, ContentWriterFence.decide(stored(INC_A, 50L), INC_A, 7L));
    }

    @Test
    public void anOlderGenerationInTheSAMEIncarnationIsWritten() {
        assertEquals(Decision.WRITE_PRESERVING_ACL_GROUP,
                ContentWriterFence.decide(stored(INC_A, 3L), INC_A, 7L));
    }

    /**
     * THE RESTORE CASE. A restore reuses the id but restarts `_rev` at 1. Without the incarnation
     * this is "generation 1 < stored 50" and the restored document is refused FOR EVER; with it, a
     * different incarnation means a new lifetime and the write is authoritative.
     */
    @Test
    public void aDIFFERENTIncarnationIsAuthoritative_evenAtGenerationOne() {
        assertEquals(Decision.WRITE_PRESERVING_ACL_GROUP,
                ContentWriterFence.decide(stored(INC_A, 50L), INC_B, 1L));
    }

    /** §8.1: a stored incarnation that is absent (or malformed) is a MISMATCH, never a comparison. */
    @Test
    public void anABSENTStoredIncarnationIsTreatedAsAMismatch() {
        assertEquals(Decision.WRITE_PRESERVING_ACL_GROUP,
                ContentWriterFence.decide(stored(null, 50L), INC_A, 1L));
        assertEquals(Decision.WRITE_PRESERVING_ACL_GROUP,
                ContentWriterFence.decide(stored(null, 50L), INC_A, 1L));
    }

    /** §8.1: an incoming incarnation the writer could not establish means DO NOT STAMP. */
    @Test
    public void anABSENTIncomingIncarnationFailsClosed() {
        assertEquals(Decision.FAIL_CLOSED, ContentWriterFence.decide(stored(INC_A, 1L), null, 7L));
        assertEquals(Decision.FAIL_CLOSED, ContentWriterFence.decide(stored(INC_A, 1L), "  ", 7L));
    }

    @Test
    public void anUnindexedObjectIsWritten() {
        assertEquals(Decision.WRITE_PRESERVING_ACL_GROUP, ContentWriterFence.decide(null, INC_A, 1L));
    }

    // ── preserve ───────────────────────────────────────────────────

    /**
     * ALL THREE fields move together. Preserving only `readers` while the content writer stamps a
     * fresh `acl_index_generation` yields "old readers, new generation", and `updateReadersFenced` —
     * which still reads that field — then SKIPS FOR EVER, freezing the stale readers with the very
     * mechanism meant to protect them.
     */
    @Test
    public void preserveCarriesALLTHREEAclGroupFields() {
        SolrDocument s = stored(INC_A, 5L,
                "readers", List.of("user:r:alice", "group:r:g1"),
                "effective_acl_epoch", 42L,
                "acl_index_generation", 9L);
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        rebuilt.put("readers", List.of("user:r:STALE"));
        rebuilt.put("acl_index_generation", 99L);   // what the content writer just stamped

        assertEquals(AclGroupOutcome.PRESERVED, ContentWriterFence.preserveAclGroup(s, rebuilt));

        assertEquals(List.of("user:r:alice", "group:r:g1"), rebuilt.get("readers"));
        assertEquals(42L, rebuilt.get("effective_acl_epoch"));
        assertEquals(9L, rebuilt.get("acl_index_generation"),
                "the generation must come back too, or updateReadersFenced skips for ever");
    }

    /** An EXISTING document with no ACL group: the content writer's expansion is not an answer. */
    @Test
    public void anExistingDocumentWithoutAnAclGroupIsHandedToReconciliation() {
        SolrDocument s = stored(INC_A, 5L);
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        rebuilt.put("readers", List.of("user:r:computed-by-the-content-writer"));

        assertEquals(AclGroupOutcome.MISSING_ON_EXISTING, ContentWriterFence.preserveAclGroup(s, rebuilt));
        assertFalse(rebuilt.containsKey("readers"), "the content writer's expansion must be dropped");
    }

    @Test
    public void anUnindexedObjectKeepsTheWritersOwnValues() {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        rebuilt.put("readers", List.of("user:r:bootstrap"));

        assertEquals(AclGroupOutcome.BOOTSTRAP_NOT_INDEXED, ContentWriterFence.preserveAclGroup(null, rebuilt));
        assertEquals(List.of("user:r:bootstrap"), rebuilt.get("readers"), "first index: nothing to preserve");
    }

    // ── the identity itself ────────────────────────────────────────

    @Test
    public void aPresentButMalformedIncarnationIsAnAnomaly_notTreatedAsAbsent() {
        // Treating it as absent would MINT A SECOND identity for a Content that already has a
        // (damaged) one — two lifetimes for one object.
        assertThrows(AclEpochAnomalyException.class,
                () -> ContentIncarnation.read("d", Map.of(ContentIncarnation.FIELD, "not-a-uuid")));
        assertThrows(AclEpochAnomalyException.class,
                () -> ContentIncarnation.read("d", Map.of(ContentIncarnation.FIELD, 42)));
        assertNull(ContentIncarnation.read("d", Map.of()), "absent is a legitimate pre-migration state");
    }

    @Test
    public void mintIsAUuidAndDistinct() {
        String a = ContentIncarnation.mint();
        assertTrue(a.matches("[0-9a-f-]{36}"), a);
        assertFalse(a.equals(ContentIncarnation.mint()));
    }
}
