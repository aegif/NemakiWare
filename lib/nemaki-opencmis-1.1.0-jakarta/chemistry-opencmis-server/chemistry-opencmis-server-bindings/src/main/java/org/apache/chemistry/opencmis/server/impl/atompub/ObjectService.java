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
package org.apache.chemistry.opencmis.server.impl.atompub;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.MimeHelper;
import org.apache.chemistry.opencmis.commons.impl.ReturnVersion;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.ObjectInfo;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

/**
 * Object Service operations.
 */
public class ObjectService {

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Create.
     */
    public static class Create extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_ID);
            String sourceFolderId = getStringParameter(request, Constants.PARAM_SOURCE_FOLDER_ID);
            VersioningState versioningState = getEnumParameter(request, Constants.PARAM_VERSIONIG_STATE,
                    VersioningState.class);

            TempStoreOutputStreamFactory streamFactory = (TempStoreOutputStreamFactory) context
                    .get(CallContext.STREAM_FACTORY);
            AtomEntryParser parser = new AtomEntryParser(streamFactory);
            parser.setIgnoreAtomContentSrc(true); // needed for some clients
            parser.parse(request.getInputStream());

            // execute
            String newObjectId = null;
            String objectId = parser.getId();
            try {
                if (stopBeforeService(service)) {
                    return;
                }

                if (objectId == null) {
                    // create
                    ContentStream contentStream = parser.getContentStream();
                    newObjectId = service.create(repositoryId, parser.getProperties(), folderId, contentStream,
                            versioningState, parser.getPolicyIds(), null);
                } else {
                    if (sourceFolderId == null || sourceFolderId.trim().length() == 0) {
                        // addObjectToFolder
                        service.addObjectToFolder(repositoryId, objectId, folderId, null, null);
                        newObjectId = objectId;
                    } else {
                        // move
                        Holder<String> objectIdHolder = new Holder<String>(objectId);
                        service.moveObject(repositoryId, objectIdHolder, folderId, sourceFolderId, null);
                        newObjectId = objectIdHolder.getValue();
                    }
                }

                if (stopAfterService(service)) {
                    return;
                }
            } finally {
                parser.release();
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, newObjectId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            ObjectData object = objectInfo.getObject();
            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            // set headers
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType(Constants.MEDIATYPE_ENTRY);
            response.setHeader("Location", compileUrl(baseUrl, RESOURCE_ENTRY, newObjectId));

            // write XML
            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }
    }

    /**
     * Create relationship.
     */
    public static class CreateRelationship extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            TempStoreOutputStreamFactory streamFactory = (TempStoreOutputStreamFactory) context
                    .get(CallContext.STREAM_FACTORY);
            AtomEntryParser parser = new AtomEntryParser(request.getInputStream(), streamFactory);

            // execute
            String newObjectId = null;
            try {
                if (stopBeforeService(service)) {
                    return;
                }

                newObjectId = service.createRelationship(repositoryId, parser.getProperties(), parser.getPolicyIds(),
                        null, null, null);

                if (stopAfterService(service)) {
                    return;
                }
            } finally {
                parser.release();
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, newObjectId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            ObjectData object = objectInfo.getObject();
            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            // set headers
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType(Constants.MEDIATYPE_ENTRY);
            response.setHeader("Location", compileUrl(baseUrl, RESOURCE_ENTRY, newObjectId));

            // write XML
            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }
    }

    /**
     * Delete object.
     */
    public static class DeleteObject extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            Boolean allVersions = getBooleanParameter(request, Constants.PARAM_ALL_VERSIONS);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            service.deleteObjectOrCancelCheckOut(repositoryId, objectId, allVersions, null);

            if (stopAfterService(service)) {
                return;
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    /**
     * Delete content stream.
     */
    public static class DeleteContentStream extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            String changeToken = getStringParameter(request, Constants.PARAM_CHANGE_TOKEN);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            service.deleteContentStream(repositoryId, new Holder<String>(objectId), changeToken == null ? null
                    : new Holder<String>(changeToken), null);

            if (stopAfterService(service)) {
                return;
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    /**
     * Set or append content stream.
     */
    public static class SetOrAppendContentStream extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            String changeToken = getStringParameter(request, Constants.PARAM_CHANGE_TOKEN);
            Boolean appendFlag = getBooleanParameter(request, Constants.PARAM_APPEND);
            Boolean overwriteFlag = getBooleanParameter(request, Constants.PARAM_OVERWRITE_FLAG);
            Boolean isLastChunk = getBooleanParameter(request, Constants.PARAM_IS_LAST_CHUNK);

            ContentStreamImpl contentStream = new ContentStreamImpl();
            contentStream.setStream(request.getInputStream());
            contentStream.setMimeType(request.getHeader("Content-Type"));
            String lengthStr = request.getHeader("Content-Length");
            if (lengthStr != null) {
                try {
                    contentStream.setLength(new BigInteger(lengthStr));
                } catch (NumberFormatException e) {
                    // invalid content length -> ignore
                }
            }
            String contentDisposition = request.getHeader(MimeHelper.CONTENT_DISPOSITION);
            if (contentDisposition != null) {
                contentStream.setFileName(MimeHelper.decodeContentDispositionFilename(contentDisposition));
            }

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            Holder<String> objectIdHolder = new Holder<String>(objectId);
            if (Boolean.TRUE.equals(appendFlag)) {
                service.appendContentStream(repositoryId, objectIdHolder, changeToken == null ? null
                        : new Holder<String>(changeToken), contentStream, (Boolean.TRUE.equals(isLastChunk) ? true
                        : false), null);
            } else {
                service.setContentStream(repositoryId, objectIdHolder, overwriteFlag, changeToken == null ? null
                        : new Holder<String>(changeToken), contentStream, null);
            }

            if (stopAfterService(service)) {
                return;
            }

            // set headers
            String newObjectId = objectIdHolder.getValue() == null ? objectId : objectIdHolder.getValue();
            String contentLocation = compileUrl(compileBaseUrl(request, repositoryId), RESOURCE_CONTENT, newObjectId);
            String location = compileUrl(compileBaseUrl(request, repositoryId), RESOURCE_OBJECTBYID, newObjectId);

            // set status
            if (newObjectId.equals(objectId)) {
                if (Boolean.TRUE.equals(appendFlag)) {
                    // append stream: no new version -> OK
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentLength(0);
                } else {
                    if (!Boolean.FALSE.equals(overwriteFlag)) {
                        // set stream: no new version and overwrite ->
                        // OK: if the document had a stream,
                        // CREATED: if the document had no stream
                        // ... but we don't know, ... OK is more likely ...
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentLength(0);
                    } else {
                        // set stream: no new version and not overwrite ->
                        // CREATED (the document hasn't had a stream)
                        response.setStatus(HttpServletResponse.SC_CREATED);
                    }
                }
            } else {
                // new version created -> CREATED
                response.setStatus(HttpServletResponse.SC_CREATED);
            }
            response.setHeader("Content-Location", contentLocation);
            response.setHeader("Location", location);
        }
    }

    /**
     * Delete tree.
     */
    public static class DeleteTree extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String folderId = getStringParameter(request, Constants.PARAM_ID);
            Boolean allVersions = getBooleanParameter(request, Constants.PARAM_ALL_VERSIONS);
            UnfileObject unfileObjects = getEnumParameter(request, Constants.PARAM_UNFILE_OBJECTS, UnfileObject.class);
            Boolean continueOnFailure = getBooleanParameter(request, Constants.PARAM_CONTINUE_ON_FAILURE);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            FailedToDeleteData ftd = service.deleteTree(repositoryId, folderId, allVersions, unfileObjects,
                    continueOnFailure, null);

            if (stopAfterService(service)) {
                return;
            }

            if (ftd != null && isNotEmpty(ftd.getIds())) {
                // print ids that could not be deleted
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("text/plain");

                PrintWriter pw = response.getWriter();

                pw.println("Failed to delete the following objects:");
                for (String id : ftd.getIds()) {
                    pw.println(id);
                }

                pw.flush();

                return;
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }

    /**
     * getObject.
     */
    public static class GetObject extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            ReturnVersion returnVersion = getEnumParameter(request, Constants.PARAM_RETURN_VERSION, ReturnVersion.class);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            Boolean includePolicyIds = getBooleanParameter(request, Constants.PARAM_POLICY_IDS);
            Boolean includeAcl = getBooleanParameter(request, Constants.PARAM_ACL);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectData object = null;
            if (returnVersion == ReturnVersion.LATEST || returnVersion == ReturnVersion.LASTESTMAJOR) {
                object = service.getObjectOfLatestVersion(repositoryId, objectId, null,
                        returnVersion == ReturnVersion.LASTESTMAJOR, filter, includeAllowableActions,
                        includeRelationships, renditionFilter, includePolicyIds, includeAcl, null);
            } else {
                object = service.getObject(repositoryId, objectId, filter, includeAllowableActions,
                        includeRelationships, renditionFilter, includePolicyIds, includeAcl, null);
            }

            if (stopAfterService(service)) {
                return;
            }

            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, objectId);
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_ENTRY);

            // write XML
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }
    }

    /**
     * objectByPath URI template.
     */
    public static class GetObjectByPath extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String path = getStringParameter(request, Constants.PARAM_PATH);
            String filter = getStringParameter(request, Constants.PARAM_FILTER);
            Boolean includeAllowableActions = getBooleanParameter(request, Constants.PARAM_ALLOWABLE_ACTIONS);
            IncludeRelationships includeRelationships = getEnumParameter(request, Constants.PARAM_RELATIONSHIPS,
                    IncludeRelationships.class);
            String renditionFilter = getStringParameter(request, Constants.PARAM_RENDITION_FILTER);
            Boolean includePolicyIds = getBooleanParameter(request, Constants.PARAM_POLICY_IDS);
            Boolean includeAcl = getBooleanParameter(request, Constants.PARAM_ACL);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ObjectData object = service.getObjectByPath(repositoryId, path, filter, includeAllowableActions,
                    includeRelationships, renditionFilter, includePolicyIds, includeAcl, null);

            if (stopAfterService(service)) {
                return;
            }

            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, object.getId());
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_ENTRY);

            // write XML
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }
    }

    /**
     * Allowable Actions.
     */
    public static class GetAllowableActions extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            AllowableActions allowableActions = service.getAllowableActions(repositoryId, objectId, null);

            if (stopAfterService(service)) {
                return;
            }

            if (allowableActions == null) {
                throw new CmisRuntimeException("Allowable Actions is null!");
            }

            // set headers
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(Constants.MEDIATYPE_ALLOWABLEACTION);

            // write XML
            XMLStreamWriter writer = XMLUtils.createWriter(response.getOutputStream());
            XMLUtils.startXmlDocument(writer);
            XMLConverter.writeAllowableActions(writer, context.getCmisVersion(), true, allowableActions);
            XMLUtils.endXmlDocument(writer);
        }
    }

    /**
     * getContentStream.
     */
    public static class GetContentStream extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            String streamId = getStringParameter(request, Constants.PARAM_STREAM_ID);

            BigInteger offset = context.getOffset();
            BigInteger length = context.getLength();

            // execute
            if (stopBeforeService(service)) {
                return;
            }

            ContentStream content = service.getContentStream(repositoryId, objectId, streamId, offset, length, null);

            if (stopAfterService(service)) {
                return;
            }

            if (content == null || content.getStream() == null) {
                throw new CmisRuntimeException("Content stream is null!");
            }

            // set HTTP headers, if requested by the server implementation
            if (sendContentStreamHeaders(content, request, response)) {
                return;
            }

            String contentType = content.getMimeType();
            if (contentType == null) {
                contentType = Constants.MEDIATYPE_OCTETSTREAM;
            }

            // set headers
            if ((offset == null || offset.signum() == 0) && (length == null)) {
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);

                if (content.getBigLength() != null && content.getBigLength().signum() == 1) {
                    BigInteger firstBytePos = (offset == null ? BigInteger.ZERO : offset);
                    BigInteger lastBytePos = firstBytePos.add(content.getBigLength().subtract(BigInteger.ONE));

                    response.setHeader("Content-Range",
                            "bytes " + firstBytePos.toString() + "-" + lastBytePos.toString() + "/*");
                }
            }
            response.setContentType(contentType);

            if (content.getFileName() != null) {
                response.setHeader(MimeHelper.CONTENT_DISPOSITION,
                        MimeHelper.encodeContentDisposition(MimeHelper.DISPOSITION_ATTACHMENT, content.getFileName()));
            }

            // send content
            InputStream in = content.getStream();
            OutputStream out = response.getOutputStream();
            try {
                IOUtils.copy(in, out, BUFFER_SIZE);
                out.flush();
            } finally {
                IOUtils.closeQuietly(in);
            }
        }
    }

    /**
     * UpdateProperties.
     */
    public static class UpdateProperties extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            // get parameters
            String objectId = getStringParameter(request, Constants.PARAM_ID);
            Boolean checkin = getBooleanParameter(request, Constants.PARAM_CHECK_IN);
            String checkinComment = getStringParameter(request, Constants.PARAM_CHECKIN_COMMENT);
            Boolean major = getBooleanParameter(request, Constants.PARAM_MAJOR);

            TempStoreOutputStreamFactory streamFactory = (TempStoreOutputStreamFactory) context
                    .get(CallContext.STREAM_FACTORY);
            AtomEntryParser parser = new AtomEntryParser(request.getInputStream(), streamFactory);

            // execute
            Holder<String> objectIdHolder = new Holder<String>(objectId);

            try {
                if (checkin != null && checkin.booleanValue()) {
                    if (stopBeforeService(service)) {
                        return;
                    }

                    ContentStream contentStream = parser.getContentStream();
                    service.checkIn(repositoryId, objectIdHolder, major, parser.getProperties(), contentStream,
                            checkinComment, parser.getPolicyIds(), null, null, null);

                    if (stopAfterService(service)) {
                        return;
                    }
                } else {
                    Properties properties = parser.getProperties();
                    String changeToken = null;
                    if (properties != null) {
                        changeToken = extractChangeToken(properties);
                        if (changeToken != null) {
                            properties = new PropertiesImpl(properties);
                            ((PropertiesImpl) properties).removeProperty(PropertyIds.CHANGE_TOKEN);
                        }
                    }

                    if (changeToken == null) {
                        // not required by the CMIS specification
                        // -> keep for backwards compatibility with older
                        // OpenCMIS
                        // clients
                        changeToken = getStringParameter(request, Constants.PARAM_CHANGE_TOKEN);
                    }

                    if (stopBeforeService(service)) {
                        return;
                    }

                    service.updateProperties(repositoryId, objectIdHolder, changeToken == null ? null
                            : new Holder<String>(changeToken), properties, null);

                    if (stopAfterService(service)) {
                        return;
                    }
                }
            } finally {
                parser.release();
            }

            ObjectInfo objectInfo = service.getObjectInfo(repositoryId, objectIdHolder.getValue());
            if (objectInfo == null) {
                throw new CmisRuntimeException("Object Info is missing!");
            }

            ObjectData object = objectInfo.getObject();
            if (object == null) {
                throw new CmisRuntimeException("Object is null!");
            }

            // set headers
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);
            String location = compileUrl(baseUrl, RESOURCE_ENTRY, objectIdHolder.getValue());

            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType(Constants.MEDIATYPE_ENTRY);
            response.setHeader("Content-Location", location);
            response.setHeader("Location", location);

            // write XML
            AtomEntry entry = new AtomEntry();
            entry.startDocument(response.getOutputStream(), getNamespaces(service));
            writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, true,
                    context.getCmisVersion());
            entry.endDocument();
        }

        /**
         * Gets the change token from a property set.
         */
        private String extractChangeToken(Properties properties) {
            if (properties == null) {
                return null;
            }

            Map<String, PropertyData<?>> propertiesMap = properties.getProperties();
            if (propertiesMap == null) {
                return null;
            }

            PropertyData<?> changeTokenProperty = propertiesMap.get(PropertyIds.CHANGE_TOKEN);
            if (!(changeTokenProperty instanceof PropertyString)) {
                return null;
            }

            return ((PropertyString) changeTokenProperty).getFirstValue();
        }
    }

    /**
     * BulkUpdateProperties.
     */
    public static class BulkUpdateProperties extends AbstractAtomPubServiceCall {
        @Override
        public void serve(CallContext context, CmisService service, String repositoryId, HttpServletRequest request,
                HttpServletResponse response) throws Exception {
            assert context != null;
            assert service != null;
            assert repositoryId != null;
            assert request != null;
            assert response != null;

            TempStoreOutputStreamFactory streamFactory = (TempStoreOutputStreamFactory) context
                    .get(CallContext.STREAM_FACTORY);
            AtomEntryParser parser = new AtomEntryParser(streamFactory);
            parser.parse(request.getInputStream());

            // execute
            List<BulkUpdateObjectIdAndChangeToken> result = null;
            try {
                BulkUpdateImpl bulkUpdate = parser.getBulkUpdate();
                if (bulkUpdate == null) {
                    throw new CmisInvalidArgumentException("Bulk update data is missing!");
                }

                if (stopBeforeService(service)) {
                    return;
                }

                result = service.bulkUpdateProperties(repositoryId, bulkUpdate.getObjectIdAndChangeToken(),
                        bulkUpdate.getProperties(), bulkUpdate.getAddSecondaryTypeIds(),
                        bulkUpdate.getRemoveSecondaryTypeIds(), null);

                if (stopAfterService(service)) {
                    return;
                }
            } finally {
                parser.release();
            }

            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setContentType(Constants.MEDIATYPE_FEED);

            // write XML
            AtomFeed feed = new AtomFeed();
            feed.startDocument(response.getOutputStream(), getNamespaces(service));
            feed.startFeed(true);

            // write basic Atom feed elements
            feed.writeFeedElements(null, null, null, "Bulk Update Properties",
                    new GregorianCalendar(DateTimeHelper.GMT), null,
                    (result == null ? null : BigInteger.valueOf(result.size())));

            // write links
            UrlBuilder baseUrl = compileBaseUrl(request, repositoryId);

            feed.writeServiceLink(baseUrl.toString(), repositoryId);

            UrlBuilder selfLink = compileUrlBuilder(baseUrl, RESOURCE_BULK_UPDATE, null);
            feed.writeSelfLink(selfLink.toString(), null);

            // write entries
            if (result != null) {
                AtomEntry entry = new AtomEntry(feed.getWriter());
                for (BulkUpdateObjectIdAndChangeToken idAndToken : result) {
                    if (idAndToken == null || idAndToken.getId() == null) {
                        continue;
                    }

                    ObjectDataImpl object = new ObjectDataImpl();
                    PropertiesImpl properties = new PropertiesImpl();
                    object.setProperties(properties);

                    properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID,
                            idAndToken.getNewId() != null ? idAndToken.getNewId() : idAndToken.getId()));

                    if (idAndToken.getChangeToken() != null) {
                        properties.addProperty(new PropertyStringImpl(PropertyIds.CHANGE_TOKEN, idAndToken
                                .getChangeToken()));
                    }

                    writeObjectEntry(service, entry, object, null, repositoryId, null, null, baseUrl, false,
                            context.getCmisVersion());
                }
            }

            // we are done
            feed.endFeed();
            feed.endDocument();
        }
    }

}
