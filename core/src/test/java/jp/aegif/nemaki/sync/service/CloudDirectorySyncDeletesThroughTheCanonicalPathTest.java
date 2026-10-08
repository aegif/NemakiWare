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
 * The cloud directory sync removes a user through the canonical delete — the twin of
 * {@code DirectorySyncDeletesThroughTheCanonicalPathTest} for the LDAP sync (R115).
 *
 * <p>The Microsoft delta's removal marker deleted the user's item with the generic delete, so it
 * left the user's id in every group that listed it and skipped the protected-principal decision:
 * a Solr account set to a synced UPN would have been deleted (3.4.1 batch 4 review, Codex P1).
 */
class CloudDirectorySyncDeletesThroughTheCanonicalPathTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/sync/service/CloudDirectorySyncServiceImpl.java";

    @Test
    @DisplayName("a removed cloud user is deleted with deleteUser, not the generic delete")
    void removedUsersUseDeleteUser() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertTrue(source.contains("contentService.deleteUser(repositoryId, userId)"),
                "the cloud sync no longer deletes a removed user through the canonical path");
        assertFalse(source.contains("existingItem.getId(), true)"),
                "the cloud sync deletes a removed user's item with the generic delete again — no "
                        + "membership stripping, no protected-principal decision");
    }
}
