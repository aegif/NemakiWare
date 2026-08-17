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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.rest.provider.NemakiJacksonProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Jackson 2 mapper Jersey uses and the Jackson 3 mapper everything else uses agree.
 *
 * <h2>Why a duplicated profile exists at all</h2>
 *
 * <p>Jersey's JSON support resolves the application mapper as
 * {@code ContextResolver<com.fasterxml.jackson.databind.ObjectMapper>} — matched on the
 * generic type argument. So {@code NemakiJacksonProvider} cannot hand it a Jackson 3 mapper;
 * declaring one compiles and wires and is simply never found, at which point Jersey falls
 * back to its own stock mapper and {@code /core/rest/*} loses this profile without a single
 * log line. (That is exactly what the first pass of this migration did.) The Jackson 2
 * spelling of the profile therefore has to live on, next to the Jackson 3 one.
 *
 * <h2>What this test is for</h2>
 *
 * <p>Two hand-maintained copies of a serialization profile drift — that is what they do. This
 * pins them to the only thing that matters: the bytes they emit. If someone changes one
 * profile and not the other, a response served by Jersey stops matching the same object
 * served anywhere else, and this fails with the two renderings side by side.
 */
class JerseyBoundaryMapperParityTest {

    /** A shape that exercises every setting the profile actually sets. */
    private static Map<String, Object> fixture() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "parity-001");
        row.put("count", 42L);                 // WRITE_NUMBERS_AS_STRINGS
        row.put("ratio", 1.5d);                // …including floating point
        row.put("enabled", true);
        row.put("missing", null);              // NON_NULL value inclusion
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("kept", "yes");
        nested.put("dropped", null);           // NON_NULL CONTENT inclusion
        row.put("nested", nested);
        List<String> items = new ArrayList<>();
        items.add("a");
        items.add("b");
        row.put("items", items);
        return row;
    }

    @Test
    @DisplayName("Jersey (Jackson 2) と本体 (Jackson 3) の nemaki プロファイルが同じ bytes を出す")
    void jerseyProfileMatchesTheJackson3Profile() throws Exception {
        String viaJersey = NemakiJacksonProvider.createJerseyObjectMapper()
                .writeValueAsString(fixture());
        String viaFactory = ObjectMapperFactory.createNemakiObjectMapper()
                .writeValueAsString(fixture());

        assertEquals(viaJersey, viaFactory,
                "the Jersey boundary mapper and the migrated nemaki profile disagree — the"
                        + " same object would serialize differently depending on which servlet"
                        + " answered. Whichever profile changed, change the other to match.");
    }

    /**
     * Numbers-as-strings specifically, because it is a wire contract rather than a nicety.
     *
     * <p>Clients of {@code /core/rest/*} have always received quoted numbers from this
     * profile. A migration that quietly dropped the Jersey resolver would flip them to bare
     * JSON numbers — valid JSON, different contract, and nothing in a green test suite would
     * have said so.
     */
    @Test
    @DisplayName("Jersey 側も数値を文字列として書く (既存クライアントの契約)")
    void jerseyStillWritesNumbersAsStrings() throws Exception {
        String json = NemakiJacksonProvider.createJerseyObjectMapper()
                .writeValueAsString(fixture());
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"count\":\"42\""),
                "numbers stopped being quoted on the Jersey path: " + json);
    }
}
