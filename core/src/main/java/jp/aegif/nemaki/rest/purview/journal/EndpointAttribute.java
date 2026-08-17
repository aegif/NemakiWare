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
package jp.aegif.nemaki.rest.purview.journal;

/**
 * One snapshot attribute a {@link EndpointKind} may carry, and what a valid value looks like.
 *
 * <h2>Why the type is declared and not inferred</h2>
 *
 * <p>An allowlist of names alone accepted {@code name=123} and {@code contentLength="-1"}. The
 * catalog would either drop those or store something nobody meant, and increment A-2's
 * {@code creationPayloadDigest} would hash a value whose type depends on which producer wrote it —
 * so the same fact from two code paths would digest differently.
 *
 * <h2>Scalars only</h2>
 *
 * <p>{@code TEXT} and {@code COUNT} are the whole vocabulary. A {@code List} or {@code Map} value
 * would survive {@code Map.copyOf}, which is a shallow copy, and stay mutable after the endpoint
 * was built — the snapshot would then change under an event that had already been emitted.
 * Restricting to immutable scalars makes the shallow copy sufficient.
 */
public record EndpointAttribute(String name, Type type, boolean required, Policy policy) {

    /** §2's per-attribute ceiling, in UTF-16 code units. Frozen, not configurable. */
    public static final int MAX_DISPLAY_CODE_UNITS = 1024;

    /** The suffix of the companion attribute that carries a truncated value's evidence. */
    public static final String EVIDENCE_SUFFIX = "OriginalSha256";

    /**
     * What happens when a value exceeds its ceiling (§2's attribute-limit rules).
     *
     * <p>The default everywhere is {@link #PRESERVE}, and the exceptions are enumerated one by
     * one, because almost everything that looks like a display value turns out not to be:
     * {@code versionSeriesId} is how version lineage is identified, {@code archiveState} is
     * machine-interpreted state, and {@code externalPath} mirrors an identity key under an
     * equality the endpoint's constructor enforces. Truncating any of those manufactures a
     * different fact rather than a shorter rendering of the same one.
     */
    public enum Policy {

        /**
         * Never modified. The value flows on whole, and if the fact then cannot be stored,
         * {@code LineageChunkPlanner} classifies it as durable {@code UNRESOLVED(OVERSIZE)}
         * with the endpoint's record hash — which is §2's rule for that case, and is already
         * implemented. Shortening a required value here would lose the fact instead: the
         * producer's emit path swallows construction failures.
         */
        PRESERVE,

        /**
         * An optional DISPLAY value: over the ceiling it is cut, and a companion attribute
         * {@code {name}OriginalSha256} records the SHA-256 of what it was. Nothing is dropped
         * silently — that is the rule the evidence exists to satisfy.
         */
        TRUNCATE_WITH_EVIDENCE
    }

    public enum Type {

        /** A non-blank {@code String}. */
        TEXT,

        /**
         * A non-negative whole number, as {@code Integer} or {@code Long}.
         *
         * <p>No upper bound below {@code Long.MAX_VALUE}: byte lengths and object counts have no
         * natural ceiling, and a made-up one would reject a legitimately large archive. Negative
         * is the invariant that is actually meaningful, and a {@code Double} or a numeric
         * {@code String} is a producer bug rather than a value to coerce.
         */
        COUNT
    }

    public static EndpointAttribute requiredText(String name) {
        return new EndpointAttribute(name, Type.TEXT, true, Policy.PRESERVE);
    }

    public static EndpointAttribute text(String name) {
        return new EndpointAttribute(name, Type.TEXT, false, Policy.PRESERVE);
    }

    /** An optional display value: long ones are cut, with their original digest recorded. */
    public static EndpointAttribute displayText(String name) {
        return new EndpointAttribute(name, Type.TEXT, false, Policy.TRUNCATE_WITH_EVIDENCE);
    }

    public static EndpointAttribute count(String name) {
        return new EndpointAttribute(name, Type.COUNT, false);
    }

    public static EndpointAttribute requiredCount(String name) {
        return new EndpointAttribute(name, Type.COUNT, true);
    }

    public EndpointAttribute(String name, Type type, boolean required) {
        this(name, type, required, Policy.PRESERVE);
    }

    /** The companion this attribute's evidence is written to. */
    public String evidenceName() {
        return name + EVIDENCE_SUFFIX;
    }

    /** True when this attribute IS a companion — companions are never themselves truncated. */
    public static boolean isEvidenceName(String name) {
        return name != null && name.endsWith(EVIDENCE_SUFFIX);
    }

    /**
     * How far a value may be cut without splitting a surrogate pair.
     *
     * <p>Cutting between the two halves of a pair produces an unpaired surrogate — a string
     * that is no longer valid UTF-16, hashes differently in every implementation that repairs
     * it, and renders as a replacement character. The cut moves back one unit instead, so a
     * truncated value can be {@code ceiling - 1}.
     */
    public static int truncationLength(String value, int ceiling) {
        if (value.length() <= ceiling) {
            return value.length();
        }
        return Character.isHighSurrogate(value.charAt(ceiling - 1)) ? ceiling - 1 : ceiling;
    }

    /**
     * {@code SHA-256} of the ORIGINAL value's UTF-8 bytes, lowercase hex.
     *
     * <p>The <i>evidence</i> kind in {@link LineageDigests}: plain, full width, and the only
     * digest a caller may recompute and compare against a stored {@code ...OriginalSha256}.
     */
    public static String evidenceDigest(String original) {
        return LineageDigests.evidenceDigest(original);
    }

    /** True iff {@code value} is the shape a companion may hold: 64 lowercase hex. */
    public static boolean isEvidenceDigest(Object value) {
        return value instanceof String s && s.length() == 64
                && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    /** @throws IllegalArgumentException if {@code value} is not a valid value for this attribute. */
    public void validate(Object value, EndpointKind kind) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "attribute '" + name + "' must not be null (kind=" + kind + ")");
        }
        switch (type) {
            case TEXT -> {
                if (!(value instanceof String text)) {
                    throw new IllegalArgumentException("attribute '" + name + "' must be a String,"
                            + " got " + value.getClass().getSimpleName() + " (kind=" + kind + ")");
                }
                if (text.isBlank()) {
                    throw new IllegalArgumentException("attribute '" + name + "' must not be blank"
                            + " (kind=" + kind + ")");
                }
            }
            case COUNT -> {
                if (!(value instanceof Integer) && !(value instanceof Long)) {
                    throw new IllegalArgumentException("attribute '" + name + "' must be a whole"
                            + " number, got " + value.getClass().getSimpleName()
                            + " (kind=" + kind + ")");
                }
                if (((Number) value).longValue() < 0) {
                    throw new IllegalArgumentException("attribute '" + name + "' must not be"
                            + " negative, got " + value + " (kind=" + kind + ")");
                }
            }
        }
    }
}
