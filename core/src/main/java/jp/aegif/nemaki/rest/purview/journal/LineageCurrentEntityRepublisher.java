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
 * Puts the authoritative current entity back over a historical one that turned out to be wrong.
 *
 * <h2>Why re-publish rather than delete</h2>
 *
 * <p>Delete semantics differ between catalog backends — soft delete, hard delete, delete that
 * cascades through relationships, delete that quietly does nothing for an entity with
 * references. A delete that silently did nothing would leave the tombstone in place looking
 * compensated, which is worse than not having tried. Re-publishing the current entity converges
 * on the right state whatever the backend does with deletes, and it is the operation the
 * authoritative publisher performs all the time anyway.
 *
 * <p>What Atlas OSS does here is not evidence about Purview. Each needs its own.
 */
public interface LineageCurrentEntityRepublisher {

    enum Outcome {
        /** The current entity is in the catalog, confirmed by read-back. */
        REPUBLISHED,
        /** The source could not be read; nothing was written. */
        SOURCE_UNKNOWN,
        /** The catalog could not complete it. */
        RETRYABLE
    }

    /**
     * Reads the current source and publishes the authoritative entity over the historical one.
     *
     * @param subjectDigest which endpoint, as a digest — the caller has no business handing a
     *        qualified name through a path that is logged on failure
     */
    Outcome republishCurrent(String target, String repositoryId, EndpointKind kind,
            String subjectDigest);
}
