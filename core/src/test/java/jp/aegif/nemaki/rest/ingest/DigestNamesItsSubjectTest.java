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

import jp.aegif.nemaki.rest.ingest.IngestLineageEmitter.CapturedContent;
import jp.aegif.nemaki.rest.ingest.IngestLineageEmitter.CapturedContent.DigestSubject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recorded digest must say what it is a digest OF (P1-1(d) R3).
 *
 * <h2>Why</h2>
 *
 * <p>{@code computeContentHash} runs on the bytes the connector fetched, and the very same array
 * is then handed to {@code createDocument}. Nothing reads back what the repository ended up
 * holding. So {@code contentHash} beside {@code contentStored=true} is a true statement about the
 * input that reads as a statement about storage — and reading a digest as stronger than it is is
 * the substitution this work item exists to prevent. Proving the stored bytes is fixity, P1-2,
 * with its own cost budget.
 *
 * <h2>The stronger case that used to be discarded</h2>
 *
 * <p>On the unchanged-content branch, this import fetched the bytes, hashed them, and found the
 * digest equal to the one already recorded — then reported "neither supplied nor verified".
 * {@code input-matched-recorded} is that fact. It is still not fixity: the comparison is against
 * a mutable aspect property, not against stored bytes.
 */
class DigestNamesItsSubjectTest {

    @Test
    @DisplayName("no subject can mean the stored bytes — the enum has no such member")
    void thereIsNoStoredSubject() {
        // The load-bearing assertion. Everything else here is about emitting the subject
        // correctly; this is about not being ABLE to claim the strong one. A member added here
        // without a path that re-reads and re-hashes would let the claim back in silently.
        for (DigestSubject subject : DigestSubject.values()) {
            assertFalse(subject.wireValue().toLowerCase(Locale.ROOT).equals("stored"),
                    "a digest subject meaning 'the bytes the repository holds' was added, but "
                            + "nothing in the ingest path reads those bytes back. That claim needs "
                            + "fixity (P1-2), not an enum constant.");
        }
        assertEquals(2, DigestSubject.values().length,
                "the subjects are exactly input and input-matched-recorded: "
                        + Arrays.toString(DigestSubject.values()));
    }

    @Test
    @DisplayName("a digest never goes out without a subject")
    void everyDigestCarriesASubject() {
        // hashed() is the ordinary capture. A null subject would emit a bare digest, which is
        // exactly the shape R3 forbids, so the accessor defaults rather than returning null.
        assertEquals("input",
                IngestLineageEmitter.digestSubjectValue(CapturedContent.hashed("a".repeat(64))));
        assertEquals("input-matched-recorded", IngestLineageEmitter.digestSubjectValue(
                CapturedContent.none().withMatchedInputDigest("a".repeat(64), "matched")));
        assertEquals("input", IngestLineageEmitter.digestSubjectValue(
                new CapturedContent(CapturedContent.ContentState.STORED, "a".repeat(64), null, null)),
                "a CapturedContent built without a subject emitted no subject at all");
    }

    @Test
    @DisplayName("the comparison that matched a recorded digest is not thrown away")
    void aMatchedComparisonIsRemembered() {
        // It was. compareContent nulls the digest on purpose — the stored one is a mutable aspect
        // property and may be stale — but the fact that THIS pass fetched and hashed the bytes
        // and found them equal went with it, and the event then said the pass had neither
        // supplied nor verified anything (external review, P1-1(d) D2).
        CanonicalImportServiceImpl.ContentComparison matched =
                CanonicalImportServiceImpl.compareContent("a".repeat(64), "a".repeat(64));
        assertFalse(matched.contentChanged());
        assertTrue(matched.matchedRecordedHash(),
                "the digests matched and the comparison did not remember it");

        CanonicalImportServiceImpl.ContentComparison changed =
                CanonicalImportServiceImpl.compareContent("a".repeat(64), "b".repeat(64));
        assertFalse(changed.matchedRecordedHash(), "a differing digest was reported as matching");

        CanonicalImportServiceImpl.ContentComparison noContent =
                CanonicalImportServiceImpl.compareContent(null, "a".repeat(64));
        assertFalse(noContent.matchedRecordedHash(),
                "a metadata-only pass, which fetched nothing, was reported as having matched");
    }

    @Test
    @DisplayName("the unchanged-content branch stops claiming it verified nothing")
    void theUnchangedBranchSaysWhatItActuallyDid() {
        CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
        CapturedContent described = service.describeCapturedContent("bedroom", "obj-1", null,
                CanonicalImportServiceImpl.compareContent("a".repeat(64), "a".repeat(64)));

        // The read-back still happens and still decides the state — the matched digest is added
        // to whatever it found, not substituted for it. With no ContentService wired the read
        // fails, so UNKNOWN is what the read-back legitimately reports here.
        assertEquals(CapturedContent.ContentState.UNKNOWN, described.state(),
                "the content state is genuinely undetermined here — nothing read the bytes back");
        assertEquals("a".repeat(64), described.digest(),
                "the digest this pass computed and matched was thrown away again");
        assertEquals("input-matched-recorded",
                IngestLineageEmitter.digestSubjectValue(described));
        assertTrue(described.reason().contains("digest equalled the one already recorded"),
                "the reason does not say what this pass actually did: " + described.reason());
    }

    @Test
    @DisplayName("the subject rides in both encodings, not just one")
    void bothEncodingsCarryIt() {
        // The v1 snapshot and the v2 endpoint attributes are two encodings of one table. A fact
        // added to one and forgotten in the other is a silent loss at the write flip, which is
        // what CaptureEvidenceField exists to prevent — so assert the pair, not either half.
        assertEquals("contentHashSubject", CaptureEvidenceField.CONTENT_HASH_SUBJECT.v1Key());
        assertTrue(CaptureEvidenceField.CONTENT_HASH_SUBJECT.v2Home().carriedByV2(),
                "the digest's subject has no v2 home, so it disappears at the write flip");
        assertEquals("contentHashSubject",
                CaptureEvidenceField.CONTENT_HASH_SUBJECT.v2Home().attributeName());
    }
}
