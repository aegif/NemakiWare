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
package jp.aegif.nemaki.api.v1.resource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Kind;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.OnUnexpected;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Operation;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Outcome;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Row;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.RowVerdict;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Verdict;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatchEngine;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatchPlan;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatchRequestException;
import jp.aegif.nemaki.audit.AuditEmitSupport;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.audit.AuditOperation;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.util.PasswordPolicyService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;
import tools.jackson.databind.ObjectMapper;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk correction of users, groups and memberships (design {@code docs/design/principal-batch.md}).
 *
 * <pre>
 * POST /core/api/v1/cmis/repositories/{repo}/principals/batch/preview   (multipart or JSON)
 * POST /core/api/v1/cmis/repositories/{repo}/principals/batch/execute   (multipart or JSON)
 * </pre>
 *
 * <p>Preview decides and writes nothing. Execute writes through the canonical methods only, either
 * a plan the preview handed back ({@code planId}) or the file itself ({@code onUnexpected} =
 * {@code abort} by default: one row the world does not match and nothing is written). Every
 * response is built here — a failure inside the batch is a 500 with a fixed text and an
 * {@code incidentId}, never the exception's own words (the #1410 rule; {@code ApiExceptionMapper}
 * would copy them).
 *
 * <p>Admin only. CSRF is the stack's {@code ApiCsrfFilter}. A password is read from the file,
 * handed to {@code buildAndCreateUser} / {@code hashPassword} and to nothing else: not the
 * response, not the plan, not the audit line, not the log.
 */
@Component
@Path("/repositories/{repositoryId}/principals/batch")
@Produces(MediaType.APPLICATION_JSON)
public class PrincipalBatchResource {

    private static final Logger logger = LoggerFactory.getLogger(PrincipalBatchResource.class);

    static final String ADMIN_USER_ID = "admin";
    static final String MCP_SERVICE_USER_ID = "mcp-service";

    @Autowired
    private ContentService contentService;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Autowired(required = false)
    private AuditLogger auditLogger;

    @Context
    private HttpServletRequest httpRequest;

    private final PrincipalBatchPlan.Store plans = new PrincipalBatchPlan.Store();
    private final ObjectMapper json = ObjectMapperFactory.createNemakiObjectMapper();

    // ---- preview ----

    @POST
    @Path("/preview")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response previewMultipart(@PathParam("repositoryId") String repositoryId,
            @FormDataParam("file") InputStream file, @FormDataParam("kind") String kind,
            @FormDataParam("operation") String operation) {
        return guarded(repositoryId, "preview", actor -> {
            Input input = Input.fromCsv(readBounded(file), kind, operation, null, null);
            return preview(repositoryId, input);
        });
    }

    @POST
    @Path("/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response previewJson(@PathParam("repositoryId") String repositoryId, String body) {
        return guarded(repositoryId, "preview", actor -> preview(repositoryId, Input.fromJson(json, body)));
    }

    // ---- execute ----

    @POST
    @Path("/execute")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response executeMultipart(@PathParam("repositoryId") String repositoryId,
            @FormDataParam("file") InputStream file, @FormDataParam("kind") String kind,
            @FormDataParam("operation") String operation,
            @FormDataParam("onUnexpected") String onUnexpected,
            @FormDataParam("planId") String planId) {
        return guarded(repositoryId, "execute", actor -> {
            byte[] bytes = file == null ? null : readBounded(file);
            if (bytes != null && bytes.length == 0) {
                bytes = null;
            }
            Input input = bytes == null ? Input.withoutRows(kind, operation, onUnexpected, planId)
                    : Input.fromCsv(bytes, kind, operation, onUnexpected, planId);
            return execute(repositoryId, actor, input);
        });
    }

    @POST
    @Path("/execute")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response executeJson(@PathParam("repositoryId") String repositoryId, String body) {
        return guarded(repositoryId, "execute", actor -> execute(repositoryId, actor, Input.fromJson(json, body)));
    }

    // ---- the two flows ----

    private Response preview(String repositoryId, Input input) {
        if (input.rows == null) {
            throw new PrincipalBatchRequestException(400, "preview needs the rows (a CSV file or a JSON rows array)");
        }
        PrincipalBatchEngine engine = engine(repositoryId);
        PrincipalBatchEngine.Planned planned = engine.plan(input.kind, input.operation, input.rows);
        PrincipalBatchPlan plan = plans.put(repositoryId, input.kind, input.operation, input.rows,
                planned.verdicts(), planned.snapshotHash(), planned.passwordPresent(),
                planned.passwordPresent() ? PrincipalBatchEngine.sha256(input.rawBytes) : null, Instant.now());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", plan.planId());
        body.put("snapshotHash", plan.snapshotHash());
        body.put("expiresAt", plan.expiresAt().toString());
        body.put("kind", input.kind.name().toLowerCase(Locale.ROOT));
        body.put("operation", input.operation.name().toLowerCase(Locale.ROOT));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("rows", input.rows.size());
        counts.put("expected", planned.count(Verdict.EXPECTED));
        counts.put("unexpected", planned.count(Verdict.UNEXPECTED));
        counts.put("forbidden", planned.count(Verdict.FORBIDDEN));
        counts.put("adminGrants", planned.adminGrants());
        body.put("counts", counts);
        body.put("rows", planned.verdicts().stream().map(RowVerdict::asMap).toList());
        body.put("passwordPresent", planned.passwordPresent());
        return Response.ok(body).build();
    }

    private Response execute(String repositoryId, String actor, Input input) {
        Instant now = Instant.now();
        String jobId = UUID.randomUUID().toString();
        PrincipalBatchEngine engine = engine(repositoryId);
        String mode;
        Kind kind;
        Operation operation;
        List<Row> rows;
        List<RowVerdict> verdicts;
        if (input.planId != null) {
            // The confirmed path: the plan decides, the world must still be what it was.
            PrincipalBatchPlan plan = plans.peek(input.planId, now).orElseThrow(() ->
                    new PrincipalBatchRequestException(409,
                            plans.expiredRecently(input.planId) ? "PLAN_EXPIRED" : "PLAN_UNKNOWN",
                            plans.expiredRecently(input.planId)
                                    ? "the plan expired; preview the file again"
                                    : "no such plan on this node; preview the file again (plans are "
                                            + "not shared between replicas)"));
            if (!repositoryId.equals(plan.repositoryId())) {
                // A plan is decided against ONE repository's users and groups; confirming it under
                // another path would apply bedroom's verdicts to canopy. The plan stays for the
                // repository it belongs to.
                throw new PrincipalBatchRequestException(409, "PLAN_UNKNOWN",
                        "no such plan for this repository (it was previewed against another one); "
                                + "preview the file again here");
            }
            if (input.kind != null && input.kind != plan.kind()
                    || input.operation != null && input.operation != plan.operation()) {
                throw new PrincipalBatchRequestException(400, "the plan is for " + plan.kind() + " / "
                        + plan.operation() + ", not what this request says");
            }
            kind = plan.kind();
            operation = plan.operation();
            if (plan.passwordPresent()) {
                // The plan holds no password; the same file carries it (design §6.1).
                if (input.rawBytes == null) {
                    throw new PrincipalBatchRequestException(400, "this plan has rows with a password; "
                            + "send the same file again with the planId (the plan does not keep passwords)");
                }
                if (!PrincipalBatchEngine.sha256(input.rawBytes).equals(plan.fileDigest())) {
                    throw new PrincipalBatchRequestException(409, "FILE_DIGEST_CHANGED",
                            "the file is not the one that was previewed; nothing was written");
                }
                rows = input.rows;
            } else {
                if (input.rawBytes != null) {
                    throw new PrincipalBatchRequestException(400, "send either a planId or the rows, not both");
                }
                rows = plan.rows();
            }
            // The plan is confirmed only if deciding the same rows NOW reproduces it: the targets'
            // revisions (snapshotHash) AND every row's verdict. The snapshot alone misses what the
            // verdicts depend on but the targets do not carry — a member or a group a row names
            // that has since vanished, a nested cycle that has since closed, and the actor: a plan
            // previewed by one admin and confirmed by the user it deletes must come out FORBIDDEN
            // here, not be applied from the other admin's verdicts (c39, both reviewers).
            PrincipalBatchEngine.Planned again = engine.plan(kind, operation, rows);
            if (!again.snapshotHash().equals(plan.snapshotHash()) || !again.verdicts().equals(plan.verdicts())) {
                // The world moved on; the plan is stale and is not kept for a retry.
                plans.remove(plan.planId());
                throw new PrincipalBatchRequestException(409, "SNAPSHOT_CHANGED",
                        "the users or groups this plan was decided against, or what the preview "
                                + "decided about its rows, have changed since the preview; nothing "
                                + "was written. Preview the file again");
            }
            verdicts = again.verdicts();
            if (!plans.remove(plan.planId())) {
                // Applied once: another confirmation of the same plan got past the snapshot check
                // at the same moment and took it. This one writes nothing.
                throw new PrincipalBatchRequestException(409, "PLAN_UNKNOWN",
                        "the plan was just applied or discarded by another request; nothing was "
                                + "written. Preview the file again");
            }
            mode = "plan";
        } else {
            if (input.rows == null) {
                throw new PrincipalBatchRequestException(400, "execute needs a planId or the rows");
            }
            kind = input.kind;
            operation = input.operation;
            rows = input.rows;
            PrincipalBatchEngine.Planned planned = engine.plan(kind, operation, rows);
            verdicts = planned.verdicts();
            long notExpected = planned.verdicts().size() - planned.count(Verdict.EXPECTED);
            if (input.onUnexpected == OnUnexpected.ABORT && notExpected > 0) {
                Map<String, Object> refused = new LinkedHashMap<>();
                refused.put("jobId", jobId);
                refused.put("status", "refused");
                refused.put("reason", "UNEXPECTED_ROWS");
                refused.put("message", notExpected + " row(s) are not as the file assumes and onUnexpected "
                        + "is abort, so nothing was written. Preview the file, or pass onUnexpected=skip "
                        + "to apply the expected rows only");
                Map<String, Object> counts = new LinkedHashMap<>();
                counts.put("applied", 0);
                counts.put("unexpected", planned.count(Verdict.UNEXPECTED));
                counts.put("forbidden", planned.count(Verdict.FORBIDDEN));
                refused.put("counts", counts);
                refused.put("rows", planned.verdicts().stream().map(RowVerdict::asMap).toList());
                audit(repositoryId, actor, jobId, kind, operation, "immediate-abort", false, counts);
                return Response.status(Response.Status.CONFLICT).entity(refused).build();
            }
            mode = input.onUnexpected == OnUnexpected.SKIP ? "immediate-skip" : "immediate";
        }

        PrincipalBatchEngine.Applied applied = engine.apply(kind, operation, rows, verdicts, actor);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("applied", applied.count(Outcome.APPLIED));
        counts.put("skipped", applied.count(Outcome.SKIPPED));
        counts.put("forbidden", applied.count(Outcome.FORBIDDEN));
        counts.put("failed", applied.count(Outcome.FAILED));
        counts.put("notApplied", applied.count(Outcome.NOT_APPLIED));
        body.put("counts", counts);
        body.put("rows", applied.outcomes().stream().map(PrincipalBatchEngine.RowOutcome::asMap).toList());
        if (applied.stopped()) {
            body.put("status", "partial");
            body.put("stoppedAt", applied.stoppedAtLine());
            if (applied.incidentId() != null) {
                body.put("incidentId", applied.incidentId());
                body.put("message", "applying stopped at line " + applied.stoppedAtLine()
                        + "; the rows after it were not applied and nothing was rolled back. The "
                        + "failure's details were logged under incidentId. Preview the same file "
                        + "again: the rows already applied show as unexpected");
            } else {
                body.put("message", "applying stopped at line " + applied.stoppedAtLine()
                        + ": the target was no longer as the plan found it. The rows after it were "
                        + "not applied and nothing was rolled back");
            }
            audit(repositoryId, actor, jobId, kind, operation, mode, false, counts);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
        }
        body.put("status", "applied");
        audit(repositoryId, actor, jobId, kind, operation, mode, true, counts);
        return Response.ok(body).build();
    }

    // ---- plumbing ----

    private interface Flow {
        Response run(String actor);
    }

    /** Admin check, then the flow, with every failure turned into a body this resource wrote. */
    private Response guarded(String repositoryId, String what, Flow flow) {
        String actor = requireAdmin();
        try {
            return flow.run(actor);
        } catch (PrincipalBatchRequestException refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", refused.status() == 409 ? "refused" : "error");
            if (refused.reason() != null) {
                body.put("reason", refused.reason());
            }
            body.put("message", refused.getMessage());
            if (refused.getCause() != null) {
                // A store that could not answer: its text is logged, not returned.
                String incidentId = UUID.randomUUID().toString();
                logger.warn("Principal batch {} on {} could not read the store [incident {}]: {}", what,
                        repositoryId, incidentId, refused.getCause().getMessage(), refused.getCause());
                body.put("incidentId", incidentId);
            }
            return Response.status(refused.status()).entity(body).build();
        } catch (ApiException api) {
            throw api;
        } catch (RuntimeException unexpected) {
            // Never let this reach ApiExceptionMapper, which copies getMessage() into the body.
            String incidentId = UUID.randomUUID().toString();
            logger.error("Principal batch {} on {} failed [incident {}]: {}", what, repositoryId, incidentId,
                    unexpected.getMessage(), unexpected);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "the batch failed; the details were logged under incidentId");
            body.put("incidentId", incidentId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
        }
    }

    private String requireAdmin() {
        CallContext callContext = httpRequest == null ? null
                : (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required for principal batch operations");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Only administrators can run principal batch operations");
        }
        return callContext.getUsername();
    }

    private PrincipalBatchEngine engine(String repositoryId) {
        String solrUserId = read(PropertyKey.SOLR_NEMAKI_USERID, "solr");
        String groupPrefix = read(PropertyKey.DIRECTORY_SYNC_GROUP_PREFIX, DirectorySyncConfig.DEFAULT_GROUP_PREFIX);
        String userPrefix = propertyManager == null ? DirectorySyncConfig.DEFAULT_USER_PREFIX
                : String.valueOf(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX) == null
                        ? DirectorySyncConfig.DEFAULT_USER_PREFIX
                        : propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX));
        Set<String> groupPrefixes = new LinkedHashSet<>(List.of(groupPrefix, "cloud-google:", "cloud-microsoft:"));
        String actor = httpRequest == null ? null : actorOf((CallContext) httpRequest.getAttribute("CallContext"));
        return new PrincipalBatchEngine(contentService, passwordPolicyService, repositoryId,
                new PrincipalBatchEngine.Guards(ADMIN_USER_ID, solrUserId, MCP_SERVICE_USER_ID, actor,
                        groupPrefixes, userPrefix));
    }

    private static String actorOf(CallContext ctx) {
        return ctx == null ? null : ctx.getUsername();
    }

    private String read(String key, String fallback) {
        if (propertyManager == null) {
            return fallback;
        }
        String v = propertyManager.readValue(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private void audit(String repositoryId, String actor, String jobId, Kind kind, Operation operation,
            String mode, boolean success, Map<String, Object> counts) {
        // kind, operation, mode and counts — never a row's cells.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name().toLowerCase(Locale.ROOT));
        details.put("operation", operation.name().toLowerCase(Locale.ROOT));
        details.put("mode", mode);
        details.put("counts", counts);
        AuditEmitSupport.safeEmit(auditLogger, AuditOperation.PRINCIPAL_BATCH, repositoryId, actor, jobId,
                success, null, details);
    }

    /** Reads in 8 KiB chunks and stops at the first chunk that takes the total over MAX_BYTES: at
     *  most MAX_BYTES + 8192 bytes are consumed, and the 413 comes before anything is parsed. */
    static byte[] readBounded(InputStream in) {
        if (in == null) {
            throw new PrincipalBatchRequestException(400, "the file part is missing");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > PrincipalBatch.MAX_BYTES) {
                    throw new PrincipalBatchRequestException(413, "the file is larger than "
                            + PrincipalBatch.MAX_BYTES + " bytes");
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new PrincipalBatchRequestException(400, "the file could not be read");
        }
        return out.toByteArray();
    }

    /** What a request carried, in one shape whichever way it arrived. */
    static final class Input {
        final Kind kind;
        final Operation operation;
        final OnUnexpected onUnexpected;
        final String planId;
        final byte[] rawBytes;
        final List<Row> rows;

        private Input(Kind kind, Operation operation, OnUnexpected onUnexpected, String planId,
                byte[] rawBytes, List<Row> rows) {
            this.kind = kind;
            this.operation = operation;
            this.onUnexpected = onUnexpected;
            this.planId = planId;
            this.rawBytes = rawBytes;
            this.rows = rows;
        }

        static Input withoutRows(String kind, String operation, String onUnexpected, String planId) {
            String plan = blankToNull(planId);
            if (plan == null) {
                throw new PrincipalBatchRequestException(400, "execute needs a planId or the rows");
            }
            return new Input(blankToNull(kind) == null ? null : Kind.parse(kind),
                    blankToNull(operation) == null ? null : Operation.parse(operation),
                    OnUnexpected.parse(onUnexpected), plan, null, null);
        }

        static Input fromCsv(byte[] bytes, String kind, String operation, String onUnexpected, String planId) {
            Kind k = Kind.parse(kind);
            Operation op = Operation.parse(operation);
            if (!op.appliesTo(k)) {
                throw new PrincipalBatchRequestException(400, "operation " + op.name().toLowerCase(Locale.ROOT)
                        + " does not apply to " + k.name().toLowerCase(Locale.ROOT));
            }
            List<Row> rows = PrincipalBatchEngine.parseCsv(bytes, k, op);
            return new Input(k, op, OnUnexpected.parse(onUnexpected), blankToNull(planId), bytes, rows);
        }

        @SuppressWarnings("unchecked")
        static Input fromJson(ObjectMapper json, String body) {
            if (body == null || body.isBlank()) {
                throw new PrincipalBatchRequestException(400, "the JSON body is empty");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > PrincipalBatch.MAX_BYTES) {
                throw new PrincipalBatchRequestException(413, "the body is larger than "
                        + PrincipalBatch.MAX_BYTES + " bytes");
            }
            Map<String, Object> root;
            try {
                root = json.readValue(body, Map.class);
            } catch (RuntimeException e) {
                // Jackson 3 reports a malformed body as an unchecked JacksonException.
                throw new PrincipalBatchRequestException(400, "the body is not valid JSON");
            }
            String planId = blankToNull(str(root.get("planId")));
            Object rowsRaw = root.get("rows");
            Kind k = root.get("kind") == null ? null : Kind.parse(str(root.get("kind")));
            Operation op = root.get("operation") == null ? null : Operation.parse(str(root.get("operation")));
            OnUnexpected on = OnUnexpected.parse(str(root.get("onUnexpected")));
            if (rowsRaw == null) {
                if (planId == null) {
                    throw new PrincipalBatchRequestException(400, "execute needs a planId or the rows");
                }
                return new Input(k, op, on, planId, null, null);
            }
            if (k == null || op == null) {
                throw new PrincipalBatchRequestException(400, "kind and operation are required with rows");
            }
            if (!op.appliesTo(k)) {
                throw new PrincipalBatchRequestException(400, "operation " + op.name().toLowerCase(Locale.ROOT)
                        + " does not apply to " + k.name().toLowerCase(Locale.ROOT));
            }
            if (!(rowsRaw instanceof List<?> list)) {
                throw new PrincipalBatchRequestException(400, "rows must be an array");
            }
            if (list.size() > PrincipalBatch.MAX_ROWS) {
                throw new PrincipalBatchRequestException(413, "rows has " + list.size()
                        + " entries; the limit is " + PrincipalBatch.MAX_ROWS);
            }
            List<Row> rows = PrincipalBatchEngine.rowsFromJson(list, k, op);
            // The digest a password-carrying plan is bound to: the ROWS as sent, not the whole
            // body — the confirming request carries a planId the previewed one did not.
            byte[] rowBytes;
            try {
                rowBytes = json.writeValueAsString(rowsRaw).getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                throw new PrincipalBatchRequestException(400, "rows could not be re-serialised");
            }
            return new Input(k, op, on, planId, rowBytes, rows);
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }
}
