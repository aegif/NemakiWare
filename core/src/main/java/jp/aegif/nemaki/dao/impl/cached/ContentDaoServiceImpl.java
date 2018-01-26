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
package jp.aegif.nemaki.dao.impl.cached;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.PatchHistory;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.model.Tree;
import net.sf.ehcache.Element;

/**
 * Dao Service implementation for CouchDB.
 *
 * @author linzhixing
 *
 */
@Component
public class ContentDaoServiceImpl implements ContentDaoService {
	private static final Log log = LogFactory.getLog(ContentDaoServiceImpl.class);

	private ContentDaoService nonCachedContentDaoService;
	private NemakiCachePool nemakiCachePool;

	private final String TOKEN_CACHE_LATEST_CHANGE_TOKEN = "lc";

	public ContentDaoServiceImpl() {

	}

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		List<NemakiTypeDefinition> v = typeCache.get("typedefs");

		if (v != null) {
			return v;
		}

		List<NemakiTypeDefinition> result = nonCachedContentDaoService.getTypeDefinitions(repositoryId);

		if (CollectionUtils.isEmpty(result)) {
			return null;
		} else {
			typeCache.put(new Element("typedefs", result));
			return result;
		}
	}

	@Override
	public NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId) {

		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		List<NemakiTypeDefinition> v = typeCache.get("typedefs");

		List<NemakiTypeDefinition> typeDefs = null;
		if (v == null) {
			typeDefs = this.getTypeDefinitions(repositoryId);
		} else {
			typeDefs = v;
		}

		for (NemakiTypeDefinition def : typeDefs) {
			if (def.getTypeId().equals(typeId)) {
				return def;
			}
		}
		return null;
	}

	@Override
	public NemakiTypeDefinition createTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		NemakiTypeDefinition nt = nonCachedContentDaoService.createTypeDefinition(repositoryId, typeDefinition);
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		return nt;
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		NemakiTypeDefinition nt = nonCachedContentDaoService.updateTypeDefinition(repositoryId, typeDefinition);
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		return nt;
	}

	@Override
	public void deleteTypeDefinition(String repositoryId, String nodeId) {
		nonCachedContentDaoService.deleteTypeDefinition(repositoryId, nodeId);

		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId) {
		return nonCachedContentDaoService.getPropertyDefinitionCores(repositoryId);
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String nodeId) {
		return nonCachedContentDaoService.getPropertyDefinitionCore(repositoryId, nodeId);
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId) {
		return nonCachedContentDaoService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
	}

	@Override
	public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String repositoryId, String nodeId) {
		return nonCachedContentDaoService.getPropertyDefinitionDetail(repositoryId, nodeId);
	}

	@Override
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String repositoryId,
			String coreNodeId) {
		return nonCachedContentDaoService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, coreNodeId);
	}

	@Override
	public NemakiPropertyDefinitionCore createPropertyDefinitionCore(String repositoryId,
			NemakiPropertyDefinitionCore propertyDefinitionCore) {
		return nonCachedContentDaoService.createPropertyDefinitionCore(repositoryId, propertyDefinitionCore);
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		return nonCachedContentDaoService.createPropertyDefinitionDetail(repositoryId, propertyDefinitionDetail);
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		NemakiPropertyDefinitionDetail np = nonCachedContentDaoService.updatePropertyDefinitionDetail(repositoryId,
				propertyDefinitionDetail);
		NemakiCache typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		return np;
	}

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	@Override
	public NodeBase getNodeBase(String repositoryId, String objectId) {
		return nonCachedContentDaoService.getNodeBase(repositoryId, objectId);
	}

	/**
	 * get Document/Folder(not Attachment) Return Document/Folder class FIXME
	 * devide this method into getDcoument & getFolder
	 */
	@Override
	public Content getContent(String repositoryId, String objectId) {
		log.info(MessageFormat.format("cache.ContentDaoService#getContent START: Repo={0}, Id={1}", repositoryId, objectId));
		if (objectId == null){
			log.warn("DAO getContent param ObjcetId is null!");
			return null;
		}

		NemakiCache<Content> contentCache = nemakiCachePool.get(repositoryId).getContentCache();
		Content v = contentCache.get(objectId);

		if (v != null) {
			return v;
		}

		Content content = nonCachedContentDaoService.getContent(repositoryId, objectId);

		if (content == null) {
			return null;
		} else {
			contentCache.put(new Element(objectId, content));
		}

		log.info(MessageFormat.format("cache.ContentDaoService#getContent END: Repo={0}, Id={1}", repositoryId, objectId));

		return content;
	}

	@Override
	public boolean existContent(String repositoryId, String objectTypeId) {
		return nonCachedContentDaoService.existContent(repositoryId, objectTypeId);
	}

	@Override
	public Document getDocument(String repositoryId, String objectId) {
		Document doc = null;
		Content c = this.getContent(repositoryId, objectId);
		if (c != null) {
			try {
				doc = (Document) c;
			} catch (ClassCastException e) {
				log.error("Content type is not document : " + c.getObjectType());
			}
		}
		return doc;
	}

	@Override
	public List<Document> getCheckedOutDocuments(String repositoryId, String parentFolderId) {
		return nonCachedContentDaoService.getCheckedOutDocuments(repositoryId, parentFolderId);
	}

	@Override
	public VersionSeries getVersionSeries(String repositoryId, String nodeId) {
		NemakiCache<VersionSeries> versionSeriesCache = nemakiCachePool.get(repositoryId).getVersionSeriesCache();
		VersionSeries v = versionSeriesCache.get(nodeId);

		if (v != null) {
			return (VersionSeries) v;
		}
		VersionSeries vs = nonCachedContentDaoService.getVersionSeries(repositoryId, nodeId);
		if (vs == null) {
			return null;
		} else {
			versionSeriesCache.put(new Element(nodeId, vs));
			return vs;
		}
	}

	@Override
	public List<Document> getAllVersions(String repositoryId, String versionSeriesId) {
		return nonCachedContentDaoService.getAllVersions(repositoryId, versionSeriesId);
	}

	@Override
	public Document getDocumentOfLatestVersion(String repositoryId, String versionSeriesId) {
		return nonCachedContentDaoService.getDocumentOfLatestVersion(repositoryId, versionSeriesId);
	}

	@Override
	public Document getDocumentOfLatestMajorVersion(String repositoryId, String versionSeriesId) {
		return nonCachedContentDaoService.getDocumentOfLatestMajorVersion(repositoryId, versionSeriesId);
	}

	@Override
	public Folder getFolder(String repositoryId, String objectId) {
		Folder folder = null;
		Content c = this.getContent(repositoryId, objectId);
		if (c != null) {
			try {
				folder = (Folder) c;
			} catch (ClassCastException e) {
				log.warn("Content type is not folder : " + c.getObjectType());
				return null;
			}
		}
		return folder;
	}

	@Override
	public Folder getFolderByPath(String repositoryId, String path) {
		return nonCachedContentDaoService.getFolderByPath(repositoryId, path);
	}

	@Override
	public List<Content> getChildren(String repositoryId, String parentId) {
		if(nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled()){
			List<Content> result = new ArrayList<Content>();
			Tree tree = getOrCreateTreeCache(repositoryId, parentId);
			Set<String> children = tree.getChildren();
			for(String childId : children){
				Content content = getContent(repositoryId, childId);
				if(content != null){
					result.add(content);
				}
			}
			return result;
		}else{
			return nonCachedContentDaoService.getChildren(repositoryId, parentId);
		}
	}

	@Override
	public Content getChildByName(String repositoryId, String parentId, String name) {
		return nonCachedContentDaoService.getChildByName(repositoryId, parentId, name);
	}

	@Override
	public List<String> getChildrenNames(String repositoryId, String parentId) {
		return nonCachedContentDaoService.getChildrenNames(repositoryId, parentId);
	}

	@Override
	public Relationship getRelationship(String repositoryId, String objectId) {
		Relationship rel = null;
		Content c = this.getContent(repositoryId, objectId);
		if (c != null) {
			try {
				rel = (Relationship) c;
			} catch (ClassCastException e) {
				log.error("Content type is not relationship : " + c.getObjectType());
			}
		}
		return rel;
	}

	@Override
	public List<Relationship> getRelationshipsBySource(String repositoryId, String sourceId) {
		return nonCachedContentDaoService.getRelationshipsBySource(repositoryId, sourceId);
	}

	@Override
	public List<Relationship> getRelationshipsByTarget(String repositoryId, String targetId) {
		return nonCachedContentDaoService.getRelationshipsByTarget(repositoryId, targetId);
	}

	@Override
	public Policy getPolicy(String repositoryId, String objectId) {
		return nonCachedContentDaoService.getPolicy(repositoryId, objectId);
	}

	@Override
	public List<Policy> getAppliedPolicies(String repositoryId, String objectId) {
		return nonCachedContentDaoService.getAppliedPolicies(repositoryId, objectId);
	}

	@Override
	public Item getItem(String repositoryId, String objectId) {
		Item item = nonCachedContentDaoService.getItem(repositoryId, objectId);
		return item;
	}

	@Override
	public UserItem getUserItem(String repositoryId, String objectId) {
		UserItem item = null;
		Content c = this.getContent(repositoryId, objectId);
		if (c != null) {
			try{
				item = (UserItem) c;
			}catch(ClassCastException ex){
				log.warn("Content type is not UserItem : " + c.getObjectType());
			}
		}
		return item;
	}

	@Override
	public UserItem getUserItemById(String repositoryId, String userId) {
		NemakiCache<UserItem> userItemCache = nemakiCachePool.get(repositoryId).getUserItemCache();
		UserItem v = userItemCache.get(userId);
		if (v != null) {
			return  v;
		}

		UserItem userItem = nonCachedContentDaoService.getUserItemById(repositoryId, userId);

		if (userItem == null) {
			return null;
		} else {
			userItemCache.put(userId, userItem);
		}

		return userItem;
	}

	@Override
	public List<UserItem> getUserItems(String repositoryId) {
		return nonCachedContentDaoService.getUserItems(repositoryId);
	}

	@Override
	public GroupItem getGroupItem(String repositoryId, String objectId) {
		GroupItem item = null;
		Content c = this.getContent(repositoryId, objectId);
		if (c != null) {
			try{
				item = (GroupItem) c;
			}catch(ClassCastException ex){
				log.warn("Content type is not GroupItem : " + c.getObjectType());
			}
		}
		return item;
	}

	@Override
	public GroupItem getGroupItemById(String repositoryId, String groupId) {
		NemakiCache<GroupItem> groupItemCache = nemakiCachePool.get(repositoryId).getGroupItemCache();
		GroupItem v = groupItemCache.get(groupId);

		if (v != null) {
			return  v;
		}

		GroupItem groupItem = nonCachedContentDaoService.getGroupItemById(repositoryId, groupId);

		if (groupItem == null) {
			return null;
		} else {
			groupItemCache.put(groupId, groupItem);
		}

		return groupItem;
	}

	@Override
	public List<GroupItem> getGroupItems(String repositoryId) {
		return nonCachedContentDaoService.getGroupItems(repositoryId);
	}

	@Override
	public List<String> getJoinedGroupByUserId(String repositoryId, String userId) {
		NemakiCache<List<String>> joinedGroupCache = nemakiCachePool.get(repositoryId).getJoinedGroupCache();
		List<String> v = joinedGroupCache.get(userId);

		if (v != null) {
			return  v;
		}

		List<String> joinedGroup = nonCachedContentDaoService.getJoinedGroupByUserId(repositoryId, userId);

		if (joinedGroup == null){
			return null;
		}else{
			joinedGroupCache.put(userId,joinedGroup);
		}

		return joinedGroup;
	}

	@Override
	public PatchHistory getPatchHistoryByName(String repositoryId, String name) {
		return nonCachedContentDaoService.getPatchHistoryByName(repositoryId, name);
	}

	@Override
	public Configuration getConfiguration(String repositoryId) {
		NemakiCache<Configuration> configCache = nemakiCachePool.get(repositoryId).getConfigCache();
		Configuration v = configCache.get("configuration");

		if (v != null) {
			try {
				return (Configuration) v;
			} catch (ClassCastException e) {
				throw e;
			}
		}

		Configuration configuration = nonCachedContentDaoService.getConfiguration(repositoryId);

		if (configuration == null) {
			return null;
		} else {
			configCache.put(new Element("configuration", configuration));
			return configuration;
		}
	}

	@Override
	public Document create(String repositoryId, Document document) {
		Document created = nonCachedContentDaoService.create(repositoryId, document);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(created.getId(), created));

		//Tree cache
		addToTreeCache(repositoryId, created);

		return created;
	}



	private Tree getOrCreateTreeCache(String repositoryId, String parentId){
		NemakiCache<Tree> treeCache = nemakiCachePool.get(repositoryId).getTreeCache();
		Tree tree = treeCache.get(parentId);
		if(tree == null){
			List<Content> list = nonCachedContentDaoService.getChildren(repositoryId, parentId);
			tree = new Tree(parentId);
			if(org.apache.commons.collections.CollectionUtils.isNotEmpty(list)){
				for(Content child : list){
					tree.add(child.getId());
				}
			}
			treeCache.put(tree.getParent(), tree);
		}

		return tree;
	}

	private void addToTreeCache(String repositoryId, Content content){
		if(!nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled()){
			//do nothing when cache disabled
			return;
		}

		Tree tree = getOrCreateTreeCache(repositoryId, content.getParentId());

		if(content instanceof Document){
			Document doc = (Document)content;
			if(doc.isPrivateWorkingCopy()){
				//do nothing
				return;
			}else{
				List<Document> versions = getAllVersions(repositoryId, doc.getVersionSeriesId());
				if(versions != null){
					Collections.sort(versions, new VersionComparator());
					for(Document version : versions){
						if(version.getId().equals(doc.getId())){
							tree.add(doc.getId());
						}else{
							tree.remove(version.getId());
						}
					}
				}
			}
		}else if(content instanceof Folder || content instanceof Item){
			tree.add(content.getId());
		}
	}

	private class VersionComparator implements Comparator<Content> {
		@Override
		public int compare(Content content0, Content content1) {
			// TODO when created time is not set
			GregorianCalendar created0 = content0.getCreated();
			GregorianCalendar created1 = content1.getCreated();

			if (created0.before(created1)) {
				return 1;
			} else if (created0.after(created1)) {
				return -1;
			} else {
				return 0;
			}
		}
	}

	/**
	 *
	 * @param repositoryId
	 * @param doc
	 * @return Previous version. If previous version does not exist, return null.
	 * @throws Exception
	 */
	private Document getPreviousVersion(String repositoryId, Document doc) throws Exception{
		String vsId = doc.getVersionSeriesId();
		List<Document> docs = getAllVersions(repositoryId, vsId);
		if(CollectionUtils.isEmpty(docs)){
			throw new Exception(String.format("Version series of document[%s] is broken!", doc.getId()));
		}else if(docs.size() <=1){
			return null;
		}else{
			Document previous = docs.get(docs.size() - 2);
			return previous;
		}
	}

	@Override
	public VersionSeries create(String repositoryId, VersionSeries versionSeries) {
		VersionSeries vs = nonCachedContentDaoService.create(repositoryId, versionSeries);
		NemakiCache<VersionSeries> versionSeriesCache = nemakiCachePool.get(repositoryId).getVersionSeriesCache();
		versionSeriesCache.put(new Element(vs.getId(), vs));
		return vs;
	}

	@Override
	public Change create(String repositoryId, Change change) {
		Change created = nonCachedContentDaoService.create(repositoryId, change);
		Change latest = nonCachedContentDaoService.getLatestChange(repositoryId);
		nemakiCachePool.get(repositoryId).getLatestChangeTokenCache().removeAll();
		nemakiCachePool.get(repositoryId).getLatestChangeTokenCache()
				.put(new Element(TOKEN_CACHE_LATEST_CHANGE_TOKEN, latest));
		return created;
	}

	@Override
	public Folder create(String repositoryId, Folder folder) {
		Folder created = nonCachedContentDaoService.create(repositoryId, folder);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(created.getId(), created));
		addToTreeCache(repositoryId, created);

		return created;
	}

	@Override
	public Relationship create(String repositoryId, Relationship relationship) {
		Relationship created = nonCachedContentDaoService.create(repositoryId, relationship);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(created.getId(), created));
		return created;
	}

	@Override
	public Policy create(String repositoryId, Policy policy) {
		Policy created = nonCachedContentDaoService.create(repositoryId, policy);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(created.getId(), created));
		return created;
	}

	@Override
	public Item create(String repositoryId, Item item) {
		Item created = nonCachedContentDaoService.create(repositoryId, item);
		nemakiCachePool.get(repositoryId).getContentCache().put(created.getId(), created);
		addToTreeCache(repositoryId, created);
		return created;
	}

	@Override
	public UserItem create(String repositoryId, UserItem userItem) {
		UserItem created = nonCachedContentDaoService.create(repositoryId, userItem);
		nemakiCachePool.get(repositoryId).getContentCache().put(created.getId(), created);
		nemakiCachePool.get(repositoryId).getUserItemCache().put(created.getUserId(), created);
		addToTreeCache(repositoryId, created);
		return created;
	}

	@Override
	public GroupItem create(String repositoryId, GroupItem groupItem) {
		GroupItem created = nonCachedContentDaoService.create(repositoryId, groupItem);
		nemakiCachePool.get(repositoryId).getContentCache().put(created.getId(), created);
		nemakiCachePool.get(repositoryId).getGroupItemCache().put(created.getGroupId(), created);
		addToTreeCache(repositoryId, created);
		return created;
	}

	@Override
	public PatchHistory create(String repositoryId, PatchHistory patchHistory) {
		PatchHistory created = nonCachedContentDaoService.create(repositoryId, patchHistory);
		return created;
	}

	@Override
	public Configuration create(String repositoryId, Configuration configuration) {
		Configuration created = nonCachedContentDaoService.create(repositoryId, configuration);
		nemakiCachePool.get(repositoryId).getConfigCache().put(new Element(created.getId(), created));
		return created;
	}

	@Override
	public NodeBase create(String repositoryId, NodeBase nodeBase) {
		return nonCachedContentDaoService.create(repositoryId, nodeBase);
	}

	@Override
	public Document update(String repositoryId, Document document) {
		Document updated = nonCachedContentDaoService.update(repositoryId, document);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(updated.getId(), updated));
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());

		return updated;
	}

	@Override
	public Document move(String repositoryId, Document document, String sourceId) {
		moveTreeCache(repositoryId, document, sourceId);
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(document.getId());
		return update(repositoryId, document);
	}

	@Override
	public VersionSeries update(String repositoryId, VersionSeries versionSeries) {
		VersionSeries updated = nonCachedContentDaoService.update(repositoryId, versionSeries);
		NemakiCache<VersionSeries> versionSeriesCache = nemakiCachePool.get(repositoryId).getVersionSeriesCache();
		versionSeriesCache.put(new Element(updated.getId(), updated));
		return updated;
	}

	@Override
	public Folder update(String repositoryId, Folder folder) {
		Folder updated = nonCachedContentDaoService.update(repositoryId, folder);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(updated.getId(), updated));
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());

		return updated;
	}

	@Override
	public Folder move(String repositoryId, Folder folder, String sourceId) {
		moveTreeCache(repositoryId, folder, sourceId);
		return update(repositoryId, folder);
	}

	private void moveTreeCache(String repositoryId, Content updated, String sourceId){
		NemakiCache<Tree> cache = nemakiCachePool.get(repositoryId).getTreeCache();
		if(!cache.isCacheEnabled()){
			//do nothing when disabled
			return;
		}

		String targetId = updated.getParentId();

		if(!sourceId.equals(targetId)){
			//Remove from source
			Tree souceTree = cache.get(sourceId);
			if(souceTree != null){
				souceTree.remove(updated.getId());
			}

			//Add to target
			addToTreeCache(repositoryId, updated);
		}
	}

	@Override
	public Relationship update(String repositoryId, Relationship relationship) {
		Relationship updated = nonCachedContentDaoService.update(repositoryId, relationship);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(updated.getId(), updated));
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());
		return updated;
	}

	@Override
	public Policy update(String repositoryId, Policy policy) {
		Policy updated = nonCachedContentDaoService.update(repositoryId, policy);
		nemakiCachePool.get(repositoryId).getContentCache().put(new Element(updated.getId(), updated));
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());
		return updated;
	}

	@Override
	public Item update(String repositoryId, Item item) {
		Item updated = nonCachedContentDaoService.update(repositoryId, item);
		nemakiCachePool.get(repositoryId).getContentCache().put(updated.getId(), updated);
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());
		return updated;
	}

	@Override
	public UserItem update(String repositoryId, UserItem userItem) {
		UserItem updated = nonCachedContentDaoService.update(repositoryId, userItem);
		nemakiCachePool.get(repositoryId).getContentCache().put(updated.getId(), updated);
		nemakiCachePool.get(repositoryId).getUserItemCache().put(updated.getUserId(), updated);
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());
		return updated;
	}

	@Override
	public GroupItem update(String repositoryId, GroupItem groupItem) {
		GroupItem updated = nonCachedContentDaoService.update(repositoryId, groupItem);
		nemakiCachePool.get(repositoryId).getContentCache().put(updated.getId(), updated);
		nemakiCachePool.get(repositoryId).getGroupItemCache().put(updated.getGroupId(), updated);
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(updated.getId());
		nemakiCachePool.get(repositoryId).getJoinedGroupCache().removeAll();
		return updated;
	}

	@Override
	public PatchHistory update(String repositoryId, PatchHistory patchHistory) {
		PatchHistory updated = nonCachedContentDaoService.update(repositoryId, patchHistory);
		return updated;
	}

	@Override
	public Configuration update(String repositoryId, Configuration configuration) {
		Configuration updated = nonCachedContentDaoService.update(repositoryId, configuration);
		nemakiCachePool.get(repositoryId).getConfigCache().put(new Element(updated.getId(), updated));
		return updated;
	}

	@Override
	public NodeBase update(String repositoryId, NodeBase nodeBase) {
		NodeBase updated = nonCachedContentDaoService.update(repositoryId, nodeBase);
		return updated;
	}

	@Override
	public void delete(String repositoryId, String objectId) {
		//Check if cache enabled
		boolean treeCacheEnabled = nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled();

		NodeBase nb = nonCachedContentDaoService.getNodeBase(repositoryId, objectId);

		if(nb == null){
			return;
		}

		//read document in advance
		Document doc = null;
		Document previous = null;
		if(nb.isDocument()){
			doc = (Document)getDocument(repositoryId, objectId);
			try {
				previous = getPreviousVersion(repositoryId, doc);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		//read tree in advance
		Tree tree = null;
		if(treeCacheEnabled && (nb.isDocument() || nb.isFolder() || nb.isItem())){
			Content _c = getContent(repositoryId, objectId);
			tree = getOrCreateTreeCache(repositoryId, _c.getParentId());
		}

		//delete user/group from cache
		if(nb.isUser()){
			UserItem item = getUserItem(repositoryId, objectId);
			nemakiCachePool.get(repositoryId).getUserItemCache().remove(item.getUserId());
		}else if(nb.isGroup()){
			GroupItem item = getGroupItem(repositoryId, objectId);
			nemakiCachePool.get(repositoryId).getGroupItemCache().remove(item.getGroupId());
		}

		// remove from database
		nonCachedContentDaoService.delete(repositoryId, objectId);

		// remove from cache
		if(doc == null){
			if (nb.isAttachment()) {
				nemakiCachePool.get(repositoryId).getAttachmentCache().remove(objectId);
			}else{
				nemakiCachePool.get(repositoryId).getContentCache().remove(objectId);
				nemakiCachePool.get(repositoryId).getObjectDataCache().remove(objectId);
				nemakiCachePool.get(repositoryId).getAclCache().remove(objectId);
				if(tree != null)tree.remove(objectId);
			}
		}else{
			//DOCUMENT case
			if(doc.isPrivateWorkingCopy()){
				//delete just pwc-related  cache
				nemakiCachePool.get(repositoryId).getAttachmentCache().remove(doc.getAttachmentNodeId());
				nemakiCachePool.get(repositoryId).getContentCache().remove(doc.getId());
				nemakiCachePool.get(repositoryId).getObjectDataCache().remove(doc.getId());
				nemakiCachePool.get(repositoryId).getAclCache().remove(doc.getId());
				nemakiCachePool.get(repositoryId).getVersionSeriesCache().remove(doc.getVersionSeriesId());
			}else{
				nemakiCachePool.get(repositoryId).getAttachmentCache().remove(doc.getAttachmentNodeId());
				nemakiCachePool.get(repositoryId).getContentCache().remove(doc.getId());
				nemakiCachePool.get(repositoryId).getObjectDataCache().remove(doc.getId());
				nemakiCachePool.get(repositoryId).getAclCache().remove(doc.getId());
				if(tree != null)tree.remove(doc.getId());
				nemakiCachePool.get(repositoryId).getVersionSeriesCache().remove(doc.getVersionSeriesId());

			}
		}
	}

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	@Override
	public AttachmentNode getAttachment(String repositoryId, String attachmentId) {
		NemakiCache<AttachmentNode> attachmentCache = nemakiCachePool.get(repositoryId).getAttachmentCache();
		AttachmentNode v = attachmentCache.get(attachmentId);

		AttachmentNode an = null;
		if (v != null) {
			an = v;

		} else {
			an = nonCachedContentDaoService.getAttachment(repositoryId, attachmentId);
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
	public void setStream(String repositoryId, AttachmentNode attachmentNode) {
		nonCachedContentDaoService.setStream(repositoryId, attachmentNode);

		// throw new
		// UnsupportedOperationException(Thread.currentThread().getStackTrace()[0].getMethodName()
		// + ":this method is only for non-cahced service.");
	}

	@Override
	public Rendition getRendition(String repositoryId, String objectId) {
		return nonCachedContentDaoService.getRendition(repositoryId, objectId);
	}

	@Override
	public String createRendition(String repositoryId, Rendition rendition, ContentStream contentStream) {
		return nonCachedContentDaoService.createRendition(repositoryId, rendition, contentStream);
	}

	@Override
	public String createAttachment(String repositoryId, AttachmentNode attachment, ContentStream cs) {
		return nonCachedContentDaoService.createAttachment(repositoryId, attachment, cs);
	}

	@Override
	public void updateAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		NemakiCache<AttachmentNode> attachmentCache = nemakiCachePool.get(repositoryId).getAttachmentCache();
		AttachmentNode v = attachmentCache.get(attachment.getId());
		if (v != null) {
			attachmentCache.remove(attachment.getId());
		}
		nonCachedContentDaoService.updateAttachment(repositoryId, attachment, contentStream);
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Change events
	// //////////////////////////////////////////////////////////////////////////////
	@Override
	public Change getChangeEvent(String repositoryId, String changeTokenId) {
		return nonCachedContentDaoService.getChangeEvent(repositoryId, changeTokenId);
	}

	@Override
	public Change getLatestChange(String repositoryId) {
		Change change = null;

		Change v = nemakiCachePool.get(repositoryId).getLatestChangeTokenCache().get(TOKEN_CACHE_LATEST_CHANGE_TOKEN);
		if (v != null) {
			change = v;
		}

		if (change != null) {
			return change;
		} else {
			change = nonCachedContentDaoService.getLatestChange(repositoryId);
			if (change != null) {
				nemakiCachePool.get(repositoryId).getLatestChangeTokenCache()
						.put(new Element(TOKEN_CACHE_LATEST_CHANGE_TOKEN, change));
			}
			return change;
		}
	}

	@Override
	public List<Change> getLatestChanges(String repositoryId, String startToken, int maxItems) {
		return nonCachedContentDaoService.getLatestChanges(repositoryId, startToken, maxItems);
	}

	@Override
	public List<Change> getObjectChanges(String repositoryId, String objectId) {
		return nonCachedContentDaoService.getObjectChanges(repositoryId, objectId);
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Archive
	// //////////////////////////////////////////////////////////////////////////////
	@Override
	public Archive getArchive(String repositoryId, String archiveId) {
		return nonCachedContentDaoService.getArchive(repositoryId, archiveId);
	}

	@Override
	public Archive getArchiveByOriginalId(String repositoryId, String originalId) {
		return nonCachedContentDaoService.getArchiveByOriginalId(repositoryId, originalId);
	}

	@Override
	public Archive getAttachmentArchive(String repositoryId, Archive archive) {
		return nonCachedContentDaoService.getAttachmentArchive(repositoryId, archive);
	}

	@Override
	public List<Archive> getChildArchives(String repositoryId, Archive archive) {
		return nonCachedContentDaoService.getChildArchives(repositoryId, archive);
	}

	@Override
	public List<Archive> getArchivesOfVersionSeries(String repositoryId, String versionSeriesId) {
		return nonCachedContentDaoService.getArchivesOfVersionSeries(repositoryId, versionSeriesId);
	}

	@Override
	public List<Archive> getAllArchives(String repositoryId) {
		return nonCachedContentDaoService.getAllArchives(repositoryId);
	}

	@Override
	public List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc) {
		return nonCachedContentDaoService.getArchives(repositoryId, skip, limit, desc);
	}

	@Override
	public Archive createArchive(String repositoryId, Archive archive, Boolean deletedWithParent) {
		return nonCachedContentDaoService.createArchive(repositoryId, archive, deletedWithParent);
	}

	@Override
	public Archive createAttachmentArchive(String repositoryId, Archive archive) {
		return nonCachedContentDaoService.createAttachmentArchive(repositoryId, archive);
	}

	@Override
	public void deleteArchive(String repositoryId, String archiveId) {
		nonCachedContentDaoService.deleteArchive(repositoryId, archiveId);
	}

	@Override
	public void deleteDocumentArchive(String repositoryId, String archiveId) {
		nonCachedContentDaoService.deleteDocumentArchive(repositoryId, archiveId);
	}

	@Override
	public void restoreContent(String repositoryId, Archive archive) {
		nonCachedContentDaoService.restoreContent(repositoryId, archive);
	}

	@Override
	public void restoreAttachment(String repositoryId, Archive archive) {
		nonCachedContentDaoService.restoreAttachment(repositoryId, archive);
	}

	@Override
	public void restoreDocumentWithArchive(String repositoryId, Archive archive) {
		final String originalId = archive.getOriginalId();
		nonCachedContentDaoService.restoreDocumentWithArchive(repositoryId, archive);

		Content restored = getContent(repositoryId, originalId);
		if(restored == null){
			//TODO log
		}else{
			//TODO rebuild cache into getChildren()
			addToTreeCache(repositoryId, restored);
		}
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Cache management
	// //////////////////////////////////////////////////////////////////////////////
	public void refreshCmisObjectData(String repositoryId, String objectId){
		nemakiCachePool.get(repositoryId).getObjectDataCache().remove(objectId);
	}


	// //////////////////////////////////////////////////////////////////////////////
	// Spring
	// //////////////////////////////////////////////////////////////////////////////
	public void setNonCachedContentDaoService(ContentDaoService nonCachedContentDaoService) {
		this.nonCachedContentDaoService = nonCachedContentDaoService;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

}