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

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;

public class ObjectServiceImpl extends AbstractLocalService implements ObjectService {

    /**
     * Constructor.
     */
    public ObjectServiceImpl(BindingSession session, CmisServiceFactory factory) {
        setSession(session);
        setServiceFactory(factory);
    }

    @Override
    public String createDocument(String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            String serviceResult = service.createDocument(repositoryId, properties, folderId, contentStream,
                    versioningState, policies, addAces, removeAces, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public String createDocumentFromSource(String repositoryId, String sourceId, Properties properties,
            String folderId, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            String serviceResult = service.createDocumentFromSource(repositoryId, sourceId, properties, folderId,
                    versioningState, policies, addAces, removeAces, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            String serviceResult = service.createFolder(repositoryId, properties, folderId, policies, addAces,
                    removeAces, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public String createPolicy(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            String serviceResult = service.createPolicy(repositoryId, properties, folderId, policies, addAces,
                    removeAces, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public String createItem(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }
            String serviceResult = service.createItem(repositoryId, properties, folderId, policies, addAces,
                    removeAces, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public String createRelationship(String repositoryId, Properties properties, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            String serviceResult = service.createRelationship(repositoryId, properties, policies, addAces, removeAces,
                    extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.deleteContentStream(repositoryId, objectId, changeToken, extension);

            if (stopAfterService(service)) {
                return;
            }

        } finally {
            service.close();
        }
    }

    @Override
    public void deleteObject(String repositoryId, String objectId, Boolean allVersions, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.deleteObject(repositoryId, objectId, allVersions, extension);

            if (stopAfterService(service)) {
                return;
            }

        } finally {
            service.close();
        }
    }

    @Override
    public FailedToDeleteData deleteTree(String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            FailedToDeleteData serviceResult = service.deleteTree(repositoryId, folderId, allVersions, unfileObjects,
                    continueOnFailure, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public AllowableActions getAllowableActions(String repositoryId, String objectId, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            AllowableActions serviceResult = service.getAllowableActions(repositoryId, objectId, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public ContentStream getContentStream(String repositoryId, String objectId, String streamId, BigInteger offset,
            BigInteger length, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ContentStream serviceResult = service.getContentStream(repositoryId, objectId, streamId, offset, length,
                    extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public ObjectData getObject(String repositoryId, String objectId, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ObjectData serviceResult = service.getObject(repositoryId, objectId, filter, includeAllowableActions,
                    includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public ObjectData getObjectByPath(String repositoryId, String path, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ObjectData serviceResult = service.getObjectByPath(repositoryId, path, filter, includeAllowableActions,
                    includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public Properties getProperties(String repositoryId, String objectId, String filter, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            Properties serviceResult = service.getProperties(repositoryId, objectId, filter, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public List<RenditionData> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            List<RenditionData> serviceResult = service.getRenditions(repositoryId, objectId, renditionFilter,
                    maxItems, skipCount, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.moveObject(repositoryId, objectId, targetFolderId, sourceFolderId, extension);

            if (stopAfterService(service)) {
                return;
            }
        } finally {
            service.close();
        }
    }

    @Override
    public void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.setContentStream(repositoryId, objectId, overwriteFlag, changeToken, contentStream, extension);

            if (stopAfterService(service)) {
                return;
            }
        } finally {
            service.close();
        }
    }

    @Override
    public void appendContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.appendContentStream(repositoryId, objectId, changeToken, contentStream, isLastChunk, extension);

            if (stopAfterService(service)) {
                return;
            }
        } finally {
            service.close();
        }
    }

    @Override
    public void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            Properties properties, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return;
            }

            service.updateProperties(repositoryId, objectId, changeToken, properties, extension);

            if (stopAfterService(service)) {
                return;
            }
        } finally {
            service.close();
        }
    }

    @Override
    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            List<BulkUpdateObjectIdAndChangeToken> serviceResult = service.bulkUpdateProperties(repositoryId,
                    objectIdAndChangeToken, properties, addSecondaryTypeIds, removeSecondaryTypeIds, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }
}
