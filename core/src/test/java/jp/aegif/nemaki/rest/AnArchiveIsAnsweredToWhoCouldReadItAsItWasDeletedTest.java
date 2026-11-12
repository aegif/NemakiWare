/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * An archive's content and properties are answered to an administrator and to who could read the object as it was
 * deleted — not to its creator or the user who deleted it as such, and not only to them (R323, 3.4.2).
 *
 * <p>{@code GET /rest/repo/{repo}/archive/{id}/content} and {@code archive/show/{id}} answered the creator and the user
 * who deleted the object. A caller who deleted a version it could not read was then answered that version's content
 * (measured on nb33). They now ask {@code ContentService.wasArchiveReadableAtDeletion}, which answers by the users the
 * permission service answered as the object was deleted (its own lock is {@code AnArchiveRecordsWhoCouldReadItAsItWasDeletedTest}).
 *
 * <p>What is locked, on the product's {@code ArchiveDownloadResource.download} and {@code ArchiveResource.show}: a user
 * the decision answers — whether the creator, the deleting user or neither — is answered, and one it does not answer is
 * refused as "not found", the creator and the deleting user included; the content asks about the content and show
 * about the properties; an administrator is answered whatever the decision says; and the restore's decision, which
 * still answers the creator and the
 * deleting user, did not move. The decision, the archive and its bytes are stand-ins; the restore's decision answers as
 * the product's does.
 */
class AnArchiveIsAnsweredToWhoCouldReadItAsItWasDeletedTest {

    private static final String REPO = "bedroom";
    private static final byte[] BYTES = "the first version".getBytes(StandardCharsets.UTF_8);

    private final ContentService contentService = mock(ContentService.class);
    /** Per archive: who could read its properties, and who its content. */
    private final Map<String, Set<String>> properties = new HashMap<>();
    private final Map<String, Set<String>> content = new HashMap<>();

    AnArchiveIsAnsweredToWhoCouldReadItAsItWasDeletedTest() {
        // The restore's decision answers as the product's does (the creator and the deleting user), so a reader put back on
        // it answers those two — and is told apart from the decision, which answers others.
        ContentServiceImpl restoreRule = new ContentServiceImpl();
        when(contentService.isArchiveAccessible(anyString(), any(Archive.class)))
                .thenAnswer(call -> restoreRule.isArchiveAccessible(call.getArgument(0), call.getArgument(1)));
        when(contentService.wasArchiveReadableAtDeletion(eq(REPO), any(Archive.class), anyString(), anyBoolean()))
                .thenAnswer(call -> {
                    Archive archive = call.getArgument(1);
                    String who = call.getArgument(2);
                    boolean withContent = call.getArgument(3);
                    return (withContent ? content : properties).getOrDefault(archive.getId(), Set.of()).contains(who);
                });
    }

    private Archive archive(String id, Set<String> readProperties, Set<String> readContent) {
        Archive archive = new Archive();
        archive.setId(id);
        archive.setName(id + ".txt");
        archive.setType("cmis:document");
        archive.setOriginalId("original-of-" + id);
        archive.setCreator("creator");
        archive.setArchivedBy("deleter");
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
        archive.setMimeType("text/plain");
        properties.put(id, readProperties);
        content.put(id, readContent);
        when(contentService.getArchive(REPO, id)).thenReturn(archive);
        when(contentService.getArchiveContentStream(eq(REPO), eq(id))).thenAnswer(call -> new ByteArrayInputStream(BYTES));
        return archive;
    }

    private static HttpServletRequest requestOf(String username, boolean admin) {
        CallContext context = mock(CallContext.class);
        when(context.getUsername()).thenReturn(username);
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString())).thenAnswer(call -> "CallContext".equals(call.getArgument(0)) ? context : null);
        return request;
    }

    private int contentStatus(String id, String username, boolean admin) {
        ArchiveDownloadResource resource = new ArchiveDownloadResource();
        resource.setContentService(contentService);
        Response response = assertDoesNotThrow(() -> resource.download(REPO, id, requestOf(username, admin)),
                "reading the content of " + id + " as " + username);
        return response.getStatus();
    }

    private JSONObject show(String id, String username, boolean admin) {
        ArchiveResource resource = new ArchiveResource();
        resource.setContentService(contentService);
        String answer = assertDoesNotThrow(() -> resource.show(REPO, id, requestOf(username, admin)),
                "showing " + id + " to " + username);
        return assertDoesNotThrow(() -> (JSONObject) new JSONParser().parse(answer), "the answer to show: " + answer);
    }

    private boolean shown(String id, String username, boolean admin) {
        return show(id, username, admin).containsKey("archive");
    }

    @Test
    @DisplayName("a user who could read it is answered — the creator, the deleting user, or someone who is neither")
    void whoCouldReadIsAnswered() {
        archive("READ-BY-THREE", Set.of("deleter", "creator", "reader"), Set.of("deleter", "creator", "reader"));
        for (String who : new String[] {"deleter", "creator", "reader"}) {
            assertEquals(200, contentStatus("READ-BY-THREE", who, false), "the content answered to the " + who + " who could read it");
            assertTrue(shown("READ-BY-THREE", who, false), "the archive shown to the " + who + " who could read it");
        }
    }

    @Test
    @DisplayName("the deleting user and the creator are refused when they could not read it")
    void whoCouldNotReadIsRefused() {
        archive("READ-BY-SOMEONE-ELSE", Set.of("reader"), Set.of("reader"));
        archive("READ-BY-NOBODY", Set.of(), Set.of());
        for (String id : new String[] {"READ-BY-SOMEONE-ELSE", "READ-BY-NOBODY"}) {
            for (String who : new String[] {"deleter", "creator"}) {
                assertEquals(404, contentStatus(id, who, false), "the content of " + id + " answered to the " + who);
                assertFalse(shown(id, who, false), id + " shown to the " + who);
            }
        }
    }

    @Test
    @DisplayName("an administrator is answered whatever the decision says")
    void anAdministratorIsAnswered() {
        archive("READ-BY-NOBODY", Set.of(), Set.of());
        assertEquals(200, contentStatus("READ-BY-NOBODY", "admin", true), "the content answered to an administrator");
        assertTrue(shown("READ-BY-NOBODY", "admin", true), "the archive shown to an administrator");
    }

    @Test
    @DisplayName("the content asks about the content and show about the properties")
    void thePropertiesAndTheContentAreAnsweredApart() {
        archive("PROPERTIES-ONLY", Set.of("reader"), Set.of());
        assertTrue(shown("PROPERTIES-ONLY", "reader", false), "the archive shown to a reader of its properties");
        assertEquals(404, contentStatus("PROPERTIES-ONLY", "reader", false), "the content answered to a reader of its properties only");
        archive("CONTENT-ONLY", Set.of(), Set.of("reader"));
        assertFalse(shown("CONTENT-ONLY", "reader", false), "the archive shown to a user the properties decision refuses");
        assertEquals(200, contentStatus("CONTENT-ONLY", "reader", false), "the content answered to a user the content decision answers");
    }

    @Test
    @DisplayName("the restore's decision did not move: it still answers the creator and the deleting user")
    void theRestoresDecisionDidNotMove() {
        Archive archive = archive("RESTORABLE", Set.of(), Set.of());
        ContentServiceImpl service = new ContentServiceImpl();
        assertTrue(service.isArchiveAccessible("creator", archive), "the creator may restore it");
        assertTrue(service.isArchiveAccessible("deleter", archive), "the deleting user may restore it");
        assertFalse(service.isArchiveAccessible("someone", archive), "another user may not");
    }
}
