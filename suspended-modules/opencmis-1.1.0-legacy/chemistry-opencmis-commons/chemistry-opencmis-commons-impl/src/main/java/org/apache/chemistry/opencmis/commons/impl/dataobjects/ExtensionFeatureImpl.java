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

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ExtensionFeature;

public class ExtensionFeatureImpl extends ExtensionDataImpl implements ExtensionFeature {

    private static final long serialVersionUID = 1L;

    private String id;
    private String url;
    private String commonName;
    private String versionLabel;
    private String description;
    private Map<String, String> featureData;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    @Override
    public String getVersionLabel() {
        return versionLabel;
    }

    public void setVersionLabel(String versionLabel) {
        this.versionLabel = versionLabel;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public Map<String, String> getFeatureData() {
        if (featureData == null) {
            featureData = new HashMap<String, String>(2);
        }

        return featureData;
    }

    public void setFeatureData(Map<String, String> featureData) {
        this.featureData = featureData;
    }

    @Override
    public String toString() {
        return "Extension Feature [id=" + id + ", url=" + url + ", commonName=" + commonName + ", versionLabel="
                + versionLabel + ", description=" + description + ", featureData=" + featureData + "]"
                + super.toString();
    }

}
