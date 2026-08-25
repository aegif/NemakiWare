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

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes {@link AnchorReceipt} as a plain document (P2-0).
 *
 * <h2>Why it lives here and not next to the store</h2>
 *
 * <p>{@code AnchorReceipt.confirmed} is package-private so that only a rung's own implementation
 * can mint a confirmed receipt — a restriction worth keeping, because "confirmed" is the word
 * the whole evidence report leans on. Rehydrating a stored receipt needs that factory, so the
 * codec belongs inside the package rather than the restriction being widened for it.
 *
 * <h2>What round-tripping must not do</h2>
 *
 * <p>Reload must not be able to strengthen a receipt. A stored row whose status says CONFIRMED
 * but which carries no proof is not a confirmed anchor — it is a corrupt row, and the honest
 * reading is FAILED with the reason said out loud. Reconstructing it as CONFIRMED would let a
 * database edit manufacture an anchor that no external party ever made.
 */
public final class AnchorReceiptCodec {

    private AnchorReceiptCodec() {
    }

    public static Map<String, Object> toDocument(AnchorReceipt receipt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", receipt.kind().name());
        doc.put("status", receipt.status().name());
        doc.put("timeSemantics", receipt.timeSemantics().name());
        doc.put("anchoredDigest", receipt.anchoredDigest());
        doc.put("attemptedAt", receipt.attemptedAt() == null ? null
                : receipt.attemptedAt().toString());
        doc.put("anchoredAt", receipt.anchoredAt() == null ? null
                : receipt.anchoredAt().toString());
        byte[] proof = receipt.proof();
        doc.put("proofBase64", proof == null ? null : Base64.getEncoder().encodeToString(proof));
        doc.put("proofDigest", receipt.proofDigest());
        doc.put("attributes", receipt.attributes());
        doc.put("failureReason", receipt.failureReason());
        return doc;
    }

    @SuppressWarnings("unchecked")
    public static AnchorReceipt fromDocument(Map<String, Object> doc) {
        if (doc == null) {
            return null;
        }
        AnchorKind kind = AnchorKind.valueOf(String.valueOf(doc.get("kind")));
        AnchorStatus status = AnchorStatus.valueOf(String.valueOf(doc.get("status")));
        String digest = (String) doc.get("anchoredDigest");
        Instant attemptedAt = instant(doc.get("attemptedAt"));
        byte[] proof = decode(doc.get("proofBase64"));
        Map<String, String> attributes = doc.get("attributes") instanceof Map<?, ?> m
                ? (Map<String, String>) m : Map.of();

        return switch (status) {
            case NOT_CONFIGURED -> AnchorReceipt.notConfigured(kind, digest);
            case FAILED -> AnchorReceipt.failed(kind, digest, attemptedAt,
                    (String) doc.get("failureReason"));
            case PENDING -> AnchorReceipt.pending(kind, digest, attemptedAt, proof,
                    (String) doc.get("proofDigest"), attributes);
            case CONFIRMED -> confirmedOrRefuse(kind, status, digest, attemptedAt, proof,
                    (String) doc.get("proofDigest"), attributes, doc);
        };
    }

    private static AnchorReceipt confirmedOrRefuse(AnchorKind kind, AnchorStatus status,
            String digest, Instant attemptedAt, byte[] proof, String proofDigest,
            Map<String, String> attributes, Map<String, Object> doc) {
        Instant anchoredAt = instant(doc.get("anchoredAt"));
        if (proofDigest != null && proof != null && proof.length > 0
                && !proofDigest.equalsIgnoreCase(sha256Hex(proof))) {
            // The row says CONFIRMED and its own two halves disagree. This catches a partial
            // write or a corrupted blob; it does NOT stop somebody who can edit the database
            // from writing a matching pair, and it is not offered as if it did.
            return AnchorReceipt.failed(kind, digest, attemptedAt,
                    "the stored proof does not hash to the recorded proofDigest; the row is "
                            + "inconsistent with itself and is not treated as an anchor");
        }
        if (proof == null || proof.length == 0) {
            // The row says confirmed and cannot support it. Rebuilding it as CONFIRMED would let
            // anyone who can edit this database manufacture an anchor no external party made.
            return AnchorReceipt.failed(kind, digest, attemptedAt,
                    "the stored receipt says CONFIRMED but carries no proof; it is not treated "
                            + "as an anchor");
        }
        AnchorKind.TimeSemantics semantics;
        try {
            semantics = AnchorKind.TimeSemantics.valueOf(
                    String.valueOf(doc.get("timeSemantics")));
        } catch (IllegalArgumentException | NullPointerException e) {
            // An unreadable semantics is not an excuse to assume the kind's usual one: an RFC
            // 3161 token without accuracy is deliberately downgraded, and guessing would undo
            // that downgrade on every reload.
            semantics = AnchorKind.TimeSemantics.UPPER_BOUND_ONLY;
        }
        return AnchorReceipt.confirmed(kind, digest, attemptedAt, anchoredAt, proof, proofDigest,
                attributes, semantics);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Instant instant(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] decode(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
