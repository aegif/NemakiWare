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
package org.apache.chemistry.opencmis.commons.impl.server;

import org.apache.chemistry.opencmis.commons.server.LinkInfo;

/**
 * Implementation of the {@link LinkInfo} interface.
 */
public class LinkInfoImpl implements LinkInfo {

    private String rel;
    private String href;
    private String type = null;
    private String id = null;

    public LinkInfoImpl() {
    }

    public LinkInfoImpl(String rel, String href) {
        this.rel = rel;
        this.href = href;
    }

    public LinkInfoImpl(String rel, String href, String type) {
        this(rel, href);
        this.type = type;
    }

    public LinkInfoImpl(String rel, String href, String type, String id) {
        this(rel, href, type);
        this.id = id;
    }

    @Override
    public String getRel() {
        return rel;
    }

    public void setRel(String rel) {
        this.rel = rel;
    }

    @Override
    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    @Override
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
