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
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import java.util.GregorianCalendar;
import java.util.TimeZone;

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
    /**
     * Jackson-2 defaults, lenient about unknown fields, and nothing else.
     *
     * <p>The narrowest profile here, and deliberately not the DAO-delegate one: the row decode
     * that uses it (property-definition details) reads through Jackson's default visibility,
     * and giving it the accessor-only visibility would change which members are populated. Same
     * bytes, same behaviour, one definition — which is all R52 asks of it.
     */
    public static ObjectMapper createLenientReadObjectMapper() {
        return JsonMapper.builderWithJackson2Defaults()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * The DAO-delegate profile: accessor-visible, lenient about unknown fields, and able to
     * read a timestamp the Cloudant SDK has widened to a {@code Double}.
     *
     * <p>Distinct from {@link #createCouchdbObjectMapper()} on purpose. That one persists whole
     * documents with FIELD visibility; this one decodes rows through SETTER / CREATOR /
     * GETTER so {@code @JsonCreator} constructors and validating setters actually run — the
     * property-definition contamination fix depends on it. Consolidating the two would be
     * "one setting", not "one definition", and R52 asks for the second.
     *
     * <p>It was defined twice: here (as {@code DaoHelper.createConfiguredObjectMapper}) and,
     * byte for byte except the module below, as a private copy in
     * {@code ContentDaoServiceImpl}. The copy silently missed the stored-timestamps module, so
     * the R51 fix did not reach it (R52).
     */
    public static ObjectMapper createDaoDelegateObjectMapper() {
        return JsonMapper.builderWithJackson2Defaults()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addModule(storedTimestampsModule())
                .changeDefaultVisibility(vc -> vc
                        .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                        .withVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.ANY)
                        .withVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
                        .withVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.ANY)
                        .withVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.ANY))
                .build();
    }

    /**
     * 2^53: the largest integer a double holds exactly. Above it the SDK's widening has already
     * dropped digits, so the number no longer names the instant that was stored.
     */
    private static final double EXACT_INTEGER_LIMIT = 9007199254740992.0;

    /**
     * Lets a stored timestamp be read back after the Cloudant SDK has widened it.
     *
     * <p>This mapper WRITES a {@code GregorianCalendar} as epoch millis, and CouchDB stores that
     * as a JSON integer — verified on a live archive row ({@code archivedAt: 1786536650704}).
     * But the SDK hands a document back as {@code Map<String, Object>}
     * ({@code Document#getProperties}, {@code ViewResultRow#getValue}), and every JSON number in
     * such a map arrives as a {@code Double}. Re-serialising that map writes
     * {@code 1.786536650704E12}, and Jackson's own calendar deserialiser accepts
     * {@code VALUE_NUMBER_INT} only: the read then failed with "Cannot deserialize value of type
     * java.util.GregorianCalendar from Floating-point value". The same mapper could write the
     * row and not read it back.
     *
     * <p>What that cost: every archive row decoded through those maps counted as unreadable. The
     * trash listing served an empty page (before this branch made it refuse) or a 503 (after) —
     * for a database whose rows are intact. Measured on a tree holding 716,821 archive rows.
     *
     * <p>This only WIDENS what is accepted — an integer, a string and a null are handled exactly
     * as before, by the deserialiser this one delegates the remaining shapes to. A float that is
     * not a whole number of milliseconds is still not a timestamp we wrote, and is refused.
     */
    private static SimpleModule storedTimestampsModule() {
        SimpleModule module = new SimpleModule("nemaki-stored-timestamps");
        module.addDeserializer(GregorianCalendar.class, new ValueDeserializer<GregorianCalendar>() {
            @Override
            public GregorianCalendar deserialize(JsonParser p, DeserializationContext ctxt) {
                if (p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                    double widened = p.getDoubleValue();
                    if (widened != Math.floor(widened) || Double.isInfinite(widened)
                            || Math.abs(widened) > EXACT_INTEGER_LIMIT) {
                        // Refused, not guessed. Two ways a float fails to name the instant this
                        // code wrote, and a review found the second:
                        //   - not a whole millisecond, so it was never one of ours;
                        //   - beyond 2^53, where a double no longer holds every integer. Past
                        //     it the SDK's widening has already lost the last digits (an
                        //     original 9007199254740993 comes back as ...992, one millisecond
                        //     out), and a cast saturates at Long.MAX_VALUE, so 1.0E20 would
                        //     read as a perfectly ordinary date.
                        // Putting a made-up instant on a record is the failure this whole
                        // branch exists to stop.
                        return (GregorianCalendar) ctxt.handleUnexpectedToken(
                                GregorianCalendar.class, p);
                    }
                    GregorianCalendar restored = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                    restored.setTimeInMillis((long) widened);
                    return restored;
                }
                // Every other shape keeps Jackson's own behaviour, including the refusals.
                java.util.Calendar asRead = ctxt.readValue(p, java.util.Calendar.class);
                if (asRead == null) {
                    return null;
                }
                if (asRead instanceof GregorianCalendar alreadyOurs) {
                    return alreadyOurs;
                }
                // NOT null for anything else. Jackson builds this through
                // Calendar.getInstance(), which under a Japanese-imperial FORMAT locale
                // (ja_JP_JP) answers a JapaneseImperialCalendar — not a GregorianCalendar. A
                // review found the first version returning null there: a value that PARSED,
                // reported as the absence of one, on the very field this branch is about. The
                // instant is what was read; only the calendar system differs.
                GregorianCalendar sameInstant = new GregorianCalendar(asRead.getTimeZone());
                sameInstant.setTimeInMillis(asRead.getTimeInMillis());
                return sameInstant;
            }
        });
        return module;
    }

    private static JsonInclude.Value nonNull(JsonInclude.Value current) {
        return current
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL);
    }
}
