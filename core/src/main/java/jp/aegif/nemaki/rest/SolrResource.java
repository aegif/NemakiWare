package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGHealthStatus;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.User;
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
import java.util.Set;

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

	/**
	 * Get PermissionService from SpringContext.
	 */
	private PermissionService getPermissionService() {
		try {
			return SpringContext.getApplicationContext().getBean("PermissionService", PermissionService.class);
		} catch (Exception e) {
			log.error("Failed to get PermissionService: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get ContentService from SpringContext.
	 */
	private ContentService getContentService() {
		try {
			return SpringContext.getApplicationContext().getBean("ContentService", ContentService.class);
		} catch (Exception e) {
			log.error("Failed to get ContentService: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get PrincipalService from SpringContext.
	 */
	private PrincipalService getPrincipalService() {
		try {
			return SpringContext.getApplicationContext().getBean("PrincipalService", PrincipalService.class);
		} catch (Exception e) {
			log.error("Failed to get PrincipalService: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get DiscoveryService from SpringContext.
	 */
	private DiscoveryService getDiscoveryService() {
		try {
			return SpringContext.getApplicationContext().getBean("DiscoveryService", DiscoveryService.class);
		} catch (Exception e) {
			log.error("Failed to get DiscoveryService: " + e.getMessage());
			return null;
		}
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
					throw new RuntimeException("Solr server connection failed with status: " + responseStatus);
				}
				return EntityUtils.toString(response.getEntity(), "UTF-8");
			});
			status = checkSuccess(body);
			if (!status) {
				// Log with Solr response details for debugging
				String sanitizedBody = sanitizeSolrResponse(body);
				log.error("Solr init operation failed for repository: " + repositoryId + ", response: " + sanitizedBody);
				// Client gets safe message only
				errMsg.add("Solr init operation failed for repository: " + repositoryId);
			}
		} catch (Exception e) {
			status = false;
			// Log full details for debugging
			log.error("Solr init operation error for repository: " + repositoryId, e);
			// Client gets safe, generic message (no internal details)
			errMsg.add("Solr init operation failed. Please check server logs for details.");
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
					throw new RuntimeException("Solr server connection failed with status: " + responseStatus);
				}
				return EntityUtils.toString(response.getEntity(), "UTF-8");
			});
			status = checkSuccess(body);
			if (!status) {
				// Log with Solr response details for debugging
				String sanitizedBody = sanitizeSolrResponse(body);
				log.error("Solr password change operation failed for repository: " + repositoryId + ", response: " + sanitizedBody);
				// Client gets safe message only
				errMsg.add("Solr password change operation failed for repository: " + repositoryId);
			}
		} catch (Exception e) {
			status = false;
			// Log full details for debugging
			log.error("Solr password change operation error for repository: " + repositoryId, e);
			// Client gets safe, generic message (no internal details)
			errMsg.add("Solr password change operation failed. Please check server logs for details.");
		}

		// Output
		return makeResult(status, result, errMsg);
	}

	/**
	 * Prepare Solr response for logging.
	 * Truncates to reasonable length and removes newlines for single-line log readability.
	 * Note: This does not remove sensitive information - Solr responses should not contain secrets.
	 */
	private String sanitizeSolrResponse(String response) {
		if (response == null) {
			return "[null response]";
		}
		// Truncate very long responses
		final int maxLength = 500;
		String sanitized = response.length() > maxLength
			? response.substring(0, maxLength) + "...[truncated]"
			: response;
		// Remove newlines for single-line logging
		sanitized = sanitized.replace("\n", " ").replace("\r", " ");
		return sanitized;
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

	/**
	 * Execute a raw Solr query with optional ACL filtering simulation.
	 *
	 * @param repositoryId Repository ID
	 * @param query Solr query string
	 * @param start Start offset for pagination
	 * @param rows Number of rows to return
	 * @param sort Sort specification
	 * @param fields Fields to return (fl parameter)
	 * @param simulateAsUserId (Optional) Simulate query results as this user for ACL filtering
	 * @param request HTTP request for admin check
	 * @return JSON response with search results
	 */
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
			@FormParam("simulateAsUserId") String simulateAsUserId,
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

		// If simulateAsUserId is provided, validate the user exists
		if (simulateAsUserId != null && !simulateAsUserId.trim().isEmpty()) {
			PrincipalService principalService = getPrincipalService();
			if (principalService == null) {
				errMsg.add("Principal service is not available for user simulation");
				return makeResult(false, result, errMsg).toString();
			}
			User user = principalService.getUserById(repositoryId, simulateAsUserId);
			if (user == null) {
				errMsg.add("Simulated user not found: " + simulateAsUserId);
				return makeResult(false, result, errMsg).toString();
			}
			result.put("simulatedAsUser", simulateAsUserId);
		}

		SolrQueryResult queryResult = service.executeSolrQuery(repositoryId, query, start, rows, sort, fields);

		if (queryResult.getErrorMessage() != null) {
			errMsg.add(queryResult.getErrorMessage());
			return makeResult(false, result, errMsg).toString();
		}

		// Apply ACL filtering if simulation user is specified
		List<Map<String, Object>> docs = queryResult.getDocs();
		int originalCount = docs != null ? docs.size() : 0;
		int filteredCount = 0;

		int aclCheckErrors = 0;
		if (simulateAsUserId != null && !simulateAsUserId.trim().isEmpty() && docs != null) {
			// Performance warning for large result sets
			if (docs.size() > 100) {
				log.warn("ACL filtering for large result set (" + docs.size() + " documents) may be slow");
			}

			PermissionService permissionService = getPermissionService();
			ContentService contentService = getContentService();
			PrincipalService principalService = getPrincipalService();

			if (permissionService != null && contentService != null && principalService != null) {
				// Get the simulated user's groups (with null safety)
				Set<String> userGroups;
				try {
					Set<String> groupIds = principalService.getGroupIdsContainingUser(repositoryId, simulateAsUserId);
					userGroups = groupIds != null ? groupIds : java.util.Collections.emptySet();
				} catch (Exception e) {
					log.warn("Failed to get groups for user '" + simulateAsUserId + "': " + e.getMessage() + ". Using empty group set.");
					userGroups = java.util.Collections.emptySet();
				}

				List<Map<String, Object>> filteredDocs = new java.util.ArrayList<>();
				for (Map<String, Object> doc : docs) {
					String objectId = (String) doc.get("id");
					if (objectId == null) {
						objectId = (String) doc.get("object_id");
					}
					if (objectId != null) {
						try {
							Content content = contentService.getContent(repositoryId, objectId);
							if (content != null) {
								jp.aegif.nemaki.model.Acl acl = contentService.calculateAcl(repositoryId, content);
								boolean canRead = permissionService.checkPermissionWithGivenList(
									new SystemCallContext(repositoryId),
									repositoryId,
									PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
									acl,
									content.getType(),
									content,
									simulateAsUserId,
									userGroups
								);
								if (canRead) {
									filteredDocs.add(doc);
								}
							}
						} catch (Exception e) {
							aclCheckErrors++;
							log.warn("ACL check failed for document " + objectId + ": " + e.getMessage());
						}
					}
				}
				docs = filteredDocs;
				filteredCount = originalCount - filteredDocs.size();
			}
		}

		// Note: numFound is the raw Solr result count BEFORE ACL filtering.
		// When simulateAsUserId is specified, visibleCount shows the actual count
		// after ACL filtering, and aclFilteredCount shows how many were removed.
		result.put("numFound", queryResult.getNumFound());
		result.put("start", queryResult.getStart());
		result.put("queryTime", queryResult.getQueryTime());
		if (simulateAsUserId != null && !simulateAsUserId.trim().isEmpty()) {
			result.put("aclFilteredCount", filteredCount);
			result.put("visibleCount", docs != null ? docs.size() : 0);
			result.put("simulatedAsUser", simulateAsUserId);
			if (aclCheckErrors > 0) {
				result.put("aclCheckErrors", aclCheckErrors);
			}
		}

		JSONArray docsArray = new JSONArray();
		if (docs != null) {
			for (Map<String, Object> doc : docs) {
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

	// ========================================
	// CMIS Query Simulation Endpoint
	// ========================================

	/**
	 * Execute a CMIS SQL query with user permission simulation.
	 * This endpoint executes standard CMIS queries with automatic ACL filtering
	 * based on the specified user's permissions.
	 *
	 * @param repositoryId Repository ID
	 * @param statement CMIS SQL query statement (e.g., "SELECT * FROM cmis:document WHERE cmis:name LIKE '%test%'")
	 * @param simulateAsUserId User ID to simulate permissions for (admin only feature)
	 * @param maxItems Maximum number of items to return
	 * @param skipCount Number of items to skip for pagination
	 * @param request HTTP request for admin check
	 * @return JSON response with search results filtered by user permissions
	 */
	@POST
	@Path("/cmis-query")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String executeCmisQuery(@PathParam("repositoryId") String repositoryId,
			@FormParam("statement") String statement,
			@FormParam("simulateAsUserId") String simulateAsUserId,
			@FormParam("maxItems") @DefaultValue("100") int maxItems,
			@FormParam("skipCount") @DefaultValue("0") int skipCount,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		if (statement == null || statement.trim().isEmpty()) {
			errMsg.add("Query statement is required");
			return makeResult(false, result, errMsg).toString();
		}

		DiscoveryService discoveryService = getDiscoveryService();
		if (discoveryService == null) {
			errMsg.add("Discovery service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		PrincipalService principalService = getPrincipalService();
		if (principalService == null) {
			errMsg.add("Principal service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		// Validate simulated user exists (or use admin if not specified)
		String effectiveUserId = simulateAsUserId;
		boolean usingDefaultUser = false;
		if (effectiveUserId == null || effectiveUserId.trim().isEmpty()) {
			effectiveUserId = "admin"; // Default to admin when not specified
			usingDefaultUser = true;
			log.info("No simulateAsUserId specified for CMIS query, using admin by default");
		}

		User user = principalService.getUserById(repositoryId, effectiveUserId);
		if (user == null) {
			errMsg.add("Simulated user not found: " + effectiveUserId);
			return makeResult(false, result, errMsg).toString();
		}

		try {
			// Create a call context with the simulated user
			org.apache.chemistry.opencmis.server.impl.CallContextImpl callContext =
				new org.apache.chemistry.opencmis.server.impl.CallContextImpl(
					null,
					org.apache.chemistry.opencmis.commons.enums.CmisVersion.CMIS_1_1,
					repositoryId,
					null, null, null, null, null
				);
			callContext.put(org.apache.chemistry.opencmis.commons.server.CallContext.USERNAME, effectiveUserId);

			// Execute CMIS query
			org.apache.chemistry.opencmis.commons.data.ObjectList queryResult = discoveryService.query(
				callContext,
				repositoryId,
				statement,
				false,   // searchAllVersions
				true,    // includeAllowableActions
				org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
				null,    // renditionFilter
				java.math.BigInteger.valueOf(maxItems),
				java.math.BigInteger.valueOf(skipCount),
				null     // extension
			);

			result.put("simulatedAsUser", effectiveUserId);
			result.put("statement", statement);
			result.put("numItems", queryResult.getNumItems() != null ? queryResult.getNumItems().intValue() : 0);
			result.put("hasMoreItems", queryResult.hasMoreItems());
			if (usingDefaultUser) {
				result.put("warning", "No simulateAsUserId specified, using admin by default");
			}

			JSONArray docsArray = new JSONArray();
			if (queryResult.getObjects() != null) {
				for (org.apache.chemistry.opencmis.commons.data.ObjectData obj : queryResult.getObjects()) {
					JSONObject docObj = new JSONObject();
					if (obj.getProperties() != null && obj.getProperties().getProperties() != null) {
						for (Map.Entry<String, org.apache.chemistry.opencmis.commons.data.PropertyData<?>> entry :
								obj.getProperties().getProperties().entrySet()) {
							org.apache.chemistry.opencmis.commons.data.PropertyData<?> prop = entry.getValue();
							if (prop.getValues() != null && !prop.getValues().isEmpty()) {
								if (prop.getValues().size() == 1) {
									docObj.put(entry.getKey(), prop.getFirstValue());
								} else {
									JSONArray values = new JSONArray();
									values.addAll(prop.getValues());
									docObj.put(entry.getKey(), values);
								}
							}
						}
					}
					docsArray.add(docObj);
				}
			}
			result.put("objects", docsArray);

		} catch (Exception e) {
			log.error("CMIS query execution failed: " + e.getMessage(), e);
			errMsg.add("Query failed: " + e.getMessage());
			return makeResult(false, result, errMsg).toString();
		}

		return makeResult(status, result, errMsg).toString();
	}

	/**
	 * Get list of users for simulation dropdown.
	 * Returns all users in the repository that can be used for permission simulation.
	 */
	@GET
	@Path("/users")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String getUsers(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest request) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		if (!checkAdmin(errMsg, request)) {
			return makeResult(status, result, errMsg).toString();
		}

		PrincipalService principalService = getPrincipalService();
		if (principalService == null) {
			errMsg.add("Principal service is not available");
			return makeResult(false, result, errMsg).toString();
		}

		List<User> users = principalService.getUsers(repositoryId);
		JSONArray usersArray = new JSONArray();
		if (users != null) {
			for (User user : users) {
				JSONObject userObj = new JSONObject();
				userObj.put("userId", user.getUserId());
				userObj.put("userName", user.getName());
				usersArray.add(userObj);
			}
		}
		result.put("users", usersArray);

		return makeResult(status, result, errMsg).toString();
	}

}
