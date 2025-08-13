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
package org.apache.chemistry.opencmis.workbench.worker;

import java.awt.Component;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Rendition;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.MimeTypes;

public abstract class OpenContentWorker extends InfoWorkbenchWorker {

    private Component comp;
    private CmisObject cmisObject;
    private String streamId;
    private Rendition rendition;
    private ContentStream contentStream;
    private String filename;

    public OpenContentWorker(Component comp, Document doc) {
        this(comp, doc, null);
    }

    public OpenContentWorker(Component comp, CmisObject cmisObject, String streamId) {
        super(comp);
        this.comp = comp;
        this.cmisObject = cmisObject;
        this.streamId = streamId;
        this.rendition = null;
        this.contentStream = null;
    }

    @Override
    protected String getTitle() {
        return "Opening Content";
    }

    @Override
    protected String getMessage() {
        return "Opening Content " + (filename != null ? "'" + filename + "'" : "") + "...";
    }

    public Component getComponent() {
        return comp;
    }

    public String getFilename() {
        return filename;
    }

    public ContentStream getContentStream() {
        return contentStream;
    }

    @Override
    public void executeTask() {
        if (cmisObject == null) {
            return;
        }

        if (!(cmisObject instanceof Document)) {
            if (streamId == null) {
                return;
            }

            List<Rendition> renditions = cmisObject.getRenditions();
            if (renditions == null) {
                return;
            }

            for (Rendition rendition : renditions) {
                if (streamId.equals(rendition.getStreamId())) {
                    this.rendition = rendition;
                }
            }

            if (rendition == null) {
                return;
            }
        }

        super.executeTask();
    }

    public void doWork() throws Exception {
        if (cmisObject instanceof Document) {
            contentStream = ((Document) cmisObject).getContentStream(streamId);
        } else if (rendition != null) {
            contentStream = rendition.getContentStream();
        }

        filename = contentStream.getFileName();
        if (filename == null || filename.length() == 0) {
            if (cmisObject instanceof Document) {
                filename = ((Document) cmisObject).getContentStreamFileName();
            }
        }
        if (filename == null || filename.length() == 0) {
            filename = cmisObject.getName();
        }
        if (filename == null || filename.length() == 0) {
            filename = "content";
        }

        String ext = MimeTypes.getExtension(contentStream.getMimeType());
        if (ext.length() > 0 && !filename.endsWith(ext)) {
            filename = filename + ext;
        }
    }

    @Override
    protected Object doInBackground() throws Exception {
        doWork();
        return null;
    }

    @Override
    protected void done() {
        super.done();

        if (!isCancelled() && contentStream != null) {
            processStream(comp, contentStream, filename);
        } else {
            IOUtils.closeQuietly(contentStream);
            handleError(comp, filename);
        }
    }

    /**
     * Processes and closes the stream.
     */
    protected abstract void processStream(Component comp, ContentStream contentStream, String filename);

    protected void handleError(Component comp, String filename) {
    }
}
