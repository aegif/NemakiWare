package controllers;

import com.fasterxml.jackson.databind.JsonNode;

import constant.Token;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.searchengine.index;
import org.pac4j.play.java.Secure;
public class SearchEngine extends Controller{

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	@Secure
	public Result index(String repositoryId){
		return ok(index.render(repositoryId));
	}

	@Secure
	public Result init(String repositoryId){
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "init");

		String status = result.get(Token.REST_STATUS).textValue();
		if(Token.REST_SUCCESS.equals(status)){
			return ok();
		}else{
			return badRequest(result.get(Token.REST_ERROR));
		}
	}

	@Secure
	public Result reindex(String repositoryId){
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "reindex");

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
