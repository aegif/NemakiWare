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

import javax.swing.JOptionPane;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;

public class DeleteWorker extends InfoWorkbenchWorker {

    // in
    private CmisObject cmisObject;
    private boolean allversions;
    private UnfileObject unfile;
    private boolean continueOnFailure;

    // out
    private List<String> failedIds;

    public DeleteWorker(Component comp, CmisObject cmisObject) {
        this(comp, cmisObject, true);
    }

    public DeleteWorker(Component comp, CmisObject cmisObject, boolean allversions) {
        super(comp);

        this.cmisObject = cmisObject;
        this.allversions = allversions;
        this.unfile = UnfileObject.DELETE;
        this.continueOnFailure = true;
    }

    public DeleteWorker(Component comp, Folder folder, boolean allversions, UnfileObject unfile,
            boolean continueOnFailure) {
        super(comp);

        this.cmisObject = folder;
        this.allversions = allversions;
        this.unfile = unfile;
        this.continueOnFailure = continueOnFailure;
    }

    @Override
    protected String getTitle() {
        return "Deleting";
    }

    @Override
    protected String getMessage() {
        return "<html>Deleting '" + cmisObject.getName() + "' (" + cmisObject.getId() + ")...";
    }

    @Override
    protected Object doInBackground() throws Exception {
        if (cmisObject instanceof Folder) {
            failedIds = ((Folder) cmisObject).deleteTree(allversions, unfile, continueOnFailure);
        } else {
            cmisObject.delete(allversions);
        }
        return null;
    }

    @Override
    protected void finializeTask() {
        if (failedIds != null && !failedIds.isEmpty()) {
            StringBuilder sb = new StringBuilder(128);

            sb.append("Delete tree failed! At least the following objects could not be deleted:\n");

            for (String id : failedIds) {
                sb.append('\n');
                sb.append(id);
            }

            JOptionPane.showMessageDialog(getParent(), sb.toString(), "Delete Tree", JOptionPane.ERROR_MESSAGE);
        }
    }
}
