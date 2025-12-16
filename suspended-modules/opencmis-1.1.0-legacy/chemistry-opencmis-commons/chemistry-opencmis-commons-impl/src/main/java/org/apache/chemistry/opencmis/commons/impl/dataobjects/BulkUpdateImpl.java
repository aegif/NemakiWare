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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.Properties;

public class BulkUpdateImpl extends AbstractExtensionData {

    private static final long serialVersionUID = 1L;

    private List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken;
    private Properties properties;
    private List<String> addSecondaryTypeIds;
    private List<String> removeSecondaryTypeIds;

    public List<BulkUpdateObjectIdAndChangeToken> getObjectIdAndChangeToken() {
        return objectIdAndChangeToken;
    }

    public void setObjectIdAndChangeToken(List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken) {
        this.objectIdAndChangeToken = objectIdAndChangeToken;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public List<String> getAddSecondaryTypeIds() {
        return addSecondaryTypeIds;
    }

    public void setAddSecondaryTypeIds(List<String> addSecondaryTypeIds) {
        this.addSecondaryTypeIds = addSecondaryTypeIds;
    }

    public List<String> getRemoveSecondaryTypeIds() {
        return removeSecondaryTypeIds;
    }

    public void setRemoveSecondaryTypeIds(List<String> removeSecondaryTypeIds) {
        this.removeSecondaryTypeIds = removeSecondaryTypeIds;
    }

    @Override
    public String toString() {
        return "BulkUpdate [objectIdAndChangeToken=" + objectIdAndChangeToken + ", properties=" + properties
                + ", addSecondaryTypeIds=" + addSecondaryTypeIds + ", removeSecondaryTypeIds=" + removeSecondaryTypeIds
                + "]" + super.toString();
    }
}
