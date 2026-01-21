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
package org.apache.chemistry.opencmis.client.runtime.cache;

import java.io.Serializable;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Session;

/**
 * Implements a session cache providing following capabilities:
 * <ul>
 * <li>access CmisObject by object object ID</li>
 * <li>access CmisObject by object path</li>
 * </ul>
 */
public interface Cache extends Serializable {

    void initialize(Session session, Map<String, String> parameters);

    boolean containsId(String objectId, String cacheKey);

    boolean containsPath(String path, String cacheKey);

    void put(CmisObject object, String cacheKey);

    void putPath(String path, CmisObject object, String cacheKey);

    CmisObject getById(String objectId, String cacheKey);

    CmisObject getByPath(String path, String cacheKey);

    String getObjectIdByPath(String path);

    void remove(String objectId);

    void removePath(String path);

    void clear();

    int getCacheSize();
}
