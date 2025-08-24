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
package org.apache.chemistry.opencmis.client.runtime.async;

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.chemistry.opencmis.client.api.AsyncSession;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;

/**
 * An abstract implementation of the {@link AsyncSession} interface providing
 * convenience implementations.
 */
public abstract class AbstractAsyncSession implements AsyncSession {

    protected Session session;

    public AbstractAsyncSession(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("Session must be set!");
        }

        this.session = session;
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public Future<CmisObject> getObject(ObjectId objectId) {
        return getObject(objectId, session.getDefaultContext());
    }

    @Override
    public Future<CmisObject> getObject(String objectId) {
        return getObject(objectId, session.getDefaultContext());
    }

    @Override
    public Future<CmisObject> getObjectByPath(String path) {
        return getObjectByPath(path, session.getDefaultContext());
    }

    @Override
    public Future<CmisObject> getObjectByPath(String parentPath, String name) {
        return getObjectByPath(parentPath, name, session.getDefaultContext());
    }

    @Override
    public Future<Document> getLatestDocumentVersion(ObjectId objectId) {
        return getLatestDocumentVersion(objectId, session.getDefaultContext());
    }

    @Override
    public Future<Document> getLatestDocumentVersion(ObjectId objectId, OperationContext context) {
        return getLatestDocumentVersion(objectId, false, context);
    }

    @Override
    public Future<Document> getLatestDocumentVersion(String objectId) {
        return getLatestDocumentVersion(objectId, session.getDefaultContext());
    }

    @Override
    public Future<Document> getLatestDocumentVersion(String objectId, OperationContext context) {
        return getLatestDocumentVersion(objectId, false, context);
    }

    @Override
    public Future<ObjectId> createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState) {
        return createDocument(properties, folderId, contentStream, versioningState, null, null, null);
    }

    @Override
    public Future<ObjectId> createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState) {
        return createDocumentFromSource(source, properties, folderId, versioningState, null, null, null);
    }

    @Override
    public Future<ObjectId> createFolder(Map<String, ?> properties, ObjectId folderId) {
        return createFolder(properties, folderId, null, null, null);
    }

    @Override
    public Future<ObjectId> createPolicy(Map<String, ?> properties, ObjectId folderId) {
        return createPolicy(properties, folderId, null, null, null);
    }

    @Override
    public Future<ObjectId> createItem(Map<String, ?> properties, ObjectId folderId) {
        return createItem(properties, folderId, null, null, null);
    }

    @Override
    public Future<ObjectId> createRelationship(Map<String, ?> properties) {
        return createRelationship(properties, null, null, null);
    }

    @Override
    public Future<ContentStream> getContentStream(ObjectId docId) {
        return getContentStream(docId, null, null, null);
    }

    @Override
    public Future<ContentStream> storeContentStream(ObjectId docId, OutputStream target) {
        return storeContentStream(docId, null, null, null, target);
    }

    @Override
    public Future<?> delete(ObjectId objectId) {
        return delete(objectId, true);
    }
}
