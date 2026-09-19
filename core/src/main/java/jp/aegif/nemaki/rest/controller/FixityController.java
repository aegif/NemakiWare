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
package jp.aegif.nemaki.rest.controller;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.FixityLedgerRecorder;
import jp.aegif.nemaki.fixity.FixityScanReport;
import jp.aegif.nemaki.fixity.FixityScanService;
import jp.aegif.nemaki.fixity.FixityVerifier;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixity: check that stored bytes still hash to what the capture recorded (P1-2).
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md}. Every response carries the verdict FIRST and
 * the limits of what a result establishes, because a fixity report is the kind of artefact that
 * gets forwarded and quoted — and "MISMATCH" reads as "tampered with" unless the response says
 * otherwise, which it cannot honestly claim.
 */
@RestController
@RequestMapping("/v1/admin/fixity")
public class FixityController {

    private static final Logger logger = LoggerFactory.getLogger(FixityController.class);

    @Autowired(required = false)
    private FixityScanService fixityScanService;

    @Autowired(required = false)
    private ContentService contentService;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /** One object, now. The shape a drill and a "is this one still intact?" question want. */
    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyOne(
            @RequestParam String repositoryId,
            @RequestParam String objectId) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // Before the branch, not on each arm. The class javadoc promises "every response carries
        // the verdict FIRST and the limits of what a result establishes", and the guards that
        // return early — 403, 503, the two refusals below, the catch — carried no limits at all.
        // Repeating the line on each arm is what left them out in the first place.
        body.put("limits", LIMITS);
        if (fixityScanService == null || contentService == null) {
            body.put("status", "error");
            body.put("message", "the fixity service is not wired on this node");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                // The sibling endpoint got this a round ago and this one did not. verifyOne
                // answers UNVERIFIABLE for a null content, which the wrapper then shipped as
                // HTTP 200 / status:"success" / outcome:"UNVERIFIABLE" — so a mistyped id came
                // back looking like a checked object whose bytes could not be confirmed, rather
                // than like an id that names nothing. Those are different things to be told.
                body.put("status", "error");
                body.put("objectId", objectId);
                body.put("message", "no object with that id was found in " + repositoryId
                        + ". This is NOT a finding about any object's bytes.");
                body.put("limits", "Nothing was verified: the id did not resolve, so no digest "
                        + "was recorded, recomputed or compared.");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }
            FixityVerifier.Result result = fixityScanService.verifyOne(repositoryId, content);
            body.put("status", "success");
            body.put("outcome", result.outcome().name());
            body.put("objectId", objectId);
            body.put("recordedDigest", result.recordedDigest());
            body.put("computedDigest", result.computedDigest());
            body.put("reason", result.reason());
            body.put("algorithm", FixityVerifier.ALGORITHM);
            body.put("subject", FixityVerifier.SUBJECT_STORED_REVERIFIED);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // A read failure is not a verdict about the bytes. Saying so beats returning an
            // outcome the caller would read as one.
            logger.warn("Fixity verify failed for {}/{}: {}", repositoryId, objectId,
                    e.getMessage());
            body.put("status", "error");
            body.put("message", "the object could not be checked: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * A folder's immediate children.
     *
     * <p>Scoped rather than repository-wide on purpose: a whole-repository pass re-reads every
     * attachment, which is the operation the roadmap holds until the volume has been measured.
     * A folder is what an operator can decide the cost of.
     */
    @PostMapping("/scan/folder")
    public ResponseEntity<Map<String, Object>> scanFolder(
            @RequestParam String repositoryId,
            @RequestParam String folderId,
            @RequestParam(defaultValue = "200") int limit) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // Before the branch, as in verifyOne above. The 403, the 503, the two refusals below and
        // the catch arm all returned without it; the class javadoc promises otherwise.
        body.put("limits", LIMITS);
        if (fixityScanService == null || contentService == null) {
            body.put("status", "error");
            body.put("message", "the fixity service is not wired on this node");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            // IMMEDIATE CHILDREN ONLY. FixityScanService sets COMPLETE when the iterable it was
            // handed runs out -- "the pass reached the end of its scope" -- and the scope is
            // whatever this caller enumerated. So a folder with sub-folders answered
            // verdict=COMPLETE for a scan that never looked inside them: the COMPLETE trap this
            // repository has hit before (an index verdict that means "everything present is
            // stamped", and a folder reindex that skips the folder itself).
            //
            // Recursing is a bigger change than a review can validate -- an unbounded walk on a
            // deep tree, under a `limit` that would then mean something different. So the scope
            // is NAMED on the response instead, beside the verdict, and the verdict is not
            // allowed to travel alone.
            // The folder has to be THERE before its emptiness means anything. getChildren
            // answers [] for a folder that does not exist — a typo, a document id, an object
            // deleted since — exactly as it answers for a folder with nothing in it, and the
            // report built from [] is verdict=COMPLETE, scanned=0, mismatch=0. That verdict then
            // goes into the append-only chain a dozen lines below, where it cannot be corrected:
            // a permanent record that a folder nobody ever looked at came back clean.
            //
            // This is the same substitution the scope fix above was made for, one level earlier:
            // there the scope of a real pass was overstated, here there is no pass at all.
            Content folder = contentService.getContent(repositoryId, folderId);
            if (folder == null) {
                body.put("status", "error");
                body.put("folderId", folderId);
                body.put("message", "no object with this id is readable in this repository, so "
                        + "nothing was scanned. This is NOT a finding that the folder is intact "
                        + "or that it is empty — nothing was looked at.");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }
            if (!Boolean.TRUE.equals(folder.isFolder())) {
                body.put("status", "error");
                body.put("folderId", folderId);
                body.put("message", "this id names a " + folder.getType() + ", not a folder. A "
                        + "scan of its children would be a scan of nothing, and would report "
                        + "COMPLETE for it.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
            }
            List<Content> children = new ArrayList<>();
            List<Content> found = contentService.getChildren(repositoryId, folderId);
            if (found != null) {
                children.addAll(found);
            }
            // A child the store returned and could not decode is a child that EXISTS and is not
            // in this list. The scan's verdict is about the list it was handed, and COMPLETE
            // then means "the end of a list that was short" — which goes into the append-only
            // chain as a pass over this folder. The absent-folder guard above stops the empty
            // case; this is the same substitution when the folder IS there and part of it could
            // not be read.
            int unreadableChildren = contentService.lastUnreadableChildCount();
            FixityScanReport report =
                    fixityScanService.scan(repositoryId, children, limit);
            Map<String, Object> out = new LinkedHashMap<>(report.asMap());
            // The outer status FOLLOWS the verdict. FixityScanService deliberately does not
            // throw -- "A pass that died half way through has counts that describe nothing in
            // particular. FAILED, not PARTIAL" -- so a failed pass came back 200 success with
            // its zeros. This method's OWN error arm already encodes the rule: it builds
            // FixityScanReport.failed(...) and pairs it with status:"error" and a 500. The rule
            // was applied to the failure this method produces and not to the failure the
            // service hands back, which is the second producer in one method that AnchorController
            // was corrected for a few hours earlier.
            // The verdict must not read as a clean pass over the folder, and it must not say so
            // only in the response: recordPass commits the verdict AND the scope together, so a
            // scope that does not mention the gap makes the chain entry the overclaim. FAILED
            // is too strong for an unreadable child — the documents that WERE read were really
            // checked — so the counts stand and the scope names what they cover.
            //
            // "partial" only DOWNGRADES a success. Written as two statements it upgraded a
            // FAILED pass instead: a pass that died AND lost children came back saying
            // "partial", which reads as "mostly fine", beside an HTTP 500 computed from the
            // verdict. One assignment, so the two facts cannot disagree.
            boolean failed = report.verdict() == FixityScanReport.Verdict.FAILED;
            if (unreadableChildren > 0) {
                out.put("unreadableChildren", unreadableChildren);
            }
            out.put("status", failed ? "error" : unreadableChildren > 0 ? "partial" : "success");
            out.put("folderId", folderId);
            // The UNCOUNTED arm is gone with the -1 it read. Nothing returns a negative now:
            // the count is taken when a listing is read and travels with it, including out of
            // the tree cache. Leaving the branch would be a lock on retracted behaviour — dead
            // code that tells the next reader the value can still arrive.
            out.put("scope", unreadableChildren > 0
                    ? "IMMEDIATE_CHILDREN_ONLY_PARTIAL" : "IMMEDIATE_CHILDREN_ONLY");
            out.put("scopeLimits", (unreadableChildren > 0
                            ? unreadableChildren + " child object(s) of this folder could not be "
                                    + "read and were NOT scanned — they are missing from every "
                                    + "count below, and this is NOT a finding that they are "
                                    + "intact. "
                            : "")
                    + "This pass looked at the DIRECT children of " + folderId
                    + " and did not descend into sub-folders. A verdict of COMPLETE means the "
                    + "pass reached the end of THAT list — it does not mean every document "
                    + "under this folder was checked. Scan sub-folders separately. The list "
                    + "itself is what the repository handed over: rows it could not decode are "
                    + "counted above, but a listing served from a cache that another node has "
                    + "since made stale is NOT detected here, so this is a statement about the "
                    + "list, not about the folder as the database holds it now.");
            // The pass goes into the evidence chain (P1-3 §2). Fail-open, like capture and
            // unlike disposition: the scan has already run and its results are already in this
            // response, so refusing would throw away a completed pass to protect a record of
            // it. A gap is reported instead — never hidden, because a chain read as a complete
            // history of what was checked is worse than no chain.
            if (fixityLedgerRecorder != null) {
                // "folder:" said the pass covered the folder. It covered the folder's DIRECT
                // CHILDREN, and passDigest commits the verdict and the scope TOGETHER -- so the
                // append-only, never-purged chain paired verdict=COMPLETE with a scope reading
                // "this folder", permanently. That is the overclaim this endpoint's response was
                // just corrected for, standing in the one place it cannot be corrected later.
                //
                // The response fix and the chain fix are two arms of one obligation, and the
                // first version of this change reached only the response -- the same mechanic
                // the audit that found the original defect had named.
                //
                // And the SCOPE carries the gap. passDigest commits the verdict and the scope
                // together, in an entry that is never purged: with children the store could not
                // read, "folder-children:{id}" claims a pass over children that were never
                // looked at. The response was corrected for that a few lines up; the chain is
                // the arm that cannot be corrected afterwards, and the first version of this
                // change reached only the response — the same pairing this endpoint was already
                // fixed for once.
                //
                // Third arm, found one round after the second. "partial" is a reserved word:
                // FixityScanService says so where it chooses FAILED over it — "partial means
                // 'we stopped on purpose'". A pass that DIED and had unreadable children was
                // getting "folder-children-partial:{id}:unread=3" written into the chain, which
                // says two false things at once: that the reduction was deliberate, and that
                // the shortfall was exactly three. It was three PLUS however far the pass did
                // not get, which nobody knows. And a chain reader sees only this string — the
                // verdict is inside passDigest, a one-way hash.
                //
                // So the count keeps its exact form only where it IS exact, and a failed pass
                // says it is failed and that the count is a floor.
                boolean scanFailed = report.verdict() == FixityScanReport.Verdict.FAILED;
                String chainScope;
                if (scanFailed) {
                    chainScope = unreadableChildren > 0
                            ? "folder-children-incomplete:" + folderId + ":unreadAtLeast="
                                    + unreadableChildren
                            : "folder-children-incomplete:" + folderId;
                } else if (unreadableChildren > 0) {
                    chainScope = "folder-children-partial:" + folderId + ":unread="
                            + unreadableChildren;
                } else {
                    chainScope = "folder-children:" + folderId;
                }
                FixityLedgerRecorder.Recorded recorded = fixityLedgerRecorder.recordPass(
                        repositoryId, chainScope, report, java.time.Instant.now().toString());
                out.put("chained", recorded.inChain());
                if (recorded.warning() != null) {
                    out.put("chainWarning", recorded.warning());
                }
            }
            // And the HTTP code with it. 200 for a FAILED pass is the one an operator's
            // tooling reads without opening the body.
            return report.verdict() == FixityScanReport.Verdict.FAILED
                    ? ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(out)
                    : ResponseEntity.ok(out);
        } catch (Exception e) {
            logger.warn("Fixity folder scan failed for {}/{}: {}", repositoryId, folderId,
                    e.getMessage());
            // FAILED, not an empty clean report: a pass that could not enumerate has counts
            // that establish nothing, including its zeros.
            Map<String, Object> out = new LinkedHashMap<>(
                    FixityScanReport.failed(repositoryId,
                            "the folder could not be enumerated: " + e.getMessage()).asMap());
            out.put("status", "error");
            out.put("limits", LIMITS);
            // What it failed ON. The success arm names the folder and its scope; the failure arm
            // said only that something went wrong, so a caller scanning several folders could not
            // tell which one it had lost.
            out.put("folderId", folderId);
            out.put("scope", "IMMEDIATE_CHILDREN_ONLY");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(out);
        }
    }

    private FixityLedgerRecorder fixityLedgerRecorder;

    /**
     * Optional: a deployment without the evidence ledger still runs fixity passes; it simply
     * does not chain them, and {@code chained} is then absent from the response rather than
     * present and false. "We did not try" and "we tried and failed" must not read alike.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFixityLedgerRecorder(FixityLedgerRecorder fixityLedgerRecorder) {
        this.fixityLedgerRecorder = fixityLedgerRecorder;
    }

    static final String LIMITS =
            "A mismatch means the stored bytes are not what this repository recorded — not that "
            + "they were tampered with: the digest is an ordinary stored property, so anything "
            + "with direct database access can change both and keep them agreeing. NOT_RECORDED "
            + "means there was no digest to check against, which is a gap in what was captured, "
            + "not a failure of this check.";

    private ResponseEntity<Map<String, Object>> requireAdmin() {
        boolean admin = false;
        if (httpRequest != null) {
            Object ctx = httpRequest.getAttribute("CallContext");
            admin = ctx instanceof CallContext callContext
                    && Boolean.TRUE.equals(callContext.get(CallContextKey.IS_ADMIN));
        }
        if (admin) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", "Admin access required");
        // The refusal carries them too. Both callers set them before their own branch, and this
        // helper returns from INSIDE that prologue — so the one exit that bypassed the promise
        // was the shared one.
        body.put("limits", LIMITS);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
