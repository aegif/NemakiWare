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
import org.springframework.web.bind.annotation.PathVariable;
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
     * <p>Whether the reference validator bundled with commons-ip2 was RUN on a given package is
     * a per-response fact, carried in {@code X-Nemaki-Csip-Validated} — the header exists
     * because "the validator rejected it" and "the validator could not check it" are different
     * answers, and only the first is a defect. Saying flatly that the package "passes the
     * reference validator" here would assert on every response a check that may not have run.
     *
     * <p>Even a package it accepted is a statement about the container: not about the record,
     * not about any receiving archive's acceptance profile, and — measured against RODA 6.3.0 —
     * not about which parts of the package a receiver that ingests it will keep.
     */
    static final String EXPORT_LIMITS =
            "This package is built to E-ARK CSIP 2.2.0. Whether the bundled reference validator "
                    + "was RUN on it is in the X-Nemaki-Csip-Validated header — a package the "
                    + "validator rejected is never returned, but one it could not check is, and "
                    + "says so. Even a package it accepted is only a statement that the "
                    + "CONTAINER is well formed. It is NOT a statement that any particular "
                    + "archive will accept it, NOT a claim of E-ARK certification, and NOT a "
                    + "statement that the record's metadata is true — the descriptive metadata "
                    + "is what the source system reported at capture. Nor does an archive that "
                    + "INGESTS this package necessarily KEEP every part of it WHERE IT WAS PUT: "
                    + "measured against RODA 6.3.0 on 2026-08-27, the PREMIS in "
                    + "metadata/preservation was not in the resulting AIP at all, and everything "
                    + "this package puts in metadata/other arrived under metadata/descriptive "
                    + "instead. The authenticity report inside the package carries the "
                    + "per-section limits.";

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
            // The validator's verdict, in a header for the same reason as the omissions: a
            // caller streaming the zip to disk never reads a JSON body. "Not checked on this
            // node" is a different answer from "checked and accepted", and a receiver that
            // cannot tell them apart has been given the stronger one for free.
            EarkSipExporter.Validation validation = exported.validation();
            headers.add("X-Nemaki-Csip-Validated",
                    validation == null ? "unknown" : String.valueOf(validation.ran()));
            if (validation != null) {
                headers.add("X-Nemaki-Csip-Validation-Limits",
                        validation.limits().replace('\n', ' '));
            }
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

    /**
     * The same package, in the transfer format Archivematica accepts.
     *
     * <p>A separate endpoint rather than a flag on the export, because a bag is not a better
     * SIP — it is a different thing for a different receiver, and the limits that travel with
     * it say the far end will not read the SIP inside it. A caller that wants an E-ARK SIP for
     * an E-ARK receiver should not get a bag by accident.
     */
    @PostMapping(value = "/{repositoryId}/objects/{objectId}/bag")
    public ResponseEntity<?> bag(@PathVariable String repositoryId,
            @PathVariable String objectId,
            @RequestParam(required = false, defaultValue = "false") boolean includeInternalOnly,
            @RequestParam(required = false, defaultValue = "") String submissionId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (exporter == null) {
            return unavailable("E-ARK SIP export is not available on this node");
        }
        if (submissionId == null || submissionId.isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "a transfer needs a submission id: a bag with no "
                    + "External-Identifier cannot be referred to in a later receipt");
            body.put("limits", EXPORT_LIMITS);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        java.nio.file.Path workDir = null;
        try {
            workDir = java.nio.file.Files.createTempDirectory("nemaki-bag-");
            EarkSipExporter.Exported exported = exporter.export(repositoryId, objectId,
                    // The same construction the export endpoint uses, so the two
                    // cannot disagree about what "include personal data" means.
                    new EarkSipExporter.Options(includeInternalOnly,
                            "NemakiWare deployment"),
                    workDir);
            jp.aegif.nemaki.custody.BagItTransferPackager.Bagged bagged =
                    jp.aegif.nemaki.custody.BagItTransferPackager.bag(exported.sip(),
                            java.nio.file.Files.createDirectories(workDir.resolve("transfer")),
                            submissionId, digestOf(exported.sip()));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment().filename(bagged.zippedBag().getFileName().toString()).build());
            headers.add("X-Nemaki-Withheld-Property-Count",
                    String.valueOf(exported.withheldPropertyCount()));
            headers.add("X-Nemaki-Includes-Personal-Data", String.valueOf(includeInternalOnly));
            headers.add("X-Nemaki-Export-Limits", EXPORT_LIMITS);
            // The bag's OWN limits, separately: what a reader must not conclude about the
            // receiving system reading the SIP inside it is a different statement from what
            // the package itself establishes.
            headers.add("X-Nemaki-Bag-Limits", bagged.limits().replace('\n', ' '));
            headers.add("X-Nemaki-Payload-Oxum", bagged.payloadOxum());
            for (String note : exported.notes()) {
                headers.add("X-Nemaki-Export-Note", note.replace('\n', ' '));
            }
            return ResponseEntity.ok().headers(headers)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(bagged.zippedBag().toFile()));
        } catch (EarkSipExporter.ExportRefusedException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "refused");
            body.put("message", e.getMessage());
            body.put("limits", EXPORT_LIMITS);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            logger.warn("The bag for {}/{} could not be built: {}", repositoryId, objectId,
                    e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "the bag could not be built: " + e.getMessage());
            body.put("limits", EXPORT_LIMITS);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /** SHA-256 of the package, which is what a later receipt has to name. */
    private static String digestOf(java.nio.file.Path file) throws java.io.IOException {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.io.IOException("this JVM does not provide SHA-256", e);
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
