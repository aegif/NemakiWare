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
 * Authors: Jens
 */

/**
 * CMISQL parser.
 http://stackoverflow.com/questions/504402/how-to-handle-escape-sequences-in-string-literals-in-antlr-3
 */
grammar TextSearch;

options {
    ASTLabelType = CommonTree;
    output = AST;
}

tokens {
    TEXT_AND;
	TEXT_OR;
	TEXT_MINUS;
}

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
 * THIS FILE IS AUTO-GENERATED, DO NOT EDIT.
 */

package org.apache.chemistry.opencmis.server.support.query;
}

@lexer::header {
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
 * THIS FILE IS AUTO-GENERATED, DO NOT EDIT.
 */

package org.apache.chemistry.opencmis.server.support.query;
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

@lexer::members {
	public void reportError(RecognitionException e) {
	   super.reportError(e);
		throw new RuntimeException(e);
	}
}

@rulecatch {
    catch (RecognitionException e) {
        throw e;
    }
}

//////////////////////////////////////////////////////////////////////////7
// Lexer Part

AND : ('A'|'a')('N'|'n')('D'|'d');
OR : ('O'|'o')('R'|'r');
TEXT_MINUS : '-';

// test search related tokens

fragment 
QUOTE: '\'';

fragment 
DOUBLE_QUOTE: '\"';

fragment 
BACKSL: '\\';

// An escape sequence is two backslashes for backslash, backslash single quote for single quote
// single quote single quote for single quote
fragment
ESC 
	: BACKSL (QUOTE | DOUBLE_QUOTE | BACKSL | TEXT_MINUS | '*' | '?')
// add this line if you want to support double single quote as escaped quote for full text as in SQL-92	
//	| { ((CharStream)input).LT(2)=='\'' }? => QUOTE QUOTE
	;
	
WS : ( ' ' | '\t' | '\r'? '\n' )+ { $channel=HIDDEN; };

fragment
TEXT_SEARCH_PHRASE_STRING 
   : 
     ( ESC 
       | ~(BACKSL | DOUBLE_QUOTE | TEXT_MINUS | QUOTE)
     )+
   ;
   
TEXT_SEARCH_PHRASE_STRING_LIT
    : DOUBLE_QUOTE TEXT_SEARCH_PHRASE_STRING DOUBLE_QUOTE
	;

	// a literal for text search is a very generic rule and matches almost anything
TEXT_SEARCH_WORD_LIT 
   : 
     ( ESC 
       | ~(BACKSL | QUOTE | ' ' | '\t' | '\r' | '\n' | '-')
     )+
   ;

// ----- Parser -----
	
text_search_expression
    : conjunct (OR conjunct)+
	    -> ^(TEXT_OR conjunct+)
	| conjunct
	;

conjunct
    : term (AND? term)+
	    -> ^(TEXT_AND term+)
	  | term
	;
	
term
    : TEXT_MINUS^? (word | phrase)
	;
	
phrase
    : TEXT_SEARCH_PHRASE_STRING_LIT
	;

word
    : TEXT_SEARCH_WORD_LIT 
	;
	
