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
 * Why the bag this product ships must not be sent to RODA's {@code BagitToAIPPlugin}, pinned as
 * a check rather than as a sentence in a design document.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link BagItTransferPackager} writes two payload manifests — SHA-512 and SHA-256 — which
 * RFC 8493 §2.1.3 allows and which puts the digest this product's evidence chain uses inside a
 * binding a receiver's own bag verification covers. <b>One known receiver cannot read that
 * shape.</b> The bag spent 2026-08-26 as a single manifest for exactly this reason, before the
 * E-ARK route into RODA was measured working and the accommodation stopped making sense.
 *
 * <p>{@code BagItTransferPackagerTest} pins the shipped shape. It cannot say why one receiver
 * chokes on it, and it cannot say when that stops being true. This pins the mechanism:
 * commons-ip's <b>v1</b> {@code BagitSIP.parse} — the entry point {@code BagitToAIPPlugin} calls
 * — iterates {@code Bag.getPayLoadManifests()} and adds an {@code IPFile} per entry of each
 * manifest's {@code getFileToChecksumMap()}, with no dedupe. Two manifests therefore yield the
 * payload twice, and {@code BagitToAIPPluginUtils} hands each one to
 * {@code ModelService.createFile} — hence the "Binary already exists" rollback measured live on
 * 2026-08-26.
 *
 * <h2>What this pins, stated exactly</h2>
 *
 * <p>The parser here is the one on <b>this project's own classpath</b> (commons-ip2 2.12.0's
 * legacy package). RODA 6.3.0 bundles <b>2.11.3</b>. This corroborates the mechanism against the
 * version we build with; the receiver behaviour itself was measured live and is recorded in
 * {@code docs/design/p3-4-custody-transfer.md} §10. It does not pin RODA's runtime, and it says
 * nothing about Archivematica, which reads bags with a BagIt verifier rather than commons-ip and
 * <b>has not been measured with either shape</b>.
 *
 * <p>It also does not assert that RODA fails — only that the parse yields duplicates. The step
 * from "duplicate {@code IPFile}" to "rolled back transaction" is RODA's, and belongs to the
 * live measurement.
 *
 * <h2>When this fails</h2>
 *
 * <p>If the two-manifest count comes back as one, the library stopped duplicating: the warning
 * in {@link BagItTransferPackager#LIMITS} about RODA's bag route can be revisited — <b>after</b>
 * re-measuring against the receiver, since our version and RODA's may have diverged. If it stops
 * compiling, the legacy {@code org.roda_project.commons_ip} package left the commons-ip2
 * artifact; that divergence is itself the news.
 */
class TwoPayloadManifestsBreakTheLegacyBagParserTest {

    @Test
    @DisplayName("the legacy bag parser yields the payload once per manifest, not once per file")
    void aSecondManifestDuplicatesThePayload(@TempDir Path tmp) throws Exception {
        // The two-manifest side is THE PRODUCT'S OWN BAG, so this measures the shape actually
        // shipped, against the single-manifest shape as a control. Without the control, "2" is
        // just a number: it could as easily mean the parser reports two files for any bag.
        Path sip = Files.writeString(Files.createDirectories(tmp.resolve("in")).resolve("sip.zip"),
                "one payload, however many manifests", StandardCharsets.UTF_8);
        Path shipped = BagItTransferPackager.bag(sip,
                Files.createDirectories(tmp.resolve("shipped")), "sub-manifest-probe",
                "a".repeat(64)).zippedBag();

        int shippedShape = payloadFilesSeenByLegacyParser(shipped, tmp.resolve("p1"));
        int withOne = payloadFilesSeenByLegacyParser(
                oneManifestBag(tmp.resolve("one"), sip), tmp.resolve("p2"));

        assertEquals(1, withOne,
                "one payload manifest no longer yields one file; the control for the "
                        + "comparison below is gone, so its count means nothing");
        assertEquals(2, shippedShape,
                "the legacy bag parser no longer duplicates the payload per manifest. That is "
                        + "the whole reason BagItTransferPackager.LIMITS warns against sending "
                        + "this bag to RODA's BagitToAIPPlugin "
                        + "(docs/design/p3-4-custody-transfer.md §10) — re-measure against the "
                        + "actual receiver before dropping the warning, because this build's "
                        + "commons-ip2 and RODA's may have diverged");
    }

    /** The same bag with a single payload manifest — the control, and what shipped for a day. */
    private static Path oneManifestBag(Path workDir, Path sip) throws Exception {
        Path bagRoot = Files.createDirectories(workDir.resolve("bag"));
        Files.copy(sip, bagRoot.resolve(sip.getFileName().toString()));

        Metadata metadata = new Metadata();
        metadata.add("External-Identifier", "sub-manifest-probe");
        BagCreator.bagInPlace(bagRoot,
                List.<SupportedAlgorithm>of(StandardSupportedAlgorithms.SHA512), false, metadata);

        // Entries from the bag root, with no wrapping directory -- the same layout
        // BagItTransferPackager produces. If the control were zipped differently, the
        // comparison would have two variables in it instead of one.
        Path zip = workDir.resolve("one-manifest.zip");
        try (java.util.zip.ZipOutputStream out =
                new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
                java.util.stream.Stream<Path> tree = Files.walk(bagRoot)) {
            for (Path file : tree.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(
                        new java.util.zip.ZipEntry(bagRoot.relativize(file).toString()));
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
