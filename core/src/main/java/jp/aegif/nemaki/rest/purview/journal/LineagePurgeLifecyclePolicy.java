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

import java.util.EnumMap;
import java.util.Map;

/**
 * Whether NemakiWare can ever witness a source of this kind being destroyed.
 *
 * <h2>Why this is a classification and not a capability flag</h2>
 *
 * <p>"No purge mark exists for this kind" has two completely different causes, and treating
 * them the same is how a machine either stalls for ever or tombstones a live object:
 *
 * <ul>
 *   <li><b>{@code LEDGERED}</b> — NemakiWare owns the destruction, so a mark can and must be
 *       written when it happens. A kind here with no hook is a gap: {@code SOURCE_PURGED} is
 *       unreachable and its obligations retry for ever.</li>
 *   <li><b>{@code NON_PURGEABLE_BY_NEMAKI}</b> — there is no destruction for NemakiWare to
 *       witness. A hook here would be fabricated, and the only candidate call sites are
 *       compensating cleanups, which describe a <em>failed</em> operation rather than a purge.
 *       Writing a mark from one would tombstone an object that is still there.</li>
 * </ul>
 *
 * <p>So the classification is stated per kind, with its reason, and readiness refuses anything
 * it does not recognise. An unlisted kind is neither — which is the honest answer for a kind
 * nobody has looked at, and the one that must never be green.
 */
public enum LineagePurgeLifecyclePolicy {

    /** NemakiWare destroys it, and records the destruction in {@link LineagePurgeLedger}. */
    LEDGERED,

    /**
     * NemakiWare never destroys the source, so it can never attest that it is gone.
     *
     * <p>Obligations for these kinds resolve by materialising the observation the event
     * carries, not by tombstoning — see {@code LineageCatalogObligationService}. They must
     * never reach {@code SOURCE_PURGED}.
     */
    NON_PURGEABLE_BY_NEMAKI;

    /**
     * The classification for every endpoint kind, with the reason it holds.
     *
     * <p>Frozen deliberately: a derived answer would say "purgeable" for any kind with a
     * {@code delete} somewhere in reach, and every candidate among the external kinds turned
     * out to be a compensating cleanup for a failed operation.
     */
    private static final Map<EndpointKind, Classification> BY_KIND = classify();

    /** One kind's policy and why. */
    public record Classification(LineagePurgeLifecyclePolicy policy, String reason) { }

    private static Map<EndpointKind, Classification> classify() {
        Map<EndpointKind, Classification> byKind = new EnumMap<>(EndpointKind.class);
        byKind.put(EndpointKind.CMIS_DOCUMENT, new Classification(LEDGERED,
                "destroyArchive destroys the document; restoreArchive brings it back"));
        byKind.put(EndpointKind.CMIS_FOLDER, new Classification(LEDGERED,
                "destroyArchive destroys the folder; restoreArchive brings it back"));
        byKind.put(EndpointKind.ARCHIVE, new Classification(LEDGERED,
                "destroyArchive destroys the archive row itself"));
        byKind.put(EndpointKind.EXTERNAL_ASSET, new Classification(NON_PURGEABLE_BY_NEMAKI,
                "the source belongs to the external system; NemakiWare has no API that"
                        + " destroys it"));
        byKind.put(EndpointKind.CLOUD_OBJECT, new Classification(NON_PURGEABLE_BY_NEMAKI,
                "unlink removes local metadata only and leaves the provider's object intact"));
        byKind.put(EndpointKind.COLD_STORAGE, new Classification(NON_PURGEABLE_BY_NEMAKI,
                "every LongTermStorageAdapter.delete() call is compensating cleanup for a cold"
                        + " move that failed — there is no purge path for an object whose move"
                        + " succeeded, and a mark written from a rollback would tombstone"
                        + " content that is still in the archive"));
        byKind.put(EndpointKind.IMPORT_ARTIFACT, new Classification(NON_PURGEABLE_BY_NEMAKI,
                "an operation-scoped virtual entity; there is no stored source object to"
                        + " destroy"));
        byKind.put(EndpointKind.EXPORT_ARTIFACT, new Classification(NON_PURGEABLE_BY_NEMAKI,
                "an operation-scoped virtual entity; there is no stored source object to"
                        + " destroy"));
        return java.util.Collections.unmodifiableMap(byKind);
    }

    /**
     * How this kind's source can end, or empty when nobody has classified it.
     *
     * <p>Empty is not a default — it is the answer for a kind this table has not been updated
     * for, and readiness refuses it rather than guessing which of the two it is.
     */
    public static java.util.Optional<Classification> of(EndpointKind kind) {
        return java.util.Optional.ofNullable(kind == null ? null : BY_KIND.get(kind));
    }

    /** Whether a purge verdict is even possible for this kind. */
    public static boolean canBePurged(EndpointKind kind) {
        return of(kind).map(c -> c.policy() == LEDGERED).orElse(false);
    }
}
