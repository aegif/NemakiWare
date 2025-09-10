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

import java.io.FileNotFoundException;

import javax.swing.JCheckBox;
import javax.swing.JTextField;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.ActionPanel;

public class AppendContentStreamPanel extends ActionPanel {

    private static final long serialVersionUID = 1L;

    private JTextField filenameField;
    private JCheckBox isLastChunkBox;

    public AppendContentStreamPanel(ClientModel model) {
        super("Append Content Stream", "Append Content Stream", model);
    }

    @Override
    protected void createActionComponents() {
        filenameField = new JTextField(30);
        addActionComponent(createFilenamePanel(filenameField));

        isLastChunkBox = new JCheckBox("is last chunk", false);
        addActionComponent(isLastChunkBox);
    }

    @Override
    public boolean isAllowed() {
        if (getCmisVersion() == CmisVersion.CMIS_1_0) {
            return false;
        }

        if ((getObject() == null) || !(getObject() instanceof Document)) {
            return false;
        }

        if ((getObject().getAllowableActions() == null)
                || (getObject().getAllowableActions().getAllowableActions() == null)) {
            return true;
        }

        return getObject().hasAllowableAction(Action.CAN_SET_CONTENT_STREAM);
    }

    @Override
    public void doAction() throws FileNotFoundException {
        ContentStream content = getClientModel().createContentStream(filenameField.getText());

        try {
            ((Document) getObject()).appendContentStream(content, isLastChunkBox.isSelected(), false);
        } finally {
            IOUtils.closeQuietly(content);
        }

        reload(true);
    }
}