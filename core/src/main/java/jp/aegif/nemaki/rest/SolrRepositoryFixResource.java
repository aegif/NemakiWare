package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

@Path("/repo/{repositoryId}/search-engine/fix")
public class SolrRepositoryFixResource extends ResourceBase {

    private SolrUtil solrUtil;

    @POST
    @Path("/repository-id")
    @Produces(MediaType.APPLICATION_JSON)
    public String fixRepositoryId(@PathParam("repositoryId") String repositoryId,
                                 @FormParam("objectId") String objectId,
                                 @Context HttpServletRequest request) {
        boolean status = true;
        JSONObject result = new JSONObject();
        JSONArray errMsg = new JSONArray();

        if (!checkAdmin(errMsg, request)) {
            return makeResult(status, result, errMsg).toString();
        }

        if (objectId == null || objectId.trim().isEmpty()) {
            status = false;
            errMsg.add("objectId parameter is required");
            return makeResult(status, result, errMsg).toString();
        }

        HttpClient httpClient = HttpClientBuilder.create().build();
        String solrUrl = solrUtil.getSolrUrl();
        String url = solrUrl + "nemaki/update?commit=true";
        
        String updateJson = String.format(
            "[{\"id\":\"%s_%s\",\"repository_id\":{\"set\":\"%s\"}}]",
            repositoryId, objectId, repositoryId
        );

        HttpPost httpPost = new HttpPost(url);
        try {
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(updateJson, "UTF-8"));
            
            HttpResponse response = httpClient.execute(httpPost);
            int responseStatus = response.getStatusLine().getStatusCode();
            if (HttpStatus.SC_OK != responseStatus) {
                throw new Exception("Solr server connection failed: " + responseStatus);
            }

            String body = EntityUtils.toString(response.getEntity(), "UTF-8");
            result.put("updated_object", objectId);
            result.put("repository_id", repositoryId);
            result.put("solr_response", body);
            
        } catch (Exception e) {
            status = false;
            errMsg.add("Failed to update Solr: " + e.getMessage());
            e.printStackTrace();
        }

        result = makeResult(status, result, errMsg);
        return result.toString();
    }

    public void setSolrUtil(SolrUtil solrUtil) {
        this.solrUtil = solrUtil;
    }
}
