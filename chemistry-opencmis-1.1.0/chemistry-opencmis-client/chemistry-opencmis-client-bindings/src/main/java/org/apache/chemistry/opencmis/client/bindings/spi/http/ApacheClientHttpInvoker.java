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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpInetSocketAddress;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeLayeredSocketFactory;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.impl.conn.ProxySelectorRoutePlanner;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/**
 * A {@link HttpInvoker} that uses The Apache HTTP client.
 */
public class ApacheClientHttpInvoker extends AbstractApacheClientHttpInvoker {

    @Override
    protected DefaultHttpClient createHttpClient(UrlBuilder url, BindingSession session) {
        // set params
        HttpParams params = createDefaultHttpParams(session);
        params.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);

        // set up scheme registry and connection manager
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", 80, PlainSocketFactory.getSocketFactory()));
        registry.register(new Scheme("https", 443, getSSLSocketFactory(url, session)));

        // set up connection manager
        PoolingClientConnectionManager connManager = new PoolingClientConnectionManager(registry);

        // set max connection a
        String keepAliveStr = System.getProperty("http.keepAlive", "true");
        if ("true".equalsIgnoreCase(keepAliveStr)) {
            String maxConnStr = System.getProperty("http.maxConnections", "5");
            int maxConn = 5;
            try {
                maxConn = Integer.parseInt(maxConnStr);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            connManager.setDefaultMaxPerRoute(maxConn);
            connManager.setMaxTotal(4 * maxConn);
        }

        // set up proxy
        ProxySelectorRoutePlanner routePlanner = new ProxySelectorRoutePlanner(registry, null);

        // set up client
        DefaultHttpClient httpclient = new DefaultHttpClient(connManager, params);
        httpclient.setRoutePlanner(routePlanner);

        return httpclient;
    }

    /**
     * Builds a SSL Socket Factory for the Apache HTTP Client.
     */
    private SchemeLayeredSocketFactory getSSLSocketFactory(final UrlBuilder url, final BindingSession session) {
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

        if (hv instanceof X509HostnameVerifier) {
            return new org.apache.http.conn.ssl.SSLSocketFactory(sf, (X509HostnameVerifier) hv);
        }

        // build new socket factory
        return new SchemeLayeredSocketFactory() {

            @Override
            public boolean isSecure(Socket sock) {
                return true;
            }

            @Override
            public Socket createSocket(HttpParams params) throws IOException {
                return sf.createSocket();
            }

            @Override
            public Socket connectSocket(final Socket socket, final InetSocketAddress remoteAddress,
                    final InetSocketAddress localAddress, final HttpParams params) throws IOException {

                Socket sock = socket != null ? socket : createSocket(params);
                if (localAddress != null) {
                    sock.setReuseAddress(HttpConnectionParams.getSoReuseaddr(params));
                    sock.bind(localAddress);
                }

                int connTimeout = HttpConnectionParams.getConnectionTimeout(params);
                int soTimeout = HttpConnectionParams.getSoTimeout(params);

                try {
                    sock.setSoTimeout(soTimeout);
                    sock.connect(remoteAddress, connTimeout);
                } catch (SocketTimeoutException ex) {
                    closeSocket(sock);
                    throw new ConnectTimeoutException("Connect to " + remoteAddress + " timed out!");
                }

                String host;
                if (remoteAddress instanceof HttpInetSocketAddress) {
                    host = ((HttpInetSocketAddress) remoteAddress).getHttpHost().getHostName();
                } else {
                    host = remoteAddress.getHostName();
                }

                SSLSocket sslSocket;
                if (sock instanceof SSLSocket) {
                    sslSocket = (SSLSocket) sock;
                } else {
                    int port = remoteAddress.getPort();
                    sslSocket = (SSLSocket) sf.createSocket(sock, host, port, true);
                }
                verify(hv, host, sslSocket);

                return sslSocket;
            }

            @Override
            public Socket createLayeredSocket(final Socket socket, final String host, final int port,
                    final HttpParams params) throws IOException {
                SSLSocket sslSocket = (SSLSocket) sf.createSocket(socket, host, port, true);
                verify(hv, host, sslSocket);

                return sslSocket;
            }
        };
    }
}
