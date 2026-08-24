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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a set of objects, re-reads their stored bytes and checks them against the recorded
 * digest.
 *
 * <h2>What a pass reports</h2>
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md}. Two things, always together: the counts, and
 * <b>how much was looked at</b> ({@link FixityScanReport.Verdict}). A clean count over a
 * truncated scan is the same mistake the ACL-epoch migration's {@code COMPLETE} made, and it is
 * the one an operator acts on.
 *
 * <p>The walk itself is supplied by the caller — a scheduled sweep, an admin request over one
 * folder, a drill over one object. This class owns the verdict arithmetic and the limit, not the
 * enumeration, so a caller cannot get the honesty rules wrong by choosing a different source.
 */
public class FixityScanService {

    private static final Logger logger = LoggerFactory.getLogger(FixityScanService.class);

    /** Beyond this, a single request would hold the server for an unbounded time. */
    public static final int MAX_LIMIT = 10_000;

    /** Findings listed per pass. Matches are counted, never listed. */
    public static final int MAX_FINDINGS = 500;

    private ContentService contentService;

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * Verifies each object the iterator yields, up to {@code limit}.
     *
     * @param objects the objects to check, in whatever order the caller enumerates them
     * @param limit   the most objects to check; the verdict says whether it stopped here
     */
    public FixityScanReport scan(String repositoryId, Iterable<Content> objects, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        long scanned = 0;
        long match = 0;
        long mismatch = 0;
        long unverifiable = 0;
        long notRecorded = 0;
        List<FixityScanReport.Finding> findings = new ArrayList<>();
        boolean stoppedAtLimit = false;

        try {
            for (Content content : objects) {
                if (scanned >= capped) {
                    // Stopped because we were told to, not because we finished. The verdict
                    // below must say so however clean the counts are.
                    stoppedAtLimit = true;
                    break;
                }
                scanned++;
                FixityVerifier.Result result = verifyOne(repositoryId, content);
                switch (result.outcome()) {
                    case MATCH -> match++;
                    case MISMATCH -> {
                        mismatch++;
                        addFinding(findings, content, result);
                    }
                    case UNVERIFIABLE -> {
                        unverifiable++;
                        addFinding(findings, content, result);
                    }
                    case NOT_RECORDED -> notRecorded++;
                }
            }
        } catch (Exception e) {
            // A pass that died half way through has counts that describe nothing in
            // particular. FAILED, not PARTIAL: partial means "we stopped on purpose".
            logger.warn("Fixity pass over {} failed: {}", repositoryId, e.toString());
            return FixityScanReport.failed(repositoryId,
                    "the pass failed and its counts establish nothing: " + e.getMessage());
        }

        FixityScanReport.Verdict verdict = stoppedAtLimit
                ? FixityScanReport.Verdict.PARTIAL
                : FixityScanReport.Verdict.COMPLETE;
        String note = stoppedAtLimit
                ? "stopped at the limit of " + capped + "; these counts describe a sample"
                : null;
        return new FixityScanReport(verdict, repositoryId, scanned, match, mismatch,
                unverifiable, notRecorded, List.copyOf(findings), note);
    }

    /** One object: read the stored bytes back and compare. */
    public FixityVerifier.Result verifyOne(String repositoryId, Content content) {
        if (content == null) {
            return FixityVerifier.Result.unverifiable(null, "the object could not be read");
        }
        String recorded = FixityVerifier.recordedDigest(content);
        if (recorded == null) {
            // Ask before fetching bytes: reading an attachment we have nothing to compare it
            // against is pure cost, and on a repository of pre-digest documents it would be
            // the whole pass.
            return FixityVerifier.Result.notRecorded();
        }
        if (!(content instanceof Document document)) {
            return FixityVerifier.Result.unverifiable(recorded,
                    "only documents carry content to verify");
        }
        String attachmentId = document.getAttachmentNodeId();
        if (attachmentId == null || attachmentId.isBlank()) {
            // A digest was recorded and there is no content to check it against. That is not
            // "nothing to verify" — the digest says there SHOULD be bytes.
            return FixityVerifier.Result.unverifiable(recorded,
                    "a content digest is recorded but the document has no attachment");
        }
        AttachmentNode attachment;
        try {
            attachment = contentService.getAttachment(repositoryId, attachmentId);
        } catch (Exception e) {
            return FixityVerifier.Result.unverifiable(recorded,
                    "the attachment could not be read: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
        if (attachment == null) {
            return FixityVerifier.Result.unverifiable(recorded,
                    "the attachment named by the document does not exist");
        }
        return FixityVerifier.verify(content, attachment.getInputStream());
    }

    private static void addFinding(List<FixityScanReport.Finding> findings, Content content,
            FixityVerifier.Result result) {
        if (findings.size() >= MAX_FINDINGS) {
            // Bounded so one bad repository cannot produce an unbounded response. The COUNTS
            // are not bounded, so the report still says how many there were.
            return;
        }
        findings.add(new FixityScanReport.Finding(content == null ? null : content.getId(),
                result.outcome(), result.recordedDigest(), result.computedDigest(),
                result.reason()));
    }
}
