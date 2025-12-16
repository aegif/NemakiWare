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
package org.apache.chemistry.opencmis.client.runtime;

import java.io.Serializable;

import org.apache.chemistry.opencmis.client.api.ObjectId;

/**
 * Implementation of <code>ObjectId</code>.
 */
public class ObjectIdImpl implements ObjectId, Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /**
     * Constructor.
     */
    public ObjectIdImpl(String id) {
        if (id == null || id.length() == 0) {
            throw new IllegalArgumentException("Id must be set!");
        }

        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Sets the id.
     */
    public void setId(String id) {
        if (id == null || id.length() == 0) {
            throw new IllegalArgumentException("Id must be set!");
        }

        this.id = id;
    }

    @Override
    public String toString() {
        return "Object Id: " + id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        return id.equals(((ObjectIdImpl) obj).id);
    }
}
