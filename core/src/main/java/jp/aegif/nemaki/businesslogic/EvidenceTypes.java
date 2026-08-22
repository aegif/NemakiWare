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
package jp.aegif.nemaki.businesslogic;

import java.util.Set;

/**
 * The secondary types whose aspects an ingest writes as evidence, and which CMIS may not detach.
 *
 * <h2>Why a list and not a flag on the type</h2>
 *
 * <p>A marker on {@code NemakiTypeDefinition} is the better long-term answer, but it needs a type
 * system change, a migration patch and a rolling-restart story. This is the same shape P1-1(c)
 * used for the property list: in the product, readable, and wrong in a way a reader can see.
 *
 * <h2>Why not derive it from updatability</h2>
 *
 * <p>"Every property is READONLY" is not the same question. {@code cmis:createdBy} is READONLY
 * and is not evidence — READONLY means the server owns the value, not that the value records a
 * capture. A type with a mix of both would have no answer.
 *
 * <h2>Why the second entry matters more than it looks</h2>
 *
 * <p>The first draft of this proposed deriving the list from "types owning a property P1-1(c)
 * made READONLY". That rule would never have included {@code nemaki:externalIntegration}, because
 * P1-1(c) deliberately scoped itself to chat — and that type is where {@code nemaki:contentHash}
 * lives, along with the source identity. Detaching it does not merely lose evidence: the dedupe
 * check reads {@code nemaki:contentHash} back, so the next poll of the same source object sees no
 * recorded digest and imports it again as a new version (external review, P1-1(d)).
 *
 * <h2>What this does NOT establish</h2>
 *
 * <p>Not immutability. Anything with direct CouchDB access is unaffected. Nor does deleting the
 * object destroy the evidence — {@code archive.create.enabled} defaults to true and the archive
 * copy preserves {@code aspects} and {@code secondaryIds} verbatim, restorable; the lineage
 * events live in a separate database that delete does not touch at all.
 */
public final class EvidenceTypes {

    /**
     * The protected set.
     *
     * <p>Kept explicit rather than derived. There is no mechanical check that a new evidence type
     * was added here — the association between a type and its properties lives in a private local
     * array inside the type patch, and what reaches the database is detail node ids rather than
     * property ids, so the containment test the design first proposed cannot be written today.
     * Saying so is the point: a guarantee that cannot be tested must not be described as one.
     */
    public static final Set<String> PROTECTED = Set.of(
            // The eleven chat evidence properties P1-1(c) made READONLY.
            "nemaki:chatContextMetadata",
            // nemaki:contentHash, nemaki:sourceObjectId, nemaki:sourceSystem,
            // nemaki:ingestionRunId, nemaki:sourceUrl. Not READONLY — P1-1(c) scoped itself to
            // chat — but evidence all the same, and the dedupe path depends on it.
            "nemaki:externalIntegration");

    private EvidenceTypes() {
    }

    /** Whether detaching this secondary type would take evidence with it. */
    public static boolean isProtected(String secondaryTypeId) {
        return secondaryTypeId != null && PROTECTED.contains(secondaryTypeId);
    }
}
