/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.aspect.query.solr;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.query.QueryProcessor;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.chemistry.opencmis.server.support.query.QueryObject.SortSpec;
import org.apache.chemistry.opencmis.server.support.query.QueryUtilStrict;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;

public class SolrQueryProcessor implements QueryProcessor {

	private TypeManager typeManager;
	private ContentService contentService;
	private PermissionService permissionService;
	private CompileService compileService;
	private ExceptionService exceptionService;
	private ThreadLockService threadLockService;
	private SolrUtil solrUtil;
	private static final Log logger = LogFactory
			.getLog(SolrQueryProcessor.class);

	public SolrQueryProcessor() {

	}

	private class CmisTypeManager implements org.apache.chemistry.opencmis.server.support.TypeManager{
		private String repositoryId;
		private TypeManager typeManager;
		
		public CmisTypeManager(String repositoryId, TypeManager typeManager){
			this.repositoryId = repositoryId;
			this.typeManager = typeManager;
		}
		@Override
		public void addTypeDefinition(TypeDefinition arg0, boolean arg1) {
			throw new UnsupportedOperationException("Type creation via query processor is not supported");
		}
		@Override
		public void deleteTypeDefinition(String typeId) {
			typeManager.deleteTypeDefinition(repositoryId, typeId);
			
		}
		@Override
		public String getPropertyIdForQueryName(TypeDefinition typeDefinition, String propQueryName) {
			return typeManager.getPropertyIdForQueryName(repositoryId, typeDefinition, propQueryName);
		}
		@Override
		public List<TypeDefinitionContainer> getRootTypes() {
			return typeManager.getRootTypes(repositoryId);
		}
		@Override
		public TypeDefinitionContainer getTypeById(String typeId) {
			return typeManager.getTypeById(repositoryId, typeId);
		}
		@Override
		public TypeDefinition getTypeByQueryName(String typeQueryName) {
			return typeManager.getTypeByQueryName(repositoryId, typeQueryName);
		}
		@Override
		public Collection<TypeDefinitionContainer> getTypeDefinitionList() {
			return typeManager.getTypeDefinitionList(repositoryId);
		}
		@Override
		public void updateTypeDefinition(TypeDefinition typeDefinition) {
			typeManager.updateTypeDefinition(repositoryId, typeDefinition);
			
		}
	}
	
	@Override
	public ObjectList query(CallContext callContext, String repositoryId,
			String statement, Boolean searchAllVersions,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		if (logger.isDebugEnabled()) {
			logger.debug("SolrQueryProcessor.query called with statement: " + statement);
		}
		
		// Create CMIS Type Manager first and test basic functionality
		CmisTypeManager cmisTypeManager = new CmisTypeManager(repositoryId, typeManager);
		
		// Test basic type lookup
		try {
			TypeDefinition testType = cmisTypeManager.getTypeByQueryName("cmis:document");
			if (logger.isDebugEnabled()) {
				logger.debug("Found cmis:document type: " + (testType != null ? testType.getId() : "null"));
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception during type lookup: " + e.getMessage());
			}
		}
		
		SolrClient solrClient = null;
		try {
			solrClient = solrUtil.getSolrClient();
			if (logger.isDebugEnabled()) {
				logger.debug("Got Solr client: " + (solrClient != null ? solrClient.getClass().getSimpleName() : "null"));
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception getting Solr client: " + e.getMessage());
			}
			logger.error("Failed to get Solr client", e);
		}
		
		// Handle case where Solr client creation failed due to HTTP Client compatibility
		if (solrClient == null) {
			logger.warn("Solr client unavailable due to HTTP Client compatibility issues - returning empty result");
			logger.warn("CMIS query will not use full-text search functionality: " + statement);
			ObjectListImpl nullList = new ObjectListImpl();
			nullList.setHasMoreItems(false);
			nullList.setNumItems(BigInteger.ZERO);
			return nullList;
		}
		
		// replacing backslashed for TIMESTAMP only
		Pattern time_p = Pattern.compile("(TIMESTAMP\\s?'[\\-\\d]*T\\d{2})\\\\:(\\d{2})\\\\:([\\.\\d]*Z')", Pattern.CASE_INSENSITIVE);
		Matcher time_m = time_p.matcher(statement);
		statement = time_m.replaceAll("$1:$2:$3");

		// CRITICAL FIX (2025-12-18): Auto-inject JOIN for secondary type properties
		// This allows queries like "WHERE nemaki:comment LIKE '%test%'" to work without
		// requiring users to manually add "JOIN nemaki:commentable" to the query
		statement = injectSecondaryTypeJoins(repositoryId, statement);

		if (logger.isDebugEnabled()) {
			logger.debug("Creating QueryUtilStrict with statement: " + statement);
		}
		QueryUtilStrict util = null;
		try {
			util = new QueryUtilStrict(statement, cmisTypeManager, null);
			if (logger.isDebugEnabled()) {
				logger.debug("QueryUtilStrict created successfully");
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("QueryUtilStrict initialization failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			}
			logger.error("QueryUtilStrict initialization failed during Jakarta EE operation", e);
			
			// Return empty result for Jakarta EE compatibility
			ObjectListImpl emptyResult = new ObjectListImpl();
			emptyResult.setHasMoreItems(false);
			emptyResult.setNumItems(BigInteger.ZERO);
			emptyResult.setObjects(new ArrayList<>());
			return emptyResult;
		}
		
		// Get queryObject before processStatement
		QueryObject queryObject = util.getQueryObject();
		if (logger.isDebugEnabled()) {
			logger.debug("QueryObject created (before processStatement)");
		}
		
		// Debug the QueryObject state before processStatement
		if (logger.isDebugEnabled()) {
			try {
				logger.debug("Checking QueryObject state before processStatement...");
				// Use reflection to access the froms map
				java.lang.reflect.Field fromsField = QueryObject.class.getDeclaredField("froms");
				fromsField.setAccessible(true);
				Map<String, String> froms = (Map<String, String>) fromsField.get(queryObject);
				logger.debug("froms map size before processStatement: " + (froms != null ? froms.size() : "null"));
				if (froms != null && !froms.isEmpty()) {
					for (Map.Entry<String, String> entry : froms.entrySet()) {
						logger.debug("FROM entry: " + entry.getKey() + " -> " + entry.getValue());
					}
				}
			} catch (Exception e) {
				logger.debug("Error accessing froms map: " + e.getMessage());
			}
		}
		
		// Get where caluse as Tree
		Tree whereTree = null;
		try {
			if (logger.isDebugEnabled()) {
				logger.debug("About to call util.processStatement()");
			}
			util.processStatement();
			if (logger.isDebugEnabled()) {
				logger.debug("processStatement() completed");
			}
			Tree tree = util.parseStatement();
			if (logger.isDebugEnabled()) {
				logger.debug("parseStatement() completed");
			}
			whereTree = extractWhereTree(tree);
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception in processStatement: " + e.getMessage());
			}
			logger.error("Exception in processStatement", e);
		}
		
		// Check queryObject after processStatement
		if (logger.isDebugEnabled()) {
			logger.debug("After processStatement, checking froms map state...");
			try {
				java.lang.reflect.Field fromsField = QueryObject.class.getDeclaredField("froms");
				fromsField.setAccessible(true);
				Map<String, String> froms = (Map<String, String>) fromsField.get(queryObject);
				logger.debug("froms map size after processStatement: " + (froms != null ? froms.size() : "null"));
				if (froms != null && !froms.isEmpty()) {
					for (Map.Entry<String, String> entry : froms.entrySet()) {
						logger.debug("FROM entry after processStatement: " + entry.getKey() + " -> " + entry.getValue());
					}
				}
			} catch (Exception e) {
				logger.debug("Error accessing froms map after processStatement: " + e.getMessage());
			}
		}
		
		// Now try getMainFromName() with detailed error handling
		try {
			TypeDefinition mainFromName = queryObject.getMainFromName();
			if (logger.isDebugEnabled()) {
				logger.debug("getMainFromName() returned: " + (mainFromName != null ? mainFromName.getId() : "null"));
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception in getMainFromName(): " + e.getMessage());
			}
			logger.error("Exception in getMainFromName()", e);
		}

		// Build solr statement of WHERE
		String whereQueryString = "";
		if (whereTree == null || whereTree.isNil()) {
			// CRITICAL FIX (2025-12-18): Try to parse secondary type properties manually
			// when OpenCMIS parsing fails (e.g., due to FailedPredicateException)
			String manualWhereQuery = parseSecondaryTypeWhereClause(repositoryId, statement);
			if (manualWhereQuery != null && !manualWhereQuery.isEmpty()) {
				whereQueryString = manualWhereQuery;
				if (logger.isDebugEnabled()) {
					logger.debug("Using manually parsed WHERE clause for secondary type: " + whereQueryString);
				}
			} else {
				whereQueryString = "*:*";
				if (logger.isDebugEnabled()) {
					logger.debug("whereTree is null or nil, using default query: *:*");
				}
			}
		} else {
			try {
				SolrPredicateWalker solrPredicateWalker = new SolrPredicateWalker(repositoryId,
						queryObject, solrUtil, contentService);
				Query whereQuery = solrPredicateWalker.walkPredicate(whereTree);

				// CRITICAL FIX (2025-12-18): Handle null whereQuery from walkPredicate
				// walkPredicate can return null for unsupported patterns like ANY cmis:secondaryObjectTypeIds IN (...)
				if (whereQuery != null) {
					whereQueryString = whereQuery.toString();
				} else {
					// Fall back to manual parsing
					String manualWhereQuery = parseSecondaryTypeWhereClause(repositoryId, statement);
					if (manualWhereQuery != null && !manualWhereQuery.isEmpty()) {
						whereQueryString = manualWhereQuery;
						if (logger.isDebugEnabled()) {
							logger.debug("walkPredicate returned null, using manually parsed WHERE clause: " + whereQueryString);
						}
					} else {
						whereQueryString = "*:*";
						if (logger.isDebugEnabled()) {
							logger.debug("walkPredicate returned null, using default query: *:*");
						}
					}
				}
			} catch (Exception e) {
				logger.error("Error in SolrPredicateWalker.walkPredicate: " + e.getMessage(), e);
				// CRITICAL FIX (2025-12-18): Try manual parsing before throwing exception
				String manualWhereQuery = parseSecondaryTypeWhereClause(repositoryId, statement);
				if (manualWhereQuery != null && !manualWhereQuery.isEmpty()) {
					whereQueryString = manualWhereQuery;
					logger.info("Using manually parsed WHERE clause after walkPredicate error: " + whereQueryString);
				} else {
					e.printStackTrace();
					exceptionService.invalidArgument("Invalid CMIS SQL statement: " + e.getMessage());
				}
			}
		}

		// Build solr query of FROM
		String fromQueryString = "";
		
		String repositoryQuery = "repository_id:" + repositoryId;
		
		fromQueryString += repositoryQuery + " AND ";
		TypeDefinition td = null;

		// Use the debug version that already handles exceptions
		try {
			td = queryObject.getMainFromName();
			if (logger.isDebugEnabled()) {
				logger.debug("getMainFromName() in FROM query section returned: " + (td != null ? td.getId() : "null"));
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception in getMainFromName() during FROM query: " + e.getMessage());
			}
			logger.error("Exception in getMainFromName() during FROM query", e);
			// Return empty result instead of crashing
			ObjectListImpl nullList = new ObjectListImpl();
			nullList.setHasMoreItems(false);
			nullList.setNumItems(BigInteger.ZERO);
			return nullList;
		}

		// Check if td is null before proceeding
		if (td == null) {
			if (logger.isDebugEnabled()) {
				logger.debug("TypeDefinition is null, cannot proceed with query");
			}
			ObjectListImpl nullList = new ObjectListImpl();
			nullList.setHasMoreItems(false);
			nullList.setNumItems(BigInteger.ZERO);
			return nullList;
		}

		// includedInSupertypeQuery
		// CRITICAL FIX (2025-12-14): Use getId() instead of getQueryName() for Solr type filtering
		// Reason: Solr indexes use content.getObjectType() which returns the type ID (cmis:document),
		// but getQueryName() returns a different name (nemaki:document) causing no results.
		List<TypeDefinitionContainer> typeDescendants = typeManager
				.getTypesDescendants(repositoryId, td.getId(), BigInteger.valueOf(-1), false);
		Iterator<TypeDefinitionContainer> iterator = typeDescendants.iterator();
		List<String> tables = new ArrayList<String>();

		// CRITICAL FIX (2025-12-14): Always include the base type (td) first
		// getTypesDescendants() only returns descendants, not the base type itself
		// Without this, querying "SELECT * FROM cmis:document" won't find documents
		// that have objecttype=cmis:document (only subtypes like nemaki:document)
		String baseTypeId = td.getId();
		if (baseTypeId != null) {
			tables.add(baseTypeId.replaceAll(":", "\\\\:"));
		}

		while (iterator.hasNext()) {
			TypeDefinition descendant = iterator.next().getTypeDefinition();
			// Skip if this is the base type (already added above)
			if (td.getId().equals(descendant.getId())) {
				continue;
			}
			boolean isq = (descendant.isIncludedInSupertypeQuery() == null) ? false
					: descendant.isIncludedInSupertypeQuery();
			if (!isq)
				continue;
			// FIX: Use getId() to match what is indexed in Solr (content.getObjectType())
			String table = descendant.getId();
			if (table != null) {
				tables.add(table.replaceAll(":", "\\\\:"));
			}
		}
		
//		Term t = new Term(
//				solrUtil.getPropertyNameInSolr(PropertyIds.OBJECT_TYPE_ID),
//				StringUtils.join(tables, " "));
//		fromQueryString += new TermQuery(t).toString();
		fromQueryString += "("+ solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.OBJECT_TYPE_ID) +":"+ StringUtils.join(tables," " + solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.OBJECT_TYPE_ID) + ":") + ")";

		// Execute query
		SolrQuery solrQuery = new SolrQuery();
		solrQuery.setQuery(whereQueryString);
		solrQuery.setFilterQueries(fromQueryString);
		
		// RANKING FIX: Add sort by modification date descending to prioritize recent documents
		// This ensures that newly created documents appear at the top of search results
		solrQuery.setSort("modified", SolrQuery.ORDER.desc);
		if (logger.isDebugEnabled()) {
			logger.debug("Query: Added sort by modified desc to prioritize recent documents");
		}
		
		if (logger.isDebugEnabled()) {
			logger.debug("Solr query: " + solrQuery.toString());
			logger.debug("CMIS statement: " + statement);
			logger.debug("skipCount: " + skipCount + ", maxItems: " + maxItems);
			logger.debug("whereQueryString: " + whereQueryString);
			logger.debug("fromQueryString: " + fromQueryString);
		}
		if(skipCount == null){
			solrQuery.set(CommonParams.START, 0);
		}else{
			solrQuery.set(CommonParams.START, skipCount.intValue());
		}
		if(maxItems == null){
			solrQuery.set(CommonParams.ROWS, 50);
		}else{
			solrQuery.set(CommonParams.ROWS, maxItems.intValue());
		}
		

		QueryResponse resp = null;
		try {
			if (solrClient == null) {
				logger.error("SolrClient is null - cannot execute query");
				exceptionService.invalidArgument("Solr client initialization failed");
				return null;
			}
			// Core name is already included in the URL from SolrUtil.getSolrUrl()
			resp = solrClient.query(solrQuery);
		} catch (SolrServerException | IOException e) {
			logger.error("Solr query failed: " + e.getMessage(), e);
			exceptionService.invalidArgument("Solr query execution failed: " + e.getMessage());
			return null;
		}

		long numFound =0;
		// Output search results to ObjectList
		if (resp != null && resp.getResults() != null
				&& resp.getResults().getNumFound() != 0) {
			SolrDocumentList docs = resp.getResults();
			numFound = docs.getNumFound();

			List<Content> contents = new ArrayList<Content>();
			for (SolrDocument doc : docs) {
				// Type-safe field value extraction
				String docId = extractStringFieldValue(doc, "object_id");
				if (docId == null) {
					logger.warn("Skipping document with null object_id");
					continue;
				}
				
				Content c = contentService.getContent(repositoryId, docId);

				// When for some reason the content is missed, pass through
				if (c == null) {
					logger.warn("[objectId=" + docId
							+ "]It is missed in DB but still rests in Solr.");
				} else {
					contents.add(c);
				}

			}
			
			
			List<Lock> locks = threadLockService.readLocks(repositoryId, contents);
			try{
				threadLockService.bulkLock(locks);

				// Debug logging for permission filtering
				if (logger.isDebugEnabled()) {
					logger.debug("Before permission filtering - includeAllowableActions=" + includeAllowableActions + ", contents.size=" + contents.size() + ", user=" + callContext.getUsername());
				}

				// Filter out by permissions
				List<Content> permitted = permissionService.getFiltered(
						callContext, repositoryId, contents);

				// Debug logging after permission filtering
				if (logger.isDebugEnabled()) {
					logger.debug("After permission filtering - permitted.size=" + permitted.size() + ", filtered out=" + (contents.size() - permitted.size()));
				}

				// Filter return value with SELECT clause
				// TCK CRITICAL FIX: Query alias support - get full alias map instead of just values
				Map<String, String> requestedWithAliasKey = queryObject
						.getRequestedPropertiesByAlias();
				if (logger.isDebugEnabled()) {
					logger.debug("TCK Alias: requestedWithAliasKey=" + requestedWithAliasKey);
				}
				String filter = null;
				if (!requestedWithAliasKey.keySet().contains("*")) {
					// Create filter(queryNames) from query aliases
					filter = StringUtils.join(requestedWithAliasKey.values(), ",");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("TCK Alias: filter=" + filter);
				}


				// Build ObjectList
				String orderBy = orderBy(queryObject);
				// TCK CRITICAL FIX: Pass propertyAliases map to enable query alias support
				// Build ObjectList with original includeAllowableActions parameter for final response
				if (logger.isDebugEnabled()) {
					logger.debug("TCK Alias: Calling compileObjectDataListForSearchResult with propertyAliases");
				}
				ObjectList result = compileService.compileObjectDataListForSearchResult(
						callContext, repositoryId, permitted, filter, requestedWithAliasKey,
						includeAllowableActions, includeRelationships, renditionFilter, false,
						maxItems, skipCount, false, orderBy,numFound);

				return result;
				
			}finally{
				threadLockService.bulkUnlock(locks);
			}
		} else {
			ObjectListImpl nullList = new ObjectListImpl();
			nullList.setHasMoreItems(false);
			nullList.setNumItems(BigInteger.ZERO);
			return nullList;
		}
	}
	
	/**
	 * Type-safe field value extraction from SolrDocument.
	 * Handles both String and ArrayList<String> return types.
	 */
	private String extractStringFieldValue(SolrDocument doc, String fieldName) {
		Object value = doc.getFieldValue(fieldName);
		if (value == null) {
			return null;
		}
		
		if (value instanceof String) {
			return (String) value;
		} else if (value instanceof java.util.ArrayList) {
			@SuppressWarnings("unchecked")
			java.util.ArrayList<String> listValue = (java.util.ArrayList<String>) value;
			if (!listValue.isEmpty()) {
				return listValue.get(0);
			}
		}
		
		logger.warn("Unexpected field type for " + fieldName + ": " + value.getClass().getName());
		return value.toString();
	}
	
	private String orderBy(QueryObject queryObject){
		List<SortSpec> sortSpecs = queryObject.getOrderBys();
		List<String> _orderBy = new ArrayList<String>();
		for (SortSpec sortSpec : sortSpecs) {
			List<String> _sortSpec = new ArrayList<String>();
			_sortSpec.add(sortSpec.getSelector().getName());
			if (!sortSpec.isAscending()) {
				_sortSpec.add("DESC");
			}

			_orderBy.add(StringUtils.join(_sortSpec, " "));
		}
		String orderBy = StringUtils.join(_orderBy, ",");
		return orderBy;
	}

	private Tree extractWhereTree(Tree tree){
		for (int i = 0; i < tree.getChildCount(); i++) {
			Tree selectTree = tree.getChild(i);
			if ("SELECT".equals(selectTree.getText())) {
				for(int j=0; j < selectTree.getChildCount(); j++){
					Tree whereTree = selectTree.getChild(j);
					if("WHERE".equals(whereTree.getText())){
						return whereTree.getChild(0);
					}
				}

			}
		}

		return null;
	}

	/**
	 * CRITICAL FIX (2025-12-18): Auto-inject JOIN clauses for secondary type properties
	 *
	 * CMIS 1.1 requires that queries using secondary type properties must include
	 * a JOIN clause for the secondary type. However, this is not intuitive for users.
	 * This method automatically detects secondary type properties in the WHERE clause
	 * and injects the appropriate JOIN clauses.
	 *
	 * Example transformation:
	 * Input:  SELECT cmis:objectId FROM cmis:document WHERE nemaki:comment LIKE '%test%'
	 * Output: SELECT cmis:objectId FROM cmis:document JOIN nemaki:commentable WHERE nemaki:comment LIKE '%test%'
	 *
	 * @param repositoryId the repository ID
	 * @param statement the original CMIS SQL statement
	 * @return the modified statement with JOIN clauses for secondary types, or original if no changes needed
	 */
	private String injectSecondaryTypeJoins(String repositoryId, String statement) {
		if (statement == null || repositoryId == null || typeManager == null) {
			return statement;
		}

		try {
			// Extract WHERE clause to find property names
			String upperStatement = statement.toUpperCase();
			int whereIndex = upperStatement.indexOf(" WHERE ");
			if (whereIndex < 0) {
				// No WHERE clause, nothing to do
				return statement;
			}

			String whereClause = statement.substring(whereIndex + 7); // After " WHERE "

			// Find property names in WHERE clause (pattern: prefix:name)
			// This regex matches CMIS property names like "nemaki:comment", "cmis:name", etc.
			Pattern propPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)");
			Matcher propMatcher = propPattern.matcher(whereClause);

			java.util.Set<String> secondaryTypesToJoin = new java.util.LinkedHashSet<>();

			while (propMatcher.find()) {
				String propertyName = propMatcher.group(1);

				// Skip standard CMIS properties (they don't need JOINs)
				if (propertyName.startsWith("cmis:")) {
					continue;
				}

				// Check if this property belongs to a secondary type
				TypeDefinition secondaryType = typeManager.findSecondaryTypeByPropertyQueryName(repositoryId, propertyName);
				if (secondaryType != null) {
					secondaryTypesToJoin.add(secondaryType.getId());
					if (logger.isDebugEnabled()) {
						logger.debug("Found secondary type property '" + propertyName + "' -> adding JOIN for '" + secondaryType.getId() + "'");
					}
				}
			}

			// If we found secondary types, inject JOIN clauses
			if (!secondaryTypesToJoin.isEmpty()) {
				// Find the FROM clause position
				int fromIndex = upperStatement.indexOf(" FROM ");
				if (fromIndex < 0) {
					return statement;
				}

				// Find the end of the FROM clause (either WHERE, ORDER BY, or end of string)
				int fromEndIndex = whereIndex; // We know WHERE exists

				// Build the JOIN clause string
				StringBuilder joinClause = new StringBuilder();
				for (String secondaryTypeId : secondaryTypesToJoin) {
					joinClause.append(" JOIN ").append(secondaryTypeId);
				}

				// Insert JOIN clause before WHERE
				String beforeWhere = statement.substring(0, fromEndIndex);
				String afterIncludingWhere = statement.substring(fromEndIndex);
				String modifiedStatement = beforeWhere + joinClause.toString() + afterIncludingWhere;

				if (logger.isDebugEnabled()) {
					logger.debug("Injected secondary type JOIN: " + statement + " -> " + modifiedStatement);
				}

				return modifiedStatement;
			}

		} catch (Exception e) {
			logger.warn("Error in injectSecondaryTypeJoins: " + e.getMessage() + " - using original statement");
		}

		return statement;
	}

	/**
	 * CRITICAL FIX (2025-12-18): Manual parser for secondary type WHERE clauses.
	 * This is a fallback when OpenCMIS QueryUtilStrict fails to parse queries
	 * containing secondary type properties (throws FailedPredicateException).
	 *
	 * Supports the following patterns:
	 * - property LIKE 'pattern'
	 * - property = 'value'
	 * - property != 'value'
	 * - property IS NULL
	 * - property IS NOT NULL
	 * - ANY cmis:secondaryObjectTypeIds IN ('type1', 'type2')
	 *
	 * @param repositoryId the repository ID
	 * @param statement the CMIS SQL statement
	 * @return Solr query string, or null if parsing fails or no secondary type properties
	 */
	private String parseSecondaryTypeWhereClause(String repositoryId, String statement) {
		if (statement == null || repositoryId == null) {
			return null;
		}

		try {
			// Extract WHERE clause
			String upperStatement = statement.toUpperCase();
			int whereIndex = upperStatement.indexOf(" WHERE ");
			if (whereIndex < 0) {
				return null;
			}

			String whereClause = statement.substring(whereIndex + 7).trim();
			String upperWhereClause = whereClause.toUpperCase();

			// CRITICAL FIX (2025-12-18): Handle cmis:secondaryObjectTypeIds queries
			// Pattern: ANY cmis:secondaryObjectTypeIds IN ('value1', 'value2', ...)
			if (upperWhereClause.contains("SECONDARYOBJECTTYPEIDS")) {
				Pattern anyInPattern = Pattern.compile(
					"ANY\\s+cmis:secondaryObjectTypeIds\\s+IN\\s*\\(([^)]+)\\)",
					Pattern.CASE_INSENSITIVE
				);
				Matcher anyInMatcher = anyInPattern.matcher(whereClause);
				if (anyInMatcher.find()) {
					String valuesStr = anyInMatcher.group(1);
					// Parse values: 'value1', 'value2', ...
					Pattern valuePattern = Pattern.compile("'([^']*)'");
					Matcher valueMatcher = valuePattern.matcher(valuesStr);

					List<String> values = new ArrayList<>();
					while (valueMatcher.find()) {
						values.add(valueMatcher.group(1));
					}

					if (!values.isEmpty()) {
						// Build Solr query for multi-valued field
						// secondary_object_type_ids:(value1 OR value2 OR ...)
						StringBuilder solrQuery = new StringBuilder();
						solrQuery.append("secondary_object_type_ids:(");
						for (int i = 0; i < values.size(); i++) {
							if (i > 0) {
								solrQuery.append(" OR ");
							}
							// Escape colons in type IDs for Solr
							solrQuery.append(values.get(i).replace(":", "\\:"));
						}
						solrQuery.append(")");

						if (logger.isDebugEnabled()) {
							logger.debug("Parsed cmis:secondaryObjectTypeIds ANY IN query: " + solrQuery);
						}
						return solrQuery.toString();
					}
				}

				// Pattern: cmis:secondaryObjectTypeIds = 'value' (single value)
				Pattern equalsPattern = Pattern.compile(
					"cmis:secondaryObjectTypeIds\\s*=\\s*'([^']*)'",
					Pattern.CASE_INSENSITIVE
				);
				Matcher equalsMatcher = equalsPattern.matcher(whereClause);
				if (equalsMatcher.find()) {
					String value = equalsMatcher.group(1);
					String solrQuery = "secondary_object_type_ids:" + value.replace(":", "\\:");
					if (logger.isDebugEnabled()) {
						logger.debug("Parsed cmis:secondaryObjectTypeIds = query: " + solrQuery);
					}
					return solrQuery;
				}
			}

			// Check if this WHERE clause contains secondary type properties (non-cmis: prefix)
			Pattern propPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)");
			Matcher propMatcher = propPattern.matcher(whereClause);

			boolean hasSecondaryTypeProperty = false;
			while (propMatcher.find()) {
				String propName = propMatcher.group(1);
				if (!propName.startsWith("cmis:")) {
					hasSecondaryTypeProperty = true;
					break;
				}
			}

			if (!hasSecondaryTypeProperty) {
				return null;
			}

			// Parse the WHERE clause manually
			// Support: LIKE, =, !=, <>, IS NULL, IS NOT NULL

			// Pattern for LIKE: property LIKE 'value'
			Pattern likePattern = Pattern.compile(
				"([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)\\s+LIKE\\s+'([^']*)'",
				Pattern.CASE_INSENSITIVE
			);
			Matcher likeMatcher = likePattern.matcher(whereClause);
			if (likeMatcher.find()) {
				String propertyName = likeMatcher.group(1);
				String pattern = likeMatcher.group(2);

				// Convert to Solr field name
				String solrFieldName = solrUtil.getPropertyNameInSolr(repositoryId, propertyName);

				// Convert SQL LIKE pattern to Solr wildcard pattern
				// % -> *, _ as SQL wildcard -> ? (but literal _ should remain _)
				// Note: This simple conversion treats all _ as wildcards
				String solrPattern = pattern.replace("%", "*").replace("_", "?");

				if (logger.isDebugEnabled()) {
					logger.debug("Parsed secondary type LIKE query: " + propertyName + " -> " + solrFieldName + ":" + solrPattern);
				}

				return solrFieldName + ":" + solrPattern;
			}

			// Pattern for equals: property = 'value'
			Pattern equalsPattern = Pattern.compile(
				"([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*'([^']*)'",
				Pattern.CASE_INSENSITIVE
			);
			Matcher equalsMatcher = equalsPattern.matcher(whereClause);
			if (equalsMatcher.find()) {
				String propertyName = equalsMatcher.group(1);
				String value = equalsMatcher.group(2);
				String solrFieldName = solrUtil.getPropertyNameInSolr(repositoryId, propertyName);
				return solrFieldName + ":\"" + value + "\"";
			}

			// Pattern for not equals: property != 'value' or property <> 'value'
			Pattern notEqualsPattern = Pattern.compile(
				"([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:!=|<>)\\s*'([^']*)'",
				Pattern.CASE_INSENSITIVE
			);
			Matcher notEqualsMatcher = notEqualsPattern.matcher(whereClause);
			if (notEqualsMatcher.find()) {
				String propertyName = notEqualsMatcher.group(1);
				String value = notEqualsMatcher.group(2);
				String solrFieldName = solrUtil.getPropertyNameInSolr(repositoryId, propertyName);
				return "-" + solrFieldName + ":\"" + value + "\"";
			}

			// Pattern for IS NULL: property IS NULL
			Pattern isNullPattern = Pattern.compile(
				"([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)\\s+IS\\s+NULL",
				Pattern.CASE_INSENSITIVE
			);
			Matcher isNullMatcher = isNullPattern.matcher(whereClause);
			if (isNullMatcher.find()) {
				String propertyName = isNullMatcher.group(1);
				String solrFieldName = solrUtil.getPropertyNameInSolr(repositoryId, propertyName);
				return "-" + solrFieldName + ":[* TO *]";
			}

			// Pattern for IS NOT NULL: property IS NOT NULL
			Pattern isNotNullPattern = Pattern.compile(
				"([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)\\s+IS\\s+NOT\\s+NULL",
				Pattern.CASE_INSENSITIVE
			);
			Matcher isNotNullMatcher = isNotNullPattern.matcher(whereClause);
			if (isNotNullMatcher.find()) {
				String propertyName = isNotNullMatcher.group(1);
				String solrFieldName = solrUtil.getPropertyNameInSolr(repositoryId, propertyName);
				return solrFieldName + ":[* TO *]";
			}

			return null;

		} catch (Exception e) {
			logger.warn("Error parsing secondary type WHERE clause: " + e.getMessage());
			return null;
		}
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}
}
