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
package org.apache.chemistry.opencmis.workbench.types;

import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;

public class TypeSplitPane extends JSplitPane {

    private static final long serialVersionUID = 1L;

    public static final int ID_COLUMN = 1;

    private final ClientModel model;

    private TypeDefinitionInfoPanel typePanel;

    private PropertyDefinitionsSplitPane propertyDefinitionSplitPane;

    public TypeSplitPane(ClientModel model) {
        super(JSplitPane.HORIZONTAL_SPLIT);

        this.model = model;

        createGUI();
    }

    protected ClientModel getClientModel() {
        return model;
    }

    private void createGUI() {
        typePanel = new TypeDefinitionInfoPanel(model);
        propertyDefinitionSplitPane = new PropertyDefinitionsSplitPane(model);

        setLeftComponent(new JScrollPane(typePanel));
        setRightComponent(propertyDefinitionSplitPane);

        setDividerLocation(0.3);
    }

    public void setType(ObjectType type) {
        typePanel.setType(type);
        propertyDefinitionSplitPane.setType(type);
    }
}
