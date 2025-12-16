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
/**
 * CMISQL parser.
 */
parser grammar CmisQlExtParser;

options {
    tokenVocab = CmisQlExtLexer;
    output = AST;
}

import CmisBaseGrammar;

@header {
/*
 * THIS FILE IS AUTO-GENERATED, DO NOT EDIT.
 *
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
 *
 * THIS FILE IS AUTO-GENERATED, DO NOT EDIT.
 */
package org.apache.chemistry.opencmis.server.support.query;
}

@members {
    public boolean hasErrors() {
    	return gCmisBaseGrammar.hasErrors();
    }

	public String getErrorMessages() {
    	return gCmisBaseGrammar.getErrorMessages();
	}
}

query: SELECT^ DISTINCT? select_list from_clause where_clause? order_by_clause?;

value_expression:
      column_reference
    | string_value_function
    | numeric_value_function
    ;

quantified_comparison_predicate:
    literal comp_op ANY multi_valued_column_reference
      -> ^(OP_ANY comp_op literal multi_valued_column_reference)
    ;

comp_op:
    EQ | NEQ | LT | GT | LTEQ | GTEQ
    ;

string_value_function:
    ID LPAR column_reference RPAR
      -> ^(FUNC ID column_reference)
    ;
