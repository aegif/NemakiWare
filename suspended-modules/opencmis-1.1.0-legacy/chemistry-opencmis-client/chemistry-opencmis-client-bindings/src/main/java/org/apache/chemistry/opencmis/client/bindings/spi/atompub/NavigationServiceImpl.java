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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomBase;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomElement;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomEntry;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomFeed;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomLink;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectParentDataImpl;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;

/**
 * Navigation Service AtomPub client.
 */
public class NavigationServiceImpl extends AbstractAtomPubService implements NavigationService {

    /**
     * Constructor.
     */
    public NavigationServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public ObjectInFolderList getChildren(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        ObjectInFolderListImpl result = new ObjectInFolderListImpl();

        // find the link
        String link = loadLink(repositoryId, folderId, Constants.REL_DOWN, Constants.MEDIATYPE_CHILDREN);

        if (link == null) {
            throwLinkException(repositoryId, folderId, Constants.REL_DOWN, Constants.MEDIATYPE_CHILDREN);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ORDER_BY, orderBy);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);

        // handle top level
        for (AtomElement element : feed.getElements()) {
            if (element.getObject() instanceof AtomLink) {
                if (isNextLink(element)) {
                    result.setHasMoreItems(Boolean.TRUE);
                }
            } else if (isInt(NAME_NUM_ITEMS, element)) {
                result.setNumItems((BigInteger) element.getObject());
            }
        }

        // get the children
        if (!feed.getEntries().isEmpty()) {
            result.setObjects(new ArrayList<ObjectInFolderData>(feed.getEntries().size()));

            for (AtomEntry entry : feed.getEntries()) {
                ObjectInFolderDataImpl child = null;
                String pathSegment = null;

                lockLinks();
                try {
                    // clean up cache
                    removeLinks(repositoryId, entry.getId());

                    // walk through the entry
                    for (AtomElement element : entry.getElements()) {
                        if (element.getObject() instanceof AtomLink) {
                            addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                        } else if (isStr(NAME_PATH_SEGMENT, element)) {
                            pathSegment = (String) element.getObject();
                        } else if (element.getObject() instanceof ObjectData) {
                            child = new ObjectInFolderDataImpl();
                            child.setObject((ObjectData) element.getObject());
                        }
                    }
                } finally {
                    unlockLinks();
                }

                if (child != null) {
                    child.setPathSegment(pathSegment);
                    result.getObjects().add(child);
                }
            }
        }

        return result;
    }

    @Override
    public List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        List<ObjectInFolderContainer> result = new ArrayList<ObjectInFolderContainer>();

        // find the link
        String link = loadLink(repositoryId, folderId, Constants.REL_DOWN, Constants.MEDIATYPE_DESCENDANTS);

        if (link == null) {
            throwLinkException(repositoryId, folderId, Constants.REL_DOWN, Constants.MEDIATYPE_DESCENDANTS);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_DEPTH, depth);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);

        // process tree
        addDescendantsLevel(repositoryId, feed, result);

        return result;
    }

    @Override
    public ObjectData getFolderParent(String repositoryId, String folderId, String filter, ExtensionsData extension) {
        ObjectData result = null;

        // find the link
        String link = loadLink(repositoryId, folderId, Constants.REL_UP, Constants.MEDIATYPE_ENTRY);

        if (link == null) {
            throwLinkException(repositoryId, folderId, Constants.REL_UP, Constants.MEDIATYPE_ENTRY);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_FILTER, filter);

        // read
        Response resp = read(url);

        AtomBase base = parse(resp.getStream(), AtomBase.class);

        // get the entry
        AtomEntry entry = null;
        if (base instanceof AtomFeed) {
            AtomFeed feed = (AtomFeed) base;
            if (feed.getEntries().isEmpty()) {
                throw new CmisRuntimeException("Parent feed is empty!");
            }
            entry = feed.getEntries().get(0);
        } else if (base instanceof AtomEntry) {
            entry = (AtomEntry) base;
        } else {
            throw new CmisRuntimeException("Unexpected document!");
        }

        lockLinks();
        try {
            // clean up cache
            removeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof ObjectData) {
                    result = (ObjectData) element.getObject();
                }
            }
        } finally {
            unlockLinks();
        }

        return result;
    }

    @Override
    public List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        List<ObjectInFolderContainer> result = new ArrayList<ObjectInFolderContainer>();

        // find the link
        String link = loadLink(repositoryId, folderId, Constants.REL_FOLDERTREE, Constants.MEDIATYPE_DESCENDANTS);

        if (link == null) {
            throwLinkException(repositoryId, folderId, Constants.REL_FOLDERTREE, Constants.MEDIATYPE_DESCENDANTS);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_DEPTH, depth);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_PATH_SEGMENT, includePathSegment);

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);

        // process tree
        addDescendantsLevel(repositoryId, feed, result);

        return result;
    }

    @Override
    public List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includeRelativePathSegment, ExtensionsData extension) {
        List<ObjectParentData> result = new ArrayList<ObjectParentData>();

        // find the link
        String link = loadLink(repositoryId, objectId, Constants.REL_UP, Constants.MEDIATYPE_FEED);

        if (link == null) {
            // root and unfiled objects have no UP link
            return result;
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_RELATIVE_PATH_SEGMENT, includeRelativePathSegment);

        // read and parse
        Response resp = read(url);

        AtomBase base = parse(resp.getStream(), AtomBase.class);

        if (base instanceof AtomFeed) {
            // it's a feed
            AtomFeed feed = (AtomFeed) base;

            // walk through the feed
            for (AtomEntry entry : feed.getEntries()) {
                ObjectParentDataImpl objectParent = processParentEntry(entry, repositoryId);

                if (objectParent != null) {
                    result.add(objectParent);
                }
            }
        } else if (base instanceof AtomEntry) {
            // it's an entry
            AtomEntry entry = (AtomEntry) base;

            ObjectParentDataImpl objectParent = processParentEntry(entry, repositoryId);

            if (objectParent != null) {
                result.add(objectParent);
            }
        }

        return result;
    }

    private ObjectParentDataImpl processParentEntry(AtomEntry entry, String repositoryId) {
        ObjectParentDataImpl result = null;
        String relativePathSegment = null;

        lockLinks();
        try {
            // clean up cache
            removeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof ObjectData) {
                    result = new ObjectParentDataImpl((ObjectData) element.getObject());
                } else if (is(NAME_RELATIVE_PATH_SEGMENT, element)) {
                    relativePathSegment = (String) element.getObject();
                }
            }
        } finally {
            unlockLinks();
        }

        if (result != null) {
            result.setRelativePathSegment(relativePathSegment);
        }

        return result;
    }

    @Override
    public ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        ObjectListImpl result = new ObjectListImpl();

        // find the link
        String link = loadCollection(repositoryId, Constants.COLLECTION_CHECKEDOUT);

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or checkedout collection not supported!");
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_FOLDER_ID, folderId);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ORDER_BY, orderBy);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        url.addParameter(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        url.addParameter(Constants.PARAM_RENDITION_FILTER, renditionFilter);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);

        // handle top level
        for (AtomElement element : feed.getElements()) {
            if (element.getObject() instanceof AtomLink) {
                if (isNextLink(element)) {
                    result.setHasMoreItems(Boolean.TRUE);
                }
            } else if (isInt(NAME_NUM_ITEMS, element)) {
                result.setNumItems((BigInteger) element.getObject());
            }
        }

        // get the documents
        if (!feed.getEntries().isEmpty()) {
            result.setObjects(new ArrayList<ObjectData>(feed.getEntries().size()));

            for (AtomEntry entry : feed.getEntries()) {
                ObjectData child = null;

                lockLinks();
                try {
                    // clean up cache
                    removeLinks(repositoryId, entry.getId());

                    // walk through the entry
                    for (AtomElement element : entry.getElements()) {
                        if (element.getObject() instanceof AtomLink) {
                            addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                        } else if (element.getObject() instanceof ObjectData) {
                            child = (ObjectData) element.getObject();
                        }
                    }
                } finally {
                    unlockLinks();
                }

                if (child != null) {
                    result.getObjects().add(child);
                }
            }
        }

        return result;
    }

    // ---- internal ----

    /**
     * Adds descendants level recursively.
     */
    private void addDescendantsLevel(String repositoryId, AtomFeed feed, List<ObjectInFolderContainer> containerList) {
        if (feed == null || feed.getEntries().isEmpty()) {
            return;
        }

        // walk through the feed
        for (AtomEntry entry : feed.getEntries()) {
            ObjectInFolderDataImpl objectInFolder = null;
            String pathSegment = null;
            List<ObjectInFolderContainer> childContainerList = new ArrayList<ObjectInFolderContainer>();

            lockLinks();
            try {
                // clean up cache
                removeLinks(repositoryId, entry.getId());

                // walk through the entry
                for (AtomElement element : entry.getElements()) {
                    if (element.getObject() instanceof AtomLink) {
                        addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                    } else if (element.getObject() instanceof ObjectData) {
                        objectInFolder = new ObjectInFolderDataImpl((ObjectData) element.getObject());
                    } else if (is(NAME_PATH_SEGMENT, element)) {
                        pathSegment = (String) element.getObject();
                    } else if (element.getObject() instanceof AtomFeed) {
                        addDescendantsLevel(repositoryId, (AtomFeed) element.getObject(), childContainerList);
                    }
                }
            } finally {
                unlockLinks();
            }

            if (objectInFolder != null) {
                objectInFolder.setPathSegment(pathSegment);
                ObjectInFolderContainerImpl childContainer = new ObjectInFolderContainerImpl(objectInFolder);
                childContainer.setChildren(childContainerList);
                containerList.add(childContainer);
            }
        }
    }
}
