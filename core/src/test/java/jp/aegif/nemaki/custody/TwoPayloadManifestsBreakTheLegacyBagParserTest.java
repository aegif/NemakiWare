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
package jp.aegif.nemaki.custody;

import gov.loc.repository.bagit.creator.BagCreator;
import gov.loc.repository.bagit.domain.Metadata;
import gov.loc.repository.bagit.hash.StandardSupportedAlgorithms;
import gov.loc.repository.bagit.hash.SupportedAlgorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reason {@link BagItTransferPackager} writes one payload manifest, pinned as a check rather
 * than as a sentence in a design document.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code BagItTransferPackagerTest} pins the <i>decision</i> — exactly one payload manifest
 * comes out. It cannot tell anyone why, and it cannot tell anyone when the reason stops holding.
 * Since RFC 8493 §2.1.3 allows several manifests and a second one carries a receiver-verified
 * SHA-256 binding this product's evidence wants, "the constraint has lifted" is a fact worth
 * learning about rather than rediscovering by standing up a container.
 *
 * <p>So this pins the mechanism: commons-ip's <b>v1</b> {@code BagitSIP.parse} — the entry point
 * RODA 6.3.0's {@code BagitToAIPPlugin} calls — iterates {@code Bag.getPayLoadManifests()} and
 * adds an {@code IPFile} per entry of each manifest's {@code getFileToChecksumMap()}, with no
 * dedupe. Two manifests therefore yield the payload twice, and RODA's
 * {@code BagitToAIPPluginUtils} hands each one to {@code ModelService.createFile} — hence the
 * "Binary already exists" rollback measured on 2026-08-26.
 *
 * <h2>What this pins, stated exactly</h2>
 *
 * <p>The parser here is the one on <b>this project's own classpath</b> (commons-ip2 2.12.0's
 * legacy package). RODA 6.3.0 bundles <b>2.11.3</b>. This corroborates the mechanism against the
 * version we build with; the receiver behaviour itself was measured live and is recorded in
 * {@code docs/design/p3-4-custody-transfer.md} §10. It does not pin RODA's runtime, and it says
 * nothing about Archivematica, which reads bags with bagit-python and has not been measured.
 *
 * <p>It also does not assert that RODA fails — only that the parse yields duplicates. The step
 * from "duplicate {@code IPFile}" to "rolled back transaction" is RODA's, and belongs to the
 * live measurement.
 *
 * <h2>When this fails</h2>
 *
 * <p>If the one-manifest count comes back as two, the library stopped duplicating and the
 * accommodation in {@link BagItTransferPackager} can be revisited — <b>after</b> re-measuring
 * against the receiver, since our version and RODA's may have diverged. If it stops compiling,
 * the legacy {@code org.roda_project.commons_ip} package left the commons-ip2 artifact; that
 * divergence is itself the news.
 */
class TwoPayloadManifestsBreakTheLegacyBagParserTest {

    @Test
    @DisplayName("the legacy bag parser yields the payload once per manifest, not once per file")
    void aSecondManifestDuplicatesThePayload(@TempDir Path tmp) throws Exception {
        // The one-manifest side is THE PRODUCT'S OWN BAG, so this compares what we actually ship
        // against the shape we backed away from.
        Path sip = Files.writeString(Files.createDirectories(tmp.resolve("in")).resolve("sip.zip"),
                "one payload, however many manifests", StandardCharsets.UTF_8);
        Path shipped = BagItTransferPackager.bag(sip,
                Files.createDirectories(tmp.resolve("shipped")), "sub-manifest-probe",
                "a".repeat(64)).zippedBag();

        int withOne = payloadFilesSeenByLegacyParser(shipped, tmp.resolve("p1"));
        int withTwo = payloadFilesSeenByLegacyParser(
                twoManifestBag(tmp.resolve("two"), sip), tmp.resolve("p2"));

        assertEquals(1, withOne,
                "the bag this product ships no longer yields one file through the legacy "
                        + "parser; the baseline for the comparison below is gone");
        assertEquals(2, withTwo,
                "the legacy bag parser no longer duplicates the payload per manifest. That is "
                        + "the whole reason BagItTransferPackager writes a single payload "
                        + "manifest (docs/design/p3-4-custody-transfer.md §10) — re-measure "
                        + "against the actual receiver before writing two again, because this "
                        + "build's commons-ip2 and RODA's may have diverged");
    }

    /** The same bag with a second payload manifest — what the packager used to write. */
    private static Path twoManifestBag(Path workDir, Path sip) throws Exception {
        Path bagRoot = Files.createDirectories(workDir.resolve("bag"));
        Files.copy(sip, bagRoot.resolve(sip.getFileName().toString()));

        Metadata metadata = new Metadata();
        metadata.add("External-Identifier", "sub-manifest-probe");
        BagCreator.bagInPlace(bagRoot, List.<SupportedAlgorithm>of(
                StandardSupportedAlgorithms.SHA512, StandardSupportedAlgorithms.SHA256),
                false, metadata);

        Path zip = workDir.resolve("two-manifests.zip");
        try (java.util.zip.ZipOutputStream out =
                new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
                java.util.stream.Stream<Path> tree = Files.walk(bagRoot)) {
            for (Path file : tree.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(
                        bagRoot.getFileName() + "/" + bagRoot.relativize(file)));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return zip;
    }

    /** Parses a zipped bag with commons-ip v1 and counts the files it reports. */
    private static int payloadFilesSeenByLegacyParser(Path zippedBag, Path unpackTo)
            throws Exception {
        org.roda_project.commons_ip.model.SIP parsed =
                org.roda_project.commons_ip.model.impl.bagit.BagitSIP.parse(zippedBag,
                        Files.createDirectories(unpackTo));
        return parsed.getRepresentations().stream()
                .mapToInt(representation -> representation.getData().size())
                .sum();
    }
}
