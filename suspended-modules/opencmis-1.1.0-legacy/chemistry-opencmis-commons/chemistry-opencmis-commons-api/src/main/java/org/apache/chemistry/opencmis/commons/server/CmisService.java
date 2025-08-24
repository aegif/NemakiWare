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
package org.apache.chemistry.opencmis.commons.server;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.MultiFilingService;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.chemistry.opencmis.commons.spi.PolicyService;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

/**
 * OpenCMIS server interface.
 * 
 * <p>
 * <em>
 * See CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 * 
 * <p>
 * This interface adds a few more operations to the operation set defined by
 * CMIS to address binding specific requirements.
 * </p>
 */
public interface CmisService extends RepositoryService, NavigationService, ObjectService, VersioningService,
        DiscoveryService, MultiFilingService, RelationshipService, AclService, PolicyService {

    /**
     * Creates a new document, folder, policy, or item.
     * 
     * The property "cmis:objectTypeId" defines the type and implicitly the base
     * type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param properties
     *            the property values that MUST be applied to the newly created
     *            object
     * @param folderId
     *            <em>(optional)</em> if specified, the identifier for the
     *            folder that MUST be the parent folder for the newly created
     *            object
     * @param contentStream
     *            <em>(optional)</em> if the object to create is a document
     *            object, the content stream that MUST be stored for the newly
     *            created document object
     * @param versioningState
     *            <em>(optional)</em> if the object to create is a document
     *            object, it specifies what the versioning state of the newly
     *            created object MUST be (default is
     *            {@link VersioningState#MAJOR})
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that MUST be applied
     *            to the newly created object
     * @param extension
     *            extension data
     * @return the object ID of the newly created object
     */
    String create(String repositoryId, Properties properties, String folderId, ContentStream contentStream,
            VersioningState versioningState, List<String> policies, ExtensionsData extension);

    /**
     * Deletes an object or cancels a check out.
     * 
     * For the Web Services binding this is always an object deletion. For the
     * AtomPub it depends on the referenced object. If it is a checked out
     * document then the check out must be canceled. If the object is not a
     * checked out document then the object must be deleted.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param allVersions
     *            <em>(optional)</em> If {@code true} then delete all versions
     *            of the document, otherwise delete only the document object
     *            specified (default is {@code true})
     * @param extension
     *            extension data
     */
    void deleteObjectOrCancelCheckOut(String repositoryId, String objectId, Boolean allVersions,
            ExtensionsData extension);

    /**
     * Applies a new ACL to an object.
     * 
     * Since it is not possible to transmit an "add ACL" and a "remove ACL" via
     * AtomPub, the merging has to be done the client side. The ACEs provided
     * here is supposed to the new complete ACL.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param aces
     *            the ACEs that should replace the current ACL of the object
     * @param aclPropagation
     *            <em>(optional)</em> specifies how ACEs should be handled
     *            (default is {@link AclPropagation#REPOSITORYDETERMINED})
     * @return new ACL
     */
    Acl applyAcl(String repositoryId, String objectId, Acl aces, AclPropagation aclPropagation);

    /**
     * Returns the {@link ObjectInfo} of the given object id or {@code null} if
     * no object info exists.
     * 
     * Only AtomPub requests will require object infos.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @return the object info
     */
    ObjectInfo getObjectInfo(String repositoryId, String objectId);

    /**
     * Signals that this object will not be used anymore and resources can
     * released.
     */
    void close();
}
