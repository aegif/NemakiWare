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
package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import com.ibm.cloud.cloudant.v1.model.Document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which duplicate the setup patch may delete, and — mostly — which it may not.
 *
 * <p>The duplication this heals is the patch's own doing: the initialization dump seeds
 * {@code system.version} under {@code system_config_001}, the patch derived
 * {@code system_config_system_version} for the same key, and an id-only existence check let
 * both live. The strict configuration reader then refuses to choose between them, which turned
 * the 4b cursor preflight red on every standard install.
 *
 * <p>What is pinned hardest here is restraint: the patch deletes its own document and nothing
 * else, because every other id is either the dump's seed or an operator's, and neither is this
 * patch's to judge.
 */
class PatchSystemFolderConfigDedupTest {

    private static final String DERIVED = "system_config_system_version";

    private static Document doc(String id) {
        Document document = new Document();
        document.setId(id);
        return document;
    }

    @Test
    @DisplayName("its own document is deleted only while another also carries the key")
    void deletesItsOwnCopyOnDuplication() {
        assertEquals(DERIVED, Patch_SystemFolderSetup.duplicateToDelete(DERIVED,
                List.of(doc("system_config_001"), doc(DERIVED))));
    }

    @Test
    @DisplayName("a single holder is healthy whatever its id")
    void singleHolderIsHealthy() {
        assertNull(Patch_SystemFolderSetup.duplicateToDelete(DERIVED, List.of(doc(DERIVED))),
                "the patch's own document alone is the ordinary post-patch state");
        assertNull(Patch_SystemFolderSetup.duplicateToDelete(DERIVED,
                        List.of(doc("system_config_001"))),
                "the seeded document alone is the ordinary pre-patch state");
    }

    /** A duplication among documents this patch never wrote is not its call to resolve. */
    @Test
    @DisplayName("foreign duplicates are left for the strict reader to keep refusing")
    void foreignDuplicatesAreNotTouched() {
        assertNull(Patch_SystemFolderSetup.duplicateToDelete(DERIVED,
                List.of(doc("system_config_001"), doc("operator_copy"))));
    }

    @Test
    @DisplayName("nothing to decide over null or a short list")
    void degenerateInputs() {
        assertNull(Patch_SystemFolderSetup.duplicateToDelete(DERIVED, null));
        assertNull(Patch_SystemFolderSetup.duplicateToDelete(DERIVED, List.of()));
        assertNull(Patch_SystemFolderSetup.duplicateToDelete(null,
                List.of(doc("a"), doc("b"))));
    }
}
