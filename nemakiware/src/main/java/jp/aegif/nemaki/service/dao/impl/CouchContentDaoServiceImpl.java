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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

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
import jp.aegif.nemaki.model.constant.NodeType;
import jp.aegif.nemaki.model.couch.CouchArchive;
import jp.aegif.nemaki.model.couch.CouchAttachmentNode;
import jp.aegif.nemaki.model.couch.CouchChange;
import jp.aegif.nemaki.model.couch.CouchContent;
import jp.aegif.nemaki.model.couch.CouchDocument;
import jp.aegif.nemaki.model.couch.CouchFolder;
import jp.aegif.nemaki.model.couch.CouchItem;
import jp.aegif.nemaki.model.couch.CouchNodeBase;
import jp.aegif.nemaki.model.couch.CouchPolicy;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionCore;
import jp.aegif.nemaki.model.couch.CouchPropertyDefinitionDetail;
import jp.aegif.nemaki.model.couch.CouchRelationship;
import jp.aegif.nemaki.model.couch.CouchRendition;
import jp.aegif.nemaki.model.couch.CouchTypeDefinition;
import jp.aegif.nemaki.model.couch.CouchVersionSeries;
import jp.aegif.nemaki.service.dao.ContentDaoService;
import jp.aegif.nemaki.service.db.CouchConnector;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.Attachment;
import org.ektorp.AttachmentInputStream;
import org.ektorp.CouchDbConnector;
import org.ektorp.ViewQuery;
import org.ektorp.ViewResult;
import org.ektorp.ViewResult.Row;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

/**
 * Dao Service implementation for CouchDB.
 *
 * @author linzhixing
 *
 */
@Component
public class CouchContentDaoServiceImpl implements ContentDaoService {

	private CouchDbConnector connector;
	private CouchDbConnector archiveConnector;

	private static final Log log = LogFactory
			.getLog(CouchContentDaoServiceImpl.class);

	private static final String DESIGN_DOCUMENT = "_design/_repo";
	private static final String ATTACHMENT_NAME = "content";

	public CouchContentDaoServiceImpl() {

	}

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions() {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("typeDefinitions");
		List<CouchTypeDefinition> l = connector.queryView(query,
				CouchTypeDefinition.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			List<NemakiTypeDefinition> result = new ArrayList<NemakiTypeDefinition>();
			for (CouchTypeDefinition ct : l) {
				result.add(ct.convert());
			}
			return result;
		}
	}

	@Override
	public NemakiTypeDefinition getTypeDefinition(String typeId) {
		throw new UnsupportedOperationException(
				Thread.currentThread().getStackTrace()[0].getMethodName()
						+ ":this method is only for cahced service. No need for implementation.");
	}

	@Override
	public NemakiTypeDefinition createTypeDefinition(
			NemakiTypeDefinition typeDefinition) {
		CouchTypeDefinition ct = new CouchTypeDefinition(typeDefinition);
		connector.create(ct);
		return ct.convert();
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(
			NemakiTypeDefinition typeDefinition) {
		CouchTypeDefinition cp = connector.get(CouchTypeDefinition.class,
				typeDefinition.getId());
		CouchTypeDefinition update = new CouchTypeDefinition(typeDefinition);
		update.setRevision(cp.getRevision());

		connector.update(update);
		return update.convert();
	}

	@Override
	public void deleteTypeDefinition(String nodeId) {
		delete(nodeId);
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores() {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("propertyDefinitionCores");
		List<CouchPropertyDefinitionCore> l = connector.queryView(query,
				CouchPropertyDefinitionCore.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			List<NemakiPropertyDefinitionCore> result = new ArrayList<NemakiPropertyDefinitionCore>();
			for (CouchPropertyDefinitionCore cpdc : l) {
				result.add(cpdc.convert());
			}
			return result;
		}
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("propertyDefinitionCores").key(nodeId);
		List<CouchPropertyDefinitionCore> l = connector.queryView(query,
				CouchPropertyDefinitionCore.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(
			String propertyId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("propertyDefinitionCoresByPropertyId")
				.key(propertyId);
		List<CouchPropertyDefinitionCore> l = connector.queryView(query,
				CouchPropertyDefinitionCore.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(
			String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("propertyDefinitionDetails").key(nodeId);
		List<CouchPropertyDefinitionDetail> l = connector.queryView(query,
				CouchPropertyDefinitionDetail.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(
			String coreNodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("propertyDefinitionDetailsByCoreNodeId")
				.key(coreNodeId);
		List<CouchPropertyDefinitionDetail> l = connector.queryView(query,
				CouchPropertyDefinitionDetail.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			List<NemakiPropertyDefinitionDetail> result = new ArrayList<NemakiPropertyDefinitionDetail>();
			for (CouchPropertyDefinitionDetail cpdd : l) {
				result.add(cpdd.convert());
			}
			return result;
		}
	}

	@Override
	public NemakiPropertyDefinitionCore createPropertyDefinitionCore(
			NemakiPropertyDefinitionCore propertyDefinitionCore) {
		CouchPropertyDefinitionCore cpc = new CouchPropertyDefinitionCore(
				propertyDefinitionCore);
		connector.create(cpc);
		return cpc.convert();
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		CouchPropertyDefinitionDetail cpd = new CouchPropertyDefinitionDetail(
				propertyDefinitionDetail);
		connector.create(cpd);
		return cpd.convert();
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {

		CouchPropertyDefinitionDetail cpd = connector.get(
				CouchPropertyDefinitionDetail.class,
				propertyDefinitionDetail.getId());

		CouchPropertyDefinitionDetail update = new CouchPropertyDefinitionDetail(
				propertyDefinitionDetail);
		update.setRevision(cpd.getRevision());

		connector.update(update);
		return update.convert();
	}

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	@Override
	public NodeBase getNodeBase(String objectId) {
		CouchNodeBase cnb = connector.get(CouchNodeBase.class, objectId);
		return cnb.convert();
	}

	@Override
	public Content getContent(String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("contentsById").key(objectId);
		List<CouchContent> l = connector.queryView(query, CouchContent.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	// TODO Use view
	@Override
	public Document getDocument(String objectId) {
		CouchDocument cd = connector.get(CouchDocument.class, objectId);
		Document doc = cd.convert();
		return doc;
	}

	@Override
	public boolean existContent(String objectTypeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("countByObjectType").key(objectTypeId);
		ViewResult l = connector.queryView(query);
		List<Row> rows = l.getRows();
		if (CollectionUtils.isEmpty(rows)) {
			return false;
		} else {
			for (Row row : rows) {
				if (objectTypeId.equals(row.getKey())) {
					int count = row.getValueAsInt();
					if (count == 0) {
						return false;
					} else {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public List<Document> getCheckedOutDocuments(String parentFolderId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("privateWorkingCopies");
		if (parentFolderId != null)
			query.key(parentFolderId);
		List<CouchDocument> l = connector.queryView(query, CouchDocument.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		List<Document> results = new ArrayList<Document>();
		for (CouchDocument cd : l) {
			results.add(cd.convert());
		}
		return results;
	}

	@Override
	public VersionSeries getVersionSeries(String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("versionSeries").key(nodeId);
		List<CouchVersionSeries> l = connector.queryView(query,
				CouchVersionSeries.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public List<Document> getAllVersions(String versionSeriesId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("documentsByVersionSeriesId").key(versionSeriesId);

		List<CouchDocument> cds = connector.queryView(query,
				CouchDocument.class);
		if (CollectionUtils.isEmpty(cds))
			return null;
		List<Document> list = new ArrayList<Document>();
		for (CouchDocument cd : cds) {
			list.add(cd.convert());
		}
		return list;

	}

	@Override
	public Document getDocumentOfLatestVersion(String versionSeriesId) {
		if (versionSeriesId == null)
			return null;
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("latestVersions").key(versionSeriesId);
		List<CouchDocument> list = connector.queryView(query,
				CouchDocument.class);

		if (list.size() == 1) {
			return list.get(0).convert();
		} else if (list.size() > 1) {
			log.warn("The latest version of [" + versionSeriesId
					+ "] is duplicate.");
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Document getDocumentOfLatestMajorVersion(String versionSeriesId) {
		if (versionSeriesId == null)
			return null;
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("latestMajorVersions").key(versionSeriesId);
		List<CouchDocument> list = connector.queryView(query,
				CouchDocument.class);

		if (list.size() == 1) {
			return list.get(0).convert();
		} else if (list.size() > 1) {
			log.warn("The latest major version of [" + versionSeriesId
					+ "] is duplicate.");
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Folder getFolder(String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("folders").key(objectId);
		List<CouchFolder> list = connector.queryView(query, CouchFolder.class);

		if (CollectionUtils.isEmpty(list)) {
			return null;
		} else {
			return list.get(0).convert();
		}
	}

	@Override
	public Folder getFolderByPath(String path) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("foldersByPath").key(path);
		List<CouchFolder> l = connector.queryView(query, CouchFolder.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}

	@Override
	public List<Content> getLatestChildrenIndex(String parentId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("children").key(parentId);
		List<CouchContent> list = connector
				.queryView(query, CouchContent.class);

		if (list != null && !list.isEmpty()) {
			List<Content> contents = new ArrayList<Content>();
			for (CouchContent cc : list) {
				contents.add(cc.convert());
			}
			return contents;
		} else {
			return null;
		}
	}

	@Override
	public Content getChildByName(String parentId, String name) {
		JSONObject json = new JSONObject();
		json.put("parentId", parentId);
		json.put("name", name);
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("childByName").key(json);
		List<CouchContent> list = connector
				.queryView(query, CouchContent.class);

		if (CollectionUtils.isEmpty(list))
			return null;
		return list.get(0).convert();
	}

	@Override
	public Relationship getRelationship(String objectId) {
		CouchRelationship cr = connector.get(CouchRelationship.class, objectId);
		return cr.convert();
	}

	@Override
	public List<Relationship> getRelationshipsBySource(String sourceId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("relationshipsBySource").key(sourceId);
		List<CouchRelationship> crs = connector.queryView(query,
				CouchRelationship.class);

		List<Relationship> result = new ArrayList<Relationship>();
		if (crs != null && !crs.isEmpty()) {
			for (CouchRelationship cr : crs) {
				result.add(cr.convert());
			}
		}
		return result;
	}

	@Override
	public List<Relationship> getRelationshipsByTarget(String targetId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("relationshipsByTarget").key(targetId);
		List<CouchRelationship> crs = connector.queryView(query,
				CouchRelationship.class);

		List<Relationship> result = new ArrayList<Relationship>();
		if (crs != null && !crs.isEmpty()) {
			for (CouchRelationship cr : crs) {
				result.add(cr.convert());
			}
		}
		return result;
	}

	@Override
	public Policy getPolicy(String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("policies").key(objectId);
		List<CouchPolicy> cps = connector.queryView(query, CouchPolicy.class);
		if (!CollectionUtils.isEmpty(cps)) {
			return cps.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public List<Policy> getAppliedPolicies(String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("policiesByAppliedObject").key(objectId);
		List<CouchPolicy> cps = connector.queryView(query, CouchPolicy.class);
		if (!CollectionUtils.isEmpty(cps)) {
			List<Policy> policies = new ArrayList<Policy>();
			for (CouchPolicy cp : cps) {
				policies.add(cp.convert());
			}
			return policies;
		} else {
			return null;
		}
	}

	@Override
	public Item getItem(String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("items").key(objectId);
		List<CouchItem> cpi = connector.queryView(query, CouchItem.class);
		if (!CollectionUtils.isEmpty(cpi)) {
			return cpi.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Document create(Document document) {
		CouchDocument cd = new CouchDocument(document);
		connector.create(cd);
		return cd.convert();
	}

	@Override
	public VersionSeries create(VersionSeries versionSeries) {
		CouchVersionSeries cvs = new CouchVersionSeries(versionSeries);
		connector.create(cvs);
		return cvs.convert();
	}

	@Override
	public Folder create(Folder folder) {
		CouchFolder cf = new CouchFolder(folder);
		connector.create(cf);
		return cf.convert();
	}

	@Override
	public Relationship create(Relationship relationship) {
		CouchRelationship cr = new CouchRelationship(relationship);
		connector.create(cr);
		return cr.convert();
	}

	@Override
	public Policy create(Policy policy) {
		CouchPolicy cp = new CouchPolicy(policy);
		connector.create(cp);
		return cp.convert();
	}

	@Override
	public Item create(Item item) {
		CouchItem ci = new CouchItem(item);
		connector.create(ci);
		return ci.convert();
	}

	@Override
	public Document update(Document document) {
		CouchDocument cd = connector.get(CouchDocument.class, document.getId());

		// Set the latest revision for avoid conflict
		CouchDocument update = new CouchDocument(document);
		update.setRevision(cd.getRevision());

		connector.update(update);
		return update.convert();
	}

	@Override
	public VersionSeries update(VersionSeries versionSeries) {
		CouchVersionSeries cvs = connector.get(CouchVersionSeries.class,
				versionSeries.getId());

		// Set the latest revision for avoid conflict
		CouchVersionSeries update = new CouchVersionSeries(versionSeries);
		update.setRevision(cvs.getRevision());

		connector.update(update);
		return update.convert();
	}

	@Override
	public Folder update(Folder folder) {
		CouchFolder cf = connector.get(CouchFolder.class, folder.getId());
		// Set the latest revision for avoid conflict
		CouchFolder update = new CouchFolder(folder);
		update.setRevision(cf.getRevision());

		connector.update(update);

		return update.convert();
	}

	@Override
	public Relationship update(Relationship relationship) {
		CouchRelationship cp = connector.get(CouchRelationship.class,
				relationship.getId());
		// Set the latest revision for avoid conflict
		CouchRelationship update = new CouchRelationship(relationship);
		update.setRevision(cp.getRevision());

		connector.update(update);
		return update.convert();
	}

	@Override
	public Policy update(Policy policy) {
		CouchPolicy cp = connector.get(CouchPolicy.class, policy.getId());
		// Set the latest revision for avoid conflict
		CouchPolicy update = new CouchPolicy(policy);
		update.setRevision(cp.getRevision());

		connector.update(update);
		return update.convert();
	}

	@Override
	public Item update(Item item) {
		CouchItem ci = connector.get(CouchItem.class, item.getId());
		// Set the latest revision for avoid conflict
		CouchItem update = new CouchItem(item);
		update.setRevision(ci.getRevision());

		connector.update(update);
		return update.convert();
	}

	@Override
	public void delete(String objectId) {
		CouchNodeBase cnb = connector.get(CouchNodeBase.class, objectId);
		connector.delete(cnb);
	}

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	@Override
	public AttachmentNode getAttachment(String attachmentId) {

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("attachments").key(attachmentId);
		List<CouchAttachmentNode> list = connector.queryView(query,
				CouchAttachmentNode.class);

		if (CollectionUtils.isEmpty(list)) {
			return null;
		} else {
			CouchAttachmentNode can = list.get(0);

			Attachment a = can.getAttachments().get(ATTACHMENT_NAME);

			AttachmentNode an = new AttachmentNode();
			an.setId(can.getId());
			an.setMimeType(a.getContentType());
			an.setLength(a.getContentLength());
			an.setType(NodeType.ATTACHMENT.value());

			return an;
		}
	}

	@Override
	public void setStream(AttachmentNode attachmentNode) {
		AttachmentInputStream ais = connector.getAttachment(
				attachmentNode.getId(), ATTACHMENT_NAME);
		attachmentNode.setInputStream(ais);
	}

	@Override
	public Rendition getRendition(String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("renditions").key(objectId);
		List<CouchRendition> crnds = connector.queryView(query,
				CouchRendition.class);
		if (!CollectionUtils.isEmpty(crnds)) {
			CouchRendition crnd = crnds.get(0);
			Rendition rnd = crnd.convert();
			// Set an input stream
			InputStream is = connector.getAttachment(rnd.getId(),
					rnd.getTitle());
			rnd.setInputStream(is);
			try {
				BufferedImage bimg = ImageIO.read(is);
				if (bimg != null) {
					rnd.setWidth(bimg.getWidth());
					rnd.setHeight(bimg.getHeight());
					bimg.flush();
				}
			} catch (IOException e) {
				// TODO logging
				// do nothing
			}

			return rnd;

		} else {
			return null;
		}
	}

	@Override
	public String createAttachment(AttachmentNode attachment,
			ContentStream contentStream) {
		CouchAttachmentNode ca = new CouchAttachmentNode(attachment);
		connector.create(ca);

		AttachmentInputStream ais = new AttachmentInputStream(ATTACHMENT_NAME,
				contentStream.getStream(), contentStream.getMimeType(),
				contentStream.getLength());
		connector.createAttachment(ca.getId(), ca.getRevision(), ais);

		return ca.getId();
	}

	@Override
	public void updateAttachment(AttachmentNode attachment,
			ContentStream contentStream) {
		CouchAttachmentNode ca = connector.get(CouchAttachmentNode.class,
				attachment.getId());
		CouchAttachmentNode update = new CouchAttachmentNode(attachment);
		// Set the latest revision for avoid conflict
		update.setRevision(ca.getRevision());

		String revisionAfterDeleted = connector.deleteAttachment(ca.getId(),
				ca.getRevision(), ATTACHMENT_NAME);

		AttachmentInputStream ais = new AttachmentInputStream(ATTACHMENT_NAME,
				contentStream.getStream(), contentStream.getMimeType());
		connector.createAttachment(attachment.getId(), revisionAfterDeleted,
				ais);
	}

	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	@Override
	public Change getChangeEvent(String token) {
		Long _token =  null;
		try{
			_token = Long.valueOf(token);
		}catch(Exception e){
			log.error("Change token must be long type value", e);
		}

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("changesByToken").key(_token);
		List<CouchChange> l = connector.queryView(query, CouchChange.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}

	@Override
	public Change getLatestChange() {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("changesByCreated").descending(true).limit(1);
		List<CouchChange> l = connector.queryView(query, CouchChange.class);
		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public List<Change> getLatestChanges(String startToken, int maxItems) {
		long _startToken = 0;
		if(StringUtils.isNotBlank(startToken)){
			//null startToken means the first entry in the repository
			try{
				_startToken = Long.parseLong(startToken);
			}catch(Exception e){
				log.error("startToken=" + startToken + " must be numeric");
			}
		}

		Change latest = getLatestChange();
		long latestToken = 0;
		try{
			latestToken = Long.parseLong(latest.getChangeToken());
		}catch(Exception e){
			log.error("ChangeEvent(" + latest.getId() + ") changeToken is not numeric");
		}


		if (_startToken <= 0)
			_startToken = 0;
		if (latest == null || _startToken > latestToken){
			return null;
		}


		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("changesByToken").descending(false).startKey(_startToken).endKey(latestToken);
		if (maxItems > 0)
			query.limit(maxItems);

		List<CouchChange> l = connector.queryView(query, CouchChange.class);
		if (CollectionUtils.isEmpty(l))
			return null;
		List<Change> result = new ArrayList<Change>();
		for (CouchChange cc : l) {
			result.add(cc.convert());
		}
		return result;
	}

	@Override
	public Change create(Change change) {
		CouchChange cc = new CouchChange(change);
		connector.create(cc);
		return cc.convert();
	}

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	@Override
	public Archive getArchive(String archiveId) {
		CouchArchive ca = archiveConnector.get(CouchArchive.class, archiveId);
		return ca.convert();
	}

	@Override
	public Archive getArchiveByOriginalId(String originalId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("all").key(originalId);
		List<CouchArchive> list = archiveConnector.queryView(query,
				CouchArchive.class);

		if (list != null && !list.isEmpty()) {
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Archive getAttachmentArchive(Archive archive) {
		if (!archive.isDocument())
			return null;
		String attachmentId = archive.getAttachmentNodeId();
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("attachments").key(attachmentId);
		List<CouchArchive> list = archiveConnector.queryView(query,
				CouchArchive.class);

		if (list != null && !list.isEmpty()) {
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public List<Archive> getChildArchives(Archive archive) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("children").key(archive.getOriginalId());
		List<CouchArchive> list = archiveConnector.queryView(query,
				CouchArchive.class);

		if (list != null && !list.isEmpty()) {
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive ca : list) {
				archives.add(ca.convert());
			}
			return archives;
		} else {
			return null;
		}
	}

	@Override
	public List<Archive> getArchivesOfVersionSeries(String versionSeriesId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("versionSeries").key(versionSeriesId);
		List<CouchArchive> list = archiveConnector.queryView(query,
				CouchArchive.class);

		if (list != null && !list.isEmpty()) {
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive ca : list) {
				archives.add(ca.convert());
			}
			return archives;
		} else {
			return null;
		}
	}

	@Override
	public List<Archive> getAllArchives() {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT)
				.viewName("all");
		List<CouchArchive> list = archiveConnector.queryView(query,
				CouchArchive.class);

		List<Archive> archives = new ArrayList<Archive>();
		for (CouchArchive ca : list) {
			archives.add(ca.convert());
		}

		return archives;
	}

	@Override
	public Archive createArchive(Archive archive, Boolean deletedWithParent) {
		CouchNodeBase cnb = connector.get(CouchNodeBase.class,
				archive.getOriginalId());
		CouchArchive ca = new CouchArchive(archive);
		ca.setLastRevision(cnb.getRevision());

		// Write to DB
		archiveConnector.create(ca);
		return ca.convert();
	}

	@Override
	public Archive createAttachmentArchive(Archive archive) {
		CouchArchive ca = new CouchArchive(archive);
		CouchNodeBase cnb = connector.get(CouchNodeBase.class,
				archive.getOriginalId());
		ca.setLastRevision(cnb.getRevision());

		archiveConnector.create(ca);
		return ca.convert();
	}

	@Override
	// FIXME return archiveId or something when successfully deleted
	public void deleteArchive(String archiveId) {
		try {
			CouchArchive ca = archiveConnector.get(CouchArchive.class,
					archiveId);
			archiveConnector.delete(ca);
		} catch (Exception e) {
			log.warn(buildLogMsg(archiveId, "the archive not found on db"));
			return;
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void restoreContent(Archive archive) {
		if (archive.isDocument()) {
			// Restore a content
			CouchDocument cd = connector.get(CouchDocument.class,
					archive.getOriginalId(), archive.getLastRevision());
			cd.setRevision(null);
			connector.update(cd);
		} else if (archive.isFolder()) {
			CouchFolder cf = connector.get(CouchFolder.class,
					archive.getOriginalId(), archive.getLastRevision());
			cf.setRevision(null);
			connector.update(cf);
		} else {
			// TODO Do nothing?
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void restoreAttachment(Archive archive) {
		// Restore its attachment
		CouchAttachmentNode can = connector.get(CouchAttachmentNode.class,
				archive.getOriginalId(), archive.getLastRevision());
		can.setRevision(null);
		AttachmentInputStream is = connector.getAttachment(can.getId(),
				ATTACHMENT_NAME, archive.getLastRevision());
		connector.createAttachment(can.getId(), can.getRevision(), is);
		CouchAttachmentNode restored = connector.get(CouchAttachmentNode.class,
				can.getId());
		restored.setType(NodeType.ATTACHMENT.value());
		connector.update(restored);
	}

	// ///////////////////////////////////////
	// Other
	// ///////////////////////////////////////
	private String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}

	public void setConnector(CouchConnector connector) {
		this.connector = connector.getConnection();
	}

	public void setArchiveConnector(CouchConnector archiveConnector) {
		this.archiveConnector = archiveConnector.getConnection();
	}
}
