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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No compiled class may be one that throws {@code Unresolved compilation problem} when touched.
 *
 * <h2>Why this can happen at all</h2>
 *
 * <p>The IDE's Java language server compiles into the same {@code target/classes} that Maven uses.
 * Where it disagrees with javac — a generics cast javac accepts and Eclipse rejects is the case
 * seen here — it writes a class file whose method bodies throw {@code java.lang.Error: Unresolved
 * compilation problem}. Maven's incremental build then has no reason to recompile that file, so
 * the broken class is packaged into the WAR and shipped.
 *
 * <p>It is a nasty failure to diagnose from the outside: the server answers a bare
 * {@code CmisRuntimeException} with no message, nothing appears in the application log, and
 * reverting to an earlier commit does not help because the stale class file survives. It cost
 * hours once already, twice in a single day, and "remember to run clean" is not a control.
 *
 * <p>The marker string is embedded by the Eclipse compiler in the constant pool of every affected
 * class, so scanning for it finds them without loading anything.
 */
class CompiledClassesAreUsableTest {

    /** What the Eclipse compiler puts in a class it could not compile. */
    private static final byte[] MARKER =
            "Unresolved compilation problem".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    @DisplayName("target/classes に「コンパイルできなかったクラス」が混入していない")
    void noCompiledClassCarriesAnUnresolvedCompilationProblem() throws IOException {
        Path classes = Path.of("target", "classes");
        if (!Files.isDirectory(classes)) {
            return; // nothing built yet — surefire always runs after compile, so this is a no-op
        }
        List<String> offenders = new ArrayList<>();
        int scanned = scan(classes, offenders);

        assertEquals(List.of(), offenders,
                "these class files throw at runtime instead of doing their job. The IDE's language"
                        + " server wrote them into the Maven output directory and the incremental"
                        + " build kept them. Run `mvn clean` and rebuild — and do not package a WAR"
                        + " from an incremental build");
        assertTrue(scanned > 100,
                "expected to scan the compiled output, only saw " + scanned + " class files");
    }

    private static int scan(Path root, List<String> offenders) throws IOException {
        int[] scanned = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.getFileName().toString().endsWith(".class")) {
                    return FileVisitResult.CONTINUE;
                }
                scanned[0]++;
                try {
                    if (indexOf(Files.readAllBytes(file), MARKER) >= 0) {
                        offenders.add(root.relativize(file).toString());
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

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
