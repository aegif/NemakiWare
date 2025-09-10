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
package org.apache.chemistry.opencmis.server.impl;

import java.io.File;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.server.MutableCallContext;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

/**
 * Implementation of the {@link CallContext} interface.
 */
public class CallContextImpl implements MutableCallContext, Serializable {

    private static final long serialVersionUID = 1L;

    private final String binding;
    private final boolean objectInfoRequired;
    private final Map<String, Object> parameter = new HashMap<String, Object>();

    public CallContextImpl(String binding, CmisVersion cmisVersion, String repositoryId, ServletContext servletContext,
            HttpServletRequest request, HttpServletResponse response, CmisServiceFactory factory,
            TempStoreOutputStreamFactory streamFactory) {
        this.binding = binding;
        this.objectInfoRequired = BINDING_ATOMPUB.equals(binding);
        put(REPOSITORY_ID, repositoryId);

        // CMIS version
        if (cmisVersion == null) {
            throw new IllegalArgumentException("CMIS version must be set!");
        }
        put(CallContext.CMIS_VERSION, cmisVersion);

        // servlet context and HTTP servlet request and response
        put(CallContext.SERVLET_CONTEXT, servletContext);
        put(CallContext.HTTP_SERVLET_REQUEST, request);
        put(CallContext.HTTP_SERVLET_RESPONSE, response);

        if (factory != null) {
            put(TEMP_DIR, factory.getTempDirectory());
            put(MEMORY_THRESHOLD, factory.getMemoryThreshold());
            put(MAX_CONTENT_SIZE, factory.getMaxContentSize());
            put(ENCRYPT_TEMP_FILE, factory.encryptTempFiles());
            put(STREAM_FACTORY, streamFactory);
        }
    }

    public void setRange(String rangeHeader) {
        if (rangeHeader == null) {
            return;
        }

        remove(OFFSET);
        remove(LENGTH);

        rangeHeader = rangeHeader.replaceAll("\\s", "").toLowerCase(Locale.ENGLISH);

        if (rangeHeader.length() > 6 && rangeHeader.startsWith("bytes=") && rangeHeader.indexOf(',') == -1
                && rangeHeader.charAt(6) != '-') {
            BigInteger offset = null;
            BigInteger length = null;

            int ds = rangeHeader.indexOf('-');
            if (ds > 6) {
                try {
                    String firstBytePosStr = rangeHeader.substring(6, ds);
                    if (firstBytePosStr.length() > 0) {
                        offset = new BigInteger(firstBytePosStr);
                    }

                    if (!rangeHeader.endsWith("-")) {
                        String lastBytePosStr = rangeHeader.substring(ds + 1);
                        if (offset == null) {
                            length = (new BigInteger(lastBytePosStr)).add(BigInteger.ONE);
                        } else {
                            length = (new BigInteger(lastBytePosStr)).subtract(offset).add(BigInteger.ONE);
                        }
                    }

                    if (offset != null) {
                        put(OFFSET, offset);
                    }
                    if (length != null) {
                        put(LENGTH, length);
                    }
                } catch (NumberFormatException e) {
                    // invalid Range header must be ignored
                }
            }
        }
    }

    public void setAcceptLanguage(String acceptLanguageHeader) {
        if (acceptLanguageHeader == null) {
            return;
        }

        remove(LOCALE_ISO639_LANGUAGE);
        remove(LOCALE_ISO3166_COUNTRY);
        remove(LOCALE);

        double lastQ = 0;
        String language = null;
        String country = null;

        String[] languageHeader = acceptLanguageHeader.split(",");
        for (String languageRange : languageHeader) {
            String langRange = languageRange.trim();
            double currentQ = 0;

            int x = langRange.indexOf(';');
            if (x > -1) {
                String qStr = langRange.substring(x + 1).replaceAll("\\s", "").toLowerCase(Locale.ENGLISH);
                if (!qStr.startsWith("q=") && qStr.length() < 3) {
                    continue;
                }
                currentQ = Double.parseDouble(qStr.substring(2));
                langRange = langRange.substring(0, x);
            } else {
                currentQ = 1;
            }

            if (currentQ <= lastQ) {
                continue;
            } else {
                lastQ = currentQ;
            }

            String[] locale = langRange.split("-");
            String locale0 = locale[0].trim();

            language = null;
            country = null;

            if (!locale0.equals("*")) {
                language = locale0;
                if (locale.length > 1) {
                    String locale1 = locale[1].trim();
                    if (!locale1.equals("*")) {
                        country = locale1;
                    }
                }
            }

            if (currentQ >= 1) {
                break;
            }
        }

        if (language != null) {
            put(LOCALE_ISO639_LANGUAGE, language);
            put(LOCALE, language);
        }

        if (country != null) {
            put(LOCALE_ISO3166_COUNTRY, country);
            put(LOCALE, language + "-" + country);
        }
    }

    @Override
    public String getBinding() {
        return binding;
    }

    @Override
    public boolean isObjectInfoRequired() {
        return objectInfoRequired;
    }

    @Override
    public Object get(String key) {
        return parameter.get(key);
    }

    @Override
    public CmisVersion getCmisVersion() {
        return (CmisVersion) get(CMIS_VERSION);
    }

    @Override
    public String getRepositoryId() {
        return (String) get(REPOSITORY_ID);
    }

    @Override
    public String getUsername() {
        return (String) get(USERNAME);
    }

    @Override
    public String getPassword() {
        return (String) get(PASSWORD);
    }

    @Override
    public String getLocale() {
        return (String) get(LOCALE);
    }

    @Override
    public BigInteger getOffset() {
        return (BigInteger) get(OFFSET);
    }

    @Override
    public BigInteger getLength() {
        return (BigInteger) get(LENGTH);
    }

    @Override
    public File getTempDirectory() {
        return (File) get(TEMP_DIR);
    }

    @Override
    public boolean encryptTempFiles() {
        return Boolean.TRUE.equals(get(ENCRYPT_TEMP_FILE));
    }

    @Override
    public int getMemoryThreshold() {
        return (Integer) get(MEMORY_THRESHOLD);
    }

    @Override
    public long getMaxContentSize() {
        return (Long) get(MAX_CONTENT_SIZE);
    }

    @Override
    public final void put(String key, Object value) {
        parameter.put(key, value);
    }

    @Override
    public final Object remove(String key) {
        return parameter.remove(key);
    }
}
