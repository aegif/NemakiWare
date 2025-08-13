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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.soap.MTOMFeature;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * WebSphere JAX-WS implementation
 */
public class WebSpherePortProvider extends AbstractPortProvider {
    private static final Logger LOG = LoggerFactory.getLogger(WebSpherePortProvider.class);

    /**
     * Creates a port object.
     */
    @Override
    protected BindingProvider createPortObject(CmisServiceHolder serviceHolder) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Creating Web Service port object of " + serviceHolder.getServiceName() + "...");
        }

        try {
            // create port object
            BindingProvider portObject = createPortObjectFromServiceHolder(serviceHolder, new MTOMFeature());

            // add SOAP and HTTP authentication headers
            AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(getSession());
            Map<String, List<String>> httpHeaders = null;
            if (authProvider != null) {
                // SOAP header
                Element soapHeader = authProvider.getSOAPHeaders(portObject);
                if (soapHeader != null) {
                    Transformer transformer = XMLUtils.newTransformer();
                    StringWriter headerXml = new StringWriter();
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                    transformer.transform(new DOMSource(soapHeader), new StreamResult(headerXml));

                    Map<QName, List<String>> header = new HashMap<QName, List<String>>();
                    header.put(new QName(soapHeader.getNamespaceURI(), soapHeader.getLocalName()),
                            Collections.singletonList(headerXml.toString()));
                    portObject.getRequestContext().put("jaxws.binding.soap.headers.outbound", header);
                }

                // HTTP header
                String url = (serviceHolder.getEndpointUrl() != null ? serviceHolder.getEndpointUrl().toString()
                        : serviceHolder.getServiceObject().getWSDLDocumentLocation().toString());
                httpHeaders = authProvider.getHTTPHeaders(url);

                // TODO: set SSL Factory

                // TODO: set Hostname Verifier
            }

            // set HTTP headers
            setHTTPHeaders(portObject, httpHeaders);

            // set endpoint URL
            setEndpointUrl(portObject, serviceHolder.getEndpointUrl());

            // timeouts
            int connectTimeout = getSession().get(SessionParameter.CONNECT_TIMEOUT, -1);
            if (connectTimeout >= 0) {
                portObject.getRequestContext().put("connection_timeout", connectTimeout);
            }

            int readTimeout = getSession().get(SessionParameter.READ_TIMEOUT, -1);
            if (readTimeout >= 0) {
                portObject.getRequestContext().put("request_timeout", readTimeout);
            }

            return portObject;
        } catch (CmisBaseException ce) {
            throw ce;
        } catch (Exception e) {
            throw new CmisConnectionException("Cannot initalize Web Services port object: " + e.getMessage(), e);
        }
    }
}
