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
package jp.aegif.nemaki.rest.ingest;

/**
 * The one fail-closed {@code _all_docs} walk over {@code nemaki_conf}.
 *
 * <p>Extracted from {@code ConnectorDefinitionServiceImpl} when the import-profile service
 * needed the same walk. Two copies of pagination logic are how the skip-after-delete defect
 * would come back on one side only — the shape this batch has hit five times in one file —
 * so the walk lives once and both services' locks assert that they call it.
 *
 * <p>Continuation is {@code startKey(lastSeenId)} WITHOUT a server-side {@code skip(1)}. The
 * continuation key can be a row the caller just DELETED (a migrated legacy row at a page
 * boundary); CouchDB then starts at the first key after it, and a server-side skip would
 * discard a live row — a legacy row at position page+1 was silently missed and the pass
 * reported clean. A review caught it before this ever ran. The still-present case (the key
 * is re-served as the first row) is dropped by id comparison instead.
 */
final class NemakiConfAllDocs {

    /** One page of the walk. The DEFINITION rows of nemaki_conf are in the tens; the
     *  database as a whole is not (it also accumulates ingest-job and dead-letter records),
     *  so a full walk is paged. A ranged variant that skipped the other rows was withdrawn —
     *  it could not answer the completeness the callers' rules need. Small also keeps the
     *  paging test's full page of mocks affordable. */
    static final int MIGRATION_PAGE = 200;

    private NemakiConfAllDocs() {
    }

    /**
     * Calls {@code perRow} for every row of the database. Throws — never returns short —
     * when the enumeration itself did not answer or cannot make progress.
     */
    static void forEachRow(com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow) {
        walk(cloudant, dbName, perRow);
    }

    // A RANGED variant (startKey/endKey over one document type's deterministic ids) lived
    // here for two rounds as a cost measure for the runtime resolvers. It was withdrawn:
    // a bounded walk that finds something is still incomplete, and both resolvers refuse on
    // ambiguity — a rule that needs completeness. The history is in the design ledger.

    private static void walk(com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow) {
        String resumeAfterId = null;
        while (true) {
            com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder page =
                    new com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder()
                            .db(dbName).includeDocs(true).limit((long) MIGRATION_PAGE);
            if (resumeAfterId != null) {
                page.startKey(resumeAfterId);
            }
            com.ibm.cloud.cloudant.v1.model.AllDocsResult listing;
            try {
                listing = cloudant.postAllDocs(page.build()).execute().getResult();
            } catch (RuntimeException transport) {
                // The walk used to let a transport failure out as-is. Callers wrap
                // IllegalStateException into a typed 503 and let everything else become
                // a 500. A reset mid-page is "could not ask", the same as a listing
                // that did not answer.
                throw new IllegalStateException("the _all_docs listing of '" + dbName
                        + "' could not be read, so whether the rows are there cannot be"
                        + " established; retry shortly: " + transport.getMessage(),
                        transport);
            }
            if (listing == null || listing.getRows() == null) {
                // The ENUMERATION did not answer. Returning what has been seen so far would
                // read as "migration complete" to the caller — the same failure-as-absence
                // this migration exists to close, one layer up.
                throw new IllegalStateException("the _all_docs listing of '" + dbName
                        + "' did not answer, so whether any legacy rows remain"
                        + " cannot be established; the migration will retry on the next"
                        + " startup");
            }
            String lastNonNullId = null;
            for (com.ibm.cloud.cloudant.v1.model.DocsResultRow row : listing.getRows()) {
                String id = row.getId();
                if (id != null) {
                    if (id.equals(resumeAfterId)) {
                        // the continuation key itself, re-served because it still exists
                        continue;
                    }
                    lastNonNullId = id;
                }
                perRow.accept(row);
            }
            if (lastNonNullId != null) {
                resumeAfterId = lastNonNullId;
            } else if (listing.getRows().size() >= MIGRATION_PAGE) {
                // A FULL page advanced the cursor by nothing: repeating the query would loop
                // on the same page for ever, and stopping quietly would claim the rest of
                // the database was seen.
                throw new IllegalStateException("a full _all_docs page of '" + dbName
                        + "' carried no usable row ids, so the walk cannot make progress");
            }
            if (listing.getRows().size() < MIGRATION_PAGE) {
                break;
            }
        }
    }
}
