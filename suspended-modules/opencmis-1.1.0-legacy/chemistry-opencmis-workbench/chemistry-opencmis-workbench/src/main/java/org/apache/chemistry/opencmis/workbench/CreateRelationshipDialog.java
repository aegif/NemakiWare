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
package org.apache.chemistry.opencmis.workbench;

import java.awt.Cursor;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.workbench.icons.NewRelationshipIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.CreateDialog;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class CreateRelationshipDialog extends CreateDialog {

    private static final long serialVersionUID = 1L;

    private JTextField nameField;
    private JComboBox<ObjectTypeItem> typeBox;
    private JTextField sourceIdField;
    private JTextField targetIdField;

    public CreateRelationshipDialog(Frame owner, ClientModel model) {
        super(owner, "Create Relationship", model);
        createGUI();
    }

    private void createGUI() {
        final CreateRelationshipDialog thisDialog = this;

        nameField = new JTextField(60);
        createRow("Name:", nameField, 0);

        ObjectTypeItem[] types = getTypes(BaseTypeId.CMIS_RELATIONSHIP.value());
        if (types.length == 0) {
            JOptionPane.showMessageDialog(this, "No creatable type!", "Creatable Types", JOptionPane.ERROR_MESSAGE);
            thisDialog.dispose();
            return;
        }

        typeBox = new JComboBox<ObjectTypeItem>(types);
        typeBox.setSelectedIndex(0);
        typeBox.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                TypeDefinition type = ((ObjectTypeItem) typeBox.getSelectedItem()).getObjectType();
                updateMandatoryOrOnCreateFields(type);
            }
        });

        ObjectTypeItem type = (ObjectTypeItem) typeBox.getSelectedItem();
        updateMandatoryOrOnCreateFields(type.getObjectType());

        createRow("Type:", typeBox, 1);

        sourceIdField = new JTextField(60);
        if (getClientModel().getCurrentObject() != null) {
            sourceIdField.setText(getClientModel().getCurrentObject().getId());
        }
        createRow("Source Id:", sourceIdField, 2);

        targetIdField = new JTextField(60);
        createRow("Target Id:", targetIdField, 3);

        JButton createButton = new JButton("Create Relationship", new NewRelationshipIcon(
                ClientHelper.BUTTON_ICON_SIZE, ClientHelper.BUTTON_ICON_SIZE));
        createButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                String name = nameField.getText();
                ObjectType type = ((ObjectTypeItem) typeBox.getSelectedItem()).getObjectType();
                String sourceId = sourceIdField.getText();
                String targetId = targetIdField.getText();

                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    ObjectId objectId = getClientModel().createRelationship(name, type.getId(), sourceId, targetId,
                            getMandatoryOrOnCreatePropertyValues(type));

                    if (objectId != null) {
                        LoadObjectWorker.loadObject(getOwner(), getClientModel(), objectId.getId());
                    }

                    thisDialog.setVisible(false);
                    thisDialog.dispose();
                } catch (Exception e) {
                    ClientHelper.showError(null, e);
                } finally {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    LoadFolderWorker.reloadFolder(getOwner(), getClientModel());
                }
            }
        });
        createActionRow("", createButton, 4);

        getRootPane().setDefaultButton(createButton);

        showDialog();
    }
}
