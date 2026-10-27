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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException;

/**
 * Two things the query path's field lookup must not answer quietly (closing batch 4 review, subagent P2-1 and P3-4).
 *
 * <p>A property definition that could not be READ is marked as such, so the query answers it as the server's failure
 * and not as «this WHERE clause cannot be translated» (the client's 400). A field the indexer never writes is refused
 * rather than queried: `cmis:contentStreamMimeType = 'text/plain'` answered 0 and `IS NULL` every document on bedroom.
 */
class AQueryFieldThatCannotBeReadIsMarkedTest {

    @Test
    @DisplayName("a property definition that could not be read is marked unreadable, not answered as a string field")
    void anUnreadableDefinitionIsMarked() {
        SolrUtil solrUtil = new SolrUtil();
        TypeService types = mock(TypeService.class);
        IllegalStateException cause = new IllegalStateException("the property definition core for 'nemaki:x' could not be"
                + " read; this is NOT a finding that the property is undefined");
        when(types.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:x")).thenThrow(cause);
        solrUtil.setTypeService(types);
        DefinitionsUnreadableException marked = assertThrows(DefinitionsUnreadableException.class,
                () -> solrUtil.getPropertyNameInSolr("bedroom", "nemaki:x"));
        assertEquals(cause, marked.getCause(), "the read that failed is not carried");
    }

    @Test
    @DisplayName("each field the property map names but the indexer never writes is refused, and an indexed one is not")
    void aFieldTheIndexDoesNotHoldIsRefused() {
        for (String unindexed : java.util.List.of("content_name", "content_mimetype", "is_checkedout", "checkedout_id",
                "checkedout_by", "allowed_child_object_type_ids")) {
            assertThrows(CmisInvalidArgumentException.class, () -> SolrUtil.queryableFieldName(unindexed),
                    unindexed + " is queried although the index does not hold it");
        }
        assertEquals("content_length", SolrUtil.queryableFieldName("content_length"));
        assertEquals("name", SolrUtil.queryableFieldName("name"));
    }
}
