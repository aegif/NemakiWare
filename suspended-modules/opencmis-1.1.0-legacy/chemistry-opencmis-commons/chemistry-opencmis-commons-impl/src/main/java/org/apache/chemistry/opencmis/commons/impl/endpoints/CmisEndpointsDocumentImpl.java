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
package org.apache.chemistry.opencmis.commons.impl.endpoints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.chemistry.opencmis.commons.endpoints.CmisAuthentication;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpoint;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;

public class CmisEndpointsDocumentImpl extends LinkedHashMap<String, Object> implements CmisEndpointsDocument {

    private static final long serialVersionUID = 1L;

    public CmisEndpointsDocumentImpl() {
    }

    public CmisEndpointsDocumentImpl(List<CmisEndpoint> endpoints) {
        put(KEY_ENDPOINTS, endpoints);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<CmisEndpoint> getEndpoints() {
        Object endpoints = get(KEY_ENDPOINTS);

        if (endpoints instanceof List) {
            return Collections.unmodifiableList((List<CmisEndpoint>) endpoints);
        }

        return Collections.emptyList();
    }

    @Override
    public List<CmisAuthentication> getAuthenticationsSortedByPreference() {
        List<CmisAuthentication> result = new ArrayList<CmisAuthentication>();

        for (CmisEndpoint endpoint : getEndpoints()) {
            for (CmisAuthentication authentication : endpoint.getAuthentications()) {
                result.add(authentication);
            }
        }

        Collections.sort(result, new Comparator<CmisAuthentication>() {
            @Override
            public int compare(CmisAuthentication ap1, CmisAuthentication ap2) {
                if (ap1.getPreference() == null && ap2.getPreference() == null) {
                    return 0;
                }
                if (ap1.getPreference() == null) {
                    return 1;
                }
                if (ap2.getPreference() == null) {
                    return -1;
                }

                return ap1.getPreference().compareTo(ap2.getPreference());
            }
        });

        return result;
    }
}
