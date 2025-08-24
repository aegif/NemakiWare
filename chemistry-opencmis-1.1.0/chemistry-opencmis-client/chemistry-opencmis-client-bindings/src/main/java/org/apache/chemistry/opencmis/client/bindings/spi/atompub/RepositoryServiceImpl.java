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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomElement;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomEntry;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomFeed;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomLink;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;

/**
 * Repository Service AtomPub client.
 */
public class RepositoryServiceImpl extends AbstractAtomPubService implements RepositoryService {

    /**
     * Constructor.
     */
    public RepositoryServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension) {
        return getRepositoriesInternal(null);
    }

    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {
        List<RepositoryInfo> repositoryInfos = getRepositoriesInternal(repositoryId);

        if (repositoryInfos.isEmpty()) {
            throw new CmisObjectNotFoundException("Repository '" + repositoryId + "' not found!");
        }

        // find the repository
        for (RepositoryInfo info : repositoryInfos) {
            if (info.getId() == null) {
                continue;
            }

            if (info.getId().equals(repositoryId)) {
                return info;
            }
        }

        throw new CmisObjectNotFoundException("Repository '" + repositoryId + "' not found!");
    }

    @Override
    public TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension) {
        return getTypeDefinitionInternal(repositoryId, typeId);
    }

    @Override
    public TypeDefinitionList getTypeChildren(String repositoryId, String typeId, Boolean includePropertyDefinitions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        TypeDefinitionListImpl result = new TypeDefinitionListImpl();

        // find the link
        String link = null;
        if (typeId == null) {
            link = loadCollection(repositoryId, Constants.COLLECTION_TYPES);
        } else {
            link = loadTypeLink(repositoryId, typeId, Constants.REL_DOWN, Constants.MEDIATYPE_CHILDREN);
        }

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or type!");
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_PROPERTY_DEFINITIONS, includePropertyDefinitions);
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

        result.setList(new ArrayList<TypeDefinition>(feed.getEntries().size()));

        // get the children
        if (!feed.getEntries().isEmpty()) {
            for (AtomEntry entry : feed.getEntries()) {
                TypeDefinition child = null;

                lockTypeLinks();
                try {
                    // walk through the entry
                    for (AtomElement element : entry.getElements()) {
                        if (element.getObject() instanceof AtomLink) {
                            addTypeLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                        } else if (element.getObject() instanceof TypeDefinition) {
                            child = (TypeDefinition) element.getObject();
                        }
                    }
                } finally {
                    unlockTypeLinks();
                }

                if (child != null) {
                    result.getList().add(child);
                }
            }
        }

        return result;
    }

    @Override
    public List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension) {
        List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();

        // find the link
        String link = null;
        if (typeId == null) {
            link = loadRepositoryLink(repositoryId, Constants.REP_REL_TYPEDESC);
        } else {
            link = loadTypeLink(repositoryId, typeId, Constants.REL_DOWN, Constants.MEDIATYPE_DESCENDANTS);
        }

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or type!");
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_DEPTH, depth);
        url.addParameter(Constants.PARAM_PROPERTY_DEFINITIONS, includePropertyDefinitions);

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);

        // process tree
        addTypeDescendantsLevel(repositoryId, feed, result);

        return result;
    }

    /**
     * Adds type descendants level recursively.
     */
    private void addTypeDescendantsLevel(String repositoryId, AtomFeed feed, List<TypeDefinitionContainer> containerList) {
        if (feed == null || feed.getEntries().isEmpty()) {
            return;
        }

        // walk through the feed
        for (AtomEntry entry : feed.getEntries()) {
            TypeDefinitionContainerImpl childContainer = null;
            List<TypeDefinitionContainer> childContainerList = new ArrayList<TypeDefinitionContainer>();

            // walk through the entry
            lockTypeLinks();
            try {
                for (AtomElement element : entry.getElements()) {
                    if (element.getObject() instanceof AtomLink) {
                        addTypeLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                    } else if (element.getObject() instanceof TypeDefinition) {
                        childContainer = new TypeDefinitionContainerImpl((TypeDefinition) element.getObject());
                    } else if (element.getObject() instanceof AtomFeed) {
                        addTypeDescendantsLevel(repositoryId, (AtomFeed) element.getObject(), childContainerList);
                    }
                }
            } finally {
                unlockTypeLinks();
            }

            if (childContainer != null) {
                childContainer.setChildren(childContainerList);
                containerList.add(childContainer);
            }
        }
    }

    @Override
    public TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        if (type == null) {
            throw new CmisInvalidArgumentException("Type definition must be set!");
        }

        String parentId = type.getParentTypeId();
        if (parentId == null) {
            throw new CmisInvalidArgumentException("Type definition has no parent type ID!");
        }

        // find the link
        String link = loadTypeLink(repositoryId, parentId, Constants.REL_DOWN, Constants.MEDIATYPE_CHILDREN);

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or parent type!");
        }

        // set up writer
        final AtomEntryWriter entryWriter = new AtomEntryWriter(type, getCmisVersion(repositoryId));

        // post the new type definition
        Response resp = post(new UrlBuilder(link), Constants.MEDIATYPE_ENTRY, new Output() {
            @Override
            public void write(OutputStream out) throws XMLStreamException, IOException {
                entryWriter.write(out);
            }
        });

        // parse the response
        AtomEntry entry = parse(resp.getStream(), AtomEntry.class);

        // we expect a CMIS entry
        if (entry.getId() == null) {
            throw new CmisConnectionException("Received Atom entry is not a CMIS entry!");
        }

        lockTypeLinks();
        TypeDefinition result = null;
        try {
            // clean up cache
            removeTypeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addTypeLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof TypeDefinition) {
                    result = (TypeDefinition) element.getObject();
                }
            }
        } finally {
            unlockTypeLinks();
        }

        return result;
    }

    @Override
    public TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        if (type == null) {
            throw new CmisInvalidArgumentException("Type definition must be set!");
        }

        String typeId = type.getId();
        if (typeId == null) {
            throw new CmisInvalidArgumentException("Type definition has no type ID!");
        }

        // find the link
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put(Constants.PARAM_ID, typeId);

        String link = loadTemplateLink(repositoryId, Constants.TEMPLATE_TYPE_BY_ID, parameters);
        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or type!");
        }

        // set up writer
        final AtomEntryWriter entryWriter = new AtomEntryWriter(type, getCmisVersion(repositoryId));

        // post the new type definition
        Response resp = put(new UrlBuilder(link), Constants.MEDIATYPE_ENTRY, new Output() {
            @Override
            public void write(OutputStream out) throws XMLStreamException, IOException {
                entryWriter.write(out);
            }
        });

        // parse the response
        AtomEntry entry = parse(resp.getStream(), AtomEntry.class);

        // we expect a CMIS entry
        if (entry.getId() == null) {
            throw new CmisConnectionException("Received Atom entry is not a CMIS entry!");
        }

        lockTypeLinks();
        TypeDefinition result = null;
        try {
            // clean up cache
            removeTypeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addTypeLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof TypeDefinition) {
                    result = (TypeDefinition) element.getObject();
                }
            }
        } finally {
            unlockTypeLinks();
        }

        return result;
    }

    @Override
    public void deleteType(String repositoryId, String typeId, ExtensionsData extension) {
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put(Constants.PARAM_ID, typeId);

        String link = loadTemplateLink(repositoryId, Constants.TEMPLATE_TYPE_BY_ID, parameters);
        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        delete(new UrlBuilder(link));
    }
}
