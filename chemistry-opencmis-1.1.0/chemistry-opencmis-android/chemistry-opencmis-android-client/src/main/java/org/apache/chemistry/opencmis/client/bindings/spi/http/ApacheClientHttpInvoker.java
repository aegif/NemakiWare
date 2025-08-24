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
package org.apache.chemistry.opencmis.client.bindings.spi.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.http.Header;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.LayeredSocketFactory;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.cookie.Cookie;
import org.apache.http.cookie.CookieOrigin;
import org.apache.http.cookie.CookieSpec;
import org.apache.http.cookie.CookieSpecFactory;
import org.apache.http.cookie.MalformedCookieException;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.ProxySelectorRoutePlanner;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.impl.cookie.CookieSpecBase;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/**
 * A {@link HttpInvoker} that uses The Apache HTTP client.
 */
public class ApacheClientHttpInvoker extends AbstractApacheClientHttpInvoker {

    private static final String IGNORE_COOKIES = "ignoreCookies";

    @Override
    protected DefaultHttpClient createHttpClient(UrlBuilder url, BindingSession session) {
        // set params
        HttpParams params = createDefaultHttpParams(session);
        params.setParameter(ClientPNames.COOKIE_POLICY, IGNORE_COOKIES);

        // set max connection
        String maxConnStr = System.getProperty("http.maxConnections", "5");
        int maxConn = 5;
        try {
            maxConn = Integer.parseInt(maxConnStr);
        } catch (NumberFormatException nfe) {
            // ignore
        }
        params.setIntParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, maxConn * 4);
        params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, new ConnPerRouteBean(maxConn));

        // set up scheme registry
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        registry.register(new Scheme("https", getSSLSocketFactory(url, session), 443));

        // set up connection manager
        ThreadSafeClientConnManager connManager = new ThreadSafeClientConnManager(params, registry);

        // set up proxy
        ProxySelectorRoutePlanner routePlanner = new ProxySelectorRoutePlanner(registry, null);

        // set up client
        DefaultHttpClient httpclient = new DefaultHttpClient(connManager, params);
        httpclient.setRoutePlanner(routePlanner);
        httpclient.getCookieSpecs().register(IGNORE_COOKIES, new IgnoreSpecFactory());

        return httpclient;
    }

    /**
     * Builds a SSL Socket Factory for the Apache HTTP Client.
     */
    private LayeredSocketFactory getSSLSocketFactory(final UrlBuilder url, final BindingSession session) {
        // get authentication provider
        AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(session);

        // check SSL Socket Factory
        final SSLSocketFactory sf = authProvider.getSSLSocketFactory();
        if (sf == null) {
            // no custom factory -> return default factory
            return org.apache.http.conn.ssl.SSLSocketFactory.getSocketFactory();
        }

        // check hostame verifier and use default if not set
        final HostnameVerifier hv = (authProvider.getHostnameVerifier() == null ? new BrowserCompatHostnameVerifier()
                : authProvider.getHostnameVerifier());

        // build new socket factory
        return new LayeredSocketFactory() {

            @Override
            public boolean isSecure(Socket sock) {
                return true;
            }

            @Override
            public Socket createSocket() throws IOException {
                return sf.createSocket();
            }

            @Override
            public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
                SSLSocket sslSocket = (SSLSocket) sf.createSocket(socket, host, port, autoClose);
                verify(hv, host, sslSocket);

                return sslSocket;
            }

            @Override
            public Socket connectSocket(Socket sock, String host, int port, InetAddress localAddress, int localPort,
                    HttpParams params) throws IOException {
                SSLSocket sslSocket = (SSLSocket) (sock != null ? sock : createSocket());

                if (localAddress != null || localPort > 0) {
                    if (localPort < 0) {
                        localPort = 0;
                    }

                    InetSocketAddress isa = new InetSocketAddress(localAddress, localPort);
                    sslSocket.bind(isa);
                }

                InetSocketAddress remoteAddress = new InetSocketAddress(host, port);

                int connTimeout = HttpConnectionParams.getConnectionTimeout(params);
                int soTimeout = HttpConnectionParams.getSoTimeout(params);

                try {
                    sslSocket.setSoTimeout(soTimeout);
                    sslSocket.connect(remoteAddress, connTimeout);
                } catch (SocketTimeoutException ex) {
                    closeSocket(sock);
                    throw new ConnectTimeoutException("Connect to " + remoteAddress + " timed out!");
                }

                verify(hv, host, sslSocket);

                return sslSocket;
            }
        };
    }

    /**
     * A cookies spec factory that ignores cookies.
     */
    private static class IgnoreSpecFactory implements CookieSpecFactory {
        @Override
        public CookieSpec newInstance(final HttpParams params) {
            return new CookieSpecBase() {
                @Override
                public int getVersion() {
                    return 0;
                }

                @Override
                public List<Cookie> parse(Header header, CookieOrigin origin) throws MalformedCookieException {
                    return Collections.emptyList();
                }

                @Override
                public List<Header> formatCookies(List<Cookie> cookies) {
                    return Collections.emptyList();
                }

                @Override
                public Header getVersionHeader() {
                    return null;
                }
            };
        }
    }
}
