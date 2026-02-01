package jp.aegif.nemaki.businesslogic.impl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import jp.aegif.nemaki.cmis.service.ObjectService;
import org.apache.chemistry.opencmis.commons.data.ContentStream;

@RunWith(MockitoJUnitRunner.class)
public class CloudDriveServiceImplTest {

	@Mock
	private ObjectService objectService;

	@InjectMocks
	private CloudDriveServiceImpl service;

	@Test
	public void testGetCloudFileUrl_Google() {
		String url = service.getCloudFileUrl("google", "file123");
		assertEquals("https://drive.google.com/file/d/file123/edit", url);
	}

	@Test
	public void testGetCloudFileUrl_Microsoft() {
		String url = service.getCloudFileUrl("microsoft", "file456");
		assertEquals("https://onedrive.live.com/edit?id=file456", url);
	}

	@Test
	public void testGetCloudFileUrl_UnknownProvider() {
		String url = service.getCloudFileUrl("dropbox", "file789");
		assertNull(url);
	}

	@Test
	public void testGetCloudFileUrl_NullFileId() {
		// Should still construct URL (no null check in implementation)
		String url = service.getCloudFileUrl("google", null);
		assertEquals("https://drive.google.com/file/d/null/edit", url);
	}

	@Test(expected = RuntimeException.class)
	public void testPushToCloud_NoContentStream() {
		when(objectService.getContentStream(any(), eq("bedroom"), eq("doc1"), any(), any(), any()))
				.thenReturn(null);
		service.pushToCloud("bedroom", "doc1", "google", "token");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testPushToCloud_UnknownProvider() {
		ContentStream cs = mock(ContentStream.class);
		when(cs.getStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
		when(objectService.getContentStream(any(), eq("bedroom"), eq("doc1"), any(), any(), any()))
				.thenReturn(cs);
		service.pushToCloud("bedroom", "doc1", "dropbox", "token");
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testPullFromCloud_ThrowsUnsupported() {
		service.pullFromCloud("bedroom", "doc1", "google", "token");
	}

	@Test
	public void testDeleteFromCloud_UnknownProvider_CaughtByTryCatch() {
		// Implementation catches exceptions in try-catch, so no exception propagates
		service.deleteFromCloud("dropbox", "file1", "token");
	}
}
