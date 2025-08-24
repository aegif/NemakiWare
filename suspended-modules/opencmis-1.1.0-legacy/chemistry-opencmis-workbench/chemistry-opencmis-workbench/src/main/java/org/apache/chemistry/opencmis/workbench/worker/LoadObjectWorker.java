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

import org.apache.chemistry.opencmis.workbench.model.ClientModel;

public class LoadObjectWorker extends InfoWorkbenchWorker {

    private ClientModel model;
    private String id;

    public static void loadObject(Component comp, ClientModel model, String id) {
        (new LoadObjectWorker(comp, model, id)).executeTask();
    }

    public static void reloadObject(Component comp, ClientModel model) {
        if (model.getCurrentObject() != null) {
            (new LoadObjectWorker(comp, model, null)).executeTask();
        }
    }

    public static void reloadObject(Window parent, ClientModel model) {
        if (model.getCurrentObject() != null) {
            (new LoadObjectWorker(parent, model, null)).executeTask();
        }
    }

    public LoadObjectWorker(Component comp, ClientModel model, String id) {
        super(comp);

        this.model = model;
        this.id = id;
    }

    public LoadObjectWorker(Window parent, ClientModel model, String id) {
        super(parent);

        this.model = model;
        this.id = id;
    }

    @Override
    protected String getTitle() {
        return "Loading Object";
    }

    @Override
    protected String getMessage() {
        return "<html>Loading object '" + id + "'...";
    }

    @Override
    protected Object doInBackground() throws Exception {
        if (id == null) {
            model.reloadObject();
        } else {
            model.loadObject(id);
        }
        return null;
    }
}
