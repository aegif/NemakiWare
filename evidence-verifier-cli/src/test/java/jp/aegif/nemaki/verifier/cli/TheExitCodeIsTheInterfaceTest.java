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
package jp.aegif.nemaki.verifier.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exit code is what a receiving organisation's script branches on.
 *
 * <p>Which makes one substitution the whole point of this class: <b>3 is not 0</b>. "Could not
 * tell" arriving as success at the last possible moment would undo every refusal underneath it.
 */
class TheExitCodeIsTheInterfaceTest {

    private static final String ROOT = "sip/";

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Verify.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static Path zip(Path dir, String name, Map<String, String> entries) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, String> goodPackage(String payload) {
        // Computed here with the JDK rather than through the verifier's own helpers: a
        // fixture built by the code under test would agree with it by construction.
        String digest = sha256Hex(payload);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", "<mets:mets "
                + "xmlns:mets=\"http://www.loc.gov/METS/\" "
                + "xmlns:xlink=\"http://www.w3.org/1999/xlink\"><mets:fileSec><mets:file><mets:FLocat "
                + "xlink:href=\"representations/rep1/data/minutes.txt\"/></mets:file>"
                + "</mets:fileSec></mets:mets>");
        entries.put(ROOT + "representations/rep1/data/minutes.txt", payload);
        entries.put(ROOT + "metadata/preservation/premis.xml",
                // The namespace IS declared, as PremisWriter declares it: the reader parses
                // rather than string-matches, so an unbound prefix would not be PREMIS at all.
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigestAlgorithm>SHA-256</premis:messageDigestAlgorithm>"
                        + "<premis:messageDigest>" + digest + "</premis:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>");
        return entries;
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String AT = "2026-09-20T00:00:00Z";
    private static final String TSA_POLICY = "1.2.3.4.5";

    static {
        java.security.Security.addProvider(
                new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    /** A P2-complete package and the two hashes a holder might bring to it. */
    private record Anchored(Path zip, String coveringHash, String notOnTheChain) {
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.append(first ? "" : ",").append('"').append(e.getKey()).append("\":")
                        .append(json(e.getValue()));
                first = false;
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "" : ",").append(json(list.get(i)));
            }
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> checkpoint(long from, long to, String root, String prev) {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("domain", "record-content");
        cp.put("fromSequence", from);
        cp.put("toSequence", to);
        cp.put("merkleRoot", root);
        cp.put("prevCheckpointHash", prev);
        cp.put("createdAt", AT);
        cp.put("checkpointHash", jp.aegif.nemaki.verifier.Canonical.hash("LEDGER_CHECKPOINT_V1",
                "record-content", from, to, root, prev, AT));
        return cp;
    }

    /** A real RFC 3161 token over the 32 bytes of {@code merkleRoot}, from a throwaway TSA. */
    private static byte[] tokenOver(String merkleRoot) throws Exception {
        java.security.KeyPairGenerator keys = java.security.KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        java.security.KeyPair tsaKeys = keys.generateKeyPair();
        java.util.Date from = new java.util.Date(System.currentTimeMillis() - 86_400_000L);
        java.util.Date to = new java.util.Date(System.currentTimeMillis() + 86_400_000L);
        org.bouncycastle.asn1.x500.X500Name name = new org.bouncycastle.asn1.x500.X500Name("CN=Test TSA");
        org.bouncycastle.cert.X509CertificateHolder tsa =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name,
                        java.math.BigInteger.ONE, from, to, name, tsaKeys.getPublic())
                        .addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                                        org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping))
                        .build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                                "SHA256withRSA").build(tsaKeys.getPrivate()));
        org.bouncycastle.asn1.ASN1ObjectIdentifier sha256 =
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1");
        org.bouncycastle.tsp.TimeStampRequestGenerator requests =
                new org.bouncycastle.tsp.TimeStampRequestGenerator();
        requests.setCertReq(true);
        org.bouncycastle.tsp.TimeStampRequest request =
                requests.generate(sha256, java.util.HexFormat.of().parseHex(merkleRoot));
        org.bouncycastle.operator.DigestCalculatorProvider digests =
                new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder()
                        .setProvider("BC").build();
        org.bouncycastle.cms.SignerInfoGenerator signer =
                new org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(digests)
                        .build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                                "SHA256withRSA").setProvider("BC").build(tsaKeys.getPrivate()), tsa);
        org.bouncycastle.tsp.TimeStampTokenGenerator generator =
                new org.bouncycastle.tsp.TimeStampTokenGenerator(signer,
                        digests.get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(sha256)),
                        new org.bouncycastle.asn1.ASN1ObjectIdentifier(TSA_POLICY));
        generator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                List.of(new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                        .setProvider("BC").getCertificate(tsa))));
        return generator.generate(request, java.math.BigInteger.ONE, new java.util.Date())
                .getEncoded();
    }

    /**
     * Everything ANCHORED_CHECKPOINT_V1 reads, consistent: the P0 package, a statement over its
     * payload, the ledger entry that commits to the statement, a covering checkpoint over that
     * one entry, a chain of two checkpoints and a real RFC 3161 token over the anchor target's
     * root. {@code predecessor} is the first link's {@code prevCheckpointHash}: null makes the
     * chain start at the ledger's first checkpoint; a hash makes it start after one the package
     * does not carry.
     */
    private static Anchored anchoredPackage(Path dir, String predecessor) throws Exception {
        String payload = "the minutes";
        Map<String, String> text = goodPackage(payload);
        String section = ROOT + "metadata/other/nemaki-evidence/";
        Map<String, byte[]> files = new LinkedHashMap<>();

        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("repositoryId", "bedroom");
        statement.put("objectId", "doc-1");
        statement.put("versionObjectId", "doc-1");
        statement.put("contentStreamId", "att-1");
        statement.put("contentDigest", sha256Hex(payload));
        statement.put("contentLength", (long) payload.getBytes(StandardCharsets.UTF_8).length);
        statement.put("commitmentKind", "CAPTURED");
        statement.put("captureIntentId", null);
        statement.put("recordedAt", AT);
        String statementDigest = jp.aegif.nemaki.verifier.Canonical.documentDigest(statement);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("domain", "record-content");
        entry.put("sequence", 5L);
        entry.put("subjectKind", "RECORD_CONTENT_STATE");
        entry.put("subjectId", "doc-1");
        entry.put("payloadDigest", statementDigest);
        entry.put("occurredAt", AT);
        entry.put("prevEntryHash", null);
        String entryHash = jp.aegif.nemaki.verifier.Canonical.hash("LEDGER_ENTRY_V1",
                "record-content", 5L, "RECORD_CONTENT_STATE", "doc-1", statementDigest, AT, null);
        entry.put("entryHash", entryHash);

        String root = jp.aegif.nemaki.verifier.Merkle.root(List.of(entryHash));
        Map<String, Object> covering = checkpoint(5, 5, root, predecessor);
        String laterRoot = sha256Hex("the next period");
        Map<String, Object> target = checkpoint(6, 6, laterRoot,
                String.valueOf(covering.get("checkpointHash")));
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("leafHash", jp.aegif.nemaki.verifier.Merkle.hashLeaf(entryHash));
        proof.put("steps", List.of());
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("links", List.of(covering, target));

        files.put("record-content-statement.json", json(statement).getBytes(StandardCharsets.UTF_8));
        files.put("record-content-statement.c14n",
                jp.aegif.nemaki.verifier.Canonical.encode(statement));
        files.put("ledger-entry.json", json(entry).getBytes(StandardCharsets.UTF_8));
        files.put("inclusion-proof.json", json(proof).getBytes(StandardCharsets.UTF_8));
        files.put("covering-checkpoint.json", json(covering).getBytes(StandardCharsets.UTF_8));
        files.put("anchor-target-checkpoint.json", json(target).getBytes(StandardCharsets.UTF_8));
        files.put("checkpoint-chain.json", json(chain).getBytes(StandardCharsets.UTF_8));
        files.put("anchors/rfc3161.der", tokenOver(laterRoot));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileVersion", "1");
        profile.put("declaredProfiles", List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1",
                "ANCHORED_CHECKPOINT_V1"));
        files.put("profile.json", json(profile).getBytes(StandardCharsets.UTF_8));

        List<Map<String, Object>> listed = new ArrayList<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("path", f.getKey());
            one.put("sha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(f.getValue())));
            listed.add(one);
        }
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", "RFC3161_TSA");
        rung.put("state", "PRESENT");
        rung.put("path", "anchors/rfc3161.der");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", AT);
        manifest.put("files", listed);
        manifest.put("anchors", List.of(rung));
        files.put("bundle-manifest.json", json(manifest).getBytes(StandardCharsets.UTF_8));

        Path file = dir.resolve(predecessor == null ? "anchored.zip" : "anchored-later.zip");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : text.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(section + f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        return new Anchored(file, String.valueOf(covering.get("checkpointHash")), "9".repeat(64));
    }

    @Test
    @DisplayName("a package anchored by an RFC 3161 token exits 0 at ANCHORED_CHECKPOINT_V1")
    void anAnchoredPackageExits0AtP2(@TempDir Path tmp) throws Exception {
        Anchored anchored = anchoredPackage(tmp, null);

        Run result = run("verify", anchored.zip().toString(), "--profile", "ANCHORED_CHECKPOINT_V1");

        assertEquals(Verify.EXIT_VERIFIED, result.code(),
                "the one profile above P0 this CLI is documented to reach exit 0 at never had a "
                        + "package that reached it in these tests; every lock below depends on "
                        + "this one: " + result.out() + result.err());
    }

    @Test
    @DisplayName("an expected checkpoint that is on the chain leaves exit 0; one that is not, placed inside the presented period by its toSequence, is exit 2; hash alone is exit 3")
    void anExpectedCheckpointIsLookedForOnTheChain(@TempDir Path tmp) throws Exception {
        Anchored anchored = anchoredPackage(tmp, null);

        assertEquals(Verify.EXIT_VERIFIED, run("verify", anchored.zip().toString(),
                "--profile", "ANCHORED_CHECKPOINT_V1",
                "--expected-checkpoint", anchored.coveringHash()).code());
        // The fixture's chain covers toSequence 5 (covering) to 6 (target) from the ledger's
        // first checkpoint; a retained checkpoint at 6 that is not on it is missing from the
        // presented period.
        Run missing = run("verify", anchored.zip().toString(), "--profile", "ANCHORED_CHECKPOINT_V1",
                "--expected-checkpoint", anchored.notOnTheChain(),
                "--expected-checkpoint-sequence", "6");
        assertEquals(Verify.EXIT_FAILED, missing.code(),
                "the chain presents toSequence 5..6 from the ledger's first checkpoint, and the "
                        + "holder's checkpoint at 6 is not in it: " + missing.out());
        Run hashOnly = run("verify", anchored.zip().toString(), "--profile", "ANCHORED_CHECKPOINT_V1",
                "--expected-checkpoint", anchored.notOnTheChain(), "--json");
        assertEquals(Verify.EXIT_INDETERMINATE, hashOnly.code(),
                "the hash alone does not place the checkpoint: it may lie after this package's "
                        + "target. Exit 2 here accused every package older than the holder's record "
                        + "(c96 confirmation review, P2): " + hashOnly.out());
        assertTrue(hashOnly.out().contains("\"reasonCode\":\"EXPECTED_SEQUENCE_UNKNOWN\""),
                hashOnly.out());
    }

    @Test
    @DisplayName("an expected checkpoint after this package's target is exit 3 with EXPECTED_AFTER_TARGET; a sequence without a hash, or that is not an integer, is usage")
    void anExpectedCheckpointAfterThePackageIsNotDecided(@TempDir Path tmp) throws Exception {
        Anchored anchored = anchoredPackage(tmp, null);

        Run after = run("verify", anchored.zip().toString(), "--profile", "ANCHORED_CHECKPOINT_V1",
                "--expected-checkpoint", anchored.notOnTheChain(),
                "--expected-checkpoint-sequence", "7", "--json");
        assertEquals(Verify.EXIT_INDETERMINATE, after.code(),
                "the package's target closes at toSequence 6 and the holder's checkpoint at 7 "
                        + "was retained later: the package cannot carry it, and that is not a "
                        + "rollback (c96 confirmation review, P2): " + after.out());
        assertTrue(after.out().contains("\"reasonCode\":\"EXPECTED_AFTER_TARGET\""), after.out());

        assertEquals(Verify.EXIT_USAGE, run("verify", anchored.zip().toString(),
                "--profile", "ANCHORED_CHECKPOINT_V1", "--expected-checkpoint-sequence", "6").code(),
                "a sequence with no checkpoint to place is a question this run would silently not answer");
        assertEquals(Verify.EXIT_USAGE, run("verify", anchored.zip().toString(),
                "--profile", "ANCHORED_CHECKPOINT_V1", "--expected-checkpoint", anchored.notOnTheChain(),
                "--expected-checkpoint-sequence", "six").code());
    }

    @Test
    @DisplayName("an expected checkpoint the chain cannot decide about is exit 3 — required, so never exit 0")
    void anUndecidableExpectedCheckpointIsIndeterminateNotVerified(@TempDir Path tmp)
            throws Exception {
        Anchored anchored = anchoredPackage(tmp, "a".repeat(64));
        // Without a checkpoint to compare against, the package is sound and exit 0 — the
        // rollback check is NOT_PRESENT and not required.
        assertEquals(Verify.EXIT_VERIFIED, run("verify", anchored.zip().toString(),
                "--profile", "ANCHORED_CHECKPOINT_V1").code());

        Run result = run("verify", anchored.zip().toString(), "--profile", "ANCHORED_CHECKPOINT_V1",
                "--expected-checkpoint", anchored.notOnTheChain(),
                "--expected-checkpoint-sequence", "4", "--json");

        assertEquals(Verify.EXIT_INDETERMINATE, result.code(),
                "the chain starts after a predecessor the package does not carry, so the "
                        + "holder's checkpoint may lie before it: not a rollback, not a pass. "
                        + "With the check not required this was exit 0 — a holder who supplied a "
                        + "checkpoint was told VERIFIED over a question nobody answered (9-6 "
                        + "review, P1): " + result.out());
        assertTrue(result.out().contains("\"reasonCode\":\"CHAIN_STARTS_AFTER_EXPECTED\""),
                result.out());
    }

    @Test
    @DisplayName("an expected checkpoint with a profile below ANCHORED_CHECKPOINT_V1 is usage — not a question silently unasked")
    void anExpectedCheckpointBelowP2IsUsage(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--profile", "PACKAGE_INTEGRITY_V1",
                "--expected-checkpoint", "9".repeat(64), "--expected-checkpoint-sequence", "6");

        assertEquals(Verify.EXIT_USAGE, result.code(),
                "the rollback check belongs to ANCHORED_CHECKPOINT_V1 and above; at P0 it never "
                        + "ran, and the holder who supplied a checkpoint was told VERIFIED (c96 "
                        + "confirmation review, round 2, P1): " + result.out() + result.err());
        assertTrue(result.err().contains("ANCHORED_CHECKPOINT_V1"), result.err());
    }

    @Test
    @DisplayName("a package that passes P0 exits 0")
    void aGoodPackageExitsZero(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));
        Run result = run("verify", sip.toString());

        assertEquals(Verify.EXIT_VERIFIED, result.code(), result.out() + result.err());
        assertTrue(result.out().contains("verdict: VERIFIED"), result.out());
        assertTrue(result.out().contains("does NOT establish"),
                "the limits print on SUCCESS too. A reader who sees VERIFIED and nothing else "
                        + "supplies their own idea of what it means");
    }

    @Test
    @DisplayName("the limits say what ANCHORED_CHECKPOINT_V1 checks, and do not put it behind a trust profile")
    void theLimitsSayWhatTheAnchorCheckIs() {
        // They said the anchor was checked only against a trust profile and that without one
        // the anchor checks report NOT_PRESENT — while ANCHORED_CHECKPOINT_V1 reaches exit 0
        // with no trust profile at all, on the token's own certificate (9-6 review, P1). The
        // text travels with every answer, VERIFIED included, so it has to describe that run.
        assertFalse(Verify.LIMITS.contains("the anchor checks report NOT_PRESENT"), Verify.LIMITS);
        assertTrue(Verify.LIMITS.contains("ANCHORED_CHECKPOINT_V1 checks that an RFC 3161 token "
                + "commits the checkpoint's root and is signed by the certificate it carries"),
                Verify.LIMITS);
        assertTrue(Verify.LIMITS.contains("not who that signer is, or whether to trust them"),
                Verify.LIMITS);
        assertTrue(Verify.LIMITS.contains("checked from TRUSTED_RFC3161_V1 up, and only against a "
                + "trust profile you supplied"), Verify.LIMITS);
    }

    @Test
    @DisplayName("an edited package exits 2, and it is not 3")
    void anEditedPackageExitsTwo(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "representations/rep1/data/minutes.txt", "the EDITED minutes");
        Path sip = zip(tmp, "edited.zip", entries);

        Run result = run("verify", sip.toString());

        assertEquals(Verify.EXIT_FAILED, result.code(), result.out());
        assertTrue(result.out().contains("verdict: FAILED"), result.out());
    }

    @Test
    @DisplayName("a package that cannot be evaluated exits 3, which is NOT success")
    void anIndeterminatePackageExitsThree(@TempDir Path tmp) throws Exception {
        // A P0-only package asked for the ledger profile: its P1 section is simply not there.
        Path sip = zip(tmp, "legacy.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--profile", "RECORD_LEDGER_V1");

        assertEquals(Verify.EXIT_INDETERMINATE, result.code(), result.out());
        assertTrue(result.out().contains("LEGACY_PACKAGE_LAYOUT"), result.out());
        assertTrue(result.code() != Verify.EXIT_VERIFIED,
                "'could not tell' arriving as success at the last possible moment would undo "
                        + "every refusal underneath it");
    }

    @Test
    @DisplayName("an unreadable package is indeterminate, not a finding about its contents")
    void anUnreadablePackageIsIndeterminate(@TempDir Path tmp) throws Exception {
        Path notAZip = Files.writeString(tmp.resolve("broken.zip"), "this is not a zip");

        Run result = run("verify", notAZip.toString());

        assertEquals(Verify.EXIT_INDETERMINATE, result.code(), result.out());
        assertTrue(result.out().contains("NOT_A_ZIP"), result.out());
    }

    @Test
    @DisplayName("a profile this version cannot evaluate is a usage error, never a weaker pass")
    void anUnknownProfileIsAUsageError(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        // Every profile evidence-profile-v1.md defines is now evaluable, so the refusal is
        // measured with a name that is not one of them. This assertion has already had to move
        // twice as profiles landed — each time, leaving it pointed at a name that had become
        // known would have stopped measuring the refusal without going red.
        Run result = run("verify", sip.toString(), "--profile", "SOMETHING_FROM_A_LATER_VERSION");

        assertEquals(Verify.EXIT_USAGE, result.code(),
                "a caller asking for a profile this version cannot evaluate must not be told "
                        + "the package passed a different one");
        assertTrue(result.err().contains("not a profile this version evaluates"), result.err());
    }

    @Test
    @DisplayName("--allow-network is refused rather than silently ignored")
    void allowNetworkIsRefused(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--allow-network");

        assertEquals(Verify.EXIT_USAGE, result.code(),
                "a caller passing it expects something to happen; ignoring it would let them "
                        + "believe a network check ran");
    }

    @Test
    @DisplayName("no package named is a usage error, and nothing is checked")
    void noPackageIsAUsageError() {
        Run result = run("verify");
        assertEquals(Verify.EXIT_USAGE, result.code());
        assertTrue(result.err().contains("usage:"), result.err());
    }

    @Test
    @DisplayName("--json prints one object a script can read")
    void jsonOutputIsParseable(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--json");

        assertEquals(Verify.EXIT_VERIFIED, result.code());
        Object parsed = jp.aegif.nemaki.verifier.Json.parse(result.out().trim());
        assertTrue(parsed instanceof Map, result.out());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed;
        assertEquals("VERIFIED", body.get("verdict"));
        assertTrue(String.valueOf(body.get("limits")).contains("does NOT establish"),
                "the limits are in the machine-readable output too, not only the human one");
    }

    @Test
    @DisplayName("every exit code the documentation names is distinct")
    void theExitCodesAreDistinct() {
        // 1 is deliberately unused, so a shell treating "nonzero" as failure is right either
        // way and it stays clear that every code here was chosen.
        assertEquals(5, java.util.Set.of(Verify.EXIT_VERIFIED, Verify.EXIT_FAILED,
                Verify.EXIT_INDETERMINATE, Verify.EXIT_USAGE, Verify.EXIT_INTERNAL).size());
        assertEquals(0, Verify.EXIT_VERIFIED, "0 is VERIFIED and nothing else is");
    }


    // ------------------------------------------------------------------------------------
    // The JSON is the interface too (R66). The schema at docs/evidence-profile/v1/ is what a
    // receiving party validates the output against with THEIR tools; this module never reads
    // it at run time (a validator would be one more library they have to trust). These locks
    // keep the schema and the implementation pointing at the same shape.
    // ------------------------------------------------------------------------------------

    private static final Path SCHEMA =
            Path.of("../docs/evidence-profile/v1/verifier-result.schema.json");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() throws Exception {
        assertTrue(Files.exists(SCHEMA), "the result schema is not at " + SCHEMA);
        Object parsed = jp.aegif.nemaki.verifier.Json.parse(
                Files.readString(SCHEMA, StandardCharsets.UTF_8));
        assertTrue(parsed instanceof Map, "the schema is not a JSON object");
        Map<String, Object> root = (Map<String, Object>) parsed;
        // The WHOLE tree is checked for keywords this validator does not implement, before any
        // instance is validated. Checking only the nodes an instance happens to visit left an
        // optional property with a `pattern` unchecked as long as no fixture emitted it — and
        // the day the CLI did, a receiving party's validator would refuse what every lock
        // here had passed (Codex review, P2).
        preflight(root, "$");
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void preflight(Map<String, Object> node, String at) {
        for (Map.Entry<String, Object> keyword : node.entrySet()) {
            String key = keyword.getKey();
            if (!ANNOTATIONS.contains(key) && !IMPLEMENTED.contains(key)) {
                throw new IllegalStateException("the schema uses the keyword '" + key + "' at "
                        + at + ", which this validator does not implement");
            }
            if (key.equals("additionalProperties") && !(keyword.getValue() instanceof Boolean)) {
                // A schema-valued additionalProperties would be read as "allowed" by the
                // boolean check below, which is the one place this validator would report
                // an unread constraint as no constraint (review, P3).
                throw new IllegalStateException("additionalProperties at " + at + " is a "
                        + "schema, which this validator does not implement");
            }
        }
        Object props = node.get("properties");
        if (props instanceof Map) {
            for (Map.Entry<String, Object> p : ((Map<String, Object>) props).entrySet()) {
                preflight((Map<String, Object>) p.getValue(), at + "." + p.getKey());
            }
        }
        for (String sub : List.of("items", "if", "then", "else", "not")) {
            if (node.get(sub) instanceof Map) {
                preflight((Map<String, Object>) node.get(sub), at + "/" + sub);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> node) {
        return (Map<String, Object>) node.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    /** Keywords that carry no constraint. Anything else the validator does not implement is refused. */
    private static final java.util.Set<String> ANNOTATIONS =
            java.util.Set.of("$schema", "$id", "title", "description");

    /** Keywords this validator implements. A schema using any other keyword makes it throw. */
    private static final java.util.Set<String> IMPLEMENTED = java.util.Set.of("type", "enum",
            "const", "minLength", "minItems", "required", "additionalProperties", "properties",
            "items", "if", "then", "else", "not");

    /**
     * A validator for the subset of JSON Schema this schema uses, driven by the schema text.
     *
     * <p>Applicators are INSTANCE-driven, as in JSON Schema: {@code required} and
     * {@code properties} apply whenever the value is an object, {@code items} whenever it is an
     * array, {@code minLength} whenever it is a string — independent of whether the node also
     * says {@code type}. The first version evaluated them only under {@code type: "object"} (and
     * the array/string keywords likewise), so the schema's {@code if}/{@code then}/{@code else}
     * /{@code not} nodes — which carry no {@code type} — were never evaluated at all: {@code if}
     * always matched, {@code then} checked nothing, and {@code not} would have refused every
     * non-UNAVAILABLE check had {@code else} ever been reached. Two defects cancelling into
     * green (both reviews, P1).
     *
     * <p>Unknown keywords are refused, not skipped. A keyword this validator does not implement
     * would otherwise be read as "no constraint" — a could-not-check reported as passed, the
     * defect this branch is named after, in the lock that guards the schema.
     */
    @SuppressWarnings("unchecked")
    private static void validate(Object value, Map<String, Object> node, String at,
            List<String> errors) {
        for (String key : node.keySet()) {
            if (!ANNOTATIONS.contains(key) && !IMPLEMENTED.contains(key)) {
                throw new IllegalStateException("the schema uses the keyword '" + key + "' at "
                        + at + ", which this validator does not implement. Skipping it would "
                        + "report 'conforms' for a constraint nobody checked");
            }
        }
        if (node.containsKey("const") && !java.util.Objects.equals(node.get("const"), value)) {
            errors.add(at + ": expected const " + node.get("const") + ", got " + value);
        }
        if (node.containsKey("enum") && !list(node.get("enum")).contains(value)) {
            errors.add(at + ": " + value + " is not in enum " + node.get("enum"));
        }
        if (node.containsKey("type")) {
            String type = String.valueOf(node.get("type"));
            boolean ok = switch (type) {
                case "object" -> value instanceof Map;
                case "array" -> value instanceof List;
                case "string" -> value instanceof String;
                default -> throw new IllegalStateException("type '" + type + "' at " + at
                        + " is not one this validator implements");
            };
            if (!ok) {
                errors.add(at + ": expected " + type + ", got "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
            }
        }
        if (value instanceof Map) {
            Map<String, Object> object = (Map<String, Object>) value;
            for (Object required : list(node.getOrDefault("required", List.of()))) {
                if (!object.containsKey(String.valueOf(required))) {
                    errors.add(at + ": missing required " + required);
                }
            }
            Map<String, Object> props = (Map<String, Object>) node.getOrDefault("properties", Map.of());
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                if (props.containsKey(entry.getKey())) {
                    validate(entry.getValue(), (Map<String, Object>) props.get(entry.getKey()),
                            at + "." + entry.getKey(), errors);
                } else if (Boolean.FALSE.equals(node.get("additionalProperties"))) {
                    errors.add(at + ": undeclared key " + entry.getKey());
                }
            }
        }
        if (value instanceof List) {
            List<Object> array = list(value);
            if (node.containsKey("minItems")
                    && array.size() < ((Number) node.get("minItems")).intValue()) {
                errors.add(at + ": fewer than minItems " + node.get("minItems"));
            }
            Map<String, Object> items = (Map<String, Object>) node.get("items");
            for (int i = 0; items != null && i < array.size(); i++) {
                validate(array.get(i), items, at + "[" + i + "]", errors);
            }
        }
        if (value instanceof String && node.containsKey("minLength")
                && ((String) value).length() < ((Number) node.get("minLength")).intValue()) {
            errors.add(at + ": shorter than minLength " + node.get("minLength"));
        }
        if (node.containsKey("if")) {
            List<String> ifErrors = new ArrayList<>();
            validate(value, (Map<String, Object>) node.get("if"), at, ifErrors);
            Object branch = ifErrors.isEmpty() ? node.get("then") : node.get("else");
            if (branch != null) {
                validate(value, (Map<String, Object>) branch, at, errors);
            }
        }
        if (node.containsKey("not")) {
            List<String> notErrors = new ArrayList<>();
            validate(value, (Map<String, Object>) node.get("not"), at, notErrors);
            if (notErrors.isEmpty()) {
                errors.add(at + ": matches a 'not' schema " + node.get("not"));
            }
        }
    }

    /** The per-check schema node (checks.items). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkItems() throws Exception {
        return (Map<String, Object>) ((Map<String, Object>)
                properties(schema()).get("checks")).get("items");
    }

    @Test
    @DisplayName("the schema's reasonCode rule actually discriminates, judged by the schema")
    void theReasonCodeRuleDiscriminates() throws Exception {
        // The two objects the if/then/else exists to reject, run through the schema's own
        // per-check node. Expected values come from the schema, not from a hand-written list:
        // empty the schema's `then` and this goes red. The first validator let both through
        // (both reviews, P1).
        Map<String, Object> items = checkItems();
        List<String> missingReason = new ArrayList<>();
        validate(Map.of("name", "x", "outcome", "UNAVAILABLE"), items, "unavailable-no-reason",
                missingReason);
        assertTrue(!missingReason.isEmpty(),
                "an UNAVAILABLE check with no reasonCode passed the schema's per-check node, so "
                        + "the if/then rule is not being evaluated");
        List<String> reasonOnPassed = new ArrayList<>();
        validate(Map.of("name", "x", "outcome", "PASSED", "reasonCode", "UNKNOWN_ALGORITHM"),
                items, "passed-with-reason", reasonOnPassed);
        assertTrue(!reasonOnPassed.isEmpty(),
                "a PASSED check carrying a reasonCode passed the schema's per-check node, so "
                        + "the else/not rule is not being evaluated");
        // And the honest pair passes, or the rule refuses everything.
        List<String> fine = new ArrayList<>();
        validate(Map.of("name", "x", "outcome", "UNAVAILABLE", "reasonCode", "UNKNOWN_ALGORITHM"),
                items, "unavailable-with-reason", fine);
        validate(Map.of("name", "x", "outcome", "PASSED"), items, "passed-plain", fine);
        assertTrue(fine.isEmpty(), "a conforming check was refused: " + fine);
    }

    /** A zip with more entries than the reader admits — the RESOURCE_LIMIT refusal. */
    private static Path tooManyEntries(Path dir) throws Exception {
        Path file = dir.resolve("too-many.zip");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i <= jp.aegif.nemaki.verifier.PackageReader.MAX_ENTRIES; i++) {
                zip.putNextEntry(new ZipEntry(ROOT + "e" + i));
                zip.closeEntry();
            }
        }
        return file;
    }

    /** Two entries with one name — ZipOutputStream refuses to write that, so the bytes are patched. */
    private static Path duplicateEntries(Path dir) throws Exception {
        Path file = dir.resolve("duplicate.zip");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("a/premis.xml"));
            zip.write("first".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("b/premis.xml"));
            zip.write("second".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] raw = Files.readAllBytes(file);
        byte[] from = "b/premis.xml".getBytes(StandardCharsets.UTF_8);
        byte[] to = "a/premis.xml".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + from.length <= raw.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length && match; j++) {
                match = raw[i + j] == from[j];
            }
            if (match) {
                System.arraycopy(to, 0, raw, i, to.length);
            }
        }
        Files.write(file, raw);
        return file;
    }

    /**
     * A zip whose central directory and local headers disagree — the INCONSISTENT_ARCHIVE refusal.
     * Written by hand (STORED) because ZipOutputStream cannot write one; the central directory
     * simply omits the PREMIS, so a reader built on it never sees the file the stream reader
     * judged. The reader's own tests cover the other two shapes (a record only the directory has,
     * one name with two different bytes).
     */
    private static Path disagreeingTables(Path dir) throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream central = new java.io.ByteArrayOutputStream();
        int listed = 0;
        for (Map.Entry<String, String> e : goodPackage("the minutes").entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            int offset = body.size();
            java.nio.ByteBuffer local = java.nio.ByteBuffer.allocate(30 + name.length)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            local.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt((int) crc.getValue())
                    .putInt(data.length).putInt(data.length).putShort((short) name.length)
                    .putShort((short) 0).put(name);
            body.writeBytes(local.array());
            body.writeBytes(data);
            if (e.getKey().endsWith("premis.xml")) {
                continue;
            }
            java.nio.ByteBuffer record = java.nio.ByteBuffer.allocate(46 + name.length)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            record.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putInt((int) crc.getValue()).putInt(data.length).putInt(data.length)
                    .putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).put(name);
            central.writeBytes(record.array());
            listed++;
        }
        java.nio.ByteBuffer end = java.nio.ByteBuffer.allocate(22)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) listed)
                .putShort((short) listed).putInt(central.size()).putInt(body.size())
                .putShort((short) 0);
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        all.writeBytes(body.toByteArray());
        all.writeBytes(central.toByteArray());
        all.writeBytes(end.array());
        Path file = dir.resolve("two-tables.zip");
        Files.write(file, all.toByteArray());
        return file;
    }

    @Test
    @DisplayName("--json output conforms to the published schema for every profile and every refusal")
    void theJsonConformsToThePublishedSchema(@TempDir Path tmp) throws Exception {
        // Every profile the CLI knows (read from the CLI, not copied), each asked to echo the
        // profile it was given; and one package per refusal the reader can raise. What this
        // exercises is the CLI's own reason-code path (refusal.reasonCode()) plus the ledger
        // profiles' LEGACY_PACKAGE_LAYOUT — five codes of the registry's seventeen. The other
        // twelve need anchor material the good package does not carry; their SHAPE is held
        // by the schema-driven validator and the registry, not by this fixture. The first
        // version ran one package against six profiles and called it twelve outputs — the
        // non-zip is refused BEFORE the profile branch, so that was one case six times, and
        // only NOT_A_ZIP and LEGACY_PACKAGE_LAYOUT ever appeared (both reviews, P2).
        Map<String, Object> schema = schema();
        Path good = zip(tmp, "good.zip", goodPackage("the minutes"));
        Path notAZip = tmp.resolve("broken.zip");
        Files.writeString(notAZip, "this is not a zip");
        Map<String, String> traversal = new LinkedHashMap<>(goodPackage("the minutes"));
        traversal.put("../escape.txt", "x");
        Path unsafe = zip(tmp, "unsafe.zip", traversal);
        Map<Path, String> refusals = new LinkedHashMap<>();
        refusals.put(notAZip, "NOT_A_ZIP");
        refusals.put(unsafe, "UNSAFE_PATH");
        refusals.put(duplicateEntries(tmp), "DUPLICATE_ENTRY");
        refusals.put(tooManyEntries(tmp), "RESOURCE_LIMIT");
        refusals.put(disagreeingTables(tmp), "INCONSISTENT_ARCHIVE");
        // One fixture per refusal the reader can raise — tied to the enum, so a fifth refusal
        // added there is a red run here, not a silently unexercised code (review, P3).
        java.util.Set<String> everyRefusal = new java.util.TreeSet<>();
        for (jp.aegif.nemaki.verifier.PackageReader.Refusal r
                : jp.aegif.nemaki.verifier.PackageReader.Refusal.values()) {
            everyRefusal.add(r.name());
        }
        assertEquals(everyRefusal, new java.util.TreeSet<>(refusals.values()),
                "the reader can raise a refusal this test has no package for");

        List<String> allErrors = new ArrayList<>();
        java.util.SortedSet<String> codesSeen = new java.util.TreeSet<>();
        for (String profile : Verify.KNOWN_PROFILES) {
            Run result = run("verify", good.toString(), "--profile", profile, "--json");
            assertTrue(result.code() != Verify.EXIT_USAGE && result.code() != Verify.EXIT_INTERNAL,
                    profile + " did not produce a result: " + result.err());
            Map<String, Object> body = (Map<String, Object>) jp.aegif.nemaki.verifier.Json.parse(
                    result.out().trim());
            assertEquals(profile, body.get("profile"),
                    "the output does not echo the profile it was asked for");
            validate(body, schema, profile + "/good.zip", allErrors);
            for (Object c : list(body.get("checks"))) {
                Object code = ((Map<String, Object>) c).get("reasonCode");
                if (code != null) {
                    codesSeen.add(String.valueOf(code));
                }
            }
        }
        for (Map.Entry<Path, String> refusal : refusals.entrySet()) {
            Run result = run("verify", refusal.getKey().toString(), "--json");
            assertEquals(Verify.EXIT_INDETERMINATE, result.code(),
                    refusal.getValue() + " did not come back INDETERMINATE: " + result.err());
            Map<String, Object> body = (Map<String, Object>) jp.aegif.nemaki.verifier.Json.parse(
                    result.out().trim());
            validate(body, schema, refusal.getValue(), allErrors);
            java.util.Set<Object> codes = new java.util.HashSet<>();
            for (Object c : list(body.get("checks"))) {
                codes.add(((Map<String, Object>) c).get("reasonCode"));
            }
            assertTrue(codes.contains(refusal.getValue()),
                    "the " + refusal.getKey().getFileName() + " package was meant to exercise "
                            + refusal.getValue() + " and the output carries " + codes
                            + " — the branch this fixture exists for was not reached");
            codesSeen.add(refusal.getValue());
        }
        assertTrue(codesSeen.contains("LEGACY_PACKAGE_LAYOUT"),
                "no profile produced LEGACY_PACKAGE_LAYOUT on the legacy good package, so the "
                        + "ledger profiles' UNAVAILABLE path went unexercised: " + codesSeen);
        assertTrue(allErrors.isEmpty(),
                "output the CLI really prints does not conform to the published schema. A "
                        + "receiving party validating with it would reject these results:\n  "
                        + String.join("\n  ", allErrors));
    }

    @Test
    @DisplayName("the schema is closed, pins the limits, and is versioned in its id")
    void theSchemaIsClosedAndPinsTheLimits() throws Exception {
        Map<String, Object> schema = schema();
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "the top level admits undeclared keys");
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>)
                properties(schema).get("checks")).get("items");
        assertEquals(Boolean.FALSE, items.get("additionalProperties"),
                "a check admits undeclared keys");
        assertTrue(list(schema.get("required")).contains("limits"),
                "limits is not required. A schema that lets the limits go is a schema that "
                        + "accepts a CLI which dropped them");
        Map<String, Object> limits = (Map<String, Object>) properties(schema).get("limits");
        assertTrue(((Number) limits.get("minLength")).intValue() >= 1,
                "limits may be empty under the schema");
        assertTrue(String.valueOf(schema.get("$id")).contains("/v1/"),
                "the schema's $id does not carry the profile version");
        // Deliberately NOT an enum (owner's decision): the CLI refuses unknown profiles with
        // exit 4 before any output exists, so the schema closing it too would only mean two
        // lists to keep equal.
        Map<String, Object> profile = (Map<String, Object>) properties(schema).get("profile");
        assertTrue(!profile.containsKey("enum"), "profile became an enum");
    }
}
