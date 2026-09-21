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
package jp.aegif.nemaki.rest.purview.anchor;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import com.sun.net.httpserver.HttpServer;

import org.bouncycastle.asn1.cmp.PKIFailureInfo;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TSA client against a TSA we control, so the failure modes that matter can actually happen.
 *
 * <p>The load-bearing test is {@link RefusalHandling}: BouncyCastle's
 * {@code TimeStampResponse.validate()} returns normally for a rejection, so an implementation
 * that trusts validate() reports a refusal as a success and stores "evidence" that is an empty
 * response. Removing the explicit status check in {@code Rfc3161AnchorTarget} makes that test
 * fail — which is the whole reason it exists.
 *
 * <p>These tests never reach the network. A live check against a real TSA is a separate,
 * manual smoke: it proves interoperability, which no local fake can, but it cannot be a unit
 * test without making the build depend on someone else's free service.
 */
class Rfc3161AnchorTargetTest {

    private static final String DIGEST =
            "b7e3a1c9f4d80652e1a7c4f9b2d5e8a3c6f1b4d7e0a3c6f9b2e5d8a1c4f7b0e3";

    private HttpServer server;

    private String startServer(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- configuration

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a blank endpoint leaves the rung unconfigured rather than broken")
        void blankUrlIsNotConfigured() {
            for (String url : new String[]{null, "", "   "}) {
                Rfc3161AnchorTarget target = new Rfc3161AnchorTarget(url, null, null);
                assertFalse(target.isConfigured(), "url=" + url);

                AnchorReceipt receipt = target.anchor(DIGEST);
                assertEquals(AnchorStatus.NOT_CONFIGURED, receipt.status());
                assertNull(receipt.failureReason(), "declining to climb a rung is not a failure");
            }
        }

        @Test
        @DisplayName("a malformed digest is a caller bug and throws, unlike remote failure")
        void malformedDigestThrows() {
            Rfc3161AnchorTarget target = new Rfc3161AnchorTarget("http://tsa.invalid/tsr", null, null);

            assertThrows(IllegalArgumentException.class, () -> target.anchor(null));
            assertThrows(IllegalArgumentException.class, () -> target.anchor("abc"));
            assertThrows(IllegalArgumentException.class,
                    () -> target.anchor("z".repeat(64)), "non-hex must be rejected too");
        }

        @Test
        @DisplayName("an unconfigured target rejects a bad digest before reporting NOT_CONFIGURED")
        void validationPrecedesConfigurationCheck() {
            Rfc3161AnchorTarget target = new Rfc3161AnchorTarget(null, null, null);
            assertThrows(IllegalArgumentException.class, () -> target.anchor("nope"),
                    "a caller bug should surface even where the rung is switched off, "
                            + "otherwise it hides until the day the rung is enabled");
        }
    }

    // ---------------------------------------------------------------- refusals

    @Nested
    @DisplayName("refusal handling (the pitfall this class exists for)")
    class RefusalHandling {

        @Test
        @DisplayName("a TSA rejection is FAILED, not a silent success")
        void rejectionIsNotSuccess() throws Exception {
            // A well-formed REJECTION: no token, status 2. BouncyCastle's validate() accepts
            // this without complaint, so only an explicit status check catches it.
            TimeStampResponseGenerator gen = new TimeStampResponseGenerator(
                    (TimeStampTokenGenerator) null, java.util.Set.of("2.16.840.1.101.3.4.2.1"));
            String url = startServer("/tsr", exchange -> {
                byte[] req = exchange.getRequestBody().readAllBytes();
                byte[] body;
                try {
                    body = gen.generateRejectedResponse(
                            new Exception("policy not supported")).getEncoded();
                } catch (Exception e) {
                    body = new byte[0];
                }
                exchange.getResponseHeaders().add("Content-Type", Rfc3161AnchorTarget.RESPONSE_CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, null).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status(),
                    "a refusal reported as CONFIRMED would store an empty response as evidence");
            assertNull(receipt.proof(), "there is no token to keep");

            // Asserting FAILED alone does NOT discriminate the fix: drop the status check and the
            // code walks into token.getTimeStampInfo() on a null token, the catch-all turns the
            // NullPointerException into the same FAILED, and a test that stopped here would pass
            // while the diagnosis was gone. So require the reason to name what the TSA actually
            // said — an operator seeing "NullPointerException" cannot tell a refusal from a bug.
            assertNotNull(receipt.failureReason());
            assertTrue(receipt.failureReason().startsWith("TSA refused:"),
                    "the refusal must be diagnosed as a refusal, not as an internal error; got: "
                            + receipt.failureReason());
            assertTrue(receipt.failureReason().contains("status="),
                    "the PKI status belongs in the record: " + receipt.failureReason());
            assertFalse(receipt.failureReason().contains("NullPointer"),
                    "a NullPointerException here means the status check was skipped");
        }
    }

    // ---------------------------------------------------------------- transport

    @Nested
    @DisplayName("transport failures are recorded, never thrown")
    class Transport {

        @Test
        @DisplayName("an HTML error page is reported as a content-type mismatch, not a parse error")
        void htmlErrorPageIsDiagnosed() throws Exception {
            String url = startServer("/tsr", exchange -> {
                byte[] body = "<html><body>502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, null).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status());
            assertTrue(receipt.failureReason().contains("Content-Type"),
                    "the operator needs to know an intermediary answered, not that DER failed to "
                            + "parse; got: " + receipt.failureReason());
        }

        @Test
        @DisplayName("an HTTP error status is recorded with its code")
        void httpErrorIsRecorded() throws Exception {
            String url = startServer("/tsr", exchange -> {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, null).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status());
            assertTrue(receipt.failureReason().contains("503"), receipt.failureReason());
        }

        @Test
        @DisplayName("an unreachable TSA fails the anchor, not the caller")
        void unreachableTsaDoesNotThrow() {
            // Port 1 on loopback: nothing listens, connection refused immediately.
            AnchorReceipt receipt =
                    new Rfc3161AnchorTarget("http://127.0.0.1:1/tsr", null, null).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status(),
                    "anchoring is evidence gathering; it must never be able to fail a CMIS write");
            assertNotNull(receipt.failureReason());
        }

        @Test
        @DisplayName("an oversized reply is refused rather than parsed")
        void oversizedReplyIsRefused() throws Exception {
            String url = startServer("/tsr", exchange -> {
                byte[] body = new byte[512 * 1024];
                exchange.getResponseHeaders().add("Content-Type", Rfc3161AnchorTarget.RESPONSE_CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, null).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status());
            assertTrue(receipt.failureReason().contains("exceeds"), receipt.failureReason());
        }
    }

    // ---------------------------------------------------------------- request shape

    @Nested
    @DisplayName("request shape")
    class RequestShape {

        @Test
        @DisplayName("certReq is set and the nonce is unpredictable")
        void certReqAndNonce() throws Exception {
            // Capture what the client actually sends. certReq=false would mean the TSA is
            // REQUIRED to omit its certificate, leaving a token nobody can build a chain for.
            java.util.List<TimeStampRequest> captured = java.util.Collections.synchronizedList(
                    new java.util.ArrayList<>());
            String url = startServer("/tsr", exchange -> {
                byte[] req = exchange.getRequestBody().readAllBytes();
                captured.add(new TimeStampRequest(req));
                exchange.sendResponseHeaders(503, -1);   // the reply does not matter here
                exchange.close();
            });

            Rfc3161AnchorTarget target = new Rfc3161AnchorTarget(url, null, null);
            target.anchor(DIGEST);
            target.anchor(DIGEST);

            assertEquals(2, captured.size());
            for (TimeStampRequest r : captured) {
                assertTrue(r.getCertReq(),
                        "without certReq the TSA must not send its certificate (RFC 3161 2.4.1)");
                assertNotNull(r.getNonce(), "a nonce is what ties the reply to this request");
                assertTrue(r.getNonce().bitLength() > 32,
                        "a timestamp-derived nonce is guessable; expected a large random value");
                assertNull(r.getReqPolicy(),
                        "no policy was configured, so none must be demanded — a wrong OID fails "
                                + "every request");
            }
            assertFalse(captured.get(0).getNonce().equals(captured.get(1).getNonce()),
                    "nonces must not repeat across requests");
        }

        @Test
        @DisplayName("a configured policy OID is demanded")
        void policyIsSentWhenConfigured() throws Exception {
            String policy = "1.2.3.4.5";
            java.util.List<TimeStampRequest> captured = java.util.Collections.synchronizedList(
                    new java.util.ArrayList<>());
            String url = startServer("/tsr", exchange -> {
                captured.add(new TimeStampRequest(exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });

            new Rfc3161AnchorTarget(url, policy, "JP_MIC_ACCREDITED").anchor(DIGEST);

            assertEquals(1, captured.size());
            assertEquals(policy, String.valueOf(captured.get(0).getReqPolicy()));
        }
    }

    // ---------------------------------------------------------------- the success path

    @Nested
    @DisplayName("success path against a real signing TSA")
    class SuccessPath {

        /**
         * A genuine TSA: a self-signed key with the timeStamping EKU, issuing real RFC 3161
         * tokens. Without this the confirmed branch was entirely untested — removing the
         * signature check, breaking the accuracy arithmetic or emitting wrong attributes would
         * all have gone unnoticed (external review, 3.4).
         */
        private java.security.KeyPair keyPair;
        private java.security.cert.X509Certificate certificate;

        private String startTsa(boolean withAccuracy) throws Exception {
            return startTsaGenerating(withAccuracy);
        }

        /** Serve tokens signed by the given key, presenting the given certificates. */
        private String startTsaWith(java.security.KeyPair kp,
                java.security.cert.X509Certificate cert,
                java.util.List<java.security.cert.X509Certificate> embed,
                boolean withAccuracy) throws Exception {
            org.bouncycastle.tsp.TimeStampTokenGenerator tokenGen =
                    new org.bouncycastle.tsp.TimeStampTokenGenerator(
                            new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                                    .build("SHA256withRSA", kp.getPrivate(), cert),
                            new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                                    .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                            new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                                    "2.16.840.1.101.3.4.2.1"))),
                            new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.1"));
            tokenGen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(embed));
            if (withAccuracy) {
                tokenGen.setAccuracySeconds(1);
            }
            org.bouncycastle.tsp.TimeStampResponseGenerator responseGen =
                    new org.bouncycastle.tsp.TimeStampResponseGenerator(
                            tokenGen, java.util.Set.of("2.16.840.1.101.3.4.2.1"));
            return startServer("/tsr", exchange -> {
                byte[] body;
                try {
                    TimeStampRequest request =
                            new TimeStampRequest(exchange.getRequestBody().readAllBytes());
                    body = responseGen.generate(request, BigInteger.valueOf(99), new Date())
                            .getEncoded();
                } catch (Exception e) {
                    body = new byte[0];
                }
                exchange.getResponseHeaders().add("Content-Type",
                        Rfc3161AnchorTarget.RESPONSE_CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
        }

        private String startTsaGenerating(boolean withAccuracy) throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            keyPair = kpg.generateKeyPair();

            org.bouncycastle.asn1.x500.X500Name subject =
                    new org.bouncycastle.asn1.x500.X500Name("CN=Test TSA");
            java.util.Date from = new java.util.Date(System.currentTimeMillis() - 86_400_000L);
            java.util.Date to = new java.util.Date(System.currentTimeMillis() + 86_400_000L);
            org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            subject, BigInteger.ONE, from, to, subject, keyPair.getPublic());
            // RFC 3161 2.3: the EKU must be present and critical, or BouncyCastle refuses to
            // build the token generator at all.
            certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                    new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
            org.bouncycastle.operator.ContentSigner signer =
                    new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                            .build(keyPair.getPrivate());
            org.bouncycastle.cert.X509CertificateHolder holder = certBuilder.build(signer);
            certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .getCertificate(holder);

            org.bouncycastle.tsp.TimeStampTokenGenerator tokenGen =
                    new org.bouncycastle.tsp.TimeStampTokenGenerator(
                            new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                                    .build("SHA256withRSA", keyPair.getPrivate(), certificate),
                            new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                                    .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                            new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                                    "2.16.840.1.101.3.4.2.1"))),
                            new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.1"));
            tokenGen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                    java.util.List.of(certificate)));
            if (withAccuracy) {
                tokenGen.setAccuracySeconds(1);
            }
            org.bouncycastle.tsp.TimeStampResponseGenerator responseGen =
                    new org.bouncycastle.tsp.TimeStampResponseGenerator(
                            tokenGen, java.util.Set.of("2.16.840.1.101.3.4.2.1"));

            return startServer("/tsr", exchange -> {
                byte[] body;
                try {
                    TimeStampRequest request =
                            new TimeStampRequest(exchange.getRequestBody().readAllBytes());
                    body = responseGen.generate(request, BigInteger.valueOf(42), new Date())
                            .getEncoded();
                } catch (Exception e) {
                    body = new byte[0];
                }
                exchange.getResponseHeaders().add("Content-Type",
                        Rfc3161AnchorTarget.RESPONSE_CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
        }

        @Test
        @DisplayName("a real token is CONFIRMED with its signature actually verified")
        void realTokenIsConfirmed() throws Exception {
            String url = startTsa(true);

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, "NONE").anchor(DIGEST);

            assertEquals(AnchorStatus.CONFIRMED, receipt.status(), receipt.failureReason());
            assertNotNull(receipt.anchoredAt());
            assertTrue(receipt.proof().length > 0);
            assertEquals("true", receipt.attributes().get("signatureVerified"));
            assertEquals("1.2.3.4.1", receipt.attributes().get("policyOid"));
            assertEquals("1.0", receipt.attributes().get("accuracySeconds"));
            assertEquals("1", receipt.attributes().get("embeddedCertificateCount"));
        }

        @Test
        @DisplayName("a token without a configured anchor still preserves a checkable artifact")
        void tokenWithoutAnchorStillPreservesTheArtifact() throws Exception {
            String url = startTsa(true);

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, "NONE").anchor(DIGEST);

            assertEquals(AnchorStatus.CONFIRMED, receipt.status());
            assertEquals("false", receipt.attributes().get("signerTrustAnchorConfigured"),
                    "but nothing here says whose certificate that is — the record must not imply "
                            + "it does");
            assertEquals("NONE", receipt.attributes().get("accreditationDeclaredByOperator"));
            assertTrue(receipt.attributes().get("trustAnchorCheck").contains("not configured"),
                    "no anchor was configured, so the record must not describe a check that "
                            + "never ran");
        }

        @Test
        @DisplayName("the receipt asserts a preserved artifact, never that the TSA is independent")
        void independenceIsNotSomethingThisCodeDecides() throws Exception {
            // An operator can run their own TSA, configure its certificate as the anchor and
            // write any accreditation string they like. Every local check then passes. Three
            // review rounds showed each attempt to COMPUTE independence was derivable this way,
            // so the claim is gone: what is asserted is that the token carries what a reader
            // needs to check it, and who the issuer really is stays the reader's judgement.
            String url = startTsa(true);

            AnchorReceipt selfOperated =
                    new Rfc3161AnchorTarget(url, null, "JP_MIC_ACCREDITED", certificate).anchor(DIGEST);

            assertEquals(AnchorStatus.CONFIRMED, selfOperated.status());
            assertEquals("JP_MIC_ACCREDITED",
                    selfOperated.attributes().get("accreditationDeclaredByOperator"),
                    "the operator's word is recorded verbatim and interpreted by nobody here — "
                            + "deriving a boolean from it put independence back in by the side door");
            assertTrue(selfOperated.attributes().get("trustAnchorCheck").contains("PKIX"),
                    "no reader may mistake the issuer check for path validation");
        }

        @Test
        @DisplayName("a configured anchor the signer does not chain to makes it FAILED")
        void anchorMismatchFailsClosed() throws Exception {
            // The operator configured an anchor precisely to have this checked. Recording
            // signerChainsToTrustAnchor=false in an attribute and returning CONFIRMED anyway
            // answers a different question than the one they asked.
            String url = startTsa(true);
            java.security.KeyPair unrelated =
                    java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
            java.security.cert.X509Certificate foreignAnchor =
                    selfSignedTsaCert(unrelated, "CN=Some Other CA");

            AnchorReceipt receipt =
                    new Rfc3161AnchorTarget(url, null, "JP_MIC_ACCREDITED", foreignAnchor).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status(),
                    "the signature verifies, so only the anchor check can reject this");
            assertTrue(receipt.failureReason().contains("trust anchor"), receipt.failureReason());
        }

        @Test
        @DisplayName("a TSA behind an intermediate CA validates to the root — the real-world shape")
        void signerBehindAnIntermediateValidates() throws Exception {
            // Commercial accredited TSAs issue from an intermediate, not from their root. The
            // first implementation only checked "is the anchor the direct issuer", so configuring
            // a real TSA's root would have failed EVERY anchor — and the anchor check fails
            // closed, so it would have failed loudly and totally.
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair rootKey = kpg.generateKeyPair();
            java.security.KeyPair interKey = kpg.generateKeyPair();
            java.security.KeyPair tsaKey = kpg.generateKeyPair();

            java.security.cert.X509Certificate root = ca("CN=Test Root CA", rootKey, null, null);
            java.security.cert.X509Certificate intermediate =
                    ca("CN=Test Intermediate CA", interKey, root, rootKey);
            java.security.cert.X509Certificate tsaCert =
                    tsaLeaf("CN=Accredited TSA", tsaKey, intermediate, interKey);

            String url = startTsaWith(tsaKey, tsaCert,
                    java.util.List.of(tsaCert, intermediate), true);

            AnchorReceipt receipt =
                    new Rfc3161AnchorTarget(url, null, "JP_MIC_ACCREDITED", root).anchor(DIGEST);

            assertEquals(AnchorStatus.CONFIRMED, receipt.status(), receipt.failureReason());
            assertEquals("true", receipt.attributes().get("signerValidatesToTrustAnchor"),
                    "the path runs signer -> intermediate -> root, which a direct-issuer check "
                            + "cannot follow");
            assertEquals("2", receipt.attributes().get("embeddedCertificateCount"));
        }

        /** A CA certificate; self-signed when no issuer is given. */
        private java.security.cert.X509Certificate ca(String dn, java.security.KeyPair kp,
                java.security.cert.X509Certificate issuer, java.security.KeyPair issuerKey)
                throws Exception {
            org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name(dn);
            org.bouncycastle.asn1.x500.X500Name issuerName = issuer == null ? subject
                    : new org.bouncycastle.asn1.x500.X500Name(issuer.getSubjectX500Principal().getName());
            org.bouncycastle.cert.X509v3CertificateBuilder b =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            issuerName, BigInteger.valueOf(System.nanoTime()),
                            new java.util.Date(System.currentTimeMillis() - 86_400_000L),
                            new java.util.Date(System.currentTimeMillis() + 86_400_000L),
                            subject, kp.getPublic());
            b.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                    new org.bouncycastle.asn1.x509.BasicConstraints(true));
            b.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
                    new org.bouncycastle.asn1.x509.KeyUsage(
                            org.bouncycastle.asn1.x509.KeyUsage.keyCertSign
                                    | org.bouncycastle.asn1.x509.KeyUsage.cRLSign));
            java.security.PrivateKey signWith =
                    issuerKey == null ? kp.getPrivate() : issuerKey.getPrivate();
            return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                    b.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                            "SHA256withRSA").build(signWith)));
        }

        /** A TSA leaf issued by the given CA. */
        private java.security.cert.X509Certificate tsaLeaf(String dn, java.security.KeyPair kp,
                java.security.cert.X509Certificate issuer, java.security.KeyPair issuerKey)
                throws Exception {
            org.bouncycastle.cert.X509v3CertificateBuilder b =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            new org.bouncycastle.asn1.x500.X500Name(
                                    issuer.getSubjectX500Principal().getName()),
                            BigInteger.valueOf(System.nanoTime()),
                            new java.util.Date(System.currentTimeMillis() - 86_400_000L),
                            new java.util.Date(System.currentTimeMillis() + 86_400_000L),
                            new org.bouncycastle.asn1.x500.X500Name(dn), kp.getPublic());
            b.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                    new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
            return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                    b.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                            "SHA256withRSA").build(issuerKey.getPrivate())));
        }

        @Test
        @DisplayName("a token WITHOUT accuracy degrades to an upper-bound claim")
        void missingAccuracyDegradesTimeSemantics() throws Exception {
            String url = startTsa(false);

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, "NONE").anchor(DIGEST);

            assertEquals(AnchorStatus.CONFIRMED, receipt.status());
            assertEquals("unspecified", receipt.attributes().get("accuracySeconds"));
            assertEquals(AnchorKind.TimeSemantics.UPPER_BOUND_ONLY, receipt.timeSemantics(),
                    "a token that states no precision cannot carry the bidirectional claim its "
                            + "kind normally does — and FreeTSA is exactly this case");
        }

        @Test
        @DisplayName("a forged token whose signature does not verify is FAILED, not CONFIRMED")
        void forgedTokenIsRejected() throws Exception {
            // The attack the CRITICAL finding described: whoever can answer the TSA URL builds a
            // token carrying the right nonce and imprint. validate() accepts it. Here the token
            // is signed by one key while shipping a DIFFERENT certificate, so only real signature
            // verification can tell it apart from an honest reply.
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair signingKey = kpg.generateKeyPair();
            java.security.KeyPair advertisedKey = kpg.generateKeyPair();

            java.security.cert.X509Certificate mismatched =
                    selfSignedTsaCert(advertisedKey, "CN=Impostor TSA");

            org.bouncycastle.tsp.TimeStampTokenGenerator tokenGen =
                    new org.bouncycastle.tsp.TimeStampTokenGenerator(
                            new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                                    // signs with signingKey but presents mismatched's certificate
                                    .build("SHA256withRSA", signingKey.getPrivate(), mismatched),
                            new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                                    .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                            new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                                    "2.16.840.1.101.3.4.2.1"))),
                            new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.1"));
            tokenGen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                    java.util.List.of(mismatched)));
            org.bouncycastle.tsp.TimeStampResponseGenerator responseGen =
                    new org.bouncycastle.tsp.TimeStampResponseGenerator(
                            tokenGen, java.util.Set.of("2.16.840.1.101.3.4.2.1"));

            String url = startServer("/tsr", exchange -> {
                byte[] body;
                try {
                    TimeStampRequest request =
                            new TimeStampRequest(exchange.getRequestBody().readAllBytes());
                    body = responseGen.generate(request, BigInteger.valueOf(7), new Date()).getEncoded();
                } catch (Exception e) {
                    body = new byte[0];
                }
                exchange.getResponseHeaders().add("Content-Type",
                        Rfc3161AnchorTarget.RESPONSE_CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            AnchorReceipt receipt = new Rfc3161AnchorTarget(url, null, "NONE").anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status(),
                    "the response is well-formed and passes validate(); only signature "
                            + "verification distinguishes it from an honest token");
            assertTrue(receipt.failureReason().contains("signature"), receipt.failureReason());
        }

        /** A self-signed certificate with the critical timeStamping EKU RFC 3161 §2.3 demands. */
        private java.security.cert.X509Certificate selfSignedTsaCert(
                java.security.KeyPair kp, String dn) throws Exception {
            org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name(dn);
            org.bouncycastle.cert.X509v3CertificateBuilder builder =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            subject, BigInteger.ONE,
                            new java.util.Date(System.currentTimeMillis() - 86_400_000L),
                            new java.util.Date(System.currentTimeMillis() + 86_400_000L),
                            subject, kp.getPublic());
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                    new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
            return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                    builder.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                            "SHA256withRSA").build(kp.getPrivate())));
        }
    }

    // ---------------------------------------------------------------- receipt semantics

    @Nested
    @DisplayName("receipt semantics")
    class ReceiptSemantics {

        @Test
        @DisplayName("a failed anchor never supports an claim")
        void failedAnchorClaimsNothing() {
            AnchorReceipt receipt = AnchorReceipt.failed(
                    AnchorKind.RFC3161_TSA, DIGEST, java.time.Instant.now(), "unreachable");
            assertNull(receipt.anchoredAt(), "no anchor time may be asserted for a failure");
        }

        @Test
        @DisplayName("a pending anchor asserts no time and no independence")
        void pendingAssertsNoTime() {
            AnchorReceipt receipt = AnchorReceipt.pending(
                    AnchorKind.OPENTIMESTAMPS, DIGEST, java.time.Instant.now(),
                    new byte[]{1, 2, 3}, "d", java.util.Map.of());

            assertEquals(AnchorStatus.PENDING, receipt.status());
            assertNull(receipt.anchoredAt(),
                    "filling in 'now' would state a time the proof does not support");
        }

        @Test
        @DisplayName("the kind carries time semantics — and deliberately not independence")
        void ladderSemantics() {
            // There is no independentOfOperator() to assert. Four review rounds established that
            // this code cannot determine organizational independence, so no flag claims to.
            assertEquals(AnchorKind.TimeSemantics.NOT_A_TIME_PROOF,
                    AnchorKind.ATLAS_CATALOG.timeSemantics());
            assertEquals(AnchorKind.TimeSemantics.UPPER_BOUND_ONLY,
                    AnchorKind.OPENTIMESTAMPS.timeSemantics(),
                    "OpenTimestamps proves 'no later than', never a point in time");
            assertEquals(AnchorKind.TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY,
                    AnchorKind.RFC3161_TSA.timeSemantics());
        }

        @Test
        @DisplayName("proof bytes are copied in and out, so a receipt cannot be edited afterwards")
        void proofIsDefensivelyCopied() {
            byte[] original = {1, 2, 3};
            AnchorReceipt receipt = AnchorReceipt.confirmed(
                    AnchorKind.RFC3161_TSA, DIGEST, java.time.Instant.now(),
                    java.time.Instant.now(), original, "d", java.util.Map.of("k", "v"), AnchorKind.TimeSemantics.BIDIRECTIONAL_WITHIN_ACCURACY);

            original[0] = 9;
            assertEquals(1, receipt.proof()[0], "mutating the source must not alter the receipt");

            receipt.proof()[0] = 9;
            assertEquals(1, receipt.proof()[0], "mutating a returned copy must not alter the receipt");

            assertThrows(UnsupportedOperationException.class, () -> receipt.attributes().put("x", "y"));
        }
    }

    @Nested
    class RevocationCollection {

        private java.security.KeyPair keyPair;
        private java.security.cert.X509Certificate certificate;

        private String escapeBefore;

        @org.junit.jupiter.api.BeforeEach
        void allowTheLocalStub() {
            // The CRL fetch rides the send-time-pinned path, which refuses loopback — as it
            // should in production. The stub below IS loopback, so the test-only escape the
            // adapter tests use is set here and put back after (R65). Put back, not cleared:
            // a JVM started with the property set would otherwise leave this class with it
            // off (Codex review, P3).
            escapeBefore = System.getProperty("nemaki.ingest.allowLocalhost");
            System.setProperty("nemaki.ingest.allowLocalhost", "true");
        }

        @org.junit.jupiter.api.AfterEach
        void putTheEscapeBack() {
            if (escapeBefore == null) {
                System.clearProperty("nemaki.ingest.allowLocalhost");
            } else {
                System.setProperty("nemaki.ingest.allowLocalhost", escapeBefore);
            }
        }

        /** A token whose signer certificate names {@code crlUrl} as its CRL distribution point. */
        private org.bouncycastle.tsp.TimeStampToken tokenWithDistributionPoint(String crlUrl)
                throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            keyPair = kpg.generateKeyPair();
            org.bouncycastle.asn1.x500.X500Name subject =
                    new org.bouncycastle.asn1.x500.X500Name("CN=Test TSA with CRL DP");
            java.util.Date from = new java.util.Date(System.currentTimeMillis() - 86_400_000L);
            java.util.Date to = new java.util.Date(System.currentTimeMillis() + 86_400_000L);
            org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            subject, BigInteger.TWO, from, to, subject, keyPair.getPublic());
            certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                    new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping));
            org.bouncycastle.asn1.x509.GeneralName uri = new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier, crlUrl);
            org.bouncycastle.asn1.x509.DistributionPoint point =
                    new org.bouncycastle.asn1.x509.DistributionPoint(
                            new org.bouncycastle.asn1.x509.DistributionPointName(
                                    new org.bouncycastle.asn1.x509.GeneralNames(uri)), null, null);
            certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.cRLDistributionPoints,
                    false, new org.bouncycastle.asn1.x509.CRLDistPoint(
                            new org.bouncycastle.asn1.x509.DistributionPoint[] { point }));
            org.bouncycastle.operator.ContentSigner signer =
                    new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                            .build(keyPair.getPrivate());
            certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .getCertificate(certBuilder.build(signer));

            org.bouncycastle.tsp.TimeStampTokenGenerator tokenGen =
                    new org.bouncycastle.tsp.TimeStampTokenGenerator(
                            new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
                                    .build("SHA256withRSA", keyPair.getPrivate(), certificate),
                            new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                                    .get(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                            new org.bouncycastle.asn1.ASN1ObjectIdentifier(
                                                    "2.16.840.1.101.3.4.2.1"))),
                            new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4.1"));
            tokenGen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(
                    java.util.List.of(certificate)));
            org.bouncycastle.tsp.TimeStampRequestGenerator reqGen =
                    new org.bouncycastle.tsp.TimeStampRequestGenerator();
            reqGen.setCertReq(true);
            org.bouncycastle.tsp.TimeStampRequest request = reqGen.generate(
                    new org.bouncycastle.asn1.ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"),
                    new byte[32], BigInteger.ONE);
            return tokenGen.generate(request, BigInteger.ONE, new java.util.Date());
        }

        private Rfc3161AnchorTarget collecting() {
            Rfc3161AnchorTarget target = new Rfc3161AnchorTarget(null, null, null);
            target.setCollectRevocationAtIssuance(true);
            return target;
        }

        /**
         * A stub reached by NAME, so the fetch takes the HTTP pinning branch production takes:
         * the URI rewritten to the resolved literal and the {@code Host} header carried over,
         * which needs the {@code jdk.httpclient.allowRestrictedHeaders=host} flag surefire
         * sets. With the escape narrowed to loopback, this branch is no longer skipped for a
         * local stub (subagent review, P2-4: until then no test in the suite sent through it).
         * That the rewrite itself happens for a loopback NAME under the escape is measured
         * directly in {@code TheTestEscapeIsLoopbackOnlyTest}; here, the fetch succeeding
         * through it is the claim.
         */
        @Test
        @DisplayName("with collection on, the signer's CRL is captured through the pinned path")
        void materialIsCapturedThroughThePinnedPath() throws Exception {
            byte[] crl = "-- a CRL, as far as this test is concerned --".getBytes(StandardCharsets.UTF_8);
            java.util.concurrent.atomic.AtomicReference<String> hostSeen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // Bound by the same name the URL carries, so the stub sits on whichever loopback
            // address "localhost" resolves to first — the one the pin picks.
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/ca.crl", exchange -> {
                hostSeen.set(exchange.getRequestHeaders().getFirst("Host"));
                exchange.sendResponseHeaders(200, crl.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(crl);
                }
            });
            server.start();
            String url = "http://localhost:" + server.getAddress().getPort() + "/ca.crl";

            RevocationMaterial material = collecting()
                    .collectRevocationMaterial(tokenWithDistributionPoint(url));

            assertEquals(RevocationMaterial.Status.CAPTURED, material.status(), material.detail());
            assertArrayEquals(crl, material.der());
            assertEquals(url, material.source());
            assertEquals("localhost:" + server.getAddress().getPort(), hostSeen.get(),
                    "the stub saw Host " + hostSeen.get() + ". The pin rewrites the URI to the "
                            + "literal and carries the original name in Host; a fetch that "
                            + "reached the stub under another Host did not take that branch");
        }

        /**
         * The body has its own clock. {@code HttpRequest.timeout} ends at the headers, and a
         * distribution point that sends one byte and stops would otherwise park the anchoring
         * — and the admin request waiting on it — for ever (both reviews, 2026-09-22).
         */
        @Test
        @DisplayName("a distribution point that starts answering and stops is UNAVAILABLE within the body budget")
        void aStalledBodyIsUnavailableWithinTheBudget() throws Exception {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            String url = startServer("/slow.crl", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                OutputStream os = exchange.getResponseBody();
                os.write(0x30);
                os.flush();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    os.close();
                } catch (IOException ignored) {
                    // the client is gone by then, which is the point
                }
            });
            org.bouncycastle.tsp.TimeStampToken token = tokenWithDistributionPoint(url);
            Rfc3161AnchorTarget target = collecting();
            target.crlBodyBudget = java.time.Duration.ofMillis(500);
            try {
                // Preemptive: if the budget is not on the path, the fetch never returns, and a
                // test that waits for it never fails. This one fails on its own clock.
                RevocationMaterial material = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(10), () -> target.collectRevocationMaterial(token));

                assertEquals(RevocationMaterial.Status.UNAVAILABLE, material.status(),
                        "a stalled body was reported as " + material.status() + ". The material "
                                + "is one byte of a CRL; shipping it as CAPTURED would be a "
                                + "truncated presence, and NOT_ATTEMPTED would deny the fetch "
                                + "was made");
                assertTrue(material.detail().contains("CRL_READ_TIMEOUT"), material.detail());
            } finally {
                release.countDown();
            }
        }

        /**
         * "Not now" is an answer. The retry loop the connectors use sleeps 2, 4 and 8 seconds
         * (or {@code Retry-After}, up to 120 s each) before giving up, which is right for a
         * poll and wrong on the request thread this anchoring runs on (subagent review).
         */
        @Test
        @DisplayName("a 503 from the distribution point is UNAVAILABLE at once, not retried on the request thread")
        void notNowIsNotRetried() throws Exception {
            String url = startServer("/busy.crl", exchange -> {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            org.bouncycastle.tsp.TimeStampToken token = tokenWithDistributionPoint(url);
            Rfc3161AnchorTarget target = collecting();

            // The first retry sleep alone is 2 s; a fetch that takes longer than this took it.
            RevocationMaterial material = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofMillis(1500), () -> target.collectRevocationMaterial(token));

            assertEquals(RevocationMaterial.Status.UNAVAILABLE, material.status(), material.detail());
            assertTrue(material.detail().contains("503"), material.detail());
        }

        @Test
        @DisplayName("a CRL over the cap is UNAVAILABLE, never a truncated capture")
        void tooLargeIsUnavailable() throws Exception {
            byte[] huge = new byte[(int) Rfc3161AnchorTarget.MAX_CRL_BYTES + 1];
            String url = startServer("/big.crl", exchange -> {
                exchange.sendResponseHeaders(200, huge.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(huge);
                }
            });
            RevocationMaterial material = collecting()
                    .collectRevocationMaterial(tokenWithDistributionPoint(url));

            assertEquals(RevocationMaterial.Status.UNAVAILABLE, material.status(),
                    "material over the cap was reported as " + material.status()
                            + ". A truncated CRL shipped as CAPTURED is an absence dressed as a "
                            + "presence — the verifier would evaluate the wrong bytes");
            assertTrue(material.detail().contains("CRL_TOO_LARGE"), material.detail());
        }

        @Test
        @DisplayName("with the localhost escape off, the loopback distribution point is refused — the guard is on the path")
        void theGuardIsOnThePath() throws Exception {
            String url = startServer("/ca.crl", exchange -> {
                exchange.sendResponseHeaders(200, 1);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(new byte[] { 0x30 });
                }
            });
            org.bouncycastle.tsp.TimeStampToken token = tokenWithDistributionPoint(url);
            System.clearProperty("nemaki.ingest.allowLocalhost");

            RevocationMaterial material = collecting().collectRevocationMaterial(token);

            // Refused at send time by the pinned path, and reported as UNAVAILABLE — asked, and
            // refused to ask a loopback address — never as NOT_ATTEMPTED and never captured.
            assertEquals(RevocationMaterial.Status.UNAVAILABLE, material.status(),
                    "a loopback distribution point was " + material.status() + " with the "
                            + "localhost escape off. If it was CAPTURED, the fetch is not on the "
                            + "guarded path");
            assertTrue(material.detail().contains("SecurityException"), material.detail());
        }
    }
}
