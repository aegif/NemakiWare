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
package org.apache.chemistry.opencmis.workbench.actions;

import java.awt.BorderLayout;
import java.awt.Color;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.ActionPanel;

public class RemoveObjectFromFolderPanel extends ActionPanel {

    private static final long serialVersionUID = 1L;

    private JTextField folderField;

    public RemoveObjectFromFolderPanel(ClientModel model) {
        super("Remove Object From Folder", "Remove", model);
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            if (getClientModel().getCurrentFolder() != null) {
                folderField.setText(getClientModel().getCurrentFolder().getId());
            } else {
                folderField.setText("");
            }
        }

        super.setVisible(visible);
    }

    @Override
    protected void createActionComponents() {
        JPanel folderPanel = new JPanel(new BorderLayout());
        folderPanel.setBackground(Color.WHITE);

        folderPanel.add(new JLabel("Folder Id:"), BorderLayout.LINE_START);

        folderField = new JTextField(30);
        folderPanel.add(folderField, BorderLayout.CENTER);

        addActionComponent(folderPanel);
    }

    @Override
    public boolean isAllowed() {
        if (!(getObject() instanceof FileableCmisObject)) {
            return false;
        }

        if ((getObject().getAllowableActions() == null)
                || (getObject().getAllowableActions().getAllowableActions() == null)) {
            return true;
        }

        return getObject().hasAllowableAction(Action.CAN_REMOVE_OBJECT_FROM_FOLDER);
    }

    @Override
    public void doAction() {
        ObjectId folderId = getClientModel().getClientSession().getSession().createObjectId(folderField.getText());
        ((FileableCmisObject) getObject()).removeFromFolder(folderId);

        reload(true);
    }
}
