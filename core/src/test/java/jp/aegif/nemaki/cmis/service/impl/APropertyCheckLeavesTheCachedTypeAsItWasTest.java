package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
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
        SecondaryTypeDefinitionImpl commentable = secondary(COMMENTABLE, "nemaki:comment");
        SecondaryTypeDefinitionImpl evidence = secondary(EVIDENCE, "nemaki:sourceSystem");
        when(types.getTypeById(REPO, COMMENTABLE)).thenReturn(container(commentable));
        when(types.getTypeById(REPO, EVIDENCE)).thenReturn(container(evidence));
        when(types.getTypeById(REPO, "nemaki:gone")).thenReturn(null);
        // The store's lookup, which reads the types again when the cache does not have one: the check asks it too.
        when(types.getTypeDefinition(REPO, COMMENTABLE)).thenReturn(commentable);
        when(types.getTypeDefinition(REPO, EVIDENCE)).thenReturn(evidence);
        // A type another replica made: this replica's cache does not have it, the store's lookup reads it again.
        when(types.getTypeById(REPO, "nemaki:fresh")).thenReturn(null);
        when(types.getTypeDefinition(REPO, "nemaki:fresh")).thenReturn(secondary("nemaki:fresh", "nemaki:freshNote"));
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
        // And it is a check: a property of a type the object does not have is still refused at the entry (closing batch
        // 27 review, subagent P3-3 — the entry's lock had no arm that refuses, so dropping the call stayed green).
        Document plain = document("plain");
        when(contentService.getContent(REPO, "plain")).thenReturn(plain);
        assertThrows(CmisConstraintException.class, () -> service.updateProperties(callContext, REPO,
                new Holder<>("plain"), anUpdate("nemaki:comment"), null, null),
                "updateProperties allowed a property no type of the object defines");
    }

    @Test
    @DisplayName("the types an object has are its aspects, which the store reads, not its secondaryIds (R214)")
    void theTypesAnObjectHasAreItsAspects() {
        ObjectServiceImpl service = serviceWithTheRealCheck();
        // The two disagree here so that reading the other one is seen (closing batch 27 review, subagent P3-4).
        Document document = document("doc", COMMENTABLE);
        document.setSecondaryIds(List.of());
        when(contentService.getContent(REPO, "doc")).thenReturn(document);
        when(contentService.updateProperties(any(), eq(REPO), any(), any())).thenReturn(document);
        assertDoesNotThrow(() -> service.updateProperties(callContext, REPO, new Holder<>("doc"),
                anUpdate("nemaki:comment"), null, null), "the check read the object's secondaryIds, not the aspects the "
                + "store reads");
        Document stale = document("stale");
        stale.setSecondaryIds(List.of(COMMENTABLE));
        when(contentService.getContent(REPO, "stale")).thenReturn(stale);
        assertThrows(CmisConstraintException.class, () -> service.updateProperties(callContext, REPO,
                new Holder<>("stale"), anUpdate("nemaki:comment"), null, null),
                "the check allowed a property of a type the object names only in secondaryIds — the store keeps no aspect");
    }

    @Test
    @DisplayName("a list of secondary types is read as the store reads it, whatever type the property came as (R214)")
    void aListIsReadAsTheStoreReadsIt() {
        // REST v1's default property type is string: the list was no list for the check (DataUtil.getIdListProperty
        // answers null for it) and a list for the store (closing batch 27 review, subagent P3-1).
        PropertiesImpl asStrings = anUpdate("nemaki:comment");
        asStrings.addProperty(new PropertyStringImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of(COMMENTABLE)));
        assertDoesNotThrow(() -> check.constraintPropertyValue(REPO, cachedDocumentType, asStrings, "doc", List.of()),
                "a list sent as a string property was not read");
        // The Browser binding sends an empty list as one empty string, and the store skips a blank id: it is the request
        // that takes every type away (measured on bedroom, closing batch 28 — refused as an unknown type before this).
        PropertiesImpl takesAllAway = new PropertiesImpl();
        takesAllAway.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of("")));
        assertDoesNotThrow(() -> check.constraintPropertyValue(REPO, cachedDocumentType, takesAllAway, "doc",
                List.of(COMMENTABLE)), "a list of one empty string was refused as a type the repository does not define");
        PropertiesImpl takesAllAwayWithAValue = anUpdate("nemaki:comment");
        takesAllAwayWithAValue.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of("")));
        assertThrows(CmisConstraintException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                takesAllAwayWithAValue, "doc", List.of(COMMENTABLE)), "a property of a type a list of one empty string "
                + "takes away was not refused as the property it is");
        PropertiesImpl names = anUpdate("nemaki:comment");
        names.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of("nemaki:gone")));
        assertThrows(CmisInvalidArgumentException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                names, "doc", List.of()), "a type the request names that the repository does not define was not "
                + "answered as the client's error");
    }

    @Test
    @DisplayName("bulkUpdateProperties checks the properties with the add and remove lists folded in, as they are stored (R214)")
    void theBulkEntryChecksThePropertiesAsTheyAreStored() {
        ObjectServiceImpl service = serviceWithTheRealCheck();
        Document plain = document("plain");
        Document holding = document("holding", COMMENTABLE);
        holding.setSecondaryIds(List.of(COMMENTABLE));
        when(contentService.getContent(REPO, "plain")).thenReturn(plain);
        when(contentService.getContent(REPO, "holding")).thenReturn(holding);
        when(contentService.updateProperties(any(), eq(REPO), any(), any())).thenAnswer(call -> call.getArgument(3));
        // Adding the type with a value of its property: stored as asked, so it is allowed — it was refused, the object
        // missing from the result (measured on bedroom, closing batch 27's WAR).
        List<BulkUpdateObjectIdAndChangeToken> added = service.bulkUpdateProperties(callContext, REPO,
                List.of(new BulkUpdateObjectIdAndChangeTokenImpl("plain", null)), anUpdate("nemaki:comment"),
                List.of(COMMENTABLE), null, null);
        // The list holds the objects updated: a refused object's task answers null, and the list kept it (R225 — the
        // REST v1 resource read every entry and answered the whole request with a 500).
        assertEquals(1, added.size(), "a bulk update that adds the type and sets its property was refused");
        assertFalse(added.contains(null), "a refused object's null is in the list of objects updated");
        // Removing the type with a value of its property: the store drops the value, so it is refused — it was let
        // through and reported as done.
        List<BulkUpdateObjectIdAndChangeToken> removed = service.bulkUpdateProperties(callContext, REPO,
                List.of(new BulkUpdateObjectIdAndChangeTokenImpl("holding", null)), anUpdate("nemaki:comment"), null,
                List.of(COMMENTABLE), null);
        assertEquals(List.of(), removed, "a bulk update that removes the type and sets its property was reported as done, "
                + "or its refusal left a null in the list");
        // No properties at all: nothing to check, the add is done (the guard that skips the check).
        Document another = document("another");
        when(contentService.getContent(REPO, "another")).thenReturn(another);
        assertEquals(1, service.bulkUpdateProperties(callContext, REPO,
                List.of(new BulkUpdateObjectIdAndChangeTokenImpl("another", null)), null, List.of(COMMENTABLE), null, null)
                .size(), "a bulk update that only adds a type was refused");
    }

    @Test
    @DisplayName("checkIn checks the properties of the new version as an update does (R214)")
    void theCheckInEntryChecksTheProperties() {
        VersioningServiceImpl service = versioningWithTheRealCheck();
        Document holding = pwc("holding", COMMENTABLE);
        Document plain = pwc("plain");
        when(contentService.getDocument(REPO, "holding")).thenReturn(holding);
        when(contentService.getDocument(REPO, "plain")).thenReturn(plain);
        when(contentService.checkIn(any(), eq(REPO), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(document("version"));
        assertDoesNotThrow(() -> service.checkIn(callContext, REPO, new Holder<>("holding"), true,
                anUpdate("nemaki:comment"), null, null, null, null, null, null),
                "checkIn refused a property of a secondary type the PWC has");
        assertThrows(CmisConstraintException.class, () -> service.checkIn(callContext, REPO, new Holder<>("plain"), true,
                anUpdate("nemaki:comment"), null, null, null, null, null, null),
                "checkIn let a property no type of the document defines through — the store ignores it");
        PropertiesImpl dropsIt = anUpdate("nemaki:comment");
        dropsIt.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, new ArrayList<String>()));
        assertThrows(CmisConstraintException.class, () -> service.checkIn(callContext, REPO, new Holder<>("holding"),
                true, dropsIt, null, null, null, null, null, null),
                "checkIn let a property of a type the request takes away through — the store drops it");
        // No properties at all: nothing to check (the ingest, OData and an empty REST v1 check-in send none).
        assertDoesNotThrow(() -> service.checkIn(callContext, REPO, new Holder<>("plain"), true, null, null, null, null,
                null, null, null), "a check-in without properties was refused or failed");
    }

    @Test
    @DisplayName("checkIn reads the secondary types the latest version has as well, as the store merges them in (R214)")
    void theCheckInEntryReadsTheTypesTheLatestVersionHas() {
        // ContentServiceImpl.checkIn adds the latest version's aspects the PWC lacks (mergeSecondaryTypesFromLatest) before
        // it stores the properties — a document pushed to a cloud drive while checked out has one there (closing batch 28
        // review, both reviewers).
        VersioningServiceImpl service = versioningWithTheRealCheck();
        Document pwc = pwc("pwc");
        when(contentService.getDocument(REPO, "pwc")).thenReturn(pwc);
        when(contentService.getDocumentOfLatestVersion(REPO, pwc.getVersionSeriesId()))
                .thenReturn(document("latest", COMMENTABLE));
        when(contentService.checkIn(any(), eq(REPO), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(document("version"));
        assertDoesNotThrow(() -> service.checkIn(callContext, REPO, new Holder<>("pwc"), true,
                anUpdate("nemaki:comment"), null, null, null, null, null, null),
                "checkIn refused a property of a type the latest version has, which the store merges into the new version");
    }

    @Test
    @DisplayName("a type is looked up as the store looks it up, and only a secondary type is one (R214)")
    void aTypeIsLookedUpAsTheStoreLooksItUp() {
        // The cache alone answered «no such type» for one another replica had just made; the store reads the types again
        // (closing batch 28 review, subagent P3-1).
        PropertiesImpl fresh = new PropertiesImpl();
        fresh.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of("nemaki:fresh")));
        fresh.addProperty(new PropertyStringImpl("nemaki:freshNote", "n"));
        assertDoesNotThrow(() -> check.constraintPropertyValue(REPO, cachedDocumentType, fresh, "doc", List.of()),
                "a type the cache did not have but the store's lookup reads was refused");
        // A base type in the list is no secondary type: the store keeps no aspect for it (subagent P3-2).
        PropertiesImpl notSecondary = new PropertiesImpl();
        notSecondary.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of("cmis:document")));
        assertThrows(CmisInvalidArgumentException.class, () -> check.constraintPropertyValue(REPO, cachedDocumentType,
                notSecondary, "doc", List.of()), "a type that is not a secondary type was read as one");
        // A type the object names already that the repository no longer defines is not the client's error — a bulk update
        // folds the object's own list in.
        PropertiesImpl folded = anUpdate("nemaki:comment");
        folded.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, List.of("nemaki:gone", COMMENTABLE)));
        assertDoesNotThrow(() -> check.constraintPropertyValue(REPO, cachedDocumentType, folded, "doc",
                List.of("nemaki:gone")), "a type the object has that the repository no longer defines was answered as the client's error");
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

    /** VersioningServiceImpl with every collaborator mocked except the property check, which is the real one. */
    private VersioningServiceImpl versioningWithTheRealCheck() {
        serviceWithTheRealCheck();
        VersioningServiceImpl service = new VersioningServiceImpl();
        service.setContentService(contentService);
        service.setExceptionService(exceptions);
        service.setTypeManager(types);
        service.setNemakiCachePool(caches);
        service.setThreadLockService(locks);
        return service;
    }

    private ExceptionService exceptions;
    private NemakiCachePool caches;
    private ThreadLockService locks;

    /** ObjectServiceImpl with every collaborator mocked except the property check, which is the real one. */
    private ObjectServiceImpl serviceWithTheRealCheck() {
        contentService = mock(ContentService.class);
        callContext = mock(CallContext.class);
        exceptions = mock(ExceptionService.class);
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
        caches = mock(NemakiCachePool.class);
        when(caches.get(REPO)).thenReturn(mock(CacheService.class, RETURNS_DEEP_STUBS));
        locks = mock(ThreadLockService.class);
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
        // secondaryIds left empty: the types an object has are read from its aspects, as the store reads them, and a
        // fixture that set both alike would not see a reader of the other (closing batch 27 review, subagent P3-4).
        document.setSecondaryIds(new ArrayList<>());
        return document;
    }

    private static Document pwc(String id, String... secondaryTypes) {
        Document pwc = document(id, secondaryTypes);
        pwc.setPrivateWorkingCopy(true);
        pwc.setVersionSeriesId("series-" + id);
        return pwc;
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
