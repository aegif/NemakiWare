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
package jp.aegif.nemaki.fixity;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Property;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Re-reads stored bytes and checks them against the digest the capture recorded.
 *
 * <h2>What this is for</h2>
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md}. P1-1(d) R3 established that a recorded
 * {@code nemaki:contentHash} is the digest of <b>the bytes the ingest fetched</b>
 * ({@code DigestSubject.INPUT}) — nothing had ever read back what the repository ended up
 * holding, which is why {@code DigestSubject} deliberately had no {@code STORED} member. This is
 * the first path that can put one there.
 *
 * <h2>What it does not establish</h2>
 *
 * <p><b>Not tamper-evidence.</b> The formula is public SHA-256 and the recorded digest is an
 * ordinary property; anything with direct database access can rewrite the bytes AND the digest
 * and keep them agreeing. Closing that needs an external anchor (P1-3 / P2), not this.
 *
 * <p><b>Not "everything was verified".</b> Objects with no recorded digest are
 * {@link FixityOutcome#NOT_RECORDED} — outside the check, not failures of it.
 */
public final class FixityVerifier {

    /** Where the capture records the digest of what it fetched. */
    public static final String INTEGRATION_ASPECT = "nemaki:externalIntegration";

    /** The property holding it. */
    public static final String CONTENT_HASH_PROPERTY = "nemaki:contentHash";

    /**
     * The subject value a fixity result carries.
     *
     * <p>{@code STORED_REVERIFIED}, not {@code STORED}: "stored" alone could be read as "we
     * stored it" — which is what the capture already (over)claimed. Having READ IT BACK is the
     * whole of what this value means (design §4).
     */
    public static final String SUBJECT_STORED_REVERIFIED = "stored-reverified";

    /** The digest algorithm, named in the result so a future change is visible in old rows. */
    public static final String ALGORITHM = "SHA-256";

    private FixityVerifier() {
    }

    /** One object's verdict, and the two digests behind it. */
    public record Result(FixityOutcome outcome, String recordedDigest, String computedDigest,
                         String reason) {

        public static Result notRecorded() {
            return new Result(FixityOutcome.NOT_RECORDED, null, null,
                    "no content digest was recorded for this object");
        }

        public static Result unverifiable(String recorded, String reason) {
            return new Result(FixityOutcome.UNVERIFIABLE, recorded, null, reason);
        }
    }

    /**
     * The digest the capture recorded for this object, or null when there is none.
     *
     * <p>Reads the RAW aspect, not the CMIS-compiled view: {@code nemaki:contentHash} has no
     * property declaration on the document type, so the compiled view never contains it and a
     * verifier reading that view would report every object as having no digest (the same trap
     * {@code EvidenceMetadataHash} documents).
     */
    public static String recordedDigest(Content content) {
        if (content == null || content.getAspects() == null) {
            return null;
        }
        for (Aspect aspect : content.getAspects()) {
            if (aspect == null || !INTEGRATION_ASPECT.equals(aspect.getName())
                    || aspect.getProperties() == null) {
                continue;
            }
            for (Property property : aspect.getProperties()) {
                if (property != null && CONTENT_HASH_PROPERTY.equals(property.getKey())
                        && property.getValue() != null) {
                    String value = String.valueOf(property.getValue()).trim();
                    // Blank counts as absent, the word every other evidence path uses.
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Verifies one object.
     *
     * @param content the object, with its RAW aspects
     * @param bytes   the stored bytes, or null when they could not be read. The stream is
     *                consumed and closed here.
     */
    public static Result verify(Content content, InputStream bytes) {
        String recorded = recordedDigest(content);
        if (recorded == null) {
            // Nothing to check against. Not a failure of this pass — a gap in what was
            // captured, which P1-1 owns.
            closeQuietly(bytes);
            return Result.notRecorded();
        }
        if (bytes == null) {
            return Result.unverifiable(recorded,
                    "the stored bytes could not be read, so nothing was compared");
        }
        String computed;
        try {
            computed = sha256(bytes);
        } catch (IOException | NoSuchAlgorithmException e) {
            // "We could not look" is not "we looked and it was wrong". Reporting a read failure
            // as a mismatch would send an operator hunting corruption that may not exist.
            return Result.unverifiable(recorded,
                    "the stored bytes could not be hashed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        } finally {
            closeQuietly(bytes);
        }
        // The recorded value may or may not carry an algorithm prefix depending on when it was
        // written; compare the hex payload either way rather than requiring one shape.
        boolean equal = hexOf(recorded).equalsIgnoreCase(hexOf(computed));
        return new Result(equal ? FixityOutcome.MATCH : FixityOutcome.MISMATCH,
                recorded, computed, null);
    }

    /** The hex payload of a digest string, with any {@code algo:} prefix removed. */
    static String hexOf(String digest) {
        if (digest == null) {
            return "";
        }
        int colon = digest.lastIndexOf(':');
        return colon >= 0 ? digest.substring(colon + 1).trim() : digest.trim();
    }

    private static String sha256(InputStream in) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(ALGORITHM);
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // Closing a stream we have finished with cannot change the verdict.
        }
    }

    /** Convenience for callers holding the bytes already. */
    public static Result verify(Content content, byte[] bytes) {
        return verify(content, bytes == null ? null : new java.io.ByteArrayInputStream(bytes));
    }

    /** The process types a fixity result is recorded under, for callers building events. */
    public static List<String> resultKeys() {
        return List.of("fixityOutcome", "fixityRecordedDigest", "fixityComputedDigest",
                "fixitySubject", "fixityAlgorithm");
    }
}
