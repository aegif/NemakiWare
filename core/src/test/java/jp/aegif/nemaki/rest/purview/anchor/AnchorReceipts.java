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
import java.util.Map;

/**
 * Mints receipts for tests in other packages.
 *
 * <p>{@code AnchorReceipt.confirmed} is package-private so only a rung's own implementation can
 * mint a confirmed receipt. That restriction is worth keeping in production code, and this
 * lives in the test tree so it cannot be reached from it.
 */
public final class AnchorReceipts {

    private AnchorReceipts() {
    }

    public static AnchorReceipt confirmed(AnchorKind kind, String digest, Instant at,
            byte[] proof, String proofDigest, Map<String, String> attributes) {
        return AnchorReceipt.confirmed(kind, digest, at, at, proof, proofDigest, attributes,
                kind.timeSemantics());
    }
}
