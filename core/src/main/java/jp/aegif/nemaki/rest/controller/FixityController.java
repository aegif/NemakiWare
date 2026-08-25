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
        if (fixityScanService == null || contentService == null) {
            body.put("status", "error");
            body.put("message", "the fixity service is not wired on this node");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            FixityVerifier.Result result = fixityScanService.verifyOne(repositoryId, content);
            body.put("status", "success");
            body.put("outcome", result.outcome().name());
            body.put("objectId", objectId);
            body.put("recordedDigest", result.recordedDigest());
            body.put("computedDigest", result.computedDigest());
            body.put("reason", result.reason());
            body.put("algorithm", FixityVerifier.ALGORITHM);
            body.put("subject", FixityVerifier.SUBJECT_STORED_REVERIFIED);
            body.put("limits", LIMITS);
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
        if (fixityScanService == null || contentService == null) {
            body.put("status", "error");
            body.put("message", "the fixity service is not wired on this node");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            List<Content> children = new ArrayList<>();
            List<Content> found = contentService.getChildren(repositoryId, folderId);
            if (found != null) {
                children.addAll(found);
            }
            FixityScanReport report =
                    fixityScanService.scan(repositoryId, children, limit);
            Map<String, Object> out = new LinkedHashMap<>(report.asMap());
            out.put("status", "success");
            out.put("folderId", folderId);
            // The pass goes into the evidence chain (P1-3 §2). Fail-open, like capture and
            // unlike disposition: the scan has already run and its results are already in this
            // response, so refusing would throw away a completed pass to protect a record of
            // it. A gap is reported instead — never hidden, because a chain read as a complete
            // history of what was checked is worse than no chain.
            if (fixityLedgerRecorder != null) {
                FixityLedgerRecorder.Recorded recorded = fixityLedgerRecorder.recordPass(
                        repositoryId, "folder:" + folderId, report,
                        java.time.Instant.now().toString());
                out.put("chained", recorded.inChain());
                if (recorded.warning() != null) {
                    out.put("chainWarning", recorded.warning());
                }
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            logger.warn("Fixity folder scan failed for {}/{}: {}", repositoryId, folderId,
                    e.getMessage());
            // FAILED, not an empty clean report: a pass that could not enumerate has counts
            // that establish nothing, including its zeros.
            Map<String, Object> out = new LinkedHashMap<>(
                    FixityScanReport.failed(repositoryId,
                            "the folder could not be enumerated: " + e.getMessage()).asMap());
            out.put("status", "error");
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
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
