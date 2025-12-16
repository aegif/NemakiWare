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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceFeature;
import jakarta.xml.ws.handler.HandlerResolver;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.http.HTTPException;

import org.apache.chemistry.opencmis.client.bindings.impl.ClientVersion;
import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.HttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisProxyAuthenticationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisTooManyRequestsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ACLService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ACLServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.DiscoveryService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.DiscoveryServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.MultiFilingService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.MultiFilingServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.NavigationService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.NavigationServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ObjectService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ObjectServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.PolicyService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.PolicyServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.RelationshipService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.RelationshipServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.RepositoryService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.RepositoryServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.VersioningService;
import org.apache.chemistry.opencmis.commons.impl.jaxb.VersioningServicePort;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public abstract class AbstractPortProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractPortProvider.class);

    private static final int PORT_CACHE_SIZE = 5;

    protected static final int CHUNK_SIZE = (64 * 1024) - 1;

    protected enum CmisWebSerivcesService {
        REPOSITORY_SERVICE("RepositoryService", false, RepositoryService.class, RepositoryServicePort.class,
                SessionParameter.WEBSERVICES_REPOSITORY_SERVICE,
                SessionParameter.WEBSERVICES_REPOSITORY_SERVICE_ENDPOINT),

        NAVIGATION_SERVICE("NavigationService", false, NavigationService.class, NavigationServicePort.class,
                SessionParameter.WEBSERVICES_NAVIGATION_SERVICE,
                SessionParameter.WEBSERVICES_NAVIGATION_SERVICE_ENDPOINT),

        OBJECT_SERVICE("ObjectService", true, ObjectService.class, ObjectServicePort.class,
                SessionParameter.WEBSERVICES_OBJECT_SERVICE, SessionParameter.WEBSERVICES_OBJECT_SERVICE_ENDPOINT),

        VERSIONING_SERVICE("VersioningService", true, VersioningService.class, VersioningServicePort.class,
                SessionParameter.WEBSERVICES_VERSIONING_SERVICE,
                SessionParameter.WEBSERVICES_VERSIONING_SERVICE_ENDPOINT),

        DISCOVERY_SERVICE("DiscoveryService", false, DiscoveryService.class, DiscoveryServicePort.class,
                SessionParameter.WEBSERVICES_DISCOVERY_SERVICE, SessionParameter.WEBSERVICES_DISCOVERY_SERVICE_ENDPOINT),

        MULTIFILING_SERVICE("MultiFilingService", false, MultiFilingService.class, MultiFilingServicePort.class,
                SessionParameter.WEBSERVICES_MULTIFILING_SERVICE,
                SessionParameter.WEBSERVICES_MULTIFILING_SERVICE_ENDPOINT),

        RELATIONSHIP_SERVICE("RelationshipService", false, RelationshipService.class, RelationshipServicePort.class,
                SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE,
                SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE_ENDPOINT),

        POLICY_SERVICE("PolicyService", false, PolicyService.class, PolicyServicePort.class,
                SessionParameter.WEBSERVICES_POLICY_SERVICE, SessionParameter.WEBSERVICES_POLICY_SERVICE_ENDPOINT),

        ACL_SERVICE("ACLService", false, ACLService.class, ACLServicePort.class,
                SessionParameter.WEBSERVICES_ACL_SERVICE, SessionParameter.WEBSERVICES_ACL_SERVICE_ENDPOINT);

        private final String name;
        private final QName qname;
        private final boolean handlesContent;
        private final Class<? extends Service> serviceClass;
        private final Class<?> portClass;
        private final String wsdlKey;
        private final String endpointKey;

        CmisWebSerivcesService(String localname, boolean handlesContent, Class<? extends Service> serviceClass,
                Class<?> port11Class, String wsdlKey, String endpointKey) {
            this.name = localname;
            this.qname = new QName("http://docs.oasis-open.org/ns/cmis/ws/200908/", localname);
            this.handlesContent = handlesContent;
            this.serviceClass = serviceClass;
            this.portClass = port11Class;
            this.wsdlKey = wsdlKey;
            this.endpointKey = endpointKey;
        }

        public String getServiceName() {
            return name;
        }

        public QName getQName() {
            return qname;
        }

        public boolean handlesContent() {
            return handlesContent;
        }

        public Class<? extends Service> getServiceClass() {
            return serviceClass;
        }

        public Class<?> getPortClass() {
            return portClass;
        }

        public String getWsdlKey() {
            return wsdlKey;
        }

        public String getEndpointKey() {
            return endpointKey;
        }
    }

    class CmisServiceHolder {
        private final CmisWebSerivcesService service;
        private SoftReference<Service> serviceObject;
        private final URL endpointUrl;

        public CmisServiceHolder(final CmisWebSerivcesService service, final URL endpointUrl)
                throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
            this.service = service;
            this.endpointUrl = endpointUrl;
            this.serviceObject = new SoftReference<Service>(createServiceObject());
        }

        private Service createServiceObject() throws InstantiationException, IllegalAccessException,
                InvocationTargetException, NoSuchMethodException {
            final Constructor<? extends Service> serviceConstructor = service.getServiceClass().getConstructor(
                    new Class<?>[] { URL.class, QName.class });

            URL wsdlUrl = service.getPortClass().getResource("/wsdl/cmis11/CMISWS-Service.wsdl");
            if (LOG.isDebugEnabled()) {
                LOG.debug("Session {}: WSDL URL: {}", getSession().getSessionId(), wsdlUrl.toExternalForm());
            }

            Service newService = serviceConstructor.newInstance(new Object[] { wsdlUrl, service.getQName() });

            AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(getSession());
            if (authProvider != null) {
                HandlerResolver handlerResolver = authProvider.getHandlerResolver();
                if (handlerResolver != null) {
                    newService.setHandlerResolver(handlerResolver);
                }
            }

            return newService;
        }

        public CmisWebSerivcesService getService() {
            return service;
        }

        public Service getServiceObject() throws InstantiationException, IllegalAccessException,
                InvocationTargetException, NoSuchMethodException {
            Service result = serviceObject.get();
            if (result == null) {
                result = createServiceObject();
                serviceObject = new SoftReference<Service>(result);
            }

            return result;
        }

        public URL getEndpointUrl() {
            return endpointUrl;
        }

        public String getServiceName() {
            return service.getServiceName();
        }
    }

    private BindingSession session;
    private boolean useCompression;
    private boolean useClientCompression;
    private String acceptLanguage;

    private final ReentrantLock portObjectLock = new ReentrantLock();
    private final EnumMap<CmisWebSerivcesService, ArrayDeque<SoftReference<BindingProvider>>> portObjectCache = new EnumMap<CmisWebSerivcesService, ArrayDeque<SoftReference<BindingProvider>>>(
            CmisWebSerivcesService.class);

    public BindingSession getSession() {
        return session;
    }

    public void setSession(BindingSession session) {
        this.session = session;

        final Object compression = session.get(SessionParameter.COMPRESSION);
        useCompression = (compression != null) && Boolean.parseBoolean(compression.toString());

        final Object clientCompression = session.get(SessionParameter.CLIENT_COMPRESSION);
        useClientCompression = (clientCompression != null) && Boolean.parseBoolean(clientCompression.toString());

        if (session.get(CmisBindingsHelper.ACCEPT_LANGUAGE) instanceof String) {
            acceptLanguage = session.get(CmisBindingsHelper.ACCEPT_LANGUAGE).toString();
        }
    }

    public boolean useCompression() {
        return useCompression;
    }

    public boolean useClientCompression() {
        return useClientCompression;
    }

    public String getAcceptLanguage() {
        return acceptLanguage;
    }

    /**
     * Return the Repository Service port object.
     */
    public RepositoryServicePort getRepositoryServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.REPOSITORY_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (RepositoryServicePort) portObject;
    }

    /**
     * Return the Navigation Service port object.
     */
    public NavigationServicePort getNavigationServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.NAVIGATION_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (NavigationServicePort) portObject;
    }

    /**
     * Return the Object Service port object.
     */
    public ObjectServicePort getObjectServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.OBJECT_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (ObjectServicePort) portObject;
    }

    /**
     * Return the Versioning Service port object.
     */
    public VersioningServicePort getVersioningServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.VERSIONING_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (VersioningServicePort) portObject;
    }

    /**
     * Return the Discovery Service port object.
     */
    public DiscoveryServicePort getDiscoveryServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.DISCOVERY_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (DiscoveryServicePort) portObject;
    }

    /**
     * Return the MultiFiling Service port object.
     */
    public MultiFilingServicePort getMultiFilingServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.MULTIFILING_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (MultiFilingServicePort) portObject;
    }

    /**
     * Return the Relationship Service port object.
     */
    public RelationshipServicePort getRelationshipServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.RELATIONSHIP_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (RelationshipServicePort) portObject;
    }

    /**
     * Return the Policy Service port object.
     */
    public PolicyServicePort getPolicyServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.POLICY_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (PolicyServicePort) portObject;
    }

    /**
     * Return the ACL Service port object.
     */
    public ACLServicePort getACLServicePort(CmisVersion cmisVersion, String soapAction) {
        BindingProvider portObject = getPortObject(CmisWebSerivcesService.ACL_SERVICE);
        setSoapAction(portObject, soapAction, cmisVersion);

        return (ACLServicePort) portObject;
    }

    public void endCall(Object portObject) {
        AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(session);
        if (authProvider != null && portObject instanceof BindingProvider) {
            BindingProvider bp = (BindingProvider) portObject;
            String url = (String) bp.getRequestContext().get(BindingProvider.ENDPOINT_ADDRESS_PROPERTY);
            if (bp.getResponseContext() != null) {
                @SuppressWarnings("unchecked")
                Map<String, List<String>> headers = (Map<String, List<String>>) bp.getResponseContext().get(
                        MessageContext.HTTP_RESPONSE_HEADERS);
                Integer statusCode = (Integer) bp.getResponseContext().get(MessageContext.HTTP_RESPONSE_CODE);
                authProvider.putResponseHeaders(url, statusCode == null ? -1 : statusCode, headers);
            }

            CmisWebSerivcesService service = null;

            if (portObject instanceof RepositoryServicePort) {
                service = CmisWebSerivcesService.REPOSITORY_SERVICE;
            } else if (portObject instanceof NavigationServicePort) {
                service = CmisWebSerivcesService.NAVIGATION_SERVICE;
            } else if (portObject instanceof ObjectServicePort) {
                service = CmisWebSerivcesService.OBJECT_SERVICE;
            } else if (portObject instanceof VersioningServicePort) {
                service = CmisWebSerivcesService.VERSIONING_SERVICE;
            } else if (portObject instanceof DiscoveryServicePort) {
                service = CmisWebSerivcesService.DISCOVERY_SERVICE;
            } else if (portObject instanceof MultiFilingServicePort) {
                service = CmisWebSerivcesService.MULTIFILING_SERVICE;
            } else if (portObject instanceof RelationshipServicePort) {
                service = CmisWebSerivcesService.RELATIONSHIP_SERVICE;
            } else if (portObject instanceof PolicyServicePort) {
                service = CmisWebSerivcesService.POLICY_SERVICE;
            } else if (portObject instanceof ACLServicePort) {
                service = CmisWebSerivcesService.ACL_SERVICE;
            }

            if (service == null) {
                return;
            }

            portObjectLock.lock();
            try {
                ArrayDeque<SoftReference<BindingProvider>> queue = portObjectCache.get(service);
                if (queue == null) {
                    throw new CmisRuntimeException("This is a bug!");
                }

                if (queue.size() < PORT_CACHE_SIZE) {
                    queue.push(new SoftReference<BindingProvider>(bp));
                } else {
                    Iterator<SoftReference<BindingProvider>> iter = queue.iterator();
                    while (iter.hasNext()) {
                        SoftReference<BindingProvider> ref = iter.next();
                        if (ref.get() == null) {
                            iter.remove();
                            queue.push(new SoftReference<BindingProvider>(bp));
                            break;
                        }
                    }
                }
            } finally {
                portObjectLock.unlock();
            }
        }
    }

    // ---- internal ----

    @SuppressWarnings("unchecked")
    protected BindingProvider getPortObject(final CmisWebSerivcesService service) {
        Map<CmisWebSerivcesService, CmisServiceHolder> serviceMap = (Map<CmisWebSerivcesService, CmisServiceHolder>) session
                .get(SpiSessionParameter.SERVICES);

        // does the service map exist?
        if (serviceMap == null) {
            session.writeLock();
            try {
                // try again
                serviceMap = (Map<CmisWebSerivcesService, CmisServiceHolder>) session.get(SpiSessionParameter.SERVICES);
                if (serviceMap == null) {
                    serviceMap = new EnumMap<CmisWebSerivcesService, CmisServiceHolder>(CmisWebSerivcesService.class);
                    session.put(SpiSessionParameter.SERVICES, serviceMap, true);
                }

                if (serviceMap.containsKey(service)) {
                    return createPortObject(serviceMap.get(service));
                }

                // create service object
                CmisServiceHolder serviceholder = initServiceObject(service);
                serviceMap.put(service, serviceholder);

                // create port object
                return createPortObject(serviceholder);
            } finally {
                session.writeUnlock();
            }
        }

        // is the service in the service map?
        if (!serviceMap.containsKey(service)) {
            session.writeLock();
            try {
                // try again
                if (serviceMap.containsKey(service)) {
                    return createPortObject(serviceMap.get(service));
                }

                // create object
                CmisServiceHolder serviceholder = initServiceObject(service);
                serviceMap.put(service, serviceholder);

                return createPortObject(serviceholder);
            } finally {
                session.writeUnlock();
            }
        }

        return createPortObject(serviceMap.get(service));
    }

    /**
     * Creates a service object.
     */
    protected CmisServiceHolder initServiceObject(final CmisWebSerivcesService service) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session {}: Initializing Web Service {} ...", getSession().getSessionId(),
                    service.getServiceName());
        }

        try {
            // get URLs
            URL endpointUrl = null;

            String wsdlUrlStr = (String) session.get(service.getWsdlKey());
            if (wsdlUrlStr != null) {
                endpointUrl = getEndpointUrlFromWsdl(wsdlUrlStr, service);
            } else {
                String endpointUrlStr = (String) session.get(service.getEndpointKey());
                if (endpointUrlStr != null) {
                    endpointUrl = new URL(endpointUrlStr);
                }
            }

            if (endpointUrl == null) {
                throw new CmisRuntimeException("Neither a WSDL URL nor an endpoint URL is specified for the service "
                        + service.getServiceName() + "!");
            }

            // build the requested service object
            return new CmisServiceHolder(service, endpointUrl);
        } catch (CmisBaseException ce) {
            throw ce;
        } catch (HTTPException he) {
            String message = "Cannot connect to Web Services [" + service.getServiceName() + "]: " + he.getMessage();
            if (he.getStatusCode() == 401) {
                throw new CmisUnauthorizedException(message, he);
            } else if (he.getStatusCode() == 404) {
                throw new CmisObjectNotFoundException(message, he);
            } else if (he.getStatusCode() == 407) {
                throw new CmisProxyAuthenticationException(message, he);
            } else if (he.getStatusCode() == 429) {
                throw new CmisTooManyRequestsException(message, he);
            } else if (he.getStatusCode() == 301 || he.getStatusCode() == 302 || he.getStatusCode() == 303
                    || he.getStatusCode() == 307) {
                throw new CmisConnectionException("Redirects are not supported (HTTP status code " + he.getStatusCode()
                        + "): " + message, he);
            } else {
                throw new CmisConnectionException(message, he);
            }
        } catch (InvocationTargetException ite) {
            String message = "Cannot initalize Web Services service object [" + service.getServiceName() + "]: "
                    + ite.getCause().getMessage();
            throw new CmisConnectionException(message, ite);
        } catch (Exception e) {
            String message = "Cannot initalize Web Services service object [" + service.getServiceName() + "]: "
                    + e.getMessage();
            throw new CmisConnectionException(message, e);
        }
    }

    /**
     * Reads the URL and extracts the endpoint URL of the given service.
     */
    private URL getEndpointUrlFromWsdl(final String wsdlUrl, final CmisWebSerivcesService service) {
        InputStream wsdlStream;
        URL url;

        // check the WSDL URL
        try {
            url = new URL(wsdlUrl);
        } catch (MalformedURLException e) {
            throw new CmisConnectionException("Invalid WSDL URL: " + wsdlUrl, e);
        }

        // check protocol
        if (url.getProtocol().equalsIgnoreCase("http") || url.getProtocol().equalsIgnoreCase("https")) {
            // HTTP URL -> use HttpInvoker to enable authentication
            HttpInvoker hi = CmisBindingsHelper.getHttpInvoker(session);
            Response wsdlResponse = hi.invokeGET(new UrlBuilder(wsdlUrl), session);

            if (wsdlResponse.getResponseCode() != 200) {
                throw new CmisConnectionException("Cannot access WSDL: " + wsdlUrl, BigInteger.ZERO,
                        wsdlResponse.getErrorContent());
            } else {
                wsdlStream = wsdlResponse.getStream();
            }
        } else {
            // non-HTTP URL -> just open the stream
            try {
                wsdlStream = url.openStream();
            } catch (IOException e) {
                throw new CmisConnectionException("Cannot access WSDL: " + wsdlUrl, e);
            }
        }

        // parse the WSDL
        try {
            final Document doc = XMLUtils.parseDomDocument(new BufferedInputStream(wsdlStream, 64 * 1024));

            NodeList serivceList = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "service");
            for (int i = 0; i < serivceList.getLength(); i++) {
                Element serviceNode = (Element) serivceList.item(i);

                String name = serviceNode.getAttribute("name");
                if (name == null) {
                    continue;
                }

                if (!service.getQName().getLocalPart().equals(name)) {
                    continue;
                }

                NodeList portList = serviceNode.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "port");
                if (portList.getLength() < 1) {
                    throw new CmisRuntimeException("This service has no ports: " + service.getServiceName());
                }

                Element port = (Element) portList.item(0);

                NodeList addressList = port.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/soap/", "address");
                if (addressList.getLength() < 1) {
                    // see CMIS-908
                    addressList = port.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/soap12/", "address");
                }
                if (addressList.getLength() < 1) {
                    throw new CmisRuntimeException("This service has no endpoint address: " + service.getServiceName());
                }

                Element address = (Element) addressList.item(0);

                String location = address.getAttribute("location");
                if (location == null) {
                    throw new CmisRuntimeException("This service has no endpoint address: " + service.getServiceName());
                }

                try {
                    return new URL(location);
                } catch (MalformedURLException e) {
                    throw new CmisRuntimeException("This service provides an invalid endpoint address: "
                            + service.getServiceName(), e);
                }
            }

            throw new CmisRuntimeException("This service does not provide an endpoint address: "
                    + service.getServiceName());
        } catch (ParserConfigurationException pe) {
            throw new CmisRuntimeException("Cannot parse this WSDL: " + wsdlUrl, pe);
        } catch (SAXException se) {
            throw new CmisRuntimeException("Cannot parse this WSDL: " + wsdlUrl, se);
        } catch (IOException ioe) {
            throw new CmisRuntimeException("Cannot read this WSDL: " + wsdlUrl, ioe);
        } finally {
            IOUtils.closeQuietly(wsdlStream);
        }
    }

    /**
     * Sets the default HTTP headers on a {@link BindingProvider} object.
     */
    protected void setHTTPHeaders(BindingProvider portObject, Map<String, List<String>> httpHeaders) {
        if (httpHeaders == null) {
            httpHeaders = new HashMap<String, List<String>>();
        }

        // CMIS client header
        httpHeaders.put("X-CMIS-Client", Collections.singletonList((String) session.get(SessionParameter.USER_AGENT,
                ClientVersion.OPENCMIS_USER_AGENT)));

        // compression
        if (useCompression) {
            httpHeaders.put("Accept-Encoding", Collections.singletonList("gzip"));
        }

        // client compression
        if (useClientCompression) {
            httpHeaders.put("Content-Encoding", Collections.singletonList("gzip"));
        }

        // locale
        if (acceptLanguage != null) {
            httpHeaders.put("Accept-Language", Collections.singletonList(acceptLanguage));
        }

        portObject.getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS, httpHeaders);
    }

    /**
     * Sets the endpoint URL if the URL is not <code>null</code>.
     */
    protected void setEndpointUrl(BindingProvider portObject, URL endpointUrl) {
        if (endpointUrl == null) {
            return;
        }

        portObject.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpointUrl.toString());
    }

    /**
     * Sets the SOAP Action header.
     */
    protected void setSoapAction(BindingProvider portObject, String soapAction, CmisVersion cmisVersion) {
        portObject.getRequestContext().put(BindingProvider.SOAPACTION_USE_PROPERTY, Boolean.TRUE);

        if (cmisVersion == CmisVersion.CMIS_1_0) {
            portObject.getRequestContext().put(BindingProvider.SOAPACTION_URI_PROPERTY, "");
        } else {
            portObject.getRequestContext().put(BindingProvider.SOAPACTION_URI_PROPERTY, soapAction);
        }
    }

    /**
     * Creates a simple port object from a CmisServiceHolder object.
     */
    protected BindingProvider createPortObjectFromServiceHolder(final CmisServiceHolder serviceHolder,
            WebServiceFeature... features) throws Exception {
        portObjectLock.lock();
        try {
            ArrayDeque<SoftReference<BindingProvider>> queue = portObjectCache.get(serviceHolder.getService());
            if (queue == null) {
                queue = new ArrayDeque<SoftReference<BindingProvider>>();
                portObjectCache.put(serviceHolder.getService(), queue);
            }

            while (!queue.isEmpty()) {
                BindingProvider bp = queue.pop().get();
                if (bp != null) {
                    return bp;
                }
            }
        } finally {
            portObjectLock.unlock();
        }

        return (BindingProvider) serviceHolder.getServiceObject().getPort(serviceHolder.getService().getPortClass(),
                features);
    }

    /**
     * Creates a port object.
     */
    protected abstract BindingProvider createPortObject(CmisServiceHolder serviceHolder);
}
