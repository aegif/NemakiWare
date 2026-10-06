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
package jp.aegif.nemaki.verifier.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command the release notes print has to be one this build produces.
 *
 * <p>The notes said {@code java -jar evidence-verifier-cli.jar} and nothing built a jar that
 * ran that way: no main class in the manifest, no classpath to BouncyCastle (9-6 review, P1).
 * The jar is built in the {@code package} phase, after these tests run, so what can be measured
 * here is the build's declaration — the main class the manifest will name, read against the
 * class that has {@code main}, and the dependencies going to {@code lib/} beside the jar — and
 * that the notes name the artefact this pom produces. Whether the jar then runs is measured in
 * the release procedure (evidence-verifier-release.md §2) and recorded in the readiness document.
 */
class TheDistributionRunsWithJavaJarTest {

    private static String read(String path) throws IOException {
        Path file = Path.of(path);
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the manifest names the class that has main, and the dependencies go to lib/ beside the jar")
    void theManifestNamesTheEntryPointAndTheClasspath() throws Exception {
        String pom = read("pom.xml");
        Verify.class.getMethod("main", String[].class);
        assertTrue(pom.contains("<mainClass>" + Verify.class.getName() + "</mainClass>"),
                "the jar's manifest does not name " + Verify.class.getName() + " as Main-Class, "
                        + "so java -jar has nothing to run");
        assertTrue(pom.contains("<addClasspath>true</addClasspath>")
                        && pom.contains("<classpathPrefix>lib/</classpathPrefix>"),
                "the manifest does not point at lib/, so the jar cannot find evidence-verifier-"
                        + "core or BouncyCastle when run with java -jar");
        assertTrue(pom.contains("<goal>copy-dependencies</goal>")
                        && pom.contains("<outputDirectory>${project.build.directory}/lib</outputDirectory>")
                        && pom.contains("<includeScope>runtime</includeScope>"),
                "the runtime dependencies are not copied to target/lib, so the classpath the "
                        + "manifest names is empty on disk");
    }

    @Test
    @DisplayName("the release notes and the release procedure name the jar this pom builds")
    void theDocumentsNameTheArtefactThatIsBuilt() throws Exception {
        String pom = read("pom.xml");
        Matcher version = Pattern.compile("<parent>.*?<version>([^<]+)</version>", Pattern.DOTALL)
                .matcher(pom);
        assertTrue(version.find(), "the pom has no parent version to build the artefact name from");
        String jar = "evidence-verifier-cli-" + version.group(1) + ".jar";

        String notes = read("../RELEASE_NOTES.md");
        assertTrue(notes.contains("java -jar " + jar + " verify"),
                "RELEASE_NOTES.md does not show `java -jar " + jar + " verify`, the command "
                        + "this build's artefact answers to");
        assertTrue(notes.contains("`lib/`"),
                "RELEASE_NOTES.md does not say the jar needs lib/ beside it, so a receiver who "
                        + "copies the jar alone gets NoClassDefFoundError");
        String procedure = read("../docs/operations/evidence-verifier-release.md");
        assertTrue(procedure.contains("java -jar evidence-verifier-cli/target/" + jar),
                "the release procedure's smoke test does not run the built jar with java -jar");
        assertTrue(procedure.contains("lib/*.jar"),
                "the release procedure's SHA-256SUMS does not cover the jars under lib/, so a "
                        + "receiver cannot check what the manifest loads");
    }
}
