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
 *     Stefane Fermigier, Nuxeo
 *     Florent Guillaume, Nuxeo
 */
 
parser grammar CmisBaseGrammar;

// Note: The following tokenVocab option generates a warning: 
// warning(160): CMISBaseGrammar.g:27:5: tokenVocab option ignored in imported gram
// mar CMISBaseGrammar
// If you omit this it results in compilation errors, may be a maven issue (not sure
// of outside of maven this needs file needs to be compilable
// options must have AST options otherwise 

options {
    tokenVocab = CmisQlStrictLexer;
    output = AST;
}

@members {
    private List<String> errorMessages = new ArrayList<String>();
    
    public boolean hasErrors() {
    	return errorMessages.size() > 0;
    }

	public String getErrorMessages() {
		StringBuffer allMessages = new StringBuffer();
		
		for (String msg : errorMessages)
			allMessages.append(msg).append('\n');
			
		return allMessages.toString();
	}

    @Override
    // Instead of sending all errors to System.err collect them in a list
	public void emitErrorMessage(String msg) {
		errorMessages.add(msg);
	}
	
}

query: 
	SELECT select_list from_clause where_clause? order_by_clause?
		-> ^(SELECT select_list from_clause order_by_clause? where_clause?)
	;

select_list
    : STAR
    | select_sublist ( COMMA select_sublist )*
      -> ^(SEL_LIST select_sublist+)
    ;

select_sublist
    : value_expression ( AS!? column_name )?
    | qualifier DOT STAR
    //| multi_valued_column_reference
    ;

value_expression:
      column_reference
    | numeric_value_function
    ;

column_reference:
    ( qualifier DOT )? column_name
      -> ^(COL qualifier? column_name)
    ;

multi_valued_column_reference:
    ( qualifier DOT )? multi_valued_column_name
      -> ^(COL qualifier? multi_valued_column_name)
    ;

numeric_value_function:
    SCORE LPAR RPAR -> ^(SCORE);

qualifier:
      table_name
    //| correlation_name
    ;

from_clause: FROM^ table_reference;

table_reference:
    one_table table_join*
    ;

table_join:
    join_kind one_table join_specification?
    -> ^(JOIN join_kind one_table join_specification?)
    ;

one_table:
      LPAR! table_reference RPAR!
    | table_name
        -> ^(TABLE table_name)
    | table_name AS? correlation_name
        -> ^(TABLE table_name correlation_name)
    ;

join_kind:
      JOIN
        -> INNER
    | INNER JOIN
        -> INNER
    | LEFT OUTER? JOIN
        -> LEFT
    | RIGHT OUTER? JOIN
        -> RIGHT
    ;

join_specification:
    ON^ column_reference EQ column_reference
    ;

///////////////////////////////////////////////////////////
// Begin Where

where_clause: WHERE^ search_condition;

search_condition:
    boolean_term (OR^ boolean_term)*;

boolean_term:
    boolean_factor (AND^ boolean_factor)*;

boolean_factor:
    NOT^ boolean_test
    | boolean_test
	;
	
boolean_test:
      predicate
    | LPAR search_condition RPAR -> search_condition
    ;

predicate:
      comparison_predicate
    | in_predicate
    | like_predicate
    | null_predicate
    | quantified_comparison_predicate
    | quantified_in_predicate
    | text_search_predicate
    | folder_predicate
    ;

comparison_predicate:
    value_expression EQ literal
      -> ^(EQ value_expression literal)
    | value_expression NEQ literal
      -> ^(NEQ value_expression literal)
    | value_expression LT literal
      -> ^(LT value_expression literal)
    | value_expression GT literal
      -> ^(GT value_expression literal)
    | value_expression LTEQ literal
      -> ^(LTEQ value_expression literal)
    | value_expression GTEQ literal
      -> ^(GTEQ value_expression literal)
    ;

literal:
      NUM_LIT
    | STRING_LIT
    | TIME_LIT
    | BOOL_LIT
    ;

in_predicate:
      column_reference IN LPAR in_value_list RPAR
        -> ^(IN column_reference in_value_list)
    | column_reference NOT IN LPAR in_value_list RPAR
        -> ^(NOT_IN column_reference in_value_list)
    ;

in_value_list:
    literal ( COMMA literal )*
      -> ^(IN_LIST literal+)
    ;

like_predicate:
      column_reference LIKE STRING_LIT
        -> ^(LIKE column_reference STRING_LIT)
    | column_reference NOT LIKE STRING_LIT
        -> ^(NOT_LIKE column_reference STRING_LIT)
    ;

null_predicate:
    // second alternative commented out to remove left recursion for now.
    //( column_reference | multi_valued_column_reference ) 'IS' 'NOT'? 'NULL';
    column_reference IS
      ( NOT NULL -> ^(IS_NOT_NULL column_reference)
      | NULL     -> ^(IS_NULL     column_reference)
      )
    ;

quantified_comparison_predicate:
    literal EQ ANY multi_valued_column_reference
      -> ^(EQ_ANY literal multi_valued_column_reference)
    ;

quantified_in_predicate:
    ANY multi_valued_column_reference
      ( NOT IN LPAR in_value_list RPAR
          -> ^(NOT_IN_ANY multi_valued_column_reference in_value_list )
      | IN     LPAR in_value_list RPAR
          -> ^(IN_ANY    multi_valued_column_reference in_value_list )
      )
    ;

text_search_predicate :// returns [CommonTree result]:
    CONTAINS LPAR (qualifier COMMA)? text_search_expression RPAR
      -> ^(CONTAINS qualifier? text_search_expression) //{ 
//      $result = StringUtil.parseTextSearchPredicate($text_search_expression.text)
//    }
    ;

// end where
/////////////////////////////////////////////////////////////////

folder_predicate:
    ( f=IN_FOLDER | f=IN_TREE ) LPAR (qualifier COMMA)? folder_id RPAR
      -> ^($f qualifier? folder_id)
    ;

order_by_clause:
    ORDER BY sort_specification ( COMMA sort_specification )*
      -> ^(ORDER_BY sort_specification+)
    ;

sort_specification:
      column_reference -> column_reference ASC
    | column_reference ( ASC | DESC )
    ;

correlation_name:
    ID;

table_name:
    ID;

column_name:
    ID;

multi_valued_column_name:
    ID;

folder_id:
    STRING_LIT;

text_search_expression:
    STRING_LIT;
