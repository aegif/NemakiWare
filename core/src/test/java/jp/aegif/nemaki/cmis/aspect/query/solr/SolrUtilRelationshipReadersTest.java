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
}
