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
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

/**
 * A timestamp authority for fixtures that need a REAL token over a value they compute.
 *
 * <p>The golden record supplies the only token this module otherwise has, and it covers one
 * value. A lock about what a RENEWAL must cover therefore cannot use it: a fixture whose
 * imprint is also wrong is refused by the imprint comparison and never reaches the branch under
 * test — which is exactly how such a lock stayed green under its own control (measured).
 */
final class TestAuthority {

    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";

    private static KeyPair keys;
    private static X509Certificate certificate;

    private TestAuthority() {
    }

    /** A token whose message imprint is exactly {@code imprint}. */
    static synchronized byte[] tokenOver(byte[] imprint) throws Exception {
        if (keys == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            keys = kpg.generateKeyPair();
            org.bouncycastle.asn1.x500.X500Name subject =
                    new org.bouncycastle.asn1.x500.X500Name("CN=Fixture TSA");
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                    BigInteger.ONE, new Date(System.currentTimeMillis() - 86_400_000L),
                    new Date(System.currentTimeMillis() + 86_400_000L), subject,
                    keys.getPublic());
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                    new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
            certificate = new JcaX509CertificateConverter().getCertificate(builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
        }
        TimeStampRequestGenerator requests = new TimeStampRequestGenerator();
        requests.setCertReq(true);
        TimeStampRequest request =
                requests.generate(new ASN1ObjectIdentifier(SHA256), imprint);
        TimeStampTokenGenerator generator = new TimeStampTokenGenerator(
                new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                        .build("SHA256withRSA", keys.getPrivate(), certificate),
                new JcaDigestCalculatorProviderBuilder().build().get(
                        new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new ASN1ObjectIdentifier(SHA256))),
                new ASN1ObjectIdentifier("1.2.3.4.9"));
        generator.addCertificates(
                new org.bouncycastle.cert.jcajce.JcaCertStore(List.of(certificate)));
        return generator.generate(request, BigInteger.ONE, new Date()).getEncoded();
    }
}
