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

import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The unresolved-capture listing: ingests that started changing things and never said they
 * finished.
 *
 * <p>Without this the whole boundary is invisible. A row in {@code UNRESOLVED} is the only trace
 * of an ingest that crashed between committing the content and committing the evidence, and the
 * existing journal listing cannot show it — that one selects {@code type == lineage_event}, and
 * these rows deliberately never become events.
 *
 * <h2>What an entry does and does not mean</h2>
 *
 * <p>It means: an intent was opened, and nothing completed it before the deadline. It does
 * <b>not</b> mean a document was created, or that one was not. Deciding that needs a stamp the
 * ingest does not write yet, so the sweeper does not go looking and neither does this endpoint —
 * showing a guess here would be worse than showing the attempt.
 */
@RestController
@RequestMapping("/v1/admin/capture-intents")
public class CaptureIntentController {

    private static final Logger logger = LoggerFactory.getLogger(CaptureIntentController.class);

    @Autowired(required = false)
    private CaptureMaintenanceStore maintenanceStore;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /** For tests and for wiring without a servlet container. */
    public void setMaintenanceStore(CaptureMaintenanceStore maintenanceStore) {
        this.maintenanceStore = maintenanceStore;
    }

    /**
     * Unresolved captures, newest first.
     *
     * @param repositoryId optional filter; absent means every repository
     * @param limit        page size, bounded by the store
     * @param offset       where to start
     */
    @GetMapping("/unresolved")
    public ResponseEntity<Map<String, Object>> listUnresolved(
            @RequestParam(required = false) String repositoryId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) {
            return forbidden;
        }
        if (maintenanceStore == null) {
            // Distinguished from an empty listing on purpose. "The boundary is not wired" and
            // "nothing is unresolved" look identical otherwise, and the second is the answer an
            // operator would act on.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "unavailable");
            body.put("message", "The capture boundary is not wired in this deployment, so "
                    + "whether anything is unresolved cannot be answered");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        try {
            CaptureMaintenanceStore.UnresolvedPage page =
                    maintenanceStore.listUnresolved(repositoryId, limit, offset);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("count", page.entries().size());
            body.put("limit", page.limit());
            body.put("offset", page.offset());
            body.put("hasMore", page.hasMore());
            body.put("entries", page.entries());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Could not list unresolved captures: {}", e.toString(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "Could not read the unresolved captures: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    private ResponseEntity<Map<String, Object>> requireAdminOrForbidden() {
        if (!isAdmin()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Admin access required");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        return null;
    }

    private boolean isAdmin() {
        if (httpRequest == null) {
            return false;
        }
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            return false;
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        return isAdmin != null && isAdmin;
    }
}
