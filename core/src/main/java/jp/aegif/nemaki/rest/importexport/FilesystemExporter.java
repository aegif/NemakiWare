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

import java.io.FileWriter;
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
                        StandardOpenOption[] options = allowOverwrite
                            ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING }
                            : new StandardOpenOption[] { StandardOpenOption.CREATE_NEW };
                        try (InputStream is = attachment.getInputStream();
                             OutputStream os = Files.newOutputStream(childPath, options)) {
                            if (is == null) {
                                throw new IOException("the attachment "
                                        + doc.getAttachmentNodeId() + " produced no stream");
                            }
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = is.read(buffer)) != -1) {
                                os.write(buffer, 0, len);
                            }
                        }
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
                    try (FileWriter writer = new FileWriter(metaPath.toFile(), StandardCharsets.UTF_8)) {
                        writer.write(metadata.toJSONString());
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
                        StandardOpenOption[] options = allowOverwrite
                            ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING }
                            : new StandardOpenOption[] { StandardOpenOption.CREATE_NEW };
                        try (InputStream is = attachment.getInputStream();
                             OutputStream os = Files.newOutputStream(versionPath, options)) {
                            if (is == null) {
                                throw new IOException("the attachment "
                                        + version.getAttachmentNodeId()
                                        + " produced no stream");
                            }
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = is.read(buffer)) != -1) {
                                os.write(buffer, 0, len);
                            }
                        }
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
                    try (FileWriter writer = new FileWriter(versionMetaPath.toFile(), StandardCharsets.UTF_8)) {
                        writer.write(versionMeta.toJSONString());
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
    private static java.nio.file.Path resolveUnderTarget(java.nio.file.Path targetDir, String safeName) {
        java.nio.file.Path base = targetDir.toAbsolutePath().normalize();
        java.nio.file.Path resolved = base.resolve(safeName).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            return null;
        }
        return resolved;
    }
}
