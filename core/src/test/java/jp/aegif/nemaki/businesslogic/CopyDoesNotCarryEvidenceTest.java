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
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;

import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CMIS copy does not carry capture evidence (evidence-types §0, 「複製の偽証拠」).
 *
 * <p>{@code createDocumentFromSource} mints a NEW object id and a NEW version series; the
 * capture ledger rows stand behind the ORIGINAL's id. Before this, the copy carried the
 * evidence aspects — asserting a capture that never happened to the new object — and
 * {@code keepEvidenceAspects} then refused to ever detach them. The split mirrors the zip
 * import (D-6): identity-preserving copies keep evidence, identity-minting copies must not.
 */
class CopyDoesNotCarryEvidenceTest {

    private static Aspect aspect(String name, String key, String value) {
        Aspect a = new Aspect();
        a.setName(name);
        a.setProperties(new ArrayList<>(List.of(new Property(key, value))));
        return a;
    }

    @Test
    @DisplayName("createDocumentFromSource strips evidence; ordinary aspects survive")
    @SuppressWarnings("unchecked")
    void fromSourceCopyIsStripped() throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        jp.aegif.nemaki.dao.ContentDaoService dao = mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Document[] captured = new Document[1];
        when(dao.create(eq("bedroom"), any(Document.class))).thenAnswer(inv -> {
            captured[0] = inv.getArgument(1);
            Document created = inv.getArgument(1);
            created.setId("new-1");
            return created;
        });
        for (String fieldName : new String[]{"contentDaoService", "aclDelegate", "helper",
                "solrUtil"}) {
            try {
                Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(service, fieldName.equals("contentDaoService") ? dao
                        : mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS));
            } catch (NoSuchFieldException ignored) {
                // Not every collaborator exists under these names in every revision; the ones
                // that do are enough to reach PHASE 4 (the create).
            }
        }
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn("root-id");
        when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);

        Document original = new Document();
        original.setId("orig-1");
        original.setType("cmis:document");
        original.setObjectType("cmis:document");
        original.setName("doc");
        List<String> originalSecondaryIds = new ArrayList<>(List.of(
                "nemaki:chatContextMetadata", "nemaki:externalIntegration",
                "nemaki:messageMetadata", "nemaki:noteMetadata",
                "nemaki:businessRecordMetadata", "nemaki:tagged"));
        original.setSecondaryIds(originalSecondaryIds);
        original.setAspects(new ArrayList<>(List.of(
                aspect("nemaki:chatContextMetadata", "nemaki:chatChannelId", "C-CAPTURED"),
                aspect("nemaki:externalIntegration", "nemaki:sourceSystem", "slack"),
                // The three archetype homes joined PROTECTED on 2026-08-24 ((c) §8.1); the copy
                // strip is driven by that set, so they must travel the same way — a copy that
                // kept a Message-ID would assert an email capture that never happened to it.
                aspect("nemaki:messageMetadata", "nemaki:internetMessageId", "<a@example.com>"),
                aspect("nemaki:noteMetadata", "nemaki:notePageId", "page-1"),
                aspect("nemaki:businessRecordMetadata", "nemaki:recordId", "ACC-001"),
                aspect("nemaki:tagged", "nemaki:tagName", "kept"))));
        original.setSubTypeProperties(new ArrayList<>());
        Folder target = new Folder();
        target.setId("folder-1");

        try {
            service.createDocumentFromSource(
                    mock(org.apache.chemistry.opencmis.commons.server.CallContext.class),
                    "bedroom", null, target, original, VersioningState.MAJOR, null, null, null);
        } catch (Exception afterCreate) {
            // Later phases (change events, indexing) may fail under these mocks; the create —
            // the persistence point this test is about — has already been captured by then.
        }

        assertNotNull(captured[0], "the copy never reached the create — fixture broke earlier");
        List<String> aspectNames = captured[0].getAspects().stream().map(Aspect::getName).toList();
        assertTrue(!aspectNames.contains("nemaki:chatContextMetadata"),
                "the copy asserts a chat capture that never happened to this NEW object: "
                        + aspectNames);
        assertTrue(!aspectNames.contains("nemaki:externalIntegration"),
                "the copy carries the dedupe identity of the ORIGINAL's source — the next poll "
                        + "of that source would treat this copy's claims as capture state");
        assertTrue(!aspectNames.contains("nemaki:messageMetadata"),
                "the copy carries a Message-ID: it asserts an email capture that never happened "
                        + "to this NEW object: " + aspectNames);
        assertTrue(!aspectNames.contains("nemaki:noteMetadata"), aspectNames.toString());
        assertTrue(!aspectNames.contains("nemaki:businessRecordMetadata"),
                aspectNames.toString());
        assertTrue(aspectNames.contains("nemaki:tagged"),
                "ordinary aspects must survive the copy — the strip is evidence-scoped");
        assertEquals(List.of("nemaki:tagged"), captured[0].getSecondaryIds(),
                "secondaryIds must match the stripped aspects");
        assertEquals(List.of("nemaki:chatContextMetadata", "nemaki:externalIntegration",
                        "nemaki:messageMetadata", "nemaki:noteMetadata",
                        "nemaki:businessRecordMetadata", "nemaki:tagged"), originalSecondaryIds,
                "buildCopyDocument shares the ORIGINAL's secondaryIds list by reference — the "
                        + "strip must not mutate the original");
        assertEquals(6, original.getAspects().size(), "the original must be untouched");
    }
}
