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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical form of an evidence JSON document — the bytes a {@code .c14n} file holds.
 *
 * <p>Specified in {@code docs/design/evidence-profile-v1.md} §3.2, which is what a third party
 * writes their verifier from. This class exists so the product and that verifier can be shown
 * to agree on the same vectors; it is not a general-purpose JSON canonicaliser and should not
 * grow into one.
 *
 * <h2>What it refuses, and why refusing is the point</h2>
 *
 * <p>A canonical form is only worth having if each document has exactly ONE of them. Three
 * shapes would give a document two, so all three are rejected as malformed rather than
 * normalised into something:
 *
 * <ul>
 * <li><b>Duplicate keys.</b> JSON parsers usually keep the last. Then the same bytes mean two
 *     things depending on who reads them, and "the canonical form" is whichever the writer's
 *     parser chose. Jackson can be told to refuse, and is.</li>
 * <li><b>Non-integral numbers.</b> The typed encoding has LONG and nothing else, so a
 *     canonicaliser that met {@code 1.5} would have to round — and the hash of the rounded
 *     value is not the hash of the document. Out-of-range integers are the same case.</li>
 * <li><b>Anything the encoding has no tag for.</b> Silently stringifying is how a digest comes
 *     to depend on {@code toString()}.</li>
 * </ul>
 *
 * <p><b>Refusal is an exception, not a value.</b> Returning a digest of "the parts we could
 * read" would be exactly the defect this branch is named after: a read that failed reported
 * with the same value as a read that answered.
 */
public final class CanonicalJson {

    /**
     * Strict duplicate detection is on here and cannot be turned off by a caller, because a
     * lenient parse does not produce a weaker canonical form — it produces a WRONG one, and one
     * that verifies against itself.
     */
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private CanonicalJson() {
    }

    /** A document that has no canonical form. Carries why, because "malformed" is not a reason. */
    public static class NotCanonicalisable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotCanonicalisable(String message) {
            super(message);
        }

        public NotCanonicalisable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The canonical bytes of {@code json}.
     *
     * @throws NotCanonicalisable when the document has no single canonical form
     */
    public static byte[] canonicalBytes(String json) {
        return LineageCanonicalHash.canonicalBytes(parseStrict(json));
    }

    /** {@code hex(SHA-256(canonicalBytes(json)))} — the profile's {@code documentDigest}. */
    public static String documentDigest(String json) {
        return hex(sha256(canonicalBytes(json)));
    }

    /**
     * Whether a shipped {@code .c14n} is the canonical form of its {@code .json}.
     *
     * <p>Recomputed, never trusted. The whole reason to ship the bytes is that a reader can
     * compare them with their own; a check that read the shipped file and hashed THAT would
     * verify the file against itself.
     */
    public static boolean shippedFormMatches(String json, byte[] shipped) {
        if (shipped == null) {
            return false;
        }
        return java.util.Arrays.equals(canonicalBytes(json), shipped);
    }

    /**
     * JSON to Map / List / String / Long / Boolean / null.
     *
     * <p>Package-visible for the lock that measures the refusals directly; a lock that could
     * only reach them through {@link #documentDigest} would be measuring two things at once.
     */
    static Object parseStrict(String json) {
        if (json == null) {
            throw new NotCanonicalisable("there is no document to canonicalise");
        }
        try (JsonParser parser = FACTORY.createParser(
                tools.jackson.core.ObjectReadContext.empty(), json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new NotCanonicalisable("the document is empty");
            }
            Object value = readValue(parser, first, 0);
            if (parser.nextToken() != null) {
                throw new NotCanonicalisable("the document has trailing content after its "
                        + "top-level value, so it is not one document");
            }
            return value;
        } catch (NotCanonicalisable e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Jackson's duplicate-key refusal arrives here. It is reported as what it is rather
            // than as a parse failure, because the two call for different answers from an
            // operator: one is a corrupt file, the other is a file with two meanings.
            throw new NotCanonicalisable("the document could not be read as canonicalisable "
                    + "JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Depth is bounded here rather than by the recursion blowing the stack.
     *
     * <p>A package is untrusted input, and {@code StackOverflowError} is not something a
     * verifier can report as {@code RESOURCE_LIMIT} — it is an Error, and catching it leaves
     * the JVM in a state nobody should reason about.
     */
    private static final int MAX_DEPTH = 64;

    private static Object readValue(JsonParser parser, JsonToken token, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new NotCanonicalisable("the document nests deeper than " + MAX_DEPTH
                    + " levels, which this reader refuses rather than recursing into");
        }
        switch (token) {
            case VALUE_NULL:
                return null;
            case VALUE_STRING:
                return parser.getString();
            case VALUE_TRUE:
                return Boolean.TRUE;
            case VALUE_FALSE:
                return Boolean.FALSE;
            case VALUE_NUMBER_INT:
                return integerOrRefuse(parser);
            case VALUE_NUMBER_FLOAT:
                throw new NotCanonicalisable("the typed encoding has no tag for a non-integral "
                        + "number, and rounding " + parser.getString() + " would produce the "
                        + "digest of a value the document does not contain");
            case START_ARRAY: {
                List<Object> list = new ArrayList<>();
                JsonToken element;
                while ((element = parser.nextToken()) != JsonToken.END_ARRAY) {
                    if (element == null) {
                        throw new NotCanonicalisable("the document ends inside an array");
                    }
                    list.add(readValue(parser, element, depth + 1));
                }
                return list;
            }
            case START_OBJECT: {
                // Insertion-ordered on the way in; the ENCODER sorts by UTF-8 bytes. Sorting
                // here as well would hide an encoder that stopped sorting.
                Map<String, Object> map = new LinkedHashMap<>();
                JsonToken field;
                while ((field = parser.nextToken()) != JsonToken.END_OBJECT) {
                    if (field == null) {
                        throw new NotCanonicalisable("the document ends inside an object");
                    }
                    String name = parser.currentName();
                    map.put(name, readValue(parser, parser.nextToken(), depth + 1));
                }
                return map;
            }
            default:
                throw new NotCanonicalisable("the typed encoding has no tag for " + token);
        }
    }

    private static Long integerOrRefuse(JsonParser parser) throws IOException {
        try {
            return parser.getLongValue();
        } catch (RuntimeException e) {
            // Jackson reports an integer wider than long as an input-coercion failure. Saturating
            // to Long.MAX_VALUE — which is what a naive read does — turns an unrepresentable
            // number into an ordinary-looking one, and the digest then commits to a value the
            // document never held.
            throw new NotCanonicalisable("the integer " + parser.getString() + " does not fit in "
                    + "int64, and the typed encoding has no wider tag", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** UTF-8, because the canonical form is defined over bytes and a platform default is not. */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
