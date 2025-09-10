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
 * CMISQL lexer.
 */
lexer grammar CmisBaseLexer;

tokens {
    TABLE;
    COL;
    SEL_LIST;
    IN_LIST;
    IN_ANY;
    NOT_IN_ANY;
    EQ_ANY;
    NOT_IN;
    NOT_LIKE;
    IS_NULL;
    IS_NOT_NULL;
    ORDER_BY;
}

@members {
    private List<String> errorMessages = new ArrayList<String>();
    
    public boolean hasErrors() {
    	return !errorMessages.isEmpty();
    }

	public String getErrorMessages() {
		StringBuilder allMessages = new StringBuilder();
		
		for (String msg : errorMessages) {
			allMessages.append(msg).append('\n');
		}
			
		return allMessages.toString();
	}

    @Override
    // Instead of sending all errors to System.err collect them in a list
	public void emitErrorMessage(String msg) {
		errorMessages.add(msg);
	}
	
}

// ----- Generic SQL -----

SELECT : ('S'|'s')('E'|'e')('L'|'l')('E'|'e')('C'|'c')('T'|'t');
//DISTINCT : ('D'|'d')('I'|'i')('S'|'s')('T'|'t')('I'|'i')('N'|'n')('C'|'c')('T'|'t');
FROM : ('F'|'f')('R'|'r')('O'|'o')('M'|'m');
AS : ('A'|'a')('S'|'s');
JOIN : ('J'|'j')('O'|'o')('I'|'i')('N'|'n');
INNER : ('I'|'i')('N'|'n')('N'|'n')('E'|'e')('R'|'r');
OUTER : ('O'|'o')('U'|'u')('T'|'t')('E'|'e')('R'|'r');
LEFT : ('L'|'l')('E'|'e')('F'|'f')('T'|'t');
RIGHT : ('R'|'r')('I'|'i')('G'|'g')('H'|'h')('T'|'t');
ON : ('O'|'o')('N'|'n');
WHERE : ('W'|'w')('H'|'h')('E'|'e')('R'|'r')('E'|'e');
ORDER : ('O'|'o')('R'|'r')('D'|'d')('E'|'e')('R'|'r');
BY : ('B'|'b')('Y'|'y');
ASC : ('A'|'a')('S'|'s')('C'|'c');
DESC : ('D'|'d')('E'|'e')('S'|'s')('C'|'c');

// ----- Operators -----

IS : ('I'|'i')('S'|'s');
NULL : ('N'|'n')('U'|'u')('L'|'l')('L'|'l');
AND : ('A'|'a')('N'|'n')('D'|'d');
OR : ('O'|'o')('R'|'r');
NOT : ('N'|'n')('O'|'o')('T'|'t');
IN : ('I'|'i')('N'|'n');
LIKE : ('L'|'l')('I'|'i')('K'|'k')('E'|'e');
ANY : ('A'|'a')('N'|'n')('Y'|'y');
CONTAINS : ('C'|'c')('O'|'o')('N'|'n')('T'|'t')('A'|'a')('I'|'i')('N'|'n')('S'|'s');
SCORE : ('S'|'s')('C'|'c')('O'|'o')('R'|'r')('E'|'e');
IN_FOLDER : ('I'|'i')('N'|'n')'_'('F'|'f')('O'|'o')('L'|'l')('D'|'d')('E'|'e')('R'|'r');
IN_TREE : ('I'|'i')('N'|'n')'_'('T'|'t')('R'|'r')('E'|'e')('E'|'e');
TIMESTAMP : 'TIMESTAMP'|'timestamp';

STAR : '*';
LPAR : '(';
RPAR : ')';
COMMA : ',';
DOT : '.';
EQ : '=';
NEQ : '<>';
LT : '<';
GT : '>';
LTEQ : '<=';
GTEQ : '>=';

// ----- Literals -----

BOOL_LIT : 'TRUE' | 'true' | 'FALSE' | 'false';

fragment Sign : ('+'|'-')?;
fragment Digits : ('0'..'9')+;
fragment ExactNumLit : Digits DOT Digits | Digits DOT | DOT Digits | Digits;
fragment ApproxNumLit : ExactNumLit ('e'|'E') Sign Digits;
NUM_LIT : Sign (ExactNumLit | ApproxNumLit);

fragment QUOTE: '\'';
fragment BACKSL: '\\';
fragment UNDERSCORE: '_';
fragment PERCENT: '%';

// An escape sequence is two backslashes for backslash, backslash single quote for single quote
// or single quote single quote for single quote
fragment
ESC 
	: BACKSL (QUOTE | BACKSL | PERCENT | UNDERSCORE)
	| QUOTE QUOTE
	;
	
STRING_LIT
    :  QUOTE ( ESC | ~(BACKSL|QUOTE) )* QUOTE
	;

WS : ( ' ' | '\t' | '\r'? '\n' )* { $channel=HIDDEN; };

TIME_LIT : TIMESTAMP WS STRING_LIT;

ID :
    ('a'..'z'|'A'..'Z'|'_')
    ('a'..'z'|'A'..'Z'|'_'|'0'..'9'|':')*
    ;
