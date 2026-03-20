package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

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
        assertEquals("ACTIVE", attributes.get("lifecycleState"));
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

    private GregorianCalendar calendar(String isoInstant) {
        GregorianCalendar calendar = GregorianCalendar.from(java.time.ZonedDateTime.parse(isoInstant));
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        return calendar;
    }
}
