package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;

/**
 * R214: check-in runs the caller's check of the properties on the new version as it will be stored — the PWC's copy with
 * the latest version's secondary types merged in — and before it writes anything.
 *
 * <p>The check used to read the latest version itself, a second reading of it: a type the latest version gained or lost
 * between the two (a cloud drive push to the checked-out document) was answered by one reading and stored by the other
 * (closing batch 29 review, Codex P2). The PWC's types alone refused a property of a type only the latest version has
 * (closing batch 28 review, both reviewers).
 */
class TheCheckInCheckSeesTheVersionAsStoredTest {

    /** The collaborators checkIn reaches before it writes, as CopyingContentRefusesAMissingRowTest wires them. */
    private static ContentServiceImpl serviceOn(jp.aegif.nemaki.dao.ContentDaoService dao) throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        for (String fieldName : new String[]{"contentDaoService", "helper", "aclDelegate", "solrUtil", "nemakiCachePool"}) {
            Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, fieldName.equals("contentDaoService") ? dao
                    : mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS));
        }
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info = mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn("root-id");
        when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);
        return service;
    }

    private static Document document(String id, String attachmentNodeId, String... secondaryTypes) {
        Document document = new Document();
        document.setId(id);
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setAttachmentNodeId(attachmentNodeId);
        document.setVersionSeriesId("vs-1");
        document.setSubTypeProperties(new ArrayList<>());
        List<Aspect> aspects = new ArrayList<>();
        for (String type : secondaryTypes) {
            aspects.add(new Aspect(type, new ArrayList<>()));
        }
        document.setAspects(aspects);
        document.setSecondaryIds(new ArrayList<>(List.of(secondaryTypes)));
        return document;
    }

    /** What the check was given, and the refusal it answered so that nothing after it runs. */
    private static final class Refused extends RuntimeException {
        private Refused() {
            super("the caller's check refused");
        }
    }

    @Test
    @DisplayName("the check sees the PWC's types and the latest version's, and runs before anything is written (R214)")
    void theCheckSeesTheNewVersionBeforeAnythingIsWritten() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Document pwc = document("pwc-1", "att-9", "nemaki:own");
        pwc.setPrivateWorkingCopy(true);
        when(dao.getDocument("bedroom", "pwc-1")).thenReturn(pwc);
        when(dao.getDocumentOfLatestVersion("bedroom", "vs-1")).thenReturn(document("doc-1", "att-8", "nemaki:latest"));
        ContentServiceImpl service = serviceOn(dao);

        Set<String> seen = new TreeSet<>();
        assertThrows(Refused.class, () -> service.checkIn(mock(CallContext.class), "bedroom", new Holder<>("pwc-1"),
                Boolean.TRUE, null, null, "comment", null, null, null, null, newVersion -> {
                    for (Aspect aspect : newVersion.getAspects()) {
                        seen.add(aspect.getName());
                    }
                    throw new Refused();
                }), "check-in did not run the caller's check");
        assertEquals(Set.of("nemaki:latest", "nemaki:own"), seen,
                "the check was not given the new version's types — the PWC's with the latest version's merged in");
        // Refused before anything is written: no attachment copied, no version created.
        verify(dao, never()).create(anyString(), any(Document.class));
        verify(dao, never()).getAttachment(anyString(), anyString());
        // And the PWC as it was: the merge wrote the latest version's types onto the PWC's own list, which stays in the
        // content cache (closing batch 30 review, subagent P2-1).
        assertEquals(List.of("nemaki:own"), pwc.getSecondaryIds(), "a refused check-in left the latest version's types on "
                + "the PWC's secondaryIds");
        assertEquals(List.of("nemaki:own"), pwc.getAspects().stream().map(Aspect::getName).toList(),
                "a refused check-in left the latest version's aspects on the PWC");
    }

    @Test
    @DisplayName("with a content stream too, the check runs before the journal row opens and the attachment is made (R214)")
    void theCheckRunsBeforeTheContentIsWritten() throws Exception {
        // The arm without a content stream copies the PWC's attachment; this one opens the content-write row and makes a new
        // attachment, and both come after the check (closing batch 30 review, Codex P3).
        jp.aegif.nemaki.dao.ContentDaoService dao = mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Document pwc = document("pwc-1", "att-9", "nemaki:own");
        pwc.setPrivateWorkingCopy(true);
        when(dao.getDocument("bedroom", "pwc-1")).thenReturn(pwc);
        when(dao.getDocumentOfLatestVersion("bedroom", "vs-1")).thenReturn(document("doc-1", "att-8"));
        ContentServiceImpl service = serviceOn(dao);
        jp.aegif.nemaki.evidence.RecordContentStateRecorder recorder =
                mock(jp.aegif.nemaki.evidence.RecordContentStateRecorder.class);
        Field recordContentState = ContentServiceImpl.class.getDeclaredField("recordContentState");
        recordContentState.setAccessible(true);
        recordContentState.set(service, recorder);
        org.apache.chemistry.opencmis.commons.data.ContentStream bytes =
                mock(org.apache.chemistry.opencmis.commons.data.ContentStream.class);
        assertThrows(Refused.class, () -> service.checkIn(mock(CallContext.class), "bedroom", new Holder<>("pwc-1"),
                Boolean.TRUE, null, bytes, "comment", null, null, null, null, newVersion -> {
                    throw new Refused();
                }), "check-in with a content stream did not run the caller's check");
        verify(recorder, never()).openBeforeWriting(anyString(), anyString(), any(), any(), anyString());
        verify(dao, never()).create(anyString(), any(Document.class));
        verify(bytes, never()).getStream();
    }

    @Test
    @DisplayName("the store does not take a type it could not read for one that is not there (R214)")
    void aTypeTheStoreCannotReadIsNotDropped() throws Exception {
        // buildSecondaryTypes caught every exception and dropped the aspect, every value it held with it, and the update
        // was answered as done (closing batch 30 review, Codex P2).
        jp.aegif.nemaki.dao.ContentDaoService dao = mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ContentServiceImpl service = serviceOn(dao);
        jp.aegif.nemaki.cmis.aspect.type.TypeManager types = mock(jp.aegif.nemaki.cmis.aspect.type.TypeManager.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(types.getTypeDefinition("bedroom", "nemaki:unreadable")).thenThrow(
                new IllegalStateException("the type definitions could not be read"));
        Field typeManager = ContentServiceImpl.class.getDeclaredField("typeManager");
        typeManager.setAccessible(true);
        typeManager.set(service, types);
        Document document = document("doc-1", null);
        org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl properties =
                new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl();
        properties.addProperty(new org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl(
                "cmis:secondaryObjectTypeIds", List.of("nemaki:unreadable")));
        assertThrows(IllegalStateException.class, () -> service.updateProperties(mock(CallContext.class), "bedroom",
                properties, document), "the store answered a type it could not read as an absent type");
        verify(dao, never()).update(anyString(), any(Document.class));
    }
}
