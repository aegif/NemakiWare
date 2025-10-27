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
package org.apache.chemistry.opencmis.client.runtime;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.DocumentType;
import org.apache.chemistry.opencmis.client.api.ObjectFactory;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.bindings.spi.LinkAccess;
import org.apache.chemistry.opencmis.client.runtime.util.AppendOutputStream;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ContentStreamHash;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PartialContentStream;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamHashImpl;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public class DocumentImpl extends AbstractFilableCmisObject implements Document {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public DocumentImpl(SessionImpl session, ObjectType objectType, ObjectData objectData, OperationContext context) {
        initialize(session, objectType, objectData, context);
    }

    @Override
    public DocumentType getDocumentType() {
        ObjectType objectType = super.getType();
        if (objectType instanceof DocumentType) {
            return (DocumentType) objectType;
        } else {
            throw new ClassCastException("Object type is not a document type.");
        }
    }

    @Override
    public boolean isVersionable() {
        return Boolean.TRUE.equals(getDocumentType().isVersionable());
    }

    @Override
    public Boolean isVersionSeriesPrivateWorkingCopy() {
        if (Boolean.FALSE.equals(getDocumentType().isVersionable())) {
            return false;
        }

        Boolean isCheckedOut = isVersionSeriesCheckedOut();
        if (Boolean.FALSE.equals(isCheckedOut)) {
            return false;
        }

        Boolean isPWC = isPrivateWorkingCopy();
        if (isPWC != null) {
            return isPWC;
        }

        String vsCoId = getVersionSeriesCheckedOutId();
        if (vsCoId == null) {
            // we don't know ...
            return null;
        }

        return vsCoId.equals(getId());
    }

    // properties

    @Override
    public String getCheckinComment() {
        return getPropertyValue(PropertyIds.CHECKIN_COMMENT);
    }

    @Override
    public String getVersionLabel() {
        return getPropertyValue(PropertyIds.VERSION_LABEL);
    }

    @Override
    public String getVersionSeriesId() {
        return getPropertyValue(PropertyIds.VERSION_SERIES_ID);
    }

    @Override
    public String getVersionSeriesCheckedOutId() {
        return getPropertyValue(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID);
    }

    @Override
    public String getVersionSeriesCheckedOutBy() {
        return getPropertyValue(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY);
    }

    @Override
    public Boolean isImmutable() {
        return getPropertyValue(PropertyIds.IS_IMMUTABLE);
    }

    @Override
    public Boolean isLatestMajorVersion() {
        return getPropertyValue(PropertyIds.IS_LATEST_MAJOR_VERSION);
    }

    @Override
    public Boolean isLatestVersion() {
        return getPropertyValue(PropertyIds.IS_LATEST_VERSION);
    }

    @Override
    public Boolean isMajorVersion() {
        return getPropertyValue(PropertyIds.IS_MAJOR_VERSION);
    }

    @Override
    public Boolean isPrivateWorkingCopy() {
        return getPropertyValue(PropertyIds.IS_PRIVATE_WORKING_COPY);
    }

    @Override
    public Boolean isVersionSeriesCheckedOut() {
        return getPropertyValue(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
    }

    @Override
    public long getContentStreamLength() {
        BigInteger bigInt = getPropertyValue(PropertyIds.CONTENT_STREAM_LENGTH);
        return bigInt == null ? (long) -1 : bigInt.longValue();
    }

    @Override
    public String getContentStreamMimeType() {
        return getPropertyValue(PropertyIds.CONTENT_STREAM_MIME_TYPE);
    }

    @Override
    public String getContentStreamFileName() {
        return getPropertyValue(PropertyIds.CONTENT_STREAM_FILE_NAME);
    }

    @Override
    public String getContentStreamId() {
        return getPropertyValue(PropertyIds.CONTENT_STREAM_ID);
    }

    @Override
    public List<ContentStreamHash> getContentStreamHashes() {
        List<String> hashes = getPropertyValue(PropertyIds.CONTENT_STREAM_HASH);
        if (isNullOrEmpty(hashes)) {
            return null;
        }

        List<ContentStreamHash> result = new ArrayList<ContentStreamHash>(hashes.size());
        for (String hash : hashes) {
            result.add(new ContentStreamHashImpl(hash));
        }

        return result;
    }

    @Override
    public String getLatestAccessibleStateId() {
        return getPropertyValue(PropertyIds.LATEST_ACCESSIBLE_STATE_ID);
    }

    // operations

    @Override
    public Document copy(ObjectId targetFolderId, Map<String, ?> properties, VersioningState versioningState,
            List<Policy> policies, List<Ace> addAces, List<Ace> removeAces, OperationContext context) {

        ObjectId newId = null;
        try {
            newId = getSession().createDocumentFromSource(this, properties, targetFolderId, versioningState, policies,
                    addAces, removeAces);
        } catch (CmisNotSupportedException nse) {
            newId = copyViaClient(targetFolderId, properties, versioningState, policies, addAces, removeAces);
        }

        // if no context is provided the object will not be fetched
        if (context == null || newId == null) {
            return null;
        }
        // get the new object
        CmisObject object = getSession().getObject(newId, context);
        if (!(object instanceof Document)) {
            throw new CmisRuntimeException("Newly created object is not a document! New id: " + newId);
        }

        return (Document) object;
    }

    @Override
    public Document copy(ObjectId targetFolderId) {
        return copy(targetFolderId, null, null, null, null, null, getSession().getDefaultContext());
    }

    /**
     * Copies the document manually. The content is streamed from the repository
     * and back.
     */
    protected ObjectId copyViaClient(ObjectId targetFolderId, Map<String, ?> properties,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
        Map<String, Object> newProperties = new HashMap<String, Object>();

        OperationContext allPropsContext = getSession().createOperationContext();
        allPropsContext.setFilterString("*");
        allPropsContext.setIncludeAcls(false);
        allPropsContext.setIncludeAllowableActions(false);
        allPropsContext.setIncludePathSegments(false);
        allPropsContext.setIncludePolicies(false);
        allPropsContext.setIncludeRelationships(IncludeRelationships.NONE);
        allPropsContext.setRenditionFilterString("cmis:none");

        Document allPropsDoc = (Document) getSession().getObject(this, allPropsContext);

        for (Property<?> prop : allPropsDoc.getProperties()) {
            if (prop.getDefinition().getUpdatability() == Updatability.READWRITE
                    || prop.getDefinition().getUpdatability() == Updatability.ONCREATE) {
                newProperties.put(prop.getId(), prop.getValue());
            }
        }

        if (properties != null) {
            newProperties.putAll(properties);
        }

        ContentStream contentStream = allPropsDoc.getContentStream();
        try {
            return getSession().createDocument(newProperties, targetFolderId, contentStream, versioningState, policies,
                    addAces, removeAces);
        } finally {
            if (contentStream != null) {
                InputStream stream = contentStream.getStream();
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ioe) {
                        throw new CmisRuntimeException("Cannot close source stream!", ioe);
                    }
                }
            }
        }
    }

    @Override
    public void deleteAllVersions() {
        delete(true);
    }

    // versioning

    @Override
    public ObjectId checkOut() {
        String newObjectId = null;

        readLock();
        try {
            String objectId = getObjectId();
            Holder<String> objectIdHolder = new Holder<String>(objectId);

            getBinding().getVersioningService().checkOut(getRepositoryId(), objectIdHolder, null, null);
            newObjectId = objectIdHolder.getValue();
        } finally {
            readUnlock();
        }

        // remove original version from cache, the path and a few versioning
        // properties are not valid anymore
        getSession().removeObjectFromCache(this);

        if (newObjectId == null) {
            return null;
        }

        return getSession().createObjectId(newObjectId);
    }

    @Override
    public void cancelCheckOut() {
        String objectId = getObjectId();

        getBinding().getVersioningService().cancelCheckOut(getRepositoryId(), objectId, null);

        // remove PWC from cache, it doesn't exist anymore
        getSession().removeObjectFromCache(this);
    }

    @Override
    public ObjectId checkIn(boolean major, Map<String, ?> properties, ContentStream contentStream,
            String checkinComment, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
        String newObjectId = null;

        readLock();
        try {
            Holder<String> objectIdHolder = new Holder<String>(getObjectId());

            ObjectFactory of = getObjectFactory();

            Set<Updatability> updatebility = EnumSet.noneOf(Updatability.class);
            updatebility.add(Updatability.READWRITE);
            updatebility.add(Updatability.WHENCHECKEDOUT);

            getBinding().getVersioningService().checkIn(getRepositoryId(), objectIdHolder, major,
                    of.convertProperties(properties, getType(), getSecondaryTypes(), updatebility),
                    of.convertContentStream(contentStream), checkinComment, of.convertPolicies(policies),
                    of.convertAces(addAces), of.convertAces(removeAces), null);

            newObjectId = objectIdHolder.getValue();
        } finally {
            readUnlock();
        }

        // remove PWC from cache, it doesn't exist anymore
        getSession().removeObjectFromCache(this);

        if (newObjectId == null) {
            return null;
        }

        return getSession().createObjectId(newObjectId);
    }

    @Override
    public List<Document> getAllVersions() {
        return getAllVersions(getSession().getDefaultContext());
    }

    @Override
    public List<Document> getAllVersions(OperationContext context) {
        String objectId;
        String versionSeriesId;

        readLock();
        try {
            objectId = getObjectId();
            versionSeriesId = getVersionSeriesId();
        } finally {
            readUnlock();
        }

        List<ObjectData> versions = getBinding().getVersioningService().getAllVersions(getRepositoryId(), objectId,
                versionSeriesId, context.getFilterString(), context.isIncludeAllowableActions(), null);

        ObjectFactory objectFactory = getSession().getObjectFactory();

        List<Document> result = new ArrayList<Document>();
        if (versions != null) {
            for (ObjectData objectData : versions) {
                CmisObject doc = objectFactory.convertObject(objectData, context);
                if (!(doc instanceof Document)) {
                    // should not happen...
                    continue;
                }

                result.add((Document) doc);
            }
        }

        return result;

    }

    @Override
    public Document getObjectOfLatestVersion(boolean major) {
        return getObjectOfLatestVersion(major, getSession().getDefaultContext());
    }

    @Override
    public Document getObjectOfLatestVersion(boolean major, OperationContext context) {
        return getSession().getLatestDocumentVersion(this, major, context);
    }

    // content operations

    @Override
    public ContentStream getContentStream() {
        return getContentStream(null, null, null);
    }

    @Override
    public ContentStream getContentStream(BigInteger offset, BigInteger length) {
        return getContentStream(null, offset, length);
    }

    @Override
    public ContentStream getContentStream(String streamId) {
        return getContentStream(streamId, null, null);
    }

    @Override
    public ContentStream getContentStream(String streamId, BigInteger offset, BigInteger length) {
        // get the stream
        ContentStream contentStream = getSession().getContentStream(this, streamId, offset, length);

        if (contentStream == null) {
            return null;
        }

        // the AtomPub binding doesn't return a file name
        // -> get the file name from properties, if present
        String filename = contentStream.getFileName();
        if (filename == null) {
            filename = getContentStreamFileName();
        }

        long lengthLong = (contentStream.getBigLength() == null ? -1 : contentStream.getBigLength().longValue());

        // convert and return stream object
        return getSession().getObjectFactory().createContentStream(filename, lengthLong, contentStream.getMimeType(),
                contentStream.getStream(), contentStream instanceof PartialContentStream);
    }

    @Override
    public String getContentUrl() {
        return getContentUrl(null);
    }

    @Override
    public String getContentUrl(String streamId) {
        if (getBinding().getObjectService() instanceof LinkAccess) {
            LinkAccess linkAccess = (LinkAccess) getBinding().getObjectService();

            if (streamId == null) {
                return linkAccess.loadContentLink(getRepositoryId(), getId());
            } else {
                return linkAccess.loadRenditionContentLink(getRepositoryId(), getId(), streamId);
            }
        }

        return null;
    }

    @Override
    public Document setContentStream(ContentStream contentStream, boolean overwrite) {
        ObjectId objectId = setContentStream(contentStream, overwrite, true);
        if (objectId == null) {
            return null;
        }

        if (!getObjectId().equals(objectId.getId())) {
            return (Document) getSession().getObject(objectId, getCreationContext());
        }

        return this;
    }

    @Override
    public ObjectId setContentStream(ContentStream contentStream, boolean overwrite, boolean refresh) {
        String newObjectId = null;

        readLock();
        try {
            Holder<String> objectIdHolder = new Holder<String>(getObjectId());
            Holder<String> changeTokenHolder = new Holder<String>((String) getPropertyValue(PropertyIds.CHANGE_TOKEN));

            getBinding().getObjectService().setContentStream(getRepositoryId(), objectIdHolder, overwrite,
                    changeTokenHolder, getObjectFactory().convertContentStream(contentStream), null);

            newObjectId = objectIdHolder.getValue();
        } finally {
            readUnlock();
        }

        if (refresh) {
            refresh();
        }

        if (newObjectId == null) {
            return null;
        }

        return getSession().createObjectId(newObjectId);
    }

    @Override
    public Document appendContentStream(ContentStream contentStream, boolean isLastChunk) {
        if (getSession().getRepositoryInfo().getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }

        ObjectId objectId = appendContentStream(contentStream, isLastChunk, true);
        if (objectId == null) {
            return null;
        }

        if (!getObjectId().equals(objectId.getId())) {
            return (Document) getSession().getObject(objectId, getCreationContext());
        }

        return this;
    }

    @Override
    public ObjectId appendContentStream(ContentStream contentStream, boolean isLastChunk, boolean refresh) {
        if (getSession().getRepositoryInfo().getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }

        String newObjectId = null;

        readLock();
        try {
            Holder<String> objectIdHolder = new Holder<String>(getObjectId());
            Holder<String> changeTokenHolder = new Holder<String>((String) getPropertyValue(PropertyIds.CHANGE_TOKEN));

            getBinding().getObjectService().appendContentStream(getRepositoryId(), objectIdHolder, changeTokenHolder,
                    getObjectFactory().convertContentStream(contentStream), isLastChunk, null);

            newObjectId = objectIdHolder.getValue();
        } finally {
            readUnlock();
        }

        if (refresh) {
            refresh();
        }

        if (newObjectId == null) {
            return null;
        }

        return getSession().createObjectId(newObjectId);
    }

    @Override
    public Document deleteContentStream() {
        ObjectId objectId = deleteContentStream(true);
        if (objectId == null) {
            return null;
        }

        if (!getObjectId().equals(objectId.getId())) {
            return (Document) getSession().getObject(objectId, getCreationContext());
        }

        return this;
    }

    @Override
    public ObjectId deleteContentStream(boolean refresh) {
        String newObjectId = null;

        readLock();
        try {
            Holder<String> objectIdHolder = new Holder<String>(getObjectId());
            Holder<String> changeTokenHolder = new Holder<String>((String) getPropertyValue(PropertyIds.CHANGE_TOKEN));

            getBinding().getObjectService().deleteContentStream(getRepositoryId(), objectIdHolder, changeTokenHolder,
                    null);

            newObjectId = objectIdHolder.getValue();
        } finally {
            readUnlock();
        }

        if (refresh) {
            refresh();
        }

        if (newObjectId == null) {
            return null;
        }

        return getSession().createObjectId(newObjectId);
    }

    @Override
    public OutputStream createOverwriteOutputStream(String filename, String mimeType) {
        if (getSession().getRepositoryInfo().getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }

        return new AppendOutputStream(getSession(), this, true, filename, mimeType);
    }

    @Override
    public OutputStream createOverwriteOutputStream(String filename, String mimeType, int bufferSize) {
        if (getSession().getRepositoryInfo().getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }

        return new AppendOutputStream(getSession(), this, true, filename, mimeType, bufferSize);
    }

    @Override
    public OutputStream createAppendOutputStream() {
        if (getSession().getRepositoryInfo().getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }

        return new AppendOutputStream(getSession(), this, false, null, null);
    }

    @Override
    public OutputStream createAppendOutputStream(int bufferSize) {
        if (getSession().getRepositoryInfo().getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }

        return new AppendOutputStream(getSession(), this, false, null, null, bufferSize);
    }

    @Override
    public ObjectId checkIn(boolean major, Map<String, ?> properties, ContentStream contentStream,
            String checkinComment) {
        return this.checkIn(major, properties, contentStream, checkinComment, null, null, null);
    }
}
