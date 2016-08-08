package controllers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.mvc.Controller;
import play.mvc.Result;
import util.ErrorMessage;
import util.Util;

public class Archive extends Controller{
<<<<<<< HEAD
	
	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
	
	public static Result index(String repositoryId, Integer page) {
		
		String endPoint = getEndpoint(repositoryId) + "index";
		
		int pageSize = Util.getNavigationPagingSize();
		endPoint += ("?limit=" + pageSize);
		
=======

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	public static Result index(String repositoryId, Integer page) {

		String endPoint = getEndpoint(repositoryId) + "index";

		int pageSize = Util.getNavigationPagingSize();
		endPoint += ("?limit=" + pageSize);

>>>>>>> integration
		Integer skip = 0;
		if(page >= 2){
			skip = (page - 1) * pageSize;
			endPoint += ("&skip=" + skip);
		}
<<<<<<< HEAD
		
=======

>>>>>>> integration
		JsonNode json = Util.getJsonResponse(session(), endPoint);

		ArrayNode archives =  (ArrayNode) json.get("archives");
		Iterator<JsonNode> itr = archives.iterator();
		List<model.Archive> list = new ArrayList<model.Archive>();
		while(itr.hasNext()){
			ObjectNode archiveJson = (ObjectNode)(itr.next());
			model.Archive archive = new model.Archive(archiveJson);
			list.add(archive);
		}
<<<<<<< HEAD
		
		return ok(views.html.archive.index.render(repositoryId, list, page));
		
	}
	
=======

		return ok(views.html.archive.index.render(repositoryId, list, page));

	}

>>>>>>> integration
	public static Result restore(String repositoryId, String archiveId){
		JsonNode json = Util.putJsonResponse(session(), getEndpoint(repositoryId) + "restore/" + archiveId, null);
		if(Util.isRestSuccess(json)){
			return ok();
		}else{
			String errorCode = json.get("error").get(0).get("archive").asText();
			return internalServerError(ErrorMessage.getMessage(errorCode));
		}
	}
<<<<<<< HEAD
	
=======

>>>>>>> integration
	public static Result destroy(String repositoryId, String archiveId){
		JsonNode json = Util.deleteJsonResponse(session(), getEndpoint(repositoryId) + "destroy/" + archiveId);
		if(Util.isRestSuccess(json)){
			return ok();
		}else{
			String errorCode = json.get("error").get(0).get("archive").asText();
			return internalServerError(ErrorMessage.getMessage(errorCode));
		}
	}
<<<<<<< HEAD
	
=======

>>>>>>> integration
	private static String getEndpoint(String repositoryId){
		return coreRestUri + "repo/" + repositoryId + "/archive/";
	}
}
