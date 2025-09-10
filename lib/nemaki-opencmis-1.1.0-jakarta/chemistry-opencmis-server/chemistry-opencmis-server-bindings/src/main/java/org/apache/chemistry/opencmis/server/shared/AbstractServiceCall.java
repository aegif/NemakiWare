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
package org.apache.chemistry.opencmis.server.shared;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.CacheHeaderContentStream;
import org.apache.chemistry.opencmis.commons.data.ContentLengthContentStream;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.LastModifiedContentStream;
import org.apache.chemistry.opencmis.commons.data.RedirectingContentStream;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.CmisEnumHelper;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.ProgressControlCmisService;
import org.apache.chemistry.opencmis.commons.server.ProgressControlCmisService.Progress;

public abstract class AbstractServiceCall implements ServiceCall {

    /**
     * Extracts a string parameter.
     */
    public String getStringParameter(HttpServletRequest request, String name) {
        return HttpUtils.getStringParameter(request, name);
    }

    /**
     * Extracts a boolean parameter (with default).
     */
    public boolean getBooleanParameter(HttpServletRequest request, String name, boolean def) {
        String value = getStringParameter(request, name);
        if (value == null || value.length() == 0) {
            return def;
        }

        return Boolean.valueOf(value);
    }

    /**
     * Extracts a boolean parameter.
     */
    public Boolean getBooleanParameter(HttpServletRequest request, String name) {
        String value = getStringParameter(request, name);
        if (value == null || value.length() == 0) {
            return null;
        }

        return Boolean.valueOf(value);
    }

    /**
     * Extracts an integer parameter (with default).
     */
    public BigInteger getBigIntegerParameter(HttpServletRequest request, String name, long def) {
        BigInteger result = getBigIntegerParameter(request, name);
        if (result == null) {
            result = BigInteger.valueOf(def);
        }

        return result;
    }

    /**
     * Extracts an integer parameter.
     */
    public BigInteger getBigIntegerParameter(HttpServletRequest request, String name) {
        String value = getStringParameter(request, name);
        if (value == null || value.length() == 0) {
            return null;
        }

        try {
            return new BigInteger(value);
        } catch (Exception e) {
            throw new CmisInvalidArgumentException("Invalid parameter '" + name + "'!", e);
        }
    }

    public DateTimeFormat getDateTimeFormatParameter(HttpServletRequest request) {
        String s = getStringParameter(request, Constants.PARAM_DATETIME_FORMAT);

        if (s == null) {
            return DateTimeFormat.SIMPLE;
        }

        try {
            return DateTimeFormat.fromValue(s.trim().toLowerCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            throw new CmisInvalidArgumentException("Invalid value for parameter " + Constants.PARAM_DATETIME_FORMAT
                    + "!");
        }
    }

    /**
     * Extracts an enum parameter.
     */
    public <T extends Enum<T>> T getEnumParameter(HttpServletRequest request, String name, Class<T> clazz) {
        return CmisEnumHelper.fromValue(getStringParameter(request, name), clazz);
    }

    /**
     * Sets certain HTTP headers if the server implementation requested them.
     * 
     * @return {@code true} if the request has been served by this method (for
     *         example status code 304 was send), {@code false} if the content
     *         should be served.
     */
    public boolean sendContentStreamHeaders(ContentStream content, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        assert request != null;
        assert response != null;

        // check if Last-Modified header should be set
        if (content instanceof LastModifiedContentStream) {
            GregorianCalendar lastModified = ((LastModifiedContentStream) content).getLastModified();
            if (lastModified != null) {
                long lastModifiedSecs = (long) Math.floor((double) lastModified.getTimeInMillis() / 1000);

                Date modifiedSince = DateTimeHelper.parseHttpDateTime(request.getHeader("If-Modified-Since"));
                if (modifiedSince != null) {
                    long modifiedSinceSecs = (long) Math.floor((double) modifiedSince.getTime() / 1000);

                    if (modifiedSinceSecs >= lastModifiedSecs) {
                        // close stream
                        content.getStream().close();

                        // send not modified status code
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        response.setContentLength(0);
                        return true;
                    }
                }

                response.setHeader("Last-Modified", DateTimeHelper.formatHttpDateTime(lastModifiedSecs * 1000));
            }
        }

        // check if redirection is needed
        if (content instanceof RedirectingContentStream) {
            RedirectingContentStream rcs = (RedirectingContentStream) content;
            // close stream
            if (content.getStream() != null) {
                IOUtils.closeQuietly(content.getStream());
            }

            if (rcs.getLocation() != null) {
                response.setHeader("Location", rcs.getLocation());
            }

            int status = rcs.getStatus();
            if (status < 300 || status >= 400) {
                status = HttpServletResponse.SC_TEMPORARY_REDIRECT;
            }

            response.setStatus(status);

            return true;
        }

        // check if cache headers should be set
        if (content instanceof CacheHeaderContentStream) {
            CacheHeaderContentStream chcs = (CacheHeaderContentStream) content;

            if (chcs.getETag() != null) {
                String etag = request.getHeader("If-None-Match");
                if (etag != null && !etag.equals("*")) {
                    if (etag.length() > 2 && etag.charAt(0) == '"' && etag.endsWith("\"")) {
                        etag = etag.substring(1, etag.length() - 1);
                    }

                    if (chcs.getETag().equals(etag)) {
                        // close stream
                        content.getStream().close();

                        // send not modified status code
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        response.setContentLength(0);
                        return true;
                    }
                }

                response.setHeader("ETag", "\"" + chcs.getETag() + "\"");
            }

            if (chcs.getCacheControl() != null) {
                response.setHeader("Cache-Control", chcs.getCacheControl());
            }

            if (chcs.getExpires() != null) {
                response.setHeader("Expires", DateTimeHelper.formatHttpDateTime(chcs.getExpires()));
            }
        }

        // check if Content-Length header should be set
        if (content instanceof ContentLengthContentStream) {
            if (content.getBigLength() != null && content.getBigLength().signum() >= 0) {
                response.setHeader("Content-Length", content.getBigLength().toString());
            }
        }

        return false;
    }

    /**
     * Determines if the processing should be stopped before the service method
     * is called.
     * 
     * @return {@code true} if the processing should be stopped, {@code false}
     *         otherwise
     */
    protected boolean stopBeforeService(CmisService service) {
        if (!(service instanceof ProgressControlCmisService)) {
            return false;
        }

        return ((ProgressControlCmisService) service).beforeServiceCall() == Progress.STOP;
    }

    /**
     * Determines if the processing should be stopped after the service method
     * is called.
     * 
     * @return {@code true} if the processing should be stopped, {@code false}
     *         otherwise
     */
    protected boolean stopAfterService(CmisService service) {
        if (!(service instanceof ProgressControlCmisService)) {
            return false;
        }

        return ((ProgressControlCmisService) service).afterServiceCall() == Progress.STOP;
    }
}
