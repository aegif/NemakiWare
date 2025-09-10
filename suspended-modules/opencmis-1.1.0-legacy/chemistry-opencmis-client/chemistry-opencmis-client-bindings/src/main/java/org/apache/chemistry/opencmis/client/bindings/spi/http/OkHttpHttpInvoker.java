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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.chemistry.opencmis.client.bindings.impl.ClientVersion;
import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.AbstractAuthenticationProvider;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

public class OkHttpHttpInvoker implements HttpInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(OkHttpHttpInvoker.class);

    protected static final String HTTP_CLIENT = "org.apache.chemistry.opencmis.client.bindings.spi.http.OkHttpHttpInvoker.httpClient";

    public OkHttpHttpInvoker() {
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

    private Response invoke(UrlBuilder url, String method, final String contentType, Map<String, String> headers,
            final Output writer, BindingSession session, BigInteger offset, BigInteger length) {
        int respCode = -1;

        try {
            // log before connect
            if (LOG.isDebugEnabled()) {
                LOG.debug("Session {}: {} {}", session.getSessionId(), method, url);
            }

            // get HTTP client object from session
            OkHttpClient httpclient = (OkHttpClient) session.get(HTTP_CLIENT);
            if (httpclient == null) {
                session.writeLock();
                try {
                    httpclient = (OkHttpClient) session.get(HTTP_CLIENT);
                    if (httpclient == null) {
                        httpclient = createClientBuilder(session).build();
                        session.put(HTTP_CLIENT, httpclient, true);
                    }
                } finally {
                    session.writeUnlock();
                }
            }

            // set up the request
            Request.Builder requestBuilder = new Request.Builder().url(url.toString());

            // prepare the request body
            RequestBody body = null;
            if (writer != null) {
                body = new RequestBody() {

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        try {
                            OutputStream out = sink.outputStream();
                            writer.write(out);
                            out.flush();
                        } catch (IOException ioe) {
                            throw ioe;
                        } catch (Exception e) {
                            throw new IOException("Could not send stream to server: " + e.toString(), e);
                        }
                    }

                    @Override
                    public MediaType contentType() {
                        if (contentType != null) {
                            return MediaType.parse(contentType);
                        } else {
                            return MediaType.parse("application/octet-stream");
                        }
                    }
                };
            }

            if ("GET".equals(method)) {
                requestBuilder.get();
            } else if ("POST".equals(method)) {
                requestBuilder.post(body);
            } else if ("PUT".equals(method)) {
                requestBuilder.put(body);
            } else if ("DELETE".equals(method)) {
                requestBuilder.delete();
            } else {
                throw new CmisRuntimeException("Invalid HTTP method!");
            }

            // set content type
            if (contentType != null) {
                requestBuilder.header("Content-Type", contentType);
            }
            // set other headers
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    requestBuilder.addHeader(header.getKey(), header.getValue());
                }
            }

            requestBuilder.header("User-Agent",
                    (String) session.get(SessionParameter.USER_AGENT, ClientVersion.OPENCMIS_USER_AGENT));

            // authenticate
            AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(session);
            if (authProvider != null) {
                Map<String, List<String>> httpHeaders = authProvider.getHTTPHeaders(url.toString());
                if (httpHeaders != null) {
                    for (Map.Entry<String, List<String>> header : httpHeaders.entrySet()) {
                        if (header.getKey() != null && isNotEmpty(header.getValue())) {
                            String key = header.getKey();
                            if (key.equalsIgnoreCase("user-agent")) {
                                requestBuilder.header("User-Agent", header.getValue().get(0));
                            } else {
                                for (String value : header.getValue()) {
                                    if (value != null) {
                                        requestBuilder.addHeader(key, value);
                                    }
                                }
                            }
                        }
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

                requestBuilder.header("Range", sb.toString());
            }

            // compression
            Object compression = session.get(SessionParameter.COMPRESSION);
            if (compression != null && Boolean.parseBoolean(compression.toString())) {
                requestBuilder.header("Accept-Encoding", "gzip,deflate");
            }

            // locale
            if (session.get(CmisBindingsHelper.ACCEPT_LANGUAGE) instanceof String) {
                requestBuilder.header("Accept-Language", session.get(CmisBindingsHelper.ACCEPT_LANGUAGE).toString());
            }

            okhttp3.Response okResponse = httpclient.newCall(requestBuilder.build()).execute();

            // get stream, if present
            respCode = okResponse.code();
            InputStream inputStream = null;
            InputStream errorStream = null;

            if (respCode == 200 || respCode == 201 || respCode == 203 || respCode == 206) {
                inputStream = okResponse.body().byteStream();
            } else {
                errorStream = okResponse.body().byteStream();
            }

            Map<String, List<String>> responseHeaders = okResponse.headers().toMultimap();

            // log after connect
            if (LOG.isTraceEnabled()) {
                LOG.trace("Session {}: {} {} > Headers: {}", session.getSessionId(), method, url,
                        responseHeaders.toString());
            }

            // forward response HTTP headers
            if (authProvider != null) {
                authProvider.putResponseHeaders(url.toString(), respCode, responseHeaders);
            }

            // get the response
            return new Response(respCode, okResponse.message(), responseHeaders, inputStream, errorStream);
        } catch (Exception e) {
            throw new CmisConnectionException(url.toString(), respCode, e);
        }
    }

    /**
     * Creates a OkHttpClient.Builder and configures it.
     * 
     * Subclasses can override this method to make use of OkHttp specific
     * features.
     * 
     * @param session
     *            the binding session
     * 
     * @return the builder
     */
    @SuppressWarnings("deprecation")
    protected OkHttpClient.Builder createClientBuilder(BindingSession session) {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

        // timeouts
        int connectTimeout = session.get(SessionParameter.CONNECT_TIMEOUT, -1);
        if (connectTimeout >= 0) {
            clientBuilder.connectTimeout(connectTimeout, TimeUnit.MILLISECONDS);
        }

        int readTimeout = session.get(SessionParameter.READ_TIMEOUT, -1);
        if (readTimeout >= 0) {
            clientBuilder.readTimeout(readTimeout, TimeUnit.MILLISECONDS);
        }

        AuthenticationProvider authProvider = CmisBindingsHelper.getAuthenticationProvider(session);
        if (authProvider != null) {
            SSLSocketFactory sf = authProvider.getSSLSocketFactory();
            if (sf != null) {
                X509TrustManager tm = null;

                if (authProvider instanceof AbstractAuthenticationProvider) {
                    tm = ((AbstractAuthenticationProvider) authProvider).getTrustManager();
                }

                if (tm == null) {
                    clientBuilder.sslSocketFactory(sf);
                } else {
                    clientBuilder.sslSocketFactory(sf, tm);
                }
            }

            HostnameVerifier hv = authProvider.getHostnameVerifier();
            if (hv != null) {
                clientBuilder.hostnameVerifier(hv);
            }
        }

        return clientBuilder;
    }
}
