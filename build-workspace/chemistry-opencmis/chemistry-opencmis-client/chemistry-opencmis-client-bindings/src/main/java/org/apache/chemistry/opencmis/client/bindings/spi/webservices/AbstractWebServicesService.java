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
package org.apache.chemistry.opencmis.client.bindings.spi.webservices;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.impl.RepositoryInfoCache;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisFilterNotValidException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNameConstraintViolationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Base class for all Web Services clients.
 */
public abstract class AbstractWebServicesService {

    private static final String ADDITIONAL_DATA_NS = "http://chemistry.apache.org/opencmis/exception";
    private static final String ADDITIONAL_DATA_TAG = "additionalData";
    private BindingSession session;

    /**
     * Sets the current session.
     */
    protected void setSession(BindingSession session) {
        this.session = session;
    }

    /**
     * Gets the current session.
     */
    protected BindingSession getSession() {
        return session;
    }

    /**
     * Converts a Web Services Exception into a CMIS Client exception.
     */
    protected CmisBaseException convertException(CmisException ex) {
        if ((ex == null) || (ex.getFaultInfo() == null)) {
            return new CmisRuntimeException("CmisException has no fault!");
        }

        String msg = ex.getFaultInfo().getMessage();
        BigInteger code = ex.getFaultInfo().getCode();

        String errorContent = null;
        Map<String, String> additionalData = null;
        if (!ex.getFaultInfo().getAny().isEmpty()) {
            StringBuilder sb = new StringBuilder(1024);

            for (Object o : ex.getFaultInfo().getAny()) {
                if (o != null) {
                    if (o instanceof Node) {
                        Node node = (Node) o;

                        if (ADDITIONAL_DATA_NS.equals(node.getNamespaceURI())
                                && ADDITIONAL_DATA_TAG.equals(node.getNodeName())) {
                            NodeList entries = node.getChildNodes();
                            int n = entries.getLength();
                            for (int i = 0; i < n; i++) {
                                Node entry = entries.item(i);
                                if (!"entry".equals(entry.getNodeName())) {
                                    continue;
                                }

                                String key = null;
                                String value = null;

                                NodeList keyValueList = entry.getChildNodes();
                                int n2 = keyValueList.getLength();
                                for (int j = 0; j < n2; j++) {
                                    Node item = keyValueList.item(j);
                                    if ("key".equals(item.getNodeName())) {
                                        key = item.getTextContent();
                                    } else if ("value".equals(item.getNodeName())) {
                                        value = item.getTextContent();
                                    }
                                }

                                if (key == null || value == null) {
                                    continue;
                                }

                                if (additionalData == null) {
                                    additionalData = new HashMap<String, String>();
                                }

                                additionalData.put(key, value);
                            }
                        }

                        sb.append(getNodeAsString(node));
                    } else {
                        sb.append(o.toString());
                    }
                    sb.append('\n');
                }
            }
            errorContent = sb.toString();
        }

        switch (ex.getFaultInfo().getType()) {
        case CONSTRAINT:
            return new CmisConstraintException(msg, code, errorContent, additionalData);
        case CONTENT_ALREADY_EXISTS:
            return new CmisContentAlreadyExistsException(msg, code, errorContent, additionalData);
        case FILTER_NOT_VALID:
            return new CmisFilterNotValidException(msg, code, errorContent, additionalData);
        case INVALID_ARGUMENT:
            return new CmisInvalidArgumentException(msg, code, errorContent, additionalData);
        case NAME_CONSTRAINT_VIOLATION:
            return new CmisNameConstraintViolationException(msg, code, errorContent, additionalData);
        case NOT_SUPPORTED:
            return new CmisNotSupportedException(msg, code, errorContent, additionalData);
        case OBJECT_NOT_FOUND:
            return new CmisObjectNotFoundException(msg, code, errorContent, additionalData);
        case PERMISSION_DENIED:
            return new CmisPermissionDeniedException(msg, code, errorContent, additionalData);
        case RUNTIME:
            return new CmisRuntimeException(msg, code, errorContent, additionalData);
        case STORAGE:
            return new CmisStorageException(msg, code, errorContent, additionalData);
        case STREAM_NOT_SUPPORTED:
            return new CmisStreamNotSupportedException(msg, code, errorContent, additionalData);
        case UPDATE_CONFLICT:
            return new CmisUpdateConflictException(msg, code, errorContent, additionalData);
        case VERSIONING:
            return new CmisVersioningException(msg, code, errorContent, additionalData);
        default:
        }

        return new CmisRuntimeException("Unknown exception[" + ex.getFaultInfo().getType().value() + "]: " + msg);
    }

    private static String getNodeAsString(Node node) {
        try {
            Transformer transformer = XMLUtils.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            // transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            StringWriter sw = new StringWriter(512);
            transformer.transform(new DOMSource(node), new StreamResult(sw));
            return sw.toString();
        } catch (TransformerException e) {
            assert false;
        }

        return "";
    }

    /**
     * Return the CMIS version of the given repository.
     */
    protected CmisVersion getCmisVersion(String repositoryId) {
        if (CmisBindingsHelper.getForcedCmisVersion(session) != null) {
            return CmisBindingsHelper.getForcedCmisVersion(session);
        }

        RepositoryInfoCache cache = CmisBindingsHelper.getRepositoryInfoCache(session);
        RepositoryInfo info = cache.get(repositoryId);

        if (info == null) {
            info = CmisBindingsHelper.getSPI(session).getRepositoryService().getRepositoryInfo(repositoryId, null);
            if (info != null) {
                cache.put(info);
            }
        }

        // if the version is unknown try CMIS 1.0
        return info == null ? CmisVersion.CMIS_1_0 : info.getCmisVersion();
    }
}
