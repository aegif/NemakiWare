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
package org.apache.chemistry.opencmis.commons.impl.endpoints;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.endpoints.CmisAuthentication;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpoint;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;

/**
 * CMIS endpoints document helper methods.
 */
public class CmisEndpointsDocumentHelper {

    // -- read --

    public static CmisEndpointsDocument read(URL url) throws IOException, JSONParseException {
        if (url == null) {
            throw new IllegalArgumentException("URL is null!");
        }

        InputStream stream = url.openStream();
        try {
            return read(stream);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    public static CmisEndpointsDocument read(File file) throws IOException, JSONParseException {
        if (file == null) {
            throw new IllegalArgumentException("File is null!");
        }

        InputStream stream = new BufferedInputStream(new FileInputStream(file));
        try {
            return read(stream);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    public static CmisEndpointsDocument read(InputStream in) throws IOException, JSONParseException {
        if (in == null) {
            throw new IllegalArgumentException("InputStream is null!");
        }

        return read(new InputStreamReader(in, "UTF-8"));
    }

    public static CmisEndpointsDocument read(Reader in) throws IOException, JSONParseException {
        if (in == null) {
            throw new IllegalArgumentException("Reader is null!");
        }

        JSONParser parser = new JSONParser();
        return convert(parser.parse(in));
    }

    public static CmisEndpointsDocument read(String in) throws JSONParseException {
        if (in == null) {
            throw new IllegalArgumentException("String is null!");
        }

        JSONParser parser = new JSONParser();
        return convert(parser.parse(in));
    }

    private static CmisEndpointsDocument convert(Object obj) throws JSONParseException {
        if (!(obj instanceof JSONObject)) {
            throw new IllegalArgumentException("JSON is not a CMIS Endpoint Document!");
        }

        return convertEndpointsDocument((JSONObject) obj);
    }

    private static CmisEndpointsDocument convertEndpointsDocument(JSONObject json) {
        CmisEndpointsDocumentImpl result = new CmisEndpointsDocumentImpl();

        for (Map.Entry<String, Object> entry : json.entrySet()) {
            if (CmisEndpointsDocument.KEY_ENDPOINTS.equals(entry.getKey()) && (entry.getValue() instanceof JSONArray)) {
                result.put(entry.getKey(), convertEndpoints((JSONArray) entry.getValue()));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    private static List<Object> convertEndpoints(JSONArray json) {
        List<Object> result = new ArrayList<Object>();

        for (Object item : json) {
            if (item instanceof JSONObject) {
                CmisEndpointImpl endpoint = new CmisEndpointImpl();

                for (Map.Entry<String, Object> entry : ((JSONObject) item).entrySet()) {
                    if (CmisEndpoint.KEY_AUTHENTICATION.equals(entry.getKey())
                            && (entry.getValue() instanceof JSONArray)) {
                        endpoint.put(entry.getKey(), convertAuthentication(endpoint, (JSONArray) entry.getValue()));
                    } else {
                        endpoint.put(entry.getKey(), entry.getValue());
                    }
                }

                result.add(endpoint);
            } else {
                result.add(item);
            }
        }

        return result;
    }

    private static List<Object> convertAuthentication(CmisEndpoint endpoint, JSONArray json) {
        List<Object> result = new ArrayList<Object>();

        for (Object item : json) {
            if (item instanceof JSONObject) {
                CmisAuthenticationImpl auth = new CmisAuthenticationImpl(endpoint);
                auth.putAll((JSONObject) item);
                result.add(auth);
            } else {
                result.add(item);
            }
        }

        return result;
    }

    // -- write --

    public static void write(CmisEndpointsDocument doc, OutputStream out) throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("Document must be set!");
        }
        if (out == null) {
            throw new IllegalArgumentException("OutputStream is null!");
        }

        Writer writer = new OutputStreamWriter(out, "UTF-8");
        write(doc, writer);
        writer.flush();
    }

    public static void write(CmisEndpointsDocument doc, Writer out) throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("Document must be set!");
        }
        if (out == null) {
            throw new IllegalArgumentException("Writer is null!");
        }

        JSONObject.writeJSONString(doc, out);
        out.flush();
    }

    public static String write(CmisEndpointsDocument doc) {
        if (doc == null) {
            throw new IllegalArgumentException("Document must be set!");
        }

        return JSONObject.toJSONString(doc);
    }

    public static void write(CmisEndpoint endpoint, OutputStream out) throws IOException {
        if (endpoint == null) {
            throw new IllegalArgumentException("Endpoint must be set!");
        }
        if (out == null) {
            throw new IllegalArgumentException("Endpoint is null!");
        }

        Writer writer = new OutputStreamWriter(out, "UTF-8");
        write(endpoint, writer);
        writer.flush();
    }

    public static void write(CmisEndpoint endpoint, Writer out) throws IOException {
        if (endpoint == null) {
            throw new IllegalArgumentException("Endpoint must be set!");
        }
        if (out == null) {
            throw new IllegalArgumentException("Writer is null!");
        }

        JSONObject.writeJSONString(endpoint, out);
        out.flush();
    }

    public static String write(CmisEndpoint endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("Endpoint must be set!");
        }

        return JSONObject.toJSONString(endpoint);
    }

    public static void write(CmisAuthentication authentication, OutputStream out) throws IOException {
        if (authentication == null) {
            throw new IllegalArgumentException("Authentication must be set!");
        }
        if (out == null) {
            throw new IllegalArgumentException("Endpoint is null!");
        }

        Writer writer = new OutputStreamWriter(out, "UTF-8");
        write(authentication, writer);
        writer.flush();
    }

    public static void write(CmisAuthentication authentication, Writer out) throws IOException {
        if (authentication == null) {
            throw new IllegalArgumentException("Authentication must be set!");
        }
        if (out == null) {
            throw new IllegalArgumentException("Writer is null!");
        }

        JSONObject.writeJSONString(authentication, out);
        out.flush();
    }

    public static String write(CmisAuthentication authentication) {
        if (authentication == null) {
            throw new IllegalArgumentException("Authentication must be set!");
        }

        return JSONObject.toJSONString(authentication);
    }
}
