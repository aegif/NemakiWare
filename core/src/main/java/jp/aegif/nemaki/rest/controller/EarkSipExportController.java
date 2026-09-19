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
 * <p>A SIP is handed to another organisation, and once it has left there is no recall. That
 * decision belongs to an administrator whatever the flags say — <b>the document's own bytes are
 * packaged either way</b>, so a record whose CONTENT holds personal data leaves with it at the
 * default setting. {@code includeInternalOnly} widens what leaves by adding the METADATA
 * PROPERTIES the disclosure table marks INTERNAL_ONLY; it is not a switch that keeps personal
 * data in. It defaults to false so that the wider disclosure is not made by accident.
 *
 * <p>This paragraph said the narrower thing until 2026-08-28 — the fourth exit of that one claim
 * in this file, after the two header names and the exporter's own option javadoc were corrected
 * for it. It sits two lines above the comment that records those corrections.
 */
@RestController
@RequestMapping("/v1/admin/eark")
public class EarkSipExportController {

    private static final Logger logger = LoggerFactory.getLogger(EarkSipExportController.class);

    /**
     * What the export limits mean when NO package was produced.
     *
     * <p>{@link #EXPORT_LIMITS} is written about a package in the caller's hands — "This package
     * is built to E-ARK CSIP 2.2.0", "whether the validator was RUN on it is in the
     * X-Nemaki-Csip-Validated header". On a 409 or a 500 there is no package and no such header,
     * and the sentences describe an artefact that does not exist.
     *
     * <p>The success path of the bag route gained a qualifying prefix in this change set and its
     * refusal paths did not — the success ↔ error seam, on the fix itself. The custody endpoints
     * avoid the whole shape by putting their limits BEFORE the branch, so no arm can differ;
     * that is not available here because the two arms genuinely mean different things.
     */
    private static final String NO_PACKAGE_WAS_PRODUCED =
            "NO PACKAGE WAS PRODUCED by this request, so the limits below describe what one "
                    + "would have been rather than anything you are holding: ";

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

    /**
     * Says whether the EXPORTER is wired here, without making a package.
     *
     * <p>Not "whether an export could be made", which is what this said. The flag reports one
     * bean's presence; {@code EarkSipExporter.export} also refuses when the content service is
     * not wired, so a node can answer {@code available: true} and then refuse every export. A
     * capability endpoint that overstates the capability is the kind of answer somebody
     * schedules a migration around.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("available", exporter != null);
        body.put("availableMeans", "the E-ARK exporter bean is wired on this node. Export ALSO "
                + "requires the content service, which this does not check — so true here is "
                + "not a promise that a package can be produced.");
        body.put("csipVersion", EarkSipExporter.CSIP_VERSION);
        // Same qualification as the refusal paths: this endpoint reports a capability, and
        // EXPORT_LIMITS is written about a package in the caller's hands.
        body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
        return ResponseEntity.ok(body);
    }

    /**
     * Builds a SIP for one object and returns it.
     *
     * @param includeInternalOnly whether to include the METADATA PROPERTIES the disclosure
     *        table marks INTERNAL_ONLY. <b>Defaults to false.</b> Setting it true widens what
     *        leaves this organisation in a file that cannot be recalled. It does <b>not</b>
     *        govern the document's bytes: {@code EarkSipExporter.writePayload} packages the
     *        content unconditionally, so false is not "no personal data leaves".
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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment().filename(exported.sip().getFileName().toString()).build());
            // The omissions travel in headers, because the body is a zip and a caller streaming
            // it to disk would never see a JSON note. A package that quietly dropped fields
            // reads as a complete record of what was captured.
            headers.add("X-Nemaki-Withheld-Property-Count",
                    String.valueOf(exported.withheldPropertyCount()));
            // NOT "X-Nemaki-Includes-Personal-Data". That name answered a question this
            // flag does not: includeInternalOnly selects METADATA PROPERTIES, and the
            // document body is written unconditionally, so a package could carry personal
            // data in its content while the header said "false". A machine-readable
            // "false" is worse than prose, because a caller can act on it. The prose was
            // corrected first and this header kept saying the retracted thing -- one
            // claim, several exits, for the third time in this change.
            headers.add("X-Nemaki-Includes-Internal-Only-Properties",
                    String.valueOf(includeInternalOnly));
            headers.add("X-Nemaki-Content-Included", "true");
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
            return streaming(headers, exported.sip(), workDir);
        } catch (EarkSipExporter.ExportRefusedException e) {
            // Refusals are the designed outcome for "we would have had to ship something
            // incomplete", so they are a 409 with the reason, not a 500 with a stack trace.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "refused");
            body.put("message", e.getMessage());
            body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
            logger.warn("E-ARK export of {}/{} refused: {}", repositoryId, objectId,
                    e.getMessage());
            // Nothing is being streamed out of this directory, so it goes now. Every call to
            // this endpoint made one under the system temp directory and none of them was ever
            // removed: a refused export — the DESIGNED outcome for an incomplete record —
            // left a half-built package on disk for ever, on the endpoint an operator retries.
            deleteWorkDir(workDir);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (java.io.IOException e) {
            // The package was built and then could not be opened to stream out. Same shape as
            // the refusal: nothing is being served, so the directory goes now.
            deleteWorkDir(workDir);
            logger.warn("The E-ARK package for {}/{} could not be streamed: {}", repositoryId,
                    objectId, e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "the package was built but could not be read back to send: "
                    + e.getMessage());
            body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        } catch (RuntimeException e) {
            deleteWorkDir(workDir);
            throw e;
        }
    }

    /**
     * The built package, streamed out, with its working directory removed once it has been.
     *
     * <h2>Why not a {@link FileSystemResource}</h2>
     *
     * <p>Every call to {@code /export} and {@code /bag} made a directory under the system temp
     * directory and nothing ever removed it. The refusal paths were cleaned first because they
     * are easy: nothing is being served, so the directory can go before the method returns. The
     * SUCCESS path cannot do that — Spring writes the body after this controller has returned —
     * so the directory has to outlive the call, and "outlive the call" quietly became "outlive
     * the JVM".
     *
     * <p>A {@code FileSystemResource} reports {@code isFile() == true}, which lets the servlet
     * container take a zero-copy path that never opens {@code getInputStream()} — so a delete
     * hung off stream close would sometimes not run, and sometimes run while the response was
     * still being written. An {@code InputStreamResource} takes that choice away: Spring copies
     * through the stream exactly once, and closing it is the last thing that happens to the
     * file. The length is set explicitly because this resource cannot report one.
     *
     * <p>Deletion failure is logged and swallowed. An export that succeeded must not become an
     * error because a temporary file could not be removed — and by then the bytes are already
     * on their way.
     */
    private static ResponseEntity<Resource> streaming(HttpHeaders headers, Path file, Path workDir)
            throws java.io.IOException {
        long length = Files.size(file);
        java.io.InputStream in = Files.newInputStream(file);
        Resource body = new org.springframework.core.io.InputStreamResource(
                new java.io.FilterInputStream(in) {
                    @Override
                    public void close() throws java.io.IOException {
                        try {
                            super.close();
                        } finally {
                            deleteWorkDir(workDir);
                        }
                    }
                });
        headers.setContentLength(length);
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
    }

    /**
     * Removes a working directory once nothing is being served out of it.
     *
     * <p>Called from two places, for the same reason at different times. The refusal and error
     * paths call it directly: nothing is being served, so the directory can go before the
     * method returns. The success path cannot — Spring writes the body after the controller
     * returns — so {@link #streaming} hangs this off the stream's close instead.
     *
     * <p>The success path was a known leak for one round, and the javadoc here said so. It is
     * fixed; the sentence is gone rather than left standing, because a caveat that outlives
     * what it describes is read as a live limitation. Design §25, §29.
     *
     * <p>Failure to delete is logged and swallowed: an export that succeeded must not be turned
     * into an error because a temporary file could not be removed.
     */
    private static void deleteWorkDir(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    logger.warn("Could not remove {}: {}", path, e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.warn("Could not remove the E-ARK working directory {}: {}", workDir,
                    e.getMessage());
        }
    }

    /**
     * What every statement on a bag response is about.
     *
     * <p>The validator, the export limits and the notes all describe the E-ARK SIP. The artefact
     * the caller receives is the bag around it. One prefix, used everywhere on this route, so
     * the response cannot qualify one sentence and leave its neighbour bare.
     */
    private static final String SIP_INSIDE_BAG =
            "About the E-ARK SIP inside this bag, not about the bag: ";

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
            body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        java.nio.file.Path workDir = null;
        try {
            workDir = java.nio.file.Files.createTempDirectory("nemaki-bag-");
            EarkSipExporter.Exported exported = exporter.export(repositoryId, objectId,
                    // The same construction the export endpoint uses, so the two cannot
                    // disagree about which METADATA PROPERTIES are included. Neither of them
                    // governs the payload — the document's bytes are written either way.
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
            // NOT "X-Nemaki-Includes-Personal-Data". That name answered a question this
            // flag does not: includeInternalOnly selects METADATA PROPERTIES, and the
            // document body is written unconditionally, so a package could carry personal
            // data in its content while the header said "false". A machine-readable
            // "false" is worse than prose, because a caller can act on it. The prose was
            // corrected first and this header kept saying the retracted thing -- one
            // claim, several exits, for the third time in this change.
            headers.add("X-Nemaki-Includes-Internal-Only-Properties",
                    String.valueOf(includeInternalOnly));
            headers.add("X-Nemaki-Content-Included", "true");
            // Prefixed for the same reason the CSIP limits are, below. EXPORT_LIMITS says
            // "THIS package is built to E-ARK CSIP 2.2.0" and points at the validated header,
            // which is true of the SIP and false of the bag around it — so on this response the
            // unqualified sentence told the reader the verdict was about the artefact in hand.
            // Qualifying only the CSIP limits and leaving this one bare made the response
            // disagree with itself.
            headers.add("X-Nemaki-Export-Limits", SIP_INSIDE_BAG + EXPORT_LIMITS);
            // The SAME verdict the /export response carries. The SIP inside this bag went
            // through the same validation, and omitting the header here left a caller unable to
            // tell "checked and accepted" from "not checked on this node" for a package that is
            // MORE likely to be handed straight to a receiver, not less.
            EarkSipExporter.Validation validation = exported.validation();
            headers.add("X-Nemaki-Csip-Validated",
                    validation == null ? "unknown" : String.valueOf(validation.ran()));
            if (validation != null) {
                // Prefixed, because the artefact being downloaded here is the BAG and the
                // validator never saw a bag. The limits text says "this package's structure and
                // METS", which is true of the SIP inside and false of the thing in the reader's
                // hands — the same sentence, on this response, would name the wrong object.
                headers.add("X-Nemaki-Csip-Validation-Limits",
                        SIP_INSIDE_BAG + validation.limits().replace('\n', ' '));
            }
            // The bag's OWN limits, separately: what a reader must not conclude about the
            // receiving system reading the SIP inside it is a different statement from what
            // the package itself establishes.
            headers.add("X-Nemaki-Bag-Limits", bagged.limits().replace('\n', ' '));
            headers.add("X-Nemaki-Payload-Oxum", bagged.payloadOxum());
            for (String note : exported.notes()) {
                // Same prefix. When the validator did not run, EarkSipExporter puts the very
                // sentence carried by X-Nemaki-Csip-Validation-Limits into notes as well — so
                // without this the identical text appeared twice on one response, once saying
                // which object it was about and once not.
                headers.add("X-Nemaki-Export-Note", SIP_INSIDE_BAG + note.replace('\n', ' '));
            }
            return streaming(headers, bagged.zippedBag(), workDir);
        } catch (EarkSipExporter.ExportRefusedException e) {
            // Same as the export route: no file is being served, so the directory goes now.
            // This route makes TWO levels of temporary output (the SIP and the bag around it),
            // so a failed bag left more behind than a failed export did.
            deleteWorkDir(workDir);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "refused");
            body.put("message", e.getMessage());
            body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (Exception e) {
            deleteWorkDir(workDir);
            logger.warn("The bag for {}/{} could not be built: {}", repositoryId, objectId,
                    e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "the bag could not be built: " + e.getMessage());
            body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
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
        // Prefixed, because nothing was built. This class's own javadoc records that the bag
        // route's success path got the prefix and its refusal path did not — "the seam between
        // success and error, on top of the correction itself". These two shared helpers are the
        // last exits with neither the prefix nor the limits, and they serve all three endpoints.
        body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    private ResponseEntity<Map<String, Object>> unavailable(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        body.put("limits", NO_PACKAGE_WAS_PRODUCED + EXPORT_LIMITS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
