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

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;

/**
 * ObjectInFolderContainer implementation.
 */
public class ObjectInFolderContainerImpl extends AbstractExtensionData implements ObjectInFolderContainer {

    private static final long serialVersionUID = 1L;

    private ObjectInFolderData object;
    private List<ObjectInFolderContainer> children;

    /**
     * Constructor.
     */
    public ObjectInFolderContainerImpl() {
    }

    /**
     * Constructor.
     */
    public ObjectInFolderContainerImpl(ObjectInFolderData object) {
        if (object == null) {
            throw new IllegalArgumentException("Object must be set!");
        }

        this.object = object;
    }

    @Override
    public ObjectInFolderData getObject() {
        return object;
    }

    public void setObject(ObjectInFolderData object) {
        if (object == null) {
            throw new IllegalArgumentException("Object must be set!");
        }

        this.object = object;
    }

    @Override
    public List<ObjectInFolderContainer> getChildren() {
        if (children == null) {
            children = new ArrayList<ObjectInFolderContainer>(0);
        }

        return children;
    }

    public void setChildren(List<ObjectInFolderContainer> children) {
        this.children = children;
    }

    @Override
    public String toString() {
        return "ObjectInFolder Container [object=" + object + ", children=" + children + "]";
    }
}
