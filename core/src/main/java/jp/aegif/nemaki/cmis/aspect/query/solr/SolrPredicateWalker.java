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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Folder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictLexer;
import org.apache.chemistry.opencmis.server.support.query.CmisSelector;
import org.apache.chemistry.opencmis.server.support.query.ColumnReference;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.chemistry.opencmis.server.support.query.TextSearchLexer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

/**
 * CMIS to Solr parser class for WHERE clause of query
 *
 * @author linzhixing
 *
 */
public class SolrPredicateWalker{

	private static final Log log = LogFactory.getLog(SolrPredicateWalker.class);

	private final String repositoryId;
	private final SolrUtil solrUtil;
	private final QueryObject queryObject;
	private final ContentService contentService;

	public static final String FLD = "field";
	public static final String CND = "cond";

	public SolrPredicateWalker(String repositoryId, QueryObject queryObject, SolrUtil solrUtil, ContentService contentService) {
		this.repositoryId = repositoryId;
		this.queryObject = queryObject;
		this.solrUtil = solrUtil;
		this.contentService = contentService;
	}

	public Query walkPredicate(Tree node) {
		switch (node.getType()) {
		// Boolean walks
		case CmisQlStrictLexer.NOT:
			return walkNot(node.getChild(0));
		case CmisQlStrictLexer.AND:
			return walkAnd(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.OR:
			return walkOr(node.getChild(0), node.getChild(1));
			// Comparison walks
		case CmisQlStrictLexer.EQ:
			return walkEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NEQ:
			return walkNotEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.GT:
			return walkGreaterThan(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.GTEQ:
			return walkGreaterOrEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.LT:
			return walkLessThan(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.LTEQ:
			return walkLessOrEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.LIKE:
			return walkLike(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NOT_LIKE:
			return walkNotLike(node.getChild(0), node.getChild(1));
			// Multiple value type walks
		case CmisQlStrictLexer.IN:
			return walkIn(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NOT_IN:
			return walkNotIn(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.ANY:
			return walkInAny(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NOT_IN_ANY:
			return walkNotInAny(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.IS_NULL:
			return walkIsNull(node.getChild(0));
		case CmisQlStrictLexer.IS_NOT_NULL:
			return walkIsNotNull(node.getChild(0));
			// GetChildren type walks
		case CmisQlStrictLexer.IN_FOLDER:
			if (node.getChildCount() == 1) {
				return walkInFolder(null, node.getChild(0));
			} else {
				return walkInFolder(node.getChild(0), node.getChild(1));
			}
		case CmisQlStrictLexer.IN_TREE:
			if (node.getChildCount() == 1) {
				return walkInTree(null, node.getChild(0));
			} else {
				return walkInTree(node.getChild(0), node.getChild(1));
			}
			// Full-text search type walk
		case CmisQlStrictLexer.CONTAINS:
			if (node.getChildCount() == 1) {
				return walkContains(null, node.getChild(0));
			} else {
				return walkContains(node.getChild(0), node.getChild(1));
			}
		default:
			return null;
		}
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of Boolean walks
	// //////////////////////////////////////////////////////////////////////////////
	private BooleanQuery walkNot(Tree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkPredicate(node), Occur.MUST_NOT);
		return builder.build();
	}

	private BooleanQuery walkOr(Tree leftNode, Tree rightNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkPredicate(leftNode), Occur.SHOULD);
			builder.add(walkPredicate(rightNode), Occur.SHOULD);
		return builder.build();
	}

	private BooleanQuery walkAnd(Tree leftNode, Tree rightNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkPredicate(leftNode), Occur.MUST);
			builder.add(walkPredicate(rightNode), Occur.MUST);
		return builder.build();
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of Comparison walks
	// //////////////////////////////////////////////////////////////////////////////
	private Query walkEquals(Tree leftNode, Tree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		Term term = new Term(map.get(FLD), map.get(CND));
		Query q = new TermQuery(term);
		return q;
	}

	private Query walkNotEquals(Tree leftNode, Tree rightNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkEquals(leftNode, rightNode), Occur.MUST_NOT);
		return builder.build();
	}

	private Query walkGreaterThan(Tree leftNode, Tree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD),
				new BytesRef(map.get(CND)), null, false, false);
		return t;
	}

	private Query walkGreaterOrEquals(Tree leftNode, Tree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD),
				new BytesRef(map.get(CND)), null, true, false);
		return t;
	}

	private Query walkLessThan(Tree leftNode, Tree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD), null,
				new BytesRef(map.get(CND)), false, false);
		return t;
	}

	private Query walkLessOrEquals(Tree leftNode, Tree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD), null,
				new BytesRef(map.get(CND)), false, true);
		return t;
	}

	/**
	 * TODO Implement check for each kind of literal
	 * Parse field name & condition value. Field name is prepared for Solr
	 * query.
	 *
	 * @param leftNode
	 * @param rightNode
	 * @return
	 */
	private HashMap<String, String> walkCompareInternal(Tree leftNode,
			Tree rightNode) {
		HashMap<String, String> map = new HashMap<String, String>();

		String left = solrUtil.convertToString(leftNode);
		String right = safeWalkExprToString(rightNode);

		map.put(FLD, ClientUtils.escapeQueryChars(solrUtil.getPropertyNameInSolr(repositoryId, left)));
		map.put(CND, right);
		return map;
	}

	private Query walkLike(Tree colNode, Tree stringNode) {
		// Check for CMIS SQL specification
		Object rVal = walkExpr(stringNode);
		if (!(rVal instanceof String)) {
			throw new IllegalStateException(
					"LIKE operator requires String literal on right hand side.");
		}
		ColumnReference colRef = getColumnReference(colNode);
		String colRefName = colRef.getName();
		TypeDefinition td = colRef.getTypeDefinition();
		Map<String, PropertyDefinition<?>> pds = td.getPropertyDefinitions();
		PropertyDefinition<?> pd = pds.get(colRefName);
		PropertyType propType = pd.getPropertyType();
		if (propType != PropertyType.STRING && propType != PropertyType.HTML
				&& propType != PropertyType.ID && propType != PropertyType.URI) {
			throw new IllegalStateException("Property type " + propType.value()
					+ " is not allowed FOR LIKE");
		}
		if (pd.getCardinality() != Cardinality.SINGLE) {
			throw new IllegalStateException(
					"LIKE is not allowed for multi-value properties ");
		}

		// Build a statement
		String field = solrUtil.getPropertyNameInSolr(repositoryId,solrUtil.convertToString(colNode));
		String pattern = translatePattern((String) rVal); // Solr wildcard expression
		
		WildcardQuery q = new WildcardQuery(new Term(field, pattern));
		return q;
	}

	private Query walkNotLike(Tree colNode, Tree stringNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		builder.add(walkLike(colNode, stringNode), Occur.MUST_NOT);
		return builder.build();
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of multiple value type walks
	// //////////////////////////////////////////////////////////////////////////////
	private Query walkIn(Tree colNode, Tree listNode) {
		// Check for CMIS SQL specification
		ColumnReference colRef = getColumnReference(colNode);

		// Build a statement
		// Combine queries with "OR" because Solr doesn't have "IN" syntax
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		String field = solrUtil.getPropertyNameInSolr(repositoryId, colRef.getPropertyQueryName().toString());
		List<?> list = (List<?>) walkExpr(listNode);
		for (Object elm : list) {
			Term t = new Term(field, elm.toString());
			TermQuery tq = new TermQuery(t);
			builder.add(tq, Occur.SHOULD);
		}
		return builder.build();
	}

	private Query walkNotIn(Tree colNode, Tree listNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkIn(colNode, listNode), Occur.MUST_NOT);
		return builder.build();
	}

	private Query walkInAny(Tree leftNode, Tree rightNode) {
		// Check for CMIS SQL specification
		ColumnReference colRef = getColumnReference(leftNode);
		PropertyDefinition<?> pd = colRef.getPropertyDefinition();
		if (pd.getCardinality() != Cardinality.MULTI) {
			throw new IllegalStateException(
					"Operator ANY...IN only is allowed on multi-value properties ");
		}

		// Build a statement
		// TODO Just set multiValued flag ON on Solr. Syntax is common as that
		// of wakEquals.
		Query q = walkEquals(leftNode, rightNode);
		return q;
	}

	private Query walkNotInAny(Tree leftNode, Tree rightNode) {
		Query q = walkNotEquals(leftNode, rightNode);
		return q;
	}

	private Query walkIsNull(Tree colNode) {
		String field = safeWalkExprToString(colNode);
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		// Lucene 9.x: Use TermRangeQuery.newStringRange() for wildcard range
		TermRangeQuery q1 = TermRangeQuery.newStringRange(field, null, null, false, false);
			builder.add(q1, Occur.MUST_NOT);
		return builder.build();
	}

	private Query walkIsNotNull(Tree colNode) {
		String field = safeWalkExprToString(colNode);
		// Lucene 9.x: Use TermRangeQuery.newStringRange() for wildcard range
		TermRangeQuery q = TermRangeQuery.newStringRange(field, null, null, false, false);
		return q;
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of getChildren type walks
	// //////////////////////////////////////////////////////////////////////////////
	private Query walkInFolder(Tree qualNode, Tree paramNode) {
		// Extract folder ID safely handling ArrayList cases
		String folderId = null;
		Object lit = walkExpr(paramNode);
		
		if (lit instanceof String) {
			folderId = (String) lit;
		} else if (lit instanceof List && ((List<?>) lit).size() > 0) {
			Object firstElement = ((List<?>) lit).get(0);
			if (firstElement instanceof String) {
				folderId = (String) firstElement;
			}
		}
		
		if (folderId == null) {
			throw new IllegalStateException(
					"Folder id in IN_FOLDER must be a valid string");
		}

		// Build a statement
		String parentIdField = solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.PARENT_ID);
		Term t = new Term(parentIdField, folderId);
		Query q = new TermQuery(t);
		
		if (qualNode != null) { // When a table alias exists
			String qualifier = safeWalkExprToString(qualNode);
			Term tQual = new Term("type", buildQualField(qualifier));
			Query qQual = new TermQuery(tQual);
			BooleanQuery.Builder bqBuilder = new BooleanQuery.Builder();
			bqBuilder.add(qQual, Occur.MUST);
			bqBuilder.add(q, Occur.MUST);
			return bqBuilder.build();
		}
		return q;
	}

	private Query walkInTree(Tree qualNode, Tree paramNode) {
		// OpenCMIS correctly returns ArrayList for IN_TREE parameters
		// Extract folder ID from the ArrayList structure
		Object lit = walkExpr(paramNode);
		String folderId = null;
		
		// Handle ArrayList returned by OpenCMIS parser for IN_TREE
		if (lit instanceof List && ((List<?>) lit).size() > 0) {
			Object firstElement = ((List<?>) lit).get(0);
			if (firstElement instanceof String) {
				folderId = (String) firstElement;
			} else {
				// Try to convert to string
				folderId = String.valueOf(firstElement);
			}
		} else if (lit instanceof String) {
			// Fallback for direct string case
			folderId = (String) lit;
		} else if (lit != null) {
			// Last resort - convert to string
			folderId = String.valueOf(lit);
		}
		
		if (folderId == null || folderId.trim().isEmpty()) {
			throw new IllegalStateException(
					"Folder id in IN_TREE must be a valid string");
		}

		// Build a Statement using the extracted folder ID
		Query q = walkInTreeInternal(folderId, this.repositoryId);
		if (qualNode != null) {
			String qualifier = safeWalkExprToString(qualNode);
			if (qualifier != null) {
				Term tQual = new Term("type", buildQualField(qualifier));
				Query qQual = new TermQuery(tQual);
				BooleanQuery.Builder bqBuilder = new BooleanQuery.Builder();
				bqBuilder.add(qQual, Occur.MUST);
				bqBuilder.add(q, Occur.MUST);
				return bqBuilder.build();
			}
		}
		return q;
	}

	private Query walkInTreeInternal(String folderId, String repositoryId) {
		// Alternative IN_TREE implementation using parent_id relationships
		// This avoids circular dependency with ContentService path calculations
		
		// Set Solr server
		SolrClient solrClient = solrUtil.getSolrClient();

		// Recursively find all descendant folder IDs using parent_id relationships
		Set<String> allDescendantIds = new HashSet<String>();
		List<String> currentLevelIds = new ArrayList<String>();
		currentLevelIds.add(folderId); // Start with the specified folder
		
		// Add the root folder itself as a descendant 
		allDescendantIds.add(folderId);
		
		// Recursively find descendants up to a reasonable depth limit
		int maxDepth = 10; // Prevent infinite loops
		for (int depth = 0; depth < maxDepth && !currentLevelIds.isEmpty(); depth++) {
			List<String> nextLevelIds = new ArrayList<String>();
			
			// Build Solr query string to find direct children of current level folders
			StringBuilder queryString = new StringBuilder();
			queryString.append("basetype:cmis\\:folder AND (");
			
			boolean first = true;
			for (String parentId : currentLevelIds) {
				if (!first) {
					queryString.append(" OR ");
				}
				queryString.append("parent_id:").append(parentId);
				first = false;
			}
			queryString.append(")");
			
			try {
				SolrQuery solrQuery = new SolrQuery(queryString.toString());
				QueryResponse resp = solrClient.query(solrQuery);
				SolrDocumentList children = resp.getResults();
				
				for (SolrDocument child : children) {
					Object objectIdValue = child.getFieldValue("object_id");
					String childId = null;
					
					if (objectIdValue instanceof String) {
						childId = (String) objectIdValue;
					} else if (objectIdValue instanceof List && !((List<?>) objectIdValue).isEmpty()) {
						// Handle multi-valued field case
						Object firstValue = ((List<?>) objectIdValue).get(0);
						if (firstValue instanceof String) {
							childId = (String) firstValue;
						}
					}
					
					if (childId != null && !allDescendantIds.contains(childId)) {
						allDescendantIds.add(childId);
						nextLevelIds.add(childId);
					}
				}
			} catch (SolrServerException | IOException e) {
				log.error("Error during IN_TREE descendant search: " + e.getMessage(), e);
				break; // Stop recursion on error
			}
			
			currentLevelIds = nextLevelIds;
		}
		
		// Build final query for all objects under any of the descendant folders
		if (allDescendantIds.isEmpty()) {
			// Return empty query if no descendants found
			return new MatchNoDocsQuery();
		}
		
		StringBuilder finalQueryString = new StringBuilder();
		finalQueryString.append("(");
		
		boolean first = true;
		for (String descendantId : allDescendantIds) {
			if (!first) {
				finalQueryString.append(" OR ");
			}
			finalQueryString.append("parent_id:").append(descendantId);
			first = false;
		}
		finalQueryString.append(")");
		
		// Convert Solr query string to Lucene Query for return
		// For now, return a simple query - the framework will handle Solr syntax conversion
		if (allDescendantIds.size() == 1) {
			String singleId = allDescendantIds.iterator().next();
			return new TermQuery(new Term("parent_id", singleId));
		} else {
			// For multiple IDs, create a BooleanQuery
			BooleanQuery.Builder builder = new BooleanQuery.Builder();
			for (String descendantId : allDescendantIds) {
				builder.add(new TermQuery(new Term("parent_id", descendantId)), Occur.SHOULD);
			}
			return builder.build();
		}
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of full-text search type walk
	// //////////////////////////////////////////////////////////////////////////////
	// Wildcards of CONTAINS() is the same as those of Solr, so leave them as they are.
	private Query walkContains(Tree qualNode, Tree queryNode) {
		if (qualNode != null) {
			//Qualifier isn't needed as long as JOIN isn't supported
			//String qualifier = walkExpr(qualNode).toString();
			//Term tQual = new Term("type", buildQualField(qualifier));
			//Query qQual = new TermQuery(tQual);

			BooleanQuery.Builder builder = new BooleanQuery.Builder();
			//q.add(qQual, Occur.MUST);
			builder.add(walkSearchExpr(queryNode), Occur.MUST);

			return builder.build();
		}
		return walkSearchExpr(queryNode);
	}

	private Query walkSearchExpr(Tree node) {
		int t = node.getType();
		switch (node.getType()) {
		case TextSearchLexer.TEXT_AND:
			return walkTextAnd(node);
		case TextSearchLexer.TEXT_OR:
			return walkTextOr(node);
		case TextSearchLexer.TEXT_MINUS:
			return walkTextMinus(node);
		case TextSearchLexer.TEXT_SEARCH_WORD_LIT:
			return walkTextWord(node);
		case TextSearchLexer.TEXT_SEARCH_PHRASE_STRING_LIT:
			return walkTextPhrase(node);
		default:
			//walkOtherExpr(node);
			//return null;
			return walkTextPhrase(node);
		}
	}

	private Query walkTextAnd(Tree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (int i = 0; i < node.getChildCount(); i++) {
			Tree child = node.getChild(i);
			builder.add(walkSearchExpr(child), Occur.MUST);
		}
		return builder.build();
	}

	private Query walkTextOr(Tree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (int i = 0; i < node.getChildCount(); i++) {
			Tree child = node.getChild(i);
			builder.add(walkSearchExpr(child), Occur.SHOULD);
		}
		return builder.build();
	}

	private Query walkTextMinus(Tree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (int i = 0; i < node.getChildCount(); i++) {
			Tree child = node.getChild(i);
			builder.add(walkSearchExpr(child), Occur.MUST);
		}
		return builder.build();
	}

	private Query walkTextWord(Tree node) {
		Term term = new Term("text", escapeString(node.toString()));
		TermQuery q = new TermQuery(term);
		return q;
	}

	private Query walkTextPhrase(Tree node) {
		String termString = escapeString(node.toString());
		if(termString.charAt(0) == '\'' && termString.charAt(termString.length()-1) == '\'' ){
			termString = '"' + termString.substring(1, termString.length() -1) + '"';
		}
		Term term = new Term("text", termString);
		TermQuery q = new TermQuery(term);
		return q;
	}
	
	private String escapeString(String val) {
				
		return val.replaceAll(":", "\\\\:");
		
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of walkExpr and its subwalks
	// These are used from various walks to evaluate a node value.
	// //////////////////////////////////////////////////////////////////////////////
	private Object walkExpr(Tree node) {
		switch (node.getType()) {
		case CmisQlStrictLexer.BOOL_LIT:
			return walkBoolean(node);
		case CmisQlStrictLexer.NUM_LIT:
			return walkNumber(node);
		case CmisQlStrictLexer.STRING_LIT:
			return walkString(node);
		case CmisQlStrictLexer.TIME_LIT:
			return walkTimestamp(node);
		case CmisQlStrictLexer.IN_LIST:
			return walkList(node);
		case CmisQlStrictLexer.COL:
			return walkCol(node);
		case CmisQlStrictLexer.ID:
			return walkId(node);
		default:
			return walkOtherExpr(node);
		}
	}

	private Object walkBoolean(Tree node) {
		String s = node.getText();
		return Boolean.valueOf(s);
	}

	private Object walkNumber(Tree node) {
		String s = node.getText();
		if (s.contains(".") || s.contains("e") || s.contains("E")) {
			return Double.valueOf(s);
		} else {
			return Long.valueOf(s);
		}
	}

	private Object walkString(Tree node) {
		String s = node.getText();
		s = s.substring(1, s.length() - 1);
		//return "\"" + ClientUtils.escapeQueryChars(s) + "\"";
		return ClientUtils.escapeQueryChars(s);
	}

	private Object walkTimestamp(Tree node) {
		String s = node.getText();
		s = s.substring(s.indexOf('\'') + 1, s.length() - 1);
		return s;
	}

	private Object walkList(Tree node) {
		int n = node.getChildCount();
		List<Object> res = new ArrayList<Object>(n);
		for (int i = 0; i < n; i++) {
			res.add(walkExpr(node.getChild(i)));
		}
		return res;
	}

	private Object walkCol(Tree node) {
		return null;
	}

	private Object walkId(Tree node) {
		String s;
		s = node.toStringTree();
		return s;
	}

	private Object walkOtherExpr(Tree node) {
		throw new CmisRuntimeException("Unknown node type: " + node.getType()
				+ " (" + node.getText() + ")");
	}

	// //////////////////////////////////////////////////////////////////////////////

	/**
	 * Utility methods
	 */

	/**
	 * Convert String to BytesRef for Lucene TermRangeQuery
	 * @deprecated No longer needed in Lucene 9.x - TermRangeQuery.newStringRange() accepts String directly
	 *
	 * @param s
	 * @return
	 */
	@Deprecated
	private BytesRef convertToBytesRef(String s) {
		byte[] bytes = s.getBytes();
		BytesRef bytesRef = new BytesRef(bytes);
		return bytesRef;
	}

	/**
	 * Translate a full-text search expression from SQL style to Solr style
	 *
	 * @param wildcardString
	 * @return
	 */
	private static String translatePattern(String wildcardString) {
		int index = 0;
		int start = 0;
		StringBuffer res = new StringBuffer();

		while (index >= 0) {
			index = wildcardString.indexOf('%', start);
			if (index < 0) {
				res.append(wildcardString.substring(start));
			} else if (index == 0 || index > 0
					&& wildcardString.charAt(index - 1) != '\\') {
				res.append(wildcardString.substring(start, index));
				res.append("*");
			} else {
				res.append(wildcardString.substring(start, index + 1));
			}
			start = index + 1;
		}
		wildcardString = res.toString();

		index = 0;
		start = 0;
		res = new StringBuffer();

		while (index >= 0) {
			index = wildcardString.indexOf('_', start);
			if (index < 0) {
				res.append(wildcardString.substring(start));
			} else if (index == 0 || index > 0
					&& wildcardString.charAt(index - 1) != '\\') {
				res.append(wildcardString.substring(start, index));
				res.append("?"); //
			} else {
				res.append(wildcardString.substring(start, index + 1));
			}
			start = index + 1;
		}
		return res.toString();
	}

	private ColumnReference getColumnReference(Tree columnNode) {
		CmisSelector sel = queryObject.getColumnReference(columnNode
				.getTokenStartIndex());
		if (null == sel) {
			throw new IllegalStateException("Unknown property query name "
					+ columnNode.getChild(0));
		} else if (sel instanceof ColumnReference) {
			return (ColumnReference) sel;
		} else {
			throw new IllegalStateException(
					"Unexpected numerical value function in where clause");
		}
	}

	/**
	 * Safely convert walkExpr result to String, handling ArrayList cases
	 */
	private String safeWalkExprToString(Tree node) {
		Object result = walkExpr(node);
		if (result instanceof String) {
			return (String) result;
		} else if (result instanceof List && ((List<?>) result).size() > 0) {
			Object firstElement = ((List<?>) result).get(0);
			if (firstElement instanceof String) {
				return (String) firstElement;
			}
		}
		// Fallback to toString()
		return result != null ? result.toString() : null;
	}

	/**
	 * Look up a Solr name of a table from alias
	 *
	 * @param alias
	 * @return
	 */
	private String buildQualField(String alias) {
		String cmisName = queryObject.getTypeQueryName(alias);
		String solrName = solrUtil.getPropertyNameInSolr(repositoryId, cmisName);
		return solrName;
	}

	/**
	 * Get all subfolder ids by connecting to Solr recursively
	 *
	 * @param folderId
	 * @param solrServer
	 * @return
	 */
	private List<String> getDescendantFolderId(String folderId,
			SolrClient solrClient) {
		List<String> list = new ArrayList<String>();

		list.add(folderId); // Add oneself to the list in advance

		SolrQuery query = new SolrQuery();

		query.setQuery(solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.PARENT_ID) + ":" + folderId + " AND "
				+ solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.BASE_TYPE_ID) + ":cmis\\:folder"); // only "folder" nodes

		// Connect to SolrServer and add subfolder ids to the list
		try {
			QueryResponse resp = solrClient.query(query);
			SolrDocumentList children = resp.getResults();
			// END NODE case: Do nothing but return oneself
			if (children.getNumFound() == 0) {
				return list;
				// Other than END NODE case: collect descendants values
				// recursively
			} else {
				Iterator<SolrDocument> iterator = resp.getResults().iterator();
				while (iterator.hasNext()) {
					SolrDocument child = iterator.next();
					String childId = (String) child.getFieldValue("id");
					// Recursive call to this method
					List<String> l = getDescendantFolderId(childId, solrClient);
					list.addAll(l);
				}
				return list;
			}
		} catch (SolrServerException | IOException e) {
			e.printStackTrace();
			return null;
		}
	}
}
