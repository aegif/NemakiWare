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

    /** Metrics, when a bean is wired; {@code null} otherwise. */
    LineageMetrics metrics();
}
