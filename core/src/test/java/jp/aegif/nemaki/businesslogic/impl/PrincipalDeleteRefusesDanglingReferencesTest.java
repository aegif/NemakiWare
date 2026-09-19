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

import jp.aegif.nemaki.util.test.HarnessBroken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Deleting a principal REFUSES when a referencing group cannot be re-fetched.
 *
 * <h2>The revived membership this prevents</h2>
 *
 * <p>The reverse-lookup view says which groups reference the principal being deleted — that
 * read is fail-closed ({@code parentGroupIdsFrom}). But each parent is then RE-FETCHED for its
 * revision, and a fetch that came back null was skipped: the other parents were updated, the
 * delete reported success, and the dangling id sat in the skipped parent's member list. Nothing
 * sweeps those. Re-creating the same user or group id later — an ordinary admin operation —
 * silently revived the membership with every ACE the group grants. Found by the round-32
 * sibling sweep: the view half was fixed in round 31, the re-fetch half was not.
 *
 * <p>The re-fetch is FRESH (not cached): the reverse view is cross-replica truth, and a
 * cached parent from before another replica added the membership reads as "does not contain
 * it" — the stale-cache twin of the null-skip. Driven by reflection because the walk is
 * private; the seam fails loudly if renamed.
 */
class PrincipalDeleteRefusesDanglingReferencesTest {

    private static final String REPO = "bedroom";

    @Test
    @DisplayName("a parent group that cannot be re-fetched aborts the user delete")
    void aFailedRefetchAbortsTheUserDelete() throws Exception {
        ContentServiceImpl service = spy(new ContentServiceImpl());
        doReturn(List.of("group-p")).when(service)
                .getGroupIdsDirectlyContainingUser(REPO, "user-x");
        doReturn(null).when(service).getGroupItemByIdFresh(REPO, "group-p");

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokePrivate(service, "removeUserFromAllGroups", "user-x"),
                "the parent that could not be confirmed was skipped, and the delete left a "
                        + "dangling membership that revives on id reuse");
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("a parent group that cannot be re-fetched aborts the group delete — the twin")
    void aFailedRefetchAbortsTheGroupDelete() throws Exception {
        ContentServiceImpl service = spy(new ContentServiceImpl());
        doReturn(List.of("group-p")).when(service)
                .getGroupIdsDirectlyContainingGroup(REPO, "group-x");
        doReturn(null).when(service).getGroupItemByIdFresh(REPO, "group-p");

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> invokePrivate(service, "removeGroupFromAllNestedGroups", "group-x"));
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                String.valueOf(wrapped.getCause()));
    }

    @Test
    @DisplayName("an unreferenced principal still deletes without fetching — the control")
    void anUnreferencedPrincipalStillProceeds() throws Exception {
        ContentServiceImpl service = spy(new ContentServiceImpl());
        doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, "user-y");

        invokePrivate(service, "removeUserFromAllGroups", "user-y");

        verify(service, org.mockito.Mockito.never()).getGroupItemByIdFresh(anyString(), anyString());
    }

    private static void invokePrivate(ContentServiceImpl service, String method, String principal)
            throws Exception {
        try {
            Method m = ContentServiceImpl.class.getDeclaredMethod(method, String.class,
                    String.class);
            m.setAccessible(true);
            m.invoke(service, REPO, principal);
        } catch (NoSuchMethodException e) {
            throw new HarnessBroken(method + " was renamed or reshaped — update this test to "
                    + "keep driving the walk the delete drives", e);
        }
    }
}
