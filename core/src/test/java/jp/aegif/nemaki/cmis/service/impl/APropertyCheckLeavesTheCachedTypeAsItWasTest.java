package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * R214: the property check of a create, an update and a copy leaves the type it is given — the type cache's own
 * instance — as it was, and answers the same whatever came before it.
 *
 * <p>The check put each secondary type's properties a request named into the base type's map, which was the cache's,
 * not a copy. From the first create with {@code nemaki:commentable}, {@code cmis:document}'s definition — AtomPub and
 * Browser alike — said every document had {@code nemaki:comment}, until the cache was rebuilt; the CMIS TCK's 13
 * 「Property nemaki:comment」 failures were this (closing batch 3). And the check leaned on it: an update of
 * {@code nemaki:comment} alone, on a document that has the type, was refused after a start (400, 「An undefined
 * property is provided!」) and allowed once some create had put the property there (measured on bedroom, closing
 * batch 27). The store keeps the types the object has when a request lists none ({@code buildSecondaryTypes}), so the
 * check reads them by the same rule.
 */
class APropertyCheckLeavesTheCachedTypeAsItWasTest {

    private static final String REPO = "bedroom";
    private static final String COMMENTABLE = "nemaki:commentable";
    private static final String EVIDENCE = "nemaki:externalIntegration";

    private TypeManager types;
    private ExceptionServiceImpl check;
    private DocumentTypeDefinitionImpl cachedDocumentType;
    private Set<String> itsPropertiesAtTheStart;

    @BeforeEach
    void setUp() {
        cachedDocumentType = new DocumentTypeDefinitionImpl();
        cachedDocumentType.setId("cmis:document");
        cachedDocumentType.setQueryName("cmis:document");
        cachedDocumentType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        cachedDocumentType.setIsVersionable(false);
        cachedDocumentType.setPropertyDefinitions(new LinkedHashMap<>());
        cachedDocumentType.addPropertyDefinition(string(PropertyIds.NAME));
        cachedDocumentType.addPropertyDefinition(id(PropertyIds.OBJECT_TYPE_ID, Cardinality.SINGLE));
        cachedDocumentType.addPropertyDefinition(id(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, Cardinality.MULTI));
        itsPropertiesAtTheStart = new LinkedHashSet<>(cachedDocumentType.getPropertyDefinitions().keySet());

        types = mock(TypeManager.class);
        when(types.getTypeById(REPO, COMMENTABLE)).thenReturn(container(secondary(COMMENTABLE, "nemaki:comment")));
        when(types.getTypeById(REPO, EVIDENCE)).thenReturn(container(secondary(EVIDENCE, "nemaki:sourceSystem")));
        when(types.getTypeById(REPO, "nemaki:gone")).thenReturn(null);
        when(types.getTypeDefinition(REPO, "cmis:document")).thenReturn(cachedDocumentType);
        when(types.getTypeDefinition(eq(REPO), any(Content.class))).thenReturn(cachedDocumentType);

        check = new ExceptionServiceImpl();
        check.setTypeManager(types);
    }

    @Test
    @DisplayName("a create with a secondary type and its property leaves the base type's definition as it was (R214)")
    void aCreateWithASecondaryTypeLeavesTheBaseTypeAsItWas() {
        assertDoesNotThrow(() -> check.constraintPropertyValue(REPO, cachedDocumentType, aCreate(COMMENTABLE,
                "nemaki:comment"), null), "a create that names the secondary type and its property was refused");
        assertEquals(itsPropertiesAtTheStart, cachedDocumentType.getPropertyDefinitions().keySet(),
                "the check put the secondary type's property into the cached base type, which then says every document "
                        + "has it");
    }

    @Test
    @DisplayName("an update that lists no secondary types is answered the same before and after a create (R214)")
    void anUpdateIsAnsweredTheSameBeforeAndAfterACreate() {
        // The object has no secondary type here, so nemaki:comment is no property of it — before a create and after one.
        assertThrows(CmisConstraintException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                anUpdate("nemaki:comment"), "doc", List.of()), "a property of a type the object does not have was allowed");
        check.constraintPropertyValue(REPO, cachedDocumentType, aCreate(COMMENTABLE, "nemaki:comment"), null);
        assertThrows(CmisConstraintException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                anUpdate("nemaki:comment"), "doc", List.of()), "after a create with the secondary type, the same update "
                + "was allowed — the answer came from what an earlier request had put into the cached type");
    }

    @Test
    @DisplayName("an update may name a property of a secondary type the object has without listing the types (R214)")
    void anUpdateMayNameAPropertyOfASecondaryTypeTheObjectHas() {
        assertDoesNotThrow(() -> check.constraintPropertyValue(REPO, cachedDocumentType, anUpdate("nemaki:comment"),
                "doc", List.of(COMMENTABLE)), "an update of a property of a secondary type the object has was refused "
                + "because the request did not list the types — the store keeps them");
        assertEquals(itsPropertiesAtTheStart, cachedDocumentType.getPropertyDefinitions().keySet(),
                "the update put the property into the cached base type");
    }

    @Test
    @DisplayName("a list of secondary types the request carries is the one read, not the types the object has (R214)")
    void aListTheRequestCarriesIsTheOneRead() {
        PropertiesImpl dropsIt = anUpdate("nemaki:comment");
        dropsIt.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, new ArrayList<String>()));
        assertThrows(CmisConstraintException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                dropsIt, "doc", List.of(COMMENTABLE)), "a property of a type the request takes away was allowed");
    }

    @Test
    @DisplayName("a secondary type the object has that the repository no longer defines allows none of its properties (R214)")
    void aTypeTheObjectHasThatIsGoneAllowsNoneOfItsProperties() {
        assertThrows(CmisConstraintException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                anUpdate("nemaki:comment"), "doc", List.of("nemaki:gone")),
                "a type the repository cannot read was not answered as the check's refusal");
    }

    @Test
    @DisplayName("updateProperties reads the secondary types the object has (R214)")
    void theUpdateEntryReadsTheTypesTheObjectHas() {
        ObjectServiceImpl service = serviceWithTheRealCheck();
        Document document = document("doc", COMMENTABLE);
        when(contentService.getContent(REPO, "doc")).thenReturn(document);
        when(contentService.updateProperties(any(), eq(REPO), any(), any())).thenReturn(document);
        assertDoesNotThrow(() -> service.updateProperties(callContext, REPO, new Holder<>("doc"),
                anUpdate("nemaki:comment"), null, null), "updateProperties refused a property of a secondary type the "
                + "object has — the check was not given the object's types");
        assertEquals(itsPropertiesAtTheStart, cachedDocumentType.getPropertyDefinitions().keySet(),
                "updateProperties left the property in the cached base type");
    }

    @Test
    @DisplayName("createDocumentFromSource reads the secondary types the copy keeps — not the evidence types it drops (R214)")
    void theCopyEntryReadsTheTypesTheCopyKeeps() {
        ObjectServiceImpl service = serviceWithTheRealCheck();
        Document original = document("source", COMMENTABLE, EVIDENCE);
        Folder folder = new Folder();
        folder.setId("folder");
        Document copy = document("copy");
        when(contentService.getDocument(REPO, "source")).thenReturn(original);
        when(contentService.getFolder(REPO, "folder")).thenReturn(folder);
        when(contentService.createDocumentFromSource(any(), eq(REPO), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(copy);
        assertDoesNotThrow(() -> service.createDocumentFromSource(callContext, REPO, "source", anUpdate("nemaki:comment"),
                "folder", null, null, null, null, null), "a copy refused a property of a secondary type it keeps from "
                + "the source");
        // The copy does not carry the source's evidence types (ContentServiceImpl.stripEvidenceForNewObject), so the store
        // would drop a value for one of their properties: the check refuses it.
        assertThrows(CmisConstraintException.class, () -> service.createDocumentFromSource(callContext, REPO, "source",
                anUpdate("nemaki:sourceSystem"), "folder", null, null, null, null, null),
                "a copy allowed a property of an evidence type it does not carry");
        assertEquals(itsPropertiesAtTheStart, cachedDocumentType.getPropertyDefinitions().keySet(),
                "the copy left a property in the cached base type");
    }

    private ContentService contentService;
    private CallContext callContext;

    /** ObjectServiceImpl with every collaborator mocked except the property check, which is the real one. */
    private ObjectServiceImpl serviceWithTheRealCheck() {
        contentService = mock(ContentService.class);
        callContext = mock(CallContext.class);
        ExceptionService exceptions = mock(ExceptionService.class);
        doAnswer(call -> {
            check.constraintPropertyValue((String) call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    (String) call.getArgument(3));
            return null;
        }).when(exceptions).constraintPropertyValue(any(), any(), any(), any());
        doAnswer(call -> {
            check.constraintPropertyValue((String) call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    (String) call.getArgument(3), call.getArgument(4));
            return null;
        }).when(exceptions).constraintPropertyValue(any(), any(), any(), any(), any());
        NemakiCachePool caches = mock(NemakiCachePool.class);
        when(caches.get(REPO)).thenReturn(mock(CacheService.class, RETURNS_DEEP_STUBS));
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(anyString(), anyString())).thenAnswer(call -> new ReentrantLock());

        ObjectServiceImpl service = new ObjectServiceImpl();
        service.setContentService(contentService);
        service.setExceptionService(exceptions);
        service.setTypeManager(types);
        service.setNemakiCachePool(caches);
        service.setThreadLockService(locks);
        return service;
    }

    private static Document document(String id, String... secondaryTypes) {
        Document document = new Document();
        document.setId(id);
        document.setObjectType("cmis:document");
        List<Aspect> aspects = new ArrayList<>();
        for (String type : secondaryTypes) {
            aspects.add(new Aspect(type, new ArrayList<>()));
        }
        document.setAspects(aspects);
        document.setSecondaryIds(List.of(secondaryTypes));
        return document;
    }

    private static PropertiesImpl aCreate(String secondaryType, String itsProperty) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "a"));
        properties.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of(secondaryType)));
        properties.addProperty(new PropertyStringImpl(itsProperty, "x"));
        return properties;
    }

    /** Only the one property: the request lists no secondary types. */
    private static PropertiesImpl anUpdate(String property) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl(property, "y"));
        return properties;
    }

    private static SecondaryTypeDefinitionImpl secondary(String id, String property) {
        SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
        type.setId(id);
        type.setQueryName(id);
        type.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        type.setPropertyDefinitions(new LinkedHashMap<>());
        type.addPropertyDefinition(string(property));
        return type;
    }

    private static TypeDefinitionContainerImpl container(SecondaryTypeDefinitionImpl type) {
        return new TypeDefinitionContainerImpl(type);
    }

    private static PropertyStringDefinitionImpl string(String id) {
        return defined(new PropertyStringDefinitionImpl(), id, PropertyType.STRING, Cardinality.SINGLE);
    }

    private static PropertyIdDefinitionImpl id(String id, Cardinality cardinality) {
        return defined(new PropertyIdDefinitionImpl(), id, PropertyType.ID, cardinality);
    }

    private static <D extends AbstractPropertyDefinition<?>> D defined(D definition, String id, PropertyType type,
            Cardinality cardinality) {
        definition.setId(id);
        definition.setQueryName(id);
        definition.setLocalName(id);
        definition.setPropertyType(type);
        definition.setCardinality(cardinality);
        definition.setUpdatability(Updatability.READWRITE);
        definition.setIsRequired(false);
        return definition;
    }
}
