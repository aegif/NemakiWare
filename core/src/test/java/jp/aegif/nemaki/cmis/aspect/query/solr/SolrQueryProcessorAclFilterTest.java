package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regression pins for the ACL-in-Solr query-side filter construction,
 * {@link SolrQueryProcessor#aclFilterQueries}.
 *
 * Pins the behavioral contract a live-only verification cannot: a non-admin
 * query is restricted to the caller's reader tokens (relationships included —
 * they carry the union of their source/target readers, so no carve-out), an
 * admin gets no readers restriction, an unwired expander / anonymous caller
 * falls back to in-memory-only, and RAG docs are always excluded.
 */
public class SolrQueryProcessorAclFilterTest {

    private static final String READERS_FQ =
            "readers:(\"anyone:bedroom\" OR \"user:bedroom:alice\" OR \"group:bedroom:GROUP_EVERYONE\")";

    // ── Query side: aclFilterQueries ──

    @Test
    public void adminGetsNoReadersRestriction() {
        List<String> fqs = SolrQueryProcessor.aclFilterQueries(true, READERS_FQ);
        assertEquals(1, fqs.size(), "admin must get only the RAG-doc exclusion");
        assertEquals("-doc_type:[* TO *]", fqs.get(0));
    }

    @Test
    public void nonAdminGetsPlainReadersFq() {
        List<String> fqs = SolrQueryProcessor.aclFilterQueries(false, READERS_FQ);
        assertEquals(2, fqs.size());
        assertEquals(READERS_FQ, fqs.get(0),
                "non-admin must be restricted to their reader tokens (relationships carry the "
                        + "union of their source/target readers, so no carve-out is needed)");
        assertEquals("-doc_type:[* TO *]", fqs.get(1), "RAG docs must always be excluded");
    }

    @Test
    public void unwiredExpanderFallsBackToInMemoryOnly() {
        // No readers fq available (expander unwired / anonymous caller): no Solr
        // restriction — the in-memory getFiltered still enforces ACL.
        List<String> fqs = SolrQueryProcessor.aclFilterQueries(false, null);
        assertEquals(1, fqs.size());
        assertEquals("-doc_type:[* TO *]", fqs.get(0));
    }

    @Test
    public void ragDocExclusionIsAlwaysPresent() {
        assertTrue(SolrQueryProcessor.aclFilterQueries(true, null).contains("-doc_type:[* TO *]"));
        assertTrue(SolrQueryProcessor.aclFilterQueries(false, READERS_FQ).contains("-doc_type:[* TO *]"));
    }
}
