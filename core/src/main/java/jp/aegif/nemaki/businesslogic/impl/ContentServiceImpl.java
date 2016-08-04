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

import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
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
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CmisPermission;
import jp.aegif.nemaki.util.constant.NodeType;
import jp.aegif.nemaki.util.constant.PrincipalId;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.RenditionKind;

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
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Node Service implementation
 *
 * @author linzhixing
 *
 */
public class ContentServiceImpl implements ContentService {

	private RepositoryInfoMap repositoryInfoMap;
	private ContentDaoService contentDaoService;
	private TypeManager typeManager;
	private RenditionManager renditionManager;
	private PropertyManager propertyManager;
	private SolrUtil solrUtil;

	private static final Log log = LogFactory.getLog(ContentServiceImpl.class);
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
	public boolean isTopLevel(String repositoryId, Content content){
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

		if (content.isDocument()) {
			return contentDaoService.getDocument(repositoryId, content.getId());
		} else if (content.isFolder()) {
			return contentDaoService.getFolder(repositoryId, content.getId());
		} else if (content.isRelationship()) {
			return contentDaoService.getRelationship(repositoryId, content.getId());
		} else if (content.isPolicy()) {
			return contentDaoService.getPolicy(repositoryId, content.getId());
		} else if (content.isItem()) {
			return contentDaoService.getItem(repositoryId, content.getId());
		} else {
			return null;
		}
	}

	/**
	 * Get the pieces of content available at that path.
	 *
	 * @throws CmisException
	 */
	@Override
	public Content getContentByPath(String repositoryId, String path) {
		List<String> splittedPath = splitLeafPathSegment(path);
		String rootId = repositoryInfoMap.get(repositoryId).getRootFolderId();

		if (splittedPath.size() <= 0) {
			return null;
		} else if (splittedPath.size() == 1) {
			if (!splittedPath.get(0).equals(PATH_SEPARATOR))
				return null;
			// root
			return contentDaoService.getFolder(repositoryId, rootId);
		} else {
			Content content = contentDaoService.getFolder(repositoryId, rootId);
			// Get the the leaf node
			for (int i = 1; i < splittedPath.size(); i++) {
				String nodeName = splittedPath.get(i);
				if (content == null) {
					log.warn("node '" + nodeName + "' in  path '" + path + "' is not found.");
					return null;
				} else {
					Content child = contentDaoService.getChildByName(repositoryId, content.getId(), nodeName);
					content = child;
				}
			}
			
			//return
			if(content == null){
				return null;
			}else{
				return getContent(repositoryId, content.getId());
			}
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
		Content content = contentDaoService.getContent(repositoryId, objectId);
		return getFolder(repositoryId, content.getParentId());
	}

	/**
	 * Get children contents in a given folder
	 */
	@Override
	public List<Content> getChildren(String repositoryId, String folderId) {
		List<Content> children = new ArrayList<Content>();

		List<Content> indices = contentDaoService.getChildren(repositoryId, folderId);
		if (CollectionUtils.isEmpty(indices))
			return null;

		//TODO getを重複して行う必要なし
		for (Content c : indices) {
			if (c.isDocument()) {
				Document d = contentDaoService.getDocument(repositoryId, c.getId());
				children.add(d);
			} else if (c.isFolder()) {
				Folder f = contentDaoService.getFolder(repositoryId, c.getId());
				children.add(f);
			} else if (c.isPolicy()) {
				Policy p = contentDaoService.getPolicy(repositoryId, c.getId());
				children.add(p);
			} else if (c.isItem()) {
				Item i = contentDaoService.getItem(repositoryId, c.getId());
				children.add(i);
			}
		}
		return children;
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
	public String calculatePath(String repositoryId, Content content) {
		List<String> path = calculatePathInternal(new ArrayList<String>(), content, repositoryId);
		path.remove(0);
		return PATH_SEPARATOR + StringUtils.join(path, PATH_SEPARATOR);
	}

	private List<String> calculatePathInternal(List<String> path, Content content, String repositoryId) {
		path.add(0, content.getName());

		if (isRoot(repositoryId, content)) {
			return path;
		} else {
			Content parent = getParent(repositoryId, content.getId());
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

	private String writeChangeEvent(CallContext callContext, String repositoryId, Content content,
			ChangeType changeType) {

		return writeChangeEvent(callContext,repositoryId,content, null, changeType );
	}

	public String writeChangeEvent(CallContext callContext, String repositoryId, Content content,
			Acl acl, ChangeType changeType) {
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
		case DELETED:
			change.setTime(content.getCreated());
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

		// Create a new change event
		Change created = contentDaoService.create(repositoryId, change);

		// Update change token of the content
		content.setChangeToken(created.getId());

		update(repositoryId, content);

		return change.getToken();

	}



	private String generateChangeToken(NodeBase node) {
		return String.valueOf(node.getCreated().getTimeInMillis());
	}

	// TODO Create a rendition
	@Override
	public Document createDocument(CallContext callContext, String repositoryId, Properties properties,
			Folder parentFolder, ContentStream contentStream, VersioningState versioningState, String versionSeriesId) {
		Document d = buildNewBasicDocument(callContext, repositoryId, properties, parentFolder);

		// Check contentStreamAllowed
		DocumentTypeDefinition tdf = (DocumentTypeDefinition) (typeManager.getTypeDefinition(repositoryId, d));

		ContentStreamAllowed csa = tdf.getContentStreamAllowed();
		if (csa == ContentStreamAllowed.REQUIRED || csa == ContentStreamAllowed.ALLOWED && contentStream != null) {

			// Prepare ContentStream(to read it twice)
			// Map<String,ContentStream> contentStreamMap =
			// copyContentStream(contentStream);

			// Create Attachment node
			String attachmentId = createAttachment(callContext, repositoryId, contentStream);
			d.setAttachmentNodeId(attachmentId);

			// Create Renditions
			if (isPreviewEnabled()) {
				try {
					AttachmentNode an = getAttachment(repositoryId, attachmentId);
					ContentStream previewCS = new ContentStreamImpl(contentStream.getFileName(), contentStream
							.getBigLength(), contentStream.getMimeType(), an.getInputStream());

					// ContentStream previewCS =
					// contentStreamMap.get("preview");
					if (renditionManager.checkConvertible(previewCS.getMimeType())) {
						createPreview(callContext, repositoryId, previewCS, d);
					}
				} catch (Exception ex) {
					// not stop follow sequence
					log.error(ex);
				}
			}
		}

		// Set version properties
		VersionSeries vs = setVersionProperties(callContext, repositoryId, versioningState, d);

		// Create
		Document document = contentDaoService.create(repositoryId, d);

		// Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
		if (versioningState == VersioningState.CHECKEDOUT) {
			updateVersionSeriesWithPwc(callContext, repositoryId, vs, document);
		}

		// Write change event
		writeChangeEvent(callContext, repositoryId, document, ChangeType.CREATED);

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

		return document;
	}

	@Override
	public Document createDocumentFromSource(CallContext callContext, String repositoryId, Properties properties,
			Folder target, Document original, VersioningState versioningState, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces) {
		Document copy = buildCopyDocumentWithBasicProperties(callContext, original);

		String attachmentId = copyAttachment(callContext, repositoryId, original.getAttachmentNodeId());
		copy.setAttachmentNodeId(attachmentId);

		setVersionProperties(callContext, repositoryId, versioningState, copy);

		copy.setParentId(target.getId());
		// Set updated properties
		updateProperties(callContext, repositoryId, properties, copy);
		setSignature(callContext, copy);

		// Create
		Document result = contentDaoService.create(repositoryId, copy);

		// Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
		if (versioningState == VersioningState.CHECKEDOUT) {
			updateVersionSeriesWithPwc(callContext, repositoryId, getVersionSeries(repositoryId, result), result);
		}

		// Record the change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.CREATED);

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

		return result;
	}

	@Override
	public Document createDocumentWithNewStream(CallContext callContext, String repositoryId, Document original,
			ContentStream contentStream) {
		Document copy = buildCopyDocumentWithBasicProperties(callContext, original);

		// Attachment
		String attachmentId = createAttachment(callContext, repositoryId, contentStream);
		copy.setAttachmentNodeId(attachmentId);

		// Rendition
		if (isPreviewEnabled()) {
			AttachmentNode an = getAttachment(repositoryId, attachmentId);
			ContentStream previewCS = new ContentStreamImpl(contentStream.getFileName(), contentStream
					.getBigLength(), contentStream.getMimeType(), an.getInputStream());
			if (renditionManager.checkConvertible(previewCS.getMimeType())) {
				createPreview(callContext, repositoryId, previewCS, copy);
			}
		}

		// Set other properties
		// TODO externalize versionigState
		updateVersionProperties(callContext, repositoryId, VersioningState.MINOR, copy, original);

		// Create
		Document result = contentDaoService.create(repositoryId, copy);


		// Record the change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.CREATED);

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

		return result;
	}

	public Document replacePwc(CallContext callContext, String repositoryId, Document originalPwc,
			ContentStream contentStream) {
		// Update attachment contentStream
		AttachmentNode an = contentDaoService.getAttachment(repositoryId, originalPwc.getAttachmentNodeId());
		contentDaoService.updateAttachment(repositoryId, an, contentStream);

		// Update rendition contentStream

		if (isPreviewEnabled()) {
			ContentStream previewCS = new ContentStreamImpl(contentStream.getFileName(), contentStream
					.getBigLength(), contentStream.getMimeType(), an.getInputStream());

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
		solrUtil.callSolrIndexing(repositoryId);

		return originalPwc;
	}

	@Override
	public Document checkOut(CallContext callContext, String repositoryId, String objectId, ExtensionsData extension) {
		Document latest = getDocument(repositoryId, objectId);
		Document pwc = buildCopyDocumentWithBasicProperties(callContext, latest);

		// Create PWC attachment
		String attachmentId = copyAttachment(callContext, repositoryId, latest.getAttachmentNodeId());
		pwc.setAttachmentNodeId(attachmentId);

		// Create PWC renditions
		copyRenditions(callContext, repositoryId, latest.getRenditionIds());

		// Set other properties
		updateVersionProperties(callContext, repositoryId, VersioningState.CHECKEDOUT, pwc, latest);

		// Create PWC itself
		Document result = contentDaoService.create(repositoryId, pwc);

		// Modify versionSeries
		updateVersionSeriesWithPwc(callContext, repositoryId, getVersionSeries(repositoryId, result), result);

		// Write change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.CREATED);

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

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

		List<Document> versions = getAllVersions(callContext, repositoryId, vs.getId());
		if(CollectionUtils.isNotEmpty(versions)){
			//Collections.sort(versions, new VersionComparator());
			for(Document version : versions){
				contentDaoService.refreshCmisObjectData(repositoryId, version.getId());
			}
		}

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);
	}

	@Override
	public Document checkIn(CallContext callContext, String repositoryId, Holder<String> objectId, Boolean major,
			Properties properties, ContentStream contentStream, String checkinComment, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {

		String id = objectId.getValue();

		Document pwc = getDocument(repositoryId, id);
		Document checkedIn = buildCopyDocumentWithBasicProperties(callContext, pwc);
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

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

		return result;
	}

	private Document buildNewBasicDocument(CallContext callContext, String repositoryId, Properties properties,
			Folder parentFolder) {
		Document d = new Document();
		setBaseProperties(callContext, repositoryId, properties, d, parentFolder.getId());
		d.setParentId(parentFolder.getId());
		d.setImmutable(DataUtil.getBooleanProperty(properties, PropertyIds.IS_IMMUTABLE));
		setSignature(callContext, d);
		
		
		// Acl
		/*d.setAclInherited(true);
		d.setAcl(new Acl());*/
		setAclOnCreated(callContext, repositoryId, d);

		return d;
	}
	
	private void setAclOnCreated(CallContext callContext, String repositoryId, Content content){
		Acl acl = new Acl();
		if(isTopLevel(repositoryId, content)){
			
			Ace ace = new Ace();
			ace.setPrincipalId(callContext.getUsername());
			ace.setPermissions(new ArrayList<String>( Arrays.asList(CmisPermission.ALL)));
			acl.setLocalAces(new ArrayList<Ace>( Arrays.asList(ace) ));
		}
		content.setAcl(acl);
		
		content.setAclInherited(getAclInheritedWithDefault(repositoryId, content));

	}

	private Document buildCopyDocumentWithBasicProperties(CallContext callContext, Document original) {
		Document copy = new Document();
		copy.setType(original.getType());
		copy.setObjectType(original.getObjectType());
		copy.setName(original.getName());
		copy.setDescription(original.getDescription());
		copy.setParentId(original.getParentId());
		copy.setImmutable(original.isImmutable());
		copy.setAclInherited(original.isAclInherited());
		copy.setAcl(original.getAcl());
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
		vs = createVersionSeries(callContext, repositoryId, versioningState);
		d.setVersionSeriesId(vs.getId());
		switch (versioningState) {
		// TODO NONE is not allowed
		case CHECKEDOUT:
			d.setLatestVersion(false);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setPrivateWorkingCopy(true);
			break;
		case MAJOR:
			d.setLatestVersion(true);
			d.setMajorVersion(true);
			d.setLatestMajorVersion(true);
			d.setVersionLabel("1.0");
			d.setPrivateWorkingCopy(false);
			break;
		case MINOR:
			d.setLatestVersion(true);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setVersionLabel("0.1");
			d.setPrivateWorkingCopy(false);
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
			// former latestVersion/latestMajorVersion remains unchanged
		default:
			break;
		}
	}

	private VersionSeries createVersionSeries(CallContext callContext, String repositoryId,
			VersioningState versioningState) {
		VersionSeries vs = new VersionSeries();
		vs.setVersionSeriesCheckedOut(false);
		setSignature(callContext, vs);

		VersionSeries versionSeries = contentDaoService.create(repositoryId, vs);
		return versionSeries;
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

		versionSeries.setVersionSeriesCheckedOut(true);
		versionSeries.setVersionSeriesCheckedOutId(pwc.getId());
		versionSeries.setVersionSeriesCheckedOutBy(callContext.getUsername());
		contentDaoService.update(repositoryId, versionSeries);
	}

	@Override
	public Folder createFolder(CallContext callContext, String repositoryId, Properties properties,
			Folder parentFolder) {
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

		//Acl
		/*Acl acl = new Acl();
		if (isRoot(repositoryId, parentFolder)){
			Ace ace = new Ace();
			ace.setPrincipalId(callContext.getUsername());
			ace.setPermissions(new ArrayList<String>( Arrays.asList(CmisPermission.ALL)));
			acl.setLocalAces(new ArrayList<Ace>( Arrays.asList(ace) ));
			//f.setAclInherited(false);
		}else{
			f.setAclInherited(true);
		}
		f.setAcl(acl);*/
		
		setAclOnCreated(callContext, repositoryId, f);
		
		
		// Create
		Folder folder = contentDaoService.create(repositoryId, f);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, folder, ChangeType.CREATED);

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

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
		rel.setAclInherited(true);
		rel.setAcl(new Acl());

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
		p.setAclInherited(true);
		p.setAcl(new Acl());

		Policy policy = contentDaoService.create(repositoryId, p);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, policy, ChangeType.CREATED);

		return policy;
	}

	@Override
	public Item createItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {
		Item i = new Item();
		setBaseProperties(callContext, repositoryId, properties, i, null);
		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
		TypeDefinition tdf = typeManager.getTypeDefinition(repositoryId, objectTypeId);
		if (tdf.isFileable()) {
			i.setParentId(folderId);
		}

		// Set ACL
		i.setAclInherited(true);
		i.setAcl(new Acl());

		Item item = contentDaoService.create(repositoryId, i);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, item, ChangeType.CREATED);

		return item;
	}

	private void setBaseProperties(CallContext callContext, String repositoryId, Properties properties, Content content,
			String parentFolderId) {
		// Object Type
		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
		content.setObjectType(objectTypeId);

		// Base Type
		TypeDefinition typeDefinition = typeManager.getTypeDefinition(repositoryId, objectTypeId);
		BaseTypeId baseTypeId = typeDefinition.getBaseTypeId();
		content.setType(baseTypeId.value());

		// Name
		String name = DataUtil.getStringProperty(properties, PropertyIds.NAME);
		content.setName(name);

		// Description
		content.setDescription(DataUtil.getStringProperty(properties, PropertyIds.DESCRIPTION));

		// Secondary Type IDs
		content.setSecondaryIds(DataUtil.getIdListProperty(properties, PropertyIds.SECONDARY_OBJECT_TYPE_IDS));

		// Signature
		setSignature(callContext, content);
	}

	private String copyAttachment(CallContext callContext, String repositoryId, String attachmentId) {
		AttachmentNode original = getAttachment(repositoryId, attachmentId);
		ContentStream cs = new ContentStreamImpl(original.getName(), BigInteger.valueOf(original.getLength()), original
				.getMimeType(), original.getInputStream());

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
			ContentStream cs = new ContentStreamImpl("content", BigInteger.valueOf(original.getLength()), original
					.getMimetype(), original.getInputStream());

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

	private Content modifyProperties(CallContext callContext, String repositoryId, Properties properties,
			Content content) {
		if (properties == null || MapUtils.isEmpty(properties.getProperties())) {
			return content;
		}

		// Primary
		org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = typeManager
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
			content.setSubTypeProperties(subTypeProperties);
		}

		// Secondary
		List<Aspect> secondary = buildSecondaryTypes(repositoryId, properties, content);
		if (!CollectionUtils.isEmpty(secondary)) {
			content.setAspects(secondary);
		}

		// Set modified signature
		setModifiedSignature(callContext, content);

		return content;
	}

	private List<Property> buildSubTypeProperties(String repositoryId, Properties properties, Content content) {
		List<PropertyDefinition<?>> subTypePropertyDefinitions = typeManager
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
			org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = typeManager
					.getTypeDefinition(repositoryId, secondaryTypeId);
			Aspect aspect = new Aspect();
			aspect.setName(secondaryTypeId);

			List<Property> props = injectPropertyValue(td.getPropertyDefinitions().values(), properties, content);

			aspect.setProperties(props);
			aspects.add(aspect);
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

		Content result = update(repositoryId, modified);

		// Record the change event
		writeChangeEvent(callContext, repositoryId, result, ChangeType.UPDATED);

		return result;
	}

	@Override
	public Content update(String repositoryId, Content content) {
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
			result = contentDaoService.update(repositoryId, (Item) content);
		}

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);

		return result;
	}

	// TODO updatable CMIS properties are hard-coded.
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
						for(String name: names) {
							if (lowerCaseProposedName.equals(name.toLowerCase()) && !originalName.equals(name)) {
								throw new CmisContentAlreadyExistsException(
										"A content with the specified name already exists",
										BigInteger.valueOf(409));
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
		solrUtil.callSolrIndexing(repositoryId);
	}

	private Content move(String repositoryId, Content content, String sourceId){
		Content result = null;
		if(content instanceof Document){
			result = contentDaoService.move(repositoryId, (Document)content, sourceId);
		}else if(content instanceof Folder){
			result = contentDaoService.move(repositoryId, (Folder)content, sourceId);
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
	public void removePolicy(CallContext callContext, String repositoryId, String policyId,
			String objectId, ExtensionsData extension) {
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
		Content content = getContent(repositoryId, objectId);

		//TODO workaround
		if(content == null){
			//If content is already deleted, do nothing;
			return;
		}

		// Record the change event(Before the content is deleted!)
		writeChangeEvent(callContext, repositoryId, content, ChangeType.DELETED);

		// Archive and then Delete
		createArchive(callContext, repositoryId, objectId, deletedWithParent);
		contentDaoService.delete(repositoryId, objectId);

		// Call Solr indexing(optional)
		solrUtil.callSolrIndexing(repositoryId);
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
		Document document = (Document) getContent(repositoryId, objectId);

		// Make the list of objects to be deleted
		List<Document> versionList = new ArrayList<Document>();
		String versionSeriesId = document.getVersionSeriesId();
		if (allVersions) {
			versionList = getAllVersions(callContext, repositoryId, versionSeriesId);
		} else {
			versionList.add(document);
		}

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
			delete(callContext, repositoryId, version.getId(), deleteWithParent);
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
		solrUtil.callSolrIndexing(repositoryId);
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
		AttachmentNode an = contentDaoService.getAttachment(repositoryId, attachmentId);
		contentDaoService.setStream(repositoryId, an);
		return an;
	}

	@Override
	public AttachmentNode getAttachmentRef(String repositoryId, String attachmentId) {
		AttachmentNode an = contentDaoService.getAttachment(repositoryId, attachmentId);
		return an;
	}

	private String createAttachment(CallContext callContext, String repositoryId, ContentStream contentStream) {
		AttachmentNode a = new AttachmentNode();
		a.setMimeType(contentStream.getMimeType());
		a.setLength(contentStream.getLength());
		setSignature(callContext, a);
		return contentDaoService.createAttachment(repositoryId, a, contentStream);
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
		Acl acl = content.getAcl();

		//boolean iht = (content.isAclInherited() == null) ? false : content.isAclInherited();
		boolean iht = getAclInheritedWithDefault(repositoryId, content);

		if (!isRoot(repositoryId, content) && iht) {
			// Caching the results of calculation
			List<Ace> aces = new ArrayList<Ace>();
			List<Ace> result = calculateAclInternal(repositoryId, aces, content);

			// Convert result to Acl
			Acl _acl = new Acl();
			for (Ace r : result) {
				if (r.isDirect()) {
					_acl.getLocalAces().add(r);
				} else {
					_acl.getInheritedAces().add(r);
				}
			}
			acl = _acl;
		}

		// Convert anonymous and anyone
		convertSystemPrincipalId(repositoryId, acl.getAllAces());

		return acl;
	}

	private List<Ace> calculateAclInternal(String repositoryId, List<Ace> result, Content content) {
		if (isRoot(repositoryId, content) || !getAclInheritedWithDefault(repositoryId, content)) {
			List<Ace> rootAces = new ArrayList<Ace>();
			List<Ace> aces = content.getAcl().getLocalAces();
			for (Ace ace : aces) {
				Ace rootAce = deepCopy(ace);
				rootAce.setDirect(true);
				rootAces.add(rootAce);
			}
			return mergeAcl(repositoryId, result, rootAces);
		} else {
			Content parent = getParent(repositoryId, content.getId());
			return mergeAcl(repositoryId, content.getAcl()
					.getLocalAces(), calculateAclInternal(repositoryId, new ArrayList<Ace>(), parent));
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
	public Boolean getAclInheritedWithDefault(String repositoryId, Content content){
		boolean inheritedAtTopLevel = 
				propertyManager.readBoolean(PropertyKey.CAPABILITY_EXTENDED_PERMISSION_INHERITANCE_TOPLEVEL);

		if(isRoot(repositoryId, content)){
			return false; 
		}else{
			if(isTopLevel(repositoryId, content) && !inheritedAtTopLevel){
				//default to TRUE
				return (content.isAclInherited() == null) ? false: content.isAclInherited();
			}else{
				//default to FALSE
				return (content.isAclInherited() == null) ? true: content.isAclInherited();
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
	public List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc){
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
	public void restoreArchive(String repositoryId, String archiveId) throws ParentNoLongerExistException{
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

		CallContextImpl dummyContext = new CallContextImpl(null, CmisVersion.CMIS_1_1, null, null, null, null, null, null);
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
		solrUtil.callSolrIndexing(repositoryId);
	}

	private Document restoreDocument(String repositoryId, Archive archive) {
		try {
			// Get archives of the same version series
			List<Archive> versions = contentDaoService
					.getArchivesOfVersionSeries(repositoryId, archive.getVersionSeriesId());
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

	private Folder restoreFolder(String repositoryId, Archive archive) throws ParentNoLongerExistException{
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
	
	public void destroyArchive(String repositoryId, String archiveId){
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
	
	private void destroyFolder(String repositoryId, Archive archive){
		// Restore direct children
		List<Archive> children = contentDaoService.getChildArchives(repositoryId, archive);
		if (CollectionUtils.isNotEmpty(children)) {
			for (Archive child : children) {
				destroyArchive(repositoryId, child.getId());
			}
		}
		contentDaoService.deleteArchive(repositoryId, archive.getId());
	}
	
	
	private void destoryDocument(String repositoryId, Archive archive){
		try {
			// Get archives of the same version series
			List<Archive> versions = contentDaoService
					.getArchivesOfVersionSeries(repositoryId, archive.getVersionSeriesId());
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
	private String buildUniqueName(String repositoryId, String proposedName, String folderId, Content current) {
		boolean bun = propertyManager.readBoolean(PropertyKey.CAPABILITY_EXTENDED_BUILD_UNIQUE_NAME);
		if (!bun) {
			return proposedName;
		}

		//Check if update method
		if(current != null && current.getName().equals(proposedName)){
			return proposedName;
		}

		List<String>names = contentDaoService.getChildrenNames(repositoryId, folderId);
		String[] splitted = splitFileName(proposedName);
		String originalNameBody = splitted[0];
		String extension = splitted[1];

		String newNameBody = originalNameBody;
		for(Integer i = 1; i <= names.size(); i++){
			if(names.contains(newNameBody + extension)){
				newNameBody = originalNameBody + " ~" + i;
				continue;
			}else{
				break;
			}
		}

		return newNameBody + extension;
	}

	private String[] splitFileName(String name) {
		if (name == null)
			return null;

		String body = "";
		String suffix = "";
		int point = name.lastIndexOf(".");
		if (point != -1) {
			body = name.substring(0, point);
			suffix = "." + name.substring(point + 1);
		} else {
			body = name;
		}

		String[] ary = { body, suffix };
		return ary;
	}

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

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
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
}
