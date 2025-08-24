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

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import org.apache.chemistry.opencmis.client.bindings.impl.ClientVersion;
import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultHttpInvoker implements HttpInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpInvoker.class);

    private static final int BUFFER_SIZE = 2 * 1024 * 1024;

    public DefaultHttpInvoker() {
    }

    @Override
    public Response invokeGET(UrlBuilder url, BindingSession session) {
        return invoke(url, "GET", null, null, null, session, null, null);
    }

    @Override
    public Response invokeGET(UrlBuilder url, BindingSession session, BigInteger offset, BigInteger length) {
        return invoke(url, "GET", null, null, null, session, offset, length);
    }

    @Override
    public Response invokePOST(UrlBuilder url, String contentType, Output writer, BindingSession session) {
        return invoke(url, "POST", contentType, null, writer, session, null, null);
    }

    @Override
    public Response invokePUT(UrlBuilder url, String contentType, Map<String, String> headers, Output writer,
            BindingSession session) {
        return invoke(url, "PUT", contentType, headers, writer, session, null, null);
    }

    @Override
    public Response invokeDELETE(UrlBuilder url, BindingSession session) {
        return invoke(url, "DELETE", null, null, null, session, null, null);
    }

    private Response invoke(UrlBuilder url, String method, String contentType, Map<String, String> headers,
            Output writer, BindingSession session, BigInteger offset, BigInteger length) {
        int respCode = -1;

        try {
            // log before connect
            if (LOG.isDebugEnabled()) {
                LOG.debug("Session {}: {} {}", session.getSessionId(), method, url);
            }

            // connect
            HttpURLConnection conn = (HttpURLConnection) (new URL(url.toString())).openConnection();
            conn.setRequestMethod(method);
            conn.setDoInput(true);
            conn.setDoOutput(writer != null);
            conn.setAllowUserInteraction(false);
            conn.setUseCaches(false);

            conn.setRequestProperty("User-Agent",
                    (String) session.get(SessionParameter.USER_AGENT, ClientVersion.OPENCMIS_USER_AGENT));

            // timeouts
            int connectTimeout = session.get(SessionParameter.CONNECT_TIMEOUT, -1);
            if (connectTimeout >= 0) {
                conn.setConnectTimeout(connectTimeout);
            }

            int readTimeout = session.get(SessionParameter.READ_TIMEOUT, -1);
            if (readTimeout >= 0) {
                conn.setReadTimeout(readTimeout);
            }

            // set content type
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            // set other headers
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.addRequestProperty(header.getKey(), header.getValue());
                }
            }

            // authenticate
            AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(session);
            if (authProvider != null) {
                Map<String, List<String>> httpHeaders = authProvider.getHTTPHeaders(url.toString());
                if (httpHeaders != null) {
                    for (Map.Entry<String, List<String>> header : httpHeaders.entrySet()) {
                        if (header.getKey() != null && isNotEmpty(header.getValue())) {
                            String key = header.getKey();
                            if (key.equalsIgnoreCase("user-agent")) {
                                conn.setRequestProperty("User-Agent", header.getValue().get(0));
                            } else {
                                for (String value : header.getValue()) {
                                    if (value != null) {
                                        conn.addRequestProperty(key, value);
                                    }
                                }
                            }
                        }
                    }
                }

                if (conn instanceof HttpsURLConnection) {
                    SSLSocketFactory sf = authProvider.getSSLSocketFactory();
                    if (sf != null) {
                        ((HttpsURLConnection) conn).setSSLSocketFactory(sf);
                    }

                    HostnameVerifier hv = authProvider.getHostnameVerifier();
                    if (hv != null) {
                        ((HttpsURLConnection) conn).setHostnameVerifier(hv);
                    }
                }
            }

            // range
            if (offset != null || length != null) {
                StringBuilder sb = new StringBuilder("bytes=");

                if ((offset == null) || (offset.signum() == -1)) {
                    offset = BigInteger.ZERO;
                }

                sb.append(offset.toString());
                sb.append('-');

                if (length != null && length.signum() == 1) {
                    sb.append(offset.add(length.subtract(BigInteger.ONE)).toString());
                }

                conn.setRequestProperty("Range", sb.toString());
            }

            // compression
            Object compression = session.get(SessionParameter.COMPRESSION);
            if (compression != null && Boolean.parseBoolean(compression.toString())) {
                conn.setRequestProperty("Accept-Encoding", "gzip,deflate");
            }

            // locale
            if (session.get(CmisBindingsHelper.ACCEPT_LANGUAGE) instanceof String) {
                conn.setRequestProperty("Accept-Language", session.get(CmisBindingsHelper.ACCEPT_LANGUAGE).toString());
            }

            // send data
            if (writer != null) {
                conn.setChunkedStreamingMode((64 * 1024) - 1);

                OutputStream connOut = null;

                Object clientCompression = session.get(SessionParameter.CLIENT_COMPRESSION);
                if ((clientCompression != null) && Boolean.parseBoolean(clientCompression.toString())) {
                    conn.setRequestProperty("Content-Encoding", "gzip");
                    connOut = new GZIPOutputStream(conn.getOutputStream(), 4096);
                } else {
                    connOut = conn.getOutputStream();
                }

                OutputStream out = new BufferedOutputStream(connOut, BUFFER_SIZE);
                writer.write(out);
                out.close();
            }

            // connect
            conn.connect();

            // get stream, if present
            respCode = conn.getResponseCode();
            InputStream inputStream = null;
            if (respCode == 200 || respCode == 201 || respCode == 203 || respCode == 206) {
                inputStream = conn.getInputStream();
            }

            // log after connect
            if (LOG.isTraceEnabled()) {
                LOG.trace("Session {}: {} {} > Headers: {}", session.getSessionId(), method, url,
                        conn.getHeaderFields().toString());
            }

            // forward response HTTP headers
            if (authProvider != null) {
                authProvider.putResponseHeaders(url.toString(), respCode, conn.getHeaderFields());
            }

            // get the response
            return new Response(respCode, conn.getResponseMessage(), conn.getHeaderFields(), inputStream,
                    conn.getErrorStream());
        } catch (Exception e) {
            throw new CmisConnectionException(url.toString(), respCode, e);
        }
    }
}
