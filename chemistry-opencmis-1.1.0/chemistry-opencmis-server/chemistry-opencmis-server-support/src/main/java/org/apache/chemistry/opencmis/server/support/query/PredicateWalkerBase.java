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

import org.antlr.runtime.tree.Tree;

/**
 * Base interface for a tree walker of a WHERE clause.
 * <p>
 * COntains only a single method used by the ANTLR query walker to initiate a
 * tree walk for evaluating the query. You can inherit from this interface if
 * you want to have your own walking mechanism
 * </p>
 */
public interface PredicateWalkerBase {

    Boolean walkPredicate(Tree whereNode);

}
