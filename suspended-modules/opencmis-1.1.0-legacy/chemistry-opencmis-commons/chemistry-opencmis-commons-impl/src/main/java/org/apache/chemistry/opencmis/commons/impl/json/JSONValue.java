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
package org.apache.chemistry.opencmis.commons.impl.json;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;

/**
 * (Taken from JSON.simple &lt;http://code.google.com/p/json-simple/&gt; and
 * modified for OpenCMIS.)
 * 
 * @author FangYidong&lt;fangyidong@yahoo.com.cn&gt;
 */
public final class JSONValue {

    private JSONValue() {
    }

    /**
     * Parse JSON text into java object from the input source. Please use
     * parseWithException() if you don't want to ignore the exception.
     * 
     * @see org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser#parse(Reader)
     * @see #parseWithException(Reader)
     * 
     * @param in
     * @return Instance of the following: JSONObject, JSONArray,
     *         java.lang.String, java.lang.Number, java.lang.Boolean, null
     * 
     */
    public static Object parse(Reader in) {
        try {
            JSONParser parser = new JSONParser();
            return parser.parse(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object parse(String s) {
        StringReader in = new StringReader(s);
        return parse(in);
    }

    /**
     * Parse JSON text into java object from the input source.
     * 
     * @see org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser
     * 
     * @param in
     * @return Instance of the following: JSONObject, JSONArray,
     *         java.lang.String, java.lang.Number, java.lang.Boolean, null
     */
    public static Object parseWithException(Reader in) throws IOException, JSONParseException {
        JSONParser parser = new JSONParser();
        return parser.parse(in);
    }

    public static Object parseWithException(String s) throws JSONParseException {
        JSONParser parser = new JSONParser();
        return parser.parse(s);
    }

    /**
     * Encode an object into JSON text and write it to out.
     * <p>
     * If this object is a Map or a List, and it's also a JSONStreamAware or a
     * JSONAware, JSONStreamAware or JSONAware will be considered firstly.
     * <p>
     * DO NOT call this method from writeJSONString(Writer) of a class that
     * implements both JSONStreamAware and (Map or List) with "this" as the
     * first parameter, use JSONObject.writeJSONString(Map, Writer) or
     * JSONArray.writeJSONString(List, Writer) instead.
     * 
     * @see org.apache.chemistry.opencmis.commons.impl.json.JSONObject#writeJSONString(Map,
     *      Writer)
     * @see org.apache.chemistry.opencmis.commons.impl.json.JSONArray#writeJSONString(List,
     *      Writer)
     * 
     * @param value
     * @param out
     */
    @SuppressWarnings("unchecked")
    public static void writeJSONString(Object value, Writer out) throws IOException {
        if (value == null) {
            out.write("null");
            return;
        }

        if (value instanceof String) {
            out.write('\"');
            out.write(escape((String) value));
            out.write('\"');
            return;
        }

        if (value instanceof Double) {
            if (((Double) value).isInfinite() || ((Double) value).isNaN()) {
                out.write("null");
            } else {
                out.write(value.toString());
            }
            return;
        }

        if (value instanceof Float) {
            if (((Float) value).isInfinite() || ((Float) value).isNaN()) {
                out.write("null");
            } else {
                out.write(value.toString());
            }
            return;
        }

        if (value instanceof BigDecimal) {
            out.write(((BigDecimal) value).toPlainString());
            return;
        }

        if (value instanceof Number) {
            out.write(value.toString());
            return;
        }

        if (value instanceof Boolean) {
            out.write(value.toString());
            return;
        }

        if (value instanceof JSONStreamAware) {
            ((JSONStreamAware) value).writeJSONString(out);
            return;
        }

        if (value instanceof JSONAware) {
            out.write(((JSONAware) value).toJSONString());
            return;
        }

        if (value instanceof Map) {
            JSONObject.writeJSONString((Map<String, Object>) value, out);
            return;
        }

        if (value instanceof List) {
            JSONArray.writeJSONString((List<Object>) value, out);
            return;
        }

        out.write(value.toString());
    }

    /**
     * Converts an object to JSON text.
     * <p>
     * If this object is a Map or a List, and it's also a JSONAware, JSONAware
     * will be considered firstly.
     * <p>
     * DO NOT call this method from toJSONString() of a class that implements
     * both JSONAware and Map or List with "this" as the parameter, use
     * JSONObject.toJSONString(Map) or JSONArray.toJSONString(List) instead.
     * 
     * @see org.apache.chemistry.opencmis.commons.impl.json.JSONObject#toJSONString(Map)
     * @see org.apache.chemistry.opencmis.commons.impl.json.JSONArray#toJSONString(List)
     * 
     * @param value
     * @return JSON text, or "null" if value is null or it's an NaN or an INF
     *         number.
     */
    public static String toJSONString(Object value) {
        StringBuilder sb = new StringBuilder(1024);
        addJSONString(value, sb);
        return sb.toString();
    }

    /**
     * Converts an object to JSON text and attach it the the given
     * StringBuilder.
     * 
     * @see #toJSONString(Object)
     */
    @SuppressWarnings("unchecked")
    public static void addJSONString(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
            return;
        }

        if (value instanceof String) {
            sb.append('\"');
            escape((String) value, sb);
            sb.append('\"');
            return;
        }

        if (value instanceof Double) {
            if (((Double) value).isInfinite() || ((Double) value).isNaN()) {
                sb.append("null");
            } else {
                sb.append(value.toString());
            }
            return;
        }

        if (value instanceof Float) {
            if (((Float) value).isInfinite() || ((Float) value).isNaN()) {
                sb.append("null");
            } else {
                sb.append(value.toString());
            }
            return;
        }

        if (value instanceof BigDecimal) {
            sb.append(((BigDecimal) value).toPlainString());
            return;
        }

        if (value instanceof Number) {
            sb.append(value.toString());
            return;
        }

        if (value instanceof Boolean) {
            sb.append(value.toString());
            return;
        }

        if (value instanceof JSONAware) {
            sb.append(((JSONAware) value).toJSONString());
            return;
        }

        if (value instanceof Map) {
            JSONObject.addJSONString((Map<String, Object>) value, sb);
            return;
        }

        if (value instanceof List) {
            JSONArray.addJSONString((List<Object>) value, sb);
            return;
        }

        sb.append(value.toString());
    }

    /**
     * Escape quotes, \, /, \r, \n, \b, \f, \t and other control characters
     * (U+0000 through U+001F).
     * 
     * @param s
     * @return
     */
    public static String escape(String s) {
        if (s == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(s.length() + 16);
        escape(s, sb);
        return sb.toString();
    }

    /**
     * @param s
     *            - Must not be null.
     * @param sb
     */
    static void escape(String s, StringBuilder sb) {
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            switch (ch) {
            case '"':
                sb.append("\\\"");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            case '/':
                sb.append("\\/");
                break;
            default:
                // Reference: http://www.unicode.org/versions/Unicode5.1.0/
                if ((ch >= '\u0000' && ch <= '\u001F') || (ch >= '\u007F' && ch <= '\u009F')
                        || (ch >= '\u2000' && ch <= '\u20FF')) {
                    String ss = Integer.toHexString(ch);
                    sb.append("\\u");
                    for (int k = 0; k < 4 - ss.length(); k++) {
                        sb.append('0');
                    }
                    sb.append(ss.toUpperCase());
                } else {
                    sb.append(ch);
                }
            }
        }// for
    }
}
