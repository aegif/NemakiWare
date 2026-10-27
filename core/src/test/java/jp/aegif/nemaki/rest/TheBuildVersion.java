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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import jp.aegif.nemaki.util.test.HarnessBroken;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version this build was made from, as the locks on the version exits compare with it.
 *
 * <p>Read from a test resource Maven filters to {@code ${project.version}} — not from
 * {@link BuildInfoResource}, which every exit reads. A lock that took its expected value from the
 * product's own source agreed with whatever that source said: a {@code version.properties} fixed
 * at an old version, or a {@code BuildInfoResource} answering a literal, kept every exit and every
 * lock in step on the wrong version (3.4.1 batch 1 review, Codex P2).
 */
public final class TheBuildVersion {

    private TheBuildVersion() {
    }

    public static String expected() {
        try (InputStream in = TheBuildVersion.class.getClassLoader()
                .getResourceAsStream("build-version/expected-version.txt")) {
            HarnessBroken.unless((in) != null, "build-version/expected-version.txt is not on the test classpath");
            String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"),
                    "build-version/expected-version.txt was not filtered (it says '" + version
                            + "'), so there is no build version to compare with");
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
