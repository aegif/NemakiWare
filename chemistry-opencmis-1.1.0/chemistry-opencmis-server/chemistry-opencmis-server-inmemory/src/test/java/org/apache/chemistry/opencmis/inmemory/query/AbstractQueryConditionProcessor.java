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
package org.apache.chemistry.opencmis.inmemory.query;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.server.support.query.CalendarHelper;
import org.apache.chemistry.opencmis.server.support.query.CmisQlStrictLexer;
import org.apache.chemistry.opencmis.server.support.query.PredicateWalkerBase;
import org.apache.chemistry.opencmis.server.support.query.StringUtil;
import org.apache.chemistry.opencmis.server.support.query.TextSearchLexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractQueryConditionProcessor implements PredicateWalkerBase {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessQueryTest.class);

    protected abstract void onStartProcessing(Tree whereNode);

    protected abstract void onStopProcessing();

    // Compare operators
    protected void onPreEquals(Tree eqNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onEquals(Tree eqNode, Tree leftNode, Tree rightNode);

    protected void onPostEquals(Tree eqNode, Tree leftNode, Tree rightNode) {
    }

    protected void onPreNotEquals(Tree neNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onNotEquals(Tree neNode, Tree leftNode, Tree rightNode);

    protected void onPostNotEquals(Tree neNode, Tree leftNode, Tree rightNode) {
    }

    protected void onPreGreaterThan(Tree gtNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onGreaterThan(Tree gtNode, Tree leftNode, Tree rightNode);

    protected void onPostGreaterThan(Tree gtNode, Tree leftNode, Tree rightNode) {
    }

    protected void onPreGreaterOrEquals(Tree geNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onGreaterOrEquals(Tree geNode, Tree leftNode, Tree rightNode);

    protected void onPostGreaterOrEquals(Tree geNode, Tree leftNode, Tree rightNode) {
    }

    protected void onPreLessThan(Tree ltNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onLessThan(Tree ltNode, Tree leftNode, Tree rightNode);

    protected void onPostLessThan(Tree ltNode, Tree leftNode, Tree rightNode) {
    }

    protected void onPreLessOrEquals(Tree leqNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onLessOrEquals(Tree leqNode, Tree leftNode, Tree rightNode);

    protected void onPostLessOrEquals(Tree leqNode, Tree leftNode, Tree rightNode) {
    }

    // Boolean operators
    protected abstract void onNot(Tree opNode, Tree leftNode);

    protected void onPostNot(Tree opNode, Tree leftNode) {
    }

    protected void onPreAnd(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onAnd(Tree opNode, Tree leftNode, Tree rightNode);

    protected void onPostAnd(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    protected void onPreOr(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    protected abstract void onOr(Tree opNode, Tree leftNode, Tree rightNode);

    protected void onPostOr(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    // Multi-value:
    protected void onPreIn(Tree node, Tree colNode, Tree listNode) {
    }

    protected abstract void onIn(Tree node, Tree colNode, Tree listNode);

    protected void onPostIn(Tree node, Tree colNode, Tree listNode) {
    }

    protected void onPreNotIn(Tree node, Tree colNode, Tree listNode) {
    }

    protected abstract void onNotIn(Tree node, Tree colNode, Tree listNode);

    protected void onPostNotIn(Tree node, Tree colNode, Tree listNode) {
    }

    protected void onPreInAny(Tree node, Tree colNode, Tree listNode) {
    }

    protected abstract void onInAny(Tree node, Tree colNode, Tree listNode);

    protected void onPostInAny(Tree node, Tree colNode, Tree listNode) {
    }

    protected void onPreNotInAny(Tree node, Tree colNode, Tree listNode) {
    }

    protected abstract void onNotInAny(Tree node, Tree literalNode, Tree colNode);

    protected void onPostNotInAny(Tree node, Tree colNode, Tree listNode) {
    }

    protected void onPreEqAny(Tree node, Tree literalNode, Tree colNode) {
    }

    protected abstract void onEqAny(Tree node, Tree literalNode, Tree colNode);

    protected void onPostEqAny(Tree node, Tree literalNode, Tree colNode) {
    }

    // Null comparisons:
    protected abstract void onIsNull(Tree nullNode, Tree colNode);

    protected void onPostIsNull(Tree nullNode, Tree colNode) {
    }

    protected abstract void onIsNotNull(Tree notNullNode, Tree colNode);

    protected void onPostIsNotNull(Tree notNullNode, Tree colNode) {
    }

    // String matching:
    protected void onPreIsLike(Tree node, Tree colNode, Tree stringNode) {
    }

    protected abstract void onIsLike(Tree node, Tree colNode, Tree stringNode);

    protected void onPostIsLike(Tree node, Tree colNode, Tree stringNode) {
    }

    protected void onPreIsNotLike(Tree node, Tree colNode, Tree stringNode) {
    }

    protected abstract void onIsNotLike(Tree node, Tree colNode, Tree stringNode);

    protected void onPostIsNotLike(Tree node, Tree colNode, Tree stringNode) {
    }

    protected abstract void onInFolder(Tree node, Tree colNode, Tree paramNode);

    protected void onBetweenInFolder(Tree node, Tree colNode, Tree paramNode) {
    }

    protected void onPostInFolder(Tree node, Tree colNode, Tree paramNode) {
    }

    protected abstract void onInTree(Tree node, Tree colNode, Tree paramNode);

    protected void onBetweenInTree(Tree node, Tree colNode, Tree paramNode) {
    }

    protected void onPostInTree(Tree node, Tree colNode, Tree paramNode) {
    }

    protected abstract void onScore(Tree node);

    protected abstract void onColNode(Tree node);

    protected void onPreTextAnd(Tree node, List<Tree> conjunctionNodes) {
    }

    protected abstract void onTextAnd(Tree node, List<Tree> conjunctionNodes, int index);

    protected void onPostTextAnd(Tree node, List<Tree> conjunctionNodes) {
    }

    protected void onPreTextOr(Tree node, List<Tree> termNodes) {
    }

    protected abstract void onTextOr(Tree node, List<Tree> termNodes, int index);

    protected void onPostTextOr(Tree node, List<Tree> termNodes) {
    }

    protected abstract void onTextMinus(Tree node, Tree notNode);

    protected void onPostTextMinus(Tree node, Tree notNode) {
    }

    protected abstract void onTextWord(String word);

    protected abstract void onTextPhrase(String phrase);

    // Base interface called from query parser
    @Override
    public Boolean walkPredicate(Tree whereNode) {
        if (null != whereNode) {
            onStartProcessing(whereNode);
            evalWhereNode(whereNode);
            onStopProcessing();
        }
        return null; // unused
    }

    // ///////////////////////////////////////////////////////
    // Processing the WHERE clause

    protected void evalWhereNode(Tree node) {
        // Ensure that we receive only valid tokens and nodes in the where
        // clause:
        LOG.debug("evaluating node: " + node.toString());
        switch (node.getType()) {
        case CmisQlStrictLexer.WHERE:
            break; // ignore
        case CmisQlStrictLexer.EQ:
            onPreEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostEquals(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.NEQ:
            onPreNotEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onNotEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostNotEquals(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.GT:
            onPreGreaterThan(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onGreaterThan(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostGreaterThan(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.GTEQ:
            onPreGreaterOrEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onGreaterOrEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostGreaterOrEquals(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.LT:
            onPreLessThan(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onLessThan(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostLessThan(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.LTEQ:
            onPreLessOrEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onLessOrEquals(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostLessOrEquals(node, node.getChild(0), node.getChild(1));
            break;

        case CmisQlStrictLexer.NOT:
            onNot(node, node.getChild(0));
            evalWhereNode(node.getChild(0));
            onPostNot(node, node.getChild(0));
            break;
        case CmisQlStrictLexer.AND:
            onPreAnd(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onAnd(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostAnd(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.OR:
            onPreOr(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onOr(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostOr(node, node.getChild(0), node.getChild(1));
            break;

        // Multi-value:
        case CmisQlStrictLexer.IN:
            onPreIn(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onIn(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostIn(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.NOT_IN:
            onPreNotIn(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onNotIn(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostNotIn(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.IN_ANY:
            onPreInAny(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onInAny(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostInAny(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.NOT_IN_ANY:
            onPreNotInAny(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onNotInAny(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostNotInAny(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.EQ_ANY:
            onPreEqAny(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onEqAny(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostEqAny(node, node.getChild(0), node.getChild(1));
            break;

        // Null comparisons:
        case CmisQlStrictLexer.IS_NULL:
            onIsNull(node, node.getChild(0));
            evalWhereNode(node.getChild(0));
            onPostIsNull(node, node.getChild(0));
            break;
        case CmisQlStrictLexer.IS_NOT_NULL:
            onIsNotNull(node, node.getChild(0));
            evalWhereNode(node.getChild(0));
            onPostIsNotNull(node, node.getChild(0));
            break;

        // String matching
        case CmisQlStrictLexer.LIKE:
            onPreIsLike(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onIsLike(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostIsLike(node, node.getChild(0), node.getChild(1));
            break;
        case CmisQlStrictLexer.NOT_LIKE:
            onPreIsNotLike(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(0));
            onIsNotLike(node, node.getChild(0), node.getChild(1));
            evalWhereNode(node.getChild(1));
            onPostIsNotLike(node, node.getChild(0), node.getChild(1));
            break;

        // Functions
        case CmisQlStrictLexer.CONTAINS:
            Tree typeNode = node.getChildCount() == 1 ? null : node.getChild(0);
            Tree textSearchNode = node.getChildCount() == 1 ? node.getChild(0) : node.getChild(1);

            onPreContains(node, typeNode, textSearchNode);
            if (node.getChildCount() > 1) {
                evalWhereNode(typeNode);
                onBetweenContains(node, typeNode, textSearchNode);
            }
            onContains(node, typeNode, textSearchNode);
            break;
        case CmisQlStrictLexer.IN_FOLDER:
            if (node.getChildCount() == 1) {
                onInFolder(node, null, node.getChild(0));
                evalWhereNode(node.getChild(0));
                onPostInFolder(node, null, node.getChild(0));
            } else {
                onInFolder(node, node.getChild(0), node.getChild(1));
                evalWhereNode(node.getChild(0));
                onBetweenInFolder(node, node.getChild(0), node.getChild(1));
                evalWhereNode(node.getChild(1));
                onPostInFolder(node, node.getChild(0), node.getChild(1));
            }
            break;
        case CmisQlStrictLexer.IN_TREE:
            if (node.getChildCount() == 1) {
                onInTree(node, null, node.getChild(0));
                evalWhereNode(node.getChild(0));
                onPostInTree(node, null, node.getChild(0));
            } else {
                onInTree(node, node.getChild(0), node.getChild(1));
                evalWhereNode(node.getChild(0));
                onBetweenInTree(node, node.getChild(0), node.getChild(1));
                evalWhereNode(node.getChild(1));
                onPostInTree(node, node.getChild(0), node.getChild(1));
            }
            break;
        case CmisQlStrictLexer.SCORE:
            onScore(node);
            break;
        case CmisQlStrictLexer.COL:
            onColNode(node);
            break;
        case CmisQlStrictLexer.BOOL_LIT:
        case CmisQlStrictLexer.NUM_LIT:
        case CmisQlStrictLexer.STRING_LIT:
        case CmisQlStrictLexer.TIME_LIT:
            onLiteral(node);
            break;
        case CmisQlStrictLexer.IN_LIST:
            onLiteralList(node);
            break;
        case CmisQlStrictLexer.ID:
            onId(node);
            break;
        default:
            // do nothing;
        }
    }

    protected void onPreContains(Tree node, Tree typeNode, Tree searchExprNode) {
    }

    protected void onContains(Tree node, Tree typeNode, Tree searchExprNode) {
        evalTextSearchNode(typeNode, searchExprNode);
    }

    protected void onBetweenContains(Tree node, Tree typeNode, Tree searchExprNode) {
    }

    protected void evalTextSearchNode(Tree typeNode, Tree node) {
        // Ensure that we receive only valid tokens and nodes in the where
        // clause:
        LOG.debug("evaluating node: " + node.toString());
        switch (node.getType()) {
        case TextSearchLexer.TEXT_AND:
            List<Tree> children = getChildrenAsList(node);
            onPreTextAnd(node, children);
            int i = 0;
            for (Tree child : children) {
                evalTextSearchNode(typeNode, child);
                onTextAnd(node, children, i++);
            }
            onPostTextAnd(node, children);
            break;
        case TextSearchLexer.TEXT_OR:
            children = getChildrenAsList(node);
            onPreTextOr(node, children);
            int j = 0;
            for (Tree child : children) {
                evalTextSearchNode(typeNode, child);
                onTextOr(node, children, j++);
            }
            onPostTextOr(node, children);
            break;
        case TextSearchLexer.TEXT_MINUS:
            onTextMinus(node, node.getChild(0));
            evalTextSearchNode(typeNode, node.getChild(0));
            onPostTextMinus(node, node.getChild(0));
            break;
        case TextSearchLexer.TEXT_SEARCH_PHRASE_STRING_LIT:
            onTextPhrase(onTextLiteral(node));
            break;
        case TextSearchLexer.TEXT_SEARCH_WORD_LIT:
            onTextWord(onTextLiteral(node));
            break;
        }
    }

    // helper functions that are needed by most query tree walkers

    protected Object getLiteral(Tree node) {
        int type = node.getType();
        String text = node.getText();
        switch (type) {
        case CmisQlStrictLexer.BOOL_LIT:
            return Boolean.parseBoolean(node.getText());
        case CmisQlStrictLexer.NUM_LIT:
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.parseDouble(text);
            } else {
                return Long.parseLong(text);
            }
        case CmisQlStrictLexer.STRING_LIT:
            return text.substring(1, text.length() - 1);
        case CmisQlStrictLexer.TIME_LIT:
            GregorianCalendar gc = CalendarHelper.fromString(text.substring(text.indexOf('\'') + 1,
                    text.lastIndexOf('\'')));
            return gc;
        default:
            throw new RuntimeException("Unknown literal. " + node);
        }
    }

    protected Object onLiteral(Tree node) {
        return getLiteral(node);
    }

    protected String onId(Tree node) {
        return node.getText();
    }

    protected String onTextLiteral(Tree node) {
        int type = node.getType();
        String text = node.getText();
        switch (type) {
        case TextSearchLexer.TEXT_SEARCH_PHRASE_STRING_LIT:
            return StringUtil.unescape(text.substring(1, text.length() - 1), null);
        case TextSearchLexer.TEXT_SEARCH_WORD_LIT:
            return StringUtil.unescape(text, null);
        default:
            throw new RuntimeException("Unknown text literal. " + node);
        }
    }

    protected List<Object> onLiteralList(Tree node) {
        List<Object> res = new ArrayList<Object>(node.getChildCount());
        for (int i = 0; i < node.getChildCount(); i++) {
            Tree literal = node.getChild(i);
            res.add(getLiteral(literal));
        }
        return res;
    }

    protected List<Tree> getChildrenAsList(Tree node) {
        List<Tree> res = new ArrayList<Tree>(node.getChildCount());
        for (int i = 0; i < node.getChildCount(); i++) {
            Tree childNnode = node.getChild(i);
            res.add(childNnode);
        }
        return res;
    }
}
