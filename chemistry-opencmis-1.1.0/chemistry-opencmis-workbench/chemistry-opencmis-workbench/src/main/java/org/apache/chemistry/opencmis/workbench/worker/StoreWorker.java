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
 */package org.apache.chemistry.opencmis.workbench.worker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.workbench.LoggingInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StoreWorker extends WorkbenchWorker<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(StoreWorker.class);

    private static final int BUFFER_SIZE = 64 * 1024;

    private ContentStream contentStream;
    private File file;
    private String name;
    private boolean success = false;

    public StoreWorker(ContentStream contentStream, File file, String name) {
        super();
        this.contentStream = contentStream;
        this.file = file;
        this.name = name;

        setProgressMax(contentStream.getLength());
    }

    @Override
    protected String getTitle() {
        return "Downloading";
    }

    @Override
    protected String getMessage() {
        return "<html>Downloading '" + name + "' to '" + file.getPath() + "'...";
    }

    @Override
    protected boolean hasDialog() {
        return true;
    }

    public void doWork() throws Exception {
        long length = 0;

        OutputStream out = null;
        InputStream in = null;
        try {
            out = new FileOutputStream(file);

            if (contentStream != null && contentStream.getStream() != null) {
                in = new LoggingInputStream(contentStream.getStream(), name);

                int b;
                byte[] buffer = new byte[BUFFER_SIZE];

                setProgress(0);
                while ((b = in.read(buffer)) > -1) {
                    if (isCancelled()) {
                        break;
                    }

                    out.write(buffer, 0, b);

                    length += b;
                    publish(length);
                }
            }

            success = !isCancelled();
        } finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }

        if (isCancelled()) {
            if (!file.delete()) {
                LOG.error("Could not delete '{}'.", file.getAbsolutePath());
            }
        }
    }

    @Override
    protected Object doInBackground() throws Exception {
        doWork();
        return null;
    }

    @Override
    protected void finializeTask() {
    }

    @Override
    protected void done() {
        super.done();

        if (success) {
            processFile(file);
        } else {
            handleError();
        }
    }

    protected void processFile(File file) {
    }

    protected void handleError() {
    }
}
