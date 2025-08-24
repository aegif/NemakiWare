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
package org.apache.chemistry.opencmis.commons;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ExtensionFeature;

public final class ExtensionFeatures {

    public static final ExtensionFeature EXTENDED_DATETIME_FORMAT = new ExtensionFeature() {
        private static final long serialVersionUID = 1L;

        @Override
        public String getId() {
            return "http://docs.oasis-open.org/ns/cmis/extension/datetimeformat";
        }

        @Override
        public String getUrl() {
            return "https://www.oasis-open.org/committees/tc_home.php?wg_abbrev=cmis";
        }

        @Override
        public String getCommonName() {
            return "Browser Binding DateTime Format";
        }

        @Override
        public String getVersionLabel() {
            return "1.0";
        }

        @Override
        public String getDescription() {
            return "Adds an additional DateTime format for the Browser Binding.";
        }

        @Override
        public Map<String, String> getFeatureData() {
            return null;
        }

        @Override
        public void setExtensions(List<CmisExtensionElement> extensions) {
        }

        @Override
        public List<CmisExtensionElement> getExtensions() {
            return null;
        }
    };

    public static final ExtensionFeature CONTENT_STREAM_HASH = new ExtensionFeature() {
        private static final long serialVersionUID = 1L;

        @Override
        public String getId() {
            return "http://docs.oasis-open.org/ns/cmis/extension/contentstreamhash";
        }

        @Override
        public String getUrl() {
            return "https://www.oasis-open.org/committees/tc_home.php?wg_abbrev=cmis";
        }

        @Override
        public String getCommonName() {
            return "Content Stream Hash";
        }

        @Override
        public String getVersionLabel() {
            return "1.0";
        }

        @Override
        public String getDescription() {
            return "Adds the property cmis:contentStreamHash, which represents the hash of the document content.";
        }

        @Override
        public Map<String, String> getFeatureData() {
            return null;
        }

        @Override
        public void setExtensions(List<CmisExtensionElement> extensions) {
        }

        @Override
        public List<CmisExtensionElement> getExtensions() {
            return null;
        }
    };

    public static final ExtensionFeature LATEST_ACCESSIBLE_STATE = new ExtensionFeature() {
        private static final long serialVersionUID = 1L;

        @Override
        public String getId() {
            return "http://docs.oasis-open.org/ns/cmis/extension/latestAccessibleState/1.1";
        }

        @Override
        public String getUrl() {
            return "https://www.oasis-open.org/committees/tc_home.php?wg_abbrev=cmis";
        }

        @Override
        public String getCommonName() {
            return "Latest Accessible State";
        }

        @Override
        public String getVersionLabel() {
            return "1.1";
        }

        @Override
        public String getDescription() {
            return "This extension provides for an identifier of each cmis:document that retrieves "
                    + "the latest accessible state of the document whether the document is versioned or not.";
        }

        @Override
        public Map<String, String> getFeatureData() {
            return null;
        }

        @Override
        public void setExtensions(List<CmisExtensionElement> extensions) {
        }

        @Override
        public List<CmisExtensionElement> getExtensions() {
            return null;
        }
    };
}
