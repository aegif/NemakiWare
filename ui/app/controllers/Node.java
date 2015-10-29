package controllers;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import model.Principal;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.Rendition;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import play.data.DynamicForm;
import play.data.Form;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;
import views.html.node.blank;
import views.html.node.detail;
import views.html.node.file;
import views.html.node.preview;
import views.html.node.property;
import views.html.node.search;
import views.html.node.tree;
import views.html.node.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import constant.Token;

@Authenticated(Secured.class)
public class Node extends Controller {

	private static Session getCmisSession(String repositoryId) {
		return CmisSessions.getCmisSession(repositoryId, session());
	}

	public static Result index(String repositoryId) {
		Session session = getCmisSession(repositoryId);
		Folder root = session.getRootFolder();
		return showChildren(repositoryId, root.getId());
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
		MessageFormat docFormat = new MessageFormat("cmis:name LIKE ''%{0}%'' OR cmis:description LIKE ''%{0}%'' OR CONTAINS(''{0}'')");
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

		// Build WHERE clause(cmis:folder)
		if (false) { //フォルダはデフォルトでは検索しない
			MessageFormat folderFormat = new MessageFormat("cmis:name LIKE ''%{0}%'' OR cmis:description LIKE ''%{0}%''");
			String folderStatement = "";
			if (StringUtils.isNotBlank(term)) {
				folderStatement = folderFormat.format(new String[] { term });
			}
			ItemIterable<CmisObject> folderResults = session.queryObjects("cmis:folder", folderStatement, false, ctxt);
			Iterator<CmisObject> folderItr = folderResults.iterator();
			while (folderItr.hasNext()) {
				list.add(folderItr.next());
			}
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

	public static Result showDetail(String repositoryId, String id, Boolean activatePreviewTab) {
		Session session = getCmisSession(repositoryId);

		FileableCmisObject o = (FileableCmisObject) session.getObject(id);

		// Get parentId
		String parentId = o.getParents().get(0).getId();

		// Get user
		final String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
		final String endPoint = coreRestUri + "repo/" + repositoryId + "/user/";
		JsonNode result = Util.getJsonResponse(session(), endPoint + "show/" + session().get(Token.LOGIN_USER_ID));
		model.User user = new model.User();
		if ("success".equals(result.get("status").asText())) {
			JsonNode _user = result.get("user");
			user = new model.User(_user);
		} else {
			internalServerError("User retrieveing failure");
		}

		return ok(detail.render(repositoryId, o, parentId, activatePreviewTab, user));
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

	public static Result download(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject obj = session.getObject(id);
		if (!Util.isDocument(obj)) {
			// TODO logging
			return badRequest();
		}

		Document doc = (Document) obj;
		ContentStream cs = doc.getContentStream();

		File tmpFile = null;
		try {
			tmpFile = Util.convertInputStreamToFile(cs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			if (request().getHeader("User-Agent").indexOf("MSIE") == -1) {
				// Firefox, Opera 11
				response().setHeader("Content-Disposition", String
						.format(Locale.JAPAN, "attachment; filename*=utf-8'jp'%s", URLEncoder
								.encode(doc.getName(), "utf-8")));
			} else {
				// IE7, 8, 9
				response().setHeader("Content-Disposition", String
						.format(Locale.JAPAN, "attachment; filename=\"%s\"", new String(doc.getName()
								.getBytes("MS932"), "ISO8859_1")));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		response().setContentType(cs.getMimeType());
		return ok(tmpFile);
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
		return ok(tmpFile, true);
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

		return ok(views.html.node.permission.render(repositoryId, obj, members, permissionDefs));
	}

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
				System.out.println("Specify drag & drop action type");
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
		// d1.updateProperties(param);
	}

	public static Result create(String repositoryId) {
		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		// Extract special property
		String objectTypeId = Util.getFormData(input, PropertyIds.OBJECT_TYPE_ID);
		String _parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
		ObjectId parentId = new ObjectIdImpl(_parentId);

		// Check dragAndDrop
		boolean dragAndDrop = (Util.getFormData(input, "dragAndDrop") == null) ? false
				: Boolean.valueOf(Util.getFormData(input, "dragAndDrop"));

		Session session = getCmisSession(repositoryId);

		ObjectType objectType = session.getTypeDefinition(objectTypeId);

		// Set CMIS parameter
		Map<String, PropertyDefinition<?>> pdfs = session.getTypeDefinition(objectTypeId).getPropertyDefinitions();
		List<Updatability> upds = new ArrayList<Updatability>();
		upds.add(Updatability.ONCREATE);
		upds.add(Updatability.READWRITE);
		HashMap<String, Object> param = Util.buildProperties(pdfs, input, upds);
		param.put(PropertyIds.OBJECT_TYPE_ID, objectTypeId);

		// Document/Folder specific
		switch (Util.getBaseType(session, objectTypeId)) {
		case CMIS_DOCUMENT:
			ContentStreamAllowed csa = ((DocumentTypeDefinition) objectType).getContentStreamAllowed();
			if (csa == ContentStreamAllowed.REQUIRED) {
				List<FilePart> files = null;
				MultipartFormData body = request().body().asMultipartFormData();
				if (body != null && CollectionUtils.isNotEmpty(body.getFiles())) {
					files = body.getFiles();
				}

				if (CollectionUtils.isEmpty(files)) {
					// TODO error
					System.err.println(objectTypeId + ":This type requires a file");
				} else {
					ContentStream cs = Util.convertFileToContentStream(session, files.get(0));
					if (param.get(PropertyIds.NAME) == null) {
						param.put(PropertyIds.NAME, cs.getFileName());
					}
					session.createDocument(param, parentId, cs, VersioningState.MAJOR);
				}
			} else if (csa == ContentStreamAllowed.ALLOWED) {
				// Retrieve a file
				List<FilePart> files = null;
				MultipartFormData body = request().body().asMultipartFormData();
				if (body != null && CollectionUtils.isNotEmpty(body.getFiles())) {
					files = body.getFiles();
				}

				// Set content stream if there is a file
				if (CollectionUtils.isNotEmpty(files)) {
					ContentStream cs = Util.convertFileToContentStream(session, files.get(0));
					if (param.get(PropertyIds.NAME) == null) {
						param.put(PropertyIds.NAME, cs.getFileName());
					}
					session.createDocument(param, parentId, cs, VersioningState.MAJOR);
				}
			} else if (csa == ContentStreamAllowed.NOTALLOWED) {
				// don't set content stream
				session.createDocument(param, parentId, null, VersioningState.MAJOR);
			}

			break;
		case CMIS_FOLDER:
			session.createFolder(param, parentId);
			break;
		default:
			break;

		}

		if (dragAndDrop) {
			return ok();
		} else {
			return redirectToParent(repositoryId, input);
		}
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
					String value = input.data().get(pdf.getId());
					// TODO type conversion
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

		MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if (files.isEmpty()) {
			// TODO error
			System.err.println("There is no file when uploading");
		}
		FilePart file = files.get(0);

		Document doc = (Document) o;
		ContentStream cs = Util.convertFileToContentStream(session, file);
		doc.setContentStream(cs, true);

		return redirectToParent(repositoryId, input);
	}

	public static Result delete(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);

		CmisObject object = session.getObject(id);
		if (BaseTypeId.CMIS_FOLDER == object.getBaseTypeId()) {
			Folder folder = (Folder) object;
			folder.deleteTree(true, null, true);
		} else {
			session.delete(new ObjectIdImpl(id));
		}

		return ok();
	}

	public static Result checkOut(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObject(id);

		DynamicForm input = Form.form();
		input = input.bindFromRequest();

		if (!Util.isDocument(o)) {
			// TODO error
		}

		Document doc = (Document) o;

		// Check if checkout is possible
		if (doc.isVersionSeriesCheckedOut()) {
			// TODO error
			System.out.println("already checked out");
		} else {
			// Check out
			doc.checkOut();

		}

		return ok();
	}

	public static Result cancelCheckOut(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject o = session.getObject(id);

		if (!Util.isDocument(o)) {
			// TODO error
		}

		Document doc = (Document) o;
		doc.cancelCheckOut();

		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		return redirectToParent(repositoryId, input);
	}

	public static Result checkIn(String repositoryId, String id) {
		Session session = getCmisSession(repositoryId);
		CmisObject obj = session.getObject(id);

		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		MultipartFormData body = request().body().asMultipartFormData();

		// Files
		List<FilePart> files = body.getFiles();
		if (files.isEmpty()) {
			// TODO error
		}
		FilePart file = files.get(0);
		Document doc = (Document) obj;
		ContentStream cs = Util.convertFileToContentStream(session, file);

		// Comment
		String checkinComment = Util.getFormData(input, PropertyIds.CHECKIN_COMMENT);

		// Execute
		Map<String, Object> param = new HashMap<String, Object>();
		doc.checkIn(true, param, cs, checkinComment);

		return redirectToParent(repositoryId, input);
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

		String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

		// user
		JsonNode resultUser = Util
				.getJsonResponse(session(), coreRestUri + "repo/" + repositoryId + "/user/show/" + principalId);
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

	private static Result redirectToParent(String repositoryId, DynamicForm input) {
		String parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
		// TODO fix hard code
		if ("".equals(parentId) || "/".equals(parentId)) {
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

}