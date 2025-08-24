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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Content;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RenditionUtil {
    
    private static final Logger LOG = LoggerFactory.getLogger(RenditionUtil.class.getName());
    private static final int BUFFER_SIZE = 65536;
    public static final String RENDITION_MIME_TYPE_JPEG = "image/jpeg";
    public static final String RENDITION_MIME_TYPE_PNG = "image/png";
    public static final String RENDITION_SUFFIX = "-rendition";
    public static final int THUMBNAIL_SIZE = 100;
    public static final int ICON_SIZE = 32;

    public static boolean hasRendition(StoredObject so, String user) {
        if (so instanceof Folder) {
            return true;
        } else if (so instanceof Content) {
            ContentStream contentStream = ((Content)so).getContent();
            if (null == contentStream) {
                return false;
            }

            String mimeType = contentStream.getMimeType();

            return isImage(mimeType) || isAudio(mimeType) || isVideo(mimeType) || isPDF(mimeType) || isPowerpoint(mimeType)
                    || isExcel(mimeType) || isWord(mimeType) || isHtml(mimeType) || isPlainText(mimeType);
        } else {
            return false;
        }
    }
    
    private static boolean isImage(String mimeType) {
        return mimeType.startsWith("image/");
    }

    private static boolean isWord(String mimeType) {
        return mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || mimeType.equals("application/ms-word");
    }

    private static boolean isExcel(String mimeType) {
        return mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || mimeType.equals("application/vnd.ms-excel");
    }

    private static boolean isPowerpoint(String mimeType) {
        return mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.slideshow")
                || mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                || mimeType.equals("application/vnd.ms-powerpoint");
    }

    private static boolean isPDF(String mimeType) {
        return mimeType.equals("application/pdf");
    }

    private static boolean isHtml(String mimeType) {
        return mimeType.equals("text/html");
    }

    private static boolean isAudio(String mimeType) {
        return mimeType.startsWith("audio/");
    }

    private static boolean isVideo(String mimeType) {
        return mimeType.startsWith("video/");
    }

    private static boolean isPlainText(String mimeType) {
        return mimeType.equals("text/plain");
    }

    public static ContentStream getIconFromResourceDir(String name) throws IOException {

        InputStream imageStream = StoredObjectImpl.class.getResourceAsStream(name);
        ByteArrayOutputStream ba = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int noBytesRead = 0;

        try {
            while ((noBytesRead = imageStream.read(buffer)) >= 0) {
                ba.write(buffer, 0, noBytesRead);
            }
        } finally {
            IOUtils.closeQuietly(ba);
            IOUtils.closeQuietly(imageStream);
        }

        ContentStreamDataImpl content = new ContentStreamDataImpl(0);
        content.setFileName(name);
        content.setMimeType("image/png");
        content.setContent(new ByteArrayInputStream(ba.toByteArray()));
        return content;
    }

    public static boolean testRenditionFilterForImage(String[] formats) {
        if (formats.length == 1 && null != formats[0] && formats[0].equals("cmis:none")) {
            return false;
        } else {
            return arrayContainsString(formats, "*") || arrayContainsString(formats, "image/*")
                    || arrayContainsString(formats, "image/jpeg");
        }
    }

    private static boolean arrayContainsString(String[] formats, String val) {
        for (String s : formats) {
            if (val.equals(s)) {
                return true;
            }
        }
        return false;
    }

    public static ContentStream getRenditionContent(StoredObject so, String streamId, long offset, long length) {
        if (so instanceof Folder) {
            return RenditionUtil.getFolderRenditionContent(streamId, offset, length);
        }
        if (!(so instanceof Content)) {
            throw new CmisInvalidArgumentException("Only objects with content can have a rendition");
        }
        ContentStream contentStream = ((Content)so).getContent();

        if (null == contentStream) {
            return null;
        }

        String mimeType = contentStream.getMimeType();

        try {
            if (isImage(mimeType)) {
                ImageThumbnailGenerator generator = new ImageThumbnailGenerator(contentStream.getStream());
                return generator.getRendition(THUMBNAIL_SIZE, 0);
            } else if (isAudio(mimeType)) {
                return getIconFromResourceDir("/audio-x-generic.png");
            } else if (isVideo(mimeType)) {
                return getIconFromResourceDir("/video-x-generic.png");
            } else if (isPDF(mimeType)) {
                return getIconFromResourceDir("/application-pdf.png");
            } else if (isWord(mimeType)) {
                return getIconFromResourceDir("/application-msword.png");
            } else if (isPowerpoint(mimeType)) {
                return getIconFromResourceDir("/application-vnd.ms-powerpoint.png");
            } else if (isExcel(mimeType)) {
                return getIconFromResourceDir("/application-vnd.ms-excel.png");
            } else if (isHtml(mimeType)) {
                return getIconFromResourceDir("/text-html.png");
            } else if (isPlainText(mimeType)) {
                return getIconFromResourceDir("/text-x-generic.png");
            } else {
                return null;
            }
        } catch (IOException e) {
            LOG.error("Failed to generate rendition: ", e);
            throw new CmisRuntimeException("Failed to generate rendition: " + e);
        }
    }
    
    private static ContentStream getFolderRenditionContent(String streamId, long offset, long length) {
        try {
            return getIconFromResourceDir("/folder.png");
        } catch (IOException e) {
            LOG.error("Failed to generate rendition: ", e);
            throw new CmisRuntimeException("Failed to generate rendition: " + e);
        }
    }
    
    public static List<RenditionData> getRenditions(StoredObject so, String renditionFilter, long maxItems, long skipCount) {

        String tokenizer = "[\\s;]";
        if (null == renditionFilter) {
            return null;
        }
        String[] formats = renditionFilter.split(tokenizer);
        boolean isImageRendition = RenditionUtil.testRenditionFilterForImage(formats);
        if (!(so instanceof Content) && !(so instanceof Folder)) {
            return null;
        }
        
        if (isImageRendition && hasRendition(so, null)) {
            String mimeType;
            if (so  instanceof Folder) {
                mimeType = "image/png";
            } else {
                ContentStream contentStream = ((Content)so).getContent();
                mimeType = contentStream.getMimeType();
            }

            List<RenditionData> renditions = new ArrayList<RenditionData>(1);
            RenditionDataImpl rendition = new RenditionDataImpl();
            if (mimeType.equals("image/jpeg")) {
                rendition.setBigHeight(BigInteger.valueOf(THUMBNAIL_SIZE));
                rendition.setBigWidth(BigInteger.valueOf(THUMBNAIL_SIZE));
                rendition.setMimeType(RENDITION_MIME_TYPE_JPEG);
            } else {
                rendition.setBigHeight(BigInteger.valueOf(ICON_SIZE));
                rendition.setBigWidth(BigInteger.valueOf(ICON_SIZE));
                rendition.setMimeType(RENDITION_MIME_TYPE_PNG);
            }
            rendition.setKind("cmis:thumbnail");
            rendition.setRenditionDocumentId(so.getId());
            rendition.setStreamId(so.getId() + RENDITION_SUFFIX);
            rendition.setBigLength(BigInteger.valueOf(-1L));
            rendition.setTitle(so.getName());
            renditions.add(rendition);
            return renditions;
        } else {
            return null;
        }
    }

}
