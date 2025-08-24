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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;

/**
 * TypeDefinitionList implementation.
 */
public class TypeDefinitionListImpl extends AbstractExtensionData implements TypeDefinitionList {

    private static final long serialVersionUID = 1L;

    private List<TypeDefinition> list;
    private Boolean hasMoreItems = Boolean.FALSE;
    private BigInteger numItems;

    /**
     * Default constructor.
     */
    public TypeDefinitionListImpl() {
    }

    /**
     * Constructor from a list.
     * 
     * @param list
     *            the initial list
     */
    public TypeDefinitionListImpl(List<TypeDefinition> list) {
        this.list = list;
        this.hasMoreItems = Boolean.FALSE;
        this.numItems = BigInteger.valueOf(list.size());
    }

    @Override
    public List<TypeDefinition> getList() {
        return list;
    }

    public void setList(List<TypeDefinition> list) {
        this.list = list;
    }

    @Override
    public Boolean hasMoreItems() {
        return hasMoreItems;
    }

    public void setHasMoreItems(Boolean hasMoreItems) {
        this.hasMoreItems = hasMoreItems;
    }

    @Override
    public BigInteger getNumItems() {
        return numItems;
    }

    public void setNumItems(BigInteger numItems) {
        this.numItems = numItems;
    }

    @Override
    public String toString() {
        return "Type Definition List [list=" + list + ", has more items=" + hasMoreItems + ", num items=" + numItems
                + "]" + super.toString();
    }
}
