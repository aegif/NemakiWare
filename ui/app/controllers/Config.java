package controllers;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Session;

import com.fasterxml.jackson.databind.JsonNode;
//import com.sun.media.jfxmedia.track.Track.Encoding;

import play.data.DynamicForm;
import play.data.Form;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;
import util.authentication.NemakiProfile;
import views.html.config.*;
import org.pac4j.play.java.Secure;

public class Config extends Controller {

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	private static Session getCmisSession(String repositoryId) {
		return CmisSessions.getCmisSession(repositoryId, ctx());
	}

	@Secure
	public Result index(String repositoryId) {
		return list(repositoryId);
	}

	@Secure
	public Result list(String repositoryId) {
		NemakiProfile profile = Util.getProfile(ctx());
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "/list");

		// TODO check status
		JsonNode configurations = result.get("configurations");

		List<model.Config> list = new ArrayList<model.Config>();
		if (configurations == null) {
			configurations = Json.parse("[]");
		} else {
			Iterator<JsonNode> itr = configurations.elements();
			while (itr.hasNext()) {
				JsonNode node = itr.next();
				model.Config config = createConfig(node);
				list.add(config);
			}
		}

		// render
		if (Util.dataTypeIsHtml(request().acceptedTypes())) {
			return ok(index.render(repositoryId, list, profile));
		} else {
			return ok(configurations);
		}
	}

	@Secure
	public Result showDetail(String repositoryId, String configKey) {
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "/show/" + configKey);

		JsonNode configNode = result.get("configuration");
		model.Config config = createConfig(configNode);

		// render
		return ok(detail.render(repositoryId, config));
	}

	private static model.Config createConfig(JsonNode configNode) {
		model.Config config = new model.Config();
		config.key = configNode.get("key").asText();
		config.value = configNode.get("value").asText();
		config.isDefault = configNode.get("isDefault").asBoolean();
		return config;
	}

	@Secure
	public Result update(String repositoryId, String key) {
		// Get input form data
		DynamicForm input = Form.form();
		input = input.bindFromRequest();
		String value = Util.getFormData(input, "config-value");

		Map<String, String> params = new HashMap<String, String>();
		params.put("key", key);
		params.put("value", value);
		JsonNode result = Util.putJsonResponse(ctx(), getEndpoint(repositoryId), params);

    	if(isSuccess(result)){
    		JsonNode configNode = result.get("configuration");
    		model.Config config = createConfig(configNode);

    		// render
    		return ok(configNode);
    	}else{
    		String error = result.get("error").get(0).get("user").asText();
    		return internalServerError(error);
    	}
	}

	private static String getEndpoint(String repositoryId) {
		return coreRestUri + "repo/" + repositoryId + "/config";
	}

	private static boolean isSuccess(JsonNode result){
		return "success".equals(result.get("status").asText());
	}

}
