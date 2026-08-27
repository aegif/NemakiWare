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
package jp.aegif.nemaki.rest.eark;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.AuthenticityReport;
import jp.aegif.nemaki.evidence.AuthenticityReport.Section;
import jp.aegif.nemaki.evidence.AuthenticityReport.Verdict;
import jp.aegif.nemaki.evidence.AuthenticityReportAssembler;
import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.validity.ErsFormat;
import jp.aegif.nemaki.evidence.validity.EvidenceRecordService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When this node has an evidence record, the SIP carries it at the place
 * {@link ErsFormat#CSIP_LOCATION} names.
 *
 * <h2>Why this is pinned separately</h2>
 *
 * <p>The RODA acceptance test (p3-4 §10) could not say whether a receiver keeps
 * {@code metadata/preservation/ers.der}, because <b>the package submitted did not contain one</b>
 * — the tested node had no confirmed RFC 3161 anchor, so {@code EvidenceRecordService.latest}
 * returned absent and the exporter wrote nothing. That gap is only visible if something asserts
 * where the file goes when there IS one; otherwise "no ers.der in the AIP" and "no ers.der in
 * the SIP" look identical from the far end.
 *
 * <p>So this pins the near half: given a present record, the exporter puts it at
 * {@code metadata/preservation/}{@link ErsFormat#fileName()}. Whether a receiver keeps it there
 * is the far half, and it is <b>still unmeasured</b> — RODA did not keep the {@code premis.xml}
 * that sits in the same directory, which is reason to check rather than to assume either way.
 *
 * <p>The DER here is a stub. Nothing about the bytes is asserted; the subject is the path.
 */
class ErsIsInTheSipAtItsDeclaredPlaceTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "doc-1";

    @Test
    @DisplayName("a present evidence record lands at metadata/preservation/, beside the PREMIS")
    void theEvidenceRecordIsWhereTheFormatSaysItIs(@TempDir Path tmp) throws Exception {
        byte[] stubDer = "not a real ERS; this test is about the path".getBytes(StandardCharsets.UTF_8);
        Path sip = exportWith(tmp, new EvidenceRecordService.Built(stubDer, checkpoint(), null));

        Map<String, byte[]> entries = entriesOf(sip);
        String expected = ErsFormat.CSIP_LOCATION + "/" + ErsFormat.CHOSEN.fileName();
        String found = entries.keySet().stream()
                .filter(name -> name.endsWith(expected))
                .findFirst()
                .orElse(null);

        assertNotNull(found,
                "the evidence record is not at " + expected + ", so a receiver looking where "
                        + "ErsFormat.CSIP_LOCATION says to look will not find it: "
                        + entries.keySet());
        // And the PREMIS is beside it — the two share a directory, which is why the RODA result
        // for one is a reason to check the other.
        assertTrue(entries.keySet().stream()
                        .anyMatch(name -> name.endsWith(ErsFormat.CSIP_LOCATION + "/premis.xml")),
                "the PREMIS is no longer in the same directory as the evidence record, so the "
                        + "recorded reasoning in p3-4 §10 about that directory no longer applies: "
                        + entries.keySet());
    }

    @Test
    @DisplayName("no evidence record means no file — not an empty one")
    void anAbsentRecordWritesNothing(@TempDir Path tmp) throws Exception {
        // This is the shape the RODA test actually submitted. An empty or placeholder ers.der
        // would read, at the far end, as "the archive dropped our timestamp evidence".
        Path sip = exportWith(tmp,
                new EvidenceRecordService.Built(null, null, "no confirmed RFC 3161 token"));

        assertTrue(entriesOf(sip).keySet().stream()
                        .noneMatch(name -> name.endsWith(ErsFormat.CHOSEN.fileName())),
                "a package with no evidence record still carries a file named "
                        + ErsFormat.CHOSEN.fileName() + ", which a receiver would read as "
                        + "evidence this node does not have");
    }

    private static EvidenceCheckpoint checkpoint() {
        return new EvidenceCheckpoint(REPO, 1L, 2L, "b".repeat(64), null,
                "2026-08-27T00:00:00Z", "c".repeat(64));
    }

    private static Path exportWith(Path tmp, EvidenceRecordService.Built built) throws Exception {
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);

        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream())
                .thenReturn(new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cmis:name", "minutes.txt");
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AuthenticityReport(REPO, OBJECT, "2026-08-27T00:00:00Z",
                        List.of(new Section("identity", Verdict.REPORTED, body,
                                "These are the attributes AS STORED NOW; nothing here checks "
                                        + "that the source told the truth."))));

        EvidenceRecordService records = mock(EvidenceRecordService.class);
        when(records.latest(anyString())).thenReturn(built);

        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);
        exporter.setEvidenceRecordService(records);
        return exporter.export(REPO, OBJECT, EarkSipExporter.Options.withholdingPersonalData(),
                Files.createDirectories(tmp.resolve("out"))).sip();
    }

    private static Map<String, byte[]> entriesOf(Path zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}
