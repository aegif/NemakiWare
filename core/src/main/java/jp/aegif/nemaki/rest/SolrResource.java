package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGHealthStatus;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.w3c.dom.Node;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Path("/repo/{repositoryId}/search-engine")
public class SolrResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(SolrResource.class);

	private SolrUtil solrUtil;
	private SolrIndexMaintenanceService solrIndexMaintenanceService;
	private RAGIndexMaintenanceService ragIndexMaintenanceService;

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	public void setSolrIndexMaintenanceService(SolrIndexMaintenanceService solrIndexMaintenanceService) {
		this.solrIndexMaintenanceService = solrIndexMaintenanceService;
	}

	public void setRagIndexMaintenanceService(RAGIndexMaintenanceService ragIndexMaintenanceService) {
		this.ragIndexMaintenanceService = ragIndexMaintenanceService;
	}

	/**
	 * Get SolrUtil with fallback to SpringContext lookup.
	 * Jersey may create its own instances instead of using Spring beans,
	 * so we need this fallback for dependency injection.
	 */
	private SolrUtil getSolrUtil() {
		if (solrUtil != null) {
			return solrUtil;
		}
		// Fallback to manual Spring context lookup
		try {
			SolrUtil util = SpringContext.getApplicationContext()
					.getBean("solrUtil", SolrUtil.class);
			if (util != null) {
				log.debug("SolrUtil retrieved from SpringContext successfully");
				return util;
			}
		} catch (Exception e) {
			log.error("Failed to get SolrUtil from SpringContext: " + e.getMessage(), e);
		}
		log.error("SolrUtil is null and SpringContext fallback failed - dependency injection issue");
		return null;
	}

	/**
	 * Get SolrIndexMaintenanceService with fallback to SpringContext lookup.
	 */
	private SolrIndexMaintenanceService getMaintenanceService() {
		if (solrIndexMaintenanceService != null) {
			return solrIndexMaintenanceService;
		}
		try {
			SolrIndexMaintenanceService service = SpringContext.getApplicationContext()
					.getBean("solrIndexMaintenanceService", SolrIndexMaintenanceService.class);
			if (service != null) {
				log.debug("SolrIndexMaintenanceService retrieved from SpringContext successfully");
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get SolrIndexMaintenanceService from SpringContext: " + e.getMessage(), e);
		}
		log.error("SolrIndexMaintenanceService is null and SpringContext fallback failed");
		return null;
	}

	/**
	 * Get RAGIndexMaintenanceService with fallback to SpringContext lookup.
	 */
	private RAGIndexMaintenanceService getRAGMaintenanceService() {
		if (ragIndexMaintenanceService != null) {
			return ragIndexMaintenanceService;
		}
		try {
			RAGIndexMaintenanceService service = SpringContext.getApplicationContext()
					.getBean(RAGIndexMaintenanceService.class);
			if (service != null) {
				log.debug("RAGIndexMaintenanceService retrieved from SpringContext successfully");
				return service;
			}
		} catch (Exception e) {
			log.debug("RAGIndexMaintenanceService not available: " + e.getMessage());
		}
		return null;
	}

	@GET
	@Path("/url")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String url() {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		SolrUtil util = getSolrUtil();
		if (util == null) {
			errMsg.add("Solr utility is not available");
			return makeResult(false, result, errMsg).toJSONString();
		}

		String solrUrl = util.getSolrUrl();
		result.put("url", solrUrl);

		// Output
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@POST
	@Path("/init")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String initialize(@PathParam("repositoryId") String repositoryId, @Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Check admin
		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrUtil util = getSolrUtil();
		if (util == null) {
			errMsg.add("Solr utility is not available");
			return makeResult(false, result, errMsg).toString();
		}

		// Call Solr
		HttpClient httpClient = HttpClientBuilder.create().build();
		String solrUrl = util.getSolrUrl();
		String url = solrUrl + "admin/cores?core=nemaki&action=init&repositoryId=" + repositoryId;
		HttpGet httpGet = new HttpGet(url);
		try {
			String body = httpClient.execute(httpGet, response -> {
				int responseStatus = response.getCode();
				if (HttpStatus.SC_OK != responseStatus) {
					throw new RuntimeException("Solr server connection failed");
				}
				return EntityUtils.toString(response.getEntity(), "UTF-8");
			});
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
	@Path("/reindex")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String reindex(@PathParam("repositoryId") String repositoryId, @Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Check admin
		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		// Use the new SolrIndexMaintenanceService for full reindex
		// This ensures progress tracking works correctly
		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean started = service.startFullReindex(repositoryId);
		if (!started) {
			errMsg.add("Reindex already in progress for this repository");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Full reindex started");
		result.put("repositoryId", repositoryId);
		return makeResult(status, result, errMsg).toString();
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
		String solrUrl = getSolrUtil().getSolrUrl();
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
			String body = httpClient.execute(httpAction, response -> {
				int responseStatus = response.getCode();
				if (HttpStatus.SC_OK != responseStatus) {
					throw new RuntimeException("Solr server connection failed");
				}
				return EntityUtils.toString(response.getEntity(), "UTF-8");
			});
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

	@POST
	@Path("/reindex/folder/{folderId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String reindexFolder(@PathParam("repositoryId") String repositoryId,
			@PathParam("folderId") String folderId,
			@QueryParam("recursive") @DefaultValue("true") boolean recursive,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean started = service.startFolderReindex(repositoryId, folderId, recursive);
		if (!started) {
			errMsg.add("Reindex already in progress for this repository");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Folder reindex started");
		result.put("folderId", folderId);
		result.put("recursive", recursive);
		return makeResult(status, result, errMsg).toString();
	}

	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String getStatus(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		ReindexStatus reindexStatus = service.getReindexStatus(repositoryId);
		result.put("repositoryId", reindexStatus.getRepositoryId());
		result.put("status", reindexStatus.getStatus());
		result.put("totalDocuments", reindexStatus.getTotalDocuments());
		result.put("indexedCount", reindexStatus.getIndexedCount());
		result.put("errorCount", reindexStatus.getErrorCount());
		result.put("silentDropCount", reindexStatus.getSilentDropCount());
		result.put("reindexedCount", reindexStatus.getReindexedCount());
		result.put("startTime", reindexStatus.getStartTime());
		result.put("endTime", reindexStatus.getEndTime());
		result.put("currentFolder", reindexStatus.getCurrentFolder());
		result.put("errorMessage", reindexStatus.getErrorMessage());

		// Always include errors array for consistent API response
		// This ensures UI doesn't need to handle undefined errors field
		List<String> errors = reindexStatus.getErrors();
		JSONArray errorsArray = new JSONArray();
		if (errors != null) {
			errorsArray.addAll(errors);
		}
		result.put("errors", errorsArray);

		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/cancel")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String cancelReindex(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean cancelled = service.cancelReindex(repositoryId);
		result.put("cancelled", cancelled);
		return makeResult(status, result, errMsg).toString();
	}

	@GET
	@Path("/health")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String checkHealth(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		IndexHealthStatus healthStatus = service.checkIndexHealth(repositoryId);
		result.put("repositoryId", healthStatus.getRepositoryId());
		result.put("solrDocumentCount", healthStatus.getSolrDocumentCount());
		result.put("couchDbDocumentCount", healthStatus.getCouchDbDocumentCount());
		result.put("missingInSolr", healthStatus.getMissingInSolr());
		result.put("orphanedInSolr", healthStatus.getOrphanedInSolr());
		result.put("healthy", healthStatus.isHealthy());
		result.put("message", healthStatus.getMessage());
		result.put("checkTime", healthStatus.getCheckTime());

		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/query")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String executeSolrQuery(@PathParam("repositoryId") String repositoryId,
			@FormParam("q") String query,
			@FormParam("start") @DefaultValue("0") int start,
			@FormParam("rows") @DefaultValue("10") int rows,
			@FormParam("sort") String sort,
			@FormParam("fl") String fields,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		SolrQueryResult queryResult = service.executeSolrQuery(repositoryId, query, start, rows, sort, fields);
		
		if (queryResult.getErrorMessage() != null) {
			errMsg.add(queryResult.getErrorMessage());
			return makeResult(false, result, errMsg).toString();
		}

		result.put("numFound", queryResult.getNumFound());
		result.put("start", queryResult.getStart());
		result.put("queryTime", queryResult.getQueryTime());
		
		JSONArray docsArray = new JSONArray();
		if (queryResult.getDocs() != null) {
			for (Map<String, Object> doc : queryResult.getDocs()) {
				JSONObject docObj = new JSONObject();
				docObj.putAll(doc);
				docsArray.add(docObj);
			}
		}
		result.put("docs", docsArray);

		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/reindex/document/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String reindexDocument(@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean success = service.reindexDocument(repositoryId, objectId);
		if (!success) {
			errMsg.add("Failed to reindex document: " + objectId);
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Document reindexed successfully");
		result.put("objectId", objectId);
		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/delete/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String deleteFromIndex(@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean success = service.deleteFromIndex(repositoryId, objectId);
		if (!success) {
			errMsg.add("Failed to delete document from index: " + objectId);
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Document deleted from index successfully");
		result.put("objectId", objectId);
		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/clear")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String clearIndex(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean success = service.clearIndex(repositoryId);
		if (!success) {
			errMsg.add("Failed to clear index");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Index cleared successfully");
		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/optimize")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String optimizeIndex(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		SolrIndexMaintenanceService service = getMaintenanceService();
		if (service == null) {
			errMsg.add("Solr index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean success = service.optimizeIndex(repositoryId);
		if (!success) {
			errMsg.add("Failed to optimize index");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Index optimized successfully");
		return makeResult(status, result, errMsg).toString();
	}

	// ========================================
	// RAG Index Maintenance Endpoints
	// ========================================

	@POST
	@Path("/rag/reindex")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String ragReindex(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null || !service.isRAGEnabled()) {
			errMsg.add("RAG index maintenance service is not available or RAG is disabled");
			return makeResult(false, result, errMsg).toString();
		}

		boolean started = service.startFullRAGReindex(repositoryId);
		if (!started) {
			errMsg.add("RAG reindex already in progress for this repository");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Full RAG reindex started");
		result.put("repositoryId", repositoryId);
		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/rag/reindex/folder/{folderId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String ragReindexFolder(@PathParam("repositoryId") String repositoryId,
			@PathParam("folderId") String folderId,
			@QueryParam("recursive") @DefaultValue("true") boolean recursive,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null || !service.isRAGEnabled()) {
			errMsg.add("RAG index maintenance service is not available or RAG is disabled");
			return makeResult(false, result, errMsg).toString();
		}

		boolean started = service.startFolderRAGReindex(repositoryId, folderId, recursive);
		if (!started) {
			errMsg.add("RAG reindex already in progress for this repository");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "RAG folder reindex started");
		result.put("folderId", folderId);
		result.put("recursive", recursive);
		return makeResult(status, result, errMsg).toString();
	}

	@GET
	@Path("/rag/status")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String getRagStatus(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null) {
			errMsg.add("RAG index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		RAGReindexStatus ragStatus = service.getRAGReindexStatus(repositoryId);
		result.put("repositoryId", ragStatus.getRepositoryId());
		result.put("status", ragStatus.getStatus());
		result.put("totalDocuments", ragStatus.getTotalDocuments());
		result.put("indexedCount", ragStatus.getIndexedCount());
		result.put("skippedCount", ragStatus.getSkippedCount());
		result.put("errorCount", ragStatus.getErrorCount());
		result.put("startTime", ragStatus.getStartTime());
		result.put("endTime", ragStatus.getEndTime());
		result.put("currentDocument", ragStatus.getCurrentDocument());
		result.put("errorMessage", ragStatus.getErrorMessage());

		List<String> errors = ragStatus.getErrors();
		JSONArray errorsArray = new JSONArray();
		if (errors != null) {
			errorsArray.addAll(errors);
		}
		result.put("errors", errorsArray);

		return makeResult(status, result, errMsg).toString();
	}

	@GET
	@Path("/rag/health")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String checkRagHealth(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null) {
			errMsg.add("RAG index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		RAGHealthStatus healthStatus = service.checkRAGHealth(repositoryId);
		result.put("repositoryId", healthStatus.getRepositoryId());
		result.put("ragDocumentCount", healthStatus.getRagDocumentCount());
		result.put("ragChunkCount", healthStatus.getRagChunkCount());
		result.put("eligibleDocuments", healthStatus.getEligibleDocuments());
		result.put("enabled", healthStatus.isEnabled());
		result.put("healthy", healthStatus.isHealthy());
		result.put("message", healthStatus.getMessage());
		result.put("checkTime", healthStatus.getCheckTime());

		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/rag/cancel")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String cancelRagReindex(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null) {
			errMsg.add("RAG index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean cancelled = service.cancelRAGReindex(repositoryId);
		result.put("cancelled", cancelled);
		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/rag/clear")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String clearRagIndex(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null) {
			errMsg.add("RAG index maintenance service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		boolean success = service.clearRAGIndex(repositoryId);
		if (!success) {
			errMsg.add("Failed to clear RAG index");
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "RAG index cleared successfully");
		return makeResult(status, result, errMsg).toString();
	}

	@POST
	@Path("/rag/reindex/document/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String ragReindexDocument(@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		RAGIndexMaintenanceService service = getRAGMaintenanceService();
		if (service == null || !service.isRAGEnabled()) {
			errMsg.add("RAG index maintenance service is not available or RAG is disabled");
			return makeResult(false, result, errMsg).toString();
		}

		boolean success = service.reindexDocument(repositoryId, objectId);
		if (!success) {
			errMsg.add("Failed to reindex document in RAG: " + objectId);
			return makeResult(false, result, errMsg).toString();
		}

		result.put("message", "Document reindexed in RAG successfully");
		result.put("objectId", objectId);
		return makeResult(status, result, errMsg).toString();
	}

}
