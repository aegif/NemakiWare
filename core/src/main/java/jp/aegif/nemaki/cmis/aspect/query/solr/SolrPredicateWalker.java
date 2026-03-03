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
import org.apache.lucene.search.TermInSetQuery;
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
	 * Parse field name & condition value with literal type validation.
	 * Field name is prepared for Solr query.
	 *
	 * Validates that the literal type on the right-hand side is compatible
	 * with the property type on the left-hand side.
	 *
	 * @param leftNode the column reference node (left side of comparison)
	 * @param rightNode the literal value node (right side of comparison)
	 * @return map containing field name (FLD) and condition value (CND)
	 * @throws IllegalStateException if literal type is not compatible with property type
	 */
	private HashMap<String, String> walkCompareInternal(Tree leftNode,
			Tree rightNode) {
		HashMap<String, String> map = new HashMap<String, String>();

		String left = solrUtil.convertToString(leftNode);
		
		// Validate literal type compatibility with property type
		validateLiteralType(leftNode, rightNode);
		
		String right = safeWalkExprToString(rightNode);

		map.put(FLD, ClientUtils.escapeQueryChars(solrUtil.getPropertyNameInSolr(repositoryId, left)));
		map.put(CND, right);
		return map;
	}

	/**
	 * Validates that the literal type is compatible with the property type.
	 *
	 * Note: CMIS QL grammar ensures left-hand side is always a column reference
	 * and right-hand side is a literal value in comparison expressions.
	 * This method assumes that ordering.
	 *
	 * @param columnNode the column reference node (left-hand side of comparison)
	 * @param literalNode the literal value node (right-hand side of comparison)
	 * @throws IllegalStateException if types are not compatible
	 */
	private void validateLiteralType(Tree columnNode, Tree literalNode) {
		int literalType = literalNode.getType();
		
		// Skip validation for non-literal types (e.g., column references, expressions)
		// This handles cases like "column1 = column2" comparisons
		if (!LiteralTypeValidator.isLiteralType(literalType)) {
			return;
		}
		
		try {
			ColumnReference colRef = getColumnReference(columnNode);
			String colRefName = colRef.getName();
			TypeDefinition td = colRef.getTypeDefinition();
			Map<String, PropertyDefinition<?>> pds = td.getPropertyDefinitions();
			PropertyDefinition<?> pd = pds.get(colRefName);
			
			if (pd != null) {
				PropertyType propType = pd.getPropertyType();
				LiteralTypeValidator.validate(propType, literalType, colRefName);
			}
		} catch (IllegalStateException e) {
			// Re-throw type validation errors
			throw e;
		} catch (Exception e) {
			// Log but don't fail for other errors (e.g., missing property definitions)
			// This maintains backward compatibility with existing queries
			log.debug("Could not validate literal type for comparison: " + e.getMessage());
		}
	}

	/**
	 * Validates that all literal types in an IN list are compatible with the property type.
	 *
	 * @param colRef the column reference
	 * @param listNode the IN_LIST node containing literal elements
	 * @throws IllegalStateException if any element's type is not compatible
	 */
	private void validateInListLiteralTypes(ColumnReference colRef, Tree listNode) {
		if (listNode.getType() != CmisQlStrictLexer.IN_LIST) {
			return;
		}
		
		try {
			String colRefName = colRef.getName();
			TypeDefinition td = colRef.getTypeDefinition();
			Map<String, PropertyDefinition<?>> pds = td.getPropertyDefinitions();
			PropertyDefinition<?> pd = pds.get(colRefName);
			
			if (pd == null) {
				return;
			}
			
			PropertyType propType = pd.getPropertyType();
			
			// Validate each element in the list
			int childCount = listNode.getChildCount();
			for (int i = 0; i < childCount; i++) {
				Tree child = listNode.getChild(i);
				int literalType = child.getType();
				
				// Skip non-literal types using the common utility method
				if (!LiteralTypeValidator.isLiteralType(literalType)) {
					continue;
				}
				
				LiteralTypeValidator.validate(propType, literalType, colRefName);
			}
		} catch (IllegalStateException e) {
			// Re-throw type validation errors
			throw e;
		} catch (Exception e) {
			// Log but don't fail for other errors
			log.debug("Could not validate literal types for IN list: " + e.getMessage());
		}
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

		// Validate literal types for each element in the IN list
		validateInListLiteralTypes(colRef, listNode);

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

		// Validate literal types for each element in the IN list
		validateInListLiteralTypes(colRef, rightNode);

		// CRITICAL FIX (2025-12-19): Build proper OR query for multi-valued IN clause
		// The previous implementation called walkEquals() which doesn't handle IN lists correctly
		// Now we iterate over the list values similar to walkIn() method
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		String field = solrUtil.getPropertyNameInSolr(repositoryId, colRef.getPropertyQueryName().toString());
		List<?> list = (List<?>) walkExpr(rightNode);
		for (Object elm : list) {
			Term t = new Term(field, elm.toString());
			TermQuery tq = new TermQuery(t);
			builder.add(tq, Occur.SHOULD);
		}
		return builder.build();
	}

	private Query walkNotInAny(Tree leftNode, Tree rightNode) {
		// CRITICAL FIX (2025-12-19): Build proper NOT query for multi-valued NOT IN clause
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		builder.add(walkInAny(leftNode, rightNode), Occur.MUST_NOT);
		return builder.build();
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
		// Use cached folder hierarchy from SolrUtil (TTL-based with invalidation)
		Map<String, List<String>> childrenMap = solrUtil.getFolderHierarchy(repositoryId);
		if (childrenMap == null) {
			return new MatchNoDocsQuery();
		}

		// BFS from folderId to collect all descendant folder IDs
		Set<String> allDescendantIds = new HashSet<>();
		allDescendantIds.add(folderId);
		List<String> queue = new ArrayList<>();
		queue.add(folderId);

		while (!queue.isEmpty()) {
			List<String> nextQueue = new ArrayList<>();
			for (String parentId : queue) {
				List<String> children = childrenMap.get(parentId);
				if (children != null) {
					for (String childId : children) {
						if (allDescendantIds.add(childId)) {
							nextQueue.add(childId);
						}
					}
				}
			}
			queue = nextQueue;
		}

		// Build final query: parent_id IN (all descendant folder IDs)
		if (allDescendantIds.isEmpty()) {
			return new MatchNoDocsQuery();
		}

		if (allDescendantIds.size() == 1) {
			String singleId = allDescendantIds.iterator().next();
			return new TermQuery(new Term("parent_id", singleId));
		} else {
			// Use TermInSetQuery instead of BooleanQuery with SHOULD clauses
			// to avoid TooManyClauses when descendant count exceeds maxClauseCount (default 1024)
			List<BytesRef> terms = new ArrayList<>(allDescendantIds.size());
			for (String descendantId : allDescendantIds) {
				terms.add(new BytesRef(descendantId));
			}
			return new TermInSetQuery("parent_id", terms);
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
		case CmisQlStrictLexer.STRING_LIT:
			// CmisQlStrictParser delivers CONTAINS('text') as STRING_LIT (type 62).
			// CMIS spec: CONTAINS('word1 word2') = AND of individual words,
			//            CONTAINS('"word1 word2"') = phrase search.
			return walkContainsStringLit(node);
		default:
			return walkTextPhrase(node);
		}
	}

	/**
	 * Handle STRING_LIT from CmisQlStrictParser's CONTAINS clause.
	 * Per CMIS spec:
	 *   CONTAINS('word1 word2')   → AND of individual words (each analyzed per-field)
	 *   CONTAINS('"word1 word2"') → exact phrase search
	 */
	private Query walkContainsStringLit(Tree node) {
		String text = escapeString(node.toString());

		// Strip surrounding single quotes from CMIS syntax
		if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
			text = text.substring(1, text.length() - 1);
		}
		text = text.trim();

		// Inner double quotes indicate phrase search: CONTAINS('"exact phrase"')
		if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
			String phrase = text.substring(1, text.length() - 1);
			return buildDualFieldQuery(phrase);
		}

		// Split on whitespace: CONTAINS('word1 word2') → word1 AND word2
		String[] words = text.split("\\s+");
		if (words.length > 1) {
			BooleanQuery.Builder andBuilder = new BooleanQuery.Builder();
			for (String word : words) {
				if (word.isEmpty()) continue;
				andBuilder.add(buildDualFieldQuery(word), Occur.MUST);
			}
			return andBuilder.build();
		}

		return buildDualFieldQuery(text);
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
		String nodeText = node.toString();
		String escapedText = escapeString(nodeText);

		// If text contains spaces, split into individual words and combine with AND.
		// CMIS CONTAINS('word1 word2') means "documents containing both word1 AND word2",
		// NOT phrase matching. Each word is searched independently against dual-index fields.
		String[] words = escapedText.trim().split("\\s+");
		if (words.length > 1) {
			BooleanQuery.Builder andBuilder = new BooleanQuery.Builder();
			for (String word : words) {
				if (word.isEmpty()) continue;
				andBuilder.add(buildDualFieldQuery(word), Occur.MUST);
			}
			return andBuilder.build();
		}

		return buildDualFieldQuery(escapedText);
	}

	private enum ScriptType { CJK_ONLY, LATIN_ONLY, MIXED }

	/**
	 * Unicode Character Block でテキストの語種を判定する。
	 * CJK統合漢字、ひらがな、カタカナ、ハングル → CJK
	 * Basic Latin（ASCII letters/digits）→ Latin
	 * それ以外・混在 → MIXED
	 *
	 * コードポイントベースで走査し、補助平面のCJK（拡張B以降 U+20000+）も正しく判定する。
	 */
	private static ScriptType detectScript(String text) {
		boolean hasCjk = false;
		boolean hasLatin = false;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || cp == '"' || cp == '\'' || cp == '_' || cp == '-') {
				continue;
			}
			Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
			if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
				|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
				|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
				|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
				|| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
				|| block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
				|| block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
				|| block == Character.UnicodeBlock.HIRAGANA
				|| block == Character.UnicodeBlock.KATAKANA
				|| block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
				|| block == Character.UnicodeBlock.HANGUL_SYLLABLES
				|| block == Character.UnicodeBlock.HANGUL_JAMO
				|| block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
				hasCjk = true;
			} else if (block == Character.UnicodeBlock.BASIC_LATIN) {
				if (Character.isLetterOrDigit(cp)) {
					hasLatin = true;
				}
			}
			if (hasCjk && hasLatin) return ScriptType.MIXED;
		}
		if (hasCjk && !hasLatin) return ScriptType.CJK_ONLY;
		if (hasLatin && !hasCjk) return ScriptType.LATIN_ONLY;
		return ScriptType.MIXED;
	}

	/**
	 * Build a query for a single word, selecting field(s) based on script detection.
	 * CJK only → text (Kuromoji) のみ（text_en の Porter stemming は CJK に無益）。
	 * Latin only / mixed → text + text_en の両方を SHOULD (OR) で検索。
	 *   text は author/content_type/resourcename/url 等の copyField を含み、
	 *   text_en は Porter stemming による英語の語形変化を吸収する。
	 */
	private Query buildDualFieldQuery(String word) {
		String quoted = "\"" + word + "\"";
		ScriptType script = detectScript(word);

		if (script == ScriptType.CJK_ONLY) {
			return new TermQuery(new Term("text", quoted));
		} else {
			// LATIN_ONLY / MIXED: 両フィールドを検索
			BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(new TermQuery(new Term("text", quoted)), Occur.SHOULD);
			builder.add(new TermQuery(new Term("text_en", quoted)), Occur.SHOULD);
			return builder.build();
		}
	}

	private Query walkTextPhrase(Tree node) {
		String nodeText = node.toString();
		String termString = escapeString(nodeText);

		// Strip surrounding single quotes from CMIS phrase syntax
		if(termString.charAt(0) == '\'' && termString.charAt(termString.length()-1) == '\'' ){
			termString = termString.substring(1, termString.length() - 1);
		}

		// Phrase search: wrap in double quotes to force exact phrase matching.
		// This is correct for walkTextPhrase (CMIS CONTAINS('"phrase here"')).
		return buildDualFieldQuery(termString);
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
	 * Translate a full-text search expression from SQL style to Solr style
	 *
	 * @param wildcardString
	 * @return
	 */
	private static String translatePattern(String wildcardString) {
		int index = 0;
		int start = 0;
		StringBuilder res = new StringBuilder();

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
		res = new StringBuilder();

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

	/**
	 * Extract a String field value from a SolrDocument, handling multi-valued fields.
	 */
	private String extractSolrStringField(SolrDocument doc, String fieldName) {
		Object value = doc.getFieldValue(fieldName);
		if (value instanceof String) {
			return (String) value;
		} else if (value instanceof List && !((List<?>) value).isEmpty()) {
			Object first = ((List<?>) value).get(0);
			if (first instanceof String) {
				return (String) first;
			}
		}
		return null;
	}
}
