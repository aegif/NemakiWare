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
package org.apache.chemistry.opencmis.commons.spi;

import java.math.BigInteger;
import java.util.List;

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

/**
 * Object Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface ObjectService {

    /**
     * Creates a document object of the specified type (given by the
     * cmis:objectTypeId property) in the (optionally) specified location.
     * 
     * The stream in <code>contentStream</code> is consumed but not closed by
     * this method.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param properties
     *            the property values that must be applied to the newly created
     *            document object
     * @param folderId
     *            <em>(optional)</em> if specified, the identifier for the
     *            folder that must be the parent folder for the newly created
     *            document object
     * @param contentStream
     *            <em>(optional)</em> the content stream that must be stored for
     *            the newly created document object
     * @param versioningState
     *            <em>(optional)</em> specifies what the versioning state of the
     *            newly created object must be (default is
     *            {@link VersioningState#MAJOR})
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that must be applied
     *            to the newly created document object
     * @param addAces
     *            <em>(optional)</em> a list of ACEs that must be added to the
     *            newly created document object, either using the ACL from
     *            {@code folderId} if specified, or being applied if no
     *            {@code folderId} is specified
     * @param removeAces
     *            <em>(optional)</em> a list of ACEs that must be removed from
     *            the newly created document object, either using the ACL from
     *            {@code folderId} if specified, or being ignored if no
     *            {@code folderId} is specified
     * @param extension
     *            extension data
     * @return the ID of the newly created document
     */
    String createDocument(String repositoryId, Properties properties, String folderId, ContentStream contentStream,
            VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension);

    /**
     * Creates a document object as a copy of the given source document in the
     * (optionally) specified location.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param sourceId
     *            the identifier for the source document
     * @param properties
     *            the property values that must be applied to the newly created
     *            document object
     * @param folderId
     *            <em>(optional)</em> if specified, the identifier for the
     *            folder that must be the parent folder for the newly created
     *            document object
     * @param versioningState
     *            <em>(optional)</em> specifies what the versioning state of the
     *            newly created object must be (default is
     *            {@link VersioningState#MAJOR})
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that must be applied
     *            to the newly created document object
     * @param addAces
     *            <em>(optional)</em> a list of ACEs that must be added to the
     *            newly created document object, either using the ACL from
     *            {@code folderId} if specified, or being applied if no
     *            {@code folderId} is specified
     * @param removeAces
     *            <em>(optional)</em> a list of ACEs that must be removed from
     *            the newly created document object, either using the ACL from
     *            {@code folderId} if specified, or being ignored if no
     *            {@code folderId} is specified
     * @param extension
     *            extension data
     * @return the ID of the newly created document
     */
    String createDocumentFromSource(String repositoryId, String sourceId, Properties properties, String folderId,
            VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension);

    /**
     * Creates a folder object of the specified type (given by the
     * cmis:objectTypeId property) in the specified location.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param properties
     *            the property values that must be applied to the newly created
     *            folder object
     * @param folderId
     *            the identifier for the parent folder
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that must be applied
     *            to the newly created folder object
     * @param addAces
     *            <em>(optional)</em> a list of ACEs that must be added to the
     *            newly created folder object, either using the ACL from
     *            {@code folderId} if specified, or being applied if no
     *            {@code folderId} is specified
     * @param removeAces
     *            <em>(optional)</em> a list of ACEs that must be removed from
     *            the newly created folder object, either using the ACL from
     *            {@code folderId} if specified, or being ignored if no
     *            {@code folderId} is specified
     * @param extension
     *            extension data
     * @return the ID of the newly created folder
     */
    String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension);

    /**
     * Creates a relationship object of the specified type (given by the
     * cmis:objectTypeId property).
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param properties
     *            the property values that must be applied to the newly created
     *            relationship object
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that must be applied
     *            to the newly created relationship object
     * @param addAces
     *            <em>(optional)</em> a list of ACEs that must be added to the
     *            newly created relationship object, either using the ACL from
     *            {@code folderId} if specified, or being applied if no
     *            {@code folderId} is specified
     * @param removeAces
     *            <em>(optional)</em> a list of ACEs that must be removed from
     *            the newly created relationship object, either using the ACL
     *            from {@code folderId} if specified, or being ignored if no
     *            {@code folderId} is specified
     * @param extension
     *            extension data
     * @return the ID of the newly created relationship
     */
    String createRelationship(String repositoryId, Properties properties, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension);

    /**
     * Creates a policy object of the specified type (given by the
     * cmis:objectTypeId property).
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param properties
     *            the property values that must be applied to the newly created
     *            policy object
     * @param folderId
     *            <em>(optional)</em> if specified, the identifier for the
     *            folder that must be the parent folder for the newly created
     *            policy object
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that must be applied
     *            to the newly created policy object
     * @param addAces
     *            <em>(optional)</em> a list of ACEs that must be added to the
     *            newly created policy object, either using the ACL from
     *            {@code folderId} if specified, or being applied if no
     *            {@code folderId} is specified
     * @param removeAces
     *            <em>(optional)</em> a list of ACEs that must be removed from
     *            the newly created policy object, either using the ACL from
     *            {@code folderId} if specified, or being ignored if no
     *            {@code folderId} is specified
     * @param extension
     *            extension data
     * @return the ID of the newly created policy
     */
    String createPolicy(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension);

    /**
     * Creates an item object of the specified type (given by the
     * cmis:objectTypeId property).
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param properties
     *            the property values that must be applied to the newly created
     *            policy object
     * @param folderId
     *            <em>(optional)</em> if specified, the identifier for the
     *            folder that must be the parent folder for the newly created
     *            policy object
     * @param policies
     *            <em>(optional)</em> a list of policy IDs that must be applied
     *            to the newly created policy object
     * @param addAces
     *            <em>(optional)</em> a list of ACEs that must be added to the
     *            newly created policy object, either using the ACL from
     *            {@code folderId} if specified, or being applied if no
     *            {@code folderId} is specified
     * @param removeAces
     *            <em>(optional)</em> a list of ACEs that must be removed from
     *            the newly created policy object, either using the ACL from
     *            {@code folderId} if specified, or being ignored if no
     *            {@code folderId} is specified
     * @param extension
     *            extension data
     * @return the ID of the newly created item
     */
    String createItem(String repositoryId, Properties properties, String folderId, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension);

    /**
     * Gets the list of allowable actions for an object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param extension
     *            extension data
     * @return the allowable actions
     */
    AllowableActions getAllowableActions(String repositoryId, String objectId, ExtensionsData extension);

    /**
     * Gets the specified information for the object specified by id.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the allowable actions for the object (default is
     *            {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            object participates must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is <code>"cmis:none"</code>)
     * @param includePolicyIds
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the policy ids for the object (default is {@code false}
     *            )
     * @param includeAcl
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the ACL for the object (default is {@code false})
     * @param extension
     *            extension data
     * @return the object
     */
    ObjectData getObject(String repositoryId, String objectId, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension);

    /**
     * Gets the list of properties for an object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param extension
     *            extension data
     * @return the object properties
     */
    Properties getProperties(String repositoryId, String objectId, String filter, ExtensionsData extension);

    /**
     * Gets the list of associated renditions for the specified object. Only
     * rendition attributes are returned, not rendition stream.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is <code>"cmis:none"</code>)
     * @param maxItems
     *            <em>(optional)</em> the maximum number of items to return in a
     *            response (default is repository specific)
     * @param skipCount
     *            <em>(optional)</em> number of potential results that the
     *            repository must skip/page over before returning any results
     *            (default is 0)
     * @param extension
     *            extension data
     * @return the list of renditions
     */
    List<RenditionData> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

    /**
     * Gets the specified information for the object specified by path.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param path
     *            the path to the object
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the allowable actions for the object (default is
     *            {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            object participates must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is <code>"cmis:none"</code>)
     * @param includePolicyIds
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the policy ids for the object (default is {@code false}
     *            )
     * @param includeAcl
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the ACL for the object (default is {@code false})
     * @param extension
     *            extension data
     * @return the object
     */
    ObjectData getObjectByPath(String repositoryId, String path, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension);

    /**
     * Gets the content stream for the specified document object, or gets a
     * rendition stream for a specified rendition of a document or folder
     * object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param extension
     *            extension data
     * @return the content stream
     */
    ContentStream getContentStream(String repositoryId, String objectId, String streamId, BigInteger offset,
            BigInteger length, ExtensionsData extension);

    /**
     * Updates properties of the specified object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object. The repository might return a
     *            different/new object id
     * @param changeToken
     *            <em>(optional)</em> the last change token of this object that
     *            the client received. The repository might return a new change
     *            token (default is {@code null})
     * @param properties
     *            the updated property values that must be applied to the object
     * @param extension
     *            extension data
     */
    void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            Properties properties, ExtensionsData extension);

    /**
     * Updates properties and secondary types of one or more objects.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectIdsAndChangeTokens
     *            the ids and change tokens of the objects to update
     * @param properties
     *            the properties to set
     * @param addSecondaryTypeIds
     *            the secondary types to apply
     * @param removeSecondaryTypeIds
     *            the secondary types to remove
     * @param extension
     *            extension data
     * @return the list of updated objects with their change tokens
     */
    List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdsAndChangeTokens, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension);

    /**
     * Moves the specified file-able object from one folder to another.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object. The repository might return a
     *            different/new object id
     * @param targetFolderId
     *            the identifier for the target folder
     * @param sourceFolderId
     *            the identifier for the source folder
     * @param extension
     *            extension data
     */
    void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            ExtensionsData extension);

    /**
     * Deletes the specified object.
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
    void deleteObject(String repositoryId, String objectId, Boolean allVersions, ExtensionsData extension);

    /**
     * Deletes the specified folder object and all of its child- and
     * descendant-objects.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param folderId
     *            the identifier for the folder
     * @param allVersions
     *            <em>(optional)</em> If {@code true} then delete all versions
     *            of the document, otherwise delete only the document object
     *            specified (default is {@code true})
     * @param unfileObjects
     *            <em>(optional)</em> defines how the repository must process
     *            file-able child- or descendant-objects (default is
     *            {@link UnfileObject#DELETE})
     * @param continueOnFailure
     *            <em>(optional)</em> If {@code true}, then the repository
     *            should continue attempting to perform this operation even if
     *            deletion of a child- or descendant-object in the specified
     *            folder cannot be deleted (default is {@code false})
     * @param extension
     *            extension data
     * @return a (possibly incomplete) collection of object IDs of objects that
     *         couldn't be deleted
     */
    FailedToDeleteData deleteTree(String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension);

    /**
     * Sets the content stream for the specified document object.
     * 
     * The stream in <code>contentStream</code> is consumed but not closed by
     * this method.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object. The repository might return a
     *            different/new object id
     * @param overwriteFlag
     *            <em>(optional)</em> If {@code true}, then the repository must
     *            replace the existing content stream for the object (if any)
     *            with the input content stream. If If {@code false}, then the
     *            repository must only set the input content stream for the
     *            object if the object currently does not have a content stream
     *            (default is {@code true})
     * @param changeToken
     *            <em>(optional)</em> the last change token of this object that
     *            the client received. The repository might return a new change
     *            token (default is {@code null})
     * @param contentStream
     *            the content stream
     * @param extension
     *            extension data
     */
    void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension);

    /**
     * Deletes the content stream for the specified document object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object. The repository might return a
     *            different/new object id
     * @param changeToken
     *            <em>(optional)</em> the last change token of this object that
     *            the client received. The repository might return a new change
     *            token (default is {@code null})
     * @param extension
     *            extension data
     */
    void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ExtensionsData extension);

    /**
     * Appends the content stream to the content of the document.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object. The repository might return a
     *            different/new object id
     * @param changeToken
     *            <em>(optional)</em> the last change token of this object that
     *            the client received. The repository might return a new change
     *            token (default is {@code null})
     * @param contentStream
     *            the content stream to append
     * @param isLastChunk
     *            indicates if this content stream is the last chunk
     * @param extension
     *            extension data
     */
    void appendContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ContentStream contentStream, boolean isLastChunk, ExtensionsData extension);
}
