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
package jp.aegif.nemaki.evidence.validity;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Writes the golden evidence record that {@code evidence-verifier-core} verifies against.
 *
 * <h2>Why a file on disk</h2>
 *
 * <p>{@code evidence-verifier-core} may not depend on {@code core} — it exists so a receiving
 * organisation can run it with no WAR, no Spring and no CouchDB — so no test over there can ask
 * this product for a record. Its fixtures were therefore hand-built from the verifier's own
 * reading, and agreed with it: they were two-element sequences with the wanted digest lying at
 * the top level, a shape {@code ErsRecord.parse} rejects outright. Meanwhile the record this
 * product really writes has <b>no reduced hash tree</b> and keeps the root inside the token's
 * {@code TSTInfo}, where the verifier's scan never looked. The verifier passed its own fixtures
 * and would have failed every real package (both reviews, fifth round).
 *
 * <p>So one real record, produced by {@link ErsRecord} and a test timestamp authority, is
 * checked in as bytes. The verifier runs its real checks over it. If either side drifts, the
 * bytes stay put and one of the two ends goes red.
 *
 * <h2>Regenerating</h2>
 *
 * <pre>
 * mvn -o -pl core test-compile
 * java -cp "core/target/test-classes:core/target/classes:$(cat /tmp/core-cp.txt)" \
 *      jp.aegif.nemaki.evidence.validity.ErsGoldenWriter \
 *      evidence-verifier-core/src/test/resources/golden
 * </pre>
 *
 * <p>The record is NOT reproducible byte for byte — the timestamp authority's key and the
 * token's genTime are fresh each time — so regenerating deliberately replaces the file rather
 * than confirming it. What keeps it honest is
 * {@code TheGoldenEvidenceRecordIsStillWhatWeWriteTest}, which reads the checked-in bytes with
 * this product's own reader and compares their shape against a record built now.
 */
public final class ErsGoldenWriter {

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    /** The Merkle root the golden record covers. Any 64 hex would do; this one is fixed. */
    public static final String MERKLE_ROOT =
            "3fdba35f04dc8c462986c992bcf875546257113072a909c162f7e470e581e278";

    private ErsGoldenWriter() {
    }

    /**
     * A record over {@code dataObjectHash}, built the way the product builds one.
     *
     * <p>Exposed so the lock that guards the checked-in bytes compares them against a record
     * built NOW rather than against another copy of themselves.
     */
    public static byte[] recordOver(byte[] dataObjectHash) throws Exception {
        return ErsRecord.first(dataObjectHash,
                tokenOver(ErsRecord.imprintForFirst(dataObjectHash))).der();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(dir);
        byte[] h = HexFormat.of().parseHex(MERKLE_ROOT);
        byte[] der = recordOver(h);
        Files.write(dir.resolve("product-ers.der"), der);
        Files.writeString(dir.resolve("product-ers-root.txt"), MERKLE_ROOT + "\n",
                StandardCharsets.UTF_8);
        System.out.println("wrote " + der.length + " bytes over " + MERKLE_ROOT + " to " + dir);
    }

    /** A token over {@code imprint}, requested the way {@code Rfc3161AnchorTarget} requests one. */
    private static byte[] tokenOver(byte[] imprint) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=NemakiWare golden test TSA");
        Date from = new Date(0L);
        Date to = new Date(4_102_444_800_000L);
        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                        subject, BigInteger.ONE, from, to, subject, keyPair.getPublic());
        certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                        org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
        org.bouncycastle.operator.ContentSigner signer =
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                        .build(keyPair.getPrivate());
        X509Certificate certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));
        TimeStampTokenGenerator tokenGenerator = new TimeStampTokenGenerator(
                new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                        .build("SHA256withRSA", keyPair.getPrivate(), certificate),
                new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                        .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier(SHA256_OID))),
                new ASN1ObjectIdentifier("1.2.3.4.1"));
        tokenGenerator.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                List.of(certificate)));

        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        // Without this RFC 3161 REQUIRES the TSA to omit its certificate, and the record then
        // carries a timestamp nobody outside this deployment can attribute. The product sets it.
        requests.setCertReq(true);
        TimeStampRequest request =
                requests.generate(new ASN1ObjectIdentifier(SHA256_OID), imprint);
        TimeStampResponse response = new TimeStampResponseGenerator(tokenGenerator,
                Set.of(SHA256_OID)).generate(request, BigInteger.ONE, new Date());
        if (response.getTimeStampToken() == null) {
            throw new IllegalStateException("the test TSA refused: " + response.getStatusString());
        }
        return response.getTimeStampToken().getEncoded();
    }
}
