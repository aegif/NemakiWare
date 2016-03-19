package controllers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.libs.F.Promise;
import play.libs.ws.WS;
import play.libs.ws.WSResponse;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;

public class Archive extends Controller{
	
	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
	
	public static Result index(String repositoryId) {
		//List<CmisObject> list = new ArrayList<CmisObject>();
		
		Promise<WSResponse> result = WS.url(getEndpoint(repositoryId) + "index").setAuth("admin", "admin").get();
		WSResponse response = result.get(5000);
		JsonNode json = response.asJson();
		ArrayNode archives =  (ArrayNode) json.get("archives");
		Iterator<JsonNode> itr = archives.iterator();
		List<model.Archive> list = new ArrayList<model.Archive>();
		while(itr.hasNext()){
			ObjectNode archiveJson = (ObjectNode)(itr.next());
			model.Archive archive = new model.Archive(archiveJson);
			list.add(archive);
		}
		
		return ok(views.html.archive.index.render(repositoryId, list));
		
	}
	
	private static String getEndpoint(String repositoryId){
		return coreRestUri + "repo/" + repositoryId + "/archive/";
	}
}
