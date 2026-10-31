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
    }
}
