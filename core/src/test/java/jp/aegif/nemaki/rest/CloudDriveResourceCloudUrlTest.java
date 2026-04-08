package jp.aegif.nemaki.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
