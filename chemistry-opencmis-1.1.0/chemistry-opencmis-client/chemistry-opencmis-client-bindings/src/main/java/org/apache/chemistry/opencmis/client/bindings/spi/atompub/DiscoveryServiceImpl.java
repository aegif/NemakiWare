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

import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomElement;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomEntry;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomFeed;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomLink;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.QueryTypeImpl;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.ExtendedHolder;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Discovery Service AtomPub client.
 */
public class DiscoveryServiceImpl extends AbstractAtomPubService implements DiscoveryService {

    /**
     * Constructor.
     */
    public DiscoveryServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public ObjectList getContentChanges(String repositoryId, Holder<String> changeLogToken, Boolean includeProperties,
            String filter, Boolean includePolicyIds, Boolean includeACL, BigInteger maxItems, ExtensionsData extension) {
        ObjectListImpl result = new ObjectListImpl();

        // find the link
        String link = null;
        UrlBuilder url = null;

        // if the application doesn't know the change log token but the link to
        // the next Atom feed
        if (changeLogToken instanceof ExtendedHolder && changeLogToken.getValue() == null) {
            link = (String) ((ExtendedHolder<String>) changeLogToken).getExtraValue(Constants.REP_REL_CHANGES);
            if (link != null) {
                url = new UrlBuilder(link);
            }
        }

        // if the application didn't provide a link to next Atom feed
        if (link == null) {
            link = loadRepositoryLink(repositoryId, Constants.REP_REL_CHANGES);
            if (link != null) {
                url = new UrlBuilder(link);
                url.addParameter(Constants.PARAM_CHANGE_LOG_TOKEN,
                        (changeLogToken == null ? null : changeLogToken.getValue()));
                url.addParameter(Constants.PARAM_PROPERTIES, includeProperties);
                url.addParameter(Constants.PARAM_FILTER, filter);
                url.addParameter(Constants.PARAM_POLICY_IDS, includePolicyIds);
                url.addParameter(Constants.PARAM_ACL, includeACL);
                url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
            }
        }

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or content changes not supported!");
        }

        // read and parse
        Response resp = read(url);
        AtomFeed feed = parse(resp.getStream(), AtomFeed.class);
        String lastChangeLogToken = null;

        // handle top level
        String nextLink = null;
        for (AtomElement element : feed.getElements()) {
            if (element.getObject() instanceof AtomLink) {
                if (isNextLink(element)) {
                    result.setHasMoreItems(Boolean.TRUE);
                    nextLink = ((AtomLink) element.getObject()).getHref();
                }
            } else if (isInt(NAME_NUM_ITEMS, element)) {
                result.setNumItems((BigInteger) element.getObject());
            } else if (isStr("changeLogToken", element)) {
                lastChangeLogToken = (String) element.getObject();
            }
        }

        // get the changes
        if (!feed.getEntries().isEmpty()) {
            result.setObjects(new ArrayList<ObjectData>(feed.getEntries().size()));

            for (AtomEntry entry : feed.getEntries()) {
                ObjectData hit = null;

                // walk through the entry
                for (AtomElement element : entry.getElements()) {
                    if (element.getObject() instanceof ObjectData) {
                        hit = (ObjectData) element.getObject();
                    }
                }

                if (hit != null) {
                    result.getObjects().add(hit);
                }
            }
        }

        if (changeLogToken != null) {
            // the AtomPub binding cannot return a new change log token,
            // but an OpenCMIS server uses a proprietary tag
            changeLogToken.setValue(lastChangeLogToken);

            // but we can provide the link to the next Atom feed
            if (changeLogToken instanceof ExtendedHolder && nextLink != null) {
                ((ExtendedHolder<String>) changeLogToken).setExtraValue(Constants.REP_REL_CHANGES, nextLink);
            }
        }

        return result;
    }

    @Override
    public ObjectList query(String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        ObjectListImpl result = new ObjectListImpl();

        // find the link
        String link = loadCollection(repositoryId, Constants.COLLECTION_QUERY);

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or query not supported!");
        }

        UrlBuilder url = new UrlBuilder(link);

        // compile query request
        final QueryTypeImpl query = new QueryTypeImpl();
        query.setStatement(statement);
        query.setSearchAllVersions(searchAllVersions);
        query.setIncludeAllowableActions(includeAllowableActions);
        query.setIncludeRelationships(includeRelationships);
        query.setRenditionFilter(renditionFilter);
        query.setMaxItems(maxItems);
        query.setSkipCount(skipCount);

        final CmisVersion cmisVersion = getCmisVersion(repositoryId);

        // post the query and parse results
        Response resp = post(url, Constants.MEDIATYPE_QUERY, new Output() {
            @Override
            public void write(OutputStream out) throws Exception {
                XMLStreamWriter writer = XMLUtils.createWriter(out);
                XMLUtils.startXmlDocument(writer);
                XMLConverter.writeQuery(writer, cmisVersion, query);
                XMLUtils.endXmlDocument(writer);
            }
        });
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

        // get the result set
        if (!feed.getEntries().isEmpty()) {
            result.setObjects(new ArrayList<ObjectData>(feed.getEntries().size()));

            for (AtomEntry entry : feed.getEntries()) {
                ObjectData hit = null;

                // walk through the entry
                for (AtomElement element : entry.getElements()) {
                    if (element.getObject() instanceof ObjectData) {
                        hit = (ObjectData) element.getObject();
                    }
                }

                if (hit != null) {
                    result.getObjects().add(hit);
                }
            }
        }

        return result;
    }
}
