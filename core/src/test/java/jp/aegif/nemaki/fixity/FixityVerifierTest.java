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
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Fixity: does what is stored still hash to what the capture recorded?
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md}. The golden digests are computed OUTSIDE this
 * codebase (python hashlib), like every other pinned digest here — a vector produced by the code
 * it pins proves nothing.
 */
class FixityVerifierTest {

    /** python: hashlib.sha256(b"hello world").hexdigest() */
    private static final String HELLO_SHA256 =
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

    private static Document documentWithDigest(String digest) {
        Document doc = new Document();
        doc.setId("doc-1");
        doc.setType("cmis:document");
        List<Aspect> aspects = new ArrayList<>();
        if (digest != null) {
            Aspect integration = new Aspect();
            integration.setName(FixityVerifier.INTEGRATION_ASPECT);
            integration.setProperties(new ArrayList<>(List.of(
                    new Property(FixityVerifier.CONTENT_HASH_PROPERTY, digest),
                    new Property("nemaki:sourceSystem", "slack"))));
            aspects.add(integration);
        }
        doc.setAspects(aspects);
        return doc;
    }

    private static InputStream bytesOf(String text) {
        return new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("stored bytes that hash to the recorded digest are a MATCH")
    void matchingBytesMatch() {
        FixityVerifier.Result result =
                FixityVerifier.verify(documentWithDigest(HELLO_SHA256), bytesOf("hello world"));

        assertEquals(FixityOutcome.MATCH, result.outcome());
        assertEquals(HELLO_SHA256, result.computedDigest(),
                "the computed digest disagrees with the external reference — the formula moved");
    }

    @Test
    @DisplayName("changed bytes are a MISMATCH — the whole point")
    void changedBytesMismatch() {
        FixityVerifier.Result result =
                FixityVerifier.verify(documentWithDigest(HELLO_SHA256), bytesOf("hello worlds"));

        assertEquals(FixityOutcome.MISMATCH, result.outcome(),
                "a single changed byte was reported as intact");
        assertNotNull(result.computedDigest(), "the operator needs both digests to act");
    }

    @Test
    @DisplayName("no recorded digest is NOT_RECORDED, not UNVERIFIABLE")
    void noRecordedDigestIsItsOwnAnswer() {
        // Folding this into UNVERIFIABLE would bury the real unverifiable — the object whose
        // bytes could not be read — under every pre-digest document in the repository. A value
        // that is permanently present is one operators learn to skip; the capture verifier
        // split ABSENT out of UNVERIFIABLE for exactly this reason.
        FixityVerifier.Result result =
                FixityVerifier.verify(documentWithDigest(null), bytesOf("hello world"));

        assertEquals(FixityOutcome.NOT_RECORDED, result.outcome());
        assertNull(result.recordedDigest());
    }

    @Test
    @DisplayName("bytes that cannot be READ are UNVERIFIABLE, not MISMATCH")
    void unreadableBytesAreUnverifiable() {
        // "We could not look" is not "we looked and it was wrong". Reporting a read failure as
        // a mismatch sends an operator hunting corruption that may not exist.
        FixityVerifier.Result nothingToRead =
                FixityVerifier.verify(documentWithDigest(HELLO_SHA256), (InputStream) null);
        assertEquals(FixityOutcome.UNVERIFIABLE, nothingToRead.outcome());

        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("storage went away mid-read");
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("storage went away mid-read");
            }
        };
        FixityVerifier.Result midReadFailure =
                FixityVerifier.verify(documentWithDigest(HELLO_SHA256), failing);

        assertEquals(FixityOutcome.UNVERIFIABLE, midReadFailure.outcome(),
                "a read that died half way through was reported as corrupted content");
        assertNotNull(midReadFailure.reason());
    }

    @Test
    @DisplayName("a recorded digest with an algorithm prefix still compares")
    void prefixedDigestsCompare() {
        // Recorded values have carried both shapes over time. Requiring one would report every
        // object written under the other shape as a mismatch — a repository-wide false alarm.
        FixityVerifier.Result result = FixityVerifier.verify(
                documentWithDigest("sha256:" + HELLO_SHA256), bytesOf("hello world"));

        assertEquals(FixityOutcome.MATCH, result.outcome());
    }

    @Test
    @DisplayName("a blank recorded digest counts as absent, not as a digest to fail against")
    void blankDigestIsAbsent() {
        // A blank stored value compared literally would make every such object a MISMATCH — the
        // repository would look corrupted because a property was empty.
        assertEquals(FixityOutcome.NOT_RECORDED,
                FixityVerifier.verify(documentWithDigest("   "), bytesOf("hello world"))
                        .outcome());
    }

    @Test
    @DisplayName("the subject says the bytes were RE-READ, not merely stored")
    void theSubjectNamesTheRereading() {
        // P1-1(d) R3 gave DigestSubject its members and deliberately left out STORED, because
        // nothing read back what the repository held. This is the first path that does — and
        // "stored" alone could still be read as "we stored it", which is the claim R3 refused.
        assertEquals("stored-reverified", FixityVerifier.SUBJECT_STORED_REVERIFIED);
        assertEquals("SHA-256", FixityVerifier.ALGORITHM,
                "the algorithm is recorded beside each result so a future change is visible in "
                        + "rows written before it");
    }
}
