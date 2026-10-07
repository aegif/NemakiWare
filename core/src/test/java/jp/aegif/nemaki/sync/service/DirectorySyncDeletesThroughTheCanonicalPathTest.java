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
package jp.aegif.nemaki.sync.service;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Directory sync deletes principals through the CANONICAL path, not a private copy of it.
 *
 * <h2>The half that had no stripping at all</h2>
 *
 * <p>The user side kept a private {@code removeUserFromAllGroups} that scanned every group in
 * the repository and skipped whatever it could not read — the shape the canonical path was
 * fixed away from (it uses the reverse-lookup view, refuses a short answer, and re-fetches
 * each parent FRESH before rewriting it). Worse, the GROUP side had no stripping at all: an
 * orphan group removed by directory sync left its id in every parent group's nested list, and
 * re-creating the same group id later silently revived the nesting.
 *
 * <p>Pinned in source because driving the sync needs LDAP wiring this test does not carry, and
 * what is being asserted is which method is called — the same reason
 * {@code aRetainedFolderStaysFindable} is a source lock.
 */
class DirectorySyncDeletesThroughTheCanonicalPathTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/sync/service/DirectorySyncServiceImpl.java";

    @Test
    @DisplayName("orphan users are removed with deleteUser, not a private copy")
    void orphanUsersUseDeleteUser() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertTrue(source.contains("contentService.deleteUser(repositoryId, userId)"),
                "directory sync no longer deletes users through the canonical path, so the "
                        + "membership stripping is a private copy again");
        assertFalse(source.contains("private void removeUserFromAllGroups"),
                "the private copy came back — it scans every group and skips what it cannot "
                        + "read, which is what the canonical path refuses to do");
    }

    @Test
    @DisplayName("orphan groups are removed with deleteGroup — the half that had no stripping")
    void orphanGroupsUseDeleteGroup() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertTrue(source.contains(
                "contentService.deleteGroup(repositoryId, existingGroup.getGroupId())"),
                "directory sync deletes groups with a bare delete again, leaving the group's "
                        + "id in every parent group's nested list");
    }
}
