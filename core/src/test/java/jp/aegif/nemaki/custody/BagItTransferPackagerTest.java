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
    @DisplayName("it is a bag: declaration, both payload manifests, and bag-info")
    void itIsActuallyABag(@TempDir Path tmp) throws Exception {
        BagItTransferPackager.Bagged bagged = BagItTransferPackager.bag(
                sip(Files.createDirectories(tmp.resolve("in")), "x"),
                Files.createDirectories(tmp.resolve("work")), SUBMISSION, DIGEST);

        Map<String, byte[]> entries = entriesOf(bagged.zippedBag());
        assertTrue(entries.containsKey("bagit.txt"), entries.keySet().toString());
        // SHA-512 because receivers ask for it, SHA-256 because that is the digest this
        // product's evidence chain uses. In a manifest it is a path->digest binding the
        // receiver's own verification covers; in bag-info.txt it is free text nobody checks.
        //
        // This spent 2026-08-26 as ONE manifest, because RODA 6.3.0's BagitToAIPPlugin rolls
        // back a two-manifest ingest (commons-ip v1 adds the payload once per manifest --
        // TwoPayloadManifestsBreakTheLegacyBagParserTest pins that, and it is still true).
        // It is two again because RODA takes the SIP directly, so a parser defect in a
        // receiver this layer does not serve should not pick the format for the one it does.
        //
        // Enumerate rather than forbid a name: an assertion that only forbade manifest-sha1.txt
        // would pass if the SHA-256 one silently disappeared, which is the loss that matters.
        List<String> payloadManifests = entries.keySet().stream()
                .filter(name -> name.startsWith("manifest-") && name.endsWith(".txt"))
                .sorted()
                .toList();
        assertEquals(List.of("manifest-sha256.txt", "manifest-sha512.txt"), payloadManifests,
                "the payload manifests are not SHA-256 + SHA-512. If the SHA-256 one is gone, "
                        + "the digest this product's chain uses is no longer something a "
                        + "receiver's bag verification checks: " + entries.keySet());

        // A file called manifest-sha256.txt is not the point -- an EMPTY one would satisfy the
        // check above. The point is that the line in it binds data/sip.zip to the SHA-256 a
        // receiver would compute, because that binding is the whole reason the second manifest
        // is here. So compute it and look for the actual line.
        String expected = hex("SHA-256", entries.get("data/sip.zip"));
        List<String> sha256Lines = new String(entries.get("manifest-sha256.txt"),
                StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
        assertEquals(List.of(expected + "  data/sip.zip"), sha256Lines,
                "manifest-sha256.txt does not bind data/sip.zip to its actual SHA-256, so a "
                        + "receiver's bag verification does not cover the digest this product's "
                        + "chain uses -- which is the only reason this manifest exists");

        assertTrue(entries.containsKey("bag-info.txt"), entries.keySet().toString());
    }

    private static String hex(String algorithm, byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance(algorithm).digest(bytes)) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
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
                        + "tied together through a system that holds both. manifest-sha256.txt "
                        + "carries the same value as a verified path→digest binding; this line "
                        + "is what a conversation about the transfer quotes: " + info);
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
        // does not: there is no E-ARK transfer type, so the far side never interprets the SIP.
        String limits = BagItTransferPackager.LIMITS;

        assertTrue(limits.contains("TRANSFER FORMAT"), limits);
        assertTrue(limits.contains("does not read the E-ARK SIP's METS"), limits);
        assertTrue(limits.contains("Nothing here establishes that the receiver accepted"), limits);
        // And it must not describe itself as enclosing an IP for transport: RFC 8493 specifies
        // no serialization, so that phrasing claims a guarantee the standard does not make.
        assertFalse(limits.contains("enclos"), limits);

        // The measured correction has to be IN the string, not just in a design document.
        // This said "a payload of opaque files" until 2026-08-27, when Archivematica 1.18.0's
        // automated config was measured extracting the SIP zip and filing its tree under the
        // AIP's objects/. Without these two lines, deleting that clause leaves the test green
        // and puts a falsified sentence back on every bag.
        assertFalse(limits.contains("opaque"), limits);
        assertTrue(limits.contains("unpacked is not understood"),
                "the limits no longer say that unpacking is not understanding. A receiver may "
                        + "well extract the payload -- Archivematica does -- and a reader who is "
                        + "only told 'it stays opaque' will read the extracted tree as the "
                        + "receiver having honoured the package: " + limits);
    }

    @Test
    @DisplayName("a package that is not there is refused, not bagged empty")
    void nothingToSendIsNotAnEmptyBag(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.zip");

        assertThrows(IllegalArgumentException.class,
                () -> BagItTransferPackager.bag(missing, tmp, SUBMISSION, DIGEST));
    }

    // ── the submission id names the bag, and names nothing outside the working directory ──

    @Test
    @DisplayName("a submission id that walks out of the working directory does not")
    void aSubmissionIdCannotWalkOutOfTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        // submissionId arrives on the bag endpoint as a @RequestParam and went straight into
        // workDir.resolve(submissionId + ".zip"). '../../escaped' wrote escaped.zip two levels
        // up, over whatever was there. Admin-only is not confinement (CodeQL java/path-injection,
        // confirmed by reading the flow: request param -> resolve -> Files.newOutputStream).
        // The working directory sits TWO levels down inside this test's own @TempDir, so the
        // escape this measures lands inside the temp dir too. Pointing the assertion at the
        // shared temp ROOT instead made the sabotaged run leave escaped.zip behind, and the
        // restored run then failed on someone else's litter — the runner caught it as "the
        // tree is NOT green after restore".
        Path work = Files.createDirectories(tmp.resolve("a/b/work"));
        Path source = sip(Files.createDirectories(tmp.resolve("in")), "x");

        BagItTransferPackager.Bagged bagged = BagItTransferPackager.bag(source, work,
                "../../escaped", DIGEST);

        Path written = bagged.zippedBag().toAbsolutePath().normalize();
        assertTrue(written.startsWith(work.toAbsolutePath().normalize()),
                "the bag was written outside the working directory: " + written);
        assertTrue(Files.exists(written), "no bag was written at all");
        assertFalse(Files.exists(tmp.resolve("a/escaped.zip")),
                "a file was created outside the working directory");
    }

    @Test
    @DisplayName("the bag's file name carries no path separator of its own")
    void theBagFileNameCarriesNoSeparator(@TempDir Path tmp) throws Exception {
        // zipUnder directly, because the two layers cover each other through bag(): with the
        // character reduction removed the confinement check still refuses, so a test that goes
        // through bag() fails on an exception rather than on its own assertion and measures
        // nothing about the reduction (the runner named it: "FIRED FOR THE WRONG REASON").
        // Here the reduction is the only thing between the id and the name.
        Path work = Files.createDirectories(tmp.resolve("work"));

        Path zip = BagItTransferPackager.zipUnder(work, "sub/2026 0001");

        assertEquals(work.toAbsolutePath().normalize(), zip.getParent(),
                "the name put the bag in a directory of the caller's choosing: " + zip);
        assertFalse(zip.getFileName().toString().contains("/"),
                "a separator survived into the file name: " + zip.getFileName());
    }

    @Test
    @DisplayName("the caller's own submission id still reaches bag-info.txt unchanged")
    void theRawSubmissionIdStillReachesBagInfo(@TempDir Path tmp) throws Exception {
        // The over-throw guard: only the FILE NAME is reduced. External-Identifier is the field
        // a later receipt refers to, so it must carry what the caller wrote, separators and all.
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path source = sip(Files.createDirectories(tmp.resolve("in")), "x");

        BagItTransferPackager.Bagged bagged = BagItTransferPackager.bag(source, work,
                "sub/2026 0001", DIGEST);

        Map<String, byte[]> entries = entriesOf(bagged.zippedBag());
        String bagInfo = new String(entries.get("bag-info.txt"), StandardCharsets.UTF_8);
        assertTrue(bagInfo.contains("External-Identifier: sub/2026 0001"),
                "the submission id was rewritten inside the bag: " + bagInfo);
    }
}
