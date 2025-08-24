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
package org.apache.chemistry.opencmis.server.support.query;

public final class StringUtil {

    private StringUtil() {
    }

    /**
     * Removes all escape sequences in a string and return unescaped string
     * escape character is backslash \, so \\ --&gt; \, \' --&gt; ' additional
     * escaped characters can be allowed in escapedChars.
     * 
     * @param literal
     *            String to unescape
     * @param escapedChars
     *            set of allowed characters to be escaped with a backslash, if
     *            set to null then ' (quote) and \ (backslash) are allowed to be
     *            escaped
     * @return unescaped literal or null if the literal is illegal
     */
    public static String unescape(String literal, String escapedChars) {
        char c = '?';
        int i = 0;

        if (null == escapedChars) {
            escapedChars = "\\'";
        }

        if (null == literal) {
            return null;
        }

        int len = literal.length();

        if (len == 1 && literal.charAt(0) == '\\') {
            return null;
        }

        if (len > 1 && literal.charAt(len - 2) != '\\' && literal.charAt(len - 1) == '\\') {
            return null;
        }

        StringBuilder sb = new StringBuilder(len + 16);

        for (i = 0; i < len; i++) {
            c = literal.charAt(i);
            if (c == '\\') {
                char escChar = literal.charAt(i + 1);
                boolean matched = false;
                for (int j = 0; j < escapedChars.length(); j++) {
                    if (escChar == escapedChars.charAt(j)) {
                        sb.append(escChar);
                        ++i;
                        matched = true;
                        break;
                    }
                }

                if (!matched) {
                    return null;
                }

            } else {
                sb.append(literal.charAt(i));
            }
        }

        return sb.toString();
    }

}
