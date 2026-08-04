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
 * What the lineage documents are called on disk. A persistence contract, owned by nobody.
 *
 * <p>These two names are shared across responsibilities — {@code DB_NAME} by every store, and
 * {@code SEQ_PREFIX} by both v1's {@code assignSequenceNumber} and §8-a's fenced allocator, which
 * address the <em>same</em> counter document. Leaving them on {@link CouchLineageJournalStore}
 * made every delegate reference the facade's concrete type to read a string, which is a
 * dependency on the facade whether or not it forms a cycle: it means a delegate cannot be
 * compiled, tested or reasoned about without the class it was extracted from.
 *
 * <p>Moving them changes nothing on disk. The values are byte-identical to what the facade
 * declared, and {@code LineageStoreCollaboratorTest} pins that no delegate names the facade at
 * all — the check that keeps this from drifting back.
 */
final class LineageStoreDocuments {

    /** The one database every lineage document lives in. */
    static final String DB_NAME = "nemaki_lineage";

    /**
     * {@code lineage_seq:{repositoryId}} — the per-repository sequence counter.
     *
     * <p>Deliberately one document for two writers. v1's allocator seeds it; the fenced
     * allocator refuses to (a missing counter under existing history means rewound sequences,
     * I-4). Splitting the prefix would make that a second counter and silently un-fence the
     * sequence.
     */
    static final String SEQ_PREFIX = "lineage_seq:";

    private LineageStoreDocuments() {
    }
}
