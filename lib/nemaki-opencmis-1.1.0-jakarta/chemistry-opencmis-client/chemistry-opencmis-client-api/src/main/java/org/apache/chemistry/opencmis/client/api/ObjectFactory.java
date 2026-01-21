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

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

/**
 * A factory to create and convert CMIS objects.
 * 
 * Custom {@link ObjectFactory} implementations may use the convert methods to
 * inject specific implementations of the interfaces when the data is transfered
 * from the low level API to the high level API.
 * 
 * @see org.apache.chemistry.opencmis.client.api.Session#getObjectFactory()
 */
public interface ObjectFactory {

    void initialize(Session session, Map<String, String> parameters);

    // repository info

    RepositoryInfo convertRepositoryInfo(RepositoryInfo repositoryInfo);

    // ACL and ACE

    Acl convertAces(List<Ace> aces);

    Acl createAcl(List<Ace> aces);

    Ace createAce(String principal, List<String> permissions);

    // policies

    List<String> convertPolicies(List<Policy> policies);

    // renditions

    Rendition convertRendition(String objectId, RenditionData rendition);

    // content stream

    /**
     * Creates an object that implements the {@link ContentStream} interface.
     * 
     * @param filename
     *            the filename, should be set
     * @param length
     *            the length of the stream or -1 if the length is unknown
     * @param mimetype
     *            the MIME type, if unknown "application/octet-stream" should be
     *            used
     * @param stream
     *            the stream, should not be <code>null</code>
     * 
     * @return the {@link ContentStream} object
     */
    ContentStream createContentStream(String filename, long length, String mimetype, InputStream stream);

    /**
     * Creates an object that implements the {@link ContentStream} interface.
     * 
     * @param filename
     *            the filename, should be set
     * @param length
     *            the length of the stream or -1 if the length is unknown
     * @param mimetype
     *            the MIME type, if unknown "application/octet-stream" should be
     *            used
     * @param stream
     *            the stream, should not be <code>null</code>
     * @param partial
     *            if <code>false</code> the stream represents the full content,
     *            if <code>true</code> the stream is only a part of the content
     * 
     * @return the {@link ContentStream} object
     */
    ContentStream createContentStream(String filename, long length, String mimetype, InputStream stream, boolean partial);

    /**
     * Converts a high level {@link ContentStream} object into a low level
     * {@link ContentStream} object.
     * 
     * @param contentStream
     *            the original {@link ContentStream} object
     * @return the {@link ContentStream} object
     */
    ContentStream convertContentStream(ContentStream contentStream);

    // types

    ObjectType convertTypeDefinition(TypeDefinition typeDefinition);

    ObjectType getTypeFromObjectData(ObjectData objectData);

    // properties

    <T> Property<T> createProperty(PropertyDefinition<T> type, List<T> values);

    Map<String, Property<?>> convertProperties(ObjectType objectType, Collection<SecondaryType> secondaryTypes,
            Properties properties);

    Properties convertProperties(Map<String, ?> properties, ObjectType type, Collection<SecondaryType> secondaryTypes,
            Set<Updatability> updatabilityFilter);

    List<PropertyData<?>> convertQueryProperties(Properties properties);

    // objects

    CmisObject convertObject(ObjectData objectData, OperationContext context);

    QueryResult convertQueryResult(ObjectData objectData);

    ChangeEvent convertChangeEvent(ObjectData objectData);

    ChangeEvents convertChangeEvents(String changeLogToken, ObjectList objectList);
}
