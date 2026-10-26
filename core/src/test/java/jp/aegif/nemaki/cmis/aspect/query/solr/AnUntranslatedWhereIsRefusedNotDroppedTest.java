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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.server.support.query.CmisTree;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;

/**
 * A WHERE clause the server cannot translate is refused — it is not dropped and answered as
 * "every document" (R188).
 *
 * <p>Drives {@link SolrQueryProcessor#query} itself, with the real OpenCMIS parser, and records the
 * query each statement sends to Solr. Before R188 three statements here came back as successful
 * queries for something other than what was asked: an unknown property name made the parse fail,
 * the manual fallback recognised nothing, and the query went out as {@code *:*}; and a manual
 * fallback that recognised ONE predicate of two sent that one and dropped the other. Measured on
 * bedroom (2,449 documents) the same shapes returned all 2,449.
 *
 * <p>The accepted statements are the ones that must keep working: no WHERE at all is every
 * document, a WHERE the walker translates is sent as translated, and a WHERE the manual fallback
 * covers WHOLE — one predicate on a secondary-type property the parser does not know — is sent as
 * the fallback translated it. Refusing those is the same weight of defect as dropping the others.
 */
class AnUntranslatedWhereIsRefusedNotDroppedTest {

    private static final String REPO = "bedroom";

    private SolrQueryProcessor processor;
    /** Every {@code q} the processor sent to Solr, in order. */
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        DocumentTypeDefinitionImpl document = new DocumentTypeDefinitionImpl();
        document.setId("cmis:document");
        document.setLocalName("document");
        document.setQueryName("cmis:document");
        document.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        document.setIsQueryable(true);
        document.setIsFulltextIndexed(false);
        document.setIsIncludedInSupertypeQuery(true);
        Map<String, PropertyDefinition<?>> properties = new LinkedHashMap<>();
        properties.put("cmis:name", define(new PropertyStringDefinitionImpl(), "cmis:name", PropertyType.STRING,
                Cardinality.SINGLE));
        properties.put("cmis:objectId", define(new PropertyIdDefinitionImpl(), "cmis:objectId", PropertyType.ID,
                Cardinality.SINGLE));
        properties.put("cmis:objectTypeId", define(new PropertyIdDefinitionImpl(), "cmis:objectTypeId",
                PropertyType.ID, Cardinality.SINGLE));
        // A property of the type, so that a statement naming it PARSES and reaches the walker.
        properties.put("nemaki:known", define(new PropertyStringDefinitionImpl(), "nemaki:known", PropertyType.STRING,
                Cardinality.SINGLE));
        properties.put("nemaki:labels", define(new PropertyStringDefinitionImpl(), "nemaki:labels", PropertyType.STRING,
                Cardinality.MULTI));
        properties.put("cmis:secondaryObjectTypeIds", define(new PropertyIdDefinitionImpl(),
                "cmis:secondaryObjectTypeIds", PropertyType.ID, Cardinality.MULTI));
        document.setPropertyDefinitions(properties);

        // A type that does not declare cmis:secondaryObjectTypeIds: a statement about the secondary types FROM it fails
        // the strict parse, which is the one way the fallback's own IN-list reading is reached.
        DocumentTypeDefinitionImpl plain = new DocumentTypeDefinitionImpl();
        plain.setId("nemaki:plain");
        plain.setLocalName("plain");
        plain.setQueryName("nemaki:plain");
        plain.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        plain.setIsQueryable(true);
        plain.setIsFulltextIndexed(false);
        plain.setIsIncludedInSupertypeQuery(true);
        Map<String, PropertyDefinition<?>> plainProperties = new LinkedHashMap<>();
        plainProperties.put("cmis:name", properties.get("cmis:name"));
        plainProperties.put("cmis:objectId", properties.get("cmis:objectId"));
        plainProperties.put("cmis:objectTypeId", properties.get("cmis:objectTypeId"));
        plain.setPropertyDefinitions(plainProperties);

        TypeManager types = mock(TypeManager.class);
        when(types.getTypeByQueryName(REPO, "cmis:document")).thenReturn(document);
        when(types.getTypeByQueryName(REPO, "nemaki:plain")).thenReturn(plain);
        when(types.getPropertyIdForQueryName(eq(REPO), any(TypeDefinition.class), anyString()))
                .thenAnswer(call -> {
                    String queryName = call.getArgument(2);
                    return properties.containsKey(queryName) ? queryName : null;
                });
        when(types.getTypesDescendants(eq(REPO), anyString(), any(BigInteger.class), anyBoolean()))
                .thenReturn(Collections.emptyList());
        // The properties a SECONDARY TYPE defines — the only ones the manual fallback may translate. A statement
        // naming one without the JOIN fails the strict parse and reaches the fallback; a property no type defines
        // must be refused, not translated into a field nothing is indexed under (closing batch 2 review, P1).
        org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl aspect =
                new org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl();
        aspect.setId("nemaki:aspect");
        aspect.setQueryName("nemaki:aspect");
        for (String defined : List.of("nemaki:tag", "nemaki:due", "nemaki:other", "nemaki:note")) {
            when(types.findSecondaryTypeByPropertyQueryName(REPO, defined)).thenReturn(aspect);
        }

        SolrClient client = mock(SolrClient.class);
        when(client.query(any(SolrParams.class))).thenAnswer(call -> {
            SolrParams params = call.getArgument(0);
            sent.add(params.get("q"));
            QueryResponse response = mock(QueryResponse.class);
            SolrDocumentList none = new SolrDocumentList();
            none.setNumFound(0);
            when(response.getResults()).thenReturn(none);
            return response;
        });
        SolrUtil solrUtil = mock(SolrUtil.class);
        when(solrUtil.getSolrClient()).thenReturn(client);
        when(solrUtil.getPropertyNameInSolr(eq(REPO), anyString()))
                .thenAnswer(call -> ((String) call.getArgument(1)).replace(':', '_'));
        // SolrUtil.convertToString itself: the column node's children joined with a dot.
        when(solrUtil.convertToString(any())).thenAnswer(call -> {
            CmisTree node = call.getArgument(0);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                parts.add(node.getChild(i).toString());
            }
            return String.join(".", parts);
        });

        processor = new SolrQueryProcessor();
        processor.setTypeManager(types);
        processor.setSolrUtil(solrUtil);
        processor.setContentService(mock(ContentService.class));
        processor.setExceptionService(new ExceptionServiceImpl());
    }

    private static <T extends AbstractPropertyDefinition<?>> T define(T definition, String id, PropertyType type,
            Cardinality cardinality) {
        definition.setId(id);
        definition.setLocalName(id);
        definition.setQueryName(id);
        definition.setPropertyType(type);
        definition.setCardinality(cardinality);
        definition.setUpdatability(Updatability.READONLY);
        definition.setIsQueryable(true);
        definition.setIsOrderable(true);
        definition.setIsInherited(false);
        definition.setIsRequired(false);
        return definition;
    }

    private void run(String statement) {
        processor.query(null, REPO, statement, false, false, null, null, BigInteger.TEN, BigInteger.ZERO, null);
    }

    private void assertRefusedWithoutAQuery(String statement) {
        sent.clear();
        CmisInvalidArgumentException refused = assertThrows(CmisInvalidArgumentException.class, () -> run(statement),
                () -> "the WHERE clause of 「" + statement + "」 cannot be translated, and the statement was "
                        + "answered instead of refused — the queries it sent: " + sent);
        assertEquals(List.of(), sent, "「" + statement + "」 was refused (" + refused.getMessage() + ") but only "
                + "after a query went to Solr");
    }

    private String assertAnsweredWithOneQuery(String statement) {
        sent.clear();
        // A refusal here is an OVER-refusal, the same weight of defect as an answer without the conditions —
        // so it has to fail this lock's own assertion, not escape as the exception it is.
        assertDoesNotThrow(() -> run(statement), () -> "「" + statement + "」 can be translated whole and was refused "
                + "instead of answered");
        assertFalse(sent.isEmpty(), "「" + statement + "」 was answered without asking Solr anything");
        return sent.get(sent.size() - 1);
    }

    @Test
    @DisplayName("a WHERE whose property the parser does not know, and no fallback recognises, is refused")
    void aWhereNothingTranslatesIsRefused() {
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE chatChannelName = 'x'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE cmis:rm_expirationDate IS NULL");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE cmis:name LIKE '%a%' AND notAProperty IS NULL");
        // The keyword on its own line: the fallback looked for " WHERE " with a space on both sides.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document\nWHERE chatChannelName = 'x'");
    }

    @Test
    @DisplayName("a property no type defines is refused — not translated into a field nothing is indexed under (closing batch 2 review)")
    void aPropertyNoTypeDefinesIsRefused() {
        // Measured on bedroom before this: `nemaki:doesNotExist IS NULL` answered 200 with all 2,449 documents.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:doesNotExist IS NULL");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:doesNotExist <> 'x'");
        // A value that looks like prefix:name names no property: the gate reads the clause with the literals blanked.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE cmis:nmae <> 'a:b'");
    }

    @Test
    @DisplayName("a walker failure is not answered by a fallback that covers part of the WHERE")
    void aWalkerFailureIsNotAnsweredWithPartOfTheWhere() {
        // Parses (both properties belong to the type), and the walker throws on ANY over a
        // single-valued property; the fallback then recognised the equality alone.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE ANY cmis:name IN ('a') AND nemaki:known = 'x'");
    }

    @Test
    @DisplayName("a WHERE the walker answers nothing for, and the fallback does not cover, is refused")
    void aWhereTheWalkerAnswersNothingForIsRefused() {
        // `literal = ANY column`: the walker has no arm for EQ_ANY and returns null, and this went out as every
        // document. (`ANY column IN (...)` used to be here; its arm existed and was dispatched on the wrong token —
        // closing batch 2 review — so it is translated now, below.)
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE 'a' = ANY nemaki:labels");
    }

    @Test
    @DisplayName("ANY ... IN over a multi-valued property is translated by the walker — its arm was dispatched on the keyword's token")
    void anyInOverAMultiValuedPropertyIsTranslated() {
        String q = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE ANY nemaki:labels IN ('a', 'b')");
        assertTrue(q.contains("nemaki_labels"), "ANY ... IN went out without its field: " + q);
        String combined = assertAnsweredWithOneQuery(
                "SELECT * FROM cmis:document WHERE cmis:name LIKE 'a%' AND ANY nemaki:labels IN ('a')");
        assertTrue(combined.contains("nemaki_labels") && combined.contains("cmis_name"),
                "a combined WHERE with ANY ... IN went out without one of its conditions: " + combined);
    }

    @Test
    @DisplayName("a manual fallback that recognises one predicate of two is refused, not sent as that one")
    void aFallbackThatCoversPartOfTheWhereIsRefused() {
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'x' AND notAProperty IS NULL");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'x' OR nemaki:other = 'y'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE NOT (nemaki:tag = 'x')");
    }

    @Test
    @DisplayName("every pattern of the manual fallback answers only for the whole clause, never for a part of it")
    void everyFallbackPatternCoversTheWholeClauseOrNothing() {
        // One statement per pattern, each where THAT pattern — and only it — recognises a predicate
        // and the rest of the clause is something nothing translates. Each pattern used find(), so
        // each of these was answered with that one predicate.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag LIKE 'a%' AND notAProperty = 'b'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'x' AND notAProperty IS NULL");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag != 'x' AND notAProperty = 'b'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:due IS NULL AND notAProperty = 'b'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:due IS NOT NULL AND notAProperty = 'b'");
        assertRefusedWithoutAQuery(
                "SELECT * FROM cmis:document WHERE ANY cmis:secondaryObjectTypeIds IN ('a') AND notAProperty = 'b'");
        assertRefusedWithoutAQuery(
                "SELECT * FROM cmis:document WHERE cmis:secondaryObjectTypeIds = 'a' AND notAProperty = 'b'");
    }

    @Test
    @DisplayName("no WHERE at all is every document")
    void noWhereIsEveryDocument() {
        assertEquals("*:*", assertAnsweredWithOneQuery("SELECT * FROM cmis:document"));
        assertEquals("*:*", assertAnsweredWithOneQuery("SELECT * FROM cmis:document ORDER BY cmis:name"));
    }

    @Test
    @DisplayName("a WHERE the walker translates is sent as translated")
    void aTranslatedWhereIsSent() {
        String q = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE cmis:name = 'a'");
        assertNotEquals("*:*", q, "a translated WHERE went out as every document");
        assertTrue(q.contains("cmis_name"), "the translated WHERE does not name the field it asked about: " + q);
        String quoted = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE cmis:name = 'say where'");
        assertTrue(quoted.contains("cmis_name"), "a WHERE inside a literal changed the translation: " + quoted);
    }

    @Test
    @DisplayName("a WHERE the manual fallback covers whole is sent as the fallback translated it")
    void aWhereTheFallbackCoversWholeIsSent() {
        assertEquals("nemaki_tag:\"x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'x'"));
        assertEquals("nemaki_tag:\"x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE (nemaki:tag = 'x')"));
        assertEquals("nemaki_tag:\"x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'x' ORDER BY cmis:name"));
        assertEquals("-nemaki_due:[* TO *]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:due IS NULL"));
        assertEquals("nemaki_tag:\"x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE\tnemaki:tag = 'x'"));
        // FROM a type that does not declare the property, so the fallback reads the list itself.
        assertEquals("secondary_object_type_ids:(\"a\" OR \"b\")", assertAnsweredWithOneQuery(
                "SELECT * FROM nemaki:plain WHERE ANY cmis:secondaryObjectTypeIds IN ('a', 'b')"));
        // A table qualifier names the same property (closing batch 2 review: it used to be refused).
        assertEquals("nemaki_tag:\"x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document d WHERE d.nemaki:tag = 'x'"));
        // CMIS escapes a quote with a backslash; the escaped quote stays inside the value.
        assertEquals("nemaki_tag:\"a'b\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'a\\'b'"));
        // `!=` matches documents that HAVE the property and not this value — a document without it is not a match.
        assertEquals("+nemaki_tag:[* TO *] -nemaki_tag:\"x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag <> 'x'"));
        // An escaped quote inside an IN value does not split it in two.
        // (escapeAndQuote also escapes the colon inside the phrase; Solr reads it back as the same value.)
        assertEquals("secondary_object_type_ids:(\"acme\\:Bob's\" OR \"c\")", assertAnsweredWithOneQuery(
                "SELECT * FROM nemaki:plain WHERE ANY cmis:secondaryObjectTypeIds IN ('acme:Bob\\'s', 'c')"));
        // ORDER BY inside a literal is the value, not the end of the clause.
        assertEquals("nemaki_tag:\"order by x\"",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'order by x'"));
    }
}
