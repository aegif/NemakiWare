package jp.aegif.nemaki.query.solr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.query.QueryProcessor;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.node.ContentService;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.support.query.CmisQueryWalker;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.chemistry.opencmis.server.support.query.QueryUtil;
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

/**
 * QueryProcessor class for Solr
 * 
 * @author linzhixing
 * 
 */
public class SolrQueryProcessor implements QueryProcessor {

	private ContentService contentService;
	private PermissionService permissionService;
	private CompileObjectService compileObjectService;
	private SolrServer solrServer;
	private QueryObject queryObject;
	private static final Log log = LogFactory.getLog(SolrQueryProcessor.class);

	public SolrQueryProcessor() {
		solrServer = SolrUtil.getSolrServer();
	}

	public ObjectList query(TypeManager typeManager, CallContext callContext,
			String username, String id, String statement,
			Boolean searchAllVersions, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount) {

		ObjectListImpl objectList = new ObjectListImpl();
		objectList.setObjects(new ArrayList<ObjectData>());

		// queryObject includes the SQL information
		queryObject = new QueryObject(typeManager);
		QueryUtil util = new QueryUtil();
		CmisQueryWalker walker = null;

		// If statement is invalid, trhow exception
		walker = util
				.traverseStatementAndCatchExc(statement, queryObject, null);

		// "WHERE" clause to Lucene query
		String whereQueryString = "";
		Tree whereTree = walker.getWherePredicateTree();
		if (whereTree == null || whereTree.isNil()) {
			whereQueryString = "*:*";
		} else {
			SolrPredicateWalker solrPredicateWalker = new SolrPredicateWalker(
					queryObject);
			Query whereQuery = solrPredicateWalker.walkPredicate(whereTree);
			whereQueryString = whereQuery.toString();
		}

		// "FROM" clause to Lucene query
		String fromQueryString = "";

		TypeDefinition td = queryObject.getMainFromName();
		String fromTable = td.getId();
		Term t = new Term("type", SolrUtil.getSolrName(fromTable));
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
		if (resp != null & resp.getResults() != null
				&& resp.getResults().getNumFound() != 0) {
			SolrDocumentList docs = resp.getResults();
			List<ObjectData> dataList = new ArrayList<ObjectData>();

			List<Content> contents = new ArrayList<Content>();
			for (SolrDocument doc : docs) {
				String docId = (String) doc.getFieldValue("id");
				Content c = contentService.getContentAsEachBaseType(docId);
				contents.add(c);
			}

			// Filter out by permissions
			List<Content> filtered = permissionService.getFiltered(callContext,
					contents);
			if (filtered == null) {
				objectList.setNumItems(BigInteger.ZERO);
				return objectList;
			}

			// Filter return value with SELECT clause
			for (Content c : filtered) {
				// FIXME parameter is hard-fixed.
				ObjectDataImpl data = (ObjectDataImpl) compileObjectService
						.compileObjectData(callContext, c, null,
								includeAllowableActions, true);
				dataList.add(data);
			}
			// Add an ObjectData to the list
			objectList.getObjects().addAll(dataList);
		}
		objectList.setNumItems(BigInteger.valueOf(objectList.getObjects()
				.size()));
		return objectList;
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
}