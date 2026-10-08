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
 * a feed naming one version and a screen naming another (3.4.1 batch 1 review, Codex P2).
 */
class TheUiNamesTheBuildVersionTest {

    private static final Path UI = Path.of("src/main/webapp/ui");

    private static String onlyMatch(String text, Pattern pattern, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), what + " was not found");
        String found = m.group(1);
        assertFalse(m.find(), what + " was found twice");
        return found;
    }

    @Test
    @DisplayName("ui/package.json carries the build's version — the one the bundle shows")
    void packageJsonIsTheBuildVersion() throws Exception {
        String version = onlyMatch(Files.readString(UI.resolve("package.json"), StandardCharsets.UTF_8),
                Pattern.compile("(?m)^  \"version\": \"([^\"]+)\",?$"), "the top-level version of ui/package.json");
        assertEquals(TheBuildVersion.expected(), version,
                "the screen shows ui/package.json's version " + version + ", and this build is "
                        + TheBuildVersion.expected());
    }

    @Test
    @DisplayName("Layout.tsx's fallback names the build's version")
    void theLayoutFallbackIsTheBuildVersion() throws Exception {
        String version = onlyMatch(
                Files.readString(UI.resolve("src/components/Layout/Layout.tsx"), StandardCharsets.UTF_8),
                Pattern.compile("typeof __UI_VERSION__ !== 'undefined' \\? __UI_VERSION__ : '([^']+)'"),
                "Layout.tsx's fallback for __UI_VERSION__");
        assertEquals(TheBuildVersion.expected(), version,
                "Layout.tsx falls back to " + version + ", and this build is " + TheBuildVersion.expected());
    }
}
