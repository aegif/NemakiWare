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

import org.antlr.runtime.BaseRecognizer;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenStream;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.TreeParser;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.server.support.TypeManager;

/**
 * Utility class to help parsing and processing a query statement using the
 * AntLR parser Subclasses have to implement methods that setup parser and query
 * walker and parse and process a query. This class provides common methods for
 * error handling and for storing the necessary opencmis objects for query
 * support
 * 
 * @param <T>
 *            AntLR tree grammar, will usually be a CmisQueryWalker but can be
 *            custom class for customized (extended) parsers
 */
public abstract class QueryUtilBase<T extends TreeParser> {

    protected T walker;
    protected QueryObject queryObj;
    protected PredicateWalkerBase predicateWalker;
    protected String statement;

    protected CommonTree parserTree; // the ANTLR tree after parsing phase
    protected TokenStream tokens; // the ANTLR token stream

    /**
     * Perform the first phase of query processing. Setup lexer and parser,
     * parse the statement, check for syntax errors and create an AST
     * 
     * @return the abstract syntax tree of the parsed statement
     */
    public abstract CommonTree parseStatement() throws RecognitionException;

    /**
     * Perform the second phase of query processing, analyzes the select part,
     * check for semantic errors, fill the query object. Usually a walker will
     * be CmisQueryWalker (or subclass) if the supporting OpenCMIS query classes
     * are used.
     */
    public abstract void walkStatement() throws RecognitionException;

    /**
     * Fully process a query by parsing and walking it and setting up the
     * supporting objects
     */
    public void processStatement() throws RecognitionException {
        parseStatement();
        walkStatement();
    }

    protected QueryUtilBase(String statement, TypeManager tm, PredicateWalkerBase pw, QueryObject.ParserMode mode) {
        walker = null;
        queryObj = new QueryObject(tm);
        if (mode != null) {
        	queryObj.setSelectMode(mode);
        }
        predicateWalker = pw;
        this.statement = statement;
    }

    public T getWalker() {
        return walker;
    }

    public PredicateWalkerBase getPredicateWalker() {
        return predicateWalker;
    }

    public QueryObject getQueryObject() {
        return queryObj;
    }

    public String getStatement() {
        return statement;
    }

    /**
     * Same as traverseStatement but throws only CMIS Exceptions
     */
    public void processStatementUsingCmisExceptions() {
        try {
            processStatement();
        } catch (RecognitionException e) {
            String errorMsg = getErrorMessage(e);
            throw new CmisInvalidArgumentException(
                    "Processing of query statement failed with RecognitionException error: \n   " + errorMsg, e);
        } catch (CmisBaseException e) {
            throw e;
        } catch (Exception e) {
            throw new CmisInvalidArgumentException("Processing of query statement failed with exception: "
                    + e.getMessage(), e);
        }
    }

    public String getErrorMessage(RecognitionException e) {
        if (null == walker) {
            return e.toString();
        } else {
            return getErrorMessage(walker, e);
        }
    }

    private static String getErrorMessage(BaseRecognizer recognizer, RecognitionException e) {
        String[] tokenNames = recognizer.getTokenNames();
        String hdr = "Line " + e.line + ":" + e.charPositionInLine;
        String msg = recognizer.getErrorMessage(e, tokenNames);
        return hdr + " " + msg;
    }

}