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
package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;

/**
 * Resolving one path segment must not fetch the same document three times.
 *
 * <h2>What was wrong (ledger C1 / C2)</h2>
 *
 * <p>{@code childByName} is declared {@code emit({parentId, name}, doc)} and {@code children} is
 * {@code emit(doc.parentId, doc)} — in both, the value IS the document. The lookup nevertheless
 * asked for {@code include_docs=true}, so CouchDB looked the document up again by id and sent a
 * second copy, and then the code threw both away and called {@code getContent} for a third.
 *
 * <p>Path resolution walks one segment at a time, so {@code /a/b/c/d} paid that on every segment.
 * {@code getChildren} had already been corrected the same way — measured there at 40 ms / 93 KB
 * versus 5 ms / 49 KB on a 50-child folder — and this is the same fix applied to the two
 * name-lookup paths it left behind.
 *
 * <h2>Why the assertions look like this</h2>
 *
 * <p>{@code include_docs} is asserted on the parameter map rather than inferred from behaviour,
 * because <b>omitting it is not the same as disabling it</b>: {@code CloudantClientWrapper.queryView}
 * defaults it back to {@code true} when the caller says nothing, so a "fix" that simply deletes
 * the line changes nothing on the wire and no behavioural test would notice.
 *
 * <p>The third read is pinned by verifying the client is never asked for a document by id. That
 * is the round trip being removed; asserting only on the returned object would pass either way.
 */
class ChildByNameViewValueTest {

	private static final String REPO = "bedroom";
	private static final String PARENT = "4383c1a96093a7526774f8d2db12f00c";
	private static final String CHILD_ID = "614f4643d5c7eef37e94a5426113e2e2";

	private CloudantClientWrapper client;

	private ContentDaoServiceImpl daoWith(ViewResult result, boolean viewAvailable) {
		client = mock(CloudantClientWrapper.class);
		when(client.isViewAvailable(anyString(), anyString())).thenReturn(viewAvailable);
		when(client.queryView(anyString(), anyString(), any(Map.class))).thenReturn(result);
		CloudantClientPool pool = mock(CloudantClientPool.class);
		when(pool.getClient(anyString())).thenReturn(client);
		ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
		dao.setConnectorPool(pool);
		return dao;
	}

	/** The value shape the views emit: the whole document. */
	private static Map<String, Object> documentValue(String name) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("_id", CHILD_ID);
		v.put("_rev", "1-9f3b1c2d4e5f60718293a4b5c6d7e8f9");
		v.put("type", "cmis:document");
		v.put("objectType", "cmis:document");
		v.put("name", name);
		v.put("parentId", PARENT);
		v.put("creator", "admin");
		v.put("modifier", "admin");
		v.put("latestVersion", Boolean.TRUE);
		v.put("aclInherited", Boolean.TRUE);
		return v;
	}

	private static ViewResult resultWith(Map<String, Object>... values) {
		ViewResult result = mock(ViewResult.class);
		java.util.List<ViewResultRow> rows = new java.util.ArrayList<>();
		for (Map<String, Object> v : values) {
			ViewResultRow row = mock(ViewResultRow.class);
			when(row.getValue()).thenReturn(v);
			when(row.getId()).thenReturn((String) v.get("_id"));
			rows.add(row);
		}
		when(result.getRows()).thenReturn(rows);
		return result;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturedParams() {
		ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
		verify(client).queryView(anyString(), anyString(), captor.capture());
		return captor.getValue();
	}

	/** The view path: one query, no second copy, no third read. */
	@Test
	void theViewPathReadsTheEmittedValue() {
		ContentDaoServiceImpl dao = daoWith(resultWith(documentValue("報告書.txt")), true);

		Content found = dao.getChildByName(REPO, PARENT, "報告書.txt");

		assertNotNull(found, "the child is in the view result");
		assertEquals(CHILD_ID, found.getId());
		assertEquals("報告書.txt", found.getName());

		assertEquals(Boolean.FALSE, capturedParams().get("include_docs"),
				"include_docs must be sent as false; leaving it out makes queryView default it "
						+ "back to true, so the second copy would still be on the wire");
		verify(client, never()).get(anyString());
	}

	/** The fallback path, used when the childByName view does not exist. */
	@Test
	void theFallbackFiltersOnTheEmittedValue() {
		ContentDaoServiceImpl dao = daoWith(
				resultWith(documentValue("別のファイル.txt"), documentValue("報告書.txt")), false);

		Content found = dao.getChildByName(REPO, PARENT, "報告書.txt");

		assertNotNull(found,
				"the name filter has to read the emitted value — with include_docs off, "
						+ "row.getDoc() is null and a filter reading it matches nothing");
		assertEquals("報告書.txt", found.getName());
		assertEquals(Boolean.FALSE, capturedParams().get("include_docs"));
		verify(client, never()).get(anyString());
	}

	/** A child that genuinely is not there must still be reported absent. */
	@Test
	void anAbsentChildIsStillAbsent() {
		ContentDaoServiceImpl dao = daoWith(resultWith(documentValue("別のファイル.txt")), false);

		assertNull(dao.getChildByName(REPO, PARENT, "報告書.txt"));
	}

	/**
	 * The other half of "read the value": if the value is not a document, the child must not be
	 * reported as missing. A false absence here surfaces as "path not found" for an object that
	 * exists, which is worse than the round trip this change removes.
	 */
	@Test
	void aValueThatIsNotADocumentFallsBackToAReadById() {
		ViewResult result = mock(ViewResult.class);
		ViewResultRow row = mock(ViewResultRow.class);
		when(row.getValue()).thenReturn("not-a-document");
		when(row.getId()).thenReturn(CHILD_ID);
		when(result.getRows()).thenReturn(List.of(row));

		ContentDaoServiceImpl dao = daoWith(result, true);

		// getContent goes back to the client; the point is that it is ATTEMPTED, not that this
		// stub can satisfy it.
		dao.getChildByName(REPO, PARENT, "報告書.txt");

		verify(client).get(eq(CHILD_ID));
	}
}
