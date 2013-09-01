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
package jp.aegif.nemaki.service.node.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.constant.NemakiConstant;
import jp.aegif.nemaki.model.constant.NodeType;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.dao.ContentDaoService;
import jp.aegif.nemaki.service.dao.impl.ContentDaoServiceImpl;
import jp.aegif.nemaki.service.node.ContentService;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Principal;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Node Service implementation
 * 
 * @author linzhixing
 * 
 */
public class ContentServiceImpl implements ContentService {

	private ContentDaoService contentDaoService;
	private TypeManager typeManager;
	private static final Log log = LogFactory
			.getLog(ContentDaoServiceImpl.class);
	final static int FIRST_TOKEN = 1;
	
	/**
	 * 
	 */
	@Override
	public Content getContentAsTheBaseType(String objectId) {
		Content content = contentDaoService.getContent(objectId);
		if(content == null) return null;
		
		if(content.isDocument()){
			return contentDaoService.getDocument(content.getId());
		}else if(content.isFolder()){
				return contentDaoService.getFolder(content.getId());
		}else if(content.isRelationship()){
			return contentDaoService.getRelationship(content.getId());
		}else if(content.isPolicy()){
			return contentDaoService.getPolicy(content.getId());
		}else{
			return null;
		}
	}

	/**
	 * Get the pieces of content available at that path.
	 */
	public Content getContentByPath(String path) {
		List<String> splittedPath = splitLeafPathSegment(path);
		
		if(splittedPath.size() <= 0){
			return null;
		}else if(splittedPath.size() == 1) {
			if(!splittedPath.get(0).equals(NemakiConstant.PATH_SEPARATOR)) return null;
			return contentDaoService.getFolder(NemakiConstant.PATH_SEPARATOR);
		}else if(splittedPath.size() >= 1){
			Content content = contentDaoService.getFolder(NemakiConstant.PATH_SEPARATOR);
			//Get the the leaf node
			for(int i=1; i< splittedPath.size(); i++){
				Content child = contentDaoService.getChildByName(content.getId(), splittedPath.get(i));
				content = child;
			}
			return getContentAsTheBaseType(content.getId());
		}
		return null;
	}

	private List<String> splitLeafPathSegment(String path) {
		List<String> splitted = new LinkedList<String>();
		if(path.equals(NemakiConstant.PATH_SEPARATOR)){
			splitted.add(NemakiConstant.PATH_SEPARATOR);
			return splitted;
		}

		//TODO validation for irregular path
		splitted = new LinkedList<String>(Arrays.asList(path.split(NemakiConstant.PATH_SEPARATOR)));
		splitted.remove(0);
		splitted.add(0, NemakiConstant.PATH_SEPARATOR);
		return splitted;
	}
	
	@Override
	public String getPath(Content content){
		List<String> path = getPathInternal(new ArrayList<String>(), content);
		path.remove(0);
		return NemakiConstant.PATH_SEPARATOR + StringUtils.join(path, NemakiConstant.PATH_SEPARATOR);
	}

	private List<String> getPathInternal(List<String> path, Content content){
		path.add(0, content.getName());
		
		if(content.isRoot()){
			return path;
		}else{
			Content parent = getParent(content.getId());
			getPathInternal(path, parent);
		}
		return path;
	}
	
	@Override
	public Folder getParent(String objectId) {
		// As a model within Nemaki, content have the parentId?
		Content content = contentDaoService.getContent(objectId);
		return getFolder(content.getParentId());
	}

	/**
	 * Get children contents in a given folder
	 */
	public List<Content> getChildren(String folderId) {
		List<Content> children = new ArrayList<Content>();
		
		List<Content> indices = contentDaoService
				.getLatestChildrenIndex(folderId);
		if(CollectionUtils.isEmpty(indices)) return null;
		
		for (Content c : indices) {
			if (c.isDocument()) {
				Document d = contentDaoService.getDocument(c.getId());
				children.add(d);
			} else if (c.isFolder()) {
				Folder f = contentDaoService.getFolder(c.getId());
				children.add(f);
			}else if (c.isRelationship()) {
				Relationship r = contentDaoService.getRelationship(c.getId());
				children.add(r);
			}else if (c.isPolicy()) {
				Policy p = contentDaoService.getPolicy(c.getId());
				children.add(p);
			}
		}
		return children;
	}

	// if the root of the tree doesn't exists, return null
	public List<Content> getDescendants(String folderId, int depth) {
		//Content class is somehow abstract, but it's enough for DELETE.
		Content content = contentDaoService.getContent(folderId);
		if (content == null)
			return null;

		List<Content> descendants = getDescendantsInternal(content, depth);
		return descendants;
	}

	private List<Content> getDescendantsInternal(Content content, int depth) {
		// check depth
		if (depth == 0) {
			throw new CmisInvalidArgumentException("Depth must not be 0!");
		}
		if (depth < -1) {
			depth = -1;
		}

		List<Content> descendants = new ArrayList<Content>();
		List<Content> children = getChildren(content.getId());
		if (children == null)
			return descendants;

		for (Content c : children) {
			descendants.add(c);
			if (getChildren(c.getId()) == null || depth == 1) {
				return descendants;
			} else {
				descendants.addAll(getDescendantsInternal(c, depth - 1));
			}
		}
		return descendants;
	}

	@Override
	public Document getDocument(String objectId) {
		return contentDaoService.getDocument(objectId);
	}

	@Override
	public Document getDocumentOfLatestVersion(String versionSeriesId) {
		return contentDaoService.getDocumentOfLatestVersion(versionSeriesId);
	}
	
	public List<Document> getCheckedOutDocs(String folderId, String orderBy,ExtensionsData extension){
		//NOTE "orderBy" is not supported under the capabilities at this time
		return contentDaoService.getCheckedOutDocuments(folderId);
	}

	@Override
	public List<Document> getAllVersions(String versionSeriesId) {
		return contentDaoService.getAllVersions(versionSeriesId);
	}

	@Override
	public Folder getFolder(String objectId) {
		return contentDaoService.getFolder(objectId);
	}

	@SuppressWarnings("unchecked")
	@Override
	//TODO Subtypes of relationships can be correctly retrieved?
	public List<Relationship> getRelationsipsOfObject(String objectId,
			RelationshipDirection relationshipDirection) {
		// Set default
		relationshipDirection = (relationshipDirection == null) ? RelationshipDirection.SOURCE
				: relationshipDirection;
		switch (relationshipDirection) {
		case SOURCE:
			return contentDaoService.getRelationshipsBySource(objectId);
		case TARGET:
			return contentDaoService.getRelationshipsByTarget(objectId);
		case EITHER:
			List<Relationship> sources = contentDaoService
					.getRelationshipsBySource(objectId);
			List<Relationship> targets = contentDaoService
					.getRelationshipsByTarget(objectId);
			return (List<Relationship>) CollectionUtils.disjunction(sources,
					targets);
		default:
			return null;
		}
	}

	@Override
	public Relationship getRelationship(String objectId) {
		return contentDaoService.getRelationship(objectId);
	}

	@Override
	public Policy getPolicy(String objectId) {
		return contentDaoService.getPolicy(objectId);
	}

	@Override
	public void applyPolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension) {
		Policy policy = getPolicy(policyId);
		List<String> ids = policy.getAppliedIds();
		ids.add(objectId);
		policy.setAppliedIds(ids);
		contentDaoService.updatePolicy(policy);
		
		//Record the change event
		Content content = getContentAsTheBaseType(objectId);
		writeChangeEvent(callContext, content, ChangeType.SECURITY);
	}

	@Override
	public void removePolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension) {
		Policy policy = getPolicy(policyId);
		List<String> ids = policy.getAppliedIds();
		ids.remove(objectId);
		policy.setAppliedIds(ids);
		contentDaoService.updatePolicy(policy);
		
		//Record the change event
		Content content = getContentAsTheBaseType(objectId);
		writeChangeEvent(callContext, content, ChangeType.SECURITY);
	}

	@Override
	public List<Policy> getAppliedPolicies(String objectId,
			ExtensionsData extension) {
		return contentDaoService.getAppliedPolicies(objectId);
	}
	
	private int writeChangeEvent(CallContext callContext, Content content, ChangeType changeType){
		Change latest = contentDaoService.getLatestChange();
		
		Change change = new Change();
		change.setType(NodeType.CHANGE.value());
		change.setObjectId(content.getId());
		change.setChangeType(changeType);
		switch(changeType){
		case CREATED:
			change.setTime(content.getCreated());
			break;
		case UPDATED:
			change.setTime(content.getModified());
			break;
		case DELETED:
			change.setTime(content.getCreated());
			break;
		default:
			break;
		}
		if(latest == null){
			change.setChangeToken(FIRST_TOKEN);
		}else{
			change.setChangeToken(latest.getChangeToken() + 1);
		}
		
		change.setLatest(true);
		change.setType(NodeType.CHANGE.value());
		change.setName(content.getName());
		change.setBaseType(content.getType());
		change.setObjectType(content.getObjectType());
		change.setParentId(content.getParentId());
		List<String> policyIds = new ArrayList<String>();
		List<Policy> policies = getAppliedPolicies(content.getId(), null);
		if(!CollectionUtils.isEmpty(policies)){
			for(Policy p : policies){
				policyIds.add(p.getId());
			}
		}
		change.setPolicyIds(policyIds);
		if(content.isDocument()){
			Document d = (Document)content;
			change.setVersionSeriesId(d.getVersionSeriesId());
			change.setVersionLabel(d.getVersionLabel());
		}
		
		setSignature(callContext, change);
		
		//Modify old change event
		contentDaoService.create(change);
		if(latest != null){
			latest.setLatest(false);
			contentDaoService.updateChange(latest);
		}
		
		//Update change token of the content
		content.setChangeToken(change.getChangeToken());
		update(content);
		
		return change.getChangeToken();
	}
	
	// TODO name uniqueness in a folder
	// TODO Create a rendition
	@Override
	public Document createDocument(CallContext callContext,
			Properties properties, Folder parentFolder,
			ContentStream contentStream, VersioningState versioningState,
			String versionSeriesId) {
		Document d = buildNewBasicDocument(callContext, properties, parentFolder);
		
		// create Attachment node
		String attachmentId = createAttachment(callContext, contentStream);
		d.setAttachmentNodeId(attachmentId);
		
		// TODO create Renditions

		// Set version properties
		VersionSeries vs = setVersionProperties(callContext, versioningState, d);

		// Create
		Document document = contentDaoService.create(d);

		// Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
		if (versioningState == VersioningState.CHECKEDOUT) {
			updateVersionSeriesWithPwc(callContext, vs, document);
		}
		
		//Write change event
		writeChangeEvent(callContext, document, ChangeType.CREATED);		

		return document;
	}
	
	@Override
	public Document createDocumentFromSource(CallContext callContext,
			Properties properties, String folderId,
			Document original, VersioningState versioningState, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces, org.apache.chemistry.opencmis.commons.data.Acl removeAces){
		Document copy = buildCopyDocumentWithBasicProperties(callContext, original);
		
		String attachmentId = copyAttachment(callContext, original.getAttachmentNodeId());
		copy.setAttachmentNodeId(attachmentId);
		
		setVersionProperties(callContext, versioningState, copy);
		
		if(folderId != null) copy.setParentId(folderId);
		//Set updated properties
		updateProperties(callContext, properties, copy);
		setSignature(callContext, copy);
		
		//Create
		Document result = contentDaoService.create(copy);
		
		// Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
		if (versioningState == VersioningState.CHECKEDOUT) {
			updateVersionSeriesWithPwc(callContext, getVersionSeries(result.getVersionSeriesId()), result);
		}
		
		//Record the change event
		writeChangeEvent(callContext, result, ChangeType.CREATED);
		
		return result;
	}
	
	@Override
	public Document createDocumentWithNewStream(CallContext callContext, Document original, ContentStream contentStream){
		Document copy = buildCopyDocumentWithBasicProperties(callContext, original);

		String attachmentId = createAttachment(callContext, contentStream);
		copy.setAttachmentNodeId(attachmentId);
		//TODO copy renditions
		
		//Set other properties
		//TODO externalize versionigState
		updateVersionProperties(callContext, VersioningState.MINOR, copy, original);
		
		//Create
		Document result = contentDaoService.create(copy);
		
		//Record the change event
		writeChangeEvent(callContext, result, ChangeType.CREATED);
		
		return result;
	}
	
	@Override
	public Document checkOut(CallContext callContext, String objectId,
			ExtensionsData extension) {
		Document latest = getDocument(objectId);
		Document pwc = buildCopyDocumentWithBasicProperties(callContext, latest);
		
		
		// Create PWC attachment
		String attachmentId = copyAttachment(callContext, latest.getAttachmentNodeId());
		pwc.setAttachmentNodeId(attachmentId);
		
		// Create PWC renditions
		copyRenditions(callContext, latest.getRenditionIds());

		

		//Set other properties
		updateVersionProperties(callContext, VersioningState.CHECKEDOUT, pwc, latest);

		// Create PWC itself
		Document result = contentDaoService.create(pwc);


		// Modify versionSeries
		updateVersionSeriesWithPwc(callContext, getVersionSeries(result.getVersionSeriesId()), result);
		
		return result;
	}
	
	@Override
	public void cancelCheckOut(CallContext callContext, String objectId,
			ExtensionsData extension) {
		Document pwc = getDocument(objectId);
		VersionSeries vs = getVersionSeries(pwc.getVersionSeriesId());
		
		//Delete attachment & document itself(without archiving)
		contentDaoService.delete(pwc.getAttachmentNodeId());
		contentDaoService.delete(pwc.getId());
		
		//Reverse the effect of checkout
		setModifiedSignature(callContext, vs);
		vs.setVersionSeriesCheckedOut(false);
		vs.setVersionSeriesCheckedOutBy("");
		vs.setVersionSeriesCheckedOutId("");
		contentDaoService.updateVersionSeries(vs);
	}

	@Override
	public Document checkIn(CallContext callContext, Holder<String> objectId,
			Boolean major, Properties properties, ContentStream contentStream,
			String checkinComment, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension) {
		String id = objectId.getValue();
		Document pwc = getDocument(id);
		Document checkedIn = buildCopyDocumentWithBasicProperties(callContext, pwc);

		Document latest = getDocumentOfLatestVersion(pwc.getVersionSeriesId());
		
		//When PWCUpdatable is true
		if(contentStream == null){
			checkedIn.setAttachmentNodeId(copyAttachment(callContext, pwc.getAttachmentNodeId()));
		//When PWCUpdatable is false	
		}else{
			checkedIn.setAttachmentNodeId(createAttachment(callContext, contentStream));
		}
		
		//Set updated properties
		//updateProperties(callContext, properties, checkedIn);
		modifyProperties(callContext, properties, checkedIn);
		setSignature(callContext, checkedIn);
		checkedIn.setCheckinComment(checkinComment);
		
		//update version information
		VersioningState versioningState = (major) ? VersioningState.MAJOR : VersioningState.MINOR;
		updateVersionProperties(callContext, versioningState, checkedIn, latest);
		
		//TODO set policies & ACEs
		
		//Create
		Document result = contentDaoService.create(checkedIn);
		
		//Reverse the effect of checkedout
		cancelCheckOut(callContext, id, extension);
		
		//Record the change event
		writeChangeEvent(callContext, result, ChangeType.CREATED);
		
		return result;
	}

	private Document buildNewBasicDocument(CallContext callContext,
			Properties properties, Folder parentFolder) {
		Document d = new Document();
		setBaseProperties(callContext, properties, d);
		d.setParentId(parentFolder.getId());
		setSignature(callContext, d);
		// Aspect
		List<Aspect> aspects = buildAspects(properties);
		if (aspects != null)
			d.setAspects(aspects);
		// Acl
		d.setAclInherited(true);
		d.setAcl(new Acl());

		return d;
	}
	
	private Document buildCopyDocumentWithBasicProperties(CallContext callContext,
			Document original) {
		Document copy = new Document();
		copy.setType(original.getType());
		copy.setObjectType(original.getObjectType());
		copy.setName(original.getName());
		copy.setDescription(original.getDescription());
		setSignature(callContext, copy);
		copy.setParentId(original.getParentId());
		copy.setAclInherited(original.isAclInherited());
		copy.setAcl(original.getAcl());
		copy.setAspects(original.getAspects());
		copy.setSecondaryIds(original.getSecondaryIds());

		return copy;
	}
	
	private VersionSeries setVersionProperties(CallContext callContext,
			VersioningState versioningState, Document d) {
		// Version properties
		// CASE:New VersionSeries
		VersionSeries vs;
		vs = createVersionSeries(callContext, versioningState);
		d.setVersionSeriesId(vs.getId());
		switch (versioningState) {
		//TODO NONE is not allowed
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
	
	private void updateVersionProperties(CallContext callContext,
			VersioningState versioningState, Document d, Document former){
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
			contentDaoService.updateDocument(former);
			break;
		case MINOR:
			d.setLatestVersion(true);
			d.setMajorVersion(false);
			d.setLatestMajorVersion(false);
			d.setVersionLabel(increasedVersionLabel(former, versioningState));
			d.setPrivateWorkingCopy(false);
			former.setLatestVersion(false);
			contentDaoService.updateDocument(former);
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
	
	private VersionSeries createVersionSeries(CallContext callContext,
			VersioningState versioningState) {
		VersionSeries vs = new VersionSeries();
		vs.setType(NodeType.VERSION_SERIES.value());
		vs.setVersionSeriesCheckedOut(false);
		setSignature(callContext, vs);

		VersionSeries versionSeries = contentDaoService.createVersionSeries(vs);
		return versionSeries;
	}
	
	/**
	 * Update versionSeriesId#versionSeriesCheckedOutId after creating a PWC
	 * @param callContext
	 * @param versionSeries
	 * @param pwc
	 */
	private void updateVersionSeriesWithPwc(CallContext callContext, VersionSeries versionSeries, Document pwc){
		
		versionSeries.setVersionSeriesCheckedOut(true);
		versionSeries.setVersionSeriesCheckedOutId(pwc.getId());
		versionSeries.setVersionSeriesCheckedOutBy(callContext.getUsername());
		contentDaoService.updateVersionSeries(versionSeries);
	}

	@Override
	public Folder createFolder(CallContext callContext, Properties properties,
			Folder parentFolder) {
		Folder f = new Folder();
		setBaseProperties(callContext, properties, f);
		f.setParentId(parentFolder.getId());
		//Defaults to document & folder if not specified
		List<String> allowedTypes = getIdListProperty(properties,PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS);
		if(CollectionUtils.isEmpty(allowedTypes)){
			f.setAllowedChildTypeIds(allowedTypes);
		}else{
			List<String> l = new ArrayList<String>();
			l.add(BaseTypeId.CMIS_FOLDER.value());
			l.add(BaseTypeId.CMIS_DOCUMENT.value());
			f.setAllowedChildTypeIds(l);
		}
		setSignature(callContext, f);
		f.setAclInherited(true);
		f.setAcl(new Acl());
		f.setAspects(buildAspects(properties));
		// Create
		Folder folder = contentDaoService.create(f);

		//Record the change event
		writeChangeEvent(callContext, folder, ChangeType.CREATED);
		
		return folder;
	}

	@Override
	public Relationship createRelationship(CallContext callContext,
			Properties properties, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces,
			ExtensionsData extension) {

		Relationship rel = new Relationship();
		setBaseProperties(callContext, properties, rel);
		rel.setSourceId(getIdProperty(properties, PropertyIds.SOURCE_ID));
		rel.setTargetId(getIdProperty(properties, PropertyIds.TARGET_ID));
		// Set ACL
		rel.setAclInherited(true);
		rel.setAcl(new Acl());

		Relationship relationship = contentDaoService.create(rel);

		//Record the change event
		writeChangeEvent(callContext, relationship, ChangeType.CREATED);
		
		return relationship;
	}

	@Override
	public Policy createPolicy(CallContext callContext, Properties properties,
			String folderId, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces,
			ExtensionsData extension) {

		Policy p = new Policy();
		setBaseProperties(callContext, properties, p);
		p.setPolicyText(getStringProperty(properties, PropertyIds.POLICY_TEXT));
		p.setAppliedIds(new ArrayList<String>());

		// Set ACL
		p.setAclInherited(true);
		p.setAcl(new Acl());

		Policy policy = contentDaoService.create(p);

		//Record the change event
		writeChangeEvent(callContext, policy, ChangeType.CREATED);
		
		return policy;
	}

	private void setBaseProperties(CallContext callContext,
			Properties properties, Content content) {
		//Base Type
		content.setType(getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID));

		//Object Type
		content.setObjectType(getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID));
		
		//Name(Unique in a folder)
		String uniqueName = buildUniqueName(getStringProperty(properties, PropertyIds.NAME), content.getParentId(), getIdProperty(properties, PropertyIds.OBJECT_ID));
		content.setName(uniqueName);
		
		//Description
		content.setDescription(getStringProperty(properties,
				PropertyIds.DESCRIPTION));
		
		//Signature
		setSignature(callContext, content);
	}

	private String copyAttachment(CallContext callContext, String attachmentId) {
		AttachmentNode attachment = getAttachment(attachmentId);
		ContentStream cs = new ContentStreamImpl(attachment.getName(),
				BigInteger.valueOf(attachment.getLength()),
				attachment.getMimeType(), attachment.getInputStream());
		setSignature(callContext, attachment);
		attachment.setId(null);
		return contentDaoService.createAttachment(attachment, cs);
	}

	private List<String> copyRenditions(CallContext callContext,
			List<String> renditionIds) {
		if (CollectionUtils.isEmpty(renditionIds))
			return null;

		for (String renditionId : renditionIds) {
			// TODO not yet implemented
		}
		return null;
	}

	@Override
	public VersionSeries getVersionSeries(String versionSeriesId) {
		return contentDaoService.getVersionSeries(versionSeriesId);
	}

	private List<String> getSecondaryTypeIds(Content content){
		List<String> result = new ArrayList<String>();
		List<Aspect> aspects = content.getAspects();
		for(Aspect aspect : aspects){
			result.add(aspect.getName());
		}
		return result;
	}
	
	private List<Aspect> buildSecondaryTypes(Properties properties, Content content){
		List<Aspect> aspects = new ArrayList<Aspect>();
		PropertyData secondaryTypeIds = properties.getProperties().get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
		
		//TODO ここ確認。secondaryTypeの適用とそのプロパティ設定を同時に行ってよいかどうか
		List<String> ids = new ArrayList<String>();
		if(secondaryTypeIds == null){
			ids = getSecondaryTypeIds(content);
		}else{
			ids = secondaryTypeIds.getValues();
		}
		
		for(String secondaryTypeId : ids){
			org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = typeManager.getTypeDefinition(secondaryTypeId);
			Aspect aspect = new Aspect();
			aspect.setName(secondaryTypeId);
			
			List<Property>props = new ArrayList<Property>();
			for(Entry<String, org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?>> entry : td.getPropertyDefinitions().entrySet()){
				org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> pd = entry.getValue();
				
				switch(pd.getUpdatability()){
				case READONLY:
					continue;
				case READWRITE:
					break;
				case WHENCHECKEDOUT:
					if(!content.isDocument()){
						continue;
					}else{
						Document d = (Document)content;
						if(!d.isPrivateWorkingCopy()){
							continue;
						}
					}
					break;
				default:
					continue;
				}
				
				PropertyData<?> property = properties.getProperties().get(entry.getKey());
				if(property == null) continue;
				Property p = new Property();
				p.setKey(property.getId());
				switch(entry.getValue().getCardinality()){
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

			aspect.setProperties(props);
			aspects.add(aspect);
		}
		return aspects;
	}
	
	private Content modifyProperties(CallContext callContext, Properties properties, Content content) {
		//Primary
		org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = typeManager.getTypeDefinition(content.getType());
		for(PropertyData<?> p : properties.getPropertyList()){
			org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition pd = td.getPropertyDefinitions().get(p.getId());
			if(pd == null) continue;
			
			//CASE: READ&WRITE(ANYTIME)
			if (pd.getUpdatability() == Updatability.READWRITE){
				setUpdatePropertyValue(content, p, properties);
			}
			//CASE:WHEN CHECKED OUT
			if(pd.getUpdatability() == Updatability.WHENCHECKEDOUT && content.isDocument()){
				Document d = (Document)content;
				if(d.isPrivateWorkingCopy()){
					setUpdatePropertyValue(content, p, properties);
				}
			}
		}
		
		//Secondary
		List<Aspect> secondary = buildSecondaryTypes(properties, content);
		if (secondary != null){
			content.setAspects(secondary);
		}
		
		//Set modified signature
		setModifiedSignature(callContext, content);
		
		return content;
	}
	
	/**
	 * Update one content.
	 */
	// FIXME Refine the logic
	public Content updateProperties(CallContext callContext, Properties properties, Content content) {

		Content modified = modifyProperties(callContext, properties, content);
		
		Content result = update(modified);
		
		//Record the change event
		writeChangeEvent(callContext, result, ChangeType.UPDATED);
		
		return result;
	}
	
	//TODO private化　& updateACLは別に作る & setModifiedSignatureなどはContentService内で処理する
	@Override
	public Content update(Content content) {
		if(content instanceof Document){
			return contentDaoService.updateDocument((Document)content);
		}else if(content instanceof Folder){
			return contentDaoService.updateFolder((Folder)content);
		}else if(content instanceof Relationship){
			return contentDaoService.updateRelationship((Relationship)content);
		}else if(content instanceof Policy){
			return contentDaoService.updatePolicy((Policy)content);
		}else{
			return null;
		}
	}

	/**
	 * As of now, updatable properties are name, description and secondary object types. 
	 * @param content
	 * @param propertyData
	 * @param properties
	 */
	public void setUpdatePropertyValue(Content content, PropertyData<?> propertyData, Properties properties){
		if(propertyData.getId().equals(PropertyIds.NAME)){
			if(getIdProperty(properties, PropertyIds.OBJECT_ID) != content.getId()){
				String uniqueName = buildUniqueName(getStringProperty(properties, PropertyIds.NAME), content.getParentId(), getIdProperty(properties, PropertyIds.OBJECT_ID));
				content.setName(uniqueName);
			}
		}
		
		if(propertyData.getId().equals(PropertyIds.DESCRIPTION)){
			content.setDescription(getStringProperty(properties, propertyData.getId()));
		}
		
		if(propertyData.getId().equals(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)){
			content.setSecondaryIds(getStringListProperty(properties, PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
		}
	}
	
	/**
	 * Move a Content
	 */
	@Override
	public void move(Content content, String targetFolderId) {
		content.setParentId(targetFolderId);
		String uniqueName = buildUniqueName(content.getName(), targetFolderId, null);
		content.setName(uniqueName);
		update(content);
	}

	@Override
	public Archive createArchive(CallContext callContext, String objectId,
			Boolean deletedWithParent) {
		Content content = getContentAsTheBaseType(objectId);

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

		contentDaoService.createArchive(a, deletedWithParent);
		// TODO return a result archive
		return null;
	}

	@Override
	public Archive createAttachmentArchive(CallContext callContext,
			String attachmentId) {
		Archive a = new Archive();
		a.setDeletedWithParent(true);
		a.setOriginalId(attachmentId);
		a.setType(NodeType.ATTACHMENT.value());
		setSignature(callContext, a);

		Archive archive = contentDaoService.createAttachmentArchive(a);
		return archive;
	}

	/**
	 * Delete a Content.
	 */
	public void delete(CallContext callContext, String objectId,
			Boolean deletedWithParent) {
		Content content = getContentAsTheBaseType(objectId);
		
		//Record the change event(Before the content is deleted!)
		writeChangeEvent(callContext, content, ChangeType.DELETED);	
		
		//Archive and then Delete
		createArchive(callContext, objectId, deletedWithParent);
		contentDaoService.delete(objectId);
	}
	
	@Override
	public void deleteAttachment(CallContext callContext, String attachmentId) {
		createAttachmentArchive(callContext, attachmentId);
		contentDaoService.delete(attachmentId);
	}

	public void deleteDocument(CallContext callContext, String objectId,
			Boolean allVersions, Boolean deleteWithParent) {
		Document document = (Document) getContentAsTheBaseType(objectId);

		// Make the list of objects to be deleted
		List<Document> versionList = new ArrayList<Document>();
		String versionSeriesId = document.getVersionSeriesId();
		if (allVersions) {
			versionList = getAllVersions(versionSeriesId);
		} else {
			versionList.add(document);
		}

		// Delete
		for (Document version : versionList) {
			// Archive a document
			if (version.getAttachmentNodeId() != null) {
				String attachmentId = version.getAttachmentNodeId();
				// Delete an attachment
				deleteAttachment(callContext, attachmentId);
			}
			// Delete a document
			delete(callContext, version.getId(), deleteWithParent);
		}

		// Move up the latest version
		if (!allVersions) {
			Document latestVersion = getDocumentOfLatestVersion(versionSeriesId);
			if(latestVersion != null){
				latestVersion.setLatestVersion(true);
				latestVersion.setLatestMajorVersion(latestVersion.isMajorVersion());
				contentDaoService.updateDocument(latestVersion);
			}
		}
	}



	// 中のdelete系メソッドは、基本的にはdeletedWithParent=true。ただしObjectServiceからこのメソッドを最初に呼び出すときだけfalse
	@Override
	public void deleteTree(CallContext callContext, String folderId,
			Boolean allVersions, Boolean continueOnFailure,
			Boolean deletedWithParent) throws Exception {
		// Delete children
		List<Content> children = getChildren(folderId);
		if (!CollectionUtils.isEmpty(children)){
			for (Content child : children) {
				try {
					if (child.isFolder()) {
						deleteTree(callContext, child.getId(), allVersions,
								continueOnFailure, true);
					} else if (child.isDocument()) {
						deleteDocument(callContext, child.getId(), allVersions,
								true);
					} else {
						delete(callContext, child.getId(), true);
					}
				} catch (Exception e) {
					if (continueOnFailure) {
						continue;
					} else {
						throw e;
					}
				}
			}
		}

		// Delete the folder itself
		try {
			delete(callContext, folderId, deletedWithParent);
		} catch (Exception e) {
			if (!continueOnFailure) {
				throw e;
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Acl
	// //////////////////////////////////////////////////////////////////////////////
	// NavigationServiceでObjectDataを出力する場合の取り扱いは別途考える
	public Acl getInheritedAcl(Content content) {
		Acl acl = content.getAcl();
		if (content.isRoot())
			return acl;

		if (content.isAclInherited()) {
			Content parent = getParent(content.getId());
			acl.setInheritedAces(getInheritedAcl(parent).getPropagatingAces());
			// TODO Merge ACEs of the same principal id
		} else {
			return acl;
		}
		return acl;
	}

	/**
	 * Convert to CMIS ACL including inherited ones
	 */
	public org.apache.chemistry.opencmis.commons.data.Acl convertToCmisAcl(
			Content content, Boolean onlyBasicPermissions) {
		AccessControlListImpl cmisAcl = new AccessControlListImpl();
		cmisAcl.setAces(new ArrayList<org.apache.chemistry.opencmis.commons.data.Ace>());

		Acl acl = getInheritedAcl(content);
		// Set local ACEs
		for (Ace ace : acl.getLocalAces()) {
			Principal principal = new AccessControlPrincipalDataImpl(
					ace.getPrincipalId());
			AccessControlEntryImpl cmisAce = new AccessControlEntryImpl(
					principal, ace.getPermissions());
			cmisAce.setDirect(true);
			cmisAcl.getAces().add(cmisAce);
		}

		// Set inherited ACEs
		for (jp.aegif.nemaki.model.Ace ace : acl.getInheritedAces()) {
			Principal principal = new AccessControlPrincipalDataImpl(
					ace.getPrincipalId());
			AccessControlEntryImpl cmisAce = new AccessControlEntryImpl(
					principal, ace.getPermissions());
			cmisAce.setDirect(false);
			cmisAcl.getAces().add(cmisAce);
		}

		// Set "exact" property
		cmisAcl.setExact(true);

		// Set "inherited" property, which is out of bounds to CMIS
		String namespace = NemakiConstant.NAMESPACE_ACL_INHERITANCE;
		CmisExtensionElementImpl inherited = new CmisExtensionElementImpl(
				namespace, NemakiConstant.EXTNAME_ACL_INHERITED, null, content.isAclInherited()
						.toString());
		List<CmisExtensionElement> exts = new ArrayList<CmisExtensionElement>();
		exts.add(inherited);
		cmisAcl.setExtensions(exts);

		return cmisAcl;
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Attachment
	// //////////////////////////////////////////////////////////////////////////////
	/**
	 * Get an attachment.
	 */
	public AttachmentNode getAttachment(String attachmentId) {
		return contentDaoService.getAttachment(attachmentId);
	}

	/**
	 * Create a new attachment
	 */
	public String createAttachment(CallContext callContext, ContentStream contentStream) {
		AttachmentNode a = new AttachmentNode();
		a.setType(AttachmentNode.TYPE);
		setSignature(callContext, a);
		return contentDaoService.createAttachment(a, contentStream);
	}

	@Override
	public Rendition getRendition(String streamId) {
		return contentDaoService.getRendition(streamId);
	}

	@Override
	public List<Rendition> getRenditions(String objectId) {
		Content c = getContentAsTheBaseType(objectId);
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
		for (String id : ids) {
			renditions.add(contentDaoService.getRendition(id));
		}
		return renditions;
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Change
	// //////////////////////////////////////////////////////////////////////////////
	@Override
	public List<Change> getLatestChanges(CallContext context,
			Holder<String> changeLogToken, Boolean includeProperties,
			String filter, Boolean includePolicyIds, Boolean includeAcl,
			BigInteger maxItems, ExtensionsData extension){
		int latestChangeLogToken = FIRST_TOKEN;
		if(changeLogToken != null && changeLogToken.getValue() != null && NumberUtils.isNumber(changeLogToken.getValue()))
			latestChangeLogToken = Integer.parseInt(changeLogToken.getValue()); 
		
		return contentDaoService.getLatestChanges(latestChangeLogToken, maxItems.intValue());
	}

	@Override
	public Change getChangeEvent(String token){
		return contentDaoService.getChangeEvent(token);
	}
	
	@Override
	public String getLatestChangeToken() {
		Change latest = contentDaoService.getLatestChange();
		if(latest == null){
			//TODO null is OK?
			return null;
		}else{
			return String.valueOf(latest.getChangeToken());
		}
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Archive
	// //////////////////////////////////////////////////////////////////////////////
	@Override
	public List<Archive> getAllArchives() {
		return contentDaoService.getAllArchives();
	}

	@Override
	public Archive getArchive(String archiveId) {
		return contentDaoService.getArchive(archiveId);
	}

	@Override
	public Archive getArchiveByOriginalId(String originalId) {
		return contentDaoService.getArchiveByOriginalId(originalId);
	}

	@Override
	public void restoreArchive(String archiveId) {
		Archive archive = contentDaoService.getArchive(archiveId);
		if (archive == null){
			log.error("Archive does not exist!");
			return;
		}
		
		// Check whether the destination does still extist.
		if (!restorationTargetExists(archive)) {
			log.error("The destination of the restoration doesn't exist");
			return;
		}

		// Switch　an operation depending on archive
		if (archive.isFolder()) {
			restoreFolder(archive);
		} else if (archive.isDocument()) {
			restoreDocument(archive);
		} else if (archive.isAttachment()) {
			log.error("Attachment can't be restored alone");
		} else {
			log.error("Only document or folder is supported for restoration");
		}
		
		//TODO What should be done about Change Event?
	}

	private void restoreDocument(Archive archive) {
		try {
			// Get archives of the same version series
			List<Archive> versions = contentDaoService
					.getArchivesOfVersionSeries(archive.getVersionSeriesId());
			for (Archive version : versions) {
				// Restore a document
				contentDaoService.restoreContent(version);
				// Restore its attachment
				Archive attachmentArchive = contentDaoService
						.getAttachmentArchive(version);
				contentDaoService.restoreAttachment(attachmentArchive);
				// delete archives
				contentDaoService.deleteArchive(version.getId());
				contentDaoService.deleteArchive(attachmentArchive.getId());
			}
		} catch (Exception e) {
			log.error("fail to restore a document", e);
		}
	}

	private void restoreFolder(Archive archive) {
		contentDaoService.restoreContent(archive);

		// Restore direct children
		List<Archive> children = contentDaoService.getChildArchives(archive);
		if (children != null) {
			for (Archive child : children) {
				//Restore descendants recursively
				// NOTE: Restored only when deletedWithParent flag is true
				if (child.isDeletedWithParent()) {
					restoreArchive(child.getId());
				}
			}
		}
		contentDaoService.deleteArchive(archive.getId());
	}

	private String buildUniqueName(String originalName, String folderId, String existingId) {
		List<Content> children = getChildren(folderId);

		List<String> conflicts = new ArrayList<String>();
		if (CollectionUtils.isEmpty(children)){
			return originalName;
		}else{
			//Get collection of conflict names
			for(Content child : children){
				//Exclude update of existing content in the folder
				if(existingId != null && !child.getId().equals(existingId)){
					if(nameConflicts(originalName, child.getName())){
						conflicts.add(child.getName());
					}
				}
			}
						
			if(CollectionUtils.isEmpty(conflicts)){
				return originalName;
			}else{
				//Insert unused suffix
				String nameWithSuffix = originalName;
				for(int i=0; i<conflicts.size() + 1; i++){
					nameWithSuffix = buildNameWithSuffix(originalName, i+1);
					if(!conflicts.contains(nameWithSuffix)){
						break;
					}
				}
				return nameWithSuffix;
			}
		}
	}
	
	private boolean nameConflicts(String originalName, String targetName) {
		String[] original = splitFileName(originalName);
		String[] target = splitFileName(targetName);
		
		if(!original[1].equals(target[1])) return false;
		if(removeCopySuffix(original[0]).equals(removeCopySuffix(target[0]))){
			return true;
		}else{
			return false;
		}
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

	private String buildNameWithSuffix(String fileName, int suffixNumber) {
		String[] split = splitFileName(fileName);
		String inserted = split[0] + "(" + suffixNumber + ")" + split[1];
		return inserted;
	}
	
	private String removeCopySuffix(String bodyOfFileName){
		String regexp = "\\([0-9]+\\)$";
		Pattern pattern = Pattern.compile(regexp);
		Matcher matcher = pattern.matcher(bodyOfFileName);
		if(matcher.find()){
			return  matcher.replaceFirst("");
		}else{
			return bodyOfFileName;
		}
	}

	private Boolean restorationTargetExists(Archive archive) {
		String parentId = archive.getParentId();
		Content parent = contentDaoService.getContent(parentId);
		if (parent == null) {
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Converts long value to GregorianCalendar class.
	 */
	private GregorianCalendar millisToCalendar(long millis) {
		GregorianCalendar calendar = new GregorianCalendar();
		calendar.setTimeZone(TimeZone.getTimeZone("GMT"));
		calendar.setTimeInMillis(millis);
		return calendar;
	}

	/**
	 * Reads a given property from a set of properties.
	 */
	private String getStringProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyString)) {
			return null;
		}

		return ((PropertyString) property).getFirstValue();
	}

	private String getIdProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyId)) {
			return null;
		}

		return ((PropertyId) property).getFirstValue();
	}

	private List<String> getStringListProperty(Properties properties,
			String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyString)) {
			return null;
		}

		return ((PropertyString) property).getValues();
	}

	private List<String> getIdListProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyId)) {
			return null;
		}

		return ((PropertyId) property).getValues();
	}

	private GregorianCalendar getTimeStamp() {
		return millisToCalendar(System.currentTimeMillis());
	}

	/**
	 * Parse CMIS extension to Nemaki Aspect model
	 * 
	 * @param properties
	 * @return aspects
	 */
	private List<Aspect> buildAspects(Properties properties) {
		List<CmisExtensionElement> exts = properties.getExtensions();
		if (CollectionUtils.isEmpty(exts))
			return null;
		
		CmisExtensionElement aspectsExt = extractAspectsExtension(exts);
		List<Aspect> aspects = new ArrayList<Aspect>();

		List<CmisExtensionElement> aspectsList = aspectsExt.getChildren();
		if(aspectsList != null && CollectionUtils.isNotEmpty(aspectsList)){
			//Aspect
			for(CmisExtensionElement aspectExt : aspectsList){
				if(aspectExt.getAttributes() == null ||
						StringUtils.isBlank(aspectExt.getAttributes().get(NemakiConstant.EXTATTR_ASPECT_ID))){
					log.equals("Cusotm aspect must have the id attribute");
					continue;
				}
				//Property
				List<Property> props = new ArrayList<Property>();
				List<CmisExtensionElement> propsList = aspectExt.getChildren();
				for (CmisExtensionElement propExt : propsList) {
					if(propExt.getAttributes() == null ||
							StringUtils.isBlank(propExt.getAttributes().get(NemakiConstant.EXTATTR_ASPECT_ID))){
						log.equals("Cusotm aspect's property must have the id attribute");
						continue;
					}
					props.add(new Property(propExt.getAttributes().get(NemakiConstant.EXTATTR_ASPECT_ID), propExt.getValue()));
				}
				aspects.add(new Aspect(aspectExt.getAttributes().get(NemakiConstant.EXTATTR_ASPECT_ID),props));
			}
		}
		return aspects;
	}
	
	private CmisExtensionElement extractAspectsExtension(List<CmisExtensionElement> list){
		for(CmisExtensionElement ce : list){
			if("aspects".equals(ce.getName())){
				return ce;
			}
		}
		return null;
	}

	private String increasedVersionLabel(Document document,
			VersioningState versioningState) {
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

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}
}
