package controllers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Relationship;
import org.apache.chemistry.opencmis.client.api.RelationshipType;
import org.apache.chemistry.opencmis.client.api.Rendition;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.play.java.Secure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import constant.PropertyKey;
import constant.Token;
import jp.aegif.nemaki.plugin.action.JavaBackedUIAction;
import jp.aegif.nemaki.plugin.action.UIActionContext;
import model.ActionPluginUIElement;
import model.Principal;
import net.lingala.zip4j.io.ZipOutputStream;
import net.lingala.zip4j.model.ZipModel;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import play.Logger;
import play.Logger.ALogger;
import play.data.DynamicForm;
import play.data.Form;
import play.i18n.Messages;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Result;
import util.CmisObjectTree;
import util.DateTimeUtil;
import util.NemakiConfig;
import util.RelationshipUtil;
import util.Util;
import util.authentication.NemakiProfile;
import views.html.node.blank;
import views.html.node.detail;
import views.html.node.detailFull;
import views.html.node.file;
import views.html.node.preview;
import views.html.node.property;
import views.html.node.relationship;
import views.html.node.relationship_create;
import views.html.node.search;
import views.html.node.searchFreeQuery;
import views.html.node.tree;
import views.html.node.version;

public class Node extends Controller {
	private static final ALogger logger = Logger.of(Node.class);

	@Inject
	public org.pac4j.core.config.Config config;

	private static Session getCmisSession(String repositoryId) {
		return CmisSessions.getCmisSession(repositoryId, ctx());
	}

	@Secure
	public Result index(String repositoryId) {
		Session session = getCmisSession(repositoryId);
		Folder root = session.getRootFolder();
		return showChildren(repositoryId, root.getId(), 0, "cmis:name DESC",null);
	}
	@Secure
	public Result index(String repositoryId, int currentPage) {
		Session session = getCmisSession(repositoryId);
		Folder root = session.getRootFolder();
		return showChildren(repositoryId, root.getId(), currentPage, "cmis:name DESC",null);
	}
	@Secure
	public Result index(String repositoryId, int currentPage, String orderBy) {
		Session session = getCmisSession(repositoryId);
		Folder root = session.getRootFolder();
		return showChildren(repositoryId, root.getId(), currentPage, orderBy,null);
	}

	@Secure
	public Result direct(String repositoryId, String objectId, String activateTabName) {
		NemakiProfile profile = Util.getProfile(ctx());
		Session session = getCmisSession(repositoryId);

		Document target = (Document) session.getObject(objectId);
		Folder parent = target.getParents().get(0);
		Document latest = target.getObjectOfLatestVersion(false);

		// Get user
		String userId = profile.getAttribute(Token.LOGIN_USER_ID, String.class);
		final String endPoint = Util.buildNemakiCoreRestRepositoryUri(repositoryId);
		String url = endPoint + "user/show/" + userId;
		JsonNode result = Util.getJsonResponse(ctx(), url);
		model.User user = new model.User();

		if ("success".equals(result.get("status").asText())) {
			JsonNode _user = result.get("user");
			user = new model.User(_user);
		} else {
			internalServerError("User retrieveing failure");
		}

		return ok(detailFull.render(repositoryId, target, parent.getId(), latest.getId(), activateTabName, user,
				session, profile));

	}
	@Secure
	public Result showChildren(String repositoryId, String objectId){
		return showChildren(repositoryId,  objectId, 0,"cmis:name DESC",null);
	}
	@Secure
	public Result showChildren(String repositoryId, String objectId, int currentPage){
		return showChildren(repositoryId,  objectId, currentPage,"cmis:name DESC",null);
	}
	
	@Secure
	public Result showChildren(String repositoryId, String objectId, int currentPage, String orderBy, String term){
		NemakiProfile profile = Util.getProfile(ctx());
		Session session = getCmisSession(repositoryId);
		ObjectId id = session.createObjectId(objectId);

		OperationContext cmisOpCtxParent = new OperationContextImpl();
		cmisOpCtxParent.setIncludeRelationships(IncludeRelationships.NONE);
		cmisOpCtxParent.setIncludeAcls(true);
		cmisOpCtxParent.setIncludeAllowableActions(true);
		
		CmisObject parent = session.getObject(id, cmisOpCtxParent);

		List<CmisObject> results = new ArrayList<CmisObject>();
		if (Util.isFolder(parent)) {
			Folder _parent = (Folder) parent;

			logger.warn("[Call Folder#getChildren]Begin");
			int maxItemsPerPage = Util.getNavigationPagingSize();
			int skipCount = maxItemsPerPage * currentPage;

			OperationContext cmisOpCtx = new OperationContextImpl();
			cmisOpCtx.setIncludeRelationships(IncludeRelationships.NONE);
			cmisOpCtx.setIncludeAcls(true);
			cmisOpCtx.setIncludeAllowableActions(true);
			cmisOpCtx.setMaxItemsPerPage(maxItemsPerPage);
			if(orderBy != null){
				cmisOpCtx.setOrderBy(orderBy);
			}

			ItemIterable<CmisObject> allChildren = _parent.getChildren(cmisOpCtx);
			ItemIterable<CmisObject> children = allChildren.skipTo(skipCount).getPage();
			long totalItemCount = allChildren.getTotalNumItems();


			Iterator<CmisObject> itr = children.iterator();
			while (itr.hasNext()) {
				CmisObject obj = itr.next();

				// Check and replace to PWC for owner
				if (Util.isDocument(obj)) {
					Document doc = (Document) obj;
					if (doc.isVersionSeriesCheckedOut()) {
						// check owner
						String userId = profile.getAttribute(Token.LOGIN_USER_ID, String.class);
						String owner = doc.getVersionSeriesCheckedOutBy();
						if (userId.equals(owner)) {
							String pwcId = doc.getVersionSeriesCheckedOutId();
							CmisObject pwc = session.getObject(pwcId);
							results.add(pwc);
							continue;
						}
					}
				}
				results.add(obj);
			}

		logger.warn("[Call Folder#getChildren]End");

		// Fill in CMIS types
		List<Tree<ObjectType>> typeFolders = session.getTypeDescendants(BaseTypeId.CMIS_FOLDER.value(), -1, false);
		List<Tree<ObjectType>> typeDocs = session.getTypeDescendants(BaseTypeId.CMIS_DOCUMENT.value(), -1, false);
		List<Tree<ObjectType>> types = new ArrayList<Tree<ObjectType>>();
		types.addAll(typeFolders);
		types.addAll(typeDocs);

		List<String> enableTypes = NemakiConfig.getValues(PropertyKey.UI_VISIBILITY_CREATE_OBJECT);

		List<Tree<ObjectType>> viewTypes = types.stream().filter(p -> enableTypes.contains(p.getItem().getLocalName()))
				.collect(Collectors.toList());

			return ok(tree.render(repositoryId, _parent, results, viewTypes, session, profile, currentPage, totalItemCount,orderBy ,term));
		}else{
			return internalServerError();
		}
	}

	@Secure
	public Result showChildrenByPath(String repositoryId, String term) {
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObjectByPath(term);

		return showChildren(repositoryId, o.getId(), 0 ,null,term);
	}
	@Secure
	public Result showChildrenByPath(String repositoryId, String term, int currentPage) {
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObjectByPath(term);

		return showChildren(repositoryId, o.getId(), currentPage, null,term);
	}
	@Secure
	public Result showChildrenByPath(String repositoryId, String term, int currentPage, String orderBy) {
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObjectByPath(term);

		return showChildren(repositoryId, o.getId(), currentPage, orderBy,term);
	}

	@Secure
	public Result search(String repositoryId, String term, int currentPage, String orderBy) {

		if (term.startsWith("[cmis]")) {
			return this.searchFreeQuery(repositoryId, term);
		}
		if (term.trim().isEmpty()){
			return this.index(repositoryId);
		}

		NemakiProfile profile = Util.getProfile(ctx());
		Session session = getCmisSession(repositoryId);

		OperationContext ctxt = session.getDefaultContext();

		List<CmisObject> list = new ArrayList<CmisObject>();
		// Build WHERE clause(cmis:document)
		MessageFormat docFormat = new MessageFormat(
				"cmis:isLatestVersion=true AND ( cmis:name LIKE ''%{0}%'' OR cmis:description LIKE ''%{0}%'' OR CONTAINS(''{0}'') )");
		String docStatement = "";
		if (StringUtils.isNotBlank(term)) {
			docStatement = docFormat.format(new String[] { term.replaceAll("%", "\\%").replaceAll("_", "\\_") });
		}

		int maxItemsPerPage = Util.getNavigationPagingSize();
		int skipCount = maxItemsPerPage * currentPage;

		ItemIterable<CmisObject> allResults = session.queryObjects("cmis:document", docStatement, false, ctxt);
		ItemIterable<CmisObject> docResults = allResults.skipTo(skipCount).getPage(maxItemsPerPage);
		Iterator<CmisObject> docItr = docResults.iterator();
		long totalItemCount = docResults.getTotalNumItems();

		while (docItr.hasNext()) {
			CmisObject doc = docItr.next();
			boolean val = doc.getPropertyValue("cmis:isLatestVersion");
			if (!val)
				continue;
			list.add(doc);
		}

		return ok(search.render(repositoryId, term, list, session, profile, currentPage, totalItemCount, orderBy));
	}

	private Result searchFreeQuery(String repositoryId, String term) {

		NemakiProfile profile = Util.getProfile(ctx());
		Session session = getCmisSession(repositoryId);
		OperationContext ctxt = session.getDefaultContext();

		String pureQuery = term.replace("[cmis]", "");

		ItemIterable<QueryResult> results = session.query(pureQuery, false, ctxt);
		Iterator<QueryResult> resultItr = results.iterator();
		List<QueryResult> qrList = new ArrayList<QueryResult>();

		while (resultItr.hasNext()) {
			qrList.add(resultItr.next());
		}
		return ok(searchFreeQuery.render(repositoryId, term, qrList, profile));
	}

	@Secure
	public Result showBlank(String repositoryId) {
		String parentId = request().getQueryString("parentId");
		String objectTypeId = request().getQueryString("objectType");

		if (StringUtils.isEmpty(objectTypeId)) {
			objectTypeId = "cmis:folder"; // default
		}

		Session session = getCmisSession(repositoryId);
		ObjectType objectType = session.getTypeDefinition(objectTypeId);

		return ok(blank.render(repositoryId, parentId, objectType));
	}

	@Secure
	public Result showDetail(String repositoryId, String id, String activateTabName) {
		Session session = getCmisSession(repositoryId);

		FileableCmisObject o = (FileableCmisObject) session.getObject(id);

		// Get parentId
		String parentId = o.getParents().get(0).getId();

		// Get user
		NemakiProfile profile = Util.getProfile(ctx());
		String userId = profile.getAttribute(Token.LOGIN_USER_ID, String.class);
		final String endPoint = Util.buildNemakiCoreRestRepositoryUri(repositoryId);
		String url = endPoint + "user/show/" + userId;
		JsonNode result = Util.getJsonResponse(ctx(), url);
		model.User user = new model.User();

		if ("success".equals(result.get("status").asText())) {
			JsonNode _user = result.get("user");
			user = new model.User(_user);
		} else {
			internalServerError("User retrieveing failure");
		}

		return ok(detail.render(repositoryId, o, parentId, activateTabName, user, session));
	}

	@Secure
	public Result showProperty(String repositoryId, String id) {
		try {
			Session session = getCmisSession(repositoryId);

			FileableCmisObject o = (FileableCmisObject) session.getObject(id);

			List<Property<?>> properties = o.getProperties();
			List<SecondaryType> secondaryTypes = o.getSecondaryTypes();

			// divide
			List<Property<?>> primaries = new ArrayList<Property<?>>();
			Map<SecondaryType, List<Property<?>>> secondaries = new HashMap<SecondaryType, List<Property<?>>>();

			if (CollectionUtils.isNotEmpty(secondaryTypes)) {
				Iterator<SecondaryType> itr = secondaryTypes.iterator();
				while (itr.hasNext()) {
					SecondaryType st = itr.next();
					secondaries.put(st, new ArrayList<Property<?>>());
				}
			}

			for (Property<?> p : properties) {
				boolean isSecondary = false;

				try {
					if (CollectionUtils.isNotEmpty(secondaryTypes)) {
						Iterator<SecondaryType> itr2 = secondaryTypes.iterator();
						while (itr2.hasNext()) {
							SecondaryType st = itr2.next();
							if (st.getPropertyDefinitions().containsKey(p.getId())) {
								secondaries.get(st).add(p);
								isSecondary = true;
							}
						}
					}

					if (!isSecondary) {
						primaries.add(p);
					}
				} catch (Exception ex) {
					logger.error("Property Error name=" + p.getDisplayName());
				}
			}

			return ok(property.render(repositoryId, o, primaries, secondaries));
		} catch (Exception ex) {
			logger.error("Error", ex);
			return internalServerError();
		}
	}

	@Secure
	public Result showFile(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		FileableCmisObject o = (FileableCmisObject) session.getObject(id);

		// Get parentId
		String parentId = o.getParents().get(0).getId();

		return ok(file.render(repositoryId, o, parentId));

	}

	@Secure
	public Result downloadWithRelationTargetAsCompressedFile(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject cmisObject = session.getObject(id);

		// Relation target
		List<Relationship> rels = cmisObject.getRelationships();
		List<Document> list = null;
		if (rels != null) {
			try {
				list = rels.stream().filter(p -> id.equals(p.getSourceId().getId())).map(Relationship::getTargetId)
						.distinct().map(p -> session.getObject(p)).filter(p -> Util.isDocument(p))
						.map(p -> (Document) p).collect(Collectors.toList());
			} catch (CmisObjectNotFoundException ex) {
				logger.error("Source or target cmis object not found.", ex);
			}
		}

		if (Util.isDocument(cmisObject)) {
			list.add((Document) cmisObject);
		}

		File tempFile = null;
		try {
			// Error too large file
			long maxsize = Util.getCompressionTargetMaxSize();
			Long allDocSum = list.stream().mapToLong(p -> p.getContentStreamLength()).sum();
			if (allDocSum > Util.getCompressionTargetMaxSize()) {
				String errmsg = Messages.get("view.message.compress.error.toolarge", maxsize);
				return internalServerError(errmsg);
			}

			// Archive
			ZipParameters parameters = new ZipParameters();
			parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
			parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
			parameters.setSourceExternalStream(true);

			ZipModel zipModel = new ZipModel();
			String prefix = NemakiConfig.getValue(PropertyKey.COMPRESSION_FILE_PREFIX);
			Path tempPath = Files.createTempFile(prefix, ".zip");
			tempFile = tempPath.toFile();
			tempFile.deleteOnExit();

			try (ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(tempFile), zipModel)) {

				for (Document doc : list) {
					ZipParameters params = (ZipParameters) parameters.clone();
					params.setFileNameInZip(doc.getName());
					outputStream.putNextEntry(null, params);

					try (InputStream inputStream = doc.getContentStream().getStream();) {
						byte[] readBuff = new byte[4096];
						int readLen = -1;
						while ((readLen = inputStream.read(readBuff)) != -1) {
							outputStream.write(readBuff, 0, readLen);
						}
					}
					outputStream.closeEntry();
				}
				outputStream.finish();
			}

		} catch (Exception e) {
			logger.error("Zip packing error", e);
		}
		String fileName = FilenameUtils.getBaseName(cmisObject.getName());
		createAttachmentResponse(fileName + ".zip", "application/zip");
		return ok(tempFile);
	}

	@Secure
	public Result downloadAsCompressedFile(String repositoryId, String id) {
		return downloadAsCompressedFileByBatch(repositoryId, Arrays.asList(id));
	}

	@Secure
	public Result downloadAsCompressedFileByBatch(String repositoryId, List<String> ids) {
		Session session = getCmisSession(repositoryId);
		File tempFile = null;

		try {
			CmisObjectTree tree = new CmisObjectTree(session);
			tree.buildTree(ids.toArray(new String[0]));

			// Erro too large file
			long maxsize = Util.getCompressionTargetMaxSize();
			if (tree.getContentsSize() > Util.getCompressionTargetMaxSize()) {
				String errmsg = Messages.get("view.message.compress.error.toolarge", maxsize);
				return internalServerError(errmsg);
			}

			// Archive
			ZipParameters parameters = new ZipParameters();
			parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
			parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
			parameters.setSourceExternalStream(true);

			ZipModel zipModel = new ZipModel();
			String prefix = NemakiConfig.getValue(PropertyKey.COMPRESSION_FILE_PREFIX);
			Path tempPath = Files.createTempFile(prefix, ".zip");
			tempFile = tempPath.toFile();
			tempFile.deleteOnExit();

			try (ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(tempFile), zipModel)) {
				HashMap<String, CmisObject> map = tree.getHashMap();
				for (String key : map.keySet()) {
					ZipParameters params = (ZipParameters) parameters.clone();
					params.setFileNameInZip(StringUtils.stripStart(key, "/"));

					outputStream.putNextEntry(null, params);

					CmisObject obj = map.get(key);
					if (Util.isDocument(obj)) {
						try (InputStream inputStream = ((Document) obj).getContentStream().getStream();) {
							byte[] readBuff = new byte[4096];
							int readLen = -1;
							while ((readLen = inputStream.read(readBuff)) != -1) {
								outputStream.write(readBuff, 0, readLen);
							}
						}
					}
					outputStream.closeEntry();
				}
				outputStream.finish();
			}

		} catch (Exception e) {
			logger.error("Zip packing error", e);
		}

		createAttachmentResponse("compressed-files.zip", "application/zip");
		return ok(tempFile);
	}

	@Secure
	public Result download(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);
		if (!Util.isDocument(obj)) {
			// TODO logging
			return badRequest();
		}

		Document doc = (Document) obj;
		ContentStream cs = doc.getContentStream();
		createAttachmentResponse(doc.getName(), cs.getMimeType());

		File tmpFile = null;
		try {
			tmpFile = Util.convertInputStreamToFile(cs);
			TemporaryFileInputStream fin = new TemporaryFileInputStream(tmpFile);
			return ok(fin);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return internalServerError("File not found");
		} catch (Exception e) {
			e.printStackTrace();
		}

		createAttachmentResponse(doc.getName(), cs.getMimeType());
		return ok(tmpFile);
	}

	private static void createAttachmentResponse(String name, String mimeType) {
		try {
			if (request().getHeader("User-Agent").indexOf("MSIE") == -1) {
				// Firefox, Opera 11
				response().setHeader("Content-Disposition", String.format(Locale.JAPAN,
						"attachment; filename*=utf-8'jp'%s", URLEncoder.encode(name, "utf-8")));
			} else {
				// IE7, 8, 9
				response().setHeader("Content-Disposition", String.format(Locale.JAPAN, "attachment; filename=\"%s\"",
						new String(name.getBytes("MS932"), "ISO8859_1")));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		response().setContentType(mimeType);
	}

	@Secure
	public Result downloadPreview(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);
		if (!Util.isDocument(obj)) {
			// TODO logging
			return badRequest();
		}
		if (!Util.existPreview(obj)) {
			// TODO logging
			return badRequest();
		}

		List<Rendition> renditions = obj.getRenditions();
		Rendition preview = null;
		for (Rendition rendition : renditions) {
			if ("cmis:preview".equals(rendition.getKind())) {
				preview = rendition;
			}
		}

		// Download
		File tmpFile = null;
		try {
			tmpFile = Util.convertInputStreamToFile(preview.getContentStream());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// TODO better solution for encoding file name

		response().setHeader("Content-disposition", "filename=" + obj.getName() + ".pdf");
		response().setContentType(preview.getContentStream().getMimeType());

		try {
			TemporaryFileInputStream fin = new TemporaryFileInputStream(tmpFile);
			return ok(fin);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return internalServerError("File not found");
		}

	}

	@Secure
	public Result showVersion(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject o = session.getObject(id);

		List<Document> result = new ArrayList<Document>();

		if (Util.isDocument(o)) {
			Document doc = (Document) o;
			result = doc.getAllVersions();
		}

		return ok(version.render(repositoryId, result, id));

	}

	@Secure
	public Result showRelationshipCreate(String repositoryId, String objectId) {
		Session session = getCmisSession(repositoryId);

		ObjectId id = session.createObjectId(objectId);
		OperationContext cmisOpCtx = new OperationContextImpl();
		cmisOpCtx.setIncludeRelationships(IncludeRelationships.BOTH);
		cmisOpCtx.setIncludeAcls(true);
		cmisOpCtx.setIncludeAllowableActions(true);
		CmisObject obj = session.getObject(id, cmisOpCtx);

		String parentId = null;
		if (Util.isDocument(obj)) {
			Document doc = (Document) obj;
			parentId = doc.getParents().get(0).getId();
		} else if (Util.isDocument(obj)) {
			Folder folder = (Folder) obj;
			parentId = folder.getFolderParent().getId();
		}

		List<String> enableTypes = NemakiConfig.getValues(PropertyKey.UI_VISIBILITY_CREATE_RELATIONSHIP);

		List<RelationshipType> viewTypesTemp = session.getTypeDescendants(null, -1, true).stream().map(Tree::getItem)
				.filter(p -> p.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP).map(p -> (RelationshipType) p)
				.filter(p -> enableTypes.contains(p.getLocalName())).collect(Collectors.toList());

		List<RelationshipType> viewTypes = viewTypesTemp.stream()
				.filter(p -> p.getAllowedSourceTypes().contains(obj.getType())).collect(Collectors.toList());

		Set<ObjectType> targetTypes = viewTypes.stream().flatMap(p -> p.getAllowedTargetTypes().stream()).distinct()
				.collect(Collectors.toSet());

		return ok(relationship_create.render(repositoryId, obj, parentId, viewTypes, targetTypes));
	}

	@Secure
	public Result showRelationship(String repositoryId, String objectId) {
		Session session = getCmisSession(repositoryId);

		ObjectId id = session.createObjectId(objectId);
		OperationContext cmisOpCtx = new OperationContextImpl();
		cmisOpCtx.setIncludeRelationships(IncludeRelationships.BOTH);
		cmisOpCtx.setIncludeAcls(true);
		cmisOpCtx.setIncludeAllowableActions(true);
		CmisObject obj = session.getObject(id, cmisOpCtx);


		List<Relationship> relationships = obj.getRelationships();
		List<Relationship> result = new ArrayList<Relationship>();
		if (relationships != null) {
			result = relationships.stream().filter(r -> {
				try {
					r.getTarget();
				} catch (CmisObjectNotFoundException e) {
					return false;
				}
				try {
					r.getSource();
				} catch (CmisObjectNotFoundException e) {
					return false;
				}
				return true;
			}).collect(Collectors.toList());
		}

		return ok(relationship.render(repositoryId, obj, result));
	}

	@Secure
	public Result showPreview(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(id);

		return ok(preview.render(repositoryId, obj));
	}

	@Secure
	public Result showPermission(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);

		// Get permission types
		List<PermissionDefinition> permissionDefs = Util
				.trimForDisplay(session.getRepositoryInfo().getAclCapabilities().getPermissions());

		// Principals
		List<Principal> members = new ArrayList<Principal>();
		if (obj.getAcl() != null) {
			List<Ace> list = obj.getAcl().getAces();
			if (CollectionUtils.isNotEmpty(list)) {
				String anyone = session.getRepositoryInfo().getPrincipalIdAnyone();
				String anonymous = session.getRepositoryInfo().getPrincipalIdAnonymous();
				for (Ace ace : list) {
					String principalId = ace.getPrincipalId();
					// call API
					Principal p = getPrincipal(repositoryId, principalId, anyone, anonymous);
					if (p != null) {
						members.add(p);
					}
				}
			}
		}

		// for IE
		// ResponseUtil.setNoCache(response());
		return ok(views.html.node.permission.render(repositoryId, obj, members, permissionDefs));
	}

	@Secure
	public Result showAction(String repositoryId, String id, String actionId) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);

		ActionPluginUIElement elm = Util.getActionPluginUIElement(obj, actionId, session);

		return ok(views.html.node.action.render(repositoryId, obj, elm));
	}

	@Secure
	public Result doAction(String repositoryId, String id, String actionId) {
		JsonNode json = request().body().asJson();

		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(id);
		JavaBackedUIAction action = Util.getActionPlugin(obj, actionId, session);
		UIActionContext context = new UIActionContext(obj, session);
		String result = action.executeAction(context, json.toString());
		return ok(result);
	}

	/**
	 * Handle with a file per each request Multiple drag & drop should be made
	 * by calling this as many times
	 *
	 * @param repositoryId
	 * @param action
	 * @return
	 */
	@Secure
	public Result dragAndDrop(String repositoryId, String action) {
		// Bind input parameters
		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		// Process the file
		MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if (CollectionUtils.isEmpty(files)) {
			// Do nothing
			return ok();
		} else {
			if ("create".equals(action)) {
				dragAndDropCreate(repositoryId, files.get(0), input);
			} else if ("update".equals(action)) {
				dragAndDropUpdate(repositoryId, files.get(0), input);
			} else {
				return internalServerError("Specify drag & drop action type");
			}

			return ok();
		}
	}

	private static void setParam(Map<String, Object> param, DynamicForm input, String key) {
		param.put(key, Util.getFormData(input, key));
	}

	private static void dragAndDropCreate(String repositoryId, FilePart file, DynamicForm input) {
		// Parse parameters
		Map<String, Object> param = new HashMap<String, Object>();
		ObjectId parentId = new ObjectIdImpl(Util.getFormData(input, PropertyIds.PARENT_ID));
		setParam(param, input, PropertyIds.OBJECT_TYPE_ID);
		setParam(param, input, PropertyIds.NAME);

		// Execute
		Session session = getCmisSession(repositoryId);
		ContentStream cs = Util.convertFileToContentStream(session, file);
		session.createDocument(param, parentId, cs, VersioningState.MAJOR);

		// Clean temp file just after CMIS createDocument finished
		file.getFile().delete();
	}

	private static void dragAndDropUpdate(String repositoryId, FilePart file, DynamicForm input) {
		// Parse parameters
		Map<String, Object> param = new HashMap<String, Object>();
		ObjectId objectId = new ObjectIdImpl(Util.getFormData(input, PropertyIds.OBJECT_ID));
		setParam(param, input, PropertyIds.OBJECT_TYPE_ID);
		// setParam(param, input, PropertyIds.NAME);

		// Execute
		Session session = getCmisSession(repositoryId);
		ContentStream cs = Util.convertFileToContentStream(session, file);
		Document doc = (Document) session.getObject(objectId);
		doc.setContentStream(cs, true);

		// Clean temp file just after CMIS createDocument finished
		file.getFile().delete();
	}

	@Secure
	public Result create(String repositoryId) {
		NemakiProfile profile = Util.getProfile(ctx());

		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		// Extract special property
		String objectTypeId = Util.getFormData(input, PropertyIds.OBJECT_TYPE_ID);
		String _parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
		ObjectId parentId = new ObjectIdImpl(_parentId);

		Session session = getCmisSession(repositoryId);

		ObjectType objectType = session.getTypeDefinition(objectTypeId);

		// Set CMIS parameter
		Map<String, PropertyDefinition<?>> pdfs = session.getTypeDefinition(objectTypeId).getPropertyDefinitions();
		Map<String, String> stringMap = Util.createPropFormDataMap(pdfs, input);

		List<Updatability> upds = new ArrayList<Updatability>();
		upds.add(Updatability.ONCREATE);
		upds.add(Updatability.READWRITE);

		HashMap<String, Object> param = Util.buildProperties(pdfs, input, upds, ctx().lang().toLocale());
		param.put(PropertyIds.OBJECT_TYPE_ID, objectTypeId);

		// Document/Folder specific
		switch (Util.getBaseType(session, objectTypeId)) {
		case CMIS_DOCUMENT:
			ContentStreamAllowed csa = ((DocumentTypeDefinition) objectType).getContentStreamAllowed();

			if (csa == ContentStreamAllowed.NOTALLOWED) {
				// don't set content stream
				session.createDocument(param, parentId, null, VersioningState.MAJOR);
			} else {
				List<FilePart> files = null;
				MultipartFormData body = request().body().asMultipartFormData();
				if (body != null && CollectionUtils.isNotEmpty(body.getFiles())) {
					files = body.getFiles();
				}

				if (CollectionUtils.isEmpty(files)) {
					// Case: no file
					if (csa == ContentStreamAllowed.REQUIRED) {
						return internalServerError(objectTypeId + ":This type requires a file");
					} else if (csa == ContentStreamAllowed.ALLOWED) {
						session.createDocument(param, parentId, null, VersioningState.MAJOR);
					}
				} else {
					// Case: file exists
					ContentStream contentStream = Util.convertFileToContentStream(session, files.get(0));
					if (param.get(PropertyIds.NAME) == null) {
						param.put(PropertyIds.NAME, contentStream.getFileName());
					}
					session.createDocument(param, parentId, contentStream, VersioningState.MAJOR);

					// Clean temp file just after CMIS createDocument finished
					if (CollectionUtils.isNotEmpty(files)) {
						for (FilePart file : files) {
							file.getFile().delete();
						}
					}
				}
			}

			break;
		case CMIS_FOLDER:
			session.createFolder(param, parentId);
			break;
		default:
			break;
		}

		return redirectToParent(repositoryId, input);
	}

	@Secure
	public Result update(String repositoryId, String id) {
		NemakiProfile profile = Util.getProfile(ctx());

		// Get an object in the repository
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObject(id);

		// Get input form data
		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		// Build upadte properties
		Map<String, Object> properties = new HashMap<String, Object>();
		for (Entry<String, PropertyDefinition<?>> entry : o.getType().getPropertyDefinitions().entrySet()) {
			PropertyDefinition<?> pdf = entry.getValue();
			if (Updatability.READWRITE == pdf.getUpdatability()) {
				// TODO work around
				if (PropertyIds.SECONDARY_OBJECT_TYPE_IDS.equals(pdf.getId())) {
					continue;
				}

				if (Cardinality.SINGLE == pdf.getCardinality()) {
					String strValue = input.data().get(pdf.getId());
					Object value = strValue;
					// TODO type conversion
					if (pdf.getPropertyType() == PropertyType.DATETIME) {
						if (strValue != null && !strValue.isEmpty()) {
							value = DateTimeUtil.convertStringToCalendar(strValue, ctx().lang().toLocale());
							if (value == null) {
								throw new RuntimeException("Invalid DateTime format.");
							}
						} else {
							value = null;
						}
					} else if (pdf.getPropertyType() == PropertyType.BOOLEAN) {
						value = strValue.isEmpty() ? null : Boolean.valueOf(strValue);
					}

					properties.put(pdf.getId(), value);
				} else {
					// TODO find better way
					List<String> list = new ArrayList<String>();
					if (input.data().containsKey(pdf.getId())) {
						// one item
						list.add(input.data().get(pdf.getId()));
					} else {
						// multiple items
						for (int i = 0; i < input.data().keySet().size(); i++) {
							String keyWithIndex = pdf.getId() + "[" + i + "]";
							String value = input.data().get(keyWithIndex);
							Map<String, String> data = input.data();
							if (value == null) {
								break;
							}
							list.add(value);
						}
					}
					properties.put(pdf.getId(), list);
				}
			}
		}

		// Set change token
		properties.put(PropertyIds.CHANGE_TOKEN, o.getChangeToken());

		// Execute
		o.updateProperties(properties);

		// return redirectToParent(input);
		return ok();
	}

	@Secure
	public Result updatePermission(String repositoryId, String id) {
		// Get an object in the repository
		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(id);

		List<Ace> originalAcl = obj.getAcl().getAces();
		Map<String, Ace> originalAclMap = new HashMap<String, Ace>();
		for (Ace ace : originalAcl) {
			originalAclMap.put(ace.getPrincipalId(), ace);
		}

		List<Ace> addAces = new ArrayList<Ace>();
		List<Ace> removeAces = new ArrayList<Ace>();
		Map<String, Ace> newAclMap = new HashMap<String, Ace>();

		// Get input form data
		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		String acl = input.get("acl");
		ObjectMapper mapper = new ObjectMapper();
		try {
			JsonNode jn = mapper.readTree(acl);
			Iterator<JsonNode> itr = jn.iterator();
			while (itr.hasNext()) {
				JsonNode ace = itr.next();

				Boolean inheritance = ace.get("inheritance").asBoolean();
				if (inheritance)
					continue;

				String principalId = ace.get("principalId").asText();

				List<String> _permission = new ArrayList<String>();
				JsonNode permission = ace.get("permission");
				Iterator<JsonNode> itr2 = permission.iterator();
				while (itr2.hasNext()) {
					_permission.add(itr2.next().asText());
				}

				Ace _newAce = buildAce(principalId, _permission);
				Ace newAce = convertNoneAce(principalId, _newAce);
				newAclMap.put(principalId, newAce);

				// Compare with original and allot to add/removeAces
				Ace originalAce = originalAclMap.get(principalId);
				if (originalAce == null) {
					// Add a new ACE row
					addAces.add(newAce);
				} else {
					// add or remove permissions of an existing ACE row
					List<String> original = (originalAce.isDirect()) ? originalAce.getPermissions()
							: new ArrayList<String>();
					List<String> remove = Util.difference(original, newAce.getPermissions());
					List<String> add = Util.difference(newAce.getPermissions(), original);

					AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl(principalId);
					if (CollectionUtils.isNotEmpty(add)) {
						Ace addAce = new AccessControlEntryImpl(principal, new ArrayList<String>(add));
						addAces.add(addAce);
					}

					if (CollectionUtils.isNotEmpty(remove)) {
						Ace removeAce = new AccessControlEntryImpl(principal, new ArrayList<String>(remove));
						removeAces.add(removeAce);
					}
				}
			}

			// Remove an existing ACE row
			for (String key : originalAclMap.keySet()) {
				if (!newAclMap.containsKey(key)) {
					removeAces.add(originalAclMap.get(key));
				}
			}

			obj.applyAcl(addAces, removeAces, AclPropagation.PROPAGATE);

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return ok();
	}

	private static Ace convertNoneAce(String principalId, Ace ace) {
		if (CollectionUtils.isEmpty(ace.getPermissions())) {
			AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl(principalId);
			List<String> cmisNone = Arrays.asList("cmis:none");
			Ace none = new AccessControlEntryImpl(principal, cmisNone);
			return none;
		} else {
			return ace;
		}
	}

	private static Ace buildAce(String principalId, List<String> permission) {

		AccessControlEntryImpl ace = new AccessControlEntryImpl();
		AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl(principalId);
		ace.setPrincipal(principal);
		ace.setPermissions(permission);

		return ace;
	}

	@Secure
	public Result upload(String repositoryId, String id) {
		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObject(id);

		if (o.getType() instanceof DocumentTypeDefinition) {
			ContentStreamAllowed csa = ((DocumentTypeDefinition) (o.getType())).getContentStreamAllowed();
			if (csa == ContentStreamAllowed.NOTALLOWED) {
				return redirectToParent(repositoryId, input);
			}
		}

		Document doc = (Document) o;
		MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if (files.isEmpty())
			System.err.println("There is no file when uploading");
		FilePart file = files.get(0);

		ContentStream cs = Util.convertFileToContentStream(session, file);
		doc.setContentStream(cs, true);

		return redirectToParent(repositoryId, input);
	}

	@Secure
	public Result delete(String repositoryId, String id) {
		List<String> deletedList = new ArrayList<String>();
		Session session = getCmisSession(repositoryId);
		deletedList.addAll(delete(id, session));
		JsonNode json = Json.toJson(deletedList);
		return ok(json);
	}

	@Secure
	public Result deleteByBatch(String repositoryId, List<String> ids) {
		List<String> deletedList = new ArrayList<String>();
		Session session = getCmisSession(repositoryId);
		ids.forEach(id -> deletedList.addAll(delete(id, session)));
		JsonNode json = Json.toJson(deletedList);
		return ok(json);
	}

	private static List<String> delete(String id, Session session) {
		List<String> deletedList = new ArrayList<String>();
		CmisObject cmisObject = session.getObject(id);

		// Relation cascade delete
		List<Relationship> rels = cmisObject.getRelationships();
		if (rels != null) {
			try {
				rels.stream()
						.filter(p -> id.equals(p.getSourceId().getId())
								&& RelationshipUtil.isCascadeRelation(p.getType()))
						.map(Relationship::getTargetId).distinct().forEach(tId -> {
							try {
								deletedList.addAll(delete(tId.getId(), session));
							} catch (Exception ex) {
								logger.error("Target cmis object id not found", ex);
							}
						});
			} catch (CmisObjectNotFoundException ex) {
				logger.error("Source or target cmis object not found.", ex);
			}
		}
		deletedList.addAll(delete(cmisObject, session));

		return deletedList;
	}

	private static List<String> delete(CmisObject cmisObject, Session session) {
		List<String> deletedList = new ArrayList<String>();
		if (Util.isFolder(cmisObject)) {
			Folder folder = (Folder) cmisObject;
			deletedList.addAll(folder.deleteTree(true, null, true));
		} else {
			String id = cmisObject.getId();
			session.delete(new ObjectIdImpl(id));
			deletedList.add(id);
		}
		return deletedList;
	}

	@Secure
	public Result checkOut(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject cmisObject = session.getObject(id);
		checkOut(cmisObject);
		return ok();
	}

	@Secure
	public Result checkOutByBatch(String repositoryId, List<String> ids) {
		Session session = getCmisSession(repositoryId);
		for (String id : ids) {
			CmisObject cmisObject = session.getObject(id);
			checkOut(cmisObject);
		}
		return ok();
	}

	private static void checkOut(CmisObject cmisObject) {
		if (Util.isDocument(cmisObject)) {
			Document doc = (Document) cmisObject;
			// Check if checkout is possible
			if (doc.isVersionSeriesCheckedOut()) {
				// Do nothing
			} else {
				doc.checkOut();
			}
		} else if (Util.isFolder(cmisObject)) {
			Folder dir = (Folder) cmisObject;
			for (CmisObject childNode : dir.getChildren()) {
				checkOut(childNode);
			}
		} else {
			// no-op
		}

	}

	@Secure
	public Result cancelCheckOut(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject cmisObject = session.getObject(id);

		cancelCheckOut(cmisObject);

		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		return redirectToParent(repositoryId, input);
	}

	@Secure
	public Result cancelCheckOutByBatch(String repositoryId, List<String> ids) {
		Session session = getCmisSession(repositoryId);

		for (String id : ids) {
			CmisObject cmisObject = session.getObject(id);

			cancelCheckOut(cmisObject);
		}

		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		return redirectToParent(repositoryId, input);
	}

	public static void cancelCheckOut(CmisObject cmisObject) {
		if (Util.isDocument(cmisObject)) {
			Document doc = (Document) cmisObject;
			doc.cancelCheckOut();
		} else if (Util.isFolder(cmisObject)) {
			Folder dir = (Folder) cmisObject;
			for (CmisObject childNode : dir.getChildren()) {
				cancelCheckOut(childNode);
			}
		} else {
			// no-op
		}
	}

	@Secure
	public Result checkInPWC(String repositoryId, String id) throws FileNotFoundException {
		Session session = getCmisSession(repositoryId);

		// Comment
		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		String checkinComment = Util.getFormData(input, PropertyIds.CHECKIN_COMMENT);

		CmisObject cmisObject = session.getObject(id);
		checkInPWC(cmisObject, checkinComment);
		return redirectToParent(repositoryId, input);
	}

	private static void checkInPWC(CmisObject cmisObject, String checkinComment) {
		if (Util.isDocument(cmisObject)) {
			Document doc = (Document) cmisObject;
			if (doc.isPrivateWorkingCopy()) {
				Map<String, Object> param = new HashMap<String, Object>();
				doc.checkIn(true, param, doc.getContentStream(), checkinComment);
			}
		} else if (Util.isFolder(cmisObject)) {
			Folder dir = (Folder) cmisObject;
			for (CmisObject childNode : dir.getChildren()) {
				checkInPWC(childNode, checkinComment);
			}
		} else {
			// no-op
		}
	}

	@Secure
	public Result checkInPWCByBatch(String repositoryId, List<String> ids) {
		Session session = getCmisSession(repositoryId);

		for (String id : ids) {
			CmisObject cmisObject = session.getObject(id);
			checkInPWC(cmisObject, "");
		}

		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		return redirectToParent(repositoryId, input);
	}

	@Secure
	public Result checkIn(String repositoryId, String id) throws FileNotFoundException {
		Session session = getCmisSession(repositoryId);

		// Comment
		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		String checkinComment = Util.getFormData(input, PropertyIds.CHECKIN_COMMENT);

		// File
		MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if (files.isEmpty()) {
			throw new FileNotFoundException();
		}
		FilePart file = files.get(0);

		CmisObject cmisObject = session.getObject(id);
		checkIn(cmisObject, checkinComment, file, session);

		return redirectToParent(repositoryId, input);
	}

	private static void checkIn(CmisObject obj, String checkinComment, FilePart file, Session session)
			throws FileNotFoundException {
		Document doc = (Document) obj;
		Map<String, Object> param = new HashMap<String, Object>();
		ContentStream cs = Util.convertFileToContentStream(session, file);
		doc.checkIn(true, param, cs, checkinComment);
	}

	private static Principal getPrincipal(String repositoryId, String principalId, String anyone, String anonymous) {
		// anyone
		if (anyone.equals(principalId)) {
			return new Principal("group", anyone, anyone);
		}

		// anonymous
		if (anonymous.equals(principalId)) {
			return new Principal("user", anonymous, anonymous);
		}

		String coreRestUri = Util.buildNemakiCoreRestRepositoryUri(repositoryId);

		// user
		JsonNode resultUser = Util.getJsonResponse(ctx(), coreRestUri + "user/show/" + principalId);
		// TODO check status
		JsonNode user = resultUser.get("user");
		if (user != null) {
			Principal p = new Principal("user", user.get("userId").asText(), user.get("userName").asText());
			return p;
		}

		// group
		JsonNode resultGroup = Util.getJsonResponse(ctx(), coreRestUri + "group/show/" + principalId);
		// TODO check status
		JsonNode group = resultGroup.get("group");
		if (group != null) {
			Principal p = new Principal("group", group.get("groupId").asText(), group.get("groupName").asText());
			return p;
		}

		return null;
	}

	@Secure
	public Result createRelationToNew(String repositoryId, String sourceId) {
		// Get input form data
		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		String parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
		String relType = Util.getFormData(input, "nemaki:relationshipType");
		String relName = Util.getFormData(input, "nemaki:relationshipName");
		String docType = Util.getFormData(input, "nemaki:documentType");

		// Get an object in the repository
		Session session = getCmisSession(repositoryId);
		Folder folder = (Folder) session.getObject(parentId);

		MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if (files.isEmpty())
			System.err.println("There is no file when uploading");
		FilePart file = files.get(0);
		ContentStream cs = Util.convertFileToContentStream(session, file);

		Map<String, String> newDocProps = new HashMap<String, String>();
		newDocProps.put(PropertyIds.OBJECT_TYPE_ID, StringUtils.isBlank(docType) ? "cmis:document" : docType);
		newDocProps.put(PropertyIds.NAME, file.getFilename());
		Document doc = folder.createDocument(newDocProps, cs, VersioningState.MAJOR, null, null, null,
				session.getDefaultContext());

		ObjectId newId = createRelation(relType, relName, repositoryId, sourceId, doc.getId());

		return ok(newId.toString());
	}

	@Secure
	public Result createRelationToExisting(String repositoryId, String sourceId) {
		// Get input form data
		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		String targetId = Util.getFormData(input, "nemaki:targetId");
		String relType = Util.getFormData(input, "nemaki:relationshipType");
		String relName = Util.getFormData(input, "nemaki:relationshipName");
		if (StringUtils.isEmpty(targetId)) {
			return internalServerError("ObjectId is empty.");
		}

		// Get an object in the repository
		Session session = getCmisSession(repositoryId);

		try {
			session.getObject(targetId);
			createRelation(relType, relName, repositoryId, sourceId, targetId);
			return ok();
		} catch (CmisObjectNotFoundException e) {
			e.printStackTrace();
			return internalServerError("CmisObject is not found.");
		}
	}

	private static ObjectId createRelation(String relType, String name, String repositoryId, String sourceId,
			String targetId) {
		// Get an object in the repository
		Session session = getCmisSession(repositoryId);

		// Source acl copy to relation acl
		CmisObject srcObj = session.getObject(sourceId);
		CmisObject targetObj = session.getObject(targetId);
		Acl srcAcl = srcObj.getAcl();
		List<Ace> srcAceList = srcAcl.getAces();
		String relName = StringUtils.isEmpty(name) ? FilenameUtils.removeExtension(targetObj.getName()) : name;

		Map<String, String> relProps = new HashMap<String, String>();
		relProps.put(PropertyIds.OBJECT_TYPE_ID, relType);
		relProps.put(PropertyIds.NAME, relName);
		relProps.put("cmis:sourceId", sourceId);
		relProps.put("cmis:targetId", targetId);
		return session.createRelationship(relProps, null, srcAceList, srcAceList);
	}

	private static Result redirectToParent(String repositoryId, DynamicForm input) {
		String parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
		// TODO fix hard code
		if (parentId == null || "".equals(parentId) || "/".equals(parentId)) {
			return redirect(routes.Node.index(repositoryId,0,"cmis:name DESC"));
		} else {
			return redirect(routes.Node.showChildren(repositoryId, parentId,0,"cmis:name DESC",null));
		}
	}

	@Secure
	public Result getAce(String repositoryId, String objectId, String principalId) {
		Session session = getCmisSession(repositoryId);
		ObjectId id = session.createObjectId(objectId);
		Acl acl = session.getAcl(id, false);

		Map<String, Ace> map = Util.zipWithId(acl);
		Ace ace = map.get(principalId);

		if (ace == null) {
			return ok(Util.emptyJsonObject());
		} else {
			JsonNode json = Json.toJson(ace);
			return ok(json);
		}
	}

	private static class TemporaryFileInputStream extends FileInputStream {
		private File file;

		public TemporaryFileInputStream(File file) throws FileNotFoundException {
			super(file);
			this.file = file;
		}

		@Override
		public void close() throws IOException {
			super.close();
			this.file.delete();
		}
	}

}