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

import jp.aegif.nemaki.evidence.AuthenticityReport;
import jp.aegif.nemaki.evidence.AuthenticityReportAssembler;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One object's evidence, gathered and bounded (P1-4).
 *
 * <p>Design: {@code docs/design/p1-4-authenticity-report.md}. Admin-only, because the identity
 * section reads capture evidence and the custody section names connectors and schedules — an
 * inventory of how this deployment ingests, which is not a thing to hand to any authenticated
 * caller.
 *
 * <p>Two representations of the SAME report: JSON is canonical, HTML is for a person. The HTML
 * is not a separate assembly — it renders the same {@link AuthenticityReport}, so a limits
 * paragraph cannot be present in one and missing from the other.
 */
@RestController
@RequestMapping("/v1/admin/authenticity")
public class AuthenticityReportController {

    private static final Logger logger =
            LoggerFactory.getLogger(AuthenticityReportController.class);

    @Autowired(required = false)
    private AuthenticityReportAssembler assembler;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    void setAssembler(AuthenticityReportAssembler assembler) {
        this.assembler = assembler;
    }

    /**
     * @param includeInternalOnly personal data — participants, addressees — is withheld by
     *                            default. A caller who needs it for an investigation must ask
     *                            for it explicitly, and the report then says it is carrying it.
     */
    @GetMapping(value = "/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> report(
            @RequestParam String repositoryId,
            @RequestParam String objectId,
            @RequestParam(defaultValue = "false") boolean includeInternalOnly) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (assembler == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "the evidence report service is not wired on this node");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        return ResponseEntity.ok(assemble(repositoryId, objectId, includeInternalOnly).asMap());
    }

    /** The same report for a person to read. Browser print gives a PDF; see the design §2. */
    @GetMapping(value = "/report.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> reportHtml(
            @RequestParam String repositoryId,
            @RequestParam String objectId,
            @RequestParam(defaultValue = "false") boolean includeInternalOnly) {

        if (requireAdmin() != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Admin access required");
        }
        if (assembler == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("the evidence report service is not wired on this node");
        }
        return ResponseEntity.ok(assemble(repositoryId, objectId, includeInternalOnly).asHtml());
    }

    private AuthenticityReport assemble(String repositoryId, String objectId,
            boolean includeInternalOnly) {
        if (includeInternalOnly) {
            logger.info("Evidence report for {}/{} requested WITH internal-only properties",
                    repositoryId, objectId);
        }
        return assembler.assemble(repositoryId, objectId, Instant.now().toString(),
                includeInternalOnly);
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
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
