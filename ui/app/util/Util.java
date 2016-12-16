package util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.chemistry.opencmis.client.SessionParameterMap;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Item;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.Rendition;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.PropertyImpl;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.ExtensionLevel;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;

import play.api.http.MediaRange;
import play.data.DynamicForm;
import play.libs.Json;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Http.Request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Guice;
import com.google.inject.Injector;

import constant.PropertyKey;
import constant.Token;
import constant.UpdateContext;
import controllers.CmisSessions;
import jp.aegif.nemaki.plugin.action.JavaBackedAction;
import jp.aegif.nemaki.plugin.action.JavaBackedActionModule;
import jp.aegif.nemaki.plugin.action.JavaBackedUIAction;
import jp.aegif.nemaki.plugin.action.UIActionContext;
import jp.aegif.nemaki.plugin.action.trigger.ActionTriggerBase;
import jp.aegif.nemaki.plugin.action.trigger.UserButtonPerCmisObjcetActionTrigger;
import model.ActionPluginUIElement;

public class Util {

	public static String getVersion(String repositoryId, play.mvc.Http.Session session){
		Session cmisSession = CmisSessions.getCmisSession(repositoryId, session);
		RepositoryInfo repo = cmisSession.getRepositoryInfo();
		return repo.getProductVersion();
	}

	public static boolean isAdmin(String repositoryId, String userId, play.mvc.Http.Session session){
		boolean isAdmin = false;

		String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
		String endPoint = coreRestUri + "repo/" + repositoryId + "/user/";

		try{
			JsonNode result = Util.getJsonResponse(session, endPoint + "show/" + userId);
			if("success".equals(result.get("status").asText())){
				JsonNode _user = result.get("user");
				model.User user = new model.User(_user);

				isAdmin = user.isAdmin;
			}
		}catch(Exception e){
			//TODO logging
			System.out.println("This user is not returned in REST API:" + userId);
		}

		return isAdmin;
	}

	public static Session createCmisSession(String repositoryId, play.mvc.Http.Session session){

		if(StringUtils.isNotBlank(session.get(Token.HEADER_AUTH))){
			String userId = session.get(Token.LOGIN_USER_ID);
			return createCmisSessionByAuthHeader(repositoryId, userId);
		}else{
			String userId = session.get(Token.LOGIN_USER_ID);
			String password = session.get(Token.LOGIN_USER_PASSWORD);
			return createCmisSessionByBasicAuth(repositoryId, userId, password);
		}
	}
	public static Session createCmisSessionByAuthHeader(String repositoryId, String remoteUserId){
		SessionParameterMap parameter = new SessionParameterMap();

		//TODO enable change a user
		parameter.addHeader("X-NemakiWare-Remote-User", remoteUserId);

		Session session = createCmisSessionWithParam(repositoryId, parameter);

		return session;
	}


	public static Session createCmisSessionByBasicAuth(String repositoryId, String userId, String password){
		SessionParameterMap parameter = new SessionParameterMap();

		//TODO enable change a user
		parameter.setBasicAuthentication(userId, password);

		Session session = createCmisSessionWithParam(repositoryId, parameter);

		return session;
	}

	private static Session createCmisSessionWithParam(String repositoryId, SessionParameterMap parameter) {
		parameter.setLocale("", "");
		parameter.setRepositoryId(repositoryId);
		String coreAtomUri = buildNemakiCoreUri() + "atom/"+ repositoryId;
		parameter.setAtomPubBindingUrl(coreAtomUri);

    	SessionFactory f = SessionFactoryImpl.newInstance();

    	Session session = f.createSession(parameter);

    	OperationContext operationContext = session.createOperationContext(null,
				true, true, false, IncludeRelationships.BOTH, null, false, null, false, 100);
		session.setDefaultContext(operationContext);
		return session;
	}

	public static String buildNemakiCoreUri(){
		String protocol = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_PROTOCOL);
		String host = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_HOST);
		String port = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_PORT);
		String context = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_CONTEXT);
		return buildUri(protocol, host, port, context);
	}

	public static String buildNemakiCoreRestRepositoryUri(String repositoryId){
		String coreUri = Util.buildNemakiCoreUri();
		StringBuilder sb = new StringBuilder(coreUri);
		String rest = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_REST);
		String repo = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_REPOSITORY);
		sb.append(rest).append("/").append(repo).append("/").append(repositoryId).append("/");
		return sb.toString();
	}

	private static String buildUri(String protocol, String host, String port, String context){
		StringBuilder sb = new StringBuilder();
		sb.append(protocol).append("://").append(host).append(":").append(port).append("/").append(context).append("/");
		return sb.toString();
	}

	public static boolean isDocument(CmisObject obj) {
		return obj.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT);
	}

	public static boolean isFolder(CmisObject obj) {
		return obj.getBaseTypeId().equals(BaseTypeId.CMIS_FOLDER);
	}

	public static boolean hasContentStream(CmisObject object){
		if(object instanceof Document){
			Document doc = (Document)object;

			DocumentTypeDefinition type = (DocumentTypeDefinition) (doc.getType());
			ContentStreamAllowed csa = type.getContentStreamAllowed();
			switch (csa){
				case REQUIRED:
					return true;
				case ALLOWED:
					return doc.getContentStream() != null;
				case NOTALLOWED:
					return false;
				default:
					return true;
			}
		}else{
			return false;
		}
	}

	
	public static Set<JavaBackedUIAction> getUIActions() {
		ServiceLoader<JavaBackedActionModule> loader = ServiceLoader.load(JavaBackedActionModule.class);
		Injector injector = Guice.createInjector(loader);
		injector.injectMembers(ActionPlugin.class);
		ActionPlugin instance = injector.getInstance(ActionPlugin.class);
		return instance.getActions();
	}
	
	public static List<ActionPluginUIElement> getUIActionPluginUIElementList(CmisObject object, Session session) {
		List<ActionPluginUIElement> result = new ArrayList<ActionPluginUIElement>();
		Set<JavaBackedUIAction> actions = getUIActions();
		for (JavaBackedUIAction action : actions) {
			UIActionContext context = new UIActionContext(object, session);
			if (action.canExecute(context)) {
				ActionTriggerBase trigger = action.getActionTrigger(context);
				if (trigger instanceof UserButtonPerCmisObjcetActionTrigger) {
					ActionPluginUIElement button = new ActionPluginUIElement();
					UserButtonPerCmisObjcetActionTrigger objectActionTrigger = (UserButtonPerCmisObjcetActionTrigger) trigger;
					button.setActionId(action.getClass().getSimpleName());
					button.setDisplayName(objectActionTrigger.getDisplayName());
					button.setFontAwesomeName(objectActionTrigger.getFontAwesomeName());
					button.setFormHtml(objectActionTrigger.getFormHtml());
					result.add(button);
				}
			}
		}
		return result;
	}
	
	public static JavaBackedUIAction getActionPlugin(CmisObject object, String actionId, Session session){
		Set<JavaBackedUIAction> actions = getUIActions();
		for (JavaBackedUIAction action : actions) {
			UIActionContext context = new UIActionContext(object, session);
			ActionTriggerBase trigger = action.getActionTrigger(context);
			if (action.getClass().getSimpleName().equals(actionId)) {
				return action;
			}
		}
		return null;
	}
	
	public static List<ActionPluginUIElement> getActionPluginUIElementList(CmisObject object){
		List<CmisExtensionElement> exList = object.getExtensions(ExtensionLevel.OBJECT);
		List<ActionPluginUIElement> result = new ArrayList<ActionPluginUIElement>();

		
		if (exList != null){
			for(CmisExtensionElement elm : exList){
				if(elm.getNamespace() == "http://www.aegif.jp/Nemaki/action"){
					ActionPluginUIElement button = new ActionPluginUIElement();
					for(CmisExtensionElement actionElm : elm.getChildren()){
						if(actionElm.getName().equals("actionId")){
							button.setActionId(actionElm.getValue());
						}else if(actionElm.getName().equals("actionButtonLabel")){
							button.setDisplayName(actionElm.getValue());
						}else if(actionElm.getName().equals("actionButtonIcon")){
							button.setFontAwesomeName(actionElm.getValue());
						}else if(actionElm.getName().equals("actionFormHtml")){
							button.setFormHtml(actionElm.getValue());
						}
					}
					result.add( button );
				}
			}
		}
		return result;
	}

	public static ActionPluginUIElement getActionPluginUIElement(CmisObject object, String actionId, Session session){
		for(ActionPluginUIElement elm : Util.getUIActionPluginUIElementList(object, session)){
			if(elm.getActionId().equals(actionId)) return elm;
		}
		return null;
	}

	public static List<String> getTypeFilterList(List<CmisObject> list){
		List<String> result = list
				.stream()
				.filter(p -> isDocument(p) || isFolder(p))
				.map(CmisObject::getType)
				.distinct()
				.map(ObjectType::getDisplayName)
				.collect(Collectors.toList());
		return result;
	}

	public static Document convertToDocument(CmisObject obj) {
		Document doc = (Document) obj;
		return doc;
	}
	public static Folder convertToFolder(CmisObject obj) {
		Folder folder = (Folder) obj;
		return folder;
	}

	/**
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	public static File convertInputStreamToFile(InputStream inputStream)
			throws IOException {

		File file = File.createTempFile(
				String.valueOf(System.currentTimeMillis()), null);
		file.deleteOnExit();

		try {
			// write the inputStream to a FileOutputStream
			OutputStream out = new FileOutputStream(file);

			int read = 0;
			byte[] bytes = new byte[1024];

			while ((read = inputStream.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}
			inputStream.close();
			out.flush();
			out.close();
		} catch (IOException e) {
			System.out.println(e.getMessage());
		}

		return file;
	}

	/**
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	public static File convertInputStreamToFile(ContentStream contentStream)
			throws IOException {

		InputStream inputStream = contentStream.getStream();

		File file = File.createTempFile(contentStream.getFileName(), "");

		OutputStream out = null;
		try {
			out = new FileOutputStream(file);

			int read = 0;
			byte[] bytes = new byte[1024];

			while ((read = inputStream.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}

		} catch (IOException e) {
			System.out.println(e.getMessage());
		}finally{
			inputStream.close();
			out.flush();
			out.close();
		}

		file.deleteOnExit();
		return file;
	}

	public static ContentStream convertFileToContentStream(Session session, FilePart file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file.getFile());
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		ContentStream cs = session.getObjectFactory().createContentStream(
				file.getFilename(), file.getFile().length(),
				file.getContentType(), fis);

		return cs;
	}

	public static String getIconClassName(CmisObject obj){
		String className = "";

		// Document
		if (obj instanceof Document) {
			Document doc = (Document) obj;
			String mimeType = doc.getContentStreamMimeType();

			if ("application/x-javascript".equals(mimeType)) {
				className = "fa-file-image-o";
			} else if ("text/plain".equals(mimeType)) {
				className = "fa-file-text-o ";
			} else if ("application/msword".equals(mimeType)) {
				className = "fa-file-word-o ";
			} else if ("text/xml".equals(mimeType)) {
				className = "fa-file-code-o";
			} else if ("image/gif".equals(mimeType)) {
				className = "fa-file-image-o";
			} else if ("image/jpeg".equals(mimeType)) {
				className = "fa-file-image-o";
			} else if ("image/jpeg2000".equals(mimeType)) {
				className = "fa-file-image-o";
			} else if ("video/mpeg".equals(mimeType)) {
				className = "fa-file-video-o ";
			} else if ("audio/x-mpeg".equals(mimeType)) {
				className = "fa-file-audio-o";
			} else if ("video/mp4".equals(mimeType)) {
				className = "fa-file-video-o";
			} else if ("video/mpeg2".equals(mimeType)) {
				className = "fa-file-video-o";
			} else if ("application/pdf".equals(mimeType)) {
				className = "fa-file-pdf-o";
			} else if ("image/png".equals(mimeType)) {
				className = "fa-file-image-o ";
			} else if ("application/vnd.powerpoint".equals(mimeType)) {
				className = "fa-file-powerpoint-o";
			} else if ("audio/x-wav".equals(mimeType)) {
				className = "fa-file-audio-o ";
			} else if ("application/vnd.excel".equals(mimeType)) {
				className = "fa-file-excel-o";
			} else if ("application/zip".equals(mimeType)) {
				className = "fa-file-archive-o";
			} else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document"
					.equals(mimeType)) {
				className = "fa-file-word-o";
			} else if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
					.equals(mimeType)) {
				className = "fa-file-excel-o";
			} else if ("application/vnd.openxmlformats-officedocument.presentationml.presentation"
					.equals(mimeType)) {
				className = "fa-file-powerpoint-o";
			} else {
				className = "fa-file-o ";
			}
			// Folder
		} else if(obj instanceof Folder) {
			className = "fa-folder-o";
		} else if(obj instanceof Item) {
			className = "fa fa-circle-thin";
		}

		return className;
	}

	private static HttpClient buildClient(play.mvc.Http.Session session){
		// configurations
				String userAgent = "NemakiWare UI";

				// headers
				List<Header> headers = new ArrayList<Header>();
				headers.add(new BasicHeader("Accept-Charset", "utf-8"));
				headers.add(new BasicHeader("Accept-Language", "ja, en;q=0.8"));
				headers.add(new BasicHeader("User-Agent", userAgent));

				// set credential
				String user = session.get(Token.LOGIN_USER_ID);
				String password = session.get(Token.LOGIN_USER_PASSWORD);
				Credentials credentials = new UsernamePasswordCredentials(user, password);
				String host = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_HOST);
				String port = NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_PORT);
				AuthScope scope = new AuthScope(host, Integer.valueOf(port));
				CredentialsProvider credsProvider = new BasicCredentialsProvider();
				credsProvider.setCredentials(scope, credentials);

				//CredentialsProvider doesn't add BASIC auth header
				headers.add(BasicScheme.authenticate(credentials, "US-ASCII", false));

				// create client
				HttpClient httpClient = HttpClientBuilder.create()
						.setDefaultHeaders(headers)
						.setDefaultCredentialsProvider(credsProvider)
						.build();

				return httpClient;
	}

	private static JsonNode executeRequest(HttpClient client, HttpRequest request){
		try {
			HttpResponse response = client.execute((HttpUriRequest) request);
			int responseStatus = response.getStatusLine().getStatusCode();
			if (HttpStatus.SC_OK != responseStatus) {
				throw new Exception("Server connection failed");
			}

			InputStream is = response.getEntity().getContent();

			BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));

			StringBuilder sb = new StringBuilder();

			String line;

			while ((line = br.readLine()) != null) {
				sb.append(line);
			}

			//TODO should log the reason of failure
			JsonNode jn = Json.parse(sb.toString());

			return jn;

		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public static JsonNode getJsonResponse(play.mvc.Http.Session session, String url) {
		// create client
		HttpClient client = buildClient(session);
		HttpGet request = new HttpGet(url);
		return executeRequest(client, request);
	}

	public static JsonNode postJsonResponse(play.mvc.Http.Session session, String url, JsonNode json) {
		// create client
		HttpClient client = buildClient(session);
		HttpPost request = new HttpPost(url);

		try {
			StringEntity body = new StringEntity(json.toString());
			request.addHeader("Accept", "application/json");
	        request.addHeader("Content-Type", "application/json");
	        request.setEntity(body);
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return executeRequest(client, request);
	}

	public static JsonNode postJsonResponse(play.mvc.Http.Session session, String url, Map<String, String>params) {
		// create client
		HttpClient client = buildClient(session);

		HttpPost request = new HttpPost(url);
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		if(MapUtils.isNotEmpty(params)){
			for(Entry<String, String>entry : params.entrySet()){
				list.add(new BasicNameValuePair(entry.getKey(),entry.getValue()));
			}
		}

		try {
			request.setEntity(new UrlEncodedFormEntity(list, "utf-8"));
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return executeRequest(client, request);
	}


	public static JsonNode putJsonResponse(play.mvc.Http.Session session, String url, Map<String, String>params) {
		// create client
		HttpClient client = buildClient(session);

		HttpPut request = new HttpPut(url);
		List<NameValuePair> list = new ArrayList<NameValuePair>();
		if(MapUtils.isNotEmpty(params)){
			for(Entry<String, String>entry : params.entrySet()){
				list.add(new BasicNameValuePair(entry.getKey(),entry.getValue()));
			}
		}

		try {
			request.setEntity(new UrlEncodedFormEntity(list, "utf-8"));
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return executeRequest(client, request);
	}

	public static JsonNode deleteJsonResponse(play.mvc.Http.Session session, String url){
		HttpClient client = buildClient(session);
		HttpDelete request = new HttpDelete(url);
		return executeRequest(client, request);
	}

	public static String escapeLikeQuery(String query){
		String result = query;
		result = query.replaceAll("%", "\\%");
		result = query.replaceAll("_", "\\_");
		return result;
	}

	public static String escapeContainsQuery(String query){
		String result = query;
		result = query.replaceAll("*", "\\*");
		result = query.replaceAll("?", "\\?");
		return result;
	}

	public static String escapeSelector(String selector){
		String result = selector;
		result = selector.replaceAll(":", "\\\\\\\\:");
		return result;
	}

	public static List<String> dividePath(String path){
		String[] ary = path.split("/");

		if(ary.length == 0){
			return new ArrayList<String>();
		}else{
			List<String> result = new ArrayList<String>();
			//TODO error
			//remove first empty element
			for(int i=1; i < ary.length; i++){
				result.add(ary[i]);
			}
			return result;
		}
	}

	public static boolean dataTypeIsHtml(List<MediaRange>accepted){
		if(CollectionUtils.isNotEmpty(accepted)){
			for(MediaRange mr : accepted){
				if(mr.toString().equals("text/html")){
					return true;
				}
			}
		}
		return false;
	}


	public static List<String> convertToList(JsonNode jsonArray){
		List<String> result = new ArrayList<String>();

		Iterator<JsonNode> itr = jsonArray.iterator();
		while(itr.hasNext()){
			result.add(itr.next().asText());
		}

		return result;
	}

	public static Boolean isOnCreate(Property<?>property){
		PropertyDefinition<?> pdf = property.getDefinition();
		return isOnCreate(pdf);
	}

	public static Boolean isOnCreate(PropertyDefinition<?>propertyDefinition){
		Updatability updatability = propertyDefinition.getUpdatability();
		if(Updatability.ONCREATE == updatability){
			return true;
		}else{
			return false;
		}
	}

	public static Boolean isReadWrite(Property<?>property){
		PropertyDefinition<?> pdf = property.getDefinition();
		return isEditable(pdf);
	}

	public static Boolean isReadWrite(PropertyDefinition<?>propertyDefinition){
		Updatability updatability = propertyDefinition.getUpdatability();
		if(Updatability.READWRITE == updatability || Updatability.WHENCHECKEDOUT == updatability){
			return true;
		}else{
			return false;
		}
	}

	public static Boolean isWhenCheckedOut(Property<?>property){
		PropertyDefinition<?> pdf = property.getDefinition();
		return isEditable(pdf);
	}

	public static Boolean isWhenCheckedOut(PropertyDefinition<?>propertyDefinition){
		Updatability updatability = propertyDefinition.getUpdatability();
		if(Updatability.READWRITE == updatability || Updatability.WHENCHECKEDOUT == updatability){
			return true;
		}else{
			return false;
		}
	}

	public static Boolean isEditable(Property<?>property){
		PropertyDefinition<?> pdf = property.getDefinition();
		return isEditable(pdf);
	}

	public static Boolean isEditable(PropertyDefinition<?>propertyDefinition){
		Updatability updatability = propertyDefinition.getUpdatability();
		if(Updatability.READWRITE == updatability || Updatability.WHENCHECKEDOUT == updatability){
			return true;
		}else{
			return false;
		}
	}

	public static Map<String, Ace> zipWithId(Acl acl){
		Map<String, Ace>result = new HashMap<String, Ace>();

		if(acl != null){
			List<Ace> list = acl.getAces();
			if(CollectionUtils.isNotEmpty(list)){
				for(Ace ace : list){
					result.put(ace.getPrincipalId(), ace);
				}
			}
		}

		return result;
	}

	public static String getHostPath(Request request){
		if(request.secure()){
			return "https://" + request.host();
		}else{
			return "http://" + request.host();
		}
	}

	 public static BaseTypeId getBaseType(Session cmisSession, String objectTypeId){
		 ObjectType objectType = cmisSession.getTypeDefinition(objectTypeId, true);
		 return objectType.getBaseTypeId();
	 }

	 public static String getFormData(DynamicForm input, String key){
		 String value = input.get(key);
		 if(value == null){
			 return getFormDataWithoutColon(input, key);
		 }else{
			 return value;
		 }
	 }

	 public static String getFormDataWithoutColon(DynamicForm input, String keyWithColon){
		 String key = keyWithColon.replace(":", "");
		 return input.get(key);
	 }

	 public static HashMap<String,Object> buildProperties(Map<String, PropertyDefinition<?>>propertyDefinitions, DynamicForm input, List<Updatability>updatabilities){
		 HashMap<String,Object>data = new HashMap<String, Object>();

		 for(Entry<String, PropertyDefinition<?>> entry : propertyDefinitions.entrySet()){
			 PropertyDefinition<?>propDef = entry.getValue();

			//TODO work around
			 if(propDef.getId().equals(PropertyIds.SECONDARY_OBJECT_TYPE_IDS) ||
				propDef.getId().equals(PropertyIds.OBJECT_TYPE_ID)){
				 continue;
			 }

			 //TODO work around: skip multiple value
			 if(propDef.getCardinality() == Cardinality.MULTI){
				 if (propDef.getPropertyType() == PropertyType.STRING) {
					 String items = getFormData(input, propDef.getId());
					 List<String> itemList = Arrays.asList(items.split(","));
					 data.put(propDef.getId(), itemList);
				 }
				 continue;
			 }

			 //TODO work around: skip obligatory choice value
			 if(CollectionUtils.isNotEmpty(propDef.getChoices()) && !propDef.isOpenChoice()){
				 continue;
			 }

			 if(updatabilities.contains(propDef.getUpdatability())){
				 if (propDef.getPropertyType() == PropertyType.STRING) {
					 data.put(propDef.getId(), getFormData(input, propDef.getId()));
				 } else if (propDef.getPropertyType() == PropertyType.DATETIME) {
					 String dateStr = getFormData(input, propDef.getId());
					 if (dateStr == null || dateStr.isEmpty()) {
						 continue;
					 }
					 GregorianCalendar cal = convertStringToCalendar(dateStr);
					 if (cal == null) {
						 throw new RuntimeException("Invalid DateTime format.");
					 }
					 data.put(propDef.getId(), cal);
				 }
			 }
		 }

		 return data;
	 }

	 public static HashMap<String,Object> buildProperties(Map<String, PropertyDefinition<?>>propertyDefinitions, Map<String,String> stringMap, List<Updatability>updatabilities){
		 HashMap<String,Object>data = new HashMap<String, Object>();

		 for(Entry<String, PropertyDefinition<?>> entry : propertyDefinitions.entrySet()){
			 PropertyDefinition<?>propDef = entry.getValue();

			//TODO work around
			 if(propDef.getId().equals(PropertyIds.SECONDARY_OBJECT_TYPE_IDS) ||
				propDef.getId().equals(PropertyIds.OBJECT_TYPE_ID)){
				 continue;
			 }

			 //TODO work around: skip multiple value
			 if(propDef.getCardinality() == Cardinality.MULTI){
				 continue;
			 }

			 //TODO work around: skip obligatory choice value
			 if(CollectionUtils.isNotEmpty(propDef.getChoices()) && !propDef.isOpenChoice()){
				 continue;
			 }

			 if(updatabilities.contains(propDef.getUpdatability())){
				 data.put(propDef.getId(), stringMap.get(propDef.getId()));
			 }
		 }

		 return data;
	 }

	 public static Map<String,String> createPropFormDataMap(Map<String, PropertyDefinition<?>>propertyDefinitions, DynamicForm input){
		 Map<String,String> result = new HashMap<String,String>();
		 for(Entry<String, PropertyDefinition<?>> def : propertyDefinitions.entrySet()){
			 String key = def.getValue().getId();
			 String value = getFormData(input, key);
			 if(value == null)continue;
			 result.put(key, value);
		 }
		 return result;
	 }

	 public static String tail(String str){
		 return str.substring(1, str.length());
	 }

	 public static boolean existPreview(CmisObject obj){
		 List<Rendition>renditions = obj.getRenditions();
		 if(CollectionUtils.isNotEmpty(renditions)){
			 for(Rendition rendition : renditions){
				 if("cmis:preview".equals(rendition.getKind())){
					 return true;
				 }
			 }
		 }

	    return false;
	 }

	 public static int getNavigationPagingSize(){
		 String _size = NemakiConfig.getValue(PropertyKey.NAVIGATION_PAGING_SIZE);
		 return Integer.valueOf(_size);
	 }

	 public static String getSeachEngineUrl(play.mvc.Http.Session session){
		String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
		String endPoint = coreRestUri + "all/" + "search-engine/";

		JsonNode result = Util.getJsonResponse(session, endPoint + "url");

		String status = result.get(Token.REST_STATUS).textValue();
		try {
			if(Token.REST_SUCCESS.equals(status)){
				String _url = result.get("url").textValue();
				String url;

					url = URLDecoder.decode(_url, "UTF-8");
					return url;

			}else{
				throw new Exception("REST API returned failure.");
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	 }

	 @SuppressWarnings("rawtypes")
	public static Property buildTempProperty(String id, String name, boolean required, boolean updatable, boolean multiple, List<Choice>choices, boolean openChoice, Object values){
		 PropertyDefinition pdf = buildTempStringDefinition(id, name, required, updatable, multiple, choices, openChoice);

		@SuppressWarnings("unchecked")
		PropertyImpl prop = new PropertyImpl(pdf, listify(values));
		 return prop;
	 }

	 @SuppressWarnings("rawtypes")
	public static Property buildTempProperty(PropertyDefinition pdf, Object values){
		 @SuppressWarnings("unchecked")
		PropertyImpl prop = new PropertyImpl(pdf, listify(values));
		 return prop;
	 }

	 @SuppressWarnings("unchecked")
	public static List<?> listify(Object obj){
		 if (obj instanceof List<?>) {
			 return (List<?>)obj;
		 }else{
			 @SuppressWarnings("rawtypes")
			List list = new ArrayList<Object>();
			 list.add(obj);
			 return list;
		 }
	 }

	 @SuppressWarnings("rawtypes")
	public static PropertyDefinition buildTempStringDefinition(String id, String name, boolean required, boolean updatable, boolean multiple, List<Choice>choices, boolean openChoice){
		PropertyStringDefinitionImpl pdf = new PropertyStringDefinitionImpl();
		pdf.setId(id);
		pdf.setDisplayName(name);
		pdf.setIsRequired(required);
		pdf.setUpdatability((updatable)? Updatability.READWRITE : Updatability.READONLY);
		pdf.setCardinality((multiple)? Cardinality.MULTI : Cardinality.SINGLE);
		pdf.setChoices(null);
		pdf.setIsOpenChoice(openChoice);
		return pdf;
	 }

	 public static boolean isMultiple(PropertyDefinition<?> pdf){
		 return Cardinality.MULTI == pdf.getCardinality();
	 }

	 public static boolean isEditable(@SuppressWarnings("rawtypes") PropertyDefinition pdf, UpdateContext updateContext){
		 switch(updateContext){
		 case NORMAL:
			 return isReadWrite(pdf);
		 case CREATE:
			 return isReadWrite(pdf) || isOnCreate(pdf);
		 case CHECKOUT:
			 return isReadWrite(pdf) || isWhenCheckedOut(pdf);
		 default:
			 return false;
		 }
	 }

	 public static boolean isEditableOnNodeBlank(PropertyDefinition<?> pdf){
		 return isEditable(pdf, UpdateContext.CREATE) &&
			!PropertyIds.OBJECT_TYPE_ID.equals(pdf.getId()) &&
			!PropertyIds.SECONDARY_OBJECT_TYPE_IDS.equals(pdf.getId());
	 }

	 public static String displayValue(CmisObject obj, String propertyId,  Locale locale){
		 if(PropertyIds.CREATED_BY.equals(propertyId)){
			 return obj.getCreatedBy();
		 }else if(PropertyIds.CREATION_DATE.equals(propertyId)){
			 return Formatter.calToString(obj.getCreationDate(), locale);
		 } else if(PropertyIds.LAST_MODIFIED_BY.equals(propertyId)){
			 return obj.getLastModifiedBy();
		 }else if(PropertyIds.LAST_MODIFICATION_DATE.equals(propertyId)){
			 return Formatter.calToString(obj.getLastModificationDate(), locale);
		 }else if(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY.equals(propertyId)){
			 if(isDocument(obj)){
				 Document doc = (Document)obj;
				 return doc.getVersionSeriesCheckedOutBy();
			 }else{
				 return null;
			 }
		 }else if(PropertyIds.OBJECT_TYPE_ID.equals(propertyId)){
			 return NemakiConfig.getLabel(obj.getType().getId(), locale.toLanguageTag());
		 }else{
			 Property<Object> prop =obj.getProperty(propertyId);
			 return prop == null ? "" : prop.getValueAsString();
		 }
	 }

	 public static boolean isFreezeCopy(CmisObject obj, play.mvc.Http.Session session){
		 String loginUserId = session.get(Token.LOGIN_USER_ID);
		 if(isDocument(obj)){
			 Document doc = (Document)obj;
			 if(doc.isVersionSeriesCheckedOut()){
				 String owner = doc.getVersionSeriesCheckedOutBy();
				 return (!loginUserId.equals(owner));
			 }
		 }
		return false;
	 }

	 public static List<PermissionDefinition> trimForDisplay(List<PermissionDefinition> list){
		 List<PermissionDefinition> result = new ArrayList<PermissionDefinition>();
		 for(PermissionDefinition def:list){
			 if(!"cmis:none".equals(def.getId())){
				 result.add(def);
			 }
		 }

		 return result;
	 }

	 public static List<String> difference(List<String> target, List<String> source){
		 List<String> result = new ArrayList<String>();

		 for(String s : target){
			 if(!source.contains(s)){
				 result.add(s);
			 }
		 }

		 return result;
	 }

	 public static JsonNode emptyJsonObject(){
		 return Json.toJson(new HashMap<String, String>());
	 }

	 public static boolean isRestSuccess(JsonNode nemakiApiResult){
		 if(nemakiApiResult == null || !(nemakiApiResult instanceof ObjectNode)){
			 return false;
		 }else{
			 JsonNode status = nemakiApiResult.get(Token.REST_STATUS);
			 if(status == null){
				 return false;
			 }else{
				 return Token.REST_SUCCESS.equals(status.textValue());
			 }
		 }
	 }

	 public static long getCompressionTargetMaxSize(){
		 String _size = NemakiConfig.getValue(PropertyKey.COMPRESSION_TARGET_MAXSIZE);
		 return Long.valueOf(_size);
	 }

	 public static GregorianCalendar convertStringToCalendar(String date, String format, Locale locale) {
		SimpleDateFormat sdf = new SimpleDateFormat(format, locale);
		Date d;
		try {
			d = sdf.parse(date);
			GregorianCalendar cal = new GregorianCalendar();
			cal.setTime(d);
			String str = cal.toString();
			return cal;
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return null;
	 }

	 public static GregorianCalendar convertStringToCalendar(String date) {
		 GregorianCalendar result = convertStringToCalendar(date, "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
		 if (result == null) {
			 result = convertStringToCalendar(date, "yyyy-MM-dd HH:mm:ss", Locale.JAPAN);
		 }
		 return result;
	 }


}
