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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rung 1: put the digest in the customer's own catalog.
 *
 * <p>The weakest rung, and {@link AnchorKind#ATLAS_CATALOG} already carries why in its type:
 * {@code NOT_A_TIME_PROOF}. It records when the catalog was TOLD, not when the data existed, and
 * a same-tenant catalog is administered by the very party whose behaviour is in question. It is
 * still worth having — separation of duties is a real control, and a catalog with its own
 * history makes the rewrite an operator would have to perform visible somewhere they may not
 * administer.
 *
 * <h2>Confirmed on write, because there is nothing to wait for</h2>
 *
 * <p>Unlike OpenTimestamps this settles synchronously: the publisher either wrote the entity or
 * it did not, so this rung never returns {@code PENDING}. There would be no window in which
 * pending meant anything.
 */
public class CatalogAnchorTarget implements AnchorTarget {

    /**
     * How the digest reaches the catalog.
     *
     * <p>A seam rather than a direct dependency on the Purview sink: this rung's contract is
     * "put this value somewhere the customer administers", and which catalog that is belongs to
     * the deployment. An implementation returns the entity id it wrote, or throws.
     */
    @FunctionalInterface
    public interface CatalogAnchorPublisher {
        String publish(String hexDigest);
    }

    private CatalogAnchorPublisher publisher;
    private boolean enabled;

    public void setPublisher(CatalogAnchorPublisher publisher) {
        this.publisher = publisher;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public AnchorKind kind() {
        return AnchorKind.ATLAS_CATALOG;
    }

    @Override
    public boolean isConfigured() {
        // A rung with no publisher is not configured however the flag is set. Reporting it
        // configured would make every anchor FAILED and bury the actual cause — that nobody
        // wired a catalog — under what looks like an outage.
        return enabled && publisher != null;
    }

    @Override
    public AnchorReceipt anchor(String hexDigest) {
        requireDigest(hexDigest);
        if (!isConfigured()) {
            return AnchorReceipt.notConfigured(kind(), hexDigest);
        }
        Instant attemptedAt = Instant.now();
        String entityId;
        try {
            entityId = publisher.publish(hexDigest);
        } catch (RuntimeException e) {
            // Ordinary remote failure is a fact to record, never an exception that could fail
            // the CMIS write that triggered the anchoring.
            return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                    "the catalog rejected the anchor: " + e.getMessage());
        }
        if (entityId == null || entityId.isBlank()) {
            // A publisher that returns nothing has given no evidence anything was written.
            // CONFIRMED here would put an anchor in the evidence report with nothing to check
            // it against — which this rung, being the weakest, can least afford.
            return AnchorReceipt.failed(kind(), hexDigest, attemptedAt,
                    "the catalog accepted the digest but returned no entity id, so there is "
                            + "nothing to check the anchor against");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("catalogEntityId", entityId);
        return confirmed(hexDigest, attemptedAt, entityId, attributes);
    }

    private AnchorReceipt confirmed(String hexDigest, Instant attemptedAt, String entityId,
            Map<String, String> attributes) {
        // The proof is the entity reference: what a checker fetches from the catalog to see the
        // same digest. Not the digest itself — that would "prove" the anchor from the value it
        // is supposed to be anchoring.
        byte[] proof = entityId.getBytes(StandardCharsets.UTF_8);
        return AnchorReceipt.confirmed(kind(), hexDigest, attemptedAt, attemptedAt, proof,
                sha256Hex(proof), attributes, kind().timeSemantics());
    }

    private static void requireDigest(String hexDigest) {
        if (hexDigest == null || !hexDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("an anchor digest must be 64 lowercase hex "
                    + "characters; a caller passing anything else is a bug here, not a remote "
                    + "failure to record");
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
