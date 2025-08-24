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
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.chemistry.opencmis.client.api.AsyncSession;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;

/**
 * An abstract implementation of the {@link AsyncSession} interface that uses an
 * {@link ExecutorService} object for running asynchronous tasks.
 */
public abstract class AbstractExecutorServiceAsyncSession<E extends ExecutorService> extends AbstractAsyncSession {

    public AbstractExecutorServiceAsyncSession(Session session) {
        super(session);
    }

    /**
     * Returns the {@link ExecutorService} object.
     */
    public abstract E getExecutorService();

    /**
     * A {@link Callable} that has a {@link Session} object.
     */
    public abstract static class SessionCallable<V> implements Callable<V> {

        protected Session session;

        public SessionCallable(Session session) {
            if (session == null) {
                throw new IllegalArgumentException("Session must be set!");
            }

            this.session = session;
        }
    }

    /**
     * Submits a task for execution.
     * 
     * @see ExecutorService#submit(Callable)
     */
    public <T> Future<T> submit(SessionCallable<T> task) {
        return getExecutorService().submit(task);
    }

    // --- types ---

    protected static class GetTypeDefinitonCallable extends SessionCallable<ObjectType> {
        private String typeId;

        public GetTypeDefinitonCallable(Session session, String typeId) {
            super(session);
            this.typeId = typeId;
        }

        @Override
        public ObjectType call() throws Exception {
            return session.getTypeDefinition(typeId);
        }
    }

    @Override
    public Future<ObjectType> getTypeDefinition(String typeId) {
        return submit(new GetTypeDefinitonCallable(session, typeId));
    }

    protected static class CreateTypeCallable extends SessionCallable<ObjectType> {
        private TypeDefinition type;

        public CreateTypeCallable(Session session, TypeDefinition type) {
            super(session);
            this.type = type;
        }

        @Override
        public ObjectType call() throws Exception {
            return session.createType(type);
        }
    }

    @Override
    public Future<ObjectType> createType(TypeDefinition type) {
        return submit(new CreateTypeCallable(session, type));
    }

    protected static class UpdateTypeCallable extends SessionCallable<ObjectType> {
        private TypeDefinition type;

        public UpdateTypeCallable(Session session, TypeDefinition type) {
            super(session);
            this.type = type;
        }

        @Override
        public ObjectType call() throws Exception {
            return session.updateType(type);
        }
    }

    @Override
    public Future<ObjectType> updateType(TypeDefinition type) {
        return submit(new UpdateTypeCallable(session, type));
    }

    protected static class DeleteTypeCallable extends SessionCallable<Object> {
        private String typeId;

        public DeleteTypeCallable(Session session, String typeId) {
            super(session);
            this.typeId = typeId;
        }

        @Override
        public Object call() throws Exception {
            session.deleteType(typeId);
            return null;
        }
    }

    @Override
    public Future<?> deleteType(String typeId) {
        return submit(new DeleteTypeCallable(session, typeId));
    }

    // --- objects ---

    protected static class GetObjectCallable extends SessionCallable<CmisObject> {
        private ObjectId objectId;
        private String objectIdStr;
        private OperationContext context;

        public GetObjectCallable(Session session, ObjectId objectId) {
            this(session, objectId, null);
        }

        public GetObjectCallable(Session session, ObjectId objectId, OperationContext context) {
            super(session);
            this.objectId = objectId;
            this.context = context;
        }

        public GetObjectCallable(Session session, String objectId) {
            this(session, objectId, null);
        }

        public GetObjectCallable(Session session, String objectId, OperationContext context) {
            super(session);
            this.objectIdStr = objectId;
            this.context = context;
        }

        @Override
        public CmisObject call() throws Exception {
            if (objectId != null) {
                if (context != null) {
                    return session.getObject(objectId, context);
                } else {
                    return session.getObject(objectId);
                }
            } else {
                if (context != null) {
                    return session.getObject(objectIdStr, context);
                } else {
                    return session.getObject(objectIdStr);
                }
            }
        }
    }

    @Override
    public Future<CmisObject> getObject(ObjectId objectId, OperationContext context) {
        return submit(new GetObjectCallable(session, objectId, context));
    }

    @Override
    public Future<CmisObject> getObject(String objectId, OperationContext context) {
        return submit(new GetObjectCallable(session, objectId, context));
    }

    protected static class GetObjectByPathCallable extends SessionCallable<CmisObject> {
        private String path;
        private String parentPath;
        private String name;
        private OperationContext context;

        public GetObjectByPathCallable(Session session, String path) {
            this(session, path, (OperationContext) null);
        }

        public GetObjectByPathCallable(Session session, String path, OperationContext context) {
            super(session);
            this.path = path;
            this.context = context;
        }

        public GetObjectByPathCallable(Session session, String parentPath, String name) {
            this(session, parentPath, name, null);
        }

        public GetObjectByPathCallable(Session session, String parentPath, String name, OperationContext context) {
            super(session);
            this.parentPath = parentPath;
            this.name = name;
            this.context = context;
        }

        @Override
        public CmisObject call() throws Exception {
            if (parentPath != null) {
                if (context != null) {
                    return session.getObjectByPath(parentPath, name, context);
                } else {
                    return session.getObjectByPath(parentPath, name);
                }
            } else {
                if (context != null) {
                    return session.getObjectByPath(path, context);
                } else {
                    return session.getObjectByPath(path);
                }
            }
        }
    }

    @Override
    public Future<CmisObject> getObjectByPath(String path, OperationContext context) {
        return submit(new GetObjectByPathCallable(session, path, context));
    }

    @Override
    public Future<CmisObject> getObjectByPath(String parentPath, String name, OperationContext context) {
        return submit(new GetObjectByPathCallable(session, parentPath, name, context));
    }

    protected static class GetLatestDocumentVersionCallable extends SessionCallable<Document> {
        private ObjectId objectId;
        private String objectIdStr;
        private boolean major;
        private OperationContext context;

        public GetLatestDocumentVersionCallable(Session session, ObjectId objectId) {
            this(session, objectId, false, null);
        }

        public GetLatestDocumentVersionCallable(Session session, ObjectId objectId, boolean major,
                OperationContext context) {
            super(session);
            this.objectId = objectId;
            this.major = major;
            this.context = context;
        }

        public GetLatestDocumentVersionCallable(Session session, String objectId) {
            this(session, objectId, false, null);
        }

        public GetLatestDocumentVersionCallable(Session session, String objectId, boolean major,
                OperationContext context) {
            super(session);
            this.objectIdStr = objectId;
            this.major = major;
            this.context = context;
        }

        @Override
        public Document call() throws Exception {
            if (objectId != null) {
                if (context != null) {
                    return session.getLatestDocumentVersion(objectId, major, context);
                } else {
                    return session.getLatestDocumentVersion(objectId);
                }
            } else {
                if (context != null) {
                    return session.getLatestDocumentVersion(objectIdStr, major, context);
                } else {
                    return session.getLatestDocumentVersion(objectIdStr);
                }
            }
        }
    }

    @Override
    public Future<Document> getLatestDocumentVersion(ObjectId objectId, boolean major, OperationContext context) {
        return submit(new GetLatestDocumentVersionCallable(session, objectId, major, context));
    }

    @Override
    public Future<Document> getLatestDocumentVersion(String objectId, boolean major, OperationContext context) {
        return submit(new GetLatestDocumentVersionCallable(session, objectId, major, context));
    }

    // --- create ---

    protected static class CreateDocumentCallable extends SessionCallable<ObjectId> {
        private Map<String, ?> properties;
        private ObjectId folderId;
        private ContentStream contentStream;
        private VersioningState versioningState;
        private List<Policy> policies;
        private List<Ace> addAces;
        private List<Ace> removeAces;

        public CreateDocumentCallable(Session session, Map<String, ?> properties, ObjectId folderId,
                ContentStream contentStream, VersioningState versioningState, List<Policy> policies, List<Ace> addAces,
                List<Ace> removeAces) {
            super(session);
            this.properties = properties;
            this.folderId = folderId;
            this.contentStream = contentStream;
            this.versioningState = versioningState;
            this.policies = policies;
            this.addAces = addAces;
            this.removeAces = removeAces;
        }

        @Override
        public ObjectId call() throws Exception {
            return session.createDocument(properties, folderId, contentStream, versioningState, policies, addAces,
                    removeAces);
        }
    }

    @Override
    public Future<ObjectId> createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
        return submit(new CreateDocumentCallable(session, properties, folderId, contentStream, versioningState,
                policies, addAces, removeAces));
    }

    protected static class CreateDocumentFromSourceCallable extends SessionCallable<ObjectId> {
        private ObjectId source;
        private Map<String, ?> properties;
        private ObjectId folderId;
        private VersioningState versioningState;
        private List<Policy> policies;
        private List<Ace> addAces;
        private List<Ace> removeAces;

        public CreateDocumentFromSourceCallable(Session session, ObjectId source, Map<String, ?> properties,
                ObjectId folderId, VersioningState versioningState, List<Policy> policies, List<Ace> addAces,
                List<Ace> removeAces) {
            super(session);
            this.source = source;
            this.properties = properties;
            this.folderId = folderId;
            this.versioningState = versioningState;
            this.policies = policies;
            this.addAces = addAces;
            this.removeAces = removeAces;
        }

        @Override
        public ObjectId call() throws Exception {
            return session.createDocumentFromSource(source, properties, folderId, versioningState, policies, addAces,
                    removeAces);
        }
    }

    @Override
    public Future<ObjectId> createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
        return submit(new CreateDocumentFromSourceCallable(session, source, properties, folderId, versioningState,
                policies, addAces, removeAces));
    }

    protected static class CreateFolderCallable extends SessionCallable<ObjectId> {

        private Map<String, ?> properties;
        private ObjectId folderId;
        private List<Policy> policies;
        private List<Ace> addAces;
        private List<Ace> removeAces;

        public CreateFolderCallable(Session session, Map<String, ?> properties, ObjectId folderId,
                List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
            super(session);
            this.properties = properties;
            this.folderId = folderId;
            this.policies = policies;
            this.addAces = addAces;
            this.removeAces = removeAces;
        }

        @Override
        public ObjectId call() throws Exception {
            return session.createFolder(properties, folderId, policies, addAces, removeAces);
        }
    }

    @Override
    public Future<ObjectId> createFolder(Map<String, ?> properties, ObjectId folderId, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces) {
        return submit(new CreateFolderCallable(session, properties, folderId, policies, addAces, removeAces));
    }

    protected static class CreatePolicyCallable extends SessionCallable<ObjectId> {
        private Map<String, ?> properties;
        private ObjectId folderId;
        private List<Policy> policies;
        private List<Ace> addAces;
        private List<Ace> removeAces;

        public CreatePolicyCallable(Session session, Map<String, ?> properties, ObjectId folderId,
                List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
            super(session);
            this.properties = properties;
            this.folderId = folderId;
            this.policies = policies;
            this.addAces = addAces;
            this.removeAces = removeAces;
        }

        @Override
        public ObjectId call() throws Exception {
            return session.createPolicy(properties, folderId, policies, addAces, removeAces);
        }
    }

    @Override
    public Future<ObjectId> createPolicy(Map<String, ?> properties, ObjectId folderId, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces) {
        return submit(new CreatePolicyCallable(session, properties, folderId, policies, addAces, removeAces));
    }

    protected static class CreateItemCallable extends SessionCallable<ObjectId> {
        private Map<String, ?> properties;
        private ObjectId folderId;
        private List<Policy> policies;
        private List<Ace> addAces;
        private List<Ace> removeAces;

        public CreateItemCallable(Session session, Map<String, ?> properties, ObjectId folderId, List<Policy> policies,
                List<Ace> addAces, List<Ace> removeAces) {
            super(session);
            this.properties = properties;
            this.folderId = folderId;
            this.policies = policies;
            this.addAces = addAces;
            this.removeAces = removeAces;
        }

        @Override
        public ObjectId call() throws Exception {
            return session.createItem(properties, folderId, policies, addAces, removeAces);
        }
    }

    @Override
    public Future<ObjectId> createItem(Map<String, ?> properties, ObjectId folderId, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces) {
        return submit(new CreateItemCallable(session, properties, folderId, policies, addAces, removeAces));
    }

    protected static class CreateRelationshipCallable extends SessionCallable<ObjectId> {
        private Map<String, ?> properties;
        private List<Policy> policies;
        private List<Ace> addAces;
        private List<Ace> removeAces;

        public CreateRelationshipCallable(Session session, Map<String, ?> properties, List<Policy> policies,
                List<Ace> addAces, List<Ace> removeAces) {
            super(session);
            this.properties = properties;
            this.policies = policies;
            this.addAces = addAces;
            this.removeAces = removeAces;
        }

        @Override
        public ObjectId call() throws Exception {
            return session.createRelationship(properties, policies, addAces, removeAces);
        }
    }

    @Override
    public Future<ObjectId> createRelationship(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces) {
        return submit(new CreateRelationshipCallable(session, properties, policies, addAces, removeAces));
    }

    // --- content ---

    protected static class GetContentStreamCallable extends SessionCallable<ContentStream> {
        private ObjectId docId;
        private String streamId;
        private BigInteger offset;
        private BigInteger length;

        public GetContentStreamCallable(Session session, ObjectId docId, String streamId, BigInteger offset,
                BigInteger length) {
            super(session);
            this.docId = docId;
            this.streamId = streamId;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public ContentStream call() throws Exception {
            return session.getContentStream(docId, streamId, offset, length);
        }
    }

    @Override
    public Future<ContentStream> getContentStream(ObjectId docId, String streamId, BigInteger offset, BigInteger length) {
        return submit(new GetContentStreamCallable(session, docId, streamId, offset, length));
    }

    protected static class StoreContentStreamCallable extends GetContentStreamCallable {
        private OutputStream target;

        public StoreContentStreamCallable(Session session, ObjectId docId, String streamId, BigInteger offset,
                BigInteger length, OutputStream target) {
            super(session, docId, streamId, offset, length);
            this.target = target;
        }

        @Override
        public ContentStream call() throws Exception {
            ContentStream contentStream = super.call();
            try {
                if (contentStream != null && contentStream.getStream() != null && target != null) {
                    IOUtils.copy(contentStream.getStream(), target);
                }
            } finally {
                IOUtils.closeQuietly(contentStream);
            }

            return contentStream;
        }
    }

    @Override
    public Future<ContentStream> storeContentStream(ObjectId docId, String streamId, BigInteger offset,
            BigInteger length, OutputStream target) {
        return submit(new StoreContentStreamCallable(session, docId, streamId, offset, length, target));
    }

    // --- delete ---

    protected static class DeleteCallable extends SessionCallable<Object> {
        private ObjectId objectId;
        private boolean allVersions;

        public DeleteCallable(Session session, ObjectId objectId, boolean allVersions) {
            super(session);
            this.objectId = objectId;
            this.allVersions = allVersions;
        }

        @Override
        public Object call() throws Exception {
            session.delete(objectId, allVersions);
            return null;
        }
    }

    @Override
    public Future<?> delete(ObjectId objectId, boolean allVersions) {
        return submit(new DeleteCallable(session, objectId, allVersions));
    }

    protected static class DeleteTreeCallable extends SessionCallable<List<String>> {
        private ObjectId folderId;
        private boolean allVersions;
        private UnfileObject unfile;
        private boolean continueOnFailure;

        public DeleteTreeCallable(Session session, ObjectId folderId, boolean allVersions, UnfileObject unfile,
                boolean continueOnFailure) {
            super(session);
            this.folderId = folderId;
            this.allVersions = allVersions;
            this.unfile = unfile;
            this.continueOnFailure = continueOnFailure;
        }

        @Override
        public List<String> call() throws Exception {
            return session.deleteTree(folderId, allVersions, unfile, continueOnFailure);
        }
    }

    @Override
    public Future<List<String>> deleteTree(ObjectId folderId, boolean allVersions, UnfileObject unfile,
            boolean continueOnFailure) {
        return submit(new DeleteTreeCallable(session, folderId, allVersions, unfile, continueOnFailure));
    }

    // --- ACL ---

    protected static class ApplyAclCallable extends SessionCallable<Acl> {
        private ObjectId objectId;
        private List<Ace> addAces;
        private List<Ace> removeAces;
        private AclPropagation aclPropagation;

        public ApplyAclCallable(Session session, ObjectId objectId, List<Ace> addAces, List<Ace> removeAces,
                AclPropagation aclPropagation) {
            super(session);
            this.objectId = objectId;
            this.addAces = addAces;
            this.removeAces = removeAces;
            this.aclPropagation = aclPropagation;
        }

        @Override
        public Acl call() throws Exception {
            return session.applyAcl(objectId, addAces, removeAces, aclPropagation);
        }
    }

    @Override
    public Future<Acl> applyAcl(ObjectId objectId, List<Ace> addAces, List<Ace> removeAces,
            AclPropagation aclPropagation) {
        return getExecutorService()
                .submit(new ApplyAclCallable(session, objectId, addAces, removeAces, aclPropagation));
    }

    protected static class SetAclCallable extends SessionCallable<Acl> {
        private ObjectId objectId;
        private List<Ace> aces;

        public SetAclCallable(Session session, ObjectId objectId, List<Ace> aces) {
            super(session);
            this.objectId = objectId;
            this.aces = aces;
        }

        @Override
        public Acl call() throws Exception {
            return session.setAcl(objectId, aces);
        }
    }

    @Override
    public Future<Acl> setAcl(ObjectId objectId, List<Ace> aces) {
        return submit(new SetAclCallable(session, objectId, aces));
    }

    // --- policy ---

    protected static class ApplyPolicyCallable extends SessionCallable<Object> {
        private ObjectId objectId;
        private ObjectId[] policyIds;

        public ApplyPolicyCallable(Session session, ObjectId objectId, ObjectId... policyIds) {
            super(session);
            this.objectId = objectId;
            this.policyIds = policyIds;
        }

        @Override
        public Object call() throws Exception {
            session.applyPolicy(objectId, policyIds);
            return null;
        }
    }

    @Override
    public Future<?> applyPolicy(ObjectId objectId, ObjectId... policyIds) {
        return submit(new ApplyPolicyCallable(session, objectId, policyIds));
    }

    protected static class RemovePolicyCallable extends SessionCallable<Object> {
        private ObjectId objectId;
        private ObjectId[] policyIds;

        public RemovePolicyCallable(Session session, ObjectId objectId, ObjectId... policyIds) {
            super(session);
            this.objectId = objectId;
            this.policyIds = policyIds;
        }

        @Override
        public Object call() throws Exception {
            session.removePolicy(objectId, policyIds);
            return null;
        }
    }

    @Override
    public Future<?> removePolicy(ObjectId objectId, ObjectId... policyIds) {
        return submit(new RemovePolicyCallable(session, objectId, policyIds));
    }

    // --- shut down ---

    /**
     * @see ExecutorService#shutdown()
     */
    public void shutdown() {
        if (getExecutorService() != null) {
            getExecutorService().shutdown();
        }
    }

    /**
     * @see ExecutorService#shutdownNow()
     */
    public List<Runnable> shutdownNow() {
        if (getExecutorService() != null) {
            return getExecutorService().shutdownNow();
        }

        return Collections.emptyList();
    }

    /**
     * @see ExecutorService#isShutdown()
     */
    public boolean isShutdown() {
        if (getExecutorService() != null) {
            return getExecutorService().isShutdown();
        }

        return true;
    }

    /**
     * @see ExecutorService#isTerminated()
     */
    public boolean isTerminated() {
        if (getExecutorService() != null) {
            return getExecutorService().isTerminated();
        }

        return true;
    }

    /**
     * @see ExecutorService#awaitTermination(long, TimeUnit)
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (getExecutorService() != null) {
            return getExecutorService().awaitTermination(timeout, unit);
        }

        return true;
    }
}
