/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.service.dao.impl;

import java.util.List;

import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.repository.RequestDurationCacheBean;
import jp.aegif.nemaki.service.cache.NemakiCache;
import jp.aegif.nemaki.service.dao.ContentDaoService;
import jp.aegif.nemaki.util.constant.NemakiConstant;
import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * Dao Service implementation for CouchDB.
 *
 * @author linzhixing
 *
 */
@Component
public class ContentDaoServiceImpl implements ContentDaoService {

	private ContentDaoService nonCachedContentDaoService;
	private NemakiCache nemakiCache;

	public ContentDaoServiceImpl() {
		
	}

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions() {
		Cache typeCache = nemakiCache.getTypeCache();
		Element v = typeCache.get("typedefs");

		if (v != null) {
			return (List<NemakiTypeDefinition>) v.getObjectValue();
		}

		List<NemakiTypeDefinition> result = nonCachedContentDaoService
				.getTypeDefinitions();

		if (CollectionUtils.isEmpty(result)) {
			return null;
		} else {
			typeCache.put(new Element("typedefs", result));
			return result;
		}
	}

	@Override
	public NemakiTypeDefinition getTypeDefinition(String typeId) {

		Cache typeCache = nemakiCache.getTypeCache();
		Element v = typeCache.get("typedefs");

		List<NemakiTypeDefinition> typeDefs = null;
		if (v == null) {
			typeDefs = this.getTypeDefinitions();
		} else {
			typeDefs = (List<NemakiTypeDefinition>) v.getObjectValue();
		}

		for (NemakiTypeDefinition def : typeDefs) {
			if (def.getTypeId().equals(typeId)) {
				return def;
			}
		}
		return null;
	}

	@Override
	public NemakiTypeDefinition createTypeDefinition(
			NemakiTypeDefinition typeDefinition) {
		NemakiTypeDefinition nt = nonCachedContentDaoService
				.createTypeDefinition(typeDefinition);
		Cache typeCache = nemakiCache.getTypeCache();
		typeCache.remove("typedefs");
		return nt;
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(
			NemakiTypeDefinition typeDefinition) {
		NemakiTypeDefinition nt = nonCachedContentDaoService
				.updateTypeDefinition(typeDefinition);
		Cache typeCache = nemakiCache.getTypeCache();
		typeCache.remove("typedefs");
		return nt;
	}

	@Override
	public void deleteTypeDefinition(String nodeId) {
		nonCachedContentDaoService.deleteTypeDefinition(nodeId);

		Cache typeCache = nemakiCache.getTypeCache();
		typeCache.remove("typedefs");
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores() {
		return nonCachedContentDaoService.getPropertyDefinitionCores();
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String nodeId) {
		return nonCachedContentDaoService.getPropertyDefinitionCore(nodeId);
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(
			String propertyId) {
		return nonCachedContentDaoService
				.getPropertyDefinitionCoreByPropertyId(propertyId);
	}

	@Override
	public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(
			String nodeId) {
		return nonCachedContentDaoService.getPropertyDefinitionDetail(nodeId);
	}

	@Override
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(
			String coreNodeId) {
		return nonCachedContentDaoService
				.getPropertyDefinitionDetailByCoreNodeId(coreNodeId);
	}

	@Override
	public NemakiPropertyDefinitionCore createPropertyDefinitionCore(
			NemakiPropertyDefinitionCore propertyDefinitionCore) {
		return nonCachedContentDaoService
				.createPropertyDefinitionCore(propertyDefinitionCore);
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		return nonCachedContentDaoService
				.createPropertyDefinitionDetail(propertyDefinitionDetail);
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		NemakiPropertyDefinitionDetail np = nonCachedContentDaoService
				.updatePropertyDefinitionDetail(propertyDefinitionDetail);
		Cache typeCache = nemakiCache.getTypeCache();
		typeCache.remove("typedefs");
		return np;
	}

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	@Override
	public NodeBase getNodeBase(String objectId) {
		return nonCachedContentDaoService.getNodeBase(objectId);
	}

	/**
	 * get Document/Folder(not Attachment) Return Document/Folder class FIXME
	 * devide this method into getDcoument & getFolder
	 */
	@Override
	public Content getContent(String objectId) {
		Cache contentCache = nemakiCache.getContentCache();
		Element v = contentCache.get(objectId);

		if (v != null) {
			return (Content) v.getObjectValue();
		}

		Content content = nonCachedContentDaoService.getContent(objectId);

		if (content == null) {
			return null;
		} else {
			contentCache.put(new Element(objectId, content));
		}

		return content;
	}

	@Override
	public boolean existContent(String objectTypeId) {
		return nonCachedContentDaoService.existContent(objectTypeId);
	}

	@Override
	public Document getDocument(String objectId) {
		Cache documentCache = nemakiCache.getDocumentCache();
		Element v = documentCache.get(objectId);

		if (v != null) {
			return (Document) v.getObjectValue();
		}

		Document doc = nonCachedContentDaoService.getDocument(objectId);
		if (doc == null) {
			return null;
		} else {
			documentCache.put(new Element(objectId, doc));
			return doc;
		}
	}

	@Override
	public List<Document> getCheckedOutDocuments(String parentFolderId) {
		return nonCachedContentDaoService
				.getCheckedOutDocuments(parentFolderId);
	}

	@Override
	public VersionSeries getVersionSeries(String nodeId) {
		Cache versionSeriesCache = nemakiCache.getVersionSeriesCache();
		Element v = versionSeriesCache.get(nodeId);

		if (v != null) {
			return (VersionSeries) v.getObjectValue();
		}

		VersionSeries vs = nonCachedContentDaoService.getVersionSeries(nodeId);

		if (vs == null) {
			return null;
		} else {
			versionSeriesCache.put(new Element(nodeId, vs));
			return vs;
		}
	}

	@Override
	public List<Document> getAllVersions(String versionSeriesId) {
		return nonCachedContentDaoService.getAllVersions(versionSeriesId);
	}

	@Override
	public Document getDocumentOfLatestVersion(String versionSeriesId) {
		return nonCachedContentDaoService
				.getDocumentOfLatestVersion(versionSeriesId);
	}

	@Override
	public Document getDocumentOfLatestMajorVersion(String versionSeriesId) {
		return nonCachedContentDaoService
				.getDocumentOfLatestMajorVersion(versionSeriesId);
	}

	@Override
	public Folder getFolder(String objectId) {
		Cache folderCache = nemakiCache.getFolderCache();
		Element v = folderCache.get(objectId);

		if (v != null) {
			return (Folder) v.getObjectValue();
		}

		Folder folder = nonCachedContentDaoService.getFolder(objectId);

		if (folder == null) {
			return null;
		} else {
			folderCache.put(new Element(objectId, folder));
			return folder;
		}
	}

	@Override
	public Folder getFolderByPath(String path) {
		return nonCachedContentDaoService.getFolderByPath(path);
	}

	@Override
	public List<Content> getLatestChildrenIndex(String parentId) {
		return nonCachedContentDaoService.getLatestChildrenIndex(parentId);
	}

	@Override
	public Content getChildByName(String parentId, String name) {
		return nonCachedContentDaoService.getChildByName(parentId, name);
	}

	@Override
	public Relationship getRelationship(String objectId) {
		return nonCachedContentDaoService.getRelationship(objectId);
	}

	@Override
	public List<Relationship> getRelationshipsBySource(String sourceId) {
		return nonCachedContentDaoService.getRelationshipsBySource(sourceId);
	}

	@Override
	public List<Relationship> getRelationshipsByTarget(String targetId) {
		return nonCachedContentDaoService.getRelationshipsByTarget(targetId);
	}

	@Override
	public Policy getPolicy(String objectId) {
		return nonCachedContentDaoService.getPolicy(objectId);
	}

	@Override
	public List<Policy> getAppliedPolicies(String objectId) {
		return nonCachedContentDaoService.getAppliedPolicies(objectId);
	}

	@Override
	public Item getItem(String objectId) {
		return nonCachedContentDaoService.getItem(objectId);
	}

	@Override
	public Document create(Document document) {
		Document d = nonCachedContentDaoService.create(document);
		Cache documentCache = nemakiCache.getDocumentCache();
		documentCache.put(new Element(d.getId(), d));
		return d;
	}

	@Override
	public VersionSeries create(VersionSeries versionSeries) {
		VersionSeries vs = nonCachedContentDaoService.create(versionSeries);
		Cache versionSeriesCache = nemakiCache.getVersionSeriesCache();
		versionSeriesCache.put(new Element(vs.getId(), vs));
		return vs;
	}

	@Override
	public Change create(Change change) {
		Change created = nonCachedContentDaoService.create(change);
		Change latest = nonCachedContentDaoService.getLatestChange();
		nemakiCache.getLatestChangeTokenCache().removeAll();
		nemakiCache.getLatestChangeTokenCache().put(new Element(NemakiConstant.TOKEN_CACHE_LATEST_CHANGE_TOKEN, latest));
		return created;
	}

	@Override
	public Folder create(Folder folder) {
		Folder created = nonCachedContentDaoService.create(folder);
		Cache folderCache = nemakiCache.getFolderCache();
		folderCache.put(new Element(created.getId(), created));
		return created;
	}

	@Override
	public Relationship create(Relationship relationship) {
		Relationship created = nonCachedContentDaoService.create(relationship);
		return created;
	}

	@Override
	public Policy create(Policy policy) {
		Policy created = nonCachedContentDaoService.create(policy);
		return created;
	}

	@Override
	public Item create(Item item) {
		return nonCachedContentDaoService.create(item);
	}

	@Override
	public Document update(Document document) {
		Document updated = nonCachedContentDaoService.update(document);
		Cache documentCache = nemakiCache.getDocumentCache();
		documentCache.put(new Element(updated.getId(), updated));
		return updated;
	}

	@Override
	public VersionSeries update(VersionSeries versionSeries) {
		VersionSeries updated = nonCachedContentDaoService
				.update(versionSeries);
		Cache versionSeriesCache = nemakiCache.getVersionSeriesCache();
		versionSeriesCache.put(new Element(updated.getId(), updated));
		return updated;
	}

	@Override
	public Folder update(Folder folder) {
		Folder updated = nonCachedContentDaoService.update(folder);
		Cache folderCache = nemakiCache.getFolderCache();
		folderCache.put(new Element(updated.getId(), updated));
		return updated;
	}

	@Override
	public Relationship update(Relationship relationship) {
		return nonCachedContentDaoService.update(relationship);
	}

	@Override
	public Policy update(Policy policy) {
		return nonCachedContentDaoService.update(policy);
	}

	@Override
	public Item update(Item item) {
		return nonCachedContentDaoService.update(item);
	}

	@Override
	public void delete(String objectId) {
		NodeBase nb = nonCachedContentDaoService.getNodeBase(objectId);

		// remove from database
		nonCachedContentDaoService.delete(objectId);

		// remove from cache
		String id = objectId;
		Cache folderCache = nemakiCache.getFolderCache();
		Cache contentCache = nemakiCache.getContentCache();
		Cache documentCache = nemakiCache.getDocumentCache();
		Cache versionSeriesCache = nemakiCache.getVersionSeriesCache();
		Cache attachmentCache = nemakiCache.getAttachmentCache();

		contentCache.remove(id);
		folderCache.remove(id);

		if (nb.isDocument()) {
			Document d = this.getDocument(objectId);
			// we can delete versionSeries or not?
			versionSeriesCache.remove(d.getVersionSeriesId());
			attachmentCache.remove(d.getAttachmentNodeId());
			documentCache.remove(id);
		}

		if (nb.isAttachment()) {
			attachmentCache.remove(id);
		}
	}

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	@Override
	public AttachmentNode getAttachment(String attachmentId) {
		Cache attachmentCache = nemakiCache.getAttachmentCache();
		Element v = attachmentCache.get(attachmentId);

		AttachmentNode an = null;
		if (v != null) {
			an = (AttachmentNode) v.getObjectValue();

		} else {
			an = nonCachedContentDaoService.getAttachment(attachmentId);
			if (an == null) {
				return null;
			} else {
				attachmentCache.put(new Element(attachmentId, an));
			}
		}

		return an;
		// throw new
		// UnsupportedOperationException(Thread.currentThread().getStackTrace()[0].getMethodName()
		// + ":this method is only for non-cahced service.");
	}

	@Override
	public void setStream(AttachmentNode attachmentNode) {
		nonCachedContentDaoService.setStream(attachmentNode);

		// throw new
		// UnsupportedOperationException(Thread.currentThread().getStackTrace()[0].getMethodName()
		// + ":this method is only for non-cahced service.");
	}

	@Override
	public Rendition getRendition(String objectId) {
		return nonCachedContentDaoService.getRendition(objectId);
	}

	@Override
	public String createRendition(Rendition rendition,
			ContentStream contentStream) {
		return nonCachedContentDaoService.createRendition(rendition, contentStream);
	}

	@Override
	public String createAttachment(AttachmentNode attachment, ContentStream cs) {
		return nonCachedContentDaoService.createAttachment(attachment, cs);
	}

	@Override
	public void updateAttachment(AttachmentNode attachment,
			ContentStream contentStream) {
		Cache attachmentCache = nemakiCache.getAttachmentCache();
		Element v = attachmentCache.get(attachment.getId());
		if (v != null) {
			attachmentCache.remove(attachment.getId());
		}
		nonCachedContentDaoService.updateAttachment(attachment, contentStream);

	}

	// //////////////////////////////////////////////////////////////////////////////
	// Change events
	// //////////////////////////////////////////////////////////////////////////////
	@Override
	public Change getChangeEvent(String token) {
		return nonCachedContentDaoService.getChangeEvent(token);
	}

	@Override
	public Change getLatestChange() {
		Change change =null;
		
		Element v = nemakiCache.getLatestChangeTokenCache().get("lc");
		if (v != null) {
			change = (Change)v.getObjectValue();
		}
		
		if (change != null) {
			return change;
		} else {
			change = nonCachedContentDaoService.getLatestChange();
			if (change != null) {
				nemakiCache.getLatestChangeTokenCache().put(new Element(NemakiConstant.TOKEN_CACHE_LATEST_CHANGE_TOKEN, change));
			}
			return change;
		}
	}

	@Override
	public List<Change> getLatestChanges(String startToken, int maxItems) {
		return nonCachedContentDaoService
				.getLatestChanges(startToken, maxItems);
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Archive
	// //////////////////////////////////////////////////////////////////////////////
	@Override
	public Archive getArchive(String archiveId) {
		return nonCachedContentDaoService.getArchive(archiveId);
	}

	@Override
	public Archive getArchiveByOriginalId(String originalId) {
		return nonCachedContentDaoService.getArchiveByOriginalId(originalId);
	}

	@Override
	public Archive getAttachmentArchive(Archive archive) {
		return nonCachedContentDaoService.getAttachmentArchive(archive);
	}

	@Override
	public List<Archive> getChildArchives(Archive archive) {
		return nonCachedContentDaoService.getChildArchives(archive);
	}

	@Override
	public List<Archive> getArchivesOfVersionSeries(String versionSeriesId) {
		return nonCachedContentDaoService
				.getArchivesOfVersionSeries(versionSeriesId);
	}

	@Override
	public List<Archive> getAllArchives() {
		return nonCachedContentDaoService.getAllArchives();
	}

	@Override
	public Archive createArchive(Archive archive, Boolean deletedWithParent) {
		return nonCachedContentDaoService.createArchive(archive,
				deletedWithParent);
	}

	@Override
	public Archive createAttachmentArchive(Archive archive) {
		return nonCachedContentDaoService.createAttachmentArchive(archive);
	}

	@Override
	public void deleteArchive(String archiveId) {
		nonCachedContentDaoService.deleteArchive(archiveId);
	}

	@Override
	public void restoreContent(Archive archive) {
		nonCachedContentDaoService.restoreContent(archive);
	}

	@Override
	public void restoreAttachment(Archive archive) {
		nonCachedContentDaoService.restoreAttachment(archive);
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Spring
	// //////////////////////////////////////////////////////////////////////////////
	public void setNonCachedContentDaoService(
			ContentDaoService nonCachedContentDaoService) {
		this.nonCachedContentDaoService = nonCachedContentDaoService;
	}

	public void setNemakiCache(NemakiCache nemakiCache) {
		this.nemakiCache = nemakiCache;
	}
}