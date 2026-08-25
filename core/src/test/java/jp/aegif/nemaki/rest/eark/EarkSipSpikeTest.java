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

import org.roda_project.commons_ip2.model.IPContentInformationType;
import org.roda_project.commons_ip2.model.IPContentType;
import org.roda_project.commons_ip2.model.IPFile;
import org.roda_project.commons_ip2.model.IPRepresentation;
import org.roda_project.commons_ip2.model.SIP;
import org.roda_project.commons_ip2.model.impl.eark.EARKSIP;
import org.roda_project.commons_ip2.model.impl.eark.out.writers.factory.ZipWriteStrategyFactory;
import org.roda_project.commons_ip2.validator.EARKSIPValidator;
import org.roda_project.commons_ip2.validator.reporter.ValidationReportOutputJson;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One document goes out as an E-ARK SIP and the reference validator accepts it (P3-1).
 *
 * <h2>Why this exists before any exporter does</h2>
 *
 * <p>The roadmap's acceptance criterion for P3-1 is not "we generate METS" — it is "the output
 * passes the validator, pinned as a CI test". Everything else in the increment is only worth
 * building if that is reachable in this build, on this JVM, from a plain unit test. So this runs
 * first, with nothing of ours in it: build the smallest package the specification allows, hand it
 * to {@code EARKSIPValidator}, and see.
 *
 * <p>It also pins the two facts that were easy to get wrong and were got wrong:
 *
 * <ul>
 *   <li>the artifact is {@code org.roda-community:commons-ip2} on Maven Central. The group
 *       {@code org.roda-project} is a 404 there, and the project's README points at GitHub
 *       Packages, which needs a token.</li>
 *   <li>the validator ships INSIDE the library, so "validated in CI" needs no sidecar and no
 *       10 MB CLI jar — {@code EARKSIPValidator} runs in this process.</li>
 * </ul>
 *
 * <p>What this does NOT establish: nothing here carries NemakiWare's own metadata yet. Passing
 * with an empty descriptive-metadata section says the container is well formed, not that a
 * record's provenance survived the trip.
 */
class EarkSipSpikeTest {

    /** The CSIP version this project targets. The validator also accepts 2.0.4 and 2.1.0. */
    private static final String CSIP_VERSION = "2.2.0";

    @Test
    @DisplayName("a minimal SIP is built and the reference validator accepts it")
    void aMinimalSipPassesTheReferenceValidator(@TempDir Path tmp) throws Exception {
        Path payload = tmp.resolve("hello.txt");
        Files.writeString(payload, "hello", StandardCharsets.UTF_8);

        // The VERSION argument matters. Without it EARKSIP defaults to 2.1.0 and writes
        // mets/@PROFILE = ...E-ARK-SIP-v2-1-0.xml, which the 2.2.0 validator rejects outright
        // (SIP2, MUST). The version has to be the same one the validator is given.
        SIP sip = new EARKSIP("nemaki-spike-1", IPContentType.getMIXED(),
                IPContentInformationType.getMIXED(), CSIP_VERSION);
        sip.addCreatorSoftwareAgent("NemakiWare", "3.4");
        // SIP15 (MUST): a submitting agent. The software agent above does NOT satisfy it —
        // addCreatorSoftwareAgent writes TYPE="OTHER" OTHERTYPE="SOFTWARE", and the rule wants
        // ROLE="CREATOR" with a TYPE that is not OTHER. Who submitted has to be a who.
        sip.addAgent(new org.roda_project.commons_ip2.model.IPAgent("NemakiWare deployment",
                "CREATOR", null,
                org.roda_project.commons_ip.utils.METSEnums.CreatorType.ORGANIZATION, null,
                "nemakiware", org.roda_project.commons_ip2.model.IPAgentNoteTypeEnum.IDENTIFICATIONCODE));

        IPRepresentation representation = new IPRepresentation("rep1");
        representation.addFile(new IPFile(payload));
        sip.addRepresentation(representation);

        Path buildDir = Files.createDirectories(tmp.resolve("build"));
        Path zip = sip.build(new ZipWriteStrategyFactory().create(buildDir), "nemaki-spike-1");

        assertNotNull(zip, "the SIP was not written");
        assertTrue(Files.exists(zip), "the SIP path does not exist: " + zip);

        ByteArrayOutputStream reportOut = new ByteArrayOutputStream();
        EARKSIPValidator validator = new EARKSIPValidator(
                new ValidationReportOutputJson(zip, reportOut), CSIP_VERSION);
        boolean valid = validator.validate(CSIP_VERSION);

        assertTrue(valid, "the reference validator rejected our SIP. Report:\n"
                + reportOut.toString(StandardCharsets.UTF_8));
    }
}
