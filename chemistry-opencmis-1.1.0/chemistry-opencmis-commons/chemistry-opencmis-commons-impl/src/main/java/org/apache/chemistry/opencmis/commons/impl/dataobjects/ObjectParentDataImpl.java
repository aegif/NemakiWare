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
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;

/**
 * ObjectParentData implementation.
 */
public class ObjectParentDataImpl extends AbstractExtensionData implements ObjectParentData {

    private static final long serialVersionUID = 1L;

    private ObjectData object;
    private String relativePathSegment;

    /**
     * Constructor.
     */
    public ObjectParentDataImpl() {
    }

    /**
     * Constructor.
     */
    public ObjectParentDataImpl(ObjectData object) {
        this.object = object;
    }

    @Override
    public ObjectData getObject() {
        return object;
    }

    public void setObject(ObjectData object) {
        this.object = object;
    }

    @Override
    public String getRelativePathSegment() {
        return relativePathSegment;
    }

    public void setRelativePathSegment(String relativePathSegment) {
        this.relativePathSegment = relativePathSegment;
    }

    @Override
    public String toString() {
        return "Object Parent [object=" + object + ", relative path segment=" + relativePathSegment + "]"
                + super.toString();
    }
}
