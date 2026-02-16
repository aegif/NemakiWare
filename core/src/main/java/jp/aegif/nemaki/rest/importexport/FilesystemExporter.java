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

        ExportResult result = new ExportResult();
        exportFolderToFilesystem(repositoryId, folder, targetDir, callContext, result, allowOverwrite);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void exportFolderToFilesystem(String repositoryId, Folder folder, java.nio.file.Path targetDir,
            CallContext callContext, ExportResult result, boolean allowOverwrite) throws Exception {

        ContentService cs = getContentService();
        List<Content> children = cs.getChildren(repositoryId, folder.getId());

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

            java.nio.file.Path childPath = targetDir.resolve(child.getName());

            if (child instanceof Folder) {
                Files.createDirectories(childPath);
                result.foldersExported++;

                exportFolderToFilesystem(repositoryId, (Folder) child, childPath, callContext, result, allowOverwrite);

            } else if (child instanceof Document) {
                Document doc = (Document) child;

                if (Files.exists(childPath) && !allowOverwrite) {
                    result.errors.add("File already exists (overwrite not allowed): " + child.getName());
                    continue;
                }

                if (doc.getAttachmentNodeId() != null) {
                    try {
                        var attachment = cs.getAttachment(repositoryId, doc.getAttachmentNodeId());
                        if (attachment != null && attachment.getInputStream() != null) {
                            StandardOpenOption[] options = allowOverwrite
                                ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING }
                                : new StandardOpenOption[] { StandardOpenOption.CREATE_NEW };
                            try (InputStream is = attachment.getInputStream();
                                 OutputStream os = Files.newOutputStream(childPath, options)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, len);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to export content for: " + childPath, e);
                        result.errors.add("Failed to export content: " + child.getName());
                        continue;
                    }
                }

                JSONObject metadata = zipExporter.buildDocumentMetadata(repositoryId, doc, callContext);
                java.nio.file.Path metaPath = targetDir.resolve(child.getName() + META_SUFFIX);
                if (Files.exists(metaPath) && !allowOverwrite) {
                    result.errors.add("Metadata file already exists (overwrite not allowed): " + child.getName() + META_SUFFIX);
                } else {
                    try (FileWriter writer = new FileWriter(metaPath.toFile(), StandardCharsets.UTF_8)) {
                        writer.write(metadata.toJSONString());
                    }
                }

                result.documentsExported++;

                exportVersionHistoryToFilesystem(repositoryId, doc, targetDir, callContext, result, allowOverwrite);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void exportVersionHistoryToFilesystem(String repositoryId, Document doc, java.nio.file.Path targetDir,
            CallContext callContext, ExportResult result, boolean allowOverwrite) {

        try {
            ContentService cs = getContentService();
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

                String versionFileName = doc.getName() + VERSION_PREFIX + versionNum;
                java.nio.file.Path versionPath = targetDir.resolve(versionFileName);

                if (Files.exists(versionPath) && !allowOverwrite) {
                    result.errors.add("Version file already exists (overwrite not allowed): " + versionFileName);
                    versionNum++;
                    continue;
                }

                if (version.getAttachmentNodeId() != null) {
                    try {
                        var attachment = cs.getAttachment(repositoryId, version.getAttachmentNodeId());
                        if (attachment != null && attachment.getInputStream() != null) {
                            StandardOpenOption[] options = allowOverwrite
                                ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING }
                                : new StandardOpenOption[] { StandardOpenOption.CREATE_NEW };
                            try (InputStream is = attachment.getInputStream();
                                 OutputStream os = Files.newOutputStream(versionPath, options)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, len);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to export version content: " + versionFileName, e);
                    }
                }

                JSONObject versionMeta = new JSONObject();
                versionMeta.put("versionLabel", version.getVersionLabel());
                versionMeta.put("checkinComment", version.getCheckinComment());
                versionMeta.put("isMajorVersion", version.isMajorVersion());

                java.nio.file.Path versionMetaPath = targetDir.resolve(versionFileName + META_SUFFIX);
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
        }
    }
}
