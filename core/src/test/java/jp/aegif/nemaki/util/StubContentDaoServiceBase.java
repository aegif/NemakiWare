package jp.aegif.nemaki.util;

import java.io.InputStream;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ContentStream;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.*;

/**
 * Minimal stub base for ContentDaoService. Returns null / empty / 0 for all
 * methods. Subclass and override only the method(s) you need in your test.
 *
 * This avoids Mockito inline-mock byte-buddy issues while keeping test classes
 * concise.
 */
@SuppressWarnings("unchecked")
public class StubContentDaoServiceBase implements ContentDaoService {

    // Type & Property definition
    @Override public List<NemakiTypeDefinition> getTypeDefinitions(String r) { return Collections.emptyList(); }
    @Override public NemakiTypeDefinition getTypeDefinition(String r, String id) { return null; }
    @Override public NemakiTypeDefinition createTypeDefinition(String r, NemakiTypeDefinition d) { return null; }
    @Override public NemakiTypeDefinition updateTypeDefinition(String r, NemakiTypeDefinition d) { return null; }
    @Override public void deleteTypeDefinition(String r, String id) {}
    @Override public void clearTypeCache(String r) {}
    @Override public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String r) { return Collections.emptyList(); }
    @Override public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String r, String id) { return null; }
    @Override public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String r, String propId) { return null; }
    @Override public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String r, String id) { return null; }
    @Override public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetails(String r) { return Collections.emptyList(); }
    @Override public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String r, String coreId) { return Collections.emptyList(); }
    @Override public NemakiPropertyDefinitionCore createPropertyDefinitionCore(String r, NemakiPropertyDefinitionCore c) { return null; }
    @Override public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String r, NemakiPropertyDefinitionDetail d) { return null; }
    @Override public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String r, NemakiPropertyDefinitionDetail d) { return null; }

    // Content
    @Override public NodeBase getNodeBase(String r, String id) { return null; }
    @Override public Content getContent(String r, String id) { return null; }
    @Override public Map<String, Content> getContentsByIds(String r, List<String> ids) { return Collections.emptyMap(); }
    @Override public Content getContentFresh(String r, String id) { return null; }
    @Override public Document getDocumentFresh(String r, String id) { return null; }
    @Override public Folder getFolderFresh(String r, String id) { return null; }
    @Override public Relationship getRelationshipFresh(String r, String id) { return null; }
    @Override public Policy getPolicyFresh(String r, String id) { return null; }
    @Override public Item getItemFresh(String r, String id) { return null; }
    @Override public boolean existContent(String r, String id) { return false; }
    @Override public Document getDocument(String r, String id) { return null; }
    @Override public List<Document> getCheckedOutDocuments(String r, String folderId) { return Collections.emptyList(); }
    @Override public VersionSeries getVersionSeries(String r, String vsId) { return null; }
    @Override public Document getDocumentOfLatestVersion(String r, String vsId) { return null; }
    @Override public Document getDocumentOfLatestMajorVersion(String r, String vsId) { return null; }
    @Override public List<Document> getAllVersions(String r, String vsId) { return Collections.emptyList(); }
    @Override public Folder getFolder(String r, String id) { return null; }
    @Override public Folder getFolderByPath(String r, String path) { return null; }
    @Override public List<Content> getChildren(String r, String folderId) { return Collections.emptyList(); }
    @Override public List<Content> getChildrenPaged(String r, String folderId, int skip, int limit) { return Collections.emptyList(); }
    @Override public long getChildrenCount(String r, String folderId) { return 0; }
    @Override public Content getChildByName(String r, String folderId, String name) { return null; }
    @Override public List<String> getChildrenNames(String r, String folderId) { return Collections.emptyList(); }
    @Override public Relationship getRelationship(String r, String id) { return null; }
    @Override public List<Relationship> getRelationshipsBySource(String r, String sourceId) { return Collections.emptyList(); }
    @Override public List<Relationship> getRelationshipsByTarget(String r, String targetId) { return Collections.emptyList(); }
    @Override public Policy getPolicy(String r, String id) { return null; }
    @Override public List<Policy> getAppliedPolicies(String r, String objectId) { return Collections.emptyList(); }
    @Override public Item getItem(String r, String id) { return null; }
    @Override public UserItem getUserItem(String r, String userId) { return null; }
    @Override public UserItem getUserItemById(String r, String id) { return null; }
    @Override public List<UserItem> getUserItems(String r) { return Collections.emptyList(); }
    @Override public List<UserItem> getUserItems(String r, int skip, int limit) { return Collections.emptyList(); }
    @Override public int getUserItemCount(String r) { return 0; }
    @Override public GroupItem getGroupItem(String r, String groupId) { return null; }
    @Override public GroupItem getGroupItemById(String r, String id) { return null; }
    @Override public GroupItem getGroupItemByIdFresh(String r, String id) { return null; }
    @Override public List<GroupItem> getGroupItems(String r) { return Collections.emptyList(); }
    @Override public List<GroupItem> getGroupItems(String r, int skip, int limit) { return Collections.emptyList(); }
    @Override public List<String> getGroupIdsDirectlyContainingGroup(String r, String groupId) { return Collections.emptyList(); }
    @Override public List<String> getGroupIdsDirectlyContainingUser(String r, String userId) { return Collections.emptyList(); }
    @Override public int getGroupItemCount(String r) { return 0; }
    @Override public List<String> getJoinedGroupByUserId(String r, String userId) { return Collections.emptyList(); }
    @Override public PatchHistory getPatchHistoryByName(String r, String name) { return null; }
    @Override public Configuration getConfiguration(String r) { return new Configuration(); }
    @Override public List<ApiKey> getApiKeys(String r) { return Collections.emptyList(); }

    // Create methods
    @Override public Document create(String r, Document d) { return null; }
    @Override public VersionSeries create(String r, VersionSeries vs) { return null; }
    @Override public Folder create(String r, Folder f) { return null; }
    @Override public Relationship create(String r, Relationship rel) { return null; }
    @Override public Policy create(String r, Policy p) { return null; }
    @Override public Item create(String r, Item i) { return null; }
    @Override public UserItem create(String r, UserItem u) { return null; }
    @Override public GroupItem create(String r, GroupItem g) { return null; }
    @Override public PatchHistory create(String r, PatchHistory ph) { return null; }
    @Override public Configuration create(String r, Configuration c) { return null; }
    @Override public ApiKey create(String r, ApiKey ak) { return null; }
    @Override public NodeBase create(String r, NodeBase nb) { return null; }
    @Override public Change create(String r, Change c) { return null; }

    // Update methods
    @Override public Document update(String r, Document d) { return null; }
    @Override public Document move(String r, Document d, String srcId) { return null; }
    @Override public VersionSeries update(String r, VersionSeries vs) { return null; }
    @Override public Folder update(String r, Folder f) { return null; }
    @Override public Folder move(String r, Folder f, String srcId) { return null; }
    @Override public Relationship update(String r, Relationship rel) { return null; }
    @Override public Policy update(String r, Policy p) { return null; }
    @Override public Item update(String r, Item i) { return null; }
    @Override public UserItem update(String r, UserItem u) { return null; }
    @Override public GroupItem update(String r, GroupItem g) { return null; }
    @Override public PatchHistory update(String r, PatchHistory ph) { return null; }
    @Override public Configuration update(String r, Configuration c) { return null; }
    @Override public NodeBase update(String r, NodeBase nb) { return null; }

    // Delete methods
    @Override public void delete(String r, String id) {}
    @Override public void delete(String r, String id, boolean verify) {}
    @Override public int deleteBulk(String r, List<String> ids) { return 0; }

    // Attachment
    @Override public AttachmentNode getAttachment(String r, String id) { return null; }
    @Override public AttachmentNode getAttachmentRef(String r, String id) { return null; }
    @Override public void setStream(String r, AttachmentNode a) {}
    @Override public Rendition getRendition(String r, String id) { return null; }
    @Override public String createRendition(String r, Rendition rend, ContentStream cs) { return null; }
    @Override public String createAttachment(String r, AttachmentNode a, ContentStream cs) { return null; }
    @Override public void updateAttachment(String r, AttachmentNode a, ContentStream cs) {}

    // Change event
    @Override public Change getChangeEvent(String r, String token) { return null; }
    @Override public Change getLatestChange(String r) { return null; }
    @Override public List<Change> getLatestChanges(String r, String startToken, int maxItems) { return Collections.emptyList(); }
    @Override public List<Change> getObjectChanges(String r, String objectId) { return Collections.emptyList(); }

    // Archive
    @Override public Archive getArchive(String r, String id) { return null; }
    @Override public Archive getArchiveByOriginalId(String r, String originalId) { return null; }
    @Override public Archive getAttachmentArchive(String r, Archive a) { return null; }
    @Override public List<Archive> getChildArchives(String r, Archive a) { return Collections.emptyList(); }
    @Override public List<Archive> getArchivesOfVersionSeries(String r, String vsId) { return Collections.emptyList(); }
    @Override public List<Archive> getAllArchives(String r) { return Collections.emptyList(); }
    @Override public List<Archive> getArchives(String r, Integer skip, Integer limit, Boolean desc) { return Collections.emptyList(); }
    @Override public List<Archive> getArchivesByCreator(String r, String creator) { return Collections.emptyList(); }
    @Override public List<Archive> getArchivesByArchivedBy(String r, String archivedBy) { return Collections.emptyList(); }
    @Override public Archive createArchive(String r, Archive a, Boolean deleteWithParent) { return null; }
    @Override public Archive createAttachmentArchive(String r, Archive a) { return null; }
    @Override public String deleteArchive(String r, String id) { return null; }
    @Override public void deleteDocumentArchive(String r, String id) {}
    @Override public void refreshCmisObjectData(String r, String id) {}
    @Override public void restoreContent(String r, Archive a) {}
    @Override public void restoreAttachment(String r, Archive a) {}
    @Override public void restoreDocumentWithArchive(String r, Archive a) {}
    @Override public void restoreVersionSeries(String r, String vsId) {}
    @Override public Long getAttachmentActualSize(String r, String attachId) { return null; }

    // Retention lifecycle
    @Override public List<Archive> getArchivesByState(String r, String state) { return Collections.emptyList(); }
    @Override public List<Archive> getSearchableArchives(String r, String state) { return Collections.emptyList(); }
    @Override public List<Archive> getSearchableArchivesPaged(String r, int skip, int limit, boolean desc) { return Collections.emptyList(); }
    @Override public long getSearchableArchivesCount(String r) { return 0; }
    @Override public List<Archive> getSearchableArchivesByStatePaged(String r, String state, int skip, int limit, boolean desc) { return Collections.emptyList(); }
    @Override public long getSearchableArchivesByStateCount(String r, String state) { return 0; }
    @Override public List<Archive> getArchivesForColdTransition(String r, GregorianCalendar beforeDate) { return Collections.emptyList(); }
    @Override public void updateArchiveState(String r, String archiveId, String newState, Map<String, String> contentRef, GregorianCalendar coldArchivedAt) {}
    @Override public InputStream getArchiveContentStream(String r, Archive archive) { return null; }
    @Override public boolean deleteArchiveContent(String r, Archive archive) { return false; }
    @Override public List<String> getExpiredDocumentIds(String r, GregorianCalendar beforeDate) { return Collections.emptyList(); }
    @Override public List<String> getStaleDocumentIds(String r, GregorianCalendar beforeDate) { return Collections.emptyList(); }
    @Override public void resetColdMoveMetadata(String r, String archiveId) {}
    @Override public void updateArchiveColdMoveMode(String r, String archiveId, String coldMoveMode) {}

    // WebAuthn
    @Override public List<WebAuthnCredential> getWebAuthnCredentialsByUserId(String r, String userId) { return Collections.emptyList(); }
    @Override public WebAuthnCredential getWebAuthnCredentialByCredentialId(String r, String credId) { return null; }
    @Override public WebAuthnCredential createWebAuthnCredential(String r, WebAuthnCredential cred) { return null; }
    @Override public WebAuthnCredential updateWebAuthnCredential(String r, WebAuthnCredential cred) { return null; }
    @Override public void deleteWebAuthnCredential(String r, String id) {}

    // Secondary types & object count
    @Override public List<Content> getContentsBySecondaryType(String r, String secondaryTypeId) { return Collections.emptyList(); }
    @Override public long getObjectCount(String r, String objectType) { return 0; }
}
