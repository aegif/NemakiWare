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
tree grammar CmisQueryWalker;

options {
    tokenVocab = CmisQlStrictLexer;
    ASTLabelType = CommonTree;
    output = AST;
}

import CmisBaseWalker;

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
 *     Florent Guillaume, Nuxeo
 *
 * THIS FILE IS AUTO-GENERATED, DO NOT EDIT.
 */
package org.apache.chemistry.opencmis.server.support.query;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
}


@members {
    public Tree getWherePredicateTree() {
        return gCmisBaseWalker.getWherePredicateTree();
    }

    protected void mismatch(IntStream input, int ttype, BitSet follow) throws RecognitionException
    {
        gCmisBaseWalker.mismatch(input, ttype, follow);
    }

    public void recoverFromMismatchedSet(IntStream input, RecognitionException e, antlr.collections.impl.BitSet follow) throws RecognitionException
    {
        gCmisBaseWalker.recoverFromMismatchedSet(input, e, follow);
    }

    public void setDoFullTextParse(boolean value) {
        gCmisBaseWalker.setDoFullTextParse(value);
    }
	
    public boolean getDoFullTextParse() {
        return gCmisBaseWalker.getDoFullTextParse();
    }
    
    public int getNumberOfContainsClauses() {
        return gCmisBaseWalker.getNumberOfContainsClauses();
    }
	
}

// For CMIS SQL it will be sufficient to stop on first error:
@rulecatch {
    catch (RecognitionException e) {
        throw e;
    }
}

root [QueryObject go, PredicateWalkerBase pw] throws CmisQueryException:
      query [go, pw];