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

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.workbench.ClientHelper;
import org.apache.chemistry.opencmis.workbench.model.ClientModel;
import org.apache.chemistry.opencmis.workbench.swing.ActionPanel;

public class DownloadPanel extends ActionPanel {

    private static final long serialVersionUID = 1L;

    private JTextField streamIdField;

    public DownloadPanel(ClientModel model) {
        super("Download Content", "Download", model);
    }

    @Override
    protected void createActionComponents() {
        JPanel streamIdPanel = new JPanel(new BorderLayout());
        streamIdPanel.setBackground(Color.WHITE);

        streamIdPanel.add(new JLabel("Stream Id:"), BorderLayout.LINE_START);

        streamIdField = new JTextField(30);
        streamIdPanel.add(streamIdField, BorderLayout.CENTER);

        addActionComponent(streamIdPanel);
    }

    @Override
    public boolean isAllowed() {
        if ((getObject() == null) || !(getObject() instanceof Document)) {
            return false;
        }

        if ((getObject().getAllowableActions() == null)
                || (getObject().getAllowableActions().getAllowableActions() == null)) {
            return true;
        }

        return getObject().hasAllowableAction(Action.CAN_GET_CONTENT_STREAM);
    }

    @Override
    public void doAction() {
        String streamId = streamIdField.getText().trim();
        if (streamId.length() == 0) {
            streamId = null;
        }

        ClientHelper.download(this, getObject(), streamId);
    }
}
