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
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;

public class NavigationServiceImpl extends AbstractLocalService implements NavigationService {

    /**
     * Constructor.
     */
    public NavigationServiceImpl(BindingSession session, CmisServiceFactory factory) {
        setSession(session);
        setServiceFactory(factory);
    }

    @Override
    public ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }
            ObjectList serviceResult = service.getCheckedOutDocs(repositoryId, folderId, filter, orderBy,
                    includeAllowableActions, includeRelationships, renditionFilter, maxItems, skipCount, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public ObjectInFolderList getChildren(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ObjectInFolderList serviceResult = service.getChildren(repositoryId, folderId, filter, orderBy,
                    includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, maxItems,
                    skipCount, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }
            List<ObjectInFolderContainer> serviceResult = service.getDescendants(repositoryId, folderId, depth, filter,
                    includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public ObjectData getFolderParent(String repositoryId, String folderId, String filter, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ObjectData serviceResult = service.getFolderParent(repositoryId, folderId, filter, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            List<ObjectInFolderContainer> serviceResult = service.getFolderTree(repositoryId, folderId, depth, filter,
                    includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, extension);

            if (stopAfterService(service)) {
                return null;
            }
            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includeRelativePathSegment, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            List<ObjectParentData> serviceResult = service.getObjectParents(repositoryId, objectId, filter,
                    includeAllowableActions, includeRelationships, renditionFilter, includeRelativePathSegment,
                    extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }
}
