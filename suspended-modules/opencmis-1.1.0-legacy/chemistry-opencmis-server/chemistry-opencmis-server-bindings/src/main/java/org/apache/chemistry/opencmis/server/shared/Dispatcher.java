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
package org.apache.chemistry.opencmis.server.shared;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLStreamException;

import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatcher for the AtomPub and Browser binding servlet.
 */
public class Dispatcher implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String BASE_URL_ATTRIBUTE = "org.apache.chemistry.opencmis.baseurl";

    public static final String METHOD_GET = "GET";
    public static final String METHOD_HEAD = "HEAD";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_PUT = "PUT";
    public static final String METHOD_DELETE = "DELETE";

    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class.getName());

    private final boolean caseSensitive;
    private final Map<String, ServiceCall> serviceCallMap;

    public Dispatcher() {
        this(true);
    }

    public Dispatcher(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        serviceCallMap = new HashMap<String, ServiceCall>();
    }

    /**
     * Connects a resource and HTTP method with an object that handles the call.
     */
    public void addResource(String resource, String httpMethod, ServiceCall serviceCall) {
        serviceCallMap.put(getKey(resource, httpMethod), serviceCall);
    }

    /**
     * Handles the a call.
     * 
     * @return <code>true</code> if an object was found that can handle the
     *         request, <code>false</code> otherwise.
     */
    public boolean dispatch(String resource, String httpMethod, CallContext context, CmisService service,
            String repositoryId, HttpServletRequest request, HttpServletResponse response) {
        ServiceCall serviceCall = serviceCallMap.get(getKey(resource, httpMethod));
        if (serviceCall == null) {
            return false;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(repositoryId + " / " + resource + ", " + httpMethod + " -> " + serviceCall.getClass().getName());
        }

        try {
            serviceCall.serve(context, service, repositoryId, request, response);
        } catch (CmisBaseException ce) {
            throw ce;
        } catch (XMLStreamException xse) {
            throw new CmisInvalidArgumentException("Invalid XML!", xse);
        } catch (JSONParseException jpe) {
            throw new CmisInvalidArgumentException("Invalid JSON!", jpe);
        } catch (Exception e) {
            throw new CmisRuntimeException(e.getMessage(), e);
        }

        return true;
    }

    /**
     * Generates a map key from a resource and an HTTP method.
     */
    private String getKey(String resource, String httpMethod) {
        String s = resource + "/" + httpMethod;
        return (caseSensitive ? s : s.toUpperCase());
    }
}
