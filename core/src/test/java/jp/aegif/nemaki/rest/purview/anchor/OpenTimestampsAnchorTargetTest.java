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
            assertFalse(receipt.preservesIndependentlyCheckableArtifact(),
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
        @DisplayName("still pending: the receipt is returned untouched")
        void stillPendingIsNotAFailure() throws Exception {
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"PENDING\",\"changed\":false,\"proofBase64\":\""
                            + PROOF_B64 + "\",\"exitCode\":1}",
                    "/info", "{\"complete\":false,\"digestMatches\":true,\"pending\":true}"));
            AnchorReceipt pending = pendingReceipt(url);

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pending);

            assertSame(pending, result);
            assertEquals(AnchorStatus.PENDING, result.status());
        }

        @Test
        @DisplayName("complete but unverified stays PENDING and says why")
        void completeButUnverifiedIsNotConfirmed() throws Exception {
            // The shipped configuration has no Bitcoin node, so a complete proof cannot be
            // checked here. CONFIRMED is reserved for evidence somebody verified; printing
            // "confirmed" for a proof nobody checked is the overclaim this design exists to stop.
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"PENDING\",\"changed\":false,\"proofBase64\":\""
                            + PROOF_B64 + "\"}",
                    "/info", "{\"complete\":true,\"digestMatches\":true,\"bitcoinBlockHeight\":921447}",
                    "/verify", "{\"verified\":false,\"stderr\":\"Could not connect to local Bitcoin node\"}"));

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pendingReceipt(url));

            assertEquals(AnchorStatus.PENDING, result.status());
            assertEquals("true", result.attributes().get("proofComplete"));
            assertEquals("921447", result.attributes().get("bitcoinBlockHeight"));
            assertEquals("false", result.attributes().get("chainVerifiedLocally"));
            assertTrue(result.preservesIndependentlyCheckableArtifact(),
                    "we did not check it — but we kept the complete proof, and keeping the thing "
                            + "an auditor can check is precisely what this deployment can honestly "
                            + "claim to have done");
        }

        @Test
        @DisplayName("a proof for a DIFFERENT digest is refused, however complete it is")
        void proofForAnotherDigestIsRefused() throws Exception {
            // A perfectly valid, fully confirmed proof for someone else's document says nothing
            // about ours. Without this binding it would be promoted on our receipt.
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"CONFIRMED\",\"changed\":true,\"proofBase64\":\""
                            + PROOF_B64 + "\"}",
                    "/info", "{\"complete\":true,\"digestMatches\":false,"
                            + "\"error\":\"this proof is for a different digest\"}"));
            AnchorReceipt pending = pendingReceipt(url);

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pending);

            assertSame(pending, result);
            assertEquals(AnchorStatus.PENDING, result.status());
        }

        @Test
        @DisplayName("verified against Bitcoin: CONFIRMED and independently checkable")
        void verifiedProofIsConfirmed() throws Exception {
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"CONFIRMED\",\"changed\":false,\"proofBase64\":\""
                            + PROOF_B64 + "\"}",
                    "/info", "{\"complete\":true,\"digestMatches\":true,\"bitcoinBlockHeight\":921447}",
                    "/verify", "{\"verified\":true,\"attestation\":\"Bitcoin block 921447 attests "
                            + "existence as of 2026-07-29\"}"));

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pendingReceipt(url));

            assertEquals(AnchorStatus.CONFIRMED, result.status());
            assertEquals("true", result.attributes().get("chainVerifiedLocally"));
            assertTrue(result.preservesIndependentlyCheckableArtifact());
            assertEquals(AnchorKind.TimeSemantics.UPPER_BOUND_ONLY, result.timeSemantics());
            assertNull(result.anchoredAt());
        }

        @Test
        @DisplayName("partial progress is kept, not thrown away")
        void partialUpgradeIsPreserved() throws Exception {
            byte[] partial = "partly-upgraded".getBytes(StandardCharsets.UTF_8);
            String url = start(java.util.Map.of(
                    "/upgrade", "{\"status\":\"PENDING\",\"changed\":true,\"proofBase64\":\""
                            + Base64.getEncoder().encodeToString(partial) + "\"}",
                    "/info", "{\"complete\":false,\"digestMatches\":true,\"pending\":true}"));

            AnchorReceipt result = new OpenTimestampsAnchorTarget(url).upgrade(pendingReceipt(url));

            assertEquals(AnchorStatus.PENDING, result.status());
            org.junit.jupiter.api.Assertions.assertArrayEquals(partial, result.proof(),
                    "discarding the newer bytes would rely on calendars still holding the same "
                            + "intermediate data on the next attempt");
            assertEquals("partial", result.attributes().get("upgraded"));
        }

        @Test
        @DisplayName("a confirmed receipt is left alone")
        void confirmedIsNotUpgradedAgain() throws Exception {
            String url = start(java.util.Map.of());
            AnchorReceipt confirmed = AnchorReceipt.confirmed(AnchorKind.OPENTIMESTAMPS, DIGEST,
                    java.time.Instant.now(), null, PROOF, "d", java.util.Map.of(),
                    true, AnchorKind.TimeSemantics.UPPER_BOUND_ONLY);

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
