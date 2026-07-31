package jp.aegif.nemaki.rest.purview.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.junit.jupiter.api.Test;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.Property;

public class PurviewEntityPayloadFactoryTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildRepositoryEntityMapsQualifiedNameAndRootFolder() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setName("Bedroom Repository");
        repositoryInfo.setDescription("Primary test repository");
        repositoryInfo.setRootFolder("root-001");

        Map<String, Object> entity = factory.buildRepositoryEntity(repositoryInfo);

        assertEquals("nemaki_repository", entity.get("typeName"));
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("nemaki://bedroom", attributes.get("qualifiedName"));
        assertEquals("Bedroom Repository", attributes.get("name"));
        assertEquals("bedroom", attributes.get("repositoryId"));
        assertEquals("root-001", attributes.get("rootFolderId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildFolderEntityMapsQualifiedNameAndParentAttributes() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Folder folder = new Folder();
        folder.setId("folder-001");
        folder.setName("Contracts");
        folder.setDescription("Customer contracts");
        folder.setParentId("root-001");
        folder.setObjectType("cmis:folder");
        folder.setCreator("alice");
        folder.setModifier("bob");
        folder.setCreated(calendar("2026-03-20T01:00:00Z"));
        folder.setModified(calendar("2026-03-20T02:00:00Z"));

        Map<String, Object> entity = factory.buildFolderEntity("bedroom", folder);

        assertEquals("nemaki_folder", entity.get("typeName"));
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("nemaki://bedroom/objects/folder-001", attributes.get("qualifiedName"));
        assertEquals("Contracts", attributes.get("name"));
        assertEquals("bedroom", attributes.get("repositoryId"));
        assertEquals("folder-001", attributes.get("objectId"));
        assertEquals("root-001", attributes.get("parentId"));
        assertEquals("cmis:folder", attributes.get("typeId"));
        assertFalse(attributes.containsKey("folderPath"), "folderPath must be omitted when not provided");
        assertEquals("ACTIVE", attributes.get("lifecycleState"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildFolderEntityWithFolderPathSetsFolderPathAttribute() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Folder folder = new Folder();
        folder.setId("folder-001");
        folder.setName("Contracts");
        folder.setParentId("root-001");
        folder.setObjectType("cmis:folder");
        folder.setCreator("alice");

        Map<String, Object> entity = factory.buildFolderEntity("bedroom", folder, "/Reports/Contracts");

        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("/Reports/Contracts", attributes.get("folderPath"));
        assertEquals("Contracts", attributes.get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentEntityWithFolderPathSetsFolderPathAttribute() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setName("Report.pdf");
        document.setParentId("folder-001");
        document.setObjectType("cmis:document");
        document.setCreator("alice");

        Map<String, Object> entity = factory.buildDocumentEntity("bedroom", document, "/Reports");

        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("/Reports", attributes.get("folderPath"));
        assertEquals("Report.pdf", attributes.get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentEntityMapsQualifiedNameAndVersionAttributes() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setName("Quarterly Report");
        document.setDescription("FY26 Q1");
        document.setParentId("folder-001");
        document.setObjectType("D:custom:report");
        document.setCreator("alice");
        document.setModifier("bob");
        document.setVersionSeriesId("vs-001");
        document.setVersionLabel("1.2");
        document.setLatestVersion(Boolean.TRUE);
        document.setCreated(calendar("2026-03-20T01:00:00Z"));
        document.setModified(calendar("2026-03-20T02:00:00Z"));
        document.setAspects(List.of(new Aspect("nemaki:cloudDriveMetadata", List.of(
                new Property("nemaki:cloudProvider", "google"),
                new Property("nemaki:cloudFileId", "cloud-001"),
                new Property("nemaki:cloudFileUrl", "https://drive.example/doc-001"),
                new Property("nemaki:cloudLastSyncedAt", "2026-03-20T03:00:00.000+0000")))));

        Map<String, Object> entity = factory.buildDocumentEntity("bedroom", document);

        assertEquals("nemaki_document", entity.get("typeName"));
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("nemaki://bedroom/objects/doc-001", attributes.get("qualifiedName"));
        assertEquals("Quarterly Report", attributes.get("name"));
        assertEquals("bedroom", attributes.get("repositoryId"));
        assertEquals("doc-001", attributes.get("objectId"));
        assertEquals("folder-001", attributes.get("parentId"));
        assertEquals("D:custom:report", attributes.get("typeId"));
        assertEquals("vs-001", attributes.get("versionSeriesId"));
        assertEquals("1.2", attributes.get("versionLabel"));
        assertEquals(Boolean.TRUE, attributes.get("isLatestVersion"));
        assertEquals("ACTIVE", attributes.get("lifecycleState"));
        assertEquals("google", attributes.get("cloudProvider"));
        assertEquals("cloud-001", attributes.get("externalFileId"));
        // no stored URL reaches the catalog: SharePoint-style sharing tokens live in the path,
        // so no transformation of a stored URL is secret-free (A-1g); null also clears any raw
        // URL published before this rule, on the next republish
        assertNull(attributes.get("cloudFileUrl"));
        assertEquals("2026-03-20T03:00:00.000+0000", attributes.get("cloudLastSyncedAt"));
        assertTrue(((Number) attributes.get("createTime")).longValue() > 0);
        assertTrue(((Number) attributes.get("modifiedTime")).longValue() > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildTypeDefinitionEntityMapsQualifiedNameAndTypeAttributes() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        NemakiTypeDefinition typeDefinition = new NemakiTypeDefinition();
        typeDefinition.setId("typedef-001");
        typeDefinition.setTypeId("D:custom:report");
        typeDefinition.setDisplayName("Report");
        typeDefinition.setDescription("Custom report type");
        typeDefinition.setQueryName("custom_report");
        typeDefinition.setBaseId(BaseTypeId.CMIS_DOCUMENT);
        typeDefinition.setParentId("cmis:document");
        typeDefinition.setProperties(List.of("prop-1", "prop-2"));
        typeDefinition.setVersionable(Boolean.TRUE);
        typeDefinition.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);
        typeDefinition.setCreator("alice");
        typeDefinition.setModifier("bob");
        typeDefinition.setCreated(calendar("2026-03-20T01:00:00Z"));
        typeDefinition.setModified(calendar("2026-03-20T02:00:00Z"));

        Map<String, Object> entity = factory.buildTypeDefinitionEntity("bedroom", typeDefinition);

        assertEquals("nemaki_type_definition", entity.get("typeName"));
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        assertEquals("nemaki://bedroom/types/D:custom:report", attributes.get("qualifiedName"));
        assertEquals("bedroom", attributes.get("repositoryId"));
        assertEquals("D:custom:report", attributes.get("typeId"));
        assertEquals("custom_report", attributes.get("queryName"));
        assertEquals("cmis:document", attributes.get("baseTypeId"));
        assertEquals("cmis:document", attributes.get("parentTypeId"));
        assertEquals(2L, ((Number) attributes.get("propertyCount")).longValue());
        assertEquals(Boolean.TRUE, attributes.get("versionable"));
        assertEquals("allowed", attributes.get("contentStreamAllowed"));
        assertEquals("ACTIVE", attributes.get("lifecycleState"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentTypeRelationshipMapsQualifiedNames() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setObjectType("D:custom:report");

        Map<String, Object> relationship = factory.buildDocumentTypeRelationship("bedroom", document);

        assertEquals("nemaki_document_has_type_definition", relationship.get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertEquals("nemaki_document", end1.get("typeName"));
        assertEquals("nemaki_type_definition", end2.get("typeName"));
        assertEquals("nemaki://bedroom/objects/doc-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/types/D:custom:report",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentArchiveRelationshipMapsQualifiedNames() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");

        Map<String, Object> relationship = factory.buildDocumentArchiveRelationship("bedroom", archive);

        assertEquals("nemaki_document_has_archive", relationship.get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertEquals("DataSet", end1.get("typeName"));
        assertEquals("nemaki_archive", end2.get("typeName"));
        assertEquals("nemaki://bedroom/objects/doc-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/archives/archive-001",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildRepositoryFolderRelationshipMapsQualifiedNames() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Folder folder = new Folder();
        folder.setId("root-001");

        Map<String, Object> relationship = factory.buildRepositoryFolderRelationship("bedroom", folder);

        assertEquals("nemaki_repository_contains_folder", relationship.get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertEquals("nemaki_repository", end1.get("typeName"));
        assertEquals("nemaki_folder", end2.get("typeName"));
        assertEquals("nemaki://bedroom",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/objects/root-001",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildFolderFolderRelationshipMapsQualifiedNames() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Folder folder = new Folder();
        folder.setId("child-folder-001");
        folder.setParentId("parent-folder-001");

        Map<String, Object> relationship = factory.buildFolderFolderRelationship("bedroom", folder);

        assertEquals("nemaki_folder_contains_folder", relationship.get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertEquals("nemaki_folder", end1.get("typeName"));
        assertEquals("nemaki_folder", end2.get("typeName"));
        assertEquals("nemaki://bedroom/objects/parent-folder-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/objects/child-folder-001",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildFolderDocumentRelationshipMapsQualifiedNames() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setParentId("folder-001");

        Map<String, Object> relationship = factory.buildFolderDocumentRelationship("bedroom", document);

        assertEquals("nemaki_folder_contains_document", relationship.get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertEquals("nemaki_folder", end1.get("typeName"));
        assertEquals("nemaki_document", end2.get("typeName"));
        assertEquals("nemaki://bedroom/objects/folder-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/objects/doc-001",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildArchivedDocumentAndArchiveEntityMapsArchiveLifecycle() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");
        archive.setName("Quarterly Report");
        archive.setParentId("folder-001");
        archive.setType("cmis:document");
        archive.setCreator("alice");
        archive.setArchivedBy("archiver");
        archive.setVersionSeriesId("vs-001");
        archive.setVersionLabel("1.2");
        archive.setIsLatestVersion(Boolean.TRUE);
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
        archive.setCreated(calendar("2026-03-20T01:00:00Z"));
        archive.setArchivedAt(calendar("2026-03-20T03:00:00Z"));

        Map<String, Object> archivedDocument = factory.buildArchivedDocumentEntity("bedroom", archive);
        Map<String, Object> archiveEntity = factory.buildArchiveEntity("bedroom", archive);

        Map<String, Object> documentAttributes = (Map<String, Object>) archivedDocument.get("attributes");
        assertEquals("nemaki://bedroom/objects/doc-001", documentAttributes.get("qualifiedName"));
        assertEquals("ARCHIVED", documentAttributes.get("lifecycleState"));
        assertEquals("archive-001", documentAttributes.get("archiveId"));
        assertEquals(Archive.STATE_ARCHIVED_LOCAL, documentAttributes.get("archiveState"));
        assertEquals(Boolean.TRUE, documentAttributes.get("isLatestVersion"));

        Map<String, Object> archiveAttributes = (Map<String, Object>) archiveEntity.get("attributes");
        assertEquals("nemaki://bedroom/archives/archive-001", archiveAttributes.get("qualifiedName"));
        assertEquals("doc-001", archiveAttributes.get("originalObjectId"));
        assertEquals("ARCHIVED", archiveAttributes.get("lifecycleState"));
        assertEquals(Archive.STATE_ARCHIVED_LOCAL, archiveAttributes.get("archiveState"));
        assertEquals("vs-001", archiveAttributes.get("versionSeriesId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildBulkPayloadWrapsEntitiesForBulkUpsert() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        Map<String, Object> payload = factory.buildBulkPayload(List.of(Map.of("typeName", "nemaki_document")));

        assertTrue(((Map<String, Object>) payload.get("referredEntities")).isEmpty());
        assertEquals(1, ((List<Map<String, Object>>) payload.get("entities")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildArchiveLineageEntitiesMapExternalTargetAndProcess() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");
        archive.setName("Quarterly Report");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setArchivedBy("archiver");
        archive.setArchivedAt(calendar("2026-03-20T03:00:00Z"));
        archive.setColdArchivedAt(calendar("2026-03-20T04:00:00Z"));
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://archive-bucket/bedroom/doc-001.bin"));

        Map<String, Object> externalAsset = factory.buildExternalAssetEntity("bedroom", archive);
        Map<String, Object> archiveProcess = factory.buildArchiveProcessEntity("bedroom", archive);

        Map<String, Object> externalAttributes = (Map<String, Object>) externalAsset.get("attributes");
        assertEquals("nemaki_external_asset", externalAsset.get("typeName"));
        assertEquals("s3://archive-bucket/bedroom/doc-001.bin", externalAttributes.get("externalStableKey"));
        assertEquals("s3", externalAttributes.get("sourceSystem"));
        assertEquals("s3://archive-bucket/bedroom/doc-001.bin", externalAttributes.get("externalPath"));

        Map<String, Object> processAttributes = (Map<String, Object>) archiveProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) archiveProcess.get("relationshipAttributes");
        assertEquals("nemaki_archive_process", archiveProcess.get("typeName"));
        assertEquals("archive-001", processAttributes.get("archiveId"));
        assertEquals("s3://archive-bucket/bedroom/doc-001.bin", processAttributes.get("externalStableKey"));
        assertEquals("s3://archive-bucket/bedroom/doc-001.bin", processAttributes.get("targetDescription"));
        assertEquals(1, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(2, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildCloudSyncLineageEntitiesMapExternalSourceAndProcess() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setName("Quarterly Report");
        document.setParentId("folder-001");
        document.setAspects(List.of(new Aspect("nemaki:cloudDriveMetadata", List.of(
                new Property("nemaki:cloudProvider", "google"),
                new Property("nemaki:cloudFileId", "cloud-001"),
                new Property("nemaki:cloudFileUrl", "https://drive.example/doc-001"),
                new Property("nemaki:cloudLastSyncedAt", "2026-03-20T03:00:00.000+0000")))));

        Map<String, Object> externalAsset = factory.buildExternalAssetEntity("bedroom", document);
        Map<String, Object> syncProcess = factory.buildCloudSyncProcessEntity("bedroom", document);

        Map<String, Object> externalAttributes = (Map<String, Object>) externalAsset.get("attributes");
        assertEquals("nemaki_external_asset", externalAsset.get("typeName"));
        assertEquals("google:cloud-001", externalAttributes.get("externalStableKey"));
        assertEquals("google", externalAttributes.get("sourceSystem"));
        // the file id, not the stored URL: a drive URL's query string can carry a sharing
        // token, and externalPath is persisted in the catalog (A-1f)
        assertEquals("cloud-001", externalAttributes.get("externalPath"));

        Map<String, Object> processAttributes = (Map<String, Object>) syncProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) syncProcess.get("relationshipAttributes");
        assertEquals("nemaki_cloud_sync_process", syncProcess.get("typeName"));
        assertEquals("doc-001", processAttributes.get("objectId"));
        assertEquals("google", processAttributes.get("cloudProvider"));
        assertEquals("google:cloud-001", processAttributes.get("externalStableKey"));
        assertEquals(1, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(1, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildFilesystemImportLineageEntitiesMapExternalSourceAndProcess() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        Map<String, Object> externalAsset = factory.buildFilesystemExternalAssetEntity(
                "bedroom",
                "/managed/imports/team-a",
                "alice",
                1742439600000L);
        Map<String, Object> importProcess = factory.buildFilesystemImportProcessEntity(
                "bedroom",
                "folder-001",
                "/managed/imports/team-a",
                "alice",
                1742439600000L,
                5);

        Map<String, Object> externalAttributes = (Map<String, Object>) externalAsset.get("attributes");
        assertEquals("nemaki_external_asset", externalAsset.get("typeName"));
        assertEquals("filesystem:/managed/imports/team-a", externalAttributes.get("externalStableKey"));
        assertEquals("filesystem", externalAttributes.get("sourceSystem"));
        assertEquals("/managed/imports/team-a", externalAttributes.get("externalPath"));

        Map<String, Object> processAttributes = (Map<String, Object>) importProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) importProcess.get("relationshipAttributes");
        assertEquals("nemaki_import_process", importProcess.get("typeName"));
        assertEquals("folder-001", processAttributes.get("folderId"));
        assertEquals("filesystem", processAttributes.get("importMode"));
        assertEquals("filesystem:/managed/imports/team-a", processAttributes.get("externalStableKey"));
        assertEquals("/managed/imports/team-a", processAttributes.get("sourceDescription"));
        assertEquals(5L, processAttributes.get("objectCount"));
        assertEquals(1, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(1, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildUploadedImportProcessEntityUsesFolderOutputOnly() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        Map<String, Object> importProcess = factory.buildUploadedImportProcessEntity(
                "bedroom",
                "folder-001",
                "zip-upload",
                "alice",
                1742439600000L,
                5);

        Map<String, Object> processAttributes = (Map<String, Object>) importProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) importProcess.get("relationshipAttributes");
        assertEquals("nemaki_import_process", importProcess.get("typeName"));
        assertEquals("folder-001", processAttributes.get("folderId"));
        assertEquals("zip-upload", processAttributes.get("importMode"));
        assertEquals("upload:zip-upload", processAttributes.get("sourceDescription"));
        assertEquals(5L, processAttributes.get("objectCount"));
        assertTrue(!processAttributes.containsKey("externalStableKey") || processAttributes.get("externalStableKey") == null);
        assertEquals(0, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(1, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildFilesystemExportLineageEntitiesMapExternalTargetAndProcess() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        Map<String, Object> externalAsset = factory.buildFilesystemExternalAssetEntity(
                "bedroom",
                "/managed/exports/team-a",
                "alice",
                1742439600000L);
        Map<String, Object> exportProcess = factory.buildFilesystemExportProcessEntity(
                "bedroom",
                "folder-001",
                "/managed/exports/team-a",
                "alice",
                1742439600000L,
                7);

        Map<String, Object> processAttributes = (Map<String, Object>) exportProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) exportProcess.get("relationshipAttributes");
        assertEquals("nemaki_export_process", exportProcess.get("typeName"));
        assertEquals("folder-001", processAttributes.get("folderId"));
        assertEquals("filesystem", processAttributes.get("exportMode"));
        assertEquals("filesystem:/managed/exports/team-a", processAttributes.get("externalStableKey"));
        assertEquals("/managed/exports/team-a", processAttributes.get("targetDescription"));
        assertEquals(7L, processAttributes.get("objectCount"));
        assertEquals(1, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(1, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildZipFolderExportProcessEntityOmitsExternalAssetAndKeepsFolderInput() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        Map<String, Object> exportProcess = factory.buildZipFolderExportProcessEntity(
                "bedroom",
                "folder-001",
                "Contracts",
                "alice",
                1742439600000L,
                4);

        Map<String, Object> processAttributes = (Map<String, Object>) exportProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) exportProcess.get("relationshipAttributes");
        assertEquals("nemaki_export_process", exportProcess.get("typeName"));
        assertEquals("folder-001", processAttributes.get("folderId"));
        assertEquals("zip-folder", processAttributes.get("exportMode"));
        assertEquals("zip-download:folder:folder-001", processAttributes.get("targetDescription"));
        assertEquals(4L, processAttributes.get("objectCount"));
        assertTrue(!processAttributes.containsKey("externalStableKey") || processAttributes.get("externalStableKey") == null);
        assertEquals(1, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(0, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildSelectedObjectsExportProcessEntityUsesSelectedRootsAsInputs() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setName("Quarterly Report");
        document.setObjectType("cmis:document");
        Folder folder = new Folder();
        folder.setId("folder-001");
        folder.setName("Contracts");
        folder.setObjectType("cmis:folder");

        Map<String, Object> exportProcess = factory.buildSelectedObjectsExportProcessEntity(
                "bedroom",
                List.of(document, folder),
                "alice",
                1742439600000L,
                7);

        Map<String, Object> processAttributes = (Map<String, Object>) exportProcess.get("attributes");
        Map<String, Object> relationshipAttributes = (Map<String, Object>) exportProcess.get("relationshipAttributes");
        assertEquals("nemaki_export_process", exportProcess.get("typeName"));
        assertEquals("zip-selection", processAttributes.get("exportMode"));
        assertEquals("zip-download:selected:doc-001,folder-001", processAttributes.get("targetDescription"));
        assertEquals(7L, processAttributes.get("objectCount"));
        assertTrue(!processAttributes.containsKey("folderId") || processAttributes.get("folderId") == null);
        assertTrue(!processAttributes.containsKey("externalStableKey") || processAttributes.get("externalStableKey") == null);
        assertEquals(2, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(0, ((List<?>) relationshipAttributes.get("outputs")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRelationshipEndIncludesGuidWhenProvided() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setParentId("folder-001");

        Map<String, String> guidMap = Map.of(
                "nemaki://bedroom/objects/folder-001", "guid-folder",
                "nemaki://bedroom/objects/doc-001", "guid-doc");

        Map<String, Object> relationship = factory.buildFolderDocumentRelationship("bedroom", document, guidMap);

        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertEquals("guid-folder", end1.get("guid"));
        assertEquals("guid-doc", end2.get("guid"));
        assertEquals("nemaki://bedroom/objects/folder-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/objects/doc-001",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRelationshipEndOmitsGuidWhenNotProvided() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Document document = new Document();
        document.setId("doc-001");
        document.setParentId("folder-001");

        // Empty guid map — matches existing behavior
        Map<String, Object> relationship = factory.buildFolderDocumentRelationship("bedroom", document, Map.of());

        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        assertTrue(!end1.containsKey("guid"));
        assertTrue(!end2.containsKey("guid"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGuidOverloadForAllRelationshipBuilders() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        // buildDocumentTypeRelationship
        Document doc = new Document();
        doc.setId("doc-001");
        doc.setObjectType("cmis:document");
        Map<String, String> docTypeGuids = Map.of(
                "nemaki://bedroom/objects/doc-001", "guid-doc",
                "nemaki://bedroom/types/cmis:document", "guid-type");
        Map<String, Object> docTypeRel = factory.buildDocumentTypeRelationship("bedroom", doc, docTypeGuids);
        assertEquals("guid-doc", ((Map<String, Object>) docTypeRel.get("end1")).get("guid"));
        assertEquals("guid-type", ((Map<String, Object>) docTypeRel.get("end2")).get("guid"));

        // buildDocumentArchiveRelationship
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");
        Map<String, String> archiveGuids = Map.of(
                "nemaki://bedroom/objects/doc-001", "guid-doc",
                "nemaki://bedroom/archives/archive-001", "guid-archive");
        Map<String, Object> archiveRel = factory.buildDocumentArchiveRelationship("bedroom", archive, archiveGuids);
        assertEquals("guid-doc", ((Map<String, Object>) archiveRel.get("end1")).get("guid"));
        assertEquals("guid-archive", ((Map<String, Object>) archiveRel.get("end2")).get("guid"));

        // buildRepositoryFolderRelationship
        Folder folder = new Folder();
        folder.setId("root-001");
        Map<String, String> repoFolderGuids = Map.of(
                "nemaki://bedroom", "guid-repo",
                "nemaki://bedroom/objects/root-001", "guid-folder");
        Map<String, Object> repoFolderRel = factory.buildRepositoryFolderRelationship("bedroom", folder, repoFolderGuids);
        assertEquals("guid-repo", ((Map<String, Object>) repoFolderRel.get("end1")).get("guid"));
        assertEquals("guid-folder", ((Map<String, Object>) repoFolderRel.get("end2")).get("guid"));

        // buildFolderFolderRelationship
        Folder childFolder = new Folder();
        childFolder.setId("child-001");
        childFolder.setParentId("parent-001");
        Map<String, String> folderFolderGuids = Map.of(
                "nemaki://bedroom/objects/parent-001", "guid-parent",
                "nemaki://bedroom/objects/child-001", "guid-child");
        Map<String, Object> folderFolderRel = factory.buildFolderFolderRelationship("bedroom", childFolder, folderFolderGuids);
        assertEquals("guid-parent", ((Map<String, Object>) folderFolderRel.get("end1")).get("guid"));
        assertEquals("guid-child", ((Map<String, Object>) folderFolderRel.get("end2")).get("guid"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentEntityIncludesCustomPropertyValues() {
        IntegrationSettingsService service = mock(IntegrationSettingsService.class);
        String json = """
                {
                  "nemaki:document": {
                    "nemaki:department": { "enabled": true, "catalogName": "department" },
                    "nemaki:priority": { "enabled": true, "catalogName": "priority" }
                  }
                }
                """;
        when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

        TypeService typeService = mock(TypeService.class);
        NemakiPropertyDefinitionCore stringCore = mockCore(PropertyType.STRING);
        NemakiPropertyDefinitionCore intCore = mockCore(PropertyType.INTEGER);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:department")).thenReturn(stringCore);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:priority")).thenReturn(intCore);

        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        when(repoMap.keys()).thenReturn(java.util.Set.of("bedroom"));
        CatalogPropertyMappingResolver resolver = new CatalogPropertyMappingResolver(service, typeService, repoMap);

        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        factory.setPropertyMappingResolver(resolver);

        Document doc = new Document();
        doc.setId("doc-001");
        doc.setName("Test Document");
        doc.setObjectType("nemaki:document");
        doc.setCreator("user1");
        List<Property> customProps = new ArrayList<>();
        customProps.add(new Property("nemaki:department", "Engineering"));
        customProps.add(new Property("nemaki:priority", 5));
        doc.setSubTypeProperties(customProps);

        Map<String, Object> entity = factory.buildDocumentEntity("bedroom", doc);
        Map<String, Object> attrs = (Map<String, Object>) entity.get("attributes");

        assertEquals("Engineering", attrs.get("department"));
        assertEquals(5, attrs.get("priority"));
        assertEquals("doc-001", attrs.get("objectId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentEntityOmitsDisabledMappings() {
        IntegrationSettingsService service = mock(IntegrationSettingsService.class);
        String json = """
                {
                  "nemaki:document": {
                    "nemaki:department": { "enabled": true, "catalogName": "department" },
                    "nemaki:secret": { "enabled": false, "catalogName": "secret" }
                  }
                }
                """;
        when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

        TypeService typeService = mock(TypeService.class);
        NemakiPropertyDefinitionCore strCore = mockCore(PropertyType.STRING);
        when(typeService.getPropertyDefinitionCoreByPropertyId(eq("bedroom"), anyString()))
                .thenReturn(strCore);

        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        when(repoMap.keys()).thenReturn(java.util.Set.of("bedroom"));
        CatalogPropertyMappingResolver resolver = new CatalogPropertyMappingResolver(service, typeService, repoMap);

        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        factory.setPropertyMappingResolver(resolver);

        Document doc = new Document();
        doc.setId("doc-002");
        doc.setName("Secret Doc");
        doc.setObjectType("nemaki:document");
        List<Property> customProps = new ArrayList<>();
        customProps.add(new Property("nemaki:department", "Sales"));
        customProps.add(new Property("nemaki:secret", "classified"));
        doc.setSubTypeProperties(customProps);

        Map<String, Object> entity = factory.buildDocumentEntity("bedroom", doc);
        Map<String, Object> attrs = (Map<String, Object>) entity.get("attributes");

        assertEquals("Sales", attrs.get("department"));
        assertNull(attrs.get("secret"), "Disabled mapping should not be included");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRepositoryIsolationInPayload() {
        IntegrationSettingsService service = mock(IntegrationSettingsService.class);
        when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(
                "{ \"nemaki:doc\": { \"nemaki:dept\": { \"enabled\": true, \"catalogName\": \"dept_a\" } } }");
        when(service.readSetting("catalog.sync.propertyMappings.canopy")).thenReturn(
                "{ \"nemaki:doc\": { \"nemaki:dept\": { \"enabled\": true, \"catalogName\": \"dept_b\" } } }");

        TypeService typeService = mock(TypeService.class);
        NemakiPropertyDefinitionCore strCore = mockCore(PropertyType.STRING);
        when(typeService.getPropertyDefinitionCoreByPropertyId(anyString(), eq("nemaki:dept")))
                .thenReturn(strCore);

        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        when(repoMap.keys()).thenReturn(java.util.Set.of("bedroom", "canopy"));
        CatalogPropertyMappingResolver resolver = new CatalogPropertyMappingResolver(service, typeService, repoMap);

        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        factory.setPropertyMappingResolver(resolver);

        Document doc = new Document();
        doc.setId("doc-x");
        doc.setName("Doc");
        doc.setObjectType("nemaki:doc");
        List<Property> customProps = new ArrayList<>();
        customProps.add(new Property("nemaki:dept", "Engineering"));
        doc.setSubTypeProperties(customProps);

        Map<String, Object> bedroomEntity = factory.buildDocumentEntity("bedroom", doc);
        Map<String, Object> canopyEntity = factory.buildDocumentEntity("canopy", doc);
        Map<String, Object> bedroomAttrs = (Map<String, Object>) bedroomEntity.get("attributes");
        Map<String, Object> canopyAttrs = (Map<String, Object>) canopyEntity.get("attributes");

        assertEquals("Engineering", bedroomAttrs.get("dept_a"));
        assertNull(bedroomAttrs.get("dept_b"));
        assertEquals("Engineering", canopyAttrs.get("dept_b"));
        assertNull(canopyAttrs.get("dept_a"));
    }

    /**
     * Regression test: same catalogName, same propertyId, but different type across repos.
     * Schema generation keeps the first repo's type (bedroom=STRING wins over canopy=INTEGER).
     * Payload from the losing repo (canopy) must NOT emit the value because its type
     * disagrees with the schema.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testCrossRepoSamePropertyIdTypeMismatchSuppressesLosingPayload() {
        IntegrationSettingsService service = mock(IntegrationSettingsService.class);
        // Both repos map nemaki:priority → "priority", but bedroom resolves as STRING, canopy as INTEGER
        when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(
                "{ \"nemaki:doc\": { \"nemaki:priority\": { \"enabled\": true, \"catalogName\": \"priority\" } } }");
        when(service.readSetting("catalog.sync.propertyMappings.canopy")).thenReturn(
                "{ \"nemaki:doc\": { \"nemaki:priority\": { \"enabled\": true, \"catalogName\": \"priority\" } } }");

        TypeService typeService = mock(TypeService.class);
        NemakiPropertyDefinitionCore strCore = mockCore(PropertyType.STRING);
        NemakiPropertyDefinitionCore intCore = mockCore(PropertyType.INTEGER);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:priority")).thenReturn(strCore);
        when(typeService.getPropertyDefinitionCoreByPropertyId("canopy", "nemaki:priority")).thenReturn(intCore);

        // bedroom comes first in iteration order → STRING wins in global union
        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        when(repoMap.keys()).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("bedroom", "canopy")));
        CatalogPropertyMappingResolver resolver = new CatalogPropertyMappingResolver(service, typeService, repoMap);

        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        factory.setPropertyMappingResolver(resolver);

        Document doc = new Document();
        doc.setId("doc-conflict");
        doc.setName("Conflict Doc");
        doc.setObjectType("nemaki:doc");
        List<Property> customProps = new ArrayList<>();
        customProps.add(new Property("nemaki:priority", "high"));
        doc.setSubTypeProperties(customProps);

        // bedroom (STRING, winner) → value emitted
        Map<String, Object> bedroomEntity = factory.buildDocumentEntity("bedroom", doc);
        Map<String, Object> bedroomAttrs = (Map<String, Object>) bedroomEntity.get("attributes");
        assertEquals("high", bedroomAttrs.get("priority"), "Winner repo should emit value");

        // canopy (INTEGER, loser) → value suppressed
        Map<String, Object> canopyEntity = factory.buildDocumentEntity("canopy", doc);
        Map<String, Object> canopyAttrs = (Map<String, Object>) canopyEntity.get("attributes");
        assertNull(canopyAttrs.get("priority"), "Losing repo with type mismatch should NOT emit value");
    }

    /**
     * Cross-repo normalization: different propertyIds mapping to the same catalogName
     * with the same type should both be emitted (this is the intended use case).
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testCrossRepoDifferentPropertyIdSameTypeBothEmitted() {
        IntegrationSettingsService service = mock(IntegrationSettingsService.class);
        // bedroom maps nemaki:dept_a → "department", canopy maps nemaki:dept_b → "department"
        when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(
                "{ \"nemaki:doc\": { \"nemaki:dept_a\": { \"enabled\": true, \"catalogName\": \"department\" } } }");
        when(service.readSetting("catalog.sync.propertyMappings.canopy")).thenReturn(
                "{ \"nemaki:doc\": { \"nemaki:dept_b\": { \"enabled\": true, \"catalogName\": \"department\" } } }");

        TypeService typeService = mock(TypeService.class);
        NemakiPropertyDefinitionCore strCore = mockCore(PropertyType.STRING);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:dept_a")).thenReturn(strCore);
        when(typeService.getPropertyDefinitionCoreByPropertyId("canopy", "nemaki:dept_b")).thenReturn(strCore);

        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        when(repoMap.keys()).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("bedroom", "canopy")));
        CatalogPropertyMappingResolver resolver = new CatalogPropertyMappingResolver(service, typeService, repoMap);

        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        factory.setPropertyMappingResolver(resolver);

        Document bedroomDoc = new Document();
        bedroomDoc.setId("doc-br");
        bedroomDoc.setName("BR Doc");
        bedroomDoc.setObjectType("nemaki:doc");
        List<Property> brProps = new ArrayList<>();
        brProps.add(new Property("nemaki:dept_a", "Engineering"));
        bedroomDoc.setSubTypeProperties(brProps);

        Document canopyDoc = new Document();
        canopyDoc.setId("doc-cn");
        canopyDoc.setName("CN Doc");
        canopyDoc.setObjectType("nemaki:doc");
        List<Property> cnProps = new ArrayList<>();
        cnProps.add(new Property("nemaki:dept_b", "Sales"));
        canopyDoc.setSubTypeProperties(cnProps);

        // Both repos should emit because type is compatible
        Map<String, Object> brEntity = factory.buildDocumentEntity("bedroom", bedroomDoc);
        assertEquals("Engineering", ((Map<String, Object>) brEntity.get("attributes")).get("department"));

        Map<String, Object> cnEntity = factory.buildDocumentEntity("canopy", canopyDoc);
        assertEquals("Sales", ((Map<String, Object>) cnEntity.get("attributes")).get("department"),
                "Different propertyId but same catalogName and type should be emitted");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildDocumentEntityWithoutResolverOmitsCustomProps() {
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();

        Document doc = new Document();
        doc.setId("doc-003");
        doc.setName("Plain Doc");
        doc.setObjectType("nemaki:document");
        List<Property> customProps = new ArrayList<>();
        customProps.add(new Property("nemaki:department", "HR"));
        doc.setSubTypeProperties(customProps);

        Map<String, Object> entity = factory.buildDocumentEntity("bedroom", doc);
        Map<String, Object> attrs = (Map<String, Object>) entity.get("attributes");

        assertNull(attrs.get("department"), "Without resolver, custom props should not appear");
        assertEquals("doc-003", attrs.get("objectId"));
    }

    private static NemakiPropertyDefinitionCore mockCore(PropertyType type) {
        return mockCore(type, Cardinality.SINGLE);
    }

    private static NemakiPropertyDefinitionCore mockCore(PropertyType type, Cardinality card) {
        NemakiPropertyDefinitionCore core = org.mockito.Mockito.mock(NemakiPropertyDefinitionCore.class,
                org.mockito.Mockito.withSettings().stubOnly());
        org.mockito.Mockito.doReturn(type).when(core).getPropertyType();
        org.mockito.Mockito.doReturn(card).when(core).getCardinality();
        return core;
    }

    private GregorianCalendar calendar(String isoInstant) {
        GregorianCalendar calendar = GregorianCalendar.from(java.time.ZonedDateTime.parse(isoInstant));
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        return calendar;
    }
}
