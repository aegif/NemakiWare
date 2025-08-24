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
package org.apache.chemistry.opencmis.server.impl.webservices;

import javax.xml.namespace.QName;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.headers.Header;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Extracts username and password from the UsernameToken header and prepares
 * them for the call context.
 * 
 * This class emulates the behavior of the OpenCMIS server framework 0.13.0 and
 * earlier.
 */
public class UsernameTokenInterceptor extends AbstractCallContextInterceptor {

    public UsernameTokenInterceptor() {
        super();
    }

    protected static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    protected static final QName WSSE_SECURITY = new QName(WSSE_NS, "Security");
    protected static final String WSSE_USERNAME_TOKEN = "UsernameToken";
    protected static final String WSSE_USERNAME = "Username";
    protected static final String WSSE_PASSWORD = "Password";
    protected static final String WSSE_PASSWORD_TYPE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    @Override
    public void handleMessage(SoapMessage message) {
        // don't extract a user, if there is already one
        if (getCurrentUser(message) == null) {
            Header securityHeader = message.getHeader(WSSE_SECURITY);
            if (securityHeader != null) {
                if (!(securityHeader.getObject() instanceof Node)) {
                    throw new CmisRuntimeException("Cannot read Security header.");
                }

                Node usernameTokenNode = getUsernameTokenNode((Node) securityHeader.getObject());
                if (usernameTokenNode == null) {
                    return;
                }

                String username = getUsername(usernameTokenNode);
                if (username == null) {
                    return;
                }

                String password = getPassword(usernameTokenNode);

                setUserAndPassword(message, username, password);
            }
        }
    }

    protected Node getUsernameTokenNode(Node securityNode) {
        return findElement(securityNode, WSSE_NS, WSSE_USERNAME_TOKEN);
    }

    protected String getUsername(Node usernameTokenNode) {
        Node node = findElement(usernameTokenNode, WSSE_NS, WSSE_USERNAME);

        if (node != null) {
            return node.getTextContent();
        }

        return null;
    }

    protected String getPassword(Node usernameTokenNode) {
        Node node = findElement(usernameTokenNode, WSSE_NS, WSSE_PASSWORD);

        if (node != null) {
            Node type = node.getAttributes().getNamedItem("Type");
            if (type == null || WSSE_PASSWORD_TYPE.equals(type.getTextContent())) {
                return node.getTextContent();
            }
        }

        return null;
    }

    protected Node findElement(Node parent, String namespace, String localname) {
        NodeList nl = parent.getChildNodes();

        for (int i = 0; i < nl.getLength(); i++) {
            Node node = nl.item(i);
            if (namespace.equals(node.getNamespaceURI()) && localname.equals(node.getLocalName())
                    && node.getNodeType() == Node.ELEMENT_NODE) {
                return node;
            }
        }

        return null;
    }
}
