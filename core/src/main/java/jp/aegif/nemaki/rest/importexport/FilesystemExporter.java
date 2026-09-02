/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest.importexport;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportResult;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static jp.aegif.nemaki.rest.importexport.ImportExportUtils.*;

/**
 * Handles filesystem-based export operations (admin only).
 */
public class FilesystemExporter {

    private static final Log log = LogFactory.getLog(FilesystemExporter.class);

    private final ZipExporter zipExporter = new ZipExporter();

    public ExportResult exportToFilesystemDirectory(String repositoryId, Folder folder,
            java.nio.file.Path targetDir, CallContext callContext, boolean allowOverwrite) throws Exception {

        return exportToFilesystemDirectory(repositoryId, folder, targetDir, callContext,
                allowOverwrite, getContentService());
    }

    /**
     * The same export, driven with an explicit store.
     *
     * <p>The store used to be fetched from the Spring context inside the walk, which put the
     * refusal arms out of reach of any test that does not stand a container up. They are the
     * arms that decide whether an export that lost a document's bytes still reports success,
     * so they are the ones that most need measuring.
     */
    ExportResult exportToFilesystemDirectory(String repositoryId, Folder folder,
            java.nio.file.Path targetDir, CallContext callContext, boolean allowOverwrite,
            ContentService cs) throws Exception {

        ExportResult result = new ExportResult();
        exportFolderToFilesystem(repositoryId, folder, targetDir, callContext, result,
                allowOverwrite, cs);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void exportFolderToFilesystem(String repositoryId, Folder folder, java.nio.file.Path targetDir,
            CallContext callContext, ExportResult result, boolean allowOverwrite,
            ContentService cs) throws Exception {

        List<Content> children = cs.getChildren(repositoryId, folder.getId());
        // A short listing makes this export INCOMPLETE, and nothing else would say so: rows
        // the repository cannot decode are absent without an exception, and an export that
        // presents itself as the folder's contents is exactly where that silence becomes a
        // false completeness claim — often in a backup someone restores from later.
        if (cs.lastUnreadableChildCount() > 0) {
            result.errors.add("Folder '" + folder.getName() + "' (" + folder.getId() + "): "
                    + cs.lastUnreadableChildCount() + " child row(s) could not be decoded and "
                    + "are NOT in this export");
        }

        for (Content child : children) {
            if (child.getName() == null) {
                log.debug("Skipping child with null name (id=" + child.getId() + ", type=" + child.getType() + ")");
                continue;
            }

            if (child instanceof Folder && ".system".equals(child.getName())) {
                log.debug("Skipping .system folder during export");
                continue;
            }

            if (!(child instanceof Folder) && !(child instanceof Document)) {
                log.debug("Skipping non-folder/non-document item: " + child.getName() + " (type=" + child.getType() + ")");
                continue;
            }

            // Object names are user-controllable; sanitize to a single safe
            // segment and verify the resolved path stays under targetDir so
            // a name like "../../x" cannot escape the export root.
            java.nio.file.Path childPath = resolveUnderTarget(targetDir, sanitizeExportName(child.getName()));
            if (childPath == null) {
                result.errors.add("Skipping unsafe export name: " + child.getName());
                continue;
            }

            if (child instanceof Folder) {
                Files.createDirectories(childPath);
                result.foldersExported++;
                result.recordExported(child.getId(), child.getName(), true);

                exportFolderToFilesystem(repositoryId, (Folder) child, childPath, callContext, result, allowOverwrite, cs);

            } else if (child instanceof Document) {
                Document doc = (Document) child;

                if (Files.exists(childPath) && !allowOverwrite) {
                    result.errors.add("File already exists (overwrite not allowed): " + child.getName());
                    continue;
                }

                if (doc.getAttachmentNodeId() != null) {
                    // A content read that FAILED and an attachment that produced no stream
                    // both used to leave the file absent while the export walked on. Only
                    // the first of the two recorded an error, and the response reads
                    // "success" whenever errors is empty — so the silent one handed back a
                    // directory that looked complete. Both are errors now; the caller turns
                    // a non-empty errors list into status "partial".
                    try {
                        var attachment = cs.getAttachment(repositoryId, doc.getAttachmentNodeId());
                        if (attachment == null) {
                            throw new IOException("the attachment " + doc.getAttachmentNodeId()
                                    + " could not be read. This is NOT a statement that the"
                                    + " document has no content.");
                        }
                        // The stream is checked BEFORE the file is opened. try-with-resources
                        // initialises left to right, so with the check inside the body
                        // Files.newOutputStream had already CREATED the file — and the catch
                        // below then left a 0-byte file with no .meta.json beside it. The
                        // importer reads a sidecar-less content file as a document, so the
                        // bytes this arm refused to export came back as an empty record: the
                        // exact substitution the refusal exists to prevent, produced by the
                        // refusal itself. Found by a review of the fix.
                        InputStream is = attachment.getInputStream();
                        if (is == null) {
                            throw new IOException("the attachment "
                                    + doc.getAttachmentNodeId() + " produced no stream");
                        }
                        copyLeavingTheTargetIntactOnFailure(
                                is, childPath, allowOverwrite, result);
                    } catch (Exception e) {
                        log.warn("Failed to export content for: " + childPath, e);
                        result.errors.add("Failed to export content: " + child.getName()
                                + " (" + e.getMessage() + ")");
                        continue;
                    }
                }

                JSONObject metadata = zipExporter.buildDocumentMetadata(repositoryId, doc, callContext);
                // Derive from the already-sanitized childPath so the metadata
                // sidecar shares the same safe, in-bounds name.
                java.nio.file.Path metaPath = childPath.resolveSibling(
                        childPath.getFileName().toString() + META_SUFFIX);
                if (Files.exists(metaPath) && !allowOverwrite) {
                    result.errors.add("Metadata file already exists (overwrite not allowed): " + child.getName() + META_SUFFIX);
                } else {
                    // Through the SAME staging helper as the content bytes. The round-5 fix
                    // staged the content and left this FileWriter — with allowOverwrite it
                    // TRUNCATES the existing sidecar before writing, so a mid-write failure
                    // (disk full) destroys the old complete metadata while the content next
                    // to it is protected. The importer then reads the document without its
                    // type and properties. The same-file one-arm shape, fifth time; a
                    // sibling sweep caught it.
                    try {
                        copyLeavingTheTargetIntactOnFailure(
                                new java.io.ByteArrayInputStream(
                                        metadata.toJSONString().getBytes(StandardCharsets.UTF_8)),
                                metaPath, allowOverwrite, result);
                    } catch (java.nio.file.FileAlreadyExistsException raced) {
                        // A sidecar that appeared BETWEEN the exists() check above and the
                        // move. The move without REPLACE_EXISTING is what makes the race
                        // visible at all — the old FileWriter silently overwrote the racing
                        // file, which violated allowOverwrite=false. But the first staged
                        // version let this exception ESCAPE, which turned the same conflict
                        // the exists() check reports as one error line into a 500 for the
                        // whole export. Same outcome as the visible conflict: record, keep
                        // the document, keep walking.
                        result.errors.add("Metadata file already exists (overwrite not"
                                + " allowed): " + child.getName() + META_SUFFIX
                                + " (it appeared while the export was running)");
                    }
                }

                result.documentsExported++;
                result.recordExported(doc.getId(), doc.getName(), false);

                exportVersionHistoryToFilesystem(repositoryId, doc, targetDir, callContext, result, allowOverwrite, cs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void exportVersionHistoryToFilesystem(String repositoryId, Document doc, java.nio.file.Path targetDir,
            CallContext callContext, ExportResult result, boolean allowOverwrite,
            ContentService cs) {

        try {
            VersionSeries vs = cs.getVersionSeries(repositoryId, doc);
            if (vs == null) {
                return;
            }

            List<Document> allVersions = cs.getAllVersions(callContext, repositoryId, vs.getId());
            if (allVersions == null || allVersions.size() <= 1) {
                return;
            }

            allVersions.sort((a, b) -> {
                if (a.getCreated() != null && b.getCreated() != null) {
                    return a.getCreated().compareTo(b.getCreated());
                }
                String labelA = a.getVersionLabel();
                String labelB = b.getVersionLabel();
                if (labelA != null && labelB != null) {
                    try {
                        double vA = Double.parseDouble(labelA);
                        double vB = Double.parseDouble(labelB);
                        return Double.compare(vA, vB);
                    } catch (NumberFormatException e) {
                        return labelA.compareTo(labelB);
                    }
                }
                return 0;
            });

            int versionNum = 1;
            for (Document version : allVersions) {
                if (version.isLatestVersion()) {
                    continue;
                }

                String versionFileName = sanitizeExportName(doc.getName()) + VERSION_PREFIX + versionNum;
                java.nio.file.Path versionPath = resolveUnderTarget(targetDir, versionFileName);
                if (versionPath == null) {
                    result.errors.add("Skipping unsafe version export name: " + doc.getName());
                    versionNum++;
                    continue;
                }

                if (Files.exists(versionPath) && !allowOverwrite) {
                    result.errors.add("Version file already exists (overwrite not allowed): " + versionFileName);
                    versionNum++;
                    continue;
                }

                if (version.getAttachmentNodeId() != null) {
                    // This arm recorded NOTHING at all: the version's .meta sidecar was
                    // written next to a version file that was never created, and the export
                    // still reported "success".
                    try {
                        var attachment = cs.getAttachment(repositoryId, version.getAttachmentNodeId());
                        if (attachment == null) {
                            throw new IOException("the attachment "
                                    + version.getAttachmentNodeId() + " could not be read");
                        }
                        // Same order as the document body above: check, then open.
                        InputStream is = attachment.getInputStream();
                        if (is == null) {
                            throw new IOException("the attachment "
                                    + version.getAttachmentNodeId() + " produced no stream");
                        }
                        copyLeavingTheTargetIntactOnFailure(
                                is, versionPath, allowOverwrite, result);
                    } catch (Exception e) {
                        log.warn("Failed to export version content: " + versionFileName, e);
                        result.errors.add("Failed to export version content: "
                                + versionFileName + " (" + e.getMessage() + ")");
                        versionNum++;
                        continue;
                    }
                }

                JSONObject versionMeta = new JSONObject();
                versionMeta.put("versionLabel", version.getVersionLabel());
                versionMeta.put("checkinComment", version.getCheckinComment());
                versionMeta.put("isMajorVersion", version.isMajorVersion());

                java.nio.file.Path versionMetaPath = versionPath.resolveSibling(
                        versionPath.getFileName().toString() + META_SUFFIX);
                if (Files.exists(versionMetaPath) && !allowOverwrite) {
                    result.errors.add("Version metadata file already exists (overwrite not allowed): " + versionFileName + META_SUFFIX);
                } else {
                    // Same staging rule as the document sidecar above — one helper, so the
                    // two arms cannot drift apart again.
                    try {
                        copyLeavingTheTargetIntactOnFailure(
                                new java.io.ByteArrayInputStream(
                                        versionMeta.toJSONString().getBytes(StandardCharsets.UTF_8)),
                                versionMetaPath, allowOverwrite, result);
                    } catch (java.nio.file.FileAlreadyExistsException raced) {
                        // Same race arm as the document sidecar. Left escaping, this landed
                        // in the version-history catch and ABANDONED every remaining version
                        // of the document over one racing sidecar.
                        result.errors.add("Version metadata file already exists (overwrite"
                                + " not allowed): " + versionFileName + META_SUFFIX
                                + " (it appeared while the export was running)");
                    }
                }

                versionNum++;
            }

        } catch (Exception e) {
            log.warn("Failed to export version history for: " + doc.getName(), e);
            result.errors.add("Failed to export version history: " + doc.getName()
                    + " (" + e.getMessage() + ")");
        }
    }

    /**
     * Resolve {@code safeName} (already passed through
     * {@link ImportExportUtils#sanitizeExportName}) under {@code targetDir}
     * and verify the normalized result is still inside {@code targetDir}.
     * Returns {@code null} if the resolved path would escape the target
     * directory — a defense-in-depth check on top of name sanitization.
     */
    /**
     * Copies {@code is} onto {@code destination}, leaving whatever is already there untouched
     * unless the whole copy succeeds.
     *
     * <h2>Why this is one method and not two</h2>
     *
     * <p>The document body and the version body are the same copy written twice, and every
     * round of this batch fixed one of them and left the other: the streamless check, the
     * mid-copy cleanup, the ownership flag, and the control that measures it — four times, in
     * this file, each caught by a different reviewer. Two callers of one method cannot drift
     * apart that way.
     *
     * <h2>Why a temporary file</h2>
     *
     * <p>Writing straight to {@code destination} destroys data on the path that matters most.
     * With {@code allowOverwrite} the open uses {@code TRUNCATE_EXISTING}, so a copy that dies
     * part way has ALREADY emptied the previous, complete export; deleting the remains then
     * leaves neither the old artefact nor the new one. Two earlier attempts — deleting
     * unconditionally, then deleting only what this invocation opened — both had that shape.
     * A staging file cannot: on failure the destination is exactly as it was, and the only
     * thing left behind is a {@code .part} file with no sidecar.
     *
     * <p>"which no importer reads" is what this comment used to add, and it was false —
     * {@code FilesystemImporter} collects every regular file and skipped only sidecars and
     * version files, so a staging file left by a failed cleanup, or seen by an import running
     * at the same time, was ingested as a document. The name is now declared in
     * {@code ImportExportUtils} and the importer skips it; the two sides share one constant
     * so they cannot drift.
     *
     * <p>The replacement asks for {@code ATOMIC_MOVE} when it is allowed to overwrite. Plain
     * {@code move} with {@code REPLACE_EXISTING} may be implemented as delete-then-move, and
     * the guarantee this method is written to make — a failed export leaves the previous one
     * intact — is exactly what that would break. Where the filesystem cannot do it, the
     * export refuses rather than falling back to the weaker move, because the fallback is
     * indistinguishable from the guarantee right up to the moment it is not.
     */
    private static void copyLeavingTheTargetIntactOnFailure(InputStream is,
            java.nio.file.Path destination, boolean allowOverwrite, ExportResult result)
            throws IOException {
        java.nio.file.Path staging;
        try {
            staging = Files.createTempFile(destination.getParent(),
                    EXPORT_STAGING_PREFIX, EXPORT_STAGING_SUFFIX);
            giveTheStagingFileTheModeTheDestinationShouldHave(staging, destination, result);
        } catch (IOException | RuntimeException cannotStage) {
            // The copy below owns the stream once it starts; before that, this does. Without
            // it a disk-full staging failure leaks the attachment's stream — the case the
            // whole guard exists for.
            try {
                is.close();
            } catch (Exception ignored) {
                // the staging failure is the one worth reporting
            }
            throw cannotStage;
        }
        try {
            try (InputStream in = is;
                 OutputStream os = Files.newOutputStream(staging,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
            if (allowOverwrite) {
                Files.move(staging, destination,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } else {
                // No REPLACE_EXISTING: this is the CREATE_NEW the caller asked for, so a
                // destination that appeared during the copy still refuses.
                Files.move(staging, destination);
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(staging);
            } catch (Exception cleanup) {
                result.errors.add("A partial copy was left at " + staging
                        + " and could not be removed (" + cleanup.getMessage()
                        + "); it has no metadata sidecar and is not importable, but it"
                        + " should be swept up");
            }
            throw failed;
        }
    }

    /**
     * Makes the staging file carry the mode the destination should end up with.
     *
     * <h2>What staging quietly changed</h2>
     *
     * <p>{@code Files.createTempFile} creates owner-only (0600) by design, and {@code
     * Files.move} replaces the destination's inode — so mode, owner and hard links come from
     * the staging file, not from whatever was there. Switching from "open the destination"
     * to "stage and move" therefore turned every exported file from umask-derived 0644 into
     * 0600, and re-exporting over an earlier export silently stripped group and other read
     * from a file that had it. An export directory read by a backup agent, a share, or
     * another service account stops being readable, and nothing in the response says so.
     *
     * <p>Neither reviewer of the staging change caught this; an audit of the change measured
     * it on the actual filesystem. It is the cost of a fix that looked purely additive.
     *
     * <p>So: an existing destination lends its own permissions (an export must not downgrade
     * a file it is replacing), and a new one gets what an ordinary create would have given —
     * probed once, because the umask is not visible from Java.
     */
    private static void giveTheStagingFileTheModeTheDestinationShouldHave(
            java.nio.file.Path staging, java.nio.file.Path destination, ExportResult result) {
        try {
            if (!staging.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                return;
            }
            java.util.Set<java.nio.file.attribute.PosixFilePermission> mode =
                    Files.exists(destination)
                            ? Files.getPosixFilePermissions(destination)
                            : defaultCreateMode(staging.getParent());
            if (mode != null) {
                Files.setPosixFilePermissions(staging, mode);
            }
        } catch (Exception notPosixOrNotPermitted) {
            // Best effort on the MODE — the bytes are the record, so this is not a reason to
            // refuse the export. But the first version only logged, and that is fail-open:
            // the export reported SUCCESS while the file came out 0600, and a backup agent
            // or group reader simply cannot read it, with nothing in the response saying so.
            // errors is what turns the status to "partial"; a caller who does not care can
            // ignore it, one who does can see it. A round-6 review named the silent half.
            log.warn("Could not give " + staging + " the permissions " + destination
                    + " should have; the exported file may be owner-only", notPosixOrNotPermitted);
            result.errors.add("The exported file " + destination.getFileName()
                    + " may be owner-only: its permissions could not be set ("
                    + notPosixOrNotPermitted.getMessage() + "). The bytes are complete.");
        }
    }

    /** What an ordinary {@code newOutputStream(CREATE)} produces here, probed once. */
    private static volatile java.util.Set<java.nio.file.attribute.PosixFilePermission>
            defaultCreateMode;

    private static java.util.Set<java.nio.file.attribute.PosixFilePermission> defaultCreateMode(
            java.nio.file.Path directory) throws IOException {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> cached = defaultCreateMode;
        if (cached != null) {
            return cached;
        }
        // The umask is not readable from Java, so it is measured rather than assumed: create
        // a file the ordinary way and look at what came out.
        // Named with the STAGING prefix and suffix, not a probe-specific pair. The comment
        // below used to say a stray probe "is skipped by the importer like any staging
        // file", and with its own name it would not have been — the importer's rule matches
        // that prefix AND that suffix. Making the sentence true was cheaper than weakening
        // the rule, and the sentence was written before it was checked.
        java.nio.file.Path probe = Files.createTempFile(directory,
                EXPORT_STAGING_PREFIX + "mode-probe-", EXPORT_STAGING_SUFFIX);
        try {
            Files.delete(probe);
            try (OutputStream probeStream = Files.newOutputStream(probe,
                    StandardOpenOption.CREATE_NEW)) {
                probeStream.flush();
            }
            cached = Files.getPosixFilePermissions(probe);
            defaultCreateMode = cached;
            return cached;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (Exception ignored) {
                // a stray probe file is skipped by the importer like any staging file
            }
        }
    }

    private static java.nio.file.Path resolveUnderTarget(java.nio.file.Path targetDir, String safeName) {
        java.nio.file.Path base = targetDir.toAbsolutePath().normalize();
        java.nio.file.Path resolved = base.resolve(safeName).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            return null;
        }
        return resolved;
    }
}
