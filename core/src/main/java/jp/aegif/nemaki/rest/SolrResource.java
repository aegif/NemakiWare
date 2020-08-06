package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.w3c.dom.Node;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/repo/{repositoryId}/search-engine")
public class SolrResource extends ResourceBase {

	private SolrUtil solrUtil;

	@GET
	@Path("/url")
	@Produces(MediaType.APPLICATION_JSON)
	public String url() {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		String solrUrl = solrUtil.getSolrUrl();

		result.put("url", solrUrl);

		// Output
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@GET
	@Path("/init")
	@Produces(MediaType.APPLICATION_JSON)
	public String initialize(@PathParam("repositoryId") String repositoryId, @Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Check admin
		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		// Call Solr
		HttpClient httpClient = HttpClientBuilder.create().build();
		String solrUrl = solrUtil.getSolrUrl();
		String url = solrUrl + "admin/cores?core=nemaki&action=init&repositoryId=" + repositoryId;
		HttpGet httpGet = new HttpGet(url);
		try {
			HttpResponse response = httpClient.execute(httpGet);
			int responseStatus = response.getStatusLine().getStatusCode();
			if (HttpStatus.SC_OK != responseStatus) {
				throw new Exception("Solr server connection failed");
			}

			String body = EntityUtils.toString(response.getEntity(), "UTF-8");
			// TODO error message
			status = checkSuccess(body);
		} catch (Exception e) {
			status = false;
			// TODO error message
			e.printStackTrace();
		}

		// Output
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@GET
	@Path("/reindex")
	@Produces(MediaType.APPLICATION_JSON)
	public String reindex(@PathParam("repositoryId") String repositoryId, @Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Check admin
		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		// Call Solr
		HttpClient httpClient = HttpClientBuilder.create().build();
		String solrUrl = solrUtil.getSolrUrl();
		String url = solrUrl + "admin/cores?core=nemaki&action=index&tracking=FULL&repositoryId=" + repositoryId;
		HttpGet httpGet = new HttpGet(url);
		try {
			HttpResponse response = httpClient.execute(httpGet);
			int responseStatus = response.getStatusLine().getStatusCode();
			if (HttpStatus.SC_OK != responseStatus) {
				throw new Exception("Solr server connection failed");
			}

			String body = EntityUtils.toString(response.getEntity(), "UTF-8");
			// TODO error message
			status = checkSuccess(body);
		} catch (Exception e) {
			status = false;
			// TODO error message
			e.printStackTrace();
		}

		// Output
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@POST
	@Path("/change_admin_password")
	@Produces(MediaType.APPLICATION_JSON)
	public String changeAdminPassword(@FormParam("repositoryId") String repositoryId,
			@FormParam("password") String password, @FormParam("currentPassword") String currentPassword,
			@Context HttpServletRequest request) {
		JSONObject result = changeAdminPasswordImpl(repositoryId, password, currentPassword, request);
		return result.toString();
	}

	public  JSONObject changeAdminPasswordImpl(String repositoryId, String password, String currentPassword,
			HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Check admin
		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg);
		}

		// Call Solr
		HttpClient httpClient = HttpClientBuilder.create().build();
		String solrUrl = solrUtil.getSolrUrl();
		String url = solrUrl + "admin/cores?core=nemaki&action=CHANGE_PASSWORD&tracking=FULL&repositoryId="
				+ repositoryId + "&password=" + password + "&currentPassword=" + currentPassword;
		HttpGet httpAction = new HttpGet(url);

		/*
		HttpPost httpAction = new HttpPost(url);
		List<BasicNameValuePair> requestParams = new ArrayList<BasicNameValuePair>();
		requestParams.add(new BasicNameValuePair("repositoryId",repositoryId));
		requestParams.add(new BasicNameValuePair("password",password));
		requestParams.add(new BasicNameValuePair("currentPassword",currentPassword));
		httpAction.setEntity(new UrlEncodedFormEntity(requestParams));
		 */

		try {
			HttpResponse response = httpClient.execute(httpAction);
			int responseStatus = response.getStatusLine().getStatusCode();
			if (HttpStatus.SC_OK != responseStatus) {
				throw new Exception("Solr server connection failed");
			}

			String body = EntityUtils.toString(response.getEntity(), "UTF-8");
			// TODO error message
			status = checkSuccess(body);
		} catch (Exception e) {
			status = false;
			// TODO error message
			e.printStackTrace();
		}

		// Output
		return makeResult(status, result, errMsg);
	}

	private boolean checkSuccess(String xml) throws Exception {
		// sanitize
		xml = xml.replace("\n", "");

		// parse
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();

		// traverse
		InputStream bais = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
		Node root = db.parse(bais);
		Node response = root.getFirstChild();
		Node lst = response.getFirstChild();
		Node status = lst.getFirstChild();

		// check
		return "0".equals(status.getTextContent());
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

}