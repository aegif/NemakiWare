package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CanonicalImportServiceTest {

    private CanonicalImportServiceImpl service;
    private ConnectorDefinitionService connectorService;
    private ImportProfileDefinitionService profileService;
    private ObjectService objectService;
    private ContentService contentService;

    @BeforeEach
    void setUp() {
        service = new CanonicalImportServiceImpl();
        connectorService = mock(ConnectorDefinitionService.class);
        profileService = mock(ImportProfileDefinitionService.class);
        objectService = mock(ObjectService.class);
        contentService = mock(ContentService.class);
        service.setConnectorDefinitionService(connectorService);
        service.setImportProfileDefinitionService(profileService);
        service.setObjectService(objectService);
        service.setContentService(contentService);
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
        verify(objectService, never()).createDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
    void testAttachmentSourceObjectTypeUsesAttachmentProcessType() {
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
}
