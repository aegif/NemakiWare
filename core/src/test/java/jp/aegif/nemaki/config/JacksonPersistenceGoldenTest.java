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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
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
 * <h2>Why sibling ORDER is excluded — and why that is not a weakening</h2>
 *
 * <p>The first version of this test compared raw bytes. It passed standalone and failed
 * inside the full suite, which turned out to be a fact about the JVM rather than about
 * Jackson. HotSpot keeps a class's method array sorted by the ADDRESS of each method-name
 * Symbol, and Symbols are interned process-wide in load order. So if any class declaring
 * {@code isFolder()} is loaded before {@code CouchNodeBase}, that class's
 * {@code getDeclaredMethods()} comes back with {@code isFolder} ahead of {@code isDocument} —
 * demonstrated directly, and it reproduces exactly the reordering the suite showed. Jackson
 * derives accessor-property order from that reflection order, in 2.x and 3.x alike.
 *
 * <p>Sibling order of accessor-derived properties is therefore something no JVM promises and
 * this product never had. Asserting it would be asserting noise: the test would fail or pass
 * on class-loading history. What the goldens do assert is everything Jackson actually
 * controls — the set of properties, their values, their ENCODINGS (numbers-as-strings vs
 * numeric, date shape, inclusion of nulls), nesting, and the duplicate-key rewrite quirk.
 * The one ordering rule that IS a Jackson decision — alphabetical sorting, whose default
 * Jackson 3 flipped to ON — is asserted directly in {@link #propertySortingStaysOff()}
 * rather than inferred from byte order.
 *
 * <p>Map-shaped documents (the lineage shape) keep byte-exact comparison: their order comes
 * from {@code LinkedHashMap} insertion, which IS deterministic.
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

    /**
     * @param orderIsDeterministic true for map-shaped fixtures, whose key order comes from
     *        LinkedHashMap insertion rather than from reflection (see the class javadoc).
     */
    private record GoldenCase(String name, ObjectMapper mapper, Object fixture,
            boolean orderIsDeterministic) { }

    private static List<GoldenCase> cases() {
        JacksonConfig config = new JacksonConfig();
        List<GoldenCase> cases = new ArrayList<>();
        // The configurations production wires. couchdb is what CloudantClientWrapper
        // persists EVERY document with; nemaki is the DAO-side bean (numbers as strings).
        cases.add(new GoldenCase("couchdb-content", config.couchdbObjectMapper(),
                contentFixture(), false));
        cases.add(new GoldenCase("couchdb-type", config.couchdbObjectMapper(),
                typeFixture(), false));
        cases.add(new GoldenCase("couchdb-detail", config.couchdbObjectMapper(),
                detailFixture(), false));
        cases.add(new GoldenCase("couchdb-lineage-shape", config.couchdbObjectMapper(),
                lineageShapeFixture(), true));
        cases.add(new GoldenCase("nemaki-content", config.nemakiObjectMapper(),
                contentFixture(), false));
        cases.add(new GoldenCase("nemaki-lineage-shape", config.nemakiObjectMapper(),
                lineageShapeFixture(), true));
        cases.add(new GoldenCase("factory-nemaki-content",
                ObjectMapperFactory.createNemakiObjectMapper(), contentFixture(), false));
        cases.add(new GoldenCase("factory-couchdb-type",
                ObjectMapperFactory.createCouchdbObjectMapper(), typeFixture(), false));
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
            String expected = readGolden(c.name());
            if (c.orderIsDeterministic()) {
                assertEquals(expected, actual, c.name()
                        + ": the serialized bytes moved — that is a persisted-format change,"
                        + " not a refactoring detail");
            } else {
                assertEquals(canonicalize(expected), canonicalize(actual), c.name()
                        + ": a persisted property, value or ENCODING moved (sibling order is"
                        + " excluded — see the class javadoc; it is JVM class-loading order,"
                        + " not a Jackson decision)");
            }
        }
        if (write) {
            fail("goldens (re)written deliberately: " + written + " — inspect and commit them,"
                    + " then run without -Djackson.golden.write");
        }
    }

    /**
     * Reading the golden back and writing it again produces a KNOWN shape — pinned as its
     * own golden, because it is not the input.
     *
     * <p>The Couch models take the whole document through a delegating creator that fills the
     * typed fields AND retains every key in the any-getter map, so a rewrite emits each
     * property twice (typed first, map copy after; CouchDB keeps the last). That is a
     * long-standing quirk of the CURRENT binary, and this migration's discipline is to
     * reproduce behaviour, not to improve it — a dependency swap that also reshapes rewrites
     * would be two changes wearing one commit. The quirk itself is recorded for a separate
     * fix; until then, Jackson 3 must reproduce it (delegating-creator plus any-getter
     * semantics surviving the migration is precisely what this pins).
     */
    @Test
    @DisplayName("保存済み文書の再書込みが今日と同じ (quirky な) 形になる")
    void rewriteShapeIsStable() throws Exception {
        boolean write = Boolean.getBoolean("jackson.golden.write");
        List<String> written = new ArrayList<>();
        JacksonConfig config = new JacksonConfig();
        ObjectMapper couch = config.couchdbObjectMapper();
        for (String name : List.of("couchdb-content", "couchdb-type", "couchdb-detail")) {
            String golden = readGolden(name);
            Object model = switch (name) {
                case "couchdb-content" -> couch.readValue(golden, CouchContent.class);
                case "couchdb-type" -> couch.readValue(golden, CouchTypeDefinition.class);
                default -> couch.readValue(golden, CouchPropertyDefinitionDetail.class);
            };
            String rewritten = couch.writeValueAsString(model);
            if (write) {
                Files.createDirectories(SOURCE_DIR);
                Files.writeString(SOURCE_DIR.resolve(name + ".rewrite.golden.json"), rewritten,
                        StandardCharsets.UTF_8);
                written.add(name);
                continue;
            }
            assertEquals(canonicalize(readGolden(name + ".rewrite")), canonicalize(rewritten),
                    name + ": the rewrite content moved — read-modify-write would reshape"
                            + " stored documents");
        }
        if (write) {
            fail("rewrite goldens (re)written deliberately: " + written + " — they are derived"
                    + " from the input goldens, so regenerate BOTH in one run, inspect, and"
                    + " commit together");
        }
    }

    /**
     * The duplicate keys a rewrite emits carry EQUAL values.
     *
     * <p>This is the property that makes the quirk survivable: CouchDB (like every JSON
     * reader) keeps the last occurrence, so duplicates are harmless only while both copies
     * say the same thing. If the typed field and the any-getter copy ever diverged, a rewrite
     * would silently overwrite the typed value with a stale map copy — and no byte-order
     * assertion would notice. Asserting it directly is worth more than the byte order the
     * previous version of this test was accidentally checking.
     */
    @Test
    @DisplayName("再書込みの重複キーは同じ値を持つ — last-wins が無害である根拠")
    void duplicateKeysAgreeOnValue() throws Exception {
        for (String name : List.of("couchdb-content", "couchdb-type", "couchdb-detail")) {
            Map<String, String> seen = new LinkedHashMap<>();
            for (String entry : canonicalize(readGolden(name + ".rewrite"))) {
                int sep = entry.indexOf(SEP);
                String path = entry.substring(0, sep);
                String value = entry.substring(sep + SEP.length());
                String previous = seen.put(path, value);
                if (previous != null) {
                    assertEquals(previous, value, name + ": the rewrite emits '" + path
                            + "' twice with DIFFERENT values — last-wins would then change"
                            + " the stored value on every read-modify-write");
                }
            }
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
    private static final String SEP = " = ";

    private static List<String> canonicalize(String json) throws IOException {
        ObjectMapper reader = ObjectMapperFactory.createCouchdbObjectMapper();
        List<String> entries = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        // One counter per open container: >= 0 counts array positions, -1 marks an object.
        Deque<int[]> counters = new ArrayDeque<>();
        try (JsonParser p = reader.createParser(json)) {
            String pendingName = null;
            for (JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                if (t == JsonToken.PROPERTY_NAME) {
                    pendingName = p.currentName();
                    continue;
                }
                if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
                    path.pop();
                    counters.pop();
                    pendingName = null;
                    continue;
                }
                String step = nextStep(counters, pendingName);
                pendingName = null;
                if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                    path.push(step);
                    counters.push(new int[] {t == JsonToken.START_ARRAY ? 0 : -1});
                } else {
                    entries.add(join(path) + step + SEP + rawValue(p, t));
                }
            }
        }
        entries.sort(null);
        return entries;
    }

    /** An array slot when inside an array, a dotted name inside an object, "" at the root. */
    private static String nextStep(Deque<int[]> counters, String pendingName) {
        int[] enclosing = counters.peek();
        if (enclosing != null && enclosing[0] >= 0) {
            return "[" + (enclosing[0]++) + "]";
        }
        return pendingName == null ? "" : "." + pendingName;
    }

    private static String join(Deque<String> path) {
        StringBuilder sb = new StringBuilder();
        List<String> steps = new ArrayList<>(path);
        for (int i = steps.size() - 1; i >= 0; i--) {
            sb.append(steps.get(i));
        }
        return sb.toString();
    }

    /** The scalar's JSON spelling, tagged with its token so 42 and "42" never collapse. */
    private static String rawValue(JsonParser p, JsonToken t) {
        return switch (t) {
            case VALUE_STRING -> "str:" + p.getString();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "num:" + p.getNumberValue();
            case VALUE_TRUE, VALUE_FALSE -> "bool:" + p.getBooleanValue();
            case VALUE_NULL -> "null";
            default -> "other:" + t;
        };
    }

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
