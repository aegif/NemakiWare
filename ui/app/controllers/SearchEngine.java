package controllers;

import com.fasterxml.jackson.databind.JsonNode;

import constant.Token;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.searchengine.index;

public class SearchEngine extends Controller{
	
	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
	private static String endPoint = coreRestUri + "search-engine/";
	
	public static Result index(){
		return ok(index.render());
	}
	
	public static Result init(){
		JsonNode result = Util.getJsonResponse(session(), endPoint + "init");
		
		String status = result.get(Token.REST_STATUS).textValue();
		if(Token.REST_SUCCESS.equals(status)){
			return ok();
		}else{
			return badRequest(result.get(Token.REST_ERROR));
		}
	}
	
	public static Result reindex(){
		JsonNode result = Util.getJsonResponse(session(), endPoint + "reindex");
		
		String status = result.get(Token.REST_STATUS).textValue();
		if(Token.REST_SUCCESS.equals(status)){
			return ok();
		}else{
			return badRequest(result.get(Token.REST_ERROR));
		}
	}
}
