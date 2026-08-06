/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place NemakiWare's mapper configurations are defined (Jackson 3).
 *
 * <h2>Why every builder starts from {@code builderWithJackson2Defaults()}</h2>
 *
 * <p>These mappers write CouchDB documents, and the byte shape of a stored document is a
 * persistent contract — {@code JacksonPersistenceGoldenTest} pins it. Jackson 3 changed
 * serialization defaults (dates as ISO strings, sorted map entries, and more); starting from
 * the Jackson 2 compatibility profile keeps the persisted bytes exactly what this codebase
 * has always written, so the dependency migration is not silently also a format migration.
 *
 * <p>Mappers are immutable in Jackson 3, so what used to be scattered
 * {@code mapper.configure(...)} mutation is builder configuration here — which is also what
 * stops the three historical copies of each profile (this class and {@code JacksonConfig}
 * carried near-identical definitions) from drifting apart again: {@code JacksonConfig} now
 * delegates here.
 */
public class ObjectMapperFactory {

    /**
     * The DAO-side profile: field-visible, null-omitting, and — deliberately —
     * numbers-as-strings, which is how existing documents store them.
     */
    public static ObjectMapper createNemakiObjectMapper() {
        return baseBuilder()
                .configure(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS, true)
                .build();
    }

    /**
     * The profile {@code CloudantClientWrapper} persists every document with. Identical to
     * the nemaki profile except numbers stay numeric.
     */
    public static ObjectMapper createCouchdbObjectMapper() {
        return baseBuilder().build();
    }

    /** Human-readable output for logs and debugging; never used for persistence. */
    public static ObjectMapper createDebugObjectMapper() {
        return JsonMapper.builderWithJackson2Defaults()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(incl -> nonNull(incl))
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * An unconfigured mapper that still behaves like the Jackson 2 one this code was written
     * against — the replacement for a bare {@code new ObjectMapper()}.
     *
     * <p>In Jackson 3 {@code new ObjectMapper()} means Jackson 3 DEFAULTS, and those differ
     * from Jackson 2 in sixteen behaviours that {@code configureForJackson2()} exists to undo:
     * alphabetical property sorting on, dates as ISO strings rather than timestamps,
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} and {@code FAIL_ON_TRAILING_TOKENS} on,
     * {@code USE_GETTERS_AS_SETTERS} and {@code ALLOW_FINAL_FIELDS_AS_MUTATORS} off, and more.
     * So the migration's most dangerous edit was the one that changed nothing visible: leaving
     * {@code new ObjectMapper()} in place would have flipped all sixteen at ~60 call sites at
     * once — one of them ({@code ExternalIngestController}) reads client-supplied JSON into a
     * bean with primitive fields, where {@code {"dryRun": null}} goes from {@code false} to a
     * thrown exception.
     *
     * <p>Use this for ad-hoc mappers. Persistence goes through the profiles above.
     */
    public static ObjectMapper createDefaultObjectMapper() {
        return JsonMapper.builderWithJackson2Defaults().build();
    }

    private static JsonMapper.Builder baseBuilder() {
        return JsonMapper.builderWithJackson2Defaults()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                // Field access ANY so private fields serialize; accessors stay PUBLIC_ONLY.
                // This is what makes @JsonProperty-mixed models (CouchTypeDefinition and
                // friends) keep their historical shape.
                .changeDefaultVisibility(vc -> vc
                        .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                        .withGetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY)
                        .withSetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY)
                        .withIsGetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY))
                .changeDefaultPropertyInclusion(incl -> nonNull(incl));
    }

    /**
     * NON_NULL for values AND for container CONTENT — what Jackson 2's
     * {@code setSerializationInclusion(NON_NULL)} meant.
     *
     * <p>Jackson 2 expanded that one argument into {@code JsonInclude.Value.construct(incl,
     * incl)}, so it governed map/collection entries too. Setting only the value inclusion
     * leaves content at {@code USE_DEFAULTS}, and {@code MapSerializer} then suppresses
     * nothing — which reaches persistence through {@code CouchNodeBase}'s
     * {@code @JsonAnyGetter}: a null in {@code additionalProperties} would start being stored
     * as an explicit null where it used to be omitted. Presence is load-bearing there
     * (the ACL-epoch markers are read with {@code containsKey}, and Mango selectors use
     * {@code $exists}, which matches a present null), so this is a one-word difference
     * between "same document" and "reclassified document".
     */
    private static JsonInclude.Value nonNull(JsonInclude.Value current) {
        return current
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL);
    }
}
