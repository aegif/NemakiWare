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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Records what a version's stored bytes are NOW — {@code commitmentKind = OBSERVED}.
 *
 * <h2>What an observation is, and what it is not</h2>
 *
 * <p>E1 records content as it is written. Everything written before E1 existed has no statement,
 * and never will: <b>backfilling history as {@code CAPTURED} would manufacture evidence</b> —
 * it would say the repository committed to those bytes at capture time, which it did not.
 *
 * <p>What can honestly be said about old content is that it was there when someone looked. That
 * is this class, and it is deliberately weaker:
 *
 * <ul>
 * <li>it says <b>"these bytes were stored at this observation, and were anchored after it"</b>;
 * <li>it says <b>nothing</b> about the time between receipt and the observation;
 * <li>so a reader must never render an {@code OBSERVED} statement the way a {@code CAPTURED}
 *     one is rendered. The kind is part of the digest precisely so the two cannot be swapped.
 * </ul>
 *
 * <h2>No journal row</h2>
 *
 * <p>Nothing is being written, so there is no window between bytes and statement and nothing to
 * leave open. An observation that fails to record leaves exactly what was there before: a
 * version with no statement.
 */
@Component
public class RecordContentObserver {

    private static final Logger logger = LoggerFactory.getLogger(RecordContentObserver.class);

    private ContentService contentService;
    private RecordContentStateRecorder recorder;

    @Autowired(required = false)
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    @Autowired(required = false)
    public void setRecorder(RecordContentStateRecorder recorder) {
        this.recorder = recorder;
    }

    /** Why an observation produced no statement. Each is a different answer to an operator. */
    public enum Refusal {
        /** The observer is not wired on this node. */
        NOT_WIRED,
        /** No such document, or it could not be read. */
        NO_SUCH_DOCUMENT,
        /**
         * The document has no content.
         *
         * <p>Not an error: a document without a content stream is a legitimate object. What
         * would be wrong is a statement saying its content is the empty string, because the
         * SHA-256 of no input is a real-looking digest and nothing about that value says it
         * came from an absence.
         */
        NO_CONTENT,
        /** The stored bytes could not be read back. */
        UNREADABLE,
        /**
         * The bytes were read and the digest could not be vouched for.
         *
         * <p>Short read against the recorded length. Recording anyway would commit the chain to
         * a digest of part of a file.
         */
        NOT_DIGESTIBLE
    }

    /**
     * @param refusal null when a statement was produced
     * @param result null when {@code refusal} is set
     * @param statement the statement that was built, so a caller — and a lock — can see WHICH
     *        claim was made rather than inferring it from a digest. Comparing digests needed a
     *        reconstructed statement, and reconstructing one needs {@code recordedAt}, which is
     *        generated in here: the lock that tried it could never match and so protected
     *        nothing (control HV3 did not fire).
     */
    public record Observation(Refusal refusal, RecordContentStateRecorder.Result result,
                              String detail, RecordContentStatementV1 statement) {
        public boolean recorded() {
            return result != null && result.inChain();
        }
    }

    public Observation observe(String repositoryId, String objectId) {
        if (contentService == null || recorder == null) {
            return new Observation(Refusal.NOT_WIRED, null,
                    "the content service or the recorder is not wired on this node", null);
        }
        Document document;
        try {
            document = contentService.getDocument(repositoryId, objectId);
        } catch (RuntimeException e) {
            // "could not read" is not "does not exist", and the detail says which.
            return new Observation(Refusal.NO_SUCH_DOCUMENT, null,
                    "the document could not be read: " + e.getMessage(), null);
        }
        if (document == null) {
            return new Observation(Refusal.NO_SUCH_DOCUMENT, null, "there is no such document",
                    null);
        }
        if (document.getAttachmentNodeId() == null) {
            return new Observation(Refusal.NO_CONTENT, null,
                    "the document has no content stream, so there are no bytes to state", null);
        }

        AttachmentNode attachment;
        try {
            attachment = contentService.getAttachment(repositoryId, document.getAttachmentNodeId());
        } catch (RuntimeException e) {
            return new Observation(Refusal.UNREADABLE, null,
                    "the content could not be opened: " + e.getMessage(), null);
        }
        if (attachment == null || attachment.getInputStream() == null) {
            return new Observation(Refusal.UNREADABLE, null,
                    "the document names an attachment whose bytes could not be opened. This is "
                            + "NOT a finding that it has no content", null);
        }

        long declared = attachment.getLength();
        String digest;
        long observedLength;
        try (InputStream body = attachment.getInputStream()) {
            DigestingInputStream digesting = DigestingInputStream.over(body);
            if (digesting == null) {
                return new Observation(Refusal.NOT_DIGESTIBLE, null,
                        "SHA-256 is unavailable on this node", null);
            }
            // Drained through a fixed buffer, never held: a gigabyte attachment must not
            // allocate a gigabyte to produce a 32-byte digest.
            if (!digesting.drain()) {
                return new Observation(Refusal.UNREADABLE, null,
                        "the content stopped making progress before its end, so the digest "
                                + "would cover only part of it", null);
            }
            observedLength = digesting.bytesRead();
            // The declared length is checked when there IS one. A stored row with an unknown
            // length (-1) is common enough that refusing on it would make this endpoint useless;
            // what is recorded in that case is the length actually read.
            digest = digesting.digestIfTrustworthy(declared);
        } catch (IOException | RuntimeException e) {
            return new Observation(Refusal.UNREADABLE, null,
                    "the content could not be read to the end: " + e.getMessage(), null);
        }
        if (digest == null) {
            return new Observation(Refusal.NOT_DIGESTIBLE, null,
                    "the stored row declares " + declared + " bytes and " + observedLength
                            + " were read, so the digest would cover something other than what "
                            + "the row says is there", null);
        }

        RecordContentStatementV1 statement = new RecordContentStatementV1(
                repositoryId, document.getId(), document.getId(),
                document.getAttachmentNodeId(), digest, observedLength,
                RecordContentStatementV1.CommitmentKind.OBSERVED, null,
                java.time.Instant.now().toString());
        // No Pending: nothing was written, so there is no row to close.
        RecordContentStateRecorder.Result result = recorder.recordAndClose(null, statement);
        if (!result.inChain()) {
            logger.warn("E1: the observation of {} was not chained ({}).", objectId,
                    result.outcome());
        }
        return new Observation(null, result, null, statement);
    }
}
