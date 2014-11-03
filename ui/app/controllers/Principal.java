package controllers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


@Authenticated(Secured.class)
public class Principal extends Controller{
	
	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
	
	
	public static Result search(String term){
		List<model.Principal>principals = new ArrayList<model.Principal>();
		
		//user search
		JsonNode resultUsers = Util.getJsonResponse(coreRestUri + "user/search?query=" + term);
    	//TODO check status
    	JsonNode users = resultUsers.get("result");
		if(users != null){
			Iterator<JsonNode> userItr = users.iterator();
			while(userItr.hasNext()){
				JsonNode user = userItr.next();
				
				model.Principal p = new model.Principal("user", user.get("userId").asText(), user.get("userName").asText());
				principals.add(p);
			}
		}
		
		//group search
		JsonNode resultGroups = Util.getJsonResponse(coreRestUri + "group/search?query=" + term);
    	//TODO check status
    	JsonNode groups = resultGroups.get("result");
		if(groups != null){
			Iterator<JsonNode> groupItr = groups.iterator();
			while(groupItr.hasNext()){
				JsonNode group = groupItr.next();
				
				model.Principal p = new model.Principal("group", group.get("groupId").asText(), group.get("groupName").asText());
				principals.add(p);
			}
		}
    	
		//convert
		 final ByteArrayOutputStream out = new ByteArrayOutputStream();
		ObjectMapper mapper = new ObjectMapper();
		try {
			mapper.writeValue(out, principals);
			 final byte[] data = out.toByteArray();
			 JsonNode converted = Json.parse(new String(data));
			 
			 return ok(converted);
			 
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return ok();
	}
}
