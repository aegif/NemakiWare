package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Property;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CanonicalImportServiceTest {

    private CanonicalImportServiceImpl service;
    private ConnectorDefinitionService connectorService;
    private ImportProfileDefinitionService profileService;
    private ObjectService objectService;
    private ContentService contentService;
    private jp.aegif.nemaki.cmis.service.VersioningService versioningService;

    @BeforeEach
    void setUp() {
        service = new CanonicalImportServiceImpl();
        connectorService = mock(ConnectorDefinitionService.class);
        profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(ObjectService.class);
        contentService = mock(ContentService.class);
        versioningService = mock(jp.aegif.nemaki.cmis.service.VersioningService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setVersioningService(versioningService);
    }

    @Test
    void testExecuteProfileNotFound() {
        when(profileService.get("no-such-profile")).thenReturn(null);
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("no-such-profile");
        req.setConnectorId("conn1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("not found"));
    }

    @Test
    void testExecuteProfileDisabled() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(false);
        when(profileService.get("p1")).thenReturn(profile);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("disabled"));
    }

    @Test
    void testExecuteConnectorNotFound() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        when(profileService.get("p1")).thenReturn(profile);
        when(connectorService.get("no-conn")).thenReturn(null);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("no-conn");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("not found"));
    }

    @Test
    void testExecuteConnectorNotAllowed() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setRepositoryId("bedroom");
        profile.setAllowedConnectorIds(List.of("allowed-only"));
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("other-conn");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        when(connectorService.get("other-conn")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("other-conn");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("not allowed"));
    }

    @Test
    void testExecuteArchetypeNotAllowed() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setRepositoryId("bedroom");
        profile.setAllowedArchetypes(List.of(SourceArchetype.COMPOUND_NOTE));
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        when(connectorService.get("c1")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("not allowed"));
    }

    @Test
    void testExecuteProfileRepositoryMismatch() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setRepositoryId("canopy");
        when(profileService.get("p1")).thenReturn(profile);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("scoped to repository"));
    }

    @Test
    void testExecuteDryRun() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        when(connectorService.get("c1")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");
        req.setDryRun(true);

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.dryRun());
        // Verify NO repository side effects
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(objectService, never()).createRelationship(any(), any(), any(), any(), any(), any(), any());
        verify(versioningService, never()).checkOut(any(), any(), any(), any(), any());
        verify(versioningService, never()).checkIn(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any());
        verify(contentService, never()).update(any(), any(), any());
    }

    @Test
    void testExecuteNoTargetFolder() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId(null);
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        when(connectorService.get("c1")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("obj1");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("targetFolderId"));
    }

    @Test
    void testChatContextRejectsWhenChannelIdMissing() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);
        connector.setSourceSystem("slack");
        when(connectorService.get("c1")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("msg-1");
        // No metadata.channelId

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("channelId"));
    }

    @Test
    void testMessageContextRejectsWhenMailboxIdMissing() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.MESSAGE_CONTEXT);
        connector.setSourceSystem("imap");
        when(connectorService.get("c1")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("msg-1");
        // No metadata.mailboxId

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("mailboxId"));
    }

    @Test
    void testAttachmentSourceObjectTypeImportsSuccessfully() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.COMPOUND_NOTE);
        connector.setSourceSystem("notion");
        when(connectorService.get("c1")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("attachment-id");

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("attach-1");
        req.setSourceObjectType("attachment");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());
    }

    @Test
    void testMessageContextAttachmentImportsSuccessfully() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.MESSAGE_CONTEXT);
        connector.setSourceSystem("imap");
        when(connectorService.get("c1")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("att-obj-id");

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("att-1");
        req.setSourceObjectType("attachment");
        req.setMetadata(java.util.Map.of("mailboxId", "INBOX", "messageStableId", "msg-1"));

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());
        assertEquals("att-obj-id", result.objectId());
    }

    @Test
    void testExecuteMailImportRequiresContentStream() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("msg-1");
        // No content stream

        ExternalIngestResult result = service.executeMailImport(mock(CallContext.class), req);
        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("Content stream"));
    }

    @Test
    void testExecuteHappyPath() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("google_drive");
        when(connectorService.get("c1")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("new-obj-id");

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("file-123");
        req.setSourceObjectType("files");
        req.setFileName("test.txt");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());
        assertEquals("new-obj-id", result.objectId());
    }

    // ── Cloud Drive Pipeline Integration Tests ────────────────────

    private Content createMockContent(String objectId) {
        Content content = new Content();
        content.setId(objectId);
        content.setAspects(new ArrayList<>());
        content.setSecondaryIds(new ArrayList<>());
        return content;
    }

    @Test
    void testCloudDriveMetadataAppliedWhenCloudProviderInMetadata() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("google-drive-default");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("google");
        when(connectorService.get("google-drive-default")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("cloud-doc-id");

        // Mock contentService.getContent to return a Content with mutable aspects
        Content mockContent = createMockContent("cloud-doc-id");
        when(contentService.getContent("bedroom", "cloud-doc-id")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("google-drive-default");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("google-file-abc123");
        req.setSourceObjectType("file");
        req.setFileName("report.docx");
        req.setMetadata(Map.of(
                "cloudProvider", "google",
                "cloudFileId", "google-file-abc123",
                "cloudFileUrl", "https://docs.google.com/document/d/abc123/edit"
        ));

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());
        assertEquals("cloud-doc-id", result.objectId());

        // Verify contentService.update was called with Content containing cloudDriveMetadata
        ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
        verify(contentService, atLeastOnce()).update(any(), eq("bedroom"), contentCaptor.capture());

        Content updatedContent = contentCaptor.getValue();
        // Check secondaryIds includes cloudDriveMetadata
        assertTrue(updatedContent.getSecondaryIds().contains("nemaki:cloudDriveMetadata"),
                "secondaryIds should contain nemaki:cloudDriveMetadata");

        // Check aspect properties
        Aspect cloudAspect = updatedContent.getAspects().stream()
                .filter(a -> "nemaki:cloudDriveMetadata".equals(a.getName()))
                .findFirst().orElse(null);
        assertNotNull(cloudAspect, "nemaki:cloudDriveMetadata aspect should exist");

        Map<String, Object> propValues = new java.util.HashMap<>();
        for (Property p : cloudAspect.getProperties()) {
            propValues.put(p.getKey(), p.getValue());
        }
        assertEquals("google", propValues.get("nemaki:cloudProvider"));
        assertEquals("google-file-abc123", propValues.get("nemaki:cloudFileId"));
        assertEquals("https://docs.google.com/document/d/abc123/edit", propValues.get("nemaki:cloudFileUrl"));
        assertNotNull(propValues.get("nemaki:cloudLastSyncedAt"));
    }

    @Test
    void testCloudDriveMetadataNotAppliedWhenNoCloudProvider() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("box");
        when(connectorService.get("c1")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("box-doc-id");

        Content mockContent = createMockContent("box-doc-id");
        when(contentService.getContent("bedroom", "box-doc-id")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("box-file-xyz");
        req.setSourceObjectType("file");
        req.setFileName("data.xlsx");
        // No cloudProvider in metadata — just a plain Box import

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());

        // Verify cloudDriveMetadata is NOT applied
        ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
        verify(contentService, atLeastOnce()).update(any(), eq("bedroom"), contentCaptor.capture());

        Content updatedContent = contentCaptor.getValue();
        assertFalse(updatedContent.getSecondaryIds().contains("nemaki:cloudDriveMetadata"),
                "nemaki:cloudDriveMetadata should NOT be applied without cloudProvider");
    }

    @Test
    void testExecuteWithAutoResolveForCloudImport() {
        // Set up connector that can be found by sourceSystem + archetype
        ConnectorDefinition googleConn = new ConnectorDefinition();
        googleConn.setConnectorId("google-drive-default");
        googleConn.setEnabled(true);
        googleConn.setSourceArchetype(SourceArchetype.FILE_SHARE);
        googleConn.setSourceSystem("google");
        when(connectorService.findBySystemAndArchetype("google", SourceArchetype.FILE_SHARE)).thenReturn(googleConn);
        when(connectorService.get("google-drive-default")).thenReturn(googleConn);

        // Set up profile that can be found by repository + archetype + connectorId
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("cloud-import-bedroom");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("cloud-import-bedroom")).thenReturn(profile);
        when(profileService.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, "google-drive-default"))
                .thenReturn(profile);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("auto-resolved-doc-id");

        Content mockContent = createMockContent("auto-resolved-doc-id");
        when(contentService.getContent("bedroom", "auto-resolved-doc-id")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("google-file-xyz");
        req.setSourceObjectType("file");
        req.setFileName("presentation.pptx");
        req.setMetadata(Map.of(
                "cloudProvider", "google",
                "cloudFileId", "google-file-xyz",
                "cloudFileUrl", "https://docs.google.com/presentation/d/xyz"
        ));
        // No profileId or connectorId — auto-resolve should find them

        ExternalIngestResult result = service.executeWithAutoResolve(
                mock(CallContext.class), req, "google", SourceArchetype.FILE_SHARE);
        assertTrue(result.isSuccess(), "Auto-resolve should succeed: " + result.errors());
        assertEquals("auto-resolved-doc-id", result.objectId());
    }

    /**
     * Cloud UI sends provider {@code google} while some deployments register connectors as {@code google_drive}.
     */
    @Test
    void testExecuteWithAutoResolveGoogleAliasFindsGoogleDriveConnector() {
        ConnectorDefinition googleDriveConn = new ConnectorDefinition();
        googleDriveConn.setConnectorId("gd-conn");
        googleDriveConn.setEnabled(true);
        googleDriveConn.setSourceArchetype(SourceArchetype.FILE_SHARE);
        googleDriveConn.setSourceSystem("google_drive");
        when(connectorService.findBySystemAndArchetype("google", SourceArchetype.FILE_SHARE)).thenReturn(null);
        when(connectorService.findBySystemAndArchetype("google_drive", SourceArchetype.FILE_SHARE)).thenReturn(googleDriveConn);
        when(connectorService.get("gd-conn")).thenReturn(googleDriveConn);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-gd");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p-gd")).thenReturn(profile);
        when(profileService.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, "gd-conn"))
                .thenReturn(profile);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("doc-alias");

        Content mockContent = createMockContent("doc-alias");
        when(contentService.getContent("bedroom", "doc-alias")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("file-1");
        req.setSourceObjectType("file");
        req.setFileName("a.txt");
        req.setMetadata(Map.of("cloudProvider", "google", "cloudFileId", "file-1"));

        ExternalIngestResult result = service.executeWithAutoResolve(
                mock(CallContext.class), req, "google", SourceArchetype.FILE_SHARE);
        assertTrue(result.isSuccess(), () -> String.valueOf(result.errors()));
        verify(connectorService).findBySystemAndArchetype("google_drive", SourceArchetype.FILE_SHARE);
    }

    /**
     * Cloud UI sends provider {@code microsoft} while scheduler-oriented configs use {@code onedrive}.
     */
    @Test
    void testExecuteWithAutoResolveMicrosoftAliasFindsOnedriveConnector() {
        ConnectorDefinition odConn = new ConnectorDefinition();
        odConn.setConnectorId("od-conn");
        odConn.setEnabled(true);
        odConn.setSourceArchetype(SourceArchetype.FILE_SHARE);
        odConn.setSourceSystem("onedrive");
        when(connectorService.findBySystemAndArchetype("microsoft", SourceArchetype.FILE_SHARE)).thenReturn(null);
        when(connectorService.findBySystemAndArchetype("onedrive", SourceArchetype.FILE_SHARE)).thenReturn(odConn);
        when(connectorService.get("od-conn")).thenReturn(odConn);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-od");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        when(profileService.get("p-od")).thenReturn(profile);
        when(profileService.findDefaultForRepository("bedroom", SourceArchetype.FILE_SHARE, "od-conn"))
                .thenReturn(profile);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("doc-od");

        Content mockContent = createMockContent("doc-od");
        when(contentService.getContent("bedroom", "doc-od")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("item-1");
        req.setSourceObjectType("file");
        req.setFileName("b.txt");
        req.setMetadata(Map.of("cloudProvider", "microsoft", "cloudFileId", "item-1"));

        ExternalIngestResult result = service.executeWithAutoResolve(
                mock(CallContext.class), req, "microsoft", SourceArchetype.FILE_SHARE);
        assertTrue(result.isSuccess(), () -> String.valueOf(result.errors()));
        verify(connectorService).findBySystemAndArchetype("onedrive", SourceArchetype.FILE_SHARE);
    }
}
