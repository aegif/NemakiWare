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
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.roda_project.commons_ip2.model.SIP;
import org.roda_project.commons_ip2.model.impl.eark.EARKSIP;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The package this product exports is refused by commons-ip's <i>v1</i> parser and accepted by
 * its v2 one — which is why a receiver has to be pointed at the right plugin (P3-4 §10).
 *
 * <h2>Why this is pinned as a test</h2>
 *
 * <p>RODA 6.3.0 ships two E-ARK ingest plugins. {@code EARKSIP2ToAIPPlugin} calls
 * {@code commons_ip2}'s parser and produced an AIP from our SIP; {@code EARKSIPToAIPPlugin} calls
 * {@code commons_ip}'s — the legacy v1 API, which lives inside the <i>same</i>
 * {@code commons-ip2} jar — and refused the same bytes. Pointing a job at the second one and
 * reading the refusal as "RODA cannot ingest E-ARK SIPs" is exactly the mistake this project
 * made on 2026-08-26; it took a live container and three reviews to catch.
 *
 * <p>The mechanism is a profile-version gap, not a defect: METS 1.11 declares
 * {@code agent/note} as {@code xsd:string}, a <b>simple type</b>, while the DILCIS-patched METS
 * 1.12 that CSIP 2.2.0 uses makes it a complexType carrying a required {@code csip:NOTETYPE}.
 * v1 validates against the former with JAXB and fails on the attribute.
 *
 * <h2>What this pins, stated exactly — because the original mistake was being loose here</h2>
 *
 * <p>The package under test is built by <b>{@link EarkSipExporter#export} itself</b>, over the
 * same mocked content service the exporter's own tests use — so the bytes the parsers see are
 * the product's output, not a fixture's. An earlier draft hand-built a minimal SIP with
 * commons-ip2 directly while claiming to cover "our generator"; it did not.
 *
 * <p><b>But that coupling is thin, and it was measured rather than assumed.</b> Sabotaging the
 * exporter three ways:
 *
 * <ul>
 *   <li>{@code CSIP_VERSION} 2.2.0 → 2.1.0 — this test stays <b>green</b></li>
 *   <li>drop {@code sip.addRepresentation(...)} — stays <b>green</b></li>
 *   <li>drop {@code sip.addAgent(...)} — this test errors, but with
 *       {@code ExportRefusedException}: the exporter's own validator refused the package before
 *       either parser saw it. That is {@code EarkSipExporterTest}'s job, not this one's.</li>
 * </ul>
 *
 * <p>So <b>this is not a guard on the exporter.</b> Its subject is the v1/v2 divergence, checked
 * against bytes the product really emits. Do not cite it as coverage of what the exporter
 * produces.
 *
 * <p>The two parsers are <b>both from the commons-ip2 on this project's own classpath</b> —
 * currently 2.12.0. RODA 6.3.0 bundles <b>2.11.3</b>. So this corroborates the mechanism against
 * the version we build with; it does <b>not</b> pin RODA's runtime. Saying more would repeat the
 * error this test exists to prevent: the original write-up ran {@code commons_ip2}'s validator,
 * saw it pass, and reported that as RODA's answer, when RODA never calls that API for this route.
 *
 * <p>That RODA's own 2.11.3 behaves the same way was measured separately, once, by running its
 * v1 parser out of the RODA fat jar's {@code BOOT-INF/lib}: same refusal, same METS line 6, same
 * {@code csip:NOTETYPE} message. That run is recorded in
 * {@code docs/design/p3-4-custody-transfer.md} §10 and is <b>not</b> re-run here — it needs jars
 * this build does not depend on.
 *
 * <p>And nothing here says whether any archive accepts the package. That is measured against a
 * live receiver, and only RODA 6.3.0 has been.
 *
 * <h2>When this fails</h2>
 *
 * <p>Three possibilities, and none of them is "delete the test":
 *
 * <ul>
 *   <li><b>It stops compiling</b> — the likeliest one. {@code org.roda_project.commons_ip} is a
 *       legacy package carried inside the commons-ip2 artifact, and a future bump may drop it.
 *       That means our jar diverged from the one RODA ships, so the recorded mechanism can no
 *       longer be corroborated here; re-measure against the receiver.</li>
 *   <li><b>The v1 half fails</b> — the legacy parser learned METS 1.12. Receivers on it may now
 *       accept CSIP 2.x, which would change the design's advice.</li>
 *   <li><b>The v2 half fails</b> — commons-ip2 stopped accepting what we emit. Note the
 *       exporter validates its own output and refuses to return a rejected package, so this
 *       half failing means the two disagree, which is worth looking at directly.</li>
 * </ul>
 */
class LegacyEarkParserRejectsOurSipTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "doc-1";

    @Test
    @DisplayName("commons-ip v1 refuses the package we export; commons-ip2 accepts the same bytes")
    void theLegacyParserRefusesWhatTheCurrentOneAccepts(@TempDir Path tmp) throws Exception {
        Path built = exportedSip(tmp);

        // The v1 API: the one RODA's EARKSIPToAIPPlugin calls.
        org.roda_project.commons_ip.model.SIP viaV1 =
                org.roda_project.commons_ip.model.impl.eark.EARKSIP.parse(built,
                        Files.createDirectories(tmp.resolve("v1")));
        org.roda_project.commons_ip.model.ValidationReport v1Report = viaV1.getValidationReport();

        assertFalse(v1Report.isValid(),
                "commons-ip v1 now accepts a CSIP " + EarkSipExporter.CSIP_VERSION + " package. "
                        + "That is a real change: the design (p3-4 §10) explains a receiver's "
                        + "refusal by this profile-version gap, and the explanation no longer "
                        + "holds.");
        // The token, not the sentence: the recorded diagnostic came back localised.
        String v1Text = String.valueOf(v1Report.getValidationEntries());
        assertTrue(v1Text.contains("NOTETYPE"),
                "commons-ip v1 still refuses the package, but no longer over csip:NOTETYPE. The "
                        + "recorded diagnosis is stale; re-measure before quoting it: " + v1Text);

        // The v2 API: the one RODA's EARKSIP2ToAIPPlugin calls, and the one that ingested this
        // product's SIP on a live RODA 6.3.0. Without this half, the assertion above would also
        // pass if our exporter had simply started emitting a broken package.
        SIP viaV2 = new EARKSIP().parse(built, Files.createDirectories(tmp.resolve("v2")));
        assertTrue(viaV2.getValidationReport().isValid(),
                "commons-ip2 no longer accepts the package this product exports, so the refusal "
                        + "above is not a profile gap — it is our package: "
                        + viaV2.getValidationReport());
    }

    /**
     * A package from the real exporter, over the same mocked content service
     * {@code EarkSipExporterTest} uses. Nothing about the parsers is involved in building it.
     */
    private static Path exportedSip(Path tmp) throws Exception {
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

        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);
        return exporter.export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(),
                Files.createDirectories(tmp.resolve("export"))).sip();
    }
}
