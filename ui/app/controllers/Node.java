package controllers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import model.ActionPluginUIElement;
import model.Principal;
import net.lingala.zip4j.core.*;
import net.lingala.zip4j.io.*;
import net.lingala.zip4j.model.*;
import net.lingala.zip4j.util.*;

import org.apache.chemistry.opencmis.client.api.*;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.MimeTypes;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.api.libs.Files.TemporaryFile;
import play.i18n.Messages;
import play.data.DynamicForm;
import play.data.Form;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.CmisObjectTree;
import util.NemakiConfig;
import util.RelationshipUtil;
import util.Util;
import views.html.node.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import constant.PropertyKey;
import constant.Token;
import jp.aegif.nemaki.common.NemakiObjectType;

@Authenticated(Secured.class)
public class Node extends Controller {
	private static Logger log = LoggerFactory.getLogger(Node.class);

	private static Session getCmisSession(String repositoryId) {
		return CmisSessions.getCmisSession(repositoryId, session());
	}

	public static Result index(String repositoryId) {
		try {
			Session session = getCmisSession(repositoryId);
			Folder root = session.getRootFolder();

			return showChildren(repositoryId, root.getId());
		} catch (Exception ex) {
			CmisSessions.disconnect(repositoryId, session());
			return redirect(routes.Application.login(repositoryId));
		}
	}

	public static Result showChildren(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject parent = session.getObject(id);
		// TODO type check
		Folder _parent = (Folder) parent;
		ItemIterable<CmisObject> children = _parent.getChildren();

		List<CmisObject> results = new ArrayList<CmisObject>();
		Iterator<CmisObject> itr = children.iterator();
		while (itr.hasNext()) {
			CmisObject obj = itr.next();

			// Check and replace to PWC for owner
			if (Util.isDocument(obj)) {
				Document doc = (Document) obj;
				if (doc.isVersionSeriesCheckedOut()) {
					// check owner
					String loginUser = session().get(Token.LOGIN_USER_ID);
					String owner = doc.getVersionSeriesCheckedOutBy();
					if (loginUser.equals(owner)) {
						String pwcId = doc.getVersionSeriesCheckedOutId();
						CmisObject pwc = session.getObject(pwcId);
						results.add(pwc);
						continue;
					}
				}
			}

			results.add(obj);
		}

		// Fill in CMIS types
		List<Tree<ObjectType>> typeFolders = session.getTypeDescendants(BaseTypeId.CMIS_FOLDER.value(), -1, false);
		List<Tree<ObjectType>> typeDocs = session.getTypeDescendants(BaseTypeId.CMIS_DOCUMENT.value(), -1, false);
		List<Tree<ObjectType>> types = new ArrayList<Tree<ObjectType>>();
		types.addAll(typeFolders);
		types.addAll(typeDocs);

		return ok(tree.render(repositoryId, _parent, results, types));
	}

	public static Result showChildrenByPath(String repositoryId, String path) {
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObjectByPath(path);

		return showChildren(repositoryId, o.getId());
	}

	public static Result search(String repositoryId, String term) {
		Session session = getCmisSession(repositoryId);

		OperationContext ctxt = session.getDefaultContext();

		List<CmisObject> list = new ArrayList<CmisObject>();
		// Build WHERE clause(cmis:document)
		MessageFormat docFormat = new MessageFormat(
				"cmis:name LIKE ''%{0}%'' OR cmis:description LIKE ''%{0}%'' OR CONTAINS(''{0}'')");
		String docStatement = "";
		if (StringUtils.isNotBlank(term)) {
			docStatement = docFormat.format(new String[] { term });
		}
		ItemIterable<CmisObject> docResults = session.queryObjects("cmis:document", docStatement, false, ctxt);
		Iterator<CmisObject> docItr = docResults.iterator();
		while (docItr.hasNext()) {
			CmisObject doc = docItr.next();
			boolean val = doc.getPropertyValue("cmis:isLatestVersion");
			if (!val)
				continue;
			list.add(doc);
		}

		return ok(search.render(repositoryId, term, list));
	}

	public static Result showBlank(String repositoryId) {
		String parentId = request().getQueryString("parentId");
		String objectTypeId = request().getQueryString("objectType");

		if (StringUtils.isEmpty(objectTypeId)) {
			objectTypeId = "cmis:folder"; // default
		}

		Session session = getCmisSession(repositoryId);
		ObjectType objectType = session.getTypeDefinition(objectTypeId);

		return ok(blank.render(repositoryId, parentId, objectType));
	}

	public static Result showDetail(String repositoryId, String id, String activateTabName) {
		Session session = getCmisSession(repositoryId);

		FileableCmisObject o = (FileableCmisObject) session.getObject(id);

		// Get parentId
		String parentId = o.getParents().get(0).getId();

		// Get user
		final String endPoint = Util.buildNemakiCoreRestRepositoryUri(repositoryId);
		String url = endPoint + "user/show/" + session().get(Token.LOGIN_USER_ID);
		JsonNode result = Util.getJsonResponse(session(), url);
		model.User user = new model.User();
		if ("success".equals(result.get("status").asText())) {
			JsonNode _user = result.get("user");
			user = new model.User(_user);
		} else {
			internalServerError("User retrieveing failure");
		}

		return ok(detail.render(repositoryId, o, parentId, activateTabName, user));
	}

	public static Result showProperty(String repositoryId, String id) {
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

		}

		return ok(property.render(repositoryId, o, primaries, secondaries));
	}

	public static Result showFile(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		FileableCmisObject o = (FileableCmisObject) session.getObject(id);

		// Get parentId
		String parentId = o.getParents().get(0).getId();

		return ok(file.render(repositoryId, o, parentId));

	}

	public static Result downloadAsCompressedFile(String repositoryId, String id) {
		return downloadAsCompressedFileByBatch(repositoryId, Arrays.asList(id));
	}

	public static Result downloadAsCompressedFileByBatch(String repositoryId, List<String> ids) {
		Session session = getCmisSession(repositoryId);
		File tempFile = null;

		try {
			CmisObjectTree tree = new CmisObjectTree(session);
			tree.buildTree(ids.toArray(new String[0]));

			// ファイルが大きすぎたらエラーにする
			long maxsize = Util.getCompressionTargetMaxSize();
			if (tree.getContentsSize() > Util.getCompressionTargetMaxSize()) {
				String errmsg = Messages.get("view.message.compress.error.toolarge", maxsize);
				return internalServerError(errmsg);
			}

			// ファイルにアーカイブ
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
			log.error("Zip packing error", e);
		}

		createAttachmentResponse("compressed-files.zip", "application/zip");
		return ok(tempFile);
	}

	public static Result download(String repositoryId, String id) {
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

	public static Result downloadPreview(String repositoryId, String id) {
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

	public static Result showVersion(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject o = session.getObject(id);

		List<Document> result = new ArrayList<Document>();

		if (Util.isDocument(o)) {
			Document doc = (Document) o;
			result = doc.getAllVersions();
		}

		return ok(version.render(repositoryId, result));

	}

	public static Result showRelationshipCreate(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(id);

		String parentId = null;
		if (Util.isDocument(obj)) {
			Document doc = (Document) obj;
			parentId = doc.getParents().get(0).getId();
		} else if (Util.isDocument(obj)) {
			Folder folder = (Folder) obj;
			parentId = folder.getFolderParent().getId();
		}

		List<ObjectType> relationshipTypes = session
				.getTypeDescendants(null, -1, true)
				.stream()
				.map(Tree::getItem)
				.filter(p -> p.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP)
				.collect(Collectors.toList());
				;

		return ok(relationship_create.render(repositoryId, obj, parentId, relationshipTypes));
	}

	public static Result showRelationship(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);

		List<Relationship> relationships = obj.getRelationships();
		List<Relationship> result = new ArrayList<Relationship>();
		if (relationships != null) {
			result = relationships.stream().filter(r -> {
				try {
					CmisObject t = r.getTarget();
				} catch (CmisObjectNotFoundException e) {
					return false;
				}
				try {
					CmisObject s = r.getSource();
				} catch (CmisObjectNotFoundException e) {
					return false;
				}
				return true;
			}).collect(Collectors.toList());
		}

		return ok(relationship.render(repositoryId, obj, result));
	}

	public static Result showPreview(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(id);

		return ok(preview.render(repositoryId, obj));
	}

	public static Result showPermission(String repositoryId, String id) {
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

	public static Result showAction(String repositoryId, String id, String actionId) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);

		ActionPluginUIElement elm = Util.getActionPluginUIElement(obj, actionId);

		return ok(views.html.node.action.render(repositoryId, obj, elm));
	}

	public static Result doAction(String repositoryId, String id, String actionId) {
		JsonNode json = request().body().asJson();

		String restUri = Util.buildNemakiCoreUri() + "rest/repo/" + repositoryId + "/action/" + actionId + "/do/" + id;
		JsonNode result = Util.postJsonResponse(session(), restUri, json);

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
	public static Result dragAndDrop(String repositoryId, String action) {
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
		Document d0 = (Document) session.getObject(objectId);
		Document d1 = d0.setContentStream(cs, true);

		// Clean temp file just after CMIS createDocument finished
		file.getFile().delete();
	}

	public static Result create(String repositoryId) {
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
		HashMap<String, Object> param = Util.buildProperties(pdfs, input, upds);
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

	public static Result update(String repositoryId, String id) {
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
						value = Util.convertStringToCalendar(strValue);
						if (value == null) {
							throw new RuntimeException("Invalid DateTime format.");
						}
					}
					
					properties.put(pdf.getId(), value);
				} else {
					// TODO find better way
					List<String> list = new ArrayList<String>();
					for (int i = 0; i < input.data().keySet().size(); i++) {
						String keyWithIndex = pdf.getId() + "[" + i + "]";
						String value = input.data().get(keyWithIndex);
						if (value == null) {
							break;
						}
						list.add(value);
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

	public static Result updatePermission(String repositoryId, String id) {
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

	public static Result upload(String repositoryId, String id) {
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


	public static Result delete(String repositoryId, String id) {
		List<String> deletedList = new ArrayList<String>();
		Session session = getCmisSession(repositoryId);
		deletedList.addAll(delete(id, session));
		JsonNode json = Json.toJson(deletedList);
		return ok(json);
	}

	public static Result deleteByBatch(String repositoryId, List<String> ids) {
		List<String> deletedList = new ArrayList<String>();
		Session session = getCmisSession(repositoryId);
		ids.forEach(id -> deletedList.addAll(delete(id, session)));
		JsonNode json = Json.toJson(deletedList);
		return ok(json);
	}

	private static List<String> delete(String id, Session session){
		List<String> deletedList = new ArrayList<String>();
		CmisObject cmisObject = session.getObject(id);

		//Relation cascade delete
		List<Relationship> rels = cmisObject.getRelationships();
		if (rels != null){
			try{
				rels.stream()
					.filter(p -> id.equals(p.getSourceId().getId()) && RelationshipUtil.isCascadeRelation(p.getType()))
					.map(Relationship::getTargetId)
					.distinct()
					.forEach(tId -> {
						try{
							deletedList.addAll(delete(tId.getId(), session));
						}catch(Exception ex){
							log.error("Target cmis object id not found", ex);
						}
					});
			}catch(CmisObjectNotFoundException ex){
				log.error("Source or target cmis object not found.", ex);
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

	public static Result checkOut(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject cmisObject = session.getObject(id);
		checkOut(cmisObject);
		return ok();
	}

	public static Result checkOutByBatch(String repositoryId, List<String> ids) {
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

	public static Result cancelCheckOut(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject cmisObject = session.getObject(id);

		cancelCheckOut(cmisObject);

		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		return redirectToParent(repositoryId, input);
	}

	public static Result cancelCheckOutByBatch(String repositoryId, List<String> ids) {
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

	public static Result checkIn(String repositoryId, String id) throws FileNotFoundException {
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
		JsonNode resultUser = Util.getJsonResponse(session(), coreRestUri + "user/show/" + principalId);
		// TODO check status
		JsonNode user = resultUser.get("user");
		if (user != null) {
			Principal p = new Principal("user", user.get("userId").asText(), user.get("userName").asText());
			return p;
		}

		// group
		JsonNode resultGroup = Util.getJsonResponse(session(), coreRestUri + "group/show/" + principalId);
		// TODO check status
		JsonNode group = resultGroup.get("group");
		if (group != null) {
			Principal p = new Principal("group", group.get("groupId").asText(), group.get("groupName").asText());
			return p;
		}

		return null;
	}

	public static Result createRelationToNew(String repositoryId, String sourceId) {
		// Get input form data
		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		String parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
		String relType = Util.getFormData(input, "nemaki:relationshipType");
		String relName = Util.getFormData(input, "nemaki:relationshipName");

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
		newDocProps.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");
		newDocProps.put(PropertyIds.NAME, file.getFilename());
		Document doc = folder.createDocument(newDocProps, cs, VersioningState.MAJOR, null, null, null,
				session.getDefaultContext());

		createRelation(relType, relName, repositoryId, sourceId, doc.getId());

		return ok();
	}

	public static Result createRelationToExisting(String repositoryId, String sourceId) {
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

	private static ObjectId createRelation(String relType, String relName, String repositoryId, String sourceId, String targetId) {
		// Get an object in the repository
		Session session = getCmisSession(repositoryId);

		// Source acl copy to relation acl
		CmisObject srcObj = session.getObject(sourceId);
		Acl srcAcl = srcObj.getAcl();
		List<Ace> srcAceList = srcAcl.getAces();

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
			return redirect(routes.Node.index(repositoryId));
		} else {
			return redirect(routes.Node.showChildren(repositoryId, parentId));
		}
	}

	public static Result getAce(String repositoryId, String objectId, String principalId) {
		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(objectId);

		Map<String, Ace> map = Util.zipWithId(obj.getAcl());
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