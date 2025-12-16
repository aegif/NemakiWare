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

import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;

import org.apache.chemistry.opencmis.workbench.model.ClientModel;

public class DetailsTabs extends JTabbedPane {

    private static final long serialVersionUID = 1L;

    private final ClientModel model;

    private ObjectPanel objectPanel;
    private ActionsPanel actionsPanel;
    private PropertyTable propertyTable;
    private RelationshipTable relationshipTable;
    private RenditionTable renditionTable;
    private ACLTable aclTable;
    private PolicyTable policyTable;
    private VersionTable versionTable;
    private TypesPanel typesPanel;
    private ExtensionsPanel extensionsPanel;

    public DetailsTabs(ClientModel model) {
        super(SwingConstants.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);

        this.model = model;
        createGUI();
    }

    private void createGUI() {
        objectPanel = new ObjectPanel(model);
        actionsPanel = new ActionsPanel(model);
        propertyTable = new PropertyTable(model);
        relationshipTable = new RelationshipTable(model);
        renditionTable = new RenditionTable(model);
        aclTable = new ACLTable(model);
        policyTable = new PolicyTable(model);
        versionTable = new VersionTable(model);
        typesPanel = new TypesPanel(model);
        extensionsPanel = new ExtensionsPanel(model);

        addTab("Object", new JScrollPane(objectPanel));
        addTab("Actions", new JScrollPane(actionsPanel));
        addTab("Properties", new JScrollPane(propertyTable));
        addTab("Relationships", new JScrollPane(relationshipTable));
        addTab("Renditions", new JScrollPane(renditionTable));
        addTab("ACL", new JScrollPane(aclTable));
        addTab("Policies", new JScrollPane(policyTable));
        addTab("Versions", new JScrollPane(versionTable));
        addTab("Type", new JScrollPane(typesPanel));
        addTab("Extensions", new JScrollPane(extensionsPanel));
    }
}
