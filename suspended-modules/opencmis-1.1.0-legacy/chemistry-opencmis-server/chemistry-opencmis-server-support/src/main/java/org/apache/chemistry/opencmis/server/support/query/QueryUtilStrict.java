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
 */
package org.apache.chemistry.opencmis.server.support.query;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CharStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenStream;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.server.support.TypeManager;
import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictParser.root_return;

public class QueryUtilStrict extends QueryUtilBase<CmisQueryWalker> {

    /* the ANTLR tree after parsing phase */
    private CommonTree parserTree;
    /* the ANTLR token stream */
    private TokenStream tokens;
    private boolean parseFulltext = true;

    public QueryUtilStrict(String statement, TypeManager tm, PredicateWalkerBase pw) {
        super(statement, tm, pw, null);
    }

    public QueryUtilStrict(String statement, TypeManager tm, PredicateWalkerBase pw, boolean parseFulltext) {
        super(statement, tm, pw, null);
        this.parseFulltext = parseFulltext;
    }

    public QueryUtilStrict(String statement, TypeManager tm, PredicateWalkerBase pw, boolean parseFulltext, QueryObject.ParserMode mode) {
        super(statement, tm, pw, mode);
        this.parseFulltext = parseFulltext;
    }

    @Override
    public CommonTree parseStatement() throws RecognitionException {
        CharStream input = new ANTLRStringStream(statement);
        CmisQlStrictLexer lexer = new CmisQlStrictLexer(input);
        tokens = new CommonTokenStream(lexer);
        CmisQlStrictParser parser = new CmisQlStrictParser(tokens);

        root_return parsedStatement = parser.root();
        if (lexer.hasErrors()) {
            throw new CmisInvalidArgumentException(lexer.getErrorMessages());
        } else if (parser.hasErrors()) {
            throw new CmisInvalidArgumentException(parser.getErrorMessages());
        }

        parserTree = (CommonTree) parsedStatement.getTree();
        return parserTree;
    }

    @Override
    public void walkStatement() throws RecognitionException {

        if (null == parserTree) {
            throw new CmisQueryException("You must parse the query before you can walk it.");
        }

        CommonTreeNodeStream nodes = new CommonTreeNodeStream(parserTree);
        nodes.setTokenStream(tokens);
        walker = new CmisQueryWalker(nodes);
        walker.setDoFullTextParse(parseFulltext);
        walker.query(queryObj, predicateWalker);
        walker.getWherePredicateTree();
    }

}
