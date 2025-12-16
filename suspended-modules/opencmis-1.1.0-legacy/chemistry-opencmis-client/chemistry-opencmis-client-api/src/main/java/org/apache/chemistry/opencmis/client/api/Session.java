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

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

/**
 * A session is a connection to a CMIS repository with a specific user.
 * <p>
 * CMIS itself is stateless. OpenCMIS uses the concept of a session to cache
 * data across calls and to deal with user authentication. The session object is
 * also used as entry point to all CMIS operations and objects. Because a
 * session is only a client side concept, the session object needs not to be
 * closed or released when it's not needed anymore.
 * <p>
 * Not all operations provided by this API might be supported by the connected
 * repository. Either OpenCMIS or the repository will throw an exception if an
 * unsupported operation is called. The capabilities of the repository can be
 * discovered by evaluating the repository info (see
 * {@link #getRepositoryInfo()}).
 * <p>
 * Almost all methods might throw exceptions derived from
 * {@link CmisBaseException} which is a runtime exception. See the CMIS
 * specification for a list of all operations and their exceptions. Note that
 * some incompliant repositories might throw other exception than you expect.
 * <p>
 * Refer to the <a href="http://docs.oasis-open.org/cmis/CMIS/v1.0/os/">CMIS 1.0
 * specification</a> or the
 * <a href="http://docs.oasis-open.org/cmis/CMIS/v1.0/os/">CMIS 1.1
 * specification</a> for details about the domain model, terms, concepts, base
 * types, properties, IDs and query names, query language, etc.
 * </p>
 */
public interface Session extends Serializable {

    /**
     * Clears all cached data.
     */
    void clear();

    // session context

    /**
     * Returns the underlying binding object.
     * 
     * @return the binding object, not {@code null}
     */
    CmisBinding getBinding();

    /**
     * Returns the session parameters that were used to create this session.
     * 
     * @return the session parameters, a unmodifiable Map, not {@code null}
     */
    Map<String, String> getSessionParameters();

    /**
     * Returns the current default operation parameters for filtering, paging
     * and caching.
     * 
     * <p>
     * <em>Please note:</em> The returned object is not thread-safe and should
     * only be modified right after the session has been created and before the
     * session object has been used. In order to change the default context in
     * thread-safe manner, create a new {@link OperationContext} object and use
     * {@link #setDefaultContext(OperationContext)} to apply it.
     * </p>
     * 
     * @return the default operation context, not {@code null}
     */
    OperationContext getDefaultContext();

    /**
     * Sets the current session parameters for filtering, paging and caching.
     * 
     * @param context
     *            the {@code OperationContext} to be used for the session; if
     *            {@code null}, a default context is used
     */
    void setDefaultContext(OperationContext context);

    /**
     * Creates a new operation context object.
     * 
     * @return the newly created operation context object
     */
    OperationContext createOperationContext();

    /**
     * Creates a new operation context object with the given properties.
     * 
     * @param filter
     *            the property filter, a comma separated string of <em>query
     *            names</em> or "*" for all properties or {@code null} to let
     *            the repository determine a set of properties
     * @param includeAcls
     *            indicates whether ACLs should be included or not
     * @param includeAllowableActions
     *            indicates whether Allowable Actions should be included or not
     * @param includePolicies
     *            indicates whether policies should be included or not
     * @param includeRelationships
     *            enum that indicates if and which relationships should be
     *            includes
     * @param renditionFilter
     *            the rendition filter or {@code null} for no renditions
     * @param includePathSegments
     *            indicates whether path segment or the relative path segment
     *            should be included or not
     * @param orderBy
     *            the object order, a comma-separated list of <em>query
     *            names</em> and the ascending modifier "ASC" or the descending
     *            modifier "DESC" for each query name
     * @param cacheEnabled
     *            flag that indicates if the object cache should be used
     * @param maxItemsPerPage
     *            the max items per batch
     * 
     * @return the newly created operation context object
     * 
     * @see OperationContext
     */
    OperationContext createOperationContext(Set<String> filter, boolean includeAcls, boolean includeAllowableActions,
            boolean includePolicies, IncludeRelationships includeRelationships, Set<String> renditionFilter,
            boolean includePathSegments, String orderBy, boolean cacheEnabled, int maxItemsPerPage);

    /**
     * Creates an object ID from a String.
     * 
     * @return the object ID object
     */
    ObjectId createObjectId(String id);

    // localization

    /**
     * Get the current locale to be used for this session.
     * 
     * @return the current locale, may be {@code null}
     */
    Locale getLocale();

    // services

    /**
     * Returns the repository info of the repository associated with this
     * session.
     * 
     * @return the repository info, not {@code null}
     * 
     * @cmis 1.0
     */
    RepositoryInfo getRepositoryInfo();

    /**
     * Gets a factory object that provides methods to create the objects used by
     * this API.
     * 
     * @return the repository info, not {@code null}
     */
    ObjectFactory getObjectFactory();

    // types

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
    ObjectType getTypeDefinition(String typeId);

    /**
     * Gets the definition of a type.
     * 
     * @param typeId
     *            the ID of the type
     * @param useCache
     *            specifies if the type definition should be first looked up in
     *            the type definition cache, if it is set to {@code false} or
     *            the type definition is not in the cache, the type definition
     *            is loaded from the repository
     * 
     * @return the type definition
     * 
     * @throws CmisObjectNotFoundException
     *             if a type with the given type ID doesn't exist
     * 
     * @cmis 1.0
     */
    ObjectType getTypeDefinition(String typeId, boolean useCache);

    /**
     * Gets the type children of a type.
     * 
     * @param typeId
     *            the type ID or {@code null} to request the base types
     * @param includePropertyDefinitions
     *            indicates whether the property definitions should be included
     *            or not
     * @return the type iterator, not {@code null}
     * 
     * @throws CmisObjectNotFoundException
     *             if a type with the given type ID doesn't exist
     * 
     * @cmis 1.0
     */
    ItemIterable<ObjectType> getTypeChildren(String typeId, boolean includePropertyDefinitions);

    /**
     * Gets the type descendants of a type.
     * 
     * @param typeId
     *            the type ID or {@code null} to request the base types
     * @param depth
     *            the tree depth, must be greater than 0 or -1 for infinite
     *            depth
     * @param includePropertyDefinitions
     *            indicates whether the property definitions should be included
     *            or not
     * @return the tree of types
     * 
     * @throws CmisObjectNotFoundException
     *             if a type with the given type ID doesn't exist
     * 
     * @cmis 1.0
     */
    List<Tree<ObjectType>> getTypeDescendants(String typeId, int depth, boolean includePropertyDefinitions);

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
    ObjectType createType(TypeDefinition type);

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
    ObjectType updateType(TypeDefinition type);

    /**
     * Deletes a type.
     * 
     * @param typeId
     *            the ID of the type to delete
     * 
     * @cmis 1.1
     */
    void deleteType(String typeId);

    // navigation

    /**
     * Gets the root folder of the repository.
     * 
     * @return the root folder object, not {@code null}
     * 
     * @cmis 1.0
     */
    Folder getRootFolder();

    /**
     * Gets the root folder of the repository with the given
     * {@link OperationContext}.
     * 
     * @return the root folder object, not {@code null}
     * 
     * @cmis 1.0
     */
    Folder getRootFolder(OperationContext context);

    /**
     * Returns all checked out documents.
     * 
     * @see Folder#getCheckedOutDocs()
     * 
     * @cmis 1.0
     */
    ItemIterable<Document> getCheckedOutDocs();

    /**
     * Returns all checked out documents with the given {@link OperationContext}
     * .
     * 
     * @see Folder#getCheckedOutDocs(OperationContext)
     * 
     * @cmis 1.0
     */
    ItemIterable<Document> getCheckedOutDocs(OperationContext context);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @see #getObject(String)
     * 
     * @cmis 1.0
     */
    CmisObject getObject(ObjectId objectId);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @see #getObject(String, OperationContext)
     * 
     * @cmis 1.0
     */
    CmisObject getObject(ObjectId objectId, OperationContext context);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @see #getObject(ObjectId)
     * 
     * @cmis 1.0
     */
    CmisObject getObject(String objectId);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @see #getObject(ObjectId, OperationContext)
     * 
     * @cmis 1.0
     */
    CmisObject getObject(String objectId, OperationContext context);

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
     * @return the requested object
     * 
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @cmis 1.0
     */
    CmisObject getObjectByPath(String path);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @cmis 1.0
     */
    CmisObject getObjectByPath(String path, OperationContext context);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @cmis 1.0
     */
    CmisObject getObjectByPath(String parentPath, String name);

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
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @cmis 1.0
     */
    CmisObject getObjectByPath(String parentPath, String name, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * Some repositories throw an exception if the document is not versionable;
     * others just return the unversioned document. To avoid surprises, check
     * first whether the document is versionable or not.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Document getLatestDocumentVersion(ObjectId objectId);

    /**
     * Returns the latest version in a version series.
     * 
     * Some repositories throw an exception if the document is not versionable;
     * others just return the unversioned document. To avoid surprises, check
     * first whether the document is versionable or not.
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
    Document getLatestDocumentVersion(ObjectId objectId, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * Some repositories throw an exception if the document is not versionable;
     * others just return the unversioned document. To avoid surprises, check
     * first whether the document is versionable or not.
     * 
     * If {@code major} == {@code true} and the version series doesn't contain a
     * major version, the repository is supposed to throw a
     * {@link CmisObjectNotFoundException}.
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
    Document getLatestDocumentVersion(ObjectId objectId, boolean major, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * Some repositories throw an exception if the document is not versionable;
     * others just return the unversioned document. To avoid surprises, check
     * first whether the document is versionable or not.
     * 
     * @param objectId
     *            the document ID of an arbitrary version in the version series
     * 
     * @return the latest document version
     * 
     * @cmis 1.0
     */
    Document getLatestDocumentVersion(String objectId);

    /**
     * Returns the latest version in a version series.
     * 
     * Some repositories throw an exception if the document is not versionable;
     * others just return the unversioned document. To avoid surprises, check
     * first whether the document is versionable or not.
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
    Document getLatestDocumentVersion(String objectId, OperationContext context);

    /**
     * Returns the latest version in a version series.
     * 
     * Some repositories throw an exception if the document is not versionable;
     * others just return the unversioned document. To avoid surprises, check
     * first whether the document is versionable or not.
     * 
     * If {@code major} == {@code true} and the version series doesn't contain a
     * major version, the repository is supposed to throw a
     * {@link CmisObjectNotFoundException}.
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
    Document getLatestDocumentVersion(String objectId, boolean major, OperationContext context);

    /**
     * Checks if an object with given object ID exists in the repository and is
     * visible for the current user.
     * 
     * If the object doesn't exist (anymore), it is removed from the cache.
     * 
     * @param objectId
     *            the object ID
     * @return {@code true} if the object exists in the repository,
     *         {@code false} otherwise
     * 
     * @cmis 1.0
     */
    boolean exists(ObjectId objectId);

    /**
     * Checks if an object with given object ID exists in the repository and is
     * visible for the current user.
     * 
     * If the object doesn't exist (anymore), it is removed from the cache.
     * 
     * @param objectId
     *            the object ID
     * @return {@code true} if the object exists in the repository,
     *         {@code false} otherwise
     * 
     * @cmis 1.0
     */
    boolean exists(String objectId);

    /**
     * Checks if an object with given path exists in the repository and is
     * visible for the current user.
     * 
     * If the object doesn't exist (anymore), it is removed from the cache.
     * 
     * @param path
     *            the path
     * @return {@code true} if the object exists in the repository,
     *         {@code false} otherwise
     * 
     * @cmis 1.0
     */
    boolean existsPath(String path);

    /**
     * Checks if an object with given path exists in the repository and is
     * visible for the current user.
     * 
     * If the object doesn't exist (anymore), it is removed from the cache.
     * 
     * @param parentPath
     *            the path of the parent folder
     * @param name
     *            the (path segment) name of the object in the folder
     * 
     * @return the requested object
     * 
     * @throws CmisObjectNotFoundException
     *             if an object with the given ID doesn't exist
     * 
     * @cmis 1.0
     */
    boolean existsPath(String parentPath, String name);

    /**
     * Removes the given object from the cache.
     * 
     * @param objectId
     *            object ID
     * 
     * @see #removeObjectFromCache(String)
     */
    void removeObjectFromCache(ObjectId objectId);

    /**
     * Removes the given object from the cache.
     * 
     * @param objectId
     *            object ID
     */
    void removeObjectFromCache(String objectId);

    // discovery

    /**
     * Sends a query to the repository. Refer to the CMIS specification for the
     * CMIS query language syntax.
     * 
     * @param statement
     *            the query statement (CMIS query language)
     * @param searchAllVersions
     *            specifies whether non-latest document versions should be
     *            included or not, {@code true} searches all document versions,
     *            {@code false} only searches latest document versions
     * 
     * @return an {@link Iterable} to iterate over the query result
     * 
     * @cmis 1.0
     */
    ItemIterable<QueryResult> query(String statement, boolean searchAllVersions);

    /**
     * Sends a query to the repository using the given {@link OperationContext}.
     * (See CMIS spec "2.1.10 Query".)
     * 
     * @param statement
     *            the query statement (CMIS query language)
     * @param searchAllVersions
     *            specifies whether non-latest document versions should be
     *            included or not, {@code true} searches all document versions,
     *            {@code false} only searches latest document versions
     * @param context
     *            the operation context to use
     * 
     * @return an {@link Iterable} to iterate over the query result
     * 
     * @cmis 1.0
     */
    ItemIterable<QueryResult> query(String statement, boolean searchAllVersions, OperationContext context);

    /**
     * Builds a CMIS query and returns the query results as an iterator of
     * {@link CmisObject} objects.
     * 
     * @param typeId
     *            the ID of the object type
     * @param where
     *            the WHERE part of the query
     * @param searchAllVersions
     *            specifies whether non-latest document versions should be
     *            included or not, {@code true} searches all document versions,
     *            {@code false} only searches latest document versions
     * @param context
     *            the operation context to use
     * 
     * @return an {@link Iterable} to iterate over the objects
     * 
     * @cmis 1.0
     */
    ItemIterable<CmisObject> queryObjects(String typeId, String where, boolean searchAllVersions,
            OperationContext context);

    /**
     * Creates a query statement.
     * <p>
     * Sample code:
     * 
     * <pre>
     * QueryStatement stmt = session
     *         .createQueryStatement(&quot;SELECT ?, ? FROM ? WHERE ? &gt; TIMESTAMP ? AND IN_FOLDER(?) OR ? IN (?)&quot;);
     * </pre>
     * 
     * @param statement
     *            the query statement with placeholders ('?'), see
     *            {@link QueryStatement} for details
     * 
     * @return a new query statement object
     * 
     * @see QueryStatement
     * 
     * @cmis 1.0
     */
    QueryStatement createQueryStatement(String statement);

    /**
     * Creates a query statement for a query of one primary type joined by zero
     * or more secondary types.
     * <p>
     * Sample code:
     * 
     * <pre>
     * List&lt;String&gt; select = new ArrayList&lt;String&gt;();
     * select.add(&quot;cmis:name&quot;);
     * select.add(&quot;SecondaryStringProp&quot;);
     * 
     * Map&lt;String, String&gt; from = new HashMap&lt;String, String&gt;();
     * from.put(&quot;d&quot;, &quot;cmis:document&quot;);
     * from.put(&quot;s&quot;, &quot;MySecondaryType&quot;);
     * 
     * String where = &quot;d.cmis:name LIKE ?&quot;;
     * 
     * List&lt;String&gt; orderBy = new ArrayList&lt;String&gt;();
     * orderBy.add(&quot;cmis:name&quot;);
     * orderBy.add(&quot;SecondaryIntegerProp&quot;);
     * 
     * QueryStatement stmt = session.createQueryStatement(select, from, where, orderBy);
     * </pre>
     * 
     * Generates something like this:
     * 
     * <pre>
     * SELECT d.cmis:name,s.SecondaryStringProp FROM cmis:document AS d JOIN MySecondaryType AS s ON d.cmis:objectId=s.cmis:objectId WHERE d.cmis:name LIKE ? ORDER BY d.cmis:name,s.SecondaryIntegerProp
     * </pre>
     * 
     * @param selectPropertyIds
     *            the property IDs in the SELECT statement, if {@code null} all
     *            properties are selected
     * @param fromTypes
     *            a Map of type aliases (keys) and type IDs (values), the Map
     *            must contain exactly one primary type and zero or more
     *            secondary types
     * @param whereClause
     *            an optional WHERE clause with placeholders ('?'), see
     *            {@link QueryStatement} for details
     * @param orderByPropertyIds
     *            an optional list of properties IDs for the ORDER BY clause
     * 
     * @return a new query statement object
     * 
     * @see QueryStatement
     * 
     * @cmis 1.0
     */
    QueryStatement createQueryStatement(Collection<String> selectPropertyIds, Map<String, String> fromTypes,
            String whereClause, List<String> orderByPropertyIds);

    /**
     * Returns the content changes.
     * 
     * @param changeLogToken
     *            the change log token to start from or {@code null} to start
     *            from the first available event in the repository
     * @param includeProperties
     *            indicates whether changed properties should be included in the
     *            result or not
     * @param maxNumItems
     *            maximum numbers of events
     * 
     * @return the change events
     * 
     * @cmis 1.0
     */
    ChangeEvents getContentChanges(String changeLogToken, boolean includeProperties, long maxNumItems);

    /**
     * Returns the content changes.
     * 
     * @param changeLogToken
     *            the change log token to start from or {@code null} to start
     *            from the first available event in the repository
     * @param includeProperties
     *            indicates whether changed properties should be included in the
     *            result or not
     * @param maxNumItems
     *            maximum numbers of events
     * @param context
     *            the OperationContext
     * 
     * @return the change events
     * 
     * @cmis 1.0
     */
    ChangeEvents getContentChanges(String changeLogToken, boolean includeProperties, long maxNumItems,
            OperationContext context);

    /**
     * Returns an iterator of content changes, starting from the given change
     * log token to the latest entry in the change log.
     * <p>
     * Note: Paging and skipping are not supported.
     * 
     * @param changeLogToken
     *            the change log token to start from or {@code null} to start
     *            from the first available event in the repository
     * @param includeProperties
     *            indicates whether changed properties should be included in the
     *            result or not
     * 
     * @cmis 1.0
     */
    ItemIterable<ChangeEvent> getContentChanges(String changeLogToken, boolean includeProperties);

    /**
     * Returns an iterator of content changes, starting from the given change
     * log token to the latest entry in the change log.
     * <p>
     * Note: Paging and skipping are not supported.
     * 
     * @param changeLogToken
     *            the change log token to start from or {@code null} to start
     *            from the first available event in the repository
     * @param includeProperties
     *            indicates whether changed properties should be included in the
     *            result or not
     * @param context
     *            the OperationContext
     * 
     * @cmis 1.0
     */
    ItemIterable<ChangeEvent> getContentChanges(final String changeLogToken, final boolean includeProperties,
            OperationContext context);

    /**
     * Returns the latest change log token.
     * <p>
     * In contrast to the repository info, this change log token is not cached.
     * This method requests the token from the repository every single time it
     * is called.
     * 
     * @return the latest change log token or {@code null} if the repository
     *         doesn't provide one
     * 
     * @cmis 1.0
     */
    String getLatestChangeLogToken();

    // create

    /**
     * Creates a new document.
     * 
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @return the object ID of the new document
     * 
     * @see Folder#createDocument(Map, ContentStream, VersioningState, List,
     *      List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new document.
     * 
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @return the object ID of the new document
     * 
     * @see Folder#createDocument(Map, ContentStream, VersioningState, List,
     *      List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState);

    /**
     * Creates a new document from a source document.
     * 
     * @return the object ID of the new document
     * 
     * @see Folder#createDocumentFromSource(ObjectId, Map, VersioningState,
     *      List, List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new document from a source document.
     * 
     * @return the object ID of the new document
     * 
     * @see Folder#createDocumentFromSource(ObjectId, Map, VersioningState,
     *      List, List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState);

    /**
     * Creates a new folder.
     * 
     * @param properties
     *            the folder properties
     * @param folderId
     *            the folder ID of the parent folder, not {@code null}
     * 
     * @return the object ID of the new folder
     * 
     * @see Folder#createFolder(Map, List, List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createFolder(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces);

    /**
     * Creates a new folder.
     * 
     * @param properties
     *            the folder properties
     * @param folderId
     *            the folder ID of the parent folder, not {@code null}
     * 
     * @return the object ID of the new folder
     * 
     * @see Folder#createFolder(Map, List, List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createFolder(Map<String, ?> properties, ObjectId folderId);

    /**
     * Creates a folder path.
     * 
     * All missing folders in the path are created. Existing folders are not
     * touched.
     * 
     * @param newPath
     *            the absolute path
     * @param typeId
     *            the type ID of all folders that are being created
     * 
     * @return the object ID of the deepest folder
     * 
     * @cmis 1.0
     */
    ObjectId createPath(String newPath, String typeId);

    /**
     * Creates a folder path.
     * 
     * All missing folders in the path are created. Existing folders are not
     * touched.
     * 
     * @param startFolderId
     *            the ID of a folder in the path that the path creation should
     *            start with, {@code null} for the root folder.
     * @param newPath
     *            the absolute path
     * @param typeId
     *            the type ID of all folders that are being created
     * 
     * @return the object ID of the deepest folder
     * 
     * @cmis 1.0
     */
    ObjectId createPath(ObjectId startFolderId, String newPath, String typeId);

    /**
     * Creates a folder path.
     * 
     * All missing folders in the path are created. Existing folders are not
     * touched.
     * 
     * @param newPath
     *            the absolute path
     * @param properties
     *            the properties of all folders that are being created
     * 
     * @return the object ID of the deepest folder
     * 
     * @cmis 1.0
     */
    ObjectId createPath(String newPath, Map<String, ?> properties);

    /**
     * Creates a folder path.
     * 
     * All missing folders in the path are created. Existing folders are not
     * touched.
     * 
     * @param startFolderId
     *            the ID of a folder in the path that the path creation should
     *            start with, {@code null} for the root folder
     * @param newPath
     *            the absolute path
     * @param properties
     *            the properties of all folders that are being created
     * 
     * @return the object ID of the deepest folder
     * 
     * @cmis 1.0
     */
    ObjectId createPath(ObjectId startFolderId, String newPath, Map<String, ?> properties);

    /**
     * Creates a folder path.
     * 
     * All missing folders in the path are created. Existing folders are not
     * touched.
     * 
     * @param startFolderId
     *            the ID of a folder in the path that the path creation should
     *            start with, {@code null} for the root folder
     * @param newPath
     *            the absolute path
     * @param properties
     *            the properties of all folders that are being created
     * 
     * @return the object ID of the deepest folder
     * 
     * @cmis 1.0
     */
    ObjectId createPath(ObjectId startFolderId, String newPath, Map<String, ?> properties, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces);

    /**
     * Creates a new policy.
     * 
     * @param properties
     *            the policy properties
     * @param folderId
     *            the folder ID of the parent folder, {@code null} for an
     *            unfiled policy
     * 
     * @return the object ID of the new policy
     * 
     * @see Folder#createPolicy(Map, List, List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createPolicy(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces);

    /**
     * Creates a new policy.
     * 
     * @param properties
     *            the policy properties
     * @param folderId
     *            the folder ID of the parent folder, {@code null} for an
     *            unfiled policy
     * 
     * @return the object ID of the new policy
     * 
     * @see Folder#createPolicy(Map, List, List, List, OperationContext)
     * 
     * @cmis 1.0
     */
    ObjectId createPolicy(Map<String, ?> properties, ObjectId folderId);

    /**
     * Creates a new item.
     * 
     * @param properties
     *            the item properties
     * @param folderId
     *            the folder ID of the parent folder, {@code null} for an
     *            unfiled item
     * 
     * @return the object ID of the new policy
     * 
     * @see Folder#createItem(Map, List, List, List, OperationContext)
     * 
     * @cmis 1.1
     */
    ObjectId createItem(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces);

    /**
     * Creates a new item.
     * 
     * @param properties
     *            the item properties
     * @param folderId
     *            the folder ID of the parent folder, {@code null} for an
     *            unfiled item
     * 
     * @return the object ID of the new item
     * 
     * @see Folder#createItem(Map, List, List, List, OperationContext)
     * 
     * @cmis 1.1
     */
    ObjectId createItem(Map<String, ?> properties, ObjectId folderId);

    /**
     * Creates a new relationship.
     *
     * @param properties
     *            the relationship properties
     *
     * @return the object ID of the new relationship
     * 
     * @cmis 1.0
     */
    ObjectId createRelationship(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces);

    /**
     * Creates a new relationship.
     * 
     * @param properties
     *            the relationship properties
     * 
     * @return the object ID of the new relationship
     * 
     * @cmis 1.0
     */
    ObjectId createRelationship(Map<String, ?> properties);

    /**
     * Fetches the relationships from or to an object from the repository.
     * 
     * @cmis 1.0
     */
    ItemIterable<Relationship> getRelationships(ObjectId objectId, boolean includeSubRelationshipTypes,
            RelationshipDirection relationshipDirection, ObjectType type, OperationContext context);

    /**
     * Updates multiple objects in one request.
     * 
     * @cmis 1.0
     */
    List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(List<CmisObject> objects, Map<String, ?> properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds);

    /**
     * Deletes an object and, if it is a document, all versions in the version
     * series.
     * 
     * @param objectId
     *            the ID of the object
     * 
     * @cmis 1.0
     */
    void delete(ObjectId objectId);

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
    void delete(ObjectId objectId, boolean allVersions);

    /**
     * Deletes an object by path and, if it is a document, all versions in the
     * version series.
     * 
     * @param path
     *            the path of the object
     * 
     * @cmis 1.0
     */
    public void deleteByPath(String path);

    /**
     * Deletes an object by path and, if it is a document, all versions in the
     * version series.
     * 
     * @param parentPath
     *            the path of the parent folder
     * @param name
     *            the (path segment) name of the object in the folder
     * 
     * @cmis 1.0
     */
    void deleteByPath(String parentPath, String name);

    /**
     * Deletes an object by path.
     * 
     * @param path
     *            the path of the object
     * @param allVersions
     *            if this object is a document this parameter defines if only
     *            this version or all versions should be deleted
     * 
     * @cmis 1.0
     */
    void deleteByPath(String path, boolean allVersions);

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
    List<String> deleteTree(ObjectId folderId, boolean allVersions, UnfileObject unfile, boolean continueOnFailure);

    /**
     * Deletes a folder and all subfolders by path.
     * 
     * @param parentPath
     *            the path of the parent folder
     * @param name
     *            the (path segment) name of the folder in the parent folder
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
    List<String> deleteTreebyPath(String parentPath, String name, boolean allVersions, UnfileObject unfile,
            boolean continueOnFailure);

    /**
     * Deletes a folder and all subfolders by path.
     * 
     * @param path
     *            the path of the folder
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
    List<String> deleteTreebyPath(String path, boolean allVersions, UnfileObject unfile, boolean continueOnFailure);

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
    ContentStream getContentStream(ObjectId docId);

    /**
     * Retrieves the content stream of a document.
     * 
     * @param docId
     *            the ID of the document
     * @param streamId
     *            the stream ID or {@code null} for the main stream
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
    ContentStream getContentStream(ObjectId docId, String streamId, BigInteger offset, BigInteger length);

    /**
     * Retrieves the main content stream of a document.
     * 
     * @param path
     *            the path of the document
     * 
     * @return the content stream or {@code null} if the document has no content
     *         stream
     * 
     * @cmis 1.0
     */
    ContentStream getContentStreamByPath(String path);

    /**
     * Retrieves the content stream of a document.
     * 
     * @param path
     *            the path of the document
     * @param streamId
     *            the stream ID or {@code null} for the main stream
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
    ContentStream getContentStreamByPath(String path, String streamId, BigInteger offset, BigInteger length);

    /**
     * Fetches the ACL of an object from the repository.
     * 
     * @param objectId
     *            the ID the object
     * @param onlyBasicPermissions
     *            if {@code true} the repository should express the ACL only
     *            with the basic permissions defined in the CMIS specification;
     *            if {@code false} the repository can express the ACL with basic
     *            and repository specific permissions
     * 
     * @return the ACL of the object
     * 
     * @cmis 1.0
     */
    Acl getAcl(ObjectId objectId, boolean onlyBasicPermissions);

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
    Acl applyAcl(ObjectId objectId, List<Ace> addAces, List<Ace> removeAces, AclPropagation aclPropagation);

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
    Acl setAcl(ObjectId objectId, List<Ace> aces);

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
    void applyPolicy(ObjectId objectId, ObjectId... policyIds);

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
    void removePolicy(ObjectId objectId, ObjectId... policyIds);
}
