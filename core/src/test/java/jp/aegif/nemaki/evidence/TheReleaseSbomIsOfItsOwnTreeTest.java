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
package jp.aegif.nemaki.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release's UI SBOM is made from the node_modules of the tree being released.
 *
 * <p>cyclonedx-npm reads {@code node_modules}. The 3.4.0 worktree had none, and the UI SBOM was
 * made with the working copy's {@code node_modules} borrowed in — checked only for an identical
 * {@code package-lock.json}. {@code tools/sbom/make-sbom.sh} now installs the tree's own with
 * {@code npm ci} before it asks cyclonedx-npm. Read from the script; nothing here runs npm.
 */
class TheReleaseSbomIsOfItsOwnTreeTest {

    private static final Path SCRIPT = Path.of("../tools/sbom/make-sbom.sh");

    @Test
    @DisplayName("make-sbom.sh installs the UI's node_modules from its own lockfile before the npm SBOM")
    void theUiSbomIsMadeFromTheTreesOwnInstall() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*#.*$", "");
        Matcher install = Pattern.compile("(?m)^\\s*\\(\\s*cd core/src/main/webapp/ui && npm ci\\b[^)]*\\)\\s*$")
                .matcher(script);
        assertTrue(install.find(), "make-sbom.sh does not run npm ci in the UI before the npm SBOM:\n" + script);
        int sbom = script.indexOf("npx --yes \"${NPM_TOOL}\"");
        assertTrue(sbom > 0, "make-sbom.sh no longer asks cyclonedx-npm, so this lock reads nothing");
        assertTrue(install.start() < sbom,
                "make-sbom.sh asks cyclonedx-npm before it installs the tree's node_modules");
    }
}
