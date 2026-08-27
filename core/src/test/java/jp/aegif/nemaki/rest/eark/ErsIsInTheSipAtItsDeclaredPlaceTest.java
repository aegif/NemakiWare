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
 * <p>The first RODA run (p3-4 §10) submitted a package with no evidence record in it — that
 * node had no confirmed RFC 3161 anchor, so {@code EvidenceRecordService.latest} returned absent
 * and the exporter wrote nothing. Adding one and re-running showed why that mattered: with the
 * record at {@code metadata/preservation/}, RODA 6.3.0 failed the <b>whole ingest</b>
 * ({@code Failed to load PREMIS}, transaction rolled back) while the identical package without
 * it succeeded. CSIP's {@code metadata/preservation} is where PREMIS goes; a DER blob there is
 * our misreading, not the receiver being strict.
 *
 * <p>So the lock is now two-sided: the record must be at {@link ErsFormat#CSIP_LOCATION}
 * ({@code metadata/other}) and must <b>not</b> be in {@code metadata/preservation}. The second
 * half is the one that matters — putting it back beside the PREMIS is a change that makes a
 * receiver reject everything, and nothing else in the build would notice.
 *
 * <p>What this does NOT establish: whether a receiver <b>keeps</b> the record at
 * {@code metadata/other}. That is measured against a live receiver, and is recorded in p3-4 §10.
 *
 * <p>The DER here is a stub. Nothing about the bytes is asserted; the subject is the path.
 */
class ErsIsInTheSipAtItsDeclaredPlaceTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "doc-1";

    @Test
    @DisplayName("a present evidence record lands at metadata/other/, NOT beside the PREMIS")
    void theEvidenceRecordIsWhereTheFormatSaysItIs(@TempDir Path tmp) throws Exception {
        byte[] stubDer = "not a real ERS; this test is about the path".getBytes(StandardCharsets.UTF_8);
        Path sip = exportWith(tmp, new EvidenceRecordService.Built(stubDer, checkpoint(), null));

        Map<String, byte[]> entries = entriesOf(sip);
        String expected = ErsFormat.CSIP_LOCATION + "/" + ErsFormat.CHOSEN.fileName();
        assertNotNull(entries.keySet().stream()
                        .filter(name -> name.endsWith(expected))
                        .findFirst()
                        .orElse(null),
                "the evidence record is not at " + expected + ", so a receiver looking where "
                        + "ErsFormat.CSIP_LOCATION says to look will not find it: "
                        + entries.keySet());

        assertTrue(entries.keySet().stream()
                        .noneMatch(name -> name.contains("metadata/preservation/")
                                && name.endsWith(ErsFormat.CHOSEN.fileName())),
                "the evidence record is back under metadata/preservation: " + entries.keySet());
        // The PREMIS is still there, and still alone: this is what makes the line above a claim
        // about the record's placement rather than about the directory having vanished.
        assertTrue(entries.keySet().stream()
                        .anyMatch(name -> name.endsWith("metadata/preservation/premis.xml")),
                "there is no PREMIS in metadata/preservation any more, so the assertion above "
                        + "passes for the wrong reason: " + entries.keySet());

        // THE CAUSAL VARIABLE, asserted directly. The directory is a side effect; what a
        // receiver acts on is the METS section. CSIP32 makes <digiprovMD> the PREMIS slot, and
        // RODA 6.3.0 reads digiprovMD into SIP.getPreservationMetadata() and pushes every entry
        // through PremisV3Utils.binaryToGenericPremis -- so a DER declared there rolls the whole
        // transaction back.
        //
        // HONESTLY: no product-side sabotage isolates this assertion today. Through commons-ip2's
        // public API, addPreservationMetadata moves the FILE as well as the declaration, so the
        // attempt to declare in digiprovMD while leaving the path at metadata/other was caught by
        // the path assertion above (measured, cg16). This is therefore a guard against a future
        // API or library change that decouples the two -- not a lock with its own measured
        // control. It is here because the path is the symptom and this is the cause, and a
        // reader who only sees the path assertions will fix the wrong thing.
        String mets = new String(entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/METS.xml"))
                .filter(e -> !e.getKey().contains("/representations/"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        int record = mets.indexOf(ErsFormat.CHOSEN.fileName());
        assertTrue(record >= 0, "the evidence record is not declared in the root METS: " + mets);
        // The element names carry no namespace prefix in what commons-ip2 writes, but do not
        // rely on that: match an optional one.
        int lastDigiprov = lastOpeningTagBefore(mets, "digiprovMD", record);
        int lastDmdSec = lastOpeningTagBefore(mets, "dmdSec", record);
        assertTrue(lastDmdSec > lastDigiprov,
                "the evidence record is declared inside <digiprovMD>, which CSIP32 reserves for "
                        + "PREMIS. A receiver that parses digiprovMD as PREMIS -- RODA 6.3.0 "
                        + "does -- rejects the WHOLE package over it, and the file path can look "
                        + "perfectly correct while this is wrong. METS was: " + mets);
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

    /** Offset of the last {@code <name} or {@code <prefix:name} opening tag before {@code before}. */
    private static int lastOpeningTagBefore(String xml, String name, int before) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(?:[A-Za-z_][\\w.-]*:)?" + name + "[\\s>]")
                .matcher(xml.substring(0, before));
        int last = -1;
        while (m.find()) {
            last = m.start();
        }
        return last;
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
