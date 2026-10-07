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
import jp.aegif.nemaki.evidence.AuthenticityReportAssembler;
import jp.aegif.nemaki.evidence.EvidenceBundle;
import jp.aegif.nemaki.evidence.EvidenceBundleAssembler;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The product's export entry point, on the two 9-6 findings about what it hands over.
 *
 * <p>The assembler and the assurance enum have their own locks; both were green while the
 * entry point shipped a package saying "no evidence" for a ledger it could not reach, and
 * handed an RFC 3161 token to a caller who required an OpenTimestamps proof. These tests go
 * through {@link EarkSipExporter#export} with the collaborators the real one has.
 */
class TheExportRefusesWhatItCouldNotReadTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "doc-1";

    private static EarkSipExporter exporterWith(EvidenceBundleAssembler bundles) {
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
        AuthenticityReportAssembler reports = mock(AuthenticityReportAssembler.class);
        when(reports.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AuthenticityReport(REPO, OBJECT, "2026-08-27T00:00:00Z", List.of()));

        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(reports);
        exporter.setBundleAssembler(bundles);
        return exporter;
    }

    /** The one-checkpoint bundle of the layout test, anchored on the rungs given. */
    private static EvidenceBundle anchoredBy(AnchorKind... present) {
        EvidenceBundle base = TheSipLayoutIsWhereCommonsIpPutsItTest.oneBundle();
        Map<AnchorKind, EvidenceBundle.AnchorPart> anchors = new LinkedHashMap<>();
        for (AnchorKind kind : AnchorKind.values()) {
            anchors.put(kind, new EvidenceBundle.AnchorPart(
                    EvidenceBundle.AnchorPart.State.NOT_CONFIGURED, null, "not configured here"));
        }
        for (AnchorKind kind : present) {
            anchors.put(kind, new EvidenceBundle.AnchorPart(
                    EvidenceBundle.AnchorPart.State.PRESENT, new byte[] { 0x30, 0x03 }, null));
        }
        return new EvidenceBundle(base.repositoryId(), base.objectId(), base.versionObjectId(),
                base.statement(), base.entry(), base.inclusionProof(), base.coveringCheckpoint(),
                base.checkpointChain(), base.anchorTargetCheckpoint(), anchors, base.createdAt());
    }

    private static EvidenceBundleAssembler returning(EvidenceBundle bundle) {
        return TheSipLayoutIsWhereCommonsIpPutsItTest.assemblerReturning(bundle);
    }

    private static boolean aSipWasWritten(Path work) throws Exception {
        try (var files = Files.walk(work)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".zip"));
        }
    }

    @Test
    @DisplayName("evidence that could not be read refuses the export — under BEST_AVAILABLE too — rather than shipping 'no evidence'")
    void anUnreadableLedgerRefusesTheExport(@TempDir Path tmp) throws Exception {
        EvidenceBundleAssembler.EvidenceNotReadable failure =
                new EvidenceBundleAssembler.EvidenceNotReadable(OBJECT,
                        List.of("the ledger entries for doc-1 could not be read: ledger is away"));
        EarkSipExporter exporter = exporterWith(new EvidenceBundleAssembler() {
            @Override
            public EvidenceBundle assemble(String repositoryId, String objectId,
                    String versionObjectId) {
                throw failure;
            }
        });
        Path work = Files.createDirectories(tmp.resolve("work"));

        EarkSipExporter.ExportRefusedException refused = assertThrows(
                EarkSipExporter.ExportRefusedException.class,
                () -> exporter.export(REPO, OBJECT,
                        EarkSipExporter.Options.withoutInternalOnlyProperties(), work),
                "the ledger could not be read and a package was built anyway. Its profile.json "
                        + "and manifest then say the record has no evidence — a claim about the "
                        + "record that nobody established, and one a receiver cannot tell from "
                        + "the truth (9-6 review, P1)");
        assertTrue(refused.getMessage().contains("ledger is away"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no package was built"), refused.getMessage());
        assertTrue(!aSipWasWritten(work), "a SIP was written under the work directory anyway");
    }

    @Test
    @DisplayName("an RFC 3161 token does not satisfy a request for an OpenTimestamps proof — and does satisfy a request for itself")
    void anRfc3161TokenDoesNotSatisfyARequestForAnOtsProof(@TempDir Path tmp) throws Exception {
        EvidenceBundle rfc3161Only = anchoredBy(AnchorKind.RFC3161_TSA);

        EarkSipExporter.AssuranceNotMetException refused = assertThrows(
                EarkSipExporter.AssuranceNotMetException.class,
                () -> exporterWith(returning(rfc3161Only)).export(REPO, OBJECT,
                        new EarkSipExporter.Options(false, "Acme Ltd",
                                EarkSipExporter.Assurance.REQUIRE_ANCHORED_OTS),
                        Files.createDirectories(tmp.resolve("ots"))),
                "the record has no OpenTimestamps proof and the caller got a package; the "
                        + "profiles were ranked in one list with P3 above P4, so a token "
                        + "satisfied a request for a proof it is not (9-6 review, P1)");
        assertEquals(List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1", "ANCHORED_CHECKPOINT_V1",
                "TRUSTED_RFC3161_V1"), refused.supported(),
                "the refusal names every profile the record does support, so the caller can "
                        + "ask for one of those");

        // Not over-refusing: the same record satisfies a request for what it has.
        Path sip = exporterWith(returning(rfc3161Only)).export(REPO, OBJECT,
                new EarkSipExporter.Options(false, "Acme Ltd",
                        EarkSipExporter.Assurance.REQUIRE_TRUSTED_RFC3161),
                Files.createDirectories(tmp.resolve("rfc"))).sip();
        assertTrue(Files.isRegularFile(sip), "no package for a request the record does satisfy");
    }

    @Test
    @DisplayName("an OpenTimestamps proof alone does not satisfy a request for an anchored checkpoint")
    void anOtsProofAloneDoesNotSatisfyARequestForAnAnchoredCheckpoint(@TempDir Path tmp) {
        EarkSipExporter.AssuranceNotMetException refused = assertThrows(
                EarkSipExporter.AssuranceNotMetException.class,
                () -> exporterWith(returning(anchoredBy(AnchorKind.OPENTIMESTAMPS))).export(REPO,
                        OBJECT, new EarkSipExporter.Options(false, "Acme Ltd",
                                EarkSipExporter.Assurance.REQUIRE_ANCHORED_CHECKPOINT),
                        Files.createDirectories(tmp.resolve("ots-only"))),
                "spec §11 reads RFC 3161 material only, so a verifier cannot reach "
                        + "ANCHORED_CHECKPOINT_V1 on an OTS rung alone; this record used to be "
                        + "called ANCHORED_OTS_V1, which ranked above P2");
        assertEquals(List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1"), refused.supported());
    }
}
