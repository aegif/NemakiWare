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
 * Contributors:
 *     Florent Guillaume, Nuxeo
 */
package org.apache.chemistry.opencmis.server.support.query;

import java.util.ArrayList;
import java.util.List;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

/**
 * Basic implementation walking a predicate in lexical order.
 * <p>
 * The {@code walkXYZ} methods can be overridden to change the walking order.
 */
public abstract class AbstractPredicateWalker implements PredicateWalker {

    @Override
    public Boolean walkPredicate(Tree node) {
        switch (node.getType()) {
        case CmisQlStrictLexer.NOT:
            return walkNot(node, node.getChild(0));
        case CmisQlStrictLexer.AND:
            return walkAnd(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.OR:
            return walkOr(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.EQ:
            return walkEquals(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.NEQ:
            return walkNotEquals(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.GT:
            return walkGreaterThan(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.GTEQ:
            return walkGreaterOrEquals(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.LT:
            return walkLessThan(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.LTEQ:
            return walkLessOrEquals(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.IN:
            return walkIn(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.NOT_IN:
            return walkNotIn(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.IN_ANY:
            return walkInAny(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.NOT_IN_ANY:
            return walkNotInAny(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.EQ_ANY:
            return walkEqAny(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.IS_NULL:
            return walkIsNull(node, node.getChild(0));
        case CmisQlStrictLexer.IS_NOT_NULL:
            return walkIsNotNull(node, node.getChild(0));
        case CmisQlStrictLexer.LIKE:
            return walkLike(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.NOT_LIKE:
            return walkNotLike(node, node.getChild(0), node.getChild(1));
        case CmisQlStrictLexer.CONTAINS:
            if (node.getChildCount() == 1) {
                return walkContains(node, null, node.getChild(0));
            } else {
                return walkContains(node, node.getChild(0), node.getChild(1));
            }
        case CmisQlStrictLexer.IN_FOLDER:
            if (node.getChildCount() == 1) {
                return walkInFolder(node, null, node.getChild(0));
            } else {
                return walkInFolder(node, node.getChild(0), node.getChild(1));
            }
        case CmisQlStrictLexer.IN_TREE:
            if (node.getChildCount() == 1) {
                return walkInTree(node, null, node.getChild(0));
            } else {
                return walkInTree(node, node.getChild(0), node.getChild(1));
            }
        case CmisQlStrictLexer.BOOL_LIT:
            walkBoolean(node);
            return false;
        case CmisQlStrictLexer.NUM_LIT:
            walkNumber(node);
            return false;
        case CmisQlStrictLexer.STRING_LIT:
            walkString(node);
            return false;
        case CmisQlStrictLexer.TIME_LIT:
            walkTimestamp(node);
            return false;
        case CmisQlStrictLexer.IN_LIST:
            walkList(node);
            return false;
        case CmisQlStrictLexer.COL:
            walkCol(node);
            return false;
        case CmisQlStrictLexer.ID:
            walkId(node);
            return false;
        case CmisQlStrictLexer.SCORE:
            return walkScore(node);
        default:
            return walkOtherPredicate(node);
        }
    }


    /** For extensibility. */
    protected Boolean walkOtherPredicate(Tree node) {
        throw new CmisRuntimeException("Unknown node type: " + node.getType() + " (" + node.getText() + ")");
    }

    @Override
    public Boolean walkNot(Tree opNode, Tree node) {
        walkPredicate(node);
        return false;
    }

    @Override
    public Boolean walkAnd(Tree opNode, Tree leftNode, Tree rightNode) {
        walkPredicate(leftNode);
        walkPredicate(rightNode);
        return false;
    }

    @Override
    public Boolean walkOr(Tree opNode, Tree leftNode, Tree rightNode) {
        walkPredicate(leftNode);
        walkPredicate(rightNode);
        return false;
    }

    @Override
    public Object walkExpr(Tree node) {
        switch (node.getType()) {
        case CmisQlStrictLexer.BOOL_LIT:
            return walkBoolean(node);
        case CmisQlStrictLexer.NUM_LIT:
            return walkNumber(node);
        case CmisQlStrictLexer.STRING_LIT:
            return walkString(node);
        case CmisQlStrictLexer.TIME_LIT:
            return walkTimestamp(node);
        case CmisQlStrictLexer.IN_LIST:
            return walkList(node);
        case CmisQlStrictLexer.COL:
            return walkCol(node);
        case CmisQlStrictLexer.ID:
            return walkId(node);
        default:
            return walkOtherExpr(node);
        }
    }

    public Boolean walkSearchExpr(Tree node) {
        switch (node.getType()) {
        case TextSearchLexer.TEXT_AND:
            return walkTextAnd(node);
        case TextSearchLexer.TEXT_OR:
            return walkTextOr(node);
        case TextSearchLexer.TEXT_MINUS:
            return walkTextMinus(node);
        case TextSearchLexer.TEXT_SEARCH_WORD_LIT:
            return walkTextWord(node);
        case TextSearchLexer.TEXT_SEARCH_PHRASE_STRING_LIT:
            return walkTextPhrase(node);
        default:
            walkOtherExpr(node);
            return null;
        }
    }

    /** For extensibility. */
    protected Object walkOtherExpr(Tree node) {
        throw new CmisRuntimeException("Unknown node type: " + node.getType() + " (" + node.getText() + ")");
    }

    @Override
    public Boolean walkEquals(Tree opNode, Tree leftNode, Tree rightNode) {
        walkExpr(leftNode);
        walkExpr(rightNode);
        return false;
    }

    @Override
    public Boolean walkNotEquals(Tree opNode, Tree leftNode, Tree rightNode) {
        walkExpr(leftNode);
        walkExpr(rightNode);
        return false;
    }

    @Override
    public Boolean walkGreaterThan(Tree opNode, Tree leftNode, Tree rightNode) {
        walkExpr(leftNode);
        walkExpr(rightNode);
        return false;
    }

    @Override
    public Boolean walkGreaterOrEquals(Tree opNode, Tree leftNode, Tree rightNode) {
        walkExpr(leftNode);
        walkExpr(rightNode);
        return false;
    }

    @Override
    public Boolean walkLessThan(Tree opNode, Tree leftNode, Tree rightNode) {
        walkExpr(leftNode);
        walkExpr(rightNode);
        return false;
    }

    @Override
    public Boolean walkLessOrEquals(Tree opNode, Tree leftNode, Tree rightNode) {
        walkExpr(leftNode);
        walkExpr(rightNode);
        return false;
    }

    @Override
    public Boolean walkIn(Tree opNode, Tree colNode, Tree listNode) {
        walkExpr(colNode);
        walkExpr(listNode);
        return false;
    }

    @Override
    public Boolean walkNotIn(Tree opNode, Tree colNode, Tree listNode) {
        walkExpr(colNode);
        walkExpr(listNode);
        return false;
    }

    @Override
    public Boolean walkInAny(Tree opNode, Tree colNode, Tree listNode) {
        walkExpr(colNode);
        walkExpr(listNode);
        return false;
    }

    @Override
    public Boolean walkNotInAny(Tree opNode, Tree colNode, Tree listNode) {
        walkExpr(colNode);
        walkExpr(listNode);
        return false;
    }

    @Override
    public Boolean walkEqAny(Tree opNode, Tree literalNode, Tree colNode) {
        walkExpr(literalNode);
        walkExpr(colNode);
        return false;
    }

    @Override
    public Boolean walkIsNull(Tree opNode, Tree colNode) {
        walkExpr(colNode);
        return false;
    }

    @Override
    public Boolean walkIsNotNull(Tree opNode, Tree colNode) {
        walkExpr(colNode);
        return false;
    }

    @Override
    public Boolean walkLike(Tree opNode, Tree colNode, Tree stringNode) {
        walkExpr(colNode);
        walkExpr(stringNode);
        return false;
    }

    @Override
    public Boolean walkNotLike(Tree opNode, Tree colNode, Tree stringNode) {
        walkExpr(colNode);
        walkExpr(stringNode);
        return false;
    }

    @Override
    public Boolean walkContains(Tree opNode, Tree qualNode, Tree queryNode) {
        if (qualNode != null) {
            return walkSearchExpr(qualNode);
        }
        return walkSearchExpr(queryNode);
    }

    @Override
    public Boolean walkInFolder(Tree opNode, Tree qualNode, Tree paramNode) {
        if (qualNode != null) {
            walkExpr(qualNode);
        }
        walkExpr(paramNode);
        return false;
    }

    @Override
    public Boolean walkInTree(Tree opNode, Tree qualNode, Tree paramNode) {
        if (qualNode != null) {
            walkExpr(qualNode);
        }
        walkExpr(paramNode);
        return false;
    }

    @Override
    public Object walkList(Tree node) {
        int n = node.getChildCount();
        List<Object> res = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) {
            res.add(walkExpr(node.getChild(i)));
        }
        return res;
    }

    @Override
    public Object walkBoolean(Tree node) {
        String s = node.getText();
        return Boolean.valueOf(s);
    }

    @Override
    public Object walkNumber(Tree node) {
        String s = node.getText();
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.valueOf(s);
        } else {
            return Long.valueOf(s);
        }
    }

    @Override
    public Object walkString(Tree node) {
        String s = node.getText();
        s = s.substring(1, s.length() - 1);
        s = s.replace("''", "'"); // unescape quotes
        return s;
    }

    @Override
    public Object walkTimestamp(Tree node) {
        String s = node.getText();
        s = s.substring(s.indexOf('\'') + 1, s.length() - 1);
        return CalendarHelper.fromString(s);
    }

    @Override
    public Object walkCol(Tree node) {
        return null;
    }

    @Override
    public Object walkId(Tree node) {
        return null;
    }
    
    protected Boolean walkTextAnd(Tree node) {
        return null;
    }
    
    protected Boolean walkTextOr(Tree node) {
        return null;
    }
    
    protected Boolean walkTextMinus(Tree node) {
        return null;
    }
    
    protected Boolean walkTextWord(Tree node) {
        return null;
    }
    
    protected Boolean walkTextPhrase(Tree node) {
        return null;
    }
    
    protected Boolean walkScore(Tree node) {
       return false;        
    }

}
