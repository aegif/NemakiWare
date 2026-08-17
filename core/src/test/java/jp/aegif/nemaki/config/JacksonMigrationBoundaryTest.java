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
package jp.aegif.nemaki.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Jackson 2 → 3 migration boundary, pinned as a test.
 *
 * <p>Jackson 3 ({@code tools.jackson}) is this codebase's databind; Jackson 2
 * ({@code com.fasterxml.jackson.databind} / {@code .core} / {@code .dataformat}) remains on
 * the classpath ONLY for transitives (Cloudant SDK, SolrJ, CXF, the Jersey JSON provider)
 * and for two named boundary files that feed Jackson-2-only consumers. A new
 * {@code com.fasterxml} databind import anywhere else is not a style problem — it creates a
 * second, differently-configured serialization stack whose bytes silently diverge from the
 * golden-pinned profile ({@link JacksonPersistenceGoldenTest}).
 *
 * <p>{@code com.fasterxml.jackson.annotation} is deliberately NOT banned: the Jackson 3 BOM
 * itself ships annotations 2.x as the shared annotation namespace.
 */
class JacksonMigrationBoundaryTest {

    /** Files allowed to touch Jackson 2 databind/core, and why. */
    private static final Set<String> BOUNDARY_FILES = Set.of(
            // Jersey ContextResolvers feeding the Jackson 2 JAX-RS provider. Jersey looks
            // these up as ContextResolver<com.fasterxml…ObjectMapper>, matching on the generic
            // type argument, so the Jackson 2 type IS the contract: migrating one does not
            // rename it, it unplugs it silently. One per Jersey application: /rest and /api/v1.
            "jp/aegif/nemaki/rest/provider/NemakiJacksonProvider.java",
            "jp/aegif/nemaki/api/v1/ApiJacksonProvider.java",
            // Yubico POJOs carry Jackson 2 databind annotations (@JsonDeserialize(builder=…))
            // which Jackson 3 does not read
            "jp/aegif/nemaki/rest/WebAuthnResource.java");

    private static final Pattern JACKSON2_NON_ANNOTATION = Pattern.compile(
            "com\\.fasterxml\\.jackson\\.(databind|core|dataformat)\\b");

    @Test
    @DisplayName("Jackson 2 databind/core は境界ファイル以外から参照されない")
    void jackson2StaysBehindTheBoundary() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> BOUNDARY_FILES.stream()
                            .noneMatch(b -> p.toString().replace('\\', '/').endsWith(b)))
                    .forEach(p -> {
                        String src;
                        try {
                            src = Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        if (JACKSON2_NON_ANNOTATION.matcher(src).find()) {
                            offenders.add(root.relativize(p).toString());
                        }
                    });
        }
        assertTrue(offenders.isEmpty(),
                "Jackson 2 databind/core referenced outside the declared boundary — this forks"
                        + " the serialization stack away from the golden-pinned Jackson 3"
                        + " profile. Either migrate the file to tools.jackson, or (only for a"
                        + " genuine Jackson-2-only consumer) add it to BOUNDARY_FILES with the"
                        + " reason: " + offenders);
    }

    /**
     * Nobody constructs a bare {@code new ObjectMapper()}.
     *
     * <p>Under Jackson 2 that expression meant "the defaults this code was written against".
     * Under Jackson 3 the identical expression means Jackson 3's defaults, which differ in
     * sixteen behaviours — alphabetical property sorting, dates as ISO strings,
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, {@code FAIL_ON_TRAILING_TOKENS} and more. That
     * makes it the migration's most dangerous line: it compiles, reads identically in review,
     * and changes behaviour at every call site at once. {@code
     * ObjectMapperFactory.createDefaultObjectMapper()} is the same expression with the
     * Jackson 2 compatibility profile applied; persistence uses the named profiles instead.
     */
    @Test
    @DisplayName("素の new ObjectMapper() は禁止 — Jackson 3 既定に化けるため")
    void bareMapperConstructionIsBanned() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String tree : List.of("src/main/java", "src/test/java")) {
            Path root = Path.of(tree);
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !isExempt(p))
                        .forEach(p -> {
                            try {
                                if (Files.readString(p, StandardCharsets.UTF_8)
                                        .contains("new ObjectMapper()")) {
                                    offenders.add(root.relativize(p).toString());
                                }
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            }
        }
        assertTrue(offenders.isEmpty(),
                "bare new ObjectMapper() silently adopts Jackson 3 defaults — use"
                        + " ObjectMapperFactory.createDefaultObjectMapper() (Jackson 2"
                        + " compatibility profile) or one of the named persistence profiles: "
                        + offenders);
    }

    /**
     * The boundary files build Jackson 2 mappers on purpose, the factory defines the
     * replacement, and this file quotes the banned expression in order to explain it.
     */
    private static boolean isExempt(Path p) {
        String path = p.toString().replace('\\', '/');
        return BOUNDARY_FILES.stream().anyMatch(path::endsWith)
                || path.endsWith("jp/aegif/nemaki/config/ObjectMapperFactory.java")
                || path.endsWith("jp/aegif/nemaki/config/JacksonMigrationBoundaryTest.java");
    }
}
