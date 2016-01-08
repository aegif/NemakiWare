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
package jp.aegif.nemaki.dao.impl.couch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
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
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.ConnectorPool;
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
import jp.aegif.nemaki.util.constant.NodeType;

/**
 * Dao Service implementation for CouchDB.
 *
 * @author linzhixing
 *
 */
@Component
public class ContentDaoServiceImpl implements ContentDaoService {

	private RepositoryInfoMap repositoryInfoMap;
	private ConnectorPool connectorPool;
	private static final Log log = LogFactory.getLog(ContentDaoServiceImpl.class);

	private static final String DESIGN_DOCUMENT = "_design/_repo";
	private static final String ATTACHMENT_NAME = "content";

	public ContentDaoServiceImpl() {

	}

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("typeDefinitions");
		List<CouchTypeDefinition> l = connectorPool.get(repositoryId).queryView(query, CouchTypeDefinition.class);

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
	public NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		throw new UnsupportedOperationException(Thread.currentThread().getStackTrace()[0].getMethodName()
				+ ":this method is only for cahced service. No need for implementation.");
	}

	@Override
	public NemakiTypeDefinition createTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		CouchTypeDefinition ct = new CouchTypeDefinition(typeDefinition);
		connectorPool.get(repositoryId).create(ct);
		return ct.convert();
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
		CouchTypeDefinition cp = connectorPool.get(repositoryId).get(CouchTypeDefinition.class, typeDefinition.getId());
		CouchTypeDefinition update = new CouchTypeDefinition(typeDefinition);
		update.setRevision(cp.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public void deleteTypeDefinition(String repositoryId, String nodeId) {
		delete(repositoryId, nodeId);
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("propertyDefinitionCores");
		List<CouchPropertyDefinitionCore> l = connectorPool.get(repositoryId)
				.queryView(query, CouchPropertyDefinitionCore.class);

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
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("propertyDefinitionCores").key(nodeId);
		List<CouchPropertyDefinitionCore> l = connectorPool.get(repositoryId)
				.queryView(query, CouchPropertyDefinitionCore.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("propertyDefinitionCoresByPropertyId")
				.key(propertyId);
		List<CouchPropertyDefinitionCore> l = connectorPool.get(repositoryId)
				.queryView(query, CouchPropertyDefinitionCore.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String repositoryId, String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("propertyDefinitionDetails")
				.key(nodeId);
		List<CouchPropertyDefinitionDetail> l = connectorPool.get(repositoryId)
				.queryView(query, CouchPropertyDefinitionDetail.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String repositoryId,
			String coreNodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("propertyDefinitionDetailsByCoreNodeId")
				.key(coreNodeId);
		List<CouchPropertyDefinitionDetail> l = connectorPool.get(repositoryId)
				.queryView(query, CouchPropertyDefinitionDetail.class);

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
	public NemakiPropertyDefinitionCore createPropertyDefinitionCore(String repositoryId,
			NemakiPropertyDefinitionCore propertyDefinitionCore) {
		CouchPropertyDefinitionCore cpc = new CouchPropertyDefinitionCore(propertyDefinitionCore);
		connectorPool.get(repositoryId).create(cpc);
		return cpc.convert();
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		CouchPropertyDefinitionDetail cpd = new CouchPropertyDefinitionDetail(propertyDefinitionDetail);
		connectorPool.get(repositoryId).create(cpd);
		return cpd.convert();
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId,
			NemakiPropertyDefinitionDetail propertyDefinitionDetail) {

		CouchPropertyDefinitionDetail cpd = connectorPool.get(repositoryId)
				.get(CouchPropertyDefinitionDetail.class, propertyDefinitionDetail.getId());

		CouchPropertyDefinitionDetail update = new CouchPropertyDefinitionDetail(propertyDefinitionDetail);
		update.setRevision(cpd.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	@Override
	public NodeBase getNodeBase(String repositoryId, String objectId) {
		CouchNodeBase cnb = connectorPool.get(repositoryId).get(CouchNodeBase.class, objectId);
		return cnb.convert();
	}

	@Override
	public Content getContent(String repositoryId, String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("contentsById").key(objectId);

		ViewResult result = connectorPool.get(repositoryId).queryView(query);
		return convertJsonToEachBaeType(result);
	}

	private Content convertJsonToEachBaeType(ViewResult result) {
		if (result.getRows().isEmpty()) {
			return null;
		} else {
			Iterator<Row> iterator = result.getRows().iterator();
			while (iterator.hasNext()) {
				ObjectMapper mapper = new ObjectMapper();
				JsonNode jn = iterator.next().getValueAsNode();
				String baseType = jn.path("type").textValue();

				if (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)) {
					CouchDocument cd = mapper.convertValue(jn, CouchDocument.class);
					return cd.convert();
				} else if (BaseTypeId.CMIS_FOLDER.value().equals(baseType)) {
					CouchFolder cf = mapper.convertValue(jn, CouchFolder.class);
					return cf.convert();
				} else if (BaseTypeId.CMIS_POLICY.value().equals(baseType)) {
					CouchPolicy cp = mapper.convertValue(jn, CouchPolicy.class);
					return cp.convert();
				} else if (BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)) {
					CouchRelationship cr = mapper.convertValue(jn, CouchRelationship.class);
					return cr.convert();
				} else if (BaseTypeId.CMIS_ITEM.value().equals(baseType)) {
					CouchItem ci = mapper.convertValue(jn, CouchItem.class);
					return ci.convert();
				}
			}
		}

		return null;
	}

	// TODO Use view
	@Override
	public Document getDocument(String repositoryId, String objectId) {
		CouchDocument cd = connectorPool.get(repositoryId).get(CouchDocument.class, objectId);
		Document doc = cd.convert();
		return doc;
	}

	@Override
	public boolean existContent(String repositoryId, String objectTypeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("countByObjectType").key(objectTypeId);
		ViewResult l = connectorPool.get(repositoryId).queryView(query);
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
	public List<Document> getCheckedOutDocuments(String repositoryId, String parentFolderId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("privateWorkingCopies");
		if (parentFolderId != null)
			query.key(parentFolderId);
		List<CouchDocument> l = connectorPool.get(repositoryId).queryView(query, CouchDocument.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		List<Document> results = new ArrayList<Document>();
		for (CouchDocument cd : l) {
			results.add(cd.convert());
		}
		return results;
	}

	@Override
	public VersionSeries getVersionSeries(String repositoryId, String nodeId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("versionSeries").key(nodeId);
		List<CouchVersionSeries> l = connectorPool.get(repositoryId).queryView(query, CouchVersionSeries.class);

		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public List<Document> getAllVersions(String repositoryId, String versionSeriesId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("documentsByVersionSeriesId")
				.key(versionSeriesId);

		List<CouchDocument> cds = connectorPool.get(repositoryId).queryView(query, CouchDocument.class);
		if (CollectionUtils.isEmpty(cds))
			return null;
		List<Document> list = new ArrayList<Document>();
		for (CouchDocument cd : cds) {
			list.add(cd.convert());
		}
		return list;

	}

	@Override
	public Document getDocumentOfLatestVersion(String repositoryId, String versionSeriesId) {
		if (versionSeriesId == null)
			return null;
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("latestVersions").key(versionSeriesId);
		List<CouchDocument> list = connectorPool.get(repositoryId).queryView(query, CouchDocument.class);

		if (list.size() == 1) {
			return list.get(0).convert();
		} else if (list.size() > 1) {
			log.warn("The latest version of [" + versionSeriesId + "] is duplicate.");
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Document getDocumentOfLatestMajorVersion(String repositoryId, String versionSeriesId) {
		if (versionSeriesId == null)
			return null;
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("latestMajorVersions")
				.key(versionSeriesId);
		List<CouchDocument> list = connectorPool.get(repositoryId).queryView(query, CouchDocument.class);

		if (list.size() == 1) {
			return list.get(0).convert();
		} else if (list.size() > 1) {
			log.warn("The latest major version of [" + versionSeriesId + "] is duplicate.");
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Folder getFolder(String repositoryId, String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("folders").key(objectId);
		List<CouchFolder> list = connectorPool.get(repositoryId).queryView(query, CouchFolder.class);

		if (CollectionUtils.isEmpty(list)) {
			return null;
		} else {
			return list.get(0).convert();
		}
	}

	@Override
	public Folder getFolderByPath(String repositoryId, String path) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("foldersByPath").key(path);
		List<CouchFolder> l = connectorPool.get(repositoryId).queryView(query, CouchFolder.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}

	@Override
	public List<Content> getLatestChildrenIndex(String repositoryId, String parentId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("children").key(parentId);
		List<CouchContent> list = connectorPool.get(repositoryId).queryView(query, CouchContent.class);

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
	public Content getChildByName(String repositoryId, String parentId, String name) {
		class ViewKey {
			private String parentId;
			private String name;

			public ViewKey(String parentId, String name) {
				this.parentId = parentId;
				this.name = name;
			}

			public String getParentId() {
				return parentId;
			}

			public String getName() {
				return name;
			}

		}

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("childByName")
				.key(new ViewKey(parentId, name));
		ViewResult result = connectorPool.get(repositoryId).queryView(query);

		return convertJsonToEachBaeType(result);
	}

	public List<String> getChildrenNames(String repositoryId, String parentId){
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("childrenNames")
				.key(parentId);
		ViewResult result = connectorPool.get(repositoryId).queryView(query);
		
		List<String>list =  new ArrayList<String>();
		if(result == null || result.isEmpty()){
			return new ArrayList<String>();
		}else{
			Iterator<Row> itr = result.iterator();
			while(itr.hasNext()){
				list.add(itr.next().getValue());
			}
		}
		
		return list;
	}
	
	@Override
	public Relationship getRelationship(String repositoryId, String objectId) {
		CouchRelationship cr = connectorPool.get(repositoryId).get(CouchRelationship.class, objectId);
		return cr.convert();
	}

	@Override
	public List<Relationship> getRelationshipsBySource(String repositoryId, String sourceId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("relationshipsBySource").key(sourceId);
		List<CouchRelationship> crs = connectorPool.get(repositoryId).queryView(query, CouchRelationship.class);

		List<Relationship> result = new ArrayList<Relationship>();
		if (crs != null && !crs.isEmpty()) {
			for (CouchRelationship cr : crs) {
				result.add(cr.convert());
			}
		}
		return result;
	}

	@Override
	public List<Relationship> getRelationshipsByTarget(String repositoryId, String targetId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("relationshipsByTarget").key(targetId);
		List<CouchRelationship> crs = connectorPool.get(repositoryId).queryView(query, CouchRelationship.class);

		List<Relationship> result = new ArrayList<Relationship>();
		if (crs != null && !crs.isEmpty()) {
			for (CouchRelationship cr : crs) {
				result.add(cr.convert());
			}
		}
		return result;
	}

	@Override
	public Policy getPolicy(String repositoryId, String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("policies").key(objectId);
		List<CouchPolicy> cps = connectorPool.get(repositoryId).queryView(query, CouchPolicy.class);
		if (!CollectionUtils.isEmpty(cps)) {
			return cps.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public List<Policy> getAppliedPolicies(String repositoryId, String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("policiesByAppliedObject")
				.key(objectId);
		List<CouchPolicy> cps = connectorPool.get(repositoryId).queryView(query, CouchPolicy.class);
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
	public Item getItem(String repositoryId, String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("items").key(objectId);
		List<CouchItem> cpi = connectorPool.get(repositoryId).queryView(query, CouchItem.class);
		if (!CollectionUtils.isEmpty(cpi)) {
			return cpi.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Document create(String repositoryId, Document document) {
		CouchDocument cd = new CouchDocument(document);
		connectorPool.get(repositoryId).create(cd);
		return cd.convert();
	}

	@Override
	public VersionSeries create(String repositoryId, VersionSeries versionSeries) {
		CouchVersionSeries cvs = new CouchVersionSeries(versionSeries);
		connectorPool.get(repositoryId).create(cvs);
		return cvs.convert();
	}

	@Override
	public Folder create(String repositoryId, Folder folder) {
		CouchFolder cf = new CouchFolder(folder);
		connectorPool.get(repositoryId).create(cf);
		return cf.convert();
	}

	@Override
	public Relationship create(String repositoryId, Relationship relationship) {
		CouchRelationship cr = new CouchRelationship(relationship);
		connectorPool.get(repositoryId).create(cr);
		return cr.convert();
	}

	@Override
	public Policy create(String repositoryId, Policy policy) {
		CouchPolicy cp = new CouchPolicy(policy);
		connectorPool.get(repositoryId).create(cp);
		return cp.convert();
	}

	@Override
	public Item create(String repositoryId, Item item) {
		CouchItem ci = new CouchItem(item);
		connectorPool.get(repositoryId).create(ci);
		return ci.convert();
	}

	@Override
	public Document update(String repositoryId, Document document) {
		CouchDocument cd = connectorPool.get(repositoryId).get(CouchDocument.class, document.getId());

		// Set the latest revision for avoid conflict
		CouchDocument update = new CouchDocument(document);
		update.setRevision(cd.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public VersionSeries update(String repositoryId, VersionSeries versionSeries) {
		CouchVersionSeries cvs = connectorPool.get(repositoryId).get(CouchVersionSeries.class, versionSeries.getId());

		// Set the latest revision for avoid conflict
		CouchVersionSeries update = new CouchVersionSeries(versionSeries);
		update.setRevision(cvs.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public Folder update(String repositoryId, Folder folder) {
		CouchFolder cf = connectorPool.get(repositoryId).get(CouchFolder.class, folder.getId());
		// Set the latest revision for avoid conflict
		CouchFolder update = new CouchFolder(folder);
		update.setRevision(cf.getRevision());

		connectorPool.get(repositoryId).update(update);

		return update.convert();
	}

	@Override
	public Relationship update(String repositoryId, Relationship relationship) {
		CouchRelationship cp = connectorPool.get(repositoryId).get(CouchRelationship.class, relationship.getId());
		// Set the latest revision for avoid conflict
		CouchRelationship update = new CouchRelationship(relationship);
		update.setRevision(cp.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public Policy update(String repositoryId, Policy policy) {
		CouchPolicy cp = connectorPool.get(repositoryId).get(CouchPolicy.class, policy.getId());
		// Set the latest revision for avoid conflict
		CouchPolicy update = new CouchPolicy(policy);
		update.setRevision(cp.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public Item update(String repositoryId, Item item) {
		CouchItem ci = connectorPool.get(repositoryId).get(CouchItem.class, item.getId());
		// Set the latest revision for avoid conflict
		CouchItem update = new CouchItem(item);
		update.setRevision(ci.getRevision());

		connectorPool.get(repositoryId).update(update);
		return update.convert();
	}

	@Override
	public void delete(String repositoryId, String objectId) {
		CouchNodeBase cnb = connectorPool.get(repositoryId).get(CouchNodeBase.class, objectId);
		connectorPool.get(repositoryId).delete(cnb);
	}

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	@Override
	public AttachmentNode getAttachment(String repositoryId, String attachmentId) {

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("attachments").key(attachmentId);
		List<CouchAttachmentNode> list = connectorPool.get(repositoryId).queryView(query, CouchAttachmentNode.class);

		if (CollectionUtils.isEmpty(list)) {
			return null;
		} else {
			CouchAttachmentNode can = list.get(0);

			Attachment a = can.getAttachments().get(ATTACHMENT_NAME);

			AttachmentNode an = new AttachmentNode();
			an.setId(can.getId());
			an.setMimeType(a.getContentType());
			an.setLength(a.getContentLength());

			return an;
		}
	}

	@Override
	public void setStream(String repositoryId, AttachmentNode attachmentNode) {
		AttachmentInputStream ais = connectorPool.get(repositoryId)
				.getAttachment(attachmentNode.getId(), ATTACHMENT_NAME);
		attachmentNode.setInputStream(ais);
	}

	@Override
	public Rendition getRendition(String repositoryId, String objectId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("renditions").key(objectId);
		List<CouchRendition> list = connectorPool.get(repositoryId).queryView(query, CouchRendition.class);

		if (CollectionUtils.isEmpty(list)) {
			return null;
		} else {
			CouchRendition crd = list.get(0);
			Attachment a = crd.getAttachments().get(ATTACHMENT_NAME);

			Rendition rd = new Rendition();
			rd.setId(objectId);
			rd.setTitle(crd.getTitle());
			rd.setKind(crd.getKind());
			rd.setMimetype(a.getContentType());
			rd.setLength(a.getContentLength());
			AttachmentInputStream ais = connectorPool.get(repositoryId).getAttachment(objectId, ATTACHMENT_NAME);
			rd.setInputStream(ais);

			/*
			 * try { BufferedImage bimg = ImageIO.read(ais); if (bimg != null) {
			 * rd.setWidth(bimg.getWidth()); rd.setHeight(bimg.getHeight());
			 * bimg.flush(); } } catch (IOException e) { // TODO logging // do
			 * nothing }
			 */

			return rd;
		}
	}

	@Override
	public String createAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		CouchAttachmentNode ca = new CouchAttachmentNode(attachment);
		connectorPool.get(repositoryId).create(ca);

		AttachmentInputStream ais = new AttachmentInputStream(ATTACHMENT_NAME, contentStream.getStream(), contentStream
				.getMimeType(), contentStream.getLength());
		connectorPool.get(repositoryId).createAttachment(ca.getId(), ca.getRevision(), ais);

		return ca.getId();
	}

	@Override
	public String createRendition(String repositoryId, Rendition rendition, ContentStream contentStream) {
		CouchRendition cr = new CouchRendition(rendition);
		connectorPool.get(repositoryId).create(cr);

		AttachmentInputStream ais = new AttachmentInputStream(ATTACHMENT_NAME, contentStream.getStream(), contentStream
				.getMimeType(), contentStream.getLength());
		connectorPool.get(repositoryId).createAttachment(cr.getId(), cr.getRevision(), ais);

		return cr.getId();
	}

	@Override
	public void updateAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		CouchAttachmentNode ca = connectorPool.get(repositoryId).get(CouchAttachmentNode.class, attachment.getId());
		CouchAttachmentNode update = new CouchAttachmentNode(attachment);
		// Set the latest revision for avoid conflict
		update.setRevision(ca.getRevision());

		String revisionAfterDeleted = connectorPool.get(repositoryId)
				.deleteAttachment(ca.getId(), ca.getRevision(), ATTACHMENT_NAME);

		AttachmentInputStream ais = new AttachmentInputStream(ATTACHMENT_NAME, contentStream.getStream(), contentStream
				.getMimeType());
		connectorPool.get(repositoryId).createAttachment(attachment.getId(), revisionAfterDeleted, ais);
	}

	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	@Override
	public Change getChangeEvent(String repositoryId, String changeTokenId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("changesByToken").key(changeTokenId);
		List<CouchChange> l = connectorPool.get(repositoryId).queryView(query, CouchChange.class);

		if (CollectionUtils.isEmpty(l))
			return null;
		return l.get(0).convert();
	}

	@Override
	public Change getLatestChange(String repositoryId) {
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("changesByToken").descending(true)
				.limit(1);
		List<CouchChange> l = connectorPool.get(repositoryId).queryView(query, CouchChange.class);
		if (CollectionUtils.isEmpty(l)) {
			return null;
		} else {
			return l.get(0).convert();
		}
	}

	@Override
	public List<Change> getLatestChanges(String repositoryId, String startToken, int maxItems) {
		List<Change> result = new ArrayList<Change>();
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("changesByToken").descending(false);

		if (StringUtils.isNotBlank(startToken)) {
			CouchDbConnector conn = connectorPool.get(repositoryId);
			try {
				CouchChange startChange = conn.get(CouchChange.class, startToken);
				Long startKey = startChange.getToken();
				query.startKey(startKey);
			} catch (org.ektorp.DocumentNotFoundException ex) {
				return null;
			}
		}
		if (maxItems > 0) {
			query.limit(maxItems);
		}

		List<CouchChange> l = connectorPool.get(repositoryId).queryView(query, CouchChange.class);
		if (CollectionUtils.isEmpty(l))
			return null;

		for (CouchChange cc : l) {
			result.add(cc.convert());
		}
		return result;
	}

	@Override
	public Change create(String repositoryId, Change change) {
		CouchChange cc = new CouchChange(change);
		connectorPool.get(repositoryId).create(cc);
		return cc.convert();
	}

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	@Override
	public Archive getArchive(String repositoryId, String objectId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);
		CouchArchive ca = connectorPool.get(archive).get(CouchArchive.class, objectId);
		return ca.convert();
	}

	@Override
	public Archive getArchiveByOriginalId(String repositoryId, String originalId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("all").key(originalId);
		List<CouchArchive> list = connectorPool.get(archive).queryView(query, CouchArchive.class);

		if (list != null && !list.isEmpty()) {
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public Archive getAttachmentArchive(String repositoryId, Archive archive) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		if (!archive.isDocument())
			return null;
		String attachmentId = archive.getAttachmentNodeId();
		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("attachments").key(attachmentId);
		List<CouchArchive> list = connectorPool.get(archiveId).queryView(query, CouchArchive.class);

		if (list != null && !list.isEmpty()) {
			return list.get(0).convert();
		} else {
			return null;
		}
	}

	@Override
	public List<Archive> getChildArchives(String repositoryId, Archive archive) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("children")
				.key(archive.getOriginalId());
		List<CouchArchive> list = connectorPool.get(archiveId).queryView(query, CouchArchive.class);

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
	public List<Archive> getArchivesOfVersionSeries(String repositoryId, String versionSeriesId) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("versionSeries").key(versionSeriesId);
		List<CouchArchive> list = connectorPool.get(archiveId).queryView(query, CouchArchive.class);

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
	public List<Archive> getAllArchives(String repositoryId) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		ViewQuery query = new ViewQuery().designDocId(DESIGN_DOCUMENT).viewName("all");
		List<CouchArchive> list = connectorPool.get(archiveId).queryView(query, CouchArchive.class);

		List<Archive> archives = new ArrayList<Archive>();
		for (CouchArchive ca : list) {
			archives.add(ca.convert());
		}

		return archives;
	}

	@Override
	public Archive createArchive(String repositoryId, Archive archive, Boolean deletedWithParent) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		CouchNodeBase cnb = connectorPool.get(repositoryId).get(CouchNodeBase.class, archive.getOriginalId());
		CouchArchive ca = new CouchArchive(archive);
		ca.setLastRevision(cnb.getRevision());

		// Write to DB
		connectorPool.get(archiveId).create(ca);
		return ca.convert();
	}

	@Override
	public Archive createAttachmentArchive(String repositoryId, Archive archive) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);

		CouchArchive ca = new CouchArchive(archive);
		CouchNodeBase cnb = connectorPool.get(repositoryId).get(CouchNodeBase.class, archive.getOriginalId());
		ca.setLastRevision(cnb.getRevision());

		connectorPool.get(archiveId).create(ca);
		return ca.convert();
	}

	@Override
	// FIXME return archiveId or something when successfully deleted
	public void deleteArchive(String repositoryId, String archiveId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);

		try {
			CouchArchive ca = connectorPool.get(archive).get(CouchArchive.class, archiveId);
			connectorPool.get(archive).delete(ca);
		} catch (Exception e) {
			log.warn(buildLogMsg(archiveId, "the archive not found on db"));
			return;
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void restoreContent(String repositoryId, Archive archive) {
		if (archive.isDocument()) {
			// Restore a content
			CouchDocument cd = connectorPool.get(repositoryId)
					.get(CouchDocument.class, archive.getOriginalId(), archive.getLastRevision());
			cd.setRevision(null);
			connectorPool.get(repositoryId).update(cd);
		} else if (archive.isFolder()) {
			CouchFolder cf = connectorPool.get(repositoryId)
					.get(CouchFolder.class, archive.getOriginalId(), archive.getLastRevision());
			cf.setRevision(null);
			connectorPool.get(repositoryId).update(cf);
		} else {
			// TODO Do nothing?
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void restoreAttachment(String repositoryId, Archive archive) {
		CouchDbConnector connector = connectorPool.get(repositoryId);

		// Restore its attachment
		CouchAttachmentNode can = connector
				.get(CouchAttachmentNode.class, archive.getOriginalId(), archive.getLastRevision());
		can.setRevision(null);
		AttachmentInputStream is = connector.getAttachment(can.getId(), ATTACHMENT_NAME, archive.getLastRevision());
		connector.createAttachment(can.getId(), can.getRevision(), is);
		CouchAttachmentNode restored = connector.get(CouchAttachmentNode.class, can.getId());
		restored.setType(NodeType.ATTACHMENT.value());
		connector.update(restored);
	}

	// ///////////////////////////////////////
	// Other
	// ///////////////////////////////////////
	private String buildLogMsg(String objectId, String msg) {
		return "[objectId:" + objectId + "]" + msg;
	}

	public void setConnectorPool(ConnectorPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

}
