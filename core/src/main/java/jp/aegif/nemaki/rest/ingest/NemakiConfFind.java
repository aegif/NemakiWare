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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;

/**
 * The one paged Mango selector read over {@code nemaki_conf} — the selector twin of
 * {@link NemakiConfAllDocs}.
 *
 * <p>Both definition services asked the selector for one page of 200 and returned it as the
 * whole answer. Nothing continued past the page: the 201st matching row was not listed, by
 * the admin listing or by any caller that filtered {@code list()} — and unlike the
 * rebuilding-index case, that is a listing that answers short every time it runs. The page
 * is followed by its bookmark until a page comes back partial.
 *
 * <p>This does not make the selector index-free. It still answers from the Mango index, so a
 * rebuild still shows a partial view; the runtime resolvers walk {@code _all_docs} for that
 * reason and are not changed by this.
 */
final class NemakiConfFind {

    /** One page of the selector read; the last page is the first that comes back short. */
    static final int PAGE = 200;

    private NemakiConfFind() {
    }

    /**
     * Every document matching {@code selector}. Throws — never returns short — when a full
     * page carries no bookmark to continue from, or when the listing did not answer.
     */
    static List<Document> allMatching(com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            Map<String, Object> selector) {
        List<Document> all = new ArrayList<>();
        String bookmark = null;
        while (true) {
            PostFindOptions.Builder page = new PostFindOptions.Builder()
                    .db(dbName).selector(selector).limit((long) PAGE);
            if (bookmark != null) {
                page.bookmark(bookmark);
            }
            FindResult result = cloudant.postFind(page.build()).execute().getResult();
            List<Document> docs = result != null ? result.getDocs() : null;
            if (docs == null) {
                // A Mango answer always carries `docs`; one without it is not an answer, and
                // returning what was seen so far would read as a complete listing.
                throw new IllegalStateException("the selector listing of '" + dbName
                        + "' did not answer, so whether the rows are there cannot be"
                        + " established");
            }
            all.addAll(docs);
            if (docs.size() < PAGE) {
                return all;
            }
            String next = result.getBookmark();
            if (next == null || next.isBlank() || next.equals(bookmark)) {
                // A FULL page with nothing to continue from: repeating the query would loop
                // on the same page for ever, and stopping quietly would claim the rest of
                // the database was seen.
                throw new IllegalStateException("a full selector page of '" + dbName
                        + "' carried no continuation bookmark, so the listing cannot make"
                        + " progress");
            }
            bookmark = next;
        }
    }
}
