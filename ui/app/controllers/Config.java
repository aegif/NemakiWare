package controllers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.Session;

import com.fasterxml.jackson.databind.JsonNode;

import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;

import views.html.config.*;

@Authenticated(Secured.class)
public class Config extends Controller {

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	private static Session getCmisSession(String repositoryId){
		return CmisSessions.getCmisSession(repositoryId, session());
	}

	public static Result index(String repositoryId){
	    	return list(repositoryId);
	  }

	public static Result list(String repositoryId){

    	JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "/list" );

    	//TODO check status
    	JsonNode configurations = result.get("configurations");

    	List<model.Config> list = new ArrayList<model.Config>();

    	if(configurations == null){
    		configurations = Json.parse("[]");
    	}else{
    		Iterator<JsonNode> itr = configurations.elements();
        	while(itr.hasNext()){
        		JsonNode node = itr.next();

        		model.Config config = new model.Config();

        		config.key = node.get("key").asText();
        		config.value = node.get("value").asText();
        		config.isDefault = node.get("isDefault").asBoolean();

        		list.add(config);
        	}
    	}


    	//render
    	if(Util.dataTypeIsHtml(request().acceptedTypes())){
    		return ok(index.render(repositoryId, list));
    	}else{
    		return ok(configurations);

    	}


	}

	private static String getEndpoint(String repositoryId){
		return coreRestUri + "repo/" + repositoryId + "/config";
	}

}
