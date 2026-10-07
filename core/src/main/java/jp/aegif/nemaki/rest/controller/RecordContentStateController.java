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

import jakarta.servlet.http.HttpServletRequest;

import jp.aegif.nemaki.evidence.ContentWriteJournal;
import jp.aegif.nemaki.evidence.RecordContentObserver;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
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
 * E1's two admin verbs: observe what is stored now, and list the writes that were never recorded.
 *
 * <p>Both are read-shaped from an operator's point of view, and both are deliberately honest
 * about what they cannot answer — see {@code docs/design/adr-e1-durable-commitment.md}.
 */
@RestController
@RequestMapping("/v1/admin/record-content")
public class RecordContentStateController {

    /** Travels with every answer, because the endpoint's name is stronger than what it does. */
    static final String LIMITS =
            "An OBSERVED statement says these bytes were stored when this observation ran, and "
            + "were anchored after it. It does NOT say they are the bytes that were received, "
            + "nor that they were unchanged in between. Unresolved rows are the writes this "
            + "node could record as unresolved; a write made while the journal itself was "
            + "unreachable leaves no row at all.";

    @Autowired(required = false)
    private RecordContentObserver observer;

    private ContentWriteJournal journal;

    @Autowired(required = false)
    public void setJournal(ContentWriteJournal journal) {
        this.journal = journal;
    }

    @Autowired(required = false)
    private RecordContentStateRecorder recorder;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /**
     * Records what one document's stored bytes are now.
     *
     * <p>One document per call, on purpose. A sweep over a repository would produce thousands of
     * OBSERVED statements whose only shared property is when the sweep ran, and an operator who
     * asked for it would have no way to tell which of them failed.
     */
    @PostMapping("/observe-current-state")
    public ResponseEntity<Map<String, Object>> observe(@RequestParam String repositoryId,
            @RequestParam String objectId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", LIMITS);
        if (observer == null) {
            body.put("status", "unavailable");
            body.put("message", "the content observer is not wired on this node");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        RecordContentObserver.Observation observation = observer.observe(repositoryId, objectId);
        if (observation.refusal() != null) {
            body.put("status", "refused");
            body.put("reason", observation.refusal().name());
            body.put("message", observation.detail());
            // 404 only for the one refusal that IS an absence. The others are conditions of the
            // stored content, and answering 404 for them would tell a caller the document is not
            // there when it is.
            HttpStatus status =
                    observation.refusal() == RecordContentObserver.Refusal.NO_SUCH_DOCUMENT
                            ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(body);
        }
        body.put("status", observation.recorded() ? "recorded" : "not-chained");
        body.put("outcome", observation.result().outcome().name());
        body.put("commitmentKind", "OBSERVED");
        body.put("statementDigest", observation.result().statementDigest());
        body.put("sequence", observation.result().sequence());
        // 200 for both, and the body says which. A not-chained observation is not a client
        // error and not a server failure; it is a recorded fact about what could not be
        // recorded, and a status code cannot carry that.
        return ResponseEntity.ok(body);
    }

    /**
     * Writes whose statement never reached the chain.
     *
     * <p>An empty list from an unreachable journal is NOT "there are no gaps", and this endpoint
     * refuses to let a caller read it that way: when the journal cannot be asked it answers 503
     * rather than 200 with an empty array.
     */
    @GetMapping("/unresolved-writes")
    public ResponseEntity<Map<String, Object>> unresolved(
            @RequestParam(defaultValue = "100") int limit) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", LIMITS);
        if (journal == null || !journal.isActive()) {
            body.put("status", "unavailable");
            body.put("message", "the content-write journal could not be asked, so this is NOT a "
                    + "finding that there are no unresolved writes");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        List<ContentWriteJournal.Unresolved> rows = journal.unresolved(limit);
        int unreadable = journal.unreadableCount();
        if (unreadable > 0) {
            // Partially answered. The rows that WERE read are still worth returning, and the
            // count says the answer is incomplete — the same shape the ingest side settled on.
            body.put("status", "partial");
            body.put("unreadableRows", unreadable);
            body.put("message", unreadable + " row(s) could not be read, so this list is not "
                    + "the whole set of unresolved writes");
        } else {
            body.put("status", "success");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentWriteJournal.Unresolved row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intentId", row.intentId());
            item.put("repositoryId", row.repositoryId());
            item.put("objectId", row.objectId());
            item.put("versionObjectId", row.versionObjectId());
            item.put("writeKind", row.kind().name());
            item.put("openedAt", row.openedAt());
            out.add(item);
        }
        body.put("unresolved", out);
        body.put("count", out.size());
        if (recorder != null) {
            // Counted in memory, and labelled as such: these are the gaps NOTHING durable knows
            // about, so a zero here says only that this process has not seen one since it
            // started.
            body.put("journalOutagesSinceStartup", recorder.journalOutagesSinceStartup());
            body.put("unrecordedWritesSinceStartup", recorder.unrecordedWritesSinceStartup());
        }
        return ResponseEntity.ok(body);
    }

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
        body.put("limits", LIMITS);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
