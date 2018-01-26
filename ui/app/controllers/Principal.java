package controllers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.net.URLEncoder;
import org.apache.chemistry.opencmis.client.api.Session;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import constant.Token;
import org.pac4j.play.java.Secure;

public class Principal extends Controller{

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	private static Session getCmisSession(String repositoryId){
		return CmisSessions.getCmisSession(repositoryId, ctx());
	}

	@Secure
	public Result search(String repositoryId, String term, String groupId){
		List<model.Principal>principals = new ArrayList<model.Principal>();
		
		try {
			term = URLEncoder.encode(term,"UTF-8");
		} catch (UnsupportedEncodingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		//user search
		JsonNode resultUsers = Util.getJsonResponse(ctx(), coreRestUri + "repo/" + repositoryId + "/user/search?query=" + term); //TODO
    	//TODO check status
    	JsonNode users = resultUsers.get("result");
		if(users != null){
			Iterator<JsonNode> userItr = users.iterator();
			while(userItr.hasNext()){
				JsonNode user = userItr.next();

				model.Principal p = new model.Principal(Token.PRINCIPAL_GENRE_USER, user.get("userId").asText(), user.get("userName").asText());
				principals.add(p);
			}
		}

		//group search
		JsonNode resultGroups = Util.getJsonResponse(ctx(), coreRestUri + "repo/" + repositoryId + "/group/search?query=" + term);
    	//TODO check status
    	JsonNode groups = resultGroups.get("result");
		if(groups != null){
			Iterator<JsonNode> groupItr = groups.iterator();
			while(groupItr.hasNext()){
				JsonNode group = groupItr.next();

				model.Principal p = new model.Principal(Token.PRINCIPAL_GENRE_GROUP, group.get("groupId").asText(), group.get("groupName").asText());

				//Remove the same group id when called from a group member search
				if(p.id != null && p.id.equals(groupId)){
					continue;
				}

				principals.add(p);
			}
		}

		//Add anyone group
		principals.add(getAnyone(repositoryId));

		//convert
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		ObjectMapper mapper = new ObjectMapper();
		try {
			mapper.writeValue(out, principals);
			final byte[] data = out.toByteArray();
			JsonNode converted = Json.parse(new String(data, StandardCharsets.UTF_8));

			return ok(converted);

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return ok();
	}

	private static model.Principal getAnyone(String repositoryId){
		Session session = getCmisSession(repositoryId);
		String anyone = session.getRepositoryInfo().getPrincipalIdAnyone();
		model.Principal p = new model.Principal(Token.PRINCIPAL_GENRE_GROUP, anyone, anyone);
		return p;
	}
}
