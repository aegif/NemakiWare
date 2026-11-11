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
 ******************************************************************************/
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The version history a ZIP export writes carries only the versions the caller may read (R320, 3.4.2).
 *
 * <p>{@code ZipExporter.exportVersionHistory} — the one writer behind the export of a folder and the export of chosen
 * objects — read every version of a document's series and wrote each version's content and metadata into the archive.
 * Nothing asked what the caller may read: each version has its own ACL, and a caller whom {@code getObject} refuses a
 * version was handed that version's content in the archive (measured on nb33 — a document whose first version only an
 * administrator may read, exported by a caller who is no administrator).
 *
 * <p>What is locked: the versions written are the ones the permission service keeps for the caller; a version the
 * caller may not read is not written and its content is not read from the store; a caller who may read every version
 * is handed them all as before; and a permission that could not be asked — the service fails, answers nothing, or is not there — stops
 * the archive instead of being taken for a refusal, as every other failure to read the history does.
 *
 * <p>The product's {@code ZipExporter.exportVersionHistory} is driven and what it wrote is read back from the archive (that
 * the export of a folder and the export of chosen objects reach it is measured on a running server, not here). The versions, their
 * contents and the permission decisions are stand-ins, reached the way the exporter reaches them (through the Spring
 * context). Which version the caller may read is the permission service's to say; this lock does not repeat that rule.
 */
class TheExportedVersionHistoryCarriesOnlyWhatTheCallerMayReadTest {

    private static final String REPO = "bedroom";
    private static final String BASE = "folder/report.txt";

    private final ContentService contentService = mock(ContentService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final CallContext callContext = mock(CallContext.class);
    /** The ids of the versions the caller may read. */
    private final Set<String> readable = new HashSet<>();
    private final List<Document> versions = new ArrayList<>();
    private ApplicationContext previousContext;
    private long clock = 1_700_000_000_000L;

    @BeforeEach
    void seedTheSpringContext() {
        when(callContext.getUsername()).thenReturn("reader");
        when(context.getBean("ContentService", ContentService.class)).thenReturn(contentService);
        when(context.getBean("PermissionService", PermissionService.class)).thenReturn(permissions);
        // The series: V1, V2, V3 in the order they were made; V3 is the latest, the document that is exported.
        for (int n = 1; n <= 3; n++) {
            Document version = new Document();
            version.setId("V" + n);
            version.setName("report.txt");
            version.setType("cmis:document");
            version.setObjectType("cmis:document");
            version.setVersionSeriesId("S");
            version.setVersionLabel(n + ".0");
            version.setMajorVersion(Boolean.TRUE);
            version.setLatestVersion(n == 3);
            version.setAttachmentNodeId("content-of-V" + n);
            GregorianCalendar created = new GregorianCalendar();
            created.setTimeInMillis(clock += 1000);
            version.setCreated(created);
            versions.add(version);
            AttachmentNode attachment = mock(AttachmentNode.class);
            String content = "the content of V" + n;
            when(attachment.getInputStream()).thenAnswer(call -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            when(contentService.getAttachment(REPO, "content-of-V" + n)).thenReturn(attachment);
        }
        VersionSeries series = new VersionSeries();
        series.setId("S");
        when(contentService.getVersionSeries(eq(REPO), any(Document.class))).thenReturn(series);
        when(contentService.getAllVersions(any(), eq(REPO), eq("S"))).thenAnswer(call -> new ArrayList<>(versions));
        when(permissions.getFiltered(any(), eq(REPO), any())).thenAnswer(call -> {
            List<Document> kept = new ArrayList<>();
            for (Document version : call.<List<Document>>getArgument(2)) {
                if (readable.contains(version.getId())) {
                    kept.add(version);
                }
            }
            return kept;
        });
        previousContext = SpringContext.getApplicationContext();
        new SpringContext().setApplicationContext(context);
    }

    @AfterEach
    void restoreTheSpringContext() {
        new SpringContext().setApplicationContext(previousContext);
    }

    /** What the export of the latest version's history wrote: entry name to content, in the order written. */
    private Map<String, String> historyWritten() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            new ZipExporter().exportVersionHistory(REPO, versions.get(2), BASE, zos, callContext);
            // an archive with no entry cannot be closed; the marker is not part of what is measured
            zos.putNextEntry(new ZipEntry("end-of-archive"));
            zos.closeEntry();
        }
        Map<String, String> written = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                if (!entry.getName().equals("end-of-archive")) {
                    written.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return written;
    }

    /** The contents of the versions an archive carries, in the order written — the metadata entries left out. */
    private static List<String> contentsIn(Map<String, String> written) {
        List<String> contents = new ArrayList<>();
        for (Map.Entry<String, String> entry : written.entrySet()) {
            if (!entry.getKey().endsWith(ImportExportUtils.META_SUFFIX)) {
                contents.add(entry.getValue());
            }
        }
        return contents;
    }

    private List<String> attachmentsRead() {
        List<String> ids = new ArrayList<>();
        for (Invocation call : mockingDetails(contentService).getInvocations()) {
            if (call.getMethod().getName().equals("getAttachment")) {
                ids.add(call.getArgument(1));
            }
        }
        return ids;
    }

    @Test
    @DisplayName("a version the caller may not read is not written into the archive — neither its content nor its metadata — and is not read from the store")
    void aVersionTheCallerMayNotReadIsNotWritten() {
        readable.addAll(List.of("V1", "V3"));
        Map<String, String> written = assertDoesNotThrow(this::historyWritten, "the export of the history");
        assertEquals(List.of("the content of V1"), contentsIn(written), "the contents of the versions the archive carries");
        assertEquals(List.of(BASE + ImportExportUtils.VERSION_PREFIX + "1", BASE + ImportExportUtils.VERSION_PREFIX + "1" + ImportExportUtils.META_SUFFIX),
                new ArrayList<>(written.keySet()), "the entries written for the history");
        assertEquals(List.of("content-of-V1"), attachmentsRead(), "the contents read from the store");
        for (String entry : written.values()) {
            assertEquals(false, entry.contains("2.0"), "the archive names the version the caller may not read: " + entry);
        }
    }

    @Test
    @DisplayName("a caller who may read only the document that is exported is handed no earlier version")
    void aCallerWhoMayReadOnlyTheLatestIsHandedNoEarlierVersion() {
        readable.add("V3");
        Map<String, String> written = assertDoesNotThrow(this::historyWritten, "the export of the history");
        assertEquals(Map.of(), written, "what was written for the history");
        assertEquals(List.of(), attachmentsRead(), "the contents read from the store");
    }

    @Test
    @DisplayName("a caller who may read every version is handed the earlier ones as before, oldest first")
    void aCallerWhoMayReadEveryVersionIsHandedThemAsBefore() {
        readable.addAll(List.of("V1", "V2", "V3"));
        Map<String, String> written = assertDoesNotThrow(this::historyWritten, "the export of the history");
        assertEquals(List.of("the content of V1", "the content of V2"), contentsIn(written), "the contents of the versions the archive carries");
        assertEquals(4, written.size(), "a content and a metadata entry for each earlier version");
    }

    @Test
    @DisplayName("the versions are put through the permission service once, for the caller who exports")
    void theCallerWhoExportsIsTheOneAsked() {
        readable.addAll(List.of("V1", "V2", "V3"));
        assertDoesNotThrow(this::historyWritten, "the export of the history");
        List<Invocation> asked = new ArrayList<>();
        for (Invocation call : mockingDetails(permissions).getInvocations()) {
            if (call.getMethod().getName().equals("getFiltered")) {
                asked.add(call);
            }
        }
        assertEquals(1, asked.size(), "the versions were put through the permission service " + asked.size() + " times");
        assertSame(callContext, asked.get(0).getArgument(0), "the permission was not asked for the caller who exports");
        List<String> ids = new ArrayList<>();
        for (Document version : asked.get(0).<List<Document>>getArgument(2)) {
            ids.add(version.getId());
        }
        assertEquals(List.of("V1", "V2", "V3"), ids, "the versions the permission was asked about");
    }

    @Test
    @DisplayName("a permission that could not be asked stops the archive — it is not taken for a refusal, nor for a leave to write")
    void aPermissionThatCouldNotBeAskedStopsTheArchive() {
        readable.addAll(List.of("V1", "V2", "V3"));
        when(permissions.getFiltered(any(), anyString(), any())).thenThrow(new IllegalStateException("the ACL could not be read"));
        assertThrows(ZipExporter.ExportRefusedException.class, this::historyWritten,
                "the archive was finished though the permission service failed");
        assertEquals(List.of(), attachmentsRead(), "a content was read from the store though the permission was not answered");
    }

    @Test
    @DisplayName("a permission service that answers nothing about the versions stops the archive — nothing is not a leave to write them all")
    void aPermissionServiceThatAnswersNothingStopsTheArchive() {
        readable.addAll(List.of("V1", "V2", "V3"));
        when(permissions.getFiltered(any(), anyString(), any())).thenReturn(null);
        assertThrows(ZipExporter.ExportRefusedException.class, this::historyWritten,
                "the archive was finished though the permission service answered nothing");
        assertEquals(List.of(), attachmentsRead(), "a content was read from the store though the permission was not answered");
    }

    @Test
    @DisplayName("with no permission service to ask, the archive is stopped")
    void withNoPermissionServiceTheArchiveIsStopped() {
        readable.addAll(List.of("V1", "V2", "V3"));
        when(context.getBean("PermissionService", PermissionService.class)).thenThrow(new IllegalStateException("no such bean"));
        assertThrows(ZipExporter.ExportRefusedException.class, this::historyWritten,
                "the archive was finished though there was no permission service to ask");
        assertEquals(List.of(), attachmentsRead(), "a content was read from the store though nobody was asked");
    }
}
