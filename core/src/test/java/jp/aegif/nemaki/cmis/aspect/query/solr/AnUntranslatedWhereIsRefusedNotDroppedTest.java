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
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.server.support.query.CmisTree;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
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
    private TypeManager types;
    private SolrUtil solrUtil;
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
        // A property the map sends to a field the indexer never writes (closing batch 4 review, subagent P3-4).
        properties.put("cmis:contentStreamMimeType", define(new PropertyStringDefinitionImpl(),
                "cmis:contentStreamMimeType", PropertyType.STRING, Cardinality.SINGLE));
        // A property whose definition cannot be READ when its field is looked up (closing batch 4 review, P2-1).
        properties.put("nemaki:unreadableField", define(new PropertyStringDefinitionImpl(), "nemaki:unreadableField",
                PropertyType.STRING, Cardinality.SINGLE));
        // Properties whose QUERY name is not their id — nemaki:tag is queried as tag on bedroom (closing batch 4 review,
        // subagent P2-2). The index is written under the id.
        PropertyStringDefinitionImpl noteById = define(new PropertyStringDefinitionImpl(), "nemaki:noteId",
                PropertyType.STRING, Cardinality.SINGLE);
        noteById.setQueryName("noteq");
        properties.put("nemaki:noteId", noteById);
        PropertyStringDefinitionImpl tagsById = define(new PropertyStringDefinitionImpl(), "nemaki:tagsId",
                PropertyType.STRING, Cardinality.MULTI);
        tagsById.setQueryName("tagsq");
        properties.put("nemaki:tagsId", tagsById);
        // One property's ID is another's QUERY name (closing batch 5 review, subagent P3-3): the column names the
        // second, and a lookup by key first took the first.
        PropertyStringDefinitionImpl clashById = define(new PropertyStringDefinitionImpl(), "clash",
                PropertyType.STRING, Cardinality.SINGLE);
        clashById.setQueryName("clashq");
        properties.put("clash", clashById);
        PropertyStringDefinitionImpl clashByQueryName = define(new PropertyStringDefinitionImpl(), "nemaki:clashB",
                PropertyType.STRING, Cardinality.SINGLE);
        clashByQueryName.setQueryName("clash");
        properties.put("nemaki:clashB", clashByQueryName);
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

        types = mock(TypeManager.class);
        when(types.getTypeByQueryName(REPO, "cmis:document")).thenReturn(document);
        when(types.getTypeByQueryName(REPO, "nemaki:plain")).thenReturn(plain);
        // As TypeManagerImpl answers: the property found by its query name, and its QUERY name returned (not its id).
        when(types.getPropertyIdForQueryName(eq(REPO), any(TypeDefinition.class), anyString()))
                .thenAnswer(call -> {
                    String queryName = call.getArgument(2);
                    TypeDefinition type = call.getArgument(1);
                    for (PropertyDefinition<?> definition : type.getPropertyDefinitions().values()) {
                        if (queryName.equals(definition.getQueryName())) {
                            return definition.getQueryName();
                        }
                    }
                    return null;
                });
        when(types.getTypesDescendants(eq(REPO), anyString(), any(BigInteger.class), anyBoolean()))
                .thenReturn(Collections.emptyList());
        // The properties a SECONDARY TYPE defines — the only ones the manual fallback may translate. A statement
        // naming one without the JOIN fails the strict parse and reaches the fallback; a property no type defines
        // must be refused, not translated into a field nothing is indexed under (closing batch 2 review, P1).
        // The type carries the DEFINITIONS (closing batch 3 review, Codex P1): the gate checks that a query may use the
        // property the way the statement uses it, as the walker does — queryable, and single-valued text for `=`,
        // `<>` and LIKE.
        SecondaryTypeDefinitionImpl aspect = new SecondaryTypeDefinitionImpl();
        aspect.setId("nemaki:aspect");
        aspect.setQueryName("nemaki:aspect");
        Map<String, PropertyDefinition<?>> aspectProperties = new LinkedHashMap<>();
        for (String text : List.of("nemaki:tag", "nemaki:due", "nemaki:other", "nemaki:note")) {
            aspectProperties.put(text, define(new PropertyStringDefinitionImpl(), text, PropertyType.STRING,
                    Cardinality.SINGLE));
        }
        PropertyStringDefinitionImpl hidden = define(new PropertyStringDefinitionImpl(), "nemaki:hidden",
                PropertyType.STRING, Cardinality.SINGLE);
        hidden.setIsQueryable(false);
        aspectProperties.put("nemaki:hidden", hidden);
        aspectProperties.put("nemaki:aspectMulti", define(new PropertyStringDefinitionImpl(), "nemaki:aspectMulti",
                PropertyType.STRING, Cardinality.MULTI));
        aspectProperties.put("nemaki:count", define(new PropertyIntegerDefinitionImpl(), "nemaki:count",
                PropertyType.INTEGER, Cardinality.SINGLE));
        // A property whose query name is not its id: the index knows it by its id (closing batch 3 review, subagent P3-5).
        PropertyStringDefinitionImpl renamed = define(new PropertyStringDefinitionImpl(), "nemaki:renamedId",
                PropertyType.STRING, Cardinality.SINGLE);
        renamed.setQueryName("nemaki:renamed");
        aspectProperties.put("nemaki:renamedId", renamed);
        // Multi-valued numbers, booleans and dates, and a name with a hyphen (closing batch 4 review).
        aspectProperties.put("nemaki:ratings", define(new PropertyIntegerDefinitionImpl(), "nemaki:ratings",
                PropertyType.INTEGER, Cardinality.MULTI));
        aspectProperties.put("nemaki:flags", define(new PropertyBooleanDefinitionImpl(), "nemaki:flags",
                PropertyType.BOOLEAN, Cardinality.MULTI));
        aspectProperties.put("nemaki:dates", define(new PropertyDateTimeDefinitionImpl(), "nemaki:dates",
                PropertyType.DATETIME, Cardinality.MULTI));
        aspectProperties.put("nemaki:review-id", define(new PropertyStringDefinitionImpl(), "nemaki:review-id",
                PropertyType.STRING, Cardinality.SINGLE));
        aspectProperties.put("nemaki:unreadableAspect", define(new PropertyStringDefinitionImpl(),
                "nemaki:unreadableAspect", PropertyType.STRING, Cardinality.SINGLE));
        aspect.setPropertyDefinitions(aspectProperties);
        for (String defined : List.of("nemaki:tag", "nemaki:due", "nemaki:other", "nemaki:note", "nemaki:hidden",
                "nemaki:aspectMulti", "nemaki:count", "nemaki:renamed", "nemaki:ratings", "nemaki:flags",
                "nemaki:dates", "nemaki:review-id", "nemaki:unreadableAspect")) {
            when(types.findSecondaryTypeByPropertyQueryName(REPO, defined)).thenReturn(aspect);
        }
        // A lookup that FAILS: the type system could not be searched (closing batch 3 review, subagent P3-2).
        when(types.findSecondaryTypeByPropertyQueryName(REPO, "nemaki:unreadable"))
                .thenThrow(new IllegalStateException("No types are loaded for repository " + REPO));

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
        solrUtil = mock(SolrUtil.class);
        when(solrUtil.getSolrClient()).thenReturn(client);
        when(solrUtil.getPropertyNameInSolr(eq(REPO), anyString()))
                .thenAnswer(call -> ((String) call.getArgument(1)).replace(':', '_'));
        when(solrUtil.getPropertyNameInSolr(REPO, "cmis:contentStreamMimeType")).thenReturn("content_mimetype");
        when(solrUtil.getPropertyNameInSolr(REPO, "nemaki:unreadableAspect")).thenThrow(
                new jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException("the definition could not be read"));
        when(solrUtil.getPropertyNameInSolr(REPO, "nemaki:unreadableField")).thenThrow(
                new jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException("the definition could not be read"));
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
        // The inequality is written <>, not !=: `!` is not CMIS, the strict lexer stops at it, FROM is never read, and
        // such a statement is refused as one that does not parse — whatever the fallback answered. With != this line
        // was satisfied by that refusal, and the not-equals pattern's find() went unseen (closing batch 5, QH5).
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag LIKE 'a%' AND notAProperty = 'b'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag = 'x' AND notAProperty IS NULL");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:tag <> 'x' AND notAProperty = 'b'");
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
    @Test
    @DisplayName("the fallback answers only what a query may ask of the property: queryable, and single-valued text for =, <> and LIKE (closing batch 3 review)")
    void aPropertyAQueryCannotUseThatWayIsRefused() {
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:hidden = 'x'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:hidden IS NULL");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:aspectMulti = 'x'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:aspectMulti LIKE 'x%'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:aspectMulti <> 'x'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:count LIKE '1%'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE nemaki:count = '1'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE ANY nemaki:tag IN ('a')");
        // And not more than that: whether a property has a value can be asked of any of them.
        assertEquals("-nemaki_aspectMulti:[* TO *]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:aspectMulti IS NULL"));
        assertEquals("nemaki_count:[* TO *]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:count IS NOT NULL"));
    }

    @Test
    @DisplayName("ANY ... IN over a multi-valued secondary-type property is translated by the fallback (closing batch 3 review)")
    void anyInOverASecondaryMultiValuedPropertyIsTranslated() {
        assertEquals("nemaki_aspectMulti:(\"a\" OR \"b'c\")", assertAnsweredWithOneQuery(
                "SELECT * FROM cmis:document WHERE ANY nemaki:aspectMulti IN ('a', 'b\\'c')"));
    }

    @Test
    @DisplayName("an escaped % or _ in a LIKE pattern is that character, not a wildcard — in both readers (closing batch 3 review)")
    void anEscapedWildcardIsALiteral() {
        // The fallback.
        assertEquals("nemaki_tag:100%",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag LIKE '100\\%'"));
        assertEquals("nemaki_tag:a_b",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag LIKE 'a\\_b'"));
        assertEquals("nemaki_tag:100*",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:tag LIKE '100%'"));
        // The walker, which read the escaped text and kept the backslash.
        assertEquals("cmis_name:100%",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE cmis:name LIKE '100\\%'"));
        assertEquals("cmis_name:100*",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE cmis:name LIKE '100%'"));
    }

    @Test
    @DisplayName("the walker reads a literal's escapes: \\' is a quote in the value (closing batch 3 review)")
    void theWalkerReadsTheLiteralsEscapes() {
        assertEquals("cmis_name:a'b",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE cmis:name = 'a\\'b'"));
        String in = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE ANY nemaki:labels IN ('a\\'b', 'c')");
        assertTrue(in.contains("nemaki_labels:a'b"), "the escaped quote stayed escaped in the value: " + in);
    }

    @Test
    @DisplayName("<>, NOT LIKE and NOT IN match documents that HAVE the property — in the walker as in the fallback (closing batch 3 review)")
    void aNegationNeedsTheProperty() {
        assertEquals("+cmis_name:[* TO *] -cmis_name:x",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE cmis:name <> 'x'"));
        assertEquals("+nemaki_known:[* TO *] -nemaki_known:a*",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:known NOT LIKE 'a%'"));
        assertEquals("+nemaki_known:[* TO *] -(nemaki_known:a nemaki_known:b)",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:known NOT IN ('a', 'b')"));
    }

    @Test
    @DisplayName("the field is the one the property's definition is indexed under — its id, not the name written (closing batch 3 review)")
    void theFieldIsTheDefinitionsId() {
        assertEquals("-nemaki_renamedId:[* TO *]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE nemaki:renamed IS NULL"));
    }

    @Test
    @DisplayName("a failure to read what the query needs is the server's failure, not the client's 400 and not an empty answer (closing batch 3 review)")
    void aFailureIsNotARefusalOrAnEmptyAnswer() throws Exception {
        sent.clear();
        assertThrows(CmisRuntimeException.class,
                () -> run("SELECT * FROM cmis:document WHERE nemaki:unreadable IS NULL"),
                "a type system that could not be searched was answered as the client's mistake");
        assertEquals(List.of(), sent, "a statement whose gate could not be read was sent anyway");

        SolrUtil noClient = mock(SolrUtil.class);
        SolrQueryProcessor withoutSolr = processorOver(noClient);
        assertThrows(CmisRuntimeException.class, () -> withoutSolr.query(null, REPO, "SELECT * FROM cmis:document",
                false, false, null, null, BigInteger.TEN, BigInteger.ZERO, null),
                "a search engine that could not be reached was answered as «nothing matches»");

        SolrClient failing = mock(SolrClient.class);
        when(failing.query(any(SolrParams.class))).thenThrow(new SolrServerException("down"));
        SolrUtil failingUtil = mock(SolrUtil.class);
        when(failingUtil.getSolrClient()).thenReturn(failing);
        SolrQueryProcessor overFailing = processorOver(failingUtil);
        assertThrows(CmisRuntimeException.class, () -> overFailing.query(null, REPO, "SELECT * FROM cmis:document",
                false, false, null, null, BigInteger.TEN, BigInteger.ZERO, null),
                "a search engine that did not answer was answered as the client's mistake");
    }

    @Test
    @DisplayName("FROM a type the repository does not have is the client's 400, not an empty answer (closing batch 3 review)")
    void anUnknownTypeInFromIsRefused() {
        assertRefusedWithoutAQuery("SELECT * FROM nemaki:typo");
    }

    private SolrQueryProcessor processorOver(SolrUtil util) {
        SolrQueryProcessor other = new SolrQueryProcessor();
        other.setTypeManager(types);
        other.setSolrUtil(util);
        other.setContentService(mock(ContentService.class));
        other.setExceptionService(new ExceptionServiceImpl());
        return other;
    }

    @Test
    @DisplayName("NOT around a comparison is the opposite comparison: a document without the property matches neither (closing batch 4 review)")
    void aNotAroundAComparisonIsTheOppositeComparison() {
        // OData's not(description ne 'x') builds the first one; the whole negation of `+p:[* TO *] -p:x` returned every
        // document without p besides the ones with p = 'x'.
        assertEquals("cmis_name:x",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (cmis:name <> 'x')"));
        assertEquals("nemaki_known:a*",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (nemaki:known NOT LIKE 'a%')"));
        assertEquals("nemaki_known:a nemaki_known:b",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (nemaki:known NOT IN ('a', 'b'))"));
        assertEquals("+cmis_name:[* TO *] -cmis_name:x",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (cmis:name = 'x')"));
        assertEquals("+nemaki_known:[* TO *] -nemaki_known:a*",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (nemaki:known LIKE 'a%')"));
        assertEquals("+nemaki_known:[* TO *] -(nemaki_known:a)",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (nemaki:known IN ('a'))"));
    }

    @Test
    @DisplayName("NOT around an ordering is the opposite ordering, not every document without the property (closing batch 5)")
    void aNotAroundAnOrderingIsTheOppositeOrdering() {
        // NOT (p < 'm') is p >= 'm' under three-valued logic. The whole negation, -p:{* TO m}, also matched every
        // document that has no p at all.
        assertEquals("cmis_name:[m TO *}",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (cmis:name < 'm')"));
        assertEquals("cmis_name:{m TO *}",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (cmis:name <= 'm')"));
        assertEquals("cmis_name:{* TO m]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (cmis:name > 'm')"));
        assertEquals("cmis_name:{* TO m}",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE NOT (cmis:name >= 'm')"));
    }

    @Test
    @DisplayName("ANY ... NOT IN is refused: one query on a multi-valued field cannot ask whether SOME value is outside the list (closing batch 4 review)")
    void anyNotInIsRefused() {
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE ANY nemaki:labels NOT IN ('a')");
    }

    @Test
    @DisplayName("the fallback's ANY ... IN reads the literal each type is written with: numbers and booleans bare, text quoted (closing batch 4 review)")
    void anyInReadsNumbersAndBooleans() {
        assertEquals("nemaki_ratings:(\"1\" OR \"\\-2\")",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE ANY nemaki:ratings IN (1, -2)"));
        assertEquals("nemaki_flags:(\"true\")",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE ANY nemaki:flags IN (TRUE)"));
        // Read as the walker reads a number, not compared as written (closing batch 5 review, subagent P3-4).
        assertEquals("nemaki_ratings:(\"1\" OR \"1\")",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE ANY nemaki:ratings IN (+1, 01)"));
        // Not a literal of the property's type, and not DATETIME (R187): refused.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE ANY nemaki:ratings IN ('1')");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE ANY nemaki:ratings IN (1.5)");
        assertRefusedWithoutAQuery(
                "SELECT * FROM cmis:document WHERE ANY nemaki:dates IN (TIMESTAMP '2026-01-01T00:00:00.000Z')");
        // A DATETIME is refused for its TYPE, not because its literal does not read as a number: a bare number for
        // it would read as one (closing batch 6 — SJ5 stayed green once numbers were read back).
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE ANY nemaki:dates IN (20260101)");
    }

    @Test
    @DisplayName("a property the index does not hold is refused, not answered as nothing or as everything (closing batch 4 review)")
    void aPropertyTheIndexDoesNotHoldIsRefused() {
        // Measured on bedroom: `= 'text/plain'` answered 0 and `IS NULL` all 2,456 documents.
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE cmis:contentStreamMimeType = 'text/plain'");
        assertRefusedWithoutAQuery("SELECT * FROM cmis:document WHERE cmis:contentStreamMimeType IS NULL");
    }

    @Test
    @DisplayName("a definition that cannot be read is the server's failure in the walker as in the fallback, and a statement that does not parse is the client's (closing batch 4 review)")
    void anUnreadableDefinitionIsAFailureAndAMalformedStatementARefusal() {
        sent.clear();
        assertThrows(CmisRuntimeException.class,
                () -> run("SELECT * FROM cmis:document WHERE nemaki:unreadableField = 'x'"),
                "a property definition the walker could not read was answered as an untranslatable WHERE clause");
        assertEquals(List.of(), sent, "a statement whose field could not be read was sent anyway");
        assertThrows(CmisRuntimeException.class,
                () -> run("SELECT * FROM cmis:document WHERE nemaki:unreadableAspect IS NULL"),
                "a property definition the manual fallback could not read was answered as an untranslatable WHERE clause");
        when(types.getTypeByQueryName(REPO, "nemaki:broken")).thenThrow(
                new jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException("No types are loaded"));
        assertThrows(CmisRuntimeException.class, () -> run("SELECT * FROM nemaki:broken WHERE cmis:name = 'a'"),
                "a type system that could not be read was answered as the client's mistake");
        // The client's statement does not parse: a 400, not the server's 500.
        assertRefusedWithoutAQuery("SELECT * FORM cmis:document");
    }

    @Test
    @DisplayName("a property whose query name is not its id is queried under its id, in every production of the walker (closing batch 4 review)")
    void aPropertyIsQueriedUnderItsId() {
        // Measured on bedroom before this: one nemaki:document tagged 'r215xtag' — `tag IS NOT NULL` answered 0 and
        // `tag IS NULL` 1, because the field was built from the query name.
        assertEquals("+*:* -nemaki_noteId:[* TO *]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE noteq IS NULL"));
        assertEquals("nemaki_noteId:x", assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE noteq = 'x'"));
        assertEquals("+nemaki_noteId:[* TO *] -nemaki_noteId:x",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE noteq <> 'x'"));
        assertEquals("nemaki_noteId:a*", assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE noteq LIKE 'a%'"));
        assertEquals("nemaki_noteId:a nemaki_noteId:b",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE noteq IN ('a', 'b')"));
        String any = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE ANY tagsq IN ('a')");
        assertTrue(any.contains("nemaki_tagsId") && !any.contains("tagsq"), "ANY ... IN went out under the query name: " + any);
        // A qualified column in a comparison resolves the same way (the joined text named d.noteq).
        assertEquals("nemaki_noteId:x",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document d WHERE d.noteq = 'x'"));
    }

    @Test
    @DisplayName("a column is resolved by its query name before any property whose id is that text (closing batch 5 review)")
    void aQueryNameIsResolvedBeforeAnIdOfTheSameText() {
        assertEquals("nemaki_clashB:x", assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE clash = 'x'"));
        assertEquals("+*:* -nemaki_clashB:[* TO *]",
                assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE clash IS NULL"));
    }

    @Test
    @DisplayName("CONTAINS reads its literal's CMIS escapes, as every other literal is read (closing batch 5 review)")
    void containsReadsTheLiteralsEscapes() {
        // it's, escaped as a text search expression and then as a CMIS literal: `CONTAINS('it\\\'s')` — what the
        // bundled UI and OData's $search write. Undoing only the literal's level left `it\'s` for Solr.
        String q = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE CONTAINS('it\\\\\\'s')");
        assertTrue(q.contains("it's"), "the quote the literal escapes is not in the search: " + q);
        assertFalse(q.contains("\\'"), "the escape reached the search as a backslash: " + q);
    }

    @Test
    @DisplayName("a CONTAINS phrase that ends in an escaped backslash is still a phrase (closing batch 6 review)")
    void aPhraseEndingInAnEscapedBackslashIsAPhrase() {
        // The expression "a b\\" — a phrase whose last character is a backslash — was read as the words "a and b\"
        // (subagent P3-4): the check asked only whether it ended in \" .
        String q = assertAnsweredWithOneQuery("SELECT * FROM cmis:document WHERE CONTAINS('\"a b\\\\\\\\\"')");
        assertFalse(q.contains("+("), "a phrase was split into words: " + q);
        assertTrue(q.startsWith("text:\"a b\\\\"), "the phrase is not the text a b\\ : " + q);
    }

    @Test
    @DisplayName("a failure that is not a syntax error is the server's 500, even without the unreadable mark (closing batch 5 review)")
    void aFailureThatIsNotASyntaxErrorIsTheServers() {
        // A RuntimeException from the type manager while the statement is processed — its lazy init failing — left no
        // FROM, and was answered as «the statement could not be parsed» (closing batch 5 review, subagent P3-2).
        when(types.getTypeByQueryName(REPO, "nemaki:failing")).thenThrow(new IllegalStateException("init failed"));
        sent.clear();
        assertThrows(CmisRuntimeException.class, () -> run("SELECT * FROM nemaki:failing"),
                "a failure of the server's own was answered as the client's malformed statement");
        assertEquals(List.of(), sent, "a statement whose FROM could not be read was sent anyway");
    }
}
