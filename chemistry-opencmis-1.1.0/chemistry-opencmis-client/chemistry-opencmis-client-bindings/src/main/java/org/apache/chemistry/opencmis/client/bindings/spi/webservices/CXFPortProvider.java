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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import javax.xml.namespace.QName;
import jakarta.xml.ws.Binding;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.soap.MTOMFeature;
import jakarta.xml.ws.soap.SOAPBinding;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.cxf.Bus;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.headers.Header;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * Apache CXF JAX-WS implementation.
 */
public class CXFPortProvider extends AbstractPortProvider {
    private static final Logger LOG = LoggerFactory.getLogger(CXFPortProvider.class);

    private int contentThreshold;
    private int responseThreshold;

    @Override
    public void setSession(BindingSession session) {
        super.setSession(session);

        contentThreshold = session.get(SessionParameter.WEBSERVICES_MEMORY_THRESHOLD, 4 * 1024 * 1024);
        responseThreshold = session.get(SessionParameter.WEBSERVICES_REPSONSE_MEMORY_THRESHOLD, -1);

        if (responseThreshold > contentThreshold) {
            contentThreshold = responseThreshold;
        }
    }

    /**
     * Creates a port object.
     */
    @Override
    protected BindingProvider createPortObject(CmisServiceHolder serviceHolder) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session {}: Creating Web Service port object of {} ...", getSession().getSessionId(),
                    serviceHolder.getServiceName());
        }

        try {
            // create port object
            BindingProvider portObject = createPortObjectFromServiceHolder(serviceHolder, new MTOMFeature());

            Binding binding = portObject.getBinding();
            ((SOAPBinding) binding).setMTOMEnabled(true);

            Client client = ClientProxy.getClient(portObject);
            HTTPConduit http = (HTTPConduit) client.getConduit();
            HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
            httpClientPolicy.setAllowChunking(true);

            // temp files and large stream handlding
            Bus bus = client.getBus();

            Object tempDir = getSession().get(SessionParameter.WEBSERVICES_TEMP_DIRECTORY);
            if (tempDir != null) {
                bus.setProperty("bus.io.CachedOutputStream.OutputDirectory", tempDir.toString());
            }

            if (serviceHolder.getService().handlesContent()) {
                bus.setProperty("bus.io.CachedOutputStream.Threshold", String.valueOf(contentThreshold));
            } else if (responseThreshold > -1) {
                bus.setProperty("bus.io.CachedOutputStream.Threshold", String.valueOf(responseThreshold));
            }

            bus.setProperty("bus.io.CachedOutputStream.MaxSize", "-1");

            if (getSession().get(SessionParameter.WEBSERVICES_TEMP_ENCRYPT, false)) {
                bus.setProperty("bus.io.CachedOutputStream.CipherTransformation", "AES/CTR/PKCS5Padding");
            }

            // add SOAP and HTTP authentication headers
            AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(getSession());
            Map<String, List<String>> httpHeaders = null;
            if (authProvider != null) {
                // SOAP header
                Element soapHeader = authProvider.getSOAPHeaders(portObject);
                if (soapHeader != null) {
                    List<Header> soapHeaderList = new ArrayList<Header>(2);
                    soapHeaderList.add(new Header(new QName(soapHeader.getNamespaceURI(), soapHeader.getLocalName()), soapHeader));
                    portObject.getRequestContext().put(Header.HEADER_LIST, soapHeaderList);
                }

                // HTTP header
                String url = (serviceHolder.getEndpointUrl() != null ? serviceHolder.getEndpointUrl().toString()
                        : serviceHolder.getServiceObject().getWSDLDocumentLocation().toString());
                httpHeaders = authProvider.getHTTPHeaders(url);

                // SSL factory and hostname verifier
                SSLSocketFactory sslSocketFactory = authProvider.getSSLSocketFactory();
                HostnameVerifier hostnameVerifier = authProvider.getHostnameVerifier();
                if (sslSocketFactory != null || hostnameVerifier != null) {
                    TLSClientParameters tlsCP = new TLSClientParameters();
                    if (sslSocketFactory != null) {
                        tlsCP.setSSLSocketFactory(sslSocketFactory);
                    }
                    if (hostnameVerifier != null) {
                        tlsCP.setHostnameVerifier(hostnameVerifier);
                    }
                    http.setTlsClientParameters(tlsCP);
                }
            }

            // set HTTP headers
            setHTTPHeaders(portObject, httpHeaders);

            // set endpoint URL
            setEndpointUrl(portObject, serviceHolder.getEndpointUrl());

            // timeouts
            int connectTimeout = getSession().get(SessionParameter.CONNECT_TIMEOUT, -1);
            if (connectTimeout >= 0) {
                httpClientPolicy.setConnectionTimeout(connectTimeout);
            }

            int readTimeout = getSession().get(SessionParameter.READ_TIMEOUT, -1);
            if (readTimeout >= 0) {
                httpClientPolicy.setReceiveTimeout(readTimeout);
            }

            http.setClient(httpClientPolicy);

            return portObject;
        } catch (CmisBaseException ce) {
            throw ce;
        } catch (Exception e) {
            throw new CmisConnectionException("Cannot initalize Web Services port object: " + e.getMessage(), e);
        }
    }
}
