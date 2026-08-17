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
package jp.aegif.nemaki.dao.impl.couch.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.PostViewOptions;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * A keyed view query must ask CouchDB for that key.
 *
 * <h2>What this is guarding</h2>
 *
 * <p>{@link CloudantClientWrapper#queryView(String, String, String, boolean)} used to send NO key.
 * It fetched the entire view — every row in the repository, with {@code include_docs=true} — and
 * then kept the matching rows in Java. The answers were correct, which is why nothing caught it:
 * the only symptom was cost, and the cost was proportional to the size of the repository rather
 * than to the size of the answer.
 *
 * <p>It sat on the createDocument hot path. CMIS name-uniqueness calls {@code getChildrenNames},
 * which lands here, so every single document creation downloaded the whole {@code childrenNames}
 * view. Measured on the dev stack at 2,722 rows: 2.6 MB / 0.90 s per create against 0.28 MB /
 * 0.095 s for the keyed query, and sixteen concurrent creates degraded to about 11 s each —
 * roughly 1.4 creates/second in total, no better than running them one at a time.
 *
 * <h2>Why the assertions are on the REQUEST</h2>
 *
 * <p>Asserting on the returned rows cannot fail: client-side filtering produced exactly the same
 * rows. These tests capture the {@link PostViewOptions} handed to the client and assert the key is
 * in it, which is the only observable difference between the two implementations. Deleting the
 * {@code builder.key(key)} line makes {@link #aKeyedQueryAsksCouchDbForThatKey()} fail.
 */
public class CloudantClientWrapperViewKeyTest {

    private static final String DB = "bedroom";
    private static final String PARENT = "e02f784f8360a02cc14d1314c10038ff";

    private ArgumentCaptor<PostViewOptions> captor;

    @SuppressWarnings("unchecked")
    private CloudantClientWrapper wrapperReturning(List<ViewResultRow> rows) {
        Cloudant client = mock(Cloudant.class);
        ServiceCall<ViewResult> call = mock(ServiceCall.class);
        Response<ViewResult> response = mock(Response.class);
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(rows);
        when(response.getResult()).thenReturn(result);
        when(call.execute()).thenReturn(response);
        captor = ArgumentCaptor.forClass(PostViewOptions.class);
        when(client.postView(captor.capture())).thenReturn(call);
        return new CloudantClientWrapper(client, DB, ObjectMapperFactory.createDefaultObjectMapper());
    }

    private static ViewResultRow row(String key, String value) {
        ViewResultRow r = mock(ViewResultRow.class);
        when(r.getKey()).thenReturn(key);
        when(r.getValue()).thenReturn(value);
        return r;
    }

    /**
     * The point of the fix. The mock answers with rows regardless, so this can only fail on the
     * request — which is exactly what the old implementation got wrong.
     */
    @Test
    public void aKeyedQueryAsksCouchDbForThatKey() {
        CloudantClientWrapper wrapper =
                wrapperReturning(new ArrayList<>(List.of(row(PARENT, "report.txt"))));

        wrapper.queryView("_repo", "childrenNames", PARENT);

        PostViewOptions sent = captor.getValue();
        assertEquals(PARENT, sent.key(),
                "the key must go to CouchDB; without it the whole view is transferred and filtered "
                        + "in Java, making one folder's name check cost the whole repository");
        assertEquals("childrenNames", sent.view());
        assertEquals("_repo", sent.ddoc());
    }

    /** A query with no key still means "the whole view" — the fix must not narrow that. */
    @Test
    public void anUnkeyedQueryStillAsksForTheWholeView() {
        CloudantClientWrapper wrapper =
                wrapperReturning(new ArrayList<>(List.of(row("a", "x"), row("b", "y"))));

        ViewResult result = wrapper.queryView("_repo", "userItemsById");

        assertNull(captor.getValue().key(), "no key was requested, so none may be sent");
        assertNotNull(result);
        assertEquals(2, result.getRows().size(), "every row must come back, not just one key's");
    }

    /**
     * The null-on-no-match contract survives the change.
     *
     * <p>It has to: the old code returned null when its client-side filter kept nothing, and
     * callers branch on that ({@code getUserItemById} treats null as "no such user"). A
     * server-side query answers an unmatched key with an empty row list, not a 404, so the
     * translation has to be explicit.
     */
    @Test
    public void aKeyThatMatchesNothingIsStillNullRatherThanAnEmptyResult() {
        CloudantClientWrapper wrapper = wrapperReturning(new ArrayList<>());

        assertNull(wrapper.queryView("_repo", "userItemsById", "nobody"),
                "callers branch on null; an empty ViewResult would look like a found-but-empty row "
                        + "set and change how they behave");
    }

    /** Rows that DO match come back untouched. */
    @Test
    public void matchingRowsAreReturnedAsIs() {
        CloudantClientWrapper wrapper = wrapperReturning(
                new ArrayList<>(List.of(row(PARENT, "a.txt"), row(PARENT, "b.txt"))));

        ViewResult result = wrapper.queryView("_repo", "childrenNames", PARENT);

        assertNotNull(result);
        assertEquals(2, result.getRows().size());
        assertEquals("a.txt", result.getRows().get(0).getValue());
    }

    /**
     * {@code include_docs} must not be requested — and this is a correctness matter, not only a
     * bandwidth one.
     *
     * <p>CouchDB rejects {@code include_docs} on a reduce view outright:
     * {@code query_parse_error: `include_docs` is invalid for reduce}. {@code countByObjectType}
     * has a reduce function, so {@code existContent} threw on every single call and answered false
     * from its catch block — it could never report that a type had instances.
     *
     * <p>What that does NOT mean: the CMIS "type still has instances" constraint is a separate
     * gap. {@code existContent}'s only consumer, {@code constraintObjectsStillExist}, has no
     * callers, and the live {@code deleteType} path uses {@code TypeManagerImpl}'s
     * {@code checkTypeHasInstances}, an unimplemented stub returning false. Deleting a populated
     * type still succeeds; that was checked against the running server after this fix, not
     * assumed.
     *
     * <p>Nothing needs the documents anyway: every caller of this overload reads
     * {@code row.getValue()}, and the views emit what they want as the value.
     */
    @Test
    public void documentsAreNotRequestedBecauseReduceViewsRejectThem() {
        CloudantClientWrapper wrapper =
                wrapperReturning(new ArrayList<>(List.of(row("cmis:document", "2"))));

        wrapper.queryView("_repo", "countByObjectType", "cmis:document");

        assertNull(captor.getValue().includeDocs(),
                "include_docs on a reduce view is a CouchDB query_parse_error, so countByObjectType "
                        + "threw on every call and existContent could only ever answer false");
    }

    /**
     * {@code update=false} is how callers ask for a stale-tolerant read; the key must not disturb
     * it, and vice versa.
     */
    @Test
    public void theUpdateFlagAndTheKeyAreIndependent() {
        CloudantClientWrapper wrapper =
                wrapperReturning(new ArrayList<>(List.of(row(PARENT, "a.txt"))));

        wrapper.queryView("_repo", "childrenNames", PARENT, false);

        assertEquals("false", captor.getValue().update());
        assertEquals(PARENT, captor.getValue().key());
    }
}
