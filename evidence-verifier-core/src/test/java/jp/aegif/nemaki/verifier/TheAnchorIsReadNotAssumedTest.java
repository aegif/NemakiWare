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
package jp.aegif.nemaki.verifier;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 reads the anchor it carries (release condition 3).
 *
 * <p>Until 2026-09-22 {@code anchor commits root} checked that the material named by the
 * manifest was present and then answered UNAVAILABLE for every package, so no package could
 * reach VERIFIED at {@code ANCHORED_CHECKPOINT_V1} whatever it carried. Now an RFC 3161 token
 * is parsed and its imprint compared with the anchor target's Merkle root — the same
 * comparison P3 makes under {@code token imprint}, without P3's questions about who signed.
 * The end-to-end case below composes P0 + P1 + P2 the way the CLI does and reaches VERIFIED
 * with a real token, which is the sentence the release notes are allowed to say.
 */
class TheAnchorIsReadNotAssumedTest {

    private static final String ROOT = "sip/";
    private static final String DIR = ROOT + "metadata/other/nemaki-evidence/";
    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final String SHA512 = "2.16.840.1.101.3.4.2.3";

    /** Merkle roots the way the ledger writes them: 64 lowercase hex characters. */
    private static final String ROOT_A = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String ROOT_Z = "0000d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // ---------------------------------------------------------------- a TSA of our own

    private record Authority(X509Certificate ca, KeyPair caKeys, X509Certificate tsa,
                             KeyPair tsaKeys) {
    }

    private static Authority authority() throws Exception {
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair caKeys = keys.generateKeyPair();
        KeyPair tsaKeys = keys.generateKeyPair();
        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 86_400_000L);
        X500Name caName = new X500Name("CN=Test CA");
        X509CertificateHolder caHolder = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE,
                from, to, caName, caKeys.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate()));
        X509CertificateHolder tsaHolder = new JcaX509v3CertificateBuilder(caName, BigInteger.TWO,
                from, to, new X500Name("CN=Test TSA"), tsaKeys.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                .addExtension(Extension.extendedKeyUsage, true,
                        new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate()));
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");
        return new Authority(converter.getCertificate(caHolder), caKeys,
                converter.getCertificate(tsaHolder), tsaKeys);
    }

    /**
     * A real token over {@code over}, made the way the product makes them: a Merkle root is
     * already a SHA-256 digest, so its BYTES are what gets timestamped
     * ({@code Rfc3161AnchorTarget.decodeSha256Hex}). For the SHA-512 case there is nothing to
     * decode — the point there is an algorithm this verifier does not compute.
     */
    private static byte[] tokenOver(Authority authority, String over, String digestOid)
            throws Exception {
        byte[] imprint;
        if (SHA512.equals(digestOid)) {
            imprint = MessageDigest.getInstance("SHA-512").digest(over.getBytes(StandardCharsets.UTF_8));
        } else {
            imprint = java.util.HexFormat.of().parseHex(over);
        }
        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        requests.setCertReq(true);
        TimeStampRequest request = requests.generate(new ASN1ObjectIdentifier(digestOid), imprint);
        org.bouncycastle.cms.SignerInfoGenerator signerInfo =
                new org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC")
                                        .build(authority.tsaKeys().getPrivate()),
                                new X509CertificateHolder(authority.tsa().getEncoded()));
        TimeStampTokenGenerator generator = new TimeStampTokenGenerator(signerInfo,
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
                        .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier(SHA256))),
                new ASN1ObjectIdentifier("1.2.3.4.5"));
        generator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                List.of(authority.tsa(), authority.ca())));
        return generator.generate(request, BigInteger.ONE, new Date()).getEncoded();
    }

    // ---------------------------------------------------------------- documents

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
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(e.getKey()).append("\":").append(json(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(json(list.get(i)));
            }
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> checkpoint(long from, long to, String root, String prev) {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("domain", "record-content");
        cp.put("fromSequence", from);
        cp.put("toSequence", to);
        cp.put("merkleRoot", root);
        cp.put("prevCheckpointHash", prev);
        cp.put("createdAt", "2026-09-20T00:00:00Z");
        cp.put("checkpointHash", Canonical.hash("LEDGER_CHECKPOINT_V1", "record-content", from,
                to, root, prev, "2026-09-20T00:00:00Z"));
        return cp;
    }

    private static Map<String, Object> manifestWith(String kind, String path) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleId", "b");
        manifest.put("createdAt", "2026-09-20T01:00:00Z");
        manifest.put("files", List.of());
        Map<String, Object> rung = new LinkedHashMap<>();
        rung.put("kind", kind);
        rung.put("state", "PRESENT");
        rung.put("path", path);
        manifest.put("anchors", List.of(rung));
        return manifest;
    }

    /** A P2 section: one checkpoint that is both covering and anchor target, with a token. */
    private static Map<String, byte[]> anchoredChain(Map<String, Object> checkpoint, byte[] token) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("links", List.of(checkpoint));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DIR + "checkpoint-chain.json", bytes(json(chain)));
        entries.put(DIR + "covering-checkpoint.json", bytes(json(checkpoint)));
        entries.put(DIR + "anchor-target-checkpoint.json", bytes(json(checkpoint)));
        entries.put(DIR + "bundle-manifest.json",
                bytes(json(manifestWith("RFC3161_TSA", "anchors/rfc3161.der"))));
        entries.put(DIR + "anchors/rfc3161.der", token);
        return entries;
    }

    private static Outcome.Check named(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- the check

    @Test
    @DisplayName("a real token over the anchor target's Merkle root PASSES anchor commits root")
    void aTokenOverTheRootPasses() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        byte[] token = tokenOver(authority(), ROOT_A, SHA256);

        Outcome.Check anchor = named(AnchoredCheckpoint.check(anchoredChain(cp, token), null),
                "anchor commits root");

        assertEquals(Outcome.PASSED, anchor.outcome(), anchor.detail());
        assertTrue(anchor.detail().contains("anchors/rfc3161.der"), anchor.detail());
    }

    @Test
    @DisplayName("a real token over something else FAILS — a well-formed anchor for a different root")
    void aTokenOverSomethingElseFails() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        byte[] token = tokenOver(authority(), ROOT_Z, SHA256);

        Outcome.Check anchor = named(AnchoredCheckpoint.check(anchoredChain(cp, token), null),
                "anchor commits root");

        assertEquals(Outcome.FAILED, anchor.outcome(),
                "a token that commits to another root was reported as " + anchor.outcome()
                        + ". Presence is not verification: the material is real and it is not "
                        + "about this checkpoint");
        assertTrue(anchor.detail().contains("something else"), anchor.detail());
    }

    @Test
    @DisplayName("an imprint under a digest this version does not compute is UNKNOWN, not a mismatch")
    void anImprintUnderAnotherDigestIsUnknown() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        byte[] token = tokenOver(authority(), ROOT_A, SHA512);

        Outcome.Check anchor = named(AnchoredCheckpoint.check(anchoredChain(cp, token), null),
                "anchor commits root");

        assertEquals(Outcome.UNAVAILABLE, anchor.outcome(),
                "a SHA-512 imprint compared against a SHA-256 expectation was reported as "
                        + anchor.outcome() + ". A digest this version cannot compute is one it "
                        + "has not checked; calling it a mismatch reports tampering nobody found");
        assertEquals("UNKNOWN_ALGORITHM", anchor.reasonCode());
    }

    /**
     * The root compared is the CHAIN'S, so a target document that is missing one is caught by
     * {@code chain ends} — which recomputes the document — rather than leaving
     * {@code anchor commits root} to compare against a field that is not there.
     */
    @Test
    @DisplayName("a target with no Merkle root fails the walk, and the anchor check reads the chain's root")
    void aTargetWithoutARootFailsTheWalk() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        Map<String, byte[]> entries = anchoredChain(cp, tokenOver(authority(), ROOT_A, SHA256));
        Map<String, Object> rootless = new LinkedHashMap<>(cp);
        rootless.remove("merkleRoot");
        entries.put(DIR + "anchor-target-checkpoint.json", bytes(json(rootless)));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(entries, null);
        assertEquals(Outcome.NOT_PRESENT, named(checks, "chain ends").outcome(),
                "a target document that omits merkleRoot cannot be recomputed, and the walk "
                        + "reported " + named(checks, "chain ends").outcome());
    }

    /**
     * The attack the third review found: the target document carries the chain's hash and
     * SOMEONE ELSE'S root, and the token is over that other root. Comparing only the stated
     * checkpointHash let every check pass while the anchor committed to a root the chain never
     * reached (Codex, P1).
     */
    @Test
    @DisplayName("a target carrying the chain's hash and another root fails the walk")
    void aTargetWithASubstitutedRootFailsTheWalk() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        Map<String, byte[]> entries = anchoredChain(cp, tokenOver(authority(), ROOT_Z, SHA256));
        Map<String, Object> substituted = new LinkedHashMap<>(cp);
        substituted.put("merkleRoot", ROOT_Z);           // the token is over this
        // ...and the hash it presents is still the chain's, so chainEnds' old comparison held.
        entries.put(DIR + "anchor-target-checkpoint.json", bytes(json(substituted)));

        List<Outcome.Check> checks = AnchoredCheckpoint.check(entries, null);
        assertEquals(Outcome.FAILED, named(checks, "chain ends").outcome(),
                "the target's fields do not hash to the hash it presents, and the walk said "
                        + named(checks, "chain ends").outcome());
        // And the anchor check reads the CHAIN's root, so it refuses the token too.
        assertEquals(Outcome.FAILED, named(checks, "anchor commits root").outcome(),
                "the token is over the substituted root and the anchor check accepted it");
    }

    /**
     * "An anchor commits" is a claim about a signature. Reading a field out of an unverified
     * CMS structure is not one — anyone can write a TSTInfo (Codex, third review, P1).
     */
    @Test
    @DisplayName("a token whose signature does not verify is FAILED, however well its imprint matches")
    void aTokenWithABrokenSignatureIsFailed() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        byte[] token = tokenOver(authority(), ROOT_A, SHA256);
        // Corrupt the signature bytes without touching the imprint: the last byte of the DER
        // is inside the signature value.
        byte[] tampered = token.clone();
        tampered[tampered.length - 1] ^= 0x01;
        Map<String, byte[]> entries = anchoredChain(cp, tampered);

        Outcome.Check anchor = named(AnchoredCheckpoint.check(entries, null), "anchor commits root");
        assertEquals(Outcome.FAILED, anchor.outcome(),
                "a token whose signature does not verify was reported as " + anchor.outcome()
                        + ". The imprint matching says what the STRUCTURE claims; only the "
                        + "signature says someone issued it: " + anchor.detail());
    }

    /**
     * P2 and P3 read the same file, named the same way. A rung claiming another kind, at
     * another path, is not the RFC 3161 anchor — P3 would find no token at all (Codex, P2).
     */
    @Test
    @DisplayName("a matching token at a decoy path under another kind is not read as the RFC 3161 anchor")
    void aDecoyRungIsNotReadAsTheAnchor() throws Exception {
        Map<String, Object> cp = checkpoint(1, 10, ROOT_A, null);
        Map<String, byte[]> entries = anchoredChain(cp, tokenOver(authority(), ROOT_A, SHA256));
        Map<String, Object> decoy = manifestWith("OPENTIMESTAMPS", "anchors/decoy-rfc3161.der");
        entries.put(DIR + "bundle-manifest.json", bytes(json(decoy)));
        entries.put(DIR + "anchors/decoy-rfc3161.der", entries.get(DIR + "anchors/rfc3161.der"));
        entries.remove(DIR + "anchors/rfc3161.der");

        Outcome.Check anchor = named(AnchoredCheckpoint.check(entries, null), "anchor commits root");
        assertEquals(Outcome.UNAVAILABLE, anchor.outcome(),
                "a token under a rung calling itself OPENTIMESTAMPS, at a path §4.2 does not "
                        + "name, was read as the RFC 3161 anchor: " + anchor.detail());
        assertEquals("ANCHOR_NOT_PARSED", anchor.reasonCode());
    }

    // ---------------------------------------------------------------- end to end

    /**
     * P0 + P1 + P2 composed the way the CLI composes them, with a real token: VERIFIED. This is
     * the first package that can reach VERIFIED above P1, and the only sentence about P2 the
     * release notes may carry.
     */
    @Test
    @DisplayName("a consistent package with a real RFC 3161 token is VERIFIED at ANCHORED_CHECKPOINT_V1")
    void aConsistentPackageWithARealTokenIsVerifiedAtP2() throws Exception {
        String payload = "minutes of the meeting";
        String payloadDigest = Canonical.hex(Canonical.sha256(bytes(payload)));

        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("repositoryId", "bedroom");
        statement.put("objectId", "doc-1");
        statement.put("versionObjectId", "doc-1");
        statement.put("contentStreamId", "att-1");
        statement.put("contentDigest", payloadDigest);
        statement.put("contentLength", (long) bytes(payload).length);
        statement.put("commitmentKind", "CAPTURED");
        statement.put("captureIntentId", null);
        statement.put("recordedAt", "2026-09-20T00:00:00Z");
        String statementDigest = Canonical.documentDigest(statement);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("domain", "record-content");
        entry.put("sequence", 1L);
        entry.put("subjectKind", "RECORD_CONTENT_STATE");
        entry.put("subjectId", "doc-1");
        entry.put("payloadDigest", statementDigest);
        entry.put("occurredAt", "2026-09-20T00:00:00Z");
        entry.put("prevEntryHash", null);
        entry.put("entryHash", Canonical.hash("LEDGER_ENTRY_V1", "record-content", 1L,
                "RECORD_CONTENT_STATE", "doc-1", statementDigest, "2026-09-20T00:00:00Z", null));

        String root = Merkle.root(List.of(String.valueOf(entry.get("entryHash"))));
        Map<String, Object> covering = checkpoint(1, 1, root, null);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("leafHash", Merkle.hashLeaf(String.valueOf(entry.get("entryHash"))));
        proof.put("steps", List.<Map<String, Object>>of());

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", bytes("<mets:mets><mets:fileSec><mets:file><mets:FLocat "
                + "xlink:href=\"representations/rep1/data/minutes.txt\"/></mets:file>"
                + "</mets:fileSec></mets:mets>"));
        entries.put(ROOT + "representations/rep1/data/minutes.txt", bytes(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml", bytes("<premis:premis>"
                + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                + "<premis:messageDigestAlgorithm>SHA-256</premis:messageDigestAlgorithm>"
                + "<premis:messageDigest>" + payloadDigest + "</premis:messageDigest>"
                + "</premis:fixity></premis:objectCharacteristics></premis:object></premis:premis>"));
        entries.put(DIR + "record-content-statement.json", bytes(json(statement)));
        entries.put(DIR + "record-content-statement.c14n", Canonical.encode(statement));
        entries.put(DIR + "ledger-entry.json", bytes(json(entry)));
        entries.put(DIR + "inclusion-proof.json", bytes(json(proof)));
        entries.putAll(anchoredChain(covering, tokenOver(authority(), root, SHA256)));

        List<Outcome.Check> all = new ArrayList<>(PackageIntegrity.check(entries));
        all.addAll(RecordLedger.check(entries));
        all.addAll(AnchoredCheckpoint.check(entries, null));
        List<Outcome.Check> required = new ArrayList<>();
        for (String name : PackageIntegrity.REQUIRED) {
            required.add(named(all, name));
        }
        for (String name : RecordLedger.REQUIRED) {
            required.add(named(all, name));
        }
        for (String name : AnchoredCheckpoint.REQUIRED) {
            required.add(named(all, name));
        }

        assertEquals(Outcome.Verdict.VERIFIED, Outcome.combine(all, required),
                "a consistent P0+P1+P2 package with a real token did not verify: " + all);
    }
}
