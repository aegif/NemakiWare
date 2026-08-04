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

import java.util.Optional;

/**
 * A snapshot that has earned the right to become a historical entity.
 *
 * <h2>Why this type exists rather than a check inside the publisher</h2>
 *
 * <p>A publisher taking {@link LineageWaitingSnapshot} could be handed one whose source still
 * exists, and would then have to remember to refuse it. Every caller would have to remember the
 * same thing. This type can only be constructed by {@link #from}, which refuses everything the
 * publisher must not act on — so "publish a tombstone for a live object" is not a mistake the
 * API allows, rather than one it documents.
 *
 * <p>The gate is deliberately narrow: only {@code SOURCE_PURGED}, only a subject that matches
 * the obligation, and only a target that matches the publisher's registration.
 */
public record HistoricalEntitySnapshot(LineageWaitingSnapshot snapshot, String taskKey) {

    public HistoricalEntitySnapshot {
        if (snapshot == null) {
            throw new IllegalArgumentException("a historical snapshot needs a snapshot");
        }
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("a historical snapshot needs its task key");
        }
        if (snapshot.sourceDisposition() != LineageSourceDisposition.SOURCE_PURGED) {
            // The invariant the type exists for. A live source's entity belongs to the
            // authoritative publisher, and a tombstone for it would be the catalog's record of
            // a document that is sitting in the repository.
            throw new IllegalArgumentException(
                    "only a purged source may become a historical entity");
        }
    }

    /**
     * Converts, if every condition holds. Empty otherwise — the caller cannot force it.
     *
     * @param registeredTarget the target the publisher is bound to, so a historical entity
     *        cannot be written to a catalog the obligation does not name
     */
    public static Optional<HistoricalEntitySnapshot> from(LineageWaitingSnapshot snapshot,
            LineageCatalogObligation obligation, String registeredTarget) {
        if (snapshot == null || obligation == null) {
            return Optional.empty();
        }
        if (!snapshot.describesSubject(obligation)) {
            return Optional.empty();
        }
        if (registeredTarget == null || !registeredTarget.equals(snapshot.target())) {
            return Optional.empty();
        }
        if (snapshot.sourceDisposition() != LineageSourceDisposition.SOURCE_PURGED) {
            return Optional.empty();
        }
        return Optional.of(new HistoricalEntitySnapshot(snapshot, obligation.taskKey()));
    }

    public String target() {
        return snapshot.target();
    }

    public String repositoryId() {
        return snapshot.repositoryId();
    }

    public EndpointKind endpointKind() {
        return snapshot.endpointKind();
    }

    public String catalogQualifiedName() {
        return snapshot.catalogQualifiedName();
    }

    /** Subject as digests; no qualified name, no attribute value, no task key. */
    @Override
    public String toString() {
        return "HistoricalEntitySnapshot[" + snapshot + "]";
    }
}
