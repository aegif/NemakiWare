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

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;

/**
 * CMIS document interface.
 * 
 * @cmis 1.0
 */
public interface Document extends FileableCmisObject, DocumentProperties {

    /**
     * Returns the object type as a document type.
     * 
     * @return the document type
     * 
     * @throws ClassCastException
     *             if the object type is not a document type
     * 
     * @cmis 1.0
     */
    DocumentType getDocumentType();

    /**
     * Returns whether the document is versionable or not.
     * 
     * @return {@code true} if the document is versionable, {@code false} if the
     *         document is not versionable or if it's unknown
     * 
     * @cmis 1.0
     */
    boolean isVersionable();

    /**
     * Determines whether this document is the PWC in the version series or not.
     * 
     * The evaluation is based on the properties
     * {@code cmis:isVersionSeriesCheckedOut},
     * {@code cmis:isPrivateWorkingCopy}, and
     * {@code cmis:versionSeriesCheckedOutId} and works for all CMIS versions.
     * 
     * @return {@code true} if it is the PWC, {@code false} if it is not the
     *         PWC, or {@code null} if it can't be determined
     * 
     * @see DocumentProperties#isPrivateWorkingCopy()
     * 
     * @cmis 1.0
     */
    Boolean isVersionSeriesPrivateWorkingCopy();

    // object service

    /**
     * Deletes this document and all its versions.
     * 
     * @cmis 1.0
     */
    void deleteAllVersions();

    /**
     * Retrieves the content stream of this document.
     * 
     * @return the content stream, or {@code null} if the document has no
     *         content
     * 
     * @cmis 1.0
     */
    ContentStream getContentStream();

    /**
     * Retrieves the content stream of this document.
     * 
     * @param offset
     *            the offset of the stream or {@code null} to read the stream
     *            from the beginning
     * @param length
     *            the maximum length of the stream or {@code null} to read to
     *            the end of the stream
     * 
     * @return the content stream, or {@code null} if the document has no
     *         content
     * 
     * @cmis 1.0
     */
    ContentStream getContentStream(BigInteger offset, BigInteger length);

    /**
     * Retrieves the content stream that is associated with the given stream ID.
     * This is usually a rendition of the document.
     * 
     * @param streamId
     *            the stream ID
     * 
     * @return the content stream, or {@code null} if no content is associated
     *         with this stream ID
     * 
     * @cmis 1.0
     */
    ContentStream getContentStream(String streamId);

    /**
     * Retrieves the content stream that is associated with the given stream ID.
     * This is usually a rendition of the document.
     * 
     * @param streamId
     *            the stream ID
     * @param offset
     *            the offset of the stream or {@code null} to read the stream
     *            from the beginning
     * @param length
     *            the maximum length of the stream or {@code null} to read to
     *            the end of the stream
     * 
     * @return the content stream, or {@code null} if no content is associated
     *         with this stream ID
     * 
     * @cmis 1.0
     */
    ContentStream getContentStream(String streamId, BigInteger offset, BigInteger length);

    /**
     * Returns the content URL of the document if the binding supports content
     * URLs.
     * 
     * Depending on the repository and the binding, the server might not return
     * the content but an error message. Authentication data is not attached.
     * That is, a user may have to re-authenticate to get the content.
     * 
     * @return the content URL of the document or {@code null} if the binding
     *         does not support content URLs
     */
    public String getContentUrl();

    /**
     * Returns the content URL of the document or a rendition if the binding
     * supports content URLs.
     * 
     * Depending on the repository and the binding, the server might not return
     * the content but an error message. Authentication data is not attached.
     * That is, a user may have to re-authenticate to get the content.
     * 
     * @param streamId
     *            the ID of the rendition or {@code null} for the document
     * 
     * @return the content URL of the document or rendition or {@code null} if
     *         the binding does not support content URLs
     */
    public String getContentUrl(String streamId);

    /**
     * Sets a new content stream for the document and refreshes this object
     * afterwards. If the repository created a new version, this new document is
     * returned. Otherwise the current document is returned.
     * <p>
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @param contentStream
     *            the content stream
     * @param overwrite
     *            if this parameter is set to {@code false} and the document
     *            already has content, the repository throws a
     *            {@link CmisContentAlreadyExistsException}
     * 
     * @return the updated document, or {@code null} if the repository did not
     *         return an object ID
     * 
     * @see ObjectFactory#createContentStream(String, long, String,
     *      java.io.InputStream)
     * 
     * @cmis 1.0
     */
    Document setContentStream(ContentStream contentStream, boolean overwrite);

    /**
     * Sets a new content stream for the document. If the repository created a
     * new version, the object ID of this new version is returned. Otherwise the
     * object ID of the current document is returned.
     * <p>
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @param contentStream
     *            the content stream
     * @param overwrite
     *            if this parameter is set to {@code false} and the document
     *            already has content, the repository throws a
     *            {@link CmisContentAlreadyExistsException}
     * @param refresh
     *            if this parameter is set to {@code true}, this object will be
     *            refreshed after the new content has been set
     * 
     * @return the updated object ID, or {@code null} if the repository did not
     *         return an object ID
     * 
     * @see ObjectFactory#createContentStream(String, long, String,
     *      java.io.InputStream)
     * 
     * @cmis 1.0
     */
    ObjectId setContentStream(ContentStream contentStream, boolean overwrite, boolean refresh);

    /**
     * Appends a content stream to the content stream of the document and
     * refreshes this object afterwards. If the repository created a new
     * version, this new document is returned. Otherwise the current document is
     * returned.
     * <p>
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @param contentStream
     *            the content stream
     * @param isLastChunk
     *            indicates if this stream is the last chunk of the content
     * 
     * @return the updated document, or {@code null} if the repository did not
     *         return an object ID
     * 
     * @see ObjectFactory#createContentStream(String, long, String,
     *      java.io.InputStream)
     * 
     * @cmis 1.1
     */
    Document appendContentStream(ContentStream contentStream, boolean isLastChunk);

    /**
     * Appends a content stream to the content stream of the document. If the
     * repository created a new version, the object ID of this new version is
     * returned. Otherwise the object ID of the current document is returned.
     * <p>
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @param contentStream
     *            the content stream
     * @param isLastChunk
     *            indicates if this stream is the last chunk of the content
     * @param refresh
     *            if this parameter is set to {@code true}, this object will be
     *            refreshed after the content stream has been appended
     * 
     * @return the updated object ID, or {@code null} if the repository did not
     *         return an object ID
     * 
     * @cmis 1.1
     */
    ObjectId appendContentStream(ContentStream contentStream, boolean isLastChunk, boolean refresh);

    /**
     * Removes the current content stream from the document and refreshes this
     * object afterwards. If the repository created a new version, this new
     * document is returned. Otherwise the current document is returned.
     * 
     * @return the updated document, or {@code null} if the repository did not
     *         return an object ID
     * 
     * @cmis 1.0
     */
    Document deleteContentStream();

    /**
     * Removes the current content stream from the document. If the repository
     * created a new version, the object ID of this new version is returned.
     * Otherwise the object ID of the current document is returned.
     * 
     * @param refresh
     *            if this parameter is set to {@code true}, this object will be
     *            refreshed after the content stream has been deleted
     * 
     * @return the updated document, or {@code null} if the repository did not
     *         return an object ID
     * 
     * @cmis 1.0
     */
    ObjectId deleteContentStream(boolean refresh);

    /**
     * Creates an {@link OutputStream} stream object that can be used to
     * overwrite the current content of the document.
     * 
     * @param filename
     *            the file name
     * @param mimeType
     *            the MIME type
     * @return the OutputStream object
     * 
     * @cmis 1.1
     */
    OutputStream createOverwriteOutputStream(String filename, String mimeType);

    /**
     * Creates an {@link OutputStream} stream object that can be used to
     * overwrite the current content of the document.
     * 
     * @param filename
     *            the file name
     * @param mimeType
     *            the MIME type
     * @param bufferSize
     *            buffer size in bytes
     * @return the OutputStream object
     * 
     * @cmis 1.1
     */
    OutputStream createOverwriteOutputStream(String filename, String mimeType, int bufferSize);

    /**
     * Creates an {@link OutputStream} stream object that can be used to append
     * content the current content of the document.
     * 
     * @return the OutputStream object
     * 
     * @cmis 1.1
     */
    OutputStream createAppendOutputStream();

    /**
     * Creates an {@link OutputStream} stream object that can be used to append
     * content the current content of the document.
     * 
     * @param bufferSize
     *            buffer size in bytes
     * @return the OutputStream object
     * 
     * @cmis 1.1
     */
    OutputStream createAppendOutputStream(int bufferSize);

    // versioning service

    /**
     * Checks out the document and returns the object ID of the PWC (private
     * working copy).
     * 
     * @return PWC object ID
     * 
     * @cmis 1.0
     */
    ObjectId checkOut();

    /**
     * If this is a PWC (private working copy) the check out will be reversed.
     * If this is not a PWC it an exception will be thrown.
     * 
     * @cmis 1.0
     */
    void cancelCheckOut();

    /**
     * If this is a PWC (private working copy) it performs a check in. If this
     * is not a PWC it an exception will be thrown.
     * 
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @return new document ID
     * 
     * @cmis 1.0
     */
    ObjectId checkIn(boolean major, Map<String, ?> properties, ContentStream contentStream, String checkinComment,
            List<Policy> policies, List<Ace> addAces, List<Ace> removeAces);

    /**
     * If this is a PWC (private working copy) it performs a check in. If this
     * is not a PWC it an exception will be thrown.
     * 
     * The stream in {@code contentStream} is consumed but not closed by this
     * method.
     * 
     * @return new document ID
     * 
     * @cmis 1.0
     */
    ObjectId checkIn(boolean major, Map<String, ?> properties, ContentStream contentStream, String checkinComment);

    /**
     * Fetches the latest major or minor version of this document.
     * 
     * @param major
     *            if {@code true} the latest major version will be returned,
     *            otherwise the very last version will be returned
     * 
     * @return the latest document object
     * 
     * @cmis 1.0
     */
    Document getObjectOfLatestVersion(boolean major);

    /**
     * Fetches the latest major or minor version of this document using the
     * given {@link OperationContext}.
     * 
     * @param major
     *            if {@code true} the latest major version will be returned,
     *            otherwise the very last version will be returned
     * 
     * @return the latest document object
     * 
     * @cmis 1.0
     */
    Document getObjectOfLatestVersion(boolean major, OperationContext context);

    /**
     * Fetches all versions of this document.
     * <p>
     * The behavior of this method is undefined if the document is not
     * versionable and can be different for each repository.
     * 
     * @return all versions of the version series, sorted by
     *         {@code cmis:creationDate} descending and preceded by the PWC, if
     *         one exists, not {@code null}
     * 
     * @cmis 1.0
     */
    List<Document> getAllVersions();

    /**
     * Fetches all versions of this document using the given
     * {@link OperationContext}.
     * <p>
     * The behavior of this method is undefined if the document is not
     * versionable and can be different for each repository.
     * 
     * @return all versions of the version series, sorted by
     *         {@code cmis:creationDate} descending and preceded by the PWC, if
     *         one exists, not {@code null}
     * 
     * @cmis 1.0
     */
    List<Document> getAllVersions(OperationContext context);

    /**
     * Creates a copy of this document, including content.
     * 
     * @param targetFolderId
     *            the ID of the target folder, {@code null} to create an unfiled
     *            document
     * 
     * @return the new document object
     * 
     * @cmis 1.0
     */
    Document copy(ObjectId targetFolderId);

    /**
     * Creates a copy of this document, including content.
     * 
     * @param targetFolderId
     *            the ID of the target folder, {@code null} to create an unfiled
     *            document
     * 
     * @return the new document object or {@code null} if the parameter
     *         {@code context} was set to {@code null}
     * 
     * @cmis 1.0
     */
    Document copy(ObjectId targetFolderId, Map<String, ?> properties, VersioningState versioningState,
            List<Policy> policies, List<Ace> addACEs, List<Ace> removeACEs, OperationContext context);

}
