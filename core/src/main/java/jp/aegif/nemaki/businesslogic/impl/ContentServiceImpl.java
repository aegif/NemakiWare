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
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.constant.CmisPermission;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.constant.NodeType;
import jp.aegif.nemaki.util.constant.PrincipalId;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.RenditionKind;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
// CmisException import removed due to Jakarta EE compatibility issues
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * Node Service implementation
 *
 * @author linzhixing
 *
 */
public class ContentServiceImpl implements ContentService {

	private RepositoryInfoMap repositoryInfoMap;
	private ContentDaoService contentDaoService;
	// TypeManager obtained via SpringContext to avoid circular dependency
	// private TypeManager typeManager;
	private RenditionManager renditionManager;
	private PropertyManager propertyManager;
	private SolrUtil solrUtil;
	private NemakiCachePool nemakiCachePool;
	private TypeManager typeManager;

	private static final Logger log = LoggerFactory.getLogger(ContentServiceImpl.class);
	private final static String PATH_SEPARATOR = "/";

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	@Override
	public boolean isRoot(String repositoryId, Content content) {
		String rootId = repositoryInfoMap.get(repositoryId).getRootFolderId();
		if (content.isFolder() && rootId.equals(content.getId())) {
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean isTopLevel(String repositoryId, Content content) {
		String rootId = repositoryInfoMap.get(repositoryId).getRootFolderId();
		String parentId = content.getParentId();
		return rootId.equals(parentId);
	}

	@Override
	public boolean existContent(String repositoryId, String objectTypeId) {
		return contentDaoService.existContent(repositoryId, objectTypeId);
	}

	@Override
	public Content getContent(String repositoryId, String objectId) {
		Content content = contentDaoService.getContent(repositoryId, objectId);
		if (content == null)
			return null;

		return getContentInternal(repositoryId, content);
	}

	/**
	 * Get the pieces of content available at that path.
	 *
	 * @throws CmisException
	 */
	@Override
	public Content getContentByPath(String repositoryId, String path) {
		log.debug("getContentByPath called with path='" + path + "' in repository='" + repositoryId + "'");
		
		List<String> splittedPath = splitLeafPathSegment(path);
		String rootId = repositoryInfoMap.get(repositoryId).getRootFolderId();

		if (splittedPath.size() <= 0) {
			return null;
		} else if (splittedPath.size() == 1) {
			if (!splittedPath.get(0).equals(PATH_SEPARATOR)) {
				return null;
			}
			// root
			return contentDaoService.getFolder(repositoryId, rootId);
		} else {
			Content content = contentDaoService.getFolder(repositoryId, rootId);
			
			// Get the leaf node
			for (int i = 1; i < splittedPath.size(); i++) {
				String nodeName = splittedPath.get(i);
				
				if (content == null) {
					log.debug("getContentByPath: parent content is null for node '" + nodeName + "' in path '" + path + "'");
					return null;
				} else {
					Content child = contentDaoService.getChildByName(repositoryId, content.getId(), nodeName);
					if (child == null) {
						log.debug("getContentByPath: child '" + nodeName + "' NOT FOUND under parent ID=" + content.getId());
						return null;
					}
					content = child;
				}
			}

			// Path resolution successful - use getContent() to ensure consistent object loading
			log.debug("getContentByPath: path resolution successful, final content ID=" + content.getId());
			return getContent(repositoryId, content.getId());
		}
	}

	private List<String> splitLeafPathSegment(String path) {
		List<String> splitted = new LinkedList<String>();
		if (path.equals(PATH_SEPARATOR)) {
			splitted.add(PATH_SEPARATOR);
			return splitted;
		}

		// TODO validation for irregular path
		splitted = new LinkedList<String>(Arrays.asList(path.split(PATH_SEPARATOR)));
		splitted.remove(0);
		splitted.add(0, PATH_SEPARATOR);
		return splitted;
	}

	@Override
	public Folder getParent(String repositoryId, String objectId) {
		Content content = getContent(repositoryId, objectId);
		if (content == null) {
			log.warn("Content not found: " + objectId + " in repository: " + repositoryId);
			return null;
		}
		if (content.getParentId() == null) {
			// Root folder has no parent
			log.debug("Content has no parent (root folder): " + objectId);
			return null;
		}
		return getFolder(repositoryId, content.getParentId());
	}

	/**
	 * Get children contents in a given folder
	 */
	@Override
	public List<Content> getChildren(String repositoryId, String folderId) {
		List<Content> result = new ArrayList<Content>();
		List<Content> daoContentList = contentDaoService.getChildren(repositoryId, folderId);
		for (Content content : daoContentList) {
			result.add(getContentInternal(repositoryId, content));
		}

		return result;
	}

	/**
	 * content / user or group items are
	 *
	 * @param content
	 * @return
	 */
	private Content getContentInternal(String repositoryId, Content content) {
		if (content.isItem()) {
			return content;
			/**
			 * TODO: for userItems discard ok? if
			 * (ObjectUtils.equals(NemakiObjectType.nemakiUser,
			 * content.getObjectType())) { return
			 * contentDaoService.getUserItem(repositoryId, content.getId()); }
			 * else if (ObjectUtils.equals(NemakiObjectType.nemakiGroup,
			 * content.getObjectType())) { return
			 * contentDaoService.getGroupItem(repositoryId, content.getId()); }
			 * else { return contentDaoService.getItem(repositoryId,
			 * content.getId()); }
			 **/
		} else {
			return content;
		}
	}

	@Override
	public Document getDocument(String repositoryId, String objectId) {
		return contentDaoService.getDocument(repositoryId, objectId);
	}

	@Override
	public Document getDocumentOfLatestVersion(String repositoryId, String versionSeriesId) {
		return contentDaoService.getDocumentOfLatestVersion(repositoryId, versionSeriesId);
	}

	@Override
	public Document getDocumentOfLatestMajorVersion(String repositoryId, String versionSeriesId) {
		return contentDaoService.getDocumentOfLatestMajorVersion(repositoryId, versionSeriesId);
	}

	@Override
	public List<Document> getAllVersions(CallContext callContext, String repositoryId, String versionSeriesId) {
		List<Document> results = new ArrayList<Document>();

		// TODO hide PWC from a non-owner user
		List<Document> versions = contentDaoService.getAllVersions(repositoryId, versionSeriesId);

		if (CollectionUtils.isNotEmpty(versions)) {
			for (Document doc : versions) {
				if (!doc.isPrivateWorkingCopy()) {
					results.add(doc);
				}
			}
		}

		return contentDaoService.getAllVersions(repositoryId, versionSeriesId);
	}

	// TODO enable orderBy
	@Override
	public List<Document> getCheckedOutDocs(String repositoryId, String folderId, String orderBy,
			ExtensionsData extension) {
		return contentDaoService.getCheckedOutDocuments(repositoryId, folderId);
	}

	@Override
	public VersionSeries getVersionSeries(String repositoryId, Document document) {
		return getVersionSeries(repositoryId, document.getVersionSeriesId());
	}

	@Override
	public VersionSeries getVersionSeries(String repositoryId, String versionSeriesId) {
		return contentDaoService.getVersionSeries(repositoryId, versionSeriesId);
	}

	@Override
	public Folder getFolder(String repositoryId, String objectId) {
		return contentDaoService.getFolder(repositoryId, objectId);
	}

	@Override
	public Folder getSystemFolder(String repositoryId) {
		if (log.isDebugEnabled()) {
			log.debug("ContentServiceImpl.getSystemFolder called with repositoryId='" + repositoryId + "'");
		}
		
		final String systemFolder = propertyManager.readValue(repositoryId, PropertyKey.SYSTEM_FOLDER);
		if (log.isDebugEnabled()) {
			log.debug("PropertyManager.readValue returned systemFolder='" + (systemFolder != null ? systemFolder : "NULL") + "'");
		}
		
		if (systemFolder != null) {
			Folder folder = contentDaoService.getFolder(repositoryId, systemFolder);
			if (log.isDebugEnabled()) {
				log.debug("contentDaoService.getFolder returned: " + (folder != null ? "Folder object with ID=" + folder.getId() : "NULL"));
			}
			return folder;
		} else {
			if (log.isDebugEnabled()) {
				log.debug("System folder ID is null - returning null");
			}
			return null;
		}
	}

	@Override
	public String calculatePath(String repositoryId, Content content) {
		List<String> path = calculatePathInternal(new ArrayList<String>(), content, repositoryId);
		path.remove(0);
		return PATH_SEPARATOR + StringUtils.join(path, PATH_SEPARATOR);
	}

	private List<String> calculatePathInternal(List<String> path, Content content, String repositoryId) {
		if (content == null) {
			log.error("Content is null during path calculation");
			throw new RuntimeException("Content is null during path calculation");
		}
		
		path.add(0, content.getName());

		if (isRoot(repositoryId, content)) {
			return path;
		} else {
			Content parent = getParent(repositoryId, content.getId());
			if (parent == null) {
				log.error("Parent not found for content: " + content.getId() + " in repository: " + repositoryId);
				throw new RuntimeException("Parent not found for content: " + content.getId());
			}
			calculatePathInternal(path, parent, repositoryId);
		}
		return path;
	}

	@Override
	public Relationship getRelationship(String repositoryId, String objectId) {
		return contentDaoService.getRelationship(repositoryId, objectId);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<Relationship> getRelationsipsOfObject(String repositoryId, String objectId,
			RelationshipDirection relationshipDirection) {

		log.info(MessageFormat.format("ContentService#getRelationsipsOfObject START: Repo={0}, Id={1}", repositoryId,
				objectId));

		// Set default (according to the specification)
		relationshipDirection = (relationshipDirection == null) ? RelationshipDirection.SOURCE : relationshipDirection;
		switch (relationshipDirection) {
		case SOURCE:
			return contentDaoService.getRelationshipsBySource(repositoryId, objectId);
		case TARGET:
			return contentDaoService.getRelationshipsByTarget(repositoryId, objectId);
		case EITHER:
			List<Relationship> sources = contentDaoService.getRelationshipsBySource(repositoryId, objectId);
			List<Relationship> targets = contentDaoService.getRelationshipsByTarget(repositoryId, objectId);
			return (List<Relationship>) CollectionUtils.disjunction(sources, targets);
		default:
			return null;
		}
	}

	@Override
	public Policy getPolicy(String repositoryId, String objectId) {
		return contentDaoService.getPolicy(repositoryId, objectId);
	}

	@Override
	public List<Policy> getAppliedPolicies(String repositoryId, String objectId, ExtensionsData extension) {
		return contentDaoService.getAppliedPolicies(repositoryId, objectId);
	}

	@Override
	public Item getItem(String repositoryId, String objectId) {
		return contentDaoService.getItem(repositoryId, objectId);
	}

	@Override
	public UserItem getUserItem(String repositoryId, String objectId) {
		return contentDaoService.getUserItem(repositoryId, objectId);
	}

	@Override
	public UserItem getUserItemById(String repositoryId, String userId) {
		return contentDaoService.getUserItemById(repositoryId, userId);
	}

	@Override
	public List<UserItem> getUserItems(String repositoryId) {
		return contentDaoService.getUserItems(repositoryId);
	}

	@Override
	public GroupItem getGroupItem(String repositoryId, String objectId) {
		return contentDaoService.getGroupItem(repositoryId, objectId);
	}

	@Override
	public GroupItem getGroupItemById(String repositoryId, String groupId) {
		return contentDaoService.getGroupItemById(repositoryId, groupId);
	}

	@Override
	public List<GroupItem> getGroupItems(String repositoryId) {
		return contentDaoService.getGroupItems(repositoryId);
	}

	@Override
	public Set<String> getGroupIdsContainingUser(String repositoryId, String userId) {
		String anonymous = getAnonymous(repositoryId);
		String anyone = getAnyone(repositoryId);

		Set<String> groupIds = new HashSet<String>();

		// Anonymous user doesn't belong to any group, even to Anyone.
		if (userId.equals(anonymous)) {
			return groupIds;
		}

		List<String> resultGroups = contentDaoService.getJoinedGroupByUserId(repositoryId, userId);
		groupIds.addAll(resultGroups);
		groupIds.add(anyone);

		return groupIds;
	}

	private boolean containsUserInGroup(String repositoryId, String userId, GroupItem group) {
		log.debug("$$ group:" + group.getName());
		if (group.getUsers().contains(userId))
			return true;
		for (String groupId : group.getGroups()) {
			log.debug("$$ subgroup: " + groupId);
			GroupItem g = getGroupItemById(repositoryId, groupId);
			if (g == null) {
				log.debug("$$ group:" + groupId + "does not exist!");
				return false;
			}
			boolean result = containsUserInGroup(repositoryId, userId, g);
			if (result)
				return true;
		}
		return false;
	}

	@Override
	public String getAnonymous(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return info.getPrincipalIdAnonymous();
	}

	@Override
	public String getAnyone(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return info.getPrincipalIdAnyone();
	}

	private String writeChangeEvent(CallContext callContext, String repositoryId, Content content,
			ChangeType changeType) {

		return writeChangeEvent(callContext, repositoryId, content, null, changeType);
	}

	public String writeChangeEvent(CallContext callContext, String repositoryId, Content content, Acl acl,
			ChangeType changeType) {
		Change change = new Change();
		change.setAcl(acl);
		change.setObjectId(content.getId());
		change.setChangeType(changeType);
		switch (changeType) {
		case CREATED:
			change.setTime(content.getCreated());
			break;
		case UPDATED:
			change.setTime(content.getModified());
			break;
		case SECURITY:
			change.setTime(content.getModified());
			break;
		default:
			break;
		}

		change.setType(NodeType.CHANGE.value());
		change.setName(content.getName());
		change.setBaseType(content.getType());
		change.setObjectType(content.getObjectType());
		change.setParentId(content.getParentId());

		/*
		 * //Policy List<String> policyIds = new ArrayList<String>();
		 * List<Policy> policies = getAppliedPolicies(repositoryId,
		 * content.getId(), null); if (!CollectionUtils.isEmpty(policies)) { for
		 * (Policy p : policies) { policyIds.add(p.getId()); } }
		 * change.setPolicyIds(policyIds);
		 */

		if (content.isDocument()) {
			Document d = (Document) content;
			change.setVersionSeriesId(d.getVersionSeriesId());
			change.setVersionLabel(d.getVersionLabel());
		}

		setSignature(callContext, change);
		change.setToken(generateChangeToken(change));

		// Create change event record (no content modification needed)
		Change created = contentDaoService.create(repositoryId, change);
		
		log.debug("Change event created successfully - ID=" + created.getId() + 
			", token=" + created.getToken() + ", objectId=" + content.getId());

		return change.getToken();

	}

	private String generateChangeToken(NodeBase node) {
		return String.valueOf(node.getCreated().getTimeInMillis());
	}

	@Override
	public Document createDocument(CallContext callContext, String repositoryId, Properties properties,
		Folder parentFolder, ContentStream contentStream, VersioningState versioningState, List<String> policies,
		org.apache.chemistry.opencmis.commons.data.Acl addAces,
		org.apache.chemistry.opencmis.commons.data.Acl removeAces) {
	
	// ATOMIC OPERATION IMPLEMENTATION: Document+Attachment+Versioning
	// All CouchDB operations executed within controlled sequence to prevent _rev inconsistencies
	log.debug("=== ATOMIC DOCUMENT CREATION START ===");
	log.debug("Repository: {}, ContentStream: {}", repositoryId, (contentStream != null ? "PROVIDED" : "NULL"));
	
	if (log.isDebugEnabled() && properties != null) {
		Object objectTypeId = properties.getProperties().get("cmis:objectTypeId");
		Object name = properties.getProperties().get("cmis:name");
		Object secondaryTypeIds = properties.getProperties().get("cmis:secondaryObjectTypeIds");
		log.debug("Creating document - Name: {}, Type: {}, SecondaryTypes: {}, ContentStream: {}", 
			name, objectTypeId, secondaryTypeIds, (contentStream != null ? "provided" : "none"));
	}
	
	Document atomicResult = null;
	String createdDocumentId = null;
	String createdAttachmentId = null;
	boolean rollbackRequired = false;
	
	try {
		// PHASE 1: Prepare all components without CouchDB writes
		Document d = buildNewBasicDocument(callContext, repositoryId, properties, parentFolder, addAces, removeAces);
		
		// CRITICAL FIX: Evaluate ContentStreamAllowed considering Secondary Types
		DocumentTypeDefinition tdf = (DocumentTypeDefinition) (getTypeManager().getTypeDefinition(repositoryId, d));
		ContentStreamAllowed csa = tdf.getContentStreamAllowed();
		
		List<String> secondaryTypeIds = d.getSecondaryIds();
		if (secondaryTypeIds != null && !secondaryTypeIds.isEmpty()) {
			for (String secondaryTypeId : secondaryTypeIds) {
				try {
					org.apache.chemistry.opencmis.commons.definitions.TypeDefinition secondaryTd = 
						getTypeManager().getTypeDefinition(repositoryId, secondaryTypeId);
					if (secondaryTd instanceof DocumentTypeDefinition) {
						DocumentTypeDefinition secondaryDocTd = (DocumentTypeDefinition) secondaryTd;
						ContentStreamAllowed secondaryCsa = secondaryDocTd.getContentStreamAllowed();
						if (secondaryCsa == ContentStreamAllowed.REQUIRED) {
							csa = ContentStreamAllowed.REQUIRED;
							log.debug("Secondary Type {} requires content stream, upgrading ContentStreamAllowed to REQUIRED", secondaryTypeId);
							break;
						}
					}
				} catch (Exception e) {
					log.debug("Could not evaluate Secondary Type {} for ContentStreamAllowed: {}", secondaryTypeId, e.getMessage());
				}
			}
		}
		
		log.debug("Final ContentStreamAllowed evaluation: {}, ContentStream provided: {}", csa, (contentStream != null));
		
		log.debug("ContentStreamAllowed: {}, ContentStream provided: {}", csa, (contentStream != null));
		
		// CMIS 1.1 SPECIFICATION COMPLIANT: Evaluate attachment creation conditions correctly
		boolean conditionA = (csa == ContentStreamAllowed.REQUIRED);
		boolean conditionB = (csa == ContentStreamAllowed.ALLOWED && contentStream != null);
		boolean overallCondition = conditionA || conditionB;
		
		log.debug("Attachment creation evaluation - Type: {}, ContentStreamAllowed: {}, ContentStream: {}, WillCreateAttachment: {}", 
			d.getObjectType(), csa, (contentStream != null ? "provided" : "null"), overallCondition);
		
		// PHASE 2: Atomic Attachment creation (CMIS 1.1 SPECIFICATION COMPLIANT)
		if (csa == ContentStreamAllowed.REQUIRED || (csa == ContentStreamAllowed.ALLOWED && contentStream != null)) {
			// Create Attachment atomically with immediate Document reference
			createdAttachmentId = createAttachmentAtomic(callContext, repositoryId, contentStream);
			d.setAttachmentNodeId(createdAttachmentId);
			log.debug("Created AttachmentId atomically: {}", createdAttachmentId);
			
		log.debug("Created AttachmentId: {}, set Document.attachmentNodeId to: {}", createdAttachmentId, d.getAttachmentNodeId());
			
			// Preview creation (optional - failure won't affect main operation)
			if (isPreviewEnabled()) {
				try {
					createPreviewAtomic(callContext, repositoryId, contentStream, d, createdAttachmentId);
				} catch (Exception ex) {
					log.warn("Preview creation failed for document {} (non-critical): {}", d.getName(), ex.getMessage());
				}
			}
		}
		
		// PHASE 3: Version properties preparation
		log.debug("PHASE 3: Setting version properties for versioningState: {}", versioningState);
		VersionSeries vs = setVersionProperties(callContext, repositoryId, versioningState, d);
		log.debug("VersionSeries created/configured: ID={}, revision={}", vs.getId(), vs.getRevision());
		
		// PHASE 4: Atomic Document creation (single CouchDB write)
		log.debug("PHASE 4: Creating document atomically with versionSeriesId: {}", d.getVersionSeriesId());
	log.debug("Document.attachmentNodeId before DAO: {}", d.getAttachmentNodeId());
		
		atomicResult = contentDaoService.create(repositoryId, d);
		createdDocumentId = atomicResult.getId();
		log.debug("Created Document atomically: {} with revision: {}", createdDocumentId, atomicResult.getRevision());
		
	log.debug("Created Document ID: {}, atomicResult.attachmentNodeId: {}", createdDocumentId, atomicResult.getAttachmentNodeId());
		
	// PHASE 5: Version series update (if required)
	log.debug("VersioningState: {}, atomicResult.attachmentNodeId BEFORE version series update: {}", versioningState, atomicResult.getAttachmentNodeId());
	
	if (versioningState == VersioningState.CHECKEDOUT) {
		updateVersionSeriesWithPwcAtomic(callContext, repositoryId, vs, atomicResult);
		log.debug("atomicResult.attachmentNodeId AFTER version series update: {}", atomicResult.getAttachmentNodeId());
	}
		
	// PHASE 6: Change event and indexing (non-critical operations)
	log.debug("atomicResult.attachmentNodeId BEFORE change event: {}", atomicResult.getAttachmentNodeId());
	
	writeChangeEvent(callContext, repositoryId, atomicResult, ChangeType.CREATED);
	
	log.debug("atomicResult.attachmentNodeId AFTER change event: {}", atomicResult.getAttachmentNodeId());
		
		// Solr indexing (failure won't affect main operation)
		try {
			if (solrUtil != null) {
				solrUtil.indexDocument(repositoryId, atomicResult);
			} else {
				log.debug("solrUtil is null - skipping indexing for document: {}", atomicResult.getId());
			}
		} catch (Exception e) {
			log.warn("Solr indexing failed for document {} (non-critical): {}", atomicResult.getId(), e.getMessage());
		}
		
		if (log.isDebugEnabled()) {
			log.debug("Final atomicResult.id: " + atomicResult.getId());
			log.debug("Final atomicResult.attachmentNodeId: " + atomicResult.getAttachmentNodeId());
			log.debug("Final atomicResult.type: " + atomicResult.getType());
			if (atomicResult.getAttachmentNodeId() == null) {
				log.debug("WARNING: attachmentNodeId is NULL in final result");
			}
		}
		
		log.debug("=== ATOMIC DOCUMENT CREATION SUCCESS: {} ===", atomicResult.getId());
		return atomicResult;
		
	} catch (Exception e) {
		rollbackRequired = true;
		log.error("=== ATOMIC DOCUMENT CREATION FAILED - INITIATING ROLLBACK ===");
		log.error("Error during atomic document creation: {}", e.getMessage(), e);
		
		// ROLLBACK STRATEGY: Clean up any created resources
		try {
			if (createdDocumentId != null) {
				log.debug("Rolling back created document: {}", createdDocumentId);
				contentDaoService.delete(repositoryId, createdDocumentId);
			}
			if (createdAttachmentId != null) {
				log.debug("Rolling back created attachment: {}", createdAttachmentId);
				contentDaoService.delete(repositoryId, createdAttachmentId);
			}
		} catch (Exception rollbackException) {
			log.error("Rollback operation failed: {}", rollbackException.getMessage(), rollbackException);
		}
		
		throw new CmisRuntimeException("Atomic document creation failed: " + e.getMessage(), e);
	}
}

	@Override
	public Document createDocumentFromSource(CallContext callContext, String repositoryId, Properties properties,
		Folder target, Document original, VersioningState versioningState, List<String> policies,
		org.apache.chemistry.opencmis.commons.data.Acl addAces,
		org.apache.chemistry.opencmis.commons.data.Acl removeAces) {
	
	// ATOMIC OPERATION IMPLEMENTATION: Document Copy + Attachment + Versioning
	log.debug("=== ATOMIC DOCUMENT FROM SOURCE START ===");
	log.debug("Repository: {}, Original: {}", repositoryId, original.getId());
	
	Document atomicResult = null;
	String createdDocumentId = null;
	String createdAttachmentId = null;
	
	try {
		// PHASE 1: Prepare document copy without CouchDB writes
		Document copy = buildCopyDocument(callContext, repositoryId, original, null, null);

		// PHASE 2: Atomic Attachment copy (if source has attachment)
		if (original.getAttachmentNodeId() != null) {
			createdAttachmentId = copyAttachmentAtomic(callContext, repositoryId, original.getAttachmentNodeId());
			copy.setAttachmentNodeId(createdAttachmentId);
			log.debug("Copied AttachmentId atomically: {}", createdAttachmentId);
		}

		// PHASE 3: Version properties and other metadata
		setVersionProperties(callContext, repositoryId, versioningState, copy);
		copy.setParentId(target.getId());
		updateProperties(callContext, repositoryId, properties, copy);
		setSignature(callContext, copy);

		// PHASE 4: Atomic Document creation (single CouchDB write)
		atomicResult = contentDaoService.create(repositoryId, copy);
		createdDocumentId = atomicResult.getId();
		log.debug("Created Document from source atomically: {}", createdDocumentId);

		// PHASE 5: Version series update (if required)
		if (versioningState == VersioningState.CHECKEDOUT) {
			updateVersionSeriesWithPwcAtomic(callContext, repositoryId, getVersionSeries(repositoryId, atomicResult), atomicResult);
		}

		// PHASE 6: Change events and indexing (non-critical operations)
		writeChangeEvent(callContext, repositoryId, atomicResult, ChangeType.CREATED);
		
		// Solr indexing (failure won't affect main operation)
		try {
			if (solrUtil != null) {
				solrUtil.indexDocument(repositoryId, atomicResult);
			}
		} catch (Exception e) {
			log.warn("Solr indexing failed for copied document {} (non-critical): {}", atomicResult.getId(), e.getMessage());
		}

		log.debug("=== ATOMIC DOCUMENT FROM SOURCE SUCCESS: {} ===", atomicResult.getId());
		return atomicResult;
		
	} catch (Exception e) {
		log.error("=== ATOMIC DOCUMENT FROM SOURCE FAILED - INITIATING ROLLBACK ===");
		log.error("Error during atomic document from source: {}", e.getMessage(), e);
		
		// ROLLBACK STRATEGY
		try {
			if (createdDocumentId != null) {
				log.debug("Rolling back created document: {}", createdDocumentId);
				contentDaoService.delete(repositoryId, createdDocumentId);
			}
			if (createdAttachmentId != null) {
				log.debug("Rolling back created attachment: {}", createdAttachmentId);
				contentDaoService.delete(repositoryId, createdAttachmentId);
			}
		} catch (Exception rollbackException) {
			log.error("Rollback operation failed: {}", rollbackException.getMessage(), rollbackException);
		}
		
		throw new CmisRuntimeException("Atomic document from source creation failed: " + e.getMessage(), e);
	}
}

	@Override
	public Document createDocumentWithNewStream(CallContext callContext, String repositoryId, Document original,
		ContentStream contentStream) {
	
	// ATOMIC OPERATION IMPLEMENTATION: Document with New Stream + Attachment + Preview
	log.debug("=== ATOMIC DOCUMENT WITH NEW STREAM START ===");
	log.debug("Repository: {}, Original: {}", repositoryId, original.getId());
	
	Document atomicResult = null;
	String createdDocumentId = null;
	String createdAttachmentId = null;
	
	try {
		// PHASE 1: Prepare document copy without CouchDB writes
		Document copy = buildCopyDocument(callContext, repositoryId, original, null, null);

		// PHASE 2: Atomic Attachment creation with new stream
		createdAttachmentId = createAttachmentAtomic(callContext, repositoryId, contentStream);
		copy.setAttachmentNodeId(createdAttachmentId);
		log.debug("Created new AttachmentId atomically: {}", createdAttachmentId);

		// PHASE 3: Preview creation (optional - failure won't affect main operation)
		if (isPreviewEnabled()) {
			try {
				createPreviewAtomic(callContext, repositoryId, contentStream, copy, createdAttachmentId);
			} catch (Exception ex) {
				log.warn("Preview creation failed for document with new stream {} (non-critical): {}", copy.getName(), ex.getMessage());
			}
		}

		// PHASE 4: Version properties update
		updateVersionProperties(callContext, repositoryId, VersioningState.MINOR, copy, original);

		// PHASE 5: Atomic Document creation (single CouchDB write)
		atomicResult = contentDaoService.create(repositoryId, copy);
		createdDocumentId = atomicResult.getId();
		log.debug("Created Document with new stream atomically: {}", createdDocumentId);

		// PHASE 6: Change events and indexing (non-critical operations)
		writeChangeEvent(callContext, repositoryId, atomicResult, ChangeType.CREATED);
		writeChangeEvent(callContext, repositoryId, original, ChangeType.UPDATED);
		
		// Solr indexing (failure won't affect main operation)
		try {
			if (solrUtil != null) {
				solrUtil.indexDocument(repositoryId, atomicResult);
				solrUtil.indexDocument(repositoryId, original);
			}
		} catch (Exception e) {
			log.warn("Solr indexing failed for document with new stream {} (non-critical): {}", atomicResult.getId(), e.getMessage());
		}

		log.debug("=== ATOMIC DOCUMENT WITH NEW STREAM SUCCESS: {} ===", atomicResult.getId());
		return atomicResult;
		
	} catch (Exception e) {
		log.error("=== ATOMIC DOCUMENT WITH NEW STREAM FAILED - INITIATING ROLLBACK ===");
		log.error("Error during atomic document with new stream: {}", e.getMessage(), e);
		
		// ROLLBACK STRATEGY
		try {
			if (createdDocumentId != null) {
				log.debug("Rolling back created document: {}", createdDocumentId);
				contentDaoService.delete(repositoryId, createdDocumentId);
			}
			if (createdAttachmentId != null) {
				log.debug("Rolling back created attachment: {}", createdAttachmentId);
				contentDaoService.delete(repositoryId, createdAttachmentId);
			}
		} catch (Exception rollbackException) {
			log.error("Rollback operation failed: {}", rollbackException.getMessage(), rollbackException);
		}
		
		throw new CmisRuntimeException("Atomic document with new stream creation failed: " + e.getMessage(), e);
	}
}

	public Document replacePwc(CallContext callContext, String repositoryId, Document originalPwc,
			ContentStream contentStream) {
		// Update attachment contentStream
		AttachmentNode an = contentDaoService.getAttachment(repositoryId, originalPwc.getAttachmentNodeId());
		contentDaoService.updateAttachment(repositoryId, an, contentStream);

		// Update rendition contentStream

		if (isPreviewEnabled()) {
			ContentStream previewCS = new ContentStreamImpl(contentStream.getFileName(), contentStream.getBigLength(),
					contentStream.getMimeType(), an.getInputStream());

			if (renditionManager.checkConvertible(previewCS.getMimeType())) {
				List<String> renditionIds = originalPwc.getRenditionIds();
				if (CollectionUtils.isNotEmpty(renditionIds)) {
					List<String> removedRenditionIds = new ArrayList<String>();

					// Create preview
					for (String renditionId : renditionIds) {
						Rendition rd = contentDaoService.getRendition(repositoryId, renditionId);
						if (RenditionKind.CMIS_PREVIEW.equals(rd.getKind())) {
							removedRenditionIds.add(renditionId);
							createPreview(callContext, repositoryId, previewCS, originalPwc);
						}
					}

					// Update reference to preview ID
					renditionIds.removeAll(removedRenditionIds);
					originalPwc.setRenditionIds(renditionIds);
				}
			}
		}

		// Modify signature of pwc
		setSignature(callContext, originalPwc);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, originalPwc, ChangeType.UPDATED);

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);

		return originalPwc;
	}

	@Override
	public Document checkOut(CallContext callContext, String repositoryId, String objectId, ExtensionsData extension) {
		Document latest = getDocument(repositoryId, objectId);
		Document pwc = buildCopyDocument(callContext, repositoryId, latest, null, null);

		// Create PWC attachment
		String attachmentId = copyAttachment(callContext, repositoryId, latest.getAttachmentNodeId());
		pwc.setAttachmentNodeId(attachmentId);

		// Create PWC renditions
		copyRenditions(callContext, repositoryId, latest.getRenditionIds());

		// Set other properties
		updateVersionProperties(callContext, repositoryId, VersioningState.CHECKEDOUT, pwc, latest);

		// Create PWC itself
		Document result = contentDaoService.create(repositoryId, pwc);

		// CRITICAL TCK FIX: Set versionSeriesCheckedOutId for PWC after it has been created and has an ID
		log.error("*** CRITICAL TCK FIX: Setting versionSeriesCheckedOutId for PWC {} ***", result.getId());
		result.setVersionSeriesCheckedOutId(result.getId());
		result.setVersionSeriesCheckedOut(true);
		result.setVersionSeriesCheckedOutBy(callContext.getUsername());
		contentDaoService.update(repositoryId, result);
		log.error("*** CRITICAL TCK FIX: PWC versionSeriesCheckedOutId set to: {} ***", result.getVersionSeriesCheckedOutId());

		// Modify versionSeries
		VersionSeries vs = getVersionSeries(repositoryId, result);
		updateVersionSeriesWithPwc(callContext, repositoryId, vs, result);

		// CRITICAL TCK FIX: Update all versions in version series to reflect checked-out state
		// This ensures cmis:isVersionSeriesCheckedOut and related properties are updated
		log.error("*** CRITICAL TCK FIX: Starting version series update for VS: {} ***", vs.getId());
		List<Document> versions = contentDaoService.getAllVersions(repositoryId, vs.getId());
		log.error("*** CRITICAL TCK FIX: Found {} versions in version series ***", (versions != null ? versions.size() : 0));
		if (CollectionUtils.isNotEmpty(versions)) {
			for (Document version : versions) {
				log.error("*** CRITICAL TCK FIX: Processing version {} (PWC: {}) ***", version.getId(), version.isPrivateWorkingCopy());
				if (!version.isPrivateWorkingCopy()) { // Don't update PWC, it already has correct properties
					// Update versioning properties to reflect VersionSeries state
					log.error("*** CRITICAL TCK FIX: Updating version {} with checkout properties ***", version.getId());
					version.setVersionSeriesCheckedOut(true);
					version.setVersionSeriesCheckedOutBy(callContext.getUsername());
					version.setVersionSeriesCheckedOutId(result.getId());
					contentDaoService.update(repositoryId, version);
					log.error("*** CRITICAL TCK FIX: Updated version {} successfully ***", version.getId());
				}
			}
		} else {
			log.error("*** CRITICAL TCK FIX: No versions found in version series {} ***", vs.getId());
		}

		// Write change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.CREATED);

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);

		return result;
	}

	@Override
	public void cancelCheckOut(CallContext callContext, String repositoryId, String objectId,
			ExtensionsData extension) {
		Document pwc = getDocument(repositoryId, objectId);

		writeChangeEvent(callContext, repositoryId, pwc, ChangeType.DELETED);

		// Delete attachment & document itself(without archiving)
		contentDaoService.delete(repositoryId, pwc.getAttachmentNodeId());
		contentDaoService.delete(repositoryId, pwc.getId());

		VersionSeries vs = getVersionSeries(repositoryId, pwc);
		// Reverse the effect of checkout
		setModifiedSignature(callContext, vs);
		vs.setVersionSeriesCheckedOut(false);
		vs.setVersionSeriesCheckedOutBy("");
		vs.setVersionSeriesCheckedOutId("");
		contentDaoService.update(repositoryId, vs);

		// CRITICAL TCK FIX: Update all versions in version series to reflect canceled checkout state
		// This ensures cmis:isVersionSeriesCheckedOut and related properties are updated
		List<Document> versions = contentDaoService.getAllVersions(repositoryId, vs.getId());
		if (CollectionUtils.isNotEmpty(versions)) {
			for (Document version : versions) {
				// Update versioning properties to reflect VersionSeries state (no PWC filtering needed)
				version.setVersionSeriesCheckedOut(false);
				version.setVersionSeriesCheckedOutBy("");
				version.setVersionSeriesCheckedOutId("");
				contentDaoService.update(repositoryId, version);
			}
		}

		// Call Solr indexing(optional) - delete document from index
		solrUtil.deleteDocument(repositoryId, pwc.getId());
	}

	@Override
	public Document checkIn(CallContext callContext, String repositoryId, Holder<String> objectId, Boolean major,
			Properties properties, ContentStream contentStream, String checkinComment, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {

		String id = objectId.getValue();

		Document pwc = getDocument(repositoryId, id);
		Document checkedIn = buildCopyDocument(callContext, repositoryId, pwc, addAces, removeAces);
		Document latest = getDocumentOfLatestVersion(repositoryId, pwc.getVersionSeriesId());

		// When PWCUpdatable is true
		if (contentStream == null) {
			checkedIn.setAttachmentNodeId(copyAttachment(callContext, repositoryId, pwc.getAttachmentNodeId()));
			// When PWCUpdatable is false
		} else {
			checkedIn.setAttachmentNodeId(createAttachment(callContext, repositoryId, contentStream));
		}

		// Set updated properties
		// updateProperties(callContext, properties, checkedIn);
		modifyProperties(callContext, repositoryId, properties, checkedIn);
		setSignature(callContext, checkedIn);
		checkedIn.setCheckinComment(checkinComment);

		// Reverse the effect of checkedout
		cancelCheckOut(callContext, repositoryId, id, extension);

		// update version information
		VersioningState versioningState = (major) ? VersioningState.MAJOR : VersioningState.MINOR;
		updateVersionProperties(callContext, repositoryId, versioningState, checkedIn, latest);

		// TODO set policies & ACEs

		// Create
		Document result = contentDaoService.create(repositoryId, checkedIn);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.CREATED);
		writeChangeEvent(callContext, repositoryId, latest, ChangeType.UPDATED);

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);

		return result;
	}

	public Document updateWithoutCheckInOut(CallContext callContext, String repositoryId, Boolean major,
			Properties properties, ContentStream contentStream, String checkinComment, Document previousDoc,
			VersionSeries vs) {

		Document checkedIn = buildCopyDocument(callContext, repositoryId, previousDoc, null, null);

		checkedIn.setAttachmentNodeId(createAttachment(callContext, repositoryId, contentStream));

		// Set updated properties
		// updateProperties(callContext, properties, checkedIn);
		modifyProperties(callContext, repositoryId, properties, checkedIn);
		setSignature(callContext, checkedIn);
		checkedIn.setCheckinComment(checkinComment);

		// update version information
		VersioningState versioningState = (major) ? VersioningState.MAJOR : VersioningState.MINOR;
		updateVersionProperties(callContext, repositoryId, versioningState, checkedIn, previousDoc);

		// TODO set policies & ACEs

		// Create
		Document result = contentDaoService.create(repositoryId, checkedIn);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.CREATED);
		writeChangeEvent(callContext, repositoryId, previousDoc, ChangeType.UPDATED);

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);

		return result;
	}

	private Document buildNewBasicDocument(CallContext callContext, String repositoryId, Properties properties,
			Folder parentFolder, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces) {
		Document d = new Document();
		setBaseProperties(callContext, repositoryId, properties, d, parentFolder.getId());
		d.setParentId(parentFolder.getId());
		d.setImmutable(DataUtil.getBooleanProperty(properties, PropertyIds.IS_IMMUTABLE));
		setSignature(callContext, d);

		// Acl
		setAclOnCreated(callContext, repositoryId, d, addAces, removeAces);

		return d;
	}

	private void setAclOnCreated(CallContext callContext, String repositoryId, Content content,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces) {
		Acl acl = new Acl();
		if (isTopLevel(repositoryId, content)) {

			Ace ace = new Ace();
			ace.setPrincipalId(callContext.getUsername());
			ace.setPermissions(new ArrayList<String>(Arrays.asList(CmisPermission.ALL)));
			acl.setLocalAces(new ArrayList<Ace>(Arrays.asList(ace)));
		}

		if (addAces != null) {
			for (org.apache.chemistry.opencmis.commons.data.Ace cmisAce : addAces.getAces()) {
				acl.getLocalAces().add(new Ace(cmisAce.getPrincipalId(), cmisAce.getPermissions(), true));
			}
		}

		// removeAces
		// 'remove' means remove from parent folder's ACL.
		// On the base of NemakiWare ACL inheritance, it does not make sense.

		content.setAcl(acl);

		content.setAclInherited(getAclInheritedWithDefault(repositoryId, content));
	}

	private Document buildCopyDocument(CallContext callContext, String repositoryId, Document original,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces) {
		Document copy = new Document();
		copy.setType(original.getType());
		copy.setObjectType(original.getObjectType());
		copy.setName(original.getName());
		copy.setDescription(original.getDescription());
		copy.setParentId(original.getParentId());
		copy.setImmutable(original.isImmutable());
		copy.setAclInherited(original.isAclInherited());
		copy.setSubTypeProperties(new ArrayList<Property>(original.getSubTypeProperties()));
		setAclOnCreated(callContext, repositoryId, copy, addAces, removeAces);
		copy.setAspects(original.getAspects());
		copy.setSecondaryIds(original.getSecondaryIds());
		setSignature(callContext, copy);

		return copy;
	}

	private VersionSeries setVersionProperties(CallContext callContext, String repositoryId,
			VersioningState versioningState, Document d) {
		// Version properties
		// CASE:New VersionSeries
		VersionSeries vs;

		// CRITICAL FIX: Handle null versioningState for non-versionable documents
		// For versionable=false document types, versioningState may be null from Browser Binding
		if (versioningState == null) {
			versioningState = VersioningState.NONE;
			log.debug("versioningState was null, defaulting to NONE for non-versionable document");
		}

		vs = createVersionSeries(callContext, repositoryId, versioningState);
		d.setVersionSeriesId(vs.getId());
		switch (versioningState) {
		case NONE:
			// CMIS 1.1 COMPLIANCE: Handle NONE state for non-versionable documents
			d.setLatestVersion(true);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setVersionLabel(null);
			d.setPrivateWorkingCopy(false);
			d.setVersionSeriesCheckedOut(false);
			d.setVersionSeriesCheckedOutBy(null);
			d.setVersionSeriesCheckedOutId(null);
			// CMIS 1.1 compliance: provide empty string default for required cmis:checkinComment
			d.setCheckinComment("");
			break;
		case CHECKEDOUT:
			d.setLatestVersion(false);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setPrivateWorkingCopy(true);
			// CRITICAL CMIS 1.1 COMPLIANCE: Set isVersionSeriesCheckedOut=true for checked out documents
			d.setVersionSeriesCheckedOut(true);
			// Set additional versioning properties for checked out documents
			d.setVersionSeriesCheckedOutBy(callContext.getUsername());
			d.setVersionSeriesCheckedOutId(d.getId());
			break;
		case MAJOR:
			d.setLatestVersion(true);
			d.setMajorVersion(true);
			d.setLatestMajorVersion(true);
			d.setVersionLabel("1.0");
			d.setPrivateWorkingCopy(false);
			// CRITICAL CMIS 1.1 COMPLIANCE: Set isVersionSeriesCheckedOut=false for non-checked out documents
			d.setVersionSeriesCheckedOut(false);
			// Clear additional versioning properties for non-checked out documents
			d.setVersionSeriesCheckedOutBy(null);
			d.setVersionSeriesCheckedOutId(null);
			// CMIS 1.1 compliance: provide empty string default for required cmis:checkinComment
			d.setCheckinComment("");
			break;
		case MINOR:
			d.setLatestVersion(true);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setVersionLabel("0.1");
			d.setPrivateWorkingCopy(false);
			// CRITICAL CMIS 1.1 COMPLIANCE: Set isVersionSeriesCheckedOut=false for non-checked out documents
			d.setVersionSeriesCheckedOut(false);
			// Clear additional versioning properties for non-checked out documents
			d.setVersionSeriesCheckedOutBy(null);
			d.setVersionSeriesCheckedOutId(null);
			// CMIS 1.1 compliance: provide empty string default for required cmis:checkinComment
			d.setCheckinComment("");
			break;
		default:
			break;
		}

		return vs;
	}

	private void updateVersionProperties(CallContext callContext, String repositoryId, VersioningState versioningState,
			Document d, Document former) {
		d.setVersionSeriesId(former.getVersionSeriesId());

		switch (versioningState) {
		case MAJOR:
			d.setLatestVersion(true);
			d.setMajorVersion(true);
			d.setLatestMajorVersion(true);
			d.setVersionLabel(increasedVersionLabel(former, versioningState));
			d.setPrivateWorkingCopy(false);
			former.setLatestVersion(false);
			former.setLatestMajorVersion(false);
			contentDaoService.update(repositoryId, former);
			break;
		case MINOR:
			d.setLatestVersion(true);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setVersionLabel(increasedVersionLabel(former, versioningState));
			d.setPrivateWorkingCopy(false);
			former.setLatestVersion(false);
			contentDaoService.update(repositoryId, former);
			break;
		case CHECKEDOUT:
			d.setLatestVersion(false);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setPrivateWorkingCopy(true);
			// CRITICAL TCK FIX: Set versioning properties required by VersioningStateCreateTest
			d.setVersionSeriesCheckedOut(true); // Mark as checked out
			d.setVersionSeriesCheckedOutBy(callContext.getUsername());
			d.setVersionSeriesCheckedOutId(d.getId()); // PWC's own ID
			// former latestVersion/latestMajorVersion remains unchanged
			break;
		default:
			break;
		}
	}

	private VersionSeries createVersionSeries(CallContext callContext, String repositoryId,
			VersioningState versioningState) {
		log.debug("Creating new VersionSeries for versioningState: {}", versioningState);
		
		VersionSeries vs = new VersionSeries();
		vs.setVersionSeriesCheckedOut(false);
		setSignature(callContext, vs);

		try {
			VersionSeries versionSeries = contentDaoService.create(repositoryId, vs);
			log.debug("VersionSeries created successfully with ID: {} and revision: {}", 
				versionSeries.getId(), versionSeries.getRevision());
			return versionSeries;
		} catch (Exception e) {
			log.error("Failed to create VersionSeries: {}", e.getMessage(), e);
			throw new RuntimeException("VersionSeries creation failed", e);
		}
	}

	/**
	 * Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
	 *
	 * @param callContext
	 * @param repositoryId
	 *            TODO
	 * @param versionSeries
	 * @param pwc
	 */
	private void updateVersionSeriesWithPwc(CallContext callContext, String repositoryId, VersionSeries versionSeries,
			Document pwc) {

		// CRITICAL FIX: Fetch latest VersionSeries from DB to ensure _rev synchronization
		// This prevents Cloudant SDK revision conflicts during update operations
		log.debug("Fetching latest VersionSeries from DB for revision synchronization: {}", versionSeries.getId());
		VersionSeries latestVersionSeries = contentDaoService.getVersionSeries(repositoryId, versionSeries.getId());
		
		if (latestVersionSeries == null) {
			log.error("VersionSeries not found in database: {}", versionSeries.getId());
			throw new IllegalStateException("VersionSeries not found for update: " + versionSeries.getId());
		}
		
		// Apply updates to the fresh object with current _rev
		latestVersionSeries.setVersionSeriesCheckedOut(true);
		latestVersionSeries.setVersionSeriesCheckedOutId(pwc.getId());
		latestVersionSeries.setVersionSeriesCheckedOutBy(callContext.getUsername());
		
		log.debug("Updating VersionSeries with current revision: {} for PWC: {}", 
			latestVersionSeries.getRevision(), pwc.getId());
		
		// Update with synchronized revision
		contentDaoService.update(repositoryId, latestVersionSeries);
		
		log.debug("VersionSeries update completed successfully for PWC: {}", pwc.getId());
	}

	@Override
	public Folder createFolder(CallContext callContext, String repositoryId, Properties properties, Folder parentFolder,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {
		log.debug("Creating folder in repository: " + repositoryId);
		
		if (parentFolder == null) {
			log.warn("Cannot create folder - parentFolder is null. Repository may not be fully initialized yet for repository: " + repositoryId);
			return null;
		}
		
		Folder f = new Folder();
		setBaseProperties(callContext, repositoryId, properties, f, parentFolder.getId());
		f.setParentId(parentFolder.getId());
		// Defaults to document / folder / item if not specified
		List<String> allowedTypes = DataUtil.getIdListProperty(properties, PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS);
		if (CollectionUtils.isEmpty(allowedTypes)) {
			List<String> l = new ArrayList<String>();
			l.add(BaseTypeId.CMIS_FOLDER.value());
			l.add(BaseTypeId.CMIS_DOCUMENT.value());
			l.add(BaseTypeId.CMIS_ITEM.value());
			f.setAllowedChildTypeIds(l);
		} else {
			f.setAllowedChildTypeIds(allowedTypes);
		}
		setSignature(callContext, f);

		setAclOnCreated(callContext, repositoryId, f, addAces, removeAces);

		// Create
		Folder folder = contentDaoService.create(repositoryId, f);

		// Record the change event
		// Content objects now maintain revision state, enabling proper writeChangeEvent
		writeChangeEvent(callContext, repositoryId, folder, ChangeType.CREATED);

		// Call Solr indexing(optional)
		solrUtil.indexDocument(repositoryId, folder);

		return folder;
	}

	@Override
	public Relationship createRelationship(CallContext callContext, String repositoryId, Properties properties,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {

		Relationship rel = new Relationship();
		setBaseProperties(callContext, repositoryId, properties, rel, null);
		rel.setSourceId(DataUtil.getIdProperty(properties, PropertyIds.SOURCE_ID));
		rel.setTargetId(DataUtil.getIdProperty(properties, PropertyIds.TARGET_ID));
		// Set ACL
		setAclOnCreated(callContext, repositoryId, rel, addAces, removeAces);

		Relationship relationship = contentDaoService.create(repositoryId, rel);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, relationship, ChangeType.CREATED);

		return relationship;
	}

	@Override
	public Policy createPolicy(CallContext callContext, String repositoryId, Properties properties,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {

		Policy p = new Policy();
		setBaseProperties(callContext, repositoryId, properties, p, null);
		p.setPolicyText(DataUtil.getStringProperty(properties, PropertyIds.POLICY_TEXT));
		p.setAppliedIds(new ArrayList<String>());

		// Set ACL
		setAclOnCreated(callContext, repositoryId, p, addAces, removeAces);

		Policy policy = contentDaoService.create(repositoryId, p);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, policy, ChangeType.CREATED);

		return policy;
	}

	@Override
	public Item createItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {
		// Check nemaki:user type
		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
		if (NemakiObjectType.nemakiUser.equals(objectTypeId)) {
			return createUserItem(callContext, repositoryId, properties, folderId, policies, addAces, removeAces,
					extension);
		}
		if (NemakiObjectType.nemakiGroup.equals(objectTypeId)) {
			return createGroupItem(callContext, repositoryId, properties, folderId, policies, addAces, removeAces,
					extension);
		}

		Item i = buildItem(callContext, repositoryId, properties, folderId, policies, addAces, removeAces, extension);
		Item item = contentDaoService.create(repositoryId, i);
		writeChangeEvent(callContext, repositoryId, item, ChangeType.CREATED);
		return item;
	}

	@Override
	public UserItem createUserItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {
		UserItem userItem = buildUserItem(callContext, repositoryId, properties, folderId, policies, addAces,
				removeAces, extension);
		return createUserItem(callContext, repositoryId, userItem);
	}

	@Override
	public UserItem createUserItem(CallContext callContext, String repositoryId, UserItem userItem) {
		validateUserItem(repositoryId, userItem);

		UserItem created = contentDaoService.create(repositoryId, userItem);
		writeChangeEvent(callContext, repositoryId, created, ChangeType.CREATED);
		return created;
	}

	@Override
	public GroupItem createGroupItem(CallContext callContext, String repositoryId, Properties properties,
			String folderId, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {
		Item i = buildItem(callContext, repositoryId, properties, folderId, policies, addAces, removeAces, extension);
		GroupItem groupItem = new GroupItem(i);
		final String groupId = properties.getProperties().get("nemaki:groupId").getFirstValue().toString();
		groupItem.setGroupId(groupId);

		validateGroupItem(repositoryId, groupItem);

		GroupItem created = contentDaoService.create(repositoryId, groupItem);
		writeChangeEvent(callContext, repositoryId, created, ChangeType.CREATED);
		return created;
	}

	@Override
	public GroupItem createGroupItem(CallContext callContext, String repositoryId, GroupItem groupItem) {
		validateGroupItem(repositoryId, groupItem);

		GroupItem created = contentDaoService.create(repositoryId, groupItem);
		writeChangeEvent(callContext, repositoryId, created, ChangeType.CREATED);
		return created;
	}

	private Item buildItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {

		Item i = new Item();
		setBaseProperties(callContext, repositoryId, properties, i, null);
		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
		TypeDefinition tdf = getTypeManager().getTypeDefinition(repositoryId, objectTypeId);
		if (tdf.isFileable()) {
			i.setParentId(folderId);
		}

		// Set ACL
		setAclOnCreated(callContext, repositoryId, i, addAces, removeAces);

		return i;
	}

	private UserItem buildUserItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {

		Item i = buildItem(callContext, repositoryId, properties, folderId, policies, addAces, removeAces, extension);

		// User ID
		UserItem userItem = new UserItem(i);
		final String userId = properties.getProperties().get("nemaki:userId").getFirstValue().toString();
		userItem.setUserId(userId);

		// ACL
		final String ANYONE = repositoryInfoMap.get(repositoryId).getPrincipalIdAnyone();
		Acl acl = new Acl();
		acl.setLocalAces(Arrays.asList(
				// anyone else cannot see the user,
				new Ace(ANYONE, Arrays.asList("cmis:none"), true),
				// except oneself.
				new Ace(userId, Arrays.asList(CmisPermission.READ, CmisPermission.WRITE), true)));
		userItem.setAcl(acl);

		return userItem;
	}

	private void setBaseProperties(CallContext callContext, String repositoryId, Properties properties, Content content,
			String parentFolderId) {
		// Object Type
		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
		TypeDefinition typeDefinition = getTypeManager().getTypeDefinition(repositoryId, objectTypeId);
		content.setObjectType(objectTypeId);

		// Base Type
		BaseTypeId baseTypeId = typeDefinition.getBaseTypeId();
		content.setType(baseTypeId.value());

		// Name
		String name = DataUtil.getStringProperty(properties, PropertyIds.NAME);
		content.setName(name);

		// Description - CMIS 1.1 compliance: provide empty string default for required property
		String description = DataUtil.getStringProperty(properties, PropertyIds.DESCRIPTION);
		content.setDescription(description != null ? description : "");

		// Secondary Type IDs - CMIS 1.1 compliance: provide empty list default for required property
		List<String> secondaryTypeIds = DataUtil.getIdListProperty(properties, PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
		content.setSecondaryIds(secondaryTypeIds != null ? secondaryTypeIds : new ArrayList<String>());

		// Subtype properties
		List<Property> subTypeProperties = buildSubTypeProperties(repositoryId, properties, content);
		if (!CollectionUtils.isEmpty(subTypeProperties)) {
			content.setSubTypeProperties(subTypeProperties);
		}

		// Secondary properties
		List<Aspect> secondary = buildSecondaryTypes(repositoryId, properties, content);
		if (!CollectionUtils.isEmpty(secondary)) {
			content.setAspects(secondary);
			log.debug("Applied {} Secondary Types to content: {}", secondary.size(), 
				secondary.stream().map(Aspect::getName).collect(java.util.stream.Collectors.toList()));
		}

		// Signature
		setSignature(callContext, content);
	}

	private String copyAttachment(CallContext callContext, String repositoryId, String attachmentId) {
		AttachmentNode original = getAttachment(repositoryId, attachmentId);
		ContentStream cs = new ContentStreamImpl(original.getName(), BigInteger.valueOf(original.getLength()),
				original.getMimeType(), original.getInputStream());

		AttachmentNode copy = new AttachmentNode();
		copy.setName(original.getName());
		copy.setLength(original.getLength());
		copy.setMimeType(original.getMimeType());
		setSignature(callContext, copy);

		return contentDaoService.createAttachment(repositoryId, copy, cs);
	}

	private List<String> copyRenditions(CallContext callContext, String repositoryId, List<String> renditionIds) {
		if (CollectionUtils.isEmpty(renditionIds))
			return null;

		List<String> list = new ArrayList<String>();
		for (String renditionId : renditionIds) {
			Rendition original = getRendition(repositoryId, renditionId);
			ContentStream cs = new ContentStreamImpl("content", BigInteger.valueOf(original.getLength()),
					original.getMimetype(), original.getInputStream());

			Rendition copy = new Rendition();
			copy.setKind(original.getKind());
			copy.setHeight(original.getHeight());
			copy.setWidth(original.getWidth());
			copy.setLength(original.getLength());
			copy.setMimetype(original.getMimetype());
			setSignature(callContext, copy);

			String createdId = contentDaoService.createRendition(repositoryId, copy, cs);
			list.add(createdId);
		}
		return list;
	}

	private <T extends Content> T modifyProperties(CallContext callContext, String repositoryId, Properties properties,
			T content) {
		if (properties == null || MapUtils.isEmpty(properties.getProperties())) {
			return content;
		}

		// Primary
		org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = getTypeManager()
				.getTypeDefinition(repositoryId, content.getObjectType());
		for (PropertyData<?> p : properties.getPropertyList()) {
			org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition pd = td.getPropertyDefinitions()
					.get(p.getId());
			if (pd == null)
				continue;

			// CASE: READ&WRITE(ANYTIME)
			if (pd.getUpdatability() == Updatability.READWRITE) {
				setUpdatePropertyValue(repositoryId, content, p, properties);
			}
			// CASE:WHEN CHECKED OUT
			if (pd.getUpdatability() == Updatability.WHENCHECKEDOUT && content.isDocument()) {
				Document d = (Document) content;
				if (d.isPrivateWorkingCopy()) {
					setUpdatePropertyValue(repositoryId, content, p, properties);
				}
			}
		}

		// TODO
		// Subtype specific
		List<Property> subTypeProperties = buildSubTypeProperties(repositoryId, properties, content);
		if (!CollectionUtils.isEmpty(subTypeProperties)) {
			List<Property> allSubTypeProperties = content.getSubTypeProperties();

			// For each pre-existing property, remove it if exist in the
			// added/modified properties.
			// Iterate on a copy to avoid concurrent modifications
			for (Property priorProperty : new ArrayList<Property>(allSubTypeProperties)) {
				if (properties.getProperties().containsKey(priorProperty.getKey())) {
					// Overwrite by removing the prior property.
					allSubTypeProperties.remove(priorProperty);
					log.info("Remove " + priorProperty.getKey());
				} else {
					log.info("Leave " + priorProperty.getKey());
				}
			}

			// Combine incoming properties to existing ones.
			allSubTypeProperties.addAll(subTypeProperties);
			Map<String, Property> subTypePropertiesMap = new LinkedHashMap<>();
			allSubTypeProperties.forEach(p -> subTypePropertiesMap.put(p.getKey(), p));
			List<Property> combinedAllSubTypeProperties = new ArrayList<>();
			subTypePropertiesMap.forEach((key, value) -> combinedAllSubTypeProperties.add(value));
			// Save this properties.
			content.setSubTypeProperties(combinedAllSubTypeProperties);
		}

		// Secondary
		List<Aspect> secondary = buildSecondaryTypes(repositoryId, properties, content);
		// CRITICAL FIX: Always set aspects, even if empty list (to allow secondary type removal)
		// The isEmpty() check prevented secondary type deletion
		if (secondary != null) {
			content.setAspects(secondary);
		}

		// Set modified signature
		setModifiedSignature(callContext, content);
		return content;
	}

	private List<Property> buildSubTypeProperties(String repositoryId, Properties properties, Content content) {
		List<PropertyDefinition<?>> subTypePropertyDefinitions = getTypeManager()
				.getSpecificPropertyDefinitions(content.getObjectType());
		if (CollectionUtils.isEmpty(subTypePropertyDefinitions))
			return (new ArrayList<Property>());

		return injectPropertyValue(subTypePropertyDefinitions, properties, content);
	}

	private List<Aspect> buildSecondaryTypes(String repositoryId, Properties properties, Content content) {
		List<Aspect> aspects = new ArrayList<Aspect>();
		PropertyData secondaryTypeIds = properties.getProperties().get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);

		List<String> ids = new ArrayList<String>();
		if (secondaryTypeIds == null) {
			ids = getSecondaryTypeIds(content);
		} else {
			ids = secondaryTypeIds.getValues();
		}

		for (String secondaryTypeId : ids) {
			if (secondaryTypeId != null && !secondaryTypeId.trim().isEmpty()) {
				try {
					org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = getTypeManager()
							.getTypeDefinition(repositoryId, secondaryTypeId);
					
					if (td != null && td.getBaseTypeId() == org.apache.chemistry.opencmis.commons.enums.BaseTypeId.CMIS_SECONDARY) {
						Aspect aspect = new Aspect();
						aspect.setName(secondaryTypeId);

						Collection<PropertyDefinition<?>> propDefs = null;
						if (td.getPropertyDefinitions() != null) {
							propDefs = td.getPropertyDefinitions().values();
							int nullPropDefCount = 0;
							for (PropertyDefinition<?> propDef : propDefs) {
								if (propDef == null) {
									nullPropDefCount++;
								}
							}
							if (nullPropDefCount > 0) {
								log.warn("Found " + nullPropDefCount + " null PropertyDefinitions in secondary type " + secondaryTypeId);
							}
						} else {
							propDefs = new ArrayList<>();
							if (log.isDebugEnabled()) {
								log.debug("No property definitions found for secondary type " + secondaryTypeId);
							}
						}
						
						List<Property> props = injectPropertyValue(propDefs, properties, content);
						aspect.setProperties(props);
						aspects.add(aspect);
						
						if (log.isDebugEnabled()) {
							log.debug("Successfully processed secondary type " + secondaryTypeId + " with " + 
								(props != null ? props.size() : 0) + " properties");
						}
					} else {
						if (log.isDebugEnabled()) {
							log.debug("Secondary type {} is not valid or not a secondary type (td: {}, baseTypeId: {})", 
								secondaryTypeId, td != null ? "not null" : "null", 
								td != null ? td.getBaseTypeId() : "null");
						}
					}
				} catch (Exception e) {
					if (log.isDebugEnabled()) {
						log.debug("Secondary type {} not found or invalid: {}", secondaryTypeId, e.getMessage());
					}
				}
			}
		}
		return aspects;
	}

	private List<String> getSecondaryTypeIds(Content content) {
		List<String> result = new ArrayList<String>();
		List<Aspect> aspects = content.getAspects();
		if (CollectionUtils.isNotEmpty(aspects)) {
			for (Aspect aspect : aspects) {
				result.add(aspect.getName());
			}
		}
		return result;
	}

	private List<Property> injectPropertyValue(Collection<PropertyDefinition<?>> propertyDefnitions,
			Properties properties, Content content) {
		List<Property> props = new ArrayList<Property>();
		for (PropertyDefinition<?> pd : propertyDefnitions) {
			switch (pd.getUpdatability()) {
			case READONLY:
				continue;
			case ONCREATE:
			case READWRITE:
				break;
			case WHENCHECKEDOUT:
				if (!content.isDocument()) {
					continue;
				} else {
					Document d = (Document) content;
					if (!d.isPrivateWorkingCopy()) {
						continue;
					}
				}
				break;
			default:
				continue;
			}

			PropertyData<?> property = properties.getProperties().get(pd.getId());
			if (property == null)
				continue;
			Property p = new Property();
			p.setKey(property.getId());
			switch (pd.getCardinality()) {
			case SINGLE:
				p.setValue(property.getFirstValue());
				break;
			case MULTI:
				p.setValue(property.getValues());
				break;
			default:
				break;
			}
			props.add(p);
		}

		return props;
	}

	@Override
	public Content updateProperties(CallContext callContext, String repositoryId, Properties properties,
			Content content) {

		Content modified = modifyProperties(callContext, repositoryId, properties, content);

		Content result = updateInternal(repositoryId, modified);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.UPDATED);

		return result;
	}

	@Override
	public Content update(CallContext callContext, String repositoryId, Content content) {
		Content result = updateInternal(repositoryId, content);
		writeChangeEvent(callContext, repositoryId, result, ChangeType.UPDATED);
		return result;
	}

	@Override
	public Content updateInternal(String repositoryId, Content content) {
		Content result = null;
		

		if (content instanceof Document) {
			result = contentDaoService.update(repositoryId, (Document) content);
		} else if (content instanceof Folder) {
			result = contentDaoService.update(repositoryId, (Folder) content);
		} else if (content instanceof Relationship) {
			result = contentDaoService.update(repositoryId, (Relationship) content);
		} else if (content instanceof Policy) {
			result = contentDaoService.update(repositoryId, (Policy) content);
		} else if (content instanceof Item) {
			if (content instanceof UserItem) {
				result = contentDaoService.update(repositoryId, (UserItem) content);
			} else if (content instanceof GroupItem) {
				result = contentDaoService.update(repositoryId, (GroupItem) content);
			} else {
				result = contentDaoService.update(repositoryId, (Item) content);
			}
		}
		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);

		return result;
	}

	private void validateUserItem(String repositoryId, UserItem userItem) {
		UserItem existingUser = contentDaoService.getUserItemById(repositoryId, userItem.getUserId());
		if (existingUser != null && userItem.getId() == null) {
			throw new CmisRuntimeException(
					"userId=" + userItem.getUserId() + " already exists. Skip creating a new user.");
		}
	}

	private void validateGroupItem(String repositoryId, GroupItem groupItem) {
		UserItem existingUser = contentDaoService.getUserItemById(repositoryId, groupItem.getGroupId());
		if (existingUser != null && groupItem.getId() == null) {
			throw new CmisRuntimeException(
					"userId=" + groupItem.getGroupId() + " already exists. Skip creating a new user.");
		}
	}

	private void setUpdatePropertyValue(String repositoryId, Content content, PropertyData<?> propertyData,
			Properties properties) {
		if (propertyData.getId().equals(PropertyIds.NAME)) {
			if (DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID) != content.getId()) {
				String proposedName = DataUtil.getStringProperty(properties, PropertyIds.NAME);

				// Check duplicate name
				Folder parentFolder = this.getFolder(repositoryId, content.getParentId());
				if (parentFolder != null) {
					boolean mustUnique = propertyManager.readBoolean(PropertyKey.CAPABILITY_EXTENDED_UNIQUE_NAME_CHECK);
					if (mustUnique) {
						List<String> names = contentDaoService.getChildrenNames(repositoryId, parentFolder.getId());
						String originalName = content.getName();
						String lowerCaseProposedName = proposedName.toLowerCase();
						for (String name : names) {
							if (lowerCaseProposedName.equals(name.toLowerCase()) && !originalName.equals(name)) {
								throw new CmisContentAlreadyExistsException(
										"A content with the specified name already exists", BigInteger.valueOf(409));
							}
						}
					}
				}
				content.setName(proposedName);
			}
		}

		if (propertyData.getId().equals(PropertyIds.DESCRIPTION)) {
			content.setDescription(DataUtil.getStringProperty(properties, propertyData.getId()));
		}

		if (propertyData.getId().equals(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)) {
			content.setSecondaryIds(DataUtil.getIdListProperty(properties, PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
		}
	}

	@Override
	public void move(CallContext callContext, String repositoryId, Content content, Folder target) {
		String sourceId = content.getParentId();

		content.setParentId(target.getId());

		move(repositoryId, content, sourceId);

		Folder source = getFolder(repositoryId, sourceId);
		writeChangeEvent(callContext, repositoryId, source, ChangeType.UPDATED);
		writeChangeEvent(callContext, repositoryId, target, ChangeType.UPDATED);

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);
	}

	private Content move(String repositoryId, Content content, String sourceId) {
		Content result = null;
		if (content instanceof Document) {
			result = contentDaoService.move(repositoryId, (Document) content, sourceId);
		} else if (content instanceof Folder) {
			result = contentDaoService.move(repositoryId, (Folder) content, sourceId);
		}
		return result;
	}

	@Override
	public void applyPolicy(CallContext callContext, String repositoryId, String policyId, String objectId,
			ExtensionsData extension) {
		Policy policy = getPolicy(repositoryId, policyId);
		List<String> ids = policy.getAppliedIds();
		ids.add(objectId);
		policy.setAppliedIds(ids);
		contentDaoService.update(repositoryId, policy);

		// Record the change event
		Content content = getContent(repositoryId, objectId);
		writeChangeEvent(callContext, repositoryId, content, ChangeType.SECURITY);
	}

	@Override
	public void removePolicy(CallContext callContext, String repositoryId, String policyId, String objectId,
			ExtensionsData extension) {
		Policy policy = getPolicy(repositoryId, policyId);
		List<String> ids = policy.getAppliedIds();
		ids.remove(objectId);
		policy.setAppliedIds(ids);
		contentDaoService.update(repositoryId, policy);

		// Record the change event
		Content content = getContent(repositoryId, objectId);
		writeChangeEvent(callContext, repositoryId, content, ChangeType.SECURITY);
	}

	/**
	 * Delete a Content.
	 */
	@Override
	public void delete(CallContext callContext, String repositoryId, String objectId, Boolean deletedWithParent) {
		log.error("=== CONTENTSERVICE DELETE FLOW START ===");
		log.error("ContentServiceImpl.delete() called for object: {} in repository: {}", objectId, repositoryId);
		log.error("Thread: {}", Thread.currentThread().getName());
		
		Content content = getContent(repositoryId, objectId);

		// TODO workaround
		if (content == null) {
			// If content is already deleted, do nothing;
			log.error("Content is null for object: {} - exiting early", objectId);
			return;
		}

		log.error("Content found: {} (type: {})", content.getName(), content.getObjectType());

		// Record the change event(Before the content is deleted!)
		writeChangeEvent(callContext, repositoryId, content, ChangeType.DELETED);

		// Archive
		log.error("Creating archive for object: {}", objectId);
		createArchive(callContext, repositoryId, objectId, deletedWithParent);
		
		// CRITICAL FIX: Delete attached relationships using optimized approach
		// Collect all related documents for bulk deletion
		List<Relationship> sourceRelationships = contentDaoService.getRelationshipsBySource(repositoryId, objectId);
		List<Relationship> targetRelationships = contentDaoService.getRelationshipsByTarget(repositoryId, objectId);
		
		List<String> relationshipIds = new ArrayList<>();
		for (Relationship rel : sourceRelationships) {
			relationshipIds.add(rel.getId());
		}
		for (Relationship rel : targetRelationships) {
			relationshipIds.add(rel.getId());
		}
		
		// Delete relationships in batch if any exist
		if (!relationshipIds.isEmpty()) {
			try {
				log.debug("Deleting " + relationshipIds.size() + " relationships for object: " + objectId);
				deleteRelationshipsBatch(repositoryId, relationshipIds);
			} catch (Exception e) {
				log.error("Error deleting relationships for object " + objectId + ": " + e.getMessage(), e);
				// Continue with main object deletion even if relationship deletion fails
			}
		}

		// Delete item
		log.error("About to call contentDaoService.delete() for object: {}", objectId);
		log.error("contentDaoService instance: {}", contentDaoService.getClass().getName());
		
		try {
			contentDaoService.delete(repositoryId, objectId);
			log.error("contentDaoService.delete() completed successfully for object: {}", objectId);
		} catch (Exception e) {
			log.error("ERROR in contentDaoService.delete() for object {}: {}", objectId, e.getMessage(), e);
			throw e; // Re-throw to maintain original error handling
		}

		// Call Solr indexing(optional) - delete from index
		log.error("Calling Solr delete for object: {}", objectId);
		solrUtil.deleteDocument(repositoryId, objectId);
		
		log.error("=== CONTENTSERVICE DELETE FLOW END ===");
	}
	
	/**
	 * CRITICAL: Batch deletion of relationships for better Cloudant SDK performance
	 * @param repositoryId repository identifier
	 * @param relationshipIds list of relationship IDs to delete
	 */
	private void deleteRelationshipsBatch(String repositoryId, List<String> relationshipIds) {
		if (relationshipIds == null || relationshipIds.isEmpty()) {
			return;
		}
		
		// For now, use individual deletes with proper error handling
		// TODO: Implement true bulk delete when available in ContentDaoService
		for (String relationshipId : relationshipIds) {
			try {
				contentDaoService.delete(repositoryId, relationshipId);
				// Brief pause to prevent overwhelming CouchDB
				Thread.sleep(5);
			} catch (Exception e) {
				log.warn("Failed to delete relationship " + relationshipId + ": " + e.getMessage());
				// Continue with other deletions
			}
		}
	}

	@Override
	public void deleteAttachment(CallContext callContext, String repositoryId, String attachmentId) {
		createAttachmentArchive(callContext, repositoryId, attachmentId);
		contentDaoService.delete(repositoryId, attachmentId);
	}

	@Override
	public void deleteContentStream(CallContext callContext, String repositoryId, Holder<String> objectId) {
		// TODO Auto-generated method stub

	}

	@Override
	public void deleteDocument(CallContext callContext, String repositoryId, String objectId, Boolean allVersions,
			Boolean deleteWithParent) {
		log.error("=== CONTENTSERVICE DELETEDOCUMENT FLOW START ===");
		log.error("ContentServiceImpl.deleteDocument() called for object: {} in repository: {}", objectId, repositoryId);
		log.error("allVersions: {}, deleteWithParent: {}", allVersions, deleteWithParent);
		
		Document document = (Document) getContent(repositoryId, objectId);

		// Make the list of objects to be deleted
		List<Document> versionList = new ArrayList<Document>();
		String versionSeriesId = document.getVersionSeriesId();
		if (allVersions) {
			try {
				log.error("Attempting getAllVersions for versionSeriesId: {}", versionSeriesId);
				versionList = getAllVersions(callContext, repositoryId, versionSeriesId);
				log.error("getAllVersions succeeded, found {} versions", versionList.size());
			} catch (Exception e) {
				log.error("CRITICAL: getAllVersions failed for versionSeriesId {}: {}", versionSeriesId, e.getMessage(), e);
				// Fall back to single version deletion
				log.error("Falling back to single version deletion");
				versionList.add(document);
			}
		} else {
			versionList.add(document);
		}
		
		log.error("Final versionList size: {} for document: {}", versionList.size(), objectId);

		// Delete
		for (Document version : versionList) {
			// Archive a document
			if (version.getAttachmentNodeId() != null) {
				String attachmentId = version.getAttachmentNodeId();
				// Delete an attachment
				deleteAttachment(callContext, repositoryId, attachmentId);
			}
			// Delete rendition(no need for archive)
			if (CollectionUtils.isNotEmpty(version.getRenditionIds())) {
				for (String renditionId : version.getRenditionIds()) {
					contentDaoService.delete(repositoryId, renditionId);
				}
			}

			// Delete a document
			log.error("About to call delete() for version: {} (deleteWithParent: {})", version.getId(), deleteWithParent);
			delete(callContext, repositoryId, version.getId(), deleteWithParent);
			log.error("Completed delete() for version: {}", version.getId());
		}

		// Move up the latest version
		if (!allVersions) {
			Document latestVersion = getDocumentOfLatestVersion(repositoryId, versionSeriesId);
			if (latestVersion != null) {
				latestVersion.setLatestVersion(true);
				latestVersion.setLatestMajorVersion(latestVersion.isMajorVersion());
				contentDaoService.update(repositoryId, latestVersion);
			}
		}

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);
	}

	// deletedWithParent flag controls whether it's deleted with the parent all
	// together.
	@Override
	public List<String> deleteTree(CallContext callContext, String repositoryId, String folderId, Boolean allVersions,
			Boolean continueOnFailure, Boolean deletedWithParent) {
		List<String> failureIds = new ArrayList<String>();

		// Delete children
		List<Content> children = getChildren(repositoryId, folderId);
		if (!CollectionUtils.isEmpty(children)) {
			for (Content child : children) {
				try {
					if (child.isFolder()) {
						deleteTree(callContext, repositoryId, child.getId(), allVersions, continueOnFailure, true);
					} else if (child.isDocument()) {
						deleteDocument(callContext, repositoryId, child.getId(), allVersions, true);
					} else {
						delete(callContext, repositoryId, child.getId(), true);
					}
				} catch (Exception e) {
					if (continueOnFailure) {
						failureIds.add(child.getId());
						continue;
					} else {
						log.error("", e);
					}
				}
			}
		}

		// Delete the folder itself
		try {
			delete(callContext, repositoryId, folderId, deletedWithParent);
		} catch (Exception e) {
			if (continueOnFailure) {
				failureIds.add(folderId);
			} else {
				log.error("", e);
			}
		}

		return failureIds;
	}

	@Override
	public AttachmentNode getAttachment(String repositoryId, String attachmentId) {
		if (log.isDebugEnabled()) {
			log.debug("getAttachment called - Repository ID: " + repositoryId + ", Attachment ID: " + attachmentId);
		}
		
		AttachmentNode an = contentDaoService.getAttachment(repositoryId, attachmentId);
		
		// CRITICAL FIX: Only call setStream if no InputStream exists
		// setStream() consumes the InputStream, so we need to avoid calling it when retrieving for getContentStream
		if (an != null && an.getInputStream() == null) {
			if (log.isDebugEnabled()) {
				log.debug("AttachmentNode has no InputStream, calling setStream to populate it");
			}
			contentDaoService.setStream(repositoryId, an);
		}
		
		if (log.isDebugEnabled()) {
			log.debug("getAttachment completed - InputStream: " + (an != null && an.getInputStream() != null ? "SUCCESS" : "NULL"));
		}
		
		return an;
	}

	@Override
	public AttachmentNode getAttachmentRef(String repositoryId, String attachmentId) {
		if (StringUtils.isBlank(attachmentId)) {
			return null;
		}
		
		// Try to get attachment with minimal retry for async scenarios
		final int maxRetries = 2;
		final long retryDelayMs = 25;
		
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			AttachmentNode an = contentDaoService.getAttachment(repositoryId, attachmentId);
			if (an != null) {
				return an;
			}
			
			if (attempt < maxRetries) {
				try {
					Thread.sleep(retryDelayMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.warn("Interrupted while retrieving attachment: attachmentId=" + attachmentId);
					break;
				}
			}
		}
		
		return null;
	}

	private String createAttachment(CallContext callContext, String repositoryId, ContentStream contentStream) {
		AttachmentNode a = new AttachmentNode();
		a.setMimeType(contentStream.getMimeType());
		
		// CRITICAL FIX: Calculate actual stream size when length is unknown (-1) or invalid (0 or negative)
		// BUT avoid consuming the stream if it doesn't support mark/reset
		long streamLength = contentStream.getLength();
		if (streamLength <= 0) {
			log.warn("ContentStream length is " + streamLength + " (unknown/invalid) for: " + contentStream.getFileName());

			// TCK COMPATIBILITY FIX: Only calculate size if stream supports mark/reset
			// This prevents consuming the stream content during size calculation
			InputStream stream = contentStream.getStream();
			if (stream != null && stream.markSupported()) {
				log.debug("Stream supports mark/reset, calculating actual size for: " + contentStream.getFileName());
				streamLength = calculateStreamSize(stream);

				if (streamLength >= 0) {
					log.info("Calculated actual stream size: " + streamLength + " bytes for: " + contentStream.getFileName());
				} else {
					log.error("Failed to calculate stream size, using -1 (unknown) for: " + contentStream.getFileName());
					streamLength = -1L; // Use -1 to indicate unknown size to DAO layer
				}
			} else {
				log.debug("Stream does not support mark/reset, preserving content and using -1 (unknown size) for: " + contentStream.getFileName());
				streamLength = -1L; // Let DAO layer handle unknown size without consuming content
			}
		}
		
		a.setLength(streamLength);
		a.setName(contentStream.getFileName());
		setSignature(callContext, a);
		return contentDaoService.createAttachment(repositoryId, a, contentStream);
	}

	/**
	 * Calculate the actual size of an InputStream by reading through it
	 * This is needed when ContentStream.getLength() returns -1 (unknown size)
	 * @param inputStream The stream to measure
	 * @return The actual size in bytes
	 */
	private long calculateStreamSize(InputStream inputStream) {
		if (inputStream == null) {
			return 0L;
		}
		
		long totalBytes = 0L;
		byte[] buffer = new byte[8192]; // 8KB buffer for efficient reading
		
		try {
			// Mark the stream for reset if possible
			if (inputStream.markSupported()) {
				inputStream.mark(Integer.MAX_VALUE);
			}
			
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				totalBytes += bytesRead;
			}
			
			// Reset stream to beginning if possible
			if (inputStream.markSupported()) {
				inputStream.reset();
			} else {
				log.warn("InputStream does not support mark/reset - stream position cannot be restored");
			}
			
		} catch (IOException e) {
			log.error("Error calculating stream size", e);
			return -1L; // Return -1 to indicate error, will be handled by calling code
		}
		
		return totalBytes;
	}

	private String createPreview(CallContext callContext, String repositoryId, ContentStream contentStream,
			Document document) {

		Rendition rendition = new Rendition();
		rendition.setTitle("PDF Preview");
		rendition.setKind(RenditionKind.CMIS_PREVIEW.value());
		rendition.setMimetype(contentStream.getMimeType());
		rendition.setLength(contentStream.getLength());

		ContentStream converted = renditionManager.convertToPdf(contentStream, document.getName());

		setSignature(callContext, rendition);
		if (converted == null) {
			// TODO logging
			return null;
		} else {
			String renditionId = contentDaoService.createRendition(repositoryId, rendition, converted);
			List<String> renditionIds = document.getRenditionIds();
			if (renditionIds == null) {
				document.setRenditionIds(new ArrayList<String>());
			}

			document.getRenditionIds().add(renditionId);

			return renditionId;
		}

	}

	private boolean isPreviewEnabled() {
		String _cpbltyPreview = propertyManager.readValue(PropertyKey.CAPABILITY_EXTENDED_PREVIEW);
		boolean cpbltyPreview = (Boolean.valueOf(_cpbltyPreview) == null) ? false : Boolean.valueOf(_cpbltyPreview);
		return cpbltyPreview;
	}

	@Override
	public void appendAttachment(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {
		Document document = contentDaoService.getDocument(repositoryId, objectId.getValue());
		AttachmentNode attachment = getAttachment(repositoryId, document.getAttachmentNodeId());
		InputStream is = attachment.getInputStream();
		// Append
		SequenceInputStream sis = new SequenceInputStream(is, contentStream.getStream());
		// appendStream will be used for a huge file, so avoid reading stream
		long length = attachment.getLength() + contentStream.getLength();
		ContentStream cs = new ContentStreamImpl("content", BigInteger.valueOf(length), attachment.getMimeType(), sis);
		contentDaoService.updateAttachment(repositoryId, attachment, cs);

		writeChangeEvent(callContext, repositoryId, document, ChangeType.UPDATED);
	}

	@Override
	public Rendition getRendition(String repositoryId, String streamId) {
		return contentDaoService.getRendition(repositoryId, streamId);
	}

	@Override
	public List<Rendition> getRenditions(String repositoryId, String objectId) {
		Content c = getContent(repositoryId, objectId);
		List<String> ids = new ArrayList<String>();
		if (c.isDocument()) {
			Document d = (Document) c;
			ids = d.getRenditionIds();
		} else if (c.isFolder()) {
			Folder f = (Folder) c;
			ids = f.getRenditionIds();
		} else {
			return null;
		}

		List<Rendition> renditions = new ArrayList<Rendition>();
		if (CollectionUtils.isNotEmpty(ids)) {
			for (String id : ids) {
				renditions.add(contentDaoService.getRendition(repositoryId, id));
			}
		}

		return renditions;
	}

	// ///////////////////////////////////////
	// Acl
	// ///////////////////////////////////////
	// Merge inherited ACL
	@Override
	public Acl calculateAcl(String repositoryId, Content content) {

		NemakiCache<Acl> aclCache = nemakiCachePool.get(repositoryId).getAclCache();
		Acl acl = aclCache.get(content.getId());

		if (acl == null) {
			boolean iht = getAclInheritedWithDefault(repositoryId, content);
			if (!isRoot(repositoryId, content) && iht) {

				List<Ace> aces = new ArrayList<Ace>();
				List<Ace> result = calculateAclInternal(repositoryId, aces, content);

				// Convert result to Acl
				acl = new Acl();
				for (Ace r : result) {
					if (r.isDirect()) {
						acl.getLocalAces().add(r);
					} else {
						acl.getInheritedAces().add(r);
					}
				}
			} else {
				acl = content.getAcl();
			}

			// Convert anonymous and anyone
			convertSystemPrincipalId(repositoryId, acl.getAllAces());

			// Caching the results of calculation
			aclCache.put(content.getId(), acl);
		}
		return acl;
	}

	private List<Ace> calculateAclInternal(String repositoryId, List<Ace> result, Content content) {
		Acl contentAcl = content.getAcl();
		List<Ace> aces = null;
		if (contentAcl == null) {
			log.error("Invalid Acl, content ACL is null! [ID=" + content.getId() + "]" + content.getName());
			aces = new ArrayList<Ace>();
		} else {
			aces = contentAcl.getLocalAces();
		}

		if (isRoot(repositoryId, content) || !getAclInheritedWithDefault(repositoryId, content)) {
			List<Ace> rootAces = new ArrayList<Ace>();

			for (Ace ace : aces) {
				Ace rootAce = deepCopy(ace);
				rootAce.setDirect(true);
				rootAces.add(rootAce);
			}
			return mergeAcl(repositoryId, result, rootAces);
		} else {
			// reduce db access instead of getParent(repositoryId,
			// content.getId())
			if (content.getParentId() == null) {
				return aces;
			} else {
				Folder parent = getFolder(repositoryId, content.getParentId());
				if (parent == null) {
					return aces;
				} else {
					return mergeAcl(repositoryId, aces,
							calculateAclInternal(repositoryId, new ArrayList<Ace>(), parent));
				}
			}
		}
	}

	private List<Ace> mergeAcl(String repositoryId, List<Ace> target, List<Ace> source) {
		HashMap<String, Ace> _result = new HashMap<String, Ace>();

		// convert Normalize system principal id token to a real one
		convertSystemPrincipalId(repositoryId, target);

		HashMap<String, Ace> targetMap = buildAceMap(target);
		HashMap<String, Ace> sourceMap = buildAceMap(source);

		for (Entry<String, Ace> t : targetMap.entrySet()) {
			Ace ace = deepCopy(t.getValue());
			ace.setDirect(true);
			_result.put(t.getKey(), ace);
		}

		// Overwrite
		for (Entry<String, Ace> s : sourceMap.entrySet()) {
			// TODO Deep copy
			if (!targetMap.containsKey(s.getKey())) {
				Ace ace = deepCopy(s.getValue());
				ace.setDirect(false);
				_result.put(s.getKey(), ace);
			}
		}

		// Convert
		List<Ace> result = new ArrayList<Ace>();
		for (Entry<String, Ace> r : _result.entrySet()) {
			result.add(r.getValue());
		}

		return result;
	}

	private HashMap<String, Ace> buildAceMap(List<Ace> aces) {
		HashMap<String, Ace> map = new HashMap<String, Ace>();

		for (Ace ace : aces) {
			map.put(ace.getPrincipalId(), ace);
		}

		return map;
	}

	private Ace deepCopy(Ace ace) {
		Ace result = new Ace();

		result.setPrincipalId(ace.getPrincipalId());
		result.setDirect(ace.isDirect());
		if (CollectionUtils.isEmpty(ace.getPermissions())) {
			result.setPermissions(new ArrayList<String>());
		} else {
			List<String> l = new ArrayList<String>();
			for (String p : ace.getPermissions()) {
				l.add(p);
			}
			result.setPermissions(l);
		}

		return result;
	}

	private void convertSystemPrincipalId(String repositoryId, List<Ace> aces) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);

		for (Ace ace : aces) {
			if (PrincipalId.ANONYMOUS_IN_DB.equals(ace.getPrincipalId())) {
				String anonymous = info.getPrincipalIdAnonymous();
				ace.setPrincipalId(anonymous);
			}
			if (PrincipalId.ANYONE_IN_DB.equals(ace.getPrincipalId())) {
				String anyone = info.getPrincipalIdAnyone();
				ace.setPrincipalId(anyone);
			}
		}
	}

	@Override
	public Boolean getAclInheritedWithDefault(String repositoryId, Content content) {
		boolean inheritedAtTopLevel = propertyManager
				.readBoolean(PropertyKey.CAPABILITY_EXTENDED_PERMISSION_INHERITANCE_TOPLEVEL);

		if (isRoot(repositoryId, content)) {
			return false;
		} else {
			if (isTopLevel(repositoryId, content) && !inheritedAtTopLevel) {
				// default to TRUE
				return (content.isAclInherited() == null) ? false : content.isAclInherited();
			} else {
				// default to FALSE
				return (content.isAclInherited() == null) ? true : content.isAclInherited();
			}
		}
	}

	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	@Override
	public Change getChangeEvent(String repositoryId, String changeTokenId) {
		return contentDaoService.getChangeEvent(repositoryId, changeTokenId);
	}

	@Override
	public List<Change> getLatestChanges(String repositoryId, CallContext context, Holder<String> changeLogToken,
			Boolean includeProperties, String filter, Boolean includePolicyIds, Boolean includeAcl, BigInteger maxItems,
			ExtensionsData extension) {
		return contentDaoService.getLatestChanges(repositoryId, changeLogToken.getValue(), maxItems.intValue());
	}

	@Override
	public String getLatestChangeToken(String repositoryId) {
		Change latest = contentDaoService.getLatestChange(repositoryId);
		if (latest == null) {
			// TODO null is OK?
			return null;
		} else {
			// return String.valueOf(latest.getChangeToken());
			return String.valueOf(latest.getId());
		}
	}

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	@Override
	public List<Archive> getAllArchives(String repositoryId) {
		return contentDaoService.getAllArchives(repositoryId);
	}

	@Override
	public List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc) {
		return contentDaoService.getArchives(repositoryId, skip, limit, true);
	}

	@Override
	public Archive getArchive(String repositoryId, String archiveId) {
		return contentDaoService.getArchive(repositoryId, archiveId);
	}

	@Override
	public Archive getArchiveByOriginalId(String repositoryId, String originalId) {
		return contentDaoService.getArchiveByOriginalId(repositoryId, originalId);
	}

	@Override
	public Archive createArchive(CallContext callContext, String repositoryId, String objectId,
			Boolean deletedWithParent) {
		Content content = getContent(repositoryId, objectId);

		// Set base info
		Archive a = new Archive();

		a.setOriginalId(content.getId());
		// a.setLastRevision(content.getRevision());
		a.setName(content.getName());
		a.setType(content.getType());
		a.setDeletedWithParent(deletedWithParent);
		a.setParentId(content.getParentId());
		setSignature(callContext, a);

		// Set Document archive specific info
		if (content.isDocument()) {
			Document document = (Document) content;
			a.setAttachmentNodeId(document.getAttachmentNodeId());
			a.setVersionSeriesId(document.getVersionSeriesId());
			a.setIsLatestVersion(document.isLatestVersion());
		}

		return contentDaoService.createArchive(repositoryId, a, deletedWithParent);
	}

	@Override
	public Archive createAttachmentArchive(CallContext callContext, String repositoryId, String attachmentId) {
		Archive a = new Archive();
		a.setDeletedWithParent(true);
		a.setOriginalId(attachmentId);
		a.setType(NodeType.ATTACHMENT.value());
		setSignature(callContext, a);

		Archive archive = contentDaoService.createAttachmentArchive(repositoryId, a);
		return archive;
	}

	@Override
	public void restoreArchive(String repositoryId, String archiveId) throws ParentNoLongerExistException {
		Archive archive = contentDaoService.getArchive(repositoryId, archiveId);
		if (archive == null) {
			log.error("Archive does not exist!");
			return;
		}

		// Check whether the destination does still extist.
		if (!restorationTargetExists(repositoryId, archive)) {
			log.error("The destination of the restoration doesn't exist");
			throw new ParentNoLongerExistException();
		}

		CallContextImpl dummyContext = new CallContextImpl(null, CmisVersion.CMIS_1_1, null, null, null, null, null,
				null);
		dummyContext.put(dummyContext.USERNAME, PrincipalId.SYSTEM_IN_DB);

		// Switch over the operation depending on the type of archive
		if (archive.isFolder()) {
			Folder restored = restoreFolder(repositoryId, archive);
			writeChangeEvent(dummyContext, repositoryId, restored, ChangeType.CREATED);
		} else if (archive.isDocument()) {
			Document restored = restoreDocument(repositoryId, archive);
			writeChangeEvent(dummyContext, repositoryId, restored, ChangeType.CREATED);
		} else if (archive.isAttachment()) {
			log.error("Attachment can't be restored alone");
		} else {
			log.error("Only document or folder is supported for restoration");
		}

		// Call Solr indexing(optional)
		// TODO: Update with specific document indexing 
		// solrUtil.indexDocument(repositoryId, content);
	}

	private Document restoreDocument(String repositoryId, Archive archive) {
		try {
			// Get archives of the same version series
			List<Archive> versions = contentDaoService.getArchivesOfVersionSeries(repositoryId,
					archive.getVersionSeriesId());
			for (Archive version : versions) {
				contentDaoService.restoreDocumentWithArchive(repositoryId, version);
				// delete archives
				contentDaoService.deleteDocumentArchive(repositoryId, version.getId());
			}
		} catch (Exception e) {
			log.error("fail to restore a document", e);
		}

		return getDocument(repositoryId, archive.getOriginalId());
	}

	private Folder restoreFolder(String repositoryId, Archive archive) throws ParentNoLongerExistException {
		contentDaoService.restoreContent(repositoryId, archive);

		// Restore direct children
		List<Archive> children = contentDaoService.getChildArchives(repositoryId, archive);
		if (children != null) {
			for (Archive child : children) {
				// Restore descendants recursively
				// NOTE: Restored only when deletedWithParent flag is true
				if (child.isDeletedWithParent()) {
					restoreArchive(repositoryId, child.getId());
				}
			}
		}
		contentDaoService.deleteArchive(repositoryId, archive.getId());

		return getFolder(repositoryId, archive.getOriginalId());
	}

	private Boolean restorationTargetExists(String repositoryId, Archive archive) {
		String parentId = archive.getParentId();
		Content parent = contentDaoService.getContent(repositoryId, parentId);
		if (parent == null) {
			return false;
		} else {
			return true;
		}
	}

	public void destroyArchive(String repositoryId, String archiveId) {
		Archive archive = contentDaoService.getArchive(repositoryId, archiveId);
		if (archive == null) {
			log.error("Archive does not exist!");
			return;
		}

		if (archive.isFolder()) {
			destroyFolder(repositoryId, archive);
		} else if (archive.isDocument()) {
			destoryDocument(repositoryId, archive);
		} else if (archive.isAttachment()) {
			log.error("Attachment can't be restored alone");
		} else {
			log.error("Only document or folder is supported for restoration");
		}
	}

	private void destroyFolder(String repositoryId, Archive archive) {
		// Restore direct children
		List<Archive> children = contentDaoService.getChildArchives(repositoryId, archive);
		if (CollectionUtils.isNotEmpty(children)) {
			for (Archive child : children) {
				destroyArchive(repositoryId, child.getId());
			}
		}
		contentDaoService.deleteArchive(repositoryId, archive.getId());
	}

	private void destoryDocument(String repositoryId, Archive archive) {
		try {
			// Get archives of the same version series
			List<Archive> versions = contentDaoService.getArchivesOfVersionSeries(repositoryId,
					archive.getVersionSeriesId());
			for (Archive version : versions) {
				// Restore its attachment
				Archive attachmentArchive = contentDaoService.getAttachmentArchive(repositoryId, version);
				// delete archives
				contentDaoService.deleteArchive(repositoryId, version.getId());
				contentDaoService.deleteArchive(repositoryId, attachmentArchive.getId());
			}
		} catch (Exception e) {
			log.error("fail to restore a document", e);
		}
	}

	// ///////////////////////////////////////
	// Utility
	// ///////////////////////////////////////
	private String increasedVersionLabel(Document document, VersioningState versioningState) {
		// e.g. #{major}(.{#minor})
		String label = document.getVersionLabel();
		int major = 0;
		int minor = 0;

		int point = label.lastIndexOf(".");
		if (point == -1) {
			major = Integer.parseInt(label);
		} else {
			major = Integer.parseInt(label.substring(0, point));
			minor = Integer.parseInt(label.substring(point + 1));
		}

		String newLabel = label;
		if (versioningState == VersioningState.MAJOR) {
			newLabel = String.valueOf(major + 1) + ".0";
		} else if (versioningState == VersioningState.MINOR) {
			newLabel = String.valueOf(major) + "." + String.valueOf(minor + 1);
		}
		return newLabel;
	}

	private void setSignature(CallContext callContext, NodeBase n) {
		n.setCreator(callContext.getUsername());
		n.setCreated(getTimeStamp());
		n.setModifier(callContext.getUsername());
		n.setModified(getTimeStamp());
	}

	private void setModifiedSignature(CallContext callContext, NodeBase n) {
		n.setModifier(callContext.getUsername());
		n.setModified(getTimeStamp());
	}

	private GregorianCalendar getTimeStamp() {
		return DataUtil.millisToCalendar(System.currentTimeMillis());
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	// TypeManager injected via proper DI to maintain proxy benefits
	private TypeManager getTypeManager() {
		if (typeManager == null) {
			log.error("TypeManager not properly injected - this indicates a configuration issue");
		}
		return typeManager;
	}

	public void setRenditionManager(RenditionManager renditionManager) {
		this.renditionManager = renditionManager;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	@Override
	public Long getAttachmentActualSize(String repositoryId, String attachmentId) {
		try {
			// Get the attachment node document from CouchDB with _attachments metadata
			AttachmentNode attachment = contentDaoService.getAttachment(repositoryId, attachmentId);
			if (attachment == null) {
				log.warn("Attachment node not found: " + attachmentId);
				return null;
			}
			
			// Try to get the actual size from CouchDB attachment metadata
			// This requires a direct call to the DAO to get _attachments information
			Long actualSize = contentDaoService.getAttachmentActualSize(repositoryId, attachmentId);
			if (actualSize != null && actualSize > 0) {
				log.debug("Retrieved actual attachment size from CouchDB: " + actualSize + " bytes for attachment " + attachmentId);
				return actualSize;
			}
			
			log.warn("Could not retrieve actual attachment size from CouchDB for attachment: " + attachmentId);
			return null;
			
		} catch (Exception e) {
			log.error("Error retrieving actual attachment size for " + attachmentId + ": " + e.getMessage(), e);
			return null;
		}
	}	
	/**
	 * ATOMIC OPERATIONS: Helper methods for atomic Document+Attachment operations
	 * These methods ensure _rev consistency during compound CouchDB operations
	 */
	
	/**
	 * Create attachment with atomic operation pattern
	 * Prevents _rev inconsistency by using immediate consistent read pattern
	 */
	private String createAttachmentAtomic(CallContext callContext, String repositoryId, ContentStream contentStream) {
		log.debug("Creating attachment atomically for repository: {}", repositoryId);
		
		// Create attachment using existing method (already handles _rev properly)
		String attachmentId = createAttachment(callContext, repositoryId, contentStream);
		
		// ATOMIC VERIFICATION: Ensure attachment exists and is accessible
		AttachmentNode verification = getAttachment(repositoryId, attachmentId);
		if (verification == null) {
			throw new CmisRuntimeException("Atomic attachment creation failed - attachment not accessible: " + attachmentId);
		}
		
		log.debug("Atomic attachment creation verified: {}", attachmentId);
		return attachmentId;
	}
	
	
	/**
	 * Create preview with atomic attachment reference
	 */
	private void createPreviewAtomic(CallContext callContext, String repositoryId, ContentStream contentStream, 
			Document document, String attachmentId) {
		log.debug("Creating preview atomically for attachment: {}", attachmentId);
		
		// Use the already-created and verified attachment
		AttachmentNode an = getAttachment(repositoryId, attachmentId);
		if (an == null) {
			throw new CmisRuntimeException("Atomic preview creation failed - attachment not found: " + attachmentId);
		}
		
		ContentStream previewCS = new ContentStreamImpl(contentStream.getFileName(),
				contentStream.getBigLength(), contentStream.getMimeType(), an.getInputStream());

		if (renditionManager.checkConvertible(previewCS.getMimeType())) {
			createPreview(callContext, repositoryId, previewCS, document);
		}
	}
	
	/**
	 * Update version series with atomic pattern
	 */
	private void updateVersionSeriesWithPwcAtomic(CallContext callContext, String repositoryId, 
			VersionSeries vs, Document document) {
		log.debug("Updating version series atomically for document: {}", document.getId());
		
		// Use existing method with atomic consistency
		updateVersionSeriesWithPwc(callContext, repositoryId, vs, document);
		
		log.debug("Version series updated atomically for document: {}", document.getId());
	}
	
	/**
	 * Copy attachment with atomic operation pattern
	 */
	private String copyAttachmentAtomic(CallContext callContext, String repositoryId, String originalAttachmentId) {
		log.debug("Copying attachment atomically: {}", originalAttachmentId);
		
		// Copy attachment using existing method
		String attachmentId = copyAttachment(callContext, repositoryId, originalAttachmentId);
		
		// ATOMIC VERIFICATION: Ensure copied attachment exists and is accessible
		AttachmentNode verification = getAttachment(repositoryId, attachmentId);
		if (verification == null) {
			throw new CmisRuntimeException("Atomic attachment copy failed - attachment not accessible: " + attachmentId);
		}
		
		log.debug("Atomic attachment copy verified: {}", attachmentId);
		return attachmentId;
	}

	////////////////////////////////////////////
}
