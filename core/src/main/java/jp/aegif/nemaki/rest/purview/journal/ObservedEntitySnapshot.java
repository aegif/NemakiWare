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
 * A snapshot that may become an ordinary catalog entity, for a source NemakiWare never destroys.
 *
 * <h2>What it claims, and what it deliberately does not</h2>
 *
 * <p>It claims exactly one thing: <em>a durable event observed this endpoint</em>. It does not
 * claim the source exists now — nothing here has asked the external system — and it does not
 * claim it is gone. That is the whole difference from {@link HistoricalEntitySnapshot}, which
 * exists only for a source proven destroyed and refuses anything else.
 *
 * <p>The two are separate types rather than one type with a flag, because the flag would be the
 * only thing standing between an observation and a tombstone. A type that cannot represent a
 * tombstone cannot accidentally become one.
 *
 * <h2>Why a LEDGERED kind is refused</h2>
 *
 * <p>For a kind NemakiWare destroys, "the catalog has no entity" has a possible explanation this
 * type cannot express: the source was purged and the entity should be a tombstone. Materialising
 * an ordinary entity there would publish an object as though it were still current, and the
 * later purge verdict would have nothing to correct it with. Those kinds go through the source
 * disposition resolver and the historical machine instead.
 *
 * @param snapshot what the event observed, already self-verified by its own constructor
 * @param taskKey the obligation this materialisation would settle
 */
public record ObservedEntitySnapshot(LineageWaitingSnapshot snapshot, String taskKey) {

    public ObservedEntitySnapshot {
        if (snapshot == null) {
            throw new IllegalArgumentException("an observed snapshot needs a snapshot");
        }
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("an observed snapshot needs its task key");
        }
        // The invariant the type exists for. A LEDGERED kind's absent entity may mean a purge,
        // and this type cannot say that — see the class javadoc.
        var policy = LineagePurgeLifecyclePolicy.of(snapshot.endpointKind());
        if (policy.isEmpty()
                || policy.get().policy() != LineagePurgeLifecyclePolicy.NON_PURGEABLE_BY_NEMAKI) {
            throw new IllegalArgumentException("only a source NemakiWare never destroys may be"
                    + " materialised as an ordinary observed entity");
        }
        // The snapshot must describe the task it would settle. A near miss is not a weaker
        // match: an entity built from another subject's snapshot is wrong in a way nothing
        // downstream can detect, because the write itself succeeds.
        String derived = LineageCatalogObligation.taskKey(snapshot.target(),
                snapshot.repositoryId(), snapshot.endpointKind(),
                snapshot.catalogQualifiedName());
        if (!taskKey.equals(derived)) {
            throw new IllegalArgumentException(
                    "the snapshot does not describe the task it would settle");
        }
        // The evidence digest is recomputed and constant-time compared by the snapshot's own
        // canonical constructor, which every path including deserialisation goes through — so
        // holding an instance already means it verified. Asserted here anyway, cheaply, because
        // this type's whole job is to be the thing that refuses: a future change that relaxed
        // the snapshot would otherwise silently relax this too.
        if (snapshot.evidenceDigest() == null
                || !snapshot.evidenceDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("the snapshot carries no usable evidence digest");
        }
        if (snapshot.snapshotSchemaVersion() < LineageWaitingSnapshot.MIN_SNAPSHOT_SCHEMA_VERSION
                || snapshot.snapshotSchemaVersion()
                        > LineageWaitingSnapshot.MAX_SNAPSHOT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("the snapshot's schema version is out of range");
        }
        // The disposition may be UNKNOWN — that is the honest value for a source nobody asked
        // about. What it must never be is PURGED: that verdict belongs to the historical path,
        // and reaching it here would mean a tombstone was routed to the wrong type.
        if (snapshot.sourceDisposition() == LineageSourceDisposition.SOURCE_PURGED) {
            throw new IllegalArgumentException("a purged source is not an observed entity");
        }
    }

    /**
     * Whether this snapshot can actually produce a publishable entity.
     *
     * @return the missing mandatory attribute names, empty when it can; names only, never values
     */
    public java.util.List<String> missingMandatoryAttributes() {
        return LineageHistoricalEntityFactory.missingMandatoryAttributes(
                LineageHistoricalEntityFactory.observedEntityFor(this),
                snapshot.endpointKind());
    }

    /** The target this materialisation would write to. */
    public String target() {
        return snapshot.target();
    }
}
