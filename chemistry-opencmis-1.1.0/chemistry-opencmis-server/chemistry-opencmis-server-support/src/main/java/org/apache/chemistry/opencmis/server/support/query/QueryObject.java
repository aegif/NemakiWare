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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.server.support.TypeManager;
import org.apache.chemistry.opencmis.server.support.TypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QueryObject is a class used to encapsulate a CMIS query. It is created from
 * an ANTLR parser on an incoming query string. During parsing various
 * informations are collected and stored in objects suitable for evaluating the
 * query (like selected properties, effected types and order statements. A query
 * evaluator can use this information to perform the query and build the result.
 */
public class QueryObject {

    private static final Logger LOG = LoggerFactory.getLogger(QueryObject.class);

    // For error handling see:
    // http://www.antlr.org/pipermail/antlr-interest/2008-April/027600.html
    // select part
    protected TypeManager typeMgr;
    protected final List<CmisSelector> selectReferences = new ArrayList<CmisSelector>();
    protected final List<CmisSelector> whereReferences = new ArrayList<CmisSelector>();
    protected final List<CmisSelector> joinReferences = new ArrayList<CmisSelector>();
    // --> Join not implemented yet
    protected final Map<String, CmisSelector> colOrFuncAlias = new HashMap<String, CmisSelector>();

    // from part
    /** map from alias name to type query name */
    protected final Map<String, String> froms = new LinkedHashMap<String, String>();

    /** main from alias name */
    protected String from = null;

    protected final List<JoinSpec> joinSpecs = new ArrayList<JoinSpec>();

    // where part
    protected final Map<Integer, CmisSelector> columnReferences = new HashMap<Integer, CmisSelector>();
    protected final Map<Integer, String> typeReferences = new HashMap<Integer, String>();

    // order by part
    protected final List<SortSpec> sortSpecs = new ArrayList<SortSpec>();

    @SuppressWarnings("serial")
    protected List<String> predefinedQueryNames = new ArrayList<String>() {
        {
            add("SEARCH_SCORE");
        }
    };

    private String errorMessage;
    
    public enum ParserMode {MODE_STRICT, MODE_ALLOW_RELAXED_SELECT};
    
    private ParserMode selectMode = ParserMode.MODE_STRICT;

    public static class JoinSpec {

        /** INNER / LEFT / RIGHT */
        public final String kind;

        /** Alias or full table type */
        public final String alias;

        public ColumnReference onLeft;

        public ColumnReference onRight;

        public JoinSpec(String kind, String alias) {
            this.kind = kind;
            this.alias = alias;
        }

        public void setSelectors(ColumnReference onLeft, ColumnReference onRight) {
            this.onLeft = onLeft;
            this.onRight = onRight;
        }

        @Override
        public String toString() {
            return "JoinReference(" + kind + "," + alias + "," + onLeft + "," + onRight + ")";
        }
    }

    public class SortSpec {
        public final boolean ascending;
        public final Integer colRefKey; // key in columnReferencesMap point to
                                        // column

        // descriptions

        public SortSpec(Integer key, boolean ascending) {
            this.colRefKey = key;
            this.ascending = ascending;
        }

        public CmisSelector getSelector() {
            return columnReferences.get(colRefKey);
        }

        public boolean isAscending() {
            return ascending;
        }
    }

    public QueryObject() {
    }

    public QueryObject(TypeManager tm) {
        typeMgr = tm;
    }

    public void setSelectMode(ParserMode mode) {
    	selectMode = mode;
    }
    
    public Map<Integer, CmisSelector> getColumnReferences() {
        return Collections.unmodifiableMap(columnReferences);
    }

    public CmisSelector getColumnReference(Integer token) {
        return columnReferences.get(token);
    }

    public String getTypeReference(Integer token) {
        return typeReferences.get(token);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // ///////////////////////////////////////////////////////
    // SELECT part

    // public accessor methods
    public List<CmisSelector> getSelectReferences() {
        return selectReferences;
    }

    public void addSelectReference(Tree node, CmisSelector selRef) {
        selectReferences.add(selRef);
        columnReferences.put(node.getTokenStartIndex(), selRef);
    }

    public void addAlias(String aliasName, CmisSelector aliasRef) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("add alias: {} for: {}", aliasName, aliasRef);
        }
        if (colOrFuncAlias.containsKey(aliasName)) {
            throw new CmisQueryException("You cannot use name " + aliasName + " more than once as alias in a select.");
        } else {
            aliasRef.setAliasName(aliasName);
            colOrFuncAlias.put(aliasName, aliasRef);
        }
    }

    public CmisSelector getSelectAlias(String aliasName) {
        return colOrFuncAlias.get(aliasName);
    }

    // ///////////////////////////////////////////////////////
    // FROM part

    public String addType(String aliasName, String typeQueryName) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("add alias: {} for: {}", aliasName, typeQueryName);
            }

            if (froms.containsKey(aliasName)) {
                throw new CmisQueryException("You cannot use name " + aliasName
                        + " more than once as alias in a from part.");
            }
            if (aliasName == null) {
                aliasName = typeQueryName;
            }
            froms.put(aliasName, typeQueryName);
            if (from == null) {
                from = aliasName;
            }
            return aliasName;
        } catch (CmisQueryException cqe) {
            errorMessage = cqe.getMessage(); // preserve message
            return null; // indicate an error to ANTLR so that it generates
                         // FailedPredicateException
        }
    }

    public String getMainTypeAlias() {
        return from;
    }

    public Map<String, String> getTypes() {
        return Collections.unmodifiableMap(froms);
    }

    public String getTypeQueryName(String qualifier) {
        return froms.get(qualifier);
    }

    public TypeDefinition getTypeDefinitionFromQueryName(String queryName) {
        return typeMgr.getTypeByQueryName(queryName);
    }

    public TypeDefinition getParentType(TypeDefinition td) {
        String parentType = td.getParentTypeId();
        return parentType == null ? null : typeMgr.getTypeById(parentType).getTypeDefinition();
    }

    public TypeDefinition getParentType(String typeId) {
        TypeDefinition td = typeMgr.getTypeById(typeId).getTypeDefinition();
        String parentType = td == null ? null : td.getParentTypeId();
        return parentType == null ? null : typeMgr.getTypeById(parentType).getTypeDefinition();
    }

    public TypeDefinition getMainFromName() {
        // as we don't support JOINS take first type
        String queryName = froms.values().iterator().next();
        TypeDefinition td = getTypeDefinitionFromQueryName(queryName);
        return td;
    }

    /**
     * return a map of all columns that have been requested in the SELECT part
     * of the statement.
     * 
     * @return a map with a String as a key and value. key is the alias if an
     *         alias was given or the query name otherwise. value is the query
     *         name of the property.
     */
    public Map<String, String> getRequestedPropertiesByAlias() {
        return getRequestedProperties(true);
    }

    private Map<String, String> getRequestedProperties(boolean byAlias) {

        Map<String, String> res = new HashMap<String, String>();
        for (CmisSelector sel : selectReferences) {
            if (sel instanceof ColumnReference) {
                ColumnReference colRef = (ColumnReference) sel;
                String key = colRef.getPropertyId();
                if (null == key) {
                    key = colRef.getPropertyQueryName(); // happens for *
                }
                String propDescr = colRef.getAliasName() == null ? colRef.getPropertyQueryName() : colRef
                        .getAliasName();
                if (byAlias) {
                    res.put(propDescr, key);
                } else {
                    res.put(key, propDescr);
                }
            }
        }
        return res;
    }

    /**
     * return a map of all functions that have been requested in the SELECT part
     * of the statement.
     * 
     * @return a map with a String as a key and value. key is the alias if an
     *         alias was given or the function name otherwise, value is the a
     *         name of the property.
     */
    public Map<String, String> getRequestedFuncsByAlias() {
        return getRequestedFuncs(true);
    }

    private Map<String, String> getRequestedFuncs(boolean byAlias) {

        Map<String, String> res = new HashMap<String, String>();
        for (CmisSelector sel : selectReferences) {
            if (sel instanceof FunctionReference) {
                FunctionReference funcRef = (FunctionReference) sel;
                String propDescr = funcRef.getAliasName() == null ? funcRef.getName() : funcRef.getAliasName();
                if (byAlias) {
                    res.put(propDescr, funcRef.getName());
                } else {
                    res.put(funcRef.getName(), propDescr);
                }
            }
        }
        return res;
    }

    // ///////////////////////////////////////////////////////
    // JOINS

    public void addJoinReference(Tree node, CmisSelector reference) {
        columnReferences.put(node.getTokenStartIndex(), reference);
        joinReferences.add(reference);
    }

    public List<CmisSelector> getJoinReferences() {
        return Collections.unmodifiableList(joinReferences);
    }

    public void addJoin(String kind, String alias, boolean hasSpec) {
        JoinSpec join = new JoinSpec(kind, alias);
        if (hasSpec) {
            // get columns from last added references
            int n = joinReferences.size();
            ColumnReference onLeft = (ColumnReference) joinReferences.get(n - 2);
            ColumnReference onRight = (ColumnReference) joinReferences.get(n - 1);
            join.setSelectors(onLeft, onRight);
        }
        joinSpecs.add(join);
    }

    public List<JoinSpec> getJoins() {
        return joinSpecs;
    }

    // ///////////////////////////////////////////////////////
    // WHERE part

    public void addWhereReference(Tree node, CmisSelector reference) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("add node to where: {}", System.identityHashCode(node));
        }
        columnReferences.put(node.getTokenStartIndex(), reference);
        whereReferences.add(reference);
    }

    public List<CmisSelector> getWhereReferences() {
        return Collections.unmodifiableList(whereReferences);
    }

    public void addWhereTypeReference(Tree node, String qualifier) {
        if (node != null) {
            typeReferences.put(node.getTokenStartIndex(), qualifier);
        }
    }

    // ///////////////////////////////////////////////////////
    // ORDER_BY part

    public List<SortSpec> getOrderBys() {
        return Collections.unmodifiableList(sortSpecs);
    }

    public void addSortCriterium(Tree node, ColumnReference colRef, boolean ascending) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("addSortCriterium: {} ascending: {}", colRef, ascending);
        }
        columnReferences.put(node.getTokenStartIndex(), colRef);
        sortSpecs.add(new SortSpec(node.getTokenStartIndex(), ascending));
    }

    /**
     * Tests if the query has a JOIN from one primary type to only secondary
     * types (This JOIN does not require a JOIN capability in CMIS).
     * 
     * @return list of secondary type ids that are joined or null if the query
     *         has no JOINs or has joins to primary types
     */
    public List<TypeDefinition> getJoinedSecondaryTypes() {
        List<TypeDefinition> secondaryTypeIds = new ArrayList<TypeDefinition>();
        Map<String, String> froms = getTypes();
        if (froms.size() == 1) {
            return null; // no JOIN in query
        }
        String mainTypeQueryName = froms.get(getMainTypeAlias());
        for (String queryName : froms.values()) {
            TypeDefinition td = getTypeDefinitionFromQueryName(queryName);
            if (queryName.equals(mainTypeQueryName)) {
                continue;
            }
            if (td.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY) {
                secondaryTypeIds.add(td);
            } else {
                return null;
            }
        }
        for (JoinSpec join : getJoins()) {
            if (!(null != join.onLeft && null != join.onRight && ((join.onRight.getPropertyId() == null
                    && join.onRight.getTypeDefinition().getBaseTypeId() == BaseTypeId.CMIS_SECONDARY && join.onLeft
                    .getPropertyId().equals(PropertyIds.OBJECT_ID)) || (join.onLeft.getPropertyId() == null
                    && join.onLeft.getTypeDefinition().getBaseTypeId() == BaseTypeId.CMIS_SECONDARY && join.onRight
                    .getPropertyId().equals(PropertyIds.OBJECT_ID))))) {
                return null;
            }
        }
        return secondaryTypeIds;
    }
    
    // ///////////////////////////////////////////////////////
    // resolve types after first pass traversing the AST is complete

    public boolean resolveTypes() {
        try {
            LOG.debug("First pass of query traversal is complete, resolving types");
            if (null == typeMgr) {
                return true;
            }

            // First resolve all alias names defined in SELECT:
            for (CmisSelector alias : colOrFuncAlias.values()) {
                if (alias instanceof ColumnReference) {
                    ColumnReference colRef = ((ColumnReference) alias);
                    resolveTypeForAlias(colRef);
                }
            }

            // Then replace all aliases used somewhere by their resolved column
            // reference:
            for (Integer obj : columnReferences.keySet()) {
                CmisSelector selector = columnReferences.get(obj);
                String key = selector.getName();
                if (colOrFuncAlias.containsKey(key)) { // it is an alias
                    CmisSelector resolvedReference = colOrFuncAlias.get(key);
                    columnReferences.put(obj, resolvedReference);
                    // Note: ^ This may replace the value in the map with the
                    // same
                    // value, but this does not harm.
                    // Otherwise we need to check if it is resolved or not which
                    // causes two more ifs:
                    // if (selector instanceof ColumnReference) {
                    // ColumnReference colRef = ((ColumnReference) selector);
                    // if (colRef.getTypeDefinition() == null) // it is not yet
                    // resolved
                    // // replace unresolved column reference by resolved on
                    // from
                    // alias map
                    // columnReferences.put(obj,
                    // colOrFuncAlias.get(selector.getAliasName()));
                    // } else
                    // columnReferences.put(obj,
                    // colOrFuncAlias.get(selector.getAliasName()));
                    if (whereReferences.remove(selector)) {
                        // replace unresolved by resolved reference
                        whereReferences.add(resolvedReference);
                    }
                    if (joinReferences.remove(selector)) {
                        // replace unresolved by resolved reference
                        joinReferences.add(resolvedReference);
                    }
                }
            }

            // The replace all remaining column references not using an alias
            for (CmisSelector select : columnReferences.values()) {
                // ignore functions here
                if (select instanceof ColumnReference) {
                    ColumnReference colRef = ((ColumnReference) select);
                    if (colRef.getTypeDefinition() == null) { // not yet
                                                              // resolved
                        if (colRef.getQualifier() == null) {
                            // unqualified select: SELECT p FROM
                            resolveTypeForColumnReference(colRef);
                        } else {
                            // qualified select: SELECT t.p FROM
                            validateColumnReferenceAndResolveType(colRef);
                        }
                    }
                }
            }

            // Replace types used as qualifiers (IN_TREE, IN_FOLDER,
            // CONTAINS) by their corresponding alias (correlation name)
            for (Entry<Integer, String> en : typeReferences.entrySet()) {
                Integer obj = en.getKey();
                String qualifier = en.getValue();
                String typeQueryName = getReferencedTypeQueryName(qualifier);
                if (typeQueryName == null) {
                    throw new CmisQueryException(qualifier + " is neither a type query name nor an alias.");
                }
                if (typeQueryName.equals(qualifier)) {
                    // try to find an alias for it
                    String alias = null;
                    for (Entry<String, String> e : froms.entrySet()) {
                        String q = e.getKey();
                        String tqn = e.getValue();
                        if (!tqn.equals(q) && typeQueryName.equals(tqn)) {
                            alias = q;
                            break;
                        }
                    }
                    if (alias != null) {
                        typeReferences.put(obj, alias);
                    }
                }
            }

            return true;
        } catch (CmisQueryException cqe) {
            errorMessage = cqe.getMessage(); // preserve message
            return false; // indicate an error to ANTLR so that it generates
                          // FailedPredicateException
        }
    }

    protected void resolveTypeForAlias(ColumnReference colRef) {
        String aliasName = colRef.getAliasName();

        if (colOrFuncAlias.containsKey(aliasName)) {
            CmisSelector selector = colOrFuncAlias.get(aliasName);
            if (selector instanceof ColumnReference) {
                colRef = (ColumnReference) selector; // alias target
                if (colRef.getQualifier() == null) {
                    // unqualified select: SELECT p FROM
                    resolveTypeForColumnReference(colRef);
                } else {
                    // qualified select: SELECT t.p FROM
                    validateColumnReferenceAndResolveType(colRef);
                }
            }
            // else --> ignore FunctionReference
        }
    }

    // for a select x from y, z ... find the type in type manager for x
    protected void resolveTypeForColumnReference(ColumnReference colRef) {
        String propName = colRef.getPropertyQueryName();
        boolean isStar = propName.equals("*");

        // it is property query name without a type, so find type
        int noFound = 0;
        TypeDefinition tdFound = null;

        if (isPredfinedQueryName(propName)) {
            return;
        }

        for (String typeQueryName : froms.values()) {
            TypeDefinition td = typeMgr.getTypeByQueryName(typeQueryName);
            if (null == td) {
                throw new CmisQueryException(typeQueryName + " is neither a type query name nor an alias.");
            } else if (isStar) {
                ++noFound;
                tdFound = null;
            } else if (TypeValidator.typeContainsPropertyWithQueryName(td, propName)) {
                ++noFound;
                tdFound = td;
            }
        }
        
        if (noFound == 0 && selectMode == ParserMode.MODE_STRICT) {
        	throw new CmisQueryException(propName + " is not a property query name in any of the types in from ...");
        } else if (noFound > 1 && !isStar) {
            throw new CmisQueryException(propName + " is not a unique property query name within the types in from ...");
        } else if (null != tdFound) {
        	validateColumnReferenceAndResolveType(tdFound, colRef);
        }
    }

    public boolean isPredfinedQueryName(String name) {
        return predefinedQueryNames.contains(name);
    }

    // for a select x.y from x ... check that x has property y and that x is in
    // from
    protected void validateColumnReferenceAndResolveType(ColumnReference colRef) {
        // either same name or mapped alias
        String typeQueryName = getReferencedTypeQueryName(colRef.getQualifier());
        TypeDefinition td = typeMgr.getTypeByQueryName(typeQueryName);
        if (null == td) {
            throw new CmisQueryException(colRef.getQualifier() + " is neither a type query name nor an alias.");
        }

        validateColumnReferenceAndResolveType(td, colRef);
    }

    protected void validateColumnReferenceAndResolveType(TypeDefinition td, ColumnReference colRef) {

        // type found, check if property exists
        boolean hasProp;
        if (colRef.getPropertyQueryName().equals("*")) {
            hasProp = true;
        } else {
            hasProp = TypeValidator.typeContainsPropertyWithQueryName(td, colRef.getPropertyQueryName());
            if (!hasProp && td.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY
                    && colRef.getPropertyQueryName().equals(PropertyIds.OBJECT_ID)) {
                hasProp = true; // special handling for object id on secondary
                                // types which are required for JOINS
            }
        }
        if (!hasProp) {
            throw new CmisQueryException(colRef.getPropertyQueryName() + " is not a valid property query name in type "
                    + td.getId() + ".");
        }

        colRef.setTypeDefinition(typeMgr.getPropertyIdForQueryName(td, colRef.getPropertyQueryName()), td);
    }

    // return type query name for a referenced column (which can be the name
    // itself or an alias
    protected String getReferencedTypeQueryName(String qualifier) {
        String typeQueryName = froms.get(qualifier);
        if (null == typeQueryName) {
            // if an alias was defined but still the original is used we have to
            // search case: SELECT T.p FROM T AS TAlias
            String q = null;
            for (String tqn : froms.values()) {
                if (qualifier.equals(tqn)) {
                    if (q != null) {
                        throw new CmisQueryException(qualifier + " is an ambiguous type query name.");
                    }
                    q = tqn;
                }
            }
            return q;
        } else {
            return typeQueryName;
        }
    }

}
