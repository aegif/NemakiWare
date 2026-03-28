package jp.aegif.nemaki.rest.purview.journal;

import static org.mockito.Mockito.*;

import java.lang.reflect.Method;

import jp.aegif.nemaki.rest.ImportExportResource;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.rest.purview.lineage.PurviewImportExportLineageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

/**
 * Tests that Purview direct lineage calls are bypassed when journal is active.
 *
 * <p>When lineage.mode is DIRECT or JOURNALED, the journal owns lineage emission.
 * The five publish*Lineage() methods in ImportExportResource must skip direct
 * Purview calls to avoid duplicate emission.
 */
public class LineagePurviewBypassTest {

    private ImportExportResource resource;
    private PurviewImportExportLineageService purviewService;
    private LineageConfig lineageConfig;

    @BeforeEach
    void setUp() {
        resource = new ImportExportResource();
        purviewService = mock(PurviewImportExportLineageService.class);
        lineageConfig = mock(LineageConfig.class);
        resource.setPurviewImportExportLineageService(purviewService);
    }

    // --- publishFilesystemImportLineage ---

    @Test
    void publishFilesystemImportLineageSkipsPurviewWhenJournaled() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.JOURNALED);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            ImportResult result = new ImportResult();
            result.documentsCreated = 5;
            result.foldersCreated = 2;

            invokePublishFilesystemImportLineage("bedroom", "folder-1", "/tmp/data", "admin", result);

            verifyNoInteractions(purviewService);
        }
    }

    @Test
    void publishFilesystemImportLineageSkipsPurviewWhenDirect() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.DIRECT);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            ImportResult result = new ImportResult();
            result.documentsCreated = 5;
            result.foldersCreated = 2;

            invokePublishFilesystemImportLineage("bedroom", "folder-1", "/tmp/data", "admin", result);

            verifyNoInteractions(purviewService);
        }
    }

    @Test
    void publishFilesystemImportLineageCallsPurviewWhenDisabled() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.DISABLED);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            ImportResult result = new ImportResult();
            result.documentsCreated = 5;
            result.foldersCreated = 2;

            invokePublishFilesystemImportLineage("bedroom", "folder-1", "/tmp/data", "admin", result);

            verify(purviewService).upsertFilesystemImportLineage("bedroom", "folder-1", "/tmp/data", "admin", 7L);
        }
    }

    // --- publishZipFolderExportLineage ---

    @Test
    void publishZipFolderExportLineageSkipsPurviewWhenJournaled() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.JOURNALED);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            invokePublishZipFolderExportLineage("bedroom", "folder-1", "myFolder", "admin", 10);

            verifyNoInteractions(purviewService);
        }
    }

    @Test
    void publishZipFolderExportLineageCallsPurviewWhenDisabled() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.DISABLED);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            invokePublishZipFolderExportLineage("bedroom", "folder-1", "myFolder", "admin", 10);

            verify(purviewService).upsertZipFolderExportLineage("bedroom", "folder-1", "myFolder", "admin", 10L);
        }
    }

    // --- publishUploadedImportLineage ---

    @Test
    void publishUploadedImportLineageSkipsPurviewWhenDirect() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.DIRECT);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            invokePublishUploadedImportLineage("bedroom", "folder-1", "overwrite", "admin", 8);

            verifyNoInteractions(purviewService);
        }
    }

    @Test
    void publishUploadedImportLineageCallsPurviewWhenDisabled() throws Exception {
        when(lineageConfig.getMode()).thenReturn(LineageMode.DISABLED);

        try (MockedStatic<SpringContext> ctx = mockStatic(SpringContext.class)) {
            ApplicationContext appCtx = mock(ApplicationContext.class);
            ctx.when(SpringContext::getApplicationContext).thenReturn(appCtx);
            when(appCtx.getBean(LineageConfig.class)).thenReturn(lineageConfig);

            invokePublishUploadedImportLineage("bedroom", "folder-1", "overwrite", "admin", 8);

            verify(purviewService).upsertUploadedImportLineage("bedroom", "folder-1", "overwrite", "admin", 8L);
        }
    }

    // --- Helper: invoke private/protected methods via reflection ---

    private void invokePublishFilesystemImportLineage(
            String repositoryId, String folderId, String sourcePath,
            String requestedBy, ImportResult importResult) throws Exception {
        Method m = ImportExportResource.class.getDeclaredMethod(
                "publishFilesystemImportLineage",
                String.class, String.class, String.class, String.class,
                ImportResult.class);
        m.setAccessible(true);
        m.invoke(resource, repositoryId, folderId, sourcePath, requestedBy, importResult);
    }

    private void invokePublishZipFolderExportLineage(
            String repositoryId, String folderId, String folderName,
            String requestedBy, long objectCount) throws Exception {
        Method m = ImportExportResource.class.getDeclaredMethod(
                "publishZipFolderExportLineage",
                String.class, String.class, String.class, String.class, long.class);
        m.setAccessible(true);
        m.invoke(resource, repositoryId, folderId, folderName, requestedBy, objectCount);
    }

    private void invokePublishUploadedImportLineage(
            String repositoryId, String folderId, String importMode,
            String requestedBy, long objectCount) throws Exception {
        Method m = ImportExportResource.class.getDeclaredMethod(
                "publishUploadedImportLineage",
                String.class, String.class, String.class, String.class, long.class);
        m.setAccessible(true);
        m.invoke(resource, repositoryId, folderId, importMode, requestedBy, objectCount);
    }
}
