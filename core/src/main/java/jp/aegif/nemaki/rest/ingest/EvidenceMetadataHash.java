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
package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.patch.Patch_ArchetypeMetadataEvidenceReadOnly;
import jp.aegif.nemaki.patch.Patch_BusinessRecordMetadataSecondaryType;
import jp.aegif.nemaki.patch.Patch_ChatContextEvidenceReadOnly;
import jp.aegif.nemaki.patch.Patch_ChatContextMetadataSecondaryType;
import jp.aegif.nemaki.patch.Patch_MessageMetadataSecondaryType;
import jp.aegif.nemaki.patch.Patch_NoteMetadataSecondaryType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The recomputable digest over the evidence metadata an ingest actually applied.
 *
 * <p>Design: {@code docs/design/p1-1d-metadata-hash.md}. The two central decisions, restated
 * where the code lives:
 *
 * <ul>
 *   <li><b>Applied values, read back — never the request.</b> The lineage event carries what was
 *       REQUESTED ({@code assuranceAsserted} names it), and the wrapper's writes can fail into a
 *       warning. Hashing the request would notarize a claim.</li>
 *   <li><b>Separate hashes, not one.</b> THREE since 2026-08-24: chat, source identity, and the
 *       archetype homes (mail / note / business record). The original reason was that chat was
 *       READONLY from day one while the source-identity properties were still CMIS-writable, so
 *       "an unrecorded change" and "changed since capture" were different claims and mixing them
 *       would let a legitimate edit read as tampering. <b>All three compartments are READONLY
 *       now</b> — source identity since D-7, the archetypes since (c) §8.1 — so the reason is
 *       narrower today: a mismatch has to name WHICH evidence moved, and one digest over
 *       everything cannot.</li>
 * </ul>
 *
 * <h2>What the escaping guarantees — and what it does not</h2>
 *
 * <p>{@code mh1:SHA-256( join(LF, sorted(propertyId + "=" + escape(value))) )} with
 * {@code \ → \\} and {@code LF → \n}. The escaping makes the JOIN injective over the canonical
 * per-property strings: no value can impersonate a line boundary. Without it the first draft
 * collided — a value containing {@code "\nnemaki:sourceObjectType=…"} swallowed its neighbouring
 * lines, so two DIFFERENT stored states canonicalized identically and a forged object verified
 * as MATCH — inside this design's own threat model (external review P1-1).
 *
 * <p>Injectivity over RAW stored states is deliberately NOT claimed. Canonicalization collapses
 * on purpose: blank equals absent (the word every other evidence path uses), a duplicated
 * property id within an aspect keeps the last occurrence, duplicate aspects of one name merge,
 * and datetime {@code Number}s truncate to whole millis ({@code longValue()}). Each collapse
 * equates states the PRODUCT already treats as the same value; a verifier reporting MATCH across
 * one of them is reporting product semantics, not missing a forgery of the property text
 * (external review of the batch — the earlier heading overclaimed "injective" without this
 * scope).
 */
public final class EvidenceMetadataHash {

    /** The formula version, carried beside every recorded hash so rows outlive the formula. */
    public static final String FORMULA = "mh1";

    /** What the digest is of. Never the request; see the class javadoc. */
    public static final String SUBJECT = "applied";

    /**
     * The {@code nemaki:externalIntegration} properties the hash covers — exactly the set
     * {@code applySourceMetadata} writes, minus {@link #EXCLUDED_FROM_METADATA_HASH}.
     *
     * <p>{@code buildSourceIdentityProps} and this hash read the SAME constant, and a test pins
     * the correspondence: a twelfth put added to one without the other fails rather than leaving
     * a new fact silently outside the hash (external review P2-6 — the first draft counted the
     * set by hand and missed {@code sourceArchetype}).
     */
    public static final List<String> SOURCE_IDENTITY_PROPERTIES = List.of(
            "nemaki:sourceArchetype",
            "nemaki:sourceSystem",
            "nemaki:sourceObjectType",
            "nemaki:sourceObjectId",
            "nemaki:sourceUrl",
            "nemaki:ingestionRunId",
            "nemaki:externalSourceType",
            "nemaki:externalSourceId",
            // The recorded digest VALUE, not the bytes: this line detects the recorded digest
            // being rewritten, which would silently change what the next dedupe compares against.
            "nemaki:contentHash");

    /**
     * Written by the same method but deliberately not hashed.
     *
     * <p>{@code nemaki:externalContext} is up to 1MB of free-form carried JSON — evidence
     * transport, not evidence identity; hashing it turns "did the metadata change" into "did a
     * large blob change". {@code nemaki:externalContextUpdatedAt} is an operational clock that
     * moves on every decoration pass; hashing it would make every re-decoration a change.
     */
    public static final List<String> EXCLUDED_FROM_METADATA_HASH = List.of(
            "nemaki:externalContext",
            "nemaki:externalContextUpdatedAt");

    /**
     * The three DATETIME-typed evidence properties, and only they get datetime normalization.
     *
     * <p>Type-aware on purpose: {@code chatSelectionReason} is caller free text and may
     * legitimately BE an ISO date string — normalizing every parseable string would hash that
     * text as a number and lose the distinction. The three ids mirror the {@code mkDt} calls in
     * {@code Patch_ChatContextMetadataSecondaryType}; the golden-vector test pins the equality
     * of forms per id.
     */
    public static final List<String> DATETIME_EVIDENCE_PROPERTIES = buildDatetimeProperties();

    private static List<String> buildDatetimeProperties() {
        List<String> ids = new ArrayList<>(
                Patch_ChatContextMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        // The archetype homes joined the hash on 2026-08-24. Their datetime properties MUST be
        // here: without normalization a Calendar from a cache hit and a Long from the CouchDB
        // round trip hash differently, so the same stored instant would read as MISMATCH — the
        // F1 regression, one archetype over.
        ids.addAll(Patch_MessageMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        ids.addAll(Patch_NoteMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        ids.addAll(Patch_BusinessRecordMetadataSecondaryType.DATETIME_PROPERTY_IDS);
        return List.copyOf(ids);
    }

    /**
     * The archetype evidence homes — mail, note and business record.
     *
     * <p>One hash across the three rather than three hashes: an object carries at most one
     * archetype aspect ({@code applyArchetypeMetadata} is called once, with one type id), and
     * the property-id namespaces do not overlap, so a union is unambiguous even in the case the
     * product does not produce. Three near-empty fields would say the same thing at four times
     * the width, in the record, in the completion evidence, in the journal fact keys and in the
     * verify response.
     */
    public static final List<String> ARCHETYPE_ASPECT_NAMES = List.of(
            Patch_MessageMetadataSecondaryType.TYPE_ID,
            Patch_NoteMetadataSecondaryType.TYPE_ID,
            Patch_BusinessRecordMetadataSecondaryType.TYPE_ID);

    private EvidenceMetadataHash() {
    }

    /** The hashes for one object's aspects, null where nothing was there to hash. */
    public record AppliedHashes(String chatEvidenceHash, String sourceIdentityHash,
                                String archetypeEvidenceHash) {

        /** Pre-archetype shape, kept so callers that only know the two need not change. */
        public AppliedHashes(String chatEvidenceHash, String sourceIdentityHash) {
            this(chatEvidenceHash, sourceIdentityHash, null);
        }

        public boolean isEmpty() {
            return chatEvidenceHash == null && sourceIdentityHash == null
                    && archetypeEvidenceHash == null;
        }
    }

    /**
     * Computes both hashes from RAW aspects — {@code ContentService.getContent}'s view, not the
     * CMIS-compiled one. The two differ: {@code nemaki:contentHash} has no property declaration,
     * so the compiled view never contains it, and a verifier reading the compiled view would
     * report every object as MISMATCH (external review P2-2).
     */
    public static AppliedHashes compute(List<Aspect> aspects) {
        return new AppliedHashes(
                hashOf(aspects, "nemaki:chatContextMetadata",
                        Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES),
                hashOf(aspects, "nemaki:externalIntegration", SOURCE_IDENTITY_PROPERTIES),
                hashOf(aspects, ARCHETYPE_ASPECT_NAMES,
                        Patch_ArchetypeMetadataEvidenceReadOnly.EVIDENCE_PROPERTIES));
    }

    /** One hash, or null when the aspect holds none of the target properties. */
    static String hashOf(List<Aspect> aspects, String aspectName, List<String> propertyIds) {
        return hashOf(aspects, List.of(aspectName), propertyIds);
    }

    /** As above, across several aspect names whose property-id namespaces do not overlap. */
    static String hashOf(List<Aspect> aspects, List<String> aspectNames, List<String> propertyIds) {
        Map<String, String> canonical = new TreeMap<>();
        if (aspects != null) {
            for (Aspect aspect : aspects) {
                if (aspect == null || !aspectNames.contains(aspect.getName())
                        || aspect.getProperties() == null) {
                    continue;
                }
                for (Property property : aspect.getProperties()) {
                    if (property == null || !propertyIds.contains(property.getKey())) {
                        continue;
                    }
                    String value = canonicalValue(property.getKey(), property.getValue());
                    // Blank counts as absent — the same word every other evidence path uses
                    // (presentValues, addStringProp). A blank line would make "stored empty"
                    // and "never stored" hash differently from how the product treats them.
                    if (value != null && !value.isBlank()) {
                        canonical.put(property.getKey(), value);
                    }
                }
            }
        }
        if (canonical.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(canonical.size());
        for (Map.Entry<String, String> entry : canonical.entrySet()) {
            lines.add(entry.getKey() + "=" + escape(entry.getValue()));
        }
        return FORMULA + ":" + sha256(String.join("\n", lines));
    }

    /**
     * The value as canonical text.
     *
     * <p>For the three datetime properties, every shape a stored instant can take — a live
     * {@code Calendar} from a cache hit, a {@code Double}/{@code Long} from the CouchDB round
     * trip, an ISO or millis string from an older writer — normalizes to the same decimal epoch
     * millis. Treating those as different values is the exact mistake that made a datetime
     * comparison never-true earlier in this work (F1); the normalization is shared with that
     * fix through {@link #toEpochMillis}. An unreadable datetime falls back to its raw string
     * form — deterministic, and still a value rather than a silent absence.
     *
     * <p>For string properties, no normalization: free text that merely LOOKS like a date must
     * stay text.
     */
    static String canonicalValue(String propertyId, Object value) {
        if (value == null) {
            return null;
        }
        if (DATETIME_EVIDENCE_PROPERTIES.contains(propertyId)) {
            Long millis = toEpochMillis(value);
            return millis == null ? String.valueOf(value) : Long.toString(millis);
        }
        if (value instanceof Number n) {
            // Defensive: a string-typed property should never arrive as a number, but if one
            // does, a whole number prints without a fraction whether it was Long or Double.
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString(n.longValue());
            }
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    /** {@code \ → \\}, {@code LF → \n}. What makes the canonical form injective. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /**
     * Epoch millis for the shapes a stored datetime can take, or null when unreadable.
     *
     * <p>Moved here from {@code CanonicalImportServiceImpl} so the hash and the re-import
     * comparison normalize identically — two normalizers is how they drift.
     */
    public static Long toEpochMillis(Object stored) {
        if (stored instanceof java.util.Calendar c) return c.getTimeInMillis();
        if (stored instanceof java.util.Date d) return d.getTime();
        if (stored instanceof Number n) return n.longValue();
        if (stored instanceof String str && !str.isBlank()) {
            try {
                return java.time.Instant.parse(str).toEpochMilli();
            } catch (Exception notIso) {
                try {
                    return Long.parseLong(str.trim());
                } catch (Exception notMillis) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String sha256(String canonical) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm; a JVM without it cannot run this product.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
