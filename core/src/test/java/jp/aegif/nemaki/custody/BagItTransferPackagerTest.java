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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transfer format, and the claim it must not be read as making (P3-4).
 */
class BagItTransferPackagerTest {

    private static final String SUBMISSION = "sub-2026-0001";
    private static final String DIGEST = "a".repeat(64);

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

    private static Path sip(Path dir, String body) throws Exception {
        return Files.write(dir.resolve("sip.zip"), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the bag carries the package unchanged, under data/")
    void thePackageIsCarriedUnchanged(@TempDir Path tmp) throws Exception {
        Path source = sip(Files.createDirectories(tmp.resolve("in")), "the package bytes");

        BagItTransferPackager.Bagged bagged = BagItTransferPackager.bag(source,
                Files.createDirectories(tmp.resolve("work")), SUBMISSION, DIGEST);

        Map<String, byte[]> entries = entriesOf(bagged.zippedBag());
        assertTrue(entries.containsKey("data/sip.zip"), entries.keySet().toString());
        assertArrayEquals("the package bytes".getBytes(StandardCharsets.UTF_8),
                entries.get("data/sip.zip"),
                "the bytes that arrive are not the bytes that were packaged");
        // And the original is untouched: a transfer that consumed the package would leave a
        // failed send with nothing to retry.
        assertTrue(Files.exists(source));
    }

    @Test
    @DisplayName("it is a bag: declaration, both manifests, and bag-info")
    void itIsActuallyABag(@TempDir Path tmp) throws Exception {
        BagItTransferPackager.Bagged bagged = BagItTransferPackager.bag(
                sip(Files.createDirectories(tmp.resolve("in")), "x"),
                Files.createDirectories(tmp.resolve("work")), SUBMISSION, DIGEST);

        Map<String, byte[]> entries = entriesOf(bagged.zippedBag());
        assertTrue(entries.containsKey("bagit.txt"), entries.keySet().toString());
        assertTrue(entries.containsKey("manifest-sha512.txt"), entries.keySet().toString());
        // Exactly ONE payload manifest. RFC 8493 allows several, and RODA 6.3.0 adds each
        // payload file once per manifest — so a second one makes the ingest fail with
        // "Binary already exists" and roll back. Measured against a live RODA on 2026-08-26;
        // the identical bag with one manifest produced an AIP.
        assertFalse(entries.containsKey("manifest-sha256.txt"),
                "a second payload manifest is back; the tested receiver cannot ingest such a "
                        + "bag: " + entries.keySet());
        assertTrue(entries.containsKey("bag-info.txt"), entries.keySet().toString());
    }

    @Test
    @DisplayName("bag-info names the submission and the package digest")
    void theBagCanBeTalkedAboutLater() throws Exception {
        Path tmp = Files.createTempDirectory("bag-info");
        BagItTransferPackager.Bagged bagged = BagItTransferPackager.bag(
                sip(Files.createDirectories(tmp.resolve("in")), "x"),
                Files.createDirectories(tmp.resolve("work")), SUBMISSION, DIGEST);

        String info = new String(entriesOf(bagged.zippedBag()).get("bag-info.txt"),
                StandardCharsets.UTF_8);

        assertTrue(info.contains("External-Identifier: " + SUBMISSION), info);
        assertTrue(info.contains(DIGEST),
                "the bag does not name the package digest, so a bag and a receipt can only be "
                        + "tied together through a system that holds both — and, since there is "
                        + "now only one payload manifest, this is also where a receiver finds "
                        + "the SHA-256 it needs to reconcile against this product's chain: "
                        + info);
    }

    @Test
    @DisplayName("a transfer with no submission id is refused")
    void aBagNobodyCanReferToIsRefused(@TempDir Path tmp) throws Exception {
        Path source = sip(Files.createDirectories(tmp.resolve("in")), "x");
        Path work = Files.createDirectories(tmp.resolve("work"));

        assertThrows(IllegalArgumentException.class,
                () -> BagItTransferPackager.bag(source, work, "  ", DIGEST));
    }

    @Test
    @DisplayName("two runs over the same package produce the same archive")
    void theArchiveIsReproducible(@TempDir Path tmp) throws Exception {
        // An archive whose byte order depends on a directory listing, or whose entries carry
        // the mtime of a file this process just wrote, cannot be compared with a previous one
        // — and "was the same package sent twice?" then has no cheap answer.
        //
        // The one that actually bit: bagit-java writes the TAG MANIFESTS by iterating a set,
        // so two runs over identical input produced the same four lines in a different order.
        // Everything else already matched.
        Path source = sip(Files.createDirectories(tmp.resolve("in")), "same bytes");

        byte[] first = Files.readAllBytes(BagItTransferPackager.bag(source,
                Files.createDirectories(tmp.resolve("w1")), SUBMISSION, DIGEST).zippedBag());
        byte[] second = Files.readAllBytes(BagItTransferPackager.bag(source,
                Files.createDirectories(tmp.resolve("w2")), SUBMISSION, DIGEST).zippedBag());

        assertArrayEquals(first, second,
                "the same package bagged twice produced two different archives");
    }

    @Test
    @DisplayName("tag manifest lines come out sorted — the deterministic control")
    void tagManifestsAreOrdered(@TempDir Path tmp) throws Exception {
        // The end-to-end comparison above only catches this when the two directories happen to
        // hash into different orders, which is chance — measured: removing the sort left it
        // green. This drives the normalisation directly, so it fails every time.
        Path bagRoot = Files.createDirectories(tmp.resolve("bag"));
        Files.write(bagRoot.resolve("tagmanifest-sha512.txt"),
                List.of("ffff  bagit.txt", "0000  bag-info.txt", "aaaa  manifest-sha512.txt"));
        Files.write(bagRoot.resolve("tagmanifest-sha256.txt"),
                List.of("ffff  bagit.txt", "0000  bag-info.txt"));
        // A payload manifest, which must be left alone: its order is bagit-java's business and
        // rewriting files this method was not asked to touch is how a normaliser corrupts a bag.
        Files.write(bagRoot.resolve("manifest-sha512.txt"),
                List.of("ffff  data/z.bin", "0000  data/a.bin"));

        BagItTransferPackager.sortTagManifestLines(bagRoot);

        assertEquals(List.of("0000  bag-info.txt", "aaaa  manifest-sha512.txt", "ffff  bagit.txt"),
                Files.readAllLines(bagRoot.resolve("tagmanifest-sha512.txt")));
        assertEquals(List.of("0000  bag-info.txt", "ffff  bagit.txt"),
                Files.readAllLines(bagRoot.resolve("tagmanifest-sha256.txt")));
        assertEquals(List.of("ffff  data/z.bin", "0000  data/a.bin"),
                Files.readAllLines(bagRoot.resolve("manifest-sha512.txt")),
                "the payload manifest was rewritten too");
    }

    @Test
    @DisplayName("the limits say the receiver does not read the SIP")
    void theLimitsRefuseTheObviousMisreading() {
        // "We have a BagIt connector" is read as "Archivematica ingests our E-ARK SIPs". It
        // does not: there is no E-ARK transfer type, so on the far side the SIP is a file
        // inside a payload.
        String limits = BagItTransferPackager.LIMITS;

        assertTrue(limits.contains("TRANSFER FORMAT"), limits);
        assertTrue(limits.contains("does not read the E-ARK SIP's METS"), limits);
        assertTrue(limits.contains("Nothing here establishes that the receiver accepted"), limits);
        // And it must not describe itself as enclosing an IP for transport: RFC 8493 specifies
        // no serialization, so that phrasing claims a guarantee the standard does not make.
        assertFalse(limits.contains("enclos"), limits);
    }

    @Test
    @DisplayName("a package that is not there is refused, not bagged empty")
    void nothingToSendIsNotAnEmptyBag(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.zip");

        assertThrows(IllegalArgumentException.class,
                () -> BagItTransferPackager.bag(missing, tmp, SUBMISSION, DIGEST));
    }
}
