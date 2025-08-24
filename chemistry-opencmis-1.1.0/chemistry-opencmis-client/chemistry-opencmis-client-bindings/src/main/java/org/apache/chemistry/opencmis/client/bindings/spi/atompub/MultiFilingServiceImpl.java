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

import javax.xml.stream.XMLStreamException;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.MultiFilingService;

/**
 * MultiFiling Service AtomPub client.
 */
public class MultiFilingServiceImpl extends AbstractAtomPubService implements MultiFilingService {

    /**
     * Constructor.
     */
    public MultiFilingServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public void addObjectToFolder(String repositoryId, String objectId, String folderId, Boolean allVersions,
            ExtensionsData extension) {
        if (objectId == null) {
            throw new CmisInvalidArgumentException("Object id must be set!");
        }

        // find the link
        String link = loadLink(repositoryId, folderId, Constants.REL_DOWN, Constants.MEDIATYPE_CHILDREN);

        if (link == null) {
            throwLinkException(repositoryId, folderId, Constants.REL_DOWN, Constants.MEDIATYPE_CHILDREN);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_ALL_VERSIONS, allVersions);

        // set up object and writer
        final AtomEntryWriter entryWriter = new AtomEntryWriter(createIdObject(objectId), getCmisVersion(repositoryId));

        // post addObjectToFolder request
        postAndConsume(url, Constants.MEDIATYPE_ENTRY, new Output() {
            @Override
            public void write(OutputStream out) throws XMLStreamException, IOException {
                entryWriter.write(out);
            }
        });
    }

    @Override
    public void removeObjectFromFolder(String repositoryId, String objectId, String folderId, ExtensionsData extension) {
        if (objectId == null) {
            throw new CmisInvalidArgumentException("Object id must be set!");
        }

        // find the link
        String link = loadCollection(repositoryId, Constants.COLLECTION_UNFILED);

        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository or unfiling not supported!");
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_REMOVE_FROM, folderId);

        // set up object and writer
        final AtomEntryWriter entryWriter = new AtomEntryWriter(createIdObject(objectId), getCmisVersion(repositoryId));

        // post removeObjectFromFolder request
        postAndConsume(url, Constants.MEDIATYPE_ENTRY, new Output() {
            @Override
            public void write(OutputStream out) throws XMLStreamException, IOException {
                entryWriter.write(out);
            }
        });
    }
}
