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
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Delete flows refuse to act blind when the version list or edge deletes fail.
 *
 * <h2>The catch that inverted its own input's refusal</h2>
 *
 * <p>{@code getAllVersions} throws when the version list cannot be read — because
 * "deleteAllVersions reads this list and acts on every member". The round-33 review found
 * {@code deleteDocument} catching that throw twice and acting anyway: the allVersions arm
 * "fell back to single version deletion" while STILL deleting the version-series document
 * (orphaning the sibling versions it never saw), and the single-version arm read the empty
 * list as "this is the only version" and ESCALATED to a series delete. The relationship arm
 * had the write-side twin: a failed edge delete was logged and the object deleted anyway,
 * leaving edges whose end no longer exists — which nothing sweeps.
 *
 * <p>The two version-list catches sit deep in a method needing live wiring, so they are
 * pinned in source (the {@code aRetainedFolderStaysFindable} precedent: driving them would
 * measure the harness). The relationship batch is driven directly by reflection.
 */
class DeleteFlowsRefuseBlindVersionListsTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java";

    @Test
    @DisplayName("the allVersions catch refuses instead of 'falling back' over the series")
    void theAllVersionsCatchRefuses() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        int at = source.indexOf("could not be enumerated; deleting all versions without the list");
        assertTrue(at >= 0,
                "the allVersions catch no longer refuses — a failed version list 'falls "
                        + "back to single version deletion' while still deleting the series "
                        + "document over unseen sibling versions");
        // The fallback must be GONE, not merely joined by a throw.
        String catchArm = source.substring(Math.max(0, at - 600), at);
        assertFalse(catchArm.contains("Falling back to single version deletion"),
                "the fallback survived next to the refusal");
    }

    @Test
    @DisplayName("the single-version catch refuses instead of escalating to a series delete")
    void theSingleVersionCatchRefuses() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertTrue(source.contains(
                "succession (or that it is the last version) without the list"),
                "the single-version catch no longer refuses — an empty list reads as 'this "
                        + "is the only version' and escalates one version's delete into the "
                        + "whole series");
    }

    @Test
    @DisplayName("a partially failed edge delete aborts the object delete")
    void aPartialEdgeDeleteAborts() throws Exception {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.deleteBulk("bedroom", List.of("rel-1", "rel-2"))).thenReturn(1);
        ContentServiceImpl service = new ContentServiceImpl();
        service.setContentDaoService(dao);

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokeBatch(service, List.of("rel-1", "rel-2")),
                "one of two edges survived and the object delete proceeded — the survivor "
                        + "now points at nothing, and nothing sweeps it");
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("a fully deleted edge batch proceeds — the control")
    void aFullEdgeDeleteProceeds() throws Exception {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.deleteBulk("bedroom", List.of("rel-1", "rel-2"))).thenReturn(2);
        ContentServiceImpl service = new ContentServiceImpl();
        service.setContentDaoService(dao);

        invokeBatch(service, List.of("rel-1", "rel-2"));
    }

    private static void invokeBatch(ContentServiceImpl service, List<String> ids)
            throws Exception {
        try {
            Method m = ContentServiceImpl.class.getDeclaredMethod("deleteRelationshipsBatch",
                    String.class, List.class);
            m.setAccessible(true);
            m.invoke(service, "bedroom", ids);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("deleteRelationshipsBatch was renamed — update this test",
                    e);
        }
    }
}
