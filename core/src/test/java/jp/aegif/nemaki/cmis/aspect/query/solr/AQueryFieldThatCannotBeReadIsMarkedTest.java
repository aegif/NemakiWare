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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // An indexed field is answered — asserted, not thrown through: a refusal here is the lock's own failure.
        assertEquals("content_length", assertDoesNotThrow(() -> SolrUtil.queryableFieldName("content_length")));
        assertEquals("name", assertDoesNotThrow(() -> SolrUtil.queryableFieldName("name")));
    }

    /** CMIS properties a SECONDARY type defines: indexed, when an object carries them, as dynamic.property.<id>. */
    private static final java.util.Set<String> DEFINED_BY_SECONDARY_TYPES = java.util.Set.of("cmis:contentStreamHash",
            "cmis:latestAccessibleStateId", "cmis:rm_destructionDate", "cmis:rm_expirationDate", "cmis:rm_holdIds",
            "cmis:rm_startOfRetention");

    @Test
    @DisplayName("every property a base type defines is queried under a field the indexer writes, or refused")
    void everyBasePropertyIsAWrittenFieldOrRefused() throws Exception {
        // The six fields above were found one review at a time; the next five were not in the map at all and fell to
        // the custom-property arm — `cmis:sourceId = '<a real id>'` answered 0 and `IS NULL` every relationship on
        // bedroom (closing batch 5 review, both reviewers). So the rule is checked over the whole vocabulary, against
        // what the REAL writer writes for every kind of object with every field set.
        java.util.Set<String> written = new java.util.TreeSet<>();
        for (jp.aegif.nemaki.model.Content content : java.util.List.of(new jp.aegif.nemaki.model.Document(),
                new jp.aegif.nemaki.model.Folder(), new jp.aegif.nemaki.model.Relationship(),
                new jp.aegif.nemaki.model.Item(), new jp.aegif.nemaki.model.Policy())) {
            fillEverySetter(content);
            java.lang.reflect.Method create = SolrUtil.class.getDeclaredMethod("createSolrDocument", String.class,
                    jp.aegif.nemaki.model.Content.class, boolean.class);
            create.setAccessible(true);
            // The path and the content length are written only with a ContentService to compute them from.
            jp.aegif.nemaki.businesslogic.ContentService contents = mock(jp.aegif.nemaki.businesslogic.ContentService.class);
            when(contents.calculatePath(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn("/a/path");
            jp.aegif.nemaki.model.AttachmentNode attachment = new jp.aegif.nemaki.model.AttachmentNode();
            attachment.setLength(7L);
            when(contents.getAttachmentRef(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString())).thenReturn(attachment);
            SolrUtil writer = new SolrUtil();
            java.lang.reflect.Field cache = SolrUtil.class.getDeclaredField("contentServiceCache");
            cache.setAccessible(true);
            cache.set(writer, contents);
            org.apache.solr.common.SolrInputDocument doc =
                    (org.apache.solr.common.SolrInputDocument) create.invoke(writer, "bedroom", content, false);
            written.addAll(doc.getFieldNames());
        }
        assertTrue(written.contains("dynamic.source_id") && written.contains("name"),
                "the writer was not driven far enough to show what it writes: " + written);

        SolrUtil solrUtil = new SolrUtil();
        solrUtil.setTypeService(mock(TypeService.class));
        java.util.List<String> wrong = new java.util.ArrayList<>();
        int checked = 0;
        for (java.lang.reflect.Field constant : org.apache.chemistry.opencmis.commons.PropertyIds.class.getFields()) {
            String id = (String) constant.get(null);
            if (DEFINED_BY_SECONDARY_TYPES.contains(id)) {
                continue;
            }
            checked++;
            String field = solrUtil.getPropertyNameInSolr("bedroom", id);
            boolean refused;
            try {
                SolrUtil.queryableFieldName(field);
                refused = false;
            } catch (CmisInvalidArgumentException expected) {
                refused = true;
            }
            if (!refused && !written.contains(field)) {
                wrong.add(id + " -> " + field + " (queried, and nothing writes it)");
            }
            if (refused && written.contains(field)) {
                wrong.add(id + " -> " + field + " (refused, and the writer writes it)");
            }
        }
        assertTrue(checked >= 30, "only " + checked + " CMIS properties were checked");
        assertEquals(java.util.List.of(), wrong, "the property map and the index disagree");
    }

    @Test
    @DisplayName("a property the index does not hold is not advertised as queryable in the shipped type definitions")
    void aRefusedPropertyIsNotAdvertisedQueryable() throws Exception {
        // A client that reads the capability could not foresee the 400 (closing batch 5 review, subagent P3-7).
        java.util.Properties shipped = new java.util.Properties();
        try (java.io.Reader in = java.nio.file.Files.newBufferedReader(
                java.nio.file.Path.of("src/main/webapp/WEB-INF/classes/nemakiware-property.properties"))) {
            shipped.load(in);
        }
        SolrUtil solrUtil = new SolrUtil();
        solrUtil.setTypeService(mock(TypeService.class));
        java.util.List<String> advertised = new java.util.ArrayList<>();
        int checked = 0;
        for (java.lang.reflect.Field constant : org.apache.chemistry.opencmis.commons.PropertyIds.class.getFields()) {
            String id = (String) constant.get(null);
            if (!SolrUtil.NOT_INDEXED.contains(solrUtil.getPropertyNameInSolr("bedroom", id))) {
                continue;
            }
            String value = shipped.getProperty("property." + id.substring("cmis:".length()) + ".queryable");
            if (value == null) {
                continue; // defined in TypeManagerImpl, not in this file
            }
            checked++;
            if (!"false".equals(value.trim())) {
                advertised.add(id + " queryable=" + value);
            }
        }
        assertTrue(checked >= 8, "only " + checked + " refused properties were found in the shipped file");
        assertEquals(java.util.List.of(), advertised, "a refused property is advertised as queryable");
    }

    /** Every public setter of the object (and its superclasses) called with a value of its parameter's type. */
    private static void fillEverySetter(Object target) throws Exception {
        for (java.lang.reflect.Method setter : target.getClass().getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) {
                continue;
            }
            java.lang.reflect.Type generic = setter.getGenericParameterTypes()[0];
            if (generic instanceof java.lang.reflect.ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] != String.class) {
                continue; // a list of model objects (aspects, properties, ACEs): not a field of its own
            }
            Object value = sampleOf(setter.getParameterTypes()[0], setter.getName());
            if (value != null) {
                setter.invoke(target, value);
            }
        }
    }

    private static Object sampleOf(Class<?> type, String setterName) {
        if (type == String.class) {
            return setterName.substring(3) + "-value";
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == Long.class || type == long.class) {
            return 7L;
        }
        if (type == Integer.class || type == int.class) {
            return 7;
        }
        if (type == java.util.GregorianCalendar.class || type == java.util.Calendar.class) {
            return new java.util.GregorianCalendar(2026, 0, 1);
        }
        if (type == java.util.List.class) {
            return new java.util.ArrayList<>(java.util.List.of(setterName.substring(3) + "-item"));
        }
        return null;
    }
}
