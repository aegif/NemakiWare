/*
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

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A LOCAL RFC 3161 time-stamping authority, for measuring — not for evidence.
 *
 * <p>It answers RFC 3161 requests with real tokens (BouncyCastle's generator, SHA-256, a fresh
 * self-signed RSA key with the timeStamping EKU), so the product's rung 3 and the independent
 * verifier can be exercised end to end on a machine with no network: RC condition 6's round trip
 * of a package that carries an anchor (docs/operations/roda-round-trip-runbook.md §anchors).
 *
 * <p><b>Not a trusted TSA.</b> The key is generated at start-up and discarded at exit; nobody
 * vouches for it; its time is this machine's clock. A token from here proves only that the
 * pipeline carries and checks a token — never when anything existed. Do not point a production
 * deployment at it.
 *
 * <pre>
 * BC=~/.m2/repository/org/bouncycastle
 * java -cp $BC/bcprov-jdk18on/1.85/bcprov-jdk18on-1.85.jar:$BC/bcpkix-jdk18on/1.85/bcpkix-jdk18on-1.85.jar:$BC/bcutil-jdk18on/1.85/bcutil-jdk18on-1.85.jar \
 *      tools/local-tsa/LocalTsa.java 3180 /tmp/local-tsa-cert.pem
 * </pre>
 *
 * Then {@code -Danchor.rfc3161.tsa.url=http://host.docker.internal:3180/} for a core in Docker.
 */
public final class LocalTsa {

    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
    /** An OID under the private test arc, so no token from here names a real policy. */
    private static final String POLICY = "1.2.3.4.1";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3180;
        Path certOut = args.length > 1 ? Path.of(args[1]) : null;

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name subject = new X500Name("CN=NemakiWare LOCAL test TSA (not trusted)");
        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 7 * 86_400_000L);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.valueOf(System.currentTimeMillis()), from, to, subject, kp.getPublic());
        builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate())));
        if (certOut != null) {
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
            Files.writeString(certOut, pem);
        }

        TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(
                new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", kp.getPrivate(), cert),
                new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(new ASN1ObjectIdentifier(SHA256))),
                new ASN1ObjectIdentifier(POLICY));
        tokens.addCertificates(new JcaCertStore(List.of(cert)));
        tokens.setAccuracySeconds(1);
        TimeStampResponseGenerator responses = new TimeStampResponseGenerator(tokens, Set.of(SHA256));
        AtomicLong serial = new AtomicLong(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            byte[] body;
            int status = 200;
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    throw new IllegalArgumentException("POST an application/timestamp-query");
                }
                TimeStampRequest request = new TimeStampRequest(exchange.getRequestBody().readAllBytes());
                body = responses.generate(request, BigInteger.valueOf(serial.getAndIncrement()), new Date()).getEncoded();
                System.out.println("stamped imprint " + java.util.HexFormat.of().formatHex(request.getMessageImprintDigest()));
            } catch (Exception e) {
                status = 400;
                body = String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/timestamp-reply" : "text/plain");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        System.out.println("LOCAL test TSA (not trusted) on port " + port + ", policy " + POLICY
                + (certOut == null ? "" : ", certificate written to " + certOut));
    }
}
