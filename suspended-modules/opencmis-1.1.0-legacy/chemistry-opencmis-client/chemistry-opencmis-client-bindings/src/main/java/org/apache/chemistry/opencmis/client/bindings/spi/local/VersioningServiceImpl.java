/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.chemistry.opencmis.client.bindings.spi.local;

import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

public class VersioningServiceImpl extends AbstractLocalService implements VersioningService {

    /**
     * Constructor.
     */
    public VersioningServiceImpl(BindingSession session, CmisServiceFactory factory) {
        setSession(session);
        setServiceFactory(factory);
    }

    @Override
    public void cancelCheckOut(String repositoryId, String objectId, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.cancelCheckOut(repositoryId, objectId, extension);

            if (stopAfterService(service)) {
                return;
            }

        } finally {
            service.close();
        }
    }

    @Override
    public void checkIn(String repositoryId, Holder<String> objectId, Boolean major, Properties properties,
            ContentStream contentStream, String checkinComment, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.checkIn(repositoryId, objectId, major, properties, contentStream, checkinComment, policies,
                    addAces, removeAces, extension);

            if (stopAfterService(service)) {
                return;
            }
        } finally {
            service.close();
        }
    }

    @Override
    public void checkOut(String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.checkOut(repositoryId, objectId, extension, contentCopied);

            if (stopAfterService(service)) {
                return;
            }
        } finally {
            service.close();
        }
    }

    @Override
    public List<ObjectData> getAllVersions(String repositoryId, String objectId, String versionSeriesId, String filter,
            Boolean includeAllowableActions, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            List<ObjectData> serviceResult = service.getAllVersions(repositoryId, objectId, versionSeriesId, filter,
                    includeAllowableActions, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public ObjectData getObjectOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ObjectData serviceResult = service.getObjectOfLatestVersion(repositoryId, objectId, versionSeriesId, major,
                    filter, includeAllowableActions, includeRelationships, renditionFilter, includePolicyIds,
                    includeAcl, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public Properties getPropertiesOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            Properties serviceResult = service.getPropertiesOfLatestVersion(repositoryId, objectId, versionSeriesId,
                    major, filter, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }
}
