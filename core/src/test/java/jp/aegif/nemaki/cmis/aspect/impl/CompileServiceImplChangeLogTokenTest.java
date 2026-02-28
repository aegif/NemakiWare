package jp.aegif.nemaki.cmis.aspect.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.Before;
import org.junit.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;

/**
 * Unit tests for ContentChanges token progression logic in
 * {@link CompileServiceImpl#compileChangeDataList}.
 *
 * Verifies that the changeLogToken advances only through the
 * consecutive-success prefix of the batch, so that failed events
 * (and all subsequent events) are re-fetched on the next poll.
 */
public class CompileServiceImplChangeLogTokenTest {

	private static final String REPO_ID = "bedroom";

	private CompileServiceImpl compileService;
	private ContentService contentService;
	private RepositoryService repositoryService;
	private TypeManager typeManager;
	private CallContext callContext;

	@Before
	public void setUp() {
		compileService = new CompileServiceImpl();

		contentService = mock(ContentService.class);
		repositoryService = mock(RepositoryService.class);
		typeManager = mock(TypeManager.class);
		callContext = mock(CallContext.class);

		compileService.setContentService(contentService);
		compileService.setRepositoryService(repositoryService);
		compileService.setTypeManager(typeManager);

		// repositoryService.getRepositoryInfo() is called for hasMoreItems check
		RepositoryInfo repoInfo = mock(RepositoryInfo.class);
		when(repoInfo.getLatestChangeLogToken()).thenReturn("999");
		when(repositoryService.getRepositoryInfo(REPO_ID)).thenReturn((jp.aegif.nemaki.cmis.factory.info.RepositoryInfo) repoInfo);

		// typeManager returns non-null for valid types (cmis:document, cmis:folder)
		when(typeManager.getTypeDefinition(eq(REPO_ID), anyString())).thenReturn(mock(org.apache.chemistry.opencmis.commons.definitions.TypeDefinition.class));
	}

	/**
	 * Helper to create a Change event with the given token and objectId.
	 * Sets minimal required fields for compileChangeObjectData to succeed.
	 */
	private Change createChange(String token, String objectId, ChangeType changeType) {
		Change change = new Change();
		change.setToken(token);
		change.setObjectId(objectId);
		change.setChangeType(changeType);
		change.setBaseType("cmis:folder");
		change.setObjectType("cmis:folder");
		change.setName("test-" + objectId);
		GregorianCalendar now = new GregorianCalendar();
		change.setTime(now);
		return change;
	}

	/**
	 * Helper to set up a Content mock that contentService.getContent() will return.
	 */
	private void stubContent(String objectId) {
		Folder folder = mock(Folder.class);
		when(folder.getId()).thenReturn(objectId);
		when(contentService.getContent(REPO_ID, objectId)).thenReturn(folder);
	}

	/**
	 * Helper to make contentService.getContent() throw an exception for a given objectId,
	 * simulating a compile failure.
	 */
	private void stubContentFailure(String objectId) {
		when(contentService.getContent(REPO_ID, objectId))
				.thenThrow(new RuntimeException("Simulated failure for " + objectId));
	}

	// -----------------------------------------------------------------------
	// Test: All events succeed → token advances to last event
	// -----------------------------------------------------------------------
	@Test
	public void testAllSucceed_tokenAdvancesToLast() {
		stubContent("obj-A");
		stubContent("obj-B");
		stubContent("obj-C");

		List<Change> changes = Arrays.asList(
				createChange("10", "obj-A", ChangeType.CREATED),
				createChange("20", "obj-B", ChangeType.CREATED),
				createChange("30", "obj-C", ChangeType.CREATED));

		Holder<String> token = new Holder<>("5");
		ObjectList result = compileService.compileChangeDataList(
				callContext, REPO_ID, changes, token,
				false, null, false, false);

		assertEquals("Token should advance to last event", "30", token.getValue());
		assertEquals("All 3 events should be in result", 3, result.getObjects().size());
	}

	// -----------------------------------------------------------------------
	// Test: Middle event fails → token stops at last consecutive success
	// -----------------------------------------------------------------------
	@Test
	public void testMiddleFailure_tokenStopsBeforeFailure() {
		stubContent("obj-A");
		stubContentFailure("obj-B"); // This one fails
		stubContent("obj-C");

		List<Change> changes = Arrays.asList(
				createChange("10", "obj-A", ChangeType.CREATED),
				createChange("20", "obj-B", ChangeType.CREATED),
				createChange("30", "obj-C", ChangeType.CREATED));

		Holder<String> token = new Holder<>("5");
		ObjectList result = compileService.compileChangeDataList(
				callContext, REPO_ID, changes, token,
				false, null, false, false);

		assertEquals("Token should stop at last consecutive success (A)",
				"10", token.getValue());
		// A succeeds, B fails (skipped), C succeeds but token frozen
		assertEquals("2 events should be compiled (A and C)", 2, result.getObjects().size());
	}

	// -----------------------------------------------------------------------
	// Test: First event fails → token does not advance at all
	// -----------------------------------------------------------------------
	@Test
	public void testFirstFailure_tokenDoesNotAdvance() {
		stubContentFailure("obj-A"); // First one fails
		stubContent("obj-B");
		stubContent("obj-C");

		List<Change> changes = Arrays.asList(
				createChange("10", "obj-A", ChangeType.CREATED),
				createChange("20", "obj-B", ChangeType.CREATED),
				createChange("30", "obj-C", ChangeType.CREATED));

		Holder<String> token = new Holder<>("5");
		ObjectList result = compileService.compileChangeDataList(
				callContext, REPO_ID, changes, token,
				false, null, false, false);

		assertEquals("Token should not advance when first event fails",
				"5", token.getValue());
		assertEquals("2 events should be compiled (B and C)", 2, result.getObjects().size());
	}

	// -----------------------------------------------------------------------
	// Test: All events fail → token does not advance at all
	// -----------------------------------------------------------------------
	@Test
	public void testAllFail_tokenDoesNotAdvance() {
		stubContentFailure("obj-A");
		stubContentFailure("obj-B");
		stubContentFailure("obj-C");

		List<Change> changes = Arrays.asList(
				createChange("10", "obj-A", ChangeType.CREATED),
				createChange("20", "obj-B", ChangeType.CREATED),
				createChange("30", "obj-C", ChangeType.CREATED));

		Holder<String> token = new Holder<>("5");
		ObjectList result = compileService.compileChangeDataList(
				callContext, REPO_ID, changes, token,
				false, null, false, false);

		assertEquals("Token should not advance when all events fail",
				"5", token.getValue());
		assertEquals("No events should be compiled", 0, result.getObjects().size());
	}

	// -----------------------------------------------------------------------
	// Test: Retry scenario — failed event is re-fetched and succeeds
	// -----------------------------------------------------------------------
	@Test
	public void testRetryAfterFailure_eventReprocessed() {
		// First poll: A succeeds, B fails, C succeeds
		stubContent("obj-A");
		stubContentFailure("obj-B");
		stubContent("obj-C");

		List<Change> batch1 = Arrays.asList(
				createChange("10", "obj-A", ChangeType.CREATED),
				createChange("20", "obj-B", ChangeType.CREATED),
				createChange("30", "obj-C", ChangeType.CREATED));

		Holder<String> token = new Holder<>("5");
		compileService.compileChangeDataList(
				callContext, REPO_ID, batch1, token,
				false, null, false, false);

		assertEquals("After first poll, token should be at A's position",
				"10", token.getValue());

		// Second poll: simulates re-fetch starting from token "10".
		// CouchDB startkey is inclusive, so the delegate would skip the
		// already-delivered event and return B, C, D.
		// Now B succeeds on retry — reset mock to clear the thenThrow stub.
		reset(contentService);
		stubContent("obj-B");
		stubContent("obj-C");
		stubContent("obj-D");

		List<Change> batch2 = Arrays.asList(
				createChange("20", "obj-B", ChangeType.CREATED),
				createChange("30", "obj-C", ChangeType.CREATED),
				createChange("40", "obj-D", ChangeType.CREATED));

		ObjectList result2 = compileService.compileChangeDataList(
				callContext, REPO_ID, batch2, token,
				false, null, false, false);

		assertEquals("After retry, token should advance to D",
				"40", token.getValue());
		assertEquals("All 3 events should be compiled", 3, result2.getObjects().size());
	}

	// -----------------------------------------------------------------------
	// Test: Empty change list → token unchanged
	// -----------------------------------------------------------------------
	@Test
	public void testEmptyChangeList_tokenUnchanged() {
		List<Change> changes = new ArrayList<>();

		Holder<String> token = new Holder<>("5");
		ObjectList result = compileService.compileChangeDataList(
				callContext, REPO_ID, changes, token,
				false, null, false, false);

		assertEquals("Token should not change for empty list", "5", token.getValue());
		assertEquals("No events should be in result", 0, result.getObjects().size());
	}

	// -----------------------------------------------------------------------
	// Test: Null change list → token unchanged
	// -----------------------------------------------------------------------
	@Test
	public void testNullChangeList_tokenUnchanged() {
		Holder<String> token = new Holder<>("5");
		ObjectList result = compileService.compileChangeDataList(
				callContext, REPO_ID, null, token,
				false, null, false, false);

		assertEquals("Token should not change for null list", "5", token.getValue());
		assertEquals("No events should be in result", 0, result.getObjects().size());
	}
}
