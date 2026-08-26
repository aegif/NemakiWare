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

import jp.aegif.nemaki.custody.CustodyReceipt;
import jp.aegif.nemaki.custody.CustodyState;
import jp.aegif.nemaki.custody.CustodyTransfer;
import jp.aegif.nemaki.custody.CustodyTransferService;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The custody-transfer protocol, reachable (P3-4).
 *
 * <h2>Custody has its own endpoint on purpose</h2>
 *
 * <p>{@code /advance} cannot reach {@code CUSTODY_TRANSFERRED}; {@code /pass-custody} is the
 * only route to it, and it records the handover before moving. Two endpoints rather than one
 * with a state parameter, because the difference is not a parameter: every other move is a note
 * about what the far end said, and this one is where this repository stops being answerable for
 * the record.
 *
 * <p>Admin only. A handover decides who holds a record; it is not an ordinary user operation.
 */
@RestController
@RequestMapping("/v1/admin/custody")
public class CustodyTransferController {

    /** What these endpoints do NOT establish, in the response rather than in a manual. */
    static final String CUSTODY_LIMITS =
            "These endpoints record what THIS repository knows about a handover: what it "
                    + "packaged, what the receiving system reported, and whether that report "
                    + "was about the package we sent. They do NOT send anything, do not check "
                    + "the receiving system's claims, and do not establish that the far end "
                    + "still holds the record. A transfer reaching CUSTODY_TRANSFERRED means "
                    + "this repository recorded a handover it verified a receipt for — not "
                    + "that the record is safe somewhere else.";

    @Autowired(required = false)
    private CustodyTransferService service;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    @PostMapping("/{repositoryId}/transfers")
    public ResponseEntity<Map<String, Object>> open(@PathVariable String repositoryId,
            @RequestParam String transferId, @RequestParam String objectId,
            @RequestParam String sipDigest,
            @RequestParam(required = false, defaultValue = "") String receivingSystem) {
        ResponseEntity<Map<String, Object>> refused = precondition();
        if (refused != null) {
            return refused;
        }
        return respond(service.open(repositoryId, transferId, objectId, sipDigest,
                receivingSystem), HttpStatus.CREATED);
    }

    @PostMapping("/{repositoryId}/transfers/{transferId}/advance")
    public ResponseEntity<Map<String, Object>> advance(@PathVariable String repositoryId,
            @PathVariable String transferId, @RequestParam String to,
            @RequestParam(required = false, defaultValue = "") String reason) {
        ResponseEntity<Map<String, Object>> refused = precondition();
        if (refused != null) {
            return refused;
        }
        CustodyState next;
        try {
            next = CustodyState.valueOf(to);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, to + " is not a state of this machine. The "
                    + "states are " + java.util.Arrays.toString(CustodyState.values()) + ".");
        }
        return respond(service.advance(repositoryId, transferId, next, reason), HttpStatus.OK);
    }

    @PostMapping("/{repositoryId}/transfers/{transferId}/receipt")
    public ResponseEntity<Map<String, Object>> receipt(@PathVariable String repositoryId,
            @PathVariable String transferId, @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> refused = precondition();
        if (refused != null) {
            return refused;
        }
        CustodyReceipt candidate;
        try {
            candidate = new CustodyReceipt(str(body.get("submissionId")), str(body.get("aipId")),
                    str(body.get("aipChecksum")), str(body.get("sipDigest")),
                    str(body.get("verificationOutcome")), str(body.get("receivingAgent")),
                    str(body.get("receivedAt")), str(body.get("signature")),
                    // NEVER taken from the request. A caller that could set this could tell
                    // this repository a signature had been verified by asserting it.
                    false);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return respond(service.verifyReceipt(repositoryId, transferId, candidate), HttpStatus.OK);
    }

    /** The one route to CUSTODY_TRANSFERRED. Records the handover, then moves. */
    @PostMapping("/{repositoryId}/transfers/{transferId}/pass-custody")
    public ResponseEntity<Map<String, Object>> passCustody(@PathVariable String repositoryId,
            @PathVariable String transferId) {
        ResponseEntity<Map<String, Object>> refused = precondition();
        if (refused != null) {
            return refused;
        }
        return respond(service.passCustody(repositoryId, transferId), HttpStatus.OK);
    }

    @GetMapping("/{repositoryId}/transfers/{transferId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String repositoryId,
            @PathVariable String transferId) {
        ResponseEntity<Map<String, Object>> refused = precondition();
        if (refused != null) {
            return refused;
        }
        CustodyTransferService.Found found = service.find(repositoryId, transferId);
        if (found.transfer() == null) {
            // 409, not 404, when a row exists and could not be read: "there is no transfer" is
            // the reassuring answer and the wrong one, and a 404 is exactly how a reader
            // reaches it. Decided on the KIND, not on the wording — matching the sentence
            // breaks when the sentence is improved, and it breaks towards the reassuring side.
            return error(found.absence() == CustodyTransferService.Found.Absence.UNREADABLE
                            ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    found.absent());
        }
        CustodyTransfer transfer = found.transfer();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", CUSTODY_LIMITS);
        body.put("status", "success");
        body.put("transfer", describe(transfer));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{repositoryId}/objects/{objectId}/transfers")
    public ResponseEntity<Map<String, Object>> byObject(@PathVariable String repositoryId,
            @PathVariable String objectId,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        ResponseEntity<Map<String, Object>> refused = precondition();
        if (refused != null) {
            return refused;
        }
        CustodyTransferService.Listed listed =
                service.findByObject(repositoryId, objectId, limit);
        List<Map<String, Object>> transfers = new ArrayList<>();
        for (CustodyTransfer transfer : listed.transfers()) {
            transfers.add(describe(transfer));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", CUSTODY_LIMITS);
        // Completeness BEFORE the list. A reader who meets the rows first has already drawn
        // the conclusion by the time they reach the caveat, and the conclusion an incomplete
        // list invites is "this record was never sent anywhere".
        body.put("complete", listed.complete());
        if (!listed.complete()) {
            body.put("incomplete", listed.incomplete());
        }
        body.put("status", "success");
        body.put("transfers", transfers);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> describe(CustodyTransfer transfer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", transfer.transferId());
        body.put("objectId", transfer.objectId());
        body.put("sipDigest", transfer.sipDigest());
        body.put("receivingSystem", transfer.receivingSystem());
        body.put("state", transfer.state().name());
        // What the state MEANS, beside the state. A name like RECEIPT_VERIFIED is read as
        // stronger than it is by anyone who has not read the enum.
        body.put("stateMeans", transfer.state().limits());
        body.put("custodyHasPassed", transfer.state().custodyHasPassed());
        List<Map<String, Object>> history = new ArrayList<>();
        for (CustodyTransfer.Step step : transfer.history()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("from", step.from() == null ? null : step.from().name());
            one.put("to", step.to().name());
            one.put("at", step.at());
            one.put("reason", step.reason());
            history.add(one);
        }
        body.put("history", history);
        body.put("receipt", transfer.receipt() == null ? null : transfer.receipt().asMap());
        return body;
    }

    private ResponseEntity<Map<String, Object>> respond(CustodyTransferService.Outcome outcome,
            HttpStatus onSuccess) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", CUSTODY_LIMITS);
        if (!outcome.done()) {
            // A refusal is the designed outcome for "the rule said no", so it is a 409 with the
            // reason rather than a 500 with a stack trace.
            body.put("status", "refused");
            body.put("message", outcome.refusedReason());
            if (outcome.transfer() != null) {
                body.put("transfer", describe(outcome.transfer()));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        body.put("status", "success");
        body.put("transfer", describe(outcome.transfer()));
        return ResponseEntity.status(onSuccess).body(body);
    }

    private ResponseEntity<Map<String, Object>> precondition() {
        Object context = httpRequest == null ? null : httpRequest.getAttribute("CallContext");
        boolean admin = context instanceof CallContext callContext
                && Boolean.TRUE.equals(callContext.get(CallContextKey.IS_ADMIN));
        if (!admin) {
            return error(HttpStatus.FORBIDDEN, "Admin access required");
        }
        if (service == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "the custody transfer service is not "
                    + "wired on this node");
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        body.put("limits", CUSTODY_LIMITS);
        return ResponseEntity.status(status).body(body);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
