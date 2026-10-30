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
import java.util.Set;
import java.util.TreeSet;

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
    /** The {@code fq} list of each query, in the same order as {@link #sent} (R215 — a JOIN narrows the FROM's rows). */
    private final List<List<String>> filtersSent = new ArrayList<>();

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

        // A secondary type the statements JOIN (R215): resolvable by its query name, with the cmis:objectId an ON names
        // and a property of its own. A WHERE naming that property gets the server's own JOIN.
        SecondaryTypeDefinitionImpl joinable = new SecondaryTypeDefinitionImpl();
        joinable.setId("nemaki:joinable");
        joinable.setLocalName("joinable");
        joinable.setQueryName("nemaki:joinable");
        joinable.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        joinable.setIsQueryable(true);
        joinable.setIsFulltextIndexed(false);
        joinable.setIsIncludedInSupertypeQuery(true);
        Map<String, PropertyDefinition<?>> joinableProperties = new LinkedHashMap<>();
        joinableProperties.put("cmis:objectId", properties.get("cmis:objectId"));
        joinableProperties.put("nemaki:jtag", define(new PropertyStringDefinitionImpl(), "nemaki:jtag", PropertyType.STRING,
                Cardinality.SINGLE));
        joinable.setPropertyDefinitions(joinableProperties);
        when(types.getTypeByQueryName(REPO, "nemaki:joinable")).thenReturn(joinable);
        when(types.findSecondaryTypeByPropertyQueryName(REPO, "nemaki:jtag")).thenReturn(joinable);
        // Another secondary type, a type whose subtype a query of it includes, and one whose subtype it leaves out
        // (closing batch 17 review: subagent P2-1, P3-1, P3-2).
        secondaryType("nemaki:second", properties.get("cmis:objectId"));
        SecondaryTypeDefinitionImpl parentAspect = secondaryType("nemaki:parentAspect", properties.get("cmis:objectId"));
        // A property of it, so that a statement can name it as a row (R217).
        parentAspect.getPropertyDefinitions().put("nemaki:ptag", define(new PropertyStringDefinitionImpl(), "nemaki:ptag",
                PropertyType.STRING, Cardinality.SINGLE));
        when(types.findSecondaryTypeByPropertyQueryName(REPO, "nemaki:ptag")).thenReturn(parentAspect);
        SecondaryTypeDefinitionImpl childAspect = secondaryType("nemaki:childAspect", properties.get("cmis:objectId"));
        childAspect.setParentTypeId("nemaki:parentAspect");
        SecondaryTypeDefinitionImpl quietParent = secondaryType("nemaki:quietParent", properties.get("cmis:objectId"));
        SecondaryTypeDefinitionImpl quietChild = secondaryType("nemaki:quietChild", properties.get("cmis:objectId"));
        quietChild.setParentTypeId("nemaki:quietParent");
        quietChild.setIsIncludedInSupertypeQuery(false);
        when(types.getTypesDescendants(eq(REPO), eq("nemaki:parentAspect"), any(BigInteger.class), anyBoolean()))
                .thenReturn(List.of(new org.apache.chemistry.opencmis.commons.impl.dataobjects
                        .TypeDefinitionContainerImpl(childAspect)));
        when(types.getTypesDescendants(eq(REPO), eq("nemaki:quietParent"), any(BigInteger.class), anyBoolean()))
                .thenReturn(List.of(new org.apache.chemistry.opencmis.commons.impl.dataobjects
                        .TypeDefinitionContainerImpl(quietChild)));
        // Two types whose subtypes the type system cannot say anything of: one listed without its definition — as
        // TypeManagerImpl keeps such a container — and one listed as null (closing batch 18 review: Codex P2, subagent
        // P3-2).
        secondaryType("nemaki:unreadableParent", properties.get("cmis:objectId"));
        when(types.getTypesDescendants(eq(REPO), eq("nemaki:unreadableParent"), any(BigInteger.class), anyBoolean()))
                .thenReturn(List.of(new org.apache.chemistry.opencmis.commons.impl.dataobjects
                        .TypeDefinitionContainerImpl()));
        secondaryType("nemaki:nullParent", properties.get("cmis:objectId"));
        when(types.getTypesDescendants(eq(REPO), eq("nemaki:nullParent"), any(BigInteger.class), anyBoolean()))
                .thenReturn(java.util.Arrays.asList(
                        (org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer) null));
        // A document type defining a property of the same query name as nemaki:joinable's (closing batch 20 review,
        // subagent P3-1), and a type the type system does not answer for (closing batch 20 review, Codex P2).
        DocumentTypeDefinitionImpl withJtag = new DocumentTypeDefinitionImpl();
        withJtag.setId("nemaki:withJtag");
        withJtag.setLocalName("withJtag");
        withJtag.setQueryName("nemaki:withJtag");
        withJtag.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        withJtag.setIsQueryable(true);
        withJtag.setIsFulltextIndexed(false);
        withJtag.setIsIncludedInSupertypeQuery(true);
        Map<String, PropertyDefinition<?>> withJtagProperties = new LinkedHashMap<>();
        withJtagProperties.put("cmis:name", properties.get("cmis:name"));
        withJtagProperties.put("cmis:objectId", properties.get("cmis:objectId"));
        withJtagProperties.put("cmis:objectTypeId", properties.get("cmis:objectTypeId"));
        withJtagProperties.put("nemaki:jtag", joinableProperties.get("nemaki:jtag"));
        withJtag.setPropertyDefinitions(withJtagProperties);
        when(types.getTypeByQueryName(REPO, "nemaki:withJtag")).thenReturn(withJtag);
        when(types.getTypeByQueryName(REPO, "nemaki:unloadable"))
                .thenThrow(new CmisRuntimeException("the type system is not loaded"));

        SolrClient client = mock(SolrClient.class);
        when(client.query(any(SolrParams.class))).thenAnswer(call -> {
            SolrParams params = call.getArgument(0);
            sent.add(params.get("q"));
            String[] fqs = params.getParams("fq");
            filtersSent.add(fqs == null ? List.of() : List.of(fqs));
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

    /** A queryable secondary type of the given id, resolvable by its query name, with cmis:objectId for an ON. */
    private SecondaryTypeDefinitionImpl secondaryType(String id, PropertyDefinition<?> objectId) {
        SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
        type.setId(id);
        type.setLocalName(id.substring(id.indexOf(':') + 1));
        type.setQueryName(id);
        type.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        type.setIsQueryable(true);
        type.setIsFulltextIndexed(false);
        type.setIsIncludedInSupertypeQuery(true);
        Map<String, PropertyDefinition<?>> typeProperties = new LinkedHashMap<>();
        typeProperties.put("cmis:objectId", objectId);
        type.setPropertyDefinitions(typeProperties);
        when(types.getTypeByQueryName(REPO, id)).thenReturn(type);
        return type;
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
        filtersSent.clear();
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

    // ---------------------------------------------------------------------------------------------------- R215: JOIN
    // A JOIN with a secondary type is a condition on the answer. It was parsed and then not read: FROM cmis:document d
    // JOIN nemaki:commentable c ON d.cmis:objectId = c.cmis:objectId answered the same 2,457 documents as the statement
    // without it, not the 2 that have the type (measured on bedroom before the change, 2026-10-29). What each statement
    // sent is read against three documents — one with the type, one without it under the same name, and one more with
    // the type under another name — so a query that drops the JOIN, or keeps only the other condition, answers a set
    // the lock names as wrong (a count would not: two statements wrong the same way agree on it).

    /** Each document's indexed fields, under the names the mocked map gives them (a property id with : as _). */
    private static final Map<String, Map<String, List<String>>> DOCUMENTS = Map.of(
            "has", fields("a", "nemaki:joinable", "x", "x"),
            "hasNot", fields("a", null, null, "x"),
            "hasToo", fields("b", "nemaki:joinable", null, null),
            "hasSecond", fields("c", "nemaki:second", "y", null),
            "typedHas", typedFields("nemaki:withJtag", "a", null, "x"),
            "typedJoined", typedFields("nemaki:withJtag", "b", "nemaki:joinable", "x"));

    /** A document of another type, with a value of nemaki:jtag — the type's own — and the secondary type given. */
    private static Map<String, List<String>> typedFields(String type, String name, String secondaryType, String jtag) {
        Map<String, List<String>> fields = fields(name, secondaryType, jtag, null);
        fields.put("cmis_objectTypeId", List.of(type));
        return fields;
    }

    private static Map<String, List<String>> fields(String name, String secondaryType, String jtag, String tag) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("repository_id", List.of(REPO));
        fields.put("cmis_objectTypeId", List.of("cmis:document"));
        fields.put("cmis_name", List.of(name));
        fields.put("cmis_secondaryObjectTypeIds", secondaryType == null ? List.of() : List.of(secondaryType));
        fields.put("nemaki_jtag", jtag == null ? List.of() : List.of(jtag));
        fields.put("nemaki_tag", tag == null ? List.of() : List.of(tag));
        return fields;
    }

    /** The documents the last query sent matches: its q and every one of its fq. */
    private Set<String> answered(String statement) {
        assertAnsweredWithOneQuery(statement);
        String q = sent.get(sent.size() - 1);
        List<String> fqs = filtersSent.get(filtersSent.size() - 1);
        Set<String> matched = new TreeSet<>();
        for (Map.Entry<String, Map<String, List<String>>> document : DOCUMENTS.entrySet()) {
            boolean all = SentQuery.matches(q, document.getValue());
            for (String fq : fqs) {
                all &= SentQuery.matches(fq, document.getValue());
            }
            if (all) {
                matched.add(document.getKey());
            }
        }
        return matched;
    }

    /** What the last query sent, for a message: its q and its fq. */
    private String lastSent() {
        return "q=" + sent.get(sent.size() - 1) + " fq=" + filtersSent.get(filtersSent.size() - 1);
    }

    @Test
    @DisplayName("a JOIN with a secondary type answers only the documents that have it, as ANY cmis:secondaryObjectTypeIds does (R215)")
    void aJoinWithASecondaryTypeAnswersOnlyTheDocumentsThatHaveIt() {
        assertEquals(Set.of("has", "hasToo"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId"), () -> "the JOIN did not narrow the answer to the documents with the type" + " — " + lastSent());
        List<String> joinFilters = filtersSent.get(filtersSent.size() - 1);
        // The same documents the statement that asks it without a JOIN answers — and it is asked in the same text: the
        // JOIN's filter is the clause the ANY statement sends as its q. A filter in other text answered other documents
        // on bedroom: the id unescaped was a syntax error to Solr, which the reader below also refuses.
        assertEquals(Set.of("has", "hasToo"), answered("SELECT cmis:objectId FROM cmis:document WHERE "
                + "ANY cmis:secondaryObjectTypeIds IN ('nemaki:joinable')"), () -> "the ANY statement answered other documents" + " — " + lastSent());
        String anyClause = sent.get(sent.size() - 1);
        assertTrue(joinFilters.stream().anyMatch(fq -> fq.endsWith(" AND (" + anyClause + ")")),
                () -> "the JOIN did not send the clause ANY sends (" + anyClause + "): " + joinFilters);
    }

    @Test
    @DisplayName("a JOIN and a condition answer the documents that meet both, not the condition alone (R215)")
    void aJoinAndAConditionAnswerTheDocumentsThatMeetBoth() {
        assertEquals(Set.of("has"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId WHERE d.cmis:name = 'a'"),
                () -> "the JOIN with a condition answered the condition alone or neither" + " — " + lastSent());
    }

    @Test
    @DisplayName("a JOIN is the same JOIN without aliases and with its ON written the other way round (R215)")
    void aJoinWithoutAliasesOrTurnedRoundIsTheSameJoin() {
        assertEquals(Set.of("has", "hasToo"), answered("SELECT cmis:document.cmis:objectId FROM cmis:document JOIN nemaki:joinable "
                + "ON cmis:document.cmis:objectId = nemaki:joinable.cmis:objectId"),
                () -> "the JOIN without aliases did not narrow the answer" + " — " + lastSent());
        assertEquals(Set.of("has", "hasToo"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON j.cmis:objectId = d.cmis:objectId"), () -> "the JOIN with its ON turned round did not narrow the answer" + " — " + lastSent());
    }

    @Test
    @DisplayName("a JOIN not ON the two objects' ids is refused, not answered without its condition (R215)")
    void aJoinThatIsNotOnTheObjectIdsIsRefused() {
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:name = j.cmis:objectId");
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = d.cmis:objectId");
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j");
    }

    @Test
    @DisplayName("a JOIN with a type that is not a secondary type is refused: two primary types are not joined (R215)")
    void aJoinWithAPrimaryTypeIsRefused() {
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:plain p "
                + "ON d.cmis:objectId = p.cmis:objectId");
    }

    @Test
    @DisplayName("an outer JOIN with a secondary type is refused: only the INNER JOIN is answered (R215)")
    void anOuterJoinIsRefused() {
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d LEFT OUTER JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId");
    }

    @Test
    @DisplayName("the JOIN the server adds for a property of a secondary type keeps the answer it had (R215, over-refusal)")
    void theJoinTheServerAddsKeepsTheAnswerItHad() {
        // The server's own JOIN is not the client's condition: WHERE nemaki:jtag IS NULL answers the documents without
        // the property, those without the type among them, as it did before R215 — neither refused nor narrowed.
        assertEquals(Set.of("hasNot", "hasToo"), answered("SELECT cmis:objectId FROM cmis:document WHERE nemaki:jtag "
                + "IS NULL"), () -> "the server's own JOIN changed the answer of a statement the client wrote without one" + " — " + lastSent());
        assertEquals(Set.of("has"), answered("SELECT cmis:objectId FROM cmis:document WHERE nemaki:jtag = 'x'"),
                () -> "the server's own JOIN changed the answer of a statement the client wrote without one" + " — " + lastSent());
        // With the column qualified the statement PARSES after the server's JOIN — unqualified, cmis:objectId names a
        // column of both types and the parse fails before the JOINs are read at all — so the server's JOIN reaches the
        // reading of the JOINs, and has to be told from the client's there.
        assertEquals(Set.of("hasNot", "hasToo"), answered("SELECT cmis:document.cmis:objectId FROM cmis:document WHERE "
                + "nemaki:jtag IS NULL"), () -> "the server's own JOIN, read with the client's, changed the answer of a "
                + "statement that parses — " + lastSent());
        assertEquals(Set.of("has"), answered("SELECT cmis:document.cmis:objectId FROM cmis:document WHERE nemaki:jtag = "
                + "'x'"), () -> "the server's own JOIN, read with the client's, changed the answer of a statement that "
                + "parses — " + lastSent());
    }

    @Test
    @DisplayName("a JOIN the client wrote stays a condition when the WHERE names a property of the joined type (R215)")
    void aWrittenJoinStaysAConditionWhenTheWhereNamesItsProperty() {
        // The server adds its own JOIN of the type too; the client's is read as a condition and the server's is not.
        assertEquals(Set.of("hasToo"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId WHERE j.nemaki:jtag IS NULL"),
                () -> "the client's JOIN was dropped when the WHERE named a property of the type" + " — " + lastSent());
    }

    @Test
    @DisplayName("a JOIN in a statement the parser walks but cannot resolve stays a condition of the fallback's answer (R215)")
    void aJoinInAStatementThatFailsToResolveStaysACondition() {
        // nemaki:tag belongs to a secondary type this world cannot resolve, so the server's JOIN for it fails the parse
        // after the whole statement was walked — as nemaki:comment of nemaki:commentable fails on bedroom. The manual
        // fallback answers the WHERE; the client's JOIN must still narrow it (hasNot has the tag and not the type), and
        // the statement must not be refused either: it was answered right on bedroom before R215.
        assertEquals(Set.of("has"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId WHERE nemaki:tag = 'x'"),
                () -> "the client's JOIN was dropped, or the statement refused, when its parse failed — " + lastSent());
    }


    @Test
    @DisplayName("a JOIN with a secondary type whose subtype a query of it includes is refused, not answered narrower (R215)")
    void aJoinWithATypeThatHasSubtypesIsRefused() {
        // The JOIN asks for the subtype's documents too, and ANY ... IN ('that type') answers the type alone: JOIN
        // cmis:secondary answered 0 (closing batch 17 review, subagent P2-1).
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:parentAspect p "
                + "ON d.cmis:objectId = p.cmis:objectId");
    }

    @Test
    @DisplayName("a JOIN with a secondary type whose subtypes a query of it leaves out is answered (R215, over-refusal)")
    void aJoinWithATypeWhoseSubtypesAQueryLeavesOutIsAnswered() {
        answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:quietParent q ON d.cmis:objectId = q.cmis:objectId");
        assertTrue(filtersSent.get(filtersSent.size() - 1).stream()
                        .anyMatch(fq -> fq.endsWith(" AND (cmis_secondaryObjectTypeIds:nemaki\\:quietParent)")),
                () -> "the JOIN with a type whose subtype is left out was not narrowed to the type — " + lastSent());
    }

    @Test
    @DisplayName("an ON qualified by a type's name instead of its alias is the same JOIN (R215, over-refusal)")
    void anOnQualifiedByTheTypesNameIsTheSameJoin() {
        // The parser takes a type's query name for the row it names (closing batch 17 review, subagent P3-1).
        assertEquals(Set.of("has", "hasToo"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON cmis:document.cmis:objectId = j.cmis:objectId"), () -> "an ON naming the FROM by its type was not "
                + "answered as the JOIN — " + lastSent());
        assertEquals(Set.of("has", "hasToo"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = nemaki:joinable.cmis:objectId"), () -> "an ON naming the joined type by its name "
                + "was not answered as the JOIN — " + lastSent());
    }

    @Test
    @DisplayName("a JOIN ON an earlier joined type's cmis:objectId is answered, both JOINs narrowing (R215, over-refusal)")
    void aJoinChainedOnAnEarlierJoinIsAnswered() {
        // The earlier joined row is the same object as the FROM's (closing batch 17 review, subagent P3-1). No document of
        // the fixture has both types, so the answer is none — the JOIN dropped answers hasToo and has.
        assertEquals(Set.of(), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId JOIN nemaki:second s ON j.cmis:objectId = s.cmis:objectId"),
                () -> "the chained JOIN was not answered with both JOINs narrowing — " + lastSent());
    }

    @Test
    @DisplayName("an alias that is a type's name is not overwritten by the JOIN the server adds for that type (R215)")
    void anAliasNamedAsATypeIsNotOverwrittenByTheServersJoin() {
        // The parser files an unaliased JOIN under the type's name and overwrites an entry of that name, so the
        // server's JOIN of nemaki:joinable took the place of the client's JOIN of nemaki:second (closing batch 17
        // review, subagent P3-2). hasSecond has the type the client joined and the value the WHERE asks for.
        assertEquals(Set.of("hasSecond"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:second "
                + "nemaki:joinable ON d.cmis:objectId = nemaki:joinable.cmis:objectId WHERE nemaki:jtag = 'y'"),
                () -> "the client's JOIN was taken over by the server's JOIN of the alias's name — " + lastSent());
    }

    @Test
    @DisplayName("a client's own JOIN without an ON is refused for its ON, not for a JOIN the server added (R215)")
    void aClientsJoinWithoutAnOnIsRefusedForItsOn() {
        // The server added a JOIN of the same type and the client's was read as the server's, so only the count of
        // JOINs refused it, with a reason that was not the reason (closing batch 17 review, subagent P2-2).
        sent.clear();
        CmisInvalidArgumentException refused = assertThrows(CmisInvalidArgumentException.class, () -> run(
                "SELECT cmis:document.cmis:objectId FROM cmis:document JOIN nemaki:joinable WHERE nemaki:jtag IS NULL"),
                () -> "a client's JOIN without an ON was answered — the queries it sent: " + sent);
        assertTrue(refused.getMessage().contains("answered only ON"), () -> "a client's JOIN without an ON was refused "
                + "for another reason: " + refused.getMessage());
        assertEquals(List.of(), sent, "a client's JOIN without an ON was refused only after a query went to Solr");
    }

    @Test
    @DisplayName("a JOIN with a secondary type whose subtype cannot be read is not answered (R215, fail-closed)")
    void aJoinWithATypeWhoseSubtypeCannotBeReadIsNotAnswered() {
        // A subtype listed without its definition is not one a query leaves out: skipping it answered the type alone,
        // narrower than asked whenever the subtype is one a query includes (closing batch 18 review, Codex P2, subagent
        // P3-2).
        for (String type : List.of("nemaki:unreadableParent", "nemaki:nullParent")) {
            sent.clear();
            assertThrows(CmisRuntimeException.class, () -> run("SELECT d.cmis:objectId FROM cmis:document d JOIN " + type
                    + " p ON d.cmis:objectId = p.cmis:objectId"), () -> "the JOIN with " + type + ", whose subtype "
                    + "could not be read, was not refused as unreadable — the queries it sent: " + sent);
            assertEquals(List.of(), sent, "the JOIN with " + type + " was refused only after a query went to Solr");
        }
    }

    @Test
    @DisplayName("a name given to two rows of the FROM is refused, not answered with one of the rows lost (R215)")
    void aNameGivenToTwoRowsIsRefused() {
        // The parser files a JOIN without an alias under its type's name and overwrites the row of that name (closing
        // batch 18 review, subagent P3-1): the first statement lost its JOIN of nemaki:second and answered has and hasToo,
        // the second turned the FROM's row into the secondary type's.
        assertRefusedWithoutAQuery("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:second nemaki:joinable "
                + "ON d.cmis:objectId = nemaki:joinable.cmis:objectId JOIN nemaki:joinable "
                + "ON d.cmis:objectId = nemaki:joinable.cmis:objectId");
        assertRefusedWithoutAQuery("SELECT nemaki:joinable.cmis:objectId FROM cmis:document nemaki:joinable JOIN "
                + "nemaki:joinable ON nemaki:joinable.cmis:objectId = nemaki:joinable.cmis:objectId");
        assertRefusedWithoutAQuery("SELECT cmis:document.cmis:objectId FROM cmis:document JOIN nemaki:joinable "
                + "ON cmis:document.cmis:objectId = nemaki:joinable.cmis:objectId JOIN nemaki:joinable "
                + "ON cmis:document.cmis:objectId = nemaki:joinable.cmis:objectId");
    }

    @Test
    @DisplayName("the same secondary type JOINed under two aliases is answered (R215, over-refusal)")
    void theSameTypeJoinedUnderTwoAliasesIsAnswered() {
        // Two names, two rows: what is refused is a name given twice, not a type joined twice.
        assertEquals(Set.of("has", "hasToo"), answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable "
                + "j1 ON d.cmis:objectId = j1.cmis:objectId JOIN nemaki:joinable j2 ON d.cmis:objectId = j2.cmis:objectId"),
                () -> "the type JOINed under two aliases was not answered as the type's documents — " + lastSent());
    }

    @Test
    @DisplayName("a SELECT list naming a secondary type keeps the JOIN the server adds for its property (R215, over-refusal)")
    void aSelectListNamingATypeKeepsTheServersJoin() {
        // The FROM was read from the start of the statement, so a column alias that is a type's name counted as the FROM
        // naming the type: the server added no JOIN, the property did not resolve, and the manual fallback, which cannot
        // answer the WHERE whole, refused it (closing batch 18 review, subagent P3-3). The second is the same statement
        // without the alias. The column is qualified: unqualified, cmis:objectId names a column of the server's JOIN too,
        // and the parse fails with or without the alias.
        assertEquals(Set.of("has"), answered("SELECT cmis:document.cmis:objectId AS nemaki:joinable FROM cmis:document "
                + "WHERE nemaki:jtag = 'x' AND cmis:name = 'a'"), () -> "a column alias that is a type's name changed the "
                + "answer — " + lastSent());
        assertEquals(Set.of("has"), answered("SELECT cmis:document.cmis:objectId FROM cmis:document WHERE nemaki:jtag = 'x' "
                + "AND cmis:name = 'a'"), () -> "the server's JOIN for the property did not answer the WHERE whole — "
                + lastSent());
    }

    @Test
    @DisplayName("a statement refused for its shape is refused for it, before a subtype the type system cannot read (R215)")
    void aStatementRefusedForItsShapeIsRefusedBeforeASubtypeIsRead() {
        // The names of the rows and the count of the JOINs read were checked JOIN by JOIN, after the type system was asked
        // for the subtypes of the JOINs before: the first JOIN's unreadable subtype answered 500 for a statement whose
        // second JOIN named a row already named, or was not read (closing batch 19 review, Codex P2).
        for (String statement : List.of(
                "SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable nemaki:unreadableParent "
                        + "ON d.cmis:objectId = nemaki:unreadableParent.cmis:objectId JOIN nemaki:unreadableParent "
                        + "ON d.cmis:objectId = nemaki:unreadableParent.cmis:objectId",
                "SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:unreadableParent p ON d.cmis:objectId = "
                        + "p.cmis:objectId JOIN nemaki:unreadableParent p ON d.cmis:objectId = p.cmis:objectId")) {
            sent.clear();
            CmisInvalidArgumentException refused = assertThrows(CmisInvalidArgumentException.class, () -> run(statement),
                    () -> "「" + statement + "」 was not refused for its shape — the queries it sent: " + sent);
            assertTrue(refused.getMessage().contains("names more than one row")
                    || refused.getMessage().contains("were not all read"), () -> "「" + statement + "」 was refused for "
                    + "another reason: " + refused.getMessage());
            assertEquals(List.of(), sent, "「" + statement + "」 was refused only after a query went to Solr");
        }
    }

    @Test
    @DisplayName("a statement refused for its shape is refused for it when the type system does not answer for a JOIN's type (R215)")
    void aStatementRefusedForItsShapeReadsNoTypeFirst() {
        // The first pass told the server's JOINs from the client's by reading each JOIN's type, so a statement whose second
        // JOIN the parser did not record answered 500 for a type the type system did not answer for (closing batch 20
        // review, Codex P2).
        String statement = "SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:unloadable p ON d.cmis:objectId = "
                + "p.cmis:objectId JOIN nemaki:joinable p ON d.cmis:objectId = p.cmis:objectId";
        sent.clear();
        CmisInvalidArgumentException refused = assertThrows(CmisInvalidArgumentException.class, () -> run(statement),
                () -> "「" + statement + "」 was not refused for its shape — the queries it sent: " + sent);
        assertTrue(refused.getMessage().contains("were not all read"), () -> "「" + statement + "」 was refused for "
                + "another reason: " + refused.getMessage());
        assertEquals(List.of(), sent, "「" + statement + "」 was refused only after a query went to Solr");
    }

    @Test
    @DisplayName("a property the FROM's own type defines is read there, without a JOIN the server adds (R215, over-refusal)")
    void aPropertyTheFromTypeDefinesGetsNoJoinFromTheServer() {
        // The server JOINed nemaki:joinable for nemaki:jtag although the FROM's type defines it too: the name became
        // ambiguous, the parse failed and the manual fallback, which cannot answer the WHERE whole, refused the statement
        // — a statement answered before was refused once a FROM after a newline was found too (closing batch 20 review,
        // subagent P3-1). typedHas is of the FROM's type; has has the secondary type's value under the same name.
        for (String space : List.of(" ", "\n")) {
            assertEquals(Set.of("typedHas"), answered("SELECT cmis:objectId" + space + "FROM nemaki:withJtag WHERE "
                    + "nemaki:jtag = 'x' AND cmis:name = 'a'"), () -> "a property of the FROM's own type was not read "
                    + "there — " + lastSent());
        }
    }

    @Test
    @DisplayName("a column qualified by a secondary type keeps the server's JOIN when the FROM's type defines the property too, and is narrowed by it (R215, R217)")
    void aColumnQualifiedByTheTypeKeepsTheServersJoin() {
        // The JOIN was left out because the FROM's type defines nemaki:jtag too, and it was the one row the qualifier
        // nemaki:joinable resolved to: the parse failed and the manual fallback refused a statement answered before
        // (closing batch 21 review, subagent P3-1). The JOIN narrows (R217): typedHas has the value under the shared
        // property and not the type, and was answered until the closing batch 22 review (Codex P1); typedJoined has both.
        assertEquals(Set.of("typedJoined"), answered("SELECT nemaki:withJtag.cmis:objectId FROM nemaki:withJtag WHERE "
                + "nemaki:joinable.nemaki:jtag = 'x'"), () -> "a column qualified by the secondary type was not answered as "
                + "the row of that type — " + lastSent());
    }

    @Test
    @DisplayName("a column qualified by a secondary type answers only the documents that have it, as the JOIN written does (R217)")
    void aColumnQualifiedByASecondaryTypeAnswersOnlyTheDocumentsThatHaveIt() {
        // The server's JOIN for a type the statement names as a row narrows as the client's JOIN does: hasNot has no
        // nemaki:jtag and not the type, and was answered when the server's JOIN added no condition.
        Set<String> joined = answered("SELECT d.cmis:objectId FROM cmis:document d JOIN nemaki:joinable j "
                + "ON d.cmis:objectId = j.cmis:objectId WHERE j.nemaki:jtag IS NULL");
        assertEquals(Set.of("hasToo"), joined, () -> "the JOIN written did not answer the documents of the type — " + lastSent());
        assertEquals(joined, answered("SELECT cmis:document.cmis:objectId FROM cmis:document WHERE "
                + "nemaki:joinable.nemaki:jtag IS NULL"), () -> "a WHERE qualified by the secondary type answered other "
                + "than the JOIN written — " + lastSent());
        assertTrue(filtersSent.get(filtersSent.size() - 1).stream()
                        .anyMatch(fq -> fq.endsWith(" AND (cmis_secondaryObjectTypeIds:nemaki\\:joinable)")),
                () -> "the qualified WHERE was not narrowed to the type as ANY ... IN ('nemaki:joinable') is — " + lastSent());
        assertEquals(joined, answered("SELECT nemaki:joinable.nemaki:jtag FROM cmis:document WHERE nemaki:jtag IS NULL"),
                () -> "a SELECT list qualified by the secondary type answered other than the JOIN written — " + lastSent());
    }

    @Test
    @DisplayName("a qualifier before a space or a newline, or the qualifier of IN_FOLDER, names the row too (R217, over-refusal)")
    void aQualifierAfterWhitespaceOrInAFunctionNamesTheRow() {
        // Only the dot right after the type's name was read, so the server added no JOIN for these when the FROM's type
        // defines the property too, and the parse failed on the qualifier (closing batch 22 review, Codex P2, subagent
        // P3-1).
        for (String space : List.of(" ", "\n")) {
            assertEquals(Set.of("typedJoined"), answered("SELECT nemaki:withJtag.cmis:objectId FROM nemaki:withJtag WHERE "
                    + "nemaki:joinable" + space + ".nemaki:jtag = 'x'"), () -> "a qualifier before whitespace was not read "
                    + "as the row — " + lastSent());
        }
        assertAnsweredWithOneQuery("SELECT nemaki:withJtag.cmis:objectId FROM nemaki:withJtag WHERE IN_FOLDER(nemaki:joinable, "
                + "'f1') AND nemaki:withJtag.nemaki:jtag = 'x'");
        assertTrue(filtersSent.get(filtersSent.size() - 1).stream()
                        .anyMatch(fq -> fq.endsWith(" AND (cmis_secondaryObjectTypeIds:nemaki\\:joinable)")),
                () -> "the qualifier of IN_FOLDER was not read as the row — " + lastSent());
    }

    @Test
    @DisplayName("a row named of a secondary type whose subtype a query of it includes is refused, as the JOIN written is (R217)")
    void aRowNamedOfATypeWithSubtypesIsRefused() {
        assertRefusedWithoutAQuery("SELECT cmis:document.cmis:objectId FROM cmis:document WHERE "
                + "nemaki:parentAspect.nemaki:ptag = 'x'");
    }

    @Test
    @DisplayName("a FROM after a newline or a tab keeps the JOIN the server adds for a property (R215, over-refusal)")
    void aFromAfterANewlineOrATabKeepsTheServersJoin() {
        // The server looked for " FROM " with a space on each side, so after any other whitespace it added no JOIN, the
        // property did not resolve and the manual fallback, which cannot answer the WHERE whole, refused the statement
        // (closing batch 19 review, subagent P3-1).
        for (String space : List.of("\n", "\t", "\r\n")) {
            assertEquals(Set.of("has"), answered("SELECT cmis:document.cmis:objectId" + space + "FROM cmis:document WHERE "
                    + "nemaki:jtag = 'x' AND cmis:name = 'a'"), () -> "a FROM after " + space.replace("\n", "LF")
                    .replace("\t", "TAB").replace("\r", "CR") + " changed the answer — " + lastSent());
        }
    }

    /**
     * The documents a query of the forms this processor sends matches: terms, phrases, {@code field:[* TO *]},
     * {@code *:*}, {@code +} and {@code -}, AND and parentheses. A group of MUST_NOT clauses alone matches nothing
     * nested and everything not excluded at the top, as Lucene and Solr read it. A form it does not know is the lock
     * not looking, not a document that fails to match.
     */
    private static final class SentQuery {
        private final String text;
        private int at;

        private SentQuery(String text) {
            this.text = text;
        }

        static boolean matches(String query, Map<String, List<String>> document) {
            SentQuery reader = new SentQuery(query);
            boolean matched = reader.group(document, true);
            jp.aegif.nemaki.util.test.HarnessBroken.unless(reader.at == query.length(),
                    "the lock's reader stopped at " + reader.at + " of the query it was given: " + query);
            return matched;
        }

        private boolean group(Map<String, List<String>> document, boolean top) {
            List<Boolean> must = new ArrayList<>();
            List<Boolean> should = new ArrayList<>();
            List<Boolean> mustNot = new ArrayList<>();
            boolean andBefore = false;
            while (true) {
                skipBlanks();
                if (at >= text.length() || text.charAt(at) == ')') {
                    break;
                }
                if (text.startsWith("AND ", at) || text.startsWith("AND(", at)) {
                    at += 3;
                    // AND makes the clause before it required as well as the one after it.
                    if (!should.isEmpty()) {
                        must.add(should.remove(should.size() - 1));
                    }
                    andBefore = true;
                    continue;
                }
                char occur = text.charAt(at);
                if (occur == '+' || occur == '-') {
                    at++;
                } else {
                    occur = andBefore ? '+' : ' ';
                }
                andBefore = false;
                boolean clause;
                if (text.charAt(at) == '(') {
                    at++;
                    clause = group(document, false);
                    jp.aegif.nemaki.util.test.HarnessBroken.unless(at < text.length() && text.charAt(at) == ')',
                            "the lock's reader found no ) in the query it was given: " + text);
                    at++;
                } else {
                    clause = term(document);
                }
                (occur == '+' ? must : occur == '-' ? mustNot : should).add(clause);
            }
            if (mustNot.contains(true)) {
                return false;
            }
            if (must.isEmpty() && should.isEmpty()) {
                return top;
            }
            return !must.contains(false) && (!must.isEmpty() || should.contains(true));
        }

        private boolean term(Map<String, List<String>> document) {
            if (text.startsWith("*:*", at)) {
                at += 3;
                return true;
            }
            int colon = text.indexOf(':', at);
            jp.aegif.nemaki.util.test.HarnessBroken.unless(colon > at, "the lock's reader found no field at " + at
                    + " of the query it was given: " + text);
            String field = text.substring(at, colon);
            at = colon + 1;
            List<String> values = document.getOrDefault(field, List.of());
            if (text.startsWith("[* TO *]", at)) {
                at += "[* TO *]".length();
                return !values.isEmpty();
            }
            StringBuilder value = new StringBuilder();
            boolean prefix = false;
            if (at < text.length() && text.charAt(at) == '"') {
                // A phrase, its escapes read: the repository's and the type's ids are quoted with : escaped.
                at++;
                while (at < text.length() && text.charAt(at) != '"') {
                    if (text.charAt(at) == '\\' && at + 1 < text.length()) {
                        at++;
                    }
                    value.append(text.charAt(at));
                    at++;
                }
                jp.aegif.nemaki.util.test.HarnessBroken.unless(at < text.length(), "the lock's reader found an open "
                        + "phrase in: " + text);
                at++;
            } else {
                while (at < text.length() && text.charAt(at) != ' ' && text.charAt(at) != ')') {
                    char c = text.charAt(at);
                    if (c == '\\' && at + 1 < text.length()) {
                        value.append(text.charAt(at + 1));
                        at += 2;
                        continue;
                    }
                    if (c == ':') {
                        // An unescaped colon in a term's value: Solr's parser refuses the whole query, a 500.
                        org.junit.jupiter.api.Assertions.fail("Solr cannot parse the query sent — an unescaped : in the "
                                + "value of " + field + ": " + text);
                    }
                    if (c == '*' && (at + 1 == text.length() || text.charAt(at + 1) == ' ' || text.charAt(at + 1) == ')')) {
                        prefix = true;
                        at++;
                        continue;
                    }
                    value.append(c);
                    at++;
                }
            }
            String wanted = value.toString();
            for (String held : values) {
                if (prefix ? held.startsWith(wanted) : held.equals(wanted)) {
                    return true;
                }
            }
            return false;
        }

        private void skipBlanks() {
            while (at < text.length() && text.charAt(at) == ' ') {
                at++;
            }
        }
    }
}
