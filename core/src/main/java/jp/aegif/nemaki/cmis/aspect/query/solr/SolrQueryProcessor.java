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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

import org.apache.chemistry.opencmis.server.support.query.CmisTree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.support.query.ColumnReference;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.chemistry.opencmis.server.support.query.QueryObject.JoinSpec;
import org.apache.chemistry.opencmis.server.support.query.QueryObject.SortSpec;
import org.apache.chemistry.opencmis.server.support.query.QueryUtilStrict;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.client.solrj.request.SolrQuery;
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
	// ACL-in-Solr: builds the query-time readers fq from the caller's principals.
	private jp.aegif.nemaki.rag.acl.ACLExpander aclExpander;
	private ExceptionService exceptionService;

	/**
	 * Answers "is a permission change still landing in this repository?". Optional: when it is not
	 * wired the over-cap behaviour is exactly what it always was (reject with 400).
	 */
	private AclPropagationStaleness propagationStaleness;
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
		public void addTypeDefinition(TypeDefinition typeDefinition, boolean addInheritedProperties) {
			// This adapter is read-only for query processing purposes.
			// Type definitions are managed by the main TypeManager, not through queries.
			throw new UnsupportedOperationException(
					"CmisTypeManager is a read-only adapter for query processing. " +
					"Use TypeManager directly to add type definitions.");
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
			// NOT an empty result: that answered 「nothing matches」 for a search that was never run (closing batch 3
			// review, subagent P3-7 — the sibling of the parser-initialisation exit Codex named in the batch before).
			logger.error("Solr client unavailable; the CMIS query was not run: " + statement);
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"The search engine could not be reached, so the query was not run");
		}
		
		// replacing backslashed for TIMESTAMP only
		Pattern time_p = Pattern.compile("(TIMESTAMP\\s?'[\\-\\d]*T\\d{2})\\\\:(\\d{2})\\\\:([\\.\\d]*Z')", Pattern.CASE_INSENSITIVE);
		Matcher time_m = time_p.matcher(statement);
		statement = time_m.replaceAll("$1:$2:$3");

		// CRITICAL FIX (2025-12-18): Auto-inject JOIN for secondary type properties
		// This allows queries like "WHERE nemaki:comment LIKE '%test%'" to work without
		// requiring users to manually add "JOIN nemaki:commentable" to the query
		// The statement as the client wrote it is kept, and the JOINs added are named: a JOIN the client wrote is a
		// condition on the answer, one the server added is not (R215).
		String asWritten = statement;
		Set<String> injectedJoins = new LinkedHashSet<String>();
		// R217: of those, the secondary types the statement names as a row (a column qualified by the type).
		Map<String, TypeDefinition> rowsNamed = new LinkedHashMap<String, TypeDefinition>();
		statement = injectSecondaryTypeJoins(repositoryId, statement, injectedJoins, rowsNamed);

		// Parse the CMIS query using QueryUtilStrict, then use SolrPredicateWalker
		// to convert the WHERE clause AST into Solr query syntax.

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
			// NOT an empty result. That answered a statement the server could not even begin to read as
			// 「nothing matches」 — objects [], numItems 0, a success (closing batch 2 review, Codex P2).
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"The query could not be prepared, so it was not run: " + e.getMessage(), e);
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
		
		// Get where caluse as CmisTree
		CmisTree whereTree = null;
		// Why the statement could not be processed, when it could not: a statement that does not parse is the
		// client's (closing batch 4 review, subagent P3-5 — `SELECT * FORM cmis:document` became a 500 once the
		// FROM lookup below stopped answering an empty list).
		Exception parseFailure = null;
		try {
			if (logger.isDebugEnabled()) {
				logger.debug("About to call util.processStatement()");
			}
			util.processStatement();
			if (logger.isDebugEnabled()) {
				logger.debug("processStatement() completed");
			}
			CmisTree tree = util.parseStatement();
			if (logger.isDebugEnabled()) {
				logger.debug("parseStatement() completed");
			}
			whereTree = extractWhereTree(tree);
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception in processStatement: " + e.getMessage());
			}
			logger.error("Exception in processStatement", e);
			if (jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException.isIn(e)) {
				// The type definitions could not be read: not a statement that does not parse (closing batch 4
				// review, subagent P2-1 (c) — a repository whose type system did not load answered WHERE cmis:name =
				// 'a' with the client's 400).
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
						"The type definitions could not be read, so the query was not run: " + e.getMessage(), e);
			}
			parseFailure = e;
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
		// ANTLR3's Tree.isNil() was `token == null || token.getType() == INVALID_TOKEN_TYPE`,
		// and INVALID_TOKEN_TYPE is 0. CmisTree (the ANTLR4 stack) exposes no isNil, so the
		// same condition is spelled out: a node with no token type is the nil root.
		//
		// "*:*" ONLY when the statement has no WHERE clause (R188). A WHERE clause that could not be
		// translated used to fall back to "*:*" too: the condition vanished and every document of the
		// type was returned as if it matched — measured on bedroom, `WHERE chatChannelName = 'x'` and
		// `WHERE cmis:rm_expirationDate IS NULL` returned all 2,449. No WHERE and "could not read the
		// WHERE" are told apart by the statement's text, not by the parse: a parse that failed leaves
		// no tree either.
		if ((whereTree == null || whereTree.getType() == 0) && !hasWhereClause(statement)) {
			whereQueryString = "*:*";
		} else if (whereTree == null || whereTree.getType() == 0) {
			// CRITICAL FIX (2025-12-18): Try to parse secondary type properties manually
			// when OpenCMIS parsing fails (e.g., due to FailedPredicateException)
			whereQueryString = wholeWhereOrRefuse(repositoryId, statement,
					"the WHERE clause could not be parsed");
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
					whereQueryString = wholeWhereOrRefuse(repositoryId, statement,
							"the WHERE clause has a form this server does not translate");
				}
			} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException refusal) {
				// A REFUSAL is not a translation failure, and it must NOT be handed to the manual
				// parser (round-8 review, both reviewers, P1). That parser returns the FIRST predicate
				// it recognises and discards the rest, so
				//     WHERE nemaki:tag = 'x' AND nemaki:dueDate IS NULL
				// came back as a successful query for tag alone — the refused condition silently
				// dropped, 200 with results where the answer must be a 400. The walker refuses only
				// when it knows the field cannot be queried at all; nothing downstream can do better.
				logger.warn("Refusing the statement: " + refusal.getMessage());
				throw refusal;
			} catch (Exception e) {
				logger.error("Error in SolrPredicateWalker.walkPredicate: " + e.getMessage(), e);
				if (jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException.isIn(e)) {
					// A definition the walker needed could not be read: the server's failure, not an untranslatable
					// WHERE clause (closing batch 4 review, subagent P2-1 (b)).
					throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
							"The type definitions could not be read, so the query was not run: " + e.getMessage(), e);
				}
				// CRITICAL FIX (2025-12-18): Try manual parsing before throwing exception
				whereQueryString = wholeWhereOrRefuse(repositoryId, statement,
						"Invalid CMIS SQL statement: " + e.getMessage());
			}
		}

		// R215: each JOIN the client wrote narrows the answer to the documents that have the joined secondary type, or
		// the statement is refused. The JOIN used to be parsed and then not read: FROM cmis:document d JOIN
		// nemaki:commentable c ON d.cmis:objectId = c.cmis:objectId answered the same 2,457 documents as the statement
		// without it, not the 2 that have the type (measured on bedroom before this change, 2026-10-29).
		// Read after the WHERE is translated: a statement the WHERE refuses is refused for that (400), before the type
		// system is asked for the subtypes of a joined type (500) — closing batch 23 review, subagent P3-2.
		List<String> joinFilters = secondaryTypeJoinFilters(repositoryId, asWritten, queryObject, parseFailure,
				injectedJoins, rowsNamed);

		// Build solr query of FROM
		String fromQueryString = "";
		
		// Quote the repository id as a phrase so it is always a literal term.
		// It is normally a known value from repositories.yml, but treating it
		// as raw query text would let any special character it contained alter
		// the filter; escapeAndQuote keeps it inert.
		String repositoryQuery = "repository_id:"
				+ jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(repositoryId);
		
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
			// NOT an empty result (closing batch 3 review, subagent P3-7). A statement that did not parse has no FROM
			// to resolve, and that is the client's 400; anything else is a FROM this server could not read.
			// «Did not parse» is told POSITIVELY: the parser reports a syntax error as CmisInvalidArgumentException.
			// Taking every failure that lacks the unreadable mark for one made a server failure the client's 400 — a
			// RuntimeException from the type manager's lazy init during processStatement (closing batch 5 review,
			// subagent P3-2).
			if (parseFailure instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException
					&& !jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException.isIn(e)) {
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(
						"The statement could not be parsed: " + parseFailure.getMessage());
			}
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"The type in FROM could not be resolved, so the query was not run: " + e.getMessage(), e);
		}

		// Check if td is null before proceeding
		if (td == null) {
			if (logger.isDebugEnabled()) {
				logger.debug("TypeDefinition is null, cannot proceed with query");
			}
			// FROM names no type this repository has: the client's error, not 「no document matches」 — `FROM
			// nemaki:typo` answered 200 with nothing (closing batch 3 review, subagent P3-7, measured on bedroom).
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(
					"FROM names no type this repository has, so the query was not run: " + statement);
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
			// Phrase-quote so the whole id is a literal term (see the
			// descendant branch below for the same rationale).
			tables.add(jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(baseTypeId));
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
				// Quote each type id as a phrase so its whole content is a
				// literal term — escaping only ':' left whitespace and other
				// Solr specials live.
				tables.add(jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(table));
			}
		}
		
		// Both calls through the same rule as the WHERE productions (a no-op for this static
		// property, but the rule has no exemptions — see EveryFieldNameIsEscapedOnceTest).
		String typeIdField = SolrUtil.queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.OBJECT_TYPE_ID));
		fromQueryString += "("+ typeIdField +":"+ StringUtils.join(tables," " + typeIdField + ":") + ")";
		// With the FROM's type, not in q: the JOIN narrows the rows the FROM names, and a q of the walker's NOT is a
		// clause of MUST_NOT alone, which matches nothing once it is nested under an AND (R215).
		for (String joinFilter : joinFilters) {
			fromQueryString += " AND (" + joinFilter + ")";
		}

		// Execute query
		SolrQuery solrQuery = new SolrQuery();
		solrQuery.setQuery(whereQueryString);
		solrQuery.setFilterQueries(fromQueryString);

		// ACL-in-Solr: filter to documents the caller may read, IN SOLR, so
		// numFound is the authorized count (not the pre-ACL total). This lets a
		// low-privilege user search a large repository — their authorized subset
		// is small even if the overall match exceeds the scan cap — and removes
		// the pre-ACL cap rejection for such users. Admins bypass the fq (they see
		// everything, matching the in-memory admin bypass). The in-memory
		// permissionService.getFiltered below is kept as defense-in-depth.
		boolean callerIsAdmin = false;
		String callerUsername = (callContext != null) ? callContext.getUsername() : null;
		try {
			jp.aegif.nemaki.model.UserItem callerItem =
					(callerUsername != null) ? contentService.getUserItemById(repositoryId, callerUsername) : null;
			callerIsAdmin = (callerItem != null && Boolean.TRUE.equals(callerItem.isAdmin()));
		} catch (Exception e) {
			// Fail-closed: an admin-check failure is treated as non-admin, so the
			// readers fq is applied rather than skipped.
			callerIsAdmin = false;
		}
		String readersFilterQuery = (!callerIsAdmin && aclExpander != null && callerUsername != null)
				? aclExpander.buildReaderFilterQuery(repositoryId, callerUsername)
				: null;
		for (String aclFq : aclFilterQueries(callerIsAdmin, readersFilterQuery)) {
			solrQuery.addFilterQuery(aclFq);
		}

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
		// Authorization is applied primarily IN SOLR via the readers fq added
		// above (ACL-in-Solr), so for a non-admin caller Solr's numFound is already
		// the authorized count and the pre-ACL total is never materialized
		// (relationships are NOT exempt — they carry their source/target readers).
		// The in-memory permissionService.getFiltered below remains as
		// defense-in-depth (and is the sole ACL gate for admins, who bypass the
		// fq). The matching set is
		// fetched up to a bounded cap and sorted + paged in memory so ORDER BY is
		// correct across pages; a set LARGER than the cap cannot be ordered/paged
		// in memory and is REJECTED (see the count probe below) rather than
		// returned as a truncated page with a misleading hasMoreItems. Within the
		// cap the whole set is materialized, so numItems is exact and hasMoreItems
		// is honest. Raise the cap with -Dnemakiware.cmis.query.aclScanMaxRows.
		int aclScanCap = Integer.getInteger("nemakiware.cmis.query.aclScanMaxRows", 10000);

		if (solrClient == null) {
			logger.error("SolrClient is null - cannot execute query");
			exceptionService.invalidArgument("Solr client initialization failed");
			return null;
		}

		// Two-phase, scan-cap-bounded fetch (rows=0 count probe → reject over-cap
		// before any body transfer → rows=cap fetch → race re-check). Extracted so
		// the guard behaviour is unit-testable (see SolrQueryProcessorScanCapTest).
		// A CmisInvalidArgumentException from the guard is a 400 that propagates
		// out; its message never echoes the pre-ACL count.
		QueryResponse resp = null;
		boolean truncatedByScanCap = false;
		try {
			final String repoForStaleness = repositoryId;
			CappedResult capped = queryWithinScanCap(solrClient, solrQuery, aclScanCap,
					() -> propagationStaleness != null
							&& propagationStaleness.isPropagationUnconverged(repoForStaleness));
			resp = capped.response;
			truncatedByScanCap = capped.truncated;
			if (truncatedByScanCap) {
				AclPropagationStaleness.recordDegradedQuery();
				logger.warn("Query in " + repositoryId + " matched more than the " + aclScanCap
						+ "-row ACL scan limit while a permission change is still propagating;"
						+ " answering with the rows that could be confirmed instead of rejecting."
						+ " numItems is a lower bound and hasMoreItems reflects only what is"
						+ " reachable.");
			}
		} catch (SolrServerException | IOException e) {
			logger.error("Solr query failed: " + e.getMessage(), e);
			// The SERVER's failure, not the client's: invalidArgument made a search engine that did not answer a 400 —
			// through API v1 too once that endpoint stopped reading the message (closing batch 3 review, subagent P3-2).
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"The search engine did not answer, so the query was not run: " + e.getMessage(), e);
		}

		long numFound =0;
		// Output search results to ObjectList
		if (resp != null && resp.getResults() != null
				&& resp.getResults().getNumFound() != 0) {
			SolrDocumentList docs = resp.getResults();
			numFound = docs.getNumFound();

			List<Content> contents = new ArrayList<Content>();
			Set<String> seenIds = new HashSet<String>();
			for (SolrDocument doc : docs) {
				// Type-safe field value extraction
				String docId = extractStringFieldValue(doc, "object_id");
				if (docId == null) {
					logger.warn("Skipping document with null object_id");
					continue;
				}
				if (!seenIds.add(docId)) {
					logger.warn("[objectId=" + docId + "] Duplicate entry in Solr, skipping.");
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

				// ORDER BY / repository-default ordering must be applied to the FULL
				// authorized set BEFORE paging. ACL filtering happens after Solr
				// returns, so the page has to be sliced from the ordered authorized
				// set — not the Solr-native (modified desc) order. If the page were
				// sliced first and sorted only within itself, a page size of 1 would
				// make the sort a no-op and page N would disagree with the unpaged
				// order. Sort here, then slice.
				List<Content> ordered = compileService.sortContentsForSearchResult(
						callContext, repositoryId, permitted, orderBy);

				// ACL-aware paging in memory. `ordered` is the authorized full set
				// (bounded by aclScanCap); numItems is the authorized total and the
				// page is sliced here so a full authorized page is returned even when
				// the Solr window contained non-authorized documents.
				int totalAuthorized = ordered.size();
				// Math.max(0, ...) bounds the RESULT of the truncation, not the truncation:
				// intValue() keeps the low 32 bits first, so 2^32 arrives as 0 and this page
				// comes back empty. Compare before converting, like the children and type
				// listings.
				int skip = clampQuerySkip(skipCount);
				int max = (maxItems == null) ? totalAuthorized : clampQueryPage(maxItems);
				List<Content> pageContents;
				if (skip >= totalAuthorized) {
					pageContents = new ArrayList<Content>();
				} else {
					pageContents = new ArrayList<Content>(
							ordered.subList(skip, Math.min(skip + max, totalAuthorized)));
				}
				// numFound <= aclScanCap here (the reachability guard above rejects
				// anything larger), so `ordered` is the COMPLETE authorized set:
				// numItems is the exact authorized total and hasMoreItems (computed
				// in compileObjectDataListForSearchResult from skip+max vs the total)
				// is honest — there is no unreachable remainder to mis-signal.

				// TCK CRITICAL FIX: Pass propertyAliases map to enable query alias support
				if (logger.isDebugEnabled()) {
					logger.debug("TCK Alias: Calling compileObjectDataListForSearchResult with propertyAliases");
				}
				// The page is already globally ordered; pass "NONE" so the page compile
				// does not re-sort it (a SELECT-limited property set could otherwise
				// reorder the page by a property it no longer carries).
				ObjectList result = compileService.compileObjectDataListForSearchResult(
						callContext, repositoryId, pageContents, filter, requestedWithAliasKey,
						includeAllowableActions, includeRelationships, renditionFilter, false,
						maxItems, skipCount, false, "NONE", totalAuthorized);
				if (truncatedByScanCap) {
					markTruncated(result);
				}

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
	
	/**
	 * Reachability guard for ACL-aware in-memory paging: the pre-ACL Solr match
	 * count {@code numFound} exceeds the scan cap. When true the query cannot be
	 * correctly authorized/ordered/paged in memory (only the first {@code cap}
	 * rows are fetched) and must be rejected rather than returned as a
	 * wrong-ordered / truncated page with a misleading {@code hasMoreItems=true}.
	 * Boundary: {@code numFound == cap} is allowed; {@code cap + 1} is rejected.
	 */
	static boolean exceedsScanCap(long numFound, int aclScanCap) {
		return numFound > aclScanCap;
	}

	/**
	 * ACL-in-Solr: build the ACL-related filter queries for a CMIS query.
	 * <ul>
	 *   <li><b>Admin</b> (or no readers fq available — expander unwired /
	 *       anonymous): NO readers filtering in Solr; the in-memory
	 *       {@code permissionService.getFiltered} still enforces ACL.</li>
	 *   <li><b>Non-admin</b>: restrict to content carrying one of the caller's
	 *       reader tokens. Relationships carry the union of their source/target
	 *       readers (stamped at index time), so they are filtered like any other
	 *       content — no carve-out.</li>
	 *   <li>Always: exclude RAG parent/chunk docs, which share this Solr core and
	 *       also carry a {@code readers} field (CMIS content has no
	 *       {@code doc_type}).</li>
	 * </ul>
	 * Package-private + static for the unit test.
	 */
	static List<String> aclFilterQueries(boolean callerIsAdmin, String readersFilterQuery) {
		List<String> fqs = new ArrayList<String>();
		if (!callerIsAdmin && readersFilterQuery != null) {
			fqs.add(readersFilterQuery);
		}
		fqs.add("-doc_type:[* TO *]");
		return fqs;
	}

	/**
	 * Execute the Solr query in two phases so the ACL-scan cap is enforced without
	 * transferring document bodies for an over-cap match set:
	 * <ol>
	 *   <li><b>Phase 1</b> issues the query with {@code rows=0} — this returns
	 *       {@code numFound} with NO body transfer. Because the ACL-in-Solr readers
	 *       fq is already applied to {@code solrQuery}, for a non-admin caller this
	 *       count is the AUTHORIZED match count (an admin, who bypasses the fq,
	 *       sees the full count — which is fine, they may read everything). If it
	 *       exceeds the cap the query is rejected here (no second query, no
	 *       cap-sized fetch), so even {@code $top=1} is cheap to reject.</li>
	 *   <li><b>Phase 2</b> (only when within the cap) re-issues the query with
	 *       {@code rows=cap} to fetch the set for in-memory ACL + sort + paging,
	 *       then re-checks the count to close the race window where documents were
	 *       added between the probe and the fetch.</li>
	 * </ol>
	 * The rejection {@link CmisInvalidArgumentException} (HTTP 400) never echoes
	 * the count — that would leak the number of matching objects. Package-private
	 * so {@code SolrQueryProcessorScanCapTest} can drive it with a mock
	 * {@link SolrClient}.
	 */
	static CappedResult queryWithinScanCap(SolrClient solrClient, SolrQuery solrQuery,
			int aclScanCap, java.util.function.BooleanSupplier mayDegrade)
			throws SolrServerException, IOException {
		// Phase 1: rows=0 count probe.
		solrQuery.set(CommonParams.START, 0);
		solrQuery.set(CommonParams.ROWS, 0);
		boolean degraded = false;
		if (exceedsScanCap(numFoundOf(solrClient.query(solrQuery)), aclScanCap)) {
			// Over the cap. Ordinarily that is a query too broad to authorize in memory and it is
			// refused here, before any body transfer. There is one case where the count is not
			// evidence of breadth: while a permission change is still propagating, the index still
			// carries the revoked principal's tokens, so the pre-gate count is inflated by rows the
			// in-memory gate is about to remove. Rejecting then makes a revocation look like a
			// broken search.
			//
			// The question is asked ONLY here — on a request that was going to fail — so the
			// cheap-reject path is untouched for every query that is genuinely too broad.
			if (!mayDegrade.getAsBoolean()) {
				throw scanCapExceeded(aclScanCap);
			}
			degraded = true;
		}
		// Phase 2: fetch up to the cap. Bounded either way, so the degraded path costs no more
		// than a query that was within the cap.
		solrQuery.set(CommonParams.ROWS, aclScanCap);
		QueryResponse resp = solrClient.query(solrQuery);
		// Race-window re-check (documents added between the probe and the fetch).
		if (!degraded && exceedsScanCap(numFoundOf(resp), aclScanCap)) {
			if (!mayDegrade.getAsBoolean()) {
				throw scanCapExceeded(aclScanCap);
			}
			degraded = true;
		}
		return new CappedResult(resp, degraded);
	}

	/**
	 * A cap-bounded fetch, and whether it is all there is.
	 *
	 * <p>{@code truncated} means the match set was larger than the ACL scan cap and the server
	 * chose to answer with what it could confirm rather than refuse. The rows are correct — the
	 * in-memory gate still runs — but they are a prefix, so the caller must not present the count
	 * as a total or claim more pages than it can actually serve.
	 */
	static final class CappedResult {
		final QueryResponse response;
		final boolean truncated;

		CappedResult(QueryResponse response, boolean truncated) {
			this.response = response;
			this.truncated = truncated;
		}
	}

	/**
	 * Tell the caller the page is a prefix, not the whole answer.
	 *
	 * <p>{@code hasMoreItems} already says only what this server can actually serve: it is
	 * computed from the slice against the confirmed set, so once the caller reaches the end of the
	 * cap-bounded window it goes false rather than promising pages that cannot be fetched. What
	 * that alone cannot say is WHY the answer stops there, which is the difference between "you
	 * have seen everything" and "narrow your query". The extension carries that, and the count
	 * stays a lower bound rather than a fabricated total.
	 */
	private static void markTruncated(ObjectList result) {
		if (result == null) {
			return;
		}
		List<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement> exts =
				new ArrayList<org.apache.chemistry.opencmis.commons.data.CmisExtensionElement>();
		if (result.getExtensions() != null) {
			exts.addAll(result.getExtensions());
		}
		exts.add(new org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl(
				null, "truncatedByAclScanLimit", null, "true"));
		result.setExtensions(exts);
	}

	private static long numFoundOf(QueryResponse resp) {
		return (resp != null && resp.getResults() != null) ? resp.getResults().getNumFound() : 0;
	}

	/** Build the 400 for an over-cap result set — note: NO pre-ACL count in the message. */
	static CmisInvalidArgumentException scanCapExceeded(int aclScanCap) {
		return new CmisInvalidArgumentException(
				"The query is too broad to authorize in memory: it matches more than the "
				+ aclScanCap + "-row ACL scan limit. Narrow the query (add a WHERE clause) "
				+ "or raise -Dnemakiware.cmis.query.aclScanMaxRows.");
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

	/**
	 * The predicate under WHERE, or null when the statement has none.
	 *
	 * <p>The ROOT is the SELECT node itself under OpenCMIS 2.x; under the ANTLR3 grammar it
	 * was a nil node WRAPPING the SELECT. Only looking one level down — as this did — silently
	 * returned null on the 2.x shape, and a null predicate means the Solr query falls back to
	 * {@code *:*}: every LIKE, IN_FOLDER and equality filter was dropped and the query returned
	 * the whole repository. It failed no parse and logged nothing; the TCK caught it as
	 * "name should start with 'a' but is 'bDocument'". Both shapes are accepted here.
	 */
	private CmisTree extractWhereTree(CmisTree tree){
		if (tree == null) {
			return null;
		}
		CmisTree selectTree = "SELECT".equals(tree.getText()) ? tree : null;
		if (selectTree == null) {
			for (int i = 0; i < tree.getChildCount(); i++) {
				if ("SELECT".equals(tree.getChild(i).getText())) {
					selectTree = tree.getChild(i);
					break;
				}
			}
		}
		if (selectTree == null) {
			return null;
		}
		for (int j = 0; j < selectTree.getChildCount(); j++) {
			CmisTree whereTree = selectTree.getChild(j);
			if ("WHERE".equals(whereTree.getText())) {
				return whereTree.getChild(0);
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
	private String injectSecondaryTypeJoins(String repositoryId, String statement, Set<String> injected,
			Map<String, TypeDefinition> rowsNamed) {
		if (statement == null || repositoryId == null || typeManager == null) {
			return statement;
		}

		try {
			// Extract WHERE clause to find property names — found the way hasWhereClause finds it (any whitespace
			// around the keyword, never inside a literal). This looked for " WHERE " with a space on each side, so a
			// keyword after a newline got no JOIN while the rest of the processor saw a WHERE clause (closing batch 2
			// review, subagent P3). The names are read with the literals blanked, so a value cannot add a JOIN.
			String blanked = withoutStringLiterals(statement);
			Matcher where = WHERE_KEYWORD.matcher(blanked);
			boolean hasWhere = where.find();
			// Where the FROM part ends: at the WHERE, else at the ORDER BY, else at the end of the statement. A statement
			// needs no WHERE to name a secondary type as a row (R218: SELECT nemaki:commentable.nemaki:comment FROM
			// cmis:document had no JOIN and answered every document — 2,457 on bedroom, the statement written with the JOIN 2).
			int whereIndex;
			if (hasWhere) {
				whereIndex = where.start();
			} else {
				Matcher orderBy = ORDER_BY_KEYWORD.matcher(blanked);
				whereIndex = orderBy.find() ? orderBy.start() : blanked.length();
			}

			String whereClause = hasWhere ? blanked.substring(where.end()) : "";

			// Find property names in WHERE clause (pattern: prefix:name)
			// This regex matches CMIS property names like "nemaki:comment", "cmis:name", etc.
			Pattern propPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_]*)");
			Matcher propMatcher = propPattern.matcher(whereClause);

			java.util.Set<String> secondaryTypesToJoin = new java.util.LinkedHashSet<>();
			Map<String, TypeDefinition> namedAsARow = new LinkedHashMap<String, TypeDefinition>();
			// Not when the FROM part already names the type, as a type or as an alias (closing batch 17 review, subagent
			// P3-2): the parser files an unaliased JOIN under the type's own name and OVERWRITES an entry of that name,
			// so `JOIN nemaki:other nemaki:commentable ON ...` became a JOIN of nemaki:commentable and the client's JOIN
			// of nemaki:other was lost; and a client's own unaliased JOIN of the type without an ON was read as the
			// server's.
			// From the FROM keyword on: the SELECT list may name a type too — a column alias — and that is not the FROM
			// naming it (closing batch 18 review, subagent P3-3: the server's JOIN went missing and the statement was
			// refused).
			Matcher from = FROM_KEYWORD.matcher(blanked);
			boolean fromFound = from.find() && from.start() < whereIndex;
			String fromPart = blanked.substring(fromFound ? from.start() : 0, whereIndex);

			// R218: the secondary types the statement names as a row — the qualifier of a column anywhere in the statement
			// (whitespace allowed before the dot), or of IN_FOLDER, IN_TREE or CONTAINS — each get the JOIN, though the WHERE
			// names none of its properties. The JOIN was added only for a property the WHERE named, so a qualifier in the
			// SELECT list, the ORDER BY or a function alone had nothing to resolve to: the parse failed, and the statement was
			// answered without the row (no WHERE: every document) or refused. A name is a type only as the type manager
			// answers it, so neither an alias nor a name in another case is taken for one (closing batch 23 review, subagent
			// P3-1). Read once here, and the only reading of "named as a row": the JOIN for a property the WHERE names is
			// narrowed exactly when its type is in it (R217).
			Map<String, TypeDefinition> rowsNamedHere = new LinkedHashMap<String, TypeDefinition>();
			Matcher qualifier = ROW_QUALIFIER.matcher(blanked);
			while (qualifier.find()) {
				String name = (qualifier.group(1) != null) ? qualifier.group(1) : qualifier.group(2);
				TypeDefinition named = typeManager.getTypeByQueryName(repositoryId, name);
				if (named != null && named.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY && !namedIn(fromPart, named)) {
					rowsNamedHere.put(named.getId(), named);
				}
			}

			while (propMatcher.find()) {
				String propertyName = propMatcher.group(1);

				// Skip standard CMIS properties (they don't need JOINs)
				if (propertyName.startsWith("cmis:")) {
					continue;
				}

				// Check if this property belongs to a secondary type
				TypeDefinition secondaryType = typeManager.findSecondaryTypeByPropertyQueryName(repositoryId, propertyName);
				// A type the statement names as a row gets its JOIN below even when the FROM's type defines the property too.
				if (secondaryType != null && !namedIn(fromPart, secondaryType)
						&& !fromTypeDefines(repositoryId, fromPart, propertyName)) {
					secondaryTypesToJoin.add(secondaryType.getId());
					if (logger.isDebugEnabled()) {
						logger.debug("Found secondary type property '" + propertyName + "' -> adding JOIN for '" + secondaryType.getId() + "'");
					}
				}
			}

			for (TypeDefinition named : rowsNamedHere.values()) {
				secondaryTypesToJoin.add(named.getId());
				namedAsARow.put(named.getId(), named);
			}

			// If we found secondary types, inject JOIN clauses
			if (!secondaryTypesToJoin.isEmpty()) {
				// The FROM found as the WHERE is — any whitespace around the keyword, never inside a literal. " FROM " with a
				// space on each side missed a FROM after a newline or a tab, and the server's JOIN with it (closing batch 19
				// review, subagent P3-1).
				if (!fromFound) {
					return statement;
				}

				// The end of the FROM clause: the WHERE, the ORDER BY or the end of the statement (above).
				int fromEndIndex = whereIndex;

				// Build the JOIN clause string — a space after it as well, since the keyword need not follow one.
				StringBuilder joinClause = new StringBuilder();
				for (String secondaryTypeId : secondaryTypesToJoin) {
					joinClause.append(" JOIN ").append(secondaryTypeId);
				}
				injected.addAll(secondaryTypesToJoin);
				rowsNamed.putAll(namedAsARow);
				joinClause.append(' ');

				// Insert JOIN clause before WHERE (or ORDER BY, or at the end)
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
	 * The filter each JOIN the client wrote adds to the FROM's (R215): the documents that have the joined secondary
	 * type - what {@code ANY cmis:secondaryObjectTypeIds IN ('that type')} answers, built by the same method
	 * ({@link SolrPredicateWalker#anyOf}), so the two send Solr the same text. The JOIN answered is the INNER JOIN of
	 * the FROM type with a secondary type ON the two objects' ids. Any other JOIN is refused rather than answered
	 * without its condition: a JOIN with a type that is not a secondary type (two primary types are not joined), an
	 * outer JOIN, and a JOIN whose ON is not the FROM's cmis:objectId equal to the joined type's (or that has no ON).
	 *
	 * <p>A statement whose parse failed is read the same way when the parser recorded every JOIN the client wrote: a
	 * column the type manager cannot resolve - a property of the joined type it does not name, as nemaki:comment of
	 * nemaki:commentable on bedroom - fails after the whole statement was walked, and the manual fallback answers the
	 * WHERE, so the JOIN stays a condition. Refused before, {@code JOIN ... WHERE c.nemaki:comment IS NOT NULL} was an
	 * over-refusal of a statement answered right without the change (measured on bedroom, 2026-10-29). When fewer
	 * JOINs were recorded than the client wrote, the statement is refused.
	 *
	 * <p>The JOINs the server adds itself ({@link #injectSecondaryTypeJoins} - a WHERE that names a property of the
	 * type) add no filter, except one for a type the statement names as a row (R217, below): the WHERE that named the
	 * property narrows the answer already, and that answer is kept as it was. {@code WHERE nemaki:comment IS NULL} answers the documents without a comment, those without the type
	 * among them, as before. (The server adds its JOIN to a statement that JOINs the type already, too; the client's
	 * JOIN is read as a condition and the server's is not, so the second one changes nothing.)
	 */
	List<String> secondaryTypeJoinFilters(String repositoryId, String asWritten, QueryObject queryObject,
			Exception parseFailure, Set<String> injected, Map<String, TypeDefinition> rowsNamed) {
		List<String> filters = new ArrayList<String>();
		int written = 0;
		if (asWritten != null) {
			Matcher joinKeyword = JOIN_KEYWORD.matcher(withoutStringLiterals(asWritten));
			while (joinKeyword.find()) {
				written++;
			}
		}
		if (parseFailure != null && written == 0) {
			// The server's own JOINs only: the manual fallback answers the WHERE as it did, narrowed to the rows the
			// statement names (R217).
			narrowToTheRowsNamed(repositoryId, rowsNamed, filters);
			return filters;
		}
		String mainAlias = queryObject.getMainTypeAlias();
		Map<String, String> types = queryObject.getTypes();
		// The rows an ON may join to: the FROM's and each JOIN's already read, so a JOIN ON an earlier joined type's
		// cmis:objectId — the same object's — is answered (closing batch 17 review, subagent P3-1).
		Set<String> bound = new LinkedHashSet<String>();
		bound.add(mainAlias);
		// Every name the FROM gives a row. The parser refuses an alias used twice, but files an unaliased JOIN under its
		// type's name without that check and OVERWRITES the row of that name, so `JOIN nemaki:other nemaki:commentable
		// ON ... JOIN nemaki:commentable ON ...` lost the JOIN of nemaki:other and answered the other alone (closing
		// batch 18 review, subagent P3-1). Refused as the parser refuses a repeated alias.
		Set<String> names = new HashSet<String>();
		if (mainAlias != null) {
			names.add(mainAlias);
		}
		// First the statement's shape — the names of its rows, and whether every JOIN it wrote was read — and only then
		// what the type system says of each joined type: a statement refused for its shape is refused for that (400), not
		// for a subtype the type system could not read (500) on the way (closing batch 19 review, Codex P2).
		List<JoinSpec> clientJoinSpecs = new ArrayList<JoinSpec>();
		for (JoinSpec join : queryObject.getJoins()) {
			if (join.alias != null && !names.add(join.alias)) {
				throw new CmisInvalidArgumentException("The name " + join.alias + " names more than one row of the FROM "
						+ "(a JOIN without an alias is named by its type), so the statement is refused rather than "
						+ "answered with one of those rows lost");
			}
			// Told by the text alone: the server writes its JOIN as the type's id, with no alias and no ON, so no type is
			// read on this pass (closing batch 20 review, Codex P2 — the read of each JOIN's type here answered 500 for a
			// statement refused for its shape when the type system did not answer).
			if (join.onLeft == null && join.onRight == null && injected.contains(types.get(join.alias))) {
				continue;
			}
			clientJoinSpecs.add(join);
		}
		if (clientJoinSpecs.size() != written) {
			// The text JOINs a type the parser did not record: refused, not answered as if the JOIN were not there. A
			// guard for a walk that stops part way: the one statement known to come here — a client's own JOIN without an
			// ON, of a type the server JOINed too — no longer does, since the server does not JOIN a type the FROM already
			// names (closing batch 17 review, subagent P2-2).
			throw new CmisInvalidArgumentException("The statement's JOINs were not all read (" + clientJoinSpecs.size()
					+ " of " + written + "), so it is refused rather than answered without them"
					+ (parseFailure != null ? ": " + parseFailure.getMessage() : ""));
		}
		for (JoinSpec join : clientJoinSpecs) {
			String queryName = types.get(join.alias);
			TypeDefinition joined = (queryName == null) ? null : queryObject.getTypeDefinitionFromQueryName(queryName);
			String named = (queryName != null) ? queryName : join.alias;
			if (joined == null || joined.getBaseTypeId() != BaseTypeId.CMIS_SECONDARY) {
				throw new CmisInvalidArgumentException("Only a JOIN with a secondary type is answered, and " + named
						+ " is not one, so the statement is refused rather than answered without the JOIN");
			}
			if (!"INNER".equalsIgnoreCase(join.kind)) {
				throw new CmisInvalidArgumentException("Only an INNER JOIN with a secondary type is answered, so the "
						+ join.kind + " JOIN with " + named + " is refused");
			}
			if (!joinsTheObjectIds(join, bound, types)) {
				throw new CmisInvalidArgumentException("A JOIN with a secondary type is answered only ON the FROM's "
						+ "cmis:objectId = the joined type's cmis:objectId, so the JOIN with " + named
						+ " is refused rather than answered without its condition");
			}
			refuseSubtypesAQueryIncludes(repositoryId, joined, named);
			bound.add(join.alias);
			filters.add(theDocumentsThatHave(repositoryId, joined));
		}
		narrowToTheRowsNamed(repositoryId, rowsNamed, filters);
		return filters;
	}

	/**
	 * The filter of a JOIN that narrows: the documents that have the type, as {@code ANY cmis:secondaryObjectTypeIds IN
	 * ('that type')} asks. The id escaped as the walker escapes the literal of ANY ... IN (walkString): unescaped, the
	 * colon of nemaki:commentable is a syntax error to Solr (measured on bedroom: 500, «Cannot parse»).
	 */
	private String theDocumentsThatHave(String repositoryId, TypeDefinition joined) {
		String field = SolrUtil.queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
		return SolrPredicateWalker.anyOf(field, java.util.Collections.singletonList(
				org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars(joined.getId()))).toString();
	}

	/**
	 * R217: the JOIN the server adds for a secondary type the statement names as a row — a column qualified by the type, in
	 * the SELECT list or the WHERE, or the type as the qualifier of IN_FOLDER, IN_TREE or CONTAINS — narrows the answer as
	 * the client's JOIN does, to what {@code ANY cmis:secondaryObjectTypeIds IN ('that type')} answers. The statement asked
	 * about that row, and a document without the type has none: {@code SELECT nemaki:commentable.nemaki:comment FROM
	 * cmis:document WHERE nemaki:comment IS NULL} answered 2,455 documents on bedroom, the same statement written with the
	 * JOIN 0 (measured 2026-10-30). A type with subtypes a query of it includes is refused as the client's JOIN is.
	 */
	private void narrowToTheRowsNamed(String repositoryId, Map<String, TypeDefinition> rowsNamed, List<String> filters) {
		for (TypeDefinition joined : rowsNamed.values()) {
			refuseSubtypesAQueryIncludes(repositoryId, joined, joined.getId() + " (the row a qualifier names)");
			filters.add(theDocumentsThatHave(repositoryId, joined));
		}
	}

	/**
	 * A type with subtypes a query of it includes: the JOIN asks for those too, and ANY ... IN ('that type') answers the
	 * type alone, so the two would part (closing batch 17 review, subagent P2-1 — JOIN cmis:secondary answered 0).
	 * Refused (400) rather than answered narrower than asked; a subtype the type system cannot say anything of is not
	 * one a query leaves out (500).
	 */
	private void refuseSubtypesAQueryIncludes(String repositoryId, TypeDefinition joined, String named) {
		List<TypeDefinitionContainer> descendants = typeManager.getTypesDescendants(repositoryId, joined.getId(),
				BigInteger.valueOf(-1), false);
		if (descendants == null) {
			// Not «no subtypes»: the type system did not answer.
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
					"The subtypes of " + named + " could not be read, so the JOIN was not answered");
		}
		for (TypeDefinitionContainer descendant : descendants) {
			TypeDefinition sub = (descendant == null) ? null : descendant.getTypeDefinition();
			if (sub == null) {
				// Not «a subtype a query leaves out»: the type system listed a subtype it could not say anything of
				// (TypeManagerImpl keeps a container without its definition), and skipping it answered the type alone
				// (closing batch 18 review, Codex P2 and subagent P3-2).
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
						"A subtype of " + named + " could not be read, so the JOIN was not answered");
			}
			if (!joined.getId().equals(sub.getId()) && Boolean.TRUE.equals(sub.isIncludedInSupertypeQuery())) {
				throw new CmisInvalidArgumentException("A JOIN with " + named + " would also ask for its subtype "
						+ sub.getId() + ", which this server does not answer, so the statement is refused");
			}
		}
	}

	/**
	 * ON {a row already read}.cmis:objectId = {the joined type}.cmis:objectId, in either order. A side is qualified by
	 * its row's alias or by its type's query name when that names one row only — the parser takes both (closing batch 17
	 * review, subagent P3-1: ON cmis:document.cmis:objectId = c.cmis:objectId under FROM cmis:document d was refused).
	 */
	private static boolean joinsTheObjectIds(JoinSpec join, Set<String> bound, Map<String, String> types) {
		ColumnReference left = join.onLeft;
		ColumnReference right = join.onRight;
		if (left == null || right == null || join.alias == null) {
			return false;
		}
		if (!PropertyIds.OBJECT_ID.equals(left.getPropertyQueryName())
				|| !PropertyIds.OBJECT_ID.equals(right.getPropertyQueryName())) {
			return false;
		}
		String l = rowOf(left.getQualifier(), types);
		String r = rowOf(right.getQualifier(), types);
		return (l != null && r != null)
				&& ((bound.contains(l) && join.alias.equals(r)) || (bound.contains(r) && join.alias.equals(l)));
	}

	/** The alias of the row a qualifier names: the alias itself, or a type's query name that one row only has. */
	private static String rowOf(String qualifier, Map<String, String> types) {
		if (qualifier == null) {
			return null;
		}
		if (types.containsKey(qualifier)) {
			return qualifier;
		}
		String row = null;
		for (Map.Entry<String, String> entry : types.entrySet()) {
			if (qualifier.equals(entry.getValue())) {
				if (row != null) {
					return null;
				}
				row = entry.getKey();
			}
		}
		return row;
	}

	/**
	 * Whether the type the FROM names first defines a property of that query name itself. The parser resolves the name
	 * there, and a JOIN of a secondary type defining a property of the same query name made the name ambiguous: the parse
	 * failed and the statement went to the manual fallback (closing batch 20 review, subagent P3-1 — a statement answered
	 * before was refused once a FROM after a newline was found too). A type the type system does not answer throws, and
	 * the caller's catch leaves the statement as written.
	 */
	private boolean fromTypeDefines(String repositoryId, String fromPart, String propertyName) {
		Matcher fromType = FROM_TYPE.matcher(fromPart);
		if (!fromType.find()) {
			return false;
		}
		TypeDefinition type = typeManager.getTypeByQueryName(repositoryId, fromType.group(1));
		return type != null && typeManager.getPropertyIdForQueryName(repositoryId, type, propertyName) != null;
	}

	/** Whether the text before WHERE (literals blanked) names the type, by its id or query name, as a type or alias. */
	private static boolean namedIn(String fromPart, TypeDefinition type) {
		for (String name : new String[] { type.getId(), type.getQueryName() }) {
			if (name != null && Pattern.compile("(?<![A-Za-z0-9_:.])" + Pattern.quote(name) + "(?![A-Za-z0-9_:.])")
					.matcher(fromPart).find()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The manual parser's translation when it covers the WHOLE WHERE clause; otherwise a refusal
	 * (R188).
	 *
	 * <p>The three places that fell back to {@code *:*} — or to whatever single predicate the manual
	 * parser recognised — come here. A statement is answered only with a translation of everything
	 * it asked; a WHERE clause this server cannot translate is refused with invalidArgument (HTTP
	 * 400), which says so, instead of being answered with fewer conditions than were asked.
	 */
	private String wholeWhereOrRefuse(String repositoryId, String statement, String why) {
		String manualWhereQuery = parseSecondaryTypeWhereClause(repositoryId, statement);
		if (manualWhereQuery != null && !manualWhereQuery.isEmpty()) {
			if (logger.isDebugEnabled()) {
				logger.debug("Using manually parsed WHERE clause: " + manualWhereQuery);
			}
			return manualWhereQuery;
		}
		String refusal = "The WHERE clause of this statement cannot be translated, so the statement is refused"
				+ " rather than answered without its conditions (" + why + ")";
		logger.warn(refusal);
		exceptionService.invalidArgument(refusal);
		// invalidArgument throws. If it ever stopped doing so, the statement must still not be answered.
		throw new org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException(refusal);
	}

	/**
	 * A CMIS string literal's content: single quotes, with {@code \'} and {@code \\} escaped by a backslash (CMIS 1.1,
	 * 2.1.14.2.4.3). Possessive, so a literal thousands of characters long cannot exhaust the stack on backtracking.
	 * The first version read SQL's {@code ''} instead, which is not CMIS's escape: {@code 'a\' ORDER BY x'} was cut in
	 * the middle (closing batch 2 review, subagent P3).
	 */
	private static final String LITERAL = "'((?:[^'\\\\]++|\\\\.)*+)'";
	/** An optional table qualifier ({@code d.} in {@code FROM cmis:document d}), which names the same property. */
	private static final String QUALIFIER = "(?:[A-Za-z_][A-Za-z0-9_]*\\.)?";
	private static final String PROPERTY = "([A-Za-z_][A-Za-z0-9_]*:[A-Za-z_][A-Za-z0-9_]*)";
	private static final Pattern WHERE_KEYWORD = Pattern.compile("(?i)(?<![A-Za-z0-9_:.])WHERE(?![A-Za-z0-9_:.])");
	private static final Pattern JOIN_KEYWORD = Pattern.compile("(?i)(?<![A-Za-z0-9_:.])JOIN(?![A-Za-z0-9_:.])");
	private static final Pattern FROM_KEYWORD = Pattern.compile("(?i)(?<![A-Za-z0-9_:.])FROM(?![A-Za-z0-9_:.])");
	/** A qualifier: a name before a dot (whitespace between allowed), or the first argument of IN_FOLDER, IN_TREE or CONTAINS. */
	private static final Pattern ROW_QUALIFIER = Pattern.compile("(?<![A-Za-z0-9_:.])([A-Za-z_][A-Za-z0-9_:]*)\\s*\\."
			+ "|(?<![A-Za-z0-9_:.])(?i:IN_FOLDER|IN_TREE|CONTAINS)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_:]*)\\s*,");
	private static final Pattern FROM_TYPE = Pattern.compile("(?i)(?<![A-Za-z0-9_:.])FROM\\s+([A-Za-z_][A-Za-z0-9_:]*)");
	private static final Pattern ORDER_BY_KEYWORD =
			Pattern.compile("(?i)(?<![A-Za-z0-9_:.])ORDER\\s+BY(?![A-Za-z0-9_:.])");

	/**
	 * Whether the statement has a WHERE clause: the keyword, standing alone, outside a string literal
	 * (R188).
	 *
	 * <p>Read from the TEXT because the parse cannot tell it: a statement whose WHERE clause failed
	 * to parse leaves no tree, exactly like one that has none. A value that says "where" is inside a
	 * literal and is not a clause; a property whose name contains the word does not stand alone.
	 */
	static boolean hasWhereClause(String statement) {
		return statement != null && WHERE_KEYWORD.matcher(withoutStringLiterals(statement)).find();
	}

	/**
	 * The WHERE clause's text — after the keyword, before ORDER BY — with parentheses that enclose the
	 * whole of it removed. Null when the statement has no WHERE clause.
	 */
	static String whereClauseText(String statement) {
		if (statement == null) {
			return null;
		}
		String blanked = withoutStringLiterals(statement);
		Matcher where = WHERE_KEYWORD.matcher(blanked);
		if (!where.find()) {
			return null;
		}
		int start = where.end();
		Matcher orderBy = ORDER_BY_KEYWORD.matcher(blanked);
		int end = orderBy.find(start) ? orderBy.start() : statement.length();
		String clause = statement.substring(start, end).trim();
		String blankedClause = blanked.substring(start, end).trim();
		while (clause.length() >= 2 && clause.startsWith("(") && clause.endsWith(")")
				&& theFirstParenthesisClosesAtTheEnd(blankedClause)) {
			clause = clause.substring(1, clause.length() - 1).trim();
			blankedClause = blankedClause.substring(1, blankedClause.length() - 1).trim();
		}
		return clause;
	}

	/** Whether the opening parenthesis at index 0 is closed by the last character, not before it. */
	private static boolean theFirstParenthesisClosesAtTheEnd(String blankedClause) {
		int depth = 0;
		for (int i = 0; i < blankedClause.length(); i++) {
			char c = blankedClause.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i == blankedClause.length() - 1;
				}
			}
		}
		return false;
	}

	/**
	 * The statement with every string literal's content blanked — same length, same positions. Read the CMIS way: a
	 * backslash escapes the character after it, and an unterminated literal runs to the end.
	 */
	private static String withoutStringLiterals(String statement) {
		StringBuilder blanked = new StringBuilder(statement);
		boolean inLiteral = false;
		for (int i = 0; i < statement.length(); i++) {
			char c = statement.charAt(i);
			if (!inLiteral) {
				inLiteral = c == '\'';
				continue;
			}
			if (c == '\'') {
				inLiteral = false;
				continue;
			}
			blanked.setCharAt(i, ' ');
			if (c == '\\' && i + 1 < statement.length()) {
				blanked.setCharAt(++i, ' ');
			}
		}
		return blanked.toString();
	}

	/** A literal's content with its CMIS escapes undone. */
	private static String unescapeLiteral(String content) {
		return content.replaceAll("\\\\(.)", "$1");
	}

	/**
	 * The values of an IN list — {@code 'a', 'b'} — or null when it is anything else. Each value is a CMIS literal, so
	 * an escaped quote stays inside its value (closing batch 2 review, Codex P1: a pattern that took every quoted run
	 * split one value in two and widened the query).
	 */
	static List<String> inListValues(String list) {
		Matcher value = Pattern.compile("\\s*" + LITERAL + "\\s*(,|$)", Pattern.DOTALL).matcher(list);
		List<String> values = new ArrayList<>();
		int at = 0;
		boolean another = true;
		while (another) {
			if (!value.find(at) || value.start() != at) {
				return null;
			}
			values.add(unescapeLiteral(value.group(1)));
			at = value.end();
			another = !value.group(2).isEmpty();
		}
		return at == list.length() ? values : null;
	}

	/** The definition {@code secondary} carries for a property, by query name or id; null when it carries none. */
	private static PropertyDefinition<?> definitionIn(TypeDefinition secondary, String propertyName) {
		if (secondary == null || secondary.getPropertyDefinitions() == null) {
			return null;
		}
		for (PropertyDefinition<?> definition : secondary.getPropertyDefinitions().values()) {
			if (propertyName.equals(definition.getQueryName()) || propertyName.equals(definition.getId())) {
				return definition;
			}
		}
		return null;
	}

	/**
	 * Whether a property can be compared with a quoted literal by {@code =}, {@code <>} or LIKE — the walker's rule:
	 * single-valued, and a string, HTML, id or URI.
	 */
	private static boolean comparableAsText(PropertyDefinition<?> definition) {
		return definition != null
				&& definition.getCardinality() == org.apache.chemistry.opencmis.commons.enums.Cardinality.SINGLE
				&& textual(definition);
	}

	/** A string, HTML, id or URI property — the types a quoted literal is compared with. */
	private static boolean textual(PropertyDefinition<?> definition) {
		org.apache.chemistry.opencmis.commons.enums.PropertyType type = definition.getPropertyType();
		return type == org.apache.chemistry.opencmis.commons.enums.PropertyType.STRING
				|| type == org.apache.chemistry.opencmis.commons.enums.PropertyType.HTML
				|| type == org.apache.chemistry.opencmis.commons.enums.PropertyType.ID
				|| type == org.apache.chemistry.opencmis.commons.enums.PropertyType.URI;
	}

	/**
	 * The values of an IN list of bare INTEGER, DECIMAL or BOOLEAN literals — {@code 1, -2} or {@code TRUE} — each in the
	 * form CMIS writes it for that type; null when the list is anything else, or the type is none of the three.
	 */
	static List<String> plainListValues(String list, org.apache.chemistry.opencmis.commons.enums.PropertyType type) {
		Pattern literal;
		if (type == org.apache.chemistry.opencmis.commons.enums.PropertyType.INTEGER) {
			literal = Pattern.compile("[+-]?[0-9]+");
		} else if (type == org.apache.chemistry.opencmis.commons.enums.PropertyType.DECIMAL) {
			literal = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
		} else if (type == org.apache.chemistry.opencmis.commons.enums.PropertyType.BOOLEAN) {
			literal = Pattern.compile("(?i)TRUE|FALSE");
		} else {
			return null;
		}
		List<String> values = new ArrayList<>();
		for (String part : list.split(",", -1)) {
			String value = part.trim();
			if (!literal.matcher(value).matches()) {
				return null;
			}
			if (type == org.apache.chemistry.opencmis.commons.enums.PropertyType.BOOLEAN) {
				values.add(value.toLowerCase(java.util.Locale.ROOT));
				continue;
			}
			// A number is read the way the walker reads it (walkNumber: Double when it has a point or an exponent,
			// Long otherwise) and written back from that value. Compared as written, `IN (+1)`, `IN (01)` and a
			// DECIMAL's `IN (2.50)` answered 0 here while the walker matched the same value (closing batch 5 review,
			// subagent P3-4).
			try {
				values.add(value.contains(".") || value.contains("e") || value.contains("E")
						? Double.valueOf(value).toString() : Long.valueOf(value).toString());
			} catch (NumberFormatException outOfRange) {
				return null;
			}
		}
		return values;
	}

	/** The Solr field a property is indexed under, escaped once — the rule the walker uses. */
	private String fieldFor(String repositoryId, String propertyQueryName) {
		return SolrUtil.queryableFieldName(solrUtil.getPropertyNameInSolr(repositoryId, propertyQueryName));
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
			// The WHOLE WHERE clause, and every pattern below must match ALL of it (R188). They used
			// find(), so the first predicate a pattern recognised was returned and the rest of the
			// clause discarded: `WHERE nemaki:tag = 'x' AND nemaki:dueDate IS NULL` came back as a
			// successful query for tag alone. A clause these patterns cannot cover whole answers null,
			// and the caller refuses the statement.
			String whereClause = whereClauseText(statement);
			if (whereClause == null) {
				return null;
			}

			// cmis:secondaryObjectTypeIds — the one cmis: property this fallback answers for.
			Matcher anyIn = Pattern.compile("ANY\\s+" + QUALIFIER + "cmis:secondaryObjectTypeIds\\s+IN\\s*\\((.*)\\)",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (anyIn.matches()) {
				List<String> values = inListValues(anyIn.group(1));
				if (values == null || values.isEmpty()) {
					return null;
				}
				// Each type id quoted as a phrase, so its whole content is a literal term and cannot inject Solr
				// syntax or boolean operators into the (...) group.
				StringBuilder solrQuery = new StringBuilder("secondary_object_type_ids:(");
				for (int i = 0; i < values.size(); i++) {
					if (i > 0) {
						solrQuery.append(" OR ");
					}
					solrQuery.append(jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(values.get(i)));
				}
				return solrQuery.append(")").toString();
			}
			Matcher secondaryEquals = Pattern.compile(QUALIFIER + "cmis:secondaryObjectTypeIds\\s*=\\s*" + LITERAL,
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (secondaryEquals.matches()) {
				return "secondary_object_type_ids:"
						+ jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(unescapeLiteral(secondaryEquals.group(1)));
			}

			// Every other property this clause names must be one a SECONDARY TYPE defines (closing batch 2 review,
			// both reviewers, P1). This fallback exists for those — a statement naming a secondary type's property
			// without the JOIN fails the strict parse — and the gate used to be 「the name is not cmis:」 alone, so a
			// property no type defines (a typo, a type since deleted) was translated into a field nothing is indexed
			// under: `IS NULL` then matched every document, which is R188's answer again. Names are read outside
			// the literals, so a value that looks like prefix:name decides nothing.
			// AND the property must be one a query may use, the way the walker would use it (closing batch 3 review,
			// Codex P1): the gate asked only whether some secondary type DEFINES the name, so a property that is not
			// queryable, a multi-valued one compared with `=`, or a number compared with LIKE was answered here although
			// the walker refuses each. The definition is kept, and every production below checks it.
			Matcher named = Pattern.compile("(?<![A-Za-z0-9_:])([A-Za-z_][A-Za-z0-9_]*:[A-Za-z_][A-Za-z0-9_]*)")
					.matcher(withoutStringLiterals(whereClause));
			Map<String, PropertyDefinition<?>> definitions = new HashMap<>();
			while (named.find()) {
				String propertyName = named.group(1);
				if (propertyName.startsWith("cmis:") || typeManager == null) {
					return null;
				}
				TypeDefinition secondary;
				try {
					secondary = typeManager.findSecondaryTypeByPropertyQueryName(repositoryId, propertyName);
				} catch (RuntimeException unreadable) {
					// The type system could not be searched: not a finding that no type defines the name, so not the
					// client's 400 (closing batch 3 review, subagent P3-2).
					throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
							"The type definitions could not be read, so the query was not run: " + unreadable.getMessage(),
							unreadable);
				}
				PropertyDefinition<?> definition = definitionIn(secondary, propertyName);
				if (definition == null || !Boolean.TRUE.equals(definition.isQueryable())) {
					return null;
				}
				definitions.put(propertyName, definition);
			}
			if (definitions.isEmpty()) {
				return null;
			}

			// One predicate on that property, matched over the whole clause. The field is the one the property's
			// DEFINITION is indexed under — by its id. The name as written may be its query name, and the index does not
			// know that name, so `IS NULL` on it matched every document (closing batch 3 review, subagent P3-5).
			//
			// ANY ... IN over a multi-valued property a secondary type defines: CMIS's way to ask a multi-valued
			// property, which the walker answers too. Without it the statement was refused although it can be
			// translated whole (closing batch 3 review, subagent P2-4).
			Matcher anyInProperty = Pattern.compile("ANY\\s+" + QUALIFIER + PROPERTY + "\\s+IN\\s*\\((.*)\\)",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (anyInProperty.matches()) {
				PropertyDefinition<?> definition = definitions.get(anyInProperty.group(1));
				if (definition.getCardinality() != org.apache.chemistry.opencmis.commons.enums.Cardinality.MULTI) {
					return null;
				}
				// Quoted literals for a text property, bare numbers or booleans for a numeric or boolean one — the
				// literal CMIS writes for each (closing batch 4 review, Codex P2: only quoted text was read, so
				// `ANY acme:ratings IN (1, 2)` was refused). DATETIME is not translated here: R187.
				List<String> values = textual(definition) ? inListValues(anyInProperty.group(2))
						: plainListValues(anyInProperty.group(2), definition.getPropertyType());
				if (values == null || values.isEmpty()) {
					return null;
				}
				StringBuilder solrQuery = new StringBuilder(fieldFor(repositoryId, definition.getId())).append(":(");
				for (int i = 0; i < values.size(); i++) {
					if (i > 0) {
						solrQuery.append(" OR ");
					}
					solrQuery.append(jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(values.get(i)));
				}
				return solrQuery.append(")").toString();
			}
			Matcher like = Pattern.compile(QUALIFIER + PROPERTY + "\\s+LIKE\\s+" + LITERAL,
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (like.matches()) {
				// The walker's rule: LIKE on a single-valued string property only.
				if (!comparableAsText(definitions.get(like.group(1)))) {
					return null;
				}
				// SQL '%' -> Solr '*', SQL '_' -> Solr '?', every other char escaped one by one. The RAW literal: the
				// conversion reads CMIS's escapes itself, so an escaped % stays a percent sign.
				return fieldFor(repositoryId, definitions.get(like.group(1)).getId()) + ":" + likeToEscapedSolrWildcard(like.group(2));
			}
			Matcher equals = Pattern.compile(QUALIFIER + PROPERTY + "\\s*=\\s*" + LITERAL,
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (equals.matches()) {
				// A quoted literal compared with `=`: a single-valued string property (a multi-valued one takes ANY).
				if (!comparableAsText(definitions.get(equals.group(1)))) {
					return null;
				}
				return fieldFor(repositoryId, definitions.get(equals.group(1)).getId()) + ":"
						+ jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(unescapeLiteral(equals.group(2)));
			}
			Matcher notEquals = Pattern.compile(QUALIFIER + PROPERTY + "\\s*(?:!=|<>)\\s*" + LITERAL,
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (notEquals.matches()) {
				if (!comparableAsText(definitions.get(notEquals.group(1)))) {
					return null;
				}
				// A comparison with a property that has no value is not a match (CMIS follows SQL here), so the
				// document must HAVE the field as well as not have this value — a bare negation also matched every
				// document that never set it (closing batch 2 review, Codex P1).
				String field = fieldFor(repositoryId, definitions.get(notEquals.group(1)).getId());
				return "+" + field + ":[* TO *] -" + field + ":"
						+ jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escapeAndQuote(unescapeLiteral(notEquals.group(2)));
			}
			Matcher isNull = Pattern.compile(QUALIFIER + PROPERTY + "\\s+IS\\s+NULL",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (isNull.matches()) {
				// Escaped once, through the same method the walker uses (round-5 review).
				return "-" + fieldFor(repositoryId, definitions.get(isNull.group(1)).getId()) + ":[* TO *]";
			}
			Matcher isNotNull = Pattern.compile(QUALIFIER + PROPERTY + "\\s+IS\\s+NOT\\s+NULL",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(whereClause);
			if (isNotNull.matches()) {
				return fieldFor(repositoryId, definitions.get(isNotNull.group(1)).getId()) + ":[* TO *]";
			}

			return null;

		} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException failure) {
			// A FAILURE to read what the gate needs: answering null would turn it into the client's 400.
			throw failure;
		} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException refusal) {
			// A REFUSAL, not a parse failure: answering null here would make the caller fall back to
			// "*:*" — the whole WHERE clause dropped, every document returned. The refusal has to
			// reach the client (round-7 review).
			throw refusal;
		} catch (Exception e) {
			if (jp.aegif.nemaki.cmis.aspect.type.DefinitionsUnreadableException.isIn(e)) {
				// The field of a property could not be read (closing batch 4 review, subagent P2-1 (a)).
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
						"The type definitions could not be read, so the query was not run: " + e.getMessage(), e);
			}
			logger.warn("Error parsing secondary type WHERE clause: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Convert a SQL LIKE pattern to a Solr wildcard pattern safely. SQL
	 * wildcards map to Solr wildcards ({@code %}→{@code *}, {@code _}→
	 * {@code ?}); every other character is individually escaped via
	 * {@link jp.aegif.nemaki.rag.util.SolrQuerySanitizer#escape(String)} so
	 * it cannot inject Solr query syntax. Processing char-by-char avoids any
	 * sentinel/marker that a field value could collide with.
	 */
	static String likeToEscapedSolrWildcard(String pattern) {
		if (pattern == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(pattern.length() * 2);
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '\\' && i + 1 < pattern.length()) {
				// CMIS's escape: the character after a backslash is literal — \% and \_ are a percent sign and an
				// underscore, not wildcards (closing batch 3 review, Codex P1: the fallback unescaped first and then
				// read the wildcards, so an escaped % became one again and the query matched more than was asked).
				appendLiteral(sb, pattern.charAt(++i));
			} else if (c == '%') {
				sb.append('*');
			} else if (c == '_') {
				sb.append('?');
			} else {
				appendLiteral(sb, c);
			}
		}
		return sb.toString();
	}

	private static void appendLiteral(StringBuilder sb, char c) {
		if (Character.isWhitespace(c)) {
			// Whitespace is a token separator in the Lucene query parser
			// (an implicit OR between terms). SolrQuerySanitizer.escape
			// does NOT escape it, so escape it here to keep the value a
			// single term — otherwise 'foo OR bar' would become two
			// clauses joined by a live OR operator.
			sb.append('\\').append(c);
		} else {
			sb.append(jp.aegif.nemaki.rag.util.SolrQuerySanitizer.escape(String.valueOf(c)));
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

	public void setAclExpander(jp.aegif.nemaki.rag.acl.ACLExpander aclExpander) {
		this.aclExpander = aclExpander;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setPropagationStaleness(AclPropagationStaleness propagationStaleness) {
		this.propagationStaleness = propagationStaleness;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}
	/** The largest query page assembled in memory in one response. */
	private static final int MAX_QUERY_PAGE = 10_000;

	/** A non-positive maxItems is not "nothing": it is the ordinary default page. */
	private static final int DEFAULT_QUERY_PAGE_FOR_NON_POSITIVE = 100;

	/** Converts a client's query maxItems without truncating it into 0 or a negative. */
	private static int clampQueryPage(java.math.BigInteger maxItems) {
		if (maxItems == null || maxItems.signum() <= 0) {
			// The default page, matching the children listing and the compile service. A
			// query asked with maxItems=0 used to answer with an empty page here while the
			// same value returned 100 items one service over.
			return DEFAULT_QUERY_PAGE_FOR_NON_POSITIVE;
		}
		return maxItems.compareTo(java.math.BigInteger.valueOf(MAX_QUERY_PAGE)) >= 0
				? MAX_QUERY_PAGE
				: maxItems.intValue();
	}

	/** A skip count is a position: never negative, never truncated. */
	private static int clampQuerySkip(java.math.BigInteger skipCount) {
		if (skipCount == null || skipCount.signum() <= 0) {
			return 0;
		}
		return skipCount.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) >= 0
				? Integer.MAX_VALUE
				: skipCount.intValue();
	}

}
