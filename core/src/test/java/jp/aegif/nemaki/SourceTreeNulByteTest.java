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
package jp.aegif.nemaki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No source file may contain a literal NUL byte.
 *
 * <h2>Why this is a test and not a script</h2>
 *
 * <p>A NUL inside a string literal compiles fine in both Java and TypeScript, so nothing in the
 * ordinary build notices it. Git does: one NUL makes it classify the whole file as binary, and
 * from then on {@code git diff} shows {@code Bin 6765 -> 6970} instead of the change. The file
 * stops being reviewable, and a later edit to it stops being visible in a pull request.
 *
 * <p>{@code scripts/validate-soc-templates.sh} has scanned for this since RC6.10, and its own
 * comment records two NUL-shipped regressions in a single release cycle. It is a script someone
 * has to remember to run, so a third one shipped anyway — a separator written as {@code "\0"} in
 * a cache key. A check that only runs when someone remembers is not a check.
 *
 * <p>The scan covers the whole repository (Java and web sources alike), not just this module, and
 * matches the script's exclusions so the two cannot disagree about what is in scope.
 */
class SourceTreeNulByteTest {

    private static final Set<String> EXTENSIONS = Set.of(".java", ".ts", ".tsx", ".js", ".jsx");

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "target", "dist", "build", ".git",
            "coverage", "playwright-report", "test-results");

    /**
     * Walk up from the module directory to the project root.
     *
     * <p>Anchored on the root {@code pom.xml}, not on {@code .git}: verification builds run from a
     * mirror of the tree that deliberately omits git metadata (see tools/verify), and a test that
     * cannot find its subject there fails for a reason that has nothing to do with what it checks.
     */
    private static Path projectRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("pom.xml"))
                    && Files.isDirectory(p.resolve("core"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    @Test
    @DisplayName("ソースツリーに NUL バイトが 1 つも無い (git が binary 扱いして diff が消える)")
    void noSourceFileContainsANulByte() throws IOException {
        Path root = projectRoot();
        assertTrue(root != null && Files.isDirectory(root),
                "could not locate the project root (a directory with pom.xml and core/) — this test"
                        + " needs it to scan the tree");

        List<String> offenders = new ArrayList<>();
        int scanned = scan(root, offenders);

        assertEquals(List.of(), offenders,
                "a literal NUL byte makes git treat the file as binary, so its diffs disappear."
                        + " Write the character as an escape (\\u0001 for a separator) instead");
        // A scan that silently matched nothing would pass forever; make the coverage explicit.
        assertTrue(scanned > 500,
                "expected to scan the whole source tree, only saw " + scanned
                        + " files — the walk is probably starting in the wrong place");
    }

    private static int scan(Path root, List<String> offenders) throws IOException {
        int[] scanned = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return EXCLUDED_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                boolean interesting = EXTENSIONS.stream().anyMatch(name::endsWith);
                if (!interesting) {
                    return FileVisitResult.CONTINUE;
                }
                scanned[0]++;
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    int count = 0;
                    for (byte b : bytes) {
                        if (b == 0) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        offenders.add(root.relativize(file) + " (" + count + " NUL byte(s))");
                    }
                } catch (IOException e) {
                    offenders.add(root.relativize(file) + " (unreadable: " + e.getMessage() + ")");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return scanned[0];
    }
}
