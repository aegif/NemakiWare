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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.model.couch.CouchContent;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionDetail;
import jp.aegif.nemaki.model.couch.CouchTypeDefinition;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What NemakiWare's mappers persist, pinned before the Jackson 3 migration.
 *
 * <h2>Why goldens, not "the tests pass"</h2>
 *
 * <p>Every CouchDB document this product stores goes through one of these mapper
 * configurations ({@code CloudantClientWrapper} serializes with {@code couchdbObjectMapper};
 * DAO components use {@code nemakiObjectMapper}). Jackson 3 changes serialization defaults —
 * date shapes, feature reorganisation, number encodings — and a changed value encoding in a
 * persisted document is a format migration nobody ordered. "The suite is green" cannot stand
 * in for this: a default flip that serializes dates differently round-trips fine and corrupts
 * nothing visible until two binaries disagree about the same document.
 *
 * <h2>Why byte-exact works again</h2>
 *
 * <p>An earlier version of this test compared raw bytes, passed standalone and failed inside
 * the full suite — a fact about the JVM rather than about Jackson. HotSpot keeps a class's
 * method array sorted by the ADDRESS of each method-name Symbol, and Symbols are interned
 * process-wide in load order, so loading any class that declares {@code isFolder()} before
 * {@code CouchNodeBase} flips that class's {@code getDeclaredMethods()} order. Jackson derives
 * accessor-property order from reflection order, in 2.x and 3.x alike, so the key order of
 * every stored document depended on class-loading history.
 *
 * <p>The models now DECLARE their order ({@code @JsonPropertyOrder(alphabetic = true)} on
 * {@code CouchNodeBase}, and a sorted map behind the {@code @JsonAnyGetter}), so the bytes are
 * a function of the data alone. Byte-exact comparison is therefore back for every case: it is
 * the strongest statement available, and it is now a statement about this code rather than
 * about which class happened to load first.
 *
 * <h2>Regenerating</h2>
 *
 * <p>Deliberately manual: run with {@code -Djackson.golden.write=true}, which writes the
 * goldens into {@code src/test/resources/jackson/} and fails the test so the change is seen
 * and committed on purpose. Regenerate only when the persisted format is MEANT to change.
 */
class JacksonPersistenceGoldenTest {

    private static final String RESOURCE_DIR = "/jackson/";
    private static final Path SOURCE_DIR =
            Path.of("src/test/resources/jackson");

    private static TimeZone savedTimeZone;

    @BeforeAll
    static void pinTimeZone() {
        // Date rendering reads the default zone; bytes are only comparable if it is fixed.
        savedTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void restoreTimeZone() {
        TimeZone.setDefault(savedTimeZone);
    }

    // ------------------------------------------------------------------ fixtures

    /** A document row: dates, booleans, a list, an additional property. */
    private static CouchContent contentFixture() {
        CouchContent content = new CouchContent();
        content.setId("golden-doc-001");
        content.setRevision("1-abcdef0123456789abcdef0123456789");
        content.setType("cmis:document");
        content.setName("golden.txt");
        content.setCreator("system");
        content.setModifier("system");
        java.util.GregorianCalendar when =
                new java.util.GregorianCalendar(TimeZone.getTimeZone("UTC"));
        when.setTimeInMillis(1_700_000_000_000L);
        content.setCreated(when);
        content.setModified(when);
        content.setAdditionalProperty("customFlag", Boolean.TRUE);
        content.setAdditionalProperty("customCount", 42L);
        content.setAdditionalProperty("customTags", List.of("a", "b"));
        // A null INSIDE the any-getter map. Jackson 2's setSerializationInclusion(NON_NULL)
        // suppressed map content as well as bean values, so this key is absent from the
        // goldens — and "absent" versus "present but null" is a real distinction here: the
        // ACL-epoch markers this map carries are read with containsKey, and Mango's
        // {"$exists": true} matches a present null. Without a fixture like this one, a
        // migration that sets only the VALUE inclusion looks byte-identical.
        content.setAdditionalProperty("customAbsent", null);
        return content;
    }

    /** A type definition row: the @JsonProperty-heavy shape with a detail-id list. */
    private static CouchTypeDefinition typeFixture() {
        CouchTypeDefinition type = new CouchTypeDefinition();
        type.setId("golden-type-001");
        type.setType("typeDefinition");
        type.setTypeId("golden:type");
        type.setLocalName("goldenType");
        type.setQueryName("golden:type");
        type.setDisplayName("Golden Type");
        List<String> properties = new ArrayList<>();
        properties.add("golden-detail-001");
        properties.add("golden-detail-002");
        type.setProperties(properties);
        return type;
    }

    /** A property-definition detail row. */
    private static CouchPropertyDefinitionDetail detailFixture() {
        CouchPropertyDefinitionDetail detail = new CouchPropertyDefinitionDetail();
        detail.setId("golden-detail-001");
        detail.setType("propertyDefinitionDetail");
        detail.setCoreNodeId("golden-core-001");
        detail.setLocalName("goldenProp");
        detail.setDisplayName("Golden Property");
        return detail;
    }

    /** The lineage-document shape: nested maps with longs, booleans and lists. */
    private static Map<String, Object> lineageShapeFixture() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "golden-lineage-001");
        doc.put("type", "lineage_event_v2");
        doc.put("schemaVersion", 2);
        doc.put("sequenceNumber", 9_007_199_254_740_991L);
        doc.put("chunkIndex", 0);
        doc.put("active", true);
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("targets", List.of("atlas"));
        doc.put("delivery", delivery);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("atlas", "PENDING");
        doc.put("publishStatusByTarget", status);
        return doc;
    }

    // ------------------------------------------------------------------ machinery

    private record GoldenCase(String name, ObjectMapper mapper, Object fixture) { }

    private static List<GoldenCase> cases() {
        JacksonConfig config = new JacksonConfig();
        List<GoldenCase> cases = new ArrayList<>();
        // The configurations production wires. couchdb is what CloudantClientWrapper
        // persists EVERY document with; nemaki is the DAO-side bean (numbers as strings).
        cases.add(new GoldenCase("couchdb-content", config.couchdbObjectMapper(),
                contentFixture()));
        cases.add(new GoldenCase("couchdb-type", config.couchdbObjectMapper(),
                typeFixture()));
        cases.add(new GoldenCase("couchdb-detail", config.couchdbObjectMapper(),
                detailFixture()));
        cases.add(new GoldenCase("couchdb-lineage-shape", config.couchdbObjectMapper(),
                lineageShapeFixture()));
        cases.add(new GoldenCase("nemaki-content", config.nemakiObjectMapper(),
                contentFixture()));
        cases.add(new GoldenCase("nemaki-lineage-shape", config.nemakiObjectMapper(),
                lineageShapeFixture()));
        cases.add(new GoldenCase("factory-nemaki-content",
                ObjectMapperFactory.createNemakiObjectMapper(), contentFixture()));
        cases.add(new GoldenCase("factory-couchdb-type",
                ObjectMapperFactory.createCouchdbObjectMapper(), typeFixture()));
        return cases;
    }

    @Test
    @DisplayName("永続される内容と符号化が Jackson 2 と同一である")
    void persistedContentIsStable() throws Exception {
        boolean write = Boolean.getBoolean("jackson.golden.write");
        List<String> written = new ArrayList<>();
        for (GoldenCase c : cases()) {
            String actual = c.mapper().writeValueAsString(c.fixture());
            if (write) {
                Files.createDirectories(SOURCE_DIR);
                Files.writeString(SOURCE_DIR.resolve(c.name() + ".golden.json"), actual,
                        StandardCharsets.UTF_8);
                written.add(c.name());
                continue;
            }
            assertEquals(readGolden(c.name()), actual, c.name()
                    + ": the serialized bytes moved — that is a persisted-format change,"
                    + " not a refactoring detail");
        }
        if (write) {
            fail("goldens (re)written deliberately: " + written + " — inspect and commit them,"
                    + " then run without -Djackson.golden.write");
        }
    }

    /**
     * A stored document reads back and writes out as the SAME document.
     *
     * <p>It did not use to. The models funnelled the whole stored document into their
     * {@code @JsonAnyGetter} map and echoed the read-only derived properties into it as well,
     * so a rewrite emitted every property twice — and because the map is written last, its
     * copy is the one that survives a Map conversion, which is how {@code
     * CloudantClientWrapper} writes. A modified field was therefore overwritten by a stale
     * copy of itself. The mechanism is pinned per class in
     * {@code CouchModelSerializationShapeTest}; here it is stated where it is most visible —
     * bytes in, identical bytes out.
     */
    @Test
    @DisplayName("保存済み文書の読み書き往復が同一 bytes に戻る")
    void rewriteIsIdentity() throws Exception {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper couch = config.couchdbObjectMapper();
        for (String name : List.of("couchdb-content", "couchdb-type", "couchdb-detail")) {
            String golden = readGolden(name);
            Object model = switch (name) {
                case "couchdb-content" -> couch.readValue(golden, CouchContent.class);
                case "couchdb-type" -> couch.readValue(golden, CouchTypeDefinition.class);
                default -> couch.readValue(golden, CouchPropertyDefinitionDetail.class);
            };
            assertEquals(golden, couch.writeValueAsString(model),
                    name + ": a read-modify-write would reshape stored documents");
        }
    }

    /**
     * Alphabetical property sorting stays OFF.
     *
     * <p>Jackson 3 flipped {@code SORT_PROPERTIES_ALPHABETICALLY}'s default from off to on
     * (verified in the shipped enum: 2.x constructs it with {@code false}, 3.x with
     * {@code true}). That IS a Jackson decision about ordering — unlike sibling order from
     * reflection — so it is asserted where it lives instead of being inferred from bytes.
     */
    @Test
    @DisplayName("アルファベット順ソートは無効のまま (Jackson 3 は既定を反転した)")
    void propertySortingStaysOff() {
        for (GoldenCase c : cases()) {
            assertFalse(
                    c.mapper().serializationConfig()
                            .isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY),
                    c.name() + ": alphabetical sorting turned itself on — every persisted"
                            + " document would be rewritten in a different key order");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A document as a SORTED list of {@code path=rawValue} entries.
     *
     * <p>Duplicates are preserved (the rewrite quirk depends on them), array positions are
     * part of the path, and scalars keep their JSON spelling — {@code "9007199254740991"} and
     * {@code 9007199254740991} do not collapse, which is the whole point for a mapper that
     * writes numbers as strings. Only the order of sibling object properties is discarded.
     */
    private static String readGolden(String name) throws IOException {
        try (InputStream in = JacksonPersistenceGoldenTest.class
                .getResourceAsStream(RESOURCE_DIR + name + ".golden.json")) {
            if (in == null) {
                fail("golden '" + name + "' is missing — generate deliberately with"
                        + " -Djackson.golden.write=true and commit the files");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
