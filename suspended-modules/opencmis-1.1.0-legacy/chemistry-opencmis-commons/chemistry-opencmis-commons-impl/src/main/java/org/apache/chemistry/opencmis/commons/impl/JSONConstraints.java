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

public class JSONConstraints {

    public static final int MAX_OBJECT_SIZE;
    public static final int MAX_ARRAY_SIZE;
    public static final int MAX_DEPTH;

    public static final int MAX_OBJECT_SIZE_DEFAULT = 1000000;
    public static final int MAX_ARRAY_SIZE_DEFAULT = 1000000;
    public static final int MAX_DEPTH_DEFAULT = 200;

    public static final String MAX_OBJECT_SIZE_SYSTEM_PROPERTY = "org.apache.chemistry.opencmis.JSONConstraints.maxObjectSize";
    public static final String MAX_ARRAY_SIZE_SYSTEM_PROPERTY = "org.apache.chemistry.opencmis.JSONConstraints.maxArraySize";
    public static final String MAX_DEPTH_SYSTEM_PROPERTY = "org.apache.chemistry.opencmis.JSONConstraints.maxDepth";

    static {
        int maxObjectSize = MAX_OBJECT_SIZE_DEFAULT;
        try {
            String maxObjectSizeStr = System.getProperty(MAX_OBJECT_SIZE_SYSTEM_PROPERTY);
            if (maxObjectSizeStr != null) {
                maxObjectSize = Integer.parseInt(maxObjectSizeStr);

                // check for sane values
                if (maxObjectSize < 100) {
                    maxObjectSize = MAX_OBJECT_SIZE_DEFAULT;
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        MAX_OBJECT_SIZE = maxObjectSize;

        int maxArraySize = MAX_ARRAY_SIZE_DEFAULT;
        try {
            String maxArraySizeStr = System.getProperty(MAX_ARRAY_SIZE_SYSTEM_PROPERTY);
            if (maxArraySizeStr != null) {
                maxArraySize = Integer.parseInt(maxArraySizeStr);

                // check for sane values
                if (maxArraySize < 1000) {
                    maxArraySize = MAX_ARRAY_SIZE_DEFAULT;
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        MAX_ARRAY_SIZE = maxArraySize;

        int maxDepth = MAX_DEPTH_DEFAULT;
        try {
            String maxDepthStr = System.getProperty(MAX_DEPTH_SYSTEM_PROPERTY);
            if (maxDepthStr != null) {
                maxDepth = Integer.parseInt(maxDepthStr);

                // check for sane values
                if (maxDepth < 10) {
                    maxDepth = MAX_DEPTH_DEFAULT;
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        MAX_DEPTH = maxDepth;
    }

    private JSONConstraints() {
    }
}
