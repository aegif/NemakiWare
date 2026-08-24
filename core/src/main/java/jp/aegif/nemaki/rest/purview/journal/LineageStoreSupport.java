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

import java.util.Map;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * The shared storage basis the extracted responsibilities need — and nothing else.
 *
 * <p>The responsibilities being split out of {@link CouchLineageJournalStore} genuinely share
 * one database, one provisioning path and one set of strict-IO rules. They must not each
 * acquire their own route to CouchDB: that would be a behaviour change wearing a refactoring's
 * clothes. But they must also not depend on the facade, or the split would end with a cycle
 * where every child can reach every other child's methods.
 *
 * <p>So this interface names exactly what is shared, the facade implements it, and each
 * delegate holds only this. What is deliberately NOT here: any v1 journal query, any
 * sequencing, any transition — those are responsibilities, not basis.
 *
 * <p><b>Nothing here changes meaning.</b> Each method is the existing implementation reached
 * through a narrower door; the 404/409/failure classification, the exception types and the
 * provisioning semantics are the ones that were already there.
 */
interface LineageStoreSupport {

    /** Provisions the database and its design document if this process has not yet. */
    void ensureDatabase();

    /** The live client. Callers must have called {@link #ensureDatabase} first. */
    CloudantClientWrapper client();

    /** The design document name every view lives under. One per database, deliberately. */
    String designDoc();

    /**
     * Strict raw read: 404 is {@code null} (an ordinary answer), anything else throws.
     *
     * <p>The forgiving wrapper returns null for outages too, which would let an infrastructure
     * failure impersonate an absent document and mis-route the recovery.
     */
    Map<String, Object> readRawStrict(String documentId);

    /**
     * Strict CAS: true committed, false = 409 (an ordinary CAS loss), otherwise throws.
     *
     * <p>An outage reported as "conflict" would make the caller re-read forever instead of
     * latching.
     */
    boolean updateStrictCas(Map<String, Object> raw);

    /** Strict raw read of a v2 row by its delivery id. Same three-way classification. */
    Map<String, Object> readV2RawStrict(String recordId);

    /** Decodes a v2 row, refusing a shape that cannot mean anything. */
    LineageJournalRowV2 decodeV2Strict(Map<String, Object> raw);

    /**
     * Strict create: {@code true} committed, {@code false} = the id already exists.
     *
     * <p>Not {@link CloudantClientWrapper#create}: both wrapper create methods swallow every
     * exception and return {@code null}, which makes a conflict indistinguishable from an
     * outage. Anything that is not a conflict propagates, so a caller can tell "already there"
     * from "could not write" — the distinction the capture boundary is built on.
     */
    boolean createIfAbsentStrict(String documentId, Map<String, Object> properties);

    /**
     * Raw view query against ANY design document in this database.
     *
     * <p>Returns the stored documents as maps rather than decoded journal rows: the capture
     * boundary's rows are not journal events and decoding them as such would either fail or,
     * worse, half-succeed.
     *
     * @return the rows' documents, or an empty list when the view answers nothing. An empty list
     *         is NOT a promise that the view is empty — a view group being rebuilt answers with
     *         an incomplete index rather than failing, which is why these rows have their own
     *         design document.
     */
    java.util.List<Map<String, Object>> queryRawView(String designDocName, String viewName,
            Map<String, Object> params);

    /**
     * How many rows a view answers with, up to {@code limit}, WITHOUT fetching the documents.
     *
     * <p>{@link #queryRawView} always asks for {@code include_docs}, which is right when the
     * caller needs the rows and wrong when it only needs a number: counting three states at the
     * documented scan limit would pull 300,000 documents into heap to produce three integers
     * (external review).
     */
    int countRawView(String designDocName, String viewName, int limit);

    /**
     * The EXACT number of rows a view holds, from its {@code _count} reduce.
     *
     * <p>Not a bounded scan: the B-tree already carries the total, so this is O(1) and — the
     * point — is a number rather than a lower bound. A bound that stops at 10,000 says the same
     * thing at 10,001 rows and at ten million, which is the wrong shape for "is CAPTURED
     * growing without bound?" (AC 13).
     *
     * <p>Default: {@code null}, meaning "this store cannot answer" — an implementation whose
     * design document predates the reduce, or a test double. Callers fall back to the scan.
     */
    default Long reduceCount(String designDocName, String viewName) {
        return null;
    }

    /**
     * Deletes a document ONLY if it is still at the revision in the given map.
     *
     * <p>{@code false} when it was not deleted — including when the revision has moved on, which
     * is an ordinary lost race rather than an error. Conditional on purpose: a retention sweep
     * decides what may be deleted from what it read, so deleting whatever is there NOW would let
     * it destroy a row that became undeletable in between.
     */
    boolean deleteRaw(Map<String, Object> raw);

    /** Metrics, when a bean is wired; {@code null} otherwise. */
    LineageMetrics metrics();
}
