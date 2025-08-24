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
package org.apache.chemistry.opencmis.client.api;

import java.io.OutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

/**
 * This interface provides asynchronous CMIS operations.
 */
public interface AsyncSession {

    /**
     * Returns the session object.
     * 
     * @return the session object, not {@code null}
     */
    Session getSession();

    // --- types ---

    /**
     * Gets the definition of a type.
     * 
     * @param typeId
     *            the ID of the type
     * 
     * @return the type definition
     * 
     * @throws CmisObjectNotFoundException
     *             if a type with the given type ID doesn't exist
     * 
     * @cmis 1.0
     */
    Future<ObjectType> getTypeDefinition(String typeId);

    /**
     * Creates a new type.
     * 
     * @param type
     *            the type definition
     * 
     * @return the new type definition
     * 
     * @cmis 1.1
     */
    Future<ObjectType> createType(TypeDefinition type);

    /**
     * Updates an existing type.
     * 
     * @param type
     *            the type definition updates
     * 
     * @return the updated type definition
     * 
     * @cmis 1.1
     */
    Future<ObjectType> updateType(TypeDefinition type);

    /**
     * Deletes a type.
     * 
     * @param typeId
     *            the ID of the type to delete
     * 
     * @cmis 1.1
     */
    Future<?> deleteType(String typeId);

    // --- objects ---

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the cache is turned off per default {@link OperationContext}, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param objectId
     *            the object ID
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObject(ObjectId objectId);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the given {@link OperationContext} has caching turned off, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param objectId
     *            the object ID
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObject(ObjectId objectId, OperationContext context);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the cache is turned off per default {@link OperationContext}, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param objectId
     *            the object ID
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObject(String objectId);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the given {@link OperationContext} has caching turned off, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param objectId
     *            the object ID
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObject(String objectId, OperationContext context);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the cache is turned off per default {@link OperationContext}, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param path
     *            the object path
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObjectByPath(String path);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the given {@link OperationContext} has caching turned off, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param path
     *            the object path
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObjectByPath(String path, OperationContext context);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the cache is turned off per default {@link OperationContext}, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param parentPath
     *            the path of the parent folder
     * @param name
     *            the (path segment) name of the object in the folder
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObjectByPath(String parentPath, String name);

    /**
     * Returns a CMIS object from the session cache. If the object is not in the
     * cache or the given {@link OperationContext} has caching turned off, it
     * will load the object from the repository and puts it into the cache.
     * <p>
     * This method might return a stale object if the object has been found in
     * the cache and has been changed in or removed from the repository. Use
     * {@link CmisObject#refresh()} and {@link CmisObject#refreshIfOld(long)} to
     * update the object if necessary.
     * 
     * @param parentPath
     *            the path of the parent folder
     * @param name
     *            the (path segment) name of the object in the folder
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the requested object
     * 
     * @cmis 1.0
     */
    Future<CmisObject> getObjectByPath(String parentPath, String name, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Future<Document> getLatestDocumentVersion(ObjectId objectId);

    /**
     * Returns the latest version in a version series.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Future<Document> getLatestDocumentVersion(ObjectId objectId, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * @param major
     *            if {@code true} the latest major version will be returned,
     *            otherwise the very last version will be returned
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Future<Document> getLatestDocumentVersion(ObjectId objectId, boolean major, OperationContext context);

    /**
     * /** Returns the latest version in a version series.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Future<Document> getLatestDocumentVersion(String objectId);

    /**
     * Returns the latest version in a version series.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Future<Document> getLatestDocumentVersion(String objectId, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * @param major
     *            if {@code true} the latest major version will be returned,
     *            otherwise the very last version will be returned
     * @param context
     *            the {@link OperationContext} to use
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Future<Document> getLatestDocumentVersion(String objectId, boolean major, OperationContext context);

    // --- create ---

    /**
     * Creates a new document.
     * 
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @return the object ID of the new document
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new document.
     * 
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @return the object ID of the new document
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState);

    /**
     * Creates a new document from a source document.
     * 
     * @return the object ID of the new document
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new document from a source document.
     * 
     * @return the object ID of the new document
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState);

    /**
     * Creates a new folder.
     * 
     * @return the object ID of the new folder
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createFolder(Map<String, ?> properties, ObjectId folderId, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new folder.
     * 
     * @return the object ID of the new folder
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createFolder(Map<String, ?> properties, ObjectId folderId);

    /**
     * Creates a new policy.
     * 
     * @return the object ID of the new policy
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createPolicy(Map<String, ?> properties, ObjectId folderId, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new policy.
     * 
     * @return the object ID of the new policy
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createPolicy(Map<String, ?> properties, ObjectId folderId);

    /**
     * Creates a new item.
     * 
     * @return the object ID of the new policy
     * 
     * @cmis 1.1
     */
    Future<ObjectId> createItem(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces);

    /**
     * Creates a new item.
     * 
     * @return the object ID of the new item
     * 
     * @cmis 1.1
     */
    Future<ObjectId> createItem(Map<String, ?> properties, ObjectId folderId);

    /**
     * Creates a new relationship.
     * 
     * @return the object ID of the new relationship
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createRelationship(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces);

    /**
     * Creates a new relationship.
     * 
     * @return the object ID of the new relationship
     * 
     * @cmis 1.0
     */
    Future<ObjectId> createRelationship(Map<String, ?> properties);

    // --- content ---

    /**
     * Retrieves the content stream of a document.
     * 
     * @param docId
     *            the ID of the document
     * @param streamId
     *            the stream ID
     * @param offset
     *            the offset of the stream or {@code null} to read the stream
     *            from the beginning
     * @param length
     *            the maximum length of the stream or {@code null} to read to
     *            the end of the stream
     * 
     * @return the content stream or {@code null} if the document has no content
     *         stream
     * 
     * @cmis 1.0
     */
    Future<ContentStream> getContentStream(ObjectId docId, String streamId, BigInteger offset, BigInteger length);

    /**
     * Retrieves the main content stream of a document.
     * 
     * @param docId
     *            the ID of the document
     * 
     * @return the content stream or {@code null} if the document has no content
     *         stream
     * 
     * @cmis 1.0
     */
    Future<ContentStream> getContentStream(ObjectId docId);

    /**
     * Reads the document content and writes it to an output stream.
     * 
     * The output stream is not closed.
     * 
     * @param docId
     *            the ID of the document
     * @param streamId
     *            the stream ID
     * @param offset
     *            the offset of the stream or {@code null} to read the stream
     *            from the beginning
     * @param length
     *            the maximum length of the stream or {@code null} to read to
     *            the end of the stream
     * 
     * @return the content stream object (the input stream is closed) or
     *         {@code null} if the document has no content stream
     * 
     * @cmis 1.0
     */
    Future<ContentStream> storeContentStream(ObjectId docId, String streamId, BigInteger offset, BigInteger length,
            OutputStream target);

    /**
     * Reads the document content and writes it to an output stream.
     * 
     * The output stream is not closed.
     * 
     * @param docId
     *            the ID of the document
     * 
     * @return the content stream object (the input stream is closed) or
     *         {@code null} if the document has no content stream
     * 
     * @cmis 1.0
     */
    Future<ContentStream> storeContentStream(ObjectId docId, OutputStream target);

    // --- delete ---

    /**
     * Deletes an object.
     * 
     * @param objectId
     *            the ID of the object
     * @param allVersions
     *            if this object is a document this parameter defines if only
     *            this version or all versions should be deleted
     * 
     * @cmis 1.0
     */
    Future<?> delete(ObjectId objectId, boolean allVersions);

    /**
     * Deletes an object and, if it is a document, all versions in the version
     * series.
     * 
     * @param objectId
     *            the ID of the object
     * 
     * @cmis 1.0
     */
    Future<?> delete(ObjectId objectId);

    /**
     * Deletes a folder and all subfolders.
     * 
     * @param folderId
     *            the ID of the folder
     * @param allVersions
     *            if this object is a document this parameter defines if only
     *            this version or all versions should be deleted
     * @param unfile
     *            defines how objects should be unfiled
     * @param continueOnFailure
     *            if {@code true} the repository tries to delete as many objects
     *            as possible; if {@code false} the repository stops at the
     *            first object that could not be deleted
     * 
     * @return a list of object IDs which failed to be deleted
     * 
     * @cmis 1.0
     */
    Future<List<String>> deleteTree(ObjectId folderId, boolean allVersions, UnfileObject unfile,
            boolean continueOnFailure);

    // --- ACL ---

    /**
     * Applies ACL changes to an object and dependent objects.
     * 
     * Only direct ACEs can be added and removed.
     * 
     * @param objectId
     *            the ID the object
     * @param addAces
     *            list of ACEs to be added or {@code null} if no ACEs should be
     *            added
     * @param removeAces
     *            list of ACEs to be removed or {@code null} if no ACEs should
     *            be removed
     * @param aclPropagation
     *            value that defines the propagation of the ACE changes;
     *            {@code null} is equal to
     *            {@link AclPropagation#REPOSITORYDETERMINED}
     * 
     * @return the new ACL of the object
     * 
     * @cmis 1.0
     */
    Future<Acl> applyAcl(ObjectId objectId, List<Ace> addAces, List<Ace> removeAces, AclPropagation aclPropagation);

    /**
     * Removes the direct ACEs of an object and sets the provided ACEs.
     * 
     * The changes are local to the given object and are not propagated to
     * dependent objects.
     * 
     * @param objectId
     *            the ID the object
     * @param aces
     *            list of ACEs to be set
     * 
     * @return the new ACL of the object
     * 
     * @cmis 1.0
     */
    Future<Acl> setAcl(ObjectId objectId, List<Ace> aces);

    // --- policy ---

    /**
     * Applies a set of policies to an object.
     * 
     * This operation is not atomic. If it fails some policies might already be
     * applied.
     * 
     * @param objectId
     *            the ID the object
     * @param policyIds
     *            the IDs of the policies to be applied
     * 
     * @cmis 1.0
     */
    Future<?> applyPolicy(ObjectId objectId, ObjectId... policyIds);

    /**
     * Removes a set of policies from an object.
     * 
     * This operation is not atomic. If it fails some policies might already be
     * removed.
     * 
     * @param objectId
     *            the ID the object
     * @param policyIds
     *            the IDs of the policies to be removed
     * 
     * @cmis 1.0
     */
    Future<?> removePolicy(ObjectId objectId, ObjectId... policyIds);
}
