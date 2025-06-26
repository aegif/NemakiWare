package jp.aegif.nemaki.rest;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/repositories")
public class RepositoriesResource extends ResourceBase {

    private static final Logger log = LoggerFactory.getLogger(RepositoriesResource.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String list() {
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray repositories = new JSONArray();
        JSONArray errMsg = new JSONArray();

        try {
            repositories.add("bedroom");
            repositories.add("canopy");
            
            result.put("repositories", repositories);
            status = true;
        } catch (Exception e) {
            log.warn("Failed to retrieve repositories list", e);
            addErrMsg(errMsg, "repositories", "failsToRetrieve");
            status = false;
        }

        result = makeResult(status, result, errMsg);
        return result.toJSONString();
    }
}
