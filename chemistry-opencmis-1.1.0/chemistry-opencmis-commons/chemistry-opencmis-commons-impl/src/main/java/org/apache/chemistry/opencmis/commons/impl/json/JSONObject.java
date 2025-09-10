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
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JSON object. Key value pairs are order by insertion. JSONObject supports
 * java.util.Map interface.
 * 
 * (Taken from JSON.simple &lt;http://code.google.com/p/json-simple/&gt; and
 * modified for OpenCMIS.)
 * 
 * @author FangYidong&lt;fangyidong@yahoo.com.cn&gt;
 */
public class JSONObject extends LinkedHashMap<String, Object> implements Map<String, Object>, JSONAware,
        JSONStreamAware {

    private static final long serialVersionUID = 1;

    @Override
    public Object put(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("JSON key must not be null!");
        }

        return super.put(key, value);
    }

    /**
     * Encode a map into JSON text and write it to out. If this map is also a
     * JSONAware or JSONStreamAware, JSONAware or JSONStreamAware specific
     * behaviours will be ignored at this top level.
     * 
     * @see JSONValue#writeJSONString(Object, Writer)
     * 
     * @param map
     * @param out
     */
    public static void writeJSONString(Map<String, Object> map, Writer out) throws IOException {
        if (map == null) {
            out.write("null");
            return;
        }

        boolean first = true;

        out.write('{');
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (first) {
                first = false;
            } else {
                out.write(',');
            }

            out.write('\"');
            if (entry.getKey() == null) {
                out.write("null");
            } else {
                out.write(escape(entry.getKey()));
            }
            out.write('\"');
            out.write(':');
            JSONValue.writeJSONString(entry.getValue(), out);
        }
        out.write('}');
    }

    @Override
    public void writeJSONString(Writer out) throws IOException {
        writeJSONString(this, out);
    }

    /**
     * Convert a map to JSON text. The result is a JSON object. If this map is
     * also a JSONAware, JSONAware specific behaviours will be omitted at this
     * top level.
     * 
     * @see JSONValue#toJSONString(Object)
     * 
     * @param map
     * @return JSON text, or "null" if map is null.
     */
    public static String toJSONString(Map<String, Object> map) {
        if (map == null) {
            return "null";
        }

        StringBuilder sb = new StringBuilder(1024);
        addJSONString(map, sb);
        return sb.toString();
    }

    public static void addJSONString(Map<String, Object> map, StringBuilder sb) {
        if (map == null) {
            sb.append("null");
            return;
        }

        boolean first = true;

        sb.append('{');
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }

            addJSONString(entry.getKey(), entry.getValue(), sb);
        }
        sb.append('}');
    }

    @Override
    public String toJSONString() {
        return toJSONString(this);
    }

    private static void addJSONString(String key, Object value, StringBuilder sb) {
        sb.append('\"');
        if (key == null) {
            sb.append("null");
        } else {
            JSONValue.escape(key, sb);
        }

        sb.append('\"').append(':');

        sb.append(JSONValue.toJSONString(value));
    }

    @Override
    public String toString() {
        return toJSONString();
    }

    public static String toString(String key, Object value) {
        StringBuilder sb = new StringBuilder(1024);
        addJSONString(key, value, sb);
        return sb.toString();
    }

    /**
     * Escape quotes, \, /, \r, \n, \b, \f, \t and other control characters
     * (U+0000 through U+001F). It's the same as JSONValue.escape() only for
     * compatibility here.
     * 
     * @see JSONValue#escape(String)
     * 
     * @param s
     * @return
     */
    public static String escape(String s) {
        return JSONValue.escape(s);
    }
}
