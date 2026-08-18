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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * OpenTimestamps — rung 2 of the trust ladder, reached through the sidecar.
 *
 * <p>This class deliberately contains no OpenTimestamps protocol logic. The {@code .ots} format
 * has no specification document — the Python implementation IS the specification — and rung 2's
 * entire value is that a third party can verify the proof <em>without trusting us</em>. A Java
 * reimplementation would put our reading of the format where the format belongs. So the sidecar
 * runs the official client and this class is an HTTP call. See {@code docker/ots/server.py}.
 *
 * <h3>Pending is not failure</h3>
 *
 * <p>A freshly stamped commitment is not verifiable until Bitcoin confirms it, which takes
 * hours (measured: calendars aggregate at intervals from roughly one hour to nine). Every
 * successful stamp therefore returns {@link AnchorStatus#PENDING}, and callers are expected to
 * {@link #upgrade} later rather than stamp again. Reporting CONFIRMED at stamp time would
 * assert a proof nobody can yet check; treating PENDING as an error would make a working anchor
 * look broken and invite pointless re-stamping.
 *
 * <h3>What leaves the deployment</h3>
 *
 * <p>Only a digest, and not even that in the clear: the client blinds it as
 * {@code SHA256(digest || 16 random bytes)} before it reaches a calendar, so a calendar operator
 * learns neither the document nor the digest being anchored. The sidecar rejects anything that
 * is not a 64-character hex digest, so that boundary holds even if a caller here is wrong.
 */
public class OpenTimestampsAnchorTarget implements AnchorTarget {

    private static final Logger logger = LoggerFactory.getLogger(OpenTimestampsAnchorTarget.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** Stamping talks to several remote calendars; the sidecar caps its own subprocess too. */
    private static final int READ_TIMEOUT_MS = 90_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final String sidecarUrl;

    /** @param sidecarUrl base URL of the sidecar, or null/blank to leave rung 2 unconfigured */
    public OpenTimestampsAnchorTarget(String sidecarUrl) {
        this.sidecarUrl = sidecarUrl == null || sidecarUrl.isBlank()
                ? null : sidecarUrl.trim().replaceAll("/+$", "");
    }

    @Override
    public AnchorKind kind() {
        return AnchorKind.OPENTIMESTAMPS;
    }

    @Override
    public boolean isConfigured() {
        return sidecarUrl != null;
    }

    @Override
    public AnchorReceipt anchor(String hexDigest) {
        // Validate first, even when unconfigured: a caller bug should surface now rather than
        // on the day someone switches this rung on.
        Rfc3161AnchorTarget.decodeSha256Hex(hexDigest);
        if (!isConfigured()) {
            return AnchorReceipt.notConfigured(kind(), hexDigest);
        }
        Instant attemptedAt = Instant.now();
        try {
            JsonNode response = post("/stamp", "{\"digest\":\"" + hexDigest + "\"}");
            String status = response.path("status").asString("");
            if (!"PENDING".equals(status) && !"CONFIRMED".equals(status)) {
                return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                        "sidecar reported " + status + ": " + response.path("error").asString(""));
            }
            byte[] proof = Base64.getDecoder().decode(response.path("proofBase64").asString(""));

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("sidecarUrl", sidecarUrl);
            attrs.put("calendars", response.path("calendars").toString());
            attrs.put("noncePolicy", "SHA256(digest || 16 random bytes)");
            attrs.put("upgraded", "false");

            logger.info("OpenTimestamps commitment created for {} ({} bytes, pending confirmation)",
                    hexDigest, proof.length);

            // Always pending at stamp time — see the class comment.
            return AnchorReceipt.pending(kind(), hexDigest, attemptedAt, proof,
                    Rfc3161AnchorTarget.sha256Hex(proof), attrs);

        } catch (Exception e) {
            logger.warn("OpenTimestamps anchoring failed via {}: {}", sidecarUrl, e.toString());
            return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Ask the sidecar whether Bitcoin has confirmed the commitment yet.
     *
     * <p>Returns the receipt unchanged while it is still pending, which is the common case for
     * hours and must not be logged or reported as a failure. The upgraded proof replaces the
     * pending one only when the sidecar says the bytes actually changed — the client exits
     * non-zero for "nothing to upgrade yet", so exit status alone cannot be trusted to mean
     * failure here.
     */
    @Override
    public AnchorReceipt upgrade(AnchorReceipt pending) {
        if (pending == null || pending.status() != AnchorStatus.PENDING
                || pending.kind() != kind() || pending.proof() == null || !isConfigured()) {
            return pending;
        }
        try {
            byte[] current = pending.proof();
            JsonNode upgradeResponse = post("/upgrade", "{\"proofBase64\":\""
                    + Base64.getEncoder().encodeToString(current) + "\"}");
            byte[] latest = Base64.getDecoder().decode(
                    upgradeResponse.path("proofBase64").asString(""));
            boolean changed = upgradeResponse.path("changed").asBoolean(false);

            // Ask the proof what it says ABOUT THIS DIGEST. "Unchanged" also describes an
            // already-complete proof, so completeness is a property of the proof rather than of
            // whether upgrade altered bytes. The sidecar binds the proof to the digest: a valid
            // complete proof for some other document says nothing about ours.
            byte[] candidate = changed && latest.length > 0 ? latest : current;
            JsonNode info = post("/info", "{\"digest\":\"" + pending.anchoredDigest()
                    + "\",\"proofBase64\":\"" + Base64.getEncoder().encodeToString(candidate) + "\"}");

            if (!info.path("digestMatches").asBoolean(false)) {
                logger.warn("OpenTimestamps proof does not belong to digest {}: {}",
                        pending.anchoredDigest(), info.path("error").asString(""));
                return pending;
            }

            Map<String, String> attrs = new LinkedHashMap<>(pending.attributes());
            if (changed && latest.length > 0) {
                // Progress that may not be completion: keep the newer bytes rather than relying
                // on calendars still holding the same intermediate data next time.
                attrs.put("upgraded", info.path("complete").asBoolean(false) ? "true" : "partial");
            }

            if (!info.path("complete").asBoolean(false)) {
                // Nothing new to record: hand back the same receipt rather than an equal-looking
                // copy, so a caller can tell "no progress" from "progress" by identity.
                if (!changed || latest.length == 0) {
                    return pending;
                }
                return AnchorReceipt.pending(kind(), pending.anchoredDigest(), pending.attemptedAt(),
                        candidate, Rfc3161AnchorTarget.sha256Hex(candidate), attrs);
            }

            // The proof now CONTAINS a Bitcoin attestation. That is not the same as having
            // checked it: confirming it means resolving the named block, which needs a Bitcoin
            // node this container deliberately does not ship. So a complete-but-unchecked proof
            // stays PENDING and says why. CONFIRMED is reserved for evidence somebody verified
            // (external review, 3.4) — a report that prints "confirmed" for an unverified proof
            // is the overclaim this whole design exists to prevent.
            attrs.put("proofComplete", "true");
            attrs.put("bitcoinBlockHeight", info.path("bitcoinBlockHeight").asString(""));
            attrs.put("chainVerifiedLocally", "false");
            attrs.put("chainVerificationProcedure",
                    "ots verify (requires a Bitcoin node; this deployment runs none)");

            JsonNode verified = post("/verify", "{\"digest\":\"" + pending.anchoredDigest()
                    + "\",\"proofBase64\":\"" + Base64.getEncoder().encodeToString(candidate) + "\"}");
            if (!verified.path("verified").asBoolean(false)) {
                logger.info("OpenTimestamps proof for {} is complete (Bitcoin block {}) but "
                                + "unverified here; a verifier with a Bitcoin node can check it",
                        pending.anchoredDigest(), info.path("bitcoinBlockHeight").asString(""));
                return AnchorReceipt.pending(kind(), pending.anchoredDigest(), pending.attemptedAt(),
                        candidate, Rfc3161AnchorTarget.sha256Hex(candidate), attrs);
            }

            attrs.put("chainVerifiedLocally", "true");
            attrs.put("attestation", verified.path("attestation").asString(""));
            logger.info("OpenTimestamps proof for {} verified against Bitcoin: {}",
                    pending.anchoredDigest(), verified.path("attestation").asString(""));

            return AnchorReceipt.confirmed(kind(), pending.anchoredDigest(), pending.attemptedAt(),
                    null, candidate, Rfc3161AnchorTarget.sha256Hex(candidate), attrs,
                    // Verified here, and re-verifiable by anyone with block headers without us.
                    true, AnchorKind.TimeSemantics.UPPER_BOUND_ONLY);

        } catch (Exception e) {
            logger.warn("OpenTimestamps upgrade failed for {}: {}",
                    pending.anchoredDigest(), e.toString());
            return pending;
        }
    }

    private JsonNode post(String path, String jsonBody) throws IOException {
        URL url = URI.create(sidecarUrl + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            byte[] responseBytes = stream == null ? new byte[0]
                    : stream.readNBytes(MAX_RESPONSE_BYTES);
            if (code >= 400) {
                throw new IOException("sidecar answered HTTP " + code + ": "
                        + new String(responseBytes, StandardCharsets.UTF_8));
            }
            return MAPPER.readTree(responseBytes);
        } finally {
            conn.disconnect();
        }
    }
}
