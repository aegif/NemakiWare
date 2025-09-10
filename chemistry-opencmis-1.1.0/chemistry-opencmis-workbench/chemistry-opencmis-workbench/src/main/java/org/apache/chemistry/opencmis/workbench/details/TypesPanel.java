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
package org.apache.chemistry.opencmis.workbench.details;

import javax.swing.SwingUtilities;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.model.ClientModelEvent;
import org.apache.chemistry.opencmis.workbench.model.ObjectListener;
import org.apache.chemistry.opencmis.workbench.types.TypeSplitPane;

public class TypesPanel extends TypeSplitPane implements ObjectListener {

    private static final long serialVersionUID = 1L;

    public TypesPanel(ClientModel model) {
        super(model);
        model.addObjectListener(this);
    }

    @Override
    public void objectLoaded(ClientModelEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                CmisObject object = getClientModel().getCurrentObject();

                if (object == null) {
                    setType(null);
                } else {
                    setType(object.getType());
                }
            }
        });
    }
}
