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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INTERNAL_ONLY evidence stays out of the search index by default (plan §5, disclosure §6.2).
 *
 * <p>Before the filter, every aspect value landed unfiltered in {@code dynamic.property.*}
 * ({@code stored=true}), so the default deployment answered
 * {@code WHERE nemaki:chatParticipants LIKE '%name%'} — finding people by name. The filter is
 * the third door driven by the SAME declaration the two catalog doors read
 * ({@code CaptureEvidenceField.internalOnlyCmisPropertyIds()}): one table, three doors, no
 * hand-curated copies. What it does NOT do — and the javadoc says so — is mask reads: whoever
 * can read the object still sees the values.
 */
class SolrInternalOnlyEvidenceIndexingTest {

    private static boolean filtered(String propertyKey) throws Exception {
        Method m = SolrUtil.class.getDeclaredMethod("isInternalOnlyEvidence", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, propertyKey);
    }

    @Test
    @DisplayName("the six INTERNAL_ONLY property ids are filtered; ordinary ids are not")
    void theDeclarationDrivesTheFilter() throws Exception {
        for (String id : CaptureEvidenceField.internalOnlyCmisPropertyIds()) {
            assertTrue(filtered(id), id + " is declared INTERNAL_ONLY but the index filter "
                    + "does not know it — the third door drifted from the declaration");
        }
        assertTrue(filtered("nemaki:chatParticipants"),
                "the concrete field the plan names must be covered");
        assertFalse(filtered("nemaki:comment"),
                "an ordinary aspect property must keep being searchable");
        assertFalse(filtered("nemaki:sourceSystem"),
                "EXTERNAL_OK evidence must keep being searchable");
        assertFalse(filtered(null), "null key must not trip the filter");
    }

    @Test
    @DisplayName("at indexing, participants stay OUT of the document; channel id stays in")
    void indexingFiltersTheDeclaredFields() throws Exception {
        // Drives the REAL createSolrDocument: the helper tests above cannot see a regression at
        // the loop (a removed `continue` leaves them green).
        SolrUtil util = new SolrUtil();
        jp.aegif.nemaki.model.Aspect chat = new jp.aegif.nemaki.model.Aspect();
        chat.setName("nemaki:chatContextMetadata");
        chat.setProperties(new java.util.ArrayList<>(java.util.List.of(
                new jp.aegif.nemaki.model.Property("nemaki:chatParticipants", "otsuka,ishii"),
                new jp.aegif.nemaki.model.Property("nemaki:chatChannelId", "C1"))));
        jp.aegif.nemaki.model.Document content = new jp.aegif.nemaki.model.Document();
        content.setId("obj-1");
        content.setName("doc");
        content.setType("cmis:document");
        content.setObjectType("cmis:document");
        content.setAspects(new java.util.ArrayList<>(java.util.List.of(chat)));

        Method m = SolrUtil.class.getDeclaredMethod("createSolrDocument",
                String.class, jp.aegif.nemaki.model.Content.class, boolean.class);
        m.setAccessible(true);
        org.apache.solr.common.SolrInputDocument doc =
                (org.apache.solr.common.SolrInputDocument) m.invoke(util, "bedroom", content,
                        false);

        assertTrue(doc.getField("dynamic.property.nemaki:chatParticipants") == null,
                "participants reached the index — people are findable by name again");
        assertTrue(doc.getField("dynamic.property.nemaki:chatChannelId") != null,
                "an EXTERNAL_OK evidence field must still be indexed — over-stripping kills "
                        + "legitimate search");
    }

    @Test
    @DisplayName("the config default fails toward LESS disclosure")
    void defaultIsNotIndexing() throws Exception {
        // A fresh SolrUtil with no PropertyManager wired: indexInternalOnlyEvidence() must be
        // false — an unreadable or absent config never fails toward disclosure.
        SolrUtil util = new SolrUtil();
        Method m = SolrUtil.class.getDeclaredMethod("indexInternalOnlyEvidence");
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(util),
                "with no config at all, INTERNAL_ONLY evidence was indexed — the default "
                        + "leaked toward disclosure");
    }
}
