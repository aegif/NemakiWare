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
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.workbench.icons.NewPolicyIcon;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.CreateDialog;
import org.apache.chemistry.opencmis.workbench.worker.LoadFolderWorker;
import org.apache.chemistry.opencmis.workbench.worker.LoadObjectWorker;

public class CreatePolicyDialog extends CreateDialog {

    private static final long serialVersionUID = 1L;

    private JRadioButton unfiledButton;
    private JRadioButton currentPathButton;
    private JTextField nameField;
    private JTextField policyTextField;
    private JComboBox<ObjectTypeItem> typeBox;

    public CreatePolicyDialog(Frame owner, ClientModel model) {
        super(owner, "Create Policy", model);
        createGUI();
    }

    private void createGUI() {
        final CreatePolicyDialog thisDialog = this;

        boolean hasCurrentFolder = getClientModel().getCurrentFolder() != null;

        unfiledButton = new JRadioButton("create unfiled");
        unfiledButton.setSelected(!hasCurrentFolder);

        currentPathButton = new JRadioButton("create in the current folder: "
                + (hasCurrentFolder ? getClientModel().getCurrentFolder().getPath() : ""));
        currentPathButton.setSelected(hasCurrentFolder);
        currentPathButton.setEnabled(hasCurrentFolder);

        ButtonGroup filedGroup = new ButtonGroup();
        filedGroup.add(unfiledButton);
        filedGroup.add(currentPathButton);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.add(unfiledButton);
        buttonPanel.add(currentPathButton);

        createRow("", buttonPanel, 0);

        nameField = new JTextField(60);
        createRow("Name:", nameField, 1);

        ObjectTypeItem[] types = getTypes(BaseTypeId.CMIS_POLICY.value());
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

        createRow("Type:", typeBox, 2);

        policyTextField = new JTextField(60);
        createRow("Policy Text:", policyTextField, 3);

        JButton createButton = new JButton("Create Policy", new NewPolicyIcon(ClientHelper.BUTTON_ICON_SIZE,
                ClientHelper.BUTTON_ICON_SIZE));
        createButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                String name = nameField.getText();
                String policyText = policyTextField.getText();
                ObjectType type = ((ObjectTypeItem) typeBox.getSelectedItem()).getObjectType();

                try {
                    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                    ObjectId objectId = getClientModel().createPolicy(name, type.getId(), policyText,
                            getMandatoryOrOnCreatePropertyValues(type), unfiledButton.isSelected());

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
