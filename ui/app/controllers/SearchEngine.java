package controllers;

import com.fasterxml.jackson.databind.JsonNode;

import constant.Token;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.searchengine.index;

public class SearchEngine extends Controller{
	
	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
	
	public static Result index(String repositoryId){
		return ok(index.render(repositoryId));
	}
	
	public static Result init(String repositoryId){
		JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "init");
		
		String status = result.get(Token.REST_STATUS).textValue();
		if(Token.REST_SUCCESS.equals(status)){
			return ok();
		}else{
			return badRequest(result.get(Token.REST_ERROR));
		}
	}
	
	public static Result reindex(String repositoryId){
		JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "reindex");
		
		String status = result.get(Token.REST_STATUS).textValue();
		if(Token.REST_SUCCESS.equals(status)){
			return ok();
		}else{
			return badRequest(result.get(Token.REST_ERROR));
		}
	}
	
	private static String getEndpoint(String repositoryId){
		return coreRestUri + "repo/" + repositoryId + "/search-engine/";
	}
}
