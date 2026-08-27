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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Puts an E-ARK SIP into the transfer format Archivematica accepts (P3-4).
 *
 * <h2>What this does NOT do</h2>
 *
 * <p><b>It does not make the receiving system understand the package.</b> Archivematica's
 * transfer types are {@code standard / zipfile / unzipped bag / zipped bag / dspace / maildir /
 * TRIM / dataverse} — there is no E-ARK type. A {@code zipped bag} is therefore how the bytes
 * get across, and on the far side the SIP is a file inside a payload: its METS is not read, its
 * structure is not honoured, and nothing about this step makes an Archivematica AIP an E-ARK
 * one. Anyone reading "we have a BagIt connector" as "Archivematica ingests our E-ARK SIPs"
 * has been told something this code does not do, so {@link #LIMITS} travels with every bag.
 *
 * <p>Nor is this "an IP enclosed in a bag for transport". RFC 8493 does not specify a
 * serialization, so that phrasing describes a guarantee the standard does not make. What is
 * true is narrower: a directory with a payload and manifests, zipped, which is what the
 * receiving system's {@code zipped bag} transfer type reads.
 *
 * <h2>ONE payload manifest — measured, not preferred</h2>
 *
 * <p>SHA-512 only. The first version wrote SHA-512 <i>and</i> SHA-256: RFC 8493 §2.1.3 allows
 * several, and the second one meant a receiver reconciling a bag against this product's
 * SHA-256 evidence would not have to recompute anything.
 *
 * <p><b>RODA 6.3.0 cannot ingest such a bag.</b> Measured 2026-08-26 against a live instance:
 * "Binary already exists", and the whole ingest transaction is rolled back. The identical bag
 * with one manifest ingests and produces an AIP.
 *
 * <p>The mechanism is in the library, not in RODA's plugin: {@code BagitSIP.parse} — commons-ip
 * <i>v1</i>, which ships inside {@code commons-ip2-2.11.3.jar} — walks
 * {@code Bag.getPayLoadManifests()} and, per manifest, adds an {@code IPFile} for every entry of
 * {@code getFileToChecksumMap()}, with no dedupe. {@code BagitToAIPPluginUtils} then hands each
 * one to {@code ModelService.createFile}, so the payload is created twice.
 *
 * <p><b>Writing two was not wrong.</b> RFC 8493 §2.1.3 allows several payload manifests. This is
 * an accommodation of one library's parser, not a correction.
 *
 * <p><b>Something is lost by dropping to one, and it is not nothing.</b> The payload's SHA-256
 * is still stated — {@code bag-info.txt} carries it in {@code External-Description}, which is
 * the value a receiver needs to tie the bag to this product's chain. What is gone is that any
 * RFC 8493 verifier used to <i>check</i> it as a path→digest binding; {@code External-Description}
 * is free text that no verifier reads. The value survives, the verification of it does not. The
 * tag manifests drop to SHA-512 only for the same reason.
 *
 * <p>Note also that {@code External-Description} is only written when a caller supplies
 * {@code sipDigest} (unlike {@code submissionId}, which is refused when absent). The export
 * endpoint always supplies it; a caller that does not gets a bag with the SHA-256 nowhere.
 *
 * <p>If a deployment's receiver wants a second manifest, that is a change to make deliberately —
 * and to measure against that receiver. Archivematica reads bags with bagit-python, not
 * commons-ip, so it plausibly accepts two; <b>that has not been measured.</b>
 *
 * <p>Design: {@code docs/design/p3-4-custody-transfer.md} §6.
 */
public final class BagItTransferPackager {

    private BagItTransferPackager() {
    }

    /**
     * What a reader must not conclude from a bag this produced.
     *
     * <p>Returned with every bag rather than written in a document nobody opens at transfer
     * time: the sentence has to be where the decision is made.
     */
    public static final String LIMITS =
            "This bag is a TRANSFER FORMAT, not an interpretation. The receiving system reads "
                    + "it as a payload of opaque files; it does not read the E-ARK SIP's METS, "
                    + "honour its structure, or produce an E-ARK AIP because of it. Nothing "
                    + "here establishes that the receiver accepted, understood or kept "
                    + "anything — those are its own processes, reported in a receipt.";

    /** The bag, what it holds, and what that does not mean. */
    public record Bagged(Path zippedBag, String payloadOxum, long payloadBytes, String limits) {}

    /**
     * Wraps {@code sip} as a zipped bag under {@code workDir}.
     *
     * @param sip the package to carry; copied, never moved, so a failure here cannot destroy it
     * @param submissionId goes into {@code bag-info.txt} as {@code External-Identifier}, which
     *        is the field a later conversation about this transfer refers to
     * @param sipDigest goes in as an {@code External-Description} line, so the bag itself names
     *        the package digest a receipt has to match. Without it, a bag and a receipt can
     *        only be tied together through a system that has both.
     */
    public static Bagged bag(Path sip, Path workDir, String submissionId, String sipDigest)
            throws IOException {
        if (sip == null || !Files.isRegularFile(sip)) {
            throw new IllegalArgumentException("there is no package at " + sip + " to transfer");
        }
        if (submissionId == null || submissionId.isBlank()) {
            // A bag with no external identifier can be transferred and then not discussed. The
            // whole point of the custody protocol is that both ends can name the same thing.
            throw new IllegalArgumentException("a transfer needs a submission id: a bag with no "
                    + "External-Identifier cannot be referred to in a later receipt");
        }
        Path bagRoot = Files.createDirectories(workDir.resolve("bag"));
        // Straight into the bag root, NOT into data/. bagInPlace() creates data/ itself and
        // MOVES everything it finds in the root into it — so a data/ we made first becomes
        // data/data/, and the payload ends up one level too deep with a manifest that agrees
        // with itself. Nothing fails; the receiver just gets the wrong layout.
        Path staged = bagRoot.resolve(sip.getFileName().toString());
        Files.copy(sip, staged, StandardCopyOption.REPLACE_EXISTING);

        Metadata metadata = new Metadata();
        metadata.add("External-Identifier", submissionId);
        metadata.add("Bag-Software-Agent", "NemakiWare");
        if (sipDigest != null && !sipDigest.isBlank()) {
            metadata.add("External-Description",
                    "E-ARK SIP; package digest (SHA-256) " + sipDigest);
        }
        // ONE manifest. See the class javadoc: two of them make RODA 6.3.0 roll back the
        // ingest. This costs the SHA-256 its manifest line — the value stays in bag-info.txt,
        // but no bag verifier checks it there.
        try {
            BagCreator.bagInPlace(bagRoot, List.of(StandardSupportedAlgorithms.SHA512),
                    false, metadata);
        } catch (java.security.NoSuchAlgorithmException e) {
            // Not swallowed into a generic failure: a JVM without SHA-512 is a deployment
            // problem with a specific fix, and "the bag could not be written" sends whoever
            // reads it looking at the disk.
            throw new IOException("this JVM does not provide a digest the bag manifests need ("
                    + e.getMessage() + "), so no bag was written", e);
        }

        // After bagInPlace, which moved it.
        Path carried = bagRoot.resolve("data").resolve(sip.getFileName().toString());
        if (!Files.isRegularFile(carried)) {
            throw new IOException("the bag was written but the package is not at "
                    + bagRoot.relativize(carried) + "; the payload layout is not what a "
                    + "receiver will look for, so this bag is not sent");
        }
        sortTagManifestLines(bagRoot);

        long bytes = Files.size(carried);
        String oxum = bytes + ".1";
        Path zip = workDir.resolve(submissionId + ".zip");
        zipDirectory(bagRoot, zip);
        return new Bagged(zip, oxum, bytes, LIMITS);
    }

    /**
     * Puts the tag manifests' lines in a fixed order.
     *
     * <p>bagit-java writes them by iterating a collection keyed on ABSOLUTE paths, so the
     * sequence depends on where the bag was built — measured, not assumed: two bags of the same
     * package under different working directories came out with the same four lines in a
     * different order, and the archives then differed in length, because deflate compresses a
     * reordered file differently. "Was this sent twice?" has no cheap answer after that.
     *
     * <p>Whether two particular directories happen to hash into the same order is chance, which
     * is why the control for this is a direct test of this method and not the end-to-end
     * comparison — that one passes about one time in {@code n!} with the sorting removed.
     *
     * <p>Safe to rewrite: a tag manifest is a set of {@code hash path} lines whose order no
     * verifier depends on, and nothing hashes the tag manifests themselves — they are the top
     * of the chain, which is why bagit-java can afford to be careless about the order.
     */
    static void sortTagManifestLines(Path bagRoot) throws IOException {
        try (var listing = Files.list(bagRoot)) {
            List<Path> manifests = listing
                    .filter(p -> p.getFileName().toString().startsWith("tagmanifest-"))
                    .toList();
            for (Path manifest : manifests) {
                List<String> lines = new ArrayList<>(
                        Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8));
                lines.sort(Comparator.naturalOrder());
                Files.write(manifest, lines, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Zips {@code root} with entries relative to it, in a stable order.
     *
     * <p>Sorted, so two runs over the same input produce the same archive. An archive whose
     * byte order depends on a directory listing cannot be compared with a previous one, and
     * "the same package was sent twice" then has no cheap answer.
     */
    private static void zipDirectory(Path root, Path zip) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(root.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/'));
                // A fixed time, for the same reason as the sort: the mtime of a file this
                // process just wrote is not information about the record, and letting it vary
                // makes two identical transfers produce two different archives.
                entry.setTime(0L);
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }
}
