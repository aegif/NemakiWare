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
import java.io.File;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.workbench.ClientHelper;

public class TempFileContentWorker extends OpenContentWorker {

    private File tempFile;
    private StoreWorker storeWorker;

    public TempFileContentWorker(Component comp, Document doc) {
        super(comp, doc);
    }

    public TempFileContentWorker(Component comp, CmisObject cmisObject, String streamId) {
        super(comp, cmisObject, streamId);
    }

    public synchronized File getTempFile() {
        return tempFile;
    }

    public synchronized StoreWorker getStoreWorker() {
        return storeWorker;
    }

    public synchronized File createTempFile(String filename) {
        if (tempFile == null) {
            tempFile = ClientHelper.createTempFile(filename);
        }
        return tempFile;
    }

    @Override
    protected synchronized void processStream(Component comp, ContentStream contentStream, String filename) {
        storeWorker = new StoreWorker(contentStream, createTempFile(filename), filename) {
            @Override
            protected void processFile(File file) {
                processTempFile(file);
            }

            @Override
            protected void handleError() {
                TempFileContentWorker.this.handleError(getComponent(), getFilename());
            }
        };
        storeWorker.executeTask();
    }

    protected void processTempFile(File file) {
    }

    public File executeSync() throws Exception {
        doWork();

        StoreWorker storeWorker = new StoreWorker(getContentStream(), createTempFile(getFilename()), getFilename());
        storeWorker.doWork();

        return getTempFile();
    }
}
