package jp.aegif.nemaki.rest;

import jakarta.ws.rs.core.MediaType;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Static checks for client-supplied cloud deep links ({@link CloudDriveResource#isAllowedCloudUrl}).
 */
class CloudDriveResourceCloudUrlTest {

    @Test
    void googleAllowsDocsAndDriveHosts() {
        assertTrue(CloudDriveResource.isAllowedCloudUrl("google",
                "https://docs.google.com/document/d/abc/edit"));
        assertTrue(CloudDriveResource.isAllowedCloudUrl("google",
                "https://drive.google.com/file/d/xyz/view"));
    }

    @Test
    void googleRejectsNonGoogleHttps() {
        assertFalse(CloudDriveResource.isAllowedCloudUrl("google", "https://evil.example.com/phish"));
    }

    @Test
    void microsoftAllowsCommonGraphWebUrlHosts() {
        assertTrue(CloudDriveResource.isAllowedCloudUrl("microsoft",
                "https://contoso-my.sharepoint.com/personal/user/Documents/a.docx"));
        assertTrue(CloudDriveResource.isAllowedCloudUrl("microsoft",
                "https://contoso.sharepoint.de/sites/s/Shared%20Documents/x.xlsx"));
        assertTrue(CloudDriveResource.isAllowedCloudUrl("microsoft",
                "https://teams.microsoft.com/l/file/abc"));
        assertTrue(CloudDriveResource.isAllowedCloudUrl("microsoft",
                "https://onedrive.live.com/edit?id=ABC"));
    }

    @Test
    void microsoftRejectsArbitraryHttps() {
        assertFalse(CloudDriveResource.isAllowedCloudUrl("microsoft", "https://attacker.example.com/"));
    }

    @Test
    void rejectsHttpAndBlank() {
        assertFalse(CloudDriveResource.isAllowedCloudUrl("google", "http://docs.google.com/doc"));
        assertFalse(CloudDriveResource.isAllowedCloudUrl("google", ""));
    }

    /** Aligns with {@link jp.aegif.nemaki.rest.importexport.ImportExportUtils#guessMimeType} + csv/svg fallbacks. */
    @Test
    void inferMimeType_legacyOfficeAndOoxml() {
        assertEquals("application/msword", CloudDriveResource.inferMimeType("Report.doc"));
        assertEquals("application/vnd.ms-excel", CloudDriveResource.inferMimeType("sheet.xls"));
        assertEquals("application/vnd.ms-powerpoint", CloudDriveResource.inferMimeType("slides.ppt"));
        assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                CloudDriveResource.inferMimeType("new.docx"));
    }

    @Test
    void inferMimeType_csvAndSvgFallbacks() {
        assertEquals("text/csv", CloudDriveResource.inferMimeType("data.csv"));
        assertEquals("image/svg+xml", CloudDriveResource.inferMimeType("icon.svg"));
    }

    @Test
    void inferMimeType_unknownUsesOctetStream() {
        assertEquals("application/octet-stream", CloudDriveResource.inferMimeType("binary.bin"));
        assertEquals("application/octet-stream", CloudDriveResource.inferMimeType(null));
    }

    /** Legacy import path must use the same rules as {@link CloudDriveResource#inferMimeType} (not a hardcoded OOXML-only map). */
    @Test
    void resolveLegacyCloudImportMimeType_matchesInferMimeForLegacyOffice() {
        assertEquals("application/msword", CloudDriveResource.resolveLegacyCloudImportMimeType("Report.doc", null));
        assertEquals("application/vnd.ms-excel", CloudDriveResource.resolveLegacyCloudImportMimeType("sheet.xls", null));
        assertEquals("application/vnd.ms-powerpoint", CloudDriveResource.resolveLegacyCloudImportMimeType("slides.ppt", null));
    }

    @Test
    void resolveLegacyCloudImportMimeType_usesPartContentTypeWhenFilenameUnknown() {
        FormDataBodyPart part = mock(FormDataBodyPart.class);
        when(part.getMediaType()).thenReturn(new MediaType("application", "pdf"));
        assertEquals("application/pdf", CloudDriveResource.resolveLegacyCloudImportMimeType("blob", part));
    }

    @Test
    void resolveLegacyCloudImportMimeType_keepsOctetStreamWhenPartAlsoGeneric() {
        FormDataBodyPart part = mock(FormDataBodyPart.class);
        when(part.getMediaType()).thenReturn(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        assertEquals("application/octet-stream", CloudDriveResource.resolveLegacyCloudImportMimeType("unknown.bin", part));
    }
}
