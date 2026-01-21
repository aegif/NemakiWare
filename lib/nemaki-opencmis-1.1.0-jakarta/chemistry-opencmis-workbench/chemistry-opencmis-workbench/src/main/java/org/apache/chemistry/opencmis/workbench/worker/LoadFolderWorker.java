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
import java.awt.Window;

import javax.swing.SwingUtilities;

import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;

public class LoadFolderWorker extends InfoWorkbenchWorker {

    final private ClientModel model;
    private String id;
    private boolean byPath;

    public static void loadFolder(Component comp, ClientModel model, String id) {
        loadFolder(SwingUtilities.getWindowAncestor(comp), model, id);
    }

    public static void loadFolder(Window parent, ClientModel model, String id) {
        (new LoadFolderWorker(parent, model, id)).executeTask();
    }

    public static void loadFolderById(Component comp, ClientModel model, String id) {
        loadFolderById(SwingUtilities.getWindowAncestor(comp), model, id);
    }

    public static void loadFolderById(Window parent, ClientModel model, String id) {
        (new LoadFolderWorker(parent, model, id, false)).executeTask();
    }

    public static void loadFolderByPath(Component comp, ClientModel model, String path) {
        loadFolderByPath(SwingUtilities.getWindowAncestor(comp), model, path);
    }

    public static void loadFolderByPath(Window parent, ClientModel model, String path) {
        (new LoadFolderWorker(parent, model, path, true)).executeTask();
    }

    public static void reloadFolder(Component comp, ClientModel model) {
        reloadFolder(SwingUtilities.getWindowAncestor(comp), model);
    }

    public static void reloadFolder(Window parent, ClientModel model) {
        if (model.getCurrentFolder() != null) {
            (new LoadFolderWorker(parent, model, model.getCurrentFolder().getId())).executeTask();
        }
    }

    public LoadFolderWorker(Component comp, ClientModel model, String id) {
        super(comp);

        this.model = model;
        this.id = id;
        this.byPath = false;

        if (this.id != null) {
            if (this.id.length() == 0) {
                this.id = "/";
            }

            byPath = id.charAt(0) == '/';
        }
    }

    public LoadFolderWorker(Window parent, ClientModel model, String id) {
        super(parent);

        this.model = model;
        this.id = id;
        this.byPath = false;

        if (this.id != null) {
            if (this.id.length() == 0) {
                this.id = "/";
            }

            byPath = id.charAt(0) == '/';
        }
    }

    public LoadFolderWorker(Window parent, ClientModel model, String id, boolean byPath) {
        super(parent);

        this.model = model;
        this.id = id;
        this.byPath = byPath;

        if (this.id != null) {
            if (this.byPath && this.id.length() == 0) {
                this.id = "/";
            }
        }
    }

    @Override
    protected String getTitle() {
        return "Loading Folder";
    }

    @Override
    protected String getMessage() {
        return "<html>Loading folder '" + id + "'...";
    }

    @Override
    public void executeTask() {
        if (id != null) {
            super.executeTask();
        }
    }

    @Override
    protected Object doInBackground() throws Exception {
        ObjectId objectId = model.loadFolder(id, byPath);
        if (!isCancelled()) {
            model.loadObject(objectId.getId());
        }
        return null;
    }
}
