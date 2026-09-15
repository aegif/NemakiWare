package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
// FetchResult is now a top-level record in the same package
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST API for the external ingest scheduler.
 */
@RestController
@RequestMapping("/v1/admin/ingest-scheduler")
public class IngestSchedulerController {

    @Autowired
    private IngestSchedulerService schedulerService;

    /**
     * Optional: only to read its undelivered-webhook counts for {@code GET /idle/status}.
     * Without it that section is absent rather than empty, which is the honest answer for a
     * node that does not run the receiver.
     */
    @Autowired(required = false)
    private IngestWebhookController webhookController;

    @Autowired
    private HttpServletRequest httpRequest;

    /** Only for the index-free existence check behind a 404-versus-503 split. */
    @Autowired(required = false)
    private ConnectorDefinitionService connectorDefinitionService;

    /**
     * Returns the list of profiles with schedulerEnabled=true and their
     * resolved default connectors.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        if (!isAdmin()) return forbidden();

        ImportProfileDefinitionService.OwnedProfiles walked =
                schedulerService.scheduledProfilesWithUnreadable();
        List<ImportProfileDefinition> scheduled = walked.profiles();
        List<Map<String, Object>> entries = scheduled.stream().map(profile -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("profileId", profile.getProfileId());
            entry.put("repositoryId", profile.getRepositoryId());
            entry.put("displayName", profile.getDisplayName());
            IngestSchedulerService.ConnectorForProfile resolution =
                    schedulerService.resolveConnectorFor(profile);
            ConnectorDefinition connector = resolution.connector();
            entry.put("connectorId", connector != null ? connector.getConnectorId() : null);
            entry.put("connectorSystem", connector != null ? connector.getSourceSystem() : null);
            entry.put("ready", connector != null);
            if (connector == null) {
                // "ready: false" alone reads as "the connector is missing or disabled" — a
                // statement three of the five reasons do not support. The reason travels with
                // it, and says explicitly when this node could not ask.
                entry.put("notReadyReason", String.valueOf(resolution.why()));
                entry.put("notReadyIsAnAnswer", resolution.answered());
            }
            return entry;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scheduledProfiles", entries);
        response.put("count", entries.size());
        if (!walked.uninterpretable().isEmpty()) {
            // The count is of what this walk COULD read. Saying so costs a field; leaving it
            // out made a short list look like the whole schedule.
            response.put("profilesUnreadable", walked.uninterpretable().stream()
                    .map(row -> row.profileId() == null ? "(no profileId)" : row.profileId())
                    .toList());
        }
        // The scheduled list IS established by this point. Letting the idle listing's refusal
        // take the whole answer down discards it — over-throwing, which this batch counts as
        // a defect of the same weight. The part that could not be answered says so in its own
        // field instead. A review found the endpoint turned 503 as a whole.
        try {
            response.put("idleProfiles", schedulerService.getIdleProfiles());
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException couldNotAsk) {
            response.put("idleProfilesUnavailable", couldNotAsk.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Start the periodic polling scheduler.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startScheduler() {
        if (!isAdmin()) return forbidden();
        schedulerService.startPolling();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "Ingest scheduler started");
        return ResponseEntity.ok(response);
    }

    /**
     * Stop the periodic polling scheduler.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopScheduler() {
        if (!isAdmin()) return forbidden();
        schedulerService.stopPolling();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "Ingest scheduler stopped");
        return ResponseEntity.ok(response);
    }

    /**
     * Manually trigger ingest for a specific scheduled profile.
     * Currently supports MESSAGE_CONTEXT (IMAP) connectors only.
     */
    @PostMapping("/trigger/{profileId}")
    public ResponseEntity<Map<String, Object>> triggerIngest(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();

        Map<String, Object> response = new LinkedHashMap<>();
        ImportProfileDefinitionService.OwnedProfiles walked =
                schedulerService.scheduledProfilesWithUnreadable();
        ImportProfileDefinition profile = walked.profiles().stream()
                .filter(p -> profileId.equals(p.getProfileId()))
                .findFirst().orElse(null);
        if (profile == null) {
            // The walk reports the rows it could not read. Answering "not found or not
            // scheduler-enabled" for one of THOSE is two statements, neither established —
            // and the listing behind this endpoint had been dropping them since it was
            // written for the poll, which has no caller to answer. A review found it.
            if (walked.uninterpretable().stream()
                    .anyMatch(row -> profileId.equals(row.profileId()))) {
                response.put("status", "error");
                response.put("message", "import profile " + profileId + " has a row this node"
                        + " could not read, so whether it is scheduled cannot be established;"
                        + " retry shortly");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            response.put("status", "error");
            response.put("message", "Profile not found or not scheduler-enabled: " + profileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        IngestSchedulerService.ConnectorForProfile resolution =
                schedulerService.resolveConnectorFor(profile);
        ConnectorDefinition connector = resolution.connector();
        if (connector == null) {
            // Unconditional: every arm of unresolvedConnector returns, and the earlier
            // `if (refusal != null)` left a path where a future null would reach executeFetch
            // with a null connector. The folder twin never had the hole.
            return unresolvedConnector(profile, resolution, response);
        }

        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Dispatch to appropriate adapter via unified method, using persisted scope params
        Map<String, String> params = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams() : Map.of();
        FetchResult fetchResult =
                schedulerService.executeFetch(callContext, profile, connector, params);

        response.put("status", fetchResult.hasErrors() ? "partial" : "success");
        response.put("fetched", fetchResult.fetched());
        response.put("imported", fetchResult.imported());
        if (fetchResult.hasErrors()) {
            response.put("errors", fetchResult.errors());
        }
        response.put("profileId", profileId);
        response.put("connectorId", connector.getConnectorId());
        response.put("sourceSystem", connector.getSourceSystem());
        response.put("archetype", connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : null);
        return ResponseEntity.ok(response);
    }

    // ── IMAP IDLE endpoints ─────────────────────────────────────────

    @PostMapping("/idle/start/{profileId}")
    public ResponseEntity<Map<String, Object>> startIdle(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        String error = schedulerService.startIdle(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        if (error != null) {
            response.put("status", "error");
            response.put("message", error);
            return ResponseEntity.status(statusOfIdleRefusal(error, profileId)).body(response);
        }
        response.put("status", "success");
        response.put("message", "IMAP IDLE started for " + profileId);
        return ResponseEntity.ok(response);
    }

    /**
     * The status for one refusal message. Every refusal here used to be a 400, including the
     * ones this batch added for a read that did not answer — a body ending in "retry shortly"
     * inside a status that says the request itself is wrong. The other entry points (the
     * webhook receiver, the ingest endpoints, the definition APIs) already split these; a
     * review found this one left behind, and the next one found the split still too coarse:
     * a standing twin pair is not a malformed request (409 everywhere else), and a profile the
     * walk established is ABSENT is not one either (404). Matched on the refusal texts the
     * monitor and the scheduler service build; a message that says nothing about any of these
     * keeps its 400.
     */
    private static HttpStatus statusOfIdleRefusal(String error, String profileId) {
        String raw = error == null ? "" : error;
        // The caller's own profileId is interpolated into every message here, so the caller
        // can write markers into the text this method reads. Anchoring the arms at the start
        // was the first answer and it was not enough: a profile named
        // " no longer has a row in repository " turned an UNWIRED node's message into the
        // 404 arm — a could-not-ask answered as "it is not there", the batch's own defect,
        // newly introduced. A review found it one round later.
        //
        // So the id is taken OUT of the text before the arms that mean "the store answered"
        // (404 / 409 / 403) are tested, and the arm that means "could not ask" (503) is
        // tested against BOTH forms. A hostile id can then only ever buy itself a 503, never
        // a settled answer, and can never take a 503 away.
        String message = profileId == null || profileId.isBlank() ? raw
                : raw.replace(profileId, "{id}");
        //
        // Absence the index-free read ESTABLISHED — not a retry, and not a wrong request.
        if (message.startsWith("Profile not found")
                || message.startsWith("import profile ") && message.contains(
                        " no longer has a row in repository ")) {
            return HttpStatus.NOT_FOUND;
        }
        // A session that is already there is a standing conflict: no retry and no correction
        // of the request changes it. A review found it on the 400 arm while every other
        // conflict on this endpoint had moved.
        if (message.startsWith("IDLE already running")) {
            return HttpStatus.CONFLICT;
        }
        // An authorisation outcome, answered as a malformed request until a review named it.
        if (message.startsWith("Delegated authorization denied")) {
            return HttpStatus.FORBIDDEN;
        }
        // A stored row the mapper REFUSED, or one whose document names a different id. It was
        // matching "could not be read" below and answering 503 "retry" for something no retry
        // repairs — an administrator has to fix the row. A review found the standing case
        // wearing the transient answer, and the round after found the exclusion written for
        // "as a connector" missing "as THAT connector", which is the deterministic-id
        // mismatch. Matched on the common prefix so a fourth phrasing cannot slip past.
        if (message.contains("could not be read as ")) {
            return HttpStatus.CONFLICT;
        }
        if (couldNotAsk(message) || couldNotAsk(raw)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        // The twin-pair wording of both services, 409 here exactly as on the definition APIs.
        if (message.contains("more than one definition row")
                || message.contains("more than one owned definition row")) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }

    /**
     * The answer for a profile whose connector did not resolve — 400 only when the store
     * said so.
     *
     * <p>"No compatible connector found" was the answer for all five reasons, three of which
     * are not statements about the connector. The one that needs a second read is
     * {@code ABSENT_OR_HIDDEN}: one index-free walk tells "there is no such connector" (404)
     * from "the index cannot show it" (503). That walk is affordable here because this is one
     * profile per request; the listing that feeds the dashboard does not do it.
     */
    private ResponseEntity<Map<String, Object>> unresolvedConnector(
            ImportProfileDefinition profile,
            IngestSchedulerService.ConnectorForProfile resolution,
            Map<String, Object> response) {
        response.put("status", "error");
        switch (resolution.why()) {
            case NOT_WIRED -> {
                response.put("message", "the connector service is not wired on this node;"
                        + " retry shortly against a node that runs it");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            case LISTING_REFUSED -> {
                // Without this arm the default below answered 400 "no connector" for a
                // listing that never ran (R29).
                response.put("message", "the connectors of profile " + profile.getProfileId()
                        + " could not be listed while the index is not ready; retry shortly");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            case NOT_READ -> {
                response.put("message", "connector " + profile.getDefaultConnectorId()
                        + " exists but could not be read as that connector");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            case ABSENT_OR_HIDDEN -> {
                String id = profile.getDefaultConnectorId();
                if (connectorDefinitionService == null) {
                    // Without the walk there is no way to tell absence from a hidden row, and
                    // the arm below would answer "does not exist" — a claim no read made. A
                    // review found the short-circuit fabricating absence for an unwired node.
                    response.put("message", "the connector service is not wired on"
                            + " this node, so whether connector " + id + " exists cannot be"
                            + " established; retry shortly against a node that runs it");
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(response);
                }
                boolean rowIsThere;
                try {
                    rowIsThere = connectorDefinitionService.existsIndexFree(id);
                } catch (RuntimeException couldNotAsk) {
                    response.put("message", "whether connector " + id + " exists could not be"
                            + " established; retry shortly: " + couldNotAsk.getMessage());
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                }
                if (rowIsThere) {
                    response.put("message", "connector " + id + " exists but the index cannot"
                            + " show it; retry shortly");
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                }
                response.put("message", "connector " + id + " does not exist");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            default -> {
                response.put("message", "No compatible connector found for profile: "
                        + profile.getProfileId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
        }
    }

    /** The vocabulary that means "this node could not ask", wherever it appears. */
    private static boolean couldNotAsk(String message) {
        // A row the mapper REFUSED is a corrupt stored row: standing, not transient. It was
        // matching "could not be read" and answering 503 "retry" for something no retry
        // repairs. Matched on the common prefix of all three phrasings ("as a profile", "as a
        // connector", "as THAT connector") — the first version listed two of them and a review
        // found the third slipping past.
        if (message.contains("could not be read as ")) {
            return false;
        }
        return message.contains("retry shortly")
                || message.contains("could not be established")
                || message.contains("could not be read")
                || message.contains("could not be looked up");
    }

    @PostMapping("/idle/stop/{profileId}")
    public ResponseEntity<Map<String, Object>> stopIdle(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        String error = schedulerService.stopIdle(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        if (error != null) {
            response.put("status", "error");
            response.put("message", error);
            // Same classifier as the start: an unwired node is not a bad request here either.
            return ResponseEntity.status(statusOfIdleRefusal(error, profileId)).body(response);
        }
        response.put("status", "success");
        response.put("message", "IMAP IDLE stopped for " + profileId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/idle/status")
    public ResponseEntity<Map<String, Object>> getIdleStatus() {
        if (!isAdmin()) return forbidden();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("idleProfiles", schedulerService.getIdleProfiles());
        // A session that is running is not the same as a session that has captured
        // everything. When the authorisation cannot be re-checked AND the miss cannot be
        // dead-lettered, the message is gone and only this count says so — the decision to
        // keep the session up instead of stopping it rests on the count being readable.
        // Webhook deliveries the node accepted, did not fetch, and could not record. The IMAP
        // twin was surfaced a round earlier and the webhook counter was added with a javadoc
        // saying it appears here — two reviewers found it had no reader at all.
        if (webhookController != null) {
            Map<String, Integer> undelivered = webhookController.undeliveredWebhookCounts();
            if (!undelivered.isEmpty()) {
                response.put("undeliveredWebhooks", undelivered);
                response.put("undeliveredWebhookNote", "webhook deliveries these profiles"
                        + " accepted but could neither fetch nor record. In memory and lost on"
                        + " restart; re-fetch through the connector");
            }
        }
        Map<String, Integer> missed = schedulerService.idleUndurableMisses();
        if (!missed.isEmpty()) {
            response.put("undurableMisses", missed);
            response.put("undurableMissNote", "messages these profiles did not capture and"
                    + " could not record. The count is in memory and is lost on restart; the"
                    + " UID checkpoint has not moved, so re-fetch the mailbox to recover");
        }
        return ResponseEntity.ok(response);
    }

    // ── Checkpoint management ───────────────────────────────────────

    @GetMapping("/checkpoint/{profileId}")
    public ResponseEntity<Map<String, Object>> getCheckpoints(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        CheckpointManager.Enumeration enumerated =
                schedulerService.enumerateCheckpoints(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profileId", profileId);
        response.put("checkpoints", enumerated.checkpoints());
        if (!enumerated.profileRowRead()) {
            // The scoped keys are rebuilt from the profile's schedulerParams, so without that
            // row this list is the static scopes alone — and an empty list of scoped
            // checkpoints looks exactly the same. Saying which one this is costs a field.
            response.put("warning", "the profile's definition row was not read, so any scoped"
                    + " checkpoints (slack/teams/mattermost/chatwork/m365mail/box) are not in"
                    + " this list");
        }
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/checkpoint/{profileId}")
    public ResponseEntity<Map<String, Object>> resetCheckpoints(
            @PathVariable String profileId,
            @RequestParam(required = false) String scope) {
        if (!isAdmin()) return forbidden();
        CheckpointManager.ResetSummary summary =
                schedulerService.resetCheckpoint(profileId, scope);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        // BLANK, not just null: the manager treats a blank scope as "reset everything" and
        // this branch used to treat it as "one named scope", so `?scope=` cleared every
        // static checkpoint while the answer named a single one — and the incomplete-reset
        // warning below could not be reached at all. A review found the two predicates
        // disagreeing on the same input.
        if (scope != null && !scope.isBlank()) {
            response.put("message", "Checkpoint reset for " + profileId + "/" + scope);
        } else if (summary.profileRowRead()) {
            response.put("message", "All checkpoints reset for " + profileId
                    + " (" + summary.keysReset() + " keys)");
        } else {
            // "All" was answered for a pass that could only name the static scopes, because
            // the profile's row — the only source of the scoped keys — was not read. A review
            // found the incomplete reset reported as a complete one. Not a 503: the static
            // keys ARE reset, and an answer that reads as "nothing happened" would be worse.
            response.put("message", "Reset " + summary.keysReset() + " checkpoint keys for "
                    + profileId);
            response.put("warning", "the profile's definition row was not read, so any scoped"
                    + " checkpoints (slack/teams/mattermost/chatwork/m365mail/box) could not be"
                    + " named and still hold their position");
        }
        return ResponseEntity.ok(response);
    }

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    private boolean isAdmin() {
        if (httpRequest == null) return false;
        CallContext ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("message", "Admin access required");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(r);
    }

    /**
     * The typed "this row could not be read" refusals reach these endpoints and Spring answers
     * 500 — "our bug" for a condition whose whole point is that a retry fixes it. The
     * definition APIs have had this floor since the batch began; a review found the scheduler,
     * ingest, DLQ and webhook controllers without it.
     *
     * <p>Where they come from, as of this revision. The PROFILE refusal reaches here from
     * {@code scheduledProfilesWithUnreadable} — its unwired arm, and the walk it delegates
     * to ({@code listScheduledIndexFreeWithUnreadable}) — which both {@code GET /status} and
     * {@code POST /trigger/{id}} read, and from {@code getIdleProfiles} on
     * {@code GET /idle/status}. NOT from {@code getScheduledProfiles}: since the endpoints
     * moved off it, its only caller is the poll, which catches the refusal itself. The
     * CONNECTOR refusal does not arrive through the per-profile resolution either —
     * {@code resolveConnectorFor} catches it and answers {@code NOT_READ} — so what remains
     * is the archetype fallback's {@code listByArchetype}.
     *
     * <p>AND the checkpoint enumeration, as of the round that gave it a refusing settings
     * read. Two earlier versions of this note said the opposite — "every arm of that read
     * answers null rather than throwing" — and that stopped being true in the commit that
     * converted {@code CheckpointManager}; the refusal then escaped {@code GET}/{@code DELETE
     * .../checkpoint/{id}} as a Spring 500, because {@code GlobalExceptionHandler} is scoped
     * to {@code rest.controller} and does not cover this package. A review found it.
     *
     * <p>This paragraph has now been wrong FIVE times: three because the code moved under it,
     * once because the ledger recorded a correction that was never made, and once because a
     * throw was ADDED to a path this note had just finished excluding. If you move or add a
     * throw, edit this in the same commit.
     */
    @ExceptionHandler({ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
            ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
            jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                    .SettingUnreadableException.class})
    public ResponseEntity<Map<String, Object>> definitionRowsCouldNotBeRead(RuntimeException e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(r);
    }
}
