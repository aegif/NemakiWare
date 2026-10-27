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
package jp.aegif.nemaki.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jp.aegif.nemaki.util.test.HarnessBroken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screen names the version this build was made from.
 *
 * <p>The UI's version label is baked into the bundle from {@code ui/package.json} (vite's
 * {@code __UI_VERSION__}), and {@code Layout.tsx} falls back to a literal where a bundle was built
 * without it. Both are bumped by hand at a release. 3.4.0 made them agree with Maven by hand (A-0)
 * and nothing checked it after, so a release that bumped the pom alone would ship a WAR, a SIP and
 * a feed naming one version and a screen naming another (3.4.1 batch 1 review, Codex P2). The
 * define that carries package.json's version into the bundle is read too (round 2, both
 * reviewers): with it a literal, the two files could be right and the screen not.
 *
 * <p>What this cannot see: {@code package-lock.json}'s copy of the version, and a pom that was not
 * bumped at all — then every exit and this lock agree on the old version. The release smoke
 * compares the running image's version with the tag, after the images are pushed.
 */
class TheUiNamesTheBuildVersionTest {

    // Spelt from the repository root ("../core/..."), not from core: the lock that requires the
    // workflow to start for every document a lock reads only sees paths written that way, and with
    // "src/main/webapp/ui" it saw none of these — a pull request changing package.json or vite's
    // define alone did not start the workflow that runs this lock (batch 5 review, subagent P2).
    private static final Path PACKAGE_JSON = Path.of("../core/src/main/webapp/ui/package.json");
    private static final Path LAYOUT = Path.of("../core/src/main/webapp/ui/src/components/Layout/Layout.tsx");
    private static final Path VITE_CONFIG = Path.of("../core/src/main/webapp/ui/vite.config.ts");

    private static String onlyMatch(String text, Pattern pattern, String what) {
        Matcher m = pattern.matcher(text);
        HarnessBroken.unless(m.find(), what + " was not found");
        String found = m.group(1);
        assertFalse(m.find(), what + " was found twice");
        return found;
    }

    @Test
    @DisplayName("ui/package.json carries the build's version — the one the bundle shows")
    void packageJsonIsTheBuildVersion() throws Exception {
        String version = onlyMatch(Files.readString(PACKAGE_JSON, StandardCharsets.UTF_8),
                Pattern.compile("(?m)^  \"version\": \"([^\"]+)\",?$"), "the top-level version of ui/package.json");
        assertEquals(TheBuildVersion.expected(), version,
                "the screen shows ui/package.json's version " + version + ", and this build is "
                        + TheBuildVersion.expected());
    }

    @Test
    @DisplayName("Layout.tsx's fallback names the build's version")
    void theLayoutFallbackIsTheBuildVersion() throws Exception {
        String version = onlyMatch(
                Files.readString(LAYOUT, StandardCharsets.UTF_8),
                Pattern.compile("typeof __UI_VERSION__ !== 'undefined' \\? __UI_VERSION__ : '([^']+)'"),
                "Layout.tsx's fallback for __UI_VERSION__");
        assertEquals(TheBuildVersion.expected(), version,
                "Layout.tsx falls back to " + version + ", and this build is " + TheBuildVersion.expected());
    }

    @Test
    @DisplayName("vite bakes ui/package.json's version into the bundle — the label is not a literal")
    void viteBakesThePackageVersionIntoTheBundle() throws Exception {
        // The seam between the two checks above and the screen: package.json can be right and the
        // bundle still show a literal, if the define stops reading it (3.4.1 batch 1, round 2,
        // Codex P2). Comments are stripped first, so a commented-out define does not count.
        String code = Files.readString(VITE_CONFIG, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        String defined = onlyMatch(code, Pattern.compile("__UI_VERSION__\\s*:\\s*([^,}\\n]+)"),
                "vite's define of __UI_VERSION__");
        assertEquals("JSON.stringify(pkgVersion)", defined.trim(),
                "the bundle's version label is not package.json's version: __UI_VERSION__ is " + defined.trim());
        String read = onlyMatch(code, Pattern.compile("const\\s+pkgVersion\\s*=\\s*([^;]*?\\)\\.version)\\b"),
                "the definition of pkgVersion");
        assertTrue(read.replaceAll("\\s+", "").equals(
                        "JSON.parse(fs.readFileSync(newURL('./package.json',import.meta.url),'utf-8')).version"),
                "pkgVersion is not ui/package.json's version: " + read);
    }
}
