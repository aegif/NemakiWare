package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.UserItem;

/**
 * Regression pins for the ACL-in-Solr wiring:
 * {@link SolrQueryProcessor#aclFilterQueries} (query-side filter construction)
 * and {@link SolrUtil#needsReadersStamp} (index-side stamping scope).
 *
 * These pin the two behavioral contracts a live-only verification cannot:
 * <ul>
 *   <li>non-admin queries are restricted to the caller's reader tokens, with
 *       relationships EXEMPTED (they store no ACL — read permission derives
 *       from the source object and is enforced by the in-memory filter); an
 *       admin gets no readers restriction at all;</li>
 *   <li>every queryable content INCLUDING principal items is stamped with
 *       readers (user/group items sit under /.system with a default
 *       GROUP_EVERYONE:read inherited ACL and were visible to non-admins
 *       before ACL-in-Solr), while relationships are NOT stamped.</li>
 * </ul>
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
    public void nonAdminGetsReadersFqWithRelationshipExemption() {
        List<String> fqs = SolrQueryProcessor.aclFilterQueries(false, READERS_FQ);
        assertEquals(2, fqs.size());
        assertEquals("(" + READERS_FQ + ") OR basetype:\"cmis:relationship\"", fqs.get(0),
                "non-admin must be restricted to their reader tokens, with relationships "
                        + "exempted (no stored ACL; source-object check happens in memory)");
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

    // ── Index side: needsReadersStamp ──

    @Test
    public void documentsFoldersAndItemsAreStamped() {
        assertTrue(SolrUtil.needsReadersStamp(new Document()));
        assertTrue(SolrUtil.needsReadersStamp(new Folder()));
        assertTrue(SolrUtil.needsReadersStamp(new Item()));
    }

    @Test
    public void principalItemsAreStamped() {
        // Regression: an earlier revision skipped user/group items, which made
        // them vanish from every non-admin CMIS query — they live under /.system
        // with a default GROUP_EVERYONE:read inherited ACL and were readable
        // through the in-memory filter before ACL-in-Solr.
        assertTrue(SolrUtil.needsReadersStamp(new UserItem()),
                "user items must carry readers (they are non-admin-visible content)");
        assertTrue(SolrUtil.needsReadersStamp(new GroupItem()),
                "group items must carry readers (they are non-admin-visible content)");
    }

    @Test
    public void relationshipsAreNotStamped() {
        // Relationships store no ACL; expandToReaders would fail closed to an
        // admin-only token set, hiding them from every non-admin query. They are
        // exempted from the readers fq and authorized in memory instead.
        assertFalse(SolrUtil.needsReadersStamp(new Relationship()));
    }
}
