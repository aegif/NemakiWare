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
package jp.aegif.nemaki.rest.purview.journal;

/**
 * The one check every catalog sink has to make before it builds a payload.
 *
 * <h2>Why an unresolved asset must not be published</h2>
 *
 * <p>An {@link LineageAssetRef.Unresolved} reference is one whose qualified name is known but
 * whose identity could not be established — a purged source, a mapping that failed. Publishing it
 * anyway would create a catalog entity with a name and nothing else, which is exactly the "shell"
 * the design's §10 verification is built to reject: it looks like a successful publish, it links
 * the Process to something, and the something is empty.
 *
 * <h2>Why this reports a failure and not a skip</h2>
 *
 * <p>The right answer is §2's {@code WAITING_FOR_CATALOG}: not published, not failed, not
 * consuming a retry. That state does not exist yet, so the choice is between the two that do, and
 * they are not symmetric. {@link LineageTargetSinkResult#skipped} carries {@code success=true},
 * which the projection loop records as {@code PUBLISHED} and then advances the cursor past — an
 * event that was never delivered, gone quietly. A failure retries and eventually dead-letters,
 * which is loud and recoverable.
 *
 * <p>So this is the fail-closed option on purpose, and it is temporary. When
 * {@code WAITING_FOR_CATALOG} lands, this becomes that.
 *
 * <p>Nothing produces an {@code Unresolved} reference yet: {@link LineageRecord#fromV1} produces
 * legacy names and {@link LineageRecord#fromV2} produces typed ones. This is the guard that has to
 * be in place before the legacy resolver starts producing them.
 */
final class LineageSinkAssets {

    private LineageSinkAssets() {
    }

    /**
     * @return a reason string naming the first unresolved asset, or {@code null} if every asset on
     *         the record can be referenced
     */
    static String firstUnresolvedReason(LineageRecord record) {
        for (LineageAssetRef ref : record.allAssets()) {
            if (ref instanceof LineageAssetRef.Unresolved unresolved) {
                // The reason and a digest, never the qualified name: an external asset's name is
                // reversible base64 of its stable key (§4).
                return "unresolved asset (" + unresolved.reason() + ", "
                        + LineageEndpoint.shortDigest(unresolved.qualifiedName()) + ")";
            }
        }
        return null;
    }
}
