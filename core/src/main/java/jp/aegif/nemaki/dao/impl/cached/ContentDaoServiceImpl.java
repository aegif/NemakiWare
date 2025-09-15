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
// @Component annotation removed to prevent conflicts with XML bean definition in daoContext.xml
public class ContentDaoServiceImpl implements ContentDaoService {
	private static final Log log = LogFactory.getLog(ContentDaoServiceImpl.class);

	private ContentDaoService nonCachedContentDaoService;
	private NemakiCachePool nemakiCachePool;
	private jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager;

	private final String TOKEN_CACHE_LATEST_CHANGE_TOKEN = "lc";

	public ContentDaoServiceImpl() {

	}

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
		log.debug("Getting type definitions for repository: " + repositoryId);
		
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		List<NemakiTypeDefinition> v = typeCache.get("typedefs");

		if (v != null) {
			log.debug("Found cached typedefs: " + v.size() + " types");
			return v;
		}

		log.debug("No cached typedefs, calling nonCachedContentDaoService");
		List<NemakiTypeDefinition> result = nonCachedContentDaoService.getTypeDefinitions(repositoryId);

		if (CollectionUtils.isEmpty(result)) {
			return null;
		} else {
			log.debug("Caching " + result.size() + " types for repository: " + repositoryId);
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
		log.debug("createTypeDefinition called for repositoryId: " + repositoryId);
		
		NemakiTypeDefinition nt = nonCachedContentDaoService.createTypeDefinition(repositoryId, typeDefinition);
		
		// Invalidate both type and property definition caches
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		// Clear property definition cache since type changes affect property definitions
		nemakiCachePool.get(repositoryId).getPropertyDefinitionCache().removeAll();
		
		if (typeManager != null) {
			log.debug("Refreshing TypeManager cache after type creation");
			typeManager.refreshTypes();
			log.debug("TypeManager cache refresh completed");
		}
		
		return nt;
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		NemakiTypeDefinition nt = nonCachedContentDaoService.updateTypeDefinition(repositoryId, typeDefinition);
		// Invalidate both type and property definition caches
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		// Clear property definition cache since type changes affect property definitions
		nemakiCachePool.get(repositoryId).getPropertyDefinitionCache().removeAll();
		return nt;
	}

	@Override
	public void deleteTypeDefinition(String repositoryId, String nodeId) {
		nonCachedContentDaoService.deleteTypeDefinition(repositoryId, nodeId);

		// Invalidate both type and property definition caches
		NemakiCache<List<NemakiTypeDefinition>> typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		// Clear property definition cache since type deletion affects property definitions
		nemakiCachePool.get(repositoryId).getPropertyDefinitionCache().removeAll();
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId) {
		// Check cache first
		String cacheKey = "prop_def_cores_all";
		NemakiCache cache = nemakiCachePool.get(repositoryId).getPropertyDefinitionCache();
		List<NemakiPropertyDefinitionCore> cached = (List<NemakiPropertyDefinitionCore>) cache.get(cacheKey);
		
		if (cached != null) {
			return cached;
		}
		
		// Load from database and cache
		List<NemakiPropertyDefinitionCore> result = nonCachedContentDaoService.getPropertyDefinitionCores(repositoryId);
		if (result != null) {
			cache.put(cacheKey, result);
		}
		
		return result;
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String nodeId) {
		// Check cache first
		String cacheKey = "prop_def_core_" + nodeId;
		NemakiCache<NemakiPropertyDefinitionCore> cache = nemakiCachePool.get(repositoryId).getPropertyDefinitionCache();
		NemakiPropertyDefinitionCore cached = cache.get(cacheKey);
		
		if (cached != null) {
			return cached;
		}
		
		// Load from database and cache
		NemakiPropertyDefinitionCore result = nonCachedContentDaoService.getPropertyDefinitionCore(repositoryId, nodeId);
		if (result != null) {
			cache.put(cacheKey, result);
		}
		
		return result;
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId) {
		// Check cache first
		String cacheKey = "prop_def_" + propertyId;
		NemakiCache<NemakiPropertyDefinitionCore> cache = nemakiCachePool.get(repositoryId).getPropertyDefinitionCache();
		NemakiPropertyDefinitionCore cached = cache.get(cacheKey);
		
		if (cached != null) {
			return cached;
		}
		
		// Load from database and cache
		NemakiPropertyDefinitionCore propDef = nonCachedContentDaoService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
		if (propDef != null) {
			cache.put(cacheKey, propDef);
		}
		
		return propDef;
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
		NemakiPropertyDefinitionCore result = nonCachedContentDaoService.createPropertyDefinitionCore(repositoryId, propertyDefinitionCore);
		// Clear property definition cache since new property definition was created
		nemakiCachePool.get(repositoryId).getPropertyDefinitionCache().removeAll();
		return result;
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		NemakiPropertyDefinitionDetail result = nonCachedContentDaoService.createPropertyDefinitionDetail(repositoryId, propertyDefinitionDetail);
		// Clear property definition cache since new property definition was created
		nemakiCachePool.get(repositoryId).getPropertyDefinitionCache().removeAll();
		return result;
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		NemakiPropertyDefinitionDetail np = nonCachedContentDaoService.updatePropertyDefinitionDetail(repositoryId,
				propertyDefinitionDetail);
		// Invalidate both type and property definition caches
		NemakiCache typeCache = nemakiCachePool.get(repositoryId).getTypeCache();
		typeCache.remove("typedefs");
		// Clear property definition cache since property definition changed
		nemakiCachePool.get(repositoryId).getPropertyDefinitionCache().removeAll();
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
		log.info("CRITICAL TRACE: Line 2 reached in cache.getContent");
		if (objectId == null){
			log.warn("DAO getContent param ObjcetId is null!");
			return null;
		}
		log.info("CRITICAL TRACE: Line 5 reached in cache.getContent - objectId null check passed");

		// Critical dependency checks for Cloudant migration debugging
		if (nemakiCachePool == null) {
			log.error("CRITICAL: nemakiCachePool is NULL in cached ContentDaoService");
			return null;
		}
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}

		log.info("cache.getContent: Dependencies OK - nemakiCachePool and nonCachedContentDaoService are not null");

		try {
			NemakiCache<Content> contentCache = nemakiCachePool.get(repositoryId).getContentCache();
			Content v = contentCache.get(objectId);

			if (v != null) {
				log.info("CACHE HIT: Found content in cache for " + objectId + ". Type: " + v.getClass().getSimpleName() + ", ObjectType: " + v.getObjectType() + ", isFolder: " + v.isFolder());
				return v;
			}

			log.info("CACHE MISS: Calling nonCachedContentDaoService.getContent for " + objectId);
			Content content = nonCachedContentDaoService.getContent(repositoryId, objectId);

			if (content == null) {
				log.warn("nonCachedContentDaoService returned NULL for " + objectId);
				return null;
			} else {
				log.info("nonCachedContentDaoService returned: " + content.getClass().getSimpleName() + ", ObjectType: " + content.getObjectType() + ", isFolder: " + content.isFolder());
				contentCache.put(new Element(objectId, content));
			}

			log.info(MessageFormat.format("cache.ContentDaoService#getContent END: Repo={0}, Id={1}, returning: {2}", repositoryId, objectId, (content != null ? content.getClass().getSimpleName() + "[objectType=" + content.getObjectType() + "]" : "NULL")));

			return content;
		} catch (Exception e) {
			log.error("Exception in cache.getContent for " + objectId + ": " + e.getMessage(), e);
			return null;
		}
	}

	@Override
	public Content getContentFresh(String repositoryId, String objectId) {
		// Bypass cache and get fresh content directly from database
		// This is critical for revision-sensitive operations like writeChangeEvent
		log.info("CACHE BYPASS: Getting fresh content from database for " + objectId);
		
		if (objectId == null) {
			log.warn("DAO getContentFresh param ObjectId is null!");
			return null;
		}
		
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}
		
		try {
			Content freshContent = nonCachedContentDaoService.getContent(repositoryId, objectId);
			if (freshContent != null) {
				log.info("CACHE BYPASS SUCCESS: Retrieved fresh content for " + objectId + 
					", revision=" + freshContent.getRevision() + ", type=" + freshContent.getClass().getSimpleName());
			} else {
				log.warn("CACHE BYPASS: No content found for " + objectId);
			}
			return freshContent;
		} catch (Exception e) {
			log.error("Error in getContentFresh", e);
			return null;
		}
	}

	@Override
	public Document getDocumentFresh(String repositoryId, String objectId) {
		log.info("CACHE BYPASS: Getting fresh document from database for " + objectId);
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}
		try {
			Document freshDocument = nonCachedContentDaoService.getDocument(repositoryId, objectId);
			if (freshDocument != null) {
				log.info("CACHE BYPASS SUCCESS: Retrieved fresh document for " + objectId + 
					", revision=" + freshDocument.getRevision());
			}
			return freshDocument;
		} catch (Exception e) {
			log.error("Error in getDocumentFresh", e);
			return null;
		}
	}

	@Override
	public Folder getFolderFresh(String repositoryId, String objectId) {
		log.info("CACHE BYPASS: Getting fresh folder from database for " + objectId);
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}
		try {
			Folder freshFolder = nonCachedContentDaoService.getFolder(repositoryId, objectId);
			if (freshFolder != null) {
				log.info("CACHE BYPASS SUCCESS: Retrieved fresh folder for " + objectId + 
					", revision=" + freshFolder.getRevision());
			}
			return freshFolder;
		} catch (Exception e) {
			log.error("Error in getFolderFresh", e);
			return null;
		}
	}

	@Override
	public Relationship getRelationshipFresh(String repositoryId, String objectId) {
		log.info("CACHE BYPASS: Getting fresh relationship from database for " + objectId);
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}
		try {
			Relationship freshRelationship = nonCachedContentDaoService.getRelationship(repositoryId, objectId);
			if (freshRelationship != null) {
				log.info("CACHE BYPASS SUCCESS: Retrieved fresh relationship for " + objectId + 
					", revision=" + freshRelationship.getRevision());
			}
			return freshRelationship;
		} catch (Exception e) {
			log.error("Error in getRelationshipFresh", e);
			return null;
		}
	}

	@Override
	public Policy getPolicyFresh(String repositoryId, String objectId) {
		log.info("CACHE BYPASS: Getting fresh policy from database for " + objectId);
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}
		try {
			Policy freshPolicy = nonCachedContentDaoService.getPolicy(repositoryId, objectId);
			if (freshPolicy != null) {
				log.info("CACHE BYPASS SUCCESS: Retrieved fresh policy for " + objectId + 
					", revision=" + freshPolicy.getRevision());
			}
			return freshPolicy;
		} catch (Exception e) {
			log.error("Error in getPolicyFresh", e);
			return null;
		}
	}

	@Override
	public Item getItemFresh(String repositoryId, String objectId) {
		log.info("CACHE BYPASS: Getting fresh item from database for " + objectId);
		if (nonCachedContentDaoService == null) {
			log.error("CRITICAL: nonCachedContentDaoService is NULL in cached ContentDaoService");
			return null;
		}
		try {
			Item freshItem = nonCachedContentDaoService.getItem(repositoryId, objectId);
			if (freshItem != null) {
				log.info("CACHE BYPASS SUCCESS: Retrieved fresh item for " + objectId + 
					", revision=" + freshItem.getRevision());
			}
			return freshItem;
		} catch (Exception e) {
			log.error("Error in getItemFresh", e);
			return null;
		}
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
		// CRITICAL: Enhanced implementation with type hierarchy support for Cloudant migration
		log.info("cache.getFolder START: Repo=" + repositoryId + ", Id=" + objectId);
		Content content = null;
		try {
			content = this.getContent(repositoryId, objectId);
			log.info("cache.getFolder: getContent completed. Result is " + (content != null ? "NOT NULL" : "NULL"));
		} catch (Exception e) {
			log.error("cache.getFolder: Exception in getContent for " + objectId + ": " + e.getMessage(), e);
			return null;
		}
		
		if (content == null) {
			log.warn("cache.getFolder: getContent returned NULL for " + objectId);
			return null;
		}
		
		log.info("cache.getFolder: Got content of type: " + content.getClass().getSimpleName() + ", ObjectType: " + content.getObjectType() + ", isFolder: " + content.isFolder());
		
		// Check if content is already a Folder instance
		if (content instanceof Folder) {
			log.info("cache.getFolder: Content is already Folder instance");
			return (Folder) content;
		}
		
		// Check if content has a folder-type objectType (supporting type hierarchy)
		String objectType = content.getObjectType();
		if (objectType != null && isFolderType(repositoryId, objectType)) {
			log.info("cache.getFolder: ObjectType " + objectType + " is folder type");
			// Convert content to folder if it has folder-type but is not a Folder instance
			if (content.isFolder()) {
				log.info("cache.getFolder: Converting Content to Folder");
				// Create a Folder instance from the content
				Folder folder = new Folder(content);
				return folder;
			} else {
				log.warn("cache.getFolder: ObjectType is folder type but content.isFolder() returned false");
			}
		} else {
			log.warn("cache.getFolder: ObjectType " + objectType + " is NOT folder type");
		}
		
		log.warn("Content " + objectId + " exists but is not a folder type. ObjectType: " + objectType);
		return null;
	}
	
	/**
	 * Check if the given objectType is a folder type (cmis:folder or inherits from cmis:folder)
	 */
	private boolean isFolderType(String repositoryId, String objectType) {
		if (objectType == null) return false;
		
		// Direct match for standard folder types
		if ("cmis:folder".equals(objectType) || "folder".equals(objectType)) {
			return true;
		}
		
		// Check for custom folder types (nemaki:folder, etc.)
		if (objectType.contains("folder")) {
			return true;
		}
		
		// TODO: Add proper type hierarchy checking using TypeManager
		// For now, use simple pattern matching as a temporary solution
		return false;
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

		if (log.isDebugEnabled()) {
			log.debug("getUserItemById for userId: " + userId + " in repository: " + repositoryId);
		}
		UserItem userItem = nonCachedContentDaoService.getUserItemById(repositoryId, userId);
		if (log.isDebugEnabled()) {
			log.debug("got userItem result: " + (userItem != null ? "NOT NULL" : "NULL"));
		}

		if (userItem == null) {
			return null;
		} else {
			userItemCache.put(userId, userItem);
		}

		return userItem;
	}

	@Override
	public List<UserItem> getUserItems(String repositoryId) {
		log.info("=== CACHED LAYER: getUserItems called for repository: " + repositoryId + " ===");
		List<UserItem> result = nonCachedContentDaoService.getUserItems(repositoryId);
		log.info("=== CACHED LAYER: getUserItems returned " + result.size() + " users ===");
		return result;
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
		
		// CRITICAL FIX: Handle case where created document has null ID gracefully
		if (created != null && created.getId() != null) {
			nemakiCachePool.get(repositoryId).getContentCache().put(new Element(created.getId(), created));
			//Tree cache
			addToTreeCache(repositoryId, created);
			log.debug("Document created and cached successfully with ID: " + created.getId());
		} else {
			log.warn("Document created but has null ID - skipping cache operations. This may indicate a problem with document creation process.");
			// Don't throw exception - allow operation to continue
			// The document creation itself may have succeeded even if ID is not immediately available
		}

		return created;
	}



	private Tree getOrCreateTreeCache(String repositoryId, String parentId){
		NemakiCache<Tree> treeCache = nemakiCachePool.get(repositoryId).getTreeCache();
		Tree tree = treeCache.get(parentId);
		if(tree == null){
			List<Content> list = nonCachedContentDaoService.getChildren(repositoryId, parentId);
			tree = new Tree(parentId);
			if(org.apache.commons.collections4.CollectionUtils.isNotEmpty(list)){
				for(Content child : list){
					tree.add(child.getId());
				}
			}
			treeCache.put(tree.getParent(), tree);
		}

		return tree;
	}

	private void addToTreeCache(String repositoryId, Content content){
		log.debug("DEBUG: addToTreeCache called for content: " + (content != null ? content.getClass().getSimpleName() : "null"));
		
		if(!nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled()){
			//do nothing when cache disabled
			log.debug("DEBUG: Tree cache disabled, skipping");
			return;
		}

		// CRITICAL FIX: Check if content ID is null before proceeding
		if (content == null || content.getId() == null) {
			log.debug("DEBUG: Cannot add content to tree cache: content or ID is null - content: " + (content != null ? "not null" : "null") + ", ID: " + (content != null ? content.getId() : "N/A"));
			return;
		}

		log.debug("DEBUG: Content has valid ID: " + content.getId() + ", proceeding with tree cache");

		Tree tree = getOrCreateTreeCache(repositoryId, content.getParentId());

		if(content instanceof Document){
			Document doc = (Document)content;
			if(doc.isPrivateWorkingCopy()){
				//do nothing
				return;
			}else{
				// CRITICAL FIX: Skip getAllVersions if doc.getId() is null to prevent NullPointerException
				if (doc.getId() != null) {
					log.debug("DEBUG: Calling getAllVersions for versionSeriesId: " + doc.getVersionSeriesId());
					List<Document> versions = getAllVersions(repositoryId, doc.getVersionSeriesId());
					log.debug("DEBUG: getAllVersions returned: " + (versions != null ? versions.size() + " versions" : "null"));
					
					if(versions != null){
						Collections.sort(versions, new VersionComparator());
						for(Document version : versions){
							log.debug("DEBUG: Processing version with ID: " + (version != null ? version.getId() : "null"));
							// CRITICAL FIX: Check if version ID is null before comparison
							if(version.getId() != null && version.getId().equals(doc.getId())){
								tree.add(doc.getId());
								log.debug("DEBUG: Added doc ID to tree: " + doc.getId());
							}else if(version.getId() != null){
								tree.remove(version.getId());
								log.debug("DEBUG: Removed version ID from tree: " + version.getId());
							} else {
								log.debug("DEBUG: Version has null ID, skipping");
							}
						}
					}
				} else {
					log.debug("DEBUG: Document ID is null, skipping tree cache update for version series: " + doc.getVersionSeriesId());
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
		// COMPREHENSIVE REVISION MANAGEMENT: Cache layer handles revision transparently
		// The underlying DAO layer will fetch current revision as needed for Content objects
		log.debug("CACHE LAYER: Updating document " + document.getId() + " (revision will be managed by DAO layer)");
		
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
		// COMPREHENSIVE REVISION MANAGEMENT: Cache layer handles revision transparently
		// The underlying DAO layer will fetch current revision as needed for Content objects
		log.debug("CACHE LAYER: Updating folder " + folder.getId() + " (revision will be managed by DAO layer)");
		
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

		// CRITICAL FIX: Remove from cache BEFORE database deletion
		// This prevents read-through cache population during deletion process
		
		//delete user/group from cache
		if(nb.isUser()){
			UserItem item = getUserItem(repositoryId, objectId);
			nemakiCachePool.get(repositoryId).getUserItemCache().remove(item.getUserId());
		}else if(nb.isGroup()){
			GroupItem item = getGroupItem(repositoryId, objectId);
			nemakiCachePool.get(repositoryId).getGroupItemCache().remove(item.getGroupId());
		}

		// remove from cache FIRST
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
			//DOCUMENT case - remove from cache first
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

		// remove from database AFTER cache removal
		nonCachedContentDaoService.delete(repositoryId, objectId);
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

	@Override
	public Long getAttachmentActualSize(String repositoryId, String attachmentId) {
		// Delegate to non-cached implementation since this is metadata retrieval
		return nonCachedContentDaoService.getAttachmentActualSize(repositoryId, attachmentId);
	}

}
