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
public record EndpointAttribute(String name, Type type, boolean required) {

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
        return new EndpointAttribute(name, Type.TEXT, true);
    }

    public static EndpointAttribute text(String name) {
        return new EndpointAttribute(name, Type.TEXT, false);
    }

    public static EndpointAttribute count(String name) {
        return new EndpointAttribute(name, Type.COUNT, false);
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
