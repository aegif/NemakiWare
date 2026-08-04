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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one SHA-256 primitive, and the names for what a lineage digest can mean.
 *
 * <p>Seven places used to call {@code MessageDigest.getInstance("SHA-256")} directly, under five
 * different method names ({@code hash}, {@code sha256}, {@code sha256Hex}, {@code shortDigest},
 * {@code evidenceDigest}). They all produced lowercase hex of UTF-8 bytes, which made them look
 * interchangeable. They are not. Two of them are truncated, one is domain-separated, and only
 * one kind may ever be compared against a stored value.
 *
 * <h2>The four kinds</h2>
 *
 * <table border="1">
 * <caption>What a lineage digest can mean</caption>
 * <tr><th>kind</th><th>owner</th><th>domain-separated</th><th>width</th><th>comparable to</th></tr>
 * <tr>
 *   <td><b>identity</b></td>
 *   <td>{@link LineageCanonicalHash#hash}</td>
 *   <td>yes — the typed, length-prefixed encoding <i>is</i> the separation</td>
 *   <td>64 hex</td>
 *   <td>another identity hash of the same typed shape, and nothing else</td>
 * </tr>
 * <tr>
 *   <td><b>distribution</b></td>
 *   <td>{@link LineageBinaryDigest}</td>
 *   <td>yes — {@code BARRIER_BINARY_V1} tag, built on identity</td>
 *   <td>64 hex</td>
 *   <td>an approved digest in configuration</td>
 * </tr>
 * <tr>
 *   <td><b>evidence</b></td>
 *   <td>{@link #evidenceDigest}</td>
 *   <td>no — plain SHA-256 of the original UTF-8 bytes</td>
 *   <td>64 hex</td>
 *   <td>a recomputation from the same original value</td>
 * </tr>
 * <tr>
 *   <td><b>redaction</b></td>
 *   <td>{@link #redactionDigest}</td>
 *   <td>no</td>
 *   <td><b>{@value #REDACTION_HEX_CHARS} hex — truncated</b></td>
 *   <td><b>nothing.</b> Log-side only: it says "same value" or "different value" to a human</td>
 * </tr>
 * </table>
 *
 * <p><b>Why the widths matter.</b> A redaction digest is a prefix of an evidence digest of the
 * same string. If one is ever accepted where the other is expected, a 12-hex value would pass a
 * "looks like a digest" check while carrying 52 fewer hex characters of collision resistance.
 * {@link EndpointAttribute#isEvidenceDigest} exists to refuse exactly that, and a test pins it.
 *
 * <p><b>Why domain separation is not unified away.</b> This class deliberately holds only the
 * primitive and the two plain kinds. {@link LineageCanonicalHash} and {@link LineageBinaryDigest}
 * keep their own encoders: their whole contract is that the bytes fed to SHA-256 carry type tags
 * and lengths, so that {@code hash("ab", "c")} and {@code hash("a", "bc")} differ. Routing them
 * through a "shared" plain helper would be the one refactoring that silently breaks them, and
 * their golden vectors are frozen on disk.
 */
final class LineageDigests {

    /** Hex characters kept in a redaction digest. Half of a UUID's worth — enough to compare. */
    static final int REDACTION_HEX_CHARS = 12;

    private LineageDigests() {
    }

    /**
     * A fresh SHA-256 {@link MessageDigest}, for callers that stream rather than hold bytes.
     *
     * <p>SHA-256 is mandatory in every conforming JVM (JLS/JCA), so its absence is not a runtime
     * condition to recover from — it is a broken platform, and {@link AssertionError} says so.
     */
    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /** Lowercase hex of the SHA-256 of {@code bytes}. The primitive; no domain, no truncation. */
    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    /** Lowercase hex of the SHA-256 of {@code value}'s UTF-8 bytes. */
    static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Evidence that a value existed, for a value that was stored truncated or not stored at all.
     *
     * <p>Identical to {@link #sha256Hex} by construction — the separate name is the point. A
     * caller writing {@code nameOriginalSha256} is asserting "recomputing SHA-256 over the
     * original reproduces this", which is a contract; {@code sha256Hex} is only a function.
     */
    static String evidenceDigest(String original) {
        return sha256Hex(original);
    }

    /**
     * A {@value #REDACTION_HEX_CHARS}-hex prefix, for naming a value in a log without printing it.
     *
     * <p>Truncated on purpose: it must not be usable as proof of anything. Never persist it,
     * never compare it with {@link #evidenceDigest}, never accept it as one.
     */
    static String redactionDigest(String value) {
        return sha256Hex(value).substring(0, REDACTION_HEX_CHARS);
    }
}
