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
package jp.aegif.nemaki.businesslogic;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A new version must not share its evidence aspects with the old one (P1-1(d) D3, AC7).
 *
 * <h2>The hole</h2>
 *
 * <p>{@code buildCopyDocument} handed every copy — checkOut's PWC, checkIn's new version, a
 * plain copy, and {@code updateWithoutCheckInOut}'s version — the SAME {@code Aspect} objects.
 * The default checkIn path is rescued by an accident ({@code cancelCheckOut} re-reads every
 * version and replaces the cached instances), but {@code BulkCheckInResource} goes through
 * {@code updateWithoutCheckInOut}, which has no such rescue: an in-place fill on the new
 * version then rewrites the OLD version's cached evidence, and per-version metadata hashes
 * compare against bytes that are no longer independent (external review).
 *
 * <p>The test drives {@code buildCopyDocument} itself — the single shared producer of every
 * version copy — rather than one of its four callers, so all four are covered by construction.
 */
class EvidenceAspectsAreNotSharedAcrossVersionsTest {

    private static final String CHAT = "nemaki:chatContextMetadata";
    private static final String ORDINARY = "nemaki:comment";

    private static Aspect aspect(String name, String key, String value) {
        Aspect a = new Aspect();
        a.setName(name);
        a.setProperties(new ArrayList<>(List.of(new Property(key, value))));
        return a;
    }

    private static Document copyOf(Document original) throws Exception {
        // buildCopyDocument's ACL step asks the repository info for the root folder id; a mock
        // map keeps the REAL copy logic running rather than replacing it with a stub.
        ContentServiceImpl service = new ContentServiceImpl();
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        org.mockito.Mockito.when(info.getRootFolderId()).thenReturn("root-id");
        org.mockito.Mockito.when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);
        // The copy also consults the ACL delegate for inheritance; a lenient mock keeps the
        // real copy logic running. Injected by reflection — production builds it lazily from
        // four other collaborators this test does not need.
        for (String fieldName : new String[]{"aclDelegate", "helper"}) {
            java.lang.reflect.Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, mock(f.getType()));
        }

        Method m = ContentServiceImpl.class.getDeclaredMethod("buildCopyDocument",
                org.apache.chemistry.opencmis.commons.server.CallContext.class, String.class,
                Document.class,
                org.apache.chemistry.opencmis.commons.data.Acl.class,
                org.apache.chemistry.opencmis.commons.data.Acl.class);
        m.setAccessible(true);
        return (Document) m.invoke(service,
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class),
                "bedroom", original, null, null);
    }

    private static Document original() {
        Document doc = new Document();
        doc.setId("v1");
        doc.setType("cmis:document");
        doc.setAspects(new ArrayList<>(List.of(
                aspect(CHAT, "nemaki:chatChannelId", "C-CAPTURED"),
                aspect(ORDINARY, "nemaki:commentText", "hello"))));
        doc.setSubTypeProperties(new ArrayList<>());
        return doc;
    }

    @Test
    @DisplayName("mutating the copy's evidence does not reach the original")
    void evidenceMutationDoesNotCrossVersions() throws Exception {
        Document v1 = original();
        Document v2 = copyOf(v1);

        // The in-place mutation a later fill performs on the new version.
        v2.getAspects().stream().filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow()
                .getProperties().get(0).setValue("C-REWRITTEN");

        String v1Value = String.valueOf(v1.getAspects().stream()
                .filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow()
                .getProperties().get(0).getValue());
        assertEquals("C-CAPTURED", v1Value,
                "a fill on the new version rewrote the OLD version's evidence — the versions "
                        + "share the same Aspect object and per-version hashes are fiction");
    }

    @Test
    @DisplayName("the aspect LIST is fresh too — adding to the copy does not grow the original")
    void theListItselfIsNotShared() throws Exception {
        Document v1 = original();
        int before = v1.getAspects().size();
        Document v2 = copyOf(v1);

        Aspect added = new Aspect();
        added.setName("nemaki:noteMetadata");
        added.setProperties(new ArrayList<>());
        v2.getAspects().add(added);

        assertEquals(before, v1.getAspects().size(),
                "mergeSecondaryTypesFromLatest adding to the new version's list grew the old "
                        + "version's list — they are the same object");
    }

    @Test
    @DisplayName("a MUTABLE value (multi-value list) is not aliased between versions")
    void mutableValuesAreNotAliased() throws Exception {
        // Fresh Property objects were not enough: the first copy passed p.getValue() through,
        // so a shared List (or a Calendar from a cache hit) still bridged the versions — an
        // add() through one edited the other's evidence in memory (review of the batch).
        Document v1 = original();
        java.util.List<String> participants = new ArrayList<>(List.of("otsuka"));
        v1.getAspects().stream().filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow()
                .getProperties().add(new Property("nemaki:chatParticipants", participants));

        Document v2 = copyOf(v1);
        Object v2Value = v2.getAspects().stream()
                .filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow()
                .getProperties().stream()
                .filter(p -> "nemaki:chatParticipants".equals(p.getKey()))
                .findFirst().orElseThrow().getValue();
        ((java.util.List<String>) v2Value).add("intruder");

        assertEquals(List.of("otsuka"), participants,
                "adding a participant through the copy grew the ORIGINAL version's list — the "
                        + "Property objects differ but the value object is the same");
    }

    @Test
    @DisplayName("ordinary aspects stay shared — the deliberate scope limit")
    void ordinaryAspectsStayShared() throws Exception {
        // Copying everything would be a broader behaviour change than D3 needs; the protected
        // set is where shared bytes become shared EVIDENCE. Stated by a test so the next person
        // widening or narrowing the scope does it on purpose.
        Document v1 = original();
        Document v2 = copyOf(v1);

        Aspect v1Ordinary = v1.getAspects().stream()
                .filter(a -> ORDINARY.equals(a.getName())).findFirst().orElseThrow();
        Aspect v2Ordinary = v2.getAspects().stream()
                .filter(a -> ORDINARY.equals(a.getName())).findFirst().orElseThrow();
        Aspect v2Chat = v2.getAspects().stream()
                .filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow();
        Aspect v1Chat = v1.getAspects().stream()
                .filter(a -> CHAT.equals(a.getName())).findFirst().orElseThrow();

        assertSame(v1Ordinary, v2Ordinary, "the scope widened silently");
        assertNotSame(v1Chat, v2Chat, "the evidence aspect is still the same object");
        assertTrue(EvidenceTypes.isProtected(CHAT));
    }
}
