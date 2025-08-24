/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * Authors:
 *     Florent Guillaume, Nuxeo
 */
/**
 * CMISQL tree grammar, walker for the inmemory implementation.
 * This aims at implementing proper semantics without any speed
 * optimization.
 */
tree grammar CmisBaseWalker;

options {
    tokenVocab = CmisQlStrictLexer;
    ASTLabelType = CommonTree;
    output = AST;
}

@members {
    private QueryObject queryObj;
    private Tree wherePredicateTree;
    private boolean doFullTextParse = true;
    private int noContains = 0;

    public Tree getWherePredicateTree() {
        return wherePredicateTree;
    }

    protected void mismatch(IntStream input, int ttype, BitSet follow)
        throws RecognitionException
    {
        throw new MismatchedTokenException(ttype, input);
    }

    public void recoverFromMismatchedSet(IntStream input, RecognitionException e, antlr.collections.impl.BitSet follow)
        throws RecognitionException
    {
        throw e;
    }

	public void setDoFullTextParse(boolean value) {
		doFullTextParse = value;
	}
	
	public boolean getDoFullTextParse() {
		return doFullTextParse;
	}
	
	public int getNumberOfContainsClauses() {
	    return noContains;
	}
	
    private static CommonTree parseTextSearchPredicate(String expr) throws RecognitionException {
        String unescapedExpr = StringUtil.unescape(expr.substring(1, expr.length()-1), null);
        CharStream input = new ANTLRStringStream(unescapedExpr);
        TokenSource lexer = new TextSearchLexer(input);
        TokenStream tokens = new CommonTokenStream(lexer);
        TextSearchParser parser = new TextSearchParser(tokens);

        try {
            TextSearchParser.text_search_expression_return parsedStatement = parser.text_search_expression();
            return (CommonTree) parsedStatement.getTree();
        } catch (RecognitionException e) {
            String[] tokenNames = parser.getTokenNames();
            String hdr = "Error in text search expression, line " + e.line + ":" + e.charPositionInLine;
            String msg = parser.getErrorMessage(e, tokenNames);
            throw new RuntimeException(hdr + " " + msg, e);
        }
    }

}


// For CMIS SQL it will be sufficient to stop on first error:
@rulecatch {
    catch (RecognitionException e) {
        throw e;
    }
}

query [QueryObject qo, PredicateWalkerBase pw]
    @init {
        queryObj = qo;
    }:
    ^(SELECT select_list from_clause order_by_clause? where_clause)
    {
        wherePredicateTree = $where_clause.tree==null ? null : $where_clause.tree.getChild(0);
        boolean resolved = queryObj.resolveTypes();
        if (null != pw && null != $where_clause.tree)
            pw.walkPredicate(wherePredicateTree);
    }
    {
        resolved
    }?
    ;
    catch[FailedPredicateException e]
    {
        // change default text to preserved text which is useful
        e.predicateText = queryObj.getErrorMessage();
        throw e;
    }

select_list:
      STAR
      {
            queryObj.addSelectReference($STAR, new ColumnReference($STAR.text));
      }
    | ^(SEL_LIST select_sublist+)
    ;

select_sublist
    scope { String current; }
    :
      value_expression column_name?
      {
          // add selector
          queryObj.addSelectReference($value_expression.start, $value_expression.result);
          // add alias for column
          if ($column_name.text != null) {
             queryObj.addAlias($column_name.text, $value_expression.result);
          }
      }
    | s=qualifier DOT STAR
      {
            queryObj.addSelectReference($s.start, new ColumnReference($qualifier.value, $STAR.text));
      }
    ;


value_expression returns [CmisSelector result]:
      column_reference
      {
          $result = $column_reference.result;
      }
    | SCORE^
        {
            $result = new FunctionReference(FunctionReference.CmisQlFunction.SCORE);
        }
    ;

column_reference returns [ColumnReference result]:
    ^(COL qualifier? column_name)
      {
          $result = new ColumnReference($qualifier.value, $column_name.text);
      }
    ;

multi_valued_column_reference returns [ColumnReference result]:
    ^(COL qualifier? column_name)
      {
          $result = new ColumnReference($qualifier.value, $column_name.text);
      }
    ;

qualifier returns [String value]:
      table_name
//    | correlation_name
    {
      $value = $table_name.text;
    }
    ;

from_clause:
    ^(FROM table_reference)
    ;

table_reference:
    one_table table_join*
    ;

table_join:
    ^(JOIN join_kind one_table join_specification?)
    {
        boolean hasSpec = $join_specification.tree != null;
        queryObj.addJoin($join_kind.kind, $one_table.alias, hasSpec);
    }
    ;

one_table returns [String alias]:
    ^(TABLE table_name correlation_name?)
      {
          ($alias = queryObj.addType($correlation_name.text, $table_name.text)) != null
      }?
    ;
    catch[FailedPredicateException e]
    {
        // change default text to preserved text which is useful
        e.predicateText = queryObj.getErrorMessage();
        throw e;
    }

join_kind returns [String kind]:
      INNER { $kind = "INNER"; }
    | LEFT  { $kind = "LEFT"; }
    | RIGHT { $kind = "RIGHT"; }
    ;

join_specification:
    ^(ON cr1=column_reference EQ cr2=column_reference)
    {
        queryObj.addJoinReference($cr1.start, $cr1.result);
        queryObj.addJoinReference($cr2.start, $cr2.result);
    }
    ;

where_clause:
      ^(WHERE search_condition)
    | /* nothing */
    ;

search_condition
@init {
    List<Object> listLiterals;
}:
    ^(OR s1=search_condition s2=search_condition)
    | ^(AND s1=search_condition s2=search_condition)
    | ^(NOT search_condition)
    | ^(EQ search_condition search_condition)
    | ^(NEQ search_condition search_condition)
    | ^(LT search_condition search_condition)
    | ^(GT search_condition search_condition)
    | ^(GTEQ search_condition search_condition)
    | ^(LTEQ search_condition search_condition)
    | ^(LIKE search_condition search_condition)
    | ^(NOT_LIKE search_condition search_condition)
    | ^(IS_NULL search_condition)
    | ^(IS_NOT_NULL search_condition)
    | ^(EQ_ANY literal mvcr=multi_valued_column_reference)
      {
            queryObj.addWhereReference($mvcr.start, $mvcr.result);
      }
    | ^(IN_ANY mvcr=multi_valued_column_reference in_value_list )
      {
            queryObj.addWhereReference($mvcr.start, $mvcr.result);
      }
    | ^(NOT_IN_ANY mvcr=multi_valued_column_reference in_value_list)
      {
            queryObj.addWhereReference($mvcr.start, $mvcr.result);
      }
    | ^(CONTAINS qualifier? text_search_expression)
      {
            queryObj.addWhereTypeReference($qualifier.start, $qualifier.value);
            ++noContains;
      }
    | ^(IN_FOLDER qualifier? search_condition)
      {
            queryObj.addWhereTypeReference($qualifier.start, $qualifier.value);
      }
    | ^(IN_TREE qualifier? search_condition)
      {
            queryObj.addWhereTypeReference($qualifier.start, $qualifier.value);
      }
    | ^(IN column_reference in_value_list)
      {
            queryObj.addWhereReference($column_reference.start, $column_reference.result);
      }
    | ^(NOT_IN column_reference in_value_list)
      {
            queryObj.addWhereReference($column_reference.start, $column_reference.result);
      }
    | value_expression
      {
          queryObj.addWhereReference($value_expression.start, $value_expression.result);
      }
    | literal
    ;

in_value_list returns [Object inList]
@init {
    List<Object> inLiterals = new ArrayList<Object>();
}:
    ^(IN_LIST (l=literal {inLiterals.add($l.value);})+ )
    {
        $inList = inLiterals;
    }
    ;

text_search_expression
@init {
    CommonTree tse = null;
}
@after {
   if (doFullTextParse) {
       $tree = tse;
   }
} :
    STRING_LIT
    {
        if (doFullTextParse) {
            tse = parseTextSearchPredicate($STRING_LIT.text);
        }
    }
    ;


literal returns [Object value]:
      NUM_LIT
        {
            try {
                $value = Long.valueOf($NUM_LIT.text);
            } catch (NumberFormatException e) {
                $value = new BigDecimal($NUM_LIT.text);
            }
        }
    | STRING_LIT
        {
            String s = $STRING_LIT.text;
            $value = s!= null ? s.substring(1, s.length() - 1) : null;
        }
    | TIME_LIT
        {
            String s = $TIME_LIT.text;
            s = s!= null ? s.substring(s.indexOf('\'') + 1, s.length() - 1) : null;
        }
    | BOOL_LIT
        {
            $value = Boolean.valueOf($BOOL_LIT.text);
        }
    ;

order_by_clause:
    ^(ORDER_BY sort_specification+)
    ;

sort_specification:
    column_reference ASC
    {
       queryObj.addSortCriterium($column_reference.start, $column_reference.result, true);
    }
    | column_reference DESC
    {
       queryObj.addSortCriterium($column_reference.start, $column_reference.result, false);
    }
    ;

correlation_name:
    ID;

table_name:
    ID;

column_name:
    ID;
