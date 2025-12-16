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
package org.apache.chemistry.opencmis.server.impl.atompub;

import java.math.BigInteger;
import java.util.GregorianCalendar;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.ObjectInfo;
import org.apache.chemistry.opencmis.commons.server.RenditionInfo;

/**
 * Navigation Service operations.
 */
public class NavigationService {

    /**
     * Children Collection GET.
     */
    public static class GetChildren extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_ID);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            String orderBy = getStringParameter(request, Constants.PARAM_ORDER_BY);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            Boolean includePathSegment = getBooleanParameter(request, Constants.PARAM_PATH_SEGMENT);
            BigInteger maxItems = getBigIntegerParameter(request, Constants.PARAM_MAX_ITEMS);
            BigInteger skipCount = getBigIntegerParameter(request, Constants.PARAM_SKIP_COUNT);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectInFolderList children = service.getChildren(repositoryId, folderId, filter, orderBy,
                    includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, maxItems,
                    skipCount, null);

            if (stopAfterService(service)) {
                return;
            }

            if (children == null) {
                throw new CmisRuntimeException("Children are null!");
            }

            ObjectInfo folderInfo = service.getObjectInfo(repositoryId, folderId);
            if (folderInfo == null) {
                throw new CmisRuntimeException("Folder Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(folderInfo.getId(), folderInfo.getAtomId(), folderInfo.getCreatedBy(),
                    folderInfo.getName(), folderInfo.getLastModificationDate(), null, children.getNumItems());

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_CHILDREN, folderInfo.getId());
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            selfLink.addParameter(Constants.PARAM_ORDER_BY, orderBy);
            selfLink.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            selfLink.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            selfLink.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
            selfLink.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
            selfLink.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
            selfLink.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);
            feed.writeSelfLink(selfLink.toString(), null);

            feed.writeDescribedByLink(compileUrl(baseUrl, RESOURCE_TYPE, folderInfo.getTypeId()));

            feed.writeAllowableActionsLink(compileUrl(baseUrl, RESOURCE_ALLOWABLEACIONS, folderInfo.getId()));

            feed.writeDownLink(compileUrl(baseUrl, RESOURCE_CHILDREN, folderInfo.getId()), Constants.MEDIATYPE_FEED);

            if (folderInfo.supportsDescendants()) {
                feed.writeDownLink(compileUrl(baseUrl, RESOURCE_DESCENDANTS, folderInfo.getId()),
                        Constants.MEDIATYPE_DESCENDANTS);
            }

            if (folderInfo.supportsFolderTree()) {
                feed.writeFolderTreeLink(compileUrl(baseUrl, RESOURCE_FOLDERTREE, folderInfo.getId()));
            }

            if (folderInfo.hasParent()) {
                feed.writeUpLink(compileUrl(baseUrl, RESOURCE_PARENTS, folderInfo.getId()), Constants.MEDIATYPE_FEED);
            }

            if (folderInfo.getRenditionInfos() != null) {
                for (RenditionInfo ri : folderInfo.getRenditionInfos()) {
                    feed.writeAlternateLink(compileUrl(baseUrl, RESOURCE_CONTENT, ri.getId()), ri.getContenType(),
                            ri.getKind(), ri.getTitle(), ri.getLength());
                }
            }

            if (folderInfo.hasAcl()) {
                feed.writeAclLink(compileUrl(baseUrl, RESOURCE_ACL, folderInfo.getId()));
            }

            if (folderInfo.supportsPolicies()) {
                feed.writePoliciesLink(compileUrl(baseUrl, RESOURCE_POLICIES, folderInfo.getId()));
            }

            if (folderInfo.supportsRelationships()) {
                feed.writeRelationshipsLink(compileUrl(baseUrl, RESOURCE_RELATIONSHIPS, folderInfo.getId()));
            }

            UrlBuilder pagingUrl = new UrlBuilder(compileUrlBuilder(baseUrl, RESOURCE_CHILDREN, folderInfo.getId()));
            pagingUrl.addParameter(Constants.PARAM_FILTER, filter);
            pagingUrl.addParameter(Constants.PARAM_ORDER_BY, orderBy);
            pagingUrl.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            pagingUrl.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            pagingUrl.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
            pagingUrl.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
            feed.writePagingLinks(pagingUrl, maxItems, skipCount, children.getNumItems(), children.hasMoreItems(),
                    PAGE_SIZE);

            // write collection
            feed.writeCollection(compileUrl(baseUrl, RESOURCE_CHILDREN, folderInfo.getId()), null, "Folder collection",
                    Constants.MEDIATYPE_CMISATOM);

            // write entries
            if (children.getObjects() != null) {
                AtomEntry entry = new AtomEntry(feed.getWriter());
                for (ObjectInFolderData object : children.getObjects()) {
                    if ((object == null) || (object.getObject() == null)) {
                        continue;
                    }
                    writeObjectEntry(service, entry, object.getObject(), null, repositoryId, object.getPathSegment(),
                            null, baseUrl, false, context.getCmisVersion());
                }
            }

            // write extensions
            feed.writeExtensions(children);

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }

    /**
     * Descendants feed GET.
     */
    public static class GetDescendants extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_ID);
            BigInteger depth = getBigIntegerParameter(request, Constants.PARAM_DEPTH);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            Boolean includePathSegment = getBooleanParameter(request, Constants.PARAM_PATH_SEGMENT);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<ObjectInFolderContainer> descendants = service.getDescendants(repositoryId, folderId, depth, filter,
                    includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, null);

            if (stopAfterService(service)) {
                return;
            }

            if (descendants == null) {
                throw new CmisRuntimeException("Descendants are null!");
            }

            ObjectInfo folderInfo = service.getObjectInfo(repositoryId, folderId);
            if (folderInfo == null) {
                throw new CmisRuntimeException("Folder Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(folderInfo.getId(), folderInfo.getAtomId(), folderInfo.getCreatedBy(),
                    folderInfo.getName(), folderInfo.getLastModificationDate(), null, null);

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_DESCENDANTS, folderInfo.getId());
            selfLink.addParameter(Constants.PARAM_DEPTH, depth);
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            selfLink.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            selfLink.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            selfLink.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
            selfLink.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
            feed.writeSelfLink(selfLink.toString(), null);

            feed.writeViaLink(compileUrl(baseUrl, RESOURCE_ENTRY, folderInfo.getId()));

            feed.writeDownLink(compileUrl(baseUrl, RESOURCE_CHILDREN, folderInfo.getId()), Constants.MEDIATYPE_FEED);

            if (folderInfo.supportsFolderTree()) {
                feed.writeFolderTreeLink(compileUrl(baseUrl, RESOURCE_FOLDERTREE, folderInfo.getId()));
            }

            if (folderInfo.hasParent()) {
                feed.writeUpLink(compileUrl(baseUrl, RESOURCE_PARENTS, folderInfo.getId()), Constants.MEDIATYPE_FEED);
            }

            // write entries
            AtomEntry entry = new AtomEntry(feed.getWriter());
            for (ObjectInFolderContainer container : descendants) {
                if ((container == null) || (container.getObject() == null)
                        || (container.getObject().getObject() == null)) {
                    continue;
                }
                writeObjectEntry(service, entry, container.getObject().getObject(), container.getChildren(),
                        repositoryId, container.getObject().getPathSegment(), null, baseUrl, false,
                        context.getCmisVersion());
            }

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }

    /**
     * Folder tree feed GET.
     */
    public static class GetFolderTree extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_ID);
            BigInteger depth = getBigIntegerParameter(request, Constants.PARAM_DEPTH);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            Boolean includePathSegment = getBooleanParameter(request, Constants.PARAM_PATH_SEGMENT);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<ObjectInFolderContainer> folderTree = service.getFolderTree(repositoryId, folderId, depth, filter,
                    includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, null);

            if (stopAfterService(service)) {
                return;
            }

            if (folderTree == null) {
                throw new CmisRuntimeException("Folder tree is null!");
            }

            ObjectInfo folderInfo = service.getObjectInfo(repositoryId, folderId);
            if (folderInfo == null) {
                throw new CmisRuntimeException("Folder Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(folderInfo.getId(), folderInfo.getAtomId(), folderInfo.getCreatedBy(),
                    folderInfo.getName(), folderInfo.getLastModificationDate(), null, null);

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_FOLDERTREE, folderInfo.getId());
            selfLink.addParameter(Constants.PARAM_DEPTH, depth);
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            selfLink.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            selfLink.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            selfLink.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
            selfLink.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
            feed.writeSelfLink(selfLink.toString(), null);

            feed.writeViaLink(compileUrl(baseUrl, RESOURCE_ENTRY, folderInfo.getId()));

            feed.writeDownLink(compileUrl(baseUrl, RESOURCE_CHILDREN, folderInfo.getId()), Constants.MEDIATYPE_FEED);

            if (folderInfo.supportsDescendants()) {
                feed.writeDownLink(compileUrl(baseUrl, RESOURCE_DESCENDANTS, folderInfo.getId()),
                        Constants.MEDIATYPE_DESCENDANTS);
            }

            if (folderInfo.hasParent()) {
                feed.writeUpLink(compileUrl(baseUrl, RESOURCE_PARENTS, folderInfo.getId()), Constants.MEDIATYPE_FEED);
            }

            // write entries
            AtomEntry entry = new AtomEntry(feed.getWriter());
            for (ObjectInFolderContainer container : folderTree) {
                if ((container == null) || (container.getObject() == null)
                        || (container.getObject().getObject() == null)) {
                    continue;
                }
                writeObjectEntry(service, entry, container.getObject().getObject(), container.getChildren(),
                        repositoryId, container.getObject().getPathSegment(), null, baseUrl, false,
                        context.getCmisVersion());
            }

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }

    /**
     * Object parents feed GET.
     */
    public static class GetFolderParent extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_ID);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectData object = service.getFolderParent(repositoryId, folderId, filter, null);

            if (stopAfterService(service)) {
                return;
            }

            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, folderId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_ENTRY);

            // write XML
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }
    }

    /**
     * Object parents feed GET.
     */
    public static class GetObjectParents extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            Boolean includeRelativePathSegment = getBooleanParameter(request, Constants.PARAM_RELATIVE_PATH_SEGMENT);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            List<ObjectParentData> parents = service.getObjectParents(repositoryId, objectId, filter,
                    includeAllowableActions, includeRelationships, renditionFilter, includeRelativePathSegment, null);

            if (stopAfterService(service)) {
                return;
            }

            if (parents == null) {
                throw new CmisRuntimeException("Parents are null!");
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, objectId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(objectInfo.getId(), objectInfo.getAtomId(), objectInfo.getCreatedBy(),
                    objectInfo.getName(), objectInfo.getLastModificationDate(), null, null);

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_PARENTS, objectInfo.getId());
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            selfLink.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            selfLink.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            selfLink.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
            selfLink.addParameter(Constants.PARAM_RELATIVE_PATH_SEGMENT, includeRelativePathSegment);
            feed.writeSelfLink(selfLink.toString(), null);

            // write entries
            AtomEntry entry = new AtomEntry(feed.getWriter());
            for (ObjectParentData object : parents) {
                if ((object == null) || (object.getObject() == null)) {
                    continue;
                }
                writeObjectEntry(service, entry, object.getObject(), null, repositoryId, null,
                        object.getRelativePathSegment(), baseUrl, false, context.getCmisVersion());
            }

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }

    /**
     * Checked Out Collection GET.
     */
    public static class GetCheckedOutDocs extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_FOLDER_ID);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            String orderBy = getStringParameter(request, Constants.PARAM_ORDER_BY);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            BigInteger maxItems = getBigIntegerParameter(request, Constants.PARAM_MAX_ITEMS);
            BigInteger skipCount = getBigIntegerParameter(request, Constants.PARAM_SKIP_COUNT);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectList checkedOut = service.getCheckedOutDocs(repositoryId, folderId, filter, orderBy,
                    includeAllowableActions, includeRelationships, renditionFilter, maxItems, skipCount, null);

            if (stopAfterService(service)) {
                return;
            }

            if (checkedOut == null) {
                throw new CmisRuntimeException("Checked Out list is null!");
            }

            ObjectInfo folderInfo = null;
            if (folderId != null) {
                folderInfo = service.getObjectInfo(repositoryId, folderId);
                if (folderInfo == null) {
                    throw new CmisRuntimeException("Folder Object Info is missing!");
                }
            } else {
                folderInfo = new ObjectInfoImpl();
                GregorianCalendar now = new GregorianCalendar();

                ((ObjectInfoImpl) folderInfo).setId("uri:x-checkedout");
                ((ObjectInfoImpl) folderInfo).setName("Checked Out");
                ((ObjectInfoImpl) folderInfo).setCreatedBy("");
                ((ObjectInfoImpl) folderInfo).setCreationDate(now);
                ((ObjectInfoImpl) folderInfo).setLastModificationDate(now);
                ((ObjectInfoImpl) folderInfo).setHasParent(false);
                ((ObjectInfoImpl) folderInfo).setSupportsDescendants(false);
                ((ObjectInfoImpl) folderInfo).setSupportsFolderTree(false);
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(folderInfo.getId(), folderInfo.getAtomId(), folderInfo.getCreatedBy(),
                    folderInfo.getName(), folderInfo.getLastModificationDate(), null, checkedOut.getNumItems());

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_CHECKEDOUT, folderInfo.getId());
            selfLink.addParameter(Constants.PARAM_FILTER, filter);
            selfLink.addParameter(Constants.PARAM_ORDER_BY, orderBy);
            selfLink.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            selfLink.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            selfLink.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
            selfLink.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);
            feed.writeSelfLink(selfLink.toString(), null);

            UrlBuilder pagingUrl = compileUrlBuilder(baseUrl, RESOURCE_CHECKEDOUT, folderInfo.getId());
            pagingUrl.addParameter(Constants.PARAM_FILTER, filter);
            pagingUrl.addParameter(Constants.PARAM_ORDER_BY, orderBy);
            pagingUrl.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
            pagingUrl.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
            pagingUrl.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
            feed.writePagingLinks(pagingUrl, maxItems, skipCount, checkedOut.getNumItems(), checkedOut.hasMoreItems(),
                    PAGE_SIZE);

            // write entries
            if (checkedOut.getObjects() != null) {
                AtomEntry entry = new AtomEntry(feed.getWriter());
                for (ObjectData object : checkedOut.getObjects()) {
                    if (object == null) {
                        continue;
                    }
                    writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, false,
                            context.getCmisVersion());
                }
            }

            // write extensions
            feed.writeExtensions(checkedOut);

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }
}
