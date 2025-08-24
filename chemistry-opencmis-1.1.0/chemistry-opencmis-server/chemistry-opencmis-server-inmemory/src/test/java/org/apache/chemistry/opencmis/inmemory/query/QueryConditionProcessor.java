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

import java.util.List;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.server.support.query.PredicateWalkerBase;

/**
 * An interface used by the walker when traversing the AST from the grammar. The
 * interface consists of callback methods that are called when a rule is
 * processed (as part of the WHERE statement)
 */
public interface QueryConditionProcessor extends PredicateWalkerBase {

    void onStartProcessing(Tree whereNode);

    void onStopProcessing();

    // Compare operators
    void onEquals(Tree eqNode, Tree leftNode, Tree rightNode);

    void onNotEquals(Tree neNode, Tree leftNode, Tree rightNode);

    void onGreaterThan(Tree gtNode, Tree leftNode, Tree rightNode);

    void onGreaterOrEquals(Tree geNode, Tree leftNode, Tree rightNode);

    void onLessThan(Tree ltNode, Tree leftNode, Tree rightNode);

    void onLessOrEquals(Tree leqNode, Tree leftNode, Tree rightNode);

    // Boolean operators
    void onPreNot(Tree opNode, Tree leftNode);

    void onNot(Tree opNode, Tree leftNode);

    void onPostNot(Tree opNode, Tree leftNode);

    void onPreAnd(Tree opNode, Tree leftNode, Tree rightNode);

    void onAnd(Tree opNode, Tree leftNode, Tree rightNode);

    void onPostAnd(Tree opNode, Tree leftNode, Tree rightNode);

    void onPreOr(Tree opNode, Tree leftNode, Tree rightNode);

    void onOr(Tree opNode, Tree leftNode, Tree rightNode);

    void onPostOr(Tree opNode, Tree leftNode, Tree rightNode);

    // Multi-value:
    void onIn(Tree node, Tree colNode, Tree listNode);

    void onNotIn(Tree node, Tree colNode, Tree listNode);

    void onInAny(Tree node, Tree colNode, Tree listNode);

    void onNotInAny(Tree node, Tree colNode, Tree listNode);

    void onEqAny(Tree node, Tree literalNode, Tree colNode);

    // Null comparisons:
    void onIsNull(Tree nullNode, Tree colNode);

    void onIsNotNull(Tree notNullNode, Tree colNode);

    // String matching:
    void onIsLike(Tree node, Tree colNode, Tree stringNode);

    void onIsNotLike(Tree node, Tree colNode, Tree stringNode);

    // Functions:
    void onContains(Tree node, Tree typeNode, Tree searchExprNode);

    void onInFolder(Tree node, Tree colNode, Tree paramNode);

    void onInTree(Tree node, Tree colNode, Tree paramNode);

    void onScore(Tree node);

    // full text search
    void onTextAnd(Tree node, List<Tree> conjunctionNodes);

    void onTextOr(Tree node, List<Tree> termNodes);

    void onTextMinus(Tree node, Tree notNode);

    void onTextWord(String word);

    void onTextPhrase(String phrase);
}
