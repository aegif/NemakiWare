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

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;

/**
 * ObjectInFolderData implementation.
 */
public class ObjectInFolderDataImpl extends AbstractExtensionData implements ObjectInFolderData {

    private static final long serialVersionUID = 1L;

    private ObjectData object;
    private String pathSegment;

    /**
     * Constructor.
     */
    public ObjectInFolderDataImpl() {
    }

    /**
     * Constructor.
     */
    public ObjectInFolderDataImpl(ObjectData object) {
        if (object == null) {
            throw new IllegalArgumentException("Object must be set!");
        }

        this.object = object;
    }

    @Override
    public ObjectData getObject() {
        return object;
    }

    public void setObject(ObjectData object) {
        if (object == null) {
            throw new IllegalArgumentException("Object must be set!");
        }

        this.object = object;
    }

    @Override
    public String getPathSegment() {
        return pathSegment;
    }

    public void setPathSegment(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    @Override
    public String toString() {
        return "ObjectInFolder [object=" + object + ", path segment=" + pathSegment + "]" + super.toString();
    }
}
