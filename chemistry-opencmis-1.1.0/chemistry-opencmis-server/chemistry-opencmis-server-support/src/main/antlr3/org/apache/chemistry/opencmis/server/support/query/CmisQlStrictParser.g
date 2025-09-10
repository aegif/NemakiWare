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
*/

parser grammar CmisQlStrictParser;

// Note: ANTLR is very sensitive to compilation errors, you must
// have first options, then import and then @header.
// @header must only be in derived grammars not in base grammars
// no package declarations in base grammars


options {
    tokenVocab = CmisQlStrictLexer;
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
    private List<String> errorMessages = new ArrayList<String>();

    public boolean hasErrors() {
    	return errorMessages.size() > 0 ||  gCmisBaseGrammar.hasErrors();
    }

	public String getStrictParserErrorMessages() {
		StringBuffer allMessages = new StringBuffer();
		
		for (String msg : errorMessages)
			allMessages.append(msg).append('\n');
			
		return allMessages.toString();
	}
	
	public String getErrorMessages() {
	    if (errorMessages.size() > 0) {
	        return getStrictParserErrorMessages();
	    } else {
    	    return gCmisBaseGrammar.getErrorMessages();
    	}
	}
	
	@Override
    // Instead of sending all errors to System.err collect them in a list
	public void emitErrorMessage(String msg) {
		errorMessages.add(msg);
	}
	
}

  // Rules can't be empty so we have one dummy rule here
root : query EOF;
