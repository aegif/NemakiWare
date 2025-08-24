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
package org.apache.chemistry.opencmis.client.bindings.spi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.TimeZone;

import javax.security.auth.Subject;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class LTPAWSSecurityAuthenticationProvider extends StandardAuthenticationProvider {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(LTPAWSSecurityAuthenticationProvider.class);

    @Override
    public Element getSOAPHeaders(Object portObject) {

        String securityToken = getSecurityToken();

        // Exit if no security token found
        if (securityToken == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("securityToken is null");
            }
            return null;
        }

        // Set time
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        long created = System.currentTimeMillis();
        long expires = created + 24 * 60 * 60 * 1000; // 24 hours

        // Create the SOAP WSSecurity header
        try {
            Document document = XMLUtils.newDomDocument();

            Element wsseSecurityElement = document.createElementNS(WSSE_NAMESPACE, "Security");

            Element wsuTimestampElement = document.createElementNS(WSU_NAMESPACE, "Timestamp");
            wsseSecurityElement.appendChild(wsuTimestampElement);

            Element tsCreatedElement = document.createElementNS(WSU_NAMESPACE, "Created");
            tsCreatedElement.appendChild(document.createTextNode(sdf.format(created)));
            wsuTimestampElement.appendChild(tsCreatedElement);

            Element tsExpiresElement = document.createElementNS(WSU_NAMESPACE, "Expires");
            tsExpiresElement.appendChild(document.createTextNode(sdf.format(expires)));
            wsuTimestampElement.appendChild(tsExpiresElement);

            // Add the BinarySecurityToken (contains the LTPAv2 token)
            Element wsseBinarySecurityTokenElement = document.createElementNS(WSSE_NAMESPACE, "BinarySecurityToken");
            wsseBinarySecurityTokenElement.setAttribute("xmlns:wsu", WSU_NAMESPACE);
            wsseBinarySecurityTokenElement.setAttribute("xmlns:wsst",
                    "http://www.ibm.com/websphere/appserver/tokentype");
            wsseBinarySecurityTokenElement.setAttribute("wsu:Id", "ltpa_20");
            wsseBinarySecurityTokenElement.setAttribute("ValueType", "wsst:LTPAv2");
            wsseBinarySecurityTokenElement.appendChild(document.createTextNode(securityToken));

            // Append BinarySecurityToken to Security section
            wsseSecurityElement.appendChild(wsseBinarySecurityTokenElement);

            return wsseSecurityElement;
        } catch (ParserConfigurationException e) {
            // shouldn't happen...
            throw new CmisRuntimeException("Could not build SOAP header: " + e.getMessage(), e);
        }
    }

    private String getSecurityToken() {
        try {
            Class<?> wsSubjectClass = ClassLoaderUtil.loadClass("com.ibm.websphere.security.auth.WSSubject");
            Class<?> wsCredentialClass = ClassLoaderUtil.loadClass("com.ibm.websphere.security.cred.WSCredential");

            // Get current security subject
            Method m = wsSubjectClass.getMethod("getRunAsSubject", new Class[0]);
            Subject securitySubject = (Subject) m.invoke(null, new Object[0]);
            if (securitySubject != null) {
                // Get all security credentials from the security subject
                Set<?> securityCredentials = securitySubject.getPublicCredentials(wsCredentialClass);

                // Get the first credential
                Object securityCredential = securityCredentials.iterator().next();
                String user = invokeSecurityCredentialMethod(wsCredentialClass, securityCredential, "getSecurityName");

                if (user.equalsIgnoreCase("UNAUTHENTICATED")) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("User = UNAUTHENTICATED");
                    }
                    return null;
                }

                byte[] token = invokeSecurityCredentialMethod(wsCredentialClass, securityCredential,
                        "getCredentialToken");
                if (token == null) {
                    return null;
                }

                return Base64.encodeBytes(token);
            }
        } catch (Exception e) {
            throw new CmisRuntimeException("Could not build SOAP header: " + e.getMessage(), e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeSecurityCredentialMethod(Class<?> credentialClass, Object securityCredential, String methodName)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method m = credentialClass.getMethod(methodName, new Class[0]);
        return (T) m.invoke(securityCredential, new Object[0]);
    }

    @Override
    protected boolean getSendBasicAuth() {
        return false;
    }

    @Override
    protected boolean getSendUsernameToken() {
        return false;
    }
}
