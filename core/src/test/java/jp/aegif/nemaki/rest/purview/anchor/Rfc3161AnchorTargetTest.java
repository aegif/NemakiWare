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
            assertFalse(receipt.supportsIndependenceClaim());

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

    // ---------------------------------------------------------------- receipt semantics

    @Nested
    @DisplayName("receipt semantics")
    class ReceiptSemantics {

        @Test
        @DisplayName("a failed anchor never supports an independence claim")
        void failedAnchorClaimsNothing() {
            AnchorReceipt receipt = AnchorReceipt.failed(
                    AnchorKind.RFC3161_TSA, DIGEST, java.time.Instant.now(), "unreachable");

            assertFalse(receipt.supportsIndependenceClaim(),
                    "the target is independent, but a failed attempt is not evidence");
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
            assertFalse(receipt.supportsIndependenceClaim(),
                    "a commitment nobody can verify yet is not yet evidence");
        }

        @Test
        @DisplayName("the ladder's independence and time semantics are carried on the kind")
        void ladderSemantics() {
            assertFalse(AnchorKind.ATLAS_CATALOG.independentOfOperator());
            assertTrue(AnchorKind.OPENTIMESTAMPS.independentOfOperator());
            assertTrue(AnchorKind.RFC3161_TSA.independentOfOperator());

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
                    java.time.Instant.now(), original, "d", java.util.Map.of("k", "v"));

            original[0] = 9;
            assertEquals(1, receipt.proof()[0], "mutating the source must not alter the receipt");

            receipt.proof()[0] = 9;
            assertEquals(1, receipt.proof()[0], "mutating a returned copy must not alter the receipt");

            assertThrows(UnsupportedOperationException.class, () -> receipt.attributes().put("x", "y"));
        }
    }
}
