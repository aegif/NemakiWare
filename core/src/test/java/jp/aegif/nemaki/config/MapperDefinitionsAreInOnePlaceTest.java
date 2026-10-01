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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjectMapperFactory} says it is "the one place NemakiWare's mapper configurations are
 * defined". This measures the sentence (R52).
 *
 * <h2>What the claim cost while it was untrue</h2>
 *
 * <p>The DAO-delegate profile existed twice — in {@code DaoHelper} and, byte for byte except one
 * module, as a private copy in {@code ContentDaoServiceImpl}. The copy missed the
 * stored-timestamps deserialiser, so the R51 fix landed in one and not the other. A third was
 * built INSIDE a method in {@code TypeDefinitionDaoDelegate}, where no search for "the mappers"
 * would find it. Counting by hand found three; counting by grep found six.
 *
 * <h2>Two things are pinned, because either alone is satisfiable without the other</h2>
 *
 * <p>The structural half: every {@code JsonMapper.builder…} in main/ is either in the factory or
 * on the exception list below, WITH a reason. The behavioural half: the mapper reached through
 * each DAO entry point actually carries the module — a file can be moved without the wiring
 * following it.
 */
class MapperDefinitionsAreInOnePlaceTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * Files allowed to build their own mapper, and why.
     *
     * <p>These are NOT persistence profiles. Adding to this list is a decision: it says the
     * mapper's settings belong to one caller and mean nothing to the others.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "jp/aegif/nemaki/config/ObjectMapperFactory.java",
                    "the one place the profiles are defined",
            "jp/aegif/nemaki/audit/AuditLogger.java",
                    "audit records: its own date and ordering settings, not persistence",
            "jp/aegif/nemaki/rest/purview/journal/LineageSpoolCodec.java",
                    "spool JSON: needs STRICT_DUPLICATE_DETECTION, which persistence does not",
            "jp/aegif/nemaki/rest/eark/SipVerifier.java",
                    "the third-party verifier: deliberately NOT the product's configuration, "
                            + "and it needs STRICT_DUPLICATE_DETECTION");

    @Test
    @DisplayName("no mapper is defined outside the factory except the declared exceptions")
    void everyMapperDefinitionIsDeclared() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("JsonMapper.builder")) {
                    continue;
                }
                String relative = MAIN.relativize(file).toString();
                if (!ALLOWED.containsKey(relative)) {
                    offenders.add(relative);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "a mapper is configured outside ObjectMapperFactory. Either move the definition "
                        + "there, or add the file to ALLOWED with the reason its settings belong "
                        + "to one caller:\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the exception list names files that exist and still build a mapper")
    void theExceptionListIsNotStale() throws IOException {
        // An exception list nobody prunes stops being a list of decisions and becomes a list of
        // names. Each entry has to still be doing the thing it was excused for.
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> allowed : ALLOWED.entrySet()) {
            Path file = MAIN.resolve(allowed.getKey());
            if (!Files.exists(file)) {
                stale.add(allowed.getKey() + " (no such file)");
                continue;
            }
            if (!Files.readString(file, StandardCharsets.UTF_8).contains("JsonMapper.builder")) {
                stale.add(allowed.getKey() + " (no longer builds a mapper)");
            }
        }

        assertTrue(stale.isEmpty(), "the exception list is stale:\n  " + String.join("\n  ", stale));
    }

    @Test
    @DisplayName("the DAO-delegate mapper reads a timestamp the SDK widened, from the factory")
    void theDaoDelegateMapperCarriesTheModule() {
        // The behavioural half. R51's failure was a mapper that could write a value and not read
        // it back; the structural test above would be satisfied by a factory method that simply
        // forgot the module.
        Map<String, Object> widened = new LinkedHashMap<>();
        widened.put("archivedAt", 1.786536650704E12);

        // assertDoesNotThrow: without the module Jackson REFUSES the float outright, so a bare
        // call fails with an exception rather than on this test's own assertion — and the
        // runner counts that as breaking the harness, not as the lock firing (measured).
        GregorianCalendar read = assertDoesNotThrow(
                () -> ObjectMapperFactory.createDaoDelegateObjectMapper()
                        .convertValue(widened, Widened.class).archivedAt,
                "the DAO-delegate mapper cannot read a timestamp the SDK widened");

        assertNotNull(read, "the widened timestamp did not survive the mapper");
        assertEquals(1786536650704L, read.getTimeInMillis());
    }

    @Test
    @DisplayName("the profiles stay distinct — one definition is not one setting")
    void theProfilesAreNotMerged() {
        // R52 asks for one PLACE, not one mapper. The measurable difference is VISIBILITY: the
        // couchdb profile serialises private FIELDS — that is what keeps @JsonProperty-mixed
        // models in their historical byte shape — and the DAO-delegate profile does not, because
        // it reads and writes through accessors so validating setters and @JsonCreator run.
        //
        // The first version of this test asserted the difference in the wrong place (both
        // profiles read a public setter) and failed on its own assertion. Which is the argument
        // for writing the assertion before believing the sentence.
        PrivateField model = new PrivateField("stored", "visible");

        Map<?, ?> fromCouchdb = ObjectMapperFactory.createCouchdbObjectMapper()
                .convertValue(model, Map.class);
        Map<?, ?> fromDelegate = ObjectMapperFactory.createDaoDelegateObjectMapper()
                .convertValue(model, Map.class);

        assertTrue(fromCouchdb.containsKey("hidden"),
                "the couchdb profile stopped serialising private fields, which is what keeps "
                        + "stored documents in their historical shape: " + fromCouchdb);
        assertTrue(!fromDelegate.containsKey("hidden"),
                "the DAO-delegate profile now serialises private fields — the two profiles have "
                        + "been merged, which is not what R52 asked for: " + fromDelegate);
    }

    /** A model with a GregorianCalendar the delegate profile has to restore. */
    public static class Widened {
        public GregorianCalendar archivedAt;

        public void setArchivedAt(GregorianCalendar archivedAt) {
            this.archivedAt = archivedAt;
        }
    }

    /**
     * A private field with no accessor, beside one that has a getter.
     *
     * <p>The second property is not decoration: with only the private one, the DAO-delegate
     * profile finds nothing to write and refuses the bean outright, which measures a different
     * difference (that profile does not disable FAIL_ON_EMPTY_BEANS) than the one named here.
     */
    public static class PrivateField {
        private final String hidden;
        private final String shown;

        public PrivateField(String hidden, String shown) {
            this.hidden = hidden;
            this.shown = shown;
        }

        public String getShown() {
            return shown;
        }
    }
}
