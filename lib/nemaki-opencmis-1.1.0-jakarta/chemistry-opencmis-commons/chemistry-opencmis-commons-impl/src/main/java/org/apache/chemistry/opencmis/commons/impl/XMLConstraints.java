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
package org.apache.chemistry.opencmis.commons.impl;

public final class XMLConstraints {

    public static final int MAX_STRING_LENGTH = 100 * 1024;

    public static final int MAX_EXTENSIONS_WIDTH;
    public static final int MAX_EXTENSIONS_DEPTH;

    public static final int MAX_EXTENSIONS_WIDTH_DEFAULT = 1000;
    public static final int MAX_EXTENSIONS_DEPTH_DEFAULT = 100;

    public static final String MAX_EXTENSIONS_WIDTH_SYSTEM_PROPERTY = "org.apache.chemistry.opencmis.XMLConstraints.maxExtensionWith";
    public static final String MAX_EXTENSIONS_DEPTH_SYSTEM_PROPERTY = "org.apache.chemistry.opencmis.XMLConstraints.maxExtensionDepth";

    static {
        int maxWidth = MAX_EXTENSIONS_WIDTH_DEFAULT;
        try {
            String maxWidthStr = System.getProperty(MAX_EXTENSIONS_WIDTH_SYSTEM_PROPERTY);
            if (maxWidthStr != null) {
                maxWidth = Integer.parseInt(maxWidthStr);

                // check for sane values
                if (maxWidth < 1 || maxWidth > 100000) {
                    maxWidth = MAX_EXTENSIONS_WIDTH_DEFAULT;
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        MAX_EXTENSIONS_WIDTH = maxWidth;

        int maxDepth = MAX_EXTENSIONS_DEPTH_DEFAULT;
        try {
            String maxDepthStr = System.getProperty(MAX_EXTENSIONS_DEPTH_SYSTEM_PROPERTY);
            if (maxDepthStr != null) {
                maxDepth = Integer.parseInt(maxDepthStr);

                // check for sane values
                if (maxDepth < 1 || maxDepth > 10000) {
                    maxDepth = MAX_EXTENSIONS_DEPTH_DEFAULT;
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        MAX_EXTENSIONS_DEPTH = maxDepth;
    }

    private XMLConstraints() {
    }
}
