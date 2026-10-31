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

import org.apache.chemistry.opencmis.server.support.query.CmisTree;
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
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.client.solrj.request.SolrQuery;
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

	public Query walkPredicate(CmisTree node) {
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
		// IN_ANY is the node the parser builds for `ANY col IN (...)`; ANY is only the keyword's token, which never
		// reaches here as a node. Dispatching on ANY left walkInAny unreachable: the walker answered null, and the
		// statement fell to the manual fallback — `*:*` before R188, a refusal after it (closing batch 2 review).
		case CmisQlStrictLexer.IN_ANY:
			return walkInAny(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NOT_IN_ANY:
			return walkNotInAny(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.IS_NULL:
			return walkIsNull(node.getChild(0));
		case CmisQlStrictLexer.IS_NOT_NULL:
			return walkIsNotNull(node.getChild(0));
			// GetChildren type walks
		case CmisQlStrictLexer.IN_FOLDER:
			// The folder is the last child: the first, when there are two, is the qualifier, which asks
			// nothing of the index (see walkInFolder).
			return walkInFolder(node.getChild(node.getChildCount() - 1));
		case CmisQlStrictLexer.IN_TREE:
			return walkInTree(node.getChild(node.getChildCount() - 1));
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
	private Query walkNot(CmisTree node) {
		// NOT of a comparison is the OPPOSITE comparison under SQL's three-valued logic: for a document without the
		// property both are UNKNOWN, and neither matches. Negating the whole query instead returned those documents —
		// and once <>, NOT LIKE and NOT IN required the property (closing batch 4), `NOT (p <> 'x')` returned every
		// document without p besides the ones with p = 'x' (closing batch 4 review, Codex P1; OData's not(… ne …)
		// builds exactly this). The orderings are comparisons too: `NOT (n < 5)` is `n >= 5`, and the whole negation
		// returned every document without n as well (found re-reading closing batch 5 before its record). A NOT
		// around anything else is negated as a whole, as before.
		switch (node.getType()) {
		case CmisQlStrictLexer.GT:
			return walkLessOrEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.GTEQ:
			return walkLessThan(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.LT:
			return walkGreaterOrEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.LTEQ:
			return walkGreaterThan(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.EQ:
			return walkNotEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NEQ:
			return walkEquals(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.LIKE:
			return walkNotLike(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NOT_LIKE:
			return walkLike(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.IN:
			return walkNotIn(node.getChild(0), node.getChild(1));
		case CmisQlStrictLexer.NOT_IN:
			return walkIn(node.getChild(0), node.getChild(1));
		default:
			break;
		}
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkPredicate(node), Occur.MUST_NOT);
		return builder.build();
	}

	private BooleanQuery walkOr(CmisTree leftNode, CmisTree rightNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkPredicate(leftNode), Occur.SHOULD);
			builder.add(walkPredicate(rightNode), Occur.SHOULD);
		return builder.build();
	}

	private BooleanQuery walkAnd(CmisTree leftNode, CmisTree rightNode) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkPredicate(leftNode), Occur.MUST);
			builder.add(walkPredicate(rightNode), Occur.MUST);
		return builder.build();
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of Comparison walks
	// //////////////////////////////////////////////////////////////////////////////
	private Query walkEquals(CmisTree leftNode, CmisTree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		Term term = new Term(map.get(FLD), map.get(CND));
		Query q = new TermQuery(term);
		return q;
	}

	private Query walkNotEquals(CmisTree leftNode, CmisTree rightNode) {
		// A comparison with a property that has no value is not a match (CMIS follows SQL here), so the document must
		// HAVE the field as well as not have this value. The bare negation also returned every document that never
		// set the property, and the manual fallback answered the same statement the other way (closing batch 3
		// review, subagent P3-3).
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		return hasAndIsNot(map.get(FLD), walkEquals(leftNode, rightNode));
	}

	/** {@code +field:[* TO *] -excluded}: the document has the field, and does not match {@code excluded}. */
	private static Query hasAndIsNot(String field, Query excluded) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		builder.add(new TermRangeQuery(field, null, null, true, true), Occur.MUST);
		builder.add(excluded, Occur.MUST_NOT);
		return builder.build();
	}

	/** The Solr field a column is indexed under, escaped once — what walkLike and walkIn query. */
	private String fieldOf(CmisTree colNode) {
		return queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, propertyIdOf(getColumnReference(colNode))));
	}

	private Query walkGreaterThan(CmisTree leftNode, CmisTree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD),
				new BytesRef(map.get(CND)), null, false, false);
		return t;
	}

	private Query walkGreaterOrEquals(CmisTree leftNode, CmisTree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD),
				new BytesRef(map.get(CND)), null, true, false);
		return t;
	}

	private Query walkLessThan(CmisTree leftNode, CmisTree rightNode) {
		HashMap<String, String> map = walkCompareInternal(leftNode, rightNode);
		TermRangeQuery t = new TermRangeQuery(map.get(FLD), null,
				new BytesRef(map.get(CND)), false, false);
		return t;
	}

	private Query walkLessOrEquals(CmisTree leftNode, CmisTree rightNode) {
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
	private HashMap<String, String> walkCompareInternal(CmisTree leftNode,
			CmisTree rightNode) {
		HashMap<String, String> map = new HashMap<String, String>();

		// Validate literal type compatibility with property type
		validateLiteralType(leftNode, rightNode);
		
		String right = safeWalkExprToString(rightNode);

		// Escaped once (round-5 review). escapeQueryChars over the STRING arm's output escaped the
		// backslash the property map had already put there, so a comparison against a custom
		// property named a field nothing is indexed under and matched nothing. What is NOT changed
		// here is how the column is read: "left" is still the node's children joined with a dot, so
		// a qualified column resolves to a field no document carries (R178's remaining shape).
		// The column through the query object's ColumnReference and the property's ID, like every other production
		// here (closing batch 4 review, subagent P2-2): the joined text named the property by its QUERY name, and a
		// property whose query name is not its id (nemaki:tag is queried as tag) got a field nothing is indexed under.
		// The same reading also resolves a qualified column, which the joined text did not (R178's remaining shape).
		map.put(FLD, queryableFieldName(solrUtil.getPropertyNameInSolr(repositoryId,
				propertyIdOf(getColumnReference(leftNode)))));
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
	private void validateLiteralType(CmisTree columnNode, CmisTree literalNode) {
		int literalType = literalNode.getType();
		
		// Skip validation for non-literal types (e.g., column references, expressions)
		// This handles cases like "column1 = column2" comparisons
		if (!LiteralTypeValidator.isLiteralType(literalType)) {
			return;
		}
		
		try {
			ColumnReference colRef = getColumnReference(columnNode);
			String colRefName = colRef.getName();
			PropertyDefinition<?> pd = definitionOf(colRef);
			
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
	private void validateInListLiteralTypes(ColumnReference colRef, CmisTree listNode) {
		if (listNode.getType() != CmisQlStrictLexer.IN_LIST) {
			return;
		}
		
		try {
			String colRefName = colRef.getName();
			PropertyDefinition<?> pd = definitionOf(colRef);
			
			if (pd == null) {
				return;
			}
			
			PropertyType propType = pd.getPropertyType();
			
			// Validate each element in the list
			int childCount = listNode.getChildCount();
			for (int i = 0; i < childCount; i++) {
				CmisTree child = listNode.getChild(i);
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

	private Query walkLike(CmisTree colNode, CmisTree stringNode) {
		// Check for CMIS SQL specification
		Object rVal = walkExpr(stringNode);
		if (!(rVal instanceof String)) {
			throw new IllegalStateException(
					"LIKE operator requires String literal on right hand side.");
		}
		ColumnReference colRef = getColumnReference(colNode);
		PropertyDefinition<?> pd = definitionOf(colRef);
		if (pd == null) {
			throw new IllegalStateException("No definition for the column " + colRef.getPropertyQueryName());
		}
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

		// Build a statement. The column is resolved through the query object's own ColumnReference,
		// not by joining the node's children with a dot: "WHERE d.nemaki:tag LIKE 'x%'" asked for
		// "d.nemaki:tag", which is in no property map, so the field became a custom one no document
		// carries and LIKE matched nothing (round-5 review). Escaped once, as everywhere else.
		String field = queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, propertyIdOf(colRef)));
		// The RAW literal, read with CMIS's escapes: \% and \_ are a percent sign and an underscore, \' and \\ a quote
		// and a backslash. walkString had already escaped the backslash for Solr, so an escaped % went out as a
		// backslash and a percent sign (closing batch 3 review, both reviewers). The manual fallback reads LIKE
		// through the same method, so the two readers answer one statement the same way.
		String literal = stringNode.getText();
		String pattern = SolrQueryProcessor.likeToEscapedSolrWildcard(literal.substring(1, literal.length() - 1));
		
		WildcardQuery q = new WildcardQuery(new Term(field, pattern));
		return q;
	}

	private Query walkNotLike(CmisTree colNode, CmisTree stringNode) {
		// Has the property, and does not match: the bare negation returned every document without it too.
		return hasAndIsNot(fieldOf(colNode), walkLike(colNode, stringNode));
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of multiple value type walks
	// //////////////////////////////////////////////////////////////////////////////
	private Query walkIn(CmisTree colNode, CmisTree listNode) {
		// Check for CMIS SQL specification
		ColumnReference colRef = getColumnReference(colNode);

		// Validate literal types for each element in the IN list
		validateInListLiteralTypes(colRef, listNode);

		// Build a statement
		// Combine queries with "OR" because Solr doesn't have "IN" syntax
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		// Escaped once, like every other field name here: the DATETIME arm of the property map
		// returns a RAW colon, and an IN over a custom DATETIME property was rejected with 400
		// (round-5 review).
		String field = queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, propertyIdOf(colRef)));
		List<?> list = (List<?>) walkExpr(listNode);
		for (Object elm : list) {
			Term t = new Term(field, elm.toString());
			TermQuery tq = new TermQuery(t);
			builder.add(tq, Occur.SHOULD);
		}
		return builder.build();
	}

	private Query walkNotIn(CmisTree colNode, CmisTree listNode) {
		// Has the property, and its value is none of these: the bare negation returned every document without it too.
		return hasAndIsNot(fieldOf(colNode), walkIn(colNode, listNode));
	}

	private Query walkInAny(CmisTree leftNode, CmisTree rightNode) {
		// Check for CMIS SQL specification
		ColumnReference colRef = getColumnReference(leftNode);
		// By the column's QUERY name (definitionOf): the reference's own definition is looked up by the id the type
		// manager answered, which is the query name, and is null for a property whose query name is not its id —
		// `ANY tag IN (...)` was refused on bedroom for that reason (closing batch 4 review, subagent P2-2).
		PropertyDefinition<?> pd = definitionOf(colRef);
		if (pd == null) {
			throw new IllegalStateException("No definition for the column " + colRef.getPropertyQueryName());
		}
		if (pd.getCardinality() != Cardinality.MULTI) {
			throw new IllegalStateException(
					"Operator ANY...IN only is allowed on multi-value properties ");
		}

		// Validate literal types for each element in the IN list
		validateInListLiteralTypes(colRef, rightNode);

		// CRITICAL FIX (2025-12-19): Build proper OR query for multi-valued IN clause
		// The previous implementation called walkEquals() which doesn't handle IN lists correctly
		// Now we iterate over the list values similar to walkIn() method
		String field = queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, propertyIdOf(colRef)));
		return anyOf(field, (List<?>) walkExpr(rightNode));
	}

	/**
	 * {@code ANY field IN (values)}: a document with one of the values in the multi-valued field. A JOIN with a
	 * secondary type asks the same of cmis:secondaryObjectTypeIds (R215), and is built here too, so the JOIN and
	 * {@code ANY cmis:secondaryObjectTypeIds IN ('that type')} send Solr the same text.
	 */
	static Query anyOf(String field, List<?> values) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (Object value : values) {
			builder.add(new TermQuery(new Term(field, value.toString())), Occur.SHOULD);
		}
		return builder.build();
	}

	private Query walkNotInAny(CmisTree leftNode, CmisTree rightNode) {
		// REFUSED, not answered (closing batch 4 review, Codex P1). `ANY p NOT IN (...)` asks for a document with a
		// value of p outside the list; the negation of `ANY p IN (...)` it was built as answers «no value of p is in
		// the list», which drops a document holding one value inside and one outside and adds every document with no
		// value at all. One term query on a multi-valued field cannot ask «some value is not one of these», so the
		// statement is refused like any WHERE clause this server cannot translate (R188).
		throw new IllegalStateException("ANY ... NOT IN is not translated: a query on one field cannot ask whether "
				+ "SOME value of a multi-valued property is outside the list");
	}

	private Query walkIsNull(CmisTree colNode) {
		String field = solrFieldOfColumn(colNode);
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		// An "exists" range, negated. A BooleanQuery whose only clause is MUST_NOT matches nothing,
		// so the absence is taken against all documents — the string this becomes is
		// "+*:* -<field>:[* TO *]".
		builder.add(new MatchAllDocsQuery(), Occur.MUST);
		builder.add(existsRange(field), Occur.MUST_NOT);
		return builder.build();
	}

	private Query walkIsNotNull(CmisTree colNode) {
		// An open-ended range over the field: every document that has any value for it.
		return existsRange(solrFieldOfColumn(colNode));
	}

	/**
	 * "The field has some value", as the one range both operators are built from. Inclusive bounds,
	 * so the query this prints is {@code field:[* TO *]} — the form Solr documents as an existence
	 * check. With unbounded ends the exclusive form means the same thing, but it is not the idiom
	 * and invites a second reading of a query string that is sent to Solr verbatim.
	 */
	private static TermRangeQuery existsRange(String field) {
		return TermRangeQuery.newStringRange(field, null, null, true, true);
	}

	/**
	 * The Solr field a CMIS column refers to, resolved the way {@code walkIn} and {@code walkInAny} already
	 * resolve one: through the query object's own {@link ColumnReference}, then
	 * {@link SolrUtil#getPropertyNameInSolr}. Two things this does NOT do, both measured as wrong:
	 *
	 * <ul>
	 * <li>It does not read the column with {@code safeWalkExprToString}, which walks to {@code walkCol} —
	 *     and that returns {@code null} for every column, so the field name was always null and
	 *     {@code TermRangeQuery} threw {@code "field must not be null"}. From the entry point that
	 *     exception was caught in {@code SolrQueryProcessor} and the statement fell back to the manual
	 *     parser, which answered these two operators correctly for properties outside {@code cmis:} — so
	 *     the operators were broken here, not unusable everywhere.</li>
	 * <li>It does not escape the result a second time. {@code getPropertyNameInSolr} already escapes the
	 *     colon of a custom property, and running {@code escapeQueryChars} over
	 *     {@code dynamic.property.nemaki\:foo} escapes that backslash too, naming a field nothing is
	 *     indexed under — which for {@code IS NULL} matched EVERY document the type and ACL filters left.
	 *     It escapes ONCE, through {@link SolrUtil#queryableFieldName}. An earlier version of this
	 *     javadoc said {@code walkIn} and {@code walkInAny} "do not escape either" as if that were the
	 *     model to follow: it is not — not escaping at all leaves the DATETIME arm's raw colon, which
	 *     Solr rejects with 400, and those two productions had that defect until the round-5 review
	 *     (now routed through the same method). {@code walkCompareInternal} resolves the column the old
	 *     way as well, which is the part of R178 this batch still has not opened.</li>
	 * </ul>
	 *
	 * <p>Using the query object's reference also means a qualified column ({@code WHERE t.cmis:name IS
	 * NULL}) resolves to the property, where joining the node's children with a dot gave
	 * {@code t.cmis:name} — in no property map, and so the same match-everything break.
	 */
	/**
	 * The definition of the property a column names, found by its QUERY name in the column's type. The column's own id
	 * is no help: {@code TypeManagerImpl.getPropertyIdForQueryName} answers the query name, so a property whose query name
	 * is not its id — {@code nemaki:tag} is queried as {@code tag}, and 65 properties on bedroom are like it — was looked
	 * up by the query name. Its Solr field was one nothing is indexed under: with one tagged nemaki:document on bedroom,
	 * {@code WHERE tag IS NOT NULL} answered 0 and {@code IS NULL} 1 (closing batch 4 review, subagent P2-2, measured).
	 */
	private static PropertyDefinition<?> definitionOf(ColumnReference colRef) {
		TypeDefinition td = colRef.getTypeDefinition();
		if (td == null || td.getPropertyDefinitions() == null) {
			return null;
		}
		String queryName = colRef.getPropertyQueryName();
		// By QUERY name first — that is what the column names, and how QueryObject resolved it. The map is keyed by
		// id, and looking the name up as a key first took another property whose id happens to be this query name
		// (closing batch 5 review, subagent P3-3: id `title` / query name `doc_title` beside id `acme:title` / query
		// name `title` — `WHERE title = 'x'` built the first one's field and checked the literal against its type).
		for (PropertyDefinition<?> pd : td.getPropertyDefinitions().values()) {
			if (queryName != null && queryName.equals(pd.getQueryName())) {
				return pd;
			}
		}
		return td.getPropertyDefinitions().get(queryName);
	}

	/** The id of the property a column names — what the index is written under (see {@link #definitionOf}). */
	private static String propertyIdOf(ColumnReference colRef) {
		PropertyDefinition<?> pd = definitionOf(colRef);
		return pd != null ? pd.getId() : colRef.getPropertyQueryName();
	}

	private String solrFieldOfColumn(CmisTree colNode) {
		ColumnReference colRef = getColumnReference(colNode);
		return queryableFieldName(solrUtil.getPropertyNameInSolr(repositoryId, propertyIdOf(colRef)));
	}

	/**
	 * A field name escaped for a Solr query exactly once, whatever
	 * {@link SolrUtil#getPropertyNameInSolr} returned — see {@link SolrUtil#queryableFieldName},
	 * which is where the rule lives because that is where the two disagreeing arms live. Every
	 * production in this walker that turns a property name into a field name goes through here;
	 * the round-5 review found three that did not (IN, ANY-IN and LIKE), and the manual fallback
	 * in {@link SolrQueryProcessor} is routed through the same method.
	 */
	private static String queryableFieldName(String fromPropertyMap) {
		return SolrUtil.queryableFieldName(fromPropertyMap);
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of getChildren type walks
	// //////////////////////////////////////////////////////////////////////////////
	/**
	 * The documents filed in the folder. A qualifier ({@code IN_FOLDER(d, 'id')}) adds nothing to the
	 * query (R219): the parser refuses one that names no row of the FROM, every JOIN this server answers
	 * joins on {@code cmis:objectId} (one object in every row), and the row's type is narrowed already
	 * — the FROM's type by its filter, a JOIN's type by the JOIN's. The term this used to add asked an
	 * index field {@code type} that does not exist, so Solr refused every qualified IN_FOLDER and
	 * IN_TREE — FROM's alias, the type's name, a JOIN's alias alike — and the client got a 500
	 * (measured on bedroom, closing batch 26).
	 */
	private Query walkInFolder(CmisTree paramNode) {
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
		// Through the same rule as every other field name here. A no-op for a static property
		// (the map answers "parent_id"), but the rule has no exemptions — the lock counts calls
		// of the map, not shapes, so a future static name with a special character is covered.
		String parentIdField = queryableFieldName(
				solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.PARENT_ID));
		Term t = new Term(parentIdField, folderId);
		return new TermQuery(t);
	}

	/** The documents filed in the folder or below it. A qualifier adds nothing, as in {@link #walkInFolder}. */
	private Query walkInTree(CmisTree paramNode) {
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
		return walkInTreeInternal(folderId, this.repositoryId);
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
	private Query walkContains(CmisTree qualNode, CmisTree queryNode) {
		if (qualNode != null) {
			// The qualifier adds nothing, as in walkInFolder: every row is the same object.
			BooleanQuery.Builder builder = new BooleanQuery.Builder();
			builder.add(walkSearchExpr(queryNode), Occur.MUST);

			return builder.build();
		}
		return walkSearchExpr(queryNode);
	}

	private Query walkSearchExpr(CmisTree node) {
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
	private Query walkContainsStringLit(CmisTree node) {
		// Raw text; buildDualFieldQuery does the Solr metacharacter escaping.
		String text = node.toString();

		// Strip surrounding single quotes from CMIS syntax
		if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
			text = text.substring(1, text.length() - 1);
		}
		// Two levels of CMIS escape, undone in order (closing batch 5 review, subagent P3-1). The argument is a CMIS
		// string literal, whose \' and \\ are undone first, as walkString does for every other literal. What that
		// leaves is a text search expression, which escapes its own \, ', " and - with a backslash — OpenCMIS's text
		// search lexer refuses a bare quote there — so `it's` is written CONTAINS('it\\\'s'). The phrase quotes are
		// read between the two levels, and each word or phrase is unescaped on its own. Undoing only the literal level
		// sent the expression's escape to Solr as a backslash.
		text = text.replaceAll("\\\\(.)", "$1");
		text = text.trim();

		// Inner double quotes indicate phrase search: CONTAINS('"exact phrase"')
		if (text.length() >= 2 && text.startsWith("\"") && closesWithUnescapedQuote(text)) {
			String phrase = text.substring(1, text.length() - 1);
			return buildDualFieldQuery(unescapeTextSearch(phrase));
		}

		// Split on whitespace: CONTAINS('word1 word2') → word1 AND word2
		String[] words = text.split("\\s+");
		if (words.length > 1) {
			BooleanQuery.Builder andBuilder = new BooleanQuery.Builder();
			for (String word : words) {
				if (word.isEmpty()) continue;
				andBuilder.add(buildDualFieldQuery(unescapeTextSearch(word)), Occur.MUST);
			}
			return andBuilder.build();
		}

		return buildDualFieldQuery(unescapeTextSearch(text));
	}

	/**
	 * Whether the expression ends with a double quote that no backslash escapes — an even number of backslashes before
	 * it. Asking only whether it ends in {@code \"} read a phrase ending in an escaped backslash, {@code "a b\\"}, as
	 * two words (closing batch 6 review, subagent P3-4).
	 */
	private static boolean closesWithUnescapedQuote(String text) {
		if (!text.endsWith("\"")) {
			return false;
		}
		int backslashes = 0;
		for (int i = text.length() - 2; i >= 0 && text.charAt(i) == '\\'; i--) {
			backslashes++;
		}
		return backslashes % 2 == 0;
	}

	/** A text search expression's own escapes undone: \\, \', \" and \- are the characters themselves. */
	private static String unescapeTextSearch(String expression) {
		return expression.replaceAll("\\\\(.)", "$1");
	}

	private Query walkTextAnd(CmisTree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (int i = 0; i < node.getChildCount(); i++) {
			CmisTree child = node.getChild(i);
			builder.add(walkSearchExpr(child), Occur.MUST);
		}
		return builder.build();
	}

	private Query walkTextOr(CmisTree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (int i = 0; i < node.getChildCount(); i++) {
			CmisTree child = node.getChild(i);
			builder.add(walkSearchExpr(child), Occur.SHOULD);
		}
		return builder.build();
	}

	private Query walkTextMinus(CmisTree node) {
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (int i = 0; i < node.getChildCount(); i++) {
			CmisTree child = node.getChild(i);
			builder.add(walkSearchExpr(child), Occur.MUST);
		}
		return builder.build();
	}

	private Query walkTextWord(CmisTree node) {
		// Pass the RAW word to buildDualFieldQuery, which is the single place that
		// escapes Solr query-string metacharacters. (Pre-escaping here for ':'
		// used to double-escape once buildDualFieldQuery also escaped backslashes.)
		String nodeText = node.toString();

		// If text contains spaces, split into individual words and combine with AND.
		// CMIS CONTAINS('word1 word2') means "documents containing both word1 AND word2",
		// NOT phrase matching. Each word is searched independently against dual-index fields.
		String[] words = nodeText.trim().split("\\s+");
		if (words.length > 1) {
			BooleanQuery.Builder andBuilder = new BooleanQuery.Builder();
			for (String word : words) {
				if (word.isEmpty()) continue;
				andBuilder.add(buildDualFieldQuery(word), Occur.MUST);
			}
			return andBuilder.build();
		}

		return buildDualFieldQuery(nodeText);
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
		// The term is wrapped in double quotes and later serialized via
		// Query.toString() into the Solr `q` parameter, which Solr re-parses as a
		// query string. Any bare double-quote or backslash in the user's CONTAINS()
		// text would terminate/escape the phrase and leave dangling syntax that Solr
		// resolves against its (undefined) default field `_text_`, yielding an HTTP
		// 500. Escape backslash first, then double-quote, so the round-trip is safe.
		String safe = word.replace("\\", "\\\\").replace("\"", "\\\"");
		String quoted = "\"" + safe + "\"";
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

	private Query walkTextPhrase(CmisTree node) {
		// Raw text; buildDualFieldQuery does the Solr metacharacter escaping.
		String termString = node.toString();

		// Strip surrounding single quotes from CMIS phrase syntax
		if(termString.charAt(0) == '\'' && termString.charAt(termString.length()-1) == '\'' ){
			termString = termString.substring(1, termString.length() - 1);
		}

		// Phrase search: wrap in double quotes to force exact phrase matching.
		// This is correct for walkTextPhrase (CMIS CONTAINS('"phrase here"')).
		return buildDualFieldQuery(termString);
	}

	// //////////////////////////////////////////////////////////////////////////////
	// Definition of walkExpr and its subwalks
	// These are used from various walks to evaluate a node value.
	// //////////////////////////////////////////////////////////////////////////////
	private Object walkExpr(CmisTree node) {
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

	private Object walkBoolean(CmisTree node) {
		String s = node.getText();
		return Boolean.valueOf(s);
	}

	private Object walkNumber(CmisTree node) {
		String s = node.getText();
		if (s.contains(".") || s.contains("e") || s.contains("E")) {
			return Double.valueOf(s);
		} else {
			return Long.valueOf(s);
		}
	}

	private Object walkString(CmisTree node) {
		String s = node.getText();
		s = s.substring(1, s.length() - 1);
		// CMIS's escapes undone first: \' is a quote and \\ a backslash in the VALUE. Escaping the raw text for Solr
		// kept the backslash, so 'a\'b' was searched as a, backslash, quote, b (closing batch 3 review, subagent P3-4).
		// LIKE does not come through here for its pattern — it reads the raw literal (walkLike).
		s = s.replaceAll("\\\\(.)", "$1");
		//return "\"" + ClientUtils.escapeQueryChars(s) + "\"";
		return ClientUtils.escapeQueryChars(s);
	}

	private Object walkTimestamp(CmisTree node) {
		String s = node.getText();
		s = s.substring(s.indexOf('\'') + 1, s.length() - 1);
		return s;
	}

	private Object walkList(CmisTree node) {
		int n = node.getChildCount();
		List<Object> res = new ArrayList<Object>(n);
		for (int i = 0; i < n; i++) {
			res.add(walkExpr(node.getChild(i)));
		}
		return res;
	}

	private Object walkCol(CmisTree node) {
		return null;
	}

	private Object walkId(CmisTree node) {
		String s;
		s = node.toStringTree();
		return s;
	}

	private Object walkOtherExpr(CmisTree node) {
		throw new CmisRuntimeException("Unknown node type: " + node.getType()
				+ " (" + node.getText() + ")");
	}

	// //////////////////////////////////////////////////////////////////////////////

	/**
	 * Utility methods
	 */

	private ColumnReference getColumnReference(CmisTree columnNode) {
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
	private String safeWalkExprToString(CmisTree node) {
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

		// Escape folderId before splicing into the Solr query string. CMIS
		// object IDs are typically UUIDs, but External Ingest derives IDs
		// from sourceObjectId values that are caller-supplied; characters
		// such as ':' '*' '\\' '(' ')' would otherwise inject Solr query
		// syntax (DoS via wildcards or unintended result shaping).
		query.setQuery(queryableFieldName(solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.PARENT_ID))
				+ ":" + ClientUtils.escapeQueryChars(folderId) + " AND "
				+ queryableFieldName(solrUtil.getPropertyNameInSolr(repositoryId, PropertyIds.BASE_TYPE_ID))
				+ ":cmis\\:folder"); // only "folder" nodes

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
