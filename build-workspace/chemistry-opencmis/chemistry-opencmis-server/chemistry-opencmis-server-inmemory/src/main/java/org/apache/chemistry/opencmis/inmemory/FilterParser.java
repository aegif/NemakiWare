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
package org.apache.chemistry.opencmis.inmemory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;

public final class FilterParser {

    // Utility class
    private FilterParser() {
    }

    public static boolean isContainedInFilter(String propertyId, List<String> requestedIds) {
        if (requestedIds.contains("*")) {
            return true;
        }
        return requestedIds.contains(propertyId);
    }

    public static List<String> getRequestedIdsFromFilter(String filter) {
        if (filter == null || filter.length() == 0) {
            return Collections.singletonList("*");
        } else {
            List<String> requestedIds = Arrays.asList(filter.split(",\\s*")); // comma
            // plus
            // whitespace

            // add object id because this is always needed in AtomPub binding:
            if (!(requestedIds.contains(PropertyIds.OBJECT_ID))) {
                requestedIds = new ArrayList<String>(requestedIds); // copy
                                                                    // immutable
                                                                    // list
                requestedIds.add(PropertyIds.OBJECT_ID);
            }

            if (requestedIds.contains("*")) {
                requestedIds = Collections.singletonList("*");
            }
            return requestedIds;
        }
    }

}
