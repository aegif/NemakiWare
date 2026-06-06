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
    private IngestMetadataService ingestMetadataService;

    @BeforeEach
    void setUp() {
        service = new CanonicalImportServiceImpl();
        connectorService = mock(ConnectorDefinitionService.class);
        profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(ObjectService.class);
        contentService = mock(ContentService.class);
        versioningService = mock(jp.aegif.nemaki.cmis.service.VersioningService.class);
        ingestMetadataService = mock(IngestMetadataService.class);
        // null return = metadata applied successfully
        lenient().when(ingestMetadataService.applyNoteMetadata(any(), any(), any(), any())).thenReturn(null);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
        service.setVersioningService(versioningService);
        service.setIngestMetadataService(ingestMetadataService);
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
        content.setType("cmis:document"); // Required for isDocument() to return true
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

    // ── ExternalIngestResult contract tests ──────────────────────

    @Test
    void testSkippedResultWithoutObjectId() {
        ExternalIngestResult result = ExternalIngestResult.skipped("req-1", "already exists");
        assertTrue(result.skipped());
        assertNull(result.objectId());
        assertEquals("already exists", result.skipReason());
        assertTrue(result.isSuccess()); // no errors
    }

    @Test
    void testSkippedResultWithExistingObjectId() {
        ExternalIngestResult result = ExternalIngestResult.skipped("req-2", "obj-abc-123", "idempotent");
        assertTrue(result.skipped());
        assertEquals("obj-abc-123", result.objectId());
        assertEquals("idempotent", result.skipReason());
        assertTrue(result.isSuccess());
    }

    @Test
    void testSkippedResultSerializesToJsonWithObjectId() throws Exception {
        ExternalIngestResult result = ExternalIngestResult.skipped("req-3", "existing-id", "duplicate");

        // Verify the record's accessor values are correct for JSON serialization
        assertEquals("existing-id", result.objectId());
        assertEquals("duplicate", result.skipReason());
        assertTrue(result.skipped());
        assertFalse(result.isNewVersion());
        assertFalse(result.dryRun());
    }

    // ── isTransientError — real implementation tests ───────────

    @Test
    void testIsTransientError_SocketTimeout() {
        assertTrue(service.isTransientError(new java.net.SocketTimeoutException("Read timed out")));
    }

    @Test
    void testIsTransientError_ConnectException() {
        assertTrue(service.isTransientError(new java.net.ConnectException("Connection refused")));
    }

    @Test
    void testIsTransientError_Http429InMessage() {
        assertTrue(service.isTransientError(new RuntimeException("HTTP 429 Too Many Requests")));
    }

    @Test
    void testIsTransientError_Http503InMessage() {
        assertTrue(service.isTransientError(new RuntimeException("503 Service Unavailable")));
    }

    @Test
    void testIsTransientError_WrappedSocketTimeout() {
        Exception wrapped = new RuntimeException("import failed",
                new java.net.SocketTimeoutException("connect timed out"));
        assertTrue(service.isTransientError(wrapped), "Wrapped SocketTimeout should be transient");
    }

    @Test
    void testIsTransientError_PermanentNullPointer() {
        assertFalse(service.isTransientError(new NullPointerException("null")));
    }

    @Test
    void testIsTransientError_PermanentIllegalArgument() {
        assertFalse(service.isTransientError(new IllegalArgumentException("bad input")));
    }

    @Test
    void testIsTransientError_PermanentClassCast() {
        assertFalse(service.isTransientError(new ClassCastException("cannot cast")));
    }

    // ── isTransientError: expanded classification tests ────────────

    @Test
    void testIsTransientError_SSLHandshakeIsPermanent() {
        assertFalse(service.isTransientError(
                new javax.net.ssl.SSLHandshakeException("certificate validation failed")),
                "SSL handshake errors should be permanent");
    }

    @Test
    void testIsTransientError_Http401IsPermanent() {
        assertFalse(service.isTransientError(
                new RuntimeException("Graph API auth error 401: Unauthorized")),
                "HTTP 401 should be permanent");
    }

    @Test
    void testIsTransientError_Http403IsPermanent() {
        assertFalse(service.isTransientError(
                new RuntimeException("Forbidden")),
                "HTTP 403 should be permanent");
    }

    @Test
    void testIsTransientError_SocketExceptionIsTransient() {
        assertTrue(service.isTransientError(
                new java.net.SocketException("Connection reset")),
                "SocketException should be transient");
    }

    @Test
    void testIsTransientError_RateLimitMessageIsTransient() {
        assertTrue(service.isTransientError(
                new RuntimeException("rate limit exceeded, please retry")),
                "Rate limit message should be transient");
    }

    // ── dedupeMatchBy tests ────────────────────────────────────────

    @Test
    void testDedupeMatchBySourceId_MatchesBySourceObjectId() {
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setContentDaoService(contentDaoService);

        // Existing document in target folder with matching sourceObjectId
        Content existingDoc = createMockContent("existing-doc-1");
        existingDoc.setName("old-name.txt");
        Aspect extAspect = new Aspect();
        extAspect.setName("nemaki:externalIntegration");
        extAspect.setProperties(List.of(
                new Property("nemaki:sourceObjectId", "slack-file-123"),
                new Property("nemaki:sourceSystem", "slack"),
                new Property("nemaki:sourceObjectType", "file")
        ));
        existingDoc.setAspects(List.of(extAspect));

        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(List.of(existingDoc));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy("create_new_version");
        profile.setDedupeMatchBy("source_id"); // default
        profile.setUpdatePolicy("always_version_up"); // force version-up for test
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("slack");
        when(connectorService.get("c1")).thenReturn(connector);

        // Request with same sourceObjectId → should find existing doc
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("slack-file-123");
        req.setSourceObjectType("file");
        req.setFileName("new-name.txt"); // different filename

        // Mock versioning for the update path (checkOut is void, mutates Holder)
        doAnswer(inv -> {
            org.apache.chemistry.opencmis.commons.spi.Holder<String> h = inv.getArgument(2);
            h.setValue("pwc-1");
            return null;
        }).when(versioningService).checkOut(any(), eq("bedroom"), any(), any(), isNull());
        // checkIn is also void, mutates Holder back to the checked-in objectId
        doAnswer(inv -> {
            org.apache.chemistry.opencmis.commons.spi.Holder<String> h = inv.getArgument(2);
            h.setValue("existing-doc-1");
            return null;
        }).when(versioningService).checkIn(any(), eq("bedroom"), any(), any(), any(), any(), anyString(), any(), isNull(), isNull(), isNull());
        Content mockContent = createMockContent("existing-doc-1");
        when(contentService.getContent("bedroom", "existing-doc-1")).thenReturn(mockContent);

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess(), () -> "Should succeed: " + result.errors()
                + " objectId=" + result.objectId() + " versionLabel=" + result.versionLabel()
                + " isNewVersion=" + result.isNewVersion() + " skipped=" + result.skipped()
                + " warnings=" + result.warnings());
        assertTrue(result.isNewVersion(), () -> "Should be a version-up of existing doc, got objectId="
                + result.objectId() + " versionLabel=" + result.versionLabel());
    }

    @Test
    void testDedupeMatchByFilename_MatchesByName() {
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setContentDaoService(contentDaoService);

        // Existing document with matching filename (different sourceObjectId)
        Content existingDoc = createMockContent("existing-doc-2");
        existingDoc.setName("report.pdf");
        existingDoc.setAspects(List.of()); // No external integration aspect

        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(List.of(existingDoc));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p2");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy("create_new_version");
        profile.setDedupeMatchBy("filename"); // match by name
        profile.setUpdatePolicy("always_version_up");
        when(profileService.get("p2")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c2");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("slack");
        when(connectorService.get("c2")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p2");
        req.setConnectorId("c2");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("brand-new-id-456"); // different ID
        req.setSourceObjectType("file");
        req.setFileName("report.pdf"); // same filename

        doAnswer(inv -> {
            org.apache.chemistry.opencmis.commons.spi.Holder<String> h = inv.getArgument(2);
            h.setValue("pwc-2");
            return null;
        }).when(versioningService).checkOut(any(), eq("bedroom"), any(), any(), isNull());
        doAnswer(inv -> {
            org.apache.chemistry.opencmis.commons.spi.Holder<String> h = inv.getArgument(2);
            h.setValue("existing-doc-2");
            return null;
        }).when(versioningService).checkIn(any(), eq("bedroom"), any(), any(), any(), any(), anyString(), any(), isNull(), isNull(), isNull());
        Content mockContent = createMockContent("existing-doc-2");
        when(contentService.getContent("bedroom", "existing-doc-2")).thenReturn(mockContent);

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess(), () -> "Should succeed: " + result.errors());
        assertTrue(result.isNewVersion(), "Filename match should trigger version-up");
    }

    @Test
    void testDedupeMatchByFilename_NoMatchCreatesNew() {
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setContentDaoService(contentDaoService);

        // Existing document with different filename
        Content existingDoc = createMockContent("existing-doc-3");
        existingDoc.setName("other.pdf");
        existingDoc.setAspects(List.of());

        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(List.of(existingDoc));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p3");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupeMatchBy("filename");
        when(profileService.get("p3")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c3");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("box");
        when(connectorService.get("c3")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("new-doc-id");

        Content mockContent = createMockContent("new-doc-id");
        when(contentService.getContent("bedroom", "new-doc-id")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p3");
        req.setConnectorId("c3");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("box-file-999");
        req.setFileName("report.pdf"); // no match

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());
        assertFalse(result.isNewVersion(), "No filename match → new document, not version-up");
    }

    @Test
    void testDedupeMatchBySourceIdOrFilename_FallbackToFilename() {
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setContentDaoService(contentDaoService);

        // Existing document with different sourceObjectId but matching filename
        Content existingDoc = createMockContent("existing-doc-4");
        existingDoc.setName("budget.xlsx");
        Aspect extAspect = new Aspect();
        extAspect.setName("nemaki:externalIntegration");
        extAspect.setProperties(List.of(
                new Property("nemaki:sourceObjectId", "old-id-000"),
                new Property("nemaki:sourceSystem", "slack"),
                new Property("nemaki:sourceObjectType", "file")
        ));
        existingDoc.setAspects(List.of(extAspect));

        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(List.of(existingDoc));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p4");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy("create_new_version");
        profile.setDedupeMatchBy("source_id_or_filename"); // fallback
        profile.setUpdatePolicy("always_version_up");
        when(profileService.get("p4")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c4");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("slack");
        when(connectorService.get("c4")).thenReturn(connector);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p4");
        req.setConnectorId("c4");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("new-upload-id-789"); // different from old-id-000
        req.setFileName("budget.xlsx"); // matches existing name

        doAnswer(inv -> {
            org.apache.chemistry.opencmis.commons.spi.Holder<String> h = inv.getArgument(2);
            h.setValue("pwc-4");
            return null;
        }).when(versioningService).checkOut(any(), eq("bedroom"), any(), any(), isNull());
        doAnswer(inv -> {
            org.apache.chemistry.opencmis.commons.spi.Holder<String> h = inv.getArgument(2);
            h.setValue("existing-doc-4");
            return null;
        }).when(versioningService).checkIn(any(), eq("bedroom"), any(), any(), any(), any(), anyString(), any(), isNull(), isNull(), isNull());
        Content mockContent = createMockContent("existing-doc-4");
        when(contentService.getContent("bedroom", "existing-doc-4")).thenReturn(mockContent);

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess(), () -> "Should succeed: " + result.errors());
        assertTrue(result.isNewVersion(), "source_id miss → filename match → should version-up");
    }

    @Test
    void testDefaultDedupeMatchByIsSourceId() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        assertEquals("source_id", profile.getDedupeMatchBy(),
                "Default dedupeMatchBy should be source_id for backward compatibility");
    }

    // ── Dedupe policy else-if chain regression test ────────────────

    @Test
    void testDedupeElseIfChain_ReplaceDoesNotFallThroughToParentContextChanged() {
        // This is a regression test for the if→else if fix.
        // "replace" should delete + create new; it should NOT also check
        // create_new_if_parent_context_changed afterward.
        jp.aegif.nemaki.dao.ContentDaoService contentDaoService =
                mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        service.setContentDaoService(contentDaoService);

        Content existingDoc = createMockContent("to-be-replaced");
        existingDoc.setName("file.txt");
        Aspect extAspect = new Aspect();
        extAspect.setName("nemaki:externalIntegration");
        extAspect.setProperties(List.of(
                new Property("nemaki:sourceObjectId", "src-1"),
                new Property("nemaki:sourceSystem", "test"),
                new Property("nemaki:sourceObjectType", "file")
        ));
        existingDoc.setAspects(List.of(extAspect));

        when(contentDaoService.getChildren("bedroom", "folder-1")).thenReturn(List.of(existingDoc));

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p-replace");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setDedupePolicy("replace"); // should delete + create new
        when(profileService.get("p-replace")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c-replace");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        connector.setSourceSystem("test");
        when(connectorService.get("c-replace")).thenReturn(connector);

        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                isNull(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("replaced-new-id");

        Content mockContent = createMockContent("replaced-new-id");
        when(contentService.getContent("bedroom", "replaced-new-id")).thenReturn(mockContent);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p-replace");
        req.setConnectorId("c-replace");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("src-1");
        req.setSourceObjectType("file");
        req.setFileName("file.txt");

        ExternalIngestResult result = service.execute(mock(CallContext.class), req);
        assertTrue(result.isSuccess());
        // Verify the old doc was deleted
        verify(objectService).deleteObject(any(), eq("bedroom"), eq("to-be-replaced"), eq(true), isNull());
        // Verify a new doc was created (not versioned)
        assertFalse(result.isNewVersion(), "Replace should create new, not version-up");
    }

    // ── readBounded: connector/scheduler stream size cap (audit follow-up) ──

    @Test
    void readBounded_readsContentUnderLimit() throws Exception {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 7);
        byte[] out = CanonicalImportServiceImpl.readBounded(
                new java.io.ByteArrayInputStream(data), 4096, "Content");
        assertArrayEquals(data, out, "content within the cap must round-trip unchanged");
    }

    @Test
    void readBounded_allowsExactlyTheLimit() throws Exception {
        byte[] data = new byte[4096];
        byte[] out = CanonicalImportServiceImpl.readBounded(
                new java.io.ByteArrayInputStream(data), 4096, "Content");
        assertEquals(4096, out.length, "content exactly at the cap must be allowed");
    }

    @Test
    void readBounded_rejectsContentOverLimit() {
        byte[] data = new byte[5000];
        java.io.IOException ex = assertThrows(java.io.IOException.class, () ->
                CanonicalImportServiceImpl.readBounded(
                        new java.io.ByteArrayInputStream(data), 4096, "Content"));
        assertTrue(ex.getMessage().contains("exceeds maximum size"),
                "must fail fast with a size error; was: " + ex.getMessage());
    }

    // ── importPolicy = files_only for Notion notes (spec change 3.1.3) ──

    private ImportProfileDefinition noteProfile(String importPolicy) {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setEnabled(true);
        profile.setTargetFolderId("folder-1");
        profile.setRepositoryId("bedroom");
        profile.setImportPolicy(importPolicy);
        when(profileService.get("p1")).thenReturn(profile);

        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setEnabled(true);
        connector.setSourceArchetype(SourceArchetype.COMPOUND_NOTE);
        connector.setSourceSystem("notion");
        when(connectorService.get("c1")).thenReturn(connector);
        return profile;
    }

    private ExternalIngestRequest notePageReq(String importPolicy, boolean withAttachment) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setRepositoryId("bedroom");
        req.setSourceObjectId("page-1");
        req.setSourceObjectType("page");
        req.setImportPolicy(importPolicy);
        if (importPolicy.equals("files_and_body")) {
            req.setFileName("My Page.html");
            req.setMimeType("text/html");
            req.setContentStream(new java.io.ByteArrayInputStream("<p>body</p>".getBytes()));
        }
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("pageId", "page-1");
        meta.put("pageUrl", "https://notion.so/page-1");
        if (withAttachment) {
            java.util.Map<String, Object> att = new java.util.LinkedHashMap<>();
            att.put("attachmentId", "blk-1");
            att.put("filename", "report.pdf");
            att.put("mimeType", "application/pdf");
            att.put("contentBase64", java.util.Base64.getEncoder().encodeToString("PDFDATA".getBytes()));
            meta.put("attachments", new java.util.ArrayList<>(List.of(att)));
        }
        req.setMetadata(meta);
        return req;
    }

    @Test
    void filesOnly_notion_importsAttachmentOnly_noPageDocument_noRelationship() {
        noteProfile("files_only");
        // Only one createDocument call expected (the attachment). Return its id.
        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("att-obj-id");

        ExternalIngestResult result = service.executeNoteImport(
                mock(CallContext.class), notePageReq("files_only", true));

        assertTrue(result.isSuccess(), "errors: " + result.errors());
        assertEquals("att-obj-id", result.objectId(), "primary object should be the attachment");
        // Exactly ONE document created (the attachment) — no page-body doc.
        verify(objectService, times(1)).createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull());
        // No page->attachment relationship in files_only mode (no page doc).
        verify(objectService, never()).createRelationship(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void filesOnly_notion_pageWithNoAttachments_isSkipped_noDocument() {
        noteProfile("files_only");

        ExternalIngestResult result = service.executeNoteImport(
                mock(CallContext.class), notePageReq("files_only", false));

        assertTrue(result.skipped(), "page with no attachments must be skipped");
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void filesAndBody_notion_importsPageAndAttachment_withRelationship() {
        noteProfile("files_and_body");
        // Page body doc then attachment doc.
        when(objectService.createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("page-obj-id", "att-obj-id");

        ExternalIngestResult result = service.executeNoteImport(
                mock(CallContext.class), notePageReq("files_and_body", true));

        assertTrue(result.isSuccess(), "errors: " + result.errors());
        assertEquals("page-obj-id", result.objectId(), "primary object should be the page in files_and_body");
        // Two documents: page body + attachment.
        verify(objectService, times(2)).createDocument(any(), eq("bedroom"), any(), eq("folder-1"),
                any(), any(), isNull(), isNull(), isNull(), isNull());
    }
}
