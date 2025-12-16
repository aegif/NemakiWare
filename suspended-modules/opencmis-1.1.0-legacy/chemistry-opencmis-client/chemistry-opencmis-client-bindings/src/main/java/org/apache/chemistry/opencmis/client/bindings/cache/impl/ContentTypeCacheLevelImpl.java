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
package org.apache.chemistry.opencmis.client.bindings.cache.impl;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Content type cache.
 */
public class ContentTypeCacheLevelImpl extends MapCacheLevelImpl {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public ContentTypeCacheLevelImpl() {
        super();
        enableKeyFallback(null);
    }

    @Override
    public Object get(String key) {
        return super.get(normalize(key));
    }

    @Override
    public void put(Object value, String key) {
        super.put(value, normalize(key));
    }

    @Override
    public void remove(String key) {
        super.remove(normalize(key));
    }

    /**
     * Normalizes the key which should be a content type. It's quite simple at
     * the moment but should cover most cases.
     */
    private static String normalize(String key) {
        if (key == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(32);
        int parameterStart = 0;

        // first, get the MIME type
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);

            if (Character.isWhitespace(c)) {
                continue;
            } else if (c == ';') {
                parameterStart = i;
                break;
            }

            sb.append(Character.toLowerCase(c));
        }

        // if parameters have been found, gather them
        if (parameterStart > 0) {
            SortedMap<String, String> parameter = new TreeMap<String, String>();
            StringBuilder ksb = new StringBuilder(32);
            StringBuilder vsb = new StringBuilder(32);
            boolean isKey = true;

            for (int i = parameterStart + 1; i < key.length(); i++) {
                char c = key.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }

                if (isKey) {
                    if (c == '=') {
                        // value start
                        isKey = false;
                        continue;
                    }

                    ksb.append(Character.toLowerCase(c));
                } else {
                    if (c == ';') {
                        // next key
                        isKey = true;

                        parameter.put(ksb.toString(), vsb.toString());

                        ksb.setLength(0);
                        vsb.setLength(0);

                        continue;
                    } else if (c == '"') {
                        // filter quotes
                        continue;
                    }

                    vsb.append(Character.toLowerCase(c));
                }
            }

            // add last parameter
            if (ksb.length() > 0) {
                parameter.put(ksb.toString(), vsb.toString());
            }

            // write parameters sorted by key
            for (Map.Entry<String, String> entry : parameter.entrySet()) {
                sb.append(';');
                sb.append(entry.getKey());
                sb.append('=');
                sb.append(entry.getValue());
            }
        }

        return sb.toString();
    }
}
