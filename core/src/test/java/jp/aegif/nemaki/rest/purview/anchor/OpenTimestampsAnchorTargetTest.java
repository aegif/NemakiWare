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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenTimestamps client against a stand-in for the sidecar.
 *
 * <p>What is worth testing here is not the protocol — the sidecar owns that — but the two
 * judgements this class makes about evidence:
 *
 * <ul>
 *   <li>a fresh commitment is PENDING and asserts <b>no</b> time and <b>no</b> independence,
 *       however independent its destination;</li>
 *   <li>an upgraded proof is only promoted to CONFIRMED if it actually <b>verifies</b>. Bytes
 *       that changed but do not verify are not evidence, and promoting them would put an
 *       unverifiable proof into a report that says "confirmed".</li>
 * </ul>
 *
 * <p>The live sidecar was exercised separately (real calendars, a real 735-byte proof, a real
 * "pending confirmation" verify). That proves interoperability; these tests pin the judgements.
 */
class OpenTimestampsAnchorTargetTest {

    private static final String DIGEST =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final byte[] PROOF = "pretend-ots-proof".getBytes(StandardCharsets.UTF_8);
    private static final String PROOF_B64 = Base64.getEncoder().encodeToString(PROOF);

    private HttpServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

    /** Answers each path with a canned JSON body, recording which paths were called. */
    private String start(java.util.Map<String, String> responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            String body = responses.get(exchange.getRequestURI().getPath());
            int code = body == null ? 404 : 200;
            byte[] out = (body == null ? "{\"error\":\"no stub\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a blank sidecar URL leaves the rung unconfigured")
        void blankIsNotConfigured() {
            OpenTimestampsAnchorTarget target = new OpenTimestampsAnchorTarget("  ");
            assertFalse(target.isConfigured());
            assertEquals(AnchorStatus.NOT_CONFIGURED, target.anchor(DIGEST).status());
        }

        @Test
        @DisplayName("a malformed digest throws even when the rung is switched off")
        void malformedDigestThrowsWhenUnconfigured() {
            assertThrows(IllegalArgumentException.class,
                    () -> new OpenTimestampsAnchorTarget(null).anchor("short"),
                    "a caller bug must surface now, not on the day this rung is enabled");
        }
    }

    @Nested
    @DisplayName("stamping")
    class Stamping {

        @Test
        @DisplayName("a fresh commitment is PENDING and asserts neither time nor independence")
        void freshCommitmentAssertsNothingYet() throws Exception {
            String url = start(java.util.Map.of("/stamp",
                    "{\"status\":\"PENDING\",\"proofBase64\":\"" + PROOF_B64
                            + "\",\"calendars\":[\"https://a.pool.opentimestamps.org\"]}"));

            AnchorReceipt receipt = new OpenTimestampsAnchorTarget(url).anchor(DIGEST);

            assertEquals(AnchorStatus.PENDING, receipt.status());
            assertNull(receipt.anchoredAt(),
                    "Bitcoin has not confirmed it, so no time may be asserted");
            assertFalse(receipt.supportsIndependenceClaim(),
                    "the destination is independent, but an unconfirmed commitment is not yet evidence");
            assertNotNull(receipt.proof());
            assertEquals("false", receipt.attributes().get("upgraded"));
            assertEquals("SHA256(digest || 16 random bytes)", receipt.attributes().get("noncePolicy"),
                    "the blinding policy belongs in the record: it is why calendars learn nothing");
        }

        @Test
        @DisplayName("a sidecar failure is recorded, not thrown")
        void sidecarFailureIsRecorded() throws Exception {
            String url = start(java.util.Map.of("/stamp",
                    "{\"status\":\"FAILED\",\"error\":\"ots stamp exited 1: no calendar reachable\"}"));

            AnchorReceipt receipt = new OpenTimestampsAnchorTarget(url).anchor(DIGEST);

            assertEquals(AnchorStatus.FAILED, receipt.status());
            assertTrue(receipt.failureReason().contains("no calendar reachable"), receipt.failureReason());
        }

        @Test
        @DisplayName("an unreachable sidecar fails the anchor, not the caller")
        void unreachableSidecar() {
            AnchorReceipt receipt =
                    new OpenTimestampsAnchorTarget("http://127.0.0.1:1").anchor(DIGEST);
            assertEquals(AnchorStatus.FAILED, receipt.status());
        }
    }

    @Nested
    @DisplayName("upgrading (where a proof becomes evidence, or does not)")
    class Upgrading {

        private AnchorReceipt pendingReceipt(String url) throws Exception {
            return AnchorReceipt.pending(AnchorKind.OPENTIMESTAMPS, DIGEST,
                    java.time.Instant.now(), PROOF, "d", java.util.Map.of("upgraded", "false"));
        }

        @Test
        @DisplayName("still pending: the receipt is returned untouched and nothing is re-verified")
        void stillPendingIsNotAFailure() throws Exception {
            String url = start(java.util.Map.of("/upgrade",
                    "{\"status\":\"PENDING\",\"changed\":false,\"proofBase64\":\"" + PROOF_B64
                            + "\",\"exitCode\":1}"));
            AnchorReceipt pending = pendingReceipt(url);

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pending);

            assertSame(pending, result, "nothing changed, so nothing should be rebuilt");
            assertFalse(paths.contains("/verify"),
                    "verifying an unchanged proof would be a pointless round trip to the calendars");
        }

        @Test
        @DisplayName("upgraded AND verified: promoted to CONFIRMED with the attestation recorded")
        void upgradedAndVerified() throws Exception {
            byte[] upgraded = "upgraded-ots-proof".getBytes(StandardCharsets.UTF_8);
            String upgradedB64 = Base64.getEncoder().encodeToString(upgraded);
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"CONFIRMED\",\"changed\":true,\"proofBase64\":\""
                            + upgradedB64 + "\"}",
                    "/verify", "{\"verified\":true,\"pending\":false,\"attestation\":"
                            + "\"Bitcoin block 921447 attests existence as of 2026-07-29\"}"));

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pendingReceipt(url));

            assertEquals(AnchorStatus.CONFIRMED, result.status());
            assertTrue(result.supportsIndependenceClaim(),
                    "a confirmed, independently verifiable proof is exactly what rung 2 is for");
            assertEquals("true", result.attributes().get("upgraded"));
            assertTrue(result.attributes().get("attestation").contains("921447"));
            assertArrayEqualsHelper(upgraded, result.proof());
            assertNull(result.anchoredAt(),
                    "the proof names a Bitcoin block; turning that into an instant is the "
                            + "verifier's job, and inventing one here would assert more than the proof does");
        }

        @Test
        @DisplayName("upgraded but NOT verified: stays pending rather than becoming false evidence")
        void upgradedButUnverifiedIsNotPromoted() throws Exception {
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"CONFIRMED\",\"changed\":true,\"proofBase64\":\""
                            + Base64.getEncoder().encodeToString("garbage".getBytes(StandardCharsets.UTF_8))
                            + "\"}",
                    "/verify", "{\"verified\":false,\"pending\":false,"
                            + "\"stderr\":\"Bad timestamp: mismatch\"}"));
            AnchorReceipt pending = pendingReceipt(url);

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pending);

            assertSame(pending, result,
                    "bytes that changed but do not verify are not evidence; promoting them would "
                            + "put an uncheckable proof behind the word CONFIRMED");
            assertEquals(AnchorStatus.PENDING, result.status());
        }

        @Test
        @DisplayName("a confirmed receipt is left alone")
        void confirmedIsNotUpgradedAgain() throws Exception {
            String url = start(java.util.Map.of());
            AnchorReceipt confirmed = AnchorReceipt.confirmed(AnchorKind.OPENTIMESTAMPS, DIGEST,
                    java.time.Instant.now(), null, PROOF, "d", java.util.Map.of());

            assertSame(confirmed, new OpenTimestampsAnchorTarget(url).upgrade(confirmed));
            assertTrue(paths.isEmpty(), "no request should be made for an already-confirmed proof");
        }

        @Test
        @DisplayName("a receipt from another rung is not touched")
        void otherKindsAreIgnored() throws Exception {
            String url = start(java.util.Map.of());
            AnchorReceipt tsa = AnchorReceipt.pending(AnchorKind.RFC3161_TSA, DIGEST,
                    java.time.Instant.now(), PROOF, "d", java.util.Map.of());

            assertSame(tsa, new OpenTimestampsAnchorTarget(url).upgrade(tsa));
            assertTrue(paths.isEmpty());
        }
    }

    private static void assertArrayEqualsHelper(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
