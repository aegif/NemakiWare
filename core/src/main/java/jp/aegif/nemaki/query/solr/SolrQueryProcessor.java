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
package jp.aegif.nemaki.query.solr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.query.QueryProcessor;
import jp.aegif.nemaki.repository.type.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.util.SortUtil;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.chemistry.opencmis.server.support.query.QueryObject.SortSpec;
import org.apache.chemistry.opencmis.server.support.query.QueryUtilStrict;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

public class SolrQueryProcessor implements QueryProcessor {

	private ContentService contentService;
	private PermissionService permissionService;
	private CompileObjectService compileObjectService;
	private ExceptionService exceptionService;
	private SolrUtil solrUtil;
	private SortUtil sortUtil;
	private static final Log logger = LogFactory
			.getLog(SolrQueryProcessor.class);

	public SolrQueryProcessor() {

	}

	@Override
	public ObjectList query(TypeManager typeManager, CallContext callContext,
			String username, String id, String statement,
			Boolean searchAllVersions, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount) {

		SolrServer solrServer = solrUtil.getSolrServer();

		// TODO walker is required?
		QueryUtilStrict util = new QueryUtilStrict(statement, typeManager, null);
		QueryObject queryObject = util.getQueryObject();

		// Get where caluse as Tree
		Tree whereTree = null;
		try {
			util.processStatement();
			Tree tree = util.parseStatement();
			for (int i = 0; i < tree.getChildCount(); i++) {
				Tree t = tree.getChild(i);
				if ("WHERE".equals(t.getText())) {
					whereTree = t.getChild(0);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Build solr statement of WHERE
		String whereQueryString = "";
		if (whereTree == null || whereTree.isNil()) {
			whereQueryString = "*:*";
		} else {
			try {
				SolrPredicateWalker solrPredicateWalker = new SolrPredicateWalker(
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
		TypeDefinition td = queryObject.getMainFromName();
		// includedInSupertypeQuery
		List<TypeDefinitionContainer> typeDescendants = typeManager
				.getTypesDescendants(td.getId(), BigInteger.valueOf(-1), false);
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
		Term t = new Term(
				solrUtil.getPropertyNameInSolr(PropertyIds.OBJECT_TYPE_ID),
				StringUtils.join(tables, " "));
		fromQueryString = new TermQuery(t).toString();

		// Execute query
		SolrQuery solrQuery = new SolrQuery();
		solrQuery.setQuery(whereQueryString);
		solrQuery.setFilterQueries(fromQueryString);

		QueryResponse resp = null;
		try {
			resp = solrServer.query(solrQuery);
		} catch (SolrServerException e) {
			e.printStackTrace();
		}

		// Output search results to ObjectList
		if (resp != null && resp.getResults() != null
				&& resp.getResults().getNumFound() != 0) {
			SolrDocumentList docs = resp.getResults();

			List<Content> contents = new ArrayList<Content>();
			for (SolrDocument doc : docs) {
				String docId = (String) doc.getFieldValue("id");
				Content c = contentService.getContent(docId);

				// When for some reason the content is missed, pass through
				if (c == null) {
					logger.warn("[objectId=" + docId
							+ "]It is missed in DB but still rests in Solr.");
				} else {
					contents.add(c);
				}

			}

			// Filter out by permissions
			List<Content> permitted = permissionService.getFiltered(
					callContext, contents);

			// Filter return value with SELECT clause
			Map<String, String> requestedWithAliasKey = queryObject
					.getRequestedPropertiesByAlias();
			String filter = null;
			if (!requestedWithAliasKey.keySet().contains("*")) {
				// Create filter(queryNames) from query aliases
				filter = StringUtils.join(requestedWithAliasKey.values(), ",");
			}

			// Build ObjectList
			ObjectList result = compileObjectService.compileObjectDataList(
					callContext, permitted, filter, includeAllowableActions,
					includeRelationships, renditionFilter, false, maxItems,
					skipCount, false);

			// Sort
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
			sortUtil.sort(result.getObjects(), orderBy);

			return result;
		} else {
			ObjectListImpl nullList = new ObjectListImpl();
			nullList.setHasMoreItems(false);
			nullList.setNumItems(BigInteger.ZERO);
			return nullList;
		}
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public void setCompileObjectService(
			CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	public void setSortUtil(SortUtil sortUtil) {
		this.sortUtil = sortUtil;
	}
}