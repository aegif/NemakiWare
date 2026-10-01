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
package jp.aegif.nemaki.model.couch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import tools.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.config.ObjectMapperFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A stored document round-trips into the same document — not into a doubled one.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Every Couch model funnels unmodelled keys into a {@code @JsonAnyGetter} map. Two paths
 * used to fill that map with keys the class ALSO writes as typed properties: the delegating
 * {@code @JsonCreator} copied the whole document into it, and the {@code @JsonAnySetter}
 * caught the read-only derived properties ({@code isDocument()}, {@code isFolder()} and
 * friends have no field and no setter, so they are written but cannot be read back). Either
 * way a stored document serialized every property twice, and since the map is written LAST,
 * its copy is the one that survives — {@code CloudantClientWrapper} converts through
 * {@code Map.class} before writing, and a Map keeps the last occurrence.
 *
 * <p>That made read-modify-write a trap: mutate a typed field on a document you just read,
 * write it back, and the stale copy from the map silently wins. The current DAOs happen to
 * avoid it (they build a fresh model from the domain object and carry over only the
 * revision), which is exactly why this could sit unnoticed — the next person to write the
 * obvious code would have been the one to lose data.
 */
class CouchModelSerializationShapeTest {

    private static final Path MODEL_DIR =
            Path.of("src/main/java/jp/aegif/nemaki/model/couch");

    /** Every Couch model, discovered from the source tree so a new one cannot be forgotten. */
    private static List<Class<?>> models() throws IOException {
        List<Class<?>> models = new ArrayList<>();
        try (Stream<Path> files = Files.list(MODEL_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String simple = p.getFileName().toString().replace(".java", "");
                Class<?> c = Class.forName(CouchNodeBase.class.getPackageName() + "." + simple);
                if (CouchNodeBase.class.isAssignableFrom(c)
                        && !Modifier.isAbstract(c.getModifiers())) {
                    models.add(c);
                }
            }
        } catch (ClassNotFoundException e) {
            throw new jp.aegif.nemaki.util.test.HarnessBroken(
                    "model class missing for a source file", e);
        }
        models.sort(java.util.Comparator.comparing(Class::getSimpleName));
        assertTrue(models.size() > 15, "the model package should not have shrunk: " + models);
        return models;
    }

    /**
     * The reflective set the models filter on equals the keys Jackson actually writes.
     *
     * <p>The filter is only as good as its agreement with Jackson's own introspection. Rather
     * than restate Jackson's rules and hope, this serializes each model with an empty
     * any-getter map and compares the emitted keys to the set — so a visibility change, a
     * renamed {@code @JsonProperty} or a new derived getter fails here instead of quietly
     * reintroducing duplicates.
     */
    @Test
    @DisplayName("反射で求めた型付きプロパティ名が Jackson の実出力と一致する (全モデル)")
    void filterSetMatchesWhatJacksonEmits() throws Exception {
        ObjectMapper mapper = ObjectMapperFactory.createCouchdbObjectMapper();
        List<String> mismatches = new ArrayList<>();
        for (Class<?> model : models()) {
            Object instance = model.getDeclaredConstructor().newInstance();
            // NON_NULL inclusion would hide every unset property, so give each one a value.
            Map<String, Object> emitted = mapper.readValue(
                    mapper.writeValueAsString(instance), Map.class);
            Set<String> declared = new TreeSet<>(CouchNodeBaseAccess.serializedNames(model));
            Set<String> actual = new TreeSet<>(emitted.keySet());
            // Only compare what was actually emitted: an unset property is absent, but it is
            // still a typed name and must be in the declared set.
            if (!declared.containsAll(actual)) {
                Set<String> missing = new TreeSet<>(actual);
                missing.removeAll(declared);
                mismatches.add(model.getSimpleName() + ": Jackson writes " + missing
                        + " but the filter does not know them");
            }
        }
        assertEquals(List.of(), mismatches,
                "the any-getter filter has drifted from Jackson's introspection — the keys"
                        + " listed would be written twice, and the stale copy wins");
    }

    /**
     * Reading a document and writing it back produces the same keys, each exactly once.
     *
     * <p>This is the property the duplicate emission broke. It is checked per model with a
     * document that carries every typed key the model writes plus a genuinely extra one, so
     * both the delegating-creator path and the any-setter path are exercised.
     */
    @Test
    @DisplayName("保存済み文書の読み書き往復でキーが重複しない (全モデル)")
    void rewriteDoesNotDuplicateKeys() throws Exception {
        ObjectMapper mapper = ObjectMapperFactory.createCouchdbObjectMapper();
        List<String> offenders = new ArrayList<>();
        for (Class<?> model : models()) {
            Object fresh = model.getDeclaredConstructor().newInstance();
            Map<String, Object> stored = new LinkedHashMap<>(mapper.readValue(
                    mapper.writeValueAsString(fresh), Map.class));
            stored.put("_id", "shape-001");
            stored.put("_rev", "1-shape");
            stored.put("type", "cmis:document");
            stored.put("aVendorExtension", "kept");

            Object read = mapper.readValue(mapper.writeValueAsString(stored), model);
            String rewritten = mapper.writeValueAsString(read);
            List<String> keys = topLevelKeys(rewritten);
            Set<String> unique = new TreeSet<>(keys);
            if (keys.size() != unique.size()) {
                List<String> dupes = new ArrayList<>(keys);
                unique.forEach(dupes::remove);
                offenders.add(model.getSimpleName() + " duplicates " + new TreeSet<>(dupes));
            }
            assertTrue(keys.contains("aVendorExtension"),
                    model.getSimpleName() + ": an unmodelled key must survive the round trip —"
                            + " that is what the any-getter map is FOR");
        }
        assertEquals(List.of(), offenders,
                "a rewrite emits these keys twice; the last one wins, so a modified typed"
                        + " field would be overwritten by the stale copy");
    }

    /**
     * A modification survives read-modify-write.
     *
     * <p>The end the duplication threatened, stated directly: change a typed field on a model
     * that was read from storage, write it, and the new value must be what a reader sees.
     */
    @Test
    @DisplayName("読んで書き換えて書き戻すと、変更が残る")
    void modificationSurvivesRewrite() throws Exception {
        ObjectMapper mapper = ObjectMapperFactory.createCouchdbObjectMapper();
        String stored = "{\"_id\":\"rmw-001\",\"_rev\":\"1-a\",\"type\":\"cmis:document\","
                + "\"name\":\"before\",\"aVendorExtension\":\"kept\"}";

        CouchContent read = mapper.readValue(stored, CouchContent.class);
        read.setName("after");
        // The production write path converts through Map.class, where a duplicate key would
        // collapse onto the LAST occurrence — reproduce that rather than trusting the raw JSON.
        Map<String, Object> written = mapper.convertValue(read, Map.class);

        assertEquals("after", written.get("name"),
                "the modification was overwritten by the copy the any-getter map carried");
        assertEquals("kept", written.get("aVendorExtension"),
                "an unmodelled key must still be preserved");
    }

    /** Top-level keys in document order, duplicates included. */
    private static List<String> topLevelKeys(String json) throws Exception {
        List<String> keys = new ArrayList<>();
        ObjectMapper mapper = ObjectMapperFactory.createCouchdbObjectMapper();
        try (tools.jackson.core.JsonParser p = mapper.createParser(json)) {
            int depth = 0;
            for (tools.jackson.core.JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                if (t == tools.jackson.core.JsonToken.START_OBJECT
                        || t == tools.jackson.core.JsonToken.START_ARRAY) {
                    depth++;
                } else if (t == tools.jackson.core.JsonToken.END_OBJECT
                        || t == tools.jackson.core.JsonToken.END_ARRAY) {
                    depth--;
                } else if (t == tools.jackson.core.JsonToken.PROPERTY_NAME && depth == 1) {
                    keys.add(p.currentName());
                }
            }
        }
        return keys;
    }

    /** Reaches the protected filter set without widening its visibility for production code. */
    private static final class CouchNodeBaseAccess extends CouchNodeBase {
        static Set<String> serializedNames(Class<?> type) {
            return CouchNodeBase.serializedPropertyNames(type);
        }
    }
}
