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
import org.apache.solr.client.solrj.SolrClientException;
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
			// TODO Auto-generated method stub
			
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

		SolrClient solrClient = solrUtil.getSolrClient();
		// replacing backslashed for TIMESTAMP only
		Pattern time_p = Pattern.compile("(TIMESTAMP\\s?'[\\-\\d]*T\\d{2})\\\\:(\\d{2})\\\\:([\\.\\d]*Z')", Pattern.CASE_INSENSITIVE);
		Matcher time_m = time_p.matcher(statement);
		statement = time_m.replaceAll("$1:$2:$3");

		// TODO walker is required?

		QueryUtilStrict util = new QueryUtilStrict(statement, new CmisTypeManager(repositoryId, typeManager), null);
		QueryObject queryObject = util.getQueryObject();
		// Get where caluse as Tree
		Tree whereTree = null;
		try {
			util.processStatement();
			Tree tree = util.parseStatement();
			whereTree = extractWhereTree(tree);
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Build solr statement of WHERE
		String whereQueryString = "";
		if (whereTree == null || whereTree.isNil()) {
			whereQueryString = "*:*";
		} else {
			try {
				SolrPredicateWalker solrPredicateWalker = new SolrPredicateWalker(repositoryId,
						queryObject, solrUtil, contentService);
				Query whereQuery = solrPredicateWalker.walkPredicate(whereTree);
				whereQueryString = whereQuery.toString();
				} catch (Exception e) {
				e.printStackTrace();
				// TODO Output more detailed exception
				exceptionService.invalidArgument("Invalid CMIS SQL statement!");
			}
		}

		// Build solr query of FROM
		String fromQueryString = "";
		
		String repositoryQuery = "repository_id:" + repositoryId;
		
		fromQueryString += repositoryQuery + " AND ";
		TypeDefinition td = null;

		td = queryObject.getMainFromName();

		// includedInSupertypeQuery
		List<TypeDefinitionContainer> typeDescendants = typeManager
				.getTypesDescendants(repositoryId, td.getId(), BigInteger.valueOf(-1), false);
		Iterator<TypeDefinitionContainer> iterator = typeDescendants.iterator();
		List<String> tables = new ArrayList<String>();
		while (iterator.hasNext()) {
			TypeDefinition descendant = iterator.next().getTypeDefinition();
			if (td.getId() != descendant.getId()) {
				boolean isq = (descendant.isIncludedInSupertypeQuery() == null) ? false
						: descendant.isIncludedInSupertypeQuery();
				if (!isq)
					continue;
			}
			String table = descendant.getQueryName();
			tables.add(table.replaceAll(":", "\\\\:"));
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
		
		logger.info(solrQuery.toString());
		logger.info("statement: " + statement);
		logger.info("skipCount: " + skipCount);
		logger.info("maxItems: " + maxItems);
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
			logger.info("Executing Solr query: " + solrQuery.toString());
			resp = solrClient.query(solrQuery);
			logger.info("Solr query executed successfully, response: " + (resp != null ? "not null" : "null"));
		} catch (SolrClientException e) {
			logger.error("Solr query failed: " + e.getMessage());
			e.printStackTrace();
		}

		long numFound =0;
		// Output search results to ObjectList
		logger.info("Solr response check: resp=" + (resp != null ? "not null" : "null") + 
			    ", results=" + (resp != null && resp.getResults() != null ? "not null" : "null") +
			    ", numFound=" + (resp != null && resp.getResults() != null ? resp.getResults().getNumFound() : "N/A"));
		if (resp != null && resp.getResults() != null
				&& resp.getResults().getNumFound() != 0) {
			SolrDocumentList docs = resp.getResults();
			numFound = docs.getNumFound();

			List<Content> contents = new ArrayList<Content>();
			for (SolrDocument doc : docs) {
				String docId = (String) doc.getFieldValue("object_id");
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
				System.out.println("DEBUG SolrQueryProcessor: Before permission filtering - includeAllowableActions=" + includeAllowableActions + ", contents.size=" + contents.size() + ", user=" + callContext.getUsername());
				
				// Filter out by permissions
				List<Content> permitted = permissionService.getFiltered(
						callContext, repositoryId, contents);
				
				// Debug logging after permission filtering
				System.out.println("DEBUG SolrQueryProcessor: After permission filtering - permitted.size=" + permitted.size() + ", filtered out=" + (contents.size() - permitted.size()));

				// Filter return value with SELECT clause
				Map<String, String> requestedWithAliasKey = queryObject
						.getRequestedPropertiesByAlias();
				String filter = null;
				if (!requestedWithAliasKey.keySet().contains("*")) {
					// Create filter(queryNames) from query aliases
					filter = StringUtils.join(requestedWithAliasKey.values(), ",");
				}
				

				// Build ObjectList
				String orderBy = orderBy(queryObject);
				// Build ObjectList with original includeAllowableActions parameter for final response
				ObjectList result = compileService.compileObjectDataListForSearchResult(
						callContext, repositoryId, permitted, filter,
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
