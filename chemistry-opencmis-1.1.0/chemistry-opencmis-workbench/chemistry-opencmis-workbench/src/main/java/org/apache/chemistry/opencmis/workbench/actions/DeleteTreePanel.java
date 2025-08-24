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

import javax.swing.JCheckBox;
import javax.swing.JComboBox;

import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.ActionPanel;
import org.apache.chemistry.opencmis.workbench.worker.DeleteWorker;

public class DeleteTreePanel extends ActionPanel {

    private static final long serialVersionUID = 1L;

    private JCheckBox allVersionsBox;
    private JComboBox<UnfileObject> unfileObjectsBox;
    private JCheckBox continueOnFailureBox;

    public DeleteTreePanel(ClientModel model) {
        super("Delete Tree", "Delete", model);
    }

    @Override
    protected void createActionComponents() {
        allVersionsBox = new JCheckBox("delete all versions", true);
        addActionComponent(allVersionsBox);

        unfileObjectsBox = new JComboBox<UnfileObject>(new UnfileObject[] { UnfileObject.DELETE,
                UnfileObject.DELETESINGLEFILED, UnfileObject.UNFILE });
        unfileObjectsBox.setSelectedIndex(0);
        addActionComponent(unfileObjectsBox);

        continueOnFailureBox = new JCheckBox("continue on failure", true);
        addActionComponent(continueOnFailureBox);
    }

    @Override
    public boolean isAllowed() {
        if ((getObject() == null) || !(getObject() instanceof Folder)) {
            return false;
        }

        if ((getObject().getAllowableActions() == null)
                || (getObject().getAllowableActions().getAllowableActions() == null)) {
            return true;
        }

        return getObject().hasAllowableAction(Action.CAN_DELETE_TREE);
    }

    @Override
    public void doAction() {
        DeleteWorker worker = new DeleteWorker(this, (Folder) getObject(), allVersionsBox.isSelected(),
                (UnfileObject) unfileObjectsBox.getSelectedItem(), continueOnFailureBox.isSelected()) {
            @Override
            protected void done() {
                super.done();
                
                reload(false);
            }
        };
        worker.executeTask();
    }
}
