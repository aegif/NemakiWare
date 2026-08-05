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

    /**
     * Whether a fresh verdict still authorises this write.
     *
     * <h2>Why prepare-time evidence is not write-time authorisation</h2>
     *
     * <p>The verdict that produced this plan was read before the claim was renewed, and the
     * renewal itself takes time. In between, the source can be purged, re-created or modified.
     * Publishing a current entity on the strength of the older verdict would put content in
     * the catalog for an instance of the object that no longer exists — and for a purge, it
     * would publish an object as current at the moment it stopped being so.
     *
     * <p>So the verdict is taken again immediately before the catalog is touched, and this
     * compares the two. It is <em>not</em> a re-decision of the route: a recheck that disagrees
     * does not become a historical plan, because that plan would have been built from different
     * evidence with a different snapshot. It fails the authorisation, nothing is written, and
     * the next pass starts again from prepare.
     *
     * @param recheck the fresh verdict; null, an exception upstream, or anything but a matching
     *        SOURCE_EXISTS means unauthorised
     */
    public boolean stillAuthorised(LineageSourceDispositionResolver.SourceEvidence recheck) {
        if (recheck == null) {
            return false;
        }
        // UNKNOWN and PURGED both mean this write is no longer licensed. UNKNOWN especially:
        // an unestablished source is not a weaker EXISTS.
        if (recheck.disposition() != LineageSourceDisposition.SOURCE_EXISTS) {
            return false;
        }
        if (!recheck.describesSubject(snapshot.repositoryId(), snapshot.endpointKind(),
                snapshot.catalogQualifiedName())) {
            return false;
        }
        // The same instance, not merely some instance. A re-created object at the same id is a
        // different incarnation, and a modified one a different revision — publishing the old
        // snapshot's attributes for either would assert content that instance never had.
        // checkedAtMs is deliberately not compared: it differs by construction, being the whole
        // point of taking the verdict again.
        return equalsExactly(recheck.incarnation(), evidence.incarnation())
                && equalsExactly(recheck.revision(), evidence.revision());
    }

    private static boolean equalsExactly(String a, String b) {
        return a != null && b != null && a.equals(b);
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
