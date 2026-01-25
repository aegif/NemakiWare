package jp.aegif.nemaki.rest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.w3c.dom.Node;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

@Path("/all/search-engine")
public class SolrAllResource extends ResourceBase {
	
	private static final Log log = LogFactory.getLog(SolrAllResource.class);
	
	@Context private HttpServletRequest servletRequest;
	
	private SolrUtil solrUtil;

	public SolrAllResource() {
		super();
	}

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
	public String initialize(@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//Check admin
		if(!checkAdmin(errMsg, request)){
			return makeResult(status, result, errMsg).toString();
		}
		
		//Call Solr
		HttpClient httpClient = HttpClientBuilder.create().build();
		String solrUrl = solrUtil.getSolrUrl();
		String url = solrUrl + "admin/cores?core=nemaki&action=init";
		HttpGet httpGet = new HttpGet(url);
		try {
			String body = httpClient.execute(httpGet, response -> {
				int responseStatus = response.getCode();
				if(HttpStatus.SC_OK != responseStatus){
					throw new RuntimeException("Solr server connection failed");
				}
				return EntityUtils.toString(response.getEntity(), "UTF-8");
			});
			if(checkSuccess(body)){
				status = true;
			}else{
				status = false;
				// Include truncated response for debugging (limit to 200 chars to avoid huge messages)
				String truncatedBody = body.length() > 200 ? body.substring(0, 200) + "..." : body;
				errMsg.add("Solr initialization returned non-success status");
				log.warn("Solr init request returned non-success status. Response: " + truncatedBody);
			}
		} catch (Exception e) {
			status = false;
			errMsg.add("Solr initialization failed: " + e.getMessage());
			log.error("Solr init request failed", e);
		}

		// Output
		result = makeResult(status, result, errMsg);
		return result.toString();
	}
	
	@GET
	@Path("/reindex")
	@Produces(MediaType.APPLICATION_JSON)
	public String reindex(@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//Check admin
		if(!checkAdmin(errMsg, request)){
			return makeResult(status, result, errMsg).toString();
		}
		
		try {
			// NEW APPROACH: Use SolrUtil to reindex all documents from CouchDB
			// This approach works with Solr 9.x and uses our enhanced indexDocument() method
			
			// Clear existing Solr index first
			try {
				HttpClient httpClient = HttpClientBuilder.create().build();
				String solrUrl = solrUtil.getSolrUrl();
				String clearUrl = solrUrl + "/update?commit=true";
				org.apache.hc.client5.http.classic.methods.HttpPost clearPost = new org.apache.hc.client5.http.classic.methods.HttpPost(clearUrl);
				clearPost.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity("<delete><query>*:*</query></delete>", java.nio.charset.StandardCharsets.UTF_8));
				clearPost.setHeader("Content-Type", "application/xml");
				
				httpClient.execute(clearPost, response -> {
					int responseStatus = response.getCode();
					if(HttpStatus.SC_OK != responseStatus){
						throw new RuntimeException("Solr clear failed with status: " + responseStatus);
					}
					return EntityUtils.toString(response.getEntity(), "UTF-8");
				});
				
				result.put("message", "Solr index cleared and reindexing started");
				if (log.isDebugEnabled()) {
					log.debug("Solr index cleared, reindexing will occur automatically via SolrUtil");
				}
				
			} catch (Exception e) {
				status = false;
				errMsg.add("Failed to clear Solr index: " + e.getMessage());
				e.printStackTrace();
			}
			
		} catch (Exception e) {
			status = false;
			errMsg.add("Reindex failed: " + e.getMessage());
			e.printStackTrace();
		}

		// Output
		result = makeResult(status, result, errMsg);
		return result.toString();
	}
	
	
	private boolean checkSuccess(String xml) throws Exception{
		//sanitize
		xml = xml.replace("\n", "");
		
		//parse
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance(); 
		DocumentBuilder db = dbf.newDocumentBuilder();
		
		//traverse
		InputStream bais = new ByteArrayInputStream(xml.getBytes("utf-8")); 
		Node root = db.parse(bais);
		Node response = root.getFirstChild();
		Node lst = response.getFirstChild();
		Node status = lst.getFirstChild();
		
		//check
		return "0".equals(status.getTextContent());
	}
	
	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}
	
}
