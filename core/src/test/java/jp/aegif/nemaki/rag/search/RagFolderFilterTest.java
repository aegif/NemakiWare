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
package jp.aegif.nemaki.rag.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jp.aegif.nemaki.util.test.JavaSource;

/**
 * {@code searchInFolder} must scope BOTH halves of the weighted search, each on its own field.
 *
 * <h2>Two wrong versions, both of which Solr answered instead of rejecting</h2>
 *
 * <p>A weighted search runs two KNN queries: one over chunks ({@code doc_type:chunk}) for
 * content similarity, one over parent documents ({@code doc_type:document}) for property
 * similarity. They index different fields. Only the parent carries {@code parent_id} — the
 * folder. A chunk carries {@code parent_document_id}, the document it belongs to, and nothing
 * about the folder at all.
 *
 * <ol>
 *   <li>The original filter was a to-parent Block Join,
 *       <code>{!parent which='doc_type:document'}parent_id:X</code>. The inner query of a
 *       to-parent join runs against CHILDREN, so it searched the chunks for a field only the
 *       parent has. Measured on the dev stack: one document returned for a folder holding two,
 *       and through the REST API, documents from other folders entirely — the parameter looked
 *       like it worked and scoped nothing.</li>
 *   <li>Replacing it with a plain {@code parent_id:X} fixed the property half and broke the
 *       content half: applied to chunks it matches nothing, so folder-scoped search silently
 *       lost every content-based hit. Measured on the same index — the folder held 2 documents
 *       and 1,144 chunks; the plain filter matched 0 chunks, the join matched all 1,144.</li>
 * </ol>
 *
 * <p>Neither version errored. That is the whole reason this is pinned: a filter that scopes
 * nothing and a filter that excludes everything both return 200 with a plausible list.
 *
 * <h2>Why this test reads source</h2>
 *
 * <p>Reproducing it needs a Solr core holding real chunk and parent documents; a mocked
 * SolrClient would only replay whatever query string the test itself expected. The defect is
 * entirely in the query strings, so that is what is pinned — together with the measurements, so
 * a future author who reaches for one filter here has to argue with a recorded number rather
 * than rediscover it.
 */
class RagFolderFilterTest {

    private static String searchInFolderBody() throws Exception {
        // Comments stripped: the explanation above necessarily quotes the broken queries, and a
        // check against the raw source would find the string it asserts the absence of — then
        // fail against the fixed code. This project has shipped that test twice.
        // The PARAMETERISED overload, not the 5-argument one that just delegates to it. Matching
        // on the bare method name found the delegate — two lines with no filters in them — and
        // every assertion here failed against correct code.
        return JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read("src/main/java/jp/aegif/nemaki/rag/search/VectorSearchServiceImpl.java"),
                "String folderId, int topK, Float minScore,"));
    }

    @Test
    @DisplayName("プロパティ側は親の parent_id を直接見る (Block Join にしない)")
    void theDocumentHalfFiltersOnTheParentField() throws Exception {
        String body = searchInFolderBody();

        assertTrue(body.contains("\"parent_id:\" + sanitizedFolderId"),
                "the property half searches parent documents, which is where parent_id lives");
        assertFalse(body.contains("{!parent"),
                "a to-parent Block Join here searches the CHUNKS for parent_id, which only the"
                        + " parent document carries. Solr answers that wrongly rather than"
                        + " failing: measured 1 hit for a 2-document folder, and out-of-folder"
                        + " documents through the API");
    }

    @Test
    @DisplayName("チャンク側は object_id 経由の join にする (parent_id では 0 件になる)")
    void theChunkHalfJoinsThroughObjectId() throws Exception {
        String body = searchInFolderBody();

        assertTrue(body.contains("{!join from=object_id to=parent_document_id}"),
                "chunks carry no folder field, so the only way to scope them is to join to the"
                        + " documents that ARE in the folder. Filtering chunks on parent_id"
                        + " directly matches zero of them — measured 0 of 1,144 — which is a"
                        + " folder search that silently returns no content hits");
        assertTrue(body.contains("repository_id:")
                        && body.indexOf("repository_id:") > body.indexOf("{!join"),
                "the join's INNER query needs its own repository restriction: the outer filters"
                        + " constrain which chunks come back, not which parent documents the"
                        + " join reads object_id values from. Import/export can carry object ids"
                        + " between repositories, so 'uuids never collide' is not the guarantee");
        assertFalse(body.contains("{!join from=id "),
                "the join must go through object_id: the parent's id is prefixed \"rag:\" while"
                        + " a chunk's parent_document_id is the bare object id, so joining on id"
                        + " would match nothing");
    }

    @Test
    @DisplayName("2 つのフィルタを別々に渡す (1 本を両方に載せない)")
    void theTwoHalvesGetSeparateFilters() throws Exception {
        String body = searchInFolderBody();

        // Not "both names appear somewhere" — that passes if one is built and never used, if
        // the same one goes to both halves, or if they are swapped. The argument ORDER at the
        // call site is the property, because executeWeightedKnnSearch takes (chunk, document).
        int call = body.indexOf("executeWeightedKnnSearch(");
        assertTrue(call > 0, "the weighted search call is gone — this test needs updating");
        String args = body.substring(call, body.indexOf(";", call));
        int chunkArg = args.indexOf("chunkFolderFilter");
        int docArg = args.indexOf("documentFolderFilter");
        assertTrue(chunkArg > 0 && docArg > 0,
                "both filters must actually be PASSED, not merely constructed: " + args);
        assertTrue(chunkArg < docArg,
                "executeWeightedKnnSearch takes (chunkFilter, documentFilter) in that order;"
                        + " swapping them puts a parent-only field on the chunks and a join on"
                        + " the parents, which Solr answers rather than rejects: " + args);
    }

    @Test
    @DisplayName("folderId 指定でも呼び出し側の boost / minScore を渡す")
    void theFolderPathForwardsTheCallersWeighting() throws Exception {
        // The resource used to route every folderId request to the five-argument form, so
        // propertyBoost / contentBoost / minScore were accepted and then dropped with no error.
        // That is a contract violation on its own, and it also hid the defect above: with the
        // property half always running, a chunk filter matching nothing still returned results.
        String body = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read("src/main/java/jp/aegif/nemaki/api/v1/resource/RAGSearchResource.java"),
                "public Response search("));
        int folderCall = body.indexOf("vectorSearchService.searchInFolder(");
        assertTrue(folderCall > 0, "the folder-scoped call is gone — this test needs updating");
        String call = body.substring(folderCall, body.indexOf(";", folderCall));
        assertTrue(call.contains("request.getPropertyBoost()")
                        && call.contains("request.getContentBoost()"),
                "a parameter the API documents and then ignores is worse than one it rejects:"
                        + " the caller gets a 200 describing a search they did not ask for. Call"
                        + " site was: " + call);
        // The RESOLVED local, not request.getMinScore(). The GET parameter is nullable so that
        // null can reach the service and become the configured threshold; passing the raw field
        // here would work for POST and quietly send a JAX-RS default for GET.
        assertFalse(call.contains("request.getMinScore()"),
                "pass the resolved minScore, not the raw request field: " + call);
        assertTrue(call.contains("minScore"),
                "minScore must still be forwarded at all: " + call);
    }

    @Test
    @DisplayName("フォルダ id はサニタイズしてから埋める")
    void theFolderIdIsSanitised() throws Exception {
        String body = searchInFolderBody();
        // escapeAndQuote, not escape: escaping alone leaves whitespace and boolean keywords
        // live, so `foo OR bar` becomes several clauses rather than one term value.
        int sanitise = body.indexOf("SolrQuerySanitizer.escapeAndQuote(folderId)");
        int firstUse = body.indexOf("sanitizedFolderId", sanitise + 1);
        assertTrue(sanitise > 0 && firstUse > sanitise,
                "the folder id reaches this from a query parameter; interpolating it raw would"
                        + " let a caller inject Solr syntax into a filter whose whole job is to"
                        + " restrict what they see");
        assertFalse(body.contains("+ folderId"),
                "the raw parameter must never reach a query string; only the escaped copy does");
    }
}
