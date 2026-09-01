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
 * and the exporter wrote nothing. Adding one and re-running showed why that mattered: exported
 * through {@code addPreservationMetadata}, RODA 6.3.0 failed the <b>whole ingest</b>
 * ({@code Failed to load PREMIS}, transaction rolled back) while the identical package without
 * the record succeeded, and the same record via {@code addOtherMetadata} succeeded.
 *
 * <p><b>The directory was never the cause.</b> {@code addPreservationMetadata} declares the file
 * in {@code <amdSec><digiprovMD>}, and CSIP32 makes that the PREMIS slot ("it is mandatory to
 * include one {@code <digiprovMD>} element for each piece of PREMIS metadata"). commons-ip2's
 * parser collects {@code digiprovMD} into {@code SIP.getPreservationMetadata()}, and RODA pushes
 * every entry of that list through {@code PremisV3Utils.binaryToGenericPremis}. The folder name
 * follows the same call. CSIPSTR6 is only SHOULD about the folder and CSIPSTR8 names
 * {@code other} as a MAY-level <i>example</i>, so <b>neither placement breaks CSIP at the folder
 * level</b> — the defect was declaring a DER blob in the PREMIS slot.
 *
 * <p>So this locks three things, weakest first: the file is at
 * {@link ErsFormat#CSIP_LOCATION}; it is not under {@code metadata/preservation}; and — the one
 * that matters — <b>it does not come back as preservation metadata</b>, which is the list a
 * receiver acts on.
 *
 * <p>What this does NOT establish: whether a receiver <b>keeps</b> the record. That is measured
 * against a live receiver and recorded in p3-4 §10 (RODA 6.3.0 keeps it, filed under
 * {@code metadata/descriptive/}).
 *
 * <p>The DER here is a stub. Nothing about the bytes is asserted; the subject is placement.
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
        // the path assertion above first (measured, cg16). So this is a guard against a future
        // API or library change that decouples the two -- not a lock with its own isolated
        // control. It is here because the path is the symptom and this is the cause, and a
        // reader who only sees the path assertions will fix the wrong thing.
        //
        // Read it back the way a receiver does, rather than pattern-matching the raw METS.
        org.roda_project.commons_ip2.model.SIP readBack =
                new org.roda_project.commons_ip2.model.impl.eark.EARKSIP()
                        .parse(sip, Files.createDirectories(tmp.resolve("readback")));

        assertTrue(fileNames(readBack.getPreservationMetadata())
                        .noneMatch(name -> name.equals(ErsFormat.CHOSEN.fileName())),
                "the evidence record comes back as PRESERVATION metadata -- i.e. declared inside "
                        + "<digiprovMD>, the slot CSIP32 names for PREMIS. A receiver that reads "
                        + "that list as PREMIS (RODA 6.3.0 does) rejects the WHOLE package over "
                        + "it, and the file path can look perfectly correct while this is wrong: "
                        + fileNames(readBack.getPreservationMetadata()).toList());
        assertTrue(fileNames(readBack.getOtherMetadata())
                        .anyMatch(name -> name.equals(ErsFormat.CHOSEN.fileName())),
                "the evidence record does not come back as OTHER metadata, so the assertion "
                        + "above passes for the wrong reason -- it may not be declared at all: "
                        + fileNames(readBack.getOtherMetadata()).toList());
    }

    private static java.util.stream.Stream<String> fileNames(
            List<org.roda_project.commons_ip2.model.IPMetadata> metadata) {
        return metadata.stream().map(m -> m.getMetadata().getFileName());
    }

    @Test
    @DisplayName("the read-back oracle really does tell the two declarations apart")
    void theOracleDiscriminates(@TempDir Path tmp) throws Exception {
        // The assertion above cannot be isolated by breaking the product: commons-ip2's
        // addPreservationMetadata moves the FILE as well as the declaration, so any sabotage
        // trips the path assertion first. That leaves an open question the path assertions
        // cannot answer -- does reading back through getPreservationMetadata() / getOtherMetadata()
        // actually DISTINGUISH the two calls, or would it say "other" either way?
        //
        // So ask the library directly, with no product code involved. If this ever fails, the
        // assertion above has stopped meaning anything and would pass a broken package.
        assertTrue(readBackOtherNames(tmp.resolve("viaOther"), true)
                        .contains(ErsFormat.CHOSEN.fileName()),
                "a record added with addOtherMetadata does not read back as OTHER metadata");
        assertTrue(readBack(tmp.resolve("viaPreservation"), false, false).isEmpty(),
                "a record added with addPreservationMetadata ALSO reads back as OTHER metadata, "
                        + "so the assertion in the test above cannot tell the two apart and is "
                        + "not guarding anything");
        // And it has to land SOMEWHERE: an oracle that reported "not in OTHER" because the
        // record vanished entirely would pass the line above while seeing nothing.
        assertTrue(readBack(tmp.resolve("viaPreservation2"), false, true)
                        .contains(ErsFormat.CHOSEN.fileName()),
                "a record added with addPreservationMetadata does not read back as PRESERVATION "
                        + "metadata either, so the check above passes because the oracle sees "
                        + "nothing at all -- not because it discriminates");
    }

    private static List<String> readBackOtherNames(Path work, boolean asOther) throws Exception {
        return readBack(work, asOther, false);
    }

    /**
     * Builds a bare SIP with the record added one way or the other, and reads one list back.
     *
     * @param asOther which call to add it with
     * @param preservationSide which list to read back: preservation when true, other when false
     */
    private static List<String> readBack(Path work, boolean asOther, boolean preservationSide)
            throws Exception {
        Path record = Files.write(Files.createDirectories(work.resolve("in"))
                .resolve(ErsFormat.CHOSEN.fileName()), new byte[] {1, 2, 3});
        org.roda_project.commons_ip2.model.SIP sip =
                new org.roda_project.commons_ip2.model.impl.eark.EARKSIP("oracle-probe",
                        org.roda_project.commons_ip2.model.IPContentType.getMIXED(),
                        org.roda_project.commons_ip2.model.IPContentInformationType.getMIXED(),
                        EarkSipExporter.CSIP_VERSION);
        org.roda_project.commons_ip2.model.IPMetadata metadata =
                new org.roda_project.commons_ip2.model.IPMetadata(
                        new org.roda_project.commons_ip2.model.IPFile(record),
                        new org.roda_project.commons_ip2.model.MetadataType(
                                org.roda_project.commons_ip2.model.MetadataType
                                        .MetadataTypeEnum.OTHER));
        if (asOther) {
            sip.addOtherMetadata(metadata);
        } else {
            sip.addPreservationMetadata(metadata);
        }
        Path built = sip.build(new org.roda_project.commons_ip2.model.impl.eark.out.writers
                .factory.ZipWriteStrategyFactory()
                .create(Files.createDirectories(work.resolve("out"))), false);
        org.roda_project.commons_ip2.model.SIP back =
                new org.roda_project.commons_ip2.model.impl.eark.EARKSIP()
                        .parse(built, Files.createDirectories(work.resolve("read")));
        return fileNames(preservationSide ? back.getPreservationMetadata()
                : back.getOtherMetadata()).toList();
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
        return exporter.export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(),
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
