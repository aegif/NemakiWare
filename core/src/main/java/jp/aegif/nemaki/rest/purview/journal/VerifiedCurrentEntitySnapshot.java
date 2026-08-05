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
 * A snapshot for a source the repository has just confirmed is there.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code SOURCE_EXISTS} used to mean "wait for the authoritative publisher" — which is an
 * infinite wait whenever that publisher is never going to run for this subject. The obligation
 * would retry for ever on a source sitting right there in the repository, and every event
 * waiting on it would stall behind it.
 *
 * <h2>Why it is not {@link ObservedEntitySnapshot}</h2>
 *
 * <p>That type refuses a LEDGERED kind on purpose: for a kind NemakiWare destroys, an absent
 * entity may mean a purge, and materialising an ordinary entity on a guess would publish an
 * object as current when it is gone. Relaxing it would remove that protection everywhere.
 *
 * <p>This type takes the other route to the same safety: it demands a positive
 * {@code SOURCE_EXISTS} verdict, bound to this subject, with the live identity that verdict was
 * made from. The claim is not "the event saw this once" but "the repository holds it now, and
 * here is which instance".
 *
 * @param snapshot what the event observed — the attribute source
 * @param evidence the authoritative verdict that the source exists, subject-bound and
 *        self-verifying
 * @param taskKey the obligation this would settle
 */
public record VerifiedCurrentEntitySnapshot(LineageWaitingSnapshot snapshot,
        LineageSourceDispositionResolver.SourceEvidence evidence, String taskKey) {

    public VerifiedCurrentEntitySnapshot {
        if (snapshot == null || evidence == null) {
            throw new IllegalArgumentException(
                    "a verified current snapshot needs a snapshot and a source verdict");
        }
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("a verified current snapshot needs its task key");
        }
        // Only a kind NemakiWare destroys reaches this route. The others have their own,
        // and routing one here would mean asking a source resolver that cannot answer for it.
        if (!LineagePurgeLifecyclePolicy.canBePurged(snapshot.endpointKind())) {
            throw new IllegalArgumentException(
                    "only a LEDGERED kind is materialised from a verified live source");
        }
        // The verdict, not a guess. UNKNOWN and PURGED both mean this must not be written.
        if (evidence.disposition() != LineageSourceDisposition.SOURCE_EXISTS) {
            throw new IllegalArgumentException(
                    "only a positive SOURCE_EXISTS verdict may publish a current entity");
        }
        // The verdict must be about THIS subject. Another object's "it exists" would publish
        // one object's content under another's name, and the write itself would succeed.
        if (!evidence.describesSubject(snapshot.repositoryId(), snapshot.endpointKind(),
                snapshot.catalogQualifiedName())) {
            throw new IllegalArgumentException(
                    "the source verdict is about a different subject than the snapshot");
        }
        String derived = LineageCatalogObligation.taskKey(snapshot.target(),
                snapshot.repositoryId(), snapshot.endpointKind(),
                snapshot.catalogQualifiedName());
        if (!taskKey.equals(derived)) {
            throw new IllegalArgumentException(
                    "the snapshot does not describe the task it would settle");
        }
        // Which instance was seen. Without it a re-created object at the same id is
        // indistinguishable from the one the verdict was made about.
        if (evidence.incarnation() == null || evidence.incarnation().isBlank()
                || evidence.revision() == null || evidence.revision().isBlank()) {
            throw new IllegalArgumentException(
                    "a live source verdict must name the incarnation and revision it saw");
        }
        // Recomputed and constant-time compared by the snapshot's own canonical constructor;
        // asserted here so a future relaxation there cannot silently relax this.
        if (snapshot.evidenceDigest() == null
                || !snapshot.evidenceDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("the snapshot carries no usable evidence digest");
        }
        if (snapshot.sourceDisposition() == LineageSourceDisposition.SOURCE_PURGED) {
            throw new IllegalArgumentException("a purged snapshot is not a current entity");
        }
    }

    /** The target this materialisation would write to. */
    public String target() {
        return snapshot.target();
    }

    /** The observed-entity view of this snapshot, for the shared materialisation path. */
    LineageWaitingSnapshot attributeSource() {
        return snapshot;
    }
}
