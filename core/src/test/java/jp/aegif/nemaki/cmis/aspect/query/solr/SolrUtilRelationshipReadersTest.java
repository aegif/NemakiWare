package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Index-side pin for the relationship {@code readers} contract
 * ({@link SolrUtil#unionReaders}): a relationship carries the UNION of its
 * source's and target's readers (reproducing {@code read(source) OR
 * read(target)}), and a relationship with neither endpoint resolvable is stamped
 * with an EMPTY reader set (fail-closed — the query-side fq excludes it for every
 * non-admin caller).
 */
public class SolrUtilRelationshipReadersTest {

    @Test
    public void unionOfSourceAndTargetReadersDeduped() {
        List<String> source = Arrays.asList("user:bedroom:alice", "group:bedroom:g1");
        List<String> target = Arrays.asList("user:bedroom:bob", "group:bedroom:g1");
        List<String> union = SolrUtil.unionReaders(source, target);
        assertTrue(union.contains("user:bedroom:alice"));
        assertTrue(union.contains("user:bedroom:bob"));
        assertTrue(union.contains("group:bedroom:g1"));
        assertEquals(3, union.size(), "shared tokens must be deduped, not duplicated");
    }

    @Test
    public void sourceOnly() {
        List<String> union = SolrUtil.unionReaders(Arrays.asList("user:bedroom:alice"), null);
        assertEquals(List.of("user:bedroom:alice"), union);
    }

    @Test
    public void targetOnly() {
        List<String> union = SolrUtil.unionReaders(null, Arrays.asList("user:bedroom:bob"));
        assertEquals(List.of("user:bedroom:bob"), union);
    }

    @Test
    public void bothMissingIsEmptyFailClosed() {
        assertTrue(SolrUtil.unionReaders(null, null).isEmpty(),
                "a dangling relationship (both endpoints unresolvable) must stamp NO readers");
    }

    // ── ACL-in-Solr generation fence (#1): parse the CouchDB _rev leading integer ──

    @Test
    public void parseRevGenerationExtractsLeadingInteger() {
        assertEquals(3L, SolrUtil.parseRevGeneration("3-abc123"));
        assertEquals(10L, SolrUtil.parseRevGeneration("10-deadbeef"));
        assertEquals(1L, SolrUtil.parseRevGeneration("1-x"));
    }

    @Test
    public void parseRevGenerationIsMonotonicAcrossWrites() {
        // The whole point of the fence: a later CouchDB write has a strictly greater
        // leading generation, so the reconcile can compare "mine < indexed" and skip.
        assertTrue(SolrUtil.parseRevGeneration("7-a") > SolrUtil.parseRevGeneration("6-a"),
                "a newer _rev must parse to a strictly greater generation");
    }

    @Test
    public void parseRevGenerationReturnsZeroWhenUnusable() {
        // 0 disables the fence (never skips) — fail-open to a write, which the
        // query-side live gate re-checks anyway.
        assertEquals(0L, SolrUtil.parseRevGeneration(null));
        assertEquals(0L, SolrUtil.parseRevGeneration(""));
        assertEquals(0L, SolrUtil.parseRevGeneration("abc"));
        assertEquals(0L, SolrUtil.parseRevGeneration("-5"));
        assertEquals(0L, SolrUtil.parseRevGeneration("0-zero"));
    }
}
