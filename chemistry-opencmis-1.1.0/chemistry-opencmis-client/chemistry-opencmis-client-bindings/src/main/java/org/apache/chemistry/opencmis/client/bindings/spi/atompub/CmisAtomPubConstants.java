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
package org.apache.chemistry.opencmis.client.bindings.spi.atompub;

/**
 * Constants for AtomPub.
 */
public final class CmisAtomPubConstants {

    // service doc
    public static final String TAG_SERVICE = "service";
    public static final String TAG_WORKSPACE = "workspace";
    public static final String TAG_REPOSITORY_INFO = "repositoryInfo";
    public static final String TAG_COLLECTION = "collection";
    public static final String TAG_COLLECTION_TYPE = "collectionType";
    public static final String TAG_URI_TEMPLATE = "uritemplate";
    public static final String TAG_TEMPLATE_TEMPLATE = "template";
    public static final String TAG_TEMPLATE_TYPE = "type";
    public static final String TAG_LINK = "link";

    // atom
    public static final String TAG_ATOM_ID = "id";
    public static final String TAG_ATOM_TITLE = "title";
    public static final String TAG_ATOM_UPDATED = "updated";

    // feed
    public static final String TAG_FEED = "feed";

    // entry
    public static final String TAG_ENTRY = "entry";
    public static final String TAG_OBJECT = "object";
    public static final String TAG_NUM_ITEMS = "numItems";
    public static final String TAG_PATH_SEGMENT = "pathSegment";
    public static final String TAG_RELATIVE_PATH_SEGMENT = "relativePathSegment";
    public static final String TAG_TYPE = "type";
    public static final String TAG_CHILDREN = "children";
    public static final String TAG_CONTENT = "content";
    public static final String TAG_CONTENT_MEDIATYPE = "mediatype";
    public static final String TAG_CONTENT_BASE64 = "base64";
    public static final String TAG_CONTENT_FILENAME = "filename";

    public static final String ATTR_DOCUMENT_TYPE = "cmisTypeDocumentDefinitionType";
    public static final String ATTR_FOLDER_TYPE = "cmisTypeFolderDefinitionType";
    public static final String ATTR_RELATIONSHIP_TYPE = "cmisTypeRelationshipDefinitionType";
    public static final String ATTR_POLICY_TYPE = "cmisTypePolicyDefinitionType";
    public static final String ATTR_ITEM_TYPE = "cmisTypeItemDefinitionType";
    public static final String ATTR_SECONDARY_TYPE = "cmisTypeSecondaryDefinitionType";

    // allowable actions
    public static final String TAG_ALLOWABLEACTIONS = "allowableActions";

    // ACL
    public static final String TAG_ACL = "acl";

    // HTML
    public static final String TAG_HTML = "html";

    // links
    public static final String LINK_REL = "rel";
    public static final String LINK_HREF = "href";
    public static final String LINK_TYPE = "type";
    public static final String CONTENT_SRC = "src";

    private CmisAtomPubConstants() {
    }
}
