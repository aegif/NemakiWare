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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.gson.internal.LazilyParsedNumber;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.PostViewOptions;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.model.couch.CouchDocument;

/**
 * The typed view query must not ask CouchDB for documents it was already sent.
 *
 * <h2>What was wrong (ledger C3)</h2>
 *
 * <p>{@code queryView(designDoc, viewName, key, Class)} always passed {@code includeDocs(true)},
 * then built its objects from {@code row.getDoc()}. But the views it is used with are declared
 * {@code emit(..., doc)} — the value already IS the document. So CouchDB looked every row up again
 * by id and put a second copy of it in the response. One HTTP request, twice the payload, on
 * version series, relationships, PWCs, type definitions, user and group items, and about two dozen
 * more.
 *
 * <p>This is the same defect {@code getChildren} was corrected for — measured there at 40 ms /
 * 93 KB versus 5 ms / 49 KB on a 50-child folder — left behind in the shared typed overload.
 *
 * <p>Audited against the live design document on 2026-08-14: of 53 views, 48 emit {@code doc} and
 * 5 emit a scalar. This overload is reached by 20 distinct view names, none of them among those
 * five. When a value is not a document the conversion falls back to a read by id — keyed on
 * {@code _rev}, since a projection is also a {@code Map} and would otherwise pass for one.
 *
 * <h2>Why a number is in the fixture</h2>
 *
 * <p>Reading the value means the fields arrive as the Cloudant SDK's Gson parsed them, so numbers
 * are {@code LazilyParsedNumber}. Jackson treats that as an unknown bean rather than a number, and
 * <b>it does not throw</b> — it produces a document whose dates are quietly wrong. That is the one
 * way this change can fail without anything looking broken, so the fixture carries a real
 * {@code LazilyParsedNumber} and the assertion is on the converted value.
 */
public class CloudantClientWrapperViewValueTest {

	private static final String DB = "bedroom";
	private static final String DOC_ID = "614f4643d5c7eef37e94a5426113e2e2";
	private static final long CREATED = 1754584080123L;

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
		// The id fallback reads a document that is genuinely NOT THERE. Modelled explicitly:
		// leaving getDocument unstubbed used to work only because an unstubbed mock NPEs and
		// the wrapper swallowed that as "startup", which is exactly the grace that is now
		// declared rather than guessed. A not-found is the answer this test means.
		ServiceCall<com.ibm.cloud.cloudant.v1.model.Document> docCall = mock(ServiceCall.class);
		Response<com.ibm.cloud.cloudant.v1.model.Document> docResponse = mock(Response.class);
		when(docResponse.getResult()).thenReturn(null);
		when(docCall.execute()).thenReturn(docResponse);
		when(client.getDocument(any(com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.class)))
				.thenReturn(docCall);
		return new CloudantClientWrapper(client, DB, ObjectMapperFactory.createDefaultObjectMapper());
	}

	/** What {@code emit(..., doc)} puts in the value, numbers as Gson parses them. */
	private static ViewResultRow rowEmittingTheDocument() {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("_id", DOC_ID);
		value.put("_rev", "1-9f3b1c2d4e5f60718293a4b5c6d7e8f9");
		value.put("type", "cmis:document");
		value.put("objectType", "cmis:document");
		value.put("name", "報告書.txt");
		value.put("created", new LazilyParsedNumber(Long.toString(CREATED)));
		value.put("latestVersion", Boolean.TRUE);

		ViewResultRow row = mock(ViewResultRow.class);
		when(row.getId()).thenReturn(DOC_ID);
		when(row.getValue()).thenReturn(value);
		return row;
	}

	/** The request is where the saving is, so that is what is asserted. */
	@Test
	public void theTypedQueryDoesNotAskForTheDocuments() {
		CloudantClientWrapper wrapper =
				wrapperReturning(new ArrayList<>(List.of(rowEmittingTheDocument())));

		wrapper.queryView("_repo", "versionSeries", "vs-1", CouchDocument.class);

		PostViewOptions sent = captor.getValue();
		assertFalse(Boolean.TRUE.equals(sent.includeDocs()),
				"the view emits the document as its value; include_docs makes CouchDB send a "
						+ "second copy of every row");
	}

	/**
	 * A projection must not be mistaken for a document.
	 *
	 * <p>Found in review: the first version accepted any {@code Map} and injected {@code _id} from
	 * the row, so a view rewritten to {@code emit(key, {name: doc.name})} would have been converted
	 * into a PARTIAL model and the id fallback — the thing that is supposed to catch exactly this —
	 * would never have run. {@code _rev} is the discriminator, because every full document carries
	 * one and no projection does.
	 */
	@Test
	public void aProjectionFallsBackToAReadById() {
		Map<String, Object> projection = new LinkedHashMap<>();
		projection.put("name", "報告書.txt"); // no _rev: this is not a document

		ViewResultRow row = mock(ViewResultRow.class);
		when(row.getId()).thenReturn(DOC_ID);
		when(row.getValue()).thenReturn(projection);

		CloudantClientWrapper wrapper = wrapperReturning(new ArrayList<>(List.of(row)));

		// The read by id goes back to the same mocked client, which has no document to answer
		// with; the point is that the projection was REFUSED rather than converted into a
		// half-populated object.
		List<CouchDocument> found =
				wrapper.queryView("_repo", "versionSeries", "vs-1", CouchDocument.class);

		assertTrue(found == null || found.isEmpty(),
				"a projection converted into a partial document is worse than no answer — it "
						+ "looks like a real object with fields silently missing");
	}

	/**
	 * The silent-corruption path: the value converts, and its numbers survive as numbers.
	 */
	@Test
	public void theDocumentIsBuiltFromTheEmittedValue() {
		CloudantClientWrapper wrapper =
				wrapperReturning(new ArrayList<>(List.of(rowEmittingTheDocument())));

		List<CouchDocument> found =
				wrapper.queryView("_repo", "versionSeries", "vs-1", CouchDocument.class);

		assertNotNull(found, "the row carried a document as its value");
		assertEquals(1, found.size());
		CouchDocument doc = found.get(0);
		assertEquals(DOC_ID, doc.getId(), "the id must survive the value path");
		assertEquals("報告書.txt", doc.getName());
		assertNotNull(doc.getCreated(),
				"a LazilyParsedNumber that Jackson turned into a bean does not throw — it "
						+ "produces a document whose dates are wrong, which is the only way this "
						+ "change fails quietly");
		assertEquals(CREATED, doc.getCreated().getTimeInMillis());
	}
}
