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
		String url = service.getCloudFileUrl("google", null);
		assertNull("Null fileId should return null", url);
	}

	@Test
	public void testGetCloudFileUrl_EmptyFileId() {
		String url = service.getCloudFileUrl("google", "");
		assertNull("Empty fileId should return null", url);
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

	@Test(expected = IllegalArgumentException.class)
	public void testPullFromCloudByFileId_UnknownProvider() {
		service.pullFromCloudByFileId("dropbox", "file1", "token");
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testPullFromCloud_Microsoft_ThrowsUnsupported() {
		service.pullFromCloud("bedroom", "doc1", "microsoft", "token");
	}

	@Test
	public void testGetCloudFileUrl_Google_SpecialCharsInFileId() {
		String url = service.getCloudFileUrl("google", "abc-123_XYZ");
		assertEquals("https://drive.google.com/file/d/abc-123_XYZ/edit", url);
	}

	@Test
	public void testGetCloudFileUrl_Microsoft_SpecialCharsInFileId() {
		String url = service.getCloudFileUrl("microsoft", "abc-123!XYZ");
		assertEquals("https://onedrive.live.com/edit?id=abc-123!XYZ", url);
	}

	@Test
	public void testDeleteFromCloud_Google_NoException() {
		// Google delete calls Google Drive API; with null credentials it will fail
		// but the try-catch should swallow the exception
		service.deleteFromCloud("google", "file1", "invalid-token");
	}

	@Test
	public void testDeleteFromCloud_Microsoft_NoException() {
		service.deleteFromCloud("microsoft", "file1", "invalid-token");
	}
}
