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
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomElement;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomEntry;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomFeed;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomLink;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.ReturnVersion;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

/**
 * Versioning Service AtomPub client.
 */
public class VersioningServiceImpl extends AbstractAtomPubService implements VersioningService {

    /**
     * Constructor.
     */
    public VersioningServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public void checkOut(String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied) {
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object id must be set!");
        }

        // find the link
        String link = loadCollection(repositoryId, Constants.COLLECTION_CHECKEDOUT);

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or checkedout collection not supported!");
        }

        UrlBuilder url = new UrlBuilder(link);

        // workaround for SharePoint 2010 - see CMIS-362
        if (getSession().get(SessionParameter.INCLUDE_OBJECTID_URL_PARAM_ON_CHECKOUT, false)) {
            url.addParameter("objectId", objectId.getValue());
        }

        // set up object and writer
        final AtomEntryWriter entryWriter = new AtomEntryWriter(createIdObject(objectId.getValue()),
                getCmisVersion(repositoryId));

        // post move request
        Response resp = post(url, Constants.MEDIATYPE_ENTRY, new Output() {
            @Override
            public void write(OutputStream out) throws XMLStreamException, IOException {
                entryWriter.write(out);
            }
        });

        // parse the response
        AtomEntry entry = parse(resp.getStream(), AtomEntry.class);

        objectId.setValue(entry.getId());

        lockLinks();
        try {
            // clean up cache
            removeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                }
            }
        } finally {
            unlockLinks();
        }

        if (contentCopied != null) {
            contentCopied.setValue(null);
        }
    }

    @Override
    public void cancelCheckOut(String repositoryId, String objectId, ExtensionsData extension) {
        // find the link
        String link = loadLink(repositoryId, objectId, Constants.REL_SELF, Constants.MEDIATYPE_ENTRY);

        if (link == null) {
            throwLinkException(repositoryId, objectId, Constants.REL_SELF, Constants.MEDIATYPE_ENTRY);
        }

        // prefer working copy link if available
        // (workaround for non-compliant repositories)
        String wcLink = getLink(repositoryId, objectId, Constants.REL_WORKINGCOPY, Constants.MEDIATYPE_ENTRY);
        if (wcLink != null) {
            link = wcLink;
        }

        delete(new UrlBuilder(link));
    }

    @Override
    public void checkIn(String repositoryId, Holder<String> objectId, Boolean major, Properties properties,
            ContentStream contentStream, String checkinComment, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        // we need an object id
        if ((objectId == null) || (objectId.getValue() == null) || (objectId.getValue().length() == 0)) {
            throw new CmisInvalidArgumentException("Object id must be set!");
        }

        // find the link
        String link = loadLink(repositoryId, objectId.getValue(), Constants.REL_SELF, Constants.MEDIATYPE_ENTRY);

        if (link == null) {
            throwLinkException(repositoryId, objectId.getValue(), Constants.REL_SELF, Constants.MEDIATYPE_ENTRY);
        }

        // prefer working copy link if available
        // (workaround for non-compliant repositories)
        String wcLink = getLink(repositoryId, objectId.getValue(), Constants.REL_WORKINGCOPY, Constants.MEDIATYPE_ENTRY);
        if (wcLink != null) {
            link = wcLink;
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_CHECKIN_COMMENT, checkinComment);
        url.addParameter(Constants.PARAM_MAJOR, major);
        url.addParameter(Constants.PARAM_CHECK_IN, "true");

        // workaround for SharePoint - check in without property change
        if (getSession().get(SessionParameter.ADD_NAME_ON_CHECK_IN, false)) {
            if (properties == null || properties.getPropertyList().isEmpty()) {
                properties = new PropertiesImpl();

                try {
                    String name = null;

                    // fetch the current name
                    ObjectData obj = getObjectInternal(repositoryId, IdentifierType.ID, objectId.getValue(),
                            ReturnVersion.THIS, "cmis:objectId,cmis:name", Boolean.FALSE, IncludeRelationships.NONE,
                            "cmis:none", Boolean.FALSE, Boolean.FALSE, null);

                    if (obj != null && obj.getProperties() != null && obj.getProperties().getProperties() != null
                            && obj.getProperties().getProperties().get(PropertyIds.NAME) != null) {
                        PropertyData<?> nameProp = obj.getProperties().getProperties().get(PropertyIds.NAME);
                        if (nameProp.getFirstValue() instanceof String) {
                            name = (String) nameProp.getFirstValue();
                        }
                    }

                    if (name == null) {
                        throw new CmisRuntimeException("Could not determine the name of the PWC!");
                    }

                    // set the document name to the same value - silly, but
                    // SharePoint requires that at least one property value has
                    // to be changed and the name is the only reliable property
                    ((PropertiesImpl) properties).addProperty(new PropertyStringImpl(PropertyIds.NAME, name));
                } catch (CmisBaseException e) {
                    throw new CmisRuntimeException("Could not determine the name of the PWC: " + e.toString(), e);
                }
            }
        }

        // set up writer
        final AtomEntryWriter entryWriter = new AtomEntryWriter(createObject(properties, null, policies),
                getCmisVersion(repositoryId), contentStream);

        // update
        Response resp = put(url, Constants.MEDIATYPE_ENTRY, new Output() {
            @Override
            public void write(OutputStream out) throws XMLStreamException, IOException {
                entryWriter.write(out);
            }
        });

        // parse new entry
        AtomEntry entry = parse(resp.getStream(), AtomEntry.class);

        // we expect a CMIS entry
        if (entry.getId() == null) {
            throw new CmisConnectionException("Received Atom entry is not a CMIS entry!");
        }

        // set object id
        objectId.setValue(entry.getId());

        AccessControlListImpl originalAces = null;

        lockLinks();
        try {
            // clean up cache
            removeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof ObjectData) {
                    // extract current ACL
                    ObjectData object = (ObjectData) element.getObject();
                    if (object.getAcl() != null) {
                        originalAces = new AccessControlListImpl(object.getAcl().getAces());
                        originalAces.setExact(object.isExactAcl());
                    }
                }
            }
        } finally {
            unlockLinks();
        }

        // handle ACL modifications
        if ((originalAces != null) && (isAclMergeRequired(addAces, removeAces))) {
            // merge and update ACL
            Acl newACL = mergeAcls(originalAces, addAces, removeAces);
            if (newACL != null) {
                updateAcl(repositoryId, entry.getId(), newACL, null);
            }
        }
    }

    @Override
    public List<ObjectData> getAllVersions(String repositoryId, String objectId, String versionSeriesId, String filter,
            Boolean includeAllowableActions, ExtensionsData extension) {
        List<ObjectData> result = new ArrayList<ObjectData>();

        // find the link
        String link = loadLink(repositoryId, objectId, Constants.REL_VERSIONHISTORY, Constants.MEDIATYPE_FEED);

        if (link == null) {
            throwLinkException(repositoryId, objectId, Constants.REL_VERSIONHISTORY, Constants.MEDIATYPE_FEED);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_FILTER, filter);
        url.addParameter(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);

        // get the versions
        if (!feed.getEntries().isEmpty()) {
            for (AtomEntry entry : feed.getEntries()) {
                ObjectData version = null;

                lockLinks();
                try {
                    // clean up cache
                    removeLinks(repositoryId, entry.getId());

                    // walk through the entry
                    for (AtomElement element : entry.getElements()) {
                        if (element.getObject() instanceof AtomLink) {
                            addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                        } else if (element.getObject() instanceof ObjectData) {
                            version = (ObjectData) element.getObject();
                        }
                    }
                } finally {
                    unlockLinks();
                }

                if (version != null) {
                    result.add(version);
                }
            }
        }

        return result;
    }

    @Override
    public ObjectData getObjectOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeACL, ExtensionsData extension) {

        ReturnVersion returnVersion = ReturnVersion.LATEST;
        if ((major != null) && (major.booleanValue())) {
            returnVersion = ReturnVersion.LASTESTMAJOR;
        }

        // workaround for SharePoint - use the version series ID instead of the
        // object ID
        if (getSession().get(SessionParameter.LATEST_VERSION_WITH_VERSION_SERIES_ID, false)) {
            if (versionSeriesId != null) {
                objectId = versionSeriesId;
            } else {
                ObjectData obj = getObjectInternal(repositoryId, IdentifierType.ID, objectId, null,
                        PropertyIds.OBJECT_ID + "," + PropertyIds.VERSION_SERIES_ID, Boolean.FALSE,
                        IncludeRelationships.NONE, "cmis:none", Boolean.FALSE, Boolean.FALSE, extension);

                if (obj.getProperties() != null && obj.getProperties().getProperties() != null) {
                    PropertyData<?> versionSeriesProp = obj.getProperties().getProperties()
                            .get(PropertyIds.VERSION_SERIES_ID);
                    if (versionSeriesProp != null && versionSeriesProp.getFirstValue() instanceof String) {
                        objectId = (String) versionSeriesProp.getFirstValue();
                    }
                }
            }
        }

        return getObjectInternal(repositoryId, IdentifierType.ID, objectId, returnVersion, filter,
                includeAllowableActions, includeRelationships, renditionFilter, includePolicyIds, includeACL, extension);
    }

    @Override
    public Properties getPropertiesOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, ExtensionsData extension) {
        return getObjectOfLatestVersion(repositoryId, objectId, versionSeriesId, major, filter, Boolean.FALSE,
                IncludeRelationships.NONE, "cmis:none", Boolean.FALSE, Boolean.FALSE, extension).getProperties();
    }
}
