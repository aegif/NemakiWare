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
package jp.aegif.nemaki.evidence.anchor;

/**
 * Tier 1: put the root in the customer's own catalog (P2-0 §2).
 *
 * <h2>The weakest tier, and it says so</h2>
 *
 * <p>This is neither a time proof nor independent evidence. It puts the same value in a second
 * system the customer runs, which helps only against somebody who can reach one of them — an
 * administrator who can reach both defeats it entirely. The roadmap's ladder puts it at tier 1
 * for that reason, and {@link #claimLimits()} says it in the receipt so the sentence travels with
 * the number.
 *
 * <p>It is still worth having: separation of duties is a real control in real organisations, and
 * a catalog with its own history makes the rewrite an operator would have to perform visible in
 * a place they may not administer.
 *
 * <h2>Confirmed on write, because there is nothing to wait for</h2>
 *
 * <p>Unlike OpenTimestamps this settles synchronously — the publisher either wrote the entity or
 * it did not. So this tier never returns {@code SUBMITTED}; there is no window in which it would
 * mean anything.
 */
public class CatalogAnchorTarget implements AnchorTarget {

    public static final String TIER_ID = "catalog";

    /**
     * How the root reaches the catalog.
     *
     * <p>A seam rather than a direct dependency on the Purview/Atlas sink: this tier's contract
     * is "put this value somewhere the customer administers", and which catalog that is belongs
     * to the deployment. An implementation returns the entity id it wrote, or throws.
     */
    @FunctionalInterface
    public interface CatalogAnchorPublisher {
        String publish(String domain, long fromSequence, long toSequence, String merkleRoot,
                String createdAt);
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
    public String tierId() {
        return TIER_ID;
    }

    @Override
    public boolean isEnabled() {
        // A tier with no publisher is not enabled however the flag is set: reporting it enabled
        // would make every anchor FAILED and bury the actual cause, which is that nobody wired
        // a catalog.
        return enabled && publisher != null;
    }

    @Override
    public String claimLimits() {
        return "This is NOT a time proof and NOT independent evidence. It records the same root "
                + "in a second system the customer operates, so it helps only against somebody "
                + "who can reach one of the two — an administrator who can reach both can "
                + "rewrite both. Independence begins at tier 2 (OpenTimestamps) and tier 3 "
                + "(RFC 3161).";
    }

    @Override
    public AnchorReceipt submit(String domain, long fromSequence, long toSequence,
            String merkleRoot, String createdAt) {
        if (!isEnabled()) {
            return AnchorReceipt.notAttempted(TIER_ID, claimLimits());
        }
        String entityId = publisher.publish(domain, fromSequence, toSequence, merkleRoot,
                createdAt);
        if (entityId == null || entityId.isBlank()) {
            // A publisher that returns nothing has given no evidence that anything was written.
            // Treating that as CONFIRMED would put an anchor in the report with no way to check
            // it — the exact shape this tier is least able to afford.
            return AnchorReceipt.failed(TIER_ID, domain, merkleRoot,
                    "the catalog accepted the root but returned no entity id, so there is "
                            + "nothing to check the anchor against", claimLimits());
        }
        return new AnchorReceipt(TIER_ID, AnchorState.CONFIRMED, domain, fromSequence,
                toSequence, merkleRoot, createdAt, createdAt, entityId, null, claimLimits());
    }
}
