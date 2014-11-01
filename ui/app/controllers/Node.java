package controllers;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import play.data.DynamicForm;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Http.RequestBody;
import play.mvc.Security.Authenticated;
import play.mvc.Result;
import util.Util;
import views.html.node.tree;
import views.html.node.version;
import views.html.node.blank;
import views.html.node.detail;
import views.html.node.property;
import views.html.node.file;
import views.html.node.search;


@Authenticated(Secured.class)
public class Node extends Controller {

	private static Session createSession(){
		String id = session().get("loginUserId");
		String password = session().get("loginUserPassword");
		Session cmisSession = Util.createCmisSession(id, password);
		return cmisSession;
	}

	
    public static Result index() {
    	Session session = createSession();
		Folder root = session.getRootFolder();
        return showChildren(root.getId());
    }

    public static Result showChildren(String id){
    	Session session = createSession();

    	CmisObject parent = session.getObject(id);
    	//TODO type check
    	Folder _parent = (Folder)parent;

    	ItemIterable<CmisObject> children = _parent.getChildren();

    	List<CmisObject> results = new ArrayList<CmisObject>();
		Iterator<CmisObject> itr = children.iterator();
		while (itr.hasNext()) {
			CmisObject o = itr.next();
			results.add(o);
		}

        return ok(tree.render(_parent, results));
    }

    public static Result showChildrenByPath(String path){
    	Session session = createSession();
    	CmisObject o = session.getObjectByPath(path);
    	
    	return showChildren(o.getId());
    }

    public static Result showDetail(String id) {
    	Session session = createSession();

    	FileableCmisObject o = (FileableCmisObject)session.getObject(id);

    	//Get parentId
    	String parentId = o.getParents().get(0).getId();

    	return ok(detail.render(o, parentId));
    }
    
    public static Result showVersion(String id){
    	Session session = createSession();

    	CmisObject o = session.getObject(id);

    	List<Document> result = new ArrayList<Document>();

    	if(Util.isDocument(o)){
    		Document doc = (Document) o;
    		result = doc.getAllVersions();
    	}

    	return ok(version.render(result));

    }

    public static Result showBlank(){
     	String objectType = request().getQueryString("objectType");
    	String baseType = request().getQueryString("baseType");
    	String parentId = request().getQueryString("parentId");


    	if(StringUtils.isEmpty(objectType)){
    		objectType = "cmis:folder"; //default
    	}else if(baseType.equals("cmis:folder")){

    	}else if(baseType.equals("cmis:document")){

    	}
    	
    	Session session = createSession();
    	//ProeprtyDefinitions
    	Map<String, PropertyDefinition<?>> _propertyDefinitions = session.getTypeDefinition(objectType).getPropertyDefinitions();
    	List<PropertyDefinition<?>> propertyDefinitions = new ArrayList<PropertyDefinition<?>>(_propertyDefinitions.values());   			
    	
    	//ObjectTypes(of the baseType)
    	List<Tree<ObjectType>> typeDescendants = session.getTypeDescendants(baseType, -1, true);
    	
    	return ok(blank.render(baseType, objectType, parentId, propertyDefinitions, typeDescendants));
    }


    public static Result create(){
    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();

    	//Extract special property
    	String objectTypeId = Util.getFormData(input, PropertyIds.OBJECT_TYPE_ID);
    	String _parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
    	ObjectId parentId = new ObjectIdImpl(_parentId);
    	
    	//Check dragAndDrop
    	boolean dragAndDrop = (Util.getFormData(input, "dragAndDrop") == null) ? false : Boolean.valueOf(Util.getFormData(input, "dragAndDrop")); 
    	
    	Session session = createSession();
    	if(dragAndDrop){
    		//multiple file upload
    		MultipartFormData body = request().body().asMultipartFormData();
    		List<FilePart> files = body.getFiles();
			for(FilePart file : files){
				ContentStream cs = Util.convertFileToContentStream(session, file);
				
				//Set minimum required property
				HashMap<String, Object>param = new HashMap<String, Object>();
				param.put(PropertyIds.OBJECT_TYPE_ID, objectTypeId);
				param.put(PropertyIds.NAME, cs.getFileName());
	        	
				session.createDocument(param, parentId, cs, VersioningState.MAJOR);
			}
    	}else{
        	//Set CMIS parameter
        	Map<String, PropertyDefinition<?>> pdfs = session.getTypeDefinition(objectTypeId).getPropertyDefinitions();
        	List<Updatability> upds = new ArrayList<Updatability>();
        	upds.add(Updatability.ONCREATE);
        	upds.add(Updatability.READWRITE);
        	HashMap<String, Object> param = Util.buildProperties(pdfs, input, upds);
        	param.put(PropertyIds.OBJECT_TYPE_ID, objectTypeId);
        	
        	//Document/Folder specific 
        	switch (Util.getBaseType(session, objectTypeId)){
    		case CMIS_DOCUMENT:
    			MultipartFormData body = request().body().asMultipartFormData();
        		List<FilePart> files = body.getFiles();
        		
        		if(CollectionUtils.isEmpty(files)){
        			//TODO error
        		}else if(files.size() == 1){
        			ContentStream cs = Util.convertFileToContentStream(session, files.get(0));
        			if(param.get(PropertyIds.NAME) == null){
        				param.put(PropertyIds.NAME, cs.getFileName());
        			}
        			session.createDocument(param, parentId, cs, VersioningState.MAJOR);
        		}else{
        			// TODO
        		}
        		
    			break;
    		case CMIS_FOLDER:
        		session.createFolder(param, parentId);
    			break;
    		default:
    			break;
        		
        	}
    	}
    	
    	return redirectToParent(input);
    }

   
    
    
    private static Result redirectToParent(DynamicForm input){
    	String parentId = Util.getFormData(input, PropertyIds.PARENT_ID);
    	//TODO fix hard code
    	if ("".equals(parentId) || "/".equals(parentId)){
    		return redirect(routes.Node.index());
    	}else{
    		return redirect(routes.Node.showChildren(parentId));
    	}
    }



    public static Result showProperty(String id){
    	Session session = createSession();

    	FileableCmisObject o = (FileableCmisObject)session.getObject(id);


    	List<Property<?>> properties = o.getProperties();
    	List<SecondaryType>secondaryTypes = o.getSecondaryTypes();
    	
    	//divide
    	List<Property<?>>primaries = new ArrayList<Property<?>>();
    	Map<SecondaryType, List<Property<?>>>secondaries = new HashMap<SecondaryType, List<Property<?>>>();
    	
    	if(CollectionUtils.isNotEmpty(secondaryTypes)){
    		Iterator<SecondaryType> itr = secondaryTypes.iterator();
    		while(itr.hasNext()){
    			SecondaryType st = itr.next();
    			secondaries.put(st, new ArrayList<Property<?>>());
    		}
    	}
    	
    	
    	for(Property<?> p : properties){
    		boolean isSecondary = false;

    		if(CollectionUtils.isNotEmpty(secondaryTypes)){
    			Iterator<SecondaryType> itr2 = secondaryTypes.iterator();
        		while(itr2.hasNext()){
        			SecondaryType st = itr2.next();
        			if(st.getPropertyDefinitions().containsKey(p.getId())){
        				secondaries.get(st).add(p);
        				isSecondary = true;
        			}
        		}
    		}
    		
    		if(!isSecondary){
    			primaries.add(p);
    		}
    		
    	}
    	
    	return ok(property.render(o, primaries, secondaries));
    }

    public static Result update(String id){
    	//Get an object in the repository
    	Session session = createSession();
    	CmisObject o = session.getObject(id);

    	//Get input form data
    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();

    	//Build upadte properties
    	//TODO multi-valued properties
    	Map<String, Object>properties = new HashMap<String, Object>();
    	for(Entry<String, PropertyDefinition<?>> entry : o.getType().getPropertyDefinitions().entrySet()){
    		PropertyDefinition<?>pdf =  entry.getValue();
    		if(Updatability.READWRITE == pdf.getUpdatability()){
    			//TODO work around
    			if(PropertyIds.SECONDARY_OBJECT_TYPE_IDS.equals(pdf.getId())){
    				continue;
    			}

    			String value = input.data().get(pdf.getId());

    			//TODO type conversion
    			properties.put(pdf.getId(), value);
    		}
    	}

    	//Set change token
    	properties.put(PropertyIds.CHANGE_TOKEN, o.getChangeToken());
    	
    	//Execute
    	o.updateProperties(properties);

    	//return redirectToParent(input);
    	return ok();
    }
    
    public static Result updatePermission(String id){
    	//Get an object in the repository
    	Session session = createSession();
    	CmisObject obj = session.getObject(id);
    	
    	List<Ace> oldAcl = obj.getAcl().getAces();
    	List<Ace> newAcl = new ArrayList<Ace>();
    	
    	
    	//Get input form data
    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();

    	
    	
    	String acl = input.get("acl");
    	ObjectMapper mapper = new ObjectMapper();
    	try {
			JsonNode jn = mapper.readTree(acl);
			Iterator<JsonNode> itr = jn.iterator();
			while(itr.hasNext()){
				JsonNode ace = itr.next();
				
				Boolean inheritance = ace.get("inheritance").asBoolean();
				if(inheritance) continue;
				
				String principalId = ace.get("principalId").asText();
				
				List<String> _permission = new ArrayList<String>();
				JsonNode permission = ace.get("permission");
				Iterator<JsonNode> itr2 = permission.iterator();
				while(itr2.hasNext()){
					_permission.add(itr2.next().asText());
				}
				
				newAcl.add(buildAce(principalId, _permission));
			}
			
			obj.applyAcl(newAcl, oldAcl, AclPropagation.PROPAGATE);
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    	
    	//parse json data
    	
    	
    	
    	return ok();
    	
    }
    
    private static Ace buildAce(String principalId, List<String>permission){
    	
    	AccessControlEntryImpl ace = new AccessControlEntryImpl();
    	AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl(principalId);
    	ace.setPrincipal(principal);
    	ace.setPermissions(permission);
    	
    	return ace;
    }

    public static Result showFile(String id){
    	Session session = createSession();

    	FileableCmisObject o = (FileableCmisObject)session.getObject(id);

    	//Get parentId
    	String parentId = o.getParents().get(0).getId();



    	return ok(file.render(o, parentId));

    }


    public static Result upload(String id){

    	Session session = createSession();

    	CmisObject o = session.getObject(id);

    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();

    	MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if(files.isEmpty()){
			//TODO error
		}
    	FilePart file = files.get(0);

    	Document doc = (Document)o;
    	ContentStream cs = Util.convertFileToContentStream(session, file);
    	doc.setContentStream(cs, true);


    	return redirectToParent(input);
    }

    public static Result download(String id){
    	Session session = createSession();

    	CmisObject obj = session.getObject(id);
    	if(!Util.isDocument(obj)){
    		//TODO logging
    		return badRequest();
    	}
    	
    	Document doc = (Document)obj;
    	ContentStream cs = doc.getContentStream();

    	File tmpFile = null;
		try {
			tmpFile = Util.convertInputStreamToFile(cs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		//TODO better solution for encoding file name
		
		response().setHeader("Content-disposition", "attachment; filename="+ doc.getName());
		response().setContentType(cs.getMimeType());
		
		
    	return ok(tmpFile);
    }

    public static Result delete(String id){
    	Session session = createSession();
    	ObjectId objectId = new ObjectIdImpl(id);
    	session.delete(objectId);

    	return redirect(routes.Node.index());
    }

    public static Result checkOut(String id){
    	Session session = createSession();
    	CmisObject o = session.getObject(id);

    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();

    	if(!Util.isDocument(o)){
    		//TODO error
    	}

    	Document doc = (Document)o;

    	//Check if checkout is possible
    	if(doc.isVersionSeriesCheckedOut()){
    		//TODO error
    		System.out.println("already checked out");
    	}else{
    		//Check out
    		doc.checkOut();


    	}

    	return ok();
    }

    public static Result cancelCheckOut(String id){
    	Session session = createSession();
    	CmisObject o = session.getObject(id);

    	if(!Util.isDocument(o)){
    		//TODO error
    	}

    	Document doc = (Document)o;
    	doc.cancelCheckOut();

    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();
    	return redirectToParent(input);
    }

    public static Result checkIn(String id){
    	Session session = createSession();

    	CmisObject o = session.getObject(id);

    	DynamicForm input = Form.form();
    	input = input.bindFromRequest();

    	MultipartFormData body = request().body().asMultipartFormData();
		List<FilePart> files = body.getFiles();
		if(files.isEmpty()){
			//TODO error
		}
    	FilePart file = files.get(0);


    	Document doc = (Document)o;


    	ContentStream cs = Util.convertFileToContentStream(session, file);


    	//Execute
    	Map<String, Object>param = new HashMap<String, Object>();
    	doc.checkIn(true, param, cs, "");


    	return redirectToParent(input);
    }

    public static Result search(String term){
    	Session session = createSession();

    	OperationContext ctxt = session.getDefaultContext();

    	List<CmisObject> list = new ArrayList<CmisObject>();
    	//Build WHERE clause(cmis:document)
    	MessageFormat docFormat = new MessageFormat("cmis:name LIKE ''%{0}%'' OR CONTAINS(''{0}'')");
    	String docStatement = docFormat.format(new String[]{term});
    	ItemIterable<CmisObject> docResults = session.queryObjects("cmis:document", docStatement, false, ctxt);
    	Iterator<CmisObject> docItr = docResults.iterator();
    	while(docItr.hasNext()){
    		list.add(docItr.next());
    	}

    	//Build WHERE clause(cmis:folder)
    	MessageFormat folderFormat = new MessageFormat("cmis:name LIKE ''%{0}%''");
    	String folderStatement = folderFormat.format(new String[]{term});
    	ItemIterable<CmisObject> folderResults = session.queryObjects("cmis:folder", folderStatement, false, ctxt);
    	Iterator<CmisObject> folderItr = folderResults.iterator();
    	while(folderItr.hasNext()){
    		list.add(folderItr.next());
    	}

    	return ok(search.render(term, list));
    }

   public static Result showPermission(String id){
	   Session session = createSession();
	   
	   CmisObject obj = session.getObject(id);
	   
	   //Get permission types
	   List<PermissionDefinition> permissionDefs = session.getRepositoryInfo().getAclCapabilities().getPermissions();
	   
	   //Principals
	   List<Principal>members = new ArrayList<Principal>();
	   if(obj.getAcl() != null){
		   List<Ace> list = obj.getAcl().getAces();
		   if(CollectionUtils.isNotEmpty(list)){
			   String anyone = session.getRepositoryInfo().getPrincipalIdAnyone();
			   String anonymous = session.getRepositoryInfo().getPrincipalIdAnonymous();
			   for(Ace ace : list){
				   String principalId = ace.getPrincipalId();
				   //call API
				   Principal p = getPrincipal(principalId, anyone, anonymous);
				   if(p != null){
					   members.add(p);
				   }
			   }
		   }
	   }

	   return ok(views.html.node.permission.render(obj, members, permissionDefs));
   }
   
   private static Principal getPrincipal(String principalId, String anyone, String anonymous){
	   //anyone
	   if(anyone.equals(principalId)){
			return new Principal("group", anyone, anyone);  
		   }
	   
	   //anonymous
	   if(anonymous.equals(principalId)){
		return new Principal("user", anonymous, anonymous);  
	   }
	   
	   //user
	   JsonNode resultUser = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/user/show/" + principalId);
	   //TODO check status
	   JsonNode user = resultUser.get("user");
	   if(user != null){
		   Principal p = new Principal("user", user.get("userId").asText(), user.get("userName").asText());
		   return p;
	   }
		
	   //group
	   JsonNode resultGroup = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/group/show/" + principalId);
	   //TODO check status
	   JsonNode group = resultGroup.get("group");
	   if(group != null){
		   Principal p = new Principal("group", group.get("groupId").asText(), group.get("groupName").asText());
		   return p;
	   }

	   return null;
   }

}
