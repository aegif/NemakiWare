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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.roda_project.commons_ip.utils.METSEnums.CreatorType;
import org.roda_project.commons_ip2.model.IPAgent;
import org.roda_project.commons_ip2.model.IPAgentNoteTypeEnum;
import org.roda_project.commons_ip2.model.IPContentInformationType;
import org.roda_project.commons_ip2.model.IPContentType;
import org.roda_project.commons_ip2.model.IPFile;
import org.roda_project.commons_ip2.model.IPRepresentation;
import org.roda_project.commons_ip2.model.SIP;
import org.roda_project.commons_ip2.model.impl.eark.EARKSIP;
import org.roda_project.commons_ip2.model.impl.eark.out.writers.factory.ZipWriteStrategyFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CSIP 2.2.0 package is rejected by commons-ip's <i>v1</i> parser, and that is why the
 * receiving system has to be pointed at the right plugin (P3-4 §10).
 *
 * <h2>Why this is pinned as a test</h2>
 *
 * <p>RODA 6.3.0 ships two E-ARK ingest plugins. {@code EARKSIP2ToAIPPlugin} calls
 * {@code commons_ip2}'s parser and ingests our SIP; {@code EARKSIPToAIPPlugin} calls
 * {@code commons_ip}'s — the legacy v1 API, which lives inside the <i>same</i>
 * {@code commons-ip2} jar — and refuses it. Pointing a job at the second one and reading the
 * refusal as "RODA cannot ingest E-ARK SIPs" is exactly the mistake this project made on
 * 2026-08-26, and it took a live container plus three reviews to catch.
 *
 * <p>So the fact is pinned here instead: it reproduces in this process, in a few hundred
 * milliseconds, with no container. The mechanism is a profile-version gap, not a defect —
 * METS 1.11 declares {@code agent/note} as {@code xsd:string}, a <b>simple type</b>, while the
 * DILCIS-patched METS 1.12 that CSIP 2.2.0 uses makes it a complexType carrying a required
 * {@code csip:NOTETYPE}. v1 validates against the former with JAXB and fails on the attribute.
 *
 * <p>What this establishes, and what it does not: it establishes that a receiver reading our
 * package with commons-ip v1 will refuse it. It says <b>nothing</b> about whether any particular
 * archive accepts the package — that is measured against a live receiver, and only RODA 6.3.0
 * has been.
 *
 * <p>If this test ever fails, the world changed in a way worth knowing about: either the legacy
 * parser learned METS 1.12, or our generator stopped emitting CSIP 2.2.0. Read it, do not
 * silence it.
 */
class LegacyEarkParserRejectsOurSipTest {

    /** The same version {@link EarkSipExporter} generates with. */
    private static final String CSIP_VERSION = EarkSipExporter.CSIP_VERSION;

    @Test
    @DisplayName("commons-ip v1 refuses our CSIP package; commons-ip2 accepts the same bytes")
    void theLegacyParserRefusesWhatTheCurrentOneAccepts(@TempDir Path tmp) throws Exception {
        Path built = buildSip(tmp);

        // The v1 API: the one RODA's EARKSIPToAIPPlugin calls.
        org.roda_project.commons_ip.model.SIP viaV1 =
                org.roda_project.commons_ip.model.impl.eark.EARKSIP.parse(built,
                        Files.createDirectories(tmp.resolve("v1")));
        org.roda_project.commons_ip.model.ValidationReport v1Report = viaV1.getValidationReport();

        assertFalse(v1Report.isValid(),
                "commons-ip v1 now accepts a CSIP " + CSIP_VERSION + " package. That is a real "
                        + "change: the design (p3-4 §10) explains a receiver's refusal by this "
                        + "profile-version gap, and the explanation no longer holds.");
        String v1Text = String.valueOf(v1Report.getValidationEntries());
        assertTrue(v1Text.contains("NOTETYPE"),
                "commons-ip v1 still refuses the package, but no longer over csip:NOTETYPE. The "
                        + "recorded diagnosis is stale; re-measure before quoting it: " + v1Text);

        // The v2 API: the one RODA's EARKSIP2ToAIPPlugin calls, and the one that ingested this
        // package on a live RODA 6.3.0. Without this half, the assertion above would also pass
        // if our generator had simply started emitting a broken package.
        SIP viaV2 = new EARKSIP().parse(built, Files.createDirectories(tmp.resolve("v2")));
        assertTrue(viaV2.getValidationReport().isValid(),
                "commons-ip2 no longer accepts our own package, so the refusal above is not a "
                        + "profile gap — it is our package: " + viaV2.getValidationReport());
    }

    private static Path buildSip(Path tmp) throws Exception {
        Path payload = Files.writeString(Files.createDirectories(tmp.resolve("in"))
                .resolve("hello.txt"), "hello", StandardCharsets.UTF_8);

        SIP sip = new EARKSIP("nemaki-profile-gap-1", IPContentType.getMIXED(),
                IPContentInformationType.getMIXED(), CSIP_VERSION);
        // commons-ip2 writes agent/note unconditionally — createMETSAgent has no branch — so
        // there is no way to produce a note-less METS through this generator.
        sip.addAgent(new IPAgent("NemakiWare", "CREATOR", null, CreatorType.OTHER, "SOFTWARE",
                "3.4.0", IPAgentNoteTypeEnum.SOFTWARE_VERSION));
        IPRepresentation representation = new IPRepresentation("rep1");
        representation.addFile(new IPFile(payload));
        sip.addRepresentation(representation);

        return sip.build(new ZipWriteStrategyFactory()
                .create(Files.createDirectories(tmp.resolve("out"))), false);
    }
}
