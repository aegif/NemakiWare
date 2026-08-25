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

import jp.aegif.nemaki.rest.eark.EarkSipExporter;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exporting one record as an E-ARK SIP (P3-1).
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>{@link EarkSipExporter} shipped as a {@code @Component} in a package nothing scans, with no
 * caller and no endpoint — so it was never a bean, and "a record can be exported" was true only
 * of a unit test. A reviewer pointed out that the {@code @Component} made it worse than merely
 * unused: it read as wired. This is the door.
 *
 * <h2>Admin only, and the reason is not squeamishness</h2>
 *
 * <p>A SIP is handed to another organisation. With {@code includeInternalOnly=true} it carries
 * the properties the disclosure table marks as personal data, and once it has left there is no
 * recall. That decision belongs to an administrator, and the parameter defaults to false so that
 * nobody makes it by accident.
 */
@RestController
@RequestMapping("/v1/admin/eark")
public class EarkSipExportController {

    private static final Logger logger = LoggerFactory.getLogger(EarkSipExportController.class);

    /**
     * What this endpoint does NOT establish, said in the response rather than in a manual.
     *
     * <p>The package passes the reference validator bundled with commons-ip2. That is a
     * statement about the container, not about the record, and not about any receiving
     * archive's own acceptance profile.
     */
    static final String EXPORT_LIMITS =
            "This package is built to E-ARK CSIP 2.2.0 and passes the reference validator "
                    + "bundled with commons-ip2. That establishes that the CONTAINER is "
                    + "well formed. It is NOT a statement that any particular archive will "
                    + "accept it, NOT a claim of E-ARK certification, and NOT a statement that "
                    + "the record's metadata is true — the descriptive metadata is what the "
                    + "source system reported at capture. The authenticity report inside the "
                    + "package carries the per-section limits.";

    @Autowired(required = false)
    private EarkSipExporter exporter;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /** Says whether an export could be made here, without making one. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("available", exporter != null);
        body.put("csipVersion", EarkSipExporter.CSIP_VERSION);
        body.put("limits", EXPORT_LIMITS);
        return ResponseEntity.ok(body);
    }

    /**
     * Builds a SIP for one object and returns it.
     *
     * @param includeInternalOnly whether to include the properties the disclosure table marks
     *        INTERNAL_ONLY. <b>Defaults to false.</b> Setting it true puts personal data into a
     *        file that is about to leave this organisation and cannot be recalled.
     */
    @PostMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam String repositoryId,
            @RequestParam String objectId,
            @RequestParam(defaultValue = "false") boolean includeInternalOnly,
            @RequestParam(defaultValue = "") String submittingOrganisation) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (exporter == null) {
            return unavailable("the E-ARK exporter is not wired on this node");
        }
        Path workDir;
        try {
            workDir = Files.createTempDirectory("nemaki-eark-");
        } catch (Exception e) {
            return unavailable("a working directory could not be created: " + e.getMessage());
        }
        try {
            EarkSipExporter.Exported exported = exporter.export(repositoryId, objectId,
                    new EarkSipExporter.Options(includeInternalOnly,
                            submittingOrganisation.isBlank() ? "NemakiWare deployment"
                                    : submittingOrganisation),
                    workDir);
            Resource body = new FileSystemResource(exported.sip().toFile());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment().filename(exported.sip().getFileName().toString()).build());
            // The omissions travel in headers, because the body is a zip and a caller streaming
            // it to disk would never see a JSON note. A package that quietly dropped fields
            // reads as a complete record of what was captured.
            headers.add("X-Nemaki-Withheld-Property-Count",
                    String.valueOf(exported.withheldPropertyCount()));
            headers.add("X-Nemaki-Includes-Personal-Data", String.valueOf(includeInternalOnly));
            headers.add("X-Nemaki-Export-Limits", EXPORT_LIMITS);
            for (String note : exported.notes()) {
                headers.add("X-Nemaki-Export-Note", note.replace('\n', ' '));
            }
            return ResponseEntity.ok().headers(headers)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
        } catch (EarkSipExporter.ExportRefusedException e) {
            // Refusals are the designed outcome for "we would have had to ship something
            // incomplete", so they are a 409 with the reason, not a 500 with a stack trace.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "refused");
            body.put("message", e.getMessage());
            body.put("limits", EXPORT_LIMITS);
            logger.warn("E-ARK export of {}/{} refused: {}", repositoryId, objectId,
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    private ResponseEntity<Map<String, Object>> requireAdmin() {
        Object context = httpRequest == null ? null : httpRequest.getAttribute("CallContext");
        boolean admin = context instanceof CallContext callContext
                && Boolean.TRUE.equals(callContext.get(CallContextKey.IS_ADMIN));
        if (admin) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", "Admin access required");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    private ResponseEntity<Map<String, Object>> unavailable(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
